(ns seon.eval.repl-forms-test
  "REAL-REPL semantics (owner rulings 2026-07-10): `in-ns` is THE
   movement form (state-preserving; a fresh name is created with the
   toolkit requires); `(ns …)` re-eval REPLACES the require set (edges
   heal, no orphans); a bare top-level `(require …)` loads now AND
   persists into the stored declaration (durable-by-default — resume
   replays it); `(alias …)` records a require alias (error-as-value
   when the target isn't loaded); `:as-alias` aliases without loading;
   redefinition IS update; `ns-unmap` removes the live var + retracts
   the `:seon.fn` row; `ns-unalias` drops an alias everywhere.

   Every test opens a FRESH full-schema `:memory` conn and drives the
   REAL bootstrap compile-state via `parse-forms` → `eval-batch!` —
   the exact agent path. Nothing here touches the live agent conn.

   Run interactively (single ns, NEVER overlapping in the live pod):
     (require 'seon.eval.repl-forms-test :reload)
     (cljs.test/run-tests 'seon.eval.repl-forms-test)"
  (:require
    [cljs.test :as t :refer [deftest is testing async]]
    [clojure.string :as str]
    [datahike.api :as d]
    [goog.object :as gobj]
    [seon.agent]                          ; :seon.eval / :seon.agent.turn registrations
    [seon.client :as client]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.eval :as seval]
    [seon.repl :as repl]
    [seon.repl.internal :as internal]))

;; The compile-state + globalThis are PROCESS-SHARED — a re-run in a live
;; pod would otherwise see last run's probe.rv.* nses as EXISTING (the
;; in-ns fresh-create path is state-dependent). Scrub them up front so
;; every run starts from genuinely fresh names.
(defn- scrub-probe-rv!
  []
  ;; A nil compile-state (an ISOLATED --test= run before any
  ;; ensure-bootstrap!) has no prior probe.rv.* state to scrub — no-op
  ;; (hermetic-fixture rule, C35 class).
  (when-some [cs @repl/!compile-state]
    (swap! cs update :cljs.analyzer/namespaces
           (fn [m]
             (into {}
                   (remove (fn [[k _]] (or (str/starts-with? (str k) "probe.rv")
                                           (str/starts-with? (str k) "my.rvx"))))
                   m))))
  (when-some [p (gobj/get js/globalThis "probe")]
    (gobj/remove p "rv"))
  (when-some [p (gobj/get js/globalThis "my")]
    (gobj/remove p "rvxuse")))

(t/use-fixtures :once
  {:before (fn [] (scrub-probe-rv!))})

;; ---------------------------------------------------------------------------
;; Fixtures — mirrors repair_batch_test: root `set!` of db/*conn* (a
;; `binding` does not survive await boundaries), fixed valid turn identity.
;; ---------------------------------------------------------------------------

(def ^:dynamic ^:private fixture-agent-id nil)
(def ^:dynamic ^:private fixture-turn-id nil)

(defn- with-conn
  "Open a fresh full-schema :memory conn, `set!` it as the ROOT
   `db/*conn*`, run `body` (0-arg, may return a Promise), restore after."
  [body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (-> (db.id/allocate!
                     {::db.id/allocations
                      [{::db.id/key ::fixture-agent
                        ::db.id/identity-attr :seon.agent/id}
                       {::db.id/key ::fixture-turn
                        ::db.id/identity-attr :seon.agent.turn/id}]
                      ::db.id/transaction-builder
                      (fn [ids]
                        {:seon.db/tx-data
                         [{:seon.agent/id (::fixture-agent ids)}
                          {:seon.agent.turn/id (::fixture-turn ids)}]})
                      :seon.db/conn conn})
                   (.then
                     (fn [env]
                       (set! fixture-agent-id
                             (get-in env [::db.id/ids ::fixture-agent]))
                       (set! fixture-turn-id
                             (get-in env [::db.id/ids ::fixture-turn]))
                       (let [prev db/*conn*]
                         (set! db/*conn* conn)
                         (-> (js/Promise.resolve (body))
                             (.finally
                               (fn [] (set! db/*conn* prev))))))))))))

(defn- run-batch!
  "Parse `source` and run it through `eval-batch!` against the
   root-bound conn, starting from `start-ns`. Returns Promise<result>."
  [source start-ns]
  (-> (repl/ensure-bootstrap!)
      (.then (fn [_]
               (seval/eval-batch! @repl/!compile-state
                                  (internal/parse-forms source)
                                  start-ns
                                  fixture-agent-id
                                  fixture-turn-id
                                  nil)))))

(defn- eval-rows
  "Recorded eval rows as `[{:ok? :source :error :ns} …]`, in CREATION
   order (sorted by eid — ids are minute-grained, eids are monotonic)."
  [db*]
  (->> (d/q '[:find ?e ?id ?ok ?src ?ns
              :where
              [?e :seon.eval/id ?id]
              [?e :seon.eval/ok? ?ok]
              [?e :seon.eval/source ?src]
              [?e :seon.eval/ns ?ns]]
            db*)
       (map (fn [[e id ok src ns-kw]]
              {:eid e :id id :ok? ok :source src :ns ns-kw
               :error (ffirst (d/q '[:find ?er :in $ ?id
                                     :where [?e :seon.eval/id ?id]
                                            [?e :seon.eval/error ?er]]
                                   db* id))}))
       (sort-by :eid)
       vec))

(defn- ns-source
  [db* ns-kw]
  (ffirst (d/q '[:find ?src :in $ ?ns
                 :where [?e :seon.ns/name ?ns] [?e :seon.ns/source ?src]]
               db* ns-kw)))

(defn- fn-row-source
  [db* sym-str]
  (ffirst (d/q '[:find ?src :in $ ?s
                 :where [?e :seon.fn/sym ?s] [?e :seon.fn/source ?src]]
               db* sym-str)))

;; ===========================================================================
;; A1 — in-ns to a FRESH name: never an error, never a blank slate — the
;; ns is created via the augmented path (toolkit requires), and the
;; current-ns accumulator (⇒ the :seon.eval/ns datoms the cursor +
;; namespaces block derive from) follows.
;; ===========================================================================

(deftest in-ns-fresh-name-creates-with-toolkit
  (async done
    (with-conn
      (fn []
        (-> (run-batch! (str "(in-ns 'probe.rv.fresh1)\n"
                             "(defn f1 [x] (+ x 1))\n"
                             "(f1 2)")
                        'my.agent.rv)
            (.then
              (fn [_]
                (let [db*  @db/*conn*
                      rows (eval-rows db*)]
                  (is (= [true true true] (mapv :ok? rows))
                      "in-ns to a fresh name is never an error")
                  (testing "the in-ns became the augmented (ns …) create"
                    (is (str/includes? (:source (first rows)) "(ns probe.rv.fresh1"))
                    (is (str/includes? (:source (first rows)) "seon.db")
                        "toolkit requires are REAL requires in the created ns"))
                  (testing "current-ns follows in-ns — every row ran there"
                    (is (= [:probe.rv.fresh1 :probe.rv.fresh1 :probe.rv.fresh1]
                           (mapv :ns rows))))
                  (testing "the created ns is a real program-graph row"
                    (is (some? (ns-source db* :probe.rv.fresh1)))
                    (is (some? (fn-row-source db* "probe.rv.fresh1/f1")))))))
            (.finally done))))))

(deftest in-ns-existing-ns-is-state-preserving-movement
  (async done
    (with-conn
      (fn []
        (-> (run-batch! (str "(ns probe.rv.exist (:require [clojure.string :as pstr]))\n"
                             "(defn g [s] (pstr/upper-case s))\n"
                             "(in-ns 'probe.rv.mvaway)\n"
                             "(in-ns 'probe.rv.exist)\n"
                             "(g \"ok\")")
                        'my.agent.rv)
            (.then
              (fn [_]
                (let [rows (eval-rows @db/*conn*)]
                  (is (= [true true true true true] (mapv :ok? rows)))
                  (testing "moving back does NOT clobber the ns — alias still works"
                    (is (str/includes? (:source (last rows)) "(g \"ok\")"))
                    (is (true? (:ok? (last rows)))))
                  (testing "the return move is pure movement, not a re-declare"
                    (is (= "(in-ns 'probe.rv.exist)" (:source (nth rows 3)))
                        "an existing target records the in-ns itself, no (ns …) rewrite")
                    (is (= :probe.rv.exist (:ns (nth rows 3))))))))
            (.finally done))))))

;; ===========================================================================
;; A2 — (ns …) re-declare REPLACES the requires: stored source heals,
;; removed edges actually removed, no orphaned edge rows.
;; ===========================================================================

(deftest ns-redeclare-heals-source-and-edges
  (async done
    (with-conn
      (fn []
        (-> (run-batch! "(ns probe.rv.heal (:require [clojure.string :as s1] [clojure.set :as s2]))"
                        'my.agent.rv)
            (.then (fn [_]
                     (run-batch! "(ns probe.rv.heal (:require [clojure.string :as s1]))"
                                 'my.agent.rv)))
            (.then
              (fn [_]
                (let [db*   @db/*conn*
                      edges (seval/persisted-require-edges db* :probe.rv.heal)
                      srcs  (map :seon.ns.require/target edges)]
                  (testing "stored declaration is the NEW one"
                    (let [src (ns-source db* :probe.rv.heal)]
                      (is (str/includes? src "clojure.string"))
                      (is (not (str/includes? src "clojure.set")))))
                  (testing "removed require's edge is gone — replaced wholesale"
                    (is (contains? (set srcs) :clojure.string))
                    (is (not (contains? (set srcs) :clojure.set))))
                  (testing "no orphaned edge rows survive the replace"
                    (is (= (count edges)
                           (count (d/q '[:find ?e :in $ ?ns
                                         :where [?n :seon.ns/name ?ns]
                                                [?n :seon.ns/require-edges ?e]]
                                       db* :probe.rv.heal))))))))
            (.finally done))))))

;; ===========================================================================
;; B4 — bare (require …) persists into the namespace edge facts (durable by
;; default) and is idempotent; the reconstituted resume source carries it.
;; ===========================================================================

(deftest bare-require-persists-into-the-declaration
  (async done
    (with-conn
      (fn []
        (-> (run-batch! (str "(ns probe.rv.req)\n"
                             "(require '[clojure.string :as rstr])\n"
                             "(rstr/upper-case \"x\")")
                        'my.agent.rv)
            (.then
              (fn [_]
                (let [db*  @db/*conn*
                      src  (ns-source db* :probe.rv.req)
                      rows (eval-rows db*)]
                  (is (true? (:ok? (nth rows 2))) "the alias resolves NOW")
                  (testing "the require landed in the stored declaration"
                    (is (str/includes? src "clojure.string"))
                    (is (str/includes? src ":as rstr")))
                  (testing "resume replay carries it — the reconstituted source requires it"
                    (is (str/includes? (seval/reconstitute-ns-source db* :probe.rv.req)
                                       ":as rstr")))
                  ;; idempotence: re-requiring the same spec changes nothing
                  (-> (run-batch! (str "(in-ns 'probe.rv.req)\n"
                                       "(require '[clojure.string :as rstr])")
                                  'my.agent.rv)
                      (.then (fn [_]
                               (is (= src (ns-source @db/*conn* :probe.rv.req))
                                   "re-require of the same spec is a no-op")))))))
            (.finally done))))))

;; ===========================================================================
;; B4b — cross-ns USE after movement (rung-1 gate drive repro, 2026-07-10,
;; evals/runs/2026-07-10-minimal-buildup ds-r1-ns-move-v1-d2): a fn defined
;; in an in-ns-created ns must be callable from ANOTHER ns — via a bare
;; (require '[that.ns :as a]) alias AND fully qualified. The live drive
;; showed (require '[my.convert :as convert]) from home making
;; convert/to-feet "not defined" even though (to-feet 1.0) worked inside
;; my.convert — the agent burned ~40 forms fighting it.
;; ===========================================================================

(deftest cross-ns-call-after-in-ns-defn-and-require
  (async done
    (with-conn
      (fn []
        (-> (run-batch! (str "(in-ns 'probe.rv.xuse)\n"
                             "(defn xf [m] (* m 2.0))\n"
                             "(in-ns 'my.agent.rv)\n"
                             "(require '[probe.rv.xuse :as xu])\n"
                             "(xu/xf 3.0)\n"
                             "(probe.rv.xuse/xf 4.0)")
                        'my.agent.rv)
            (.then
              (fn [_]
                (let [rows (eval-rows @db/*conn*)]
                  (is (= [true true true true true true] (mapv :ok? rows))
                      (str "every step of move→defn→move-home→require→call "
                           "succeeds — errors: "
                           (pr-str (keep :error rows))))
                  (testing "the aliased and fully-qualified calls both resolve the var"
                    (is (true? (:ok? (nth rows 4))) "aliased call (xu/xf 3.0)")
                    (is (true? (:ok? (nth rows 5))) "qualified call (probe.rv.xuse/xf 4.0)")))))
            (.finally done))))))

(deftest cross-ns-call-after-multi-turn-my-ns-require
  ;; The drive-exact shape: a `my.*` ns DECLARED first, the defn in a
  ;; SEPARATE batch (turn), the (require '[… :as a]) + calls from home in
  ;; a third — each batch = one turn, like the live loop.
  (async done
    (with-conn
      (fn []
        (-> (run-batch! "(ns my.rvxuse)" 'my.agent.rv)
            (.then (fn [_] (run-batch! (str "(in-ns 'my.rvxuse)\n"
                                            "(defn xg [m] (* m 2.0))")
                                       'my.agent.rv)))
            ;; `:stream` shape: ONE form per batch — the require and each call
            ;; land in their own turns (the live ds-r1 failure had the
            ;; aliased call in the turn AFTER the require; Spark's same-turn
            ;; require+call succeeded on the same bundle).
            (.then (fn [_] (run-batch! "(require '[my.rvxuse :as xg2])" 'my.agent.rv)))
            (.then (fn [_] (run-batch! "(xg2/xg 3.0)" 'my.agent.rv)))
            (.then (fn [_] (run-batch! "(my.rvxuse/xg 4.0)" 'my.agent.rv)))
            (.then
              (fn [_]
                (let [rows (eval-rows @db/*conn*)]
                  (is (every? :ok? rows)
                      (str "declare→defn→require→call across turns all ok — "
                           "errors: " (pr-str (keep :error rows)))))))
            (.finally done))))))

;; ===========================================================================
;; B5 — (alias 'a 'the.ns): works when the target is loaded (recorded as a
;; require alias, persisted); error-as-value when it is not (Clojure parity).
;; ===========================================================================

(deftest alias-records-a-require-alias
  (async done
    (with-conn
      (fn []
        (-> (run-batch! (str "(ns probe.rv.al)\n"
                             "(alias 'astr 'clojure.string)\n"
                             "(astr/lower-case \"AB\")")
                        'my.agent.rv)
            (.then
              (fn [_]
                (let [db*  @db/*conn*
                      rows (eval-rows db*)]
                  (is (= [true true true] (mapv :ok? rows)))
                  (is (str/includes? (ns-source db* :probe.rv.al) ":as astr")
                      "the alias persists in the stored declaration"))))
            (.finally done))))))

(deftest alias-to-an-unloaded-ns-is-an-error-value
  (async done
    (with-conn
      (fn []
        (-> (run-batch! "(alias 'zz 'no.such.probe.ns)" 'my.agent.rv)
            (.then
              (fn [_]
                (let [row (first (eval-rows @db/*conn*))]
                  (is (false? (:ok? row)))
                  (is (str/includes? (str (:error row)) "No namespace")
                      "Clojure parity: alias requires the target to exist"))))
            (.finally done))))))

;; ===========================================================================
;; B6 — :as-alias: qualified-keyword alias WITHOUT loading the target.
;; ===========================================================================

(deftest as-alias-aliases-without-loading
  (async done
    (with-conn
      (fn []
        (-> (run-batch! (str "(ns probe.rv.asa (:require [probe.rv.notloaded :as-alias nl]))\n"
                             "(str ::nl/k)")
                        'my.agent.rv)
            (.then
              (fn [_]
                (let [db*  @db/*conn*
                      rows (eval-rows db*)]
                  (is (= [true true] (mapv :ok? rows)))
                  (testing "the keyword resolved through the reader alias"
                    (is (str/includes? (:source (nth rows 1)) "nl/k")))
                  (testing "the target ns was NEVER loaded/compiled"
                    (is (nil? (get-in @@repl/!compile-state
                                      [:cljs.analyzer/namespaces
                                       'probe.rv.notloaded :name]))))
                  (testing "the edge round-trips flagged as-alias (no load on resume)"
                    (let [edge (first (filter #(= :probe.rv.notloaded
                                                  (:seon.ns.require/target %))
                                              (seval/persisted-require-edges
                                                db* :probe.rv.asa)))]
                      (is (true? (:seon.ns.require/as-alias? edge)))
                      (is (= 'nl (:seon.ns.require/alias edge))))))))
            (.finally done))))))

;; ===========================================================================
;; C7 — redefinition IS update: new behavior on call, :seon.fn row upserted
;; in place (single row, new source).
;; ===========================================================================

(deftest redefinition-is-update
  (async done
    (with-conn
      (fn []
        (-> (run-batch! (str "(ns probe.rv.redef)\n"
                             "(defn h [x] (* x 2))\n"
                             "(h 3)")
                        'my.agent.rv)
            (.then (fn [_]
                     (run-batch! (str "(in-ns 'probe.rv.redef)\n"
                                      "(defn h [x] (* x 10))\n"
                                      "(h 3)")
                                 'my.agent.rv)))
            (.then
              (fn [_]
                (let [db*  @db/*conn*
                      rows (eval-rows db*)
                      last-call (last rows)]
                  (is (true? (:ok? last-call)))
                  (testing "the call after redef returns the NEW behavior"
                    (is (str/includes?
                          (str (ffirst (d/q '[:find ?r :in $ ?id
                                              :where [?e :seon.eval/id ?id]
                                                     [?e :seon.eval/result-edn ?r]]
                                            db* (:id last-call))))
                          "30")))
                  (testing "ONE :seon.fn row, upserted to the new source"
                    (is (= 1 (count (d/q '[:find ?e :in $ ?s
                                           :where [?e :seon.fn/sym ?s]]
                                         db* "probe.rv.redef/h"))))
                    (is (str/includes? (fn-row-source db* "probe.rv.redef/h")
                                       "(* x 10)"))))))
            (.finally done))))))

;; ===========================================================================
;; C9 — deftest re-eval overwrites: single :seon.test row upserted to the
;; new source, and the auto-test pass RUNS the new version.
;; ===========================================================================

(deftest deftest-re-eval-upserts-and-autoruns-the-new-version
  (async done
    (with-conn
      (fn []
        (set! (.-rvMark js/globalThis) nil)
        (-> (run-batch! (str "(ns probe.rv.dt (:require [cljs.test]))\n"
                             "(cljs.test/deftest trv"
                             " (set! (.-rvMark js/globalThis) \"v1\"))")
                        'my.agent.rv)
            (.then (fn [_]
                     (is (= "v1" (.-rvMark js/globalThis))
                         "the auto-test pass ran the FIRST version")
                     (run-batch! (str "(in-ns 'probe.rv.dt)\n"
                                      "(cljs.test/deftest trv"
                                      " (set! (.-rvMark js/globalThis) \"v2\"))")
                                 'my.agent.rv)))
            (.then
              (fn [_]
                (let [db* @db/*conn*]
                  (is (= "v2" (.-rvMark js/globalThis))
                      "the auto-test pass ran the NEW version")
                  (testing "ONE :seon.test row, upserted to the new source"
                    (is (= 1 (count (d/q '[:find ?e :in $ ?s
                                           :where [?e :seon.test/sym ?s]]
                                         db* "probe.rv.dt/trv"))))
                    (is (str/includes?
                          (str (ffirst (d/q '[:find ?src :in $ ?s
                                              :where [?e :seon.test/sym ?s]
                                                     [?e :seon.test/source ?src]]
                                            db* "probe.rv.dt/trv")))
                          "v2"))))))
            (.finally done))))))

;; ===========================================================================
;; C10 — ns-unmap removes the live var AND retracts the :seon.fn row;
;; unknown names and core fns are error-values.
;; ===========================================================================

(deftest ns-unmap-removes-var-and-retracts-row
  (async done
    (with-conn
      (fn []
        (-> (run-batch! (str "(ns probe.rv.rm)\n"
                             "(defn rvdead [x] x)")
                        'my.agent.rv)
            (.then (fn [_]
                     (is (some? (fn-row-source @db/*conn* "probe.rv.rm/rvdead"))
                         "row exists before unmap")
                     (run-batch! (str "(in-ns 'probe.rv.rm)\n"
                                      "(ns-unmap 'rvdead)\n"
                                      "(rvdead 1)")
                                 'my.agent.rv)))
            (.then
              (fn [_]
                (let [db*  @db/*conn*
                      rows (eval-rows db*)]
                  (is (true? (:ok? (nth rows 3))) "the unmap itself succeeds")
                  (testing "the live var is gone — a later call fails"
                    (is (false? (:ok? (last rows)))))
                  (testing "the projection row is retracted (resume + instrumentation forget it)"
                    (is (nil? (fn-row-source db* "probe.rv.rm/rvdead")))))))
            (.finally done))))))

(deftest ns-unmap-unknown-and-core-are-error-values
  (async done
    (with-conn
      (fn []
        (-> ;; a boot-process row — the compiled-core case.
            (db/with-tx-context
              {:seon.db/user [:seon.agent/id "root"]
               :seon.db/process
               [:seon.db.process/id :seon.db.process/boot]}
              (fn [] (db/transact!
                       {:seon.db/tx-data
                        [{:seon.fn/sym    "probe.rv.core/pf"
                          :seon.fn/source "(defn pf [x] x)"}]})))
            (.then (fn [_]
                     (run-batch! (str "(ns-unmap 'probe.rv.rm 'never-was)\n"
                                      "(ns-unmap 'probe.rv.core 'pf)")
                                 'my.agent.rv)))
            (.then
              (fn [_]
                (let [rows (eval-rows @db/*conn*)]
                  (testing "unknown name → honest error value"
                    (is (false? (:ok? (first rows))))
                    (is (str/includes? (str (:error (first rows))) "not defined")))
                  (testing "compiled core fn → refused, named"
                    (is (false? (:ok? (second rows))))
                    (is (str/includes? (str (:error (second rows))) "core"))
                    (is (some? (fn-row-source @db/*conn* "probe.rv.core/pf"))
                        "the core row is untouched")))))
            (.finally done))))))

;; ===========================================================================
;; C10 — ns-unalias drops the alias from the analyzer, the stored
;; declaration, and the edges; the target ns stays required.
;; ===========================================================================

(deftest ns-unalias-drops-the-alias-everywhere
  (async done
    (with-conn
      (fn []
        (-> (run-batch! (str "(ns probe.rv.ua (:require [clojure.string :as ustr]))\n"
                             "(ns-unalias 'ustr)\n"
                             "(ustr/upper-case \"x\")")
                        'my.agent.rv)
            (.then
              (fn [_]
                (let [db*  @db/*conn*
                      rows (eval-rows db*)]
                  (is (true? (:ok? (nth rows 1))) "the unalias succeeds")
                  (testing "the alias no longer resolves"
                    (is (false? (:ok? (last rows)))))
                  (testing "stored declaration keeps the require, drops the alias"
                    (let [src (ns-source db* :probe.rv.ua)]
                      (is (str/includes? src "clojure.string"))
                      (is (not (str/includes? src ":as ustr")))))
                  (testing "edges updated — target kept, alias gone"
                    (let [edge (first (filter #(= :clojure.string
                                                  (:seon.ns.require/target %))
                                              (seval/persisted-require-edges
                                                db* :probe.rv.ua)))]
                      (is (some? edge))
                      (is (nil? (:seon.ns.require/alias edge)))))
                  (testing "unknown alias → honest error value"
                    (-> (run-batch! "(ns-unalias 'probe.rv.ua 'nope)" 'my.agent.rv)
                        (.then (fn [_]
                                 (let [r (last (eval-rows @db/*conn*))]
                                   (is (false? (:ok? r)))
                                   (is (str/includes? (str (:error r))
                                                      "not an alias"))))))))))
            (.finally done))))))

;; ===========================================================================
;; C59 repro — sequential eval-batch! calls within ONE async microtask
;; chain must BOTH tee their program-graph rows. Registry row C59 reports
;; two real-ns batches awaited back-to-back in one ^:async fn both teeing
;; 0 fn/ns rows (evals succeed, n-ok correct; rows never committed).
;; This pins the expected behavior: each batch's defn tees its :seon.fn
;; row and its ns row, with no macrotask separation between the batches.
;; ===========================================================================

(deftest c59-back-to-back-batches-both-tee
  (async done
    (with-conn
      (fn []
        (-> (run-batch! (str "(ns probe.rv.c59a)\n"
                             "(defn fa [x] (+ x 1))")
                        'my.agent.rv)
            ;; SAME microtask chain — no timer/macrotask between batches.
            (.then
              (fn [r1]
                (is (= 2 (:seon.eval/n-ok r1)) "batch 1 evals ok")
                (run-batch! (str "(ns probe.rv.c59b)\n"
                                 "(defn fb [x] (* x 2))")
                            'my.agent.rv)))
            (.then
              (fn [r2]
                (is (= 2 (:seon.eval/n-ok r2)) "batch 2 evals ok")
                (let [db* @db/*conn*]
                  (testing "batch 1's tee rows committed"
                    (is (some? (fn-row-source db* "probe.rv.c59a/fa"))
                        "batch 1 :seon.fn row present")
                    (is (some? (ns-source db* :probe.rv.c59a))
                        "batch 1 :seon.ns row present"))
                  (testing "batch 2's tee rows committed"
                    (is (some? (fn-row-source db* "probe.rv.c59b/fb"))
                        "batch 2 :seon.fn row present")
                    (is (some? (ns-source db* :probe.rv.c59b))
                        "batch 2 :seon.ns row present")))))
            (.finally done))))))

;; The EXACT reported shape: two awaited batches inside ONE ^:async fn,
;; under the turn-runner's outer with-tx-context (agent + turn scope) —
;; the closest hermetic stand-in for the §1 verify unit's driver.
(deftest c59-awaited-batches-in-one-async-fn-both-tee
  (async done
    (with-conn
      (fn []
        (-> (repl/ensure-bootstrap!)
            (.then
              (fn [_]
                (db/with-tx-context
                  {:seon.db/user
                   [:seon.agent/id fixture-agent-id]
                   :seon.db/process
                   [:seon.db.process/id :seon.db.process/repl]}
                  (fn ^:async run-two-batches! []
                    (let [cs @repl/!compile-state
                          r1 (await (seval/eval-batch!
                                      cs
                                      (internal/parse-forms
                                        "(ns probe.rv.c59c)\n(defn fc [x] (- x 1))")
                                      'my.agent.rv fixture-agent-id
                                      fixture-turn-id nil))
                          r2 (await (seval/eval-batch!
                                      cs
                                      (internal/parse-forms
                                        "(ns probe.rv.c59d)\n(defn fd [x] (* x 3))")
                                      'my.agent.rv fixture-agent-id
                                      fixture-turn-id nil))]
                      {:r1 r1 :r2 r2})))))
            (.then
              (fn [{:keys [r1 r2]}]
                (is (= 2 (:seon.eval/n-ok r1)) "awaited batch 1 evals ok")
                (is (= 2 (:seon.eval/n-ok r2)) "awaited batch 2 evals ok")
                (let [db* @db/*conn*]
                  (is (some? (fn-row-source db* "probe.rv.c59c/fc"))
                      "awaited batch 1 :seon.fn row present")
                  (is (some? (fn-row-source db* "probe.rv.c59d/fd"))
                      "awaited batch 2 :seon.fn row present")
                  (is (some? (ns-source db* :probe.rv.c59c))
                      "awaited batch 1 :seon.ns row present")
                  (is (some? (ns-source db* :probe.rv.c59d))
                      "awaited batch 2 :seon.ns row present"))))
            (.finally done))))))
