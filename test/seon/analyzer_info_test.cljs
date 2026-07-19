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

;; ---------------------------------------------------------------------------
;; ns-require-edges — the M4 structural store's analyzer read: aliases are
;; the :requires KEYS mapping to a different target; refers are the :uses
;; keys grouped by target; self and :require-macros excluded.
;; ---------------------------------------------------------------------------

(defn- compile-state-with-requires []
  (atom
    {:cljs.analyzer/namespaces
     {'my.probe
      {:requires {'db 'seon.db, 'seon.db 'seon.db
                  'plan 'my.plan, 'my.plan 'my.plan
                  'plain.ns 'plain.ns
                  ;; self-require artifact — must be excluded
                  'my.probe 'my.probe}
       :uses {'wait 'seon.agent.lifecycle
              'complete 'seon.agent.lifecycle}
       :require-macros {'m 'my.macros}}}}))

(deftest ns-require-edges-reads-aliases-and-refers
  (let [edges (ai/ns-require-edges (compile-state-with-requires) 'my.probe)]
    (is (m/validate :seon.analyzer-info/require-edges edges)
        "output validates as ::require-edges")
    (is (= #{{:seon.ns.require/target 'seon.db
              :seon.ns.require/alias  'db}
             {:seon.ns.require/target 'my.plan
              :seon.ns.require/alias  'plan}
             {:seon.ns.require/target 'plain.ns}
             {:seon.ns.require/target 'seon.agent.lifecycle
              :seon.ns.require/refers #{'wait 'complete}}}
           edges)
        "aliases from :requires keys, refers grouped from :uses, self + macro-only deps excluded")))

(deftest ns-require-edges-empty-for-unknown-ns
  (is (= #{} (ai/ns-require-edges (atom {:cljs.analyzer/namespaces {}}) 'no.such))
      "unknown / never-eval'd ns yields the empty edge set"))

(deftest source-require-edges-match-the-structural-edge-contract
  (let [edges (ai/require-edges-from-source
                "(ns my.probe (:require [seon.db :as db]
                                        [seon.agent.lifecycle :refer [wait complete]]
                                        [my.types :as-alias types]
                                        [legacy.all :refer :all]
                                        plain.ns))")]
    (is (m/validate :seon.analyzer-info/require-edges edges))
    (is (= #{{:seon.ns.require/target 'seon.db
              :seon.ns.require/alias 'db}
             {:seon.ns.require/target 'seon.agent.lifecycle
              :seon.ns.require/refers #{'wait 'complete}}
             {:seon.ns.require/target 'my.types
              :seon.ns.require/alias 'types
              :seon.ns.require/as-alias? true}
             {:seon.ns.require/target 'legacy.all
              :seon.ns.require/refer-all? true}
             {:seon.ns.require/target 'plain.ns}}
           edges))))

(deftest source-require-edges-fail-soft
  (is (= #{} (ai/require-edges-from-source "not an ns form")))
  (is (= #{} (ai/require-edges-from-source "(ns broken"))))

(deftest namespace-info-derives-documentation-and-edges-once
  (let [source "(ns my.probe\n  \"Owns probe behavior.\\n\\nMore detail.\"\n  (:require [seon.db :as db]))"
        info (ai/namespace-info-from-source source)]
    (is (= "Owns probe behavior.\n\nMore detail." (:seon.ns/doc info)))
    (is (= "Owns probe behavior." (:seon.ns/summary info)))
    (is (= #{{:seon.ns.require/target 'seon.db
              :seon.ns.require/alias 'db}}
           (:seon.ns/require-edges info)))
    (is (m/validate :seon.analyzer-info/namespace-info info))))

(deftest namespace-info-remains-fail-soft-for-doc-quality
  (let [long-summary (apply str (repeat 120 "x"))
        info (ai/namespace-info-from-source
               (str "(ns my.long-doc \"" long-summary "\")"))]
    (is (= long-summary (:seon.ns/summary info))
        "style warnings never make namespace indexing fail")
    (is (m/validate :seon.analyzer-info/namespace-info info)))
  (is (= {:seon.ns/require-edges #{}}
         (ai/namespace-info-from-source "(ns broken"))))

;; ---------------------------------------------------------------------------
;; var-projection — owner-ns keys (C34): the projection map speaks
;; :seon.analyzer-info/* like every internal envelope, and validates
;; against its registered schema.
;; ---------------------------------------------------------------------------

(deftest var-projection-speaks-owner-ns-keys
  (let [proj (ai/var-projection
               {:name 'my.ns/foo :fn-var true
                :arglists '(quote ([x]))
                :meta {:doc "a fn" :private false
                       :seon.fn/agent-facing? true
                       :malli/schema [:=> [:cat :int] :int]}})]
    (is (m/validate :seon.analyzer-info/var-projection proj)
        "validates against the registered ::var-projection schema")
    (is (= {:seon.analyzer-info/sym      "my.ns/foo"
            :seon.analyzer-info/fn-var?  true
            :seon.analyzer-info/arglists "([x])"
            :seon.analyzer-info/doc      "a fn"
            :seon.analyzer-info/private? false
            :seon.analyzer-info/agent-facing? true
            :seon.analyzer-info/spec     "[:=> [:cat :int] :int]"}
           proj)
        "every key is :seon.analyzer-info/* — no bare keys; single-arity
         (quote …) arglists stripped")
    (is (not (contains? (ai/var-projection {:name 'my.ns/bare :fn-var true
                                            :arglists '(quote ([x]))
                                            :meta {}})
                        :seon.analyzer-info/spec))
        "unspecced var → ::spec ABSENT (optional = absent, never nil)")
    (is (not (contains? (ai/var-projection {:name 'my.ns/internal :fn-var true
                                            :arglists '(quote ([x]))
                                            :meta {}})
                        :seon.analyzer-info/agent-facing?))
        "unmarked var → eligibility ABSENT; false is never stored")))
