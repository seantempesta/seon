---
type: research
status: active
created: 2026-09-20
tags: [schema, config, plan, data-model]
---

# Wave 1d — config and plan family

## Resumed ruling and C7 result

The orchestrator approved option 1 plus the bounded R2 extension in
`src/seon/turn.clj`'s attempt recorder and `test/seon/turn_test.clj`.
C14 changes reaching render files remain deferred. The original stop record
below is historical; this section and subsequent results supersede its status.

C7 changes `:seon.config.agent/show-all-settings` and
`:seon.config.ai/retain-reasoning` from `:boolean` to `[:= true]`.
Their docstrings state that absence disables the setting and retraction clears
it. Runtime consumers (`src/seon/agent.clj:137`, `src/seon/turn.clj:3913`)
already test truth and need no change. `config/default.edn` already declares
both absent.

The new `optional-setting-flags-assert-only-true` regression uses the canonical
fixture and `test-support/apply-config!`, pulls the two stored true facts,
validates true/false/nil against the database's acquired projection, then
reconciles their absence and pulls the surviving config identity. It passed.
Fast command:
`bin/test-fast --paths resources/seon/schemas/seon.config.agent.edn resources/seon/schemas/seon.config.ai.edn test/seon/config_test.clj -- seon.config-test`.
Tally: **22 tests, 105 assertions, 8 failures, 3 errors**; log
`tmp/config-plan-1d-c7-fast.log`. The failures include existing missing-cluster
and error-diagnostic contract paths; this is not a green namespace proof.
The requested five-namespace load completed with **exit 0, :loads** before
commit. The test namespace itself includes a subprocess-loading regression;
no second test invocation was launched by this lane.

**RESET NEEDED (C7):** the two attributes above wherever stored overlays contain
explicit false. No lifecycle command or reset was run. The unowned
`test/seon/data_shapes_test.clj:217–233` still writes and expects false for
retain-reasoning; its owner must convert that case to absence.

R3 is implemented and its new fixture regression passed, including pulls of
all four installed schema declarations. The schema namespace tally is
**34 tests, 3,535 assertions, 24 failures, 1 error**. Existing calls to
`seon.error/diagnostic` at `src/seon/schema.clj:2796` omit the now-required
base observation; related render-contract and projection-source refusal tests
also fail. No such foreign owner hunk was changed. A concurrently added hunk
in `test/seon/schema_test.clj` is preserved and excluded from this lane's
commit. The first four-namespace request executed no tests because
`seon.plan-test` does not yet exist; the R3 iteration then selected
`seon.schema-test` alone.

Cold proof owed for C7:
`bin/test --paths resources/seon/schemas/seon.config.agent.edn resources/seon/schemas/seon.config.ai.edn test/seon/config_test.clj -- seon.config-test`,
then orchestrator `bin/test --platform`. Hook publication is configured off;
these fixture observations do not claim default-cluster adoption.

## Decision boundary before production edits

Assignment received against branch `steward-platform`; inspected HEAD
`4b3c4b5f3`. No source, schema, or test edits made. This is a scope/design
stop, not an attribution of foreign test failure. No worktree, lifecycle
command, publication, reset, provider request, or foreign session operation.

Read the supplied AGENTS.md sections 0–5 end to end; the modeling guide's
"The one page" and sections 2–6; the data-modeling skill end to end;
[audit A](schema-audit-a-2026-09-19.md) end to end, including section 2b;
and the error-conversion PRD sections 1.2 and 3. Also read the
data-oriented-clojure and REPL skills and the active roadmap entry.

The following conflicts need the owner design gate before deleting facts:

1. **R2 cannot land inside the named ownership.** The actual writer is
   `src/seon/turn.clj:3932–3933,3977`: it resolves the descriptor and writes
   `:seon.ai.attempt/model`. The regression explicitly expects that ref at
   `test/seon/data_shapes_test.clj:314–318`. Neither file is owned by this
   lane. Removing just the schema and `src/seon/eval/drive.clj:198` selector
   leaves a live writer of the retired attribute. AGENTS.md lane rule 13
   requires the caller conversion and retirement in one slice.
2. **The other C1 cases are not identity duplicates with the same fix.**
   The settings ref identifies a mutable owner; `settings-edn` records the
   historical effective settings (`seon.ai.attempt.edn:11–13`, writer
   `turn.clj:3976–3979`). Audit C1.2 offers requiring the ref **or documenting
   why the snapshot outranks it**, not blindly deleting it. Raw provider
   usage and normalized usage are written together at `turn.clj:3980–3981`;
   `data_shapes_test.clj:261–282` supplies provider-specific nested fields
   and asserts both normalized counters and the exact raw payload. Guide
   section 5.3 explicitly permits opaque provider usage/settings strings.
   Replacing these requires an explicit retention guarantee; it cannot be
   inferred from “same treatment.”
