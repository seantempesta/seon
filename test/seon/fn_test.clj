(ns seon.fn-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.fn :as seon.fn]
            [seon.fn.analyzer :as analyzer]
            [seon.program :as program]
            [seon.test-support :as test-support]))

(def ^:private boot-process
  [:seon.db.process/id "seon.db.process/boot"])

(defn- fixture-root []
  (let [root (io/file "tmp" "fn-test" (str (random-uuid)))]
    (.mkdirs root)
    root))

(defn- write-source! [root relative-path source]
  (let [file (io/file root relative-path)]
    (.mkdirs (.getParentFile file))
    (spit file source)
    file))

(defn- capability-fixture!
  [root capability-source]
  (write-source!
   root "seon/effect.clj"
   (str "(ns seon.effect)\n"
        "(defn request! [owner request] [owner request])\n"))
  (write-source! root "sample/capability.clj" capability-source)
  root)

(defn- capability-refusal
  [capability-source]
  (let [root (capability-fixture! (fixture-root) capability-source)]
    (try
      (seon.fn/build-manifest {:seon.fn/roots [(.getPath root)]})
      nil
      (catch clojure.lang.ExceptionInfo error error))))

(deftest capability-metadata-is-one-program-graph-contract
  (let [root
        (capability-fixture!
         (fixture-root)
         (str "(ns sample.capability\n"
              "  (:require [seon.effect :as effect]))\n"
              "(defn- handler\n"
              "  {:malli/schema [:=> [:cat :map :map] :map]}\n"
              "  [request effective] (assoc request :effective effective))\n"
              "(defn leaf\n"
              "  {:malli/schema [:=> [:cat :map] :map]\n"
              "   :seon.workload :io\n"
              "   :seon.effect/capability sample.capability/handler}\n"
              "  [request] (effect/request! #'leaf request))\n"
              "(defn pure-caller [request] (leaf request))\n"
              "(defn blocking-helper {:seon.workload :io} [request] request)\n"
              "(defn compute-leaf {:seon.workload :compute} [request] request)\n"
              "(defn mixed-caller [request]\n"
              "  [(compute-leaf request) (leaf request)])\n"))
        rows (seon.fn/rows {:seon.fn/roots [(.getPath root)]})
        by-symbol (into {} (keep (fn [row]
                                  (when-let [sym (:seon.fn/sym row)]
                                    [sym row]))) rows)
        leaf (get by-symbol "sample.capability/leaf")]
    (testing "the owner row carries handler, schema, and workload together"
      (is (= "sample.capability/leaf" (:seon.fn/sym leaf)))
      (is (= 'sample.capability/handler
             (:seon.effect/capability leaf)))
      (is (string? (:seon.fn/spec leaf)))
      (is (= :io (:seon.fn/workload leaf))))
    (testing "call edges alone reveal the capability owner"
      (is (= [[:seon.fn/sym "sample.capability/leaf"]]
             (:seon.fn/calls (get by-symbol "sample.capability/pure-caller"))))
      (is (= [[:seon.fn/sym "sample.capability/compute-leaf"]
              [:seon.fn/sym "sample.capability/leaf"]]
             (:seon.fn/calls (get by-symbol "sample.capability/mixed-caller")))))
    (testing "a pure blocking helper remains capability-free"
      (is (= :io
             (:seon.fn/workload
              (get by-symbol "sample.capability/blocking-helper"))))
      (is (nil? (:seon.effect/capability
                 (get by-symbol "sample.capability/blocking-helper")))))))

(deftest quoted-private-handler-symbol-is-indexed-as-the-runtime-symbol
  (let [root
        (capability-fixture!
         (fixture-root)
         (str "(ns sample.capability\n"
              "  (:require [seon.effect :as effect]))\n"
              "(defn- handler\n"
              "  {:malli/schema [:=> [:cat :map :map] :map]}\n"
              "  [request effective] (assoc request :effective effective))\n"
              "(defn leaf\n"
              "  {:malli/schema [:=> [:cat :map] :map]\n"
              "   :seon.workload :io\n"
              "   :seon.effect/capability 'sample.capability/handler}\n"
              "  [request] (effect/request! #'leaf request))\n"))
        rows (seon.fn/rows {:seon.fn/roots [(.getPath root)]})
        leaf (first (filter #(= "sample.capability/leaf"
                                (:seon.fn/sym %))
                            rows))]
    (is (= 'sample.capability/handler
           (:seon.effect/capability leaf)))))

