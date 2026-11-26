# Launcher icon

A retro beige CRT monitor showing a green terminal prompt, on a graphite background (`#3A3A3F`, the "Signal"
design's launcher colour; `docs/design/hatch-icon-gray.svg`).

- **Source artwork**: Openclipart #354586 "Retro CRT monitor screen icon"
  (https://openclipart.org/detail/354586) — public domain (CC0), so no attribution is required;
  we credit it anyway. The monitor body paths are used verbatim.
- **Edits by hand** (no AI image generation): the screen was recolored from blue to dark phosphor
  (`#0D1A12`) and four green prompt lines plus a block cursor (`#5DF27A`) were added as paths.
- **Why not a modified Firefox logo**: the Firefox name and logo are Mozilla trademarks; Mozilla's
  trademark policy allows them only on unmodified official builds, and embedding GeckoView grants no
  logo rights. Hatch therefore uses its own mark.
- **Files**:
  - `app/src/main/res/drawable/ic_launcher_foreground.xml`: vector, art inside the 66 dp safe zone of the 108 dp
    adaptive canvas.
  - `ic_launcher_background.xml`: `#3A3A3F` (graphite since the design pass; it was deep teal `#1F3D3A` before).
  - `mipmap-anydpi-v26/ic_launcher.xml`: adaptive + monochrome. minSdk is 26, so no legacy PNG mipmaps.
  - `app/src/main/res/drawable/hatch_mark.xml`: the in-app mark (home screen, crash recovery), i.e.
    `docs/design/hatch-icon-gray.svg` as a vector: the same artwork on a `#3A3A3F` rounded square whose radius is
    the design's r14 at 44 dp (34.36 of 108 viewport units). Its colours are fixed: the mark looks the same in every
    palette and in dark mode. Draw it at 44–48 dp; `HatchShapes.Mark` is the matching clip if a caller needs one.
- **Preview**: regenerate with `cairosvg` from `docs/icon-preview.svg` (same geometry as the vector) or
  `docs/design/hatch-icon-gray.svg` (graphite background).
