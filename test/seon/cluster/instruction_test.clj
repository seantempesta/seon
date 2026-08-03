(ns seon.cluster.instruction-test
  "Computed cluster context membership and idempotent entity initialization."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types]
            [seon.db :as db]
            [seon.cluster :as cluster]
            [seon.cluster.instruction :as instruction]
            [seon.test-support :as test-support]))

(def ^:private reader-options {:eof ::eof})

(defn- code-spans
  [text]
  (mapv (fn [[_ fenced inline]] (or fenced inline))
        (re-seq #"(?s)```clojure\s*(.*?)```|`([^`\n]+)`" text)))

(defn- read-forms
  [source]
  (let [pushback (reader-types/indexing-push-back-reader source)]
    (loop [forms []]
      (let [form (reader/read reader-options pushback)]
        (if (= ::eof form)
          forms
          (recur (conj forms form)))))))

(defn- qualified-calls
  [form]
  (tree-seq coll? seq form))

(defn- instruction-calls
  [text]
  (into []
        (comp
         (mapcat read-forms)
         (mapcat qualified-calls)
         (filter seq?)
         (filter #(and (symbol? (first %))
                       (namespace (first %))))
         (map (fn [form]
                {:seon.cluster.instruction/symbol (str (first form))
                 :seon.cluster.instruction/arity (dec (count form))})))
        (code-spans text)))

(defn- call-resolution
  [db call]
  (let [function-symbol (:seon.cluster.instruction/symbol call)
        function (db/pull db
                         [{:seon.fn/arities
                           [:seon.fn.arity/order
                            :seon.fn.arity/min
                            :seon.fn.arity/max]}]
                         [:seon.fn/sym function-symbol])
        arities (sort-by :seon.fn.arity/order (:seon.fn/arities function))
        call-arity (:seon.cluster.instruction/arity call)]
    (assoc call
           :seon.cluster.instruction/accepted-arities arities
           :seon.cluster.instruction/resolved?
           (boolean
            (some (fn [{minimum :seon.fn.arity/min
                        maximum :seon.fn.arity/max}]
                    (and (<= minimum call-arity)
                         (or (nil? maximum) (<= call-arity maximum))))
                  arities)))))

(defn- cluster-toolkit
  [db cluster-name]
  (set
   (db/q '[:find [?namespace-name ...]
          :in $ ?cluster-name
          :where
          [?cluster :seon.cluster/name ?cluster-name]
          [?cluster :seon.cluster/toolkit ?namespace]
          [?namespace :seon.ns/name ?namespace-name]]
        db cluster-name)))

(deftest source-has-one-owner-editable-getting-started-row
  (is (re-find #"seon\.render/walk" instruction/getting-started-text))
  (is (= 1 (count (re-seq #"```clojure" instruction/getting-started-text))))
  (is (= [{:seon.cluster.instruction/id :getting-started
           :seon.cluster.instruction/text instruction/getting-started-text}]
         (instruction/seed-rows)))
  (test-support/with-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.cluster.instruction/id :getting-started
         :seon.cluster.instruction/text "Owner revision."}
        {:seon.cluster.instruction/id :reply-grammar
         :seon.cluster.instruction/text "Superseded."}])
      (db/transact! connection
                  (#'cluster/instruction-row-changes
                   @connection (instruction/seed-rows)))
      (is (= #{:getting-started}
             (set
              (db/q '[:find [?id ...]
                     :where [_ :seon.cluster.instruction/id ?id]]
                   @connection))))
      (is (= "Owner revision."
             (db/q '[:find ?text .
                    :where
                    [?instruction :seon.cluster.instruction/id
                     :getting-started]
                    [?instruction :seon.cluster.instruction/text ?text]]
                  @connection))))))

(deftest every-qualified-call-in-instruction-code-resolves-at-its-arity
  (test-support/with-database
    (fn [connection]
      (let [calls (instruction-calls instruction/getting-started-text)
            resolutions (mapv #(call-resolution @connection %) calls)]
        (testing "the invariant is computed from every Clojure code span"
          (is (seq calls))
          (is (every? :seon.cluster.instruction/resolved? resolutions)
              (pr-str resolutions)))))))

(deftest cluster-toolkit-exactly-converges-to-the-computed-rule
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection "toolkit")
      (let [computed (set (instruction/toolkit-namespaces @connection))
            removed (first (sort computed))]
        (is (seq computed) "The canonical corpus must expose a toolkit.")
        (is (= computed (cluster-toolkit @connection "toolkit")))
        (db/transact!
         connection
         (cond-> [{:seon.ns/name 'my.stale.toolkit}
                  {:seon.cluster/name "toolkit"
                   :seon.cluster/toolkit
                   [:seon.ns/name 'my.stale.toolkit]}]
           removed
           (conj [:db/retract
                  [:seon.cluster/name "toolkit"]
                  :seon.cluster/toolkit
                  [:seon.ns/name removed]])))
        (is (not= computed (cluster-toolkit @connection "toolkit")))
        (cluster/ensure-cluster-entity!
         connection "toolkit" "instruction-test-process")
        (is (some? (db/q '[:find ?process .
                          :where
                          [?process :seon.db.process/id
                           "instruction-test-process"]]
                        @connection)))
        (is (= computed (cluster-toolkit @connection "toolkit")))))))

(deftest ensure-entity-creates-once-and-resumes-untouched
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection "ensure")
      (let [first-request {:seon.cluster.agent/id "alice"
                           :seon.cluster/name "ensure"
                           :seon.ns/name 'my.agents.alice}
            resumed-request (assoc first-request
                                   :seon.ns/name 'my.agents.replacement)]
        (is (nil? (:seon.error/kind
                   (cluster/ensure-entity!
                    connection cluster/boot-process-identity first-request))))
        (let [before (db/pull @connection '[*]
                             [:seon.cluster.agent/id "alice"])]
          (is (nil? (:seon.error/kind
                     (cluster/ensure-entity!
                      connection cluster/boot-process-identity
                      resumed-request))))
          (is (= before
                 (db/pull @connection '[*]
                         [:seon.cluster.agent/id "alice"])))
          (is (nil? (db/q '[:find ?namespace .
                           :in $ ?namespace-name
                           :where
                           [?namespace :seon.ns/name ?namespace-name]]
                         @connection
                         'my.agents.replacement))))))))
