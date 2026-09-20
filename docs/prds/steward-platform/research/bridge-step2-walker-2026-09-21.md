---
type: research
status: optional storage admission committed; handler custody decision pending; retirement uncommitted
created: 2026-09-21
tags: [schema, malli, bridge, projection]
---

# Bridge step 2: compiled walker

## Resumed after publication release

The owner released fn, cluster/source, cluster and turn after publication
`3ac00fb8e`, `fa1ff1dbe`, `6dae626e0`, `ef70b0dc5`. Their path status was
clean at the resumed census. The historical held-path observations below
describe the earlier checkpoint, not a current hold.

The steward-platform working edge's 11:05, 14:35 and 16:00 rulings require
optional unstorable members to remain in memory and be omitted from native
storage selection. Required unstorable members still refuse naming the key.
Admission and both old/compiled storage selections now apply this rule.
The canonical-population regression checks a producer carrying an arbitrary
object, canonical row construction, omission from both selections and the
required-member refusal. The existing whole-facet audit follows the same
optional-member rule.

The first resumed fast request `5e8c66c72579` at snapshot HEAD
`cade2f346f893299b9c03500d498ed298525a063` loaded and armed 1,472 contracts
(1,469 program-armable), then refused admission before executing tests.
Its external recording authority still executed the live store-holder's old check refusing
optional `:seon.error/offending` on
`:seon.test.runner/invalid-marker-reason-error`. Raw evidence is
`tmp/bridge-step2-optional.log`. Executed: zero; assertions: unavailable;
durable tally: unavailable. This is why the optional fix lands with the
additive construction checkpoint before the retirement commit. No public
helper is removed in this checkpoint. The new regression is not yet proven.
Foreign checkout callers explicitly excluded from that snapshot were
`src/seon/sci/admit.clj`, `test/seon/sci/admit_test.clj` and
`test/seon/env_test.clj`; their HEAD bytes were used.

## Handler custody decision after the first commit

The optional-member and additive construction checkpoint landed as
`22a1a0567723ddeeb26d476ab0b199b78757f815`. Production loads passed before
and after that commit (`tmp/bridge-step2-precommit-load.log` and
`tmp/bridge-step2-postcommit-load.log`). No public helper was retired in it.
The second fast request, `6173f1f457ce`, tested that HEAD and again refused
before executing tests; raw evidence is `tmp/bridge-step2-optional-landed.log`.
The recording seam in `src/seon/test/runner.clj` delegates persistent results
through `fresh-operator/live-root-value!`: this is a loaded live-JVM boundary,
not evidence that the new source check failed. Acquiring the commit there
belongs to the orchestrator. This lane did not operate default.

At refreshed HEAD `bd817b01d5534b085c836370e12edfef2c2748e8`, the shell
consumer exposes a new scope decision:

- `src/seon/shell/jvm.clj:86` obtains declaration forms inside
  `environment-overrides`; its handler at `:482` receives only request and
  effective configuration. Neither declares a supplied generation.
- `src/seon/effect.clj:663` owns the request database and context. Foreground
  dispatch at `:462` and background dispatch at `:811` call the same two-arg
  handler. The generation is available in this owner's existing context.
- `src/seon/effect.clj:451` explicitly assigns replacing the dynamic context
  with handler environment arguments to seon.env Phase 3.
- Effect admission already recognizes `:seon.schema/projection` on requests
  (`:190`), but neither dispatch branch currently attaches it to handler
  input. Carrying it there is a possible protocol change, not a missing
  bridge lookup. The step-2 specification forbids a new metadata carrier.

No effect protocol or shell implementation has been changed for this
decision. Exactly three options, with estimates incremental to the remaining
bridge conversion/proof work:

1. **Recommended: constrain step 2 to the existing effect context.** Acquire
   its carried generation once at the shell handler boundary and pass that
   explicitly through `execute` to `environment-overrides`. Guarantee:
   compiled navigation uses the request's generation, with no new registry,
   carrier or handler protocol. Cost: roughly half a lane-day including
   foreground/background and disagreeing-generation regressions. Give up:
   eliminating the existing dynamic-context read in this slice; approve
   that explicit scope exception and leave its retirement in Phase 3.
