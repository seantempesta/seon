---
type: research
status: proposed, awaiting orchestrator review
created: 2026-09-16
tags: [research, schema, database, program-graph, retirement]
---

# Retirement is a fact

Design only. No production edit, schema publication, reset, or test run is
part of this commit. The owner ruled Option B on 2026-09-16: “make the schema
changes and reset and reindex.” This note makes that direction reviewable;
it does not reopen the choice between Option A and Option B.

Read AGENTS.md §0–§3 and §5, and both named authorities end to end:
[evaluation write path research](evaluation-write-path-and-retired-identities-2026-09-16.md)
(§3 grounds this change), and
[program facts PRD](../plan/program-facts-are-the-runtime-prd-2026-09-17.md)
(especially §3 I4 and I5). Reviewed the data-oriented-clojure, data-modeling,
datahike and repl skills. Source observations below are dated against
`3c976745c4413eeb7bf5ee309a94aca4be4cfbe7`, with the shared working-tree
modifications listed in §8. This inventory is a dated observation, not a new
maintained roster.

## 1. Recommendation and two constraints the implementation must not hide

Use **one shared `:seon.db/retired-tx` ref**, asserted by the writer that
removes the content. A retirement produces exactly the existing identity
datom plus this ref; `:db/id` in a pull is the entity coordinate, not another
stored attribute. Keep incoming refs. Re-definition removes retirement in
the same transaction that writes the complete definition.

Use a separate positive `:seon.db/referenced-tx` fact for an identity minted
only to resolve evidence in a database that never defined it. This is not
retirement. It prevents an incomplete declaration from quietly passing as
“referenced” merely because required content is missing.

Two consequences require explicit review:

1. There are **three states for program identities**, not two. Two arms
   suffice for issues (current/retired); program schemas need the additional
   referenced arm. `not retired` means **not retired**, not necessarily
   defined. It cannot mean defined while a third state exists. For a bound,
   existing entity, retirement is `[?e :seon.db/retired-tx ?tx]`, and its
   complement is `(not [?e :seon.db/retired-tx])`. A function is defined by
   its required `:seon.schema.admission/source` fact, also one clause. Do
   not publish a `defined?` that returns true for referenced-only names.
2. “Nothing else” is stronger than today's retirement. Program replacement
   preserves attributes declared `:seon.program/written-by`; issue
   replacement preserves assignment, creator, budget, resolution and tests.
   Strict retirement must clear these too. Ordinary redefinition continues
   respecting ownership. The retirement transition is the explicit operation
   that removes all content, not an ordinary indexer replacement with an
   unusually small desired map.

Three concrete choices for evidence-bearing identities, simplest first:

| Choice | Guarantee | Cost | Give up |
|---|---|---|---|
| **Strict retired rows; refuse content writes onto them (recommended)** | Exactly identity + retired-tx, without resurrecting a deleted definition | Update the existing result/steward/issue writers to return a typed refusal for a retired subject; ordinary reads and incoming refs remain valid | A completion arriving after its test was retired cannot update that test's current result row; it must be reported as unrecordable, never silently green |
| Preserve runtime evidence on retired rows | Existing test completions and ownership facts can remain | Broader retired schema and existing ownership-aware clearing | The requested identical two-datom retired shape |
| Relocate runtime evidence to separate existing/new owners before retirement | Strict retired rows and continued historical result recording | Cross-owner result-model work; not a bounded retirement fix | Small scope and immediate implementation |

The first choice follows the requested strict shape. It is a real behavior
change; approval must include it. Do not silently adopt the second choice
by retaining today's tombstone validator semantics.

## 2. Inventory: seven identity families, four retirement writers

`seon.program/identity-attributes` at `src/seon/program.cljc:11` contains
**six**, not four, program identities. The additional two are file and lint
identities. All six pass through `reconcile-tx-in`'s removed-identity branch.

| Identity | Entity schema | Retirement writer(s) |
|---|---|---|
| `:seon.fn/sym` | `:seon.fn/fn` | turn `row-tx`; fn `reconcile-tx-in` |
| `:seon.test/sym` | `:seon.test/test` | turn `row-tx` (paired with ns-unmap); fn `reconcile-tx-in` |
| `:seon.ns/name` | `:seon.ns/ns` | fn `reconcile-tx-in` |
| `:seon.schema/key` | `:seon.schema/schema` | turn `row-tx` (unregister); fn `reconcile-tx-in` |
| `:seon.fn.file/relative-path` | `:seon.fn.file/file` | fn `reconcile-tx-in` |
| `:seon.lint/id` | `:seon.lint/finding` | fn `reconcile-tx-in` |
| `:seon.issue/id` | `:seon.issue/issue` | issue `index-tx` **and** `adopt-tx` |

