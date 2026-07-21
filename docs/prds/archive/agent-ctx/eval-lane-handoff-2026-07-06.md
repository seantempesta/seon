---
type: orchestrator
status: active
tags: [orchestrator, agent]
---

# Eval-lane handoff — remaining benchmark work (written 2026-07-06 for an Opus 4.8 orchestrator)

**You are the eval-lane orchestrator.** Operate per `ORCHESTRATOR.md`:
think/plan/delegate/verify; seon-agent implements (opus, never haiku);
seon-verifier checks. Commit per unit with EXPLICIT pathspecs (shared
multi-agent tree — other lanes have uncommitted changes; never stage
files you didn't touch). Read
`docs/prds/agent-ctx/research/result-driven-benchmark-suite-design-2026-07-05.md`
(THE design — settled, verified ×3, do not re-litigate) and
`docs/prds/agent-ctx/CLAUDE.md` §Settled before anything.

## Standing owner constraints (do not violate)

- **TOKEN THROTTLE (2026-07-05, standing):** refinement over mass testing.
  NO multi-sample bench pass without an explicit owner go WITH a cost
  estimate presented first. Smokes n≤2 are fine.
- **Resume trigger for live runs:** the tooling lane is rebuilding the
  agent tool surface (language tools, and the filed edit-verb ask).
  Bench numbers measured on the OLD surface are stale on arrival — wait
  for their landing, rebuild the image, THEN run.
- Outcome-scored, official oracles untouched, frozen datasets, honest
  ledger rows (INCORRECT/behavior_miss are data), one contract one run
  (no prompt iteration to chase a pass), evidence per run in
  `evals/runs/<date>-<name>/`.
- Subagents PARK on background monitors (3× on 2026-07-05 despite
  warnings). Brief every long-running agent: wait ONLY via Bash
  until-loop + sleep with `timeout: 600000`, chained calls;
  "standing by for a notification" = instant failure. Expect to send one
  un-park SendMessage anyway.

## Where things stand (all committed through `756ab59c`)

- **Design FINAL:** suite = SWE-bench Verified (DeepSeek NT anchor 73.6)
  + terminal-bench (59.1), Seon packaged in docker, `/opt/seon` overlay
  mounted into UNMODIFIED official instance images via inspect-evals'
  `sandbox_config` seam. BFCL kept-unscheduled; tau2 dropped; polyglot
  demoted; thinking-arm skipped. The benched unit is the SWARM (root +
  workers); bench drives the ROOT; done = root terminal reply; goal =
  oracle verdict.
- **Slice 1 SHIPPED (`28a19b1e`):** canonical image `seon:slice1`
  (1.24 GB arm64, `docker/Dockerfile` + `docker/seon-entrypoint`,
  self-contained /opt/seon incl. bundled JRE+Node). Boot ≈15 s; agent
  answered via `POST /agents/run` from inside; db survived
  `docker restart`.
- **Slice 2 SHIPPED (`b0484167`):** official inspect-evals swe_bench runs
  on this host unchanged (arm64 epoch images pull PUBLICLY, no ghcr auth).
  Plain DeepSeek+react solved both easy smoke instances — the baseline
  contrast exists.
- **Slice 3 SHIPPED (`76e25328`):** composition proven — cluster boots
  INSIDE the official instance image; null-run byte-identical ±mounts;
  first honest ledger row `2026-07-05:swe_bench_verified:dev:k1:
  slice3-composition` = INCORRECT/model_miss (real patch, wrong insertion
  point — edited via shell only). `behavior_miss` class live in
  scorecard.py.
- **Debt unit SHIPPED (`6f9efafe`):** bench pods get writable
  `SEON_FS_ROOT=/testbed` (entrypoint honors env overrides; bench compose
  bind-mounts the fixed entrypoint); interim run bounds transacted
  post-mint (`:seon.agent.run/default-turn-limit`/`default-deadline-ms`
  via in-container wire REPL, `:turn-limit` observed live); default-deny
  egress (internal network + socat relay aliased `api.deepseek.com`,
  allow+deny both proven); catalog folded to ONE `BENCHES` BenchSpec
  registry. pytest 230/0.
- **Slice 4 PAUSED (`756ab59c`):** frozen seeded n=10 dev slice IS in
  `freeze.py` + `evals/datasets.lock`
  (`evals/runs/2026-07-05-slice4-dev-pass/dev-ids.txt`; excludes the two
  spent smoke instances) + a scorer `harness_error` fix in
  `swebench_arm.py`. NO smoke ledger rows (nothing scored before the
  pause). Docker assets in place: `seon:slice1`, volume
  `seon-runtime-slice3`, instance images per `pull-stats.txt`,
  venv `tmp/slice2-venv/`.

## Cross-lane asks FILED (watch coordination.md; do not build these)

1. **Structural edit verb in `seon.agent.fs`** (P0, evidence attached in
   the 2026-07-05 channel entry) — THE score lever; when it lands, the
   same frozen slice becomes a before/after A/B.
2. **Entrypoint contract** (design §9) — `docker/seon-entrypoint` is the
   eval-lane prototype; tooling takes formal ownership (fold the
   env-overridability fix).
3. **Cluster-level run-bounds config** — interim post-mint transact works;
   the clean config is theirs.
4. Fabricated-echo render lever (standing).

## The remaining work, in order

### Multi-arch build — ✅ DONE 2026-07-06 (`52e25b6f`)

`docker/Dockerfile` is now `TARGETARCH`-parameterized; BOTH arches build +
boot + answer (arm64 native 86s, amd64 emulated-under-Rosetta 197s; first
amd64 Seon run ever, no src change). Overlay volumes: `seon-runtime-arm64`
(SWE-bench) + `seon-runtime-amd64` (TB-2, unblocks its arch blocker).
Rosetta re-verify (`4d8809b1`) proved TB-2 amd64 scoring is faithful.
FOLLOW-UP (small, do in Unit A): repoint the swebench arm from the old
`seon-runtime-slice3` → `seon-runtime-arm64` (both arm64; the new one is
off the current Dockerfile). TB-2 live Seon drive is now UNBLOCKED
(amd64 overlay exists) — a tb2 composition unit can slot in like slice 3
did for SWE-bench.

### Unit A — image repin (AFTER tooling's new verbs land; no owner gate)

Rebuild `docker build -t seon:<newtag> .`, record new digest; re-extract
the overlay volume (see slice-3 README for the exact extraction command);
update the pinned digest where `swebench_arm.py`/`datasets.lock
image_pins` reference it; re-run the slice-3 null-run comparison ONCE
(cheap, no agent) to re-prove non-interference on the new tree; 1-sample
smoke (n=1 from the frozen slice) to confirm the new verbs work
in-container. Commit. Cost: ~1 subagent + 1 trajectory.

### Unit B — the n=10 dev pass (OWNER GO REQUIRED — present cost first)

`swe_bench_seon` task over the frozen 10, k=1, turn_limit 40,
solve_timeout 1200 s, egress default-deny, concurrency 1. Cost estimate to
present: ~10-40 min wall/sample (slice 3: easy sample = 3 min; budget
worst-case 1200 s), DeepSeek API ~150-500k tok/sample (cheap $), the real
cost = ~1-2 driving subagents (~200k orchestrator-tokens each). Append
per-sample rows + the dev mean; write the TURN-BUDGET MEMO (per-sample
turns/limit, wall, closed_reason → verdict on turn_limit 40 + 1200 s and
whether the run-bounds ask escalates). Honest mean is the baseline — do
NOT iterate prompts.

### Unit C — ✅ DONE 2026-07-06 (`09e6c1f9`): baseline mean **0.700** (7/10)

mini-swe-agent 2.4.5, model **deepseek-v4-pro** (NOT deepseek-chat — that
alias now serves v4-flash, a cheaper tier; the pod's real wire model is
v4-pro, `src/seon/ai.cljs:225` — match it or the delta is confounded).
Official swebench grader on the arm64 images via `score_official.py`
(swebench 4.1.0 `run_evaluation` hardcodes x86_64 — Unit B must score via
the inspect_evals scorer, which already works on this host). Ledger join =
row `swe_bench_verified` + `attribution.arm` (`baseline-mini-swe` vs
`seon-overlay`). All 3 misses clean model_miss; no flakes. Original spec:

### Unit C (original spec) — baseline arm (same go can cover it; run on the SAME slice)

mini-swe-agent (NOT vendored; pip-pin it, record version) over the SAME
frozen 10 instances + SAME pinned images + same model
(`openai-api/deepseek/deepseek-chat`). This is the measured
scaffold-vs-scaffold delta — the thesis number. Ledger row
`swe_bench_verified_baseline` (or notes-tagged; keep row naming consistent
with scorecard.py conventions). Report Seon-vs-baseline honestly.

### Unit D — ✅ DONE 2026-07-06 (`fe2f828a`, opus build + opus quality review)

`seon_inspect.tb_agent.SeonAgent` proven through tb's unmodified harness
(hello-world n=1, their verdict; put_archive injection + bundled-node
exec_run door). Also landed from the review: `bench_common` (shared
run-bounds/wire-REPL) + `deadline_below_door` fix in BOTH arms (+ guard
tests; the deadline==door coin-flip could have inflated the mean).
OPEN from this unit: TB 2.0 registry pin (owner call — vendored pin has
no 2.0; comparability blocked until bumped); the FABRICATION-class defect
handoff to tooling (agent claimed a write that never landed —
coordination.md 2026-07-06 entry); hardening REQUIRED before
concurrency >1: sample_port probe/claim allocation + egress relay
multi-A-record/supervision (comments in swebench_arm.py). Original spec
follows for reference:

### Unit D (original spec) — terminal-bench adapter (design §4; build ≈1 unit, no big runs)

Our pod as a custom `BaseAgent` via `--agent-import-path`
(`terminal_bench/agents/agent_factory.py:64-79`); `perform_task` gets the
task container (`TmuxSession.container`) → put_archive/exec_run the
/opt/seon runtime + boot + drive `POST /agents/run` (design §2b/§4 has
the verified mechanics). CAVEAT: the vendored pin's registry has NO
Terminal Bench 2.0 entry (the 59.1 anchor) — resolving the pin is part of
this unit; comparability claims wait on it. Acceptance: ONE task
end-to-end scored by THEIR unmodified harness. Runs beyond 1-2 tasks =
owner-gated.

### Unit D2 — ✅ TB 2.0 upgrade DONE 2026-07-06 (`ae346e5c`); live drive arch-blocked

TB 2.0 = the **Harbor** harness (harbor 0.17.1, dataset
terminal-bench/terminal-bench-2, 89 tasks — NOT a terminal-bench pkg
release). `tb2_agent.py` (harbor BaseAgent over the same ONE mechanism)
unit-proven; splits frozen dev=10/milestone=25/test=54, corpus sha-pinned.
**BLOCKERS for a live TB-2 Seon drive:** (a) all 89 prebuilt images are
amd64-ONLY → needs the amd64 `/opt/seon` overlay — fold into Unit A's
rebuild as a `buildx` multi-arch build; (b) faithful scoring on this Mac
needs Docker Desktop **Rosetta ON** (qemu segfaults pytest) — owner
setting, docker restart kills running containers, coordinate; or run on
native amd64 hardware.

### Unit E — restart-resume rows (design §5; after B)

Kill the pod mid-task (the bench container survives — it is bench-owned),
resume the same agent, official oracle scores unchanged. Compose with 2-3
instances from the frozen slice. This is the claim nobody else makes;
worth a dedicated owner conversation before spending.

### Unit F — milestone runs (OWNER-GATED, budget conversation required)

Full-500 / larger tb sets only with an explicit owner budget. Never from
this handoff alone.

## Operational cautions

- Pods: tooling lane owns 7890, acme (7980) is eval-lane's; bench
  containers use 17900-17999. Never `bin/seon start/stop/restart` the
  default cluster.
- The socat relay resolves the model-API IP per-sample at compose time —
  long runs could lose a rotated endpoint (noted in `swebench_arm.py`
  docstring).
- `.eval` logs under `evals/runs/*/logs/` need `git add -f` (global
  `logs/` gitignore).
- `evals/scorecard.jsonl` is append-only; the pass^k alarm
  (`tests/test_scorecard_alarm.py`) trips pytest on >0.10 dev drops.
- Update THIS file + `docs/prds/agent-ctx/coordination.md` + the memory
  file (`project_eval_lane_session_2026_07_02.md`) as units land — the
  same discipline as code.
