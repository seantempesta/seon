(ns seon.agent.ctx.namespaces-test
  "Behavior of the `:namespaces` section — the THREE-rule SELECTION model
   ([[seon.agent.ctx.namespaces/namespaces-block]]) and the two DENSITIES it
   dispatches to (FULL source vs COMPACT card):

     FULL    = the CURRENT ns + any ns in the `::full-source` presence-set
     COMPACT = every ns the CURRENT ns `:require`s (that isn't full)
     DROPPED = everything else

   Tests assert BEHAVIOR, never rendered format: SELECTION by which ns
   demarcation brackets appear, DENSITY by whether a fn BODY marker survives
   (full shows the body; compact elides it). No exact code strings are pinned —
   those break on any formatting change and prove nothing.

   Reads INDEXED ROWS ONLY — the fixtures seed `:seon.ns` / `:seon.fn` rows
   into a scratch in-memory conn; there is no file read."
  (:require
    [clojure.string :as str]
    [cljs.reader :as edn]
    [cljs.test :refer [deftest is testing async]]
    [seon.agent.ctx :as ctx]
    [seon.agent.ctx.namespaces :as nss]
    [seon.agent.home :as home]
    [seon.ai.tokens :as tokens]
    [seon.client :as client]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.repl.internal :as repl-internal]
    [seon.schema :as schema]))

;; A valid agent id (`:seon.agent/id` is a strict shape) and its home ns —
;; a fresh agent's current ns falls back to `(home-ns id)`.
(def ^:private agent-id "tst-2606260000")
(def ^:private cur-ns :my.agent.tst-2606260000)

;; Unique body markers: present ⇒ the fn BODY was rendered (FULL); absent ⇒ the
;; body was elided (COMPACT). Markers, not format — robust to any render tweak.
(defn- fn-row [sym ns-kw body-marker]
  (let [nm (subs sym (inc (str/index-of sym "/")))]
    {:seon.fn/sym      sym
     :seon.fn/ns       [:seon.ns/name ns-kw]
     :seon.fn/source   (str "(defn " nm " [x] (" body-marker " x))")
     :seon.fn/fn-var?  true :seon.fn/private? false
     :seon.fn/arglists "([x])"}))

