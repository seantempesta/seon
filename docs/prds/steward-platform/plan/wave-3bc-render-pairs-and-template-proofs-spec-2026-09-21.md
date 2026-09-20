---
type: plan
status: launch specifications — wait for the prerequisite and held-path releases below
created: 2026-09-21
tags: [steward-platform, namespace-agents, render, templates, wave-3]
---

# Wave 3b/3c — render pairs and five opening proofs

**Guarantee:** task-linked facts produce actionable, executable openings
through the existing render system; five recorded fixture openings and five
recorded default openings demonstrate that guarantee. A template is the
task's linked data and the render pairs it selects. There is no template
entity, template registry, prompt constructor, or second recursive renderer.

Give each implementation lane this document's common launch contract,
priority/release table, complete selected lane section, and the cited
prerequisite landing notes. The blockquoted assignments and their referenced
tables are the verbatim launch specifications. Do not launch from a table row
alone. These are two sequential assignments, not permission to edit held
paths simultaneously.

This document is the sole output of the bounded design assignment. Its
2026-09-21 date is the requested work-item date. The evidence is a dated
shared-checkout observation, not a claim about an immutable clean HEAD:
the initial inspected HEAD was
`99224deb6e7000e1a8a38dd7d5411545c0216c3b`, on `steward-platform`;
concurrent edits existed. Appendix A records every inspected schema
resource's content digest and declaration coordinates. Re-derive the census
after prerequisite landings; neither its counts nor today's line numbers are
a maintained roster.

## Authority and prerequisite order

Read end to end before launch:

- [wave-2-contracts-spec-2026-09-21.md](docs/prds/steward-platform/plan/wave-2-contracts-spec-2026-09-21.md):
  the common launch contract and campaign ownership.
- [namespace-agents-plan-2026-09-19.md](docs/prds/steward-platform/plan/namespace-agents-plan-2026-09-19.md):
  §2 wave-3 table, §§3/6/8 rulings, and later corrections to the audit.
- [context-templates-and-render-pairs-2026-09-19.md](docs/prds/steward-platform/research/context-templates-and-render-pairs-2026-09-19.md):
  the complete audit, especially §2 mechanism, §3 authoring, §4 conversation,
  and §5(b–c) ranked gaps and teaching surface.
- [wave-3a-task-family-spec-2026-09-21.md](docs/prds/steward-platform/plan/wave-3a-task-family-spec-2026-09-21.md):
  the task shape, source/status pair, generic concern expansion, opening
  consolidation, trigger and completion proofs.

Read AGENTS §§2.4/3/5 and lane rules 11–16; the vocabulary rows for render
function, AI/HTML, block, history and shown text; and
[turn PRD §§13–15](docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md).
Read the active [roadmap](docs/prds/context-generation/plan/README.md),
the relevant current checkpoint in its
[working edge](docs/prds/context-generation/plan/unsettled.md), and the
actual prerequisite landings. Source owners are listed below; read complete
owners before editing, not just the coordinates.

**Audit correction:** the named 671-line research file has no §11.
The requested “audit 6 §11” resolves here to its actual ranked list in
§5(b), with §3/§5(c) for authoring. Its old “invisible to the worker”
conclusion is qualified by the plan's later trace of
`seon.issue.opening/source` in the identity renderer and the
`:bare`/`:evidence-first` dial. Do not use that historical inference as
proof of today's output. 3a owns removing that parallel opening path after
byte parity; 3b consumes the resulting one path.

| Order | Release required | Deliverable / guarantee |
|---|---|---|
| Upstream bridge | Compiled projection/contract generation and publication owners release the relevant resources and loaded consumers; reset/adoption proved by the orchestrator | No renderer builds its own schema compiler, registry or generation cache. |
| Wave 1a and 1b | Landed error observation/occurrence shape and immutable test run/member/failure ownership; inspect actual landings | Render the new facts; never revive kind stamps, old test reports or mutable latest-result authority. |
| Wave 2 | Relevant render/error/message/schema/test contract campaigns and 2a debt admission release | New helpers have complete armed contracts; no temporary exemption for renderer work. |
| 3a | Task family, its AI/HTML and status-view pair, consolidated opening, reached-entity declared concerns, actual message/task trigger and task acceptance evidence are landed | 3b adds missing evidence pairs only; 3c exercises this exact shape. |
| **3b** | All intersecting paths released; coherent projection available | Ranked missing pairs, one derived conversation unit, discoverable authoring/preview through existing authorities. |
| **3c** | 3a + 3b fixture proofs pass; orchestrator cold/platform verdict and adopted default identified | Five fixture-first/default-second actionability proofs, exact bytes and positive completion evidence. |

The historical wave-3 table calls 3c an Opus driver. This launch selects a
**Codex gpt-6-astra high driver**: it must judge evidence and write a small
repeatable proof harness, which is implementation. It therefore respects
the no-Opus-implementation ruling. An Opus read-only review is optional
owner work, not a dependency and not a nested delegation by this lane.

## Common launch contract — include with either assignment

> Work in /Users/sean/src/seon, branch steward-platform. Read this common
> contract, priority/release table, your entire lane section and all named
> authorities. Apply data-oriented-clojure, datastar-web-ui, repl and
> clojure-testing; apply data-modeling and datahike before schema or writer
> work. Read the vendored seams below before relying on their behavior.
>
> You are not alone in this shared tree. Preserve unrelated edits and
> untracked files. Execute the bounded assignment directly, without
> delegation. Own only your named paths and landing artifacts. Before
> editing an intersecting path, get its release from the orchestrator;
> never resume, message, operate, reset or repair another lane's session.
> A reported owner is not permanently protected: recheck the working edge
> and dirty paths at launch and after a landing.
>
> Start implementation with a small read-only probe of the running default,
> using explicit cluster/database custody. Check source adoption freshness.
> Honor the current publication pause. Missing/degraded MCP is reported
> immediately and recorded in the implementation landing against its existing
> issue, not hidden by a hand-written prepl client. A probe exercises either
> a hot-reloaded Var, in-place development adoption or a new fork: name which.
> Never stop, restart or refork default, resume publication, or create a
> second ordinary development cluster. RESET NEEDED is a handoff to the
> orchestrator's batch, not a migration assignment.
>
> Use the sealed acquired projection and existing read/write/render owners.
> Every function, private included, has its real complete Malli contract;
> every arity and every returned error facet is covered. No generic error
> umbrella, :any/:some/:map/nullable escape to make a refusal disappear.
> A truly polymorphic value-render boundary retains its proven existing
> declaration. Open maps, qualified keys and actual symbols remain the rule.
> Pulled shapes derive from the stored schema and selector; do not mirror
> stored refs with hand-written pulled-map schemas.
>
> AI source pairs return :seon.render/source (or an already admitted source
> block shape where the owner requires it), with a thinking comment and
> executable status read. They never return a paragraph disguised as source
> through (identity "summary"). Terminal status-view pairs may produce text
> and Hiccup. Keep those contracts distinct: a generated read's result must
> not select its own source producer recursively.
>
> Link facts on the agent/task and let declarations choose the forms.
> The task pair and generic opening consolidation belong to 3a. There is
> one walk and one value renderer. AI presentation fits only inside AI render
> functions or the AI value renderer. No fit, substring cap or summarizer in
> the walk, history, request assembly, HTML, recorder or proof driver.
> HTML has no presentation clipping. Query-work bounds and evaluation
> deadlines stay independent and are reported honestly.
>
> Use the canonical with-database fixture, explicit environment/projection,
> fixed profiles, program-fn-row, seed-cluster!, apply-config! and transacted!
> where applicable. Real SCI, real Datahike, armed contracts. No hand-rostered
> schema or fake selector. Retain positive population/execution controls.
> Absence of the expected subject, selected pair, evaluation or terminal
> verdict is a failed/unknown proof, never green.
>
> Iterate foreground with one JVM at a time:
> bin/test-fast --paths <every owned changed source/resource/test path> -- <named namespaces>.
> No cold bin/test gates, --all, --full, slot/silence overrides, parallel JVMs,
> worktrees or test runners hidden in background shells. A foreign failure
> does not end independent owned work. Use HEAD plus owned paths; at an
> overlay refusal record the exact omitted/held path and continue work
> that does not depend on it. Never test an unreviewed foreign half-edit.
> The orchestrator owns cold --paths and --platform proof.
>
> Before a code commit, serially prove the owned namespaces load with
> clojure -M -e "(require '<owned namespace> …)" on the coherent owned
> snapshot. If that snapshot cannot be formed, name the load boundary,
> never claim a green load. Await exact facts under the declared event
> backstop, not sleeps or quiescence. Release every resource on all exits.
>
> A changed resource and its loaded consumers land in one publication.
> Retiring a public Var and converting every caller is one loadable slice.
> Path-limited commits only: git commit --only -- <explicit owned paths>.
> Never broad staging, reset, restore or checkout of shared files.
>
> Record exact commands, executed/unchanged/failed counts, selected producers,
> source and shown-text bytes, estimated tokens, bases/digests, changed paths,
> unsupported cases, reset requirement and cold proof still owed in the
> named landing. Browser paint requires its own observation.
> Stop after landing or at a genuine decision with exactly three priced
> options, simplest viable constraint first and recommended. Do not turn a
> failed proof into a private prompt repair or an unbounded campaign.

## Mechanism and dependency ledger

These coordinates are the inspected checkout, not a promise that a concurrent
edit cannot move them. Pin actual source digests again in implementation landings.

| Authority / source | What this design relies on |
|---|---|
| `src/seon/render.clj:257`, `:270`, `:461`, `:1442` | Declared return contracts identify source producers. Top-level selection is explicit value, explicit request, namespace, schema, floor. Render calls capture generated source; the floor is a last resort, not proof a missing declaration is harmless. |
| `src/seon/render.clj:339`, `:1116`, `:1222` | Schema selection considers fitting shapes; nested value projection already consults explicit/schema producers, carries a recursion set, suppresses source-producing AI functions and walks children. Namespace selection at the top is not automatically a nested per-node namespace search. |
| `src/seon/render/walk.clj:91`, `:100`, `:121`, `:253`, `:483`, `:535` | Declared concerns and installed component/ref relationships inform bounded graph acquisition. Acquisition already recursively visits connections with visited identities and distance/work bounds; explicit root selectors avoid accidental wildcard component expansion. |
| `src/seon/render/walk.clj:702`, `:756` | At this snapshot declared-acquisition reads **the root's** units and synthesizes derived/reverse-form/component concern blocks. It does not recursively execute derived-query results or expand every reached entity's declared units. 3a owns the bounded reached-entity correction. |
| `src/seon/render/block.clj:61` | surface-id constructs the stable HTML morph address. This namespace is not a recursive block composer; its small implementation must not be read as a second renderer. |
| `src/seon/render/value.clj:401`, `:550` | Nested values select a fitting pair before structural value printing. prepare calls print/fit only for AI output. Preserve HTML's full values; do not transfer AI-profile limits to it. |
| `src/seon/render/hiccup.clj:94`, `:519` | Hiccup validation and recursive markup serialization/escaping. Markup recursion is not entity discovery. |
| `src/seon/repl.clj:91`, `:142`, `:213`, `:366`, `:447`, `:463`, `:480`; `src/seon/render/walk.clj:978` | Source grammar, saved shown text, one evaluation emission, chronological history, live-result HTML and saved-text fallback. Never execute historical source to display history or re-fit saved shown text. |
| `src/seon/print.cljc:442`, `:455`, `:982`, `:1302` | Existing requery form, omission rendering/value, and profile fit. Count, path, next offset and requery refer to actual data coordinates; token estimates do not replace character/member coordinates. |
| `src/seon/error.clj:1723`, `:1752`, `:1819`; occurrence/evidence/location schemas | Observation selector and latest-fact reconstruction are existing owners. Follow the landed 1a relations, not the older occurrence blob layout or a fabricated component identity. |
| `src/seon/render/test.clj:15`, `:40`, `:67` | Existing test evidence acquisition and failure HTML are the formatting seam. 1b's recorded-result/run/member authority wins over lingering report identifiers. Extract/reuse this formatter, do not add a rival test report. |
| `src/seon/cluster/message.clj:149`, `:171`, `:322`, `:411`, `:429`; `src/my/message.clj:1` | Outside inbound currently creates an unparented message; delivery adds caused-by only from its supplied trigger. Individual message/inbox pairs and ordering exist. A renderer cannot infer reply lineage from about or timing. |
| `src/seon/render/ns.clj:844`, `:889`; `src/seon/schema.clj:3558` | Function rendering and schema-definition reads already exist. Add schema/file presentation beside those owners; do not add a schema browser registry. |
| `src/seon/schema.clj:1541`, `:1650`, `:1811`; `src/seon/bootstrap.clj:21` | register! is a declaration/candidate seam, with canonical publication/admission checking renderer contracts. Reader operation recognition at `src/seon/sci/reader.cljc:347` and evaluation delta at `src/seon/sci/eval.clj:2274` make the ordinary top-level SCI declaration durable. Calling the JVM collector alone does not prove durable agent installation. Help is the existing teaching surface. |
| `reference-code/malli/src/malli/core.cljc:2194` | Function schema info owns input/output and arity bounds, including varargs. Use the acquired compiled schemas; no source regex for producer classification. Gitlink `606083c5c5b388e84d169c7080af33ed3ec242ae`. |
| `reference-code/datahike/src/datahike/pull_api.cljc:16`, `:238`, `:315`, `:323`, `:346` | Default many limit 1,000, recursive seen-id result and implicit component expansion. A complete evidence read uses explicit selectors/limit handling under Seon's work bound; a short pull is not proof of completeness. Gitlink `e11845bac78e1241bca0766ddc07d978bd63d74a`. |
| `reference-code/sci/src/sci/core.cljc:260`, `:331`, `:345` | intern/init/fork supply live candidate contexts and copy-on-write Vars. Preview uses the existing candidate context and deadlines; no serialized context or separate evaluator. Gitlink `fcbd8862800e638dc0f8f5521111f999279cbcd2`. |

Archaeology inspected the render-owner history, including `59e51e221`,
`2a59e5e11`, `f1ce65c02` and `f5716e841`, and the audit's prior opening
records. Historical pair counts and old issue/report names are observations,
not specifications to port.

### The recursive rendering question — answered, with three priced options

**Nested component values already recurse. Declared derived queries do not
automatically recurse through their returned entities as new opening blocks.**
The graph acquisition recursion, nested value projection and Hiccup recursion
are three different existing operations. Only the first two select evidence
or render pairs. The root-only declared-concern gap is real, but is already
assigned to 3a. Recursively rendering a nested value does not mean evaluating
source emitted at every nested node.

| Option | Guarantee | Estimated incremental cost | What we give up |
|---|---|---|---|
| **1. Existing walk + 3a's bounded concern expansion + nested value pairs (recommended)** | Linked task concerns become opening source once; nested complete values choose pairs or the ordinary floor; existing visited/output identity/work bounds hold. | **0.5–1 day** in 3b for composition/selection regressions, included below; no new recursion mechanism. | No automatic conversion of every derived-query result into another source-producing opening subtree. |
| 2. Extend the same walk to recursively admit declared derived-query results | Query results can introduce further declared concerns under one explicit order/visited/work contract. | **3–5 additional engineering days**, plus renewed 3a integration and read-evidence review. | Simplicity and the current predictable source order; every result must expose identity and continuation semantics. |
| 3. General recursive block/source composer | Arbitrary nested producer output becomes a new compositional evaluation tree. | **1–2 additional weeks**, cross-owner contracts for identity, origins, termination, repeated reads and HTML morphing. | The one-mechanism guarantee unless it replaces substantial existing walk/value code; no present proof requires this. |

**Decision for these launches: option 1.** It satisfies the owner's nested-data
question with the actual mechanisms. Options 2/3 are priced alternatives,
not deferred implementation hidden inside 3b. A demonstrated failure that
cannot fit option 1 returns to the owner with concrete recorded bytes before
production changes.

## Held-path coordination — verify immediately before launch

This is a dated coordination observation, not a permanent ownership registry.
The design lane contacted no other session. Names below describe owner work
from the inspected working edge and dirty paths; they do not attribute a
failure's cause.

| Intersecting paths / work | Release and permitted action |
|---|---|
| `src/seon/schema.clj`, `schema/datahike.clj`, `schema/edn.clj`, `schema/internal.cljc`; bridge resources and canonical fixture | Schema bridge/walker work was in flight. **Neither lane owns these files.** Use landed public schema/read/admission functions; a missing capability is an upstream dependency with a minimal example. |
| `src/seon/cluster.clj`, `cluster/source.clj`, `src/seon/fn.clj`, `script/seon/fresh_operator.clj`, `seon.source.edn` and publication tests | Publication/dissolution work held dirty paths. Do not repair publication, pause flags, source identity or adoption in 3b/3c. |
| `src/seon/test/runner.clj`, `test/accretion.clj`, their resources, `test/seon/test_support.clj` | Test-system/bridge owners held these. Reuse the released recorder and fixture; no runner, arming, cache or helper rewrite in either assignment. |
| `src/seon/error.clj` and error schemas; `src/seon/cluster/message.clj` and message schema | 1a and relevant wave-2 campaigns may overlap. Read landed error facets and release before any 3b edit. Do not restore removed error/kind or report contracts to satisfy old tests. |
| `src/seon/render/walk.clj`, `src/seon/cluster/agent.clj`, `src/seon/task.clj`, task and agent schemas, task/opening tests | **3a owns generic opening consolidation and concern expansion.** 3b may edit only the released agent-units declaration to replace the inbox concern, and add its pair tests. No task pair/walk/identity-source implementation in 3b. |
| `src/seon/render*.clj`, `src/seon/render/`, `src/seon/repl.clj`, bootstrap/help and related resources | Wave-2 render/turn/IO contract sweeps must release intersecting files. 3b may change only its enumerated presentation owners; generic renderer changes require a separately released owner assignment. |
| 3b's resources/pairs and 3c's proof fixtures | Sequential. 3c consumes a fixed landed/adopted basis; a failing opening feeds back to 3a/3b. 3c never patches a held pair or injects custom source to pass its proof. |

At the design snapshot, foreign dirty files also included publication/bridge
research notes, issue notes, and new publication/lineage/parity tests.
They were preserved. A dirty path is not itself proof its owner caused a
failure. Record the exact failing command and envelope before attribution.

## 3b — ranked missing render pairs and discoverable authoring

**Guarantee:** every ranked concern below has an executable AI projection and
complete HTML through the existing selection mechanism; a linked task/fault/
failure opening is actionable under its declared profile; an agent can
discover, preview and author a pair without a second registry.

### Launch verbatim — gpt-5.6-sol low; 3–5 engineering days

> Implement wave 3b using this common contract and the complete 3b section.
> Consume 3a's landed task source/status pair and generic opening expansion.
> Do not reimplement either. Start by recording selected producers and exact
> opening bytes for the ranked subjects on the canonical fixture, then make
> the smallest missing pair changes in the existing owners.
>
> Work in this order: schema; occurrence/evidence/location; test failure;
> source file; conversation; discoverability and composition regression.
> A release delay does not authorize editing a held file: implement and
> verify independent released pairs and record the remaining boundary.
> Model choice is sol low because the data/owner/acceptance shape is fixed.
> A genuine new renderer/admission decision returns three priced options
> before edits; do not spend the estimate designing another render system.
>
> Own the existing presentation owners src/seon/render/ns.clj,
> src/seon/render/test.clj, src/seon/error.clj (new observation presentation
> functions only), src/seon/cluster/message.clj (conversation reads/pair
> only), and src/seon/bootstrap.clj (one help example only), after release.
> Own the relevant pair/status-view declarations in
> resources/seon/schemas/seon.schema.edn, seon.fn.file.edn,
> seon.error.occurrence.edn, seon.error.evidence.edn,
> seon.error.location.edn, seon.test.failure.edn, seon.message.edn,
> seon.render.edn only for genuinely shared render request/view contracts;
> keep domain status-view contracts beside their existing domain declaration.
> There is no seon.render.ns.edn or seon.render.test.edn in this snapshot;
> do not create either as a duplicate contract registry. Own only the released units entry in seon.agent.edn
> for the conversation replacement.
>
> For authoring discoverability own new src/my/render.clj and
> resources/seon/schemas/my.render.edn, plus the relevant bootstrap help
> line. Thin means delegate to existing selection, candidate SCI and
> declaration teaching; no schema engine or SCI runtime ownership.
> Inventory existing symbols first and reuse one if it has landed.
>
> Own test/seon/render/entity_pairs_test.clj for pair regression
> consolidation, new test/seon/render/template_pairs_test.clj for the
> canonical task composition matrix, and new test/my/render_test.clj for
> the thin authoring surface. Do not modify test_support.clj or the
> test runner to make the fixture admit malformed rows.
> Record the landing in
> docs/prds/steward-platform/research/wave-3b-render-pairs-2026-09-21.md
> and exact opening/HTML evidence beneath
> docs/prds/steward-platform/research/wave-3b-render-pairs-2026-09-21/.
>
> Complete the ranked matrix, contract/selection/HTML/profile regressions
> and authoring round trip below. Run the named fast suites serially,
> prove owned namespaces load, make path-limited commits and report cold
> and post-adoption/browser proof owed to the orchestrator. Stop after
> landing. Do not silently repair 3a, 1a, 1b, bridge or wave-2 ownership.

### The pair contract and read-form grammar

For the named source producers use an exact `:seon.render/source` return,
including private composing helpers' real contracts. Build forms with
`seon.repl/source-text`; never string-concatenate an unescaped identity or
parse a symbol with a regex. The generated source has this grammar:

```clojure
;; I should inspect <specific evidence> before <the next action>.
(<ordinary status reader> <actual subject/read arguments>)
```

