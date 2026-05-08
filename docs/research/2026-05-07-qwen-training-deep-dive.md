---
title: Qwen training deep-dive — Phase-0 harness implications
date: 2026-05-07
research-bundle: docs/research/qwen-training-sources/
status: synthesis (research, not decision)
---

# Qwen training deep-dive — synthesis for the the agent Phase-0 harness

## TL;DR

The Phase-0 question was: *what agentic environment was Qwen actually
trained against, so the agent's harness can land on the model's training prior
instead of asking it to do something it has no priors for?*

Answer in one paragraph:

> Qwen3-Coder (July 2025) and Qwen3-Coder-Next (late-2025/2026) were trained
> in a Docker-per-task environment harness running **20,000 parallel
> environments** on Alibaba Cloud, against **~800K verifiable software-
> engineering tasks** synthesized from real GitHub PRs + SWE-Smith + SWE-Flow
> + SWE-Rebench + Multi-SWE-RL. Reward was **rule-based** (unit tests pass /
> fail) with token-level penalties for malformed tool calls and trajectory-
> level penalties for excessive turns. Tool-call wire format is the
> **Hermes/"nous" template** with `<tool_call>{...}</tool_call>` JSON
> blocks, served by **vLLM/SGLang's `qwen3_coder` tool-call parser** plus
> the **`qwen3` reasoning parser** for the `<think>...</think>` split.
> For the agent Phase 0, the closest training-prior-preserving harness is
> **Qwen-Agent (the first-party Alibaba harness) layered with SWE-Gym-style
> Docker-sandbox environments**.

The **"hundreds of thousands of simulations"** claim Sean recalled is most
cleanly attributable to the **800K verifiable tasks** corpus from the
Qwen3-Coder-Next tech report (arxiv 2603.00729), not a literal "hundreds of
thousands of simulations" quote. The 20K-parallel-environments figure is the
infrastructure throughput; 800K tasks is the substrate. Either way, the order
of magnitude Sean recalled is correct and well-attributed.

---

## 1 — Naming reconciliation

Sean's note used "Qwen 3.5 / Qwen 3.6". The official Alibaba release lineage
through 2026-05 is:

| Community name | Official name | Date | Notable |
|---|---|---|---|
| Qwen 3.5 | **Qwen3** | 2025-04 | Thinking-mode unification, 4-stage post-train. arxiv 2505.09388. |
| (n/a) | **Qwen3-Coder** | 2025-07 | First public agent-RL infra claims (20K envs). |
| (n/a) | **Qwen3-Coder-Next** | late-2025 | 80B-A3B, 800K verifiable tasks. arxiv 2603.00729. |
| Qwen 3.6 | **Qwen3.6** | 2026-04 | 35B-A3B + 27B variants. github.com/QwenLM/Qwen3.6. |

Methodologically, the deep documentation is in **Qwen3-Coder-Next** (the
arxiv tech report) and the **Qwen3-Coder blog** — those carry the agent-RL
specifics. Qwen3.6 is the latest base+post-train; its model card emphasizes
the agentic surfaces that come from Qwen3-Coder lineage.

## 2 — The post-training pipeline (canonical 4 stages, Qwen3 blog)

1. **Long-CoT cold start (SFT)** — diverse CoT data: math, coding, STEM,
   logic. Sizes not disclosed.
2. **Reasoning-based RL** — rule-based rewards, scaling RL compute.
3. **Thinking-mode fusion** — combine long-CoT + instruction-tuning so a
   single checkpoint supports `enable_thinking=True/False`.
4. **General RL** — "applied RL across more than 20 general-domain tasks"
   (verbatim).

Qwen3-Coder + Qwen3-Coder-Next add a fifth stage in spirit:

5. **Agent / Long-Horizon RL** — multi-turn rollouts in 20K parallel
   Docker-per-task environments, rule-based reward (unit tests), trained
   against 800K verifiable SWE tasks across 9 languages.

## 3 — Numbers we can stand behind

From Qwen3-Coder blog (qwenlm.github.io/blog/qwen3-coder, July 2025):
- "20,000 independent environments in parallel"
- 7.5T pre-train tokens, 70% code ratio
- 256K native context, 1M with YaRN

