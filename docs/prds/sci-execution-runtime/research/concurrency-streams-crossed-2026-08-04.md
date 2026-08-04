---
type: research
status: active
tags: [sci, concurrency, runtime, database, research]
---

# Concurrency streams crossed — 2026-08-04

## Verdict

Seven deliberate collision families on one shared scratch cluster found five
blockers and two friction defects. Datahike's unique race was clean, identical
schema declarations were idempotent, JVM Var roots did not tear, all flooded
messages became facts, and one run's eval error did not enter another run's
facts or receipts.

The unsafe results are at the context-to-database boundary: shared-context
session snapshots cross-attributed definitions, successful definition and
schema receipts could describe changes absent from the database, namespace
removal had no coherent cold-rebuild meaning, and an ordinary eval error could
not settle its own receipt. Same-transaction message order also becomes lexical
at index ten.

The evidence supports the ruled per-run fork-context wave. The live system
still uses one shared mutable context, so immediate cross-agent visibility and
last-live-writer behavior are evidence about the current model, not objections
to the ruling. Cross-run durable attribution and success-shaped lost writes are
present defects, not merely future-model differences.

## Authorities read completely

Before probing, this lane read end to end:

- [Namespace semantics report](session-curation-namespace-semantics-2026-08-04.md)
- [Independent namespace semantics report](session-curation-namespace-semantics-opus-2026-08-04.md)
- [Session curation PRD](../plan/session-curation-prd-2026-08-04.md), including S6
- [Active execution-runtime plan](../plan/README.md), including the complete
  “Rulings 2026-08-04” section
- [Agent runtime architecture](../../../seon/architecture/agent-runtime.md)

The previous namespace reports did not probe `ns-unmap` or `remove-ns`. The
plan rules per-run `sci/fork` contexts but marks the wave unbuilt. These probes
therefore used the current shared cluster context intentionally.

## Dependency ledger

| Mechanism | Selected source | Boundary read |
|---|---|---|
| SCI | `2db3358cba913b6fbbe49c7b5b34d7ac72715924` (`v0.14.56-18-g2db3358`) | `reference-code/sci/src/sci/core.cljc`; `reference-code/sci/src/sci/impl/namespaces.cljc`; namespace tests. `sci/fork` has copy-on-write generations; `ns-unmap` and `remove-ns` mutate the selected context's namespace table. |
| Datahike | `574c5f0f0db9411d1982769f14512cb24ef719da` (`0.8.1732-98-g574c5f`) | `reference-code/datahike/src/datahike/writer.cljc` and transaction uniqueness paths. One connection's writer serializes submitted transactions. |
| Seon evaluation | scratch `current-src` commit `6a726422-0d92-5683-905b-bf1ea1c7f117` | `src/seon/sci/eval.clj`, especially reader events, `changed-session-defs`, schema deltas, acquisition, and session-image installation. |
| Seon terminal commit | same scratch publication | `src/seon/cluster/loop.clj`, `src/seon/cluster/run.clj`, `src/seon/program.cljc`, `src/seon/cluster/message.clj`, and `src/seon/cluster/work.clj`. |
| Tests | current checkout | `test/seon/concurrency_independence_test.clj`, `test/seon/sci/eval_test.clj`, and `test/seon/test_support.clj`. |

## Probe environment

- Operator root: `tmp/concurrency-streams-root`
- Cluster: `concurrency-streams-0804` (never `default`)
- Initial process: PID `15056`, prepl `56903`
- Rebuild process: PID `23418`, prepl `59359`
- Inputs: system-authored model-free runs created through
  `seon.cluster.run/system-run-tx`, plus door evaluations for explicitly
  live-only controls
- Facts: queried through `seon.db/q`, `pull`, `pull-many`, and database history;
  no log line was treated as state

The scratch helper was kept at `tmp/concurrency_streams_probe.clj`. It is
gitignored and contains only orchestration; all durable results are below.

## Ranked findings

1. **Blocker — eval failures cannot settle.** The loop passes
   `:seon.cluster.eval/triage-edn` to a receipt contract that disallows it. See
   [eval errors cannot settle triage receipts](../../../seon/issues/eval-errors-cannot-settle-triage-receipts.md).
2. **Blocker — live and durable function winners diverge.** A later successful
   definition receipt can leave no program-row change. See
   [concurrent definition receipts can diverge](../../../seon/issues/concurrent-definition-receipts-can-diverge-from-durable-program-row.md).
