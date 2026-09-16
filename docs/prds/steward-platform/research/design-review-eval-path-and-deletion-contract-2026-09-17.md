---
type: research
status: active
date: 2026-09-17
tags: [evaluation, datahike, deletion, schema]
---

# Evaluation path and deletion contract: design review

**Verdict: approve the shared evaluation transitions, with changes; recommend
that every non-component ref means a living relation, without a new property.
Neither design is ready to implement exactly as written.**

## Defects first

1. **The proposed “pure evaluate” is not pure.** The actual evaluator binds a
   connection and effect context, invokes SCI, and changes its context.
   Computing every system result before minting its turn is consequently not
   justified merely by handing evaluation a database snapshot. An effect request
   needs its turn before dispatch; writes stamp the evaluation identity.
   One evaluation path does not imply one commit for arbitrary execution
   (`src/seon/sci/eval.clj:2313–2413`;
   `src/seon/effect.clj:755–786`; `src/seon/db.clj:2689–2697`).
2. **Duplicate refusal is not identical-request idempotence.** The existing
   completed-recording writer accepts identical content without changing facts;
   the proposed opening/settlement fences refuse an existing identity or
   terminal evaluation. A lost acknowledgement after a successful commit is a
   concrete difference, even though neither permits duplicate outcomes
   (`src/seon/turn.clj:1590–1594,1654–1657,393–397,1801–1805`).
3. **“No observed effects” does not prove “could not have effects.”** Current
   read classification is retrospective; the analyzer's writes are syntactic
   attribute observations, not a complete effect analysis. Recovery cannot use
   either to justify discarding an unfinished evaluation
   (`src/seon/turn.clj:2096–2110`; `src/seon/fn.clj:460–482,859–924`).
4. **The deletion decision needs the final report, not an ordinary intermediate
   transaction call.** Otherwise deleting target then referrer differs from
   deleting referrer then target. Datahike sweeps incoming refs before the
   final database exists; inspect their original meaning in `:db-before`
   and survival in `:db-after`
   (`reference-code/datahike/src/datahike/db/transaction.cljc:998–1014,1250–1276`).
5. **N8, N10 and N11 cannot land as proposed.** The observed listener watches a
   living issue; effect rows do not already preserve the dispatched handler
   symbol; test-failure construction discards the original reported path.
   N5 also covers executable success obligations, not just note citations.
   Writer-by-writer evidence is below
   (`src/seon/issue.clj:936–952,1004–1026`;
   `src/seon/effect.clj:249–278,761–786`;
   `src/seon/test/runner.clj:2158–2172`).
6. **N9's “no re-validation” claim is stale.** The final-report validator already
   checks affected surviving entities, including identities they had before
   retraction. That can catch a swept required key on a selected entity.
   Optional ref loss still passes shape validation. Neither “every deletion
   silently loses data” nor “whole-entity validation solves deletion” is accurate
   (`src/seon/db.clj:2958–3041`;
   `docs/prds/steward-platform/research/write-admission-2026-09-17.md:719–725`).
7. **Before/after absence alone misses delete-and-recreate.** A second successful
   probe retracted target 1 and reasserted its identity in the same transaction:
   the target survived, its incoming ref did not. The report must carry the
   expanded deletion targets to enforce the literal operation-level rule;
   a set of only finally absent entities is insufficient
   (`reference-code/datahike/src/datahike/db/transaction.cljc:998–1014`;
   reproducible second probe below).

## Evidence boundary and dependency ledger

This is a documentation-only review. Read AGENTS.md §§0–3 and
`.claude/skills/datahike/SKILL.md` in full; read each of these named
authorities end to end:

- [One evaluation path](one-evaluation-path-design-2026-09-16.md).
- [Evaluation writers and retired identities](evaluation-write-path-and-retired-identities-2026-09-16.md).
- [Schema design review](schema-design-review-2026-09-17.md).
- [Program facts PRD](../plan/program-facts-are-the-runtime-prd-2026-09-17.md).
- [Write admission](write-admission-2026-09-17.md).

Also read the data-oriented Clojure, data-modeling, REPL and testing skills,
the program roadmap entries, and the turn PRD §§13–15. First-party code
citations below refer to **`0c7711e590397182c5860326c6599a6ad7848416`**,
read with `git show`, not concurrent working-tree implementations. Document
citations refer to the documents reviewed. The reader's actual filename is
`src/seon/sci/reader.cljc`.

| Dependency and pinned checkout | Mechanism read | Existing first-party seam |
|---|---|---|
| Datahike `73afe78271a289861da236c5ac3457e64349653f` | Ordered transaction expansion, component/incoming-ref retraction, final-report callback; `reference-code/datahike/src/datahike/db/transaction.cljc:831–834,998–1014,1153–1154,1206–1335` | `src/seon/turn.clj:595–657,1733–1813`; `src/seon/db.clj:3009–3050,3171–3179` |
| Same Datahike checkout | Immutable transaction probe; writer refusal before commit; automatic ref indexing; `reference-code/datahike/src/datahike/core.cljc:126–140`, `writer.cljc:147–183,201–218`, `db/utils.cljc:307–312` | Existing report validator and error extraction, `src/seon/db.clj:3193–3205` |
| SCI `fcbd8862800e638dc0f8f5521111f999279cbcd2` | Context, fork and intern semantics, `reference-code/sci/src/sci/core.cljc:260,331,345` | Strict reader context and real execution, `src/seon/sci/eval.clj:459–508,2206–2569` |

