---
type: research
status: active
tags: [schema, deletion, datahike, agent, turn, evaluation, issue, listen]
---

# Deletion semantics for the agent and runtime families

Dated 2026-09-16. Read-only investigation against `steward-platform` at the
working tree, plus two read-only JVM queries against the live `default`
cluster. No source edits, no transactions.

The owner's mandate: *"Doesn't it depend? Sometimes you want the associated
entity to be retracted and sometimes you don't. What am I ruling on? What are
the specific issues we aren't sure about."* Ruling 47, G1–G6 and the eval-path
review's "every non-component ref is living" are inputs here, not constraints.

## 0. The finding that reframes the question

**Seon already has all four menu behaviours, and the dial that selects between
"refuse" and "silent sweep" is `{:optional true}` in the entity map schema.**
It is not a new mechanism, not a declared policy property, and not something
anybody chose per attribute. It is a side effect of how the writer validates.

The chain, opened end to end:

1. `[:db/retractEntity e]` retracts the entity's own datoms, **every incoming
   ref datom** for every installed `:db.type/ref` attribute
   (`reference-code/datahike/src/datahike/db/transaction.cljc:998-1014`, the
   sweep at `:1002-1013`), and cascades `:db/isComponent` children recursively
   (`retract-components`, `:831-836`, spliced at `:1015`). Note precisely what
   cascades: `(retract-components db e-datoms)` — the entity's OWN datoms only.
   Swept incoming datoms never cascade.
2. Every one of those retractions is written through `transact-retract-datom`
   (`:813-819`), so it lands in the report's `:tx-data`.
3. Seon attaches a **final-report validator** to every `transact!`
   (`src/seon/db.clj:3178-3179`, wiring `write-report-validator` into
   `:tx-meta :datahike/validate-report`). Datahike calls it once, and any
   non-nil return throws `:transaction/validation-rejected`, aborting the
   whole transaction (`transaction.cljc:1206-1216`).
4. `write-report-error` (`src/seon/db.clj:3009-3041`) computes
   `affected` = distinct `:e` over attempted **and** effective tx-data — which
   therefore **includes every entity the sweep touched** — pulls each one's
   resulting row with `write-entity-value` (`:2911-2921`), recovers its
   identities from `:db-before` as well as `:db-after` (`:3034-3039`), and
   validates the whole row against every entity schema those identities select
   (`write-entity-error`, `:2958-3007`).
5. So: if the swept ref attribute is **required** in the referrer's entity
   schema, the referrer now fails `:malli.core/missing-key` and **the deletion
   refuses**. If it is `{:optional true}`, the row still validates and **the
   deletion sweeps silently**.

Two exemptions, both real holes:

- `write-entity-error` is `(when (seq row))` (`:2961`) — an entity retracted to
  nothing is skipped. Correct, and it is why coordinated deletion works.
- `schema-keys` derives from the row's **identity attributes** (`:2964`). An
  entity with no identity attribute selects no schema and is **never
  validated** — which is exactly the 29 unselected component maps the audit
  counted (`schema-key-audit-2026-09-16.md`). For this family that means a
  component row swept by a foreign deletion is unchecked.

The consequence for the review: **N9's premise is false.**
`schema-design-review-2026-09-17.md:66` states that for
`:seon.schedule.task/function`, "`retract-entity`'s sweep runs no
re-validation of the referrer, so the task persists in a state its own schema
forbids." It does re-validate, at `src/seon/db.clj:3032-3040`, and the task
persists in no state at all — the transaction is refused. C.1's "the open
question of what `retract-entity`'s incoming sweep does to a surviving
entity's **required** keys (N9 today has no answer)" has an answer: it refuses
the transaction, today, with a whole-entity Malli explanation.

So the owner is not ruling on "should refuse exist". It exists. The owner is
ruling on **which refs should be required** — because that is what the choice
already is — and on the handful of cases where required-ness is the wrong tool.

## 1. The menu, verified

| # | Behaviour | Declared by | Source |
|---|---|---|---|
| 1 | **cascade** — the referrer is part of the target's value and dies with it | `:seon.db/component true` | `transaction.cljc:831-836`, `:1015`; pull expands it unasked, `pull_api.cljc:346-351` |
| 2 | **sweep** — the referrer's ref datom is retracted silently; history keeps it | an **optional** ref key in the referrer's entity schema | `transaction.cljc:1002-1014`; validator passes at `src/seon/db.clj:2980` |
| 3 | **refuse** — the transaction aborts unless the referrer is retracted too | a **required** ref key in the referrer's entity schema | `src/seon/db.clj:3032-3040` → `transaction.cljc:1206-1216` |
| 4 | **value** — the fact stores the target's identity value; deletion touches nothing | retype the attribute off `:seon.db/ref` | G2; `transaction.cljc:33-52` shows `validate-val` never checks target existence, which is why a ref is the wrong carrier for a mention |
| 5 | **move** — the fact is a pending edge that its own settlement migrates to a durable sibling, so the live set is small and self-draining | two attributes, one written as the other is retracted | `:seon.message/inbox` → `:seon.message/read-tx` (`src/seon/turn.clj:435`); `:seon.effect/notify` → `:seon.effect/to` (`src/seon/effect.clj:356-360`, `:389-393`) |

Number 5 is the fifth behaviour the source genuinely supports and that this
family already uses twice, independently invented in two namespaces. It is
worth naming because it is the only one that makes *deletion* irrelevant by
making the edge short-lived.

One behaviour that is NOT on the menu and must not be added: a pre-read that
asks "does anything point at this?" before deleting. The authority re-decides
it (owner law 2026-08-29). The only legitimate write-time decision point is
`[:db.fn/call f]`, which sees the mid-transaction database
(`transaction.cljc:1153-1154`) and whose throw aborts everything
(`reference-code/datahike/src/datahike/writer.cljc:147-160`, `:201-218`). The
final-report validator already sits in exactly the right place.

## 2. Live evidence (cluster `default`, two read-only JVM queries)

