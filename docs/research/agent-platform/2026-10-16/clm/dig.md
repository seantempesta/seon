---
title: "Dig: can CLM be an adaptive form/turn classifier, trained on Seon data?"
type: research
status: read-only dig (Haiku); evidence only, no decisions; nothing edited or committed except this file
created: 2026-10-16
tags: [research, context, agent-platform]
---

# Dig: CLM as an adaptive form/turn classifier

Owner question (2026-10-16): can the lightweight "System One" model CLM classify agent REPL forms and turns,
with classes that adapt rather than a fixed hand list, cheaply enough to replay every turn of every run, judging
each form against what was known at that step; and can it be fine-tuned on Seon's own data?

## 5 facts most relevant to that question

1. **CLM is a frozen encoder plus two small heads, and its "classes" are query-time text.** Qwen3-8B last-token
   embedding, then a state head and an action head (~20M trainable params, README "How it works"). A typed question
   carries its own option texts (`Choice.criteria`, `Score.criteria`, noul true/false) that are embedded at call time
   and softmaxed. So the label set is open vocabulary with no per-class training step, but how well an unseen label
   separates is decided by the head's training distribution (Nemotron QA, synthetic hard negatives, ADP/terminal
   traces), not by Seon's forms.
2. **Measured on Seon, untuned CLM lost to trivial baselines every time.** Next-file top-1: CLM 8% vs 36% for the
   structural mix (`docs/prds/next-turn/research/synthesis` §2, 300 steps). In-form holes top-1: program head 2.6%
   vs 33.3% structural, declared key 36.1% vs 74.7%, local 26.2% vs 39.0%; rank-sum combination was worse than
   structural alone (`docs/prds/next-turn/synthesis.md` §2 table, bounded run in `research/form-assembly.md`).
   Four probes on issue text, failed-eval retry and next-action kind all lost to BM25 or a Markov baseline
   (`docs/prds/next-turn/research/zero-shot-probes.md`). Its own sanity check reached 52% only when the candidate
   text was the exact docstring.
3. **Fine-tuning here means training two projection heads on precomputed, frozen-encoder embeddings, on a CUDA or
   CPU torch trainer.** `reference-code/CLM/train/finetune.py:1-30` (tasks `clm` and `choice`); the encoder is never
   updated (`docs/prds/next-turn/research/clm-recipe.md` §2, "The encoder is frozen"); there is no MPS path
   (same note, finetune.py:173). The training pairs are (state, action) from step transitions
   (`train/adapters.py:24-37`: `state`, `action`, `task_id`, `step_idx`, plus `reward` for selection) or typed rows
   (`typed_decision_examples`, `adapters.py:69`: `state`, `questions`, `gold`). Negatives are in-batch only; same-step
   pairs are masked (`finetune.py:174-183`). `src-clm` has no trainer and no Seon-to-transition adapter. Converting
   Seon evaluations into this format is unbuilt work.
4. **The published agent-verifier numbers do not reproduce from public data, and the fine-tune gain is small.** On
   the Terminal-Bench 2.1 CV that the note could reproduce (89 tasks, 3-fold, 374 successful trajectories), fine-tuned
   CLM reached 85.39% against 84.04% random and 79.78% zero-shot; the README's 87.6% and 81.6% depend on held-out sets
   that return HTTP 401 (`clm-recipe.md`, "Training" and access-limits sections; `README.md` "Agentic benchmarks").
   "Zero-shot is below random" in that CV.
5. **Cost and runtime: about 0.3 s per fresh state on this Mac, 16 GB resident, and the Seon labels are rule-made.**
   Encode 282 ms mean (448 tokens) and 16.4 GB peak Metal in `form-assembly.md` (line 726 and 731); 70 to 520 ms in the
   synthesis; 0.53 to 0.63 s per scored step in `predictability.md:305`. Each form's state changes, so each form
   needs a new encode; candidate vectors cache by text (`src-clm/src/seon_clm/engine.py`, `VectorCache`). The 117
   forms in `context-patterns.edn` are classified by first-match rules over source and error text
   (`context-patterns.md` §3), with a 20-form hand spot check, not by a person or a model. They are a rule's labels
   on 4 attempts of one task.