The comment teaches the decision; the form reads its data. No generated
side effects, no unevaluated prose, and no `(identity "human summary")`.
Text produced by a **terminal status-view pair** is the evaluated result,
which is different from source generation. Those result shapes must not
match the source entity's identity/required-key shape again. Use exact
read-view envelopes and existing derived pulled contracts, as 3a does for
task status; do not relabel stored entities or duplicate their schemas.

**Component source addressing:** evidence/location often have no identity.
Never mint one. In an entity opening derive the existing durable observation
root through the landed component relation. A component status source
accepts that root's stable lookup and reads its corresponding component
group; it need not pretend one attribute uniquely identifies one component.
The examples below use an occurrence id when that is the landed owner.
If 1a makes the observation/signature the owner, emit that existing lookup
instead; record the actual ownership query and exact bytes. The source
reader uses one supplied database value, indexed ownership and the existing
observation selector; it refuses a missing/ambiguous owner explicitly.

For a component appearing only inside a live evaluation result, the value
renderer already has the root result handle and cursor. Reuse its existing
requery expression as the status reader's value argument, rather than an
unscoped numeric eid, an invented durable handle or embedding the full
offending value in generated source. The status readers have separately
contracted database-root and actual-component-value arities. Nested AI value
projection suppresses source producers: the containing terminal status view
must invoke the same component presentation function, so the group is still
readable without executing a second source program. Anonymous values remain
total through ordinary value rendering; lack of a durable identity is not
a reason to throw or store one.

### Ranked pairs — exact source intent and HTML acceptance

The audit's first two priorities (task pair and linked concern expansion)
are **3a**, already prerequisites. This table is the remaining 3b order.
Function names shown as additions are the intended owner-local API; reuse
the exact equivalent if it lands first, and record the substitution.

| Rank / declaring schema and observed gap | AI source form and required status data | HTML |
|---|---|---|
| **1. Schema** — `resources/seon/schemas/seon.schema.edn:96`, `:seon.schema/schema` | New `seon.render.ns/schema-ai` emits `;; I should inspect this schema's definition and its users before changing it.` then `(seon.render.ns/schema-status {:seon.schema/key :actual/key})`. The status uses schema-definition and program reads for canonical form, doc, references/users, current test evidence and actual source location if recorded. Missing source/example is explicit unknown, never invented. | `schema-html`: key and doc; readable complete form; links to recorded declaration, relevant users/tests and their current verdicts. Exact full literals survive HTML; no AI-profile elisions. |
| **2a. Occurrence** — `seon.error.occurrence.edn:16`, `:seon.error.occurrence/occurrence` | New `seon.error/occurrence-ai` emits `;; I should reproduce this fault at its recorded operation before changing the code.` then `(seon.error/occurrence-status {:seon.error.occurrence/id "actual-id"})`. Show count/first/last observation, process/agent/turn when present, layer/operation/fn, expected/offending values, location and evidence, and linked test/task facts. Acquire the landed complete observation, not only an occurrence message or blob digest. | `occurrence-html`: concise occurrence summary with full expandable evidence/location, navigable actual provenance and task/test links. Expansion is a display affordance, not data clipping. |
| **2b. Evidence** — `seon.error.evidence.edn:7`, `:seon.error.evidence/entity` | New `seon.error/evidence-ai` emits `;; I should compare the recorded evidence with the violated requirement.` then `(seon.error/evidence-status {:seon.error.occurrence/id "actual-owner"})`, or the actual landed root lookup. Group by recorded attribute; preserve every scalar or projected value and its omission evidence. No guess from attribute names about the error class. Value arity accepts the existing root/cursor requery result. | `evidence-html`: attribute/value entries in a readable table or definition list, nested values delegated to the existing value renderer, projections and omissions plainly distinguished. Reuse for the occurrence status view. |
| **2c. Location** — `seon.error.location.edn:4`, `:seon.error.location/entity` | New `seon.error/location-ai` emits `;; I should inspect the exact recorded location of the failure.` then `(seon.error/location-status {:seon.error.occurrence/id "actual-owner"})`, or actual root lookup. Order segments by their declared ordinal, retain declared length/omission, and expose the exact path and source position where present. Never treat a set's iteration order as path order. Value arity uses the existing requery expression. | `location-html`: ordered path/segments and source links only when grounded; missing segments/omission stay visible. No fabricated file:line link or concealed path suffix. |
| **3. Test failure** — `seon.test.failure.edn:35`, `:seon.test.failure/failure` | New `seon.render.test/failure-ai` emits `;; I should reproduce this assertion against the tested program before repairing it.` then `(seon.render.test/failure-status {:seon.test.failure/id "actual-id"})`. Return owning test symbol, immutable run/member provenance and tested basis, contexts, expected/actual/message, file/line, and present rerun/unchanged confidence. Read through 1b's authority, never a new latest-result cache. | Public `failure-html` reuses/extracts the current formatter: test/run links, complete expected/actual and contexts, accurate location. Blob-backed evidence gets the existing retrieval path with explicit availability, not a digest presented as the value. |
| **4. Source file** — `seon.fn.file.edn:9`, `:seon.fn.file/file` | New `seon.render.ns/file-ai` emits `;; I should inspect this indexed file and the declarations relevant to the task.` then `(seon.render.ns/file-status {:seon.fn.file/relative-path "actual/path.clj"})`. Read the indexed file identity/digest and linked declaration spans/functions/tests/citations from facts. Scope by the linked task's facts when supplied, preserving explicit evidence of omitted unrelated declarations. No filesystem read as a replacement for program facts. | `file-html`: path/digest, actual indexed source/declaration spans and navigable relevant declarations/citations/tests. A missing source payload is reported, not filled from a different checkout basis. |
| **5. Conversation** — add derived `:seon.message/of-agent` in `seon.message.edn`; individual `:seon.message/message` at `:61` already has a pair | New `seon.cluster.message/render-thread-ai` emits `;; I should read this conversation and its outstanding request before replying.` then `(seon.cluster.message/thread-status {:seon.agent/id "actual-agent"})`. Derive threads and messages as specified below, with author/direction/content/subject and actual parent links plus handling/answering evidence kept distinct. | `render-thread-html`: chronological conversations, author and timestamp, full content, actual parent/root navigation, task/request links and explicit unknowns. Reuse individual message presentation; no duplicated message fact family. |

Every file coordinate in this table is relative to
`resources/seon/schemas/` where not fully written. Register a pair once on
the entity/read-view owner. Do not attach new pairs to every scalar field.
The existing error facet pair population must remain unambiguous; the
new occurrence/component functions cannot take over every generic error.

**Adjacent formatter cleanup is bounded:** remove old test-report references
inside the touched failure formatter in favor of 1b's landed facts and update
the entity-pair assertions that insist on no comments/identity-summary source.
Do not reintroduce `seon.test.report` because the older audit lists it.
Task citations are handled through 3a's source citation components plus the
file pair. New lint, maintenance, schedule, transaction or generic error
campaigns are outside 3b; record an observed gap against its existing issue.

### Conversation: one derived unit, truthful current relations

Declare `:seon.message/of-agent` as a **non-stored derived render concern**
following the existing derived-unit shape, with its pair and ordinary read
form. Replace the released agent units entry `:seon.message/_to` with it
once; retain the individual message pair and inbox API for callers.
Do not leave both opening units emitting the same inbox.

The query seeds messages involving the agent as sender or recipient,
follows actual `:seon.message/caused-by` parent/child edges within the
declared query-work bound, deduplicates identities, and orders current
messages using their recorded time/transaction and stable identity tie-break.
An explicitly incomplete traversal reports the bound and continuation.
A same-subject string, temporal adjacency or common recipient does not
establish a conversation edge. Roots with no parent remain independent.

Outside inbound currently does not supply caused-by
(`src/seon/cluster/message.clj:149`); agent delivery can add it from trigger
(`:171`). Prove both paths. 3b adds **no** reply-composer UI or invented
inbound parent assignment. Its guarantee is a truthful rendering of asserted
conversation relations, including an independent outside request and an
actual trigger-linked reply. If the recorded production path drops a
required existing trigger, report it to 3a/message ownership with the exact
envelope; do not infer a link in the renderer. A swept optional parent is
not proof there never was a parent; current rendering states current facts
and a history read can explain the past.

Handled is a settlement claim. Answered wake derives from transaction basis.
Neither proves the answer fulfilled the request. The conversation status
keeps these separate, and 3c judges actual answer content.

### Agent-authored pairs — discoverable, no installation shortcut

Teach **one** small example in existing help: discover the relevant namespace
with dir/doc, define contracted AI/HTML functions, preview, then submit the
full schema declaration through the existing agent declaration path.
The producer names are qualified symbols, not strings. Record actual help,
dir and doc bytes and prove the two functions are callable from real SCI.

The thin `my.render` surface adds **`my.render/preview` only**. It takes
the existing render request with its supplied context, value and explicit
candidate producer; it delegates to selection-inspection/render-call and
returns selected producer, source intent, output and contract diagnostics.
Use the existing candidate SCI context when previewing an uninstalled
definition. Do not fork another evaluator, transact, install a renderer,
or change another agent's context. Preview is not unrestricted execution:
the existing bounded render invocation and purity/admission rules still hold.

Installation is the ordinary top-level SCI declaration:

```clojure
(seon.schema/register! :example/entity
  [:map {:seon.render/ai 'example/render-ai
         :seon.render/html 'example/render-html}
   [:example/id :string]])
```

The example's actual fields/contracts are replaced by the canonical
fixture's own schema. The agent supplies the **complete authored form**;
no helper pre-reads a schema and patches two keys before the authority acts.
Producer functions are already contracted and admitted through ordinary
function declarations before this schema declaration.

**Source-grounded correction to the audit's suggested API:** a new ordinary
`my.render/pair!` wrapper around register! is not equivalent to the existing
declaration path. `src/seon/sci/reader.cljc:347` and `:360` recognize the
resolved top-level `seon.schema/register!` operation; `src/seon/sci/eval.clj:2274`
opens the registration delta only for that reader-owned schema row and
passes its evaluated form to canonical admission. A wrapper could update a
collector without admitting a durable schema row. The minimal launch
therefore teaches the existing write and adds only preview/discovery.
The plan requires a discoverable my.render surface and agent-authored pairs;
it does not require that suggested wrapper name. No reader/evaluator change
is needed to satisfy either guarantee.

Prove the complete round trip by rereading schema/program facts and
selecting the pair in another ordinary evaluation, not by inspecting the
collector. Preview-before-install is advice, never a substitute for writer
validation. Failed preview/admission leaves installed facts unchanged.
Missing HTML, unresolved producer, wrong AI return, input mismatch and
ambiguous selection are explicit negative cases; do not make up a private
producer prohibition beyond the existing public namespace discovery rule.
All functions remain callable under the program graph's actual admission.

If the released canonical declaration path cannot install this pair, return
the minimal failing top-level SCI declaration to its owner and continue
independent pair work. Do not create a second installer or “fix” it by
mutating the host collector. Pair-write sugar is excluded from this launch.

### 3b regression matrix and judging bar

Use the real canonical fixture and real SCI acquisition. The assertion is
selected behavior, not just “returns a string” or schema metadata existence.

| Regression class | Required evidence |
|---|---|
| Ranked pair selection | Every row has a populated positive fixture and AI/HTML selected-producer evidence; returned AI parses into thinking comment plus actual status read. Execute the read in SCI and inspect its complete envelope. Distinguish intentional terminal value fallback from accidental source-stage floor. |
| Task composition | Root at the agent, not at the task. Real 3a task links schema/function/test/failure/error/file/message evidence; reached concerns appear once in declared order under both opening dials. No identity-renderer injected task source remains. Task pair itself remains 3a's owner. |
| Nested components | Occurrence containing evidence/location/projection, including a repeated/shared reference and an omitted query branch. Actual component rendering occurs; source producers do not loop through status results; visited identity and block ids remain stable. No new component identity. |
| Profile — task/fault/test failure | Use three positive large cases: a rendered task, a fault occurrence and an assertion failure. Each contains a long string, many children and deep data; use realistic fixed small and ordinary profiles. Assert per-evaluation shown-text token estimate fits when the profile can represent its minimum evidence, omitted count/path/next-offset and executable requery. Do not impose a new aggregate history/prompt clipping budget. |
| Honest floor and query cut | An impossibly small presentation profile reports the existing floor hit; never assert a false hard budget promise. A query-work cut is separately named and is not miscounted as presentation omission. A >1,000-member evidence relation proves no silent Datahike pull truncation. |
| Requery | Give each large result a real evaluation result handle or durable subject identity. Execute its elision's requery form against the same relevant data and recover the omitted sentinel at the reported path/offset. Count uses the actual coordinate unit, not estimated tokens. Truly unavailable root has a typed requery refusal. |
| HTML never clips | With the same tiny AI profile, the HTML pair still includes terminal sentinels in the long string, last child and deepest field of the **complete acquired value**. Validate Hiccup, serialize/escape it, inspect browser DOM after adoption. Explicit query paging/unknown is honest incompleteness; it is not permission to erase acquired content. |
| History and result lifetime | Saved shown-text bytes remain identical after a profile change and later linked-data mutation; refreshed reads append. Live HTML uses the actual complete object; with the live object unavailable it uses saved shown text and makes no claim of restoration. No re-execution or second fit of history. |
| Error composition | Multi-facet error plus occurrence/evidence/location selects the intended owner without ambiguity; the renderer stays total for missing/deleted subjects and unavailable blobs. No generic catch returns “healthy” or empty output. |
| Conversation | Outside root, trigger-linked reply, another independent same-about root, outgoing-only participation and a bounded parent chain. Exact identities appear once in chronological order; no grouping inference; handled/wake coverage/content fulfillment distinguished. |
| Agent authoring | Real SCI defines contracted pair, previews without writes, installs through authority, rereads facts and selects it. Invalid declarations are refused with no partial installation; another agent's private context is unchanged. Help/dir/doc expose how to do it. |

Choose assertions against the facts/results, not a copied implementation.
Reuse the existing fixture and lifetime regression helpers. Do not invent a
second renderer inside a test or use manual entity maps where canonical
helpers exist.

### 3b commands, landing and exclusions

First record the exact owned changed path list. Run, serially, with that
list substituted in each invocation:

```sh
bin/test-fast --paths <owned changed paths> -- seon.render.entity-pairs-test seon.render.template-pairs-test
bin/test-fast --paths <owned changed paths> -- my.render-test
```

Include existing affected message/error/value suites only when the change
reaches them; derive that reach from the current program graph and record
the actual selection. The orchestrator runs cold `--paths` plus
`--platform` after the coherent commit. Zero executed with recorded reuse
is reported as unchanged, not as freshly exercised renderer evidence; the
new positive cases must have recorded execution evidence.

Before committing, load actual owned namespaces, then commit their explicit
paths and landing artifacts. After orchestrator adoption, repeat one
task/fault/failure and conversation read and observe their HTML on default.
If reset/publication is pending, land with **fixture proof only; default
and browser proof owed**, naming the exact boundary. Do not claim a page
from a serializer or an adoption from file mtime.

**Must NOT build:** a task pair, task status, task lifecycle/trigger,
identity-opening injection, another generic walk, recursive source composer,
schema registry/compiler, private prompt template, report recorder,
conversation entity/subject-as-thread key, component identity, inbound reply
UI, result serialization, clipping outside the two AI projection owners,
new render permission system, or a maintenance/lint/general UI campaign.

## 3c — five template proofs, fixture then default

**Guarantee:** for each work class the recorded opening alone gives a capable
agent enough evidence and callable forms to make the intended next change
and verify it in one ordinary turn. Each proof has positive acceptance
evidence. An attractive summary without the subject/reproduction/action
facts fails. No proof claims a general autonomous model success rate.

### Launch verbatim — gpt-6-astra high; 1.5–3 engineering days

> Drive wave 3c using this common contract, the complete 3c section and the
> landed 3a/3b specs and evidence. You are a Codex driver, not an Opus
> implementation subagent. Execute directly; no child implementation lanes.
> High effort is for judging exact evidence and ownership, not a larger
> implementation scope.
>
> Own new test/seon/render/template_proofs_test.clj, and only a new local
> helper namespace test/seon/render/template_proof_support.clj if common
> capture code cannot remain in the test. Use canonical test-support; do
> not edit it. Own
> docs/prds/steward-platform/research/wave-3c-template-proofs-2026-09-21.md
> and its sibling artifact directory wave-3c-template-proofs-2026-09-21/.
> Store the reproducible live probe script there if one is needed.
> No production src or schema edits, no source-owner repairs.
>
> Read the actual opening before judging it. First produce all five cases
> on the canonical fixture with real SCI and armed contracts; then, after
> an identified orchestrator adoption, produce the five on default through
> the same ordinary opening path. Do not fabricate a live green from fixture
> bytes, a task-root-only walk or manual generated source.
>
> For each case record the exact input/task facts, selected pair trace,
> generated source, stored opening and shown text, program/basis/profile
> and completion evidence. Judge the opening before executing the repair.
> Read only the recorded opening plus the universal help/dir/doc protocol
> when making that judgement. Hidden source hints invalidate actionability.
>
> One turn may contain multiple ordinary SCI evaluations and a test check;
> it may not rely on a second outside clarification, private prompt addition,
> undeclared tool, or secret knowledge of the implementation. The repair
> runs only on the orchestrator-assigned trial declarations/tasks, through
> the admitted runtime writers. This is not permission to edit source files
> or implement wave-4 changesets, merge or export.
>
> Failed proofs return exact bytes and a minimal reproduction to 3a for
> data/links/opening/completion or 3b for selection/pair/presentation.
> If the underlying test/error/admission owner fails, name its existing
> upstream issue. Preserve the failed recording and continue independent
> cases. Never fix a proof privately by injecting a read, forging a test
> result or weakening an acceptance predicate.
>
> Run the bounded fast harness, prove its namespace loads, commit explicit
> owned paths and report each of the ten verdicts and the cold/default/
> browser boundary honestly. Stop after landing, or at a genuine decision
> with exactly three priced options.

### Proof setup and capture contract

Use 3a's task family and helper writers, not five hard-coded template types.
A proof's name is an artifact/test label only. Task data contains its real
subject and the relevant declared links (functions/tests/errors/keys/files/
messages/members); namespace responsibility is context, not a second route.

Use a finite dedicated trial agent per proof. The orchestrator supplies the
allowed default subjects and the adopted source commit before the live pass.
Do not mutate or wipe Juniper or another running agent's history. Acquire
and release trial graphs through their owner and stop the graph before any
fixture cleanup of its facts. For fixture construction use canonical helpers
and the ordinary trigger/task/agent opening paths. For default use the same
public owners with explicit custody; no fixture-only write path.

The harness supplies deterministic admitted virtual replies where the real
turn API supports them. Record that fact: this verifies actionable context
and turn execution, **not** a paid provider/model outcome. No paid provider
calls are needed for this assignment. Do not bypass the ordinary evaluation,
settlement or recording seam with direct “passed” datoms.

The contract-addition case must select an actual allowed baseline debt
declaration, or a canonical fixture whose admitted historical baseline
contains that debt. Do not create an uncontracted function after 2a's
ratchet or exempt a new one just to get a red. On default choose an existing
eligible debt subject released by the orchestrator. If none exists, record
that precise unavailable case and request a legitimate target; zero debt is
not a successful contract-addition proof.

Each environment/case directory contains:

- `input.edn`: exact task/subject identities and admitted fixture or trial
  inputs, with relevant linked facts and the expectation declared before
  execution. Use the real namespaces/identities from the run, not placeholders.
- `opening-source.clj`: exact generated source in ordinary order, before
  evaluation; comments are preserved.
- `opening.txt`: exact stored evaluation/history rendering used as the
  opening. No wrapping, normalization, cleanup, manual prepend or redaction
  masquerading as original bytes.
- `selection.edn` and `manifest.edn`: pair selection by unit, agent/task,
  branch/cluster, source commit/program/input digests where available,
  tested basis and recording basis separately, profile, opening dial,
  UTF-8 bytes, SHA-256 and `seon.ai.tokens/estimate`. Missing provenance is
  explicit unknown and fails any claim that depends on it.
- `action.clj` and `result.edn`: the ordinary agent turn's exact forms,
  complete outcome envelopes and immutable test/run/task/answer evidence.
- `page.html` plus a browser observation reference for the default pass:
  the rendered evidence and observed last/deep sentinel where relevant.
  This is HTML verification, not an alternate prompt.

Required primary recordings are **ten**: five fixture, then five default.
The fixture harness additionally checks both 3a opening dials for source/
linked-evidence parity; the ten primary records identify the selected dial.
Old recordings remain byte-identical after a fix; record a new attempt
rather than replace failure evidence. Do not store live result objects or
credentials in the artifact bundle.

Record one judgement per required field: present/actionable, explicit
unknown that prevents action, or absent. Then a case verdict and reason.
A profile elision is actionable only if its requery form exposes the needed
evidence within the same turn; “there might be something in the blob” fails.
A missing test/function/task population must fail the harness positively.

### The five proofs and their one-turn judging bars

