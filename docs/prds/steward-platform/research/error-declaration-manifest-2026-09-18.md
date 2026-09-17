---
type: research
status: open
created: 2026-09-18
tags: [errors, schema, manifest]
---

# Error declaration manifest — additive slice 1

**Additive implementation verified: 315 new keys across 84 resources; old
declarations unchanged.** Fast result: **129 tests, 3,816 assertions, 0 failures,
0 errors**. Five path-limited commits are preserved on
`error-declaration-manifest-slice1` (implementation tip `22dbfc035`). Shared-branch
integration remains open because `resources/seon/schemas/seon.fn.edn` carries
another lane's uncommitted edit. **RESET NEEDED** for the coordinated batch;
cold/platform and reset-boundary proof remain with the orchestrator.

**Initial dependency record (superseded by the owner’s additive scope below).**
RESET NEEDED for the coordinated batch. No production
resource or source was changed. The named dependency is coordinating same-key
schema replacements with the constructors that still return the old shape.
This is independent of another lane's uncommitted work.

## Evidence and exact boundary

The assignment requires the literal §2.5 manifest, unchanged constructors,
coexistence of old declarations until reset, and green armed schema/error/db
regressions. Those conditions conflict at existing canonical schema identities.
The [binding manifest](../plan/error-entities-prd-2026-09-17.md:148) changes
`:seon.error/value` from the old kind/message map to `:seon.error/base`.
The base requires `:seon.error/at`, `/layer`, and `/operation`.

`seon.error/diagnostic` promises `:seon.error/value` but constructs the old
shape ([src/seon/error.clj](../../../../src/seon/error.clj:338));
`seon.error/value` likewise returns only kind, message and an evidence pointer
([src/seon/error.clj](../../../../src/seon/error.clj:762)). The existing
`diagnostic-construction-is-evidence-complete` regression explicitly validates
that constructor result against the same key
([test/seon/error_test.clj](../../../../test/seon/error_test.clj:225)).
Changing the schema while retaining that constructor makes its promised output
invalid. Adding a duplicate key cannot preserve both definitions: the resource
merger refuses duplicate declarations
([src/seon/schema/edn.clj](../../../../src/seon/schema/edn.clj:306)).
A reset does not repair source-level constructor/contract disagreement.

The retained `:my.note/not-found-error` and
`:seon.error.occurrence/occurrence` contracts also acquire required base members
at their existing identities. This is not merely adding optional declarations.
Weakening the new base, accepting the old shape as an alternative under the
new base, or changing old regression expectations would conceal the mismatch.

## Measured declaration comparison

Run the committed [static comparison script](error-declaration-manifest-probe-2026-09-18.clj)
with `bb` from the repository root. It reads every literal resource fragment
with the native EDN reader and compares it with committed resource bytes,
excluding foreign working-tree edits.

Measured at source revision `49a26d725825fa020e03ed2ddd233d685b797188`:

| Observation | Count |
|---|---:|
| Literal resource fragments | 93 |
| Literal declarations | 643 |
| New keys | 316 |
| Changed existing keys | 280 |
| Unchanged existing keys | 47 |
| Namespace-placement errors | 0 |

These are syntax/key-comparison measurements, not compilation or storage proof.
The script prints exact before/manifest forms for representative collisions.
No resource → keys manifest has landed; the PRD remains the literal authority.
No retired declaration was removed or marked migrated.

The manifest still includes `:seon.error/result`. Owner
[§1q](../plan/program-facts-are-the-runtime-prd-2026-09-17.md:504)
explicitly rejects this generic contract spelling. That point is already
settled: implementation must omit it and consumers enumerate their facets.
It is not another question for the owner.

## Live observation

`bin/seon status` reported default alive, pid 80593. MCP runtime status answered
and reported 3 error signatures, 6 errored evaluations (the tool uses its older
field spelling), and 11 failed tests. These are inherited observations, not
attributed to this slice. Adoption freshness was not established.

One read-only JVM evaluation, with explicit root `/Users/sean/src/seon` and
cluster `default`, took **6 ms**. It changed no definitions, facts or lifecycle.
The probe checked a necessary condition of the proposed base, not the full
future manifest:

