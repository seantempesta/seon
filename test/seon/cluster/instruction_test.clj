(ns seon.cluster.instruction-test
  "Computed cluster context membership and idempotent entity initialization."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types]
            [datahike.api :as d]
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

(defn- function-branches
  [spec]
  (if (= :function (first spec)) (rest spec) [spec]))

(defn- fixed-input-arity
  [function-branch]
  (let [input (second function-branch)
        children (cond-> (rest input) (map? (second input)) rest)]
    (when (contains? #{:cat :catn} (first input))
      (count children))))

(defn- call-resolution
  [db call]
  (let [function-symbol (:seon.cluster.instruction/symbol call)
        function (d/pull db [:seon.fn/spec]
                         [:seon.fn/sym function-symbol])
        spec (some-> (:seon.fn/spec function) edn/read-string)
        arities (into #{} (keep fixed-input-arity) (function-branches spec))]
    (assoc call
           :seon.cluster.instruction/spec spec
           :seon.cluster.instruction/accepted-arities arities
           :seon.cluster.instruction/resolved?
           (and spec
                (contains? arities
                           (:seon.cluster.instruction/arity call))))))

(defn- cluster-toolkit
  [db cluster-name]
  (set
   (d/q '[:find [?namespace-name ...]
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
      (d/transact
       connection
       [{:seon.cluster.instruction/id :getting-started
         :seon.cluster.instruction/text "Owner revision."}
        {:seon.cluster.instruction/id :reply-grammar
         :seon.cluster.instruction/text "Superseded."}])
      (d/transact connection
                  (#'cluster/instruction-row-changes
                   @connection (instruction/seed-rows)))
      (is (= #{:getting-started}
             (set
              (d/q '[:find [?id ...]
                     :where [_ :seon.cluster.instruction/id ?id]]
                   @connection))))
      (is (= "Owner revision."
             (d/q '[:find ?text .
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
        (d/transact
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
        (is (some? (d/q '[:find ?process .
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
        (let [before (d/pull @connection '[*]
                             [:seon.cluster.agent/id "alice"])]
          (is (nil? (:seon.error/kind
                     (cluster/ensure-entity!
                      connection cluster/boot-process-identity
                      resumed-request))))
          (is (= before
                 (d/pull @connection '[*]
                         [:seon.cluster.agent/id "alice"])))
          (is (nil? (d/q '[:find ?namespace .
                           :in $ ?namespace-name
                           :where
                           [?namespace :seon.ns/name ?namespace-name]]
                         @connection
                         'my.agents.replacement))))))))
