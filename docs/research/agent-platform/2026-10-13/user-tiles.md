---
type: research
status: active
created: 2026-10-13
tags: [research, ui, tiles, chat, approval]
---

# User-facing tiles, chat, and approval: what exists, what is designed, and the smallest path

Research only, 2026-10-13. Owner's description, quoted: "all HTML renders generated from the agent are tiles and the most recent one is the primary tile. A chat coming in renders like a chat and the user could type a reply. Something else might be the workout data they requested rendered as a graph."

Marking: [V] = verified by a file, symbol, commit or memory note read in this session; [I] = inference from those. Paths are relative to the repo root unless absolute.

## Bottom line

1. The owner's "tile" design is not in the current tree. It was a Clojure-ClojureScript pod design (commit 4a309f5ff, 2026-07-02, "canvas = last-updated tile — derived default, pin to override"). The pod is deleted [V CLAUDE.md "the pod/self-host engine is deleted" via the clojurescript skill; V `git show f23a2aa4b --stat` deletes `.claude/skills/ui-live-tiles/SKILL.md`]. The owner re-stated the rule on 2026-07-13 in memory ("canvas is THE default surface", focal tile on `/agent/{id}`) [V /Users/sean/.claude/projects/-Users-sean-src-seon/memory/feedback_canvas_is_default_interactive_surface.md].
2. The current word for the surface is **canvas**, and it is TARGET only. `docs/seon/architecture/vocabulary.md` marks "[TARGET] canvas" as "no declared attribute exists yet" and lists "tile, live-tile, world" as retired spellings [V vocabulary.md, row "[TARGET] canvas"]. The attribute `:seon.render.canvas/content` is not declared anywhere [V `docs/prds/sci-execution-runtime/research/whole-system-arc-drive-2026-08-08.md`, lines 302-312, and my rg of `src/`, `resources/seon/schemas/`].
3. What works today for a user: the agent page (`/agent/{id}`) shows the transcript and a constant **message bar** that POSTs to the agent. That is the only user-to-agent input. A chat-like message from the user is committed as a fact and wakes the agent [V and I: see section 3].
4. What is missing: a render that is an input (a reply box bound to a specific message or question), a "primary tile" (most recent render), a graph render for data (only a debug graph exists), a question-task with an answer schema, `my.agent/answer!`, and any approval gate that holds a step not-done until the user approves.
5. The workout example is not designed anywhere as a UI. It appears only as a planning scenario in `docs/research/agent-platform/task-scenarios-2026-09-24.md` section 2 ("Build me a workout tracker"), with one child task `workout/page` that delivers `my.workout/render-entry-ai` and `my.workout/render-entry-html` and no graph [V].

## 1. The tile design: spec, location, build state

**Spec and history**

- Commit `4a309f5ff` (2026-07-02) "feat(render): canvas = last-updated tile — derived default, pin to override". It touched `src/seon/render/live_tile.cljs`, `src/seon/agent/ctx/render_fns.cljs`, `docs/seon/architecture/ui.md`, `docs/seon/architecture/context.md`, `docs/prds/agent-ctx/roadmap.md`, and tests under `test/seon/render/live_tile_test.cljs` [V `git show --stat 4a309f5ff`].
- The rule in that commit, quoted from the deleted skill: "Unpinned, your canvas is DERIVED — it already follows you. With no `:seon.render.live-tile/content` stored, the canvas shows your **last-updated tile fn**: among the tile fns YOU authored (output declares `:seon.render/hiccup`), the one most recently touched" [V `git show f23a2aa4b^:.claude/skills/ui-live-tiles/SKILL.md`, section "The one move"]. This is the owner's "most recent one is the primary tile" rule, already designed. Pinning overrides recency [V same section].
- The deleted skill also documents the interactive `my.tile` controls (`my.tile/button`, `my.tile/input`, `my.tile/form`) whose actions must be a fn symbol in the agent's own namespace, and the `my.ui` / `my.data` toolkit [V same file, sections "Interactive controls" and "Aggregate before you show"]. Those are pod-era APIs; none exists in `src/my/` today: `src/my/` has agent, background, edit, fs, message, program, root, shell, task, test, turn, web [V `ls src/my/`].
- Commit `f23a2aa4b` (2026-07-13) "docs(runtime): align active guidance with server and pod" deleted `.claude/skills/ui-live-tiles/SKILL.md` (433 lines) and replaced it with `.claude/skills/ui-canvas/SKILL.md` (182 lines) [V `git show --stat f23a2aa4b`]. Note the commit's own file list also touched `docs/seon/components/web-ui.md`, which no longer exists; the current page is `docs/seon/architecture/ui.md` [V `ls docs/seon/`].

