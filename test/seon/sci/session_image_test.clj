(ns seon.sci.session-image-test
  "Recurring acceptance for the database-backed SCI session image."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [sci.core :as sci]
            [sci.impl.vars :as sci.vars]
            [seon.config :as config]
            [seon.cluster.loop :as loop]
            [seon.sci.eval :as eval]
            [seon.test-support :as test-support])
  (:import [java.io File]
           [java.util.concurrent TimeUnit]))

(def ^:private caps (config/result-caps (config/defaults)))

(defn- with-memory-database
  [body]
  ;; Session values include store-global blob keys, so these cases use an
  ;; isolated memory store rather than the shared branch base. Persistence
  ;; belongs only to the two-child-JVM test below, where it is the subject.
  (test-support/with-database
   {::test-support/fresh-store? true}
   body))

(defn- run-child!
  [mode database-path store-id output-path]
  (let [java-command (.getPath
                      (File. (System/getProperty "java.home") "bin/java"))
        process (-> (ProcessBuilder.
                     ^java.util.List
                     [java-command
                      "-cp" (System/getProperty "java.class.path")
                      "clojure.main" "-m" "seon.sci.session-image-child"
                      mode database-path (str store-id) output-path])
                    (.redirectErrorStream true)
                    (.start))]
    (when-not (.waitFor process 90 TimeUnit/SECONDS)
      (.destroyForcibly process)
      (throw (ex-info "Session-image child exceeded its backstop."
                      {:mode mode})))
    (when-not (zero? (.exitValue process))
      (throw (ex-info "Session-image child failed."
                      {:mode mode
                       :exit (.exitValue process)
                       :output (slurp (.getInputStream process))})))
    nil))

(defn- evaluate!
  [ctx namespace-name source]
  (eval/evaluate
   {:seon.cluster.run.form/source source
    :seon.cluster.run.form/ns [:seon.ns/name namespace-name]
    :seon.sci.eval/ctx ctx
    :seon.sci.admit/caps caps
    :seon.sci.eval/time-limit-ms 30000
    :seon.config/on-core-error :panic}))

(deftest ^{:seon.test/long "Persists and restores one session through two fresh JVMs."}
  two-fresh-jvms-round-trip-the-owner-session
  (let [root (str "tmp/session-fresh-jvm/" (random-uuid))
        database-path (str root "/database")
        store-id (random-uuid)
        write-output (str root "/written.edn")
        read-output (str root "/restored.edn")]
    (.mkdirs (File. root))
    (try
      (run-child! "write" database-path store-id write-output)
      (is (= :written (edn/read-string (slurp write-output))))
      (run-child! "read" database-path store-id read-output)
      (is (= {:count 200000
              :scaled 40
              :names ["Ada" "Grace"]}
             (edn/read-string (slurp read-output))))
      (finally
        (test-support/delete-recursively! root)))))