The default JVM and MCP status answered. Three JVM evaluations used only local,
immutable, storeless Datahike values; no cluster connection was acquired.
The first exposed the dependency's default `:read` schema mode refusing
typed scalar declarations; the second explicitly selected `:write` and
returned the expected results; the third verified delete-and-recreate.
Exact successful forms and observations are
recorded below. This proves dependency behavior, not an implemented Seon
deletion contract or end-to-end system-turn equivalence.

## Design 1: verdict and objections

### (a) Four fenced transitions can compose in one transaction

**Confirmed, provided they are separate ordered `:db.fn/call` operations.**
The dependency implements the reduction as a `loop/recur`, carrying the
transaction report. Each iteration binds `db` from that report's current
`:db-after`; calling a transaction function passes this value. Its returned
operations are prepended to the remaining operations and fully applied before
the next call. Therefore opening becomes visible to planning, and planning's
minted evaluations become visible to settlement, within the same commit
(`reference-code/datahike/src/datahike/db/transaction.cljc:1153–1154,1250–1257,1288–1325`).

The concrete sequence is opening, planning, **one settlement per evaluation**,
then closing. “Four” names transition families, not a fixed count of four
operations. Preserve the open-agent/id checks; plan's frozen-reply, `:call`
situation and namespace checks; and settlement's open-turn, row existence,
turn/ordinal and terminal-presence checks
(`src/seon/turn.clj:393–406,620–657,1787–1813`).
Concatenating four ordinary function results all computed against the original
database would not provide this guarantee.

A later refusal rejects the transaction, not just its tail: the report is
validated before the writer queues a successful commit. No durable
“half-planned system turn” exists from a crash midway through this one
transaction. External effects performed before it are outside that guarantee
(`reference-code/datahike/src/datahike/db/transaction.cljc:1267–1276`;
`reference-code/datahike/src/datahike/writer.cljc:147–183,201–218`).
Transaction functions must remain pure, including through tempid retry
(`reference-code/datahike/src/datahike/db/transaction.cljc:1300–1321`).

### (b) The identity fence does not replace the whole retry contract

| Crash/retry boundary | Consequence |
|---|---|
| Before the single commit becomes durable | None of its rows are committed. Recovery cannot discover a partially minted system turn from that transaction. |
| After commit, before the caller receives success | All rows exist. Retrying identical cached content currently returns no additional recording datoms; the replacement opening call refuses. |
| A different payload reuses the identity | Current content comparison reports a conflict; the proposed fence rejects the identity without determining content equality. |
| Restart with lost result objects | Observe committed facts; do not re-evaluate merely to manufacture a retry payload. Recovery closes unfinished durable work and never resumes it. |

Grounding: `src/seon/turn.clj:1554–1561,1638–1672,1824–1845`;
`docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md:1005–1029`.

The actual system-turn caller also has a separate, essential fence:
`latest-evaluations` at commit must equal the history against which it
computed results. Its stale request becomes a no-op before completed recording
is invoked. Preserve this protection against another system pass or compaction;
a turn identity alone does not express it (`src/seon/turn.clj:2361–2370`).

Recommendation: share transitions and remove the second row-writing
implementation, but preserve identical completed-request acceptance with a
**pure equality check at the existing transaction admission boundary** until
callers explicitly adopt strict “already recorded; observe its outcome”
semantics. Never translate every duplicate-id refusal to success: a changed
payload must remain a conflict. Removing the equality contract is an API
change, not a simplification proved equivalent by identity uniqueness.

### (c) The row constructor is already pure

`receipt-row` takes a turn ref and request, derives identity from logical
turn id plus ordinal, and constructs data without querying a database
(`src/seon/turn.clj:912–932,523–530`). `record-evaluated-call` already calls
it (`:1668`); the inline refresh constructor is the material duplicate
(`:815–871`).

Keep one pure constructor over **logical turn id, turn ref, ordinal and
evaluation/source facts**. The proposed signature needs the logical id as
well as a numeric eid or tempid; an eid alone is not stable identity.
Tempids and lookup refs are ordinary input values, resolved when Datahike
applies the data. Resolving namespaces/origins and checking eligibility remain
writer decisions (`src/seon/turn.clj:1618–1636`;
`reference-code/datahike/src/datahike/db/transaction.cljc:1288–1321`).

Reuse `evaluation-facts`, terminal assertions and read-evidence assembly as
pure projections; do not move connection access into the constructor or
assert completed output in the minting operation
(`src/seon/turn.clj:89–125,1506–1533`).

### (d) One reader, legitimately different resolution phases

There is already one reader implementation. The three turn call sites serve
different purposes:

