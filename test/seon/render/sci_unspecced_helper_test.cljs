(ns seon.render.sci-unspecced-helper-test
  "Two third-party-consumer bugs, proven IN-PROCESS (no live cluster) —
   mirrors the extra_core_test pattern (async + ensure-bootstrap! +
   open-agent-conn! → transact :seon.ns/:seon.fn rows → replay-program-graph!
   → eval).

   BUG A — SCI live-tile bounding missed UNSPECCED helpers.
     `expose-ns` enumerated a required ns's members ONLY from the `:seon.fn`
     index, which holds only SPECCED (`:malli/schema`-carrying) fns. A tile
     fn that calls an aliased UNSPECCED compiled helper (`h/format-count`,
     `format-count` having no spec → no index row) found no entry, SCI threw
     'Unable to resolve symbol', and the tile fell to the UNBOUNDED compiled
     path (a real downstream's live failure). The fix UNIONs the COMPILED
     members enumerated off the live ns object on `js/globalThis` with the
     index, so the unspecced helper resolves under SCI.

       BEFORE fix → invoke-bounded ⇒ {:seon.render.sci/fallthrough true}
       AFTER  fix → invoke-bounded ⇒ a real render map (:seon.render/hiccup)

   BUG B — third-party source silently un-indexed.
     `index-core!` indexes a consumer's own source ONLY via `!extra-core-vars`,
     which the consumer's SEON_EXTRA_PRELOAD entry ns must `(reset! …)`. If
     SEON_EXTRA_SRC is set but the reset! is omitted, ZERO downstream rows
     index — with no error. The fix emits ONE loud, actionable `:seon.log` warn
     naming SEON_EXTRA_SRC + the exact reset! one-liner the entry ns must run."
  (:require
    [cljs.test :refer [deftest is testing async]]
    [clojure.string :as str]
    [seon.client :as client]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.log :as log]
    [seon.render.sci :as sci]
    [seon.repl :as repl]))

;; ---------------------------------------------------------------------------
;; BUG A — UNSPECCED aliased helper resolves under SCI bounding.
;;
;; probe.helpers — its WHOLE source (ns + the UNSPECCED `format-count` defn)
;;   lives in :seon.ns/source, with NO :seon.fn row for format-count (the
;;   simulation of an unspecced / un-indexed compiled helper). Replay evals it
;;   onto globalThis; the `:seon.fn` index for probe.helpers stays EMPTY.
;;
;; probe.tile — its ns :require's [probe.helpers :as h]; `dash` (a :seon.fn row
;;   WITH source) calls (h/format-count …) and returns a hiccup tile.
;; ---------------------------------------------------------------------------

(def ^:private helpers-ns-source
  "(ns probe.helpers)\n(defn format-count [n] (str n \" items\"))")

(def ^:private tile-ns-source
  "(ns probe.tile (:require [probe.helpers :as h]))")

(def ^:private dash-source
  ;; Calls the aliased UNSPECCED helper — the exact shape that broke pre-fix.
  (str "(defn dash [in]\n"
       "  {:seon.render/hiccup [:div (h/format-count 3)]\n"
       "   :seon.render/ai \"probe dash\"})"))

(defn- seed-probe-tile!
  "Transact the probe.helpers + probe.tile rows. probe.tile stores
   :seon.ns/requires [:probe.helpers] so replay topo-orders helpers first."
  []
  (db/transact!
    {:seon.db/tx-data
     [{:seon.ns/name   :probe.helpers
       :seon.ns/source helpers-ns-source}
      {:seon.ns/name     :probe.tile
       :seon.ns/source   tile-ns-source
       :seon.ns/requires [:probe.helpers]}
      {:seon.fn/sym        "probe.tile/dash"
       :seon.fn/ns         {:seon.ns/name :probe.tile}
       :seon.fn/source     dash-source
       :seon.fn/created-at (js/Date.)}]}))

