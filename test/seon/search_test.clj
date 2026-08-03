(ns seon.search-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.search :as search]
            [seon.sci.eval :as eval]
            [seon.test-support :as test-support]))

(defn- with-index
  [f]
  (test-support/with-database
   (fn [connection]
     (let [path (str "tmp/search-test-" (random-uuid))
           index (search/open! connection path)]
       (try
         (binding [db/*conn* connection]
           (f connection index))
         (finally
           (search/close! index)
           (test-support/delete-recursively! path)))))))

(deftest tokenization-follows-natural-name-separators
  (is (= ["invoice" "line" "item" "count"]
         (search/tokens :invoice.line/item-count)))
  (is (= ["seon" "search" "search"]
         (search/tokens 'seon.search/search))))

(deftest search-scopes-by-declared-fact-family-and-namespace-prefix
  (with-index
    (fn [_ _]
      (let [response
            (search/search
             {:seon.search/query "search"
              :seon.search/families #{:function}
              :seon.search/namespace-prefix 'seon.search
              :seon.search/match :substring
              :seon.search/limit 20})
            results (:seon.search/results response)]
        (is (seq results))
        (is (<= (count results) 20))
        (is (every? #(= :function (:seon.search/family %)) results))
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
            _ (search/apply-report! index namespace-report)
            report
            (db/transact!
             connection
             [{:seon.fn/sym "fixture.search.incremental/needle"
               :seon.fn/ns [:seon.ns/name 'fixture.search.incremental]
               :seon.fn/source "(defn needle [])"
               :seon.fn/doc "uniquelyincrementalneedle"}])]
        (search/apply-report! index report)
        (let [response
              (search/search
               {:seon.search/query "uniquelyincrementalneedle"
                :seon.search/families #{:function}
                :seon.search/namespace-prefix 'fixture.search
                :seon.search/match :token
                :seon.search/limit 5})]
          (is (= (:max-tx (:db-after report))
                 (:seon.search/basis-t response)))
          (is (= ["fixture.search.incremental/needle"]
                 (mapv :seon.search/identity
                       (:seon.search/results response)))))))))

(deftest search-is-an-ordinary-door-mode-function
  (with-index
    (fn [connection _]
      (let [ctx (eval/cluster-ctx @connection connection)
            request {:seon.search/query "search"
                     :seon.search/families #{:function}
                     :seon.search/namespace-prefix 'seon.search
                     :seon.search/match :token
                     :seon.search/limit 3}
            evaluation
            (eval/evaluate
             {:seon.sci.eval/ctx ctx
              :seon.cluster.run.form/source
              (str "(seon.search/search "
                   (pr-str (list 'quote request)) ")")
              :seon.sci.admit/caps
              (config/result-caps (config/defaults))
              :seon.sci.eval/time-limit-ms 5000
              :seon.config/on-core-error :panic})
            result (:seon.sci.admit/value evaluation)]
        (is (nil? (:seon.cluster.eval/error evaluation)))
        (is (seq (:seon.search/results result)))
        (is (every? #(= :function (:seon.search/family %))
                    (:seon.search/results result)))))))