| Caller | Current context and verdict |
|---|---|
| `source-events` / `source-key` | Reads with a namespace but no SCI aliases. Treating its form vectors as semantic equality is unsafe for aliases or error events; use successful, correctly resolved forms or explicit source identity (`src/seon/turn.clj:2007–2018`). |
| `evaluable-source?` | Deferred resolution checks whether source contains candidate evaluations. Segmentation must tolerate aliases not established yet (`src/seon/turn.clj:2450–2455`). |
| Reply `read-source` | Deferred resolution segments/repairs the whole reply before its forms run. A preceding form may establish the aliases needed by a later form (`src/seon/turn.clj:3116–3128,3187–3205`). |

The reader's deferred fallback substitutes the alias itself when it lacks a
resolution. That is useful for segmentation, **not an executable or canonical
resolved meaning** (`src/seon/sci/reader.cljc:100–118`). Actual evaluation
supplies SCI bindings, aliases and refers, reads one form, and refuses errors
(`src/seon/sci/eval.clj:459–508`). Preserve strict execution after preceding
forms have changed the context. Unify preparation through explicit phase
inputs to the same reader, not one forced value of
`:defer-auto-resolve?`; never execute deferred forms. Static handling of
namespace forms does not replace live bindings
(`src/seon/sci/reader.cljc:447–499`).

### (e) Recovery needs positive evidence before it can discard a read

Neither proposed source of classification is sufficient today:

- Form analysis is consulted during settlement; its ordinary-expression result
  records known calls, not a complete exclusion of effects
  (`src/seon/turn.clj:971–1027`; `src/seon/fn.clj:859–924`).
- `:seon.fn/writes` observes declared keywords inside recognized write-call
  spans. It cannot exclude dynamic calls, interop, private state changes or
  effects whose target keyword is computed (`src/seon/fn.clj:460–482`).
- `read-only-evaluation?` requires recorded reads and absence of recorded
  writes/effects. Evidence is captured after evaluation; an unfinished form
  can lack it, or read before doing an effect. Absence is not a proof of
  impossibility (`src/seon/turn.clj:2096–2110,4680–4684`).

**Recommendation: retain every minted unfinished evaluation and settle it
interrupted.** Recovery's positive observation is that execution did not
durably settle, not that it definitely started or had an effect. Planning can
mint later, unexecuted forms too. Route its interruption through the same
terminal settlement transition while retaining recovery's missing/closed
turn no-op and completed-row preservation
(`src/seon/turn.clj:548–593,326–344,1824–1845`).

The owner's pure-read rollback refinement is implementable only with
**positive, sound pre-execution evidence**, durably tied to the exact form,
bindings and program basis. Unknown analysis means “could have effects.”
Empty call/write sets and post-execution evidence do not supply that
guarantee. This is additional analysis work, not a flag on today's recovery.

If such a proof is later admitted, retracting the unfinished row is compatible
with G1; it does not undo any computation and may leave an ordinal gap.
It also discards the interrupted attempt and removes a live identity fence:
`current-receipt` reads current rows, not historical identities
(`src/seon/turn.clj:873–880`). Closure must still prevent replay, and the
living-ref contract below must admit the retraction. Keeping an interrupted
read is smaller and more informative: add its terminal fact, preserve history,
and let ordinary compaction remove evaluations under its explicit contract
(`src/seon/turn.clj:2384–2419`).

### Improved design — one-page replacement

**Target.** One reader and real SCI evaluator; one pure source/evaluation
projection; one minting constructor; one fenced terminal transition. Tests use
these same owners with canonical database/SCI fixtures. Pure functions prepare
data; database access and execution remain explicit effects at their owners
(`src/seon/sci/eval.clj:459–508,2206–2569`;
`src/seon/turn.clj:89–125,912–932,1733–1813`).

**Preparation.** Segment through the shared reader with explicit resolution
phase. Freeze exact source, identity, ordinal, namespace and provenance.
Read each executable form strictly against its actual SCI context; feed its
real evaluation value to the existing pure settlement projection. Keep
presentation at its current render boundary, not in row construction
(`src/seon/sci/eval.clj:476–508,2549–2567`;
`src/seon/turn.clj:548–593`).

**Writes.** Reuse opening → planning → settlement(s) → closing as ordered
transaction operations, preserving each eligibility check. Shared functions
do not require identical commit boundaries: ordinary or possibly effectful
execution must commit its intent before it runs. Completed read results can
be recorded in one transaction only when their effect-free execution is
positively established. A snapshot argument and the current retrospective
read selector do not establish this. Until that guarantee exists, use durable
intent for unknown cases, including system forms
(`src/seon/turn.clj:4593–4598,4639–4667`;
`src/seon/effect.clj:755–786`).

**Retries and refresh.** Preserve the history-basis comparison and identical
completed-content acceptance at writer admission. All accepted writes then
use the same mint/settle transitions. For durable-intent execution, check the
history basis when admitting intent; later settlement uses the extant/open
turn and ordinal fences. Do not compare pre-intent history to history changed
by that same request's own planning. An unchanged read's basis/evidence
advance is a distinct metadata transition, not a second settlement: it must
check that the same latest evaluation still exists and preserve shown text
and outcome. Move today's bare updates behind that named writer decision;
never recreate a compacted evaluation
(`src/seon/turn.clj:2291–2306,2361–2370`).