(deftest sci-bounds-tile-calling-unspecced-aliased-helper
  (async done
    (-> (js/Promise.all #js [(repl/ensure-bootstrap!) (client/open-agent-conn!)])
        (.then
          (fn [res]
            (let [cs   (aget res 0)
                  conn (aget res 1)]
              (binding [db/*conn* conn]
                (-> (seed-probe-tile!)
                    (.then
                      (fn [_]
                        ;; Replay evals probe.helpers + probe.tile onto
                        ;; globalThis (format-count lands COMPILED there).
                        (client/replay-program-graph!
                          {:conn conn :compile-state cs
                           :agent-id "sci-unspecced-test"})))
                    (.then
                      (fn [stats]
                        (testing "the probe nses replay cleanly"
                          (is (= 0 (:seon.client/replay-n-fail stats))
                              (str "replay had failures — " (pr-str stats))))
                        (testing "the UNSPECCED helper IS compiled onto globalThis"
                          (is (fn? (seval/lookup-value 'probe.helpers/format-count))
                              "format-count resolves via the globalThis walk"))
                        (testing "but is NOT in the :seon.fn index (unspecced)"
                          (is (empty?
                                (db/query
                                  '[:find ?s :where
                                    [?ns :seon.ns/name :probe.helpers]
                                    [?f :seon.fn/ns ?ns]
                                    [?f :seon.fn/sym ?s]]
                                  @conn))
                              "no :seon.fn row for any probe.helpers member"))
                        (testing "the tile fn itself IS resolvable + has stored source"
                          (is (fn? (seval/lookup-value 'probe.tile/dash))))
                        ;; THE ASSERTION — post-fix, invoke-bounded returns a
                        ;; REAL render map (not fallthrough), because expose-ns
                        ;; now unions the compiled helper.
                        (let [r (sci/invoke-bounded 'probe.tile/dash
                                                    {:seon.db/db @conn})]
                          (testing "invoke-bounded returns a real render map (NOT fallthrough)"
                            (is (not (:seon.render.sci/fallthrough r))
                                (str "fell through — the unspecced aliased helper "
                                     "did not resolve under SCI: " (pr-str r)))
                            (is (contains? r :seon.render/hiccup)
                                (str "expected a :seon.render/hiccup render map, got "
                                     (pr-str r)))
                            (is (= [:div "3 items"] (:seon.render/hiccup r))
                                "the helper ran under SCI and produced the tile"))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; BUG B — loud warn fires when SEON_EXTRA_SRC is set but no extras registered.
;;
;; index-core! is synchronous + builds tx-data; the warn is a fire-and-forget
;; log/warn!. We simulate SEON_EXTRA_SRC set (Node's process.env is mutable),
;; ensure !extra-core-vars is empty, capture log/warn! via with-redefs, run
;; index-core!, and assert the captured message names SEON_EXTRA_SRC and the
;; reset! one-liner.
;; ---------------------------------------------------------------------------

(deftest extra-src-set-but-unregistered-emits-loud-warn
  (let [before-extra @client/!extra-core-vars
        env          (.. js/globalThis -process -env)
        before-src   (aget env "SEON_EXTRA_SRC")
        captured     (atom [])]
    (try
      (reset! client/!extra-core-vars [])
      (aset env "SEON_EXTRA_SRC" "/tmp/acme-fake-root")
      (with-redefs [log/warn! (fn [data] (swap! captured conj data)
                                (js/Promise.resolve data))]
        (client/index-core!))
      (let [msg (some->> @captured
                         (filter #(= ::client/index-core! (:seon.log/source %)))
                         first
                         :seon.log/message)]
        (testing "exactly the extra-src warn fired"
          (is (some? msg) "a warn with source ::index-core! was emitted"))
        (testing "it names SEON_EXTRA_SRC and the value"
          (is (str/includes? (str msg) "SEON_EXTRA_SRC"))
          (is (str/includes? (str msg) "/tmp/acme-fake-root")))
        (testing "it gives the exact reset! one-liner the entry ns must run"
          (is (str/includes? (str msg) "reset! seon.client/!extra-core-vars"))
          (is (str/includes? (str msg) "specced-fn-vars"))
          (is (str/includes? (str msg) ":require-macros"))))
      (finally
        (reset! client/!extra-core-vars before-extra)
        (if (some? before-src)
          (aset env "SEON_EXTRA_SRC" before-src)
          (js-delete env "SEON_EXTRA_SRC"))))))

(deftest no-warn-when-extra-src-unset
  ;; The warn must NOT fire when SEON_EXTRA_SRC is absent (the normal
  ;; first-party pod) — observability only, no false alarms.
  (let [before-extra @client/!extra-core-vars
        env          (.. js/globalThis -process -env)
        before-src   (aget env "SEON_EXTRA_SRC")
        captured     (atom [])]
    (try
      (reset! client/!extra-core-vars [])
      (js-delete env "SEON_EXTRA_SRC")
      (with-redefs [log/warn! (fn [data] (swap! captured conj data)
                                (js/Promise.resolve data))]
        (client/index-core!))
      (is (empty? (filter #(= ::client/index-core! (:seon.log/source %)) @captured))
          "no extra-src warn when SEON_EXTRA_SRC is unset")
      (finally
        (reset! client/!extra-core-vars before-extra)
        (when (some? before-src)
          (aset env "SEON_EXTRA_SRC" before-src))))))
