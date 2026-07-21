---
type: research
status: active
tags: [research, agent]
---

# Evolution Engines — Deep-Dive Recon (which to fork for Seon's self-evolving memory loop)

Recon date: 2026-06-30. This reads the ACTUAL vendored source of the four
candidate engines and maps, per engine, the propose → evaluate → select →
archive/diversity loop with real `file:line` cites and verbatim snippets. It is
the build-decision companion to [[self-evolving-memory-survey-2026-06-29]] (the
literature survey). Where the survey says "EvolveMem is the #1 match", this file
says *how* it works in code and *exactly where our substrate plugs in*.

The decision this grounds: **fork which engine as our base**, and **graft what**
from the others, so OUR genome (datahike fns / schema-as-data) + OUR fitness (a
fresh-context child agent that must store-then-retrieve after a restart) drop
into a proven control loop instead of a hand-rolled one.

---

## TL;DR — recommendation up front

**Fork EvolveMem's control loop as the base; graft funsearch/AlphaEvolve's
QD-archive and openevolve's evaluator-cascade; keep ADAS's code-as-genome
representation for the genome itself.**

- **Base = EvolveMem** (`reference-code/SimpleMem/EvolveMem/evolvemem/`). It is
  the only one of the four that already implements the *exact* control structure
  the survey prescribed — **LLM-diagnoses-from-failure-logs → objective-fitness-
  selects → elitist accept/revert → explore-on-stagnation → meta-propose-new-
  dimension**, plus a guarded step-size cap, an attempt-history fed back to the
  proposer, and a symptom-gated recipe library (its "archive of what worked").
  funsearch/openevolve/ADAS are *generic* program-evolvers; EvolveMem is that
  loop *already specialized to memory* and *already split judge≠selector*. We
  port its orchestration, not its config-vector representation.
- **Graft 1 — the QD archive (funsearch islands + openevolve MAP-Elites).**
  EvolveMem keeps a *single mutable incumbent* (+ a linear revision history with
  rollback). That is the local-optimum/collapse risk the survey flagged. Replace
  EvolveMem's `best_config` scalar with a **MAP-Elites grid of memory-design
  variants over behavioral features** (e.g. store-token-budget × recall-accuracy ×
  schema-shape) on an **island model with periodic migration**, so randomness
  explores and we keep diverse stepping-stones, not one winner.
- **Graft 2 — the evaluator cascade (openevolve/AlphaEvolve).** Our fitness (a
  fresh child agent spawned through the candidate design) is *expensive*. Adopt
  openevolve's **cascade_thresholds** pattern: a cheap static/tiny-probe stage
  gates the full fresh-agent spawn, so most candidates die cheaply.
- **Graft 3 — code-as-genome + reflexion self-refine (ADAS).** EvolveMem evolves a
  config *vector*; our differentiator (per the survey) is that the genome is a
  *real datahike fn + schema*. ADAS proves the "genome IS a code string, exec'd
  and run as the candidate, archive fed back into the meta-prompt, with 2 reflexion
  refine passes before scoring" pattern — that is exactly how a candidate
  `my.kb/remember`+`my.kb/recall` pair should be proposed, repaired, and admitted.

The honest one-liner: **"EvolveMem's guarded diagnose→select→revert loop, with a
funsearch/AlphaEvolve QD-archive instead of a single incumbent, an openevolve
cheap→expensive cascade in front of our fresh-child fitness, and an ADAS
code-string genome — on a datahike substrate."**

---

## 1. EvolveMem — the leading fork candidate (read in full)

Location: `reference-code/SimpleMem/EvolveMem/evolvemem/`. (There is a second,
near-identical copy under `reference-code/SimpleMem/simplemem/evolver/` — the
embedded "degraded" live optimizer; cited separately below where it adds the
*online policy-revision + promotion-gate* mechanism the paper runner lacks.)

There are in fact **two distinct evolution paradigms inside SimpleMem**, and we
want pieces of both:

- **(A) The paper runner** (`EvolveMem/.../evolution.py`) — *offline, batch*
  evolution of a **retrieval CONFIG dataclass** against a labeled QA set
  (LoCoMo/MemBench), with diagnosis + elitism + meta-analyzer + cookbook.
- **(B) The embedded live optimizer** (`simplemem/evolver/policy_store.py`,
  `candidate.py`, `promotion.py`) — *online* evolution of a `MemoryPolicyState`
  with a **JSON revision history + `rollback()`** (a real lineage) and a
  **multi-axis `should_promote` gate** (the accretive-or-revert gate, but on
  several quality deltas, not one F1 number). This is the closer analog to "an
  agent quietly improving its own live memory policy".

### A.1 — Representation: a memory design IS a `RetrievalConfig` dataclass (the "structured action space")

The genome is a flat, typed, bounded dataclass — the paper's "full retrieval
configuration as a structured action space."
`EvolveMem/evolvemem/multi_retriever.py:39-203` (`@dataclass class
RetrievalConfig`). The evolvable surface (verbatim field set, abridged):

```python
class RetrievalConfig:
    semantic_top_k: int = 20
    keyword_top_k: int = 8
    structured_top_k: int = 5
    max_context: int = 25
    enable_entity_swap: bool = True
    fusion_mode: str = "first_found"   # first_found|weighted_sum|rrf|*_only
    weight_semantic / weight_keyword / weight_structured: float
    time_decay_half_life_days: float | None = None
    reflection_rounds: int = 0
    answer_style: str = "concise"
    per_category_overrides: dict = field(default_factory=dict)
    enable_query_decomposition / enable_intent_planning: bool
    enable_answer_verification: bool ; verification_style: str
    enable_kg_expansion: bool ; mmr_diversity_weight: float
    locomo_cat1_single_fact ... locomo_cat5_mcq: bool   # adapter prompt flags
```

