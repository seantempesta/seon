(ns seon.resume-replay-test
  "Resume coverage for the DB-is-the-running-system spine
   (db-is-the-running-system-2026-06-17 PRD, Phase 2).

   On boot the COMPILED package (kernel + core + third-party) is already in
   the runtime — its `:seon.ns`/`:seon.fn` rows are DISPLAY-only and are NOT
   loaded. Only the agent-authored DB LAYER is loaded:
   `replay-program-graph!` queries the agent ns set (every `:seon.ns/name`
   row minus `(core-ns-set)`), topo-sorts by the STORED `:seon.ns/require-edges`,
   and for each ns evals its reconstituted whole source
   (`seon.eval/reconstitute-ns-source` — ns form + every current
   `:seon.fn`/`:seon.schema`/`:seon.test` source). cljs.js's own load-fn
   (the DB branch of `seon.eval/guarded-load`) supplies any transitive agent
   require's source on demand, in dependency order, with cycle detection +
   load-once. There is NO per-definition replay loop, NO tx-order sort, NO
   2-pass retry, NO `ensure-target-ns!`.

   Tests open a FRESH `:memory` conn (via `seon.client/open-agent-conn!`, the
   same boot helper the pod uses) and seed rows directly — nothing here
   touches the live agent.

   Run interactively via MCP eval:
     (require 'seon.resume-replay-test :reload)
     (cljs.test/run-tests 'seon.resume-replay-test)"
  (:require
    [cljs.test :as t :refer [deftest is testing async]]
    [clojure.string :as str]
    [malli.core :as m]
    [malli.registry :as mr]
    [seon.client :as client]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.repl :as repl]
    [seon.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Seed — a fresh conn with BOTH core rows (must NOT be loaded — they're
;; compiled, display-only) and agent-authored rows (must load).
;;
;;   :seon.db          — core ns + a `transact!` fn carrying a `,,,` STUB
;;                       source; must be EXCLUDED from the agent load set so
;;                       the compiled fn is never shadowed.
;;   :seon.test.runner — core ns + a `run!` :seon.test row; must be EXCLUDED.
;;   :my.agent.t1      — agent ns + an `agent-fn` fn + a `my-test` deftest
;;                       row; both must LOAD (reconstituted into the ns).
;; ---------------------------------------------------------------------------

(def ^:private seed-tx
  [{:seon.ns/name :seon.db :seon.ns/source "(ns seon.db)"}
   {:seon.ns/name :seon.test.runner :seon.ns/source "(ns seon.test.runner)"}
   {:seon.ns/name :my.agent.t1
    :seon.ns/source "(ns my.agent.t1 (:require [cljs.test]))"}
   ;; CORE fn with a `,,,` stub source — must NOT be loaded.
   {:seon.fn/sym "seon.db/transact!"
    :seon.fn/ns [:seon.ns/name :seon.db]
    :seon.fn/source "(defn transact! [x] ,,,)"
    :seon.fn/arglists "([x])"
    :seon.fn/doc ""
    :seon.fn/private? false}
   ;; AGENT fn — must LOAD (re-eval into the agent ns).
   {:seon.fn/sym "my.agent.t1/agent-fn"
    :seon.fn/ns [:seon.ns/name :my.agent.t1]
    :seon.fn/source "(defn agent-fn [n] (* n 2))"
    :seon.fn/arglists "([n])"
    :seon.fn/doc ""
    :seon.fn/private? false}
   ;; AGENT test row — its deftest reconstitutes into the ns.
   {:seon.test/sym "my.agent.t1/my-test"
    :seon.test/ns [:seon.ns/name :my.agent.t1]
    :seon.test/source "(cljs.test/deftest my-test (cljs.test/is (= 4 (agent-fn 2))))"}
   ;; CORE test row — must NOT be loaded.
   {:seon.test/sym "seon.test.runner/run!"
    :seon.test/ns [:seon.ns/name :seon.test.runner]
    :seon.test/source "(cljs.test/deftest should-not-load (cljs.test/is true))"}])

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
;; agent-ns-set — the load discriminator: agent nses, core excluded.
;; ---------------------------------------------------------------------------

(deftest agent-ns-set-includes-agent-nses-skips-core
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [agents ((deref #'client/agent-ns-set) @conn)]
              (is (contains? agents :my.agent.t1) "agent ns is in the load set")
              (is (not (contains? agents :seon.db))
                  "core :seon.db ns is EXCLUDED (compiled, display-only)")
              (is (not (contains? agents :seon.test.runner))
                  "core :seon.test.runner ns is EXCLUDED"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; reconstitute-ns-source — ns form + every current member source, deduped.
;; ---------------------------------------------------------------------------

(deftest reconstitute-ns-source-joins-ns-form-and-members
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [src (seval/reconstitute-ns-source @conn :my.agent.t1)]
              (is (str/includes? src "(ns my.agent.t1")
                  "the verbatim (ns … (:require …)) form heads the source")
              (is (str/includes? src "(:require [cljs.test])")
                  "the aliases/requires in the stored ns form are carried verbatim")
              (is (str/includes? src "(defn agent-fn")
                  "the agent fn source is concatenated")
              (is (str/includes? src "(cljs.test/deftest my-test")
                  "the agent deftest source is concatenated"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; topo-sort-nses — a dependency comes before its dependent.
;; ---------------------------------------------------------------------------

(deftest topo-sort-orders-deps-before-dependents
  (let [order ((deref #'client/topo-sort-nses)
               {:a #{:b} :b #{:c} :c #{}})]
    (is (= [:c :b :a] order) "c (leaf) first, a (root) last"))
  (let [order ((deref #'client/topo-sort-nses) {:x #{} :y #{}})]
    (is (= #{:x :y} (set order)) "independent nses both present")
    (is (= [:x :y] order) "deterministic (sorted) ordering"))
  (testing "a require cycle terminates (cljs.js errors it at eval, not here)"
    (let [order ((deref #'client/topo-sort-nses) {:p #{:q} :q #{:p}})]
      (is (= #{:p :q} (set order)) "both present; the back-edge is broken"))))

;; ---------------------------------------------------------------------------
;; defn-form? classifier — used at the tee site (#7 strict persistence).
;; ---------------------------------------------------------------------------

(deftest defn-form-classifier-is-precise
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

;; ---------------------------------------------------------------------------
;; Full load — agent fn + agent deftest reconstitute into the agent ns;
;; counts only the agent ns; core nses are not loaded.
;; ---------------------------------------------------------------------------

(deftest full-load-reconstitutes-agent-fn-and-test
  (async done
    (-> (repl/ensure-bootstrap!)
        (.then
          (fn [cs]
            (with-seeded-conn
              (fn [conn]
                (-> (client/replay-program-graph!
                      {:seon.client/conn conn :seon.client/compile-state cs :seon.client/agent-id "resume-replay-test"})
                    (.then
                      (fn [stats]
                        ;; ONLY the one agent ns loads; core nses excluded.
                        (is (= 1 (:seon.client/replay-n-total stats))
                            "exactly the agent ns is the load unit (core excluded)")
                        (is (= 0 (:seon.client/replay-n-fail stats))
                            "no load failures")))
                    (.then
                      (fn [_]
                        (seval/eval cs "(my.agent.t1/agent-fn 21)"
                                    {:seon.eval/starting-ns 'cljs.user :seon.eval/analyze-deps? false})))
                    (.then
                      (fn [r]
                        (is (:seon.eval/ok? r) "agent-fn loaded without error")
                        (is (= 42 (:seon.eval/value r))
                            "loaded agent-fn is callable: (agent-fn 21) => 42")
                        (seval/eval cs "(some? my.agent.t1/my-test)"
                                    {:seon.eval/starting-ns 'cljs.user :seon.eval/analyze-deps? false})))
                    (.then
                      (fn [r]
                        (is (:seon.eval/ok? r) "agent deftest loaded without error")
                        (is (true? (:seon.eval/value r))
                            "deftest var reconstituted into the agent ns"))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; C37 resume leg — a SOURCELESS member-bearing ns row (the agent HOME ns:
;; its requires are wired at runtime by setup-agent-ns!, which runs AFTER
;; the boot replay, so no :seon.ns/source is ever stored) reconstitutes
;; with a head SYNTHESIZED from the stored :seon.ns/require-edges. Without
;; it the unit is headless: member defns land in cljs.user and a member
;; using `::alias/kw` cannot even READ (live-caught 2026-07-03 — the first
;; ::-keyword home-ns fn to survive the C37 gate failed the whole unit's
;; replay with 'Invalid keyword: ::db/tx-data').
;; ---------------------------------------------------------------------------

(deftest sourceless-home-ns-resumes-via-synthesized-head
  (async done
    (-> (repl/ensure-bootstrap!)
        (.then
          (fn [cs]
            (-> (client/open-agent-conn!)
                (.then
                  (fn [conn]
                    (binding [db/*conn* conn]
                      (let [uniq  (str "my.agent.kwresume" (rand-int 1000000000))
                            ns-kw (keyword uniq)]
                        (-> (db/transact!
                              {:seon.db/tx-data
                               ;; NO :seon.ns/source — edges only (the home-ns
                               ;; shape the setup-agent-ns! tee writes).
                               [{:seon.ns/name ns-kw
                                 :seon.ns/require-edges
                                 [{:seon.ns.require/target :clojure.string
                                   :seon.ns.require/alias  'pstr}]}
                                {:seon.fn/sym (str uniq "/kw-resumer")
                                 :seon.fn/ns  [:seon.ns/name ns-kw]
                                 :seon.fn/source
                                 "(defn kw-resumer [m] [(::marker m) (::pstr/mk m)])"
                                 :seon.fn/arglists "([m])"
                                 :seon.fn/doc ""
                                 :seon.fn/private? false}]})
                            (.then
                              (fn [_]
                                (let [src (seval/reconstitute-ns-source @conn ns-kw)]
                                  (testing "the head is synthesized from the stored edges"
                                    (is (str/starts-with? src (str "(ns " uniq))
                                        (str "head present — got: " (subs src 0 60)))
                                    (is (str/includes? src "[clojure.string :as pstr]")
                                        "the :as alias is carried")))
                                (client/replay-program-graph!
                                  {:seon.client/conn conn
                                   :seon.client/compile-state cs
                                   :seon.client/agent-id "kw-resume-test"})))
                            (.then
                              (fn [stats]
                                (is (= 0 (:seon.client/replay-n-fail stats))
                                    (str "the ::-keyword home-ns unit loads — "
                                         (pr-str stats)))
                                (seval/eval cs
                                            (str "(" uniq "/kw-resumer"
                                                 " {" ns-kw "/marker 1"
                                                 " :clojure.string/mk 2})")
                                            {:seon.eval/starting-ns 'cljs.user
                                             :seon.eval/analyze-deps? false})))
                            (.then
                              (fn [r]
                                (is (:seon.eval/ok? r)
                                    "resumed fn is callable IN ITS OWN ns")
                                (is (= [1 2] (:seon.eval/value r))
                                    "::marker and ::pstr/mk resolved against the synthesized head")))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Fail-loud load errors — the warn must name the actual defect, not
;; cljs.js's literal "ERROR" wrapper (live incident 2026-06-10: every
;; :my.workout/* load failure logged as `failed: ERROR`).
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
;; Registry stomp guard — a foreign (mr/set-default-registry! …) is the exact
;; side effect a bootstrap load of malli.core$macros.js re-runs, severing every
;; seon-registered schema. relink-registry! installs a watch on malli's
;; registry* atom that re-asserts seon's registry SYNCHRONOUSLY inside the
;; stomping reset!, so there is no window where validation is broken.
;; ---------------------------------------------------------------------------

(deftest stomp-guard-closes-the-window
  ;; relink (re)installs the guard on the live registry* atom.
  (is (true? (schema/relink-registry!)))
  ;; Simulate the stomp the way malli.core's top-level (def default-registry …)
  ;; does it — default schemas + var-registry, no seon mutable layer.
  (mr/set-default-registry!
   (mr/composite-registry (mr/fast-registry (m/default-schemas)) (mr/var-registry)))
  ;; NO manual relink here. The watch must have already healed, in the same
  ;; reset! the stomp triggered — so resolution NEVER broke.
  (is (some? (m/schema :seon.db/conn))
      "seon-registered keywords resolve immediately after a stomp — window closed")
  (is (true? (m/validate :seon.db/id "abc1234567890a"))
      "value validation against a seon schema still works post-stomp")
  (is (false? (m/validate :seon.db/id "x"))
      "and still rejects — the real seon schema, not a default fallthrough"))

(deftest relink-registry!-restores-after-the-guard-is-removed
  ;; With the guard detached, the stomp DOES sever — proving (a) the stomp is
  ;; real and (b) relink-registry! heals it (and re-arms the guard).
  (try
    (remove-watch malli.registry/registry* :seon.schema/seon-stomp-guard)
    (mr/set-default-registry! (m/default-schemas))
    (is (thrown? js/Error (m/schema :seon.db/conn))
        "guard removed: the stomp severs seon-registered keywords")
    (finally
      ;; relink heals AND re-installs the guard — MUST run even if the assert
      ;; above throws, or the rest of the suite breaks.
      (is (true? (schema/relink-registry!)))))
  (is (some? (m/schema :seon.db/conn))
      "after relink-registry!, seon-registered keywords resolve again"))

;; ---------------------------------------------------------------------------
;; Downstream bug #14 (2026-06-11) — agent corpus whose (ns …) row REQUIRES a
;; host-bundled, store-indexed ns (my.kb — the move the prompt teaches). The
;; reconstituted ns source carries the `(:require [my.kb :as kb])` form
;; verbatim; cljs.js's load-fn (guarded-load) answers my.kb via the
;; globalThis branch (its JS is compiled into the host bundle), so the agent
;; ns loads clean and its fns survive a pod restart.
;; ---------------------------------------------------------------------------

(deftest load-ns-with-host-bundled-require-succeeds
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
                                {:seon.client/conn conn :seon.client/compile-state cs
                                 :seon.client/agent-id "resume-replay-test"})))
                          (.then
                            (fn [stats]
                              (is (= 0 (:seon.client/replay-n-fail stats))
                                  (str "ns row requiring my.kb loads clean "
                                       "(host-bundle load-fn branch) — " (pr-str stats)))
                              (seval/eval cs "(seon.replay.kbreq/kb-fn 35)"
                                          {:seon.eval/starting-ns 'cljs.user :seon.eval/analyze-deps? false})))
                          (.then
                            (fn [r]
                              (is (:seon.eval/ok? r) "fn in the requiring ns is callable")
                              (is (= 42 (:seon.eval/value r)) "(kb-fn 35) => 42"))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Two-ns agent dependency chain — the spine. An agent ns A requires agent
;; ns B (with an `:as b` alias); both load from the DB, topo-ordered, and
;; B's fn is callable through A. This is the cross-ns dep edge the stored
;; `:seon.ns/require-edges` orders and the DB load-fn satisfies.
;; ---------------------------------------------------------------------------

(deftest load-two-ns-agent-dependency-chain
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
                             [{:seon.ns/name :seon.replay.chainb
                               :seon.ns/source "(ns seon.replay.chainb)"}
                              {:seon.fn/sym "seon.replay.chainb/bv"
                               :seon.fn/ns {:seon.ns/name :seon.replay.chainb}
                               :seon.fn/source "(defn bv [] 7)"
                               :seon.fn/arglists "([])" :seon.fn/doc "" :seon.fn/private? false}
                              {:seon.ns/name :seon.replay.chaina
                               :seon.ns/source "(ns seon.replay.chaina (:require [seon.replay.chainb :as b]))"
                               :seon.ns/require-edges
                               [{:seon.ns.require/target :seon.replay.chainb
                                 :seon.ns.require/alias  'b}]}
                              {:seon.fn/sym "seon.replay.chaina/av"
                               :seon.fn/ns {:seon.ns/name :seon.replay.chaina}
                               :seon.fn/source "(defn av [] (b/bv))"
                               :seon.fn/arglists "([])" :seon.fn/doc "" :seon.fn/private? false}]})
                          (.then
                            (fn [_]
                              (client/replay-program-graph!
                                {:seon.client/conn conn :seon.client/compile-state cs
                                 :seon.client/agent-id "resume-replay-test"})))
                          (.then
                            (fn [stats]
                              (is (= 2 (:seon.client/replay-n-total stats))
                                  "both agent nses are load units")
                              (is (= 0 (:seon.client/replay-n-fail stats))
                                  (str "topo-ordered chain loads clean — " (pr-str stats)))
                              (seval/eval cs "(seon.replay.chaina/av)"
                                          {:seon.eval/starting-ns 'cljs.user :seon.eval/analyze-deps? false})))
                          (.then
                            (fn [r]
                              (is (:seon.eval/ok? r) "cross-ns call resolves")
                              (is (= 7 (:seon.eval/value r))
                                  "av -> b/bv via the :as alias => 7"))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
