(ns seon.sci.documentation-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.sci.eval :as evaluation]
            [seon.schema :as schema]
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
           directory-value (:seon.sci.admit/value directory)
           rows (:functions directory-value)
           row (:seon.sci.admit/value documentation)]
       (is (seq rows))
       (is (some #(= 'my.agent/settings! (:sym %)) rows))
       (is (every? #(and (:in %) (:out %)) rows))
       (is (every? #(not (str/includes? (:doc %) "\n")) rows))
       (is (= #{:summary :body :example :in :out} (set (keys row))))
       (is (= (:seon.fn/doc (db/pull @connection [:seon.fn/doc]
                                    [:seon.fn/sym "my.agent/settings!"]))
              (:summary row)))
       (is (= [:cat :my.agent/settings-request]
              (:in (first (filter #(= 'my.agent/settings! (:sym %)) rows)))))
       (is (= :map (first (get-in directory-value [:schemas :my.agent/settings-request]))))
       (is (= :map (first (second (:in row)))))
       (doseq [target ["my.message/send" "my.agent/done" "my.plan" "my.note"]
               :let [doc (:seon.sci.admit/value (run (str "(doc " target ")")))]]
         (is (= #{:summary :body :example :in :out} (set (keys doc))))
         (is (not (str/blank? (:summary doc))))
         (is (not (str/blank? (:body doc))))
         (is (seq (read-string (:example doc)))))
       (doseq [result [directory documentation missing missing-ns]]
         (is (empty? (:seon.cluster.eval/output result))))
       (is (= :seon.sci.eval/documentation-unavailable
              (get-in missing [:seon.sci.admit/value :seon.error/kind])))
       (is (= :seon.sci.eval/documentation-unavailable
              (get-in missing-ns [:seon.sci.admit/value :seon.error/kind])))
       (is (= {:schemas {} :functions []} (:seon.sci.admit/value empty-ns)))
       (is (nil? (:seon.cluster.eval/error empty-ns)))))))

(deftest a-contract-mistake-carries-the-same-documentation-as-doc
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           run (fn [source]
                 (evaluation/evaluate
                  {:seon.sci.eval/ctx ctx :seon.db/db @connection
                   :seon.cluster.eval/source source
                   :seon.sci.admit/caps (config/result-caps (config/defaults))
                   :seon.sci.eval/time-limit-ms 10000
                   :seon.config/on-core-error :panic}))
           documentation (:seon.sci.admit/value (run "(doc my.message/send)"))
           failed (run "(my.message/send {:my.message/to 42 :my.message/content \"Hello\"})")
           value (:seon.sci.admit/value failed)]
       (is (= :seon.instrument/contract-violated (:seon.error/kind value)) (pr-str failed))
       (is (= documentation (:seon.error/doc value)) (pr-str failed))
       (is (schema/valid-candidate-value? :seon.error/value value))
       (is (str/includes? (:seon.eval/shown failed) ":example"))
       (is (str/includes? (:seon.eval/shown failed) "Return an addressed message"))
       (is (empty? (db/q '[:find ?m :where [?m :seon.message/id]] @connection)))
       (let [unrelated (run "(/ 1 0)")]
         (is (:seon.cluster.eval/error unrelated))
         (is (not (find (:seon.sci.admit/value unrelated) :seon.error/doc))))))))
