---
type: research
status: evidence
created: 2026-10-16
tags: [research, clm, context, agent-platform]
---

# CLM prototype: can it classify Seon REPL forms? (2026-10-16, lane clm-proto)

Scripts in this directory: `common.py` (parse, state builder), `exp1_choice.py`, `exp1b.py`, `exp2.py`, `exp3.py`, `exp3b.py`, `exp4_cost.py`, `exp5_probe.py`; logs/results `exp1.log`, `exp1_results.json`, `exp1b_logits.json`. Run with `src-clm/.venv/bin/python`.

## Verdict first

- **Open-class zero-shot Choice over form classes: not useful.** 23 to 33% accuracy on 117 hand-labelled forms; the majority class alone scores 36%.
- **Progress verifier (Noul) and "is this the next form" ranking: not useful.** AUC 0.34 to 0.51 (chance 0.50); true next form ranks at chance.
- **Embeddings are informative, but only about as much as a char-trigram baseline.** Action vectors cluster by form shape (1-NN 73 to 78%, vs 71 to 74% for char-3gram). Useful for **open-ended discovery/grouping**, marginal as a classifier.
- **A trained linear probe on CLM vectors works (74 to 76%, leave-one-attempt-out)**, so a classifier from our own labels is feasible; the zero-shot route is what fails.
- **Cost is fine**: ~57 ms per 100-token state, ~125 ms per 300 tokens, ~1.06 s at the 2048 limit; 12 to 30 ms per short action; 17 GB RSS.

## Setup and limits of this run

- Setup verified: `src-clm/.venv`, Qwen3-8B in the HF cache, head `~/.cache/clm/CLM_v0.1-8B.safetensors`; used the in-process `seon_clm.Engine` (no server started; nothing left running). Cold load 2.8 to 7.8 s. Apple silicon, 128 GB.
- **The live Seon JVM was not available** (`bin/seon status`: endpoint unavailable; I did not start it, as instructed), so the branches `:chain-b7ab51e090d5` and `:chain-10d1fd5956a9` could not be read. `context-patterns.edn` holds each form's source, class, reply token count and whether its reply was later elided, but **not the outputs or errors**. The state is therefore: a hand-written task summary (task symbol, the wildcard rule, owner, proof name) + the last k forms of the same attempt, each with "reply N tokens" and an elision note + the form itself. This is weaker than the real state (no replies), so every accuracy below is a lower bound for what CLM could do with outputs. The full 5 to 7k-token `model-context` was not assembled, so truncation to the last 2048 tokens was not measured; only k = 0, 4, 8 history sizes were (k=8 is about 290 tokens).
- Corpus: 4 attempts of one task (117 forms, 68 unique sources), class `other-error` merged into `other`. Many forms recur across attempts; numbers are patterns on a small set, not rates.
- Labels are the lane context-patterns rules, not independent human labels.

## Experiment 1: zero-shot Choice

Question "What kind of step is the agent's latest form?", 11 classes, one-sentence criteria each (`exp1_choice.py`, `exp1b.py`).

| history k | accuracy (`Engine.answer`, softmax) | ms / form |
|---|---|---|
| 0 | 8.5% | 78 |
| 4 | 23.1% | 113 |
| 8 | 26.5% | 116 |