186 installed `:db.type/ref` attributes, 55 of them components.
Agents: `root` (eid 47221), `juniper` (47398), `2393cac275ae` (66192).
53 turns, 109 evaluations, 1,743 issues, 6 messages, 1,123 context
contributions, 2 error occurrences, 5 schedule tasks, **1 listen pattern, and
exactly one of those carries `:seon.listen/entity`**.

Incoming ref datoms, by attribute (component flag in brackets):

```
agent 66192 "2393cac275ae"
  :seon.turn/agent    ×12 [ref]   :seon.message/from  ×2 [ref]
  :my.plan/agent      ×1  [ref]   :seon.message/to    ×1 [ref]
  :seon.runtime/agent ×1  [ref]   :seon.message/inbox ×1 [ref]
  :seon.issue/agent   ×1  [ref]   :seon.ns/steward    ×1 [ref]
  :seon.config/agent  ×1  [ref]
turn 68891 "0ffe7de3aa57"
  :seon.cluster.eval/run ×1 [ref]  :seon.context.capture/run ×1 [ref]
  :seon.runtime/turns    ×1 [COMPONENT]
issue 59710 "65f46c0efa7f"
  :seon.eval/origin   ×3 [ref]   :seon.message/about   ×2 [ref]
  :my.plan.item/subject ×1 [ref] :seon.listen/entity   ×1 [ref]
  :seon.turn/trigger  ×1 [ref]   :seon.runtime/trigger ×1 [ref]
evaluation 68945 "7be2986fb631"
  (none)
```

Not one incoming edge to the agent, and not one to the issue, is a component.

## 3. The inventory

Every ref-typed attribute declared in the agent and runtime families,
enumerated from `resources/seon/schemas/` (not from memory). "Today" is the
behaviour the writer actually produces, derived by reading the referrer's
entity map for required-versus-optional.

### 3.1 Agent

| Attribute | Declared today | Written at | What the fact MEANS to the writer | What a reader needs when the target is gone | Recommended | Ruling kept / overturned |
|---|---|---|---|---|---|---|
| `:seon.agent/runtime` (`seon.agent.edn:1`, member `:24` optional) | component ref | `src/seon/cluster/agent.clj` blueprint path | the agent owns its runtime | nothing — it is the agent's value | **cascade**, unchanged | G5, kept |
| `:seon.agent/plan` (`:154`, member `:22` optional) | component ref | `src/seon/plan.clj:1130` etc. | the agent owns its one plan | nothing | **cascade**, unchanged | G5, kept |
| `:seon.agent/settings` (`:157`, member `:23` optional) | component ref | `src/seon/ai.clj` overlay writes; `src/seon/issue.clj:947` | the agent owns its settings overlay | nothing | **cascade**, unchanged | G5, kept |
| `:seon.agent/namespace` (`:82-86`, member `:19-21` optional) | plain ref, optional | `src/seon/cluster/agent.clj` creation; read at `src/seon/turn.clj:626`, `:689` | "assigned to work here by default"; explicitly **not** unique, explicitly not ownership (`:85`) | the name. A deleted namespace silently un-assigns the agent, and `sci.eval/agent-namespace` then returns nil, which `src/seon/turn.clj:2213-2226` reports as `::agent-namespace-missing` — a *good* refusal, but at turn time, not at delete time | **value** (`:symbol`, the namespace name) — a namespace's deletion must not silently reshape an agent, and the name is what every reader wants | overturns "every non-component ref is living" for this one: an agent assigned to a namespace that no longer exists is a *representable, reportable* state, not a corruption |
| `:seon.runtime/agent` (`seon.runtime.edn:1-2`, member `:13` **required**) | ref, **`:seon.db/identity true`** | agent blueprint | the runtime component's identity IS a ref to its parent | — | **cascade covers it**; but see §6, this is a stored mirror of the component edge | new observation |
| `:seon.runtime/turns` (`:3-6`, member `:14` optional) | component set | `src/seon/turn.clj` open path | the agent owns its turns | — | **cascade**, unchanged | G5, kept |
| `:seon.runtime/trigger` (`:7`, member `:15` optional) | plain ref, optional | wake router | "the fact whose wake opened the latest turn" — a *latest-wins pointer*, provenance | nothing; it is superseded by the next wake anyway | **sweep** is correct. Declare it in the docstring | overturns "every non-component ref is living" |
| `:seon.runtime/listens` (`:8-9`, member `:16` optional) | component set | `src/seon/issue.clj:951-952` | the agent owns its wake patterns | — | **cascade**, unchanged | G5, kept |
| `:seon.config/agent` (`seon.config.edn:17`, required in its map) | plain ref, required | config reconciliation | the config singleton's per-agent overlay row | — | refuse is correct and already in force | — |
| `:my.plan/agent` (`my.plan.edn:1-2`, member `:9` **required**) | ref, **identity** | `src/seon/plan.clj` | same mirror shape as `:seon.runtime/agent` | — | cascade via `:seon.agent/plan`; see §6 | new observation |

### 3.2 Turn, evaluation, provider attempt

