---
type: research
date: 2026-09-16
---

# Batch 68: boot-test reds, by class

Thread: bounded triage-and-fix over `tmp/orchestrator/gate-results/batch-68/named.md`
(HEAD `d090c9934`, branch `steward-platform`). Written 2026-09-16T06:59-0600.
No test JVM was run from this thread; every claim below is source, git history,
or a live probe in the default JVM (pid 63433), and each is labelled.

Scope handed over mid-thread: `seon.call-preparation-test`'s two ERRORs and
boot-test's `a-dead-holders-run-is-unclaimed-by-the-time-start-returns`
expectations belong to the other session and are untouched here.

## Class 1 (dominant, 8 of 12 boot-test reds) — a retained record was required to be live

### Cause

`seon.cluster/stop!` declares `[:=> [:cat :seon.boot/instance] :nil]`
(`src/seon/cluster.clj:3544`) and its docstring rules
"Idempotent — stopping a stopped instance is a no-op returning nil"
(`src/seon/cluster.clj:3542`). `:seon.boot/instance` reached three opaque
resources; two of them demanded LIVENESS:

- `[:seon.store/store :seon.store/connection-object]` →
  `seon.cluster.store/connection?`, "must be a live unreleased Datahike
  connection held by this process root" (`resources/seon/schemas/seon.store.edn:17-22`
  at `d090c9934`), and its sibling `[:seon.store/store :seon.store/lock]` →
  `file-lock?`, which also asserts `.isValid` (`src/seon/cluster/store.clj:60-64`);
- `[:seon.turn.loop/cluster :seon.db/connection]` → `seon.db/connection?`,
  "must be a live unreleased Datahike connection from the calling cluster"
  (`resources/seon/schemas/seon.db.edn:128-135`, `src/seon/db.clj:42-48`).

A stopped instance necessarily holds all three released: `stop!` releases the
branch connection (`src/seon/cluster.clj:3556`), then drops its hold on the
process-root store, whose last holder calls `release-store!`
(`src/seon/cluster.clj:928`), and the caller keeps the same instance VALUE. So
the contract refused exactly the case the function exists to handle, before its
body — and therefore before `claim-stop!` could answer "already stopped".

Every red in this class is that one refusal:

| test | the stop! that refused |
|---|---|
| `two-instances-are-isolated` (3 E) | the idempotent second and third stops, `boot_test.clj:516,517,519` |
| `a-delayed-stop-never-kills-a-replacement` | the delayed stop of the superseded instance, `:573` |
| `a-failed-stop-remains-addressable-and-retryable` (2 E + 4 F) | the RETRY after an injected release failure, `:662`, `:681`; the four FAILs at `:663,665,669,670` are that refusal's consequences — the retry body never ran |
| `orderly-stop-awaits-the-active-loop-pass` | the cleanup stop, `:752` |
| `explicit-refork-destroys-the-old-branch-and-forks-current-source` | the idempotent cleanup stop after the refork already stopped it, `:1511` |

### Which commit introduced it

