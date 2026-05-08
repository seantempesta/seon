# Serving stack — vLLM vs SGLang for the agent Phase 0

**Date:** 2026-05-08 (Bangkok afternoon)
**Triggered by:** Sean's recall that the Qwen team's docs name SGLang as
having the most Qwen-specific features, contradicting the
2026-05-07 deep-dive's casual recommendation of vLLM.

**Bottom line:** Sean's recall is correct. The Qwen team puts SGLang first in
both the readthedocs TOC and on individual Qwen3.6 model cards, and SGLang's
cookbook page exposes more Qwen3.6-specific knobs than vLLM's documented
recipes. **However**, the right choice for the agent *Phase 0* is still **vLLM**,
because a sibling project already serves Qwen3.6-35B-A3B on vLLM and the Phase-0 signal-
detection workload doesn't exercise the discriminating SGLang flags. Switch to
SGLang at Phase-1 if the discriminating features (`preserve_thinking`,
multi-LoRA serving, RadixAttention on shared persona prompts) become load-
bearing — which is plausible.

## TL;DR — Phase 0

- **Use vLLM on a sibling project**, unchanged. Zero new infra, zero deployment friction,
  zero tax on the actual Phase-0 question (does the bare loop produce
  interesting behavior?).
- **Plan to evaluate SGLang at Phase-1 boundary**, when:
  - LoRA-on-best-trajectories starts (multi-LoRA serving matures)
  - Multi-turn `<think>`-block persistence is something we're A/B-testing
    (`preserve_thinking` flag is SGLang-native)
  - Shared persona/system-prompt KV-cache reuse becomes a workload bottleneck
    (RadixAttention's natural turf)

## What I verified

The 2026-05-07 deep-dive recommended:

```bash
vllm serve Qwen/Qwen3.6-35B-A3B \
  --reasoning-parser qwen3 \
  --enable-auto-tool-choice \
  --tool-call-parser qwen3_coder
```

That recipe is **correct verbatim** on the Qwen3.6-35B-A3B HuggingFace model
card. Same parser names, same flags. No drift.

What the 2026-05-07 deep-dive missed: the model card lists SGLang **first**,
with a richer recipe than vLLM's. The `preserve_thinking` flag — explicitly
called out in SGLang's cookbook "for agent scenarios" — has no documented
vLLM-side equivalent. The multimodal-attention-backend knob
(`--mm-attention-backend fa3/fa4`) is SGLang-only on the cookbook side.
EAGLE speculative-decoding knobs are exposed at finer granularity in SGLang.

This isn't fatal to vLLM. None of those knobs are load-bearing for Phase 0
(observation, no scoring, no training, single-instance OK). But they will
matter later, and the serving choice has switching cost.

## Per-axis honest comparison

### Tool-call parsing — parity

Both engines ship `qwen3_coder` parser, same Hermes wire envelope, same
`<tool_call>{...}</tool_call>` JSON. Qwen-Agent layers above either one
identically. No discriminator.

### Reasoning-mode parsing — SGLang wins on agent scenarios

`--reasoning-parser qwen3` is identical on both. The discriminator is
`preserve_thinking`: SGLang exposes it as a per-request flag for "agent
scenarios," which the agent literally is. vLLM doesn't document a server-side
equivalent. A clever client can re-inject prior `<think>` content into the
next turn's prompt on either engine, but SGLang treats it as first-class.

This matters for one of the agent's open architectural levers (handoff-doc lever
#1: `preserve_thinking=True` for fact-graph context). If we want to A/B that
in Phase 0 cleanly, SGLang is the lower-friction path.

### Vision encoder — parity, with knob differences

Both load Qwen3.6's vision tower by default. SGLang documents the multimodal-
attention backend selection (`fa3`/`fa4`); vLLM documents
`--language-model-only` to skip the encoder entirely. Different tradeoffs:
SGLang tunes the multimodal hot path; vLLM offers a way to opt out.

For the agent specifically: if the persona-reactor doesn't need vision (likely
true — personas are conversational), `--language-model-only` is worth ~few-GB
VRAM in the persona-reactor instance.

### Multi-LoRA — SGLang has the more elaborate story

vLLM supports it but its dynamic-loading docs carry a "should not be used in
production unless it is an isolated, fully trusted environment" warning.
SGLang ships Punica / S-LoRA lineage, GPU pinning, eviction policies, and
2026-Q1 work added MoE LoRA support (DeepSeek-V3 MLA, Kimi K2). Qwen3.6-A3B
is the same architectural family, so the path likely transfers, though I
couldn't find a verbatim "Qwen3.6-A3B + LoRA on SGLang" recipe.

