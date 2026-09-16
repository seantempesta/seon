---
type: research
status: proposed — step 1, awaiting review
created: 2026-09-16
tags: [program-graph, symbols, deletion, schema, reset, write-admission]
---

# Edges are symbols — implementation plan

**RESET NEEDED. This commit is the plan only; stop for review.** G1–G6
authorize the direction, not skipping the requested review of this plan.
No production edits, migration, publication, test JVM, or reset in step 1.
The orchestrator performs the single reset after this slice, symbols-everywhere,
and required-derivables converge. Step 2 uses `bin/test-fast`, never `bin/test`:
the assignment's explicit prohibition wins over its later generic gate paragraph.

## Authority and evidence boundary

Read the requested AGENTS.md sections (§0–§3 and §5–§7), and these requested
authority sections in full:

- [Program facts PRD](../plan/program-facts-are-the-runtime-prd-2026-09-17.md)
  §1f G1–G6 and §3 invariants.
- [Deletion research](datahike-deletion-and-the-program-graph-2026-09-16.md),
  end to end, especially Option B, §8 and §8.1.
- [Evaluation and retired identities](evaluation-write-path-and-retired-identities-2026-09-16.md)
  §3, all retirement paths and alternatives.
- [Schema audit](schema-key-audit-2026-09-16.md), required-collections finding,
  component coverage inventory, and the fn/test/file reset entries.
- [Symbols inventory](symbols-everywhere-inventory-2026-09-17.md), end to end;
  it remains the site inventory for the coordinated symbol retype.

Also read the [active plan entry](../../context-generation/plan/README.md)
and the data-oriented-clojure, data-modeling, datahike and repl skills.
Source locations below were inspected in the shared tree during step 1,
at HEAD `a74e92a7d1c3b34368744802465940b42b61d797`; they are dated anchors,
not a claim that foreign uncommitted code has landed. Re-read complete owning
functions and their callers after release and before each implementation edit.

**Precedence matters.** PRD §3 I4 still states the population/tombstone rule;
I2 still treats a required empty set as durable proof. G1/G2/G4 supersede those
sentences. The audit's retired-tx and submission-time validation proposals,
and the deletion research §8's retired-tx sentence, are likewise superseded.
Do not implement a retirement attribute or a submission-only proof of analysis.
Update these active claims with the schema commit; preserve dated experiments.

`bin/seon status` found default PID 41413 alive, no orphan JVMs. MCP health
answered; three plumbing procs replied, with 2 error signatures, 11 errored
evaluations, 95 failed tests, and 2 stale Vars. These are inherited observations,
not attributed failures or verification of this proposal. One read-only JVM
probe (136 ms, explicit connection custody) found 5,114 function identities,
calls installed as cardinality-many refs, and namespace identity already
`:db.type/symbol` + `:db.unique/identity`. The merged packaged declarations
have no `:seon.program/analyzed-source-digest`. No adoption freshness or browser
paint claim is made.

Exact read-only probe (no private defs or database mutation):

```clojure
(let [database (seon.db/db (seon.operator/connection "default"))]
  {:edge-schema (seon.db/pull database [:db/valueType :db/cardinality :db/index]
                             [:db/ident :seon.fn/calls])
   :namespace-schema (seon.db/pull database [:db/valueType :db/unique]
                                  [:db/ident :seon.ns/name])
   :function-count (seon.db/q '[:find (count ?e) . :where [?e :seon.fn/sym]]
                              database)
   :provenance-declared
   (boolean (get (seon.schema.edn/packaged-forms)
                 :seon.program/analyzed-source-digest))})
```

## 1. Schema and analysis provenance (a)

**Choose (ii): one required `:seon.program/analyzed-source-digest` on every
function and test definition row.** Declare it in
`resources/seon/schemas/seon.program.edn:1` using the existing 64-character
digest shape (`resources/seon/schemas/seon.fn.file.edn:4`), with a docstring:
“Digest of the exact source input whose successful analysis produced this
definition's facts; written by the analysis seam, absent means no analysis
evidence.” Use the existing digest owner, never another hash implementation.

This is one key and one requirement on both seams. Requiring file OR evaluation
refs instead creates two conditional obligations and couples definition
validity to evaluation retention. A digest identifies source content; it does
not claim an evaluation ID, wall-clock event, analyzer version, or complete
resolver-environment fingerprint. Admission provenance `:core`/`:agent` keeps
its separate meaning. No `analyzed?` boolean is stored.

Indexed rows take the digest already carried by their analyzed file context
(`src/seon/fn.clj:584`, `:1221`, `:1252`). Agent rows take the digest of the
actual analyzed input, including the generated namespace/resolver prelude:
`source-rows`, `src/seon/fn.clj:1134`, receives `text` from
`runtime-analysis-batch` and supplies `(text-context text)` to the same row
constructor. Do not hash a reconstructed form, nor stamp the digest in
`record-tx`, a validator, or a generic identity constructor. Preserve it through
`canonical-row`/`declaration-row` (`src/seon/program.cljc:863`, `:906`) and
replace it atomically with the definition. This source identity survives file
updates and evaluation compaction without a ref becoming invalid.

