---
type: research
status: evidence for the agent-record design (repl-session-entity-design-2026-09-27.md §5b, §5c, §11, §17, §19); for Astra review, then the owner; no source changed
created: 2026-09-27
branch: refactor/agent-platform
source-pin: 259fa5f7d (checkout); live default pid 75447, source 9e61340b2, branch design-1 head max-tx 536879984
lane: demo-story-opus
prototype: tmp/demo-story/renders.clj (disposable; the implementation lane lifts it)
---

# The demo agent's story: what it is, what it should read

What exists: agent `demo` on branch `:design-1` of default, loaded from `tmp/data-model-sample/`. It is the
design's target shape: agent ◆ session ◆ turns ◆ evaluations, plan and settings components, a task peer ref,
two messages to it, and root overseeing it. The debug page renders it through today's walk and today's renders,
which predate the design.

The principal approach here: pull the agent once with a selector **derived from the schemas** (§5a), render each
node with a **tight-data** render of the value it is handed (§5c), and compose the nodes and the session
transcript into one value (§5d). All of it was prototyped as pure functions of one database value of design-1
and run against the real demo entities. The result:

| Context for `demo` | Estimated tokens |
|---|---|
| today's debug walk at N=3 (89 nodes, forms not evaluated, 3 kinds skipped) | ≈3,668 |
| **proposed, now** (session in `demo.work`): 6 opening nodes + transcript | **438** (339 + 99) |
| proposed, at its opening (session in `seon.repl`, the task's namespace): namespace half included | 1,274 (1,174 + 99) |

Warm assembly takes 3.5–26 ms; a cold database value costs 80 ms, 58–61 ms of it projection acquisition (§4).

## 1. Root's ten observations, verified

All ten hold, checked on the page (`/agent/root/debug?n=3&branch=design-1&root=[:seon.agent/id "demo"]`) and
in the source at the checkout.

| # | Observation | Verdict and evidence |
|---|---|---|
| 1 | agent render predates the design | **Holds.** `seon.cluster.agent/render-identity-ai` (`src/seon/cluster/agent.clj:290-318`) returns `;;` prose, `(help)` and a pull of `:seon.agent/namespace`; it queries issues but delegates nothing. Its HTML twin reads `:seon.agent/runtime` turns and says "Idle · 0 turns" for an agent with 3 turns (`:320-360`) |
| 2 | session never a node; no pair; no transcript render | **Holds.** The page attributes `:seon.agent.session/ns` and the turns to the agent ("included by: root —:seon.agent.session/turns→"). `:seon.agent.session/session` declares no `:seon.render/ai` on the branch projection. `render-session-ai` exists nowhere in `src/` |
| 3 | every turn render fails | **Holds.** `selected-run-identities` reads `(:seon.turn/agent unit)` (`src/seon/render/transcript.clj:745-760`); with no such fact `render-run-ai` (`:782`) returns `missing-selected-run`, and the floor prints the 9-member error map in both faces |
| 4 | "● unread" ignores the ack; AI face is prose | **Holds.** `render-html` derives the state from `[?turn :seon.turn/handled ?message]` (`src/seon/cluster/message.clj:410-418`), never `:seon.message/received-tx`; `render-ai` (`:344-350`) is `;;` prose plus `(my.message/read …)` |
| 5 | plan and settings re-derive for the viewer | **Holds.** `render-plan-ai` calls `(plan {:seon.db/db … :seon.agent/id …})` for the unit's agent and returns `(seon.plan/plan {})` (`src/seon/plan.clj:1519-1552`); the page's HTML says "No objective set" beside the stored `:my.plan/objective`. `render-settings-ai` ignores its unit and returns `(seon.agent/settings)` (`src/seon/agent.clj:161-165`) |
| 6 | task render is prose with empties | **Holds.** `seon.render.ns/task-ai` (`src/seon/render/ns.clj:142-157`) prints `;; Done when … none`, `;; Subjects: none`, `;; Must read: none` and ends in `(seon.issue/task-edges …)`; it never reads `:seon.task/namespace` or `:seon.task/function` |
| 7 | the walk leaks across peers | **Holds.** Level 3 holds root's 28 messages (922 tokens), its plan, its runtime (130), 29 `seon.repl` functions (716) and 10 alias bindings (514) |
| 8 | branch is `:design-sample` | **Holds.** The datom on design-1 is `[60098 :seon.agent/branch :design-sample]`. `sample.edn` was corrected to `:design-1` after the load; the branch was never repaired (finding F1) |
| 9 | three kinds skipped | **Holds.** All three hang off `seon.repl/response` (e5837): its `:seon.fn/file` (e1620), its arity's argument row (e24007) and that row's binding (e24006). Under a derived selector a peer stops at identity, so none of them is reached |
| 10 | the page is not the agent's assembly | **Holds.** The page also titles itself "Context · root" and its "context as sent" is **root's** prompt, not the subject's (finding F5) |

## 2. The story, as demo should receive it

Context is the session transcript. The opening is turn 0: a system turn whose evaluations are the
attachments' renders. Later turns append the agent's own forms, and a system refresh appends only what changed
(turn PRD §14, design §5c.6). Every entry below is a form and its result, and every result is the **real
output** of the prototype renders on design-1. For an earlier moment the render was handed the value as it
stood then (the identity with its session in `seon.repl`; the inbox with no message, then with `demo-msg-1`
unacknowledged): a render renders the value it is handed. Two things are not real output: the operation
`(my.message/received! …)`, the target once that writer exists (the prototype prints
`(my.message/read #:my.message{:id …})`, F10), and the stored sample transcript (turns 1–2), whose shown values
the sample hand-wrote (F8).

`(render lookup)` is the proposed one generic requery: it pulls the lookup with the derived selector and
applies the selected render, so evaluating the printed form reproduces the printed data (question D2). It does
not exist yet.

**Turn 0, system: the opening.** The session starts in the task's namespace, `seon.repl`.

```clojure
seon.repl=> (render [:seon.agent/id "demo"])
{:seon.agent/id "demo", :seon.agent/branch :design-1, :seon.agent.session/ns seon.repl}

seon.repl=> (render [:seon.task/id "demo0task001"])
#:seon.task{:id "demo0task001", :intent "Make seon.repl/response return only keys the agent uses.",
 :namespace #:seon.ns{:name seon.repl, :meaning "The ONE generator of REPL bytes: one evaluation, as the session it was.", :public-count 16},
 :function #:seon.fn{:sym seon.repl/response, :meaning "The reply: exactly one `#:seon.repl{…}` map that reads back as data.",
                     :contract [:=> [:cat :seon.repl/emission] [:maybe :string]]}}

