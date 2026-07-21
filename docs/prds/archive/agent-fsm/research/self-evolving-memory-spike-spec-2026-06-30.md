---
type: research
status: draft
tags: [research, agent]
---

# Self-Evolving Memory — Spike Spec (owner review BEFORE build)

This is the buildable plan synthesized from the seven research docs (survey +
six deep-dives) under `docs/prds/agent-fsm/research/`. It does not re-research;
it commits to a design and names every Seon seam in real source. It is a SPIKE
spec — the smallest honest end-to-end that answers one question: **can an
evolutionary loop discover a memory store/retrieve design that beats our
hand-built `my.kb/remember`?** If it can't, that is an honest NO-GO and we stop.

Read-first depth (the synthesis inputs): [[self-evolving-memory-survey-2026-06-29]]
(lit map + 9-pt anti-cheat playbook), [[evolving-memory-implementations-deep-dive-2026-06-29]],
[[evolution-engines-deep-dive-2026-06-30]] (fork EvolveMem + 3 grafts),
[[fitness-anticheat-deep-dive-2026-06-30]] (the protected boundary),
[[memory-op-design-space-2026-06-30]] (7 axes; consolidation > retrieval > reflection),
[[inspect-ai-harness-deep-dive-2026-06-30]], [[mvm-live-test-2026-06-30]] (mvm NO-GO on this box today).

The honest one-liner (survey's own): **Voyager's fresh-child self-verifier +
EvolveMem's guarded diagnose→select→revert loop + AlphaEvolve's QD-archive +
re-bench's protected scoring + EvilGenie's hack-veto, applied to a memory
design, on Seon's datahike/`register!` substrate.** We are NOT first (EvolveMem,
MemEvolve, Evo-Memory shipped ~6 months ahead); the differentiated parts are the
homoiconic substrate (the genome is a real `:seon.fn`/`:seon.schema` datom, not a
config vector) and the fresh-child-after-restart fitness.

---

## 1. Goal + GO/NO-GO bar

**Goal.** Evolve a memory **store/retrieve design** — a `(defn store! …)` +
`(defn recall …)` pair plus the `schema/register!` calls they need — that, on a
held-out durability battery, recalls more correctly than the hand-built baseline
`my.kb/remember` (`src/my/kb.cljs:134`) does. On datahike a "memory design" is
just a few registered attrs + a couple of datalog fns, so the genome is small and
first-class.

**The exact metric.** `pass^k` recall accuracy on the held-out battery: a
candidate runs the two-cold-children store→restart→retrieve trial (§3) k times;
its fitness is the mean fraction of held-out questions answered correctly by the
host-side objective checker. The baseline `my.kb/remember` (+ the stored-findings
render block as its retriever) is **baseline-0** in the archive, scored on the
SAME battery under the SAME k.

**GO** = an evolved design's `pass^k` exceeds baseline-0's by a margin larger than
the between-trial noise band (estimate the band from baseline-0's own k-run
variance; require the delta to clear it — the ADAS bootstrap-CI / our gym
`pass^k` discipline), AND it survives the cross-transfer + order-flip overfit
guards (§3) AND it is not hack-vetoed (§4).

**NO-GO** = after a fixed budget of rounds the archive's best never clears that
band, or every clearing candidate is overfit/vetoed. That is a real result —
report it; it says "the hand-built baseline is already at/above what this loop can
reach on this battery," which is honest and worth knowing. Do not move the bar to
manufacture a GO.

---

## 2. Architecture — the loop, with every Seon seam named

The loop is EvolveMem's control structure (fork) with three grafts (QD archive,
evaluator cascade, code-as-genome + reflexion repair). In prose:

**genome → propose → cascade-evaluate → fresh-child fitness → host-checker select
→ QD-archive → diagnose → next.**

1. **Genome** — a candidate design = a `{::thought ::store-src ::recall-src
   ::schema-srcs}` map whose code, once admitted, is persisted as `:seon.fn` /
   `:seon.schema` datoms in the program graph (`seon.agent/start!`'s world already
   carries `:seon.fn/source` at `src/seon/agent.cljs:200` and `:seon.schema/key`/
   `/source` at `:218-221`; the analyzer projects forms into these — `code as
   data, the runtime IS the database`). **Seam: genome ↔ `:seon.fn`/`:seon.schema`
   datoms.** Range-clamping at the boundary (EvolveMem `diagnosis.py:376-420`) is
   already ours: `schema/register!` (`src/seon/schema.cljc:223`) + instrumentation
   reject an ill-typed attr or fn before it can run.

