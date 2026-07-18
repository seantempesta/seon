(ns seon.client-quiescence-test
  "Focused proof for the client quiescence database-value boundary."
  (:require
   [cljs.test :refer [async deftest is]]
   [seon.agent.run :as run]
   [seon.client :as client]
   [seon.db :as db]))

(def ^:private database
  {:db-name "default"
   :t 7
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id
   #uuid "00000000-0000-0000-0000-000000000007"})

(def ^:private final-database
  (assoc database
         :t 8
         :datahike/commit-id
         #uuid "00000000-0000-0000-0000-000000000008"))

(def ^:private native-report
  {:db-before database
   :db-after final-database
   :tx-data []
   :tempids {}
   :tx-meta {}})

(defn- pull-many-stub
  [pull]
  (fn
    ([request] (pull request))
    ([_selector _entity-ids]
     (js/Promise.reject (js/Error. "unexpected pull-many arity 2")))
    ([_database _selector _entity-ids]
     (js/Promise.reject (js/Error. "unexpected pull-many arity 3")))))

(defn- drain-agent-work!
  []
  ((deref #'client/drain-agent-work!) (+ (.now js/Date) 1000)))

(defn- settled-turns!
  [db-value turn-ids]
  ((deref #'client/settled-turns!) db-value turn-ids))

(defn- finish!
  [promise done restorations]
  (-> promise
      (.finally
       (fn []
         (doseq [[restore value] restorations]
           (restore value))
         (done)))))

(deftest drain-uses-the-final-empty-work-database-value
  (async done
    (let [original-work run/quiescence-work!
          original-close run/close-run!
          original-pull-many db/pull-many
          work-values
          (atom
           [{::db/db database
             ::run/current-runs
             [{:seon.agent/id "agent-a" :seon.agent.run/id "run-a"}]
             ::run/running-turns
             [{:seon.agent.run/id "run-b"
               :seon.agent.turn/id "turn-b"}]}
            {::db/db final-database
             ::run/current-runs []
             ::run/running-turns []}])
          close-request (atom nil)
          pull-request (atom nil)]
      (set! run/quiescence-work!
            (fn []
              (let [value (first @work-values)]
                (swap! work-values subvec 1)
                (js/Promise.resolve value))))
      (set! run/close-run!
            (fn [request]
              (reset! close-request request)
              (js/Promise.resolve native-report)))
      (set! db/pull-many
            (pull-many-stub
             (fn [request]
               (reset! pull-request request)
               (js/Promise.resolve
                [{:seon.agent.turn/id "turn-b"
                  :seon.agent.turn/status :done}]))))
      (finish!
       (-> (drain-agent-work!)
           (.then
            (fn [result]
              (is (= {:seon.client/quiesced-run-ids ["run-a"]
                      :seon.client/completed-turn-ids ["turn-b"]
                      :seon.client/errored-turn-ids []}
                     result))
              (is (= {:seon.agent.run/id "run-a"
                      :seon.agent.run/closed-reason :quiesced}
                     @close-request))
              (is (identical? final-database (::db/db @pull-request)))
              (is (= [:seon.agent.turn/id :seon.agent.turn/status]
                     (::db/pull-pattern @pull-request)))
              (is (= [[:seon.agent.turn/id "turn-b"]]
                     (::db/refs @pull-request)))))
           (.catch
            (fn [error]
              (is false (str "unexpected drain rejection: " error)))))
       done
       [[#(set! run/quiescence-work! %) original-work]
        [#(set! run/close-run! %) original-close]
        [#(set! db/pull-many %) original-pull-many]]))))

(deftest direct-work-error-stops-before-close-or-pull
  (async done
    (let [original-work run/quiescence-work!
          original-close run/close-run!
          original-pull-many db/pull-many
          close-count (atom 0)
          pull-count (atom 0)]
      (set! run/quiescence-work!
            (fn []
              (js/Promise.resolve
               {:seon.error/message "work unavailable"})))
      (set! run/close-run!
            (fn [_]
              (swap! close-count inc)
              (js/Promise.resolve native-report)))
      (set! db/pull-many
            (pull-many-stub
             (fn [_]
               (swap! pull-count inc)
               (js/Promise.resolve []))))
      (finish!
       (-> (drain-agent-work!)
           (.then
            (fn [_]
              (is false "a direct work error must reject the drain")))
           (.catch
            (fn [error]
              (is (= "Planned quiesce could not acquire current work."
                     (.-message error)))
              (is (zero? @close-count))
              (is (zero? @pull-count)))))
       done
       [[#(set! run/quiescence-work! %) original-work]
        [#(set! run/close-run! %) original-close]
        [#(set! db/pull-many %) original-pull-many]]))))

(deftest retained-run-makes-a-direct-close-error-fatal
  (async done
    (let [original-work run/quiescence-work!
          original-close run/close-run!
          original-pull-many db/pull-many
          work
          {::db/db database
           ::run/current-runs
           [{:seon.agent/id "agent-a" :seon.agent.run/id "run-a"}]
           ::run/running-turns []}
          work-values (atom [work work])
          pull-count (atom 0)]
      (set! run/quiescence-work!
            (fn []
              (let [value (first @work-values)]
                (swap! work-values subvec 1)
                (js/Promise.resolve value))))
      (set! run/close-run!
            (fn [_]
              (js/Promise.resolve
               {:seon.error/message "close refused"})))
      (set! db/pull-many
            (pull-many-stub
             (fn [_]
               (swap! pull-count inc)
               (js/Promise.resolve []))))
      (finish!
       (-> (drain-agent-work!)
           (.then
            (fn [_]
              (is false "a retained run close error must reject the drain")))
           (.catch
            (fn [error]
              (is (= "Planned quiesce could not close current runs."
                     (.-message error)))
              (is (zero? @pull-count)))))
       done
       [[#(set! run/quiescence-work! %) original-work]
        [#(set! run/close-run! %) original-close]
        [#(set! db/pull-many %) original-pull-many]]))))

(deftest terminal-pull-direct-error-fails-closed
  (async done
    (let [original-pull-many db/pull-many]
      (set! db/pull-many
            (pull-many-stub
             (fn [_]
               (js/Promise.resolve
                {:seon.error/message "terminal pull refused"}))))
      (finish!
       (-> (settled-turns! final-database #{"turn-a"})
           (.then
            (fn [_]
              (is false "a direct pull error must reject classification")))
           (.catch
            (fn [error]
              (is (= "Terminal turn acquisition failed."
                     (.-message error))))))
       done
       [[#(set! db/pull-many %) original-pull-many]]))))

(deftest nonterminal-turn-fails-closed
  (async done
    (let [original-pull-many db/pull-many]
      (set! db/pull-many
            (pull-many-stub
             (fn [_]
               (js/Promise.resolve
                [{:seon.agent.turn/id "turn-a"
                  :seon.agent.turn/status :running}]))))
      (finish!
       (-> (settled-turns! final-database #{"turn-a"})
           (.then
            (fn [_]
              (is false "a nonterminal turn must reject classification")))
           (.catch
            (fn [error]
              (is (= "A drained turn has no terminal durable status."
                     (.-message error))))))
       done
       [[#(set! db/pull-many %) original-pull-many]]))))