**Recovery.** For an open turn, derive unfinished rows inside the writer, emit
ordinary interrupted settlements, interrupt pending effects, and close.
Preserve existing recovery closure semantics: ordinary `close-call` can mark
a trigger read, whereas current recovery only closes. Do not accidentally
introduce wake acknowledgement while sharing evaluation settlement. A shared
closing helper must carry that distinction explicitly
(`src/seon/turn.clj:418–437,1824–1845`). Pure-read retraction is deferred until
a positive pre-execution proof exists.

**Delete duplication, preserve behavior.** Remove completed-recording and
refresh row assembly after routing their writes through the shared functions;
retain append-prefix and source-order fences, generated provenance, zero-form
handling and system-turn wake semantics. Existing `settle!` is an effectful
orchestration/commit owner, not a reason to route every cached projection
through agent-only issue completion
(`src/seon/turn.clj:717–774,1638–1672,2354–2370,3798–3863`).

**Acceptance.** On real armed fixtures, compare committed facts and transaction
bases; reject a late invalid settlement without partial rows; verify identical
and conflicting retries; exercise alias introduction before later forms;
verify crash-before/after commit, interruption, compaction races, and a form
that reads then writes. Prove behaviors, not “exactly four function calls.”
The in-memory probe below establishes ordering only.

## Design 2: every non-component ref is living

### Recommendation and priced alternatives

**Use the schema we already have.** Components express ownership. Every other
ref expresses a relation requiring a living target. A mention that must
survive target deletion is a value of the target's identity type.
This adds no classification property and cannot silently exempt a newly
declared ref. Native ref and component properties already drive the
dependency's retraction logic
(`reference-code/datahike/src/datahike/db/transaction.cljc:831–834,998–1014`).

| Option | Guarantee | Cost and what it gives up |
|---|---|---|
| **Every non-component ref is living — recommended** | No existing non-component relation disappears through target deletion while its source survives. | One generic check, expanded-deletion evidence in the fork's report, conversion of historical name facts and coordinated deletion callers. Gives up silent sweep and atomic reparent-then-delete under the strict proposed rule. No extra property. |
| Exhaustive per-attribute deletion policy | Can express intentionally weak relations as well as restrictive ones, if **every** ref requires a declared policy and missing policy refuses. | Same report/index work plus declaration validation, policy maintenance and more semantic cases. Gives up a uniform guarantee where weak deletion is selected. An opt-in “protect this ref” flag is insufficient. |
| Existing whole-entity shape validation only | Selected surviving entities retain required schema fields. | Least new mechanism, but optional relations may vanish and listeners may broaden. Does not meet the requested living-relation contract (`src/seon/db.clj:2958–3041`). |

This is a behavior change requiring coordinated owners, even without a new
attribute. It does not authorize preserving deleted program identities as
tombstones. G1/G3 require those to go; value conversion and coherent deletion
must make that possible
(`docs/prds/steward-platform/plan/program-facts-are-the-runtime-prd-2026-09-17.md:242–260`).

### Concrete admission algorithm

**Owner:** extend `seon.db/write-report-error`, inside its existing
final-report callback. The proposed alternatives “fork final-report
validator” and “write-report-error” are already the same path:
`write-report-validator` captures the projection and calls it; `transact-call`
attaches that callback to the real writer transaction
(`src/seon/db.clj:3009–3050,3171–3179`). No second writer, pre-read,
trailing validator transaction function, or additional callback.

**One necessary fork refinement:** `retract-entity` must carry its resolved
target eid in an ephemeral report set (proposed
`:datahike/retracted-entities`), including calls expanded from transaction
functions and component cascades. Pass it to the existing final callback,
then remove it from the public report; never store it as tx metadata or a
database attribute. Tempid retries rebuild it with the attempt. This is
operation evidence, which neither before/after values nor removed ref datoms
can distinguish from deliberate unlinking. The existing report already
carries ephemeral attempted/effective-operation evidence
(`reference-code/datahike/src/datahike/db/transaction.cljc:585–617,998–1014,1206–1276,1300–1325`).

Proposed pure decision over the report:

1. Let **B** be `:db-before`, **A** be the completed `:db-after`.
   Reuse affected entity ids from attempted and effective datoms. Let **D**
   contain the union of **expanded deletion targets** and affected ids having
   EAVT datoms in B and none in A, restricted to targets that existed in B.
   This covers native deletion even followed by recreation, transaction-function
   expansion, component cascades and explicit retraction of all attributes.
   Do not infer deletion from only top-level `:db/retractEntity` syntax
   (`src/seon/db.clj:3012–3017`;
   `reference-code/datahike/src/datahike/db/transaction.cljc:813–834,998–1014`).
2. Derive the protected attribute set **R** from B's installed
   `:db/valueType :db.type/ref`, excluding `:db/isComponent true`.
   For each d in D and a in R, read B's incoming datoms `[s a d]`
   through AVET. Ref attributes are indexed automatically. B's declaration
   governs a pre-existing relation; changing schema in this transaction must
   not silently exempt it
   (`reference-code/datahike/src/datahike/db/utils.cljc:307–312`;
   `reference-code/datahike/src/datahike/db/transaction.cljc:1002–1012`).
3. Refuse if any such s still has entity datoms in A. Accept this relationship
   check when s is also wholly retracted, including through a cascade.
   Inspect A, not the intermediate call order. Use EAVT presence, not a
   numeric lookup's `:db/id` alone.
