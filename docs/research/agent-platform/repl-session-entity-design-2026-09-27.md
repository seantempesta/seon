---
type: research
status: data-model plan, fourth revision (components all the way down; P0 incremental validation and P1 bounded read evidence as prerequisites) after owner rulings R1–R6 and the modeling review (2026-09-27); for Astra review, then the owner; no source changed
created: 2026-09-27
branch: refactor/agent-platform
source-pin: d7d185a7e (checkout); live default pid 22787, commit 6ab93d43, basis 536879662
lane: design-repl-session-opus
supersedes: ca19ad74b's shape; the `:seon.agent/cluster` proposal of config-read-scope-design-2026-09-27.md (10192e1ee)
reads: agent-session-vocabulary-2026-09-27.md (b0c5d4a89), repl-session-prior-art-2026-09-27.md (08bd655aa)
---

# The agent's record, told as a story: agent → session → turns → forms, and one hop to everything else

What exists: root's record on default is a flat star of about 124 edges, almost all of them reverse refs other
entities point at it: 61 turns, 27 messages to it, 22 messages from it, 3 schedule tasks, 3 firings, 3 maintenance
requests, 3 error occurrences, and back-refs from its own runtime, settings, plan and namespace. The turn system
was built as a set of proofs of concept, not from the agent's side. Target: the agent owns a few forward
attachments, each with a declared render. Its REPL session owns an ordered list of turns, and each turn owns its
forms. Everything else the agent must see is one declared attachment away and appears as one node, never as
one edge per item. Principal Clojure approach: model each relationship as exactly one of the guide's five choices
(guide §3), write every change a turn makes in its one settlement transaction, and let context be the attachments'
render functions.

**Skills and guides read end to end for this revision:**
- `.agents/skills/data-oriented-clojure/SKILL.md`
- `.agents/skills/data-modeling/SKILL.md`
- `.agents/skills/datahike/SKILL.md`
- `docs/seon/architecture/data-modeling-guide.md`
- the two research notes above

§11 checks every modeling choice against them and lists the skill claims that are stale.

## 0. What changed since ca19ad74b, and why

| Earlier decision | Now | Reason |
|---|---|---|
| `:seon.agent/runtime` → `:seon.agent/sci` holding only the current namespace | **`:seon.agent/session`**. It holds the current namespace and the forward, ordered list of its turns. | R1 puts the turns under the session. A SCI world holds no turns, so "sci" would no longer be true. The vocabulary survey (b0c5d4a89 §4.1) recommends `session`, which is nREPL's name for exactly this state. |
| History by the reverse of `:seon.turn/agent`; delete `:seon.runtime/turns` | **Forward edge `:seon.agent.session/turns`** (ordered by `:seon.turn/ordinal`). `:seon.turn/agent` is deleted. | R1: attach forward from the parent, one direction. |
| (second revision) Turns as forward peer refs, to avoid the validation cost | **Components all the way down: session ⊃ turns ⊃ forms**, which is the shape the story wants (R1). Two of our own defects become prerequisite slices so the model does not bend around them. | §3: the cost measured in the second revision comes from (1) the writer re-validating the whole owning value on any write, and (2) forms storing unbounded read plans. P0 (incremental owned-value validation) and P1 (bounded read evidence) fix those. Peer-ref turns remain only as the fallback if P0 cannot keep the guarantee (Q1). |
| Each evaluation keeps a `:seon.cluster.eval/ns` **ref** | **`:seon.cluster.eval/namespace`, a symbol value** (new key) | Statement versus token (data-modeling skill lines 40-70). "This form ran in `x`" is an observation that must survive `x`'s deletion. The session's current namespace stays a ref. |
| Inbox left implicit | Messages stay peer entities with `from`/`to`. The inbox is a **declared attachment**, rendered as one node. | R5 (§9) |
| Compaction retracts evaluations | Compaction retracts chosen forms and appends a system turn with new forms, in one transaction. Attachments are untouched. `:seon.db/no-history?` is removed from write-once form text. | R3 (§8). Today four form-text attributes are declared no-history, so compaction erases their text permanently. |
| No oversight fact | `:seon.agent/oversees`: peer refs from root to the agents it runs, rendered as a one-line summary per agent | R4 (§10) |

Kept from ca19ad74b:
- the per-fact audit method, now with a consumer column
- the settings link `:seon.config/base` and one `seon.ai/agent-settings` reader
- the move-namespace request
- the retirement of `:seon.turn/starting-ns` and the stored `:seon.sci.eval/ending-ns`
- the read-evidence cost slice

## 1. Root's graph, before and after (R6: the acceptance picture)

### Before (default, measured §14)

```
                                    root
  ┌──────────┬──────────┬───────────┬┴──────────┬───────────┬────────────┬───────────┐
61 turns   27 msgs→   22 msgs←   3 sched.   3 firings  3 maint.req  3 error occ.  back-refs: runtime,
(:seon.    (:seon.    (:seon.    tasks      (:fire/    (:request/   (:occurrence/ settings, plan,
turn/agent) message/to) message/   (:task/    agent)     agent)       agent)        :seon.ns/steward
                        from)      owner)
all at level 1, unordered, ~124 edges; runtime → the same 61 turns again; evaluations reached only in reverse
```

### After (the picture the debug page must show)

```
root                                                   level 1: one node per attachment (9)
├─ session ─── ns → my.agents.root                     component (one, ongoing)
│    └─ turns 1 … 61   (ordered by :seon.turn/ordinal)  level 2: components, in order
│         └─ forms 0 … n  (ordered by ordinal)          level 3: components of their turn
├─ plan                                                component
├─ settings ── base → config "default"                 component overlay + required ref
├─ home namespace → my.agents.root  · stewards 1       ref · declared derived
├─ inbox (0 unanswered)                                declared attachment over :seon.message/_to
├─ faults (3)                                          declared attachment (occurrences + of-steward)
├─ schedules (3)                                       declared attachment over :seon.schedule.task/_owner
├─ tasks (0)                                           declared attachment over :seon.task/_agent
└─ oversees → live agents (0 today; 112 retired)       peer refs, summary render
```

A graph node at level 1 is either a forward attribute of the agent or a declared attachment. Every reverse
collection is **one** node carrying a count, expanded on click, as the declared `:seon.render/units` list already
names them (`resources/seon/schemas/seon.agent.edn:36-45`). Today `walk/graph` follows every ref "forward and
reverse" (`src/seon/render/walk.clj:1033-1081`), which is what draws the star.

## 2. The target shape (R1)

