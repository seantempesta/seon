---
type: research
status: active
created: 2026-09-17
tags: [datahike, schema, reset, deletion, provenance, query]
---

# Datahike modeling study — 2026-09-17

The reset's direction is sound: preserve name observations as indexed values,
validate complete owned values at the final transaction boundary, and record
the observation that makes an empty result meaningful. Several stronger claims
in the supporting research are false. Datahike does not enforce ref existence
for numeric ids, component exclusivity, a one-transaction/one-commit relation,
or replayable listeners. GC of old commits does not remove historical datoms
still reachable from the head.

The corrections below are design findings, not implemented guarantees.
[Program-facts PRD §1i](../plan/program-facts-are-the-runtime-prd-2026-09-17.md)
was added during this investigation and makes this study an input to rebasing
the reset plan. It does not enlarge this assignment into production work.

## Evidence and boundary

AGENTS.md sections 2 and 3 and both requested skills were read end to end.
The review inputs are the
[reset batch](../plan/reset-batch-2026-09-17.md),
[reset recommendations](reset-schema-recommendations-2026-09-16.md),
[schema design review](schema-design-review-2026-09-17.md),
[schema key audit](schema-key-audit-2026-09-16.md),
[program deletion study](deletion-semantics-program-graph-2026-09-16.md),
[agent/turn deletion study](deletion-semantics-agents-and-turns-2026-09-16.md),
[connection inventory](unbreakable-connections-2026-09-16.md), and
[program-facts PRD §§1f–1h](../plan/program-facts-are-the-runtime-prd-2026-09-17.md).
Their dated counts are not observations of the current cluster.

Dependency evidence is the checked-out Datahike gitlink
`73afe78271a289861da236c5ac3457e64349653f`; its final-report-validator
commit and tests were read, together with the complete transaction owner.
All abbreviated dependency paths below are under
`reference-code/datahike/src/datahike/`. Other dependency paths are explicit.
A sampled first-party HEAD was `8dff32220b`;
first-party citations describe the working bytes opened during concurrent
editing, not a frozen implementation proof.

Exactly two read-only MCP JVM evaluations used explicit custody
`(seon.operator/connection "default")`. Both observed basis **536871223**;
the first recorded commit **6aab5437-8aa2-50d6-9c84-4cb450534fac**. Tool evaluation times were
79 ms and 52 ms; these are not index benchmarks. Neither transacted.

Default was alive, but runtime status and both result envelopes carried
`:seon.config/missing-effective`. The first evaluation's result projection
failed; the second printed the retained first result and its own selected
observations through the normal MCP tool. Two attribute reads returned
`:seon.schema/missing-projection`; those counts remain unknown. This is the
[existing default configuration issue](../../../seon/issues/the-default-clusters-effective-configuration-lost-every-required-fact.md),
not evidence against the schema proposal. Its statement that forms are refused
before evaluation is too broad for these observations: execution and stdout
succeeded while result rendering refused. No third evaluation, raw prepl
workaround, cluster lifecycle action, test JVM, gate, or lane launch was used.

### Dependency ledger

| Mechanism | Read source | Seon boundary |
|---|---|---|
| Transaction expansion, uniqueness, cascade, final validation | `db/transaction.cljc:641-855`, `:998-1154`, `:1206-1276`; fork commit above | `src/seon/db.clj:3220-3297`, `:3424`: attempted/effective datoms and the final callback |
| Schema and index selection | `schema.cljc:11-78`; `db/utils.cljc:307-327`; `db/search.cljc:140-157`; `index/persistent_set.cljc:31-245` | `src/seon/schema/datahike.clj:232-275`: derives installed facets |
| Pull and owning values | `pull_api.cljc:204-243`, `:291-385` | `src/seon/db.clj:3002-3017`: current final row is read without graph expansion; G5 must change coverage |
| Query binding and plan choice | `query.cljc:1252-1395`, `:2200-2300`; `query/estimate.cljc:28-127`; `query/plan.cljc:1002-1125`; `query/lower.cljc:1066-1105` | Existing plan-derivation issue and `seon.plan` read |
| Temporal reconstruction | `db.cljc:142-198`, `:248-310`; `db/utils.cljc:239-265` | `seon.db/history`, `as-of`, `since`; program history and read evidence |
| Writer, listener and storage | `writer.cljc:147-218`, `:234-273`, `:389-442`; `core.cljc:200-218`; `writing.cljc:423-546` | Database reports, wakes, cluster branch custody |
| Roots and reclamation | `gc.cljc:22-81`, `:123-167`; `versioning.cljc:224-310`, `:550-744`; `reference-code/konserve/src/konserve/gc.cljc:22-49` | Store custody, branch lifecycle, blob reachable-extension |

## Ten deeper truths

