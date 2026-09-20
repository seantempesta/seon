---
type: research
status: active
created: 2026-09-20
tags: [schema, config, plan, data-model]
---

# Wave 1d — config and plan family

## Current landing status (supersedes the historical gate below)

- C7: `5a0a805e4`; R3: `c50625da4`; R2: `2d1323fcd`.
- Plan needs: converted from optional refs to indexed identity values.
  The chosen value dial preserves the prerequisite token through deletion;
  a missing prerequisite blocks, and explicit dependency retraction clears it.
  Consumers converted: the two plan pull selectors, frontier derivation,
  add-step writer, whole-tree compiler/comparison, and dependency retractions
  in `src/seon/plan.clj`; dependency inputs in `test/my/plan_test.clj`.
  Deleted the now-unneeded document-reference decoder. The title renderer
  still uses lookup refs to resolve identity strings, as required by pull.
- The new canonical `seon.plan-test` regression creates the agent through
  `creation-tx`, adds two steps through the plan owner, retracts the prerequisite
  through the admitted writer, pulls the surviving dependency value, verifies
  blocked/ready reads, then explicitly retracts the dependency and verifies ready.
  It passed. Fast tally: **23 tests, 131 assertions, 0 failures, 7 errors**.
  Log: `tmp/config-plan-1d-needs-fast.log`. The errors concern existing refusal
  contracts and `seon.fn.schema-shape/normalized-form` on refusal observations.
  The existing open issue `test-refusal-observations-overflow-in-projection-acquisition`
  owns that class; this tally is not a green namespace claim.
- **RESET NEEDED: `:my.plan.item/needs`**, ref → string, cardinality many.
  The same resource documents the optional sweep behavior of agent, subject,
  and completion refs and moves the `about` codec rationale into its docstring.
- Cold plan gate owed:
  `bin/test --paths resources/seon/schemas/my.plan.item.edn src/seon/plan.clj test/seon/plan_test.clj test/my/plan_test.clj -- seon.plan-test my.plan-test`.
  The orchestrator also owes the platform proof. No reset or lifecycle command
  was run, and no worktree was created.
- Unowned plan fixtures still use ref-shaped needs in
  `test/my/plan_api_test.clj`, `test/seon/transaction_result_test.clj`,
  `test/seon/transact_feedback_test.clj`, `test/seon/render/value_test.clj`,
  `test/seon/context_blocks_fixture.clj`, `test/seon/sci/shown_text_test.clj`,
  `test/seon/html_views_test.clj`, and `test/seon/loop_proof_test.clj`.
  Their owners must convert dependency values to step identity strings.
  The held `src/seon/render/value.clj:333–336` old ref-map specialization is
  now unnecessary; string values already follow its ordinary value path.

## R1 implementation and C4 boundary

R1 removes the `:seon.config/settings` entity mirror and `:seon.config/display`
roster; `:seon.agent/settings` now names `:seon.config/agent-overlay` as its
component schema. Display properties live on the dial declarations. A native
EDN inventory on 2026-09-20 counted **34 per-agent dials** and **15 millisecond
dials**, with zero missing per-agent labels or millisecond display divisors.
The settings renderer reads each dial's properties and shows unset dials
explicitly. Its output contract names database read, unknown-shape, and
missing-effective facets; its edited branches do not read `:seon.error/kind`.
A minimal schema admission check refuses a per-agent dial missing its label
as `:seon.schema/validation-refusal`, naming the attribute and expected property.

The four-namespace iteration completed with **80 tests, 3,849 assertions,
9 failures, 10 errors** (`tmp/config-plan-1d-r1-fast.log`). R1's new regression
passed its live declaration pulls, coverage of every per-agent dial, and typed
missing-display refusal. C7, R3, and the prerequisite deletion regressions also
passed. The remaining schema assertion expects operation `seon.schema/pulled-form-in`
where the current diagnostic names `seon.schema/pulled-selector-refusal`;
config and plan failures are the refusal classes already recorded above.
The slot wait was **545 seconds**; no competing JVM or slot override was
launched by this lane. The final config-only run verified the renderer's reuse
of the carried projection and the finalized labels: **23 tests, 178 assertions,
8 failures, 3 errors**, with the new R1 regression passing again
(`tmp/config-plan-1d-r1-final-fast.log`).

