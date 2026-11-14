#!/usr/bin/env python3
"""Drives the gallery app inside the CI emulator and writes ci-out/ (screenshots, UI dumps, logcat, results.md).

Every scenario is isolated: an exception or a failed check is recorded and the next scenario still runs.
Touches are injected with native `sendevent` chains (fast enough for double taps), not with `input`.
"""
import colorsys, io, json, os, re, subprocess, sys, time, traceback
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image

PKG = "com.ax.gallery"
ACT = PKG + "/com.iris.gallery.MainActivity"
OUT = Path("ci-out")
OUT.mkdir(exist_ok=True)
(OUT / "shots").mkdir(exist_ok=True)
(OUT / "dumps").mkdir(exist_ok=True)
(OUT / "logs").mkdir(exist_ok=True)
APK = next(Path("app/build/outputs/apk/optimized").glob("*.apk"))

results = []  # (name, status, detail)
metrics = {}


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def adb(*args, timeout=180, binary=False, check=False):
    p = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    if check and p.returncode != 0:
        raise RuntimeError(f"adb {args} failed: {p.stderr.decode(errors='replace')[:300]}")
    return p.stdout if binary else p.stdout.decode(errors="replace")


def sh(cmd, timeout=180):
    return adb("shell", cmd, timeout=timeout)


# ----------------------------------------------------------------------------------------------- device helpers
W = H = 0


def screen_size():
    global W, H
    m = re.search(r"(\d+)x(\d+)", sh("wm size").splitlines()[-1])
    W, H = int(m.group(1)), int(m.group(2))


class Touch:
    """Touch injection. mode "input" = Android's own `input` command (always works, slow for double taps);
    mode "ev<N>" = native sendevent chains through the virtual touchscreen (fast), variant N."""

    def __init__(self):
        self.mode = "input"
        self.dev = None
        self.maxx = self.maxy = 0
        self.tid = 100
        dev = None
        devs = {}
        for line in sh("getevent -pl").splitlines():
            m = re.match(r"add device \d+: (\S+)", line)
            if m:
                dev = m.group(1)
                devs[dev] = {}
            m = re.search(r"ABS_MT_POSITION_([XY])\s*:.*max (\d+)", line)
            if m and dev:
                devs[dev][m.group(1)] = int(m.group(2))
        for d, v in devs.items():
            if "X" in v and "Y" in v:
                self.dev, self.maxx, self.maxy = d, v["X"], v["Y"]
                break
        log("touch device:", self.dev, self.maxx, self.maxy)

    # ---- sendevent building blocks ----
    def _ev(self, t, c, v):
        return f"sendevent {self.dev} {t} {c} {v}"

    def _pt(self, x, y):
        rx = int(min(max(x, 0), W - 1) / W * self.maxx)
        ry = int(min(max(y, 0), H - 1) / H * self.maxy)
        c = [self._ev(3, 53, rx), self._ev(3, 54, ry)]
        if self.mode == "ev1":
            c += [self._ev(3, 58, 40), self._ev(3, 48, 6)]
        if self.mode == "ev2":
            c += [self._ev(3, 0, rx), self._ev(3, 1, ry)]
        return c

    def _down(self, x, y):
        self.tid += 1
        c = [self._ev(3, 57, self.tid)] + self._pt(x, y) + [self._ev(1, 330, 1)]
        if self.mode == "ev1":
            c.append(self._ev(1, 325, 1))
        return c + [self._ev(0, 0, 0)]

    def _move(self, x, y):
        return self._pt(x, y) + [self._ev(0, 0, 0)]

    def _up(self):
        c = [self._ev(3, 57, 4294967295), self._ev(1, 330, 0)]
        if self.mode == "ev1":
            c.append(self._ev(1, 325, 0))
        return c + [self._ev(0, 0, 0)]

    def _run(self, cmds):
        sh("; ".join(cmds))

    @property
    def native(self):
        return self.mode.startswith("ev")

    # ---- gestures ----
    def tap(self, x, y):
        if not self.native:
            sh(f"input tap {int(x)} {int(y)}")
            return
        self._run(self._down(x, y) + ["sleep 0.03"] + self._up())

    def double_tap(self, x, y, gap=0.06):
        if not self.native:
            self.taps(x, y, 2)
            return
        self._run(self._down(x, y) + ["sleep 0.03"] + self._up() + [f"sleep {gap}"] + self._down(x, y) + ["sleep 0.03"] + self._up())

    def taps(self, x, y, n, gap=0.12):
        if not self.native:
            # every `input` call boots a JVM (~0.3-0.6 s on the CI emulator) - far slower than the 300 ms double-tap window.
            # Start them as overlapping background jobs, staggered, so the taps *land* ~gap seconds apart.
            cmd = "; ".join(f"(input tap {int(x)} {int(y)} &) ; sleep 0.12" for _ in range(n)) + "; sleep 2.5"
            sh(cmd, timeout=60)
            return
        c = []
        for i in range(n):
            c += self._down(x, y) + ["sleep 0.03"] + self._up()
            if i < n - 1:
                c.append(f"sleep {gap}")
        self._run(c)

    def swipe(self, x1, y1, x2, y2, ms=300, steps=14):
        if not self.native:
            sh(f"input swipe {int(x1)} {int(y1)} {int(x2)} {int(y2)} {int(ms)}")
            return
        c = self._down(x1, y1)
        for i in range(1, steps + 1):
            f = i / steps
            c += [f"sleep {ms / 1000 / steps:.3f}"] + self._move(x1 + (x2 - x1) * f, y1 + (y2 - y1) * f)
        c += self._up()
        self._run(c)

    def hold(self, x, y, seconds=1.2):
        if not self.native:
            sh(f"input swipe {int(x)} {int(y)} {int(x)} {int(y)} {int(seconds * 1000)}")
            return
        self._run(self._down(x, y) + [f"sleep {seconds}"] + self._up())


touch = None


def shot(name):
    data = adb("exec-out", "screencap", "-p", binary=True, timeout=60)
    (OUT / "shots" / f"{name}.png").write_bytes(data)
    try:
        return Image.open(io.BytesIO(data)).convert("RGB")
    except Exception:
        return None


def dump(name):
    """UI hierarchy as a list of dicts. Returns [] when uiautomator cannot get an idle state."""
    sh("rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml", timeout=60)
    raw = adb("exec-out", "cat", "/sdcard/ui.xml", binary=True)
    if not raw.strip().startswith(b"<?xml") and b"<hierarchy" not in raw:
        return []
    (OUT / "dumps" / f"{name}.xml").write_bytes(raw)
    nodes = []
    try:
        for n in ET.fromstring(raw).iter("node"):
            b = re.findall(r"\d+", n.get("bounds", ""))
            if len(b) == 4:
                x1, y1, x2, y2 = map(int, b)
                nodes.append({"text": n.get("text", ""), "desc": n.get("content-desc", ""), "cx": (x1 + x2) // 2,
                              "cy": (y1 + y2) // 2, "box": (x1, y1, x2, y2), "click": n.get("clickable") == "true",
                              "cls": n.get("class", ""), "checkable": n.get("checkable") == "true",
                              "checked": n.get("checked") == "true"})
    except ET.ParseError:
        return []
    return nodes


def find(nodes, pattern):
    rx = re.compile(pattern, re.I)
    return [n for n in nodes if rx.search(n["text"]) or rx.search(n["desc"])]