The complete relevant retirement branches were read:

- `src/seon/program.cljc:1007–1097`: `replacement-tx`, both exact-replacement
  entry points, and `deletion-row`. `ns-unmap` and unregister produce typed
  deletion requests; the transaction owner decides the existing row.
- `src/seon/turn.clj:1281–1330`: deletion branch of `row-tx`, including schema
  attribute removal. It requests `{identity value}` today.
- `src/seon/fn.clj:2557–2597`: `reconcile-tx-in` constructs identity-only
  desired rows for every removed identity, normalizes current/desired rows,
  and uses `exact-replacement-tx-in`.
- `src/seon/issue.clj:225–262`, `:265–393`, `:701–750`: the shared issue
  replacement function and both complete retirement branches. `index-tx`
  explicitly deletes stale citation components; `adopt-tx` needs the same
  cleanup guarantee. Both find current indexed issues by `:seon.issue/path`.

The historical “third retirement path” is actually **reference minting**:
`src/seon/cluster/source.clj:305–372`, called by `preserved-evidence-tx`
(`:454`) and `seon.test.runner/record-tx` (`src/seon/test/runner.clj:2201`).
Its name and docstring claim identity-only tombstones, but it synthesizes
admission provenance and a namespace ref for functions. Rename it in place
to describe referenced identities; remove the old name and its callers in
the same slice. The result writer additionally mints namespace/test rows
directly (`src/seon/test/runner.clj:2204–2255`); route those absent-identity
cases through this same constructor rather than leaving another minting path.

Search scope: `src/` and `resources/seon/schemas/`, for retain, tombstone,
identity survives, never retract, plus retirement and retraction call sites.
Branch retirement (`seon.cluster.registry/retire-branch!`) deletes branch
names, not entity content while retaining an entity identity. Config
reconciliation retracts whole managed entities (`src/seon/reconcile.cljc:416`),
and compaction retracts evaluations, not their content while keeping their
identities. Error recording's identity-only transaction prefix is completed
by its occurrence writer; it is not retirement. No further content-removal
with retained identity was established by this search.

## 3. Shared attributes and dependency ledger

Proposed declarations (neither key occurs in the inspected schema resources):

```clojure
:seon.db/retired-tx
[:and {:seon.db/index true
       :description "Transaction that removed this entity's content while retaining its identity. Present only while retired; redefinition retracts it."}
 :seon.db/ref]

:seon.db/referenced-tx
[:and {:seon.db/index true
       :description "Transaction that minted this program identity solely to resolve a reference, without a definition in this database. Definition admission retracts it."}
 :seon.db/ref]
```

These are lifecycle facts, not kind stamps. Sharing is already the idiom:
`:seon.schema.admission/source` is shared by fn, test, ns, schema, file and
lint rows, with different requiredness. Issue retirement has precisely the
same transaction-ref meaning, so seven synonymous per-kind attributes buy
nothing and force seven alternatives into every cross-kind query.

`seon.db.edn:183`'s `:seon.db/tx` is a basis-t scalar, **not** a ref and must
not be repurposed. The wrapper/ref pattern is already at `:214–215` for
process and evaluation provenance; the transaction-ref precedent is
`seon.turn.edn`'s `opened-tx`/`closed-tx`, with `open?` at
`src/seon/turn.clj:193–198` deriving absence. Use `"datomic.tx"` as the
transaction-data ref spelling admitted by the current `:seon.db/ref` string
arm. Datahike recognizes it, along with `:db/current-tx`, at
`reference-code/datahike/src/datahike/db/transaction.cljc:62–66`.

