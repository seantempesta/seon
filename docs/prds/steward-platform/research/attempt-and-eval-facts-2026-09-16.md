---
type: research
status: verification-pending
created: 2026-09-16
tags: [steward-platform, data-model, facts, verification]
---

# Attempt and evaluation facts

Bounded lane: `attempt-and-eval-facts`, rows 7, 8 and 11 of the
[namespace data model](../plan/namespace-data-model-2026-09-16.md).
Implementation is present; these rows are **not closed**. The canonical
in-process proofs and successful development adoption remain outstanding.
No test JVM was launched and default was never stopped or reforked.

## Class and construction

Structured provider counts and related entity identities should be queryable
facts, not reconstructed from EDN or a function's spelling. The existing attempt
writer now records normalized usage and settings/model refs; the existing
evaluation settlement writer records the selected function ref. Readers follow
those facts. There is no second decoder, writer, or identity generator.

The namespace data-model authority was read end to end, including §8, along
with the issue README and applicable skills. AGENTS.md supplied the governing
rules; the N7 row of the
[class mining record](../../sci-execution-runtime/research/issue-class-mining-2026-08-11.md)
provides the fact/query structural direction. This assignment names three rows,
not an entire N7 member set; it does not close the broader class.

Dependency ledger: `seon.ai/normalize-usage` (`src/seon/ai.clj:972`) already
normalizes provider keys; history includes `7ec751389` (model registry).
`resources/seon/schemas/seon.ai.model.edn` identifies registered models by their
provider identifier. The existing agent settings component and cluster config
are the settings sources. Installed storage derives from schema reach, rather
than every alias merely declared in a schema file. Datahike lookup refs at the
transaction seam resolve the renderer's stable program identity; no new
first-party program lookup registry was introduced.

## Baseline and per-row verdicts

Read-only default probes found 91 usage EDN holders, 91 settings EDN holders,
and 44 renderer symbol holders. The four usage aliases existed in source but
were not installed storage attributes: native schema pull was nil and a query
reported attribute-not-installed. Adding them to the stored attempt shape is
therefore necessary, not just changing the writer.

* **Row 7 — implemented, verification pending.** The attempt writer merges
  `normalize-usage` into the attempt. Sample provider values normalize to
  prompt 22134, completion 184, total 22318, cached 21504. Prompt calibration,
  status totals, evaluation export and transcript counts read these facts.
  The schema declares no provider-specific hit/miss fields; none were invented.
  Transcript miss count derives as prompt minus cached (630 for the sample).
  `usage-edn` remains the existing `pr-str` representation of the received map;
  it is not a byte-for-byte copy of the HTTP response. Status still reads its
  provider-specific `cost` field because no corresponding normalized fact is
  declared. This is the explicit residual before raw-usage retirement.
* **Row 8 — implemented, verification pending.** `:seon.ai.attempt/settings`
  references the agent settings component when present, otherwise cluster
  config. It identifies the source entity, not an immutable snapshot of merged
  effective dials; `settings-edn` retains that evidence. `:seon.ai.attempt/model`
  refs the registered provider model descriptor, rather than pretending its
  provider identifier is a program symbol. The live DeepSeek descriptor was
  entity 38721; root and Juniper settings components were 38780 and 42036.
  An unregistered target retains its existing wire identifier and has no
  invented descriptor. The new regression verifies the registered case.
* **Row 11 — implemented, verification pending.** `evaluation-facts` adds
  `:seon.eval/renderer-fn` beside the existing symbol, using the program row's
  identity lookup ref. Settlement schema and terminal attributes admit it;
  stored-record comparison normalizes pulled refs. REPL and transcript readers
  prefer the ref, with legacy symbol fallback for historical rows. Inspection
  of `src/seon/sci/eval.clj` showed its renderer symbol is an in-memory selection
  carried to settlement, not a durable reader; no second ref writer was added
  there. A live reference-only projection resolved function entity 5937 to
  `seon.problems/stale-var-ai` and preserved saved shown text.

