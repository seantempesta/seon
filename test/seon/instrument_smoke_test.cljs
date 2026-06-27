(ns seon.instrument-smoke-test
  "Regression guard for a bug class the rest of the suite STRUCTURALLY
   cannot catch: a fn whose `:malli/schema` REJECTS a value its OWN code
   produces.

   The whole suite runs UNINSTRUMENTED, so a fn like `seon.render.value/sample`
   whose 1-arity body once delegated `(sample x nil)` into a 2-arity `opts`
   slot schema'd `:map` passed every test green — `merge` tolerates the nil.
   But the LIVE pod instruments every schema'd fn from the program graph
   (`seon.instrument/instrument-from-db!`) at boot, and the SAME self-call
   then throws `:malli.core/invalid-input`, breaking the fn for real (commit
   7b9e771 was the catastrophe: every eval result rendered as
   \"could not be rendered as data\").

   This ns closes that gap: it turns instrumentation ON for the duration of
   the test (the boot posture — `collect!` populates the `:cljs`
   function-schema registry exactly as the pod does, then
   `malli.instrument/instrument!` wraps every var), then EXERCISES every
   arity of the multi-arity (delegation-prone) fns plus the value renderer
   with representative VALID args, asserting NO instrumentation
   input/output throw. A teardown un-instruments so nothing leaks into the
   other (uninstrumented) test namespaces.

   The trap fires ONLY when a lower-arity body delegates a literal /
   sentinel / nil into a higher-arity POSITIONAL slot whose schema is
   non-nillable (`:map`, `:int`, an enum, …). `cap-result-body` (3rd slot
   `:any` — nil valid) and `agent-turns` (db slot `:any`) are the SAFE
   counter-examples this guard also pins, so a future edit that narrows one
   of those slots is caught the moment it ships.

   Deterministic — a fresh `:memory` datahike conn seeded like the pod
   boots, never the live pod."
  (:require
    [cljs.test :refer [deftest is async use-fixtures]]
    [datahike.api :as d]
    [malli.core :as m]
    [malli.instrument :as mi]
    [my.kb.shared :as kb]
    [seon.client :as client]
    [seon.ctx :as ctx]
    [seon.db :as db]
    [seon.error :as err]
    [seon.error.instrument :as einst]
    [seon.eval :as seval]
    [seon.instrument]
    [seon.render.default :as rdef]
    [seon.render.value :as value]
    [seon.ui.components :as comp]
    [seon.ui.markdown :as md]
    [seon.web.brand :as brand])
  (:require-macros [seon.instrument :refer [collect!]]))

;; ============================================================
;; Instrumentation fixture — the boot posture, ON for this ns only.
;; `collect!` is the compile-time macro the pod's ORIGINAL boot path used:
;; it scans the analyzer's view of every first-party `:malli/schema`-bearing
;; fn and registers each into malli's `:cljs` function-schema registry (the
;; same atom `instrument-from-db!` populates at runtime). `mi/instrument!`
;; then reads that registry and wraps each live var; `ei/report-fn` makes a
;; violation THROW (so an exercised arity that trips fails the test). The
;; `:after` un-instruments so the wrappers don't leak into other nses.
;; ============================================================

(def ^:private target-nses
  "The namespaces whose fns this guard exercises. instrument!/unstrument!
   are SCOPED to these (via `mi/-filter-ns`): scoping keeps the teardown
   from tripping malli's multi-arity `unstrument!` bug on UNRELATED fns
   whose `:malli/schema` is a single `:=>` over a multi-arity fn (e.g.
   `seon.render.sci/invoke-bounded`) — unstrument nils the uncovered
   arities, breaking later nses. Every multi-arity fn in THESE nses uses a
   complete `[:function …]` schema, so scoped teardown restores them clean."
  '[seon.render.value seon.eval seon.ctx seon.render.default
    seon.ui.components seon.ui.markdown seon.web.brand seon.db
    seon.error seon.error.instrument my.kb.shared])

(defn- instrument-all!
  "Populate the `:cljs` registry from compile-time metadata (`collect!` —
   the pod's original boot scan), then install malli instrumentation with
   seon's throwing reporter, SCOPED to [[target-nses]] — the live-pod boot
   posture, in-process, blast-radius-limited."
  []
  (collect!)
  (mi/instrument! {:report  einst/report-fn
                   :filters [(apply mi/-filter-ns target-nses)]}))

