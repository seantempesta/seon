---
type: research
status: active
tags: [research, test, runtime, class/p3]
---

# Turn test reds — batch 19 continuation

Started 2026-09-16 02:36 UTC, bounded to 04:06 UTC, on
`steward-platform` at `5c9135c077f1d8124f8a4da252866743aa64cd32`.
The batch-19 report names 19 tests and 60 FAIL/ERROR blocks. This is a
continuation of [the earlier class work](turn-test-reds-2026-09-16.md),
not a claim that the namespace is green.

Read the batch-19 report end to end, AGENTS.md, issues README, the active
roadmap, and the previous landing record. The earlier record lists the
class-mining authority and member notes read end to end. Applied
data-oriented-clojure, repl, clojure-testing, datahike, seon-context-config,
and llm-providers skills.

Default PID 7595 remained alive. Its MCP status again refused an occurrence
count; direct read-only JVM evaluation and explicit default custody worked.
The [existing tool issue](../../../seon/issues/runtime-status-refuses-error-occurrence-count.md)
records the complete refusal boundary. Default was never restarted or reforked.

Fresh-base construction temporarily replaces fixture delays. To avoid replacing
those roots underneath other lanes in default, the serial MCP probes use an
isolated HEAD worktree and development JVM, cluster `turn-test-reds19`.
No test-runner JVM, `bin/test`, or `bin/test-fast` is used. Each test uses
the canonical database fixture, a fresh branch and SCI context, and armed
contracts. The final cold/platform gate belongs to the orchestrator.

## Class table

The per-member verdicts below distinguish a refused fixture write from a
failure after successful setup. The namespace remains red.

| Cause | Tests | Disposition | Regression |
|---|---|---|---|
| Error identity mistaken for occurrence evidence | Partial-stream truncation, reasoning-only diagnostic, backup attempt, cold SCI acquisition | Resolved by `2209387e2`; 36/0/0 before and after adoption | Existing exact payload, attempt-link and acquisition assertions |
| Compiled config upsert cannot express explicit absence | Shared `with-cluster` constructor | Resolved by `c01df3773` through `config/apply!`; 9/0/0 before and after adoption | Strengthened `one-successful-call-leaves-exactly-one-attempt-fact` asserts absence of the seeded backup |
| Partial config entity maps refused before test work | `refused-terminal-program-transactions-settle-and-do-not-refire`, `generated-model-attempt-traces-preserve-presence-and-episode-laws` | **Blocked by write-validation-class**; downstream assertions skipped after capturing the complete refused seed | Existing tests retained; no config-map workaround applied to these members |
| Retired generated settlement fixture | `generated-fixed-point-closes-the-run`, `generated-membership-failure-never-advances-the-run-to-call` | Setup reaches `receipt-settle-call`, which returns `no-terminal-fact`; not attributed to raw write validation | Retained until current system-turn coverage verifies green |
| Retired phase/private-state/recovery observations | Generated phase property, private-definition refusal, crash-intent test | Seeds commit; unresolved fixture/observation work | Original assertions retained |
| Prompt fixture uses a now-legal triggerless turn as its failure | Prompt-refusal test | Resolved by `7c097f8f2`: inject the actual prompt acquisition refusal on a real opened turn; 4/0/0 before and after adoption | Same no-provider, no-attempt and durable-error assertions |
| Fake evaluation and retired result observations | Opening-database prompt, streaming pair, lost-call diagnostic, delimiter repair, schema refinement/unregister | Seeds commit; distinct current-observation candidates and boundaries recorded below | No failing candidate is retained |

## Seed verification after the owner's batch-20 finding

Read the raw-write issue and the error-graph landing note, including Batch 20
triage, end to end. The isolated committed snapshot remains `9c03c1ec1` plus
owned changes, excluding foreign uncommitted edits. `src/seon/db.clj` was
not edited. The snapshot includes `26ec13420`; these results do not claim
verification of the other lane's subsequent writer repair.

Fifteen fresh canonical branches and SCI contexts each committed all three
common setup writes: **45 committed writes, zero refusals**; the
[common-seed measurements](turn-test-reds-batch19-common-seeds-2026-09-16.edn)
retain every case and the later direct constructor value. Every remaining
batch-19 member was then replayed individually through `seon.test/run` with
a scoped observer that records a refused transaction's complete value and
stops that execution before later assertions. Runs 48489–48511 are serial;
the result files include each exact run ref. Two tests have refused partial
config seeds at `:seon.config/applied-manifest-digest`; the property records
eight refusals during shrinking, representing four distinct input maps.
The common fixture succeeds; its success does not clear those extra writes.

