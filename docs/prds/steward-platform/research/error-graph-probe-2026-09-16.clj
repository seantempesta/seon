; Evaluate this one form through mcp__seon__eval_clj, mode jvm, cluster default.
; Read-only constructor probe: no transaction or definition is installed.
(do
  (require 'seon.db 'seon.operator 'seon.config 'seon.error)
  (let [d (seon.db/db (seon.operator/connection "default"))
      config (seon.config/effective d "default")
      request {:seon.error/source {:seon.error/kind :seon.error/unclassified :seon.error/message "error-graph read-only probe"}
               :seon.error/id "error-graph-read-only"
               :seon.error/at #inst "2026-09-16T03:00:00Z"
               :seon.error/process "error-graph-process-a"
               :seon.sci.admit/caps (seon.config/result-caps config)
               :seon.config.error/max-evidence-bytes (:seon.config.error/max-evidence-bytes config)
               :seon.config.error/recurrence-limit (:seon.config.error/recurrence-limit config)}
      tx (seon.error/commit-tx d request)
      other (seon.error/normalize (assoc request :seon.error/process "error-graph-process-b"))
      call [:db.fn/call #'seon.error/commit-tx request]]
  {:first-row (select-keys (first tx) [:db/id :seon.error/signature :seon.error/process])
   :process-changes-signature (not= (:seon.error/signature (first tx)) (:seon.error/signature other))
   :current-value (seon.error/value (first tx))
   :transaction-function-value
   (select-keys (try (seon.error/value call) (catch Throwable failure (seon.error/refusal failure))) [:seon.error/kind :seon.error/message])
   :steward-holders (count (seon.db/q '[:find ?e :where [?e :seon.error/steward]] d))}))
