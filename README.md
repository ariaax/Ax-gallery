<h1 align="center">Ax Gallery</h1>

<p align="center">A private, fast and good-looking Android gallery with a liquid-glass interface, by <b>Aria ax</b>.</p>

## About

Ax Gallery is a personal local media viewer. It has zero network permissions, opens fast and keeps your photos on your phone.

- Newly saved photos and videos appear on top (timeline order can be changed in Settings → Ax Gallery)
- Liquid-glass viewer, sheets, dialogs and navigation bar (can be switched off)
- Built-in editor, EXIF editor, locked vault, trash bin, duplicate scanner

## Build

The APK is built by GitHub Actions (`Build and Release APK`). To build locally:

```bash
git clone https://github.com/ariaax/Ax-gallery.git
cd Ax-gallery
./gradlew assembleRelease
```

## Credits and license

Ax Gallery is a modified version of **Iris Gallery** by Mohamad Oday (https://github.com/MohamadOday/iris-gallery), used under the Apache License 2.0. The original copyright notice is kept below as the license requires.

```
Copyright 2026 Mohamad Oday  (original Iris Gallery)
Modifications Copyright 2026 Aria ax

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
