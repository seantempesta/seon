# seon-diffusion — the verified code-buffer

Oracle-guided DiffusionGemma generation, local-first (MLX on Apple
Silicon). The diffusion model denoises Clojure on a 256-token code-buffer;
seon's co-located oracles steer it between rounds: forms **lock and
execute** the moment they parse+lint+eval (harvested off the code-buffer into
the encoder KV cache), provable near-misses are **auto-repaired** for $0
model tokens, remaining bad spans are scrambled under a `; fix:` hint
comment, and the caller's `[{call,expect}]` checks terminate the loop on
PROOF (validation-as-early-stop).

**Perf convention (owner): report in TOKENS/SECOND, always.**

Owner directive: maintained code lives HERE, not in PRD dirs or tmp/.
PoC ancestors (gitignored `tmp/flash-diffgemma/`, external
`~/ml/diffusion-gemma/`) are retired as maintained surfaces.

## Layout

```
src/seon_diffusion/
  model.py       model layer = mlx_vlm's DiffusionGemma (adapter exposing
                 load_model/new_cache/encode/decode/cfg; incremental
                 harvest via diffusion_update_cache)
  generate.py    free block-diffusion loop (the BASELINE arm — untouched)
  control.py     generate_guided: round → check → repair → lock/harvest →
                 scramble+hint → prove (T3 checks, attempt-restart)
  repair.py      model-agnostic repair + hints (NO mlx) — shared with the
                 AR path; candidate shim scheduled to move oracle-side
  oracle.py      Oracle (bb bin/oracle-server) + EvalSession (node
                 worker-oracle-eval --serve, STATEFUL) pipe clients,
                 liveness-gated
  common.py      pure span/offset helpers (living copies)
  server.py      the local diffusion-server — RunPod wire contract on
                 :17860, single MLX executor thread, idle-unload
  config.py      sole config surface (repo-root walk; SEON_* overrides)
  ab_guided.py   the guided-vs-free lift battery (scorecards + samples)
  cuda/          FROZEN RunPod A100 artifacts — revive by need
tests/           offline proofs: scripted stub model + REAL bb/node oracles
```

## Run matrix

| Target | Command | Needs |
|---|---|---|
| Tests | `.venv/bin/pytest` | bb, node, built bundles |
| Lift battery | `.venv/bin/python -m seon_diffusion.ab_guided --n 3 [--repair on\|off]` | model in HF cache |
| Server | `bin/seon start diffusion-server` (→ `SEON_DG_ENDPOINT=http://127.0.0.1:17860`) | ditto |
| Setup | `uv venv && uv pip install -e ".[test]"` | uv |

Oracles: `bin/oracle-server` (bb, parse/lint/phase) and
`out/worker-oracle-eval/main.js` (`clj -M:cljs compile worker-oracle-eval`;
needs `out/bootstrap`). `verify` before trusting numbers: every response
carries `worker_sha` (all package .py files); the battery stamps it on
every row.

Model: `mlx-community/diffusiongemma-26B-A4B-it-8bit` (HF cache; override
`SEON_DG_SNAPSHOT`), loaded through `mlx_vlm` (>= 0.6.4). Our original
from-scratch MLX port (parity-proven decoder, but an encoder that
corrupted beyond ~8-10k context — Round 9 of the typeahead research)
was retired 2026-07-10; it lives in git history and in
`~/ml/diffusion-gemma/`.

## Provenance

- Phase-0 lift proof (PoC repo, 2026-07-05): guided vs free, N=18/arm —
  parse 0.94→1.00, eval 0.78→1.00, behavioral 0.72→0.94.
- Design + phase plan: `docs/prds/diffusion-dynamic-context/` +
  the approved plan (verified code-buffer v2).
