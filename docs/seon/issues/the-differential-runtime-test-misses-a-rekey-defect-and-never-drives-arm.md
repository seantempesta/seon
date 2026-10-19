---
type: issue
status: fixed (25ed6b7ec; the real-entrance rows go red under the reviews 3 mutations of the binder)
severity: defect
tags: [issue, test, sci, runtime]
---

# The differential runtime test misses a re-key defect and never drives the real entrances

`seon.sci.runtime-test/a-composed-runtime-answers-what-its-own-source-answers` drives `#'seon.sci.eval/bind-analysed!`
against a fresh-SCI oracle, but (lane runtime-rereview, 2026-10-19,
[runtime-rereview](../../research/agent-platform/2026-10-19/runtime-rereview.md) R6, R7):

- Mutation M2, dropping `(contains? reached sym)` from a function's due rule, stays green; a deterministic chain with
  the test's own helpers (main -> A: `h0` 1 -> A's child B: `h0` 2) answers `cc` 1039 under M2 where 1049 is right
  (B served A's derivation). M1, M5 and M7 go red.
- It never calls `seon.sci.arm/arm!`, `interpret-affected!`, `fork-cluster-ctx` or `derive-overlay-ctx`; `namespaces`
  is always `#{}`; its reach index is built from the map its program is generated from; no value changes and no value
  is open. With one open value holding a function (`(def vo {:f h3 :r r0})`) it is red, shrunk to
  `[[0 {}] [3 {h3 1}]]`: an open value is evaluated once and keeps the function it captured.

Proofs to add: the two-step chain as a fixed row; an open value; a namespace change; R1-R3's forms of the review as
real-program rows; steps that enter through `arm!` and the overlay.
