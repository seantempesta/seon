(ns seon.config-test
  "Acceptance proofs for the one manifest compiler and config apply."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.config :as config]
            [seon.reconcile :as reconcile]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as test-support])
  (:import [java.util.concurrent TimeUnit]))

(set! *warn-on-reflection* true)

(schema.edn/load! {})

(def ^:private dial-attributes
  ;; entries only — never positional past :map, so an optional properties
  ;; map can come or go without silently dropping the first entry
  (into #{}
        (comp (filter vector?) (map first))
        (schema/schema-definition :seon.config/manifest)))

(defn- with-default-document
  [document body]
  (let [resource io/resource
        directory (io/file "tmp/config-test-initialization")
        packaged-file (io/file directory "default.edn")]
    (.mkdirs directory)
    (spit packaged-file (str (pr-str document) "\n"))
    (try
      (with-redefs [io/resource
                    (fn [path]
                      (if (= config/default-manifest-path path)
                        (-> packaged-file .toURI .toURL)
                        (resource path)))]
        (body))
      (finally
        (.delete packaged-file)
        (.delete directory)))))

(deftest config-loads-the-packaged-population-in-a-fresh-jvm
  (let [command
        ["clojure" "-M:dev" "-e"
         (str
          "(require 'seon.config 'seon.schema) "
          "(when-not (contains? (seon.schema/registered-schemas) "
          ":seon.ai.model/provider-id) "
          "(throw (ex-info \"packaged schema missing\" {}))) "
          "(seon.config/defaults) "
          "(println :fresh-config-ready)")]
        process
        (.start
         (doto
          (ProcessBuilder. ^java.util.List command)
          (.directory (io/file (System/getProperty "user.dir")))
          (.redirectErrorStream true)))
        output (future (slurp (.getInputStream process)))
        exited? (.waitFor process 30 TimeUnit/SECONDS)]
    (when-not exited?
      (.destroyForcibly process))
    (is exited? "the fresh config JVM exceeded its external-process backstop")
    (when exited?
      (is (zero? (.exitValue process)) @output)
      (is (str/includes? @output ":fresh-config-ready") @output))))

(deftest the-default-document-has-one-canonical-complete-location
  (is (.equals "config/default.edn" config/default-manifest-path))
  (is (.isFile (io/file config/default-manifest-path)))
  (is (= dial-attributes
         (set
          (remove
           #{config/initialization-key}
           (keys
            (edn/read-string (slurp config/default-manifest-path))))))
      "the shipped EDN itself, not a second registry, covers every production attribute")
  (is (= dial-attributes
         (set (keys (config/default-decisions))))
      "the shipped document makes one decision for every registered config attribute"))

