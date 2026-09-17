---
type: prd
status: proposed
created: 2026-09-17
tags: [error-model, data-model, contracts, schema, program-facts]
---

# Error entities, composable facets, and declared error returns

## 1. Binding rulings and scope

The owner’s [program-facts PRD §1m and §1o](program-facts-are-the-runtime-prd-2026-09-17.md#1m-owner-rulings-2026-09-17-2110z-answering-the-error-designs-options) (`:424`, `:467`) bind this plan. An error is an entity, identified structurally by its base schema; its domain facets compose. There is no stored kind, boolean class stamp, exclusive classification, or most-specific-facet dispatch. Humans receive the render pairs declared on the base and every satisfied facet; programs consume the data. A prose message is optional.

Critical faults under `:panic` stop the affected agent/cluster graph and preserve the JVM and system REPL. Database unavailable means panic under either dial setting. Otherwise errors are stored, connected facts. There is **no durable side log, backlog, or replay**. B1 predicate consolidation follows owner review of this design. The writer/turn repairs already proceeding remain valid; their schema names, constructor data, and declared error outputs must later follow this plan.

This supersedes the identity, exclusivity, log/replay, implicit-error-output, and dispatch proposals in the [research study](../research/error-and-data-model-design-2026-09-17.md), whose audit evidence remains the record. This study read §1m/§1o and both modeling skills end to end; follow the [modeling guide](../../../seon/architecture/data-modeling-guide.md) §§2.1, 3.1, 5.1–5.3 (`:243`, `:529`, `:646`). Its older allowance for `:seon.error/kind` at `:664` is superseded here. No production files, JVM, tests, MCP evaluations, or lifecycle operations belong to this design delivery.

## 2. The schema declarations

### 2.1 Extension is structural, and expansion is a prerequisite

**Source correction:** `:and` is the supported extension form, already used by `resources/seon/schemas/seon.artifact.edn:2`. `seon.schema/required-map-entries` combines compiled conjunctions (`src/seon/schema.clj:1539`); its registries use `m/default-schemas` (`:1110`, `:1802`). That default does **not** include `:merge` (`reference-code/malli/src/malli/core.cljc:3045`); Malli provides it separately in `util.cljc:398`. Do not publish an unsupported `:merge` form on the assumption it works.

The raw walker currently selects only one literal map inside an `:and` (`src/seon/schema/form.cljc:25`, `:39`), so compiled acceptance alone is insufficient. Extend the existing declaration expansion to resolve aliases and combine all inherited map entries before storage-attribute discovery, required-attribute indexing, render coherence, and canonical shape projection. Preserve the authored reference edges. Conflicting definitions or optionalization of a required base member refuse. Do not maintain a copied base in every resource.

Exactly three extension choices remain; the first is the implementation recommendation:

| Choice | Guarantee | Cost / what we give up |
|---|---|---|
| **Existing `:and` plus complete expansion — recommended** | One base, native conjunction validation, all inherited attributes reach the writer and discovery. | Fix the raw walker/projection; no `:merge` spelling. |
| Install Malli `:merge` uniformly | Native map extension and explicit merge syntax everywhere. | Update every registry, structural reader and analyzer; enforce no weakening because Malli’s merge normally lets later entries win (`util.cljc:59`). |
| Fully expanded authored maps | Works with today’s raw map walker. | Repeats the base in every file; needs an enforced generation/check step and loses direct authored extension. |

The rest of this plan uses the recommended form. A facet declaration looks like:

```clojure
:seon.db.read/error
[:and {:seon.db/attributes true
       :seon.render/ai seon.db/render-read-error-ai
       :seon.render/html seon.db/render-read-error-html}
 :seon.error/base
 [:map
  [:seon.db/read-operation :seon.db/read-operation]
  [:seon.db.read/target :seon.db.read/target]
  [:seon.error/basis :seon.error/basis]]]
```

These render functions and the new keys below are proposed, not existing APIs. Every scalar is declared once in its owner resource. Every owned relation uses `[:and {:seon.db/component true :seon.db/component-schema K} :seon.db/ref]`, abbreviated **C(K)** below. Child schemas are ordinary open `[:map {:seon.db/attributes true} …]` forms. Storage stores refs; the runtime/pulled owned value derives from the entity schema and selector, never a second hand-written schema. Component ownership and complete final-report validation remain mandatory (`src/seon/db.clj:3180`; `src/seon/schema/datahike.clj:299`).

### 2.2 Base in `resources/seon/schemas/seon.error.edn`

Declare `:seon.error/base` as an open entity map over the following entries, with its own AI/HTML pair. **Only the first three entries are universally required:** the seam always knows when, where and which operation observed failure. The remaining observations are required by facets when that domain guarantees them. Absence means unavailable/not supplied, never a fabricated `nil`, zero basis, empty identifier, or `:unknown` value inside an identity field. Add an explicit unavailable observation when the seam attempted to acquire evidence and could not.

| Member | Malli form / presence | Meaning and reason |
|---|---|---|
| `:seon.error/at` | `:inst`, required | Observation time; existing key. This records an event even with no evidence members. |
| `:seon.error/layer` | `:qualified-keyword`, required, new | Responsible boundary, e.g. `:seon.db/read`; not a discriminator or severity. |
| `:seon.error/operation` | `:qualified-symbol`, required, new | Operation that observed the failure; exact symbol, not a string or guessed namespace. |
| `:seon.error/message` | existing `:string`, optional | Original dependency prose when useful; never required for recognition or dispatch. |
| `:seon.error/member` | `[:or :qualified-keyword :qualified-symbol]`, optional, new | Observed member token, indexed; mixed union uses the existing bridge codec. An arbitrary invalid token belongs in the offending projection. |
| `:seon.error/expected-key` | `:qualified-keyword`, optional, new | Observed registered schema/attribute name, indexed value edge. |
| `:seon.error/expected-shape` | `:seon.source/digest`, optional, new | Exact canonical shape fingerprint, joined to `:seon.schema.shape/fingerprint`; survives schema deletion. |
| `:seon.error/location` | C(`:seon.error.location/entity`), optional, new | Ordered value/schema path, not a cardinality-many vector pretending to preserve order. |
| `:seon.error/offending-projection` | C(`:seon.error.projection/entity`), optional, new | Independent bounded projection of the actual offending leaf. |
| `:seon.error/evidence-items` | `[:set {component properties} :seon.db/ref]` of `:seon.error.evidence/entity`, optional, new | Owned, typed observations; empty collection has no datoms. Domain facts needed for queries stay directly in their facet. |
| `:seon.error/evidence-unavailable` | `[:string {:min 1}]`, optional, new | Why an attempted observation was unavailable; absence of evidence never asserts success. |
| `:seon.error/cause` | `:seon.db/ref`, optional, new | Peer relation to an already recorded causal error occurrence; sweep on target deletion. Before recording, a causal error value is passed as an ordinary value for the recorder to record/link. |
| `:seon.error/fix` | existing `:string`, optional | Suggested human action, not an executable command or classification. Structured repair subjects remain domain facts. |
| `:seon.error/basis` | C(`:seon.error.basis/entity`), optional, new | Required for reads/writes made against an acquired database. Contains the observed store, branch, commit and `:t`, not the later fault-recording transaction. |

Supporting maps, declared in the same resource: `projection/entity` requires `:seon.error/capped? :boolean` and either `:seon.instrument/actual :string` for a present leaf or new `:seon.error.projection/missing-member :qualified-keyword` for an observed absent map key (mutually exclusive); optionally `:seon.instrument/actual-size [:int {:min 0}]` when measured and the declared bound/omission evidence. Keep the existing actual leaf admission in `src/seon/error.clj:488` (`offending-entry`), `:512` (`admitted-size`), `:524` (`fit-fact-payload`), `:562` (`prepare`). An actual `nil` is projected as data, not stored as nil; an absent problem is different. A retained evidence blob is the existing payload mechanism, never a replay log.

`location/entity` requires `:seon.error.location/length [:int {:min 0}]`; optional owned `segments` each require `ordinal [:int {:min 0}]` and one observed key/index (registered path-segment union). Length zero positively records the root path. `evidence/entity` requires `:seon.error.evidence/attribute :qualified-keyword` and either a typed scalar `value [:or :string :int :double :boolean :qualified-keyword :qualified-symbol :inst]` or `projection C(projection/entity)`; the declaration enforces exactly one. `basis/entity` requires new `:seon.error.basis/store :seon.store/id` (`:uuid`), `/branch :seon.store/branch` (`:keyword`), `/commit :uuid`, and `/t :seon.db/basis-t` (`:int`), all observation values (`seon.store.edn:1`, `:4`; `seon.db.edn:166`, `:173`). These attributes must not acquire uniqueness. No live database object is stored; the acquisition seam supplies this basis rather than rereading after failure.

Do not retype existing `:seon.error/expected`, `/offending`, `/data`, `/evidence` to mean these new facts: they currently mean polymorphic explanation input, generic map context, or an error identity tuple (`seon.error.edn:20`, `:36`, `:211`). Move constructor use to the named members, retire obsolete declarations once references disappear. `/value` becomes a base alias for genuinely generic error inspection; domain producers must use explicit facets in their contracts (§5).

### 2.3 Facets in their owning resources

Every row below means `[:and {entity and render-pair properties} :seon.error/base [:map REQUIRED …]]`; optional detail may accrete freely. **Facets overlap deliberately.** A read error with turn/agent attribution satisfies both read and turn facets. No duplicated “read-in-turn” class is needed. Domain attributes refer to observed identities; they must not introduce a domain entity’s unique identity on the error row.

| Owning resource → proposed facet | Required non-base members and forms | Additional requirement |
|---|---|---|
| `seon.db.edn` → `:seon.db.read/error` | `:seon.db/read-operation` existing enum; `:seon.db.read/target` C(read-target); base `/basis` | Target requires query, selector+eid, or index+attribute according to the actual read grammar (`seon.db.edn:61`). Store a normalized query using the bridge codec plus bounded arguments, not a live db. |
| `seon.db.edn` → `:seon.db.write/error` | `:seon.db.write/attempt` C(attempt); base `/basis` | Attempt requires submitted-operation projection and submission observation. Confirmed rejection and unresolved completion are different evidence: optional rejection details vs elapsed/bound and submission identity. Never stamp “outcome unknown” true. |
| `seon.db.edn` → `:seon.db.availability/error` | `:seon.db.availability/connection` nonempty identity string; `/failed-observation` C(evidence/entity) | A failed availability observation, not an ordinary rejected transaction. May coexist with read/write; no fabricated basis before acquisition. |
| `seon.instrument.edn` → `:seon.instrument/contract-error` | `:seon.instrument/fn` qualified symbol; new `/arity [:int {:min 0}]`; new `/check [:enum :input :output :guard]`; base `/expected-shape`, `/offending-projection`, `/location`; new `/explanations` owned nonempty explanation rows | Arity is actual invocation count, not an arity-row ref. The check is the actual validation phase, not an error kind. Retire the narrower `/arm` diagnostic field in this reset after consumers move; do not store both spellings. |
| `seon.sci.eval.edn` → `:seon.sci.eval/acquisition-error` | new `/requested-program` digest; `/acquisition-member` observed symbol/keyword; `/acquisition-observation` C(evidence/entity) | Required target generation/member and observed refusal. No live SCI ctx in the entity. |
| `seon.sci.eval.edn` → `:seon.sci.eval/evaluation-error` | new `/evaluation-id` alias of `:seon.eval/id` with identity metadata stripped; `/source-projection` C(projection/entity) | Evaluation token survives compaction; recorder connects a current evaluation when present. |
| `seon.effect.edn` → `:seon.effect/error` | new `/request-identity` nonempty string; `/capability-symbol :qualified-symbol`; `/execution-observation` C(evidence/entity) | Request identity and the capability actually attempted. Settlement success requires accepted write evidence; never bury an error in its transaction slot. |
| `seon.turn.edn` → `:seon.turn/error` | new `/error-turn-id` value alias of turn ID; `:seon.agent/error-agent-id` value alias of agent ID | Turn attribution is itself a facet, attached by the seam that knows it. Transition/rule/phase facts add narrower facets; an unknown turn must not be invented. |
| `seon.agent.edn` → `:seon.agent/error` | `/error-agent-id` non-identity value alias | Agent attribution alone is meaningful; optional graph/proc observation adds lifecycle detail. |
| `seon.boot.edn` → `:seon.boot/error` | new `/failed-phase :qualified-keyword`; `/process-root [:string {:min 1}]` | Actual boot phase and root; works before store acquisition. Phase is an operation observation, not a kind stamp. |
| `seon.cluster.edn` → `:seon.cluster/error` | new `/error-cluster-name` non-identity value alias; `/graph-operation :qualified-symbol` | Required actual cluster/graph operation, optional proc symbol; this is not every error happening in a cluster. |
| `seon.ai.edn` → `:seon.ai/provider-error` | new `/error-provider` nonempty descriptor identity; `/request-observation` C(evidence/entity) | Provider/model/endpoint, request attempt identity, HTTP status, timeout and measured stream progress when known. Model/attempt facets require those members separately. |
| `seon.render.edn` → `:seon.render/error` | new `/requested-output [:enum :seon.render/ai :seon.render/html]`; `/input-projection` C(projection/entity) | Requested face and failed value; renderer symbol optional when selection failed before a renderer existed. |
| `seon.config.edn` → `:seon.config/error` | new `/error-key :qualified-keyword`; base `/expected-key` or `/expected-shape` | The requested member and its constraint, with offending leaf. Invalid unknown names use the contract facet, not a fabricated valid config key. |
| `seon.test.edn` → `:seon.test/error` | new `/error-test-symbol :qualified-symbol`; `/execution-observation` C(evidence/entity) | Symbol value, never function/test ref. Run/selection/worker errors additionally carry their existing request/run evidence. |

New observation aliases are **not** unique identities: strip `:seon.db/identity` before reuse. The same rule applies to all observed plan/message/blob/schema IDs below. Existing meaningful payload attributes can stay where their meaning is already correct; require the payload, not a parallel true marker.


Facet-specific renderer names follow the existing domain owner, and their contracts take the facet’s derived owned value. Each retained payload class in §3 is another facet, so it gets its own pair only where it has a distinct whole concern; identical requirements collapse to one schema/pair. Resource location is ownership, not a runtime dispatch rule. For new row-specific members not named in §2.3, use `<owning-namespace>.error/<observed-member>`: token forms alias the existing non-identity domain scalar; paths use `location/entity`, arbitrary authored requests use `projection/entity`, and completed observations use `evidence/entity`. Require exactly the facts named in that row, and document unavailable preconditions as a base-only/contract alternative in the producer output. Do not invent an umbrella `request :map` attribute.

### 2.4 Declaration checks

Extend `seon.schema.internal/assert-complete-schema!` (`src/seon/schema/internal.cljc:119`) and canonical reference expansion (`src/seon/schema.clj:1275`, `:1539`, `:1802`). Every declared error facet must structurally extend the base, retain its required members, and require at least one non-base attribute. Derive the facet population from those extension edges in the canonical schema graph, not a namespace convention, hand roster, `:class` property, or replacement marker.

Validate expanded stored attributes with `storable-attribute-in?`; distinguish an unstorable **contract envelope** from a member promised as a datom. Check component targets, requiredness, no stored nil, and AI/HTML pair coherence. Reject a purported facet whose only addition is a boolean stamp. Ordinary factual booleans such as “the admitted evidence was capped” remain legal. Enforce this for core and agent declarations. Schema aliases do not earn duplicate render blocks.

A base-only value is a valid error. `facets` returns `#{}`; the base render positively says “No domain facet matched” with available operation/evidence. It is never refused for lacking a facet. In contrast a malformed base is an instrumentation/constructor defect, with its original value independently projected. A broken renderer uses the existing total value floor, not recursive error construction.

## 3. Dated reset inventory — all 338 marked declarations in 65 resources

This table is executable scope, dated 2026-09-17; resource paths are under `resources/seon/schemas/`. It is a source EDN census, not a claim about the live registry. Each row is grounded at the first marked declaration’s line. Short attribute names use the resource namespace; source exceptions are written qualified.

**Apply to every row:** remove `:seon.error/class` metadata, `:seon.error/kind` members and constructor stamps; stop requiring `/message`. Delete the listed boolean marker attributes and their corresponding `-error` schema declarations (the class with that marker, not a guessed schema for each auxiliary member), redirecting their producers/contracts to the replacement facets in the final column. Keep the listed payload attributes with their existing meaning and make their existing class schema a base extension preserving required/optional membership (the simple one-payload classes continue to require that payload). The special `:seon.render/unknown` schema keeps its substantive required/optional members and extends the base. Merge duplicate schemas that then have identical requirements into the indicated owner facet; no aliases left merely to preserve old error names. Keep substantive `:error/message`/`:error/fn` *schema properties* for Malli explanations—they are not value stamps.

“Attach” means add the stated facet only with its real required evidence. A failure before that evidence exists remains base-only or the applicable contract/config/boot facet, and its output contract must state that possibility. This never licenses fake IDs. Payload-specialized facets keep their distinctions; boolean-only classes merge by operation and evidence, not by copying the old spelling into a new enum. Row-specific requirements below supplement §2.3; new scalar members use the existing domain’s registered value grammar; observed request/expected/actual details use the base’s typed evidence components.


### Shared error and program declarations — same reset batch

| Resource (first declaration), count | Delete boolean attributes + corresponding class schemas | Payload attributes (retain unless explicitly remapped) | Exact replacement / merge |
|---|---|---|---|
| `seon.error.edn:230` · 1 | `unclassified` | — | Delete unclassified marker/class; base-only is valid and rendered positively. Declare base/components and retire kind/class; `/value` aliases base only at generic inspection boundaries. |
| `seon.fn.binding.edn:23` · 1 | `unsupported` | — | `:seon.fn.binding/error`: required binding projection and source span; delete unsupported-only boolean. |
| `seon.fn.edn:184` · 13 | `analysis-failed`, `duplicate-program-identity`, `index-refused`, `manifest-absent`, `signature-refused`, `source-span-absent` | `capability-graph-malformed`, `index-transaction-refused`, `population-incomplete`, `schema-declaration-invalid`, `scratch-not-fresh`, `source-checkout-required`, `source-file-invalid` | `:seon.fn/error`: required program subject observation and analysis phase; six booleans merge, typed identity/path/phase schemas extend base. Add indexed error-facets fact (§5). |
| `seon.instrument.edn:36` · 2 | `registration-failed` | `contract-violated` | contract-violated→contract-error, replace redundant function-marker attribute with existing `/fn`; registration-failed→`:seon.instrument/registration-error`, requiring function and registration observation. |
| `seon.program.edn:108` · 4 | `declaration-refused`, `no-declaration-at`, `read-refused` | `not-found` | declaration/no-declaration/read booleans merge into `:seon.program/error` requiring subject and basis/source observation; not-found keeps subject. Attach read facet when an actual db read failed. |
| `seon.reconcile.edn:23` · 6 | `duplicate-identity`, `identity-outside-scope`, `missing-declarations`, `no-identity`, `two-identities` | `refused` | Five booleans merge into `:seon.reconcile/error` requiring requested identity/projection and constraint evidence; refused rule remains payload. Duplicate/two identities store the actual conflicting identities. |

### Database, storage and schemas — same reset batch

| Resource (first declaration), count | Delete boolean attributes + corresponding class schemas | Payload attributes (retain unless explicitly remapped) | Exact replacement / merge |
|---|---|---|---|
| `seon.blob.edn:32` · 5 | — | `content-digest-mismatch`, `input-stalled`, `invalid-threshold`, `store-root-absent`, `stored-content-mismatch` | Extend five payload schemas with base; digest, size, threshold and store-root evidence remain required. Attach availability facet only on an observed unavailable store. |
| `seon.db.edn:263` · 5 | `diff-refused`, `invalid-read`, `invalid-request`, `transaction-outcome-unknown`, `transaction-refused` | — | Delete all five booleans/classes. invalid-read→read facet; diff-refused→read plus two observed bases; invalid-request→contract facet; transaction-refused/outcome-unknown→write facet with rejection or completion evidence. |
| `seon.schema.datahike.edn:2` · 10 | `enum-not-storable`, `literal-not-storable`, `value-type-unavailable` | `attribute-absent`, `invalid-secondary-attribute`, `malformed-edn`, `nilable-attribute`, `noncanonical-edn`, `schema-invalid`, `storage-not-string` | Three booleans merge into `:seon.schema.datahike/error`, requiring attribute and declared value form; retain attribute-bearing facets; preserve expected storage type and offending projection. |
| `seon.schema.edn:61` · 25 | `invalid-identity-projection`, `malformed-artifact-export`, `malformed-projection-form`, `malformed-projection-identity`, `malformed-projection-row`, `missing-projection`, `noncanonical-projection-data`, `render-contract-incoherent` | `cyclic-reference`, `duplicate-projection-row`, `incomplete-predicate-contract`, `invalid-schema`, `nilable-map-value`, `nilable-return`, `nilable-value-schema`, `non-round-tripping-form`, `noncanonical-definition`, `schema-in-use`, `single-segment-namespace`, `undefined-contract`, `unknown-shape`, `unproved-predicate-purity`, `unreadable-form`, `unregister-outside-delta`, `unresolved-predicate` | The listed booleans merge into `:seon.schema/error`, requiring requested declaration/projection observation and expectation. Keep all payload facets, canonical shapes and actual member paths. |
| `seon.schema.edn.edn:11` · 8 | — | `dishonest-generator`, `duplicate-attribute`, `misplaced-attribute`, `not-a-map`, `unreadable-file`, `unregistered-predicate`, `unresolved-reference`, `unsafe-namespace` | No booleans: extend eight payload schemas. Keep file/key/predicate evidence and loader phase; duplicate schema declarations must identify both source sites. |
| `seon.schema.shape.edn:33` · 3 | `fingerprint-collision`, `noncanonical-compiled-form`, `unsupported-map-key` | — | All three merge into `:seon.schema.shape/error`, requiring fingerprint/form/path observation and expected canonical shape; no duplicate AST family. |

### SCI and evaluation — same reset batch

| Resource (first declaration), count | Delete boolean attributes + corresponding class schemas | Payload attributes (retain unless explicitly remapped) | Exact replacement / merge |
|---|---|---|---|
| `seon.cluster.reply.edn:14` · 3 | `no-forms` | `refused-tag`, `unreadable` | `:seon.cluster.reply/error`: required authored reply projection replaces no-forms; keep tag/unreadable payload and attach reader/evaluation facets. |
| `seon.eval.drive.edn:57` · 1 | `absent` | — | `:seon.eval.drive/error`: required evaluation token and deadline/driver observation; delete absent-only class. |
| `seon.sci.admit.edn:81` · 1 | `projection-failed` | — | `:seon.sci.admit/error`: required admission bound and failed projection observation; delete projection-failed boolean; preserve explicit omitted evidence. |
| `seon.sci.eval.edn:54` · 10 | `acquisition-refused`, `evaluation-failed`, `install-mismatch`, `namespace-binding-cycle`, `namespace-unloadable` | `documentation-unavailable`, `missing-function-row`, `reader-event-count`, `schema-refused`, `time-limit` | acquisition/install/binding-cycle/unloadable booleans merge into acquisition facet with target program/member; evaluation-failed→evaluation facet. Keep documentation/function/schema/count/time payload facets. |
| `seon.sci.kernel.edn:13` · 7 | `already-armed`, `failure-admission-failed`, `missing-interrupt-guard` | `invocation-failed`, `missing-function-installer`, `time-limit`, `unresolved-invocation` | Three booleans merge into `:seon.sci.kernel/error`, requiring guard/arm acquisition observation; retain invocation symbols. Broken guard is distinct data from ordinary authored evaluation failure. |
| `seon.sci.reader.edn:14` · 5 | `fabricated-response`, `keyword`, `oversize`, `unreadable` | `refused-tag` | Four booleans merge into `:seon.sci.reader/error`, requiring source projection and reader location; tag remains a payload facet. Reader mistakes remain ordinary data. |

### Agent, turn, effects and flow — same reset batch

| Resource (first declaration), count | Delete boolean attributes + corresponding class schemas | Payload attributes (retain unless explicitly remapped) | Exact replacement / merge |
|---|---|---|---|
| `seon.agent.edn:145` · 6 | `armer-quiescence-undeliverable`, `supervision-not-committed` | `creation-incomplete`, `no-such-agent`, `turn-completion-backstop`, `turn-completion-undeliverable` | Attach `:seon.agent/error`; quiescence/supervision booleans merge into `:seon.agent.graph/error` requiring proc symbol and failed control observation; preserve four agent-ID observations. |
| `seon.cluster.wake.edn:37` · 1 | — | `undeliverable-wake` | `:seon.cluster.wake/error`: extend the existing payload-bearing undeliverable-wake schema with required recipient and attempted offer observation; there is no boolean marker to delete. An unavailable offer is not no wake. |
| `seon.context.edn:81` · 1 | — | `selection-refused` | Extend selection-refused with base; preserve the actual selection constraint and subject identity; no second context error family. |
| `seon.effect.edn:164` · 3 | `already-recorded`, `already-settled`, `missing-receipt` | — | All three merge into effect facet; require request identity, capability and settlement observation. Missing evaluation is explicit failed attachment evidence. |
| `seon.flow.edn:196` · 6 | `configuration`, `fault-channel-overflow` | `launcher-stopped`, `submission-capacity`, `time-limit`, `timeout` | `:seon.flow/error`: required proc symbol or graph identity and control observation replaces configuration/overflow booleans; preserve proc-ID payloads and dropped count/bound evidence. |
| `seon.schedule.edn:42` · 7 | `incomplete-task`, `invalid-fire-id`, `invalid-task-owner`, `invalid-terminal-arm`, `missing-execution-handle`, `missing-receipt`, `unresolved-handler` | — | All seven merge into `:seon.schedule/error`, requiring task/fire observation and expected handler/settlement shape; use symbol handler identity, no status-only success. |
| `seon.turn.edn:32` · 2 | `missing-opening-datom` | `refused` | missing-opening-datom→turn facet with required expected opening attribute and observed basis; refused keeps actual transition rule but requires turn/agent when known. |
| `seon.turn.loop.edn:90` · 4 | `lint-rejected`, `phase-failed` | `prompt-failed`, `terminal-refusal-settlement-refused` | lint/phase booleans merge into `:seon.turn.loop/error`, requiring turn, agent and failed step observation; prompt/terminal-rule payloads stay and compose turn/agent facets. |

### Boot, operator and configuration — same reset batch

| Resource (first declaration), count | Delete boolean attributes + corresponding class schemas | Payload attributes (retain unless explicitly remapped) | Exact replacement / merge |
|---|---|---|---|
| `seon.artifact.edn:2` · 1 | `refused` | — | `:seon.artifact/error`: required artifact identity observation and attempted transition; delete refused-only class. |
| `seon.boot.edn:156` · 1 | `refused` | — | `:seon.boot/error` from §2.3 replaces refused-only class; retain complete phase/member/expectation evidence. |
| `seon.bootstrap.edn:4` · 2 | `prefix-drift`, `root-acquisition-empty` | — | `:seon.bootstrap/error`: required initialization member and expected/source-prefix observation; both booleans merge; attach boot phase. |
| `seon.cluster.export.edn:3` · 5 | — | `clone-unsupported`, `export-exists`, `genesis-incomplete`, `no-branch-head`, `refused` | Extend five payload schemas; shared export target/branch facts remain required. Existing refused rule is a real transition constraint, not an error-kind replacement. |
| `seon.cluster.process.edn:8` · 1 | — | `start-instant-unavailable` | Extend start-instant-unavailable schema with base and its observed PID; add the failed process observation, no invented start instant. |
| `seon.cluster.registry.edn:53` · 8 | `branch-head-absent`, `candidate-file-absent`, `dry-run-barrier-absent`, `dry-run-complete` | `cannot-retire-main`, `cluster-connected`, `refused`, `source-absent` | `:seon.cluster.registry/error`: required target branch/root and registry operation observation replaces the listed booleans; keep cluster/rule payload facets. |
| `seon.cluster.source.edn:3` · 7 | `unsafe-incremental-rows` | `invalid-source-seal`, `populate-unresolvable`, `publish-readback-failed`, `refused`, `root-absent`, `stale-publication` | `:seon.cluster.source/error`: required source commit/digest and publication/adoption observation for boolean classes; keep typed source/cluster/phase payloads. |
| `seon.cluster.store.edn:2` · 6 | — | `branch-absent`, `branch-already-open`, `file-lock-generator-failed`, `held-elsewhere`, `initialization-incomplete`, `refused` | `:seon.cluster.store/error`: required store/root observation and failed acquisition/write operation; extend all six payload schemas (there are no boolean markers here); attach availability/boot facets. No log/replay. |
| `seon.config.edn:43` · 7 | `missing-result-cap` | `manifest-unreadable`, `missing-effective`, `reconcile-refused`, `refused`, `required-absent`, `unknown-key` | `:seon.config/error` and missing-bound observation replace booleans. Replace `/refused` and `/reconcile-refused` alias attributes with their existing `:seon.config/rule` value; merge their two schemas onto the same rule/member/expected facet; retain differing operations as facts. |
| `seon.dev.mcp.artifact.edn:9` · 1 | — | `root-not-committed` | Extend root-not-committed with base and digest; retain retrieval request/basis as evidence. |
| `seon.dev.mcp.edn:2` · 5 | `jvm-exception`, `nil-deref` | `cluster-degraded`, `remainder-not-retrievable`, `value-not-found` | `:seon.dev.mcp/error`: required requested cluster/root and operation observation replaces exception/nil booleans; retain cluster/digest payload schemas. |
| `seon.env.edn:84` · 8 | `absent-environment`, `agent-id-absent`, `incomplete-environment`, `invalid-environment-replacement`, `invalid-environment-state`, `invalid-member`, `schema-absent`, `unscopable-member` | — | All eight merge into `:seon.env/error`, requiring requested member and member expectation; the incomplete/invalid-member declarations already exist, no duplicate schemas. |
| `seon.operator.collect.edn:13` · 1 | `unrecognized-option` | `option-key` | `:seon.operator.collect/error`: require existing option-key and its expected schema; delete unrecognized-option boolean, keep offending projection. |
| `seon.operator.edn:3` · 6 | `cluster-cleanup-incomplete`, `collection-incomplete`, `failed`, `process-census-incomplete`, `reap-incomplete` | `low-disk-space` | `:seon.operator/error`: required root/process identity observation and requested operation; five booleans merge; retain low-disk path with measured/required byte facts. |

### Provider and prompt — same reset batch

| Resource (first declaration), count | Delete boolean attributes + corresponding class schemas | Payload attributes (retain unless explicitly remapped) | Exact replacement / merge |
|---|---|---|---|
| `seon.ai.edn:6` · 20 | `credential-failure`, `extra-body-conflict`, `invalid-extra-body`, `model-failure`, `no-credential`, `reasoning-without-answer`, `response-failure`, `stream-truncated`, `token-starvation`, `transport-before-send-failure`, `unparseable-body` | `authentication-failure`, `authorization-failure`, `provider-error`, `provider-server-failure`, `rate-limited`, `request-failure`, `timeout`, `transport-failure`, `transport-outcome-unknown` | Attach provider facet; boolean request/response/credential cases merge by request evidence. Preserve status/timeout/endpoint observations; add sent-at/last-response observation for uncertain completion. |
| `seon.cluster.prompt.edn:30` · 5 | `no-trigger` | `budget-exceeded`, `missing-cluster`, `missing-config`, `refused` | `:seon.cluster.prompt/error`: required agent token and prompt derivation observation replaces no-trigger; retain budget/agent/rule payload facets. |

### Render, print, search and problems — same reset batch

| Resource (first declaration), count | Delete boolean attributes + corresponding class schemas | Payload attributes (retain unless explicitly remapped) | Exact replacement / merge |
|---|---|---|---|
| `seon.print.edn:396` · 1 | — | `unknown-face` | Extend unknown-face with base and face value; attach render facet where requested-output exists. No new clipping mechanism. |
| `seon.problems.edn:30` · 2 | `evaluation-failed` | `unbound-var` | evaluation-failed merges into evaluation facet when identity exists, otherwise `:seon.problems/error` requires detector/function and failed observation; unbound-var keeps symbol. |
| `seon.render.data.edn:31` · 1 | `no-such-path` | — | `:seon.render.data/error`: required root identity observation and requested path; delete no-such-path boolean. |
| `seon.render.edn:144` · 4 | `ambiguous`, `walk-failed` | `:seon.render.unknown/call`, `:seon.render.unknown/output`, `:seon.render.unknown/producer`, `:seon.render.unknown/reason`, `:seon.render.unknown/refusal`, `:seon.render.unknown/throwable`, `invalid-output` | ambiguous/walk-failed merge into render facet with candidate/step evidence; invalid-output keeps requested output; unknown becomes base extension with its substantive reason/call/refusal fields, never a marker. |
| `seon.render.hiccup.edn:3` · 1 | — | `unparseable-tag` | Extend unparseable-tag with base/tag and attach render facet; no refusal of ordinary values at the total renderer. |
| `seon.render.value.edn:52` · 3 | `missing-root-identity`, `window-realization-failed` | `window-failed` | `:seon.render.value/error`: required inspected root/window and projection observation replaces two booleans; keep window digest; preserve admission bound evidence. |
| `seon.render.walk.edn:94` · 3 | `connections-failed`, `elided`, `no-such-entity` | — | All three merge into `:seon.render.walk/error`, requiring walk subject and step observation; elision also requires bound and continuation identity, not an unexplained truncated walk. |
| `seon.render.web.edn:2` · 9 | `invalid-context-action`, `live-processes-unavailable`, `owner-not-ensured`, `preview-unavailable`, `prospective-context-unavailable` | `function-unavailable`, `missing-port`, `value-not-found`, `value-unreadable` | Boolean cases merge into `:seon.render.web/error` with requested route/context-action and owner observation; retain function/port/digest facets. Missing owner is never healthy absence. |
| `seon.search.edn:47` · 2 | `missing-resource`, `unavailable` | — | Both booleans merge into `:seon.search/error`, requiring requested resource and index/basis observation; failed rebuild is not a successful empty index. |

### Agent-facing domain protocols — same reset batch

| Resource (first declaration), count | Delete boolean attributes + corresponding class schemas | Payload attributes (retain unless explicitly remapped) | Exact replacement / merge |
|---|---|---|---|
| `my.background.edn:23` · 3 | `invalid-call`, `invalid-result`, `missing-result` | — | `:my.background/error`: required authored form projection; invalid-call/result/missing-result merge; completion evidence distinguishes them. |
| `my.edit.edn:64` · 6 | `lossless-check-failed`, `parse-refused` | `ambiguous-match`, `no-match`, `not-utf8`, `stale-source` | `:my.edit/error`: required requested path and edit/digest observation; the two boolean classes merge; keep the four path observations. |
| `my.fs.edn:121` · 16 | `atomic-write-unsupported`, `read-failed`, `write-failed` | `already-exists`, `blob-unavailable`, `changed-during-read`, `glob-failed`, `invalid-glob`, `invalid-utf8-window`, `not-directory`, `not-found`, `not-regular-file`, `path-refused`, `read-limit`, `stale-digest`, `write-limit` | `:my.fs/error`: required requested path and filesystem operation evidence; atomic/read/write booleans merge; retain bounds/digests and all path/pattern observations. |
| `my.message.edn:59` · 6 | `no-about`, `no-assignment`, `no-content`, `no-reason`, `no-recipient` | `not-found` | `:my.message/error`: required requested message projection; the five missing-member booleans merge into required-member explanations; not-found keeps message identity. |
| `my.note.edn:25` · 5 | — | `about-not-found`, `agent-not-found`, `identity-owned-by-another-agent`, `not-found`, `not-owned` | No boolean classes. Extend all five payload schemas with the base; share note/agent/about constraints with existing note requests, not a new note entity. |
| `my.plan.edn:223` · 13 | `agent-not-found`, `dependency-cycle`, `dependency-not-found`, `duplicate-identity`, `duplicate-position`, `foreign-identity`, `identity-exists`, `item-reference-not-found`, `item-reference-not-owned`, `not-found`, `not-owned`, `subject-not-found`, `unusable-current-step` | — | `:my.plan/error`: required requested plan/step/subject projection and constraint evidence; all 13 merge. Add actual identities, positions and dependency edges when available, never a new failure enum. |
| `my.shell.edn:36` · 5 | `blob-unavailable`, `cwd-refused`, `start-failed`, `stdin-limit`, `time-limit` | — | `:my.shell/error`: required command projection; all five merge, with cwd, stdin bytes, declared deadline and execution observation as real data. |
| `my.turn.edn:34` · 3 | `blank-note`, `blank-result`, `usage-walkthrough-absent` | — | `:my.turn/error`: required proposed disposition/note/result projection; all three merge; attach turn/agent facets from custody. |
| `my.web.edn:39` · 11 | `invalid-url`, `missing-location`, `no-credential`, `projection-failed`, `provider-failed`, `redirect-limit`, `redirect-loop`, `response-limit`, `timeout`, `transport-failed`, `unparseable-response` | `query`, `status`, `url` | `:my.web/error`: required request projection; all 11 merge; URL/query/status stay data. Required request fields vary by fetch/search facet, not a boolean reason. |
| `seon.message.edn:3` · 5 | `blank-content`, `no-limit` | `chain-limit`, `content-too-large`, `unknown-recipient` | `:seon.message/error`: required message request projection replaces blank-content/no-limit; retain limit/size/recipient evidence and share the same facts with my.message. |

### Test system — same reset batch

| Resource (first declaration), count | Delete boolean attributes + corresponding class schemas | Payload attributes (retain unless explicitly remapped) | Exact replacement / merge |
|---|---|---|---|
| `seon.test.accretion.edn:101` · 1 | `install-refused` | `:seon.fn/sym`, `actual`, `advisories`, `arguments`, `auto-check`, `expected`, `failure-groups`, `install?`, `orientation`, `test-count`, `test-fail-count`, `test-pass-count` | Delete install-refused boolean and kind member. Replace unique `:seon.fn/sym` on the error with `:seon.instrument/fn`; map expected/actual to base expected-shape/offending-projection and arguments to bounded projection. `:seon.test.accretion/error` requires that proposed contract evidence; retain measured counts and typed owned failure groups, not unstorable raw values. |
| `seon.test.edn:50` · 2 | — | `not-runnable`, `unknown` | Extend unknown/not-runnable payload schemas; attach test facet when a valid symbol was requested. Strings are observed source data, never coerce malformed names into symbols. |
| `seon.test.run.edn:53` · 2 | `unavailable` | `immutable` | unavailable→`:seon.test.run/error` requiring run/request observation; immutable keeps observed run identity without uniqueness; completion claim is required where asserted. |
| `seon.test.runner.edn:64` · 10 | `invalid-marker-reason`, `process-tree-exit-backstop`, `unknown-worker-command`, `unresolved-test-var`, `worker-launch-failure` | `default-cluster-refused`, `invalid-long-reason`, `invalid-selection-mode`, `invalid-silence-seconds`, `long-test-ns-hook` | Five booleans merge into `:seon.test.runner/error` requiring selection/request and worker/command observation; preserve cluster/mode/bound/namespace payload facets. Coordinate the running test-system owner. |

The census can be regenerated without a JVM: read every schema EDN map (including namespaced maps), select top-level forms whose properties contain `:seon.error/class true`, walk their `:map`/`:and` entries, resolve referenced scalar forms, and partition literal-true markers from payload members. Count declarations, not marker attributes; `:seon.render/unknown` and `:seon.test.accretion/install-refused-error` have several members. The prior [reader form and 65-resource counts](../research/error-and-data-model-design-2026-09-17.md#34-dated-per-resource-error-class-edit-inventory) remain reproducible evidence. This plan’s table names the attribute edits, not only the resource totals.

Additional edits beyond the class-marker columns: `seon.error.occurrence.edn` gains base/facet storage and connecting refs (§4); `seon.fn.edn` gains the indexed facet fact (§5); `seon.program.edn` gains facet query result shapes; `seon.schema.shape*.edn` retain one canonical shape family; `seon.agent.edn`/`seon.cluster.edn` gain failure-state components. No log schemas. Fix known shape debts before contracts: generic `:seon.error/data`, `:seon.flow/core-fault`, maintenance result/fact maps and transaction-report metadata; derive `:seon.test.failure/value` from its entity selector instead of its copied map (`seon.test.failure.edn:35`, `:58`). Preserve legitimate polymorphic value/render boundaries. The research study §§3.1–3.2 supplies the remaining domain shape prerequisites; its reset-era symbol joins are already landed, not new migration work.

## 4. Wiring the one family

### 4.1 Predicate, constructor, facet acquisition, boundaries

`seon.error/error?` (`src/seon/error.clj:1615`) becomes “satisfies the acquired base validator.” It does not ask for a message, a kind, or a matching class marker. Before database acquisition, use the same packaged declaration projection; do not create a second bootstrap error taxonomy. Validators and extension-derived facet candidates are compiled/memoised **per immutable projection**, carried by the environment/wrapper. No global cache keyed only by schema name and no database fetch while handling a failure.

`diagnostic` (`src/seon/error.clj:311`) constructs the base and the domain members the observing seam actually knows. It no longer assembles a kind plus an opaque `/data` bag. The turn/evaluation/agent seam enriches the value before returning/transporting it; the recorder does not guess the active turn from “the latest open turn.” Keep `buried-error` (`src/seon/instrument.clj:99`) and the one read-consumer helper’s short circuit. A refused `nil | row | error` read never becomes a row, zero count, empty result, or “already done.”

Add `seon.error/facets`: explicit projection plus error value → set of canonical facet keys satisfied, excluding the base and aliases. Reuse `schema/matching-shapes-in` (`src/seon/schema.clj:3398`) after extension expansion makes its required-attribute index complete. Memoise candidate validators/extension closure, **not arbitrary error objects**. Matching a value remains validation against all relevant facets; the bounded diagnostic `candidate-shapes` window is not classification authority.

Exceptions are transport only: `ex-data` is the same error value, and recognized errors are extracted before generic Throwable normalization. The writer’s owned-refusal extraction already demonstrates preservation (`src/seon/db.clj:3306`; the request’s older `:3441` anchor moved during writer repairs). Preserve that through Datahike wrapper causes; never replace the original with a second generic “db error.” The wrapper’s panic reporter is `src/seon/instrument.clj:450`; its record path must refuse execution of an invalid input too—returning from a Malli reporter alone does not prevent the call (`reference-code/malli/src/malli/core.cljc:2213`, instrumentation reporter path).

The one helper consumes the existing `:seon.config/on-core-error` and the boundary’s **disposition observation**, not a facet-to-severity table. Ordinary authored mistakes return data and are recorded without stopping. A core contract breach, refused core-owned write, or unconfirmed owned transition is recorded; `:panic` transports that same value to stop its affected graph, while `:record` aborts the failed operation and keeps unrelated work available. Confirmed database unavailability stops the affected scope under either setting. Carry the acquired dial, target graph and observation in the request; do not reread them from the unavailable store. Keep this helper in its existing owner; no second helper per facet.


B1 removes exactly the nine private copies identified by triage #1: `seon.db/error-value?` (`db.clj:161`), `seon.operator/error-value?` (`operator.clj:54`), `seon.plan/error-value?` (`plan.clj:84`), `seon.call-preparation/error-value?` (`call_preparation.clj:116`), `seon.note/error-value?` (`note.clj:30`), `seon.cluster.message/error-value?` (`cluster/message.clj:486`), `seon.render.ns/error-value?` (`render/ns.clj:54`), `seon.instrument/flat-error-value?` (`instrument.clj:93`), and `seon.schedule/flat-error?` (`schedule.clj:491`). The dated triage anchors move with the in-flight fixes. Use the existing lazy dependency-resolution pattern where required to avoid a db↔error namespace cycle; no replacement local predicate or fallback kind test.

The helper request’s disposition observation is a registered open map: required requested action `[:enum :return :abort-operation :stop-graph]`, observing function `:qualified-symbol`, and evidence C(`:seon.error.evidence/entity`); a stop additionally requires the actual graph identity/custody. This bounded action vocabulary describes a control decision, never the error’s kind. The observing core boundary derives the decision from its admitted obligation, caller/execution facts and availability evidence; agent-supplied error data cannot request a stop merely by resembling a critical facet. Persist a stop decision as the failure-state transaction in §4.4, not as a copied severity field on every error.

### 4.2 Fault entity, signature/occurrence, and connections

Keep the existing signature root and grouped occurrence mechanism (`src/seon/error.clj:1342`, `:1427` (`commit-call`), `:1506` (`recording`); `seon.error.edn:61`; `seon.error.occurrence.edn:17`). **The occurrence stores the actual error entity**: base + all asserted facet members + existing occurrence identity/count/times. The signature root groups occurrences; it is not a second copied diagnostic. Its owned occurrence relation declares the expanded occurrence/base component schema. Final validation checks that owning value and the types of supplied attributes. It must not infer a promised facet from partial key presence and refuse a valid base-only error: promised facet completeness belongs to the producer’s explicit output contract. Rendering can show unmatched partial domain evidence without claiming that facet matched. Remove duplicated generic context from the signature row once queries follow the occurrences. Replace the old kind input to the signature with stable structural site/expectation evidence; keep occurrence grouping/count semantics and `seon.id` derivation. Variable message, leaf contents, agent and turn are not signature identity.

| Connection on stored occurrence | Declaration and deletion behavior |
|---|---|
| Agent | Existing `/agent :seon.db/ref`, optional peer; resolves the attached observed agent ID inside the recording transaction. Sweep on deletion; do not make deleting an agent delete the fault. |
| Turn | Existing `/turn :seon.db/ref`, optional peer; same transaction authority. Observed turn token remains domain evidence if the turn was compacted/deleted. |
| Evaluation | New `/evaluation :seon.db/ref`, optional peer; sweep. Evaluation token in the evaluation facet records what was observed, even when no current row resolves. |
| Cluster | New `/cluster :seon.db/ref`, optional peer before acquisition, required in the recorded-cluster-fault specialization; deliberate refusal of cluster deletion unless dependent current links are handled in the same final transaction. |
| Function / test | Existing `:seon.instrument/fn` and proposed `:seon.test/error-test-symbol` are indexed qualified-symbol **values**, never refs. Remove occurrence `/proc-fn` ref in favor of its actual observed function symbol; do not manufacture a missing definition. |
| Transaction basis | Base `/basis` owns the observed store/branch/commit/`:t` value. Recording provenance is the recording transaction’s resolvable `:seon.db/user` and `/process`, not copied domain attributes. |
| Cause and details | Cause optional peer ref; projections, explanations, evidence and basis are owned components with declared component schema and cascade behavior. |

The observed token and the connecting ref answer different questions required by §1o: “which identity did execution carry?” and “which current entity is this attached to?” The recorder adds refs; it must never reverse-infer a missing token or retain a sibling merely as a ref-repair mirror. Record an unresolved attachment observation when lookup fails, keep the error, and let a query expose the missing relation. Existing lookup helpers (`error.clj:1368`, `:1380`) must distinguish failed reads from absent targets and move authoritative resolution into the recording transaction. Validate the whole owning value in the final report, including a child-only mutation; never trust a pre-read or truncated wildcard pull.

An occurrence update replaces its diagnostic aspect as a complete owning value, retracting stale facet attributes/components from the prior observation before asserting the new ones. Otherwise a later read error could inherit an earlier turn/provider facet accidentally. Count/last-at retain the observation event; history retains prior data subject to normal store retention. No fault replay dedup mechanism is added.

The existing fault committer is `src/seon/flow.clj:981`; cluster preparation/recording is `src/seon/cluster.clj:2915` (`commit-fault!`, source snapshot). Extend these owners to accept ordinary error entities as well as escaped Throwables. Record healthy-store refusals in a **separate transaction after** the rejected transaction; do not recursively submit the refused data. A failed fault write enters the bounded failure path with the original error attached. Store unavailable means immediate scope panic, not repeated attempts or a side file.

### 4.3 Human explanation and compositional rendering

Read dependency: `reference-code/malli/src/malli/error.cljc:177` resolves `:error/fn` before `:error/message`; `:288` selects localized/error-type messages; `:339` supplies `with-spell-checking`; `:374` supplies `humanize`; `:392` supplies `error-value`. `humanize` returns structured path-aligned data, not a single prose string. `error-value` extracts invalid parts but ignores missing-key errors by default (`:227`); it cannot replace the complete explanation or the current `find`-based nil/false leaf extraction.

At a contract violation, retain machine facts: function symbol, invocation arity, checked input/output/guard, canonical expected shape, schema path, value path, Malli problem type, and independently admitted actual leaf. Compute `humanize` while the compiled explanation is available and embed its output **as data**, using an owned ordered tree/leaf representation (path plus `[:vector :string]` messages in memory; ordered child rows in storage). Store no compiled Malli objects and no giant raw argument vector. `:error/message`/`:error/fn` come from the acquired schema. If explanation rendering itself fails, preserve the machine facts and a bounded explanation-failure observation.


Declare `:seon.instrument.explanation/entity` in `seon.instrument.edn`: required `ordinal [:int {:min 0}]`, `schema-location C(location/entity)`, `value-location C(location/entity)`, `expected-shape :seon.source/digest`; optional `problem-type :qualified-keyword` when Malli supplies it and `actual C(projection/entity)` for a present offending entry. Require either `humanized C(:seon.instrument.humanized/entity)` or `humanization-unavailable [:string {:min 1}]`, never both. The humanized entity contains an ordered set of message components, each with required `ordinal`, `location C(location/entity)`, and `text :string`; `message-count [:int {:min 0}]` records a legitimately empty result. This is the normalized data returned by `humanize`, not hand-authored prose replacing its semantics. Preserve the original structured explanation paths separately because localized `:error/path` may move a human message. Raw `:fn` properties run only under the acquired schema’s existing predicate/execution constraints.

Apply `with-spell-checking` as an optional explanation enrichment with `:keep-likely-misspelled-of true`; retain original machine problems. Its implementation handles extra-key and invalid-dispatch errors, so it will **not** discover every misspelled key in an open map. Do not close maps to make suggestions work. `error-value` may help the explanation render inspect invalid subtrees; actual leaf storage still uses the existing bounded admission.

Declare one base AI/HTML pair and one pair per facet in the owning schema. The error render orchestrates base first, then one block per matching canonical facet in deterministic schema-key order. Use existing entity-pair acquisition (`src/seon/schema.clj:1510`; `src/seon/render.clj:323`) and block IDs (`src/seon/render/block.clj:61`); include occurrence identity and facet key in each block address. **Bypass the current most-specific filter at `render.clj:350` for this composition**, not for unrelated rendering. A base-only error gets a visible base block and the “no domain facet matched” observation. Shared fields render in the base; facet blocks render their domain facts.

AI presentation clips only in the AI render functions/value renderer under the render profile. HTML never presentation-clips. Evidence admission bounds what is stored and records omissions; it is not a second presentation clip. Saved shown text stays exact. `agent-faults-query` (`src/seon/error.clj:1744`) and the agent’s existing `:seon.error/of-steward` derived surface (`seon.agent.edn:14`) pull occurrences and their owned values; extend the namespace route similarly. A read error in a turn must visibly render the base, read, turn and agent facets from one stored entity.

### 4.4 Panic is graph failure with positive visibility

Existing seams: `seon.cluster.agent/arm!` (`src/seon/cluster/agent.clj:674`), error fan-out (`:740`), `disarm!` (`:822`), `armer-step` (`:867`); oversight’s `agent-story`/`flow-status` (`src/seon/oversight.clj:114`, `:176`); `mcp-runtime-observation` (`src/seon/cluster.clj:582`); debug graph/page (`src/seon/render/web.clj:934`, `:1727`). The current supplied panic callback (`cluster.clj:3241`) only reports; §1m requires stopping. The committer’s signature suppression (`flow.clj:1025`) must suppress duplicate notifications only, never the required stop of a second affected agent.

Add optional owned `:seon.agent/failure` / `:seon.cluster/failure` components. Each requires a fault occurrence ref and requested-stop transaction ref; optional stopped transaction proves acknowledgement. These are positive transition facts, not a `failed?` mirror. A current failure component fences arming; recovery clears/settles it explicitly. In one healthy-store transaction record fault + failure state, then stop the affected graph via its owning control path. Do not await the dead turn’s completion in `disarm!`; stop acknowledgement has its existing declared bound and a positive failure observation when missing. Keep the fault committer/control/status surfaces able to finish and report the stop.

Oversight enumerates declared agents/graphs plus failed state, not only armed entries. A missing turn proc is failed/unknown, never omitted as healthy. Runtime status and the debug page display the connected fault, requested/confirmed stop and affected scope. The page must still serve through the existing operator/web boundary when its cluster’s working graph has stopped; it cannot depend on that dead graph emitting the next render update.

**Database-down limit:** an unavailable database cannot persist its own new failed-state datom. Stop immediately from the acquired scope, expose “database unavailable; failed state not persisted” through process-local status and the REPL, and preserve the JVM. Do not claim durability, invent a side log, or queue replay. On operator repair, record the newly observed recovery/failure state through the ordinary recorder. Previously committed failure state remains durable; uncommitted outage evidence cannot be promised across JVM loss under the owner’s no-log ruling.

### 4.5 Triage is a query over the stored data

These illustrative Datalog clauses use the proposed declarations; they are not live-query evidence:

```clojure
;; Read errors, including those that ALSO have turn/provider/contract facets.
[:find ?error ?op ?basis
 :where [?error :seon.error/operation]
        [?error :seon.db/read-operation ?op]
        [?error :seon.db.read/target]
        [?error :seon.error/basis ?basis]]
;; Errors at a function, surviving its definition's retraction.
[:find ?error :in $ ?sym
 :where [?error :seon.instrument/fn ?sym]
        [?error :seon.error/at]]
;; Connected errors for an agent; pull these occurrences and their components.
[:find ?error :in $ ?agent-id
 :where [?agent :seon.agent/id ?agent-id]
        [?error :seon.error.occurrence/agent ?agent]]
```

Generate facet-presence clauses from expanded required attributes/components; the final writer’s validation establishes their declared types. Add explicit failed attachment/base-only queries; do not count “no matching rows” as proof the detector ran successfully. Namespace stewardship follows the current function-symbol → function → namespace → steward relation (`error.clj:1392`). Historical function absence stays visible and does not erase the error.

## 5. Error facets as program facts

### 5.1 Output convention and analysis

A domain function writes `[:=> INPUT [:or SUCCESS :seon.db.read/error :seon.config/error]]`. To promise simultaneous aspects use `[:and :seon.db.read/error :seon.turn/error]` as one alternative; an `:or` of those facets means either is allowed, not that both are present. Nullable reads use `[:or :nil PULLED-ROW :seon.db.read/error]`; `PULLED-ROW` derives from entity X and the actual selector. This supersedes §1j’s earlier implicit permission to return any error without declaring it.

Malli’s `:or` keeps child schemas, and the current analyzer walks canonical references (`src/seon/program.cljc:500`, `:518`), extracts each arity’s `:output` (`:609`), and stores shared return shapes plus `/output-refs` (`:637`, `:645`; `seon.fn.arity.edn:8`, `:13`). Extend this traversal at the **top-level result position** through `:or`, `:and`, aliases and refs. Do not count a facet merely mentioned inside a returned evidence map or a schema literal. Follow facet extension ancestors too: returning a payload-specialized read facet declares read-error capability as well.

At `program/contract-facts` (`program.cljc:648`) derive the union over arities, and project it through `fn/add-contract-facts` (`src/seon/fn.clj:2327`) for both full and incremental/admitted definitions. This is the same index-time fact pattern as `keywords-by-holder` (`fn.clj:401`), **not** a keyword scan of the function body. Add in `seon.fn.edn`:

```clojure
:seon.fn/error-facets
[:set {:seon.db/index true
       :description "Declared error-return facet names, derived from output contracts; value edges survive schema retraction."}
 :qualified-keyword]
```

Add the optional member to the function entity and exact-replacement ownership. A completed contract analysis with no facet datoms means none declared; use the existing analyzed digest/spec/arity facts to distinguish it from “never analyzed.” Schema-only changes invalidate and recompute affected functions’ facet edges; digest/contract projection and re-arming already own those dependencies. Never require a nonempty set just to prove analysis.

Expose `seon.fn/functions-returning-error` (db, facet → function symbol rows) and `seon.fn/error-facets` (db, function symbol → declared facet set plus positive analysis evidence/unknown), with thin `my.program` reads over the same facts (`src/my/program.clj:129`, `:183`; existing `seon.fn/functions-using`, `fn.clj:1691`). A query is simply `[?f :seon.fn/error-facets ?facet] [?f :seon.fn/sym ?sym]`; reverse it for one function. Keep refs out of this edge so deleting a facet declaration produces a detectable unresolved name.

### 5.2 Open choice: generic error propagation

| Choice | Guarantee | Cost / what we give up |
|---|---|---|
| **Finite domain declarations; one registry-derived generic union — recommended** | Ordinary functions name exact possible facets. Generic normalizer/propagator contracts reference proposed `:seon.error/result`, a projection-derived union containing base and all declared facets; their indexed set is honestly all current facets. | Generic helpers give coarse answers; projection changes recompile that union. No hidden implicit error arm. |
| Enumerate every facet even on generic helpers | Every output is an explicit finite form in source. | Repeated lists and broad edits whenever a facet is added; a declaration checker must enforce completeness. |
| Parameterized error-preserving output contracts | A generic helper preserves exactly the caller’s facet parameter. | Requires new contract/analyzer semantics; defer rather than claim current Malli contracts express this relation. |

`seon.fn/contract-findings` has now landed (`src/seon/fn.clj:1525`). Extend it later with **`:seon.fn.contract.finding/undeclared-error-facet`**: an observed returned error matches facet F but F is absent from the declared output set, or body/return-flow analysis proves such a return. Include function symbol, arity, facet and body span or fault occurrence. A callee’s presence in the call graph alone does not prove its error reaches this function’s return; uncertain dynamic flow is explicitly unknown, not a finding of certain violation. The output wrapper records actual undeclared facets; it must not silently bless them because a broad base arm or open map also validates.

## 6. Dependency order, ownership, estimates and proof owed

These are engineering estimates in focused hours, not measured runtimes or lane launches. Each owner lands schema and consumer changes together. Canonical regressions use the complete `seon.test-support` database fixture, real Datahike/SCI where relevant, explicit projection/custody, armed public/private contracts, and bounded positive assertions. No test was run for this document. The orchestrator owns the cold/platform gate and live proof.

| Slice, in dependency order | Proposed exclusive file ownership | Estimate | One class regression / later default proof |
|---|---|---:|---|
| Schemas and extension | `schema.clj`, `schema/form.cljc`, base/occurrence resources; domain resources reserved until schema owner releases | 8–12 h | A read+turn error validates and every inherited field becomes a datom; no-facet base stays valid. Inspect the canonical registry after reset. |
| Declaration checker | `schema/internal.cljc`, declaration expansion/admission owner, their tests; serialize with prior owner | 4–6 h | An extension dropping a base requirement refuses with exact member; two overlapping facets are accepted. Query findings on default. |
| `error?`, `diagnostic`, `facets` | `error.clj` and its tests, base projection bindings in `instrument.clj` | 5–8 h | Base recognition needs no message/marker; both facets discovered, nil/false actual leaf retained independently. Read one result on default. |
| Recorder, refs and graph failure | `error.clj` after previous owner releases; `flow.clj`, `cluster.clj`, `cluster/agent.clj`, `oversight.clj`; failure-state schema resources | 10–16 h | Fault+refs+failed state persist; affected graph stops, another agent and REPL answer; second agent stops despite repeated signature. Default: one authorized scoped failure visible in status. Database-down drill on isolated custody, never destroy default. |
| Base/facet render pairs | `render.clj`, `render/block.clj`, `render/web.clj`, error render functions; coordinated owning domain functions | 6–10 h | One stored read-in-turn error renders base/read/turn/agent blocks; humanized missing-key and actual leaf survive. **Observe the actual default debug page**, not just returned Hiccup. |
| Analyzer fact and query | `program.cljc`, `fn.clj`, `my/program.clj`, `seon.fn.edn`, `seon.program.edn`, analyzer tests | 5–8 h | Output alias/union/conjunction produce exact facet edges; nested evidence does not; schema edit updates edges. Query both directions on default. |
| Constructor migration by resource groups below | One namespace group at a time, complete source/resource/test triples | 18–30 h total | Parameterized class regression per distinct domain behavior; every migrated constructor satisfies its declared facets, ordinary mistakes never stop a graph. Query remaining obsolete declarations/uses = zero. |
| Predicate consolidation B1 | `db.clj`, `operator.clj`, `plan.clj`, `call_preparation.clj`, `note.clj`, `cluster/message.clj`, `render/ns.clj`, `instrument.clj`, `schedule.clj` | 2–4 h | One armed class regression sends a base+facet error through all nine former predicates; it never becomes a row. Verify on the adopted program. |
| Contract campaign | Domain owners below; `contract-findings` owner coordinates its own files separately | Domain-sized, 2–6 h per group before new findings | Each boundary exposes its real result shape and facets; body read failures short-circuit. Real stored findings and rendered fault evidence, not missing-signal “green.” |

Proposed constructor ownership groups (assignment only; **no launch here**): database/store/schema (`seon.db`, `schema.*`, `store`, `blob`); program/instrumentation (`fn`, `program`, `instrument`, `reconcile`); SCI/evaluation (`sci.*`, `eval.drive`, `cluster.reply`); agent/turn/effect (`agent`, `cluster.agent`, `turn*`, `effect`, `schedule`, `context`); boot/operator/config (`boot`, `bootstrap`, `env`, `config`, `operator*`, `cluster.*` excluding prior groups, `dev.mcp*`, `artifact`); provider/prompt (`ai*`, `cluster.prompt`); render/print/search (`render*`, `print`, `search`, `problems`); agent surfaces (`my.*`, `message`, `note`, `plan`); test (`test*`). Each owns the same-named schema resources, source and focused regressions. Shared `error.clj`, `fn.clj` and render entry points are serial ownership transfers, not concurrent edit targets. The running contract-findings and writer/turn lanes keep their present ownership.

**One reset:** prepare the complete resource/consumer batch and pass isolated snapshot checks before its single orchestrator-owned default reset/adoption. Sequential slices above are dependency/commit order, not permission to publish incompatible partial schemas to the live shared tree. Prefer one isolated checkout for the coordinated batch; another lane’s dirty source is excluded, not repaired. Data is disposable; do not build marker migration code or a compatibility error family. After the reset, run the real fault/render/status/query proof once against the same adopted source commit. Source-only changes after that must not require another schema reset.

The serious campaign follows the triage’s writer/read consumers, turn/effect settlement, acquisition and supervision before broad private contracts. Keep the easy-first pool separate: pure identity/path/formatting helpers whose input/output are already declared and that cannot change custody, writer outcome, money, graph lifecycle or fault recording. The [critical triage](../research/critical-findings-triage-2026-09-17.md) remains the schedule’s class evidence; this plan supplies schema prerequisites, not a rival ranked backlog.

## 7. Delivery boundary

Design evidence is file/EDN-reader inspection, not live validation. Source review ran across HEAD `464b79bdd` through `dc63e6ebf`; the 338/65 EDN census was reproduced during this delivery. The shared tree advanced during study; line anchors name the observed function as well as the line so implementation can relocate it. In particular the writer extraction, contract-findings and cluster fault seams moved while their owners worked. Foreign boundary: uncommitted operator/hook/cluster/test edits and concurrent writer/program commits were neither edited nor evaluated. No JVM, tests, MCP, lane launch, message to a lane, or lifecycle operation was used. Implementation proof in §6 is owed, not claimed.