R1 has no data reset: the two retired keys are schema declarations and the
new metadata is additive. The unowned `test/seon/html_views_test.clj:198`
synthetic test still edits the retired table and must move its metadata to
its synthetic attributes. C13's three backup string copies remain listed:
`resources/seon/schemas/seon.config.ai.backup.edn` api-key-variable, endpoint,
and model; aliasing their shapes while retaining independent dial properties
is a follow-up beyond the requested one-line cleanup.

Cold R1 proof owed (the brace expansion lists only this item's schema files):

```sh
bin/test --paths \
  resources/seon/schemas/seon.config{,.agent,.ai,.ai.backup,.ai.retry,.db,.effect.background,.eval,.flow,.operator,.render,.run,.shell,.web}.edn \
  resources/seon/schemas/seon.agent.edn \
  src/seon/agent.clj src/seon/schema.clj test/seon/config_test.clj \
  -- seon.config-test seon.plan-test my.plan-test seon.schema-test
```

The separate R2 command below includes `seon.turn-test`; the orchestrator owns
both cold gates and `bin/test --platform`. This lane ran neither.

C4 investigation found that a strict check of all top-level qualified Seon
properties would additionally reach undeclared `:seon.db/cardinality`,
`:seon.error/class`, `:seon.error/refusal`, `:seon.issue/cites`, and
`:seon.schema.admission/exemption` / `:seon.schema.admission/reason`.
These belong to resources outside this lane's allowed edits, including held
error/schema owners. Silently exempting these names would defeat the requested
class-level refusal. The existing regression
`canonical-rows-carry-arbitrary-namespaced-properties` also explicitly expects
an undeclared property to be silently omitted, so its rule must change with
the owning declaration seam rather than introducing a parallel registry.

The proposed `:seon.config/default :seon.schema/value` resolves to `:any`.
The current bridge maps native scalars and mixed unions, but not `:any`
(`src/seon/schema/datahike.clj:123–184`); `storable-properties-in` drops a
property that is not storable (`:311–318`). The foreground probe falsified the audit's storage premise:
`:seon.config/default-storable? false`, persisted default properties `{}`;
the proposed shell and render properties both returned storable `true`.
It also confirmed exactly the six additional undeclared properties listed
above. The probe's global millisecond count is 16: the 15 config-family dials
plus unowned `:seon.test/check-time-limit-ms`. Reproduce with
`clojure -M docs/prds/steward-platform/research/config-plan-family-1d-property-probe-2026-09-20.clj`.
Raw output: `tmp/config-plan-1d-r1-load-and-property-probe.log`.
The requested five-namespace load printed `:loads`, and the combined JVM
exited 0. No C4 production change has been made.

The later read-only default MCP attempt returned `repl-unavailable` with
advertisement state `missing`; it did not execute. The observation was appended
to the existing issue `mcp-runtime-status-lists-no-clusters-for-an-explicit-root`.
No default lifecycle operation, substitute transport, or foreign session action
was attempted. Fast snapshots continue to admit dirty foreign callers at HEAD
bytes; no overlay refusal has been observed.

## C4 owner design gate — remaining work

C4 cannot honestly claim the requested queryability or recurrence guarantee
inside the current ownership. The audit's exact default alias stays unstored,
and six additional properties belong to unowned resources. A scope question
with these three options has been submitted; no answer has been assumed.

1. **Recommended: extend the declaration cut.** Release
   `seon.db.edn`, `seon.error.edn`, `seon.issue.edn`, and
   `seon.schema.admission.edn` for their missing properties, and choose a
   storable, non-nil default shape instead of `:any`. Add the strict admission
   check in the existing schema owner. Guarantee: all current Seon property
   declarations are explicit and new undeclared ones refuse. Cost: four more
   schema owners and their canonical regressions. Give up: an arbitrary
   in-memory value as a persisted default.
2. **Check reads and migrate their owners.** Add one supplied-registry property
   reader and convert every relevant source reader, including the held render,
   database, source-publication, shell, and schema-EDN readers. Guarantee:
   consumer property reads name declared keys. Cost: cross-owner source work;
   leaves compile-time annotations distinct from runtime property reads.
3. **Declare the three properties only, with a storable default shape.**
   Guarantee: those observations become queryable. Cost: the smallest bounded
   slice. Give up: the universal undeclared-property refusal; keep that work
   explicitly open rather than claiming the class cannot recur.

The scope boundary comes from the assignment's owned paths and AGENTS.md's
owner design gate. No foreign owner file or session was changed to pass it.

## Final reset and deferred accounting

- **RESET NEEDED:** `:my.plan.item/needs` (many refs → identity strings),
  `:seon.ai.attempt/model` (retired), and false values previously stored under
  `:seon.config.agent/show-all-settings` / `:seon.config.ai/retain-reasoning`.
- C14: `:seon.cluster.eval/at` → recording-transaction ref remains deferred
  with every writer and render consumer; exact handoff sites appear below.
  Attempt observation time remains an instant by the audit's own ruling.
- C4: untouched pending the gate above. Its live probe made no database write.
- R1 retires declaration keys, not stored value types. A pre-R1 schema
  population without dial labels will refuse the new admission rule; the
  orchestrator owns publication/reset and live boot verification.
- Default adoption, browser observation, cold gates, and platform proof are
  not claimed. The lane's evidence is instrumented canonical fixtures,
  their pulls, and foreground namespace loads.

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

## Historical decision boundary before production edits

### R2 result and C14 handoff

R3 landed in `c50625da4`; C7 landed in `5a0a805e4`. Both post-commit
five-namespace load commands completed with exit 0 and `:loads`. R3's shared
test file was committed by staging only its 22-line regression hunk, after
checking that the index contained exactly the three owned paths; the foreign
hunks remained unstaged and unchanged.

R2 deletes the `:seon.ai.attempt/model` declaration and attempt entity entry,
the recorder's descriptor lookup and ref assertion, and the evaluation-drive
selector's ref expansion. `:seon.ai/model` remains the observed identity;
its description names the descriptor join. `rg` finds no remaining uses of
the retired key in `src/` or `resources/`.

The attempt resource now explains the deliberate sweep of its optional
settings-owner ref and the distinct historical meaning of required settings
bytes and opaque provider usage bytes. These are retained, per the guide and
the bounded R2 ruling, rather than discarding provider-specific evidence.
Attempt time stays an instant because the observation predates recording.

The canonical regression in `test/seon/turn_test.clj` uses the real recorder,
asserts the descriptor exists, retracts it through the admitted writer, and
pulls the surviving attempt's model identity and original observed instant.
It passed. Fast command:
`bin/test-fast --paths resources/seon/schemas/seon.ai.attempt.edn resources/seon/schemas/seon.ai.edn src/seon/turn.clj src/seon/eval/drive.clj test/seon/turn_test.clj -- seon.turn-test`.
Tally: **34 tests, 195 assertions, 12 failures, 6 errors**; raw log
`tmp/config-plan-1d-r2-fast.log`. Failures include existing wake, generated-read,
settlement, fault-rendering, and schema-shape diagnostic paths; they were not
silently assigned to R2 or repaired outside the released recorder region.
The overlay printed that dirty render callers use HEAD bytes and **admitted**
the snapshot; that notice was not an overlay refusal.

**RESET NEEDED (R2): `:seon.ai.attempt/model`.** Unowned
`test/seon/data_shapes_test.clj:314–318` must replace its old model-ref selector
and assertion with `:seon.ai/model`; it was not part of the released test path.
The first schema patch was refused before mutation because admission checked
the removed declaration while its entity entry still existed. Applying the
entity/caller removals before the declaration removal passed admission.

C14 chosen dials: keep `:seon.ai.attempt/at` as the external observation
instant (docstring now explicit); keep the model's `last-used-at` observation
semantics, whose docstring remains with the unowned model resource. Defer
`:seon.cluster.eval/at` to a recording-transaction ref. Its held render sites
are `src/seon/render/transcript.clj:43` (selector), `:117` (ordering query),
and `:262` (render timestamp); the query must follow the replacement ref to
`:db/txInstant`. The same slice must convert `src/seon/eval/drive.clj:151,166`,
`src/seon/bootstrap.clj:806`, `src/seon/cluster/status.clj:127,131`, and the
creation/settlement sites in `src/seon/turn.clj` inventoried above. There is
no second audit-named timestamp that warrants a non-render conversion.

Cold R2 proof owed: the fast command above with `bin/test` replacing
`bin/test-fast`, plus orchestrator `bin/test --platform`. Default was not
adopted or reset by this lane.

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

## Historical item status at the original scope stop

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

## Historical evidence at the original scope stop

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

Historical leftovers at that original stop: all implementation items remained pending. Audit C3/C8/C13 cleanup
has not been applied; the guide's stale archived-tx TARGET row remains outside
this lane's owned paths. No findings are represented as completed fixes.
