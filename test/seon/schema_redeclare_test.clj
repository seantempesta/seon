(ns seon.schema-redeclare-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.cluster.boot :as boot]
            [seon.context-blocks-fixture :as fixture]
            [seon.db :as db]
            [seon.env :as env]
            [seon.cluster.process :as operator.process]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(defn adoption-probe!
  "Run the real seeded adoption in a child JVM so reload owns no worker state."
  [root]
  (try
    (support/populate-published-root! root)
    (let [instance (boot/start! {:seon.boot/root root
                                     :seon.boot/cluster-name "schema-redeclare-test"})]
      (try
        (let [handle (:seon.turn.loop/cluster instance)
              routing (:seon.agent/routing instance)
              connection (:seon.db/connection handle)
              state (:seon.sci.eval/projection-state handle)
              seed! #(schema/call-with-projection-state
                      state (fn [] (fixture/install-running! handle routing)))
              _ (seed!)
              environment @state
              class-before (class environment)
              published (cluster/refresh-source!
                         root ["src/seon/env.clj"] "schema-redeclare-test")]
          ;; Initial adoption reloads the program, including env, on this JVM.
          (assert (not= class-before (ns-resolve 'seon.env 'Environment))
                  "The subject must actually reload the record declaration.")
          (assert (= (:seon.source/commit-id published)
                     (:seon.source/commit-id
                      (db/pull @connection [:seon.source/commit-id]
                               [:seon.cluster/name "schema-redeclare-test"]))))
          (assert (env/environment? environment))
          (assert (= class-before (class (env/environment
                                         {:seon.boot/cluster-name "later"}))))
          (seed!)
          (schema/call-with-projection-state
           state
           (fn []
             (let [turn-id (fixture/submit!
                            handle routing
                            "(seon.schema/register! :redeclare/new-id [:string {:seon.db/identity true}])\n(seon.schema/register! :redeclare/new-row [:map {:seon.db/attributes true} [:redeclare/new-id :redeclare/new-id]])")
                   outcomes (db/q '[:find ?source ?error
                                    :in $ ?id
                                    :where [?turn :seon.turn/id ?id]
                                    [?evaluation :seon.cluster.eval/run ?turn]
                                    [?evaluation :seon.cluster.eval/source ?source]
                                    [(get-else $ ?evaluation :seon.cluster.eval/error "") ?error]]
                                  @connection turn-id)]
               (assert (= 2 (count outcomes)) (pr-str outcomes))
               (assert (every? (comp empty? second) outcomes) (pr-str outcomes))
               (doseq [schema-key [:example/order :example/amount :example/customer
                            :example/order-row :redeclare/new-id :redeclare/new-row]]
                 (assert (string? (:seon.schema/form
                                   (db/pull @connection [:seon.schema/form]
                                            [:seon.schema/key schema-key])))
                         (str "Missing declaration " schema-key)))
               (assert (= 4 (db/q '[:find (count ?e) . :where [?e :example/order]]
                                  @connection))))))
          (println "schema-redeclare: seed-adopt-seed-new passed"))
        (finally (boot/stop! instance))))
    (finally (support/delete-recursively! root))))

(deftest ^{:seon.test/fixture-observation
           "Real development adoption reloads record definitions; an isolated published root and child JVM keep those classes out of the shared worker."
           :seon.test/long "Publish, boot, seed, adopt and seed again in an isolated JVM to observe schema survival across record reload."
           :seon.test/long-ms 300000}
  seeded-schema-families-survive-development-adoption
  (let [root (.getCanonicalPath (io/file "tmp" (str "schema-redeclare-test-" (random-uuid))))
        base (System/getProperty "seon.test.published-base")
        argv (cond-> [(str (io/file (System/getProperty "java.home") "bin" "java"))]
               base (conj (str "-Dseon.test.published-base=" base)))
        result (operator.process/run-process!
                {:seon.operator.subprocess/argv
                 (into argv ["-cp" (System/getProperty "java.class.path")
                             "clojure.main" "-e"
                             (pr-str `(do (require 'seon.schema-redeclare-test)
                                          (adoption-probe! ~root)
                                          (shutdown-agents)))])
                 :seon.operator.subprocess/deadline-ms
                 (:seon.test/long-ms
                  (meta #'seeded-schema-families-survive-development-adoption))
                 :seon.operator.subprocess/merge-error? true})]
    (is (= 0 (:seon.operator.subprocess/exit result))
        (:seon.operator.subprocess/output result))
    (is (str/includes? (:seon.operator.subprocess/output result)
                       "schema-redeclare: seed-adopt-seed-new passed")
        (:seon.operator.subprocess/output result))))
