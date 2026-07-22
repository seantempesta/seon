(ns seon.host.sample
  "Serve retained value sampling for one JVM host session."
  (:require [clojure.edn :as edn]
            [seon.db.protocol :as db.protocol]
            [seon.host.context :as context]
            [seon.host.session :as session]
            [seon.render.value :as render.value]
            [seon.schema :as schema]))

(set! *warn-on-reflection* true)

(schema/register! ::host :map)
(schema/register! ::value :seon.render.value/value)
(schema/register! ::request :seon.render.value/value)

(declare retained-live-entry retained-live-value)

(defn drill-value
  "Project a host value against the exact retained committed generation."
  {:malli/schema [:=> [:catn [::host ::host]
                       [::value :seon.render.value/value]
                       [::request :seon.render.value/value]]
                  :seon.render.value/drill-result]}
  [host value request]
  (let [admitted
        (context/current-committed-projection (:seon.host/projection-state host))]
    (if-let [fault (:seon/error admitted)]
      {:seon.render.value/ok? false
       :seon/error
       {:seon.error/message "Schema-aware value browsing is unavailable."
        :seon.error/kind :core-bug}}
      (render.value/drill-value (::context/projection admitted)
                                value request))))
(defn- unavailable-drill-result [projection request miss]
  (let [root-request (assoc request
                            :seon.render.value/path []
                            :seon.render.value/offset 0)
        rendered (render.value/drill-value projection miss root-request)]
    (if (:seon.render.value/ok? rendered)
      (-> rendered
          (assoc :seon.render.value/availability :unavailable
                 :seon.render.value/recompute? true)
          (assoc-in [:seon.render.value/projection :seon.render.value/path]
                    (:seon.render.value/path request))
          (assoc-in [:seon.render.value/projection :seon.render.value/offset]
                    (:seon.render.value/offset request)))
      rendered)))

(defn serve-value-sample!
  "Serve one bounded value-sample request."
  {:malli/schema [:=> [:cat :map ::session/session :map] :nil]}
  [host session sample]
  (let [request (select-keys sample
                             [:seon.render.value/path
                              :seon.render.value/offset
                              :seon.render.value/effective-limits])]
    (cond
      (some? @(::session/active session))
      (session/send-frame! session
                   (session/sample-error-frame sample
                                       "The execution host already has active work."))

      (not= (:seon.execution/agent-id @(::session/startup session))
            (:seon.execution/agent-id sample))
      (session/send-frame! session
                   (session/sample-error-frame sample
                                       "The value sample names another agent."))

      (not (render.value/admitted-drill-request? request))
      (session/send-frame! session
                   (session/sample-error-frame sample
                                       "The value sample request is invalid or over budget."))

      :else
      (let [admitted (context/current-committed-projection
                      (:seon.host/projection-state host))
            projection (::context/projection admitted)
            retained (retained-live-entry
                      session (:seon.execution/eval-id sample))
            found? (::session/found? retained)
            trusted-limits (::session/limits retained)
            metadata-invalid? (and found?
                                   (or
                                    (not (db.protocol/database-value?
                                          (::session/database retained)))
                                    (not (render.value/effective-limits-within?
                                          trusted-limits trusted-limits))))
            policy-refused? (and found?
                                 (not metadata-invalid?)
                                 (not (render.value/effective-limits-within?
                                       (:seon.render.value/effective-limits request)
                                       trusted-limits)))
            result (cond
                     metadata-invalid?
                     nil
                     (:seon/error admitted)
                     {:seon.render.value/ok? false
                      :seon/error {:seon.error/message
                                   "Schema-aware value browsing is unavailable."
                                   :seon.error/kind :core-bug}}
                     policy-refused?
                     (render.value/sampling-policy-refusal)
                     found?
                     (render.value/drill-value
                      projection
                      (retained-live-value session
                                           (:seon.execution/eval-id sample))
                     request)
                     :else
                     (unavailable-drill-result projection request
                                               (::session/value retained)))
            limits (:seon.render.value/effective-limits request)]
        (if metadata-invalid?
          (session/send-frame! session
                       (session/sample-error-frame
                        sample render.value/sampling-policy-unavailable-message
                        :seon.runtime/unavailable))
          (if (render.value/bounded-drill-result? result limits)
          (session/send-frame! session
                       {:seon.execution/message session/value-sample-result-message
                        :seon.execution/protocol-version session/protocol-version
                        :seon.execution/agent-id
                        (:seon.execution/agent-id sample)
                        :seon.execution/request-id
                        (:seon.execution/request-id sample)
                        :seon.render.value/result result})
          (session/send-frame! session
                       (session/sample-error-frame
                        sample "The value sample result exceeded its bounds."))))))))

(defn valid-value-sample?
  "Whether a value-sample message has the admitted wire shape."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [message]
  ;; JVM projection of the portable closed request: exact outer keys and
  ;; scalar correlation here; the one total drill predicate owns every path
  ;; and realization-work rule on both runtimes. This avoids registering a
  ;; second JVM-only schema graph for the same frame.
  (let [request (select-keys message
                             [:seon.render.value/path
                              :seon.render.value/offset
                              :seon.render.value/effective-limits])]
    (and (= 8 (count message))
         (every? #(contains? message %)
                 [:seon.execution/message
                  :seon.execution/protocol-version
                  :seon.execution/agent-id
                  :seon.execution/request-id
                  :seon.execution/eval-id
                  :seon.render.value/path
                  :seon.render.value/offset
                  :seon.render.value/effective-limits])
         (render.value/admitted-drill-request? request)
         (= session/value-sample-message (:seon.execution/message message))
         (= session/protocol-version (:seon.execution/protocol-version message))
         (every? #(and (string? %) (seq %))
                 ((juxt :seon.execution/agent-id
                        :seon.execution/request-id
                        :seon.execution/eval-id)
                  message)))))

