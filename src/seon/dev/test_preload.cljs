(ns seon.dev.test-preload
  "Dev-only preload that pulls platform CLJS test namespaces into the
   running `:client` pod build.

   Without this, test namespaces under `test/` are on shadow's source
   path but unreachable from `:client` (per shadow-cljs.edn:25-28 — each
   build only compiles what's transitively required from its `:main`).
   Tests that need to be runnable via `(seon.test.runner/run! {::ns 'foo})`
   against the live pod must be required here.

   Wired via the `:devtools :preloads` slot on the `:client` build
   (shadow-cljs.edn). Preloads only load when `:devtools :enabled true`,
   so this has zero release-bundle cost.

   Phase 3 will replace this with `seon.test.suite` — the same idea but
   driven by the unified `:seon.fn/test? true` discovery (per
   docs/prds/agent-runtime/research/cljs-testing-infrastructure-2026-05-25.md
   §4.A)."
  (:require
    ;; Self-test for the runner itself + the synthetic probes it drives.
    [seon.test.runner-probes]
    [seon.test.runner-test]
    [seon.test.fixture-support-probes]
    [seon.test.fixture-support-test]
    [seon.test.async-fixture-probes]
    [seon.test.async-fixture-test]
    ;; Platform tests that exercise the runner end-to-end against
    ;; real production namespaces.
    [seon.agents-test]
    [seon.db-test]
    [seon.render-test]
    [seon.boot.preconditions-test]
    ;; T7 clip guardrail: store-cap + row-count guard (memory-safety) and
    ;; the display-surface guiding-message tests (agent-context).
    [seon.eval.memory-safety-test]
    [seon.agent-context-test]))