Historical attempts/evaluations were not backfilled. No paid attempt or
synthetic durable evaluation was generated in the owner's cluster. The newest
attempt remained entity 66875 (2026-09-15T23:35:05Z), with none of the new facts;
evaluation 66876 retained `seon.note/render-note-ai` without the new ref. Thus
these pulls are baseline evidence, **not** the requested post-adoption proof.

## REPL development and in-process results

Changed function forms were evaluated in the development JVM before file edits,
then probed with representative data. Test forms were loaded through the live
test loader. All runs below used the default connection and
`(seon.test/run #'namespace/test (seon.operator/connection "default"))`.
No `bin/test`, `bin/test-fast`, or private fixture-global reset was used.

| Test | Recorded run | Assertions passed / failed / errors | Evidence |
|---|---:|---:|---|
| `seon.data-shapes-test/attempt-usage-is-queryable` | 68180, 68206, 68264 | 0 / 3 / 0 each | Retained fixture rejects completion-token attribute before writer can persist the attempt. |
| `seon.data-shapes-test/attempt-settings-and-model-are-related-entities` | 68256 | 3 / 1 / 0 | Both refs existed; the assertion compared different pull depths of settings entity 39520. Corrected to EID equality. |
| same, corrected assertion | 68259 | 0 / 4 / 1 | Retained fixture again rejects the usage attribute; downstream EDN read receives nil. |
| `seon.data-shapes-test/evaluation-renderer-is-a-program-reference` | 68266 | 1 / 2 / 0 | Retained fixture lacks renderer-fn; transaction-success assertion subsequently strengthened to require db-after. |
| `seon.cluster.status-test/accounting-joins-runtime-turns-and-preserves-unknown-cost` | 68210 | 12 / 1 / 0 | Old fixture supplied EDN only; updated to supply total-token fact. |
| `seon.cluster.prompt-test/calibration-uses-the-agents-recent-attempts-and-config-prior` | 68211 | 1 / 5 / 0 | Old fixtures supplied EDN only; updated to supply count facts. |
| `seon.render.web-debug-test/turn-details-use-the-loop-opening-and-exact-segments` | 68212 | 86 / 5 / 1 | Old usage fixtures plus system-turn failures and nil cost subtraction; usage fixtures updated, remaining behavior requires fresh gate. |
| `seon.repl-test/history-preserves-shown-text-without-applying-a-later-profile` | 68223, 68248 | 4 / 0 / 0 each | Stored shown text preserved. |

These run IDs are from the complete tool responses; the table does not claim
an entire namespace ran. A later pull of these numeric IDs on default returned
nil, so their continued availability on that branch is not asserted.
Several intervening result-recording calls returned `empty not supported on
type: datom` instead of a result. Later result recording succeeded; no causal
fix or passing result is inferred from those failed calls.

Direct probes also verified transcript normalization (22134 prompt, 21504 hit,
630 miss, 184 completion), reference-only REPL projection, and legacy attempts
in the export reader. Existing unbackfilled attempts yield unknown provider
counts rather than fabricated zero. The public changed function symbols were
still present in `seon.instrument/instrumented`; an explicit broad re-arm met
an invalid-schema error during shared changes, so this is not a claim that
the entire development JVM is healthy.

## Verification boundaries and handoff

The existing
[retained canonical fixture issue](../../../seon/issues/canonical-fixture-retains-old-function-contracts-after-adoption.md)
also covers this lane's old attribute population. No fixture globals or
protected owners were modified to get around it.

Native schema later showed the new count/ref attributes, but the development
adoption stamp remained `6aa9e66f-89d6-5324-b9dd-24427d088b48` while published
source was `6aa9ef49-f29f-5fb4-8e10-fccc61e279b4`. Hook feedback included a
build-manifest contract refusal, later a file-identity conflict in the protected
program owner. The cause of those shared failures was not independently
attributed to another lane's edit. An explicit publication retry exited with
root-creator mismatch after waiting behind the hook; its shell completed.

