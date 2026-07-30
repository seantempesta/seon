(ns seon.fn-test
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.reader :as tools.reader]
            [clojure.tools.reader.reader-types :as reader-types]
            [datahike.api :as d]
            [seon.cluster.ancestor :as ancestor]
            [seon.fn :as seon.fn]
            [seon.program :as seon.program]
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

(defn- write-program-version!
  [root version]
  (let [file (io/file root "versioned.clj")]
    (.mkdirs (.getParentFile file))
    (spit
     file
     (case version
       :v1
       (str "(ns versioned (:require [clojure.test :refer [deftest is]] "
            "[seon.schema :as schema]))\n"
            "(defn ^{:malli/schema [:=> [:cat] :int]} "
            "target \"v1 doc\" [] 1)\n"
            "(defn anchor [] :v1)\n"
            "(schema/register! ::value :int)\n"
            "(deftest target-test (is (= 1 1)))")

       :v2
       (str "(ns versioned (:require [clojure.test :refer [deftest is]] "
            "[seon.schema :as schema]))\n"
            "(defn ^{:malli/schema [:=> [:cat] :int]} target [] 2)\n"
            "(defn anchor [] :v2)\n"
            "(schema/register! ::value :string)\n"
            "(deftest target-test (is (= 2 2)))")

       :v3
       (str "(ns versioned)\n"
            "(defn anchor [] :v3)")))
    root))