## 1. CLM (reference-code/CLM, pinned bb42c6c5)

- **What it is.** "A System One Model for Fast and Generalizable Decision-Making." Frozen Qwen3-8B backbone, last-token
  pooling, 512-d projection heads, score = exp(logit_scale) x cos(state_head(s), action_head(c)). Source of truth:
  `src/clm/heads.py` module docstring, and `README.md` "How it works" (architecture and training algorithm, bidirectional
  InfoNCE, mid-training hard negatives).
- **Data recipe.** Pre-train ~60M Nemotron DQA pairs; mid-train ~30M synthetic hard negatives (Gemini 2.5 Flash-Lite);
  post-train ~1M ADP and terminal trajectories, with 40% DQA replay (`README.md` "Data Recipe").
- **System One question types.** `src/clm/schema.py`: `QUESTION_TYPES = ("noul", "choice", "score")`.
  `candidates(q)` (schema.py, the function after `to_text`) builds option texts: choice uses each option's description
  or key; score uses the ordered level texts; noul uses `true` / `false` descriptions. `build_pairs` puts the question
  after the state ("context first, question last", `state_text`). `answer_from_probs` gives `noul` as p(true), `choice`
  as argmax and probabilities, `score` as expected level index.
- **`rank`.** Free-form candidates: `README.md` "Rank candidates directly" (`Engine.rank`, reference
  `src/clm/engine.py:140`). It is the same score with candidate texts supplied per call.
- **Token limit and truncation.** `README.md` Quickstart: "States longer than 2048 tokens are truncated." The
  reference embedder sends `truncate_prompt_tokens` (`src/clm/embedder.py:42-43`, default `max_tokens=2048` at line 30).
  Training uses `keep="tail"` for string states (`train/embed_utils.py:39-47`, `state_ids`) and `keep="head"` for
  actions (`finetune.py:140`). The port keeps the **last** `max_tokens` for every text (`src-clm/src/seon_clm/encoder.py`,
  `ids`, and its docstring). So the port drops the start of a long action text, unlike training, and keeps the question
  at the end of a long state, as trained. Cutting a long state keeps its tail, which is where the question sits.
- **Heads hot reload.** `src/clm/heads.py` `HeadPair` reloads on mtime (`src-clm/src/seon_clm/heads.py:5-7`); a
  fine-tune run can overwrite the head under a live server.

## 2. Fine-tuning and evaluation (reference-code/CLM)

- **`train/finetune.py`** (749 lines). `--task clm`: step transitions to embedding directories, one shared in-batch
  group-masked InfoNCE trainer, optional `--holdout-folds` and `fold_spec.json`. `--task choice`: typed System One rows
  (`LocalLLaMA/typed-decisions`), with `--loss infonce` or `softce` and `--targets soft|hard`. Module docstring
  lines 1-22.
- **What "lightweight" means.** Precomputed embeddings from the frozen encoder (`--emb-dir`, `--hf-dataset`, or
  `--data` embedded from scratch via vLLM or offline, `docs/` and `finetune.py:660-666`). Both heads and `logit_scale`
  are trained (`clm-recipe.md` §2). Defaults: 20 epochs, patience 5, batch 2048 for clm and 256 for choice, AdamW,
  OneCycle (`finetune.py:672-690`). Heads are roughly 20M params (README).
- **What counts as a label.** For `clm`, pass/fail appears only to select training rows and stratify folds; the loss
  never reads reward (`clm-recipe.md` lines 37-41). The success filter lives in the data, so mixed trajectories train
  on failures unless filtered first (same note, 51-54). For `choice`, `gold` is a distribution over options
  (`adapters.py:69-87`).
- **Eval.** `evaluation/bon_eval.py`: score each step by state/action cos, aggregate by mean over the final `--window`
  steps (default 12, line 150), select the best of N, report exact expectation over ties (lines 59-87). This is the
  only verifier path in the repo, and it needs an embedding dataset.