The generated settlement tests instead return `:seon.turn/no-terminal-fact`.
The old prompt-refusal test reaches the provider and then gets
`:seon.turn/not-call-situation`; its fixture no longer represents a prompt
refusal. All other replayed members have no refused seed transaction in this
snapshot. These distinctions prevent the platform finding from swallowing
independently verified fixture drift or the pulled-renderer contract boundary.

## Dependency ledger and boundary

Error identities and occurrence recording are owned by `seon.error/recording`
and its writer function `commit-call` (`src/seon/error.clj:1365`, `:1288`).
`latest-fact` is the existing diagnostic projection (`:1437`). The turn
recorder composes their transaction data and must return committed evidence
from the transaction report (`src/seon/turn.clj:3747`). Datahike's transaction
report supplies `:db-after`; no second evidence registry is needed.

All explicitly protected owners remain untouched. Shared uncommitted program,
schema, test-runner and issue edits are preserved. The snapshot excludes them;
its result is an isolated verification boundary, not proof of default adoption.

## Slice 1 — occurrence evidence

**Guarantee:** the attempt recorder returns the error owner's prepared
complete diagnostic only after committing that same recording; transaction
identity rows are never interpreted as diagnostics.

The final candidate uses `error/recording` directly and retains its descriptor,
including its transaction data and prepared fact. The failed intermediate
candidate read back raw refs; the armed notice contract correctly refused a
pulled map where the diagnostic requires a ref. That candidate was discarded.
The final change adds no projection owner or fallback.

The shared `durable-fact` test observer now acquires occurrences through the
existing `error/latest-fact` projection. Cold acquisition makes the same
observation directly. Partial truncation and reasoning-only failures retain
their exact payload assertions. Backup still asserts exactly two provider calls,
unchanged prompt, the committed diagnostic's projection, and linked attempts.

Exact in-process test set (each passed before the edit and after adoption):

- `seon.cluster.turn-test/a-partial-stream-truncation-is-a-durable-nonfailure-attempt-fact`: 6/0/0.
- `seon.cluster.turn-test/reasoning-only-time-limit-persists-its-flat-diagnostic`: 9/0/0.
- `seon.cluster.turn-test/an-unpaid-failure-with-a-backup-makes-exactly-two-calls`: 15/0/0.
- `seon.sci.eval-test/one-unloadable-row-cannot-prevent-cold-acquisition`: 6/0/0.

Counts are pass/fail/error assertions. Final candidate run refs: 44630–44633.
Adopted run refs: 48355–48358. Source adoption:
`6aaa0591-f886-5b55-ac01-4c9d24d84214`; snapshot advanced to committed
`9c03c1ec1` before overlay/adoption, preserving the newly landed call-edge work.
The full baseline is 19 tests, **64 passes / 49 failures / 13 errors**.
No test JVM was launched.

[Probe](turn-test-reds-batch19-probe-2026-09-16.clj),
[baseline](turn-test-reds-batch19-baseline-2026-09-16.edn),
[candidate](turn-test-reds-batch19-occurrence-candidate2-2026-09-16.edn),
[adopted results](turn-test-reds-batch19-occurrence-adopted-2026-09-16.edn),
[complete recorder values](turn-test-reds-batch19-occurrence-values-2026-09-16.edn).

Default's hook queued publication; its observed source at 02:59 UTC was still
`6aa9fe1b-203b-5ca1-bea2-9047ea996105`. The passing adoption claim is for the
isolated development JVM, not default. Existing global markdown lint reports
12 unrelated gitlink-citation errors in the agents-md audit note.

## Slice 2 — exact fixture configuration

**Guarantee:** `with-cluster` reconciles its manifest through the configuration
owner, so an explicitly absent setting cannot survive from seeded defaults;
a refused fixture seed or reconciliation stops setup before the test body.

The existing `one-successful-call-leaves-exactly-one-attempt-fact` regression
now independently checks that the backup model is absent. Before the change,
run 48517 was **8/1/0** and read the shipped backup model. The evaluated
constructor candidate passed **9/0/0**, run 48522. A direct constructor call
returned the actual agent identity and `{:seon.config.ai/model "probe"}` with
no backup key; all four writes committed. After editing and isolated source
adoption `6aaa0dae-4dcb-5985-a392-3b6843779939`, the same fresh-base in-process
test passed **9/0/0**, run 48557. Complete values are in
[the config evidence](turn-test-reds-batch19-config-2026-09-16.edn).