2. **Move explicit handler environment arguments into this slice.** Change
   the common foreground/background dispatcher, every handler contract and
   caller together. Guarantee: handlers receive custody as an ordinary
   argument. Cost: approximately 1–2 additional lane-days plus effect-owner
   coordination and capability integration proof. Give up: the bounded
   step-2 scope and Phase 3's current ownership of this transition.
3. **Authorize projection on dispatched request maps.** At the existing
   effect owner, attach the captured projection to both handler inputs using
   the existing qualified key; keep persistence/external encoding separate.
   Guarantee: explicit generation custody without changing handler arity.
   Cost: approximately half to one lane-day for contracts and both dispatch
   regressions. Give up: the no-new-metadata-carrier constraint for this
   boundary; request values now also carry execution custody.

The retirement remains uncommitted. The raw walker still exists and the
refreshed namespace-reference census has 22 executable paths, including the
walker itself and the newly discovered `test/seon/sci/eval_test.clj` caller.
Remaining callers and tests are not converted. The working tree changes
public bridge APIs and is a partial implementation, not a landing candidate.
The earlier 1,012-attribute equality is construction-checkpoint evidence;
it is not a green result for this later conversion or today's population.
No cold/platform/live proof, final parity or deletion-budget claim is made.

Owned uncommitted source/test paths at this decision (foreign changes are
excluded; no foreign file or session was modified):

```text
src/seon/agent.clj
src/seon/cluster.clj
src/seon/cluster/source.clj
src/seon/config.clj
src/seon/db.clj
src/seon/error.clj
src/seon/fn.clj
src/seon/issue.clj
src/seon/print.cljc
src/seon/reconcile.cljc
src/seon/render.clj
src/seon/render/transcript.clj
src/seon/render/walk.clj
src/seon/render/web.clj
src/seon/schema.clj
src/seon/schema/datahike.clj
src/seon/schema/internal.cljc
src/seon/turn.clj
test/seon/cluster/turn_test.clj
test/seon/schema/datahike_test.clj
test/seon/schema_audit_test.clj
test/seon/schema_test.clj
```

The expanded production require command in the launch specification, plus
`seon.reconcile`, exits zero on the current working tree; evidence is
`tmp/bridge-step2-decision-load.log`. That is a namespace load, not test or
runtime behavior proof. Before a retirement commit, remaining work includes
converting the convenience-API test callers, removing the stale private
`compiled-attribute` Var reference in the navigation-cost test, preserving
the old bridge's complete refusal grammar and tuple-child checks, updating
the independent parity baseline for explained population drift, and fixing
the canonical-fixture refusals recorded below. The optional-member test
currently proves canonical-row construction, not an executed publication.
The required-member refusal and the actual publication still need executed
canonical evidence. Review the newly converted boundary fallbacks before
landing; they do not authorize leaf generation acquisition.

These paths pass `git diff --check`. No worktree, cold gate, background JVM,
second concurrent lane JVM, default mutation or hook re-enable was used.

## Historical initial grounding and held-caller checkpoint

Baseline HEAD was `dc1efaf3c`; `fb4dfee98` is its parent. Read the step-2
launch specification, binding Malli-native bridge PRD, accepted dissolution
review, step-1 landing note, and the four schema owners end to end. Read the
five required skills and the context-generation roadmap entry. The supplied
AGENTS sections and lane rules govern this bounded assignment.

The refreshed executable namespace census still has 37 paths: the raw
walker, 22 production consumers and 14 test consumers. Required held
callers remain in `src/seon/cluster/source.clj:524` and
`src/seon/fn.clj:556,765,1632,1690`. They are not edited. The cluster's
activation requirements at `src/seon/cluster.clj:1258,1265` are outside
the stated population/refresh region; any edit still requires a fresh
path-status check. No helper can be retired until all callers convert
in one commit.

## Inherited live boundary

Read-only `bin/seon status` reports default PID 24777 alive. MCP
`runtime_status` at 2026-09-20 06:29:34 UTC returns a diagnostic instead
of health: `seon.problems/problems` refuses its return at
`[:seon.problems/error-signatures 0 :seon.error/at]`, because the signature
lacks required `:seon.error/at`. This is the existing status refusal class
tracked in [the runtime-status issue](../../../seon/issues/runtime-status-refuses-error-occurrence-count.md),
with a different offending member; no causal attribution is claimed.
Unlike the earlier opaque MCP projection failure, the current envelope
preserves the exception message. No default mutation, reload, adoption,
stop, restart or refork was performed.

