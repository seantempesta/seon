---
type: research
status: vocabulary survey for the owner; no source changed
created: 2026-09-27
branch: refactor/agent-platform
source-pin: ca19ad74b (checkout)
lane: agent-session-vocabulary-opus
---

# What other agent systems call session, turn, run, evaluation, compaction, message and task

The owner asked what other agentic systems call seven Seon concepts. This note surveys eleven systems from
their official documentation (fetched 2026-09-27), gives each cell a short definition with its citation, and
recommends a Seon name per concept under the "Use grounded vocabulary" law (Clojure's term, then the
dependency's, then a justified new term; [vocabulary](../../seon/architecture/vocabulary.md)). Read alongside
[the session entity design](repl-session-entity-design-2026-09-27.md) and
[the session prior art](repl-session-prior-art-2026-09-27.md).

The seven concepts, as the owner framed them:

1. **Session**: an agent's ongoing, possibly unending work context; REPL-like, with a current namespace.
2. **Turn**: one model → response → evaluation cycle.
3. **Run**: a budgeted invocation of N turns within a session; a session may last any number of runs.
4. **Evaluation (step?)**: the individual forms or tool calls within a turn.
5. **Compaction**: shrinking the session's history while keeping the rest.
6. **Message**: inter-agent message.
7. **Task**: the goal or unit of work.

## 1. The table

`—` means the system has no first-class name for the concept. Definitions and citations follow in §2.

| System | Session | Turn | Run (budget of turns) | Evaluation / step | Compaction | Message | Task |
|---|---|---|---|---|---|---|---|
| OpenAI Agents SDK | `Session` | turn (`max_turns`) | run (`Runner.run`, `RunResult`) | item (`RunItem`) / tool call | `OpenAIResponsesCompactionSession`, `run_compaction()` | message item | — (the run's input) |
| OpenAI Assistants (sunset 2026-08-26) → Responses/Conversations | Thread → Conversation | — | Run → Response | Run step → Item | server-side compaction, `/responses/compact` | Message → Item | — |
| Claude Agent SDK / Claude Code | session (`session_id`) | turn (`max_turns`, `num_turns`) | one `query()` loop to its `ResultMessage` | tool call (`ToolUseBlock`) | compaction (`/compact`, `compact_boundary`, `PreCompact`) | message (`SDKMessage` stream) | task (`TaskCreate`/`TaskUpdate`) |
| LangGraph (+ LangSmith deployment) | thread (`thread_id`) with checkpoints | — | run (on a thread); `recursion_limit` per execution | super-step (`langgraph_step`) / node | trim / delete / summarize messages | message (`MessagesState`) | — |
| AutoGen AgentChat | team state (resume vs `reset()`) | turn (`max_turns`) | run (`run(task=…)` → `TaskResult`) | — | — | message (`TextMessage` etc.) | task |
| AG2 | chat / conversation | turn (`max_turns`) = both agents speak; round (`max_round`) = one speaker | `initiate_chat` → `ChatResult` | step (one speaker in a turn) | `summary_method` (chat summary) | message | — |
| CrewAI | agent memory | iteration (`max_iter`) | `kickoff()` | — | `respect_context_window` summarization | — | `Task` (description, expected_output) |
| Letta (MemGPT) | agent state; conversation | step (one LLM inference) | run (`max_steps`, `stop_reason`) | tool call inside a step | compaction (summarization, `sliding_window`) | message | — |
| Inspect AI | sample (`AgentState`, `TaskState`) | turn (`turn_limit`) | sample under limits; epoch repeats it | tool call | `CompactionEdit`, `CompactionSummary`, `model.compact()` | `ChatMessage` | `Task` (an evaluation recipe) |
| Google ADK | `Session` (+ `State`, `Memory`) | — | invocation (`invocation_id`); `max_llm_calls` per run | event (`Event`) | events compaction (`EventsCompactionConfig`) | event / content | — |
| A2A protocol | context (`contextId`) | a Message is "a single turn" | — | — | — | `Message` (with `Part`s) | `Task` (with lifecycle) |
| nREPL | session (`clone`, `close`, `ls-sessions`) | — | — | request with `:op "eval"`, `:id`, `status done` | — | message (request/response map) | — |

## 2. Cell definitions and citations

### OpenAI Agents SDK

- **Run, turn.** `Runner.run` runs the agent loop; the `max_turns` docstring defines a turn as "one AI
  invocation (including any tool calls that might occur)"; exceeding it raises `MaxTurnsExceeded`, which the
  Exceptions section scopes to "the agent's run". The run returns `RunResult`.
  <https://openai.github.io/openai-agents-python/ref/run/> (`Runner.run`, parameter `max_turns`);
  <https://openai.github.io/openai-agents-python/running_agents/> ("The agent loop", "Exceptions").
- **The word "turn" is used twice.** The same page says one run call "represents a single logical turn in a chat
  conversation" (section "Conversations/chat threads"). The counted, budgeted unit is the per-invocation turn;
  the conversational sense is prose only.
- **Session.** Sessions keep conversation history "across multiple agent runs": history is loaded before each
  run and new items (user input, responses, tool calls) are stored after it.
  <https://openai.github.io/openai-agents-python/sessions/>.
- **Compaction.** `OpenAIResponsesCompactionSession` compacts stored history after a turn through the Responses
  API's `responses.compact`; `run_compaction()` does it manually. Same page.

### OpenAI Assistants API → Responses and Conversations

- The Assistants API was deprecated in August 2025 and sunset on 2026-08-26
  (<https://developers.openai.com/api/docs/deprecations>). The migration table maps
  Threads → Conversations, Runs → Responses, Run steps → Items. Items are "messages, tool calls, outputs, and
  more". <https://developers.openai.com/api/docs/assistants/migration> (mapping table).
- **Conversation.** A long-running object with its own durable id, reusable "across sessions, devices, or jobs";
  items include messages, tool calls and tool outputs. `previous_response_id` chains responses instead.
  <https://developers.openai.com/api/docs/guides/conversation-state> ("Using the Conversations API").
- **Compaction.** "reduce context size while preserving state needed for subsequent turns". Server-side mode
  triggers at a token threshold. A standalone `/responses/compact` endpoint returns a new window. The compaction
  item is opaque, and retained items may accompany it.
  <https://developers.openai.com/api/docs/guides/compaction>.

### Anthropic Claude Agent SDK / Claude Code

<https://code.claude.com/docs/en/agent-sdk/agent-loop>:

- **Turn.** "A turn is one round trip inside the loop": Claude's output with tool calls, their execution and
  the results fed back (section "Turns and messages"). `max_turns` / `maxTurns` is the "Maximum tool-use round
  trips" (section "Turns and budget"). Every result carries `num_turns`; hitting the cap ends with result subtype
  `error_max_turns` (section "Handle the result").
- **Run.** There is no noun. One `query()` loop runs from the prompt to a `ResultMessage`. With streaming input,
  a queued message starts a new loop and the max-turns count starts over, while `maxBudgetUsd` accumulates
  across messages ("Turns and budget"). So the budgeted unit is per prompt, inside a session.
- **Session.** "Each interaction with the SDK creates or continues a session", identified by `session_id` and
  resumable or forkable (section "Sessions and continuity").
- **Compaction.** Near the context limit the SDK "summarizes older history". It emits a system message with
  subtype `compact_boundary`, fires a `PreCompact` hook (trigger `manual` or `auto`), and runs manually on `/compact`
  ("Automatic compaction").
- **Message, task.** Every stage is an `SDKMessage` (`SystemMessage`, `AssistantMessage`, `UserMessage`,
  `ResultMessage`, `StreamEvent`). Task tracking uses the built-in tools `TaskCreate` / `TaskUpdate`
  ("Message types", "Built-in tools").

### LangGraph

- **Super-step, recursion limit.** "A super-step can be considered a single iteration over the graph nodes".
  `recursion_limit` is "the maximum number of super-steps the graph can execute during a single execution".
  The default is 1000 since 1.0.6. The current step is `config["metadata"]["langgraph_step"]`, and overflow
  raises `GraphRecursionError`. <https://docs.langchain.com/oss/python/langgraph/graph-api>.
- **Thread, checkpoint.** Checkpointers persist a thread's graph state as checkpoints under `thread_id`.
  <https://docs.langchain.com/oss/python/langgraph/persistence>.
- **Run.** A thread is a "persistent conversation container that maintains state across multiple runs". A run
  combines an assistant and a thread. <https://docs.langchain.com/langsmith/use-threads>.
- **Compaction.** Short-term memory is "thread-level persistence". Long histories are handled by trimming,
  deleting (`RemoveMessage`) or summarizing (`SummarizationNode`).
  <https://docs.langchain.com/oss/python/langgraph/add-memory>.

### AutoGen AgentChat (Microsoft)

- **Task, run, turn.** A team is run with a task (`run(task=…)` / `run_stream`) until a termination condition
  holds and returns `TaskResult` with a `stop_reason`. In `RoundRobinGroupChat` agents take turns, and a later
  run resumes "from the next agent" unless `reset()` clears the state.
  <https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/tutorial/teams.html>.
- `max_turns`: "The maximum number of turns in the group chat before stopping."
  <https://microsoft.github.io/autogen/stable/reference/python/autogen_agentchat.teams.html> (`RoundRobinGroupChat`).

### AG2 (the AutoGen 0.2 line)

- **Turn and round are different units.** For `initiate_chat` `max_turns`, "Each turn represents a round trip of
  both agents speaking". For `GroupChat` `max_round`, "Each round represents an agent speaking". There is also
  `max_consecutive_auto_reply`.
  <https://docs.ag2.ai/latest/docs/user-guide/advanced-concepts/orchestration/ending-a-chat/>.
- The call returns a `ChatResult` whose `summary_method` produces a chat summary.
  <https://docs.ag2.ai/latest/docs/api-reference/autogen/ConversableAgent/>.

### CrewAI

- `max_iter` is the "Maximum iterations before the agent must provide its best answer" (default 20).
  `respect_context_window` summarizes history to fit the window. `kickoff()` runs an agent or crew.
  <https://docs.crewai.com/en/concepts/agents>.
- A Task has a description, an expected output and an assigned agent. A crew runs its tasks sequentially or
  hierarchically. <https://docs.crewai.com/en/concepts/tasks>.

### Letta (MemGPT)

- **Step, run.** A step is "a single pass of LLM inference". A run is "a single invocation of an agent" that may
  contain many steps. <https://docs.letta.com/guides/agents/overview>. A message create call takes `max_steps`,
  and the run's `stop_reason` includes `max_steps` and `end_turn`.
  <https://docs.letta.com/api/resources/steps>; <https://docs.letta.com/api/typescript/resources/runs>.
- **Memory.** Memory blocks are editable context strings. Core memory is pinned into the context window.
  Archival memory is an external, searchable store. All messages persist in the database even after eviction
  from context. <https://docs.letta.com/guides/agents/memory>.
- **Compaction.** "older messages are summarized to make room for new ones". The modes are `sliding_window`
  (default `sliding_window_percentage` 0.3), `all` and self-compacting variants.
  <https://docs.letta.com/v1-sdk/messages/compaction>.

### Inspect AI (UK AISI)

- **Turn.** "A 'turn' is a single model generation (one call to the model that produces an assistant
  message)". It is distinct from message limits, recorded once per top-level `generate()`, and `model.compact()`
  generations do not count. `turn_limit` works per sample or as a scoped block (with `suspend_turn_limit()`).
  Each `EvalSample` records `turn_count`. <https://inspect.aisi.org.uk/setting-limits.html> ("Turn Limit").
- **Sample, epoch, task.** A Task is "a recipe for an evaluation" (dataset, solver, scorer). A sample is one item
  executed under the limits, and epochs repeat each sample. <https://inspect.aisi.org.uk/tasks.html>.
  `AgentState` holds `messages` ("Conversation history") and `output`.
  <https://inspect.aisi.org.uk/agent-custom.html>.
- **Compaction.** The ReAct agent offers `CompactionEdit(keep_tool_uses=…)` and `CompactionSummary(threshold=…)`.
  These affect only the model's input, and the full history is retained.
  <https://inspect.aisi.org.uk/react-agent.html>.

### Google ADK

- **Session, state, memory.** A Session is a "single, ongoing interaction" holding the chronological events.
  State is data within that session. Memory spans "multiple past sessions".
  <https://adk.dev/sessions/>.
- **Invocation, event.** An invocation is everything done for one user query, and its `invocation_id` ties
  together agent runs, LLM calls and tool executions. An event is "the message passed back and forth between the
  Runner and the Execution Logic". <https://adk.dev/runtime/event-loop/>.
- **Budget.** `RunConfig.max_llm_calls` "Caps the total number of LLM calls per run" (default 500).
  <https://adk.dev/runtime/runconfig/>.
- **Compaction.** `EventsCompactionConfig` has `compaction_interval`, `overlap_size`, `token_threshold` and
  `event_retention_size`, and uses an LLM summarizer. <https://adk.dev/context/compaction/>.

### A2A protocol (agent-to-agent)

- A Task is "A stateful unit of work" with an id and a lifecycle. A Message is "A single turn of communication"
  made of Parts. Artifacts are outputs. `contextId` groups related tasks.
  <https://a2a-protocol.org/latest/topics/key-concepts/>.

### nREPL (the Clojure side)

- Messages are EDN maps. Each request names an `:op` (such as `eval`) and an `:id`, and responses carry
  `:session`, `:id` and a final `status` of `done`. <https://nrepl.org/nrepl/design/overview.html>
  ("Messages", "Requests", "Responses").
- A session keeps dynamic bindings between requests. `clone` creates one, `close` ends it, `ls-sessions` lists
  them, and `interrupt` stops an executing request. <https://nrepl.org/nrepl/ops.html>. The session map holds
  `*ns*`, the current namespace ([prior art](repl-session-prior-art-2026-09-27.md) §nREPL,
  `session.clj:201-215`).

## 3. Where the field agrees and where it splits

- **The budgeted unit is per run, and it counts model calls.** OpenAI Agents SDK `max_turns` per `Runner.run`,
  Claude `max_turns` per `query()` loop (reset per queued message), Letta `max_steps` per run, ADK
  `max_llm_calls` per run, LangGraph `recursion_limit` per execution and Inspect `turn_limit` per sample all
  count calls inside one invocation. The container around the runs (session, thread, conversation) is unbounded.
  This is exactly the owner's "a budget of turns, a run, within a session which can last infinite runs".
- **"Turn" means one model call plus its tool results** in the three systems that define it precisely and budget
  by it: OpenAI Agents SDK, Claude Agent SDK and Inspect. Two looser senses exist. A whole user exchange is a
  turn in OpenAI's prose and A2A's Message definition, and in AG2 a turn is both agents speaking. Seon's turn
  matches the precise, budgeted sense.
- **"Step" collides everywhere.** Letta's step is a model call (Seon's turn). LangGraph's super-step is a graph
  iteration. OpenAI's run step became an item, and AG2's step is one speaker. Seon already uses step for the
  core.async.flow `step-fn` and the turn loop's proc (`src/seon/turn.clj:4933`, vocabulary row "proc, step-fn").
- **Session is the majority name** (OpenAI Agents SDK, Claude, ADK, nREPL). Thread (LangGraph, legacy
  Assistants) and conversation (OpenAI Responses, AG2, Letta) are the alternatives. Only nREPL's session holds a
  current namespace, which is the property the owner's definition names.
- **Compaction is the universal name.** Every system surveyed except nREPL and A2A either calls it compaction or
  describes it as summarizing older history. Most summarize, and several keep the originals retrievable (Letta,
  Inspect, OpenAI retained items).
- **Message and task** are near-universal nouns. Inspect's Task is the one conflicting use: there it is an
  evaluation recipe, not a unit of agent work.

## 4. Recommendation for Seon

| Concept | Name | Grounding | Storage (per the data-modeling and datahike skills) |
|---|---|---|---|
| Session | **session** | Clojure's term first: nREPL's session holds `*ns*` between requests. It is also the majority agent-SDK term. | The agent's one owned session value: a component of the agent row holding `:seon.agent.sci/ns` ([design](repl-session-entity-design-2026-09-27.md) §1). See §4.1. |
| Turn | **turn** (keep) | The owner's preference; matches the budgeted sense in the OpenAI Agents SDK, Claude SDK and Inspect | `:seon.turn` entity, unchanged |
| Run | **run** = the turns spent against one budget, from its anchor wake until the reply or the cap | OpenAI Agents SDK run / `max_turns`; Letta run / `max_steps`; ADK `max_llm_calls` per run; LangGraph run on a thread | Derived, not stored (see §4.2). Budget dial `:seon.config.run/max-turns`. |
| Evaluation | **evaluation** (keep; not "step") | Clojure's read-eval-print; nREPL `:op "eval"`; already `:seon.eval` | `:seon.eval` per branch/turn/ordinal, unchanged |
| Compaction | **compaction** (keep) | Field-wide name | Retract evaluations; regenerate the opening (vocabulary row "compaction") |
| Message | **message** (keep) | A2A, AutoGen, nREPL | `seon.message`, unchanged |
| Task | **task** (keep) | A2A Task, AutoGen `run(task=…)`, CrewAI Task, Claude `TaskCreate` | `seon.task` (vocabulary row "task") |

### 4.1 Session: the stored name

The session design renames `:seon.agent/runtime` to `:seon.agent/sci`, "the agent's SCI world". SCI's own noun
is *context*, the live forked object (`sci/fork`, vocabulary row "base SCI context / agent SCI context"). That
object is not stored and does not survive restart. What is stored is its REPL state, and today that is only the
current namespace. nREPL names exactly this state a session.

If the owner adopts "session" for the concept, name the stored component after it: `:seon.agent/session`, with
`:seon.agent.session/ns`. Keep "agent SCI context" for the live object. The concept, the attribute and nREPL
then share one name, and "SCI context" no longer names two things.

Modeling stays as designed. The session belongs to one agent's value, so it is a component, which cascades with
the agent and expands in pull (data-modeling skill, "Choose components when children belong to one parent's
value"; datahike skill, "A component is part of its parent's value", `pull_api.cljc:346-351`,
`db/transaction.cljc:832-836`). It carries no identity attribute of its own. Its owning value is validated
through `:seon.db/component-schema` (datahike skill, `write-owned-values-error`).

Because a Seon agent never starts a second session, "session" and "agent" are one-to-one. Do not introduce a
separate session entity with its own identity or a ref back to the agent. `:agent/_session` answers the reverse
edge (datahike skill, "Derive an owned child's parent from the forward edge").

### 4.2 Run: the owner's term against today's code

This is the substantive collision. Today's code uses three names for two things:

- **The owner's turn is today's `:seon.turn` entity, but the code also calls it a "run".** The turn's render
  pair is `seon.render.transcript/render-run-ai` / `render-run-html` (`resources/seon/schemas/seon.turn.edn:5-6`,
  `src/seon/render/transcript.clj:782`, `:898`). The session design writes "runs" throughout for turns (for
  example "Runs point to the agent", §1). The vocabulary already lists `run` and `seon.cluster.run` as rejected
  alternatives for turn (row "turn"). These are residue of that retired name and should become "turn".
- **The owner's run is today's "episode", also called a "session bound".** `:seon.config.run/max-episode-runs`
  (`resources/seon/schemas/seon.config.run.edn:1`) counts turns since the task assignment or the latest outside
  wake (`episode-runs`, `src/seon/turn.clj:2855`). On exhaustion `episode-exhausted!` stores the fault
  (`:5571`). `turns-left` calls the same bound "the same session bound" (`:2892`). Rename toward the field's
  terms: the dial becomes `:seon.config.run/max-turns`, whose namespace `seon.config.run` already says run.
  `:seon.turn.work/episode-runs` becomes a run's `turns` count; `max-episode-runs`, `episode-runs` and
  `episode-exhausted!` become `max-turns`, `run-turns` and `run-exhausted!`. Retire "episode" and "session bound".

A run is derived, not stored. Its boundary is already a query: turns closed after the anchor (task assignment,
or the latest outside wake). The anchor is itself a positive fact; a task's budget is "total across resumes",
and messages "do not refill" it (`episode-runs` docstring). The data-modeling skill says "Derived concerns are
queries, not stored mirrors". A `:seon.run` entity, or a turn → run ref, would mirror what the anchor and the
turns' `:seon.turn/closed-tx` already establish.

The one event a run adds is its exhaustion, and that is already stored as a fault. If a run entity is ever
needed, for example to carry a stop reason like Letta's `stop_reason` or Claude's `error_max_turns`, it must be
justified by an observation that no existing fact records (data-modeling skill, "An event fact proves exactly the
observation its writer completed").

### 4.3 Other notes

- **Avoid "step"** for evaluations. It would collide with Seon's Flow `step-fn` and with Letta's meaning (a model
  call, which is Seon's turn).
- **Compaction keeps its name, but Seon's mechanism differs from the field.** Seon writes no summary. It
  retracts the evaluations, and the next system turn regenerates the opening from current facts. That is closer
  to Inspect's `CompactionEdit` or LangGraph's delete-messages than to a summarizer. Retracted evaluations stay
  readable through Datahike history while `keep-history?` holds (datahike skill, "Retraction preserves temporal
  evidence only with `keep-history?`"). That is Seon's counterpart to Letta and Inspect keeping the full history.
- **"Result"** follows the OpenAI Agents SDK `RunResult` and Claude `ResultMessage` pattern. Seon's
  `:seon.turn/result` is per turn, which is consistent: a run's result is its last turn's.
- **Where to record the ruling.** Once the owner rules, correct the vocabulary rows "turn" (add "episode" and
  "session bound" to the rejected alternatives for run), "compaction" and "base SCI context / agent SCI context".
  Add rows for session and run in the same commit, per the vocabulary law.
