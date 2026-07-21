---
type: research
status: active
tags: [research, agent]
---

# Calibration run — /solve concurrency ceiling + per-row latency → timeouts (2026-07-02)

> Eval-lane step 1 (roadmap). Question: what is the pod `/solve` door's
> practical concurrency ceiling, what are the per-row latency medians, and what
> harness timeouts follow. All numbers below come from live runs against the
> acme pod (7980, DeepSeek) executed for this calibration; every pod-behavior
> claim cites an observed `logs/acme/pod.log` line or an eval-log sample row.

## TL;DR

- **Per-pod concurrency ceiling = 1.** At effective concurrency 2, ~15% of
  samples hard-fail (conn-swap collision: datahike cas `:entity-id/missing`
  write-error → agent loop halts `superseded` → the sample burns its FULL
  300s timeout and reports `turns=0`), plus one observed post-reply turn-close
  error. Pod-side errors + bimodal latency ⇒ not graceful. c=4/c=8 were not
  attempted: c=2 already fails the "zero harness-side errors" criterion, and
  the mechanism (single root `seon.db/*conn*` swapped per sample in
  `serve.cljs solve-once!`) makes higher levels strictly worse. **Parallelism
  = more pods, one `SEON_SOLVE_URL` each** (the eval-lane-plan's
  bench-cluster-N runner), never more samples per pod.
- **Latency (gsm8k, DeepSeek, n=14 completed):** median **40.7s**, p90 **~70s**,
  max **73.8s** per sample (1–2 turns). Clean-serial-only subset (n=5): median
  30.1s. A 6-turn multi-turn smoke ran 96.5s.
- **Timeouts wired into `src-inspect-ai/` (`seon_inspect/config.py`):**
  `QA_SOLVE_TIMEOUT_S = 240` (>3× p90 and >3× observed max for QA rows),
  `DEFAULT_SOLVE_TIMEOUT_S = 300` (general default — the surveyed agentic rows
  ranged 51→300s and get their own calibration when their generators land),
  `POD_MAX_SAMPLES = 1`, HTTP read budget = pod budget + 30s margin,
  `total_run_bound_s(n, epochs, timeout)` = n·epochs·(timeout+30) as the
  outer watch bound. All overridable per-run; per-sample
  `metadata["timeout_ms"]` wins.
- **Timeout honesty held under collision:** both hard-failed samples returned
  `timed_out=true, closed_reason="timeout"` — and their `match=I` scores
  dragged accuracy to 0.875, which is live proof for the eval-design rule that
  flakes must be classified and EXCLUDED from capability means.
- Bonus fix: `run_bench(solve_url=…)` was a no-op (env read at import time in
  `solver.py`); endpoint/timeout resolution is now call-time via the config
  surface, with a regression test. `run_bench`'s `os.environ["SEON_SOLVE_URL"]`
  write side effect is deleted (no reader depended on it — the explicit
  argument is the one channel; env never shadows config).

## Method

Matrix run through `seon_inspect.catalog.run_bench("gsm8k", …)` (the bench's
own numeric-match scorer, host-side) against `http://127.0.0.1:7980/solve`,
`solve_timeout_s=300`, epochs=1. Oracle/pipeline gate: the package pytest
suite (includes the oracle liveness gate; 22 passed) ran before the matrix.
Rendered-context spot-check before any batch: `logs/acme/turns/
hDB-2607021403/1-TDi-2607021403/prompt.txt` — coherent REPL context,
~139KB ≈ 35k est. tokens, grants live, transcript sane.

The concurrency-2 level was measured from an OBSERVED overlap: a second
serial client (a task-runner respawn of the same 8-sample run across a
harness session restart) interleaved with the original for ~10 minutes
(18:20:23–18:30:35Z), giving effective concurrency 2 over ~13
sample-executions — same request shape a deliberate `max_samples=2` run
produces (two concurrent POST /solve in flight), with per-sample results
recorded on both sides. Escalation to 4/8 was skipped per the stop-at-first-
failing-level rule (pod-side write errors already present at 2).

## Latency table

Per-sample pod-side `elapsed_ms` (from eval-log sample metadata; agent ids
cross-checked against `POST /solve OK` log lines):

### Concurrency 1 (clean serial — no other sample in flight)

| sample (agent) | elapsed s | turns | closed |
|---|---|---|---|
| pcd-2607021418 (warmup) | 42.4 | 2 | :completed |
| wRa-2607021419 | 30.1 | 1 | :completed |
| prw-2607021420 | 16.6 | 1 | :completed |
| kGf-2607021430 | 42.2 | 1 | :completed |
| ume-2607021431 | 18.1 | 1 | :completed |

