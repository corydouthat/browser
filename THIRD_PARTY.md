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

## Fenix 158 tab-tray building blocks — MPL-2.0 (chrome)

- Paths: `app/src/main/kotlin/app/hatch/ui/tabs/TabGridItem.kt`,
  `app/src/main/kotlin/app/hatch/ui/tabs/ThumbnailImage.kt`,
  `app/src/main/kotlin/app/hatch/ui/tabs/SwipeToDismiss.kt`,
  `app/src/main/kotlin/app/hatch/ui/tabs/Favicon.kt`.
- Source: Firefox for Android (Fenix) at tag `FIREFOX_158_0_RELEASE`,
  `mobile/android/fenix/app/src/main/java/org/mozilla/fenix/tabstray/ui/tabitems/TabGridTabItem.kt`,
  `.../fenix/compose/ThumbnailImage.kt`, `.../fenix/compose/SwipeToDismiss.kt`, `.../fenix/compose/Favicon.kt`.
  Adapted (Hatch's Material 3 theme, `BrowserState` tabs, storage/icons passed in, previews and Fenix-only
  state dropped); each file keeps the MPL-2.0 header and notes its origin. No Mozilla trademark artwork
  (`ic_firefox`, `mozac_ic_logo_*`) is referenced.
<!-- hatch:surfaces begin -->
- Design pass (build 22+): `TabGridItem.kt` is restyled to the "Tab tray" mockups (r18 card, 40 dp header,
  170 dp thumbnail, check circle) and takes its favicon / thumbnail as slots; the Fenix structure (header,
  thumbnail, selection ring, `SwipeToDismissBox`) and the MPL-2.0 header are unchanged.
- `app/src/main/assets/errorpages/hatch_error.js` reads the query string the way AC 158's
  `browser-errorpages/src/main/assets/errorPageScripts.js` (MPL-2.0) does (`document.documentURI`,
  `URLSearchParams`, the bad-certificate / HTTPS-only parameters and `document.addCertException` /
  `reloadWithHttpsOnlyException` calls); it is Hatch's own code (textContent only, no markup injection).
<!-- hatch:surfaces end -->
- License: Mozilla Public License 2.0, https://mozilla.org/MPL/2.0/

## Mozilla Android Components `WebContent` (reduced) — MPL-2.0 (chrome)

