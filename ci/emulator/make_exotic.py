#!/usr/bin/env python3
"""Exotic, heavy and broken media for the compatibility run (scenario s14).

Writes media/Exotic/{img,vid,aud,misc}/... and media/Exotic/manifest.json:
  [{"path": "Exotic/img/x.jpg", "kind": "image|video|audio", "expect": "ok|broken", "note": "..."}]
Every generator is wrapped in try/except: a format the CI tools cannot write is skipped and listed in "skipped".
"""
import json, os, random, shutil, subprocess, sys, time
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

out = Path(sys.argv[1] if len(sys.argv) > 1 else "media")
root = out / "Exotic"
for d in ("img", "vid", "aud", "misc"):
    (root / d).mkdir(parents=True, exist_ok=True)
manifest, skipped = [], []
NOW = time.time()


def run(*a, timeout=600):
    subprocess.run(a, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=timeout)


def add(rel, kind, expect="ok", note=""):
    p = out / rel
    if p.exists():
        os.utime(p, (NOW, NOW))
    manifest.append({"path": rel, "kind": kind, "expect": expect, "note": note})


def gen(name, fn):
    try:
        fn()
    except Exception as e:  # noqa
        skipped.append(f"{name}: {type(e).__name__}")


# a recognisable, asymmetric picture ("F" + arrow) so rotation / flips are visible in screenshots
def test_image(w=800, h=600, text="F"):
    im = Image.new("RGB", (w, h), (30, 60, 140))
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, w // 2, h // 2], fill=(220, 60, 60))
    d.polygon([(w * .55, h * .1), (w * .95, h * .3), (w * .55, h * .5)], fill=(250, 200, 40))
    try:
        f = ImageFont.load_default(size=int(min(w, h) * .45))
    except TypeError:
        f = ImageFont.load_default()
    d.text((w * .12, h * .55), text, fill="white", font=f)
    return im


I = root / "img"
# ---------------- images
def jpg_variants():
    base = test_image()
    base.save(I / "baseline.jpg", quality=90); add("Exotic/img/baseline.jpg", "image")
    base.save(I / "progressive.jpg", quality=90, progressive=True); add("Exotic/img/progressive.jpg", "image")
    base.save(I / "UPPERCASE.JPG", quality=80); add("Exotic/img/UPPERCASE.JPG", "image")
    for o in range(1, 9):  # EXIF orientation 1..8
        ex = Image.Exif(); ex[0x0112] = o
        test_image(800, 500).save(I / f"exif_orient_{o}.jpg", quality=85, exif=ex)
        add(f"Exotic/img/exif_orient_{o}.jpg", "image", note="orientation")
gen("jpg", jpg_variants)

def cmyk():
    test_image().convert("CMYK").save(I / "cmyk.jpg", quality=85); add("Exotic/img/cmyk.jpg", "image", note="CMYK jpeg")
gen("cmyk", cmyk)

def gray_jpg():
    test_image().convert("L").save(I / "gray.jpg"); add("Exotic/img/gray.jpg", "image")
gen("gray", gray_jpg)

def png_variants():
    test_image().save(I / "plain.png"); add("Exotic/img/plain.png", "image")
    run("convert", "-size", "600x400", "gradient:red-blue", "-depth", "16", str(I / "deep16.png")); add("Exotic/img/deep16.png", "image", note="16-bit png")
    test_image().convert("P").save(I / "palette.png"); add("Exotic/img/palette.png", "image")
    t = test_image(); t.putalpha(Image.linear_gradient("L").resize(t.size)); t.save(I / "alpha.png"); add("Exotic/img/alpha.png", "image", note="transparency")
gen("png", png_variants)

def other_still_formats():
    b = test_image()
    b.save(I / "photo.bmp"); add("Exotic/img/photo.bmp", "image")
    b.save(I / "photo.tiff"); add("Exotic/img/photo.tiff", "image", note="TIFF")
    b.save(I / "photo.webp", quality=80); add("Exotic/img/photo.webp", "image")
    b.save(I / "photo.ico", sizes=[(256, 256)]); add("Exotic/img/photo.ico", "image", note="ICO")
    b.save(I / "photo.jp2"); add("Exotic/img/photo.jp2", "image", note="JPEG2000")
    b.save(I / "photo.tga"); add("Exotic/img/photo.tga", "image", note="TGA")
    b.save(I / "photo.pcx"); add("Exotic/img/photo.pcx", "image", note="PCX")
    b.save(I / "photo.ppm"); add("Exotic/img/photo.ppm", "image", note="PPM")
