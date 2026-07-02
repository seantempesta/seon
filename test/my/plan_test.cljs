(ns my.plan-test
  "Envelope-contract tests for my.plan — the exemplar store/retrieve ns.
   Covers step!/done!/reopen! happy + failure paths, owner scoping (ALS
   default + explicit), the resume property (open items persist; every list
   re-derives from the conn), and the pure plan-body view. All on a
   FRESH :memory conn seeded like the pod boots — never the live agent conn."
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.client :as client]
    [seon.db :as db]
    [my.plan :as plan]
    [my.plan.internal :as plan-int]))

(def ^:private a-id "plantest-agent-a")
(def ^:private b-id "plantest-agent-b")
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
  (mapv :my.plan/title (:my.plan/steps env)))

(deftest step-stores-a-fully-formed-open-step
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (plan/step! {:my.plan/title "audit the schemas"
                            :my.plan/description "all of them"
                            :my.plan/owner a-ref
                            :my.plan/from  [:seon.user/id "user"]})
                (.then
                  (fn [{ok? :my.plan/ok? id :my.plan/id}]
                    (is (true? ok?))
                    (is (string? id) "response carries the durable id")
                    (let [t (d/pull @conn
                                    '[* {:my.plan/owner [:seon.agent/id]
                                         :my.plan/from  [:seon.user/id]}]
                                    [:my.plan/id id])]
                      (is (= "audit the schemas" (:my.plan/title t)))
                      (is (= "all of them" (:my.plan/description t)))
                      (is (= :open (:my.plan/status t)))
                      (is (inst? (:my.plan/created-at t)))
                      (is (= a-id (get-in t [:my.plan/owner :seon.agent/id])))
                      (is (= "user" (get-in t [:my.plan/from :seon.user/id])))
                      (is (nil? (:my.plan/completed-at t))
                          "open item: completed-at ABSENT, never nil")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest add-defaults-owner-from-agent-scope
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (db/with-agent a-id
                  (fn [] (plan/step! {:my.plan/title "note to self"})))
                (.then
                  (fn [{ok? :my.plan/ok? id :my.plan/id}]
                    (is (true? ok?))
                    (is (= a-id
                           (get-in (d/pull @conn
                                           '[{:my.plan/owner [:seon.agent/id]}]
                                           [:my.plan/id id])
                                   [:my.plan/owner :seon.agent/id]))
                        "owner defaulted to the ALS agent"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest add-guards-blank-title-and-missing-owner
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (plan/step! {:my.plan/title "  " :my.plan/owner a-ref})
                (.then (fn [{ok? :my.plan/ok? error :my.plan/error}]
                         (is (false? ok?))
                         (is (re-find #"blank" error))
                         (plan/step! {:my.plan/title "orphan"}))) ; no scope
                (.then (fn [{ok? :my.plan/ok? error :my.plan/error}]
                         (is (false? ok?))
                         (is (re-find #"with-agent" error) "names the fix")
                         (is (empty? (d/q '[:find ?t :where [?t :my.plan/id _]]
                                          @conn))
                             "nothing stored on either failure"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest the-store-retrieve-arc-with-resume
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (plan/step! {:my.plan/title "first (oldest)" :my.plan/owner a-ref})
                ;; backdate "first" so oldest-first ordering is deterministic
                (.then (fn [{id :my.plan/id}]
                         (d/transact! conn
                           {:tx-data [{:my.plan/id id
                                       :my.plan/created-at
                                       (js/Date. (- (js/Date.now) 120000))}]})))
                (.then (fn [_] (plan/step! {:my.plan/title "second"
                                           :my.plan/owner a-ref})))
                (.then (fn [_] (plan/step! {:my.plan/title "b's item"
                                           :my.plan/owner b-ref})))
                (.then
                  (fn [{ok? :my.plan/ok?}]
                    (is (true? ok?))
                    ;; RESUME: everything below re-derives from the conn —
                    ;; no in-memory state survives from the adds.
                    (is (= ["first (oldest)" "second"]
                           (open-titles (plan/list-open {:my.plan/owner a-ref})))
                        "owner-scoped, oldest first, b's item excluded")
                    (is (= ["first (oldest)" "second" "b's item"]
                           (open-titles (plan/list-open {:my.plan/all? true})))
                        "all? widens across owners")
                    (let [block (plan-int/plan-body @conn a-ref)
                          ids   (mapv :my.plan/id
                                      (:my.plan/steps
                                        (plan/list-open {:my.plan/owner a-ref})))]
                      (is (and (str/includes? block "my.plan/done!")
                               (str/includes? block ":my.plan/id"))
                          "header teaches the done! call — names the fn and its :my.plan/id arg")
                      (is (and (seq ids) (every? #(str/includes? block %) ids))
                          "every open row renders its durable id — actionable without a query")
                      (is (str/includes? block "first (oldest)")
                          "the oldest step's title renders in the block")
                      (is (< (str/index-of block "first (oldest)")
                             (str/index-of block "second"))
                          "oldest first — `first (oldest)` precedes the newer `second`"))
                    (let [id (-> (plan/list-open {:my.plan/owner a-ref})
                                 :my.plan/steps first :my.plan/id)]
                      (-> (plan/done! {:my.plan/id id})
                          (.then (fn [{ok? :my.plan/ok?}]
                                   (is (true? ok?))
                                   (is (inst? (:my.plan/completed-at
                                                (d/pull @conn '[*] [:my.plan/id id])))
                                       "completed-at stamped")
                                   (is (= ["second"]
                                          (open-titles
                                            (plan/list-open {:my.plan/owner a-ref})))
                                       "done item left the derived view")
                                   (plan/done! {:my.plan/id id})))
                          (.then (fn [{ok? :my.plan/ok?}]
                                   (is (true? ok?) "already-done is idempotent")
                                   (plan/reopen! {:my.plan/id id})))
                          (.then (fn [{ok? :my.plan/ok?}]
                                   (is (true? ok?))
                                   (is (nil? (:my.plan/completed-at
                                               (d/pull @conn '[*] [:my.plan/id id])))
                                       "reopen! RETRACTED completed-at")
                                   (is (= ["first (oldest)" "second"]
                                          (open-titles
                                            (plan/list-open {:my.plan/owner a-ref})))
                                       "reopened item is back, still oldest first"))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest done-unknown-id-is-an-envelope
  (async done
    (-> (with-conn
          (fn [_]
            (-> (plan/done! {:my.plan/id "zzz-0000000000"})
                (.then (fn [{ok? :my.plan/ok? error :my.plan/error}]
                         (is (false? ok?))
                         (is (re-find #"list-open" error) "points at the fix"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest block-is-empty-when-no-open-work
  (async done
    (-> (with-conn
          (fn [conn]
            (is (= "" (plan-int/plan-body @conn a-ref))
                "no open items → empty block, the section vanishes")
            (is (= "" (plan-int/plan-body @conn [:seon.agent/id "ghost"]))
                "unknown owner → empty block, not a throw")))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest recently-completed-band-recalls-closed-work
  ;; #71: open-only render makes closed setup invisible, so an agent re-does
  ;; it. The block now carries a bounded "Recently completed" recall band
  ;; (derived from ::completed-at, nothing stored) — verify it renders the
  ;; closed title (with the do-NOT-redo cue) while the section still vanishes
  ;; for a truly-idle agent.
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (plan/step! {:my.plan/title "set up KB schema"
                            :my.plan/owner a-ref})
                (.then (fn [{id :my.plan/id}]
                         (-> (plan/step! {:my.plan/title "write the plan"
                                         :my.plan/owner a-ref})
                             (.then (fn [_] (plan/done! {:my.plan/id id}))))))
                (.then
                  (fn [_]
                    (let [block (plan-int/plan-body @conn a-ref)]
                      (is (str/includes? block "write the plan")
                          "the still-open item renders")
                      (is (str/includes? block "Recently completed")
                          "a recall band appears once something is done")
                      (is (str/includes? block "set up KB schema")
                          "the CLOSED setup title is visible — the agent's memory it was done")
                      (is (str/includes? block "✓")
                          "done lines carry a ✓ marker, distinct from open lines"))
                    ;; close everything — band persists, section does NOT vanish
                    (let [open-id (-> (plan/list-open {:my.plan/owner a-ref})
                                      :my.plan/steps first :my.plan/id)]
                      (-> (plan/done! {:my.plan/id open-id})
                          (.then (fn [_]
                                   (let [block (plan-int/plan-body @conn a-ref)]
                                     (is (str/includes? block "Recently completed")
                                         "done-only still renders the recall band")
                                     (is (not (str/includes? block "open work items"))
                                         "no open items → the open section is gone")
                                     (is (= "" (plan-int/plan-body
                                                 @conn [:seon.agent/id "ghost"]))
                                         "truly-idle agent (no open, no done) → still vanishes")))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest section-tolerates-absent-db
  ;; The composer-input contract: `:seon.db/db` is the render snapshot
  ;; when present, and ABSENT db defaults to the current conn — the
  ;; same convention as every other core section fn. Regression
  ;; for the [open-steps] render-failed crash-loop (C-14 smell 1,
  ;; 2026-06-11): nil db reached plan-body's instrumented
  ;; :catn slot and every render printed :malli.core/invalid-input.
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (plan/step! {:my.plan/title "live item"
                            :my.plan/owner a-ref})
                (.then
                  (fn [_]
                    (is (re-find #"live item"
                                 (plan-int/plan-block
                                   {:seon.db/db @conn :seon.agent/id a-id}))
                        "db present → renders against that snapshot")
                    (is (re-find #"live item"
                                 (plan-int/plan-block
                                   {:seon.agent/id a-id}))
                        "db absent → defaults to the current conn, no throw")
                    (is (= "" (plan-int/plan-block
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
              (-> (plan/plan!
                    {:my.plan/title "Process inbox → KB"
                     :my.plan/children
                     [{:my.plan/title "process notes-a.md" :my.plan/ref "a"}
                      {:my.plan/title "process notes-b.md" :my.plan/ref "b"}
                      {:my.plan/title "synthesize findings"
                       :my.plan/ref "syn" :my.plan/after ["a" "b"]}]})
                  (.then (fn [{:my.plan/keys [ok? root ids]}]
                           (reset! st {:root root :ids ids})
                           (is (true? ok?) "plan! committed in ONE tx")
                           (is (string? root))
                           (is (= #{:root "a" "b" "syn"} (set (keys ids)))
                               "label→id map returned for the root + each :ref node")
                           (let [sub  (plan/tree {:my.plan/root? root})
                                 kids (:my.plan/_parent sub)
                                 syn  (some #(when (= (get ids "syn") (:my.plan/id %)) %) kids)]
                             (is (= 3 (count kids)) "plan! linked 3 children under root in one tx")
                             (is (= 2 (count (:my.plan/depends-on syn)))
                                 "syn's two dependency edges landed in the SAME tx"))
                           (is (= #{"process notes-a.md" "process notes-b.md"}
                                  (set (map :my.plan/title (plan/next {}))))
                               "next surfaces ONLY ready leaves — syn is blocked")
                           (is (:my.plan/ready? (plan/status {:my.plan/id (get ids "a")}))
                               "an open free leaf is ready")
                           (is (false? (:my.plan/blocked? (plan/status {:my.plan/id (get ids "a")}))))
                           (is (:my.plan/blocked? (plan/status {:my.plan/id (get ids "syn")}))
                               "syn is blocked while its deps have open work")
                           (is (false? (:my.plan/ready? (plan/status {:my.plan/id (get ids "syn")}))))
                           (is (= {:my.plan/done 0 :my.plan/total 3}
                                  (:my.plan/progress (plan/status {:my.plan/id root})))
                               "root roll-up counts its 3 leaves, none done")
                           (plan/done! {:my.plan/id (get ids "a")})))
                  (.then (fn [_] (plan/done! {:my.plan/id (get-in @st [:ids "b"])})))
                  (.then (fn [_]
                           (let [{:keys [root ids]} @st]
                             (is (= ["synthesize findings"]
                                    (mapv :my.plan/title (plan/next {})))
                                 "completing both deps unblocks syn — now the one ready leaf")
                             (is (false? (:my.plan/blocked?
                                           (plan/status {:my.plan/id (get ids "syn")}))))
                             (is (= {:my.plan/done 2 :my.plan/total 3}
                                    (:my.plan/progress (plan/status {:my.plan/id root})))
                                 "roll-up advances as leaves close — nothing stored"))
                           (plan/drop! {:my.plan/id (:root @st)})))
                  (.then (fn [{:my.plan/keys [ok? dropped]}]
                           (is (true? ok?))
                           (is (= 4 dropped)
                               "drop! walked the subtree: root + 3 children (plain ref, no cascade)")
                           (is (empty? (plan/next {})) "queue empty after drop!")
                           (is (empty? (:my.plan/steps
                                         (plan/list-open {:my.plan/all? true})))
                               "no open steps remain — the whole subtree was retracted"))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest add-parent-and-depends-structure-and-block-the-queue
  (async done
    (let [st (atom {})]
      (-> (with-agent-conn
            (fn []
              (-> (plan/step! {:my.plan/title "milestone"})
                  (.then (fn [{p :my.plan/id}]
                           (swap! st assoc :p p)
                           (plan/step! {:my.plan/title "step 1"
                                       :my.plan/parent [:my.plan/id p]})))
                  (.then (fn [{s1 :my.plan/id}]
                           (swap! st assoc :s1 s1)
                           (plan/step! {:my.plan/title "step 2"
                                       :my.plan/parent [:my.plan/id (:p @st)]
                                       :my.plan/depends-on [[:my.plan/id s1]]})))
                  (.then (fn [{s2 :my.plan/id}]
                           (swap! st assoc :s2 s2)
                           (is (= #{"step 1"}
                                  (set (map :my.plan/title (plan/next {}))))
                               "step!-built dependency blocks step 2 — only step 1 ready")
                           (is (:my.plan/blocked? (plan/status {:my.plan/id s2})))
                           (is (= {:my.plan/done 0 :my.plan/total 2}
                                  (:my.plan/progress
                                    (plan/status {:my.plan/id (:p @st)})))
                               "milestone roll-up = 0/2 over its leaves; the parent is never offered")
                           (plan/done! {:my.plan/id (:s1 @st)})))
                  (.then (fn [_]
                           (is (= #{"step 2"}
                                  (set (map :my.plan/title (plan/next {}))))
                               "completing step 1 unblocks step 2")
                           (plan/step! {:my.plan/title "step 3"})))
                  (.then (fn [{s3 :my.plan/id}]
                           (swap! st assoc :s3 s3)
                           (plan/depends! {:my.plan/id (:s2 @st)
                                           :my.plan/on [[:my.plan/id s3]]})))
                  (.then (fn [{ok? :my.plan/ok?}]
                           (is (true? ok?))
                           (is (:my.plan/blocked?
                                 (plan/status {:my.plan/id (:s2 @st)}))
                               "depends! on the still-open step 3 RE-blocks step 2"))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest move-reparents-a-node-in-the-tree
  (async done
    (let [st (atom {})]
      (-> (with-agent-conn
            (fn []
              (-> (plan/step! {:my.plan/title "plan A"})
                  (.then (fn [{p1 :my.plan/id}]
                           (swap! st assoc :p1 p1)
                           (plan/step! {:my.plan/title "plan B"})))
                  (.then (fn [{p2 :my.plan/id}]
                           (swap! st assoc :p2 p2)
                           (plan/step! {:my.plan/title "leaf"
                                       :my.plan/parent [:my.plan/id (:p1 @st)]})))
                  (.then (fn [{lf :my.plan/id}]
                           (swap! st assoc :leaf lf)
                           (is (= 1 (count (:my.plan/_parent
                                             (plan/tree {:my.plan/root? (:p1 @st)}))))
                               "leaf starts under plan A")
                           (is (nil? (:my.plan/_parent
                                       (plan/tree {:my.plan/root? (:p2 @st)})))
                               "plan B starts childless")
                           (plan/move! {:my.plan/id lf
                                        :my.plan/parent [:my.plan/id (:p2 @st)]})))
                  (.then (fn [{ok? :my.plan/ok?}]
                           (is (true? ok?))
                           (is (nil? (:my.plan/_parent
                                       (plan/tree {:my.plan/root? (:p1 @st)})))
                               "move! retracted the old parent edge — plan A now childless")
                           (is (= 1 (count (:my.plan/_parent
                                             (plan/tree {:my.plan/root? (:p2 @st)}))))
                               "move! re-parented the leaf under plan B"))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))
