---
type: research
status: active
tags: [prd, research, plan, quarry]
---

# Plan code archaeology

## Executive finding

The owner's memory is correct. The deleted implementation had a real,
well-tested plan system. By its final quarry revision it was 4,012 source lines
across `src-old/my/plan.cljc`, `src-old/my/plan/internal.cljc`, and
`src-old/my/plan/generation.cljc`, with 1,701 lines and 53 tests in
`test-old/my/plan_test.cljs`. It stored per-agent plan trees, dependency edges,
stable identities, status and outcome facts; derived readiness, blockage,
roll-up, focus, and bounded views; and accepted an edited whole-plan document
whose identity-preserving delta was applied in one transaction. The pure graph
and reconciliation kernel was thoughtful code. The namespace later accreted
generated-code scheduling, failure escalation, remote acquisition, rendering,
and database choreography until the good kernel sat inside an overlarge
mechanism.

Ruling 49's current outcome remains unambiguous: the fresh system is to have one
derived-first todo, not a newly ported `my.plan`. Its historical wording is not
accurate without a scope qualifier. “The my.plan toolkit (never implemented)”
and “my.plan is retired unbuilt” mean **never implemented in the fresh CLJ
tree**. They cannot mean never implemented in this repository. The active
roadmap already carries the more accurate wording: “my.plan was NEVER ported
(quarry only)”
([plan README](../plan/README.md#L1396)).

The fresh tree also contains plan code, but for a different subject. A run's
`plan-tx` freezes an ordered list of reply forms behind an absence-to-digest
transaction fence. It has no tasks, dependency edges, update document, or
semantic diff. The deleted `seon.program.plan/plan-execution` was different
again: it derived execution placement and manifests from program-graph edges.
Conflating these three meanings is the main risk this archaeology resolves.

This is quarry evidence, not a todo design.

## Scope, authorities, and method

I read the two named authorities end to end:

- [self-generating context PRD](../plan/self-generating-context-prd-2026-08-11.md),
  especially rulings 47 and 49; and
- [rebirth systems sweep](rebirth-systems-sweep-2026-08-12.md), especially the
  Todo, `my.plan`, and agent-memory rows.

I also read the final deleted `my.plan`, `my.plan.internal`, and
`my.plan.generation` namespaces end to end at the parent of the quarry deletion,
`9e44815f577b4cfda876e49183b7f6ac49bcacf2` (`099cdfa99^`), and sampled the
tests at the graph, compiler, reconciliation, and generated-plan seams. The
requested history searches were run across all refs. Rename history was
followed through the pre-split `src/` paths; otherwise `git log` on only
`src-old/` misleadingly shows merely the tree split and deletion.

The live/current comparison read [run.clj](../../../../src/seon/cluster/run.clj),
[loop.clj](../../../../src/seon/cluster/loop.clj),
[reply.clj](../../../../src/seon/cluster/reply.clj), the
[run schema](../../../../resources/seon/schemas/seon.cluster.run.edn),
[form schema](../../../../resources/seon/schemas/seon.cluster.run.form.edn),
and [focused run tests](../../../../test/seon/cluster/run_test.clj). No runtime
probe was needed to establish these code shapes. The Seon runtime MCP tools
were unavailable to this lane; the static and historical evidence is therefore
named explicitly rather than presented as a live-system proof.

Historical source references below use reproducible Git object notation, for
example:

```text
git show 9e44815f577b4cfda876e49183b7f6ac49bcacf2:src-old/my/plan.cljc
```

Prior quarry reports independently found the same broad shape. The
[transcript-aging quarry](transcript-aging-quarry-2026-07-29.md) records that
`my.plan` existed in State A but was never redesigned or ported into the fresh
system. The [agent-tools quarry](agent-tools-quarry-2026-08-03.md) recommends
keeping intent, dependencies, roll-up, document, and reconcile while deleting
the generated orchestration and duplicate rendering. The earlier
[quarry gold inventory](quarry-gold-inventory-2026-07-28.md) counted the
namespace and called it unmined fresh-tree work, not nonexistent history.

## Name map: four different meanings of “plan”

| Meaning | Identity and era | What it tracked | How it changed |
|---|---|---|---|
| Agent task graph | `seon.agent.todo`, 2026-06-10 through 2026-07-02; renamed behavior-preservingly to `my.plan` at `51a8cab8b`; deleted from the working tree at `099cdfa99` | Authored intent, tree parent, DAG dependency, agent/from/message refs, goal/expected outcome/pace, statuses and completion evidence | Initially item verbs and whole-tree creation; from `2bbaf29b1`, an edited document compiled to identity-preserving add/update/drop transaction data and a counted diff |
| Run form plan | Old `seon.agent.driver/plan-tx-data`, fresh [seon.cluster.run/plan-tx](../../../../src/seon/cluster/run.clj#L548) and transaction function `plan-call`; fresh lineage begins at `4ac6ea9deb`/`c65ddeedaf` in late July | Exact ordered Clojure source forms for one run, their authors and parse-time namespaces, plus a content digest saying the reply is frozen | Installed once by an absent-to-digest fence; never reconciled or semantically diffed; forms settle serially by ordinal |
| Generated opening prefix | Fresh [append-generated-call](../../../../src/seon/cluster/run.clj#L747), added in the 2026-08-12 sequence `53ee9c472` → `7d036203e` → `18019f218` | A system-authored ordered prefix of generated forms and terminal receipt evidence for the preceding ordinal | Appends exactly the next ordinal after the prior receipt is terminal; deliberately has no plan digest until the later model reply freezes its own form plan |
| Execution placement plan | Deleted `seon.program.plan/plan-execution`, `f3ddfb0bb` through deletion `c45616a38`, 2026-07-23–25 | Reachable program edges, eligible/selected execution tiers, schema/capability manifests, unresolved edges, and basis/commit/graph/schema fences | Re-derived from an immutable database projection; not authored, updated, or diff-applied |

Those are the semantic mechanisms relevant to the question. The search also
found honest uses of “plan” that are not agent task systems:

| Mechanism | Era and useful refs | What it tracked | Update/diff behavior |
|---|---|---|---|
| [seon.reconcile/plan](../../../../src/seon/reconcile.cljc#L315) | Fresh, introduced at `8c7cc99bc`, current | A desired identity-bearing population against current database facts and managing provenance | Purely derives exact convergent transaction data; empty vector is a no-op; not an agent-facing edit document |
| [call-preparation plans](../../../../src/seon/call_preparation.clj#L651) | Fresh, `2a12b95ed` through current | One function's arities and fact-derived supplied-default insertions, fenced by contract and database bases | Recompiled/replaced in a one-entry-per-function cache when its facts change; no domain diff |
| [render root pull plan](../../../../src/seon/render/walk.clj#L311) and Datahike read plans | Fresh render acquisition from `1a499558a`, retained compiled plan at `789869750`; current `seon.db` read evidence | Schema-generation pull instructions and the dependency revision of database reads | Cache/rederive by projection/schema/fit identity; not durable task state |
| [source file-change plan](../../../../src/seon/fn.clj#L1214) | Fresh, `995ccec92` through current | Whether a changed file admits safe same-identity program-row upserts or requires a full rebuild | Returns classification and exact upsert/rebuild data; no task graph |
| Restore plan | State A, immutable intent from `40b9365fa`, exact confirmation from `55271a82f`, deleted at `099cdfa99` | Restore/undo branch heads, artifact and protocol identity, consumer generations, confirmation, next operator command | Re-derived and digest-validated from immutable intent and observed completion facts; no in-place edit |
| Package install/remove plans | State A, `19654064b` through deletion `099cdfa99` | One admitted package-ledger transition and associated program rows | Pure convergent transaction data with explicit no-op; not task intent |
| Initialization page plan | State A, `9a885319f` through deletion `099cdfa99` | Exact ordered precomputed source-initialization pages bound to artifact/config digests | Rebuilt as a whole artifact and digest-verified on load; not semantically diffed |
| Local implementation plans | Current HEAD or final quarry parent `099cdfa99^` | `path-plan`, query/read plans, `claim-plan`, page-layout plans, and `plan-settlement` each describe one local computation | Recomputed from their inputs; lexical matches only |

They supply useful implementation idioms—pure derivation, stable input
identity, no-op convergence, and fences—but none stores an agent's intentions
or creates a competing task system. The retired `:plan-ledger` was not another
data model either: it was a duplicate context presentation contract absorbed
into the one `:plan` block at `950473e52`.

## The task-plan lineage actually built

### From small todo to dependency-aware plan

The lineage began at `e7b7e9fa0` as `seon.agent.todo`: durable open/done work
items scoped to an agent, with `add!`, `complete!`, `reopen!`, and `list-open`.
Commit `52c31dd87` made it hierarchical and dependency-aware. Its namespace
docstring explicitly said that a parent ref made the list a plan and a
`depends-on` ref sequenced work. It added:

- a tree edge from a node to its parent;
- cardinality-many dependency edges;
- Datalog rules for descendant, leaf, open-work, blocked, and ready;
- `plan!` for atomic whole-tree authoring with labels and `:after` references;
- `depends!`, `move!`, `drop!`, `next`, `tree`, and derived `status`; and
- tests for hierarchy, dependencies, readiness, and roll-up.

The rename at `51a8cab8b` was atomic and behavior-preserving. It is not a
second mechanism. Commit `1cda29489` then sharpened the model as `my.plan`:
dependencies, a current-position anchor, and a bounded render. The final
namespace described itself accurately:

```clojure
(ns my.plan
  "Maintain an agent's durable dependency-aware plan graph.

   This namespace is the public planning surface for creating and reconciling
   plan trees, selecting active work, recording dependencies and outcomes, and
   deriving readiness, blockage, progress, and bounded context views. Plans are
   database facts scoped to one agent, never transcript or todo-list state.")
```

At `099cdfa99^:src-old/my/plan.cljc:24-43`, its declared facts included
identity, title, description, stored `:open/:active/:done/:blocked` status,
timestamps, agent/from/message/namespace/claim refs, parent and needs refs,
goal, expected result, and pace. At
`099cdfa99^:src-old/my/plan/internal.cljc:24-46`, one readable Datalog rule set
derived the important graph semantics:

```clojure
[(blocked ?t) [?t :my.plan/status :blocked]]
[(blocked ?t) [?t :my.plan/needs ?d] (open-work ?d)]
[(ready ?t) [?t :my.plan/status :open] (leaf ?t) (not (blocked ?t))]
[(ready ?t) [?t :my.plan/status :open] (not (leaf ?t))
 (not (open-work ?t)) (not (blocked ?t))]
```

The last rule is a good detail: after every descendant leaf is done, the
non-leaf becomes ready for its own verify-and-close action. A plan was not
declared complete merely because there were no ready leaves.

### Whole-document reconciliation was real

Commit `2bbaf29b1` added the strongest mechanism in the quarry. `document`
returned nested ordinary EDN with stable IDs and inline dependency IDs.
`reconcile!` accepted that same shape after editing. Its contract was precise:

- a node carrying an ID updated in place;
- an omitted ID resolved only when identity was unambiguous—one open root, or
  one title-identical open sibling;
- ambiguity failed while naming candidate IDs instead of silently reminting;
- a genuinely new node received a new identity;
- an open node absent from the document was retracted;
- completed nodes were immune to omission and could not be rewritten through
  reconcile;
- scalar changes, parent replacement, and exact dependency-edge additions and
  retractions were flattened into one transaction; and
- the result reported `:my.plan/added`, `/dropped`, and `/updated` counts.

The essential compiler shape at
`099cdfa99^:src-old/my/plan/internal.cljc:530-729` was pure:

```clojure
;; desired/current identity resolution precedes transaction compilation
{::transaction-data tx
 ::allocation-keys allocation-keys
 ::labels labels
 ::root-id root-id
 ::diff {:my.plan/added   (count news)
         :my.plan/dropped (count drops)
         :my.plan/updated (count updates)}}
```

Authoring used the same compiler against an empty baseline. A document produced
from current rows round-tripped to empty transaction data and a zero diff. The
tests proved all of these cases, including one transaction containing one add,
one update, and one drop; protection of completed and foreign identities;
id-less root preservation; ambiguous id-less child refusal; malformed
dependency refusal; and duplicate identity refusal
(`099cdfa99^:test-old/my/plan_test.cljs:1538-1654`). This was not a doc-only
aspiration.

The update experience was genuinely good: an agent could pull one readable
value, edit it as data, and submit it without reissuing a sequence of mutation
verbs or losing task identity. The compiler's separation from acquisition and
rendering also made most semantics testable over immutable row vectors.

### Bounded focus and rendering

The plan derived one active/ready anchor, its ancestor chain, a small ready
frontier, roll-up counts, and bounded recent completion context. AI and HTML
faces shared the same derived value rather than independently deciding plan
state. This addressed the real rebirth problem later stated by ruling 47:
current intent survived as facts and could be compactly rendered without
replaying the transcript.

The limits were hand-tuned State A constants (frontier seven, recent-done five),
and the current architecture now has one render-profile/fit owner. The lesson
is the bounded, identity-preserving projection, not those numbers or another
custom output-fitting path.

### Generated namespace plans were a specialization

The July 19 sequence `02324bcb8`, `8065ba425`, and `626632834` reused the plan
graph as generated-code machinery. Parsed namespace require edges became
`:my.plan/needs`; namespace leaves were claimed; terminal eval evidence marked
them done; scheduler state and assignments were derived; and publishing a new
projection reconciled the namespace DAG idempotently. Completed leaves were
preserved while removed unfinished leaves were dropped. The tests prove the
edge, idempotence, preserve-done, and drop-unfinished behavior
(`099cdfa99^:test-old/my/plan_test.cljs:1217-1276`).

This is impressive reuse but poor ownership for the todo. It made a generic
intent graph carry a generated-code coordinator and helped grow the owner past
4,000 lines. Fresh Seon already has a different generation mechanism: a
dependency-ready generated form is appended to a run only after the preceding
receipt is terminal. Program dependencies live in program-graph facts. Neither
belongs in authored todo facts merely because the old scheduler used the plan
graph.

### Failure escalation was accreted, then became ballast

Commit `8492b275b` derived repeated failure at the active frontier, selected a
planner, sent a once-per-episode escalation message, and accepted a revised
plan. It was carefully tested, but it coupled task representation to provider
selection, message delivery, failure-query budgets, and generated-code
coordination. Later fixes added remote acquisition and cause-query bounds.

This is the clearest negative lesson. A todo should expose durable obligation
and evidence. The run loop, problem routing, provider descriptor rows, and
subagent/message owners should decide what action follows failure. Rebuilding
escalation inside todo would restore the deleted central coordinator under a
friendlier name.

## Quality assessment

### `my.plan`: strong kernel, overgrown owner

**Pure planning and reconciliation kernel: A-.** The data model was explicit;
tree and DAG edges had different meanings; readiness and roll-up were derived;
identity ambiguity failed loudly; authoring and reconciliation converged on one
compiler; a no-op edit issued no transaction; the diff was ordinary data; and
the test suite attacked the dangerous cases. The code comments and docstrings
were unusually clear about create versus update and about the exact semantics
of an omitted node.

The minus matters. Stored `:active`, `:blocked`, and `:done` mixed assertions
with derivable state and do not fit the fresh system's presence/absence and
derive-don't-store laws. Some maps were closed, as was conventional in that
era. Title-based recovery for omitted IDs was humane but expensive and could
only refuse ambiguity, never make identity inference intrinsically safe. The
compiler previewed once before ID allocation and compiled again inside the
allocation transaction builder, with “compilation changed” treated as a core
throw; that is more machinery than an authored todo should need.

**Whole owner: C+.** Four thousand source lines plus 1,701 test lines is too
large for the concept. Public task operations, writer acquisition, allocation,
generated namespace scheduling, provider-aware escalation, message emission,
AI rendering, HTML rendering, and compatibility scaffolding lived together.
Commit `99c5046bf` did delete a duplicate Markdown reconciliation path—good
dissolution—but the surviving EDN path remained embedded in a broad async
CLJS/pod mechanism. This matches the prior quarry verdict: mine the data and
pure transformations, not the owner wholesale.

### Current run form plan: narrow and sound

**A for its stated job.** [plan-call](../../../../src/seon/cluster/run.clj#L604)
checks held/open/call state inside the transaction, refuses an existing digest,
gives every form a stable `(run, ordinal)` identity, preserves parse-time
namespace and author, and asserts the digest and forms atomically. The loop
hashes the exact ordered source value and only then installs it
([loop.clj](../../../../src/seon/cluster/loop.clj#L1146)). Its predecessor in
the deleted driver had the same central law: absent-to-digest CAS made
concurrent replies mutually exclusive so the loser could not splice forms into
the winner.

It does **not** track task dependencies. Ordinal is execution order, not a
dependency edge. Later `:seon.fn/calls` edges attached to settled forms describe
the program graph, not prerequisites between tasks. The digest is a content
identity and freeze fence, not a Merkle dependency digest. There is no
application of changes: a second plan is refused. A revised session is instead
represented by the separate curation revision/proof/adoption mechanism, not an
in-place form-plan diff.

### Deleted `plan-execution`: principled answer to the wrong topology

**B as a fail-closed derivation; D as system fit.** At
`c45616a38^:src/seon/program/plan.cljc`, `plan-execution` walked reachable
function edge bundles, intersected eligible tiers for terminal calls, closed
schema and predicate dependencies, accumulated required bindings/effects/native
leaves/artifact exports, and returned `:anywhere`, `:constrained`, or
`:unplannable` with explicit unresolved evidence. Its acquired projection was
fenced by database basis, commit ID, graph digest, schema fingerprint, and
artifact inventory. That is honest plan data.

It was nevertheless not a task graph, and it did not support authored updates
or diff application. The [WTF review](wtf-review-2026-07-24.md) measured the
combined placement/edge mechanism at roughly 1,240 lines on every reply, with
three unbounded whole-corpus queries to decide among a topology that then had
exactly one JVM and one Bun path. Commit `c45616a38` deleted 671 production
lines and 507 direct test lines when the synchronous form fold replaced the
phase driver. Its lesson is to preserve explicit unresolved evidence and input
fences where placement actually varies—not to add dependency planning to the
todo.

## What ruling 49 did and did not cover

Ruling 47 originally required `my.plan` to be fact-backed with statuses and a
compact render. Ruling 49 superseded that specific target:

> The todo is the one task system; my.plan is retired unbuilt.

The operative details are derived-first obligations—unanswered messages, open
runs, and failing tests—plus authored item facts for decomposition; one union
render; and completed authored items disappearing from the current view
([self-generating context PRD](../plan/self-generating-context-prd-2026-08-11.md#L333)).
The [systems sweep](rebirth-systems-sweep-2026-08-12.md#L65) translated that
into the proposed minimal authored shape: identity, agent ref, title, optional
parent; entity presence means open and completion retracts it.

The ruling **did cover**:

- retirement of the fresh target namespace and public `my.plan` toolkit;
- cancellation of a fresh agent→plan relationship and plan-status family;
- replacement of a plan-only current view with the todo union; and
- ownership of agent-authored intentions by todo facts.

It **did not cover**:

- historical existence. `my.plan` and its predecessor were built, exercised,
  and deleted; “never ported” is the evidence-correct phrase;
- the surviving run form plan and its digest, which describe executable reply
  forms rather than work intentions;
- the already-deleted `plan-execution` placement/manifest derivation;
- a conclusion that dependency edges, stable identities, pure reconciliation,
  or bounded derived projections were never tried; or
- authorization to port those mechanisms. Ruling 49 selects a smaller task
  model despite their historical existence.

There is also incomplete documentation cleanup at current HEAD. The normative
PRD says the toolkit is removed from target vocabulary, but the current
[toolkit architecture](../../../seon/architecture/toolkit.md#L82) still lists
`my.plan`, and its namespace section still describes intent/dependency facts,
roll-up, reconciliation, and twin rendering
([toolkit.md](../../../seon/architecture/toolkit.md#L261)). Fresh `src/` and
`resources/` contain no `my.plan` namespace or schema, while the roadmap says
“NEVER ported.” This is stale target documentation, not implementation evidence
and not a reason to reverse the ruling.

Recommended historical wording for the owner to consider:

> The todo is the one task system; the State A `my.plan` was not ported into the
> fresh CLJ system and its fresh target is retired. Todo owns authored intent.

That refines scope without changing the decided system.

## Quarry mechanisms that may inform the todo

Each item below states both the positive evidence and the case against carrying
it. These are design inputs, not recommendations to reopen ruling 49.

### 1. Stable authored identity and pure whole-value reconciliation

**Evidence for carrying the lesson.** The old document/reconcile experience was
the best code found. Stable IDs preserved progress across edits. Current and
desired ordinary data compiled to one atomic transaction and a counted diff.
Ambiguity and foreign ownership failed before writes. A round trip converged to
no transaction. Current [seon.reconcile/plan](../../../../src/seon/reconcile.cljc#L315)
independently validates the same general shape: pure exact convergence, empty
transaction data when already converged, and identity/provenance refusals.

**Case against.** Ruling 49's union mixes derived obligations with authored
items. Absence from an agent's partial or elided todo view must never mean
“delete the underlying message/run/test.” Even for authored items, a small
add/complete surface may be enough; whole-document replacement makes omission
destructive and title-based ID recovery creates a costly ambiguity protocol.
The old reconciliation should therefore not be copied as a union-wide admission function.
If later evidence earns bulk editing, its scope must be only the complete
authored-item projection, with explicit stable IDs preferred over inference.

### 2. Dependency edges and derived ready/blocked work

**Evidence for.** `:my.plan/needs` was not decorative. It made the ready frontier
mechanical, prevented offering blocked work, and let completing one prerequisite
unblock several dependents. The rules were compact and the tests covered the
transition. A parent tree alone cannot represent cross-branch prerequisites.

**Case against.** The ruled todo is an obligation view, not a scheduler.
Unanswered messages, open runs, and failing tests already derive their state
from native facts. A general dependency DAG adds cycle policy, closure cost,
stale-edge semantics, and pressure to restore a plan-status family and central
dispatcher. No current ruling names a use case that requires it. Because maps
and schemas accrete, a `/needs` ref can be added later if a real authored-task
workflow falsifies the flat/parent-only model; speculative inclusion would make
the first todo more complex than the evidence requires.

### 3. Parent refs for authored decomposition

**Evidence for.** A single optional parent ref made nested decomposition and
roll-up cheap, readable, and queryable. It is already in the systems sweep's
minimal authored shape. It also gives an edited or rendered item a stable path
without stamping a kind.

**Case against.** Parent is presentation/decomposition, not execution order.
The union's derived arms have their own natural causes and owners, so forcing
all of them into one authored tree would duplicate facts. A hierarchy can also
hide urgent leaves beneath pleasant milestones. The old transitive tree and
progress roll-up should apply only where authored parent facts actually exist.

### 4. Derive projections; do not restore the status machine

**Evidence for.** The old plan was strongest where it derived readiness,
blockage, active ancestry, progress, and focus from graph rows. Its compact
current view survived transcript aging and rebirth.

**Case against.** It still stored `:open/:active/:done/:blocked`, plus completion
time, and mixed explicit block assertions with dependency-derived blockage.
That conflicts with ruling 49's simpler presence-is-open, retract-on-completion
shape. The old recent-completion tail also conflicts with “completed items
vanish” from the current todo render. Historical completion can remain
queryable through database history without being copied into current-state
todo facts or rendered by default.

### 5. Bounded, identity-preserving current views

**Evidence for.** An anchor, ancestors, a bounded frontier, total counts, and
stable item identities gave the agent useful context without dumping the whole
forest. This is directly relevant to a heterogeneous todo union.

**Case against.** The fixed seven/five limits are archaeology, not target
constants. Current rendering owns consumer fit through render profiles,
`seon.print/fit`, elision values, and requery identity. A todo-specific clipping
algorithm or silent truncation would duplicate that owner. Grouping the union
by obligation source may also be more honest than pretending it is one ordered
frontier.

### 6. Digest/fence lessons from the run plan

**Evidence for.** The form plan proves a good concurrency rule: identity and
content become durable in the same transaction, and an absence fence prevents
two complete answers from interleaving. Any future bulk authored-todo edit
would benefit from an explicit observed basis or identity fence rather than a
read-then-write race.

**Case against.** A stored todo-plan digest would be remembered derived state
unless it guarded a concrete once-only boundary. Todo items are individually
identified facts, not one immutable ordered reply. Reusing `/plan-digest` or
the run's freeze semantics would falsely imply that authored work cannot
accrete after its first transaction.

### 7. Keep generated scheduling and escalation outside todo

**Evidence for.** The old namespace demonstrates that a task graph can host a
namespace DAG and retry/escalation workflow. Its compiler was idempotent and its
terminal preservation was careful.

**Case against.** This was the accretion that made the owner unwieldy. Fresh
program edges, run forms, receipts, work derivation, problem routing, messages,
and provider descriptors already name the relevant facts and owners. Todo may
render obligations derived from those facts; it should not become their run
loop, placement engine, or escalation dispatcher.

## Bottom line for the owner

Ruling 49 chose the smaller current task system; this archaeology does not
falsify that choice. It falsifies only the unqualified historical claim that
`my.plan` “never implemented” or “never built.” The repository had a task-plan
lineage spanning `seon.agent.todo` and `my.plan`, and its later era contained a
genuinely good identity-preserving diff application mechanism and real
dependency semantics.

The highest-value quarry lesson is structural: authored intent should be
ordinary identified facts; current meaning should be derived; a complete
authored value, if bulk editing is ever earned, should compile purely to one
fenced transaction with an explicit diff and no-op convergence. The strongest
case against importing it now is equally clear: the ruling-49 todo union is not
a mutable plan document, its derived arms must never be deleted by omission,
and neither dependency scheduling nor generated-code escalation has yet earned
a place in the todo owner.