(defn- uninstrument-all! []
  (mi/unstrument! {:filters [(apply mi/-filter-ns target-nses)]}))

(use-fixtures :once {:before instrument-all! :after uninstrument-all!})

;; ============================================================
;; Helpers — assert the presence / absence of an instrumentation throw.
;; ============================================================

(defn- instrument-trap
  "Run `thunk`; return the instrumentation envelope if it raised a Malli
   input/output trap, `:ok` if it returned, or `[:error msg]` for any OTHER
   throw (a broken test input — surfaced, not swallowed)."
  [thunk]
  (try (thunk) :ok
       (catch :default e
         (if (einst/instrument-error? (ex-data e))
           (ex-data e)
           [:error (ex-message e)]))))

(defn- check-clean!
  "Assert calling `label`'s `thunk` (valid args) raises NO instrumentation
   trap — the core regression assertion. A non-instrument throw also fails
   (signals a broken seed/input rather than a real trap)."
  [label thunk]
  (let [outcome (instrument-trap thunk)]
    (is (= :ok outcome)
        (cond
          (and (map? outcome) (einst/instrument-error? outcome))
          (str label " raised a Malli instrumentation TRAP — "
               (:seon.error.malli/fn-sym outcome)
               " arg " (:seon.error.malli/arg-index outcome)
               " expected " (:seon.error.malli/expected outcome)
               " got " (:seon.error.malli/got-edn outcome)
               " (" (:seon.error.malli/got-type outcome) ")")

          (and (vector? outcome) (= :error (first outcome)))
          (str label " raised an unexpected (non-instrument) error: "
               (second outcome))

          :else (str label)))))

(defn- instrument-traps?
  "True iff `thunk` raises a Malli instrumentation trap — the INVERSE check
   that proves the guard has teeth."
  [thunk]
  (map? (instrument-trap thunk)))

;; ============================================================
;; Meta-tests — prove the harness itself is live and has teeth, so a green
;; run of the clean tests below actually MEANS something.
;; ============================================================

(deftest instrumentation-is-actually-live
  ;; A registry-and-wrapper smoke check: a representative fn from EACH
  ;; exercised namespace must be present in the `:cljs` registry (so
  ;; `collect!` covered it — guards against a compile-order miss), and a
  ;; known-bad input must throw. Without this, a no-op fixture would let
  ;; every clean test below pass vacuously.
  (let [schemas (m/function-schemas :cljs)]
    (doseq [[ns-sym fn-sym] '[[seon.render.value sample]
                              [seon.ctx cap-result-body]
                              [seon.ctx format-eval-row]
                              [seon.render.default recent-messages]
                              [seon.db core-kinds]
                              [seon.ui.components status-dot]]]
      (is (some? (get-in schemas [ns-sym fn-sym]))
          (str ns-sym "/" fn-sym " must be registered for instrumentation"))))
  ;; A wrapped fn rejects bad input with an instrumentation throw.
  (is (instrument-traps? #(db/core-kinds :not-a-db))
      "core-kinds must reject a non-db arg under instrumentation"))

