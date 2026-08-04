(ns seon.config-test
  "Acceptance proofs for the one manifest compiler and config apply."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.config :as config]
            [seon.reconcile :as reconcile]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as test-support]))

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
  (let [directory (io/file "tmp/config-test-initialization")
        packaged-file (io/file directory "default.edn")]
    (.mkdirs directory)
    (spit packaged-file (str (pr-str document) "\n"))
    (try
      (with-redefs [io/resource
                    (fn [path]
                      (when (= config/default-manifest-path path)
                        (-> packaged-file .toURI .toURL)))]
        (body))
      (finally
        (.delete packaged-file)
        (.delete directory)))))

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
               {:seon.config/manifest
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
                 (config/apply! {:seon.config/connection connection})
                 committed-basis (:max-tx @connection)
                 second-result
                 (config/apply! {:seon.config/connection connection})]
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

(deftest packaged-defaults-use-the-same-shipped-document-authority
  (let [directory (io/file "tmp/config-test-packaged")
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
                      (when (= config/default-manifest-path path)
                        (-> packaged-file .toURI .toURL)))]
        (is (= 19
               (:seon.config.flow.compute/queue-depth
                (config/default-decisions)))
            "a packaged resource wins without copying the decision roster"))
      (finally
        (.delete packaged-file)
        (.delete directory)))))

(deftest apply-converts-a-flat-reconcile-error-to-a-refusal
  (let [flat-error {:seon.error/kind :seon.db/rejected
                    :seon.error/message "injected config refusal"}
        result
        (with-redefs [reconcile/plan (fn [& _] [{}])
                      db/transact! (fn [& _] flat-error)]
          (test-support/refusal-data
           #(config/apply-compiled!
             (atom :database)
             (config/compile-manifest {}))))]
    (is (= :seon.config/refused (:seon.error/kind result)))
    (is (= :seon.config/reconcile-refused (:seon.config/rule result)))
    (is (= flat-error (:seon.config/reconcile-result result)))))

(deftest zero-overlay-compilation-resolves-every-registered-config-attribute
  (let [compiled (config/compile-manifest {})
        effective (:seon.config/effective compiled)
        row (:seon.config/desired-row compiled)]
    (is (= dial-attributes (:seon.config/resolved-attributes compiled))
        "this is the standing zero-overlay completeness proof")
    (is (= effective (select-keys row dial-attributes)))
    (is (= "default" (:seon.config/cluster row))
        "cluster name is optional everywhere")
    (is (schema/valid-candidate-value? :seon.config/effective effective))
    (is (schema/valid-candidate-value? :seon.config/entity row))
    (is (= (long (.availableProcessors (Runtime/getRuntime)))
           (:seon.config.flow.compute/concurrency effective)))
    (is (true? (:seon.config.db/keep-history? effective))
        "ordinary operator roots retain history by shipped decision")
    (is (not (contains? effective :seon.config.web/port))
        "an explicit absence decision resolves without storing nil")
    (is (not (contains? effective :seon.config.ai.backup/model)))
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
  (let [baseline (config/compile-manifest {})
        omitted (config/compile-manifest {:seon.config/manifest {}})
        absent
        (config/compile-manifest
         {:seon.config/manifest
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
               {:seon.config/manifest
                {:seon.config.flow.compute/queue-depth config/absent}}))]
        (is (= ::config/required-absent (::config/rule data)))
        (is (= :seon.config.flow.compute/queue-depth
               (::config/key data)))))))

(deftest canonical-digest-is-independent-of-map-construction-order
  (let [left
        (config/compile-manifest
         {:seon.config/manifest
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

(deftest compiler-gate-refuses-unknown-keys-and-invalid-values
  (testing "unknown means no registered config attribute schema"
    (let [data
          (test-support/refusal-data
           #(config/compile-manifest
             {:seon.config/manifest
              {:seon.config.old/transport-timeout-ms 60000}}))]
      (is (= ::config/unknown-key (::config/rule data)))
      (is (= :seon.config.old/transport-timeout-ms (::config/key data)))))
  (testing "a registered attribute with the wrong value carries Malli's explanation"
    (let [data
          (test-support/refusal-data
           #(config/compile-manifest
             {:seon.config/environment
              {:seon.config.flow.compute/queue-depth 0}}))]
      (is (= ::config/invalid-value (::config/rule data)))
      (is (= :seon.config.flow.compute/queue-depth (::config/key data)))
      (is (map? (::config/explanation data))))))

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
             {:seon.config/connection connection
              :seon.config/manifest
              {:seon.config/on-core-error :record}})
            committed-basis (:max-tx @connection)
            converged
            (config/apply!
             {:seon.config/connection connection
              :seon.config/manifest
              {:seon.config/on-core-error :record}})]
        (is (false? (:seon.reconcile/converged? result)))
        (is (true? (:seon.reconcile/converged? converged)))
        (is (zero? (:seon.reconcile/operations converged)))
        (is (= committed-basis (:max-tx @connection))
            "a converged apply writes no transaction")
        (is (= :record
               (:seon.config/on-core-error
                (config/effective @connection))))
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
        (db/transact! connection [ancestor])
        (config/apply!
         {:seon.config/connection connection
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

(deftest two-clusters-on-one-jvm-have-no-config-bleed
  (test-support/with-database
    (fn [alpha]
      (test-support/with-database
        (fn [beta]
          (config/apply!
           {:seon.config/connection alpha
            :seon.config/manifest
            {:seon.config.flow.compute/queue-depth 11}
            :seon.boot/cluster-name "alpha"})
          (config/apply!
           {:seon.config/connection beta
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
            (is (= missing-beta (config/result-caps missing-beta)))
            (is (= missing-alpha (config/result-caps missing-alpha)))
            (is (= "No effective configuration facts match cluster \"beta\"; available clusters [\"alpha\"]."
                   (:seon.error/message missing-beta)))
            (is (= "No effective configuration facts match cluster \"alpha\"; available clusters [\"beta\"]."
                   (:seon.error/message missing-alpha)))))))))