### 1. A reverse walk is an attribute/value seek, not a special ref index

The DB has EAVT, AEVT and AVET and their temporal counterparts
(`db.cljc:310`). There is no VAET. The search decision table uses EAVT
for a bound entity, AEVT for a bound attribute, and AVET for bound
attribute/value only when the attribute is indexed; otherwise it filters
AEVT. A value without an attribute filters EAVT
(`db/search.cljc:140-157`).

Refs and both unique kinds automatically enter the indexed set
(`db/utils.cljc:307-313`). Ordinary symbol/keyword edges do not. Thus
`[?caller :seon.fn/calls 'callee]` on indexed symbol edges has the same
index access shape as the numeric-ref version. A reverse walk across every
possible ref attribute pays a seek per ref attribute; that is literally how
retractEntity finds incoming refs (`db/transaction.cljc:1002-1013`).

The persistent sorted set descends the tree with comparisons, then consumes
the matching range (`index/persistent_set.cljc:194-245`;
`reference-code/persistent-sorted-set/src-java/org/replikativ/persistent_sorted_set/PersistentSortedSet.java:181-213`;
`reference-code/persistent-sorted-set/src-java/org/replikativ/persistent_sorted_set/ANode.java:85-86`).
The derived cost is roughly **O(log N + K)** comparisons/
returned datoms for a seek, versus scanning the attribute's M datoms without
AVET. Symbol comparison/serialization costs more than comparing longs;
equal access complexity does not establish equal milliseconds. Branching,
cache misses, closure fan-out and one eid→symbol mapping per operation matter.

**Consequence for Seon:** keep the symbol-edge retype and explicitly index
ordinary reverse-read edges; do not add redundant index flags to identities
because their literal schema map omits `:db/index`. Reset recommendations
D-d's claim that unique identity is insufficient for index-range is false:
`db.cljc:284-296` checks the derived indexing predicate.

### 2. A tuple is one ordered value; its members are neither attributes nor refs

A tuple occupies the V slot of one datom. Slice construction works on datom
prefixes, not tuple-member prefixes (`index/persistent_set.cljc:31-132`);
JVM value comparison uses Clojure compare (`datom.cljc:262-292`). Exact
indexed lookup of `['callee 2]` is one AVET seek. Extracting only the callee
with a query function does not magically acquire a member index. A carefully
bounded range may exploit ordering, but the whole vector remains the key;
a shorter vector is not a declared wildcard.

`schema.cljc:11-55` declares scalar types bigdec, bigint, boolean, bytes,
double, float, number, instant, keyword, long, ref, string, symbol and uuid,
plus tuple and schema vocabulary types. There is no native arbitrary map
type. Homogeneous `:db/tupleType`, heterogeneous `:db/tupleTypes`, and
composite `:db/tupleAttrs` are distinct declarations (`:65-78`,
`:167-173`). Seon's bridge currently emits heterogeneous tupleTypes
(`src/seon/schema/datahike.clj:266-269`).

There is a concrete dependency defect: heterogeneous `check-tuple` rejects
differing validity booleans, so ALL-false member validity passes
(`db/transaction.cljc:1033-1043`). The native datom route also does not
establish the same tuple member checks as the add-operation route
(`:1320-1328`). Seon's final attempted-datom validation is therefore
material. A many-valued tuple attribute must receive a collection of tuples;
a bare tuple vector is expanded as its members (`:718-737`).

**Consequence for Seon:** call-site pairs are good value tuples, with explicit
stored-member validation; use separate calls membership for reverse callers.
Never embed refs in tuples expecting lookup resolution, incoming sweeps or
component cascade: those branch on the attribute's whole ref type
(`:786-790`, `:998-1015`).

### 3. Uniqueness, upsert identity, existence and permanence are four different promises

Both unique kinds prohibit competing current owners of an attribute/value
(`db/transaction.cljc:26-32`) and support lookup refs
(`db/utils.cljc:109-148`). Only unique **identity** participates in
entity-map upsert/coalescing (`db/transaction.cljc:641-713`).
Unique **value** makes an accidental second owner fail rather than merge.

Resolution reads the transaction's current AVET. Retract first and the key
is free for a later assertion; assert first and a conflict may refuse before
the later retraction. An upsert of an existing identity resolves its current
owner; incompatible explicit ids or multiple identities refuse. Reasserting
a retracted identity without its old eid can allocate a new eid. Supplying
that numeric eid explicitly can reuse it. There is no permanent identity
reservation after retraction.

A positive numeric eid resolves as a number without requiring target datoms
(`db/utils.cljc:109-148`). Missing lookup refs refuse, but that does not make
all refs foreign keys. Native value-type checks are not existence proofs
(`db/transaction.cljc:33-52`).