(deftest capability-indexing-refuses-every-malformed-declaration
  (let [base
        (fn [handler owner]
          (str "(ns sample.capability\n"
               "  (:require [seon.effect :as effect]))\n"
               handler "\n" owner "\n"))
        handler
        (str "(defn- handler\n"
             "  {:malli/schema [:=> [:cat :map :map] :map]}\n"
             "  [request effective] (assoc request :effective effective))")
        owner
        (fn [metadata body]
          (str "(defn leaf\n  " metadata "\n"
               "  [request] " body ")"))
        refusal-rule
        (fn [source]
          (:seon.fn/capability-rule
           (some-> source capability-refusal ex-data)))]
    (is (= :marker-without-workload
           (refusal-rule
            (base handler
                  (owner
                   "{:malli/schema [:=> [:cat :map] :map]\n   :seon.effect/capability sample.capability/handler}"
                   "(effect/request! #'leaf request)")))))
    (is (= :capability-workload-not-io
           (refusal-rule
            (base handler
                  (owner
                   "{:malli/schema [:=> [:cat :map] :map]\n   :seon.workload :compute\n   :seon.effect/capability sample.capability/handler}"
                   "(effect/request! #'leaf request)")))))
    (is (= :missing-handler
           (refusal-rule
            (base handler
                  (owner
                   "{:malli/schema [:=> [:cat :map] :map]\n   :seon.workload :io\n   :seon.effect/capability sample.capability/missing}"
                   "(effect/request! #'leaf request)")))))
    (is (= :public-handler
           (refusal-rule
            (base
             (str "(defn handler\n"
                  "  {:malli/schema [:=> [:cat :map :map] :map]}\n"
                  "  [request effective] (assoc request :effective effective))")
             (owner
              "{:malli/schema [:=> [:cat :map] :map]\n   :seon.workload :io\n   :seon.effect/capability sample.capability/handler}"
              "(effect/request! #'leaf request)")))))
    (is (= :unschemaed-handler
           (refusal-rule
            (base
             "(defn- handler [request effective] (assoc request :effective effective))"
             (owner
              "{:malli/schema [:=> [:cat :map] :map]\n   :seon.workload :io\n   :seon.effect/capability sample.capability/handler}"
              "(effect/request! #'leaf request)")))))
    (is (= :capability-handler
           (refusal-rule
            (base
             (str "(defn- handler\n"
                  "  {:malli/schema [:=> [:cat :map :map] :map]\n"
                  "   :seon.workload :io\n"
                  "   :seon.effect/capability sample.capability/handler}\n"
                  "  [request effective] (assoc request :effective effective))")
             (owner
              "{:malli/schema [:=> [:cat :map] :map]\n   :seon.workload :io\n   :seon.effect/capability sample.capability/handler}"
              "(effect/request! #'leaf request)")))))
    (is (= :unmarked-request
           (refusal-rule
            (base handler
                  "(defn leaf [request] (effect/request! #'leaf request))"))))
    (is (= :capability-without-request
           (refusal-rule
            (base handler
                  (owner
                   "{:malli/schema [:=> [:cat :map] :map]\n   :seon.workload :io\n   :seon.effect/capability sample.capability/handler}"
                   "request")))))))