def focus():
    t = sh("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
    return " | ".join(l.strip() for l in t.splitlines())[:300]


def foreground_ok():
    return PKG in focus()


def pic_diff(a, b):
    """Mean absolute difference between two screenshots (0..255)."""
    if a is None or b is None:
        return 0.0
    x = a.convert("L").resize((90, 200))
    y = b.convert("L").resize((90, 200))
    px, py = list(x.getdata()), list(y.getdata())
    return sum(abs(p - q) for p, q in zip(px, py)) / len(px)


def alive():
    return bool(sh(f"pidof {PKG}").strip())


def crash_lines():
    """Crashes / ANRs of OUR app only (the emulator's Google apps crash all the time and are not our business)."""
    t = adb("logcat", "-d", "-b", "crash") + adb("logcat", "-d", "-s", "ActivityManager:E")
    hits = [l for l in t.splitlines() if ("Process: " + PKG) in l or ("ANR in " + PKG) in l]
    return hits[:5]


def save_log(name):
    (OUT / "logs" / f"{name}.txt").write_text(adb("logcat", "-d", "-v", "time", timeout=120))


def gfx_reset():
    sh(f"dumpsys gfxinfo {PKG} reset")


def gfx_stats():
    t = sh(f"dumpsys gfxinfo {PKG}")

    def g(rx):
        m = re.search(rx, t)
        return m.group(1) if m else None

    return {
        "frames": int(g(r"Total frames rendered: (\d+)") or 0),
        "janky_pct": g(r"Janky frames: \d+ \(([\d.]+)%\)"),
        "p50_ms": g(r"50th percentile: (\d+)ms"),
        "p90_ms": g(r"90th percentile: (\d+)ms"),
        "p95_ms": g(r"95th percentile: (\d+)ms"),
        "p99_ms": g(r"99th percentile: (\d+)ms"),
    }


def record(name, ok, detail=""):
    results.append((name, "PASS" if ok else "FAIL", detail))
    log(("PASS " if ok else "FAIL ") + name, detail)


def scenario(fn):
    name = fn.__name__
    log("=== scenario", name)
    if "PermissionActivity" in focus():
        confirm_system_delete()
    if not foreground_ok():
        log("WARN app not in foreground before", name, "->", focus())
        results.append(("WARN", f"{name}: app was not in the foreground at the start", focus()))
        launch()
    adb("logcat", "-c")
    try:
        fn()
    except Exception as e:  # noqa
        record(name + " (exception)", False, f"{type(e).__name__}: {e}\n{traceback.format_exc()[-600:]}")
    if alive() and not foreground_ok():
        results.append(("WARN", f"{name}: app left the foreground during the scenario", focus()))
        shot(name + "_left_foreground")
    hits = crash_lines()
    # monkey presses Back at the root now and then, which closes the app normally: only a real crash report counts there
    if hits or (not alive() and not name.startswith("s09")):
        record(name + " -> app alive", False, "; ".join(hits) or "process is gone")
        save_log(name + "_crash")
        shot(name + "_after_crash")
        launch()  # bring it back for the next scenario
    else:
        save_log(name)


# ----------------------------------------------------------------------------------------------- app helpers
def dismiss_system_dialogs():
    """The CI emulator is slow: Android sometimes shows 'X isn't responding' for the launcher / Google apps. Press Wait."""
    for _ in range(3):
        nodes = dump("anr_check")
        if not find(nodes, r"isn't responding|not responding"):
            return
        w = find(nodes, r"^Wait$")
        if w:
            touch_input_tap(w[0]["cx"], w[0]["cy"])
        else:
            sh("input keyevent KEYCODE_BACK")
        time.sleep(1.0)


def touch_input_tap(x, y):
    sh(f"input tap {int(x)} {int(y)}")


def launch(wait=True):
    for attempt in range(3):
        sh(f"am force-stop {PKG}")
        time.sleep(0.5)
        out = sh(f"am start -W -n {ACT}", timeout=120)
        m = re.search(r"TotalTime: (\d+)", out)
        if m:
            metrics["cold_start_ms"] = int(m.group(1))
        if wait:
            time.sleep(7)  # the intro animation + first composition
        dismiss_system_dialogs()
        if foreground_ok():
            return
        log("WARN launch attempt", attempt + 1, "left the app in the background:", focus())


def hue_to_second(img, box=(0.25, 0.30, 0.75, 0.55)):
    """Which second of the colour-clock video is on screen (None when the picture is not a clean colour)."""
    w, h = img.size
    crop = img.crop((int(w * box[0]), int(h * box[1]), int(w * box[2]), int(h * box[3]))).resize((8, 8))
    px = list(crop.getdata())
    r = sum(p[0] for p in px) / len(px) / 255
    g = sum(p[1] for p in px) / len(px) / 255
    b = sum(p[2] for p in px) / len(px) / 255
    hh, ss, vv = colorsys.rgb_to_hsv(r, g, b)
    if ss < 0.6 or vv < 0.5:
        return None
    return int(round(hh * 360 / 5)) % 72


def tab_tap(label, fallback_x):
    nodes = dump("tabs_" + label)
    hit = [n for n in find(nodes, rf"^{label}$") if n["cy"] > H * 0.8]
    if hit:
        touch.tap(hit[0]["cx"], hit[0]["cy"])
        return True
    touch.tap(W * fallback_x, H * 0.925)
    return False


# ----------------------------------------------------------------------------------------------- scenarios
def s00_touch_calibration():
    """Finds out which way of injecting touches really reaches the app (diagnostic for the harness itself)."""
    launch()
    nodes = dump("00_calib")
    base = shot("00_base")
    albums = [n for n in find(nodes, r"^Albums$") if n["cy"] > H * 0.8]
    if not albums:
        record("calibration: found Albums tab in UI dump", False, f"{len(nodes)} nodes, focus={focus()}")
        return
    ax, ay = albums[0]["cx"], albums[0]["cy"]
    back = [n for n in find(nodes, r"^Media$") if n["cy"] > H * 0.8]
    mx, my = (back[0]["cx"], back[0]["cy"]) if back else (W * 0.2, H * 0.925)

    def attempt(mode):
        touch.mode = mode
        touch.tap(ax, ay)
        time.sleep(1.4)
        img = shot(f"00_after_{mode}")
        moved = pic_diff(base, img) > 4.0
        touch.mode = "input"
        sh(f"input tap {int(mx)} {int(my)}")  # go back with the known-good method
        time.sleep(1.4)
        return moved

    ok_input = attempt("input")
    record("calibration: `input tap` reaches the app", ok_input, f"focus={focus()}")
    chosen = "input"
    for mode in ("ev0", "ev1", "ev2"):
        if touch.dev is None:
            break
        ok = attempt(mode)
        record(f"calibration: sendevent variant {mode} reaches the app", ok)
        if ok:
            chosen = mode
            break
    touch.mode = chosen
    metrics["touch_mode"] = chosen
    log("touch mode ->", chosen)


def s01_startup():
    launch()
    img = shot("01_after_launch")
    nodes = dump("01_after_launch")
    texts = sorted({n["text"] or n["desc"] for n in nodes if (n["text"] or n["desc"])})[:60]
    (OUT / "01_visible_texts.txt").write_text("\n".join(texts))
    record("startup: app visible and alive", alive(), f"cold start {metrics.get('cold_start_ms')} ms, {len(nodes)} ui nodes")
    crash_dialog = find(nodes, "CRASH LAST TIME")
    record("startup: no crash report dialog", not crash_dialog)


def s02_tabs():
    for round_ in range(3):
        for label, fx in (("Albums", 0.40), ("Library", 0.62), ("Favorites", 0.50), ("Media", 0.20)):
            ok = tab_tap(label, fx)
            time.sleep(0.9)
            shot(f"02_tab_{label}_{round_}")
            if round_ == 0:
                record(f"tabs: found '{label}' by text", ok)
    # fast tab mashing
    for i in range(12):
        touch.tap(W * (0.2 + 0.2 * (i % 4)), H * 0.925)
        time.sleep(0.15)
    shot("02_after_mash")


def s03_scroll_perf():
    tab_tap("Media", 0.20)
    time.sleep(1.5)
    shot("03_grid_top")
    gfx_reset()
    for i in range(8):
        touch.swipe(W * 0.5, H * 0.75, W * 0.5, H * 0.25, ms=180)
        time.sleep(0.25)
    for i in range(8):
        touch.swipe(W * 0.5, H * 0.25, W * 0.5, H * 0.75, ms=180)
        time.sleep(0.25)
    st = gfx_stats()
    metrics["photos_scroll"] = st
    shot("03_grid_after_scroll")
    record("scroll: photo grid fling x16 (info)", True, json.dumps(st))
    # Albums too
    tab_tap("Albums", 0.40)
    time.sleep(1.2)
    gfx_reset()
    for i in range(6):
        touch.swipe(W * 0.5, H * 0.75, W * 0.5, H * 0.3, ms=200)
        time.sleep(0.25)
    st = gfx_stats()
    metrics["albums_scroll"] = st
    record("scroll: albums fling x6 (info)", True, json.dumps(st))


def s04_photo_viewer():
    tab_tap("Media", 0.20)
    time.sleep(1.2)
    # a tile near the top-left of the grid (below the header)
    touch.tap(W * 0.18, H * 0.30)
    time.sleep(2.0)
    img = shot("04_viewer_open")
    nodes = dump("04_viewer_open")
    record("viewer: opened (controls or image present)", len(nodes) > 0 or alive(), f"{len(nodes)} nodes")
    for i in range(10):
        touch.swipe(W * 0.85, H * 0.5, W * 0.15, H * 0.5, ms=150)
        time.sleep(0.35)
    shot("04_viewer_after_swipes")
    for i in range(6):
        touch.swipe(W * 0.15, H * 0.5, W * 0.85, H * 0.5, ms=150)
        time.sleep(0.3)
    touch.double_tap(W * 0.5, H * 0.5)
    time.sleep(0.8)
    shot("04_viewer_double_tap_zoom")
    touch.double_tap(W * 0.5, H * 0.5)
    time.sleep(0.6)
    sh("input keyevent KEYCODE_BACK")
    time.sleep(1.0)
    shot("04_viewer_closed")


def open_clock_video():
    """Opens clock72.mp4 directly with a VIEW intent (reliable; the list navigation is covered by other scenarios)."""
    out = sh("content query --uri content://media/external/video/media --projection _id:_display_name")
    m = re.search(r"_id=(\d+), _display_name=clock72", out)
    if not m:
        log("WARN clock72 not in MediaStore: " + out[:200])
        return False
    sh(f"am start -a android.intent.action.VIEW -p {PKG} -d content://media/external/video/media/{m.group(1)} -t video/mp4", timeout=60)
    time.sleep(5.0)
    return True


def s05_video_playback_and_seek():
    open_clock_video()
    a = shot("05_video_a")
    sa = hue_to_second(a)
    time.sleep(2.0)
    b = shot("05_video_b")
    sb = hue_to_second(b)
    record("video: opens and plays (second advances)", sa is not None and sb is not None and sb != sa, f"second {sa} -> {sb}")
    if sa is None or sb is None:
        return
    # +10 s: one double tap on the right side
    t0 = time.time()
    touch.double_tap(W * 0.85, H * 0.45)
    time.sleep(0.4)
    c = shot("05_after_plus10")
    sc = hue_to_second(c)
    elapsed = time.time() - t0
    d = None if sc is None else (sc - sb) % 72
    record("video: double tap right = +10 s", d is not None and 8 <= d <= 10 + elapsed + 3, f"{sb} -> {sc} (delta {d}, tapped {elapsed:.1f}s ago)")
    # series: two more taps in a row = +20 s more
    time.sleep(1.0)
    c0 = shot("05_before_series")
    s0 = hue_to_second(c0)
    t0 = time.time()
    touch.taps(W * 0.85, H * 0.45, 3)
    time.sleep(0.4)
    e = shot("05_after_series_plus20")
    s1 = hue_to_second(e)
    elapsed = time.time() - t0
    d = None if (s0 is None or s1 is None) else (s1 - s0) % 72
    record("video: double tap + one more tap = +20 s (series)", d is not None and 18 <= d <= 20 + elapsed + 3, f"{s0} -> {s1} (delta {d})")
    # back 10 s
    time.sleep(1.0)
    f0 = shot("05_before_back")
    sf0 = hue_to_second(f0)
    t0 = time.time()
    touch.double_tap(W * 0.15, H * 0.45)
    time.sleep(0.4)
    g = shot("05_after_minus10")
    sg = hue_to_second(g)
    elapsed = time.time() - t0
    d = None if (sf0 is None or sg is None) else (sg - sf0 + 36) % 72 - 36
    record("video: double tap left = -10 s", d is not None and -10 - 1 <= d <= -10 + elapsed + 3, f"{sf0} -> {sg} (delta {d})")
    # play / pause with a double tap in the middle (only when 'double tap zoom' is off) - info only
    touch.double_tap(W * 0.5, H * 0.45)
    time.sleep(0.8)
    h1 = hue_to_second(shot("05_pause_a"))
    time.sleep(2.0)
    h2 = hue_to_second(shot("05_pause_b"))
    record("video: centre double tap toggles pause (info)", True, f"{h1} -> {h2} ({'paused' if h1 == h2 else 'still playing / zoom mode'})")


def s06_video_gestures():
    # relies on the video page left open by the previous scenario; reopen if it is gone
    if not alive():
        launch()
    nodes = dump("06_state")
    vol0 = sh("cmd media_session volume --get --stream 3")
    touch.swipe(W * 0.85, H * 0.65, W * 0.85, H * 0.30, ms=500)
    time.sleep(0.6)
    shot("06_volume_hud")
    vol1 = sh("cmd media_session volume --get --stream 3")
    record("video: swipe up on right changes volume", vol0.strip() != vol1.strip(), f"{vol0.strip()!r} -> {vol1.strip()!r}")
    touch.swipe(W * 0.15, H * 0.65, W * 0.15, H * 0.30, ms=500)
    time.sleep(0.6)
    shot("06_brightness_hud")
    touch.hold(W * 0.5, H * 0.45, 1.3)
    shot("06_after_hold")
    sh("input keyevent KEYCODE_BACK")
    time.sleep(1.0)
    shot("06_back_to_list")


def s07_swipe_between_videos():
    tab_tap("Library", 0.62)
    time.sleep(1.0)
    nodes = dump("07_lib")
    vid = find(nodes, r"^Videos$")
    touch.tap(*( (vid[0]["cx"], vid[0]["cy"]) if vid else (W * 0.3, H * 0.30)))
    time.sleep(2.0)
    touch.tap(W * 0.18, H * 0.25)
    time.sleep(2.5)
    for i in range(8):
        touch.swipe(W * 0.85, H * 0.5, W * 0.15, H * 0.5, ms=140)
        time.sleep(0.9)
    shot("07_after_video_swipes")
    for i in range(4):
        touch.swipe(W * 0.15, H * 0.5, W * 0.85, H * 0.5, ms=140)
        time.sleep(0.7)
    shot("07_back_swipes")
    sh("input keyevent KEYCODE_BACK")


def s08_rotation():
    sh("settings put system accelerometer_rotation 0")
    for rot in (1, 0, 3, 0):
        sh(f"settings put system user_rotation {rot}")
        time.sleep(1.8)
        shot(f"08_rot_{rot}")
    sh("settings put system user_rotation 0")
    sh("settings put system accelerometer_rotation 1")


def confirm_system_delete():
    """Android asks 'Allow Ax Gallery to delete this photo?' in a system dialog (PermissionActivity). Press Allow."""
    for _ in range(4):
        time.sleep(1.5)
        if "PermissionActivity" not in focus():
            return True
        nodes = dump("system_delete_dialog")
        b = find(nodes, r"^(allow|ok|delete|move to trash|yes)$")
        if b:
            touch_input_tap(b[-1]["cx"], b[-1]["cy"])
        else:
            sh("input keyevent KEYCODE_ENTER")
    return "PermissionActivity" not in focus()


def s10_favorite_and_delete():
    """Viewer actions that change data: favourite toggle, then delete. Reports what really happened to the file."""
    launch()
    before = sh("content query --uri content://media/external/images/media --projection _id:_display_name:is_favorite --where \"_display_name='IMG_001.jpg'\"")
    touch.tap(W * 0.17, H * 0.27)  # first tile
    time.sleep(2.5)
    shot("10_viewer")
    touch.tap(W * 0.34, H * 0.94)  # heart
    time.sleep(1.2)
    shot("10_after_heart")
    touch.tap(W * 0.84, H * 0.94)  # trash
    time.sleep(1.5)
    shot("10_after_trash_tap")
    nodes = dump("10_delete_dialog")
    btn = find(nodes, r"^(delete|move to trash|trash|remove|ok|allow|yes)$")
    log("delete dialog buttons:", [(n["text"] or n["desc"]) for n in btn])
    if btn:
        touch_input_tap(btn[-1]["cx"], btn[-1]["cy"])
        time.sleep(1.0)
        record("delete: system confirmation closed", confirm_system_delete(), "")
    shot("10_after_delete")
    dismiss_system_dialogs()
    after = sh("content query --uri content://media/external/images/media --projection _id:_display_name --where \"_display_name='IMG_001.jpg'\"")
    onfs = sh("ls /sdcard/DCIM/Camera/IMG_001.jpg 2>&1")
    record("delete: info only (see shots 10_*)", True, f"row before={'Row' in before} after={'Row' in after}; file: {onfs.strip()[:80]}; dialog buttons={[(n['text'] or n['desc']) for n in btn]}")


def s15_viewer_menu():
    """New Telegram-style popup menu: open, check entries are visible, dismiss; then reopen and dismiss with BACK."""
    launch()
    tab_tap("Media", 0.20)
    time.sleep(1.2)
    touch.tap(W * 0.18, H * 0.30)
    time.sleep(2.0)
    nodes = dump("15_viewer")
    more = find(nodes, "More selection")
    record("menu: flower button found in viewer", bool(more), f"{len(more)} nodes")
    if not more:
        return
    for rnd in (1, 2):
        touch.tap(more[0]["cx"], more[0]["cy"])
        time.sleep(1.0)
        shot(f"15_menu_open_{rnd}")
        n2 = dump(f"15_menu_open_{rnd}")
        time.sleep(2.0)
        shot(f"15_menu_open_late_{rnd}")
        has = bool(find(n2, "Rename|Move to album|Copy to album"))
        record(f"menu: entries visible (round {rnd})", has, f"{len(n2)} nodes")
        if rnd == 1:
            sh("input keyevent KEYCODE_BACK")
        else:
            touch.tap(W * 0.5, H * 0.75)
        time.sleep(0.8)
        shot(f"15_menu_closed_{rnd}")
        n3 = dump(f"15_menu_closed_{rnd}")
        record(f"menu: dismissed (round {rnd})", not find(n3, "Rename|Copy to album"), "")
    for i in range(4):
        touch.swipe(W * 0.85, H * 0.5, W * 0.15, H * 0.5, ms=150)
        time.sleep(0.5)
    shot("15_after_pager")
    touch.tap(W * 0.5, H * 0.5)
    time.sleep(0.6)
    touch.tap(W * 0.5, H * 0.5)
    time.sleep(0.6)
    shot("15_bars_toggled")



def tap_text(pattern, tag, min_y=0.0, max_y=1.0, exact=False):
    """Tap the first UI node whose text/description matches. Returns True when found."""
    nodes = dump(tag)
    rx = rf"^{pattern}$" if exact else pattern
    hit = [n for n in find(nodes, rx) if H * min_y <= n["cy"] <= H * max_y]
    if not hit:
        return False
    touch.tap(hit[0]["cx"], hit[0]["cy"])
    return True


def s20_tour():
    """Walk through every screen and take a picture of each, so look-and-feel problems show up in one run."""
    launch()
    tab_tap("Media", 0.20)
    time.sleep(1.5)
    shot("20_media")
    # settings
    if tap_text("Settings", "20_find_settings", max_y=0.2):
        time.sleep(1.5)
        shot("20_settings_0")
        for i in range(1, 9):
            touch.swipe(W * 0.5, H * 0.78, W * 0.5, H * 0.25, ms=350)
            time.sleep(0.7)
            shot(f"20_settings_{i}")
        sh("input keyevent KEYCODE_BACK")
        time.sleep(1.0)
    # selection mode
    touch.long_press(W * 0.18, H * 0.40) if hasattr(touch, "long_press") else touch.swipe(W * 0.18, H * 0.40, W * 0.18, H * 0.40, ms=900)
    time.sleep(1.2)
    shot("20_selection")
    sh("input keyevent KEYCODE_BACK")
    time.sleep(0.8)
    # search
    if tap_text("Search", "20_find_search", max_y=0.2):
        time.sleep(1.0)
        sh("input text 1")
        time.sleep(1.2)
        shot("20_search")
        sh("input keyevent KEYCODE_BACK")
        sh("input keyevent KEYCODE_BACK")
        time.sleep(0.8)
    # top-bar menu
    if tap_text("More", "20_find_more", max_y=0.2):
        time.sleep(1.0)
        shot("20_topmenu")
        sh("input keyevent KEYCODE_BACK")
        time.sleep(0.8)
    # albums and favourites
    for label, fx in (("Albums", 0.40), ("Favorites", 0.62)):
        tab_tap(label, fx)
        time.sleep(1.5)
        shot(f"20_tab_{label}")
    tab_tap("Albums", 0.40)
    time.sleep(1.0)
    touch.tap(W * 0.5, H * 0.42)
    time.sleep(1.5)
    shot("20_album_open")
    sh("input keyevent KEYCODE_BACK")
    time.sleep(0.8)
    # library sections
    for sec in ("Videos", "Memories", "Duplicates", "Locked", "Trips", "Cleanup", "Trash", "Editor", "Smart groups",
                "Media formats", "Folders", "Excluded", "Rescan"):
        tab_tap("Library", 0.78)
        time.sleep(1.2)
        found = tap_text(sec, f"20_find_{sec}", min_y=0.12, max_y=0.9)
        if not found:
            touch.swipe(W * 0.5, H * 0.75, W * 0.5, H * 0.25, ms=350)
            time.sleep(0.6)
            found = tap_text(sec, f"20_find2_{sec}", min_y=0.12, max_y=0.9)
        time.sleep(1.8)
        shot("20_lib_" + sec.replace(" ", "_") + ("" if found else "_NOTFOUND"))
        sh("input keyevent KEYCODE_BACK")
        time.sleep(0.8)
    # rotation sanity on the library
    tab_tap("Library", 0.78)
    time.sleep(1.0)
    shot("20_library_end")


def s21_fold_and_trash():
    """Icon row folds away on scroll (photos must move up), comes back at the top; then trash -> restore -> delete forever."""
    launch()
    tab_tap("Media", 0.20)
    time.sleep(1.5)
    shot("21_top")
    for i in range(3):
        touch.swipe(W * 0.5, H * 0.75, W * 0.5, H * 0.35, ms=300)
        time.sleep(0.5)
    time.sleep(0.6)
    shot("21_folded")
    gap = sh("echo ok")
    for i in range(4):
        touch.swipe(W * 0.5, H * 0.3, W * 0.5, H * 0.8, ms=250)
        time.sleep(0.4)
    time.sleep(1.2)
    shot("21_back_top")
    # trash flow through the Library
    tab_tap("Media", 0.20)
    time.sleep(1.0)
    touch.tap(W * 0.17, H * 0.36)
    time.sleep(2.0)
    shot("21_viewer")
    touch.tap(W * 0.84, H * 0.94)
    time.sleep(1.5)
    nodes = dump("21_delete_dialog")
    btn = find(nodes, r"^(delete|move to trash|trash|remove|ok|allow|yes)$")
    if btn:
        touch_input_tap(btn[-1]["cx"], btn[-1]["cy"])
        time.sleep(1.0)
        record("delete: system confirmation closed", confirm_system_delete(), "")
    shot("21_after_delete")
    # make sure we are back on the Media tab (a viewer or a dialog may still be on top)
    for _ in range(3):
        sh("input keyevent KEYCODE_BACK")
        time.sleep(0.8)
        if "com.ax.gallery" in focus():
            break
    tab_tap("Media", 0.20)
    time.sleep(1.0)
    if tap_text("Trash", "21_find_trash", min_y=0.12, max_y=0.5):
        time.sleep(1.8)
        shot("21_trash")
        touch.tap(W * 0.17, H * 0.22)
        time.sleep(1.5)
        shot("21_trash_item")
        sh("input keyevent KEYCODE_BACK")
        time.sleep(0.8)
    record("fold/trash: info only (see shots 21_*); crash check runs after the scenario", True, "")


def vis(nodes, n=45):
    """Short list of the texts/descriptions currently visible (for the report)."""
    out = []
    for x in nodes:
        t = (x["text"] or x["desc"]).strip().replace("\n", " ")
        if t and t not in out:
            out.append(t)
    return " / ".join(out)[:n * 12]


def click_text(pattern, tag, min_y=0.0, max_y=1.0, pick=0):
    nodes = dump(tag)
    hit = [n for n in find(nodes, pattern) if H * min_y <= n["cy"] <= H * max_y]
    if not hit:
        return False
    n = hit[pick] if pick < len(hit) else hit[-1]
    touch.tap(n["cx"], n["cy"])
    return True


def system_confirm():
    """Android's own 'allow this app to move/delete these files?' dialog."""
    try:
        return confirm_system_delete()
    except Exception:
        return False


def count_in_header(nodes, rx=r"Trash\s*\u00b7\s*([\d.]+)\s*(?:KB|MB|GB|B)"):
    for n in nodes:
        m = re.search(rx, n["text"] + " " + n["desc"])
        if m:
            return float(m.group(1))
    return None


def open_quick(label, tag):
    """Media tab -> tap one of the four icon-row shortcuts."""
    tab_tap("Media", 0.20)
    time.sleep(1.2)
    ok = click_text(rf"^{label}$", tag, min_y=0.10, max_y=0.45)
    time.sleep(2.0)
    return ok


def dismiss_tips():
    nodes = dump("tips")
    if find(nodes, r"^Don't show again$"):
        click_text(r"^Don't show again$", "tips_dismiss")
        time.sleep(0.8)


def s22_trash():
    """Trash, end to end: delete two photos from the viewer, restore one, delete the other forever (the user's crash), then Empty."""
    launch()
    open_quick("Trash", "22_trash_start")
    n0 = count_in_header(dump("22_trash_count0"))
    record("trash: header shows a size", n0 is not None, f"{n0}")
    sh("input keyevent KEYCODE_BACK")
    time.sleep(1.2)
    for i in range(2):
        tab_tap("Media", 0.20)
        time.sleep(1.0)
        touch.tap(W * (0.17 + 0.33 * i), H * 0.66)  # photo tiles, well below the video row
        time.sleep(2.2)
        dismiss_tips()
        shot(f"22_viewer_{i}")
        touch.tap(W * 0.84, H * 0.94)  # trash button of the viewer bar
        time.sleep(1.5)
        nodes = dump(f"22_delete_dialog_{i}")
        b = find(nodes, r"^(delete|move to trash|trash|remove|ok|yes|move)$")
        results.append((f"delete dialog {i}", "INFO", vis(nodes)))
        if b:
            touch.tap(b[-1]["cx"], b[-1]["cy"])
        time.sleep(1.0)
        system_confirm()
        time.sleep(1.5)
        shot(f"22_after_delete_{i}")
        for _ in range(2):
            if "MainActivity" in focus() and find(dump("22_back_check"), r"^Media$"):
                break
            sh("input keyevent KEYCODE_BACK")
            time.sleep(0.8)
    open_quick("Trash", "22_trash_again")
    n1 = count_in_header(dump("22_trash_count1"))
    shot("22_trash_after_delete")
    record("trash: deleted photos arrived in the trash", n0 is not None and n1 is not None and n1 > n0, f"{n0} -> {n1}")
    # restore the first one from the trash viewer
    touch.tap(W * 0.17, H * 0.22)
    time.sleep(2.0)
    dismiss_tips()
    nodes = dump("22_trash_viewer")
    shot("22_trash_viewer")
    results.append(("trash viewer texts", "INFO", vis(nodes)))
    r = find(nodes, r"restore")
    if r:
        touch.tap(r[-1]["cx"], r[-1]["cy"])
    else:
        touch.tap(W * 0.30, H * 0.94)
    time.sleep(1.5)
    system_confirm()
    time.sleep(2.0)
    shot("22_after_restore")
    record("trash: restore from the viewer did not crash", alive() and not crash_lines(), "; ".join(crash_lines()))
    # delete the remaining one forever
    if "MainActivity" not in focus():
        launch()
    open_quick("Trash", "22_trash_third")
    n2 = count_in_header(dump("22_trash_count2"))
    record("trash: restore shrank the trash", n1 is not None and n2 is not None and n2 < n1, f"{n1} -> {n2}")
    touch.tap(W * 0.17, H * 0.22)
    time.sleep(2.0)
    dismiss_tips()
    nodes = dump("22_trash_viewer2")
    d = find(nodes, r"delete permanently")
    if d:
        touch.tap(d[-1]["cx"], d[-1]["cy"])
    else:
        touch.tap(W * 0.70, H * 0.94)
    time.sleep(1.5)
    nodes = dump("22_perm_dialog")
    shot("22_perm_dialog")
    results.append(("perm delete dialog", "INFO", vis(nodes)))
    b = find(nodes, r"^(delete|delete permanently|delete forever|ok|yes|confirm)$")
    if b:
        touch.tap(b[-1]["cx"], b[-1]["cy"])
    time.sleep(1.0)
    system_confirm()
    time.sleep(2.5)
    shot("22_after_perm_delete")
    record("trash: delete forever did not crash", alive() and not crash_lines(), "; ".join(crash_lines()))
    if "MainActivity" not in focus():
        launch()
    open_quick("Trash", "22_trash_fourth")
    n3 = count_in_header(dump("22_trash_count3"))
    record("trash: permanent delete emptied it", n3 is not None and (n2 is None or n3 < n2 or n3 == 0), f"{n2} -> {n3}")
    if click_text(r"^empty$", "22_empty", min_y=0.0, max_y=0.35):
        time.sleep(1.2)
        click_text(r"^empty trash$|^empty$|^delete$|^ok$", "22_empty_dialog", min_y=0.3, max_y=0.9)
        time.sleep(1.0)
        system_confirm()
        time.sleep(2.5)
        record("trash: Empty did not crash", alive() and not crash_lines(), "; ".join(crash_lines()))


def s23_library_buttons():
    """Every icon-row shortcut must open its screen; then the Library tab (Cleanup, Trips, Locked ...)."""
    launch()
    expect = {"Trash": r"trash", "Videos": r"video", "Locked": r"locked|unlock|private", "Trips": r"trips|trip"}
    for label, want in expect.items():
        tab_tap("Media", 0.20)
        time.sleep(1.2)
        before = shot(f"23_{label}_before")
        ok = click_text(rf"^{label}$", f"23_find_{label}", min_y=0.10, max_y=0.45)
        time.sleep(2.2)
        nodes = dump(f"23_{label}_screen")
        shot(f"23_{label}_screen")
        txt = vis(nodes)
        still_media = bool(find(nodes, r"^TRASH$")) and bool(find(nodes, r"^VIDEOS$")) and bool(find(nodes, r"^TRIPS$"))
        record(f"icon row: '{label}' button opens its screen", ok and not still_media and bool(re.search(want, txt, re.I)),
               f"found={ok} still_on_media={still_media} texts={txt[:160]}")
        for _ in range(2):
            sh("input keyevent KEYCODE_BACK")
            time.sleep(0.8)
            if "MainActivity" not in focus():
                launch()
    # Library tab: list everything it offers, then open Cleanup / Trips / Locked
    tab_tap("Library", 0.78)
    time.sleep(2.0)
    nodes = dump("23_library_tab")
    shot("23_library_tab")
    results.append(("library tab texts", "INFO", vis(nodes)))
    for label in ("Cleanup", "Trips", "Locked", "Screenshots", "Large videos"):
        nodes = dump(f"23_lib_find_{label}")
        if find(nodes, rf"{label}"):
            click_text(rf"{label}", f"23_lib_tap_{label}", min_y=0.08, max_y=0.9)
            time.sleep(2.0)
            n2 = dump(f"23_lib_{label}")
            shot(f"23_lib_{label}")
            results.append((f"library '{label}' texts", "INFO", vis(n2)))
            record(f"library: '{label}' opens without crash", alive() and not crash_lines(), "")
            sh("input keyevent KEYCODE_BACK")
            time.sleep(1.0)
            if "MainActivity" not in focus():
                launch()
                tab_tap("Library", 0.78)
                time.sleep(1.5)
        else:
            results.append((f"library: '{label}' not visible on the Library tab", "WARN", ""))


def device_ms():
    return int(sh("date +%s%3N").strip())


def s24_new_photo_latency():
    """Adds new photos while the app is open, like the camera does, and measures MediaStore->notify->on screen."""
    launch()
    tab_tap("Media", 0.20)
    time.sleep(1.5)
    adb("logcat", "-c")
    latencies = []
    for i in range(3):
        img = Image.new("RGB", (1600, 1200), (40 + i * 60, 90, 200 - i * 50))
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=88)
        local = OUT / f"ax_new_{i}.jpg"
        local.write_bytes(buf.getvalue())
        remote = f"/sdcard/DCIM/Camera/ax_new_{int(time.time())}_{i}.jpg"
        sh("mkdir -p /sdcard/DCIM/Camera")
        adb("push", str(local), remote, check=True)
        t0 = device_ms()
        sh(f"am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://{remote} >/dev/null 2>&1")
        seen = None
        for _ in range(60):  # up to 30 s
            time.sleep(0.5)
            lg = adb("logcat", "-d", "-v", "epoch", "-s", "AxLatency")
            for line in lg.splitlines():
                m = re.match(r"\s*(\d+)\.(\d+)\s+\S+\s+\S+\s+\S+\s+AxLatency: (.*)", line) or re.match(r"\s*(\d+)\.(\d+)\s+.*?AxLatency\s*: (.*)", line)
                if not m:
                    continue
                ts = int(m.group(1)) * 1000 + int(m.group(2).ljust(3, "0")[:3])
                if ts >= t0 and m.group(3).startswith("2 on screen"):
                    seen = (ts - t0, m.group(3))
                    break
            if seen:
                break
        latencies.append(seen[0] if seen else None)
        record(f"new photo {i + 1}: appears in the grid", seen is not None, f"{seen[0]} ms after the scan ({seen[1][:70]})" if seen else "never showed up within 30 s")
        shot(f"24_after_new_{i}")
    metrics["new_photo_latency_ms"] = latencies
    lg = adb("logcat", "-d", "-v", "epoch", "-s", "AxLatency")
    (OUT / "logs" / "s24_axlatency.txt").write_text(lg)
    ok = [x for x in latencies if x is not None]
    record("new photos: median latency under 4 s", bool(ok) and sorted(ok)[len(ok) // 2] < 4000, f"{latencies}")


def sweep_screen(tag, header_rx, rounds=6, cap=28, skip_rx=r"(?i)^(delete|reset|clear|erase|factory|empty|sign out|log out)"):
    """Opens every tappable row of the current screen (scrolling down), screenshots it, then comes back. Never crashes is the check."""
    seen = set()
    taps = 0
    for rnd in range(rounds):
        nodes = dump(f"{tag}_r{rnd}")
        shot(f"{tag}_page{rnd}")
        for n in nodes:
            label = (n["text"] or n["desc"]).strip()
            if not (label and 3 <= len(label) < 45) or label in seen:
                continue
            if not (H * 0.12 < n["cy"] < H * 0.86) or re.search(skip_rx, label):
                continue
            seen.add(label)
            taps += 1
            if taps > cap:
                break
            touch.tap(n["cx"], n["cy"])
            time.sleep(1.2)
            shot(f"{tag}_tap{taps:02d}")
            if not alive():
                record(f"{tag}: '{label}' crashed the app", False, "; ".join(crash_lines()))
                launch()
                return taps
            if not find(dump(f"{tag}_after{taps:02d}"), header_rx):
                sh("input keyevent KEYCODE_BACK")
                time.sleep(1.0)
                if not find(dump(f"{tag}_back{taps:02d}"), header_rx):
                    sh("input keyevent KEYCODE_BACK")
                    time.sleep(1.0)
            if "MainActivity" not in focus():
                launch()
                return taps
        if taps > cap:
            break
        touch.swipe(W * 0.5, H * 0.78, W * 0.5, H * 0.28, ms=350)
        time.sleep(0.8)
    return taps


def s25_settings():
    """Settings: open every row, look for crashes and ugly screens."""
    launch()
    tab_tap("Library", 0.78)
    time.sleep(1.5)
    touch.tap(W * 0.93, H * 0.088)  # gear at the top right of the Library tab
    time.sleep(2.0)
    nodes = dump("25_settings_open")
    shot("25_settings_open")
    results.append(("settings texts", "INFO", vis(nodes)))
    record("settings: opens from the Library tab", bool(find(nodes, r"settings|appearance|general|language")), vis(nodes)[:120])
    taps = sweep_screen("25", r"(?i)settings")
    record("settings: every row opens without a crash", alive() and not crash_lines(), f"{taps} rows tried; " + "; ".join(crash_lines()))


def s26_editor():
    """Editor: open a photo, tap the pencil, look at every tool, rotate/crop/filters, leave without saving."""
    launch()
    tab_tap("Media", 0.20)
    time.sleep(1.0)
    touch.tap(W * 0.17, H * 0.66)
    time.sleep(2.2)
    dismiss_tips()
    touch.tap(W * 0.53, H * 0.94)  # pencil in the viewer bar
    time.sleep(2.5)
    nodes = dump("26_editor_open")
    shot("26_editor_open")
    results.append(("editor texts", "INFO", vis(nodes)))
    record("editor: opens (or offers a choice)", alive() and len(nodes) > 5, vis(nodes)[:120])
    # a chooser ('edit a copy' / 'edit original') may come first
    for pat in (r"^Ax Editor$", r"edit copy|save as copy|create copy", r"^continue$|^edit$|^ok$"):
        if find(nodes, pat) and not find(nodes, r"^(crop|filters|adjust)$"):
            click_text(pat, "26_choice")
            time.sleep(2.0)
            nodes = dump("26_editor_after_choice")
            shot("26_editor_after_choice")
    taps = sweep_screen("26", r"(?i)crop|adjust|filter|draw|text|editor", rounds=3, cap=14,
                        skip_rx=r"(?i)^(delete|reset|clear|erase|save|done|apply|discard|exit|close)")
    record("editor: tools open without a crash", alive() and not crash_lines(), f"{taps} controls tried; " + "; ".join(crash_lines()))
    sh("input keyevent KEYCODE_BACK")
    time.sleep(1.0)
    click_text(r"(?i)^(discard|don't save|leave|exit|no)$", "26_discard")
    time.sleep(1.0)
    record("editor: can be left", alive(), "")


def s27_app_lock():
    """App lock settings screen: find it, open it, do not set a PIN (would lock the test run)."""
    launch()
    tab_tap("Library", 0.78)
    time.sleep(1.5)
    touch.tap(W * 0.93, H * 0.088)
    time.sleep(2.0)
    found = False
    for _ in range(18):
        nodes = dump("27_settings")
        hit = [n for n in find(nodes, r"(?i)app lock|lock the app|pin|biometric|security|privacy") if H * 0.12 < n["cy"] < H * 0.86]
        if hit:
            touch.tap(hit[0]["cx"], hit[0]["cy"])
            found = True
            break
        touch.swipe(W * 0.5, H * 0.78, W * 0.5, H * 0.28, ms=350)
        time.sleep(0.8)
    time.sleep(1.8)
    nodes = dump("27_lock_screen")
    shot("27_lock_screen")
    results.append(("app lock screen texts", "INFO", vis(nodes)))
    record("app lock: settings entry found and opens", found and alive(), vis(nodes)[:140])
    taps = sweep_screen("27", r"(?i)lock|security|privacy|pin", rounds=2, cap=6,
                        skip_rx=r"(?i)^(delete|reset|clear|erase|disable|turn off|remove)")
    record("app lock: rows open without crash", alive() and not crash_lines(), f"{taps} rows; " + "; ".join(crash_lines()))


def s28_real_glass():
    """Turns the experimental 'Real liquid glass' switch on, then looks at the screens that carry the glass bar."""
    launch()
    shot("28_before")
    tab_tap("Library", 0.78)
    time.sleep(1.5)
    touch.tap(W * 0.93, H * 0.088)
    time.sleep(2.0)
    found = False
    for _ in range(14):
        nodes = dump("28_settings")
        hit = [n for n in find(nodes, r"Real liquid glass") if H * 0.12 < n["cy"] < H * 0.88]
        if hit:
            found = True
            ty = hit[0]["cy"] + H * 0.0275
            for k in range(6):
                (touch_input_tap if k % 2 == 0 else touch.tap)(W * 0.86, ty)
                time.sleep(1.6)
                nd = dump("28_after_tap")
                chk = [n for n in nd if n.get("checkable") and abs(n["cy"] - ty) < H * 0.02]
                on = bool(chk) and chk[0].get("checked")
                results.append((f"real glass: switch state after try {k}", "INFO", f"nodes={len(chk)} checked={on}"))
                if on:
                    break
            break
            break
        touch.swipe(W * 0.5, H * 0.78, W * 0.5, H * 0.28, ms=350)
        time.sleep(0.6)
    time.sleep(1.0)
    shot("28_switch")
    if not alive():
        (OUT / "logs" / "s28_after_switch_crash.txt").write_text(adb("logcat", "-d", "-t", "8000"))
    record("real glass: switch found in Settings", found, "")
    sh("input keyevent KEYCODE_BACK")
    time.sleep(1.2)
    if not alive():
        (OUT / "logs" / "s28_after_back_crash.txt").write_text(adb("logcat", "-d", "-t", "8000"))
    for name, x in (("media", 0.20), ("library", 0.78)):
        tab_tap("Media" if name == "media" else "Library", x)
        time.sleep(2.0)
        if not alive():
            (OUT / "logs" / f"s28_{name}_crash.txt").write_text(adb("logcat", "-d", "-t", "8000"))
        shot(f"28_glass_{name}")
    touch.swipe(W * 0.5, H * 0.75, W * 0.5, H * 0.25, ms=300)
    time.sleep(1.0)
    shot("28_glass_scrolled")
    if not alive():
        (OUT / "logs" / "s28_end_crash.txt").write_text(adb("logcat", "-d", "-t", "8000"))
    record("real glass: no crash with the shader bar", alive() and not crash_lines(), "; ".join(crash_lines()))


def _changed(a, b):
    """Pixels that changed noticeably between two screenshots (ignores the status bar). Pure PIL, no numpy on the runner."""
    from PIL import ImageChops
    k = 4
    d = ImageChops.difference(a, b).convert("L").resize((a.width // k, a.height // k)).point(lambda v: 255 if v > 30 else 0)
    w, h = d.size
    data = d.getdata()
    xs, ys = [], []
    for i, v in enumerate(data):
        if v:
            y = (i // w) * k
            if y > H * 0.04:
                xs.append((i % w) * k)
                ys.append(y)
    return xs, ys


def s29_selection_hit():
    """Every tap in selection mode must change ONLY the tapped tile (reported bug: the row below gets selected)."""
    launch()
    tab_tap("Media", 0.20)
    time.sleep(1.5)
    tile = W / 3.0
    # tiles of the first rows (3 columns on the emulator): column centres, row centres below the shortcut row
    ys0 = H * 0.43
    taps = [(0, 0), (1, 0), (2, 1), (0, 2), (1, 1), (2, 0)]
    first = True
    bad = 0
    for n, (c, r) in enumerate(taps):
        x = tile * (c + 0.5)
        y = ys0 + tile * r
        before = shot(f"29_before_{n}")
        if first:
            touch.swipe(x, y, x, y, ms=900)
            first = False
        else:
            touch.tap(x, y)
        time.sleep(1.0)
        after = shot(f"29_after_{n}")
        if before is None or after is None:
            continue
        xs, ys = _changed(before, after)
        if not xs:
            results.append((f"selection tap {n}: nothing changed", "WARN", f"tap=({int(x)},{int(y)})"))
            continue
        # judge only the grid area; the changed pixels must form ONE tile-sized box that contains the tap point
        keep = [i for i, yy in enumerate(ys) if yy > H * 0.14]
        xs = [xs[i] for i in keep]; ys = [ys[i] for i in keep]
        if not xs:
            continue
        w_, h_ = max(xs) - min(xs), max(ys) - min(ys)
        hit = min(xs) - 8 <= x <= max(xs) + 8 and min(ys) - 8 <= y <= max(ys) + 8
        ok = hit and w_ <= tile + 24 and h_ <= tile + 24
        bad += (not ok)
        record(f"selection tap {n}: only the tapped tile changes", ok,
               f"tap=({int(x)},{int(y)}) changed_x={min(xs)}-{max(xs)} y={min(ys)}-{max(ys)}")
    nodes = dump("29_header")
    results.append(("selection header texts", "INFO", vis(nodes)))
    record("selection: no crash", alive() and not crash_lines(), "; ".join(crash_lines()))


def media_ids():
    out = sh("content query --uri content://media/external/images/media --projection _id")
    return set(re.findall(r"_id=(\d+)", out)) | set(re.findall(r"_id=(\d+)", sh("content query --uri content://media/external/video/media --projection _id")))


def selected_count():
    nodes = dump("29_sel")
    for n in nodes:
        m = re.search(r"(\d+)\s+selected", n["text"] + " " + n["desc"], re.I)
        if m:
            return int(m.group(1)), nodes
    return 0, nodes


def s30_selection_delete():
    """Selecting exactly what the finger touches, and deleting exactly what is selected."""
    launch()
    tab_tap("Media", 0.20)
    time.sleep(1.5)
    # scroll a little so the shortcut row is folded away, like real use
    touch.swipe(W * 0.5, H * 0.70, W * 0.5, H * 0.35, ms=300)
    time.sleep(1.2)
    before = media_ids()
    row_y = H * 0.55
    touch.hold(W * 0.17, row_y, seconds=1.3)
    time.sleep(1.0)
    n1, nodes = selected_count()
    shot("29_one_selected")
    record("selection: long-press selects exactly one", n1 == 1, f"selected={n1}")
    # layout must NOT shift when selection starts: the first tile has to stay exactly where the finger pressed it
    after1 = shot("29_layout_after_select")
    results.append(("selection: layout stable (see 29_one_selected)", "INFO", ""))
    touch.tap(W * 0.50, row_y)
    time.sleep(0.8)
    n2, _ = selected_count()
    record("selection: tapping a second tile adds exactly one", n2 == 2, f"selected={n2}")
    touch.tap(W * 0.83, row_y)
    time.sleep(0.8)
    n3, _ = selected_count()
    record("selection: tapping a third tile adds exactly one", n3 == 3, f"selected={n3}")
    touch.tap(W * 0.83, row_y)
    time.sleep(0.8)
    n4, _ = selected_count()
    record("selection: tapping it again removes exactly that one", n4 == 2, f"selected={n4}")
    shot("29_two_selected")
    # tap a tile on the row BELOW: must add exactly one, not two
    touch.tap(W * 0.50, row_y + H * 0.14)
    time.sleep(0.8)
    n5, _ = selected_count()
    record("selection: tile on the next row adds exactly one", n5 == 3, f"selected={n5}")
    touch.tap(W * 0.50, row_y + H * 0.14)
    time.sleep(0.8)
    # delete the two selected
    nodes = dump("29_before_delete")
    b = [n for n in find(nodes, r"delete|trash") if n["cy"] < H * 0.16]
    if b:
        touch.tap(b[-1]["cx"], b[-1]["cy"])
    else:
        results.append(("selection: no delete button found in the top bar", "WARN", vis(nodes)))
    time.sleep(1.5)
    shot("29_delete_dialog")
    nodes = dump("29_delete_dialog_nodes")
    d = find(nodes, r"^(delete|move to trash|trash|remove|ok|yes)$")
    if d:
        touch.tap(d[-1]["cx"], d[-1]["cy"])
    time.sleep(1.0)
    system_confirm()
    time.sleep(2.5)
    shot("29_after_delete")
    after = media_ids()
    gone = before - after
    added = after - before
    record("delete: exactly the 2 selected items disappeared", len(gone) == 2 and not added, f"removed={len(gone)} added={len(added)}")
    record("delete: no crash", alive() and not crash_lines(), "; ".join(crash_lines()))


def s31_row_after_delete():
    """Shortcut row must stay tappable when we come back to the top after scrolling + deleting."""
    launch()
    time.sleep(3.0)
    if "MainActivity" not in focus():
        launch()
        time.sleep(3.0)
    tab_tap("Media", 0.20)
    time.sleep(1.5)
    for _ in range(3):
        touch.swipe(W * 0.5, H * 0.75, W * 0.5, H * 0.30, ms=250)
        time.sleep(0.5)
    time.sleep(1.0)
    shot("31_scrolled")
    before = media_ids()
    touch.hold(W * 0.17, H * 0.55, seconds=1.3)
    time.sleep(1.0)
    shot("31_selected")
    nodes = dump("31_selected")
    results.append(("row-after-delete: selection state after hold", "INFO", vis(nodes)[:160]))
    b = [n for n in find(nodes, r"delete|trash") if n["cy"] < H * 0.16]
    if b:
        touch.tap(b[-1]["cx"], b[-1]["cy"])
    time.sleep(1.5)
    nodes = dump("31_dialog")
    d = find(nodes, r"^(delete|move to trash|trash|remove|ok|yes)$")
    if d:
        touch.tap(d[-1]["cx"], d[-1]["cy"])
    time.sleep(1.0)
    system_confirm()
    time.sleep(2.5)
    record("row-after-delete: item removed", len(before - media_ids()) == 1, "")
    for _ in range(5):
        touch.swipe(W * 0.5, H * 0.30, W * 0.5, H * 0.85, ms=200)
        time.sleep(0.4)
    time.sleep(1.5)
    shot("31_back_at_top")
    nodes = dump("31_top_nodes")
    for _ in range(3):
        if nodes:
            break
        time.sleep(1.5)
        nodes = dump("31_top_nodes")
    shown = bool(find(nodes, r"^TRASH$"))
    record("row-after-delete: shortcut row visible at top", shown, vis(nodes)[:150])
    ok = click_text(r"^Videos$", "31_tap_videos", min_y=0.08, max_y=0.45)
    time.sleep(2.0)
    nodes = dump("31_after_tap")
    shot("31_after_tap")
    record("row-after-delete: tapping a shortcut opens its screen", ok and not find(nodes, r"^TRASH$"), f"found={ok}")


def s32_search_and_intro():
    """Intro must cover the whole screen from the first frame; the search bar must be readable over scrolling photos."""
    sh(f"am force-stop {PKG}")
    time.sleep(0.8)
    sh(f"am start -n {ACT}")
    frames = []
    t0 = time.time()
    for i in range(10):
        im = shot(f"32_intro_{i}")
        frames.append((round(time.time() - t0, 2), im))
    for t, im in frames:
        if im is None:
            continue
        top = im.crop((0, 2, im.width, 28)).resize((1, 1)).getpixel((0, 0))
        mid = im.crop((0, int(H * 0.30), im.width, int(H * 0.30) + 26)).resize((1, 1)).getpixel((0, 0))
        results.append((f"intro frame t={t}s", "INFO", f"status-bar band rgb={top} mid band rgb={mid}"))
    time.sleep(6)
    dismiss_system_dialogs()
    tab_tap("Media", 0.20)
    time.sleep(1.5)
    nodes = dump("32_media")
    b = [n for n in find(nodes, r"^Search$") if n["cy"] < H * 0.16]
    if b:
        touch.tap(b[0]["cx"], b[0]["cy"])
    else:
        touch.tap(W * 0.12, H * 0.075)
    time.sleep(1.2)
    shot("32_search_open")
    for _ in range(2):
        touch.swipe(W * 0.5, H * 0.75, W * 0.5, H * 0.35, ms=250)
        time.sleep(0.5)
    shot("32_search_scrolled")
    sh("input text IMG")
    time.sleep(1.2)
    shot("32_search_typed")
    record("search/intro: no crash", alive() and not crash_lines(), "; ".join(crash_lines()))


def s09_monkey():
    launch()
    gfx_reset()
    out = sh(f"monkey -p {PKG} --throttle 150 --pct-syskeys 0 --pct-appswitch 0 --ignore-security-exceptions "
             f"--monitor-native-crashes -s 7 -v 1500", timeout=900)
    (OUT / "logs" / "monkey.txt").write_text(out[-6000:])
    crashed = "// CRASH" in out or "ANR" in out
    record("monkey: 1500 random events without crash/ANR", not crashed, out.strip().splitlines()[-1] if out.strip() else "")
    metrics["monkey_gfx"] = gfx_stats()
    shot("09_after_monkey")


def main():
    global touch
    log("installing", APK)
    adb("install", "-r", "-g", str(APK), check=True, timeout=300)
    screen_size()
    log("screen", W, H)
    # media
    adb("push", "media/.", "/sdcard/", check=True, timeout=300)
    sh("find /sdcard/DCIM /sdcard/Pictures /sdcard/Download /sdcard/Movies -type f | while read f; do "
       "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d \"file://$f\" >/dev/null 2>&1; done", timeout=300)
    time.sleep(8)
    cnt = sh("content query --uri content://media/external/file --projection _id --where \"media_type=1 OR media_type=3\"").count("Row:")
    log("media rows indexed:", cnt)
    metrics["media_rows"] = cnt
    record("setup: media indexed by MediaStore", cnt >= 30, f"{cnt} rows")
    sh("settings put global window_animation_scale 1; settings put global transition_animation_scale 1; settings put global animator_duration_scale 1")
    sh("settings put secure immersive_mode_confirmations confirmed")  # no 'Viewing full screen' popup over the video
    touch = Touch()
    record("setup: touch device found", touch.dev is not None, str(touch.dev))
    allfns = (s00_touch_calibration, s01_startup, s02_tabs, s03_scroll_perf, s04_photo_viewer, s05_video_playback_and_seek,
              s06_video_gestures, s07_swipe_between_videos, s08_rotation, s10_favorite_and_delete, s15_viewer_menu, s20_tour, s21_fold_and_trash, s22_trash, s23_library_buttons, s24_new_photo_latency, s25_settings, s26_editor, s27_app_lock, s28_real_glass, s29_selection_hit, s30_selection_delete, s31_row_after_delete, s32_search_and_intro, s09_monkey)
    # the fresh emulator often shows a launcher ANR dialog; restart the launcher before the first scenario
    sh("am force-stop com.google.android.apps.nexuslauncher; am force-stop com.google.android.apps.nexuslauncher")
    time.sleep(12)
    sh("input keyevent KEYCODE_HOME")
    time.sleep(4)
    dismiss_system_dialogs()
    only = [x.strip() for x in os.environ.get("SCENARIOS", "").split(",") if x.strip()]
    for fn in allfns:
        if only and fn.__name__.split("_")[0] not in only:
            continue
        scenario(fn)
    # report
    lines = ["# Emulator test results", "", "| result | check | detail |", "|---|---|---|"]
    for n, st, d in results:
        lines.append(f"| {st} | {n} | {d.replace('|', '/').replace(chr(10), ' ')[:260]} |")
    lines += ["", "## Metrics", "```json", json.dumps(metrics, indent=1), "```"]
    (OUT / "results.md").write_text("\n".join(lines))
    save_log("final")
    small = OUT / "shots_small"
    small.mkdir(exist_ok=True)
    for f in sorted((OUT / "shots").glob("*.png")):
        try:
            im = Image.open(f).convert("RGB")
            im.thumbnail((540, 1200))
            im.save(small / (f.stem + ".jpg"), quality=62)
        except Exception:
            pass
    fails = [r for r in results if r[1] == "FAIL"]
    log(f"{len(results) - len(fails)} passed, {len(fails)} failed")
    return 0  # the job reports through results.md; a failed check must not hide the artifacts


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:
        traceback.print_exc()
        (OUT / "results.md").write_text("# Harness crashed\n```\n" + traceback.format_exc() + "\n```")
        sys.exit(0)
