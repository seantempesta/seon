---
type: research
date: 2026-09-17
lane: issue-settlement (relaunch)
---

# Per-settlement issue test runs: already dissolved at `0c8f90630`

## Verdict

The assignment relaunched at 04:35Z ("`seon.plan/run-issue-tests!` runs on
EVERY settlement, 2290 ms of each 3.5 s opening pass") describes work that
**already landed** on 2026-09-15 23:22 local as `0c8f90630` ("turn: issue
tests run at the ordinary close, and only when stale") and was **proven cold**
in gate batch 30 B (`unsettled.md`:595 — "issue-settlement, issue-test
(`0c8f90630` proven cold)"). No source change was needed or made. This note
records the verification and the one live blocker found while verifying.

## What is in the tree at HEAD (read, not trusted)

- `seon.turn/closing-settlement?` (`src/seon/turn.clj:3442`) derives the close
  from the settled disposition and the undisposed agent form — the same two
  facts that emit `close-tx`. No stored flag, no cache.
- `evaluation-terminal-data` (`src/seon/turn.clj:3555`) and `settle-batch!`
  (`src/seon/turn.clj:3593`) call `plan/run-issue-tests!` only for the
  settlement that closes; a generated opening form and every system-turn
  settlement run none. `close-turn` (`src/seon/turn.clj:4839`) is the ordinary
  close and is unchanged.
- `seon.plan/stale-issue-tests` (`src/seon/plan.clj:598`) filters the open
  issue's cited tests through `seon.test/stale`, whose named arity
  (`src/seon/test.clj:481`, `stale-in` at `:459`) compares each test's recorded
  `:seon.test/reach-digest` against `runner/reach-digests` for exactly the
  supplied symbols. A test whose reach closure is unchanged since its recorded
  result is not re-run; the done query still reads `verified?`, so the step
  completes on recorded evidence. An issue with no cited tests yields an empty
  set and `run-issue-tests!` acquires no SCI context, provenance or deadline.
- `:seon.issue/resolved-tx` is still written by settlement only when every
  cited test verified (`seon.plan/issue-done-query`, `src/seon/plan.clj:585`).

The three regressions the assignment asks for exist in
`test/seon/issue_settlement_test.clj:45`
(`issue-settlement-runs-tests-and-derives-completion`), driven through the real
seam (`turn/virtual-turn!` → `turn/next-agent-work` → `turn/turn`):
a system turn records no run for either cited test; a close that changed
nothing keeps both recorded run identities; editing one reached function
re-runs exactly its test and leaves the other's identity untouched; the step
and the issue settle together (`completed` = `resolved`).

## Measured (previous lane, in-process on default PID 53378)

| Run | Before | After |
|---|---|---|
| `seon.issue-test/issue-worker-opening-links-its-issue` | 21,452 ms, 0/0/1 (exceeded the declared 20 s backstop) | 12,921 ms, 8/0/0 |
| `seon.issue-settlement-test/issue-settlement-runs-tests-and-derives-completion` | — (rewritten onto the real seam) | 13,509 ms, 32/0/0 |
| `seon.test/stale` named arity, two symbols | (whole-population digest) | 6 ms |

Per-settlement cost removed from an opening pass: 2,290 ms × (forms − 1).

## Live blocker found while verifying (not mine, not repaired)

In-process verification on default (PID 30138) is **refused JVM-wide** right
now, for every lane:

```
(seon.test.runner/provenance db)
=> {:seon.error/kind :seon.test.run/unavailable
    :seon.error/message "Test provenance unavailable: A program identity attribute declares no row schema."}
```

Cause, measured: `seon.program/authored-shapes` derives from
`seon.schema.edn/packaged-forms` — the declaration **resources as they are on
disk now** — but pairs them with the **loaded** constant
`seon.program/identity-attributes`. The in-flight rename of
`:seon.fn.file/path` → `:seon.fn.file/relative-path` is written to
`resources/seon/schemas/seon.fn.file.edn` and to `src/seon/program.cljc` in the
working tree, but only the resources are live:

```
seon.program/identity-attributes (loaded) => [… :seon.fn.file/path :seon.lint/id]
(contains? (packaged-forms) :seon.fn.file/path)          => false
(contains? (packaged-forms) :seon.fn.file/relative-path) => true
```

so `derived-shape` refuses for `:seon.fn.file/path`, `shapes` refuses, and
`program-digest` → `provenance` → every `seon.test/run` (both arities) refuses.
Adoption of `src/seon/program.cljc` clears it. Filed as
`docs/seon/issues/live-resources-outrun-the-loaded-program-identity-list.md`.

## Boundary

Verified by reading HEAD and by live queries against default's loaded program
state. No in-process test run was possible in this session (blocker above); the
green numbers above are the previous lane's in-process measurements plus the
cold gate proof in batch 30 B. No source file was changed by this lane.