3. **Blocker — session-image attribution crosses runs.** One run persisted
   another run's definition in its terminal transaction. See
   [shared-context session delta crosses attribution](../../../seon/issues/shared-context-session-delta-crosses-run-attribution.md).
4. **Blocker — divergent schema loser falsely succeeds.** Two different forms
   returned success; only one ever became a fact. See
   [divergent schema declarations falsely both succeed](../../../seon/issues/concurrent-divergent-schema-declarations-falsely-both-succeed.md).
5. **Blocker — namespace removal has inconsistent durable meaning.** Neither
   `ns-unmap` nor `remove-ns` rebuilds contracted-only. See
   [namespace removal does not rebuild contracted only](../../../seon/issues/namespace-removal-does-not-rebuild-contracted-only.md).
6. **Friction — message order is lexical after index nine.** See
   [same-transaction message order is lexical](../../../seon/issues/same-transaction-message-order-is-lexical.md).
7. **Friction — dynamically hidden namespace movement cannot support the next
   definition's lookup ref.** See
   [dynamic in-ns cannot persist definition namespace](../../../seon/issues/dynamic-in-ns-cannot-persist-definition-namespace.md).

## Exact fact queries

These query shapes were reused across scenarios. Transaction positions are
queried from datoms, not inferred from wall time.

```clojure
;; Terminal receipts and their transaction.
(seon.db/q
 '[:find ?run-id ?ordinal ?tx
   :where
   [?run :seon.cluster.run/id ?run-id]
   [?receipt :seon.cluster.eval/run ?run]
   [?receipt :seon.cluster.eval/ordinal ?ordinal]
   [?receipt :seon.cluster.eval/result-edn _ ?tx]]
 database)
```

```clojure
;; Program and session definitions, including the asserting transaction.
(seon.db/q
 '[:find ?sym ?tx
   :in $ ?prefix
   :where
   [?function :seon.fn/sym ?sym ?tx]
   [(clojure.string/starts-with? ?sym ?prefix)]]
 database prefix)

(seon.db/q
 '[:find ?id ?tx
   :in $ ?prefix
   :where
   [?definition :seon.code.def/id ?id ?tx]
   [(clojure.string/starts-with? ?id ?prefix)]]
 database prefix)
```

```clojure
;; Definition attribution by one shared terminal transaction.
(seon.db/q
 '[:find ?sym ?run-id ?agent-id ?ordinal ?tx
   :where
   [?function :seon.fn/sym ?sym ?tx]
   [?receipt :seon.cluster.eval/result-edn _ ?tx]
   [?receipt :seon.cluster.eval/ordinal ?ordinal]
   [?receipt :seon.cluster.eval/run ?run]
   [?run :seon.cluster.run/id ?run-id]
   [?run :seon.cluster.run/agent ?agent]
   [?agent :seon.cluster.agent/id ?agent-id]]
 database)
```

```clojure
;; Message facts and exact batch transaction.
(seon.db/q
 '[:find ?id ?content ?at ?tx ?from-id ?to-id
   :in $ ?agent-id
   :where
   [?agent :seon.cluster.agent/id ?agent-id]
   [?message :seon.cluster.message/to ?agent]
   [?message :seon.cluster.message/id ?id ?tx]
   [?message :seon.cluster.message/content ?content]
   [?message :seon.cluster.message/at ?at]
   [?message :seon.cluster.message/from ?from]
   [?from :seon.cluster.agent/id ?from-id]
   [?agent :seon.cluster.agent/id ?to-id]]
 database agent-id)
```

## Scenario 1 — different Vars, same namespace

### Expected by current and future design

Both definitions survive. Each program row and receipt is attributable to its
own run and agent. Current shared-context runs can see the other name
immediately; future fork-context runs should see it only after adoption and
cluster acquisition.

### Observed

Top-level `(in-ns 'streams.shared)` followed by synchronized contracted
definitions produced both rows:

| Symbol | Program-row transaction | Attributed run | Agent |
|---|---:|---|---|
| `streams.shared/alpha` | `536871061` | `streams-contracted-a` | `streams-d2-a` |
| `streams.shared/beta` | `536871062` | `streams-contracted-b` | `streams-d2-b` |

Both completion receipts observed the other Var, and both functions returned
their declared values. There was no `:seon.fn/author` fact; attribution was
possible only by correlating the program-row datom with the terminal receipt's
transaction.

