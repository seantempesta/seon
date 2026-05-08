# Voyager (MineDojo)

**Summary.** The canonical paper-and-code reference for "LLM-driven embodied agent that learns through curriculum + skill library" — the architectural pattern the agent's brainstorm explicitly cites as inspiration. Last commit 2023-07; the codebase is a research artifact, not a maintained framework.

**Repo:** [MineDojo/Voyager](https://github.com/MineDojo/Voyager)
**License:** MIT
**Last commit:** 2023-07-27 (frozen)
**Language:** Python + JavaScript (Mineflayer/Minecraft bot)
**Paper:** arXiv 2305.16291 — "Voyager: An Open-Ended Embodied Agent with Large Language Models"

## Architecture

Three components, each a `langchain.ChatOpenAI`-driven agent (yes, original-langchain — the codebase is from mid-2023):

- **`CurriculumAgent`** (`voyager/agents/curriculum.py`) — proposes the next task for the agent to attempt. Takes `completed_tasks` and `failed_tasks` as state; uses an embedding-based QA cache (Chroma) to recall relevant prior tasks. Outputs the next milestone task to attempt.
- **`ActionAgent`** — given a task and current context, writes a Minecraft Mineflayer JS program that attempts it. Iterates on the program with self-critique.
- **`CriticAgent`** — judges whether a written program achieved the task; produces error messages for the action agent's next iteration.

Skill library: completed-task code is stored in a vector-DB-indexed library; future tasks retrieve relevant skills as in-context examples. **This is the "agent builds its own skills through play" pattern.**

The Minecraft-specific glue is irrelevant for the agent; the **conceptual pattern** is what to borrow:

1. Curriculum agent proposes; action agent attempts; critic agent judges.
2. Vector-indexed library of "skills the agent has discovered."
3. Failure-recovery via critic feedback → action-agent retry.

## the agent-specific fit

This is a **pattern reference**, not a runnable harness:

1. **Curriculum:** Excellent pattern. the agent's curriculum agent could be the same shape — propose next scenario based on completed/failed.
2. **Pluggable tool-call format:** N/A — Voyager writes Mineflayer JS, not OpenAI tool-calls.
3. **Multi-agent:** Three roles (curriculum/action/critic), all GPT-4 with different prompts. the agent's three-role plan (agent / reactor / grader) is structurally identical.
4. **Trajectory capture:** Per-task code + execution result; not in a modern SFT/RL format.
5. **Pluggable scoring:** Critic agent is the LLM-judge; Mineflayer execution is the decidable check. Same stratification the agent wants.
6. **License + maintenance:** MIT but **dead since 2023.** Code references langchain's old chat-models API which has since been refactored multiple times.
7. **Python-first:** Yes (and JS for the Minecraft bot).

## What we'd need to change/add to use Voyager

Functionally everything except the structural pattern. The Minecraft env, the Mineflayer JS executor, the langchain dependencies all need rewrite. By the time you've ported it to a modern OpenAI-async-client world with the agent's primitives instead of Mineflayer commands, you've written verifiers' MultiTurnEnv from scratch.

## Verdict

**Borrow concepts; do not adopt code.** Voyager is the right paper to cite when explaining the agent's curriculum thesis to the client lead; the **CurriculumAgent / ActionAgent / CriticAgent triad maps cleanly onto the agent's curriculum-scheduler / the orchestrator agent / cultural-grader split.** That's the architectural inheritance.

Specific patterns to borrow:

1. **Curriculum agent as a separate role** — a Qwen instance whose job is "propose the next scenario given completed/failed history." the agent's M0 has Sean hand-writing scenarios; M1 onward should consider promoting curriculum-generation to its own agent role.
2. **Skill library = vector-indexed retrieval over prior successful trajectories.** the agent's "trajectory pool" plus retrieval-on-relevant-context is the same pattern. When the orchestrator agent encounters a new scenario, its `project()` could pull relevant prior-trajectory shards from a skill library.
3. **Failure-recovery as iteration with critic feedback** — the agent's curriculum risk #1 (reward-hacking on recoveries) is sharper here: Voyager pays the agent for iterating-toward-success; the agent explicitly rules out paying for recovery itself.

**Recommend: pass on adopting Voyager directly. Reference the paper's three-agent pattern in the agent's V1 design doc; implement the same shape inside verifiers.**
