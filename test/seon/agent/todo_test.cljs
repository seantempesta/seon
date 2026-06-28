(ns seon.agent.todo-test
  "Envelope-contract tests for seon.agent.todo — the exemplar store/retrieve ns.
   Covers add!/done!/reopen! happy + failure paths, owner scoping (ALS
   default + explicit), the resume property (open items persist; every list
   re-derives from the conn), and the pure open-todos-body view. All on a
   FRESH :memory conn seeded like the pod boots — never the live agent conn."
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.client :as client]
    [seon.db :as db]
    [seon.agent.todo :as todo]
    [seon.agent.todo.internal :as todo-int]))

(def ^:private a-id "todotest-agent-a")
(def ^:private b-id "todotest-agent-b")
(def ^:private a-ref [:seon.agent/id a-id])
(def ^:private b-ref [:seon.agent/id b-id])

(defn- fresh-conn
  "Promise of a fresh :memory conn with the pod's boot schema + the user
   entity + agents A and B (the same rows seon.client seeds at boot)."
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (d/transact!
                       conn
                       {:tx-data (into (db/malli->datahike-schema
                                         client/agent-bootstrap-attrs)
                                       (db/tx-meta-datahike-schema))})
                     (.then (fn [_]
                              (d/transact!
                                conn
                                {:tx-data [{:seon.user/id "user"}
                                           {:seon.agent/id a-id}
                                           {:seon.agent/id b-id}]})))
                     (.then (fn [_] conn))))))))

(defn- with-conn
  "Fresh seeded conn, `set!` as the ROOT db/*conn* for `body` (conn →
   Promise), prior root restored after. Root set!, not `binding` — CLJS
   dynamic bindings pop at the first microtask boundary inside ^:async
   bodies (verified live; see seon.agent.message-test)."
  [body]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

(defn- with-agent-conn
  "Fresh seeded conn as the root *conn*, `body` (a 0-arg fn → Promise) run
   inside (db/with-agent a-id) so the verbs' ALS scope resolves to agent A."
  [body]
  (with-conn (fn [_] (db/with-agent a-id body))))

(defn- open-titles [env]
  (mapv :seon.agent.todo/title (:seon.agent.todo/todos env)))

