(ns seon.sci.desk-test
  "Recurring acceptance for agent-scoped SCI desk facts."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.cluster.loop :as loop]
            [seon.cluster.run :as run]
            [seon.config :as config]
            [seon.db :as db]
            [seon.operator.runtime]
            [seon.sci.eval :as eval]
            [seon.test-support :as test-support])
  (:import [java.io File]
           [java.util.concurrent TimeUnit]))

(def ^:private caps (config/result-caps (config/defaults)))
(def ^:private child-backstop-seconds 180)

(defn- start-child!
  [mode database-path store-id output-path log-path]
  (let [java-command (.getPath
                      (File. (System/getProperty "java.home") "bin/java"))]
    (-> (ProcessBuilder.
         ^java.util.List
         [java-command
          "-cp" (System/getProperty "java.class.path")
          "clojure.main" "-m" "seon.sci.desk-child"
          mode database-path (str store-id) output-path])
        (.redirectErrorStream true)
        (.redirectOutput (File. log-path))
        (.start))))

(defn- await-file!
  [path process]
  (loop [attempt 0]
    (cond
      (.exists (File. path)) true
      (not (.isAlive process)) false
      (< attempt (* child-backstop-seconds 40))
      (do (Thread/sleep 25) (recur (inc attempt)))
      :else false)))

(defn- assert-child-exit!
  [process mode log-path]
  (when-not (.waitFor process child-backstop-seconds TimeUnit/SECONDS)
    (.destroyForcibly process)
    (throw (ex-info "Desk proof child exceeded its backstop."
                    {:seon.sci.desk/mode mode})))
  (when-not (zero? (.exitValue process))
    (throw (ex-info "Desk proof child failed."
                    {:seon.sci.desk/mode mode
                     :seon.sci.desk/exit (.exitValue process)
                     :seon.sci.desk/output (slurp log-path)}))))

(defn- evaluate!
  [ctx namespace-name source]
  (eval/evaluate
   {:seon.cluster.run.form/source source
    :seon.cluster.run.form/ns [:seon.ns/name namespace-name]
    :seon.sci.eval/ctx ctx
    :seon.sci.admit/caps caps
    :seon.sci.eval/time-limit-ms 30000
    :seon.config/on-core-error :panic}))

(defn- desk-row
  [agent-id namespace-name intern-name attributes]
  (let [qualified (str (symbol (str namespace-name) (str intern-name)))]
    (merge
     {:seon.def/key (pr-str [agent-id qualified])
      :seon.def/id qualified
      :seon.def/agent [:seon.cluster.agent/id agent-id]
      :seon.def/ns [:seon.ns/name namespace-name]
      :seon.def/name intern-name
      :seon.def/ordinal 0
      :seon.schema.admission/source :agent}
     attributes)))

(defn- function-root-edn
  [namespace-name source]
  (let [ctx (sci/init {})]
    (sci/add-namespace! ctx namespace-name {})
    (sci/binding [sci/ns (sci/create-ns namespace-name)]
      (sci/eval-string* ctx source))
    (binding [*print-meta* true]
      (pr-str
       (first
        (sci/var-root-data
         ctx [(symbol (str namespace-name) "helper")]))))))

