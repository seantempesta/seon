(ns seon.fn-test
  (:require [clojure.java.io :as io]
            [clojure.set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cluster.ancestor :as ancestor]
            [seon.fn :as seon.fn]
            [seon.sci.reader :as reader]
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

(defn- source-files
  [roots]
  (->> roots
       (mapcat (fn [root] (file-seq (io/file root))))
       (filter (fn [file]
                 (and (.isFile ^java.io.File file)
                      (let [file-name (.getName ^java.io.File file)]
                        (or (str/ends-with? file-name ".clj")
                            (str/ends-with? file-name ".cljc"))))))))

(defn- read-events
  [file]
  (reader/read {:seon.sci.reader/text (slurp file)
                :seon.sci.reader/features #{:clj}
                :seon.sci.reader/tags {'inst identity 'uuid identity}}))

(defn- declared-functions
  "Top-level `defn`/`defn-` names in `events`, counted from the forms.

  Read from the form itself rather than from the reader's lifted facts, so
  this count cannot agree with the rows by sharing their bug."
  [events]
  (into #{}
        (keep (fn [event]
                (let [form (:seon.sci.reader/form event)]
                  (when (and (seq? form)
                             (symbol? (first form))
                             (contains? #{"defn" "defn-"}
                                        (name (first form)))
                             (symbol? (second form)))
                    (second form)))))
        events))

(deftest every-declared-function-in-the-tree-becomes-one-row
  ;; The invariant, per source file: one `:seon.fn` row for every top-level
  ;; `defn`/`defn-` form. Not a count a partial graph could still satisfy,
  ;; and not gated on a contract: call-graph reachability runs through
  ;; private helpers. A hand list of namespace-stable operations once
  ;; erased every declaration below the first ordinary top-level call.
  (let [files (source-files seon.fn/source-roots)
        rows (seon.fn/rows {:seon.fn/roots seon.fn/source-roots})
        rows-by-namespace
        (reduce
         (fn [index row]
           (if-some [sym (:seon.fn/sym row)]
             (update index
                     (symbol (namespace (symbol sym)))
                     (fnil conj #{})
                     (symbol (name (symbol sym))))
             index))
         {}
         rows)]
    (is (seq files))
    (doseq [file files
            :let [events (read-events file)]]
      (is (vector? events)
          (str "reader refused " (.getPath ^java.io.File file)))
      (when (vector? events)
        (let [declared (declared-functions events)
              namespace-name (some :seon.ns/name events)]
          (when (seq declared)
            (is (some? namespace-name)
                (str "no namespace declaration in "
                     (.getPath ^java.io.File file)))
            (is (= declared
                   (clojure.set/intersection
                    declared
                    (get rows-by-namespace namespace-name #{})))
                (str "unadmitted declarations in "
                     (.getPath ^java.io.File file)))))))
    (testing "private helpers are rows, marked private rather than dropped"
      (let [private (filter :seon.fn/private? rows)]
        (is (< 100 (count private)))
        (is (some (fn [row]
                    (and (= "seon.fn/source-file?" (:seon.fn/sym row))
                         (not (contains? row :seon.fn/spec))))
                  private))))))

(deftest unplaceable-declaration-is-refused-loudly
  ;; A file that contributes no rows must say so with the reason, never
  ;; vanish. Silence read as health is this project's recurring failure.
  (let [root (str "tmp/fn-test/" (random-uuid))
        file (io/file root "opaque.clj")]
    (.mkdirs (.getParentFile file))
    (spit file
          (str "(ns opaque)\n"
               "(do (in-ns 'elsewhere))\n"
               "(defn f [n] n)\n"))
    (let [failure (try
                    (seon.fn/rows {:seon.fn/roots [root]})
                    (catch clojure.lang.ExceptionInfo error error))
          data (ex-data failure)]
      (is (instance? clojure.lang.ExceptionInfo failure))
      (is (= :seon.fn/index-refused (:seon.error/kind data)))
      (is (str/ends-with? (:seon.fn/file data) "opaque.clj"))
      (is (= [{:seon.fn/line 3
               :seon.fn/source "(defn f [n] n)"
               :seon.fn/reason :seon.fn/namespace-unproven}]
             (:seon.fn/unadmitted data))))))

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
