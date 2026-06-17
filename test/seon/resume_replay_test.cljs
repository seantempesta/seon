(ns seon.resume-replay-test
  "Step 4 of coherent-bootstrap-indexing-2026-06-08: resume coverage + cleanup.

   On resume/boot the CORE corpus is rebuilt from real source by
   `seon.client/index-core!` (no replay — its rows are compiled fns;
   re-evaling `(defn ^:async transact! …)` would be wrong). The agent's OWN
   corpus (fns / tests / schemas / nses under `my.agent.<id>`) IS replayed:
   `replay-program-graph!` re-evals each persisted `:source` so the agent's
   definitions come back after a pod restart.

   These tests pin the Step 4 discriminator + cleanup:

     - `query-program-graph-entries` EXCLUDES core rows (owning ns in
       `core-ns-kws` = #{:seon.db :seon.schema :seon.test.runner}) — even
       when a core `:seon.fn` carries a `,,,` stub source, it is SKIPPED.
     - It INCLUDES agent-authored `:seon.fn` AND `:seon.test` rows (and agent
       `:seon.ns`), in tx order.
     - A full `replay-program-graph!` re-evals the agent fn + agent test source
       into the bootstrap compile-state (agent-fn callable, test `(def …)`
       reconstituted) and counts ONLY the agent rows — core rows are not
       replayed and contribute no failures.

   The discriminator is ns membership (derived from `core-vars`, the same
   source `index-core!` writes from) — NOT the `:seon.db/origin
   :core-seed` tx-meta, which can be absent on a re-asserted / older row.
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
;; Seed — a fresh conn with BOTH core rows (must be skipped) and
;; agent-authored rows (must replay).
;;
;;   :seon.db          — core ns + a `transact!` fn carrying a `,,,` STUB
;;                       source (the exact thing the old curated path wrote);
;;                       must be SKIPPED so the compiled fn is never shadowed.
;;   :seon.test.runner — core ns + a `run!` :seon.test row; must be SKIPPED.
;;   :my.agent.t1    — agent ns + an `agent-fn` fn + a `my-test` :seon.test
;;                       row whose source `(def replay-marker 42)` EVALS; both
;;                       must REPLAY.
;; ---------------------------------------------------------------------------

(def ^:private seed-tx
  [{:seon.ns/name :seon.db :seon.ns/source "(ns seon.db)"}
   {:seon.ns/name :seon.test.runner :seon.ns/source "(ns seon.test.runner)"}
   {:seon.ns/name :my.agent.t1 :seon.ns/source "(ns my.agent.t1)"}
   ;; CORE fn with a `,,,` stub source — must be SKIPPED on replay.
   {:seon.fn/sym "seon.db/transact!"
    :seon.fn/ns [:seon.ns/name :seon.db]
    :seon.fn/source "(defn transact! [x] ,,,)"
    :seon.fn/arglists "([x])"
    :seon.fn/doc ""
    :seon.fn/private? false}
   ;; AGENT fn — must REPLAY (re-eval into the agent ns).
   {:seon.fn/sym "my.agent.t1/agent-fn"
    :seon.fn/ns [:seon.ns/name :my.agent.t1]
    :seon.fn/source "(defn agent-fn [n] (* n 2))"
    :seon.fn/arglists "([n])"
    :seon.fn/doc ""
    :seon.fn/private? false}
   ;; AGENT test row — must REPLAY its source.
   {:seon.test/sym "my.agent.t1/my-test"
    :seon.test/ns [:seon.ns/name :my.agent.t1]
    :seon.test/source "(def replay-marker 42)"}
   ;; CORE test row — must be SKIPPED.
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

(deftest replay-set-includes-agent-corpus-skips-core
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (.then
              (query-entries conn)
              (fn [entries]
                (let [pairs (set (map (juxt :kind :ident) entries))]
                  ;; AGENT corpus IS in the replay set.
                  (is (contains? pairs [:ns :my.agent.t1]) "agent ns replays")
                  (is (contains? pairs [:fn "my.agent.t1/agent-fn"]) "agent fn replays")
                  (is (contains? pairs [:test "my.agent.t1/my-test"])
                      "agent :seon.test row replays its source")
                  ;; CORE corpus is NOT in the replay set — even the
                  ;; `,,,`-stubbed transact! row, which the old curated path
                  ;; would have re-evaled into a broken shadow.
                  (is (not (contains? pairs [:ns :seon.db]))
                      "core :seon.db ns is SKIPPED")
                  (is (not (contains? pairs [:fn "seon.db/transact!"]))
                      "core transact! fn is SKIPPED (even with a ,,, stub)")
                  (is (not (contains? pairs [:ns :seon.test.runner]))
                      "core :seon.test.runner ns is SKIPPED")
                  (is (not (contains? pairs [:test "seon.test.runner/run!"]))
                      "core run! :seon.test row is SKIPPED")
                  ;; The whole replay set is agent-only: exactly the 3 agent rows.
                  (is (= 3 (count entries)) "only the 3 agent rows survive the filter"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Strict persistence (#7) — only a literal single `(defn …)` replays as a
;; :seon.fn row. Classify on the form HEAD.
;;
;; The old #29 effectful-bare-def heuristic is GONE: under strict-head gating
;; a bare `(def x (message! …))` is never persisted, so there is nothing to
;; scan. The replay-set filter now drops ANY :fn row whose source isn't a
;; clean defn — that catches the deployed-store ghost-message poison AND
;; pure-value defs in ONE rule, no effectful scan.
;; ---------------------------------------------------------------------------

(def ^:private ghost-seed-tx
  [{:seon.ns/name :my.agent.g1 :seon.ns/source "(ns my.agent.g1)"}
   ;; POISON: bare def whose init calls an effectful core fn. Historical
   ;; deployed-store row (the tee no longer creates these). MUST be dropped
   ;; from the replay set so it never re-fires.
   {:seon.fn/sym "my.agent.g1/virtue-eval"
    :seon.fn/ns [:seon.ns/name :my.agent.g1]
    :seon.fn/source "(def virtue-eval (seon.agent/message! {:to :user :text \"Running virtue eval…\"}))"
    :seon.fn/fn-var? false
    :seon.fn/arglists "nil" :seon.fn/doc "" :seon.fn/private? false}
   ;; A real `(defn …)` — clean defining form. MUST replay.
   {:seon.fn/sym "my.agent.g1/notify"
    :seon.fn/ns [:seon.ns/name :my.agent.g1]
    :seon.fn/source "(defn notify [t] (seon.agent/message! {:to :user :text t}))"
    :seon.fn/fn-var? true
    :seon.fn/arglists "([t])" :seon.fn/doc "" :seon.fn/private? false}
   ;; A pure value def — POLICY FLIP (#7): a `(def …)` is runtime state, not
   ;; a function. Now DROPPED from the replay set (was kept under #29).
   {:seon.fn/sym "my.agent.g1/answer"
    :seon.fn/ns [:seon.ns/name :my.agent.g1]
    :seon.fn/source "(def answer 42)"
    :seon.fn/fn-var? false
    :seon.fn/arglists "nil" :seon.fn/doc "" :seon.fn/private? false}])

(deftest defn-form-classifier-is-precise
  ;; The strict-head gate used at both the tee and replay sites.
  (is (true?  (seval/defn-form? "(defn f [a] a)")))
  (is (true?  (seval/defn-form? "(defn- g [a] a)")))
  (is (true?  (seval/defn-form? "(defn notify [t] (seon.agent/message! {:to :user :text t}))"))
      "a defn whose body calls message! still persists — it's a defn")
  (is (false? (seval/defn-form? "(def y 42)")))
  (is (false? (seval/defn-form? "(def z (fn [a] a))")))
  (is (false? (seval/defn-form? "(def r (agent/reply! {:text \"x\"}))")))
  (is (false? (seval/defn-form? "(do (defn a []) (defn b []))"))
      "a do-wrapped defn is not a single literal defn")
  (is (false? (seval/defn-form? "(defn a []) (defn b [])"))
      "multi-form source is not a single defn")
  (is (false? (seval/defn-form? "(deftest t (is true))"))
      "head is deftest — persists via its own path, not as a defn row")
  (is (false? (seval/defn-form? "")))
  (is (false? (seval/defn-form? "(("))
      "unreadable source classifies false (fail-closed)"))

(deftest replay-set-keeps-only-defns
  (async done
    (-> (client/open-agent-conn!)
        (.then (fn [conn]
                 (binding [db/*conn* conn]
                   (-> (db/transact! {:seon.db/tx-data ghost-seed-tx})
                       (.then (fn [_] (query-entries conn)))))))
        (.then
          (fn [entries]
            (let [fn-idents (set (->> entries (filter #(= :fn (:kind %))) (map :ident)))]
              (is (not (contains? fn-idents "my.agent.g1/virtue-eval"))
                  "effectful bare def is DROPPED from the replay set")
              (is (not (contains? fn-idents "my.agent.g1/answer"))
                  "pure value def is now DROPPED (strict-policy flip)")
              (is (contains? fn-idents "my.agent.g1/notify")
                  "a clean `(defn …)` row is KEPT"))))
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
                        ;; ONLY the 3 agent rows replay; core rows skipped.
                        (is (= 3 (:seon.client/replay-n-total stats))
                            "exactly the 3 agent rows are in the replay set")
                        (is (= 0 (:seon.client/replay-n-fail stats))
                            "no replay failures")))
                    (.then
                      (fn [_]
                        (seval/eval cs "(my.agent.t1/agent-fn 21)"
                                    {:ns 'cljs.user :analyze-deps? false})))
                    (.then
                      (fn [r]
                        (is (:ok r) "agent-fn replayed without error")
                        (is (= 42 (:value r))
                            "replayed agent-fn is callable: (agent-fn 21) => 42")
                        (seval/eval cs "(+ replay-marker 8)"
                                    {:ns 'my.agent.t1 :analyze-deps? false})))
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
;; :my.workout/* replay failure logged as `failed: ERROR`).
;; ---------------------------------------------------------------------------

(deftest error-chain-message-surfaces-the-real-defect
  (let [err {:seon.error/message "ERROR"
             :seon.error/stack   "Error: ERROR\n    at compile-loop"
             :seon.error/cause
             {:seon.error/message "schema/register! :my.workout/date: bad form"
              :seon.error/stack   "Error: schema/register!…\n    at assert_compilable"
              :seon.error/cause
              {:seon.error/message ":malli.core/invalid-schema"}}}]
    (is (= (str "ERROR <- schema/register! :my.workout/date: bad form"
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
;; `{:pre [(ana/ast? sym)]}` — live: `replay of fn "my.workout/…"
;; failed: Assert failed: (ana/ast? sym)` on every boot (2026-06-10).
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Reordered-ns-row replay — the live resume bug (P7, 2026-06-10).
;; The tee re-asserts `:seon.fn/ns {:seon.ns/name <kw>}` on EVERY define;
;; the nested-map upsert bumps the owning ns row's :seon.ns/name datom tx.
;; Sequence: define fn in ns A → author a deftest (requires cljs.test)
;; → redefine the fn twice (each redefine drags A's ns-row tx PAST the
;; deftest's tx). Plain tx-order replay then evals the deftest BEFORE the
;; `(ns A (:require [cljs.test]))` row — ensure-target-ns! only creates a
;; BARE ns, so the deftest dies with `undeclared cljs.test/deftest`
;; (live: deftest tx …937 replayed before its ns row tx …939). The fix:
;; replay ALL :ns entries first, then def-shaped entries, each tx-ordered.
;; ---------------------------------------------------------------------------

(deftest replay-ns-rows-first-despite-upsert-bumped-tx
  (async done
    (-> (repl/ensure-bootstrap!)
        (.then
          (fn [cs]
            (-> (client/open-agent-conn!)
                (.then
                  (fn [conn]
                    (let [tx!    (fn [data]
                                   (binding [db/*conn* conn]
                                     (db/transact! {:seon.db/tx-data data})))
                          fn-row (fn [src]
                                   [{:seon.fn/sym "seon.replay.reorder/f1"
                                     :seon.fn/ns {:seon.ns/name :seon.replay.reorder}
                                     :seon.fn/source src
                                     :seon.fn/arglists "([n])"
                                     :seon.fn/doc ""
                                     :seon.fn/private? false}])]
                      ;; Each step in its OWN tx — the tx spread is the bug.
                      (-> (tx! [{:seon.ns/name :seon.replay.reorder
                                 :seon.ns/source "(ns seon.replay.reorder (:require [cljs.test]))"}])
                          (.then (fn [_] (tx! (fn-row "(defn f1 [n] (* n 2))"))))
                          ;; deftest authored BETWEEN the defines.
                          (.then (fn [_]
                                   (tx! [{:seon.test/sym "seon.replay.reorder/reorder-test"
                                          :seon.test/ns {:seon.ns/name :seon.replay.reorder}
                                          ;; Body references f1 — the SECOND live failure shape:
                                          ;; the redefines bump f1's OWN row tx past the deftest's,
                                          ;; so the deftest replays first and compiles against an
                                          ;; undeclared f1. The replay retry pass heals it (live
                                          ;; 2026-06-10: my.workout/add-workout-test vs the
                                          ;; twice-redefined add-workout!).
                                          :seon.test/source "(cljs.test/deftest reorder-test (cljs.test/is (= 8 (f1 2))))"}])))
                          ;; Two redefines — each nested :seon.fn/ns upsert
                          ;; bumps the ns row's tx past the deftest's.
                          (.then (fn [_] (tx! (fn-row "(defn f1 [n] (* n 3))"))))
                          (.then (fn [_] (tx! (fn-row "(defn f1 [n] (* n 4))"))))
                          ;; The precondition that makes this test honest: the
                          ;; ns row's tx must now sort AFTER the deftest's tx.
                          (.then (fn [_] (query-entries conn)))
                          (.then
                            (fn [entries]
                              (let [tx-of (fn [k i]
                                            (some #(when (and (= k (:kind %))
                                                              (= i (:ident %)))
                                                     (:tx %))
                                                  entries))]
                                (is (> (tx-of :ns :seon.replay.reorder)
                                       (tx-of :test "seon.replay.reorder/reorder-test"))
                                    "PRECONDITION: redefines bumped the ns row's tx past the deftest's")
                                (is (= :ns (:kind (first entries)))
                                    "ns entries replay FIRST despite their later tx"))
                              (client/replay-program-graph!
                                {:conn conn :compile-state cs
                                 :agent-id "resume-replay-test"})))
                          (.then
                            (fn [stats]
                              (is (= 3 (:seon.client/replay-n-total stats))
                                  "ns + fn + deftest are the replay set")
                              (is (= 0 (:seon.client/replay-n-fail stats))
                                  "deftest replays AFTER its requiring ns form — no undeclared cljs.test/deftest")
                              (seval/eval cs "[(seon.replay.reorder/f1 10) (some? seon.replay.reorder/reorder-test)]"
                                          {:ns 'cljs.user :analyze-deps? false})))
                          (.then
                            (fn [r]
                              (is (:ok r) "replayed corpus is live")
                              (is (= [40 true] (:value r))
                                  "latest f1 redefine won; deftest var reconstituted"))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Downstream bug #14 (2026-06-11) — agent corpus whose (ns …) row REQUIRES a
;; host-bundled, store-indexed ns (my.kb — the move the prompt teaches). Before
;; the guarded-load host-bundle fallback, the ns row's replay died with
;; `Could not require my.kb <- ns my.kb not available` (B4 inside replay);
;; the half-failed ns eval left an ANALYZER ENTRY but no JS ns object, so
;; ensure-target-ns! skipped its heal and every def in the ns failed with
;; `Cannot set/read properties of undefined` on BOTH passes
;; (logs/pod-events.log, agents UPE-2606101815 / vGq-2606111337) — agent fns
;; gone after every pod restart until re-defined by hand.
;; ---------------------------------------------------------------------------

(deftest replay-ns-row-with-host-bundled-require-succeeds
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
                             [{:seon.ns/name :seon.replay.kbreq
                               :seon.ns/source
                               "(ns seon.replay.kbreq (:require [my.kb :as kb]))"}
                              {:seon.fn/sym "seon.replay.kbreq/kb-fn"
                               :seon.fn/ns {:seon.ns/name :seon.replay.kbreq}
                               :seon.fn/source "(defn kb-fn [n] (+ n 7))"
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
                                  (str "ns row requiring my.kb replays clean "
                                       "(B4-in-replay fixed) — " (pr-str stats)))
                              (seval/eval cs "(seon.replay.kbreq/kb-fn 35)"
                                          {:ns 'cljs.user :analyze-deps? false})))
                          (.then
                            (fn [r]
                              (is (:ok r) "fn in the requiring ns is callable")
                              (is (= 42 (:value r)) "(kb-fn 35) => 42"))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest replay-heals-analyzer-entry-without-live-ns-object
  ;; The #14 cascade mechanism in isolation: an analyzer entry EXISTS
  ;; (a half-failed ns eval registers one) but the munged JS ns object
  ;; was never created. The old ensure-target-ns! trusted the analyzer
  ;; entry alone and skipped the bare-(ns) heal — every def then died
  ;; writing onto `undefined`. Both probes must hold before skipping.
  (async done
    (-> (repl/ensure-bootstrap!)
        (.then
          (fn [cs]
            ;; Poison: analyzer knows the ns; globalThis does not.
            (swap! cs assoc-in
                   [:cljs.analyzer/namespaces 'seon.replay.poisoned :name]
                   'seon.replay.poisoned)
            (is (false? (seval/ns-live-on-globalthis? 'seon.replay.poisoned))
                "PRECONDITION: no JS object for the poisoned ns")
            (-> (client/open-agent-conn!)
                (.then
                  (fn [conn]
                    (binding [db/*conn* conn]
                      (-> (db/transact!
                            {:seon.db/tx-data
                             [{:seon.fn/sym "seon.replay.poisoned/healed-fn"
                               :seon.fn/source "(defn healed-fn [n] (* n 6))"
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
                                  "fn replays into the healed ns — no `Cannot set properties of undefined`")
                              (seval/eval cs "(seon.replay.poisoned/healed-fn 7)"
                                          {:ns 'cljs.user :analyze-deps? false})))
                          (.then
                            (fn [r]
                              (is (:ok r) "healed fn is callable")
                              (is (= 42 (:value r)) "(healed-fn 7) => 42"))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

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