| Dependency/mechanism | Source read | First-party seam |
|---|---|---|
| Datahike retractEntity removes incoming refs too | `reference-code/datahike/src/datahike/db/transaction.cljc:998–1014` | Preserve the identity entity; retract attributes, never that entity |
| Malli `:or` runs child validators, `:not` complements its child | `reference-code/malli/src/malli/core.cljc:996–1035`, `:1107–1140` | Declared mutually exclusive schema arms |
| Malli `:multi` dispatches an evaluated function | same file `:1861–1915` | Avoid a custom dispatch function: `:or` with explicit presence/absence is sufficient |
| Storage derives entity map attributes | complete `src/seon/schema/form.cljc`; `src/seon/schema/datahike.clj:314–340` | Extend the existing structural walker, not a second attribute registry |
| Whole-result write validation | `src/seon/db.clj:2683–2702`, `:2895–3035` | Discover the union's common identity, validate the whole union once |
| Shape/catalog indexing | `src/seon/schema/internal.cljc:235–275`; `src/seon/schema.clj:1653–1672`, `:1850–1870`, `:1958–1975` | Union required keys are the intersection of branch requirements |
| Program attribute ownership | `src/seon/program.cljc:54–140`, `:963–1047` | Derive defined content from positive map entries; retirement clears all current nonidentity datoms |

## 4. Exact schema construction

Recommend `:or` of guarded open maps, not `:multi`. Neither works merely by
editing EDN today: `schema.form/database-attributes` recognizes only a
top-level `:map`, `program/derived-shape` demands one, and
`db/write-entity-schemas` only discovers those maps. A union without fixing
these seams could disable entity validation rather than repair it.

The following is an **exact form-producing specification**, not a proposed
second validator or runtime schema generator. Expand its result into the
existing EDN entity declarations at implementation. `defined` is the complete
existing entity map, byte-for-byte including every required/optional entry and
render property. Thus no required field is weakened, and S2's required fields
can be added to that map in the same publication. `:seon.schema/value` is the
existing explicitly polymorphic schema: here it means “a present value of any
shape,” so invalid content values cannot evade a key-absence check.

```clojure
(let [forms (seon.schema.edn/packaged-forms)
      absent (fn [attribute]
               [:not [:map [attribute :seon.schema/value]]])
      entity-form
      (fn [entity-key identity-key reference-arm?]
        (let [defined (get forms entity-key)
              properties (seon.schema.form/schema-properties defined)
              entries (seon.schema.form/map-entries defined)
              content (mapv first (remove #(= identity-key (first %)) entries))
              no-content (mapv absent content)
              retired
              (into [:and
                     [:map [identity-key identity-key]
                      [:seon.db/retired-tx :seon.db/retired-tx]]
                     (absent :seon.db/referenced-tx)]
                    no-content)
              referenced
              (into [:and
                     [:map [identity-key identity-key]
                      [:seon.db/referenced-tx :seon.db/referenced-tx]]
                     (absent :seon.db/retired-tx)]
                    no-content)]
          (cond-> [:or properties
                   [:and (into [:map] entries)
                    (absent :seon.db/retired-tx)
                    (absent :seon.db/referenced-tx)]
                   retired]
            reference-arm? (conj referenced))))]
  {:seon.fn/fn (entity-form :seon.fn/fn :seon.fn/sym true)
   :seon.issue/issue (entity-form :seon.issue/issue :seon.issue/id false)})
```

In particular the fn defined arm still requires `sym`, `admission/source`
and `ns`; its optional `source`, `file`, `form-span`, `arglists`,
`arglists-override?`, `doc`, `private?`, `internal?`, `doc-order`, `macro?`,
`spec`, `calls`, `destroys`, `references`, `call-arities`, `writes`,
`pending-calls`, `keywords`, `arities`, `ast`, `workload`, `external-sink`,
`projection-boundary`, `effect/capability`, `capability-fn`, and
`test/subject` entries remain exactly as declared. Every one except `sym`
is forbidden in its retired and referenced arms. This is a dated expansion
description; the constructor above derives the list from the actual map.

The issue defined arm still requires `id`, `title`, `status`, `severity`,
`problem`; every existing optional entry remains optional there. The retired
arm requires `id` and `retired-tx` and forbids every other declared issue
entry, including tests, agent, created-by, and resolved-tx. An archived
issue is not the same state as a resolved issue that still has its content.

These are open maps under AGENTS.md: unrelated extension keys are not
globally forbidden. **The writer** guarantees the exact two-datom result
by inspecting all existing datoms, while the schema forbids its declared
content. Do not introduce `{:closed true}` to assert a stronger promise than
the project's open-map law permits. Schema rows also carry projected
properties: their retirement clears those actual datoms, not just the few
entries literally listed in `seon.schema/schema`.