(deftest add-stores-a-fully-formed-open-todo
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (todo/add! {:seon.agent.todo/title "audit the schemas"
                            :seon.agent.todo/description "all of them"
                            :seon.agent.todo/owner a-ref
                            :seon.agent.todo/from  [:seon.user/id "user"]})
                (.then
                  (fn [{ok? :seon.agent.todo/ok? id :seon.agent.todo/id}]
                    (is (true? ok?))
                    (is (string? id) "response carries the durable id")
                    (let [t (d/pull @conn
                                    '[* {:seon.agent.todo/owner [:seon.agent/id]
                                         :seon.agent.todo/from  [:seon.user/id]}]
                                    [:seon.agent.todo/id id])]
                      (is (= "audit the schemas" (:seon.agent.todo/title t)))
                      (is (= "all of them" (:seon.agent.todo/description t)))
                      (is (= :open (:seon.agent.todo/status t)))
                      (is (inst? (:seon.agent.todo/created-at t)))
                      (is (= a-id (get-in t [:seon.agent.todo/owner :seon.agent/id])))
                      (is (= "user" (get-in t [:seon.agent.todo/from :seon.user/id])))
                      (is (nil? (:seon.agent.todo/completed-at t))
                          "open item: completed-at ABSENT, never nil")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest add-defaults-owner-from-agent-scope
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (db/with-agent a-id
                  (fn [] (todo/add! {:seon.agent.todo/title "note to self"})))
                (.then
                  (fn [{ok? :seon.agent.todo/ok? id :seon.agent.todo/id}]
                    (is (true? ok?))
                    (is (= a-id
                           (get-in (d/pull @conn
                                           '[{:seon.agent.todo/owner [:seon.agent/id]}]
                                           [:seon.agent.todo/id id])
                                   [:seon.agent.todo/owner :seon.agent/id]))
                        "owner defaulted to the ALS agent"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest add-guards-blank-title-and-missing-owner
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (todo/add! {:seon.agent.todo/title "  " :seon.agent.todo/owner a-ref})
                (.then (fn [{ok? :seon.agent.todo/ok? error :seon.agent.todo/error}]
                         (is (false? ok?))
                         (is (re-find #"blank" error))
                         (todo/add! {:seon.agent.todo/title "orphan"}))) ; no scope
                (.then (fn [{ok? :seon.agent.todo/ok? error :seon.agent.todo/error}]
                         (is (false? ok?))
                         (is (re-find #"with-agent" error) "names the fix")
                         (is (empty? (d/q '[:find ?t :where [?t :seon.agent.todo/id _]]
                                          @conn))
                             "nothing stored on either failure"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest the-store-retrieve-arc-with-resume
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (todo/add! {:seon.agent.todo/title "first (oldest)" :seon.agent.todo/owner a-ref})
                ;; backdate "first" so oldest-first ordering is deterministic
                (.then (fn [{id :seon.agent.todo/id}]
                         (d/transact! conn
                           {:tx-data [{:seon.agent.todo/id id
                                       :seon.agent.todo/created-at
                                       (js/Date. (- (js/Date.now) 120000))}]})))
                (.then (fn [_] (todo/add! {:seon.agent.todo/title "second"
                                           :seon.agent.todo/owner a-ref})))
                (.then (fn [_] (todo/add! {:seon.agent.todo/title "b's item"
                                           :seon.agent.todo/owner b-ref})))
                (.then
                  (fn [{ok? :seon.agent.todo/ok?}]
                    (is (true? ok?))
                    ;; RESUME: everything below re-derives from the conn —
                    ;; no in-memory state survives from the adds.
                    (is (= ["first (oldest)" "second"]
                           (open-titles (todo/list-open {:seon.agent.todo/owner a-ref})))
                        "owner-scoped, oldest first, b's item excluded")
                    (is (= ["first (oldest)" "second" "b's item"]
                           (open-titles (todo/list-open {:seon.agent.todo/all? true})))
                        "all? widens across owners")
                    (let [block (todo-int/open-todos-body @conn a-ref)
                          ids   (mapv :seon.agent.todo/id
                                      (:seon.agent.todo/todos
                                        (todo/list-open {:seon.agent.todo/owner a-ref})))]
                      (is (and (str/includes? block "seon.agent.todo/done!")
                               (str/includes? block ":seon.agent.todo/id"))
                          "header teaches the done! call — names the fn and its :seon.agent.todo/id arg")
                      (is (and (seq ids) (every? #(str/includes? block %) ids))
                          "every open row renders its durable id — actionable without a query")
                      (is (str/includes? block "first (oldest)")
                          "the oldest todo's title renders in the block")
                      (is (< (str/index-of block "first (oldest)")
                             (str/index-of block "second"))
                          "oldest first — `first (oldest)` precedes the newer `second`"))
                    (let [id (-> (todo/list-open {:seon.agent.todo/owner a-ref})
                                 :seon.agent.todo/todos first :seon.agent.todo/id)]
                      (-> (todo/done! {:seon.agent.todo/id id})
                          (.then (fn [{ok? :seon.agent.todo/ok?}]
                                   (is (true? ok?))
                                   (is (inst? (:seon.agent.todo/completed-at
                                                (d/pull @conn '[*] [:seon.agent.todo/id id])))
                                       "completed-at stamped")
                                   (is (= ["second"]
                                          (open-titles
                                            (todo/list-open {:seon.agent.todo/owner a-ref})))
                                       "done item left the derived view")
                                   (todo/done! {:seon.agent.todo/id id})))
                          (.then (fn [{ok? :seon.agent.todo/ok?}]
                                   (is (true? ok?) "already-done is idempotent")
                                   (todo/reopen! {:seon.agent.todo/id id})))
                          (.then (fn [{ok? :seon.agent.todo/ok?}]
                                   (is (true? ok?))
                                   (is (nil? (:seon.agent.todo/completed-at
                                               (d/pull @conn '[*] [:seon.agent.todo/id id])))
                                       "reopen! RETRACTED completed-at")
                                   (is (= ["first (oldest)" "second"]
                                          (open-titles
                                            (todo/list-open {:seon.agent.todo/owner a-ref})))
                                       "reopened item is back, still oldest first"))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest done-unknown-id-is-an-envelope
  (async done
    (-> (with-conn
          (fn [_]
            (-> (todo/done! {:seon.agent.todo/id "zzz-0000000000"})
                (.then (fn [{ok? :seon.agent.todo/ok? error :seon.agent.todo/error}]
                         (is (false? ok?))
                         (is (re-find #"list-open" error) "points at the fix"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest block-is-empty-when-no-open-work
  (async done
    (-> (with-conn
          (fn [conn]
            (is (= "" (todo-int/open-todos-body @conn a-ref))
                "no open items → empty block, the section vanishes")
            (is (= "" (todo-int/open-todos-body @conn [:seon.agent/id "ghost"]))
                "unknown owner → empty block, not a throw")))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest section-tolerates-absent-db
  ;; The composer-input contract: `:seon.db/db` is the render snapshot
  ;; when present, and ABSENT db defaults to the current conn — the
  ;; same convention as every other core section fn. Regression
  ;; for the [open-todos] render-failed crash-loop (C-14 smell 1,
  ;; 2026-06-11): nil db reached open-todos-body's instrumented
  ;; :catn slot and every render printed :malli.core/invalid-input.
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (todo/add! {:seon.agent.todo/title "live item"
                            :seon.agent.todo/owner a-ref})
                (.then
                  (fn [_]
                    (is (re-find #"live item"
                                 (todo-int/open-todos-block
                                   {:seon.db/db @conn :seon.agent/id a-id}))
                        "db present → renders against that snapshot")
                    (is (re-find #"live item"
                                 (todo-int/open-todos-block
                                   {:seon.agent/id a-id}))
                        "db absent → defaults to the current conn, no throw")
                    (is (= "" (todo-int/open-todos-block
                                {:seon.agent/id b-id}))
                        "other agent, no items → empty, section vanishes"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; --- hierarchical + dependency-aware behavior (the plan tree + DAG). Assert
;; --- MECHANISM (tree builds, deps block, next = ready leaves, drop! walks the
;; --- subtree, blocked/ready derive correctly), never exact rendered strings.

(deftest plan-builds-tree-and-deps-and-derives-the-queue
  (async done
    (let [st (atom {})]
      (-> (with-agent-conn
            (fn []
              (-> (todo/plan!
                    {:seon.agent.todo/title "Process inbox → KB"
                     :seon.agent.todo/children
                     [{:seon.agent.todo/title "process notes-a.md" :seon.agent.todo/ref "a"}
                      {:seon.agent.todo/title "process notes-b.md" :seon.agent.todo/ref "b"}
                      {:seon.agent.todo/title "synthesize findings"
                       :seon.agent.todo/ref "syn" :seon.agent.todo/after ["a" "b"]}]})
                  (.then (fn [{:seon.agent.todo/keys [ok? root ids]}]
                           (reset! st {:root root :ids ids})
                           (is (true? ok?) "plan! committed in ONE tx")
                           (is (string? root))
                           (is (= #{:root "a" "b" "syn"} (set (keys ids)))
                               "label→id map returned for the root + each :ref node")
                           (let [sub  (todo/tree {:seon.agent.todo/root? root})
                                 kids (:seon.agent.todo/_parent sub)
                                 syn  (some #(when (= (get ids "syn") (:seon.agent.todo/id %)) %) kids)]
                             (is (= 3 (count kids)) "plan! linked 3 children under root in one tx")
                             (is (= 2 (count (:seon.agent.todo/depends-on syn)))
                                 "syn's two dependency edges landed in the SAME tx"))
                           (is (= #{"process notes-a.md" "process notes-b.md"}
                                  (set (map :seon.agent.todo/title (todo/next {}))))
                               "next surfaces ONLY ready leaves — syn is blocked")
                           (is (:seon.agent.todo/ready? (todo/status {:seon.agent.todo/id (get ids "a")}))
                               "an open free leaf is ready")
                           (is (false? (:seon.agent.todo/blocked? (todo/status {:seon.agent.todo/id (get ids "a")}))))
                           (is (:seon.agent.todo/blocked? (todo/status {:seon.agent.todo/id (get ids "syn")}))
                               "syn is blocked while its deps have open work")
                           (is (false? (:seon.agent.todo/ready? (todo/status {:seon.agent.todo/id (get ids "syn")}))))
                           (is (= {:seon.agent.todo/done 0 :seon.agent.todo/total 3}
                                  (:seon.agent.todo/progress (todo/status {:seon.agent.todo/id root})))
                               "root roll-up counts its 3 leaves, none done")
                           (todo/done! {:seon.agent.todo/id (get ids "a")})))
                  (.then (fn [_] (todo/done! {:seon.agent.todo/id (get-in @st [:ids "b"])})))
                  (.then (fn [_]
                           (let [{:keys [root ids]} @st]
                             (is (= ["synthesize findings"]
                                    (mapv :seon.agent.todo/title (todo/next {})))
                                 "completing both deps unblocks syn — now the one ready leaf")
                             (is (false? (:seon.agent.todo/blocked?
                                           (todo/status {:seon.agent.todo/id (get ids "syn")}))))
                             (is (= {:seon.agent.todo/done 2 :seon.agent.todo/total 3}
                                    (:seon.agent.todo/progress (todo/status {:seon.agent.todo/id root})))
                                 "roll-up advances as leaves close — nothing stored"))
                           (todo/drop! {:seon.agent.todo/id (:root @st)})))
                  (.then (fn [{:seon.agent.todo/keys [ok? dropped]}]
                           (is (true? ok?))
                           (is (= 4 dropped)
                               "drop! walked the subtree: root + 3 children (plain ref, no cascade)")
                           (is (empty? (todo/next {})) "queue empty after drop!")
                           (is (empty? (:seon.agent.todo/todos
                                         (todo/list-open {:seon.agent.todo/all? true})))
                               "no open todos remain — the whole subtree was retracted"))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest add-parent-and-depends-structure-and-block-the-queue
  (async done
    (let [st (atom {})]
      (-> (with-agent-conn
            (fn []
              (-> (todo/add! {:seon.agent.todo/title "milestone"})
                  (.then (fn [{p :seon.agent.todo/id}]
                           (swap! st assoc :p p)
                           (todo/add! {:seon.agent.todo/title "step 1"
                                       :seon.agent.todo/parent [:seon.agent.todo/id p]})))
                  (.then (fn [{s1 :seon.agent.todo/id}]
                           (swap! st assoc :s1 s1)
                           (todo/add! {:seon.agent.todo/title "step 2"
                                       :seon.agent.todo/parent [:seon.agent.todo/id (:p @st)]
                                       :seon.agent.todo/depends-on [[:seon.agent.todo/id s1]]})))
                  (.then (fn [{s2 :seon.agent.todo/id}]
                           (swap! st assoc :s2 s2)
                           (is (= #{"step 1"}
                                  (set (map :seon.agent.todo/title (todo/next {}))))
                               "add!-built dependency blocks step 2 — only step 1 ready")
                           (is (:seon.agent.todo/blocked? (todo/status {:seon.agent.todo/id s2})))
                           (is (= {:seon.agent.todo/done 0 :seon.agent.todo/total 2}
                                  (:seon.agent.todo/progress
                                    (todo/status {:seon.agent.todo/id (:p @st)})))
                               "milestone roll-up = 0/2 over its leaves; the parent is never offered")
                           (todo/done! {:seon.agent.todo/id (:s1 @st)})))
                  (.then (fn [_]
                           (is (= #{"step 2"}
                                  (set (map :seon.agent.todo/title (todo/next {}))))
                               "completing step 1 unblocks step 2")
                           (todo/add! {:seon.agent.todo/title "step 3"})))
                  (.then (fn [{s3 :seon.agent.todo/id}]
                           (swap! st assoc :s3 s3)
                           (todo/depends! {:seon.agent.todo/id (:s2 @st)
                                           :seon.agent.todo/on [[:seon.agent.todo/id s3]]})))
                  (.then (fn [{ok? :seon.agent.todo/ok?}]
                           (is (true? ok?))
                           (is (:seon.agent.todo/blocked?
                                 (todo/status {:seon.agent.todo/id (:s2 @st)}))
                               "depends! on the still-open step 3 RE-blocks step 2"))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest move-reparents-a-node-in-the-tree
  (async done
    (let [st (atom {})]
      (-> (with-agent-conn
            (fn []
              (-> (todo/add! {:seon.agent.todo/title "plan A"})
                  (.then (fn [{p1 :seon.agent.todo/id}]
                           (swap! st assoc :p1 p1)
                           (todo/add! {:seon.agent.todo/title "plan B"})))
                  (.then (fn [{p2 :seon.agent.todo/id}]
                           (swap! st assoc :p2 p2)
                           (todo/add! {:seon.agent.todo/title "leaf"
                                       :seon.agent.todo/parent [:seon.agent.todo/id (:p1 @st)]})))
                  (.then (fn [{lf :seon.agent.todo/id}]
                           (swap! st assoc :leaf lf)
                           (is (= 1 (count (:seon.agent.todo/_parent
                                             (todo/tree {:seon.agent.todo/root? (:p1 @st)}))))
                               "leaf starts under plan A")
                           (is (nil? (:seon.agent.todo/_parent
                                       (todo/tree {:seon.agent.todo/root? (:p2 @st)})))
                               "plan B starts childless")
                           (todo/move! {:seon.agent.todo/id lf
                                        :seon.agent.todo/parent [:seon.agent.todo/id (:p2 @st)]})))
                  (.then (fn [{ok? :seon.agent.todo/ok?}]
                           (is (true? ok?))
                           (is (nil? (:seon.agent.todo/_parent
                                       (todo/tree {:seon.agent.todo/root? (:p1 @st)})))
                               "move! retracted the old parent edge — plan A now childless")
                           (is (= 1 (count (:seon.agent.todo/_parent
                                             (todo/tree {:seon.agent.todo/root? (:p2 @st)}))))
                               "move! re-parented the leaf under plan B"))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))
