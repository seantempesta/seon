---
type: prd
status: research
tags: [prd, agent, context]
---

# Next turn — give the agent exactly what its next completion needs

**The mandate (owner, 2026-09-26):** getting the right data to the agent
matters more than anything else in the context pipeline. Dream big: if we could
mostly know what an agent will do on its next turn (which functions it will
call, what data it will pass, which schemas it needs), every part of its context
could serve that one completion. Maybe even token by token: predict the forms
the way an autocomplete does. Which parts of this system can be decided with
fast "System 1" thinking instead of a full model call? No RAG-style dumps of
maybe-related text. Estimate the cost of the top ideas.

**Owner hypothesis (2026-09-26), to be tested, not assumed:** per-turn curated
dynamic context plus form autocomplete will most improve agent performance. The
agent doesn't know how the system works; showing only what it needs lets it focus
on the task. The synthesis must say whether the evidence supports this and what
would falsify it.

This folder is the working record. Research lanes write to `research/`; the
synthesis (ranked ideas with costs) lands in `synthesis.md`.

## Where the session started (context, not a constraint)

The conversation that opened this folder evolved one idea, and the owner asked
that research **not** be anchored on it:

1. Score every renderable entity against the agent's state with a contrastive
   state/action model (CLM, ported to MLX in `src-clm/`), per turn, instead of
   the fixed 2-hop walk.
2. Candidates are (entity, renderer) pairs, keyed by (input digest, renderer
   definition digest) → text digest → vector. Ranking picks what to show and
   how much.
3. Owner idea: an LRU cache for all renders, since re-rendering is cheap;
   evict what isn't used.

## Ingredients (verify each against source before relying on it)

- **Recorded agent work** in Datahike: evaluations (`:seon.cluster.eval/*`:
  source, comment, output, error, read evidence), shown text
  (`:seon.eval/shown`), turns, provider attempts with reasoning
  (`:seon.ai.attempt/reasoning`), messages, errors, tasks, issues.
- **The program as rows:** functions, tests, schemas, namespaces, contracts,
  call graph (`:seon.fn/calls`), render pairs, analysis and test evidence keyed
  by definition digests; Datahike history/branches/`as-of`; Malli schemas and
  contracts; SCI contexts.
- **Rendering:** per-entity AI/HTML renderers, the walk
  (`seon.render.walk`), prompt assembly (`seon.cluster.prompt`), opening turns
  (`seon.turn/system-turn`), changed reads.
- **Trajectory corpora on disk:** Claude Code transcripts for this repo
  (`~/.claude/projects/-Users-sean-src-seon/`: 39 top-level sessions, 538 MB;
  1,211 files and 1.9 GB with subagents) and
  Codex sessions (`~/.codex/sessions`, ~7.5 GB). These are our own agents
  working on this very codebase.
- **Models on this Mac (M5 Max, 128 GB):** CLM-8B on MLX (`src-clm/`, state
  encode 70–520 ms, 100k-candidate scan 1.6 ms); needle 26M REPL-autosuggest
  port (`src-needle/`); cached MLX LLMs incl. Qwen3.6-35B-A3B-4bit, Qwen2.5-Coder
  0.5–3B; provider models via `seon.ai` (default DeepSeek).
- **Prior art in this repo:** `docs/prds/context-generation/` (the rendering
  pipeline program), the archived `docs/prds/repl-autosuggest/`
  (`git show 607147a62^:docs/prds/repl-autosuggest/...`),
  `docs/research/agent-platform/` (context cost, render pipeline, task context).
- **Current selection design (orchestrator, 2026-09-26):** the graph walk from the
  agent entity (`seon.render.walk/graph`);
  `docs/research/agent-platform/task-context-install-design-2026-09-25.md` (+ its
  review), `render-graph-research-2026-09-25.md` (0.8 ms walk, 97% render-cache
  hits), and the task-seeded model in `language-requirements-2026-09-24.md` §5a
  (seeds from the task's proofs/must-read; prototype 7.7 KB vs 680 KB). The owner
  is choosing edges and task seeding now; this research feeds that.
- **Render caching:** per-branch `:seon.render/cache`, shared by same-branch forks (~70 MB, unbounded); a central
  weighted-cache design (Caffeine, one byte budget) is pending the owner.
- **Dependencies:** `reference-code/` (Datahike incl. secondary indices and the
  Proximum vector adapter, Malli, SCI, core.async flow, konserve, CLM).

## Rules for lanes

- Read-only on the codebase: write only your own file under `research/`.
- The shared JVM is under profiling: at most a few small read-only MCP queries,
  no JVM starts, no test runs, no `bin/seon` lifecycle commands.
- One GPU/MLX process at a time on this machine; only the lane that says so may
  run models.
- Evidence with `file:line`, measured numbers with the command that produced
  them; mark every estimate as an estimate.

## Lanes

| lane | question | file |
|---|---|---|
| ingredients | What does Seon record and derive that could predict a turn; volumes and access cost | `research/ingredients.md` |
| predictability | Measured: how predictable is an agent's next action from its trajectory | `research/predictability.md` |
| token-level | Form/token-level prediction: autocomplete, speculation, constrained decoding | `research/token-level.md` |
| prior-art | Literature and systems: context engineering, tool retrieval, next-action prediction, anticipation | `research/prior-art.md` |
| render-cache | The owner's render LRU: what exists, where it lives, cost | `research/render-cache.md` |
| astra | Independent brainstorm by an outside model | `research/astra.md` |
| form-assembly | Owner, 2026-09-26: building a form as typed choices (head → arity → args from schemas); prior art, measured hole-by-hole choice sets | `research/form-assembly.md` |
| test-selection | Owner, 2026-09-26: rank and filter impacted tests to bound what runs per change | `research/test-selection.md` |

## Status

Research complete (2026-09-26). Synthesis in `synthesis.md`, revised after
Astra's review (`research/synthesis-review.md`). Awaiting owner decisions.