n=5 · median **30.1s** · max 42.4s · errors 0 · timeouts 0.

### Effective concurrency 2 (overlap window 18:20:23–18:30:35Z)

| sample (agent) | elapsed s | turns | closed |
|---|---|---|---|
| Dhv-2607021420 | **303.8** | 0 (log shows 3 ran) | **timeout** (collision) |
| ItA-2607021421 | 28.7 | 2 | :completed |
| Byy-2607021422 | 73.8 | 1 | :completed |
| NEv-2607021423 | 70.7 | 2 | :completed |
| xPd-2607021424 | 39.2 | 2 | :completed |
| loj-2607021425 | **300.1** | 0 | **timeout** (collision) |
| IHj-2607021425 | 31.6 | 1 | :completed |
| tDT-2607021426 | 60.4 | 1 | :completed (post-reply turn-close error) |
| YFP-2607021427 | 54.2 | 1 | :completed |
| Ehk-2607021428 | 24.1 | 1 | :completed |
| unx-2607021428 | 45.2 | 2 | :completed |

n=11 completed=9 · completed median **45.2s** (vs 30.1 clean — ~50%
inflation) · hard-fail 2/13 sample-executions ≈ **15%**, each burning the
full 300s budget.

### Pooled completed gsm8k (n=14, both windows)

Sorted s: 16.6 18.1 24.1 28.7 30.1 31.6 39.2 42.2 42.4 45.2 54.2 60.4 70.7 73.8
→ median **40.7s** (`statistics.median`, mean of the 7th/8th values 39.2 and
42.2) · p90 **≈70s** · max **73.8s**. Multi-turn reference: the pre-matrix
grants smoke (hDB-2607021403, 6 turns) ran 96.5s. Errata: the first draft
reported 42.3s — a hand-computed off-by-one that averaged the 8th/9th sorted
values instead of the 7th/8th; corrected here and everywhere derived.

Row accuracy for the recorded 8-sample eval: 7/8 = 0.875 (`match/accuracy`),
the single miss being the Dhv collision timeout — a harness artifact, not a
model miss.

## Ceiling verdict: 1 per pod (evidence)

Mechanism (code): `serve.cljs solve-once!` saves `db/*conn*`, `set!`s it to a
fresh scratch conn per sample, restores in `finally` — documented SERIAL-ONLY
in the fn itself. One root binding ⇒ two in-flight samples share a world.

Observed at concurrency 2 (verbatim `logs/acme/pod.log`):

```text
2026-07-02T18:21:30.307Z INFO [seon.web.serve] POST /solve — minted scratch agent {:agent "ItA-2607021421", ...}   ; while Dhv mid-turn-2
2026-07-02T18:21:42.188Z :error datahike.db.utils [146 8] Nothing found for entity id [:seon.agent/id "Dhv-2607021420"]
2026-07-02T18:21:42.205Z :error datahike.writer :datahike/write-error  ; :db.fn/cas [:seon.agent/id "Dhv-2607021420"] :seon.agent/run …
2026-07-02T18:21:42.237Z INFO [seon.agent.loop/Dhv-2607021420] halt superseded — a newer run owns the agent
2026-07-02T18:25:27.364Z INFO [seon.web.serve] POST /solve OK {:agent "Dhv-2607021420", :turns 0, :evals 0, :elapsed-ms 303786}
```

Same signature repeated for loj-2607021425 (mints 67ms apart at 18:25:35;
cas write-error 18:26:43; 300.1s timeout, turns=0). Near-miss: tDT-2607021426
returned OK at 18:27:15.429 but its turn-0 close then hit
`Nothing found for entity id [:seon.agent.turn/id "LSu-2607021426"]`
(`run-turn! error`) — the reply happened to be read before the world swapped.

Failure anatomy: the newer sample's `set!` swaps the world under the older
in-flight sample → the older agent's entities vanish from the CURRENT conn →
its turn-close cas write-errors, its loop halts `superseded`, the host-side
idle-poll goes blind (polls the new world) and burns the entire `timeout_ms`,
and the returned `turns/evals` are queried against the wrong world (0 despite
3 turns in the log). Note the pod did NOT wedge: the health smoke after the
matrix (`WAb-2607021434`, "17+25" → reply `"42"`, 16.6s, `:completed`)
proves the last `finally` restored the live conn. Interleaved restores CAN
leave the pod on a scratch conn in other orderings (restore-order dependent)
— fenced by never running concurrent samples rather than relied on.

Headroom claim: none at the pod level until the `*conn*` single-root is made
fiber-local (tooling-lane item, coordination.md). Parallel scoring today =
`bench-cluster-N` disposable pods, `max_connections` across pods, 1 in-flight
sample per pod URL.

