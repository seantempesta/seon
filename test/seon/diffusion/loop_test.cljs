(ns seon.diffusion.loop-test
  "Offline proof for the FULL buzzsaw control LOOP (`seon.diffusion.loop`) — NO
   GPU, NO embeddings. The legs + the unified `refine` dispatcher are proven
   elsewhere; this proves the ONE thing they don't: the orchestration
   refine → apply → re-refine → converge (or give up).

   Three proofs over a seeded :memory program graph (two real fns, raw datahike
   schema so the test is self-contained — same fixture as oracle-test):

     (a) CONVERGENCE — a canvas with BOTH a hallucinated symbol AND a syntax
         error drives renoise-spans → 0 and injections → 0 and reports CONVERGED;
         the per-iteration trace SHRINKS.
     (b) DETECTION — a clean canvas converges at iteration 0 and STOPS (never
         applies, never iterates a clean canvas).
     (c) GIVE-UP / TERMINATION — an unfixable canvas (the fixture has no fill)
         terminates with GIVE-UP and never exceeds the K-budget; a direct policy
         check proves the budget backstop.

   Run interactively via MCP eval:
     (require 'seon.diffusion.loop-test :reload)
     (cljs.test/run-tests 'seon.diffusion.loop-test)"
  (:require
    [cljs.test :as t :refer [deftest is testing async]]
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.diffusion.loop :as loop]
    [seon.diffusion.oracle :as oracle]
    [seon.diffusion.retrieval :as retrieval]))

;; ---------------------------------------------------------------------------
;; A seeded :memory program graph — two real fns (raw datahike schema, so the
;; test is self-contained). Returns a Promise of the db VALUE refine reads.
;; ---------------------------------------------------------------------------

(def ^:private fn-schema
  [{:db/ident :seon.fn/sym      :db/cardinality :db.cardinality/one
    :db/valueType :db.type/string :db/unique :db.unique/identity}
   {:db/ident :seon.fn/arglists :db/cardinality :db.cardinality/one :db/valueType :db.type/string}
   {:db/ident :seon.fn/doc      :db/cardinality :db.cardinality/one :db/valueType :db.type/string}
   {:db/ident :seon.fn/spec     :db/cardinality :db.cardinality/one :db/valueType :db.type/string}
   {:db/ident :seon.fn/source   :db/cardinality :db.cardinality/one :db/valueType :db.type/string}])

(def ^:private fn-rows
  [{:seon.fn/sym "seon.db/transact!"
    :seon.fn/arglists "([& call-args])"
    :seon.fn/doc "Commit tx-data. Two call shapes: map-in or positional."
    :seon.fn/spec "[:=> [:cat :seon.db/transact-request] :seon.db/transact-response]"
    :seon.fn/source "(defn transact! [& call-args] …)"}
   {:seon.fn/sym "seon.db/query"
    :seon.fn/arglists "([& args])"
    :seon.fn/doc "Run a Datalog query."
    :seon.fn/source "(defn query [& args] …)"}])

(defn- fresh-db []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? false}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (d/transact! conn fn-schema)
                     (.then (fn [_] (d/transact! conn fn-rows)))
                     (.then (fn [_] @conn))))))))

(defn- with-db [f done]
  (-> (fresh-db)
      (.then f)
      (.catch (fn [e] (is false (str "test chain threw — " e))))
      (.then (fn [_] (done)))))

(defn- ren-count [step] (count (::oracle/renoise-spans (::loop/control-set step))))
(defn- inj-count [step] (count (::oracle/injections (::loop/control-set step))))

;; ---------------------------------------------------------------------------
;; (a) CONVERGENCE — a degraded canvas with a hallucinated call (db/transct! →
;; db/transact!) AND a broken trailing form. The fixture's fill for the broken
;; form itself introduces a SECOND hallucination (db/quer → db/query), so the
;; loop must take THREE iterations: fix-both-tier-0 → fix-the-revealed-symbol →
;; clean. The trace shrinks renoise 1→0→0 and injections 1→1→0.
;; ---------------------------------------------------------------------------

(def ^:private degraded
  (str "(ns my.work (:require [seon.db :as db]))\n"  ; clean clamp
       "(defn good [x] (inc x))\n"                    ; clean clamp
       "(db/transct! {:seon.db/tx-data []})\n"        ; hallucination → injection
       "(defn broken [x"))                            ; broken syntax → renoise