The orchestrator must run the path-limited affected namespaces and platform
gate serially, establish successful adoption, rerun the three canonical row
tests, and pull a subsequently recorded attempt/evaluation showing the new
facts. The gate request is
`tmp/orchestrator/gate-requests/attempt-and-eval-facts.txt`.
No default reset, foreign-session operation, test JVM, or owned background shell
is part of this handoff. Broader class closure and raw EDN retirement remain
outside this verified result.

Final recheck: default had changed to PID 7595, start 01:36:02Z, PREPL 51919.
The test namespace was absent and was loaded through `with-test-loader`.
The usage test then timed out at the MCP 20,000 ms boundary; completion is
unknown and no further test was launched. Runtime status also timed out and
reported health/Flow unknown. This is recorded in the existing
[MCP timeout issue](../../../seon/issues/default-component-probe-times-out-after-adoption.md).
`git diff --check` passed. Markdown hook feedback still includes unrelated
gitlink citations in the agents-md audit; this lane did not edit that audit.

## Renderer regression follow-up — 2026-09-16

**Fixed and verified for row 11 in `cecfaf428`.** The new canonical regression evaluates real
SCI source, crosses `settlement-projection` and `evaluation-facts`, commits with
the existing settlement transaction, and pulls both the renderer symbol and
function ref. A plain map passes the same path and has neither attribute.
The former regression supplied the renderer by hand and therefore could not
detect loss before settlement. This follow-up replaces that incomplete proof.

The root cause is `563034709`: `seon.render.value/value-node*` added `(not ai?)`
to its existing pair-selection entrance. Thus `prepare` had no selected
renderer to hand `shown-result`; settlement preserved that absence. The fix
removes that guard and restores the one existing declared-pair mechanism.
The original result remains in SCI; callers explicitly requesting structural
printing still use the existing `:seon.render.value/structural?` option. The
structural-results regression now requests that option and retains all its
attribute, bound, live-object and requery assertions. No new rendering path
or clipping spot was added. `474234fb7` only changes inert-reference reads;
it does not remove the selected renderer from settlement.

Read-only default baseline reproduced 78 evaluations with zero renderer
symbols/refs; `seon.plan/format-plan-ai` existed as function entity 6298.
An immutable Datahike `with` probe with an absent function lookup ref rejected
the entire transaction with `:entity-id/missing`, disproving silent omission
of the two attributes. A no-renderer `evaluation-facts` request emitted only
turn identity and ordinal. `turn.clj` needed no edit, so the error-graph lane's
protected hunks were untouched.

The candidate `value-node*` form and new regression were evaluated in the
default JVM before source edits. The canonical fixture initially exposed a
missing test `user` namespace row; that setup was corrected. A later assertion
incorrectly compared raw shown text with the REPL response envelope for a plain
map; it was corrected to compare the stored shown text directly. Final runs:

| In-process test | Before file edit | After disk reload | Result |
|---|---:|---:|---|
| `seon.data-shapes-test/evaluation-renderer-is-a-program-reference` | 61944 | 62271 | 11 pass, 0 fail/error |
| `seon.render.value-test/explicit-structural-results-retain-attributes-through-real-evaluation` | 61970 | 65245 | 68 pass, 0 fail/error |
| `seon.render.value-test/block-pairs-remain-explicit` | — | 62190 | 15 pass, 0 fail/error |

Every run used `(seon.test/run #'namespace/test
(seon.operator/connection "default"))`. Runs 61936 and 61943 were the test
development failures described above; run 61945 demonstrated the obsolete
implicit-structural expectation (34 pass, 11 fail, 2 errors). There was no test
JVM, fixture-global replacement, or default lifecycle operation.