(deftest shipped-initialization-admission-is-registry-derived
  (let [base (edn/read-string (slurp config/default-manifest-path))
        row {:seon.db.process/id "config-population-admission-test"}]
    (testing "a declared row with exactly one declared identity is admitted"
      (with-default-document
        (assoc base config/initialization-key [row])
        #(is (= [row] (config/default-population)))))
    (testing "the reserved entry is not a sparse overlay dial"
      (let [data
            (test-support/refusal-data
             #(config/compile-manifest
               {:seon.boot/cluster-name "default" :seon.config/manifest
                {config/initialization-key [row]}}))]
        (is (= ::config/initialization-not-allowed (::config/rule data)))))
    (doseq [[label population expected-rule expected-key]
            [["the population must be a vector"
              row
              ::config/invalid-initialization
              nil]
             ["every member must be a map"
              ["not-a-row"]
              ::config/invalid-initialization-row
              nil]
             ["keys must be qualified keywords"
              [{:id "x"}]
              ::config/invalid-initialization-attribute
              :id]
             ["unknown attributes are refused"
              [{:seon.unknown/id "x"}]
              ::config/unknown-initialization-attribute
              :seon.unknown/id]
             ["declared values are rigorously validated"
              [{:seon.db.process/id ""}]
              ::config/invalid-initialization-value
              :seon.db.process/id]
             ["a row must have an identity attribute"
              [{:seon.config/on-core-error :panic}]
              ::config/invalid-initialization-identity
              nil]
             ["a row cannot have two identity attributes"
              [{:seon.db.process/id "x"
                :seon.config/cluster "x"}]
              ::config/invalid-initialization-identity
              nil]]]
      (testing label
        (let [data
              (with-default-document
                (assoc base config/initialization-key population)
                #(test-support/refusal-data config/default-population))]
          (is (= expected-rule (::config/rule data)))
          (when expected-key
            (is (= expected-key (::config/key data)))))))))

(deftest initialization-rows-apply-query-and-converge-with-the-config-row
  (let [base (edn/read-string (slurp config/default-manifest-path))
        process-id "seon.db.process/config-population-test"
        document
        (assoc base config/initialization-key
               [{:seon.db.process/id process-id}])]
    (with-default-document
      document
      #(test-support/with-database
         (fn [connection]
           (let [first-result
                 (config/apply! {:seon.boot/cluster-name "default" :seon.db/connection connection})
                 committed-basis (:max-tx @connection)
                 second-result
                 (config/apply! {:seon.boot/cluster-name "default" :seon.db/connection connection})]
             (is (false? (:seon.reconcile/converged? first-result)))
             (is (= process-id
                    (db/q
                     '[:find ?id .
                       :in $ ?id
                       :where [_ :seon.db.process/id ?id]]
                     @connection
                     process-id)))
             (is (true? (:seon.reconcile/converged? second-result)))
             (is (zero? (:seon.reconcile/operations second-result)))
             (is (= committed-basis (:max-tx @connection))
                 "an identical config and population write no transaction")))))))

(deftest converged-apply-uses-carried-projection-and-remains-exact
  (test-support/with-database
   (fn [connection]
     (let [request {:seon.db/connection connection
                    :seon.boot/cluster-name "default"}
           first-result (config/apply! request)
           basis (:max-tx @connection)
           rebuild schema/projection-from-database
           fallback db/projection-fallback
           plan reconcile/plan
           caller (Thread/currentThread)
           rebuilds (atom 0)
           fallbacks (atom 0)
           planned-operations (atom [])]
       (is (false? (:seon.reconcile/converged? first-result)))
       (is (some? (db/carried-projection (db/db connection))))
       (with-redefs [schema/projection-from-database
                     (fn [& arguments]
                       (when (identical? caller (Thread/currentThread))
                         (swap! rebuilds inc))
                       (apply rebuild arguments))
                     db/projection-fallback
                     (fn [operation]
                       (when (identical? caller (Thread/currentThread))
                         (swap! fallbacks inc))
                       (fallback operation))
                     reconcile/plan
                     (fn [database request]
                       (let [operations (plan database request)]
                         (when (identical? caller (Thread/currentThread))
                           (swap! planned-operations conj (count operations)))
                         operations))]
         (is (= {:seon.reconcile/converged? true
                 :seon.reconcile/operations 0}
                (config/apply! request))))
       (is (zero? @rebuilds))
       (is (zero? @fallbacks))
       (is (= [0] @planned-operations)
           "a real exact read observes convergence without rebuilding")
       (is (= basis (:max-tx @connection)))
       (let [compiled (config/compile-manifest {:seon.boot/cluster-name "default"})
             digest (:seon.config/applied-manifest-digest compiled)
             queue-depth (get-in compiled [:seon.config/effective
                                          :seon.config.flow.compute/queue-depth])]
         (let [edited (db/transact!
                       connection
                       [{:db/id [:seon.config/cluster "default"]
                         :seon.config.flow.compute/queue-depth (inc queue-depth)}])]
           (is (some? (:db-after edited)) (pr-str edited))
           (is (= (inc queue-depth)
                  (:seon.config.flow.compute/queue-depth
                   (db/pull (db/db connection)
                            [:seon.config.flow.compute/queue-depth]
                            [:seon.config/cluster "default"])))
               "the hand edit committed before reconciliation"))
         (is (= digest (:seon.config/applied-manifest-digest
                        (db/pull @connection [:seon.config/applied-manifest-digest]
                                 [:seon.config/cluster "default"]))))
         (is (false? (:seon.reconcile/converged? (config/apply! request)))
             "the same manifest repairs a hand edit")
         (is (= queue-depth (:seon.config.flow.compute/queue-depth
                             (config/effective @connection "default"))))
         (let [process-identity [:seon.db.process/id "config-apply-cost-initialization"]
               changed (update compiled :seon.config/initialization conj
                               {(first process-identity) (second process-identity)})]
           (is (false? (:seon.reconcile/converged?
                        (config/apply-compiled! connection changed)))
               "initialization can change while the dial digest stays equal")
           (is (= (second process-identity)
                  (:seon.db.process/id
                   (db/pull @connection [:seon.db.process/id] process-identity))))))))))

(deftest packaged-defaults-use-the-same-shipped-document-authority
  (let [resource io/resource
        directory (io/file "tmp/config-test-packaged")
        packaged-file (io/file directory "default.edn")
        repository-decisions (edn/read-string
                              (slurp config/default-manifest-path))
        packaged-decisions
        (assoc repository-decisions
               :seon.config.flow.compute/queue-depth 19)]
    (.mkdirs directory)
    (spit packaged-file (str (pr-str packaged-decisions) "\n"))
    (try
      (with-redefs [io/resource
                    (fn [path]
                      (if (= config/default-manifest-path path)
                        (-> packaged-file .toURI .toURL)
                        (resource path)))]
        (is (= 19
               (:seon.config.flow.compute/queue-depth
                (config/default-decisions)))
            "a packaged resource wins without copying the decision roster"))
      (finally
        (.delete packaged-file)
        (.delete directory)))))

(deftest apply-converts-a-flat-reconcile-error-to-a-refusal
  (test-support/with-database
    (fn [connection]
      (let [flat-error {:seon.error/kind :seon.db/rejected
                        :seon.error/message "injected config refusal"}
            result
            (with-redefs [reconcile/plan (fn [& _] [{}])
                          db/transact! (fn [& _] flat-error)]
              (test-support/refusal-data
               #(config/apply-compiled!
                 connection
                 (config/compile-manifest {:seon.boot/cluster-name "default"}))))]
        (is (= :seon.config/refused (:seon.error/kind result)))
        (is (= :seon.config/reconcile-refused (:seon.config/rule result)))
        (is (= flat-error (:seon.config/reconcile-result result)))))))

