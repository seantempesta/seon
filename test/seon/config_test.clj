(ns seon.config-test
  "Sealed acceptance draft for manifest-to-config-facts."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.config :as config]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]))

(set! *warn-on-reflection* true)

(defn- schema-resource
  [path]
  (with-open [reader (java.io.PushbackReader. (io/reader (io/resource path)))]
    (edn/read reader)))

; Load only this sealed suite's production declarations. The global loader is
; separately sealed by seon.schema.edn-test; a fixture must not depend on
; unrelated predicate-owning namespaces having loaded first.
(schema/contribute-candidate-forms!
 (merge
  (schema-resource "seon/schema/config.edn")
  (schema-resource "seon/schema/provenance.edn")
  (select-keys (schema-resource "seon/schema/flow.edn")
               [:seon.config.flow.compute/queue-depth
                :seon.config.flow.compute/concurrency])
  (select-keys (schema-resource "seon/schema/boot.edn")
               [:seon.boot/cluster-name])
  (select-keys (schema-resource "seon/schema/admit.edn")
               [:seon.config.eval.result/max-depth
                :seon.config.eval.result/max-collection
                :seon.config.eval.result/max-string
                :seon.config.eval.result/max-nodes])))

(def ^:private expected-dial-attributes
  #{:seon.config.flow.compute/queue-depth
    :seon.config.flow.compute/concurrency
    :seon.config.eval.result/max-depth
    :seon.config.eval.result/max-collection
    :seon.config.eval.result/max-string
    :seon.config.eval.result/max-nodes
    :seon.config.eval/time-limit-ms
    :seon.config/on-core-error})

(def ^:private dial-attributes
  (into #{}
        (map first)
        (drop 2 (schema/schema-definition :seon.config/manifest))))

(def ^:private process-identity-schema
  {:db/ident :seon.db.process/id
   :db/valueType :db.type/string
   :db/cardinality :db.cardinality/one
   :db/unique :db.unique/identity})

(def ^:private model-attributes
  [:seon.db/process
   :seon.db/user
   :seon.config/cluster
   :seon.config/applied-manifest-digest
   :seon.config.flow.compute/queue-depth
   :seon.config.flow.compute/concurrency
   :seon.config.eval.result/max-depth
   :seon.config.eval.result/max-collection
   :seon.config.eval.result/max-string
   :seon.config.eval.result/max-nodes
   :seon.config.eval/time-limit-ms
   :seon.config/on-core-error])

(defn- with-config-database
  [body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (d/transact connection
                  (into [process-identity-schema]
                        (schema.datahike/malli->datahike-schema
                         model-attributes)))
      (d/transact connection
                  [{:seon.db.process/id config/managing-process-identity}])
      (body connection)
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(defn- deepest-ex-data
  [error]
  (loop [throwable error
         found nil]
    (if throwable
      (recur (ex-cause throwable)
             (or (not-empty (ex-data throwable)) found))
      found)))

(defn- refusal-data
  [f]
  (try
    (f)
    (throw (ex-info "expected refusal" {::missing-refusal true}))
    (catch Exception error
      (when (.equals "awaits implementation" (ex-message error))
        (throw error))
      (deepest-ex-data error))))

(deftest the-default-document-has-one-canonical-location
  (is (.equals "config/default.edn" config/default-manifest-path))
  (is (.isFile (io/file config/default-manifest-path))))

(deftest defaults-are-complete-and-valid-for-every-dial
  (let [manifest (config/defaults)]
    (is (= expected-dial-attributes dial-attributes)
        "the registered manifest owns exactly today's honest dial population")
    (is (= dial-attributes (set (keys manifest)))
        "zero registered dials are missing and no operational quarry key leaks")
    (doseq [dial dial-attributes]
      (is (schema/registered? dial) (str dial " has one registered schema"))
      (is (schema/valid-candidate-value? dial (get manifest dial))
          (pr-str (schema/explain-candidate-value dial (get manifest dial)))))
    (is (schema/valid-candidate-value? :seon.config/effective manifest))))

(deftest read-manifest-resolves-one-override-against-the-default-document
  (let [directory (io/file "tmp/config-test")
        path (io/file directory "override.edn")]
    (.mkdirs directory)
    (spit path "{:seon.config/on-core-error :record}\n")
    (try
      (let [manifest (config/read-manifest (str path))]
        (is (= dial-attributes (set (keys manifest))))
        (is (= :record (:seon.config/on-core-error manifest)))
        (is (= 10 (:seon.config.flow.compute/queue-depth manifest)))
        (is (= 18 (:seon.config.flow.compute/concurrency manifest))))
      (finally
        (.delete path)
        (.delete directory)))))

(deftest manifest-gate-refuses-unknown-keys-and-invalid-values
  (testing "unknown means no registered dial schema"
    (let [data
          (refusal-data
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
          (refusal-data
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
        (refusal-data
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
  (with-config-database
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