4. Independently validate final surviving **added or reasserted ref datoms**:
   their targets must exist in A. A missing numeric eid must not bypass a
   rule only missing lookup refs enforce. Restrict to assertions still
   present in A so temporary add-then-retract data is not falsely rejected.
   The report supplies attempted assertions even when idempotent; classify
   these refs using A's schema
   (`reference-code/datahike/src/datahike/db/transaction.cljc:1206–1216`;
   `reference-code/datahike/src/datahike/db/utils.cljc:113–138`).
5. Return the first ordinary flat diagnostic through the existing callback;
   do not throw out of the Seon API. The fork rejects the complete attempted
   report and Seon extracts that exact refusal
   (`reference-code/datahike/src/datahike/db/transaction.cljc:1206–1216`;
   `src/seon/db.clj:3193–3205`).

**Strict meaning matters:** B contains an incoming relation even if the caller
explicitly removes or reparents it earlier in the same transaction. Under the
review's literal “unless the referrer is retracted” rule, target deletion
still refuses if that source survives. That is deliberately stronger than
“no final dangling refs.” If atomic reparent-and-delete must be accepted,
the owner must change the rule or require operation provenance: final
absence alone cannot distinguish a deliberate unlink from Datahike's
automatic sweep. A prior committed unlink is a different before-state.

**Diagnostic contract, proposed:** name the operation, target eid and its
prior identity attributes, surviving referrer eid and identities, attribute,
original `[s a d]`, before basis, and expected condition (“target is not
retracted or referrer is also deleted”). Include enough evidence to identify
one concrete blocker; additional blockers can be queried from the same
basis. Never return success because a target/referrer lookup could not be
observed. Ordinary schema validation still checks partially stripped entities
(`src/seon/db.clj:2990–3006,3032–3041`).

**Boundary:** this validates Seon's admitted writes, not arbitrary direct
dependency bypasses. Existing old dangling refs need a one-time population
check/reset; incremental checking is not certification of every untouched
fact. Schema changes need equivalent validation of affected existing ref
populations. Components remain governed by native cascade plus the G5
parent-value validator, not an invented second component identity
(`reference-code/datahike/src/datahike/core.cljc:142–150`;
`docs/prds/steward-platform/plan/program-facts-are-the-runtime-prd-2026-09-17.md:269–274`).

### Cost and measured boundary

Let K be attempted/effective datoms, U affected entities including expanded
deletion targets, D distinct deletion targets that existed before, R protected
ref attributes, I incoming datoms visited, S distinct
referrers inspected, and A+ distinct final asserted ref targets.

The straightforward implementation adds **O(K + R) traversal, O(U + S + A+)
EAVT existence seeks, D × R AVET seeks, and O(I) returned datoms**;
the expanded deletion set costs O(D) temporary space. Cache
existence only within this immutable report. Index-seek costs depend on the
configured index and population. No full entity-graph scan or dependency
closure is required. Derive R only when needed for deletion; with D = 0
there are no incoming-ref seeks or protected-attribute enumeration. The native
deletion already performs one incoming search per installed ref attribute
per target, so this reference implementation repeats part of that work to
recover the before-state guarantee
(`reference-code/datahike/src/datahike/db/transaction.cljc:1001–1014`;
`src/seon/db.clj:3014–3015`).

An implementation may reduce repeated seeks by using removed ref datoms in
the report as candidates, intersecting with D and confirming against B.
This does not replace expanded-deletion evidence: the candidate datoms alone
do not distinguish deletion from deliberate unlinking. Prove equivalence
for explicit unlink, cascade, purge and repeated operations before taking that
optimization. Effective retractions are reported, including purge
(`reference-code/datahike/src/datahike/db/transaction.cljc:813–829`).
A policy property changes R, not the shape of the algorithm.

The probe had two application entities and one incoming ref: before count
**1**, after count **0**, surviving source **true**, both-deleted **true**.
The ordered-call observation and original-value preservation were both
**true**; final-report rejection named target **1**, source **2**.
The MCP reported **10 ms for the entire successful form**. This is a
semantic check, **not** a scalable latency estimate; no production cost
number is claimed. The delete-and-recreate probe reported 5 ms for its whole
form: target-present-after **true**, incoming-after **0**, with the three
application datoms recorded below.

### Consequences for current deletion paths

