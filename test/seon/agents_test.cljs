(ns seon.agents-test
  "Phase 0 tests for `seon.agents` — the per-agent runtime atom held
   in the dynvar `*ctx*`.

   Each test exercises one Phase 0 invariant:
     1. *ctx* is unbound by default; ctx-or-throw fails loudly.
     2. run-as-agent binds *ctx* to the right atom; swap! mutates it.
     3. The binding survives `await` (Node ALS extension).
     4. Two concurrent agents see only their own atom across interleaved
        awaits.
     5. Identity-assert: calling run-as-agent with an atom whose
        :seon.agents/id mismatches the claim throws AND logs.

   PRD: docs/prds/agent-runtime/atom-state-system-2026-05-26.md
   Phase 0 spec: §10 Phase 0 row + §5 sketches.

   Tests #3 + #4 (cross-await + interleaving) are written so the
   runner picks them up; live runtime verification is gated on the
   `seon-cljs` MCP being online (currently offline). They will run
   on the next live REPL session."
  (:require
    [cljs.test :as t :refer [deftest is testing async]]
    [seon.agents :as agents]
    [seon.log :as log]))

;; ============================================================
;; Test helpers
;; ============================================================

(defn- cleanup-agent! [id]
  (try (agents/stop-agent! {:seon.agents/id id}) (catch :default _ nil)))

(defn- fresh-id
  "14-char id satisfying :seon.db/id."
  ([] (fresh-id "agt"))
  ([prefix]
   (let [tail (str (.now js/Date))
         pad  (apply str (repeat (max 0 (- 14 (count prefix) (count tail))) "0"))]
     (subs (str prefix pad tail) 0 14))))

;; ============================================================
;; #1 — *ctx* unbound default
;; ============================================================