```clojure
(let [failure (try (seon.id/valid?)
                   (catch Throwable t (ex-data t)))
      base [:map
            [:seon.error/at inst?]
            [:seon.error/layer :qualified-keyword]
            [:seon.error/operation :qualified-symbol]]
      explanation (malli.core/explain base failure)]
  {:manifest-probe/keys (vec (sort (keys failure)))
   :manifest-probe/current-error? (seon.error/error? failure)
   :manifest-probe/base-valid? (malli.core/validate base failure)
   :manifest-probe/missing (mapv :path (:errors explanation))
   :manifest-probe/data (:seon.error/data failure)})
```

Observed: current error predicate **true**, proposed necessary base shape
**false**; missing paths were exactly `/at`, `/layer`, `/operation`.
The actual top-level keys were `:seon.error/data`, `/kind`, `/message`, and
`:seon.instrument/contract-violated`. Nested data recorded function
`seon.id/valid?`, actual arity 0, arm `:input`, cause
`:malli.core/invalid-arity`, and arglists `([length id])`.
The full tool result retained digest
`bcf248a809e6f7231df4cc77d10207851c9e69d476979275f991b1377b57d6de`,
reported size 5373 bytes. No future arity-facet acceptance is claimed.

## Checker, measurement and test status

No checker rules changed. Still owed: complete alias/conjunction expansion;
base-extension and non-base required-member admission; non-storable member
refusal; non-unique observations; optional coherent render properties; complete
owned-child and whole-parent ordering/count/omission invariants; overlapping
facet and base-only proof. Existing raw map inspection still selects one
literal map in an `:and` ([src/seon/schema/form.cljc](../../../../src/seon/schema/form.cljc:25)).

Fixture projection build time before/after: **not measured**. Fast tally:
**not run**, stopped at the constructor/contract sequencing dependency before
production edits. No green, storage, generated-value, armed-regression, cold,
platform, reset or browser proof is claimed. The static reader probe passed
with positive subject counts above.

## What slice 2 consumes

The intended interfaces remain the PRD's `:seon.error/base`,
`:seon.instrument/contract-error`, `/arity-error`, `/undeclared-error`, and
`/refusal-result`, with owned projection/location/explanation/arity-bound
children and complete declaration projection. **None is supplied by this
landing.** The wrapper owner must not treat this note as a released prerequisite.
An actual arity refusal needs the base observations and declared bounds from
the invocation seam; the current refusal cannot validate unchanged.

## Required sequencing decision

**Recommendation:** permit slice 1 to add genuinely new declarations and
expansion/checkers while explicitly deferring the 280 same-key replacements
that need consumers to their coordinated reset batch. This preserves current
contracts and permits focused green proofs, but changes the assignment's
"literal manifest exactly" delivery boundary. Alternatively, authorize the
constructor/recorder changes with the replacements, expanding this bounded
slice. The exact affected population is derived by the committed script.

The [issue](../../../seon/issues/archive/error-manifest-replacements-require-constructor-coordination.md)
records this dependency. No other lane was contacted or operated. The hook
reported syntax errors in the stage-1 `test/seon/test/selection_test.clj`
during an attempted clarification; that file was preserved and is not the
reason for this stop. No worktree, scratch root, test JVM or background shell
was created. No default mutation/publication/reset was requested.

Grounding reviewed the supplied AGENTS instructions, schema skills, modeling
guide, owner §§1j–1q, review findings, and the binding declaration/sequencing
sections. The whole PRD's detailed per-resource implementation review remains
owed; this note does not claim an end-to-end implementation review completed.


## Additive scope accepted — 2026-09-18

Owner resolution `989a2d4b2` changes slice 1 to genuinely new declarations and
expansion/checkers only. The measured 316 additions become **315** after
omitting `:seon.error/result` under §1q. The 280 same-key replacements remain
unchanged until their constructor groups land schema, constructors and
recorder changes together. All groups join the one reset. The preceding
sections record the initial dependency and are not the implementation tally.

The isolated implementation baseline is
`707508b6fe3b701fa761a4a200c9253ee843d987`. New declarations use their exact
namespace resource. Proposed `base-ai`, `base-html`, `facet-ai`, and `facet-html`
functions do not exist yet, so their properties are absent under owner §1p;
the compositional render slice supplies callable pairs in its publication.
No placeholder pair or alternate renderer was added. Existing pairs are unchanged.

### Dependency ledger