| Proof | Fixture and default task facts | What the opening must contain before the turn | Positive result / refusal that counts |
|---|---|---|---|
| **1. Repair a red test** | An actually executed failing test; its immutable run/member/failure; linked function and indexed source/citation; real task done condition. Use a bounded deterministic defect in the assigned subject, not a synthetic failure datom. | Test symbol; expected vs actual assertion and context; exact reported location and relevant function definition/contract or executable read; tested basis/freshness; callable reproduction/check form; named done condition. The agent can identify a concrete repair, not merely “tests failed.” | One ordinary turn changes the allowed subject through its admitted writer and runs the relevant check. A new positive recorded result at the changed program/input/basis proves green and the task acceptance query agrees. A stale green, covered but unrelated run, empty selection or dropped failure does not count. |
| **2. Add a contract** | An eligible baseline debt function and its existing callers/arglists/source plus a reproducing example and current tests. Existing 2a policy remains armed. | Qualified symbol, exact arities including variadic bound, current source, observed input/output behavior and relevant call sites, explicit missing contract obligation, admitted declaration form and verification form. No private owner hint about a return facet. | Install a complete truthful contract via the normal candidate/declaration seam. Exercise each arity with representative calls under instrumentation and run the selected tests. The current debt query no longer names it; an actually wrong argument/output produces the precise typed violation. Merely adding metadata or satisfying a source scanner fails. |
| **3. Strengthen a schema with a reproducing example** | A trial schema with its current accepted too-broad example, desired declared invariant, valid neighboring example, affected references and test. Link the actual schema key and example-bearing test/request to the task. | Current canonical form/doc; offending accepted example and wanted behavior; valid example that must remain valid; referenced users and source; exact read/write/check forms; explicit scope of the change. It must be possible to distinguish an intended refusal from a malformed fixture. | First record the reproducing behavior. Submit the change through canonical admission; prove the bad value is refused at the actual relevant write/validation boundary, the valid value still succeeds and the reproducing test is green at the new basis. If narrowing requires same-transaction repairs, supply them at the authority or record its complete refusal. Do not quietly break stored live entities or migrate disposable data. |
| **4. Repair a fault** | A real recorded fault occurrence from an assigned bounded execution, its observation/evidence/location, implicated function, reproduction and acceptance test. Use the normal fault recorder, never hand-write an occurrence row. | Exact operation/layer/function and source; expected/offending values, ordered location, original evidence or working requery; occurrence count/time and process/turn provenance; a callable reproduction and the test/done condition. Error message alone is insufficient. | The ordinary turn repairs the allowed subject and records a positive reproduction/test outcome. Task acceptance uses current test evidence and 3a's fault-basis rule. “No new occurrence seen” or a quiet listener is never completion. Keep the original occurrence/history queryable. |
| **5. Answer a request** | A real outside request message and a task linked to it; relevant subject data and an explicit answer criterion. Include a trigger-linked response path and a same-about unrelated root as a control. | Original request content and author/recipient/direction; actual conversation ancestry; requested subject data or its exact read; required answer criterion and reply form. Separate handled, wake answered and request fulfilled. A subject string alone is not a request. | One accepted ordinary reply answers the actual question with cited task data, records its message through the existing owner and satisfies the explicit criterion. Verify recipient/parent facts when supplied by the real trigger path; independent roots stay independent. A handling claim, wake coverage or polite acknowledgement without the answer fails. |

Choose examples small enough that a competent agent can finish in one turn,
but rich enough to require the linked pair (a real assertion, a nontrivial
arity/output, one relational/schema constraint, one located fault and a
data-backed answer). “One turn” does not promise one evaluation or forbid
the existing elision requery. Do not give the driver the expected patch
outside the opening.

The schema proof is a controlled trial of current admission, not wave 4's
branch/diff/merge/export campaign. A complete refusal is useful evidence of
the authority, but it does **not** make the requested strengthening proof
green until the accepted repair and positive checks are recorded.

### Failed-proof feedback — change the owner, never the demonstration

| Observed failure | Feedback destination and exact handoff |
|---|---|
| Task omits its subject/test/error/example/message, bad trigger routing, duplicate injected opening, missing reached concerns, false completion | **3a**: input/task facts, generated source, selected units, stored opening and desired positive predicate. Preserve the ordinary root at the agent. |
| Pair absent/ambiguous/floor where a ranked concern should render; unreadable value; missing status detail; wrong source grammar; HTML clipping; broken requery; invisible authoring help | **3b**: unit request, supplied profile/projection basis, selected candidate evidence, source/read result and byte/sentinel failure. |
| Writer/admission/recorder/armed contract fault prevents a valid trial | Actual **1a/1b/bridge/wave-2 owner**, referenced through the 3a/3b integration handoff; raw complete envelope, no guessed attribution. |
| Need automatic recursive derived-result source expansion beyond option 1 | Owner decision: recorded minimal nested example and the three options/costs above. No private recursive harness. |
| No eligible live subject, default stale or foreign publication failed | Orchestrator live-proof prerequisite; preserve fixture proof and continue independent cases. Do not reset default, invent a live result or declare ten green. |

A failed case stays failed until the owner change is landed and the ordinary
opening is recaptured on the correct basis. The driver may repair **its own
capture/test bug**, with a regression, but may not change the required bar
to make product output pass.

### 3c commands, landing and exclusions

Run the canonical fixture harness serially:

```sh
bin/test-fast --paths <owned proof test/helper paths> -- seon.render.template-proofs-test
```

Load the new test namespace using the repository's test classpath, not an
unavailable production-only classpath, before its path-limited commit.
Record the actual command and whether it exercised new members or reused
recorded green. The ordinary common-contract production require is
applicable only if a production namespace is owned; this lane owns none.

Default pass uses the committed live probe script through the supported
MCP/REPL and the existing trial/turn APIs after orchestrator adoption.
Bound every evaluation and event wait. Do not launch another development
JVM merely to obtain a recording. The orchestrator supplies cold
`--paths`/`--platform` and any reset; the lane reports those as owed until
their real results are available.

The landing table has ten rows with environment, case, task/agent,
opening hash/bytes/tokens, actionable fields, action result, positive
acceptance evidence, source/program/basis and verdict. Include links to
each artifact, old failed attempts, and exact unresolved boundaries.
Report no aggregate “templates work” claim while one row is absent.

**Must NOT build:** a template registry or discriminator, a bespoke opening
builder, prompt coaching outside linked task facts, model/provider benchmark,
new test recorder/runner, synthetic success datoms, source-owner fix, task
lifecycle, schema migration, changeset/merge/export mechanism, generalized
recursive renderer, new background service, paid-provider campaign or
Opus implementation lane.

## Design verification boundary and findings assigned here

The design lane read the four named plan/research authorities end to end,
the requested PRD §§13–15, AGENTS's requested rules/vocabulary, and all of
`render.clj`, `render/walk.clj`, `render/block.clj`,
`render/value.clj`, `render/hiccup.clj` and `repl.clj` end to end.
It structurally read every top-level schema declaration for the census,
and inspected the owning/dependency slices cited above. It applied the
data-oriented-clojure, datastar-web-ui, clojure-testing, data-modeling and
datahike skills. The roadmap and current working-edge checkpoint were
read for release context; this is not a claim to audit every historical
working-edge entry.

**No JVM, test run, prepl evaluation, operator lifecycle action, browser
probe, worktree or delegated agent was launched for this design.**
Source sufficed; the one permitted read-only prepl evaluation was unused.
MCP tools were discoverable; their runtime health was not tested. This
document claims a grounded launch design and static census, not a live
opening, passing test, complete adoption or observed browser paint.

Findings have an explicit home in this sole authorized document:

- The nonexistent audit §11 and root-only concern qualification are resolved
  in the authority/mechanism sections; 3a remains the implementation owner.
- The touched test renderer's identity-summary source and old report-name
  references are assigned to 3b's bounded formatter cleanup after 1b.
- Existing outward-render defects remain with
  [class-outward-values-bypass-total-render-contract.md](docs/seon/issues/class-outward-values-bypass-total-render-contract.md)
  (open at inspection); the separate
  [transaction-html-has-a-second-generic-value-renderer.md](docs/seon/issues/transaction-html-has-a-second-generic-value-renderer.md)
  is out of scope. No duplicate issue or second generic renderer is created.
- Static missing-pair counts are **not** observed fallback counts.
  Matching schemas/aliases may already select another pair; implementations
  must record actual selection before declaring a live gap.
- The audit's proposed pair! wrapper is not equivalent to the reader's
  recognized declaration operation; 3b uses the native write plus my.render
  preview, with the source-grounded correction above.
- Inbound unparented messages are a present limitation, not permission to
  infer threads from subject text. The precise truthful guarantee and
  default proof are assigned above.

The edit hook reported a repository-wide Markdown pin error against the
foreign 3a spec at line 737: it associated SCI commit
`fcbd8862800e638dc0f8f5521111f999279cbcd2` with Datahike. Direct inspection of
3a lines 733–737 names Datahike's actual
`e11845bac78e1241bca0766ddc07d978bd63d74a` and labels the other hash SCI;
`git ls-files -s reference-code/datahike reference-code/sci` agrees.
This is a hook diagnostic boundary, not a demonstrated stale dependency
pin in 3a. The design lane changed neither that document nor the linter.
Its own fence-format warnings were corrected in this document.

Final static checks: native Babashka single-file Markdown validation
reported **0 issues**; every Markdown document link resolved; the appendix
contained **210 resources and 3,249 unique declaration coordinates**, with
all pair/marker subtotals matching its recorded census. Re-running the
embedded read-only census confirmed every unchanged file's coordinates.
Three resources had advanced concurrently since the captured hashes:
`seon.ai.edn`, `seon.test.accretion.edn` and `seon.test.runner.edn`.
Their dated inventory was preserved, not silently mixed with newer bytes.
No finding here claims those foreign changes are defects.
The initial native linter invocation needed the full script/src/resources
classpath; a broad vault scan was stopped and re-run as bounded single-file
validation, with links checked directly. All design-owned processes exited.

The document-only commit needs no JVM gate. Static verification checks
complete schema coverage/counts, both launch assignments, the ten-proof
matrix, referenced paths/coordinates, and the absence of any other authored
file. Its implementation/cold/live obligations remain explicitly future.

## Appendix A — complete dated declaration census

This is a **literal declaration census**, not an effective compiled-schema
query. Every top-level key in every `resources/seon/schemas/*.edn` file is
listed, including attributes, aliases, view shapes, facets and entities.
The parser reads balanced EDN containers/strings/comments and namespaced
map keys, then records literal render properties within each declaration.
It does not chase aliases, evaluate predicates or prove runtime selection.

**Snapshot totals:** 210 resource files; 3,249 top-level declarations;
373 with both literal output properties, 5 AI-only, 0 HTML-only, and 2,871
with neither. Of 198 declarations carrying literal
`:seon.db/attributes true`, 54 have a full pair, 144 have neither and
none has a half pair. The marker is only a census aid: notably
`:seon.eval/entity` has a pair without that marker, so an entity-flag-only
query would omit it. The historical audit's 145/23 counts describe a
different population; no ratio here predicts the live floor rate.

Notation for the file tables:

- The resource filename supplies a **default keyword namespace**.
  `name@line` means `:<filename-without-.edn>/name` at that file line.
  A token beginning `:` is already fully qualified and overrides that default.
- `*` marks literal `:seon.db/attributes true`.
  `=Pnn` identifies the exact literal AI/HTML values in the pair legend.
- “No literal pair” lists every remaining declaration; it does not assert
  that alias chasing or another fitting schema cannot select a pair.
- A file heading plus `@line` is its complete file:line coordinate.
  SHA-256 pins the inspected bytes against concurrent changes.
- Half pairs have a legend entry with `—` for the absent output; they are
  observations, not automatically defects (e.g. an AI-only elision).

The census procedure is reproduced after the inventory. No helper file or
maintained registry was added to the repository.

### Literal pair legend

| Pair | AI property | HTML property |
|---|---|---|
| P01 | `my.turn/render-namespace-ai` | — |
| P02 | `seon.agent/render-settings-ai` | `seon.agent/render-settings-html` |
| P03 | `seon.ai/attempt-ai` | `seon.ai/attempt-html` |
| P04 | `seon.ai/model-ai` | `seon.ai/model-html` |
| P05 | `seon.ai/provider-ai` | `seon.ai/provider-html` |
| P06 | `seon.background/render-ai` | `seon.background/render-html` |
| P07 | `seon.bootstrap/render-help-ai` | `seon.bootstrap/render-help-html` |
| P08 | `seon.cluster.agent/render-creation-ai` | `seon.cluster.agent/render-creation-html` |
| P09 | `seon.cluster.agent/render-identity-ai` | `seon.cluster.agent/render-identity-html` |
| P10 | `seon.cluster.agent/render-situation-ai` | — |
| P11 | `seon.cluster.instruction/instruction-ai` | `seon.cluster.instruction/instruction-html` |
| P12 | `seon.cluster.message/render-ai` | `seon.cluster.message/render-html` |
| P13 | `seon.cluster.message/render-inbox-ai` | `seon.cluster.message/render-inbox-html` |
| P14 | `seon.cluster.status/render-ai` | `seon.cluster.status/render-html` |
| P15 | `seon.cluster/render-ai` | `seon.cluster/render-html` |
| P16 | `seon.context/capture-ai` | `seon.context/capture-html` |
| P17 | `seon.db/render-diff-ai` | — |
| P18 | `seon.db/render-rejection-ai` | `seon.db/render-rejection-html` |
| P19 | `seon.db/render-transaction-ai` | `seon.db/render-transaction-html` |
| P20 | `seon.effect/render-ai` | `seon.effect/render-html` |
| P21 | `seon.error/ai-prose` | `seon.error/render-html` |
| P22 | `seon.error/edit-prose` | `seon.error/render-html` |
| P23 | `seon.error/elision-prose` | `seon.error/elision-html` |
| P24 | `seon.error/index-refusal-prose` | `seon.error/render-html` |
| P25 | `seon.error/mcp-prose` | `seon.error/render-html` |
| P26 | `seon.error/refusal-prose` | `seon.error/render-html` |
| P27 | `seon.error/render-ai` | `seon.error/render-html` |
| P28 | `seon.error/render-faults-ai` | `seon.error/render-faults-html` |
| P29 | `seon.error/time-limit-prose` | `seon.error/render-html` |
| P30 | `seon.issue/render-ai` | `seon.issue/render-html` |
| P31 | `seon.maintenance/render-report-ai` | `seon.maintenance/render-report-html` |
| P32 | `seon.note/render-note-ai` | `seon.note/render-note-html` |
| P33 | `seon.note/render-notes-ai` | `seon.note/render-notes-html` |
| P34 | `seon.plan/format-plan-ai` | `seon.plan/render-plan-html` |
| P35 | `seon.plan/render-item-ai` | `seon.plan/render-item-html` |
| P36 | `seon.plan/render-plan-ai` | `seon.plan/render-plan-html` |
| P37 | `seon.print/render-elision-ai` | — |
| P38 | `seon.problems/ai-prose` | `seon.problems/html-report` |
| P39 | `seon.problems/missing-model-ai` | `seon.problems/missing-model-html` |
| P40 | `seon.render.ns/function-ai` | `seon.render.ns/function-html` |
| P41 | `seon.render.ns/render-ai` | `seon.render.ns/render-html` |
| P42 | `seon.render.ns/render-alias-ai` | `seon.render.ns/render-alias-html` |
| P43 | `seon.render.ns/render-import-ai` | `seon.render.ns/render-import-html` |
| P44 | `seon.render.ns/render-refer-ai` | `seon.render.ns/render-refer-html` |
| P45 | `seon.render.test/render-ai` | `seon.render.test/render-html` |
| P46 | `seon.render.transcript/render-history-ai` | `seon.render.transcript/render-history-html` |
| P47 | `seon.render.transcript/render-run-ai` | `seon.render.transcript/render-run-html` |
| P48 | `seon.render.transcript/render-runtime-ai` | `seon.render.transcript/render-runtime-html` |
| P49 | `seon.render.value/render-ai` | `seon.render.value/render-html` |
| P50 | `seon.render.value/render-database-identity-ai` | — |
| P51 | `seon.render/unknown-ai` | `seon.render/unknown-html` |
| P52 | `seon.repl/render-ai` | `seon.repl/render-html` |
| P53 | `seon.repl/render-directory-ai` | `seon.repl/render-directory-html` |
| P54 | `seon.test.accretion/render-ai` | `seon.test.accretion/render-html` |

### resources/seon/schemas/datahike.read.edn

SHA-256: `bc6e784f9edd97ccc62dc4a4fadef359c459ce6281b04a550e0311654cfc1d91`.

Literal pair declarations: none.

No literal pair:

```text
dependency-plan@6  revision@7
```

### resources/seon/schemas/error.edn

SHA-256: `80332581c15f2da5846dd47f2f9c6deb08818c96c0ca2c4b16fc1ce3eec7fd81`.

Literal pair declarations: none.

No literal pair:

```text
message@1
```

### resources/seon/schemas/gen.edn

SHA-256: `877e0ae6b0647c6f2d315e911890fa3b04bd1739e3225ce64b8f8d779e7d3b9d`.

Literal pair declarations: none.

No literal pair:

```text
schema@1
```

### resources/seon/schemas/malli.edn

SHA-256: `d8dff607398a200a83a91906a88a9d155ccd79fa329dcd6e75cb9d90fa108899`.

Literal pair declarations: none.

No literal pair:

```text
:inst@1
```

### resources/seon/schemas/my.agent.edn

SHA-256: `0336256811f39093b9a3ca7298d84d254bd1553e66a991bd0050b8ef48e6cfef`.

Literal pair declarations: none.

No literal pair:

```text
id@1  namespace@2  steward@3  turns-left@4  identity@5  settings@8  settings-request@9
```

### resources/seon/schemas/my.background.edn

SHA-256: `103ea44c1322b4910f8b101897271871ef530e008a2c395728329c054926cd23`.

Literal pair declarations:

```text
invalid-call-error@25*=P06  invalid-result-error@26*=P06  missing-result-error@27*=P06
```

No literal pair:

```text
receipt@1  result@20  call-source@22  result-observation@23  missing-result-ref@24  authored-form@30
error@36*
```

### resources/seon/schemas/my.edit.edn

SHA-256: `a4973321fdd5ec34e2e722fdd8a85c0dc9c6d14c90c3c0365317bb1aaed09302`.

Literal pair declarations:

```text
no-match-error@64=P22  ambiguous-match-error@66=P22  parse-refused-error@68=P27
lossless-check-failed-error@70=P27  stale-source-error@72=P27  not-utf8-error@74=P27
```

No literal pair:

```text
path@1  expected-digest@2  form@3  operation@4  source@6  form-request@7  old-string@19  new-string@20
replace-all?@21  exact-request@22  from-line@29  to-line@30  old-window@31  new-window@32
actual-window@33  lines-request@34  changed?@42  before-digest@43  after-digest@44  before-bytes@45
after-bytes@46  source-window@47  source-window-complete?@48  replacements@49  result@50  no-match@63
ambiguous-match@65  parse-refused@67  lossless-check-failed@69  stale-source@71  not-utf8@73
edit-observation@77  error@83*  error-path@92
```

### resources/seon/schemas/my.edit.form.edn

SHA-256: `6cc04b02356a03020cfdcd006f7d23946993188cf18acf14c7d1ce4a1239c85f`.

Literal pair declarations: none.

No literal pair:

```text
head@1  name@2  dispatch-source@3  selector@4
```

### resources/seon/schemas/my.fs.edn

SHA-256: `75767569f436b9ddc2db898d65391581b40335368f096c29dcfbba614cd1c919`.

Literal pair declarations:

```text
not-found-error@121=P27  not-directory-error@123=P27  not-regular-file-error@125=P27
already-exists-error@127=P27  path-refused-error@129=P27  read-failed-error@131=P27
write-failed-error@133=P27  read-limit-error@135=P27  write-limit-error@137=P27
stale-digest-error@139=P27  changed-during-read-error@141=P27  invalid-utf8-window-error@143=P27
atomic-write-unsupported-error@145=P27  glob-failed-error@147=P27  invalid-glob-error@149=P27
blob-unavailable-error@151=P27
```

No literal pair:

```text
after-digest@1  already-exists?@2  before-digest@3  byte@4  byte-count@5  byte-offset@6  byte-size@7
bytes@8  bytes-read@9  bytes-written@10  changed?@11  complete?@12  content@13  created?@28  digest@29
directory?@30  encoding@31  eof?@32  examined@33  expected-absence?@34  expected-digest@35
file-bytes@36  glob-request@37  glob-result@43  max-bytes@50  max-depth@51  max-results@52
modified-at@53  path@54  paths@55  pattern@56  precondition@57  read-request@58  read-result@64
regular-file?@75  returned@76  root@77  stat-request@78  stat-result@79  symbolic-link?@87  text@88
window-digest@89  write-precondition@90  write-request@107  write-result@112  not-found@120
not-directory@122  not-regular-file@124  already-exists@126  path-refused@128  read-failed@130
write-failed@132  read-limit@134  write-limit@136  stale-digest@138  changed-during-read@140
invalid-utf8-window@142  atomic-write-unsupported@144  glob-failed@146  invalid-glob@148
blob-unavailable@150  error@154*  error-path@163  io-observation@166
```

### resources/seon/schemas/my.message.edn

SHA-256: `08935040cfb0a792b15d3603eda48e46697fef69aac3253df8cab5af2715593a`.

Literal pair declarations:

```text
no-recipient-error@59=P27  no-content-error@61=P27  no-about-error@63=P27  no-assignment-error@65=P27
no-reason-error@67=P27  not-found-error@69=P27
```

No literal pair:

```text
read-request@1  about@2  assignment@3  content@4  from@5  at@6  id@7  inbox-entry@8  inbox@14
inbox-request@15  inbox-options@20  declination@23  message@28  send-request@34  decline-request@42
reason@49  to@50  value@51  no-recipient@58  no-content@60  no-about@62  no-assignment@64  no-reason@66
not-found@68  error@72*  error-request@80
```

### resources/seon/schemas/my.note.edn

SHA-256: `abcd292e18c3c0dc3b0e853cde2d2c2a0521195189af31a786c4d12bda7b48ca`.

Literal pair declarations:

```text
agent@2=P33  note@8*=P32  notes@18=P33  agent-not-found-error@25=P27
identity-owned-by-another-agent-error@27=P27  about-not-found-error@29=P27  not-found-error@31=P27
not-owned-error@33=P27
```