- **Data access.** `Contrastive-LM/deepswe-clm-embeddings-8k` and the Terminal-Bench repos return 401; the research
  repo returns 404 (`clm-recipe.md`, access-limits block). The release contains no DeepSWE or TB traces.
- **Docs.** `docs/FINETUNING.md` is an autoresearch loop: one focused change to `finetune.py` per run, keep or reset
  by held-out best-of-N. It forbids changing data, folds and eval.
- **Hub claims.** The model cards and `clm-publications.md` were read in the PRD; not re-read here.

## 3. src-clm (Seon's MLX port)

- **Status.** Committed on 2026-10-14 in `9c4e81605` ("Experimental: CLM encoder port to Apple MLX (src-clm/)"), 11
  files, 729 insertions. The 2026-10-14 `untracked-dirs.md` note says it is untracked; that is now out of date.
- **Files.** `src-clm/README.md` (81 lines). `src/seon_clm/`: `encoder.py` (81, mlx-lm `Qwen3Model`, last token, L2),
  `heads.py` (91, safetensors, hot reload), `engine.py` (120, `VectorCache` LRU keyed by `(namespace, text)`, `project`,
  `score`), `server.py` (126, `POST /v1/project` and `POST /v1/score`, plus the reference routes and playground),
  `convert.py` (38, `.pt` to `.safetensors`). `tests/test_parity.py` (82). `scripts/bench.py` (66).
- **`/v1/project`** returns L2 vectors in the head's space (base64 float32), so a caller can cache by its own key.
  **`/v1/score`** takes a state and candidates and returns `scale * cos` logits, top_k. The README says logits are not
  normalised across candidates and should use a threshold fitted on labelled turns.
- **`VectorCache` keying.** `engine.py` `VectorCache.get`: keys are `(namespace, text)`, and the namespace names the
  exact weights (`HeadPair.namespace`, `f"{self.name}@{self.generation}"`). Capacity 200,000 rows by default.
- **Bench.** `scripts/bench.py` prints state encode latency at 128 to 2048 tokens, card throughput, scoring over
  10k and 100k cards, and peak Metal. No recorded output is in the repo. Numbers in the PRD come from
  `form-assembly.md` (282 ms, 16.4 GB) and `predictability.md` (0.53 to 0.63 s per step).
- **Environment.** `src-clm/.venv` exists with `seon_clm-0.1.0.dist-info`, `mlx-0.32.2`, `mlx_lm-0.31.3`, and
  `bin/seon-clm-serve` (script wrapping the venv, Sep 26). Not run in this dig.
- **Weights.** `~/.cache/clm/CLM_v0.1-8B.pt` (75.6 MB) and `CLM_v0.1-8B.safetensors` (75.6 MB) both present.
  `~/.cache/huggingface/hub/models--Qwen--Qwen3-8B` has a snapshot of 15 GB of safetensors via blob symlinks (the
  blob directory itself is 4.3 MB because the files are symlinked into `snapshots/`).
- **Seon consumers.** No `src/`, `bin/`, `script/` or `resources/` file imports `seon_clm` or calls the server. The
  one grep hit (`resources/seon/tasks/seon.operator.edn`) is a number in prose. So the "Seon uses it to score context
  candidates" claim in the README is aspirational. Nothing calls it in production.

## 4. Git history

| commit | date (+0700) | what it did |
|---|---|---|
| `9c4e81605` | 2026-10-14 11:40 | Experimental: CLM encoder port to MLX (`src-clm/`), 11 files, +729 |
| `b40b5881a` | 2026-10-14 11:07 | Experimental: the next-turn PRD draft (`docs/prds/next-turn/`), 14 files, +3987 |
| `8dcabebd2` | 2026-10-14 11:04 | Research note `untracked-dirs.md` on those two dirs (says untracked; predates the commits above) |
| `62646ee70` | 2026-09-28 | Reply-value commit that also added the `reference-code/CLM` submodule entry (per `untracked-dirs.md`) |
| `bb42c6c` (in CLM submodule) | | README: how to raise the 2048 limit; `9d308a9` accepts Noul/Choice/Score objects in `Engine.answer` |

