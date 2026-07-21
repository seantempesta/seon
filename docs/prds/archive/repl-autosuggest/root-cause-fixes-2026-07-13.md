---
type: prd
status: active
tags: [prd, agent, flow]
---

# Root-cause fixes — the "garbage in, garbage out" push (2026-07-13)

**Restart anchor.** A day of measuring whether a tiny model (needle 26M
/ Qwen 1.5B) can autocomplete REPL forms produced suggestive-but-never-
settled numbers. An overnight data audit found WHY: **our own
instruments lie.** This push fixes the correctness foundation BEFORE
resuming model work. Read this doc top-to-bottom; it is self-contained.
Sibling docs: [[design.md]] (the contract), [[roadmap.md]] (we-are-here),
[[CLAUDE.md]] (index). Full plan detail lived in
`~/.claude/plans/okay-so-garbage-in-replicated-donut.md`.

## NEXT SESSION FOCUS — DATA QUALITY (owner, 2026-07-13)

**The owner doubts our data quality and wants it to be the primary
focus of the next session.** The 5-tier plan below stands, but its
*purpose* is trustworthy trajectories — Tiers 0/1 (runtime + truthful
eval boundary) are the ENABLING PREREQUISITE (you cannot have clean data
while eval lies about success), and Tier 2 (the data pipeline) is the
deliverable the owner cares about. Two explicit directions on top of the
plan:

1. **Smarter models generate PROPER TRAJECTORIES.** Stop relying on
   cheap single-form DeepSeek drafts (the source of the 27% garbage).
   Use smarter models (Muse, frontier) driven through FULL task arcs
   (plan → work → verify → done), REPL-proven end to end. The
   plan-preload pilot already proved real driven agents produce good
   multi-step trajectories with honest verify-then-close cycles — lean
   into DRIVING smart models through staged scenarios and mining those
   turns, not fabricating single (context→form) pairs. A trajectory is
   the training unit, not an isolated form.
2. **Investigate LLM JUDGES for goal-achievement.** Determine whether an
   LLM judge can decide "did the agent reach the goal, even by a
   different path than history" — for BOTH (a) SCORING (the
   creative-solutions tier: a valid working alternative should count) and
   (b) CURATING trajectories (did this trajectory actually accomplish the
   task before we mint it as gold). NOTE the tension: the scorer FN audit
   ([[research/scorer-false-negative-audit-2026-07-12.md]]) recommended
   keeping exact-match as the metric CORE and using judges only as a
   calibration pass (the judges themselves needed gating + adjudication).
   The owner wants to investigate leaning IN. The honest way: CALIBRATE
   the judge against a GROUND-TRUTH oracle — the vendored benchmark tasks
   (terminal-bench, aider-polyglot, deepswe) have deterministic
   pass/fail, so a judge's agreement with the oracle is measurable before
   we trust it on judge-only tasks. Fold into Tier 4's fair scorer.

## The diagnosis — three classes of garbage

1. **The eval boundary lies.** `seon.eval` records `:seon.eval/ok? true`
   for an undeclared-var call with a quoted arg (it "ran NOTHING"). This
   is the deepest root cause: `ok?` is what we mine as "gold," so both
   the held-out 214 AND every generated training pair inherit no-ops
   labelled as successes.
2. **The data pipeline ships garbage.** Generation filtered on "function
   name exists," never "evaluates clean against a real world" → 27% of
   pairs hard-fail at eval. Contexts were TEXT-staged (fabricated render
   grammar), not rendered from real transacted db state.
3. **The runtime invites agent garbage.** A live drive stored a WRONG
   number to durable memory at `:confidence :verified` because no
   `::expect` was in view. (Mostly CONTEXT/config, not code — see Tier 3.)

## The 5-tier plan (exact fixes, file:line)

