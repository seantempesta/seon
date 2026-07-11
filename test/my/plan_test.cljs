(ns my.plan-test
  "Envelope-contract tests for my.plan — the exemplar store/retrieve ns.
   Covers step!/done!/reopen! happy + failure paths, agent scoping (the
   INJECTED `:seon.agent/id` default + explicit), the resume property (open
   items persist; every list re-derives from the conn), and the pure
   plan-body view. All on a FRESH :memory conn seeded like the pod boots —
   never the live agent conn.

   The verbs are INSTRUMENTED for this ns (the same `register-target!` →
   `mi/instrument!` path the pod boots with) so the declared-key injection
   — omit `:seon.agent/id`, the wrapper fills the calling agent — is
   exercised for real, not stubbed. Teardown unstruments."
  (:require
    [cljs.test :refer [deftest is async use-fixtures]]
    [clojure.string :as str]
    [datahike.api :as d]
    [malli.instrument :as mi]
    [seon.client :as client]
    [seon.db :as db]
    [seon.instrument :as inst]
    [my.plan :as plan]
    [my.plan.internal :as plan-int]))

;; 14-char ids — the :seon.db/id shape the :seon.agent/id schema validates.
(def ^:private a-id "plantestagentA")
(def ^:private b-id "plantestagentB")
(def ^:private a-ref [:seon.agent/id a-id])
(def ^:private b-ref [:seon.agent/id b-id])

(def ^:private verb-schemas
  "fn-sym → its :malli/schema, for every my.plan public verb."
  {'step!     (:malli/schema (meta #'plan/step!))
   'plan!     (:malli/schema (meta #'plan/plan!))
   'active!   (:malli/schema (meta #'plan/active!))
   'done!     (:malli/schema (meta #'plan/done!))
   'reopen!   (:malli/schema (meta #'plan/reopen!))
   'needs!    (:malli/schema (meta #'plan/needs!))
   'move!     (:malli/schema (meta #'plan/move!))
   'drop!     (:malli/schema (meta #'plan/drop!))
   'next      (:malli/schema (meta #'plan/next))
   'tree      (:malli/schema (meta #'plan/tree))
   'status    (:malli/schema (meta #'plan/status))
   'list-open (:malli/schema (meta #'plan/list-open))})