| Entity/path | Consequence and recommendation |
|---|---|
| Agent | A positive id datom already records creation; lack of a separate created-at is not lack of an event. Creation also attaches plan, settings and runtime (`src/seon/cluster/agent.clj:123–149`). Message from/to/inbox, issue authorship/assignment, namespace stewardship, note ownership and contribution agent refs prevent casual deletion while those rows survive (`src/seon/cluster/message.clj:263–267`; `src/seon/issue.clj:1025`; `src/seon/cluster/agent.clj:98–121`; `src/seon/note.clj:177–209`; `resources/seon/schemas/seon.context.contribution.edn:17–30`). Recommend keeping identity after normal deactivation; if a permanent closure lifecycle is wanted, record a positive closure fact and make activation/admission honor it. Do not introduce an agent retirement tombstone or silently cascade other agents' history. Physical deletion remains possible only with coherent removal of living relations. G1 names program entities/issues, not an unconditional agent-deletion API (`program-facts-are-the-runtime-prd-2026-09-17.md:242–247`). |
| Namespace with functions | `:seon.fn/ns` is real membership, not an observed name (`src/seon/fn.clj:644–648`). Namespace-only deletion refuses; coherent deletion must remove its functions/tests and other surviving dependents or detach them in an earlier transaction. Do not mark the child→namespace ref component: deleting a function would then delete its namespace. Program/name history needs value conversion where it must survive. Assigned agents also refer to the namespace (`src/seon/cluster/agent.clj:143–148`). |
| Turn with evaluations | Evaluations currently carry a required reverse `run` ref; the runtime's turns are components (`resources/seon/schemas/seon.eval.edn:12`; `resources/seon/schemas/seon.runtime.edn:3–6`). Smallest coherent deletion explicitly retracts evaluations and turn in one transaction, plus other dependents. If evaluations become components, ownership must run **turn→evaluations**, never evaluation→turn. That is a separate schema/reader change, not a property flip on `run`. |
| Issue whose note disappeared but history cites it | Current note reconciliation retracts the issue (`src/seon/issue.clj:398–408`). Preserve authored citations as typed values; retract issue-scoped listener entities in the same transaction, not merely their entity constraint (`:936–952`; runtime owns listeners at `resources/seon/schemas/seon.runtime.edn:8–9`). Historical generated evaluation origins can point to issues (`src/seon/turn.clj:2154–2158`): an origin that must outlive deletion needs an identity value, or that surviving evaluation blocks deletion. Recording loss in a docstring, as N12 suggests, contradicts the living-ref guarantee. G1 requires resolving this conflict without keeping the issue alive as a tombstone. |
| Evaluation compaction — additional rollout dependency | Current compaction retracts evaluations (`src/seon/turn.clj:2384–2419`), while transaction provenance stamps `:seon.db/receipt` refs and effects can point at evaluations (`src/seon/db.clj:2689–2697`; `src/seon/effect.clj:272–278`). Universal protection can therefore block compaction. Historical transaction/effect provenance that must survive is an identity value; do not cascade transaction entities or exempt those refs silently. N1–N11 is not a complete rollout inventory. |

### N1–N11: verify the writer, not the attribute name

| Finding | Writer evidence and verdict |
|---|---|
| **N1 references** | **Confirm name values.** Analyzer references become lookup refs to named functions; the referencing function does not own those declarations (`src/seon/fn.clj:624–632,667–670`). Store qualified symbols and query target existence separately. |
| **N2 writes** | **Confirm attribute-name values; reject “same fact” as keywords.** The writer selects declared keyword occurrences within recognized write spans, then maps them to schema lookup refs (`src/seon/fn.clj:460–482,503–506,674`). This is a narrower syntactic observation than all keywords and not a guarantee of all effects. Preserve that meaning when storing keywords. |
| **N3 schema references** | **Confirm registry-name values.** The projection's reference graph supplies keys; population turns each into a schema lookup ref (`src/seon/schema.clj:2961–2986`). The declaring schema does not own the referenced schema. Preserve the actual declared key domain; a move from keyword identity to qualified-keyword alone also needs a domain proof. |
| **N4 arity input/output/guard refs** | **Confirm registry-name values.** Malli schema walks yield keys, which `schema-references` wraps in lookup refs; the arity writer stores them (`src/seon/program.cljc:456–460,692–736`). These are declared dependencies, not owned schema entities. |
| **N5 issue citations** | **Confirm for authored citations, qualify the blanket claim.** The note index resolves token spellings to existing eids (`src/seon/issue.clj:158–201,214–229`). But detector subjects and authored success-test obligations also populate these attributes (`:448–477,1004–1026,1044–1055`). Values can preserve those names, but missing obligated tests must remain an explicit incomplete/refused condition, never disappear or count as verified. Preserve retention constraints. Unclassified/ambiguous raw tokens cannot all become one typed family merely because resolution failed; retain that evidence until type is known. |
| **N6 renderer-fn** | **Confirm redundant ref.** `evaluation-facts` writes both symbol and lookup ref from the same renderer input (`src/seon/turn.clj:103–107`). Keep the symbol and remove redundant ref consumers, including recording comparison normalization (`:1580–1587`). |
| **N7 subject/pending-subject** | **Confirm one declared name.** Test metadata supplies the subject; settlement chooses resolved ref or pending symbol according to current target existence (`src/seon/fn.clj:508–514`; `src/seon/turn.clj:1347–1361`). Store the subject symbol once. Missing subject remains queryable and does not imply passing coverage. |
| **N8 listen/entity** | **Reject name classification for the actual issue writer.** It creates a scoped listener for one living issue's budget (`src/seon/issue.clj:936–952`). The matcher deliberately permits an absent entity constraint as an authored wildcard (`src/seon/cluster/wake.clj:411–423`). Keep the living ref; block deletion unless the scoped listener itself goes too. Universally refusing absent constraints breaks legitimate wildcard patterns. Watching a name across deletion/recreation is a different contract, not an equivalent retype. |
| **N9 scheduled function** | **Confirm handler name.** Portfolio declarations name a handler; the seed writer resolves that name to the function ref (`src/seon/schedule.clj:45–108`). Store its qualified symbol and diagnose absence when firing. Correct the review's claim that required-key loss bypasses revalidation: the final-report check already exists (`src/seon/db.clj:2958–3041`). |
| **N10 capability-fn** | **Split declaration from historical execution.** Function metadata already carries the handler symbol and creates the redundant lookup ref (`src/seon/fn.clj:585–609,694–702`). Removing that declaration ref would amend G2, which explicitly says to retain it (`program-facts-are-the-runtime-prd-2026-09-17.md:253–254`). The effect writer copies a handler eid from its owner; its open-request does **not** store the handler symbol (`src/seon/effect.clj:249–278,761–786`). Joining the owner's current symbol later loses execution-time provenance. Persist the exact admitted dispatch symbol before removing the historical ref. Also hand that decision through: dispatch resolves a handler at `:696–702` while the writer re-reads the owner at `:249–255`; those observations need not describe the same version. |
| **N11 failure file** | **Confirm path identity, falsify “raw survives.”** Failure construction unconditionally removes reported-file and line, then stores canonical file ref/line only for a recognized site (`src/seon/test/runner.clj:2158–2172`). A later portability fallback substitutes reported-file only when the canonical file is absent (`:2309–2322`). Populate durable canonical path/line values on the successful path before removing refs; preserve original reported text separately only if that distinction matters. Deleting the ref alone loses site evidence. |

