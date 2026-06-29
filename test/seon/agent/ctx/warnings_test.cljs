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
    [seon.db :as db]))

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