(deftest static-index-preserves-the-jvm-program-row-contract
  (let [root (fixture-root)
        source
        (str "(ns sample.core\n"
             "  (:require [clojure.test :refer [deftest]]\n"
             "            [clojure.test.check.clojure-test :refer [defspec]]\n"
             "            [clojure.string :as str])\n"
             "  (:import [java.util Date]))\n"
             "(defn ^:private helper [x] (str/trim x))\n"
             "(defn ^{:malli/schema [:=> [:cat fn?] string?]\n"
             "         :seon.workload :compute}\n"
             "  contracted \"Exact doc.\" [f] (helper (f)))\n"
             "(defrecord Pair [left right])\n"
             "(deftype Cell [value])\n"
             "(deftest example-test (contracted identity))\n"
             "(defspec generated-test 10 true)\n"
             "(throw (ex-info \"top-level source must never run\" {}))\n")]
    (write-source! root "sample/core.clj" source)
    (let [rows (seon.fn/rows {:seon.fn/roots [(.getPath root)]})
          by-id (into {} (map (juxt program/row-identity identity)) rows)
          namespace-row (get by-id [:seon.ns/name 'sample.core])]
      (testing "top-level source is analyzed and never evaluated"
        (is (contains? by-id [:seon.fn/sym "sample.core/contracted"])))
      (testing "functions, tests, records, and types keep JVM parity"
        (is (= #{"sample.core/helper" "sample.core/contracted"
                 "sample.core/->Pair" "sample.core/map->Pair"
                 "sample.core/->Cell"}
               (into #{} (keep :seon.fn/sym) rows)))
        (is (= #{"sample.core/example-test" "sample.core/generated-test"}
               (into #{} (keep :seon.test/sym) rows)))
        (is (true? (:seon.fn/private?
                    (get by-id [:seon.fn/sym "sample.core/helper"]))))
        (is (= "([f])" (:seon.fn/arglists
                         (get by-id [:seon.fn/sym "sample.core/contracted"]))))
        (is (= "[:=> [:cat clojure.core/fn?] clojure.core/string?]"
               (:seon.fn/spec
                (get by-id [:seon.fn/sym "sample.core/contracted"]))))
        (is (= :compute (:seon.fn/workload
                         (get by-id [:seon.fn/sym "sample.core/contracted"]))))
        (is (= [[:seon.fn/sym "sample.core/helper"]]
               (:seon.fn/calls
                (get by-id [:seon.fn/sym "sample.core/contracted"]))))
        (is (= [[:seon.fn/sym "sample.core/contracted"]]
               (:seon.fn/calls
                (get by-id [:seon.test/sym "sample.core/example-test"]))))
        (is (nil? (:seon.fn/calls
                   (get by-id [:seon.fn/sym "sample.core/helper"])))
            "dependency and unresolved targets never become function refs")
        (is (= "(defrecord Pair [left right])"
               (:seon.fn/source
                (get by-id [:seon.fn/sym "sample.core/map->Pair"]))))
      (testing "namespace context is exact source data"
        (is (= #{[:seon.ns/name 'clojure.test]
                 [:seon.ns/name 'clojure.test.check.clojure-test]
                 [:seon.ns/name 'clojure.string]}
               (:seon.ns/requires namespace-row)))
        (is (contains? (:seon.ns/aliases namespace-row)
                       {:seon.ns.alias/local 'str
                        :seon.ns.alias/target-ns 'clojure.string}))
        (is (contains? (:seon.ns/refers namespace-row)
                       {:seon.ns.refer/local 'deftest
                        :seon.ns.refer/target-ns 'clojure.test
                        :seon.ns.refer/target-name 'deftest}))
        (is (contains? (:seon.ns/imports namespace-row)
                        {:seon.ns.import/local 'Date
                        :seon.ns.import/target-class 'java.util.Date})))))))

(deftest publication-is-first-party-only
  (let [root (fixture-root)]
    (write-source! root "first/party.clj"
                   "(ns first.party (:require [clojure.string :as str]))\n(defn trim [x] (str/trim x))")
    (let [rows (seon.fn/rows {:seon.fn/roots [(.getPath root)]})]
      (is (= #{'first.party} (into #{} (keep :seon.ns/name) rows)))
      (is (= #{"first.party/trim"} (into #{} (keep :seon.fn/sym) rows)))
      (is (not-any? #(= "clojure.string/trim" (:seon.fn/sym %)) rows))))
  (is (= ["src" "test"] seon.fn/source-roots)))

(deftest requires-resolve-totally
  (test-support/with-database
    (fn [connection]
      (let [db @connection
            requires
            (db/q '[:find ?namespace ?required ?required-name
                   :where
                   [?namespace :seon.ns/requires ?required]
                   [?required :seon.ns/name ?required-name]]
                 db)
            required-eids
            (into #{} (map second) requires)
            name-only-eids
            (db/q '[:find [?namespace ...]
                   :where
                   [?namespace :seon.ns/name]
                   (not [?namespace :seon.ns/source])]
                 db)
            name-only-eids (set name-only-eids)
            external-eids-by-name
            (reduce
             (fn [by-name [_ required required-name]]
               (cond-> by-name
                 (contains? name-only-eids required)
                 (update required-name (fnil conj #{}) required)))
             {}
             requires)]
        (is (seq requires))
        (is (every? (fn [[_ required required-name]]
                      (and (integer? required)
                           (symbol? required-name)))
                    requires))
        (is (some name-only-eids required-eids)
            "external requires are shared name-only namespace rows")
        (is (every? #(= 1 (count %)) (vals external-eids-by-name))
            "each external namespace name resolves to exactly one eid")
        (is (empty?
             (db/q '[:find ?namespace ?required
                    :where
                    [?namespace :seon.ns/requires ?required]
                    (not [?required :seon.ns/name])]
                  db)))))))

(deftest contracted-program-rows-carry-queryable-facts-in-their-spec-transaction
  (test-support/with-database
    (fn [connection]
      (let [db @connection
            contracted
            (db/q '[:find [?function ...]
                   :where [?function :seon.fn/spec]]
                 db)
            complete
            (db/q '[:find [?function ...]
                   :where
                   [?function :seon.fn/spec]
                   [?function :seon.fn/arities]
                   [?function :seon.fn/ast]]
                 db)
            assertion-transactions
            (db/q '[:find ?function ?spec-tx ?arities-tx ?ast-tx
                   :where
                   [?function :seon.fn/spec _ ?spec-tx]
                   [?function :seon.fn/arities _ ?arities-tx]
                   [?function :seon.fn/ast _ ?ast-tx]]
                 db)
            functions-by-role
            (db/q '[:find ?role ?function-symbol
                   :in $ ?schema-key
                   :where
                   [?schema :seon.schema/key ?schema-key]
                   (or-join [?schema ?arity ?role]
                     (and [?arity :seon.fn.arity/input-refs ?schema]
                          [(ground :input) ?role])
                     (and [?arity :seon.fn.arity/output-refs ?schema]
                          [(ground :output) ?role]))
                   [?function :seon.fn/arities ?arity]
                   [?function :seon.fn/sym ?function-symbol]]
                 db :seon.schema/value)]
        (testing "the complete contracted population is backfilled"
          (is (seq contracted))
          (is (= (set contracted) (set complete))))
        (testing "spec and every parsed root assert atomically"
          (is (= (count contracted) (count assertion-transactions)))
          (is (every? (fn [[_ spec-tx arities-tx ast-tx]]
                        (= spec-tx arities-tx ast-tx))
                      assertion-transactions)))
        (testing "one query answers both directions for a given schema"
          (is (seq (filter (comp #{:input} first) functions-by-role)))
          (is (seq (filter (comp #{:output} first) functions-by-role))))))))

(deftest parsed-contract-backfill-is-one-transaction-and-idempotent
  (test-support/with-database
    (fn [connection]
      (let [functions
            (take 2
                  (sort
                   (db/q '[:find [?function ...]
                          :where
                          [?function :seon.fn/spec]
                          [?function :seon.fn/arities]
                          [?function :seon.fn/ast]]
                        @connection)))]
        (is (= 2 (count functions)))
        (db/transact!
         connection
         (into []
               (mapcat (fn [function]
                         [[:db.fn/retractAttribute function :seon.fn/arities]
                          [:db.fn/retractAttribute function :seon.fn/ast]]))
               functions))
        (let [before (:max-tx @connection)
              first-result
              (seon.fn/backfill-contract-facts!
               {:seon.store/branch-connection connection
                :seon.db/process boot-process})
              after-first (:max-tx @connection)
              second-result
              (seon.fn/backfill-contract-facts!
               {:seon.store/branch-connection connection
                :seon.db/process boot-process})
              after-second (:max-tx @connection)]
          (is (= {:seon.reconcile/converged? false
                  :seon.reconcile/operations 2}
                 first-result))
          (is (= (inc before) after-first)
              "all missing graphs commit in one transaction")
          (is (= {:seon.reconcile/converged? true
                  :seon.reconcile/operations 0}
                 second-result))
          (is (= after-first after-second)
              "the converged second run writes nothing")
          (is (empty?
               (db/q '[:find ?function
                      :where
                      [?function :seon.fn/spec]
                      (or (not [?function :seon.fn/arities])
                          (not [?function :seon.fn/ast]))]
                    @connection))))))))

(deftest publication-refuses-every-error-level-analyzer-finding
  (let [root (fixture-root)]
    (write-source! root "audit/unresolved.clj"
                   "(ns audit.unresolved)\n(defn broken [] missing)\n")
    (let [failure
          (try
            (seon.fn/build-manifest {:seon.fn/roots [(.getPath root)]})
            nil
            (catch clojure.lang.ExceptionInfo error error))]
      (is (= :seon.fn/index-refused (:seon.error/kind (ex-data failure))))
      (is (some #(= :unresolved-symbol (::analyzer/type %))
                (::seon.fn/findings (ex-data failure)))))))

(deftest file-artifacts-and-manifests-are-byte-digested-and-deterministic
  (let [root (fixture-root)
        alpha-source
        (str "(ns artifact.alpha)\r\n"
             "(defn target [] 1)\r\n")
        beta-source
        (str "(ns artifact.beta\n"
             "  (:require [artifact.alpha :as alpha]\n"
             "            [clojure.string :as str]))\n"
             "(defn caller [x] (str/trim (str (alpha/target) x)))\n")
        alpha (write-source! root "artifact/alpha.clj" alpha-source)
        beta (write-source! root "artifact/beta.clj" beta-source)
        request {:seon.fn/roots [(.getPath root)]}
        manifest (seon.fn/build-manifest request)
        repeated (seon.fn/build-manifest request)
        artifacts (into {} (map (juxt :seon.fn.file/path identity))
                        (:seon.fn.manifest/artifacts manifest))
        beta-artifact (get artifacts (.getCanonicalPath beta))
        beta-caller
        (first (filter #(= "artifact.beta/caller" (:seon.fn/sym %))
                       (:seon.fn.file/rows beta-artifact)))
        incremental
        (seon.fn/build-artifact
         {:seon.fn.file/path (.getPath beta)
          :seon.fn.file/first-party-functions
          ["artifact.alpha/target"]})]
    (testing "the complete manifest is stable and partitions every file"
      (is (= manifest repeated))
      (is (= #{(.getCanonicalPath alpha) (.getCanonicalPath beta)}
             (set (keys artifacts))))
      (is (re-matches #"[0-9a-f]{64}"
                      (:seon.fn.manifest/digest manifest))))
    (testing "pure manifest helpers find files and derive function context"
      (is (= beta-artifact
             (seon.fn/artifact-by-path manifest (.getCanonicalPath beta))))
      (is (nil? (seon.fn/artifact-by-path manifest "/absent.clj")))
      (is (= ["artifact.alpha/target" "artifact.beta/caller"]
             (seon.fn/manifest-function-symbols manifest))))
    (testing "artifact replacement recomputes one deterministic manifest"
      (let [changed-beta (assoc beta-artifact :seon.fn.file/digest "changed")
            changed (seon.fn/replace-manifest-artifacts manifest [changed-beta])]
        (is (= changed-beta
               (seon.fn/artifact-by-path changed (.getCanonicalPath beta))))
        (is (= (:seon.fn.manifest/roots manifest)
               (:seon.fn.manifest/roots changed)))
        (is (= (:seon.fn.manifest/identities manifest)
               (:seon.fn.manifest/identities changed)))
        (is (not= (:seon.fn.manifest/digest manifest)
                  (:seon.fn.manifest/digest changed)))
        (is (= (sort (map :seon.fn.file/path
                          (:seon.fn.manifest/artifacts changed)))
               (map :seon.fn.file/path
                    (:seon.fn.manifest/artifacts changed))))))
    (testing "one-file analysis retains only calls to known first-party rows"
      (is (= [[:seon.fn/sym "artifact.alpha/target"]]
             (:seon.fn/calls beta-caller)))
      (is (= beta-artifact incremental)))
    (testing "the file digest covers exact bytes, including CRLF"
      (is (re-matches #"[0-9a-f]{64}"
                      (:seon.fn.file/digest (get artifacts
                                                 (.getCanonicalPath alpha)))))
      (is (= "(ns artifact.alpha)"
             (:seon.ns/source
              (first (:seon.fn.file/rows
                      (get artifacts (.getCanonicalPath alpha))))))))))

(deftest changed-file-planning-is-conservative-and-explicit
  (let [path "/repo/src/sample.clj"
        namespace-row {:seon.ns/name 'sample
                       :seon.ns/source "(ns sample)"}
        current-row {:seon.fn/sym "sample/value"
                     :seon.fn/ns [:seon.ns/name 'sample]
                     :seon.fn/source "(defn value [] 1)"
                     :seon.fn/arglists "([])"
                     :seon.fn/private? false}
        desired-row (assoc current-row :seon.fn/source "(defn value [] 2)")
        current {:seon.fn.file/path path
                 :seon.fn.file/digest "old"
                 :seon.fn.file/rows [namespace-row current-row]
                 :seon.fn.file/identities
                 [[:seon.ns/name 'sample] [:seon.fn/sym "sample/value"]]}
        desired {:seon.fn.file/path path
                 :seon.fn.file/digest "new"
                 :seon.fn.file/rows [namespace-row desired-row]
                 :seon.fn.file/identities
                 [[:seon.ns/name 'sample] [:seon.fn/sym "sample/value"]]}
        plan #(seon.fn/plan-file-change
               (merge {:seon.fn.change/status :modified
                       :seon.fn.change/current-artifact current
                       :seon.fn.change/desired-artifact desired}
                      %))]
    (testing "same identities with cardinality-one updates are upserts"
      (is (= {:seon.fn.change/action :incremental-upsert
              :seon.fn.change/path path
              :seon.fn.change/digest "new"
              :seon.fn.change/artifact desired
              :seon.fn.change/rows
              [{:seon.fn/sym "sample/value"
                :seon.fn/source "(defn value [] 2)"}]
              :seon.fn.change/identities
              [[:seon.ns/name 'sample] [:seon.fn/sym "sample/value"]]}
             (plan {}))))
    (testing "removed identity and cardinality-many changes rebuild"
      (is (contains?
           (set (:seon.fn.change/reasons
                 (plan {:seon.fn.change/desired-artifact
                        (update desired :seon.fn.file/rows pop)
                        :seon.fn.change/uncertain? true})))
           :uncertain-projection))
      (is (some #{:removed-identity}
                (:seon.fn.change/reasons
                 (plan {:seon.fn.change/desired-artifact
                        (-> desired
                            (update :seon.fn.file/rows pop)
                            (update :seon.fn.file/identities pop))}))))
      (is (some #{:component-or-cardinality-many-change}
                (:seon.fn.change/reasons
                 (plan {:seon.fn.change/desired-artifact
                        (update-in desired [:seon.fn.file/rows 1]
                                   assoc :seon.fn/calls
                                   [[:seon.fn/sym "sample/other"]])})))))
    (testing "any added identity rebuilds because old callers may resolve it"
      (let [added-row (assoc desired-row
                             :seon.fn/sym "sample/new-value"
                             :seon.fn/source "(defn new-value [] 3)")
            result
            (plan {:seon.fn.change/desired-artifact
                   (-> desired
                       (update :seon.fn.file/rows conj added-row)
                       (update :seon.fn.file/identities conj
                               [:seon.fn/sym "sample/new-value"]))})]
        (is (some #{:added-identity} (:seon.fn.change/reasons result)))
        (is (= [[:seon.fn/sym "sample/new-value"]]
               (:seon.fn.change/added-identities result)))))
    (testing "unsafe event and artifact states name their fallback reason"
      (doseq [[request reason]
              [[{:seon.fn.change/status :deleted
                 :seon.fn.change/desired-artifact nil} :deleted]
               [{:seon.fn.change/status :moved} :moved]
               [{:seon.fn.change/status :schema-resource} :schema-resource]
               [{:seon.fn.change/status :analysis-error} :analysis-error]
               [{:seon.fn.change/stale? true} :stale-artifact]
               [{:seon.fn.change/current-artifact nil} :missing-artifact]
               [{:seon.fn.change/uncertain? true} :uncertain-projection]]]
        (is (some #{reason}
                  (:seon.fn.change/reasons (plan request)))
            (str request))))))

(deftest indexing-uses-a-prebuilt-manifest-without-analysis
  (let [manifest
        {:seon.fn.manifest/roots ["/repo/src"]
         :seon.fn.manifest/digest "digest"
         :seon.fn.manifest/artifacts
         [{:seon.fn.file/path "/repo/src/prebuilt.clj"
           :seon.fn.file/digest "file-digest"
           :seon.fn.file/rows
           [{:seon.ns/name 'prebuilt
             :seon.ns/source "(ns prebuilt)"}
            {:seon.fn/sym "prebuilt/value"
             :seon.fn/ns [:seon.ns/name 'prebuilt]
             :seon.fn/source "(defn value [] 1)"
             :seon.fn/arglists "([])"
             :seon.fn/private? false}
            {:seon.test/sym "prebuilt/value-test"
             :seon.test/ns [:seon.ns/name 'prebuilt]
             :seon.test/source "(deftest value-test (value))"
             :seon.fn/calls [[:seon.fn/sym "prebuilt/value"]]
             :seon.test/subject [:seon.fn/sym "prebuilt/value"]}]
           :seon.fn.file/identities
           [[:seon.ns/name 'prebuilt]
            [:seon.fn/sym "prebuilt/value"]
            [:seon.test/sym "prebuilt/value-test"]]}]
         :seon.fn.manifest/identities
         [[:seon.ns/name 'prebuilt]
          [:seon.fn/sym "prebuilt/value"]
          [:seon.test/sym "prebuilt/value-test"]]}
        transactions (atom [])]
    (with-redefs [analyzer/analyze
                  (fn [_]
                    (throw (ex-info "analysis must not run" {})))
                  db/q (fn [& _] nil)
                  db/transact!
                  (fn [_ request]
                    (swap! transactions conj request)
                    {})]
      (let [result (seon.fn/index!
                    {:seon.store/branch-connection (atom :database)
                     :seon.fn/manifest manifest})]
        (is (pos? (:seon.reconcile/operations result)))
        (is (= 4 (count @transactions)))
        (is (= 'prebuilt
               (-> @transactions first :tx-data first :seon.ns/name)))
        (is (some #(= "prebuilt/value" (:seon.fn/sym %))
                  (-> @transactions second :tx-data)))
        (is (= [{:seon.test/sym "prebuilt/value-test"
                 :seon.test/subject [:seon.fn/sym "prebuilt/value"]}]
               (-> @transactions (nth 2) :tx-data)))
        (is (= [{:seon.test/sym "prebuilt/value-test"
                 :seon.fn/calls [[:seon.fn/sym "prebuilt/value"]]}]
               (-> @transactions (nth 3) :tx-data)))))
    (let [attempts (atom 0)
          result
          (with-redefs [db/q (fn [& _] nil)
                        db/transact!
                        (fn [& _]
                          (swap! attempts inc)
                          {:seon.error/kind :seon.db/invalid-transaction})]
            (try
              (seon.fn/index!
               {:seon.store/branch-connection (atom :database)
                :seon.fn/manifest manifest})
              ::committed
              (catch clojure.lang.ExceptionInfo failure
                (ex-data failure))))]
      (is (= :seon.fn/index-refused (:seon.error/kind result)))
      (is (= :seon.fn/namespaces (:seon.fn/index-phase result)))
      (is (= 1 @attempts)
          "a refused identity phase prevents dependent transactions"))))

(deftest keyword-usage-is-indexed-per-declaration
  (let [root (fixture-root)
        source
        (str "(ns sample.keys\n"
             "  (:require [clojure.test :refer [deftest is]]\n"
             "            [seon.error :as-alias error]))\n"
             "(defn refuse [reason]\n"
             "  {:seon.error/kind :sample.keys/refused\n"
             "   ::error/message reason\n"
             "   ::local true})\n"
             "(defn built [n] (keyword \"seon.error\" (str \"kind\" n)))\n"
             "(defn destructured [{:sample.keys/keys [depth] :keys [plain]}]\n"
             "  [depth plain])\n"
             "(deftest refusal-test\n"
             "  (is (= :sample.keys/refused (:seon.error/kind (refuse \"why\")))))\n")]
    (write-source! root "sample/keys.clj" source)
    (write-source! root "seon/error.clj" "(ns seon.error)\n(def message :m)\n")
    (let [rows (seon.fn/rows {:seon.fn/roots [(.getPath root)]})
          by-id (into {} (map (juxt program/row-identity identity)) rows)
          used (fn [program-identity]
                 (:seon.fn/keywords (get by-id program-identity)))]
      (testing "literal qualified keywords land on the declaration that reads them"
        (is (= #{:sample.keys/local :sample.keys/refused
                 :seon.error/kind :seon.error/message}
               (used [:seon.fn/sym "sample.keys/refuse"]))
            "::kw, ::alias/kw, and :fully/qualified resolve to one honest form"))
      (testing "unqualified keywords stay out; qualified ones are kept verbatim"
        (is (= #{:sample.keys/depth :sample.keys/keys}
               (used [:seon.fn/sym "sample.keys/destructured"]))
            (str ":keys and :plain never enter the index. The namespaced "
                 ":sample.keys/keys marker does, because it IS written "
                 "literally — the fact is source usage, not declaredness")))
      (testing "a keyword built at runtime is invisible to static analysis"
        (is (nil? (used [:seon.fn/sym "sample.keys/built"]))
            "the honest boundary: only literal keyword usage is a fact"))
      (testing "test rows carry their own keyword usage"
        (is (= #{:sample.keys/refused :seon.error/kind}
               (used [:seon.test/sym "sample.keys/refusal-test"]))
            "a test's keywords are the ones it reads, never its subject's"))
      (testing "the indexed facts answer the motivating query"
        (test-support/with-database
          (fn [connection]
            (db/transact!
             connection
             (into (mapv #(dissoc % :seon.fn/keywords :seon.fn/calls)
                         (filter #(or (:seon.ns/name %) (:seon.fn/sym %)
                                      (:seon.test/sym %))
                                 rows))
                   (mapcat (fn [row]
                             (map (fn [used]
                                    [:db/add (program/row-identity row)
                                     :seon.fn/keywords used])
                                  (:seon.fn/keywords row))))
                   rows))
            ;; Keywords transact as explicit datoms: inside a map, Datahike
            ;; reads a two-element collection whose first element is a
            ;; unique-identity keyword as a lookup ref and refuses the entity.
            (is (= #{:sample.keys/depth :sample.keys/keys}
                   (set (:seon.fn/keywords
                         (db/pull @connection [:seon.fn/keywords]
                                  [:seon.fn/sym "sample.keys/destructured"])))))
            (is (= ["sample.keys/refuse"]
                   (filterv #(str/starts-with? % "sample.keys/")
                            (seon.fn/functions-using @connection
                                                     :seon.error/kind)))
                "a test reading the keyword is never a function consumer")
            (is (= [] (seon.fn/functions-using @connection
                                               :sample.keys/never-written)))))))))

(deftest tests-reaching-follows-calls-and-explicit-subjects
  (test-support/with-database
    (fn [connection]
      (let [namespace-ref [:seon.ns/name 'sample.reach]
            function-row
            (fn [function-symbol calls]
              (cond-> {:seon.fn/sym function-symbol
                       :seon.fn/ns namespace-ref
                       :seon.fn/source (str "(defn " (name (symbol function-symbol))
                                            " [] nil)")
                       :seon.fn/arglists "([])"
                       :seon.fn/private? false}
                (seq calls) (assoc :seon.fn/calls calls)))
            test-row
            (fn [test-symbol references]
              (merge {:seon.test/sym test-symbol
                      :seon.test/ns namespace-ref
                      :seon.test/source (str "(deftest "
                                             (name (symbol test-symbol)) ")")}
                     references))]
        (db/transact!
         connection
         [{:seon.ns/name 'sample.reach
           :seon.ns/source "(ns sample.reach)"}
          (function-row "sample.reach/target" nil)
          (function-row "sample.reach/bridge"
                        [[:seon.fn/sym "sample.reach/target"]])
          (function-row "sample.reach/direct"
                        [[:seon.fn/sym "sample.reach/target"]])])
        (db/transact!
         connection
         [(test-row "sample.reach/direct"
                    {:seon.fn/calls
                     [[:seon.fn/sym "sample.reach/target"]]})
          (test-row "sample.reach/indirect"
                    {:seon.fn/calls
                     [[:seon.fn/sym "sample.reach/bridge"]]})
          (test-row "sample.reach/property"
                    {:seon.test/subject
                     [:seon.fn/sym "sample.reach/bridge"]})])
        (is (not= (:db/id (db/pull @connection [:db/id]
                                   [:seon.fn/sym "sample.reach/direct"]))
                  (:db/id (db/pull @connection [:db/id]
                                   [:seon.test/sym "sample.reach/direct"])))
            "function and test identities stay distinct at the same name")
        (is (= ["sample.reach/direct"
                "sample.reach/indirect"
                "sample.reach/property"]
               (seon.fn/tests-reaching @connection "sample.reach/target")))
        (is (nil? (:seon.fn/calls
                   (db/pull @connection [:seon.fn/calls]
                            [:seon.test/sym "sample.reach/property"])))
            "the schema-property test reaches its subject without a call edge")
        (is (= []
               (seon.fn/tests-reaching @connection "sample.reach/absent")))))))

(deftest blocking-analysis-keeps-the-fresh-branch-unpublished
  (let [root (fixture-root)]
    (write-source! root "valid/core.clj" "(ns valid.core)\n(defn value [] 1)")
    (write-source! root "broken/core.clj" "(ns broken.core)\n(defn broken [")
    (test-support/with-database
      (fn [connection]
        (let [before (:max-tx @connection)
              failure (try
                        (seon.fn/index! {:seon.store/branch-connection connection
                                         :seon.db/process boot-process
                                         :seon.fn/roots [(.getPath root)]})
                        nil
                        (catch clojure.lang.ExceptionInfo error error))]
          (is (= :seon.fn/index-refused (:seon.error/kind (ex-data failure))))
          (is (some #(= (.getCanonicalPath (io/file root "broken/core.clj"))
                        (:seon.fn.analyzer/filename %))
                    (:seon.fn/findings (ex-data failure))))
          (is (= before (:max-tx @connection)))
          (is (nil? (db/pull @connection [:db/id]
                            [:seon.fn/sym "valid.core/value"]))))))))

(deftest indexing-refuses-an-already-populated-branch
  (let [root (fixture-root)]
    (write-source! root "fresh/core.clj"
                   "(ns fresh.core)\n(defn value [] 1)\n")
    (test-support/with-database
      (fn [connection]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"fresh source scratch"
             (seon.fn/index! {:seon.store/branch-connection connection
                              :seon.db/process boot-process
                              :seon.fn/roots [(.getPath root)]})))))))