The divergence was in `:seon.code.def`: B's transaction `536871062` also
asserted `streams.shared/alpha`. `changed-session-defs` observed A's concurrent
context mutation as B's change. This is the evidence behind
[shared-context session delta crosses attribution](../../../seon/issues/shared-context-session-delta-crosses-run-attribution.md).

The first control hid `in-ns` inside `do`. Both live evaluations moved to
`streams.shared`, but the next definition settled as `:seon.db/rejected`:
`Nothing found for entity id [:seon.ns/name streams.shared]`. Top-level
`in-ns` did not fail. That adjacent discovery is filed separately.

## Scenario 2 — repeated same-Var redefinition

### Expected by current design

Whole Var roots, last live writer wins, and the durable terminal winner agrees
with what both runs see. Under the future fork model, divergent definitions
must produce one adopted winner and an explicit loser/refusal.

### Observed

Two synchronized system runs redefined
`streams.same/collision-value`. A's definition receipt committed at
`536871077`; B's later successful receipt committed at `536871081`. History
contained only A's `:seon.fn/source` at `536871077`. Before rebuild, the live
Var returned B; after rebuild it returned `{:writer :a, :iteration 1}` from the
durable A row.

A separate direct collision made each writer call `alter-var-root` 1,000
times with a map containing writer, iteration, and a 64-element uniform
payload. Both readers observed a complete B map at iteration 999, and no mixed
payload was observed. The dependency-level root mutation is atomic; terminal
admission is not honest about the durable winner.

## Scenario 3 — foreign `ns-unmap` and `remove-ns`

### Expected by ruled fork-context design

A's removal remains inside A's candidate context while B's run continues in
its own fork. After adoption/rebuild, B's contracted program function returns;
the removed uncontracted session name does not. A foreign run cannot delete
B's contracted row.

### Observed: live-only `ns-unmap`

B's facts before removal were:

```clojure
{:functions [["streams.victim2/contracted" 536871096]]
 :session-defs [["streams.victim2/ephemeral" 536871098]]}
```

A door eval removed both live names. B's following form became a durable
`:seon.cluster.loop/lint-rejected` receipt, but B still reached its explicit
completion. Because the door owns no terminal transaction, both database rows
remained. Restart restored **both** `contracted` and `ephemeral`.

### Observed: run `ns-unmap`

An actual remover run produced two nil receipts and closed. Querying the same
prefix then returned no function rows and no session rows. Cold acquisition
therefore has no contracted row to resurrect.

### Observed: mid-run `remove-ns`

B defined both names, met A at a two-run barrier, then waited until A executed
`(remove-ns 'streams.victim3)`. A's three receipts settled and its run closed.
B's next form failed to resolve `contracted`; the triage-contract defect then
escaped B's loop and left receipt ordinal 4 running.

After settlement, the database held:

```clojure
{:functions [["streams.victim3/contracted" 536871130]]
 :session-defs [["streams.victim3/ephemeral" 536871132]
                ["streams.victim3/contracted" 536871135]]}
```

Pulling the session rows showed `ephemeral` retained its serialized value and
the contracted shadow was `:seon.code.def/unrestorable`. Acquisition skips a
session row when the program function exists, so the contracted function can
return, but the retained ephemeral value returns too. `ns-unmap` and
`remove-ns` therefore disagree durably, and neither gives contracted-only
recovery.

Current-model evidence rather than a separate issue: A can mutate/remove B's
live namespace immediately because both use one shared context. The fork wave
is explicitly intended to end that visibility.

## Scenario 4 — unique database conflict

### Expected

The connection writer serializes both requests: one winner, one flat rejection,
no missing or duplicate winner.

### Observed

Two concurrent `seon.cluster.agent/creation-tx` calls assigned different agent
IDs to the same unique namespace `streams.unique.race`. Results were:

```clojure
[{:seon.error/kind :seon.db/rejected
  :seon.error/message
  "Transaction rejected: :seon.cluster.agent/namespace value 14612 is already held by \"an existing entity\"."
  :seon.error/data
  {:seon.db/conflict-attribute :seon.cluster.agent/namespace
   :seon.db/conflict-value 14612}}
 :committed]
```

The exact winner query returned only
`["streams-unique-b" streams.unique.race]`. This scenario passed. The result
face is structurally clean but exposes a process-local entity ID (`14612`),
which is unnecessary diagnostic noise.

