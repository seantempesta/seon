---
type: research
status: completed
tags: [research, agent, comparison]
---

# Hermes Agent vs Seon — architectural comparison (2026-05-25)

## TL;DR

Hermes and Seon are both "personal AI agent" projects with substantial overlap in user-visible goals (long-lived agent, cross-session memory, sandboxed code execution, scheduling, multi-platform reach) but they disagree at the foundation in one decisive way: **Hermes treats the agent as an LLM driver over a fixed catalog of curated capabilities; Seon treats the agent as a Clojure programmer of a live runtime.** Hermes' "self-improving" loop is procedural-memory accumulation (write a `SKILL.md`, edit `MEMORY.md`). Seon's analogue is the same source corpus that defines the substrate — the agent edits the running program, which is the database, which is the source. Almost every other difference falls out of that one.

This document compares the two on architecture, the learning/skill loop, productization, sandboxing, language choice, and what each project should learn from the other.

---

## 1. Architectural thesis

**Hermes** bets on **fixed tool catalog + LLM judgment + accumulated text artifacts**. `tools/registry.py` is the spine: every capability is a Python function with a JSON-schema, registered at import time, dispatched from `model_tools.py`'s `handle_function_call()`. There are ~40+ tools, organized into 30+ named toolsets (`toolsets.py::TOOLSETS`), and the agent gets a subset selected by platform/profile. The agent loop in `run_agent.py::run_conversation()` is literally a `while iterations < max:` over `client.chat.completions.create()` with tool calls — synchronous, ~4348 LOC of orchestration around that core, but the core is unsurprising. "More capability" means "more tools in the catalog" or "more skills in `skills/`". The substrate is largely closed; the agent's adaptation happens inside the chat transcript and inside skill markdown.

**Seon** bets on **the language as the harness** (per `MEMORY.md`: "merge the language into the harness instead of handcrafting tools"). The agent's primary action is `eval` over CLJS in a long-running pod. There is no `tools/registry.py` equivalent — there is a namespace graph, a Datahike DB, and `cljs.js` to compile new forms. The agent's "tool" is "write the function you need, register it, call it." Capability is bounded by WIT-typed imports (`fs`, `http`, `mcp`, `capability-prompt`, `eval` per `wasm-spike-2026-05-20.md`), not by a tool whitelist. This is a much more general bet and a much more dangerous bet — the agent can construct novel control flow inside a turn, but the substrate must be designed so that "everything is reachable" stays bounded by capability grants rather than by what's been curated.

**Where they agree**: both projects believe the agent runtime should outlive the conversation, accumulate state between sessions, and run wherever the user is (laptop, server, phone). Both reject the "spawn a fresh agent process per request" pattern that web-SaaS agent products use.

**Where they fundamentally disagree**: Hermes believes the right unit of agent capability is the **named tool with a schema + a curated skill markdown that teaches the LLM when/how to invoke it**. Seon believes the right unit is the **registered function in a namespace, discoverable by shape via the program graph**. Hermes makes the agent more reliable today (the LLM is good at OpenAI-style tool calling); Seon bets the next generation of agents will be better at programming than at tool selection, and the harness that gives them a programming substrate will pull ahead.

---

## 2. The skill/learning loop

This is where the comparison is sharpest, because both projects identify "agents that learn from their own work" as the central problem.

**Hermes' loop** is fully fleshed out and is the project's marketing centerpiece:

- `tools/skill_manager_tool.py` (1034 LOC) gives the agent `create / edit / patch / delete / write_file / remove_file` over `~/.hermes/skills/<name>/SKILL.md`. Skills are markdown with YAML frontmatter, supporting files in `references/`, `templates/`, `scripts/`.
- `tools/skills_tool.py` provides progressive disclosure: `skills_list` returns metadata only, `skill_view` loads full content on demand.
- `tools/skill_usage.py` tracks per-skill `use_count / view_count / patch_count / last_activity_at` in a sidecar JSON.
- `agent/curator.py` (1781 LOC!) is a background auxiliary-model task that, after idle time, runs an LLM pass over agent-created skills and decides whether to consolidate/archive/pin them. It can absorb one skill's content into another and detect that via heuristic scanning of its own tool calls (see `_classify_removed_skills`). It writes structured YAML summaries, keeps backups, and is gated so it only touches `created_by: "agent"` skills.
- `tools/memory_tool.py` is a parallel surface: bounded text files `MEMORY.md` / `USER.md`, snapshotted at session start into the system prompt (the "frozen snapshot" pattern in the file's docstring) so prompt-caching stays valid.
- `tools/session_search_tool.py` + `hermes_state.py`'s FTS5 indices give the agent search over its own past conversations.

This is an impressive, deeply considered system. But notice the shape: **it is many parallel artifact stores** (skills/, MEMORY.md, USER.md, session SQLite, .curator_state, .usage.json) **with custom curation logic per store**, all of which exist because the agent cannot modify its own source.

**Seon's analogue** is more radical and much less built out. Per `concepts/code-as-data-runtime.md` (referenced from CLAUDE.md): the agent's authored forms are persisted as `:seon.fn` / `:seon.ns` / `:seon.schema` entities. The substrate source IS the bootstrap. The seed-and-resume path, the publish gate, the disk-write debug mode all read from the same place. The analyzer that processes the agent's evals is the same analyzer that loads the substrate at boot. Reactive context (per `concepts/reactive-context.md`) means "surfaces" are query functions over the DB — render at request time, vanish when the underlying datoms vanish. No "mark this warning as seen", no notification queue, no `.curator_state`.

**Are they solving the same problem differently, or different problems?** Mostly the same problem, differently. Hermes' skills are procedural memory ("how to do task X"); Seon's `:seon.fn` entities are literally executable. A Hermes skill is read by the LLM and re-interpreted into tool calls each invocation; a Seon function is called. This means Hermes' skill-quality bottleneck is "did the LLM correctly follow the markdown?", while Seon's is "did the function the agent wrote work, and is its schema right?". The latter is checkable by the runtime; the former is checkable only by re-running and observing.

The curator pattern is genuinely interesting because Hermes had to invent it to manage skill sprawl — the substrate doesn't garbage-collect dead skills. Seon's "derived by default" stance suggests that a function nobody calls and no consumer registers interest in is naturally invisible; you don't need a curator if the system surfaces only what currently has consumers. But Seon hasn't proven this at scale yet; Hermes has 1781 LOC of evidence that *something* has to manage the artifact pile.

---

## 3. Substrate vs product — what Hermes' surface tells us

Hermes is unapologetically product. Gateway adapters under `gateway/platforms/` cover ~25 platforms (Telegram, Discord, Slack, WhatsApp, Signal, Matrix, Mattermost, Email, SMS, Dingtalk, Wecom, Weixin, Feishu, QQBot, Bluebubbles, Yuanbao, Home Assistant, webhook, api_server, ...). The Ink/React TUI (`ui-tui/`), the dashboard with PTY-over-WebSocket (`hermes_cli/pty_bridge.py`), the skin engine (`hermes_cli/skin_engine.py`), the curses tools-config UI, the onboarding wizard, voice-memo transcription, Nous Portal OAuth, model picker, 200+-model provider plugin set — all polished UX. The CLI subcommand catalog (`hermes model`, `hermes tools`, `hermes setup`, `hermes doctor`, `hermes kanban`, `hermes curator`, `hermes cron`, `hermes claw migrate`) shows what users actually do with the thing.

Seon's deliberate "no consumer-product code in `src/`" stance (CLAUDE.md "hard rule") means none of this lives in the substrate. The question is: **what does Hermes' productized surface tell us a Seon-consumer-product would need to provide?**

The non-negotiables, ranked by how unavoidable they look from Hermes' shape:

1. **Multi-platform message ingress as a separate process from the agent runtime.** Hermes' gateway is a long-lived watcher process distinct from the CLI/TUI. Its `gateway/platforms/base.py` has nontrivial logic for queueing messages while the agent is mid-turn, bypass paths for approval/control commands, token-scoped locks for credentials. This is real engineering and it does NOT belong in the substrate. A Seon consumer that wants Telegram needs its own gateway process talking to the pod via WIT-typed inbound messages.
2. **A "session" abstraction with searchable history.** `hermes_state.py` is 3279 LOC, mostly schema management and FTS5 search. Seon's "everything is in Datahike, query it" pitch needs to demonstrate equivalent ergonomics; Datahike doesn't have FTS5, and search-over-past-conversations is a common-enough agent capability that this gap should be planned for.
3. **Background scheduling.** `cron/` is small but load-bearing. Seon has no cron equivalent in the V0 pod. For a "personal AI that does work while you sleep", this is table stakes.
4. **Approval/clarify flow.** Hermes' `tools/approval.py` + `clarify_tool.py` + the gateway's bypass logic are how the agent asks the human a question without blocking the whole event loop. WIT's `capability-prompt` interface is the seon-equivalent skeleton but the UX (remember-this-decision, review pane, deny-with-reason) is unbuilt.
5. **Cross-session user modeling.** Hermes ships Honcho as the default memory plugin for "what the agent knows about you". Seon's reactive-context stance suggests this should be a derived view, but the actual user-model data has to be persisted somewhere with deliberate schema.

The implication for Seon: the "seon-desktop" / "seon-app" distribution is going to be larger than the substrate. The substrate gives you the pod, the eval, the DB, the WIT surface. The product gives you the gateway-process, the platform adapters, the cron, the approval UX, the session-search index. The pattern in `pod-host/wasm-tauri/` for Tauri-host-owns-everything-outside-the-pod is correct, but the *amount* of code that lives there is going to be substantial — Hermes is the evidence.

---

## 4. Containment and sandboxing

Hermes' `tools/environments/` ships seven backends (`local.py`, `docker.py`, `ssh.py`, `singularity.py`, `modal.py`, `daytona.py`, `vercel_sandbox.py`) plus `managed_modal.py` for shared infra. The shared `BaseEnvironment` (`tools/environments/base.py`) implements **spawn-per-call** with a session snapshot — env vars, functions, aliases captured once at init and re-sourced before each command, CWD persisted via in-band stdout markers (remote) or a temp file (local). It is a unified shell-exec abstraction.

**Threat model in Hermes:** the agent has bash. The question is *whose bash*. `local.py` is the agent's host machine, full trust, no isolation. `docker.py` is hardened (`cap-drop ALL`, `no-new-privileges`, PID limits, configurable resource caps, optional bind mounts) but the agent still has root-equivalent inside the container. `ssh.py` is an arbitrary remote machine. The Modal/Daytona/Vercel backends are remote ephemeral sandboxes with serverless hibernation — the most interesting threat properties because they're not on the user's machine at all.

**Capability model in Hermes:** coarse. The agent has shell-exec or it doesn't. Per-command approval (`tools/approval.py`) is the only fine-grained gate, and approval policy is patterns the user maintains (`hermes setup` collects them). Tool-level enable/disable via toolsets is opt-in/out at session start, not per-call.

**Seon's WASM-Tauri model** (from `wasm-spike-2026-05-20.md`) inverts this:

- **Default is no capability.** The pod's CLJS code cannot read a file or open a socket without going through a WIT-imported interface.
- **Capability is typed.** `fs::read-file` exists as a WIT function with a `path` parameter; the host decides whether `path` is in the allowlist. There is no "bash" in the substrate — the agent constructs the call it wants.
- **Containment is the runtime, not policy.** wasmtime enforces the import surface; the agent's `(js/require "node:fs")` simply doesn't resolve because the module isn't there. Hermes' Docker hardening is policy on a Linux process; the kernel is the trust boundary. WASM's trust boundary is the wasmtime-validated component edge, which is a smaller and more inspectable surface.

**What each enables:**

- Hermes' bash-as-tool is honest about how most agent work happens today (run a command, parse output). Engineers reason about it correctly because they already know how shells work. The cost is that the threat model is "trust the LLM's command", because every shell-out is a potential `rm -rf $HOME`.
- Seon's typed-capability model is strictly more secure if it works, but it requires that every capability the agent needs has been planned and exposed. The agent cannot improvise its way to a capability that wasn't anticipated. The escape hatch is "give the agent code-execution and let it call WIT directly", which puts you back in something like Hermes' situation.

**The honest comparison:** Hermes has shipped a working product where the sandboxing question is largely punted to "use Docker/Modal/whatever the user trusts". Seon is betting that a planned capability surface beats a powerful-but-policed one. Both bets can be right for different user populations — Hermes for "I want my agent to do anything a developer can do", Seon for "I want my agent to do things I've authorized and nothing else, by construction". Seon's bet is harder to ship and pays off later.

One specific thing Hermes gets right that Seon should copy: the **`tools/approval.py` + per-pattern allowlist** model. Even with WIT containment, individual `fs::write-file` calls to sensitive paths need user approval; the pattern of "user defines patterns that auto-approve" rather than "user clicks every time" is correct UX and Hermes has the matured shape.

---

## 5. Language/runtime choice

**Hermes (Python):**

- **Gains:** the entire LLM-tooling ecosystem (anthropic SDK, openai SDK, litellm, mcp, langchain-adjacent libraries) is in Python. Every provider's official SDK works first try. Hiring is trivial. The agent itself can write Python and run it via `tools/code_execution_tool.py`, which is the most common dynamic-capability use case in agent work today.
- **Gives up:** the agent is editing files that the runtime has to re-import to use, with all of Python's reload pain. Hot reload of agent-written code into the running process is not a real Hermes feature; the agent writes scripts, the agent runs scripts, the result is captured as text. The agent cannot reach into the runtime and patch a function. The runtime and the source code are different things, and there is friction between them.
- **Subprocess everywhere:** `tools/environments/base.py` spawn-per-call is *the* execution model. Every command boots a fresh `bash -c`. Every code execution is a subprocess. Every delegate-task is a thread with its own subprocess pool. This is fine for shell work, but it means inter-tool data has to round-trip through JSON serialization and stdout parsing. There is no shared memory between the agent's reasoning and its tools, only the chat transcript.

**Seon (Clojure/ClojureScript):**

- **Gains:** the agent's authored code runs in the same process as the harness. `(defn foo ...)` mutates the analyzer state, the function is callable from the next form, and the form's data flows back to the agent's context without serialization. Homoiconicity means the agent can read its own program structure (the analyzer produces it for free) and reason about call graphs, schemas, refs. The REPL-driven dev loop matches how agents actually work (try, observe, iterate) — Hermes' agent is doing the same loop, but through a transcript instead of a runtime.
- **Gives up:** the LLM ecosystem is much weaker. Every provider integration has to be hand-rolled (`seon.ai.deepseek` is one file; Hermes has 20+ provider plugins). Clojure hiring is harder. ClojureScript-on-Node is a less-tested target than Python-anywhere. CLJS bootstrap compilation under wasm-rquickjs is a genuine open question (per `wasm-spike-2026-05-20.md` §"Risk: cljs.js bootstrap").
- **The pod is the unit of state.** Seon does not have Hermes' "spawn a subprocess per tool call" model. Everything happens inside the one pod process, sharing the one DB, sharing the one analyzer. This makes the agent's introspection trivial (`(d/q '[:find ...] @conn)` returns live data) but it also means the pod is single-tenant by design. Multi-agent in Seon is multiple pods, not multiple subprocesses inside one pod (per the agent-id ALS dynvar work on the current branch).

The deepest tradeoff: Hermes gains everything Python gains and gives up the substrate-mutability that makes Seon's "code-as-data" thesis possible. Seon gains substrate-mutability and gives up most of the ecosystem. For a research/personal project where the user (you) IS the developer, Seon's tradeoff is plausibly correct. For a "100,000 users on Telegram" project, Hermes' tradeoff is obviously correct. The question is whether Seon's bet pays off at the *agent quality* level: if agents become much better at programming-in-the-large than at tool-calling, the substrate that lets them program wins. That's a 2-3 year bet, not a 6-month bet.

---

## 6. Things Seon should steal from Hermes

1. **The progressive-disclosure pattern for skills/functions.** Hermes' `skills_list` returns metadata, `skill_view` returns body. Seon's "the agent can query the program graph" is the analogue, but the *cost discipline* (tier 1 = name+description+tags, tier 2 = full source) is a UX pattern worth importing. When the agent asks "what functions accept a `::user-id`?", return signatures only; full source on second query.
2. **The curator-as-auxiliary-model pattern.** Even with Seon's "derived by default" stance, agent-authored `:seon.fn` entities will accumulate. A background auxiliary-LLM pass that consolidates / archives / re-tags them, gated to only touch agent-authored ones, is a known-good pattern. The 1781 LOC of `agent/curator.py` has solved problems Seon will eventually have.
3. **The `MEMORY.md` frozen-snapshot prompt-caching discipline.** Per `tools/memory_tool.py`'s docstring: mid-session writes update the file but do NOT change the system prompt, to preserve the prefix cache. This is correct LLM-economics. Seon's reactive-context rendering needs to be aware that *putting a derived view in the system prompt invalidates the cache* — derived-at-render is great, derived-into-system-prompt-every-turn is expensive.
4. **The per-pattern approval allowlist.** `tools/approval.py` + user-curated patterns is the right UX for "agent asks before doing dangerous things". Seon's `capability-prompt` WIT interface needs the same shape on the host side.
5. **The `delegate_task` shape with `role="leaf"` vs `role="orchestrator"`.** Restricted toolsets per child, max-spawn-depth, max-concurrent-children, parent-blocks-on-children. Seon's multi-pod story needs equivalent semantics.
6. **The skill-description hardline: ≤60 chars, one sentence, ends with a period, no marketing words.** This is operationally important — Hermes discovered that long skill descriptions dilute the model's attention when many skills are loaded. Seon's `:malli/schema` docstrings will have the same problem when the program graph is large.
7. **FTS5 over session messages.** Whatever Seon's equivalent storage shape is (probably datoms-with-text-attrs), there has to be a fast text-search path. Datahike doesn't ship one. This is going to bite.
8. **The "platforms:" gating on agent-authored artifacts.** Hermes' skills declare which OS platforms they support; the loader filters at load time. Seon's analogue is "this function requires `node:fs`" or "this function uses an unbounded `wasi:http` import" — capability gating should be metadata on the registered function.

---

## 7. Things Hermes validates about Seon's bets

1. **Long-lived process, not request-per-conversation.** Hermes' core process model (CLI or gateway, both long-lived) matches Seon's. Nobody serious is building agents on the stateless-Lambda model.
2. **One database for everything the agent knows.** Hermes' `hermes_state.py` is SQLite-with-FTS5, but it's the same "single store, queried for everything" shape Seon does with Datahike. The argument "the runtime IS the database" is winning by default in this space.
3. **The agent should run wherever the user is, not be tied to a UI.** Hermes' gateway architecture (Telegram bot etc. talks to a backend agent on a $5 VPS) is the same desire Seon expresses with "Tauri host + agent runs in pod, accessible from anywhere". Different topology, same conviction.
4. **Capability surface > capability theater.** Hermes' `delegate_task` `DELEGATE_BLOCKED_TOOLS` set (no recursive delegation, no clarify, no memory writes, no send_message, no execute_code for children) is the same instinct as Seon's WIT-import restrictions: children get a strictly smaller capability surface than parents. Both projects independently arrived at "capability removal is a first-class operation".
5. **Cross-session memory of the user, not just the conversation.** Hermes ships Honcho by default. Seon's reactive-context stance implies a user-model schema is going to be necessary. Both projects agree this is core, not optional.
6. **The agent should be able to schedule itself.** Hermes ships cron with the `cronjob` tool the agent can call. Seon's "agent can write any function and register a trigger" is the same capability via a different mechanism.

---

## 8. Where they disagree, and who's probably right

**On "should the agent's primary capability be tool-calling or code-writing?"** Hermes says tool-calling; Seon says code-writing. **Today, Hermes is right.** Frontier models are well-tuned for OpenAI-style tool calls, and "write a Clojure form against an analyzer-discovered graph" is harder for the model than "call `read_file(path='x')`". **In 2-3 years, Seon is probably right.** The model-capability trajectory points at agents that program, and the harness that gives them a programming substrate scales further than the one that gives them a tool catalog. Hermes' 40+ tools and 30+ toolsets are *already* hitting "the model gets confused which tool to use" — they invented the curator partly to manage this.

**On "should the substrate ship the product surface?"** Hermes says yes (the gateway, the TUI, the dashboard, the platform adapters all live in the same repo). Seon says no (the substrate ships only the pod and the WIT surface; products live downstream). **Seon is right for the project's stated goals (a substrate other projects build on)**, but the cost is that *building anything personally useful with Seon today requires building the gateway/UI/scheduler yourself*. Hermes has the harder-to-resist value proposition for a single user who wants something working tonight.

**On "how should agent-authored artifacts be managed?"** Hermes says with explicit curation (`curator.py`, usage tracking, archives). Seon says by derivation (if nothing references it, it's invisible; if nothing transacts it, it doesn't exist). **Seon's stance is theoretically cleaner but unproven at scale.** Hermes' 1781-LOC curator is evidence that real systems need the explicit-management layer even if the theoretical model says they shouldn't.

**On "what's the right sandbox?"** Hermes says "Docker is good enough for most users, give them Modal/Daytona if they need more". Seon says "WASM components with WIT-typed imports are the only honest answer". **Seon is right for security-sensitive use, Hermes is right for capability-rich use today.** Both can be true for different users.

**On "Python vs Clojure".** Asked in 2024 the answer was unambiguous (Python wins). Asked in 2027 it's genuinely contested — homoiconic substrates start winning when the agent is the primary author. Seon's bet is on the inflection. Hermes' bet is on the present.

---

## Closing observation

The most important thing this comparison surfaced: **Hermes has built every piece of the personal-AI substrate Seon's `MEMORY.md` describes — the curator, the memory layers, the gateway, the cron, the session search, the multi-platform reach, the subagent delegation — and it works.** The Hermes codebase is direct evidence that this product category is buildable today with a fixed tool catalog and a competent LLM. Seon's bet is not "this product category is possible"; it's "the architecture Hermes built will hit a ceiling that a code-as-data substrate doesn't". That bet may be correct, but it's not a 2026 bet — it's a 2027-2028 bet. Until then, Hermes' shape is the benchmark Seon should measure itself against feature-by-feature, while preserving the architectural conviction that distinguishes them.