**Current specs**

- `docs/seon/architecture/ui.md`, section "1. One walk, explicit output": "A block is an entity rendered through its schema's AI/HTML pair. Scalars remain in the entity's block; components and declared derived queries supply related blocks." [V]. The current unit is a **block**, not a tile.
- Same file, section "1": "A generalized agent-authored canvas remains a separate contract; ordinary namespace pages do not imply arbitrary form/control execution semantics." [V]
- Same file, section "2. Whole-view delivery": the whole view is one patch; a refresh notification is payload-free and the tab re-reads one database value [V]. This is the delivery model a tile feed would use.
- `.claude/skills/ui-canvas/SKILL.md`, section "Inspect before inventing an API": "Do not present proposed `my.canvas` constructors as installed APIs." It states the route table has no generic `/call` route [V]. It also says a future action "returns declared data interpreted by the existing turn/effect owner. Agent code never receives an SSE connection." [V]
- `.claude/skills/datastar-web-ui/SKILL.md` frontmatter: "generalized canvas design has a separate contract" [V].
- `docs/prds/sci-execution-runtime/plan/whole-system-arc-2026-08-08.md`, "Stage 2 — canvas centerpiece": "One agent builds an interactive `my.canvas` surface (a small live dashboard over its own namespace's facts — buttons/inputs that round-trip through messages). The human interacts with it; the agent responds in a subsequent turn." [V]. Its drive report `docs/prds/sci-execution-runtime/research/whole-system-arc-drive-2026-08-08.md` line 83: "Interactive `my.canvas` surface | **CANNOT RUN** — no canvas mechanism exists in the tree"; line 491: "Reclassify Stage 2 as unbuilt until a canvas mechanism exists" [V]. That is still the state.

**Built**

- `seon.render.web` (`src/seon/render/web.clj`) renders the agent page as a shell with surfaces, each placed at `seon.render.block/surface-id`; a feed `/feed/{id}` pushes whole views over SSE [V `src/seon/render/web.clj` `shell` docstring, `feed` at ~2631; `src/seon/render/route.clj` lines 25-26].
- `seon.render.web/message-bar-html` (~line 302) is the constant message surface with a Datastar form that posts to `::route/agent-message` [V].
- `seon.render.web/feed` writes packages to the browser; `join-package` / `write-package!` are the delivery path ui-canvas says to reuse [V ui-canvas SKILL.md].
- Render pairs: `:seon.render/html` on an entity schema, selected through `src/seon/render/walk.clj` and `src/seon/render/value.clj`. A render returns hiccup or data; the value printer applies limits [V ui.md section 1; ui-canvas SKILL.md].
- Debug graph: `seon.render.web` line ~876 `seon-debug-graph-canvas` with `resources/public/js/seon-graph.js` (cytoscape-style reconcile of a model) [V]. It is a debug view of the namespace/agent graph, not a data render.

**Missing**

- No tile or canvas attribute, no "most recent render" rule, no pin.
- `src/seon/render/` has no `canvas.clj`, `live_tile`, or `tile` namespace [V `ls src/seon/render/`]. `resources/public/css/input.css` line 23-24 and 44-48 and 464 still refer to "the seon.render.canvas ns docstring" and "my.canvas's class strings", and "canvas_test asserts the pairing" [V]. Those comments point at code that does not exist: stale.

**Conflicts and stale docs**