(defn- instrument-verbs! []
  (doseq [[fn-sym schema] verb-schemas]
    (inst/register-target! 'my.plan fn-sym schema false))
  (mi/instrument! {:filters [(fn [n _ _] (= n 'my.plan))]}))

(defn- uninstrument-verbs! []
  (mi/unstrument! {:filters [(fn [n _ _] (= n 'my.plan))]}))

(use-fixtures :once {:before instrument-verbs! :after uninstrument-verbs!})

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
                            :seon.agent/id a-id
                            :my.plan/from  [:seon.user/id "user"]})
                (.then
                  (fn [{ok? :my.plan/ok? id :my.plan/id}]
                    (is (true? ok?))
                    (is (string? id) "response carries the durable id")
                    (let [t (d/pull @conn
                                    '[* {:my.plan/agent [:seon.agent/id]
                                         :my.plan/from  [:seon.user/id]}]
                                    [:my.plan/id id])]
                      (is (= "audit the schemas" (:my.plan/title t)))
                      (is (= "all of them" (:my.plan/description t)))
                      (is (= :open (:my.plan/status t)))
                      (is (inst? (:my.plan/created-at t)))
                      (is (= a-id (get-in t [:my.plan/agent :seon.agent/id])))
                      (is (= "user" (get-in t [:my.plan/from :seon.user/id])))
                      (is (nil? (:my.plan/completed-at t))
                          "open item: completed-at ABSENT, never nil")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest step-defaults-agent-from-agent-scope
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
                                           '[{:my.plan/agent [:seon.agent/id]}]
                                           [:my.plan/id id])
                                   [:my.plan/agent :seon.agent/id]))
                        "agent defaulted to the ALS agent"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest step-guards-blank-title-and-missing-agent
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (plan/step! {:my.plan/title "  " :seon.agent/id a-id})
                (.then (fn [{ok? :my.plan/ok? error :my.plan/error}]
                         (is (false? ok?))
                         (is (re-find #"blank" error))
                         (plan/step! {:my.plan/title "orphan"}))) ; no scope
                (.then (fn [{ok? :my.plan/ok? error :my.plan/error}]
                         (is (false? ok?))
                         (is (re-find #"agent turn" error) "names the fix")
                         (is (empty? (d/q '[:find ?t :where [?t :my.plan/id _]]
                                          @conn))
                             "nothing stored on either failure"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest the-store-retrieve-arc-with-resume
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (plan/step! {:my.plan/title "first (oldest)" :seon.agent/id a-id})
                ;; backdate "first" so oldest-first ordering is deterministic
                (.then (fn [{id :my.plan/id}]
                         (d/transact! conn
                           {:tx-data [{:my.plan/id id
                                       :my.plan/created-at
                                       (js/Date. (- (js/Date.now) 120000))}]})))
                (.then (fn [_] (plan/step! {:my.plan/title "second"
                                           :seon.agent/id a-id})))
                (.then (fn [_] (plan/step! {:my.plan/title "b's item"
                                           :seon.agent/id b-id})))
                (.then
                  (fn [{ok? :my.plan/ok?}]
                    (is (true? ok?))
                    ;; RESUME: everything below re-derives from the conn —
                    ;; no in-memory state survives from the adds.
                    (is (= ["first (oldest)" "second"]
                           (open-titles (plan/list-open {:seon.agent/id a-id})))
                        "agent-scoped, oldest first, b's item excluded")
                    (is (= ["first (oldest)" "second" "b's item"]
                           (open-titles (plan/list-open {:my.plan/all? true})))
                        "all? widens across agents")
                    (let [block (plan-int/plan-body @conn a-ref)
                          ids   (mapv :my.plan/id
                                      (:my.plan/steps
                                        (plan/list-open {:seon.agent/id a-id})))]
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
                    (let [id (-> (plan/list-open {:seon.agent/id a-id})
                                 :my.plan/steps first :my.plan/id)]
                      (-> (plan/done! {:my.plan/id id})
                          (.then (fn [{ok? :my.plan/ok?}]
                                   (is (true? ok?))
                                   (is (inst? (:my.plan/completed-at
                                                (d/pull @conn '[*] [:my.plan/id id])))
                                       "completed-at stamped")
                                   (is (= ["second"]
                                          (open-titles
                                            (plan/list-open {:seon.agent/id a-id})))
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
                                            (plan/list-open {:seon.agent/id a-id})))
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

(deftest block-teaches-when-no-open-work
  ;; Colocation (2026-07-10): the no-plan state renders the block's OWN
  ;; decompose-first teaching (byte-stable) instead of vanishing — the
  ;; empty state is exactly when nothing else teaches the workflow.
  (async done
    (-> (with-conn
          (fn [conn]
            (is (= plan-int/empty-plan-teaching (plan-int/plan-body @conn a-ref))
                "no open items → the block teaches decompose-first")
            (is (= plan-int/empty-plan-teaching
                   (plan-int/plan-body @conn [:seon.agent/id "ghost"]))
                "unknown agent → the same teaching, not a throw")))
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
                            :seon.agent/id a-id})
                (.then (fn [{id :my.plan/id}]
                         (-> (plan/step! {:my.plan/title "write the plan"
                                         :seon.agent/id a-id})
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
                    (let [open-id (-> (plan/list-open {:seon.agent/id a-id})
                                      :my.plan/steps first :my.plan/id)]
                      (-> (plan/done! {:my.plan/id open-id})
                          (.then (fn [_]
                                   (let [block (plan-int/plan-body @conn a-ref)]
                                     (is (str/includes? block "Recently completed")
                                         "done-only still renders the recall band")
                                     (is (not (str/includes? block "Open frontier"))
                                         "no open items → the open section is gone")
                                     (is (= plan-int/empty-plan-teaching
                                            (plan-int/plan-body
                                              @conn [:seon.agent/id "ghost"]))
                                         "truly-idle agent (no open, no done) → the teaching header")))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest open-root-with-all-done-children-is-ready-to-close
  ;; Frontier regression: an :open ROOT whose children are all :done is
  ;; neither a leaf (so the old (leaf ?t) `ready` rule skipped it) nor :done
  ;; (so the recall band skipped it) — it went INVISIBLE, the one node whose
  ;; remaining action is verify-and-close. The extended `ready` rule surfaces
  ;; a drained open non-leaf; genuinely-incomplete subtrees still frontier
  ;; their open leaves, not the parent.
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (plan/plan! {:my.plan/title "verify the sum end to end"
                             :my.plan/children
                             [{:my.plan/title "compute the sum" :my.plan/ref "a"}
                              {:my.plan/title "report the sum" :my.plan/ref "b"}]
                             :seon.agent/id a-id})
                (.then
                  (fn [{ok? :my.plan/ok? root :my.plan/root ids :my.plan/ids}]
                    (is (true? ok?))
                    (is (not (plan-int/ready? @conn root))
                        "an incomplete parent is not yet ready-to-close")
                    (is (plan-int/ready? @conn (get ids "a"))
                        "an open leaf child is ready — normal frontier intact")
                    (-> (plan/done! {:my.plan/id (get ids "a")})
                        (.then (fn [_] (plan/done! {:my.plan/id (get ids "b")})))
                        (.then
                          (fn [_]
                            (is (= :open (:my.plan/status
                                           (d/pull @conn '[*] [:my.plan/id root])))
                                "root stayed :open — nobody closed it")
                            (is (plan-int/ready? @conn root)
                                "drained open non-leaf is READY (verify and close)")
                            (is (some #(= root (:my.plan/id %))
                                      (plan-int/ready-leaves
                                        @conn (plan-int/agent-eid @conn a-ref)))
                                "root surfaces in the ready set")
                            (let [block (plan-int/plan-body @conn a-ref)]
                              (is (str/includes? block "verify the sum end to end")
                                  "the open root renders in the plan block")
                              (is (str/includes? block "Open frontier")
                                  "it lands in the actionable frontier band"))))))))))
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
                            :seon.agent/id a-id})
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
                    (is (= plan-int/empty-plan-teaching
                           (plan-int/plan-block {:seon.agent/id b-id}))
                        "other agent, no items → the decompose-first teaching"))))))
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
                             (is (= 2 (count (:my.plan/needs syn)))
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

(deftest step-parent-and-needs-structure-and-block-the-queue
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
                                       :my.plan/needs [[:my.plan/id s1]]})))
                  (.then (fn [{s2 :my.plan/id}]
                           (swap! st assoc :s2 s2)
                           (is (= #{"step 1"}
                                  (set (map :my.plan/title (plan/next {}))))
                               "step!-built needs edge blocks step 2 — only step 1 ready")
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
                           (plan/needs! {:my.plan/id (:s2 @st)
                                           :my.plan/on [[:my.plan/id s3]]})))
                  (.then (fn [{ok? :my.plan/ok?}]
                           (is (true? ok?))
                           (is (:my.plan/blocked?
                                 (plan/status {:my.plan/id (:s2 @st)}))
                               "needs! on the still-open step 3 RE-blocks step 2"))))))
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

;; --- planning redesign (deps as `needs`, :active position, windowed render).
;; --- Assert MECHANISM (anchor derives, frontier caps, interior drops,
;; --- stored :blocked excludes from ready), never exact rendered phrasing.

(deftest active-sets-the-position-and-demotes-the-previous
  (async done
    (let [st (atom {})]
      (-> (with-agent-conn
            (fn []
              (-> (plan/plan!
                    {:my.plan/title "two-front plan"
                     :my.plan/children
                     [{:my.plan/title "front a" :my.plan/ref "a"}
                      {:my.plan/title "front b" :my.plan/ref "b"}]})
                  (.then (fn [{:my.plan/keys [ok? ids]}]
                           (is (true? ok?))
                           (reset! st ids)
                           (plan/active! {:my.plan/id (get ids "a")})))
                  (.then (fn [{ok? :my.plan/ok?}]
                           (is (true? ok?))
                           (is (= :active (plan-int/status-of (get @st "a")))
                               "active! stores the :active status")
                           (plan/active! {:my.plan/id (get @st "b")})))
                  (.then (fn [{ok? :my.plan/ok?}]
                           (is (true? ok?))
                           (is (= :active (plan-int/status-of (get @st "b"))))
                           (is (= :open (plan-int/status-of (get @st "a")))
                               "one position at a time — the previous active demotes to :open")
                           (plan/done! {:my.plan/id (get @st "b")})))
                  (.then (fn [_] (plan/active! {:my.plan/id (get @st "b")})))
                  (.then (fn [{ok? :my.plan/ok? error :my.plan/error}]
                           (is (false? ok?) "a :done step cannot be taken up")
                           (is (re-find #"reopen!" error) "names the fix"))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest stored-blocked-status-excludes-from-ready
  (async done
    (let [st (atom {})]
      (-> (with-agent-conn
            (fn []
              (-> (plan/step! {:my.plan/title "waiting on the human"})
                  (.then (fn [{id :my.plan/id}]
                           (swap! st assoc :id id)
                           (is (:my.plan/ready? (plan/status {:my.plan/id id}))
                               "open free leaf starts ready")
                           (db/transact!
                             {:seon.db/tx-data [{:my.plan/id id
                                                 :my.plan/status :blocked}]})))
                  (.then (fn [_]
                           (let [id (:id @st)]
                             (is (:my.plan/blocked? (plan/status {:my.plan/id id}))
                                 "stored :blocked derives blocked? true")
                             (is (false? (:my.plan/ready? (plan/status {:my.plan/id id}))))
                             (is (empty? (plan/next {}))
                                 "a :blocked step never enters the focus queue")
                             (is (= [(:id @st)]
                                    (mapv :my.plan/id
                                          (:my.plan/steps (plan/list-open {}))))
                                 "list-open still surfaces it — unfinished, not done")
                             (plan/reopen! {:my.plan/id id}))))
                  (.then (fn [{ok? :my.plan/ok?}]
                           (is (true? ok?))
                           (is (:my.plan/ready? (plan/status {:my.plan/id (:id @st)}))
                               "reopen! flips :blocked back to ready"))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest windowed-render-anchor-frontier-and-dropped-interior
  ;; The drive's #1 failure (research/long-horizon-plan-drive-2026-07-02):
  ;; the old open-items-only render went silent on success and carried no
  ;; position. The windowed render must (a) anchor the position (goal +
  ;; active step + roll-up), (b) prompt verify-before-done! off ::expect,
  ;; (c) cap the frontier, and (d) DROP the completed interior while the
  ;; recent tail keeps resume grounding.
  (async done
    (let [st (atom {})]
      (-> (with-agent-conn
            (fn []
              (-> (plan/plan!
                    {:my.plan/title "big tracker"
                     :my.plan/goal  "a tracker the human keeps using"
                     :my.plan/pace  :multi-session
                     :my.plan/children
                     (vec (for [i (range 10)]
                            {:my.plan/title (str "leaf-" i "-x")
                             :my.plan/ref   (str "c" i)
                             :my.plan/expect (str "outcome-" i " holds")}))})
                  (.then (fn [{:my.plan/keys [ok? ids]}]
                           (is (true? ok?))
                           (reset! st ids)
                           ;; close the interior: c0..c6 done
                           (reduce (fn [p i]
                                     (.then p (fn [_]
                                                (plan/done!
                                                  {:my.plan/id (get @st (str "c" i))}))))
                                   (js/Promise.resolve nil)
                                   (range 7))))
                  ;; backdate c0/c1 completions so the tail (newest 5) is
                  ;; deterministic even when done! stamps share a millisecond
                  (.then (fn [_]
                           (db/transact!
                             {:seon.db/tx-data
                              [{:my.plan/id (get @st "c0")
                                :my.plan/completed-at (js/Date. (- (js/Date.now) 120000))}
                               {:my.plan/id (get @st "c1")
                                :my.plan/completed-at (js/Date. (- (js/Date.now) 60000))}]})))
                  (.then (fn [_] (plan/active! {:my.plan/id (get @st "c7")})))
                  (.then (fn [_]
                           (let [block (plan-int/plan-body @db/*conn*
                                                           [:seon.agent/id a-id])]
                             ;; (a) the position anchor
                             (is (str/includes? block "a tracker the human keeps using")
                                 "the root goal narrative renders every turn")
                             (is (str/includes? block "multi-session")
                                 "the pace renders — the don't-rush constraint is visible")
                             (is (str/includes? block (get @st "c7"))
                                 "the :active step id anchors the position")
                             (is (str/includes? block "7 of 10")
                                 "the roll-up narrates progress (7 of 10 done)")
                             ;; (b) verify-before-done! off the active step's expect
                             (is (str/includes? block "outcome-7 holds")
                                 "the active step's ::expect renders as the verify prompt")
                             ;; (c) the frontier: ready c8/c9 render
                             (is (str/includes? block (get @st "c8")))
                             (is (str/includes? block (get @st "c9")))
                             ;; (d) the DROPPED interior vs the recent tail
                             (is (not (str/includes? block "leaf-0-x"))
                                 "the oldest completed step is OUT of the prompt")
                             (is (not (str/includes? block "leaf-1-x"))
                                 "the second-oldest completed step is OUT too")
                             (is (str/includes? block "leaf-6-x")
                                 "a recently-completed step stays as resume grounding")
                             (is (str/includes? block "✓")
                                 "the tail is marked done, distinct from the frontier"))))
                  (.then (fn [_]
                           ;; frontier cap: reopen everything → 9 ready (c7 active)
                           (reduce (fn [p i]
                                     (.then p (fn [_]
                                                (plan/reopen!
                                                  {:my.plan/id (get @st (str "c" i))}))))
                                   (js/Promise.resolve nil)
                                   (range 7))))
                  (.then (fn [_]
                           (let [block (plan-int/plan-body @db/*conn*
                                                           [:seon.agent/id a-id])
                                 open-ids (map #(get @st (str "c" %)) (range 10))
                                 shown    (count (filter #(str/includes? block %)
                                                         open-ids))]
                             (is (<= shown (+ plan-int/frontier-limit 1))
                                 "frontier caps at the limit (+ the active step) — constant-size render")
                             (is (re-find #"more ready" block)
                                 "the overflow is narrated, not silently dropped")))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest entity-ref-direction-and-agent-retract-semantics
  ;; The settled `my.*` scoping shape: per-agent data points DATA→AGENT
  ;; (`:my.plan/agent`, a plain ref registered in my.plan — the owning ns
  ;; is the schema authority; the agent entity is never edited to gain a
  ;; domain). One VAET-indexed ref reads both ways; retracting the AGENT
  ;; retracts the incoming scoping edges (datahike retract-entity v-datoms,
  ;; transaction.cljc:897) and ORPHANS the steps — no cascade, history
  ;; keeps everything.
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (plan/step! {:my.plan/title "a's step" :seon.agent/id a-id})
                (.then (fn [_] (plan/step! {:my.plan/title "b's step"
                                            :seon.agent/id b-id})))
                (.then
                  (fn [{ok? :my.plan/ok? bid :my.plan/id}]
                    (is (true? ok?))
                    ;; forward: step → agent
                    (is (= b-id (get-in (d/pull @conn
                                                '[{:my.plan/agent [:seon.agent/id]}]
                                                [:my.plan/id bid])
                                        [:my.plan/agent :seon.agent/id]))
                        "forward pull: the step refs its owning agent")
                    ;; reverse: agent → steps via the SAME ref (_agent)
                    (is (= ["b's step"]
                           (mapv :my.plan/title
                                 (:my.plan/_agent
                                   (d/pull @conn
                                           '[{:my.plan/_agent [:my.plan/title]}]
                                           b-ref))))
                        "reverse pull: the agent reaches its steps, scoped")
                    ;; retract the AGENT entity — the design's delete semantics
                    (-> (d/transact! conn
                          {:tx-data [[:db.fn/retractEntity b-ref]]})
                        (.then
                          (fn [_]
                            (is (empty? (d/q '[:find ?e :in $ ?id
                                               :where [?e :seon.agent/id ?id]]
                                             @conn b-id))
                                "agent entity gone")
                            (let [step (d/pull @conn '[*] [:my.plan/id bid])]
                              (is (= "b's step" (:my.plan/title step))
                                  "step entity SURVIVES the agent retract — no cascade")
                              (is (nil? (:my.plan/agent step))
                                  "the scoping edge is retracted (v-datom semantics) — the step is orphaned"))
                            (is (= [] (:my.plan/steps
                                        (plan/list-open {:seon.agent/id b-id})))
                                "orphaned step invisible to any agent-scoped read")
                            (is (= ["a's step"]
                                   (open-titles
                                     (plan/list-open {:seon.agent/id a-id})))
                                "the other agent's scope is untouched")))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
