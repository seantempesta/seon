---
type: prd
status: draft for owner review (revised after Astra review)
tags: [prd, agent, context]
---

# Next turn — synthesis: what to build, how cheaply, and what it costs

Eight research lanes ran on 2026-09-26 (`research/`), and an independent review
(`research/synthesis-review.md`) required changes that are folded in here. Every
number is either measured (the lane file names it) or marked *estimate*. Line
citations are anchors at HEAD 58379420 and drift; the named functions are the
stable reference.

## The answer

**Measured:** an agent's next step is mostly predictable from structure and its own
recent work, not from a semantic model (`research/predictability.md`, 430,803 tool
calls by our own agents on this repo).

- 82% of the next files and 60% of the next symbols were already touched in the
  session. The task text names 54% of file targets.
- A 1 ms mix of recency, transitions, task text and recent results puts the next
  file in its top 10 74% of the time. Plain most-recent-first already gets 69.6%,
  and top-1 is 36.7% vs 35.6%.
- Zero-shot CLM-8B got 8% top-1 against the mix's 36% on the same 300 steps, at
  0.53–0.63 s per step.
- File hits are lenient any-target matches. Re-reads often fetch a different range.

**Inside a form, once the function is known:**

- arity is top-1 84%, declared map keys are top-5 81%, locals are top-5 88%;
- but only 35% of all holes are small closed sets;
- schema-listed values are under 2% of contracted arguments;
- the largest fill (27.5% of arguments are db/conn slots) is **already built** for
  product agents (`research/form-assembly.md`).

**The literature:** structure-derived context beats similarity retrieval, and more
context hurts. On SWE-bench, 13K→50K retrieved tokens lowered the resolve rate for
every model (`research/prior-art.md`).

**Verdict on the owner's hypothesis (per-turn curated context plus autocomplete):**
the evidence supports **testing focused structural context**. It does not yet show
that context raises task success. No study, and nothing we measured, closes that
gap. Autocomplete in Seon means **menus and checks at the form boundary**, not
token-level decoding: ~11% of tokens are forced, and hosted providers expose no
decoding hooks (`research/token-level.md`). Hot-path zero-shot CLM is rejected for
the tested setting. That is not a rejection of learned ranking in general.

## 1. The most impactful things, and how to do each cheaply

Ranked by expected impact on the agent. Each starts with a screen of half a day to
a day that needs no new infrastructure.

### ① Per-turn structural context, generated as ordinary reads

**What.** Before each completion, choose a small set of **ordinary read requests**
and run them through the existing system-turn path
(`seon.turn/system-plan`, `system-turn`). Declared rendering produces whole
recorded units, so there is no second prompt path, no card clipping and no new
printer. Three sources compete, and the screens decide their order:

- **(a) Contract-directed context for known operations.** The functions the agent
  is using or has just named get their arity contract, the named schemas of their
  unresolved arguments, their declared keys, and one recorded successful call.
  Uses the existing documentation owner (`seon.sci.eval` doc path) and Malli.
  (Astra #1, prior-art #1: strong evidence.)
- **(b) Next proof obligation plus changed facts.** The task's smallest unproved
  obligation: its test, subject, input contract and current failure. Plus
  previously read facts that changed and bear on it (the existing
  `system-plan` changed-read refresh). (Astra #2 and #5.)
- **(c) Working set by recency.** Entities the agent's recent forms resolved, and
  entities named in the task or in recent results, ranked most-recent-first. Add
  the transition mix only if it beats plain recency on held-out Seon data. At
  top-1 it barely does (36.7 vs 35.6).

**Never omitted by ranking:** task must-reads, actionable errors and required
refresh evidence.

**Hysteresis** is left out of the first cut. It needs prior membership, which
should come from recorded exposures, not an atom.

**Compute** *(estimates unless noted)*:

| arm | per turn | memory |
|---|---|---|
| (a) contracts | 2–15 ms | 1–5 MiB derived |
| (b) proof / changed facts | 5–40 ms (full reaching-test work excluded) | 1–10 MiB |
| (c) recency | ~1 ms (measured in Python) | the agent's own rows |

Renders go through the render cache, so ① depends on ②'s render prerequisite. A
cache hit validates freshness per entry; a compiled non-turn miss is ~1.5 ms today,
plus ~4 ms of key construction per call until that is memoized. **Turn renders
take 1.4–1.9 s** because the turn renderer queries the database itself, so that
owner must be fixed before this reaches every turn (`research/render-cache.md` §0).
Recomputation is keyed on the evaluation rows each derivation reads, never on the
commit alone (CLAUDE.md "Reuse content-valid work").

**Build** *(estimates, from Astra's pricing)*: (a) 2–4 days, +45–85 lines plus
40–70 verification; (b) 3–5 days, +55–95 plus 45–80; (c) 1–2 days. It retires the
walk-as-selector path: `:seon.render/units` inclusion use, the `issue-opening`
variants, `context-relevant?` and `toolkit-namespaces`. The deletion count must be
measured before the cut is accepted.

**Screens first, no new code paths:**
- **(a) Known-head oracle.** 30 historical call-construction or refusal cases, with
  the true next function supplied. Stop if the slot view can't expose the needed
  schema/key in ≥ 24/30, or isn't smaller and more usable than `doc`.
- **(b) Proof coverage.** 10 tasks with concrete proofs. Stop if ≥ 3 have no useful
  proof links, needed definitions are missed in > 1, or context doesn't shrink by
  ≥ 30% at equal coverage.
- **(c) Recency at equal budget.** Replay against the walk at an equal token
  budget. It must beat the walk by a margin set before the run.

### ② Form assistance: a known-head options menu and an advisory check

**What:**
- **`options` for one known head.** Takes a form with `_` holes. Returns data per
  hole: filled by the existing defaults-by-schema-name join (`arity-plan`,
  `seon.call-preparation`); or the arity choices, the literal-map keys (via
  `m/children` and `m/deref-all`), or an explicit unknown.
  - Lexical bindings come from the parsed form without evaluating their
    initializers; they are distinct from live session bindings.
  - Producer suggestions are limited to declared named-schema matches.
  - Any new enumerator row must first prove an existing extension seam, per the
    no-second-registry law.
- **Advisory precheck of each completed form:** resolution, arity, and
  `malli.core/validator` on complete literal arguments only. It reports; it does
  not cancel or execute. **Streamed evaluation and cancellation are deferred:**
  replies continue after an ordinary error value today (`turn.clj:4895–4903`),
  and the stream sink publishes replaceable snapshots, not admissions. Changing
  either is a semantic decision for the owner.
- **The web REPL autocomplete** calls the same `options` later.

**Compute** *(estimates)*: < 5 ms warm per `options` call for small attributes; a
few ms per precheck using cached compiled validators; negligible memory.

**Build** *(estimates)*: `options` 2–4 days, +60–100 lines; precheck 1–2 days;
schema tightening (e.g. `seon.cluster.boot/connection` `:string` →
`:seon.boot/cluster-name`, which leaves 2,611 holes open) half a day. The web UI is
+3–5 days, ~80 Clojure + 60 JS lines, deferred.

**Screen:** offline, the true filler must be in the top 5 on ≥ 70% of holes it
claims to close (measured: keys 81%, locals 88%). The precheck must catch ≥ 50% of
the statically catchable class (3.9% of the recent unique cohort) with zero false
refusals. Agent usage alone can't judge a menu, so the pilot tests it as an arm.

### ③ Enabling work: trustworthy evidence, a sound render path, ordered tests

These make ① and ② measurable and affordable. They are maintenance with their own
value, not product features.

- **Exposure and use evidence (diagnostic).** What was shown is already recorded:
  `:seon.eval/shown` and context capture. Add, per evaluation, the symbols its
  forms **directly reference** (reader, cheap) alongside the existing
  `:fn-entries` count (`seon.eval.edn:36`).
  - Exercised calls from profile cells are **not** attributable yet. Cells are
    process-wide, include other threads, and `explain` filters them. Attribution
    must be proven at the evaluation/contract owner first.
  - Label all of it "observed", never "used". Usefulness comes only from ablation.
  - Cost *(estimate)*: 2–4 days; a profile snapshot is 80–160 KB per bracket
    (calculated).
- **Render prerequisite.** The owner's LRU, built as one region of the pending
  central Caffeine cache (task `edc8cb02dfc2`, `research/render-cache.md`):
  - bounds today's 69.8 MB unbounded cache;
  - memoizes the ~4 ms reach digest to a map lookup;
  - moves page-update interest to the page registration in the same cut.

  Estimated +55 / −435 to −465 lines, excluding the central cache itself. Fixing
  the turn renderer's own queries is unpriced; measure first.
- **Ordered tests in the edit loop.**
  - Rank by call distance and duration.
  - Stop at the first red; untried tests answer a typed `:pending`, never green.
  - Route the install gate's separate candidate loop (`sci/eval.clj:4858`, which
    runs all tests after a red) through the one ordered runner.
  - Merge policy is unchanged: task tests plus root's checks (CLAUDE.md:158).

  Estimated 1.5–2 days, +40–60 / −20–40. Replacing static reach with observed
  reach is **not** proposed: observed reach is diagnostic under CLAUDE.md's proof
  rules and misses macros, `def`s and schema dependencies.

## 2. All candidates compared

| candidate | evidence | per-turn / per-call | memory | build | verdict |
|---|---|---|---|---|---|
| ①(a) contract context | strong (typed-hole literature) + measured closed holes | 2–15 ms* | 1–5 MiB* | 2–4 d* | **do: screen first** |
| ①(b) proof + changed facts | Astra; refresh already exists | 5–40 ms* | 1–10 MiB* | 3–5 d* | **do: screen** |
| ①(c) recency working set | measured 69.6% top-10 | ~1 ms | small | 1–2 d* | **do** |
| transition/logistic ranker | +4 points top-10 over recency | ~1 ms | a few MB* | +2 d* | only if it beats (c) on Seon data |
| ② `options` + precheck | measured menus; 3.9% catchable | < 5 ms* | ≈ 0 | 3–6 d* | **do** |
| streamed eval / cancel | semantics unresolved | — | — | 3–4 d* + decision | owner decision first |
| situation memory (error-keyed lessons) | moderate–weak; gains vanish at equal budget | Datalog trigger | small | unpriced | cheap challenger arm in the pilot |
| CLM-8B semantic ranker | lost at next-file (8% vs 36% top-1) and at every in-form hole; rank-sum made structural worse; won only on 70 first touches | 0.28–0.6 s | 16.4 GB | fine-tune + eval | challenger for first touches only, after fine-tuning |
| local constrained decoding | fewer invalid forms, modest correctness | +5–25%/token* | 1–20 GB | 5–8 d* | only with a local-agent tier |
| vectors in Datahike (Proximum) | sync hashes the full index per commit | — | — | — | no |
| plan extraction from visible text | names the next target 1% of the time | — | — | — | no |

\* estimate.

**Bounded CLM run on in-form holes** (150 evals, ~10 GPU-minutes; appended to
`research/form-assembly.md`). Top-1, CLM vs structural:

| hole | CLM | structural |
|---|---|---|
| program head (rerank of top-50) | 2.6% | 33.3% |
| declared key | 36.1% | 74.7% |
| local | 26.2% | 39.0% |

- Combining the two by rank-sum was worse than structural alone.
- None of the pre-registered bars was met.
- Cost: 282 ms per state encode and 16.4 GB peak Metal memory.

Zero-shot CLM has no role on the hot path.

## 3. The pilot that decides success

Screens first (①a, ①b, ②); none needs call-evidence infrastructure. Then a paired
pilot on minimal prototypes:

- **Fixtures:** frozen task states, program revision, branch fixtures, model
  settings and acceptance checks. Split by task family and time; randomize arm
  order; repeat stochastic runs. Exclude cases whose private state can't be
  rebuilt, and report them.
- **Arms, all at an equal total budget with mandatory evidence fixed:**
  - the current walk;
  - task, recency and contract context;
  - proof/changed-fact context;
  - plus the `options` arm and the memory challenger, factorially where affordable.
- **Primary measure:** independently verified task success, with paired intervals
  and a non-inferiority margin set before the run.
- **Secondary measures:** time to accepted result, billed cost per success
  (including cache hit/miss tokens), repair turns, errors, required evidence
  omitted, CPU time and peak memory.
- **Size:** 20–30 pairs is a feasibility screen only. Size the real run from the
  observed variance. An inconclusive interval means more evidence, not a verdict.
- **Cost:** paid provider runs. Price them from a fixed model, repetition count and
  token cap before spending. **Needs the owner's go-ahead.**

## 4. Corrections found along the way

- **My merge-gate claim was wrong.** It was checked against the superseded law;
  since c03761415, merge requires the task's tests plus root's checks, and
  reaching-test counts are shown, not gated. **Retracted to the orchestrator.**
- **Test invalidation still unowned:** 246 tests invalidated per change (median),
  1,481 at p90; nothing reruns them. Routed.
- **Other routed items:**
  - `gate-set` broke on the pre-restart JVM; fixed on HEAD. The refusal-building
    bug is task 407264e2b69d.
  - The llm-providers skill had stale citations; lane `skills-citations-opus` is
    sweeping them.
  - `seon.cluster.boot/connection` takes `:string` where `:seon.boot/cluster-name`
    exists.
- **README fixed:**
  - the transcripts are 538 MB for top-level Claude sessions (1.9 GB with
    subagents);
  - the render cache is per branch, not per agent;
  - walk depth differs by path (2 vs 3).
