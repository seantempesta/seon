(ns seon.search-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [malli.generator :as mg]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.schema :as schema]
            [seon.search :as search]
            [seon.sci.eval :as eval]
            [seon.test-support :as test-support]))

(defn- with-index
  [f]
  (test-support/with-database
   (fn [connection]
     (let [_ (test-support/seed-cluster! connection "search-fixture")
           path (str "tmp/search-test-" (random-uuid))
           index (search/open! connection path)]
       (try
         (f connection index)
         (finally
           (search/close! index)
           (test-support/delete-recursively! path)))))))

(defn- search-with-handle
  [handle request]
  (search/search (assoc request :seon.search/handle handle)))

(deftest index-step-contract-has-durable-generative-host-predicates
  (let [definition
        (schema/canonical-definition
         (:malli/schema (meta #'search/index-step))
         {})
        definition-values (set (tree-seq coll? seq definition))]
    (is (= definition (edn/read-string (pr-str definition))))
    (doseq [[predicate-symbol generator-symbol]
            [['seon.search/ping-map-fn?
              'seon.search/ping-map-fn-generator]
             ['seon.search/datahike-datom?
              'seon.search/datahike-datom-generator]]]
      (is (schema/core-predicate-registered? predicate-symbol))
      (is (contains? definition-values predicate-symbol))
      (let [compiled
            (m/schema
             (schema/compilable-form
              [:fn {:gen/gen generator-symbol} predicate-symbol]
              {predicate-symbol @(requiring-resolve predicate-symbol)}))
            generated (mg/sample compiled {:seed 2026080604 :size 20})]
        (is (seq generated))
        (is (every? #(m/validate compiled %) generated))))
    (test-support/with-database
      (fn [connection]
        (let [stored
              (edn/read-string
               (:seon.fn/spec
                (db/pull @connection
                         [:seon.fn/spec]
                         [:seon.fn/sym "seon.search/index-step"])))
              stored-values (set (tree-seq coll? seq stored))]
          (is (contains? stored-values 'seon.search/ping-map-fn?))
          (is (contains? stored-values 'seon.search/ping-map-fn-generator))
          (is (contains? stored-values 'seon.search/datahike-datom?))
          (is (contains? stored-values
                         'seon.search/datahike-datom-generator)))))))

(deftest tokenization-follows-natural-name-separators
  (is (= ["invoice" "line" "item" "count"]
         (search/tokens :invoice.line/item-count)))
  (is (= ["seon" "search" "search"]
         (search/tokens 'seon.search/search))))

(deftest document-fields-follow-the-current-search-declarations
  (test-support/with-database
   (fn [connection]
     (let [field :seon.fn/doc
           declared #(set (#'search/document-specs @connection))
           original {:seon.search/field field :seon.search/index :text}
           changed {:seon.search/field field :seon.search/index :symbol}]
       (is (contains? (declared) original))
       (let [report (db/transact! connection
                                 [[:db/add [:seon.schema/key field]
                                   :seon.search/index :symbol]])]
         (is (some? (:db-after report)) (pr-str report))
         (assert (:db-after report) (pr-str report)))
       (is (contains? (declared) changed))
       (is (not (contains? (declared) original)))))))

(deftest search-scopes-by-declared-fact-family-and-namespace-prefix
  (with-index
    (fn [_connection index]
      (let [response
            (search-with-handle
             index
             {:seon.search/query "search"
              :seon.search/families #{:seon.fn/sym}
              :seon.search/namespace-prefix 'seon.search
              :seon.search/match :substring
              :seon.search/limit 20})
            results (:seon.search/results response)]
        (is (seq results))
        (is (<= (count results) 20))
        (is (every? #(= :seon.fn/sym (:seon.search/family %)) results))
        (is (every?
             #(let [namespace-name
                    (str (:seon.search/namespace-prefix %))]
                (or (= "seon.search" namespace-name)
                    (str/starts-with? namespace-name "seon.search.")))
             results))))))

(deftest an-exact-transaction-report-advances-the-index-basis
  (with-index
    (fn [connection index]
      (let [namespace-report
            (db/transact! connection
                          [{:seon.ns/name 'fixture.search.incremental}])
            _ (assert (:db-after namespace-report) (pr-str namespace-report))
            _ (search/apply-report! index namespace-report)
            report
            (db/transact!
             connection
             [{:seon.fn/sym "fixture.search.incremental/needle"
               :seon.schema.admission/source :core
               :seon.fn/ns [:seon.ns/name 'fixture.search.incremental]
               :seon.fn/source "(defn needle [])"
               :seon.fn/doc "uniquelyincrementalneedle"}])]
        (is (some? (:db-after report)) (pr-str report))
        (assert (:db-after report) (pr-str report))
        (search/apply-report! index report)
        (let [response
              (search-with-handle
               index
               {:seon.search/query "uniquelyincrementalneedle"
                :seon.search/families #{:seon.fn/sym}
                :seon.search/namespace-prefix 'fixture.search
                :seon.search/match :token
                :seon.search/limit 5})]
          (is (= (:max-tx (:db-after report))
                 (:seon.search/basis-t response)))
          (is (= ["fixture.search.incremental/needle"]
                 (mapv :seon.search/identity
                       (:seon.search/results response)))))))))

(deftest message-and-instruction-content-are-searchable-by-family
  (with-index
    (fn [connection index]
      (let [report
            (db/transact!
             connection
             [{:db/id "fixture-search-agent"
               :seon.agent/id "fixture-search-agent"}
              {:seon.cluster.instruction/id :fixture-search-instruction
               :seon.cluster.instruction/text "crossfamilysearchneedle"}
              {:seon.message/id "fixture-search-message" :seon.message/to "fixture-search-agent" :seon.message/content "crossfamilysearchneedle"}])]
        (search/apply-report! index report)
        (let [request
              {:seon.search/query "crossfamilysearchneedle"
               :seon.search/match :token
               :seon.search/limit 5}
              instruction-results
              (:seon.search/results
               (search-with-handle
                index
                (assoc request :seon.search/families
                       #{:seon.cluster.instruction/id})))
              message-results
              (:seon.search/results
               (search-with-handle
                index
                (assoc request :seon.search/families
                       #{:seon.message/id})))]
          (is (= [{:seon.search/family :seon.cluster.instruction/id
                   :seon.search/field :seon.cluster.instruction/text
                   :seon.search/identity :fixture-search-instruction}]
                 (mapv #(select-keys % [:seon.search/family
                                        :seon.search/field
                                        :seon.search/identity])
                       instruction-results)))
          (is (= [{:seon.search/family :seon.message/id
                   :seon.search/field :seon.message/content
                   :seon.search/identity "fixture-search-message"}]
                 (mapv #(select-keys % [:seon.search/family
                                        :seon.search/field
                                        :seon.search/identity])
                       message-results))))))))

(deftest search-is-an-ordinary-sci-evaluation-function
  (with-index
    (fn [connection index]
      (let [ctx (test-support/fork-cluster-ctx connection "search-fixture")
            state (get ctx env/state-carrier)
            environment (assoc (env/of ctx) :seon.search/handle index)
            _ (env/replace-environment! state environment)
            _ (is (identical? index (search/supplied-handle (env/of ctx))))
            _ (is (identical? index (:seon.search/handle
                                    (env/scope environment {:seon.agent/id "search-agent"}))))
            request {:seon.search/query "search"
                     :seon.search/families #{:seon.fn/sym}
                     :seon.search/namespace-prefix 'seon.search
                     :seon.search/match :token
                     :seon.search/limit 3}
            evaluation
            (eval/evaluate
             {:seon.sci.eval/ctx ctx
              :seon.cluster.eval/source
              (str "(seon.search/search "
                   (pr-str (list 'quote request)) ")")
              :seon.sci.admit/caps
              (config/result-caps config/defaults)
              :seon.sci.eval/time-limit-ms 5000
              :seon.config/on-core-error :panic})
            result (:seon.sci.admit/value evaluation)]
        (is (nil? (:seon.cluster.eval/error evaluation)))
        (is (seq (:seon.search/results result)))
        (is (every? #(= :seon.fn/sym (:seon.search/family %))
                    (:seon.search/results result)))))))