- Malli conjunction and default schema grammar:
  `reference-code/malli/src/malli/core.cljc`; its generator conjunction chooses
  the first child's generator (`reference-code/malli/src/malli/generator.cljc`).
  The literal whole-parent predicate generators are propagated to their compiled
  conjunction; an explicitly declared parent generator takes precedence.
- Namespace placement and declaration merge: `src/seon/schema/edn.clj:259`.
- Structural expansion: `src/seon/schema/form.cljc`; canonical required-member
  and shape projection: `src/seon/schema.clj` and `schema/internal.cljc`.
- Storage grammar: `src/seon/schema/datahike.clj`, supplied-projection
  `storable-attribute-in?`; no open map is substituted for a stored leaf.
- Complete owning values: `src/seon/db.clj`, final transaction-report validation;
  Datahike `reference-code/datahike/src/datahike/db/transaction.cljc` stores
  cardinality-many without order. Children therefore carry checked ordinals.
- Canonical fixture and instrumentation: `test/seon/test_support.clj`,
  `src/seon/test/arm.clj`, through `bin/test-fast`.

### Manifest additions (dated, derived from the literal PRD and baseline)

Every key below is additive; every prior resource key retains its definition.
The committed comparison script accepts a baseline revision argument and emits
this inventory. Render properties are omitted as described above.