(deftest converges-from-both-error-kinds
  (async done
    (with-db
      (fn [db]
        ;; Discover the broken form's EXACT source so the fixture key is faithful,
        ;; then map it to a fill that reveals one more hallucinated symbol.
        (let [cs0        (oracle/refine {::oracle/canvas-text degraded ::oracle/db db})
              broken-src (::oracle/source (first (::oracle/renoise-spans cs0)))
              fills      {broken-src "(defn broken [x] (db/quer x))"}
              {::loop/keys [trace verdict reason iterations]}
              (loop/dry-run {::loop/canvas-text degraded
                             ::loop/fills fills
                             ::loop/k-budget 8
                             ::loop/db db})
              last-canvas (::loop/canvas-text (last trace))]

          (testing "the broken form was detected as a renoise span up front"
            (is (str/includes? broken-src "(defn broken")))

          (testing "the loop reports CONVERGED"
            (is (= :converged verdict))
            (is (= :clean reason)))

          (testing "convergence took 3 iterations (two error tiers + a clean read)"
            (is (= 3 iterations))
            (is (= 3 (count trace))))

          (testing "the per-iteration trace SHRINKS errors to zero"
            (is (= [1 0 0] (mapv ren-count trace)) "renoise spans: 1 → 0 → 0")
            (is (= [1 1 0] (mapv inj-count trace)) "injections: 1 → 1 → 0"))

          (testing "every step refines the canvas recorded for it (basis recomputed)"
            (doseq [step trace]
              (is (= (::loop/control-set step)
                     (oracle/refine {::oracle/canvas-text (::loop/canvas-text step)
                                     ::oracle/db db})))))

          (testing "the CONVERGED canvas holds the corrected symbols + the clamped good forms"
            (is (str/includes? last-canvas "db/transact!"))
            (is (str/includes? last-canvas "db/query"))
            (is (not (str/includes? last-canvas "db/transct!")))
            (is (not (str/includes? last-canvas "db/quer ")))
            (is (str/includes? last-canvas "(defn good [x] (inc x))"))     ; clamp held verbatim
            (is (str/includes? last-canvas "(ns my.work")))))             ; clamp held verbatim
      done)))

;; ---------------------------------------------------------------------------
;; (b) DETECTION — a clean canvas converges immediately and does NOT iterate.
;; ---------------------------------------------------------------------------

(def ^:private clean-canvas
  (str "(ns my.work (:require [seon.db :as db]))\n"
       "(defn good [x] (db/query [:find '?e :where ['?e :seon.fn/sym]]))"))

(deftest stops-at-convergence-clean-canvas
  (async done
    (with-db
      (fn [db]
        (let [{::loop/keys [trace verdict reason iterations]}
              (loop/dry-run {::loop/canvas-text clean-canvas
                             ::loop/fills {}
                             ::loop/k-budget 8
                             ::loop/db db})]
          (testing "converges on the first read with no iteration"
            (is (= :converged verdict))
            (is (= :clean reason))
            (is (= 1 iterations))
            (is (= 1 (count trace))))
          (testing "the clean canvas is returned UNCHANGED (no apply ran)"
            (is (= clean-canvas (::loop/canvas-text (first trace))))
            (is (zero? (ren-count (first trace))))
            (is (zero? (inj-count (first trace)))))))
      done)))

;; ---------------------------------------------------------------------------
;; (c) GIVE-UP / TERMINATION — an unfixable canvas (the fixture has no fill for
;; its broken form) must TERMINATE, not spin. The apply leaves the canvas
;; unchanged, so the control set repeats and the no-progress rule fires. A
;; second, direct policy check proves the K-budget backstop independently.
;; ---------------------------------------------------------------------------

(def ^:private unfixable "(defn nope [x")   ; trailing unbalanced — no fill provided

(deftest gives-up-on-unfixable-canvas-no-infinite-loop
  (async done
    (with-db
      (fn [db]
        (let [{::loop/keys [trace verdict reason iterations]}
              (loop/dry-run {::loop/canvas-text unfixable
                             ::loop/fills {}                  ; NO fill — genuinely unfixable
                             ::loop/k-budget 8
                             ::loop/db db})]
          (testing "the loop TERMINATES with GIVE-UP — no infinite loop"
            (is (= :give-up verdict))
            (is (= :no-progress reason)))
          (testing "it never exceeds the K-budget (hard termination bound)"
            (is (<= iterations 8)))
          (testing "the unfixable canvas is unchanged across every step"
            (is (every? #(= unfixable (::loop/canvas-text %)) trace))
            (is (every? #(pos? (ren-count %)) trace)))))
      done)))

;; The K-budget BACKSTOP, proven directly on the pure policy fn: even with
;; ongoing progress (a different prev signature) an exhausted budget gives up.
(deftest policy-budget-backstop
  (let [cs   {::oracle/clamps []
              ::oracle/renoise-spans [{::oracle/span [0 5]
                                       ::oracle/error-kind :eof
                                       ::oracle/source "(abc "}]
              ::oracle/injections []
              ::oracle/legs [:parse :retrieve]}
        prev (assoc-in cs [::oracle/renoise-spans 0 ::oracle/span] [0 6])  ; DIFFERENT → progress
        out  (loop/checkpoint-policy {::loop/control-set cs
                                      ::loop/iteration 5
                                      ::loop/k-budget 5
                                      ::loop/prev-control-set prev})]
    (testing "budget exhausted (iteration ≥ k-budget) ⇒ GIVE-UP even with progress"
      (is (= :give-up (::loop/verdict out)))
      (is (= :budget-exhausted (::loop/reason out))))
    (testing "a clean control set ⇒ CONVERGED regardless of budget"
      (let [clean {::oracle/clamps [] ::oracle/renoise-spans []
                   ::oracle/injections [] ::oracle/legs [:parse :retrieve]}]
        (is (= :converged (::loop/verdict
                            (loop/checkpoint-policy {::loop/control-set clean
                                                     ::loop/iteration 99
                                                     ::loop/k-budget 5}))))))))
