# Relevance lab (2026-10-27)

The experiments behind `docs/vector.md#relevance`: which design ranks what a task's work needs, and how its score becomes a chance. The findings are in the wiki; this folder is how to run them again.

## Data

- `truth.py`: the truth set from main's history, `python3 truth.py truth-all.edn` in a checkout. Each merge of `jolt/<task>`: its plan (the task entry at the merge's first parent, or at the merge), else its commits' subjects (`plan-at "commits"`). What the merge changed: top-level definitions in `src/`, wiki sections, schema entries. `truth-all.edn` holds the 279 rows of 2026-10-27.
- `export.clj`: run as root on a worktree root (`ev.sh export.clj root`); it writes every vector and each item's graph facts (refs, names, links, keywords) to `export/`. `load.py` turns them into `corpus.pkl`.
- `emb.py` with `embedfile.clj`: embeds texts through an attached agent's `:embed` (`{:op :attach :task ...}` on the root's port first). `reembed.py` makes `V-titled.pkl`, every item titled as `seon.vector/document` does now.

## Experiments, in order

- `exp1.py`: cosine at each Matryoshka width, BM25 and their fusions, all kinds and per kind (`run1b.py`: stored against titled vectors).
- `exp2.py`: the query-side adapter trained on graph edges. `exp4.py`: on query-shaped pairs.
- `exp3.py` with `run3b.py`, `run5.py`: the logistic over text and graph features, cold and warm, with an ablation and gradient-boosted trees.
- `run6.py`: local-push PageRank against the full iteration, and Platt recalibration on plans.
- `run7.py`: cheap z-scores (moments, coarse) and name-only BM25. `run8.py`: the score over a bounded pool with BM25 local to it.
- `run9.py`, `run11.py`: the implemented feature set, at two push thresholds. `run12.py`: links weighed by rarity (`plumb.py` counts plumbing lines). `run12b.py` and `export_weights.py` make the weights and calibration tables in `resources/seon/config/default.edn`.
- `run10.py`: the related part before relevance, on the same plans, and the reliability bins.

Python 3 with numpy, scipy, scikit-learn and edn_format, in a venv outside the repository.
