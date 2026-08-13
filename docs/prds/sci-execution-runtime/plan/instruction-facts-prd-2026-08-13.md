---
type: prd
status: draft
tags: [prd, agent, context, architecture, program-graph]
---

# Instruction facts: files become facts and lessons join the walk

Owner-markup draft, 2026-08-13. **No implementation is authorized by this
document.** The implementation phases below remain held until the owner answers
the numbered decisions and marks this PRD ready.

## Status and scope

The design-ideas ledger places the instruction-facts PRD and the five laws'
suite-gated demonstrations in the authorized queue (items 5 and 6). It still
marks mined lessons and executed negative lessons as awaiting an owner response
(items 9 and 10). This lane's brief pulls item 10's mechanism into the proposed
contract—one real wrong call and its typed refusal receipt—but leaves item 9 as
an explicit owner decision below
([design-ideas ledger](design-ideas-ledger-2026-08-13.md)). The working edge
independently records this exact deliverable as a draft for markup, with no
implementation before review (`unsettled.md:93-104`).

In scope:

- translate the catalogue's 167 stable ids, kinds, dispositions, sources, and
  notes into one source-initialization transaction;
- accrete the existing `:seon.cluster.instruction` fact family with kind, law
  tags, program-graph subjects, source links, deterministic artifact placement,
  and declared renders;
- make root `AGENTS.md` the byte-exact ordered render of those facts;
- discover relevant lessons through the existing bidirectional walk and make
  them self-fade from program/test facts;
- make each of the five design laws own a suite-gated executable usage
  demonstration, with negative teaching executed rather than paraphrased; and
- assign exact implementation owners and phase exits.

Out of scope before markup: production edits, schema edits, changing
`AGENTS.md`, adding tests, changing the walk, or promoting a lived session.
The catalogue's separate class-issue/tag campaign is item 7 and is not part of
this PRD ([design-ideas ledger](design-ideas-ledger-2026-08-13.md)).

## Binding rulings

1. Blocks are the one render unit. The system message, global instruction
   files, and REPL instructions are schema'd instruction facts reached by the
   ordinary walk; static text has no separate assembly path
   (`plan/README.md:1994-2008`).
2. Shared instructions are explicit datoms. The cluster owns truly global
   instruction refs, facts mutate in place on stable identities, and Datahike
   history supplies forensics (`plan/README.md:2054-2065`).
3. Demonstrations are `^{:seon.test/usage true}` tests, and `bin/test` must go
   red before an agent sees a rotten lesson
   ([self-generating-context PRD](self-generating-context-prd-2026-08-11.md),
   ruling 26).
4. Worked teaching is spec-first, uses existing concepts, resolves through the
   existing owning-namespace/render-contract mechanism, and contains only forms
   generation genuinely emits
   ([self-generating-context PRD](self-generating-context-prd-2026-08-11.md),
   ruling 37).
5. Rebirth is a real capability: current facts plus empty history must generate
   a valid compact context. An agent's own contracted functions, declared
   renders, and green usage tests close teaching gaps, including after rebirth
   ([self-generating-context PRD](self-generating-context-prd-2026-08-11.md),
   rulings 45 and 46).
6. Anything that must survive rebirth is a fact with a declared render; the
   current render is the compaction and future-facing contract
   ([self-generating-context PRD](self-generating-context-prd-2026-08-11.md),
   rulings 47 and 48).
7. Ready plan items' `:about` refs already establish the intended subject-ref
   idiom: resolve identities at authoring time, join subjects into walk
   membership, and admit them through the existing beyond-closure budget; a
   refless opening stays byte-identical (`plan/README.md:117-143` and
   [plan-context PRD](plan-context-prd-2026-08-13.md)).

## Current seams to strengthen, not duplicate