| Resource | New keys |
|---|---|
| ` my.background.edn ` |  `:my.background/authored-form`, `:my.background/error`  |
| ` my.edit.edn ` |  `:my.edit/edit-observation`, `:my.edit/error`, `:my.edit/error-path`  |
| ` my.fs.edn ` |  `:my.fs/error`, `:my.fs/error-path`, `:my.fs/io-observation`  |
| ` my.message.edn ` |  `:my.message/error`, `:my.message/error-request`  |
| ` my.note.edn ` |  `:my.note/error-about`  |
| ` my.plan.edn ` |  `:my.plan/constraint-observation`, `:my.plan/error`, `:my.plan/error-request`  |
| ` my.shell.edn ` |  `:my.shell/error`, `:my.shell/error-command`, `:my.shell/execution-observation`  |
| ` my.turn.edn ` |  `:my.turn/error`, `:my.turn/error-proposal`  |
| ` my.web.edn ` |  `:my.web/error-request`  |
| ` seon.agent.edn ` |  `:seon.agent/error`, `:seon.agent/error-agent-id`, `:seon.agent/failure`  |
| ` seon.agent.graph.edn ` |  `:seon.agent.graph/control-observation`, `:seon.agent.graph/error`, `:seon.agent.graph/proc`  |
| ` seon.ai.edn ` |  `:seon.ai/error-provider`, `:seon.ai/request-error`, `:seon.ai/request-observation`  |
| ` seon.artifact.edn ` |  `:seon.artifact/error`, `:seon.artifact/error-identity`, `:seon.artifact/error-transition`  |
| ` seon.boot.edn ` |  `:seon.boot/error`, `:seon.boot/failed-phase`, `:seon.boot/process-root`  |
| ` seon.bootstrap.edn ` |  `:seon.bootstrap/error`, `:seon.bootstrap/error-member`, `:seon.bootstrap/prefix-observation`  |
| ` seon.cluster.edn ` |  `:seon.cluster/error`, `:seon.cluster/error-cluster-name`, `:seon.cluster/failure`, `:seon.cluster/graph-operation`  |
| ` seon.cluster.prompt.edn ` |  `:seon.cluster.prompt/derivation-observation`, `:seon.cluster.prompt/error`, `:seon.cluster.prompt/error-agent-id`  |
| ` seon.cluster.registry.edn ` |  `:seon.cluster.registry/error`, `:seon.cluster.registry/registry-observation`, `:seon.cluster.registry/target-branch`  |
| ` seon.cluster.reply.edn ` |  `:seon.cluster.reply/authored-reply`, `:seon.cluster.reply/error`  |
| ` seon.cluster.source.edn ` |  `:seon.cluster.source/error`, `:seon.cluster.source/error-source`, `:seon.cluster.source/publication-observation`  |
| ` seon.cluster.store.edn ` |  `:seon.cluster.store/acquisition-observation`, `:seon.cluster.store/error`, `:seon.cluster.store/error-root`  |
| ` seon.cluster.wake.edn ` |  `:seon.cluster.wake/error`, `:seon.cluster.wake/error-recipient`, `:seon.cluster.wake/offer-observation`  |
| ` seon.config.edn ` |  `:seon.config/error`, `:seon.config/error-key`, `:seon.config/rule-error`  |
| ` seon.db.availability.edn ` |  `:seon.db.availability/connection`, `:seon.db.availability/error`, `:seon.db.availability/failed-observation`  |
| ` seon.db.read.edn ` |  `:seon.db.read/error`, `:seon.db.read/target`  |
| ` seon.db.read.target.edn ` |  `:seon.db.read.target/arguments`, `:seon.db.read.target/attribute`, `:seon.db.read.target/entity`, `:seon.db.read.target/entity-projection`, `:seon.db.read.target/index`, `:seon.db.read.target/index-request`, `:seon.db.read.target/query`, `:seon.db.read.target/selector`  |
| ` seon.db.write.attempt.edn ` |  `:seon.db.write.attempt/bound-ms`, `:seon.db.write.attempt/completion-unavailable`, `:seon.db.write.attempt/entity`, `:seon.db.write.attempt/observed-at`, `:seon.db.write.attempt/operations`, `:seon.db.write.attempt/rejection`, `:seon.db.write.attempt/request-id`, `:seon.db.write.attempt/submitted-at`, `:seon.db.write.attempt/waited-ms`  |
| ` seon.db.write.edn ` |  `:seon.db.write/attempt`, `:seon.db.write/error`  |
| ` seon.dev.mcp.edn ` |  `:seon.dev.mcp/error`, `:seon.dev.mcp/error-cluster`, `:seon.dev.mcp/request-observation`  |
| ` seon.effect.edn ` |  `:seon.effect/capability-symbol`, `:seon.effect/error`, `:seon.effect/execution-observation`, `:seon.effect/request-identity`  |
| ` seon.env.edn ` |  `:seon.env/error`, `:seon.env/error-member`, `:seon.env/member-expectation`  |
| ` seon.error.basis.edn ` |  `:seon.error.basis/branch`, `:seon.error.basis/commit`, `:seon.error.basis/entity`, `:seon.error.basis/store`, `:seon.error.basis/t`  |
| ` seon.error.disposition.edn ` |  `:seon.error.disposition/action`, `:seon.error.disposition/evidence`, `:seon.error.disposition/graph-id`, `:seon.error.disposition/observation`, `:seon.error.disposition/observer`  |
| ` seon.error.edn ` |  `:seon.error/base`, `:seon.error/basis`, `:seon.error/cause`, `:seon.error/evidence-items`, `:seon.error/evidence-unavailable`, `:seon.error/expected-key`, `:seon.error/expected-shape`, `:seon.error/fix`, `:seon.error/layer`, `:seon.error/location`, `:seon.error/member`, `:seon.error/offending-projection`, `:seon.error/operation`  |
| ` seon.error.evidence.edn ` |  `:seon.error.evidence/attribute`, `:seon.error.evidence/entity`, `:seon.error.evidence/projection`, `:seon.error.evidence/value`  |
| ` seon.error.key.edn ` |  `:seon.error.key/bound-bytes`, `:seon.error.key/capped?`, `:seon.error.key/entity`, `:seon.error.key/projection`, `:seon.error.key/scalar`  |
| ` seon.error.location.edn ` |  `:seon.error.location/entity`, `:seon.error.location/length`, `:seon.error.location/omission`, `:seon.error.location/segments`  |
| ` seon.error.location.segment.edn ` |  `:seon.error.location.segment/entity`, `:seon.error.location.segment/key`, `:seon.error.location.segment/ordinal`  |
| ` seon.error.occurrence.edn ` |  `:seon.error.occurrence/cluster`, `:seon.error.occurrence/evaluation`  |
| ` seon.error.omission.edn ` |  `:seon.error.omission/bound`, `:seon.error.omission/bound-key`, `:seon.error.omission/entity`, `:seon.error.omission/omitted-count`, `:seon.error.omission/retained-count`, `:seon.error.omission/unavailable-count-reason`  |
| ` seon.error.projection.edn ` |  `:seon.error.projection/bound-bytes`, `:seon.error.projection/entity`, `:seon.error.projection/missing-member`, `:seon.error.projection/omission`  |
| ` seon.eval.drive.edn ` |  `:seon.eval.drive/driver-observation`, `:seon.eval.drive/error`, `:seon.eval.drive/error-evaluation`  |
| ` seon.failure.edn ` |  `:seon.failure/entity`, `:seon.failure/fault`, `:seon.failure/requested-tx`, `:seon.failure/stopped-tx`  |
| ` seon.flow.edn ` |  `:seon.flow/control-observation`, `:seon.flow/error`, `:seon.flow/error-graph`  |
| ` seon.fn.arity.edn ` |  `:seon.fn.arity/error-facet-digest`, `:seon.fn.arity/error-facets`  |
| ` seon.fn.binding.edn ` |  `:seon.fn.binding/binding-projection`, `:seon.fn.binding/error`, `:seon.fn.binding/source-location`  |
| ` seon.fn.contract.finding.edn ` |  `:seon.fn.contract.finding/error-facet-analysis-unavailable`, `:seon.fn.contract.finding/undeclared-error-facet`  |
| ` seon.fn.edn ` |  `:seon.fn/analysis-phase`, `:seon.fn/error`, `:seon.fn/error-facets`, `:seon.fn/error-subject`  |
| ` seon.instrument.arity.edn ` |  `:seon.instrument.arity/bounds`, `:seon.instrument.arity/max`, `:seon.instrument.arity/min`, `:seon.instrument.arity/ordinal`  |
| ` seon.instrument.edn ` |  `:seon.instrument/actual-facet-count`, `:seon.instrument/actual-facets`, `:seon.instrument/arity`, `:seon.instrument/arity-error`, `:seon.instrument/check`, `:seon.instrument/contract-error`, `:seon.instrument/declared-arities`, `:seon.instrument/declared-arity-count`, `:seon.instrument/declared-facet-count`, `:seon.instrument/declared-facet-digest`, `:seon.instrument/declared-facets`, `:seon.instrument/explanations`, `:seon.instrument/refusal-result`, `:seon.instrument/registration-error`, `:seon.instrument/registration-observation`, `:seon.instrument/returned-error`, `:seon.instrument/undeclared-error`  |
| ` seon.instrument.explanation.edn ` |  `:seon.instrument.explanation/actual`, `:seon.instrument.explanation/entity`, `:seon.instrument.explanation/expected-shape`, `:seon.instrument.explanation/humanization-unavailable`, `:seon.instrument.explanation/humanized`, `:seon.instrument.explanation/ordinal`, `:seon.instrument.explanation/problem-type`, `:seon.instrument.explanation/schema-location`, `:seon.instrument.explanation/value-location`  |
| ` seon.instrument.explanations.edn ` |  `:seon.instrument.explanations/count`, `:seon.instrument.explanations/entity`, `:seon.instrument.explanations/items`, `:seon.instrument.explanations/omission`  |
| ` seon.instrument.humanized.edn ` |  `:seon.instrument.humanized/entity`, `:seon.instrument.humanized/message-count`, `:seon.instrument.humanized/messages`, `:seon.instrument.humanized/omission`  |
| ` seon.instrument.humanized.message.edn ` |  `:seon.instrument.humanized.message/entity`, `:seon.instrument.humanized.message/location`, `:seon.instrument.humanized.message/ordinal`, `:seon.instrument.humanized.message/text`  |
| ` seon.message.edn ` |  `:seon.message/error`, `:seon.message/error-request`  |
| ` seon.operator.collect.edn ` |  `:seon.operator.collect/error`, `:seon.operator.collect/error-option`, `:seon.operator.collect/expected-option`  |
| ` seon.operator.edn ` |  `:seon.operator/error`, `:seon.operator/error-root`, `:seon.operator/operation-observation`  |
| ` seon.problems.edn ` |  `:seon.problems/detector-observation`, `:seon.problems/error`, `:seon.problems/error-detector`  |
| ` seon.program.edn ` |  `:seon.program/error`, `:seon.program/error-facet-arity`, `:seon.program/error-facet-result`, `:seon.program/error-subject`, `:seon.program/source-observation`  |
| ` seon.reconcile.edn ` |  `:seon.reconcile/constraint-observation`, `:seon.reconcile/error`, `:seon.reconcile/requested-identity`  |
| ` seon.render.data.edn ` |  `:seon.render.data/error`, `:seon.render.data/error-root`, `:seon.render.data/requested-location`  |
| ` seon.render.edn ` |  `:seon.render/error`, `:seon.render/input-projection`, `:seon.render/requested-output`  |
| ` seon.render.unknown.edn ` |  `:seon.render.unknown/call-projection`, `:seon.render.unknown/output`, `:seon.render.unknown/producer`, `:seon.render.unknown/refusal-projection`, `:seon.render.unknown/throwable`  |
| ` seon.render.value.edn ` |  `:seon.render.value/error`, `:seon.render.value/error-window`, `:seon.render.value/projection-observation`  |
| ` seon.render.walk.edn ` |  `:seon.render.walk/error`, `:seon.render.walk/error-subject`, `:seon.render.walk/step-observation`  |
| ` seon.render.web.edn ` |  `:seon.render.web/error`, `:seon.render.web/error-request`, `:seon.render.web/owner-observation`  |
| ` seon.schedule.edn ` |  `:seon.schedule/error`, `:seon.schedule/error-task`, `:seon.schedule/settlement-observation`  |
| ` seon.schema.datahike.edn ` |  `:seon.schema.datahike/declared-form`, `:seon.schema.datahike/error`, `:seon.schema.datahike/error-attribute`  |
| ` seon.schema.edn ` |  `:seon.schema/declaration-expectation`, `:seon.schema/error`, `:seon.schema/error-declaration`  |
| ` seon.schema.shape.edn ` |  `:seon.schema.shape/error`, `:seon.schema.shape/error-form`, `:seon.schema.shape/shape-expectation`  |
| ` seon.sci.admit.edn ` |  `:seon.sci.admit/bound-bytes`, `:seon.sci.admit/error`, `:seon.sci.admit/projection-observation`  |
| ` seon.sci.eval.edn ` |  `:seon.sci.eval/acquisition-error`, `:seon.sci.eval/acquisition-member`, `:seon.sci.eval/acquisition-observation`, `:seon.sci.eval/evaluation-error`, `:seon.sci.eval/evaluation-id`, `:seon.sci.eval/requested-program`, `:seon.sci.eval/source-projection`  |
| ` seon.sci.kernel.edn ` |  `:seon.sci.kernel/error`, `:seon.sci.kernel/guard-observation`  |
| ` seon.sci.reader.edn ` |  `:seon.sci.reader/error`, `:seon.sci.reader/reader-location`, `:seon.sci.reader/source-projection`  |
| ` seon.search.edn ` |  `:seon.search/error`, `:seon.search/error-resource`, `:seon.search/index-observation`  |
| ` seon.test.accretion.auto.edn ` |  `:seon.test.accretion.auto/capabilities`, `:seon.test.accretion.auto/case-count`, `:seon.test.accretion.auto/entity`, `:seon.test.accretion.auto/executed-count`, `:seon.test.accretion.auto/failure`, `:seon.test.accretion.auto/seed`, `:seon.test.accretion.auto/skip-reason`, `:seon.test.accretion.auto/status`  |
| ` seon.test.accretion.edn ` |  `:seon.test.accretion/error`, `:seon.test.accretion/error-advisories`, `:seon.test.accretion/error-arguments`, `:seon.test.accretion/error-auto-check`, `:seon.test.accretion/error-group-count`, `:seon.test.accretion/error-groups`  |
| ` seon.test.accretion.failure.edn ` |  `:seon.test.accretion.failure/entity`, `:seon.test.accretion.failure/evidence`, `:seon.test.accretion.failure/ordinal`, `:seon.test.accretion.failure/source`, `:seon.test.accretion.failure/test-symbol`  |
| ` seon.test.accretion.group.edn ` |  `:seon.test.accretion.group/count`, `:seon.test.accretion.group/entity`, `:seon.test.accretion.group/failures`, `:seon.test.accretion.group/ordinal`, `:seon.test.accretion.group/shape`  |
| ` seon.test.edn ` |  `:seon.test/error`, `:seon.test/error-test-symbol`, `:seon.test/execution-observation`  |
| ` seon.test.run.edn ` |  `:seon.test.run/error`, `:seon.test.run/error-request`  |
| ` seon.test.runner.edn ` |  `:seon.test.runner/error`, `:seon.test.runner/error-selection`, `:seon.test.runner/worker-observation`  |
| ` seon.turn.edn ` |  `:seon.turn/error`, `:seon.turn/error-turn-id`  |
| ` seon.turn.loop.edn ` |  `:seon.turn.loop/error`, `:seon.turn.loop/failed-step`  |