Extend the existing structural inspection with these rules:

- Positive `:map` entries contribute attributes; `:and` combines positives;
  `:or` unions their attribute sets. Follow registry refs with cycle detection.
  Never collect entries under `:not` (those declare forbidden presence).
- Required keys: union for `:and`, **intersection** for `:or`, none from
  `:not`. Each entity above consequently keeps its identity as a common
  required key. Reject a declared entity union whose arms have no common
  required identity; absence must not disable discovery.
- Preserve outer entity/render properties. Use this inspection in storage
  derivation, whole-entity discovery, program owned-attribute derivation,
  schema shape catalogs, `db/row-identity-attribute` (`:2333`) and renderer
  specificity (`src/seon/render.clj:333–343`). Keep the ordinary Malli union
  validator authoritative. Do not flatten the union into an all-optional map.
- Delete `write-tombstone-validator` and its fallback in `write-entity-error`.
  No prior-existence exception, no validation after “the real validator failed.”

## 5. Writer transitions

Retire under the transaction function's database value, using the supplied
projection. If already retired, return no datoms and retain the first
retirement transaction. For a defined row, retract each nonidentity content
attribute and its owned components, then assert `retired-tx "datomic.tx"`.
Reuse exact replacement's tuple-aware retraction behavior. Do not retract
incoming refs, including fn calls, test reach, issue citations or file refs.
If no identity exists, retirement does not fabricate a deletion. If the row
is referenced-only, deleting nonexistent content is a no-op.

The same operation must serve all four writers in §2. Definition replacement
keeps its existing per-writer ownership rules but explicitly retracts
`retired-tx` and `referenced-tx` before asserting the full definition, within
the same transaction. A missing-key refusal rolls back both changes.

Issue reintroduction cannot use only the current `:path` census: retirement
removes path. Look up every desired identity at the writer, including retired
ones, so reintroduction clears its marker. Source publication must likewise
retain lifecycle markers through `canonical-row` and `normalized-index-row`
until replacement decides them; otherwise their own filtering loses the
fact that must be retracted.

Reference minting asserts only identity + `referenced-tx`, never `:core`, a
synthetic namespace, a digest, a form or a lint site. All six program identity
families can then be named honestly without inventing content. Namespace
companions are unnecessary for a reference-only function. The meaning is
local to the destination database: historical evidence that a different
publication defined a name does not prove this database did.

The strict reference arm also cannot accept result facts on the same row.
`preserved-evidence-tx` must keep refs **to** reference-only identities while
reporting a typed, counted unavailable result when the referenced-only test
itself would be the result subject. `record-tx` must report this outcome too;
no bare success for discarded results. This is part of choice 1 in §1, not
an implementation detail to discover after schema publication.

Issue retirement can conflict with the existing append-only tests
contract (`seon.issue.edn:11–14`, issues README). Keep that contract: refuse
retirement if it would erase protected test membership or creator authority;
return a named diagnostic from the retirement writer. The check is historical,
not merely current assignment: `src/seon/db.clj:3050–3123` retains activation
and first creator even after unassignment, and forbids clearing the last test
after activation even for the creator. Only notes without those protected
facts take the normal retirement transition. Do not bypass creator authority
or make retirement a datom-write escape hatch. The orchestrator must review
this constraint together with the strict shape before implementation.

## 6. Queries

Currently retired functions whose latest retirement happened after basis t:

```clojure
'[:find ?name ?retired
  :in $ ?t
  :where
  [?e :seon.fn/sym ?name]
  [?e :seon.db/retired-tx ?retired]
  [(> ?retired ?t)]]
```

To include deletion followed by redefinition, run that query on
`(seon.db/history database)` with the retirement pattern changed to
`[?e :seon.db/retired-tx ?retired ?assertion-tx true]`. Current absence of
retirement cannot answer whether a definition was ever deleted.

Names only ever referenced in this database (positive marker remains until
the first definition, is never recreated on an existing identity):

```clojure
'[:find ?identity-attribute ?name ?referenced
  :in $
  :where
  [?attribute :db/ident ?identity-attribute]
  [?attribute :db/unique :db.unique/identity]
  [?e ?identity-attribute ?name]
  [?e :seon.db/referenced-tx ?referenced]]
```

