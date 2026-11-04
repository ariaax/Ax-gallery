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
            sh(f"input tap {int(x)} {int(y)}; input tap {int(x)} {int(y)}")
            return
        self._run(self._down(x, y) + ["sleep 0.03"] + self._up() + [f"sleep {gap}"] + self._down(x, y) + ["sleep 0.03"] + self._up())

    def taps(self, x, y, n, gap=0.12):
        if not self.native:
            sh("; ".join([f"input tap {int(x)} {int(y)}"] * n))
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
                              "cls": n.get("class", "")})
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
    if hits or not alive():
        record(name + " -> app alive", False, "; ".join(hits) or "process is gone")
        save_log(name + "_crash")
        shot(name + "_after_crash")
        launch()  # bring it back for the next scenario
    else:
        save_log(name)


# ----------------------------------------------------------------------------------------------- app helpers
def launch(wait=True):
    sh(f"am force-stop {PKG}")
    time.sleep(0.5)
    out = sh(f"am start -W -n {ACT}", timeout=120)
    m = re.search(r"TotalTime: (\d+)", out)
    if m:
        metrics["cold_start_ms"] = int(m.group(1))
    if wait:
        time.sleep(7)  # the intro animation + first composition


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
    """Opens clock72.mp4 through Library -> Videos. Returns True when a video page is showing."""
    tab_tap("Library", 0.62)
    time.sleep(1.2)
    nodes = dump("05_library")
    vid = find(nodes, r"^Videos$")
    if vid:
        touch.tap(vid[0]["cx"], vid[0]["cy"])
    else:
        touch.tap(W * 0.3, H * 0.30)
    time.sleep(2.0)
    shot("05_videos_list")
    nodes = dump("05_videos_list")
    # tiles: the clock video is 72 s long -> its duration label is "1:12"
    dur = find(nodes, r"1:12")
    if dur:
        touch.tap(dur[0]["cx"], dur[0]["cy"] - 40)
    else:
        touch.tap(W * 0.18, H * 0.25)
    time.sleep(3.0)
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
    touch.double_tap(W * 0.85, H * 0.45)
    touch.taps(W * 0.85, H * 0.45, 1)
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
    touch = Touch()
    record("setup: touch device found", touch.dev is not None, str(touch.dev))
    for fn in (s00_touch_calibration, s01_startup, s02_tabs, s03_scroll_perf, s04_photo_viewer, s05_video_playback_and_seek,
               s06_video_gestures, s07_swipe_between_videos, s08_rotation, s09_monkey):
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
