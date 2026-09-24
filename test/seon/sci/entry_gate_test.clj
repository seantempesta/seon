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
  ([ctx source] (evaluate-in ctx source (config/result-caps config/defaults)))
  ([ctx source caps]
  (sci.eval/evaluate
   {:seon.cluster.eval/source source
    :seon.cluster.eval/ns [:seon.ns/name 'scratch.entry-gate]
    :seon.sci.eval/ctx ctx
    :seon.sci.admit/caps caps
    :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms config/defaults)
    :seon.config/on-core-error :panic})))

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

(deftest a-definition-without-a-contract-is-refused-before-evaluation
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)]
       (evaluate-in ctx (str "(defn twice \"Double x.\" "
                             "{:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x))"))
       (doseq [source ["(defn bare \"No contract.\" [x] (inc x))"
                       "(defn twice \"No contract.\" [x] (* 3 x))"]]
         (let [refusal (:seon.sci.admit/value (evaluate-in ctx source))]
           (is (= 'seon.sci.eval/evaluate (:seon.error/operation refusal)) source)
           (is (= :seon.fn/spec (:seon.sci.eval/missing-declaration refusal)) source)))
       (is (= 'absent (:seon.sci.admit/value
                       (evaluate-in ctx "(if (resolve 'bare) 'present 'absent)")))
           "the refused definition never entered the context")
       (is (= 4 (:seon.sci.admit/value (evaluate-in ctx "(twice 2)")))
           "the refused redefinition left the armed root in place")
       (is (= :input
              (:seon.instrument/check
               (:seon.sci.admit/value (evaluate-in ctx "(twice \"a\")")))))))))

(deftest printed-output-rides-with-the-value-under-the-storage-bound
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           caps (assoc (config/result-caps config/defaults)
                       :seon.config.eval.result/max-bytes 64)
           printed (evaluate-in ctx "(do (println \"hi\") (binding [*out* *err*] (print \"é\")) 1)" caps)
           runaway (evaluate-in ctx "(do (dotimes [_ 100] (print \"0123456789\")) :done)" caps)
           refusal (:seon.sci.admit/value runaway)]
       (is (= "hi\né" (:seon.cluster.eval/output printed)) (pr-str printed))
       (is (= "1" (:seon.eval/shown printed)))
       (is (= (apply str (repeat 6 "0123456789"))
              (:seon.cluster.eval/output runaway))
           "the exact text up to the bound is kept")
       (is (= :seon.config.eval.result/max-bytes (:seon.error/member refusal)) (pr-str refusal))
       (is (= 64 (:seon.sci.admit/bound-bytes refusal)))
       (is (= 70 (:seon.sci.admit/bytes refusal)))))))