### Expansion and checker rules

`schema.form/map-entries` and `map-shape?` now accept the supplied forms and
resolve aliases, refs and every conjunctive map arm. They preserve authored
member order and reference edges, combine required members, and refuse
conflicting member definitions or optionalization of an inherited requirement.
Storage-attribute discovery, shape projection, required-member indexing and
pulled-form derivation consume that expansion. No second registry was added.

At declaration admission, a base extension must retain the base requirements
and a concrete facet must require at least one non-base domain member. A
boolean marker alone cannot supply that domain. Aliases and overlapping facets
remain legal. Every stored facet member and its complete owned-child closure
must pass the supplied projection's `storable-attribute-in?`; components must
name a complete child schema. A render pair is either absent or two qualified
function symbols in schema properties.

Pure predicates in `seon.error` enforce exclusive evidence alternatives,
contiguous unique ordinals, retained counts, explicit zero with absent children,
omission evidence, arity interval consistency/exclusion, read-target grammar and
operation agreement, and the declared config/graph/accretion constraints.
These are declaration predicates and generators only. Existing constructors,
callers, `error?`, recording and dispatch are unchanged.

### Deferred replacements and retirement inventory

**No existing declaration is retired or replaced in this slice.** Every old
key remains byte-equivalent as an EDN value. The PRD §3 inventory continues to
map each retired marker declaration to its replacement facet for the reset.
The 280 same-key replacements are all deferred to the PRD §6 constructor
namespace groups; their replacement is the literal §2.5 form at that same key.
The comparison script derives that partition from the recorded baseline.

