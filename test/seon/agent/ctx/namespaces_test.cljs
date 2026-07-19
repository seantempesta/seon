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
    [cljs.test :refer [deftest is testing async]]
    [seon.agent.ctx :as ctx]
    [seon.agent.ctx.namespaces :as nss]
    [seon.agent.home :as home]
    [seon.db :as db]
    [seon.db.protocol :as protocol]))

;; A valid agent id (`:seon.agent/id` is a strict shape) and its home ns —
;; a fresh agent's current ns falls back to `(home-ns id)`.
(def ^:private agent-id "tst-2606260000")
(def ^:private cur-ns 'my.agent.tst-2606260000)
(def ^:private database {:datahike/commit-id "namespaces" :max-tx 1})

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

(def ^:private eager-current-row
  {:seon.ns/name cur-ns
   :seon.db/tx 1
   :seon.ns/source
   "(ns my.agent.tst-2606260000 (:require [my.helper :as h])) (defn plan [x] (CUR-BODY x))"
   :seon.ns/require-edges [{:seon.ns.require/target 'my.helper
                            :seon.ns.require/refers #{'assist}}]
   :seon.fn/_ns [(dissoc (fn-row "my.agent.tst-2606260000/plan"
                                  cur-ns "CUR-BODY")
                           :seon.fn/ns)]})

(def ^:private eager-helper-row
  {:seon.ns/name 'my.helper
   :seon.db/tx 1
   :seon.ns/source "(ns my.helper)"
   :seon.fn/_ns
   (mapv #(dissoc % :seon.fn/ns)
         [(fn-row "my.helper/assist" 'my.helper "HLP-BODY")
          (fn-row "my.helper/runtime-helper" 'my.helper "RUNTIME-BODY")
          {:seon.fn/sym "my.helper/unspecced"
           :seon.fn/source "(defn unspecced [x] x)"
           :seon.fn/fn-var? true :seon.fn/private? false
           :seon.fn/arglists "([x])"}
          {:seon.fn/sym "my.helper/secret"
           :seon.fn/source "(defn- secret [x] x)"
           :seon.fn/fn-var? true :seon.fn/private? true
           :seon.fn/arglists "([x])"
           :seon.fn/spec "[:=> [:cat :int] :int]"}])})

(def ^:private eager-internal-row
  {:seon.ns/name 'my.helper.internal
   :seon.db/tx 1
   :seon.ns/source
   "(ns my.helper.internal) (defn implementation [x] (INTERNAL-BODY x))"
   :seon.fn/_ns
   [(dissoc (fn-row "my.helper.internal/implementation"
                    'my.helper.internal "INTERNAL-BODY")
            :seon.fn/ns)]})

(def ^:private eager-test-row
  {:seon.ns/name 'my.helper-test
   :seon.db/tx 1
   :seon.ns/source
   "(ns my.helper-test) (defn behavioral-test [] (TEST-NS-BODY))"})

(defn- eager-input []
  {:seon.agent/id agent-id
   :seon.agent.ctx.render-fns/current-ns cur-ns
   :seon.agent.ctx.namespaces/full-source #{}
   :seon.agent.ctx.namespaces/with-tests #{}
   :seon.agent.ctx.namespaces/current-full? true
   :seon.agent.ctx.namespaces/current-tests? true
   :seon.agent.ctx.namespaces/home-requires home/home-ns-require-specs
   :seon.agent.ctx.namespaces/namespace-rows
   [eager-current-row eager-helper-row]
   :seon.agent.ctx/schema-rows []})

(defn- member [result]
  {::protocol/success? true ::protocol/result result})

(defn- fail-member [message]
  {::protocol/success? false
   ::protocol/error {:seon.error/message message}})

(defn- fail-on-db-io [& _]
  (throw (js/Error. "pure namespace tail attempted database I/O")))

(deftest eager-namespace-tail-does-zero-database-io
  (let [original-execute-many db/execute-many
        original-query db/query
        original-pull db/pull
        original-entity db/entity]
    (try
      (set! db/execute-many fail-on-db-io)
      (set! db/query fail-on-db-io)
      (set! db/pull fail-on-db-io)
      (set! db/entity fail-on-db-io)
      (let [out (@#'nss/format-namespaces-block (eager-input))]
        (is (str/includes? out "CUR-BODY"))
        (is (str/includes? out "my.helper/assist"))
        (is (not (str/includes? out "HLP-BODY"))))
      (finally
        (set! db/execute-many original-execute-many)
        (set! db/query original-query)
        (set! db/pull original-pull)
        (set! db/entity original-entity)))))

(deftest exact-full-source-pin-may-reveal-internal-but-never-tests
  (let [base (update (eager-input)
                     :seon.agent.ctx.namespaces/namespace-rows
                     into [eager-internal-row eager-test-row])
        ordinary (@#'nss/format-namespaces-block base)
        prefix-only (@#'nss/format-namespaces-block
                     (assoc base
                            :seon.agent.ctx.namespaces/full-source
                            #{'my.helper}))
        explicitly-pinned
        (@#'nss/format-namespaces-block
         (assoc base
                :seon.agent.ctx.namespaces/full-source
                #{'my.helper.internal 'my.helper-test}))]
    (is (not (str/includes? ordinary "INTERNAL-BODY"))
        "ordinary agents do not see internal implementation source")
    (is (not (str/includes? prefix-only "INTERNAL-BODY"))
        "selecting a parent never broadens to internal descendants")
    (is (str/includes? explicitly-pinned "INTERNAL-BODY")
        "an exact generated-development pin exposes the implementation")
    (is (not (str/includes? explicitly-pinned "TEST-NS-BODY"))
        "test namespaces remain excluded even under an exact pin")))

(deftest remote-acquisition-is-bounded-and-selection-scoped
  (let [initial (@#'nss/initial-acquisition-members agent-id)
        selected (@#'nss/selected-acquisition-members
                   ['my.agent.tst-2606260000 'my.helper])
        [pull-member latest-member assignment-member] initial
        [pull-many-member tx-member] selected]
    (testing "initial discovery is one pull plus the bounded latest query"
      (is (= [protocol/pull-operation protocol/query-operation
              protocol/query-operation]
             (mapv ::protocol/operation initial)))
      (is (= 32768 (:datahike.resource/max-results latest-member))
          "the bound admits about 8,000 successful evals before explicit failure")
      (is (pos? (:datahike.resource/max-work latest-member)))
      (is (pos? (:datahike.resource/max-result-weight latest-member)))
      (let [query (::protocol/query-form latest-member)]
        (is (= '[?at :desc ?eval-tx :desc] (:order-by query)))
        (is (= 1 (:limit query)))
        (is (some #{'[?eval :seon.eval/at ?at ?eval-tx]} (:where query))))
      (is (= home/namespace-assignment-query
             (::protocol/query-form assignment-member)))
      (is (= [agent-id] (::protocol/arguments assignment-member)))
      (is (= [:seon.agent/id agent-id] (::protocol/entity-id pull-member))))
    (testing "selected rows use one pull-many and one selected tx query"
      (is (= [protocol/pull-many-operation protocol/query-operation]
             (mapv ::protocol/operation selected)))
      (is (= [[:seon.ns/name 'my.agent.tst-2606260000]
              [:seon.ns/name 'my.helper]]
             (::protocol/entity-ids pull-many-member)))
      (let [selector-values
            (set (tree-seq coll? seq (::protocol/selector pull-many-member)))]
        (is (contains? selector-values :seon.test/last-passed-at))
        (is (contains? selector-values :seon.test/last-failed-at))
        (is (contains? selector-values :seon.test/last-failure-summary)))
      (is (= [['my.agent.tst-2606260000 'my.helper]]
             (::protocol/arguments tx-member)))
      (is (not-any? #{'?all-names}
                    (tree-seq coll? seq (::protocol/query-form tx-member)))))))

(deftest remote-namespace-failures-keep-member-evidence
  (async done
    (let [original-execute-many db/execute-many
          request (atom nil)]
      (set! db/execute-many
            (fn [value]
              (reset! request value)
              (js/Promise.resolve
                {::db/results
                 [(member {:seon.agent/id agent-id})
                  (fail-member "latest namespace failed")
                  (member {})]})))
      (-> (nss/namespaces-block {:seon.agent/id agent-id
                                 ::db/db database} nil)
          (.then
            (fn [member-result]
              (let [member-failure (:seon.render/ai member-result)]
                (is (str/includes? member-failure "initial member"))
                (is (identical? database (::db/db @request))))))
          (.catch (fn [e] (is false (str "threw: " (.-message e)))))
          (.finally
            (fn []
              (set! db/execute-many original-execute-many)
              (done)))))))

(deftest grown-schema-frontier-keeps-the-production-cap-and-budgets
  (async done
    (let [left-keys (mapv #(keyword "grown.left" (str "k" %))
                          (range ctx/referenced-schema-cap))
          right-keys (mapv #(keyword "grown.right" (str "k" %))
                           (range ctx/referenced-schema-cap))
          spec (fn [keys]
                 (str "[:=> [:cat "
                      (str/join " " (map pr-str keys))
                      "] :string]"))
          requests (atom [])
          original-query db/query]
      (set! db/query
            (fn
              ([request]
               (swap! requests conj request)
               (js/Promise.resolve
                 (mapv (fn [k] [k ":string"])
                       (first (::db/args request)))))
              ([_query & _inputs] (js/Promise.resolve []))))
      (-> (js/Promise.resolve
            ((deref #'nss/acquire-schema-rows!)
             {::db/db database
              :seon.agent.ctx.namespaces/namespace-rows
              [{:seon.ns/name 'grown.left
                :seon.fn/_ns [{:seon.fn/sym "grown.left/run"
                               :seon.fn/spec (spec left-keys)}]}
               {:seon.ns/name 'grown.right
                :seon.fn/_ns [{:seon.fn/sym "grown.right/run"
                               :seon.fn/spec (spec right-keys)}]}]}))
          (.then
            (fn [acquired]
              (is (= (* 2 ctx/referenced-schema-cap)
                     (count (:seon.agent.ctx/schema-rows acquired)))
                  "the wire result supports both independent formatter closures")
              (is (= 2 (count @requests)))
              (doseq [request @requests]
                (let [frontier (first (::db/args request))]
                  (is (= ctx/referenced-schema-cap (count frontier)))
                  (is (identical? database (::db/db request)))
                  (is (= 500000 (::db/max-work request)))
                  (is (= 256 (::db/max-results request)))
                  (is (= 262144 (::db/max-result-weight request)))))))
          (.catch (fn [e] (is false (str "threw: " (.-message e)))))
          (.finally (fn [] (set! db/query original-query) (done)))))))

(deftest schema-frontier-fails-instead-of-silently-truncating-the-aggregate
  (async done
    (let [namespace-count 52
          rows
          (mapv
            (fn [namespace-index]
              (let [ns-name (symbol (str "aggregate.n" namespace-index))
                    keys (mapv #(keyword (name ns-name) (str "k" %))
                               (range ctx/referenced-schema-cap))]
                {:seon.ns/name ns-name
                 :seon.fn/_ns
                 [{:seon.fn/sym (str (name ns-name) "/run")
                   :seon.fn/spec
                   (str "[:=> [:cat "
                        (str/join " " (map pr-str keys))
                        "] :string]")}]}))
            (range namespace-count))
          original-query db/query]
      (set! db/query
            (fn
              ([request]
               (js/Promise.resolve
                 (mapv (fn [k] [k ":string"])
                       (first (::db/args request)))))
              ([_query & _inputs] (js/Promise.resolve []))))
      (-> (js/Promise.resolve
            ((deref #'nss/acquire-schema-rows!)
             {::db/db database
              :seon.agent.ctx.namespaces/namespace-rows rows}))
          (.then
            (fn [acquired]
              (let [error (:seon.agent.ctx.namespaces/error acquired)
                    data (:seon.error/data error)]
                (is (map? error))
                (is (str/includes? (:seon.error/message error)
                                   "schema aggregate bound"))
                (is (> (:seon.agent.ctx/schema-key-count data)
                       (:seon.agent.ctx/schema-key-cap data))))))
          (.catch (fn [e] (is false (str "threw: " (.-message e)))))
          (.finally (fn [] (set! db/query original-query) (done)))))))

(deftest schema-acquisition-does-not-preselect-the-formatters-first-forty
  (async done
    (let [initial-keys (mapv #(keyword "z.branch" (str "k" %))
                             (range ctx/referenced-schema-cap))
          earlier-child :a.branch/child
          row {:seon.ns/name 'branch.root
               :seon.fn/_ns
               [{:seon.fn/sym "branch.root/run"
                 :seon.fn/spec
                 (str "[:=> [:cat "
                      (str/join " " (map pr-str initial-keys))
                      "] :string]")}]}
          original-query db/query]
      (set! db/query
            (fn
              ([request]
               (js/Promise.resolve
                 (mapv (fn [k]
                         [k (if (= k (first initial-keys))
                              (pr-str [:tuple earlier-child])
                              ":string")])
                       (first (::db/args request)))))
              ([_query & _inputs] (js/Promise.resolve []))))
      (-> (js/Promise.resolve
            ((deref #'nss/acquire-schema-rows!)
             {::db/db database
              :seon.agent.ctx.namespaces/namespace-rows [row]}))
          (.then
            (fn [acquired]
              (let [schema-rows (:seon.agent.ctx/schema-rows acquired)
                    rendered (ctx/render-namespace-ai
                               {:seon.ns/name 'branch.root
                                :seon.agent.ctx/namespace-rows [row]
                                :seon.agent.ctx/schema-rows schema-rows})]
                (is (= (inc ctx/referenced-schema-cap) (count schema-rows))
                    "acquisition retains the complete reachable closure")
                (is (str/includes? rendered
                                   "(register! :a.branch/child"))
                (is (not (str/includes? rendered
                                        "(register! :z.branch/k9"))
                    "ctx alone selects its lexically re-sorted first forty")
                (is (str/includes? rendered
                                   "40+ referenced schemas — capped")))))
          (.catch (fn [e] (is false (str "threw: " (.-message e)))))
          (.finally (fn [] (set! db/query original-query) (done)))))))
