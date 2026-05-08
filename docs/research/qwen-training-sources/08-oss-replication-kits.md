---
title: OSS replication kits and training environments — ranked by Phase-0 fit
source-url: various — inline
retrieved: 2026-05-07
fetched-via: WebFetch
---

# OSS training kits / environments — ranked for the agent Phase 0

Phase 0 doesn't need to do agentic RL itself; it needs an environment harness
shaped like what Qwen was trained against, so the model lands on its training
prior. Ranked by fit for that goal:

## Tier 1 — closest fit

### 1. Qwen-Agent (Alibaba)

- The first-party harness. Tool-call format = the wire-level truth.
- Built-in Docker-sandboxed code interpreter, MCP integration, RAG.
- **Use this as the reference; copy the prompt envelope.** the agent Phase 0 can
  literally embed Qwen-Agent (or import its `fncall_prompts`) for a
  zero-friction match.
- Repo: https://github.com/QwenLM/Qwen-Agent

### 2. Qwen Code (Alibaba CLI)

- The first-party agentic CLI. File-system + shell + skills + subagents.
- Reference for the *agent loop shape* (skill packs, subagent spawning).
- Not a training pipeline; useful for understanding what trajectories the
  model expects.
- Repo: https://github.com/QwenLM/qwen-code

## Tier 2 — strong fit, well-engineered

### 3. SkyRL (NovaSky / Berkeley)

- Full-stack RL library with **skyrl-gym** (math, coding, search, SQL,
  terminal envs), **skyrl-train**, **skyrl-agent** for "long-horizon, real
  environment" agents. On-policy distillation supported.
- Closest open-source equivalent to Alibaba's MegaFlow + RollArt stack at
  scale. If Phase 0 ever expands into actual RL, this is the reference.
- Repo: https://github.com/NovaSky-AI/SkyRL

### 4. OpenHands (All-Hands-AI)

- Production-grade agent runtime with Docker per-session sandbox, browser,
  shell, file editor. Used as the rollout substrate by SWE-Gym.
- Bigger + more general than Qwen Code; integrates Claude/GPT/Qwen. Tool
  format is OpenHands-specific but interoperable.
- Repo: https://github.com/All-Hands-AI/OpenHands

### 5. SWE-Gym (Princeton/UCSD)

- 2,400 real Python tasks from 11 repos with executable environments.
  Demonstrated +14% absolute on SWE-Bench Verified with 32B model.
- Trajectory-collection scaffold using OpenHands + MoatlessTools.
- **Closest open analogue to Qwen3-Coder's training environments.**
- Repo: https://github.com/SWE-Gym/SWE-Gym

## Tier 3 — useful tooling

### 6. SWE-rebench, SWE-Smith, SWE-Flow, Multi-SWE-RL

These are the four datasets Qwen3-Coder-Next was trained on (per arxiv
2603.00729). Mining them gives Phase 0 access to *Qwen's actual training
distribution*. SWE-rebench in particular is widely-used.

### 7. AgentTuning / AgentInstruct (Tsinghua + Microsoft)

- AgentInstruct (Microsoft 2024): synthetic agentic instruction-tuning data.
  Smaller than Qwen-scale but methodologically transparent.
- AgentTuning: SFT-only mid-training kit; not full RL.
- Useful for *pre-RL warmup data shape*, not Phase-0-relevant directly.

### 8. Open-Agent-Collection / AutoGen Studio / Letta

- Higher-level agent frameworks. Useful as inspiration for agent
  *architecture*, not as training kits.
- Letta has a memory-management abstraction that overlaps with the agent's
  fact-graph thinking — worth a separate look (#52 in the agent CLAUDE.md
  research questions).

## Tier 4 — production infra (deferred)

### 9. RollArt (Alibaba paper)

- Disaggregated RL infra: H800 for prefill, H20 for decode, CPU for env,
  serverless for reward. **Not open-sourced.** Read for design philosophy
  only.
- Paper: https://arxiv.org/abs/2512.22560

### 10. E2B / Modal / Firecracker

- Per-rollout sandboxing infra. Phase 0 won't need this scale; flagged for
  V1+.

## Phase-0 recommendation

**Build on Qwen-Agent's harness (Tier-1 #1) for the tool-call format and
Docker sandbox. When Phase-0 needs richer environments for trajectory
collection, layer SWE-Gym (Tier-2 #5) trajectories on top — that's literally
the training distribution Qwen3-Coder-Next saw.**

For the the agent-specific persona/conversation environments (the parts that
*aren't* SWE-Bench), no OSS kit fits — those need the agent-internal synthetic
generation. But the *primitive interface* should mirror Qwen-Agent so
trajectories emitted by the agent-environments are training-compatible if the
question of fine-tuning ever arrives.