### Tier 0 — adopt the refactored runtime + rebuild acme [FOUNDATION]
HEAD = `9f1f819b`, **118 commits** past the retired pin `93c8d8ad`.
- **HEAD health VERIFIED HEALTHY** (2026-07-13): live pod up since
  03:09Z, 0 SEON-CORE-FAULT in the live log, build fresh 0 warnings. The
  remembered "pod crashing" was a Jul 9 heartbeat-wedge the refactor's
  supervisor/fiber fixes RESOLVED. "Much more robust/fast" corroborated.
- Refactor = atomic identity (`new src/seon/db/id.cljc`), tx provenance,
  captured-read replay, instrument exact-deltas, pay-for-use context
  views, gym retired → Inspect AI.
- **Breaking API deltas** (fully migrated on-branch; verify lane doesn't
  hit them): `seon.db/new-id!` DELETED (→ `db/ensure-provenance!`);
  `id->time-str` gone; eval publics `stash-result-raw!`/`home-requires-for`/
  `home-ns-form`/`require-edges-from-source` → new `seon.agent.home`;
  `seon.agent.ctx/home-ns` → `seon.agent.home`.
- **Retire pin + clean worktrees**: `git worktree remove seon-pin`,
  `seon-fn-surface`, `seon-toolkit-gaps` (work is on HEAD). **CHECK
  FIRST for existing fix work**: `seon-eval-fix` [branch
  `fix/eval-silent-ok-query-envelope` — Bug 1+2!], `seon-display-v3`
  [`repl-autosuggest/display-v3`], `seon-plan-fix` (may hold reconcile
  WIP). Adopt if coherent instead of rebuilding.
- **Reconcile lane WIP**: commit-or-stash uncommitted `my/plan.cljs` +
  `my/plan/internal.cljs` + `test/my/plan_test.cljs` (the reconcile fix;
  compatible, no retired-API use). Re-verify on HEAD: `:autocomplete`
  profile (now config→DB `:seon.config/context-profiles`, acme inherits
  it), `my.kb/recall`, `my.ns/functions`, tool-surface cards.
- **Update acme completely**: one command — `bin/acme cluster reset acme`
  (auto-builds HEAD bundle, stops→wipes `store` ONLY→reseeds→restarts).
  Blobs preserved; cannot touch live default; plan-pilot store is a
  separate repo root (safe). Sequence acme rebuild AFTER Tier 1.
- **PRESERVE-FIRST**: 214 held-out durable in `data/tune/
  acme-2026-07-12.jsonl` (safe). acme store has turns newer than the
  11:31 export — re-run exporter first only if those matter.

### Tier 1 — the eval boundary tells the truth [CORRECTNESS]
None owned by the runtime-reliability lane (verified).
- **Bug 1 — false-ok on quoted-arg undeclared head** (deepest cause).
  Mechanism: quoted args (`'{…}`/`'[…]`) make inner symbols CONSTANTS
  (analyzer short-circuits, no warning), so an undeclared HEAD's lone
  `:undeclared-var` warning fails to reach `raw-eval`'s reject branch
  (`src/seon/eval.cljs:1138-1196`) → `:else` → records `ok? true` +
  result-edn `"nil"`. **Fix (NO HACKS — use the existing structural
  detector):** gate on `seon.diffusion.retrieval/unresolved-references`
  (`src/seon/diffusion/retrieval.cljs:510` — pure program-graph check,
  flags call-position head, SKIPS quoted data). A top-level call whose
  head is a free unresolved symbol can never record `ok? true`. Sites:
  `raw-eval` cond + `record-eval!` (`eval.cljs:2991`) / `truly-undeclared?`
  (`:621-691`).
- **Bug 2 — `db/query` silent `#{}`**: a request map lacking `::query`
  fails `(contains? a0 ::query)` at `src/seon/db.cljs:800`, falls into
  the positional else-branch, gets treated as a raw map-form query →
  `assert-known-query-attrs!` can't catch it (no `:where`) → `#{}`.
  **Fix:** the dispatch predicate at `db.cljs:800`; reads THROW a
  `:seon.error/kind :user-input` ex-info naming the missing key (mirror
  `assert-known-query-attrs!:989-992` — reads throw, not the write
  `{::ok? false}` envelope).