**Live virtual-turn proof:** default PID 7595, agent `renderer-facts-proof`,
turn `d0ec099ed5ec`, closed at transaction 536871544. `(dir seon.repl)` produced
evaluation `49e907fc33b9` with symbol `seon.repl/render-directory-ai` and ref
7183 to that exact function row. `{:example/plain 1}` produced evaluation
`d0caeb852edb` with neither attribute. The
[probe forms and exact pull](attempt-renderer-probes-2026-09-16.edn)
preserve this evidence. A separate newly recorded system plan evaluation
`f9a3e0a36a42` also carried `seon.plan/format-plan-ai` and ref 6298.

The first probe submitted Juniper turn `404bfad994bc`, but its already armed
proc raised `:malli.core/invalid-schema` for `:seon.error/recording` before
evaluating either form. That turn remains open; neither evaluation has a
terminal result. The fresh proof agent used the normal armer and completed
without operating Juniper's graph or editing its protected owners. Payload-free
wakes of Juniper did not resolve the stale-schema boundary. No claim of full
cluster health follows from the completed fresh-agent proof.

The loaded changed function's metadata was `seon/render/value.clj:265` after
publication, and the live result proves that behavior. Full adoption remained
unconverged: stamp `6aa9fe1b-203b-5ca1-bea2-9047ea996105` versus published
`6aaa0146-1d06-5369-94b1-d953bd377525`. This is a hot-reloaded-function proof,
not a claim of whole-cluster adoption. `git diff --check` passed; the hook
reported only existing shadow/redundant-let warnings after missing test
requires were fixed. The orchestrator's serial gate remains external.

## Batch 19 triage — 2026-09-16

Independent reds triage of the batch-19 named-namespace gate
(`tmp/orchestrator/gate-results/batch-19/attempt-and-eval-facts.md`, run at
02:32Z), plus the batch-24 `seon.issue-test` row the coordinator added. Read
that report, AGENTS.md and this landing note end to end. No test JVM was
launched: every verdict is `(seon.test/run #'<var> (seon.operator/connection
"default") {…})` in default's own JVM, one test at a time, complete returned
value read, the test namespace reloaded through `seon.test`'s own loader
first, and each run on its own daemon thread so no MCP bound can interrupt a
fixture. Default was never stopped, restarted or reforked. Counts are
pass/fail/error assertions.

### Verdict table

