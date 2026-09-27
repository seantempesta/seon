---
type: research
status: data-model plan, sixth revision (sample-load diagnosis §19; tight-data render philosophy) — addresses Astra review e80bf7c0d (REVISE, 12 findings) and owner rulings of 2026-09-27 (declared reverse attachments, acknowledgement, relevance tiers, no home namespace, session render, distributed rendering, every modeling kind in the sample); for re-review, then the owner; no source changed
created: 2026-09-27
branch: refactor/agent-platform
source-pin: d7d185a7e (checkout); live default pid 22787, commit 6ab93d43, basis 536879662
lane: design-repl-session-opus
supersedes: earlier revisions of this file; the `:seon.agent/cluster` proposal of config-read-scope-design-2026-09-27.md (10192e1ee)
reads: agent-session-vocabulary-2026-09-27.md (b0c5d4a89), repl-session-prior-art-2026-09-27.md (08bd655aa), repl-session-entity-review-2026-09-27.md (e80bf7c0d)
sample: tmp/data-model-sample/sample.edn, tmp/data-model-sample/load.clj
---

# The agent's record, told as a story

## Summary

What exists: root's record on default is a flat star of about 124 edges, almost all of them reverse refs that
other entities point at it:

- 61 turns
- 27 messages to it and 22 messages from it
- 3 schedule tasks and 3 firings
- 3 maintenance requests
- 3 error occurrences
- back-references from its own runtime, settings, plan and namespace

The turn system was built as a set of proofs of concept, not from the agent's side.

The target has five parts:

1. The agent owns a few forward attachments.
2. Its session owns its ordered turns, and each turn owns its ordered evaluations. It is components all the way
   down.
3. Every other entity the agent needs points at the agent. The agent's schema **declares** which of those
   reverse edges are its attachments, each with a render.
4. One pull selector per entity kind is derived from the schema (forward refs plus declared attachments). It
   drives the walk, the context and the debug graph.
5. Context is the attachments' renders; detail is one requery away.

The principal Clojure approach:

- Each relationship is exactly one of the guide's five choices (guide §3).
- A statement about a living entity is a ref; an observed name is a value.
- Everything a turn changes is written in its one settlement transaction.
- Two of our own defects are fixed first, so the model does not bend around them: the writer re-validating whole
  owned trees, and forms storing unbounded read plans.

Skills and guides read end to end:

- `.agents/skills/data-oriented-clojure/SKILL.md`
- `.agents/skills/data-modeling/SKILL.md`
- `.agents/skills/datahike/SKILL.md`
- `docs/seon/architecture/data-modeling-guide.md`
- the three research notes above

§12 checks every modeling choice against them. §15 answers each review finding.

## 0. Owner rulings this revision applies

| Ruling (2026-09-27) | Consequence here |
|---|---|
| The fact lives on the entity that states it (message → agent, fn → namespace). The reverse is a read path, never a stored copy. Each entity schema **declares** its reverse attachments, each with a render. One pull selector per entity kind is derived from the schema. Undeclared reverse refs stay queryable but never appear. Owned things are forward components. | §2, §5 |
| Unread = to me without my acknowledgement. The agent writes one received fact on the message; there is no stored unread ref. | §5, §9 |
| Relevance tiers are a render choice. Renders return tight data (names, one-line meaning, contract shape), with docs folded in only when relevant; full `doc` and source on request. "Operations on this data" is a query over `:seon.fn.arity/input-refs`, not a stored relation (refined 2026-09-27, §5c). Render selection order is unchanged: inline override, then the viewing agent's namespace, then the schema pair, then the floor. | §5 |
| The agent's current task is a peer ref, `:seon.agent/task`. | §2, §4 |
| Definitions are namespace information; there is no "my functions". Authorship, if ever wanted, goes in transaction metadata. | §4 |
| **There is no home namespace, only a current one.** `:seon.agent/namespace` is deleted. Creation sets `:seon.agent.session/ns`. A namespace agent is creation with a chosen namespace plus a task naming it. | §2, §3 (reader list), §8 |
| The turns render is **one render pair on `:seon.agent/session`**: the transcript. | §5, §8 |
| The settings and task parts are approved as planned. | §7 |
| Names (settled in `docs/seon/architecture/vocabulary.md`, f49086d5c): session; turn (the `:seon.turn` entity); run = a derived budget of turns, never stored; compaction. | throughout |

## 1. Root's graph before and after: the acceptance picture

### Before (default, measured §18)

```
root ── 61 :seon.turn/agent · 27 :seon.message/to · 22 :seon.message/from · 3 :seon.schedule.task/owner
        · 3 :seon.schedule.fire/agent · 3 :seon.maintenance.request/agent · 3 :seon.error.occurrence/agent
        · back-refs :seon.runtime/agent, :seon.config/agent, :my.plan/agent · :seon.ns/steward
        (all at level 1, unordered; runtime → the same 61 turns again; evaluations only in reverse)
```

### After

```
root                                            level 1: forward attributes + declared attachments
├─ session  ns → my.agents.root                 component: one, ongoing
│    └─ turns 0 … 60   by :seon.turn/ordinal    level 2: components, in order (bounded window + count)
│         └─ evaluations 0 … n  by ordinal       level 3: components of their turn
├─ plan                                         component (present once it has a fact)
├─ settings ─ base → config "default"           component overlay + required ref
├─ task → (none on root)                        peer ref
├─ oversees → 0 agents (112 closed & exited)    peer refs, one summary line each, not expanded
├─ inbox        (unread 0 of 27)                declared attachment :seon.message/_to
├─ notes        (0)                             declared attachment :my.note/_agent
├─ issues       (0)                             declared attachment :seon.issue/_agent
├─ assignments  (0)                             declared attachment :seon.task/_agent
├─ schedules    (3)                             declared attachment :seon.schedule.task/_owner
├─ faults       (3)                             declared derived :seon.error/of-steward + occurrences
└─ listens      (0)                             component (wake subscriptions; plumbing, one line)
```

Every node at level 1 comes from the schema: a forward attribute of the agent, or a declared attachment. A
declared attachment is **one** node carrying its count; it is expanded on click. Undeclared reverse refs (for
example `:seon.message/from`, `:seon.schedule.fire/agent`) are queryable but not drawn.

## 2. Target schema

```clojure
;; agent — forward attachments and declared reverse attachments (:seon.render/units stays the declaration)
[:map {:seon.db/attributes true :seon.program/partition :seon.data
       :seon.render/units [:seon.agent/session :seon.agent/plan :seon.agent/settings :seon.agent/task
                           :seon.agent/oversees :seon.agent/listens
                           :seon.message/_to :my.note/_agent :seon.issue/_agent :seon.task/_agent
                           :seon.schedule.task/_owner :seon.error/of-steward]
       :seon.render/ai … :seon.render/html …}
 [:seon.agent/id …] [:seon.agent/branch …] [:seon.agent/archived-tx {:optional true} …]
 [:seon.agent/admission-closed-tx {:optional true} …]
 [:seon.agent/session :seon.agent/session]                  ; required: created with the agent
 [:seon.agent/plan {:optional true} …]                      ; created with its first fact
 [:seon.agent/settings :seon.agent/settings]                ; required: created with its base
 [:seon.agent/task {:optional true} :seon.agent/task]       ; peer ref: the current task
 [:seon.agent/oversees {:optional true} :seon.agent/oversees]
 [:seon.agent/listens {:optional true} :seon.agent/listens]]
;; deleted from the agent: :seon.agent/namespace, :seon.agent/runtime

;; session (identity-less component; no back-ref)
#:seon.agent.session{:ns    [:and {…} :seon.db/ref]                                   ; required
                     :turns [:set {:seon.db/component true :seon.db/component-schema :seon.turn/turn} :seon.db/ref]
                     :session [:map {:seon.db/attributes true :seon.program/partition :seon.data
                                     :seon.render/ai seon.render.transcript/render-session-ai
                                     :seon.render/html seon.render.transcript/render-session-html}
                               [:seon.agent.session/ns :seon.agent.session/ns]
                               [:seon.agent.session/turns {:optional true} :seon.agent.session/turns]]}

;; turn (today's :seon.turn entity)
#:seon.turn{:ordinal     [:int {:min 0}]                                                  ; required, written by the open writer
            :evaluations [:set {:seon.db/component true :seon.db/component-schema :seon.cluster.eval/receipt} :seon.db/ref]}
;; NOT :seon.turn/forms: that key already names the in-memory [:vector :seon.turn/form] of a system result
;; (seon.turn.edn:145, required at :55-58; produced turn.clj:2358-2362; consumed :2031, :2251, :5492).

;; evaluation (identity id/evaluation(turn, ordinal) unchanged)
#:seon.cluster.eval{:namespace [:symbol {:seon.db/index true}]}                            ; the observed namespace

;; messages, tasks, settings
#:seon.message{:received-tx [:and {…} :seon.db/ref]}           ; the recipient's acknowledgement (a tx ref)
#:seon.task{:namespace [:and {…} :seon.db/ref] :function [:and {…} :seon.db/ref]}
#:seon.config{:base [:and {…} :seon.db/ref]
              :agent-settings [:map {…} [:seon.config/base :seon.config/base] <derived per-agent dial entries>]}
```

The retired set:
- `:seon.agent/namespace`, `:seon.agent/runtime`
- `:seon.runtime/agent`, `:seon.runtime/turns`, `:seon.runtime/trigger` (listens move to `:seon.agent/listens`)
- `:seon.turn/agent`, `:seon.turn/starting-ns`
- `:seon.cluster.eval/run`, `:seon.cluster.eval/ns`
- the stored `:seon.sci.eval/ending-ns`
- `:seon.eval/renderer-fn`, `:my.plan/agent`, `:seon.config/agent`, `:seon.maintenance.request/agent`

