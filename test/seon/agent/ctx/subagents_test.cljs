(ns seon.agent.ctx.subagents-test
  "Piece 3 (subagents) + Piece 4 (orphaned-agents) derived sections — called
   directly like the render engine does, on a hermetic :memory conn. Assert
   presence/structure + the reactive vanish, never exact strings."
  (:require
    [cljs.test :refer [deftest is async]]
    [datahike.api :as d]
    [seon.agent.ctx.subagents :as sub]
    [seon.agent.run :as run]
    [seon.ai.tokens :as tokens]
    [seon.client :as client]
    [seon.db :as db]
    [seon.db.id :as db.id]))

(def ^:dynamic ^:private parent-id nil)
(def ^:dynamic ^:private child-id nil)
(def ^:dynamic ^:private gchild-id nil)

(defn- fresh-conn []
  (-> (client/open-agent-conn!)
      (.then
        (fn [conn]
          (-> (db.id/allocate!
                {::db.id/allocations
                 [{::db.id/key ::parent
                   ::db.id/identity-attr :seon.agent/id}]
                 ::db.id/transaction-builder
                 (fn [ids]
                   {:seon.db/tx-data
                    [{:seon.agent/id (::parent ids)}]})
                 :seon.db/conn conn})
              (.then
                (fn [env]
                  (when-not (:seon.db/ok? env)
                    (throw (ex-info "parent agent allocation failed" env)))
                  (set! parent-id (get-in env [::db.id/ids ::parent]))
                  conn)))))))

(defn- with-conn [body]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

(defn- add-child! [conn allocation-key parent purpose]
  (-> (db.id/allocate!
        {::db.id/allocations
         [{::db.id/key allocation-key
           ::db.id/identity-attr :seon.agent/id}]
         ::db.id/transaction-builder
         (fn [ids]
           {:seon.db/tx-data
            [(cond-> {:seon.agent/id (get ids allocation-key)
                      :seon.agent/parent [:seon.agent/id parent]}
               purpose (assoc :seon.agent/purpose purpose))]})
         :seon.db/conn conn})
      (.then
        (fn [env]
          (case allocation-key
            ::child (set! child-id (get-in env [::db.id/ids ::child]))
            ::gchild (set! gchild-id (get-in env [::db.id/ids ::gchild]))
            nil)
          env))))

(defn- render [id]
  (sub/subagents-block {:seon.db/db @db/*conn* :seon.agent/id id}))

(deftest subagents-childless-renders-nothing
  (async done
    (-> (with-conn (fn [_] (is (= "" (render parent-id)) "childless -> reactive vanish")))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw " e)) (done))))))

(deftest subagents-running-child-shows-progress
  (async done
    (-> (with-conn
          (fn ^:async af [conn]
            (await (add-child! conn ::child parent-id "research duckdb"))
            (await (run/open-run! {:seon.agent/id child-id :seon.agent.run/trigger :message}))
            (let [out (render parent-id)]
              (is (re-find (re-pattern child-id) out) "names the child")
              (is (re-find #"running" out) "shows running state")
              (is (re-find #"turn 0/" out) "shows turn i/limit"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw " e)) (done))))))

(deftest subagents-completed-child-shows-result
  (async done
    (-> (with-conn
          (fn ^:async af [conn]
            (await (add-child! conn ::child parent-id "compute"))
            (let [snap (await (run/open-run! {:seon.agent/id child-id :seon.agent.run/trigger :message}))
                  rid  (:seon.agent.run/id snap)]
              (await (d/transact! conn {:tx-data [{:seon.agent.run/id rid :seon.agent.run/result "the answer is 42"}]}))
              (await (run/close-run! {:seon.agent.run/id rid :seon.agent.run/closed-reason :completed}))
              (let [out (render parent-id)]
                (is (re-find #"completed" out) "shows completed")
                (is (re-find #"answer is 42" out) "shows the result string")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw " e)) (done))))))

(deftest subagents-error-closed-child-shows-death
  (async done
    (-> (with-conn
          (fn ^:async af [conn]
            (await (add-child! conn ::child parent-id "risky"))
            (let [snap (await (run/open-run! {:seon.agent/id child-id :seon.agent.run/trigger :message}))
                  rid  (:seon.agent.run/id snap)]
              (await (run/close-run! {:seon.agent.run/id rid :seon.agent.run/closed-reason :error}))
              (let [out (render parent-id)]
                (is (re-find #"error" out) "a dead child is visible, not just a succeeded one")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw " e)) (done))))))

(deftest subagents-direct-children-only
  (async done
    (-> (with-conn
          (fn ^:async af [conn]
            (await (add-child! conn ::child parent-id "child"))
            (await (add-child! conn ::gchild child-id "grandchild"))
            (let [out (render parent-id)]
              (is (re-find (re-pattern child-id) out) "direct child present")
              (is (not (re-find (re-pattern gchild-id) out)) "grandchild ABSENT (direct only)"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw " e)) (done))))))

(deftest subagents-token-capped
  (async done
    (-> (with-conn
          (fn ^:async af [conn]
            (dotimes [i 5]
              (await (add-child! conn
                                 (keyword "seon.agent.ctx.subagents-test"
                                          (str "kid-" i))
                                 parent-id
                                 (apply str (repeat 300 "x")))))
            (let [out (render parent-id)]
              (is (<= (tokens/estimate out) 900) "section bounded in TOKENS"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw " e)) (done))))))

(deftest orphaned-agents-empty-normally
  (async done
    (-> (with-conn
          (fn ^:async af [conn]
            (await (add-child! conn ::child parent-id "alive"))
            (is (= "" (sub/orphaned-agents-block {:seon.db/db @db/*conn*}))
                "no orphans -> reactive vanish")))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw " e)) (done))))))

(deftest orphaned-agents-shows-live-child-of-terminated-parent
  (async done
    (-> (with-conn
          (fn ^:async af [conn]
            (await (add-child! conn ::child parent-id "orphan"))
            (await (d/transact! conn {:tx-data [{:seon.agent/id parent-id
                                                 :seon.agent/terminated-at (js/Date.)}]}))
            (let [out (sub/orphaned-agents-block {:seon.db/db @db/*conn*})]
              (is (re-find (re-pattern child-id) out) "orphan child listed")
              (is (re-find (re-pattern parent-id) out) "names the terminated parent"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw " e)) (done))))))

(deftest orphaned-agents-excludes-terminated-child
  (async done
    (-> (with-conn
          (fn ^:async af [conn]
            (await (add-child! conn ::child parent-id "dead-orphan"))
            (await (d/transact! conn {:tx-data [{:seon.agent/id parent-id :seon.agent/terminated-at (js/Date.)}
                                                {:seon.agent/id child-id :seon.agent/terminated-at (js/Date.)}]}))
            (is (= "" (sub/orphaned-agents-block {:seon.db/db @db/*conn*}))
                "a terminated child of a terminated parent is NOT a live orphan")))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw " e)) (done))))))
