(ns seon.code.edit.ns
  "Namespace require management, diffing, and topological form ordering for `seon.code.edit`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.code.parse :as parse]
            [seon.ctx :as ctx]
            [seon.fork.calls :as fcalls :refer [*agent-ctx*]]
            [seon.health :as health]
            [seon.store :as store]
            [seon.code.cst :as cst]
            [seon.code.cst.ns :as cst-ns]
            [seon.code.io :as cio]))

(defn ^{:seon.code/reexport 'seon.code.io/resolve-form-target} resolve-form-target
  "Re-exports `seon.code.io/resolve-form-target`."
  {:malli/schema [:=> [:cat :seon.host/conn :keyword :any] :map] :cost/ticks 5000000}
  [conn branch target] (cio/resolve-form-target conn branch target))

(defn unused-requires
  "AST-accurate analysis of used vs unused `:require` specs in namespace `nsym`."
  {:malli/schema [:=> [:cat :any] [:maybe :map]], :seon.flow/edge true}
  [nsym]
  (when-let [{:keys [conn branch]} *agent-ctx*]
    (let [s (if (string? nsym) (symbol nsym) nsym)
          fsrc (cio/merged-ns-src conn branch s)]
      (if (str/blank? fsrc)
        {:ok? false :error :ns-not-found :ns s}
        (let [analysis (cst-ns/analyze-ns-requires fsrc)]
          {:ok? true
           :ns s
           :unused (vec (map :lib (remove :keep? analysis)))
           :kept (vec (map :lib (filter :keep? analysis)))
           :detail analysis})))))

(defn prune-requires!
  "AST-accurately removes unused `:require` specs from `nsym`, persisting to Datahike and
   returning a compact diff of ONLY the `(ns ...)` form (never the entire file)."
  {:malli/schema [:function
                  [:=> [:cat :any] [:maybe :map]]
                  [:=> [:cat :any :map] [:maybe :map]]]}
  ([nsym] (prune-requires! nsym {}))
  ([nsym {:keys [dry-run?]}]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [s (if (string? nsym) (symbol nsym) nsym)
           {:keys [path key src]} (resolve-form-target conn branch s)
           fsrc (cio/merged-ns-src conn branch s)]
       (if (str/blank? fsrc)
         {:ok? false :error :ns-not-found :ns s}
         (let [res (cst-ns/prune-unused-requires-src fsrc)
               new-ns-src (when (:changed? res)
                            (some-> (parse/read-clj (or path (str s)) (:src res))
                                    :forms first :seon.code.form/src))
               udiff (if (:changed? res)
                       (cst/unified-diff (or src "") (or new-ns-src "") (or key (str s "#ns")))
                       "")]
           (cond
             (not (:changed? res))
             {:ok? true :changed? false :ns s :path path :removed [] :kept (:kept res) :diff ""}

             dry-run?
             {:ok? true :changed? true :dry-run? true :ns s :path path
              :removed (:removed res) :kept (:kept res) :diff udiff}

             :else
             (let [tx (code/file-import-tx @conn path (:src res) false)
                   r (d/transact conn {:tx-data tx})]
               (ctx/refresh-active! (:db-after r) (:tx-data r))
               {:ok? true :ns s :path path :changed? true
                :removed (:removed res) :kept (:kept res)
                :tx (:max-tx (:db-after r)) :diff udiff}))))))))

