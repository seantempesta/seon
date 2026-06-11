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
    [seon.db-test]
    [seon.render-test]
    [seon.boot.preconditions-test]
    ;; T7 clip guardrail: store-cap + row-count guard (memory-safety) and
    ;; the display-surface guiding-message tests (agent-context).
    [seon.eval.memory-safety-test]
    [seon.agent-context-test]
    ;; Run-4 root-cause fix (2026-06-09): data-ns schema tee upsert +
    ;; record-eval! never silently loses the eval row.
    [seon.eval.record-eval-tee-test]
    ;; Run-5 / A4 (2026-06-09): transact! envelope contract — never
    ;; rejects, :double bridges, cryptic errors translated, register!
    ;; type gate.
    [seon.db.envelope-test]
    ;; Unit 1.5 (2026-06-09): messaging codified — message!/reply!,
    ;; from/to refs, hops, blank-content guard, derived conversation.
    [seon.agent.message-test]
    ;; seon.agent.search (2026-06-09): the exemplar npm-package wrapper —
    ;; ripgrep envelope contract, seon.agent.fs allowlist gating, truncation.
    [seon.agent.search-test]
    ;; my.kb scaffold (V3-B, 2026-06-10): provenance shapes registered
    ;; once + my.kb.instruction seed/section/edit-by-transact contract.
    ;; Required here so the deftest-vars roster carries my.kb-test and
    ;; its full source renders as the exemplar test sibling.
    [my.kb-test]
    ;; my.soul (2026-06-11): the store-resident system prompt —
    ;; seed-from-SOUL.md, seed-only-if-absent (edits survive reboot),
    ;; LLM call path reads the store. NOT a my.kb child (and so not an
    ;; exemplar test sibling) — see the my.soul ns doc.
    [my.soul-test]
    ;; Boot-time test indexing (unit #23 fix b): this preload's require
    ;; closure IS the pod's test roster, so the deftest-vars macro below
    ;; can see every deftest var — seon.client (compiled before the test
    ;; nses) cannot.
    [seon.client :as client])
  (:require-macros [seon.indexing :refer [deftest-vars]]))

;; Hand the pod's full deftest roster to the boot indexer
;; (seon.client/index-tests reads this; start-agent! transacts the rows).
(reset! client/!indexed-test-vars (deftest-vars))
