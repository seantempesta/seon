---
type: research
status: active
tags: [render, faults, ai, verification]
---

# Fault rendering and provider refusal — 2026-09-09

Read the two assigned issue notes end to end, AGENTS.md's copied PRD §10
lane instructions, and the turn PRD §15 end to end. Read the active roadmap
and working edge. Verification uses SEON_TEST_WORKERS=3.

## Inherited state and boundaries

Default PID 38717 answered status and MCP: three error signatures, six failed
runs. The browser connector returned `No browser is available`; native Chrome
and Brave each returned `cgWindowNotFound` (-10005). Screenshot verification
is unavailable through the attached computer surface. Default was never
stopped, reforked, or restarted.

During the first slice the turn rename began editing the shared tree,
including walk.clj, error.clj, loop.clj, and their consumers. Verification
therefore uses `tmp/faults-render-wt` at a991283de, with only this lane's
changes and the repository's reference-code linked. Foreign edits are
preserved. The loop hunk will be recorded here while that owner is editing it.

## Slice 1: ordered evaluation settlement

Live JVM probe before the fix returned:

```
seon.render.walk/ordered-episode violated its contract (invalid-input): must be a print node at [[:seon.repl/settled 0 :seon.sci.admit/print-node]]
```

The settlement contract admits saved shown text, output, and error. Settlement
presence advances the prefix even without an object; shown text never reaches
the structural reference walker. Older callers' actual print nodes retain
their reference discovery, guarded by the dependency-owned `print/node?`.

Dependency ledger: `src/seon/print.cljc:897` owns iterative node validation;
`:778` owns structural references. `resources/seon/schemas/seon.eval.edn`
declares shown text. `seon.eval/of-agent` supplies the stored evaluation in
the canonical fixture regression. No serialization or text decoding is added.

Fast fixture proof: 1 test, 7 assertions, zero failures/errors. Isolated gate: 1 test, 7 assertions, zero failures/errors. The older bootstrap caller still decodes shown text in
`src/seon/bootstrap.clj:599`; that file is concurrently held by turn-rename.
This slice prevents its non-node values from faulting ordered-episode but does
not claim to replace the retired opening algorithm.
