(ns seon.resume-replay-test
  "Step 4 of coherent-bootstrap-indexing-2026-06-08: resume coverage + cleanup.

   On resume/boot the SUBSTRATE corpus is rebuilt from real source by
   `seon.client/index-substrate!` (no replay — its rows are compiled fns;
   re-evaling `(defn ^:async transact! …)` would be wrong). The agent's OWN
   corpus (fns / tests / schemas / nses under `seon.agent.<id>`) IS replayed:
   `replay-program-graph!` re-evals each persisted `:source` so the agent's
   definitions come back after a pod restart.

   These tests pin the Step 4 discriminator + cleanup:

     - `query-program-graph-entries` EXCLUDES substrate rows (owning ns in
       `substrate-ns-kws` = #{:seon.db :seon.schema :seon.test.runner}) — even
       when a substrate `:seon.fn` carries a `,,,` stub source, it is SKIPPED.
     - It INCLUDES agent-authored `:seon.fn` AND `:seon.test` rows (and agent
       `:seon.ns`), in tx order.
     - A full `replay-program-graph!` re-evals the agent fn + agent test source
       into the bootstrap compile-state (agent-fn callable, test `(def …)`
       reconstituted) and counts ONLY the agent rows — substrate rows are not
       replayed and contribute no failures.

   The discriminator is ns membership (derived from `substrate-vars`, the same
   source `index-substrate!` writes from) — NOT the `:seon.db/origin
   :substrate-seed` tx-meta, which can be absent on a re-asserted / older row.
   The old `,,,`/last-write-race ordering hack is therefore gone.

   Tests open a FRESH `:memory` conn (via `seon.client/open-agent-conn!`, the
   same boot helper the pod uses) and seed rows directly — nothing here touches
   the live agent.

   Run interactively via MCP eval:
     (require 'seon.resume-replay-test :reload)
     (cljs.test/run-tests 'seon.resume-replay-test)"
  (:require
    [cljs.test :as t :refer [deftest is async]]
    [malli.core :as m]
    [malli.registry :as mr]
    [seon.client :as client]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.repl :as repl]
    [seon.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Seed — a fresh conn with BOTH substrate rows (must be skipped) and
;; agent-authored rows (must replay).
;;
;;   :seon.db          — substrate ns + a `transact!` fn carrying a `,,,` STUB
;;                       source (the exact thing the old curated path wrote);
;;                       must be SKIPPED so the compiled fn is never shadowed.
;;   :seon.test.runner — substrate ns + a `run!` :seon.test row; must be SKIPPED.
;;   :seon.agent.t1    — agent ns + an `agent-fn` fn + a `my-test` :seon.test
;;                       row whose source `(def replay-marker 42)` EVALS; both
;;                       must REPLAY.
;; ---------------------------------------------------------------------------

(def ^:private seed-tx
  [{:seon.ns/name :seon.db :seon.ns/source "(ns seon.db)"}
   {:seon.ns/name :seon.test.runner :seon.ns/source "(ns seon.test.runner)"}
   {:seon.ns/name :seon.agent.t1 :seon.ns/source "(ns seon.agent.t1)"}
   ;; SUBSTRATE fn with a `,,,` stub source — must be SKIPPED on replay.
   {:seon.fn/sym "seon.db/transact!"
    :seon.fn/ns [:seon.ns/name :seon.db]
    :seon.fn/source "(defn transact! [x] ,,,)"
    :seon.fn/arglists "([x])"
    :seon.fn/doc ""
    :seon.fn/private? false}
   ;; AGENT fn — must REPLAY (re-eval into the agent ns).
   {:seon.fn/sym "seon.agent.t1/agent-fn"
    :seon.fn/ns [:seon.ns/name :seon.agent.t1]
    :seon.fn/source "(defn agent-fn [n] (* n 2))"
    :seon.fn/arglists "([n])"
    :seon.fn/doc ""
    :seon.fn/private? false}
   ;; AGENT test row — must REPLAY its source.
   {:seon.test/sym "seon.agent.t1/my-test"
    :seon.test/ns [:seon.ns/name :seon.agent.t1]
    :seon.test/source "(def replay-marker 42)"}
   ;; SUBSTRATE test row — must be SKIPPED.
   {:seon.test/sym "seon.test.runner/run!"
    :seon.test/ns [:seon.ns/name :seon.test.runner]
    :seon.test/source "(def should-not-replay true)"}])

(defn- query-entries
  "Call the private `query-program-graph-entries` against `conn`. Returns the
   Promise of the entry vector."
  [conn]
  ((deref #'client/query-program-graph-entries) conn))

(defn- with-seeded-conn
  "Open a fresh conn, seed `seed-tx`, run `body` (1-arg `conn`). Returns a
   Promise. `db/*conn*` is bound for the transact so lookup-refs resolve."
  [body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (binding [db/*conn* conn]
                 (-> (db/transact! {:seon.db/tx-data seed-tx})
                     (.then (fn [_] (body conn)))))))))

;; ---------------------------------------------------------------------------
;; query-program-graph-entries — the replay discriminator.
;; ---------------------------------------------------------------------------

(deftest replay-set-includes-agent-corpus-skips-substrate
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (.then
              (query-entries conn)
              (fn [entries]
                (let [pairs (set (map (juxt :kind :ident) entries))]
                  ;; AGENT corpus IS in the replay set.
                  (is (contains? pairs [:ns :seon.agent.t1]) "agent ns replays")
                  (is (contains? pairs [:fn "seon.agent.t1/agent-fn"]) "agent fn replays")
                  (is (contains? pairs [:test "seon.agent.t1/my-test"])
                      "agent :seon.test row replays its source")
                  ;; SUBSTRATE corpus is NOT in the replay set — even the
                  ;; `,,,`-stubbed transact! row, which the old curated path
                  ;; would have re-evaled into a broken shadow.
                  (is (not (contains? pairs [:ns :seon.db]))
                      "substrate :seon.db ns is SKIPPED")
                  (is (not (contains? pairs [:fn "seon.db/transact!"]))
                      "substrate transact! fn is SKIPPED (even with a ,,, stub)")
                  (is (not (contains? pairs [:ns :seon.test.runner]))
                      "substrate :seon.test.runner ns is SKIPPED")
                  (is (not (contains? pairs [:test "seon.test.runner/run!"]))
                      "substrate run! :seon.test row is SKIPPED")
                  ;; The whole replay set is agent-only: exactly the 3 agent rows.
                  (is (= 3 (count entries)) "only the 3 agent rows survive the filter"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Full replay — agent fn + agent test reconstitute; counts only agent rows.
;; ---------------------------------------------------------------------------

(deftest full-replay-reconstitutes-agent-fn-and-test
  (async done
    (-> (repl/ensure-bootstrap!)
        (.then
          (fn [cs]
            (with-seeded-conn
              (fn [conn]
                (-> (client/replay-program-graph!
                      {:conn conn :compile-state cs :agent-id "resume-replay-test"})
                    (.then
                      (fn [stats]
                        ;; ONLY the 3 agent rows replay; substrate rows skipped.
                        (is (= 3 (:seon.client/replay-n-total stats))
                            "exactly the 3 agent rows are in the replay set")
                        (is (= 0 (:seon.client/replay-n-fail stats))
                            "no replay failures")))
                    (.then
                      (fn [_]
                        (seval/eval cs "(seon.agent.t1/agent-fn 21)"
                                    {:ns 'cljs.user :analyze-deps? false})))
                    (.then
                      (fn [r]
                        (is (:ok r) "agent-fn replayed without error")
                        (is (= 42 (:value r))
                            "replayed agent-fn is callable: (agent-fn 21) => 42")
                        (seval/eval cs "(+ replay-marker 8)"
                                    {:ns 'seon.agent.t1 :analyze-deps? false})))
                    (.then
                      (fn [r]
                        (is (:ok r) "agent test source replayed without error")
                        (is (= 50 (:value r))
                            "replayed test (def replay-marker 42) is in scope: 42+8=50"))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Fail-loud replay errors — the warn must name the actual defect, not
;; cljs.js's literal "ERROR" wrapper (live incident 2026-06-10: every
;; :seon.workout/* replay failure logged as `failed: ERROR`).
;; ---------------------------------------------------------------------------

(deftest error-chain-message-surfaces-the-real-defect
  (let [err {:seon.error/message "ERROR"
             :seon.error/stack   "Error: ERROR\n    at compile-loop"
             :seon.error/cause
             {:seon.error/message "schema/register! :seon.workout/date: bad form"
              :seon.error/stack   "Error: schema/register!…\n    at assert_compilable"
              :seon.error/cause
              {:seon.error/message ":malli.core/invalid-schema"}}}]
    (is (= (str "ERROR <- schema/register! :seon.workout/date: bad form"
                " <- :malli.core/invalid-schema")
           ((deref #'client/error-chain-message) err))
        "every cause-level message joins into the surfaced string")
    (is (= "Error: schema/register!…\n    at assert_compilable"
           ((deref #'client/error-chain-stack) err))
        "stack comes from the DEEPEST level that has one — the throw site")))

(deftest error-chain-message-dedupes-and-skips-blanks
  (is (= "boom"
         ((deref #'client/error-chain-message)
          {:seon.error/message "boom"
           :seon.error/cause {:seon.error/message ""
                              :seon.error/cause {:seon.error/message "boom"}}}))
      "blank + duplicate messages collapse"))

;; ---------------------------------------------------------------------------
;; Registry stomp recovery — relink-registry! restores seon-registered
;; schema resolution after a foreign (mr/set-default-registry! …), the
;; exact side effect a bootstrap load of malli.core$macros.js re-runs.
;; ---------------------------------------------------------------------------

(deftest relink-registry!-heals-a-default-registry-stomp
  (try
    ;; Simulate the stomp: default registry loses seon's mutable layer.
    (mr/set-default-registry! (m/default-schemas))
    (is (thrown? js/Error (m/schema :seon.db/conn))
        "after the stomp, seon-registered keywords no longer resolve")
    (finally
      ;; The fn under test doubles as the cleanup — MUST heal even if
      ;; the assertion above throws, or the rest of the suite breaks.
      (is (true? (schema/relink-registry!)))))
  (is (some? (m/schema :seon.db/conn))
      "after relink-registry!, seon-registered keywords resolve again"))

;; ---------------------------------------------------------------------------
;; Defn-before-ns ordering — a fn entry whose owning ns has NO :seon.ns
;; row (or whose ns row tx-sorts later, as the live workout corpus does)
;; must still replay on a FRESH compile-state. Without ensure-target-ns!
;; cljs.js's :def-emits-var dies in the cljs compiler's emit* :the-var
;; `{:pre [(ana/ast? sym)]}` — live: `replay of fn "seon.workout/…"
;; failed: Assert failed: (ana/ast? sym)` on every boot (2026-06-10).
;; ---------------------------------------------------------------------------

(deftest replay-fn-without-ns-row-creates-its-namespace
  (async done
    (-> (repl/ensure-bootstrap!)
        (.then
          (fn [cs]
            (-> (client/open-agent-conn!)
                (.then
                  (fn [conn]
                    (binding [db/*conn* conn]
                      (-> (db/transact!
                            {:seon.db/tx-data
                             ;; fn row ONLY — deliberately no :seon.ns row for
                             ;; seon.replay.orphan (defn-before-ns shape).
                             [{:seon.fn/sym "seon.replay.orphan/orphan-fn"
                               :seon.fn/source "(defn orphan-fn [n] (+ n 5))"
                               :seon.fn/arglists "([n])"
                               :seon.fn/doc ""
                               :seon.fn/private? false}]})
                          (.then
                            (fn [_]
                              (client/replay-program-graph!
                                {:conn conn :compile-state cs
                                 :agent-id "resume-replay-test"})))
                          (.then
                            (fn [stats]
                              (is (= 0 (:seon.client/replay-n-fail stats))
                                  "fn replays into an auto-created ns — no ana/ast? assert")
                              (seval/eval cs "(seon.replay.orphan/orphan-fn 37)"
                                          {:ns 'cljs.user :analyze-deps? false})))
                          (.then
                            (fn [r]
                              (is (:ok r) "replayed orphan fn is callable")
                              (is (= 42 (:value r)) "(orphan-fn 37) => 42"))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