- `.claude/skills/ui-canvas/SKILL.md` (current) and the memory note `feedback_canvas_is_default_interactive_surface.md` (2026-07-13, "canvas is THE default surface") disagree in emphasis. The skill says generalized canvases need a separate contract and must not be presented as installed. The memory says agents should reach for interactive canvases by default. [V both]. Owner decision needed on which one is the rule now [I].
- The memory index `MEMORY.md` says "Canvas = default interactive surface... See the `ui-live-tiles` skill." That skill was deleted in `f23a2aa4b` [V]. Stale pointer.
- `docs/seon/architecture/vocabulary.md` lists "tile" as a retired spelling of canvas and of surface/card [V]. The owner's words "tile" and "primary tile" therefore must be recorded in vocabulary before they appear in code or tasks (CLAUDE.md "Words and where they come from"). Root should decide whether "tile" is a new word for a render unit, or the word stays "block" (existing in ui.md) with "canvas" for the focal one.
- Memory names the attribute `:seon.render.canvas/content`; the deleted skill names `:seon.render.live-tile/content`. Neither is declared [V].

## 2. How an agent shows HTML today, and whether a render can take input

- An agent does not author HTML in its REPL today. Its HTML is what the schema-declared `:seon.render/html` pair produces for an entity [V ui.md section 1]. Namespace and agent pages are the only HTML surfaces [V `docs/seon/architecture/vocabulary.md`, row "web UI": "`/`, `/ns/{namespace}`, `/agent/{id}`, their debug routes, and `/data`"].
- Routes [V `src/seon/render/route.clj`, lines 7-32]: `/`, `/ns/{namespace}`, `/ns/{namespace}/debug`, `/agent/{id}`, `/agent/{id}/debug`, `POST /agent/{id}/message`, `POST /agent/{id}/context`, `/health`, `/feed/{id}`, `/data`, `/css/`, `/js/`. No canvas, call or action route.
- Port: `http://127.0.0.1:7994` appears only in `src-inspect-ai/tests/test_cluster.py` as the Python test client's cluster URL [V]. The web port is set by configuration; I did not verify which port the running default serves. [I]
- Can a render be an input that writes data back? Only in one hard-wired case. The message bar is a form whose submit calls `inbound` [V `message-bar-html`, `route::agent-message` -> `inbound-response` (`src/seon/render/web.clj` ~3216) -> `inbound` (~2829)]. Any other render-as-input would need a declared action that the turn/effect owner interprets [V ui-canvas SKILL.md]. That design is not written.
- `src/seon/render/web.clj` `inbound` writes through the message owner `message/inbound-tx` (`src/seon/cluster/message.clj`, `inbound-tx` at line 151) and commits with `db/transact!` [V]. `inbound-tx-meta` records `:seon.db/user` only when the sender id is an agent [V line 2823-2827]. So a human sender is not an agent unless the id matches one. [I: the human has no agent identity today]

## 3. Agent and user messaging: wake, reply, answer

- `my.message/send` (`src/my/message.clj` line 48; schema `:my.message/send-request` in `resources/seon/schemas/my.message.edn`) sends from the agent to `:my.message/to`; the write is picked up by `db<->flow` ("without a graph ... is hosted by db<->flow's next pass", `src/my/message.clj` line 54) [V].
- `my.turn/wait` (`src/my/turn.clj` line 5) returns `{:my.turn/disposition :wait :my.turn/note …}` [V].
- `my.message/inbox` (line 6) and `my.message/read` (line 21) read inbound messages [V].
- Wake: a message is a wake when its attribute is listened. The live proof record `docs/prds/steward-platform/research/message-wake-live-proof-2026-09-17.json` shows `:seon.wake/attribute :seon.message/to` [V]. `seon.turn/unanswered-wakes` (`src/seon/turn.clj` ~2374) derives answeredness purely from `:t`: a wake is answered when an answering turn of that agent has `:t` at or after it; nothing is stored [V docstring]. `unanswered-triggers` is the message projection (~2427) [V].
- Reply linkage: `:seon.message/caused-by` is named in the steward plan as the thread [V `docs/prds/steward-platform/plan/namespace-agents-plan-2026-09-19.md` line 44]. The message bar sends no reply-to or caused-by reference [V message-bar-html]. [I: a reply box for a specific question needs that reference, or a keyed `:seon.message/about`.] `my.message/send` has an `:my.message/about` slot and an `:my.message/assignment` slot [V `resources/seon/schemas/my.message.edn`].
- `my.agent/answer!`: NOT implemented. `src/my/agent.clj` has no `answer!` [V rg]. It is a TARGET in `docs/seon/specs/agent-loop.md` (line ~73): "The task declares its answer's Malli schema, and `my.agent/answer!` validates the answer against it and stores it as ordinary Datahike data". The agent-loop spec line ~515 says `:seon.agent/answered-t` is "[TARGET] (not yet declared)" [V].
- Question tasks with an answer schema: target only, same spec [V]. No `:seon.task/answer` key is in `resources/seon/schemas/seon.task.edn` [V grep for answer/question]. `seon.task.edn` has "assignment-view", "faults", "git-commit", "needs", "execution" keys [V].
- The user as a party: there is no user entity or user address in the current schemas. The inbox is keyed by agent id [V `my.message.edn` `:my.message/inbox-request` requires `:seon.agent/id`]. The steward plan's D1 "chatting task" is planned, not built: "Chatting task: one persistent entity, repeated sessions; test = no inbound message from the user newer than my newest reply to them ... planned (slice 3)" [V `docs/prds/steward-platform/plan/README.md` line 315].

