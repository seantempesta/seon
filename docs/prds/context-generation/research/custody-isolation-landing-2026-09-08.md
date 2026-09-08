---
type: research
status: complete
date: 2026-09-08
tags: [research, custody, cluster, db, verification]
---

# Custody isolation: the refusal was already there; the red was the runner

Written by the `custody-isolation` lane against
[AGENTS.md](../../../../AGENTS.md) (read end to end: §1 Store, §2.1, §3
`seon.db`), [the issue](../../../seon/issues/cross-cluster-writes-are-not-refused.md),
[the P1-P6 verification](verify-p1-p6-and-backlog-2026-09-08.md) (production
defect 2, §7.3), and
[the cluster/branch/SCI wake model](cluster-branch-sci-wake-model-2026-09-07.md),
plus the `datahike` and `data-oriented-clojure` skills.

## 0. Headline

**The assignment's premise is falsified.** A write naming another cluster's
branch **is** refused at HEAD, with a typed `:seon.db/foreign-connection`
value, and nothing reaches the foreign branch. Proven three ways: in-process,
live on two clusters sharing one store in one JVM, and through the alpha
cluster's own live SCI evaluation path.

What was actually red is not custody. `seon.custody-stability-test` failed
because the **pooled test worker died in `seon.test.runner/arm-contracts!`**
during a mid-run re-arm — a worker-level instrumentation fault that took the
whole namespace down before `cross-cluster-write-isolation` ever executed.
The runner's own confirmation phase said so in the same run: verdict
`parallel-only`, i.e. the test **passes in isolation**.

Delivered anyway: the refusal now names **both branches** rather than only two
connection ids, and a class regression asserts the whole invariant at the
write seam.

| claim | verdict | evidence |
|---|---|---|
| cross-cluster write is not refused | **FALSIFIED** | §1, §2 |
| refusal is decided at the write seam, not a pre-read | **HOLDS** | §3 |
| `seon.custody-stability-test` red = custody | **FALSIFIED** | §4 |
| the sibling `sci.eval-test` custody red has the same cause | §5 |
| `bin/test --platform` | **GREEN 73 / 398 / 0** | §6 |

## 1. Reproduction, live, on two clusters

Scratch root `tmp/custody-root`, `bin/seon --root tmp/custody-root init`, then
`start alpha` and `start beta`. **One JVM (pid 64719) hosts both**: one store,
two branches — `[f0d0f2b4-… "cluster-alpha"]` and
`[f0d0f2b4-… "cluster-beta"]`. That is the shape the invariant is about; two
separate JVMs on one store is the thing the `flock` prevents outright, so the
cross-cluster write is only constructible inside one process.

`mcp__seon__eval_clj` jvm mode, alpha, with alpha's connection as the writing
custody:

```clojure
{:own-kind nil
 :elided-kind nil
 :foreign-kind :seon.db/foreign-connection
 :foreign-message "The explicit transaction connection does not belong to the calling agent's cluster."
 :foreign-data {:seon.db/ambient-connection-id  [#uuid "f0d0f2b4-…" :cluster-alpha]
                :seon.db/explicit-connection-id [#uuid "f0d0f2b4-…" :cluster-beta]}
 :alpha-has #{"custody-own-live" "custody-elided-live"}
 :beta-count-before 1, :beta-count-after 1}
```

Both the explicit-own and the elided arity commit; the foreign write returns a
flat typed error and **beta's datom count is unchanged**.

## 2. The same thing through the agent's own evaluation path

Beta's connection interned into alpha's live cluster SCI ctx, then evaluated
through `seon.sci.eval/evaluate` on that ctx (the path a turn uses):

```clojure
{:foreign-kind :seon.db/foreign-connection
 :foreign-data {:seon.db/ambient-connection-id  [… :cluster-alpha]
                :seon.db/explicit-connection-id [… :cluster-beta]}
 :own-error nil, :beta-before 1, :beta-after 1}
```