`git log --all -- src-clm reference-code/CLM docs/prds/next-turn` returns the rows above and no others for those
paths. The commit-message grep for CLM returns `9c4e81605`, `86942d3d0` (a `bin/lane` change that mentions
gate inputs, not CLM work) and `8dcabebd2`. The submodule is at `bb42c6c5bf914fd449bed2f6ca65be80602cb1f7`
(`git submodule status`). The `reference-code/CLM` submodule shows as modified-free in the start-of-session status.

## 5. docs/prds/next-turn (2026-09-26 to 27)

- **Mandate (owner, 2026-09-26).** "Getting the right data to the agent matters more than anything else." Hypothesis
  to test: per-turn curated dynamic context plus form autocomplete raises agent performance. Research must not be
  anchored on the first idea, which was to score every renderable entity with CLM per turn (`README.md`).
- **Lanes (13 notes).** ingredients, predictability, token-level, prior-art, render-cache, astra, form-assembly,
  test-selection, plus zero-shot-probes, clm-recipe, clm-publications, synthesis-review.
- **Decided (synthesis.md, "Status" and §2).** Hot-path zero-shot CLM is rejected for the tested setting. The
  recommended path is structural context (recency, transitions, task proofs, contracts) and `options`/precheck at the
  form boundary. CLM is a "challenger for first touches only, after fine-tuning" (§2 table).
- **Left open.** The pilot is the decider and "awaiting owner decisions" (synthesis.md §3, README "Status"). The
  fine-tune on Seon successful trajectories is described as "an open bet" (`zero-shot-probes.md` conclusion). The
  owner has not decided the pilot's go-ahead ("needs the owner's go-ahead", synthesis §3 Cost).
- **Why it stopped.** Its own evidence: untuned CLM lost at every hole and probe, and the cheap structural baselines
  won. The PRD's status is "research complete, awaiting owner decisions". No fine-tune was run.
- **Data and reproduction.** Its numbers sit in gitignored `tmp/next-turn-*` (the PRD's own note), so they cannot be
  rebuilt from the repo.

## 6. Seon's own data (for training or eval)

### 6a. `docs/research/agent-platform/2026-10-16/context-patterns.edn` (25 KB, 117 forms)

- Top-level keys: `{:corpus {:attempts 4 :forms 117 :model-calls 46 :source "chain branches :chain-10d1fd5956a9 and
  :chain-b7ab51e090d5"}, :attempts [...], :forms [...]}`.
- **`:attempts`** (4 rows): `{:attempt "attempt/<id>" :chain :end (:green|:pending) :calls :calls-to-green :forms
  :repeated :agent-errors :platform-errors :no-form :prompt-tokens :completion-tokens :cached-tokens :wall-ms}`.
- **`:forms`** (117 rows): `{:attempt "attempt/<id>" :turn n :ordinal n :class <kw> :reply-tokens n :source "<the form>"
  :elided-in-history bool}`. Classes (count): targeted-read 42, re-read-after-elision 16, probe-own-fix 15,
  in-ns-navigation 14, search-discovery 9, progress 8, contract-refusal 4, cleanup 2, task-read 3, invented-name 1,
  other 2, other-error 1.
- **Labels are rule output.** First-match rules over source text and error text (`context-patterns.md` §3). The
  md's spot check agreed on 20 of 20 random forms. Known miss: one errored `deftest` classed progress.
- **Scope gap.** The md says merge-step attempts are gone from the store. Only chain-step forms are readable, and
  all four are one task (`seon.ref` wildcard citations), with 1 green attempt pair per chain.
- **Reply-time cost.** `:reply-tokens` per form (exact counts from the evaluation) is in the rows, so a classifier
  could also be scored on the context it saw.

### 6b. Where per-turn context lives

- **What the model saw.** `src/seon/context.clj:911` `model-context` ("What one model pass sends, as shown text"):
  system message (`help`), attempt-level block views (cached), then the agent's own model exchanges, then turn
  sections. History comes from `exchanges` (`context.clj:882`, private), with `exchange-form` at `context.clj:864`
  eliding replies over `:seon.config.context/reference-tokens` (default 400). So the exact per-turn input is
  reconstructible from the database, but only the non-elided part.
- **Evaluations.** `:seon.cluster.eval/*` on the agent branch (`src/seon/eval.clj:29-57`, the query over
  `:seon.cluster.eval/run`, `/id`, `/ordinal`). Each form row carries source, comment, output, error and read evidence
  (`docs/prds/next-turn/README.md` ingredients). Per-turn shown text is `:seon.eval/shown`.
- **Model calls.** Agents' calls, prompts and basis per turn are rows on the agent (`:seon.cluster.eval/origin :model`
  joined to call rows; `context-patterns.md` header).
