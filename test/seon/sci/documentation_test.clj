(ns seon.sci.documentation-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.sci.eval :as evaluation]
            [seon.test-support :as support]))

(deftest documentation-is-returned-data-without-a-second-printed-copy
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           run (fn [source]
                 (evaluation/evaluate
                  {:seon.sci.eval/ctx ctx
                   :seon.cluster.eval/source source
                   :seon.sci.admit/caps (config/result-caps (config/defaults))
                   :seon.sci.eval/time-limit-ms 2000
                   :seon.config/on-core-error :panic}))
           directory (run "(dir my.agent)")
           documentation (run "(doc my.agent/settings!)")
           missing (run "(doc my.agent/does-not-exist)")
           missing-ns (run "(dir missing.namespace)")
           empty-ns (run "(do (in-ns 'fixture.empty-doc) (dir fixture.empty-doc))")
           rows (:seon.sci.admit/value directory)
           row (:seon.sci.admit/value documentation)]
       (is (seq rows))
       (is (some #(= "my.agent/settings!" (:seon.fn/sym %)) rows))
       (is (every? :seon.fn/spec rows))
       (is (every? #(not (str/includes? (:seon.fn/doc %) "\n")) rows))
       (is (= "my.agent/settings!" (:seon.fn/sym row)))
       (is (= (:seon.fn/doc (db/pull @connection [:seon.fn/doc]
                                    [:seon.fn/sym "my.agent/settings!"]))
              (:seon.fn/doc row)))
       (is (seq (:seon.fn/arities row)))
       (is (seq (get-in row [:seon.fn/arities 0 :seon.fn.arity/input-refs])))
       (doseq [result [directory documentation missing missing-ns]]
         (is (empty? (:seon.cluster.eval/output result))))
       (is (= :seon.sci.eval/documentation-unavailable
              (get-in missing [:seon.sci.admit/value :seon.error/kind])))
       (is (= :seon.sci.eval/documentation-unavailable
              (get-in missing-ns [:seon.sci.admit/value :seon.error/kind])))
       (is (= [] (:seon.sci.admit/value empty-ns)))
       (is (nil? (:seon.cluster.eval/error empty-ns)))))))