No literal pair:

```text
id@1  content@6  about@7  agent-not-found@24  identity-owned-by-another-agent@26  about-not-found@28
not-found@30  not-owned@32  existing-about@34  add-request@37  forget-request@38  error-about@41
```

### resources/seon/schemas/my.plan.edn

SHA-256: `a9eff79b397ed98af222955a1835579f045c3553a0235db50dfe5e28b4d00d8d`.

Literal pair declarations:

```text
entity@5*=P36  render-step@87=P35  component-view@152=P34  agent-not-found-error@223=P27
dependency-cycle-error@225=P27  dependency-not-found-error@227=P27  duplicate-identity-error@229=P27
duplicate-position-error@231=P27  foreign-identity-error@233=P27  identity-exists-error@235=P27
item-reference-not-found-error@237=P27  item-reference-not-owned-error@239=P27  not-found-error@241=P27
not-owned-error@243=P27  subject-not-found-error@245=P27  unusable-current-step-error@247=P27
```

No literal pair:

```text
agent@1  completion@3  objective@4  current-value@13  step-summary@24  steps@35  current-step@41
agent-state@46*  parent-step@53  current?@58  needs@62  parent@67  depth@72  state@77
stable-item-reference@82  render-steps@112  component-input@116  added@170  changed@171  retracted@172
diff@173  converged?@180  basis-t@181  plan-result@182  request@187  item-request@191  item-ids@195
items-request@196  ready@200  intent-subjects@205  blocked@206  recent-completions@211
older-completions@216  ready-items@217  agent-not-found@222  dependency-cycle@224
dependency-not-found@226  duplicate-identity@228  duplicate-position@230  foreign-identity@232
identity-exists@234  item-reference-not-found@236  item-reference-not-owned@238  not-found@240
not-owned@242  subject-not-found@244  unusable-current-step@246  add-request@248  complete-request@251
current-request@252  update-fields@253  update-request@259  constraint-observation@270  error@276*
error-request@285
```

### resources/seon/schemas/my.plan.item.edn

SHA-256: `8ca1bec7f1e3e0206416ec8cafe00dd558d2eaea916e8903131712f43f3ec0bd`.

Literal pair declarations:

```text
item@60*=P35
```

No literal pair:

```text
id@1  title@7  description@11  agent@15  needs@20  about-token@26  about@27  completed-tx@33
done-when@38  done-query@43  subject@46  position@49  steps@54  add-request@85
```

### resources/seon/schemas/my.program.edn

SHA-256: `2c89329cbf553d3f0c3f7b46024239a46caa1160a9a9308a9c2a31ce7dd4fd48`.

Literal pair declarations: none.

No literal pair:

```text
context@1  read-request@5  subject-request@6  breaks-request@9  key-request@13  history-request@16
```

### resources/seon/schemas/my.shell.edn

SHA-256: `6e12f8b90db219555129f5203b8f5b04ba650de32ca71aa129960fc51327d44e`.

Literal pair declarations:

```text
blob-unavailable-error@36=P27  stdin-limit-error@38=P27  cwd-refused-error@40=P27
time-limit-error@42=P27  start-failed-error@44=P27
```

No literal pair:

```text
argv@1  cwd@2  exit@3  run-request@4  run-result@9  stderr@16  stdin@17  stdin-bytes@32  stdin-text@33
stdout@34  blob-unavailable@35  stdin-limit@37  cwd-refused@39  time-limit@41  start-failed@43
error@47*  error-command@56  execution-observation@62
```

### resources/seon/schemas/my.shell.output.edn

SHA-256: `16af3f7e0dfe4978cabcf8721ade223ccd1a569c2fe600539104b8a53ab74965`.

Literal pair declarations: none.

No literal pair:

```text
blob@1  bytes@2  digest@3  octet-values@4  preview@5  preview-complete?@6  text@7  value@8
```

### resources/seon/schemas/my.test.edn

SHA-256: `a903d5e1bb891e1bbe9c2791f7abfd760f91266eef6d6df4eede8f100c1207ec`.

Literal pair declarations: none.

No literal pair:

```text
check-request@1
```

### resources/seon/schemas/my.turn.edn

SHA-256: `6647f57d1ed219e44926c25d70fcdd721a59255380bf73c1c0c514611c5824db`.

Literal pair declarations:

```text
namespace-unit@11=P01  blank-note-error@34=P27  blank-result-error@36=P27
usage-walkthrough-absent-error@38=P27
```

No literal pair:

```text
completed@1  delivered-to@8  disposition@9  note@10  usage-unit@18  result@24  value@25  wait@26
blank-note@33  blank-result@35  usage-walkthrough-absent@37  error@41*  error-proposal@49
```

### resources/seon/schemas/my.web.body.edn

SHA-256: `8ef53e52d30aa3628f3eeebdc60c1035e14ba1693a2236e6d5677ce57ad1d9cf`.

Literal pair declarations: none.

No literal pair:

```text
blob@1  bytes@2  digest@3  octet-values@4  text@5  value@6
```

### resources/seon/schemas/my.web.edn

SHA-256: `593a6d62bb29a9c6a3670cb8d2d879563fe02a7bb1262ca1a9cc515c5841a09f`.

Literal pair declarations:

```text
invalid-url-error@37=P27  missing-location-error@49=P27  no-credential-error@60=P27
projection-failed-error@70=P27  provider-failed-error@80=P27  redirect-limit-error@94=P27
redirect-loop-error@104=P27  response-limit-error@115=P27  timeout-error@142=P27
transport-failed-error@153=P27  unparseable-response-error@164=P27
```

No literal pair:

```text
body@1  content-type@2  credits@3  error@4  extraction@17  extraction-error@18  fetch-request@19
fetch-result@23  final-url@35  invalid-url@36  max-results@46  method@47  missing-location@48
no-credential@59  projection-failed@69  provider-failed@79  query@90  raw-response@91
raw-response-bytes@92  redirect-limit@93  redirect-loop@103  redirects@113  response-limit@114
result-count@124  results@125  returned@126  search-request@127  search-result@131  status@140
timeout@141  transport-failed@152  unparseable-response@163  url@173  error-request@176
```

### resources/seon/schemas/my.web.extract.edn

SHA-256: `4dff44dd4cf4db3bccc0b1cd192d9816b1e8f2107d607d76af7697a7e3b45302`.

Literal pair declarations: none.

No literal pair:

```text
text@1  title@2  value@3
```

### resources/seon/schemas/my.web.redirect.edn

SHA-256: `fb85b567f3e443733eb704590b61b81596307f76a65daad53f5e653744e46007`.

Literal pair declarations: none.

No literal pair:

```text
from@1  status@2  to@3  value@4
```

### resources/seon/schemas/my.web.result.edn

SHA-256: `7a56476e0d6d140f9a745278ff16010e9176132693c8861f0aeb5aa0e04fa59a`.

Literal pair declarations: none.

No literal pair:

```text
link@1  position@2  snippet@3  title@4  value@5
```

### resources/seon/schemas/seon.activation.edn

SHA-256: `aeb5dd6df37d0d36c0176027efec62a2d9b1b4bcb7b2591ff3125624beb87506`.

Literal pair declarations: none.

No literal pair:

```text
source-digest@1  schema-keys@3  schema-key@4  required-attributes@5  required-attribute@6
config-defaults@7  config-required@8  config-dial@9  executable-symbols@10  executable-symbol@11
lookup-attribute@12  lookup-value@13  lookup-rows@14  requested-symbols@15  request@16  lookup-refs@22
closure@34*  missing-fact@56  missing@73  missing-count@74  missing-elision@75  refusal@76  result@85
stored@91
```

### resources/seon/schemas/seon.activation.lookup.edn

SHA-256: `efe78e6417ffb1790fda496b65db7c54036db80406894a507969062e0453edcf`.

Literal pair declarations: none.

No literal pair:

```text
id@1  attribute@3  value@4  lookup@5*
```

### resources/seon/schemas/seon.agent.edn

SHA-256: `3071b3a29f12bd129d76e060df3068189048944b575973b52e54d8cda2f8fcf6`.

Literal pair declarations:

```text
agent@3*=P09  creation-result@56=P08  situation@123=P10  no-such-agent-error@145=P27
turn-completion-backstop-error@147=P27  turn-completion-undeliverable-error@149=P27
creation-incomplete-error@151=P27  armer-quiescence-undeliverable-error@153=P27
supervision-not-committed-error@155=P27  plan@156=P36  settings@159=P02
```

No literal pair:

```text
archived-tx@1  runtime@2  arm-request@27  armed@34  blueprint-request@45  count@50  creation-request@51
creation-tx@66  disarm-request@67  eid@72  id@73  identity-request@80  namespace@84  context-state@89
routing@95  source-submission-request@101  source-submission-result@111  namespace-ref@119
unread-message-count@120  open-run-ref@121  protocol-namespaces@122  no-such-agent@144
turn-completion-backstop@146  turn-completion-undeliverable@148  creation-incomplete@150
armer-quiescence-undeliverable@152  supervision-not-committed@154  error@164*  error-agent-id@172
failure@175
```

### resources/seon/schemas/seon.agent.graph.edn

SHA-256: `96e1956f134ade078f468e5e608e5cc9a18441e7bce4caa71d9698bba83dd8d0`.

Literal pair declarations: none.

No literal pair:

```text
control-observation@4  error@10*  proc@20
```

### resources/seon/schemas/seon.ai.attempt.edn

SHA-256: `016e7526f0a46d006bf90bb47abdb6a16013eb2ff7d328b9475d0bb694ee9da2`.

Literal pair declarations: none.

No literal pair:

```text
failover-from@1  finish-reason@2  reasoning-size@3  delay-ms@4  id@5  ordinal@6  reasoning-blob@7  at@8
usage-edn@9  truncation@10  settings@11  settings-edn@12  reasoning@13  error@14
```

### resources/seon/schemas/seon.ai.edn

SHA-256: `2d11ca30cd98162cc8428f01834759f36b3ef773dc02ea5018de861b86508e4b`.

Literal pair declarations:

```text
rate-limited-error@4=P21  authorization-failure-error@14=P21  authentication-failure-error@24=P21
response-failure-error@36=P21  credential-failure-error@54=P21  transport-failure-error@63=P21
token-starvation-error@72=P21  request-failure-error@98=P21  model-failure-error@110=P21
reasoning-without-answer-error@125=P21  no-credential-error@139=P21  unparseable-body-error@161=P21
stream-truncated-error@245=P21  extra-body-conflict-error@275=P21  provider-server-failure-error@339=P21
attempt@352*=P03  transport-before-send-failure-error@429=P21  invalid-extra-body-error@439=P21
timeout-error@448=P21  provider-error-error@467=P21  transport-outcome-unknown-error@477=P21
```

No literal pair:

```text
request-attribute@1  inert-when-thinking@2  provider-error@3  wire@13  transport-outcome-unknown@34
invalid-extra-body@35  transport-failure@45  sink@46  temperature@52  top-p@53  output-observed?@62
error-class@81  unparseable-body@94  rate-limited@95  sent@96  inert@97  extra-body-edn@107
thinking@108  backup@109  targets@119  disposition@123  http-status@124  no-credential@135
authentication-failure@136  stream-truncated@137  reasoning-partial@138  partial@148
credential-failure@159  api-key-variable@160  json-value@170  reasoning-without-answer@184  primary@185
request@186  request-failure@244  normalized-usage@254  response-failure@268  model-failure@269
wire-settings@270  tokens@274  token-starvation@284  reasoning-content@285
transport-before-send-failure@286  target@287  response-format@337  provider-server-failure@338
extra-body@349  model@350  backup?@351  stop@406  completion@407  settings@420  prompt@421
request-body@422  presence-penalty@423  timeout-ms@424  frequency-penalty@425  response-started?@426
finish-reason@427  authorization-failure@428  text@457  disposition-request@458  endpoint@462  usage@463
request-transmitted?@464  stream?@465  extra-body-conflict@466  max-tokens@476  system@487  timeout@488
error-provider@491  request-error@494*  request-observation@503
```

### resources/seon/schemas/seon.ai.http.edn

SHA-256: `93f6bef6f425ee88c74957878be599a551b538a9996592fc25932eb8d834f4ef`.

Literal pair declarations: none.

No literal pair:

```text
body@1
```

### resources/seon/schemas/seon.ai.model.edn

SHA-256: `aeb309c2b762653609b67234a4c227a8e3f375f99cd4f25af4fbcfecaabb61e3`.

Literal pair declarations:

```text
provider-entity@41*=P05  entity@67*=P04
```

No literal pair:

```text
id@1  provider@4  provider-id@5  openai-chat-completions@7  output-token-wire-key@8
context-window-tokens@10  max-output-tokens@11  input-usd-per-mtok@12  output-usd-per-mtok@13
cached-input-usd-per-mtok@14  input-modalities@15  thinking-dials@16  last-tokens-per-second@18
last-latency-ms@20  last-used-at@22  deepseek-off-peak-windows@25  deepseek-window-id@26
deepseek-utc-start@28  deepseek-utc-end@29  deepseek-regular-price-factor@30
deepseek-peak-price-factor@31  deepseek-pricing-schedule-status@32  meta-search-usd-per-kquery@35
meta-free-requests-per-minute@36  meta-free-tokens-per-minute@37  meta-paid-requests-per-minute@38
meta-paid-tokens-per-minute@39  deepseek-window-entity@54*  models@126  observation-request@128
```

### resources/seon/schemas/seon.ai.retry.edn

SHA-256: `44515ee960d4898a911235c7e8c6147a8f6d1283f419f3379356c1b147b04ba6`.

Literal pair declarations: none.

No literal pair:

```text
base-delay-ms@1  delays@2  jitter-fraction@3  maximum-delay-ms@4  maximum-retries@5
maximum-total-delay-ms@6  multiplier@7  strategy@8
```

### resources/seon/schemas/seon.ai.tokens.edn

SHA-256: `a1284003a41aa7aead271f76f6bf648db242f7c9f0ee05f861e7f9bd9209839c`.

Literal pair declarations: none.

No literal pair:

```text
chars-per-token@4  basis@9  sample-count@13  relative-error@17  characters@19  estimate@20  estimated@21
upper-bound@22  verdict@24  observation@28  observations@33  calibration@35  budget-report@44
```

### resources/seon/schemas/seon.ai.usage.edn

SHA-256: `0a8ccb7ad4fbe933b4ea39a26d06545393067b76a280adc0cacb6a4dd3ed28b5`.

Literal pair declarations: none.

No literal pair:

```text
cached-tokens@1  completion-tokens@2  prompt-tokens@3  total-tokens@4
```

### resources/seon/schemas/seon.artifact.edn

SHA-256: `d74ae54b143ac5c8c99cde2190c11450ba04454e0ceecf352f3720d3b567674c`.

Literal pair declarations:

```text
refused-error@2=P27
```

No literal pair:

```text
refused@1  error@5*  error-identity@14  error-transition@17
```

### resources/seon/schemas/seon.await.edn

SHA-256: `3bb22558ba5e086b5f9506d32ae38f8878259a9ea663e875d847814db130fbe8`.

Literal pair declarations: none.

No literal pair:

```text
config-attribute@1  config-value@2  bound@6  diagnostic@10  put-operation@18  port-operation@20
port-operations@22  accept?@24  blocking-deref@29  port-request@34  future-request@40  deref-request@45
request@50
```

### resources/seon/schemas/seon.blob.edn

SHA-256: `2ce7b217c2b74f38cb33c040f970b0e1b48e15b1b1094ca60ab9d9306ba861c7`.

Literal pair declarations:

```text
invalid-threshold-error@32=P27  store-root-absent-error@34=P27  stored-content-mismatch-error@36=P27
input-stalled-error@38=P27  content-digest-mismatch-error@40=P27
```

No literal pair:

```text
content@1  digest@2  inline-prefix@3  input-stream@4  length@9  octet-array@10  offset@15  size@16
staged-path@17  staged-octets@18  staged-write@19  write-result@26  invalid-threshold@31
store-root-absent@33  stored-content-mismatch@35  input-stalled@37  content-digest-mismatch@39
```

### resources/seon/schemas/seon.boot.edn

SHA-256: `3ab6c7f0b1bf3da9be8cf841619582ab59f4f4801c9e2280547d5869ca26d69a`.

Literal pair declarations:

```text
refused-error@156=P27
```

No literal pair:

```text
advertisement@1  cluster-name@14  config@24  executors@32  instance@46  log-dir@88  overrides@89
pid@105  prepl-host@106  prepl-port@107  readiness@108  recovered-runs@128  recovery-operations@129
root@130  start-instant@131  start-request@132  store-dir@154  refused@155  error@159*  failed-phase@168
process-root@171
```

### resources/seon/schemas/seon.bootstrap.edn

SHA-256: `49543f10dbe4abd7c4fb0bdfef0770abfa46d85512b52b49b6175654fab7c0d9`.

Literal pair declarations:

```text
root-acquisition-empty-error@2=P27  prefix-drift-error@13=P27
```

No literal pair:

```text
root-acquisition-empty@1  prefix-drift@12  error@25*  error-member@35  prefix-observation@38
```

### resources/seon/schemas/seon.call-preparation.edn

SHA-256: `c46910189bf1c1486b156a0a3e506fe488ece3133eab8add3f5b17d0cad44054`.

Literal pair declarations: none.

No literal pair:

```text
key@22  schema@28  supplier@33  row@35*  supplier-symbol@43  schema-key@44  shape@45
supplied-default@47  supplied-defaults@55  validators@58  refusals@61  prepared-symbols@70  basis-t@78
checked-through-t@79  snapshot@81  entry-key@93  slot@95  slots@105  insertion@109  insertions@114
variadic?@116  rest-slot?@117  ambiguous?@118  empty?@119  supplied-count@120  candidates@121
argument-validators@122  arity-plan@124  arity-plans@136  call-shape@140  dispatch@153  preparation@165
by-supplied-count@182  variadic@189  contract-t@196  plan@198  state@215
```

### resources/seon/schemas/seon.cluster.edn

SHA-256: `9703c7432af996412a2590d2c62abcb23306391972d444b07ede56700379f46a`.

Literal pair declarations:

```text
cluster@1*=P15
```

No literal pair:

```text
config@10  created?@11  instructions@12  name@13  toolkit@14  toolkit-namespaces@15  error@18*
error-cluster-name@27  failure@30  graph-operation@36
```

### resources/seon/schemas/seon.cluster.eval.edn

SHA-256: `678616718c63204a4467fd9f2a3cb93a158429ed97ba2e4b65167eecf4827786`.

Literal pair declarations: none.

No literal pair:

```text
triage-edn@1  read-basis-transaction@2  error@3  read-evidence@4  source@6  author@8  comment@14
interrupted-at@20  id@21  ns@22  run@23  output@24  ordinal@26  receipt@27*  settle-request@116  at@193
read-evidence-entity@194*
```

### resources/seon/schemas/seon.cluster.export.edn

SHA-256: `c87dad2a7f21b69f93104103d321361eafac7e5159c72edfdddfa06f702c9d30`.

Literal pair declarations:

```text
clone-unsupported-error@3=P27  export-exists-error@5=P27  genesis-incomplete-error@7=P27
no-branch-head-error@9=P27  refused-error@11=P27
```

No literal pair:

```text
rule@1  clone-unsupported@2  export-exists@4  genesis-incomplete@6  no-branch-head@8  refused@10
```

### resources/seon/schemas/seon.cluster.instruction.edn

SHA-256: `6ec08ecc16729e7390d978d65c6b817f02478f8f03af19f54af8ff3ee2331683`.

Literal pair declarations:

```text
instruction@2*=P11
```

No literal pair:

```text
id@1  seed-rows@13  text@16
```

### resources/seon/schemas/seon.cluster.process.edn

SHA-256: `fb10cb3f61c6f8311d5b221fc6fd40e7d647445d00f7b87713cbddcf00f50e55`.

Literal pair declarations:

```text
start-instant-unavailable-error@8*=P27
```

No literal pair:

```text
identity@1  pid@6  start-instant-unavailable@7
```

### resources/seon/schemas/seon.cluster.prompt.edn

SHA-256: `15c04e11758baf6a848bf76f1bd0744c75b338251291826bc7edb03e35f749d8`.

Literal pair declarations:

```text
no-trigger-error@30=P27  refused-error@32=P27  missing-cluster-error@34=P27
budget-exceeded-error@36=P27  missing-config-error@38=P27
```

No literal pair:

```text
rendered-context@1  result@8  request@12  text@27  rule@28  no-trigger@29  refused@31
missing-cluster@33  budget-exceeded@35  missing-config@37  derivation-observation@41  error@47*
error-agent-id@58
```

### resources/seon/schemas/seon.cluster.registry.edn

SHA-256: `0a619844af7eb013af1c54699bbd7396a2e6c9c89b58eefd24aeee8256cf87b3`.

Literal pair declarations:

```text
cannot-retire-main-error@53=P27  cluster-connected-error@55=P27  source-absent-error@57=P27
refused-error@59=P27  branch-head-absent-error@61=P27  candidate-file-absent-error@63=P27
dry-run-barrier-absent-error@65=P27  dry-run-complete-error@67=P27
```

No literal pair:

```text
branch-commit-request@1  branch-request@5  branch-result@11  cluster-request@16  from@23
retire-request@24  roster@28  swept@29  retained-files@30  candidate-files@31  candidate-bytes@32
file-bytes@33  mark-duration-ms@34  missing-candidate-files@35  inventory@36  rule@51
cannot-retire-main@52  cluster-connected@54  source-absent@56  refused@58  branch-head-absent@60
candidate-file-absent@62  dry-run-barrier-absent@64  dry-run-complete@66  error@70*
registry-observation@81  target-branch@87
```

### resources/seon/schemas/seon.cluster.reply.edn