Retypes above are proposed reset-boundary changes, not production edits or an
implicit amendment of G2. The implementation must audit every remaining ref's
writer and reader, including historical provenance outside N1–N11, before
universal enforcement is enabled. The simple invariant is worth keeping;
a protection property is not a substitute for completing that audit.

Implementation acceptance must include target-only refusal with unchanged
committed facts, deleting both endpoints in either order, nested transaction
functions and component cascades, delete-and-recreate, explicit unlink plus
delete, optional refs, and nonexistent numeric targets. Use the canonical
armed fixture and one generative test varying operation order; the tiny
dependency probes do not test Seon's future admission policy.

## Reproducible dependency probe

Executed once successfully through `mcp__seon__eval_clj`, JVM mode, cluster
`default`, `read_only: true`, 20,000 ms bound. It creates no connection,
store, namespace definition or file. This is intentionally a dependency
probe, not a substitute for the canonical Seon fixture.

```clojure
(let [schema {:review/id {:db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                      :review/ref {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
                      :review/seen {:db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}}
      empty (datahike.db/empty-db schema {:index :datahike.index/persistent-set :schema-flexibility :write})
      seed (datahike.core/with empty [{:db/id -1 :review/id "target"} {:db/id -2 :review/id "source" :review/ref -1}])
      before (:db-after seed)
      target (get (:tempids seed) -1)
      source (get (:tempids seed) -2)
      ordered (datahike.core/with before
                [[:db.fn/call (fn [_] [{:db/id -3 :review/id "planned"}])]
                 [:db.fn/call (fn [database]
                               (if (seq (datahike.core/q '[:find ?e :where [?e :review/id "planned"]] database))
                                 [[:db/add [:review/id "planned"] :review/seen true]]
                                 (throw (ex-info "Earlier row absent" {}))))]])
      deletion (datahike.core/with before [[:db/retractEntity target]])
      both (datahike.core/with before [[:db/retractEntity target] [:db/retractEntity source]])
      refusal (try
                (datahike.core/with before [[:db/retractEntity target]]
                  {:datahike/validate-report
                   (fn [{:keys [db-before db-after]}]
                     (when (and (seq (datahike.core/q '[:find ?s :in $ ?t :where [?s :review/ref ?t]] db-before target))
                                (seq (datahike.core/q '[:find ?s :in $ ?s :where [?s :review/id _]] db-after source)))
                       {:review/error :living-ref :review/target target :review/source source}))})
                :unexpected-success
                (catch clojure.lang.ExceptionInfo e (:datahike/validation-refusal (ex-data e))))]
  {:review/ordered-visible (boolean (seq (datahike.core/q '[:find ?e :where [?e :review/seen true]] (:db-after ordered))))
   :review/incoming-before (count (datahike.core/q '[:find ?s :in $ ?t :where [?s :review/ref ?t]] before target))
   :review/incoming-after (count (datahike.core/q '[:find ?s :in $ ?t :where [?s :review/ref ?t]] (:db-after deletion) target))
   :review/source-survives (boolean (seq (datahike.core/q '[:find ?s :in $ ?s :where [?s :review/id _]] (:db-after deletion) source)))
   :review/both-gone (empty? (datahike.core/q '[:find ?e :where [?e :review/id _]] (:db-after both)))
   :review/refusal refusal
   :review/original-unchanged (= 2 (count (datahike.core/q '[:find ?e :where [?e :review/id _]] before)))})
```

Successful value (the MCP JSON envelope renders keyword values as strings):

```clojure
{:review/both-gone true
 :review/incoming-after 0
 :review/incoming-before 1
 :review/ordered-visible true
 :review/original-unchanged true
 :review/refusal {:review/error :living-ref :review/source 2 :review/target 1}
 :review/source-survives true}
```

