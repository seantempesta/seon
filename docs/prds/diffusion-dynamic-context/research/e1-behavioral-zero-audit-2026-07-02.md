---
type: research
status: active
tags: [research, agent]
---

# E1 behavioral-zero audit — why ALL THREE arms scored 0.0 (2026-07-02)

> Offline audit ($0, no GPU) of the 2026-06-29 three-arm kill-gate run
> (`e1_scorecard.jsonl`, git-shas `db5f4516`/`dab06957`, final N=6 rows:
> `behavioral_rate 0.0`, `instrumentable_rate 0.0`, `faithful_rate 0.0` for
> guided AND naked AND naked+oracle). Owner suspicion: uniform 0.0 across arms is
> a harness/context defect signature, not a model ceiling. **Confirmed — twice
> over.** The KILL Δ+0.000 verdict is VOID as a measurement of the model.

## TL;DR — verdict

1. **PRIMARY (scorer-environment defect, GROUNDED + reproduced): the run scored
   against a DEAD eval bundle, which force-zeroed the correctness term of every
   one of the 18 samples.** The eval-tier source fix (`2ef4eb8c`, 06-29 00:15)
   landed, but the on-disk bundle `out/worker-oracle-eval/main.js` was only
   rebuilt **Jun 30 10:14** — the morning AFTER the scorecard was written (mtime
   Jun 29 23:56). The run-time bundle was the known-broken one ("throws `single
   colon` on every input incl. `42`" — [[roadmap]] P1 recorded this gap). With
   every eval returning `ok:false`: `instrumentable = … ∧ ev.ok` → 0 and
   `defines=false → behavioral_pass=False` for ALL samples
   (`e1_kill_gate.py:294-325, 356+`). **No output the model could have produced
   would have scored above 0.** Reproduction below matches the recorded means to
   the third decimal.
2. **SECONDARY (context defect, GROUNDED): even with a live oracle, the naked
   arms (2/3) could not have passed behavioral, because the prompt never stated
   the calling convention the harness demands.** The harness calls
   `(celsius->fahrenheit {::celsius n})` and reads `::fahrenheit` from the
   returned map; the prompt said only "a Seon map-in/map-out fn". A
   correct-math simple-signature fn and a correct-math bare-keys map fn both
   score behavioral **0** (probes P3/P4 below). So `delta_1v3` was structurally
   biased toward arm1 (whose scaffold embodies the contract) — and arm1 STILL
   scored 0, which is what exposed the dead oracle.
3. **Genuine model failures exist but are unquantifiable for this run: the raw
   N=6 generations were never persisted** (only aggregate scorecard rows).
   The one preserved live example (the instrumentable-but-wrong transducer
   body, see the 06-28 memory/handoff) is real — but this run had zero
   sensitivity to distinguish it from correct output.

**Fixes are IN (harness scripts, gitignored `tmp/flash-diffgemma/`):**
fail-loud golden-sample self-check that ABORTS a run when the oracle stack can't
score a known-correct sample (`e1_kill_gate.py:653` `assert_oracle_live`,
wired into `run_gate:683`; override `E1_ALLOW_DEGRADED=1`); raw-sample
persistence to `e1_samples.jsonl` (every arm × idx × text — auditable next
time); the prompt now states the behavioral CONTRACT (input/output map keys,
`e1_kill_gate.py:801` — fair context for all arms; the formula stays the
model's job). Offline mock proof re-run GREEN end-to-end after the changes.

## The reproduction (the proof)

### R1 — the scorer, TODAY, passes known-correct code (so the pipeline itself is sound)

Real bb `bin/oracle-server` + the current (rebuilt) node eval bundle,
via the run's own `score_attempt`:

| Probe | Input | Result |
|---|---|---|
| P1 | arm1-shaped: scaffold + correct body `{::fahrenheit (+ (* celsius 1.8) 32.0)}` | `faithful: true, behavioral_pass: true, got 32.0/212.0` |
| P2 | hand-written full register!+defn map-in/map-out | `faithful: true, behavioral_pass: true` |
| P0b | eval-server multi-form semantics | `(defn …)\n[(f 1) (f 41)]` → `ok:true, value:"[2 42]"` (last-form value — harness mechanics sound) |

### R2 — a dead eval tier reproduces the GPU run EXACTLY

Monkeypatch the eval server to answer `ok:false` ("single colon") on everything
— the documented state of the run-time bundle:

```
DEAD-EVAL arm1 CORRECT sample: {'parses': True, 'structural': True, 'instrumentable': False,
                                'behavioral_pass': False, 'faithful': False, 'faithfulness': 0.85}
DEAD-EVAL naked sample:        {'parses': True, 'structural': False, 'instrumentable': False,
                                'behavioral_pass': False, 'faithful': False, 'faithfulness': 0.65}
predicted arm1 mean: 0.85        (GPU run recorded 0.85)
predicted naked mean: 0.625      (GPU run recorded 0.625)
```

The scalar is `0.15·parses + 0.20·structural + 0.15·correctness + 0.50·¬vacuous`
(`score_attempt`): the recorded means (0.85 arm1 / 0.65, 0.625 naked) are
exactly the values with **correctness ≡ 0 for every sample** — i.e. a
KNOWN-CORRECT submission would have scored identically to what the model
produced. The run measured the bundle, not the model.

### R3 — the context defect (live oracle, correct math, still 0)

| Probe | Input (correct math, both) | behavioral |
|---|---|---|
| P3 | `(defn celsius->fahrenheit [c] (+ (* c (/ 9 5)) 32))` — simple signature | **0** (returns a number, not a map) |
| P4 | map-in/map-out with BARE keys `{:fahrenheit …}` | **0** (harness reads `::fahrenheit`) |

The prompt never named the keys nor the `::` auto-resolve requirement, so the
naked arms fail these by construction — "wrong context causes 0" exactly as the
owner predicted. (P3's failure is a legitimate contract miss ONLY once the
contract is actually communicated; before that it's an unfair scorer demand.)

## Failure classes, named

| Class | Evidence | Explains |
|---|---|---|
| (i) scorer-environment: stale/dead eval bundle | bundle mtime Jun 30 10:14 vs scorecard Jun 29 23:56; roadmap's own "single colon" note; R2 exact-arithmetic reproduction | ALL 18 zeros forced regardless of output — the run had zero sensitivity |
| (ii) context: contract not in prompt | R3 (P3/P4): correct-math naked shapes score 0; prompt text quoted at `e1_kill_gate.py:801` (pre-fix wording lacked keys) | naked arms structurally unable to pass behavioral; `delta_1v3` biased |
| (iii) genuine model failure | the preserved transducer-body example (06-28, live pod) | real but unquantifiable for THIS run — raw samples were not persisted |

## Concrete fix list for the E1 re-run (all landed except the re-run itself)

1. **Golden-sample self-check gates every run** — `assert_oracle_live` scores the
   task's `golden_middle` through the FULL stack before any GPU call; abort on
   failure (proven: live → passes; dead-eval → SystemExit). No more paying for a
   run the scorer can't score.
2. **Raw samples persisted** (`e1_samples.jsonl`) — the next audit reads
   generations, not tea leaves.
3. **The prompt states the behavioral contract** (input `{::celsius <double>}`,
   output `{::fahrenheit <double>}`) — all arms told the same contract; the
   formula remains the model's job. This un-biases `delta_1v3`.
4. **Re-run E1 on the next GPU session** (after exp D, ~$0.5): with (1)-(3) the
   behavioral numbers become meaningful for the first time. Until then, every
   "guided guarantees shape not correctness" / "KILL Δ+0.000" claim should be
   read as **inconclusive — voided by a dead oracle**.
5. (Optional hardening) record `oracle_health` per scorecard row so a degraded
   run is visibly marked in the data, not just stderr.

## Honesty markers

- GROUNDED: the dead-bundle timeline (file mtimes + roadmap's contemporaneous
  note + commit dates); the R2 arithmetic reproduction; probes P0-P5 (real
  oracles, outputs verbatim above).
- ESTIMATED: nothing — every claim above was executed, not inferred.
- UNKNOWABLE for the 06-29 run: the per-sample split between (ii) and (iii) —
  raw generations were not persisted. Fixed going forward.
