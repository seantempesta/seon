---
title: "Untracked directories: src-clm and docs/prds/next-turn"
type: research
status: read-only research; wrote no source, test or database state; this note is the only file changed
created: 2026-10-14
---

# Untracked directories: `src-clm/` and `docs/prds/next-turn/`

Read-only research, 2026-10-14. Nothing was edited except this note. Shell history and home files were not read.

## Summary

- **`src-clm/`** is a small Python project (MLX port of the CLM contrastive encoder, Qwen3-8B last-token encoder plus projection heads, for Apple silicon). About 600 lines of source, 2 test/bench files, a 976 MB local `.venv`. Last source change 2026-09-26 23:45. It looks like the owner's (or an agent working for the owner) finished-enough experimental tool, not an agent's accidental leftover: it has a coherent README with setup and routes, not scratch files.
- **`docs/prds/next-turn/`** is a PRD folder: a README (mandate, owner hypothesis), `synthesis.md`, and 13 research notes in `research/`, all written 2026-09-27 00:04 to 02:00. The owner framed the question; agent research lanes produced the notes (their text says "research lanes" and "owner hypothesis"). It is work in progress, marked `status: draft for owner review` and `status: research`. Its scratch data sits in gitignored `tmp/next-turn-*`.
- **Seon code does not depend on either.** No `src/`, `test/`, `bin/`, `script/` or `resources/` file references `src-clm`, `seon_clm`, port 8700 or `next-turn` as a dependency. The only `next-turn` hits in code are unrelated test names (`...-next-turn`, meaning "the following turn").
- **The only link to Seon is the CLM submodule.** `reference-code/CLM` is a git submodule (`.gitmodules`, pinned at `bb42c6c5`). `src-clm/README.md` installs it editable. The `.gitmodules` entry and submodule pointer were added by commit `62646ee70` (2026-09-28), a commit about reply values that also swept unrelated files (issue system deletions and others). That sweep is the likely cause of confusion, not a dependency.
- **Both are already known to Seon's own docs as an open owner question.** `docs/seon/plan.md:65` ("Owner question open: what are the untracked `src-clm/` ... and `docs/prds/next-turn/` (both present before this session)? Left untouched."). `docs/seon/schedule/README.md:496` says the owner checks what to restore. Many lane reports list them as "foreign" and leave them alone.
- **Recommendation:** owner decides. Two clean options: commit `docs/prds/next-turn/` as a PRD (it is docs, no code risk) and either commit `src-clm/` (without `.venv` and `.pytest_cache`, both already gitignored in its own `.gitignore`) or move it out of the repo. Do not delete either without owner confirmation.

## `src-clm/`

| Item | Detail |
|---|---|
| Purpose | Inference half of CLM (`reference-code/CLM`) on MLX: `seon_clm.encoder.Encoder` (mlx-lm `Qwen3Model`, last token, L2), `seon_clm.heads.HeadPair` (safetensors, hot reload), `seon_clm.engine.VectorCache`, HTTP server (`/v1/project`, `/v1/score` added for Seon). Used for relevance scoring of context candidates per agent turn. |
| Language | Python 3.12 (`requires-python >=3.11`), built with hatchling. |
| Packages | `mlx`, `mlx-lm`, `safetensors`, `numpy`, `huggingface-hub`, `transformers`, `fastapi`, `uvicorn`, `requests`. Tests optionally need `torch` and `pytest`. |
| Source | `src/seon_clm/`: `__init__.py` 6 lines, `convert.py` 38, `encoder.py` 81, `engine.py` 120, `heads.py` 91, `server.py` 126. |
| Tests / scripts | `tests/test_parity.py` 82 lines; `scripts/bench.py` 66 lines. |
| Other | `README.md` 81 lines, `pyproject.toml` 35, `.gitignore` 3 (`.venv/`, `.pytest_cache/`, `*.egg-info/`). |
| Size | 24 KB of source and docs; 976 MB `.venv`. |
| Last modified | Source and README: 2026-09-26 23:37 to 23:45. `.venv` and `.pytest_cache` also 2026-09-26. |
| Depth-2 structure | `.gitignore`, `.pytest_cache/`, `.venv/`, `README.md`, `pyproject.toml`, `scripts/bench.py`, `src/seon_clm/`, `tests/` (with `__pycache__` and `test_parity.py`). |
| Git | Not tracked (`git ls-files src-clm` is empty). No commit ever added it. Its name appears only in commit messages and docs that describe it as untracked. |
| Model artefacts | Not in the directory. The README expects `~/.cache/clm/CLM_v0.1-8B.pt` (converted to safetensors) and a Qwen3-8B download (~16 GB) via `hf`. |
| Status | Looks complete for its inference scope (parity tests, server, bench). Not verified here: the `.venv` is not run, and no model runs were made. |