- **Bug 3 — reconcile! id-remint WIP is COHERENT + COMPLETE** (verified:
  4 layers — `::resolved-root` schema, response envelope,
  `resolve-doc-identities` resolver with root+child rules and
  ambiguity-refusal, 5 tests). Only unverified: do tests pass. **Action:
  run tests, if green COMMIT (don't rewrite).** Closes the Tier-3
  reconcile item too.
- **Drop-taint the 214**: re-classify `acme-2026-07-12.jsonl` with the
  fixed `silent_ok` detector (`src-needle/scripts/lora_audit_report.py:88`),
  DROP no-op rows → clean set (owner decision 1).

### Tier 2 — the data pipeline can't ship garbage [DATA]
Root cause: generation never *evaluates*. Garbage enters at three doors
— fabricated CONTEXTS (`lora_gen_situations.py` builds render grammar
from Python templates; the 45 fabricated finish-stage `⟹ #{…}` results,
70 unbalanced echoes); bare-head TARGETS passing name-existence but
undeclared in the agent's home ns (`lora_curate.py head_known:111` — 128
of 149 failures); value/envelope defects invisible without eval.
**Fix = INVERT the order (stage → render → eval → keep-clean):**
- Stage a real world: `audit-pair!`'s ladder
  (`src-needle/audit/seon/needle_lora_audit_test.cljs:152`).
- **Render the context FROM the staged world** via
  `seon.repl.autocomplete/context` (`src/seon/repl/autocomplete.cljs:163`
  — PURE over the db VALUE, so a `db-with` synthetic world renders
  BYTE-IDENTICAL to serving; the design's value tier, design.md:290-295).
  This is the missing step; replaces the Python fabrication whole.
- `drive-turn!` the frontier draft; classify (`lora_audit_report.py`
  `classify_pair`); mint only `eval-clean`; keep rejects as correction
  data. DELETES the `lora_audit_manifest.py` reverse-engineering.
- **Seed = both, via db-staging**: plan-pilot harvest store
  (`/Users/sean/src/seon-plan-pilot/data/clusters/acme`, real worlds,
  separate cluster from held-out → training-legal) for query/report; the
  15 domain arcs (`lora_gen_situations.py DOMAINS:94`) for
  plan-bookkeeping breadth, db-STAGED not text-staged.
- Drives here also mine the fresh held-out set (decision 1, later phase).

### Tier 3 — the runtime doesn't invite garbage [triaged, NO HACKS]
The triage PRUNES this tier — a naive "detect and scold" would be the
exact hack CLAUDE.md forbids.
- **Expect-blind poisoned memory (headline) → CONVERGES WITH TIER 2.**
  Root cause is authoring-unreliability, not `done!` policing. Fixed by
  Tier 2's "stage the plan as pre-transacted DATA." (Residual: kb
  `::confidence :verified` self-graded, `kb.cljs:47` — teaching.)
- **Address-step `active!` capture → SMALL CODE FIX.** The `✉` step
  mints at turn 0, sorts oldest-first in `ready-leaves`
  (`src/my/plan/internal.cljs:169-189`), captures `▶`. Fix = authored
  steps outrank the `✉` step in the ordering derivation. Don't exclude
  it. Coordinate — partial runtime-lane overlap.
- **`:stream` prose-flail → CONFIG LEVER, not code.** Discrimination
  already exists (`eval.cljs:2007 prose-paren?`, deliberately
  err-toward-keep). A DeepSeek regex would be a hack. For OUR drives: set
  DeepSeek per-model `repl-mode :batch`.
- **Derived `delay-minutes` stored → TEACHING** (no syntactic signal;
  guess-and-flag would be a scold).
- **reconcile! root re-mint → covered by Tier 1 Bug 3 WIP.**