(deftest index-rows-admit-only-the-canonical-program
  (let [root (write-program! (str "tmp/fn-test/" (random-uuid)))]
    (let [rows (seon.fn/rows {:seon.fn/roots [root]})]
      (is (= #{"sample/contracted"}
             (into #{} (keep :seon.fn/sym) rows)))
      (is (= #{"sample/contracted-test"}
             (into #{} (keep :seon.test/sym) rows)))
      (is (contains? (into #{} (keep :seon.schema/key) rows)
                     :sample/amount))
      (is (= #{'sample}
             (into #{} (keep :seon.ns/name) rows))))))

(deftest index-expands-refer-all-and-preserves-renamed-test-identity
  (let [root (str "tmp/fn-test/" (random-uuid))
        file (io/file root "exact_bindings.clj")]
    (.mkdirs (.getParentFile file))
    (spit file
          (str "(ns exact.bindings "
               "(:require [clojure.test :refer [deftest] "
               ":rename {deftest dt}] "
               "[clojure.set :refer :all]))\n"
               "(dt renamed-test :ok)\n"
               "(defn united [a b] (union a b))"))
    (let [rows (seon.fn/rows {:seon.fn/roots [root]})
          namespace-row (some #(when (:seon.ns/name %) %) rows)]
      (is (= #{"exact.bindings/renamed-test"}
             (into #{} (keep :seon.test/sym) rows)))
      (is (contains? (:seon.ns/requires namespace-row) 'clojure.set))
      (is (contains? (:seon.ns/refers namespace-row)
                     {:seon.ns.refer/local 'dt
                      :seon.ns.refer/target-ns 'clojure.test
                      :seon.ns.refer/target-name 'deftest}))
      (is (contains? (:seon.ns/refers namespace-row)
                     {:seon.ns.refer/local 'union
                      :seon.ns.refer/target-ns 'clojure.set
                      :seon.ns.refer/target-name 'union})))))

(defn- source-files
  [roots]
  (->> roots
       (mapcat (fn [root] (file-seq (io/file root))))
       (filter (fn [file]
                 (and (.isFile ^java.io.File file)
                      (let [file-name (.getName ^java.io.File file)]
                        (or (str/ends-with? file-name ".clj")
                            (str/ends-with? file-name ".cljc"))))))))

(defn- core-operation?
  [form operation]
  (and (seq? form)
       (contains? #{operation (symbol "clojure.core" (name operation))}
                  (first form))))

(defn- namespace-bindings
  [form]
  (reduce
   (fn [bindings spec]
     (if (and (vector? spec) (symbol? (first spec)))
       (let [target (first spec)
             options (apply hash-map (rest spec))
             alias (or (:as options) (:as-alias options))
             renames (or (:rename options) {})
             refers (if (vector? (:refer options)) (:refer options) [])]
         (cond-> bindings
           (symbol? alias) (assoc-in [:aliases alias] target)
           (seq refers)
           (update :refers
                   into
                   (map (fn [target-name]
                          [(get renames target-name target-name)
                           (symbol (str target) (str target-name))])
                        refers))))
       bindings))
   {:aliases {} :refers {}}
   (into []
         (comp
          (filter #(and (seq? %) (= :require (first %))))
          (mapcat rest))
         (drop 2 form))))

(defn- independently-resolved-operation
  [operation {:keys [aliases refers]}]
  (when (symbol? operation)
    (if-let [operation-namespace (namespace operation)]
      (if-let [target (get aliases (symbol operation-namespace))]
        (symbol (str target) (name operation))
        operation)
      (or (get refers operation)
          (symbol "clojure.core" (name operation))))))

(defn- independent-schema-value
  [form]
  (cond
    (and (seq? form) (= 'quote (first form)) (= 2 (count form)))
    (second form)

    (vector? form) (mapv independent-schema-value form)
    (map? form) (into (empty form)
                      (map (fn [[k v]] [(independent-schema-value k)
                                        (independent-schema-value v)]))
                      form)
    (set? form) (into #{} (map independent-schema-value) form)
    :else form))

(defn- independent-declaration
  [file namespace-name bindings form]
  (when (seq? form)
    (let [operation (independently-resolved-operation (first form) bindings)
          declared-name (second form)
          base {:file (.getCanonicalPath ^java.io.File file)
                :line (:line (meta form))}]
      (cond
        (and (contains? #{'clojure.core/defn 'clojure.core/defn-} operation)
             namespace-name
             (symbol? declared-name))
        (assoc base :family :seon.fn/sym
               :identity (str (symbol (str namespace-name)
                                      (str declared-name))))

        (and (= 'clojure.test/deftest operation)
             namespace-name
             (symbol? declared-name))
        (assoc base :family :seon.test/sym
               :identity (str (symbol (str namespace-name)
                                      (str declared-name))))

        (and (= 'seon.schema/register! operation)
             (= 3 (count form))
             (qualified-keyword? declared-name))
        (assoc base :family :seon.schema/key
               :identity [declared-name
                          (pr-str (independent-schema-value (nth form 2)))])

        :else nil))))

(defn- independently-scan-form
  [file namespace-name bindings form]
  (cond
    (core-operation? form 'ns)
    [(second form) (namespace-bindings form) []]

    (core-operation? form 'in-ns)
    [(when (and (seq? (second form))
                (= 'quote (first (second form))))
       (second (second form)))
     bindings
     []]

    (and (seq? form) (= 'do (first form)))
    (reduce
     (fn [[current current-bindings declarations] child]
       (let [[next-current next-bindings found]
             (independently-scan-form
              file current current-bindings child)]
         [next-current next-bindings (into declarations found)]))
     [namespace-name bindings []]
     (rest form))

    :else
    (let [declaration
          (independent-declaration file namespace-name bindings form)]
      [namespace-name bindings (cond-> [] declaration (conj declaration))])))

(defn- independent-declarations
  [file]
  (let [source-reader
        (reader-types/indexing-push-back-reader (slurp file))
        eof (Object.)
        created (atom #{})
        namespace-object
        (fn [namespace-name]
          (or (find-ns (or namespace-name 'user))
              (let [namespace-name (or namespace-name 'user)]
                (swap! created conj namespace-name)
                (create-ns namespace-name))))]
    (try
      (loop [namespace-name nil
             bindings {:aliases {} :refers {}}
             declarations []]
        (let [form (binding [*ns* (namespace-object namespace-name)
                             tools.reader/*alias-map* (:aliases bindings)]
                     (tools.reader/read {:eof eof
                                         :read-cond :allow
                                         :features #{:clj}}
                                        source-reader))]
          (if (identical? eof form)
            declarations
            (let [[next-namespace next-bindings found]
                  (independently-scan-form
                   file namespace-name bindings form)]
              (recur next-namespace next-bindings
                     (into declarations found))))))
      (finally
        (doseq [namespace-name @created]
          (remove-ns namespace-name))))))

(deftest every-declaration-in-the-tree-becomes-one-family-row
  ;; The independent census finds direct function, schema, and test identities
  ;; without using the production reader's lifted facts as its oracle. The
  ;; program graph is evaluated final state, so repeated definitions of one
  ;; identity intentionally collapse to one row; evaluated and macro-generated
  ;; identities may add rows the direct source census cannot see. The invariant
  ;; is exact no-drop inclusion of every independently read identity. A hand
  ;; list once erased functions below an ordinary call; a missing test signal
  ;; later made the same silence legal.
  (let [files (source-files seon.fn/source-roots)
        declared (into [] (mapcat independent-declarations) files)
        rows (seon.fn/rows {:seon.fn/roots seon.fn/source-roots})
        expected-identities
        (into #{}
              (keep (fn [{:keys [family identity]}]
                      (when identity [family identity])))
              declared)
        actual-identities
        (into #{}
              (keep (fn [row]
                      (cond
                        (:seon.fn/sym row)
                        [:seon.fn/sym (:seon.fn/sym row)]

                        (:seon.schema/key row)
                        [:seon.schema/key [(:seon.schema/key row)
                                           (:seon.schema/form row)]]

                        (:seon.test/sym row)
                        [:seon.test/sym (:seon.test/sym row)])))
              rows)]
    (is (seq files))
    (is (every? (comp some? :line) declared))
    (is (set/subset? expected-identities actual-identities)
        (str "every independently read declaration is present in the evaluated census: "
             {:missing (set/difference expected-identities
                                       actual-identities)}))
    (testing "private helpers are rows, marked private rather than dropped"
      (let [private (filter :seon.fn/private? rows)]
        (is (< 100 (count private)))
        (is (some (fn [row]
                    (and (= "seon.fn/source-file?" (:seon.fn/sym row))
                         (not (contains? row :seon.fn/spec))))
                  private))))))

(deftest isolated-build-evaluation-is-an-exact-repl
  (let [root (str "tmp/fn-test/" (random-uuid))
        file (io/file root "evaluated_test.clj")]
    (.mkdirs (.getParentFile file))
    (spit file
          (str "(ns audit.evaluated)\n"
               "(eval '(alias 'schema 'seon.schema))\n"
               "(schema/register! ::computed (vector :int))\n"
               "(apply alias ['test 'clojure.test])\n"
               "(ns-unmap *ns* 'String)\n"
               "(eval '(in-ns 'audit.changed))\n"
               "(clojure.core/refer 'clojure.core)\n"
               "(seon.schema/register! ::changed :string)\n"
               "(defn ^{:malli/schema [:=> [:cat] fn?]} direct [] :direct)\n"
               "(eval '(defn ^{:malli/schema [:=> [:cat] string?]} "
               "indirect [] :indirect))\n"
               "(eval '(clojure.test/deftest indirect-test "
               "(clojure.test/is true)))\n"
               "(create-ns 'audit.existing)\n"
               "(do (in-ns 'audit.third) "
               "(clojure.core/refer 'clojure.core) "
               "(eval '(defn ^{:malli/schema [:=> [:cat] int?]} "
               "nested [] 1)) "
               "(in-ns 'audit.changed))\n"
               "(do (in-ns 'audit.existing) "
               "(clojure.core/refer 'clojure.core) "
               "(eval '(defn ^{:malli/schema [:=> [:cat] int?]} "
               "from-existing [] 2)) "
               "(in-ns 'audit.changed))\n"))
    (let [rows (seon.fn/rows {:seon.fn/roots [root]})
          by-identity (into {} (map (juxt seon.program/row-identity identity)) rows)
          original-ns (get by-identity [:seon.ns/name 'audit.evaluated])]
      (is (= "[:int]"
             (:seon.schema/form
              (get by-identity [:seon.schema/key :audit.evaluated/computed]))))
      (is (= ":string"
             (:seon.schema/form
              (get by-identity [:seon.schema/key :audit.changed/changed]))))
      (is (contains? by-identity [:seon.fn/sym "audit.changed/direct"]))
      (is (contains? by-identity [:seon.fn/sym "audit.changed/indirect"]))
      (is (contains? by-identity [:seon.fn/sym "audit.third/nested"]))
      (is (contains? by-identity
                     [:seon.fn/sym "audit.existing/from-existing"]))
      (is (contains? by-identity
                     [:seon.test/sym "audit.changed/indirect-test"]))
      (is (= "[:=> [:cat] [:fn clojure.core/fn?]]"
             (:seon.fn/spec
              (get by-identity [:seon.fn/sym "audit.changed/direct"]))))
      (is (= "[:=> [:cat] [:fn clojure.core/string?]]"
             (:seon.fn/spec
              (get by-identity [:seon.fn/sym "audit.changed/indirect"]))))
      (is (= #{'clojure.test 'seon.schema}
             (:seon.ns/requires original-ns)))
      (is (= #{{:seon.ns.alias/local 'schema
                :seon.ns.alias/target-ns 'seon.schema}
               {:seon.ns.alias/local 'test
                :seon.ns.alias/target-ns 'clojure.test}}
             (:seon.ns/aliases original-ns)))
      (is (contains? (:seon.ns/imports original-ns)
                     {:seon.ns.import/local 'String}))
      (is (= 'audit.changed
             (:seon.ns/name
              (get by-identity [:seon.ns/name 'audit.changed]))))
      (is (= 'audit.third
             (:seon.ns/name
              (get by-identity [:seon.ns/name 'audit.third])))))))

(deftest inspection-cache-key-includes-every-local-classpath-input
  (let [root (.getCanonicalFile
              (io/file (str "tmp/fn-test/" (random-uuid))))
        source-root (io/file root "program")
        source (io/file source-root "source.clj")
        resources (io/file root "resources")
        schema (io/file resources "schema/example.edn")
        vendored-source (io/file root "reference-code/example/src")
        dependency (io/file vendored-source "example/dependency.clj")
        manifest (io/file root "deps.edn")
        request {:seon.fn/roots [(.getPath source-root)]}
        digest (fn [] (#'seon.fn/content-digest request))]
    (doseq [file [source schema dependency manifest]]
      (.mkdirs (.getParentFile file)))
    (spit source "(ns cache.source)\n(defn value [] 1)\n")
    (spit schema "{:cache/value :int}\n")
    (spit dependency "(ns example.dependency)\n(defn value [] 1)\n")
    (spit manifest "{:paths [\"program\" \"resources\"]}\n")
    (with-redefs-fn
      {#'seon.fn/project-root (constantly root)
       #'seon.fn/resolved-index-classpath
       (constantly [resources vendored-source])}
      (fn []
        (let [initial (digest)]
          (spit source "(ns cache.source)\n(defn value [] 2)\n")
          (let [source-changed (digest)]
            (is (not= initial source-changed))
            (spit schema "{:cache/value :string}\n")
            (let [resource-changed (digest)]
              (is (not= source-changed resource-changed))
              (spit dependency
                    "(ns example.dependency)\n(defn value [] 2)\n")
              (let [local-dependency-changed (digest)]
                (is (not= resource-changed local-dependency-changed))
                (spit manifest
                      "{:paths [\"program\" \"resources\" \"extra\"]}\n")
                (is (not= local-dependency-changed (digest)))))))))))

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

(deftest indexing-redefines-removes-and-converges-every-declaration-family
  (let [root (str "tmp/fn-test/" (random-uuid))
        authored-ns 'my.agents.registration-owner]
    (write-program-version! root :v1)
    (test-support/with-database
      (fn [connection]
        (d/transact connection
                    [{:seon.db.process/id "seon.db.process/agent"}])
        (d/transact
         connection
         {:tx-data
          [{:seon.ns/name authored-ns
            :seon.ns/source "(ns my.agents.registration-owner)"}
           {:seon.fn/sym "my.agents.registration-owner/survives"
            :seon.fn/ns [:seon.ns/name authored-ns]
            :seon.fn/source "(defn survives [] 42)"
            :seon.fn/arglists "([])"
            :seon.fn/private? false
            :seon.fn/spec "[:=> [:cat] :int]"}
           {:seon.schema/key :my.agents.registration-owner/value
            :seon.schema/form ":int"}
           {:seon.test/sym "my.agents.registration-owner/survives-test"
            :seon.test/ns [:seon.ns/name authored-ns]
            :seon.test/source "(deftest survives-test)"}]
          :tx-meta {:seon.db/process agent-process}})
        (let [request {:seon.store/branch-connection connection
                       :seon.db/process boot-process
                       :seon.fn/roots [root]}]
          (testing "V1 installs one current row for each source declaration"
            (is (pos? (:seon.reconcile/operations (seon.fn/index! request))))
            (is (= "v1 doc"
                   (:seon.fn/doc
                    (d/pull @connection '[*]
                            [:seon.fn/sym "versioned/target"]))))
            (is (= ":int"
                   (:seon.schema/form
                    (d/pull @connection '[*]
                            [:seon.schema/key :versioned/value]))))
            (is (= "(deftest target-test (is (= 1 1)))"
                   (:seon.test/source
                    (d/pull @connection '[*]
                            [:seon.test/sym "versioned/target-test"])))))

          (write-program-version! root :v2)
          (testing "V2 exactly replaces source and retracts omitted optionals"
            (is (pos? (:seon.reconcile/operations (seon.fn/index! request))))
            (let [function
                  (d/pull @connection '[*]
                          [:seon.fn/sym "versioned/target"])]
              (is (= "(defn ^{:malli/schema [:=> [:cat] :int]} target [] 2)"
                     (:seon.fn/source function)))
              (is (not (contains? function :seon.fn/doc))))
            (is (= ":string"
                   (:seon.schema/form
                    (d/pull @connection '[*]
                            [:seon.schema/key :versioned/value]))))
            (is (= "(deftest target-test (is (= 2 2)))"
                   (:seon.test/source
                    (d/pull @connection '[*]
                            [:seon.test/sym "versioned/target-test"])))))

          (write-program-version! root :v3)
          (testing "V3 removes every absent source-owned declaration family"
            (is (pos? (:seon.reconcile/operations (seon.fn/index! request))))
            (is (nil? (d/pull @connection [:db/id]
                              [:seon.fn/sym "versioned/target"])))
            (is (nil? (d/pull @connection [:db/id]
                              [:seon.schema/key :versioned/value])))
            (is (nil? (d/pull @connection [:db/id]
                              [:seon.test/sym "versioned/target-test"]))))

          (testing "source reconciliation never owns agent-authored rows"
            (is (some? (d/pull @connection [:db/id]
                               [:seon.fn/sym
                                "my.agents.registration-owner/survives"])))
            (is (some? (d/pull @connection [:db/id]
                               [:seon.schema/key
                                :my.agents.registration-owner/value])))
            (is (some? (d/pull @connection [:db/id]
                               [:seon.test/sym
                                "my.agents.registration-owner/survives-test"]))))

          (testing "the converged V3 writes no transaction"
            (let [before (:max-tx @connection)]
              (is (= {:seon.reconcile/converged? true
                      :seon.reconcile/operations 0}
                     (seon.fn/index! request)))
              (is (= before (:max-tx @connection))))))))))