(defn diff
  "Returns branch upserts, retractions, schemas, and compact unified diffs vs merge-base.
   Fast by default (`:health? false`, < 15 ms); pass `{:health? true}` or `(diff 'ns/fn {:health? true})`
   to include full `seon.health/caused-by-branch` checks."
  {:malli/schema [:function
                  [:=> [:cat] [:maybe :map]]
                  [:=> [:cat :any] [:maybe :map]]
                  [:=> [:cat :any :map] [:maybe :map]]]
   :seon.eval/budget-override {:ticks 300000000, :bytes 1073741824}}
  ([] (diff nil {}))
  ([target-or-opts]
   (if (map? target-or-opts)
     (diff nil target-or-opts)
     (diff target-or-opts {})))
  ([target {:keys [health?] :or {health? false}}]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [bdb @conn
           mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))
           base-cid (when (not= branch :main) (try (d/merge-base conn :main branch) (catch Throwable _ nil)))
           base-db (when base-cid (d/commit-as-db conn base-cid))
           ops (if base-cid
                 (code/changed-form-ops conn base-cid (d/commit-id bdb))
                 {:upserts [] :retracted-keys #{} :schema-keys #{}})
           errs (if (and health? (not= branch :main))
                  (health/caused-by-branch mdb bdb {})
                  [])
           upserts (if target
                     (let [k (str target)]
                       (filterv #(= k (:seon.code.form/key %)) (:upserts ops)))
                     (:upserts ops))
           diffs (into {}
                       (keep (fn [{:keys [:seon.code.form/key :seon.code.form/src]}]
                               (let [old-s (when base-db
                                             (:seon.code.form/src (d/pull base-db '[:seon.code.form/src] [:seon.code.form/key key])))]
                                 [key (cst/unified-diff (or old-s "") (or src "") key)])))
                       (take 12 upserts))]
       {:ok? true
        :branch branch
        :upserts (vec (sort (map :seon.code.form/key (:upserts ops))))
        :retracted (vec (sort (:retracted-keys ops)))
        :schemas (vec (sort (:schema-keys ops)))
        :health-checked? (boolean health?)
        :health-errors errs
        :diffs diffs}))))

(defn add-require!
  "Idempotently adds one or more `:require` specs to `nsym`, persists `#ns`, and live-requires."
  {:malli/schema [:function
                  [:=> [:cat :any :any] [:maybe :map]]
                  [:=> [:cat :any :any :map] [:maybe :map]]]}
  ([nsym spec-or-specs] (add-require! nsym spec-or-specs {}))
  ([nsym spec-or-specs {:keys [dry-run? eval?] :or {eval? true}}]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [s (if (string? nsym) (symbol nsym) nsym)
           {:keys [key path src]} (resolve-form-target conn branch s)]
       (if (str/blank? (str src))
         {:ok? false :error :ns-not-found :ns s}
         (let [res (cst-ns/add-require-src src spec-or-specs)]
           (cond
             (not (:ok? res)) (assoc res :ns s)
             (not (:changed? res)) {:ok? true :ns s :changed? false :added [] :diff ""}
             dry-run? {:ok? true :dry-run? true :ns s :added (:added res) :diff (cst/unified-diff src (:src res) key)}
             :else
             (let [new-ns-src (:src res)
                   file-src (cio/merged-ns-src conn branch s)
                   idx (.indexOf ^String file-src ^String src)
                   tx (when-not (neg? idx)
                        (code/file-import-tx @conn path
                                             (str (subs file-src 0 idx) new-ns-src (subs file-src (+ idx (count src))))
                                             false))]
               (if-not tx
                 {:ok? false :error :source-sync-mismatch :ns s}
                 (let [r (d/transact conn {:tx-data tx})
                       eval-err (when eval?
                                  (try
                                    (binding [*ns* *ns*]
                                      (eval (read-string new-ns-src)))
                                    nil
                                    (catch Throwable ex (or (.getMessage ex) (str ex)))))]
                   (ctx/refresh-active! (:db-after r) (:tx-data r))
                   (cond-> {:ok? (nil? eval-err)
                            :ns s :path path :changed? true :added (:added res)
                            :tx (:max-tx (:db-after r))
                            :diff (cst/unified-diff src new-ns-src key)}
                     eval-err (assoc :eval-error eval-err))))))))))))

(defn ns-requires
  "Returns the parsed `:require` specs of namespace `nsym` directly from its `#ns` form."
  {:malli/schema [:=> [:cat :any] [:maybe :map]]}
  [nsym]
  (when-let [{:keys [conn branch]} *agent-ctx*]
    (let [s (if (string? nsym) (symbol nsym) nsym)
          {:keys [src]} (resolve-form-target conn branch s)]
      (if (str/blank? (str src))
        {:ok? false :error :ns-not-found :ns s}
        {:ok? true :ns s :requires (cst-ns/parse-ns-requires src)}))))

(defn reorder-ns-forms!
  "Topologically reorders `:seon.code.form/order` for `nsym` so same-namespace callees always\n       precede their callers (eliminating `:used-before-defined` with minimal diff)."
  {:malli/schema
   [:function
    [:=> [:cat :any] [:maybe :map]]
    [:=> [:cat :any :map] [:maybe :map]]]}
  ([nsym] (reorder-ns-forms! nsym {}))
  ([nsym {:keys [dry-run?]}]
    (when-let [{:keys [conn]} *agent-ctx*]
      (let [ns-sym (if (string? nsym) (symbol nsym) nsym)
            forms (code/ns-forms @conn ns-sym)
            tx (cst-ns/topo-order-ns-forms forms)]
        (cond
          (empty? tx) {:ok? true,
                       :ns ns-sym,
                       :changed? false,
                       :reordered 0}
          dry-run? {:ok? true,
                    :dry-run? true,
                    :ns ns-sym,
                    :changed? true,
                    :reordered (count tx)}
          :else (let [r (d/transact conn {:tx-data tx})]
                  (ctx/refresh-active! (:db-after r) (:tx-data r))
                  {:ok? true,
                   :ns ns-sym,
                   :changed? true,
                   :reordered (count tx),
                   :tx (:max-tx (:db-after r))}))))))