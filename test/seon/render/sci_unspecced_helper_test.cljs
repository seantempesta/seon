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
                          {:seon.client/conn conn :seon.client/compile-state cs
                           :seon.client/agent-id "sci-unspecced-test"})))
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
                          (testing "invoke-bounded returns a real render map (NOT an error)"
                            (is (not (:seon.render.sci/error r))
                                (str "bounding failed — the unspecced aliased helper "
                                     "did not resolve under SCI: " (pr-str r)))
                            (is (contains? r :seon.render/hiccup)
                                (str "expected a :seon.render/hiccup render map, got "
                                     (pr-str r)))
                            (is (= [:div "3 items"] (:seon.render/hiccup r))
                                "the helper ran under SCI and produced the tile"))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; BUG A-2 — own-ns NON-fn DATA constant resolves under SCI bounding.
;;
;; The same class as BUG A, one layer deeper: `expose-ns` enumerated own-ns
;; (and required-ns) members ONLY as FNS (`ns-fn-members` filters on `fn?`)
;; plus the SPECCED index. A tile fn that references a top-level NON-fn
;; `(def grounded-dims #{…})` data constant in its OWN ns found no entry, SCI
;; threw 'Unable to resolve symbol', and the tile fell to the UNBOUNDED
;; compiled path. The fix adds `seon.eval/ns-data-members` (the data twin of
;; `ns-fn-members`, same globalThis munge/demunge scheme) and merges it into
;; the SCI ns map, so the data const resolves under SCI.
;;
;;   BEFORE fix → invoke-bounded ⇒ {:seon.render.sci/fallthrough true}
;;   AFTER  fix → invoke-bounded ⇒ a real render map (:seon.render/hiccup)
;;
;; data.tile — one ns, no requires: a top-level `(def grounded-dims #{…})` in
;;   :seon.ns/source (NO :seon.fn row — it is not a fn) and a SPECCED-shape
;;   `dims` fn (a :seon.fn row WITH source) that reads `grounded-dims` by
;;   simple name and renders `(count grounded-dims)`.
;; ---------------------------------------------------------------------------

(def ^:private data-tile-ns-source
  ;; The own-ns NON-fn data const + the tile fn live in ONE ns. The const has
  ;; NO :seon.fn row; replay evals the whole ns source so the const lands as a
  ;; non-fn own member on globalThis (the exact case ns-data-members captures).
  (str "(ns data.tile)\n"
       "(def grounded-dims #{:a :b :c})"))

(def ^:private dims-source
  ;; Reads the own-ns data const by simple name — the shape that broke pre-fix.
  (str "(defn dims [in]\n"
       "  {:seon.render/hiccup [:div (count grounded-dims)]\n"
       "   :seon.render/ai \"data dims\"})"))

(defn- seed-data-tile!
  "Transact the data.tile ns row (carrying the `grounded-dims` def in its
   source) + the `dims` :seon.fn row."
  []
  (db/transact!
    {:seon.db/tx-data
     [{:seon.ns/name   :data.tile
       :seon.ns/source data-tile-ns-source}
      {:seon.fn/sym        "data.tile/dims"
       :seon.fn/ns         {:seon.ns/name :data.tile}
       :seon.fn/source     dims-source
       :seon.fn/created-at (js/Date.)}]}))

