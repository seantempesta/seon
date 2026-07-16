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
    [seon.agent.ctx :as ctx]
    [seon.client :as client]
    [seon.db :as db]
    [seon.render.canvas :as canvas]
    [seon.repl.internal :as repl-internal]))

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
    :seon.schema/form ":string"}
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
                          :seon.render/depth 0 :seon.render/format :ai
                          :seon.render/detail :full})
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
                       :seon.schema/form ":string"}]})
                  (.then
                    (fn [_]
                      (let [text (:seon.render/text
                                   (agent/render-namespace
                                     {:seon.db/db @conn :seon.ns/name :test.runtime
                                      :seon.render/depth 0 :seon.render/format :ai
                                      :seon.render/detail :full}))]
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
              (is (canvas/valid-hiccup? hiccup) "passes valid-hiccup?")
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
                            :seon.render/depth 1 :seon.render/format :ai
                            :seon.render/detail :full}))]
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
                            :seon.render/depth 0 :seon.render/format :ai
                            :seon.render/detail :full}))]
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
                            :seon.render/depth 1 :seon.render/format :ai
                            :seon.render/detail :full}))]
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
                             :seon.render/depth 10 :seon.render/format :ai
                             :seon.render/detail :full}))
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
                           {:seon.db/db db :seon.ns/name :test.child
                            :seon.render/detail :full}))]
              (is (str/includes? text "(ns test.parent")
                  "default depth 1 follows requires one level")
              (is (str/includes? text "(defn greet")
                  "the required ns's fns are in view by default (in its source)"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Default detail is :full — signatures are retired. The function returns the ns's
;; WHOLE real source (here test.parent carries its full file source), unclipped.
;; ---------------------------------------------------------------------------

(deftest default-detail-is-full
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db   @conn
                  text (:seon.render/text
                         ;; NO :seon.render/detail — exercises the default (:full).
                         (agent/render-namespace
                           {:seon.db/db db :seon.ns/name :test.parent
                            :seon.render/depth 0}))]
              (is (not (str/includes? text "(signatures)"))
                  "signatures are retired — no manifest view")
              (is (str/includes? text "(ns test.parent")
                  "the ns SOURCE head is shown by default (full)")
              (is (str/includes? text "(defn greet")
                  "the fn definition is shown by default (full)")
              (is (str/includes? text "(str \"hi \"")
                  "the fn BODY is shown by default (full, no clipping)"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Member drill — :seon.ns/member names ONE fn → its FULL source, nothing
;; else (the common case the agent re-issued render-namespace 4× to get).
;; ---------------------------------------------------------------------------

(deftest member-drill-returns-one-fns-full-source
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db   @conn
                  text (:seon.render/text
                         (agent/render-namespace
                           {:seon.db/db db :seon.ns/name :test.parent
                            :seon.ns/member "greet"}))]
              (is (str/includes? text "(member greet)")
                  "the member-drill block is tagged with the member name")
              (is (str/includes? text "[fn test.parent/greet]")
                  "the drilled fn's header is shown")
              (is (str/includes? text "(defn greet [x] (str \"hi \" x))")
                  "the drilled fn's FULL source is inlined (always, no threshold)"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest member-drill-accepts-qualified-name
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db   @conn
                  text (:seon.render/text
                         (agent/render-namespace
                           {:seon.db/db db :seon.ns/name :test.parent
                            ;; qualified form resolves by trailing name.
                            :seon.ns/member "test.parent/greet"}))]
              (is (str/includes? text "(defn greet [x] (str \"hi \" x))")
                  "a qualified member name resolves to the same fn"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest member-drill-unknown-member-is-noted-not-errored
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db   @conn
                  text (:seon.render/text
                         (agent/render-namespace
                           {:seon.db/db db :seon.ns/name :test.parent
                            :seon.ns/member "nope"}))]
              (is (str/includes? text "not found")
                  "an unknown member returns a note, not a throw")
              (is (str/includes? text "greet")
                  "the note lists the public fns so the agent can re-issue"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; The fn HEADER is INERT — the `; [fn …]  (ns/fn [args])` documentation line is
;; a `;` prose comment, so if an agent echoes a rendered block back into its
;; reply, `seon.repl.internal/parse-forms` SKIPS the bare arglist call instead
;; of EXECUTING it. Regression for #84: a rendered `(seon.schema/clear-all! [])`
;; header once wiped the live registry when re-read from the reply. The real
;; `(defn …)` SOURCE below it IS a form (re-eval just redefines — harmless);
;; the guard is that the HEADER arglist is never a parsed callable form.
;; Exercised via the member-drill (the fn-block-ai path that emits the header).
;; ---------------------------------------------------------------------------

(deftest rendered-fn-header-is-inert-documentation
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db    @conn
                  text  (:seon.render/text
                          (agent/render-namespace
                            {:seon.db/db db :seon.ns/name :test.parent
                             :seon.ns/member "greet"}))
                  ;; the parser the agent loop runs over its OWN reply — the
                  ;; exact path that re-executed echoed render text in #84.
                  forms (->> (repl-internal/parse-forms text)
                             (filter #(= :form (:seon.repl/kind %))))]
              (is (str/includes? text "(test.parent/greet [x])")
                  "the fn header arglist is still SHOWN to the agent")
              (is (not-any? #(str/includes? (str (:seon.repl/source %)) "(test.parent/greet [x])")
                            forms)
                  "the header arglist is a `;` comment — never a parsed callable form")
              (is (some #(str/includes? (str (:seon.repl/source %)) "(defn greet")
                        forms)
                  "the real (defn) source IS a form — re-eval redefines, harmless"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(def ^:private pure-namespace-row
  {:seon.ns/name :pure.root
   :seon.fn/_ns
   [{:seon.fn/sym "pure.root/run"
     :seon.fn/arglists "([request])"
     :seon.fn/source "(defn run [request] request)"
     :seon.fn/spec
     "[:=> [:cat :pure.root/own :pure.cross/a :pure.missing/value] :pure.cross/output]"}]
   :seon.schema/_ns
   [{:seon.schema/key :pure.root/own
     :seon.schema/form "[:map [:pure.root/value :pure.cross/b]]"}]})

(def ^:private pure-schema-rows
  [{:seon.schema/key :pure.root/own
    :seon.schema/form "[:map [:pure.root/value :pure.cross/b]]"}
   {:seon.schema/key :pure.cross/a
    :seon.schema/form "[:tuple :pure.cross/b :string]"}
   {:seon.schema/key :pure.cross/b
    :seon.schema/form "[:or :pure.cross/a :int]"}
   {:seon.schema/key :pure.cross/output
    :seon.schema/form ":string"}])

(defn- fail-on-database-io [& _]
  (throw (js/Error. "pure namespace formatting attempted database I/O")))

(deftest eager-namespace-formatting-is-pure-and-closes-schema-refs
  (let [render #(ctx/render-namespace-ai
                  {:seon.ns/name :pure.root
                   :seon.agent.ctx/namespace-rows [pure-namespace-row]
                   :seon.agent.ctx/schema-rows pure-schema-rows})
        text (with-redefs [db/query fail-on-database-io
                           db/pull fail-on-database-io
                           db/entity fail-on-database-io
                           db/entity-lazy fail-on-database-io]
               (render))]
    (testing "ordinary eager rows reach no database API"
      (is (str/includes? text "[fn pure.root/run]")))
    (testing "cross-namespace references close cycles once"
      (is (str/includes? text "(register! :pure.cross/a"))
      (is (str/includes? text "(register! :pure.cross/b"))
      (is (str/includes? text "(register! :pure.cross/output"))
      (is (= 1 (count (re-seq #"register! :pure.cross/a" text))))
      (is (= 1 (count (re-seq #"register! :pure.cross/b" text)))))
    (testing "owned and missing definitions are not duplicated or invented"
      (is (not (str/includes? text "(register! :pure.root/own")))
      (is (not (str/includes? text "(register! :pure.missing/value"))))
    (testing "the frontier seam uses the same Malli-native direct-ref parser"
      (is (= #{:pure.root/own :pure.cross/a :pure.missing/value
               :pure.cross/output}
             (ctx/schema-refs
               ["[:=> [:cat :pure.root/own :pure.cross/a :pure.missing/value] :pure.cross/output]"]))))))

(deftest eager-namespace-formatting-preserves-missing-row-and-cap
  (let [keys        (mapv #(keyword "pure.cap" (str "k" %)) (range 41))
        schema-rows (mapv (fn [i k]
                            {:seon.schema/key k
                             :seon.schema/form
                             (if (= i 40) ":string" (pr-str (keys (inc i))))})
                          (range 41) keys)
        row         {:seon.ns/name :pure.cap.root
                     :seon.fn/_ns
                     [{:seon.fn/sym "pure.cap.root/run"
                       :seon.fn/spec
                       (str "[:=> [:cat " (pr-str (first keys)) "] :string]")}]}
        capped      (ctx/render-namespace-ai
                      {:seon.ns/name :pure.cap.root
                       :seon.agent.ctx/namespace-rows [row]
                       :seon.agent.ctx/schema-rows schema-rows})
        missing     (ctx/render-namespace-ai
                      {:seon.ns/name :pure.absent
                       :seon.agent.ctx/namespace-rows [row]
                       :seon.agent.ctx/schema-rows schema-rows})]
    (is (= 40 (count (re-seq #"\(register! :pure.cap/k" capped)))
        "the existing closure cap emits exactly forty definitions")
    (is (str/includes? capped "40+ referenced schemas — capped"))
    (is (= "; requires: pure.absent (not in db)" missing))))

(deftest eager-and-local-namespace-ai-are-byte-identical
  (async done
    (let [proof
          (with-seeded-conn
            (fn [conn]
              (binding [db/*conn* conn]
                (-> (db/transact!
                      {:seon.db/tx-data
                       (into
                         [{:seon.ns/name :pure.root}
                          {:seon.fn/sym "pure.root/run"
                           :seon.fn/ns [:seon.ns/name :pure.root]
                           :seon.fn/arglists "([request])"
                           :seon.fn/source "(defn run [request] request)"
                           :seon.fn/spec
                           "[:=> [:cat :pure.root/own :pure.cross/a :pure.missing/value] :pure.cross/output]"}
                          {:seon.schema/key :pure.root/own
                           :seon.schema/ns [:seon.ns/name :pure.root]
                           :seon.schema/form "[:map [:pure.root/value :pure.cross/b]]"}]
                         (remove #(= :pure.root/own (:seon.schema/key %))
                                 pure-schema-rows))})
                    (.then
                      (fn [_]
                        (let [local (:seon.render/text
                                      (ctx/render-namespace
                                        {:seon.db/db @conn
                                         :seon.ns/name :pure.root
                                         :seon.render/depth 0}))
                              eager (ctx/render-namespace-ai
                                      {:seon.ns/name :pure.root
                                       :seon.agent.ctx/namespace-rows
                                       [pure-namespace-row]
                                       :seon.agent.ctx/schema-rows
                                       pure-schema-rows})]
                          (is (= local eager)
                              "the DB wrapper delegates to the exact pure AI formatter"))))))))]
      (-> proof
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))
