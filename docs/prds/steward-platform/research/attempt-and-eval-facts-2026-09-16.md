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