for n, f in (("bmp/tiff/webp/ico/jp2/tga/pcx", other_still_formats),):
    gen(n, f)

def avif():
    b = I / "_src.png"; test_image().save(b)
    run("ffmpeg", "-y", "-i", str(b), "-c:v", "libsvtav1", "-still-picture", "1", str(I / "photo.avif"))
    b.unlink(); add("Exotic/img/photo.avif", "image", note="AVIF")
gen("avif", avif)

def animated():
    frames = [test_image(480, 480, str(i)) for i in range(60)]
    frames[0].save(I / "anim_60f.gif", save_all=True, append_images=frames[1:], duration=50, loop=0); add("Exotic/img/anim_60f.gif", "image", note="60 frame gif")
    frames[0].save(I / "anim.webp", save_all=True, append_images=frames[1:30], duration=60, loop=0); add("Exotic/img/anim.webp", "image", note="animated webp")
    frames[0].save(I / "anim.png", save_all=True, append_images=frames[1:10], duration=100, loop=0, format="PNG"); add("Exotic/img/anim.png", "image", note="APNG")
gen("animated", animated)

def heavy_images():
    Image.MAX_IMAGE_PIXELS = None
    g = Image.linear_gradient("L").resize((20000, 20000)); g.save(I / "huge_20000x20000_400MP.png", compress_level=1); del g
    add("Exotic/img/huge_20000x20000_400MP.png", "image", note="400 megapixel PNG (decode must be sub-sampled)")
    j = Image.effect_noise((10000, 10000), 60).convert("RGB"); j.save(I / "noise_10000x10000_100MP.jpg", quality=70); del j
    add("Exotic/img/noise_10000x10000_100MP.jpg", "image", note="100 megapixel noisy JPEG")
    p = Image.linear_gradient("L").rotate(90).resize((30000, 1500)); p.save(I / "panorama_30000x1500.jpg", quality=80); del p
    add("Exotic/img/panorama_30000x1500.jpg", "image", note="extreme panorama")
    t = Image.linear_gradient("L").resize((200, 12000)); t.save(I / "strip_200x12000.png"); del t
    add("Exotic/img/strip_200x12000.png", "image", note="extreme tall strip")
    Image.new("RGB", (1, 1), (255, 0, 0)).save(I / "pixel_1x1.png"); add("Exotic/img/pixel_1x1.png", "image", note="1x1")
gen("heavy images", heavy_images)

def names():
    b = test_image(400, 300)
    for nm in ("عکس فارسی ۱۴۰۴.jpg", "name with spaces & symbols (1) #2 %20.jpg", "emoji_😀_photo.jpg", ("long_" + "x" * 200 + ".jpg"), "noextension", "dots.in.the.name.v2.final.jpg"):
        b.save(I / nm, format="JPEG"); add(f"Exotic/img/{nm}", "image", note="file name")
gen("names", names)

def mismatched():
    test_image().save(I / "png_named_jpg.jpg", format="PNG"); add("Exotic/img/png_named_jpg.jpg", "image", note="PNG data with .jpg name")
    test_image().save(I / "jpg_named_png.png", format="JPEG"); add("Exotic/img/jpg_named_png.png", "image", note="JPEG data with .png name")
    test_image().save(I / "webp_named_jpg.jpg", format="WEBP"); add("Exotic/img/webp_named_jpg.jpg", "image", note="WEBP data with .jpg name")
gen("mismatched", mismatched)