| Declaration | Exact reset edit |
|---|---|
| `resources/seon/schemas/seon.fn.edn:23` calls | `[:set {:seon.db/index true} :qualified-symbol]`. The value shape is G2's `[:set :qualified-symbol]`; explicit indexing preserves AVET lookup when ref's implicit indexing disappears. |
| `resources/seon/schemas/seon.fn.edn:44` references | Same indexed qualified-symbol set, in the same publication as calls/reach, per orchestrator review. An arity-unresolved source reference is still a name, not entity custody. |
| `resources/seon/schemas/seon.test.edn:2` reach | Same indexed symbol set, replacing ref vector/cardinality-many spelling. Update `reaches` and output contracts through this one alias. |
| `resources/seon/schemas/seon.fn.edn:114` fn map | Require analyzed-source-digest, source, arglists and private?; retain required identity/ns/admission. Calls are a logical required set derived as empty at the final validation/read projection only after provenance is present. No empty-set datom or sentinel. |
| `resources/seon/schemas/seon.test.edn:77` test map | Require the same provenance key, namespace and source with admission provenance, coordinate with S2. Calls follow the same logical-set rule. Run/result counts remain lifecycle-dependent; an unrun test has no invented run evidence. |
| `resources/seon/schemas/seon.fn.file.edn:10` file map | Keep required path/digest. Document absent relative-root as outside a declared source root; unresolved-references empty only under analyzed file evidence. Do not conflate those absence conditions with admission/source. No retirement arm. |

The final writer can validate the logical empty relation because the positive
provenance datom survived storage. An absent digest refuses a new definition
even if the caller submitted `#{}`. A partial update may inherit an existing
digest because it is validating the resulting entity, not a patch map. This
proves provenance presence, not that an arbitrary trusted caller cannot forge
a digest. That distinction must be explicit in tests and docstrings.

Compose with the symbols inventory, do not duplicate its mechanical sweep:

- `seon.fn/sym` and `seon.test/sym` become qualified-symbol identities
  (`seon.fn.edn:176` in this tree; inventory §1.1 gives its earlier location;
  `seon.test.edn:49`). Preserve identity, row-schema, source-attribute and
  search-tokenizer properties. `seon.ns/name` stays `:symbol`
  (`seon.ns.edn:5`); a namespace is not a qualified symbol.
- Inventory §1.1 items 3–7: call-arities member 0, caller, callee,
  pending-calls and pending-subject use qualified symbols. The pending-calls
  existence mechanism is removed below, so do not retype then perpetuate it.
- Items 8–13: instrument/fn, throwable-class, changed, destructive-path,
  schema/namespace-name, and render/ai compose with that lane's changes at the
  exact sites listed there. Keep genuine strings and genuine entity refs
  (§1.4–§1.5). Resolve the render/ai text/function collision in its owner;
  no second retype patch here. Keep the `:seon.search/index :symbol` tokenizer.
- Inventory §1.6's four clj-kondo unknown-namespace names must not be fabricated
  as qualified symbols that print as keywords. Preserve unresolved analyzer
  evidence at the analyzer seam; only actual qualified names become calls.
- Coordinate S2's arity/operator retypes (`seon.fn.arity.edn:1`,
  `seon.fn.ast.edn:41`) with component validation, not a competing schema edit.

## 2. Writers and deletion (b, c)

| Owner, inspected site | Exact change |
|---|---|
| `src/seon/fn.clj:584` var-row, calls at `:624`, `:662`, `:701` | Store sets of qualified symbols directly, including capability's symbol; preserve capability-fn as a genuine ref. Always construct logical calls and provenance on fn/test definitions. Delete lookup-ref wrapping and stringification. |
| `src/seon/fn.clj:858` analyzed-form, `:889`, `:922`; `:1134` source-rows | Keep qualified calls even when no current function row exists; remove first-party-existence filtering/splitting into pending-calls. Share the row constructor and source digest. Prelude rows do not become submitted declarations. |
| `src/seon/sci/eval.clj:392` definition-row; `:1902` declared-row | Carry the analyzed row unchanged into admission; compare symbols directly. Neither branch synthesizes calls or provenance from Var metadata. The shared analysis owner supplies them; deletion/schema events are not function definitions. |
| `src/seon/turn.clj:1220` relation-assertions; `:1231` pending resolver; `:1390` row-tx | Assert call symbols directly. Delete call-target existence reads and pending-call promotion. Keep pending-subject handling because subject remains a genuine ref. Remove pending-calls from relation attributes at `:1216` and corresponding schema/consumer branches. |
| `src/seon/fn.clj:2557` reconcile-tx-in | Reconcile desired live rows through existing exact replacement. For `removed`, emit `[:db/retractEntity [identity-attribute value]]`; do not append identity-only desired maps. Preserve writer-time decision and ownership for replacements. Handle every derived identity family, including namespace, schema, file and lint. |
| `src/seon/turn.clj:1280` row-tx deletion | Preserve schema-data-use guard and schema-attribute transaction; replace identity-only exact-replacement calls with retractEntity lookup refs. Missing already-deleted identity is a no-op, not a new row. |
| `src/seon/program.cljc:1013` replacement-tx, `:1034`/`:1050` exact replacement | Keep these for replacements, not deletion. `deletion-row` at `:1057` continues to carry typed identities; symbol retype removes string identities. Delete identity-only retirement expectations in callers/tests and stale docstrings. |
| `src/seon/test/runner.clj:2201` record-tx (formerly `:2092`) | Reach is a set of symbols; direct set difference for held/wanted memberships and symbol retractions. Preserve unchanged-result no-op behavior. Remove known-present/ref minting and per-member existence checks (`:2273`–`:2312`). Require the result subject to exist at the writer; refuse a late result for a deleted test, never mint test or namespace rows. |
| `src/seon/cluster/source.clj:392` result-preservation-tx | Pull/carry reach values directly, never expand or reconstruct fn refs. Preserve original tested basis/digest. |
| `src/seon/cluster/source.clj:454` preserved-evidence-tx | No identity minting or reach ref rewriting. If a required genuine test/file ref cannot resolve in the destination, return a typed unrecordable-evidence outcome; never discard evidence and report success. Retain the existing reported-file value path for absent optional file refs. Decision stays at this writer's database. |
| `src/seon/issue.clj:378` index-tx removals; `:756` adopt-tx removals | Replace identity-only issue replacements by `[:db/retractEntity [:seon.issue/id id]]`. Component citations cascade. Existing creator/append-only authority still applies; a refusal must be reported, not bypassed as part of cleanup. |

