---
type: research
status: active
tags: [research, schema, database, runtime]
---

# Error graph — implementation and live proof, 2026-09-16

Four implementation slices landed on `steward-platform`. The owner approved
Option 1 and granted the two refusal consumers in turn.clj, maintenance
settlement, and the cluster committer. The integration gate is pending;
this note does not claim a green platform gate.

**Guarantee:** identical canonically ordered error attributes identify one
entity independently of the JVM process, and one Datahike transaction
function increments each agent/turn-or-process occurrence from the
mid-transaction database.

## Authorities and archaeology

Read AGENTS.md and docs/seon/issues/README.md end to end;
namespace-data-model-2026-09-16.md §0, §7, §8 and §9 end to end;
and the structural-kill tables in issue-class-mining-2026-08-11.md.
The assignment identifies §9 rather than a mining class note or member list.
The related fault-resolution note was read end to end. Applied
data-oriented-clojure, data-modeling, datahike, repl, clojure-testing,
datastar-web-ui and seon-flow-architecture skills.

Baseline `3c35a62127424075c2c7f585fb88e04e10c652c7`: read-only default
queries found 4 error entities, 4 signature holders, 0 steward holders,
3 process entities and 4,709 function rows. These are dated measurements.
The installed error process and throwable-class were strings; occurrences
was a numeric notice attribute, not an unused declaration. The committed
[baseline probe](error-graph-probe-2026-09-16.clj) verified that changing
process changed the old signature, and passing a transaction-function
vector through the old error/value caller produced a contract refusal.
Git history and the caller source were read before editing.

Dependency ledger:

- `src/seon/id.clj`: `id` hashes pr-str; sorted-map supplies canonical
  attribute order. No second hash implementation.
- `reference-code/datahike/src/datahike/db/transaction.cljc`: `:db.fn/call`
  receives the transaction database, including earlier forms in that transaction.
- `src/seon/program.cljc`: `canonical-row` and `row-identity` prepare
  namespace/function identities. The error writer admits their identity
  datoms without submitting an incomplete program declaration map.
- `src/seon/db.clj` and `resources/seon/schemas/`: the existing schema bridge,
  transaction validation and explicit connection custody remain authoritative.
- `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj`,
  `src/seon/flow.clj` and the agent graph definition: step functions are Vars,
  but no declared proc-keyword → step-function relation was found.
- `seon.test/run` and `seon.test-support/with-database`: real canonical
  population, armed contracts, no test JVM or hand-rostered schema.

## Slices

| Commit | Change |
|---|---|
| `45998fdbf` | Signature identity, canonical attribute hashing, function ref, frame and related refs. |
| `2320dc1a9` | Occurrence identity/schema and error component set. |
| `2066b8c20` | Writer transaction function, recording return contract, granted callers. |
| `3f4f0cdf2` | Derived stewardship, reader conversions, canonical regressions and final live-probe corrections. |

The signature is `seon.id/id` with length 64 over a sorted map of kind,
throwable-class symbol, function symbol and frame tuple; absent values are
omitted. Process, agent, message and turn are not error identity inputs.
The frame line is a Clojure long: the Java StackTraceElement Integer was
rejected by Datahike's strict tuple value type until this probe corrected it.
Diagnostic-operation supplies a qualified function symbol for contract
refusals; stack attribution remains the fallback for ordinary Throwables.

The schema bridge refused changing the existing string throwable-class.
New error entities therefore use `:seon.error/exception-class` as a symbol;
the identity input still uses the ruled `:seon.error/throwable-class` key.
The old string class, process and instrument/fn remain exact occurrence
evidence. `:seon.error/fn` is a ref. Its lookup value uses the existing
string-valued `:seon.fn/sym` identity; this lane did not change that protected
program identity family. Missing function/namespace identities go through
seon.program's canonical identity mechanism and are admitted as datoms.

