---
type: research
status: completed
tags: [research, agent]
---

# Typeahead replay — P4 three-arm bench (2026-07-10)

Task: `seon_inspect.tasks.typeahead_replay` over the acme-captured replay
corpus (`evals/typeahead_replay.corpus.json`, sha
`796e81c9…badb`, n=10 across repl/movement/planning/kb rungs, every render
built ≤4k tokens from the VERBATIM captured sections). Local MLX worker
`worker_sha e3e4fc3668d3` (restarted + verified fresh before the run).
k=3 (seeds 100–102), serial. Local arms elapsed 4282 s.

## The table (outcome = the correctness gate; means are flake-excluded)

| arm | outcome | form validity | verb acc | tok→valid form (med) | wall/sample (med) | uptake | rounds→lock |
|---|---|---|---|---|---|---|---|
| arm0 DeepSeek, captured production turns (36k render, k=1) | .70 | 1.00 | .71 | 91 | n/a¹ | — | — |
| arm0b DeepSeek one-shot, SAME 4k render (k=1) | .70 | 1.00 | .57 | 22.5 | 22.9 s | — | — |
| arm1 guided, no menus | .286² | .464 | **.00** | 9 | 20.5 s | — | — |
| arm2 typeahead (step loop + menus + offers) | **.533** | **.90** | **.33** | 6 | **3.0 s** | **0.0** | 1 |
| arm3 degraded (guided + menu text, machinery inert) | .267 | .367 | .00 | 9 | 33.4 s | — | — |

¹ per-turn LLM latency was not captured on the production turns.
² 2/30 executions flaked (`worker_error`: transient JSONDecodeError while
polling /status — excluded per flake discipline, see arm1_guided.jsonl).

## Kill criteria (honest reads, not just the automated verdicts)

- **Protocol leak** (arm3 vs arm1): −0.019 — ONE execution out of 30 at
  k=3. The automated verdict string flags any negative delta; the honest
  read is **within noise, no meaningful leak** — menu text neither helps
  nor hurts the plain guided loop.
- **Dead weight** (uptake vs gain): uptake = **0.0** — zero glyph
  emissions in all 66 steps (transitions: done 20, repair 18, stuck 16,
  progress 10, grow 2), and auto-offers cannot fire because null-render
  calibration is not wired (matches the shipped provider). Yet arm2 beats
  arm1/arm3 by **+0.25 outcome**, +0.44 validity, +0.33 verb accuracy at
  **~7× lower latency**. So: the STEP-LOOP surface earns its render; the
  GLYPH-SELECTION channel specifically is **unexercised and unproven** —
  the observed lift comes from menu-text-in-a-step-regime + the
  lock/commit/repair mechanics, not from selection.

## vs the frontier reference (the owner question)

The local 26B typeahead surface is NOT better-or-equal to DeepSeek on
correctness (.533 vs .70) on this corpus; it is ~7× faster per reply
(3.0 s vs 22.9 s median) and free/local. DeepSeek's verb misses on the
slim render were real hallucinated verbs (`plan!`, bare `done!`, bare
`query` — unresolvable in the pod), i.e. the slim-render one-shot regime
costs DeepSeek verb accuracy too (.71 → .57). **Muse Spark arm: SKIPPED**
— no `META_MODEL_API_KEY` on this machine (auth friction rule).

## Files

- `arm*.jsonl` — per-execution evidence (reply, analysis, step traces,
  seeds, timings). `logs/` — the inspect EvalLogs.
- Ledger rows: `2026-07-10:typeahead_replay:dev:k3:arm{1,2,3}` in
  `evals/scorecard.jsonl` (attribution carries arm + corpus/worker shas).
- Corpus provenance: every sample stores its byte-exact prompt-blob hash
  from the acme store + the verbatim sections used for the arm renders.

## Known caveats

- arm0 is k=1 over the production render (different context size — the
  clean same-render comparison is arm0b).
- One scoring rule was fixed mid-session and the local arms fully re-run
  under it: eval-answer rows accept the answer delivered as text/message
  (the production convention) in ALL arms; the earlier partial run was
  discarded (`arm1` had reached 8/30).
- The two crash incidents during corpus generation (acme pod :core-fault
  exits on DeepSeek transport failures, fault eids 3993/4561) are a pod
  bug — see the session report; corpus generation recovered via
  restart+retry.
