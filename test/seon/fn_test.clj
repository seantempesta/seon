(ns seon.fn-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
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
             "(deftest example-test (throw (ex-info \"never\" {})))\n"
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
            (d/q '[:find ?namespace ?required ?required-name
                   :where
                   [?namespace :seon.ns/requires ?required]
                   [?required :seon.ns/name ?required-name]]
                 db)
            required-eids
            (into #{} (map second) requires)
            name-only-eids
            (d/q '[:find [?namespace ...]
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
             (d/q '[:find ?namespace ?required
                    :where
                    [?namespace :seon.ns/requires ?required]
                    (not [?required :seon.ns/name])]
                  db)))))))

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
             :seon.fn/private? false}]
           :seon.fn.file/identities
           [[:seon.ns/name 'prebuilt]
            [:seon.fn/sym "prebuilt/value"]]}]
         :seon.fn.manifest/identities
         [[:seon.ns/name 'prebuilt]
          [:seon.fn/sym "prebuilt/value"]]}
        transactions (atom [])]
    (with-redefs [analyzer/analyze
                  (fn [_]
                    (throw (ex-info "analysis must not run" {})))
                  d/q (fn [& _] nil)
                  d/transact
                  (fn [_ request]
                    (swap! transactions conj request)
                    {})]
      (let [result (seon.fn/index!
                    {:seon.store/branch-connection (atom :database)
                     :seon.fn/manifest manifest})]
        (is (pos? (:seon.reconcile/operations result)))
        (is (= 2 (count @transactions)))
        (is (= 'prebuilt
               (-> @transactions first :tx-data first :seon.ns/name)))
        (is (some #(= "prebuilt/value" (:seon.fn/sym %))
                  (-> @transactions second :tx-data)))))))

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
          (is (nil? (d/pull @connection [:db/id]
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
