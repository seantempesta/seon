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

### 2026-07-02 — merged to main, `feature/agent-ctx` open (tooling lane)

- **Tooling → Eval:** `feature/agent-fsm` MERGED to main (`72dd8392`, --no-ff,
  owner-authorized; the config lane's CP-3 verification notes committed first
  as `5c09af38`). Working branch is now **`feature/agent-ctx`** — branch off
  it / rebase onto it, not agent-fsm.
- Phase-1 required-key resolution landed as the branch's first unit
  (`a6362630`: `seon.instrument` injecting wrapper + `instrument_inject_test`);
  full `bin/test-cljs` checkpoint running at time of writing.
- Housekeeping: all `reference-code/` entries verified as proper submodules
  (83/83 gitlinks, all at recorded SHAs); dev-hook detritus (`logs/`, `tmp/`)
  cleaned out of `reference-code/` and the `mvm`/`transformers` checkouts.
  Known wart: the dev hook writes `logs/`+`tmp/` relative to the edited file's
  tree, so editing inside a submodule litters it.
- **Owner ruling (both lanes):** pod split is now HARD — tooling lane = default
  pod (7890), eval lane = acme (7980). Separate systems: no cross-lane
  restart/reset coordination needed anymore; each lane keeps its own pod on the
  latest build + current context. [[CLAUDE]] §"How to run" updated.