| Namespace | Test | In-process at HEAD | Attribution | Fix |
|---|---|---|---|---|
| seon.data-shapes-test | messages-and-turns-have-one-owning-edge-and-transaction-time | was 10/1/0 → **11/0/0 green** | `17dd75e89` authored `(is (= 8 (count (:seon.test/message-id probe))))`, but minted message ids have used `seon.id/id`'s default length of 12 since `c98d61b01` (2026-09-14) — the expectation was a stale mirror on the day it was written | FIXED `31173071d`: the length is derived from `(count (id/id))`, the one identity derivation, instead of restated |
| seon.data-shapes-test | raw-plan-forms-preserve-the-component-and-use-transaction-time | was 0/0/1 → **8/0/0 green** | `17dd75e89`'s `plan-probe` applies the canonical Juniper plan through raw `d/with`, bypassing `seon.db/transact!`'s ONE encoding seam. `:my.plan.item/done-query` is `:seon.db/query`, installed as `:db.type/string`; the fixture authors a Datalog vector (`d31d31639`), so Datahike refused: *"value does not match schema definition. Must be conform to: string?"* | FIXED `31173071d`: the probe seed goes through `seon.schema.datahike/encode-transaction-in` with the database's projection, exactly as the writer does |
| seon.cluster.status-test | routine-status-declares-unmeasured-store-size | **3/0/0 green** | Not this lane. `17dd75e89` touched only `:seon.cluster.status/provider-tokens` in this namespace. The gate's refusal was `snapshot` returning `[:seon.cluster.status/faults 0 0]` as a keyword — `(vec (sort-by first faults))` over a FLAT ERROR map yields MapEntry tuples keyed by `:seon.error/kind`. `3f4f0cdf2` (error-graph) rewrote that query onto occurrences and it now answers | none needed — green at HEAD |
| seon.render.web-debug-test | turn-details-use-the-loop-opening-and-exact-segments | 3/0/1 — still red | The `:seon.eval/renderer-fn` class `17dd75e89` introduced: `seon.eval/of-agent` refuses its own return, *"at [0 :seon.eval/renderer-fn]: expected an integer, got a map"*, caller `seon.render.walk (walk.clj:887)`, 17 problems. `52044b4f4`'s admission IS present in both the live and the fixture projection — verified by reading `:seon.eval/entity`'s form from each: `[:seon.eval/renderer-fn {:optional true} [:or :seon.eval/renderer-fn [:map [:db/id :int]]]]`. The armed wrapper nevertheless applies the bare `:seon.db/ref` | LEFT. The schema fix is correct and installed; what remains is that the armed contract for `of-agent` does not use it. Owner: the renderer-fn class, [evaluation-reader-refuses-pulled-renderer-ref](../../../seon/issues/evaluation-reader-refuses-pulled-renderer-ref.md) |
| seon.issue-test | issue-worker-opening-links-its-issue | 6/2/0 — still red | NOT this lane, and not a stale assertion. Probed the opening directly: every opening evaluation HAS a source and none has an error, but **the last one is appended and never evaluated**. Ordinals 0–2 (`(help)`, the identity pull, `(seon.plan/plan {})`) all carry `:seon.eval/shown`; ordinal 3, `(my.issue/status {:seon.issue/id "issue-family-opening"})`, carries source and no shown. Re-probed with `:seon.issue/budget` 8 instead of 1: identical, so it is not the episode cap. `seon.turn/generate-turn` appends form N then `resume-turn`s it, and the run closes with N appended but unevaluated | LEFT. Owner is the `seon.issue` opening contribution (`src/seon/issue.clj:257`, protected — held by the issue-family lane) meeting `seon.turn/generate-turn`. The assertion at `issue_test.clj:109` is RIGHT: an appended evaluation with neither shown nor error is absence-of-signal read as health |

### Exact probe for the issue-test row

```clojure
;; seon.issue-test's own body, returning the evaluations instead of asserting
[{:ord 0, :src "(help)",                                :shown? true}
 {:ord 1, :src "(seon.db/pull '[…] [:seon.agent/id \"71c045b448dd\"])", :shown? true}
 {:ord 2, :src "(seon.plan/plan {})",                   :shown? true}
 {:ord 3, :src "(my.issue/status {:seon.issue/id \"issue-family-opening\"})", :shown? false}]
```

`:seon.issue/budget` becomes `:seon.config.run/max-episode-runs`
(`src/seon/issue.clj:384`); budgets 1 and 8 produce the identical result, so
the cap is not the cause.

### Observed alongside, out of this triage's scope

Running the rest of `seon.data-shapes-test` after the fixes: 
`handling-an-outside-message-retains-its-budget-basis` 4/0/0,
`provider-reasoning-is-retained-only-by-an-explicit-setting` 12/0/0,
`attempt-usage-is-queryable` 3/0/0,
`listen-patterns-retain-optional-entity-and-logical-value` 3/0/0,
`a-system-turn-leaves-the-inbox-edge-unhandled` 2/0/0, but
`attempt-settings-and-model-are-related-entities` 4/0/1 and
`evaluation-renderer-is-a-program-reference` 11/0/1. Neither was in the
batch-19 list and neither touches `plan-probe`; both are this lane's own
tests and belong to the same renderer-fn/attempt surface.

### Verification hazard

The shared `seon.test-support/database-base` delay caches a failed
construction permanently, so one interrupted or classpath-blocked force takes
down in-process testing for every lane in default's JVM. Hit twice during this
triage and repaired both times; filed as
[in-process test runs poison the shared fixture base](../../../seon/issues/in-process-test-runs-poison-the-shared-fixture-base.md).
Every verdict above was measured after the repair.
