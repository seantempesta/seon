---
type: issue
status: open
severity: friction
created: 2026-09-19
tags: [testing, selection, wave/dev-tooling-face-hygiene]
---

# Fast overlay admission prints the complete program manifest

A1's third fast snapshot printed the complete `:seon.fn.manifest` value
between its overlay-graph announcement and test-slot acquisition. The raw
evidence is `tmp/a1-fast-3.log`, from the assigned three-namespace
`bin/test-fast --paths` invocation recorded in
[the A1 landing note](../../prds/steward-platform/research/test-selector-a1-2026-09-19.md).
The manifest line is roughly 19 MB and includes every artifact and analyzer
finding. A routine tail therefore returns millions of tokens instead of a
usable phase observation. The JVM still starts; this is an output defect,
not a selection failure.

Verify the return-printing boundary in the launcher's overlay admission
expression and print only its intended diagnostic. A1 does not own the
dirty `bin/test`; no cause beyond the observed output is asserted here.
The recurring proof should positively observe a bounded admission
announcement and absence of a rendered manifest on a successful overlay.
