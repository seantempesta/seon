---
type: plan
status: launch specification; waits for prerequisite landings and overlapping-path release
created: 2026-09-21
tags: [plan, task, namespace-agents, schema, rendering, wake, identity]
---

# Wave 3a — the task family

> **Launch amendment, 2026-09-23 (orchestrator; binding over the body below where they differ).**
> Read this block first, then the body. The body was written on 2026-09-21,
> before the owner's rulings of the 22nd; its schema delta, D1/D2 semantics,
> fingerprint contract, regressions and per-file census stand. What changes:
>
> 1. **Vocabulary.** Wherever the body says "facet" read *error schema*
>    (`[:and :seon.error/base [:map …]]`) or *error map* (the value); wherever
>    it says "family" read *the task entity and its declared attributes*
>    (`seon.task`). Never write either retired word in code, docstrings, notes
>    or issues. Explain only in Clojure, Malli, Datahike, SCI, core.async terms.
> 2. **Error shape (owner, 2026-09-22).** A stored error entity carries only
>    what its Malli error schema declares; a validation failure stores Malli's
>    explanation data; any value outside the schema is one `result/e<id>`
>    reference. A task's `:seon.task/errors` cite error roots by
>    `:seon.error/signature`; no task member ever carries a raw value.
> 3. **Seconds, not minutes (owner law, AGENTS.md).** Every writer this cut
>    adds (`seon.task/trigger-call`, occurrence update, settlement) is one
>    Datahike transaction and must complete in well under a second on the
>    canonical fixture; an opening render on the fixture under two seconds.
>    Any phase above ~2 s is a finding to explain by algorithm in the note.
>    Regressions assert facts and datom sets, never timing; the lane records
>    wall-clock per fast run.
> 4. **The platform under this cut is the one-JVM redesign**
>    ([one-jvm-publication-redesign-2026-09-22.md](one-jvm-publication-redesign-2026-09-22.md)):
>    publication and adoption go through the running cluster's prepl; the
>    body's instructions about paused hook publication, scratch-root proofs
>    "after release" and the reset batch (1a, 1d, the bridge stamp) are
>    superseded — the orchestrator resets `default` after the landing;
>    RESET NEEDED is still recorded with the exact attribute list.
> 5. **Prerequisites re-evaluated.** Bridge step 2 landed (`0f5f849bd`):
>    compiled Malli nodes are the projection; `seon.schema.form` is gone —
>    use `malli.core/properties`/`children` through the projection's
>    registry as the converted callers do. The "wait for error-family-1a's
>    D13/precise-error contracts" prerequisite is dropped: D13 is
>    `seon.error/signature` as it exists; consume it, do not extend it.
> 6. **Effort and size.** Launch at `LANE_EFFORT=low`. Cut the body's
>    4–6 lane-days into three stops, each a coherent path-limited slice with
>    HEAD loading after it and its fast tally recorded: (i) the schema delta
>    + `seon.issue → seon.task` rename in place with every caller converted;
>    (ii) `seon.task/trigger-call` (D2) + the canonical regression that one
>    trigger yields one task and one agent, and a repeat occurrence updates
>    without a new task, agent or notification; (iii) opening through the
>    task render pair + the dedup/wake/deletion regressions. Stop after each.
>

**Guarantee:** one trigger identity names one task in a cluster. The writer
links a new occurrence to that task and wakes its existing agent, or creates
the task and its one agent atomically. Namespace responsibility is
many-to-many and never selects a recipient. A task's linked facts supply its
opening through the existing render pairs and generated-read machinery.
There is no template entity, task-type discriminator, or scheduler.

This is the verbatim implementation assignment for **3a only** in the
[namespace-agents plan](namespace-agents-plan-2026-09-19.md), §§1, 3, 6, 8,
including D1/D2/D6/D8/D12/D13 and §5's accepted opening-trace correction.
The plan's earlier “plural workers per task” sentence is superseded by D2:
**one agent per task; several tasks and agents per namespace**. The
[context research](../research/context-templates-and-render-pairs-2026-09-19.md)
is archaeology, not permission to restore its superseded names or assume
that the issue pair is the only opening path. The
[adoption diagnosis](../research/adoption-silence-diagnosis-2026-09-19.md)
does not prove publication equals loaded behavior or browser paint; its
“not a second cause” attribution was corrected in plan §5, Turn 4.

## Launch verbatim on astra low, after prerequisite landings

> Implement **wave 3a — the task family** in /Users/sean/src/seon,
> branch steward-platform. Read this specification, the namespace-agents
> plan, context-templates/render-pairs research and adoption-silence
> diagnosis end to end; read AGENTS §§2–3 and lane rules, the current
> working-edge/reset checkpoint, and the data-oriented-clojure,
> data-modeling, datahike, repl, clojure-testing and datastar-web-ui skills.
> Read the dependency ledger below against the checked-out sources.
>
> You are not alone in the codebase. Preserve unrelated edits and do not
> revert others' work. Execute this bounded assignment directly, without
> delegating again. Refresh the caller census and ownership before edits.
> Wait for error-family-1a's D13/precise-error contracts and the relevant
> bridge/publication caller retirements to land. Consume the actual 1c
> landing if present; a plan row is not implementation. Any already-landed
> D1/D2 behavior is verified and renamed in place, never implemented again.
>
> **Own** the old/new task-family files, every live caller in the census,
> the narrow agent/namespace, fault recording, wake and opening seams in
> the per-file table, their named tests and affected schema/config facts,
> the current AGENTS vocabulary claims this cut invalidates, and one
> landing note at
> docs/prds/steward-platform/research/wave-3a-task-family-2026-09-21.md.
> Hold shared files until the orchestrator releases their current editing
> owner. Never operate, resume, message or repair another lane's session.
> The public retirement and conversion of every caller are ONE coherent
> slice; leave no compatibility namespace or forwarding Vars.
>
> **First proof, before changing the walk:** record a real canonical-fixture
> opening under :bare and under :evidence-first, through system-turn and
> the agent's real SCI context. Save exact generated source, evaluation
> order/origin, shown text, selected render functions and duplicate-form
> counts. Agent render-identity-ai already calls issue.opening/source;
> merely changing the entity pair would add a second status read. Follow
> the rendering contract below, moving that responsibility to the task pair.
>
> **Rename one family in place:** seon.issue → seon.task, its citation and
> detector/opening namespaces and schemas included. Retire my.issue; direct
> agent calls use seon.task/status, add! and tests! with the same supplied
> defaults. Do not resurrect my.task. Keep Markdown issue notes and their
> filesystem grammar as the input adapter to this same family. Do not
> rename the docs/seon/issues directory or rewrite dated historical notes.
> Stop copying task problem text into my.plan/objective: the existing
> plan item's subject ref is the relation; the task owns its instructions.
>
> **Implement D2 at seon.task/trigger-call** through seon.db/transact! and
> :db.fn/call, composing the existing create/start and error occurrence
> writers. The writer reads its current database, resolves detector plus
> subject identity through seon.task/subject-id, and decides existing
> assignment versus creation there. No pre-read routing, namespace
> broadcast, callback transaction, or direct graph start. Assignment and
> agent facts commit together; the existing armer reacts after commit.
>
> **Use the fingerprint contract below.** D13 remains seon.error's identity
> owner; tasks consume its error-signature subject value. No second fault
> hash, truncation, signature field, template key, kind or detector registry.
> A conflict is the same task mechanism over a conflict observation. Both
> sources and basis are evidence, never identity. Root workers are acquired
> for their individual conflict tasks; they are not one shared worker named
> root for every conflict. Wave 4 owns the merge operation and acceptance.
>
> **Existing task occurrence means an update, not a new task, agent or
> notification.** Keep one notification identity per fault task. Use the
> existing runtime listen patterns for durable occurrence updates and make
> wake delivery and unanswered-wake derivation agree, as specified below.
> Reasserting task/agent cannot wake anything. Repeating occurrences cannot
> refill the task's total turn budget. Assignment, notification, answered
> wake and satisfied acceptance tests are different facts.
>
> Declare the exact stored and transient schemas below, including every
> ref's deletion behavior. Preserve final writer guards on assigned tests
> and creator authority. Primary subject deletion is not detector success. Missing
> subjects, unavailable detector results and unrun/stale tests must remain
> visible in status and cannot settle a task. No hand-written pulled-schema
> widening; use the landed selector-derived shape owner.
>
> Prove the canonical regressions below with with-database, real SCI,
> armed contracts, canonical row helpers, transacted!, explicit carried
> projection/environment, fixed render profiles and bounded event waits.
> Use virtual replies/no-provider settings; no paid model call is needed.
> Show nonzero subjects before testing deletion or deduplication. A channel
> offer, empty query, absent error/kind, or zero executed members is not a
> behavioral proof.
>
> **RESET NEEDED.** This rename, namespace ref widening, related property/
> config changes and retired error responsibility fields join the owner's
> next clean-tree schema batch. Report the exact inventory below alongside
> 1a, 1d and the bridge stamp, without claiming they remain pending if the
> orchestrator has already reset them. Never reset/restart/refork default,
> unpause its hook or publish over foreign partial resources. Schema and
> every loaded consumer are one publication. An old incompatible default
> is a verification boundary; use the owned scratch proof after release.
>
> Iterate with bin/test-fast --paths <all owned changed paths> -- <affected
> namespaces>. No cold bin/test, --all, --full, nested gate, baseline
> preparation, or worktree. One foreground JVM at a time. The orchestrator
> owns cold --paths, --platform and default reset/adoption. At a foreign
> load failure use the HEAD-plus-owned-paths fast snapshot and continue
> independent work; if snapshot admission itself refuses, report its exact
> path and leave the public retirement uncommitted until the load proof is
> possible. Do not repair a foreign hunk to pass your run.
>
> Before the coherent commit, require every changed production namespace
> together in one foreground JVM. Deliver the refreshed census, exact
> changed-path list, additions/deletions, executed/unchanged/unavailable
> fast tallies, opening bytes, dedup/wake/deletion evidence, scratch live
> proof and reset/cold/default obligations in the landing note. Remove
> owned scratch roots only after their recorded process has exited.
> Commit path-limited and stop before 3b, 3c or wave 4. Estimate **4–6
> lane-days**, excluding coordination and reset/gate queues. At a genuine
> cross-owner decision, stop before production edits with exactly three
> priced options: simplest viable constraint first and recommended, each
> with guarantee, cost and what we give up.

## Exact schema delta — one stored family

The inspected owner is `resources/seon/schemas/seon.issue.edn:1–69`.
Move it to `resources/seon/schemas/seon.task.edn`; move
`seon.issue.citation.edn` to `seon.task.citation.edn`. No old attribute is
retyped in place or aliased. Retire the old canonical declarations and
convert every schema reference, renderer symbol and property value in the
same publication. Tables below specify the new declarations; descriptions
are the intended literal docstrings, not a menu of deletion policies.

**Notation:** one/many are native Datahike cardinalities. `ref` is
`:seon.db/ref`; `many ref` is `[:set :seon.db/ref]`. `R` is required in
`:seon.task/task`; `O` is optional. All maps stay open. Scalar “value”
means deleting an entity named in its text/token does not sweep it; the
attribute disappears with its own entity. No `:db/noHistory` is added.
Identity/index/component/wake properties not stated below are absent.

