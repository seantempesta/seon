(ns seon.primer.state
  "Primer state management - the ctx atom."
  (:require [seon.primer.schema :as schema]
            [malli.core :as m]))

(defonce ctx (atom {}))

(defn valid-ctx? [c]
  (m/validate schema/Ctx c {:registry @schema/registry}))

(defn update-ctx! [f & args]
  (let [new-ctx (apply swap! ctx f args)]
    (when-not (valid-ctx? new-ctx)
      (throw (ex-info "Invalid ctx after update"
                      {:errors (m/explain schema/Ctx new-ctx)})))
    new-ctx))

;; Add watch that triggers SSE refresh on ctx change
(defonce _ctx-watch
  (add-watch ctx :sse-auto-refresh
             (fn [_ _ old-val new-val]
               (when (not= old-val new-val)
                 (require 'seon.web.sse)
                 ((resolve 'seon.web.sse/refresh-all!))))))