**Approval gating (step stays not-done until the user approves)**

- Existing mechanism: a task completes only when its proofs pass. Proofs are deftests carrying `ns/name` in `:seon/tasks` [V `docs/seon/specs/agent-loop.md` line ~70-73, and CLAUDE.md "How to work" item 3].
- Designed but not built: "A task whose proofs read an answer completes when they pass on the agent's answer; nothing merges" [V agent-loop.md ~73]. This is the right approval gate: the user's approval is the answer, stored by `my.agent/answer!`, and a proof reads it.
- Conflict: `docs/research/agent-platform/task-scenarios-2026-09-24.md` section 1 says of a reply: "Whether it answers cannot be decided by a query or test; the honest exits are user acknowledgement on the chain or a root decision. The draft's 'no reply = a red `done-when` test' ... is not a `deftest`" [V]. The agent-loop design says proofs are deftests. These are in tension for approval. The answer-proof design resolves it: the approval becomes data that a deftest reads. [I] Root should confirm.

## 4. The workout example

- Planning scenario only: `docs/research/agent-platform/task-scenarios-2026-09-24.md` section 2 ("Build me a workout tracker: decomposition"). Children: `workout/schema`, `workout/log`, `workout/summary`, `workout/page` with `:delivers #{my.workout/render-entry-ai my.workout/render-entry-html}` [V]. No graph is named. "Fails today: `prepare-merge!` refuses additions" [V]. This is a September design and predates the task-chain spec.
- No workout PRD in `docs/prds/`: `ls docs/prds/` shows agent-platform, archive, context-generation, next-turn, steward-platform [V]. `docs/prds/next-turn/` contains only research on context and token-level prediction, no UI [V].
- Graph render of data: nothing for user data. The only graph is the debug one (section 1).

## 5. What exists and works, what is designed, what is missing

| Piece | Status | Where |
|---|---|---|
| Agent page with transcript and message bar | Built [V] | `seon.render.web/message-bar-html`, `shell`; route `/agent/{id}` |
| Message POST to agent (commits a message fact) | Built [V]; live wake of the human's message: [I] verified only by the 2026-09-17 probe record | `seon.render.web/inbound`, `seon.cluster.message/inbound-tx` |
| Agent sends a message | Built [V] | `my.message/send` |
| Wake and answeredness by `:t` | Built [V] | `seon.turn/unanswered-wakes` |
| Whole-view delivery over SSE | Built [V] | `seon.render.web/feed`; ui.md section 2 |
| Block render pairs per entity | Built [V] | ui.md section 1; `seon.render.walk` |
| Debug graph | Built, debug only [V] | `seon.render.web` ~876; `resources/public/js/seon-graph.js` |
| Primary tile = most recent render (derived), pin | Designed in pod era; deleted with the pod [V] | commit `4a309f5ff`; deleted skill `f23a2aa4b` |
| Canvas attribute `:seon.render.canvas/content` | Target only [V] | vocabulary.md; arc drive report |
| Interactive `my.canvas` / `my.tile` controls | Target / deleted [V] | ui-canvas SKILL.md; arc report line 83 |
| Reply box bound to a message (caused-by / about) | Not designed in code; thread concept in steward plan [V] | steward plan line 44 |
| Question task with answer schema; `my.agent/answer!` | Target only [V] | agent-loop.md ~73 |
| `:seon.agent/answered-t` | Target only [V] | agent-loop.md ~515 |
| Approval gate via answer-reading proof | Designed as the agent-loop rule; not built [V] | agent-loop.md ~73 |
| User as an address or agent | Not designed; D1 planned [V] | steward plan README line 315 |
| Graph render of user data | Not designed [V] | none |
| Workout example | Scenario only [V] | task-scenarios section 2 |