### Delete-and-recreate probe

This second successful form demonstrates why the final callback also needs
expanded deletion targets. Existing effective datoms preserve the remove/add
sequence, but contain no native-operation discriminator.

```clojure
(let [empty (datahike.db/empty-db
              {:review/id {:db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
               :review/ref {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}}
              {:index :datahike.index/persistent-set :schema-flexibility :write})
      seed (datahike.core/with empty [{:db/id -1 :review/id "target"} {:db/id -2 :review/id "source" :review/ref -1}])
      before (:db-after seed)
      target (get (:tempids seed) -1)
      revived (datahike.core/with before [[:db/retractEntity target] {:db/id target :review/id "target"}])]
  {:review/target-present-after (boolean (seq (datahike.core/q '[:find ?e :in $ ?e :where [?e :review/id _]] (:db-after revived) target)))
   :review/incoming-after (count (datahike.core/q '[:find ?s :in $ ?t :where [?s :review/ref ?t]] (:db-after revived) target))
   :review/operations (mapv (fn [d] [(:e d) (:a d) (:v d) (:added d)])
                       (filter #(#{:review/id :review/ref} (:a %)) (:tx-data revived)))})
```

Observed:

```clojure
{:review/target-present-after true
 :review/incoming-after 0
 :review/operations [[1 :review/id "target" false]
                     [2 :review/ref 1 false]
                     [1 :review/id "target" true]]}
```

## Landing and verification boundary

Only this review is owned by this assignment. Concurrent source/config/schema
and test edits are excluded from claims; no production file or foreign lane
was edited, probes never acquired a cluster connection, and no manual action
changed the default process lifecycle.
The proposed contracts remain **unimplemented**. Source proof and the
dependency probe establish their grounding; neither proves the future Seon
implementation.

File-specific `seon.dev.markdown/validate-file` returned
`{:seon.dev.markdown/valid? true :seon.dev.markdown/violations []}`.
The independent repository pin scan returned **30 errors, all outside this
file**. For example,
`docs/prds/context-generation/research/agents-md-audit-2026-09-15.md:226`
cites `be0c498302d805be130fb02b934dbd866dd44b29` for Datahike while the
selected gitlink is `73afe78271a289861da236c5ac3457e64349653f`.
The already documented checker class is
[the dependency-pin issue](../../../seon/issues/archive/datahike-current-pin-statements-drifted-again.md);
its resolution explains the whole-repository scan and historical-citation
convention. These unrelated findings were not repaired by this review.
Cold gate `bin/test --paths
docs/prds/steward-platform/research/design-review-eval-path-and-deletion-contract-2026-09-17.md
-- seon.datahike-fork-test` used HEAD
`0d3756246b4486ae424110ca6f770f008a29c03b` plus only this document.
It exited **1: 2 tests, 9 assertions, 2 failures, 0 errors**.
The failing test was
`seon.datahike-fork-test/schema-deletion-reads-earlier-declarations-in-one-transaction`.
Its assertions at `test/seon/datahike_fork_test.clj:86,90` expected a
successful transaction and the identity-only row
`{:seon.schema/key :turn-schema-cache/value}`. The writer instead returned:

```clojure
{:seon.error/kind :seon.db/invalid-write
 :seon.db/attribute :seon.schema.admission/source
 :seon.db/entity #:seon.schema{:key :turn-schema-cache/value}
 :seon.db/path [48052 :seon.schema.admission/source]}
```

The full diagnostic identified the resulting identity-only row and required
`:seon.schema.admission/source` as `[:enum :core :agent]`.
The within-transaction observation assertions did pass; the failure was final
admission and the stale retained-row expectation, not transaction ordering.
This is the exact foreign verification boundary, already in committed source;
no attribution to an uncommitted edit or another agent is needed.
G1/G3's deletion work owns removal of retained-identity behavior
(`program-facts-are-the-runtime-prd-2026-09-17.md:242–260`).
The existing
[retained-identity issue](../../../seon/issues/retained-identities-have-no-declared-retirement-state.md)
records the class; its competing retirement proposal is not this review's
recommendation. This assignment changes neither that protected note nor the
implementation/test to make this gate green.

Platform gate `bin/test --paths
docs/prds/steward-platform/research/design-review-eval-path-and-deletion-contract-2026-09-17.md
--platform` used HEAD `68e95b0293d0e74dc6f569743afd7c37102e469d`
plus only this document. Publication completed, but the coordinator exited
**1 before running tests**:

```text
Execution error (ClassCastException) at seon.test.runner/fn (runner.clj:724).
class clojure.lang.PersistentVector cannot be cast to class java.util.concurrent.Future
```

The trace enters `bare-namespaces` (`src/seon/test/runner.clj:750`) through
the delayed `@seon.fn/source-roots` read at `:724`, before coordinator
selection (`:3789`, all at that gate's pinned HEAD).
The same location is already recorded in
[the working edge](../plan/unsettled.md) at line 1302.
Neither gate is reported green. No production repair is part of this
design-only assignment. Both completed run roots were checked for live
holders and removed; no scratch worktree or owned background shell remains.
The shared published-base caches were left to their owner.
