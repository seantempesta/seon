---
type: issue
status: open
severity: friction
tags: [issue, source, operator, class/p1, wave/publication-velocity]
---

# Source analysis can slice changing files with stale offsets

N1 render substitution, 2026-09-15 23:50Z: hook publication
`89f8cf19-a0d4-413c-9c5e-58c6e160170f` failed with
`Range [44072, 45681) out of bounds for length 45615` at
`seon.fn/exact-source` (fn.clj:140), through analysis-rows-by-file and
cluster/stable-manifest. The diagnostic did not name the affected file.
The next ordinary publication converged; default was never restarted.
See [the landing note](../../prds/context-generation/research/n1-render-substitution-2026-09-15.md).

Core-functions follow-up, 2026-09-14: development publication on default
again failed at `seon.fn/exact-source:142`, this time with
`IndexOutOfBoundsException` from `PersistentVector/nth`. The trace continues
through `analysis-rows-by-file`, `build-manifest`, and `cluster/stable-manifest`.
Concurrent edits were present, but the exception did not identify the source
file. The lane continued with path-limited gate snapshots and retried normal
development adoption; it did not stop or refork default.

During the 2026-09-09 schema re-declaration investigation, a plain
`bin/test-fast seon.schema-redeclare-test` child published from the changing
working tree. `seon.fn/exact-source` threw `StringIndexOutOfBoundsException`:
range 8905–9304 over a string of 9220 Java character positions. The trace runs through
`analysis-rows-by-file`, `build-manifest`, and `cluster/stable-manifest`.
The exception contains no source filename. Concurrent source edits were
present; the evidence does not identify which file changed.

`src/seon/fn.clj:125` applies the analyzer's offsets to separately supplied
source text, and `src/seon/cluster.clj:1636` checks the source digest only
after building the manifest. A changing-source refusal therefore cannot
replace this earlier raw slicing exception. The exact race still needs a
bounded regression before changing the source owner.

The path-limited snapshot iteration passed. That isolates this investigation
but does not repair live publication: source bytes and analysis offsets must
describe the same immutable input, or refusal must name the changed file.
See the [schema landing note](../../prds/context-generation/research/schema-redeclare-landing-2026-09-09.md).