def broken_images():
    good = (I / "baseline.jpg").read_bytes()
    (I / "broken_zero.jpg").write_bytes(b""); add("Exotic/img/broken_zero.jpg", "image", "broken")
    (I / "broken_half.jpg").write_bytes(good[: len(good) // 2]); add("Exotic/img/broken_half.jpg", "image", "broken", "half of a jpeg")
    (I / "broken_header_only.jpg").write_bytes(good[:200]); add("Exotic/img/broken_header_only.jpg", "image", "broken")
    (I / "broken_text.png").write_bytes(b"not a png " * 100); add("Exotic/img/broken_text.png", "image", "broken")
    r = bytearray(good); random.seed(5)
    for _ in range(40): r[random.randrange(300, len(r))] = random.randrange(256)
    (I / "broken_bitflips.jpg").write_bytes(bytes(r)); add("Exotic/img/broken_bitflips.jpg", "image", "broken", "random byte damage")
    (I / "broken_bomb.png").write_bytes(subprocess.run(["convert", "-size", "60000x60000", "xc:white", "-depth", "8", "png:-"], capture_output=True).stdout[:3_000_000] or b"x")
    add("Exotic/img/broken_bomb.png", "image", "broken", "decompression-bomb style, truncated")
gen("broken images", broken_images)

# ---------------- videos
V = root / "vid"
def cyc(size, rate, dur):
    return f"color=size={size}:rate={rate}:duration={dur}:c=red,format=yuv444p,hue=h=360*t/3"


def vid(name, args, vf=None, audio=True, note="", expect="ok"):
    vfx = vf or cyc("1280x720", 30, 4)
    cmd = ["ffmpeg", "-y", "-f", "lavfi", "-i", vfx]
    if audio: cmd += ["-f", "lavfi", "-i", "sine=frequency=440:duration=4"]
    cmd += args + ([] if not audio else ["-shortest"]) + [str(V / name)]
    def g():
        run(*cmd); add(f"Exotic/vid/{name}", "video", expect, note)
    gen(name, g)

vid("h264_baseline_320x240.mp4", ["-c:v", "libx264", "-profile:v", "baseline", "-pix_fmt", "yuv420p", "-c:a", "aac"], cyc("320x240", 15, 4), note="H.264 baseline")
vid("h264_1080p60.mp4", ["-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac"], cyc("1920x1080", 60, 4), note="1080p 60 fps")
vid("hevc_1080p.mp4", ["-c:v", "libx265", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-tag:v", "hvc1", "-c:a", "aac"], cyc("1920x1080", 30, 4), note="HEVC")
vid("hevc_10bit_1080p.mp4", ["-c:v", "libx265", "-preset", "ultrafast", "-pix_fmt", "yuv420p10le", "-tag:v", "hvc1", "-c:a", "aac"], cyc("1920x1080", 30, 3), note="HEVC Main10 (HDR-style 10-bit)")
vid("hevc_4k.mp4", ["-c:v", "libx265", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-tag:v", "hvc1", "-c:a", "aac"], cyc("3840x2160", 30, 3), note="4K HEVC")
vid("hevc_8k.mp4", ["-c:v", "libx265", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-tag:v", "hvc1", "-an"], cyc("7680x4320", 24, 1), audio=False, note="8K HEVC (beyond most phone decoders)")
vid("vp9.webm", ["-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-b:v", "2M", "-c:a", "libopus"], note="VP9 + Opus")
vid("vp8.webm", ["-c:v", "libvpx", "-deadline", "realtime", "-cpu-used", "8", "-b:v", "2M", "-c:a", "libvorbis"], note="VP8 + Vorbis")
vid("av1_720p.mp4", ["-c:v", "libsvtav1", "-preset", "12", "-pix_fmt", "yuv420p", "-c:a", "aac"], cyc("1280x720", 30, 3), note="AV1")
vid("mpeg2.mpg", ["-c:v", "mpeg2video", "-b:v", "4M", "-c:a", "ac3"], note="MPEG-2 / AC-3")
vid("mpeg2.ts", ["-c:v", "mpeg2video", "-b:v", "4M", "-c:a", "aac", "-f", "mpegts"], note="MPEG-TS")
vid("mpeg4_mp3.avi", ["-c:v", "mpeg4", "-q:v", "4", "-c:a", "libmp3lame"], note="AVI MPEG-4 part 2 + MP3")
vid("h263.3gp", ["-c:v", "h263", "-c:a", "aac", "-ar", "8000", "-ac", "1", "-strict", "-2"], cyc("176x144", 15, 4), note="3GP H.263")
vid("wmv2.wmv", ["-c:v", "wmv2", "-c:a", "wmav2"], note="WMV")
vid("theora.ogv", ["-c:v", "libtheora", "-q:v", "6", "-c:a", "libvorbis"], note="Theora/Ogg")
vid("mjpeg.avi", ["-c:v", "mjpeg", "-q:v", "5", "-an"], audio=False, note="Motion-JPEG")
vid("prores_1080p.mov", ["-c:v", "prores_ks", "-profile:v", "3", "-c:a", "pcm_s16le"], cyc("1920x1080", 30, 2), note="ProRes 422 HQ (pro codec, not in Android)")
vid("ffv1_lossless.mkv", ["-c:v", "ffv1", "-c:a", "flac"], cyc("1280x720", 30, 2), note="FFV1 lossless (very heavy)")
vid("no_audio.mp4", ["-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-an"], audio=False, note="no audio track")
vid("portrait_rotate90.mp4", ["-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-metadata:s:v:0", "rotate=90"], cyc("1280x720", 30, 4), note="rotation metadata (phone portrait video)")
vid("odd_sar_4x3_pixels.mp4", ["-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-vf", "setsar=4/3", "-c:a", "aac"], note="non-square pixels")
vid("vfr_variable_framerate.mp4", ["-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-vf", "select='not(mod(n,3))+gt(random(0),0.7)',setpts=N/FRAME_RATE/TB", "-fps_mode", "vfr", "-c:a", "aac"], note="variable frame rate")
vid("long_audio_tracks_subs.mkv", ["-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-map", "0:v", "-map", "1:a", "-map", "1:a"], note="two audio tracks")

def long_video():
    src = V / "_clip.mp4"
    run("ffmpeg", "-y", "-f", "lavfi", "-i", "color=size=640x360:rate=30:duration=6:c=red,format=yuv444p,hue=h=360*t/3", "-f", "lavfi", "-i", "sine=frequency=330:duration=6",
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "38", "-g", "60", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "24k", "-shortest", str(src))
    run("ffmpeg", "-y", "-stream_loop", "600", "-i", str(src), "-c", "copy", str(V / "long_1h.mp4"), timeout=900)  # 6 s x 601 = 1 hour
    src.unlink(); add("Exotic/vid/long_1h.mp4", "video", note="1 hour long (seek across a huge file)")
gen("long video", long_video)

def broken_videos():
    good = (V / "h264_1080p60.mp4").read_bytes()
    (V / "broken_zero.mp4").write_bytes(b""); add("Exotic/vid/broken_zero.mp4", "video", "broken")
    (V / "broken_text.mp4").write_bytes(b"this is not a video\n" * 500); add("Exotic/vid/broken_text.mp4", "video", "broken")
    (V / "broken_truncated_no_moov.mp4").write_bytes(good[: len(good) // 2]); add("Exotic/vid/broken_truncated_no_moov.mp4", "video", "broken", "cut in half (index missing)")
    r = bytearray(good); random.seed(9)
    for _ in range(300): r[random.randrange(2000, len(r))] = random.randrange(256)
    (V / "broken_bitflips.mp4").write_bytes(bytes(r)); add("Exotic/vid/broken_bitflips.mp4", "video", "broken", "damaged payload")
gen("broken videos", broken_videos)

# ---------------- audio-only (a gallery must ignore or survive them)
A = root / "aud"
for name, args in (("tone.mp3", ["-c:a", "libmp3lame"]), ("tone.flac", ["-c:a", "flac"]), ("tone.opus", ["-c:a", "libopus"]), ("tone.m4a", ["-c:a", "aac"]), ("tone.wav", [])):
    def g(name=name, args=args):
        run("ffmpeg", "-y", "-f", "lavfi", "-i", "sine=frequency=523:duration=3", *args, str(A / name)); add(f"Exotic/aud/{name}", "audio", note="audio only")
    gen(name, g)

# ---------------- other files that must not appear as media
M = root / "misc"
(M / "document.pdf").write_bytes(b"%PDF-1.4\n%fake\n"); add("Exotic/misc/document.pdf", "other")
(M / "notes.txt").write_text("hello"); add("Exotic/misc/notes.txt", "other")
(M / "archive.zip").write_bytes(b"PK\x03\x04" + b"0" * 100); add("Exotic/misc/archive.zip", "other")

# a folder with .nomedia must stay out of the gallery
nm = root / "hidden_by_nomedia"; nm.mkdir(exist_ok=True)
test_image().save(nm / "secret.jpg"); (nm / ".nomedia").write_bytes(b"")

(root / "manifest.json").write_text(json.dumps({"files": manifest, "skipped": skipped}, indent=1, ensure_ascii=False))
size = sum(f.stat().st_size for f in root.rglob("*") if f.is_file())
print(f"exotic: {len(manifest)} files, {size/1e6:.0f} MB, skipped: {skipped}")
