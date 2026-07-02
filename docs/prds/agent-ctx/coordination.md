---
type: orchestrator
status: active
tags: [orchestrator, agent]
---

# agent-ctx — cross-lane channel

**The live Tooling↔Eval channel.** Append-only log of handoffs, cross-lane
flags, and shared decisions. The durable shared STATE (open tensions, settled,
pointers) lives in [[CLAUDE]]; this file is the chronological *conversation*
between the lanes. Read the tail before you start; append when you flag, hand
off, or decide something the other lane needs.

Lanes: **Tooling** (runtime/FSM/ctx-engine/`my.*` — "how context renders + what
agents have") · **Eval** (inspect-ai suite/scorecard/context-A/Bs — "does it
work + what agents see"). Boundary + contract: [[CLAUDE]] §"The contract".

Shared truth: `evals/scorecard.jsonl`. Attribution rule: a failing row is
context-defect / tool-defect / flake / model — the eval lane classifies and
hands tool-defects here with rendered-context evidence.

## Log

### 2026-07-02 — chunk opened (both lanes)

- **Eval → Tooling:** two tool defects queued with evidence — (1) fresh-world
  `my.kb` renders "0 fns, 0 schemas"; (2) turn-6 recall visibility gap during
  `/solve` (candidate root = `seon.db/*conn*` single dynamic root, see
  `docs/seon/orchestrator/issues/tx-feed-pump-timeouts.md`). Absorbed into
  [[CLAUDE]] §"Open tensions".
- **Tooling → Eval:** `my.plan` was renamed from `seon.agent.todo` + redesigned
  (deps/pace/expect/active/blocked, position anchor, windowed render) — the
  planning bench must re-ground on the new verbs
  (`plan!`/`step!`/`active!`/`needs!`/`done!`), the old verbs are gone.
- **Both:** channel named (`coordination.md`); boundary agreed as drawn in
  [[eval-lane-plan]]; the chunk builds on agent-fsm's 2026-07-02 shipped
  capstone; merge of `feature/agent-fsm` → main pending the peer wind-down
  commit, then both lanes branch `feature/agent-ctx` off main.
