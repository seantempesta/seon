---
type: research
status: completed
tags: [research, agent]
---

# Pre-slice-4 debt unit — fs grant, run bounds, egress allowlist, BenchSpec fold (2026-07-05)

Owner-approved four-item debt unit on the SWE-bench composition arm
(design `docs/prds/agent-ctx/research/result-driven-benchmark-suite-design-2026-07-05.md`
§2/§7/§9; extends slice 3, `evals/runs/2026-07-05-slice3-composition/`).
All items landed in `src-inspect-ai/` + `docker/seon-entrypoint`; zero
`src/seon/` edits. Live proofs below ran on a throwaway composed deployment
(`preslice4-probe`, instance image sympy__sympy-22914, overlay volume
`seon-runtime-slice3` — both at the slice-3 pins), torn down after.

## Item 1 — workspace-rooted writable fs grant (P0) — DONE

- **Deviation from the item's premise (named, honest):** compose-level env
  alone could NOT override the fs grant — the pinned entrypoint HARDCODED
  `SEON_FS_ROOT="$SEON_HOME"` / `SEON_FS_READ_ONLY=1` in its `start_pod`
  env block (`docker/seon-entrypoint:108-109`), clobbering any container
  env. Fix: the repo entrypoint now honors env overrides
  (`${SEON_FS_ROOT:-$SEON_HOME}` / `${SEON_FS_READ_ONLY:-1}`, defaults
  identical), and the bench compose bind-mounts that ONE file over the
  pinned volume's copy (`docker/seon-entrypoint:/opt/seon/seon-entrypoint:ro`)
  — no image rebuild, volume + image digests untouched. The mounted file's
  sha256 is stamped into sample metadata (`seon_entrypoint_sha`); this
  run's: `148305fb84b06ff23fb418015788298981c630aa9d8918a3cb7f8d5a3f1dc509`.
- Bench compose env: `SEON_FS_ROOT=/testbed`, `SEON_FS_READ_ONLY=0`
  (`seon.agent.fs.internal` treats read-only as `= "1"` and seeds the one
  allowed root from `SEON_FS_ROOT`).
- **Live proof (grant):** the running pod process inside the composed
  container carries `SEON_FS_ROOT=/testbed` + `SEON_FS_READ_ONLY=0`
  (`fs-grant-env-proof.txt`, read from /proc/PID/environ).
