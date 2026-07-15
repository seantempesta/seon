---
type: research
status: active
tags: [research, agent, component]
---

# Local model serving inventory — 2026-07-15

This is a read-only P2 inventory of the serving state observed on 2026-07-15.
No model was downloaded, no server or cluster was started or stopped, and no
evaluation was run. The result is a baseline matrix to execute after the P0
development slice freezes, not a model selection.

## Dependency ledger

- Inspect AI source is `reference-code/inspect-ai/` at
  `05322696a0f784ec399ef6abbafd3d2a250ea9cc`. The installed
  `src-inspect-ai/.venv` distribution is
  `0.3.247.dev0+g05322696a.d20260715`; the source checkout is dirty only at the
  nested `_view/ts-mono` entry.
- Inspect Evals source is `reference-code/inspect-evals/` at
  `97c99f5f6507fc5d1449fe3247f267d591f64350`, described as `v0.14.3`.
  The installed distribution still identifies itself as
  `0.0.1.dev1+unknown.gce900d638` and imports from the source checkout.
- `src-inspect-ai/pyproject.toml` selects Inspect through a mutable local path
  and declares unbounded `openai`; the environment has `openai 2.45.0`.
  [[../../../seon/issues/inspect-source-dependency-is-not-content-pinned]]
  already owns the reproducibility defect.
- Inspect's maintained local OpenAI-compatible owner is
  `model/_providers/openai_compatible.py`. A model named
  `openai-api/local/<model>` reads `LOCAL_BASE_URL` and `LOCAL_API_KEY`.
  Inspect's separate `ollama/<model>` provider defaults to
  `http://localhost:11434/v1` and needs no real credential.
- MLX serving is `mlx_lm.server`. The listener on port 8081 uses `mlx 0.32.0`,
  `mlx-lm 0.31.3`, `transformers 5.13.1`, and `huggingface-hub 1.23.0` from
  `/Users/sean/src/seon-stable/src-needle/.venv`. The listener on port 18081
  uses `mlx 0.31.2`, `mlx-lm 0.31.3`, `transformers 5.12.1`, and
  `huggingface-hub 1.21.0` from
  `/Users/sean/ml/diffusion-gemma/.venv`.
- Ollama is application and CLI version `0.32.0`. Its API and on-disk manifest
  are the identity sources inspected here.
- Existing Seon evidence owners are the native `.eval` files under
  `evals/runs/`, [[turn-evidence-retention-2026-07-15]],
  [[bfcl-native-completion-2026-07-15]], and the earlier local-model study
  [[../../repl-autosuggest/research/kt3b-coder-models-2026-07-12]].

## Live serving state

Three OpenAI-compatible listeners were ready without intervention.

| endpoint | observed process identity | readiness | identity caveat |
|---|---|---|---|
| `http://127.0.0.1:8081/v1` | `mlx_lm.server --model mlx-community/Qwen3.6-35B-A3B-4bit-DWQ --port 8081` | `GET /v1/models` returned 14 cached model ids | the response lists the shared cache, not just the loaded 35B model |
| `http://127.0.0.1:18081/v1` | `mlx_lm.server --model mlx-community/Qwen2.5-Coder-0.5B-Instruct-4bit --port 18081` | `GET /v1/models` returned the same 14 cached ids | the process command is the evidence that 0.5B is loaded |
| `http://127.0.0.1:11434/v1` | `ollama serve`, version `0.32.0` | `/api/version` and `/api/tags` returned successfully | only one Ollama artifact is installed, and it is not a small-model candidate |

The Ollama artifact is
`qwen3.5:35b-a3b-coding-nvfp4`, 21,909,194,238 bytes, manifest digest
`6e73b30f8f1cfa06b979c842ba222ae21dad1e55e7c6748a7d8acad46fb340c4`.
Its advertised capabilities are completion, vision, thinking, and tools. No
LM Studio, llama.cpp server, vLLM, or SGLang process or command was found in the
bounded process, listener, and command-path inspection.

The following commands reproduce the non-generating readiness check:

```bash
ps -axo pid=,etime=,command= | rg -i 'ollama|mlx_lm|llama-server|vllm|sglang'
lsof -nP -iTCP -sTCP:LISTEN | rg '(:8081|:18081|:11434)'
curl -fsS http://127.0.0.1:8081/v1/models
curl -fsS http://127.0.0.1:18081/v1/models
curl -fsS http://127.0.0.1:11434/api/version
curl -fsS http://127.0.0.1:11434/api/tags

```

For MLX, acceptance must record both the listener response and the exact
`mlx_lm.server --model ...` process command. Treating `/v1/models` alone as
loaded-model identity would silently mislabel a run.

## Exact local artifacts

The Hugging Face snapshots below have their named `refs/main` revision, model
weights, config and tokenizer files present, and zero broken symlinks.

