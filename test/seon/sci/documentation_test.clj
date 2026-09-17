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

(deftest documentation-keeps-named-contracts-and-guarded-result-shapes
  (support/with-database
    (fn [connection]
      (let [doc (evaluation/documentation-value (db/db connection) 'seon.db/pull 'seon.db/pull)
            output (pr-str (:out doc))
            contract (#'evaluation/documentation-contract
                      (db/db connection)
                      {:seon.fn/spec
                       "[:function [:=> [:cat :int] :string [:fn {:error/message \"pair relation\"} clojure.core/vector?]] [:=> [:cat [:or :string :int] :boolean] :keyword]]"})]
        (is (not (:seon.error/kind doc)))
        (is (not (str/includes? output ":gen/gen")) output)
        (is (= [[:cat :int] [:cat [:or :string :int] :boolean]] (:in contract)))
        (is (= [:string :keyword] (:out contract)))))))

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
       (is (= #{:summary :body :example :arglists :in :out :supplied} (set (keys row))))
       (is (= (first (str/split-lines (:seon.fn/doc (db/pull (db/db connection) [:seon.fn/doc]
                                    [:seon.fn/sym "my.agent/settings!"]))))
              (:summary row)))
       (is (= (:in row)
              (:in (first (filter #(= 'my.agent/settings! (:sym %)) rows)))))
       (is (= :map (first (get-in directory-value [:schemas :my.agent/settings-request]))))
       (is (= :map (first (second (:in row)))))
       (doseq [target ["my.message/send" "my.agent/done" "my.plan" "my.note"]
               :let [doc (:seon.sci.admit/value (run (str "(doc " target ")")))]]
         (is (= (cond-> #{:summary :body :example :in :out}
                  (namespace (symbol target)) (conj :arglists :supplied))
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
                     :seon.db/db (db/db connection)
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
       (is (true? (db/read-evidence-current? (db/db connection) evidence)))
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
       (is (false? (db/read-evidence-current? (db/db connection) evidence)))
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
           request {:seon.sci.eval/ctx base :seon.db/db (db/db connection)
                    :seon.agent/id "documentation"}
           retained (:seon.sci.eval/ctx (evaluation/fork-for-turn request))
           old-dir @(sci/resolve retained 'clojure.core/dir)]
       (#'evaluation/install-program-doc! base (db/db connection) (schema/handed-projection))
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

(deftest bare-test-macros-resolve-without-namespace-referrals
  (support/with-database
   (fn [connection]
     (is (:db-after (db/transact! connection
                                 [{:seon.agent/id "bare-tests"
                                   :seon.agent/namespace
                                   {:seon.ns/name 'fixture.bare-tests}}])))
     (doseq [ctx [(evaluation/build-base-ctx)
                 (support/fork-cluster-ctx connection)]]
       (sci/add-namespace! ctx 'fixture.bare-tests {})
       (sci/binding [sci/ns (sci/create-ns 'fixture.bare-tests)]
         (doseq [test-symbol ['deftest 'is]]
           (is (some? (sci/resolve ctx test-symbol)) (str test-symbol)))
         (sci/eval-string* ctx "(deftest bare-arithmetic (is (= 2 (+ 1 1))))")
         (is (fn? (:test (meta (sci/resolve ctx 'bare-arithmetic)))))))
     (let [ctx (support/fork-cluster-ctx connection)
           result (evaluation/evaluate
                   {:seon.sci.eval/ctx ctx
                    :seon.db/db (db/db connection) :seon.db/connection connection
                    :seon.agent/id "bare-tests"
                    :seon.cluster.eval/ns [:seon.ns/name 'fixture.bare-tests]
                    :seon.cluster.eval/source "(deftest durable-arithmetic (is (= 2 (+ 1 1))))"
                    :seon.sci.admit/caps (config/result-caps (support/effective-config))
                    :seon.sci.eval/time-limit-ms 10000
                    :seon.config/on-core-error :panic})
           row (:seon.program/row result)]
       (is (nil? (:seon.cluster.eval/error result)) (pr-str result))
       (is (= "fixture.bare-tests/durable-arithmetic" (:seon.test/sym row)))
       (let [written (db/transact! connection [(program/canonical-row row)])]
         (is (:db-after written) (pr-str written))
         (is (= (:seon.test/source row)
                (:seon.test/source
                 (db/pull (db/db connection) [:seon.test/source]
                          [:seon.test/sym "fixture.bare-tests/durable-arithmetic"])))))))))

(deftest retained-context-receives-new-bare-test-referrals
  (support/with-database
   (fn [connection]
     (is (:db-after (db/transact! connection
                                 [{:seon.agent/id "retained-tests"
                                   :seon.agent/namespace
                                   {:seon.ns/name 'fixture.retained-tests}}])))
     (let [base (support/fork-cluster-ctx connection)
           _ (sci/eval-string* base
                              "(do (ns-unmap 'clojure.core 'deftest) (ns-unmap 'clojure.core 'is))")
           request {:seon.sci.eval/ctx base :seon.db/db (db/db connection)
                    :seon.agent/id "retained-tests"}
           retained (:seon.sci.eval/ctx (evaluation/fork-for-turn request))]
       (sci/binding [sci/ns (sci/create-ns 'fixture.retained-tests)]
         (is (nil? (sci/resolve retained 'deftest)))
         (is (nil? (sci/resolve retained 'is))))
       (#'evaluation/install-program-doc! base (db/db connection) (schema/handed-projection))
       (let [updated (:seon.sci.eval/ctx
                      (evaluation/fork-for-turn
                       (assoc request :seon.sci.eval/agent-ctx retained)))]
         (is (identical? retained updated))
         (sci/binding [sci/ns (sci/create-ns 'fixture.retained-tests)]
           (doseq [test-symbol ['deftest 'is]]
             (is (some? (sci/resolve updated test-symbol)) (str test-symbol)))
           (sci/eval-string* updated "(deftest retained-arithmetic (is (= 4 (* 2 2))))")
           (is (fn? (:test (meta (sci/resolve updated 'retained-arithmetic)))))))))))

(deftest a-contract-mistake-carries-the-same-documentation-as-doc
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           run (fn [source]
                 (evaluation/evaluate
                  {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
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
       (is (str/includes? (:seon.eval/shown failed) "Example:"))
       (is (str/includes? (:seon.eval/shown failed) (:example documentation)))
       (is (empty? (db/q '[:find ?m :where [?m :seon.message/id]] (db/db connection))))
       (let [unrelated (run "(/ 1 0)")]
         (is (:seon.cluster.eval/error unrelated))
         (is (not (find (:seon.sci.admit/value unrelated) :seon.error/doc))))))))

(deftest an-absent-declaration-is-a-typed-statement-not-an-empty-one
  ;; `doc` and `dir` used to render an absent :seon.fn/spec as [], absent
  ;; arglists as () and an absent docstring as "": three claims the row does
  ;; not support. An agent reads this surface before deciding how to call a
  ;; function, so absence must say so (AGENTS.md section 2.4).
  (support/with-database
   (fn [connection]
     (support/transacted!
      connection
      [(assoc (support/program-fn-row 'my.note/undeclared)
              :seon.fn/private? false)])
     (let [database (db/db connection)
           doc (evaluation/documentation-value database
                                               'my.note/undeclared
                                               'my.note/undeclared)
           row (some #(when (= 'my.note/undeclared (:sym %)) %)
                     (:functions (evaluation/directory-value database 'my.note false)))]
       (is (not (:seon.error/kind doc)))
       (is (some? row) "the undeclared row is still listed by dir")
       (doseq [[label value attribute]
               [["doc :in" (:in doc) :seon.fn/spec]
                ["doc :out" (:out doc) :seon.fn/spec]
                ["doc arglists" (:arglists doc) :seon.fn/arglists]
                ["dir arglists" (:arglists row) :seon.fn/arglists]]]
         (is (= :seon.sci.eval/declaration-absent (:seon.error/kind value)) label)
         (is (str/includes? (:seon.error/message value) (str attribute)) label))
       (is (not= [] (:in doc)) "an absent contract never reads as a declared empty one")
       (is (not= "" (:summary doc)))
       (is (str/includes? (:summary doc) (str :seon.fn/doc)))
       (is (str/includes? (:doc row) (str :seon.fn/doc)))))))