**Delete**, with call sites in the same seam commit:
`write-tombstone-validator` (`src/seon/db.clj:2913`) and fallback (`:2965`);
`identity-tempid` (`src/seon/cluster/source.clj:299`), its namespace tempid
helper (`:302`), `mintable-identity` (`:305`), `identity-tombstone-rows`
(`:360`), `identity-ref` (`:373`). `absent-program-identities` (`:340`) has
only the preservation/recording callers found by this audit; remove it with
those callers rather than retaining it for reach. Genuine required lookup refs
use the normal writer refusal. Numeric dangling refs require the final ref
existence check; Datahike's type check alone does not establish existence.

The evidence consequence is deliberate: a completion for a test that was
deleted meanwhile cannot recreate a definition without source/provenance.
Fail the recording operation explicitly and retain the run's existing failure
reporting path. Moving historical results onto a new fact family is outside
this slice, not an excuse to mint a stub or silently filter a result.

Remove these superseded notes in the implementation cleanup commit, after
checking status and transferring any still-relevant evidence into this plan:

- `docs/seon/issues/adoption-refuses-when-test-evidence-names-a-deleted-declaration.md:1`
- `docs/seon/issues/retained-identities-have-no-declared-retirement-state.md:1`
- `docs/prds/steward-platform/research/retirement-is-a-fact-2026-09-16.md:1`

The latter and retained-identities issue are staged additions at entry. Step 1
does not change their index entries: its requested output is only this plan.
After review, unstage only those exact additions and delete them; do not reset
the shared index. The existing tracked issue deletion is path-limited. The
retirement proposal's missing-subject and protected-issue consequences are
captured above; its retired/referenced attributes are rejected by G1.

## 3. Every edge consumer (b, d, e)

Use one immutable database value and one eid→symbol map per gate operation.
Include test identities as caller names as well as function identities; do not
drop a test because it lacks fn/sym. Keep genuine declared reference edges in
their existing eid relation, joining their target to a symbol once when
combining them with calls. No per-edge pull and no persistent mirror.