seon.repl=> (render [[:seon.agent/id "demo"] :seon.agent/plan])
#:my.plan{:objective "Shorten the REPL reply map without losing the value or its handle."}

seon.repl=> (render [[:seon.agent/id "demo"] :seon.agent/settings])
{:seon.config/base "default", :seon.config.ai/model "deepseek-flash"}

seon.repl=> (render [[:seon.agent/id "demo"] :seon.message/_to])
#:seon.message{:unread [], :received 0}

seon.repl=> (render [:seon.ns/name seon.repl])
#:seon.ns{:name seon.repl, :meaning "The ONE generator of REPL bytes: one evaluation, as the session it was.",
 :functions [#:seon.fn{:sym seon.repl/cost, :meaning "The measures the declared bounds judge for one evaluation: …", :contract [:=> [:cat :seon.repl/emission] :seon.repl/cost]}
             … 16 entries, 858 tokens …
             #:seon.fn{:sym seon.repl/value-text, :meaning "Return the saved shown text unchanged.", :contract [:=> [:cat :seon.repl/emission] [:maybe :string]]}]}
```

The inbox entry is the empty form: at the opening no message had arrived. The sample's own turn 0 holds the
pre-design opening instead, `(help)` and `(dir seon.repl)`; under the design those two entries are replaced by
the six above.

**Turn 1, the agent: moves to its work namespace and defines a sample.**

```clojure
; Move to the work namespace.
seon.repl=> (in-ns 'demo.work)
#namespace[demo.work]

demo.work=> (def sample-reply {:seon.repl/value 1 :seon.repl/ms 3})
#'demo.work/sample-reply
```

**Turn 2, woken by `demo-msg-1`.** The refresh appends what changed since turn 1: the new session namespace's
half, and the inbox. Then the agent's own form.

```clojure
demo.work=> (render [:seon.ns/name demo.work])
#:seon.ns{:name demo.work, :functions []}

demo.work=> (render [[:seon.agent/id "demo"] :seon.message/_to])
#:seon.message{:unread [{:seon.message/id "demo-msg-1", :seon.message/from "root", :seon.message/at #inst "2026-09-27T17:41:11.994-00:00",
                         :seon.message/content "Please shorten seon.repl/response.",
                         :seon.render/operations [(my.message/received! {:seon.message/id "demo-msg-1"})]}],
               :received 0}