Occurrence identity is `seon.id/id` over sorted signature, optional agent,
and turn when supplied, otherwise process. The writer upserts it, increments
count, keeps first-at, replaces last-at/message/blob and retracts an obsolete
blob ref when the latest payload is inline. The blob descriptor is itself
referenced and retains the existing blob-digest fact for retention queries.
Dropped-fault counts accumulate on the occurrence; the latest digest remains
exact latest evidence. Two overflow deliveries with counts 3 and 5 retain 8.

`error/recording` returns the prepared error and occurrence refs, flat value,
and transaction data. The granted turn consumers, maintenance receipt and
cluster committer read that data, not the first transaction item. All
occurrence counts and notification decisions execute in `commit-call`.
A leading static error-identity map is still necessary for the protected
`record-attempt!` consumers of failure/truncation recordings (turn.clj around
3832–3835); it carries no occurrence state. There is exactly one db.fn/call,
not a second recurrence writer. This retained composition boundary is explicit.
The two granted turn hunks were disjoint, and turn.clj had only this lane's
hunks when committed with --only. Other turn, program, fn, issue and test-runner
owners were preserved.

Stewardship is queried through error/fn → fn/ns → ns/steward. Nothing stores
error/steward. Notifications still use the existing message/about wake.
Agent repair reads now derive through the function refs, including an empty
read before the first fault. Problems, recurrence, AI/HTML error rendering,
cluster status, fault blob accounting and debug fault rows use occurrences.
Equal occurrence counts are preserved with Datalog :with in aggregate queries.
The final source search found no durable reader querying instrument/fn as
error membership. Remaining uses are exact evidence and diagnostic formatting.
The old wake.clj docstring's stored-steward example is residual prose outside
this lane's edits; the implemented wake attributes and regression exclude it.

## REPL verification

Candidate forms were evaluated in the development JVM, invoked on real
prepared errors/database values and exercised by individual in-process tests
before source adoption; the same regressions were replayed after adoption.
Early fixture acquisition/adoption failures are not counted as passes.
The class test grew from 22 writer assertions to 34, 35, 37 and finally 38
as readers, resolution and flat-error function attribution were included.
The final 38-assertion form passed before the final edit and again after it.
The dropped-count form passed 5 assertions before and after its edit.

Exact execution shape for every table row (fully qualified Var shown below):

```clojure
(let [c (seon.operator/connection "default")]
  (seon.test/run #'<fully-qualified-test> c
    {:seon.test.run/provenance (seon.test.runner/provenance (seon.db/db c))
     :seon.test/remaining-ms 120000}))
```

The small committer replay used the two-argument run arity. Each run finished
before the next started. No bin/test, bin/test-fast or test JVM was launched.

| Slice / exact test Var | Latest pass / fail / error |
|---|---|
| Identity: `seon.error-test/the-signature-ignores-the-message` | 1 / 0 / 0 |
| Identity: `seon.error-test/the-signature-separates-different-errors` | 1 / 0 / 0 |
| Occurrence/writer/readers: `seon.error-test/error-identity-and-occurrences-are-owned-by-the-writer` | 38 / 0 / 0 |
| Writer: `seon.error-test/dropped-fault-counts-accumulate-in-the-occurrence` | 5 / 0 / 0 |
| Writer: `seon.error-test/the-storm-is-bounded-by-the-signature-count` | 3 / 0 / 0 |
| Caller: `seon.cluster-test/a-core-fault-commits-with-the-dial-its-cluster-config-carries` | 4 / 0 / 0 |
| Caller: `seon.schedule-test/returned-and-thrown-handler-errors-use-the-existing-root-wake` | 24 / 0 / 0 |
| Reader: `seon.problems-test/errors-are-grouped-by-signature-not-listed-one-by-one` | 7 / 0 / 0 |
| Reader: `seon.problems-test/every-committed-error-fact-shape-is-projectable` | 9 / 0 / 0 |
| Reader: `seon.problems-test/incomplete-error-entities-are-refused` | 2 / 0 / 0 |
| Reader: `seon.problems-test/a-run-that-closed-with-an-error-says-why` | 2 / 0 / 0 |
| Steward: `seon.cluster.wake-test/a-fault-wakes-the-steward-of-the-failing-functions-namespace` | 6 / 0 / 0 |
| Render: `seon.render.faults-test/pulled-fault-concern-uses-the-entity-pair` | 9 / 0 / 0 |
| Render: `seon.turn-test/fault-unit-lists-agent-faults-newest-first-with-run-links` | 5 / 0 / 0 |
| Flow: `seon.flow-test/core-fault-signatures-bound-durable-and-stderr-output` | 31 / 0 / 0 |

