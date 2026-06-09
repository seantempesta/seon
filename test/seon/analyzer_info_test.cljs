(ns seon.analyzer-info-test
  "Guard tests for `seon.analyzer-info/snapshot-defs`.

   Regression target (e2e-demo-findings-2026-06-08, the turn-killer):
   the self-host cljs analyzer-state grows a `nil` namespace key holding
   only the keyword-constants table (`:cljs.analyzer/constants`, written
   by `cljs.analyzer/register-constant!` when a bare keyword is analyzed
   with no enclosing ns). `snapshot-defs` declares its output as
   `[:map-of :symbol …]`, so the `nil` key made instrumentation throw
   `:malli.core/invalid-output` on EVERY per-turn pipeline call, aborting
   every agent turn. snapshot-defs must DROP non-symbol ns-keys so its
   output is a genuine `{symbol → …}` map.

   Pure unit test — builds a synthetic compile-state atom, no live pod.

   Run interactively via MCP eval:
     (require 'seon.analyzer-info-test :reload)
     (cljs.test/run-tests 'seon.analyzer-info-test)"
  (:require
    [cljs.test :as t :refer [deftest is]]
    [malli.core :as m]
    [seon.analyzer-info :as ai]))

(defn- fake-var-map [doc]
  {:meta {:doc doc} :fn-var true :arglists '(quote ([x]))})

(defn- compile-state-with-nil-key []
  ;; Mirrors the real self-host analyzer shape: a `nil` ns-key holding
  ;; only the constants table (no :defs), alongside real symbol-keyed nses.
  (atom
    {:cljs.analyzer/namespaces
     {nil {:cljs.analyzer/constants {:seen #{:enum :string}
                                     :order [:string :enum]}}
      'my.ns {:defs {'foo (fake-var-map "a fn")
                     'bar (fake-var-map "another")}}}}))

(deftest snapshot-defs-drops-nil-ns-key
  (let [cs   (compile-state-with-nil-key)
        snap (ai/snapshot-defs cs)]
    (is (not (contains? snap nil))
        "nil ns-key (constants-table artifact) must be dropped")
    (is (every? symbol? (keys snap))
        "every ns-key in the snapshot is a symbol")
    (is (contains? snap 'my.ns)
        "real symbol-keyed nses are retained")
    (is (= #{'foo 'bar} (set (keys (get snap 'my.ns))))
        "the real ns's defs are snapshotted")))

(deftest snapshot-defs-output-validates-schema
  ;; The whole point of the fix: instrumentation (which validates output
  ;; against ::defs-snapshot) must not throw on the nil-key case.
  (let [cs   (compile-state-with-nil-key)
        snap (ai/snapshot-defs cs)]
    (is (m/validate :seon.analyzer-info/defs-snapshot snap)
        "output is a valid ::defs-snapshot (map-of symbol -> map-of symbol int)")))

(deftest snapshot-defs-no-nil-and-no-throw-when-only-constants
  ;; Degenerate: analyzer-state that ONLY has the nil constants entry.
  (let [cs   (atom {:cljs.analyzer/namespaces
                    {nil {:cljs.analyzer/constants {:seen #{:x} :order [:x]}}}})
        snap (ai/snapshot-defs cs)]
    (is (= {} snap) "drops the lone nil key, yields the empty map")
    (is (m/validate :seon.analyzer-info/defs-snapshot snap))))
