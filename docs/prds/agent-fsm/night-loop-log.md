---
type: prd
status: active
tags: [prd, agent, flow]
---

# Night Loop Log — validate-to-code (2026-06-25 → )

Append-only trail of the overnight autonomous build loop on `feature/agent-fsm`.
One entry per pass: what move (refine/repair/rebuild/optimize/prove/lock-in),
what changed, the LIVE proof observed, and the commit. The user reads this on
waking. Newest at the bottom.

Revert point if the experiment goes sideways: `c84e8fc`.

## Operating frame (the user's standing guidance this run)

- **Mold, don't duplicate.** A lot of effort is already in loop.cljs / turn.cljs /
  agent.cljs — reshape in place. `.disabled`-park anything retired so it can be
  re-examined; no `*-v2`.
- **No data to preserve.** `bin/seon nuke --yes` for a fresh world whenever right.
- **Both REPLs live** for debugging: `seon_cljs` MCP → pod; `seon` MCP → JVM
  (revived this run on 7888). Unified log view: `bin/seon logs` (merged).
- **Prove live, then commit.** Each pass ends in an observed proof + a clean commit;
  revert any pass that didn't actually improve things.

## Passes

### Pass 1 — harness + unified logging (tooling)

- **Move:** lock-in / tooling. Stand up the overnight harness.
- **Did:** revived the JVM server (`bin/seon start jvm`, nREPL 7888) so the `seon`
  MCP REPL reconnects → both REPLs confirmed live. Added a merged log view:
  `bin/seon logs` (no name / `all`) interleaves every process log, source-tagged
  (`[pod]`/`[jvm]`/`[wire-server]`/`[cljs-watch]`) and time-ordered. Created this
  log + the de-risk task list.
- **Live proof:** `bin/seon logs all 40` showed pod heartbeats interleaved with JVM
  post-start checks in correct timestamp order; `seon` + `seon_cljs` MCP evals both
  returned. Survey of current→target code landed (the Phase-1 map).
- **Commit:** (this pass)
