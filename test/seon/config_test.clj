(ns seon.config-test
  "Acceptance proofs for the one manifest compiler and config apply."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.config :as config]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as test-support]))

(set! *warn-on-reflection* true)

(schema.edn/load! {::schema.edn/resource-dir "seon/schema"})

(def ^:private dial-attributes
  (into #{}
        (map first)
        (drop 2 (schema/schema-definition :seon.config/manifest))))

(deftest the-default-document-has-one-canonical-complete-location
  (is (.equals "config/default.edn" config/default-manifest-path))
  (is (.isFile (io/file config/default-manifest-path)))
  (is (= dial-attributes
         (set
          (keys
           (edn/read-string (slurp config/default-manifest-path)))))
      "the shipped EDN itself, not a second registry, covers every production attribute")
  (is (= dial-attributes
         (set (keys (config/default-decisions))))
      "the shipped document makes one decision for every registered config attribute"))

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
           :seon.config/on-core-error :record}
          :seon.config/environment
          {:seon.config.flow.compute/queue-depth 12}
          :seon.boot/cluster-name "alpha"})
        effective (:seon.config/effective compiled)]
    (is (= 12 (:seon.config.flow.compute/queue-depth effective))
        "explicit environment wins over the selected sparse overlay")
    (is (= :record (:seon.config/on-core-error effective))
        "the sparse overlay wins over shipped defaults")
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
               (d/q
                '[:find ?process-id .
                  :where
                  [?entity :seon.config/cluster "default"]
                  [?entity :seon.config/on-core-error _ ?tx]
                  [?tx :seon.db/process ?process]
                  [?process :seon.db.process/id ?process-id]]
                @connection)))))))

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
          (is (= {}
                 (config/effective @alpha "beta")))
          (is (= {}
                 (config/effective @beta "alpha"))))))))
