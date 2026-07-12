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
    [seon.ai.tokens :as tokens]
    [seon.client :as client]
    [seon.db :as db]
    [seon.eval :as seval]))

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

(deftest workspace-stub-reflects-configured-requires-not-const
  ;; Turn-0 regression: a FRESH agent (no home-ns source yet) renders the
  ;; workspace stub. Its `(ns … (:require …))` prose must reflect THIS agent's
  ;; CONFIG-RESOLVED requires ([[seon.eval/home-requires-for]]), NOT the const
  ;; default ([[seon.eval/home-ns-require-specs]]) the old 1-arg call used.
  (async done
    (-> (client/open-agent-conn!)
        (.then (fn [conn]
                 (binding [db/*conn* conn]
                   (let [fresh "tst-cfg-2606260000"
                         home  :my.agent.tst-cfg-2606260000]
                     (-> (db/transact! {:seon.db/tx-data [{:seon.agent/id fresh}]})
                         (.then (fn [_]
                                  (let [out       (nss/namespaces-block
                                                    {:seon.db/db @conn :seon.agent/id fresh})
                                        resolved  (seval/home-requires-for fresh)
                                        cfg-form  (seval/home-ns-form home resolved)
                                        const-form (seval/home-ns-form home)]
                                    (is (contains? (section-nses out) "my.agent.tst-cfg-2606260000")
                                        "the fresh agent's home ns renders (the workspace stub)")
                                    (is (str/includes? out cfg-form)
                                        "stub prose is built from the config-resolved requires")
                                    ;; Only meaningful when config actually diverges from the const.
                                    (when (not= cfg-form const-form)
                                      (is (not (str/includes? out const-form))
                                          "stub prose is NOT the stale const default"))))))))))
        (.then (fn [_] (done)) (fn [e] (is false (str "threw: " (.-message e))) (done))))))

(deftest compact-of-unindexed-ns-does-not-throw
  (async done
    (-> (with-seeded []
          (fn [conn]
            (let [card (nss/render-one-ns-compact {:seon.ns/name :ktest.absent :seon.db/db @conn})]
              (is (contains? (section-nses card) "ktest.absent")
                  "an un-indexed ns still renders a bracketed note, never throws"))))
        (.then (fn [_] (done)) (fn [e] (is false (str "threw: " (.-message e))) (done))))))