```clojure
;; agent: its attachments (forward), each with a render pair on the attribute
#:seon.agent{:session  [:and {:seon.db/component true :seon.db/component-schema :seon.agent.session/session} :seon.db/ref]
             :plan     … ; unchanged component
             :settings … ; unchanged component (gains :seon.config/base, §7)
             :namespace … ; unchanged: home assignment
             :oversees [:set {:description "Agents this agent runs and answers for."} :seon.db/ref]}   ; R4

;; the session (identity-less component; its owner is the agent's forward edge)
#:seon.agent.session{:ns    [:and {:description "The namespace the REPL is in: SCI's *ns* after the last settled form, or where a request moved it."} :seon.db/ref]
                     :turns [:set {:seon.db/component true :seon.db/component-schema :seon.turn/turn
                                   :description "The session's turns, in :seon.turn/ordinal order (components; Q1)."} :seon.db/ref]
                     :session [:map {:seon.db/attributes true :seon.render/ai … :seon.render/html …}
                               [:seon.agent.session/ns {:optional true} :seon.agent.session/ns]
                               [:seon.agent.session/turns {:optional true} :seon.agent.session/turns]]}

;; the turn (today's :seon.turn entity; the code's "run" wording becomes "turn")
#:seon.turn{:ordinal [:int {:min 0 :description "Position in its session; stable across compaction."}]
            :forms   [:set {:seon.db/component true :seon.db/component-schema :seon.cluster.eval/receipt} :seon.db/ref]}
;; retired: :seon.turn/agent, :seon.turn/starting-ns, :seon.cluster.eval/run, :seon.cluster.eval/ns,
;;          stored :seon.sci.eval/ending-ns, :seon.eval/renderer-fn, :seon.agent/runtime and :seon.runtime/*

;; the form (today's evaluation entity; identity id/evaluation(turn, ordinal) unchanged)
#:seon.cluster.eval{:namespace [:symbol {:seon.db/index true :description "The namespace this form ran in, as observed. A value: it outlives the namespace."}]}
```

The **run** is derived, not stored (vocabulary b0c5d4a89 §4.2). It is the turns spent against one budget since the
anchor wake. Renames that follow: `:seon.config.run/max-episode-runs` → `:seon.config.run/max-turns`, and
`episode-runs`/`episode-exhausted!` → `run-turns`/`run-exhausted!` (`src/seon/turn.clj:2855, 5571`).

## 3. Component validation cost (C), measured, and the options

The writer validates the complete owning value of every entity a write touches. It walks owners before and after up
to the roots, then expands each root's whole component tree (`write-owned-values-error`,
`src/seon/db.clj:3540-3760`). It refuses past `:seon.config.db/validation-node-limit`, which is 250,000 on default.
Measured with `seon.db/with`, read-only (§14 probe 12):

| Write | Owning value today | Validation + with |
|---|---|---|
| empty transaction | — | 0.8–1.0 ms |
| message content | the message | 2.6–3.9 ms |
| one field on a turn (`reply-size`) | root's tree: agent, plan, settings, runtime + 61 turns + attempts (369 maps) | 7.1–8.1 ms |
| same, with 500 more empty turns | 561 turns | 43.6–50.3 ms |
| same, with 2,000 more empty turns | 2,061 turns | 113.5–126.3 ms |
| one field on the evaluation holding the 1.5 MB evidence plan | that evaluation + evidence | 137–193 ms |
| every one of root's 117 evaluations touched once | all evaluation trees | 1,795–1,932 ms |

The rising rows are not a property of the model. They come from two defects of ours, and the model should not
bend around them (root and owner, 2026-09-27).

**Defect 1: the writer validates owned size, not the change.** Validation proceeds in three steps:
1. Seeds are every entity in the report's attempted and effective datoms, plus the targets of component datoms
   (`src/seon/db.clj:3663-3665`).
2. `owning-ancestors` walks each seed up to its roots, before and after (`:3652-3662`).
3. The reduce at `:3674-3722` expands **each root's entire component tree** and re-reads and re-validates every
   row in it (`row` at `:3588-3597`, `write-entity-value` `:3479`).

So a one-field turn write on root expands agent, plan, settings, runtime, 61 turns and their attempts: 369 maps,
7.6 ms. That grows linearly (114 ms at 2,061 turns) and refuses at `:seon.config.db/validation-node-limit`
(250,000). This violates AGENTS "Make cost follow changed inputs".

The whole expansion buys nothing that the ref schema needs:
- `:seon.db/ref` accepts an entity id as well as a nested map (`resources/seon/schemas/seon.db.edn:40`), so a
  parent row validates with its children as ids.
- Each child is validated separately against its relation's `:seon.db/component-schema` (the second `some` at
  `:3754-3770`).
- An unchanged sibling's validity cannot change unless its datoms or the projection change.

**Defect 2: forms store unbounded read plans.** A form's read evidence stores a `:datahike.read/dependency-plan`
whose `:seon.db/read-index-patterns` list one pattern per matched entity (`db.clj:678`, `:760-853`). The generated
read `(seon.error/for-agent …)` therefore stores 1.5 MB per evaluation (17.6 M characters over 451 plans on
default). No render shows it, yet every validation and every history load decodes it: 137–193 ms for one form,
1.8 s for 117, and 1.5–1.8 s to load root's history.

With both fixed, the story's shape is affordable: **components all the way down**. P0 and P1 become prerequisites
of the shape change P3 (§12).

### P0: incremental owned-value validation (prerequisite)

**Where.** `write-owned-values-error`, `src/seon/db.clj:3540-3760`: replace the root expansion (`:3674-3722`) and
keep owner discovery (`owners-of :3621-3651`, `owning-ancestors :3652-3662`).

**The rule.** Let **C** be the entities this transaction changed:
- the `:e` of every attempted or effective datom, including cascaded and swept retractions, which Datahike writes
  into `:tx-data` (`db/transaction.cljc:814-820`);
- the `:v` of every component datom added or retracted.

Then:
1. **Validate every changed row.** Each nonempty row in C is validated after the transaction against:
   - its identity schemas, when it is a root (`write-entity-error :3502`);
   - otherwise its owning relation's `:seon.db/component-schema`, where the owner is found after.
   Children are validated as refs (ids), not expanded.
2. **Owner invariants for the changed entities only**, both before and after, as the guide §4 requires:
   - at most one owner after (`::multiple-component-owners`);
   - no cycle along the owner chain from each changed entity (the chain walk already visits it; a revisit is
     `::component-cycle`);
   - a nonempty identity-less row in C with no owner after is `::unowned-entity`;
   - a component edge in C pointing at an empty row is `::missing-component`;
   - a changed child whose row became empty re-validates its before-owner's row, because the owner may require
     it (the required-key refusal of guide §3 row 3).
3. **The bound counts work done.** `charge!` charges the changed rows, their owner-chain hops and their direct
   child edges, not whole trees. `::validation-node-limit` then bounds the transaction's size, not the owner's
   history.
4. **Unchanged.** The arity gate's changed-roots walk (`:3724-3752`) already walks ancestors, not trees. The
   final-report hook and the attempted-datom rule (datahike skill lines 276-281) are unchanged.

**Proof obligations** (Astra reviews these before code):
- **(a) A parent's validity is independent of its children's content.** Every component attribute's form is
  `:seon.db/ref` or a collection of it, so no entity schema reads a child's attributes. Enforce this where the
  projection is compiled (`schema.clj`, beside `assert-entity-partition! :1517`): a component attribute whose form
  is anything else refuses. Only then is skipping unchanged children sound.
- **(b) Every entity whose final value differs is in C.** That includes cascades (`retract-components`
  `db/transaction.cljc:832-836`), incoming-ref sweeps (`:1003-1015`) and upsert merges. Datahike emits each as a
  datom, so C is complete by construction; a regression per path proves it.
- **(c) An unchanged row validated at its own write stays valid** while the projection is unchanged. A
  transaction that changes the projection (schema adoption) validates every entity whose schema changed, as
  adoption already does through `attribute-change-tx` (`schema-attribute-change-tx turn.clj:1097`, `seon.cluster/attribute-change-tx`).
