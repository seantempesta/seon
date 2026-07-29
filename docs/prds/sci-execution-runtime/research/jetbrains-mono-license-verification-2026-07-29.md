---
type: research
status: complete
tags: [research, web, font, license, release]
---

# JetBrains Mono artifact license verification

## Verdict

The font copied into historical Seon and ACME packages is licensable. It is
JetBrains Mono 2.211 under the SIL Open Font License 1.1, so no font-family swap
decision is warranted on licensing grounds. Packaging still owes provenance,
the accompanying OFL text, source-controlled webfont assets, honest weight
names, and the weights the stylesheet uses.

## Artifact identity

The inspected package member was:

```text
/Users/sean/seon-release-75fc8a21-ro/runtime-root/resources/public/fonts/jetbrains-mono-500.woff2
```

Its SHA-256 is:

```text
e7842e5e71de83b9b21b7858311a4f95f621d24421f6b968a3e04b561ce17187
```

Every inspected Seon and ACME package copy has that digest. FontTools decoded
the WOFF2 name and OS/2 tables:

```text
family: JetBrains Mono
style: Regular
PostScript name: JetBrainsMono-Regular
version: 2.211
copyright: Copyright 2020 The JetBrains Mono Project Authors
license: SIL Open Font License, Version 1.1
weight class: 400
```

The package filename is therefore wrong twice: `-500` describes neither the
font's internal style nor its OS/2 weight. The artifact contains Regular 400,
not Medium 500.

## License authority

The binary's own name table identifies the font software as SIL OFL 1.1.
JetBrains' maintained [JetBrains Mono page](https://www.jetbrains.com/lp/mono/)
independently states that the typeface is available under SIL OFL 1.1 for
commercial and non-commercial use. The upstream
[JetBrains Mono repository](https://github.com/JetBrains/JetBrainsMono)
publishes the same license and the full font family.

OFL permits redistribution, but the license text must accompany redistributed
font software. The historical packages contain no JetBrains Mono OFL entry in
`THIRD_PARTY_LICENSES/`, so those package snapshots do not provide the required
accompanying license even though the font itself is eligible to ship.

## Remaining packaging contract

The asset/package owner must:

- vendor identified upstream files and their provenance into the source tree;
- ship the exact OFL text alongside every redistributed font artifact;
- name each file and `@font-face` by its actual internal weight;
- provide every weight the CSS selects or stop selecting that weight; and
- make the release inventory fail when a font member lacks its license member.

This is packaging work, not a reason to select a different design font.