| Seam | Verified current fact | Consequence for this PRD |
|---|---|---|
| Instruction schema | `:seon.cluster.instruction/id` is already a keyword identity; the entity already declares AI and HTML renders and carries verbatim text (`resources/seon/schemas/seon.cluster.instruction.edn:1-17`). | Accrete this family in place. Do not create `seon.instruction-v2` or a second fact family. |
| Instruction population | `instruction/seed-rows` currently returns one `:getting-started` row (`src/seon/cluster/instruction.clj:9-30,59-66`). `populate-source!` inserts absent instruction rows before program indexing (`src/seon/cluster.clj:1164-1204`). | Replace the hand roster and insert-only check. Subject lookup refs require the final instruction transaction to run after program rows exist. |
| Cluster/global refs | Cluster convergence currently derives `:seon.cluster/instructions` from the hand-maintained `instruction-ids` vector (`src/seon/cluster.clj:1694-1731`). | Keep cluster refs only for genuinely global grammar/system rows; catalogue lessons are discovered through subject refs, not another roster. |
| Walk | The selector enumerates every installed ref in both directions and the pull result is membership (`src/seon/render/walk.clj:67-144`). The ordered episode emits only candidates whose subjects were introduced and whose symbols are explained (`src/seon/render/walk.clj:710-791`). | `:seon.cluster.instruction/subjects` is sufficient as a discovery edge. No instruction-specific traversal or priority path is allowed. |
| Current self-fade precedent | Opening generation already derives own-namespace public functions plus green usage results and shortens linked demonstrations when those results exist (`src/seon/bootstrap.clj:136-173,182-243`). | Generalize this query from a namespace-wide special case to instruction subjects; do not add seen/acknowledged facts. |
| Program/test facts | Static indexing records usage metadata, call refs, keyword facts, and optional test subject refs (`src/seon/fn.clj:284-303,313-380`). Test reachability and current failures are Datalog rules (`src/seon/fn.clj:668-710`). Test result counts are stored on test rows (`resources/seon/schemas/seon.test.edn:1-78`). | Discovery, suite ownership, and self-fade are queries over existing facts. |
| Schema idioms | `my.note` uses an identity, an optional validated `:about` ref, open entity maps, and declared AI/HTML/form renders (`resources/seon/schemas/my.note.edn:1-21`; `src/my/note.clj:95-107,137-169`). `my.plan` uses cardinality-many validated `:about` refs and refuses missing subjects inside its transition (`resources/seon/schemas/my.plan.item.edn:1-29`; `src/my/plan.clj:117-139`). | Reuse identity/ref/open-map semantics and validate every subject in the same atomic seed transition. |
| Executable-teaching precedent | `my.run/walkthrough` is a zero-argument contracted producer of `:seon.repl/entries`; its usage test is indexed with `:seon.test/usage true` (`src/my/run.clj:41-83`; `test/my/run_test.clj:99-114`). | Reuse the producer-plus-usage-test relationship, but not its current scratch-then-overwrite content; ruling 37 requires the five new demonstrations to be schema-first. |
| Curation | The settled design is editor → ordered source revision → proof on a fresh fork → atomic adoption through `:seon.cluster.run/supersedes` ([session-curation PRD](session-curation-prd-2026-08-04.md)); current adoption commits proof receipts and supersession refs (`src/seon/cluster/curate.clj:272-345`). | Mining is a possible producer of canonical teaching data, not a new replay/adoption mechanism. Decision 5 sets its phase. |

Dependency ledger: Datahike supplies identity lookup refs, cardinality-many set
semantics, atomic transactions, and history; Seon's Malli-to-Datahike bridge
derives installed attributes (`src/seon/schema/datahike.clj:213-248`). The
existing program graph supplies function, namespace, schema, test, call,
keyword, arity, and result facts (`resources/seon/schemas/seon.fn.edn:1-64`,
`resources/seon/schemas/seon.fn.arity.edn:1-43`, and
`resources/seon/schemas/seon.test.edn:1-78`). The existing walk supplies
bidirectional membership and explained-set ordering
(`src/seon/render/walk.clj:67-144,710-791`). No new database writer, graph,
cache, scheduler, or prompt assembler belongs in this design.

## Target invariants

1. One catalogue idea has one stable instruction identity. Duplicate catalogue
   statements remain one row with several source links.
2. Entity membership is the presence of
   `:seon.cluster.instruction/id`, never `kind`. `kind` is the owner's bounded
   editorial role enum, not an entity discriminator.
3. Every live lesson has at least one structured source link and at least one
   subject ref. A truly global grammar/system instruction may instead be
   reached by the cluster's explicit instruction ref set.
4. Artifact membership and runtime reachability are independent facts. An
   artifact-only workflow may render into `AGENTS.md` without entering a Seon
   agent's walk; an artifact row becomes a live lesson only through a subject
   ref or an explicit cluster ref.
