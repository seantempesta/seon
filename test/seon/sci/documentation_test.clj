(ns seon.sci.documentation-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [seon.config :as config]
            [seon.db :as db]
            [seon.program :as program]
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
       (is (= #{:summary :body :example :arglists :in :out} (set (keys row))))
       (is (= (:seon.fn/doc (db/pull @connection [:seon.fn/doc]
                                    [:seon.fn/sym "my.agent/settings!"]))
              (:summary row)))
       (is (= [:cat :my.agent/settings-request]
              (:in (first (filter #(= 'my.agent/settings! (:sym %)) rows)))))
       (is (= :map (first (get-in directory-value [:schemas :my.agent/settings-request]))))
       (is (= :map (first (second (:in row)))))
       (doseq [target ["my.message/send" "my.agent/done" "my.plan" "my.note"]
               :let [doc (:seon.sci.admit/value (run (str "(doc " target ")")))]]
         (is (= (cond-> #{:summary :body :example :in :out}
                  (namespace (symbol target)) (conj :arglists))
                (set (keys doc))))
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

(deftest directory-observes-a-function-installed-after-context-acquisition
  (support/with-database
   (fn [connection]
     (is (:db-after (db/transact! connection
                                 [{:seon.ns/name 'fixture.own-functions}])))
     (let [ctx (support/fork-cluster-ctx connection)
           configuration (support/effective-config)
           captured (atom [])
           run (fn [source]
                 (reset! captured [])
                 (binding [db/*read-evidence-sink* captured]
                   (evaluation/evaluate
                    {:seon.sci.eval/ctx ctx
                     :seon.db/db @connection
                     :seon.db/connection connection
                     :seon.cluster.eval/ns [:seon.ns/name 'fixture.own-functions]
                     :seon.cluster.eval/source source
                     :seon.sci.admit/caps (config/result-caps configuration)
                     :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms configuration)
                     :seon.config/on-core-error :panic})))
           initial (run "(dir fixture.own-functions)")
           evidence (db/read-evidence @captured)
           defined (run "(defn identity-number \"Return the number.\nA durable function.\" {:malli/schema [:=> [:cat :int] :int]} [number] number)")
           row (:seon.program/row defined)]
       (is (= [] (get-in initial [:seon.sci.admit/value :functions])))
       (is (seq evidence))
       (is (true? (db/read-evidence-current? @connection evidence)))
       (is (nil? (:seon.cluster.eval/error defined)) (pr-str defined))
       (is (= "fixture.own-functions/identity-number" (:seon.fn/sym row)))
       (let [written (db/transact! connection
                                   [{:seon.ns/name 'fixture.own-functions}
                                    (program/canonical-row row)])]
         (is (:db-after written) (pr-str written))
         (when (:db-after written)
           (evaluation/install-evaluated-rows!
            {:seon.sci.eval/ctx ctx :seon.db/db (:db-after written)
             :seon.sci.eval/installations
             [{:seon.program/row row :seon.sci.eval/evaluation defined}]})))
       (is (false? (db/read-evidence-current? @connection evidence)))
       (let [directory (run "(dir fixture.own-functions)")]
         (is (nil? (:seon.cluster.eval/error directory)) (pr-str directory))
         (is (= [{:sym 'fixture.own-functions/identity-number
                  :arglists '([number])
                  :doc "Return the number."
                  :in [:cat :int] :out :int}]
                (get-in directory [:seon.sci.admit/value :functions]))))
       (doseq [source ["(doc identity-number)"
                       "(doc fixture.own-functions/identity-number)"]]
         (let [documented (run source)]
           (is (= {:summary "Return the number." :body "A durable function."
                   :example "" :arglists '([number]) :in [:cat :int] :out :int}
                  (:seon.sci.admit/value documented)))
           (is (seq (db/read-evidence @captured)))))))))

(deftest retained-context-receives-the-current-repl-macro-through-its-core-alias
  (support/with-database
   (fn [connection]
     (let [base (support/fork-cluster-ctx connection)
           request {:seon.sci.eval/ctx base :seon.db/db @connection
                    :seon.agent/id "documentation"}
           retained (:seon.sci.eval/ctx (evaluation/fork-for-turn request))
           old-dir @(sci/resolve retained 'clojure.core/dir)]
       (#'evaluation/install-program-doc! base @connection (schema/handed-projection))
       (is (not (identical? old-dir @(sci/resolve base 'clojure.core/dir))))
       (let [updated (:seon.sci.eval/ctx
                      (evaluation/fork-for-turn
                       (assoc request :seon.sci.eval/agent-ctx retained)))]
         (is (identical? retained updated))
         (doseq [function ['dir 'doc]
                 namespace-name ['clojure.core 'clojure.repl]]
           (let [qualified (symbol (str namespace-name) (str function))]
             (is (identical? @(sci/resolve base qualified)
                             @(sci/resolve updated qualified))
                 (str qualified " receives its current macro root")))))))))

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