For the agent's primary moat (LoRA-on-best-trajectories), this is a real concern
**at Phase-1, not Phase-0**. Phase 0 doesn't train any LoRAs.

### Prefix caching / RadixAttention — directly relevant to the agent, both support

This is the axis I expected to differ most and it really doesn't, as a
binary capability. Both engines reuse KV cache on shared prefixes; SGLang
originated the radix-tree approach (RadixAttention, lmsys 2024 blog claim of
"up to 5x" aggregate vs vLLM v0.2.5). vLLM has caught up
(automatic-prefix-cache, on by config flag, "much higher throughput and much
lower latency" for multi-round conversations per their docs).

For the agent's workload (rollouts that reuse the scenario prompt + tool
definitions + fact-graph projection across many turns), **both engines will
materially help**. Marginal SGLang advantage on raw mechanism age and tuning,
not on the binary.

### Structured output — partial verification

vLLM documents xgrammar + guidance backends, JSON schema / regex / choice /
grammar, per-request granularity, "stable features in OpenAI-compatible API."
SGLang's equivalent page returned 404 in this research pass. SGLang has a
strong xgrammar lineage so it almost certainly supports the same surface;
I'm marking this **partially verified** rather than asserting parity.

For Phase 0 primitive tool-call returns (`assert/retract/query` returning
small structured payloads), this isn't load-bearing — JSON-mode in
chat-completions already gets it.

### MTP / speculative decoding — SGLang exposes more knobs

vLLM: `--speculative-config '{"method":"qwen3_next_mtp",...}'` — wrapped
config blob.
SGLang: `--speculative-algo NEXTN --speculative-num-steps 3
--speculative-eagle-topk 1 --speculative-num-draft-tokens 4` — granular flags
plus EAGLE algorithm + spec-v2 env var.

For Phase 0 (latency uncritical), parity. Production at scale, SGLang's
surface is larger.

### MCP integration — equal (always Qwen-Agent layer)

Neither engine routes MCP. Qwen-Agent does, and works on either.
**Engine choice does not affect the agent's MCP plans.**

## a sibling project-deployment continuity

a sibling project currently runs vLLM serving Qwen3.6-35B-A3B at `35.238.106.195:8000`.
The Phase-0 plan in the handoff doc explicitly says "two Qwen3.6-35B-A3B
instances on a sibling project's vLLM (agent + persona-reactor; same model, different
system prompts)."

**Switching cost to SGLang for Phase 0:**
- Stand up a second VM or repartition a sibling project's GPU
- Re-test with the cookbook recipe (`SGLANG_ENABLE_SPEC_V2=1 sglang serve
  --model-path Qwen/Qwen3.6-35B-A3B-FP8 --reasoning-parser qwen3
  --tool-call-parser qwen3_coder ...`)
- Re-confirm Verifiers + OpenAI client compatibility (both engines serve
  OpenAI-compatible APIs, so this should be a no-op, but "should be" is not
  "is")
- Operational tax: a teammate runs a sibling project's deployment, switching engines is a
  decision that affects more than the agent

**Phase-0 budget:** 2 weeks of observation. Spending any of it on serving-
engine churn is wrong.

**Phase-1 boundary** (after Phase 0 produces signal and we move to scoring +
training): re-evaluate. By then we have:
- Empirical evidence whether `preserve_thinking` matters in our trajectories
- Concrete LoRA-serving requirements
- A real reading on whether RadixAttention's prefix sharing is leaving perf
  on the table

That's the right time to switch, if we switch.

## Multi-instance memory accounting

the agent's RTX 6000 Pro 96 GB target hosts agent + persona-reactor concurrently.
**Neither engine offers shared-weight multi-instance** — pay the weight cost
twice.

| Config | Per-instance weights | Two instances | KV-cache headroom |
|---|---|---|---|
| Qwen3.6-35B-A3B BF16 | ~70 GB | overflow | ❌ |
| Qwen3.6-35B-A3B FP8 | ~35 GB | ~70 GB | ~26 GB |
| Qwen3.6-27B BF16 | ~54 GB | overflow | ❌ |
| Qwen3.6-27B FP8 | ~27 GB | ~54 GB | ~42 GB |