5. Subject refs are real database refs to program-graph entities. A missing ref
   aborts the complete seed transaction; absence never means the seed was
   healthy.
6. Subject refs are OR discovery edges: reaching any subject can make the
   lesson relevant. The existing walk's pull and explained-set rules still
   decide membership and executable order.
7. Law tags organize a lesson under one or more of the five laws. They do not
   drive entity membership or introduce a second priority system.
8. Artifact bytes are only the ordered AI renders of artifact-member rows.
   Headings, separators, code fences, and the final newline are fact text—not
   fixed renderer scaffolding.
9. The generated `AGENTS.md` bytes and the checked-in `AGENTS.md` bytes are
   equal. Missing rows, extra rows, duplicate positions, changed text, changed
   order, or an empty seed makes the check red.
10. No history/seen/acknowledged/mastered fact controls teaching. Current
   program and green test facts either prove the lesson or they do not.
11. An executable negative lesson contains exactly one intentionally wrong
    call. That call actually runs through the ordinary SCI/eval boundary, its
    receipt contains a typed flat `:seon.error`, and the later demonstration
    entries still execute.
12. Rebirth from current facts and empty retained history makes the same
    discovery and self-fade decisions as an ordinary later episode.

## Target schema

Registry discovery is the first implementation action. Reuse
`:seon.cluster.instruction/id`, `/text`, `:seon.db/ref`,
`:seon.render/{ai,html,form}`, `:seon.test/usage`, and the program identities;
declare only the missing instruction-owned keys. The current registry and
bridge are the authority, not the illustrative spelling below
(`AGENTS.md`, “Facts over inference”; `src/seon/schema/edn.clj:31-115,142-359`).

```clojure
;; Target resource shape, not a generated episode form.
#:seon.cluster.instruction
{:id [:keyword {:seon.db/identity true}]
 :kind [:enum :law :ban :mechanism :workflow :vocabulary :scar :pointer]
 :law-tag
 [:enum :values-carry-world
        :facts-over-inference
        :events-with-loud-backstops
        :total-honest-boundaries
        :one-mechanism-accreted]
 :law-tags [:set {:min 1} :seon.cluster.instruction/law-tag]
 :subjects [:set {:min 1} :seon.db/ref]
 :sources [:vector {:min 1, :seon.db/component true} :seon.db/ref]
 :artifact [:string {:min 1}]
 :ordinal [:int {:min 0}]
 :text [:string {:min 1}]
 :instruction
 [:map
  {:seon.db/entity true
   :seon.render/ai seon.cluster.instruction/instruction-ai
   :seon.render/html seon.cluster.instruction/instruction-html
   :seon.render/form seon.cluster.instruction/instruction-form}
  [:seon.cluster.instruction/id :seon.cluster.instruction/id]
  [:seon.cluster.instruction/kind :seon.cluster.instruction/kind]
  [:seon.cluster.instruction/law-tags
   {:optional true}
   :seon.cluster.instruction/law-tags]
  [:seon.cluster.instruction/subjects
   {:optional true}
   :seon.cluster.instruction/subjects]
  [:seon.cluster.instruction/sources :seon.cluster.instruction/sources]
  [:seon.cluster.instruction/artifact
   {:optional true}
   :seon.cluster.instruction/artifact]
  [:seon.cluster.instruction/ordinal
   {:optional true}
   :seon.cluster.instruction/ordinal]
  [:seon.cluster.instruction/text :seon.cluster.instruction/text]]}

#:seon.cluster.instruction.source
{:path [:string {:min 1}]
 :start-line [:int {:min 1}]
 :end-line [:int {:min 1}]
 :link
 [:map
  [:seon.cluster.instruction.source/path
   :seon.cluster.instruction.source/path]
  [:seon.cluster.instruction.source/start-line
   {:optional true}
   :seon.cluster.instruction.source/start-line]
  [:seon.cluster.instruction.source/end-line
   {:optional true}
   :seon.cluster.instruction.source/end-line]]}
```

