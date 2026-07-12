---
type: research
status: completed
tags: [research, agent]
---

# Slice 1 — canonical Seon docker image (evidence)

First boot of the full Seon stack on linux, ever. All acceptance criteria
of design §8 slice 1 OBSERVED (not inferred). Design:
`docs/prds/agent-ctx/research/result-driven-benchmark-suite-design-2026-07-05.md`.

## What ran

- **Image:** `seon:slice1`, 1.24 GB, arm64,
  id `sha256:63db32776190f88411542a1415a6eb44bdb17c6b809f2d1fdab39b6a2c0a0557`
  (`image-id.txt`). Multi-stage `docker/Dockerfile`; slim foreground
  `docker/seon-entrypoint` (wire-server → ready-gate → pod, signal
  forwarding). `/opt/seon` self-contained incl. bundled JRE + Node.
- **Container:** `seon-slice1`, volume `seon-slice1-data` → `/seon-data`,
  pod port published `127.0.0.1:7999 → 7890`, `SEON_BIND=0.0.0.0`.

## Acceptance criteria — all pass

- **Builds:** `build-log-cached.txt` (tail; full build succeeded to
  `seon:slice1`).
- **Boots to ready:** `fresh-boot-log.txt` — `auto-boot ready`, replay
  11/11 ok, instrumentation 600 wrapped / 0 bad. **Boot ≈ 15 s observed**
  (container start → HTTP 200 on the published port).
- **Real agent task from inside (LLM egress proven):**
  `agents-run-reply.json` — `POST /agents/run` "what is 12 × 13" →
  reply `"156"`, `closed_reason :completed`, deepseek-v4-pro, 12.1 s,
  2 turns.
- **DB survives container replacement/restart:** boot log shows
  `:resumed ["Yxu-2607051816" "root" "uBb-2607051814"], :minted []` — the
  volume-backed store came back with the core NOT re-seeded and all agents
  intact. Stronger memory proof: `agents-run-reuse-after-restart.json` —
  the SAME agent (`agent_id` reuse) after restart answered "what was the
  original question I asked you?" correctly from its db
  (`The original question was: **What is 12 × 13?** The answer is
  **156**.`).

## Linux portability fixes (loud, in-source — the slice-1 payoff)

- `src/seon/web/serve.cljs` — `bind-host`: default `127.0.0.1` unchanged;
  `SEON_BIND=0.0.0.0` for containers (docker's published-port forward
  targets the container interface, never its loopback).
- `src/seon/config.cljs` — skills dir resolves via
  `platform/artifact-path` (a checkout artifact must resolve under
  `SEON_RUNTIME_ROOT` in a containerized pod; CWD-relative behavior
  byte-identical when unset).

Both are tooling-reviewable surfaces — flagged in `coordination.md`.

## Notes

- The build agent was interrupted by a session limit after the proofs ran;
  the orchestrator re-observed a fresh boot (15 s) and assembled this
  evidence. The `build-log-cached.txt` tail is from the final (cached)
  build invocation.
- Container/image left in place (`seon-slice1` stopped, exit 0) for
  slice 2/3 reuse.