From Qwen3-Coder-Next tech report (arxiv 2603.00729):
- "~800K verifiable software engineering tasks", 9 programming languages
- Mid-training: "trillions of tokens"
- ~600B tokens from repository-level code alone
- Repository-level training context: 32,768 → 262,144 tokens
- MegaFlow orchestration on Alibaba Cloud Kubernetes; each task = an Argo
  workflow; agent containers co-located with execution-environment containers

From RollArt paper (arxiv 2512.22560):
- Production deployment for the **Qoder** product: "more than 3,000 GPUs",
  "one week" continuous agentic-RL training, hundreds-of-billions-parameter
  MoE
- 1.35–2.05× end-to-end speedup vs monolithic
- Hardware split: H800 prefill / H20 decode / CPU env / serverless reward

Missing (NOT publicly disclosed):
- Total rollout count across the run
- Specific GRPO/PPO/DPO hyperparameters
- Per-stage compute (FLOPs)
- Reward-model architecture for non-code agentic tasks

## 4 — Tool-call wire format (for the agent primitives)

Three layers:

1. **ChatML chat template** — `<|im_start|>role\n…<|im_end|>` per turn.
   Roles: system, user, assistant, tool.
2. **Thinking split** — `<think>…</think>` block at the start of assistant
   turns when `enable_thinking=True`. Tokens 151668 (`</think>`) used for
   programmatic split. Qwen3.6 introduces `preserve_thinking=True` to
   propagate the reasoning across multi-turn.
3. **Tool calls** — Hermes/"nous" template. Assistant emits one or more
   `<tool_call>{"name":..., "arguments":{...}}</tool_call>` blocks. Tool
   results return as `role="tool"` turn with `name` + JSON content.

Inference-server flags (canonical from Qwen3.6 model card):

```bash
vllm serve Qwen/Qwen3.6-35B-A3B \
  --reasoning-parser qwen3 \
  --enable-auto-tool-choice \
  --tool-call-parser qwen3_coder
```

Tool definitions on the wire follow the **OpenAI function-calling schema**
(`tools=[{type:"function", function:{name, description, parameters}}]`).
Qwen3.6 also supports **MCP servers** via `Qwen-Agent`'s `mcpServers`
config block — explicitly trained-against, so it's a first-class path.

## 5 — OSS environment options, ranked

(Full ranking in `qwen-training-sources/08-oss-replication-kits.md`.)

| Tier | Project | Why | Use it for |
|---|---|---|---|
| 1 | **Qwen-Agent** | First-party harness; wire format = ground truth | Tool-call envelope, Docker code interpreter, MCP integration |
| 1 | **Qwen Code** | First-party CLI; reference loop | Skill/subagent patterns |
| 2 | **SkyRL** | Full-stack OSS RL library | Future the agent RL training, if/when |
| 2 | **OpenHands** | Production agent runtime | Per-session Docker sandbox if the agent needs more than Qwen-Agent's CI |
| 2 | **SWE-Gym** | 2,400 real Python tasks, exec envs | Trajectory collection at the training distribution Qwen actually saw |
| 3 | SWE-rebench / SWE-Smith / SWE-Flow / Multi-SWE-RL | Training datasets Qwen3-Coder-Next consumed | Pull *Qwen's actual training distribution* |
| 3 | AgentInstruct / AgentTuning | SFT-only mid-training data | Pre-RL warmup, not Phase 0 |

## 6 — Recommendation for the agent Phase 0

**Use Qwen-Agent's harness as the foundation; expose the agent primitives as MCP
tools.**

Concretely:

1. **Inference**: vLLM serve Qwen3.6-35B-A3B with `--reasoning-parser qwen3
   --tool-call-parser qwen3_coder`. (Match a sibling project-deployed model so
   the agent can ride that infra.)
2. **Harness**: import `qwen_agent` for the prompt-template plumbing and
   tool-loop. Don't reinvent the function-calling envelope.
3. **the agent primitives** (`assert`/`retract`/`query`/`project`/etc.) → expose
   as an MCP server. Qwen-Agent will load it via the `mcpServers` block,
   and the model has explicit prior on MCP-style tool use (Qwen3.6 model
   card demonstrates this directly).