Not `ccccea806` and not `fe44a981b` (both were suspects in the handoff; both
falsified). `stop!`'s contract has been `:seon.boot/instance` since
`2d2655922`, and the store map's liveness member predates `fe44a981b`, which
only renamed `:seon.store/connection` to `:seon.store/connection-object`
(`git log -p -S "connection?" -- resources/seon/schemas/seon.store.edn`). The
class was LATENT until the gate armed contracts; `6215ff0bc` ("instrumentation,
and the four contracts it caught") met it on instrumentation's first run,
introduced `connection-object?` for exactly this reason
(`src/seon/cluster/store.clj:46-57` carries that reasoning verbatim) and
relaxed ONE member — `:seon.boot/cluster-connection`
(`resources/seon/schemas/seon.boot.edn:57-59`). These are the two it left.

### Fix (`7f99fe695`)

Liveness is not a shape. A connection's released-ness and a flock's validity
are temporal state the authority re-decides — Datahike at the transaction,
`release-store!` at the release, where the fact is ALREADY derived from the
flock ("the flock's own validity IS the released? fact",
`src/seon/cluster/store.clj:598`). Asserting it in a contract is the pre-read
the owner law forbids, and it is why the retained record became
unrepresentable. Every other opaque member of the same instance was already
structural (`seon.cluster/socket-server?` `src/seon/cluster.clj:101-105`,
`seon.flow/graph?`, `channel?`, `executor?` `src/seon/flow.clj:26-51`), so this
restores uniformity rather than inventing a rule.

Files touched:

- `resources/seon/schemas/seon.store.edn` — `:seon.store/store` now references
  the named `:seon.store/connection-object` and a new `:seon.store/lock-object`;
  two inline liveness predicates deleted.
- `src/seon/cluster/store.clj` — `file-lock-object?`, the structural companion
  of `connection-object?`, registered as a core predicate.
- `resources/seon/schemas/seon.turn.loop.edn` — `:seon.turn.loop/cluster`'s
  connection is the connection object. One value cannot have two shapes: the
  turn loop and the retained instance hold the SAME handle.
- `test/seon/cluster/store_test.clj` — `open-write-release-reopen-preserves-data`
  asserted "A SECOND RELEASE IS REFUSED", i.e. the contract ACCIDENT, directly
  contradicting `release-store!`'s docstring two lines above it. Per AGENTS.md
  §5 rule 6 the expectation was the stale half: it now asserts the ruled no-op
  and that a released store is still a store value.

The change is widening only — no contract refuses anything it admitted before.

### Verified / not verified

Verified live (default JVM pid 63433, before it stopped serving sessions):
`:seon.store/store`, `:seon.boot/instance`, `:seon.turn.loop/cluster` and
`:seon.store/lock-object` all compile against the complete file population with
the new predicate present. `clj-kondo` clean on the changed Clojure files
(5 pre-existing shadowed-var warnings in boot_test, none new).
NOT verified: no test JVM was run — the gate owns the proof.

## Class 2 — a fixture wrote a config row no mechanism can produce (`d427728d7`)

`selected-config-repairs-locked-state-before-consumers-arm` wrote
`{:seon.config/cluster "config-unlock" :seon.config.flow.compute/queue-depth 1}`
as a map (`boot_test.clj:1149`). `:seon.config/cluster` is a
`:db.unique/identity` (`resources/seon/schemas/seon.config.edn:42`), so
`seon.db`'s writer reads the map against the WHOLE `:seon.config/entity`
schema (`src/seon/db.clj:2776-2816`), which requires
`:seon.config/applied-manifest-digest` (`src/seon/schema/edn.clj:97-104`) — the
digest `seon.config` mints at compile time (`src/seon/config.clj:398-406`). The
fixture was authoring a config row no owner can produce.

Fixed through the ruled path, the same one `d090c9934` applied to clusters and
turns: one attribute on an EXISTING entity is a datom, so the write is now
`[:db/add [:seon.config/cluster NAME] :seon.config.flow.compute/queue-depth 1]`.

## Class 3 — a steer's words drifted, and the steer is not reached at all

`incompatible-sovereign-schema-refusal-steers-the-operator` asserted the
message contains "predates the incompatible schema change". `0b910eb69`
deliberately replaced that wording with the property and both of its values
when the refusal started deriving from Datahike's own per-facet acceptance
rule; it updated `seon.cluster-test` and left this expectation behind. The
assertions now name the current words ("cannot reopen in place", `:db/valueType`)
— `d427728d7`.

That is NOT why the test is red. The observed message is
`"The cluster instance failed above the REPL: :malli.core/invalid-schema"` and
no cause carries the `:seon.ns/requires` offense, so no declaration comparison
is reached. Root cause NOT established here; the exact boundary is: pinning it
needs a live `cluster/start!` over a store seeded by the test-only
`seed-incompatible-sovereign!` (`boot_test.clj:180`), which this thread was not
permitted to run, and the default cluster's io-prepl stopped serving sessions
before an alternative probe could be built. Filed as
[an opaque invalid-schema in place of the sovereign steer](../../../seon/issues/a-sovereign-schema-refusal-is-replaced-by-an-opaque-malli-invalid-schema.md),
with the suspects to falsify first.

## Classes NOT root-caused, with the exact boundary

- `boot-order-completes-in-one-start` (`boot_test.clj:1362`): the rendered root
  session contains `:seon.fn/spec` (index 4621) but neither `(defn largest` nor
  `(run/complete` (both -1). `(run/complete` is provably stale: the walkthrough's
  closing form is `(my.turn/complete …)` (`src/seon/run.clj:74-79`), and the only
  `seon.run/complete` left is in `supervision-tx` (`src/seon/bootstrap.clj:799`),
  which would render as `(seon.run/complete`, not `(run/complete`. `(defn largest`
  IS in the walkthrough and IS stored verbatim (`entry-form-source` is `pr-str`,
  `src/seon/bootstrap.clj:196-204`), so its absence says the episode's forms are
  not in the render — a real question, not a string drift. I did not change the
  expectation, because fixing only the stale half would hide the other half.
  Boundary: needs the rendered session from a real bootstrap episode.
- `partial-clusters-refuse-and-fresh-clusters-are-current` (`:903`, plus the NPE
  at `:906` which is its consequence): no activation refusal is produced.
  Untouched; not investigated.
- `incremental-source-refresh-publishes-without-touching-existing-clusters`
  (`:944`, `:957`): `:seon.source/built?` is `true` where `false` is expected,
  and the existing cluster's basis advanced 536870948 → 536870952. Untouched.
  Same neighbourhood as class 3's suspects (`a3cbcd9a8`, `0b910eb69`).
- `a-generated-prefix-resumes-on-the-same-run-after-jvm-kill` and the
  `development-adoption-targets-one-of-two-cohosted-clusters` worker-exchange
  bound: two TimeoutExceptions. Untouched — a bound firing is its own bug report
  and needs the run it timed out in.

## Tool state to report

The default cluster's io-prepl stopped answering mid-thread: `Connection reset`
on an in-flight query, then `Cluster closed the io-prepl session.` for a fresh
session id, while pid 63433 stayed alive and `runtime_status` reported the
cluster `unknown`. This thread does not restart `default`, so the remaining
live probes were abandoned rather than worked around.

## Re-gate

`seon.cluster.boot-test seon.cluster.store-test` — store-test is added because
class 1 changed one of its expectations. `seon.cluster-test seon.db-test` are
the cheapest neighbours if the orchestrator wants the widening checked against
the contracts that reference `:seon.store/store` and `:seon.turn.loop/cluster`.