3. **C14 does not identify two timestamp conversions.** Audit C14 calls
   `:seon.cluster.eval/at` a conversion, explicitly calls
   `:seon.ai.attempt/at` a correct instant that needs a docstring, and calls
   `:seon.ai.model/last-used-at` a docstring omission. Guide section 4.2 also
   says to keep attempt time. Evaluation conversion reaches the unowned
   `seon.cluster.eval.edn`, `src/seon/turn.clj`, `src/seon/bootstrap.clj`,
   `src/seon/cluster/status.clj`, and the held
   `src/seon/render/transcript.clj:43,117,262`, plus their tests.

## Three concrete options

1. **Recommended — keep this lane config/plan scoped.** Finish R1, C4,
   plan dependency values, C7, and R3; assign R2 and evaluation timestamps
   to their owning slices. Guarantee: no retired attribute retains a live
   writer. Cost: a separate coordinated attempt/evaluation slice. Give up:
   claiming all six original items complete in this lane.
2. **Expand R2 ownership.** Include `src/seon/turn.clj`,
   `test/seon/data_shapes_test.clj`, and the attempt entity entries in
   `seon.ai.edn`; retain attempt time and historical payloads with explicit
   docstrings. Guarantee: model ref removal is atomic with its writer and
   regression. Cost: turn-owner coordination and additional verification.
   Give up: evaluation timestamp conversion in this lane.
3. **One coordinated attempt/evaluation conversion after held files land.**
   Enumerate replacement timestamps and payload retention semantics first;
   transfer all callers and tests together. Guarantee: coherent end-to-end
   conversion against the chosen semantics. Cost: multiple owner changes,
   expanded gates, and delayed implementation. Give up: immediate bounded
   delivery from this lane.

## Item status and reset accounting

| Item | Schema diff / consumers touched | Verification | Reset |
|---|---|---|---|
| R1 display declarations and overlay | None; pending decision | Not run | No applied change |
| R2 attempt facts | None; caller/scope boundary above | Source inventory only | Model attribute removal would require reset |
| C4 property declarations/refusal | None | Not run | No applied change |
| Plan needs | None; prefer identity values because required many-valued refs forbid legitimately dependency-free steps | Not run | Value conversion would require reset |
| C7/C14 | None; timestamp scope conflict above | Not run | C7 false overlay rows would require reset; C14 pending decision |
| R3 surface aliases | None | Not run | None |

**RESET NEEDED introduced by this lane: none.** Prospective reset list,
not applied: `:seon.ai.attempt/model`, `:my.plan.item/needs`,
`:seon.config.agent/show-all-settings`, `:seon.config.ai/retain-reasoning`.
No second timestamp conversion is invented.

## Evidence and verification boundary

The MCP runtime-status call succeeded: `default` PID **41822**, all returned
proc observations answered `reply`; reported problem counts were **1** error
signature and **29** errored evaluations. This is health observation only,
not adoption freshness, fixture correctness, or browser proof. No live fixture
pull or mutation was performed.

Dependency source read: Datahike
`reference-code/datahike/src/datahike/db/transaction.cljc:998–1015`
enumerates incoming **ref** attributes during retraction; `:718–740`
explodes many-valued inputs. This grounds the prospective plan value-edge
choice; no new database behavior is claimed.

Fast tally: **not run** (no implementation). Overlay refusal: **none observed**.
The requested namespace-load command completed with exit **0** and `:loads`:
`clojure -M -e "(require 'seon.config 'seon.plan 'my.plan 'seon.eval.drive 'seon.agent) (println :loads)"`.
It loaded the shared working tree, not an isolated HEAD snapshot.
The Markdown edit hook reported **55** lint issues; its displayed diagnostics
name stale dependency gitlink citations in
`docs/prds/context-generation/research/agents-md-audit-2026-09-15.md`.
The remainder of that feedback was elided, so no claim is made about all
55 causes. No foreign document was edited to clear the hook.
Cold gate remains owed after implementation:
`bin/test --paths <implemented owned files> -- seon.config-test seon.plan-test my.plan-test seon.schema-test`,
plus the orchestrator's `bin/test --platform` and any attempt/evaluation
namespaces selected by the final scope. A documentation note is not gate
evidence.

All inherited dirty files were preserved, including `bin/test`, error and
instrumentation owners, SCI kernel, render schemas/source, and render tests.
The existing open issue
[schema-field-types-admit-contradictory-owning-values](../../../seon/issues/schema-field-types-admit-contradictory-owning-values.md)
already records related settings/plan owning-value concerns; this note does
not claim to reproduce or fix those writer defects.

Leftovers: all implementation items remain pending. Audit C3/C8/C13 cleanup
has not been applied; the guide's stale archived-tx TARGET row remains outside
this lane's owned paths. No findings are represented as completed fixes.
