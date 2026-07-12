(ns seon.db.pull-guard-test
  "Pins `seon.db/pull`'s uninstalled-attr guard + the consolidated
   `seon.db/installed-schema` (base-system solidity unit, 2026-06-11).

   THE TRAP under test (verified live by T5): datahike installs an
   attr's schema lazily at its FIRST transact!, so raw `d/pull` THROWS
   a cryptic `:transact/schema` resolve-datom error (\"Bad entity
   attribute … not defined in current schema\") whenever an EXPLICIT
   pull pattern names a registered-but-never-transacted attr. The
   contract pinned here:

   1. Valid pulls (every named attr installed) are byte-identical to
      raw d/pull; `'[*]` never throws.
   2. REGISTERED-but-uninstalled attrs in explicit patterns are
      silently filtered — equivalent to installed-with-zero-rows (key
      absent), top-level AND inside nested map specs.
   3. UNREGISTERED uninstalled attrs (typos) throw a LEGIBLE error
      naming the attr and the fix — never the raw resolve-datom text.
   4. `installed-schema` reflects install state (registered-only attr
      absent until first transact) and is nil-safe.

   Run via bin/test-cljs or seon.test.runner over MCP."
  (:require
    [cljs.test :refer [deftest is async]]
    [datahike.api :as d]
    [seon.agent]
    [seon.agent.message]
    [seon.db :as db]
    [seon.schema :as schema]
    [seon.test.async :refer [settle!]]))

;; ---------------------------------------------------------------------------
;; Test schemas — isolated under this ns's keyword namespace.
;; ---------------------------------------------------------------------------

(schema/register! ::name :string)
(schema/register! ::rank :int)
;; Registered but NEVER transacted in any test — the lazy-install trap.
(schema/register! ::never-transacted :string)
(schema/register! ::also-never :int)
;; Ref + child attrs for the nested map-spec case.
(schema/register! ::pet :seon.db/ref)
(schema/register! ::pet-name :string)

(defn- fresh-conn
  "Promise of a fresh :memory datahike conn (schema-on-write, no
   history)."
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      false}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (db/ensure-provenance! {:seon.db/conn conn})
                     (.then (fn [_] conn))))))))

(defn- seeded-conn
  "Promise of [conn eid]: a fresh conn holding one entity with ::name,
   ::rank, and a ::pet ref to a child carrying ::pet-name."
  []
  (-> (fresh-conn)
      (.then (fn [conn]
               ;; Child entity is its own TOP-LEVEL map — nested-only
               ;; attrs are not auto-installed (envelope_test §4 pins
               ;; that gap as a translated error).
               (-> (db/transact!
                     {:seon.db/tx-data [{:db/id -1
                                         ::name "Alpha"
                                         ::rank 1
                                         ::pet  -2}
                                        {:db/id -2
                                         ::pet-name "Rex"}]
                      :seon.db/conn    conn})
                   (.then (fn [{ok? :seon.db/ok? error :seon.db/error}]
                            (is (true? ok?)
                                (str "seed transact must succeed — "
                                     (:seon.error/message error)))
                            (let [eid (ffirst
                                        (db/query
                                          {:seon.db/query
                                           '[:find ?e :where [?e ::name _]]
                                           :seon.db/conn conn}))]
                              [conn eid]))))))))

;; ---------------------------------------------------------------------------
;; 1. Valid pulls unchanged.
;; ---------------------------------------------------------------------------

(deftest valid-pulls-unchanged
  (async done
    (-> (seeded-conn)
        (.then (fn [[conn eid]]
                 (let [dbv @conn]
                   (is (= {::name "Alpha" ::rank 1}
                          (db/pull dbv [::name ::rank] eid))
                       "explicit installed pattern, positional arity")
                   (is (= {::name "Alpha"}
                          (db/pull {:seon.db/pull-pattern [::name]
                                    :seon.db/ref          eid
                                    :seon.db/db           dbv}))
                       "explicit installed pattern, map arity")
                   (is (= "Alpha" (::name (db/pull dbv '[*] eid)))
                       "wildcard pull works")
                   (is (= {::pet {::pet-name "Rex"}}
                          (db/pull dbv [{::pet [::pet-name]}] eid))
                       "nested map-spec over installed attrs"))))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; 2. Registered-but-uninstalled attrs are filtered, never thrown.
;; ---------------------------------------------------------------------------

(deftest registered-uninstalled-attr-is-filtered
  (async done
    (-> (seeded-conn)
        (.then (fn [[conn eid]]
                 (let [dbv @conn]
                   (is (= {::name "Alpha"}
                          (db/pull dbv [::name ::never-transacted] eid))
                       "uninstalled attr filtered; key absent like a no-data attr")
                   (is (= {::name "Alpha"}
                          (db/pull {:seon.db/pull-pattern [::name ::never-transacted]
                                    :seon.db/ref          eid
                                    :seon.db/db           dbv}))
                       "map arity takes the same guard")
                   (is (= "Alpha"
                          (::name (db/pull dbv [:db/id '* ::never-transacted] eid)))
                       "wildcard + uninstalled attr still pulls; :db/id exempt")
                   (is (nil? (db/pull dbv [::never-transacted ::also-never] eid))
                       "pattern filtered to empty ⇒ nil, no datahike call")
                   (is (= {::name "Alpha"}
                          (db/pull dbv [::name {::pet [::never-transacted]}] eid))
                       "map-spec whose subpattern filters to empty is dropped")
                   (is (= {::name "Alpha" ::pet {::pet-name "Rex"}}
                          (db/pull dbv
                                   [::name {::pet [::pet-name ::never-transacted]}]
                                   eid))
                       "uninstalled attr filtered inside nested subpattern"))))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; 3. Unregistered attrs (typos) throw a LEGIBLE error.
;; ---------------------------------------------------------------------------

(deftest unregistered-attr-throws-legibly
  (async done
    (-> (seeded-conn)
        (.then (fn [[conn eid]]
                 (let [dbv @conn
                       err (try (db/pull dbv [::name :seon.db.pull-guard-test/typo-atr] eid)
                                nil
                                (catch :default e e))]
                   (is (some? err) "typo attr must throw, not pull")
                   (when err
                     (let [msg  (.-message err)
                           data (ex-data err)]
                       (is (re-find #":seon.db.pull-guard-test/typo-atr" msg)
                           "message names the offending attr")
                       (is (re-find #"seon.schema/register!" msg)
                           "message names the fix")
                       (is (re-find #"installed-schema" msg)
                           "message points at the gate")
                       (is (not (re-find #"resolve-datom" msg))
                           "never the raw datahike resolve-datom text")
                       (is (= :user-input (:seon.error/kind data))
                           "kind is FLAT in ex-data (the ONE convention, C43)"))))))
        (settle! done))))

;; ---------------------------------------------------------------------------
;; 4. installed-schema reflects install state; nil-safe.
;; ---------------------------------------------------------------------------

(deftest installed-schema-reflects-install-state
  (async done
    (-> (seeded-conn)
        (.then (fn [[conn _eid]]
                 (let [installed (db/installed-schema @conn)]
                   (is (contains? installed ::name)
                       "transacted attr is installed")
                   (is (not (contains? installed ::never-transacted))
                       "registered-but-never-transacted attr is NOT installed")
                   (is (= {} (db/installed-schema nil))
                       "nil-safe: {} for a nil db"))))
        (settle! done))))