(deftest cluster-scoped-config-refuses-an-omitted-cluster
  (test-support/with-database
   (fn [connection]
     (let [basis (:max-tx @connection)]
       (doseq [[caller invoke]
               [["seon.config/compile-manifest" #(config/compile-manifest {})]
                ["seon.config/apply!" #(config/apply! {:seon.db/connection connection})]
                ["seon.config/effective" #(apply config/effective [(db/db connection)])]]]
         (let [refusal (test-support/refusal-data invoke)]
           (is (= :seon.instrument/contract-violated (:seon.error/kind refusal)))
           (is (= caller (:seon.instrument/contract-violated refusal)))))
       (is (= basis (:max-tx @connection)))))))

(deftest zero-overlay-compilation-resolves-every-registered-config-attribute
  (let [compiled (config/compile-manifest {:seon.boot/cluster-name "default"})
        effective (:seon.config/effective compiled)
        row (:seon.config/desired-row compiled)]
    (is (= dial-attributes (:seon.config/resolved-attributes compiled))
        "this is the standing zero-overlay completeness proof")
    (is (= effective (select-keys row dial-attributes)))
    (is (= "default" (:seon.config/cluster row))
        "the caller names the cluster explicitly")
    (is (schema/valid-candidate-value? :seon.config/effective effective))
    (is (schema/valid-candidate-value? :seon.config/entity row))
    (is (= (long (.availableProcessors (Runtime/getRuntime)))
           (:seon.config.flow.compute/concurrency effective)))
    (is (true? (:seon.config.db/keep-history? effective))
        "ordinary operator roots retain history by shipped decision")
    (is (not (contains? effective :seon.config.web/port))
        "an explicit absence decision resolves without storing nil")
    (is (= (:seon.config.ai.backup/model (config/default-decisions))
           (:seon.config.ai.backup/model effective))
        "the explicitly shipped backup decision resolves without a mirror")
    (is (= :disabled (:seon.config.ai/thinking effective))
        "the shipped Flash posture explicitly disables thinking")
    (is (not (contains? effective :seon.config.ai/temperature))
        "optional request dials resolve absence without storing markers")))

(deftest one-compiler-applies-default-overlay-environment-precedence
  (let [compiled
        (config/compile-manifest
         {:seon.config/manifest
          {:seon.config.flow.compute/queue-depth 11
           :seon.config.db/keep-history? false
           :seon.config/on-core-error :record}
          :seon.config/environment
          {:seon.config.flow.compute/queue-depth 12}
          :seon.boot/cluster-name "alpha"})
        effective (:seon.config/effective compiled)]
    (is (= 12 (:seon.config.flow.compute/queue-depth effective))
        "explicit environment wins over the selected sparse overlay")
    (is (= :record (:seon.config/on-core-error effective))
        "the sparse overlay wins over shipped defaults")
    (is (false? (:seon.config.db/keep-history? effective))
        "an isolated root may select the non-temporal representation")
    (is (= 65536 (:seon.config.eval.result/max-nodes effective))
        "an unmentioned entry inherits its shipped decision")
    (is (= "alpha"
           (:seon.config/cluster (:seon.config/desired-row compiled))))))

(deftest explicit-absence-is-a-decision-never-a-stored-value
  (is (schema/valid-candidate-value?
       :seon.config/manifest
       {:seon.config.error/escalate-to config/absent})
      "the derived manifest schema admits explicit absence for an optional dial")
  (let [baseline (config/compile-manifest {:seon.boot/cluster-name "default"})
        omitted (config/compile-manifest {:seon.boot/cluster-name "default" :seon.config/manifest {}})
        absent
        (config/compile-manifest
         {:seon.boot/cluster-name "default" :seon.config/manifest
          {:seon.config.error/escalate-to config/absent}})
        effective (:seon.config/effective absent)
        row (:seon.config/desired-row absent)]
    (is (= "root"
           (:seon.config.error/escalate-to
            (:seon.config/effective baseline))
           (:seon.config.error/escalate-to
            (:seon.config/effective omitted)))
        "omission inherits the shipped default; it never means retraction")
    (is (not (contains? effective :seon.config.error/escalate-to)))
    (is (not (contains? row :seon.config.error/escalate-to)))
    (is (not-any? nil? (vals row)))
    (is (not= (:seon.config/applied-manifest-digest baseline)
              (:seon.config/applied-manifest-digest absent)))
    (testing "a required entry cannot be removed"
      (let [data
            (test-support/refusal-data
             #(config/compile-manifest
               {:seon.boot/cluster-name "default" :seon.config/manifest
                {:seon.config.flow.compute/queue-depth config/absent}}))]
        (is (= ::config/required-absent (::config/rule data)))
        (is (= :seon.config.flow.compute/queue-depth
               (::config/key data)))))))

(deftest canonical-digest-is-independent-of-map-construction-order
  (let [left
        (config/compile-manifest
         {:seon.boot/cluster-name "default" :seon.config/manifest
          (array-map
           :seon.config/on-core-error :record
           :seon.config.flow.compute/queue-depth 22)})
        right
        (config/compile-manifest
         {:seon.config/manifest
          (array-map
           :seon.config.flow.compute/queue-depth 22
           :seon.config/on-core-error :record)
          :seon.boot/cluster-name "other"})]
    (is (= (:seon.config/effective left)
           (:seon.config/effective right)))
    (is (= (:seon.config/applied-manifest-digest left)
           (:seon.config/applied-manifest-digest right))
        "cluster identity is not part of the effective-config digest")))

(deftest sparse-file-reading-does-not-compile-a-second-time
  (let [directory (io/file "tmp/config-test")
        path (io/file directory "override.edn")]
    (.mkdirs directory)
    (spit path "{:seon.config/on-core-error :record}\n")
    (try
      (is (= {:seon.config/on-core-error :record}
             (config/read-manifest (str path)))
          "selection reads a sparse overlay; compile owns all merging")
      (finally
        (.delete path)
        (.delete directory)))))

(deftest compiler-ignores-extra-keys-and-refuses-invalid-declared-values
  (testing "unknown means no registered config attribute schema"
    (let [compiled
          (config/compile-manifest
           {:seon.boot/cluster-name "default" :seon.config/manifest
            {:seon.config.old/transport-timeout-ms 60000}})]
      (is (not (contains? (:seon.config/effective compiled)
                          :seon.config.old/transport-timeout-ms))
          "an extra key is ignored until a declaration gives it meaning")))
  (testing "a registered attribute with the wrong value carries Malli's explanation"
    (let [data
          (test-support/refusal-data
           #(config/compile-manifest
             {:seon.boot/cluster-name "default" :seon.config/environment
              {:seon.config.flow.compute/queue-depth 0}}))]
      (is (= :seon.instrument/contract-violated (:seon.error/kind data)))
      (is (= "seon.config/compile-manifest"
             (:seon.instrument/contract-violated data)))
      (is (= #{[:seon.config/environment
                :seon.config.flow.compute/queue-depth]}
             (set (get-in data [:seon.error/data
                                :seon.instrument/problem-paths])))))))

(deftest unreadable-manifest-refuses-by-name
  (let [data
        (test-support/refusal-data
         #(config/read-manifest
           "tmp/config-test/this-manifest-does-not-exist.edn"))]
    (is (= ::config/manifest-unreadable (::config/rule data)))))