In particular, the constructor groups still owe `:seon.error/value` and
occurrence replacement, `my.note/about-not-found` as an observation projection,
`seon.render/unknown` refusal/call cause/projections, and removal of uniqueness
from **retained** identity-bearing aliases. New observation attributes already
use non-unique value declarations. Existing boolean markers and old kind-based
contracts remain until those groups remove their consumers together.

### What slice 2 consumes now

The prerequisite interfaces are `:seon.error/base`,
`:seon.instrument/contract-error`, `/arity-error`, `/undeclared-error`, and
`/refusal-result`, with their new owned projection, arbitrary-key location,
explanation, humanization and arity-bound declarations. Canonical expansion
includes the inherited base and turn → agent requirements. Use the supplied
projection's validators and complete owned values; required-key candidates are
not proof of predicate-bearing facet membership.

Wrapper enforcement must supply actual boundary time/layer/operation, preserve
observed invocation count and function symbol, and obtain declared intervals
from the real function contract. The old raw wrong-arity refusal is **not** a
new-base value. The regression projects its actual observations into the new
arity shape; it does not fabricate an explanation or assert constructor migration.
Both-dial input refusal, host/SCI enforcement, independent output-facet checking,
per-arity body permissions and nonrecursive boundary refusal remain slice 2.
The old `/value` promise cannot be changed by the wrapper lane in isolation;
its replacement still belongs to the coordinated constructor groups.