The lifecycle tests each use one clause once their subject is bound. A public
predicate first establishes identity existence: an absent subject is not a
current definition. Neither query treats an empty result as proof the schema
or its subject population exists; the regression asserts that explicitly.

## 7. Reset and regression boundary after approval

**RESET NEEDED, batched by the orchestrator with symbols-everywhere and S2
required derivables into ONE reset and reindex. No migration.**

Existing identity-only deletion rows lack retired-tx. They cannot be assigned
retirement versus never-defined meaning from their current attributes.
Existing fabricated `:core`/namespace rows from mintable-identity are also
semantically ambiguous and must not be preserved across the reset as live
definitions. Retired rows carrying preserved test/steward/issue facts do not
meet the new strict shape. Old string identities and definitions missing S2
required attributes are separately invalidated by the other two slices.
Some identity-only namespace rows currently validate because all namespace
content is optional: keep that fact explicit; this design does not pretend
every such row was a deletion. Reindex actual declarations and mint fresh
reference facts only when fresh evidence requires them. Do not carry the
ambiguous legacy rows into the new base.

After approval, use canonical fixtures and armed contracts, with one class
regression per retiring writer: turn deletion, fn reconciliation, issue
indexing, issue adoption. Parameterize applicable families; assert exact
logical entity equality `{identity value, retired-tx report-tx}`, stable eid,
unchanged incoming refs, component cleanup, and the one-clause retired query.
Include repeated retirement and atomic redefinition in these same regressions.
Add one schema/discovery regression proving all seven unions are discovered,
defined missing-key and retired-with-content writes refuse under both map
and datom grammar, and the new ref attribute derives as cardinality-one ref.
One reference-minting class regression covers both evidence writers and their
typed unavailable-subject behavior. Include positive subject/population counts.

Update S7 expectations only where identity-only absence was the expectation;
do not relax current issue title/status/problem requirements. Validate protected
test membership and late result recording explicitly. Iterate with
`bin/test-fast --paths <owned paths> -- <affected namespaces>` after approval.
No `bin/test` invocation belongs to this design assignment; the orchestrator
owns its later integration gate. Reset-boundary live proof remains required.

## 8. Verification and protected hunks

`bin/seon status` reported default PID 41413 alive, generation
`0feea9b2-6557-43eb-9b2a-553599147c57`, no orphan JVMs. MCP runtime status
answered: three plumbing procs replied, three agents, one deferred agent,
two error signatures, eleven errored evaluations and ninety failed tests.
These are inherited observations, not proof of this proposal or attributed
causes. Adoption freshness was not established.

One attempted read-only JVM probe was rejected by the MCP reader for an
unmatched delimiter before evaluation. This was an authored probe error,
not established tool breakage. It produced no validation measurements and
was not retried under the one-probe budget. No test JVM, mutation, publication,
reset or browser observation occurred. Batch 110's issue-title refusal was
read directly at `tmp/orchestrator/gate-results/batch-110.log:272`; it was not
reproduced. The requested 33/42 tally is supplied assignment evidence, not a
new count from this lane.

Protected at the initial status: `src/seon/turn.clj`, `src/seon/test.clj`,
`src/seon/render/transcript.clj`, `resources/seon/schemas/seon.db.edn`,
`seon.eval.edn`, `seon.message.edn`, `seon.test.edn`,
`test/seon/schema_test.clj`. Additional concurrent edits included
`src/seon/test/runner.clj`, `src/seon/cluster/message.clj`,
`test/seon/render/web_test.clj`, an MCP issue note and a pulled-ref design.
All were preserved.

Required protected hunks for the later implementation: turn `row-tx`'s
deletion/redefinition calls; db.edn's two ref declarations; test.edn's union;
schema_test's structural-discovery regression; test runner `record-tx`'s
absent/retired result subjects and namespace minting. No transcript, evaluation
or message schema hunk is required by this design. Do not edit those protected
owners or operate their sessions to finish the proposal.

The Markdown edit hook reported repository-wide pre-existing dependency-pin
errors in `docs/prds/context-generation/research/agents-md-audit-2026-09-15.md`
(among other feedback). No foreign document was edited to clear that boundary.
`git diff --check` was clean before staging this note; no canonical gate ran.

The design is ready for review, **not approved for implementation**. In
particular review the strict-shape cost in §1, the third program state, and
the assigned-issue constraint before launching a production edit.
