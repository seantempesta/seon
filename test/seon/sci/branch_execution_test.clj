(ns seon.sci.branch-execution-test
  (:require [seon.render.value]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [sci.core :as sci]
            [seon.cache]
            [seon.config :as config]
            [seon.db :as db]
            [seon.fn :as functions]
            [seon.schema :as schema]
            [seon.sci.eval :as eval]
            [seon.sci.eval-test]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(defn- evaluate!
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/connection :symbol :string] :seon.sci.eval/evaluation]}
  [ctx connection namespace-name source]
  (let [result (support/evaluate
                {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
                 :seon.db/connection connection
                 :seon.cluster.eval/ns [:seon.ns/name namespace-name]
                 :seon.cluster.eval/source source
                 :seon.sci.eval/time-limit-ms 5000
                 :seon.sci.admit/caps (config/result-caps config/defaults)})]
    (when (:seon.cluster.eval/error result)
      (println "REALITIES-EVAL-REFUSAL" (seon.render.value/core-text (select-keys result [:seon.cluster.eval/error :seon.cluster.eval/triage-edn])))
      (throw (ex-info "Agent evaluation refused." result)))
    result))

(defn- declare!
  "The evaluator's analyzed entity goes through the turn's declaration writer."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/connection :symbol :string] :seon.program/row]}
  [ctx connection namespace-name source]
  (let [result (evaluate! ctx connection namespace-name source)
        entity (:seon.program/row result)]
    (when-not entity (throw (ex-info "No declaration was produced." result)))
    (support/transacted! connection
      (#'turn/row-tx (db/db connection) {} entity))
    entity))

(defn- on-branch
  "Use the canonical fixture's store and one captured commit. The branch's
  context environment names `cluster-name`: a world holding several clusters
  names none by itself (`seon.sci.eval/projection-state`, 6db5b2451)."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.db/database-value :seon.boot/cluster-name
                       :keyword [:fn ifn?]] :nil]}
  [connection captured cluster-name branch body]
  (d/branch! connection (d/commit-id captured) branch)
  (try
    (let [child (d/connect (assoc (:config captured) :branch branch))]
      (try
        (db/carry-connection-projection-state!
          child (eval/projection-state @child (db/carried-projection captured) cluster-name))
        (db/call-with-custody {} #(body child))
        nil
        (finally (d/release child))))
    (finally (d/delete-branch! connection branch))))

(defn- agent-revision-tx
  "An agent's change to each stored declaration in `symbols`: its doc revised,
  admitted as `:agent` and digested by the canonical `support/revision`. Only an
  agent-authored row overrides the loaded program (`overridden-row?`, 8cf62cd13);
  the writer refuses a forged digest (79b6d9abd)."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :qualified-symbol]]
                  :seon.store/transaction-data]}
  [database symbols]
  (mapv #(support/revision database [:seon.fn/sym %]
                           {:seon.fn/doc (str "Changed " %) :seon.schema.admission/source :agent})
        symbols))

(defn- refusal-of
  "The data of the refusal calling `sym` in `ctx` throws, else nil."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :qualified-symbol] [:maybe :map]]}
  [ctx sym]
  (try (sci/eval-string* ctx (str "(" sym ")")) nil
       (catch Exception failure
         (some #(when (:seon.sci.eval/refused-function (ex-data %)) (ex-data %))
               (take-while some? (iterate ex-cause failure))))))

(defn- measured
  {:malli/schema [:=> [:cat [:fn ifn?]] :map]}
  [f]
  (let [start (System/nanoTime) value (f)]
    {:value value :ms (/ (- (System/nanoTime) start) 1e6)}))

