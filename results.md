# Emulator test results

| result | check | detail |
|---|---|---|
| PASS | setup: media indexed by MediaStore | 43 rows |
| PASS | setup: touch device found | /dev/input/event12 |
| s00_touch_calibration: app was not in the foreground at the start | WARN | mCurrentFocus=Window{2f5a0e1 u0 com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity} / mFocusedApp=ActivityRecord{e03bc68 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity} t12} |
| PASS | calibration: `input tap` reaches the app | focus=mCurrentFocus=Window{d3f8b42 u0 com.ax.gallery/com.iris.gallery.MainActivity} / mFocusedApp=ActivityRecord{2ee6ac6 u0 com.ax.gallery/com.iris.gallery.MainActivity} t14} |
| FAIL | calibration: sendevent variant ev0 reaches the app |  |
| FAIL | calibration: sendevent variant ev1 reaches the app |  |
| FAIL | calibration: sendevent variant ev2 reaches the app |  |
| PASS | startup: app visible and alive | cold start 1421 ms, 69 ui nodes |
| PASS | startup: no crash report dialog |  |
| PASS | tabs: found 'Albums' by text |  |
| PASS | tabs: found 'Library' by text |  |
| PASS | tabs: found 'Favorites' by text |  |
| PASS | tabs: found 'Media' by text |  |
| PASS | scroll: photo grid fling x16 (info) | {"frames": 109, "janky_pct": "100.00", "p50_ms": "200", "p90_ms": "300", "p95_ms": "300", "p99_ms": "1000"} |
| PASS | scroll: albums fling x6 (info) | {"frames": 41, "janky_pct": "100.00", "p50_ms": "200", "p90_ms": "300", "p95_ms": "300", "p99_ms": "400"} |
| PASS | viewer: opened (controls or image present) | 45 nodes |
| PASS | video: opens and plays (second advances) | second 2 -> 6 |
| PASS | video: double tap right = +10 s | 6 -> 17 (delta 11, tapped 5.4s ago) |
| PASS | video: double tap + one more tap = +20 s (series) | 19 -> 40 (delta 21) |
| FAIL | video: double tap left = -10 s | 41 -> 44 (delta 3) |
| PASS | video: centre double tap toggles pause (info) | 49 -> 52 (still playing / zoom mode) |
| PASS | video: swipe up on right changes volume | '[V] will get volume\n[V] will control stream=3 (STREAM_MUSIC)\n[V] Connecting to AudioService\n[V] volume is 5 in range [0..15]' -> '[V] will get volume\n[V] will control stream=3 (STREAM_MUSIC)\n[V] Connecting to AudioService\n[V] volume is 10 in range [0..1 |
| PASS | delete: info only (see shots 10_*) | row before=True after=True; file: /sdcard/DCIM/Camera/IMG_001.jpg; dialog buttons=['Allow'] |
| PASS | menu: flower button found in viewer | 1 nodes |
| PASS | menu: entries visible (round 1) | 17 nodes |
| PASS | menu: dismissed (round 1) |  |
| PASS | menu: entries visible (round 2) | 17 nodes |
| PASS | menu: dismissed (round 2) |  |
| PASS | monkey: 1500 random events without crash/ANR | ## Network stats: elapsed time=107702ms (0ms mobile, 0ms wifi, 107702ms not connected) |
| s09_monkey: app left the foreground during the scenario | WARN | mCurrentFocus=Window{4938732 u0 com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity} / mFocusedApp=ActivityRecord{e03bc68 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity} t12} |

## Metrics
```json
{
 "media_rows": 43,
 "cold_start_ms": 1004,
 "touch_mode": "input",
 "photos_scroll": {
  "frames": 109,
  "janky_pct": "100.00",
  "p50_ms": "200",
  "p90_ms": "300",
  "p95_ms": "300",
  "p99_ms": "1000"
 },
 "albums_scroll": {
  "frames": 41,
  "janky_pct": "100.00",
  "p50_ms": "200",
  "p90_ms": "300",
  "p95_ms": "300",
  "p99_ms": "400"
 },
 "monkey_gfx": {
  "frames": 445,
  "janky_pct": "99.33",
  "p50_ms": "300",
  "p90_ms": "600",
  "p95_ms": "800",
  "p99_ms": "1550"
 }
}
```

## Main-thread disk I/O (StrictMode)

- none found in the app's own code