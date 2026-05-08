# Letta (formerly MemGPT)

**Summary.** A stateful agent runtime with tiered memory (in-context + archival via vector store) and an opinionated memory-management toolset. As of 2026, Letta has pivoted toward being a Claude-Code-style **CLI for coding agents** with persistent memory; the Python/TypeScript SDK and API persist for embedding.

**Repo:** [letta-ai/letta](https://github.com/letta-ai/letta)
**License:** Apache-2.0
**Last commit:** 2026-04-07 (active, slightly less than the others)
**Language:** Python (server) + TypeScript (CLI)

## Architecture

Letta agents are persistent server-side objects with:
- **Memory blocks** — labeled in-context memory regions (e.g., `human` block, `persona` block) the agent edits via tool calls.
- **Archival memory** — vector-indexed long-term store, retrieved via `archival_memory_search`.
- **Recall memory** — full conversational history, queryable.
- **Tool-call loop** — server runs the inner loop; client sends a message, server returns assistant response after running any tool calls including memory operations.

The 2026 product direction (per README): "Letta Code" is a `letta` CLI that competes with Claude Code / Aider; "Letta API" is the embeddable agent service.

It is not a training harness. There's no curriculum, no scorer, no trajectory-export-for-RL pipeline. Trajectories *exist* (every conversation is logged) but the framing is "run a stateful agent in production," not "evaluate / train an agent."

Tool format: Letta uses OpenAI-format tool-calling internally; works with any model that speaks it (Qwen 3.6 fine).

## the agent-specific fit (re-evaluated as a *harness*, per the brief)

1. **Curriculum / scenario-driven training:** No. Letta is a runtime, not a training framework. You'd have to script curriculum-driving and trajectory selection externally.
2. **Pluggable tool-call format:** Yes (OpenAI-format).
3. **Multi-agent / multi-role:** Letta has a `groups/` concept (multi-agent) but it's coordinator-style (one agent calling others), not the agent's "agent + persona-reactor playing peer roles."
4. **Trajectory capture:** Conversations are logged but not in a clean "trajectory for SFT" shape. You'd build the export.
5. **Pluggable scoring:** None native. Add yourself.
6. **License + maintenance:** Apache-2.0, active.
7. **Python-first:** Yes.

## What we'd need to change/add to use Letta as a harness

Effectively all of it: curriculum scheduler, scoring, multi-role orchestration, persona-reactor, trajectory export. **The only thing Letta gives you for free is "memory tier abstraction with built-in tool-calls"** — but that's exactly the thing the agent's build thesis (primitives + curriculum + agent learns its own idiom) explicitly does not want imposed top-down. the agent wants the agent to discover what `project()` does; Letta hands the agent a fixed memory-tier API.

## Verdict

**Pass as a harness. Borrow the production-runtime architecture as a V2+ reference.**

Letta is a useful *reference* for what a production sovereign-memory runtime looks like (memory-block UX, archival/recall split, tool-mediated memory edits), but it's the wrong primitive substrate for the agent's training phase. the agent's whole pedagogical claim — "we don't tell the AI how to remember" — explicitly rejects Letta's hand-designed memory-tier API.

Two specific things worth borrowing from Letta in V1+:

- The **memory-block UX** — labeled in-context memory regions with edit/append/replace tools is a clean user-facing abstraction. If the agent's V1 demo includes a "what does the agent think it knows" surface (open question Q13 in the brainstorm), Letta's memory-block UX is the closest existing reference.
- The **server-side persistence model** — the agent will eventually need this for per-user agents that survive across sessions. Letta is a solved version of that problem.

For Phase 0 specifically: Letta is a distraction. Use verifiers; build the agent's primitive set; don't commit to Letta's memory-tier ontology.
