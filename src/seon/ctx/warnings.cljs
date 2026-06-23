(ns seon.ctx.warnings
  "The `:warnings` context section — current problems rendered as a
   `;;; ── WARNINGS ──` comment-block via the `seon.warn` check registry.
   Symbol-wired into the composer (`seon.ctx/core-default-ctx`) as
   `'seon.ctx.warnings/warnings-section`."
  (:require
    [seon.ctx :as ctx]
    [seon.warn :as warn]))

(defn warnings-section
  "Current problems as a `;;; ── WARNINGS ──` comment-block (one
   explanation + fix example per kind, then affected locations), or empty
   when clean — derived from live state, never stored, so a warning
   vanishes the moment its cause does. Corpus (spec-hygiene) checks scope
   to the agent's current ns by default; `:seon.warn/ns <ns-kw>` (or
   `:seon.warn/all`) on the `:seon.ctx` entity overrides. Runtime checks
   (failed-evals, bad-ref, slow-evals, failing-tests) are always global.
   Dev-only checks are not surfaced here. Add a kind via `seon.warn/checks`."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id] :as input}]
  (let [override (:seon.warn/ns (:seon.ctx/section input))
        scope    (cond
                   (= override :seon.warn/all) nil
                   (some? override)            override
                   :else
                   (let [ns (ctx/current-ns {:seon.agent/id id})]
                     (if (keyword? ns) ns (keyword (str ns)))))]
    (warn/render-warnings
      (cond-> {:seon.db/db db}
        (some? scope) (assoc :seon.warn/ns scope)))))