(defn safe-sample-correlation
  "Return safe correlation fields for a value-sample response."
  {:malli/schema [:=> [:cat ::session/session :map] :map]}
  [session message]
  (let [startup-agent (:seon.execution/agent-id @(::session/startup session))
        agent-id (:seon.execution/agent-id message)
        request-id (:seon.execution/request-id message)]
    {:seon.execution/agent-id
     (if (and (string? agent-id) (seq agent-id)) agent-id startup-agent)
     :seon.execution/request-id
     (if (and (string? request-id) (seq request-id)) request-id "invalid")}))
(defn- admitted-retained-value
  "Apply the one portable bounded live-result admission policy."
  [value]
  (render.value/admit-retained-value value))

(defn retain-live-value!
  "Retain one managed eval value in oldest-first bounded session state."
  {:malli/schema [:=> [:cat ::session/session :string :any :map :seon.db/db] :nil]}
  [session eval-id value limits database]
  (swap! (::session/live-values session)
         (fn [{::keys [order values]}]
           (let [order (conj (vec (remove #{eval-id} order)) eval-id)
                 values (assoc values eval-id
                               {::session/value (admitted-retained-value value)
                                ::session/limits limits
                                ::session/database database})
                 over (max 0 (- (count order)
                                render.value/retained-value-cap))
                 evicted (subvec order 0 over)
                 kept (subvec order over)]
             {::session/order kept ::session/values (apply dissoc values evicted)})))
  nil)

(defn- retained-live-entry [session eval-id]
  (let [values (::session/values @(::session/live-values session))]
    (if (contains? values eval-id)
      (merge {::session/found? true}
             (select-keys (get values eval-id) [::session/limits ::session/database]))
      {::session/found? false
       ::session/value
       {:seon.eval/ok? false
        :seon.error/message
        (str "eval " eval-id " isn't live — its bounded result slot was "
             "evicted or belonged to a prior process. Re-run the form to recompute it.")}})))

(defn- retained-live-value [session eval-id]
  (get-in @(::session/live-values session) [::session/values eval-id ::session/value]))

(def ^:private sampling-policy-query
  '[:find [?path-segments ?path-bytes ?realized ?depth ?string ?shape ?items
           ?database-edn-cap ?repair-level ?repair-classes ?repair-max-fixes
           ?repair-budget-ms]
    :in $ ?id
    :where
    [?config :seon.config/id ?id]
    [?config :seon.config.render/value-max-path-segments ?path-segments]
    [?config :seon.config.render/value-max-path-bytes ?path-bytes]
    [?config :seon.config.render/value-max-realized-items ?realized]
    [?config :seon.config.render/value-max-depth ?depth]
    [?config :seon.config.render/value-max-string ?string]
    [?config :seon.config.render/value-shape-sample ?shape]
    [?config :seon.config.render/value-max-items ?items]
    [(get-else $ ?config :seon.config.render/database-edn-cap 16384)
     ?database-edn-cap]
    [(get-else $ ?config :seon.config.repair/level :symbols) ?repair-level]
    [(get-else $ ?config :seon.config.repair/classes "{}") ?repair-classes]
    [(get-else $ ?config :seon.config.repair/max-fixes-per-form 1)
     ?repair-max-fixes]
    [(get-else $ ?config :seon.config.repair/budget-ms 50) ?repair-budget-ms]])

(defn acquire-sampling-policy!
  "Acquire sampling and repair policy at an invocation database value."
  {:malli/schema [:=> [:cat :any :seon.db/db] :map]}
  [writer database]
  (let [row (context/query-writer-at! writer database
                                      sampling-policy-query ["cluster"])
        policy (when (and (vector? row) (= 12 (count row)))
                 (zipmap
                  [:seon.config.render/value-max-path-segments
                   :seon.config.render/value-max-path-bytes
                   :seon.config.render/value-max-realized-items
                   :seon.config.render/value-max-depth
                   :seon.config.render/value-max-string
                   :seon.config.render/value-shape-sample
                   :seon.render.value/page-size
                   :seon.config.render/database-edn-cap
                   :seon.config.repair/level
                   :seon.config.repair/classes
                   :seon.config.repair/max-fixes-per-form
                   :seon.config.repair/budget-ms]
                  row))
        policy
        (if (string? (:seon.config.repair/classes policy))
          (try
            (update policy :seon.config.repair/classes edn/read-string)
            (catch Throwable _ policy))
          policy)
        sampling-limits
        (apply dissoc policy
               [:seon.config.render/database-edn-cap
                :seon.config.repair/level
                :seon.config.repair/classes
                :seon.config.repair/max-fixes-per-form
                :seon.config.repair/budget-ms])]
    (if (and (pos-int? (:seon.config.render/database-edn-cap policy))
             (contains? #{:off :safe-syntax :symbols :aggressive}
                        (:seon.config.repair/level policy))
             (map? (:seon.config.repair/classes policy))
             (pos-int? (:seon.config.repair/max-fixes-per-form policy))
             (pos-int? (:seon.config.repair/budget-ms policy))
             (render.value/effective-limits-within? sampling-limits
                                                    sampling-limits))
      policy
      (throw (ex-info "The invocation database lacks a complete value-sampling policy."
                      {:seon.error/kind :core-bug})))))
