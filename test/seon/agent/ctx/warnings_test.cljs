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
    [malli.instrument :as mi]
    [seon.agent.ctx.warnings :as warnings]
    [seon.client :as client]
    [seon.db :as db]
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

(defn- block-for
  "Render the warnings block the way the render engine calls it: the
   block's own map (carrying the scope override) arrives as
   :seon.render/node."
  [db scope-kw]
  (warnings/warnings-block
    {:seon.db/db       db
     :seon.agent/id    "wtest-agent"
     :seon.render/node {:seon.warn/ns scope-kw}}))

(deftest warnings-block-honors-scope-override-on-the-block-node
  (async done
    (-> (with-seeded-db
          (fn [db]
            (testing "override scoping the corpus checks to the defective ns RENDERS the warning"
              (let [out (block-for db :wtest.warns)]
                (is (not= "" out) "the block is non-empty when a defect is in scope")
                (is (str/includes? out "[no-malli-schema]")
                    "the no-malli-schema cluster renders")
                (is (str/includes? out "wtest.warns/no-spec")
                    "and names the specific affected fn")))
            (testing ":seon.warn/all widens scope to the whole core — still sees the defect"
              (is (str/includes? (block-for db :seon.warn/all) "wtest.warns/no-spec")))
            (testing "override scoping to a CLEAN ns renders empty — the condition is absent"
              (is (= "" (block-for db :wtest.clean))
                  "no defect in scope ⇒ empty string (self-healing, nothing stored)"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; Instrumentation-coverage invariant (C46) — `coverage-gaps` + its block.
;; ---------------------------------------------------------------------------

;; A REAL live compiled fn, deliberately WITHOUT `:malli/schema` metadata so
;; no collect!/boot pass ever wraps it — its spec exists only as the seeded
;; `:seon.fn/spec` row below. Unwrapped ⇒ a coverage gap.
(defn gap-probe [s] (str s))

(def ^:private gap-probe-spec [:=> [:cat :string] :string])

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
   ;; A STRUCTURAL async opt-out: `seon.db/transact!` is live, `^:async`,
   ;; multi-arity — `async-unwrappable?` ⇒ never a gap, must be EXCLUDED.
   {:seon.fn/sym     "seon.db/transact!"
    :seon.fn/ns      [:seon.ns/name :seon.agent.ctx.warnings-test]
    :seon.fn/source  "(defn ^:async transact! ...)"
    :seon.fn/fn-var? true
    :seon.fn/private? false
    :seon.fn/spec    "[:function [:=> [:cat [:map]] [:map]]]"}
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
    (-> (client/open-agent-conn!)
        (.then (fn [conn]
                 (binding [db/*conn* conn]
                   (-> (db/transact! {:seon.db/tx-data (coverage-seed-tx)})
                       (.then
                         (fn [_]
                           (let [dbv @conn]
                             (testing "the unwrapped specced fn IS a gap; opt-out + dead rows are NOT"
                               (let [gaps (instrument/coverage-gaps dbv)]
                                 (is (= ["seon.agent.ctx.warnings-test/gap-probe"]
                                        (mapv :seon.instrument/sym gaps)))
                                 (is (= [:seon.instrument/unwrapped]
                                        (mapv :seon.instrument/reason gaps)))))
                             (testing "the block renders the gap (root-world surface)"
                               (let [out (warnings/instrumentation-gaps-block {:seon.db/db dbv})]
                                 (is (str/includes? out "INSTRUMENTATION GAPS"))
                                 (is (str/includes? out "seon.agent.ctx.warnings-test/gap-probe"))))
                             (testing "re-asserting coverage self-heals: gap vanishes, block renders empty"
                               (instrument/register-target!
                                 'seon.agent.ctx.warnings-test 'gap-probe gap-probe-spec false)
                               (mi/instrument! {:filters [(mi/-filter-ns 'seon.agent.ctx.warnings-test)]
                                                :skip-instrumented? true})
                               (is (= [] (instrument/coverage-gaps dbv)))
                               (is (= "" (warnings/instrumentation-gaps-block
                                           {:seon.db/db dbv})))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