SHA-256: `c7c8b740069d8b6a256354205e67c374de477beb5ce93006c46525a5c88000f7`.

Literal pair declarations:

```text
refused-tag-error@14=P27  unreadable-error@16=P27  no-forms-error@18=P27
```

No literal pair:

```text
form@1  sources@11  text@12  refused-tag@13  unreadable@15  no-forms@17  authored-reply@21  error@27*
```

### resources/seon/schemas/seon.cluster.source.edn

SHA-256: `66eee218bf4657383bdde5b5d1b8d1f142dfd2aeeccf8cecd0e3a70085345d52`.

Literal pair declarations:

```text
root-absent-error@3=P27  invalid-source-seal-error@5=P27  populate-unresolvable-error@7=P27
publish-readback-failed-error@9=P27  stale-publication-error@11=P27
unsafe-incremental-rows-error@13=P27  refused-error@15=P27
```

No literal pair:

```text
rule@1  root-absent@2  invalid-source-seal@4  populate-unresolvable@6  publish-readback-failed@8
stale-publication@10  unsafe-incremental-rows@12  refused@14  error@18*  error-source@28
publication-observation@31
```

### resources/seon/schemas/seon.cluster.status.edn

SHA-256: `b80d57b4bc581572a42901c39a8a7283fa7b7a567ee038b2da41af92bdc258d7`.

Literal pair declarations:

```text
concern@4=P14
```

No literal pair:

```text
root@1  request@9  count@12  measurement@13  agent@14  value@23
```

### resources/seon/schemas/seon.cluster.store.edn

SHA-256: `fbb8ee9e469d2ca9b2656b212176987c295d85c685462304ee431a5a4c832558`.

Literal pair declarations:

```text
branch-absent-error@2=P27  branch-already-open-error@4=P27  held-elsewhere-error@7=P27
initialization-incomplete-error@9=P27  refused-error@11=P27  file-lock-generator-failed-error@13=P27
```

No literal pair:

```text
branch-absent@1  branch-already-open@3  rule@5  held-elsewhere@6  initialization-incomplete@8
refused@10  file-lock-generator-failed@12  acquisition-observation@16  error@22*  error-root@32
```

### resources/seon/schemas/seon.cluster.wake.edn

SHA-256: `cc3261695593fcfaa9d1fd096a88ab00fc29404511dbfe8d3df796daac372e22`.

Literal pair declarations:

```text
undeliverable-wake-error@37=P27
```

No literal pair:

```text
attributes@1  channels@2  delivery@3  fenced?@8  key@9  offer-result@10  route-request@11
unlisten-request@31  undeliverable-wake@36  error@40*  error-recipient@51  offer-observation@54
```

### resources/seon/schemas/seon.config.agent.edn

SHA-256: `613b530455070464ef9ff31a7a53eca57d9f16cfbaacfe99a7c6566cbaccf657`.

Literal pair declarations: none.

No literal pair:

```text
show-all-settings@1  write-refusal-bound@8  turn-completion-backstop-ms@15
```

### resources/seon/schemas/seon.config.ai.backup.edn

SHA-256: `b5e24f09755449938c81d9cc811e5c2fd977c3aff51678655eb842e3ce8851c4`.

Literal pair declarations: none.

No literal pair:

```text
api-key-variable@1  endpoint@7  model@13  timeout-ms@19
```

### resources/seon/schemas/seon.config.ai.edn

SHA-256: `d92d1423066440e764d57eb141dfd754cf6cb638830a5a627cf3f52fc0ce6079`.

Literal pair declarations: none.

No literal pair:

```text
api-key-variable@1  chars-per-token-prior@9  endpoint@16  extra-body-edn@22  frequency-penalty@31
max-tokens@43  model@51  no-auth@57  no-provider@64  presence-penalty@73  prompt-token-budget@85
response-format@92  retain-reasoning@102  stop@111  temperature@121  thinking@132  timeout-ms@143
top-p@149
```

### resources/seon/schemas/seon.config.ai.retry.edn

SHA-256: `f795792f1f301af183890699e29e47b335378531958d8b5d2260a93cea6fea07`.

Literal pair declarations: none.

No literal pair:

```text
base-delay-ms@1  jitter-fraction@5  maximum-delay-ms@9  maximum-retries@13  maximum-total-delay-ms@17
multiplier@21
```

### resources/seon/schemas/seon.config.bootstrap.edn

SHA-256: `da92040608813d1274644e49d2ae89701247fba005c7c0d708dd931abb490699`.

Literal pair declarations: none.

No literal pair:

```text
beyond-closure-token-budget@1
```

### resources/seon/schemas/seon.config.db.edn

SHA-256: `4894a04bf71031bef7abb72ccde073ddcf31b50131254a72171f2955d75b0579`.

Literal pair declarations: none.

No literal pair:

```text
validation-node-limit@1  write-time-limit-ms@2  keep-history?@3
```

### resources/seon/schemas/seon.config.edn

SHA-256: `913770554a4e8510f046ced5f29d281f08e18b5ceb14f787fc42549dc6791187`.

Literal pair declarations:

```text
missing-effective-error@23=P27  refused-error@48=P27  manifest-unreadable-error@50=P27
reconcile-refused-error@52=P27  required-absent-error@54=P27  unknown-key-error@56=P27
missing-result-cap-error@58=P27
```

No literal pair:

```text
agent@1  display-label@3  display-divisor@4  display-unit@5  dial@6  optional@7  per-agent@8
applied-manifest-digest@9  apply-request@10  cluster@21  missing-effective@22  compile-request@24
compiled@34  on-core-error@42  rule@44  path@45  key@46  refused@47  manifest-unreadable@49
reconcile-refused@51  required-absent@53  unknown-key@55  missing-result-cap@57  error@61*  error-key@74
rule-error@77*
```

### resources/seon/schemas/seon.config.effect.background.edn

SHA-256: `e7d33501fe51b8aed4aa4b9d1b9eddc1b4a2a00b3b784c7f297226ea03766556`.

Literal pair declarations: none.

No literal pair:

```text
time-limit-ms@1
```

### resources/seon/schemas/seon.config.error.edn

SHA-256: `405327e5cf0eb16610eac8c2ec02baaa1d904126217d46d09c00ec3ad85f5b57`.

Literal pair declarations: none.

No literal pair:

```text
escalate-to@1  max-evidence-bytes@6  recurrence-limit@12
```

### resources/seon/schemas/seon.config.eval.edn

SHA-256: `e9178b07f6f2e7beda93081e71a7ed215826ae4a96369fdd81941172414cdb03`.

Literal pair declarations: none.

No literal pair:

```text
time-limit-ms@1
```

### resources/seon/schemas/seon.config.eval.result.edn

SHA-256: `31324ce46868d6f5841c08508ee3eff0e06d8bf5a87c8676378fe43ddd1880eb`.

Literal pair declarations: none.

No literal pair:

```text
blob-threshold@1  max-bytes@3  max-collection@9  max-depth@11  max-nodes@13  max-source@15
max-string@17
```

### resources/seon/schemas/seon.config.flow.compute.edn

SHA-256: `870dfbdb0a23ce7c2f805bd619172778eb63ceb18b031f4f4bbb5bd5ecab13c0`.

Literal pair declarations: none.

No literal pair:

```text
concurrency@1  queue-depth@7
```

### resources/seon/schemas/seon.config.flow.edn

SHA-256: `ea8336e07772b731c4f7a8a2d293547cbbc5390240dabac1ffb634999185986a`.

Literal pair declarations: none.

No literal pair:

```text
ping-timeout-ms@1
```

### resources/seon/schemas/seon.config.flow.io.edn

SHA-256: `a0d9c109b29c9fc8e44586e155c6deddd14c00e6464414420445684648a5b307`.

Literal pair declarations: none.

No literal pair:

```text
concurrency@1  queue-depth@7
```

### resources/seon/schemas/seon.config.fs.edn

SHA-256: `a45ecf985f2ebfa1ac02c3f3f6f52fe6f76cae6d09b4076773c891e24a9940a8`.

Literal pair declarations: none.

No literal pair:

```text
max-depth@1  max-glob-results@3  max-inline-bytes@5  max-read-bytes@7  max-traversal-entries@9
max-write-bytes@11  roots@13  working-root@15
```

### resources/seon/schemas/seon.config.maintenance.edn

SHA-256: `fc6e95c6aad58833a99b7728b046ebed4fd5e8e0cee89fc53c618810a97c8b14`.

Literal pair declarations: none.

No literal pair:

```text
log-max-bytes@1  log-retained-files@3  min-usable-bytes@5  min-usable-ratio@7
```

### resources/seon/schemas/seon.config.message.edn

SHA-256: `555373b14eb6d828bde6422dae8776f42e6bc70c1b80e7eb48d4d1a5d4850c59`.

Literal pair declarations: none.

No literal pair:

```text
max-chain@1
```

### resources/seon/schemas/seon.config.operator.edn

SHA-256: `11aaf4b8701dc0e0fedf4e51fa303e11a5a920ac7a9202ba793175884df53cc3`.

Literal pair declarations: none.

No literal pair:

```text
event-silence-backstop-ms@1
```

### resources/seon/schemas/seon.config.render.agent.edn

SHA-256: `d4e219d99b86ace10e91299185c0df9660b313ce80db70cc55d6aa098d6c1be2`.

Literal pair declarations: none.

No literal pair:

```text
token-budget@1  max-depth@3  max-children@5  composition@7
```

### resources/seon/schemas/seon.config.render.edn

SHA-256: `1766272a0af603cf1c0302c0eeba740f0fb7b87ae02b3306f0f9a618acf44bc5`.

Literal pair declarations: none.

No literal pair:

```text
coalesce-ms@1  issue-opening@3
```

### resources/seon/schemas/seon.config.run.edn

SHA-256: `c9ad45d3f85f85e460b4f123e7b68a3b71395ad4b3db8784fbd186c27952c1d5`.

Literal pair declarations: none.

No literal pair:

```text
max-episode-runs@1
```

### resources/seon/schemas/seon.config.shell.edn

SHA-256: `a42ee543de251b18c8c97cc39ed0f8e3accca2a5a366bb6a155fad022fb8f568`.

Literal pair declarations: none.

No literal pair:

```text
home@1  inline-output-bytes@7  lang@9  path@15  preview-bytes@21  stdin-max-bytes@23
termination-grace-ms@25  time-limit-ms@27
```

### resources/seon/schemas/seon.config.test.edn

SHA-256: `86098b956e9d85b9e2a24fa9130c6aec41b2df12ff7fad9bc2c59302d2f051d6`.

Literal pair declarations: none.

No literal pair:

```text
auto-check-cases@1
```

### resources/seon/schemas/seon.config.web.edn

SHA-256: `593ff62702b6d2ba81ef5606d070098322305e848435941737a7cd8e90ebe3bf`.

Literal pair declarations: none.

No literal pair:

```text
max-inline-bytes@1  max-redirects@3  max-response-bytes@5  max-search-results@7  port@9
search-api-key-variable@15  search-endpoint@17  search-result-projection@19  timeout-ms@21
```

### resources/seon/schemas/seon.context.capture.edn

SHA-256: `ab92c8e8e70a067f1a4ac3ed9cf2c58d96cb50faaffc1338f01876972f3bcecb`.

Literal pair declarations:

```text
capture@2*=P16
```

No literal pair:

```text
basis-t@1  contributions@31  id@33  prompt@34  run@37
```

### resources/seon/schemas/seon.context.contribution.edn

SHA-256: `765737116da964e68cbef83d9dff99e5d5792f0eedada4ce97117bfc4a4d4fce`.

Literal pair declarations: none.

No literal pair:

```text
contribution@1*  agent@26  evaluations@27  error@28  hash@29  id@30  position@32  tokens@33
```

### resources/seon/schemas/seon.context.edn

SHA-256: `7050d02589a388a6c624786b011bf13e93a6e40e3e61c06e60c36e084b2d5e3a`.

Literal pair declarations:

```text
selection-refused-error@79=P26
```

No literal pair:

```text
capture-request@1  contribution@13  contributions@29  selection@30  append-request@31
compact-request@36  remove-request@43  comparison-request@47  comparison@54  selection-refused@70
```

### resources/seon/schemas/seon.db.availability.edn

SHA-256: `cd1f037f1fa1198fbf0385ae3abcc822bdd85a7c471326a516ec8454a0f076d5`.

Literal pair declarations: none.

No literal pair:

```text
connection@4  error@7*  failed-observation@17
```

### resources/seon/schemas/seon.db.diff.edn

SHA-256: `774a66acc9a0a7fd500a760a028309180ee11ad95cd857824c116734763f462c`.

Literal pair declarations:

```text
result@23=P17
```

No literal pair:

```text
before@2  removed?@3  values-request@4  paths@7  after@10  identity@11  changed-attributes@12  change@13
added@19  removed@20  changed@21  requery-id@22
```

### resources/seon/schemas/seon.db.edn

SHA-256: `eeb912b2c030529e910047192ccad45d4524c0cab890bbe2035afa369b6b29cc`.

Literal pair declarations:

```text
database-value-identity@197=P50  transaction-report@307=P19  transaction-outcome-unknown-error@323=P27
invalid-read-error@325=P27  invalid-request-error@327=P27  diff-refused-error@329=P27
```

No literal pair:

```text
attributes@1  error-result@6  append-only-after@31  retraction-authority@32  lookup-ref-value@33  ref@40
component@43  component-schema@44  component-entity@51  db@58  read-evidence-sink@59
source-argument-position@65  pattern-entity@66  pattern-attribute@67  pattern-value@68
read-index-pattern@69  read-index-patterns@74  read-basis-t@75  read-dependency-attributes@76
read-dependency-source@77  read-dependency-plan@84  read-operation@90  read-evidence-options@91
pull-arguments@94  read-request@95  read-result@109  read-result-digest@111  dependency-revision@119
captured-read@139  read-evidence@147  entity@156  identity@157  index@158  no-history?@159  unique@160
connection@171  custody-request@179  connection-id@183  connection-identity@184  database-value@187
database-name@194  basis-t@195  current-basis-t@196  datom@203  transaction-datom@210  datoms@212
tx@213  transaction-result@214  index-page-cursor@219  index-page-options@222  index-page-result@230
datom-count@236  entity-id@237  index-lookup@239  process@245  receipt@246  pulled-entity@268
pull-many-options@270  pull-options@278  pull-selector@286  query@287  query-args@288  time-point@298
tx-data@299  transaction-report-datom@300  user@317  conflict-attribute@318  conflict-value@319
conflict-owner@320  transaction-refused@321  transaction-outcome-unknown@322  invalid-read@324
invalid-request@326  diff-refused@328
```

### resources/seon/schemas/seon.db.id.edn

SHA-256: `c19f2405b3bdb149b1843fda383b92e39422c0e07501209451ddd236e9c3137e`.

Literal pair declarations: none.

No literal pair:

```text
generator@1
```

### resources/seon/schemas/seon.db.process.edn

SHA-256: `d2ebc9e576f38d92d7274b1ecceb06d65a707d3748f626577e5a31ee387bbed9`.

Literal pair declarations: none.

No literal pair:

```text
id@1  pid@2  start-instant@3  process@4*
```

### resources/seon/schemas/seon.db.read.edn

SHA-256: `ec5fb721b30ba03f7519de316a1138191ed8dbbabbd98718166f173e64c307ee`.

Literal pair declarations: none.

No literal pair:

```text
error@4*  target@19
```

### resources/seon/schemas/seon.db.read.target.edn

SHA-256: `a4375945fb85c661228c9722911fd36434c342a6e07f5793aa502085211cd05c`.

Literal pair declarations: none.

No literal pair:

```text
arguments@4  attribute@10  entity@13*  entity-projection@43  index@49  index-request@52  query@58
selector@64
```

### resources/seon/schemas/seon.db.write.attempt.edn

SHA-256: `52d7fa05d35846a3545813c3090893ad0c566a0fd97f9b8e645f20e700c5b2ab`.

Literal pair declarations: none.

No literal pair:

```text
transaction@4  bound-ms@8  completion-unavailable@11  entity@14*  observed-at@37  operations@40
rejection@46  request-id@52  submitted-at@55  waited-ms@58
```

### resources/seon/schemas/seon.db.write.edn

SHA-256: `cb9dcc8342b176fa0b96cd5e32323f9ba708939481646e4aa14308fb23f9996a`.

Literal pair declarations:

```text
validation-refusal@4=P18
```

No literal pair:

```text
attempt@17  error@23*
```

### resources/seon/schemas/seon.dev.mcp.artifact.edn

SHA-256: `2c7ba5864b398574bc9201e0388ab139312bfaa78f96a4b1cb2718cf942eff6e`.

Literal pair declarations:

```text
root-not-committed-error@9=P27
```

No literal pair:

```text
id@2  digest@3  entity@4*  root-not-committed@8
```

### resources/seon/schemas/seon.dev.mcp.edn

SHA-256: `4dc0349f88a6c9ca806d61eeff6c9ece6e22bdda86c8a7dbd4742328cc6fc8f5`.

Literal pair declarations:

```text
cluster-degraded-error@2=P25  value-not-found-error@4=P25  remainder-not-retrievable-error@6=P25
jvm-exception-error@7*=P25  projection-failed-error@18*=P25
```

No literal pair:

```text
cluster-degraded@1  value-not-found@3  remainder-not-retrievable@5  projection-offending-class@17
projection-failed-result@28  error@34*  error-cluster@44  request-observation@47
```

### resources/seon/schemas/seon.dev.process.edn

SHA-256: `3e9c151326367c857bb56f6fe1cd628a7b6a9ff3e68e71470ed6f3bdfe6a23e7`.

Literal pair declarations: none.

No literal pair:

```text
pid@1  start-instant@2  generation@3  root@4  identity@5
```

### resources/seon/schemas/seon.edit.edn

SHA-256: `6980218b0af32ee4308f74c5c8863906e9b4173738b3df89f3a6853cefa18b35`.

Literal pair declarations: none.

No literal pair:

```text
candidate@1  request@15  cause@20  form-count@21  candidate-evidence@22  candidates@31
candidates-complete?@32  lines@33  lines-complete?@34  line-count@35
```

### resources/seon/schemas/seon.effect.edn

SHA-256: `c634b678c35614862fe753230d2a94a0edc7bf07a55feadcc6527a46643863ac`.

Literal pair declarations:

```text
receipt@60*=P20  already-recorded-error@164=P27  already-settled-error@166=P27
missing-receipt-error@168=P27
```

No literal pair:

```text
capability@1  eval@2  file@8  form-span@14  program@19  provenance@25  content-blobs@31  background?@32
disposition@33  execution-options@34  time-limit-ms@42  form-ordinal@43  duration-ms@44  id@45
interrupted-at@46  arguments@47  open-request@51  opened-at@57  ordinal@58  owner@59
request-context@104  notify@126  request-edn@127  result-edn@128  result-blob@129  result-size@130
run@131  settle-request@132  settled-at@153  to@154  already-recorded@163  already-settled@165
missing-receipt@167  capability-symbol@171  error@174*  execution-observation@185  request-identity@191
```

### resources/seon/schemas/seon.env.edn

SHA-256: `4007e47de066ebe279670b16bd4d03aab062b88ad6c8ba0de5311776e40ddba6`.

Literal pair declarations:

```text
absent-environment-error@84=P27  agent-id-absent-error@86=P27  incomplete-environment-error@88=P27
invalid-environment-replacement-error@90=P27  invalid-environment-state-error@92=P27
invalid-member-error@94=P27  schema-absent-error@96=P27  unscopable-member-error@98=P27
```

No literal pair:

```text
layer@1  environment@2  absent-environment@83  agent-id-absent@85  incomplete-environment@87
invalid-environment-replacement@89  invalid-environment-state@91  invalid-member@93  schema-absent@95
unscopable-member@97  error@101*  error-member@110  member-expectation@113
```

### resources/seon/schemas/seon.error.basis.edn

SHA-256: `7c33fac0309698f31eff89280d9dadfbe88e70336f90d7d18e104c49fd0ba2d8`.

Literal pair declarations: none.

No literal pair:

```text
branch@4  commit@7  entity@10*  store@18  t@21
```

### resources/seon/schemas/seon.error.disposition.edn

SHA-256: `b95377e13736e52ed27f295e35ad4adbf74621d2ee1710fbd5e4032b40ad70b0`.

Literal pair declarations: none.

No literal pair:

```text
action@4  evidence@7  graph-id@13  observation@16*  observer@31
```

### resources/seon/schemas/seon.error.edn

SHA-256: `c29c3dc7a058c13c05b74401bc46868764dd595a9211cce4cf1e46d920dfc3aa`.

Literal pair declarations:

```text
fact@101=P27  error@182*=P27  of-steward@213=P28  agent@244=P28
```

No literal pair:

```text
throwable@1  explain-request@4  problem-description@14  run@25  data-blob@26  doc@27  evidence@35
value@36  notice-request@37  capped?@54  occurrences@55  occurrence-count@56  reason@57  op@63
data-edn@64  refusal-value@65  source@72  normalize-request@80  id@95  message@96  refusal-shape@97
proc@98  dropped-fault-digest@99  cid@100  commit-tx-request@164  ref@197  recording@198
prepare-request@205  data@206  prepared@207  steward@212  data-content@222  occurrence@223  notice@224
basis-t@243  data-size@252  at@253  throwable-class@254  notification-limit@255  notification@256
dropped-fault-count@257  signature@258  frame@259  exception-class@260  regressions@261  issue@262
resolved-tx@263  process@264  base@267*  basis@293  cause@299  evidence-items@302
evidence-unavailable@308  expected-key@311  expected-shape@314  fix@317  layer@320  location@323
member@329  offending-projection@332  operation@338
```

### resources/seon/schemas/seon.error.evidence.edn