`seon.sci.eval/evaluate` binds `seon.db/*conn*` from
`(get-in ctx [::custody :seon.db/connection])` (`src/seon/sci/eval.clj:2385`),
so the writing custody an agent evaluation carries is the ctx's, never a
thread's leftover binding.

## 3. The seam, and why it is not a pre-read

`seon.db/transact!` is the **only** write in `seon.db` — every other public
function there reads. Its two-arity arm calls `foreign-connection-error`
immediately before `transact-call` hands the connection to Datahike, and both
identities are read from the two connections the call itself holds. Nothing
can change between the decision and the write it governs, so this is a
decision at the authority, not a pre-read of a mirror.

`src/seon/db.clj` — the change this lane made is the message and the data,
not the decision point:

```clojure
(defn- connection-branch
  [connection]
  (:branch (:config @connection)))
```

and the refusal now carries `:seon.db/ambient-branch` and
`:seon.db/explicit-branch` alongside the two connection ids, with a message
that names both branches and says what to do instead. Accretion only: the two
pre-existing keys and the kind are unchanged.

## 4. What `seon.custody-stability-test` was actually failing on

`bin/test seon.custody-stability-test` at the start of this lane: 3 errors, 0
failures — **no custody assertion ran**. Worker `pool-1` exited 1; its stderr
(`tmp/test-runs/run.WxuFp0/workers/pool-1/logs/worker-stderr.log`):

```text
Execution error (ExceptionInfo) at seon.instrument/throwing-report$fn (instrument.clj:471).
seon.config/result-caps violated its contract (invalid-input):
  should be a string at [[:seon.config.ai.backup/api-key-variable] …] and 14 more
```

The saved report's stack frames name the exact seam (PROTECTED for this lane —
`src/seon/test/runner.clj`):

```text
seon.test.runner$arm_contracts_BANG_        runner.clj 897
seon.test.runner$reassert_contracts_BANG_   runner.clj 968
seon.test.runner$serve_worker_commands_BANG_ runner.clj 1023
```

`arm-contracts!` line 897 is `caps (config/result-caps decisions)`. On the
FIRST arm nothing is instrumented yet, so it answers. On the mid-run **re-arm**
(`reassert-contracts!`, runner.clj:968) the same call runs through its own
armed `:panic` contract and throws — killing the worker, and with it every
task it had been handed. All 18 reported problems are absent OPTIONAL config
dials (`:seon.config.ai.backup/*`, `:seon.config.ai/*`, `:seon.config.shell/*`,
`:seon.config.web/port`, …).

The verdict the same run printed for the same test is the tell:

```text
bin/test: confirmation parallel-only seon.custody-stability-test/cross-cluster-write-isolation
```

`parallel-only` = red in the pool, **green in isolation**. The custody
assertion passes; the pool killed the worker before it.

**This is not reproducible on demand.** After the change, the same selection
runs green (§6), and three later runs never re-armed. What is certain: the
throw is at `runner.clj:897`, it is not a custody defect, and it is inside a
protected file.

### The hunk for the runner's owner

`config/defaults` is a legitimate `:seon.config/effective` under the packaged
declaration projection — this lane verified it (`m/explain` → valid; armed
call with 877 instrumented vars → answers). So the re-arm's violation is a
projection disagreement *at re-arm time*, not a bad value. Two options, in
order of preference:

1. **Do not re-derive caps under the armed world.** `reassert-contracts!`
   already holds the `::instrumented`/`::projection`/`::namespaces` it armed
   with; carry the `caps` and `on-core-error` it decided at initialization
   into the re-arm instead of recomputing them:

   ```clojure
   ;; runner.clj — arm-contracts! takes the decided caps rather than deriving
   [projection worker-id namespaces caps on-core-error]
   ;; and reassert-contracts! passes the ones ::arming already recorded.
   ```

   This is the §2.1 shape (the value carries its world) and removes the only
   contracted call the re-arm makes.
2. If the caps must be re-derived, derive them **before** the armed world can
   observe them (`instrument/remove!` then arm), so a re-arm cannot be
   defeated by the arming it is repairing.