(deftest sci-bounds-tile-reading-own-ns-data-const
  (async done
    (-> (js/Promise.all #js [(repl/ensure-bootstrap!) (client/open-agent-conn!)])
        (.then
          (fn [res]
            (let [cs   (aget res 0)
                  conn (aget res 1)]
              (binding [db/*conn* conn]
                (-> (seed-data-tile!)
                    (.then
                      (fn [_]
                        ;; Replay evals data.tile onto globalThis (grounded-dims
                        ;; lands as a NON-fn own member there).
                        (client/replay-program-graph!
                          {:seon.client/conn conn :seon.client/compile-state cs
                           :seon.client/agent-id "sci-data-const-test"})))
                    (.then
                      (fn [stats]
                        (testing "the data.tile ns replays cleanly"
                          (is (= 0 (:seon.client/replay-n-fail stats))
                              (str "replay had failures — " (pr-str stats))))
                        (testing "the data const IS on globalThis as a NON-fn member"
                          (is (= #{:a :b :c}
                                 (seval/lookup-value 'data.tile/grounded-dims))
                              "grounded-dims resolves via the globalThis walk")
                          (is (contains? (seval/ns-data-members "data.tile")
                                         'grounded-dims)
                              "ns-data-members enumerates the non-fn const")
                          (is (not (contains? (seval/ns-fn-members "data.tile")
                                              'grounded-dims))
                              "ns-fn-members (fns only) does NOT — the gap the fix closes"))
                        (testing "the tile fn itself IS resolvable + has stored source"
                          (is (fn? (seval/lookup-value 'data.tile/dims))))
                        ;; THE ASSERTION — post-fix, invoke-bounded returns a
                        ;; REAL render map (not fallthrough), because expose-ns
                        ;; now merges the own-ns NON-fn data const.
                        (let [r (sci/invoke-bounded 'data.tile/dims
                                                    {:seon.db/db @conn})]
                          (testing "invoke-bounded returns a real render map (NOT an error)"
                            (is (not (:seon.render.sci/error r))
                                (str "bounding failed — the own-ns data const did "
                                     "not resolve under SCI: " (pr-str r)))
                            (is (contains? r :seon.render/hiccup)
                                (str "expected a :seon.render/hiccup render map, got "
                                     (pr-str r)))
                            (is (= [:div 3] (:seon.render/hiccup r))
                                "grounded-dims resolved under SCI → (count …) = 3"))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; BUG A-3 — a DYNAMIC var reached through a require alias (`db/*conn*`)
;; resolves under SCI bounding. The exact my.plan.internal/plan-block shape:
;; the ns requires [seon.db :as db], the tile fn derefs `db/*conn*`. Pre-fix
;; this needed the ns's REAL source stored (`full-source-ns?` excluded hidden
;; my.*.internal → a stub with no :require → no `db` alias in the cage) AND
;; `*conn*` enumerated off the live seon.db ns object (ns-data-members).
;; No replay needed: seon.db is compiled/live; the tile fn is interpreted
;; from its stored source by invoke-bounded itself.
;; ---------------------------------------------------------------------------

(def ^:private conn-tile-ns-source
  "(ns probe.conn-tile (:require [seon.db :as db]))")

(def ^:private conn-dash-source
  ;; Derefs the aliased DYNAMIC var — the exact shape that broke plan-block.
  (str "(defn conn-dash [in]\n"
       "  (let [d (or (:seon.db/db in) @db/*conn*)]\n"
       "    {:seon.render/hiccup [:div (str (some? d))]\n"
       "     :seon.render/ai \"conn dash\"}))"))

(deftest sci-bounds-tile-derefing-aliased-dynamic-var
  (async done
    (-> (js/Promise.all #js [(repl/ensure-bootstrap!) (client/open-agent-conn!)])
        (.then
          (fn [res]
            (let [conn (aget res 1)]
              (binding [db/*conn* conn]
                (-> (db/transact!
                      {:seon.db/tx-data
                       [{:seon.ns/name   :probe.conn-tile
                         :seon.ns/source conn-tile-ns-source}
                        {:seon.fn/sym        "probe.conn-tile/conn-dash"
                         :seon.fn/ns         {:seon.ns/name :probe.conn-tile}
                         :seon.fn/source     conn-dash-source
                         :seon.fn/created-at (js/Date.)}]})
                    (.then
                      (fn [_]
                        (testing "*conn* IS an enumerable NON-fn member of the live seon.db ns"
                          (is (contains? (seval/ns-data-members "seon.db") '*conn*)
                              "ns-data-members demunges _STAR_conn_STAR_ → *conn*"))
                        (let [r (sci/invoke-bounded 'probe.conn-tile/conn-dash
                                                    {:seon.db/db @conn})]
                          (testing "the aliased dynamic var resolves — the tile runs BOUNDED"
                            (is (not (:seon.render.sci/error r))
                                (str "bounding failed — db/*conn* did not resolve "
                                     "under SCI: " (pr-str r)))
                            (is (= [:div "true"] (:seon.render/hiccup r))
                                "@db/*conn* deref'd to a live conn under SCI"))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; FAIL-LOUD — a my.* render fn SCI cannot run yields the ::error envelope,
;; NEVER the old fallthrough-to-the-unbounded-compiled-path (owner ruling,
;; 2026-07-02: bounded rendering is a safety property; a fn that can't be
;; bounded renders a :seon/error block in place). The broken fn references an
;; alias its ns never required → SCI resolution fails → {::error …} with a
;; legible :seon.error/message.
;; ---------------------------------------------------------------------------

(deftest sci-bounding-failure-is-fail-loud-error-not-fallthrough
  (async done
    (-> (js/Promise.all #js [(repl/ensure-bootstrap!) (client/open-agent-conn!)])
        (.then
          (fn [res]
            (let [conn (aget res 1)]
              (binding [db/*conn* conn]
                (-> (db/transact!
                      {:seon.db/tx-data
                       [{:seon.ns/name   :probe.broken
                         :seon.ns/source "(ns probe.broken)"}
                        {:seon.fn/sym        "probe.broken/bad-tile"
                         :seon.fn/ns         {:seon.ns/name :probe.broken}
                         :seon.fn/source
                         (str "(defn bad-tile [in]\n"
                              "  {:seon.render/hiccup [:div (bogus/x 1)]\n"
                              "   :seon.render/ai \"bad\"})")
                         :seon.fn/created-at (js/Date.)}]})
                    (.then
                      (fn [_]
                        (let [r (sci/invoke-bounded 'probe.broken/bad-tile
                                                    {:seon.db/db @conn})]
                          (testing "the failure is the ::error envelope"
                            (is (map? (:seon.render.sci/error r))
                                (str "expected {:seon.render.sci/error …}, got "
                                     (pr-str r)))
                            (is (string? (get-in r [:seon.render.sci/error
                                                    :seon.error/message]))
                                "the error carries a legible message"))
                          (testing "the old fallthrough key is GONE (no unbounded path)"
                            (is (not (contains? r :seon.render.sci/fallthrough))
                                "fallthrough-to-compiled was removed"))))))))))
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
          (is (str/includes? (str msg) "public-fn-vars"))
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