- Majority baseline 35.9% (targeted-read). With k=0 the head picks `progress` for almost everything (it matches the generic "defines or changes code" text; 40 of 42 targeted reads).
- Variant with **unsupervised contextual calibration** (subtract each class's mean logit over the corpus, no labels): 29.9% (k=4), 33.3% (k=8). Variant with **example-style criteria** (criteria written as example forms): 31.6% / 29.9% calibrated; raw 12.8% (it prefers the same two options).
- Per-class (long criteria, k=8, calibrated): search-discovery 7/9, cleanup 2/2, probe-own-fix 10/15, in-ns 4/14, re-read-after-elision 4/16, targeted-read 8/42, progress 3/8. With example criteria in-ns 11/14 and search 7/9, but targeted-read 7/42.
- Top confusions: targeted-read vs probe-own-fix / other / search; in-ns vs cleanup; re-read vs targeted-read (the difference is purely "have I seen this exact form before", which CLM does not compare; it embeds, it does not match strings).
- Examples (k=8, `Engine.answer`): `(in-ns 'seon.ref)` -> progress (conf 0.62); `(in-ns 'seon.ref)` later -> targeted-read (0.75); `(doc seon.ref/span-candidate)` -> probe-own-fix (0.29); `(my.program/source 'seon.ref-test)` -> probe-own-fix (0.29); `(seon.ref/span-candidate #{} ":seon.render.unknown/foo")` -> probe-own-fix, correct (0.44).
- Reading: Choice is built for semantic options ("billing" vs "technical"). Our classes are distinctions in syntax and in history relation (first vs repeated read), which a text-to-text relevance head does not resolve. An `in-ns` form is trivially recognisable by a regex and not by this head.

## Experiment 2: progress verifier and next-form ranking

State = task + last 8 forms + the form; Noul instruction as asked. "Productive" = targeted-read, probe-own-fix, progress (66 forms); "wasteful" = the rest (51: re-read, in-ns, search, refusals, ...). AUC computed over all pairs (`exp2.py`).

| Noul instruction | AUC productive vs wasteful | mean P: productive / wasteful | ms |
|---|---|---|---|
| "moves the agent closer to making its task's proof green, given what it already knew" | **0.342** | 0.866 / 0.889 | 178 |
| "gets information or makes a change it did not already have/make earlier" | 0.421 | 0.853 / 0.870 | 163 |
| (negated) "repeats work already done or cannot succeed" | 0.511 | 0.176 / 0.187 | 156 |

- P(true) sits at 0.85 to 0.89 for everything: the probability is not calibrated for this question and does not separate. The re-read-as-low-novelty AUC is 0.45 to 0.50.
- Ranking (the head's native task): score the true next form against every other form of the same attempt (state excludes the form; ~30 candidates). Mean rank 15.3 (random 15.5), top-1 5.1%, top-3 10.3%. Mean percentile productive 0.51 vs wasteful 0.47. The head favours `search-discovery` (0.17) and `task-read` (0.12) forms for any state and puts `in-ns` last (0.84); that is a form-shape prior, not a judgement about this step.
- Outcomes were also checked per attempt only in class terms: there are 4 attempts (2 green), too few for an outcome AUC.

## Experiment 3: discovery without fixed classes

Embedded the 117 forms (`/v1/project` side `action`, here `Engine.project`) and the 117 states (side `state`, k=4); cosine k-means (`exp3.py`, `exp3b.py`).

- Leave-one-out 1-NN class agreement: action 77.8% (72.6% when identical sources are excluded), state 78.6%; char-3gram baseline 74.4% / 70.9%. So CLM vectors carry the class structure but not much beyond surface text.
- k=8 clusters on action vectors (purity 70%) found clean, meaningful groups without a class list: `defn`/`deftest` writes (7/7 progress), `in-ns`/`require` navigation (14 of 15), `(my.program/search ...)` (5/5), test-probes of the fix (15 of 26, mixed with `def`-wrapped reads), and three clusters of `(my.program/source ...)` split by which name is read (references/candidate/line-citation/document...), i.e. clustering by target, not by class. k=12 purity 73.5%.
- Clusters on state vectors (purity 68%) separate by the history stage: pre-fix reading, writing, probing. Cluster 4 grouped task reads with re-reads at the start of attempts. No new class the hand set lacks appeared; the one suggestion is that "read of X" splits naturally by X (which a query on the form's symbol gives for free).
- Verdict: discovery/grouping is the one use where open classes work, at 12 ms per new form and with a cache hit rate that is high for repeated forms (68 unique of 117). Marginal over trigram clustering at this scale.

## Experiment 4: cost (MLX, 8B bf16)

| work | time |
|---|---|
| model + head cold load | 2.8 s (7.8 s first process) |
| state encode, 100 tokens | 57 ms |
| 300 tokens | 125 ms |
| 1000 tokens | 416 ms |
| 2048 tokens (the limit) | 1055 ms |
| short action, batch of 8 / 32 | 31 / 29 ms each; single form 12 ms (batched in a project call) |
| 117 compact states (k=4, ~236 tokens) | 14.8 s cold = 127 ms each; cached 0.000 s |
| Choice over 11 criteria (class vectors cached) | state encode only, 78 to 116 ms, per form |
| peak RSS | 17.1 GB |

Roughly 0.4 ms per token up to 1000 tokens, then super-linear (0.5 ms/token at 2048). A model-context of 5 to 7k tokens cannot be encoded as is: truncated to the last 2048 it costs about 1 s per state, and one state per turn gives a replay of 10,000 turns at about 35 minutes (150 ms state + 5 forms x 12 ms each) with the compact state, about 3 hours at 1 s per state. Class vectors and repeated forms are free from the cache; states are unique per turn, so they are the cost. CLM is not a per-call gate on the Seon hot path (one second ordinary-operation rule) beyond the compact state; it is fine as a background or replay scorer.

## Experiment 5: fine-tuning feasibility (read; plus one cheap probe)

- `reference-code/CLM/train/finetune.py` trains **only the projection heads on a frozen Qwen3-8B**; the heads are an MLP over the 4096-d encoder embedding (`clm.heads.make_head`: hidden -> width -> 512). Two tasks: `clm` takes step transitions (`.json`/`.jsonl`, each with `state`, `action`, `task_id`, `step_idx`, optionally `trajectory_id`, `reward`; positives are (state, taken action), in-batch InfoNCE with group masking), and `choice` takes typed decisions (state text, option keys + candidate texts, gold or soft distribution; negatives are the other options and the batch's other option texts). Embeddings are produced once and cached (`--embed-cache`), via **vLLM** (offline or `--embed-url` to any `/v1/embeddings` server) by default.
- Feasibility on MLX: **the trainable part is small**. Encoder embeddings can come from our MLX `Encoder` (`eng.embedder.embed`, raw 4096-d, 117 texts took seconds), after which training is an MLP on cached vectors: torch on CPU/MPS is plenty (the venv already has torch). The gap: finetune.py hard-depends on vLLM only for embedding and on CUDA flags (`--gpu`); an `--embed-url` pointed at a small shim around `Encoder.embed` (the server exposes `/v1/project`, not `/v1/embeddings`; it would need that route), or a precomputed embedding dir for `--emb-dir`, avoids vLLM. A device override to `cpu`/`mps` was not tried. A rewrite of the head loop in MLX is not needed.
- The probe I ran: a linear classifier on CLM vectors of the form, leave-one-attempt-out over 4 attempts (`exp5_probe.py`): action vectors 76.2%, state vectors 73.5%, both 74.6%, raw 4096-d 72.0%, majority 36.5%. This shows the signal is learnable, but identical forms recur across attempts and the label rules are source-text rules, so part of it is memorised surface form.
- **Weak labels derivable from Seon outcomes** (all available in the store, per form, no hand work): the attempt ended green or not (`:seon.agent/end-green`); the proof's state after the eval (red to green transition); the form's result symbol never referenced by later forms (unused result); the same source or same target read again later (`repeated`, re-read); the eval carried an error (refusal, unresolved name); the form's reply was elided and the target re-read next turn; tokens of reply. With these, a state to "was this form useful" training set grows by about 25 forms per attempt, so thousands of pairs from a day of chains; `clm` tasks use (state, taken action) positives from green attempts and negatives from the same state's red alternatives, the natural CLM form. The caveat: only the 4 readable attempts exist today.

## Honest summary per use

| use | verdict |
|---|---|
| classify forms into fixed hand classes, zero-shot | not useful (23 to 33%; regex or the one-line rules in context-patterns do better) |
| open classes at query time as NL criteria | not useful zero-shot for syntactic or history-relative classes; works only for semantic options |
| progress verifier, probability | not useful (AUC 0.34 to 0.51) |
| judge a form against what was known | not testable properly without replies; the history-relation ("seen this exactly") is a string match that CLM does not do |
| cluster / group forms and states without a class list | marginal-to-useful (clean groups for free at 12 ms/form; barely above char-3gram) |
| trained probe or fine-tuned head on our own weak labels | promising, untested beyond one linear probe |

## What I would try next

1. Rerun experiments 1 and 2 with the real state: task plan plus the last k exchanges **with outputs and errors** from the branches (needs the JVM). The verifier needs the replies to judge progress.
2. Train the probe with outcome labels (green attempt, error, repeated, unused result) instead of rule classes, validated leave-one-task-out once more tasks exist; try the `clm` (state, action) task with `finetune.py` on cached MLX embeddings on cpu/mps.
3. Use CLM where it is native: rank candidates for context (cards, must-reads) against a compact state, which is what `src-clm/README.md` intends, rather than classify forms.
4. Replace the exact-repeat distinctions (re-read, repeated form) by data lookups (the form's symbol and the history), and keep CLM for the semantic remainder.
