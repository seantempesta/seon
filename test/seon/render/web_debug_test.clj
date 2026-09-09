(ns seon.render.web-debug-test
  "Schema-authored block metadata and honest unavailable dependencies."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.render.web :as web]
            [seon.render.walk :as walk]
            [seon.db :as db]
            [seon.config :as config]
            [seon.error :as error]
            [seon.render :as render]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(deftest blocks-use-the-values-schema-documentation
  (support/with-database
    {::support/extra-schema
     [{:seon.schema/key ::title :seon.schema/form ":string"}
      {:seon.schema/key ::notebook
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
    [{:seon.schema/key ::name :seon.schema/form ":string"}
     {:seon.schema/key ::notes :seon.schema/form ":string"}
     {:seon.schema/key ::record
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

(deftest history-preserves-numeric-entity-lookups
  (support/with-database
   (fn [connection]
     (db/transact! connection [{:my.plan/objective "An anonymous component"}])
     (let [database @connection
           eid (db/q '[:find ?e . :where [?e :my.plan/objective "An anonymous component"]]
                     database)
           entries (#'walk/history-entries
                    {:seon.db/db database}
                    [{:seon.render.walk/lookup eid
                      :seon.render.walk/path []
                      :seon.render/distance 0
                      :seon.render/output "The component's shown text."}]
                    (atom {}))
           alternate (#'walk/history-entries
                       {:seon.db/db database}
                       [{:seon.render.walk/lookup eid
                         :seon.render.walk/path [:fixture/another-reference]
                         :seon.render/distance 0
                         :seon.render/output "The component's shown text."}]
                       (atom {}))]
       (is (= (:seon.render.history/call-id (first entries))
              (:seon.render.history/call-id (first alternate))))
       (is (integer? eid))
       (is (= 1 (count entries)))
       (is (= eid (:seon.render.history/subject (first entries))))
       (is (= "The component's shown text."
              (:seon.render.history/bytes (first entries))))))))

(deftest history-identity-survives-reference-path-and-basis-changes
  (let [entry {:seon.render.history/call-id [42]
               :seon.render.history/basis-transaction 1
               :seon.render.history/bytes "The saved observation."}
        repeated (assoc entry :seon.render.history/basis-transaction 2
                              :seon.render.history/bytes "A later projection.")
        later {:seon.render.history/call-id [43]
               :seon.render.history/basis-transaction 2
               :seon.render.history/bytes "The next evaluation."}]
    (is (= [entry later] (web/append-history [entry] [repeated later])))))

(deftest reverse-blocks-do-not-depend-on-the-graph-page
  (support/with-database
   {::support/extra-schema
    [{:db/ident ::left :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
     {:db/ident ::right :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
     {:db/ident ::hidden :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
     {:seon.schema/key ::declared-concerns
      :seon.schema/form
      (pr-str [:map {:seon.db/attributes true
                     :seon.render/units [::_left ::_right]}
               [:seon.cluster.agent/id :seon.cluster.agent/id]])}]}
   (fn [connection]
     (db/transact! connection
                   [{:seon.cluster.agent/id "relationships"}
                    {::left [:seon.cluster.agent/id "relationships"]}
                    {::right [:seon.cluster.agent/id "relationships"]}
                    {::hidden [:seon.cluster.agent/id "relationships"]}])
     (let [database @connection
           projection (schema/projection-from-database database)
           result (schema/call-with-projection
                   projection
                   #(#'web/acquire-debug-data
                     projection database
                     {:seon.render.debug/subject [:seon.cluster.agent/id "relationships"]
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
  (let [subject [:seon.cluster.agent/id "juniper"]
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
                              {:seon.cluster.agent/id "identity-blocks"}])
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
           entity {:seon.cluster.agent/id "identity-blocks"}
           declared (#'web/declared-entity-units projection database entity)
           html (#'web/debug-found-values-html
                 projection request
                 {:seon.render.debug/subject [:seon.cluster.agent/id "identity-blocks"]}
                 entity declared
                 {:seon.render.data/incoming {:seon.render.data/complete? true}}
                 {} {} nil nil)
           units (into []
                       (keep #(when (and (vector? %) (= :article (first %)))
                                (:data-seon-unit (second %))))
                       (tree-seq coll? seq html))]
       (is (= [":seon.cluster.agent/agent" ":seon.agent/plan"
               ":seon.agent/settings"] units))
       (is (str/includes? (pr-str html) "seon.cluster.agent/render-identity-ai"))
       (is (str/includes? (pr-str html) "seon.cluster.agent/render-identity-html"))))))
