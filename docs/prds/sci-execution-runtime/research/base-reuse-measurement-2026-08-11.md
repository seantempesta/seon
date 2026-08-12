---
type: research
status: complete
tags: [testing, performance, sci, datahike, operator]
---

# Base reuse measurement — 2026-08-11

## Scope and grounding

This implementation read
[`parallel-runner-measurement-2026-08-10.md`](parallel-runner-measurement-2026-08-10.md)
and
[`parallel-test-stress-exposes-eleven-isolation-sensitive-tests.md`](../../../seon/issues/parallel-test-stress-exposes-eleven-isolation-sensitive-tests.md)
end to end before changing fixtures. It also read the complete Clojure-testing,
data-oriented-Clojure, and Datahike skills before fixture work.

The dependency ledger is:

- SCI `6ee57c9c3e73`, especially
  `reference-code/sci/src/sci/core.cljc`'s generation-aware `fork`;
- Datahike `10540578248e`, especially
  `reference-code/datahike/src/datahike/versioning.cljc`'s `branch!`,
  `force-branch!`, and `delete-branch!` semantics;
- the shared source/database base commits `e36377a02`, `6bb9cfe8d`,
  `d50275487`, and `68eedf2e2`;
- `test/seon/sci/eval_test.clj`'s acquired-context sibling regressions; and
- `test/seon/test_support.clj`'s existing immutable database base and
  per-test `with-branched-database` / `fork-cluster-ctx` seams.

## Decisions

### Acquired SCI contexts

Ordinary semantics tests that called `sci.eval/cluster-ctx` now call the
already-existing `seon.test-support/fork-cluster-ctx`. Each invocation gets a
generation-aware SCI fork carrying its own branch connection and projection
state. The immutable worker base is never handed to a test.

This changed no assertion and no proof subject. The tests still exercise the
same prompt, loop, render, effect, error, search, and transaction functions;
only the program-context construction below them changed from acquisition to
copy-on-write fork. Existing regressions
`acquired-source-context-forks-have-branch-custody-and-private-defs` and
`acquired-source-context-forks-own-their-lazy-program-state` prove that a
definition or installation in one fork cannot mutate its sibling.

The attempted boot/armed injection was **not taken**. It added an optional SCI
context to production `cluster/start!`, a worker preparation hook, and a test
start wrapper. `seon.cluster.armed-test` measured 160.04 seconds before and
171.41 seconds after, while the change added 21 net lines and did not reduce
the test-author concept count. Commits `12ec5433a` and `ef9073ada` record the
attempt; `8ae44707a` removes the extra mechanism while retaining only the
simpler ordinary-test conversions.

As a representative accepted conversion, the seven
`seon.cluster.prompt-test` tasks cost 9.339 seconds in the prior full-run log
and 1.270 seconds in the final full run, a 7.35× reduction in test work. Their
focused gate ran 63 tests / 301 assertions with zero failures or errors,
including the two SCI sibling-isolation regressions.

### Fresh operator split

The current fresh-operator family contains 31 test Vars, not approximately
109: 30 in `seon.dev.fresh-operator-test` and one export test. The split below
comes from each assertion, not from a maintained selector.

Sixteen tests prove same-JVM logic honestly:

- init changed-path parsing;
- down argument parsing;
- installation-claim root scoping;
- rejection of the legacy record directory;
- config command selection;
- destructive-stop selection;
- cold-start call ordering;
- both readiness phase/silence cases;
- both prepl progress/silence cases;
- shared child-launch source topology;
- exact unreadable-claim discard after the flock check;
- stale instrumentation refresh;
- retryable start-sweep refusal; and
- child environment precedence.

These now require `seon.fresh-operator` once and call its owning functions.
They no longer generate quoted Clojure programs, start a Babashka process,
encode the result through stdout, and decode EDN. Fifteen representative
members measured 3.590 seconds in the prior runner log and 2.075 seconds in
the final full run (1.953 seconds in a focused loaded-JVM probe), a 1.73×
full-load reduction. The converted file is 137 lines added and 322 deleted
(net −185). Their assertions and proof subjects are unchanged.

Fifteen tests genuinely prove a process boundary and retain plain cold
children:

- process-record round-trip plus PID/start-instant reuse fencing;
- down reaping every recorded process;
- `bin/seon --root` process selection;
- the isolated-root launch command's child classpath and root property;
- complete initialization and dormant-cluster lifecycle;
- live initialization and reload;
- source-less destructive reset;
- forced reset after `kill -9`;
- reopening a populated cluster across two JVM identities;
- schema instrumentation in a genuinely fresh process;
- cached boot refusal followed by readiness;
- SIGTERM fallback after a failed prepl stop;
- refusal to SIGTERM a shared JVM without force;
- cross-process operator-root lifecycle locks; and
- export of a live store through the real operator.

A pre-warmed child pool was **not taken**. These tests need different roots,
signals, environment, locks, store lifetimes, and JVM generations. A pool
would add claiming, reset, health, and reaping state while leaving most cold
boundary construction intact. It is therefore more complicated than the
retained children before any timing claim could justify it.

### Datahike branches

The requested template mechanism already exists as one function:
`with-branched-database` forks the immutable `database-base` branch, opens a
test-private connection/writer, and retires the branch after the test. No live
database connection is shared between tests.