The Flow result was recovered by pulling its recorded test entity after tool
output overflow: run-at 2026-09-16T02:27:43.440Z, 31/0/0. `seon.flow-test/fault-tap-overflow-commits-a-queryable-drop-fact`
also passed 8 assertions after evaluating its candidate reader
forms. The canonical class regression includes two agents, same-turn repeat,
process change, two calls composed from the same pre-read database, fn/ns/steward
refs, flat values without Throwable, total reader counts and Open/Resolved HTML.
No fake database or unarmed contract substitutes were introduced.

The final search also found the refusal test inspecting prepared transaction
maps for notifications. Its two helpers now query committed notifications
and sum occurrence counts.
`seon.turn-loop-test/a-refused-phase-escalates-once-per-signature-and-never-to-itself`
passed 5/0/0 before editing and 5/0/0 after loading the edited test source.
This verifies the granted refusal-terminal-data consumer through the real writer.

The former 60-trial optional-field property repeatedly acquired canonical
fixtures and exceeded the 120-second bound. Its replacement covers all four
agent/turn attribution combinations with the complete optional evidence,
9 assertions. It does not weaken the output contract. Redundant stored-steward
coverage was replaced by the real writer/wake test.

Verification boundaries, not green results:

- `seon.render.faults-test/the-opening-derives-repair-reads-through-function-refs`
  first completed 13 assertions but recorded an instrumentation-drift error
  during concurrent adoption. Latest replay: 2/0/1, `seon.eval/of-agent`
  refuses a pulled `:seon.eval/renderer-fn` map where its entity contract
  expects a database ref integer. See the [evaluation-ref issue](../../../seon/issues/evaluation-reader-refuses-pulled-renderer-ref.md).
- `seon.cluster.turn-test/generated-membership-failure-never-advances-the-run-to-call`:
  1/3/0, released instead of error, no terminal turn fact. The same 1/3/0
  predates this reader conversion in the
  [turn census](../../context-generation/research/turn-test-reds-2026-09-16.md).
  The only edits here convert fault queries to occurrence joins.
- `seon.turn-test/batch-settlement-preserves-declaration-order`: 1/2/0.
  A declaration settlement supplies `[:seon.db/invalid-read true]` as a
  lookup ref; the writer rejects its non-unique attribute. This is not a
  successful refusal-consumer probe. See the [declaration-settlement issue](../../../seon/issues/declaration-settlement-consumes-invalid-read-as-ref.md).
- Several early probes met schema publication, retained fixture or global
  instrumentation drift errors. No global fixture was reset and no wrappers
  were disabled to turn them green.

## Live adoption proof

The [replayable live probe](error-graph-live-2026-09-16.clj) invokes the armed
seon.id/valid? contract with an invalid length, then delivers that same real
Throwable twice through the actual cluster committer. On default pid 7595:

