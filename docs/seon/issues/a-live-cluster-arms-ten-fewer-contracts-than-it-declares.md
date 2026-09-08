---
type: issue
status: open
severity: friction
tags: [issue, runtime, effect, test, wave/contract-gate]
---

# A live cluster arms ten fewer contracts than it declares

Found 2026-09-08 by `test-harness` while building the gate's parity check
([landing note](../../prds/context-generation/research/test-harness-landing-2026-09-08.md) §1).

Measured on the live `default` cluster (`mcp__seon__eval_clj`, `jvm` mode),
deriving the armable set with malli's own two rules — `mi/-schema` plus the
`clojure.lang.IFn$` primitive exclusion
(`reference-code/malli/src/malli/instrument.clj:16,24`):

```clojure
{:program-namespaces 91
 :cluster-loaded-ns 518
 :cluster-instrumented 812
 :armable-in-program 820
 :armable-not-armed ["seon.edit.jvm/edit"
                     "seon.fs.jvm/glob" "seon.fs.jvm/read"
                     "seon.fs.jvm/read-complete" "seon.fs.jvm/stat"
                     "seon.fs.jvm/write"
                     "seon.shell.jvm/run"
                     "seon.test.runner/run-coordinator!"
                     "seon.web.jvm/fetch" "seon.web.jvm/search"]}
```

Nine of the ten are **the capability implementations themselves** — the
functions that actually touch the filesystem, run a shell command, and fetch
the web. Their declared contracts are enforced on nothing.

## Why

`seon.instrument/apply!` instruments what is LOADED when it runs. Boot arms
once (`script/seon/fresh_operator.clj` `instrument-form`), and the `*.jvm`
implementations are resolved lazily at first effect execution — so they load
AFTER arming and are never collected. Nothing re-arms, and nothing asks: the
count boot prints is positive, so the gap is invisible.

This is the project's recurring class from the other side. The gate now
refuses when any declared program contract carries no wrapper
(`seon.test.runner/arm-contracts!`, regression
`seon.test-runner-test/arming-refuses-when-a-program-contract-carries-no-wrapper`),
so the GATE arms 876 program contracts across all 91 namespaces. A cluster
arms 810 of the 820 it has loaded. The gate is now a strict superset, which is
the safe direction — but the cluster is the thing production runs.

## Fix

Boot should require every declared program namespace before it arms, exactly
as the worker now does (`seon.test.runner/declared-program-namespaces` derives
the set from each file's own `ns` form), and then refuse loudly on any armable
contract left unarmed. Not done here: it is a boot-path change wanting the
reset-boundary live proof, and three lanes held live clusters at the time.

## Acceptance criteria

- A booted cluster's `armable`-minus-`instrumented` difference over the
  program namespaces is EMPTY, asserted by a live-boot regression.
- The ten named above are armed, and an effect execution through
  `seon.fs.jvm/write` with a contract-violating argument refuses as a typed
  value rather than running.