SHA-256: `df2e540f64993a6b6ab9c11cd2db7d74d6c73d244068584374ecdbead2ffb620`.

Literal pair declarations: none.

No literal pair:

```text
attribute@4  entity@7*  projection@23  value@29
```

### resources/seon/schemas/seon.error.key.edn

SHA-256: `b88f2d78bfcb30dca50499b01d15a732512fa733f033160d743a722002ee3290`.

Literal pair declarations: none.

No literal pair:

```text
bound-bytes@4  capped?@7  entity@10*  projection@18  scalar@21
```

### resources/seon/schemas/seon.error.location.edn

SHA-256: `7d0408b7506854866f093fdee565f02d68e07c8a4677489bd09c298f46f7c0e4`.

Literal pair declarations: none.

No literal pair:

```text
entity@4*  length@20  omission@23  segments@29
```

### resources/seon/schemas/seon.error.location.segment.edn

SHA-256: `77dcead157d398fd18c3822fa2722733c6ad07bf963e8cf9d7aec0147ce1da58`.

Literal pair declarations: none.

No literal pair:

```text
entity@4*  key@11  ordinal@17
```

### resources/seon/schemas/seon.error.occurrence.edn

SHA-256: `988113276d9c1f4af9b4bc63950510e78061faadd3da656b0be9799f85056324`.

Literal pair declarations: none.

No literal pair:

```text
id@2  count@3  first-at@4  last-at@5  process@6  agent@7  turn@8  proc-fn@9  data-blob@10  message@11
blob-digest@12  blob@13*  occurrence@16*  cluster@46  evaluation@49
```

### resources/seon/schemas/seon.error.omission.edn

SHA-256: `3c591874bbdc3491312b8641e7cb8c08fec72c7c1b7d038fb549d0546b870a49`.

Literal pair declarations: none.

No literal pair:

```text
bound@4  bound-key@7  entity@10*  omitted-count@29  retained-count@32  unavailable-count-reason@35
```

### resources/seon/schemas/seon.error.projection.edn

SHA-256: `aa816a4d19e9f5846ccad419657dd79b619312e7ac9864a4d25034e1af875b08`.

Literal pair declarations: none.

No literal pair:

```text
bound-bytes@4  entity@7*  missing-member@29  omission@35
```

### resources/seon/schemas/seon.eval.drive.edn

SHA-256: `db54615ab544ff0468a92628db1184d0441963838f8ff90acbb94a8cb56eafaf`.

Literal pair declarations:

```text
absent-error@59=P27
```

No literal pair:

```text
nonblank-string@1  episode-request@5  sample-request@12  episode-result@19  absent@38  value@39
evaluation@40  run-ids@52  run-cap@53  outcome@54  terminal-state@55  driver-observation@62  error@68*
error-evaluation@78
```

### resources/seon/schemas/seon.eval.edn

SHA-256: `2d0aa76aedab58cfffdd4f74d3cfc8a7071cd4de525f28505bdd7f6abd75f8e9`.

Literal pair declarations:

```text
entity@5=P52
```

No literal pair:

```text
origin@1  renderer@2  renderer-fn@3  host-interop-count@4  duration-ms@37  fn-entries@38
allocated-bytes@39  outcome@40  shown@41
```

### resources/seon/schemas/seon.export.edn

SHA-256: `761acf7e47b21322ea598e7f70db24ca52288d2f2ecd2300b7c05fd3ef95a81b`.

Literal pair declarations: none.

No literal pair:

```text
parent-dir@1  path@2  request@3
```

### resources/seon/schemas/seon.failure.edn

SHA-256: `af809528569ec2ca6c42fdef810edd9e2d8d1f9dc21b1b17f97f94213c5f5515`.

Literal pair declarations: none.

No literal pair:

```text
entity@4*  fault@11  requested-tx@14  stopped-tx@17
```

### resources/seon/schemas/seon.flow.edn

SHA-256: `9ca573ffe08db7d55f014225d560d9f5d5df6ee704aa0e11f6335c9efbdd988e`.

Literal pair declarations:

```text
submission-capacity-error@196=P27  launcher-stopped-error@198=P27  time-limit-error@200=P27
configuration-error@202=P27  timeout-error@204=P27  fault-channel-overflow-error@206=P27
```

No literal pair:

```text
active-work@2  buffer-capacity@8  callback-result@9  capacity-observer-request@24  channel@28
commit-drop!@33  commit-fault!@37  completion@41  compute-timeout-ms@42  core-fault@43  error-fanout@44
error-fanout-request@54  executor@74  fault-committer-proc-request@79  future@89  graph@94  join!@99
join-error-request@104  launcher@110  launcher-configuration@115  panic!@127  parallelism@131
read-core-error-mode@132  started@134  started!@138  step-var@139  submission-id@145
submission-wait-ms@146  io-complete!@147  io-submission@148  work-call@155  work-fn@160
work-launcher@162  work-launcher-request@170  work-result@175  work-submission@186  workload@193
proc-id@194  submission-capacity@195  launcher-stopped@197  time-limit@199  configuration@201
timeout@203  fault-channel-overflow@205  control-observation@209  error@215*  error-graph@224
```

### resources/seon/schemas/seon.fn.argument.edn

SHA-256: `0fa051ebfc64102b57ced56467f46361515afb8dcc7c9ca9bf31603a7fc8ab63`.

Literal pair declarations: none.

No literal pair:

```text
order@1  index@2  rest?@3  binding@4  schema@5  rest-tail-schema@6  rest-element-schema@7  label-edn@8
label-keyword@9  label-string@10  label-symbol@11  row@12*
```

### resources/seon/schemas/seon.fn.arity.edn

SHA-256: `2bc3e53462d07b324c971e94c389a0dd04738e528f6c54d8ba3032fbfc802d94`.

Literal pair declarations: none.

No literal pair:

```text
arity@1  guard-refs@2  input-schema@3  input-refs@4  arguments@5  argument-count@7  return-schema@8
guard-schema@9  max@10  min@11  order@12  output-refs@13  row@14*  error-facet-digest@43
error-facets@46
```

### resources/seon/schemas/seon.fn.binding.child.edn

SHA-256: `328ae464b858d2bef175e55caf72536999ab15dfa5d620f4b0f8b63274a4fca2`.

Literal pair declarations: none.

No literal pair:

```text
order@1  role@2  binding@3  row@5*
```

### resources/seon/schemas/seon.fn.binding.edn

SHA-256: `6fc0bc6e03a37bace2c2cbe1563f36d2c69888ab8d2d393fefc65500782240f7`.

Literal pair declarations:

```text
unsupported-error@23=P27
```

No literal pair:

```text
form@1  shape@2  symbol@3  children@4  entries@6  row@8*  unsupported@22  binding-projection@26
error@32*  source-location@42
```

### resources/seon/schemas/seon.fn.binding.entry.edn

SHA-256: `52daccdc0a886cb2cc4f58a98f4bca1f4afd16feb5116c0d9856baf3090227af`.

Literal pair declarations: none.

No literal pair:

```text
order@1  spelling@2  binding@4  default-edn@6  row@7*
```

### resources/seon/schemas/seon.fn.contract.edn

SHA-256: `6a2c19c6f889d7e43d842fac8245da0f622af0a205593b66549c0758ccf97700`.

Literal pair declarations: none.

No literal pair:

```text
finding@2  position@13  form@18  caller-count@19  finding-row@20  report@27
```

### resources/seon/schemas/seon.fn.contract.finding.edn

SHA-256: `7f7332bb3be59b1c048d6f1b4d024ededde306220fd1c106749b1f3e165539bb`.

Literal pair declarations: none.

No literal pair:

```text
error-facet-analysis-unavailable@4  undeclared-error-facet@7
```

### resources/seon/schemas/seon.fn.edn

SHA-256: `d2c2f21505bb0f8b19a85f416115d96575cb2daff02cb6bcab92e4ce1fa6ec6f`.

Literal pair declarations:

```text
fn@109*=P40  index-transaction-refused-error@190=P24  source-checkout-required-error@192=P24
source-span-absent-error@194=P24  capability-graph-malformed-error@196=P24
analysis-failed-error@198=P24  source-file-invalid-error@200=P24  manifest-absent-error@202=P24
duplicate-program-identity-error@204=P24  population-incomplete-error@206=P24
schema-declaration-invalid-error@208=P24  scratch-not-fresh-error@210=P24  index-refused-error@212=P27
signature-refused-error@214=P27
```

No literal pair:

```text
gate-request@1  root@4  source-path@5  arglists@6  reference-to@7  file@8  form-span@9  internal?@10
doc-order@11  arglists-override?@12  macro?@13  defined-by@17  arities@30  calls@31  destroys@32
references@50  unresolved-references@51  writes@52  call-arities@56  caller@71  callee@72  call-arity@73
declared-arities@74  prepared-arities@79  arity-mismatch@80  arity-mismatches@87  arity-checked@88
arity-unchecked@89  arity-mismatch-report@90  external-sink@101  doc@103  keywords@104
changed-paths@155  index-request@156  ns@171  private?@172  projection-boundary@173  roots@175
source@176  spec@177  sym@178  workload@183  index-phase@184  capability-rule@185  resource@186
missing-population@187  existing-program-entity@188  index-transaction-refused@189
source-checkout-required@191  source-span-absent@193  capability-graph-malformed@195
analysis-failed@197  source-file-invalid@199  manifest-absent@201  duplicate-program-identity@203
population-incomplete@205  schema-declaration-invalid@207  scratch-not-fresh@209  index-refused@211
signature-refused@213  analysis-phase@217  error@220*  error-facets@229  error-subject@232
```

### resources/seon/schemas/seon.fn.file.edn

SHA-256: `4b56b9f5a96605fe28c149f54596a8c817bf75ec1569f79c771ec18cf4c98f33`.

Literal pair declarations: none.

No literal pair:

```text
relative-path@1  digest@4  relative-root@5  file@9*  rows@17  identities@18  findings@19
declaration-digests@20  first-party-functions@22  artifact@23
```

### resources/seon/schemas/seon.fn.finding.edn

SHA-256: `a7bd24ebd2ca1a08d962858ab211d6099e82c5752db223f716531ef87d1246aa`.

Literal pair declarations: none.

No literal pair:

```text
relative-path@1
```

### resources/seon/schemas/seon.fn.manifest.edn

SHA-256: `e60dccc650dc76cd7e0261efb6cde163f197a06669e162f8ec4f2bfff4442eca`.

Literal pair declarations: none.

No literal pair:

```text
root@1  relative-roots@2  digest@3  artifacts@4  identities@5  findings@6  declaration-digests@7
manifest@8
```

### resources/seon/schemas/seon.fn.output.edn

SHA-256: `50e47bbc2aabeede6dbbf470062ed3bb45ba5d226de91365f2cede1f8c102f3e`.

Literal pair declarations: none.

No literal pair:

```text
ai-paths@1  bypasses@2  classification@3  codec-paths@5  counterexamples@6  external-sink@7
first-bypass@8  html-paths@9  path@10  path-report@11  paths@26  projected@27  report@28
report-artifact@32  required-projection@37  sink@38  sinks@39  source@40  totals@41  unresolved@50
```

### resources/seon/schemas/seon.help.edn

SHA-256: `5cf96d88303452a6437da35d1c18f52b439c2fd226da65d2695cd5bac7c3f440`.

Literal pair declarations:

```text
help@2=P07
```

No literal pair:

```text
lines@1
```

### resources/seon/schemas/seon.id.edn

SHA-256: `803a49777086a256cadb9b3749b1d5daa8ec17dd7cc05a7871e294c93dab4600`.

Literal pair declarations: none.

No literal pair:

```text
character@1
```

### resources/seon/schemas/seon.instrument.arity.edn

SHA-256: `7c2bcbd96bb9ca3c0f72d7794149020ce0427fdcbe3500f751a6680cb401d541`.

Literal pair declarations: none.

No literal pair:

```text
bounds@4*  max@18  min@21  ordinal@24
```

### resources/seon/schemas/seon.instrument.edn

SHA-256: `bec22aac53ea8f99fa1d2d0d6c0e933e72df490b023eb98bfa9b6c6b9d3e6864`.

Literal pair declarations: none.

No literal pair:

```text
applied@1  args@7  actual@13  actual-size@19  arm@20  expected@21  fn@22  instrumented@23  registered@24
request@25  loaded-var@41  callable@46  actual-facet-count@53  actual-facets@56  arity@59
arity-error@62*  check@80  contract-error@83*  declared-arities@99  declared-arity-count@105
declared-facet-count@108  declared-facet-digest@111  declared-facets@114  explanations@117
refusal-result@123  registration-error@129*  registration-observation@139  returned-error@145
undeclared-error@151*
```

### resources/seon/schemas/seon.instrument.explanation.edn

SHA-256: `333fedc04df1075a0e6bfec5a0d897b46ed27827a45bd2f626aa693be8416a2d`.

Literal pair declarations: none.

No literal pair:

```text
actual@4  entity@10*  expected-shape@39  humanization-unavailable@42  humanized@45  ordinal@51
problem-type@54  schema-location@57  value-location@63
```

### resources/seon/schemas/seon.instrument.explanations.edn

SHA-256: `2886f51889aebbf0ef446b74934e18b5eab1c326134ffa678fd85395aacda832`.

Literal pair declarations: none.

No literal pair:

```text
count@4  entity@7*  items@23  omission@29
```

### resources/seon/schemas/seon.instrument.humanized.edn

SHA-256: `cec5cc2ea5d6ce13a244ac01e96c39b908c844aee0bf16dd66838d02bc95463a`.

Literal pair declarations: none.

No literal pair:

```text
entity@4*  message-count@21  messages@24  omission@30
```

### resources/seon/schemas/seon.instrument.humanized.message.edn

SHA-256: `df5c4f4e87ae38677abc781bfca9be7c9ab0f679f3c07291ef56b51e3cb6cf31`.

Literal pair declarations: none.

No literal pair:

```text
entity@4*  location@14  ordinal@20  text@23
```

### resources/seon/schemas/seon.issue.citation.edn

SHA-256: `5e1d24eb57fa2f7045eaa437fe22c355e4d8ba950f4d379849aa1f8fc1213724`.

Literal pair declarations: none.

No literal pair:

```text
id@1  file@4  row@6  end-row@7  citation@8*
```

### resources/seon/schemas/seon.issue.edn

SHA-256: `5741e65bb6445a7f00afba525a4b8ed9d26fa98187b3314b9c6b7a18a4802183`.

Literal pair declarations:

```text
issue@45*=P30
```

No literal pair:

```text
id@1  title@2  status@3  severity@4  opened@5  path@6  problem@7  functions@8  tests@10  errors@14
keys@16  namespaces@18  files@20  runs@23  issues@25  unresolved@27  commits@28  members@29  agent@30
created-by@34  detector@35  budget@36  budget-exhausted-tx@37  resolved-tx@38  status-view@39
```

### resources/seon/schemas/seon.lint.edn

SHA-256: `6c5d61f1b323a59f91976557d2348fcf6e8cccf55bbc3627e8b7ff8425ed851e`.

Literal pair declarations: none.

No literal pair:

```text
id@1  fn@4  file@5  type@6  level@7  message@8  row@9  col@10  finding@11*
```

### resources/seon/schemas/seon.listen.edn

SHA-256: `1607361a8dc8cafd7053f13a68380d0e5e5291b710d68068b615143bc76c4df3`.

Literal pair declarations: none.

No literal pair:

```text
attribute@1  entity@2  value@3  pattern@5*
```

### resources/seon/schemas/seon.maintenance.edn

SHA-256: `57256ba0cc5094c8e6fe38ff318a565c4fd0f763f64168f391ee3b0c8ceb2ad9`.

Literal pair declarations:

```text
report@21=P31
```

No literal pair:

```text
result-projection@1  attention-when@2  fact-map@3  entry@4  entries@17  receipt-facts@18
result-facts@19  error-facts@20  collection-record@26  result-entity-request@60
result-entity-response@61
```

### resources/seon/schemas/seon.maintenance.receipt.edn

SHA-256: `17cba35b0e4a89827ee9d27b437a93972a29270f1a128dd66cc46e607073efb3`.

Literal pair declarations: none.

No literal pair:

```text
id@1  fire@3  task@5  handler@7  request@9  started-at@11  completed-at@13  result@15  error@17
interrupted-at@19  receipt@21*
```

### resources/seon/schemas/seon.maintenance.request.edn

SHA-256: `2b9f00c030180ba2d4e22d14ab447559ef81ae975f6b0d9bb7b8543093fa2cdc`.

Literal pair declarations: none.

No literal pair:

```text
id@1  task@3  fire@5  handler@7  agent@9  cluster-name@11  repository-root@12  managed-root@13
log-dir@14  nominal-at@15  observed-at@16  entity@17*  value@56
```

### resources/seon/schemas/seon.maintenance.result.edn

SHA-256: `78e2954d9bd62ab5fd4f37c4b071016709038312eb7bf766d72ef957035a0e81`.

Literal pair declarations: none.

No literal pair:

```text
id@1  process-census-roots@3  process-census-processes@7  process-census-dead@11
process-census-unresponsive@15  process-census-unclaimed@20  process-census-claim-errors@25
reap-census@30  reap-stopped-processes@32  reap-roots@34  reap-refused@36  cluster-cleanup-collection@41
collect-branches@43  root-claim-id@45  root-claim-path@46  root-claim-creator-pid@47
root-claim-creator-start-instant@48  root-claim-reap-on-owner-exit?@50  process-census-root@52*
process-census-process@65*  process-census-identity@81*  process-census-claim-error@91*
process-census-component@98*  reap-stopped-process@123*  reap-root@133*  reap-refusal@141*
collection-error@148*  cluster-cleanup-collection-component@164  reap-component@167*
cluster-cleanup-component@190*  collect-branch@214*  collect-component@219*  entity@245*  value@249
```

### resources/seon/schemas/seon.message.edn

SHA-256: `21da1ccc54c889b1ab4d754a330154bc9e92cb136bfe299edd398c40b1f24842`.

Literal pair declarations:

```text
blank-content-error@1=P27  to@19=P13  no-limit-error@52=P27  message@61*=P12
content-too-large-error@132=P27  chain-limit-error@142=P27  unknown-recipient-error@157=P27
```

No literal pair:

```text
reply-request@11  content-too-large@18  about@31  assignment@34  inbound-content@37  no-limit@38
inbound-request@39  id@46  inbox-unit@47  pulled@83  caused-by@103  from@109  inbound@116
delivery-request@118  chain-limit@129  content@130  size@131  blank-content@151  unknown-recipient@152
delivery@153  limit@167  error@170*  error-request@178
```

### resources/seon/schemas/seon.ns.alias.edn

SHA-256: `fff97a1c4b7d2165d89871025ee3885544821ccd11676d12e544cbce8f654028`.

Literal pair declarations:

```text
binding@1*=P42
```

No literal pair:

```text
local@10  target-ns@14
```

### resources/seon/schemas/seon.ns.edn

SHA-256: `aef5727260d0b20e4205415eebb98e3df7319f0c17925e0952a9273d7e6777c0`.

Literal pair declarations:

```text
ns@10*=P41
```

No literal pair:

```text
context-relevant?@1  aliases@2  doc@3  imports@4  name@5  refers@31  requires@32  source@33  steward@34
```

### resources/seon/schemas/seon.ns.import.edn

SHA-256: `83a647113145e70a777e46ac701046ed812f6f96a6005d6f7af6c13a3ff7c78d`.

Literal pair declarations:

```text
binding@1*=P43
```

No literal pair:

```text
local@12  target-class@16
```

### resources/seon/schemas/seon.ns.refer.edn

SHA-256: `6ae0a0f17791220711e87a0b3927487e3783caf5fd8847a0396235f5a1075548`.

Literal pair declarations:

```text
binding@1*=P44
```

No literal pair:

```text
local@12  target-name@16  target-ns@20
```

### resources/seon/schemas/seon.operator.claim.edn

SHA-256: `15ad0f6d34990f8a6bc27c39b127ec7c61189b36169b94cb94b0854e260a4ff1`.

Literal pair declarations: none.

No literal pair:

```text
id@1  root@2  repository-root@3  store@4  ephemeral?@9  reap-on-owner-exit?@10  creator@11  clusters@12
claimed-at@13  created-at@14  destroyed-at@15  cleanup@16  footprint@17  live?@18  processes@19
record@20  path@53  read-error@54
```

### resources/seon/schemas/seon.operator.cleanup.edn

SHA-256: `a745583b70a308673c6e38e4cf583f1d9f7ed2d28aa4c620bef95d7e23d9c758`.

Literal pair declarations: none.

No literal pair:

```text
reclaimed-bytes@1
```

### resources/seon/schemas/seon.operator.cluster-cleanup.edn

SHA-256: `f1b8c5e96db88c86fd16cd2e588bb463b2a3f47a5b25df69757f66a00fa20429`.

Literal pair declarations: none.

No literal pair:

```text
request@1  managed-root@12  live-instance-stopped?@13  branch-retired?@14  removed@15  collection@16
remaining@24  reclaimed-bytes@25  complete?@26  result@29
```

### resources/seon/schemas/seon.operator.collect.edn

SHA-256: `8f64db43b3e3b63ddfded1eccd94252e7035ca0fba69910afd832a6148968bda`.

Literal pair declarations:

```text
unrecognized-option-error@13=P27
```

No literal pair:

```text
request@1  dry-run?@10  option-key@11  unrecognized-option@12  store-id@14  managed-root@15  branch@16
branches@20  objects-before@21  objects-after@22  swept-objects@23  bytes-before@24  bytes-after@25
reclaimed-bytes@26  verification-pass-swept@27  roots-verified?@28  unstored-digests@29
unverified-branch@30  unverified-digest@31  retained-files@32  candidate-files@33  candidate-bytes@34
mark-duration-ms@35  projected-duration-ms@36  complete?@37  result@38  error@95*  error-option@106
expected-option@109
```

