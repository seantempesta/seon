(ns seon.render.web-debug-test
  "Schema-authored block metadata and honest unavailable dependencies."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.render.web :as web]
            [seon.db :as db]
            [seon.config :as config]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.context-blocks-fixture :as fixture]
            [seon.eval :as evaluation]
            [seon.flow :as flow]
            [seon.error :as error]
            [seon.render :as render]
            [seon.render.hiccup :as hiccup]
            [seon.render.transcript :as transcript]
            [seon.render.walk :as walk]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(defn- element-text [node]
  (cond
    (string? node) node
    (vector? node) (apply str (map element-text (drop (if (map? (second node)) 2 1) node)))
    (sequential? node) (apply str (map element-text node))
    :else ""))

(deftest turn-details-use-the-loop-opening-and-exact-segments
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "debug-turns"
                    :seon.config/manifest {:seon.config.ai/no-provider true}})
     (cluster/ensure-cluster-entity! connection "debug-turns" cluster/boot-process-identity)
     (db/transact! connection
                   [{:seon.agent/id "root" :seon.agent/namespace {:seon.ns/name 'my.agents.root}}
                    {:seon.agent/id "juniper" :seon.agent/namespace {:seon.ns/name 'my.agents.juniper}}])
     (let [ctx (support/fork-cluster-ctx connection)
           environment (support/environment "debug-turns" connection)
           routing (agent/routing)]
       (with-open [launcher (support/closeable
                              (flow/start-work-launcher!
                               {:seon.env/environment environment
                                :seon.flow/configuration (select-keys (support/effective-config) flow/flow-workload-attributes)})
                              flow/stop-work-launcher!)
                   faults (support/closeable (async/chan (async/sliding-buffer 16)) async/close!)]
         (let [handle (support/cluster-handle
                       {:seon.env/environment environment :seon.db/connection connection
                        :seon.cluster/name "debug-turns" :seon.sci.eval/ctx ctx
                        :seon.flow/work-launcher @launcher
                        :seon.flow/executor (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                        :seon.db.process/id cluster/boot-process-identity})
               request {:seon.turn.loop/cluster handle :seon.agent/routing routing
                        :seon.agent/id "juniper" :seon.turn/write? true}]
           (swap! routing assoc :seon.agent/fault-channel @faults)
           (try
             (fixture/install! handle routing)
             (is (string? (:seon.turn/id (turn/system-turn request))))
             (agent/arm! request)
             (let [raw ";; Preserve café, <tags> and spacing as received.\n(str \"café\")\n(/ 1 0)\n"
                   virtual-id (fixture/submit! handle routing raw)
                   unit (merge handle {:seon.db/db @connection :seon.agent/id "juniper"
                                       :seon.turn/id virtual-id
                                       :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms handle)})
                   listed (transcript/render-session unit)
                   kinds (keep #(when (map? %) (:data-turn-kind %)) (tree-seq coll? seq listed))]
               (is (some #{"System"} kinds))
               (is (some #{"Virtual"} kinds)))
             (agent/disarm! request)
             (agent/arm! request)
             (let [provider-id (fixture/submit! handle routing ";; Check the total\n(+ 40 2)")
                   _ (agent/disarm! request)
                   opening (turn/opening-db @connection provider-id)
                   acquire-request (merge handle {:seon.db/db opening :seon.agent/id "juniper"
                                                   :seon.turn/id provider-id
                                                   :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms handle)})
                   acquired (render/acquire-context! acquire-request)
                   expected (:seon.cluster.prompt/text acquired)
                   _ (is (string? expected) (pr-str acquired))
                   attempt (db/transact! connection
                                         [{:db/id "debug-attempt"
                                             :seon.ai.attempt/id "debug-attempt" :seon.ai.attempt/ordinal 0
                                             :seon.ai.attempt/at (java.util.Date.)
                                             :seon.ai/endpoint "http://fixture.invalid"
                                             :seon.ai.attempt/settings-edn "{}"
                                             :seon.ai/model "fixture-model" :seon.ai.attempt/finish-reason "stop"
                                             :seon.ai.attempt/usage-edn
                                             "{\"prompt_tokens\" 100, \"completion_tokens\" 12, \"prompt_cache_hit_tokens\" 80}"}
                                          [:db/add [:seon.turn/id provider-id] :seon.turn/attempts "debug-attempt"]])
                   _ (is (:db-after attempt) (pr-str attempt))
                   _ (agent/arm! request)
                   _ (fixture/submit! handle routing "(str \"later must not enter earlier prompt\")")
                   _ (agent/disarm! request)
                   unit (merge acquire-request {:seon.db/db @connection})
                   detail (transcript/render-session (#'web/session-controls unit))
                   nodes (tree-seq coll? seq detail)
                   exact (apply str (keep #(when (and (vector? %) (= :pre (first %))
                                           (:data-emission-bytes (second %))) (element-text %)) nodes))
                   byte-count (alength (.getBytes ^String expected "UTF-8"))
                   service (assoc handle :seon.store/connection-object connection)
                   response (#'web/debug-response service 'my.agents.juniper "juniper"
                                                   {:headers {"datastar-request" "true"}
                                                    :query-string (str "turn=" provider-id)})
                   listed detail
                   headers (filter #(and (map? %) (:data-turn-id %)) (tree-seq coll? seq listed))
                   turns (db/q '[:find [?id ...] :where [?a :seon.agent/id "juniper"]
                                 [?t :seon.turn/agent ?a] [?t :seon.turn/id ?id]] @connection)]
               (is (seq headers))
               (is (= (set turns) (set (map :data-turn-id headers))))
               (is (= 1 (count (filter #(= "Provider" (:data-turn-kind %)) headers))))
               (is (= expected exact))
               (let [ledger (transcript/render-ledger (#'web/session-controls unit))
                     ledger-nodes (tree-seq coll? seq ledger)
                     reply-node (first (filter #(and (vector? %) (= provider-id (:data-reply-turn (second %)))) ledger-nodes))
                     expected-reply (:seon.turn/reply (db/pull @connection [:seon.turn/reply] [:seon.turn/id provider-id]))]
                 (is (= expected-reply (element-text reply-node)))
                 (is (= (set turns) (set (keep #(when (map? %) (:data-turn-id %)) ledger-nodes))))
                 (is (= (mapv :seon.turn/id (#'transcript/turn-rows @connection "juniper"))
                        (vec (keep #(when (map? %) (:data-strip-turn %)) ledger-nodes))))
                 (is (every? #(<= 0 %) (keep #(when (map? %) (:data-added-bytes %)) ledger-nodes)))
                 (is (= (alength (.getBytes ^String
                                (:seon.cluster.prompt/text
                                 (render/acquire-context! (dissoc unit :seon.turn/id))) "UTF-8"))
                        (reduce + (keep #(when (map? %) (:data-added-bytes %)) ledger-nodes)))
                     "The strip accounts for every byte in current context exactly once.")
                 (is (str/includes? (element-text ledger) "WE SENT"))
                 (is (str/includes? (element-text ledger) "AGENT REPLIED"))
                 (is (str/includes? (element-text ledger) "RESULTS (evaluated by seon)"))
                 (is (str/includes? (element-text ledger) "Check the total · 1 value"))
                 (let [opening-row (first (#'transcript/turn-rows @connection "juniper"))
                       opening-count (db/q '[:find (count ?e) . :in $ ?t
                                             :where [?e :seon.cluster.eval/run ?t]]
                                           @connection (:db/id opening-row))]
                   (is (= [opening-count]
                          (vec (keep #(when (map? %) (:data-opening-emissions %)) ledger-nodes)))))
                 (is (= (db/q '[:find (count ?e) . :in $ ?id
                                 :where [?t :seon.turn/id ?id] [?e :seon.cluster.eval/run ?t]]
                               @connection provider-id)
                        (reduce + (keep #(when (map? %) (:data-evaluation-count %)) ledger-nodes))))
                 (doseq [node ledger-nodes
                         :when (and (vector? node) (= :section (first node)))]
                   (is (#{"seon" "agent"} (:data-author (second node)))))
                 (doseq [card ledger-nodes :when (and (vector? card) (= "System" (:data-turn-kind (second card))))]
                   (is (not (str/includes? (element-text card) "AGENT REPLIED")))))
               (is (= expected (:seon.cluster.prompt/text (render/acquire-context! unit)))
                   "The acquisition owner honors the turn id even with today's database.")
               (doseq [row (#'transcript/turn-rows @connection "juniper")
                       :when (not= :generate (:seon.turn.work/situation row))]
                 (let [historical (turn/opening-db @connection (:seon.turn/id row))
                       historical-request (assoc unit :seon.turn/id (:seon.turn/id row))
                       rebuilt (render/acquire-context! historical-request)
                       folded (web/derive-context! (assoc historical-request :seon.db/db historical))]
                   (is (= (:seon.cluster.prompt/text folded) (:seon.cluster.prompt/text rebuilt))
                       "Every reply turn excludes its own and later reply evaluations.")))
               (is (= byte-count (alength (.getBytes ^String exact "UTF-8"))))
               (is (not (str/includes? exact "later must not enter earlier prompt")))
               (is (str/includes? (element-text detail) "re-read"))
               (doseq [origin ["● opening" "● agent" "● error"]]
                 (is (str/includes? (element-text detail) origin)))
               (is (str/includes? (element-text detail) "cache-hit tokens: 80"))
               (is (= 200 (:status response)))
               (is (= (hiccup/->string detail) (:body response)))
               (is (= "replace" (get-in response [:headers "datastar-mode"])))
               (is (= 404 (:status (#'web/debug-turn-response service "root" provider-id))))
               (doseq [forbidden ["#inst" ":db/id"]]
                 (is (not (str/includes? (apply str (map :data-turn-id headers)) forbidden))))
               (is (seq (evaluation/of-agent @connection "juniper"))))
             (finally
               (agent/disarm! request)
               (doseq [channel-key [:seon.cluster.wake/channel :seon.render/context-channel :seon.turn.loop/completion]]
                 (async/close! (get handle channel-key)))))))))))

(deftest saved-history-preserves-shown-text-with-numeric-lookups
  (support/with-database
   (fn [connection]
     (let [written
           (db/transact!
            connection
            [{:seon.ns/name 'my.agents.history-probe}
             {:db/id "history-agent" :seon.agent/id "history-probe"
              :seon.agent/namespace [:seon.ns/name 'my.agents.history-probe]
              :seon.agent/runtime {:seon.runtime/agent "history-agent"
                                   :seon.runtime/turns ["history-turn"]}
              :seon.agent/plan {:my.plan/agent "history-agent"
                                :my.plan/objective "An addressable component"}}
             {:db/id "history-turn" :seon.turn/id "history-probe-turn" :seon.turn/agent [:seon.agent/id "history-probe"] :seon.turn/opened-tx "datomic.tx"}
             {:seon.cluster.eval/id "history-probe-evaluation"
              :seon.cluster.eval/at (java.util.Date.)
              :seon.cluster.eval/run [:seon.turn/id "history-probe-turn"]
              :seon.cluster.eval/ordinal 0
              :seon.cluster.eval/ns [:seon.ns/name 'my.agents.history-probe]
              :seon.cluster.eval/source "(my.plan/plan {})"
              :seon.eval/shown "The component's shown text."}])
           _ (is (:db-after written) (pr-str written))
           database @connection
           agent-row (db/pull database '[:db/id {:seon.agent/plan [:db/id]}]
                          [:seon.agent/id "history-probe"])
           agent-eid (:db/id agent-row)
           component-eid (get-in agent-row [:seon.agent/plan :db/id])
           request {:seon.db/db database
                    :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
                    :seon.render.walk/lookup agent-eid
                    :seon.sci.admit/caps (config/result-caps (config/defaults))
                    :seon.sci.eval/time-limit-ms 5000
                    :seon.config/on-core-error :record}
           history (walk/history request)]
       (is (instance? Long agent-eid))
       (is (instance? Long component-eid))
       (is (= 1 (count history)))
       (is (= "my.agents.history-probe=> (my.plan/plan {})\n#:seon.repl{:value The component's shown text., :result result/ehistory-probe-evaluation}"
              (:seon.render.history/bytes (first history))))
       (is (= history
              (walk/history (assoc request :seon.render.walk/lookup
                                   [:seon.agent/id "history-probe"]))))
       (let [changed (db/transact! connection
                                  [[:db/add component-eid :my.plan/objective
                                    "The current component changed"]])]
         (is (nil? (:seon.error/kind changed)))
         (is (= history (walk/history (assoc request :seon.db/db @connection)))
             "History reuses the observation without executing or reprinting the component."))))))

(deftest blocks-use-the-values-schema-documentation
  (support/with-database
    {::support/extra-schema
     [{:seon.schema/key ::title :seon.schema.admission/source :core :seon.schema/form ":string"}
      {:seon.schema/key ::notebook
       :seon.schema.admission/source :core
       :seon.schema/form
       (pr-str [:map {:seon.db/attributes true
                      :title "Notebook"
                      :description "Notes for this concern."}
                [::title ::title]])}]}
    (fn [connection]
      (let [database @connection
            projection (schema/projection-from-database database)
            metadata (#'web/block-metadata projection database
                                            {::title "Example"} ::unlabelled nil)]
        (is (= ::notebook (:seon.schema/key metadata)))
        (is (= "Notebook" (:seon.render.web/block-title metadata)))
        (is (= "Notes for this concern."
               (:seon.render.web/block-description metadata)))
        (doseq [value [nil 42 "text" [{::title "Example"}]]]
          (is (map? (#'web/block-metadata projection database value ::unlabelled nil))
              "A scalar, absent attribute, or collection never enters entity transacting."))))))

(deftest unavailable-evaluation-query-is-shown-once
  (let [missing {:seon.render.web/function-unavailable 'example.missing/evaluations
                 :seon.error/kind :seon.render.web/function-unavailable
                 :seon.error/message "Not yet available: example.missing/evaluations"}
        html (#'web/debug-ai-html "example"
                                  {:seon.render.debug/request {}
                                   :seon.render.debug/evaluations missing})
        function-name "example.missing/evaluations"]
    (is (str/includes? html (str "Not yet available: " function-name)))
    (is (= (str/index-of html function-name) (str/last-index-of html function-name)))
    (is (not (str/includes? html "Context now")))
    (is (not (str/includes? html "Would-be system turn")))))


(deftest every-declared-attribute-has-an-ordered-pair-even-when-absent
  (support/with-database
   {::support/extra-schema
    [{:seon.schema/key ::name :seon.schema.admission/source :core :seon.schema/form ":string"}
     {:seon.schema/key ::notes :seon.schema.admission/source :core :seon.schema/form ":string"}
     {:seon.schema/key ::record
      :seon.schema.admission/source :core
      :seon.schema/form
      (pr-str [:map {:seon.db/attributes true}
               [::name ::name]
               [::notes {:optional true} ::notes]])}]}
   (fn [connection]
     (let [database @connection
           projection (schema/projection-from-database database)
           declared (#'web/declared-entity-units projection database {::name "Example"})
           html (#'web/debug-found-values-html
                 projection {:seon.db/db database} {} {} declared
                 {:seon.render.data/incoming {:seon.render.data/complete? true}}
                 {} {} nil nil)
           nodes (tree-seq coll? seq html)
           units (keep #(when (and (vector? %) (= :article (first %)))
                          (:data-seon-unit (second %))) nodes)
           headings (filter #(and (vector? %) (= :h4 (first %))) nodes)]
       (is (= [::name ::notes] declared))
       (is (= (mapv str declared) (vec units)))
       (is (= [[ :h4 "AI"] [:h4 "HTML"] [:h4 "AI"] [:h4 "HTML"]]
              (filterv #(#{"AI" "HTML"} (second %)) headings)))))))

(deftest reverse-blocks-do-not-depend-on-the-graph-page
  (support/with-database
   {::support/extra-schema
    [{:db/ident ::left :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
     {:db/ident ::right :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
     {:db/ident ::hidden :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
     {:seon.schema/key ::declared-concerns
      :seon.schema.admission/source :core
      :seon.schema/form
      (pr-str [:map {:seon.db/attributes true
                     :seon.render/units [::_left ::_right]}
               [:seon.agent/id :seon.agent/id]])}]}
   (fn [connection]
     (db/transact! connection
                   [{:seon.agent/id "relationships"}
                    {::left [:seon.agent/id "relationships"]}
                    {::right [:seon.agent/id "relationships"]}
                    {::hidden [:seon.agent/id "relationships"]}])
     (let [database @connection
           projection (schema/projection-from-database database)
           result (schema/call-with-projection
                   projection
                   #(#'web/acquire-debug-data
                     projection database
                     {:seon.render.debug/subject [:seon.agent/id "relationships"]
                      :seon.render.data/limit 1
                      :seon.render.data/max-ref-attributes 1
                      :seon.render.data/max-result-weight 4000
                      :seon.render.web/pull-max-work 4000}
                     {}))
           output (:seon.render.call/output (:seon.render.web/debug-data-entry result))]
       (is (seq (get-in output [:seon.render.web/reverse-values ::_left])))
       (is (seq (get-in output [:seon.render.web/reverse-values ::_right])))
       (is (nil? (get-in output [:seon.render.web/reverse-values ::_hidden]))
           "an installed reverse ref is not automatically an entity concern")))))


(deftest reverse-declarations-receive-the-actual-relationship-value
  (support/with-database
   (fn [connection]
     (db/transact! connection [{:seon.cluster/name "reverse-render-fixture"}])
     (let [effective (config/defaults)
           caps (config/result-caps effective)
           ctx (support/fork-cluster-ctx connection)
           fault (error/normalize
                  {:seon.error/source {:seon.error/kind ::fixture
                                       :seon.error/message "A declared fault card."}
                   :seon.error/id "reverse-render-fixture"
                   :seon.error/at (java.util.Date.)
                   :seon.error/process "reverse-render-fixture"
                   :seon.sci.admit/caps caps
                   :seon.config.error/max-evidence-bytes
                   (:seon.config.error/max-evidence-bytes effective)})
           experiment (#'web/selected-unit-experiment
                       {:seon.db/db @connection
                        :seon.db/connection connection
                        :seon.sci.eval/ctx ctx
                        :seon.sci.admit/caps caps
                        :seon.sci.eval/time-limit-ms (* 1000 support/event-backstop-seconds)
                        :seon.config/on-core-error :panic
                        :seon.render/profile (render/agent-render-profile effective)
                        :seon.render/captured-calls (atom {})
                        :seon.render/captured-invocations (atom {})}
                       :seon.error/_agent :seon.render/html [fault]
                       [:seon.error/id "reverse-render-fixture"]
                       {:seon.render.data/path [] :seon.render.data/offset 0})
           preview (get-in experiment [:seon.render/previews 'seon.error/render-faults-html])]
       (is (some? preview))
       (is (str/includes? (pr-str preview) "Faults (1)"))
       (is (str/includes? (pr-str preview) "A declared fault card."))))))

(deftest debug-links-omit-defaults-and-round-trip-every-override
  (let [subject [:seon.agent/id "juniper"]
        viewer 'my.agents.juniper]
    (doseq [query [{} {"details" "true" "output" ":seon.render/ai"
                      "limit" "17" "maxWork" "31" "offset" "3"
                      "path" "[:seon.agent/plan]"}]]
      (let [request (#'web/debug-query query subject viewer "juniper")
            url (#'web/debug-page-url request {})
            parameters (#'web/query-params
                        {:query-string (.getRawQuery (java.net.URI. url))})]
        (is (= request (#'web/debug-query parameters subject viewer "juniper")))
        (is (not (str/includes? url "maxRefAttributes=40")))
        (is (not (str/includes? url "viewer=")))))))

(deftest agent-identity-groups-scalars-and-keeps-declared-components
  (support/with-database
   (fn [connection]
     (db/transact! connection [{:seon.cluster/name "identity-blocks"}
                              {:seon.agent/id "identity-blocks"}])
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           projection (schema/projection-from-database database)
           effective (config/defaults)
           request {:seon.db/db database
                    :seon.db/connection connection
                    :seon.sci.eval/ctx ctx
                    :seon.sci.admit/caps (config/result-caps effective)
                    :seon.sci.eval/time-limit-ms (* 1000 support/event-backstop-seconds)
                    :seon.config/on-core-error :panic
                    :seon.render/profile (render/agent-render-profile effective)
                    :seon.render/captured-calls (atom {})
                    :seon.render/captured-invocations (atom {})}
           entity {:seon.agent/id "identity-blocks"}
           declared (#'web/declared-entity-units projection database entity)
           html (#'web/debug-found-values-html
                 projection request
                 {:seon.render.debug/subject [:seon.agent/id "identity-blocks"]}
                 entity declared
                 {:seon.render.data/incoming {:seon.render.data/complete? true}}
                 {} {} nil nil)
           units (into []
                       (keep #(when (and (vector? %) (= :article (first %)))
                                (:data-seon-unit (second %))))
                       (tree-seq coll? seq html))]
       (is (= [":seon.agent/agent" ":seon.agent/plan"
               ":seon.agent/settings" ":seon.agent/runtime"] units))
       (is (str/includes? (pr-str html) "seon.cluster.agent/render-identity-ai"))
       (is (str/includes? (pr-str html) "seon.cluster.agent/render-identity-html"))))))

(deftest a-refused-selection-is-not-reported-as-an-empty-render
  (let [refusal {:seon.error/kind :seon.config/missing-effective
                 :seon.error/message "The render profile is unavailable."}
        html (#'web/experiment-preview-html
              :seon.render/html
              {:seon.render/selection {:seon.render.selection/selected refusal}})]
    (is (str/includes? (pr-str html) "The render profile is unavailable."))
    (is (not (str/includes? (pr-str html) "No render function produced")))))

(deftest block-reference-headers-link-identities-and-omit-unidentified-entities
  (support/with-database
   {::support/extra-schema
    [{:seon.schema/key ::identity-alias
      :seon.schema.admission/source :core
      :seon.schema/form ":seon.agent/id"}]}
   (fn [connection]
     (let [installed (db/transact!
                      connection
                      [(schema.datahike/malli->datahike-attr-in
                        (schema/projection-from-database @connection)
                        ::identity-alias)])
           _ (is (:db-after installed) (pr-str installed))
           written (db/transact! connection
                                 [{:seon.agent/id "reference-agent"}
                                  {::identity-alias "aliased-reference"}
                                  {:db/id "plan"
                                   :my.plan/agent [:seon.agent/id "reference-agent"]}
                                  {:db/id "unidentified"
                                   :my.plan/objective "No identity"}])
           _ (is (:db-after written) (pr-str written))
           database @connection
           projection (schema/projection-from-database database)
           agent-ref (db/pull database '[:db/id] [:seon.agent/id "reference-agent"])
           alias-ref (db/pull database '[:db/id] [::identity-alias "aliased-reference"])
           unidentified {:db/id (get-in written [:tempids "unidentified"])}
           plan-ref {:db/id (get-in written [:tempids "plan"])}
           effective (config/defaults)
           request {:seon.db/db database
                    :seon.db/connection connection
                    :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
                    :seon.sci.admit/caps (config/result-caps effective)
                    :seon.sci.eval/time-limit-ms (* 1000 support/event-backstop-seconds)
                    :seon.config/on-core-error :panic
                    :seon.render/profile (render/agent-render-profile effective)
                    :seon.render/captured-calls (atom {})
                    :seon.render/captured-invocations (atom {})}
           debug-request {:seon.render.debug/subject [:seon.agent/id "reference-agent"]
                          :seon.render.debug/viewer-namespace 'my.agents.reference-agent}]
       (is (integer? (:db/id agent-ref)))
       (is (integer? (:db/id unidentified)))
       (is (= [[[::identity-alias "aliased-reference"] "aliased-reference"]]
              (#'web/referenced-identities database [alias-ref])))
       (is (= [[[:my.plan/agent (:db/id agent-ref)] "reference-agent"]]
              (#'web/referenced-identities database plan-ref)))
       (is (= plan-ref
              (db/pull database '[:db/id] [:my.plan/agent (:db/id agent-ref)])))
       (doseq [[references expected] [[[agent-ref unidentified agent-ref] ["reference-agent"]]
                                    [[agent-ref plan-ref] ["reference-agent" "reference-agent"]]
                                    [[unidentified] []]]]
         (let [html (#'web/debug-found-value
                     projection request debug-request ::references references true
                     {} nil nil)
               header (nth html 2)
               nodes (tree-seq coll? seq header)
               links (filter #(and (vector? %) (= :a (first %))) nodes)
               text (str/join " " (filter string? (tree-seq sequential? seq header)))]
           (is (= expected (mapv #(second (nth % 2)) links)))
           (is (not (str/includes? text (str (:db/id agent-ref)))))
           (is (not (str/includes? text (str (:db/id unidentified)))))
           (is (not (str/includes? text ":db/id")))
           (is (not (str/includes? text "referenced entities")))
           (when-let [link (first links)]
             (let [parameters (#'web/query-params
                               {:query-string (.getRawQuery
                                               (java.net.URI. (:href (second link))))})]
               (is (= (pr-str [:seon.agent/id "reference-agent"])
                      (get parameters "subject")))))
           (when (empty? expected)
             (is (not-any? #(and (map? %)
                                (= "seon-debug-stored-value" (:class %))) nodes)))))))))