The partial config seed maps in the two blocked members were left unchanged.
This repair is about expressing absence through the existing reconciler,
not accommodating the raw-write validation defect.

## Slice 3 — current prompt-refusal fixture

The fixture now opens through the ordinary turn transition and injects a typed
refusal at `prompt/prompt`. It no longer fabricates a triggerless turn and
assumes that legal state will throw. Real SCI acquisition/evaluation remains
in the fixture. All four existing behavioral obligations remain: an error
outcome, no provider request, no attempt row, and the exact durable refusal kind.

`seon.cluster.turn-test/a-prompt-refusal-is-a-recorded-error-value-never-a-throw`
passed **4/0/0** before editing (48558), then **4/0/0** after isolated
adoption `6aaa0f00-65d7-5536-88e3-d5e483baafc6` (48588). The
[complete values](turn-test-reds-batch19-prompt-2026-09-16.edn) record both.
The seed observer had verified this member's setup writes succeed; its former
refusal was the later `plan-call` transition, not write-map validation.

## Schema-deletion boundary and three priced options

The direct writer regression composes declaration and deletion in one real
transaction. Baseline run 48440, evaluated candidate 48462, observed candidate
48480 and cache-comparison run 48567 each return **4/3/0**. No production edit
was retained. The comparison proves that a mid-transaction database still
claims committed cache identity: its ordinary projection query omits the new
schema, its uncached query returns the exact declared form, and its direct
pull returns the stored form. The resulting schema diff is empty. Dependency
revision: `cdcb5792db8bd599487f099437265d18a31164a5`.

This is a separate
[transaction-cache defect](../../../seon/issues/transaction-functions-retain-committed-query-cache-identity.md),
not a refused seed. It blocks the otherwise small `row-tx` projection repair.
The dependency is outside the granted owners, so this class stops before
production changes. The independent dependency-deletion branch's Malli failure
remains an additional observable; the cache comparison does not prove its cause.

Estimates below include a canonical regression and replays, not a cold gate:

1. **Constrain cache eligibility at transaction entry (recommended), 1–2 hours
   across the dependency owner and this caller.** Clear committed cache identity
   before executing transaction functions, using the existing dependency
   mechanism; then derive deletion's projection at the writer. Guarantee:
   speculative values cannot reuse committed query results. We give up query
   result caching inside speculative transactions and immediate closure in this
   bounded lane; committed-read caching remains available.
2. **Give each intermediate transaction value its own cache lifecycle,
   3–5 hours.** Advance identity after every transaction operation at the
   dependency owner. Guarantee: cached results identify the exact intermediate
   value. We give up the simpler committed-only cache contract and take on
   intermediate-entry reclamation and lifecycle proofs.
3. **Remove query-result caching, 1–2 hours plus performance measurement.**
   Keep the dependency's query engine and delete the result-cache path in place.
   Guarantee: no query can receive stale cached results. We give up acceleration
   for repeated committed reads; latency gates may require further work.

Default publication of the config fixture was independently refused at the
foreign `:seon.issue/agent` scratch-schema boundary (hook requests
`6121a3dc-6237-4c3d-a162-5b7235d24e76` and
`7649a713-b8d3-4a77-bb54-c25e39c5f27a`). No default restart/refork was attempted.

## Evidence, residual members, and gate boundary

The [seed-check values](turn-test-reds-batch19-seed-checks-2026-09-16.edn)
contain all 15 replay results and every distinct complete refused transaction.
These diagnostic replays stop on refusal: their **28/34/12** tally is not a
namespace gate or comparable to the ordinary baseline. The
[seed probe](turn-test-reds-batch19-seed-probe-2026-09-16.clj) retains its exact
historical MCP forms as source data. Load the base helper and scoped test
classloader from the original batch-19 probe before replaying them in an
owned isolated root. The same convention applies to the
[schema-cache probe](turn-test-reds-batch19-schema-cache-probe-2026-09-16.clj)
and [complete cache values](turn-test-reds-batch19-schema-cache-2026-09-16.edn).