### resources/seon/schemas/seon.operator.edn

SHA-256: `4837fcfbcefbb8ddbb9261ab3e5fbdff01682928c86e0ff750c1646a0fd36f90`.

Literal pair declarations:

```text
low-disk-space-error@3=P27  failed-error@119=P27  cluster-cleanup-incomplete-error@121=P27
collection-incomplete-error@123=P27  process-census-incomplete-error@125=P27
reap-incomplete-error@127=P27
```

No literal pair:

```text
advertisements@1  low-disk-space@2  repository-root@4  managed-root@5  ephemeral-owner@6  root-request@7
cleanup-request@15  footprint-request@19  log-request@29  existence-request@36
process-census-request@39  low-space?@46  footprint@48*  footprint-observation@60  cleanup-result@65
claim-errors@74  roots@75  existence@76  log-result@80  branches@81  census@82  changed-paths@87
clusters@88  flow@89  health@90  observation@91  publish-request@99  refork-request@105  readiness@114
status@115  failed@118  cluster-cleanup-incomplete@120  collection-incomplete@122
process-census-incomplete@124  reap-incomplete@126  error@130*  error-root@140
operation-observation@143
```

### resources/seon/schemas/seon.operator.footprint.edn

SHA-256: `fc232e99f3794a9e4b844e5d9e0c68564ba0b7dfdbdeebbde2438ef2200d0dca`.

Literal pair declarations: none.

No literal pair:

```text
root@1  file-bytes@2  usable-bytes@3  total-bytes@4  usable-ratio@5  observed-at@6
```

### resources/seon/schemas/seon.operator.log.edn

SHA-256: `23b6a7b4f7d2fea29a1369a855f77f750e18b351010c07d6fe3c514023e444dc`.

Literal pair declarations: none.

No literal pair:

```text
path@1  bytes-before@2  bytes-after@3  rotated?@4  retained-files@5  result@6*
```

### resources/seon/schemas/seon.operator.process-census.edn

SHA-256: `ca1752529fb4adfed61e70c6b3e3b812e85fb615047916edf89d587632d0a0c5`.

Literal pair declarations: none.

No literal pair:

```text
alive?@1  responsive?@2  advertisements@3  process@5  root@17  observed-at@31  roots@32  processes@33
dead@35  unresponsive@36  unclaimed@40  claim-errors@44  complete?@48  result@51
```

### resources/seon/schemas/seon.operator.process-record.edn

SHA-256: `ce7625a20bb526ca2b7568ca4e07f627ccfc541cea449575638f9d91d446c89a`.

Literal pair declarations: none.

No literal pair:

```text
repository-root@1  generation@2  root@3  log@4  cache-path@5  record@6
```

### resources/seon/schemas/seon.operator.reap.edn

SHA-256: `a4bfa143e797322bbe97c31e5fd0fcf4cff9d3c9a06aaac06275ffd4fa085cfb`.

Literal pair declarations: none.

No literal pair:

```text
reason@1  stop-path@2  request@4  stopped-process@13  root@22  refusal@28  observed-at@34  census@35
eligible-root-claims@36  stopped-processes@38  roots@40  refused@41  reclaimed-bytes@45  complete?@46
result@48
```

### resources/seon/schemas/seon.plan.edn

SHA-256: `ee5c0306782de5c972b19c002f15c370d6d22068e76baae31932c63212116d0d`.

Literal pair declarations: none.

No literal pair:

```text
current-line@1  step-lines@3  update-example@5
```

### resources/seon/schemas/seon.print.edn

SHA-256: `a4afb5e847a37e009aa94d846c4897602c540f5cf62c4e66fcba1d1f142f5572`.

Literal pair declarations:

```text
elision@346=P37  unknown-face-error@396=P27
```

No literal pair:

```text
default@1  face@2  reference@28  references@29  identity-attributes@30  length@31  level@36
namespace-maps?@39  ordered?@40  node-child@42  node-face@53  node@258  options@266  result@287
omitted@291  elisions@292  elision-unit@293  requery-id@295  requery-form@296  requery-refusal@299
bound-by@300  text@301  prefix@302  elision-request@303  elision-base@330  sink@383  table?@388
width@394  unknown-face@395
```

### resources/seon/schemas/seon.problems.edn

SHA-256: `543cd12fc98b4bfd3e08fd17f65c9f9a87ae6686cd6216825fbeafb1a9537d6d`.

Literal pair declarations:

```text
missing-model@28=P39  problems@66=P38
```

No literal pair:

```text
failed-tests@1  errored-receipt@6  id@14  form-problem@15  unowned-namespace@26  failed-run@33
stale-var@37  deferred-agent@52  form-problem-request@58  request@65  error-signature@93
occurrences@102  author@103  detector-observation@106  error@112*  error-detector@122
```

### resources/seon/schemas/seon.program.edn

SHA-256: `039c14f9147936127da19d34c98e101369396ed306a1d88f3c784dd6996226c1`.

Literal pair declarations:

```text
no-declaration-at-error@107=P27  declaration-refused-error@115=P27  breakage@176=P49
read-refused-error@222=P27  not-found-error@230=P27
```

No literal pair:

```text
analyzed-source-digest@1  analyzed-count@4  edge-symbol@5  unresolved-callers@14  stale-test-reach@18
unresolved-report@21  identity-attribute@26  source-attribute@29  row-schema@32  written-by@39
projected-properties@46  schema-row-properties@53  owned-attributes@54  identity@56  declaration-row@58
delete-identities@77  source@78  ns@79  deletion-row@80  row@85  rows@87  shape@88  shapes@93
position@105  no-declaration-at@106  declaration-refused@114  subject@122  kind@125  contract@128
callers@129  references@130  subject-of@131  stale-reach@132  gating@133  call-sites@134
render-declared-by@141  capability-of@144  schedule-tasks@145  writes-of@146  contract-refs@147
schema-references@148  requiring-namespaces@149  owned-declarations@150  mentions@151  data-in-use@154
gap@157  unknown@162  plan@170  definition@201  history-entry@204  history@212  history-report@213
read-refused@221  not-found@229  affected@237  change@238  error@246*  error-facet-arity@255
error-facet-result@264  error-subject@270  source-observation@276
```

### resources/seon/schemas/seon.reconcile.edn

SHA-256: `0ec79e42aaa9f9ed2725a925dccf0a1b1bd6a3b92930f563c74181c354c6f224`.

Literal pair declarations:

```text
refused-error@23=P27  no-identity-error@25=P27  two-identities-error@27=P27
duplicate-identity-error@29=P27  identity-outside-scope-error@31=P27  missing-declarations-error@33=P27
```

No literal pair:

```text
adopt-identities@1  converged?@2  desired@3  operations@4  process@5  request@6  result@13  rule@21
refused@22  no-identity@24  two-identities@26  duplicate-identity@28  identity-outside-scope@30
missing-declarations@32  constraint-observation@36  error@42*  requested-identity@53
```

### resources/seon/schemas/seon.render.block.edn

SHA-256: `1458214633a419270fa130b955ff12c4974aa2fa1d986a1b0b65b8d3c316e089`.

Literal pair declarations: none.

No literal pair:

```text
name@1
```

### resources/seon/schemas/seon.render.call.edn

SHA-256: `cfc7f22dc8e905d30affca29f4dfdb9035b340d1b188c6c5699dddcaccb76e0f`.

Literal pair declarations: none.

No literal pair:

```text
id@2  entry@3
```

### resources/seon/schemas/seon.render.cost.edn

SHA-256: `baa4bfc24264667b409726fd5de0d8cabf7f1ca115831fa7e38e5cbf309a8279`.

Literal pair declarations: none.

No literal pair:

```text
shape-key@1  profile@2  estimated-tokens@3  at@4  fact@5*
```

### resources/seon/schemas/seon.render.data.edn

SHA-256: `822a93da0e916ec9e0afd8b94739e2e9b8615dd2d1296f9641305ca40570518e`.

Literal pair declarations:

```text
no-such-path-error@30*=P27  observation-error@36*=P27
```

No literal pair:

```text
cursor@1  offset@6  path@7  total@10  next-offset@11  window@12  refused-member@35  subject@41  eid@42
snapshot@43  limit@44  max-result-weight@45  max-ref-attributes@46  attribute-offset@47
ref-attributes-probed@48  direction@49  index-cursor@50  continuation@51  datoms@58  complete?@59
identities-complete?@60  page@61  outgoing@67  incoming@68  identities@69  outgoing-cursor@70
incoming-cursor@71  observation-request@72  observation@81  error@94*  error-root@104
requested-location@110  root-description@116  requested-path-length@118
```

### resources/seon/schemas/seon.render.debug.edn

SHA-256: `892cfd10732e29d5d46bd8f0c8f21ee665d95dbec107029812c87f3934a5ac61`.

Literal pair declarations: none.

No literal pair:

```text
details?@1
```

### resources/seon/schemas/seon.render.edn

SHA-256: `b4ebe2b046dfe763f1388b28d3bdaf734456f06d77dd6c9950fd0ef00d5d176f`.

Literal pair declarations:

```text
call-request@20=P49  unknown@142*=P51  ambiguous-error@224*=P27  invalid-output-error@226*=P27
walk-failed-error@228*=P27  request-error@251*=P27
```

No literal pair:

```text
cache@1  acquired-context@2  context-action@8  context-change-request@9  context-request@16
context-change-result@18  ai@19  source@67  source-blocks@68  candidate-request@74  candidates@84
selection-request@85  selection-candidate-status@89  selection-candidate-reason@91
selection-candidate@93  selection-stage-name@102  selection-stage-status@105  selection-stage@107
selection@122  distance@129  unknown-reason@130  unknown-request@131  failure@151  failure-request@156
form@161  hiccup@164  html@170  units@171  namespace@176  output@177  rendered@179  profile@180
output-schema@181  package@182  page@204  surface-id@205  unit@206  value@214  rendering@221
would-fall-to-floor?@222  invalid-output@225  error@231*  input-projection@240  requested-output@246
refused-member@249  walk-operation@253  error-result@255
```

### resources/seon/schemas/seon.render.hiccup.edn

SHA-256: `10479cf2109560b828406d24b1e9fc33b8b9544781f7c5e9756102ff53257ffb`.

Literal pair declarations:

```text
unparseable-tag-error@3*=P27
```

No literal pair:

```text
tag@1  unparseable-tag@2
```

### resources/seon/schemas/seon.render.history.edn

SHA-256: `3105bd2e9ba14680257dee45f781bb74d2d7d835a81a20eb94453f79b8b8ef9d`.

Literal pair declarations: none.

No literal pair:

```text
bytes@6  unit@7  priced-unit@11  units@15  selection@21
```

### resources/seon/schemas/seon.render.lint.edn

SHA-256: `cff5ea8afbd6670e54959fbc868515285bc79161596e85002ee19a668a679257`.

Literal pair declarations:

```text
absent-element-error@75*=P27
```

No literal pair:

```text
defect@1  path@8  tag@9  classes@10  characters@11  nodes@12  excerpt@13  repeats@14  unclosed@15
unexpected-close@16  unterminated-string@17  region@18  region-absent@19  duplicate-node-floor@20
soup-character-floor@21  placeholder-classes@22  fence-tags@23  required-regions@24  floors@25
balance@31  detail@40  finding@64  findings@69  counts@70  subject@72  id@73  absent-element@74
id-request@76  request@80  render-request@98  report@122
```

### resources/seon/schemas/seon.render.package.edn

SHA-256: `ed8e75d384dfea6792e85ffeb8fe09c45f744fa8b540827bdfab812c322b677f`.

Literal pair declarations: none.

No literal pair:

```text
base-revision@1  basis-transaction@2  delta@3  frame@4  keyframe@10  revision@12  size@13  streaming?@14
```

### resources/seon/schemas/seon.render.profile.edn

SHA-256: `1ed87d20de4b5ae83c953fd13c1f41045a4086a330066ed49a74095b92635eb7`.

Literal pair declarations: none.

No literal pair:

```text
id@1  token-budget@2  max-depth@3  max-children@4  max-string-length@5  composition@6  profile@8
```

### resources/seon/schemas/seon.render.transcript.edn

SHA-256: `06056c3e01b8688a0c370ca714641e9c139643fdf9822e081ffb165816f7350e`.

Literal pair declarations:

```text
request-error@48*=P27
```

No literal pair:

```text
history-request@1  entries@7  pulled-transaction@12  run@18  runs@31  history@36  refused-member@46
error-result@50
```

### resources/seon/schemas/seon.render.unknown.edn

SHA-256: `064d1bc8737069aa3fe71277248c98c92de28f20a7f0a95ec43fa99c4a1281c2`.

Literal pair declarations: none.

No literal pair:

```text
reason@1  call-projection@4  output@10  producer@13  refusal-projection@16  throwable@22  call@27
refused-operation@29
```

### resources/seon/schemas/seon.render.value.edn

SHA-256: `7dadfbd5963854a85d66e2d25561a0c26d50c173c707f0eaac58f751eaf5bd06`.

Literal pair declarations:

```text
window-failed-error@52*=P27  window-realization-failed-error@54*=P27
missing-root-identity-error@56*=P27
```

No literal pair:

```text
artifact@1  html@7  max-collection@8  options@10  projection@39  text@48  tree@49  truncated?@50
window-failed@51  window-offset@53  root-description@55  error@59*  error-window@69
projection-observation@75
```

### resources/seon/schemas/seon.render.walk.edn

SHA-256: `5290e5917fd21f2f922d31d0b1815ae911bf70a3ce2d71d1ea1077aabad16aaf`.

Literal pair declarations:

```text
elided-error@94*=P23  no-such-entity-error@99*=P27
```

No literal pair:

```text
attribute@1  back-reference?@2  branch@3  connection@4  found-depth@17  lookup@18  entity-lookup@21
path@24  acquisition-request@25  request@34  history-request@53  target@69  unit@70  units@92
error@103*  error-subject@113  step-observation@119  missing-lookup@125  limit@127
continuation-subject@129
```

### resources/seon/schemas/seon.render.web.edn

SHA-256: `08e9acbadc7ec637eddcd2ba98283f8741d49d32283bc576e3d13c3b88bd7653`.

Literal pair declarations:

```text
missing-port-error@143*=P27  value-not-found-error@146*=P27  value-unreadable-error@148*=P27
function-unavailable-error@150*=P27  request-error@180*=P27
```

No literal pair:

```text
feed-request@3  http-server@23  inbound@28  latest-packages@34  interest@40  page-request@46
pages-mult@56  paint-request@62  port@81  registration@82  root-agent-id@88  server@89  service@98
url@121  view@122  wanted-port@141  missing-port@142  value-not-found@145  value-unreadable@147
function-unavailable@149  error@156*  error-request@166  owner-observation@172  refused-member@178
context-error@183
```

### resources/seon/schemas/seon.repl.edn

SHA-256: `75e6f617f737c7901837ee4e6d116f2597135aa9e8f6c5197117a3013152c0b1`.

Literal pair declarations:

```text
directory@1=P53
```

No literal pair:

```text
columns@12  rows@13  schemas@14  changes@15  shown-value@16  comment@17  subject@18  interrupted@19
entry@24  note@32  error@36  settled@40  value@50  out@54  entity-request@58  pull-result@63  episode@70
result@71  candidate@76  entries@81  changed-since?@82  emission@84  response@127  candidates@141
ns@142  form@147  root-key@148  ms@149  expression@153  settled-entries@160  key@161  handle@162
```

### resources/seon/schemas/seon.runtime.edn

SHA-256: `623ec397f54df9973ce8e0f6afc157ce68eaaf76371cb79aeb1b7a75cb3469c9`.

Literal pair declarations:

```text
turns@3=P46  entity@10*=P48
```

No literal pair:

```text
agent@1  trigger@7  listens@8
```

### resources/seon/schemas/seon.schedule.edn

SHA-256: `1fa26bbd3b270624b44cfd8d89627c642604f892d4aaa59ad5540c0b50c3e640`.

Literal pair declarations:

```text
incomplete-task-error@42=P27  invalid-fire-id-error@44=P27  invalid-task-owner-error@46=P27
invalid-terminal-arm-error@48=P27  missing-execution-handle-error@50=P27  missing-receipt-error@52=P27
unresolved-handler-error@54=P27
```

No literal pair:

```text
id@1  expression@2  zone-id@3  schedule@4*  nominal-request@9  reference-at@14  proc-request@15
channel@20  execution-context@21  execution-handle@30  fire-count@40  incomplete-task@41
invalid-fire-id@43  invalid-task-owner@45  invalid-terminal-arm@47  missing-execution-handle@49
missing-receipt@51  unresolved-handler@53  error@57*  error-task@67  settlement-observation@73
```

### resources/seon/schemas/seon.schedule.fire.edn

SHA-256: `eaeb4b049de265481ba3dc242fba3475da0887274a7657c3299776d25b7c7c33`.

Literal pair declarations: none.

No literal pair:

```text
id@1  task@3  nominal-at@4  observed-at@6  agent@7  fire@15*  request@24
```

### resources/seon/schemas/seon.schedule.task.edn

SHA-256: `5db599a9273744355e3672ffaaa4ab740c28172b3498138798ebda67d2404a1f`.

Literal pair declarations: none.

No literal pair:

```text
id@1  owner@2  function@3  schedule@4  task@5*
```

### resources/seon/schemas/seon.schema.admission.edn

SHA-256: `e1916bf9788e2fb6d34c04c75fd225951e6c2377b9190573bfab314513f93bf6`.

Literal pair declarations: none.

No literal pair:

```text
source@1
```

### resources/seon/schemas/seon.schema.datahike.edn

SHA-256: `f90a33f357bcbebf57c60237b72b1da4e8587fb9e5cc044f089ea0dec593fb41`.

Literal pair declarations:

```text
literal-not-storable-error@2=P27  enum-not-storable-error@4=P27  nilable-attribute-error@6=P27
value-type-unavailable-error@8=P27  attribute-absent-error@10=P27
invalid-secondary-attribute-error@12=P27  schema-invalid-error@14=P27  storage-not-string-error@16=P27
malformed-edn-error@18=P27  noncanonical-edn-error@20=P27
```

No literal pair:

```text
literal-not-storable@1  enum-not-storable@3  nilable-attribute@5  value-type-unavailable@7
attribute-absent@9  invalid-secondary-attribute@11  schema-invalid@13  storage-not-string@15
malformed-edn@17  noncanonical-edn@19  declared-form@23  error@29*  error-attribute@40
```

### resources/seon/schemas/seon.schema.edn

SHA-256: `cf8d4e416610a9505c813e73eb8af8d224c2d73955914a57cb70834e789649b5`.

Literal pair declarations:

```text
invalid-identity-projection-error@94=P27  cyclic-reference-error@115=P27
unresolved-predicate-error@117=P27  noncanonical-definition-error@119=P27
noncanonical-projection-data-error@121=P27  unproved-predicate-purity-error@123=P27
unreadable-form-error@125=P27  non-round-tripping-form-error@127=P27
unregister-outside-delta-error@129=P27  malformed-projection-row-error@131=P27
malformed-projection-form-error@133=P27  duplicate-projection-row-error@135=P27
malformed-projection-identity-error@137=P27  malformed-artifact-export-error@139=P27
schema-in-use-error@141=P27  unknown-shape-error@143=P27  undefined-contract-error@145=P27
incomplete-predicate-contract-error@147=P27  nilable-map-value-error@149=P27
nilable-return-error@151=P27  invalid-schema-error@153=P27  nilable-value-schema-error@155=P27
single-segment-namespace-error@157=P27  missing-projection-error@159=P27
render-contract-incoherent-error@161=P27
```

No literal pair:

```text
arguments@1  registry-key@4  malli-form@5  pull-selector@10  pull-selector-element@15
parsed-pull-spec@19  parsed-pull-attribute-options@23  pulled-entry@31  pulled-entry-result@37
pulled-form-result@39  pulled-projection-result@41  definition@43  value@44  explanation@47
namespace-name@48  kvs@49  discarded-keys@51  projection-row@52  projection-rows@54  projection-input@58
projection@73  compiled-validator@74  key@75  form@80  shape@81  ns@82  references@83  identity@89
identity-only@90  generatable?@91  identity-projection@92  invalid-identity-projection@93  predicate@95
schema@96*  cyclic-reference@114  unresolved-predicate@116  noncanonical-definition@118
noncanonical-projection-data@120  unproved-predicate-purity@122  unreadable-form@124
non-round-tripping-form@126  unregister-outside-delta@128  malformed-projection-row@130
malformed-projection-form@132  duplicate-projection-row@134  malformed-projection-identity@136
malformed-artifact-export@138  schema-in-use@140  unknown-shape@142  undefined-contract@144
incomplete-predicate-contract@146  nilable-map-value@148  nilable-return@150  invalid-schema@152
nilable-value-schema@154  single-segment-namespace@156  missing-projection@158
render-contract-incoherent@160  refused-value@165  expected-value@169  validation-refusal@173
declaration-expectation@181  error@187*  error-declaration@197
```

### resources/seon/schemas/seon.schema.edn.edn

SHA-256: `1ec825482e6fb07f37dbcf0ae187ee6c4f12d02476ca5fe4f05693f5a0497522`.

Literal pair declarations:

```text
unreadable-file-error@11=P27  duplicate-attribute-error@13=P27  not-a-map-error@15=P27
unsafe-namespace-error@17=P27  misplaced-attribute-error@19=P27  dishonest-generator-error@21=P27
unregistered-predicate-error@23=P27  unresolved-reference-error@25=P27
```

No literal pair:

```text
resource@1  file@2  keys@3  load-request@4  loaded@6  unreadable-file@10  duplicate-attribute@12
not-a-map@14  unsafe-namespace@16  misplaced-attribute@18  dishonest-generator@20
unregistered-predicate@22  unresolved-reference@24
```