(deftest guard-has-teeth-on-the-delegation-trap
  ;; The EXACT shape of the 7b9e771 catastrophe: `sample`'s 2-arity `opts`
  ;; slot is schema'd `:map`, so a nil there MUST throw under
  ;; instrumentation. If a future edit reverts the 1-arity to delegate nil
  ;; (or widens the slot away from `:map`), `sample-delegates-clean` below
  ;; would silently pass — THIS test fails instead, naming the regression.
  (is (instrument-traps? #(value/sample 42 nil))
      "sample's :map opts slot MUST reject nil (the delegation-trap shape)")
  ;; And a non-int limit must trip a positional :int slot.
  (is (instrument-traps? #(seval/cap-edn "s" :not-an-int))
      "cap-edn's :int limit slot MUST reject a keyword"))

;; ============================================================
;; PURE fns — no db. Every arity, representative VALID args. These are the
;; fns the eval/render hot path runs on every turn; a trap here is the
;; sample-class catastrophe.
;; ============================================================

(def ^:private wide (vec (range 100)))

(def ^:private eval-row
  {:seon.eval/id         "ev1"
   :seon.eval/source     "(+ 1 2)"
   :seon.eval/ok?        true
   :seon.eval/result-edn "3"
   :seon.eval/at         (js/Date.)})

(def ^:private malli-envelope
  (einst/explain-payload
    :malli.core/invalid-input
    {:input   (m/schema [:cat :int])
     :args    ["x"]
     :schema  (m/schema [:int])
     :fn-name 'seon.foo/bar}))

(deftest pure-render-fns-instrument-clean
  ;; seon.render.value — the value renderer (the 7b9e771 home).
  (check-clean! "value/sample 1-arity"       #(value/sample 42))
  (check-clean! "value/sample 1-arity wide"  #(value/sample wide))
  (check-clean! "value/sample 2-arity"       #(value/sample wide {:max-items 8}))
  (check-clean! "value/render-ai"            #(value/render-ai "id" wide))
  (check-clean! "value/render-html-data"     #(value/render-html-data "id" wide))
  (check-clean! "value/project-plain"        #(value/project-plain {:a [1 2 3]}))
  ;; Exotic value types an agent eval can return (atoms / fns / js objects /
  ;; promises / records) — these must project to markers, never trip a slot.
  (doseq [[label v] [["atom"    (atom {:a 1})]
                     ["fn"      (fn [x] x)]
                     ["js-obj"  #js {:k "v"}]
                     ["promise" (js/Promise.resolve 1)]
                     ["date"    (js/Date.)]
                     ["set"     #{1 2 3}]
                     ["nil"     nil]]]
    (check-clean! (str "value/render-ai " label)         #(value/render-ai "id" v))
    (check-clean! (str "eval/render-result-edn " label)  #(seval/render-result-edn "id" v)))

  ;; seon.eval — result rendering + the store-time cap.
  (check-clean! "eval/render-result-edn"     #(seval/render-result-edn "id" {:a 1}))
  (check-clean! "eval/cap-edn 1-arity"       #(seval/cap-edn "hello"))
  (check-clean! "eval/cap-edn 2-arity"       #(seval/cap-edn "hello" 3))

  ;; seon.ctx — the transcript/text fns (every multi-arity one).
  (check-clean! "ctx/quote-lines 1-arity"    #(ctx/quote-lines "a\nb"))
  (check-clean! "ctx/quote-lines 2-arity"    #(ctx/quote-lines "a\nb" {:seon.ctx/strip-markers? true}))
  (check-clean! "ctx/quote-lines nil"        #(ctx/quote-lines nil))
  (check-clean! "ctx/truncate-edn 1-arity"   #(ctx/truncate-edn {:a 1}))
  (check-clean! "ctx/truncate-edn 2-arity"   #(ctx/truncate-edn {:a 1} 100))
  (check-clean! "ctx/cap-result 1-arity"     #(ctx/cap-result "s"))
  (check-clean! "ctx/cap-result 2-arity"     #(ctx/cap-result "s" 3))
  ;; cap-result-body — the SAFE counter-example (3rd slot :any, nil valid).
  (check-clean! "ctx/cap-result-body 1-arity" #(ctx/cap-result-body "s"))
  (check-clean! "ctx/cap-result-body 2-arity" #(ctx/cap-result-body "s" 3))
  (check-clean! "ctx/cap-result-body 3-arity" #(ctx/cap-result-body "s" 3 "eid"))
  (check-clean! "ctx/format-eval-row 1-arity" #(ctx/format-eval-row eval-row))
  (check-clean! "ctx/format-eval-row 2-arity" #(ctx/format-eval-row eval-row true))
  (check-clean! "ctx/ns-demarc 2-arity"      #(ctx/ns-demarc :my.ns "body"))
  (check-clean! "ctx/ns-demarc 3-arity"      #(ctx/ns-demarc :my.ns "body" "(sig)"))

  ;; seon.error / seon.error.instrument — the eval-failure render path.
  (check-clean! "error/->map 1-arity"        #(err/->map (js/Error. "x")))
  (check-clean! "error/->map 2-arity"        #(err/->map (js/Error. "x") 1))
  (check-clean! "einst/render-malli-error"   #(einst/render-malli-error malli-envelope))

  ;; seon.ui.markdown / seon.ui.components — the tile render helpers.
  (check-clean! "md/md->hiccup 1-arity"      #(md/md->hiccup "# hi"))
  (check-clean! "md/md->hiccup 2-arity"      #(md/md->hiccup "# hi" {:wrap-class "x"}))
  (check-clean! "comp/status-dot 1-arity"    #(comp/status-dot :running))
  (check-clean! "comp/status-dot 2-arity"    #(comp/status-dot :running "lbl"))
  (check-clean! "comp/table-header 1-arity"  #(comp/table-header "t"))
  (check-clean! "comp/table-header 2-arity"  #(comp/table-header "t" true))
  (check-clean! "comp/table-cell 1-arity"    #(comp/table-cell "c"))
  (check-clean! "comp/table-cell 2-arity"    #(comp/table-cell "c" {:mono? true}))
  (check-clean! "comp/log-container 1-arity" #(comp/log-container []))
  (check-clean! "comp/log-container 2-arity" #(comp/log-container [] "50vh"))
  (check-clean! "comp/empty-state 1-arity"   #(comp/empty-state "m"))
  (check-clean! "comp/empty-state 2-arity"   #(comp/empty-state "m" "sub"))
  (check-clean! "comp/action-button 2-arity" #(comp/action-button "l" "onclick"))
  (check-clean! "comp/action-button 3-arity" #(comp/action-button "l" "onclick" :primary))

  ;; seon.render.default — the two universal floors + the pending tile.
  (check-clean! "rdef/pretty-ai"             #(rdef/pretty-ai {:a 1}))
  (check-clean! "rdef/pretty-html"           #(rdef/pretty-html {:a 1}))
  (check-clean! "rdef/pending-html"          #(rdef/pending-html 'my.ns/foo))

  ;; seon.web.brand — css hook (env-only, no conn). Both arities; the
  ;; 0-arity delegates `(env-val …)` which may be nil into a `[:or :nil …]`
  ;; slot — the SAFE-by-:or counterpart to the sample trap.
  (check-clean! "brand/css-text 0-arity"     #(brand/css-text))
  (check-clean! "brand/css-text 1-arity nil" #(brand/css-text nil)))

;; ============================================================
;; DB-backed fns — a fresh seeded :memory conn (pod boot schema + one
;; agent). Exercises the db read API, the ctx readers, and the render-tile
;; readers, including the 0-arity ALS forms inside `db/with-agent`.
;; ============================================================

(def ^:private test-agent-id "SMOKEagent0001")

(defn ^:async build-seeded-conn
  "Promise of a fresh :memory conn carrying the pod's boot schema and ONE
   seeded agent — the deterministic stand-in for the live store."
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (await (d/create-database cfg))
    (let [conn (await (d/connect cfg {:sync? false}))]
      (await (d/transact! conn
               {:tx-data (into (db/malli->datahike-schema client/agent-bootstrap-attrs)
                               (db/tx-meta-datahike-schema))}))
      (await (d/transact! conn {:tx-data [{:seon.agent/id test-agent-id}]}))
      conn)))

(defn- exercise-db-fns!
  "Every db-backed target fn, both arities, with the seeded `conn`/`db`.
   Runs inside a `db/with-agent` scope so the 0-arity ALS forms resolve."
  [conn]
  (let [D @conn]
    ;; seon.db read API — the multi-arity ops (0/1/2-arity delegations).
    (check-clean! "db/history 0-arity"        #(db/history))
    (check-clean! "db/history 1-arity"        #(db/history D))
    (check-clean! "db/as-of 1-arity"          #(db/as-of (js/Date.)))
    (check-clean! "db/as-of 2-arity"          #(db/as-of D (js/Date.)))
    (check-clean! "db/since 1-arity"          #(db/since (js/Date. 0)))
    (check-clean! "db/since 2-arity"          #(db/since D (js/Date. 0)))
    (check-clean! "db/core-kinds 1-arity"     #(db/core-kinds D))
    (check-clean! "db/store-inventory 0-arity" #(db/store-inventory))
    (check-clean! "db/store-inventory 1-arity" #(db/store-inventory {:seon.db/system? true}))
    (check-clean! "db/assert-preconditions! 0" #(db/assert-preconditions!))
    (check-clean! "db/entity 1-arity"         #(db/entity {:seon.db/ref [:seon.agent/id test-agent-id]}))
    (check-clean! "db/entity 2-arity"         #(db/entity D [:seon.agent/id test-agent-id]))
    (check-clean! "db/entity-lazy 1-arity"    #(db/entity-lazy {:seon.db/ref [:seon.agent/id test-agent-id]}))
    (check-clean! "db/pull 1-arity"           #(db/pull {:seon.db/pull-pattern '[*]
                                                         :seon.db/ref [:seon.agent/id test-agent-id]}))
    (check-clean! "db/query 1-arity"          #(db/query '[:find ?e :where [?e :seon.agent/id _]]))
    (check-clean! "db/query 3-arity"          #(db/query '[:find ?e :in $ ?id
                                                           :where [?e :seon.agent/id ?id]]
                                                         D test-agent-id))

    ;; my.kb.shared / seon.web.brand — db-reading multi-arity fns.
    (check-clean! "kb/instructions 0-arity"   #(kb/instructions))
    (check-clean! "kb/instructions 1-arity"   #(kb/instructions D))
    (check-clean! "brand/info 0-arity"        #(brand/info))
    (check-clean! "brand/info 1-arity"        #(brand/info D))
    (check-clean! "brand/page-title"          #(brand/page-title (brand/info D) "agents"))

    ;; seon.ctx — the readers, 0-arity (ALS id) AND explicit-id forms.
    (check-clean! "ctx/messages 0-arity"      #(ctx/messages))
    (check-clean! "ctx/messages map-arity"    #(ctx/messages {:seon.agent/id test-agent-id :seon.db/db D}))
    (check-clean! "ctx/evals 0-arity"         #(ctx/evals))
    (check-clean! "ctx/evals map-arity"       #(ctx/evals {:seon.agent/id test-agent-id :seon.db/db D}))
    (check-clean! "ctx/current-ns 0-arity"    #(ctx/current-ns))
    (check-clean! "ctx/current-ns map-arity"  #(ctx/current-ns {:seon.agent/id test-agent-id :seon.db/db D}))
    (check-clean! "ctx/current-turn 0-arity"  #(ctx/current-turn))
    (check-clean! "ctx/current-turn map-arity" #(ctx/current-turn {:seon.agent/id test-agent-id :seon.db/db D}))
    (check-clean! "ctx/ctx-entities 0-arity"  #(ctx/ctx-entities))
    (check-clean! "ctx/ctx-entities map-arity" #(ctx/ctx-entities {:seon.agent/id test-agent-id}))
    (check-clean! "ctx/agent-turns 1-arity"   #(ctx/agent-turns test-agent-id))
    (check-clean! "ctx/agent-turns 2-arity"   #(ctx/agent-turns test-agent-id D))

    ;; seon.render.default — the tile readers (every multi-arity one) + view.
    (check-clean! "rdef/recent-messages 2-arity" #(rdef/recent-messages D test-agent-id))
    (check-clean! "rdef/recent-messages 3-arity" #(rdef/recent-messages D test-agent-id 5))
    (check-clean! "rdef/recent-errors 2-arity"   #(rdef/recent-errors D test-agent-id))
    (check-clean! "rdef/recent-errors 3-arity"   #(rdef/recent-errors D test-agent-id 5))
    (check-clean! "rdef/view"                    #(rdef/view {:seon.db/db D :seon.agent/id test-agent-id}))))

(deftest db-backed-fns-instrument-clean
  (async done
    (let [orig db/*conn*]
      (-> (build-seeded-conn)
          (.then (fn [conn]
                   (set! db/*conn* conn)
                   (db/with-agent test-agent-id
                     (fn [] (exercise-db-fns! conn)))))
          (.catch (fn [e]
                    (is false (str "db-backed deftest threw: " (ex-message e)))))
          (.finally (fn []
                      (set! db/*conn* orig)
                      (done)))))))