- **Live proof (write):** one trivial `/agents/run` task ("create
  /testbed/SEON_FS_PROBE.txt containing ok via your fs verbs, then reply
  done") → run closed `:completed` with a terminal reply (7 turns,
  `fs-probe.json`) and `cat /testbed/SEON_FS_PROBE.txt` in-container
  returned exactly `ok`. (Caveat noted: the reply TEXT was the resumed
  summary of the earlier turn-limited probe — same reused root agent
  finishing its interrupted work first; the write + terminal close are the
  probe's substance. Real bench samples get a fresh container each.)
- **Contract truthfulness:** `task_contract` now states the fs capability
  ("your file verbs are rooted at /testbed with write access … edit the
  repository files directly") — the every-check-stated law.

## Item 2 — interim run bounds via the wire REPL (P1) — DONE

- Attrs (read from `src/seon/agent/run.cljs:263-270`, not guessed):
  `open-run!` seeds each run's bounds from the AGENT-entity attrs
  `:seon.agent.run/default-turn-limit` / `:seon.agent.run/default-deadline-ms`
  (falling back to the legacy `:seon.agent/…` attrs, then consts 20/600000).
- Mechanism (the `apply_ai_config` precedent, `cluster.py:121`): after boot,
  before the task posts, transact the two attrs onto `[:seon.agent/id "root"]`
  over the IN-CONTAINER wire REPL (loopback :7891) — delivered by
  `sandbox().exec` of the bundled node speaking the socket protocol
  (`swebench_arm.NODE_WIRE_REPL_JS` + `run_bounds_form`; install-if-missing
  `:db.type/long` schema, sentinel'd read-back, fail-loud mismatch, retries
  while the root agent is minting). Host-side sentinel parsing extracted to
  `cluster.parse_wire_json` (shared, not duplicated).
- Parameters: `seon_swebench_solver(turn_limit=40, deadline_ms=None)` /
  `swe_bench_seon -T turn_limit=… -T deadline_ms=…`; deadline defaults to
  the solve timeout (900000 ms). Applied bounds recorded per sample in
  `state.metadata["pod_run_bounds"]` (lands in the run's .eval evidence).
- **Live proof (`turn-limit-probe.json`):** bounds set to turn_limit=2 /
  deadline 900000 (wire read-back `{'turn_limit': 2, 'deadline_ms': 900000}`),
  then a deliberately multi-step task through the door → the run came back
  `closed_reason ":turn-limit"`, `turns 2`, `timed_out` absent, empty reply —
  exactly the `behavior_miss` envelope (`scorecard.behavior_miss(":turn-limit","")
  == True`; the classification path is unit-covered in
  `tests/test_swebench_arm.py`). Bounds then restored to 40/900000
  (read-back verified) for the fs probe.

## Item 3 — model-API-only egress (P1, the DEFAULT) — DONE

- Shape (as designed): the task container joins ONLY an `internal: true`
  network; an `alpine/socat` relay sits on internal + egress networks,
  carries the compose network-alias `api.deepseek.com` on the internal net,
  and TCP-passthrough-forwards :443 to the REAL endpoint — TLS untouched
  (SNI/cert intact), zero pod config. Two adjustments discovered building it:
  - The relay forwards to a HOST-RESOLVED IP (fresh per sample at compose
    generation, stamped `seon_model_api_ip`), because on the internal
    network the NAME aliases the relay itself (a resolve-by-name forward
    would loop).
  - The relay also forwards the published pod port (`ports:` on the relay,
    `TCP:default:7890` inward) — an internal-only service cannot publish
    host ports.
- No cleaner inspect-ai seam exists (inspect consumes the compose file
  as-is), and the pod's undici-based client does not honor proxy env — the
  relay stands. Escape hatch: `open_egress=True`
  (`swe_bench_seon -T open_egress=true`), recorded per sample as
  `seon_open_egress`; the null-run keeps `network_mode: none` untouched.
- **Live proof (`egress-probes.txt`):**
  - ALLOW: both DeepSeek-driven probes above completed real multi-turn runs
    through the door — model API reachable only via the relay
    (`api.deepseek.com` resolves in-container to the relay's internal IP
    172.19.0.2).
  - DENY: `https://example.com` → `URLError … Temporary failure in name
    resolution`; raw TCP to `1.1.1.1:443` → `OSError [Errno 101] Network is
    unreachable`.

## Item 4 — BenchSpec fold (P2) — DONE

- `catalog.BENCHES: dict[str, BenchSpec]` is now the ONE per-bench wiring
  surface: task ref (`module`/`attr`), arm `kind` (`"case1"`/`"swebench"`),
  `adapter` hook, `default_task_kwargs` thunk. The three old registries
  (`CASE1_BENCHES`, `BENCH_ADAPTERS`, `BENCH_DEFAULT_TASK_KWARGS`) are
  DELETED — no aliases, no shims (a test asserts they're gone). The
  swebench arm is registered (`swe_bench_verified`, kind `swebench`);
  `load_bench_task`/`run_bench` refuse it with a pointer to its own driver,
  and `tasks/swe_bench_seon.py` loads the upstream task via the same spec.
  `run_bench`/`load_bench_task` signatures unchanged; README + freeze.py
  references updated.

## Files (this run dir)

- `egress-probes.txt` — alias resolution, relay IPs, deny probes, entrypoint sha
- `turn-limit-probe.json` — the `:turn-limit` door envelope (bounds=2)
- `fs-probe.json` — the fs-write run's door envelope
- `fs-grant-env-proof.txt` — pod process env inside the container
- `probe-compose.yaml` — the exact generated compose used
- `probe-pod-entrypoint.txt`, `probe-relay.txt` — container logs

## Suite

`src-inspect-ai` pytest: **230 passed** (was 221; +9 covering the fs/egress
compose shape, the contract statement, `run_bounds_form`/`apply_run_bounds`,
`parse_wire_json`, and the BenchSpec fold).
