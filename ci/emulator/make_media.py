#!/usr/bin/env python3
"""Creates test photos and videos.

Colour clock video: every second of "clock72.mp4" is one solid colour (hue = second * 5 degrees), so a screenshot of
the player tells the driver exactly which second is on screen. That is how seeking (+-10 s) is verified.
"""
import colorsys, os, subprocess, sys
from pathlib import Path
from PIL import Image

out = Path(sys.argv[1] if len(sys.argv) > 1 else "media")
for d in ("DCIM/Camera", "Pictures/Telegram", "Download", "Movies"):
    (out / d).mkdir(parents=True, exist_ok=True)

def run(*a):
    subprocess.run(a, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

# ---- photos: plasma images with a number, a few sizes ----
spec = [("DCIM/Camera", 24, "1600x1200"), ("Pictures/Telegram", 10, "1280x960"), ("Download", 6, "900x1600")]
n = 0
for folder, count, size in spec:
    for i in range(count):
        n += 1
        f = out / folder / f"IMG_{n:03d}.jpg"
        run("convert", "-size", size, "plasma:fractal", "-gravity", "center", "-pointsize", "160",
            "-fill", "white", "-stroke", "black", "-strokewidth", "4", "-annotate", "0", str(n), "-quality", "88", str(f))
        # spread the modification times over several days so the date sections are exercised
        t = 1730000000 - n * 40000
        os.utime(f, (t, t))

# ---- colour clock video (72 s, one colour per second, long GOP like an anime encode) ----
tmp = out / "_frames"
tmp.mkdir(exist_ok=True)
for s in range(72):
    r, g, b = colorsys.hsv_to_rgb(s * 5 / 360.0, 1.0, 1.0)
    Image.new("RGB", (1280, 720), (int(r * 255), int(g * 255), int(b * 255))).save(tmp / f"c{s:02d}.png")
run("ffmpeg", "-y", "-framerate", "1", "-i", str(tmp / "c%02d.png"), "-f", "lavfi", "-i", "sine=frequency=440:duration=72",
    "-vf", "fps=30,format=yuv420p", "-c:v", "libx264", "-preset", "veryfast", "-crf", "16", "-g", "300", "-keyint_min", "300",
    "-c:a", "aac", "-shortest", str(out / "Movies/clock72.mp4"))
for f in tmp.iterdir():
    f.unlink()
tmp.rmdir()

# ---- two short moving clips ----
for i, name in enumerate(("DCIM/Camera/VID_A.mp4", "Download/VID_B.mp4")):
    run("ffmpeg", "-y", "-f", "lavfi", "-i", f"testsrc2=size=1280x720:rate=30:duration={12 + i * 6}",
        "-f", "lavfi", "-i", "sine=frequency=330:duration=30", "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
        "-g", "60", "-c:a", "aac", "-shortest", str(out / name))
SC = os.environ.get("SCENARIOS", "")
want = lambda *tags: (not SC.strip()) or any(t in SC for t in tags)
if want("s11", "s12", "s13"):
    # ---- odd files (crash hunters) ----
    odd = out / "Pictures/Odd"
    odd.mkdir(parents=True, exist_ok=True)
    (odd / "zero_byte.jpg").write_bytes(b"")
    good = out / "DCIM/Camera/IMG_001.jpg"
    (odd / "truncated.jpg").write_bytes(good.read_bytes()[:20000])
    (odd / "not_an_image.jpg").write_bytes(b"hello this is text, not a jpeg" * 50)
    run("convert", "-size", "800x600", "plasma:fractal", str(odd / "photo.webp"))
    run("convert", "-size", "300x300", "-delay", "10", "plasma:red-blue", "plasma:green-yellow", "plasma:blue-white", "-loop", "0", str(odd / "anim.gif"))
    run("convert", "-size", "12000x9000", "gradient:red-blue", "-quality", "60", str(odd / "huge_12000x9000.jpg"))
    run("convert", "-size", "100x7000", "gradient:black-white", str(odd / "tall_panorama.png"))
    (odd / "vector.svg").write_text('<svg xmlns="http://www.w3.org/2000/svg" width="400" height="300"><rect width="400" height="300" fill="#2447d8"/><circle cx="200" cy="150" r="90" fill="#ffc928"/></svg>')
    # ---- bulk library: thousands of small photos (scroll / memory / start-up under a realistic load) ----
    bulk = out / "DCIM/Bulk"
    bulk.mkdir(parents=True, exist_ok=True)
    base = bulk / "base.jpg"
    run("convert", "-size", "640x480", "plasma:fractal", "-quality", "80", str(base))
    import shutil
    for i in range(2500):
        f = bulk / f"B_{i:04d}.jpg"
        shutil.copyfile(base, f)
        t = 1700000000 - i * 3000
        os.utime(f, (t, t))
    base.unlink()
print("media ready:", sum(1 for _ in out.rglob("*") if _.is_file()), "files")