2. **Propose** (ADAS pattern, code-as-genome). The proposer LLM is shown the
   archive-best designs + their recorded fitness + the last failure **diagnosis**,
   and emits the next genome, then runs ≤2 reflexion refine passes
   (ADAS `_mgsm/search.py:181-197`) and an exec-error repair loop
   (`debug_max`, `search.py:204-225`) where **our malli/instrumentation errors are
   the repair signal** — richer than ADAS's raw tracebacks. The proposer sees a
   diagnosis string, NEVER the probe questions, answer key, or checker (§4).
   **Seam: archive-in-prompt ↔ a reactive section fn over the candidate-design
   entities + their `:seon.fitness/*` (derive-don't-store).**

3. **Cascade-evaluate** (openevolve `evaluator.py:360-469`). Stage-1 cheap: does
   the genome `register!` valid schema, define `store!`/`recall`, pass malli
   instrumentation, and survive a 3-fact in-session tiny-probe (no restart)?
   Stage-2: the full two-cold-children restart trial (§3). Stage-3 (optional): the
   LLM hack-veto. Most candidates die at stage-1 for ~free. **Seam: cascade ↔ the
   gym's cheap `measure-context!`/in-session run before the expensive
   `run-scenario!` restart trial.**

4. **Fresh-child fitness** (Voyager `voyager.py:221` critic, ablated −73% without
   it). Two cold children (storer ≠ retriever) on a hermetic scratch conn, restart
   between, measured recall under distractors + token budget. **Seam: fitness ↔
   gym `run-scenario!` (`test/seon/gym/driver.cljs:1755`) + `measure-context!`
   (`:2016`) on a scratch `:memory` conn; children minted via `seon.agent/start!`
   (`src/seon/agent.cljs:495`).** Detail in §3.

5. **Host-checker select** (re-bench protected scoring). An OBJECTIVE host-side
   checker compares the retriever child's answers to the key and writes
   `:seon.fitness/score` — **host-written only**, the analog of re-bench's
   `check_scoring_group()`. The scalar selects; the LLM never selects. Accept iff
   `Δ > threshold` and transfers; revert on regression; soft-accept a positive
   sub-threshold gain (EvolveMem `evolution.py:510-598`). **Seam: select ↔ the
   wire-server (JVM, sole DB writer) — the pod cannot transact `:seon.fitness/*`.**

6. **QD-archive** (funsearch islands + openevolve MAP-Elites). Replace EvolveMem's
   single mutable incumbent with a MAP-Elites grid over behavioral features
   (store-token-budget × recall-accuracy × schema-size; fitness EXCLUDES the
   feature axes — openevolve `database.py:1101-1129`) on an island model with
   periodic cull-weakest-half-and-reseed (funsearch `programs_database.py:142-167`).
   **Seam: archive ↔ the program graph itself (elite `:seon.fn` per cell, lineage
   = the existing fn history); `rollback` ↔ retract-and-restore a prior fn form via
   `seon.db` retract.**

7. **Diagnose** (EvolveMem `diagnosis.py`, judge ≠ selector). Gemini-Flash reads
   the retriever child's actual transcript + failed evals + empty-query results and
   emits a `:seon.fitness/diagnosis` string that STEERS the next proposal. It never
   scores. **Seam: diagnosis ↔ a reactive section fn over the eval/turn log
   (derive-don't-store — no separate failure store; the failures are already
   datoms).**

8. **Next** — explore-on-stagnation: when the archive-best plateaus, the meta
   layer perturbs an untried axis or invokes a fresh proposer
   (EvolveMem `meta_analysis.py:186-289`).

---

## 3. The fitness harness (the make-or-break, ~80% of the work)

### 3.1 The cold-child protocol (one trial)

Per candidate design, one trial on a hermetic scratch `:memory` conn (the gym
already swaps the root `seon.db/*conn*` per `run-scenario!`, so a trial A/Bs
without a pod reset):

1. **STORE.** Mint cold child A — `seon.agent/start!` with zero prior context.
   Hand it `F ∪ D` (facts + distractors, shuffled) and the candidate's `store!`
   verb. It stores under the candidate schema. Read its evals back (live proof the
   writes landed — a datom read, not an inferred transcript).
2. **RESTART.** Tear down child A's context; reconstitute memory from the DB only
   (the gym's mid-scenario restart-against-same-scratch-store, `driver.cljs:66`).
   Durability across restart is the win condition (our "exercising agents"
   doctrine), so "keep it in context" cannot win — the context is gone.
3. **RETRIEVE.** Mint cold child B (NOT A — kills the self-grading channel,
   Voyager's bias). It gets the held-out question battery `Q` + the candidate's
   `recall` verb, answers from reconstituted memory.
4. **SCORE (host-side, objective).** The checker compares B's answers to the key →
   fraction correct. This scalar selects.
5. **DIAGNOSE (host-side LLM, never selects).** Flash reads B's failure logs →
   diagnosis string for the proposer.
6. **HACK-VETO (host-side LLM detector).** Separate LLM, only job "did this design
   cheat?" — a veto, not a score (§4).

### 3.2 Host-side protected checker — what lives where

```
  WIRE-SERVER (JVM, sole DB writer — agent cannot read or eval here)
  ├── probe bank        : fact-sets F + novel Q&A keys + distractor pool D
  ├── the checker       : compares child B's answers to the key (semantic-match)
  ├── the hack-judge    : LLM "did this design cheat?" veto
  ├── the fitness ledger: :seon.fitness/* datoms — HOST-WRITTEN ONLY
  └── the QD archive    : every candidate + lineage + score

  POD (the evolving world — proposer + cold children run here)
  ├── candidate design  : schema (register!) + verbs (store!/recall)
  ├── proposer agent    : sees the DIAGNOSIS, never the key/probe/checker
  └── cold children A,B : get facts/questions; never the grading harness
```

The pod↔wire UDS IS the `/protected` boundary, structurally — the pod genuinely
cannot eval wire-server code (no `chown` to get wrong, a CLEANER boundary than
re-bench's same-box OS users). The pod can only request "run a fitness trial on
design X" and receives `{:seon.fitness/score n :seon.fitness/diagnosis "…"}` —
re-bench's `strip_score_information` enforced by the process split.

### 3.3 Memory-pressure + distractor design

- `F` = N provenance-stamped facts; `D` = M irrelevant/near-duplicate facts
  interleaved. A **token budget cap** (per the token rule — `chars/4` via
  `seon.ai.tokens/estimate`) makes "store everything verbatim" lose: `D` crowds
  out `F` if the design can't discriminate.
- `Q` = questions whose answers require `F` but are NEVER the facts verbatim:
  paraphrased, multi-fact joins, asked in a DIFFERENT order than facts were given.
  Some answerable only after synthesis of ≥2 facts (defeats raw-text dump).
- Drawn fresh per trial from a large bank — never the same literal set twice
  (re-bench `os.urandom` seed rule).
- This selects on the ops the design-space doc ranks: **consolidation > retrieval
  ranking > reflection** — store precision dominates, then what reaches context
  under budget, then synthesis.

### 3.4 The cascade stages (cheap → expensive)

- **Stage-1 (≈free):** static — schema registers, verbs defined, malli passes; +
  a 3-fact in-session tiny-probe (no restart).
- **Stage-2 (expensive):** the full §3.1 two-child restart trial × k.
- **Stage-3 (optional):** the hack-veto LLM.

### 3.5 The overfit guards

- **`pass^k`, not pass@1** — weak child models are noisy (gym law: single-sample
  drives are NOISE). Average over k fresh trials before believing a win/regression.
- **Cross-transfer alarm** — ≥2 independent probe batteries (different fact
  domains/question styles). A design that wins on A but REGRESSES on B is overfit →
  demote even if it set a record on A (EvolveMem positive-transfer as the check).
- **Order-flip** — run `Q` in fact-order and reverse-order; a gap flags
  memorization-of-order (Evo-Memory).

---

## 4. Anti-cheat — mapped to the 9-pt playbook

The threat model is real and documented in THIS loop shape: Darwin-Gödel faked
test logs then edited the detector; EvilGenie agents hardcoded answers and deleted
the test file. Assume our memory agent WILL try to store the answer key.

What the agent literally cannot reach: the probe bank, answer key, checker,
hack-judge, and `:seon.fitness/*` ledger all live wire-server-side; the pod cannot
eval wire-server code. There is no datom to query for the key (it lives in a
host-process closure, not datahike) and no pod verb that can write
`:seon.fitness/score`.

How Flash-diagnoses-but-doesn't-select is enforced: the OBJECTIVE host checker
writes the score; Flash's output is only a `:seon.fitness/diagnosis` string fed to
the proposer. Two different host-side processes, never one model doing both.

Playbook mapping:

1. **Judge ≠ selector** — host checker selects; Flash diagnoses. (§3.1 steps 4-5.)
2. **Held-out mandatory + LLM hack-detector veto** — measured held-out selects;
   the stage-3 LLM veto (EvilGenie: ~0 false-negatives on unambiguous specs) kills
   a cheater regardless of its number. (§3.4.)
3. **Hide the checker** — probe/key/checker/anti-cheat all host-side, outside the
   pod's eval reach (DGM's failure was the detector living inside editable code).
4. **Memory pressure + distractors** make verbatim-dump lose. (§3.3.)
5. **Vary the axes a cheat exploits** — order-flip, paraphrase, multi-fact joins,
   test AFTER restart. (§3.3, §3.5.)
6. **Cross-transfer is the overfit alarm.** (§3.5.)
7. **Cascade cheap→expensive.** (§3.4.)
8. **QD archive + lineage** — every design + parent + score; revert a poisoned
   branch. (§2.6.)
9. **Tightly specify the goal** — "answer these K novel questions correctly after
   a restart, under a T-token store budget" (EvilGenie: crisp spec is a 10-40×
   defense).

The literal substrate mapping (re-bench device → Seon): `/protected` dir → probe
bank in the wire-server host closure; separate OS user → the pod↔wire process
boundary; `check_scoring_group()` gate → only the host writes `:seon.fitness/*`;
`strip_score_information` → the pod gets `{score, diagnosis}`, not per-probe
components; `os.urandom` seed → fresh probe sampling per trial; ancestor-call block
(funsearch `evaluator.py:103-112`) → a candidate `recall` may not secretly call the
baseline `my.kb/remember`.

---

## 5. Isolation plan

The arbitrary-code defense-in-depth, in priority order:

- **Docker NOW.** Two options, both available today: (a) inspect-ai's `docker`
  sandbox provider (the `SandboxEnvironment` ABC + registry — we add one
  `@sandboxenv` class, host scorer stays in-process by construction); or (b) our
  EXISTING hermetic gym (scratch conns, pid-scoped fixtures) as the first-cut
  isolation while the loop is proven. For Milestone-1, the gym's hermetic scratch
  conn + the pod↔wire process boundary is sufficient isolation — the security
  property we need (agent can't read the host-side key) is already enforced by the
  process split, not by the VM.
- **mvm AFTER macOS-26 (pluggable provider).** mvm is a LATER hardening that lights
  up after a macOS-26 upgrade (the Vz backend, zero lib install). On THIS box
  (macOS 15.7.7, M1) it is a confirmed **NO-GO today** ([[mvm-live-test-2026-06-30]]):
  the build needs the third-party `slp/krun` libkrun trio (a custom-kernel blob) +
  `zig`/`cargo-zigbuild`, both owner-gated system installs. Wire isolation as a
  PLUGGABLE provider (same `SandboxEnvironment` API — swap the provider string) so
  mvm/Vz drops in later without touching the loop.
- **The sealed-vs-`exec` gate (flag before betting on mvm).** A *cheat-proof*
  (sealed/prod) mvm image is exactly the one whose `exec` is DISABLED
  (`SandboxDevOnly`). Our loop NEEDS `exec` to run the child's REPL, so it runs on
  the DEV-tier posture — which is still vsock-only/no-host-fs, but is NOT the
  maximal dm-verity sealed posture. Resolve this tension (does dev-tier isolation
  suffice, or do we need a Linux+KVM Firecracker box for the sealed posture?)
  BEFORE committing engineering to the mvm provider. It is not a Milestone-1
  blocker (the process boundary already gives us the property).

---

## 6. Milestone-1 (smallest end-to-end)

**One-liner:** ONE scenario — store N facts (+ distractors, under a token budget)
→ restart → retrieve under distractors with novel questions — a 2-design archive
(`my.kb/remember` baseline-0 vs ONE LLM-proposed evolved variant), select on
host-checked `pass^k` restart-recall, Gemini-Flash veto for "stored the answer
key." Prove the loop ACCRETES or REVERTS correctly ONCE.

- **Pass condition:** the loop runs the §3.1 trial for both designs k times;
  the host checker writes `:seon.fitness/score` for each; the selector either
  (a) admits the evolved variant IFF its `pass^k` clears baseline-0 + the noise
  band and it isn't vetoed, or (b) reverts to baseline-0. Either outcome, correctly
  taken, is a pass for Milestone-1 — we are proving the MACHINERY, not yet a win.
- **What it proves:** the protected boundary holds (child never read the key); the
  two-cold-children restart trial measures real durability; the objective checker
  (not an LLM) selects; the accept/revert gate works; the genome round-trips as
  `:seon.fn`/`:seon.schema` datoms. It does NOT yet prove GO — that needs the full
  battery + islands + cascade + more scenarios (§7).

Build it on the gym + process boundary (no Docker/mvm yet) — fastest path to a
live loop, per "iterate live before hardening."

---

## 7. Build order

Protected boundary FIRST (without it the whole loop is forgeable):

1. **Protected host-checker boundary + host-only fitness ledger** (§3.2, §4). The
   probe bank/key/checker in the wire-server host closure; `:seon.fitness/*`
   host-written only; the pod's "run-fitness-trial" request returns only
   `{score, diagnosis}`. **THIS FIRST.**
2. **The two-cold-children restart trial + objective checker** (§3.1 steps 1-4) on
   the gym scratch conn — the primary measured signal.
3. **The propose / accept-revert loop** (fork EvolveMem `evolution.py:510-598` +
   `diagnosis.py`) with the ADAS code-genome + reflexion/instrumentation-repair —
   baseline-0 vs one variant (= Milestone-1).
4. **The QD archive** (openevolve MAP-Elites grid on funsearch islands;
   `rollback` ↔ retract/restore) — replace the single incumbent.
5. **More scenarios** (cross-transfer + order-flip batteries) + the cascade +
   hack-veto + `pass^k` guards (§3.4-3.5) — then judge GO/NO-GO.

---

## 8. Risks + mitigations

- **Weak-agent bootstrapping.** A cheap proposer with stripped context may never
  produce a working first design (Voyager needed GPT-4 + the critic loop; −73%
  without it). Mitigations: SEED the archive with `my.kb/remember` as baseline-0
  (ADAS archive-seeding); seed bootstrapping with a STRONGER model for the proposer
  (not the cheap child model); feed the proposer the failure DIAGNOSIS, not a
  pass/fail bit; allow the ≤2 reflexion + instrumentation-repair iterations before
  scoring.
- **Single-incumbent collapse → one local optimum / one cheat** (POET's whole
  motivation). Mitigation: the QD archive (MAP-Elites + islands) + explore-on-
  stagnation, NOT a single mutable baseline. (Build step 4.)
- **Not-first / EvolveMem precedent.** EvolveMem, MemEvolve, Evo-Memory shipped
  ~6 months ahead. Mitigation: say so first; the contribution is the homoiconic
  substrate (genome = `:seon.fn` datom) + the fresh-child-after-restart fitness,
  NOT the concept of evolving memory. If those two don't yield a measurable win,
  that's the NO-GO.
- **Memory drift / poisoning over rounds** (SSGM). Mitigation: the lineage/archive
  makes a poisoned branch auditable + revertible (`rollback` ↔ retract/restore).
- **The GO/NO-GO honesty risk.** The default instinct is to declare victory
  (CLAUDE.md "Slow Is Fast"). Mitigation: the bar is fixed BEFORE building (§1);
  the noise band is estimated from baseline-0's own variance; a NO-GO is reported
  as a real result, not buried.

---

## 9. Open questions for the owner (genuinely undecided)

1. **Which model seeds bootstrapping?** The proposer likely needs a STRONGER model
   than the cheap DeepSeek child to produce a working first genome + useful
   reflexion. Options: Gemini-Pro / a strong Claude for the proposer + reflexion,
   DeepSeek for the cold children, Gemini-Flash for diagnosis + hack-veto. Owner
   call on the proposer model + budget.
2. **Scenario count + battery size for Milestone-1.** Spec proposes ONE scenario,
   2-design archive, but leaves N (facts), M (distractors), |Q| (questions), and k
   (`pass^k` samples) for the owner — these set the cost-per-round and the noise
   band. A starting point to confirm: N≈15, M≈15, |Q|≈10, k≈3.
3. **Extend the gym vs adopt inspect-ai?** The gym (`run-scenario!`/`measure-
   context!` + scratch conns + `pass^k`) already covers ≈80% and is the fastest
   Milestone-1 path. inspect-ai brings Task/Scorer/datasets, `niah`/`infinite_bench`/
   `persistbench` probes, and a clean `mvm` provider seam — but is a Python harness
   beside our CLJS world. Recommendation: gym for Milestone-1, evaluate inspect-ai
   as the generalization-probe host once a win exists. Owner confirms the lane.

(Two further items to flag, not block: the sealed-vs-`exec` mvm tension (§5) — a
decision gate before betting on the mvm provider; and whether a separate `recall`
verb is the genome's second gene vs retrieval-via-`seon.db/query` + a render block
— the baseline today has no standalone `recall` defn, so the evolved genome
INTRODUCES one. Both are design choices the build can settle, surfaced here for
visibility.)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