| Attribute | Declared today | Written at | Meaning to the writer | Reader's need on deletion | Recommended | Ruling |
|---|---|---|---|---|---|---|
| `:seon.turn/agent` (`seon.turn.edn:49-56`, member `:8` **required**) | plain ref | `src/seon/turn.clj:348` `open-call`, `:417` `open-run-tx-call` | whose turn this is; its reverse IS the agent's history (`:52`) | the agent | **refuse — already in force.** A turn without an agent is not a turn | keeps C.1 kind 2; the mechanism is required-ness, not a new property |
| `:seon.turn/opened-tx` (`:158`, member `:12` **required**) | plain ref to a transaction | `src/seon/turn.clj:417` | the opening transaction | — | refuse; transactions are never retracted | — |
| `:seon.turn/closed-tx` (`:157`, member `:16` optional) | ref to a transaction | `src/seon/turn.clj:435` | `open? = no closed-tx` (`src/seon/turn.clj:189`) | — | unchanged | derive-or-die, kept |
| `:seon.turn/trigger` (`:43`, member `:11` optional) | indexed plain ref | `src/seon/issue.clj:902`; `src/seon/turn.clj:4188-4200` (comment there already says **"retained as PROVENANCE ONLY"**) | which fact's wake opened this turn | the *identity* of the trigger, for the history render | **value** where the trigger has a stable identity, else accept **sweep** and say so. The source comment already declares it provenance; the schema does not | overturns "every non-component ref is living" — the code already ruled this one |
| `:seon.turn/starting-ns` (`:97`, member `:13` optional) | plain ref | `src/seon/turn.clj:4723` | which namespace the turn began in | the **name** — history renders a name, never an eid | **value** (`:symbol`) | G2 extended to the turn family |
| `:seon.turn/attempts` (`:128-132`, member `:9` optional) | component set | `src/seon/ai.clj` | the turn owns its provider attempts | — | cascade, unchanged | G5, kept |
| `:seon.cluster.eval/run` (`seon.cluster.eval.edn:23`, member `:33` **required**) | plain ref | `src/seon/turn.clj:773`, `:923`, `:1474` | which turn this evaluation belongs to | the turn | **refuse — already in force.** This is why a turn cannot be retracted while its evaluations survive | keeps kind 2 |
| `:seon.cluster.eval/ns` (`:22`, member `:40-42` optional) | plain ref | settlement at `src/seon/sci/eval.clj:1957` | the namespace the form was read in | the **name**; `seon.eval.edn:18-21` already declares `[:or :seon.cluster.eval/ns [:map [:seon.ns/name …]]]`, a hand-written pulled-shape widening | **value** (`:symbol`). This also deletes the `[:or …]` widening | G2; deletes one of the twelve pulled-shape mirrors |
| `:seon.cluster.eval/refreshes` (`:26-27`, member `:49-51` optional) | **unique** plain ref | `src/seon/turn.clj:867` | "this evaluation supersedes that prior one" in the since-diff | the prior evaluation's identity | **refuse** (make it required-when-present is not expressible; instead make the successor's row invalid without it? no — see §7 decision 3) | genuine policy question |
| `:seon.cluster.eval/read-evidence` (`:4-5`) | component vector | `src/seon/db.clj:784`; retracted `src/seon/turn.clj:2300` | the evaluation owns its read evidence | — | cascade, unchanged | G5, kept |
| `:seon.eval/origin` (`seon.eval.edn:1`, member `:28`/`:64` optional) | **untyped** plain ref | `src/seon/turn.clj:103-104`, `:773`, `:923`, `:1474`, `:1547` | "the entity whose render declared this generated evaluation" — provenance for a system-turn form | the identity, to requery the block | **value** — but it is untyped, so it has no single identity type. See §7 decision 1 | extends N12; N12 said "record the consequence in the docstring", which is not enough |
| `:seon.eval/renderer-fn` (`:3`, member `:30`/`:66` optional) | plain ref | settlement | the renderer's program row, beside `:seon.eval/renderer` which is already `:qualified-symbol` (`:2`) | nothing the symbol does not answer | **delete the attribute** | N6, confirmed; live cluster shows the symbol is the durable half |
| `:seon.ai.attempt/model` (`seon.ai.attempt.edn:12`, member `seon.ai.edn:364` optional) | plain ref | `src/seon/ai.clj` | which model descriptor row was selected | the model **name**; `:seon.ai/model` string is already required beside it (`seon.ai.edn:361`) | **delete the ref**; the string is the record | same class as N6/N10 |
| `:seon.ai.attempt/settings` (`:11`, member `:365` optional) | plain ref | `src/seon/ai.clj` | which settings component was in force; `settings-edn` beside it preserves the exact dials (`:13`, required at `:362`) | nothing — the `-edn` is required and is the record, by the attribute's own docstring | **delete the ref** | same class |
| `:seon.ai.attempt/error` (`:15`, member `:398-400` optional) | plain ref to a `seon.error` fact | `src/seon/ai.clj` | the fault this attempt produced | the error | **refuse**: a provider attempt that claims an error must not lose it | new; kind 2 |
| `:seon.ai.attempt/failover-from` (`:1`) | plain ref to a sibling attempt | `src/seon/ai.clj` | "this attempt retried that one" | the sibling | siblings are components of the same turn and die together; **sweep** is harmless | — |
| `:seon.ai.attempt/truncation` (`:10`, member `:385-387` optional) | plain ref | `src/seon/ai.clj` | truncation record | — | leave; out of this note's reach | — |

### 3.3 Message

| Attribute | Declared today | Written at | Meaning | Reader's need | Recommended | Ruling |
|---|---|---|---|---|---|---|
| `:seon.message/to` (`seon.message.edn:20-24`, member `:74` **required**) | indexed ref | `src/my/message.clj:31` → `src/seon/cluster/message.clj` | "the permanent recipient record" — the docstring says *permanent* | the recipient | **refuse — already in force**, and the docstring already claims it | keeps kind 2 |
| `:seon.message/from` (`:119-125`, member `:76-78` optional) | indexed ref, `:seon.wake/inside true` | `src/seon/cluster/message.clj:192` region | the sender, and the INSIDE-wake marker | the sender's identity; and **critically**, the wake classification. Sweeping `from` converts an inside wake into an outside wake for any reader that re-derives it from the message | **refuse.** This is the review's "largest G1 blast radius" (`schema-design-review-2026-09-17.md:99`) and the review is right about `from` and wrong that it is uniform: `to` already refuses, `from` does not | overturns the review's blanket framing; keeps kind 2 for `from` |
| `:seon.message/inbox` (`:141-152`, member `:88-90` optional) | indexed ref, `:seon.wake/listen`, `:seon.wake/opens-turn?` | asserted on delivery; **retracted** by the answering transaction (`src/seon/turn.clj:435`, `src/seon/cluster/message.clj:276`) | the pending edge; its retraction IS the handling | — | **move (menu 5), unchanged.** The pending set is small and self-draining by construction | — |
| `:seon.message/read-tx` (`:199-203`, member `:91-93` optional) | ref to a transaction | answering transaction | "the transaction which handled the inbox edge" | — | unchanged | — |
| `:seon.message/about` (`:25-31`, member `:82-84` optional) | indexed ref, `:seon.wake/inside true` | `src/seon/error.clj:1310`; `src/seon/turn.clj:3483`; `src/seon/cluster/message.clj:192` | "the entity this message concerns when one was named"; **its presence marks the message an INSIDE wake** (`:28-30`) | the subject's identity. Live: the sample issue has 2 incoming `about` datoms; retracting it flips both messages from inside to outside wakes, silently | **value.** Absence is load-bearing here (`presence marks INSIDE`), so a sweep manufactures the false arm of a semantic flag — the project's named failure class, verbatim | new; same disease as N8 |
| `:seon.message/caused-by` (`:113-118`, member `:79-81` optional) | indexed ref to an earlier message | `src/seon/cluster/message.clj` chain limiting | the causal chain, bounded by `:seon.config.message/max-chain` | the chain length | **sweep** is acceptable: the chain is a bounded live concern, not a record. Say so in the docstring | overturns "every non-component ref is living" |