- `app/src/main/kotlin/app/hatch/ui/WebContent.kt` now only hosts the EngineView (render/release moved to
  AC's `SessionFeature`); the MPL-2.0 header and the origin note are kept.

## Fenix 158 backup rules — MPL-2.0 (fixer)

- Path: `app/src/main/res/xml/data_extraction_rules.xml`.
- Source: Firefox for Android (Fenix) at tag `FIREFOX_158_0_RELEASE`,
  `mobile/android/fenix/app/src/main/res/xml/data_extraction_rules.xml` (copied verbatim, MPL-2.0 header kept;
  wired through `android:dataExtractionRules` + `android:fullBackupContent="false"`, SEC-2).
- License: Mozilla Public License 2.0, https://mozilla.org/MPL/2.0/

<!-- hatch:toolbar begin -->
## Mozilla Android Components `InlineAutocompleteTextField` — MPL-2.0 (toolbar)

- Path: `app/src/main/kotlin/app/hatch/ui/toolbar/InlineAutocompleteTextField.kt`.
- Source: Android Components at tag `FIREFOX_158_0_RELEASE`,
  `mobile/android/android-components/components/compose/browser-toolbar/src/main/java/mozilla/components/compose/browser/toolbar/ui/InlineAutocompleteTextField.kt`
  (sha256 of the fetched file `291763ac8314639b10138f581d45e1854ec72e75d5ebf4fac7d1b6742a0066dc`).
- Ported so the edit toolbar matches the design's 44 dp pill: package changed; text style and colours are
  parameters (Hatch tokens) instead of Acorn's `autofillText` / `selectedText`; AC's test tag and Acorn previews
  dropped; AC's public `BrowserToolbarQuery` is used, not redefined. Autocomplete transformations, IME connection
  wrappers and paste sanitising are verbatim. Every change is marked `Hatch:`; the MPL-2.0 header is kept.
- License: Mozilla Public License 2.0, https://mozilla.org/MPL/2.0/
<!-- hatch:toolbar end -->


## Geist and Geist Mono 1.8.1 — SIL Open Font License 1.1 (theme)

- Paths: `app/src/main/res/font/geist_regular.ttf`, `geist_medium.ttf`, `geist_semibold.ttf`, `geist_bold.ttf`,
  `geist_mono_regular.ttf`, `geist_mono_medium.ttf`, `geist_mono_semibold.ttf` (renamed only, bytes unchanged);
  licence text shipped as `app/src/main/assets/licenses/OFL-geist.txt` (the release's `OFL.txt`).
- Source: the official vercel/geist-font GitHub release v1.8.1,
  https://github.com/vercel/geist-font/releases/download/v1.8.1/geist-font-v1.8.1.zip
  (sha256 `70f42c1452fb6df12ea183e20a65999157354f91d11dac18b4c713e383808ec2`), static TTFs from
  `geist-font/Geist/ttf/` and `geist-font/GeistMono/ttf/` (font version 1.801).
- sha256 of the shipped files:
  - `geist_regular.ttf` `4467c854246d29cf2fe8a18694f4dcd8996439e535d53b260b3bfb34c110175f`
  - `geist_medium.ttf` `cf90d22c5f12977b06173440f6f564b7e1245aeb48bc4d5fbd82e6cde16abff5`
  - `geist_semibold.ttf` `9192802fb4e3cb725833b4a74b35ec6a03c62c44cf55b64864abd8de64bf720e`
  - `geist_bold.ttf` `67482107630e0b61d601c7987426e56c45c15ab5a417ed9dd7a0f85372a2e359`
  - `geist_mono_regular.ttf` `90f1ada2b0e62e15e4268fd00636c6fb8665ba51556becfead428839f433d454`
  - `geist_mono_medium.ttf` `6804a64ebc0cd275180959cfb334bd5c886a6a28fa2cc6508d80d17dc5c972e6`
  - `geist_mono_semibold.ttf` `b5c08511cdd2fc2a3320afa93cde3f21d57aed32475dd6ffd53dc4f9c061080d`
- Use: the app UI typeface (`app.hatch.ui.theme.HatchTypography` / `HatchType.mono`). Never served to web pages.
- Error-page subsets (fixer, DF-09): `app/src/main/assets/errorpages/fonts/geist_regular.woff2`, `geist_medium.woff2`,
  `geist_semibold.woff2`, `geist_mono_medium.woff2` — Modified Versions (OFL §1) made from the four TTFs above with
  fontTools 4.67.0 `pyftsubset --unicodes="U+0020-007E,U+00A0-00FF,U+0131,U+0152-0153,U+02C6,U+02DA,U+02DC,U+2010-2027,U+2030-203A,U+2044,U+20AC,U+2122,U+2190-2193,U+2212" --flavor=woff2 --layout-features='kern,liga,calt,tnum'`
  (the `meta` table is dropped). Loaded only by Hatch's own `hatch_error.html` (`resource://android/…`), never by a
  web page. sha256:
  - `geist_regular.woff2` `8b1b09454e3cf7c3c077845165cb6b6b99eee2a2c6c05557951aa8cc8b57ad28`
  - `geist_medium.woff2` `b7d58896662d891e1a341f735ef11573ac236ab9e6bec96b75acbffcc963c889`
  - `geist_semibold.woff2` `ee59d5bfc736ff4c86fe51989ce20bdc63a033736f65cc61b5b209c32cfc2c04`
  - `geist_mono_medium.woff2` `95551c89839527de0c0155cfe44cc87116bdcf2b2b3341c58f6b45044501a085`
- Licence: SIL Open Font License, Version 1.1, https://openfontlicense.org. "Geist" is not a Reserved Font Name, so
  the subsets may keep the name (OFL §3); the `res/font` TTFs are bundled unmodified, the subsets ship under the same
  licence (`OFL-geist.txt`).

```
Copyright 2024 The Geist Project Authors (https://github.com/vercel/geist-font)

This Font Software is licensed under the SIL Open Font License, Version 1.1.
This license is copied below, and is also available with a FAQ at:
https://openfontlicense.org


-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded, 
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
```

## Launcher icon artwork, in-app mark — CC0 (theme)

- Path: `app/src/main/res/drawable/hatch_mark.xml` (the same Openclipart #354586 CC0 artwork as
  `ic_launcher_foreground.xml`, on the rounded graphite background of `docs/design/hatch-icon-gray.svg`).