The **valid ranges of every knob** are declared centrally in the diagnosis layer
(`diagnosis.py:376-420`): `INT_RANGES`, `FLOAT_RANGES`, `ENUM_VALUES`,
`BOOL_FIELDS`. Every LLM proposal is clamped to these before it is applied
(`diagnosis.py:533-558`) — *the action space is type-checked + range-clamped at
the boundary.* (This is structurally identical to our `schema/register!` +
instrumentation discipline — the analog is exact.)

The deliberately-weak starting point is `weak_initial_config()`
(`evolution.py:47-74`): semantic off, BM25 k=5, tight context — "every evolvable
knob set to its weak-but-safe value so evolution has room to climb." The
prior-art-equivalent `strong_initial_config()` and the hand-known terminal
`evolved_config()` (`evolution.py:77-143`) exist for ablation/comparability.

### A.2 — The loop: `EvolutionEngine.evolve()` (`evolution.py:402-896`)

One round (the docstring's pipeline, `evolution.py:6`): *Extract → Index →
Retrieve → Answer → Evaluate → Diagnose → Adjust → Repeat.*

1. **Extract once** (`evolution.py:424-436`) — memories extracted from sessions
   (or loaded from cache); refined later only if diagnosis finds coverage gaps.
2. **Build index + answer all QA** (`_evaluate_qa`, `evolution.py:898-1057`) —
   retrieves per the current config, generates an answer per question.
3. **Score** (`evolution.py:487-495`) — token-level F1 per question
   (`_compute_f1`, `evolution.py:259-278`), averaged → `overall_f1`; this is the
   **objective fitness**. Also a per-category F1 breakdown and a `zero_f1_count`.
4. **Elitist accept/reject** of the move that *entered* this round
   (`evolution.py:510-598`) — see A.4.
5. **Diagnose** (`diagnostics.diagnose`, `evolution.py:609-614`) — runs AFTER
   elitism so the LLM sees the (possibly reverted) incumbent. See A.3.
6. **Suggest config update** (`suggest_config_update`, `evolution.py:641-648`) —
   capped to `max_changes_per_round` fields.
7. **Meta-analysis across all rounds** (`meta_analyzer.analyze`,
   `evolution.py:684-692`) — revert / focus / explore / propose-new-dim. See A.5.
8. **Apply diff** to the live config (`evolution.py:751-784`) — only fields the
   dataclass actually has, recording a `pending_diff` so next round's accept/reject
   can attribute the outcome.
9. **Optional targeted re-extraction** if diagnosis found `missing_keywords`
   (`evolution.py:787-796`) — the only path that grows the *stored memories*, not
   the config.

### A.3 — Diagnosis: the LLM reads failure logs, proposes targeted moves (`diagnosis.py`)

This is EvolveMem's heart and the survey's headline. `MemoryDiagnostics.diagnose`
(`diagnosis.py:297-373`) first computes **deterministic** failure structure:
- failure classification — `zeros`, and within zeros, **abstention vs
  wrong-answer** by string-matching the prediction against
  `abstention_patterns` (`diagnosis.py:334-345`);
- `low_f1_count` (0<F1<0.3), per-category F1, **category_weaknesses** for any cat
  under 0.3 (`diagnosis.py:356-364`);
- **coverage-gap detection** — `_detect_coverage_gaps` (`diagnosis.py:653-666`)
  finds reference-answer keywords *absent from the memory corpus* (the signal
  that drives re-extraction, not config change).

Then `_llm_diagnosis` (`diagnosis.py:668-947`) builds a rich prompt and asks the
LLM for the next move. What it *reads* into the prompt (the failure log the
survey describes):
- a failure summary with per-cat zero counts + **per-cat abstention / relative-date
  / paraphrase signatures** computed by regex over predictions
  (`diagnosis.py:698-748`) — these are the symptoms that gate specific flags;
- a **per-subcategory F1 breakdown** (`diagnosis.py:751-761`);
- **stratified worst-case sample failures** (2-3 per category, `diagnosis.py:766-779`);
- a **TODO checklist of tier-1 levers still OFF** in the incumbent
  (`diagnosis.py:798-840`) — "pick ONE each round until empty";
- the **matched cookbook recipes** (A.6);
- the **attempt_history** of prior moves with accept/reject + delta
  (`diagnosis.py:846-869`).

The `DIAGNOSIS_PROMPT` (`diagnosis.py:22-209`) is itself a worked artifact: it
hard-codes a **step-size constraint** ("at most 2 changes per round",
`diagnosis.py:72-79`), an **ANTI-PLATEAU rule** ("if last 2+ rounds REJECTED you
MUST invoke an unused architectural recipe — familiar scalar tweaks will not
resolve a plateau", `diagnosis.py:88-97`), a **priority order** of high-ROI levers
(`diagnosis.py:99-133`), the full **action-space documentation** (every knob, its
range, its symptom, its expected lift, `diagnosis.py:134-167`), and a **decision
rubric** (`diagnosis.py:171-182`). Output is JSON `parameter_suggestions` +
`per_category_proposals` + optional `use_recipe` (`diagnosis.py:183-207`).

`suggest_config_update` (`diagnosis.py:422-640`) then **validates and applies**
the LLM's proposal: benchmark-prefix gating so `locomo_*` flags can't pollute
other benchmarks (`diagnosis.py:447-460`); an **impact-tier re-rank** so under the
2-change cap the adapter/architecture flags beat scalar tweaks
(`diagnosis.py:506-526`); per-field range clamping; a recipe applied atomically as
ONE change slot (`diagnosis.py:471-491`); and a thin **heuristic fallback** only
if the LLM produced nothing (`diagnosis.py:622-638`).

### A.4 — Select: elitist accept/revert-on-regression (the accretive-or-revert gate)

`evolution.py:510-598`. Round 0 is the baseline. Each later round computes
`delta = overall_f1 - best_f1` and **accepts only if `delta >
acceptance_threshold`** (`EvolutionConfig.acceptance_threshold = 0.003`,
`evolution.py:208`):

```python
delta = overall_f1 - best_f1
accepted_this_round = delta > self.config.acceptance_threshold
...
else:   # not accepted
    if improved_strict:      # positive but sub-threshold → keep as new baseline
        ...
    else:                    # true regression → REVERT
        consec_noaccept += 1
        for k, v in best_config.items():
            if hasattr(ret_config, k):
                setattr(ret_config, k, v)   # roll config back to incumbent
```

Nuance worth stealing: a **soft-accept** band — a strictly-positive but
below-threshold delta is *kept as the new baseline* (so small real gains aren't
discarded) but does NOT reset the no-accept counter (`evolution.py:545-565`).
Termination is `max_consec_noaccept = 5` consecutive rejections
(`evolution.py:580-598`). The accept/reject record is appended to
`attempt_history` and fed back to the diagnosis LLM (so it never re-proposes a
move already labeled REJECTED, `diagnosis.py:84-86`). The step-cap
(`max_changes_per_round = 2`) is what makes each accept/reject *attributable to a
single direction* — pure hill-climbing.

### A.5 — Explore-on-stagnation + propose-new-dimension (the meta layer)

`meta_analysis.py`. `MetaEvolutionAnalyzer.analyze` (`meta_analysis.py:186-289`)
sees the *whole round history* and fires **deterministic triggers** before any LLM
call:
- **regression** → revert to best round (`_is_regression`: latest < prev by >2pp,
  `meta_analysis.py:114-118`, `:207-219`);
- **stuck subcategories** (a sub below 0.20 for 3 straight rounds) →
  `decision_type="propose_new_dim"` with a concrete new-capability proposal
  (`_stuck_subcategories`, `:127-159`, `:221-240`) — "the current action space
  cannot fix them";
- **stagnation** (3-round span < 0.01) → `decision_type="explore"`, perturb an
  untried `fusion_mode` (`_is_stagnant`, `:120-125`, `:242-260`);
- **worst-subcategory focus** → recommend a `per_category_override`
  (`:262-275`).

New-dimension proposals are persisted to `meta_proposals.jsonl`
(`evolution.py:737-748`) — the framework *nominates where to grow itself* (the
"授人以渔" loop, `meta_analysis.py:16`). NB: under elitist mode the engine
*skips* meta-applied param overrides + meta-reverts (`evolution.py:698-734`) to
avoid double-moves busting the single-direction discipline — meta then serves
mainly as the new-dimension proposer.

### A.6 — Archive / elitism: the cookbook + the (B) revision-history-with-rollback

EvolveMem's "archive of what worked" is the **evolution cookbook**
(`evolution_cookbook.py`): a hand-checked library of `Recipe` objects, each a
*(symptom-trigger → pre-validated config bundle)* with an `expected_lift_pp`
(`evolution_cookbook.py:30-38`). `match_recipes` (`:370-401`) returns the recipes
whose `trigger(report, config, history)` fires this round; they're surfaced to the
diagnosis LLM as "capability cards" it can invoke atomically. Example —
`intent_aware_multihop_planning` fires when `Cat1 or Cat3 F1 < 0.40 AND fusion ==
rrf` and proposes the whole intent-planning bundle as one move, `+15pp`
(`:85-113`). `escape_plateau_scalar_sweep` fires on `>=2` consecutive no-accepts
(`:347-366`). This is a *curated, not evolved* archive — the compressed memory of
prior experiments, checked in as code (`:11-15`).

The *true* archive-with-lineage is in paradigm (B), the live optimizer:
`MemoryPolicyStore` (`simplemem/evolver/policy_store.py:35-92`) writes every
accepted `MemoryPolicyState` to a JSON file **plus an append-only
`.history.jsonl`** of `MemoryPolicyRevision{timestamp, reason, state}` and exposes
**`rollback(steps)`** (`:84-92`) — a literal traceable lineage you can revert a
poisoned branch from. The promotion gate `should_promote`
(`simplemem/evolver/promotion.py:20-44`) is the **multi-axis accept gate** we
should steal directly: a candidate is promoted only if it clears *several* delta
thresholds at once (query/continuation/response overlap, specificity, focus,
value-density, grounding, coverage) AND `sample_count >= 10` AND
`zero_retrieval_delta <= 2` AND `candidate_beats_baseline`. Candidate generation
is a **bounded local grid** around the live policy (`candidate.py:8-43`: vary
retrieval mode, injection budget, and each weight by ±δ). This is exactly an
online, single-incumbent hill-climb with a quality-gate and a rollback log.

### EvolveMem mechanism map

| Stage | Mechanism | Cite |
|---|---|---|
| Representation | typed bounded `RetrievalConfig` dataclass; ranges/enums declared centrally; weak-init for big delta | `multi_retriever.py:39-203`; `diagnosis.py:376-420`; `evolution.py:47-74` |
| Propose | LLM reads deterministic failure log (abstention/wrong/coverage + per-cat regex symptoms) + TODO + recipes + attempt-history → JSON moves, range-clamped, impact-reranked, step-capped to 2 | `diagnosis.py:297-373`, `:668-947`, `:422-640` |
| Evaluate | token-F1 vs reference QA, per-question + per-category; abstention/wrong split; coverage-gap keyword scan | `evolution.py:259-278`, `:487-495`; `diagnosis.py:653-666` |
| Select | elitist: accept iff `delta > 0.003`; soft-accept band; revert-config-on-regression; converge after 5 no-accepts | `evolution.py:510-598` |
| Explore | meta-layer: regression→revert, stagnation→perturb untried knob, stuck-sub→propose-new-dimension (logged) | `meta_analysis.py:186-289`; `evolution.py:737-748` |
| Archive/diversity | curated symptom-gated recipe cookbook (capability cards); (B) JSON revision-history + `rollback()`; multi-axis `should_promote` | `evolution_cookbook.py:30-401`; `policy_store.py:35-92`; `promotion.py:20-44` |

**What's missing vs the survey's playbook (the gaps we graft over):** (1) NO
quality-diversity archive — a single mutable incumbent + linear history, the
collapse risk POET/MAP-Elites warn about. (2) NO evaluator cascade — every round
scores the *full* QA set (fine for a static set, fatal for a fresh-agent fitness).
(3) NO anti-cheat layer — fitness is a labeled QA set the agent doesn't author, so
it never confronts answer-key storage / probe overfit; OUR fresh-child fitness
must add the hidden-checker + distractor defenses the survey details. (4) Genome is
a config vector, not code — our datahike-fn genome needs the ADAS representation.

---

## 2. funsearch — program database + island model

Location: `reference-code/funsearch/implementation/`. This is the published
DeepMind skeleton (sandbox + LLM are abstract hooks). The loop is a forever-running
**DB.get_prompt → LLM.draw_samples → Evaluator.analyse (sandbox run) →
DB.register_program** cycle (`funsearch.py:42-68`).

### Representation — code-as-data, body substitution is trivial

A candidate is a `Function` (`code_manipulation.py:33-65`): parsed
`name/args/body/return_type/docstring`; `__str__` reassembles source. A `Program`
(`:68-101`) is `preface` (imports/globals) + `functions`. Mutation is literally
`evolved_function.body = new_body` (`evaluator.py:84`) +
`dataclasses.replace(template, functions=…)` (`programs_database.py:270`).
**Storage hierarchy:** `ProgramsDatabase → num_islands Islands → Clusters (keyed by
score *signature*) → list[Function]`. `Signature = tuple[float,…]` of per-test
scores (`programs_database.py:30`). Two decorators mark roles:
**`@funsearch.evolve`** = the fn the LLM rewrites, **`@funsearch.run`** = the
evaluator entry point (`_extract_function_names`, `funsearch.py:27-37` — requires
exactly one of each).

### Propose — a worst→best versioned chain, Boltzmann-sampled

`Sampler.sample()` (`sampler.py:53-62`) loops: `get_prompt → draw_samples
(samples_per_prompt=4) → route each back to the SAME island via prompt.island_id`.
`ProgramsDatabase.get_prompt` picks an island **uniformly at random**
(`:104-108`). `Island.get_prompt` (`:205-234`) selects `functions_per_prompt=2`
clusters by **Boltzmann-softmax over cluster scores with a linearly-decaying
temperature**:

```python
temperature = init * (1 - (self._num_programs % period) / period)   # :212-215
probabilities = _softmax(cluster_scores, temperature)
```

It samples one program per chosen cluster, **sorts ascending by score**, renames
them `{fn}_v0…_vk` (worst→best), and appends an empty `{fn}_v{k+1}` header for the
LLM to *continue* — so the model sees an improving chain and writes the next link.
Within a cluster, `sample_program` softmaxes over **negative normalized length**
(`:292-297`) — an Occam pressure toward shorter equivalents.

### Evaluate — multi-input scoring, validity gates, NO cascade

`Evaluator.analyse` (`evaluator.py:135-155`): trim the LLM output to a valid AST
(`_trim_function_body` repeatedly `ast.parse`s, dropping trailing lines on
`SyntaxError`, `:46-65`), then loop over `self._inputs` running
`Sandbox.run(program, function_to_run, input, timeout=30)` per input →
`scores_per_test = {input: score}`. A score is kept only if it ran OK, **did not
call an ancestor version** (`_calls_ancestor` — the anti-cheat that blocks a
candidate from delegating to a prior `{fn}_v*`, `:103-112`), and returned a numeric.
**There is NO cheap→expensive cascade in the OSS skeleton** — it scores every input
flatly; the paper's cascade is not in this code. `scores_per_test` is the seam.

### Select + Archive/diversity — score-signature clusters, islands, periodic reset

A program collapses to one score = the **last test's** score (`_reduce_score`,
`:49-51`); its **signature** = the full sorted tuple of all test scores
(`_get_signature`, `:54-56`). Registration buckets by signature: same signature ⇒
append to the existing `Cluster`, new ⇒ new cluster (`:191-203`). Diversity comes
from **`num_islands=10` independent sub-populations** + a **periodic cull-and-reseed**
checked on every register (`:142-167`):

```python
if time.time() - self._last_reset_time > reset_period:   # default 4h
    reset_islands()
# reset_islands: rank islands by best score, WIPE the weakest half (5 of 10),
# reseed each wiped island with the single BEST program copied from a randomly
# chosen SURVIVING island.
```

This is the migration/diversity-renewal: stagnant lineages die, best discoveries
propagate. **Config defaults** (`config.py`): `functions_per_prompt=2`,
`num_islands=10`, `reset_period=4h`, `cluster_sampling_temperature_init=0.1`,
`…_period=30000`, `samples_per_prompt=4`, `num_samplers=15`, `num_evaluators=140`.

### funsearch mechanism map

| Stage | Mechanism | Cite |
|---|---|---|
| Representation | `Function` body-as-data in a template `Program`; clusters keyed by score signature inside islands | `code_manipulation.py:33-101`; `programs_database.py:30,191-203` |
| Propose | uniform island pick → Boltzmann-temp select of 2 clusters → worst→best versioned chain → LLM continues; length-biased within cluster | `programs_database.py:104-234,292-297`; `sampler.py:53-62` |
| Evaluate | AST-trim → sandbox-run over all inputs → `scores_per_test`; reject non-numeric / ancestor-calling cheats; NO cascade | `evaluator.py:46-155` |
| Select | reduce to last-test score; bucket by full-signature cluster; per-island best tracked | `programs_database.py:49-56,110-123` |
| Archive/diversity | 10 islands; every 4h wipe weakest 5, reseed each from a survivor's best (migration) | `programs_database.py:142-167` |

**For us:** the *island reset/migration* is the cleanest QD-renewal mechanism to
steal — simpler than full MAP-Elites and directly portable. The
**signature-clustering** (functionally-equal programs bucket together) maps onto
"memory designs that produce the same recall profile". The **ancestor-call
anti-cheat** is a real, transferable defense (block a candidate memory-fn from
secretly calling the baseline `my.kb/recall`). The missing cascade is exactly what
openevolve adds.

## 3. openevolve — AlphaEvolve OSS (MAP-Elites + diffs + cascade)

Location: `reference-code/openevolve/openevolve/`. An open-source reimplementation
of DeepMind's AlphaEvolve. Active runtime = process-parallel controller
(`process_parallel.py`); `iteration.py` is the single-process twin. It is funsearch
+ four serious upgrades (diffs, MAP-Elites, cascade, side-channels).

### Representation — whole files, EVOLVE-BLOCK markers, diff edits

Unit = a full `Program` (`database.py:43-77`): `id, code (whole file), metrics,
parent_id, generation, metadata (island/migrant), embedding?, artifacts,
changes_description`. The **user supplies two files**: an initial program with
**`# EVOLVE-BLOCK-START` / `# EVOLVE-BLOCK-END`** markers delimiting the mutable
region (`parse_evolve_blocks`, `code_utils.py:9-37`), and an **`evaluator.py`
exposing `evaluate(program_path) -> dict`** (optionally `evaluate_stage1/2/3`).

### Propose — SEARCH/REPLACE diffs (the headline FunSearch divergence)

Default **`diff_based_evolution=True`** (`config.py:421`). The LLM is asked for
targeted **SEARCH/REPLACE diff blocks**, not a full rewrite
(`templates.py:40-65`): `<<<<<<< SEARCH … ======= … >>>>>>> REPLACE`. Application
(`code_utils.py:40-75`) is a **line-wise exact match-and-splice**; if no diff
parses, the iteration is **discarded** (`return None`). Full-rewrite (fenced
```python block) exists only as the non-default fallback (`:95-120`). The prompt
sampler (`prompt/sampler.py:51-97`) stuffs in `num_top_programs=3` top + `num_diverse_programs=2`
diverse programs plus the artifacts side-channel. Child stamps
`generation=parent.generation+1`, `parent_id=parent.id`.

### Evaluate — cascade gating + artifacts feedback (the two other divergences)

`evaluate_program` writes code to a temp file and calls the user evaluator in an
executor with a timeout (`evaluator.py:132-358`). **Cascade**
(`cascade_evaluation=True`, `cascade_thresholds=[0.5,0.75,0.9]`,
`config.py:370-371`): `_cascade_evaluate` (`:360-469`) runs `evaluate_stage1`,
gates on `_passes_threshold`, and only then runs stage 2, etc. — **the cheap stage
culls before the expensive stage**. `_passes_threshold` (`:668-707`) uses
`combined_score` else averages numeric metrics. **Artifacts side-channel**: the
evaluator may return `EvaluationResult(metrics, artifacts)`; stderr/traceback/timeout
flags are stored per-program and **fed into the next prompt** (`include_artifacts`,
`max_artifact_bytes=20KB`, `:216-240`) — a repair signal funsearch lacks. Optional
`use_llm_feedback` lets an LLM ensemble score qualitatively (weight 0.1). Each
iteration runs in a worker process over a serialized DB snapshot
(`process_parallel.py:442-471`).

### Select — MAP-Elites cell-elites + tunable island parent sampling

**Fitness and features are kept separate** so the QD axes never pollute selection:
`get_fitness_score` averages metrics *excluding* the feature-dimension keys
(`metrics_utils.py:69+`, `_is_better`, `database.py:1101-1129`). **Parent sampling**
is a 3-way roll (`_sample_parent`, `:1270-1288`) with `exploration_ratio=0.2`,
`exploitation_ratio=0.7`: `<0.2` random-from-island (EXPLORE), `<0.9`
random-from-archive-elites preferring current island (EXPLOIT), else random. The
LLM is *also* shown "inspirations" (island best + top elites + diverse cells,
`_sample_inspirations`, `:1554-1668`) — double-selection where inspirations ≠ parent.

### Archive/diversity — MAP-Elites grid INSIDE islands + ring migration

The core divergences from funsearch:
- **Per-island MAP-Elites grids** (`island_feature_maps`, one grid per island,
  `database.py:129`). Feature axes default `["complexity","diversity"]`
  (`config.py:322`) or any custom evaluator metric; raw values are min-max-binned
  into `feature_bins=10` cells (`_calculate_feature_coords`, `:834-966`).
- **One fitness-elite per cell** (`add()`, `:274-341`): occupy if empty, else
  replace iff `_is_better`. This is the MAP-Elites invariant.
- **Global `archive` (size 100)** replaces the worst member when full
  (`_update_archive`, `:1131-1176`); a never-lost **`best_program_id`** is tracked
  separately. `population_size=1000` cap deletes worst-fitness programs
  (`_enforce_population_limit`, `:1678-1755`).
- **Islands on top** (`num_islands=5`), round-robin assignment, with **lazy ring
  migration** (`migration_interval=50` gens, `migration_rate=0.1`,
  `config.py:336-337`): top programs **copied** (new UUID, `migrant=True`) into
  ring-adjacent islands `[(i±1)%n]` (`migrate_programs`, `:1780-1880`).
- Optional **embedding + LLM-judge novelty rejection** (`similarity_threshold=0.99`,
  `_is_novel`, `:1058-1099`) before admission; full checkpoint/resume of the whole
  grid+island state.

### openevolve mechanism map

| Stage | Mechanism | Cite |
|---|---|---|
| Representation | whole-file `Program`; user EVOLVE-BLOCK markers + `evaluate()` file | `database.py:43-77`; `code_utils.py:9-37` |
| Propose | default SEARCH/REPLACE **diffs** (line-splice), top-3+diverse-2 + artifacts in prompt | `templates.py:40-65`; `code_utils.py:40-75`; `prompt/sampler.py:51-97` |
| Evaluate | **cascade** stage1→2→3 gated by thresholds; artifacts + optional LLM-judge feedback; parallel workers | `evaluator.py:360-469,216-240` |
| Select | fitness excludes feature axes; explore/exploit/random = 0.2/0.7/0.1, archive-elite-weighted; inspirations≠parent | `database.py:1101-1129,1270-1288,1554-1668` |
| Archive/diversity | per-island MAP-Elites grid (one elite/cell), global archive-100, ring lazy migration, novelty gate | `database.py:129,274-341,1131-1216,1780-1880` |

**For us:** the **cascade** is the single most important graft — it makes an
expensive fresh-child fitness affordable (cheap static/tiny-probe stage1 gates the
full spawn). The **MAP-Elites grid** is the QD-archive the survey demands, and its
**fitness-excludes-feature-axes** rule is a clean way to keep "recall accuracy"
(fitness) orthogonal to "store-budget / schema-shape" (diversity cells). The
**diff edits** matter once the genome is a real multi-fn file (targeted edits to a
`my.kb` ns beat whole-ns rewrites). The **artifacts→next-prompt** loop is exactly
our instrumentation-error-as-repair-signal.

## 4. ADAS — meta-agent that programs agents in code

Location: `reference-code/ADAS/` (`_mgsm/` canonical; `_arc/` confirms the pattern
generalizes identically). ADAS evolves *whole agents represented as code strings* —
the purest "code-as-genome + archive-in-meta-prompt + reflexion-refine + exec-the-
string evaluation" of the four. (`MP` = `_mgsm/mgsm_prompt.py`, `S` = `_mgsm/search.py`,
`U` = `_mgsm/utils.py`.)

### Representation — agent = a dict; `code` is a literal `forward()` source string

```python
EXAMPLE = {                                  # MP:3-10
  "thought": "**Insights:** … **Overall Idea:** … **Implementation:** …",
  "name": "Name of your proposed agent",
  "code": "def forward(self, taskInfo):\n    # Your code here\n    return answer\n"}
```

The seed archive is 7 such dicts (`get_init_archive` → CoT, CoT-SC, Reflexion,
Debate, Step-back, QD, Role-Assignment, `MP:526-527`). Each `code` composes a fixed
primitive `LLMAgentBase(output_fields, name, role=, temperature=)` (defined in the
base prompt itself, `MP:288-393`) so the meta-agent sees the API it must target. The
runtime `class AgentSystem` (`S:140-142`) is the empty shell the generated `forward`
is bolted onto.

### Propose — Meta Agent Search: generate + 2 reflexion passes, archive in the prompt

The whole archive is JSON-serialized into the meta-prompt (`get_prompt`,
`MP:530-536`): `archive_str = ",\n".join(json.dumps(sol) for sol in archive)` →
substituted into `[ARCHIVE]`. **Every prior discovery, including its `fitness`
string, re-enters the prompt verbatim** — selection pressure is purely "the model
reads past fitnesses and tries to beat them" (no harness-side parent selection). The
base prompt defines fitness as "median and 95% Bootstrap CI … maximize the fitness"
(`MP:416`) and lists 4 `## WRONG Implementation examples` (`MP:431-482`). Then **two
reflexion refine steps run as one growing chat** (`S:181-197`): generate →
Reflexion-1 (critique Interestingness vs archive + Implementation Mistakes +
Improvement, `MP:492-518`) → Reflexion-2 ("use the WRONG-examples tips, revise the
code further", `MP:520-523`). The candidate is *refined before it is ever scored*.

### Evaluate — exec-the-string into a live fn, run over a seeded batch, bootstrap-CI

The killer reusable trick (`evaluate_forward_fn`, `S:278-290`):

```python
namespace = {}
exec(forward_str, globals(), namespace)
func = namespace[<the one name>]          # asserts exactly 1 callable
setattr(AgentSystem, "forward", func)     # bolt it on live
```

It then runs `forward` over a fixed seeded validation batch (`valid_size=128`) via a
`ThreadPoolExecutor`, scores each by exact match (`score_mgsm`), and returns a 0/1
`acc_list` (`S:291-332`). **Fitness = `bootstrap_confidence_interval(acc_list)`**
(`U:88-111`: 100k resamples → median + 95% CI) — the CI, not bare accuracy, is what
cures single-run noise (the survey's `pass^k` concern). A **debug/repair loop** gives
each candidate up to `debug_max=3` eval attempts: an exec exception (or ≈0 accuracy)
is appended to the chat and the model is asked to debug into a `debug_thought` field,
then re-eval (`S:204-225`); if it never produces a usable `acc_list`, the generation
is rolled back (`n -= 1`) and the candidate is simply **not archived**.

### Archive — append-only, unbounded, no parent selection

`next_solution['fitness'] = fitness_str; next_solution['generation'] = n+1;
archive.append(next_solution)` (`S:227-235`), re-dumped in full to one JSON run file
after every candidate (`S:238-240`); resume reads `archive[-1]['generation']`
(`S:147-153`). **No pruning, no population cap, no QD** — fully open-ended; "selection"
is implicit in the meta-agent reading fitness strings. (This is ADAS's weakness for a
long-running loop: the meta-prompt grows unboundedly.)

### ADAS mechanism map + relevance to evolving MEMORY fns

| Stage | Mechanism | Cite |
|---|---|---|
| Representation | agent = `{thought,name,code}`; `code` = literal `forward()` source composing `LLMAgentBase` primitives | `MP:3-10,526-527,288-393` |
| Propose | archive JSON → meta-prompt; generate then 2 reflexion critique/refine passes in one chat | `MP:530-536,492-523`; `S:181-197` |
| Evaluate | `exec(code)` → `setattr(AgentSystem,"forward")` → seeded batch → 0/1 acc → bootstrap-CI; debug_max=3 repair loop | `S:278-332,204-225`; `U:88-111` |
| Archive | append-only JSON, fitness+generation stamped, no prune/cap/parent-select | `S:227-240,147-153` |

**Relevance — four pieces transfer almost directly:** (1) **code-as-genome** — our
genome is a `(defn store! …)` + `(defn recall …)` source pair; Seon already persists
defining forms as `:seon.fn` datoms, so the genome is a first-class datom, *better*
than ADAS's JSON blob. (2) **archive-in-meta-prompt** = a reactive-context section
fn that queries prior memory-fn entities + their recorded fitness and renders them
into the proposer's context (derive-don't-store). (3) **reflexion + debug loop** maps
onto our eval path — a memory-fn that throws on `transact!`/`pull` feeds the
malli/instrumentation error back to the proposer to repair before scoring (our
errors are *richer* repair signal than raw Python tracebacks). (4) **exec-the-string
evaluation** IS our self-host `seon.eval`; the bootstrap-CI-over-a-seeded-batch
fitness transfers directly to a store-then-retrieve memory benchmark. **What to NOT
copy:** ADAS's unbounded append-only archive (use a QD/elite archive instead), and
its in-process single-shot fitness (our memory fitness is *stateful* — write-then-
read across turns/restarts — so the benchmark must persist between the store and the
recall, which datahike gives us natively).

## Comparison table

| Axis | **EvolveMem** | **funsearch** | **openevolve** | **ADAS** |
|---|---|---|---|---|
| Genome | typed config **vector** (`RetrievalConfig`) | one **fn body** (code-as-data) | whole **file**, EVOLVE-BLOCK region | agent as **code string** (`forward()`) |
| Specialized to memory? | **YES** (retrieval+extraction) | no (generic) | no (generic) | no (generic agents) |
| Propose mechanism | **LLM diagnoses failure logs** → targeted config moves, step-capped 2 | LLM continues a worst→best versioned chain | LLM **SEARCH/REPLACE diffs** | LLM gen + **2 reflexion** passes |
| Proposer sees | failure log + symptoms + TODO + recipes + attempt-history | 2 prior programs (Boltzmann-sampled) | top-3 + diverse-2 + artifacts | **whole archive** + fitnesses |
| Fitness | token-F1 vs labeled QA | sandbox numeric over inputs | evaluator `dict`, cascade | bootstrap-CI of 0/1 batch |
| Cascade cheap→expensive? | no | **no** (seam only) | **YES** (`cascade_thresholds`) | no |
| Select | **elitist accept iff Δ>0.003, revert-on-regression** | reduce-to-last-score, cluster best | fitness-elite per cell; explore/exploit/random | implicit (meta reads fitness) |
| Archive / diversity | curated **recipe cookbook** + linear revision history + `rollback()` | **10 islands**, 4h cull-weakest-5 + reseed | **MAP-Elites grid inside islands** + ring migration | **append-only**, unbounded, no QD |
| Explore-on-stagnation | **YES** (meta: perturb / propose-new-dim) | island reset | migration + exploration arm | (none explicit) |
| Anti-cheat present | none (labeled QA) | **ancestor-call block** | novelty gate (off by default) | WRONG-examples list |
| Repair loop | heuristic fallback | AST-trim only | **artifacts → next prompt** | **debug_max=3** error feedback |
| Best single steal | the **whole guarded control loop** | island **reset/migration** | the **cascade** + MAP-Elites | **code genome** + reflexion + exec-eval |

## Recommendation + the seams where Seon plugs in

### Base engine: fork EvolveMem's control loop

**Why EvolveMem and not the generic evolvers:** the other three are *general*
program-evolution engines; you would have to re-derive, on top of any of them, the
exact control discipline EvolveMem already ships — diagnosis-from-failure-logs,
judge≠selector, elitist accept/revert, step-size cap, attempt-history feedback,
explore-on-stagnation, and a propose-new-dimension escape hatch. That discipline is
~80% of the survey's "what makes this work, and what stops it cheating/collapsing".
EvolveMem is that loop *already specialized to memory* and already split into a
*diagnosing* LLM (`MemoryDiagnostics`/`MetaEvolutionAnalyzer`) and an *objective*
selector (the elitist F1 gate) — precisely the "LLM diagnoses, fitness selects"
topology we want. We fork the **orchestration** (`evolution.py` round loop +
`diagnosis.py` proposer + `meta_analysis.py` meta-layer + the `policy_store.py`
revision-history/rollback), and we **replace its representation and fitness**.

The honest caveat: we are NOT keeping EvolveMem's `RetrievalConfig` genome (we want
real fns, not a knob vector) nor its single-incumbent archive (collapse risk). So
"fork EvolveMem" means *port its loop shape and its guard-rails*, then graft the
genome from ADAS and the archive from funsearch/openevolve.

### The three grafts

1. **QD archive (from openevolve's MAP-Elites, with funsearch's island reset).**
   Replace EvolveMem's `best_config` scalar + linear history with a **MAP-Elites grid
   over behavioral features** — natural axes for memory: *store-token-budget* ×
   *recall-accuracy* × *schema-shape/size*, one elite fn-design per cell
   (openevolve `database.py:274-341`), fitness computed **excluding** the feature
   axes (`get_fitness_score`) so accuracy stays orthogonal to budget. Wrap it in an
   **island model with periodic cull-and-reseed** (funsearch
   `programs_database.py:142-167`) so a poisoned/stagnant branch dies and the best
   discoveries migrate. Keep EvolveMem's `rollback()` lineage on top for auditable
   revert (`policy_store.py:84-92`).

2. **Evaluator cascade (from openevolve).** Our fitness — spawn a fresh child agent,
   make it store-then-retrieve through the candidate design, score recall after a
   *restart* — is expensive. Put openevolve's **`cascade_thresholds`** in front
   (`evaluator.py:360-469`): **stage-1** = cheap static checks (does the candidate
   `store!`/`recall` compile, pass malli instrumentation, survive a 3-fact
   tiny-probe?); **stage-2** = the full fresh-child spawn + held-out distractor-laden
   battery + cross-restart recall; **stage-3** (optional) = the LLM hack-detector
   veto. Most candidates die at stage-1 for ~free. This is also where the survey's
   "cheap probe before a full spawn" lands.

3. **Code-as-genome + reflexion/repair (from ADAS).** The genome is the *actual
   datahike fn pair* — `(defn store! [fact] …)` + `(defn recall [query] …)` (or a
   `{:thought :name :store-code :recall-code}` map), persisted as `:seon.fn` datoms
   (code-as-data-runtime). Propose with ADAS's pattern: render the **archive of prior
   memory-fns + their recorded fitness as a reactive-context section** into the
   meta-agent prompt (`get_prompt`, `MP:530-536`), generate, then run **2 reflexion
   passes** + an **exec-error repair loop** (`debug_max`, `S:204-225`) where our
   *malli/instrumentation errors* are the repair signal — before the candidate ever
   reaches the cascade.

### Concrete seams where OUR substrate plugs in

- **Genome ↔ `:seon.fn` / `:seon.schema` datoms.** EvolveMem's `RetrievalConfig`
  field+range table (`diagnosis.py:376-420`) is replaced by our `schema/register!`
  surface: the "action space" is "which schema attrs + which fn forms"; the
  range-clamp-at-the-boundary discipline is already ours via instrumentation. A
  candidate is a transactable set of `:seon.fn`/`:seon.schema` forms, not a JSON dict.
- **Fitness ↔ a fresh-child drive on a scratch cluster.** EvolveMem's `_evaluate_qa`
  (`evolution.py:898`) becomes "mint a cold agent (`seon.agent/start!`) on a
  hermetic scratch conn, feed it facts-to-store + distractors under a token budget,
  `bin/seon restart pod`-equivalent reset, then ask novel cross-join questions and
  measure recall" — the gym's `run-scenario!`/`measure-context!` harness is the
  existing skeleton (the agent-fsm CLAUDE.md gym). Score = recall correctness;
  noise-robustness = `pass^k` (== ADAS bootstrap-CI).
- **Diagnosis ↔ a reactive section fn over the eval/turn log.** EvolveMem reads
  per-question failure logs; we read the child's *actual transcript + failed evals +
  empty-query results* (already in the DB) via a section fn — derive-don't-store,
  no separate failure store.
- **Judge ≠ selector + hidden checker.** Keep EvolveMem's split: the diagnosing LLM
  (Gemini-Flash) only *steers*; the *measured* fresh-child recall *selects*; add the
  survey's hack-detector as a **stage-3 veto** whose code + the probe answer-key are
  **never in the candidate-agent's context** (DGM's "hide the checker" finding).
- **Archive ↔ the program graph itself.** The MAP-Elites grid is queryable datoms
  (elite fn per cell), the lineage is the existing `:seon.fn` history / revision
  log; `rollback()` ↔ retract-and-restore a prior fn form.

**Net build sentence:** port EvolveMem's `evolve()` round loop + `diagnosis.py`
proposer + `meta_analysis.py` explore/propose-new-dim + `policy_store.py`
rollback; swap its config-vector genome for ADAS-style `:seon.fn` code genomes
proposed via archive-in-prompt + reflexion + instrumentation-repair; swap its
single incumbent for an openevolve MAP-Elites grid on funsearch islands; and put an
openevolve cascade in front of a fresh-child-agent fitness whose probe set, answer
key, and hack-detector are hidden from the candidate.