(deftest apply-compiles-once-and-round-trips-through-database-facts
  (test-support/with-database
    (fn [connection]
      (let [result
            (config/apply!
             {:seon.boot/cluster-name "default" :seon.db/connection connection
              :seon.config/manifest
              {:seon.config/on-core-error :record}})
            committed-basis (:max-tx @connection)
            converged
            (config/apply!
             {:seon.boot/cluster-name "default" :seon.db/connection connection
              :seon.config/manifest
              {:seon.config/on-core-error :record}})]
        (is (false? (:seon.reconcile/converged? result)))
        (is (true? (:seon.reconcile/converged? converged)))
        (is (zero? (:seon.reconcile/operations converged)))
        (is (= committed-basis (:max-tx @connection))
            "a converged apply writes no transaction")
        (is (= :record
               (:seon.config/on-core-error
                (config/effective @connection "default"))))
        (is (= config/managing-process-identity
               (db/q
                '[:find ?process-id .
                  :where
                  [?entity :seon.config/cluster "default"]
                  [?entity :seon.config/on-core-error _ ?tx]
                  [?tx :seon.db/process ?process]
                  [?process :seon.db.process/id ?process-id]]
                @connection)))))))

(deftest apply-replaces-an-inherited-config-identity-regardless-of-provenance
  (test-support/with-database
    (fn [connection]
      (let [ancestor
            (:seon.config/desired-row
             (config/compile-manifest
              {:seon.boot/cluster-name "ancestor"}))]
        (test-support/transacted! connection [ancestor])
        (config/apply!
         {:seon.db/connection connection
          :seon.config/manifest
          {:seon.config.flow.compute/queue-depth 17}
          :seon.boot/cluster-name "fork"})
        (is (= ["fork"]
               (sort
                (db/q '[:find [?cluster-name ...]
                        :where
                        [_ :seon.config/cluster ?cluster-name]]
                      @connection))))
        (is (= 17
               (:seon.config.flow.compute/queue-depth
                (config/effective @connection "fork"))))))))

