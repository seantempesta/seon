(ns seon.store.gc
  "Extracted child namespace of `seon.store`."
  (:require [datahike.api :as d]
            [datahike.config]
            [datahike.connections]
            [datahike.ids]
            [datahike.store]
            [datahike.writing]
            [konserve.core :as k]
            [konserve.jolt.fasl-store]
            [konserve.jolt.store]
            [konserve.store]
            [jolt.fs :as fs]
            [datahike.datom :as dd]
            [seon.store.config :as config :refer [*capturing-agent-eval-reads?* config-val default-config-values]]))

(defn ^:seon.flow/edge rm-rf!
  "Recursively deletes a file or directory tree via symlink-safe `jolt.fs/delete-tree` (nil-safe, accepts File, Path, or path String)."
  {:malli/schema [:=> [:cat :any] :any]}
  [f]
  (when (or (instance? java.io.File f)
            (instance? java.nio.file.Path f)
            (and (string? f) (boolean (re-find #"\S" f))))
    (when (fs/exists? f)
      (fs/delete-tree f))))

(defn db-cache-sig
 "Cross-store cache key `[store-id branch commit-id max-tx time-point]` for live DB/AsOfDB."
 {:malli/schema [:=> [:cat :any] :any]
  :cost/ticks 50000}
 [db]
 (cond
  (instance? datahike.db.DB db)
  [(get-in db [:config :store :id]) (get-in db [:config :branch] :main)
   (d/commit-id db) (:max-tx db) nil]

  (instance? datahike.db.AsOfDB db)
  (when-let [orig (:origin-db db)]
   [(get-in orig [:config :store :id]) (get-in orig [:config :branch] :main)
    (when (instance? datahike.db.DB orig) (d/commit-id orig))
    (:max-tx orig) (:time-point db)])

  :else nil))

(defn cache-key
 "Cross-store LRU cache key from `(db-cache-sig db)` plus optional `extra`."
 {:malli/schema [:function [:=> [:cat :any] :any] [:=> [:cat :any [:sequential :any]] :any]]
  :cost/ticks 50000}
 ([db] (db-cache-sig db))
 ([db extra] (when-let [s (db-cache-sig db)] (into s extra))))

(defn fork-parent-target
  "Resolves branch-to-branch merge target for `branch` / `agent-id` honoring `:seon.agent/merge-target` (`:main`/`:none` -> nil, explicit branch/agent ref -> target, omitted -> parent branch of `:seon.agent/forked-from`)."
  {:malli/schema [:=> [:cat :seon.host/db :keyword [:maybe :keyword]] [:maybe :map]] :cost/ticks 1000000}
  [main-db branch agent-id]
  (let [dh (or (:schema main-db) {})]
    (when (and (contains? dh :seon.agent/forked-from) (contains? dh :seon.agent/id))
      (let [pat (cond-> [:seon.agent/id :seon.agent/branch :seon.agent/forked-from]
                  (contains? dh :seon.agent/merge-target) (conj :seon.agent/merge-target))
            ag-by-id (when agent-id
                       (let [a (d/pull main-db pat [:seon.agent/id agent-id])]
                         (when (= branch (:seon.agent/branch a)) a)))
            ag (or ag-by-id
                   (when (and branch (not= branch :main) (contains? dh :seon.agent/branch))
                     (some #(let [a (d/pull main-db pat (:e %))]
                              (when (= branch (:seon.agent/branch a)) a))
                           (d/datoms main-db :aevt :seon.agent/forked-from))))
            mt (:seon.agent/merge-target ag)]
        (cond
          (or (= mt :main) (= mt :none)) nil

          (some? mt)
          (let [ta (cond
                     (integer? mt) (d/pull main-db (quote [:seon.agent/id :seon.agent/branch]) mt)
                     (and (map? mt) (integer? (:db/id mt))) (d/pull main-db (quote [:seon.agent/id :seon.agent/branch]) (:db/id mt))
                     (and (vector? mt) (= 2 (count mt)) (= :seon.agent/id (first mt)))
                     (d/pull main-db (quote [:seon.agent/id :seon.agent/branch]) mt)
                     (qualified-keyword? mt)
                     (let [a (d/pull main-db (quote [:seon.agent/id :seon.agent/branch]) [:seon.agent/id mt])]
                       (if (:seon.agent/id a) a {:seon.agent/branch mt}))
                     :else nil)
                tb (:seon.agent/branch ta)]
            (when (and (:seon.agent/id ag) tb (not= tb :main) (not= tb branch))
              {:agent-id (:seon.agent/id ag) :target-id (:seon.agent/id ta) :target-branch tb}))

          :else
          (let [feid (let [f (:seon.agent/forked-from ag)] (if (map? f) (:db/id f) f))
                pa (when (integer? feid) (d/pull main-db (quote [:seon.agent/id :seon.agent/branch]) feid))
                tb (:seon.agent/branch pa)]
            (when (and (:seon.agent/id ag) (:seon.agent/id pa) tb (not= tb :main) (not= tb branch))
              {:agent-id (:seon.agent/id ag) :target-id (:seon.agent/id pa) :target-branch tb})))))))

(defn sha256-hex
  "Deterministic lowercase SHA-256 hex digest of UTF-8 string `s` (full 64 chars, or first `n` chars)."
  {:malli/schema [:=> {:examples [[["hello"] "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"]
                                  [["hello" 16] "2cf24dba5fb0a30e"]]}
                  [:cat :string [:? [:int {:min 1 :max 64}]]] :string]
   :cost/ticks 200000
   :cost/bytes 131072}
  ([^String s] (sha256-hex s 64))
  ([^String s n]
   (let [bs (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes (str s) "UTF-8"))
         nb (min (alength bs) (quot (inc (long n)) 2))
         sb (StringBuilder. (* 2 nb))]
     (dotimes [i nb]
       (let [b (bit-and 0xff (aget bs i))]
         (when (< b 16) (.append sb \0))
         (.append sb (Integer/toHexString b))))
     (let [hx (.toString sb)]
       (if (< (long n) (count hx)) (subs hx 0 (long n)) hx)))))

(defn db-schema
  "Returns the Datahike schema map for `db` (handling `DB`, `AsOfDB`, `HistoricalDB`, or plain map), else `{}`."
  {:malli/schema [:=> [:cat :any] :map]
   :cost/ticks 100000}
  [db]
  (cond
    (instance? datahike.db.interface.ISearch db) (or (:schema db) (d/schema db) {})
    (and (map? db) (map? (:schema db))) (:schema db)
    :else {}))

(defn ^:seon.flow/edge prune-merged-branches!
  "Deletes already-merged, non-protected, non-active branches beyond `:seon.config/gc-keep-merged-branches` so old branches never pin LMDB storage indefinitely."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/conn] :map]
                  [:=> [:cat :seon.host/conn nat-int?] :map]]
   :cost/ticks 15000000}
  ([conn]
   (prune-merged-branches! conn
     (long (or (config-val @conn nil :seon.config/gc-keep-merged-branches)
               (:seon.config/gc-keep-merged-branches default-config-values)))))
  ([conn keep-n]
   (let [mdb @conn
       protected #{:db :main :branch/repl}
       all-brs (set (d/branches mdb))
       ags (mapv #(d/pull mdb '[:db/id :seon.agent/branch :seon.agent/merged-tx :seon.agent/archived?] (:e %))
                 (d/datoms mdb :aevt :seon.agent/id))
       active-brs (into #{} (keep (fn [ag]
                                    (when (and (:seon.agent/branch ag)
                                               (nil? (:seon.agent/merged-tx ag))
                                               (not (:seon.agent/archived? ag)))
                                      (:seon.agent/branch ag))))
                        ags)
       merged (->> ags
                   (keep (fn [ag]
                           (let [b (:seon.agent/branch ag)
                                 mtx (:seon.agent/merged-tx ag)]
                             (when (and b (all-brs b) (not (protected b)) (not (active-brs b)) (or mtx (:seon.agent/archived? ag)))
                             {:branch b :merged-tx (long (or mtx 0))}))))
                   (group-by :branch)
                   (map (fn [[b xs]] {:branch b :merged-tx (apply max (map :merged-tx xs))}))
                   (sort-by :merged-tx >)
                   vec)
       to-del (mapv :branch (drop (long keep-n) merged))
       to-del-set (set to-del)
       archive-tx (vec (keep (fn [ag]
                               (when (and (contains? to-del-set (:seon.agent/branch ag))
                                          (not (:seon.agent/archived? ag)))
                                 {:db/id (:db/id ag) :seon.agent/archived? true}))
                             ags))
       _ (when (seq archive-tx)
           (d/transact conn archive-tx))
       deleted (volatile! [])]
   (doseq [b to-del]
     (try (d/delete-branch! conn b) (vswap! deleted conj b) (catch Throwable _ nil)))
   (with-meta {:ok? true :deleted (count @deleted) :archived (count archive-tx) :kept (min (count merged) (long keep-n))} {:pruned-branches @deleted}))))