| bracket | artifact | revision | disk | relevant contract |
|---|---|---|---:|---|
| sub-1B | `mlx-community/Qwen2.5-Coder-0.5B-Instruct-4bit` | `6b16732e5af5cd9bd600186ad59fa618867ef7a4` | 276 MB | Qwen2 causal LM, 24 layers, 32,768 context, 4-bit group 64, chat template present |
| sub-1B | `mlx-community/Qwen3.5-0.8B-OptiQ-4bit` | `9affd71fc70de2bb08a666ac2d08a3fff5c858e0` | 845 MB | Qwen3.5 hybrid, 24 text layers, 262,144 context, mixed 4/8-bit group 64, no chat template in cached tokenizer config |
| 1.5B | `mlx-community/Qwen2.5-Coder-1.5B-Instruct-4bit` | `b3252a2f97102b1fb1571fec2c9b27219a8536be` | 839 MB | Qwen2 causal LM, 28 layers, 32,768 context, 4-bit group 64, chat template present |
| 2B | `src-needle/checkpoints/qwen3.5-2b-4bit` | local conversion; config SHA-256 `8893a91e9b7763d7b94c6db181f5b7c79c6f4881a69cefb11549eefcab1823bf` | 1.0 GB | Qwen3.5 hybrid, 24 text layers, 262,144 context, 4-bit affine group 64 |
| 3B | `mlx-community/Qwen2.5-Coder-3B-Instruct-4bit` | `3dd939c621c08e5753d5b89f35a2642cd83b98ca` | 1.6 GB | Qwen2 causal LM, 36 layers, 32,768 context, 4-bit group 64, chat template present |
| 3B control | `mlx-community/starcoder2-3b-4bit` | `d90b61f0a26e018c1505ea6ed0fdfeca4e649789` | 1.8 GB | StarCoder2, 30 layers, 16,384 context, 4-bit group 64, no chat template |
| strong local sanity | `mlx-community/Qwen3.6-35B-A3B-4bit-DWQ` | `73c707af4243243b18193444467872d20cff9399` | 19 GB | Qwen3.5 MoE family, mixed 4/8-bit group 64; currently served on 8081 |

Additional complete cached artifacts include base variants for the Qwen2.5
Coder 0.5B, 1.5B, and 3B models; full upstream Qwen 3.5 0.8B Base and 2B Base
snapshots; and a full upstream `Qwen/Qwen3.5-2B` snapshot at revision
`15852e8c16360a2fea060d615a32b45270f8a8fc`. The local 2B conversion does not
embed its upstream repository or revision, so matching it to that snapshot is
an inference and not reproducible artifact identity. The same directory also
contains local Qwen 3.5 0.8B Base and 2B Base conversions; these are useful
completion controls, not the first agentic-chat baselines.

No exact 4B-or-smaller 4B artifact is installed. The P2 matrix can cover the
roadmap's `4B-or-smaller` ceiling with the two 3B candidates without a download;
an exact 4B row remains an explicit artifact gap.

## Existing result evidence

The native artifacts establish useful constraints but do not choose a model.

- The unchanged ten-sample BFCL run at
  `evals/runs/2026-07-14-bfcl-qwen35-2b-unchanged/` completed all samples with
  Qwen 3.5 2B and scored 0.0. It predates turn-bundle retention, so it is not
  adequate for context or parser attribution.
- The retained `multiple_0` smoke at
  `evals/runs/2026-07-15-inspect-turn-evidence-qwen-smoke/` used
  `Qwen/Qwen3.5-2B`, recorded four turns with reply-token counts
  `[36, 0, 0, 0]`, closed `:no-forms`, and scored 0.0.
- After correcting the general lifecycle contradiction, the identical sample
  at `evals/runs/2026-07-15-bfcl-native-complete-qwen-smoke/` recorded one
  turn, one eval, 41 reply tokens, `:completed`, 6,154 ms, and score 1.0. Its
  final database coordinate is retained. This is adapter acceptance evidence,
  not a comparative model result.
- A prior standard-Inspect HumanEval smoke used the 8081 MLX 35B server and
  Docker sandbox and scored 1.0 on one sample. It proves the provider path, not
  small-model Seon performance.
- The earlier 214-row local next-form study found strong framing sensitivity:
  Qwen2.5-Coder-1.5B-Instruct scored useful F1 `.152` zero-shot and `.265`
  with three examples, while raw base continuations ranged `.083`–`.121`.
  That study used a different, later-rejected display and scorer surface; it
  motivates including instruct and base/control rows but cannot be imported as
  the P2 baseline.

The current `.eval` records identify the pod model as `Qwen/Qwen3.5-2B`, but do
not preserve the serving endpoint, MLX package tuple, process command, or model
revision. Therefore even the retained successful smoke does not identify the
exact bytes that generated it.