(deftest a-config-row-cannot-be-left-without-a-required-result-cap
  ;; This assertion moved. It used to retract a cap key, then prove
  ;; `result-caps` refused the stale row it produced. Write admission reads an
  ;; identity-keyed map against the WHOLE config schema, so that row can no
  ;; longer be constructed at all: the retraction itself is refused, naming
  ;; the key, and nothing lands. `result-caps` remains proven against the
  ;; refusal shapes that DO reach it — a `missing-effective-error` in
  ;; `two-clusters-on-one-jvm-have-no-config-bleed`, and a cluster-less
  ;; refusal below — because `:seon.config/effective`, its other declared
  ;; input, cannot itself be missing a cap key.
  (test-support/with-database
    (fn [connection]
      (config/apply! {:seon.boot/cluster-name "default" :seon.db/connection connection})
      (let [basis (:max-tx @connection)
            refused (db/transact!
                     connection
                     [[:db/retract
                       [:seon.config/cluster "default"]
                       :seon.config.eval.result/max-nodes]])]
        (is (:seon.error/kind refused))
        (is (str/includes? (:seon.error/message refused)
                           ":seon.config.eval.result/max-nodes"))
        (is (= basis (:max-tx @connection))
            "and nothing landed")))))

(deftest caps-refused-for-want-of-a-cluster-carry-that-refusal-as-the-cause
  ;; `seon.sci.eval/database-effective-config` answers a flat refusal for a
  ;; database naming no cluster. `result-caps` keeps its own class marker —
  ;; `seon.instrument/wrap-interpreted` reports that marker when a `:panic`
  ;; contract cannot be armed — and carries the configuration refusal as the
  ;; cause, so neither fact is buried by the other.
  (let [refusal {:seon.error/kind :seon.config/required-absent
                 :seon.config/required-absent :seon.boot/cluster-name
                 :seon.error/message "this database names no cluster."}
        result (config/result-caps refusal)]
    (is (= ::config/missing-result-cap (:seon.error/kind result)))
    (is (= :seon.config.eval.result/max-bytes
           (get-in result [:seon.error/data :seon.config/key])))
    (is (= refusal
           (get-in result [:seon.error/data
                           :seon.config/configuration-refusal])))
    (is (str/includes? (:seon.error/message result)
                       "this database names no cluster."))))