(deftest ctx-unbound-default-is-nil
  (testing "*ctx* defaults to nil — silent reads possible only via raw deref"
    (is (nil? agents/*ctx*) "fresh ns load: *ctx* is nil")))

(deftest ctx-or-throw-throws-when-unbound
  (testing "ctx-or-throw turns silent nil into a loud throw"
    (let [thrown (try (agents/ctx-or-throw) :no-throw
                      (catch :default e e))]
      (is (instance? ExceptionInfo thrown))
      (is (= :ctx-unbound (:seon.agents/error (ex-data thrown)))))))

;; ============================================================
;; #2 — run-as-agent binds *ctx* to the right atom
;; ============================================================

(deftest run-as-agent-binds-the-passed-atom
  (let [id (fresh-id "bind")]
    (try
      (let [a (agents/start-agent! {:seon.agents/id id})
            observed (atom :unset)]
        (agents/run-as-agent
          {:seon.agents/id      id
           :seon.agents/atom    a
           :seon.agents/body-fn
           (fn []
             (reset! observed
                     {:identical? (identical? a agents/*ctx*)
                      :deref-id   (:seon.agents/id @agents/*ctx*)
                      :state      (:seon.agents/state @agents/*ctx*)}))})
        (is (:identical? @observed) "*ctx* IS the atom we passed")
        (is (= id (:deref-id @observed)) "atom carries the agent id")
        (is (= :booting (:state @observed)) "seeded with :booting"))
      (finally (cleanup-agent! id)))))

(deftest run-as-agent-swap-mutates-this-agents-atom
  (let [id (fresh-id "swap")]
    (try
      (let [a (agents/start-agent! {:seon.agents/id id})]
        (agents/run-as-agent
          {:seon.agents/id      id
           :seon.agents/atom    a
           :seon.agents/body-fn
           (fn []
             (swap! agents/*ctx* assoc :seon.agents/state :running))})
        (is (= :running (:seon.agents/state @a))
            "swap! through *ctx* mutates the registry-held atom"))
      (finally (cleanup-agent! id)))))

(deftest ctx-unbinds-after-body-returns
  (let [id (fresh-id "unb")]
    (try
      (let [a (agents/start-agent! {:seon.agents/id id})]
        (agents/run-as-agent
          {:seon.agents/id      id
           :seon.agents/atom    a
           :seon.agents/body-fn (fn [] :done)})
        (is (nil? agents/*ctx*)
            "after run-as-agent returns, *ctx* is back to nil"))
      (finally (cleanup-agent! id)))))

;; ============================================================
;; #3 — Cross-await binding survival (LOAD-BEARING Phase 0 invariant)
;; ============================================================
;;
;; VERIFIED: STATICALLY ONLY in this session (MCP cljs offline).
;; Awaiting live REPL run.

(deftest cross-await-binding-survives
  (async done
    (let [id (fresh-id "awt")
          a  (agents/start-agent! {:seon.agents/id id})
          !observed (atom :unset)
          body-promise
          (agents/run-as-agent
            {:seon.agents/id      id
             :seon.agents/atom    a
             :seon.agents/body-fn
             (fn []
               (-> (js/Promise.resolve nil)
                   (.then (fn [_]
                            (reset! !observed
                                    {:bound? (some? agents/*ctx*)
                                     :same?  (identical? a agents/*ctx*)
                                     :id     (:seon.agents/id @agents/*ctx*)})))))})
          finish (fn []
                   (is (:bound? @!observed)
                       "post-await *ctx* is still bound")
                   (is (:same? @!observed)
                       "post-await *ctx* is the SAME atom (ALS reattached)")
                   (is (= id (:id @!observed))
                       "post-await atom carries the right id")
                   (cleanup-agent! id)
                   (done))]
      (-> (js/Promise.resolve body-promise)
          (.then finish)
          (.catch (fn [e]
                    (cleanup-agent! id)
                    (is false (str "cross-await test threw — " e))
                    (done)))))))

;; ============================================================
;; #4 — Multi-agent interleaving
;; ============================================================
;;
;; Two agents, A and B. A's body schedules a microtask (the `.then`),
;; then B's body runs synchronously to completion, then A's microtask
;; fires. Each MUST see its own atom only. Proves ALS-per-fiber
;; scoping survives interleave (vs the naked-dynvar clobber from
;; db.cljs §*tx-context* Probe 13).
;;
;; VERIFIED: STATICALLY ONLY in this session (MCP cljs offline).
;; Awaiting live REPL run.

(deftest multi-agent-interleaving-keeps-atoms-distinct
  (async done
    (let [id-a    (fresh-id "iA")
          id-b    (fresh-id "iB")
          atom-a  (agents/start-agent! {:seon.agents/id id-a})
          atom-b  (agents/start-agent! {:seon.agents/id id-b})
          !a-seen (atom :unset)
          !b-seen (atom :unset)
          p-a (agents/run-as-agent
                {:seon.agents/id      id-a
                 :seon.agents/atom    atom-a
                 :seon.agents/body-fn
                 (fn []
                   (-> (js/Promise.resolve nil)
                       (.then (fn [_]
                                (reset! !a-seen
                                        {:same? (identical? atom-a agents/*ctx*)
                                         :id    (:seon.agents/id @agents/*ctx*)})))))})
          _ (agents/run-as-agent
              {:seon.agents/id      id-b
               :seon.agents/atom    atom-b
               :seon.agents/body-fn
               (fn []
                 (reset! !b-seen
                         {:same? (identical? atom-b agents/*ctx*)
                          :id    (:seon.agents/id @agents/*ctx*)}))})
          finish (fn []
                   (is (:same? @!a-seen)
                       "A's post-await *ctx* is A's atom")
                   (is (= id-a (:id @!a-seen))
                       "A saw its own id")
                   (is (:same? @!b-seen)
                       "B's sync body saw B's atom")
                   (is (= id-b (:id @!b-seen))
                       "B saw its own id")
                   (cleanup-agent! id-a)
                   (cleanup-agent! id-b)
                   (done))]
      (-> (js/Promise.resolve p-a)
          (.then finish)
          (.catch (fn [e]
                    (cleanup-agent! id-a)
                    (cleanup-agent! id-b)
                    (is false (str "interleave test threw — " e))
                    (done)))))))

;; ============================================================
;; #5 — Identity-assert SPOF mitigation
;; ============================================================

(deftest identity-mismatch-throws
  (testing "run-as-agent throws when atom's id doesn't match the claim"
    (let [id-real    (fresh-id "real")
          id-claimed (fresh-id "fake")]
      (try
        (let [a (agents/start-agent! {:seon.agents/id id-real})
              thrown (try
                       (agents/run-as-agent
                         {:seon.agents/id      id-claimed
                          :seon.agents/atom    a
                          :seon.agents/body-fn (fn [] :never-reached)})
                       :no-throw
                       (catch :default e e))]
          (is (instance? ExceptionInfo thrown)
              "identity mismatch raises ex-info")
          (is (= :identity-mismatch
                 (:seon.agents/error (ex-data thrown))))
          (is (= id-claimed (:seon.agents/claimed-id (ex-data thrown))))
          (is (= id-real (:seon.agents/atom-id (ex-data thrown)))))
        (finally (cleanup-agent! id-real))))))

(deftest identity-mismatch-emits-error-log
  ;; We don't snapshot the log file (env-dependent). We rebind
  ;; log/error! locally via with-redefs to confirm the log path
  ;; is wired. The throw still fires AFTER the log call.
  (testing "identity mismatch emits via seon.log/error!"
    (let [id-real    (fresh-id "rl2")
          id-claimed (fresh-id "fk2")
          !called    (atom nil)]
      (try
        (with-redefs [log/error! (fn [m]
                                   (reset! !called m)
                                   (js/Promise.resolve m))]
          (let [a (agents/start-agent! {:seon.agents/id id-real})]
            (try
              (agents/run-as-agent
                {:seon.agents/id      id-claimed
                 :seon.agents/atom    a
                 :seon.agents/body-fn (fn [] :never)})
              (catch :default _ nil))))
        (is (some? @!called) "log/error! was invoked on identity mismatch")
        (when @!called
          (is (= :seon.agents/identity-mismatch
                 (:seon.log/source @!called)))
          (is (string? (:seon.log/message @!called))))
        (finally (cleanup-agent! id-real))))))

;; ============================================================
;; Registry hygiene
;; ============================================================

(deftest start-then-stop-removes-from-registry
  (let [id (fresh-id "reg")
        a  (agents/start-agent! {:seon.agents/id id})]
    (is (contains? (agents/registered-ids) id))
    (is (identical? a (agents/lookup id)))
    (agents/stop-agent! {:seon.agents/id id})
    (is (not (contains? (agents/registered-ids) id)))
    (is (nil? (agents/lookup id)))))

(deftest double-start-throws-id-collision
  (let [id (fresh-id "col")]
    (try
      (agents/start-agent! {:seon.agents/id id})
      (let [thrown (try (agents/start-agent! {:seon.agents/id id}) :no-throw
                        (catch :default e e))]
        (is (instance? ExceptionInfo thrown))
        (is (= :id-collision (:seon.agents/error (ex-data thrown)))))
      (finally (cleanup-agent! id)))))
