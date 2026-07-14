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

## RISKS, INCOMPLETE, and PROBLEMATIC parts (read before integrating)

### Integration risks
- **Naming migration `tile`→`surface`.** Main renamed the `plan!` docstring
  example (`src/my/plan.cljs:33-34` on the pin: "summary fn + tile" / "tile
  renders totals" → "surface") and is migrating the plan-block-HTML "tile"
  naming (`internal.cljs` ~1337–1586). My cherry-picks do NOT touch those
  regions (verified) — but after applying, grep the result for reintroduced
  `tile`/`world`/`live-tile` in `my.plan` and keep main's `surface` wording.
- **Order matters:** cherry-pick `2bf4eb14` (guard) THEN `39fd81ec` (markdown
  cut) — 39fd81ec rewrites the same `plan!` docstring the guard commit's
  context sits near.
- **Verify, don't assume, the plan hunks.** Main's `plan.cljs`/`internal.cljs`
  edits are in `needs!`/`move!` + `unresolved-step-refs` (internal ~81) +
  render (~1451–1715); my hunks are `plan!` (~328), `reconcile!` (~521), the
  `::markdown` schema (~191), the parser (759–874). Disjoint on inspection,
  but a git 3-way merge can still throw context conflicts — resolve by keeping
  BOTH main's preflights and my guard/markdown-cut, then run the tests below.
- **Post-integration test gate:** `bin/test-cljs --test=my.plan-test` AND a
  full `bin/test-cljs` (the plan changes touch shared render/eval paths).

### Found-but-NOT-fixed (hand off to the refactor — these are its domain)
- **Cross-tree writer-REPL port collision (infra bug).** `bin/seon:114`
  hardcodes `SEON_WRITER_REPL_PORT` to a fixed `7891`; pods get a free port
  (`SEON_PORT=0`) but the wire-server does not. So an ephemeral bench cluster
  in one worktree collides with a co-located default wire-server (reproduced:
  the stable worktree's bench cluster vs main's default on 7891 → `Address
  already in use`, cluster-create dies). I worked around it with a one-off
  `SEON_WRITER_REPL_PORT=<free>` env; NOT fixed at root. Proper fix: allocate a
  free writer-REPL port per ephemeral cluster (mirror the pod's `SEON_PORT=0`).
  This is runtime-reliability/cluster-lifecycle territory.
- **Write-timeout is silent.** A DB write whose auto-await exceeds
  `seon.eval/!timeout-ms` (10s) records a `:seon.eval/pending` placeholder — an
  agent cannot distinguish slow-but-committed from failed. A write timeout
  should surface a clear failure. Not triggered by the plan bug (writes were
  ~400ms) but a real latent issue.

### Incomplete / follow-ups (autosuggest lane, not blocking)
- **plan-block teaching gap (owner-gated).** `empty-plan-teaching`
  (`internal.cljs`) still omits "results arrive NEXT turn" + the `reconcile!`
  pointer. I put that in `plan!`'s docstring (renders in its card), NOT the
  teaching block — deferred per the no-context-hacks rule. If wanted in the
  teaching too, it's a one-line follow-up requiring owner sign-off.
- **`plan!` guard is title-scoped.** Two DELIBERATELY same-titled plans → the
  second is refused (a distinct forest must use distinct titles). Accepted edge.
- **`offline_proof` full suite is blocked at E1** (pre-existing, diffusion):
  the `worker-oracle-eval` bundle is unbuilt, so the full suite aborts before
  reaching my planning entries. Validated them in isolation instead
  (good=1.000 / bad=0.000 via `inspect_eval` directly). Rebuild that bundle or
  make E1 resilient to run the whole suite.
- **Live behavioral proof pending.** The `plan!` guard + tightened docstrings
  are unit-tested but NOT yet proven on a live 35B drive (bench-bundle rebuild
  + one planning sample) — the "does the model stop duplicating and manage one
  clean plan" proof is still to run.

## Already in the shared codebase (pre-pin, no action)

- `3e0e0bff` — schema-reference closure, non-nilable schema gate, `my.plan`
  directive error messages, `seon.ai/generate-plan`. Committed before the pin;
  main already has it.