| New attribute | Malli / native type; cardinality; presence | Properties and literal description / deletion behavior |
|---|---|---|
| `:seon.task/id` | `[:string {:min 1}]` / string; one; R | `:seon.db/identity true`. “Value: the note slug for indexed work, the detector-plus-subject fingerprint for detected work, or the authored title/subject identity. One current task per identity in this branch. Retraction removes the current task; retained history preserves its former facts.” |
| `:seon.task/title` | nonempty string / string; one; R | `:seon.db/index true`. “Value: the task's authored title; it is not a task discriminator or a generated task's fingerprint input.” |
| `:seon.task/status` | `[:enum :open :resolved :superseded]` / keyword; one; R | “Value: the closed lifecycle vocabulary supplied by a note or the task writer. Imported terminal status is retained even when no local resolution transaction exists; runtime settlement records resolved-tx together with status. This enum describes lifecycle, never a task type.” |
| `:seon.task/severity` | `[:enum :blocker :friction :cleanup]` / keyword; one; O | “Value: the author's priority assessment for a defect; absent for work without a defect severity. This closed assessment is not a task type.” Detectors keep an explicit supplied severity; the Markdown adapter still requires its existing frontmatter. |
| `:seon.task/opened` | `:inst` / instant; one; O | “Value: the source's reported opening time, or the creation time supplied to the writer for database-authored work. An imported time is not the indexing transaction's time.” |
| `:seon.task/path` | nonempty string / string; one; O | “Value: the repository path of the indexed Markdown issue note; absent for database-authored work. Removing the note retracts the task through normal guarded deletion.” |
| `:seon.task/problem` | nonempty string / string; one; R | “Value: the task's instructions or reported problem, authored once here. Plans link this task and never copy these bytes into their objective.” |
| `:seon.task/subject` | ref; one; O; new | “Sweep: the living primary subject whose identity the trigger used to derive this task. The trigger writer requires it when admitting detected work; after target deletion the task remains with unavailable subject evidence and cannot resolve from detector silence. Authored or imported work may have only its other linked facts.” No identity/component/wake property. |
| `:seon.task/functions` | many ref; O | `:seon.task/cites [:seon.fn/sym]`. “Sweep: optional peer refs to the living functions this task concerns. Deleting a function removes its link, not the task, and does not prove completion.” |
| `:seon.task/tests` | many ref; O | `:seon.task/cites [:seon.test/sym]`; `:seon.db/append-only-after :seon.task/agent`; `:seon.db/retraction-authority :seon.task/created-by`. “Sweep before assignment; after assignment the existing final writer guard refuses loss of success tests without the original creator's authority and refuses an empty required acceptance set. Tests are peers, never owned children. A swept or unavailable test is not a green test.” Preserve the guard's actual whole-transaction/nonempty semantics, including deleting the task itself; do not weaken it to an API-only check. |
| `:seon.task/errors` | many ref; O | `:seon.task/cites [:seon.error/signature :seon.error/id]`. “Sweep: optional peer refs to durable error roots, including conflict observations. Occurrences remain components of the error root, never copied into the task. Deleting a root removes this link and does not prove repair.” |
| `:seon.task/keys` | many ref; O | `:seon.task/cites [:seon.schema/key]`. “Sweep: optional peer refs to current schema declarations this task concerns. Retraction removes the link; historical declarations remain temporal observations.” |
| `:seon.task/namespaces` | many ref; O | `:seon.task/cites [:seon.ns/name]`. “Sweep: optional peer refs to namespaces relevant to the work. Namespace membership supplies context, never a wake recipient.” |
| `:seon.task/files` | many ref; O | `:seon.db/component true`, `:seon.db/component-schema :seon.task.citation/citation`, `:seon.task/cites [:seon.fn.file/relative-path]`. “Cascade: this task owns its file/span citation components. Retracting the task destroys those citations, not the files they cite.” |
| `:seon.task/runs` | many ref; O | `:seon.task/cites [:seon.test.run/id]`. “Sweep: optional peer refs to test runs cited as evidence. Losing a run does not establish current verified results.” |
| `:seon.task/related` | many ref; O | Successor of `/issues`; `:seon.task/cites [:seon.task/id]`. “Sweep: optional peer refs to related tasks. Related work is not component ownership; retracting one task leaves the others.” |
| `:seon.task/unresolved` | `[:set [:string {:min 1}]]` / string; many; O | “Value: exact citation tokens with no installed target identity. Their presence is evidence of unresolved citations, never fabricated target entities.” |
| `:seon.task/commits` | `[:set [:string {:min 9 :max 9}]]` / string; many; O | “Value: nine-character Git commit citations from the note, not Datahike commit IDs. Target deletion cannot sweep an observed token.” |
| `:seon.task/members` | many ref; O | “Sweep: optional peer refs to tasks grouped by this class note. A class note does not own or cascade-delete its members.” |
| `:seon.task/agent` | ref; one; O | `:seon.db/index true`, `:seon.wake/listen true`, `:seon.wake/opens-turn? true`. “Sweep in the ref grammar; agent retraction is separately prohibited by the agent owner. The one agent assigned to this task, asserted with its creation in the same writer decision and retained on resume. Its first assertion is the initial wake and total-budget anchor. Archival does not remove it. Reassertion is not an occurrence update.” |
| `:seon.task/created-by` | ref; one; O | “Sweep in the ref grammar; agents are retained by their owner. The author agent's first assertion supplies enduring test-retraction authority, including after unassignment. It is not the current assignee.” |
| `:seon.task/detector` | ref; one; O | “Sweep: optional peer ref to the living detector function declaration. The task identity was derived from its qualified symbol and the subject's identity value. Missing detector evidence is unavailable acceptance, never successful completion.” |
| `:seon.task/budget` | `[:int {:min 1}]` / long; one; O | “Value: total ordinary turns allowed since the first assignment. An explicit resume raises this budget for the same agent; occurrence updates never refill it.” |
| `:seon.task/budget-exhausted-tx` | ref; one; O | “Sweep: optional peer ref to the transaction recording exhaustion and root notification. Resume retracts it after increasing the budget; absence alone does not establish that a task exists or is runnable.” |
| `:seon.task/resolved-tx` | ref; one; O | “Sweep: optional peer ref to the transaction where settlement positively verified the task's current acceptance condition. A new genuine recurrence reopens the same task by retracting this fact and setting status open; an unavailable observation never writes it.” |
| `:seon.task/messages` | many ref; O; new | `:seon.task/cites [:seon.message/id]`. “Sweep: optional peer refs to messages that request or explain this work. Deleting a message removes its link, not the task. Conversation and reply chains remain message facts, not task components.” |

`:seon.task/task` has `:seon.db/attributes true`, the required and optional
entries above, `:seon.render/ai seon.task/render-ai` and
`:seon.render/html seon.task/render-html`. Its declared units are
`[:seon.task/subject :seon.task/functions :seon.task/tests :seon.task/errors :seon.task/keys
:seon.task/namespaces :seon.task/files :seon.task/runs :seon.task/messages
:seon.task/members]`. Only populated links contribute entities; neither a
new per-task unit roster nor a template record is stored. A missing pair
uses the existing honest floor; 3b owns filling those pair gaps.

The new primary `/subject` relation is needed to retain what the trigger
selected; it differs from additional `/functions`, `/errors` and other
context links. A hash cannot recover its inputs. Generated tasks assert
this primary ref and do not copy it into another typed link merely for
rendering: its own entity pair renders through the `/subject` unit. Other
links carry additional evidence. Update scoped generation, which currently joins `/functions` to a source
root (`issue.clj:553–556`), to follow the primary `/subject` instead.
Prospective task plans use the same primary ref and subject-id derivation;
do not keep the old duplicate relation solely for a caller. The note adapter
may leave `/subject` absent because its cited topics do not assert one primary subject. Status
and settlement distinguish that case from a detected task whose `/subject`
has swept, using the detector relation and current subject facts. No stored
subject identity mirror, type stamp or guessed primary from collection order.

`:seon.task/cites` is declaration metadata, a vector of qualified identity
attribute keywords, not a stored task attribute or an entity taxonomy.
Declare its shape at the schema-property owner if the landed bridge requires
registered properties; consume it through that bridge's retained property
API. Do not retain the retired `seon.schema.form` reader from
`issue.clj:158–176`. Discovery remains declaration-driven; no recognizer
roster for task subjects is added.