(defn- seed-tx []
  [{:seon.agent/id agent-id}
   ;; CURRENT ns: real source (with a body marker) + a require edge → my.helper.
   {:seon.ns/name     cur-ns
    :seon.ns/source   "(ns my.agent.tst-2606260000 (:require [my.helper :as h])) (defn plan [x] (CUR-BODY x))"
    :seon.ns/require-edges [{:seon.ns.require/target :my.helper
                             :seon.ns.require/alias  'h}]}
   (fn-row "my.agent.tst-2606260000/plan" cur-ns "CUR-BODY")
   ;; REQUIRED by the current ns → COMPACT card.
   {:seon.ns/name :my.helper :seon.ns/source "(ns my.helper)"}
   (fn-row "my.helper/assist" :my.helper "HLP-BODY")
   ;; NEITHER current, required, nor pinned → DROPPED.
   {:seon.ns/name :my.unrelated :seon.ns/source "(ns my.unrelated)"}
   (fn-row "my.unrelated/stray" :my.unrelated "UNR-BODY")
   ;; a PRIVATE fn on the helper — never exposed in a compact card.
   {:seon.fn/sym "my.helper/secret" :seon.fn/ns [:seon.ns/name :my.helper]
    :seon.fn/source "(defn- secret [x] x)" :seon.fn/fn-var? true
    :seon.fn/private? true :seon.fn/arglists "([x])"}])

(defn- with-seeded [extra-tx body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (binding [db/*conn* conn]
                 (-> (db/transact! {:seon.db/tx-data (into (seed-tx) extra-tx)})
                     (.then (fn [_] (body conn)))))))))

(defn- section-nses
  "The set of ns names rendered in the section (by demarcation bracket)."
  [out]
  (set (map second (re-seq #";;; ┌─ namespace ([^\s]+) ─" out))))

(defn- block [conn]
  (nss/namespaces-block {:seon.db/db @conn :seon.agent/id agent-id}))

(deftest current-full-required-compact-else-dropped
  (async done
    (-> (with-seeded []
          (fn [conn]
            (let [out  (block conn)
                  nses (section-nses out)]
              (testing "SELECTION"
                (is (contains? nses "my.agent.tst-2606260000") "current ns is present")
                (is (contains? nses "my.helper") "a required ns is present")
                (is (not (contains? nses "my.unrelated")) "a non-required, non-pinned ns is dropped"))
              (testing "DENSITY"
                (is (str/includes? out "CUR-BODY") "current ns renders FULL — its body survives")
                (is (not (str/includes? out "HLP-BODY")) "a required ns renders COMPACT — its body is elided"))
              (testing "private fns never enter a compact card"
                (is (not (str/includes? out "secret")))))))
        (.then (fn [_] (done)) (fn [e] (is false (str "threw: " (.-message e))) (done))))))

(deftest full-source-pins-an-otherwise-dropped-ns-to-full
  (async done
    (-> (with-seeded [{:seon.agent/id agent-id
                       :seon.agent.ctx.namespaces/full-source [:my.unrelated]}]
          (fn [conn]
            (let [out (block conn)]
              (is (contains? (section-nses out) "my.unrelated") "the pinned ns now appears")
              (is (str/includes? out "UNR-BODY") "and is promoted to FULL — its body survives"))))
        (.then (fn [_] (done)) (fn [e] (is false (str "threw: " (.-message e))) (done))))))

(deftest current-full-off-renders-current-ns-compact
  (async done
    (-> (with-seeded [{:seon.agent/id agent-id
                       :seon.agent.ctx.namespaces/current-full? false}]
          (fn [conn]
            (let [out (block conn)]
              (is (contains? (section-nses out) "my.agent.tst-2606260000") "current ns still appears")
              (is (not (str/includes? out "CUR-BODY"))
                  "::current-full? false → the current ns renders COMPACT (body elided)"))))
        (.then (fn [_] (done)) (fn [e] (is false (str "threw: " (.-message e))) (done))))))

(deftest compact-is-smaller-than-full
  ;; The whole point of a compact card: same ns, fewer tokens than its full
  ;; source. Pure size behavior — no content pinned.
  (async done
    (-> (with-seeded []
          (fn [conn]
            (let [dbv     @conn
                  compact (nss/render-one-ns-compact {:seon.ns/name :my.helper :seon.db/db dbv})
                  full    (-> (ctx/render-namespace
                                {:seon.ns/name :my.helper :seon.render/depth 0
                                 :seon.render/detail :full :seon.db/db dbv})
                              :seon.render/text)]
              (is (< (tokens/estimate compact) (tokens/estimate full))
                  "a compact card is smaller than the full render of the same ns"))))
        (.then (fn [_] (done)) (fn [e] (is false (str "threw: " (.-message e))) (done))))))

(deftest schema-reference-closure-is-db-derived-in-both-densities
  (async done
    (-> (with-seeded
          [;; Update the indexed fn with a contract that references both an
           ;; owned schema and a cross-namespace schema. None of these are
           ;; installed in Malli's process-global registry.
           {:seon.fn/sym  "my.helper/assist"
            :seon.fn/spec "[:=> [:cat :ctx.fixture/a :my.helper/local-contract] :my.helper/output]"}
           {:seon.schema/key    :my.helper/local-contract
            :seon.schema/ns     [:seon.ns/name :my.helper]
            :seon.schema/form
            "(seon.schema/register! :my.helper/local-contract [:map [:my.helper/input :string]])"}
           ;; A raw boot-indexed form, a replayable register! call, then a
           ;; raw form closing a cycle. The map label has a real schema row so
           ;; the test catches a structural walker that mistakes labels for
           ;; schema positions.
           {:seon.schema/key    :ctx.fixture/a
            :seon.schema/form "[:map [:ctx.fixture/value :ctx.fixture/b]]"}
           {:seon.schema/key    :ctx.fixture/b
            :seon.schema/form
            "(seon.schema/register! :ctx.fixture/b [:tuple :ctx.fixture/c :string])"}
           {:seon.schema/key    :ctx.fixture/c
            :seon.schema/form "[:or :ctx.fixture/a :int]"}
           {:seon.schema/key    :ctx.fixture/value
            :seon.schema/form ":keyword"}]
          (fn [conn]
            (let [dbv        @conn
                  refs       #{:ctx.fixture/a :ctx.fixture/b :ctx.fixture/c}
                  ref-block  (ctx/referenced-schema-block
                               {:seon.db/db dbv
                                :seon.agent.ctx/seed-specs
                                ["[:=> [:cat :ctx.fixture/a :my.helper/local-contract] :my.helper/output]"]
                                :seon.agent.ctx/own-keys
                                #{:my.helper/local-contract}})
                  forms      (->> (str/split-lines ref-block)
                                  (keep (fn [line]
                                          (try
                                            (let [form (edn/read-string line)]
                                              (when (and (seq? form)
                                                         (= 'register! (first form)))
                                                form))
                                            (catch :default _ nil))))
                                  vec)
                  by-key     (into {} (map (juxt second identity)) forms)
                  full       (:seon.render/text
                               (ctx/render-namespace
                                 {:seon.ns/name :my.helper
                                  :seon.render/depth 0
                                  :seon.render/detail :full
                                  :seon.db/db dbv}))
                  compact    (nss/render-one-ns-compact
                               {:seon.ns/name :my.helper :seon.db/db dbv})
                  registry-noise
                  (with-redefs [schema/schema-definition
                                (fn [_] [:enum :runtime-only-definition])]
                    (nss/render-one-ns-compact
                      {:seon.ns/name :my.helper :seon.db/db dbv}))]
              (testing "the database, not Malli's live registry, supplies refs"
                (is (every? #(not (contains? (schema/current-keys) %)) refs))
                (is (= refs (set (keys by-key)))
                    "transitive cycle closes once; a map entry label is not a ref"))
              (testing "both persisted source encodings normalize to one shape"
                (is (= [:map [:ctx.fixture/value :ctx.fixture/b]]
                       (nth (by-key :ctx.fixture/a) 2)))
                (is (= [:tuple :ctx.fixture/c :string]
                       (nth (by-key :ctx.fixture/b) 2))
                    "a persisted register! call does not become a nested call"))
              (testing "the one closure feeds both namespace densities"
                (is (every? #(str/includes? full (str %)) refs))
                (is (every? #(str/includes? compact (str %)) refs)))
              (testing "compact owned schemas are also database projections"
                (is (= compact registry-noise)
                    "changing Malli runtime state cannot change a DB render")
                (is (str/includes?
                      compact
                      "schema :my.helper/local-contract = [:map [:my.helper/input :string]]")
                    "the persisted register! call normalizes to its Malli form"))
              (testing "compact contracts remain valid outside the described ns"
                (is (str/includes? compact ":my.helper/local-contract"))
                (is (str/includes? compact ":my.helper/input"))
                (is (str/includes? compact ":my.helper/output"))
                (is (not (re-find #"::(?:local-contract|input|output)" compact)))))))
        (.then (fn [_] (done))
               (fn [e] (is false (str "threw: " (.-message e))) (done))))))

(deftest workspace-stub-reflects-configured-requires-not-const
  ;; Turn-0 regression: a FRESH agent (no home-ns source yet) renders the
  ;; workspace stub. Its `(ns … (:require …))` prose must reflect THIS agent's
  ;; CONFIG-RESOLVED requires ([[seon.agent.home/home-requires-for]]), NOT the
  ;; const default ([[seon.agent.home/home-ns-require-specs]]) the old 1-arg
  ;; call used.
  (async done
    (-> (client/open-agent-conn!)
        (.then (fn [conn]
                 (binding [db/*conn* conn]
                   (-> (db.id/allocate!
                         {::db.id/allocations
                          [{::db.id/key ::configured-agent
                            ::db.id/identity-attr :seon.agent/id}]
                          ::db.id/transaction-builder
                          (fn [ids]
                            {:seon.db/tx-data
                             [{:seon.agent/id (::configured-agent ids)}]})
                          :seon.db/conn conn})
                       (.then (fn [env]
                                (let [fresh (get-in env [::db.id/ids
                                                        ::configured-agent])
                                      home  (home/home-ns fresh)
                                      out       (nss/namespaces-block
                                                  {:seon.db/db @conn :seon.agent/id fresh})
                                      resolved  (home/home-requires-for fresh)
                                      cfg-form  (home/home-ns-form home resolved)
                                      const-form (home/home-ns-form home)]
                                  (is (contains? (section-nses out) (name home))
                                      "the fresh agent's home ns renders (the workspace stub)")
                                  (is (str/includes? out cfg-form)
                                      "stub prose is built from the config-resolved requires")
                                  ;; Only meaningful when config actually diverges from the const.
                                  (when (not= cfg-form const-form)
                                    (is (not (str/includes? out const-form))
                                        "stub prose is NOT the stale const default")))))))))
        (.then (fn [_] (done)) (fn [e] (is false (str "threw: " (.-message e))) (done))))))

(deftest compact-of-unindexed-ns-does-not-throw
  (async done
    (-> (with-seeded []
          (fn [conn]
            (let [card (nss/render-one-ns-compact {:seon.ns/name :ktest.absent :seon.db/db @conn})]
              (is (contains? (section-nses card) "ktest.absent")
                  "an un-indexed ns still renders a bracketed note, never throws"))))
        (.then (fn [_] (done)) (fn [e] (is false (str "threw: " (.-message e))) (done))))))

(deftest compact-fn-record-keeps-contract-without-becoming-code
  (let [card (nss/compact-fn-head
               {:seon.fn/sym "my.helper/assist"
                :seon.fn/arglists "([x] [x y])"
                :seon.fn/doc "Assist with a value.\n\nDeep implementation notes."
                :seon.fn/spec "[:function [:=> [:cat :int] :boolean] [:=> [:catn [:my.helper/x :int] [:my.helper/y :int]] :boolean]]"})
        parsed (repl-internal/parse-forms card)]
    (testing "the useful callable contract remains visible"
      (is (str/includes? card "my.helper/assist"))
      (is (str/includes? card "positional"))
      (is (str/includes? card "x :int"))
      (is (str/includes? card ":my.helper/x :int"))
      (is (str/includes? card "Assist with a value."))
      (is (not-any? #(str/includes? card %) [":=>" ":catn" "…"]))
      (is (not (str/includes? card "Deep implementation notes."))))
    (testing "the record is inert and reader-safe when copied"
      (is (not (str/includes? card "#object")))
      (is (not-any? #(contains? #{:form :read} (:seon.repl/kind %)) parsed)
          "a raw fn card is prose, not an executable pseudo-definition"))))

(deftest compact-fn-record-distinguishes-map-in
  (let [card (nss/compact-fn-head
               {:seon.fn/sym "seon.db/query"
                :seon.fn/arglists "([{:seon.db/keys [query args]}])"
                :seon.fn/spec
                "[:=> [:cat [:map [:seon.db/query :seon.db/query] [:seon.db/args {:optional true} [:vector :any]]]] :seon.db/query-result]"})]
    (is (str/includes? card "map-in"))
    (is (str/includes? card ":seon.db/query"))
    (is (str/includes? card ":seon.db/args?"))
    (is (not (str/includes? card "::"))
        "compact contracts never depend on the reader's current namespace")
    (is (not-any? #(str/includes? card %) [":=>" ":cat" "…"]))))

(deftest compact-namespace-card-is-inert-at-the-reply-parser-boundary
  (async done
    (-> (with-seeded
          [{:seon.schema/key :my.helper/runtime-predicate
            :seon.schema/ns [:seon.ns/name :my.helper]
            ;; Simulate an already-indexed legacy source. The live definition
            ;; below independently exercises the function-valued Malli path.
            :seon.schema/form "[:fn #object[Function]]"}]
          (fn [conn]
            (let [original schema/schema-definition
                  card (with-redefs
                         [schema/schema-definition
                          (fn [k]
                            (if (= k :my.helper/runtime-predicate)
                              [:fn (fn [_] true)]
                              (original k)))]
                         (nss/render-one-ns-compact
                           {:seon.ns/name :my.helper :seon.db/db @conn}))
                  parsed (repl-internal/parse-forms (str card "\n(+ 20 22)"))
                  forms (filter #(= :form (:seon.repl/kind %)) parsed)
                  reads (filter #(= :read (:seon.repl/kind %)) parsed)]
              (testing "runtime predicates never leak unreadable reader tags"
                (is (str/includes? card "runtime-predicate"))
                (is (not (str/includes? card "#object"))))
              (testing "copying the complete card enqueues nothing from it"
                (is (empty? reads))
                (is (= ["(+ 20 22)"] (mapv :seon.repl/source forms))
                    "only the deliberately appended real form reaches eval")))))
        (.then (fn [_] (done))
               (fn [e] (is false (str "threw: " (.-message e))) (done))))))