| Consumer | Exact change |
|---|---|
| `src/seon/fn.clj:1262` declared-reference-rules/edges | Keep entity joins for schema-declared refs; convert endpoints at the shared operation boundary, not by reinterpreting every ref as a call value. |
| `src/seon/fn.clj:1278` test-reach-rules | Call clause joins `[caller :seon.fn/calls callee-symbol]` to `[target :seon.fn/sym callee-symbol]` only when a target entity is required. Recursive continuation uses explicit symbol↔eid joins. |
| `src/seon/fn.clj:1328` gate-set-in; `:1375` gate-sets; `:1416` tests-reaching | Start the reverse walk with the requested symbol even if its definition is absent. AVET calls lookup uses the symbol. Map incoming eids once per operation; acquire declared/file relations once. Preserve subjects and conservative file uncertainty. |
| `src/seon/fn.clj:1422` currently-failing-functions; `:1440` functions-without-tests | Use corrected shared reach rules. Public population requires definition provenance; no inferred live identity stub. Query errors or absent schema/population are unknown, not empty coverage. |
| `src/seon/fn.clj:1510` arity report; `:1561` output-graph | Keep tuple member as symbol. Output graph reads callee directly; do not inner-join away missing targets. Carry unresolved targets into existing unresolved-path reporting. |
| `src/seon/test.clj:54` changed-since-green | Pull reach as symbol values. Join changed source/spec/identity datoms from history/since to historical fn/sym, not current identity only, so deletion and redefine-with-new-eid remain visible. Return named symbols even when current row is absent; revise the ref-shaped output contract. A known completed reach digest plus no members means empty closure, not unknown. Missing run/digest evidence remains unknown. |
| `src/seon/test.clj:238` destructive-path; `:494` identity-tests; `:519` changed-reach; `:527` reaching | Traverse symbol calls, resolve existing targets only where their facts are required. A deleted function symbol still selects surviving callers' tests. Truly unknown seeds with no definition, edge or retained evidence remain typed unknown. Propagate existing refusals. |
| `src/seon/test/selection.clj:122` row-edges | Wrap each call symbol as `[:seon.fn/sym sym]` only in the in-memory identity graph; do not filter it out with `vector?`. Existing references/subject retain their typed identity pairs. |
| `src/seon/test/runner.clj:1006` destructive path; `:1082` fixture edge handling | Replace pair/ref assumptions with symbols while preserving genuine references. Coordinate platform lane's final implementation. |
| `src/seon/test/runner.clj:1874` reach attributes; `:1900` reach-refresh; `:1951` reach-entry | Pull raw calls, resolve via acquired symbol map; retain missing symbols in closure membership AND digest/dependency evidence. Deletion/redefinition must invalidate a prior digest even though caller edges are unchanged. Do not claim green from a digest that silently omitted unresolved dependencies. |
| `src/seon/test/runner.clj:2265`, `:2340`; `src/seon/cluster/source.clj:411`, `:425`, `:448`, `:489` | Remove nested reach pulls and ref conversion; normalize pulled many-values to sets. No 1,000-member truncation (use complete datoms or explicit unlimited pull). |
| `src/seon/issue/detect.clj:228`, `:245` public-without-reaching-test | Keep gate-sets authority; update generated executable examples to quoted symbols. Include positive unresolved-call evidence in detector results; no claim that an unresolved call proves missing tests. |
| `src/seon/render/test.clj:17`, `:102` | Pull scalar calls and render the symbols directly, with separately resolved links where a row exists. Missing targets remain visible text. |
| `src/seon/render.clj:677`, `:682` dependency walk | Replace expanded call maps with raw symbols and direct set accumulation. Missing target produces explicit unresolved evidence rather than disappearing during `keep`. |
| `src/seon/render/ns.clj:835`, `:844`, `:857` function pair | Delete reverse-ref pull `:seon.fn/_calls`; generate an ordinary symbol-join query. Quote symbols in generated `tests-reaching` forms; HTML continues through the same function data. |
| `src/seon/effect.clj:132` reach-rules; `src/seon/test/accretion.clj:69` candidate-capabilities | Join call symbol to target identity before eid recursion; pass the symbol directly instead of `(second call-ref)`. |
| `src/seon/db.clj:2163` external-sink-reach-rules | Add the same symbol→identity join at each call step; preserve the actual sink-ref consumer's eid contract. |
| `src/seon/bootstrap.clj:273`, `:401`, `:416`, `:457`; `src/seon/run.clj:99` | Join callee symbol to the function row before namespace/usage queries; convert subject eid to its symbol at the query boundary. |

This is the dated `src/` search inventory. Before landing, repeat searches for
calls/reach, `_calls`, nested pull selectors, and schema aliases over source,
tests, scripts and resources; inspect every remaining ref-shaped consumer.
The symbols inventory owns other identity coercions and generated forms.

### Unresolved calls and deletion's change plan

**No caller re-analysis is necessary for Option B's unresolved-call fact.**
A's source did not change when B disappeared. Its stored call remains `x/b`;
the following query at the new basis positively returns A and `x/b`:

```clojure
'[:find ?caller ?callee
  :where
  [?caller :seon.program/analyzed-source-digest]
  [?caller :seon.fn/calls ?callee]
  (not-join [?callee] [_ :seon.fn/sym ?callee])]
```

The caller may be a function or test. Resolve its identity through the declared
identity attributes; do not add a fn-only clause that drops tests. Expose one
public query in `seon.fn` beside gate-sets (`src/seon/fn.clj:1375`) and one
detector in `src/seon/issue/detect.clj:245`, returning named caller/callee and
basis evidence, including a positive empty result when observation succeeded.
The namespace function pair (`src/seon/render/ns.clj:835`) teaches that query
and displays its data. `dir` remains its existing contract (`src/seon/sci/eval.clj:1194`):
do not add a second inventory or silently widen it. Detector + namespace page
are the selected surfaces.

Publication's change plan (`src/seon/fn.clj:2025`, `:2064`, `:2113`;
held `src/seon/cluster/source.clj`) must still select tests for deleted symbols
using reverse symbol AVET joins, and invalidate affected cached reach digests.
It need not rewrite A or create duplicate unresolved datoms. Analyzer-level
unknown-namespace/file uncertainty is a different observation and retains its
existing conservative handling. The orchestrator review explicitly extends
the retype to `:seon.fn/references`: its source names survive deletion exactly
as calls do. Apply every calls writer, pull, AVET and recursion conversion above
to references too (`src/seon/fn.clj:630`, `:668`, `:900`, `:1285`, `:1344`;
`src/seon/test/selection.clj:129`; `src/seon/test/runner.clj:1916`, `:1967`).
Schema-declared genuine entity relations remain refs; they are distinct from
the source-level `references` attribute.

## 4. Component validation (f)

Dependency ledger: Datahike `retract-entity`,
`reference-code/datahike/src/datahike/db/transaction.cljc:998`, removes own and
incoming **ref** datoms and cascades components. `explode` at `:739` emits
nothing for empty many-values. Pull expands components at
`reference-code/datahike/src/datahike/pull_api.cljc:345`; its default limit is
1,000 (`:16`, `:315`). Malli validates against the supplied registry through
the existing `write-validator` (`src/seon/db.clj:2704`). No dependency fork is
proposed. Canonical schema declarations, not a hand roster, select children.