(deftest ^{:seon.test/long "Measured 14,651 ms: canonical fixture, three complete SCI acquisitions, two analyzed declarations and branch cleanup; individual acquisition 1,681 ms."
           :seon.test/long-ms 20000}
  branch-definitions-and-unchanged-callers-share-one-context
  (support/with-database
   (fn [connection]
     (db/call-with-custody {:seon.db/connection connection}
      (fn []
       (let [_ (support/seed-cluster! connection "realities-proof")
           captured (db/db connection)
           live (support/fork-cluster-ctx connection)
           leaf 'seon.sci.eval-test/cut?
           caller 'seon.sci.eval-test/ok?
           leaf-entity (db/pull captured '[*] [:seon.fn/sym leaf])
           caller-before (db/pull captured '[*] [:seon.fn/sym caller])
           live-env @(:env live)
           direct "(seon.sci.eval-test/cut? {})"
           indirect "(not (seon.sci.eval-test/ok? {}))"
           edited "(defn ^{:malli/schema [:=> [:cat :map] :boolean]} cut? [evaluation] true)"]
       (is (= :core (:seon.schema.admission/source leaf-entity)))
       (is (= :core (:seon.schema.admission/source caller-before)))
       (is (some? (:seon.program/definition-digest leaf-entity)))
       (is (identical? @(find-var caller) @(sci/resolve live caller))
           "the unchanged caller begins as the actual compiled JVM root")
       (on-branch connection captured "realities-proof" :realities-proof-a
        (fn [a]
          (on-branch connection captured "realities-proof" :realities-proof-b
           (fn [b]
             (let [a-ctx (eval/fork-cluster-ctx live (db/db a) a)
                   b-ctx (eval/fork-cluster-ctx live (db/db b) b)
                   _ (declare! a-ctx a 'seon.sci.eval-test edited)
                   acquisition (measured #(eval/acquire!
                                           {:seon.sci.eval/ctx a-ctx :seon.db/db (db/db a)}))
                   _ (eval/acquire! {:seon.sci.eval/ctx b-ctx :seon.db/db (db/db b)})
                   a-direct (measured #(:seon.sci.admit/value
                                        (evaluate! a-ctx a 'user direct)))
                   a-indirect (measured #(:seon.sci.admit/value
                                          (evaluate! a-ctx a 'user indirect)))
                   b-calls (measured #(:seon.sci.admit/value
                                      (evaluate! b-ctx b 'user (str "[" direct " " indirect "]"))))
                   closure (functions/reverse-closure
                             {:seon.db/db (db/db a) :seon.fn/seeds #{leaf}})]
               (is (= caller-before (db/pull (db/db a) '[*] [:seon.fn/sym caller])))
               (is (true? (:value a-direct)))
               (is (true? (:value a-indirect)))
               (is (= [false false] (:value b-calls)))
               (is (identical? live-env @(:env live)))
               (is (false? (sci/eval-string* live indirect)))
               (is (= (:seon.fn/tests closure)
                      (functions/gate-sets {:seon.db/db (db/db a) :seon.fn/seeds #{leaf}})))
               (is (contains? (:seon.fn/affected closure) caller))
               ;; The fixture inherits the handle's entities (an agent's own
               ;; definitions interpret everywhere), so the branch interprets
               ;; exactly what its parent did plus the changed entity's closure.
               (is (= (into (set (:seon.sci.eval/interpreted @(:seon.sci.kernel/program-snapshot live)))
                            (:seon.fn/affected closure))
                      (set (:seon.sci.eval/interpreted @(:seon.sci.kernel/program-snapshot a-ctx)))))
               (is (pos? (:seon.sci.eval/interpreted-count (:value acquisition))))
               (is (empty? (:seon.test/acquisition-refusals (eval/acquired-program a-ctx))))
               (println "REALITIES-PROOF"
                 (pr-str {:acquisition-ms (:ms acquisition)
                          :a-direct a-direct :a-indirect a-indirect :b-calls b-calls
                          :interpreted-count (:seon.sci.eval/interpreted-count (:value acquisition))
                          :function-count (count (db/q '[:find [?s ...] :where [_ :seon.fn/sym ?s]] captured))}))
               (let [host-entity (declare! a-ctx a 'seon.env
                                "(defn ^{:malli/schema [:=> [:cat :map] :map]} ->Environment [members] members)")
                     refused (eval/install-row! {:seon.sci.eval/ctx a-ctx
                                                 :seon.db/db (db/db a)
                                                 :seon.program/row host-entity})]
                 (is (= 'seon.env/->Environment (:seon.sci.eval/refused-function refused)))
                 (is ((schema/projection-validator
                        (db/carried-projection (db/db a))
                        :seon.sci.eval/interpretation-error) refused))
                 (is (nil? (sci/resolve a-ctx 'seon.env/->Environment)))
                 (is (some? (sci/resolve b-ctx 'seon.env/->Environment)))
                 (println "REALITIES-HOST-REFUSAL" (seon.render.value/core-text refused))))))))))))))

(defn- declared!
  "Transact one analyzed declaration through the turn's declaration writer."
  {:malli/schema [:=> [:cat :seon.db/connection :qualified-symbol :string] :seon.program/row]}
  [connection function-symbol source]
  (let [entity (support/program-fn-entity (db/db connection) function-symbol source)]
    (support/transacted! connection (#'turn/row-tx (db/db connection) {} entity))
    entity))

(deftest ^{:seon.test/long "Two complete SCI acquisitions of the canonical fixture program (measured 985 ms for one derivation in this run's memo test) plus one analyzed declaration."
           :seon.test/long-ms 8000}
  a-data-only-commit-reuses-the-acquired-program-and-a-program-entity-reacquires
  (support/with-database
   (fn [connection]
     (db/call-with-custody {:seon.db/connection connection}
      (fn []
       (let [live (support/fork-cluster-ctx connection)
             before (db/db connection)
             acquisition (eval/acquire! {:seon.sci.eval/ctx live :seon.db/db before})
             environment @(:env live)
             ;; A declared data-partition entity: a task.
             _ (support/transacted! connection
                 [(support/task-row "programrevis" "A commit that writes no program entity.")])
             data (db/db connection)
             reused (measured #(eval/acquire! {:seon.sci.eval/ctx live :seon.db/db data}))]
         (is (not= (db/commit-id before) (db/commit-id data))
             "the task is a real commit")
         (is (identical? acquisition (:value reused))
             "a commit writing no program entity returns the acquired program")
         (is (identical? environment @(:env live))
             "and installs nothing into the context")
         (is (identical? data (:seon.db/db (eval/acquired-program live)))
             "the acquired value advances to the program-equal commit")
         (let [_ (declared! connection 'seon.sci.eval-test/program-revision-probe
                   "(defn ^{:malli/schema [:=> [:cat] :int]} program-revision-probe [] 1)")
               changed (db/db connection)]
           (is (not (eval/acquired-database? live changed))
               "a program entity changes the acquired program's identity")
           (let [reacquired (measured #(eval/acquire! {:seon.sci.eval/ctx live :seon.db/db changed}))]
             (is (not (identical? acquisition (:value reacquired))))
             (is (not (identical? environment @(:env live))))
             (is (identical? changed (:seon.db/db (eval/acquired-program live))))
             (is (= 1 (sci/eval-string* live "(seon.sci.eval-test/program-revision-probe)")))
             (println "PROGRAM-REVISION-PROOF"
                      (pr-str {:reused-acquire-ms (:ms reused)
                               :program-reacquire-ms (:ms reacquired)}))))))))))

(defn- cluster-bases
  "The retained cluster bases of the program `database`'s JVM loaded, by cache key."
  {:malli/schema [:=> [:cat :seon.db/database-value] :map]}
  [database]
  (let [loaded (db/commit-id (#'eval/loaded-program database))]
    (into {} (filter (fn [[[_ commit]]] (= loaded commit)))
          (seon.cache/retained :seon.sci.eval/base-context))))

(deftest ^{:seon.test/long "One complete base-context derivation of the canonical fixture program (measured 452-1,192 ms on default) and one memoized copy."
           :seon.test/long-ms 8000}
  a-base-context-is-memoized-by-its-program-and-each-caller-gets-its-own-fork
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           first-call (measured #(eval/base-ctx database))
           bases (cluster-bases database)
           second-call (measured #(eval/base-ctx database))
           a (:value first-call)
           b (:value second-call)]
       (is (seq bases) "the first call left the loaded program's cluster base")
       (is (= bases (cluster-bases database)) "the second call forks it and derives none")
       (is (= (:seon.sci.eval/interpreted-count (:seon.sci.eval/acquisition a))
              (:seon.sci.eval/interpreted-count (:seon.sci.eval/acquisition b))))
       (is (not (identical? (:env a) (:env b))))
       (is (not (identical? (:seon.sci.kernel/program-snapshot a)
                            (:seon.sci.kernel/program-snapshot b))))
       (sci/eval-string* a "(def base-memo-probe 1)")
       (is (some? (sci/resolve a 'user/base-memo-probe)))
       (is (nil? (sci/resolve b 'user/base-memo-probe))
           "a definition in one caller's context is invisible to another")
       (println "BASE-CTX-MEMO-PROOF"
                (pr-str {:derive-ms (:ms first-call) :memoized-ms (:ms second-call)}))))))

(deftest ^{:seon.test/long "One complete SCI acquisition of the canonical fixture program plus one analyzed declaration."
           :seon.test/long-ms 8000}
  an-override-of-a-declaration-the-indexer-marks-host-bound-refuses-by-name
  (support/with-database
   (fn [connection]
     (db/call-with-custody {:seon.db/connection connection}
      (fn []
       (let [live (support/fork-cluster-ctx connection)
             ;; host-bound by its own call of a macro SCI does not interpret (log/warn)
             subject 'seon.cluster/warn-low-space!
             indexed (db/pull (db/db connection) '[*] [:seon.fn/sym subject])]
         (is (true? (:seon.fn/host-bound? indexed))
             "the indexer marks the declaration's own proxy (class usage alone interprets, task bd7cb669b45d)")
         (is (not (#{'clojure.core/reify 'clojure.core/proxy 'clojure.core/definterface
                     'clojure.core/deftype 'clojure.core/defrecord}
                   (:seon.fn/defined-by indexed)))
             "the form head alone would not have refused it")
         ;; WANTED since be96a64ba: an entity acquired from the database installs a
         ;; root that refuses by name when called (only an agent's evaluation
         ;; keeps the returned refusal), so the rest of the program still runs.
         ;; WANTED since 9dd373901: the override carries its contract's arity
         ;; facts, and the writer refuses one that drops an arity callers use,
         ;; so it keeps the two-argument arity boot calls.
         (let [entity (declared! connection subject
                     (str "(defn ^{:malli/schema [:=> [:cat :string :seon.config/effective] :seon.operator/footprint]}"
                          " warn-low-space! [_managed-root _effective] (seon.fs/filesystem-space \"/\"))"))
               installed (eval/install-row! {:seon.sci.eval/ctx live
                                             :seon.db/db (db/db connection)
                                             :seon.program/row entity})
               refused (refusal-of live subject)]
           (is (= :agent (:seon.schema.admission/source
                          (db/pull (db/db connection) [:seon.schema.admission/source] [:seon.fn/sym subject])))
               "the declaration writer admits the override as the agent's own change")
           (is (= :unavailable (:seon.sci.eval/load-state installed)))
           (is (string? (get-in installed [:seon.sci.eval/load-result :seon.error/message])))
           (is (= subject (:seon.sci.eval/refused-function refused)))
           (is ((schema/projection-validator
                 (db/carried-projection (db/db connection))
                 :seon.sci.eval/interpretation-error) refused)))))))))

(deftest ^{:seon.test/long "One complete SCI acquisition of the canonical fixture program shared by two concurrent acquirers (measured 985 ms per derivation) plus one analyzed declaration."
           :seon.test/long-ms 8000}
  two-concurrent-acquisitions-of-one-program-derive-it-once
  (support/with-database
   (fn [connection]
     (db/call-with-custody {:seon.db/connection connection}
      (fn []
       (let [a (support/fork-cluster-ctx connection)
             b (support/fork-cluster-ctx connection)
             bases (cluster-bases (db/db connection))
             _ (declared! connection 'seon.sci.eval-test/concurrent-acquisition-probe
                 "(defn ^{:malli/schema [:=> [:cat] :int]} concurrent-acquisition-probe [] 2)")
             changed (db/db connection)
             start (promise)
             acquiring (fn [ctx]
                         (future @start
                                 (eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db changed})))
             in-a (acquiring a)
             in-b (acquiring b)
             _ (deliver start true)
             from-a (deref in-a 6000 ::unfinished)
             from-b (deref in-b 6000 ::unfinished)]
         (is (not= ::unfinished from-a) "the first acquirer finished within its bound")
         (is (not= ::unfinished from-b) "the second acquirer finished within its bound")
         (is (seq bases) "the fixture's loaded program has its cluster base")
         (is (= bases (cluster-bases changed))
             "both acquirers fork the one cluster base; neither derives the program")
         (is (= (:seon.sci.eval/interpreted-count from-a) (:seon.sci.eval/interpreted-count from-b)))
         (is (not (identical? (:env a) (:env b))) "each context keeps its own environment")
         (is (= 2 (sci/eval-string* a "(seon.sci.eval-test/concurrent-acquisition-probe)")))
         (is (= 2 (sci/eval-string* b "(seon.sci.eval-test/concurrent-acquisition-probe)")))))))))

(deftest ^{:seon.test/long "One cluster-base derivation of the canonical fixture program, two branches, one interpreted override closure and one configuration write."
           :seon.test/long-ms 12000}
  agent-branches-and-a-settings-write-share-the-cluster-base
  (support/with-database
   (fn [connection]
     (db/call-with-custody {:seon.db/connection connection}
      (fn []
       (let [cluster-name (:seon.cluster/name (support/execution-handle connection))
             live (support/fork-cluster-ctx connection)
             captured (db/db connection)
             bases (cluster-bases captured)
             subject 'seon.sci.eval-test/cut?
             overlay (fn [ctx] (set (:seon.sci.eval/interpreted @(:seon.sci.kernel/program-snapshot ctx))))
             closure (fn [database seeds]
                       (into seeds (:seon.fn/affected (functions/reverse-closure
                                                       {:seon.db/db database :seon.fn/seeds seeds}))))
             overridden (fn [database]
                          (:seon.sci.eval/overridden
                           (#'eval/overlay-plan database (:seon.sci.eval/loaded-database
                                                          @(:seon.sci.kernel/program-snapshot live)))))]
         (is (seq bases) "the cluster base exists once the cluster context acquired")
         (on-branch connection captured cluster-name :pb01-a
          (fn [a]
            (on-branch connection captured cluster-name :pb01-b
             (fn [b]
               (support/transacted! a (agent-revision-tx (db/db a) [subject]))
               (let [a-ctx (eval/fork-cluster-ctx live (db/db a) a)
                     b-ctx (eval/fork-cluster-ctx live (db/db b) b)
                     _ (eval/acquire! {:seon.sci.eval/ctx a-ctx :seon.db/db (db/db a)})
                     first-b (eval/acquire! {:seon.sci.eval/ctx b-ctx :seon.db/db (db/db b)})
                     repeat-b (eval/acquire! {:seon.sci.eval/ctx b-ctx :seon.db/db (db/db b)})
                     _ (support/apply-config! b cluster-name {:seon.config.run/max-episode-runs 3})
                     after-settings (eval/acquire! {:seon.sci.eval/ctx b-ctx :seon.db/db (db/db b)})]
                 (is (= bases (cluster-bases (db/db b)))
                     "two first acquisitions, a repeat and a settings write construct no cluster base")
                 (is (identical? first-b repeat-b) "a repeat acquisition returns the acquired program")
                 (is (identical? first-b after-settings)
                     "a settings write that moves no armed dial keeps the acquired program")
                 (is (= (conj (overridden (db/db b)) subject) (overridden (db/db a)))
                     "a branch's overrides are its authored rows the loaded program lacks or holds otherwise")
                 (is (= (closure (db/db a) (overridden (db/db a))) (overlay a-ctx))
                     "the overlay is exactly the overrides plus their reverse closure")
                 (is (= (closure (db/db b) (overridden (db/db b))) (overlay b-ctx))))))))))))))

(defn- acquisition-view
  "What one acquisition compared against, what it interpreted and what it refused."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx] :map]}
  [ctx]
  (let [acquired (eval/acquired-program ctx)
        snapshot @(:seon.sci.kernel/program-snapshot ctx)]
    {:loaded (db/commit-id (:seon.sci.eval/loaded-database snapshot))
     :interpreted (set (:seon.sci.eval/interpreted snapshot))
     :interpreted-count (:seon.sci.eval/interpreted-count acquired)
     :refused (into #{} (map :seon.sci.eval/refused-function)
                    (:seon.test/acquisition-refusals acquired))
     :unavailable (into #{} (keep #(when (= :unavailable (:seon.sci.eval/load-state %)) (:seon.fn/sym %)))
                        ;; `acquired-program` carries no load results; the
                        ;; snapshot's acquisition (what `acquire!` returned) does.
                        (:seon.sci.eval/load-results (:seon.sci.eval/acquisition snapshot)))}))

(deftest ^{:seon.test/long "Four complete SCI acquisitions of the canonical fixture program (one per loaded commit and branch) plus two fixture writes and two branches."
           :seon.test/long-ms 12000}
  an-adoption-record-moves-the-loaded-program-every-acquisition-compares-against
  (support/with-database
   (fn [connection]
     (db/call-with-custody {:seon.db/connection connection}
      (fn []
       (let [subject 'seon.cluster/warn-low-space!
             cluster-name (:seon.cluster/name (support/execution-handle connection))
             before (db/db connection)
             record-entity (db/q '[:find ?e . :where [?e :seon.source/commit-id]] before)
             ctx (eval/cluster-ctx before connection
                                   (eval/projection-state before (db/carried-projection before))
                                   {:seon.sci.eval/loaded-connection connection})
             first-acquire (measured #(eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db before}))
             baseline (acquisition-view ctx)
             _ (support/transacted! connection (agent-revision-tx before [subject]))
             edited (db/db connection)
             edited-commit (db/commit-id edited)
             edited-acquire (measured #(eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db edited}))
             unadopted (acquisition-view ctx)
             unadopted-call (refusal-of ctx subject)
             _ (support/transacted! connection
                 [[:db/add record-entity :seon.source/commit-id edited-commit]])
             adopted-db (db/db connection)]
         (is (true? (:seon.fn/host-bound? (db/pull before '[*] [:seon.fn/sym subject])))
             "the subject is a compiled declaration SCI cannot interpret")
         (is (= #{} (:refused baseline)) "the fixture's own program interprets nothing it refuses")
         ;; WANTED since be96a64ba: a host-bound entity acquired from the database
         ;; refuses by name when called, never the acquisition.
         ;; Whether the rest of its closure interprets is the unoverridden
         ;; test's assertion; this one asks only about the subject.
         (is (not (contains? (:refused unadopted) subject)) "the changed entity does not refuse the acquisition")
         (is (contains? (:unavailable unadopted) subject))
         (is (= subject (:seon.sci.eval/refused-function unadopted-call))
             "before adoption the agent's changed host-bound entity differs from the loaded program and refuses by name when called")
         (is (not (eval/acquired-database? ctx adopted-db))
             "a moved adoption record alone makes the acquisition stale")
         (let [adopted-acquire (measured #(eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db adopted-db}))
               adopted (acquisition-view ctx)]
           (println "M9-ADOPTION-PROOF"
                    (pr-str {:first-acquire-ms (:ms first-acquire)
                             :edited-acquire-ms (:ms edited-acquire)
                             :adopted-acquire-ms (:ms adopted-acquire)
                             :baseline baseline :unadopted unadopted :adopted adopted}))
           (is (= edited-commit (:loaded adopted))
               "the loaded program is the commit the record names")
           (is (contains? (:interpreted unadopted) subject))
           (is (not (contains? (:interpreted adopted) subject))
               "the adopted entity runs compiled")
           (is (<= (:interpreted-count adopted) (:interpreted-count baseline)))
           (is (= #{} (:refused adopted)) "the adopted entity is compiled, so nothing refuses")
           (on-branch connection adopted-db cluster-name :m9-adoption-isolated
            (fn [child]
              (let [{fork :value ms :ms}
                    (measured #(eval/fork-cluster-ctx
                                ctx (db/db child) child
                                (eval/projection-state (db/db child) (db/carried-projection (db/db child)))))]
                (println "M9-ADOPTION-ISOLATED-MS" ms)
                (is (= (select-keys adopted [:loaded :interpreted :interpreted-count :refused :unavailable])
                       (acquisition-view fork))
                    "an isolated branch at the head compares against the cluster's record"))))
           (on-branch connection before cluster-name :m9-adoption-stable
            (fn [child]
              (let [{fork :value ms :ms}
                    (measured #(eval/fork-cluster-ctx
                                ctx (db/db child) child
                                (eval/projection-state (db/db child) (db/carried-projection (db/db child)))))]
                (println "M9-ADOPTION-STABLE-MS" ms)
                ;; WANTED since 8cf62cd13: only an agent-authored entity overrides.
                ;; A branch opened before the adoption holds the file entity the
                ;; loaded program moved past, so it runs the compiled Var.
                (let [stable (acquisition-view fork)]
                  (is (= edited-commit (:loaded stable)))
                  (is (not (contains? (:interpreted stable) subject)))
                  (is (= #{} (:refused stable))
                      "a branch behind the adoption is not the branch's own change and refuses nothing"))))))))))))

(deftest ^{:seon.test/long "Two complete SCI acquisitions of the canonical fixture program (the loaded one and a branch value lacking one declaration)."
           :seon.test/long-ms 12000}
  a-branch-forked-before-a-platform-change-binds-the-loaded-compiled-var
  ;; Root, 2026-09-28: an agent forked before a reload could not resolve
  ;; `my.program/search`, which the reload added: host bindings came from the
  ;; branch's own rows only, and the branch predates the row.
  (support/with-database
   (fn [connection]
     (db/call-with-custody {:seon.db/connection connection}
      (fn []
       (let [subject 'seon.render.value/bounded-text
             before (db/db connection)
             projection (db/carried-projection before)
             predating (vary-meta (:db-after (d/with before [[:db/retractEntity [:seon.fn/sym subject]]]))
                                  assoc :seon.schema/projection projection)
             ctx (eval/cluster-ctx before connection
                                   (eval/projection-state before projection)
                                   {:seon.sci.eval/loaded-connection connection})]
         (is (nil? (db/pull predating '[:seon.fn/sym] [:seon.fn/sym subject]))
             "the branch value has no row for the function the loaded program has")
         (eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db predating
                         :seon.schema/projection projection})
         (is (some? (sci/resolve ctx subject)) "the loaded program's function resolves in the branch's context")
         (is (identical? @(find-var subject) @(sci/resolve ctx subject))
             "it is the compiled Var's root, running as compiled on every branch")))))))

(deftest ^{:seon.test/long "One complete SCI acquisition of the canonical fixture program with two changed entities whose closures reach four declarations."
           :seon.test/long-ms 8000}
  an-unchanged-host-bound-caller-of-a-changed-definition-runs-as-compiled
  (support/with-database
   (fn [connection]
     (db/call-with-custody {:seon.db/connection connection}
      (fn []
       (let [changed 'seon.fs/filesystem-space
             caller 'seon.cluster/warn-low-space!
             unloaded-changed 'seon.cluster.reload-measure/measured-request
             unloaded 'seon.cluster.reload-measure/-main
             before (db/db connection)
             host-bound? #(true? (:seon.fn/host-bound? (db/pull before [:seon.fn/host-bound?] [:seon.fn/sym %])))
             ctx (eval/cluster-ctx before connection
                                   (eval/projection-state before (db/carried-projection before))
                                   {:seon.sci.eval/loaded-connection connection})
             _ (support/transacted! connection (agent-revision-tx before [changed unloaded-changed]))
             edited (db/db connection)
             acquired (measured #(eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db edited}))
             view (acquisition-view ctx)]
         (println "UNOVERRIDDEN-HOST-BOUND-MS" (:ms acquired) (:interpreted-count view))
         (is (false? (host-bound? changed)) "the changed helper interprets")
         (is (host-bound? caller) "the caller is host-bound")
         (is (not-any? host-bound? [unloaded-changed unloaded])
             "class usage alone never makes a declaration host-bound (task bd7cb669b45d)")
         (is (some? (find-ns (symbol (namespace caller)))) "the caller's namespace is loaded")
         (is (nil? (find-ns (symbol (namespace unloaded)))) "this JVM never loaded the measurement namespace")
         (is (every? (:interpreted view) [changed caller unloaded-changed unloaded])
             "each changed entity's closure reaches its host-bound caller")
         (is (= #{} (:refused view)) "no host-bound declaration without a compiled Var to override refuses the acquisition")
         ;; A BRANCH NEVER CHANGES THE PLATFORM (`seon.sci.eval`, install of a
         ;; host-bound row): the unchanged host-bound caller of a branch-changed
         ;; definition binds its compiled Var, never a refusal.
         (is (identical? @(find-var caller) @(sci/resolve ctx caller))
             "an unchanged host-bound caller of a changed definition runs as compiled")))))))

(deftest ^{:seon.test/long "One complete SCI acquisition of the canonical fixture program interpreting the reverse closure (77 declarations) of one changed hiccup helper; measured 1,426 ms."
           :seon.test/long-ms 12000}
  interpreted-callers-resolve-unchanged-names-to-compiled-vars
  (support/with-database
   (fn [connection]
     (db/call-with-custody {:seon.db/connection connection}
      (fn []
       (let [changed 'seon.render.hiccup/class-value
             before (db/db connection)
             ctx (eval/cluster-ctx before connection
                                   (eval/projection-state before (db/carried-projection before))
                                   {:seon.sci.eval/loaded-connection connection})
             _ (support/transacted! connection (agent-revision-tx before [changed]))
             acquired (measured #(eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)}))
             view (acquisition-view ctx)
             ;; Callers in class-value's current closure (the indexed program,
             ;; 2026-09-27): derive-page reads the private def stream-strip-id,
             ;; request! the referred defonce running-instances, arm-agents!
             ;; the dependency alias async/chan. selected-instance no longer
             ;; reaches class-value.
             callers '[seon.render.web/derive-page seon.cluster.boot/request!
                       seon.cluster/arm-agents!]
             ;; The indexer marks handler and serve! host-bound: unchanged
             ;; host-bound callers install a root that refuses by name when
             ;; called (be96a64ba), never an SCI definition.
             host-bound-callers '[seon.render.web/handler seon.cluster/serve!]
             load-states (fn [syms]
                           (into {} (keep (fn [{sym :seon.fn/sym state :seon.sci.eval/load-state}]
                                            (when (some #{sym} syms) [sym state])))
                                 (:seon.sci.eval/load-results (:value acquired))))]
         (is (every? (:interpreted view) (concat callers host-bound-callers [changed]))
             "the changed helper's closure reaches each caller")
         (is (= (zipmap callers (repeat :interpreted)) (load-states callers))
             "each caller is an SCI definition, analyzed against its private def, referred defonce and dependency alias")
         (is (= (zipmap host-bound-callers (repeat :unavailable)) (load-states host-bound-callers)))
         (is (= 'seon.render.web/handler (:seon.sci.eval/refused-function (refusal-of ctx 'seon.render.web/handler))))
         ;; A caller expanding a JVM macro that reads &env (async/go-loop in a
         ;; loaded test namespace) still refuses: the host-bound classification
         ;; gap recorded in the affected-callers issue, not an unbound name.
         (is (= [] (filterv #(re-find #"Unable to resolve|does not name an installed SCI Var" %)
                            (map :seon.error/message
                                 (:seon.test/acquisition-refusals (eval/acquired-program ctx))))))))))))

(deftest ^{:seon.test/long "One complete SCI acquisition of the canonical fixture program interpreting two changed entities and their reverse closures; measured 1,090 ms on default."
           :seon.test/long-ms 12000}
  an-interpreted-caller-resolves-its-compiled-namespace-aliases-privates-and-core
  (support/with-database
   (fn [connection]
     (db/call-with-custody {:seon.db/connection connection}
      (fn []
       ;; exact! calls effect/request!, an alias my.edit makes with a
       ;; top-level require outside its ns form; write-edn-atomically! calls
       ;; clojure.core/spit, which SCI's core lacks, through imported classes.
       (let [changed '[my.edit/exact! seon.fs/write-edn-atomically!]
             before (db/db connection)
             ctx (eval/cluster-ctx before connection
                                   (eval/projection-state before (db/carried-projection before))
                                   {:seon.sci.eval/loaded-connection connection})
             ;; The agent's digested change, so each entity overrides its compiled Var.
             _ (support/transacted! connection (agent-revision-tx before changed))
             acquired (measured #(eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)}))]
         (is (= (zipmap changed (repeat :interpreted))
                (into {} (keep (fn [{sym :seon.fn/sym state :seon.sci.eval/load-state}]
                                 (when (some #{sym} changed) [sym state])))
                      (:seon.sci.eval/load-results (:value acquired))))
             "each changed entity is an SCI definition")
         (is (= [] (filterv #(re-find #"Unable to resolve|does not name an installed SCI Var" %)
                            (map :seon.error/message
                                 (:seon.test/acquisition-refusals (eval/acquired-program ctx))))))))))))

(deftest ^{:seon.test/long "One complete SCI acquisition of the canonical fixture program after one analyzed declaration."
           :seon.test/long-ms 8000}
  a-fork-taken-after-a-program-entity-commit-holds-that-entity
  ;; The executing handle acquired its program before the entity was committed;
  ;; a fork onto the later value must hold the later value's program.
  (support/with-database
   (fn [connection]
     (db/call-with-custody {:seon.db/connection connection}
      (fn []
        (declared! connection 'seon.sci.eval-test/fork-after-commit-probe
                   "(defn ^{:malli/schema [:=> [:cat] :int]} fork-after-commit-probe [] 7)")
        (let [database (db/db connection)
              ctx (support/fork-cluster-ctx connection)]
          (is (eval/acquired-database? ctx database)
              "the fork's acquired program is the value it was forked onto")
          (is (contains? (:functions @(:seon.sci.kernel/program-snapshot ctx))
                         'seon.sci.eval-test/fork-after-commit-probe)
              "the entity committed after the base's acquisition is in the fork's program")
          (is (= 7 (sci/eval-string* ctx "(seon.sci.eval-test/fork-after-commit-probe)")))))))))

(deftest ^{:seon.test/long "Two whole-program definition-digest queries of the canonical fixture program plus one analyzed declaration."
           :seon.test/long-ms 8000}
  a-loaded-programs-digests-are-read-once-per-commit
  (support/with-database
   (fn [connection]
     (db/call-with-custody {:seon.db/connection connection}
      (fn []
        (let [loaded-digests #'eval/loaded-digests
              function-digests #'eval/function-digests
              hits #(get-in (seon.cache/status) [:seon.cache/kinds :seon.sci.eval/loaded-digests :seon.cache/hits] 0)
              before (db/db connection)
              first-read (loaded-digests before)
              hits-before (hits)
              second-read (loaded-digests before)]
          (is (= (function-digests before) first-read)
              "the retained map is the value's own definition identities")
          (is (identical? first-read second-read))
          (is (< hits-before (hits)) "the second read of one commit is a hit")
          (declared! connection 'seon.sci.eval-test/loaded-digest-probe
                     "(defn ^{:malli/schema [:=> [:cat] :int]} loaded-digest-probe [] 1)")
          (let [after (db/db connection)]
            (is (contains? (loaded-digests after) 'seon.sci.eval-test/loaded-digest-probe)
                "another commit is another key, never the first commit's map")
            (is (not (contains? (loaded-digests before) 'seon.sci.eval-test/loaded-digest-probe))))))))))

