(ns my.plan-test
  "Envelope-contract tests for my.plan — the exemplar store/retrieve ns.
   Covers step!/done!/reopen! happy + failure paths, agent scoping (the
   INJECTED `:seon.agent/id` default + explicit), the resume property (open
   items persist; every list re-derives from the conn), and the pure
   plan-body view. All on a FRESH :memory conn seeded like the pod boots —
   never the live agent conn.

   The functions are instrumented through the pod's exact-data path so the
   declared-key injection
   — omit `:seon.agent/id`, the wrapper fills the calling agent — is
   exercised for real, not stubbed. Teardown unstruments."
  (:require
    [cljs.test :refer [deftest is async use-fixtures]]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [datahike.api :as d]
    [datahike.query :as datahike-query]
    [seon.client :as client]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.db.protocol :as protocol]
    [seon.instrument :as inst]
    [seon.schema :as schema]
    [my.plan :as plan]
    [my.plan.internal :as plan-int]))

;; Preserved 14-character ids model known agents recovered from an older store.
;; Fresh plan/eval identities in this suite are minted through db.id/allocate!.
(def ^:private a-id "plantestagentA")
(def ^:private b-id "plantestagentB")
(def ^:private a-ref [:seon.agent/id a-id])
(def ^:private b-ref [:seon.agent/id b-id])

(def ^:private coordinate
  {:seon.db.coordinate/database-id #uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
   :seon.db.coordinate/branch :db
   :seon.db.coordinate/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
   :seon.db.coordinate/t 42})

(defn- allocate-facts!
  "Mint one identity per keyed fact and commit all facts atomically."
  [conn identity-attr keyed-facts]
  (db.id/allocate!
    {::db.id/allocations
     (mapv (fn [[allocation-key _]]
             {::db.id/key allocation-key
              ::db.id/identity-attr identity-attr})
           keyed-facts)
     ::db.id/transaction-builder
     (fn [ids]
       {:seon.db/tx-data
        (mapv (fn [[allocation-key fact]]
                (assoc fact identity-attr (get ids allocation-key)))
              keyed-facts)})
     :seon.db/conn conn}))

(def ^:private function-schemas
  "fn-sym → its :malli/schema, for every my.plan public function."
  {'step!     (:malli/schema (meta #'plan/step!))
   'plan!     (:malli/schema (meta #'plan/plan!))
   'active!   (:malli/schema (meta #'plan/active!))
   'done!     (:malli/schema (meta #'plan/done!))
   'reopen!   (:malli/schema (meta #'plan/reopen!))
   'needs!    (:malli/schema (meta #'plan/needs!))
   'move!     (:malli/schema (meta #'plan/move!))
   'drop!     (:malli/schema (meta #'plan/drop!))
   'next       (:malli/schema (meta #'plan/next))
   'tree       (:malli/schema (meta #'plan/tree))
   'document   (:malli/schema (meta #'plan/document))
   'reconcile! (:malli/schema (meta #'plan/reconcile!))
   'status     (:malli/schema (meta #'plan/status))
   'list-open  (:malli/schema (meta #'plan/list-open))})

