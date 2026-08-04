(ns seon.render-coverage-test
  "Focused coverage for important root-runtime and effect receipt faces."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.ai.tokens :as tokens]
            [seon.bootstrap :as bootstrap]
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
            [seon.schema.edn :as schema.edn]
            [seon.schema.form :as schema.form]
            [seon.sci.eval :as sci.eval]
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

(defn- walk-output-by-attribute
  [units attribute]
  (:seon.render/output
   (some #(when (= attribute (:seon.render.walk/attribute %)) %) units)))

(deftest important-runtime-entities-declare-and-use-readable-faces
  (is (= {:seon.render/ai `transcript/render-session-ai
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
  (is (= {:seon.render/ai `bootstrap/render-ai
          :seon.render/html `bootstrap/render-html}
         (select-keys (family-properties :seon.bootstrap.plan/plan)
                      [:seon.render/ai :seon.render/html])))
  (support/with-database
   (fn [connection]
     (seed-entities! connection)
     (let [database @connection
           ctx (sci.eval/cluster-ctx database connection)
           cluster-value (pulled database [:seon.cluster/name cluster-name])
           config-value (pulled database [:seon.config/cluster cluster-name])
           plan-value (pulled database
                              [:seon.bootstrap.plan/id bootstrap/plan-id])
           cases [{:value cluster-value
                   :direct-ai cluster/render-ai
                   :direct-html cluster/render-html
                   :class "seon-family-entry seon-cluster-entry"}
                  {:value config-value
                   :direct-ai config/render-ai
                   :direct-html config/render-html
                   :class "seon-family-entry seon-config-entry"}
                  {:value plan-value
                   :direct-ai bootstrap/render-ai
                   :direct-html bootstrap/render-html
                   :class "seon-family-entry seon-bootstrap-plan-entry"}]]
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
             plan-ai (bootstrap/render-ai (assoc plan-value
                                                   :seon.db/db database))]
         (is (not (str/includes? config-ai "DEEPSEEK_API_KEY")))
         (is (not (str/includes? config-ai
                                 "https://api.deepseek.com")))
         (is (str/includes? plan-ai "tokens"))
         (is (not (str/includes? plan-ai
                                 ":seon.cluster.run.form/source")))
         (is (not (str/includes? plan-ai
                                 ":seon.bootstrap.plan.form/context"))))
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
                              :seon.cluster/config
                              :seon.cluster/bootstrap-plan]]
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
           ctx (sci.eval/cluster-ctx database connection)
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