(deftest two-clusters-on-one-jvm-have-no-config-bleed
  (test-support/with-database
    (fn [alpha]
      (test-support/with-database
        (fn [beta]
          (config/apply!
           {:seon.db/connection alpha
            :seon.config/manifest
            {:seon.config.flow.compute/queue-depth 11}
            :seon.boot/cluster-name "alpha"})
          (config/apply!
           {:seon.db/connection beta
            :seon.config/manifest
            {:seon.config.flow.compute/queue-depth 22}
            :seon.boot/cluster-name "beta"})
          (is (= 11
                 (:seon.config.flow.compute/queue-depth
                  (config/effective @alpha "alpha"))))
          (is (= 22
                 (:seon.config.flow.compute/queue-depth
                  (config/effective @beta "beta"))))
          (let [missing-beta (config/effective @alpha "beta")
                missing-alpha (config/effective @beta "alpha")]
            (is (= "beta" (:seon.config/missing-effective missing-beta)))
            (is (= "alpha" (:seon.config/missing-effective missing-alpha)))
            (is (= ::config/missing-result-cap
                   (:seon.error/kind (config/result-caps missing-beta))))
            (is (= ::config/missing-result-cap
                   (:seon.error/kind (config/result-caps missing-alpha))))
            (is (= "No effective configuration facts match cluster \"beta\"; available clusters [\"alpha\"]."
                   (:seon.error/message missing-beta)))
            (is (= "No effective configuration facts match cluster \"alpha\"; available clusters [\"beta\"]."
                   (:seon.error/message missing-alpha)))))))))