### Verification boundaries and reset

**RESET NEEDED.** Default was not stopped, reset, reforked or manually adopted.
The isolated checkout excludes foreign uncommitted edits. The exact `--paths`
fast invocation refused before its JVM because that checkout has no published
program graph; its diagnostic requests orchestrator-only
`bin/test --prepare-head-base`. No preparation or cold gate was run. The owner's
plain-fast fallback was used inside the isolated checkout.

The baseline was advanced from `707508b6f` to committed `02cb1b2b7` after repeated
30-second program-indexing wait refusals. That commit supplies the declared
600-second bound; no environment override or foreign file repair was used.
The known class is [the write-bound issue](../../../seon/issues/the-thirty-second-write-bound-fails-program-publication-under-load.md).
The first timed-out JVM was stopped after its fixture retry reproduced the
failure; a subsequent run was stopped to acquire the committed fix. Those runs
are not green evidence. Cold/platform and reset-boundary runtime/render proof
remain the orchestrator's obligations.

The configured edit hook queued one isolated-worktree `schema.clj` edit
(publication `db0b1137-7cef-4492-ad65-9ec5ac70be83`). Its recorded result refused
in `init` preflight with `:seon.operator.subprocess/deadline-exceeded`; no
successful adoption is claimed. The lane did not run a default publication or
lifecycle command to work around it.

