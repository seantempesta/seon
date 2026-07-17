(ns seon.agent.ctx.warnings-test
  "Behavior test for the `:warnings` context block wiring
   (`seon.agent.ctx.warnings/warnings-block`).

   The render engine (`seon.render/render`) injects each block's OWN map
   as `:seon.render/node` — that is where a per-block `:seon.warn/ns`
   scope override lives. This test pins the MECHANISM: an override on the
   node CHANGES the scope of the corpus checks (warning renders when the
   defect is in scope, empty when it isn't). It falsifies the dead read
   that read the override from `:seon.agent.ctx/block` — a key the input
   never carries — which silently ignored every override.

   Run via bin/test-cljs, or interactively via MCP eval:
     (require 'seon.agent.ctx.warnings-test :reload)
     (cljs.test/run-tests 'seon.agent.ctx.warnings-test)"
  (:require
    [clojure.string :as str]
    [cljs.test :refer [deftest is testing async]]
    [seon.agent.ctx.warnings :as warnings]
    [seon.client :as client]
    [seon.db :as db]
    [seon.db.protocol :as protocol]
    [seon.instrument :as instrument]))

;; Two namespaces of fns: one carrying a contract defect (a public fn
;; with NO :malli/schema → the no-malli-schema corpus check fires), one
;; fully clean (a specced public fn → no corpus check fires). Scope
;; selects which one the warnings block sees.
(defn- seed-tx []
  [{:seon.ns/name :wtest.warns :seon.ns/source "(ns wtest.warns)"}
   {:seon.fn/sym     "wtest.warns/no-spec"
    :seon.fn/ns      [:seon.ns/name :wtest.warns]
    :seon.fn/source  "(defn no-spec [x] x)"
    :seon.fn/fn-var? true
    :seon.fn/private? false}
   {:seon.ns/name :wtest.clean :seon.ns/source "(ns wtest.clean)"}
   {:seon.fn/sym     "wtest.clean/ok"
    :seon.fn/ns      [:seon.ns/name :wtest.clean]
    :seon.fn/source  "(defn ok [s] (str s))"
    :seon.fn/fn-var? true
    :seon.fn/private? false
    :seon.fn/spec    "[:=> [:cat :string] :string]"}])

(defn- with-seeded-db [body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (binding [db/*conn* conn]
                 (-> (db/transact! {:seon.db/tx-data (seed-tx)})
                     (.then (fn [_] (body @conn)))))))))

(def ^:private database {:datahike/commit-id "warnings-test" :max-tx 1})

(defn- member [result]
  {::protocol/success? true ::protocol/result result})

(defn- acquisition-responses []
  [{::db/results
    [(member [])
     (member [[:wtest.warns (js/Date. 1) 1]])
     (member
       [["wtest.warns/no-spec" :wtest.warns "" true false ""]
        ["wtest.clean/ok" :wtest.clean
         "[:=> [:cat :string] :string]" true false ""]])
     {::protocol/success? true ::protocol/schema {}}
     (member [])
     (member [])
     (member [])]}
   {::db/results (vec (repeat 7 (member [])))}])

(defn- block-for
  [scope-kw]
  (warnings/warnings-block
    {:seon.agent/id "wtest-agent"
     :seon.agent/entity {:seon.agent/id "wtest-agent"}
     :seon.render/node {:seon.warn/ns scope-kw}
     ::db/db database}
    nil))

(deftest warnings-block-honors-scope-override-on-the-block-node
  (async done
    (let [original db/execute-many
          responses (atom (vec (mapcat identity
                                       (repeat 3 (acquisition-responses)))))
          requests (atom [])]
      (set! db/execute-many
            (fn [request]
              (swap! requests conj request)
              (let [response (first @responses)]
                (swap! responses subvec 1)
                (js/Promise.resolve response))))
      (-> (block-for :wtest.warns)
          (.then
            (fn [out]
              (testing "the remote ordinary-data owner preserves corpus scope"
                (is (str/includes? out "[no-malli-schema]"))
                (is (str/includes? out "wtest.warns/no-spec")))
              (block-for :seon.warn/all)))
          (.then
            (fn [out]
              (is (str/includes? out "wtest.warns/no-spec"))
              (block-for :wtest.clean)))
          (.then
            (fn [out]
              (is (= "" out))
              (is (= 6 (count @requests)) "each render uses two owner-local batches")
              (is (every? #(identical? database (::db/db %)) @requests)
                  "every batch uses the invocation database value")))
          (.catch (fn [e] (is false (str "threw — " e))))
          (.finally (fn [] (set! db/execute-many original) (done)))))))

;; ---------------------------------------------------------------------------
;; Instrumentation-coverage invariant (C46) — `coverage-gaps` + its block.
;; ---------------------------------------------------------------------------

;; A REAL live compiled fn, deliberately WITHOUT `:malli/schema` metadata so
;; no collect!/boot pass ever wraps it — its spec exists only as the seeded
;; `:seon.fn/spec` row below. Unwrapped ⇒ a coverage gap.
(defn gap-probe [s] (str s))

(defn ^:async async-gap-probe
  ([x] x)
  ([x y] [x y]))

(def ^:private gap-probe-spec [:=> [:cat :string] :string])
(def ^:private async-gap-probe-spec
  [:function
   [:=> [:cat :int] :int]
   [:=> [:cat :int :int] [:tuple :int :int]]])

(defn- coverage-seed-tx []
  [{:seon.ns/name :seon.agent.ctx.warnings-test
    :seon.ns/source "(ns seon.agent.ctx.warnings-test)"}
   ;; The wrappable-but-unwrapped fn — the ONE expected gap.
   {:seon.fn/sym     "seon.agent.ctx.warnings-test/gap-probe"
    :seon.fn/ns      [:seon.ns/name :seon.agent.ctx.warnings-test]
    :seon.fn/source  "(defn gap-probe [s] (str s))"
    :seon.fn/fn-var? true
    :seon.fn/private? false
    :seon.fn/spec    (pr-str gap-probe-spec)}
   ;; Async contracts participate in the exact same denominator.
   {:seon.fn/sym     "seon.agent.ctx.warnings-test/async-gap-probe"
    :seon.fn/ns      [:seon.ns/name :seon.agent.ctx.warnings-test]
    :seon.fn/source  "(defn ^:async async-gap-probe ...)"
    :seon.fn/fn-var? true
    :seon.fn/private? false
    :seon.fn/spec    (pr-str async-gap-probe-spec)}
   ;; A row whose var is NOT live (a prior session's fn) — not a gap
   ;; (an uncallable fn has no coverage risk).
   {:seon.fn/sym     "seon.agent.ctx.warnings-test/never-compiled"
    :seon.fn/ns      [:seon.ns/name :seon.agent.ctx.warnings-test]
    :seon.fn/source  "(defn never-compiled [s] s)"
    :seon.fn/fn-var? true
    :seon.fn/private? false
    :seon.fn/spec    (pr-str gap-probe-spec)}])

(deftest coverage-gaps-surface-then-self-heal
  (async done
    (let [rows [["seon.agent.ctx.warnings-test/gap-probe"
                 (pr-str gap-probe-spec)]
                ["seon.agent.ctx.warnings-test/async-gap-probe"
                 (pr-str async-gap-probe-spec)]
                ["seon.agent.ctx.warnings-test/never-compiled"
                 (pr-str gap-probe-spec)]]
          original db/query
          requests (atom [])]
      (set! db/query (fn [request]
                       (swap! requests conj request)
                       (js/Promise.resolve rows)))
      (testing "sync and async live contracts are gaps; dead rows are not"
        (let [gaps (instrument/coverage-gaps rows)]
          (is (= ["seon.agent.ctx.warnings-test/async-gap-probe"
                  "seon.agent.ctx.warnings-test/gap-probe"]
                 (mapv :seon.instrument/sym gaps)))
          (is (= [:seon.instrument/unwrapped :seon.instrument/unwrapped]
                 (mapv :seon.instrument/reason gaps)))))
      (-> (warnings/instrumentation-gaps-block
            {:seon.agent/id "root"
             :seon.agent/entity {:seon.agent/id "root"}
             ::db/db database}
            nil)
          (.then
            (fn [out]
              (is (str/includes? out "INSTRUMENTATION GAPS"))
              (is (str/includes? out
                                 "seon.agent.ctx.warnings-test/gap-probe"))
              (instrument/instrument-delta!
                {::instrument/changed-syms
                 #{'seon.agent.ctx.warnings-test/gap-probe
                   'seon.agent.ctx.warnings-test/async-gap-probe}
                 ::instrument/targets
                 [{::instrument/sym
                   'seon.agent.ctx.warnings-test/gap-probe
                   ::instrument/schema-form gap-probe-spec}
                  {::instrument/sym
                   'seon.agent.ctx.warnings-test/async-gap-probe
                   ::instrument/schema-form async-gap-probe-spec}]})
              (is (= [] (instrument/coverage-gaps rows)))
              (warnings/instrumentation-gaps-block
                {:seon.agent/id "root"
                 :seon.agent/entity {:seon.agent/id "root"}
                 ::db/db database}
                nil)))
          (.then (fn [out]
                   (is (= "" out))
                   (is (every? #(identical? database (::db/db %)) @requests))))
          (.catch (fn [e] (is false (str "threw — " e))))
          (.finally (fn [] (set! db/query original) (done)))))))