### 3.4 Issue and citation

| Attribute | Declared today | Written at | Meaning | Reader's need | Recommended | Ruling |
|---|---|---|---|---|---|---|
| `:seon.issue/agent` (`seon.issue.edn:30-33`, member `:64` optional) | indexed ref, `:seon.wake/listen`, `:seon.wake/opens-turn?` | `src/seon/issue.clj` `start!`; read `src/seon/turn.clj:2157`, `:2787`, `:2958`, `:5071` | the worker; **"asserted exactly once and retained on resume"**; its assertion anchors the issue turn budget | the worker. Its absence means *unassigned*, which is a real state — so a sweep produces a plausible-looking lie | **refuse.** The docstring already promises retention | keeps kind 2 |
| `:seon.issue/created-by` (`:34`, member `:65` optional) | plain ref | `src/seon/issue.clj` | "the enduring retraction authority, including after unassignment" — and it really is: `retention-check` (`src/seon/db.clj:3103-3139`) compares the actor against it | the author. A sweep **erases a live authorization fact**, which `retention-check` then treats as "no creator" | **refuse**, and it is the strongest case in the family because a security-shaped decision reads it | new; kind 2 |
| `:seon.issue/detector` (`:35`, member `:66` optional) | plain ref to a program row | `src/seon/issue.clj:470-471` | "with the subject's identity value it IS the issue's identity, so a detector run upserts instead of duplicating" | the detector's **symbol** — `src/seon/issue.clj:470` already builds the identity from `(symbol detector)` | **value** (`:qualified-symbol`). The identity derivation already uses the symbol; the ref is the redundant half | G2 extended; same class as N10 |
| `:seon.issue/functions`, `/tests`, `/errors`, `/keys`, `/namespaces`, `/runs`, `/issues`, `/members` (`:8`–`:29`, all optional) | ref sets, each carrying `:seon.issue/cites [<identity attribute>]` | `src/seon/issue.clj:370-395`, `:760-776` | citations resolved from note prose at index time | the cited **token**, which is what the note file holds | **value**, as N5 says | N5, confirmed and reinforced: live sample issue proves the citations are all one-way and all optional |
| `:seon.issue/tests` specifically | ref set + `:seon.db/append-only-after :seon.issue/agent` + `:seon.db/retraction-authority :seon.issue/created-by` | `src/seon/issue.clj` | once started, tests may only be retracted by the creator | — | **already refuses, through a different mechanism**: `retain-transaction` (`src/seon/db.clj:3142-3155`) wraps every transaction in `[:db.fn/call …]` pairs, and `retention-check` throws when a started issue's tests are removed by a non-creator. **The incoming-ref sweep of a deleted test entity trips this.** So deleting a test today refuses if a started issue cites it and the deleter is not that issue's creator | new, and it is an undeclared cross-family coupling: a `seon.test` deletion is gated by a `seon.issue` retention rule |
| `:seon.issue/files` (`:20-22`) | component set of `seon.issue.citation` | `src/seon/issue.clj:384` (stale citations retracted explicitly) | the issue owns its file citations | — | cascade, unchanged | G5, kept |
| `:seon.issue.citation/file` (`seon.issue.citation.edn:5`, member `:12` **required**) | plain ref to `seon.fn.file` | `src/seon/issue.clj` indexer | the resolved file entity | the **path** — and the citation's own identity is already derived from `[issue-id path row end-row]` (`:3`), i.e. the path is already the durable half | **value** (`:string`, the repository-relative path). Today this refuses a `seon.fn.file` deletion, which is a refusal nobody asked for | overturns kind 2 for this one: refusing is *worse* than valuing here, because the path is the fact |
| `:seon.issue/budget-exhausted-tx`, `/resolved-tx` (`:37`, `:38`) | refs to transactions | settlement | positive facts whose absence is the open state | — | unchanged | derive-or-die, kept |

### 3.5 Listen, context, error occurrence, effect, schedule, note, plan