### Resource-group commits

Implementation is committed on local branch `error-declaration-manifest-slice1`,
from the isolated `02cb1b2b7` baseline, in this dependency order:

| Commit | Owned group |
|---|---|
| `3b84644ef` | Base, owned error/instrument evidence, expansion, checkers and pure predicate/generator Vars (20 paths) |
| `28d471222` | Database, schema, storage and program declarations (14 paths) |
| `fdaf93155` | Runtime declarations and observation attributes (45 paths) |
| `acee1e21f` | Agent-facing declarations and canonical regressions (11 paths) |
| `22dbfc035` | Correct the new repeated-observation regression to read unindexed datoms through AEVT (1 path) |

Each commit used explicit paths. No `reference-code` symlink/submodule changes
were committed. Integration must preserve the foreign uncommitted
`resources/seon/schemas/seon.fn.edn` `:seon.fn/gate-request` addition; this lane's
four additions to that resource remain in its committed isolated snapshot.
No other lane was contacted or operated.


The large-value proof reads unindexed observation attributes through AEVT,
which Datahike maintains for every datom; AVET is conditional on indexing
(`reference-code/datahike/src/datahike/db/transaction.cljc:459`). The first
version's AVET assertion correctly failed after the write succeeded; it was
fixed in `22dbfc035`, without adding index/uniqueness to the declaration.


### Projection cost and storage measurements

The recurring `declaration-manifest-projection-build-cost` regression rebuilds a
schema-only projection from the canonical fixture's supplied forms in a warm
JVM. These are single samples, not a statistically controlled benchmark:

| Sample | Schema count | Rebuild milliseconds |
|---|---:|---:|
| Before, `707508b6f` without additions | 2,886 | 519.406833 |
| After, additive implementation `22dbfc035` | 3,201 | 1,185.930083 |
| Delta | +315 | +666.523250 |

The fixture baseline also acquired the committed write-bound fix before the
final run. Earlier candidate samples ranged from 449.453917 to 1,690.439125 ms
under changing machine load; no speedup or stable slowdown factor is claimed.

Positive storage evidence: 64 base/facet declarations (base included), 83
stored declarations across their owned closure plus failure/disposition
observations. Their stored members pass the actual Datahike bridge on the
fixture projection; whole-parent generators validate under that projection.
The large-value transaction reports **2,018 distinct entity IDs in tx-data
(including the transaction), 7,044 datoms, 1,001 path segments, two explanation
rows, and two separate errors observing the same agent token**. A subsequent
child-only ordinal violation refuses, and the database basis stays unchanged.
Root paths record zero with absent children; nil and unqualified missing keys
are represented as declared key observations.

The read+turn value satisfies read, turn and inherited agent facets. A base-only
value validates and has an empty matched-facet set. Removing the turn's required
agent observation refuses with `[:seon.agent/error-agent-id]` in the explanation.
The real armed wrong-arity probe supplies the observed function and count; its
new arity value validates, while admitted counts and contradictory bounds/counts
refuse. Retained-key replacements and raw constructor migration remain owed to
the constructor groups as listed above.


### Final fast tally and cleanup

**129 tests, 3,816 assertions, 0 failures, 0 errors; exit 0.** The canonical
fixture, real projection and armed contracts ran all three requested namespaces.
[Exact command, overlay refusal, static audit and full green output](error-declaration-manifest-fast-2026-09-18.txt)
are retained. Earlier complete attempts were 124/1,462 with 11 failures in the
old arbitrary-reference fixture (corrected to isolate reference grammar),
129/3,795 with one existing 5 ms query-budget failure (5.198 ms sample), and
129/3,816 with the new unindexed-AVET assertion failure fixed by `22dbfc035`.
No threshold was relaxed by this lane. Final owned Clojure lint had no errors;
`git diff --check` passed.

The disposable worktree and its probes/logs are removed after preserving this
record and the implementation branch; every lane-started test JVM has exited.
No foreign edits, sessions, roots or processes were cleaned or operated.

All production bytes remain in the committed isolated branch while the shared
resource is held. Integrate its five commits in the listed order after that
ownership boundary clears; the documentation commit is also copied to that
branch. This is green fast iteration, not a shared-tree adoption claim.