## Scenario 5 — same new schema key

### Expected

Identical definitions are idempotent. Divergent definitions admit one winner
and give the loser a clean refusal; two different current facts are impossible.

### Observed: identical

Both runs registered `:streams.collision/value` as
`[:string {:min 1}]`. Both ordinal-1 receipts settled. History contained one
form assertion at transaction `536871165`; the later receipt caused no second
history fact. This is correct idempotence.

### Observed: divergent

A registered `:streams.collision/divergent` as `:string` and B as `:int`.
Both ordinal-1 receipts returned the schema keyword. Their terminal
transactions were `536871181` and `536871182`, yet history was only:

```clojure
[[":string" 536871181 true]]
```

The current row was `:string`; B received no rejection. The database never had
two divergent current facts, but the losing receipt falsely claimed success.

## Scenario 6 — message floods mid-run

### Expected

Every returned message value becomes one fact, source-vector order is stable,
sender/recipient attribution is exact, messages received during a run wait as
unanswered triggers, and transcript rendering loses or conflates nothing.

### Observed

Runs A and B synchronized, then each returned a vector of twelve
`my.message/send` values. Queries found twelve facts per recipient, one
transaction per batch, one timestamp per batch, and correct from/to IDs. No
message fact was lost.

One message per recipient was already connected as the trigger of an
automatically opened follow-up run. The remaining eleven appeared in
`unanswered-triggers`; this is derivable custody, not loss.

Both trigger and transcript order were:

```text
00,01,10,11,02,03,04,05,06,07,08,09
```

The transcript correctly said “Agent streams-flood-a said to
streams-flood-b” and the inverse for all entries, but repeated the same
scramble. `unanswered-triggers` sorts equal timestamps by string identity;
message index is encoded only inside that string.

The returned twelve-message vector receipt itself rendered as a very large
table and was truncated. This is honest bounding, but the collision-path face
is noisy enough that a compact message-batch producer would be easier to read.

## Scenario 7 — one eval error during collision

### Expected

A's ordinary SCI error becomes A's flat terminal receipt/error fact. B's run
and transcript contain none of A's error and complete normally.

### Observed

A threw `a-only collision failure` immediately after a two-run barrier. B
defined `b-marker`, evaluated it as `:b-ok`, and completed. B's four receipts
settled, its run closed, and none carried A's error.

A's ordinal-1 receipt remained open. The host exception was:

```text
seon.cluster.run/receipt-settle-tx violated its contract (invalid-input):
[:seon.cluster.eval/triage-edn ... disallowed key]
```

Thus cross-run containment held, but the stronger “nothing throws into the run
loop” contract failed within A's run.

## Ugly output observed

- Returning a Datahike transaction report through MCP produced a roughly
  1.9 MB blob because the report includes the complete database value. Collision
  probes had to project `:committed` before returning.
- The expected Datahike invalid-read probe logged a dependency error line
  before returning its flat value; this matches the existing
  `datahike-expected-rejections-log-full-writer-exceptions.md` issue family.
- A twelve-message receipt renders as a truncated table rather than a compact
  delivery summary.
- The unique-conflict value exposes a local entity ID instead of only the
  globally identified namespace value.
- `runtime_status` on the scratch cluster returned a
  `seon.problems/problems` output-contract failure unrelated to these
  collision scenarios.

## Verification boundary

The live scenarios and fact queries completed. The repository-wide issue
authority gate could not be counted as proof because another in-flight lane
had five already-created notes missing from the index:

- `time-limit-face-exposes-interpreter-interrupt-marker.md`
- `contract-violation-serializes-print-tree-inside-error-data.md`
- `nested-error-data-hides-the-throw-site-message.md`
- `bootstrap-redefinition-fences-agent-runs.md`
- `mcp-door-top-level-string-bypasses-value-window.md`

This lane did not edit, resume, or message that lane. Its own issue rows are in
the index, but `bin/issues-index --check` remains a foreign verification
boundary until those five rows are reconciled by their owner.

After the affected definition/schema probes, foreign commit `8763b4b17`
changed `seon.cluster.run/declared-content` and added a replacement regression.
That commit may repair part of scenarios 2 and 5, but it landed after this
scratch JVM loaded the probed source and was not re-verified because the
foreign gate had already required this lane to stop. The two issues remain
open until their exact live collisions are rerun on a cluster forked after that
commit; this report does not claim the post-commit tree still produces the
same lost replacement.