The dense 27B at FP8 leaves materially more KV-cache headroom (42 vs 26 GB).
For long persona contexts (the agent's user-fact-graph projection can be large),
that headroom is real. Combined with the handoff-doc finding that 27B beats
35B-A3B on agentic-coding benchmarks (and matches Sonnet-4.5 on Terminal-
Bench 2.0), the **27B-as-Phase-0-base** decision is increasingly defensible.

The 27B vs 35B-A3B decision is **independent of the vLLM vs SGLang decision**
— both engines serve both checkpoints with the same recipe shape.

## qwen-agent / qwen-code relationship to the serving stack

Both qwen-agent (Python) and qwen-code (TypeScript CLI) **layer over** the
serving engine. They emit OpenAI-compatible chat-completions requests; the
engine handles parsing and inference.

Cloned at:
- `~/src/reference/qwen-agent/`
- `~/src/reference/qwen-code/`

qwen-agent structure:
- `qwen_agent/llm/fncall_prompts/` — exact tool-call template strings the
  model was post-trained against
- `qwen_agent/tools/mcp_manager.py` — MCP integration layer (engine-
  agnostic; routes MCP tool calls regardless of vLLM or SGLang underneath)
- `qwen_agent/tools/` — built-in tool implementations (code-interpreter,
  retrieval, web-search, image-gen, etc.)
- `qwen_agent/agents/` — agent loop implementations
- `qwen_agent/llm/` — LLM client adapters (`oai.py` is the OpenAI-
  compatible client that talks to vLLM or SGLang)

qwen-code structure:
- `packages/cli/` — interactive shell
- `packages/core/` — agent-loop and tool-execution core
- `packages/sdk-python/`, `sdk-typescript/`, `sdk-java/` — programmatic SDKs
- `docs/` — design notes, plans, user-facing docs

Implication for the agent: **Verifiers + OpenAI client + qwen-agent's tool-prompt
templates** form the harness. The serving engine underneath is replaceable
without touching that harness, as long as it's OpenAI-compatible and ships
the `qwen3_coder` tool-call parser. Both vLLM and SGLang clear that bar.

## What would change the recommendation

- **Phase-0 trajectories show `preserve_thinking` materially improves
  multi-turn coherence.** Switch to SGLang at Phase-1 because vLLM doesn't
  expose it as a server-side flag.
- **LoRA serving becomes the bottleneck at Phase-1.** SGLang's S-LoRA /
  Punica lineage and eviction-policy controls are a stronger landing pad.
- **a teammate decides to migrate a sibling project to SGLang for a sibling project's own reasons.**
  the agent rides along — single-engine ops simpler.
- **Phase-0 RadixAttention benchmark shows a 30%+ throughput delta on
  the agent's specific shared-prefix workload.** Switch.

What does **not** change the recommendation:
- Marketing / blog claims of "5x" or "20-80%" — most are stale (2024 vLLM
  baselines) or not the agent's workload.
- TOC ordering — placement signal but not load-bearing.

## Open questions

1. **Does vLLM 0.6+ actually have multi-turn `<think>` block persistence
   plumbing that just isn't documented as a flag?** Check vLLM source. If yes,
   the SGLang advantage on `preserve_thinking` shrinks.
2. **Does SGLang's MoE-LoRA support cleanly extend to Qwen3.6-A3B?** The
   2026-Q1 work landed for DeepSeek-V3 MLA and Kimi K2. Qwen3.6-A3B is a
   different MoE architecture (Gated DeltaNet hybrid). Verify before Phase-1.
3. **What does a sibling project's actual prefix-cache hit rate look like?** If it's
   already ~80% on the persona-reactor's shared system prompt, RadixAttention
   isn't the bottleneck. If it's ~10%, switching engines might unlock a real
   throughput win.
4. **`--language-model-only` on vLLM vs not loading vision in SGLang —
   measure VRAM delta** if the agent commits to text-only persona-reactor.
5. **Cookbook recipe says SGLang FP8 ~35 GB on a single GPU and "fits H100/
   H200/B200" — does it equally fit RTX 6000 Pro 96 GB?** Memory math says
   yes; verify with an actual launch when Phase 0 hardware is provisioned.

## Decision recorded

**Phase 0: vLLM on a sibling project, no change.** The 2026-05-07 deep-dive recipe
stands. Verifiers harness work proceeds against `35.238.106.195:8000`.

**Phase-1 boundary check (after ~2 weeks of Phase-0 observation):**
re-evaluate SGLang against the discriminating axes (preserve_thinking,
LoRA serving, RadixAttention measurement). If two of three matter
empirically, switch.

**Sean's recall verified, not refuted:** SGLang really does have the most
Qwen-specific features in the documentation. We're choosing not to act on
that yet because Phase 0's question isn't "what's the best serving stack" —
it's "does the bare loop produce interesting behavior at all."
