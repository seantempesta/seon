(ns seon.agent-lifecycle-test
  "Agent lifecycle (P3.5/#31): `:seon.agent/completed-at` + RESUME, DON'T
   MINT. Pins the invariants the boot path depends on:

     - `seon.agent/complete!` mirrors `seon.agent.todo/complete!` exactly:
       stamp `completed-at`, unknown id → fail envelope, already-completed
       → idempotent success; id defaults to the ALS-scoped agent.
     - un-complete is an EXPLICIT `[:db/retract …]` (absent = active).
     - `seon.client/resumable-agent-ids` — the boot resume query — returns
       every agent WITHOUT `completed-at`, sorted, and skips completed
       ones; empty store = genuine first boot (mint path).

   Tests open a FRESH `:memory` conn (via `seon.client/open-agent-conn!`,
   the same boot helper the pod uses) — nothing here touches the live
   agents.

   Run interactively via MCP eval:
     (require 'seon.agent-lifecycle-test :reload)
     (cljs.test/run-tests 'seon.agent-lifecycle-test)"
  (:require
    [cljs.test :refer [deftest is testing async]]
    [seon.agent :as agent]
    [seon.client :as client]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.render.live-tile :as tile]
    [seon.repl :as repl]
    [seon.repl.internal :as repl.internal]))

(defn- with-conn
  "Open a fresh schema-loaded conn, `set!` it as the ROOT `db/*conn*`
   (a plain `binding` does NOT survive Promise/await boundaries in CLJS
   — the same reason the pod boot uses set!), run `body` (0-arg, may
   return a Promise), restore the prior root after. Returns a Promise."
  [body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (let [prev db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body))
                     (.finally (fn [] (set! db/*conn* prev)))))))))

(defn- seed-agents!
  "Transact agent entities `a1` (active) + `a2` (active)."
  []
  (db/transact!
    {:seon.db/tx-data [{:seon.agent/id "aaa-2606101200" :seon.agent/state :idle}
                       {:seon.agent/id "bbb-2606101200" :seon.agent/state :idle}]}))

(deftest complete!-stamps-and-is-idempotent
  (async done
    (-> (with-conn
          (fn ^:async run []
            (await (seed-agents!))
            (testing "complete! stamps completed-at and answers ok"
              (let [env (await (agent/complete! {:seon.agent/id "aaa-2606101200"}))]
                (is (true? (:seon.agent/ok? env)))
                (is (= "aaa-2606101200" (:seon.agent/id env))))
              (is (inst? (:seon.agent/completed-at
                           (db/entity {:seon.db/ref [:seon.agent/id "aaa-2606101200"]})))
                  "completed-at is stamped as an inst"))
            (testing "already-completed is idempotent success"
              (let [env (await (agent/complete! {:seon.agent/id "aaa-2606101200"}))]
                (is (true? (:seon.agent/ok? env)))
                (is (= "aaa-2606101200" (:seon.agent/id env)))))
            (testing "unknown id → fail envelope (errors are values)"
              (let [env (await (agent/complete! {:seon.agent/id "zzz-2606101299"}))]
                (is (false? (:seon.agent/ok? env)))
                (is (string? (:seon.agent/error env)))))
            (testing "no id + no agent in scope → fail envelope"
              (let [env (await (agent/complete! {}))]
                (is (false? (:seon.agent/ok? env)))
                (is (string? (:seon.agent/error env)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest complete!-defaults-to-scoped-agent
  (async done
    (-> (with-conn
          (fn ^:async run []
            (await (seed-agents!))
            (await
              (db/with-agent "bbb-2606101200"
                (fn ^:async in-scope []
                  (let [env (await (agent/complete! {}))]
                    (is (true? (:seon.agent/ok? env)))
                    (is (= "bbb-2606101200" (:seon.agent/id env))
                        "id defaulted from the ALS scope, like reply!")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest resume-query-skips-completed-and-retract-restores
  (async done
    (-> (with-conn
          (fn ^:async run []
            (testing "empty store = genuine first boot (mint path)"
              (is (= [] (client/resumable-agent-ids @db/*conn*))))
            (await (seed-agents!))
            (testing "both active agents are resumable, sorted"
              (is (= ["aaa-2606101200" "bbb-2606101200"]
                     (client/resumable-agent-ids @db/*conn*))))
            (await (agent/complete! {:seon.agent/id "aaa-2606101200"}))
            (testing "completed agent is NOT resumed (history, not roster)"
              (is (= ["bbb-2606101200"]
                     (client/resumable-agent-ids @db/*conn*))))
            ;; Un-complete = explicit retract (absent = active; never nil).
            (let [env (await (db/transact!
                               {:seon.db/tx-data
                                [[:db/retract [:seon.agent/id "aaa-2606101200"]
                                  :seon.agent/completed-at]]}))]
              (is (true? (:seon.db/ok? env)) "retract transacts clean"))
            (testing "retracting completed-at makes the agent resumable again"
              (is (= ["aaa-2606101200" "bbb-2606101200"]
                     (client/resumable-agent-ids @db/*conn*))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; creation-evals! — live-tiles PRD 2026-06-11 §6 U4 (minimal scope).
;; A MINTED agent's first logged act is the REAL tile-wiring eval:
;; the eval log opens with the tutorial transact, the datom lands on
;; the agent's own entity (wired value, not the render fallback),
;; and — because the datom is durable — a restart needs no re-seed.
;; ============================================================

(def ^:private wired-id "AGTwiretile001")        ; 14 chars (:seon.db/id)

(deftest creation-evals!-wires-the-tile-as-a-real-logged-eval
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [compile-state (await (repl/ensure-bootstrap!))]
              ;; The boot path's per-agent slice (boot-one-agent!'s
              ;; relevant half): home ns + entity, in the agent's scope.
              (await
                (db/with-agent wired-id
                  (fn ^:async boot []
                    (await (seval/setup-agent-ns! compile-state
                                                  (agent/home-ns wired-id)
                                                  wired-id))
                    (await (agent/create! {:seon.agent/id wired-id})))))
              (let [batch (await (client/creation-evals!
                                   {:seon.agent/id            wired-id
                                    :seon.agent/compile-state compile-state}))]
                (is (= 3 (:seon.eval/n-ok batch))
                    "THREE creation evals, ok — the wiring, the
                     (seon.db/store-inventory) read (V4-3), and the
                     (my.kb.system/instructions) read (V4-0)")
                (is (zero? (:seon.eval/n-fail batch)) "no failures"))
              (testing "the datom landed — render resolves the WIRED value"
                (let [ent (db/entity {:seon.db/ref [:seon.agent/id wired-id]})
                      raw (:seon.render.live-tile/content ent)]
                  (is (some? raw) "attr present on the agent's own entity")
                  (is (= tile/welcome-sym
                         (db/decode-edn-value
                           :seon.render.live-tile/content raw))
                      "decodes to the substrate welcome symbol")
                  (let [{:seon.render.live-tile/keys [source value]}
                        (tile/wired-content
                          {:seon.render/entity
                           {:seon.agent/id wired-id
                            :seon.render.live-tile/content raw}})]
                    (is (= :seon.render.live-tile/content source)
                        "provenance = the tile key — NOT the welcome fallback")
                    (is (= tile/welcome-sym value)))))
              (testing "the eval log's FIRST entry is the tutorial wiring eval"
                (let [session (agent/current-session wired-id)
                      turns   (:seon.agent.session/turns session)
                      turn    (first turns)
                      ;; entity many-refs come back as a SET — order by
                      ;; :at (the batch runs the three tutorial forms in
                      ;; sequence).
                      evals   (vec (sort-by :seon.eval/at
                                            (:seon.agent.turn/evals turn)))
                      ev      (first evals)]
                  (is (= 1 (count turns)) "one creation turn")
                  (is (= :done (:seon.agent.turn/status turn)))
                  (is (= 3 (count evals))
                      "three evals — the wiring, the store-inventory
                       read (V4-3), then the system-wide instructions
                       read (V4-0)")
                  (is (true? (:seon.eval/ok? ev)) "a REAL ok result")
                  (is (= (:source (first (repl.internal/parse-forms
                                           (tile/wiring-source wired-id))))
                         (:seon.eval/source ev))
                      "source is byte-faithfully the canonical wiring form")
                  (is (re-find #"lookup ref" (str (:seon.eval/narration ev)))
                      "tutorial comments ride as the eval's narration")
                  (is (re-find #":seon.db/ok\? true"
                               (str (:seon.eval/result-edn ev)))
                      "the recorded result is the transact's ok envelope")
                  (let [ev2 (second evals)]
                    (is (= "(seon.db/store-inventory)"
                           (:seon.eval/source ev2))
                        "the SECOND eval is the store-inventory read —
                         the §3.1 consult-first tutorial query (V4-3)")
                    (is (true? (:seon.eval/ok? ev2)))
                    (is (re-find #"already in the shared store"
                                 (str (:seon.eval/narration ev2)))
                        "its tutorial comment rides as narration"))
                  (let [ev3 (nth evals 2)]
                    (is (= "(my.kb.system/instructions)"
                           (:seon.eval/source ev3))
                        "the THIRD eval is the system-wide instructions
                         read — the §3.1 tutorial query")
                    (is (true? (:seon.eval/ok? ev3)))
                    (is (re-find #"system-wide instructions"
                                 (str (:seon.eval/narration ev3)))
                        "its tutorial comment rides as narration")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