(defn- commit-evaluation!
  [connection evaluation ordinal]
  (let [stored (#'loop/store-session-values! connection evaluation)]
    (db/transact!
     connection
     {:tx-data (#'loop/session-image-tx @connection stored ordinal)})))

(deftest session-macro-without-a-row-fails-closed
  (with-memory-database
   (fn [connection]
     (let [namespace-name 'my.agents.session-macro
           _ (db/transact! connection
                         {:tx-data
                          [{:seon.config.eval.result/blob-threshold 32768}
                           {:seon.ns/name namespace-name
                            :seon.ns/source
                            "(ns my.agents.session-macro)"}]})
           ctx (eval/cluster-ctx @connection connection)
           _ (evaluate! ctx namespace-name
                        "(defmacro hidden [] '(fn [] 7))")
           evaluation (evaluate! ctx namespace-name
                                 "(def hidden-value (hidden))")
           candidate (first (:seon.sci.eval/session-defs evaluation))
           stored (#'loop/store-session-values! connection evaluation)
           row (first (#'loop/session-image-tx @connection stored 1))]
       (is (= #{'my.agents.session-macro/hidden}
              (:seon.sci.eval/unproven-called-vars candidate)))
       (is (nil? (:seon.def/source row))
           "an unstorable value is never replayed through an unproven macro")
       (is (= "Defining form calls a Var absent from the program graph."
              (:seon.def/unrestorable-reason row)))
       (is (not (contains? row :seon.sci.eval/unproven-called-vars)))))))

(deftest host-vars-remain-visible-to-capability-classification
  (with-memory-database
   (fn [connection]
     (let [namespace-name 'my.agents.host-capability
           _ (db/transact! connection
                           {:tx-data
                            [{:seon.config.eval.result/blob-threshold 32768}
                             {:seon.ns/name namespace-name
                              :seon.ns/source
                              "(ns my.agents.host-capability)"}]})
           ctx (eval/cluster-ctx @connection connection)
           capability-symbol
           (->> (db/q '[:find [?sym ...]
                        :where
                        [?function :seon.fn/sym ?sym]
                        [?function :seon.effect/capability _]]
                      @connection)
                (map symbol)
                (filter
                 (fn [function-symbol]
                   (sci/binding [sci/ns (sci/create-ns namespace-name)]
                     (instance? clojure.lang.Var
                                (sci/resolve ctx function-symbol)))))
                (sort-by str)
                first)
           _ (is (some? capability-symbol)
                 "the capability inventory supplies a host-bound Var")
           evaluation
           (evaluate! ctx namespace-name
                      (str "(def calls-capability (fn [] ("
                           capability-symbol ")))"))
           candidate (first (:seon.sci.eval/session-defs evaluation))
           referenced (:seon.sci.eval/referenced-vars candidate)
           unproven (:seon.sci.eval/unproven-called-vars candidate)]
       (is (contains? referenced capability-symbol))
       (is (contains? unproven capability-symbol))
       (is (false? (#'loop/capability-free-references?
                    @connection referenced unproven))))))
   (let [anonymous-var (clojure.lang.Var/create)
         ctx (sci/init {:namespaces {'example {'mystery anonymous-var}}})]
     (is (= #{'example/mystery}
            (#'eval/unproven-called-vars
             ctx 'user '(example/mystery)))
         "a Var without classifying metadata fails closed")))

(deftest executed-unsafe-built-ins-are-never-source-replayed
  (with-memory-database
   (fn [connection]
     (let [namespace-name 'my.agents.session-built-ins
           _ (db/transact! connection
                         {:tx-data
                          [{:seon.config.eval.result/blob-threshold 32768}
                           {:seon.ns/name namespace-name
                            :seon.ns/source
                            "(ns my.agents.session-built-ins)"}]})
           live (eval/cluster-ctx @connection connection)
           nondeterministic
           (evaluate! live namespace-name
                      (str "(def replay-symbol "
                           "(let [x (gensym \"x\")] (fn [] x)))"))
           effectful
           (evaluate! live namespace-name
                      (str "(def replay-print "
                           "(do (println \"SIDE-EFFECT\") (fn [] 1)))"))
           faithful
           (evaluate! live namespace-name "(def sampled (rand))")
           sampled-live
           @(sci/resolve live 'my.agents.session-built-ins/sampled)]
       (is (= #{'clojure.core/gensym}
              (get-in nondeterministic
                      [:seon.sci.eval/session-defs 0
                       :seon.sci.eval/nondeterministic-calls])))
       (is (= #{'clojure.core/println}
              (get-in effectful
                      [:seon.sci.eval/session-defs 0
                       :seon.sci.eval/impure-calls])))
       (doseq [[ordinal evaluation]
               (map-indexed vector [nondeterministic effectful faithful])]
         (commit-evaluation! connection evaluation ordinal))
       (let [fresh (eval/cluster-ctx @connection connection)
             replay-symbol (sci/resolve fresh
                                        'my.agents.session-built-ins/replay-symbol)
             replay-print (sci/resolve fresh
                                       'my.agents.session-built-ins/replay-print)]
         (is (false? (sci.vars/hasRoot replay-symbol)))
         (is (false? (sci.vars/hasRoot replay-print)))
         (is (= "Defining form called a nondeterministic SCI built-in."
                (:seon.def/unrestorable-reason
                 (db/pull @connection
                         [:seon.def/unrestorable-reason]
                         [:seon.def/id
                          "my.agents.session-built-ins/replay-symbol"]))))
         (is (= "Defining form called an effectful SCI built-in."
                (:seon.def/unrestorable-reason
                 (db/pull @connection
                         [:seon.def/unrestorable-reason]
                         [:seon.def/id
                          "my.agents.session-built-ins/replay-print"]))))
         (is (= sampled-live
                @(sci/resolve fresh 'my.agents.session-built-ins/sampled))
             "a faithful random value restores as data, never by re-execution"))))))

(deftest failed-evaluation-delta-restores-values-but-never-replays-source
  (with-memory-database
   (fn [connection]
     (let [namespace-name 'my.agents.failed-session
           _ (db/transact! connection
                         {:tx-data
                          [{:seon.config.eval.result/blob-threshold 32768}
                           {:seon.ns/name namespace-name
                            :seon.ns/source
                            "(ns my.agents.failed-session)"}]})
           live (eval/cluster-ctx @connection connection)
           failed
           (evaluate! live namespace-name
                      (str "(do (def kept 9) (def lost (fn [] 7)) "
                           "(throw (ex-info \"later\" {})))"))]
       (is (= :error
              (get-in failed
                      [:seon.sci.admit/record :seon.eval/outcome])))
       (is (= #{"my.agents.failed-session/kept"
                "my.agents.failed-session/lost"}
              (into #{} (map :seon.def/id)
                    (:seon.sci.eval/session-defs failed))))
       (is (= 9 @(sci/resolve live 'my.agents.failed-session/kept)))
       (is (= 7 ((deref (sci/resolve live
                                     'my.agents.failed-session/lost)))))
       (commit-evaluation! connection failed 0)
       (let [fresh (eval/cluster-ctx @connection connection)
             lost (sci/resolve fresh 'my.agents.failed-session/lost)]
         (is (= 9 @(sci/resolve fresh 'my.agents.failed-session/kept)))
         (is (false? (sci.vars/hasRoot lost)))
         (is (= "Defining evaluation did not complete successfully."
                (:seon.def/unrestorable-reason
                 (db/pull @connection
                         [:seon.def/unrestorable-reason]
                         [:seon.def/id
                          "my.agents.failed-session/lost"])))))))))

(deftest time-limited-evaluation-reports-its-earlier-def-delta
  (let [namespace-name 'my.agents.cut-session
        ctx (eval/build-base-ctx)
        evaluation
        (eval/evaluate
         {:seon.cluster.run.form/source
          "(do (def before-cut 7) (loop [] (recur)))"
          :seon.cluster.run.form/ns [:seon.ns/name namespace-name]
          :seon.sci.eval/ctx ctx
          :seon.sci.admit/caps caps
          :seon.sci.eval/time-limit-ms 25
          :seon.config/on-core-error :panic})]
    (is (= :time
           (get-in evaluation
                   [:seon.sci.admit/record :seon.eval/outcome])))
    (is (= ["my.agents.cut-session/before-cut"]
           (mapv :seon.def/id
                 (:seon.sci.eval/session-defs evaluation))))
    (is (= 7 @(sci/resolve ctx 'my.agents.cut-session/before-cut)))))

(deftest fresh-context-restores-the-forms-session-image
  (with-memory-database
   (fn [connection]
     (let [namespace-name 'my.agents.session-image
           _ (db/transact! connection
                         {:tx-data [{:seon.config.eval.result/blob-threshold
                                     32768}
                                    {:seon.ns/name namespace-name
                                     :seon.ns/source
                                     "(ns my.agents.session-image)"}]})
           live (eval/cluster-ctx @connection connection)
           sources ["(def big (vec (range 200000)))"
                    "(def names [\"Ada\" \"Grace\"])"
                    "(def limit 10)"
                    "(def scale (fn [v] (* v limit)))"
                    "(def function-map {:scale (fn [v] (* v limit))})"
                    "(def ordered (into (sorted-set) [2 1]))"
                    "(def tagged (with-meta [1 2] {:session true}))"
                    (str "(def effectful-data (do (.toUpperCase \"x\") "
                         "{:answer 42}))")
                    (str "(def dropped (do (.toUpperCase \"x\") "
                         "(fn [] 1)))")]
           evaluations (mapv #(evaluate! live namespace-name %) sources)]
       (doseq [[ordinal evaluation] (map-indexed vector evaluations)]
         (commit-evaluation! connection evaluation ordinal))
       (testing "the env diff sees a redefinition through the existing SCI Var"
         (let [redefinition (evaluate! live namespace-name "(def limit 11)")]
           (is (= ["my.agents.session-image/limit"]
                  (mapv :seon.def/id
                        (:seon.sci.eval/session-defs redefinition))))
           (commit-evaluation! connection redefinition 9)))
       (let [fresh (eval/cluster-ctx @connection connection)
             resolved #(some-> (sci/resolve fresh %) deref)]
         (is (= 200000 (count (resolved 'my.agents.session-image/big))))
         (is (= 44 ((resolved 'my.agents.session-image/scale) 4)))
         (is (= 44
                ((get (resolved 'my.agents.session-image/function-map)
                      :scale)
                 4))
             "a function nested in a map restores through its pure form")
         (is (= "Ada, Grace"
                (str/join
                 ", " (resolved 'my.agents.session-image/names))))
         (is (= clojure.lang.PersistentTreeSet
                (class (resolved 'my.agents.session-image/ordered))))
         (is (= {:session true}
                (meta (resolved 'my.agents.session-image/tagged))))
         (is (= {:answer 42}
                (resolved 'my.agents.session-image/effectful-data))
             "a faithful value touched host interop but is bound, never replayed")
         (is (some? (:seon.def/blob
                     (db/pull @connection
                             [:seon.def/blob]
                             [:seon.def/id
                              "my.agents.session-image/big"])))
             "the 200k value takes the database-configured blob path")
         (is (some? (:seon.def/value-edn
                     (db/pull @connection
                             [:seon.def/value-edn]
                             [:seon.def/id
                              "my.agents.session-image/tagged"])))
             "metadata-faithful small values bind before forms")
         (is (some? (:seon.def/value-edn
                     (db/pull @connection
                             [:seon.def/value-edn]
                             [:seon.def/id
                              "my.agents.session-image/effectful-data"]))))
         (is (= {:seon.def/source
                 "(def function-map {:scale (fn [v] (* v limit))})"}
                (db/pull @connection
                        [:seon.def/source
                         :seon.def/value-edn
                         :seon.def/blob]
                        [:seon.def/id
                         "my.agents.session-image/function-map"]))
             "nested closures force the source tier rather than a partial value")
         (is (contains? (get (sci/namespace-interns fresh) namespace-name)
                        'dropped)
             "an unrestorable name is pre-interned, never marker-bound")
         (is (= "Defining form touched host interop."
                (:seon.def/unrestorable-reason
                 (db/pull @connection
                         [:seon.def/unrestorable-reason]
                         [:seon.def/id
                          "my.agents.session-image/dropped"]))))
         )))))

(deftest two-hundred-form-image-installs-each-source-once
  (test-support/with-database
   (fn [connection]
     (let [namespace-name 'my.agents.session-cost
           rows
           (into [{:seon.ns/name namespace-name
                   :seon.ns/source "(ns my.agents.session-cost)"}]
                 (map (fn [ordinal]
                        {:seon.def/id
                         (str namespace-name "/n" ordinal)
                         :seon.def/ns [:seon.ns/name namespace-name]
                         :seon.def/name (symbol (str "n" ordinal))
                         :seon.def/source
                         (str "(def n" ordinal " " ordinal ")")
                         :seon.def/ordinal ordinal}))
                 (range 200))
           _ (db/transact! connection {:tx-data rows})
           ctx (eval/build-base-ctx)
           _ (eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db @connection})
           evaluated (atom [])
           real-eval-form sci/eval-form
           installed
           (with-redefs [sci/eval-form
                         (fn [candidate-ctx form]
                           (swap! evaluated conj form)
                           (real-eval-form candidate-ctx form))]
             (eval/install-session-image!
              {:seon.sci.eval/ctx ctx :seon.db/db @connection}))]
       (is (identical? ctx (:seon.sci.eval/ctx installed)))
       (is (empty? (:seon.sci.eval/unrestorable installed)))
       (is (= 200 (count @evaluated))
           "each admitted source row is evaluated exactly once")
       (is (= 200
              (count (filter
                      (get (sci/namespace-interns ctx) namespace-name)
                      (map #(symbol (str "n" %)) (range 200)))))
           "all 200 declared names are installed")
       (is (= 0 @(sci/resolve ctx 'my.agents.session-cost/n0)))
       (is (= 199 @(sci/resolve ctx 'my.agents.session-cost/n199)))))))

(deftest equal-large-values-share-the-content-addressed-blob
  (with-memory-database
   (fn [connection]
     (db/transact! connection
                 [{:seon.config.eval.result/blob-threshold 16}])
     (let [value (vec (range 1000))
           evaluation
           (fn [id]
             {:seon.sci.eval/session-defs
              [{:seon.def/id id
                :seon.def/ns [:seon.ns/name 'my.agents.dedup]
                :seon.def/name (symbol (last (str/split id #"/")))
                :seon.def/source "(def ignored nil)"
                :seon.sci.eval/value value
                :seon.sci.eval/referenced-vars #{}
                :seon.sci.eval/unproven-called-vars #{}}]})
           left (#'loop/store-session-values!
                 connection (evaluation "my.agents.dedup/left"))
           right (#'loop/store-session-values!
                  connection (evaluation "my.agents.dedup/right"))]
       (is (= (get-in left [:seon.sci.eval/session-defs 0
                            :seon.def/blob])
              (get-in right [:seon.sci.eval/session-defs 0
                             :seon.def/blob]))
           "two agents' equal serialized values address one blob key")))))
