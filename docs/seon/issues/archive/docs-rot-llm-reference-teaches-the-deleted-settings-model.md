---
type: issue
status: resolved
severity: blocker
tags: [issue, reference, agent, config]
---

# Rewrite the LLM reference around the fresh settings model

## Problem

`docs/seon/reference/llm-adapters.md` calls itself the complete maintained
provider/configuration reference, but it interleaves the deleted pod adapter
model with fresh facts and now directly contradicts the 2026-08-01 uniform
per-agent settings ruling. Agents following it will configure attributes and
environment variables the fresh loop does not read, omit supported per-agent
overrides, and look for provider namespaces that exist only in the quarry.

## Evidence

- `llm-adapters.md:9-52` presents `seon.ai.openai-compat.core`,
  `seon.ai.anthropic.core`, Node SDK leaves, descriptor rows, and pod-only
  local-worker registrations as the live two-core topology. Fresh `src/`
  contains only `src/seon/ai.cljc`; the named hosted-core namespaces are
  absent.
- `llm-adapters.md:59-117` teaches `SEON_AI_*` seed-once synchronization into
  a `:seon.ai/config` singleton and old `:seon.ai/*` config attributes. Fresh
  dials are `:seon.config.ai/*` in `resources/seon/schema/config.edn` and
  `config/default.edn`, reconciled through `seon.config`.
- `llm-adapters.md:433-441` says fresh Seon has no per-agent provider or
  thinking override and that settings resolve at arm time. Every registered AI
  dial now carries `:seon.config/per-agent true`; `src/seon/ai.cljc:103-120`
  merges the effective cluster settings with an agent-entity overlay, and
  `src/seon/cluster/loop.cljc:962-978` resolves both from one immutable
  database value per turn.
- `llm-adapters.md:443-497` tells operators to point “the pod” at a local model
  with environment variables and to transact a deleted
  `:seon.ai/id "config"` row using nonexistent `seon.db/transact!`.
- `llm-adapters.md:536-575` documents deleted wire/evaluation dials and stores
  model metadata on `:seon.agent.turn/*`. Fresh evidence is one
  `:seon.ai/attempt` entity with `:seon.ai.attempt/settings-edn` and
  `:seon.ai.attempt/usage-edn` at
  `resources/seon/schema/ai.edn:154-188`, committed by
  `src/seon/cluster/loop.cljc:760-825`.
- The shipped default is explicit non-thinking with a 180000 ms remote-call
  deadline (`config/default.edn:143-183`), while the reference's defaults and
  thinking section still state default-on/high and 60000 ms.

The reader chain is authoritative: root `AGENTS.md:1157` names this page for
provider details; `third-party-integration.md:38` calls it the single
maintained model catalog and configuration reference; the active AI-settings
design and multiple current research notes cite its descriptor/config
sections; and `src-old/seon/ai/AGENTS.md` tells maintainers to update its model
catalog. The outer root and integration guides therefore promote a page whose
fresh and quarry halves disagree.

## Owner

`docs/seon/reference/llm-adapters.md` owns current operator/provider guidance.
The fresh authority is `src/seon/ai.cljc`,
`resources/seon/schema/{ai,config}.edn`, `config/default.edn`, and the loop's
per-turn resolution/attempt commit.

## Acceptance

- Split maintained fresh guidance from explicitly historical pod/local-worker
  material; no paragraph mixes both as one live surface.
- Every config attribute, environment variable, default, request field, and
  persistence location maps to an actual fresh producer and consumer.
- Per-agent inheritance and per-turn resolution match the one
  schema-derived overlay mechanism and ruling #34.
- Root, downstream integration, AI-setting plans, and quarry runbooks are
  chased so none re-promotes a deleted configuration recipe.

## Progress — 2026-08-01

Commit `e0c937757` replaced the mixed pod/fresh page with one maintained
reference for `seon.ai`, the `:seon.config.ai/*` dials, schema-derived
per-agent overlays, one-resolution-per-turn, OpenAI-compatible wire mapping,
payment-safe disposition, streaming, and durable attempt facts. Commit
`116cc7854` also rewrote the downstream integration page around that fresh
configuration surface.

Proof:

- `seon.dev.markdown/validate-file` reports no violations.
- Every documented dial exists in the admitted schema resources
  (`resources/seon/schema/config.edn`, with the literal `no-auth` dial in
  `resources/seon/schema/ai.edn`); every shipped value is copied from
  `config/default.edn`.
- Resolution and attempt claims were checked against
  `src/seon/ai.cljc`, `src/seon/cluster/loop.cljc`,
  `src/seon/schema/edn.clj`, and `resources/seon/schema/ai.edn`.
- Searches find the deleted adapter namespaces, singleton, pod workers, and
  `SEON_AI_*` variables only in explicit statements that they do not exist.

Protected inbounds remain before this issue can close:

- `src-old/seon/ai/AGENTS.md:53` directs quarry maintainers to a deleted
  `llm-adapters` "Model catalog" section.
- `docs/prds/sci-execution-runtime/plan/ai-settings-design-2026-08-01.md`
  lines 40, 87, 89, 408, and 512 cite the deleted descriptor-row sections as
  current grounding.
- `docs/prds/sci-execution-runtime/plan/unsettled.md:1827` cites a deleted line
  range for the streaming invariant.

This lane is forbidden from editing `docs/prds/**` and does not own the quarry
runbook.

## Resolution — 2026-08-01

Commit `3d706726e` closed the protected inbound chase after the maintained
reference rewrite in `e0c937757` and the downstream integration rewrite in
`116cc7854`. The active AI-settings design and working edge now cite the
maintained reference's current configuration, provider-scope, attempt-fact,
and request/response sections instead of deleted line ranges. The generate-code
runbook labels its named-profile proposal historical and points to the current
schema-derived per-agent/per-turn section. The pod-era AI runbook now marks its
former model-catalog pointer as historical quarry.

Proof:

- searches over the root authority, maintained downstream integration guide,
  active AI-settings plan, working edge, generate-code runbook, and quarry AI
  runbook find no pointer to a deleted `llm-adapters` line range or “Model
  catalog” section;
- the maintained reference's deleted adapter namespaces, singleton, and
  `SEON_AI_*` variables occur only in explicit statements that those surfaces
  do not exist; and
- `seon.dev.markdown/validate-file` reports no violations for every changed
  `docs/**` inbound.