Change `write-entity-value` (`src/seon/db.clj:2895`) to obtain the complete
logical parent value with owned components expanded. Use dependency pull with
schema-derived unlimited component selectors, or the equivalent complete
datom traversal at this existing owner; a default wildcard pull is insufficient
because of its 1,000-member limit. Normalize many-values recursively according
to the declared collection shape; decode stored values. Keep noncomponent refs
as refs and never recurse the whole relationship graph. Bounded query-work
failure is a refusal, never validation of a truncated value.

`write-entity-error` (`:2942`) validates this one parent value against its
declared map with component entries naming child schemas. Merely replacing
eids by maps is insufficient: today's component entries often accept only
`:seon.db/ref`, which would accept an id-map without checking child keys.
Use the existing schema declaration/property and storage bridge mechanism to
associate each component attribute with its child schema while retaining
`:seon.db/component true` and Datahike ref storage. Derive the write projection
from those declarations; no separate child-validator registry or invented IDs.

`write-report-error` (`:2992`) currently visits changed eids only. Extend its
affected set to owning ancestors via installed component attributes, using
both before and after databases: a raw child-key retraction must validate the
unchanged parent, and a removed child/edge must validate the former parent.
Validate surviving roots once; fully retracted roots need no live entity
validation. Orphan component-only writes cannot pass merely because no identity
selected a schema: refuse them unless an owning declared parent exists in the
final transaction. Cover cycles/shared ownership honestly rather than looping.

The audit actually lists **29** no-required-identity maps (`schema-key-audit…:140`),
not a definitive list of exactly 15 components. The following is the minimum
component scope; derive the final set from declared component ownership:

| Child schema/resource anchor | Required change |
|---|---|
| `seon.fn.argument.edn:13`, `seon.fn.arity.edn:17` | fn→arities→arguments→binding validates child required keys. Normalize legitimate zero arguments under the owning analyzed definition, not absent analysis. |
| `seon.fn.ast.edn:9`, `seon.fn.ast.entry.edn:6` | fn→AST recursive children/entries validate their declared shapes, including scalar-entry alternatives. |
| `seon.fn.binding.edn:9`, `seon.fn.binding.child.edn:6`, `seon.fn.binding.entry.edn:8` | Binding trees validate through the argument owner; preserve optional typed labels/defaults. |
| `seon.ns.alias.edn:2`, `seon.ns.import.edn:2`, `seon.ns.refer.edn:2` | Namespace components validate target/name obligations. No synthetic component identities. |
| `seon.cluster.eval.edn:206` read-evidence | Evaluation parent validates its owned evidence; compose with the canonical evaluation-schema lane's final key names. |
| `seon.test.edn:242` adoption; `seon.listen.edn:5` pattern | Derive whether actually stored under a component parent; validate there. A marked observation map alone does not imply independent entity custody. |
| `seon.maintenance.result.edn:53`, `:66`, `:81`, `:91`, `:101`, `:120`, `:130`, `:138`, `:145`, `:162`, `:181`, `:203`, `:208` | Result parent validates the selected census/reap/cleanup/collect trees and nested children. Retain their different legitimate optional lifecycle fields. |

The audit's operator/log, footprint and render-cost observation maps
(`seon.operator.log.edn:7`, `seon.operator.edn:49`, `seon.render.cost.edn:6`)
are not made components merely to satisfy a count. Record their actual owning
relationship before editing. Identified components (e.g. test failures) receive
the same parent validation in addition to their existing identity selection.

## 5. Authority rewrite (g)

In the commit landing the schema, replace AGENTS.md §2's two corollaries
(currently in the owner-law paragraph before §1) with this hunk:

> Program deletion retracts the entity with `:db/retractEntity`; history,
> as-of and since retain its past. Call edges and recorded test reach store
> qualified-symbol values, so deleting a definition preserves callers' named
> edges. A name without a current function row is reported by the unresolved-call
> query, not prevented by minting an identity. Every function and test definition
> carries the required analyzed-source digest produced by its analysis; that
> positive fact plus no call datoms means “analyzed, calls nothing.” No
> tombstone, retirement attribute, or empty-collection sentinel is stored.

Update PRD I2/I4 at `program-facts…:389`/`:401` to the same semantics in that
commit. Update stale live-rule prose and the data-modeling skill's tombstone
sentence when landing the implementation; keep historical experiments dated.

## 6. Measurements and regressions (h)

No new performance claim in this plan. The deletion research measured 24.9 ms
ref vs 84.0 ms naive symbol walk on its synthetic population. **Parity with a
single eid→symbol map was argued, not measured** (that note's verification
boundary explicitly says so). Prove or falsify it before claiming parity.

Commit the probe script beside this note in step 2. Read one immutable default
population before reset, recording commit ID, basis, entity/edge counts, target
sample and exact script bytes. Run warmed, alternating ref and symbol walks
over equivalent captured populations; include map construction in per-operation
time and report median/p95 across repeated samples, reachable/test set equality,
and edge bytes. Never install parallel edge attributes into default. After the
orchestrator reset, repeat on the actual symbol schema. Different populations
are labeled separately; they cannot establish speedup. Add deletion/redefinition
targets, empty closures and cycles; verify actual AVET indexing.

Canonical `with-database`, `program-fn-row`, `transacted!`, real SCI and armed
contracts only. Evolve existing tests, one regression per writer/class:

1. Indexer publication (`test/seon/fn_test.clj`): positive nonempty symbol
   calls on both fn and test, source digest, and actual installed symbol/index
   schema. No vacuous `every?` over an absent row.
2. SCI declaration (`test/seon/sci/eval_test.clj`, `test/seon/program_test.clj`):
   real evaluated function/test use the same analysis facts and provenance.
3. Turn settlement (`test/seon/program_test.clj`): direct symbol assertions,
   ns-unmap/schema deletion as entity retraction, and component cascade.
4. Recorder (`test/seon/test/runner_test.clj`): symbol membership persists,
   repeated identical result emits no membership datoms, missing subject refuses
   atomically, and >1,000 members survive read/record unchanged.
5. Publication preservation (`test/seon/source_reconciliation_test.clj`):
   carry symbol reach including missing callees; absent required test refuses
   explicitly without minted namespace/function/test identities.
6. Deletion class (`test/seon/fn_test.clj`): A calls B; deleting B leaves A's
   exact call datom intact; unresolved query returns A→x/b; gate selects A's
   reaching test; redefine B with a new eid and the same edge resolves again.
   Assert history/as-of before deletion and current absence positively.
7. Empty-analysis class (`test/seon/schema_test.clj` or existing write-admission
   suite): analyzed-source digest plus `#{}` round-trips as “analyzed, calls
   nothing”; omission of digest refuses in map AND datom/transaction-function
   grammar and rolls back the entire transaction.
8. Component class (existing write-admission suite): malformed nested map and
   raw update/retraction of child required key both fail at parent path;
   sibling writes roll back; legitimate empty component relation passes;
   >1,000th child is checked; whole-parent deletion succeeds.
9. Consumer class (`test/seon/test_reaching_test.clj`,
   `test/seon/test/selection_test.clj`, existing entity-pair/detector tests):
   deleted target still selects tests, temporal changed-since-green names it,
   unresolved evidence is visible, emitted forms execute with quoted symbols.
10. Issue deletion (`test/seon/issue_test.clj`): index/adopt remove an ordinary
    note entity and citations; protected issue removal returns its named refusal.

Load clojure-testing before implementation. Iterate with
`bin/test-fast --paths <owned files> -- <affected namespaces>`; two shared slots,
bounded waits, no `bin/test`, `--all`, or `--full`. If the shared tree cannot
load, use the assignment's `tmp/edges-are-symbols-wt` HEAD snapshot, link
reference-code, overlay only this slice, and report exact foreign boundary.
Remove only this lane's scratch work after the awaited process exits.
The reset-boundary live proof is pending the orchestrator's reset; never claim
the old default proved the new schema through hot reload.

## 7. File release and commit order (i)

1. **This plan only**, path-limited commit, requested coauthor, stop for review.
2. After approval, check `git status` before each file. The user's held-file
   list is the initial boundary, supplemented by actual uncommitted files.
   At entry SCI eval, schema/datahike, schema, schedule and test-support were
   also modified. Treat them as protected too; no session operations or messages.
3. When fn/program/test-schema owners release, compose the symbol schema and
   analysis provenance with the shared row constructors, fn readers, turn
   writers and AGENTS/PRD rewrite as one admitted publication. Never publish a
   required key before its full constructors or a type before its consumers.
   Free-file edits may be prepared as recorded hunks, not half-published schemas.
4. When source/cluster owners release, land retractEntity reconciliation and
   evidence preservation; when runner/test owners release, land recording,
   reach digests and host-selection consumers. Required hunks are the tables
   above. A still-held file stops its dependent seam with the hunk recorded;
   proceed only with independent authorized work.
5. Land component validation with its schema/bridge consumers after S2 and
   pulled-shape owners release the needed files. Land render/detector consumers
   after their owners release; no transcript/repl edits merely to clear gates.