A read-only JVM probe of a two-map conjunction returned
`{:type :and, :entries nil, :children [:map :map]}` in 1 ms. This confirms
the vendored seam: `m/entries` does not aggregate conjunctions. It is not
an armed fixture or a post-change live proof.

## Dependency and construction grounding

Read Malli's Schema/RefSchema protocols, registry implementation, child and
entry parsing, walk callbacks, reconstruction, conjunction, reference scopes,
and public navigation/cache APIs at the pinned source ranges in the spec.
`m/-set-children` reuses unchanged nodes; `m/parent` is the constructor,
not an inheritance relation. Explicit and named references have different
walk controls. Literal children are not necessarily schemas.

Existing construction boundaries: config composites precede complete
compilation (`schema/edn.clj:66`); `compose-projection-data` computes shape
indexes as pure data (`schema.clj:2170`); incomplete canonical-row input
retains missing-reference evidence (`schema.clj:3420`). The structural
registry is currently private in `schema.clj:1228`. Admission's existing
forms-only bridge requests must gain the complete construction registry.
The initial source read preceded production edits. Provisional compiled
composition and storage folds now live in the existing owners; the old
bridge remains active until construction/parity proof and caller conversion.

## Independent old-bridge capture

The first fast snapshot was taken at advancing HEAD
`3267839b0d6b665e784acc280745475b7e3220f9`. The four schema owners have no
diff from `dc1efaf3c` at that commit. Request `f04fd216d8bd` records program
digest `7ee71727d4ae594018748fc69a8f6c1c06a85f871cb94a846f17f428ee2bcb58`.
It armed 1,398 contracts (1,395 program-armable), then recorded 11 executed,
0 unchanged, 28 assertions, 1 failure and 3 errors. This is not green.
The full writer exception expanded to over 55,000 tool-output tokens;
subsequent execution output is redirected to project-local logs so the
evidence can be read selectively. This is unreadable diagnostic output,
not a reason to change any presentation bound in this slice.

The baseline capture itself completed on `support/with-database` and its
explicitly handed projection: 3,241 forms, 969 core attributes, 42 storable
property attributes, 1,011 selected attributes and 1,011 ordered native
declarations. Input SHA-256:
`bbe66c60af03d83b2d258c4237dae407ced14e579de0e5b58a4cd5ee1e6b8ab9`.
The mechanically written `test/seon/schema/datahike_parity.edn` retains
the full forms and outputs, not a sample or intersection.

Capture command:

```sh
bin/test-fast --paths test/seon/schema/datahike_test.clj \
  docs/prds/steward-platform/research/bridge-step2-walker-2026-09-21.md \
  -- seon.schema.datahike-test
```

The initial `canonical-population-native-parity` test derived `forms` from
`schema/handed-projection` inside `support/with-database`; called old
`schema.form/database-attributes`, filtered old
`schema.form/property-attributes` through `storable-attribute-in?`, then
called old `database-attributes-in` and `malli->datahike-schema-in`. It
wrote those complete values with unlimited EDN print length/depth and
`schema/sha-256` of UTF-8 `schema/canonical-data-string forms`. The capture
writer has been removed from the regression; it now reads the frozen data,
checks input identity, and compares both key-set differences and every
native map plus ordering. Only capture provenance was corrected afterward
to the actual snapshot HEAD; no expected declaration was regenerated.

Observed baseline refusal boundaries include `seon.program/declaration-row`
returning a row missing `:seon.ns/name` from `seon.turn:1270`, and the
old `registered-shape-round-trips-through-datahike` fixture submitting an
identity-less unowned row. These are observations before changing production
behavior, not assertions that another checkout edit caused them.

First provisional compiled run (`tmp/bridge-step2-compiled-parity.log`)
did not execute tests: new `entity-maps` used bare `:set` in its input
contract, and canonical arming refused `:malli.core/child-error`. Corrected
to a set of compiled schemas. No parity claim derives from that run.

## Construction iterations (not landing proof)

