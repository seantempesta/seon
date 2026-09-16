---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [render, contracts, instrumentation, bounded-execution, class-p4]
---

# A render producer's contract refusal is too large to admit, so the typed unknown loses its kind

## Problem

`seon.render/invocation-unknown` (`src/seon/render.clj:1016`) reads the
refusal out of `:seon.sci.admit/value`, and
`seon.render/unknown-stable-evidence` (`src/seon/render.clj:871`) derives
`:seon.render.unknown/refusal` from that value's `:seon.error/kind`. When a
render producer's DECLARED CONTRACT refuses, that value never arrives:
admission refuses it first, and the result carries only

```clojure
{:seon.sci.admit/reason :over-bound :seon.sci.admit/bytes 8388639}
```

measured live on `default` (pid 17352) for
`my.render-probe/contracted`, the armed probe producer in
`test/seon/render_coverage_test.clj:467`. The typed unknown is then built
with no `:seon.error/value`, so it says a producer "did not return:
refused" and cannot say WHAT refused — the absence-as-health class.

## Root cause

`seon.sci.kernel/failure-value` (`src/seon/sci/kernel.clj:462`) correctly
preserves an instrument refusal's own `:seon.error/kind`. But the refusal
built by `seon.instrument` embeds the OFFENDING ARGUMENT, and a render
producer's argument is a render unit carrying a Datahike database value.
`pr-str` of that refusal is 8.4 MB, over the render caps, so
`seon.sci.admit/admit-value` legitimately refuses it
(`src/seon/sci/admit.clj:732`).

A producer that THROWS is unaffected (`my.render-probe/boom` keeps
`:seon.sci.kernel/invocation-failed`): its failure value carries no
offending argument.

## Evidence

`seon.render-coverage-test/a-refused-render-producer-contributes-a-stable-typed-unknown`,
run in-process on `default`, 22 pass / 1 fail / 0 error:

```
expected: (= :seon.instrument/contract-violated (:seon.render.unknown/refusal refused))
actual: (not (= :seon.instrument/contract-violated nil))
```

This assertion was previously UNREACHABLE: the same deftest aborted earlier
with the throw described in
[a-time-limited-render-producer-passes-nil-where-the-typed-unknown-requires-a-map.md](a-time-limited-render-producer-passes-nil-where-the-typed-unknown-requires-a-map.md).
Fixing that throw made this red visible; it is not a regression from it.

## Fix shape (not chosen — needs the owner's call)

Two candidates, both outside the render boundary:

1. Bound the offending value inside `seon.instrument`'s refusal, so a
   contract refusal is always admissible. §2.4 requires a refusal to NAME
   the offending value, not to carry an unbounded copy of it; this is the
   bounded-boundary law applied to the refusal itself, and it fixes every
   caller, not just the render one.
2. Keep the refusal's `:seon.error/kind` alongside the admitted value in
   `seon.sci.kernel/invoke`'s result, so admission refusing the payload
   never costs the classification.

(1) is the smaller guarantee and deletes a failure mode rather than routing
around it.

## 2026-09-17 — the class boundary, measured

The settlement-path sibling of this class was probed on `default` (pid 30138)
and is a DIFFERENT cause: the terminal refusal fault at 2026-09-16T15:33:01Z
carries the same `{:seon.sci.admit/reason :over-bound …}` shape inline, but
there the marker is `seon.error/bounded-admission`'s designed cap with the
whole 35090-byte evidence retained in the blob tier and `:seon.error/kind`
intact on the signature row — no classification is lost, and the settlement
refused for `:seon.turn/no-such-run`, not for size
([research](../../prds/steward-platform/research/terminal-refusal-settlement-2026-09-17.md)).

So this note's defect is specific to `seon.sci.kernel/invoke`'s result, where
the refusal's `:seon.error/kind` is NOT kept beside the admitted value. Fix
shape (1) — bounding the offending argument inside `seon.instrument`'s
refusal — still deletes the failure mode rather than routing around it, and
the fault-committer path is evidence that a bounded refusal plus a blob is
enough for a reader.