The source component is owned by its instruction and has no independent
identity. Omitted line members mean “the named document as a whole”; stored nil
is invalid. The seed transition enforces start/end pairing and `start <= end`.
Artifact and ordinal likewise appear together or not at all; the transition
enforces uniqueness and contiguous ordinals per artifact. Keeping these as
plain scalar attributes uses the bridge's current scalar/ref/component
derivation rather than hand-written Datahike schema
(`src/seon/schema/datahike.clj:213-248`). Maps remain open.

The seven `kind` values are exactly the catalogue's declared bounded editorial
roles ([catalogue](../research/agents-md-reorg-catalogue-2026-08-13.md)). The
five law tags use the catalogue's organizing spines. Adding another kind or law
widens the corresponding enum and requires an owner ruling; no row's existing
meaning changes.

### Render contracts

- `instruction-ai` remains verbatim text. This preserves the existing family
  contract (`src/seon/cluster/instruction.clj:68-72`).
- `instruction-html` remains the ordinary human projection; HTML is not the
  artifact source (`src/seon/cluster/instruction.clj:74-79`).
- `instruction-form` returns the honest current pull form when the lesson has
  no linked executable demonstration. When the row's subject refs include one
  admitted usage test with one zero-argument producer returning
  `:seon.repl/entries`, it returns those entries. Decision 3 chooses whether
  that relationship stays derived or receives a direct ref.
- Render-contract coherence is checked by the existing admission gate; a
  schema-attached renderer without a compatible public contract is refused
  (`src/seon/schema.clj:1480-1558`).

## From the catalogue to one transaction

The catalogue is seed input, not a production Markdown API. It contains 167
ideas: 122 in the eight in-file destinations, 27 moves, and 18 deaths
(`agents-md-reorg-catalogue-2026-08-13.md:243-250`). Decision 1 chooses the
disposition policy; the recommended compiler consumes all 167 rows, produces
live facts for the 149 non-deaths, places the 122 in-file rows in `AGENTS.md`,
keeps moved rows fact-only until their destination becomes an artifact, and
emits an explicit exclusion report for all 18 deaths.

The translation is deliberate, not regex extraction:

1. Copy the stable catalogue id and kind.
2. Re-ground every retained row's source component. A generated `AGENTS.md`
   range cannot be the row's only evidence because that would make the artifact
   cite itself. A named ruling/process document is valid for owner-directed
   workflow facts.
3. Author one exact text fragment per artifact-member id. The fragment owns any
   heading/separator bytes immediately before its idea; the renderer contributes
   no static bytes.
4. Assign one or more program-graph lookup refs as subjects. Do not derive them
   from names or prose. Unresolved lookup refs are a seed refusal.
5. Add law tags from the catalogue's explicit design-law note; do not infer a
   law from id spelling.
6. Assign a total artifact ordinal. No set or map iteration may decide output
   order (`AGENTS.md`, “Unordered collections never decide order”).

The existing population order must change. Schema rows still install first;
program indexing must finish next; then one
`[:db.fn/call ... desired-instructions]` reconciliation runs after all subject
identities exist. The transition reads the mid-transaction database value,
validates every ref and artifact invariant, mutates retained ids in place,
retracts superseded seed-owned facts, and returns the complete tx-data. Any
failure aborts the whole transaction as a typed refusal. This replaces the
current insert-only `instruction-row-changes` pre-read
(`src/seon/cluster.clj:778-790,1187-1204`) and follows the existing
in-transaction transition idiom (`src/seon/cluster/run.clj:427-686`).

There is no runtime catalogue parser, no row-per-transaction loop, no
instruction version entity, and no `instruction-ids` roster. Datahike history
retains old text on the same identities, as already required by the instruction
architecture (`docs/seon/architecture/context.md:305-311,461-469`).

## `AGENTS.md` is a render-equality artifact

`render-artifact-ai` takes one database value and artifact path, queries rows
carrying that path, rejects an empty set, rejects duplicate/non-contiguous
ordinals, sorts numerically, calls the declared instruction AI render for each
row, and concatenates the resulting bytes without adding anything. The final
newline therefore belongs to the final fact.

The checked-in file is generated output. The recurring gate builds a fresh
canonical database population, renders `"AGENTS.md"`, reads the checked-in
bytes, and asserts byte equality. The test also plants one missing row, one
duplicate ordinal, and one text mutation so absence, order ambiguity, and drift
all fail loudly. This replaces a hand-maintained claim-coverage mirror with one
observable equality: every checked-in byte came from facts, and every rendered
fact byte is checked in.