```clojure
{:outcomes [:seon.flow/committed :seon.flow/committed]
 :error {:db/id 61962
         :seon.error/signature "9a30df13cef8ae6b9c2306ebf4f1d5d40ecda8d9f0b965a4e88a4dcccfd3801d"
         :seon.error/kind :seon.instrument/contract-violated
         :seon.error/frame [seon.instrument$throwing_report$fn__10950133 invoke "instrument.clj" 413]
         :seon.error/exception-class clojure.lang.ExceptionInfo
         :seon.error/fn {:db/id 6052 :seon.fn/sym "seon.id/valid?"
                         :seon.fn/ns {:seon.ns/name seon.id}}
         :seon.error/occurrences
         [{:seon.error.occurrence/id "f82aaeb5e982"
           :seon.error.occurrence/count 2
           :seon.error.occurrence/first-at #inst "2026-09-16T02:32:10.409Z"
           :seon.error.occurrence/last-at #inst "2026-09-16T02:32:35.232Z"
           :seon.error.occurrence/process
           {:seon.db.process/id "7595-1789522562086"}}]}}
```

The complete small returned value was read. The adopted source commit was
`6aa9fe1b-203b-5ca1-bea2-9047ea996105`; current-src had advanced concurrently
to `6aa9ff7b-8a66-5384-b64d-2af20f09b2b0`. A subsequent read verified both
prepare's diagnostic-operation attribution and commit-call's accumulated
count form in default's program rows, plus installed db.unique/identity on
signature. The successful adoption log explicitly completed loaded definitions,
SCI acquisition, JVM instrumentation and cluster convergence. This proves
in-place adoption of this slice, not convergence to every concurrent edit.

Initially signature identity adoption refused: RESET NEEDED applied then.
The owner subsequently replaced default; this lane never stopped, reforked
or restarted it. The latest native schema accepts new writes. RESET NEEDED remains for
legacy data: a read-only probe found error entity 43542, signature
`9aee2b65e3bdb8a0b90f0edc20e1f51d687bb1a559e7fd872c7755f878f83866`,
written at 01:40:10Z before the new writer, with an old message/at but no
occurrences. It produces aggregate count 0 against the existing positive
count contract in problems/status. No legacy-data migration or default reset
was performed. The concurrently edited
[runtime-status issue](../../../seon/issues/runtime-status-refuses-error-occurrence-count.md)
was read end to end and preserved; this probe supplies its missing value
evidence here for its owner. A bounded isolated snapshot probe met a
foreign schema/source mismatch and an older fixture tools.build classpath
failure; it was not used as green evidence. Its cluster was downed and both
lane worktrees/roots removed after their holders exited.

## Member verdicts and exact boundaries

- Process-dependent error identity: resolved by `45998fdbf` and the class/live
  probes. The identity guarantees equality for equal attribute data; changing
  the exact frame data changes identity by design.
- Occurrence loss, duplicate error rows and pre-read increments: resolved by
  `2066b8c20` / `3f4f0cdf2`; two composed calls increment from the writer value.
- Stored but empty stewardship and numeric-occurrences reader mismatch:
  resolved by `3f4f0cdf2`; derived steward, wake and aggregate tests pass.
- [Fault resolution has no declared fact](../../../seon/issues/fault-resolution-has-no-declared-fact.md):
  resolved by the declared resolved-tx ref and real transaction/render probe.
  Issue workflow resolution itself remains the issue owner's responsibility.
- [Missing proc→step-function relation](../../../seon/issues/flow-error-proc-has-no-declared-step-function-ref.md):
  open residual. proc-fn is declared but deliberately absent until that fact
  exists; nothing guesses a function from a proc keyword.
- Regression and issue refs are declared; their workflow population belongs
  to their owning lanes. No protected issue/program/test-runner owner changed.

The exact path-limited gate request is at
`tmp/orchestrator/gate-requests/error-graph.txt`. The orchestrator owns the
namespace and platform gates. Source whitespace checks passed. The Markdown hook reported 12 errors
in the foreign agents-md-audit-2026-09-15.md dependency gitlink citations;
its feedback was elided, so this lane does not classify all 12. That document
was preserved.
No class note was supplied to close; §9's implementation is landed with the
above explicit residual and unverified integration boundaries. No lane-owned
background shell, running scratch cluster or scratch worktree remains.
