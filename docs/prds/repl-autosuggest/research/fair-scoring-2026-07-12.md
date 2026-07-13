---
type: research
status: draft
tags: [research, agent]
---

# Fair scoring (scoring v3) — layered columns, no single hard gate

**Date:** 2026-07-12 · **Owner directive:** "make sure the judges are fair
and we are encouraging creative solutions and not just hard gating exact
text. We need real signal on what's working." · **Builds on:**
[[scorer-false-negative-audit-2026-07-12.md]] (the FN audit — .40 frontier
FN rate, the prescribed-act accept-set recommendation, the 40 judged
cases that are this scorer's acceptance test) +
[[kt3-redux-full-index-2026-07-12.md]] (scoring v2 + decomposition) ·
**Implementation:** `src-needle/scripts/kt3_score.clj` extended in place
(the `:fair` static block; legacy array mode AND the kt3_redux extended
mode verified byte-stable against captured fixtures) +
`src-needle/scripts/fair_score.py` (staging manifest / harness runner /
merge / acceptance / report) · **Staged-world eval:** the data-audit
lane's harness (`src-needle/audit/seon/needle_lora_audit_test.cljs`) run
UNMODIFIED in the PINNED worktree (`/Users/sean/src/seon-pin`, sha
`93c8d8ad`) — its staging machinery REUSED (manifest assembly calls
`lora_audit_manifest.py`'s parsers), never forked · **Raw outputs:**
`src-needle/data/fair/` (gitignored).

## TL;DR

- PENDING — filled after the full rescore completes.

## The layers — every column reported, nothing hides

| layer | question | mechanism |
|---|---|---|
| **L0 parse** | does the prediction read? | edamame (`:parsed`, unchanged) |
| **L1 valid** | real fns, schema-shaped args? | head grounded in index ∪ cards ∪ own defs ∪ core ∪ context; map-arg key NAMES ⊆ the fn's known request keys, extracted from the 168-fn index's arglist destructuring (`kt3_score.clj/arglists-key-names`) |
| **L2 eval-clean** | does it RUN against the row's staged world? | each (row, prediction) evals through the LIVE pipeline (`run-turn!` with a scripted llm-fn) in a hermetic world staged from the row's own context: plan-block tx (verbatim ids), transcript-echo replay, peer agents. HARD failures (throws, unresolvable symbols, invalid-input instrument rejections) count against; error ENVELOPES do not — errors are values, the mined history is full of them |
| **L3 productive** | is it the situation's prescribed act, or a verified effect-advance? | the mechanical accept-set (below), state-guarded per the FN audit |
| **L4 history-match** | does it match what the agent historically did? | the existing scoring-v2 set-union best-match F1 vs the turn bundle — kept for cross-day comparability, demoted from headline |

**Headline:** `fair_useful = gate × max(L4, L3-credit)`

- `gate` = the prediction's hard-clean form fraction on **gated** rows;
  `1.0` on ungated rows. **The gate self-calibrates:** a row is gated
  only when its own historical TARGET evals hard-clean in the staged
  world — a target that hard-fails proves the staging (not the model)
  incomplete, so that row's eval evidence never zeroes anyone.
- `L3-credit` = `2p/(1+p)` with `p = accepted/substantive` calls —
  recall treated as 1 because the prescribed act IS a complete useful
  suggestion — and fires only when `accepted ≥ 1 ∧ voids = 0`
  (bundle purity: junk riding along a prescribed act kills the credit;
  this is what separates the 8 audited reasonable-alternatives from the
  9 same-signature real errors).

### The accept-set (state-guarded, per substantive top-level call)

| class | rule |
|---|---|
| **accepted** | `plan/active!` on the block's `→ next ready` id or an open `✉` step id, when NO step is `▶` active · `plan/done!` on the `▶`-active step id · plan read probes (`document`/`tree`/`list-open`) carrying ≥1 context-grounded id · eval-confirmed **effect-advances**: fresh `register!` (attr not already registered in the context's history) or `transact!` that evals clean in the staged world (= stores schema-valid data by construction — `transact!` validates) |
| **void** | hallucinated head · any id-shaped string not grounded in the context (exemplar leakage, invented ids) · guard-violating `active!`/`done!` (re-activating the `▶` step, `done!` on a message id or without an active step) · re-`register!` of an already-registered attr · writes that fail in the staged world (gated rows) |
| **neutral** | grounded reads (`db/query`/`pull`/…), defns, own-fn invocations, `message/*`, id-less probes, plan writes that eval clean but whose productivity is not mechanically checkable (`step!`/`reconcile!`/`plan!`) |

Deliberate exclusions, with the evidence that forced them:

- **`message/user` is NOT in the accept-set.** The audit's parrot
  signature (`(message/user "ready")` emitted on ~every instr-few row,
  exemplar-copied) would otherwise be credited as "answers the pending
  inbound" whenever a user ask is open — instr-few:127 is the concrete
  case. "Answers the pending inbound" therefore stays out of v3 until a
  content-grounded guard exists; documented as future work, not
  implemented mushily.
- **Clean plan-writes are neutral, not accepted.** `step!` duplication
  of steps the block already shows evals CLEAN — productivity of a plan
  write is precisely what the state guards can't verify mechanically, so
  clean writes neither credit nor kill.
- **`done!`-immediately-after-`active!` bundles stay at 0** — the
  audit's real-error pattern: `done!`'s guard reads the CONTEXT's `▶`
  state, not the prediction's own prior form.

## Acceptance test (mandatory, from the FN audit's judged cases)

- The 8 frontier reasonable-alternatives (deepseek:1/2/27/31/42/128/
  184/187) MUST score >0 — PENDING (static layer: all 8 fire L3).
- The 14 instr-few real-error cases MUST stay 0 — PENDING (static
  layer: all 14 blocked regardless of eval outcome — voids or
  no-accepted-call).
- All non-junk targets self-score 1.0 through the FULL fair path —
  PENDING.
- The starcoder2 six (report-only): PENDING; starcoder2-cont:37 (a
  grounded `plan/tree` probe judged wrong-but-related where the
  near-identical deepseek:42 `plan/document` probe was judged
  reasonable) is the known precision cost of the probe rule — the
  mandate (deepseek:42 > 0) wins the tie, the flip is reported.

## Rescored day table — v2-display, fair-scored

PENDING — every kt3 / kt3b / kt3redux arm on disk rescored under the
fair columns. (The kt3redux `-4card` rescore arms share the kt3b
prediction files and are covered by the kt3b rows.)

## Staging notes / harness smells (reported, not papered over)

- **Boundary injection gap (data-audit lane):** inside the harness's
  scripted `run-turn!`, the eval-boundary `:seon.agent/id` injection
  does not reach every plan write (`plan!` observed failing with
  `no :seon.agent/id resolved` while `reconcile!`/`step!`/`active!`/
  `done!` inject fine in the same build). Envelope failures carrying
  that exact boundary error are scored INCONCLUSIVE (neutral), never
  void — the boundary's failure is not the model's. Reported to the
  data-audit lane.
- **`my.plan/plan!` exists in the pin runtime** (real fn, envelope
  surface) although the FN audit described it as invented — the mined
  acme world predates it. Hallucination grounding uses the pin's index
  + the row's cards + context, so the classification stands either way.
- Transcript echoes that fail to read (render-clipped forms) are
  balance-repaired via the audit lane's repair path, else dropped —
  any resulting staging incompleteness is absorbed by the self-
  calibrating gate (the target hard-fails → row ungated).

## Limitations

- PENDING (filled with the run).