| Attribute | Declared today | Written at | Meaning | Reader's need | Recommended | Ruling |
|---|---|---|---|---|---|---|
| `:seon.listen/entity` (`seon.listen.edn:2`, member `:7` optional) | plain ref; **"absent matches any entity"** | `src/seon/issue.clj:951-952` | the entity constraint of one wake pattern | the constraint. `src/seon/cluster/wake.clj:433` is `(or (nil? entity) (= entity (:e datom)))` — **absence is the permissive arm**, so a sweep silently converts "wake me about issue X" into "wake me about EVERY issue". Live: the cluster has exactly one entity-constrained pattern and it points at the sample issue | **value plus a positive constrained? fact**, or split the attribute into `:seon.listen/any-entity? [:= true]` and a required `:seon.listen/entity`, so absence of both is unrepresentable | N8, confirmed live. This is the worst instance in the family and the one that needs no policy debate |
| `:seon.context.capture/run` (`seon.context.capture.edn:40`, member `:9-10` **required**) | plain ref to a turn | `src/seon/context.clj:490-495` `capture-tx` | which turn this prompt capture belongs to | the turn | **refuse — already in force.** With `:seon.cluster.eval/run` this is the second reason a turn cannot be retracted alone | keeps kind 2 |
| `:seon.context.capture/contributions` (`:34-35`) | component set | `src/seon/context.clj` | the capture owns its contributions | — | cascade, unchanged | G5 |
| `:seon.context.contribution/agent` (`seon.context.contribution.edn:29`, member `:17-19` optional) | plain ref | `src/seon/context.clj:204` | ownership check at `:222-225` and `:250-255` refuses a foreign contribution | the owner. A swept `agent` makes `(not= (:seon.agent/id request) nil)` true, so **the ownership check starts refusing everything** — fails closed, which is survivable, but the diagnosis is a lie | **refuse** | new; kind 2 |
| `:seon.context.contribution/evaluations` (`:30`, member `:20-22` optional) | plain ref set | `src/seon/context.clj:204`, `:238-270` | which evaluations this block's text came from | the set. `src/seon/context.clj:483` is `(seq (:seon.context.contribution/evaluations record))` — **an emptied set silently changes what the contribution is** | **refuse**, OR make `:seon.render.block/name` required and select on it as N17 says. Both; they are not exclusive | N17, confirmed, and §5(a) shows compaction is the event that triggers it |
| `:seon.error.occurrence/process` (`seon.error.occurrence.edn:6`, member `:22` **required**) | plain ref | `src/seon/error.clj:1357` region | which process the fault happened in | the process | refuse — already in force | — |
| `:seon.error.occurrence/agent` (`:7`, member `:23` optional) | plain ref | `src/seon/error.clj:1357` | whom the fault happened to; read at `:1481-1482`, `:1681`, `:1733` | the agent's identity | **value** (`:seon.agent/id` string). A fault record must survive the deletion of everything it blames — that is the whole point of a fault record, exactly as N13 argues for `:seon.error/fn` | extends N13 to the agent family |
| `:seon.error.occurrence/turn` (`:8`, member `:24` optional) | plain ref | `src/seon/error.clj` | which turn | the turn id | **value** (`:seon.turn/id` string). Same argument; and compaction/turn retraction is a routine event, so this WILL happen | extends N13 |
| `:seon.error.occurrence/proc-fn`, `/data-blob` (`:9`, `:10`) | plain refs | `src/seon/error.clj` | the proc function row; the blob row | the symbol; the digest | **value** for `proc-fn` (N13); `data-blob` is already a digest elsewhere (`:seon.error/data-blob :seon.blob/digest`, `seon.error.edn:26`) — **two spellings of one fact on one entity**, delete the ref | extends N13; new duplicate sighting |
| `:seon.error/occurrences` (`seon.error.edn:61`) | component set | `src/seon/error.clj` | the fact owns its occurrences | — | cascade, unchanged | G5 |
| `:seon.error/agent` (`:255-261`, optional in `/fact`) | indexed ref, renders the agent's fault list | `src/seon/error.clj:1482` | whom this fault happened to | as above | **value** | extends N13 |
| `:seon.error/run` (`:25`), `/fn` (`:271`), `/issue` (`:275`), `/regressions` (`:274`), `/resolved-tx` (`:276`), `/steward` (`:211`) | plain refs, all optional in `/fact` | `src/seon/error.clj` | fault attribution and follow-up | ids and symbols | **value** for `/run`, `/fn`, `/issue`, `/regressions`; `/resolved-tx` stays a transaction ref; `/steward` is derived prose (`:211` docstring says no error stores its steward) and should not be a stored ref at all | N13 generalized |
| `:seon.effect/run` (`seon.effect.edn:139`, member `:72` **required**) | indexed ref to a turn | `src/seon/effect.clj` | which turn requested the effect | the turn | refuse — already in force; **third** reason a turn cannot be retracted alone | keeps kind 2 |
| `:seon.effect/owner` (`:65`, member `:75` **required**) | plain ref | `src/seon/effect.clj` | the requesting agent | the agent | refuse — already in force | keeps kind 2 |
| `:seon.effect/eval` (`:8-13`, member `:73-74` optional) | indexed ref to an evaluation | `src/seon/effect.clj` | which evaluation issued the request | the evaluation id | **value** (`:seon.cluster.eval/id`). Compaction retracts evaluations routinely (§5a); an effect receipt must not silently lose its cause | new; the compaction event makes this urgent |
| `:seon.effect/notify` (`:134`) → `:seon.effect/to` (`member :89`) | indexed ref; **moved** on settle/interrupt (`src/seon/effect.clj:356-360`, `:389-393`) | `src/seon/effect.clj` | pending settlement wake → durable recipient | — | **move (menu 5), unchanged.** Name this pattern in the vocabulary table beside `:seon.message/inbox` | new: two independent inventions of one mechanism |
| `:seon.effect/capability-fn` (`:2-7`), `/file` (`:14-19`), `/program` (`:25-30`) | indexed plain refs, optional | `src/seon/effect.clj` | resolved handler / file / program row beside `:seon.effect/capability` symbol | symbols and paths | **value**, per N10/J2 | N10, confirmed |
| `:seon.schedule.task/function` (`seon.schedule.task.edn:3`, member **required**) | indexed ref, `:seon.fn/reference-to :seon.fn/sym` | `src/seon/schedule.clj:106` (`[:seon.fn/sym function]` — **the writer already holds the symbol**) | the handler | the symbol, at fire time | **value** (`:qualified-symbol`) | **overturns N9's stated consequence** (the task does not persist invalid — the deletion refuses) while keeping N9's recommendation (value), for a different and better reason: today deleting a function refuses because a schedule task cites it, which is a lock nobody declared |
| `:seon.schedule.task/owner` (`:2`, member required) | indexed ref | `src/seon/schedule.clj` | the owning agent | the agent | refuse — already in force | keeps kind 2 |
| `:seon.schedule.task/schedule` (`:4`, member required) | indexed ref | `src/seon/schedule.clj` | the recurrence definition | — | refuse — already in force | — |
| `:seon.schedule.fire/task` (`seon.schedule.fire.edn:3`, member required) | indexed ref | `src/seon/schedule.clj` | which task fired | the task id | **value**. A firing is a historical event; deleting a task must not be blocked by, nor erase, its history | new; same argument as the occurrence family |
| `:seon.schedule.fire/agent` (`:6-14`, member required), `:seon.wake/listen`, `:seon.wake/opens-turn? false` | indexed ref | `src/seon/schedule.clj` | "asserted once and never retracted-and-reasserted" per its own docstring | — | refuse — already in force, and the docstring already promises it | keeps kind 2 |
| `:my.note/agent` (`my.note.edn:2-5`, member **required**) | plain ref | `src/seon/note.clj:189-190` region | the note's author | the agent | refuse — already in force | keeps kind 2 |
| `:my.note/about` (`:7`, member optional) | plain ref | `src/seon/note.clj:189-190` | the subject the note concerns; read at `:52`, `:72-81` | the subject's identity | **value** or **refuse** — see §7 decision 2; it is the same shape as `:seon.message/about` but without the wake flag, so sweeping is merely lossy, not semantically inverting | — |
| `:my.plan/steps`, `:my.plan.item/steps` (`my.plan.edn:36-40`, `my.plan.item.edn:57-61`) | component sets | `src/seon/plan.clj:1130` | ownership; the docstrings already state the cascade | — | cascade, unchanged | G5 |
| `:my.plan/current-step` (`:41-45`, member `:12` optional) | plain ref | `src/seon/plan.clj:723`, `:1147` | "currentness neither owns nor copies the step, so this is an ordinary ref; absence means no step is selected and the plan still renders" — the docstring already rules it | — | **sweep, unchanged.** The best-written deletion docstring in the population; copy its form | overturns "every non-component ref is living"; this attribute already answered the question correctly |
| `:my.plan.item/needs` (`my.plan.item.edn:21-24`) | plain ref set; docstring: "dependency is not ownership, so this stays an ordinary ref, never a component" | `src/seon/plan.clj:196-281` | blocking dependencies | the blocker's identity | **value** (`:my.plan.item/id`) — a deleted blocker silently unblocks a step, which is a decision the plan renderer will then present as fact | new; the docstring solved component-vs-ref and never asked ref-vs-value |
| `:my.plan.item/about` (`:39`), `/subject` (`:50`) | plain refs, optional | `src/seon/plan.clj:80`, `:154`, `:553`, `:694`, `:717`, `:962` | what the step concerns / its program subject | tokens and symbols; `src/seon/plan.clj:154` already builds `about` from a **token** | **value** | N5's class, in the plan family |
| `:my.plan.item/agent` (`:14-19`) | plain ref, **the docstring says `my.plan` never writes it** | nothing | vestigial from the pre-component model | — | **delete the attribute** | new |
| `:seon.cluster/config`, `/instructions`, `/toolkit` (`seon.cluster.edn:10`, `:12`, `:14`, all **required** in `/cluster`) | plain ref / ref sets | `src/seon/cluster.clj` | the cluster's config row and toolkit namespaces | — | refuse — already in force, but see the G4 trap in §6 | — |

