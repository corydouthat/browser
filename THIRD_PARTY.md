# Third-party code shipped in Hatch

Dependencies resolved by Gradle (GeckoView, Mozilla Android Components, AndroidX,
Kotlin) carry their own licenses and are not copied into this repository. This
file lists code that is vendored into the source tree.

## eruda 3.4.3 — MIT

- Path: `app/src/main/assets/extension/vendor/eruda.min.js`
- Source: https://github.com/liriliri/eruda (npm `eruda@3.4.3`, file `eruda.js`,
  499,928 bytes, sha256 `332f95b14b1dc53cdbe6042e0ea95ac6025ac691c285d51b647c64360fe939e2`)
- Use: in-page developer console, injected into a tab on user request only
  (`scripting.executeScript`, MAIN world).

```
The MIT License (MIT)

Copyright (c) 2016-present liriliri

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Mozilla Android Components `WebContent` composable — MPL-2.0

- Path: `app/src/main/kotlin/app/hatch/ui/WebContent.kt` (vendored by the app
  module from `mobile/android/android-components/components/compose/engine`,
  tag `FIREFOX_158_0_RELEASE`; the MPL-2.0 header is kept in the file).
- License: Mozilla Public License 2.0, https://mozilla.org/MPL/2.0/

## Launcher icon artwork — CC0 (public domain)

- Path: `app/src/main/res/drawable/ic_launcher_foreground.xml` (monitor body paths),
  `docs/icon-preview.svg`.
- Source: Openclipart #354586 "Retro CRT monitor screen icon",
  https://openclipart.org/detail/354586, released under CC0 1.0 (no attribution
  required; credited anyway). Screen recolor and the green prompt paths were
  added by hand; see `docs/icon.md`.