There is also a source-level weakness in `schema.cljc:6-9`: its id predicate
uses the function value `string?` as an `or` arm instead of calling it.
That predicate alone therefore cannot establish ref syntax. This is not a
claim that every malformed ref reaches storage: `entid-strict` and Seon's
own grammar checks still participate. It is a dependency correction to track
with the tuple-validation defect, not permission to weaken Seon's contracts.

**Consequence for Seon:** G3's “as Datahike already refuses” sentence needs
qualification. Preserve the admitted writer's target checks; test numeric,
lookup and nested-map grammar separately. Detect removed identity VALUES,
including an identity retraction/rename on a surviving eid, not only eids
whose entire row became empty.

### 4. An absent collection is not an observed empty collection, and a no-op is not an event

Entity-map expansion emits one operation per member; empty many values emit
none (`db/transaction.cljc:718-770`). Final datoms cannot distinguish omitted
analysis from analysis yielding zero members. An explicit `#{ }` can still
be inspected as a transaction-function ARGUMENT; the earlier blanket claim
that no transaction function can see it is false. That inspection alone
does not leave durable evidence.

G4's positive analyzed-input digest supplies content provenance. It proves
the canonical writer recorded analysis of those bytes, not that arbitrary
callers supplied truthful content. The writer must own its derivation.
A digest of identical bytes is not a fresh occurrence: effective tx-data
omits idempotent assertions (`:587-625`), though the fork's validator sees
attempted datoms. If counting repeated identical observations matters, each
occurrence needs its own event identity/transaction fact.

**Consequence for Seon:** retain the exact analyzer-input digest and allow
empty edge sets. Definition analysis, test reach and read observation need
their own applicable provenance; none proves the others occurred. Do not add
a fresh event entity merely when content provenance already answers the query.

### 5. Components prescribe traversal and deletion, not exclusive ownership or complete validation

retractEntity removes own datoms and incoming REF datoms, then recursively
deletes component targets from the entity's OWN datoms
(`db/transaction.cljc:998-1015`, `:831-836`). Incoming sweeps do not cascade
the swept referrers. Plain `:db/retract` of a component link does not cascade;
retractAttribute does (`:1060-1078`). A shared child is not protected by
another parent's link when one parent cascades into it.

The component flag is on **parent→child**. The skills' prior “target contains
referrer” formulation reversed this direction. Pull auto-expands components,
but cycles/already-seen entities yield id-only maps
(`pull_api.cljc:238-243`, `:346-351`). Reverse component pull is treated
as single-valued (`:328-329`); that is not proof the database enforced one
parent. A wildcard's default many limit is **1,000 per entity/attribute**,
with no signal (`:16`, `:315-323`). Explicit `:limit nil` disables that
take, not the query-work bound.

**Consequence for Seon:** G5 requires affected owning roots from before AND
after, complete traversal under a declared bound, and a typed refusal when
completeness cannot be obtained. A malformed child-only edit, detached child,
cycle, multiply owned child and 1,001st child must not escape through a
validator that only visits changed identity-bearing eids.

### 6. Transaction functions decide at their position; the final validator decides over the whole transaction

`:db.fn/call` applies its function to the mid-transaction database and splices
returned transaction data (`db/transaction.cljc:1153-1154`). Earlier operations
are visible; later repairs are not. Tempid conflict resolution can restart
processing from the initial report (`:844-855`). Functions should therefore
be pure and must not rely on exactly-once external effects. Throwing abandons
the transaction; it does not undo external effects (`writer.cljc:147-218`).