## Recommended baseline matrix

Run this matrix only after P0 freezes membership. These rows are complementary
diagnostic probes; no row is the recommended winner.

| bracket | primary row | comparison question | readiness |
|---|---|---|---|
| sub-1B | Qwen2.5-Coder-0.5B-Instruct-4bit | Can the smallest installed chat-tuned coder follow Seon's executable-form and lifecycle contract? | artifact complete and currently served on 18081 |
| sub-1B stretch | Qwen3.5-0.8B-OptiQ-4bit | Does the newer long-context hybrid improve namespace navigation despite lacking a cached chat template? | artifact complete; server compatibility must be generation-smoked after lifecycle coordination |
| 1.5B | Qwen2.5-Coder-1.5B-Instruct-4bit | Does the prior few-shot-sensitive model gain from ordinary dynamic context without benchmark-specific examples? | artifact complete; previously exercised locally |
| 2B | Qwen3.5-2B 4-bit | Does the model with current retained Seon evidence generalize beyond one corrected BFCL sample? | local quant complete, but artifact provenance and serving identity must be fixed before scoring |
| 3B | Qwen2.5-Coder-3B-Instruct-4bit | Does added scale help tool composition and verification over 1.5B? | artifact complete |
| 3B family control | StarCoder2-3B-4bit | Are results Qwen-specific, and how costly is a completion model with shorter context? | artifact complete; requires an explicit, unchanged completion framing arm |
| strong local sanity | Qwen3.6-35B-A3B-4bit-DWQ | Can the unchanged task/context succeed at all through the same local provider path? | ready on 8081; outside small-model graduation |
| strong remote sanity | Meta Muse or DeepSeek | Is a failure systemic when a stronger model sees identical bytes and budgets? | use configured provider only; not a small-model candidate |

Keep each small-model scored arm serial against static ACME until the P1 lease
exists. Hold Seon source, config, frozen sample membership, execution mode,
temperature, thinking policy, maximum output tokens, turn/deadline ceilings,
and scorer constant. A practical first deterministic setting is temperature
zero with thinking disabled where the provider implements that contract; any
model that cannot honor the same setting gets a separately labeled arm rather
than silent coercion.

For an already-coordinated MLX listener, the standard Inspect provider form is:

```bash
cd src-inspect-ai
export LOCAL_BASE_URL="http://127.0.0.1:<port>/v1"
export LOCAL_API_KEY="sk-local-nokey"
.venv/bin/inspect eval <frozen-task> \
  --model "openai-api/local/<exact-model-id>" \
  --epochs 1 --temperature 0 --display plain --log-dir <unique-log-dir>

```

For the installed Ollama artifact, provider wiring is
`--model ollama/qwen3.5:35b-a3b-coding-nvfp4`; it is an optional strong sanity
row, not a small-model substitute. Starting or replacing either server must be
separately coordinated because this inventory found them already owned by
long-lived processes.

## Acceptance evidence for each future row

Before sample construction, persist:

- Seon commit, clean/dirty state, ACME artifact/config identity, database id,
  immutable starting coordinate, and frozen slice identity;
- Inspect and Inspect Evals commits plus installed versions, Python lock digest,
  OpenAI client version, task version, scorer identity, and native `.eval` URI;
- exact provider model string, base URL, server process command, serving package
  versions, Hugging Face revision or local artifact digest, quantization, and
  generation configuration;
- listener readiness and a one-sample generation smoke that confirms the named
  listener actually loads the named artifact; and
- final database coordinate, ordered prompt/reply turn bundle, raw score,
  classified failure, elapsed time, and cleanup/restart evidence required by
  P1.

The comparative matrix is accepted only when every row runs the same frozen
development membership and its native artifacts retain these identities. A
`/v1/models` listing, model display name, or successful single BFCL sample is
insufficient by itself.

## Remaining gaps

- P0 development membership is not yet frozen, so no comparative baseline was
  authorized by this inventory.
- Inspect, Inspect Evals, and `openai` are still not reproducibly content-pinned
  as one Python environment. The `pyproject.toml` Inspect version comment is
  stale relative to the installed/current source build.
- MLX `/v1/models` does not prove the loaded model. Future run metadata needs
  the process command or a stronger server-owned loaded-model identity.
- The local Qwen 3.5 2B quant lacks an embedded upstream revision, and existing
  `.eval` evidence lacks endpoint and artifact revision.
- No exact 4B artifact is installed. No download is needed for the initial
  ceiling matrix because two complete 3B controls are available.
- The 0.8B OptiQ tokenizer config has no chat template; its correct unchanged
  agentic framing and MLX server behavior need a bounded smoke before it enters
  a scored run.
- No baseline result yet compares the installed matrix on the frozen ordinary
  Seon context, and no winner should be selected until that evidence exists.
