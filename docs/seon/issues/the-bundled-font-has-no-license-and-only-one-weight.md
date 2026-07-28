---
type: issue
status: open
severity: friction
tags: [issue, web, build]
---

# The design language's font is redistributed without its license, and only at one weight

## Problem

Two facts that have to be fixed together, because fixing either alone leaves
the design language lying about itself.

1. **The source tree has no font at all.** `resources/public/css/input.css:58`
   declares `--font-mono: 'JetBrains Mono', 'SF Mono', ui-monospace, …` with no
   `@font-face` and no bundled file anywhere under `/Users/sean/src/seon`. Every
   machine without JetBrains Mono installed locally renders the entire terminal
   aesthetic in SF Mono and says nothing. A design language that silently
   becomes a different design language on someone else's machine is not a
   design language.
2. **Release artifacts DO ship the font, with no license.**
   `runtime-root/resources/public/fonts/jetbrains-mono-500.woff2` (39,012 bytes,
   verified WOFF2/TrueType) is present in every `seon-release-*` and
   `acme-*` package on this machine. JetBrains Mono is SIL OFL 1.1, which
   permits redistribution *provided the license accompanies the font*. The
   packages carry `THIRD_PARTY_LICENSES/` with entries for babashka, bun and
   datahike, and **no entry for the font** and no `OFL.txt` anywhere.

So the shipped artifact already redistributes an OFL font without its license,
and the source tree the artifact is built from does not contain the font to
point a reader at.

Third fact, smaller but real: only weight **500** exists, while the stylesheet
asks for `font-semibold` and `font-bold`
(`resources/public/css/input.css:35`). Those weights are synthesized by the
browser — faux bold — so even a machine WITH the font does not see the intended
type.

## Evidence

```text
$ find / -iname "*jetbrains*mono*" | head -1
/Users/sean/seon-release-75fc8a21-ro/runtime-root/resources/public/fonts/jetbrains-mono-500.woff2

$ file …/jetbrains-mono-500.woff2
Web Open Font Format (Version 2), TrueType, length 39012, version 1.0

$ ls /Users/sean/seon-release-75fc8a21-ro/THIRD_PARTY_LICENSES/
babashka-EPL-1.0.txt   bun-LICENSE.md   datahike-EPL-1.0.txt

$ grep -rl -i "jetbrains\|SIL Open Font" …/THIRD_PARTY_LICENSES/
(no output)

$ git log --all --oneline -S "jetbrains-mono" -- resources/
(no output — the font has never been tracked in this repository)
```

`resources/public/css/input.css:57-58`:

```css
  /* Fonts - JetBrains Mono for terminal aesthetic */
  --font-mono: 'JetBrains Mono', 'SF Mono', ui-monospace, Menlo, Monaco, 'Cascadia Mono', monospace;
```

## Impact

Owner ruling, 2026-07-27: bundle the font, because "a design language that
silently falls back on other machines is a lie about itself" — and flag rather
than silently fall back if licensing or size argues otherwise. Licensing argues.

This blocks nothing in the N4 render rung: `bin/css` and the block/serializer
contracts are independent of which font resolves. It is filed rather than
fixed because the remedy needs two things this lane cannot produce offline —
the OFL text and the missing weights — and because copying an unlicensed binary
into the source tree would widen the compliance gap rather than close it.

## Owner

The stylesheet/asset port (`bin/css`, `resources/public/css/input.css`,
`resources/public/fonts/`) plus whatever assembles `THIRD_PARTY_LICENSES/` in
the release build.

## Acceptance

- `resources/public/fonts/` contains the weights the stylesheet actually uses
  (400, 500 and 700 at minimum — anything the CSS names must exist, or the CSS
  stops naming it), tracked in this repository.
- `resources/public/css/input.css` declares an `@font-face` per bundled weight
  with `font-display: swap`, so the fallback is a deliberate transition rather
  than a permanent silent substitution.
- `OFL.txt` accompanies the fonts, and the release build emits a
  `THIRD_PARTY_LICENSES/` entry for them alongside babashka, bun and datahike.
- A check fails loudly when the stylesheet names a font family that no bundled
  `@font-face` provides — the silent-fallback class made unrepresentable rather
  than remembered.

## Notes

Found 2026-07-27 while porting the Tailwind build (`bin/css`) for N4. The port
itself is complete and independent of this.
