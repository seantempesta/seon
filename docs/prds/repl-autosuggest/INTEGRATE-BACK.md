---
type: orchestrator
status: active
tags: [index, agent]
---

# Integrate-back — stable worktree → main after the runtime-reliability refactor

The autosuggest lane is pinned to `b4efd4f5` in the worktree
`/Users/sean/src/seon-stable` (branch `repl-autosuggest/stable`) so the
concurrent runtime-reliability refactor cannot destabilize it. Improvements
made here must be cherry-picked back onto the post-refactor main.

## Cherry-pick list (commits after the `b4efd4f5` pin)

- **`dd8bc8a7` + `8d1631cf` — compact cards render fully-qualified keywords,
  never `::` (fix + dead-code removal).** `src/seon/agent/ctx/namespaces.cljs`,
  `src/seon/repl/autocomplete.cljs`, `src/my/ns.cljs`,
  `test/seon/agent/ctx/namespaces_test.cljs`. `abbrev-ns-kws` rewrote
  `:seon.db/tx-data` → `::tx-data` in compact cards; a card is read from the
  AGENT'S ns, so `::` resolves to the wrong ns and models fumbled it into
  malformed `::seon.db/tx-data`. Removed the function outright and its `ns-str`
  plumbing (the `::ns-str` schema and the param on `compact-schema-line` /
  `compact-fn-head`, plus the two external callers). **`namespaces.cljs` is in
  the refactor agent's active territory** — reconcile against their version
  before applying; cherry-pick both commits (or squash). Small and localized.

- **`2bf4eb14` — `plan!` refuses a same-title duplicate.** `src/my/plan.cljs`
  + `test/my/plan_test.cljs`. `plan!` had no idempotency guard, so a model
  re-emitting its plan (common — eval results arrive next turn) silently wrote
  a second full tree. Title-scoped guard: an OPEN same-title root ⇒ fail
  envelope naming the existing root id + routing to reconcile!/document/step!/
  done! (a distinct second plan is still allowed — legitimate forest).

- **`39fd81ec` — cut the markdown reconcile path; crisp plan!/reconcile!
  docstrings.** `src/my/plan.cljs`, `src/my/plan/internal.cljs`,
  `test/my/plan_test.cljs`. Removed `reconcile!`'s `:my.plan/markdown` parse
  end to end (schema, arg, cond branches, the whole internal parser) — `:tree`
  is the one update format. Docstrings: reconcile! = the UPDATE door (diff by
  id), plan! = CREATE-once + "results arrive NEXT turn, render in the :plan
  block, don't re-call to check the response."

- **`61813a97` — `long_term_planning` as a first-class inspect `@task`.**
  `src-inspect-ai/…/tasks/long_term_planning.py` + `offline_proof.py`. Wraps
  `pod_planning_driver` in a custom `@solver` so planning runs via `inspect
  eval` like its siblings; mock:good/bad offline proof. (src-inspect-ai, not
  shared refactor territory — clean apply.)

- **`c8b907ea` — swebench arm egress follows the active provider, not a
  deepseek hardcode.** `src-inspect-ai/…/swebench_arm.py` + test. Plus
  **`pyproject.toml`** gained `openai` (standard-mode model access). (Both
  src-inspect-ai — clean apply.)

## Already in the shared codebase (pre-pin, no action)

- `3e0e0bff` — schema-reference closure (`ctx/referenced-schema-block`),
  non-nilable schema gate, `my.plan` directive error messages,
  `seon.ai/generate-plan`. Committed on the shared branch before the worktree
  pin, so the refactor agent already has it.