Ownership signal: a coherent README with install and serve steps, a pinned reference submodule, and a benchmark script point to a deliberate build, probably by the owner or an agent for the owner. Nothing looks like abandoned scratch.

## `docs/prds/next-turn/`

| Item | Detail |
|---|---|
| Purpose | PRD on giving an agent exactly the data its next completion needs. The question: predict the agent's next call, file, or form and render only that, possibly via a CLM-style relevance score. The owner's hypothesis is stated as to be tested, not assumed. |
| Language | Markdown only, 14 files, 320 KB, about 4,400 lines. Front matter `type: prd`, `status: research` (README) and `status: draft for owner review, revised after Astra review` (synthesis). |
| Structure | `README.md` (105 lines), `synthesis.md` (257 lines), `research/` with 13 notes: `astra`, `clm-publications`, `clm-recipe`, `form-assembly`, `ingredients`, `predictability`, `prior-art`, `render-cache`, `synthesis-review`, `test-selection`, `token-level`, `zero-shot-probes`. |
| Last modified | All files 2026-09-27 00:04 to 02:00. Newest is `research/zero-shot-probes.md` (02:00, `status: done 2026-09-26`). |
| Git | Untracked; `git log --all -- docs/prds/next-turn` is empty. Sibling `docs/prds/` folders are tracked. |
| Data | Computed numbers live in gitignored `tmp/next-turn-form-assembly/`, `tmp/next-turn-predictability/`, `tmp/next-turn-probes/`, `tmp/next-turn-test-selection/`, `tmp/next-turn-token-level/`. The notes name them, so the research is not reproducible from the repo alone (the scripts are in `tmp/`). |
| Ownership signal | README: "The mandate (owner, 2026-09-26)", "the owner asked that research not be anchored on it". The research notes say "research lanes", so agents wrote them under owner direction. A review (`synthesis-review.md`) and a synthesis were produced. Work in progress, not finished. |
| Key finding quoted by the notes | `synthesis.md`: an agent's next step is mostly predictable from structure and its own recent work (82% of next files already touched in-session; plain most-recent-first gets top-10 69.6%, a 1 ms mix 74%). `zero-shot-probes.md`: untuned CLM-8B found no niche where it beats a trivial baseline. The notes depend on `src-clm`. |

## Dependencies and references

- `src-clm` depends on `reference-code/CLM` (submodule, installed editable from `../reference-code/CLM`) and on Hugging Face model downloads. Nothing in `src/`, `test/`, `bin/`, `script/` or `resources/` imports or starts it.
- `docs/prds/next-turn` cites `src-clm/` file paths and line ranges, and `tmp/next-turn-*` script outputs. It cites no Seon code as a dependency for its conclusions (its notes name Seon symbols as research subjects, e.g. `seon.render.walk`, `seon.cluster.prompt`).
- Seon docs that mention them, all as "untracked / foreign / leave alone": `docs/seon/plan.md:65`, `docs/seon/schedule/README.md:496`, `docs/research/agent-platform/2026-10-12/restart-audit/state-vs-docs.md:76`, and several lane reports (e.g. `2026-10-08/clojure-way/research-agenda-clojure-way.md:312`, `2026-10-12/task-triage-1.md`, `config-read-scope-review-2026-09-27.md:330`).
- No `rg` hit for `src-clm|next-turn|seon-clm` in `.agents/` or `CLAUDE.md`.

## Commands run (read-only)

`ls`, `find`, `stat`, `wc`, `du`, `cat` on both directories; `git log --all -S'src-clm'`, `git log --all -S'seon-clm'`, `git log --all -- docs/prds`, `git ls-files`, `git submodule status`, `git show --stat 62646ee70`, `git diff --cached --stat`; `rg` over `docs .agents CLAUDE.md bin script src test resources`; `bin/lane status`. The `.venv` was not run. No model or network calls.

## Open points for the owner

1. Commit or drop `docs/prds/next-turn/`. It is safe to commit as docs; its `tmp/` inputs stay gitignored, so the notes' numbers are not reproducible from the repo.
2. Commit or move `src-clm/` (excluding `.venv/` and `.pytest_cache/`, both already ignored by its own `.gitignore`). Its README setup expects a `reference-code/CLM` submodule, which is already pinned in `.gitmodules`.
3. Decide whether `62646ee70`'s `.gitmodules` change for `reference-code/CLM` stays, since it was swept into an unrelated commit.