6. Remove superseded notes and stale instructions with the implemented mechanism,
   run affected fast checks, append exact results and commit IDs below. Each
   coherent seam uses `git commit --only -- <explicit paths>` and
   `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

No changes to `bin/test`, test cache or dev_cache are needed to implement the
model. Their owners' in-flight failures are recorded boundaries, never repaired
by operating another lane. Protected file release is rechecked, not inferred
from elapsed time. No branch switch, default restart/reset, or broad staging.

## Landing note — step 1

Plan only. One owned file added. Runtime status and one read-only schema/count
probe completed; no mutation, performance benchmark, test, publication or
browser observation. **RESET NEEDED** for implementation; not performed.
Staged retirement notes and every foreign edit remain untouched pending review.
The Markdown edit hook reported 30 repository-wide dependency-pin errors,
including `docs/prds/context-generation/research/agents-md-audit-2026-09-15.md:226`
and `:227`; these are outside this file. The owned-file whitespace check passed.
Implementation has not started. Stop here for the requested review.

## Orchestrator review of step 1 (2026-09-17 00:55Z)

Read in full. **Approved for implementation** with one correction and two
notes.

Correction: `:seon.fn/references` is a name in source text exactly as
`:seon.fn/calls` is (a usage the analyzer could not resolve to an arity), so
it retypes to `[:set {:seon.db/index true} :qualified-symbol]` in the same
publication; leaving it a ref keeps one silent incoming-ref retraction alive
on every deletion and contradicts G2. `:seon.fn/unresolved-references` on the
file entity already stores symbols; the three relations then agree.

Notes: (1) the eid→symbol parity for the reverse walk is to be MEASURED, as
§6 says — the old default population before the reset, the symbol schema
after; a regression in the walk is reported with the number, not traded for
fidelity. (2) The AGENTS.md §2 rewrite hunk in §5 lands in the same commit as
the edge schema; the docs lane running tonight adds only the dated pointer.

## Step 2 — first seam preparation (2026-09-16)

The review above was read in full before edits. `references` is included in
the planned atomic edge-schema publication; AGENTS §2 still lands with that
schema, not with this independent issue-deletion seam.

Prepared the [bounded walk probe](edges-symbol-walk-probe-2026-09-16.clj)
and [exact baseline result](edges-symbol-walk-baseline-2026-09-16.edn) first.
The script returns a function and creates no Vars, threads or transactions.
It uses the actual installed AVET indexes and includes identity-map acquisition
once per measured batch. After reset it compares naive and mapped symbol walks
for identical answers. It covers calls/references, not declared dispatch or
file-uncertainty edges, so it is not a benchmark of the complete gate-set query.

Default at basis `536871871`, source commit
`6aab0333-dc9b-5928-9501-a3817f3980a6`: 6,975 function/test identities,
65,119 call/reference edges, 260,697 printed edge-value bytes. Three targets
(`seon.issue/index-tx`, `seon.issue/adopt-tx`, `seon.fn/gate-sets`) reached
116, 87 and 3,056 identities respectively. Ref walk samples, milliseconds per
three-target operation including map construction: **562.141042, 452.444458,
425.844333**; median **452.444458**, p95 **562.141042**. Concurrent tests were
running. These are a small before-reset baseline, not evidence of parity.

The first eight-high-fan-in-target probe hit its declared 30-second bound;
no timing result was claimed. A subsequent authored probe error supplied a
map to `seon.id/digest`, whose contract requires sequential parts; the script
now passes the ordered entry vector. The reported baseline is the succeeding
run (2,073 ms total MCP evaluation). A separate 21 ms read-only as-of query
recovered the source commit at the measured basis. No default writes/reset.

Prepared regression `test/seon/issue_deletion_test.clj` before the implementation:
both note-indexing and adoption retract the removed issue and component
citations, retain the noncomponent cited file and historical entity, and repeat
as a no-op. A second regression verifies both writers refuse removal of a
started issue's last test even for its creator, rolling back sibling deletion
and preserving components and basis. Updated the existing issue and settlement
expectations to honest deletion/refusal.

The production hunk is only the two removed-issue branches in
`src/seon/issue.clj` (`index-tx`, `adopt-tx`): emit
`[:db/retractEntity [:seon.issue/id id]]` instead of identity-only replacement.
Datahike's component cascade removes citations; existing retention admission
remains authoritative. This seam does not require any edge type change.

`src/seon/issue.clj` was clean immediately before the edit. During verification,
another editor added require/direct-call changes elsewhere in it. Those hunks
are preserved and excluded from this seam's verification worktree at
`tmp/edges-are-symbols-wt` (HEAD `6df6967b8` plus only this seam's files).
The worktree links reference-code and the main checkout's test-slot directory,
so it obeys the same two-slot admission limit. No foreign session was operated.
Test results and final landing status follow when the awaited runs complete.

## Step 2 continuation — landing and the held boundary (2026-09-16 16:5xZ)

The lane that wrote the section above stopped mid-verification. This
continuation read AGENTS §0–§3 and §5–§7, the datahike and clojure-testing
skills, this plan end to end including the orchestrator review, and PRD §1f
G1–G6, then re-derived the tree state rather than trusting the note.

**The issue-deletion production hunk already landed.** `git log -S` on the
exact form finds it at `c703fa8da` ("issue: require ai, cluster.message and
seon.test; resolve turn once"), which a concurrent editor of
`src/seon/issue.clj` committed together with its own `requiring-resolve`
dissolution. Both removal branches are live at HEAD: `src/seon/issue.clj:407`
(`index-tx`) and `:776` (`adopt-tx`). Nothing of this seam's production change
remains to write; only its regressions were still uncommitted. The verification
worktree `tmp/edges-are-symbols-wt` is therefore redundant and is removed.

**The schema publication seam (§7.3) is BLOCKED by concurrent holders.**
Every central file of that one atomic publication holds another lane's
uncommitted edits, verified at HEAD `bc0a0c5f1`:

| Held file | Foreign hunk observed |
|---|---|
| `resources/seon/schemas/seon.fn.edn` | `:seon.program/declaration-required true` added to the fn map's ns/source/arglists/private? entries — the same §1 table row this seam must edit |
| `resources/seon/schemas/seon.test.edn` | the same property on the test map's ns/source entries |
| `resources/seon/schemas/seon.program.edn` | new `:seon.program/declaration-required` and `:seon.program/required-attributes` declarations, and the shape map gaining the latter |
| `src/seon/program.cljc` | `shape-in` derives required attributes from those entries; `declaration-required-attributes` hand-map deleted; `canonical-row` gains a shapes argument |
| `src/seon/render.clj`, `src/seon/test/runner.clj`, `src/seon/test/selection.clj`, `src/seon/cluster/source.clj`, `src/seon/sci/eval.clj` | further uncommitted hunks of the same and other lanes |

`bin/codex-agent status` shows `reset-batch-integration` (pid 13811) and
`design-review-eval-path-and-deletion-contract` (pid 17630) live; the schema
files' mtimes fall inside that window. The required-derivables work in flight
is the *same table row* as this plan's §1 fn/test map requirement, so editing
those four files beside it would reproduce exactly the failure AGENTS §7
records as `one-lanes-intermediate-edit-refuses-adoption-for-every-lane`:
a resource is live on disk for every reader the moment it is written while the
JVM still holds the previous `def`.

Per §7.3–§7.4 the seam stops here with its hunk recorded; the hunk is the §1
table (calls, references, reach, fn map, test map, file map) plus the §2
writer table and §3 consumer table, unchanged by this observation. No partial
edge retype was written: publishing a type before its consumers, or a required
key before its constructors, is what §7.3 forbids. **The AGENTS §2 rewrite in
§5 has not landed either**, because §5 binds it to the schema commit.

Consumers that are currently FREE (`src/seon/fn.clj`, `src/seon/turn.clj`,
`src/seon/db.clj`, `src/seon/test.clj`, `src/seon/render/ns.clj`,
`src/seon/render/test.clj`, `src/seon/effect.clj`, `src/seon/issue/detect.clj`,
`src/seon/bootstrap.clj`, `src/seon/run.clj`, `src/seon/test/accretion.clj`)
were deliberately NOT edited: every one of them reads a type this seam cannot
publish, so a free-file edit there is a half-published schema by another name.

**RESET NEEDED** remains true and unperformed; `default` was neither stopped,
reforked nor written. The only default contact was the single read-only
measurement evaluation recorded above.

### Foreign gate boundary observed while verifying this seam

`bin/test-fast --paths <this seam's test files> -- …` refused to initialize at
HEAD `7b779d81a` with `The loaded function contract cannot compile`,
`:diagnostic-member seon.test/check-request`,
`:diagnostic-offending :seon.test.check/request`. The cause is not this seam:
`src/seon/test.clj:982` is COMMITTED (`a00e73e49`) declaring that contract,
while the resource declaring the key,
`resources/seon/schemas/seon.test.check.edn`, is still UNTRACKED at
`2955a0755`. HEAD alone therefore cannot arm — the exact class AGENTS §7 names
(`live-resources-outrun-the-loaded-program-identity-list`), here with the
polarity reversed: the consumer landed and its resource did not. This
continuation does not own `src/seon/test.clj`, `bin/test-check`,
`resources/seon/schemas/my.test.edn` or `seon.test.edn` (all held or committed
by the no-default-cluster lane) and did not commit them. The rerun overlays the
untracked resource read-only via `--paths` so the snapshot loads; the fix
belongs to that lane's own path-limited publication.