The remaining census found six explicit `fresh-store?` calls. All store or
retrieve blobs, whose Konserve keys live outside Datahike branch facts; sibling
branches would therefore share mutable bytes and violate isolation by
construction. The other direct `d/create-database` sites either install a
small test-specific schema, prove database creation/configuration semantics,
require `:keep-history? false`, or deliberately reopen the same physical
database from a child process. No eligible repeated full-population layer
remains, so no second template or freshness protocol was added.

The class regression remains
`seon.test-support-test/concurrent-fixtures-own-distinct-branches`: concurrent
fixtures transact independently and cannot see a sibling's datoms.

## Simplicity ledger

| Layer | Support/fixture lines added | Lines deleted | Test-author concepts before → after | Verdict |
|---|---:|---:|---:|---|
| Ordinary acquired-context reuse | 26 | 29 | database value + connection + acquisition → connection + fixture fork (3 → 2) | Taken |
| Boot/armed acquired-context injection | 106 | 85 | production start + acquisition → fixture start + injected base (2 → 2) | Not taken; fully removed |
| Same-JVM operator logic | 137 | 322 | generated program + child process + stdout EDN + assertion domain → owning function + assertion domain (4 → 2) | Taken |
| Pre-warmed child pool | 0 | 0 | cold child → pool claim + reset + health + reaping (1 → 4) | Not taken |
| New database template | 0 | 0 | existing branch fixture (1) → proposed second template/freshness path (2+) | Not taken |

## Final full-suite measurement

The frozen-tree command was:

```sh
/usr/bin/time -p bin/test --full 2>&1 \
  | tee tmp/full-gate-base-reuse-final-2026-08-11.log
```

The tested source was `8ae44707aa7fe40a78a258f4d6eea0c7346961b4`.
The runner selected 1,160 tests: 73 platform and 1,087 bulk. It aggregated
10,004 assertions, 9 failures, and 11 errors across 17 failing tests. The
complete red diagnostic took **39:06.62** wall (`real 2346.62`, `user
6123.68`, `sys 1104.02`).

The execution window from the first platform task at
`14:19:32.353358Z` through the last pool result at `14:34:59.307586Z` was
**15:26.95**. This misses the owner's eight-minute maximum by 7:26.95 and is
only 5.99 seconds faster than the prior 15:32.94 pool measurement. The
accepted local reductions are real, but too small relative to total suite
work to move the wall clock materially.

Confirmations classified exactly 11 failures as parallel-only and six as
reproducible. The parallel-only set is the already documented isolation class:
no failure was suppressed or moved out of the pool. The six reproducible
failures were:

- `seon.cluster.armed-test/two-clusters-in-one-jvm-own-distinct-live-program-contexts`;
- `seon.cluster.program-restart-test/an-agent-definition-survives-restart-and-another-agent-calls-it`;
- `seon.dev.fresh-operator-test/fresh-process-loads-schema-before-every-operator-instrumentation`;
- `seon.public-contract-test/every-fresh-public-function-has-a-complete-contract`;
- `seon.render-simplification-test/nested-values-render-their-declared-faces`;
  and
- `seon.test-runner-test/interrupted-launcher-awaits-its-runner-before-retaining-the-root`.

None is newly caused by an accepted conversion. The armed, program-restart,
fresh-process, public-contract, and runner files are unchanged at the relevant
proof. Nested declared faces was the known reproducible pending NESTED
contract before this lane; the conversion changed only its context
construction, and its identical missing nested producer face reproduces in an
isolated confirmation.

## Honest remainder

The final pool's largest costs show what remains after the accepted reuse:

| Test or family | Final pool cost | What would cut it without weakening proof |
|---|---:|---|
| `operator-test/public-contracts-refuse-invalid-input-and-output` | 250.580 s | Instrument only the public operator boundary under assertion instead of applying/removing the complete registry, if a focused filter preserves the same input/output contract proof. |
| `sci.eval-instrumentation-test/an-instrumented-dev-cluster-completes-one-agent-turn` | 233.603 s | A simpler reusable fully instrumented acquired base; scoping instrumentation would weaken this test's explicit whole-image proof. |
| real fresh-operator export | 200.542 s | Faster real publication/export/reopen work; a child pool does not remove its live-store and process-boundary proof. |
| three boot publication/refork tests | 537.539 s total | Reduce publication/indexing work or give the tests an honestly forkable published file-store template; the rejected SCI-context injection did not help. |
| seven `shell.jvm-test` process tests | 988.719 s total | Convert only assertions that do not require argv/stdin/signals/process-tree evidence; the current members must be read assertion by assertion. |
| six `web.jvm-test` boundary tests | 763.900 s total | Separate HTTP/body/blob logic that can use an in-process server from tests that genuinely require socket/dead-host/timeout behavior, derived assertion by assertion. |
| `background-blob-test/background-binary-results-remain-exact-across-the-inline-threshold` | 134.949 s | A physical blob-store template or cheaper population that still gives the test its own Konserve keyspace; Datahike branches alone are insufficient. |

The ceiling therefore remains a total-work problem. The next credible cuts are
the 250-second whole-registry instrumentation test, the 234-second fully
instrumented live-cluster proof, and assertion-derived splits in the shell and
web JVM families. A generic pool or second store cache would add concepts
without addressing those dominant operations.
