(ns seon.render-coverage-test
  "Focused coverage for important root-runtime and effect receipt faces."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.ai.tokens :as tokens]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.run :as run]
            [seon.config :as config]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.render :as render]
            [seon.render.hiccup :as hiccup]
            [seon.render.transcript :as transcript]
            [seon.render.walk :as walk]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.schema.form :as schema.form]
            [seon.test-support :as support])
  (:import [java.util Date]))

(def ^:private cluster-name "render-coverage")
(def ^:private agent-id "render-coverage-agent")
(def ^:private run-id "render-coverage-run")
(def ^:private owner-symbol "my.fs/read")
(def ^:private opened-at (Date. 1000))
(def ^:private settled-at (Date. 1012))
(def ^:private interrupted-at (Date. 1015))
(def ^:private request-edn "#:my.fs{:path \"README.md\"}")
(def ^:private result-edn "#:my.fs{:content \"rendered content\"}")
(def ^:private blob-digest (apply str (repeat 64 "a")))
(def ^:private caps (config/result-caps (config/defaults)))

(defn- render-request
  [database ctx value]
  {:seon.db/db database
   :seon.sci.eval/ctx ctx
   :seon.render/value value
   :seon.sci.admit/caps caps
   :seon.sci.eval/time-limit-ms 2000
   :seon.config/on-core-error :panic})

(defn- family-properties
  [schema-key]
  (-> (schema.edn/packaged-forms)
      (get schema-key)
      schema.form/schema-properties))

(defn- card?
  [css-class value]
  (and (= :article (first value))
       (= css-class (get-in value [1 :class]))))

(defn- one-to-three-lines?
  [text]
  (<= 1 (count (str/split-lines text)) 3))

