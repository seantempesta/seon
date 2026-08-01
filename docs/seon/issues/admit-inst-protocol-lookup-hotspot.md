---
type: issue
status: open
tags: [issue, sci]
---

# `admit` pays a protocol lookup per node via `clojure.core/inst?`

`src/seon/sci/admit.clj` (~line 249 region) calls `inst?` —
`(satisfies? Inst x)` — during classification, a protocol scan that the
2026-08-01 caps investigation measured at 29–41 KB allocated per call,
making `admit` ~7× slower and ~26× more allocating than a plain
`postwalk` rebuild
(`research/admission-caps-and-blob-fallback-2026-08-01.md`, probe
scripts `tmp/admission-caps-2026-08-01/`).

Fix: test `(instance? java.util.Date x)` first and fall through to the
protocol only for exotic Inst types. One line, but it must land BEFORE
the admission caps are raised (the generous caps multiply the per-node
cost).

Acceptance: the probe's per-node allocation drops to the plain-walk
class; a micro-benchmark note in the commit.