- **Ended branches.** Kept by default from now on: `docs/seon/schedule/cards/KEEP-BRANCHES.md` (owner ruling 2026-10-16,
  dial `:seon.config.agent/keep-ended-branches?` shipped true; `src/seon/config.clj:970` `keeps-ended-branches?`;
  `src/seon/cluster/agent.clj:2447` reads it). Measured cost of keeping: 2 to 25 MB per chain, about 140 KB per branch
  commit. Until those branches exist, mining is possible only from the branches still present.
- **Only what is kept can be mined.** Main's attempt rows point to the branch and its end commit
  (`:seon.task/branch`, `:seon.task.attempt/commit`); the records live only on the branch.

## Open questions

1. **Domain fit.** No measurement shows CLM's Nemotron/ADP-trained heads separate Seon forms better than a rule.
   Zero-shot probes lost; no fine-tune has been run on Seon data. Is a fine-tune the first experiment, or is the
   structural baseline (cheaper, and it already wins) the right floor to beat first?
2. **Label source.** The 117 labels are first-match rules, so a classifier trained on them learns the rules, not a
   judgement. What is the gold label for "judged against what was known at that step"? Candidates: the reply (did it
   refuse, was it a repeat of an elided read), the proof state after the form (did it move a proof to green), or a
   human pass on a sample. Each needs a definition before training.
3. **Adaptive classes at query time.** CLM's open vocabulary needs candidate texts per call, so a "class" is a text
   that is embedded each time. Is this cheaper than a fixed set over a few dozen labels? The Seon cost (282 ms encode
   per state) is paid per form regardless of label count.
4. **Truncation.** Long forms and long history exceed 2048 tokens (`:reply-tokens` up to 412 for one source read). The
   port keeps the tail; training kept head for actions. Which side to truncate for a form classifier, and does
   the elision in `exchange-form` already hide what a classifier needs?
5. **Training pipeline and hardware.** Training is CUDA or CPU torch; the Mac has MLX only. Is the training host a
   GPU box, and who owns the transitions adapter for Seon evaluations (a new `train/adapters.py`-style reader)?
6. **Eval set.** Held-out Seon data is 4 attempts on one task. Fold by task family and time needs more chains
   (KEEP-BRANCHES makes more available from now on).
7. **Is it our adaptive form classifier at all?** A cheaper alternative is a rule plus a learned ranker over
   structural features (the PRD's transition/logistic ranker, +4 points top-10 over recency, `synthesis.md` §2).
   Measured against the same labels, that is the bar CLM must clear.
8. **Doc drift.** `src-clm/README.md` says Seon uses the server; nothing does. `untracked-dirs.md` says untracked;
   the commit exists. Both need a correction when the owner decides.

## Commands run (read-only)

`ls` and `find` on `reference-code/CLM`, `src-clm`, `docs/prds/next-turn`, `docs/research/agent-platform/2026-10-16`;
`git log --all` for those paths and `--grep=CLM`; `git show --stat` for `9c4e81605`, `b40b5881a`, `8dcabebd2`;
`git submodule status`; `cat`/`sed`/`grep` over the CLM source, README and docs, `src-clm` source, PRD and research
notes; `grep` over `src`, `bin`, `script`, `resources` for CLM symbols; `du` and `find` over the HF cache and
`~/.cache/clm`. No model loaded, no server started, no test run, no JVM, no network call, no file other than this
note written.