The [other boundary values](turn-test-reds-batch19-boundaries-2026-09-16.edn)
retain the exact `seon.eval/of-agent` output refusal and the 120000 ms property
timeout. The [opening probe](turn-test-reds-batch19-opening-probe-2026-09-16.clj)
captures the durable occurrence after the real turn. The separate
[timeout/cleanup issue](../../../seon/issues/in-process-test-timeout-precedes-fixture-release.md)
records that cancellation returned before the fixture connection released.
That earlier owned JVM was shut down before restarting this isolated root;
default was untouched.

Unrepaired batch-19 members, all in `seon.cluster.turn-test`:

| Exact test | Latest verified disposition |
|---|---|
| `refused-terminal-program-transactions-settle-and-do-not-refire` | Blocked by write-validation-class; partial config seed refusal captured; skip downstream assertions |
| `generated-model-attempt-traces-preserve-presence-and-episode-laws` | Blocked by write-validation-class; scenario config seed refused; do not retain the unverified oracle/config candidate |
| `delimiter-repair-is-span-local-and-precedes-intent` | Retired result reads and system/agent query mixing; previous semantic candidate exposed the unchanged 300 ms performance bound; no candidate retained |
| `a-run-prompts-from-its-opening-database-value` | Fake evaluator replaces actual message reads with 1; real-evaluation candidate meets the protected renderer-ref boundary |
| `runtime-schema-key-changes-pass-the-one-usage-guarded-decision` | Retired result observation; real turn meets renderer-ref boundary; unresolved owner ruling remains separate |
| `turn-intent-is-the-complete-crash-falsifier` | Seed succeeds; current opening fails before the intended cut in this snapshot; older recovery expectations also need current wake semantics |
| `generated-fixed-point-closes-the-run` | Retired settlement seed returns `no-terminal-fact`; retained pending verified replacement coverage |
| `generated-membership-failure-never-advances-the-run-to-call` | Same retired settlement refusal; retained pending verified replacement coverage |
| `generated-phase-failures-converge-through-one-terminal-exit` | Seeds succeed; legacy phase property remains red; covering current-phase test was not green in this isolated snapshot |
| `streaming-writes-zero-datoms-test` | Seeds succeed; old fake result does not complete the intended turn; partial-observation ordering and exact reply candidate not retained without green proof |
| `concurrent-streams-share-one-conn-test` | Seeds succeed; fake completion causes extra provider calls; real-evaluation candidate meets renderer-ref boundary |
| `a-lost-model-call-leaves-a-durable-readable-reason` | Seeds succeed; query still asks error identity for occurrence message, and fake generated reads do not show the real diagnostic |
| `a-refused-definition-stays-in-its-agents-defs` | Seeds succeed; queries retired `:seon.def/*` and result storage; refusal injection names old single-evaluation settlement |
| `runtime-schema-unregister-removes-one-unused-global-schema` | Seeds succeed; retired observation masks installed-attribute residual; direct writer probe proves the separate cache boundary |

No retired test was deleted in this continuation without verified covering
coverage. The umbrella issue stays open. All failing candidate edits remain
outside source; neither assertions nor bounds were relaxed.

The requested gate file now contains exactly `seon.cluster.turn-test`,
`seon.sci.eval-test`, and `seon.turn-test`, each verified under `test/`.
No test JVM, `bin/test`, or `bin/test-fast` was launched. Cold namespace and
platform proof remains the orchestrator's gate, not these focused results.
The six retained focused regressions total **49 passing assertions**, each
green before its edit and after isolated adoption; they are not one combined
suite result. `git diff --check` passed for the owned source/doc changes.

At 03:43 UTC a read-only MCP probe with explicit default custody found source
commit `6aaa0c28-fb31-5521-9bd8-c9c64e5f123f` and both `error/recording` and
`:seon.error/fact` in the installed attempt-recorder program row. This verifies
that row, not complete convergence or an executed default regression. The
later prompt hook request `141d5156-152c-4655-b6fb-abbacb23ba9d` returned operator
124; no passing default adoption is inferred from a queued edit.

Cleanup: the last in-process test completed before operator `down` sent SIGTERM
to owned PID 33326. The operator reported the flock free and a readable roster;
the process-table check found no remaining PID 33326. The owned scratch
worktree/root was removed after unlinking only its `reference-code` symlink.
The shared dependency checkout and all other worktrees were preserved.
No lane-owned background shell or development JVM remains. The class stops
at the priced dependency-owner decision above; it is not marked complete.