## 4. What the schemas do NOT say, and should

Not one of the ~60 ref attributes above states its deletion behaviour, except
three that state it accidentally and correctly in prose:
`:my.plan/current-step` ("absence means no step is selected and the plan still
renders"), `:my.plan.item/needs` ("dependency is not ownership"), and
`:my.plan/steps` ("retracting the agent or a step retracts everything it
owns"). Every recommendation above is *already expressed* by required-ness or
by the type; the missing artefact is the sentence in the docstring that tells
the next reader which of the five behaviours applies and why.

## 5. The deletion events this family actually has

### (a) Compaction retracts an agent's evaluations

`seon.turn/compact-call` (`src/seon/turn.clj:2415-2419`) emits
`[:db.fn/retractEntity ev]` for every `?evaluation :seon.cluster.eval/run ?turn`
of the agent, after refusing when a turn is open (`:2398-2414`). Turn PRD
§13–§15 rules this: "Compaction retracts evaluations and the next system turn
regenerates the opening."

Worked on live identities — agent `2393cac275ae` (eid 66192), 109 evaluations
in the cluster, 1,123 contributions:

- **cascades**: `:seon.cluster.eval/read-evidence` component rows die with each
  evaluation. Correct and intended.
- **sweeps, silently**: `:seon.context.contribution/evaluations` on every
  contribution that cited them. The contribution's row still validates
  (optional), so the transaction succeeds, and
  `src/seon/context.clj:483` — `(seq (:seon.context.contribution/evaluations record))` —
  now takes the other branch. A contribution that *was* an evaluation-backed
  block becomes, in the database, a contribution that never had evaluations.
  1,123 contributions are exposed to this.
- **sweeps, silently**: `:seon.effect/eval` on every effect receipt issued from
  a compacted evaluation. The receipt keeps its required `/run` and `/owner`,
  so nothing refuses; the causal link to the form that requested the effect is
  gone with no marker.
- **sweeps, silently**: `:seon.eval/origin` points *from* the evaluation, so it
  vanishes with it — no reader is harmed. But the reverse is the live case:
  the sample issue 59710 has **three** incoming `:seon.eval/origin` datoms, so
  deleting an *issue* un-provenances three evaluations (see (b)).
- **sweeps, silently**: `:seon.error.occurrence/turn` is unaffected (turns
  survive compaction), but any occurrence pointing at a compacted evaluation
  via `:seon.error/run`-adjacent paths loses it.
- **does NOT refuse anywhere.** Compaction is a total success from the
  writer's point of view and a partial, unannounced data loss from three
  readers' point of view.

The fix is not to change compaction. It is that the three swept attributes
above should be refuse or value, at which point compaction must either retract
the contributions too (they are stale by construction — their text quotes
evaluations that no longer exist) or carry the evaluation ids as values.

### (b) An issue whose note is gone is retracted

`seon.issue/index-tx` emits `[:db/retractEntity [:seon.issue/id id]]` for every
stored issue whose slug is absent from the parsed note set
(`src/seon/issue.clj:405-407`), and `adopt-tx` does the same on adoption
(`:775-776`). With 1,743 issues indexed from files, deleting a note file is a
routine, unattended deletion of a database entity.

Worked on live identity `65f46c0efa7f` (eid 59710), the one issue with an
assigned agent:

- **cascades**: `:seon.issue/files` component citations die. Correct.
- **sweeps, silently**: `:seon.eval/origin` ×3 — three stored evaluations lose
  the record of which block generated them. Their shown text is unchanged, so
  the history still renders; only the requery path is gone.
- **sweeps, silently**: `:seon.message/about` ×2 — two messages lose their
  subject AND their `:seon.wake/inside true` classification (`seon.message.edn:28-30`).
- **sweeps, silently**: `:my.plan.item/subject` ×1 — a plan step silently
  becomes subjectless and `src/seon/plan.clj:694` reads it as such.
- **sweeps, silently**: `:seon.turn/trigger` ×1, `:seon.runtime/trigger` ×1 —
  provenance only, acceptable.
- **sweeps, catastrophically**: `:seon.listen/entity` ×1. This is the cluster's
  **only** entity-constrained listen pattern. Its constraint disappears; the
  pattern row keeps `:seon.listen/attribute :seon.issue/budget`;
  `src/seon/cluster/wake.clj:433` then matches **every** `:seon.issue/budget`
  datom in a 1,743-issue database and wakes that agent for all of them. A
  deleted file widens a wake pattern to the whole population.
- **refuses, sometimes**: `:seon.issue/tests` retention. If the deleted issue
  is *cited by* nothing this does not apply, but the inverse does — see §5(f).

The `:seon.listen/entity` widening is the single highest-severity item in this
note and it needs no policy ruling: absence must not be the permissive arm.

### (c) Is an agent ever deleted?

**No writer in `src/` retracts an agent entity.** `grep` over
`retractEntity`/`retract-entity` across `src/` finds retractions of
contributions (`src/seon/context.clj:224`), issues (`src/seon/issue.clj:384`,
`:407`, `:776`), read-evidence and evaluations (`src/seon/turn.clj:2300`,
`:2415`), schema attributes (`src/seon/turn.clj:1129`) and plan steps
(`src/seon/plan.clj:1130`). Nothing deletes an agent.

And today it would refuse. Retracting agent 66192 cascades
`:seon.agent/runtime` → `:seon.runtime/turns` → 12 turn entities, each of whose
incoming `:seon.cluster.eval/run` (required) and `:seon.context.capture/run`
(required) datoms are swept, leaving evaluations and captures that fail their
own schemas at `src/seon/db.clj:3032-3040`. It would also sweep one
`:seon.message/to` (required) on a surviving message. **Three independent
refusals.** The agent is, in practice, undeletable, and nobody declared that.

The positive-fact alternative the family already uses: an agent is *closed* by
facts, not by deletion. `:seon.issue/budget-exhausted-tx` and
`:seon.issue/resolved-tx` are exactly that shape, and the turn loop already
derives "no work remains" rather than storing it. **Recommendation: rule that
an agent is never retracted; it is closed by a positive
`:seon.agent/closed-tx` fact**, and the three accidental refusals become one
declared rule instead of an emergent one. That is strictly simpler than making
agent deletion work, and it matches how the cluster actually behaves.

### (d) A turn is retracted

Also not done by any writer today. Turns are components of the runtime
(`seon.runtime.edn:3-6`), so the only path is retracting the agent, which (c)
shows refuses. Evaluations are **not** components of the turn — the edge runs
the other way, `:seon.cluster.eval/run` — so a turn cannot shed its
evaluations by cascade. Live sample turn 68891 has exactly the three incoming
edges that make this concrete: one evaluation, one context capture, one
component edge from the runtime.

**Recommendation: leave it.** A turn is the unit of the agent's record; it is
retracted only as part of compaction-style bulk work, and compaction
deliberately does not touch turns. Declare in `seon.turn.edn` that a turn is
retracted only together with its evaluations, captures and effect receipts —
which is what the writer already enforces.

### (e) A listened entity is retracted

Covered in (b). The worked instance: listen row eid 80779,
`:seon.listen/attribute :seon.issue/budget`, `:seon.listen/entity` 59710.
Retract 59710 and the pattern widens from one issue to every issue.
`src/seon/issue.clj:941-942` looks the pattern up by `[?listen :seon.listen/entity ?issue]`,
so after the sweep `start!` also stops finding the existing listener and
**appends a second, redundant pattern** (`:951-952` fires because `listener` is
nil). One deletion therefore both widens the existing pattern and duplicates
it.

**Recommendation, no policy needed**: split the wildcard out of absence.
Either `:seon.listen/entity` becomes a required identity **value** with a
sibling `:seon.listen/any-entity? [:= true]` for the wildcard, or the matcher
at `src/seon/cluster/wake.clj:433` refuses a pattern whose declared constraint
is missing instead of matching everything. The first is better: it makes the
unsafe state unrepresentable rather than caught.

### (f) A cited test or function is retracted

Not in the brief but it falls out of the same evidence and it is live:
`:seon.issue/tests` carries `:seon.db/append-only-after :seon.issue/agent` and
`:seon.db/retraction-authority :seon.issue/created-by`
(`seon.issue.edn:10-13`), and `retain-transaction` wraps **every** transaction
in the pair of `[:db.fn/call …]` snapshots that enforce it
(`src/seon/db.clj:3142-3155`, `retention-check` at `:3103-3139`). Datahike's
incoming-ref sweep retracts the issue's test member when the test entity dies,
so **deleting a `seon.test` entity throws
`:seon.db/retention-refused` if any started issue cites it and the actor is
not that issue's creator**. That is a cross-family lock that no schema
docstring, PRD or ruling mentions. It is arguably the correct behaviour; it is
certainly an undeclared one, and it will read as a mystifying refusal to the
next lane that deletes a test.

## 6. Three structural observations, no policy attached

1. **`:seon.runtime/agent` and `:my.plan/agent` are identity-and-ref mirrors of
   their own component edge.** The runtime is a component of the agent
   (`seon.agent.edn:1`) *and* stores a unique ref back to it
   (`seon.runtime.edn:1-2`); same for the plan (`my.plan.edn:1-2` vs
   `seon.agent.edn:154`). G5 says a component is part of its parent's value and
   needs no identity of its own — "do not invent identity attributes on
   component rows to make a selector see them" is the datahike skill's wording
   (`.claude/skills/datahike/SKILL.md:185-188`). These two are that invention,
   and they are load-bearing: `src/seon/cluster/wake.clj:437-440` joins
   `[?agent :seon.agent/runtime ?runtime] [?runtime :seon.runtime/agent ?agent]`
   — **both directions of one edge in one query**. Worth a separate look.
2. **The `:seon.cluster/toolkit` required-set is a G4 trap.** `/toolkit` and
   `/instructions` are required ref sets in `:seon.cluster/cluster`
   (`seon.cluster.edn:8`, `:12`, `:14`). A set with no members emits no datoms
   (`transaction.cljc:739-770`), so sweeping the *last* member turns a required
   key absent and the deletion refuses — while sweeping any earlier member
   passes. The refusal threshold is set membership count, which is nobody's
   intent.
3. **Menu behaviour 5 was invented twice.** `:seon.message/inbox` →
   `:seon.message/read-tx` and `:seon.effect/notify` → `:seon.effect/to` are
   the same construction in two namespaces with no shared vocabulary. Naming it
   (a *pending edge* and its *settled sibling*) would let the next wake-bearing
   attribute copy it instead of reinventing it.

## 7. Decisions that genuinely depend on a policy choice

Everything not listed here is decided by the writer's semantics as they stand,
and is recorded as such above: component edges cascade; required refs already
refuse; optional refs already sweep; mentions carrying a token become values by
G2. Those need no ruling, only docstrings and, where recommended, a change of
required-ness or type.

These five need the owner.

**1. `:seon.eval/origin` has no target type.** It points at "the entity whose
render declared this generated evaluation", which today is an issue, a message,
a plan or an agent. There is no single identity type to convert it to. *Option
one: leave it a ref and accept the sweep*, documenting that a deleted origin
silently un-provenances its evaluations — costs nothing now, and the live
cluster already has three such edges on one deletable issue, so the loss is
real and recurring. *Option two: store the origin as a `[attribute, value]`
identity pair*, which survives any deletion and is queryable by either half —
costs one new two-member component or a tuple attribute, plus rewriting the six
write sites in `src/seon/turn.clj` and the requery path in the block renderer.
**Recommendation: option two.** The requery form is the whole reason the
attribute exists; a requery that points at nothing is the project's named
failure class, and this family's most common deletion (an issue note removed)
hits it every time.

**2. `:my.note/about` and `:seon.message/about`.** Both name a subject; only
the message's presence carries a semantic flag (`:seon.wake/inside true`).
*Option one: convert both to identity values*, which makes them survive any
deletion and makes the inside/outside wake classification independent of the
subject's lifetime — costs an identity-pair carrier, the same one option 2
above needs, so the two decisions share their cost if taken together. *Option
two: make both required refs*, so a subject cannot be deleted while a note or
message concerns it — costs nothing to declare, but it means an issue note
deletion now refuses whenever any message mentions the issue, which on the live
cluster would block the routine file-driven issue retraction at
`src/seon/issue.clj:405-407` immediately. **Recommendation: option one**, and
take it in the same edit as decision 1, since it is the same carrier.

**3. `:seon.cluster.eval/refreshes`.** The since-diff's supersession edge:
"this evaluation replaces that prior one" (written at `src/seon/turn.clj:867`,
read at `:842`). *Option one: leave it a sweepable optional ref* — the cost is
that compaction of an older evaluation silently breaks the supersession chain,
and the since-diff then treats a refreshed read as unrefreshed and re-evaluates
it; harmless but wasteful, and invisible. *Option two: make it a value* (the
prior evaluation's `:seon.cluster.eval/id`), so the chain is readable even
after compaction, and "superseded an evaluation that no longer exists" is one
clause instead of an impossibility. **Recommendation: option two**, for the
same reason G2 gave for call edges — but it is genuinely low stakes and could
wait.

**4. Does an agent get deleted at all?** *Option one: rule that it never is* —
an agent is closed by a positive `:seon.agent/closed-tx`, the three accidental
refusals in §5(c) become one declared rule, and `:seon.message/from`,
`:seon.issue/created-by`, `:seon.ns/steward` and the occurrence family stop
being at risk from the largest blast radius in the population. Costs one new
attribute and a derived `open?`. *Option two: make agent deletion work* —
requires a coordinated retraction of turns, evaluations, captures, effects,
messages and issues in one transaction, plus a decision about what happens to
another agent's message history that names the deleted sender. **Recommendation
strongly: option one.** Nothing in `src/` asks to delete an agent, the writer
already refuses it three ways, and "closed by a fact" is the construction this
project uses everywhere else.

**5. Is the eval-path review's "every non-component ref is living" the right
blanket rule for this family?** *Option one: adopt it* — uniform, one generic
check, no per-attribute property, and it cannot silently exempt a new
declaration. Costs: it would make `:my.plan/current-step`,
`:seon.runtime/trigger`, `:seon.turn/trigger`, `:seon.message/caused-by` and
`:seon.ai.attempt/failover-from` into deletion blockers, and all five are
docstring-declared or code-declared *provenance and live pointers* where
sweeping is the correct and already-intended behaviour —
`:my.plan/current-step` says so in its own schema, and `src/seon/turn.clj:4188`
says so in a comment. *Option two: keep the existing required/optional dial as
the classifier*, write the five behaviours into the docstrings, convert the
mentions to values, and add the generic check only for the residue. Costs: no
uniform guarantee; a newly declared optional ref defaults to sweep, silently.
**Recommendation: option two for this family, with one addition** — the generic
check should refuse not on *every* non-component ref, but on any swept ref
whose absence is semantically load-bearing in the referrer, which is a
declarable property with exactly three members in this family today
(`:seon.listen/entity`, `:seon.message/about`, `:seon.message/from`). If that
property is unpalatable as a new declaration, decision 1's identity-pair
carrier removes all three cases and the property is unnecessary — which is the
cheaper path and the one this note recommends overall.

## 8. What this note does not cover

The program-graph families (`seon.fn*`, `seon.ns`, `seon.schema*`,
`seon.test*`, `seon.source`), the operator and maintenance families, and
`seon.render*`. The cross-family couplings discovered here — the
`:seon.issue/tests` retention lock on test deletion (§5f), and
`:seon.schedule.task/function` blocking function deletion (§3.5) — belong to
whoever holds those families and are recorded here because the agent side is
where they fire.
