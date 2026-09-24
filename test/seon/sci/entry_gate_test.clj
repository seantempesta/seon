(ns seon.sci.entry-gate-test
  "Entry into an agent's SCI context: every program namespace carries its
  reader context, a contracted definition is armed at evaluation, and a
  definition without a contract is refused before it evaluates."
  (:require [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]))

(defn- evaluate-in
  [ctx source]
  (sci.eval/evaluate
   {:seon.cluster.eval/source source
    :seon.cluster.eval/ns [:seon.ns/name 'scratch.entry-gate]
    :seon.sci.eval/ctx ctx
    :seon.sci.admit/caps (config/result-caps config/defaults)
    :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)
    :seon.config/on-core-error :panic}))

(deftest every-program-namespace-carries-its-stored-aliases
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           row (db/pull (db/db connection) '[{:seon.ns/aliases [*]}]
                        [:seon.ns/name 'seon.note])
           stored (into {} (map (juxt :seon.ns.alias/local :seon.ns.alias/target-ns))
                        (:seon.ns/aliases row))]
       (is (= 'seon.db (get stored 'db)) "the fixture's seon.note row declares db")
       (is (= stored
              (:seon.sci.admit/value
               (evaluate-in ctx "(into {} (map (fn [[k v]] [k (ns-name v)])) (ns-aliases 'seon.note))")))
           "an uninterpreted file namespace has its row's aliases in SCI")))))

(deftest a-contracted-definition-is-armed-before-any-commit
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           defined (evaluate-in ctx (str "(defn twice \"Double x.\" "
                                         "{:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x))"))]
       (is (= :ok (get-in defined [:seon.sci.admit/record :seon.eval/outcome])))
       (is (= 4 (:seon.sci.admit/value (evaluate-in ctx "(twice 2)"))))
       (let [refused (:seon.sci.admit/value (evaluate-in ctx "(twice \"a\")"))]
         (is (= :input (:seon.instrument/check refused))
             (pr-str refused)))))))
