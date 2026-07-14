---
type: orchestrator
status: active
tags: [index, agent]
---

# Integrate-back — stable worktree → main (for the MAIN/refactor agent)

The autosuggest lane is pinned to `b4efd4f5` in the worktree
`/Users/sean/src/seon-stable` (branch `repl-autosuggest/stable`). Main
(`/Users/sean/src/seon`, branch `codex/runtime-reliability-refactor`,
HEAD `bcd2c2c9` at audit time) shares `b4efd4f5` as an ancestor, so these are
straight cherry-picks. **This list is verified against main's actual tree
(2026-07-14) — apply exactly as marked; do NOT apply the DROP items.**

## APPLY — clean (main untouched these paths)

All of `src-inspect-ai/` is **untouched by the refactor** — these apply with
zero conflict:

- **`c8b907ea` — swebench arm egress follows the active provider.**
  `src-inspect-ai/.../swebench_arm.py` + `tests/test_swebench_arm.py`.
- **`91dee957` — add `openai` dep** for standard-mode model access.
  `src-inspect-ai/pyproject.toml`. (run `uv pip install openai` in the venv)
- **`61813a97` — `long_term_planning` first-class inspect `@task`.**
  `src-inspect-ai/.../tasks/long_term_planning.py` + `offline_proof.py`.
- **`d5a456ed` + this doc + the docker-integration research** — new files under
  `docs/prds/repl-autosuggest/`. Clean adds.

## APPLY — verify (main edited the SAME files, but DIFFERENT regions)

Main changed `src/my/plan.cljs` (+23/-9) and `src/my/plan/internal.cljs`
(+20/-5) ONLY in `needs!` / `move!` (added `internal/unresolved-step-refs`
existence preflights) and the plan-block render / a docstring example
(`tile`→`surface` naming). My commits touch `plan!`, `reconcile!`, the
`::markdown` schema, and the markdown parser (internal 759–874) — **disjoint
regions**, so the cherry-picks should apply with at most trivial context
conflicts. After applying, run `bin/test-cljs --test=my.plan-test` (expect
BOTH main's `*-preflights-*` tests AND my guard/`requires-a-tree` tests green).

- **`2bf4eb14` — `plan!` refuses a same-title duplicate.** `src/my/plan.cljs`
  + `test/my/plan_test.cljs`. Depends on `internal/open-forest` +
  `internal/agent-eid` (both still present in main).
- **`39fd81ec` — cut the markdown reconcile path; crisp plan!/reconcile!
  docstrings.** `src/my/plan.cljs`, `src/my/plan/internal.cljs` (deletes the
  dead `parse-markdown`/`md-node`/`fold-entries`/regex block),
  `test/my/plan_test.cljs` (drops 3 markdown deftests; dual-arg test →
  `requires-a-tree`). Cherry-pick 2bf4eb14 THEN 39fd81ec in order.

## DROP — do NOT apply (main already fixed this, differently)

- **`dd8bc8a7` + `8d1631cf` (+ their doc commits `00dd53f6`, `5dbcc951`) —
  the compact-card `::` fix.** **REDUNDANT.** Main independently rewrote the
  compact renderer (`src/seon/agent/ctx/namespaces.cljs`, +207/-109): it
  already renders fully-qualified keywords (`compact-schema-line` uses
  `(pr-str key)`, new `"schema X = …"` format), and `abbrev-ns-kws` is already
  gone. Applying my commits would conflict with main's rewrite and duplicate
  the intent. Main also rewrote `test/seon/agent/ctx/namespaces_test.cljs`
  (+153) and touched `src/seon/repl/autocomplete.cljs` + `src/my/ns.cljs` for
  the same rewrite — so those parts of my commits are moot too.

## NOT part of integration (my local lane state — leave in the worktree)

Uncommitted in the worktree and NOT for main: `acme/src/acme/pod.cljs`,
`config/acme.edn`, `acme/CLAUDE.md`, `out-acme/client/main.js` (build output),
and `src-needle/` scratch. These are the acme harness / experiment state.

## Already in the shared codebase (pre-pin, no action)

- `3e0e0bff` — schema-reference closure, non-nilable schema gate, `my.plan`
  directive error messages, `seon.ai/generate-plan`. Committed before the pin;
  main already has it.
