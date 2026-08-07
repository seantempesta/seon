---
type: research
status: complete
tags: [research, runtime, sci]
---

# W4 probe reproduction

Copy these files into `tmp/env-probes/` (gitignored, project-local) and run
each from the repository root. No cluster, no database, no production
namespace is required; `clojure -M:dev` supplies the classpath.

`w4_base_pinning_hazard.clj` and `w4_prewarm_viability.clj` need the
`:call-preparation-hook` option, which exists only on the maintained sci
fork's branch `seon-env-hook-probe` (`reference-code/sci`, commit
`a072c8e`, deliberately unpinned in the superproject). `deps.edn` resolves
sci as `:local/root "reference-code/sci"`, so the submodule working tree
decides which sci is on the classpath — check `git -C reference-code/sci
branch --show-current` before running those two.

```bash
mkdir -p tmp/env-probes
cp docs/prds/sci-execution-runtime/research/env-phase1-w4-probes/w4_*.clj \
   tmp/env-probes/

for probe in corpus_shape install_scaling prewarm_viability \
             base_pinning_hazard fork_install_cost; do
  ns=$(printf '%s' "$probe" | tr '_' '-')
  clojure -M:dev -e "(require 'clojure.pprint) \
    (load-file \"tmp/env-probes/w4_${probe}.clj\") \
    (clojure.pprint/pprint (w4-${ns}/run))"
done
```

`w4_fork_install_cost.clj` is the long one (a few minutes: it installs
several thousand interpreted defns and forces four GC rounds per memory
measurement). The others are seconds.

| file | question | headline result |
|---|---|---|
| `w4_corpus_shape.clj` | how big is a realistic defn? | 1428 first-party defns: median 336 chars / 40 reader nodes, p90 1232 / 128 |
| `w4_install_scaling.clj` | install cost vs defn size | ~20 µs fixed + ~0.21 µs per reader node; ~24 µs at the median |
| `w4_fork_install_cost.clj` | lazy vs eager totals, memory | eager N=1000 ≈ 60–120 ms/fork and ≈ 12–23 MB/fork; lazy k=5 ≈ 0.4–0.8 ms |
| `w4_base_pinning_hazard.clj` | how bad is base pinning? | silently wrong for turn-scoped members; no cross-cluster leak; `sci/fork` shares every non-`:env` ctx key |
| `w4_prewarm_viability.clj` | can eager leave the critical path? | `assoc` after install fails; a per-fork holder works, isolated 16/16 |