`:seon.eval/entity`'s required `:seon.cluster.eval/run` (`seon.eval.edn:10`) retires with them. The run is
derived: `:seon.config.run/max-episode-runs` becomes `:seon.config.run/max-turns`, including
`config/default.edn` and every manifest consumer. `episode-runs` becomes `run-turns` and `episode-exhausted!`
becomes `run-exhausted!`.

## 3. Readers of `:seon.agent/namespace`, and what each becomes (no home)

| Reader (file:line) | Today | Becomes |
|---|---|---|
| `turn.clj:704-714` `plan-call`, `:774-781` `system-run-plan` | fallback start | the session ns (required; no fallback) |
| `turn.clj:2233`, `:5478`, `:5549` (system turn, appended generated forms) | home as current | the session ns |
| `turn.clj:4745` `freeze!` segmentation, `:5334` resume | home | the session ns |
| `turn.clj:2611` `form-owner` | assignment as ownership | `:seon.ns/steward` of the evaluation's namespace (P6) |
| `sci/eval.clj:431-449` `agent-namespace`; `:3347`; `:4536-4541` | home; the evaluation-namespace default | deleted; callers read the session ns |
| `render/walk.clj:603-612` `acquired-root-namespace-eid` | the namespace half | the session ns (via the derived selector) |
| `render.clj:646-657` `viewing-namespace` | where the viewer's render functions live | the viewer's session ns (render selection step 2) |
| `render.clj:1736-1746` `namespace-owner` | render-failure recipient | `:seon.ns/steward` (P6) |
| `render.clj:1826-1838` `repl-state` | the prompt namespace | the session ns |
| `agent.clj:18-33` `identity` (`:my.agent/namespace`, steward) | identity map | `:my.agent/namespace` from the session ns; steward is read from that namespace |
| `bootstrap.clj:22-42` (help tools), `:112-148` (situation `:seon.agent/namespace-ref`) | home's requires; situation | the session ns; the situation's `namespace-ref` becomes the session ns |
| `cluster/agent.clj:237` `creation-tx` | writes home | writes the session with its first ns |
| `cluster/agent.clj:255`, `:283` (`whoami`), `:334-341` (render identity), `:406` (situation text) | home | the session ns |
| `cluster/agent.clj:452-465` agents of a namespace (routing) | assignment | agents whose session is in the namespace, or its steward, by caller: named per caller in P6 |
| `cluster/agent.clj:697-724` submit path | home | the session ns, or the request's `:seon.ns/name`, which moves the session (§8) |
| `cluster.clj:3417-3428` creation result | home | the session ns |
| `test.clj:1542` `owned-symbols` | "tests in my namespace" | the tests of the task's namespace (`:seon.agent/task` → `:seon.task/namespace`) |
| `render/web.clj:1377-1414`, `:2824-2829`, `:3062-3069`, `:3631-3633` (debug viewer) | home | the session ns |
| `render/transcript.clj:74`, `:278-283` (form fallback), `:838`, `:1258-1270` | home | the evaluation's own `namespace` symbol; the session ns |
| `eval/drive.clj:22-28` | constructs a default | the creation request's first namespace |
| `problems.clj:294` | docstring only | unchanged text correction |

That is 25 test files naming `:seon.agent/namespace`. P2's rewrite manifest (§13) includes them.

## 4. Relationships: five choices, statement or token, requiredness, deletion

Kinds follow `docs/seon/architecture/data-modeling-guide.md:89-95`; statement versus token follows data-modeling
skill lines 40-70. Every "required" refuses an invalidating sweep only because the writer validates the surviving
row (datahike skill lines 60-82). No ref enforces target existence by itself (guide §3, `db/utils.cljc:116-155`).

| Relationship | Kind | Req. | Statement/token | Target deleted | Source deleted |
|---|---|---|---|---|---|
| agent → session | component | req. | statement | — | cascades session, turns, evaluations, evidence |
| session → current ns | peer ref, moved by settlement | **req.** | statement | refuses unless the same transaction moves the session (`remove-ns!` repairs or refuses) | — |
| session → turns | component, ordered by `:seon.turn/ordinal` | opt. | statement | — | cascades |
| turn → evaluations | component, ordered by ordinal | opt. | statement | — | cascades |
| evaluation → evidence; turn → attempts | component | opt. | statement | — | cascades |
| evaluation → namespace | **identity value** (symbol) | — | **token** | unchanged | — |
| evaluation → renderer | identity value (symbol) | opt. | token | unchanged | — |
| agent → plan / settings | component | opt. / **req.** | statement | — | cascades |
| settings → base config | peer ref | **req.** | statement | refuses | — |
| agent → task | peer ref | opt. | statement (the current work) | sweeps | — |
| task → namespace / function | peer ref | opt. | statement (what the task concerns) | sweeps; the task keeps its `:seon.task/ns` identity aspect (token) | — |
| agent → oversees | peer ref set | opt. | statement (responsibility) | sweeps | — |
| agent → listens | component set | opt. | statement | — | cascades |
| message → to | peer ref, listened | **req.** (`seon.message.edn:20-30`) | statement | refuses | — |
| message → from, caused-by | peer ref | opt. | statement | sweeps | — |
| message → received-tx | tx ref (a transition by the recipient) | opt. | statement | — | — |
| turn → trigger, handled | peer ref | opt. | statement (cause, claim) | sweeps | — |
| namespace → steward | peer ref | opt. | statement (namespace information) | sweeps | — |
| fn → namespace (`:seon.fn/ns`) | peer ref (program) | req. | statement | refuses (program rules) | — |
| note → agent / about | peer ref | req. / opt. (`my.note.edn:15-17`) | statement | refuses / sweeps | — |
| issue → agent; assignment → agent | peer ref | opt. | statement | sweeps | — |
| schedule task → owner | peer ref, arming | **req.** | statement | refuses | — |
| firing → agent | peer ref, listened | **req.** (`seon.schedule.fire.edn:19`) | statement: **the recipient of this firing**, an immutable routing observation recorded once by the firing writer (`schedule.clj:378-389`), not a mirror of the task's current owner | refuses | — |
| capture → turn | peer ref | **req.** (`seon.context.capture.edn:9-10`) | statement | refuses | — |
| cluster → config | peer ref | **req.** (`seon.cluster.edn:7`) | statement | refuses | — |
| occurrence → agent or turn (one, §5) | peer ref | opt. | statement | sweeps | — |
| evaluation → delivered faults | peer ref set | opt. | statement (what was shown) | sweeps | — |

Messages stay peers because of their **independent lifecycle**: they outlive both parties' turns and are
addressed by either side. Having two parties alone would not forbid ownership by one of them (review finding 5).
Definitions belong to their namespace (`:seon.fn/ns`); an agent reaches them only through a namespace it
renders.

## 5. Context: the derived selector, the attachments and their renders

### 5a. One derived pull selector per entity kind (review finding 6; owner ruling)

`seon.render.walk/entity-selector [projection kind]` is derived once per projection and cached by projection
fingerprint:

- every forward attribute of the kind's entity schema;
- every declared reverse attachment from its `:seon.render/units`;
- a component nests its target kind's selector;
- a peer ref selects only the target's identity attributes, so `oversees` stays shallow until requested (review
  finding 6);
