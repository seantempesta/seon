(ns seon.fn-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cluster.ancestor :as ancestor]
            [seon.fn :as seon.fn]
            [seon.test-support :as test-support]))

(def ^:private boot-process
  [:seon.db.process/id "seon.db.process/boot"])

(def ^:private agent-process
  [:seon.db.process/id "seon.db.process/agent"])

(defn- count-by
  [db attribute]
  (d/q '[:find (count ?entity) .
         :in $ ?attribute
         :where [?entity ?attribute]]
       db
       attribute))

(defn- write-program!
  [root]
  (let [file (io/file root "sample.clj")]
    (.mkdirs (.getParentFile file))
    (spit file
          (str
           "(ns sample (:require [clojure.test :refer [deftest]] "
           "[seon.schema :as schema]))\n"
           "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
           "contracted [x] (inc x))\n"
           "(def scratch 42)\n"
           "(schema/register! ::amount [:int {:min 0}])\n"
           "(deftest contracted-test)"))
    root))

(deftest index-rows-admit-only-the-canonical-program
  (let [root (write-program! (str "tmp/fn-test/" (random-uuid)))]
    (let [rows (seon.fn/rows {:seon.fn/roots [root]})]
      (is (= #{"sample/contracted"}
             (into #{} (keep :seon.fn/sym) rows)))
      (is (= #{"sample/contracted-test"}
             (into #{} (keep :seon.test/sym) rows)))
      (is (= #{:sample/amount}
             (into #{} (keep :seon.schema/key) rows)))
      (is (= #{'sample}
             (into #{} (keep :seon.ns/name) rows))))))

(deftest fresh-indexing-fills-canonical-namespace-stubs
  (test-support/with-database
    (fn [connection]
      (let [desired
            (into #{}
                  (keep :seon.ns/name)
                  (seon.fn/rows {:seon.fn/roots seon.fn/source-roots}))
            current
            (into
             #{}
             (d/q '[:find [?name ...]
                    :where
                    [?namespace :seon.ns/name ?name]
                    [?namespace :seon.ns/source]]
                  @connection))]
        (is (= desired current))))))

(deftest indexing-exactly-reconciles-source-and-preserves-cluster-facts
  (let [root (write-program! (str "tmp/fn-test/" (random-uuid)))
        digest (ancestor/digest {:seon.ancestor/roots [root]})
        now (java.util.Date.)]
    (test-support/with-database
      (fn [connection]
        (d/transact
         connection
         {:tx-data
          (into
           [{:seon.db.process/id "seon.db.process/agent"}
            {:seon.cluster.agent/id "root"}
            {:seon.cluster.agent/id "owner-agent"}]
           (concat
            (map (fn [n]
                   {:seon.cluster.message/id (str "message-" n)})
                 (range 366))
            (map (fn [n]
                   {:seon.cluster.run/id (str "run-" n)
                    :seon.cluster.run/closed-at now})
                 (range 229))))})
        (d/transact
         connection
         {:tx-data
          [{:db/id "authored-ns"
            :seon.ns/name 'my.agents.owner
            :seon.ns/source "(ns my.agents.owner)"}
           {:seon.fn/sym "my.agents.owner/survives"
            :seon.fn/ns "authored-ns"
            :seon.fn/source
            "(defn ^{:malli/schema [:=> [:cat] :int]} survives [] 42)"
            :seon.fn/spec "[:=> [:cat] :int]"}]
          :tx-meta {:seon.db/process agent-process}})
        (let [result
              (seon.fn/index!
               {:seon.store/branch-connection connection
                :seon.db/process boot-process
                :seon.fn/roots [root]
                :seon.ancestor/digest digest})
              db @connection]
          (testing "the source-owned namespace, function, and test are current"
            (is (pos? (:seon.reconcile/operations result)))
            (is (= #{'sample 'my.agents.owner}
                   (set
                    (d/q '[:find [?name ...]
                           :where
                           [?namespace :seon.ns/name ?name]
                           [?namespace :seon.ns/source]]
                         db))))
            (is (= #{"sample/contracted" "my.agents.owner/survives"}
                   (set
                    (d/q '[:find [?sym ...]
                           :where [_ :seon.fn/sym ?sym]]
                         db))))
            (is (= #{"sample/contracted-test"}
                   (set
                    (d/q '[:find [?sym ...]
                           :where [_ :seon.test/sym ?sym]]
                         db)))))
          (testing "the owner's measured non-program shape is untouched"
            (is (= 366 (count-by db :seon.cluster.message/id)))
            (is (= 229 (count-by db :seon.cluster.run/id)))
            (is (= 2 (count-by db :seon.cluster.agent/id)))
            (is (= "(ns my.agents.owner)"
                   (d/q '[:find ?source .
                          :where
                          [?namespace :seon.ns/name my.agents.owner]
                          [?namespace :seon.ns/source ?source]]
                        db)))
            (is (= "(defn ^{:malli/schema [:=> [:cat] :int]} survives [] 42)"
                   (d/q '[:find ?source .
                          :where
                          [?function :seon.fn/sym
                           "my.agents.owner/survives"]
                          [?function :seon.fn/source ?source]]
                        db))))
          (testing "the current digest records the explicit synchronization"
            (is (= digest
                   (d/q '[:find ?value .
                          :where [_ :seon.ancestor/digest ?value]]
                        db)))))
        (let [before (:max-tx @connection)
              result
              (seon.fn/index!
               {:seon.store/branch-connection connection
                :seon.db/process boot-process
                :seon.fn/roots [root]
                :seon.ancestor/digest digest})]
          (testing "a converged re-index writes no transaction"
            (is (= {:seon.reconcile/converged? true
                    :seon.reconcile/operations 0}
                   result))
            (is (= before (:max-tx @connection)))))))))