## 6. Smallest path (for root to decide)

Goal: user types a request -> task created -> agent asks a question rendered as a chat tile -> user replies in that tile -> reply is a message fact that wakes the agent -> step advances only when a proof sees approval.

Steps, in order, each with a red proof:

1. **Word first.** Record "tile" (or the owner's chosen word) in `docs/seon/architecture/vocabulary.md` before using it. Retire the stale `ui-live-tiles` pointer in memory and the `seon.render.canvas` comments in `resources/public/css/input.css`. [V these exist; a documentation-only change]
2. **User request as a fact.** The existing message bar already commits a message to the agent (`seon.render.web/inbound`). Proof: a POST to `/agent/{id}/message` creates a `:seon.message/id` fact whose `:seon.message/to` is the agent, and `seon.turn/unanswered-wakes` lists it. [Existing path; needs one test through the route if none exists. Not checked: whether such a test exists.]
3. **Task from the request.** A task whose proofs are deftests tagged `:seon/tasks`. Creation is `seon.task` admission (assignment refuses a task with no proofs or all-green proofs, agent-loop.md). [Existing; the question task is the new part.]
4. **Question task with answer schema.** Add the `:seon.task/answer` schema key and `my.agent/answer!` (agent-loop.md ~73). Proof: `my.agent/answer!` with a value that does not match the schema is refused as data; a valid answer is stored as a fact on the task. [Target code, not built.]
5. **Chat render of the question.** A `:seon.render/html` pair on the question's message entity: text bubble, plus a `form` that posts to `/agent/{id}/message` with `:seon.message/about` (or a new `:seon.message/caused-by`) set to the question's id. This reuses `message-bar-html`'s form and `inbound`. Proof: the rendered question contains a form whose post carries the question's id, and the posted reply is a `:seon.message/caused-by` fact on the question. [Needs one new optional slot on `inbound`.]
6. **Reply wakes the agent.** Existing wake path: `:seon.message/to` is listened (probe record). Proof: the reply creates an unanswered wake for the agent (`unanswered-wakes` non-empty). [Existing.]
7. **Approval gate.** The question task's proof is a deftest that reads the stored answer (or the reply's content) and asserts approval. The step completes only when that test is green. Proof: red while no approval, green after the user's reply says approve; the task stays open otherwise. [Needs step 4 and a test; the task-scenarios doc's "user acknowledgement is an exit" conflicts with this and must be decided.]
8. **Primary tile.** Derived rule from commit `4a309f5ff`: the most recently changed render of the agent's own entities is shown first on `/agent/{id}`. Proof: after a write to a render's source attribute, that render is the first block. [New; the rule is already owner-approved in memory.]
9. **Graph render of data.** A render that returns a graph view of a query result. Smallest: a render function over the workout entries returning an inline SVG or a table; no new JS. [Not designed. Needs its own contract under ui-canvas.]

Explicit non-goals for this path: a generic `/call` route, arbitrary agent-authored hiccup with actions, a `my.canvas` API. ui-canvas says these need a separate contract.

## 7. Questions for root (decide, do not block)

1. Is "tile" a new word? Vocabulary currently retires it. Recommended: keep "block" for render units, use "canvas" for the focal one, unless the owner insists on "tile".
2. Is the approval gate an answer-reading proof (agent-loop.md ~73) or a user-acknowledgement exit (task-scenarios section 1)? Recommended: the answer-reading proof, so every step's completion is a test.
3. Does a reply box need `:seon.message/caused-by` or `:seon.message/about` to address a question? Recommended: `caused-by`, already named in the steward plan.
4. Which web port serves the running default, and does a live probe confirm a user POST wakes the agent? Not checked in this session.
