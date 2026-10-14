---
type: research
status: done 2026-09-26
tags: [research, agent, context]
---

# Untuned CLM-8B on Seon-shaped questions: no niche found

This was an adaptive search for where zero-shot CLM is useful, run with the owner's
instruction to try the most promising probes first, not fill a grid. Every probe
lost to a trivial baseline, so the search stopped after four probes and a sanity
check.

- **Setup:** MLX port (`src-clm/`), reference head `CLM_v0.1-8B`, one GPU process.
- **Scripts:** `tmp/next-turn-probes/` (`build.py`, `probe1.py` to `probe4.py`).

| probe | labels | CLM | baseline |
|---|---|---|---|
| issue title → cited function (3,378 candidates, 353 issues) | functions cited in the issue body | any-hit @1 0.8%, @10 4.5% | BM25 9.1%, 26.9% |
| same, prose-style cards; CLM reranking BM25's top 20 | same | @1 4.2% (reranking hurt BM25) | BM25 9.1% |
| failed eval vs its corrected retry: which one runs? (400 pairs) | the retry succeeded | 49.8%, no better when confident | "longer form wins" 57.8% |
| issue title → namespace (103 with docstrings) | namespaces of the cited functions | @1 2.5%, @10 21.5% | BM25 12.5%, 41.1% |
| next action kind from the last 4 steps (600 steps, held-out sessions) | the next step's kind | top-1 11.3%; adds nothing to Markov | Markov 40.8% |

**Sanity check (the pipeline is sound; the model is weak here):**
- State = a function's docstring; candidates = 800 cards containing that exact
  docstring. CLM retrieves the right card 52.2% of the time; with names only as
  candidates, 2.8%.
- The port's own parity against the reference is cosine ≥ 0.9999 (`src-clm`
  tests).

**Conclusion.** Untuned CLM has no use on Seon's code, tasks or trajectories in any
framing tried. This is consistent with its own evidence: the agentic results
required fine-tuning on successful steps (`clm-recipe.md`).

- **Cheap methods that work:** structure (recency, transitions, the task's proofs,
  contracts) and plain lexical BM25.
- **Where CLM stands:** it earns a place only if a fine-tune on Seon's own
  successful, execution-verified pairs beats these baselines on held-out task
  families. That remains an open bet.
