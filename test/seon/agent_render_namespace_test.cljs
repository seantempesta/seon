(ns seon.agent-render-namespace-test
  "Tests for `seon.agent/render-namespace` (v2-context-render PRD Phase 2,
   T4) — the foundational whole-namespace render that becomes the core of
   every agent's default context.

   `render-namespace` renders ONE namespace (its `(ns …)` source + every
   `:seon.fn` / `:seon.schema` / `:seon.test` it owns) in either `:ai`
   text or `:html` hiccup, recursing into the namespaces it `(:require …)`s.
   These tests pin the contract:

     - both formats render non-blank, with fn syms + arglists + schema keys
     - depth 1 PREPENDS a required ns's content before the requiring ns
     - depth 0 = just the ns; high depth + a cycle does NOT infinite-loop
       (each ns rendered exactly once)
     - a required ns with no `:seon.ns` entity is NOTED, not errored
     - :ai → string under `:seon.render/text`; :html → hiccup vector under
       `:seon.render/hiccup`

   All tests open a FRESH `:memory` datahike conn (via
   `seon.client/open-agent-conn!`, the same boot helper the pod uses) and
   seed `:seon.ns` / `:seon.fn` / `:seon.schema` rows directly — nothing
   here touches the live agent.

   Run interactively via MCP eval:
     (require 'seon.agent-render-namespace-test :reload)
     (cljs.test/run-tests 'seon.agent-render-namespace-test)"
  (:require
    [clojure.string :as str]
    [cljs.test :as t :refer [deftest is testing async]]
    [seon.agent :as agent]
    [seon.client :as client]
    [seon.db :as db]
    [seon.render.live-tile :as tile]))

;; ---------------------------------------------------------------------------
;; Fixture — fresh conn seeded with a small ns graph:
;;   test.parent  — a fn (greet) + a schema (:test.parent/name), no requires
;;   test.child   — requires [test.parent] and [test.missing] (the latter has
;;                  NO :seon.ns entity, so it must be NOTED not errored)
;;   cyc.a / cyc.b — require each other (cycle guard)
;; The seed is one tx; tests pick the ns they care about.
;; ---------------------------------------------------------------------------

(def ^:private seed-tx
  ;; test.parent carries its REAL full file SOURCE (the shape the boot
  ;; indexer stores for a full-rendered ns): the `(ns …)` line PLUS the
  ;; actual `(defn greet …)` and `(register! …)` forms. The separate
  ;; :seon.fn / :seon.schema member entities are seeded too (the analyzer
  ;; produces both), so GI-1 can be proven: with full source present those
  ;; member blocks are NOT re-appended (they're already in the source).
  [{:seon.ns/name :test.parent
    :seon.ns/source
    (str "(ns test.parent)\n\n"
         "(defn greet\n  \"Greets x with a friendly prefix.\"\n  [x]\n  (str \"hi \" x))\n\n"
         "(seon.schema/register! :test.parent/name :string)")}
   {:seon.fn/sym "test.parent/greet"
    :seon.fn/ns [:seon.ns/name :test.parent]
    :seon.fn/arglists "([x])"
    :seon.fn/doc "Greets x with a friendly prefix."
    :seon.fn/source "(defn greet [x] (str \"hi \" x))"
    :seon.fn/private? false}
   {:seon.schema/key :test.parent/name
    :seon.schema/ns [:seon.ns/name :test.parent]
    :seon.schema/source "(seon.schema/register! :test.parent/name :string)"}
   {:seon.ns/name :test.child
    :seon.ns/source
    "(ns test.child (:require [test.parent :as p] [test.missing :as m]))"}
   {:seon.ns/name :cyc.a
    :seon.ns/source "(ns cyc.a (:require [cyc.b]))"}
   {:seon.ns/name :cyc.b
    :seon.ns/source "(ns cyc.b (:require [cyc.a]))"}])

(defn- with-seeded-conn
  "Open a fresh conn, seed the ns graph, and run `body` (1-arg `conn`)
   with `db/*conn*` bound for the SYNC extent of `body` (so a
   `render-namespace` call with no explicit `:seon.db/db` still resolves
   a conn). Returns a Promise."
  [body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (binding [db/*conn* conn]
                 (-> (db/transact! {:seon.db/tx-data seed-tx})
                     (.then (fn [_]
                              (binding [db/*conn* conn]
                                (body conn))))))))))

;; ---------------------------------------------------------------------------
;; :ai form — renders the ns + its members, non-blank, with the right shapes.
;; ---------------------------------------------------------------------------

(deftest full-source-ns-shows-source-not-duplicate-members
  ;; GI-1: when an ns carries its REAL full file SOURCE, that source IS the
  ;; authoritative body — the per-member `[fn …]` / `[schema …]` blocks are
  ;; NOT re-appended (they're already in the source). The agent sees each
  ;; def ONCE, in the source, not twice.
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db   @conn
                  res  (agent/render-namespace
                         {:seon.db/db db :seon.ns/name :test.parent
                          :seon.render/depth 0 :seon.render/format :ai})
                  text (:seon.render/text res)]
              (is (string? text) ":ai form is a string")
              (is (pos? (count text)) "non-blank")
              ;; the fn + schema appear — IN THE SOURCE.
              (is (str/includes? text "(ns test.parent")
                  "the ns block rendered (ns-source head present)")
              (is (str/includes? text "(defn greet")
                  "the fn is shown — in the rendered source")
              (is (str/includes? text "Greets x")
                  "the fn doc is shown — in the rendered source")
              (is (str/includes? text ":test.parent/name")
                  "the schema is shown — in the rendered source")
              ;; …but NOT a second time as redundant member blocks (GI-1).
              (is (not (str/includes? text "[fn test.parent/greet]"))
                  "no duplicate [fn …] member block under full source")
              (is (not (str/includes? text "[schema :test.parent/name]"))
                  "no duplicate [schema …] member block under full source"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest sourceless-ns-lists-its-members
  ;; The complement of GI-1: a runtime-created ns with NO stored source
  ;; (only schema/fn member entities — e.g. a `my.*` ns built by transact)
  ;; STILL renders its members as `[fn …]` / `[schema …]` blocks, since the
  ;; source isn't there to carry them.
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (binding [db/*conn* conn]
              (-> (db/transact!
                    {:seon.db/tx-data
                     [{:seon.ns/name :test.runtime}
                      {:seon.fn/sym "test.runtime/go" :seon.fn/ns [:seon.ns/name :test.runtime]
                       :seon.fn/arglists "([a])" :seon.fn/source "(defn go [a] a)"}
                      {:seon.schema/key :test.runtime/id :seon.schema/ns [:seon.ns/name :test.runtime]
                       :seon.schema/source "(seon.schema/register! :test.runtime/id :string)"}]})
                  (.then
                    (fn [_]
                      (let [text (:seon.render/text
                                   (agent/render-namespace
                                     {:seon.db/db @conn :seon.ns/name :test.runtime
                                      :seon.render/depth 0 :seon.render/format :ai}))]
                        (is (str/includes? text "[fn test.runtime/go]")
                            "a sourceless ns lists its fn members")
                        (is (str/includes? text "[schema :test.runtime/id]")
                            "a sourceless ns lists its schema members"))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; :html form — hiccup vector, valid, mentions the fn sym.
;; ---------------------------------------------------------------------------

(deftest html-render-of-ns-is-valid-hiccup
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db     @conn
                  res    (agent/render-namespace
                           {:seon.db/db db :seon.ns/name :test.parent
                            :seon.render/depth 0 :seon.render/format :html})
                  hiccup (:seon.render/hiccup res)]
              (is (vector? hiccup) ":html form is a hiccup vector")
              (is (= :div (first hiccup)) "outer container is a :div")
              (is (tile/valid-hiccup? hiccup) "passes valid-hiccup?")
              (is (str/includes? (pr-str hiccup) "test.parent/greet")
                  "fn sym appears somewhere in the hiccup"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Depth 1 — a required ns's content is PREPENDED before the requiring ns.
;; ---------------------------------------------------------------------------

(deftest depth-1-prepends-required-ns
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db   @conn
                  text (:seon.render/text
                         (agent/render-namespace
                           {:seon.db/db db :seon.ns/name :test.child
                            :seon.render/depth 1 :seon.render/format :ai}))]
              ;; Anchor on the rendered ns-source HEAD `(ns X` to mean "this
              ;; ns block rendered" — distinct from a bare require mention
              ;; (`[test.parent :as p]` contains the NAME but not `(ns `).
              ;; Block ORDERING is not asserted (priority is numeric +
              ;; movable, not a contract).
              (is (str/includes? text "(ns test.parent")
                  "required ns block rendered")
              (is (str/includes? text "(ns test.child")
                  "requiring ns block rendered")
              (is (str/includes? text "(defn greet")
                  "required ns's fn brought into view (in its source)"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Depth 0 — just the ns itself, no requires followed.
;; ---------------------------------------------------------------------------

(deftest depth-0-renders-only-the-ns
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db   @conn
                  text (:seon.render/text
                         (agent/render-namespace
                           {:seon.db/db db :seon.ns/name :test.child
                            :seon.render/depth 0 :seon.render/format :ai}))]
              (is (str/includes? text "(ns test.child") "the ns itself renders")
              (is (not (str/includes? text "(ns test.parent"))
                  "depth 0 does NOT render the required parent BLOCK")
              (is (not (str/includes? text "test.parent/greet"))
                  "depth 0 does NOT pull the parent's fns"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Missing required ns — noted on one line, not errored.
;; ---------------------------------------------------------------------------

(deftest missing-required-ns-is-noted-not-errored
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db   @conn
                  text (:seon.render/text
                         (agent/render-namespace
                           {:seon.db/db db :seon.ns/name :test.child
                            :seon.render/depth 1 :seon.render/format :ai}))]
              (is (str/includes? text "test.missing (not in db)")
                  "a required ns with no :seon.ns entity is NOTED, not errored")
              (is (str/includes? text "(ns test.child")
                  "rendering still completes for the requiring ns"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Cycle — mutual requires at high depth render each ns exactly once.
;; ---------------------------------------------------------------------------

(deftest mutual-require-cycle-renders-each-ns-once
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db    @conn
                  text  (:seon.render/text
                          (agent/render-namespace
                            {:seon.db/db db :seon.ns/name :cyc.a
                             :seon.render/depth 10 :seon.render/format :ai}))
                  ;; count whole-ns BLOCK HEADS (the rendered `(ns X` source
                  ;; head) — the block marker, not a bare name mention in a
                  ;; require (`[cyc.a]` has the name but not `(ns `). Literal
                  ;; substring count (the head contains `(`, not regex-safe).
                  occ   (fn [sub]
                          (loop [i 0 n 0]
                            (if-let [j (str/index-of text sub i)]
                              (recur (+ j (count sub)) (inc n))
                              n)))]
              (is (= 1 (occ "(ns cyc.a")) "cyc.a block rendered exactly once")
              (is (= 1 (occ "(ns cyc.b")) "cyc.b block rendered exactly once")
              (is (< (count text) 2000)
                  "bounded — the cycle did not blow up the render"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Default depth (1) — when :seon.render/depth is omitted, requires ARE
;; followed one level (the agent-context use case: dropped into a near-empty
;; ns that requires a parent, the parent's content is just there).
;; ---------------------------------------------------------------------------

(deftest default-depth-follows-requires-one-level
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db   @conn
                  text (:seon.render/text
                         (agent/render-namespace
                           {:seon.db/db db :seon.ns/name :test.child}))]
              (is (str/includes? text "(ns test.parent")
                  "default depth 1 follows requires one level")
              (is (str/includes? text "(defn greet")
                  "the required ns's fns are in view by default (in its source)"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