- **(d) Before and after owner discovery is kept** for unlinks and moves (guide §4: "A child-only edit or unlink
  finds owners in both before and after databases").

**Cost and target.** About +40/−60 lines in `db.clj`, plus the (a) assertion in `schema.clj` (~10 lines).
Measured targets for R8:
- a one-field turn write ≈ a one-entity write (the message row: 2.6–3.9 ms today), independent of history;
- at 61 and 2,061 turns, within 20% of each other;
- a whole turn settlement ≈ that turn's forms, not the session.

### P1: bounded read evidence (prerequisite)

**Where.** `seon.db/read-evidence` (`db.clj:928`) and pattern capture (`:760-853`).

**The rule.**
- A stored plan keeps at most K entity-level patterns per source. Past K it widens them to attribute-level
  patterns (drop `:seon.db/pattern-entity`).
- Widening is conservative: the currency check (`read-evidence-current?`, `db.clj:~1100-1160`) then reports
  "changed" more often, never less, so refresh may re-run a read but never misses a change.
- K is a declared dial; the design review picks it.
- History and refresh readers stop pulling plans they do not use (`eval.clj:18-20` default selector;
  `latest-evaluations turn.clj:2054-2056`).

**Targets:**
- the largest stored plan is bounded (on root, from 1.5 MB to kilobytes);
- `seon.eval/of-agent` on root drops from 1.55–1.82 s to the selector-only 0.41 ms order;
- validating one form drops from 137 ms to about a message write.

### Options, re-priced

| Option | Guarantee | Cost | Sacrifice |
|---|---|---|---|
| **A (recommended). Components all the way down (agent ⊃ session ⊃ turns ⊃ forms), after P0 and P1** | pull and cascade tell the whole story; deleting an agent retracts its history (kept in history); validation follows the change | P0 ~+50/−60 lines in `db.clj`/`schema.clj` and its proof; P1 in `db.clj` | a correctness-critical writer change lands first. Pull expansion is whole-tree: any pull of an agent without a selector expands its history, so readers use selectors, and the pull work bound (`pull_api.cljc` `charge-work!`) reports an elision past it |
| B (fallback). Session a component; session → turns forward peer refs; forms components of turns | bounded validation without P0 | ~+10 lines over A | no cascade from agent to turns; the story has one peer edge. Use only if P0's proof obligation (a) or (b) fails in review |
| C. Turns point back to the session | bounded | ~0 | reverse edge; the star returns; contradicts R1 |

Data-modeling skill line 179 (and data-oriented skill lines 66-70) say "History, unanswered wakes, and routed
faults remain queries". Under R1 that is **stale for history**: history becomes a forward walk from the agent. It
is **still right for unanswered wakes and routed faults**, which stay derived attachments (§9). The skill is
updated in the implementing commit (§11).

## 4. Relationships: the five choices and statement versus token (A, B)

The guide's §3 table (`docs/seon/architecture/data-modeling-guide.md:89-95`) gives the five choices: component,
optional peer ref, required peer ref, identity value, settlement moves an edge. Statement versus token follows
data-modeling skill lines 40-70.

| Relationship | Kind | Statement or token | Delete target | Delete source | Why |
|---|---|---|---|---|---|
| agent → session | component | statement | — | cascades the session | the session is part of the agent's value (skill lines 97-99) |
| session → current ns (`:seon.agent.session/ns`) | optional peer ref, **moved by settlement** | statement: the agent is in `x` | sweeps; the next turn starts at home | — | a living relation the next turn acts on; the settlement writes its successor each turn (guide §3 row 5) |
| session → turns | component, cardinality-many, ordered by `:seon.turn/ordinal` | statement | — (retracting a turn removes it from the session) | cascades turns, their forms and evidence | the session's turns are part of its value (R1); ordinals carry order (skill lines 91-92; datahike skill lines 329-330). Needs P0 (§3) |
| turn → forms | component | statement | — | cascades forms and their evidence | owned; compaction retracts forms |
| form → evidence | component | statement | — | cascades | owned |
| turn → attempts | component | statement | — | cascades | unchanged |
| form → namespace (`:seon.cluster.eval/namespace`) | **identity value** (symbol) | **token**: "ran in `x`" | nothing changes | — | the history must still say where a form ran after `remove-ns!` (skill lines 56-61) |
| form → renderer (`:seon.eval/renderer`) | identity value (symbol) | token | nothing | — | the same; `:seon.eval/renderer-fn` (the same fact as a ref) is deleted |
| turn → trigger message | optional peer ref | statement (provenance of a living message) | sweeps | — | messages are retracted only deliberately; wake answering uses `:t`, not this ref |
| turn → handled messages | optional peer ref set | statement (claim) | sweeps | — | `seon.turn.edn` `:handled` description |
| message → to agent | **required** peer ref | statement; the listened wake edge | refuses deleting the recipient while the message survives | — | `seon.message.edn:20-30` |
| message → from agent | optional peer ref | statement; the inside-wake marker | sweeps | — | `seon.message.edn` `:from` |
| message → caused-by message | optional peer ref | statement | sweeps | — | chain limit (`cluster/message.clj:44`) |
| agent → home namespace | optional peer ref | statement (assignment) | sweeps; the agent has no home | — | assignment is a living relation |
| namespace → steward agent | optional peer ref | statement (namespace information: where its faults go) | sweeps | — | `steward-call`; same for every viewer |
| agent → settings overlay | component | statement | — | cascades | owned |
| overlay → base config (`:seon.config/base`) | **required** peer ref | statement | refuses retracting a config row agents use | — | an overlay without a base cannot compute settings |
| cluster → config row | peer ref (unchanged) | statement | — | — | Q3 |
| root → overseen agents | optional peer ref set | statement | sweeps | — | R4; retirement retracts the edge |
| error occurrence → agent / turn | optional peer ref (one of them, §5) | statement | sweeps | — | guide §3: a fault keeps its observed *identity* values separately (signature, symbols) |
| schedule task → owner | required peer ref (arming attribute) | statement | refuses | — | `seon.schedule.task.edn:2` |
| firing → agent | optional peer ref, listened | statement; a denormalized routing edge | sweeps | — | kept for the stated reason: "so the wake router needs no query" (`seon.schedule.fire.edn`) |
| capture → turn | optional peer ref | statement | sweeps | — | calibration record |
| form → faults delivered | optional peer ref set | statement | sweeps | — | the observation of what the agent was shown |

Refined guidance for namespaces:
- The session's current namespace is a **ref**, written with an upsert of the namespace identity in the same
  transaction. A lookup ref alone refuses for a namespace with no row: task a318b2d38, confirmed.
- Every historical mention of a namespace, function or renderer is a **value**.

With the evaluation's namespace a value, a nested `in-ns` no longer needs a namespace row for each form at all.
Only the session's final namespace upserts one.

## 5. Per-fact audit, with who uses each fact

Legend:
- **K** keep · **M** move or rename · **D** delete · **N** new.
- "Consumer" names a concrete reader with file:line.
- A fact with no consumer is proposed for deletion with the reason it exists. Hiding it with a filter is not a fix.

### Agent and attachments

| Fact | Verdict | Writer | Consumer |
|---|---|---|---|
| `:seon.agent/id` | K | `creation-tx` `cluster/agent.clj:233` | every lookup |
| `:seon.agent/branch` | K | `branch-call` `cluster/agent.clj:205-215` | custody acquisition; a hosted agent's row on default names another branch |
| `:seon.agent/archived-tx`, `admission-closed-tx` | K | `seon.agent/archive-call` `agent.clj:58`; host retirement | presentation filter; the turn writer's admission refusal (`turn.clj:refuse-closed-admission!`) |
| `:seon.agent/namespace` | K | `creation-tx :234` | start fallback; identity render; issue routing. Stop using it as the current namespace (`turn.clj:2233, 4745, 5334, 5478, 5549`; `render/walk.clj:603-612`) or as ownership (`form-owner turn.clj:2599`; `render.clj:1736` → `:seon.ns/steward`) |
| `:seon.agent/plan` | K | `creation-tx :235` | plan render |
| `:my.plan/agent` | **D** | `creation-tx :235` | none but upsert: a back-ref (datahike skill lines 392-400). Writers address the plan through `:seon.agent/plan` |
| `:seon.agent/settings` | K | `creation-tx :236`, `settings!` `agent.clj:106` | settings render; `agent-settings` |
| `:seon.config/agent` | **D** | `creation-tx :236`, `agent.clj:101` | none but upsert: a back-ref |
| `:seon.config/base` | **N** | `creation-tx` | `seon.ai/agent-settings` (§7) |
| `:seon.agent/runtime` | **M** → `:seon.agent/session` | `creation-tx :237` | — |
| `:seon.runtime/agent` | **D** | `creation-tx :237`; `turn.clj:466, 1622` | none but upsert: a back-ref |
| `:seon.runtime/turns` | **M** → `:seon.agent.session/turns` (component, ordered) | `turn.clj:465-467, 1622-1623` | `eval.clj:49`, `cluster/status.clj:137-142`, `cluster/agent.clj:335-342`, `render/web.clj:3211`, `render/transcript.clj:1149, 2431`, `turn.clj:575, 2059` |
| `:seon.runtime/trigger` | **D** | `open-call turn.clj:468` | only the runtime render (`transcript.clj:1040-1060`), which re-sends a copy of the newest turn's trigger (~240 tokens a turn) |
| `:seon.runtime/listens` | **M** → `:seon.agent/listens` (component) | `issue.clj:1468` | wake routing `cluster/wake.clj:427-429`; issue budget `issue.clj:1456`. 0 datoms on default |
| `:seon.agent.session/ns` | **N** | settlement; a move request's open (§8) | every turn start; the namespace half of context; the `ns=>` prompt |
| `:seon.agent/oversees` | **N** | agent creation by an overseer (`cluster/host.clj:67`, `issue.clj:1338`, `cluster.clj:3383`); retirement retracts it | root's oversight render (§10) |
| `:seon.agent/listens` | **N** (moved) | `issue.clj:1468` | wake routing |

### Turn and forms

| Fact | Verdict | Writer | Consumer |
|---|---|---|---|
| `:seon.turn/id` | K | `open-call turn.clj:462` | identity; result handles via `id/evaluation`; error occurrences; captures |
| `:seon.turn/ordinal` | **N** | `open-call` (count of the session's turns, inside the writer) | ordering in render, graph, history |
| `:seon.turn/agent` | **D** | `open-call :462`, `record-evaluated-call :1583` | 47 source mentions; each becomes `[?a :seon.agent/session ?s] [?s :seon.agent.session/turns ?t]` |
| `:seon.turn/starting-ns` | **D** | `turn.clj:463, 731-732, 787, 912, 1620`; callers `cluster/agent.clj:750`, `bootstrap.clj:316, 343`, `issue.clj:1407` | `fold-namespace :4533`, `stored-record-content :1546`; both replaced by the session |
| `opened-tx`, `closed-tx` | K | `open-call`, `close-call :480-501` | open-turn derivation; wake answering by `:t` (`latest-answering-turn-t turn.clj:3111`) |
| `:seon.turn/trigger` | K | `open-call`; `issue.clj:1408` | the prompt's cause (`turn.clj:4599-4611`); `transcript.clj:141-150` |
| `:seon.turn/handled` | K | `close-call :491-501` | unanswered-message derivation (`turn.clj:497`, `cluster/message.clj:401, 553`) |
| reply, reply-blob, reply-size | K | `plan-call :677-734` | transcript; `reply-size` is also the plan-frozen fence (`plan-call :724`) and the wake-answering basis (`close-call :494`) |
| attempts, disposition, result | K | provider path; settlement | usage calibration; continuation (`next-agent-work`); the completed reply |
| `:seon.turn.work/situation` | K (flagged) | `open-call :462` | `plan-call :727` refusal; `latest-evaluations :2046`. The turn PRD §2 lists this stamp as removed; issue candidate |
| `:seon.turn/forms` | **N** (replaces `:seon.cluster.eval/run`) | the plan freeze (`source-rows :639-675`) and settlement | render, compaction |
| `:seon.cluster.eval/run` | **D** | `receipt-row :954` | 72 mentions in 11 files; the reverse `:seon.turn/_forms` is single-valued for a component (`pull_api.cljc:328-329`) |
| form id, ordinal, at, author, source, comment | K | `receipt-row` | the REPL grammar (`repl.clj`), transcript ordering, refresh authorship rules |
| `:seon.cluster.eval/ns` (ref) | **D** → `:seon.cluster.eval/namespace` (symbol) | `source-rows :666-669` (plan-time guess, deleted) and `evaluation-facts :152` | the `ns=>` prompt (`repl.clj:466-472`, `transcript.clj:278`), `form-owner`, `fn/analyze-form` via `settlement-form :1003` |
| stored `:seon.sci.eval/ending-ns` | **D** | `evaluation-facts :154-156` | `repl.clj:216` (`:seon.repl/ns`: the next prompt shows it), `fold-namespace` (replaced) |
| `:seon.eval/renderer` | K | `evaluation-facts :130` | transcript renderer label |
| `:seon.eval/renderer-fn` | **D** | `evaluation-facts :132` | the same fact as a ref; readers fall back to the symbol already (`transcript.clj:287, 1495, 1563`) |
| shown, error, diagnostics, output | K | settlement | the §15 observation shown to the agent |
| duration and cost measures | K | settlement | findings against the declared bounds (`repl/cost-attributes`) |
| read-basis-transaction, read-evidence | K, bounded (P1) | `receipt-read-evidence-tx :1463` | refresh (`system-plan :2099`, `read-evidence-current?` `db.clj`) |
| `:seon.cluster.eval/faults` | K | `with-fault-windows :3790` | transcript error delivery (`transcript.clj:351-388`) |
| `:seon.cluster.eval/triage-edn` | D (already retiring) | none | none |

### Everything else attached to the agent

| Fact | Verdict | Writer | Consumer |
|---|---|---|---|
| message id, to, content, from, caused-by, about, assignment | K | `cluster/message.clj:231`, `error.clj:1397` | wake routing (`to`); inside-wake marker and assignment queries (`from`, `turn.clj:2662`); chain limit (`caused-by`) |
| error occurrence `agent` and `turn` | K, **one per occurrence** | `error.clj:1492-1493` | `problems.clj:120`, `error.clj:1769-1772, 2066`. When the occurrence happens in a turn, the turn names the agent. Default: 11 agent-only, 0 with a turn |
| `:seon.maintenance.request/agent` | **D** | `schedule.clj:266` | none. The request already names its firing, whose `:seon.schedule.fire/agent` names the agent |
| `:seon.schedule.fire/agent` | K | `schedule.clj:389` | the wake router (listened; its description states the denormalization) |
| `:seon.schedule.task/owner` | K | `schedule.clj:96, 726` | arming (`:seon.wake/arms`), `schedule.clj:182, 327` |
| `:seon.task/agent`, `:seon.issue/agent`, created-by | K | task/issue writers | assignment routing; the tasks attachment |
| `:seon.ns/name` rows | K | `namespace-rows turn.clj:619`, now called only for the session namespace | the session ref target; program rows by definition digest (`program.cljc:614-655`) |
| `:seon.ns/steward` | K | `steward-call` | fault and complaint routing (`problems.clj` `unstewarded-namespaces`), the namespace render, the agent's "stewards" line |
| `:seon.context.capture/*` | K | `context/capture-tx context.clj:359` | token calibration (`seon.ai.tokens/characters` join) |
| `:seon.context.contribution/agent`, `/evaluations`; `seon.context/selection`, `append-tx`, `compact-tx` | **D** | `context.clj:76-256` | none outside `context.clj` (searched src, script, test) |

### Awkward on click in root's current graph, and what each becomes

| Clicked | What it is | Becomes |
|---|---|---|
| runtime → 61 turns, and the same 61 turns again at level 1 | one relation drawn twice | session → turns, ordered, level 2 |
| runtime → trigger | a copy of the newest turn's trigger | gone; the turn shows its trigger |
| runtime/settings/plan back-refs pointing at root | back-refs used only for upsert | gone |
| 27 + 22 message edges | peer messages drawn one edge each | one inbox node (unanswered); the rest under the turns that were triggered by or handled them |
| 3 maintenance requests | a copied agent ref nobody reads | gone |
| 3 firings + 3 schedule tasks | a routing edge + owned schedules | one schedules node |
| a turn's `starting-ns` | copy of its first form's namespace | gone |
| an evaluation's `ns` + `ending-ns` + `renderer-fn` | one fact two ways ×3 | `namespace` symbol, `renderer` symbol |
| `:seon.turn.work/situation` | a stored label ruled removed | flagged (issue) |

## 6. The agent's attachments and their renders (R2)

Context = render(agent). Each attachment's declared render emits REPL entries (forms plus their results). The
detail is one requery away as a runnable form. Token figures are estimates (chars/4) measured on root unless
marked.

| Attachment | Render shows | Requery for detail | Tokens |
|---|---|---|---|
| identity (scalars) | id, branch, home ns, current ns, namespaces it stewards | `(seon.db/pull … [:seon.agent/id "root"])` | ~40 (est.) |
| session | the forms since the last compaction, each `ns=>` prompt from its form's `namespace` value | a turn's forms by ordinal; history `as-of` before a compaction | root 34,593 stored today; 86% three repeated generated reads (§14) |
| plan | objective, current step, ready and blocked steps | `(seon.plan/plan {})` full tree | ~130 (root: 264 over 2 evaluations) |
| settings | the dials declared `:seon.config/shown` + the set the agent overrides | `(seon.ai/agent-settings (seon.db/db) "root")`, 82 dials | ~90 shown; ~880 full |
| inbox | unanswered messages only (to me, no handling claim) | the triggering or handling turn of any older message | ~23 each (root: 0 unanswered) |
| faults | unresolved occurrences routed to me and to namespaces I steward | `(seon.error/for-agent (seon.db/db) "root")` | ~325 today per refresh |
| schedules | next firing per task | the task's firings | small |
| tasks | open assigned tasks | the task entity | 0 on root |
| oversees (root) | one summary line per live agent | that agent's own render | ~30 per agent (est.); 0 live on default now |
| namespace half (not an attachment) | `(dir <session ns>)` | `(doc sym)` | 32 (`my.agents.root`) to 11,284 (`seon.turn`) raw |

**Per turn.** The opening after a compaction is the sum of the attachment renders plus the namespace half: about
40 + 130 + 90 + inbox + faults + dir. Every later turn appends only what changed, under the existing refresh (turn
PRD §14): a changed plan re-renders the plan, a new message re-renders the inbox, an `in-ns` appends
`(dir <new-ns>)`. Deleting the runtime-trigger read saves ~240 tokens a turn on root. Restricting the inbox to
unanswered messages replaces root's ~616-token message read with 0 today.

**The two halves (owner, 2026-09-27).**
- The agent half is every attachment above. It follows the agent.
- The namespace half is selected by `:seon.agent.session/ns` and is the same for every agent there.
- `:seon.ns/steward` is namespace information: where the namespace's faults go. The agent sees it through its
  stewards line.
- Today the opening's namespace member is the home namespace (`render/walk.clj:603-612`); the target reads the
  session's.

## 7. Settings (unchanged from ca19ad74b §6, restated briefly)

**How clusters get their config today:**
- `config/default.edn` (653 lines, one decision per dial), a sparse overlay (`config/development.edn`) and the
  environment compile into one config row per cluster (`config.clj:1-14, 543, 736`), applied at boot
  (`cluster/boot.clj:718, 762`, `cluster.clj:3362`).
- `bin/seon config apply [NAME] PATH` changes a cluster on the fly; its held overlay keeps earlier decisions
  (`config.clj:810-860`).
- `seon.agent/settings!` (`agent.clj:106-120`) overrides any of 34 per-agent dials for one agent, including
  model and provider. The next turn's composition (`cluster/prompt.clj:48-72`) uses it.

**Target:**
- The overlay gains `:seon.config/base`, a required ref to its cluster's config row, written at creation.
- One reader, `seon.ai/agent-settings [db agent-id]`, computes base effective merged with the overlay.
- The 18 guessing sites of 10192e1ee §1 A become its consumers (agent-scoped A1, A4–A13), pass settings they
  already hold (A2, A3, A14, A15), or name their cluster from the world they hold (A16–A18).
- The render shows the dials declared `:seon.config/shown` (replacing the hand list `agent.clj:147-154`) with the
  override set, and the full map is one requery away.
- `:seon.config.agent/show-all-settings` is retired: it is presentation state, and the requery replaces it.

## 8. The session: current namespace, moving it, compaction (R3)

**Writes.**
- The settlement of a turn, in its one transaction, upserts `{:db/id "namespace:x" :seon.ns/name 'x}` and asserts
  `:seon.agent.session/ns` through a transaction function that reaches the session from the agent (no back-ref).
- `x` is the in-memory `:seon.sci.eval/ending-ns` of the last settled form, SCI's `*ns*`
  (`sci/eval.clj:4755`, threaded at `turn.clj:5184`).
- Each form stores its own `:seon.cluster.eval/namespace` symbol, so no other namespace row is needed. That fixes
  a318b2d38.
- An unchanged namespace emits no datom.
- A request carrying an optional `:seon.ns/name` asserts the same fact in the turn's open transaction (R3 of the
  previous round: "just updating data we should already be reading"). Every turn start reads `(or session-ns home)`.
- The refusal arm writes the session too, because SCI has already moved (Q4).

**Compaction is one transaction:**
1. Retract the chosen forms (`[:db/retractEntity form]`, which cascades their evidence).
2. Keep every turn. Turns carry the wake-answering, claim and calibration facts other readers use (§5).
3. Append one system turn at the next ordinal whose forms are the new context. That context is the re-rendered
   attachments (the opening) and any authored instruction, for example what to do after compaction, as an
   ordinary form with a comment and a result.

Plan, settings, messages, faults, tasks and the session namespace are attachments, not forms, so compaction never
touches them and the plan stays stable (R3).

**Nothing is deleted (R3) needs a schema fix.** `:seon.cluster.eval/source`, `:comment`, `:output`,
`:diagnostics` (`seon.cluster.eval.edn:7, 14, 24, 32`) and `:seon.turn/reply` (`seon.turn.edn:135`) declare
`:seon.db/no-history? true`, so retracting a form erases its text from history
(`db/transaction.cljc:441-485`; datahike skill lines 106-112).
- These attributes are written once and never updated, so `noHistory` saves nothing until retraction. There it
  destroys exactly what R3 says to keep.
- Remove the flag from them. The retention cost is the retraction datom per compacted form.
- History of this kind needs `:seon.config.db/keep-history? true`, the shipped default in `config/default.edn`.

**Forms are easy to manipulate.** A form is addressed by `[:seon.cluster.eval/id (id/evaluation turn ordinal)]`,
ordered by `:seon.cluster.eval/ordinal` within a turn and by `:seon.turn/ordinal` across turns. Compaction and
any agent can therefore select forms by position, source, author or namespace with one query over the session's
turns, and replace them in one transaction.

## 9. Messages (R5)

- A message stays one entity with `from` and `to`. Sending and receiving are the same single entity.
- A message cannot be a component: it has two parties, and a component has one owner (skill lines 97-99;
  datahike skill lines 388-390).
- A forward `:seon.agent/inbox` edge on the recipient would store the `to` relation twice. `to` is also the
  listened wake edge (`seon.message.edn:20-30`), so it is the one to keep.
- The inbox therefore appears as a **declared attachment**: `:seon.message/_to` filtered to unanswered (no
  handling claim), rendered once as one node with its count. That is how it avoids the flat star.
- Answered messages are reached from the turns that were triggered by or handled them (`:seon.turn/trigger`,
  `:seon.turn/handled`), which is where they belong in the story.
- There is no outbox attachment. A sent message is visible in the sender's history as the form that sent it. The
  `from` edge's consumers are the inside-wake marker and assignment queries.

## 10. Root (R4)

Root is an ordinary agent with one more attachment, `:seon.agent/oversees` (optional peer refs to the agents it
runs).

- **Writers:** the creation paths that make a hosted or worker agent add the edge. Retirement (admission closed)
  retracts it, and history keeps it.
- **Render:** one line per agent: id, current namespace, open or idle, turns in the current run against the
  budget, unanswered count, last settled instant. About 30 tokens per agent. Each line's requery is that agent's
  own render.
- **Default today:** 112 hosted agents, all admission-closed. After the change, root's summary lists the live
  ones only.

## 11. Every modeling choice against the skills, and stale skill claims

| Choice | Skill/guide line | Holds? |
|---|---|---|
| session, plan, settings, forms, evidence as components; cascade and pull expansion | data-modeling skill lines 30, 97-99; datahike skill lines 380-391; guide §4 (lines 122-143) | yes |
| no back-ref identities on components; derive the parent from the forward edge | datahike skill lines 383-384 and 392-400; guide line 137 | yes. **Conflicts with turn PRD §18c** ("Components the agent writes to carry an identity derived from the owner … `:seon.runtime/agent`"). The plan follows the skill; writers use transaction functions |
| turns as components of the session | data-modeling skill lines 97-99 ("Choose components when children belong to one parent's value"); guide §4 | yes: a turn belongs to one session. Other entities (occurrences, captures) reference it by ordinary ref, which the skill allows for identified owned rows. Affordable only with P0; the guide §4 guarantee is kept by P0's proof obligations |
| order by ordinal on members | data-modeling skill lines 91-92; datahike skill lines 329-330; guide §6 (lines 176-177) | yes |
| identity-less session component | datahike skill lines 88-90 (unowned identity-less rows refuse; owned ones validate by component schema); `schema.clj:1517-1537` requires a partition only for identity-bearing schemas | yes |
| form namespace, renderer as values | data-modeling skill lines 40-70, 33 | yes; supersedes "lookup refs everywhere" |
| session ns as optional ref moved by settlement | guide §3 rows 2 and 5 | yes |
| required refs: message → to, overlay → base | datahike skill lines 60-82; guide §3 row 3 | yes |
| identity-only namespace rows are program-partition rows that never merge | `seon.ns.edn:10-18`; `program.cljc:614-655` (merge by digest) | yes |
| history of compacted forms | datahike skill lines 106-112; guide §2 (lines 49-61) | only after removing `no-history?` (§8) |
| context as attachments' renders | data-modeling skill lines 167-178 ("record and render contract") | yes |
| "history … remain queries" | data-modeling skill line 179; data-oriented skill lines 66-70 | **stale for history** under R1 (§3) |

**Proposed in-place correction of data-modeling skill line 179** (for root to apply with the owner's ruling; the
data-oriented skill's lines 66-70 take the same edit):

> Unanswered wakes and routed faults remain queries, rendered as declared attachments of the agent. History is
> not a query: it is the agent's session, attached forward (agent → session → ordered turns → forms), walked by
> the render from the agent.

**Name check.** `:seon.agent/session` (`:seon.agent.session/ns`, `:seon.agent.session/turns`) is adopted, not
`:seon.agent/sci`:
- It matches the settled vocabulary (f49086d5c) and the survey (b0c5d4a89 §4.1).
- It keeps "SCI context" for the live, unstored object only.
- No skill argues otherwise: the data-modeling skill asks for the dependency's or Clojure's term, and nREPL's
  term for this state is session.

"Run" for one turn is retired in the same slice: `seon.render.transcript/render-run-ai` and `render-run-html`
(`seon.turn.edn:5-6`, `render/transcript.clj:782, 898`) become `render-turn-ai` and `render-turn-html`.

**Stale skill and guide claims (a stale skill is a high-priority defect).** None are edited here; this lane owns
only this document. Each is fixed in the slice that touches its subject, or by root.

`.agents/skills/datahike/SKILL.md`:

| Claim | Cites | Current source |
|---|---|---|
| `q` | `db.clj:2438` | `:2450` |
| `pull` | `:2661` | `:2673` |
| `transact!` | `:4928` | `:5094` |
| `call-with-custody` | `:383` | `:390` |
| `read-evidence` | `:920` | `:928` |
| `write-entity-error` | `:3485` | `:3502` |
| `write-owned-values-error` | `:3523` | `:3540` |
| `system-turn` | `turn.clj:2157` | `:2222` |
| `system-plan` | `:2034-2074` | `:2099` |
| `latest-answering-turn-t` | `:3041-3066` | `:3111` |

`.agents/skills/data-oriented-clojure/SKILL.md`:

| Claim | Cites | Current source |
|---|---|---|
| `q` | `:2438` | `:2450` |
| `pull` | `:2661` | `:2673` |
| `transact!` | `:4912` | `:5094` |
| `collect-contracts!` | `instrument.clj:770-780` | `:767` |
| `apply!` | `:799` | `:796` |
| `base-ctx` | `sci/eval.clj:3350` | `:3514` |
| `fork-for-turn` | `:3156` | `:3320` |
| `regenerate-agent-context!` | `:3082` | `:3246` |

`.agents/skills/data-modeling/SKILL.md`:

| Claim | Cites | Current source |
|---|---|---|
| `write-owned-values-error` | `db.clj:3523` | `:3540` |
| `removed-definition-error` | `:4111` | `:4152` |
| `deletion-error` | `:4171` | `:4219` |
| `fork-for-turn` | `sci/eval.clj:3156` | `:3320` |
| `regenerate-agent-context!` | `:3082-3154` | `:3246` |

`docs/seon/architecture/data-modeling-guide.md`:
- §7 says "Current Datahike pull defaults each many-valued attribute to 1,000". False: `+default-limit+` is nil
  (`reference-code/datahike/src/datahike/pull_api.cljc:16`, used at `:315`, `:323`), as the datahike skill lines
  182-191 already say.
- Its §4 grounding `db.clj:3586-3609, 4050-4121` no longer matches (`:3540-3760`; final report
  `write-report-error :4436`).
- `write-owned-values-error`'s own docstring ("pull's 1,000 cap") repeats the stale default.

## 12. Phased plan

Each slice is one loadable commit. It converts every caller and test it touches, and it is run centrally by root
with the named tests. Additions adopt as accretion. Retirements adopt in place (`f9fc0e0d0`, `da10c8912`) in the
same commit as their writer conversions.

| # | Slice | Owner files | Tests | Adoption / RESET |
|---|---|---|---|---|
| **P0** | **Incremental owned-value validation (prerequisite, §3)**: validate changed rows and owner invariants only; the (a) component-form assertion | `src/seon/db.clj` (`write-owned-values-error :3540-3770`), `src/seon/schema.clj` (beside `:1517`) | `seon.db` write-admission and component tests (cascade, sweep, unlink, move, multiple owners, cycle, unowned row, missing child, adoption); R8 | none |
| **P1** | **Bounded read evidence (prerequisite, §3)**: at most K entity patterns per source, widened past K; history and refresh selectors drop plans | `src/seon/db.clj` (`:760-853`, `read-evidence :928`), `src/seon/eval.clj`, `src/seon/turn.clj` | read-evidence currency and refresh tests; clock rows for `of-agent` (1.55–1.82 s today) and one-form validation (137 ms) | none; existing large plans stay until the P3 reset |
| P2 | Session namespace and form namespace: `:seon.agent.session/ns` on today's runtime component; `:seon.cluster.eval/namespace` symbol; delete the plan-time ns guess, `starting-ns`, stored `ending-ns`, `previous-ending-namespace`, `fold-namespace`, the submit-path lookup; the move-namespace request key; system turns and segmentation start at the session; remove `no-history?` from form text | `seon.runtime.edn`, `seon.cluster.eval.edn`, `seon.turn.edn`, `seon.agent.edn`, `turn.clj`, `cluster/agent.clj`, `repl.clj`, `render/transcript.clj`, `bootstrap.clj`, `issue.clj`, `cluster.clj`, `fn.clj` (eval-ns readers `:1226-1308`); ~20 test files | R1–R4, R6 | accretion + retirement; no reset |
| P3 | The shape (after P0 and P1): rename runtime → `:seon.agent/session`; `turns` as components with `:seon.turn/ordinal`; `:seon.turn/forms` component; delete `:seon.turn/agent`, `:seon.cluster.eval/run`, `:seon.runtime/trigger`, `/agent`; move listens; rename `render-run-ai/html` → `render-turn-ai/html`; scripted sweep of 47 + 72 source mentions and ~120 test files | `seon.runtime.edn` (retired), `seon.agent.edn`, `seon.turn.edn`, `seon.cluster.eval.edn`, `turn.clj`, `eval.clj`, `cluster/status.clj`, `cluster/agent.clj`, `cluster/wake.clj`, `cluster/message.clj`, `issue.clj`, `problems.clj`, `repl.clj`, `prompt.clj`, `fault.clj`, `oversight.clj`, `test.clj`, `render/web.clj`, `render/transcript.clj`, `context.clj`, tests | turn, transcript, status, wake, message, web debug; R8, R9 | **RESET NEEDED**: existing turns carry only the retired reverse edge. Data is disposable; no migration |
| P4 | Graph and renders: `walk/graph` follows forward attributes and declared attachments only, each reverse collection one node; the namespace half reads the session; the attachment renders (§6); inbox restricted to unanswered | `render/walk.clj`, `render/transcript.clj`, `cluster/message.clj`, `cluster/agent.clj` (agent render), `seon.agent.edn` units | R5, R7; debug-page graph screenshot = §1 | none |
| P5 | Settings link and render (§7) | `seon.config.edn`, `seon.config.*.edn`, `ai.clj`, `cluster/prompt.clj`, `agent.clj`, `cluster/agent.clj`, the A-list owner files | config, prompt, message, plan, issue, blob; R10 | **RESET NEEDED** (existing overlays lack `base`); batch with P3 |
| P6 | Root oversight: `:seon.agent/oversees` and the summary render | `seon.agent.edn`, `cluster/host.clj`, `issue.clj`, `cluster.clj`, `cluster/agent.clj` | R11 | covered by the P3/P5 reset |
| P7 | Remaining doubles: `:my.plan/agent`, `:seon.config/agent`, `:seon.eval/renderer-fn`, `:seon.maintenance.request/agent`, occurrence agent-or-turn, ownership via steward, run renames (`max-turns`, `run-turns`) | `my.plan.edn`, `seon.config.edn`, `seon.eval.edn`, `seon.maintenance.request.edn`, `schedule.clj`, `plan.clj`, `agent.clj`, `error.clj`, `render.clj`, `turn.clj`, `seon.config.run.edn` | plan, settings, error, routing, maintenance | retirements; no reset |
| P8 | Dead context selection | `context.clj`, `seon.context.contribution.edn` | context | retirement |

**Order.**
1. P2 first: it fixes the confirmed data loss of a318b2d38 and needs neither prerequisite.
2. P0 and P1 in parallel with P2's review: they own different code in `db.clj`, so they land serially in that file.
   They are prerequisites of P3. P1's `turn.clj` selector change waits for P2 to release that file.
3. P3 + P5 + P6 land together behind one RESET, batched by root, only after P0's R8 and P1's clock rows pass.
4. P4 follows.
5. P7 and P8 come last.
6. If P0's review finds proof obligation (a) or (b) cannot hold, P3 lands option B instead (§3 table); nothing
   else in the plan changes.

**Sizes** (production, approximate):

| Slice | Lines |
|---|---|
| P0 | +50/−60 |
| P1 | +30/−10 (design review sets K) |
| P2 | +50/−150 |
| P3 | +60/−90 (mostly scripted query rewrites) |
| P4 | +40/−40 |
| P5 | +40/−60 |
| P6 | +30/−0 |
| P7 | +20/−80 |
| P8 | −190 |

P3's test sweep is the largest cost: ~120 files naming `:seon.turn/agent` or `:seon.cluster.eval/run`. It is a
scripted rewrite, then lint, load and hand-fix.

**Risks.**
- **P0 is the most correctness-critical change in the plan.** It narrows what the final writer re-checks. Its
  proof obligations (§3) and one regression per refusal class (cascade, sweep, unlink, move, multiple owners,
  cycle, unowned row, missing child, schema adoption) must pass before P3 relies on it.
- **Pull expansion.** With turns as components, pulling an agent with a wildcard, or naming a component attribute
  without a sub-selector, expands its whole history: `pull_api.cljc:346-351`, datahike skill lines 217-225. No
  production reader pulls an agent with a wildcard today (searched src and script). The render and the graph name
  sub-selectors. A wildcard pull by an agent at the REPL is bounded by the pull work charge
  (`pull_api.cljc` `charge-work!`), and the floor reports the elision.
- **Ordinal writes.** The ordinal is computed inside the open writer (a count of the session's turns); concurrent
  opens are already serialized by the one-open-turn fence (`open-call :448-458`).
- **Opening bytes** change once (turn PRD §16a).

## 13. Class regressions

- **R1 in-ns mid-turn.** A 3-form turn whose form 2 is a top-level `(in-ns 'probe.b)`: forms record their
  `namespace` symbols; the session becomes `probe.b`; the next turn starts there.
- **R2 nested in-ns (a318b2d38).** `(do (in-ns 'probe.c))` then `(+ 1 2)` in one turn: settlement commits; the
  session refs an identity-only `probe.c` row.
- **R3 scratch namespace.** After R2, `program/changed-identities` from the turn's base has no
  `[:seon.ns/name probe.c]`.
- **R4 move and continue.** A request with `:seon.ns/name` moves the session before its turn; a submission and the
  following system turn start at the session.
- **R5 two halves.** After `in-ns`, the next prompt appends `(dir probe.b)` and keeps the earlier forms with their
  own prompts.
- **R6 namespace deleted.** `remove-ns!` of the session namespace sweeps the ref; the next turn starts at home;
  earlier forms still print `probe.b=>` (value, not ref).
- **R7 no flat star.** The graph of an agent with 50 turns, 30 messages and 3 schedules has one level-1 node per
  attachment, and the turns at level 2 are in ordinal order.
- **R8 bounded validation (P0).** With turns as components, a one-field turn write costs about a one-entity write
  (2.6–3.9 ms on today's default). Its time at 61 and 2,061 turns is within 20%. It still refuses each class the
  guide §4 lists: multiple owners, cycle, unowned identity-less row, missing child, and an owner whose required
  child was retracted.
- **R9 compaction.** One transaction retracts chosen forms and appends a system turn; plan, settings, messages and
  session namespace are unchanged; the retracted forms' source is readable `as-of` before it.
- **R10 settings.** Switching the model with `settings!` changes the next request; `config/apply!` changes an
  agent without that override; a missing base refuses by name.
- **R11 root oversight.** Creating a hosted agent adds it to root's summary; retiring it removes it.

## 14. Owner questions (few, priced)

**Q1. Turns under the session.**
- (a) *Recommended*: components all the way down (session ⊃ turns ⊃ forms), after prerequisites P0 (incremental
  validation, ~+50/−60 lines in `db.clj` and `schema.clj`, with four proof obligations) and P1 (bounded read
  evidence).
  - Validation then follows the change: target ≈ 3 ms per turn write, independent of history.
  - Pull and cascade tell the whole story.
- (b) Fallback: turns as forward peer refs, forms components of the turn. ~+10 lines; bounded without P0; no
  cascade from agent to turns. Taken only if P0's review fails.
- (c) Components without P0: rejected. Every write re-validates the owner's whole history (7.6 ms for 61 empty
  turns, 114 ms for 2,061, 1.8 s once 117 forms are owned), refusing at 250,000 nodes.

**Q2. Form namespace as a value.**
- (a) *Recommended*: a `:seon.cluster.eval/namespace` symbol; history survives namespace deletion. This reverses
  "each evaluation keeps its own ns ref".
- (b) Keep the ref. When a namespace is deleted, its forms lose where they ran, and the transcript falls back to
  guesses.

**Q3. The cluster's name, stored twice** (`:seon.cluster/name` and `:seon.config/cluster`).
- (a) Keep. 0 lines.
- (b) *Recommended later*: the config row is reached as `[:seon.cluster/name n]` → `:seon.cluster/config` and loses
  its own name. ~30 lines in `config.clj`, with a no-reset adoption proof.

**Q4. A refused settlement's namespace.**
- (a) *Recommended*: write the session to where SCI ended. ~3 lines.
- (b) Leave it. The next turn starts where the context no longer is.

**Q5. Schedules as a forward component.**
- (a) *Recommended for now*: keep `:seon.schedule.task/owner` (it is the arming attribute) and show schedules as a
  declared attachment. 0 lines.
- (b) `:seon.agent/schedules` component. Wake arming moves to the forward edge; ~+30 lines in `schedule.clj` and
  the armer.

## 15. Evidence (read-only, JVM mode, default, 2026-09-27)

Earlier probes (counts, timings, token sizes, store roster, settings) are unchanged from ca19ad74b and
summarized here with the new ones.

1. Root's incoming refs: `:seon.turn/agent` 61, `:seon.message/to` 27, `:seon.message/from` 22,
   `:seon.schedule.task/owner` 3, `:seon.schedule.fire/agent` 3, `:seon.maintenance.request/agent` 3,
   `:seon.error.occurrence/agent` 3, `:seon.runtime/agent` 1, `:seon.config/agent` 1, `:my.plan/agent` 1,
   `:seon.ns/steward` 1. Root's outgoing: branch, id, namespace, plan, runtime, settings.
2. Totals: agents 113; turns 59–61; evaluations 117; `:seon.runtime/listens` 0; `:my.plan/agent` 114;
   `:seon.config/agent` 114; renderer 3 and renderer-fn 3; occurrences 11 agent-only, 0 with a turn.
3. History cost: `seon.eval/of-agent` 1.55–1.82 s; `turn/latest-evaluations` 1.56–1.60 s. Raw pull of the same
   data: 3 ms. Evidence plans: 17,572,807 characters over 451 values.
4. Namespace reads: two-hop pull 30 µs; `previous-ending-namespace` 103–118 µs.
5. Tokens: stored history 34,593; messages read 15,413 over 25 turns; errors read 8,455 over 26; runtime read
   6,000 over 25; `dir` 32 to 11,284; settings summary 76; effective settings 879.
6. Validation (`seon.db/with`, 3–4 samples each): see §3's table. The validation-node-limit is 250,000.
   Synthetic turns were added only inside `with` values, never committed.
7. Store roster: `:cluster-default` 1 cluster row, 113 agents; `:checkpoint-default-f311e0fc…` 1/0;
   `:current-src` 0/0; `:db` neither. Root reports P0.3: every production value holds one cluster row.
8. P0.1 is confirmed by root (task a318b2d38, `resources/seon/tasks/seon.turn.edn`).

Not measured:
- a full prompt build through `seon.cluster.prompt/prompt`
- test-harness worlds
- any test run
