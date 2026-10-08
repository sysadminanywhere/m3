# M3 visual identity

Use the lowercase **m3** wordmark with a square orange dot. The lettering is based
on Golos Text, weight 900, with the tight spacing used on the M3 website. The SVG
files contain outlines, so the logo needs no web font or external requests.

- Brand orange: `#f36134`; hover: `#d84d24`.
- Light background lettering: `#242821`.
- Dark background lettering: `#f5f4ef`, with the same orange dot.
- Dark theme control accents: `#ffab8a`.

Shared application assets live in `src/main/resources/META-INF/resources/icons/`:
`m3-logo.svg`, `m3-logo-inverse.svg`, `m3-logo-white.svg` and `m3-favicon.svg`.
`m3-cube.svg` is retained as a compatibility filename for the new favicon.
The website in the adjacent `m3.site` repository uses identical copies; its
square icon is named `favicon.svg`.

`BrandMark` provides the shared login, navigation and header logo. Scale consumes
that component and these resources from Community during its normal build.
Aura controls and application accents use the brand orange. Status colors still
indicate success, warnings and errors.

The glyph outlines derive from the Google Fonts Golos Text distribution under
the SIL Open Font License 1.1. The accompanying license is
`src/main/resources/META-INF/resources/icons/GolosText-OFL.txt`; retain it with
redistributed logo assets. The website contains the same license file.