### resources/seon/schemas/seon.schema.map-entry.edn

SHA-256: `25b36d39706bceb7df2b2f81bd3c336d9ed54ee7ae751e8e03a5ee7087d107ce`.

Literal pair declarations: none.

No literal pair:

```text
key-kind@1  key-fingerprint@5  key-edn@6  key-keyword@7  key-string@8  key-symbol@9  key-boolean@10
key-int@11  key-double@12  key-uuid@13  key-inst@14
```

### resources/seon/schemas/seon.schema.shape.child.edn

SHA-256: `52c47b307646bcc984764f78de13cd8ae96ca576a354400259f6095143216d71`.

Literal pair declarations: none.

No literal pair:

```text
id@1  order@2  schema@3  value-edn@4  row@5*
```

### resources/seon/schemas/seon.schema.shape.edn

SHA-256: `02bd635975c8ec0ff119109cc7c15edda929ccf3301aa42d37bf61efdc062060`.

Literal pair declarations:

```text
fingerprint-collision-error@33=P27  noncanonical-compiled-form-error@35=P27
unsupported-map-key-error@37=P27
```

No literal pair:

```text
fingerprint@1  normalization-revision@3  form@4  comparison@5  type@6  properties@7  children@8
entries@10  row@12*  fingerprint-collision@32  noncanonical-compiled-form@34  unsupported-map-key@36
error@40*  error-form@50  shape-expectation@56
```

### resources/seon/schemas/seon.schema.shape.entry.edn

SHA-256: `e3af4f1ba0fb521f87dc355d31c4e0cc7185ce4bb15fa10d06bf34e5c3639ea1`.

Literal pair declarations: none.

No literal pair:

```text
id@1  order@2  optional?@3  schema@4  properties@5  row@6*
```

### resources/seon/schemas/seon.sci.admit.edn

SHA-256: `b938c5f315866dd1285d982716a0e72aaa184a689d7423da8562d45c6f689b63`.

Literal pair declarations:

```text
projection-failed-error@81=P27
```

No literal pair:

```text
reason@1  bytes@2  edn@3  admitted@4  missing@15  unbounded?@24  caps@25  interrupt-fn@31  print-node@37
record@38  request@47  value@68  admitted-value@71  projection-failed@80  bound-bytes@84  error@87*
projection-observation@97
```

### resources/seon/schemas/seon.sci.binding.edn

SHA-256: `f18c7ae0c1e649ea4ed2759f618f9175cba4d6ff6f1f5018ac27187ec5289f3b`.

Literal pair declarations: none.

No literal pair:

```text
target@1  public-namespace@2  reason@3  testing@4  help@7  dir@10  doc@13  deftest@16  is@19
```

### resources/seon/schemas/seon.sci.eval.edn

SHA-256: `003d92503dfbde7d23b0309ec2045ecfba6b3ba0923b7c21ac7e20169c9b1b20`.

Literal pair declarations:

```text
time-limit-error@52=P29  evaluation-failed-error@62=P27  namespace-unloadable-error@73=P27
acquisition-refused-error@93=P27  missing-function-row-error@103=P27
namespace-binding-cycle-error@116=P27  schema-refused-error@189=P27  install-mismatch-error@245=P27
documentation-unavailable-error@261=P27
```

No literal pair:

```text
private-state@1  load-state@4  load-result@7  load-results@12  evaluation@13  acquisition-refused@50
namespace-unloadable@51  missing-function-row@61  referenced-vars@72  binding@83  time-limit-ms@113
defs-fork-result@114  defs-fork-request@126  evaluation-failed@138  unrun-request@139
invocation-result@147  install-mismatch@161  install-request@162  invocation-request@167  args@181
reader-event-count@199  namespace-binding-cycle@200  time-limit@201  ctx@202  ending-ns@207  request@208
schema-refused@234  reader-event-count-error@235*  documentation-unavailable@242  bindings@243
projection-state@255  acquire-request@270  row-acquisition-error@282*  row-member@292
acquisition-error@296*  acquisition-member@307  acquisition-observation@310  evaluation-error@316*
evaluation-id@325  requested-program@328  source-projection@331
```

### resources/seon/schemas/seon.sci.kernel.edn

SHA-256: `af0507b5b8f49bbbe33b779c6f41e4e821cd090f08b54c7df37d7aec3244ad5e`.

Literal pair declarations:

```text
already-armed-error@13=P27  missing-function-installer-error@15=P27
missing-interrupt-guard-error@17=P27  unresolved-invocation-error@19=P27
failure-admission-failed-error@21=P27  time-limit-error@23=P27  invocation-failed-error@25=P27
```

No literal pair:

```text
arm@1  failure-request@7  already-armed@12  missing-function-installer@14  missing-interrupt-guard@16
unresolved-invocation@18  failure-admission-failed@20  time-limit@22  invocation-failed@24  error@28*
guard-observation@37
```

### resources/seon/schemas/seon.sci.reader.edn

SHA-256: `2c65c5017bb7189c960fabc416614351a5ffa71236f6424ee9cf234b90fe34f7`.

Literal pair declarations:

```text
fabricated-response-error@14=P27  unreadable-error@16=P27  oversize-error@18=P27
refused-tag-error@20=P27  keyword-error@22=P27
```

No literal pair:

```text
tag@1  token@2  token-kind@3  call@4  argument-index@5  prose-span?@6  containers@7
fabricated-response@13  unreadable@15  oversize@17  refused-tag@19  keyword@21  error@25*
reader-location@35  source-projection@41
```

### resources/seon/schemas/seon.search.edn

SHA-256: `fc0bcd7c271db04dad11fbc25c35e0e94aca7c01583cf4c2b1b5f301442b9890`.

Literal pair declarations:

```text
unavailable-error@45=P27  missing-resource-error@56=P27
```

No literal pair:

```text
family@1  path@2  handle@3  index@7  channel@8  completion@9  families@10  match@11  limit@12  query@13
namespace-prefix@14  basis-t@15  field@16  identity@17  text@18  score@19  result@20  results@30
request@34  unavailable@44  response@53  missing-resource@55  error@59*  error-resource@68
index-observation@71
```

### resources/seon/schemas/seon.source.edn

SHA-256: `ce82aeb3c1f7a281ddc479a841512dabe88c0335af9a1a6c5f6aa26abaee6654`.

Literal pair declarations: none.

No literal pair:

```text
branch@1  refused-test-run@2  test-evidence-error@3  test-selection-request@6  test-recording-request@16
test-recording-result@17  loaded-host@19  loaded-producer-digest@20  producer-namespace@21
producer-path@22  producer-digest@23  producer@24*  loaded-producers@28  loaded-generation@31*
requested-producer-digest@35  producer-mismatch@36  host-transition@37  restart-required?@38
producer-mismatch-error@39  built-at@47  built?@48  commit-id@49  database@50  current@51  digest@55
toolchain-digest@57  test-input-digest@59  digest-request@61  expected-commit-id@64
relative-file-digests@65  populate@66  change-class@71  change-classes@73  previous-database@74
activation@75  activation-closure@76  path@78  population@79  populate-request@84  progress!@90
publish-request@91  published@102  roots@109  snapshot@110  upsert-row@114  upsert-rows@119
upsert-request@120
```

### resources/seon/schemas/seon.store.edn

SHA-256: `7bdc9accea02eb2e659be0e59959abcf1c72e6f3e0db323bea1b01a024a66d20`.

Literal pair declarations: none.

No literal pair:

```text
branch@1  backend@2  path@3  id@4  connection-object@5  dir@11  lock-file@12  lock-object@13  store@27
transaction@34  transaction-data@40  transaction-operation@42
```

### resources/seon/schemas/seon.test.accretion.auto.edn

SHA-256: `08fedb5bf9bd7bd4767d3ff5de6b6d4e6173ec9a7267cd70d5226ceba84f947b`.

Literal pair declarations: none.

No literal pair:

```text
capabilities@4  case-count@7  entity@10*  executed-count@29  failure@32  seed@38  skip-reason@41
status@44
```

### resources/seon/schemas/seon.test.accretion.edn

SHA-256: `fa2ad0f2dd7f806f9c411e4b590569e3d2b04d89e4297f0e04bb5c0517dc09a6`.

Literal pair declarations:

```text
install-refused-error@99=P54
```

No literal pair:

```text
gate-set@1  seed@2  status@3  executed-count@9  case-count@10  capabilities@11  actual@12  expected@13
explanation@14  skip-reason@15  pass?@16  failure-source@17  failure-shape@18  expected-actual@19
failures@20  failure-group@21  failure-groups@27  gate-failure@29  orientation@57  test-count@58
test-pass-count@59  test-fail-count@60  advisories@61  install?@62  gate-tests@63  gate-test-count@65
gate-pass-count@66  gate-fail-count@67  report-edn@68  report-blob@69  report-size@70  auto-check@71
gate-report@72  gate-report-request@91  install-refused@98  failure@134  auto-check-result@147
auto-check-request@165  candidate-auto-check-request@181  candidate-context-request@195
candidate-request@201  candidate-ctx@226  evaluation@227  results@228  candidate-result@229  error@241*
error-advisories@271  error-arguments@274  error-auto-check@280  error-group-count@286  error-groups@289
```

### resources/seon/schemas/seon.test.accretion.failure.edn

SHA-256: `c0c5175ebbfcc4d4002c6ca1947e7fbae812230c1ce0956a30006d529eb7d000`.

Literal pair declarations: none.

No literal pair:

```text
entity@4*  evidence@17  ordinal@23  source@26  test-symbol@29
```

### resources/seon/schemas/seon.test.accretion.group.edn

SHA-256: `fcb9ad67d76db260e0c5edab38b176546dd23ae21ff58348c704db1f9ce8a39f`.

Literal pair declarations: none.

No literal pair:

```text
count@4  entity@7*  failures@23  ordinal@29  shape@32
```

### resources/seon/schemas/seon.test.check.edn

SHA-256: `855b11a1f16a5aa1057fabb28f9e2b46bf71a3a01b8c2dd67ca329122ad0b8bf`.

Literal pair declarations: none.

No literal pair:

```text
request@1  text@7  passed?@8  result@9  response@16
```

### resources/seon/schemas/seon.test.edn

SHA-256: `25bfedbfce7f0bc7c9a2482ec780580ff307798b5d7f8e04f7e132af450fd579`.

Literal pair declarations:

```text
result@55=P45  test@79*=P45
```

No literal pair:

```text
ns@1  reach@2  reaches@3  reach-unknown@4  reach-digest@5  reach-digests@6  skipped-count@7
skip-reason@8  source@9  subject@10  usage@11  fixture-observation@12  long@15  platform@18  fixture@21
long-ms@24  pass-count@27  fail-count@28  error-count@29  run-basis-t@30  run-at@31  unchanged@32
recorded-basis-t@33  run@34  failure-identity@35  failing-assertions@37  failure-message@40  failures@41
var@42  not-runnable@47  not-runnable-error@48  sym@50  results@78  identity@141
acquisition-refusals@142  acquisition@143  class-loader@148  classpath-roots@151  classpath-root@152
jvm-options@153  classpath@154  resolution-request@158  changed@165  paths@167  namespaces@168
check-time-limit-ms@169  reaching-request@173  check-request@176  run-options@187  run-owned-request@199
host-error@208  host-result@216  reuse-request@217  reuse-result@221  declared-root@223
destructive-path@226  destructive-excluded@229  host@235  host-report@238  unknown@246
unknown-error@247  next-tier@249  command@251  deferred@252  long-excluded@256  expired@260
check-result@263  adoption-identities@285  adoption-inputs@286  adoption-cluster@287  adoption@288*
error@295*  error-test-symbol@304  execution-observation@307  selection-refusal@313
selection-error@314*  admission-refusal@318  admission-error@319*  resolution-refusal@323
resolution-error@324*  execution-refusal@328  execution-error@329*
```

### resources/seon/schemas/seon.test.failure.edn

SHA-256: `e6df1adb2c757773a84177d4f98e7b9bdf9e5035f5d6d947e459dccf72e2b4c4`.

Literal pair declarations: none.

No literal pair:

```text
id@2  test@3  type@4  ordinal@5  message@6  contexts@7  expected@8  expected-blob@9  expected-size@10
actual@11  actual-blob@12  actual-size@13  file@14  line@15  signature@16  throwable@17  first-run@18
last-run@19  seen-count@20  last-seen-at@21  reported-file@22  report@23  reports@34  failure@35*
value@58
```

### resources/seon/schemas/seon.test.member.edn

SHA-256: `a7d5791a50e41d18c5b66b9791ad40931093585f0a5242cca71e90bf292cd57f`.

Literal pair declarations: none.

No literal pair:

```text
symbol@1  reasons@3  worker@6  claimed-at@7  claim-tx@8  host@9  completed-tx@10  pass-count@11
fail-count@12  error-count@13  began?@14  ended?@15  terminated-tx@16  failures@17  error@18  member@19*
```

### resources/seon/schemas/seon.test.report.edn

SHA-256: `0ed509bf65945b38130421f804f24a24d70b6a0b282eb974cd155a409ebdbf76`.

Literal pair declarations: none.

No literal pair:

```text
id@1  symbol@4  report@6*
```

### resources/seon/schemas/seon.test.run.edn

SHA-256: `a418be743b66c28fbd9dd4676fe826cef8b7e4ad8039ccdd4a52e197b105b430`.

Literal pair declarations: none.

No literal pair:

```text
at@1  git-sha@2  id@3  program-digest@4  basis-t@5  published-base-digest@6  overlay-input-digest@7
snapshot@8  result-row@14  result-facts@19  expected@23  rows@24  callers-at-head@25  branch@26
tested-branch@27  cluster@28  change-basis-t@29  members@30  covered-by@31  exclusions@32
selection-tx@33  policy@34  namespaces@35  identities@36  include-long?@37  input-digest@38
admission@39  deadline@53  dead-workers@54  claim-request@57  terminated?@67  claim-completion@68
immutable@74  immutable-error@75  unavailable@77  provenance-failure@78  unavailable-error@79
provenance@83  destination@94  completion@95  run@98*  error@125*  error-request@133
```

### resources/seon/schemas/seon.test.runner.edn

SHA-256: `4224dc1d9fef0058607ad5b7e5f9c12756ecc47d1848d9cf68ed81063cdf3dba`.

Literal pair declarations:

```text
invalid-silence-seconds-error@65=P27  invalid-long-reason-error@67=P27  long-test-ns-hook-error@69=P27
default-cluster-refused-error@71=P27  invalid-selection-mode-error@73=P27
invalid-marker-reason-error@81*=P27  process-tree-exit-backstop-error@87*=P27
unknown-worker-command-error@93*=P27  unresolved-test-var-error@99*=P27
worker-launch-failure-error@104*=P27
```

No literal pair:

```text
error-count@1  fail-count@2  namespaces@3  pass-count@4  captured-result@5  captured-results@18
completion@20  record-request@26  record-tx@32  run-request@33  run-result@40  summary@49
unchanged-count@59  test-count@60  silence-seconds@61  long-reason@62  selection-mode@63
invalid-silence-seconds@64  invalid-long-reason@66  long-test-ns-hook@68  default-cluster-refused@70
invalid-selection-mode@72  marker-key@74  worker-id@75  process-tree-exit-bound-ms@76
process-tree-phase@77  worker-command-key@78  worker-namespace@79  worker-error-log@80  error@112*
error-selection@122  worker-observation@128
```

### resources/seon/schemas/seon.test.selection.edn

SHA-256: `d9166fb4b09bf2a0e4c81ca12a693639dbf3adf4afdb6f310a2942582a047447`.

Literal pair declarations: none.

No literal pair:

```text
request@1  widening@14  reused@15  unchanged@22  disposition@23  exclusion@26*  result@30
```

### resources/seon/schemas/seon.turn.edn

SHA-256: `28f6777f99ea916c893666c5f9024b0ff2625242dfd4d626ccdabae8397fd203`.

Literal pair declarations:

```text
turn@2*=P47  missing-opening-datom-error@30=P27  agent@51=P46  refused-error@164*=P27
```

No literal pair:

```text
write?@1  disposition@40  rule@43  handled@44  trigger@45  system-result@46  form@59
record-evaluated-request@75  compaction-request@94  basis-t@98  starting-ns@99  virtual-request@100
evaluation-facts-request@108  system-request@119  text@124  turns-remaining@125  reply@126
missing-results@127  changes@128  missing-opening-datom@129  attempts@130  refused@135  forms@136
system-run-request@137  generated-run-request@152  closed-tx@159  opened-tx@160  transition@161
reply-blob@162  id@163  status@173  generated-form-request@180  reply-size@191
record-evaluated-call-request@192  error@215*  error-turn-id@223
```

### resources/seon/schemas/seon.turn.loop.edn

SHA-256: `2172716eb2f00a2bed0818258d1d40d691a86e9383c7923f431920cfa66e57b2`.

Literal pair declarations:

```text
phase-failed-error@87=P27  terminal-refusal-settlement-refused-error@141=P27
prompt-failed-error@155=P27  lint-rejected-error@183=P27
```

No literal pair:

```text
preview-sources-request@1  turn-request@10  evaluation-settlement@14  prompt-failed@29  phase@30
settle-evaluation-request@38  outcome@53  terminal-request@55  completion@86  phase-failed@97
evaluation@98  evaluated-sources@139  settle-request@151  lint-rejected@165
evaluate-sources-request@166  commit-outcome@181  settle-failure-request@193  admitted-form@203
idle?@207  pass-report@208  turn-report@213  refusal@222  write-refusals@223  parked@224
evaluated-source@225  settlement@233  preview@237  terminal-refusal-settlement-refused@246
forms-run@247  cluster@253  now@285  failure-settlement@286  error@297*  failed-step@305
```

### resources/seon/schemas/seon.turn.work.edn

SHA-256: `b59699bc501a2342dadee50b6bfaeeeee9cf606becef592db5f1e473a9ad4f9f`.

Literal pair declarations: none.

No literal pair:

```text
agent-request@1  answered?@5  wake-request@7  attributes@17  episode-runs@22  form-settlement@23
form-state@37  forms@46  next@48  now@83  plan-settlement@84  settled?@91  situation@92
```

### resources/seon/schemas/seon.wake.edn

SHA-256: `b67790aed85bc3732cfb400ff22b92777e2fb1985343f592d80a0dab3e35304a`.

Literal pair declarations: none.

No literal pair:

```text
context-inert@1  arms@5  listen@9  opens-turn?@13  inside@17  attribute@21  t@25  unanswered@30
```

### Reproduce the literal census without a JVM

Run the following from the repository root. It reads resource files only;
it does not evaluate EDN or write a helper file. Compare the resulting
rows/digests to this dated appendix; a later population is expected to move.
This small census reader handles the literal EDN grammar present in the
inspected resources, not arbitrary tagged/executable Clojure input.

```python
from pathlib import Path
import json, hashlib
class Reader:
 def __init__(self,s): self.s=s; self.i=0
 def skip(self):
  while self.i<len(self.s):
   if self.s[self.i].isspace() or self.s[self.i]==',': self.i+=1
   elif self.s[self.i]==';':
    e=self.s.find('\n',self.i); self.i=len(self.s) if e<0 else e+1
   else: break
 def read(self):
  self.skip(); start=self.i; c=self.s[self.i]
  if c=='"':
   self.i+=1
   while self.i<len(self.s):
    c=self.s[self.i]; self.i+=1
    if c=='\\': self.i+=1
    elif c=='"': break
   return ('atom',self.s[start:self.i],start,[])
  if self.s.startswith('#:',self.i):
   while self.s[self.i]!='{': self.i+=1
   ns=self.s[start+2:self.i].strip()
   node=self.read(); return ('map',ns,start,node[3])
  if self.s.startswith('#{',self.i): self.i+=1; c='{'; typ='set'
  else: typ={'{':'map','[':'vector','(':'list'}.get(c)
  if typ:
   self.i+=1; children=[]; end={'{':'}','[':']','(':')'}[c]
   while True:
    self.skip()
    if self.s[self.i]==end: self.i+=1; break
    children.append(self.read())
   return (typ,'',start,children)
  if c in "'@": self.i+=1; return ('prefix',c,start,[self.read()])
  while self.i<len(self.s) and not self.s[self.i].isspace() and self.s[self.i] not in ',{}[]();': self.i+=1
  assert self.i>start,(start,self.s[start:start+40])
  return ('atom',self.s[start:self.i],start,[])
def pairs(n):
 assert n[0]=='map'
 assert len(n[3])%2==0,(n[2],len(n[3]))
 out=[]
 for k,v in zip(n[3][::2],n[3][1::2]):
  key=k[1]
  if n[1] and key.startswith(':') and '/' not in key: key=':'+n[1]+'/'+key[1:]
  out.append((key,k,v))
 return out
def props(n):
 out={}
 if n[0]=='map':
  for k,_,v in pairs(n):
   if k in (':seon.render/ai',':seon.render/html',':seon.db/attributes'): out[k]=v[1]
 for ch in n[3]: out.update(props(ch))
 return out
rows=[]
for p in sorted(Path('resources/seon/schemas').glob('*.edn')):
 s=p.read_text(); r=Reader(s); root=r.read(); r.skip(); assert r.i==len(s),p
 for key,k,v in pairs(root):
  pr=props(v)
  rows.append(dict(file=str(p),key=key,line=s.count('\n',0,k[2])+1,ai=pr.get(':seon.render/ai'),html=pr.get(':seon.render/html'),entity=pr.get(':seon.db/attributes')=='true'))

print(json.dumps({
 "rows": rows,
 "hashes": {str(p): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(Path("resources/seon/schemas").glob("*.edn"))}
}, sort_keys=True, indent=2))
```