demo.work=> (doc seon.repl/response)
"The reply map for one evaluation."
```

`my.message/received!` is the design's acknowledgement writer; it does not exist, so the prototype's operations
query finds only `(my.message/read #:my.message{:id "demo-msg-1"})`, which re-reads what the entry already
shows (finding F10). The sample records the acknowledgement in its load transaction, not in turn 2. The `doc`
result is the sample's invented text; the stored docstring is the `:meaning` shown in turn 0.

**Turn 3, system: what is new now.** `demo-msg-2` arrived and is unread.

```clojure
demo.work=> (render [[:seon.agent/id "demo"] :seon.message/_to])
#:seon.message{:unread [{:seon.message/id "demo-msg-2", :seon.message/from "root", :seon.message/at #inst "2026-09-27T17:41:11.994-00:00",
                         :seon.message/content "Also keep :seon.repl/result.",
                         :seon.render/operations [(my.message/received! {:seon.message/id "demo-msg-2"})]}],
               :received 1}
```

That is the whole story in about 1,280 tokens: who I am and what I am doing (≈230), the namespace I work in
(858, the one large part, and it disappears from later refreshes once I move), what I did (≈60), and what
changed (≈110). Nothing in it is a comment the agent did not write; every line is runnable.

### The same agent seen by root

Perspective is a render input. The prototype run with viewer root (`demo-context-by-root`, 428 tokens):

```clojure
user=> (render [:seon.agent/id "demo"])
{:seon.agent/id "demo", :seon.agent/branch :design-sample, :seon.agent.session/ns demo.work, :seon.agent/admission :closed}

user=> (render [[:seon.agent/id "demo"] :seon.message/_to])
#:seon.message{:messages [#:seon.message{:id "demo-msg-1", :to "demo", :at #inst "…", :content "Please shorten seon.repl/response.", :received? true}
                          #:seon.message{:id "demo-msg-2", :to "demo", :at #inst "…", :content "Also keep :seon.repl/result.", :received? false}]}
```

The closed admission appears only to another viewer: an agent whose admission is closed runs no turn, so demo
never reads it. As the sender, root sees `received?` instead of an unread list. Root's own oversight attachment
renders `[#:seon.agent{:id "demo", :branch :design-sample, :admission :closed}]` in 0.25 ms. Root's prompt is
`user=>` because root on design-1 has no session (the branch's other agents keep the old shape).

## 3. Every fact reachable from demo, with its consumer and its render

Kinds are the guide's §3 choices. "Now" is what renders it on the page today; "should" is where its render
belongs (the schema pair is the global default; a viewer-namespace function or an inline value overrides it).
Tokens are chars/4 of the prototype's real output. **No reachable fact lacks a consumer**; three are questioned
in §6 (D8, D9, D10) and none is marked for deletion here beyond the design's existing D list (§11 there).

### The agent (e60098)

| Fact | What it is | Kind | Consumer (who reads it, why) | Render now → output | Render should live | Target output | Tokens |
|---|---|---|---|---|---|---|---|
| `:seon.agent/id` "demo" | the agent's name | identity value | lookups; message routing; every render | `cluster.agent/render-identity-ai` → `;;` prose, `(help)`, pull of retired `:seon.agent/namespace` | `:seon.agent/agent` pair | `{:seon.agent/id "demo" :seon.agent/branch … :seon.agent.session/ns …}` | 35 |
| `:seon.agent/branch` `:design-sample` | custody: where its writes land | value (keyword) | the admission and turn writers; root's oversight line | identity table only | same pair; oversight pair | in the identity map; **value wrong** (F1) | in 35 |
| `:seon.agent/admission-closed-tx` | its admission closed | transition fact (tx ref) | the run writer's refusal (`system-run-call`, `plan-call`); root's oversight | table only | agent pair, **perspective**: other viewers only | `:seon.agent/admission :closed` | +6 |
| `:seon.agent/session` | its one ongoing REPL | component, required | turns, transcript, current ns | never a node | `:seon.agent.session/session` pair (new) | the transcript | 99 |
| `:seon.agent/plan` | its instructions | component, optional | the plan render; plan writers | `seon.plan/render-plan-ai` re-derives for the viewer → "No objective set" + `(seon.plan/plan {})` | `:my.plan/entity` pair only (drop the attribute's duplicate pair, D6) | `#:my.plan{:objective "…"}` | 39 |
| `:seon.agent/settings` | its dial overrides | component, required | `seon.ai/agent-settings`; the settings render | `seon.agent/render-settings-ai` ignores the value → `(seon.agent/settings)` | `:seon.config/agent-settings` pair only (D6) | `{:seon.config/base "default" :seon.config.ai/model "deepseek-flash"}` | 35 |
| `:seon.agent/task` | what it is doing now | optional peer ref, declared unit | the task render; test ownership (design §3) | `render.ns/task-ai` → prose with empties + `(seon.issue/task-edges …)` | `:seon.task/task` pair | intent + namespace and function as tight data | 120 |
| `:seon.message/_to` (2) | its inbox | declared reverse attachment | the inbox; wakes (forward `to`) | per message `;;` + `(my.message/read …)`; HTML "unread" from `handled` | the `:seon.message/to` attribute pair (the inbox render) | unread (no ack) oldest first + received count; ops per message | 87 (1 unread) |
| `:seon.agent/_oversees` (root → demo) | who answers for it | undeclared reverse | root's oversight render | page expands root fully at level 2–3 | root's `:seon.agent/oversees` pair | not in demo's story; root sees one line | 0 (demo) / 30 (root) |

### The session (e60101), its turns and evaluations

| Fact | What it is | Kind | Consumer | Render now → output | Should live | Target output | Tokens |
|---|---|---|---|---|---|---|---|
| `:seon.agent.session/ns` → demo.work | where the next turn starts | required peer ref, written by settlement | SCI `*ns*` binding; the prompt; render selection step 2; the namespace half | attached to the agent; `render.ns/render-ai` → `(ns demo.work)` + `(dir demo.work)` | the session pair names it; the namespace half is the `:seon.ns/ns` pair | `#:seon.ns{:name demo.work :functions []}` | 22 |
| `:seon.agent.session/turns` (3) | the ordered history | component set, ordered by `:seon.turn/ordinal` | transcript; compaction | three turn nodes, all failing (#3) | session pair: the transcript | `ns=> source` + shown text, by ordinal | 99 |
| `:seon.turn/id` | the turn's handle | identity value | MCP run ids; fault and occurrence refs; `handled` | table only | none in AI (detail on request) | — | 0 |
| `:seon.turn/ordinal` | position in the session | value | ordering and windowing | table | transcript order | order only | 0 |
| `:seon.turn/opened-tx`, `closed-tx` | lifecycle | tx refs | open-turn and wake answering | table | none in AI | — | 0 |
| `:seon.turn/reply-size` | size of the provider reply | value (its presence also marks "replied") | calibration; `latest-answering-turn-t` and wake predicates read its tx (`turn.clj:480`, `:3018`, `:3138`) | table | none in AI | — (D9) | 0 |
| `:seon.turn/trigger` (turn 2 → msg-1) | why the turn opened | optional peer ref | wake cause; the turn detail | table | the turn pair (single-turn detail) | `{:seon.turn/ordinal 2 :seon.turn/trigger "demo-msg-1" …}` on request | 0 in transcript |
| `:seon.turn/evaluations` | the turn's forms | component set, ordered by eval ordinal | transcript; compaction | table | session pair | entries | in 99 |
| `:seon.cluster.eval/id` | the evaluation's handle | identity value | result handle `result/eN`; fault refs | per-eval node | none in AI | — | 0 |
| `:seon.cluster.eval/ordinal` | position in its turn | value | order | — | transcript order | order only | 0 |
| `:seon.cluster.eval/namespace` | where the form ran | identity value (symbol) | the `ns=>` prompt | per-eval node | transcript | `demo.work=>` | in 99 |
| `:seon.cluster.eval/source`, `/comment` | what the agent typed | values | transcript | per-eval node | transcript | `; comment` + source | in 99 |
| `:seon.eval/shown` | what the agent saw | value | transcript | per-eval node | transcript | the saved text, unchanged | in 99 |
| `:seon.cluster.eval/at` | when it ran | instant | timing, cost rows | table | none in AI | — | 0 |
| `:seon.cluster.eval/author` | `:system` or `:agent` | stamp | `latest-evaluations` system filter (`turn.clj:2047`), agent-written query (`:3673`), `:5168` | table | none in AI | — (D8) | 0 |

### Plan, settings, task, messages, and the peers they name

| Fact | What it is | Kind | Consumer | Render now → output | Should live | Target output | Tokens |
|---|---|---|---|---|---|---|---|
| `:my.plan/objective` | what the agent is for | value | plan render; `seon.plan/plan!` | not shown (the render re-derives) | plan pair | the objective string | in 39 |
| `:seon.config/base` → config "default" | the settings' base | required peer ref | `agent-settings` reader (design §7) | table | settings pair | `"default"` (identity value of the peer) | in 35 |
| `:seon.config.ai/model` | a dial override | value | provider request | table | settings pair | the value | in 35 |
| `:seon.task/id` | the task handle | identity value | lookups; `:seon/tasks` annotations | prose header | task pair | the id | in 120 |
| `:seon.task/intent` | what done means | value | the agent | prose | task pair | the string | in 120 |
| `:seon.task/name` | readable name | value, identity aspect | the handle digest `[ns name kind]`; task EDN | prose header | none in AI (relevance: intent says it) | — | 0 |
| `:seon.task/kind` `:function` | subject kind | enum, identity aspect | the handle digest | prose header | none in AI | — (D10) | 0 |
| `:seon.task/ns` seon.repl | the task file's stem | identity value | write-back of task EDN; 19 source mentions | prose header | none in AI | — | 0 |
| `:seon.task/namespace` → seon.repl | what the task concerns | optional peer ref | task render; test ownership | not read | task pair | `{:seon.ns/name … :seon.ns/meaning … :seon.ns/public-count 16}` | in 120 |
| `:seon.task/function` → seon.repl/response | what the task concerns | optional peer ref | task render | not read | task pair | `{:seon.fn/sym … :seon.fn/meaning … :seon.fn/contract […]}` | in 120 |
| `:seon.message/id`, `/content` | the message | identity value; value | inbox; `my.message/read` | prose + read form | message pair, called by the inbox pair | id + content | in 87 |
| `:seon.message/to` → demo | recipient, listened | required peer ref | wakes; inbox | table | inbox pair (reverse) | omitted for the recipient (it is the viewer) | 0 |
| `:seon.message/from` → root | sender; marks an inside wake | optional peer ref | wake classification; sender's view | root expanded at level 2 with its 28 messages | stops at identity | `"root"` | 2 |
| `:seon.message/received-tx` | the recipient's acknowledgement | transition fact (tx ref) | inbox unread (design §9) | **ignored**; unread read from `:seon.turn/handled` | inbox pair | absent ⇒ in `:unread`; present ⇒ counted in `:received` | — |
| message time | when it was sent | derived: tx instant of `:seon.message/id` | inbox order | HTML "21 min ago" | message pair | `:seon.message/at` | in 87 |
| seon.repl: `:seon.ns/name`, `/doc`, `:seon.fn/_ns` | the task's namespace | identity; value; declared reverse unit | task render; namespace half | 29 function nodes + 10 alias nodes (1,230) | `:seon.ns/ns` pair: brief as a mention, tight as the session's half | brief 30 / tight 858 | — |
| seon.repl: `/aliases`, `/requires`, `/source`, `:seon.fn/file`, digests | program structure | components; refs; values | program, publication, write-back | alias nodes 514; file skipped | not in AI; `(dir ns)`/source on request | — | 0 |
| seon.repl/response: `/sym`, `/doc`, `/spec` | the function | identity; values | task render; `doc` | 222-token node with full source | `:seon.fn/fn` pair: brief | sym + first sentence + contract read as data | ~45 |
| seon.repl/response: `/source`, `/calls`, `/arities`, `/file`, digests | the program graph | values; components; ref | callers, publication, analysis | full source, callees, callers | on request (`(doc f)`, source) | — | 0 |
| config "default" (e34508), root (e34517) | peers | identity | their own renders | expanded nodes at level 2 (42 + 106 tokens), root's attachments at 3 | stop at identity | `"default"`, `"root"` | 2 each |

## 4. The prototype and its measurements

`tmp/demo-story/renders.clj`, loaded with `load-string` into the disposable JVM namespace `tmp.demo-story.renders`
and run in MCP JVM mode on the design-1 connection and on `datahike.versioning/branch-as-db` of it. Nothing was
written; no scratch branch was created.

| Function | Role (design section) | Real output on demo | ms (warm) |
|---|---|---|---|
| `entity-selector` | the derived selector (§5a) | 68,632-character selector for `:seon.agent/agent` (every peer ref carries all 48 installed identity attributes, D1) | — |
| `operations-index`, `operations`, `call-form` | operations as a query (§5c.3) | message → `(my.message/read #:my.message{:id "demo-msg-2"})`; plan, settings, task → none | index 15.9–18.9 per db value |
| `agent-ai` | agent pair | 35 tokens; +`:seon.agent/admission :closed` for root | 0.004–0.04 |
| `task-ai` | task pair | 120 tokens | 0.15–0.33 |
| `plan-ai`, `settings-ai` | plan and settings pairs | 39, 35 tokens | 0.002–0.16 |
| `inbox-ai`, `message-ai` | inbox pair, message pair | 87 tokens (1 unread, 1 received); 97 for root | 0.07–0.24 |
| `session-ai`, `turn-ai`, `evaluation-entry` | session pair: the transcript | 99 tokens | 0.01–0.13 |
| `ns-ai`, `ns-brief`, `fn-brief` | namespace half; mentions | seon.repl 858 tight (design est. ~900; full `dir` 2,726); demo.work 22 | 0.02–0.5 |
| `oversees-ai` | root's oversight pair | `[#:seon.agent{:id "demo", :branch :design-sample, :admission :closed}]` | 0.25 |
| `assemble` | §5d, one value: nodes + text | 438 tokens now; 1,274 at the opening | total 3.5 (same db value), 24–26 (fresh `branch-as-db` value), 80 (cold: world 58–61 + ops index 16) |

Cold cost is projection acquisition (`seon.schema/projection-from-database`, 58–61 ms on a value it has not seen,
5–7 ms after) and the operations index (16 ms per value). Both are functions of the program identity and belong
in `seon.cache`; neither is over a second.

The prototype takes its perspective, render location and operations from data: `select-render` implements the
AGENTS order (inline `:seon.render/ai` on the value, then a function in the viewer's current namespace, then
the schema pair, then the floor); a namespace override is a map entry in the call, not an edit to a builder.

## 5. System findings (C), with proposed task entries

Each is a defect met while working; root files them (this lane edits no task EDN).

| # | Finding | Evidence | Proposed task (file) |
|---|---|---|---|
| F1 | demo's custody fact names a branch that does not hold it | `[60098 :seon.agent/branch :design-sample]` on design-1; `tmp/data-model-sample/sample.edn:117` says `:design-1` (corrected after the load, never repaired) | "Repair the demo sample's `:seon.agent/branch` on design-1 or reload the sample" — root, no src (`seon.dev.mcp.edn` or none) |
| F2 | reading a branch after a restart needs a hand-built writer connection | the page refuses: "This process holds no open connection to branch …; open one (the branch's writer does), then reload" (`src/seon/render/web.clj:3441-3453`); root's recipe reaches through `datahike.db.interface/-config` and leaves an owning connection no one releases. `datahike.versioning/branch-as-db` (`reference-code/datahike/src/datahike/versioning.cljc:503`) returned the same head (same `max-tx`) in 2.6 ms with no connection, and the assembly over it matched | "Read-only branch views take `branch-as-db`, never a writer connection" — `seon.render.web.edn` / `seon.db.edn`; owner `render/web.clj:3429-3455`, `db.clj:354` |
| F3 | MCP JVM windowing spends more tokens on elisions than on what it elides | a 9,530-byte value (≈2,400 tokens) came back with members such as one datom `[:seon.message/content "…"]` (≈12 tokens) replaced by a ≈70-token `(drop 1 (seon.print/value-at (seon.render.value/artifact-value (seon.render.value/read-artifact (seon.blob/get …)))) …)` form; the same blob path repeated in every elision | "The floor never elides a member whose shown size is below its elision's size; one requery root per reply" — `seon.print.edn` (floor owner) |
| F4 | an MCP JVM exception names only the dependency's innermost frame | `:seon.error/frame [datahike.db.utils$entid invokeStatic "utils.cljc" 145]` for a nil eid passed by my own `d/pull`; the calling frame was recoverable only by catching and printing `.getStackTrace` myself | "An error value's frame is the innermost first-party or evaluated frame, beside the throwing one" — `seon.error.edn` |
| F5 | the debug page labels and shows root's context while viewing demo | header "Context · root", "agent root"; "the context as sent" is `seon.cluster.prompt/prompt` of root; HTML identity says "Idle · 0 turns" for 3 turns (reads `:seon.agent/runtime`) | folds into design §5d slice A2 (the page displays the subject's assembly); no separate task unless A2 slips |
| F6 | turn renders fail on every design-shaped turn | #3 above; `transcript.clj:745-782` | P3's `render-run` → `render-turn` rename (design §13) owns it; record as the failing class so it is not re-found |
| F7 | the operations query cannot see what contracts under-declare | `my.agent/settings!` declares `:my.agent/settings-request` = `[:map connection agent-id]` (`resources/seon/schemas/my.agent.edn:9-11`) while it takes dial keys; `:my.plan/objective` has no toolkit writer (only `seon.plan/plan!`, `src/seon/plan.clj:1294`); `my.message/received!` does not exist | three entries: "settings! declares its dials (`:seon.config/agent-overlay`)" — `my.agent.edn`; "a toolkit writer for the plan objective" — `my.plan.edn`; "`my.message/received!` taking `[:cat :seon.message/id]`" — `my.message.edn` (the last is design P4) |
| F8 | the sample transcript's shown texts are invented | `(help)` → `{:seon.repl/value "help text"}`; `(dir seon.repl)` → two functions of 16; `(doc seon.repl/response)` → "The reply map for one evaluation." while the stored doc begins "The reply: exactly one …" | "Regenerate the sample's shown texts from real evaluations, or mark them placeholders" — `tmp/data-model-sample` (root) |
| F9 | the design quoted values it had not read | design §5c: meaning "The reply map for one evaluation." and contract `… :string` (stored: `… [:maybe :string]`); "30 definitions" (16 public, 14 private); operations query "0.4–5 ms" (measured 10.7–22.9 ms joined to function symbols). Corrected in place | none (doc corrected in this commit) |
| F10 | the one operation found on a message re-reads it | `(my.message/read {:my.message/id …})` returns the content the entry already shows | covered by F7's `received!`; the operations render should drop pure re-reads of the value it renders (prototype does not yet) |
| F11 | the data-modeling skill contradicts the source on the author stamp | skill: "Do not add an author-kind stamp"; `:seon.cluster.eval/author` has 17 source uses (`turn.clj:524-5169`) | "Resolve `:seon.cluster.eval/author`: derive from the turn or correct the skill" — high priority per AGENTS (stale skill); question D8 |
| F12 | two render pairs declared for one concern | `:seon.agent/plan` and `:my.plan/entity` both declare `seon.plan/render-plan-*`; `:seon.agent/settings` and `:seon.config/agent-settings` both declare `seon.agent/render-settings-*` | question D6 |

Not findings but recorded: default was **faulted** before this lane started (10 open faults, 75 failed tests,
`seon.test/run` 67.8 s max and `seon.sci.kernel/adopt-arm` 66.6 s in the over-second lines). No call this lane
made exceeded one second; the slowest was one MCP evaluation at 464 ms (the first full datom dump).

## 6. What feels wrong in the data model itself (D), with priced options

Recommendation first in each.

**D1. A peer ref does not say what it points at.** `:seon.db/ref` names no target kind, so the derived selector
cannot choose the target's identity attribute: every peer subpattern lists all 48 installed identities (68,632
characters for demo), and the walk and the page cannot know a peer's kind without reading its datoms.
- (a) *Recommended.* Declare the target on the ref: `[:and {:seon.db/target :seon.task/task} :seon.db/ref]`. The
  selector derives one identity per peer, and the final writer gains the target-existence check the guide says
  refs lack. Cost: one storable property, ~40 ref attributes annotated by one scripted sweep, ~20 writer lines.
  Sacrifice: a ref that truly accepts several kinds needs an `[:or …]` of targets.
- (b) Resolve identity from the target's datoms at render time (as `render.clj` `entity-lookup` does today). Cost
  0 schema lines, one EAVT seek per peer. Sacrifice: kind stays unknown to selectors and validation.
- (c) Keep the all-identities selector. Cost 0. Sacrifice: a selector that grows with every new identity.

**D2. What does an AI render return: the form or the data?** The owner asked for tight data; AGENTS asks for
REPL entries whose results are evaluated. Today a render returns forms (`(my.message/read …)`) whose results say
something else.
- (a) *Recommended.* A render returns tight data; the composer prints it as the result of one generic requery
  form, `(render lookup)`, whose evaluation IS that render (pull with the derived selector, apply the selected
  render). Every entry is honest and runnable; detail is the same function with another render location. Cost:
  one public function (~15 lines) and one render-output contract per kind. Sacrifice: the transcript shows
  `(render …)` rather than domain verbs.
- (b) Renders return forms calling domain readers, evaluated once. Cost: one tight reader per kind (a second
  function beside each render). Sacrifice: two definitions of one view.
- (c) Use the existing third slot, `:seon.render/form`, for the requery and `:seon.render/ai` for the data. Cost:
  two functions per kind, already partly declared. Sacrifice: the printed form and the printed data can drift.

**D3. Render outputs use keys nobody declared.** Tight data needs derived keys: `:seon.fn/meaning`,
`:seon.ns/public-count`, `:seon.message/unread`, `:seon.message/received`, `:seon.message/received?`,
`:seon.message/at`, `:seon.agent/admission`, `:seon.render/operations`.
- (a) *Recommended.* Declare each once as a non-attribute schema in its owning resource, and give each render an
  output contract naming them. Cost: ~10 declarations, 8 contracts. Sacrifice: none beyond the lines.
- (b) Reuse stored keys for derived values (a first sentence under `:seon.fn/doc`). Cost 0. Sacrifice: the key
  then lies about the fact.
- (c) Leave them open-map extras. Cost 0. Sacrifice: breaks "declare facts at their producer"; agents cannot
  `doc` them.

**D4. "Operations on this data" finds what contracts name, and toolkit contracts name aliases and generic
requests.** `my.message/read` takes `:my.message/read-request` whose `:my.message/id` aliases `:seon.message/id`;
the direct query the design specified finds nothing for messages. Every function whose request carries a
**supplied** input (`:seon.agent/id`, `:seon.db/db`) looks like an operation on agents; `:my.plan/request` is a
generic `{db, agent}` request that made ten readers look like plan operations in the first run.
- (a) *Recommended.* Index request members and their alias chains, excluding supplied-by-name inputs (the
  prototype: ~40 lines, 16 ms per projection, cached); fix the under-declared contracts (F7). Sacrifice: a
  derived index to keep in `seon.cache`.
- (b) Only direct input refs (the design's wording). Cost 0. Sacrifice: finds almost nothing the toolkit offers.
- (c) Declare operations on entity schemas. Rejected by AGENTS ("never author what the call graph already derives").

**D5. Is anything in context outside the transcript?** Under §5c.6 the opening is turn 0's system evaluations,
so attachments appear only as turn-0 entries and appended refreshes. The debug page's attachment nodes are then
the provenance of entries, not a second presentation.
- (a) *Recommended.* Yes, the transcript is the whole context; the assembly's nodes are what produced each system
  entry. The prompt prefix is stable (provider caching) and the agent's history is what it saw. Cost: the refresh
  must know which node each prior entry came from (a `:seon.context/node` on the stored evaluation, or its form).
  Sacrifice: a stale opening stays until a refresh or compaction.
- (b) Render attachments live above the transcript every turn, never stored. Cost: less. Sacrifice: the prefix
  changes every turn (no provider cache), and what the agent saw is no longer stored history.
- (c) Both. Rejected: every attachment twice.

**D6. One concern, two render declarations.** The plan and settings pairs are declared on the agent's
attribute and again on the component's entity schema (F12).
- (a) *Recommended.* A pair lives on the entity schema that describes the value; attribute pairs remain only
  where the attribute's reverse is the collection (`:seon.message/to` → the inbox). Cost: two properties deleted.
- (b) Keep both. Sacrifice: which one wins is selection-order trivia.
- (c) Attribute only. Sacrifice: a component rendered alone has no pair.

**D7. `:seon.error/of-steward` is a ref attribute that is never stored.** It exists to hang a render pair and to
appear in the agent's units (`resources/seon/schemas/seon.error.edn:238-246`, `:seon.render/derived true`).
- (a) *Recommended.* Faults routed by stewardship are namespace information: the namespace render shows its
  functions' open faults; the agent's own occurrences are the stored reverse `:seon.error.occurrence/_agent`
  declared as a unit. Cost: one unit, one namespace-render field. Sacrifice: an agent stewarding many namespaces
  sees their faults only through those namespaces.
- (b) Keep the fake attribute. Sacrifice: a schema that declares a fact no writer stores.
- (c) A unit that names a query function. Sacrifice: a new declaration shape.

**D8. System authorship is a stamp.** `:seon.cluster.eval/author` (`:system`/`:agent`) is written on every
evaluation; the skill says derive it (F11). In the session model a system turn is a turn with no provider
attempt, which the turn already knows.
- (a) *Recommended.* Derive at the turn (no `:seon.turn/attempts`), delete the stamp in P7, convert the three
  readers. Cost: ~15 lines. Sacrifice: a mixed turn (system refresh forms inside an agent turn) needs its forms'
  origin from the form plan, not the turn.
- (b) Keep the stamp and correct the skill. Cost: one skill edit.
- (c) Both. Rejected.

**D9. `:seon.turn/reply-size` means two things.** Its value is calibration; its presence (and its transaction)
is read as "the turn replied" by wake answering (`turn.clj:480`, `:3018`, `:3138`).
- (a) *Recommended.* Keep, and say both meanings in its description; the reply transaction is exactly what
  answering needs. Cost: a description edit.
- (b) Add `:seon.turn/replied-tx`. Cost: a new fact and writer. Sacrifice: two facts written together always.

**D10. The task's identity aspect carries a derivable kind.** `:seon.task/kind :function` follows from
`:seon.task/function` being present, but it is part of the handle digest `[ns name kind]`. `:seon.task/ns` (file
stem) and `:seon.task/namespace` (subject) are both seon.repl here with different meanings.
- (a) *Recommended.* Keep all three; each has a consumer (the handle, the task file, the subject). The render
  omits kind, name and file stem by relevance, which is not hiding: the agent reads intent and subject. Cost 0.
- (b) Derive kind and drop it from the aspect. Cost: every task handle changes (a rename across task EDN and
  `:seon/tasks` annotations). Sacrifice: churn for one keyword.

**D11. A tight contract shows inputs the agent never passes.** `seon.repl/frame`'s contract
`[:=> [:cat :seon.db/db :seon.agent/id] :string]` is called as `(frame)`.
- (a) *Recommended.* The tight contract drops supplied-by-name inputs (render-time, from the call-preparation
  index); `(doc f)` keeps the whole. Cost: ~10 lines in `fn-brief`.
- (b) Show the whole contract. Cost 0. Sacrifice: the agent's first call is wrong.