(defn- seed-entities!
  [connection]
  (config/apply-compiled!
   connection
   (config/compile-manifest {:seon.boot/cluster-name cluster-name}))
  (cluster/ensure-cluster-entity!
   connection cluster-name cluster/boot-process-identity)
  (db/transact!
   connection
   (into
    (agent/creation-tx
     {:seon.cluster.agent/id agent-id
      :seon.cluster/name cluster-name
      :seon.ns/name 'my.agents.render-coverage})
    [{:seon.fn/sym owner-symbol}
     {:seon.cluster.run/id run-id
      :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]}
     {:seon.effect/id "effect-pending"
      :seon.effect/run [:seon.cluster.run/id run-id]
      :seon.effect/owner [:seon.fn/sym owner-symbol]
      :seon.effect/form-ordinal 3
      :seon.effect/ordinal 0
      :seon.effect/request-edn request-edn
      :seon.effect/opened-at opened-at}
     {:seon.effect/id "effect-returned"
      :seon.effect/run [:seon.cluster.run/id run-id]
      :seon.effect/owner [:seon.fn/sym owner-symbol]
      :seon.effect/form-ordinal 3
      :seon.effect/ordinal 1
      :seon.effect/request-edn request-edn
      :seon.effect/opened-at opened-at
      :seon.effect/result-edn result-edn
      :seon.effect/result-blob blob-digest
      :seon.effect/result-size 99
      :seon.effect/duration-ms 12
      :seon.effect/settled-at settled-at}
     {:seon.effect/id "effect-interrupted"
      :seon.effect/run [:seon.cluster.run/id run-id]
      :seon.effect/owner [:seon.fn/sym owner-symbol]
      :seon.effect/form-ordinal 3
      :seon.effect/ordinal 2
      :seon.effect/request-edn request-edn
      :seon.effect/opened-at opened-at
      :seon.effect/interrupted-at interrupted-at}]))
  nil)

(defn- pulled
  [database lookup]
  (db/pull database '[*] lookup))

(deftest render-without-a-carried-profile-or-projection-refuses
  (let [world (atom nil)]
    (support/with-database
     (fn [connection]
       (seed-entities! connection)
       (let [database @connection]
         (reset! world
                 {:database database
                  :ctx (support/fork-cluster-ctx connection)
                  :value (pulled database
                                 [:seon.config/cluster cluster-name])}))))
    (let [{:keys [database ctx value]} @world
          result (with-redefs [schema/handed-projection (constantly nil)]
                   (render/render-ai (render-request database ctx value)))]
      (is (= ::render/missing-projection (:seon.error/kind result)))
      (is (= 'seon.render/request-profile
             (get-in result
                     [:seon.error/data
                      :seon.error/diagnostic-operation]))))))

(defn- walk-output-by-attribute
  [units attribute]
  (:seon.render/output
   (some #(when (= attribute (:seon.render.walk/attribute %)) %) units)))

(deftest important-runtime-entities-declare-and-use-readable-faces
  (is (= {:seon.render/ai `agent/render-situation-ai
          :seon.render/html `transcript/render-session-html}
         (select-keys (family-properties :seon.cluster.agent/agent)
                      [:seon.render/ai :seon.render/html])))
  (is (= {:seon.render/ai `agent/render-creation-ai
          :seon.render/html `agent/render-creation-html}
         (select-keys
          (family-properties :seon.cluster.agent/creation-result)
          [:seon.render/ai :seon.render/html])))
  (is (= {:seon.render/ai `run/render-receipt-ai
          :seon.render/html `run/render-receipt-html}
         (select-keys (family-properties :seon.cluster.eval/receipt)
                      [:seon.render/ai :seon.render/html])))
  (is (= {:seon.render/ai `cluster/render-ai
          :seon.render/html `cluster/render-html}
         (select-keys (family-properties :seon.cluster/cluster)
                      [:seon.render/ai :seon.render/html])))
  (is (= {:seon.render/ai `config/render-ai
          :seon.render/html `config/render-html}
         (select-keys (family-properties :seon.config/entity)
                      [:seon.render/ai :seon.render/html])))
  (support/with-database
   (fn [connection]
     (seed-entities! connection)
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           cluster-value (pulled database [:seon.cluster/name cluster-name])
           config-value (pulled database [:seon.config/cluster cluster-name])
           cases [{:value cluster-value
                   :direct-ai cluster/render-ai
                   :direct-html cluster/render-html
                   :class "seon-family-entry seon-cluster-entry"}
                  {:value config-value
                   :direct-ai config/render-ai
                   :direct-html config/render-html
                   :class "seon-family-entry seon-config-entry"}]]
       (doseq [{:keys [value direct-ai direct-html class]} cases]
         (let [request (render-request database ctx value)
               ai (render/render-ai request)
               html (render/render-html request)]
           (is (= (direct-ai (assoc value :seon.db/db database)) ai))
           (is (= (direct-html (assoc value :seon.db/db database)) html))
           (is (one-to-three-lines? ai))
           (is (card? class html))
           (is (not (str/includes? ai ":db/id")))
           (is (not (str/includes? (hiccup/->string html) ":db/id")))))
       (let [config-ai (config/render-ai (assoc config-value
                                                 :seon.db/db database))
             model-value
             (pulled database
                     [:seon.ai.model/id (:seon.config.ai/model config-value)])
             model-ai (render/render-ai
                       (render-request database ctx model-value))]
         (is (not (str/includes? config-ai "DEEPSEEK_API_KEY")))
         (is (not (str/includes? config-ai
                                 "https://api.deepseek.com")))
         (is (not (str/includes? config-ai "Available models"))
             "the recurring config face does not inline the model roster")
         (is (str/includes? model-ai
                            (str "Model " (:seon.config.ai/model config-value)))
             "the configured model remains reachable through its own face"))
       (doseq [output [:seon.render/ai :seon.render/html]]
         (let [units (walk/neighborhood
                      {:seon.db/db database
                       :seon.sci.eval/ctx ctx
                       :seon.render.walk/lookup
                       [:seon.cluster.agent/id agent-id]
                       :seon.render/output output
                       :seon.sci.admit/caps caps
                       :seon.sci.eval/time-limit-ms 2000
                       :seon.config/on-core-error :panic
                       :seon.render/distance 2})]
           (doseq [attribute [:seon.cluster.agent/cluster
                              :seon.cluster/config]]
             (let [face (walk-output-by-attribute units attribute)]
               (is (some? face))
               (is (not (str/includes? (str face) ":db/id")))))))))))

(deftest effect-receipts-render-state-from-attribute-presence
  (is (= {:seon.render/ai `effect/render-ai
          :seon.render/html `effect/render-html}
         (select-keys (family-properties :seon.effect/receipt)
                      [:seon.render/ai :seon.render/html])))
  (support/with-database
   (fn [connection]
     (seed-entities! connection)
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           pending (pulled database [:seon.effect/id "effect-pending"])
           returned (pulled database [:seon.effect/id "effect-returned"])
           interrupted (pulled database [:seon.effect/id
                                          "effect-interrupted"])
           faces
           (into {}
                 (map
                  (fn [[state value]]
                    (let [request (render-request database ctx value)]
                      [state {:ai (render/render-ai request)
                              :html (render/render-html request)}])))
                 {:pending pending
                  :returned returned
                  :interrupted interrupted})]
       (doseq [[_ {:keys [ai html]}] faces]
         (is (one-to-three-lines? ai))
         (is (card? "seon-family-entry seon-effect-receipt-entry" html))
         (is (str/includes? ai owner-symbol))
         (is (str/includes? ai run-id))
         (is (not (str/includes? ai ":db/id"))))
       (let [pending-ai (get-in faces [:pending :ai])
             pending-html (hiccup/->string (get-in faces [:pending :html]))
             returned-ai (get-in faces [:returned :ai])
             returned-html (hiccup/->string (get-in faces [:returned :html]))
             interrupted-ai (get-in faces [:interrupted :ai])
             interrupted-html
             (hiccup/->string (get-in faces [:interrupted :html]))]
         (testing "pending shows its request and omits terminal fields"
           (is (str/includes? pending-ai "Request"))
           (is (str/includes? pending-ai
                              (str "~" (tokens/estimate request-edn)
                                   " tokens")))
           (is (not (str/includes? pending-ai "Result")))
           (is (not (str/includes? pending-html "seon-effect-result")))
           (is (not (str/includes? pending-html "Duration"))))
         (testing "returned shows result, duration, and blob handle"
           (is (str/includes? returned-ai "Result"))
           (is (str/includes? returned-ai
                              (str "~" (tokens/estimate result-edn)
                                   " tokens")))
           (is (str/includes? returned-html "Duration"))
           (is (str/includes? returned-html blob-digest))
           (is (not (str/includes? returned-ai "bytes")))
           (is (not (str/includes? returned-ai "chars"))))
         (testing "interrupted shows the interruption and no result"
           (is (str/includes? interrupted-ai "interrupted"))
           (is (not (str/includes? interrupted-ai "Result")))
           (is (not (str/includes? interrupted-html "seon-effect-result")))
           (is (not (str/includes? interrupted-html "Duration")))))
       (doseq [output [:seon.render/ai :seon.render/html]]
         (let [units (walk/neighborhood
                      {:seon.db/db database
                       :seon.sci.eval/ctx ctx
                       :seon.render.walk/lookup
                       [:seon.cluster.agent/id agent-id]
                       :seon.render/output output
                       :seon.sci.admit/caps caps
                       :seon.sci.eval/time-limit-ms 2000
                       :seon.config/on-core-error :panic
                       :seon.render/distance 2})
               face (walk-output-by-attribute units :seon.effect/run)]
           (is (some? face))
           (is (str/includes? (str face) owner-symbol))))))))

;;; ---------------------------------------------------------------------------
;;; A producer that delegates its own value never re-selects itself
;;; ---------------------------------------------------------------------------

(deftest a-producer-that-delegates-its-own-value-is-never-re-entered
  ;; THE CLASS: a declared producer may render its value THROUGH another
  ;; producer — `seon.ai/attempt-html` hands the attempt, minus reasoning,
  ;; to the value floor `seon.render.value/render-html`. The floor projects
  ;; that value, selection answers `seon.ai/attempt-html` again, and the
  ;; chain never returns. Measured 2026-08-07 in
  ;; `seon.render.web-test/thinking-stream-morphs-into-the-settled-session-transcript`:
  ;; the render proc's virtual thread sat past 1024 frames of
  ;; project-node → attempt-html → prepare → project-node, so its transform
  ;; never ended, its `::flow/stop` transition never ran, and the completion
  ;; `disarm-agents!` joins before releasing the branch connection never
  ;; arrived. `invoke-selected` now records what it is running and
  ;; `project-node` refuses a producer already on the chain, so the cycle
  ;; cannot be built.
  ;;
  ;; The unguarded code does not fail here, it never returns — so the
  ;; oracle is the shared loud backstop around the render, and the
  ;; assertions read the value the walk did produce.
  (support/with-database
   (fn [connection]
     (seed-entities! connection)
     (db/transact!
      connection
      [{:seon.ai.attempt/id "re-entrance-attempt"
        :seon.ai.attempt/run [:seon.cluster.run/id run-id]
        :seon.ai.attempt/ordinal 0
        :seon.ai.attempt/at opened-at
        :seon.ai/endpoint "https://provider.invalid"
        :seon.ai/model "fixture-attempt"
        :seon.ai.attempt/settings-edn "{}"
        :seon.ai.attempt/reasoning "private provider reasoning"}])
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           attempt (pulled database [:seon.ai.attempt/id "re-entrance-attempt"])
           request (render-request database ctx attempt)
           html (support/await-event!
                 (future (render/render-html request))
                 [:attempt-html-returns])
           ai (support/await-event!
               (future (render/render-ai request))
               [:attempt-ai-returns])]
       (is (hiccup/hiccup? html)
           "the delegating producer returns hiccup rather than recursing")
       (is (string? ai))
       (is (str/includes? (hiccup/->string html) "fixture-attempt")
           "the attempt's ordinary facts still reach the page")
       (is (not (str/includes? (hiccup/->string html)
                               "private provider reasoning"))
           "reasoning keeps its own disclosure")
       (is (not (str/includes? ai "private provider reasoning")))))))