(defn ^:seon.flow/edge gc-store!
  "Prunes merged branches beyond `:seon.config/gc-keep-merged-branches` and runs `d/gc-storage` with cutoff `:seon.config/gc-history-hours` ago (preserving active branch merge-bases)."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/conn] :map]
                  [:=> [:cat :seon.host/conn [:maybe :map]] :map]]
   :cost/ticks 25000000}
  ([conn] (gc-store! conn nil))
  ([conn opts]
   (let [mdb @conn
         keep-n (long (or (:keep opts)
                          (config-val mdb nil :seon.config/gc-keep-merged-branches)
                          (:seon.config/gc-keep-merged-branches default-config-values)))
         hrs (long (or (:history-hours opts)
                       (config-val mdb nil :seon.config/gc-history-hours)
                       (:seon.config/gc-history-hours default-config-values)))
         pruned (prune-merged-branches! conn keep-n)
         cutoff (java.util.Date. (- (System/currentTimeMillis) (* hrs 3600 1000)))
         swept @(d/gc-storage conn cutoff)
         n-swept (if (coll? swept) (count swept) swept)]
     {:ok? true :pruned pruned :pruned-branches (:pruned-branches (meta pruned) []) :swept n-swept :history-hours hrs})))

(defn resolve-lazy-str
  "Resolves an inline or out-of-line Datahike lazy datom value `v` to a string (nil when `v` is nil)."
  {:malli/schema [:function
                  [:=> [:cat :any] [:maybe :string]]
                  [:=> [:cat [:maybe :seon.host/db] :any] [:maybe :string]]]
   :cost/ticks 500000}
  ([v] (resolve-lazy-str nil v))
  ([db v]
   (cond
     (nil? v) nil
     (dd/lazy-value? v)
     (let [s (:store db)
           r (when s (k/get s (dd/lazy-key v) :seon.store/miss {:sync? true}))
           val (if (and (some? r) (not (identical? :seon.store/miss r))) r (dd/value-of v))]
       (when (some? val) (str val)))
     :else (str v))))

(defn tx-instant-ms
  "Returns epoch milliseconds for Datahike transaction id `tx` via `:db/txInstant`, or nil."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :int]] [:maybe :int]]
   :cost/ticks 500000}
  [db tx]
  (when (and db (integer? tx) (pos? (long tx)))
    (when-let [inst (:v (first (d/datoms db :eavt (long tx) :db/txInstant)))]
      (cond
        (instance? java.util.Date inst) (.getTime ^java.util.Date inst)
        (number? inst) (long inst)
        :else nil))))
