# Emulator test results

| result | check | detail |
|---|---|---|
| PASS | setup: media indexed by MediaStore | 43 rows |
| PASS | setup: touch device found | /dev/input/event12 |
| s29_selection_hit: app was not in the foreground at the start | WARN | mCurrentFocus=Window{8b9cea5 u0 com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity} / mFocusedApp=ActivityRecord{efa4664 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity} t12} |
| FAIL | selection tap 0: only the tapped tile changes | tap=(180,1032) changed_x=0-1076 y=340-2396 |
| PASS | selection tap 1: only the tapped tile changes | tap=(540,1032) changed_x=368-712 y=708-1052 |
| PASS | selection tap 2: only the tapped tile changes | tap=(900,1392) changed_x=724-1068 y=1064-1408 |
| PASS | selection tap 3: only the tapped tile changes | tap=(180,1752) changed_x=8-352 y=1420-1768 |
| PASS | selection tap 4: only the tapped tile changes | tap=(540,1392) changed_x=368-708 y=1064-1408 |
| PASS | selection tap 5: only the tapped tile changes | tap=(900,1032) changed_x=724-1064 y=708-1052 |
| INFO | selection header texts | Today / Select date / VID_B.mp4 / 0:18 / VID_A.mp4 / 0:12 / Selected / IMG_001.jpg / IMG_002.jpg / IMG_003.jpg / IMG_004.jpg / IMG_005.jpg / IMG_006.jpg / IMG_007.jpg / IMG_008.jpg / IMG_009.jpg / IMG_010.jpg / IMG_011.jpg / IMG_012.jpg / IMG_013.jpg / Trash / |
| PASS | selection: no crash |  |
| PASS | selection: long-press selects exactly one | selected=1 |
| INFO | selection: layout stable (see 29_one_selected) |  |
| PASS | selection: tapping a second tile adds exactly one | selected=2 |
| PASS | selection: tapping a third tile adds exactly one | selected=3 |
| PASS | selection: tapping it again removes exactly that one | selected=2 |
| PASS | selection: tile on the next row adds exactly one | selected=3 |
| PASS | delete: exactly the 2 selected items disappeared | removed=2 added=0 |
| PASS | delete: no crash |  |
| INFO | row-after-delete: selection state after hold | IMG_011.jpg / IMG_013.jpg / IMG_015.jpg / IMG_016.jpg / IMG_017.jpg / IMG_018.jpg / IMG_019.jpg / IMG_020.jpg / IMG_021.jpg / IMG_022.jpg / IMG_023.jpg / IMG_02 |
| PASS | row-after-delete: item removed |  |
| PASS | row-after-delete: shortcut row visible at top | Today / VID_B.mp4 / 0:18 / VID_A.mp4 / 0:12 / IMG_001.jpg / IMG_002.jpg / IMG_003.jpg / IMG_004.jpg / IMG_005.jpg / IMG_006.jpg / IMG_007.jpg / IMG_00 |
| PASS | row-after-delete: tapping a shortcut opens its screen | found=True |
| INFO | intro frame t=2.61s | status-bar band rgb=(159, 159, 167) mid band rgb=(51, 61, 87) |
| INFO | intro frame t=3.43s | status-bar band rgb=(18, 37, 75) mid band rgb=(20, 32, 62) |
| INFO | intro frame t=4.31s | status-bar band rgb=(18, 37, 75) mid band rgb=(20, 32, 62) |
| INFO | intro frame t=5.54s | status-bar band rgb=(18, 37, 75) mid band rgb=(20, 32, 62) |
| INFO | intro frame t=6.54s | status-bar band rgb=(18, 37, 75) mid band rgb=(20, 32, 62) |
| INFO | intro frame t=8.13s | status-bar band rgb=(18, 37, 75) mid band rgb=(20, 32, 62) |
| INFO | intro frame t=13.59s | status-bar band rgb=(41, 44, 54) mid band rgb=(112, 102, 99) |
| INFO | intro frame t=19.92s | status-bar band rgb=(41, 44, 54) mid band rgb=(112, 102, 99) |
| INFO | intro frame t=24.9s | status-bar band rgb=(41, 44, 54) mid band rgb=(112, 102, 99) |
| INFO | intro frame t=30.66s | status-bar band rgb=(41, 44, 54) mid band rgb=(112, 102, 99) |
| PASS | search/intro: no crash |  |

## Metrics
```json
{
 "media_rows": 43,
 "cold_start_ms": 1682
}
```