`tmp/bridge-step2-construction.log`: 66 tests, 5,514 assertions, one failure,
four errors. Config syntax inspection and scoped entity composition passed.
The provisional storage fold overflowed while following recursive refs;
the next version tracks registry scope plus ref identity, following Malli's
own `-identify-ref-schema` distinction (`core.cljc:1943`). This snapshot
preceded that correction. Its result recording refused, so these are printed
execution counts, not durable recorded results.

`tmp/bridge-step2-components.log`: 67 tests, 5,563 assertions, seven failures,
three errors. Component preparation now uses structural Malli nodes and
reconstruction, including locally registered recursive declarations;
the new local-recursion/literal-payload/idempotence regression passed.
The compiled parity fold completed: every one of the original 1,011 native
declarations matched. The sole map difference was newly added
`:seon.sci.eval/row-member`; HEAD `2a59e5e11` added its declaration and error
entity, making 3,243 forms and 1,012 selected attributes. Input digest changed
to `9064a6781019325a4d697b05e03f6c30b8564875313f2074e7087b719e742e8a`.
This is input drift, not a passed whole-population assertion. A subsequent
one-time capture uses the still-active old bridge to obtain the complete
new expected output independently; it does not use the compiled fold.

Both runs failed recording through `seon.blob/with-publication!`, which
reported undeclared `#{:seon.db.write/validation-refusal
:seon.test/execution-error}`. This is the existing class in
[test refusal observations](../../../seon/issues/test-refusal-observations-overflow-in-projection-acquisition.md).
No recorder, blob, error, or held publication path was changed to bypass it.

Shape indexes are being moved to materialization from retained compiled
roots. Pure composition keeps rows and dependency facts and carries no
Schema objects; it no longer reinterprets forms to discover optional-only
entity entries. This is the specification's materialization option, not a
new persisted metadata carrier. The serialization/materialization proof is
pending in `tmp/bridge-step2-shapes.log`.

That run completed 68 tests with 5,568 assertions, seven failures and three
errors; result recording remained unavailable. The pure-data composition
and optional-only index materialization regression passed. Its old-bridge
recapture is request `a5d2fbb2eca1`, snapshot HEAD
`2a59e5e11788fd249e675949115dc189d95fe388`, program digest
`b097548ee1583ab170f2becd9573907cba951fa161b5949a529ef1ba42c2d09e`.
The frozen parity file now contains that independently captured population.
Compared with the first capture, the exact input change is two new
`:seon.sci.eval` declarations (`row-member`, `row-acquisition-error`) plus
the new facet in `:seon.db/error-result`; no declaration was removed.
The old bridge added only the one native string attribute `row-member`.
No expected value was produced by the compiled fold. The temporary capture
writer was removed again before the next run.

The final construction iteration includes scoped recursive entity comparison
and the compiled error-inheritance inspection. It remains a construction
checkpoint, not the complete step-2 acceptance suite.

## Held construction boundary

The explicit launch hold still governs. An intermediate `git status --short`
reported clean bytes for `src/seon/fn.clj` and `src/seon/cluster/source.clj`;
the final census now reports both modified by their owner. No release was
received, and neither path was edited here.

`src/seon/fn.clj:2827` (`desired-rows`) selects changed declarations and at
`:2836` passes only those forms to `schema/canonical-schema-rows`.
`src/seon/fn.clj:1243` acquires forms, not the retained construction registry;
`:3233` also supplies those forms to `program/shapes-in`. The schema-row
helper currently catches missing references and falls back to a forms-only
map (`schema.clj:3493`). Removing that fallback requires converting this
held caller to carry the complete construction generation while preserving
the selected rows and their external-reference evidence. Syntax placeholders
cannot establish storable property declarations. No replacement leaf
population acquisition or second registry was introduced to bypass the hold.

The specification orders that partial-construction proof before broad caller
conversion. Thus that conversion and the helper retirement remain dependent
on this held path. `src/seon/cluster/source.clj:524` independently still uses
the retiring property helper in result recording. The remaining direct
namespace references are deliberately intact; there is no retirement commit
and no claim of completed step 2. The held callers have not been edited.

## Verified construction checkpoint

