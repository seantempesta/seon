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
    [datahike.api :as d]
    [seon.agent.ctx :as ctx]
    [seon.agent.ctx.namespaces :as nss]
    [seon.agent.home :as home]
    [seon.ai.tokens :as tokens]
    [seon.client :as client]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.db.protocol :as protocol]
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
     :seon.fn/agent-facing? true
     :seon.fn/arglists "([x])"
     :seon.fn/spec     "[:=> [:cat :int] :int]"}))

(defn- seed-tx []
  [{:seon.agent/id agent-id}
   ;; CURRENT ns: real source (with a body marker) + a require edge → my.helper.
   {:seon.ns/name     cur-ns
    :seon.ns/source   "(ns my.agent.tst-2606260000 (:require [my.helper :as h])) (defn plan [x] (CUR-BODY x))"
    :seon.ns/require-edges [{:seon.ns.require/target :my.helper
                             :seon.ns.require/refers #{'assist}}]}
   (fn-row "my.agent.tst-2606260000/plan" cur-ns "CUR-BODY")
   ;; REQUIRED by the current ns → COMPACT card.
   {:seon.ns/name :my.helper :seon.ns/source "(ns my.helper)"}
   (fn-row "my.helper/assist" :my.helper "HLP-BODY")
   ;; Public program data without positive eligibility stays out of the card.
   {:seon.fn/sym "my.helper/runtime-helper"
   :seon.fn/ns [:seon.ns/name :my.helper]
    :seon.fn/source "(defn runtime-helper [x] (RUNTIME-BODY x))"
    :seon.fn/fn-var? true :seon.fn/private? false
    :seon.fn/arglists "([x])"
    :seon.fn/spec "[:=> [:cat :int] :int]"}
   {:seon.fn/sym "my.helper/unspecced"
    :seon.fn/ns [:seon.ns/name :my.helper]
    :seon.fn/source "(defn unspecced [x] x)"
    :seon.fn/fn-var? true :seon.fn/private? false
    :seon.fn/arglists "([x])"}
   {:seon.fn/sym "my.helper/not-a-function"
    :seon.fn/ns [:seon.ns/name :my.helper]
    :seon.fn/fn-var? false :seon.fn/private? false
    :seon.fn/spec "[:=> [:cat :int] :int]"}
   {:seon.fn/sym "my.helper/malformed-contract"
    :seon.fn/ns [:seon.ns/name :my.helper]
    :seon.fn/fn-var? true :seon.fn/private? false
    :seon.fn/spec ":int"}
   {:seon.fn/sym "my.helper/broken-contract"
    :seon.fn/ns [:seon.ns/name :my.helper]
    :seon.fn/fn-var? true :seon.fn/private? false
    :seon.fn/spec "[:=> [:cat :int] :int]"
    :seon.fn/schema-error "invalid function schema"}
   ;; NEITHER current, required, nor pinned → DROPPED.
   {:seon.ns/name :my.unrelated :seon.ns/source "(ns my.unrelated)"}
   (fn-row "my.unrelated/stray" :my.unrelated "UNR-BODY")
   ;; a PRIVATE fn on the helper — never exposed in a compact card.
   {:seon.fn/sym "my.helper/secret" :seon.fn/ns [:seon.ns/name :my.helper]
    :seon.fn/source "(defn- secret [x] x)" :seon.fn/fn-var? true
    :seon.fn/private? true :seon.fn/arglists "([x])"
    :seon.fn/spec "[:=> [:cat :int] :int]"}])

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

(defn- block-for [conn id]
  (nss/namespaces-block {:seon.db/db @conn :seon.agent/id id}))

(defn- block [conn]
  (block-for conn agent-id))

(deftest remote-acquisition-is-bounded-and-selection-scoped
  (let [initial (@#'nss/initial-acquisition-members agent-id)
        selected (@#'nss/selected-acquisition-members
                   [:my.agent.tst-2606260000 :my.helper])
        [pull-member latest-member config-member] initial
        [pull-many-member tx-member] selected]
    (testing "initial discovery is one pull plus the bounded latest query"
      (is (= [protocol/pull-operation protocol/query-operation
              protocol/pull-operation]
             (mapv ::protocol/operation initial)))
      (is (= 32768 (:datahike.resource/max-results latest-member))
          "the bound admits about 8,000 successful evals before explicit failure")
      (is (pos? (:datahike.resource/max-work latest-member)))
      (is (pos? (:datahike.resource/max-result-weight latest-member)))
      (let [query (::protocol/query-form latest-member)]
        (is (= '[?at :desc ?eval-tx :desc] (:order-by query)))
        (is (= 1 (:limit query)))
        (is (some #{'[?eval :seon.eval/at ?at ?eval-tx]} (:where query))))
      (is (= [:seon.agent/id agent-id] (::protocol/entity-id pull-member)))
      (is (= [:seon.config/id config/cluster-config-id]
             (::protocol/entity-id config-member)))
      (is (= [:seon.config/current-ns]
             (::protocol/selector config-member))))
    (testing "selected rows use one pull-many and one selected tx query"
      (is (= [protocol/pull-many-operation protocol/query-operation]
             (mapv ::protocol/operation selected)))
      (is (= [[:seon.ns/name :my.agent.tst-2606260000]
              [:seon.ns/name :my.helper]]
             (::protocol/entity-ids pull-many-member)))
      (let [selector-values
            (set (tree-seq coll? seq (::protocol/selector pull-many-member)))]
        (is (contains? selector-values :seon.test/last-passed-at))
        (is (contains? selector-values :seon.test/last-failed-at))
        (is (contains? selector-values :seon.test/last-failure-summary)))
      (is (= [[:my.agent.tst-2606260000 :my.helper]]
             (::protocol/arguments tx-member)))
      (is (not-any? #{'?all-names}
                    (tree-seq coll? seq (::protocol/query-form tx-member)))))))

(defn- fresh-latest-ns-conn
  []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? true}
        attrs [:seon.agent/id
               :seon.agent.run/agent
               :seon.agent.turn/run
               :seon.agent.turn/evals
               :seon.eval/at
               :seon.eval/ok?
               :seon.eval/ns]]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (d/transact! conn
                       {:tx-data (db/malli->datahike-schema attrs)})
                     (.then (constantly conn))))))))

(defn- append-eval!
  [conn turn-eid eval-id at ok? ns]
  (d/transact! conn
    {:tx-data [{:db/id eval-id
                :seon.eval/at at
                :seon.eval/ok? ok?
                :seon.eval/ns ns}
               {:db/id turn-eid
                :seon.agent.turn/evals eval-id}]}))

(deftest latest-successful-ns-orders-time-then-transaction
  (async done
    (-> (fresh-latest-ns-conn)
        (.then
          (fn [conn]
            (-> (d/transact! conn
                  {:tx-data [{:db/id "agent" :seon.agent/id agent-id}
                             {:db/id "run" :seon.agent.run/agent "agent"}
                             {:db/id "turn" :seon.agent.turn/run "run"}
                             {:db/id "no-success-agent"
                              :seon.agent/id "no-success-agent"}
                             {:db/id "no-success-run"
                              :seon.agent.run/agent "no-success-agent"}
                             {:db/id "no-success-turn"
                              :seon.agent.turn/run "no-success-run"}]})
                (.then
                  (fn [report]
                    (let [turn-eid (get (:tempids report) "turn")
                          no-success-turn-eid
                          (get (:tempids report) "no-success-turn")]
                      (-> (append-eval! conn turn-eid "first"
                            (js/Date. 2000) true :my.first)
                          (.then (fn [_]
                                   (append-eval! conn no-success-turn-eid
                                     "only-failure" (js/Date. 5000) false
                                     :my.failed)))
                          (.then (fn [_]
                                   (append-eval! conn turn-eid "later-but-older"
                                     (js/Date. 1000) true :my.older-late)))
                          (.then (fn [_]
                                   (append-eval! conn turn-eid "same-time-later"
                                     (js/Date. 2000) true :my.equal-later)))
                          (.then (fn [_]
                                   (let [ids (mapv #(str "failed-" %) (range 128))]
                                     (d/transact! conn
                                       {:tx-data
                                        (conj
                                          (mapv (fn [n eval-id]
                                                  {:db/id eval-id
                                                   :seon.eval/at (js/Date. (+ 3000 n))
                                                   :seon.eval/ok? false
                                                   :seon.eval/ns :my.failed})
                                                (range 128) ids)
                                          {:db/id turn-eid
                                           :seon.agent.turn/evals ids})}))))
                          (.then
                            (fn [_]
                              (let [query (@#'nss/latest-successful-ns-query)
                                    result
                                    (d/q {:query query
                                          :args [@conn agent-id]
                                          :max-work 1000000
                                          :max-results 32768
                                          :max-result-weight 262144})
                                    missing
                                    (d/q {:query query
                                          :args [@conn "no-success-agent"]
                                          :max-work 1000000
                                          :max-results 32768
                                          :max-result-weight 262144})]
                                (testing "equal timestamps break on transaction"
                                  (is (= :my.equal-later (ffirst result))))
                                (testing "a later transaction with an older timestamp loses"
                                  (is (not= :my.older-late (ffirst result))))
                                (testing "no successful eval returns no namespace"
                                  (is (empty? missing)))))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e]
                  (is false (str "ordered latest-namespace query threw: "
                                 (.-message e)))
                  (done))))))

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
                (is (not (str/includes? out "secret"))))
              (testing "the persisted refer edge selects exactly one callable"
                (is (str/includes? out "my.helper/assist"))
                (is (not (str/includes? out "runtime-helper"))))
              (testing "schema-incomplete public rows stay program data only"
                (is (not (str/includes? out "unspecced")))))))
        (.then (fn [_] (done)) (fn [e] (is false (str "threw: " (.-message e))) (done))))))

(deftest persisted-edge-shape-selects-one-canonical-card-surface
  (async done
    (let [alias-id "tst-2606260001"
          union-id "tst-2606260002"
          as-alias-id "tst-2606260003"]
      (-> (with-seeded
            [{:seon.agent/id alias-id}
             {:seon.ns/name :my.agent.tst-2606260001
              :seon.ns/require-edges
              [{:seon.ns.require/target :my.helper
                :seon.ns.require/alias 'h}]}
             {:seon.agent/id union-id}
             {:seon.ns/name :my.agent.tst-2606260002
              :seon.ns/require-edges
              [{:seon.ns.require/target :my.helper
                :seon.ns.require/refers #{'assist}}
               {:seon.ns.require/target :my.helper
                :seon.ns.require/refers #{'runtime-helper}}]}
             {:seon.agent/id as-alias-id}
             {:seon.ns/name :my.agent.tst-2606260003
              :seon.ns/require-edges
              [{:seon.ns.require/target :my.helper
                :seon.ns.require/alias 'h
                :seon.ns.require/as-alias? true}]}]
            (fn [conn]
              (let [alias-card (block-for conn alias-id)
                    union-card (block-for conn union-id)
                    as-alias-card (block-for conn as-alias-id)]
                (testing "a real alias exposes all valid public functions"
                  (is (str/includes? alias-card "my.helper/assist"))
                  (is (str/includes? alias-card "my.helper/runtime-helper"))
                  (is (not (str/includes? alias-card "unspecced")))
                  (is (not (str/includes? alias-card "not-a-function")))
                  (is (not (str/includes? alias-card "malformed-contract")))
                  (is (not (str/includes? alias-card "broken-contract"))))
                (testing "several refer edges union deterministically"
                  (is (str/includes? union-card "my.helper/assist"))
                  (is (str/includes? union-card "my.helper/runtime-helper")))
                (testing "as-alias is keyword resolution, not a callable edge"
                  (is (not (contains? (section-nses as-alias-card) "my.helper")))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw: " (.-message e))) (done)))))))

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
           {:seon.schema/key    :my.helper/unrelated-contract
            :seon.schema/ns     [:seon.ns/name :my.helper]
            :seon.schema/form "[:map [:my.helper/unrelated :string]]"}
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
                  narrow     (nss/render-one-ns-compact
                               {:seon.ns/name :my.helper
                                :seon.db/db dbv
                                :seon.ns.require/refers #{'assist}})
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
                (is (not (re-find #"::(?:local-contract|input|output)" compact))))
              (testing "a narrow refer card emits only reachable owned schemas"
                (is (str/includes? narrow ":my.helper/local-contract"))
                (is (not (str/includes? narrow ":my.helper/unrelated-contract")))))))
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

(deftest compact-fn-record-uses-logical-arities-over-variadic-body
  (let [transact (nss/compact-fn-head
                   {:seon.fn/sym "seon.db/transact!"
                    :seon.fn/arglists "([& call-args])"
                    :seon.fn/spec
                    "[:function [:=> [:catn [:seon.db/request :seon.db/transact-request]] :seon.db/transact-response] [:=> [:catn [:seon.db/conn :seon.db/conn] [:seon.db/tx-data :seon.db/tx-data]] :seon.db/transact-response] [:=> [:catn [:seon.db/conn :seon.db/conn] [:seon.db/tx-data :seon.db/tx-data] [:seon.db/tx-meta :seon.db/tx-meta]] :seon.db/transact-response]]"})
        query    (nss/compact-fn-head
                   {:seon.fn/sym "seon.db/query"
                    :seon.fn/arglists "([& args])"
                    :seon.fn/spec
                    "[:function [:=> [:catn [:seon.db/request [:or :seon.db/query-request :seon.db/query-form]]] :any] [:=> [:catn [:seon.db/query :seon.db/query-form] [:seon.db/rest [:+ :any]]] :any]]"})]
    (testing "one variadic implementation does not overwrite logical labels"
      (is (str/includes? transact ":seon.db/request :seon.db/transact-request"))
      (is (str/includes? query ":seon.db/request [:or :seon.db/query-request :seon.db/query-form]"))
      (is (not (str/includes? transact "&")))
      (is (not (str/includes? query "&"))))
    (testing "every logical arity remains complete"
      (is (= 1 (count (re-seq #" OR " query))))
      (is (= 2 (count (re-seq #" OR " transact)))))))

(deftest compact-fn-record-keeps-a-sole-variadic-physical-arglist
  (let [card (nss/compact-fn-head
               {:seon.fn/sym "my.helper/collect"
                :seon.fn/arglists "([head & tail])"
                :seon.fn/spec "[:=> [:cat :string [:* :int]] [:vector :int]]"})]
    (is (str/includes? card "head :string"))
    (is (str/includes? card "tail [:* :int]"))
    (is (not (str/includes? card "&"))
        "the binding marker is grammar, not an argument name")
    (is (not (str/includes? card "arg-1"))
        "a single logical schema still benefits from physical binding names")))

(deftest compact-fn-record-never-promotes-vector-output-data-to-an-arity
  (doseq [{:keys [sym arglists expected-input expected-output]}
          [{:sym "my.view/nested"
            :arglists
            "([request] [:section [:header \"Title\"] (into [:ul] (map (fn [x] [:li x]) items))])"
            :expected-input "request :my.view/request"
            :expected-output "-> :my.view/control"}
           {:sym "my.view/recursive"
            :arglists
            "([node] [:branch (when-let [children (:children node)] (mapv (fn [child] [:branch (walk child)]) children))])"
            :expected-input "node :my.view/node"
            :expected-output "-> [:vector :my.view/node]"}]]
    (let [card (nss/compact-fn-head
                 {:seon.fn/sym sym
                  :seon.fn/arglists arglists
                  :seon.fn/spec
                  (str "[:=> [:cat "
                       (if (= sym "my.view/nested")
                         ":my.view/request"
                         ":my.view/node")
                       "] "
                       (if (= sym "my.view/nested")
                         ":my.view/control"
                         "[:vector :my.view/node]")
                       "]")})]
      (is (str/includes? card expected-input) sym)
      (is (str/includes? card expected-output) sym)
      (is (not (str/includes? card " OR "))
          (str sym " has exactly one schema-declared callable alternative"))
      (is (not (str/includes? card "<return unspecified>"))
          (str sym " never renders returned vector data as an input")))))

(deftest compact-fn-record-retains-physical-arities-without-a-schema
  (let [card (nss/compact-fn-head
               {:seon.fn/sym "my.helper/legacy"
                :seon.fn/arglists "([x] [x y])"})]
    (is (= 1 (count (re-seq #" OR " card))))
    (is (= 2 (count (re-seq #"<return unspecified>" card)))
        "physical arglists remain truthful fallback data when no spec exists")))

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