Either way the current behaviour is the §2.3 defect in miniature: a bound
firing *inside* the repair path takes the worker down instead of reporting.

## 5. The sibling custody red

`seon.sci.eval-test/evaluation-custody-is-derived-only-from-the-cluster-context`
asserts a DIFFERENT invariant: that an uncustodied base ctx refuses with
`:seon.db/missing-connection-binding` and that each sibling ctx reads its own
branch (`test/seon/sci/eval_test.clj:1563-1637`). It never asserts a
`foreign-connection` refusal. Verify-repair-2's summary of it as
"expected a custody refusal, got nil" is about the missing-binding arm, not
this one.

**Measured, not assumed.** `bin/test seon.sci.eval-test` at HEAD:
`evaluation-custody-is-derived-only-from-the-cluster-context` **ran and
passed** (3.2 s, worker pool-1). So the answer to "does the earlier custody
red have the same cause" is: it has neither cause — it is not a
foreign-connection assertion, and it is green today. That namespace does carry
8 other reds, none of them custody
(`a-set-print-length-survives-to-the-turns-next-form`,
`agent-contracts-apply-on-acquire-and-cold-recovery`,
`an-acquired-function-uses-the-current-evaluation-limit`,
`generated-sources-compose-fork-guard-and-admission`,
`one-unloadable-row-cannot-prevent-cold-acquisition`,
`public-walk-is-callable-through-an-agent-sci-eval`,
`runtime-function-rows-carry-parsed-contract-facts`,
`static-and-runtime-contracted-definitions-publish-identical-facts`;
68 tests / 321 assertions / 16 failures / 3 errors) — other lanes' ground.

## 6. Gates

| gate | result |
|---|---|
| `bin/test seon.custody-stability-test` | **5 tests / 26 assertions / 0 / 0** |
| `bin/test seon.env-test seon.cluster.store-test seon.custody-stability-test` | custody + env green; 2 store reds (§7) |
| `bin/test seon.cluster.store-test` alone | **17 / 62 / 0 / 0** |
| `bin/test seon.db-test` | new regression green; 4 pre-existing reds (§7) |
| `bin/test --platform` | **GREEN — 73 / 398 / 0** |

The class regression is
`seon.db-test/a-write-naming-another-clusters-branch-is-refused-naming-both`:
one bound writing custody, three writes (explicit-own, elided, foreign), and
the two branch names read back out of the refusal, with the foreign branch
asserted empty.

## 7. Findings this lane did not own

1. **`bin/test seon.db-test` carries 4 pre-existing reds**, all instrumentation
   contract violations untouched by this lane:
   `seon.db/pull` invalid-input `[:selector]`, `seon.db/as-of` and
   `seon.db/since` "should be an inst", `seon.instrument/apply!` invalid-input
   `[:seon.sci.admit/caps]`, and `my.message/inbox` invalid-output inside
   `seon.db/diff` (a `nil` `:my.message/content` in the fixture).
2. **`seon.cluster.store-test` is red only under pool load** — both flock tests
   fail with "The test future did not publish its required completion" when the
   selection includes other namespaces, green alone. A bound firing that names
   nothing about its subject.
3. **MCP `door` mode is broken on this build.** Every SCI-evaluation-mode call
   returns
   `seon.sci.eval/evaluate violated its contract (invalid-input): missing required key at [[:seon.cluster.eval/source]]`
   — the MCP door path builds its request without the source key. Reported
   immediately per AGENTS §4.
4. **The edit hook's `current-src` publication is failing for the dev cluster**
   (`logs/current-source-failure.log`):
   `seon.schema/canonical-definition violated its contract (invalid-output):
   must be a parseable, EDN-readable Malli form`, from `seon.fn/var-row`
   (`fn.clj:391`) on
   `[:=> [:cat :seon.db/database-value :seon.cluster.run/process :inst
   :seon.cluster.agent/creation-request] :seon.store/transaction-data]`.
   Pre-existing and unrelated to this lane's edits; it means every hook-driven
   publication in this session left the dev cluster on the previous commit.