Fork commit `73afe782` adds the right final invariant seam:
`validate-report` runs after transaction expansion, tuple flushing and final
database construction. It gets db-before/db-after, effective tx-data, and
attempted datoms including no-ops. Nil accepts; **any non-nil return, including
false, rejects**. The callback is stripped from stored metadata
(`db/transaction.cljc:1206-1276`). A refusal prevents commit and preserves
sibling rollback. Raw load-entities/import is a separate path
(`:1330-1401`; `core.cljc`'s load-entities-with), not an admitted
transaction just because it eventually uses the writer.

**Consequence for Seon:** strict deletion with same-transaction repair belongs
in the existing final validator. Do not impose an operation-order restriction
through a pre-read or add a second writer. Admission guarantees must name
which bootstrap/import routes deliberately bypass this seam.

### 7. Historical queries reconstruct retained evidence; retention has a write and read cost

For a retained retraction, current EAVT/AEVT and indexed AVET lose the entry.
The old positive datom and the new negative datom are inserted into the
corresponding temporal indexes (`db/transaction.cljc:440-484`).
That is two temporal insert operations per applicable index, not necessarily
two newly allocated datoms: an assertion can already exist and set insertion
deduplicates. Cardinality-one replacement has its own temporal-upsert path
(`:539-571`). Cascade and incoming sweeps multiply this work by all affected
datoms. No-op assertions avoid business-datom churn.

Retention requires BOTH keep-history and absence of noHistory.
History merges current and temporal evidence; as-of includes its boundary,
since excludes it (`db.cljc:142-152`; `db/utils.cljc:239-265`).
As-of/since reconstruct selected state by grouping candidates and resolving
cardinality/history (`db.cljc:154-198`). A broad historical query costs
the retained candidate population, not just the current entity size. A
since value alone is not interchangeable with a complete retraction log;
query historical datoms when the added flag matters.

**Consequence for Seon:** use history for prior definitions and changes within
the retained database, with narrow entity/attribute constraints. Preserve
positive current lifecycle facts when required by current queries. Turning
on noHistory is an explicit loss of temporal evidence, not free compression.

### 8. Branch/commit retention and temporal retention are independent

Same-store branch creation reuses stored index roots and updates the branch
roster (`versioning.cljc:224-284`); it does not copy all datoms.
Cross-store `fork-database` copies every enumerated store key
(`:550-724`). `merge!` accepts caller-supplied transaction data and records
parents; it does not resolve semantic conflicts for the caller (`:726-744`).

The writer can batch several transactions into one commit
(`writer.cljc:234-273`). Transaction t and commit id are therefore different
coordinates. The fork-database docstring's “one commit per transaction”
assumption is stronger than this writer guarantees; integer fork-point
selection searches stored commits' max-tx and can miss an intermediate t.
An as-of query of retained temporal data is not the same operation as
obtaining a writable branch at that t.

Storage writes nodes/schema/commit before the mutable branch head
(`writing.cljc:498-546`). Konserve promises ordered sequence batches;
all backends need not provide atomic multi-key writes
(`reference-code/konserve/src/konserve/core.cljc:435-464`). This permits
unreachable leftovers after failure without publishing an incomplete root.

GC starts from the branch roster, retains branch heads and walks selected
commit ancestry, marks their current AND temporal index roots and schema/
secondary-index data, then includes any reachable-extension
(`gc.cljc:22-81`, `:144-167`). It deletes unmarked objects older than
the captured writer safe point
(`reference-code/konserve/src/konserve/gc.cljc:22-30`).
A held immutable database object is not itself a registered root.
Removing a branch only permits later reclamation; other roots may share all
its nodes. A narrower commit window still preserves temporal history reachable
from a retained head. Purging one branch likewise does not erase old retained
snapshots. The safe-point machinery is local to cooperating writers, not a
cross-process store lock (`gc.cljc:136-142`).

**Consequence for Seon:** do not sell GC as reclaiming deleted entities' history,
or as an answer to live unreferenced shape rows. Those remain head data until
retracted/purged under a chosen retention policy. Keep Seon's single-process
store custody and distinguish cheap branches from cross-store copies.

### 9. Listen is a notification of committed work, with neither replay nor exact per-transaction after snapshots

listen! installs a callback on one connection's listeners atom
(`core.cljc:200-218`). On successful transact or merge, callbacks receive a
report (`writer.cljc:410-442`). Failed transactions do not notify.
Registration is not replay; disconnect, restart, unregistration or another
connection can leave a consumer with no notification.

There is a subtler limit: the commit loop replaces each batched report's
db-after with the batch's committed database, while tx-data remains the
individual transaction's effective datoms (`:234-273`). A listener can thus
see later transactions in its after-value. It must not infer an exact
before/after diff from that pair. The final validator runs earlier against
the exact final value of its own transaction.

Callbacks execute before the caller's promise is delivered, without a local
catch around the callback loop (`:410-417`). A throw can leave a durable
commit with a caller that does not receive the normal result; a blocking
callback obstructs delivery. This is a source-derived failure mode, not an
injected fault experiment on default. With keep-history enabled, metadata
entries become tx-entity datoms through flush-tx-meta
(`db/transaction.cljc:903-922`, `:1251-1254`); that loop does not enqueue
them when history is disabled. The validation callback itself is explicitly
excluded from stored metadata.

**Consequence for Seon:** listen before deriving current work, treat channels
as hints over durable facts, and use report tx-data for the event being
reported. Handler claims and wake answering must remain recoverable by query,
not by receiving every callback.

### 10. Query correctness must survive a different plan

Rules describe relations, and not-join declares the variables shared with
its outer scope. The legacy resolver checks bindings
(`query.cljc:2200-2300`); optimized lowering and execution must preserve
those semantics. Planner estimates sample **64** datoms in some paths
(`query/estimate.cljc:28`, `:115-127`, `:311-367`).
Entity-group order uses exact dynamic programming through **16 groups** and
greedy order above that (`query/plan.cljc:1002-1006`, `:1113-1125`).
These are optimization thresholds, not valid limits on query truth.

The current lowerer's NOT validation accumulates outputs from entity-group,
pattern-scan and function operations but not recursive-rule operations
(`query/lower.cljc:1066-1105`). A valid rule-bound variable may consequently
look unbound after a cost-driven reorder. The
[existing query issue](../../../seon/issues/the-query-planner-rejects-a-negation-bound-by-a-recursive-rule.md)
records small/populated differences, including 405 and 1,005 entities.
Those are observed fixtures, not hard-coded planner thresholds.
A rewrite to or-join is not proof the class is fixed.

**Consequence for Seon:** preserve derivable plan state as a query and fix/
verify the dependency's binding propagation at the owner. The class regression
must exercise multiple plans, not just add a particular number of filler rows.
No performance or correctness rerun is claimed here.

## Review of the reset batch

This is the correction set for the integrator, not a competing 76-table edit
schedule. The batch already incorporates several corrections from the earlier
reviews. The following distinguishes confirmed choices from changes still
needed in its text and implementation.

| Decision | Verdict and correction | Reason / required implementation evidence |
|---|---|---|
| Calls, references and recorded reach become symbol sets | **Keep.** Ordinary reverse-read edge attributes need explicit indexes; identities already index automatically. Keep current obligations separate from advisory historical reach. | Truths 1–3. Live calls are still ref-many, 65,824 datoms. No post-retype parity claim is available. Include map acquisition and equivalent populations in the batch's existing benchmark. |
| Strict program deletion | **Keep the final-state rule; widen its detection.** The plan's “had datoms before, none after” test misses removing/replacing an identity while other datoms remain. Collect affected prior identities, determine which names no longer resolve to valid declarations in the final value, then query surviving obligations. | Identity value and eid differ (truth 3). Cover identity-only retraction, identity rename, raw/nested expanded ops and repair/delete in both orders. Do not retrospectively block every unresolved token from a fresh publication; retain its explicit unresolved report. |
| G4 analyzed-source-digest | **Keep, precisely scoped.** Derive from the exact analyzed input, including any resolver prelude. Require it on complete definition rows; core file provenance and agent provenance are different arms. Do not require compactable evaluation refs merely to keep analysis evidence alive. | Truth 4. A digest is content evidence, not proof that reach ran or a count of analyses. Analyzer behavior also depends on its publication/configuration; retain the existing publication provenance when interpreting results, without inventing a second analysis registry. |
| Nineteen remaining required-many keys | **Keep the correction to optional stored membership.** Two cluster collections, one arity collection, two adoption collections, fourteen maintenance collections are not nonempty facts by construction. Remove contradictory generic “retain required entries” instructions for these same keys. | Truth 4 and the live zero-arity counterexample below. Preserve positive construction/observation facts, not dummy members or fake empty datoms. The six activation collections were handled by the earlier seal slice; do not count them again. |
| Maintenance completion provenance | **Sharpen.** A successful parent operation may license its own empty result. It must not make an absent, skipped or failed sub-operation look completed. Tie the child observation to the operation actually performed; use existing required scalar evidence where sufficient. | A generic parent completed-tx cannot prove every possible optional result branch ran. Exercise all four actual constructors, an empty successful result, a partial/error result and an absent result. Do not add fourteen marker booleans. |
| G5 parent/component validation | **Keep with completeness obligations.** Visit owning roots reached from before and after; retain the distinction between an owned occurrence row and its ref to a shared target. Require complete values or refuse under a declared bound. | Truth 5. A wildcard pull and a changed-eid list alone are insufficient. Include >1,000 children, child-only writes/unlinks, multiple-parent and cycle cases; no invented component identity. |
| Archived agents | **Apply §1g, not the stale exclusion.** Retain agent identity and add archived-tx with derived archived? for UI filtering. Archival does not stop graphs, detach ownership or disable resumption. Delete the plan's proposed successful agent/message co-deletion proof. | Archival is a new positive state fact, unlike a program tombstone. Live archived-tx is not installed. Datahike cannot justify a “never deleted” promise solely from required incoming refs: coordinated deletion could remove them all. The admitted agent lifecycle must enforce the ruled invariant, rather than merely omit a UI delete button. |
| Capability handler | **Apply §1g.** Delete declaration capability-fn ref and put the existing handler symbol in final deletion obligations. It is no longer an owner question. | The symbol observation survives deletion, and indexed lookup provides the join. Do not carry two encodings to save an unmeasured lookup. |
| Message subject / sender / protocol | **Apply all of §1h in one publication.** Subject is the supplied identity token; from alone marks inside; assignment/declination gets its own fact. Remove resolve-about and the inside property on about. | Live about and from are separate datoms on different messages. Native ref sweep can erase about; it must not change subject, provenance classification and protocol state together. Truths 3–5. |
| Message handling | **Delete the endorsed inbox-move pattern.** Restore listened to and a claim from the handling turn with the answering-t rule. Removing inbox without changing wake/query writers is incomplete. | §1h and truth 9. Claim settlement needs final write authority to avoid duplicate handling; listener delivery is not a claim. Current system turns alone do not answer wakes (`src/seon/turn.clj:3011-3037`). |
| Origin | **Apply §1h's narrow type.** eval/origin becomes issue/id value, retaining generated-by display. Do not design a generic entity-ref carrier for this already scoped field. | A generated evaluation records a source token; the issue's later deletion must not erase it. Zero live origin datoms here is no evidence of a correct writer. |
| Refreshes | **Delete the attribute and both refresh-call functions as ruled.** Keep latest-read derivation by ordinals; no new digest/index without a query requiring it. | Unique-value refreshes is installed but has zero datoms in this probe. Its uniqueness would constrain one successor, not establish a complete refresh history. Absence is not proof of execution. |
| fn.ast merge | **Recommend plan Q2 A, narrowed to existing semantic obligations.** Replace backfill/reconciliation presence checks with provenance and canonical contract shapes, then delete AST writers/resources. Never turn shared shape roots into fn-owned components. | Plan Q2 already refuted “no production readers”: maintenance readers exist. `src/seon/fn/schema_shape.clj:257-309` deduplicates shapes; shape child/entry occurrence rows are components, their schema links are ordinary refs. Keep that architecture. Price and remaining owner choice below. |
| Optional refs required “when present” | **Reject that phrase as a deletion guarantee.** Ordinary optionality allows a sweep. Use a genuine surviving outcome/provenance fact to require the relation conditionally, or store an observation value; do not require every attempt to carry error/failover data. | Truths 3 and 5. A condition derived solely from the swept key vanishes with the key and proves nothing. This corrects over-broad refuse recommendations in the agent/turn inventory. |
| History instead of issue status | **Keep the batch's X1 correction.** Do not delete authoritative status before positive replacement writers cover imported resolved AND superseded notes. | Live 1,766 status datoms versus zero resolved-tx. History can answer changes actually written; it cannot synthesize an unrecorded transition. |
| -at becomes -tx | **Apply only to recording/transition time.** Retain externally observed occurrence time separately when it differs from recording time. Avoid treating required tx provenance as proof every asynchronous operation succeeded. | Truths 4 and 7. A transaction records an observation later than the observed event; equality is a writer guarantee, not a type fact. |
| Pull-derived contracts | **Keep derivation; qualify the restrictions.** Refuse unsupported selectors honestly, but do not call all limits/recursion invalid dependency features. A validator needs complete owning values; a presentation can expose a bounded result with an elision. | Truth 5. Explicit limit nil disables default take. Aggregate population counts cannot prove any individual pull was cut. The bridge must still distinguish transaction, datom and pull grammars. |
| Shared shape reclamation / GC | **Do not add a collector merely because shape refs become noncomponents.** Retain the existing shared model and measure current roots/reachability before choosing reclamation. Do not claim Datahike storage GC collects logically unreferenced shape entities. | Truth 8. Every still-asserted shape row is reachable from the head indexes. Reclamation would first be an admitted database retraction and only later a storage-retention decision. |

The fourteen maintenance keys are already enumerated in the reset batch's
maintenance-result row: cleanup remaining/removed; collect branches; census
claim-errors/dead/processes/roots/unclaimed/unresponsive; process advertisements;
and reap refused/roots/stopped-processes/eligible-root-claims. Their exact
attribute declarations, not this dated count, remain the inventory authority.

Two qualifications change earlier rulings without changing their intended
model. First, G2's “deleting a function touches only its own datoms” is true
for symbol observers but false globally: components cascade and incoming refs
still sweep. Second, G3's missing-lookup-ref behavior does not cover numeric
refs. Neither qualification requires restoring tombstones.

## Standing patterns, checked against live counterexamples

These are attempts to falsify simple rules using actual default facts. Where
the probe did not exercise a write or deletion, the limit is explicit.

| Proposed standing rule | Live counterexample to the over-broad version | Rule that survives |
|---|---|---|
| Identity value versus relation | Calls are 65,824 ref datoms today; message about has a ref to 50213 while from on another message refs agent 48636. Treating every ref as the same semantic relation conflates a named subject with a sender. | Choose by what the writer asserts. Preserve name observations as values; retain actual custody/ownership relations as refs. These reads show current representation, not a measured deletion. |
| Event versus collection | Arity entity 22527 has min 0 and no argument datoms. Requiring stored arguments would reject a legitimate zero-argument shape. | Require the analysis/construction evidence, allow zero members. The probe did not inspect this row's complete contract; it establishes absence of membership beside a positive zero-arity fact, not complete row validity. |
| Tuple versus many values | 57,926 call-arities datoms; one is e1674 → ["seon.run/wait" 1]. The tuple's callee is still a string before reset. | Fixed pair is a tuple; symbol type must be checked inside it. Its existence does not prove symbol compliance or supply a reverse index on its first member. |
| Component versus ordinary ref | e1674 has an arities component to 20538; agent 48636 has runtime component 48639. Both need owning-value validation although their children need not be globally named observations. | Parent owns child; shared semantic targets use ordinary refs. The live sample does not prove exclusive ownership or cycle freedom; those are implementation proofs still owed. |
| History instead of an attribute | Status counts are open 322, resolved 1,173, superseded 271; resolved-tx has zero datoms. Inferring every issue open from absent resolved-tx would contradict 1,444 terminal facts. | Derive from retained evidence only when the positive event writer actually supplies it. Keep imported state until replacement facts preserve its meaning. |
| Positive archive state versus deletion | archived-tx is not installed, while an actual agent/runtime ownership edge exists. “No archive datom” cannot prove this cluster supports archival. | Install the ruled positive state and its writer; never interpret a missing schema as a healthy empty population. |
| Index declarations versus derived index capability | ns/name is symbol unique-identity with 455 datoms and no explicit index flag in the inspected declaration. | Derive indexing from Datahike's reverse schema; literal absence of index does not mean absent AVET. No seek timing was measured. |
| Population size versus pull completeness | Reach has 102,689 datoms; the largest observed group is [20421 1000]. | Per-entity cardinality matters. Exactly 1,000 neither proves a lost 1,001st value nor proves that a previous writer preserved all members. The earlier 484,412 aggregate proves neither. |

No claim of full live falsification is made for GC, callback throws or
transaction reordering: those would require mutations/lifecycle experiments
outside the two-read-only-probe boundary. Their conclusions above follow the
opened dependency source; the reset's canonical regressions must verify Seon's
integration.

## Owner questions that remain open

**Q2: preserve existing contract-query behavior while removing fn.ast, or
remove only the redundant representation?** I recommend **A: carry the
existing reconciliation/backfill obligations into schema.shape and the
already stored provenance/arity facts, then delete fn.ast**. Budget about
**one engineering day**, including real changed-contract and repeated-
publication regressions across fn/program/turn. This preserves present
contract-query behavior, gives up old AST-specific addresses, and creates
no second tree. **B: delete the AST machinery and rely on existing
spec/arity facts without promising any AST-specific query preservation** is
about **half a day** with the same admission checks. It is smaller only if
those facts already cover every actual consumer. The plan's 29-line inventory
establishes maintenance readers, so “no production reader” cannot decide B.
Under either choice, shared shape targets remain noncomponents; that is a
dependency consequence, not a third option.

**How broadly should a message's subject token be queryable at this reset?**
§1h settles its independence from sender/protocol and forbids resolution as
a prerequisite, but does not specify a universal identity-token grammar.
I recommend **A: use the existing admitted subject-token grammar, preserving
the supplied token and its family when that grammar supplies one; scope the
reset to removing resolution and the overloaded meanings**. Budget
**half to one engineering day** across message constructors, schema, rendering
and canonical message/wake regressions; this gives up a promise of one native
index covering arbitrary heterogeneous identities. **B: standardize all
message subjects now as a declared identity-attribute/value pair**, validating
the value against that identity's declared grammar while allowing a missing
target. Budget **one to two days** plus every sender/reader, codec and equality
query. It guarantees unambiguous cross-family subject queries, but arbitrary
identity values may need the existing codec: a native tuple cannot contain
every possible map/collection value or index each member separately.
Do not confuse validating a token's grammar with resolving its target.
Choose B only with a concrete cross-family subject query that A cannot answer.

These are estimates, not measured implementation times. Archival, capability-fn,
origin, refreshes and the message handling mechanism are already ruled in
§§1g–1h and are not reopened here. No default mutation or production edit is
authorized by either answer in this study.

## Skill corrections and verification boundary

The two skill files are real files under `.agents/skills/`;
`.claude/skills` resolves there. Corrections cover component direction and
coverage, qualified history retention, non-global purge, numeric ref/upsert
semantics, explicit empty transaction-function arguments, final-validator
authority, tuple/index behavior, planner thresholds, branch/GC semantics and
listener batching. Removed the unsupported aggregate-reach truncation claim,
the blanket selector ban, and the obsolete message inbox move as a standing
pattern. Also corrected system-turn wake answering and the obsolete
base-diffs description against the opened current owners.

Only the two skills and this note are changed by this assignment. Existing
foreign working-tree edits were preserved. Concurrent decision-guide link
paragraphs in both skills are excluded from this commit and remain in the
working tree. Markdown hooks reported **31
repository lint issues**, including stale dependency gitlinks in the older
agents-md audit; the shown errors name foreign documents. Hook output also
queued documentation/skill publication. No successful live adoption or browser
paint is claimed, and the two-evaluation limit prevents another freshness
probe. File-local Markdown validation of all three deliverables and
`git diff --check` passed after correcting this note's tag and an ambiguous
nearby first-party/dependency SHA citation. No source, schema, dependency,
test or lifecycle change was made here.
No scratch cluster, worktree or background shell was created.

Proof still owed by implementation: indexed symbol-walk timings on equivalent
populations; final-report identity-removal coverage; complete bounded G5
validation; actual message and archive behavior; and the orchestrator's
reset-boundary armed gates. This design study is not their substitute.

## Reproducible read-only evidence

These are the exact two forms, embedded here so the one-note deliverable
retains its probes. The second uses the normal JVM REPL's retained `*1`
because the first result's projection refused. Neither form transacts.

### Probe 1

```clojure
(let [connection (seon.operator/connection "default") database @connection attrs [:seon.fn/calls :seon.fn/sym :seon.ns/name :seon.fn/call-arities :seon.fn/arities :seon.test/reach :seon.agent/id :seon.agent/archived-tx :seon.message/about :seon.message/from :seon.eval/origin :seon.cluster.eval/refreshes :seon.issue/status :seon.issue/resolved-tx] schema (:schema database)] {:study/basis (:max-tx database) :study/commit (get-in database [:meta :datahike/commit-id]) :study/attributes (mapv (fn [a] {:study/attribute a :study/schema (select-keys (get schema a) [:db/valueType :db/cardinality :db/unique :db/index :db/isComponent :db/noHistory]) :study/count (if (get schema a) (let [ds (seon.db/datoms database :aevt a)] (if (map? ds) ds (count ds))) :study/not-installed)}) attrs)})
```

### Probe 2

```clojure
(let [prior *1 connection (seon.operator/connection "default") database @connection ds (fn [a] (if (get (:schema database) a) (seon.db/datoms database :aevt a) [])) reach (ds :seon.test/reach) arguments (ds :seon.fn.arity/arguments) arities (ds :seon.fn.arity/min) status (ds :seon.issue/status) result {:study/prior prior :study/basis (:max-tx database) :study/reach (if (map? reach) reach {:study/member-count (count reach) :study/largest (first (sort-by (comp - val) (frequencies (map :e reach))))}) :study/zero-arity (when-not (or (map? arities) (map? arguments)) (let [with-args (set (map :e arguments))] (first (for [d arities :when (and (zero? (:v d)) (not (with-args (:e d))))] (select-keys d [:e :a :v]))))) :study/issue-states (if (map? status) status (frequencies (map :v status))) :study/required-components (mapv (fn [a] {:study/attribute a :study/sample (let [rows (ds a)] (if (map? rows) rows (some-> (first rows) (select-keys [:e :a :v]))))}) [:seon.agent/runtime :seon.fn/arities :seon.fn/call-arities :seon.eval/origin :seon.message/about :seon.message/from])}] (prn result) :study/printed)
```

Selected stdout, preserving unknowns rather than reporting them as zero:

```clojure
{:study/basis 536871223
 :study/commit #uuid "6aab5437-8aa2-50d6-9c84-4cb450534fac"
 :study/counts
 {:seon.fn/calls 65824
  :seon.ns/name 455
  :seon.fn/call-arities 57926
  :seon.fn/arities 1522
  :seon.test/reach 102689
  :seon.message/about 1
  :seon.message/from 1
  :seon.eval/origin 0
  :seon.cluster.eval/refreshes 0
  :seon.issue/status 1766
  :seon.issue/resolved-tx 0}
 :study/unknown-counts
 {:seon.fn/sym :seon.schema/missing-projection
  :seon.agent/id :seon.schema/missing-projection}
 :study/not-installed [:seon.agent/archived-tx]
 :study/issue-states {:open 322 :resolved 1173 :superseded 271}
 :study/largest-reach [20421 1000]
 :study/zero-arity {:e 22527 :a :seon.fn.arity/min :v 0}
 :study/samples
 [{:e 48636 :a :seon.agent/runtime :v 48639}
  {:e 1674 :a :seon.fn/arities :v 20538}
  {:e 1674 :a :seon.fn/call-arities :v ["seon.run/wait" 1]}
  {:e 50216 :a :seon.message/about :v 50213}
  {:e 49969 :a :seon.message/from :v 48636}]}
```

This last map is a compact transcription of selected stdout, not the exact
tool envelope. The envelopes' configuration/rendering failure remains part
of the evidence boundary above.
