(ns seon.config-test
  "Sealed acceptance draft for manifest-to-config-facts."
  (:require [clojure.java.io :as io]
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

;;; The dials the defaults document must carry, read from the EFFECTIVE
;;; shape: every manifest entry is optional by design, so deriving this
;;; from the manifest would make the rule vacuous. A dial the effective
;;; shape marks optional may be absent, and absence is the state. The
;;; case that shaped the rule is :seon.config.error/escalate-to: it may
;;; be dropped by a manifest, and it nonetheless ships with a value now
;;; that boot seeds the root agent it names — requiredness and
;;; has-a-default are different questions.
(def ^:private required-dial-attributes
  (into #{}
        (comp (filter vector?)
              (keep (fn [entry]
                      (when-not (and (map? (second entry))
                                     (:optional (second entry)))
                        (first entry)))))
        (drop 2 (schema/schema-definition :seon.config/effective))))

(deftest the-default-document-has-one-canonical-location
  (is (.equals "config/default.edn" config/default-manifest-path))
  (is (.isFile (io/file config/default-manifest-path))))

(deftest defaults-are-complete-and-valid-for-every-dial
  (let [manifest (config/defaults)]
    (is (empty? (remove (set (keys manifest)) required-dial-attributes))
        "every REQUIRED dial has a value")
    (is (empty? (remove dial-attributes (keys manifest)))
        "and no operational quarry key leaks in")
    (testing "an optional dial is still representable BOTH ways — the two
    questions are separate, and conflating them is what made an optional
    dial impossible to express before"
      (is (not (contains? required-dial-attributes
                          :seon.config.error/escalate-to))
          "the effective shape marks it optional")
      (is (schema/valid-candidate-value?
           :seon.config/effective
           (dissoc manifest :seon.config.error/escalate-to))
          "so a manifest that drops it is valid — absence is the state")
      (is (= "root" (:seon.config.error/escalate-to manifest))
          "and the shipped document names the root agent boot seeds"))
    (doseq [dial dial-attributes]
      (is (schema/registered? dial) (str dial " has one registered schema"))
      (when (contains? manifest dial)
        (is (schema/valid-candidate-value? dial (get manifest dial))
            (pr-str (schema/explain-candidate-value dial (get manifest dial))))))
    (is (schema/valid-candidate-value? :seon.config/effective manifest))))

(deftest result-caps-are-derived-from-the-effective-configuration
  (let [effective (config/defaults)]
    (is (= {:seon.config.eval.result/max-depth 12
            :seon.config.eval.result/max-collection 64
            :seon.config.eval.result/max-string 4096
            :seon.config.eval.result/max-nodes 4096}
           (config/result-caps effective)))
    (is (schema/valid-candidate-value?
         :seon.sci.admit/caps
         (config/result-caps effective)))))

(deftest read-manifest-resolves-one-override-against-the-default-document
  (let [directory (io/file "tmp/config-test")
        path (io/file directory "override.edn")]
    (.mkdirs directory)
    (spit path "{:seon.config/on-core-error :record}\n")
    (try
      (let [manifest (config/read-manifest (str path))]
        (is (= (set (keys (config/defaults))) (set (keys manifest)))
            "an override resolves against the whole shipped document —
             every default it did not mention is still there")
        (is (= :record (:seon.config/on-core-error manifest)))
        (is (= 10 (:seon.config.flow.compute/queue-depth manifest)))
        (is (= 18 (:seon.config.flow.compute/concurrency manifest))))
      (finally
        (.delete path)
        (.delete directory)))))

(deftest manifest-gate-refuses-unknown-keys-and-invalid-values
  (testing "unknown means no registered dial schema"
    (let [data
          (test-support/refusal-data
           #(config/desired-rows
             {:seon.config.flow.compute/queue-depth 10
              :seon.config.flow.compute/concurrency 18
              :seon.config/on-core-error :panic
              :seon.config.old/transport-timeout-ms 60000}
             "default"))]
      (is (= ::config/unknown-key (::config/rule data)))
      (is (= :seon.config.old/transport-timeout-ms
             (::config/key data)))))
  (testing "a registered dial with the wrong value carries Malli's explanation"
    (let [data
          (test-support/refusal-data
           #(config/desired-rows
             {:seon.config.flow.compute/queue-depth 0
              :seon.config.flow.compute/concurrency 18
              :seon.config/on-core-error :panic}
             "default"))]
      (is (= ::config/invalid-value (::config/rule data)))
      (is (= :seon.config.flow.compute/queue-depth (::config/key data)))
      (is (map? (::config/explanation data))))))

(deftest unreadable-manifest-refuses-by-name
  (let [data
        (test-support/refusal-data
         #(config/read-manifest
           "tmp/config-test/this-manifest-does-not-exist.edn"))]
    (is (= ::config/manifest-unreadable (::config/rule data)))))

(deftest desired-rows-is-one-complete-identified-singleton
  (let [manifest {:seon.config.flow.compute/queue-depth 10
                  :seon.config.flow.compute/concurrency 18
                  :seon.config/on-core-error :panic}
        rows (config/desired-rows manifest "alpha")
        row (first rows)]
    (is (= 1 (count rows)))
    (is (.equals "alpha" (:seon.config/cluster row)))
    (is (= (merge (config/defaults) manifest)
           (select-keys row dial-attributes))
        "the row is the COMPLETE effective manifest — the override
         merged over defaults, never the override alone")
    (is (re-matches #"[0-9a-f]{64}"
                    (:seon.config/applied-manifest-digest row)))
    (is (schema/valid-candidate-value? :seon.config/entity row))))

(deftest apply-defaults-round-trips-through-database-facts
  (test-support/with-database
    (fn [connection]
      (let [manifest (config/defaults)
            result
            (config/apply!
             {:seon.config/connection connection
              :seon.config/manifest manifest
              :seon.boot/cluster-name "default"})]
        (is (false? (:seon.reconcile/converged? result)))
        (is (= manifest (config/effective @connection "default")))
        (is (= config/managing-process-identity
               (d/q
                '[:find ?process-id .
                  :where
                  [?entity :seon.config/cluster "default"]
                  [?entity :seon.config/on-core-error _ ?tx]
                  [?tx :seon.db/process ?process]
                  [?process :seon.db.process/id ?process-id]]
                @connection)))))))