Decision 2 chooses the hermetic authoring/write path. Under the recommended
choice, a checked-in EDN source-initialization transaction is canonical after
the one-time catalogue translation; a tiny build command renders `AGENTS.md`,
and the suite runs the same renderer in `--check` mode. A live cluster is never
required to reproduce repository artifacts.

## Discovery and teach-on-miss

Catalogue lessons are not appended to a static system prompt and are not copied
onto each agent. Each instruction points to the program entities it teaches.
When the ordinary root pull reaches one of those subjects, the pull's reverse
ref traversal reaches the instruction row. The current selector already derives
both directions from installed ref schema (`src/seon/render/walk.clj:67-144`).

Candidate generation then applies three existing gates in order:

1. **Membership:** the instruction and triggering subject are in the bounded
   pull; subject-directed plan members use the already-approved
   beyond-closure budget (`plan/README.md:126-131`).
2. **Knowledge:** the self-fade query below does not prove the agent already
   demonstrates the lesson.
3. **Explained set:** the existing fixed point introduces the subject and every
   symbol required by the instruction's real form before emitting it
   (`src/seon/render/walk.clj:730-791`).

An unrelated instruction therefore never enters the candidate set. A lesson
whose subject is absent is unknown/unneeded, not silently “healthy.” Passive
fact changes do not wake an agent; messages and addressed errors remain the
only wakes ([self-generating-context PRD](self-generating-context-prd-2026-08-11.md),
rulings 36 and 44).

### Self-fade query

The recommended proof is conservative and entirely derived. For the selected
agent namespace, collect:

- its public contracted functions (`:seon.fn/ns`, `/private?`, `/spec`);
- its declared render functions, recognized by arity output refs to
  `:seon.render/ai`, `/html`, or `/form`; and
- its usage tests whose latest result has positive passes, zero failures, and
  zero errors (`resources/seon/schemas/seon.test.edn:1-78`).

Follow existing `:seon.fn/calls`, `:seon.fn/keywords` joined to registered
schema keys, `:seon.fn.arity/{input-refs,output-refs}`, and
`:seon.test/subject` edges. An instruction self-fades only when those own
artifacts plus a green usage test cover every one of its subject refs. A missing
result, zero-pass result, failing result, unresolved edge, or subject outside
the proven closure keeps the lesson eligible. Decision 4 asks the owner to
confirm this all-subject threshold.

No stored “mastered” bit exists. At rebirth the query runs against current
facts with empty history, so demonstrated lessons remain absent and incomplete
lessons return. This is ruling 46's “already known” criterion rather than an
“already shown” receipt check
([self-generating-context PRD](self-generating-context-prd-2026-08-11.md),
rulings 45 and 46).

## Executable teaching and negative receipts

Each of the five law instruction rows links to a distinct
`^{:seon.test/usage true}` test. Under Decision 3's recommended graph shape:

1. the usage test's `:seon.test/subject` points to one public zero-argument
   producer whose output ref is `:seon.repl/entries`;
2. the instruction's subjects include that usage-test row plus the real
   functions/schemas the lesson teaches;
3. `instruction-form` derives the producer through those facts and returns its
   entries; and
4. the usage test executes the same entries through a fresh SCI fork and the
   canonical database fixture, then asserts the receipts and durable facts.

This copies no form source into the instruction row. The current
`my.run/walkthrough` plus its usage test proves the producer/test relationship
exists (`src/my/run.clj:41-83`; `test/my/run_test.clj:99-114`), while ruling 37
governs the new content. This PRD intentionally contains no invented worked
episode forms. Implementation authors must first obtain the exact entries from
the real producer, then commit those same entries and their receipts as the
test evidence.

For a negative lesson—at minimum every executable `:ban` or `:scar` row—the
producer contains exactly one wrong call. The test executes it and asserts:

- the call was attempted;
- its actual receipt is a flat typed `:seon.error` naming the failed boundary;
- no throw escapes the agent/runtime boundary;
- the next entry executes, proving refusal did not terminate the episode; and
- the database is unchanged when the refusal guards a write.