## Derived timeouts (wired into `seon_inspect/config.py`)

| Parameter | Value | Justification |
|---|---|---|
| `QA_SOLVE_TIMEOUT_S` | **240** | eval-design floor is ≥3× row median (3×40.7 = 122); taken at 3× p90 (3×70 = 210) rounded up to 240, which is also >3× observed max (74) — covers tail without letting one wedged QA sample stall a serial row >4 min |
| `DEFAULT_SOLVE_TIMEOUT_S` | **300** | general default: the survey's agentic rows (memory/planning) observed 51→300s (flake taxonomy #1) and have no calibration pass yet; re-derive per row as generators land |
| HTTP read budget | pod budget + `HTTP_MARGIN_S` (30) | the POD owns the clock and answers honestly at `timeout_ms`; margin covers serialization/transit only (was a fixed 330s regardless of pod budget) |
| `POD_MAX_SAMPLES` | **1** | the ceiling verdict above |
| total-run bound | `total_run_bound_s(n, epochs, timeout)` = n·epochs·(timeout+30) | worst-case serial wall clock; e.g. a 15-sample×2-epoch QA row bounds at 2h15m, healthy runs finish ~10× faster (observed: 8 samples in 627s incl. one full timeout burn) |

Precedence (implemented): per-sample `metadata["timeout_ms"]` > per-run
argument (`run_bench(solve_timeout_s=…)` / `seon_pod_solver(timeout_s=…)`) >
config constant. Endpoint: `solve_url` argument > `SEON_SOLVE_URL` (instance
selector, read at call time) > `DEFAULT_SOLVE_URL`.

Honesty note: `QA_SOLVE_TIMEOUT_S` is DEFINED but not yet consumed by any code
path — nothing defaults to it today; future QA rows opt in by passing
`solve_timeout_s=config.QA_SOLVE_TIMEOUT_S`. The consumed defaults are
`DEFAULT_SOLVE_TIMEOUT_S` and `POD_MAX_SAMPLES`.

## Anomalies, attributed (flake taxonomy, survey §5)

| Anomaly | Attribution |
|---|---|
| Dhv + loj: 300s burn, `turns=0`, cas write-error, `halt superseded` | **new taxonomy class: concurrent-solve conn-swap collision** — deterministic harness misuse above `POD_MAX_SAMPLES=1`, not model/pod flake; excluded from capability means; fenced by config default + solver docstring |
| tDT post-reply `run-turn!` `:entity-id/missing` | same class, near-miss variant (scored correctly; pod-side error only) |
| 0.875 accuracy on the 8-sample eval | collision artifact (7/7 on non-collided samples) — live demonstration of "classify + exclude flakes from means" |
| Latency spread 16.6→73.8s on identical-class rows | taxonomy #1 (solve-latency variance), benign at this scale; drives the 3×p90 rule |
| `warn-on-seed-origin-forge!` warnings on every scratch seed (`agent-scoped tx claims :seon.db/origin :core-seed`, count now 27+) | pre-existing warn-only noise from `/solve`'s per-sample core-seed running inside an agent scope; not sample-affecting; flagged to tooling lane |
| SCI bounding WARN: `my.plan.internal/plan-block` falls back to the UNBOUNDED compiled path (`Unable to resolve symbol: db/*conn*`) | pre-existing boot warning (pod.log line 38); render-path, not /solve-specific; flagged to tooling lane |

## Raw numbers

Eval logs: `src-inspect-ai/logs/calibration/2026-07-02T18-18-12-00-00_gsm8k_UaNtHWUqkDCq76vainLnKy.eval`
(warmup) and `…T18-19-16-00-00_gsm8k_JoCmorxW5f7uHUMbxkqdt9.eval` (8-sample
run: wall 626.7s, accuracy 0.875). Per-sample elapsed_ms (recorded run):
31604, 30148, 60401, 54230, 16640, 24126, 45166, 303786(timeout). Duplicate-
client samples from pod.log `POST /solve OK` lines: 28664, 73822, 70735,
39150, 300115(timeout), 42234, 18095. Health smoke: 16612. Multi-turn grants
smoke: 96536. Pod: acme (7980), pid 29553, started 18:01:05Z, fresh
post-merge reset; wire-server pid 29422.

## Follow-ups

- Deliberate agentic-row calibration (memory/planning) once their generators
  land — the 300s default is survey-inherited, not measured here.
- The `bench-cluster-N` parallel runner (eval-lane-plan A6) is the actual
  concurrency lever; this run fixes its per-pod invariant at 1.
- Tooling lane: fiber-local `*conn*` would raise the per-pod ceiling; the two
  warn-noise items above.