- a many-valued attachment or component set is fetched ordered before any window is cut. Turns are ordered by
  `:seon.turn/ordinal` and evaluations by `:seon.cluster.eval/ordinal`; the window is the last *n* in that order,
  never target eid order (today's cut is by eid, `walk.clj:1029`, `:1064-1068`).

One value serves three consumers:

| Consumer | Today | Target |
|---|---|---|
| The walk / context acquisition | declared concerns + the home namespace (`walk.clj:97-125`, `:603-612`) | the derived selector; the namespace half from `:seon.agent.session/ns` |
| The debug graph | every ref forward and reverse, integer eid nodes (`walk.clj:979-997`; `seon.render.walk.edn:151-169`); web assumes numeric ids (`web.clj:3267-3295`, `:3325-3339`) | the derived selector. An attachment node is `{:seon.render.walk/attachment attr :seon.render.walk/count n :seon.render.walk/members [...] :seon.render.walk/elision {...}}`, with no synthetic entity stored. The graph schema and web accept it |
| The agent's opening render | declared units | the same selector's value, rendered per attachment |

Attachment behavior, stated once:

| Case | What the attachment shows |
|---|---|
| empty | the attachment with count 0; its render may print its empty form (the plan's "no plan") |
| a refused read | the refusal as the attachment's value (floor + diagnostic) |
| a cut | an elision naming the bound, the count, the path and the requery form |
| requery | a runnable form, for example `(seon.db/pull (seon.db/db) (seon.render.walk/entity-selector …) [:seon.agent/id "root"])`, or the attachment's own query |

Inverse concerns needed for other roots are declared on their schemas, so no navigation is lost when undeclared
reverse refs stop appearing:

- a namespace declares `:seon.fn/_ns` (its definitions) and `:seon.test/_ns`;
- an evaluation reaches its turn through the component reverse (single-valued, `pull_api.cljc:328-329`);
- function callers remain the observed-symbol edges (`walk.clj` `interim-value-edges`).

### 5b. Attachments, renders and tokens (estimated tokens, chars/4, on root unless marked)

| Attachment | Render (AI) | Requery | Tokens |
|---|---|---|---|
| identity (scalars) | id, branch, current ns, namespaces it stewards | the agent pull | ~40 est. |
| **session** | **the transcript** (§8): each turn by ordinal, each evaluation by ordinal, `ns=> source` then its saved `:seon.eval/shown` (or its error map) | a turn by ordinal; `as-of` before a compaction | root 34,593 stored today; 86% three repeated generated reads |
| namespace half (session ns) | its definitions as tight data (name, meaning, contract) | full `(dir ns)`, `(doc sym)`, source | est. about a third of the full `dir` (not measured); full is 32 (`my.agents.root`) … 11,284 (`seon.turn`) raw |
| task | its intent plus its namespace and function as tight data, with operations | full `dir`/`doc` | ~120 |
| plan | objective, current step, ready/blocked | `(seon.plan/plan {})` | ~130 |
| settings | dials declared `:seon.config/shown` + the override set | `(seon.ai/agent-settings (seon.db/db) "root")`, 82 dials | ~90 / ~880 |
| inbox | **unread**: `:seon.message/_to` without `:seon.message/received-tx`, oldest first | all messages to me; sent messages by `:seon.message/_from` | ~23 each (root: 27 messages, none acknowledged under the new fact) |
| notes, issues, assignments, schedules | counts + newest items | each entity | small |
| faults | unresolved occurrences routed to me or to namespaces I steward | `(seon.error/for-agent (seon.db/db) "root")` | ~325 per refresh today |
| oversees | one line per agent: id, branch, admission state, exit state | that agent's render on its branch | ~30 each |
| listens | the attribute list | the patterns | ~10 |

Render selection order is unchanged, as ruled: the value's inline override, then a render function in the
viewing agent's current namespace (`render.clj:646-657`, now the session ns), then the schema's declared pair,
then the floor.

**Per turn.** The opening after a compaction is the sum of the attachment renders plus the namespace half. Every
later turn appends only what changed, under the existing refresh (turn PRD §14): an `in-ns` appends
the new namespace's tight rendering, a new message re-renders the inbox, and a plan change re-renders the plan. The measured
savings on root:
- deleting the runtime-trigger read: −240 tokens per turn;
- showing only unread messages instead of every message: −616 tokens per refresh.

### 5c. Distributed rendering: a recursive render over the pulled tree (owner, 2026-09-27)

The context is a recursive render over the tree the schema-derived selector pulls, seen from the viewing agent.
There is no central context builder.

1. **Pull the tree.** The derived selector (§5a) pulls the root entity with its forward refs and declared reverse
   attachments. Components nest their own derived selectors, and peer refs stop at identity. The shape comes from
   the schemas alone.
2. **Render recursively.** Each node's render renders its own node and delegates its children to their renders:
   - the agent's render hands the session to the session render, the task to the task render, and the inbox to
     the inbox attachment render;
   - a child render may in turn delegate.
   - Depth and the floor's one token budget bound the recursion. The composer dedupes, back-points and orders.
3. **Return tight data the agent can work with** (owner, 2026-09-27: "like command-line tools: how often do you
   look up the documentation?"). A render returns data, not `(doc …)` forms, by default. A one-line meaning is
   folded into the data only when it helps. Things must describe themselves well enough to say what they mean and
   how they can be manipulated. So:
   - A task's refs render as tight data: each namespace and function as its name, a one-line meaning (the
     docstring's first sentence) and, for a function, its contract shape, for example
     `{:seon.fn/sym seon.repl/response :seon.fn/meaning "The reply map for one evaluation." :seon.fn/contract [:=> [:cat :seon.repl/emission] :string]}`.
   - Full `doc` and source stay one request away: `(doc f)`, or resolving the symbol.
   - **Operations on this data.** A render may include the functions that operate on the value, found by a
     **query**, not a stored relation: public toolkit functions whose arity declares the value's schema key or
     identity key in `:seon.fn.arity/input-refs`, for example `my.message/received!` on a message.
     - That attribute is a component of the function (via `:seon.fn/arities`) holding a set of qualified schema
       keywords with `:seon.db/index true` (`resources/seon/schemas/seon.fn.arity.edn:3`), so the query is an AVET
       seek.
     - Measured on default: 3,415 input-ref values; one query takes 0.4-5 ms. Restricting it to the cluster
       toolkit namespaces (`:seon.cluster/toolkit`, 13) gives `:seon.agent/id` → `my.agent/branch`,
       `my.issue/add!`, `my.issue/tests!`, `my.program/propose-merge!`, and `:my.plan/request` → the ten `my.plan`
       readers.
     - Gap found: **no toolkit function declares a `:seon.message/*` key** (`:seon.message/message` and
       `:seon.message/id` both return `[]`), and none declares `:seon.task/handle`, `:seon.ns/name` or
       `:seon.fn/sym`. An operation is discoverable only if its contract names the entity's keys. The
       acknowledgement function must take `[:cat :seon.message/id]`.
   - A render may still **read the database** (a declared input, supplied by schema name) or return a form that
     reaches past the tree when it needs more context. The norm is to render the data it was handed.
4. **Perspective.** The viewing agent is a declared input of every render:
   - A message renders to its recipient with the acknowledgement form `(my.message/received! "id")`, and to its
     sender as delivered or received.
   - Root's `oversees` renders one line per agent.
5. **The composer** keeps the laws the 2026-09-25 design and AGENTS set:
   - each entity once per context, with later mentions a data back-pointer
     `#:seon.render{:see [:seon.ns/name seon.repl]}`, not a comment;
   - definitions before uses;
   - turns and evaluations in ordinal order;
   - renders evaluated once and cached by the value's content digest (AGENTS "Return data, not comments";
     `task-context-install-design-2026-09-25.md` §4.3-§4.5).

   What changes from that design:
   - Traversal is the one pull of the derived selector, not a level-by-level walk of every reverse ref to depth N
     (its §2, §4.2).
   - Relevance lives in the render functions: which refs are shown as tight data, and which operations are
     offered.
   - The recursion follows the pulled tree plus the forms renders return.
6. **How context is realized.** Under additive context (turn PRD §14) the opening is a system turn whose forms
   are the ones the agent's recursive render returned. The prompt is then the session render, the transcript, over
   all turns. A compaction regenerates the opening from the agent's render; later changes arrive as appended
   refresh forms.

**Render location is the tuning lever.** It is the AGENTS selection order, used on purpose:

| Where the render lives | Scope | Use it for |
|---|---|---|
| inline on the value (`:seon.render/ai`, `:seon.render/html`) | this one value | a one-off view a producer decides |
| a function in the **viewing agent's current namespace** whose contract fits the value | every agent working in that namespace | a namespace's own view of a kind, e.g. `my.agents.reviewer/render-task-ai` showing full source, or `seon.repl`'s agents seeing function docs with examples |
| the schema's declared pair | everyone: the **global default** | the ordinary view |
| the floor | values with no render | general printing |

Because the session's current namespace selects step 2, **an agent that `in-ns`es into a namespace also picks up
that namespace's renders**. Tuning the view for a kind of work means adding a render function in its namespace,
never editing a central builder.

#### Renders for the sample

Estimated tokens (chars/4); seon.repl measured on default.

| Entity / kind | Render (where declared) | Returns | Tokens |
|---|---|---|---|
| agent `demo` | `:seon.agent/agent` pair (schema default) | an identity map (id, branch, current ns); each attachment delegated to its own render | ~40 + parts |
| task `demo0task001` | `:seon.task/task` pair (schema default) | `{:seon.task/id … :seon.task/intent … :seon.task/namespace {:seon.ns/name seon.repl :seon.ns/meaning "…" :seon.ns/functions 30} :seon.task/function {:seon.fn/sym seon.repl/response :seon.fn/meaning "…" :seon.fn/contract […]}}` | ~120 |
| namespace `seon.repl` (on request, or as the session namespace) | `:seon.ns/ns` pair (default) | names, one-line meanings and contract shapes of its 30 definitions (declared `:seon.fn/_ns`); full `dir` on request | ~900 tight, est. (full `dir` 2,726 measured) |
| function `seon.repl/response` (on request) | `:seon.fn` pair (default) | name, meaning, contract, operations; `(doc f)` and source on request | ~40 (full doc ~65) |
| inbox | the `:seon.message/_to` attachment pair on the agent schema | unread messages as data, each with its operations (`my.message/received!` once its contract names `:seon.message/id`) | ~30 (1 unread) |
| settings | `:seon.agent/settings` attribute pair (`seon.agent/render-settings-ai`) | the shown dials + override set | ~90 |
| session transcript | `:seon.agent.session/session` pair (new, default) | turns by ordinal, evaluations by ordinal: `ns=> source` + saved shown text | ~150 for demo's 5 evaluations |
| root oversight | `:seon.agent/oversees` attribute pair (new, default) | one summary map per overseen agent | ~30 per agent |

Every row above is a **global default**: the schema's declared pair. Where a namespace override changes the view:

| Override (hypothetical) | Who sees it |
|---|---|
| `demo.work/render-task-ai` returning each task function's full source instead of its tight data | demo after its `in-ns 'demo.work` |
| `seon.repl/render-fn-ai` adding a function's callers to its tight data | any agent in `seon.repl` |
| `my.agents.root/render-agent-ai` summarizing an overseen agent with its recent faults | root |

An inline `:seon.render/ai` on one message (a producer's special notice) wins over all of them.

- **demo's whole opening context:** about 40 + 120 + 900 (session namespace, tight, est.) + 30 + 90 ≈ 1,200 tokens,
  then the transcript. The full `dir`/`doc` are requests, not defaults.
- **root's opening:** its identity, plan, settings, faults, schedules and one line per overseen agent.

**What in today's code fights this** (file:line; the graph code is being changed by another lane, so these are at
the checkout):
- `render/walk.clj:97-106` `declared-concerns` and `:948` `::attachments` both read `:seon.render/units`, a second
  and third reader of one declaration. The derived selector should be the only reader.
- `render/web.clj:649` `declared-entity-units` and `:1634-1641` are the debug page's own unit reader and reverse
  pulls, another path.
- `render/walk.clj:913-921` `interim-value-edges` is a hand list of value edges, retiring as the refs lane converts
  them.
- `turn.clj:1979-2030` `declared-sources` → `walk/neighborhood` is the central opening builder.
- `render/transcript.clj:112-184` `candidate-entity-ids`, with pin and limit rules, is a central history builder,
  and `:258-301` `receipt-entry` hand-maps evaluation fields (audit F06).
- `agent.clj:147-154` is the settings summary hand list.
- **A ruling conflict.** The 2026-09-25 design planned to delete `:seon.render/units` "with no replacement key"
  (its §6, owner O4). Today's ruling makes the entity schema **declare** its reverse attachments, which is exactly
  what `:seon.render/units` holds. So the plan keeps `:seon.render/units` as that declaration and names it as its
  meaning. Only the extra readers are deleted, not the key. Owner Q7 confirms.

## 6. Validation cost: two defects made prerequisites (review finding 1)

Measured with `seon.db/with`, read-only on default; Astra's review reproduced the same numbers. The validation
limit is 250,000.

| Write | Owning value today | ms |
|---|---|---|
| empty transaction | — | 0.8–1.0 |
| message content | the message | 2.6–3.9 |
| one turn field | root's tree: agent, plan, settings, runtime, 61 turns, attempts (369 maps) | 6.0–8.1 |
| same, 2,061 turns | | 107–126 |
| one field on the evaluation holding the 1.5 MB plan | that evaluation + evidence | 137–193 |
| all 121 of root's evaluations touched once | all evaluation trees | 1,796–1,932 |
| Astra counterfactual, turns as non-components: turn write at 61 / 2,061 turns | | 1.7–2.1 / 1.5–1.9 |
| same counterfactual: session write (row holds all turn ids) | | 1.6–1.8 / 2.6–3.0 |

The last two rows show what remains when descendants are not expanded. A session row still carries all its
turn ids, and Malli's collection validator walks them (`reference-code/malli/src/malli/core.cljc:1532-1539`).
So a session write is **O(the session's turn refs)** at id granularity. That is about 3 ms at 2,061 turns. It is
not constant, and this plan no longer claims it is.

**Defect 1, whole-tree re-validation.** `write-owned-values-error` (`src/seon/db.clj:3540-3770`) expands each
root's whole component tree on any write (reduce at `:3674-3722`).

**Defect 2, unbounded read plans.** Forms store read plans with one pattern per matched entity
(`db.clj:678`, `:760-853`): up to 1.5 MB each, 17.6 M characters on default. No render shows them.

### P0: incremental owned-value validation (prerequisite; its own design note and review)

**Rule.** C is every entity the transaction changed: the `:e` of attempted and effective datoms, including
cascades and sweeps, plus the `:v` of component datoms.

1. Validate each nonempty row in C, after the transaction, against its identity schemas or its owning relation's
   component schema. Children are ids, not expanded maps; `:seon.db/ref` admits an id (`seon.db.edn:40`).
2. Check owner invariants for C only, before and after: at most one owner, no cycle along the owner chain, no
   unowned identity-less row, no component edge to an empty row. A child whose row became empty re-validates its
   before-owner's row.
3. Charge `::validation-node-limit` for work done: C, owner-chain hops and direct child edges.

**Location.**
- `db.clj:3674-3722` replaces the tree expansion.
- Owner discovery stays: `owners-of :3621-3651`, `owning-ancestors :3652-3662`.
- The arity gate's changed-roots walk (`:3724-3752`) is unchanged.

**Proof obligations.** Each gets a named regression.

| # | Obligation | How it is shown |
|---|---|---|
| a | No entity schema reads a child's content. | Assert at projection compile, beside `assert-entity-partition!` (`schema.clj:1517`), that every component attribute's form is `:seon.db/ref` or a collection of it. Also show that no `:fn`, `:and` or custom validator in a parent reads children. |
| b | C is complete. | Cascades (`db/transaction.cljc:832-836`), sweeps (`:1003-1015`) and upsert merges all emit datoms. One regression per path. |
| c | Schema adoption re-validates every entity whose schema changed. | Through `attribute-change-tx` (`schema-attribute-change-tx turn.clj:1097`). |
| d | Before and after owner discovery is kept for unlinks and moves. | Guide §4. |
| e | Cross-field invariants are only row-local or owner-local. | Name any that are not; each is either re-validated when its inputs change or refused as a design error. |

**Estimate.** Not proved: about +50/−60 lines plus the (a) assertion. It gets its own reviewed note before code
(review finding 1).

**Targets (R8), each measured at 61 and 2,061 turns:**

| Write | Target |
|---|---|
| (i) turn append | O(the session's turn refs) ≈ 3 ms at 2,061 |
| (ii) session namespace change | O(the session's turn refs) |
| (iii) plan or settings edit | O(that component's row) ≈ a one-entity write |
| (iv) one turn's settlement | O(that turn's actual admitted evaluations and their evidence) |

### P1: bounded read evidence (prerequisite)

- A stored plan keeps at most K entity patterns per source (`db.clj:760-853`). Past K it widens to
  attribute-level patterns.
- Widening is conservative: the currency check (`read-evidence-current?`) may report "changed" more often, never
  less.
- K is a declared dial.
- History and refresh stop pulling plans they do not use (`eval.clj:18-20`, `turn.clj:2054-2056`).
- Targets: `of-agent` on root from 1.55–1.82 s to the selector-only 0.41 ms order; one evaluation's validation
  from 137 ms to about a one-entity write.

### Options (Q1)

| Option | Guarantee | Cost | Sacrifice |
|---|---|---|---|
| **A (recommended). Components all the way down (agent ⊃ session ⊃ turns ⊃ evaluations), after P0 and P1** | pull and cascade tell the story; each write is bounded by (i)–(iv) above | P0 (own review) + P1 | a correctness-critical writer change lands first. Any selector-less pull of an agent expands its history, so renders use the derived selector, and the pull work charge bounds a wildcard at the REPL |
| B (fallback). Turns as forward peer refs from the session; evaluations components of turns | turn writes bounded without P0; session writes O(turn refs) as in A | ~+10 over A, plus a writer check for **single-session membership and unique ordinal**, because a peer ref no longer prevents one turn in two sessions (review finding 5) | no cascade from agent to turns; one peer edge in the story |
| C. Components without P0 | — | — | every write re-validates the owner's whole history; rejected |

## 7. Settings: stored overlay versus sparse request (review finding 4)

**Today:**
- `config/default.edn`, a sparse overlay and the environment compile into one config row per cluster
  (`config.clj:1-14, 543, 736`), applied at boot (`cluster/boot.clj:718, 762`; `cluster.clj:3362`).
- `bin/seon config apply` changes a cluster on the fly (`config.clj:810-860`).
- `seon.agent/settings!` changes one agent's per-agent dials, including model and provider. The next turn uses them.

**Target (approved):**
- **Two schemas, derived once by `derive-config-forms` (`src/seon/schema/edn.clj:63-93`):**
  - The sparse request `:seon.config/agent-overlay` keeps only optional dials, as today. `settings!` and its
    transaction function keep accepting it (`agent.clj:87-113`).
  - The stored entity `:seon.config/agent-settings` is the same dial entries plus a **required**
    `:seon.config/base`. The settings component's `:seon.db/component-schema` points at it.
  - `settings!` resolves the stored overlay through the agent, so a sparse request never needs the base.
- **Creation writes the base** from the creation request's `:seon.cluster/name`. It is the overlay's first fact,
  so no empty settings component exists.
- **One reader.** `seon.ai/agent-settings [db agent-id]` reads the base's `:seon.config/cluster` and calls
  `config/effective db cluster-name` unchanged (`config.clj:862-911`). That keeps required-dial defaults, the
  typed missing-row error and false/zero precedence. The AI namespace does not copy `effective`'s semantics.
- **The config design's classification is kept** (10192e1ee §1): A-sites through `agent-settings` or held
  settings, B aggregates unchanged, C overlay steps inside the reader, D reference selection through the base ref.
  Writer-side decisions stay in the writers.
- **Render:** dials declared `:seon.config/shown` replace the hand list at `agent.clj:147-154`;
  `:seon.config.agent/show-all-settings` is retired.
- **Q3** (the config row's own name) stays later work. Removing the config identity would change `effective`'s
  lookup, not merely erase a duplicate string.

## 8. The session: lifecycle, namespace transitions, the transcript, compaction

### 8a. Empty-component lifecycle (review finding 3)

| Component | Created | First fact | Losing its last fact |
|---|---|---|---|
| session | with the agent | `:seon.agent.session/ns` (the chosen first namespace; for a namespace agent, its task's namespace) | impossible while the ns is required: `remove-ns!` of the session's namespace must move the session in the same transaction or refuse |
| settings | with the agent | `:seon.config/base` | impossible: base is required |
| plan | **with its first real fact** (no empty plan at creation) | objective or a step | the writer that removes the last fact also retracts the component edge in the same transaction; the declared plan render prints "no plan" for an absent attachment |
| listens | with the first listen | the pattern | the component set simply loses its member |

- No identity or existence stamp keeps an empty component alive.
- **Adoption of existing empty plans:** retiring `:my.plan/agent` leaves 114 plan rows empty. The retirement
  transaction (P2b) therefore also retracts every plan component whose only fact was the back-ref, and its edge.
  This is part of the retirement's own `attribute-change-tx`, not a data migration.

### 8b. Namespace transitions (review finding 8)

The in-memory `:seon.sci.eval/ending-ns` stays on the evaluation value until settlement. Only its stored copy is
retired.

| Event | Session ns after |
|---|---|
| settlement commits | the last settled evaluation's SCI `*ns*` (identity upsert + the required ref, one transaction) |
| no evaluation ran (phase failure before evaluation) | unchanged |
| a request carrying `:seon.ns/name` | written by the turn's open transaction; the turn starts there |
| an explicit move followed by a refused settlement | the refusal settlement records the last evaluated form's ending ns if it commits; if no form evaluated, the moved ns stands |
| a settlement transaction that aborts and whose refusal recording also fails | unchanged. The boundary is `settle-batch!` → `settle-batch-refusal!` (`turn.clj:3857`, `:4074-4152`) → `settlement-lost!` (`:3834`) |
| `remove-ns!` of a namespace a session is in | the same transaction moves each such session to a namespace the caller names, or the write refuses naming the sessions |

SCI binds `*ns*` per evaluation from this fact (`sci/eval.clj:4536-4541`, `:4755`), so an unrecorded move
cannot leave the context "somewhere else". The next turn starts at the recorded session ns.

**Identity-only namespace rows.** Settlement upserts `{:seon.ns/name x}` for the session ns. Such a row is
admitted by `seon.ns/ns` (`seon.ns.edn:16-19`) and absent from `program/changed-identities` (`program.cljc:631-655`).
That proves only that a scratch `in-ns` is not a merge delta; it is not universal program compatibility.
`digest-map`'s identity path refuses an undigested identity (`program.cljc:563-608`; Astra probe). Its only
production caller is scoped to changed identities (`cluster/source.clj:880`).

Open program edge (probe P0.2, before P2b): a `defn` settled in an identity-only namespace, and `prepare-merge!`'s
selection adding `[:seon.ns/name ns]` for selected functions (`cluster/source.clj` `delta`). The settlement must
either declare the namespace through the program writer or refuse by name (`:seon.fn/namespace-unresolvable`).
No definition digest is manufactured for an observed namespace.

**Adoption (P2b).** Existing evaluations hold namespace refs and existing agents hold home refs. Converting them
would be a data migration, which the owner's disposable-data rule replaces with a reset. So P2b's retirements
land behind **RESET NEEDED**, batched with P3/P5/P6 (§13). The P2a slice fixes a318b2d38 now without any schema
change.

### 8c. The session render: the transcript, and what `:shown` is

`render-session-ai` returns the transcript:
- turns in `:seon.turn/ordinal` order;
- within each turn, evaluations in ordinal order;
- for each evaluation, the prompt `ns=>` (from its `namespace` symbol), its comment and source, then its saved
  `:seon.eval/shown` text or, for a failure, its shown error map.

Compaction changes which evaluations the session holds, and the same render produces the smaller transcript.
`render-session-html` is the page's transcript. The turn keeps its own pair (renamed from `render-run-ai/html`)
for a single turn's detail.

**What `:shown` is.** `:seon.eval/shown` is the exact text the floor printer produced **once, at evaluation
time**, under the render profile's budget (turn PRD §15: "the profile is applied once at evaluation time"):
- It is stored because history must show what the agent saw; re-printing a changed object or profile could not
  reproduce those bytes.
- The live value exists only in the agent's SCI context, bound to its handle `result/eN`
  (`sci.eval/bind-result!`, `turn.clj:5178`), and is lost at restart.

Other stored copies of the result text, with dispositions:

| Copy | Disposition |
|---|---|
| `:seon.context.capture/prompt` | holds the whole prompt, which contains the session's shown texts: a duplicate. Calibration needs `:seon.ai.tokens/characters` (stored beside it) and the contribution hashes, not the text. **D** proposed in P7, after its one consumer (`context/capture-ai`, the debug view) reads the transcript instead. Not written on default today (no provider) |
| `:seon.cluster.eval/error` (failure text) beside the shown error map | the same failure in two forms. Its consumers use it as the positive terminal fact (`turn.clj` terminal checks, `problems` errored receipts). **P7:** keep one: a terminal marker without text, or the shown map as the only text |
| `:seon.turn/reply` / `:reply-blob` | the provider's raw reply. The evaluations' sources and comments are segments of it: a duplicate unless it holds text no evaluation keeps (prose outside forms). **P7 investigates** with its consumers |

### 8d. Compaction (R3; review finding 9)

- **Entrance.** `seon.turn/compact!` (today `turn.clj:2393-2430`, which retracts every evaluation) becomes a
  request `{:seon.agent/id a :seon.turn.compaction/retract #{evaluation-id …} :seon.turn.compaction/sources
  [...]}`.
- **Writer checks.** In the writer (`db.fn/call`): the agent has no open turn; every named evaluation belongs to
  a closed turn of this agent's session.
- **Replacement forms.** They are evaluated as an ordinary system turn, not a second evaluator. That turn's
  single settlement transaction retracts the selected evaluations (cascading their evidence) and appends itself
  at the next ordinal.
- **What stays.** Turns stay, with their control and calibration facts. Attachments (plan, settings, messages,
  task, session ns) are not touched.
- **Cost.** It is measured as its own clock row: retract N evaluations + one settlement.
- **Retention.** Remove `:seon.db/no-history?` from `:seon.cluster.eval/source`, `:comment`, `:output`,
  `:diagnostics` (`seon.cluster.eval.edn:7, 14, 24, 32`) and `:seon.turn/reply` (`seon.turn.edn:135`).
  - After that, a retracted attribute's old assertion and its retraction enter temporal EAVT/AEVT, and AVET where
    indexed (`db/transaction.cljc:471-485`). That is several index entries per attribute, not "one datom per form".
  - `:seon.eval/shown` already keeps history (`seon.eval.edn:47-50`); shipped keep-history is true
    (`config/default.edn:4`).
  - The flag change cannot restore text already missing from the head's temporal history.
- **Test (R9):** `as-of` on the **post-compaction** database recovers each retracted evaluation's source and shown
  text.

## 9. Messages and acknowledgement (review finding 7; owner ruling)

- A message is one entity: `to` (required, listened), `from`, `caused-by`, content. Sending is creating it.
- **Received.** The recipient writes `:seon.message/received-tx`, a ref to its own transaction (`"datomic.tx"`),
  through `my.message/received!`.
- **Unread** = to me without that fact. It is derived by the inbox render, and no unread ref is stored.
- **Answered.** Wake answering is a different predicate and stays where it is: the accepted-reply basis
  (`latest-answering-turn-t`, `unanswered-wakes`, `turn.clj:3111-3147`).
- **Handled** (`:seon.turn/handled`, written by `close-call` `turn.clj:480-501`, read by the inbox query
  `cluster/message.clj:548-554`) loses its inbox consumer.
  - Its remaining consumers are named in P7: the handled-message display and the close claim itself.
  - If none needs it once the ack exists, it is deleted in P7 (owner Q6).
- **Requery** reaches older incoming messages (`:seon.message/_to`) and **sent** messages (`:seon.message/_from`)
  after compaction has removed the form that sent them.
- **R7** covers: an accepted reply with no claim and no ack; a system turn; an acknowledged message leaving the
  inbox.

## 10. Root's oversight lifecycle (review finding 10)

`:seon.agent/oversees` is responsibility, so it outlives admission closure. The edge is kept until the overseen
agent's retirement has **exited**, not when admission closes.

- **Writers.**
  - A hosted agent: `host!` adds the edge on the cluster, the branch that holds root's record.
  - Its retirement: `host/retire!` closes admission, joins its work (`cluster/host.clj:155-179`), and on a
    successful join/release retracts the edge. A failed join keeps the edge.
  - An issue worker: the issue writer (`issue.clj:1338`) adds the edge on the parent's branch.
- **Summary line.** It reads only facts on root's branch: id, `:seon.agent/branch` (custody), admission state,
  and whether the edge survives past closure (closure without exit = "closing").
  - It never infers "idle" from the parent row. Open or idle lives on the agent's own branch and is one requery
    away.
- **R11** covers a failed retirement join: the agent stays in root's summary as closing.

## 11. Per-fact audit (consumer column)

K keep · M move · D delete · N new.

| Fact | Verdict | Writer | Consumer |
|---|---|---|---|
| `:seon.agent/id`, `branch`, `archived-tx`, `admission-closed-tx` | K | `cluster/agent.clj:205-241`; `agent.clj:58`; host retirement | lookups, custody, presentation, admission refusal |
| `:seon.agent/namespace` | **D** | `creation-tx :237` | §3: every reader converts to the session ns, the task namespace or the steward |
| `:seon.agent/session`, `:seon.agent.session/ns`, `/turns` | N / M (from runtime) | creation; settlement; the open of a moving request | every turn start; the transcript; the namespace half |
| `:seon.agent/runtime`, `:seon.runtime/agent`, `/turns`, `/trigger` | **D** | `creation-tx :237`; `open-call turn.clj:465-468`; `:1622-1623` | replaced by the session; the trigger copy's only reader was the runtime render (~240 tokens a turn) |
| `:seon.runtime/listens` | M → `:seon.agent/listens` | `issue.clj:1468` | `cluster/wake.clj:427-429`; `issue.clj:1456` |
| `:seon.agent/plan` + `:my.plan/agent` | K / **D** | `creation-tx`; `my.plan` writers | plan render / back-ref only (§8a) |
| `:seon.agent/settings` + `:seon.config/agent` | K / **D** | `creation-tx`; `agent.clj:101` | `agent-settings` / back-ref only |
| `:seon.config/base` | N | `creation-tx` | `agent-settings` |
| `:seon.agent/task`, `:seon.task/namespace`, `/function` | N | the assignment writer; task admission | task render (dir / doc) |
| `:seon.agent/oversees` | N | §10 | oversight render |
| `:seon.turn/id`, `ordinal`, opened/closed-tx | K / N / K | `open-call` | ordering, open-turn and wake answering |
| `:seon.turn/agent`, `starting-ns` | **D** | `open-call :462-463` and others | 47 mentions / `fold-namespace`, `stored-record-content` |
| `:seon.turn/trigger`, reply, reply-size, attempts, disposition, result | K | open, plan, settlement | cause; plan-frozen fence (`plan-call :724`); calibration; continuation |
| `:seon.turn/handled` | K → Q6 | `close-call :480-501` | inbox (moves to the ack), close claim |
| `:seon.turn.work/situation` | K, **named follow-up** | `open-call :462` | `plan-call :727` refusal, `latest-evaluations :2046`. The turn PRD §2 ruled it removed. A follow-up task for root to file: "derive `:call`/`:generate` from authorship and attempts; retire the stamp" |
| `:seon.turn/evaluations` | N | plan freeze; settlement | the transcript, compaction |
| `:seon.cluster.eval/run` | **D** | `receipt-row :954` | 72 source mentions; the component reverse replaces it |
| `:seon.cluster.eval/ns` → `/namespace` | **D** / N | settlement | prompts, `form-owner`, `fn/analyze-form` (`settlement-form :1003`) |
| stored `:seon.sci.eval/ending-ns` | **D** | `evaluation-facts :154-156` | the next prompt; the session |
| `:seon.eval/renderer` / `renderer-fn` | K / **D** | `evaluation-facts :130-132` | label; readers already fall back to the symbol |
| shown, diagnostics, output, cost measures, read-basis, faults | K | settlement | the transcript; bounds findings; refresh; error delivery |
| `:seon.cluster.eval/error` | K → P7 (§8c) | settlement | terminal fact; problems |
| read-evidence | K, bounded (P1) | settlement | refresh currency |
| `:seon.message/received-tx` | N | `my.message/received!` | inbox unread |
| error occurrence `agent` / `turn` | K, one per occurrence (P7) | `error.clj:1492-1493` | `problems.clj:120`, `error.clj:1769-1772`. `error.clj:2058-2072` queries the direct agent and gains turn → session → agent in P7 |
| `:seon.maintenance.request/agent` | **D** | `schedule.clj:266` | none; the firing names its recipient |
| `:seon.schedule.fire/agent` | K | `schedule.clj:389` | wake router; the firing's recipient, an immutable observation (§4) |
| `:seon.context.capture/*` | K (prompt text → P7) | `context.clj:359` | calibration (`seon.ai.tokens/characters`); contributions read by `transcript.clj:701-703`, produced by `context.clj:340-355`, tested by `context_capture_test.clj:7` |
| `seon.context/selection`, `append-tx`, `compact-tx` | **D** (functions only) | `context.clj:76-256` | none outside the file. The contribution schema and its `evaluations` member stay: captures produce and read them (review finding 9) |

## 12. Modeling choices against the skills; documentation corrections

| Choice | Authority | Holds? |
|---|---|---|
| components owned forward; cascade; pull expansion | data-modeling skill lines 30, 97-99; datahike skill lines 380-391; guide §4 | yes, with P0 |
| no back-ref identities; parent from the forward edge | datahike skill lines 383-384, 392-400; guide §4 | yes. Supersedes turn PRD §18c's back-ref prescription, which is updated under the owner's newer authority when the refs retire |
| order by ordinal | data-modeling skill lines 91-92; datahike skill lines 329-330 | yes |
| identity-less components validated by component schema | datahike skill lines 88-90; `schema.clj:1517-1537` | yes |
| evaluation namespace and renderer as values | data-modeling skill lines 40-70 | yes |
| session ns as a required ref moved by settlement, repaired or refused on retraction | guide §3 rows 3 and 5; §2 ("repair … in the same transaction or refuse") | yes |
| declared reverse attachments, one derived selector | data-modeling skill lines 167-178; guide §7 ("derive the pulled shape from the entity schema and selector") | yes |
| compacted history retained | datahike skill lines 106-121 | after the no-history change; old head history is not restored |

Corrections applied **in the slice that installs the behavior** (review finding 12), never before:

- **Data-modeling skill line 179** (and data-oriented lines 66-70). "History, unanswered wakes, and routed faults
  remain queries" becomes:

  > The session owns membership of turns and evaluations (forward components). Traversal, ordering and temporal
  > reconstruction remain derived reads. Unanswered wakes, unread messages and routed faults are derived and
  > rendered as declared attachments.

- **Stale file:line citations**, all confirmed by the review: `db.clj` `q` 2450, `pull` 2673, `transact!` 5094,
  `call-with-custody` 390, `read-evidence` 928, `write-entity-error` 3502, `write-owned-values-error` 3540,
  `removed-definition-error` 4152, `deletion-error` 4219; `turn.clj` `system-plan` 2099, `system-turn` 2222,
  `latest-answering-turn-t` 3111; `instrument.clj` 767 and 796; `sci/eval.clj` 3246, 3320, 3514.
- **Guide §7's "pull defaults to 1,000" is false.** `pull_api.cljc:16` sets the default to nil. The writer
  docstring's "pull's 1,000 cap" is false for the same reason, and guide §4's source ranges are stale.
- **"Namespace rows never merge"** narrows to §8b's wording.

## 13. Phased plan

Every slice is one loadable commit that converts its callers and tests. Root runs the named tests centrally.

| # | Slice | Owner files | Tests | Adoption |
|---|---|---|---|---|
| P2a | **Fix a318b2d38 now.** Settlement upserts the namespace identity of every evaluated form's `ns` and of the final namespace (`namespace-rows`, `turn.clj:619`). No schema change | `turn.clj` | R2 | none |
| P0 | Incremental owned-value validation (own note + review) | `db.clj :3540-3770`, `schema.clj :1517` | the owned-value members listed in the review; R8 (i)–(iv) | none |
| P1 | Bounded read evidence | `db.clj :760-853, :928`, `eval.clj`, then `turn.clj :2054` after P2a releases it | read-evidence currency, refresh; clock rows | none |
| P2b | Session ns and evaluation namespace symbol; creation writes the session; no home (§3); the move request; delete `starting-ns`, stored `ending-ns`, `previous-ending-namespace`, `fold-namespace`, the plan-time guess; remove no-history from form text; empty-plan retirement (§8a) | `seon.runtime.edn`→`seon.agent.session.edn` (**new resource owned here**, with the session render), `seon.agent.edn`, `seon.turn.edn`, `seon.cluster.eval.edn`, `seon.eval.edn`, `my.plan.edn`, `turn.clj`, `cluster/agent.clj`, `sci/eval.clj`, `repl.clj`, `render.clj`, `render/transcript.clj`, `render/walk.clj` (namespace half only), `render/web.clj`, `bootstrap.clj`, `agent.clj`, `issue.clj`, `cluster.clj`, `test.clj`, `eval/drive.clj`, `fn.clj :1226-1308` | R1–R6 | **RESET NEEDED** (batched) |
| P3 | Shape: session ⊃ turns (`:seon.turn/ordinal`) ⊃ evaluations (`:seon.turn/evaluations`); delete `:seon.turn/agent`, `:seon.cluster.eval/run`, the runtime; listens move; `render-run` → `render-turn`; the membership rewrite | `seon.agent.edn`, `seon.turn.edn`, `seon.cluster.eval.edn`, `seon.eval.edn`, `turn.clj`, `eval.clj`, `cluster/status.clj`, `cluster/agent.clj`, `cluster/wake.clj`, `cluster/message.clj`, `issue.clj`, `problems.clj`, `repl.clj`, `cluster/prompt.clj`, `fault.clj`, `oversight.clj`, `test.clj`, `render/web.clj`, `render/transcript.clj`, `context.clj`, tests | turn, transcript, status, wake, message, web-debug; R8, R9 | **RESET NEEDED** (batched): existing turns have a forward runtime component edge but no ordinal, session or evaluation membership (review finding 11); converting them is a migration |
| P4 | The derived selector and the shared graph contract (§5a): walk, context, debug graph, web and the graph schemas together; declared inverse attachments on namespace and other roots; the session render; the unread inbox; relevance-tier renders | `render/walk.clj`, `render/web.clj`, `seon.render.walk.edn`, `render/transcript.clj`, `render.clj`, `cluster/message.clj`, `cluster/agent.clj`, `seon.ns.edn`, `seon.message.edn`, `my.message` | R5, R7; the §1 graph as the page acceptance | none |
| P5 | Settings (§7) | `seon.config.edn`, `schema/edn.clj :63-93`, `ai.clj`, `cluster/prompt.clj`, `agent.clj`, `cluster/agent.clj`, the A-list owner files | config (incl. `config_test :506-521`), prompt, message, plan, issue, blob; R10 | **RESET NEEDED** (batched): existing overlays lack a base |
| P6 | Oversight (§10); ownership via steward | `seon.agent.edn`, `cluster/host.clj`, `issue.clj`, `cluster.clj`, `cluster/agent.clj`, `turn.clj :2599`, `render.clj :1736` | R11; problem routing | **RESET NEEDED** (batched): no oversees edges exist |
| P7 | Remaining doubles: `renderer-fn`, maintenance agent, occurrence agent-or-turn (with `error.clj:2058-2072`), `handled` after Q6, capture prompt text, eval error text, reply text, run renames (with `config/default.edn` and every manifest consumer) | owners named in §11 | error, routing, maintenance, config, capture | retirements |
| P8 | Dead selection functions only | `context.clj:76-256` | `context_capture_test` stays green | none |

**Order.**
1. P2a immediately.
2. P0 and P1 while P2b and P3 are reviewed. P1's `turn.clj` edit waits for P2a.
3. P2b, P3, P5 and P6 land in **one batched RESET**, only after R8 and P1's clock rows pass.
4. P4 follows.
5. P7 and P8 come last.

If P0's review fails, P3 lands option B and adds the membership and ordinal writer check.

**The membership rewrite (review finding 11).** P3 and P2b ship a reproducible, syntax-aware rewrite script
under `script/seon/dev/` using rewrite-clj (already a dependency, `deps.edn:107`), plus a manifest of
old → new forms:

- datalog clauses `[?t :seon.turn/agent ?a]` → `[?a :seon.agent/session ?s] [?s :seon.agent.session/turns ?t]`;
- `[?e :seon.cluster.eval/run ?t]` → `[?t :seon.turn/evaluations ?e]`;
- pull selectors and quoted keys;
- schema resources and defaults.

Coverage and verification:
- It runs over `src`, `script`, `test` and `resources`.
- Its verification pass reports every remaining mention, including auto-resolved `::agent` forms in
  `seon.turn`.
- The inventory is a lower bound: **82 distinct test files** (76 name `:seon.turn/agent`, 43 name
  `:seon.cluster.eval/run`) plus 25 naming `:seon.agent/namespace`.
- It is applied once in the implementation lane, then linted, loaded, and the semantic exceptions hand-fixed.

**Sizes (production, approximate; P0 unproved):**

| Slice | Lines |
|---|---|
| P2a | +10 |
| P0 | +50/−60 |
| P1 | +30/−10 |
| P2b | +60/−170 |
| P3 | +60/−90 |
| P4 | +80/−60 |
| P5 | +50/−60 |
| P6 | +40/−10 |
| P7 | +20/−100 |
| P8 | −180 |

P4 is the largest addition. It replaces the graph's reverse walk and the context's home selection with one derived
selector; it must stay under ~100 added lines or return with the smaller alternative.

## 14. Class regressions

- **R1.** An `in-ns` at form 2 of 3: each evaluation records its own namespace; the session moves; the next
  turn starts there.
- **R2 (P2a).** A nested `(do (in-ns 'x))` then a form in the same turn: settlement commits.
- **R3.** A scratch namespace is not in `changed-identities`, and `prepare-merge!`'s scoped digest reads never
  include it. P0.2 decides the `defn` case.
- **R4.** A request carrying `:seon.ns/name` moves the session before its turn; the move shows in the next turn.
- **R5.** After `in-ns`, the next prompt appends the new namespace's tight rendering; earlier evaluations keep
  their own `ns=>` prompts; the task's namespace and function render as tight data with their operations.
- **R6.** `remove-ns!` of a session's namespace moves the session in the same transaction or refuses; compaction
  keeps the session ns.
- **R7.** The inbox shows unread (no ack) messages. An accepted reply with no claim, a system turn, and an
  acknowledged message each behave as §9 says.
- **R8 (P0).** Writes (i)–(iv) of §6 at 61 and 2,061 turns, within their targets. Each refusal class of guide §4
  still refuses.
- **R9.** Compaction retracts the selected evaluations and appends a system turn in one settlement.
  Attachments are unchanged. `as-of` on the post-compaction database recovers the retracted source and shown text.
- **R10.** `settings!` with a sparse request changes the next turn. `config/apply!` changes an agent without that
  override. A missing base refuses with the named error.
- **R11.** Oversight survives a failed retirement join as "closing" and disappears after exit.
- **R12.** Empty plan: an agent is created without a plan; the first plan write creates it; removing its last
  fact removes the attachment; the plan render prints its empty form.
- **R13.** Forms-key collision: `:seon.turn/forms` (in-memory system result) and `:seon.turn/evaluations`
  (stored) both validate in the same projection.

## 15. Review findings (e80bf7c0d), answered

| # | Finding | Disposition |
|---|---|---|
| 1 | B's session writes still grow; R8 overclaimed | Accepted. §6 prices a session write as O(turn refs), about 3 ms at 2,061 (review counterfactual). A turn write is bounded by its actual evaluations. R8 now covers four writes. P0 is a prerequisite with its own note, not a ~40-line estimate. A over B now rests on P0, not on B's bound. |
| 2 | `:seon.turn/forms` already has a contract | Fixed: the stored relation is `:seon.turn/evaluations`; R13 |
| 3 | Empty-component lifecycle | Fixed: §8a (session and settings are born with a required fact; plan with its first fact; adoption retracts the empty plans) |
| 4 | Settings base vs sparse request | Fixed: §7 (two derived schemas, `effective` reused through the base, `schema/edn.clj` in P5) |
| 5 | Required refs misclassified; notes, listens, tasks/issues | Fixed: §4 (firing, capture and cluster→config required; firing recipient as an immutable observation; notes, listens, issues and assignments separate); situation stamp gets a named follow-up (§11); B's membership check (§6) |
| 6 | No shared graph contract | Fixed: §5a (one derived selector, attachment node shape, count/empty/refusal/cut/requery, ordering before windowing, shallow oversight, declared inverse concerns); P4 carries web and the graph schemas |
| 7 | Unanswered vs handled | Replaced by the owner's acknowledgement ruling (§9); answering keeps its wake predicate; sent messages stay reachable |
| 8 | Namespace adoption and refusal transitions | Fixed: §8b transition table, recovery boundary, narrowed program claim, P0.2 open edge; adoption is priced as a reset rather than claimed as accretion |
| 9 | Compaction writer, retention accounting, contributions | Fixed: §8d (entrance, writer checks, one settlement, clock row, accurate retention, post-compaction `as-of`); P8 keeps the capture contributions and deletes only the dead functions |
| 10 | Oversight across failed exit | Fixed: §10 |
| 11 | Reset and sweep claims | Fixed: §13 (honest reset reasons, 82-file lower bound, rewrite script and manifest, P1 and P3 file dependency, config defaults in P7) |
| 12 | Citations; semantic wording | Fixed: §12 (corrections applied in their installing slices; membership wording; no "history is not a query") |

## 16. Owner questions (few, priced)

**Q1. Turn ownership.**
- (a) *Recommended*: components all the way down after P0 and P1.
- (b) Fallback: turns as forward peer refs, with a membership and ordinal writer check.
- §6 prices both.

**Q2. Evaluation namespace as a value.** The review accepts it. It reverses "each evaluation keeps its own ns
ref". Confirm.

**Q3. The config row's own name.** Deferred, as the review agrees.

**Q4. A refusal that commits records where SCI ended.**
- (a) *Recommended*: yes, and the session is unchanged when no form evaluated or the recording aborts.
- (b) Never move on refusal.

**Q6. `:seon.turn/handled` once acknowledgement exists.**
- (a) Delete it in P7 if no consumer beyond the inbox remains.
- (b) Keep it as the turn's claim for a future "what did this turn handle" view.

**Q7. `:seon.render/units` as the declaration of an entity's reverse attachments.**
- (a) *Recommended*: keep the key, now meaning exactly that. Delete its extra readers (§5c). 0 new keys.
- (b) Rename it `:seon.render/attachments`: a clearer name, at the cost of a scripted rename across 3 schema
  resources and its readers.
- This reverses the 2026-09-25 design's deletion (its §6).

Q5 (schedules) is closed: they stay a declared attachment, as the review agrees.

## 17. The sample for the design review

`tmp/data-model-sample/sample.edn` holds three parts:
- the new schema forms;
- patches to existing packaged forms (added entries, made-optional members, added units, the settings component
  schema), applied by `load.clj` so every other member is kept;
- the demo record.

The demo record contains:
- agent `demo`, a namespace agent with no home: its session starts in its task's namespace `seon.repl`;
- three turns, where turn 1 moves to `demo.work` by a top-level `in-ns`, and every evaluation carries its
  namespace symbol;
- a plan;
- a settings overlay with base `[:seon.config/cluster "default"]`;
- a task referring to `seon.repl` and `seon.repl/response`;
- two messages from root, one acknowledged;
- `root :seon.agent/oversees demo`.

The demo agent's admission is closed, so no turn can run for it.

**Every modeling kind, as the view shows it:**

| Sample datum | Kind (guide §3) | Why the model needs it |
|---|---|---|
| demo ◆ session ◆ turns ◆ evaluations; demo ◆ plan; demo ◆ settings | component (cardinality-many for turns and evaluations) | owned history and owned concerns |
| settings → `[:seon.config/cluster "default"]` | required peer ref | the overrides' base |
| message → demo (`:seon.message/to`) | required peer ref, listened | the wake edge |
| demo → task; task → `seon.repl`; task → `seon.repl/response` | optional peer ref | current work; what it concerns |
| session → `demo.work` | settlement-moved edge, required | where the next turn starts |
| `demo-msg-1` `:seon.message/received-tx` | transition fact (tx ref by the recipient) | unread is derived from its absence |
| demo's inbox (`:seon.message/_to`) | declared reverse attachment | one node, unread 1 of 2 |
| `seon.repl`'s definitions (`:seon.fn/_ns`, existing program rows) | declared reverse attachment | the namespace half |
| root's sent messages (`:seon.message/from`) | undeclared reverse ref | queryable, not in root's story |
| each evaluation's `:seon.cluster.eval/namespace`; `seon.repl/response`'s 3 `:seon.fn/calls` | identity value | observed names that outlive their targets |
| root → demo (`:seon.agent/oversees`) | optional peer ref, cardinality-many | responsibility |

`tmp/data-model-sample/load.clj` is a JVM form that reads the sample and this JVM's packaged forms and returns
two SCI sources:
- `:run-1` registers 22 schema forms in one top-level `do`.
- `:run-2` transacts the record through `seon.db/transact!`, throws the writer's refusal whole, and pulls the
  record back through its forward refs.

Root submits each with MCP `eval_clj {mode "sci", branch <disposable>}`.

The generator was checked read-only on default's JVM: it returned both sources, and both read back as EDN. It defines no Var and writes nothing. It has **not** been run on a branch.

The sample keeps the branch's existing members that the design retires, for example `:seon.agent/namespace` in
the units, made optional where needed. The branch's own machinery keeps working, and the demo record simply does
not use them.

## 18. Evidence (read-only, JVM mode, default, 2026-09-27)

1. **Root's incoming refs:** `:seon.turn/agent` 61, `:seon.message/to` 27, `:seon.message/from` 22,
   `:seon.schedule.task/owner` 3, `:seon.schedule.fire/agent` 3, `:seon.maintenance.request/agent` 3,
   `:seon.error.occurrence/agent` 3, `:seon.runtime/agent` 1, `:seon.config/agent` 1, `:my.plan/agent` 1,
   `:seon.ns/steward` 1.
2. **Totals:** 113 agents; 117–121 evaluations; `:seon.runtime/listens` 0; `:my.plan/agent` 114;
   `:seon.config/agent` 114; `renderer` 3 and `renderer-fn` 3; occurrences 11 agent-only, 0 with a turn.
3. **History cost:** `of-agent` 1.55–1.82 s; `latest-evaluations` 1.56–1.60 s; raw pull 3 ms; evidence plans
   17,572,807 characters.
4. **Validation:** see §6. The review's counterfactual is artifact
   `473d73ba4d9384576e2848251e6cd2618568521a7c18ed72dfad3d821843b405`.
5. **Tokens:**

   | What | Estimated tokens |
   |---|---|
   | stored history | 34,593 |
   | messages read | 15,413 over 25 turns |
   | errors read | 8,455 over 26 |
   | runtime read | 6,000 over 25 |
   | `dir` | 32–11,284 |
   | settings summary | 76 |
   | effective settings | 879 |

6. **`:seon.agent/namespace`:** 55 source mentions (§3) and 25 test files.
7. **Store roster:** every production value holds one cluster row.
8. **Sample check:** `(load-file "tmp/data-model-sample/load.clj")` in JVM mode returned `:run-1` (22 registrations)
   and `:run-2`, both EDN-readable. `seon.repl/response` (eid 5837), `[:seon.ns/name seon.repl]` and
   `[:seon.config/cluster "default"]` exist on default.

Not measured:
- a full prompt build
- test-harness worlds
- any test run
- the sample on a branch

## 19. Findings from the sample load (for the fix lane fix-register-anywhere-opus and for root)

Root's two attempts to load `:run-1` on branch `design-1` (hosted agent `mcp/design-1`) failed. Per the owner,
the sample is **not** changed to avoid either failure. Both are our defects, owned by the fix lane. Diagnosis
below is read-only, from the branch head (commit `6ab94f9b-6617-5430-9387-571aa96a167f`) and from default.

### 19a. The rejected row, and why

- **The rejected row was not a schema row.** The fault the run recorded on `design-1` (turn eid 59962, at
  2026-09-27T17:17:15Z) says: "Complete component validation refused: unowned-entity … `:seon.db/entity 54842`".
  - Entity 54842 is a `:seon.cluster.eval/read-evidence` component; its dependency plan reads `:seon.fn/calls`.
  - Before the transaction it was owned by evaluation 39443, one of root's earlier forms,
    `(let [r (my.program/breaks …)] …)` (`[?o :seon.cluster.eval/read-evidence 54842]` → 39443).
  - After the transaction it had no owner, so the final writer refused the whole settlement
    (`write-owned-values-error`, `db.clj:3540`; Datahike's "Transaction report validation rejected",
    `transaction.cljc:1231`).
- **Why the owner edge disappeared.** `:run-1` redefines `:seon.cluster.eval/receipt` (the design's P3 change:
  `:seon.cluster.eval/run` optional, `:seon.cluster.eval/namespace` added). Adoption then does two things:
  1. `schema-attribute-change-tx` (`turn.clj:1097-1140`) marks every attribute of the redefined entity map as
     affected, including `:seon.cluster.eval/read-evidence`.
  2. For each kept attribute, `seon.cluster/attribute-change-tx` (`cluster.clj:1174`) calls
     `invalid-value-retraction`. Its docstring says "a cardinality-many attribute validates each entity's whole
     value set" (`cluster.clj` `invalid-value-retraction`): it builds `(into #{} …)` and validates that **set**.
- **Where it breaks.** `:seon.cluster.eval/read-evidence` is declared
  `[:vector {:seon.db/component true …} :seon.db/ref]` (`seon.cluster.eval.edn`). A set never satisfies
  `[:vector …]`, so every value is judged invalid and retracted. That orphans every evidence component, and the
  final validator refuses. Probe on default:

| Check | Result |
|---|---|
| `(projection-validator p :seon.cluster.eval/read-evidence)` on `#{55733 55734}` | **false** |
| same validator on `[55733 55734]` | **true** |
| `(seon.cluster/invalid-value-retraction d p :seon.cluster.eval/read-evidence)` | **459 retractions, i.e. every datom** |

- **The class.** 27 installed cardinality-many attributes are declared with `[:vector …]`, among them
  `:seon.cluster.eval/faults`, `:seon.fn/arities`, `:seon.fn.arity/arguments`, `:seon.fn.binding/entries` and
  `/children`, `:seon.test/failures`, `:seon.render/units` and `:seon.test.accretion/gate-tests`.
  - Redefining any entity schema that lists one of them drops that attribute's whole population on adoption.
  - Where the attribute is a component, the adoption refuses instead.
  - **This blocks the plan's P2b/P3 schema adoption on the live store too**, not only the sample.
- **The same run shows two total-path defects.** The caller got `{:err nil :committed? false :forms 0}` after 35
  s, with no events and no error value. The fault was recorded on the branch but never delivered to the caller
  (AGENTS "Deliver every error to whoever can act on it"). This is the same class as a318b2d38.

### 19b. Task entries for root to file

Both belong in `resources/seon/tasks/seon.cluster.agent.edn` or `seon.dev.mcp.edn`; root's call.

1. **"Branch MCP takes one form, so a multi-form registration cannot be sent."**
   - `eval_clj` in SCI branch mode refuses a second form (`script/seon/dev/mcp.clj:377-398`, "Evaluation accepts
     exactly one form").
   - `seon.schema/register!` refuses outside a run's top-level registration delta (`src/seon/schema.clj:1310-1320`,
     "Schema declarations require an isolated registration delta").
   - Together they mean no sequence of schema declarations can be sent to a branch through MCP. Root had to call
     `seon.cluster.agent/evaluate-source!` directly.
   - Fix direction: the branch entrance segments code as the root entrance does ("SCI without a branch evaluates
     every form in order as one run"), or `register!` accepts any position in a run.
2. **"A refused multi-form settlement returns no error and no events."**
   - `evaluate-source!` on `mcp/design-1` with 22 top-level `register!` forms answered
     `{:run "ce27ba34bd16", :err nil, :committed? false, :forms 0}` after 35 s.
   - The branch holds the recorded fault "Complete component validation refused: unowned-entity …" (turn 59962).
     The caller received neither it nor any evaluation event.
   - Total-path law: the value and its failure must return to the caller. Related: a318b2d38.

A third finding, for the schema-adoption owner rather than a new task if one exists:
`invalid-value-retraction` validates a cardinality-many attribute's value **set** against a `[:vector …]` form
and drops every value (§19a, 459 on default).

### 19c. Is the sample right per the design?

- **Yes, with one correction made.** `:seon.agent/branch` was the placeholder `:design-sample`. The agent's
  custody fact must name the branch that holds it, so it is now `:design-1`.
- Everything else matches the design, including the one change that triggered §19a: redefining
  `:seon.cluster.eval/receipt` is exactly the P3 change the design needs.
- `load.clj` now states the two blockers at its top and is not run.

### 19d. Schemas are not namespaces

A schema is identified by its qualified keyword alone. This document ties no schema to a namespace; the only
namespace-scoped things here are definitions (`:seon.fn/ns`) and tests (`:seon.test/ns`).

**Observation for the owner.** The source does tie them today:
- the schema row has `:seon.schema/ns`, described as "The namespace where this schema was declared"
  (`resources/seon/schemas/seon.schema.edn:87`, member at `:114`);
- `seon.sci.eval/directory-value` lists a namespace's schemas through `:seon.schema/_ns` (`sci/eval.clj:2577-2590`),
  so `(dir ns)` shows "declared schemas".

That relation is where a declaration was written (provenance), not what a schema is. Under the owner's statement
it belongs in transaction metadata, like authorship, rather than as a namespace attachment. This is not changed
here.