4. **Sandbox**: use Qwen-Agent's Docker code-interpreter for any
   the agent-generated code execution. If the agent needs persistent per-user
   sandboxes (V1+ from the the agent CLAUDE.md), graduate to OpenHands runtime
   or Firecracker.
5. **For environment-style rollouts** (the synthetic-persona simulations
   Sean's pitch describes): no OSS kit fits cleanly, but the
   *trajectory schema* should mirror Qwen-Agent's so future the agent-internal
   trajectories are training-compatible without reformatting.

What this gets you: every tool call the agent emits hits the same parser the
model was trained against. Every multi-turn loop matches the structure of
the 20K-env training. The model isn't asked to invent new tool-call
syntax — it just runs its training prior.

## 7 — Open questions for follow-up research

1. **Reward-model architecture for non-code the agent tasks.** Qwen3-Coder used
   rule-based unit-test rewards. the agent's persona/conversation tasks can't
   use unit tests. Options: model-judge (Opus-as-PRM, like a sibling project virtue
   scorer), human-rating, or synthetic-rule heuristics. Which is cheapest
   credible? Does Goodfire / OpenHands have anything off-the-shelf?
2. **MegaFlow open analogue?** Alibaba's MegaFlow / RollArt aren't open-
   sourced. SkyRL is the closest. If the agent ever wants to *train*, what's the
   minimum viable infrastructure for ~100 parallel environments (vs 20K)?
3. **SWE-Gym as warm-start for non-coding the agent.** Could SWE-Gym's
   trajectory-collection scaffold be repurposed for non-code multi-turn
   tasks (e.g. persona-conversations) by swapping the executor + reward?
   Fast prototype possible?
4. **Qwen3.6's `preserve_thinking` for fact-graph context.** the agent's
   fact-graph projection produces a context that is itself a "thought
   trajectory". Does threading it as `<think>...</think>` blocks via
   `preserve_thinking=True` give better adherence than putting it in
   system-prompt? Worth a benchmark.
5. **The 800K-tasks corpus — is any of it released?** Qwen3-Coder-Next
   doesn't release the verifiable-tasks dataset. SWE-Smith / SWE-Flow /
   Multi-SWE-RL *are* public — what fraction of the 800K is these vs.
   private synth?

## 8 — Caveats / honesty

- Gemini CLI was attempted but produced no output in the time window;
  research relied on direct WebFetch + WebSearch on canonical primary
  sources. All numbers above are from Alibaba blog posts, arxiv abstracts,
  and Hugging Face model cards.
- The "hundreds of thousands of simulations" claim Sean recalled is **most
  consistent with the 800K verifiable-tasks corpus**; a verbatim quote in
  that exact wording was not found. The 20K-parallel-envs figure is also
  consistent if interpreted as concurrent-rollout count rather than
  total-rollout count.
- Detailed RL hyperparameters, total compute, and reward-model architecture
  for non-code agentic stages are NOT public for any frontier lab — Qwen
  publishes more than DeepSeek, which publishes more than Anthropic /
  Mistral.

## 9 — Source files in this bundle

- `qwen-training-sources/01-qwen-3.6-tech-report.md` — Qwen3 blog + arxiv
  2505.09388 4-stage pipeline.
- `qwen-training-sources/02-qwen-3.5-tech-report.md` — Qwen2.5 predecessor
  context.
- `qwen-training-sources/03-qwen-agent-readme.md` — first-party tool harness.
- `qwen-training-sources/04-qwen-code-readme.md` — first-party CLI loop
  reference.
- `qwen-training-sources/05-bfcl-and-tool-formats.md` — wire-level tool-call
  truth.
- `qwen-training-sources/06-simulation-claim-source.md` — primary-source
  hunt for "hundreds of thousands of simulations".
- `qwen-training-sources/07-comparable-models.md` — DeepSeek/Mistral/Llama/
  Anthropic cross-reference.
- `qwen-training-sources/08-oss-replication-kits.md` — environment options
  ranked by Phase-0 fit.