### Issue-deletion seam — measured result

`bin/test-fast --paths test/seon/issue_deletion_test.clj test/seon/issue_test.clj
test/seon/issue_settlement_test.clj resources/seon/schemas/seon.test.check.edn --
seon.issue-deletion-test seon.issue-test seon.issue-settlement-test` at HEAD
`61f0332e6` (exit 0 from the wrapper; the suite reports its own tally):
**13 tests, 239 assertions, 1 failure, 1 error** — both foreign, neither in
this seam's assertions.

Green here: `removed-notes-retract-entities-and-components-through-both-writers`
and `removed-started-note-refuses-atomically-through-both-writers` both ran
complete with no failure, as did the rewritten
`indexed-issues-replace-facts-and-retract-removed-notes` deletion assertion
(`test/seon/issue_test.clj:72`) and the settlement refusal expectation
(`test/seon/issue_settlement_test.clj:323`). Deletion is therefore a
retraction through both writers, the component citations cascade, the cited
file's noncomponent ref survives, `as-of` still answers the pre-deletion row,
repeating the deletion writes no datom beyond `:db/txInstant`, and a started
issue's removal refuses `:seon.db/retention-refused` atomically for both.

The two reds are pre-existing at HEAD and outside this seam:

1. `issue_test.clj:65` — `seon.issue/render-ai` no longer emits
   `"(my.issue/status"`. `65986edf7` rewrote the render onto `status-view`
   without updating the expectation. Filed as
   [the-issue-ai-render-no-longer-teaches-its-requery-form](../../../seon/issues/the-issue-ai-render-no-longer-teaches-its-requery-form.md);
   deliberately NOT weakened here, because whether the status render must
   always carry a runnable form is the render owner's call.
2. `issue_settlement_test.clj:210` — `nth not supported on this type:
   PersistentArrayMap` at `src/seon/cluster/wake.clj:422`. Already owned and
   in flight: `docs/prds/steward-platform/research/batch-113-wake-matchers-2026-09-16.md`
   describes the same stack, and `src/seon/cluster/wake.clj` holds that
   lane's uncommitted fix. No second note filed.

The first attempt at this gate refused to initialize for the `check-request`
resource boundary recorded above; the rerun overlays that untracked resource
read-only. `clj-kondo` over the three owned files: 0 errors, 1 warning
(`issue_test.clj:409` shadowed `agent`, pre-existing).