### Tier 4 — the measurement tells the truth [INSTRUMENTS] (owner: fold in)
- **Display v3 — spec-bearing cards** (task #16). LIVE cards are already
  correct (`compact-fn-head` includes `:malli/schema`); the defect is the
  src-needle over-compaction. Fix = delete it + decoration-strip dial
  (KEEP eval glyphs ⟹, plan marks ▶☐✓; strip box-drawing/«»/…
  decoration) + stale-card filter. Check `repl-autosuggest/display-v3`
  worktree (`b7be18be`). Re-export v3 from the clean held-out.
- **Fair scorer** (untracked `src-needle/scripts/fair_score.py` +
  `research/fair-scoring-2026-07-12.md`): finish + run. Layered columns
  (parse → valid → eval-clean-against-staged-world → productive →
  history); headline = eval-clean ∧ (productive ∨ history) so CREATIVE-
  but-valid solutions score. Acceptance test: FN-audit's 8
  reasonable-alternatives must score, the 14 real errors stay 0.

## Owner decisions (RESOLVED 2026-07-13)
1. **Held-out = drop-taint NOW, drive-fresh LATER** (phased).
2. **Scope = FOLD IN the measurement instruments** (Tier 4 in-push). The
   definitive fair-scored re-run is the first act of resumed model work.

## Recommended sequence
Tier 0 (adopt HEAD, retire pin, reconcile WIP) → Tier 1 (eval fixes +
drop-taint) → rebuild acme on fixed runtime → Tier 2 (data pipeline) →
Tier 3 (hygiene) → Tier 4 (display v3 + fair scorer) → resume model work
(definitive fair-scored re-run, needle resume-train, vehicle decision).

## Current state (what exists NOW)
**Committed & solid:** A1 exporter `seon.repl.autocomplete` (`af67b188`);
MLX needle port (`5481ab36`); tool-surface overhaul (`2de88e0f`→
`ff29f800`, needle legibility .283→.372); `my.kb/recall` +
`my.ns/functions` (`e2e4ce92`); verbs cleanup; benchmark estate updated.

**Research (18 files in `research/`):** KT ladder (KT1 fired, KT2b lint,
KT3/KT3b ceiling), scorer FN audit (frontier zeros 40% reasonable →
corrected ceiling ~.46), **the LoRA data audit (27% eval-fail — THE
garbage finding)**, plan-preload drive (expect-blindness poisons memory),
extension prep/train (needle halted step 9400, checkpoint resumable).

**Died on Fable credit limit (partial):** display v3, fair scorer, the
eval fixes, reconcile WIP — see Tiers above for exact resume points.

**Key paths:**
- Held-out (TAINTED): `data/tune/acme-2026-07-12.jsonl` (214)
- Training seed (clean real worlds): `/Users/sean/src/seon-plan-pilot/data/clusters/acme`
- Needle checkpoint: `src-needle/checkpoints/extended-2048/` (resumable)
- Data-gen + audit harness: `src-needle/scripts/lora_*` +
  `src-needle/audit/seon/needle_lora_audit_test.cljs`
- MLX gotcha: `mx.set_cache_limit(2GB)` mandatory ([[reference_mlx_metal_cache_limit]])

## Verification
- Tier 0: `bin/test-cljs` green on HEAD; acme boots clean; A1 exporter
  re-runs 0 determinism mismatches on new runtime.
- Tier 1: hermetic repro of each bug FIRST (falsify), then fix;
  `(query 'undefined-head …)` records `ok? false`; `db/query` missing-key
  throws legible; reconcile 5 tests green.
- Tier 2: N pairs through the new gate → 0% eval-fail (vs 27%); 5
  db-staged contexts byte-parity with a real render.
- Tier 3: one drive — address-step no longer captures `▶` with authored
  steps present; DeepSeek `:batch` drive → 0 prose-flails.
- Tier 4: display v3 before/after card samples eyeballed; fair scorer
  acceptance test passes; day's raw predictions re-scored fairly.
