# Launcher icon

A retro beige CRT monitor showing a green terminal prompt, on a deep teal background.

- **Source artwork**: Openclipart #354586 "Retro CRT monitor screen icon"
  (https://openclipart.org/detail/354586) — public domain (CC0), so no attribution is required;
  we credit it anyway. The monitor body paths are used verbatim.
- **Edits by hand** (no AI image generation): the screen was recolored from blue to dark phosphor
  (`#0D1A12`) and four green prompt lines plus a block cursor (`#5DF27A`) were added as paths.
- **Why not a modified Firefox logo**: the Firefox name and logo are Mozilla trademarks; Mozilla's
  trademark policy allows them only on unmodified official builds, and embedding GeckoView grants no
  logo rights. Hatch therefore uses its own mark.
- **Files**: `app/src/main/res/drawable/ic_launcher_foreground.xml` (vector, art inside the 66 dp safe
  zone of the 108 dp adaptive canvas), `ic_launcher_background.xml` (`#1F3D3A`),
  `mipmap-anydpi-v26/ic_launcher.xml` (adaptive + monochrome). minSdk is 26, so no legacy PNG mipmaps.
- **Preview**: regenerate with `cairosvg` from `docs/icon-preview.svg` (same geometry as the vector).