`tmp/bridge-step2-construction-final.log` executed 68 tests / 5,569 assertions,
one failure / three errors. The complete parity regression passed over 3,243
forms and 1,012 attributes with **zero key differences and zero per-key native
map differences**, including ordering, core selection and storable-property
selection. Config syntax/no-load, local recursive component widening,
idempotence/literal preservation, scoped recursive entity comparison,
pure-data materialization and retained-generation regressions passed.
The remaining failure was the old assertion that acquisition must read
resource files; it is replaced by direct compiled-navigation operation
counts in the last bridge-only iteration. The three baseline fixture errors
remain tracked in [the fixture class](../../../seon/issues/fixtures-that-ignore-a-refused-transaction-read-absence-as-behaviour.md).
Recording still refused through `seon.blob/with-publication!`; executed counts
are printed evidence, durable executed/unchanged tally is unavailable.

The existing generation test measured 4,715 named providers with maximum
one invocation, 4,863 sealed entries, and warmed acquisition at zero compiles
and zero copied registry entries. Those counts include synthetic generation
subjects; the fixture itself carried 3,243 schemas and 1,467 function contracts.

Frozen parity-file SHA-256:
`70341704c2a651205fd61473934f6777138d54c5b472fbed30b92e06432d71d3`.
Final construction log SHA-256:
`5efca10b7501de431c6ac7ae161676b52fd5256c4bcb1c1c041bf2065cd645ec`.

The refreshed raw-namespace census is **36 executable paths**. Only config
preparation and selected construction navigation have converted; the old
production bridge is still active and `schema/form.cljc` still exists.
This checkpoint does not claim the retirement/deletion budget, complete
caller conversion, the full requested namespace selection, cold/platform
proof or live post-adoption proof. No commit, worktree, default mutation,
cold gate, nested gate, second lane JVM or hook re-enable was performed.

Owned changed paths at this checkpoint:

- `src/seon/schema.clj`
- `src/seon/schema/internal.cljc`
- `src/seon/schema/datahike.clj`
- `src/seon/schema/edn.clj`
- `test/seon/schema_test.clj`
- `test/seon/schema/datahike_test.clj`
- `test/seon/schema/datahike_parity.edn`
- `test/seon/schema/edn_test.clj`
- `docs/seon/issues/runtime-status-refuses-error-occurrence-count.md`
- `docs/seon/issues/fixtures-that-ignore-a-refused-transaction-read-absence-as-behaviour.md`
- this note.

`git diff --check` passes on these owned tracked paths. The Markdown hook
reports a foreign stale Datahike pin in
`docs/prds/steward-platform/plan/wave-3a-task-family-spec-2026-09-21.md`;
the design lane's document was not edited.

The final bridge-only request, `tmp/bridge-step2-navigation-counts.log`,
waited at least 960 seconds for the two shared JVM slots. At the explicit
held-caller stop, only this lane's queued launcher PID 83852 was sent TERM;
its trap removed `tmp/test-runs/run.JMkS6t`, and both launcher 83852 and
child 84230 were verified absent. Exit 143. **No JVM or tests executed for
this last request.** The newly substituted
`compiled-storage-navigation-reuses-retained-roots` cost regression is
therefore unverified; no compiled-storage zero-cost claim is made. Other
lanes' processes were untouched. The temporary recapture file was removed
after its data and provenance were preserved in the frozen parity file.

Current, uncommitted source diff: 345 added / 77 deleted physical lines across
the four schema owners. Tests: 176 added / 36 deleted lines, excluding the
mechanically captured parity data. These are checkpoint counts, not the
retirement budget: the 216-line raw walker remains. No public helper has
been deleted, and no partial caller/retirement commit was made.

Pending measurement command (same source inputs, bridge namespace only):

```sh
bin/test-fast --paths \
  src/seon/schema.clj src/seon/schema/internal.cljc \
  src/seon/schema/datahike.clj src/seon/schema/edn.clj \
  test/seon/schema_test.clj test/seon/schema/datahike_test.clj \
  test/seon/schema/datahike_parity.edn test/seon/schema/edn_test.clj \
  docs/prds/steward-platform/research/bridge-step2-walker-2026-09-21.md \
  docs/seon/issues/runtime-status-refuses-error-occurrence-count.md \
  docs/seon/issues/fixtures-that-ignore-a-refused-transaction-read-absence-as-behaviour.md \
  -- seon.schema.datahike-test
```