Preserve `/status` and `/opened` in this cut: the indexer demonstrably imports
terminal frontmatter without writing `/resolved-tx` (`issue.clj:88–114,
283–433`). [Modeling guide §7.1](../../../seon/architecture/data-modeling-guide.md#71-the-datahike-modeling-study-and-its-overrides)
explicitly qualifies their proposed dissolution. Renaming this adapter must
not reopen resolved notes or relabel imported dates as local transactions.
No status enum is used to identify the family; identity and attribute
presence do that.

### Owned citation, namespace responsibility and adjacent declarations

| Declaration | Exact type/cardinality and description |
|---|---|
| `:seon.task.citation/id` | nonempty string, one, required, unique identity. “Value: seon.id/id of [task-id path row end-row], preserving the existing stable citation identity. The containing task owns this citation.” Retain the existing identity because indexing/adoption uses it, not to evade owned-child validation. |
| `:seon.task.citation/file` | ref, one, required. “Refuse: the cited file must remain while this citation survives. Retract the citation or its owning task in the same transaction before deleting the file.” No component flag here. |
| `:seon.task.citation/row` | integer ≥1, one, optional. “Value: first cited line; absent for a bare file citation.” |
| `:seon.task.citation/end-row` | integer ≥1, one, optional. “Value: last cited line; absent for a single line or bare file citation.” Preserve the current span grammar. |
| `:seon.task.citation/citation` | open stored map with required id/file and optional row/end-row; the parent's component-schema selects it. No new render pair campaign. |
| `:seon.ns/agents` | `[:set :seon.db/ref]`, native ref/many, optional in `:seon.ns/ns`, no unique/component/wake property. “Sweep: optional peer refs to agents responsible for this namespace. An agent may serve several namespaces and a namespace may name several agents. Agents are retained and archived, so archival does not sweep responsibility. This relation never routes a trigger.” Replace `/steward`, including its written-by metadata, with the landed additive assignment function. |
| `:my.agent/agents` | read projection only, `[:set :seon.agent/id]`; replaces scalar `:my.agent/steward`. “The identity values of agents responsible for the current namespace; absent or empty when none are assigned.” No new stored agent attribute. |
| `:seon.agent/namespace` | unchanged one optional peer ref: the current/default REPL namespace, independent of responsibility. Never widen it to many. |
| `:seon.agent/agent` units | `/issue/_agent` becomes `:seon.task/_agent`. Remove obsolete namespace-routed fault unit only with its task-linked replacement; retain the agent's own-turn fault visibility through the landed 1c owner. No conversation entity or `/of-agent` thread implementation in 3a. |
| `:seon.eval/origin` | unchanged scalar string value, alias now `:seon.task/id`. “Value: the task identity observed when generating this evaluation; survives task deletion and is carried through changed-read regeneration.” No ref conversion, new stamp or restorable object. |
| `:seon.program/tasks` | read projection `[:vector :seon.task/task]`, replaces `/issues` and its caller in `my.program/breaks`; no stored work queue. Prospective plans keep actual subject-id equality with generated tasks. |
| `:seon.config.render/task-opening` | rename `/issue-opening`; one keyword enum `:bare :plan-first :evidence-first :walkthrough :questions :namespace-picture :minimal-retrieval`, optional per-agent dial, default `:bare`. Preserve current config properties, label “Task opening”. “Selects the existing task opening presentation; it does not select a task type, registry or execution path.” Update `config/default.edn:363` and every caller/overlay together. |

Retire `:seon.ns/steward`, `:my.agent/steward`, and the old task-family
schema identities. Consume 1c's retirement of `:seon.error/steward` and
`:seon.error/of-steward`; if not landed, include their removal and every
consumer in this slice once 1a releases them. Namespace-based fault routing
has no replacement scalar. Error occurrence `/agent` still means the agent
that experienced the error; **never repurpose it as the repair assignee**.
Read an agent's repair faults via task `/agent` → `/subject` or `/errors`
(joining actual error-signature facts), and its own
faults via the occurrence relation. A renamed derived error read is allowed;
a stored mirror of task assignment is not.

### Transient contracts and errors

Declare separate non-stored request/view schemas in `seon.task.edn`; a
request never pretends its detector symbol is a stored detector ref.

- `:seon.task/subject-identity`: `[:tuple :qualified-keyword
  :seon.db/lookup-ref-value]`, an installed identity attribute and its
  logical value. Runtime validation requires exactly one existing subject
  in the writer's current database. This tuple is request data, not a new
  persisted identity mirror.
- `:seon.task/trigger-request`: explicit connection in the public request;
  `:seon.fn/sym` names the detector; `:seon.task/subject-identity` names the
  subject; task title/problem and supplied creation time, budget, selected
  namespace and optional severity/settings/creator; an optional
  `:seon.error.occurrence/ref` names fault occurrence evidence. The tx-call
  form omits connection and receives the current database from Datahike.
  Reuse the already declared occurrence-ref grammar. No untyped trigger
  attribute is stored. Arbitrary caller-supplied transaction functions are
  not part of this request.
- `:seon.task/status-view`: non-stored derived result, with id/title,
  nonnegative turns-remaining, acceptance/check evidence, current test
  results and explicit missing evidence. Derive referenced shapes under
  selectors. Do not return the entire stored task with `/problem` merely
  to feed it back into the source-producing entity pair. A dedicated view
  pair, `seon.task/render-status-ai` (text) and `render-status-html`
  (hiccup), shows the result of the status form without generating another
  status form. This is a read-view pair, not a second stored task family.
- Rename the existing transient index/report/test-state keys
  `seon.issue.parse/*`, `seon.issue.test/*`, `seon.issue.unresolved/*` and
  task-local diagnostics with their owners. They gain no stored attributes
  merely because they occur in a result map.
- New task-boundary errors compose the landed error base and precise
  evidence: invalid/missing detector, absent/ambiguous/unlinkable subject,
  existing assignment, invalid budget, missing namespace/cluster, missing
  acceptance evidence. Declare the exact output alternatives per function,
  including propagated DB errors; no general `error?`, `/kind`, boolean
  class stamp, `:any` or broad undeclared success map. Existing named
  diagnostics move with their producer; use required base members and
  offending/expected evidence through `seon.error/diagnostic`.

## Trigger writer, identity and occurrence updates

### Reuse the existing identity owner

`src/seon/issue.clj:470–479` already defines the generator/prospective-plan
identity seam. Its successor is **`seon.task/subject-id`**, using the same
algorithm with the new family key:

```clojure
(seon.id/id
  (into (sorted-map)
        {:seon.task/detector detector-symbol
         subject-identity-attribute subject-identity-value}))
```

Keep its default length; do not take a second substring, use a second hash
implementation, or put DB entity IDs, task title, prose, time or process in
this fingerprint. Detector and program names are qualified symbols. The
reset deliberately changes generated task IDs because the family key and
some detector symbols change. Authored tasks retain the existing title plus
subject identity derivation, but resolve function refs to sorted identity
values at the writer before hashing; numeric EIDs/tempids are not stable
subject identities. Note slugs remain unchanged input identities. A hash is
a deterministic deduplication key, not a mathematical collision guarantee.

For a fault the subject is **`[:seon.error/signature signature]`**. Consume
the landed `seon.error` recording result; do not recompute a signature from
rendered text or a partial pull. The inspected `error.clj:198–225` uses
`seon.id/id` with length 64 over:

1. layer and operation;
2. sorted satisfied facet-schema keys of the complete observation;
3. throwable class and top frame when present;
4. the violated expected schema key/shape when present;
5. location path, including any explicit location omission evidence.

Timestamp, basis, process, message text and offending bytes are occurrence
evidence, excluded by D13. Equal signatures share one error root; its
owned occurrence rows/counts remain the existing error mechanism. A task's
fingerprint then uses that signature value plus its actual detector symbol.

Add an ordinary contracted detector **`seon.task.detect/unresolved-errors`**
for fault tasks. It names existing error roots requiring repair; absence of
current verified acceptance tests is unresolved. Once tests are attached,
current test evidence and recurrence since their tested basis decide whether
repair is still current. Do not implement “no more occurrences for N seconds”
or use the task's own resolved flag as its detector's success condition.
While no tests exist it remains unresolved, including after the creating
agent's ordinary reply. Fault tasks start with this detector so missing
regressions do not block acquiring the agent whose work is to write them.

The task completion owner must retain this recurrence obligation when tests
are added: the old tests-first branch in `issue/done?` must not bypass it.
Read `:seon.test/run-basis-t` and the run's tested-branch provenance, not
`:seon.test/recorded-basis-t` (which dates recording/reuse). Compare
occurrence transactions to a tested basis only in the same branch/lineage;
foreign or missing basis evidence is unavailable, never numeric evidence of
repair. Shared test selection still owns reuse. If an unchanged green is
older than a genuine new recurrence, report that mismatch and require a
regression/definition change establishing new evidence; do not invent a
force-run flag, inflate test inputs or claim re-recording old green repairs
the fault. Wave 4 must supply its explicit acceptance evidence for a
cross-branch conflict resolution through the same task owner.

**Conflict compatibility:** the wave-4
[conflict contract](wave-4-isolation-merge-spec-2026-09-21.md#durable-acceptance-and-conflict-evidence)
records the precise conflict refusal through this same error/task writer.
Its D13 location names the conflicting program identity. Both sources and
the B/T/C basis are complete occurrence evidence, never fingerprint inputs.
The same conflict identity with different source bytes or a later basis
updates one task; a different location/operation/facet set is a different
identity. The repair namespace is root's namespace, supplied explicitly to
the task writer; namespace selection is not recipient selection. Derive a
separate agent identity from each task, so acquiring a root worker does not
assign all conflicts to the fixed user-facing `"root"` agent. Use ordinary `seon.cluster.agent/creation-tx` with the task-derived agent ID
and explicitly supplied root namespace/settings. Do not clone the fixed
root identity or its bootstrap supervision rows; do not create a root pool.

3a proves the fingerprint and acquisition using the real error writer and
canonical fixture evidence. Wave 4 supplies its real conflict schema,
merge writer and tests; do not create a production placeholder conflict
schema in 3a. The 3a fixture may use `extra-schema` for its synthetic conflict
facet. A rejected merge rolls back everything in that transaction: wave 4
records its resulting error/task in a subsequent admitted transaction.
Never claim a task was created inside a rejected program transaction.

### One writer decision; graph activation follows commit

Strengthen the existing family, not a new dispatcher:

- **`seon.task/trigger-call [database request]`** is the tx-data owner.
  It returns transaction data; refusal throws the precise diagnostic through
  the existing transaction boundary, which returns it as a flat value. Never
  hand a refusal map to Datahike as transaction data. Verify the detector's installed program identity and contract, resolve the
  subject at that database, derive the task ID and look it up there. Build
  new linked facts through the successor of `subject-row`, storing the
  resolved primary `/subject` ref; preserve edited
  prose, existing tests, creator, assignment and budget on update.
- **Absent task:** assert its complete row, then invoke the existing
  create/start tx owner as a following `:db.fn/call` in that same returned
  transaction data. Its mid-transaction DB sees the new task. Create one
  agent, plan subject, settings, namespace responsibility and opening facts;
  assign `/agent` in that transaction. Do not call `arm!` in a tx function.
- **Task exists without agent:** use that same create path. Do not mint a
  second task, lose author edits or mistake unassigned for nonexistent.
- **Task already assigned:** preserve that agent even when archived or its
  graph is absent. Genuine recurrence reopens the same task's acceptance
  state without changing the budget. Record the occurrence update and its
  durable listen relation; never call create/start merely because a
  process-local routing entry is missing. The armer recovers from facts.
- **Explicit start/resume:** retain current `start-tx` semantics: an existing
  task can resume only its same agent, with a deliberately larger budget.
  Distinguish that from trigger delivery, which never raises a budget.
- **`seon.task/trigger! [request]`** submits the tx call and returns the
  changed task's status from the successful report. Flat refusal on failure;
  no claimed created task ID from a hash alone. Keep the composable tx-call
  available to the error and future merge owners, avoiding an outer
  transaction nested inside their writer.

`error/commit-call` must compose its occurrence operations **before** the
nested task call, which therefore sees the just-updated root/occurrence.
Keep `error/recording` as the sole preparation/blob/observation owner and
counts at its current writer seam. Do not let detector code recurse into
recording or task construction. Preserve the existing load-cycle boundary
(`issue.clj:15–31`); resolve the one necessary cross-owner Var once, or carry
it in existing explicit construction inputs. No per-trigger registry scan
or `requiring-resolve` ladder.

Do not auto-start every Markdown note or every detector census result at
publication. Indexing and `generate!` remain data population; explicit
trigger admission is the bounded work request. Route actual fault
occurrences and explicit detector/work requests through `trigger-call`.
Pure `my.program/breaks` still launches nothing. An ordinary chat message
stays conversation; an explicit work request may be a task whose subject
is that message and whose tests define completion. A reply's wake coverage
alone never proves the work fulfilled.

### A repeated occurrence must produce a real, recoverable wake

At the inspected tree, `wake/route!` supports runtime listen patterns
(`wake.clj:402–443,503–568`) but `wake/agent-wake-datoms` only reads
schema-recipient AVET datoms (`:287–317`). Consequently an authored pattern
can deliver LOOK while `turn/unanswered-wakes` derives no work from that pattern. Do not
claim the existing callback alone implements D2.

Use **one existing matching authority** for both delivery and durable
wake derivation. Extend `agent-wake-datoms` to include that agent's runtime
patterns using their declared attribute/entity/value constraints and the
same codec semantics as `wake-matchers`. Retain the schema-recipient fast
path, indexed seeks, `added` filtering, tuple shape `[entity tx attribute]`,
deduplication and bounded work. Do not scan every entity or introduce a
new listen table, wake queue or in-memory occurrence counter. Reuse an
existing equal pattern inside the writer instead of appending duplicate
listener components on each repeat. A runtime
pattern is already an explicit request to wake on its matching facts;
turn's opening/listened selection must include it in both modes without
pretending that its datom value is an agent ref.

For an error task, store **entity-constrained** runtime patterns on its
agent for the linked occurrence's `/count` and the error root's
`/occurrences`. This is a subscription, not a copied count. A new occurrence
changes root membership; another recurrence in the same existing occurrence
row changes `/count`. Add the new occurrence's count subscription in the
same task/occurrence transaction. The existing route rederives patterns
before dispatch when runtime/listen facts change. Coalesce duplicate matches
for the same agent/datom. Generic trigger subjects use their actual declared
occurrence/update evidence; an unchanged detector census is not an event.

Fault-origin updates are inside activity: mark the existing error root's
signature and occurrence's process attribute with `:seon.wake/inside true`
(or consume 1c's equivalent landed declaration). Their docstrings state
“Presence identifies population fault activity; its wake never refills an
agent's turn bound.” They retain their type/cardinality/identity semantics;
this is a property change, not an assignee stamp. No `/from` fabrication or
new task notification per occurrence. Keep the one initial error notification
message directed to the task agent, keyed by the existing signature/recipient
identity. Repetition changes evidence and delivers a wake; it does not add
another message. Do not notify every namespace agent or the fixed root in
parallel with this assignment.

A constrained subscription must not become a wildcard when its entity is
retracted. Consume the 1c deletion invariant; if absent, extend the existing
final-report check once its owner releases it: retract the affected listener
component in the same transaction, or refuse the deletion while that
listener survives. No pre-read or silent broadening. This means an error
root with live constrained listeners needs their same-transaction cleanup;
the optional task `/errors` edge itself still sweeps. Ordinary optional
function/schema subject deletion can sweep without deleting the task.

The existing `turn/latest-answering-turn-t` at `turn.clj:2955` and
`unanswered-wakes` at `:2982` remain authoritative: accepted ordinary reply
covers wakes at or before its opening basis; merely opening or a system
turn does not. A recurrence during a turn remains pending for the next one.
Repeated wakes cannot evade total task-budget exhaustion. Channel loss is
free because a boot/arm prime rederives work from the durable datoms.

## Render pair and opening consolidation

The live source path is already present:
`cluster/agent.clj:224–249` emits `issue.opening/source` with
`:seon.eval/origin`; `turn.clj:1900–1945` collects generated source from walk
calls. Meanwhile `issue.clj:737–752` declares a text-returning entity AI
pair. `issue/opening.clj:114–136,190–208` owns `:bare` and
`:evidence-first`. This is the duplication to dissolve.

**The task entity pair returns `:seon.render/source`.** Its minimal output
is a thinking comment followed by a read, with the real ID printed through
`seon.repl/source-text`:

```clojure
;; I should inspect this task's acceptance evidence before changing its subject.
(seon.task/status {:seon.task/id "<actual-task-id>"})
```

Include the task instructions exactly once as thinking-comment lines through
the existing opening helpers, including under :bare; never splice authored
prose as executable source. The status result omits the stored /problem
member so it does not also fit the stored task schema and select its source
pair again.
The exact check form may be taught in comments, not executed as a generated
write. `render-status-ai` renders the status result once as concise value
text, including tests, stale/missing evidence and the executable acceptance
form. `render-html` shows the same facts as hiccup without presentation
clipping. Keep query-work cuts explicit and separate from the AI profile.
Status/error views must not select the entity source pair recursively.

Move `issue.opening` to `task.opening` as the existing pair's helper,
retaining its declared dial while comparing the openings. Remove its call
and task query from `cluster.agent/render-identity-ai` in the same cut.
The identity block returns identity/help only. Task source comes from
`:seon.task/_agent` and the task schema pair. Preserve
`:seon.eval/origin` using the reached task's installed identity when
`declared-sources` builds its evaluation entries; do not infer origin from
a form's textual spelling or add a stored render marker. Carry that origin
through changed-read regeneration (`turn.clj:2034–2088`), so the existing
origin-qualified status read is not refused for its turn-accounting inputs.

**Distance-1 decision, byte evidence first.** The current root-only
`declared-acquisition` is now `render/walk.clj:702–738`, not the research's
old line 667. Extend the existing declared-concern expansion so a reached
task at the agent's distance 1 contributes its own declared units under
existing query-work bounds. This is declared concern expansion, not a
blanket increase in arbitrary graph distance. Use identity/output-based
visited tracking in this invocation, deterministic declared-unit order,
existing distance/node bounds and honest continuation values. A task member
cycle terminates and a shared function/test is emitted once. No HTML clipping
or second token fitter in the walk.

The accepted plan §5 requires reproducing evidence-first source through the
generic path before deleting the old linked-read emission. Record exact
before/after bytes and the intentional substitutions (family/public symbols,
the thinking comment, removal of the copied plan objective). Compare ordered
parsed read forms, their selected producers and evidence as well as bytes;
normalization must not erase a dropped read, changed selector or lost origin.
The generic expansion must preserve the tested function/test/error evidence
and expose missing pairs honestly. Remove the now-redundant manual linked
reads from the opening helper only after that parity proof; do not leave
both implementations as dial-dependent acquisition paths. If a pair cannot
express the required existing read, make the smallest correction in that
pair owner rather than starting 3b's broad campaign. At a larger rendering
contract decision, stop with the three priced options.

The former raw-map opening is a recorded defect, not an acceptance golden.
Tests must verify that source is evaluated and its result displayed, not
merely that a string contains “Task”. The `:bare` and `:evidence-first`
openings each have exactly one status evaluation and no repeated problem
text in the plan. Ordinary user-written reads still participate in the same
since-diff mechanism. Previous stored shown text remains byte-identical.

## Per-file implementation instructions and detector census

These are dated source anchors, not a permanent owner roster. Refresh after
the bridge and 1a land; dirty files move during design.

| Owner / inspected anchor | Required change |
|---|---|
| `src/seon/issue.clj:158,245,283,435,470,481,511,534,593` → `src/seon/task.clj` | Rename citation/index/adopt/generate owners and their attrs; preserve invalid-note refusal, identity-only admission protection, exact replacement and authored prose. `subject-id` remains the shared fingerprint. Add the one trigger-call/trigger! seam; make detector and primary-subject unavailability explicit. |
| `src/seon/issue.clj:642,705,737,745,829,843,879,941,995,1027,1041,1104` | Status/read pair separation, source entity pair, acceptance checks, writer creation and occurrence update, same-agent resume, budget exhaustion and guarded tests. Do not precompute task/agent existence before enqueue. |
| `src/my/issue.clj:1–35` | Remove the thin duplicate facade; convert all direct/generated callers to the callable `seon.task` owner. No `src/my/task.clj`. |
| `src/seon/issue/opening.clj:1–232` → `src/seon/task/opening.clj` | Move helper/dial/forms, retain baseline evidence, then remove duplicate linked-read acquisition after generic parity. No new template dispatch or registry. |
| `src/seon/issue/detect.clj` → `src/seon/task/detect.clj` | Rename four existing detectors, add unresolved-errors using stored complete error evidence and current test authority. Carry explicit DB/projection; contract private helpers; adopt D12 errors. |
| `src/seon/cluster/agent.clj:128,153,224,421,706,899` | Additive responsibility writer and plural reader, task-free identity renderer, retain creation/arming owner. Every existing responsibility call/return changes with `/agents`; no first-assignee wins branch. |
| `src/seon/agent.clj:11`; `src/my/agent.clj:10`; `src/seon/problems.clj:262`; `src/seon/render/ns.clj:410`; `src/seon/sci/eval.clj:269` | Plural identity/help/problem/page readers and supplied symbols. Rename `steward-call` → `assign-namespace-call`, `steward-of` → `agents-of`, and `unstewarded-namespaces` → `unassigned-namespaces` if 1c has not already landed its chosen names. Update written-by and every caller, including quoted Vars. |
| `src/seon/error.clj:198,1496,1534,1643,1711,2051–2140` | D13 unchanged; remove namespace recipient selection, compose occurrence → trigger-call; notification to task agent only. Repair-fault reads join tasks, own-turn fault reads remain. Coordinate exact held spans with 1a. |
| `src/seon/cluster/wake.clj:94,161,287,319,402,445` | Retain declared arming and schema routing; share authored-pattern matching with durable wake reads; no new graph/channel or callback write. Verify all wake modes and codecs. |
| `src/seon/turn.clj:1900,2034,2602,2617,2655,2692,2887,2955,2982,4973` | Task names/origin and pattern-inclusive wake evidence; preserve ordinary-answer basis and total budget. No turn-loop rewrite. |
| `src/seon/plan.clj:687–844` | Task done-query, tests and settlement names; missing subject/detector cannot settle. Plan item subject retains the relation; remove initial objective copying at creation. |
| `src/seon/render/walk.clj:91,376,483,535,702,756`; `src/seon/render.clj:257,270,588,692,1453` | Generic concern expansion, source selection/provenance and bounded dedup only. Consume compiled bridge APIs. Preserve namespace-authored renderer precedence and selector-derived shapes. |
| `src/seon/render/ns.clj:844,876`; `src/seon/render/test.clj:40,91`; `src/seon/render/transcript.clj:1569`; `src/seon/render/block.clj:61` | Reuse function/test/HTML pairs and block identity. Convert references and any minimal parity gap; no ranked pair campaign. |
| `src/my/program.clj:13,210–233`; `resources/seon/schemas/seon.program.edn:172` | Prospective task plans use same detector/subject identity as generation. Rename `/issues` result to `/tasks`. Keep the read pure and its explicit launch-unavailable evidence. |
| `src/seon/bootstrap.clj:37`; `src/seon/cluster.clj:1932,2549`; `src/seon/cluster/source.clj:559` | Requires/dynamic resolution, indexing/adoption result keys. Preserve publication lineage and task worker facts, never copy EIDs across branches. |
| `script/seon/dev/issues.clj:14`; `bin/issues-index`; `test/seon/dev/issues_test.clj` | Keep the human note command/path and frontmatter grammar, call task owners and read task result keys. Historical document citations remain literal input. |
| `src/seon/db.clj:3988,4052,4075` and landed successors | Preserve generic append-only/creator guards and owned-value validation. Add the listener deletion invariant only if 1c has not landed it; no bridge/writer-diet work. |
| Resources/config in schema tables; `test/seon/schema/datahike_parity.edn` | Rename declarations and expected native attributes together. Regenerate parity evidence through its existing oracle after walker release; do not hand-edit the giant EDN line. |
| `AGENTS.md` task/namespace-agent/detector/plan-of-refusal vocabulary; affected current skills/docs | Update current claims with landed evidence in this commit. Leave dated research and quoted old bytes dated; do not rename the PRD directory or maintain a second working-edge schedule. |

**Detector census, 2026-09-21 work-item date:**

| Current detector | Source | Subjects / preserved standard |
|---|---|---|
| `seon.issue.detect/entity-map-without-pair` | `src/seon/issue/detect.clj:63` | `:seon.schema/key`; stored entity maps without both pair properties, with the existing witnessed component exclusion. Do not silently widen its admission census during a rename. |
| `seon.issue.detect/public-without-doc` | `:171` | `:seon.fn/sym`; source-bearing public declarations lacking docs, with existing form-span exclusions/root scope. |
| `seon.issue.detect/public-without-contract` | `:216` | `:seon.fn/sym`; existing public contract standard and provenance exclusions. Wave 2 owns broader contract coverage; this rename does not claim all private functions are covered. |
| `seon.issue.detect/public-without-reaching-test` | `:271` | `:seon.fn/sym`; one shared gate-set derivation; missing indexed reach is not proof of no test execution. |
| `seon.program/unresolved-callers` | symbol referenced in `src/my/program.clj:215`; no definition in inspected `src/seon/program.cljc` | Prospective refusal-plan identity, explicitly unavailable for launch today. Do not mint a dummy detector/program row or claim repair-task launch is implemented. |
| `seon.task.detect/unresolved-errors` | new in renamed detector owner | Error-signature subjects; positive, current test evidence decides repair, not elapsed quiet or the task's own status. |

The four existing detector functions are the whole inspected detector
namespace's public population; its private support functions are not extra
detectors. `generate!` can still accept another properly contracted ordinary
program function. This table is a dated census, not a whitelist.

## Canonical regressions and named live proof

Move the existing task tests with their namespace identities:
`test/seon/issue_test.clj` → `task_test.clj`, `issue_generate_test.clj` →
`task_generate_test.clj`, `issue_settlement_test.clj` →
`task_settlement_test.clj`, `issue_deletion_test.clj` →
`task_deletion_test.clj`, `issue/detect_test.clj` → `task/detect_test.clj`.
Extend these owners and the listed wake/arming/render tests, not a new test
runner. Canonical fixture helpers at inspection are
`test_support.clj:309` transacted!, `:772` await-event!, `:1031`
with-database, `:1067` program-fn-row and `:1099` seed-cluster!.
Read the actual helper contracts after prerequisite landings.

| Recurring proof | Required assertions, beyond a green return |
|---|---|
| **Existing fault task wakes only its agent and creates nothing** | Seed two tasks/agents in the same namespace through real writers. Commit a further occurrence for A through error recording. Task and agent identity sets are unchanged; B has no matching new wake; A's occurrence evidence/count advances, no new message appears, and both route delivery and unanswered-wakes name the new transaction. Existing assignment/budget remain byte-identical. |
| **New occurrence creates one task and one agent** | Previously absent D13 signature → one error root, one task, one new agent, its task assignment, plan subject and namespace membership in the admitted transaction. Before/after identity-set difference is exactly one each. Start real graph infrastructure and await agent arming plus stored opening through the existing armer; never manually arm the created agent. |
| **Two occurrences with the same D13 identity create one task** | Change time, process/basis/message and offending bytes while keeping the D13 tuple fixed. Assert equal signature/task ID, one task/agent/message, correct occurrence/count evidence and a second durable wake. Include two independently prepared concurrent requests against the same earlier DB; let the serial writer decide. Also vary each D13 identity dimension to prove distinguishability, not only equality. |
| **Same aggregated occurrence row still wakes** | Same signature/process/turn updates the existing occurrence `/count` rather than adding a row. Its new datom `:t` is pending after the previous ordinary reply. No forced retraction/reassertion of task/agent or message/to. |
| **Conflict fingerprint deduplicates** | Canonical synthetic conflict facet through real error recording and task writer: equal program location/operation/facets, different both-source/basis evidence → same task and root worker; different program identity → distinct task. Both sides' evidence survives and is readable. No merge/automatic reapply is executed. Record the wave-4 real-conflict regression still owed. |
| **Writer refusal is atomic** | Unknown detector, missing subject, invalid namespace/budget or invalid assigned tests returns the declared diagnostic; no partial task/agent/plan/notification/occurrence from the refused transaction. Existing task without agent is assigned once. Repeated explicit start cannot create another worker; resume raises only the same worker's budget. |
| **Deletion dial** | Start with positive subject/task facts. Retract a primary function/schema subject: `/subject` sweeps, task remains, history/as-of shows the prior link, acceptance becomes unavailable rather than done. Also check optional auxiliary topic links sweep without changing the primary subject. Assigned acceptance-test deletion without authority refuses atomically. Required citation/file deletion refuses unless the citation/task is removed in the same transaction. Task deletion cascades citation children but not cited files/peer tasks/agents. Retain the existing started-note deletion guard tests. |
| **Listener constraint and recovery** | Subject/occurrence deletion cannot turn an entity-constrained listener into an attribute-wide subscription: surviving pattern refuses, same-transaction component cleanup succeeds. New listener plus occurrence in one transaction is delivered. Drop/coalesce channel wakes, rearm/restart and derive pending work from facts. No in-memory-only update evidence. |
| **Answeredness and budget** | System turn and mere opening do not answer a wake. Accepted ordinary/virtual reply covers its opening basis only. A recurrence committed during a turn remains pending. Multiple matches in one transaction do not create multiple paid turns. Inside repeats do not refill task budget; exhaustion remains effective and an explicit resume is required. |
| **Namespace relation** | Two agents responsible for one namespace and one agent for two namespaces round-trip as sets; archival retains responsibility. Agent REPL namespace stays one independent ref. No first-assignee-wins behavior, singular pull shape, namespace broadcast or duplicated worker. |
| **Both opening dials, generic expansion** | Real SCI system-turn capture before/after under :bare and :evidence-first, with linked function/test/error and missing-pair cases. Assert source contracts, selected pair, exact intended forms, exactly one status evaluation, task origin, no recursive source generation and no duplicated problem/objective. Shared links/cyclic task members terminate with unique blocks and explicit work-bound evidence. |
| **Status/settlement honesty** | No tests, unrun/stale/red tests, refused detector, missing detector/subject, imported resolved/superseded note, verified tests and a later recurrence. Only positive current acceptance evidence settles runtime work; no empty set or quiet period proves health. Missing task returns not-found, not an empty green view. |
| **Rename and prospective plan parity** | Canonical indexing/adoption preserves note slug, edited task prose, assignment and added tests. Declared pairs resolve to contracted current functions. No retired current schema/program rows; `my.program/breaks` fingerprint equals generation for a real installed detector. The unavailable unresolved-callers case remains explicitly unavailable. |
| **Renderer lifetime** | Changed linked evidence appends the right generated read and retains task origin; old shown text is unchanged. HTML observes unclipped entity/status content through the same pair. Query cuts identify their bound; no second presentation clipping. |

Named live proof: **wave3a-task-trigger-opening**. After incompatible schema
landings, use only `tmp/wave3a-task-root`, cluster `wave3a-task`, via the
operator and root/cluster-qualified MCP. Seed the canonical fixture with two
namespace agents and task evidence, use no-provider replies, submit a fault,
observe durable task/agent identities and armer completion, answer, submit a
same-identity recurrence and observe the second pending wake without another
worker/message. Observe the task and linked facts on the agent page; an HTTP
success or adoption marker alone is not paint evidence. Restart this owned
root once to prove durable wake recovery and retained shown text; record
process identities, source adoption identity, signature/task/agent IDs,
transaction bases, exact source/shown bytes and phase durations. Down and
remove only this root after its process exits. If MCP is unavailable, report
it immediately and record the unavailable live proof; never improvise a
prepl transport. Default's final reset/adoption/page proof remains the
orchestrator's obligation.

Implementation-only commands, expanded to every changed owner at landing:

```bash
clojure -M -e "(require 'seon.task 'seon.task.detect 'seon.task.opening 'seon.cluster.agent 'seon.cluster.wake 'seon.error 'seon.turn 'seon.plan 'seon.agent 'my.agent 'my.program 'seon.render 'seon.render.walk 'seon.render.ns 'seon.render.test 'seon.render.transcript 'seon.cluster.source 'seon.cluster 'seon.bootstrap)"
bin/test-fast --paths <all owned changed paths> -- seon.task-test seon.task-generate-test seon.task-settlement-test seon.task-deletion-test seon.task.detect-test
bin/test-fast --paths <all owned changed paths> -- seon.cluster.wake-test seon.cluster.agent-arming-test seon.cluster.agent-namespace-test seon.cluster.agent-identity-test seon.turn-test seon.error-test
bin/test-fast --paths <all owned changed paths> -- seon.render.entity-pairs-test seon.render.web-debug-test my.program-test seon.dev.issues-test seon.reset-edges-test seon.incremental-publication-test
```

Add graph-selected callers and the affected DB/schema/native-parity tests.
Run serially; report which members executed versus reused recorded evidence.
The orchestrator runs the cold `bin/test --paths … -- …` and `--platform`
after landing/reset; none of these commands was run by the design lane.

## Reset batch and held-path coordination

**RESET NEEDED; no migration and no compatibility period.** Exact 3a batch:

- Retire every stored `:seon.issue/*` attribute listed in the schema table
  and all four `:seon.issue.citation/*` attributes; install the task/citation
  successors, `/related` in place of `/issues`, and new `/subject` and
  `/messages`. The primary subject ref is optional/sweep after creation;
  missing primary evidence cannot pass a detected task's acceptance check.
- Retire the old entity/view declarations, renderer/detector/public function
  identities and `my.issue` facade with every current caller. Replace the
  declaration property `/cites` and its references/guard property values.
- Retire `:seon.ns/steward` (one ref), install `:seon.ns/agents` (many refs);
  retire the scalar read projection `:my.agent/steward`, install plural
  read projection `/agents`. The latter is a contract change, not a new
  stored agent relation.
- Retire the legacy stored error responsibility ref and derived
  `/of-steward` declaration if 1c has not already done so. Add the two
  existing-error-attribute inside-wake properties. No error occurrence
  identity, type or owning component changes.
- Retire config `/issue-opening`, install `/task-opening` and its default/
  overlays. Change the alias for `:seon.eval/origin`, task unit/property
  symbols and `seon.program` result references together; their ref/value
  semantics do not change. All additional request/view/error declarations
  above are non-stored except members explicitly owned by the error model.

The current charter is
[working edge, ~02:00 UTC](unsettled.md)
(the OVERNIGHT CHARTER at inspection lines 5175–5210). It batches 1a attributes, 1d changes and the bridge population
stamp after steps 1–2 and sweeps. 1a's original list retires
`:seon.error/unclassified`, `/refusal`, `/kind`, `/class`,
`:seon.instrument/contract-violated`, `/registration-failed`, and requires
root layer/operation; its later row-acquisition slice adds
`:seon.sci.eval/row-member`. 1d's three change groups cover four names:
`:my.plan.item/needs`, `:seon.ai.attempt/model`,
`:seon.config.agent/show-all-settings`, and
`:seon.config.ai/retain-reasoning`. Step 3 adds
`:seon.source/population-digest`. Refresh their actual pending status rather
than claiming the design document's inventory has been installed. If that
batch precedes 3a, 3a belongs to the next clean-tree reset, not a reason to
hold or repeat another lane's reset.

**History distinction:** normal program/task deletion is
`[:db/retractEntity …]`; with history retained and no noHistory, prior facts
remain queryable through `history`/`as-of`/`since`. Retraction does not keep
old executable Vars, schema registrations or compatibility APIs callable.
A destructive reset discards the old store's query route; it cannot promise
to preserve that history. Dated documents and Git retain the retired source.
Do not conflate ordinary historical queries with a data migration promise.

| Running lane / source of ownership | Held files intersecting or relevant to 3a | Coordination boundary |
|---|---|---|
| **publication-dissolution** — [spec owned paths](publication-dissolution-spec-2026-09-20.md#owned-paths) | `src/seon/cluster/source.clj`, publication region of `src/seon/cluster.clj`, `src/seon/fn.clj`, `src/seon/test/cache.clj`, `script/seon/fresh_operator.clj`, `bin/test`, `bin/seon-hook`, source/finding resources and their tests, including `test/seon/cluster/publication_findings_test.clj` observed untracked | Wait for its current toolchain/publication slice to land before converting task indexing/adoption callers. Do not absorb its cache, publisher or harness changes. `cluster.clj` was dirty at design entry. |
| **bridge-step2-walker** — [spec](bridge-step2-walker-spec-2026-09-21.md) | `src/seon/schema.clj`, `src/seon/schema/internal.cljc`, `src/seon/schema/datahike.clj`, retirement of `src/seon/schema/form.cljc`, its full caller cut including `src/seon/issue.clj`, `src/seon/render/walk.clj`, `src/seon/render.clj`, `src/seon/render/value.clj`, `src/seon/agent.clj`, `src/seon/db.clj`; `test/seon/schema/datahike_test.clj`, `test/seon/schema/datahike_parity.edn`, `test/seon/instrument_test.clj`, `test/seon/flow_test.clj`, `test/seon/db_test.clj` | Task citation/property readers must use its landed compiled API, not port the deleted raw-form calls. The schema parity file was untracked at design entry; it is foreign work. Coordinate all discovered caller intersections, not only the spec's short owner list. |
| **error-family-1a** — [landing/continuation record](../research/error-family-1a-2026-09-19.md) | `src/seon/error.clj`, `src/seon/error/refusal.clj`, `src/seon/instrument.clj`, `src/seon/sci/admit.clj`, `src/seon/sci/eval.clj`, `src/seon/sci/kernel.clj`, `resources/seon/schemas/seon.error*.edn`, `seon.instrument.edn`, `seon.db.edn`, `seon.sci.eval.edn`; error/instrument/SCI/schema tests and `test/seon/test_support.clj` when held | Consume the final D13/base/facet/row-acquisition contract. Only after release change recording/routing and exact task-related callers. Do not bring back `/kind` to get an old task test green. |

Ownership is concurrent, not permanent. Refresh `git status`, landing notes
and the orchestrator's assignment before touching any listed file. A clean
file from a landed slice is available once no current owner holds it. This
design lane never messages those lanes or attempts their gates. Subsequent
bridge stamp/writer/instrument lanes can hold the same files; coordinate
them at actual launch rather than using today's roster as permission.

## Dependency ledger — authority and existing mechanisms

Inspected gitlinks: Datahike `e11845bac78e1241bca0766ddc07d978bd63d74a`,
Malli `606083c5c5b388e84d169c7080af33ed3ec242ae`, core.async
`dc35f3e0d7bc2eef502e77982f48641f025c8051`, SCI
`fcbd8862800e638dc0f8f5521111f999279cbcd2`. No fork change is proposed.
Read actual checkout bytes: dependency work can advance beside this lane.

| Mechanism | Read seam and consequence |
|---|---|
| Identity upsert and effective datoms | `reference-code/datahike/src/datahike/db/transaction.cljc:585–625,641–713`: identity resolves against the current DB; unchanged assertions have no effective datom. Task/agent reassertion is not a wake. First-party reuse: `issue/subject-id :470`, `create-tx :879`. |
| Mid-transaction function | `…/db/transaction.cljc:1153–1154`: apply function to the progressive DB, splice returned operations. Earlier occurrence/task rows are visible to the following task/create call. Tempid resolution can retry (`:844–855`), so no graph/HTTP/filesystem effect or fresh event minting inside the tx function. Supply event time/identity before submission. |
| Serial writer and refusal | `reference-code/datahike/src/datahike/writer.cljc:130–218`: rejected operation retains old DB. `…/db/transaction.cljc:1206–1216` validates the final report before acceptance. Task assignment, creation and evidence refusal are tested here, not in a preflight mirror. |
| Retraction and ownership | `…/db/transaction.cljc:998–1015` sweeps incoming refs; `:831–836` cascades components; `:440–484` retains temporal datoms subject to history configuration. Optional refs sweep, required surviving values refuse through Seon's final owner checks. First-party: `db/write-report-error`, `write-owned-values-error`, existing task deletion tests. |
| Notification delivery | Checked-out `reference-code/datahike/src/datahike/writer.cljc:393–427` now settles the result before notifying and catches each listener's exception. The older wake docstring/skill's before-deliver/no-catch description is stale at these bytes. Preserve the stronger nonblocking/no-throw callback discipline; do not cite the old incident as current dependency behavior. Correct this touched owner comment with implementation. |
| Durable listens and arming | `src/seon/cluster/wake.clj:287,402,503`; `src/seon/cluster/agent.clj:899`; `resources/seon/schemas/seon.agent.edn:73–79`: identity assertion wakes the existing armer. Runtime patterns already declare subscriptions; completing their durable wake derivation is the one seam change needed for repeat updates. |
| Loss semantics / Flow | `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:168–198` and `…/flow/impl.clj:174–217`: use existing lifecycle/start/resume and workload; stopping a graph is not proof of child exit. First-party armer's prime rederives facts; no new proc is required. |
| Pull bounds and result grammar | `reference-code/datahike/src/datahike/pull_api.cljc:16,238–243,315–357`: many pulls default to 1,000 and recursive reads can become id-only. Task counts, listeners, acceptance and required evidence use complete bounded indexed reads or report an explicit cut; wildcard pull alone is not completeness. |
| SCI generated forms | `reference-code/sci/src/sci/core.cljc:260,331,345` supplies intern/init/fork; first-party `turn/declared-sources :1900` and `system-turn` own evaluation/storage. Use the cluster's actual acquired context and existing fork-for-turn lifetime; older flow-skill prose about never regenerating a fork does not override current AGENTS. |
| Render contracts | `src/seon/render.clj:257–275,588,1453` recognizes source by declared output, not its textual appearance; `render/walk.clj:702` owns concern ordering, `render/block.clj:61` block identity. One entity pair, ordinary read-view result pair, no template registry. |
| Test authority | `src/seon/issue.clj:829–870` already requires positive verified tests; `src/seon/plan.clj:687–844` owns settlement; `seon.test/recorded-result` and `verified?` own current evidence. Do not create a task-specific test runner or a second reach calculation. |

## What 3a must NOT build

- No template entity, template registry, per-task type/kind discriminator,
  stamped “fault task”/“conflict task”, or conversation entity. A detected
  defect is a task whose detector names its subject; conflict evidence is
  an error observation; conversation derives from message facts.
- No scheduler, dispatcher, agent pool, retry daemon, new Flow graph,
  separate work queue, polling timer or time-based fault-repair heuristic.
- No second task family or `my.task` resurrection; no compatibility aliases
  preserving `seon.issue` or `my.issue` after caller conversion.
- No namespace broadcast, shared fixed-root conflict worker, plural active
  workers on one task, or agent-per-occurrence creation.
- No second D13 hash, timestamp/source-byte conflict identity, copied error
  occurrence payload, copied task problem in the plan or derived count flag.
- No branch isolation/merge/acceptance/export implementation from wave 4;
  no five-template live campaign from 3c; no ranked render-pair campaign,
  authoring API or message-thread unit from 3b.
- No bridge API revival, publisher redesign, full contract campaign,
  dependency fork edit, general error predicate, migration, production
  regex, test bound increase or clipping outside the established AI owners.

## Estimate and deletion budget

The inspected old family is **1,730 physical lines**:
`issue.clj` 1,150, `issue/opening.clj` 232, `issue/detect.clj` 313 and
`my/issue.clj` 35. This is a rename/review surface, not 1,730 deleted lines.
The two old schema files add 82 physical lines. Counts are dated, not
runtime invariants. Refresh against the landed tree and report actual
numstat; do not delete behavior to meet a line target.

Expected true dissolution: the 35-line facade, the identity renderer's
parallel task-source injection, manual linked reads superseded by generic
concern expansion, singular namespace assignment/recipient selection and
the plan-objective copy. Most note indexing, test authority, identity,
error recording and graph lifecycle code is retained. New work is the
composable trigger decision, precise contracts, the missing durable-pattern
wake path and its proofs; it is not a mechanical namespace substitution.

Budget **4–6 lane-days**: 1–1.5 for the complete rename/schema/caller slice,
1–1.5 for writer/dedup/wake integration, 1–1.5 for opening consolidation and
canonical regressions, 0.5–1.5 for live proof, cleanup and landing evidence.
Prerequisite/path-release/reset/cold-gate queues are additional. If the
landed 1c already supplies D2 and authored-pattern wake parity, consume its
proof and deduct that implementation work; do not build an alternate path.

## Dated caller census

Snapshot captured during the 2026-09-20 session (clock 07:08 UTC), using
the requested **2026-09-21 work-item date**. Census HEAD: `d487834604718729a8442d998d1462892b7e4621`.
This is `rg` over the shared working tree, including dirty/untracked paths,
not a claim those bytes are committed or adopted. SHA-256 values below
identify the exact read bytes. The principal task files were clean; the
shared schema/error/SCI/publication paths were moving. Re-run at launch.

Scope: `src test resources script bin config deps.edn`. The first pattern
includes `:seon.issue/*`, namespaced maps, qualified symbols, comments and
quoted/generated forms. Matching **lines**, not lexical occurrence counts,
are reported. Consecutive matching line numbers are compressed into ranges;
every matching line is included. The second census catches aliases the
first cannot. The third closes the many-to-many caller boundary.

```bash
rg -n 'seon\.issue|my\.issue' src test resources script bin config deps.edn
rg -n '\bissue(?:\.opening)?/' src test resources script bin config deps.edn
rg -n ':seon\.ns/steward|my\.agent/steward|steward-call|steward-of|of-steward' src test resources script bin config deps.edn
rg -n 'issue-opening|seon.program/issues|src/seon/issue|src/my/issue' config src test resources script bin deps.edn
```

The additional last search covers non-family spellings in the config dial,
prospective result and file-path strings. `bin/issues-index` calls the
unchanged `script/seon/dev/issues.clj` adapter even though the first pattern
has no hit in the launcher itself. Historical notes and research under
`docs/` are intentionally not a mass rewrite. Current vocabulary/help and
affected authority claims are converted with the implementation.

### Literal family/public/schema references

**38 files / 956 matching lines.**

| File | Matching line numbers |
|---|---|
| `resources/seon/schemas/seon.agent.edn` | 8 |
| `resources/seon/schemas/seon.config.render.edn` | 9 |
| `resources/seon/schemas/seon.eval.edn` | 1 |
| `resources/seon/schemas/seon.issue.citation.edn` | 1, 4, 6–8, 10–13 |
| `resources/seon/schemas/seon.issue.edn` | 1, 8, 10–12, 14, 16, 18, 20–23, 25–26, 41–43, 46–69 |
| `resources/seon/schemas/seon.program.edn` | 172 |
| `script/seon/dev/issues.clj` | 14–15, 17–18, 23–24, 33, 35–38 |
| `src/my/issue.clj` | 1, 3, 8, 17–21, 30–31 |
| `src/my/program.clj` | 13, 219–221, 225–227, 229, 233 |
| `src/seon/bootstrap.clj` | 37 |
| `src/seon/cluster.clj` | 1932, 2549–2550 |
| `src/seon/cluster/agent.clj` | 78, 239–240, 936 |
| `src/seon/cluster/source.clj` | 559, 561, 563–564 |
| `src/seon/cluster/wake.clj` | 131 |
| `src/seon/error.clj` | 2080 |
| `src/seon/issue.clj` | 1, 15, 76, 88, 106–114, 119, 123, 133, 136–137, 140, 161, 170, 174–175, 211–212, 228–229, 236, 239–243, 260–261, 263–265, 273, 294, 299, 303, 307–310, 312, 314–315, 320, 323, 329, 331–332, 334, 340, 343, 348, 350, 352–355, 359, 365, 370–379, 381–382, 384, 392–393, 399–400, 403, 405–409, 417, 423, 425, 427, 429, 431–433, 441, 443, 456–457, 463, 477, 479, 490, 495–496, 499, 501–505, 507–509, 514, 516, 519, 525, 528, 543, 545, 548, 551, 554, 557–558, 561, 565, 571–572, 575–579, 584–586, 588–591, 597, 599, 608, 613, 618, 620–621, 623–624, 629, 645–664, 667–668, 683, 687, 690–692, 694–696, 701, 703, 709–715, 718, 720, 733–734, 741, 747, 751, 757–763, 765, 770, 777–780, 782, 785, 788–789, 791–795, 799, 812, 816–817, 835, 837, 848–849, 851, 857–858, 860–861, 869, 877, 882–883, 889–891, 894, 897–904, 906, 909, 915, 920, 924, 926, 931–932, 934, 936, 939, 944–945, 951–956, 960–965, 977–978, 981–982, 985, 988–989, 1001–1004, 1009, 1017, 1023, 1030–1031, 1039, 1044–1048, 1052–1055, 1057–1063, 1069–1073, 1079, 1084–1085, 1089–1092, 1097–1098, 1108–1109, 1114, 1119–1120, 1122, 1128–1130, 1134–1136, 1138–1139, 1142–1150 |
| `src/seon/issue/detect.clj` | 1, 7–8, 49–50, 58, 61, 159–161, 168, 203–205, 213, 256–258, 268 |
| `src/seon/issue/opening.clj` | 1, 7, 42–45, 48–53, 55–56, 59, 61, 63–65, 69, 81, 115, 120, 128, 139, 158, 170, 179, 182–183, 192, 201, 206, 216, 219, 221–222, 228, 231 |
| `src/seon/plan.clj` | 21, 697–699, 777, 791, 816, 818 |
| `src/seon/render/transcript.clj` | 1569 |
| `src/seon/turn.clj` | 2036–2037, 2062, 2667, 2702–2703, 2887–2888, 2890, 2916–2917, 4973 |
| `test/my/program_test.clj` | 7, 35, 38, 40, 42–43 |
| `test/seon/cluster/agent_arming_test.clj` | 4, 15, 28, 166–171, 174, 176, 178, 181, 189–190, 196, 199, 222 |
| `test/seon/cluster/wake_test.clj` | 269, 307 |
| `test/seon/cluster_test.clj` | 257 |
| `test/seon/dev/issues_test.clj` | 5, 7 |
| `test/seon/incremental_publication_test.clj` | 139–141 |
| `test/seon/issue/detect_test.clj` | 1, 16, 42, 86, 88, 101, 103, 121, 123, 125, 134, 154, 188 |
| `test/seon/issue_deletion_test.clj` | 1, 5, 14–15, 21, 26–29, 48–49, 67–68, 71–73, 75, 78–79, 82, 87–88, 92 |
| `test/seon/issue_generate_test.clj` | 1, 6–7, 10, 19, 22–24, 28–33, 53, 74–77, 80, 82, 85, 89, 94–95, 105–106, 113–115, 118, 125–126, 132, 134–136, 141, 143–145, 154–155, 158–159, 161, 167, 179, 181 |
| `test/seon/issue_settlement_test.clj` | 1, 13, 120, 134–139, 166, 184–185, 188, 201–202, 204, 208, 210, 242–243, 254, 276, 284, 291, 293, 297–304, 306, 308–310, 314, 317, 319–320, 323, 327, 331–335, 338, 341–343, 346–347, 349–351 |
| `test/seon/issue_test.clj` | 1, 12–13, 24–26, 28–32, 34, 36–43, 45–50, 52, 54–71, 73–74, 81–82, 85–89, 92–95, 98–100, 102, 104–106, 111, 113–114, 116, 120–122, 127–134, 140–142, 145–155, 161, 163–164, 166, 169, 171–172, 175, 181–184, 189–191, 193, 195–199, 209–213, 215, 217–218, 220–224, 232–233, 235–236, 238, 241, 243–245, 247, 250, 253, 257–263, 269, 272–277, 298, 306, 311, 330, 333, 336, 338, 342–344, 349–351, 353–354, 359–361, 364, 366–367, 371–372, 375, 377–378, 389–393, 396–398, 400–403, 408, 415–419, 424–425, 429–435, 438, 440, 443, 450–452, 455 |
| `test/seon/render/entity_pairs_test.clj` | 26–32, 44 |
| `test/seon/render/web_debug_test.clj` | 19, 989, 1054–1058, 1064, 1069, 1071 |
| `test/seon/reset_edges_test.clj` | 10, 255–256, 258, 261, 327–328, 339–340 |
| `test/seon/schema/datahike_parity.edn` | 1 |
| `test/seon/test/host_test.clj` | 6–7, 48–49, 52–54, 56, 58 |
| `test/seon/turn_test.clj` | 92–98, 968–969 |

### Alias-qualified calls and generated forms

**37 files / 872 matching lines.**

| File | Matching line numbers |
|---|---|
| `resources/seon/schemas/seon.agent.edn` | 8 |
| `resources/seon/schemas/seon.config.render.edn` | 9 |
| `resources/seon/schemas/seon.eval.edn` | 1 |
| `resources/seon/schemas/seon.issue.edn` | 8, 10–12, 14, 16, 18, 21, 23, 25–26, 41–43, 46–69 |
| `resources/seon/schemas/seon.program.edn` | 172 |
| `script/seon/dev/issues.clj` | 15, 17–18, 23–24, 33, 35–38 |
| `src/my/issue.clj` | 8, 11, 17–21, 24, 30–31, 34 |
| `src/my/program.clj` | 219–221, 225–227, 229, 233 |
| `src/seon/bootstrap.clj` | 37 |
| `src/seon/cluster.clj` | 1932, 2549–2550 |
| `src/seon/cluster/agent.clj` | 239–240, 247, 936 |
| `src/seon/cluster/source.clj` | 559, 561, 563–564 |
| `src/seon/cluster/wake.clj` | 131 |
| `src/seon/error.clj` | 2080 |
| `src/seon/issue.clj` | 15, 76, 88, 106–110, 119, 123, 133, 136–137, 140, 161, 170, 174–175, 228–229, 236, 243, 260–261, 263–265, 273, 294, 299, 303, 308–310, 312, 314–315, 320, 329, 334, 340, 348, 352–355, 365, 370–379, 381–382, 384, 392–393, 399–400, 403, 405–409, 417, 423, 425, 427, 429, 431–433, 441, 443, 456–457, 463, 477, 479, 490, 495–496, 499, 501–505, 507–509, 514, 516, 519, 525, 528, 543, 545, 548, 551, 554, 557–558, 561, 565, 571–572, 575–579, 584–586, 588–591, 597, 599, 608, 613, 618, 620–621, 623–624, 629, 645–658, 660–664, 667–668, 687, 690–692, 694–696, 701, 703, 709–715, 718, 733–734, 741, 747, 751, 757–761, 765, 770, 777–780, 782, 788–789, 799, 812, 816–817, 835, 837, 848–849, 851, 857–858, 860–861, 869, 877, 882–883, 889–891, 894, 897–904, 906, 909, 915, 920, 924, 926, 931–932, 936, 939, 944–945, 951–956, 960–965, 977–978, 981–982, 985, 988–989, 1001–1004, 1009, 1017, 1023, 1030–1031, 1039, 1044–1048, 1052–1055, 1057–1063, 1069–1073, 1079, 1084–1085, 1089–1092, 1097–1098, 1108–1109, 1114, 1119–1120, 1122, 1130, 1134–1136, 1138–1139, 1142–1147, 1150 |
| `src/seon/issue/detect.clj` | 7–8, 49–50, 61, 159–161, 203–205, 256–258 |
| `src/seon/issue/opening.clj` | 7, 42–45, 48–53, 55–56, 59, 61, 63–65, 69, 81, 115, 120, 128, 139, 158, 170, 179, 182–183, 192, 201, 206, 216, 219, 221–222, 228, 231 |
| `src/seon/plan.clj` | 687, 697–699, 777, 791, 816, 818, 844 |
| `src/seon/render/transcript.clj` | 1569 |
| `src/seon/turn.clj` | 2036–2037, 2062, 2667, 2702–2703, 2887–2888, 2890, 2916–2917, 4973 |
| `test/my/program_test.clj` | 35, 38–40, 42–43 |
| `test/seon/cluster/agent_arming_test.clj` | 4, 15, 166–171, 174, 176, 178, 181, 188–190, 196, 199, 222 |
| `test/seon/cluster/wake_test.clj` | 269, 307 |
| `test/seon/cluster_test.clj` | 257 |
| `test/seon/dev/issues_test.clj` | 5, 7 |
| `test/seon/incremental_publication_test.clj` | 139–141 |
| `test/seon/issue/detect_test.clj` | 123, 125 |
| `test/seon/issue_deletion_test.clj` | 14–15, 21, 23, 26, 28–30, 32–33, 48–49, 55, 66–68, 72–73, 75, 78–79, 82, 87–88, 92 |
| `test/seon/issue_generate_test.clj` | 19, 22–24, 28–33, 74–77, 80, 82, 85, 89, 94–95, 105–106, 113–115, 118, 125–126, 132, 134–136, 141, 143–145, 154–155, 158–159, 161, 167 |
| `test/seon/issue_settlement_test.clj` | 120, 134–139, 166, 184–185, 188, 201–202, 204, 208, 210, 242–243, 254, 276, 284, 291, 293, 297–304, 306, 308–310, 314, 317, 319–320, 323, 327, 331–335, 338, 341–343, 346–347, 349–351 |
| `test/seon/issue_test.clj` | 24–26, 28–32, 34, 36–43, 45–50, 52, 54–58, 60–71, 73–74, 81–82, 85–86, 88–89, 92–95, 98–100, 102, 104–106, 111, 113–114, 116, 120–122, 127–134, 140–142, 145–150, 153–155, 161, 163–164, 172, 181–184, 189–191, 193, 195–199, 209–213, 215, 217–218, 220–224, 232–233, 235–236, 238, 241, 243–245, 247, 250, 253, 257–263, 272–277, 298, 306, 311, 336, 338, 342–344, 349–351, 353–354, 359–361, 364, 366–367, 371–372, 375, 377–378, 389–393, 396–398, 400–403, 408, 415–419, 424–425, 429–435, 438, 440, 443, 450–452, 455 |
| `test/seon/render/entity_pairs_test.clj` | 26–32, 44 |
| `test/seon/render/web_debug_test.clj` | 989, 1052, 1054–1058, 1064, 1069, 1071 |
| `test/seon/reset_edges_test.clj` | 255–258, 261, 326–328, 339–340 |
| `test/seon/schema/datahike_parity.edn` | 1 |
| `test/seon/test/host_test.clj` | 46, 48–49, 52–54, 56, 58 |
| `test/seon/turn_test.clj` | 92–98, 968–969 |

### Namespace responsibility conversion

**22 files / 62 matching lines.**

| File | Matching line numbers |
|---|---|
| `resources/seon/schemas/my.agent.edn` | 3, 7 |
| `resources/seon/schemas/seon.agent.edn` | 14 |
| `resources/seon/schemas/seon.error.edn` | 213 |
| `resources/seon/schemas/seon.ns.edn` | 27, 29–30 |
| `src/my/agent.clj` | 10 |
| `src/seon/agent.clj` | 18, 30–31 |
| `src/seon/cluster/agent.clj` | 128, 131, 144, 150, 159, 177, 190, 278, 421, 424, 434 |
| `src/seon/error.clj` | 515, 1504, 2065, 2076, 2129 |
| `src/seon/problems.clj` | 266–268, 279 |
| `src/seon/render/ns.clj` | 410 |
| `src/seon/sci/eval.clj` | 269 |
| `test/seon/cluster/agent_identity_test.clj` | 117 |
| `test/seon/cluster/agent_namespace_test.clj` | 3, 25–26, 58, 61, 92, 124, 132–133 |
| `test/seon/cluster/wake_test.clj` | 433, 438 |
| `test/seon/db_test.clj` | 1048 |
| `test/seon/error_test.clj` | 105 |
| `test/seon/fixtures/html_views_ai.edn` | 1 |
| `test/seon/render/ns_test.clj` | 29, 44, 284 |
| `test/seon/render/value_test.clj` | 730, 736, 744 |
| `test/seon/render/web_test.clj` | 467, 469, 510, 514 |
| `test/seon/schema/datahike_parity.edn` | 1 |
| `test/seon/schema_test.clj` | 208, 505, 509 |

### Census byte identities

| File | SHA-256 |
|---|---|
| `resources/seon/schemas/my.agent.edn` | `0336256811f39093b9a3ca7298d84d254bd1553e66a991bd0050b8ef48e6cfef` |
| `resources/seon/schemas/seon.agent.edn` | `3071b3a29f12bd129d76e060df3068189048944b575973b52e54d8cda2f8fcf6` |
| `resources/seon/schemas/seon.config.render.edn` | `1766272a0af603cf1c0302c0eeba740f0fb7b87ae02b3306f0f9a618acf44bc5` |
| `resources/seon/schemas/seon.error.edn` | `c29c3dc7a058c13c05b74401bc46868764dd595a9211cce4cf1e46d920dfc3aa` |
| `resources/seon/schemas/seon.eval.edn` | `2d0aa76aedab58cfffdd4f74d3cfc8a7071cd4de525f28505bdd7f6abd75f8e9` |
| `resources/seon/schemas/seon.issue.citation.edn` | `5e1d24eb57fa2f7045eaa437fe22c355e4d8ba950f4d379849aa1f8fc1213724` |
| `resources/seon/schemas/seon.issue.edn` | `5741e65bb6445a7f00afba525a4b8ed9d26fa98187b3314b9c6b7a18a4802183` |
| `resources/seon/schemas/seon.ns.edn` | `aef5727260d0b20e4205415eebb98e3df7319f0c17925e0952a9273d7e6777c0` |
| `resources/seon/schemas/seon.program.edn` | `039c14f9147936127da19d34c98e101369396ed306a1d88f3c784dd6996226c1` |
| `script/seon/dev/issues.clj` | `5ef188baaf55267f1f6b5b5d9fdd3805feb3fab89b07be2b7460a5a774320360` |
| `src/my/agent.clj` | `374b9075d66eab0d28bdb346aa1051fcd14109e6686ecbec10155178e25d5e53` |
| `src/my/issue.clj` | `44345b35cdd51053a59b2a395ed404fac49825b047e1cf7fb0213caf6981996e` |
| `src/my/program.clj` | `a1619353d5e9b1dbefc322011f28e20a5b791b2fda3d09352f6f5f0a349345fc` |
| `src/seon/agent.clj` | `61ff0b62574bc9e2af448ac2ccba5916bdd932c1338061ad16e415470729fa21` |
| `src/seon/bootstrap.clj` | `450a778d2b99804b69913f953a0f32966c7d9e38ae7a93987ec999c741fb0ef7` |
| `src/seon/cluster.clj` | `f5ee05773f936cc1493fcbb86069c4ec2b1b8d7c72d11497ad80174c8bc5b4cd` |
| `src/seon/cluster/agent.clj` | `4819c2a7f27284af10d54df74a262ef1c93b99221920ba724ed6fd525bb35a01` |
| `src/seon/cluster/source.clj` | `536e972406d36005c84cf3e3741946ee66dc42d08e816d27c7158f00845135ec` |
| `src/seon/cluster/wake.clj` | `980ff3c4abae8b6d507cb535a53b8b2d05937936af9e5ed76562d3a815d24cd0` |
| `src/seon/error.clj` | `225b9193b319aa287edd2b54f2bafef43b2730460859d45192f56bc8cb30fd29` |
| `src/seon/issue.clj` | `b55d0292b66dbb1d0f5984c614482ce65dd6f096d2ab88c4b81e513c6a6b6302` |
| `src/seon/issue/detect.clj` | `cc49d2020b1ebdcf8f7fa8c515624fd0afe4e7f8925d38858f882b1d49aac8fe` |
| `src/seon/issue/opening.clj` | `23f389e6abdd6bad9b7577f7e27be05d8caa42e9e5dedbed8c62c8eb07ad48e4` |
| `src/seon/plan.clj` | `311e8225a610780f6365de140b8b38e74e465bea7f85dbef3c2aae5fe57281e3` |
| `src/seon/problems.clj` | `81dd2481593ae70993191f9e3ebacc7e2e9c1cb8efff4eaa1cef601d51a12d50` |
| `src/seon/render/ns.clj` | `6e3a369f16231fea87c1c37c7b25a84f6acf890e0fae39e5a52b0dee84a59867` |
| `src/seon/render/transcript.clj` | `ec909a3a4c96e01dea2b6221bcbfd9f515b7a0d00f71ded7d017ead179b16a49` |
| `src/seon/sci/eval.clj` | `7d95192a5d87c24f2d62354567d21d47f72ef8a7e74d82baeea6435aaaa81905` |
| `src/seon/turn.clj` | `af3805ad72d0929dff3d20d3435ce8ffa4a5e847b00c6f8017564a1b03212b2c` |
| `test/my/program_test.clj` | `9ca8a50942ab132f93f6942845a43273958c1a845852abda24ff86e2e71ead59` |
| `test/seon/cluster/agent_arming_test.clj` | `6c4f618497bbbc6cc198adb33e1e01e190336ee3e547eb97c4b2011460c0d47d` |
| `test/seon/cluster/agent_identity_test.clj` | `d279b546a942c2b9be9ca4cac748a60dfbfb23cc49c0c3a8e68c0ef7e2efe799` |
| `test/seon/cluster/agent_namespace_test.clj` | `fc8c0894024f24733352d46b42a0d7fa7b12af8625b0b09515ec107f21ae89f1` |
| `test/seon/cluster/wake_test.clj` | `7564e078ecbf08fc7ed94739598cdb42efcee66f0fe36418f245e38de69d7326` |
| `test/seon/cluster_test.clj` | `f3f99f8cb27713b205130f6d30de858cbe5ad08d5e53f0046d13dde729f1e08f` |
| `test/seon/db_test.clj` | `49c6521dba58739d26a2cd2004a0b8211bad7a1e2342608597bac3c90e8c6aa0` |
| `test/seon/dev/issues_test.clj` | `5a7cffd8c61d13e5901adda14057867615e91341d1a60389348b4ee93f342f53` |
| `test/seon/error_test.clj` | `85b19c3a657c7cebf8c61827db8283f1be75a109c14dc5c3a0b2b6f5a1aad52a` |
| `test/seon/fixtures/html_views_ai.edn` | `eff1835a52c3daa1b541cc536978b1da7180e720f20c6d779e223910df04728d` |
| `test/seon/incremental_publication_test.clj` | `f4d0e5070b84fecaf799509e48342bc8cc6000a3a5d611c9c759cbe6edf50e74` |
| `test/seon/issue/detect_test.clj` | `a36c49c4496e704adba62c84eba012791e99bbb29c03323712b2fa9c5394b47a` |
| `test/seon/issue_deletion_test.clj` | `826882f0d72f8598bdc5d9f1e971fe6cd84c09f783184846d21b13fcc5cc7c81` |
| `test/seon/issue_generate_test.clj` | `831c22414dccfc849912dd6f550af4669554924c0599cab102938dc84964eacc` |
| `test/seon/issue_settlement_test.clj` | `9cbc0bae87f603eab78977082ab84782f8720c17f88f6a2ffbcb14aff18a0b62` |
| `test/seon/issue_test.clj` | `bb8b6d1ea17d466c1405cf4089dbaa9d57d1445db57b6d2e50c1196f112add86` |
| `test/seon/render/entity_pairs_test.clj` | `12efaef973d9a71954dd6fdff890c9fb538789ccf67a2f99900ae36b5f426cf9` |
| `test/seon/render/ns_test.clj` | `bcb1ce8559ce1df9f06dfc2305b35986283619331dc156622ea7bce01d480952` |
| `test/seon/render/value_test.clj` | `b44cd6d512fabfc2586be8fd9fa60ae4a3002800dfc60ef30fbd762ccb40cfcf` |
| `test/seon/render/web_debug_test.clj` | `e2eafe8d6128933a80ef69096745da0ba94cb223d6c30bd9d72ccab86bb5963a` |
| `test/seon/render/web_test.clj` | `37576f2b178a9a007f01a169ea7ca256abeef15648f3fd64b12d12edd69727fa` |
| `test/seon/reset_edges_test.clj` | `4e9b485d3d345729e9ff93d6682c6c5f380e9f4ede365bdebd03f69aad1f461f` |
| `test/seon/schema/datahike_parity.edn` | `f20043d52907c9cfca025a0634fb8901c18b6df9b26b0b4f27edd178a43a7720` |
| `test/seon/schema_test.clj` | `9d961716137e3275fe34717e169d2c92e79ef928258537066136460b9d14eec4` |
| `test/seon/test/host_test.clj` | `87dcf5392c491f8cbcef6c4dbd31596f64935274c3d9546d66c4406059c4abcc` |
| `test/seon/turn_test.clj` | `6fab95b41dfb94d4e35fd692a43255e380d29ebd0074ccce67df58a3b16c8608` |

## Design-lane verification boundary

Read the namespace-agents plan and bridge-step3-stamp launch specification
**end to end**, including the former's §§1, 3, 6, 8 and later D13 ruling.
Read context-templates-and-render-pairs and adoption-silence-diagnosis
**end to end**; read AGENTS §§2–3 and their task/namespace/detector rows,
the named schema resources and issue/my.issue/opening/detector owners,
and the wake/turn/render/dependency seams cited above. Read the roadmap
entry and current working-edge checkpoint. The wave-4 conflict and running
lane ownership sections were read for coordination; their complete
implementation plans are not claimed as new runtime proof.

Exactly this document is authored. No source, test, schema, config, skill,
issue note or other document was edited. No JVM, test/gate, MCP evaluation,
operator/lifecycle command, worktree, lane or background process was
launched. No live-system health, adoption freshness or browser result is
claimed. The specific no-JVM/no-worktree design rules govern over the
assignment's generic fast-test/worktree fallback paragraph. Shared-tree
breakage was not loaded or attributed; static inspection continued.

Static checks verify links to existing authorities, source paths/anchors,
census arithmetic, schema inventory and whitespace. All canonical fixture
regressions, production namespace loading, runtime wake/opening proof,
schema reset, cold/platform gate and browser observation are explicitly
implementation/orchestrator obligations. The typed-pattern wake gap and
the stale dependency/flow prose encountered here are recorded in this
single allowed deliverable, with owning corrections assigned above.
Commit only this document path and stop.
