(ns seon.ctx.warnings
  "The `:warnings` context section — current problems rendered as ONE
   clustered `<warnings>` block via the `seon.warn` check registry.
   Symbol-wired into the composer layout (`seon.ctx/core-default-ctx`) as
   `'seon.ctx.warnings/warnings-section`; loaded at boot so the symbol
   resolves for `seon.eval/lookup-value`. Reads the current-ns
   derivation from the spine `seon.ctx`."
  (:require
    [seon.ctx :as ctx]
    [seon.warn :as warn]))

(defn warnings-section
  "Render current problems as ONE clustered `<warnings>` block via the
   `seon.warn` check registry: one complete explanation + one targeted
   fix example per kind, then the affected list with specific locations.
   Empty string when everything is clean; warnings vanish the moment the
   underlying state goes away (derived, never stored — see
   docs/seon/concepts/reactive-context).

   The CORPUS checks (no-malli-schema, return-is-any, arg-is-any,
   uses-maybe, no-return-spec, no-input-spec) default to
   the agent's CURRENT ns so an agent isn't confused by other
   namespaces' defects. Override per-section via the `:seon.ctx` entity:
   `:seon.warn/ns <ns-kw>` scopes to that ns; `:seon.warn/ns
   :seon.warn/all` is the whole-core overview. The RUNTIME checks
   (failed-evals, bad-ref, slow-evals, failing-tests) are always global
   — cross-agent visibility is their point.

   To add a warning kind, add a check fn to `seon.warn/checks`."
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
