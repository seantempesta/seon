(ns seon.adoption-contract-freshness-test
  "Real source adoption, isolated from the shared worker's loaded program."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.cluster.boot :as boot]
            [seon.config :as config]
            [seon.db :as db]
            [seon.instrument :as instrument]
            [seon.cluster.process :as operator.process]
            [seon.schema :as schema]
            [seon.sci.eval :as evaluation]
            [seon.test-support :as support]))

(def ^:private source-path "src/my/adoption_contract_probe.clj")
(def ^:private schema-path "resources/seon/schemas/my.adoption-contract-probe.edn")

(defn- probe-source [request-schema]
  (str "(ns my.adoption-contract-probe)\n"
       "(defn value \"Return the request value.\"\n"
       " {:malli/schema [:=> [:cat " request-schema "] [:or :string :int :boolean]]}\n"
       " [request] (:my.adoption-contract-probe/value request))\n"))

(defn- write-schemas! [wide]
  (spit schema-path
        (pr-str
         {:my.adoption-contract-probe/value [:or :string :int :boolean]
          :my.adoption-contract-probe/old-request
          [:map [:my.adoption-contract-probe/value :string]]
          :my.adoption-contract-probe/new-request
          [:map [:my.adoption-contract-probe/value wide]]
          :my.adoption-contract-probe/third-request
          [:map [:my.adoption-contract-probe/value [:or :string :int :boolean]]]})))

(defn- sci-call [instance value]
  (let [connection (:seon.boot/cluster-connection instance)
        configuration (schema/call-with-projection-state
                       (:seon.sci.eval/projection-state (:seon.sci.eval/ctx instance))
                       #(config/effective @connection "contract-freshness"))]
    (evaluation/evaluate
     {:seon.sci.eval/ctx (:seon.sci.eval/ctx instance)
      :seon.db/db @connection
      :seon.cluster.eval/ns [:seon.ns/name 'my.adoption-contract-probe]
      :seon.boot/cluster-name "contract-freshness"
      :seon.cluster.eval/source
      (pr-str (list 'my.adoption-contract-probe/value
                    {:my.adoption-contract-probe/value value}))
      :seon.sci.admit/caps (config/result-caps configuration)
      :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms configuration)
      :seon.config/on-core-error :panic})))

(defn probe!
  "Executed only in a child JVM with its own complete source checkout."
  []
  (let [root (.getCanonicalPath (io/file "tmp/contract-freshness-root"))]
    (spit source-path (probe-source :my.adoption-contract-probe/old-request))
    (write-schemas! [:or :string :int])
    (support/populate-published-root! root)
    (cluster/refresh-source! root)
    (let [instance (boot/start! {:seon.boot/root root
                                    :seon.boot/cluster-name "contract-freshness"})
          connection (:seon.boot/cluster-connection instance)]
      (try
        (let [projection (schema/projection-from-database @connection)]
          (instrument/apply! {:seon.config/on-core-error :panic
                              :seon.schema/projection projection}))
        (assert (:seon.cluster.eval/error (sci-call instance 42))
                "The initial contract must actually reject the widened input.")
        (let [previous-source (probe-source :my.adoption-contract-probe/new-request)
              phases (atom [])]
          (spit source-path (probe-source :my.adoption-contract-probe/third-request))
          (let [published
                (binding [cluster/*source-progress!*
                          (fn [phase]
                            (swap! phases conj phase)
                            (when (= phase "development loaded definitions")
                              (spit source-path previous-source)))]
                  (cluster/refresh-source! root [source-path] "contract-freshness"))]
            (assert (= 1 (count (filter #{"development source changed; retrying adoption once"} @phases)))
                    (pr-str @phases))
            (assert (= 2 (count (filter #{"development loaded definitions"} @phases)))
                    (pr-str @phases))
            (assert (= (:seon.source/commit-id published)
                       (:seon.source/commit-id
                        (db/pull @connection [:seon.source/commit-id]
                                 [:seon.cluster/name "contract-freshness"])))))
          (assert (= (pr-str (:malli/schema
                             (meta (find-var 'my.adoption-contract-probe/value))))
                     (:seon.fn/spec
                      (db/pull @connection [:seon.fn/spec]
                               [:seon.fn/sym "my.adoption-contract-probe/value"])))
                  "The immediate retry must converge host and database contracts.")
          (assert (= 42 (:seon.sci.admit/value (sci-call instance 42)))))
        (let [accepted (sci-call instance 42)
              refused (sci-call instance false)]
          (assert (= 42 (:seon.sci.admit/value accepted)) (pr-str accepted))
          (assert (:seon.cluster.eval/error refused) (pr-str refused))
          (assert (str/includes? (pr-str refused)
                                 ":my.adoption-contract-probe/new-request")
                  (pr-str refused)))
        (println "adoption-contract-freshness: changed contract and refusal retry enforced")
        (finally
          (boot/stop! instance))))))

(deftest adopted-contracts-govern-both-sci-and-host-calls
  (let [directory (.getCanonicalPath
                   (io/file "tmp" (str "adoption-contract-freshness-" (random-uuid))))
        classpath (str/join java.io.File/pathSeparator
                            (concat
                             (map #(str (io/file directory %)) ["src" "resources" "test"])
                             (map #(.getCanonicalPath (io/file %))
                                  (str/split (System/getProperty "java.class.path")
                                             (re-pattern java.io.File/pathSeparator)))))
        base (System/getProperty "seon.test.published-base")
        java (str (io/file (System/getProperty "java.home") "bin" "java"))]
    (.mkdirs (io/file directory))
    (try
      (let [copied (operator.process/run-process!
                    {:seon.operator.subprocess/argv
                     (into ["cp" "-RP"]
                           (concat
                            (map str
                                 (remove #(#{"tmp" "data" "logs" "target" "workers"}
                                            (.getName ^java.io.File %))
                                         (.listFiles (io/file "."))))
                            [directory]))
                     :seon.operator.subprocess/deadline-ms 1200000
                     :seon.operator.subprocess/merge-error? true})]
        (is (= 0 (:seon.operator.subprocess/exit copied)) (pr-str copied))
        (when (= 0 (:seon.operator.subprocess/exit copied))
          (let [result
                (operator.process/run-process!
                 {:seon.operator.subprocess/argv
                  (into (cond-> [java]
                          base (conj (str "-Dseon.test.published-base=" base)))
                        ["-cp" classpath "clojure.main" "-e"
                         "(do (require 'seon.adoption-contract-freshness-test) (seon.adoption-contract-freshness-test/probe!) (shutdown-agents))"])
                  :seon.operator.subprocess/directory directory
                  :seon.operator.subprocess/deadline-ms 1200000
                  :seon.operator.subprocess/merge-error? true})]
            (is (= 0 (:seon.operator.subprocess/exit result))
                (:seon.operator.subprocess/output result))
            (is (str/includes? (:seon.operator.subprocess/output result)
                               "adoption-contract-freshness: changed contract and refusal retry enforced")
                (:seon.operator.subprocess/output result)))))
      (finally (support/delete-recursively! directory)))))