(defn- instrument-functions! []
  (inst/instrument-delta!
    {::inst/changed-syms
     (into #{} (map #(symbol "my.plan" (name %))) (keys function-schemas))
     ::inst/targets
     (mapv (fn [[fn-sym function-schema]]
             {::inst/sym (symbol "my.plan" (name fn-sym))
              ::inst/schema-form function-schema})
           function-schemas)}))

(defn- uninstrument-functions! []
  (inst/instrument-delta!
    {::inst/changed-syms
     (into #{} (map #(symbol "my.plan" (name %))) (keys function-schemas))
     ::inst/targets []}))

(use-fixtures :once {:before instrument-functions! :after uninstrument-functions!})

(defn- fresh-conn
  "Promise of a fresh :memory conn with the pod's boot schema + the user
   entity + agents A and B (the same rows seon.client seeds at boot)."
  []
  (-> (client/open-agent-conn!)
      (.then
        (fn [conn]
          (-> (db/with-tx-context
                {:seon.db/user [:seon.agent/id "root"]
                 :seon.db/process
                 [:seon.db.process/id :seon.db.process/boot]}
                (fn []
                  (db/transact!
                    {:seon.db/conn conn
                     ;; These are known recovered identities, not fresh-ID
                     ;; creation paths under test.
                     :seon.db/tx-data
                     [{:seon.agent/id a-id
                       :seon.eval/home-requires []}
                      {:seon.agent/id b-id
                       :seon.eval/home-requires []}]})))
              (.then (fn [env]
                       (when-not (:seon.db/ok? env)
                         (throw (ex-info "plan fixture seed failed" env)))
                       conn)))))))

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
   inside (db/with-agent a-id) so the functions' ALS scope resolves to agent A."
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

(deftest frontier-active-first-as-arrow-open-as-box-done-dropped
  ;; The ▶/☐/done-dropped compactness contract, absorbed from the retired
  ;; `:plan-ledger` block (owner ruling 2026-07-11, planner-worker-design):
  ;; the :active step renders ▶-marked ahead of the ☐-marked ready steps;
  ;; a DONE step never renders in the frontier band (only the ✓ recall
  ;; band recalls it); a :blocked step is not actionable and stays off
  ;; the frontier.
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (allocate-facts!
                  conn
                  :my.plan/id
                  [[::frontier-open-a
                    {:my.plan/title "design the schema"
                     :my.plan/status :open :my.plan/agent a-ref
                     :my.plan/created-at (js/Date. 1000)}]
                   [::frontier-active
                    {:my.plan/title "store the seed rows"
                     :my.plan/status :active :my.plan/agent a-ref
                     :my.plan/created-at (js/Date. 2000)}]
                   [::frontier-open-b
                    {:my.plan/title "render the summary tile"
                     :my.plan/status :open :my.plan/agent a-ref
                     :my.plan/created-at (js/Date. 3000)}]
                   [::frontier-done
                    {:my.plan/title "already shipped setup"
                     :my.plan/status :done :my.plan/agent a-ref
                     :my.plan/created-at (js/Date. 500)
                     :my.plan/completed-at (js/Date. 900)}]
                   [::frontier-blocked
                    {:my.plan/title "waiting on the human"
                     :my.plan/status :blocked :my.plan/agent a-ref
                     :my.plan/created-at (js/Date. 400)}]])
                (.then
                  (fn [{ok? :seon.db/ok? err :seon.db/error
                        ids ::db.id/ids}]
                    (is (true? ok?) (str "seed transacted — " (pr-str err)))
                    (let [block     (plan-int/plan-body @conn a-ref)
                          active-id (get ids ::frontier-active)
                          open-id   (get ids ::frontier-open-a)
                          done-id   (get ids ::frontier-done)]
                      (is (str/includes? block (str "; ▶ " active-id))
                          "the :active step renders ▶-marked")
                      (is (str/includes? block (str "; ☐ " open-id))
                          "an open ready step renders ☐-marked")
                      (is (< (str/index-of block (str "▶ " active-id))
                             (str/index-of block (str "☐ " open-id)))
                          "the active step leads the frontier")
                      (is (str/includes? block "▶ = the step you are on")
                          "the glyph legend is taught in the frontier header")
                      (is (not (str/includes? block (str "☐ " done-id)))
                          "a DONE step is dropped from the frontier entirely")
                      (is (str/includes? block "✓ [")
                          "…and recalls only through the ✓ recently-completed band")
                      (is (not (str/includes? block "waiting on the human"))
                          "a blocked step is not actionable — off the frontier")))))))
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
                           (is (every? #(re-matches #"^[a-z][a-z0-9]{11}$" %)
                                       (vals ids))
                               "every minted plan identity uses the compact policy")
                           (is (= 4 (count (set (vals ids))))
                               "one allocation round returns a distinct id per node")
                           (let [sub  (plan/tree {:my.plan/root root})
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

(deftest plan-carries-child-description-through-to-the-db
  ;; Regression: compile-plan's walk once dropped :my.plan/description on
  ;; children (the ::node schema accepts it, step! persists it, and the tile
  ;; renders a `desc` row from it — but plan!-authored children silently lost
  ;; it). Assert a child's description survives the tempid compiler + tx.
  (async done
    (-> (with-agent-conn
          (fn []
            (-> (plan/plan!
                  {:my.plan/title "root"
                   :my.plan/children
                   [{:my.plan/title       "child with a description"
                     :my.plan/ref         "kid"
                     :my.plan/description "why this step exists"}]})
                (.then (fn [{:my.plan/keys [ok? ids]}]
                         (is (true? ok?))
                         (let [t (d/pull @db/*conn* '[:my.plan/description]
                                         [:my.plan/id (get ids "kid")])]
                           (is (= "why this step exists"
                                  (:my.plan/description t))
                               "plan! carried the child's description into the db")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

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
                                             (plan/tree {:my.plan/root (:p1 @st)}))))
                               "leaf starts under plan A")
                           (is (nil? (:my.plan/_parent
                                       (plan/tree {:my.plan/root (:p2 @st)})))
                               "plan B starts childless")
                           (plan/move! {:my.plan/id lf
                                        :my.plan/parent [:my.plan/id (:p2 @st)]})))
                  (.then (fn [{ok? :my.plan/ok?}]
                           (is (true? ok?))
                           (is (nil? (:my.plan/_parent
                                       (plan/tree {:my.plan/root (:p1 @st)})))
                               "move! retracted the old parent edge — plan A now childless")
                           (is (= 1 (count (:my.plan/_parent
                                             (plan/tree {:my.plan/root (:p2 @st)}))))
                               "move! re-parented the leaf under plan B"))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest needs-preflights-every-dependency-before-writing
  (async done
    (let [st        (atom {})
          missing-a "missing-dependency-a"
          missing-b "missing-dependency-b"]
      (-> (with-agent-conn
            (fn []
              (-> (plan/step! {:my.plan/title "dependent"})
                  (.then (fn [{id :my.plan/id}]
                           (swap! st assoc :dependent id)
                           (plan/step! {:my.plan/title "existing dependency"})))
                  (.then (fn [{id :my.plan/id}]
                           (swap! st assoc :existing id)
                           (plan/needs!
                             {:my.plan/id (:dependent @st)
                              :my.plan/on [[:my.plan/id id]
                                           [:my.plan/id missing-a]
                                           [:my.plan/id missing-b]]})))
                  (.then
                    (fn [{ok? :my.plan/ok? error :my.plan/error}]
                      (is (false? ok?)
                          "one bad dependency rejects the complete edge operation")
                      (is (and (str/includes? error "needs!")
                               (str/includes? error missing-a)
                               (str/includes? error missing-b)
                               (str/includes? error ":my.plan/on"))
                          "the envelope identifies the operation, every bad id, and retry field")
                      (is (nil? (:my.plan/needs
                                  (d/pull @db/*conn* '[*]
                                          [:my.plan/id (:dependent @st)])))
                          "preflight writes no valid prefix when another target is missing"))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest move-preflights-the-requested-parent-before-writing
  (async done
    (let [st             (atom {})
          missing-parent "missing-parent"]
      (-> (with-agent-conn
            (fn []
              (-> (plan/step! {:my.plan/title "original parent"})
                  (.then (fn [{id :my.plan/id}]
                           (swap! st assoc :parent id)
                           (plan/step!
                             {:my.plan/title "child"
                              :my.plan/parent [:my.plan/id id]})))
                  (.then (fn [{id :my.plan/id}]
                           (swap! st assoc :child id)
                           (plan/move!
                             {:my.plan/id id
                              :my.plan/parent [:my.plan/id missing-parent]})))
                  (.then
                    (fn [{ok? :my.plan/ok? error :my.plan/error}]
                      (is (false? ok?)
                          "a missing parent is an agent-facing failure envelope")
                      (is (and (str/includes? error "move!")
                               (str/includes? error missing-parent)
                               (str/includes? error ":my.plan/parent"))
                          "the envelope identifies the operation, bad parent id, and retry field")
                      (is (= (:parent @st)
                             (get-in
                               (d/pull @db/*conn*
                                       '[{:my.plan/parent [:my.plan/id]}]
                                       [:my.plan/id (:child @st)])
                               [:my.plan/parent :my.plan/id]))
                          "a rejected move preserves the existing parent fact"))))))
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

(defn- query-member-result [result]
  {::protocol/success? true :datahike.query/result result})

(defn- pull-member-result [result]
  {::protocol/success? true ::protocol/result result})

(deftest remote-plan-acquisition-is-two-coordinate-pinned-batches
  (async done
    (let [at (js/Date. 1000)
          requests (atom [])
          responses
          (atom
            [{::db/coordinate coordinate
              ::db/results
              [(query-member-result
                 [["active-step" "do it" "verify it" at false 31]])
               (query-member-result
                 [["ready-step" "then this" "" at false]])
               (query-member-result
                 [["done-step" "already done" at]])
               (pull-member-result {:db/id 1 :seon.agent/id a-id})]}
             {::db/coordinate coordinate
              ::db/results
             [(pull-member-result
                 {:my.plan/id "active-step"
                  :my.plan/title "do it"
                  :my.plan/parent
                  {:my.plan/id "root-step"
                   :my.plan/title "the goal"
                   :my.plan/goal "ship it"}})
               (query-member-result [[:done 2] [:active 1]])
               (query-member-result [])]}])
          original-execute-many db/execute-many]
      (set! db/execute-many
            (fn [request]
              (swap! requests conj request)
              (let [response (first @responses)]
                (swap! responses subvec 1)
                (js/Promise.resolve response))))
      (-> ((deref #'plan-int/acquire-plan-block)
           {::db/coordinate coordinate :seon.agent/id a-id})
          (.then
            (fn [acquired]
              (is (= 2 (count @requests)))
              (is (every? #(= coordinate (::db/coordinate %)) @requests)
                  "both dependent reads retain the inherited coordinate")
              (is (= [4 3] (mapv (comp count ::db/members) @requests)))
              (is (= [protocol/query-operation protocol/query-operation
                      protocol/query-operation protocol/pull-operation]
                     (mapv ::protocol/operation
                           (::db/members (first @requests)))))
              (is (= [protocol/pull-operation protocol/query-operation
                      protocol/query-operation]
                     (mapv ::protocol/operation
                           (::db/members (second @requests)))))
              (is (= ["root-step" "active-step"]
                     (mapv :my.plan/id
                           (get-in acquired
                                   [::plan-int/anchor :my.plan/chain]))))
              (is (= {:my.plan/done 2 :my.plan/total 3
                      :my.plan/done? false}
                     (get-in acquired
                             [::plan-int/anchor :my.plan/progress])))
              (let [rendered ((deref #'plan-int/format-plan-body) acquired)]
                (is (str/includes? rendered "ship it"))
                (is (str/includes? rendered "verify it"))
                (is (str/includes? rendered "already done"))
                (is (not (str/includes? rendered "STUCK"))
                    "an active step without a wedge pays no third request"))))
          (.catch (fn [error] (is false (str "threw — " error))))
          (.finally
            (fn []
              (set! db/execute-many original-execute-many)
              (done)))))))

(deftest remote-plan-wedge-alone-triggers-the-third-batch
  (async done
    (let [at (js/Date. 1000)
          failure (fn [id]
                    {:seon.eval/id id
                     :seon.eval/ok? false
                     :seon.eval/source
                     "(schema/register! :my.exp/rows (map [:string]))"
                     :seon.eval/error "register!: input invalid"
                     :seon.eval/error-data
                     (pr-str {:seon.error/kind :schema})})
          requests (atom [])
          responses
          (atom
            [{::db/coordinate coordinate
              ::db/results
              [(query-member-result
                 [["active-step" "do it" "verify it" at false 31]])
               (query-member-result [])
               (query-member-result [])
               (pull-member-result {:db/id 1 :seon.agent/id a-id})]}
             {::db/coordinate coordinate
              ::db/results
              [(pull-member-result
                 {:my.plan/id "active-step"
                  :my.plan/title "do it"
                  :my.plan/parent
                  {:my.plan/id "root-step" :my.plan/title "the goal"}})
               (query-member-result [[:active 1]])
               (query-member-result
                 [[10 (failure "eval-1") 32]
                  [11 (failure "eval-2") 33]
                  [12 (failure "eval-3") 34]])]}
             {::db/coordinate coordinate
              ::db/results
              [(query-member-result [["aaa-frontier" ":deepseek"]
                                     [b-id ":inherit"]
                                     ["local-worker" ":typeahead"]])
               (pull-member-result {:seon.ai/provider :deepseek})
               (query-member-result [b-id])
               (query-member-result [77])]}])
          original-execute-many db/execute-many]
      (set! db/execute-many
            (fn [request]
              (swap! requests conj request)
              (let [response (first @responses)]
                (swap! responses subvec 1)
                (js/Promise.resolve response))))
      (-> ((deref #'plan-int/acquire-plan-block)
           {::db/coordinate coordinate :seon.agent/id a-id})
          (.then
            (fn [acquired]
              (is (= [4 3 4]
                     (mapv (comp count ::db/members) @requests)))
              (is (every? #(= coordinate (::db/coordinate %)) @requests))
              (let [second-members (::db/members (nth @requests 1))
                    third-members (::db/members (nth @requests 2))
                    marker (second (::protocol/arguments
                                     (nth third-members 3)))
                    rendered ((deref #'plan-int/format-plan-body) acquired)]
                (is (= [2097152 262144 1024 65536 4096]
                       [(:datahike.resource/max-result-weight
                          (nth second-members 2))
                        (:datahike.resource/max-result-weight
                          (nth third-members 0))
                        (:datahike.resource/max-result-weight
                          (nth third-members 1))
                        (:datahike.resource/max-result-weight
                          (nth third-members 2))
                        (:datahike.resource/max-result-weight
                          (nth third-members 3))]))
                (is (= (plan-int/consult-marker "active-step" "eval-1")
                       marker)
                    "the exact derived episode marker scopes the message query")
                (is (str/includes? rendered "STUCK ▶ active-step"))
                (is (str/includes? rendered "schema/register!"))
                (is (str/includes? rendered b-id))
                (is (not (str/includes? rendered "aaa-frontier"))
                    "a step author wins over an earlier frontier candidate")
                (is (str/includes? rendered "has been consulted")))))
          (.catch (fn [error] (is false (str "threw — " error))))
          (.finally
            (fn []
              (set! db/execute-many original-execute-many)
              (done)))))))

(deftest remote-ready-plan-remains-two-batches-without-eval-query
  (async done
    (let [at (js/Date. 1000)
          requests (atom [])
          responses
          (atom
            [{::db/coordinate coordinate
              ::db/results
              [(query-member-result [])
               (query-member-result [["ready-step" "next" "" at false]])
               (query-member-result [])
               (pull-member-result {:db/id 1 :seon.agent/id a-id})]}
             {::db/coordinate coordinate
              ::db/results
              [(pull-member-result
                 {:my.plan/id "ready-step" :my.plan/title "next"})
               (query-member-result [[:open 1]])]}])
          original-execute-many db/execute-many]
      (set! db/execute-many
            (fn [request]
              (swap! requests conj request)
              (let [response (first @responses)]
                (swap! responses subvec 1)
                (js/Promise.resolve response))))
      (-> ((deref #'plan-int/acquire-plan-block)
           {::db/coordinate coordinate :seon.agent/id a-id})
          (.then
            (fn [acquired]
              (is (= [4 2] (mapv (comp count ::db/members) @requests)))
              (is (= ""
                     (::plan-int/escalation-text acquired)))
              (is (= false
                     (get-in acquired
                             [::plan-int/anchor :my.plan/active?])))))
          (.catch (fn [error] (is false (str "threw — " error))))
          (.finally
            (fn []
              (set! db/execute-many original-execute-many)
              (done)))))))

(deftest remote-plan-acquisition-returns-coordinate-and-member-errors
  (async done
    (let [responses
          (atom
            [{::db/coordinate (assoc coordinate :seon.db.coordinate/t 43)
              ::db/results []}
             {::db/coordinate coordinate
              ::db/results
              [{::protocol/success? false
                ::protocol/error "bounded active query failed"}
               (query-member-result [])
               (query-member-result [])
               (pull-member-result {:db/id 1 :seon.agent/id a-id})]}])
          original-execute-many db/execute-many]
      (set! db/execute-many
            (fn [_]
              (let [response (first @responses)]
                (swap! responses subvec 1)
                (js/Promise.resolve response))))
      (-> ((deref #'plan-int/acquire-plan-block)
           {::db/coordinate coordinate :seon.agent/id a-id})
          (.then
            (fn [mismatch]
              (is (= "Plan coordinate check failed."
                     (:seon.error/message mismatch)))
              ((deref #'plan-int/acquire-plan-block)
               {::db/coordinate coordinate :seon.agent/id a-id})))
          (.then
            (fn [member-failure]
              (is (= "Plan initial member failed."
                     (:seon.error/message member-failure)))
              (is (= "bounded active query failed"
                     (get-in member-failure
                             [:seon.error/data 0 ::protocol/error])))))
          (.catch (fn [error] (is false (str "threw — " error))))
          (.finally
            (fn []
              (set! db/execute-many original-execute-many)
              (done)))))))

(deftest remote-plan-escalation-returns-third-batch-errors
  (async done
    (let [failure {:seon.eval/id "episode"
                   :seon.eval/ok? false
                   :seon.eval/source "(schema/register! :my.exp/x :bad)"
                   :seon.eval/error "bad schema"}
          eval-member
          (query-member-result [[10 failure 32]
                                [11 failure 33]
                                [12 failure 34]])
          active {:my.plan/id "active" :my.plan/title "work"}
          responses
          (atom
            [{::db/coordinate (assoc coordinate :seon.db.coordinate/t 43)
              ::db/results []}
             {::db/coordinate coordinate
              ::db/results
              [{::protocol/success? false ::protocol/error "candidate failure"}
               (pull-member-result {})
               (query-member-result [])
               (query-member-result [])]}])
          original-execute-many db/execute-many]
      (set! db/execute-many
            (fn [_]
              (let [response (first @responses)]
                (swap! responses subvec 1)
                (js/Promise.resolve response))))
      (-> ((deref #'plan-int/acquire-escalation-text)
           coordinate a-id active eval-member)
          (.then
            (fn [mismatch]
              (is (= "Plan escalation coordinate check failed."
                     (:seon.error/message mismatch)))
              ((deref #'plan-int/acquire-escalation-text)
               coordinate a-id active eval-member)))
          (.then
            (fn [member-failure]
              (is (= "Plan escalation member failed."
                     (:seon.error/message member-failure)))
              (is (= "candidate failure"
                     (get-in member-failure
                             [:seon.error/data 0 ::protocol/error])))))
          (.catch (fn [error] (is false (str "threw — " error))))
          (.finally
            (fn []
              (set! db/execute-many original-execute-many)
              (done)))))))

(deftest acquired-plan-formatting-is-the-existing-pure-tail
  (let [at (js/Date. 1000)
        anchor {:my.plan/step
                {:my.plan/id "active" :my.plan/title "work"
                 :my.plan/expect "proof"}
                :my.plan/chain
                [{:my.plan/id "root" :my.plan/title "goal"
                  :my.plan/goal "ship"}]
                :my.plan/active? true
                :my.plan/progress {:my.plan/done 2 :my.plan/total 4}}
        actives [{:my.plan/id "active" :my.plan/title "work"
                  :my.plan/created-at at}]
        readies [{:my.plan/id "ready" :my.plan/title "next"
                  :my.plan/created-at at}]
        dones [{:my.plan/id "done" :my.plan/title "landed"
                :my.plan/completed-at at}]
        escalation-text "; escalation stays owner-formatted"
        original-execute-many db/execute-many
        original-query db/query
        original-pull db/pull
        touched (atom [])
        fail-read (fn [& args]
                    (swap! touched conj args)
                    (throw (js/Error. "unexpected database read")))]
    (try
      (set! db/execute-many fail-read)
      (set! db/query fail-read)
      (set! db/pull fail-read)
      (let [input {::plan-int/anchor anchor
                   ::plan-int/actives actives
                   ::plan-int/readies readies
                   ::plan-int/dones dones
                   ::plan-int/escalation-text escalation-text}
            expected
            (str/join "\n"
                      [(plan-int/anchor-section anchor)
                       escalation-text
                       (plan-int/frontier-section actives readies)
                       (plan-int/done-section dones)])]
        (is (= expected ((deref #'plan-int/format-plan-body) input)))
        (is (empty? @touched) "the shared formatter performs no database I/O"))
      (finally
        (set! db/execute-many original-execute-many)
        (set! db/query original-query)
        (set! db/pull original-pull)))))

(deftest thousand-step-plan-uses-bounded-datalog-queries
  (async done
    (-> (with-conn
          (fn [conn]
            (let [root-id "bulkroot000000"
                  child-id (fn [i] (str "p" (.padStart (str i) 13 "0")))
                  at (js/Date. 1000)]
              (-> (db/transact!
                    {:seon.db/tx-data
                     [{:my.plan/id root-id
                       :my.plan/title "bulk root"
                       :my.plan/status :open
                       :my.plan/created-at at
                       :my.plan/agent a-ref}]})
                  (.then
                    (fn [{ok? :seon.db/ok? :as envelope}]
                      (is (true? ok?) (pr-str envelope))
                      (db/transact!
                        {:seon.db/tx-data
                         (mapv (fn [i]
                                 {:my.plan/id (child-id i)
                                  :my.plan/title (str "step " i)
                                  :my.plan/status :open
                                  :my.plan/created-at (js/Date. (+ 1001 i))
                                  :my.plan/agent a-ref
                                  :my.plan/parent [:my.plan/id root-id]})
                               (range 999))})))
                  (.then
                    (fn [{ok? :seon.db/ok? :as envelope}]
                      (is (true? ok?) (pr-str envelope))
                      (let [database @conn
                            initial-members
                            ((deref #'plan-int/initial-acquisition-members) a-id)
                            run-query
                            (fn [member]
                              (datahike-query/q-with-evidence
                                {:query (::protocol/query-form member)
                                 :args (into [database]
                                             (::protocol/arguments member))
                                 :max-work
                                 (:datahike.resource/max-work member)
                                 :max-results
                                 (:datahike.resource/max-results member)
                                 :max-result-weight
                                 (:datahike.resource/max-result-weight member)}))
                            initial-evidence
                            (mapv run-query (take 3 initial-members))
                            ready-result
                            (get-in initial-evidence
                                    [1 :datahike.query/result])
                            selected-id (ffirst ready-result)
                            selected-members
                            ((deref #'plan-int/selected-acquisition-members)
                             selected-id)
                            rollup-evidence (run-query (second selected-members))
                            evidence (conj initial-evidence rollup-evidence)
                            resource (mapv :datahike.query/resource-evidence
                                           evidence)]
                        (is (= (inc plan-int/frontier-limit)
                               (count ready-result))
                            "the authority returns only the frontier plus overflow witness")
                        (is (= #{[:open 999]}
                               (set (get rollup-evidence
                                         :datahike.query/result))))
                        (is (= 4 (count evidence))
                            "1,000 steps still execute four normal-path queries")
                        (is (= [1001 21067 1 10017]
                               (mapv :datahike.resource/work resource)))
                        (is (= [0 1999 0 999]
                               (mapv :datahike.resource/result-count resource)))
                        (is (= [0 60753 0 2997]
                               (mapv :datahike.resource/result-weight resource)))
                        (is (every?
                              (fn [entry]
                                (let [limits (:datahike.resource/limits entry)]
                                  (and
                                    (<= (:datahike.resource/work entry)
                                        (:datahike.resource/max-work limits))
                                    (<= (:datahike.resource/result-count entry)
                                        (:datahike.resource/max-results limits))
                                    (<= (:datahike.resource/result-weight entry)
                                        (:datahike.resource/max-result-weight
                                          limits)))))
                              resource)))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error] (is false (str "threw — " error)) (done))))))

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

;; --- depth-2+ coverage. The suite-blindness gap: EVERY prior tree was
;; --- depth-1, so a broken recursive roll-up ("0 of 0 steps done" over a
;; --- nested plan) shipped unseen. These assert the shared rule derivations
;; --- (rollup/ready-leaves/ready?) over a NESTED tree AND that the html twin
;; --- agrees with the :ai block on the same tree — post re-unification that
;; --- agreement is STRUCTURAL (both faces read the SAME rollup/ready?/anchor).

(defn- hiccup-strings
  "Every string anywhere in hiccup `h` — for asserting rendered content."
  [h]
  (let [acc (atom [])]
    (walk/postwalk (fn [x] (when (string? x) (swap! acc conj x)) x) h)
    @acc))

(deftest depth-2-rollup-ready-leaves-and-html-ai-agree
  (async done
    (let [st (atom {})]
      (-> (with-agent-conn
            (fn []
              (-> (plan/plan!
                    {:my.plan/title "deep root"
                     :my.plan/children
                     [{:my.plan/title "phase-A" :my.plan/ref "A"
                       :my.plan/children
                       [{:my.plan/title "a1" :my.plan/ref "a1"}
                        {:my.plan/title "a2" :my.plan/ref "a2"}]}
                      {:my.plan/title "phase-B" :my.plan/ref "B"
                       :my.plan/children
                       [{:my.plan/title "b1" :my.plan/ref "b1"}]}]})
                  (.then
                    (fn [{:my.plan/keys [ok? root ids]}]
                      (reset! st {:root root :ids ids})
                      (is (true? ok?))
                      (let [db @db/*conn*]
                        ;; (1) roll-up counts leaves at DEPTH 2 (a1 a2 b1 = 3)
                        (is (= {:my.plan/done 0 :my.plan/total 3 :my.plan/done? false}
                               (plan-int/rollup db root))
                            "root roll-up counts the 3 depth-2 leaves, none done")
                        (is (= {:my.plan/done 0 :my.plan/total 2 :my.plan/done? false}
                               (plan-int/rollup db (get ids "A")))
                            "phase-A roll-up counts its 2 leaves (nested, not 0/0)")
                        ;; (2) ready-leaves = leaves ONLY, never an undrained parent
                        (let [ready-ids (set (map :my.plan/id
                                                  (plan-int/ready-leaves
                                                    db (plan-int/agent-eid db a-ref))))]
                          (is (= #{(get ids "a1") (get ids "a2") (get ids "b1")}
                                 ready-ids)
                              "every open leaf is ready; no undrained parent listed")
                          (is (not (contains? ready-ids (get ids "A")))
                              "phase-A (undrained non-leaf) is NEVER in the ready set")))
                      ;; (3) done! a grandchild moves the roll-up
                      (plan/done! {:my.plan/id (get-in @st [:ids "a1"])})))
                  (.then
                    (fn [_]
                      (let [{:keys [root]} @st db @db/*conn*]
                        (is (= {:my.plan/done 1 :my.plan/total 3 :my.plan/done? false}
                               (plan-int/rollup db root))
                            "closing a depth-2 leaf advances the root roll-up 0->1")
                        (plan/done! {:my.plan/id (get-in @st [:ids "a2"])}))))
                  (.then
                    (fn [_]
                      (let [{:keys [root ids]} @st db @db/*conn*]
                        (is (= {:my.plan/done 2 :my.plan/total 2 :my.plan/done? true}
                               (plan-int/rollup db (get ids "A")))
                            "phase-A fully drained -> 2/2 done?")
                        (is (plan-int/ready? db (get ids "A"))
                            "a drained non-leaf is ready-to-close (verify + done!)")
                        ;; (4) html twin AGREES with the :ai block on the SAME tree
                        (let [ai   (plan-int/plan-body db a-ref)
                              html (plan-int/plan-block-html
                                     {:seon.db/db db :seon.agent/id a-id})
                              hs   (hiccup-strings html)
                              {:my.plan/keys [done total]} (plan-int/rollup db root)]
                          (is (some #(= (str done "/" total " done") %) hs)
                              "html root card shows the SAME root roll-up as the shared fn")
                          (is (str/includes? ai (str done " of " total))
                              "the :ai block narrates the SAME root roll-up")
                          (is (some #(= "2/2" %) hs)
                              "the phase-A row shows its nested 2/2 (depth>1 count correct)"))))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

;; --- reconcile! — the whole-plan document round-trip (planner-worker W1).
;; --- Authoring = reconcile against empty (ONE code path with plan!); a node
;; --- with id updates in place, without id mints, an absent open node drops,
;; --- done steps are immune. `document` is the projection the edit starts
;; --- from; a round-trip reconcile of an unedited document is a no-op.

(deftest reconcile-authors-from-empty-like-plan
  (async done
    (-> (with-agent-conn
          (fn []
            (-> (plan/reconcile!
                  {:my.plan/tree
                   {:my.plan/title "Ship the tracker"
                    :my.plan/goal  "a tracker my human keeps using"
                    :my.plan/children
                    [{:my.plan/title "design the schema" :my.plan/ref "schema"
                      :my.plan/expect "register! returns and a row transacts"}
                     {:my.plan/title "store seed rows" :my.plan/ref "rows"
                      :my.plan/after ["schema"]}]}})
                (.then
                  (fn [{:my.plan/keys [ok? root ids diff]}]
                    (is (true? ok?) "authoring against an empty tree succeeds")
                    (is (string? root))
                    (is (= {:my.plan/added 3 :my.plan/dropped 0 :my.plan/updated 0}
                           diff)
                        "diff counts the 3 minted nodes, nothing else")
                    (let [sub  (plan/tree {:my.plan/root root})
                          kids (:my.plan/_parent sub)
                          rows (some #(when (= (get ids "rows") (:my.plan/id %)) %)
                                     kids)]
                      (is (= "Ship the tracker" (:my.plan/title sub)))
                      (is (= "a tracker my human keeps using" (:my.plan/goal sub)))
                      (is (= 2 (count kids)) "both children landed in ONE tx")
                      (is (= 1 (count (:my.plan/needs rows)))
                          ":after label compiled to a needs edge, as in plan!")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest reconcile-round-trip-of-unedited-document-is-a-noop
  (async done
    (-> (with-agent-conn
          (fn []
            (-> (plan/plan! {:my.plan/title "root"
                             :my.plan/children
                             [{:my.plan/title "a" :my.plan/ref "a"
                               :my.plan/expect "a holds"}
                              {:my.plan/title "b" :my.plan/ref "b"
                               :my.plan/after ["a"]
                               :my.plan/description "second"}]})
                (.then (fn [{ok? :my.plan/ok?}]
                         (is (true? ok?))
                         (plan/reconcile! {:my.plan/tree (plan/document {})})))
                (.then
                  (fn [{:my.plan/keys [ok? diff] :as env}]
                    (is (true? ok?))
                    (is (= {:my.plan/added 0 :my.plan/dropped 0
                            :my.plan/updated 0}
                           diff)
                        "an unedited document reconciles to ZERO delta — the projection and the diff share one shape")
                    (is (not (contains? env :my.plan/resolved-root))
                        "ids present ⇒ no identity resolution happened"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest reconcile-updates-mints-and-drops-in-one-tx
  (async done
    (let [st (atom {})]
      (-> (with-agent-conn
            (fn []
              (-> (plan/plan! {:my.plan/title "root"
                               :my.plan/children
                               [{:my.plan/title "keep me" :my.plan/ref "a"}
                                {:my.plan/title "drop me" :my.plan/ref "b"}]})
                  (.then
                    (fn [{:my.plan/keys [ok? root ids]}]
                      (is (true? ok?))
                      (reset! st {:root root :a (get ids "a") :b (get ids "b")})
                      (let [[doc-root] (plan/document {})
                            kids (:my.plan/_parent doc-root)
                            a    (some #(when (= (get ids "a") (:my.plan/id %)) %)
                                       kids)
                            ;; edit a's title + expect, DROP b, MINT c
                            doc  (assoc doc-root :my.plan/_parent
                                        [(assoc a
                                                :my.plan/title "keep me (renamed)"
                                                :my.plan/expect "renamed row reads back")
                                         {:my.plan/title "minted later"}])]
                        (plan/reconcile! {:my.plan/tree doc}))))
                  (.then
                    (fn [{:my.plan/keys [ok? diff]}]
                      (is (true? ok?))
                      (is (= {:my.plan/added 1 :my.plan/dropped 1
                              :my.plan/updated 1}
                             diff)
                          "one delta: a updated, b dropped, c minted")
                      (let [{:keys [root a b]} @st
                            sub  (plan/tree {:my.plan/root root})
                            kids (:my.plan/_parent sub)
                            a*   (some #(when (= a (:my.plan/id %)) %) kids)]
                        (is (= 2 (count kids)) "root has a + the minted child")
                        (is (= "keep me (renamed)" (:my.plan/title a*))
                            "update happened IN PLACE — same :my.plan/id")
                        (is (= "renamed row reads back" (:my.plan/expect a*))
                            "the added ::expect landed")
                        (is (nil? (plan-int/status-of b))
                            "the absent open node was dropped (drop! semantics)")
                        (is (some #(= "minted later" (:my.plan/title %)) kids)
                            "the id-less node was minted under the root")))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest reconcile-done-steps-are-immune
  (async done
    (let [st (atom {})]
      (-> (with-agent-conn
            (fn []
              (-> (plan/plan! {:my.plan/title "root"
                               :my.plan/children
                               [{:my.plan/title "finished" :my.plan/ref "f"}
                                {:my.plan/title "open" :my.plan/ref "o"}]})
                  (.then (fn [{:my.plan/keys [ids]}]
                           (reset! st ids)
                           (plan/done! {:my.plan/id (get ids "f")})))
                  (.then
                    (fn [_]
                      (let [[doc-root] (plan/document {})
                            kid-ids (set (map :my.plan/id
                                              (:my.plan/_parent doc-root)))]
                        (is (not (contains? kid-ids (get @st "f")))
                            "document EXCLUDES the done step by construction")
                        ;; hand-submit the done id anyway → fail, no mutation
                        (plan/reconcile!
                          {:my.plan/tree
                           (update doc-root :my.plan/_parent conj
                                   {:my.plan/id (get @st "f")
                                    :my.plan/title "rewriting history"})}))))
                  (.then
                    (fn [{ok? :my.plan/ok? error :my.plan/error}]
                      (is (false? ok?) "a done id in the document is refused")
                      (is (str/includes? error (get @st "f"))
                          "the envelope NAMES the immune step")
                      (is (re-find #"reopen!" error) "and names the fix")
                      (is (= "finished"
                             (:my.plan/title
                               (d/pull @db/*conn* '[*]
                                       [:my.plan/id (get @st "f")])))
                          "history was not mutated"))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

;; --- reconcile! identity resolution — the id-less-root re-mint hazard
;; --- (three live sightings: W1/W3 planner-worker drives + the 2026-07-12
;; --- plan-preload pilot). Identity is unforgeable by construction: an
;; --- edited document that OMITS the root's id must UPDATE the root, never
;; --- drop-and-re-mint it; ambiguity refuses with the candidate ids.

(deftest reconcile-idless-root-resolves-to-the-single-open-root
  (async done
    (let [st (atom {})]
      (-> (with-agent-conn
            (fn []
              (-> (plan/plan! {:my.plan/title "Ship the tracker"
                               :my.plan/goal  "a tracker in use"
                               :my.plan/children
                               [{:my.plan/title "design the schema"}
                                {:my.plan/title "store seed rows"}]})
                  (.then
                    (fn [{:my.plan/keys [ok? root]}]
                      (is (true? ok?))
                      (reset! st {:root root})
                      ;; the third-sighting shape: an edited document whose
                      ;; ROOT lost its id while the children keep theirs
                      (let [[doc-root] (plan/document {})]
                        (plan/reconcile!
                          {:my.plan/tree
                           (-> doc-root
                               (dissoc :my.plan/id)
                               (assoc :my.plan/title
                                      "Ship the tracker (retitled)"))}))))
                  (.then
                    (fn [{:my.plan/keys [ok? root resolved-root diff]}]
                      (let [{orig :root} @st]
                        (is (true? ok?) "an id-less single root reconciles")
                        (is (= orig root)
                            "the receipt's root IS the original — no mint")
                        (is (true? resolved-root)
                            "the receipt says the root was resolved")
                        (is (= {:my.plan/added 0 :my.plan/dropped 0
                                :my.plan/updated 1}
                               diff)
                            "ONE update — nothing minted, nothing dropped")
                        (is (= "Ship the tracker (retitled)"
                               (:my.plan/title
                                 (d/pull @db/*conn* '[*] [:my.plan/id orig])))
                            "the edit landed ON the original root")
                        (is (= 1 (count (d/q '[:find ?t :where
                                               [?t :my.plan/id _]
                                               (not-join [?t]
                                                         [?t :my.plan/parent _])]
                                             @db/*conn*)))
                            "exactly ONE root exists — no second root minted")))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest plan-refuses-same-title-duplicate
  ;; Regression (2026-07-13): plan! only CREATES; a same-title re-statement (a
  ;; model re-emitting its plan, since eval results arrive next turn) must NOT
  ;; silently duplicate the tree. The second call fails, names the existing
  ;; root, points to the update door, and leaves exactly one root.
  (async done
    (let [st (atom {})]
      (-> (with-agent-conn
            (fn []
              (-> (plan/plan! {:my.plan/title "Tally ledger"})
                  (.then (fn [{:my.plan/keys [root]}]
                           (swap! st assoc :r1 root)
                           (plan/plan! {:my.plan/title "Tally ledger"})))
                  (.then (fn [{ok? :my.plan/ok? error :my.plan/error}]
                           (is (false? ok?)
                               "same-title plan! refused, never silently duplicated")
                           (is (str/includes? error (:r1 @st))
                               "the envelope names the existing root id")
                           (is (str/includes? error "reconcile!")
                               "…and routes to the update door")
                           (is (= ["Tally ledger"]
                                  (mapv :my.plan/title (plan/tree {})))
                               "exactly ONE root — no duplicate tree minted"))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest plan-allows-distinct-second-plan
  ;; The guard is title-scoped: a DISTINCT second plan is a legitimate forest.
  (async done
    (-> (with-agent-conn
          (fn []
            (-> (plan/plan! {:my.plan/title "tree one"})
                (.then (fn [_] (plan/plan! {:my.plan/title "tree two"})))
                (.then (fn [{ok? :my.plan/ok?}]
                         (is (true? ok?) "a distinct-title second plan is allowed")
                         (is (= #{"tree one" "tree two"}
                                (into #{} (map :my.plan/title) (plan/tree {})))
                             "both roots coexist"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest reconcile-idless-root-refuses-when-ambiguous
  (async done
    (let [st (atom {})]
      (-> (with-agent-conn
            (fn []
              (-> (plan/plan! {:my.plan/title "tree one"})
                  (.then (fn [{:my.plan/keys [root]}]
                           (swap! st assoc :r1 root)
                           (plan/plan! {:my.plan/title "tree two"})))
                  (.then (fn [{:my.plan/keys [root]}]
                           (swap! st assoc :r2 root)
                           ;; two open roots, an id-less root naming neither —
                           ;; a guess would silently drop one and mint
                           (plan/reconcile!
                             {:my.plan/tree
                              {:my.plan/title "a third thing"
                               :my.plan/children [{:my.plan/title "s"}]}})))
                  (.then
                    (fn [{ok? :my.plan/ok? error :my.plan/error}]
                      (let [{:keys [r1 r2]} @st]
                        (is (false? ok?)
                            "ambiguous id-less root refused, never guess-minted")
                        (is (str/includes? error r1)
                            "the envelope names candidate root one")
                        (is (str/includes? error r2)
                            "…and candidate root two")
                        (is (= #{"tree one" "tree two"}
                               (into #{}
                                     (map :my.plan/title)
                                     (plan/tree {})))
                            "nothing changed on refusal — both trees intact")))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest reconcile-idless-child-resolves-on-identical-title
  (async done
    (let [st (atom {})]
      (-> (with-agent-conn
            (fn []
              (-> (plan/plan! {:my.plan/title "root"
                               :my.plan/children
                               [{:my.plan/title "alpha" :my.plan/ref "a"}
                                {:my.plan/title "beta"  :my.plan/ref "b"}]})
                  (.then
                    (fn [{:my.plan/keys [ok? ids]}]
                      (is (true? ok?))
                      (reset! st ids)
                      ;; alpha re-stated WITHOUT its id, title identical —
                      ;; resolve and update in place, never drop+re-mint
                      (let [[doc-root] (plan/document {})
                            kids (mapv (fn [k]
                                         (if (= (get ids "a") (:my.plan/id k))
                                           (-> k
                                               (dissoc :my.plan/id)
                                               (assoc :my.plan/description
                                                      "sharpened"))
                                           k))
                                       (:my.plan/_parent doc-root))]
                        (plan/reconcile!
                          {:my.plan/tree
                           (assoc doc-root :my.plan/_parent kids)}))))
                  (.then
                    (fn [{:my.plan/keys [ok? diff] :as env}]
                      (is (true? ok?) "a 1:1 identical-title child resolves")
                      (is (= {:my.plan/added 0 :my.plan/dropped 0
                              :my.plan/updated 1}
                             diff)
                          "resolved child UPDATES — nothing minted or dropped")
                      (is (not (contains? env :my.plan/resolved-root))
                          "the root itself carried its id — no root resolution")
                      (is (= "sharpened"
                             (:my.plan/description
                               (d/pull @db/*conn* '[*]
                                       [:my.plan/id (get @st "a")])))
                          "the edit landed ON the original alpha"))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest reconcile-idless-child-refuses-on-ambiguous-title
  (async done
    (let [st (atom {})]
      (-> (with-agent-conn
            (fn []
              (-> (plan/plan! {:my.plan/title "root"
                               :my.plan/children
                               [{:my.plan/title "alpha" :my.plan/ref "a1"}
                                {:my.plan/title "alpha" :my.plan/ref "a2"}]})
                  (.then
                    (fn [{:my.plan/keys [ok? ids]}]
                      (is (true? ok?))
                      (reset! st ids)
                      ;; ONE id-less «alpha» against TWO open alphas — which
                      ;; original it re-states is unknowable: refuse
                      (let [[doc-root] (plan/document {})]
                        (plan/reconcile!
                          {:my.plan/tree
                           (assoc doc-root :my.plan/_parent
                                  [{:my.plan/title "alpha"}])}))))
                  (.then
                    (fn [{ok? :my.plan/ok? error :my.plan/error}]
                      (is (false? ok?) "an ambiguous title match is refused")
                      (is (str/includes? error (get @st "a1"))
                          "the envelope names candidate a1")
                      (is (str/includes? error (get @st "a2"))
                          "…and candidate a2")
                      (is (some? (plan-int/status-of (get @st "a1")))
                          "nothing dropped on refusal")
                      (is (some? (plan-int/status-of (get @st "a2")))))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest reconcile-requires-a-tree-document
  (async done
    (-> (with-agent-conn
          (fn []
            (-> (plan/reconcile! {})
                (.then (fn [{ok? :my.plan/ok? error :my.plan/error}]
                         (is (false? ok?) "no :my.plan/tree refused")
                         (is (re-find #":my.plan/tree" error)
                             "the error names the required argument")
                         (is (empty? (:my.plan/steps (plan/list-open {})))
                             "nothing minted on failure"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest reconcile-tree-entry-declares-the-prefill-affordance
  ;; The registry-driven draft-head affordance (planner-worker-design): the
  ;; ::tree entry's :seon.render/prefill-fn PROPERTY names the projection of
  ;; the argument's current value. W2's driver consumes it; here we only
  ;; assert the declaration is discoverable from the registered schema.
  (let [definition (schema/schema-definition :my.plan/reconcile-request)
        entry      (some (fn [e] (when (and (vector? e)
                                            (= :my.plan/tree (first e)))
                                   e))
                         (rest definition))]
    (is (= 'my.plan/document (:seon.render/prefill-fn (second entry)))
        "::tree declares my.plan/document as its prefill projection")))

(deftest plan-rejects-misspelled-my-plan-keys
  ;; Registry class (silent unknown-key acceptance in my.* request maps): an
  ;; OPEN request map silently swallows a wrongly-NAMED :my.plan/* key
  ;; (:my.plan/steps for :my.plan/children), minting a childless plan. The
  ;; COMPUTED guard (accepted set derived from the schemas, no name list)
  ;; rejects it loudly + a did-you-mean; foreign/injectable + correct keys
  ;; are unaffected. Recursion catches a child-level typo too.
  (async done
    (-> (with-agent-conn
          (fn []
            (-> (plan/plan! {:my.plan/title "typo plan"
                             :my.plan/steps [{:my.plan/title "x"}]})
                (.then (fn [{ok? :my.plan/ok? error :my.plan/error}]
                         (is (false? ok?)
                             "the wrong-key request FAILS, not silently succeeds")
                         (is (str/includes? error ":my.plan/steps")
                             "the envelope names the unknown key")
                         (is (str/includes? error ":my.plan/children")
                             "and lists the accepted keys — the real one is there")
                         (is (empty? (:my.plan/steps (plan/list-open {})))
                             "a rejected request mints NO plan (the silent bug is gone)")
                         (plan/plan! {:my.plan/title "typo2" :my.plan/gol "why"})))
                (.then (fn [{ok? :my.plan/ok? error :my.plan/error}]
                         (is (false? ok?))
                         (is (re-find #"did you mean :my.plan/goal" error)
                             "a near-miss key gets a did-you-mean suggestion")
                         (plan/plan! {:my.plan/title "root ok"
                                      :my.plan/children
                                      [{:my.plan/title "kid" :my.plan/xpect "oops"}]})))
                (.then (fn [{ok? :my.plan/ok? error :my.plan/error}]
                         (is (false? ok?) "a misspelled key inside a child is caught")
                         (is (re-find #"did you mean :my.plan/expect" error)
                             "the child guard uses the NODE key set for its suggestion")
                         (plan/step! {:my.plan/title "s"
                                      :my.plan/parnt [:my.plan/id "z"]})))
                (.then (fn [{ok? :my.plan/ok? error :my.plan/error}]
                         (is (false? ok?))
                         (is (re-find #"did you mean :my.plan/parent" error)
                             "step! rejects its own misspelled key (one shared check)")
                         (plan/plan! {:my.plan/title "correct" :my.plan/goal "g"
                                      :my.plan/children
                                      [{:my.plan/title "kid" :my.plan/ref "k"}]})))
                (.then (fn [{ok? :my.plan/ok?}]
                         (is (true? ok?)
                             "a correctly-keyed plan is unaffected by the guard"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; stuck×N → frontier re-plan escalation (all DERIVED — no stored flag).
;; The wedge fixture mirrors the W3 drive evidence: one broken
;; schema/register! form re-failed turn after turn on the ▶ step.
;; ============================================================

(def ^:private wedge-src
  "(schema/register! :my.exp/rows (map [:string]))")

(defn- eval-fact
  "One synthetic eval fact without its writer-allocated identity."
  [ok? src]
  (cond-> {:seon.eval/at (js/Date.)
           :seon.eval/duration-ms 1 :seon.eval/narration ""
           :seon.eval/source src :seon.eval/ok? ok?
           :seon.eval/ns :my.agent.a :seon.eval/agent a-ref}
    (not ok?) (assoc :seon.eval/error "register!: input invalid")))

(defn- transact-eval!
  "Allocate and commit one synthetic eval, returning its durable id."
  [fact]
  (-> (allocate-facts! db/*conn* :seon.eval/id [[::wedge-eval fact]])
      (.then
        (fn [{ok? :seon.db/ok? err :seon.db/error ids ::db.id/ids}]
          (if ok?
            (get ids ::wedge-eval)
            (throw (ex-info "synthetic eval allocation failed" err)))))))

(defn- transact-rows!
  "Allocate facts sequentially; resolves to ids in transaction order."
  [facts]
  (reduce (fn [p fact]
            (.then p
                   (fn [ids]
                     (-> (transact-eval! fact)
                         (.then (fn [id] (conj ids id)))))))
          (js/Promise.resolve [])
          facts))

(defn- active-wedge-step!
  "plan! one step, active! it; resolves to the step id."
  []
  (-> (plan/plan! {:my.plan/title "wedge plan" :my.plan/goal "g"
                   :my.plan/children [{:my.plan/title "the step"
                                       :my.plan/expect "rows exist"}]
                   :seon.agent/id a-id})
      (.then (fn [_]
               (let [sid (:my.plan/id (first (plan/next {:seon.agent/id a-id})))]
                 (-> (plan/active! {:my.plan/id sid})
                     (.then (fn [_] sid))))))))

(defn- esc-now
  "The derived escalation for agent A over the CURRENT db."
  []
  (let [db @db/*conn*]
    (plan-int/escalation db (plan-int/agent-eid db a-ref))))

(deftest escalation-flags-n-same-root-failures-and-not-n-minus-1
  (async done
    (-> (with-agent-conn
          (fn []
            (-> (active-wedge-step!)
                (.then (fn [sid]
                         (-> (transact-rows! [(eval-fact false wedge-src)
                                              (eval-fact false wedge-src)])
                             (.then (fn [eval-ids]
                                      (is (nil? (esc-now))
                                          "N-1 same-root failures do NOT flag")
                                      (-> (transact-eval! (eval-fact false wedge-src))
                                          (.then
                                            (fn [_]
                                              (let [esc (esc-now)]
                                                (is (some? esc) "N same-root failures flag NOW")
                                                (is (= sid (:my.plan/id esc)) "the ▶ step is the flagged step")
                                                (is (= 3 (:my.plan/fail-count esc)))
                                                (is (= "schema/register!" (:my.plan/root-sym esc))
                                                    "root = the head symbol the agent typed, structurally read")
                                                (is (= (first eval-ids) (:my.plan/episode esc))
                                                    "episode identity = the streak's FIRST failing eval id")))))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest escalation-same-call-success-breaks-the-wedge
  (async done
    (-> (with-agent-conn
          (fn []
            (-> (active-wedge-step!)
                (.then (fn [_]
                         (transact-rows!
                           [(eval-fact false wedge-src)
                            (eval-fact false wedge-src)
                            ;; the failing CALL finally succeeds — progress on
                            ;; this root; every prior failure stops counting
                            (eval-fact true wedge-src)
                            (eval-fact false wedge-src)
                            (eval-fact false wedge-src)])))
                (.then (fn [_]
                         (is (nil? (esc-now))
                             "a same-call success resets the streak (2 live fails < N)")
                         ;; an UNRELATED success (a defn redefine — the W3 wedge
                         ;; interleaved these) is NOT progress on the root
                         (transact-rows!
                           [(eval-fact true "(defn broken-loader [] 1)")
                            (eval-fact false wedge-src)])))
                (.then (fn [_]
                         (let [esc (esc-now)]
                           (is (some? esc)
                               "an unrelated success does not break the streak")
                           (is (= 3 (:my.plan/fail-count esc)))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest escalation-counts-only-failures-since-the-step-went-active
  (async done
    (-> (with-agent-conn
          (fn []
            ;; failures land BEFORE the step is taken up — a fresh ▶ starts
            ;; with a clean slate (the window opens at the :active tx)
            (-> (plan/plan! {:my.plan/title "wedge plan" :my.plan/goal "g"
                             :my.plan/children [{:my.plan/title "the step"}]
                             :seon.agent/id a-id})
                (.then (fn [_]
                         (transact-rows! [(eval-fact false wedge-src)
                                          (eval-fact false wedge-src)])))
                (.then (fn [_]
                         (let [sid (:my.plan/id (first (plan/next {:seon.agent/id a-id})))]
                           (plan/active! {:my.plan/id sid}))))
                (.then (fn [_] (transact-eval! (eval-fact false wedge-src))))
                (.then (fn [_]
                         (is (nil? (esc-now))
                             "pre-activation failures do not count toward the wedge"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest escalation-section-renders-reactively-and-vanishes
  (async done
    (-> (with-agent-conn
          (fn []
            (-> (active-wedge-step!)
                (.then (fn [_]
                         (transact-rows! [(eval-fact false wedge-src)
                                          (eval-fact false wedge-src)
                                          (eval-fact false wedge-src)])))
                (.then (fn [_]
                         (let [body (plan-int/plan-body @db/*conn* a-ref)]
                           (is (str/includes? body "STUCK ▶")
                               "flagged ⇒ the STUCK band renders in the plan block")
                           (is (str/includes? body "schema/register!")
                               "the band names the failure root")
                           (is (str/includes? body b-id)
                               "the derived planner (B — default frontier provider) is named")
                           ;; break the wedge — the band must VANISH (nothing
                           ;; stored, nothing to acknowledge)
                           (transact-eval! (eval-fact true wedge-src)))))
                (.then (fn [_]
                         (is (not (str/includes? (plan-int/plan-body @db/*conn* a-ref)
                                                 "STUCK"))
                             "wedge broken ⇒ the band is gone from the render"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest escalation-consult-fires-once-per-episode
  (async done
    (-> (with-agent-conn
          (fn []
            (-> (active-wedge-step!)
                (.then (fn [_]
                         (transact-rows! [(eval-fact false wedge-src)
                                          (eval-fact false wedge-src)
                                          (eval-fact false wedge-src)])))
                (.then (fn [_] (plan-int/maybe-consult! {:seon.agent/id a-id})))
                (.then (fn [env]
                         (is (true? (:my.plan/consulted? env)) "flag transition ⇒ consult fires")
                         (is (= b-id (:my.plan/planner env))
                             "planner derived: the non-worker agent on a frontier provider")
                         (plan-int/maybe-consult! {:seon.agent/id a-id})))
                (.then (fn [env2]
                         (is (false? (:my.plan/consulted? env2)))
                         (is (= :already-consulted (:my.plan/consult-reason env2))
                             "episode identity is derived — no second message, no stored flag")
                         ;; the wedge deepens WITHIN the episode — still no re-fire
                         (transact-eval! (eval-fact false wedge-src))))
                (.then (fn [_] (plan-int/maybe-consult! {:seon.agent/id a-id})))
                (.then (fn [env3]
                         (is (= :already-consulted (:my.plan/consult-reason env3))
                             "a deeper wedge is the SAME episode (same first-fail eval)")
                         (let [msgs (db/query {:seon.db/query
                                               '[:find ?c :in $ ?from
                                                 :where
                                                 [?m :seon.agent.message/from ?from]
                                                 [?m :seon.agent.message/content ?c]]
                                               :seon.db/args
                                               [(plan-int/agent-eid @db/*conn* a-ref)]})]
                           (is (= 1 (count msgs)) "exactly ONE consult message exists")
                           (is (str/includes? (ffirst msgs) "[escalation :my.plan/step")
                               "the message embeds the episode marker")
                           (is (str/includes? (ffirst msgs) "my.plan/reconcile!")
                               "the ask names reconcile! as the write-back")
                           (is (str/includes? (ffirst msgs) "(complete \"re-planned")
                               "the ask CARRIES its completion condition (turn economy)"))
                         (is (str/includes? (plan-int/plan-body @db/*conn* a-ref)
                                            "has been consulted")
                             "the section reflects the consult from the message log"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest escalation-no-planner-is-a-no-op-with-a-rendered-note
  (async done
    (-> (with-agent-conn
          (fn []
            ;; B on a LOCAL diffusion provider ⇒ no frontier agent besides the
            ;; worker itself ⇒ consult is a no-op; the section says so.
            (-> (db/transact! {:seon.db/tx-data
                               [{:seon.agent/id b-id
                                 :seon.ai/agent-provider :typeahead}]})
                (.then (fn [_] (active-wedge-step!)))
                (.then (fn [_]
                         (transact-rows! [(eval-fact false wedge-src)
                                          (eval-fact false wedge-src)
                                          (eval-fact false wedge-src)])))
                (.then (fn [_] (plan-int/maybe-consult! {:seon.agent/id a-id})))
                (.then (fn [env]
                         (is (false? (:my.plan/consulted? env)))
                         (is (= :no-planner (:my.plan/consult-reason env)))
                         (is (str/includes? (plan-int/plan-body @db/*conn* a-ref)
                                            "No frontier planner")
                             "the section renders the no-planner note"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