The test never fabricates an expected receipt literal and never uses message
matching in place of the typed error data. This is the refusal-as-result test
contract (`.agents/skills/clojure-testing/SKILL.md`, “Refusal is a result”) and
the concrete resolution of ledger item 10 proposed for owner approval.

## Owner decisions

### Decision 1 — Which catalogue dispositions become live facts?

1. **Seed 149 non-deaths; 122 render `AGENTS.md`, 27 remain fact-only;
   exclude 18 deaths with a complete report (recommended).** Guarantee: every
   catalogue row is consumed exactly once and stale/dead lessons cannot enter
   context. Cost: the compiler owns a disposition report. Give up: a dead idea
   is historical catalogue data, not a live instruction entity.
2. **Seed all 167 with an active disposition.** Guarantee: every catalogue id
   is queryable as an entity. Cost: another state enum and filtering at every
   consumer. Give up: absence remains the representation of retirement, and a
   bad filter can teach dead material.
3. **Seed only the 122 in-file rows.** Guarantee: the smallest artifact model.
   Cost: moved vocabulary lessons require another future import. Give up: the
   catalogue is not the complete initial fact source.

### Decision 2 — What is canonical after the one-time translation?

1. **Checked-in EDN source-initialization tx-data; generate and check
   `AGENTS.md` from it (recommended).** Guarantee: hermetic fresh builds and one
   ordinary database transaction; no production Markdown parsing. Cost: one
   small renderer/check command and a generated file. Give up: direct edits to
   `AGENTS.md` are no longer authoritative.
2. **Enrich the catalogue Markdown and compile it through a Markdown AST.**
   Guarantee: the catalogue remains the human authoring surface. Cost: table
   parsing becomes a build dependency and every editorial table change touches
   the compiler. Give up: a pure-data seed resource.
3. **Export from a designated live database.** Guarantee: the database is the
   only authoring surface. Cost: repository builds depend on external mutable
   state and an export protocol. Give up: reproducibility from a clean checkout.

### Decision 3 — How does an instruction identify executable teaching?

1. **Derive it from a linked usage test and that test's unique zero-argument
   `:seon.repl/entries` producer (recommended).** Guarantee: program facts and
   the suite are the one relationship; copied forms cannot drift. Cost: a loud
   uniqueness/shape query at admission/render. Give up: arbitrary producer
   signatures.
2. **Add a direct instruction → producer ref.** Guarantee: simpler lookup and
   explicit intent. Cost: a second relationship that must agree with the usage
   test's call/subject edges. Give up: derivation from existing program facts.
3. **Store form sources on instruction rows.** Guarantee: rows are
   self-contained. Cost: duplicate executable source, a new reader/admission
   path, and drift against tests. Give up: ruling 26's test-as-demonstration
   anti-rot property.

### Decision 4 — What evidence is enough for self-fade?

1. **Every instruction subject is covered by an agent-owned artifact and a
   green usage test (recommended).** Guarantee: missing evidence never reads as
   mastery. Cost: lessons persist until the agent demonstrates the complete
   subject closure. Give up: aggressive early fading.
2. **Any one subject covered by a green usage test.** Guarantee: the smallest
   query and fastest fade. Cost: a broad law attached to several subjects can
   disappear after one narrow example. Give up: conservative teaching.
3. **Add explicit agent-artifact → instruction demonstration refs.** Guarantee:
   exact owner-authored mastery claims. Cost: a new admission workflow and
   durable assertions that can drift from actual call/test facts. Give up: fully
   derived knowledge.

### Decision 5 — Are mined lived lessons in this implementation?

1. **Later, as a separate accretion after the authored five-law path is live
   (recommended).** Guarantee: this PRD lands the catalogue, artifact equality,
   discovery, self-fade, and executed refusals without coupling publication to
   curation. Cost: useful lived spans are not promotable in v1. Give up: mining
   in the first release.
2. **Now, as Phase I5 below.** Guarantee: an accepted curated revision can
   become canonical teaching only after mechanical proof. Cost: crosses the
   editor/revision/proof/adoption owner, adds promotion identity/provenance and
   artifact regeneration to this wave, and materially enlarges the gate. Give
   up: a small instruction-facts first implementation.

## Phases and ownership

All phases are held until owner markup. They run serially because later exits
depend on the preceding fact shape and artifact contract.