(defn- settle!
  [connection agent-id run-id ordinal evaluated]
  (let [stored (second
                (run/settlement-projection
                 {:seon.db/connection connection}
                 evaluated))
        rows (#'loop/desk-rows @connection agent-id stored ordinal)]
    (db/transact!
     connection
     {:tx-data
      (run/receipt-start-tx
       {:seon.cluster.run/id run-id
        :seon.cluster.eval/ordinal ordinal
        :seon.cluster.eval/at (java.util.Date.)})})
    (db/transact!
     connection
     {:tx-data
      (run/receipt-settle-tx
       {:seon.cluster.run/id run-id
        :seon.cluster.eval/ordinal ordinal
        :seon.cluster.eval/result-edn
        (:seon.cluster.eval/result-edn evaluated)
        :seon.def/rows rows})})))

(defn- turn-with-system-loader
  []
  (let [ctx (sci/fork (eval/build-base-ctx))
        loader-namespace (sci/create-ns 'desk.system-loader)
        load-system!
        (fn []
          (#'eval/install-host-namespace!
           ctx 'seon.operator.runtime
           (ns-publics 'seon.operator.runtime))
          nil)]
    (sci/add-namespace!
     ctx 'desk.system-loader
     {'load! (sci/new-var 'load! load-system!
                          {:ns loader-namespace})})
    ctx))

(deftest atom-snapshots-are-emitted-after-in-place-mutation
  (let [ctx (sci/fork (eval/build-base-ctx))
        created (evaluate! ctx 'user "(def scratch (atom 1))")
        mutated (evaluate! ctx 'user "(swap! scratch inc)")]
    (is (= [{:seon.def/id "user/scratch"
             :seon.def/atom? true
             :seon.sci.eval/value 1}]
           (mapv #(select-keys % [:seon.def/id :seon.def/atom?
                                  :seon.sci.eval/value])
                 (:seon.sci.eval/desk-defs created))))
    (is (= 2 (get-in mutated [:seon.sci.eval/desk-defs 0
                              :seon.sci.eval/value]))
        "the identical atom root still emits its newly settled snapshot")))

(deftest only-turn-authored-definitions-settle-into-the-agent-desk
  (test-support/with-database
   (fn [connection]
     (let [agent-id "desk-attribution-agent"
           run-id "desk-attribution-run"
           namespace-name 'my.agents.desk-attribution
           ctx (turn-with-system-loader)]
       (db/transact!
        connection
        {:tx-data
         [{:seon.config.eval.result/blob-threshold 32768}
          {:seon.cluster.agent/id agent-id
           :seon.cluster.agent/namespace
           {:seon.ns/name namespace-name}}]})
       (db/transact!
        connection
        {:tx-data
         (run/open-tx
          {:seon.cluster.run/id run-id
           :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
           :seon.cluster.run/opened-at (java.util.Date.)})})
       (let [foreign (evaluate! ctx namespace-name "(desk.system-loader/load!)")]
         (is (empty? (:seon.sci.eval/desk-defs foreign))
             "system namespace installation emits no agent desk candidates")
         (settle! connection agent-id run-id 0 foreign)
         (is (zero?
              (or (db/q '[:find (count ?desk) .
                          :where [?desk :seon.def/agent]]
                        @connection)
                  0))
             "the terminal receipt commits no foreign desk rows"))
       (let [authored-source "(def own-value 42)"
             authored (evaluate! ctx namespace-name authored-source)]
         (settle! connection agent-id run-id 1 authored)
         (is (= [{:seon.def/id
                  "my.agents.desk-attribution/own-value"
                  :seon.def/value-edn "42"
                  :seon.schema.admission/source :agent}]
                (db/q '[:find [(pull ?desk
                                     [:seon.def/id
                                      :seon.def/value-edn
                                      :seon.schema.admission/source]) ...]
                        :where [?desk :seon.def/agent]]
                      @connection))
             "the settled value and agent attribution persist exactly once"))))))

(deftest preexisting-bad-desk-rows-do-not-block-the-next-turn
  (test-support/with-database
   (fn [connection]
     (let [agent-id "desk-recovery-agent"
           run-id "desk-recovery-run"
           namespace-name 'my.agents.desk-recovery]
       (db/transact!
        connection
        {:tx-data
         [{:seon.config.eval.result/blob-threshold 32768}
          {:seon.cluster.agent/id agent-id
           :seon.cluster.agent/namespace
           {:seon.ns/name namespace-name}}
          (desk-row agent-id 'seon.operator.runtime 'held-flocks
                    {:seon.def/atom? true
                     :seon.def/unrestorable-reason
                     "The atom's settled value is not store-faithful."})
          (desk-row agent-id 'seon.operator.runtime 'running-instances
                    {:seon.def/atom? true
                     :seon.def/unrestorable-reason
                     "The atom's settled value is not store-faithful."})
          (desk-row agent-id 'seon.operator.runtime 'root-store-holder
                    {:seon.def/atom? true})]})
       (db/transact!
        connection
        {:tx-data
         (run/open-tx
          {:seon.cluster.run/id run-id
           :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
           :seon.cluster.run/opened-at (java.util.Date.)})})
       (let [restored
             (eval/fork-for-turn
              {:seon.sci.eval/ctx (eval/cluster-ctx @connection connection)
               :seon.db/db @connection
               :seon.db/connection connection
               :seon.cluster.agent/id agent-id})
             evaluation
             (evaluate! (:seon.sci.eval/ctx restored) namespace-name "(+ 1 2)")]
         (is (= ["could not restore `held-flocks`: The atom's settled value is not store-faithful."
                 "could not restore `root-store-holder`: desk row has no defining form or stored value"
                 "could not restore `running-instances`: The atom's settled value is not store-faithful."]
                (:seon.sci.eval/desk-notices restored)))
         (is (= 3 (:seon.sci.admit/value evaluation))
             "the first evaluation runs after malformed desk rows")
         (settle! connection agent-id run-id 0 evaluation)
         (is (= (:seon.cluster.eval/result-edn evaluation)
                (db/q '[:find ?result .
                        :in $ ?run-id
                        :where
                        [?receipt :seon.cluster.eval/run ?run]
                        [?run :seon.cluster.run/id ?run-id]
                        [?receipt :seon.cluster.eval/ordinal 0]
                        [?receipt :seon.cluster.eval/result-edn ?result]]
                      @connection run-id))
             "the recovered turn settles its first receipt"))))))

(deftest turn-forks-rehydrate-only-the-selected-agent-and-state-loss
  (test-support/with-database
   (fn [connection]
     (let [namespace-name 'my.agents.desk
           agent-a "desk-a"
           agent-b "desk-b"]
       (db/transact!
        connection
        {:tx-data
         [{:seon.cluster.agent/id agent-a
           :seon.cluster.agent/namespace
           {:seon.ns/name namespace-name}}
          {:seon.cluster.agent/id agent-b
           :seon.cluster.agent/namespace
           {:seon.ns/name 'my.agents.other}}
          (desk-row agent-a namespace-name 'helper#root
                    {:seon.def/value-edn
                     (function-root-edn
                      namespace-name "(def helper (fn [x] (inc x)))")})
          (desk-row agent-a namespace-name 'data
                    {:seon.def/value-edn "{:answer 42}"})
          (desk-row agent-a namespace-name 'scratch
                    {:seon.def/value-edn "7" :seon.def/atom? true})
          (desk-row agent-a namespace-name 'lost
                    {:seon.def/unrestorable-reason "not store-faithful"})
          (desk-row agent-b 'my.agents.other 'data
                    {:seon.def/value-edn "99"})]})
       (let [base (eval/cluster-ctx @connection connection)
             eval-form-calls (atom 0)
             original-eval-form sci/eval-form
             a (with-redefs [sci/eval-form
                             (fn [& args]
                               (swap! eval-form-calls inc)
                               (apply original-eval-form args))]
                 (eval/fork-for-turn
                  {:seon.sci.eval/ctx base
                   :seon.db/db @connection
                   :seon.db/connection connection
                   :seon.cluster.agent/id agent-a}))
             ctx (:seon.sci.eval/ctx a)
             resolve-root #(some-> (sci/resolve ctx %) deref)]
         (is (zero? @eval-form-calls)
             "fact rehydration performs zero authored-form evaluations")
         (is (nil? (sci/resolve base 'my.agents.desk/data))
             "the live base remains program-only")
         (is (= 5 ((resolve-root 'my.agents.desk/helper) 4)))
         (is (= {:answer 42} (resolve-root 'my.agents.desk/data)))
         (is (= 7 @(resolve-root 'my.agents.desk/scratch)))
         (is (= {:seon.def/id "my.agents.desk/lost"
                 :seon.def/unrestorable-reason "not store-faithful"}
                (resolve-root 'my.agents.desk/lost))
             "unsupported roots install one flat unrestorable value")
         (swap! (resolve-root 'my.agents.desk/scratch) inc)
         (let [next-fork
               (eval/fork-for-turn
                {:seon.sci.eval/ctx base
                 :seon.db/db @connection
                 :seon.db/connection connection
                 :seon.cluster.agent/id agent-a})]
           (is (= 7 @(some-> (sci/resolve (:seon.sci.eval/ctx next-fork)
                                         'my.agents.desk/scratch)
                             deref))
               "each fork receives a fresh atom at the settled snapshot"))
         (is (= ["could not restore `lost`: not store-faithful"
                 "restored `scratch` from its last settled value"]
                (:seon.sci.eval/desk-notices a))))
       (testing "clearing is explicit and the next turn takes the same path"
         (db/transact! connection
                       {:tx-data
                        (run/clear-desk-tx
                         {:seon.def/agent
                          [:seon.cluster.agent/id agent-a]})})
         (let [after-clear
               (eval/fork-for-turn
                {:seon.sci.eval/ctx (eval/cluster-ctx @connection connection)
                 :seon.db/db @connection
                 :seon.db/connection connection
                 :seon.cluster.agent/id agent-a})]
           (is (nil? (sci/resolve (:seon.sci.eval/ctx after-clear)
                                  'my.agents.desk/data)))
           (is (empty? (:seon.sci.eval/desk-notices after-clear)))))))))

(deftest restore-ladder-retains-facts-and-forces-atom-snapshots
  (test-support/with-database
   (fn [connection]
     (db/transact! connection
                   {:tx-data [{:seon.config.eval.result/blob-threshold 32768}]})
     (let [ordinary
           {:seon.sci.eval/desk-defs
            [{:seon.def/id "user/data"
              :seon.def/ns [:seon.ns/name 'user]
              :seon.def/name 'data
              :seon.sci.eval/value {:answer 42}}]
            :seon.sci.admit/record
            {:seon.eval/outcome :ok :seon.eval/host-interop-count 0}}
           atom-evaluation
           (assoc-in ordinary [:seon.sci.eval/desk-defs 0]
                     (assoc (get-in ordinary [:seon.sci.eval/desk-defs 0])
                            :seon.def/id "user/scratch"
                            :seon.def/name 'scratch
                            :seon.def/atom? true
                            :seon.sci.eval/value 9))
           store-values (fn [connection evaluation]
                          (second
                           (run/settlement-projection
                            {:seon.db/connection connection}
                            evaluation)))
           stored-ordinary (store-values connection ordinary)
           stored-atom (store-values connection atom-evaluation)
           ordinary-row (first (#'loop/desk-rows @connection "agent"
                                                   stored-ordinary 0))
           atom-row (first (#'loop/desk-rows @connection "agent"
                                               stored-atom 1))]
       (is (= "{:answer 42}" (:seon.def/value-edn ordinary-row)))
       (is (true? (:seon.def/atom? atom-row)))
       (is (= "9" (:seon.def/value-edn atom-row)))))))

(deftest ^{:seon.test/long
           "80.213 s pool: settle defs, SIGKILL the writer JVM, restart, restore, and explicitly clear."}
  desk-survives-kill-9-and-explicit-clear
  (let [root (str "tmp/desk-kill/" (random-uuid))
        database-path (str root "/database")
        store-id (random-uuid)
        ready-path (str root "/committed.edn")
        result-path (str root "/restored.edn")
        writer-log (str root "/writer.log")
        reader-log (str root "/reader.log")]
    (.mkdirs (File. root))
    (try
      (let [writer (start-child! "write" database-path store-id ready-path
                                 writer-log)
            ready? (await-file! ready-path writer)]
        (when-not ready?
          (.destroyForcibly writer)
          (.waitFor writer 30 TimeUnit/SECONDS)
          (throw
           (ex-info "Writer did not settle its desk before the backstop."
                    {:seon.sci.desk/output
                     (when (.exists (File. writer-log))
                       (slurp writer-log))})))
        (is (= {:wrapper-calls 1}
               (edn/read-string (slurp ready-path)))
            "the authored wrapper executed exactly once")
        (.destroyForcibly writer)
        (is (.waitFor writer 30 TimeUnit/SECONDS))
        (is (not (.isAlive writer)) "the exact writer JVM was forcibly killed"))
      (let [reader (start-child! "read-clear" database-path store-id
                                 result-path reader-log)]
        (assert-child-exit! reader "read-clear" reader-log))
      (is (= {:helper 5
              :contracted 42
              :data {:answer 42}
              :atom 7
              :eval-form-calls 0
              :notices
              ["could not restore `lost`: The SCI function root contains a value without a faithful stored representation."
               "restored `scratch` from its last settled value"]
              :desk-count 0
              :data-after-clear nil
              :notices-after-clear []}
             (edn/read-string (slurp result-path))))
      (finally
        (test-support/delete-recursively! root)))))