| Phase | Exact owners | Scope | Exit |
|---|---|---|---|
| I0 — ruling close | This PRD only | Owner answers Decisions 1-5; revise schema names, guarantees, and phase boundaries here. | `status` changes from `draft` only after every decision is recorded; no production diff exists before then. |
| I1 — fact model and one seed tx | `resources/seon/schemas/seon.cluster.instruction.edn`; one checked-in instruction seed resource chosen by Decision 2; `src/seon/cluster/instruction.clj`; `src/seon/cluster.clj`; `test/seon/cluster/instruction_test.clj` | Registry-query-first schema accretion; consume all 167 catalogue rows under Decision 1; replace `instruction-ids` and insert-only population; run one atomic reconciliation after program indexing. | Fresh canonical database contains the exact expected live id set; every source component validates; every subject resolves; maps accept an undeclared extra key; a missing subject, duplicate id/ordinal, gap, or malformed source aborts the whole transaction; a second identical seed is a no-op; changed text mutates the same id and history retains the old datom. |
| I2 — render-equality artifact | `src/seon/cluster/instruction.clj`; the small dev/build entry point selected by Decision 2; `AGENTS.md`; `test/seon/cluster/instruction_test.clj` | Pure ordered artifact render; generate root instructions; replace the old coverage mirror with byte equality and non-vacuity falsifiers. | Fresh-db render equals `AGENTS.md` byte for byte including final newline; missing/extra/reordered/mutated/empty inputs all fail loudly; a second generation changes zero bytes. |
| I3 — subject discovery and self-fade | `src/seon/bootstrap.clj`; `src/seon/render/walk.clj` only if its generic candidate seam needs accretion; `test/seon/bootstrap_test.clj`; `test/seon/render/history_test.clj` | Add instruction candidates from reverse subject refs; use the existing budget and explained-set order; replace namespace-special demonstration shortening with Decision 4's instruction query. | Refless opening remains byte-identical; adding one subject adds exactly its admitted lesson; unrelated lessons remain absent; own artifact + green usage result fades the lesson; missing/zero/failing result does not; current facts + empty history makes the same decision; no new wake occurs. |
| I4 — five laws and negative receipts | `src/seon/cluster/instruction.clj`; `test/seon/cluster/instruction_test.clj`; the five law instruction rows in the seed resource | One real zero-arg entries producer and one `^{:seon.test/usage true}` test per law; spec-first forms only; negative `:ban`/`:scar` lessons execute one wrong call. | Five discovered usage tests run under `bin/test`; each rendered demonstration equals the tested producer's entries; all forms execute in a fresh SCI fork; each negative lesson records exactly one typed refusal and continues; corrupting a producer or removing its usage test makes the suite red before publication. |
| I5 — mined lessons, only if Decision 5 chooses now | `src/seon/cluster/curate.clj`; `resources/seon/schemas/seon.cluster.curate.edn`; instruction owner above; focused curation + instruction tests | Promote only an accepted proof's ordered receipt span into a canonical usage producer/ref; reuse revision, proof, and supersession rather than replaying again. | An unproved/editor-only/failed proof cannot publish; an accepted proof publishes once with provenance and reproduces its receipt-derived entries; identical promotion is a no-op; artifact equality stays green. |
| I6 — integration and live proof | The owners above; `bin/test` as the one gate | Run focused namespaces, bare changed-code selection, then `bin/test --all`; publish current source and fork a fresh isolated cluster. | Gate green with nonzero tests; fresh cluster has one reconciled instruction transaction; generated `AGENTS.md` is equal; one subject miss teaches, one proved lesson self-fades, rebirth preserves both decisions, and no handwritten prompt fragment or second instruction path remains. |

## Acceptance checklist for owner markup

- [ ] Decisions 1-5 are answered in this document.
- [ ] The exact schema keys and enum members are approved.
- [ ] The catalogue disposition counts are approved.
- [ ] `AGENTS.md` is approved as generated output, not an authoring surface.
- [ ] The all-subject self-fade threshold is approved or replaced.
- [ ] The five demonstrations' exact forms remain implementation evidence, not
      invented examples in this PRD.
- [ ] Session-curation mining is explicitly in I5 or explicitly deferred.
- [ ] No implementation begins while this PRD remains `status: draft`.
