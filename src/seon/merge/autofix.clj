(ns seon.merge.autofix
  "Merge-time automatic unused-require pruning and topological form ordering for `seon.merge`."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.code.cst.ns :as cst-ns]
            [seon.code.parse :as parse]))

(defn- retracted-form-keys
  {:malli/schema [:=> [:cat [:sequential :any]] [:set :any]]}
  [tx-data]
  (into #{}
        (keep (fn [op]
                (when (and (vector? op) (= :db/retractEntity (first op))
                           (vector? (second op)) (= :seon.code.form/key (first (second op))))
                  (second (second op)))))
        tx-data))

(defn- upserts-by-form-key
  {:malli/schema [:=> [:cat [:sequential :any]] [:map]]}
  [tx-data]
  (reduce (fn [acc op]
            (if (and (map? op) (:seon.code.form/key op))
              (update acc (:seon.code.form/key op) merge op)
              acc))
          {}
          tx-data))

(defn- base-ns-forms
  {:malli/schema [:=> [:cat :seon.host/db :symbol :any] [:map]]}
  [ours-db nsym retracted-fkeys]
  (into {}
        (for [dt (d/datoms ours-db :avet :seon.code.form/ns nsym)
              :let [p (d/pull ours-db '[:seon.code.form/key :seon.code.form/order
                                        :seon.code.form/lead :seon.code.form/src
                                        :seon.code.form/deps] (:e dt))
                    k (:seon.code.form/key p)]
              :when (and k (not (contains? retracted-fkeys k)))]
          [k {:key k
              :order (long (or (:seon.code.form/order p) 0))
              :lead (or (:seon.code.form/lead p) "\n\n")
              :src (:seon.code.form/src p)
              :deps (set (:seon.code.form/deps p))}])))

(defn- effective-ns-forms
  {:malli/schema [:=> [:cat :seon.host/db :symbol :string :map :map] [:map]]}
  [theirs-db nsym hdr-key base-forms upserts-by-key]
  (let [ns-upserts (filter (fn [[k m]]
                             (or (= nsym (:seon.code.form/ns m))
                                 (= k hdr-key)
                                 (str/starts-with? k (str nsym "/"))))
                           upserts-by-key)]
    (reduce (fn [acc [k m]]
              (let [prev (get acc k)
                    tf (when (and (nil? prev) (nil? (:seon.code.form/src m)))
                         (d/pull theirs-db '[:seon.code.form/order :seon.code.form/lead
                                             :seon.code.form/src :seon.code.form/deps]
                                 [:seon.code.form/key k]))]
                (assoc acc k
                       {:key k
                        :order (long (or (:seon.code.form/order m) (:order prev) (:seon.code.form/order tf) 0))
                        :lead (or (:seon.code.form/lead m) (:lead prev) (:seon.code.form/lead tf) (if (= k hdr-key) "" "\n\n"))
                        :src (or (:seon.code.form/src m) (:src prev) (:seon.code.form/src tf))
                        :deps (if (contains? m :seon.code.form/deps)
                                (set (:seon.code.form/deps m))
                                (or (:deps prev) (set (:seon.code.form/deps tf))))})))
            base-forms
            ns-upserts)))

(defn- prune-requires-fix
  {:malli/schema [:=> [:cat :seon.host/db :symbol :any :string :map] [:map]]}
  [ours-db nsym ns-path hdr-key eff-map]
  (let [hdr (get eff-map hdr-key)
        ordered (vec (sort-by (fn [f] [(if (= (:key f) hdr-key) 0 1) (:order f) (:key f)])
                              (vals eff-map)))
        full-src (str/join "\n\n" (keep :src ordered))
        prune-res (try (cst-ns/prune-unused-requires-src full-src) (catch Throwable _ nil))]
    (if (and (:changed? prune-res) (seq (:removed prune-res)))
      (let [reparsed (parse/read-clj ns-path (:src prune-res))
            new-hdr (first (:forms reparsed))
            old-reqs (set (:seon.code.ns/requires (d/pull ours-db '[:seon.code.ns/requires] [:seon.code.ns/name nsym])))
            new-reqs (set (:requires reparsed))
            retract-ops (mapv (fn [gone] [:db/retract [:seon.code.ns/name nsym] :seon.code.ns/requires gone])
                              (set/difference old-reqs new-reqs))]
        {:eff-map (assoc-in eff-map [hdr-key :src] (:seon.code.form/src new-hdr))
         :replace-hdr (assoc new-hdr
                             :seon.code.form/order (:order hdr)
                             :seon.code.form/lead (:lead hdr))
         :ops (cond-> retract-ops (seq new-reqs) (conj {:seon.code.ns/name nsym :seon.code.ns/requires new-reqs}))
         :auto-fixed {:class :unused-require :ns nsym :removed (:removed prune-res)}
         :extra-path ns-path})
      {:eff-map eff-map :replace-hdr nil :ops [] :auto-fixed nil :extra-path nil})))

(defn- used-before-defined-fix
  {:malli/schema [:=> [:cat :symbol :any :string :map] [:map]]}
  [nsym ns-path hdr-key eff-map]
  (let [ordered2 (vec (sort-by (fn [f] [(if (= (:key f) hdr-key) 0 1) (:order f) (:key f)])
                               (vals eff-map)))
        body-forms (vec (remove #(= (:key %) hdr-key) ordered2))
        def-orders (into {} (keep (fn [f] (when-not (str/includes? (:key f) "#") [(symbol (:key f)) (:order f)]))) body-forms)
        decl-orders (reduce (fn [acc f]
                              (let [s (str/triml (str (:src f)))]
                                (if (str/starts-with? s "(declare ")
                                  (let [syms (rest (try (read-string s) (catch Throwable _ nil)))]
                                    (reduce #(assoc %1 (symbol (name nsym) (name %2)) (:order f)) acc (filter symbol? syms)))
                                  acc)))
                            {}
                            body-forms)
        has-fwd? (some (fn [f]
                         (some (fn [dep]
                                 (when-let [dord (get def-orders dep)]
                                   (and (> dord (:order f))
                                        (let [decl (get decl-orders dep)]
                                          (or (nil? decl) (> decl (:order f)))))))
                               (:deps f)))
                       body-forms)]
    (if-not has-fwd?
      {:ops [] :reordered? false :auto-fixed nil :extra-path nil}
      (if-let [sorted-body (cst-ns/topo-sort-ns-forms nsym body-forms)]
        (let [full-ord (into [(get eff-map hdr-key)] sorted-body)
              ops (vec (keep-indexed (fn [i f]
                                       (when (not= (long i) (:order f))
                                         {:seon.code.form/key (:key f) :seon.code.form/order (long i)}))
                                     full-ord))]
          {:ops ops :reordered? true :auto-fixed {:class :used-before-defined :ns nsym :reordered? true} :extra-path ns-path})
        {:ops [] :reordered? false :auto-fixed nil :extra-path nil}))))

(defn- apply-form-replacements
  {:malli/schema [:=> [:cat [:sequential :any] :map] [:sequential :any]]}
  [tx-data replace-forms]
  (if (empty? replace-forms)
    tx-data
    (let [replaced-ks (volatile! #{})
          base (mapv (fn [op]
                       (if-let [r (and (map? op) (get replace-forms (:seon.code.form/key op)))]
                         (do (vswap! replaced-ks conj (:seon.code.form/key op))
                             (merge op r))
                         op))
                     tx-data)]
      (into base (vals (apply dissoc replace-forms @replaced-ks))))))

(defn auto-fix-touched-nss
  "Deterministic, safe auto-fix pass executed automatically during `three-way` merge on touched non-test
   namespaces (so agents never waste turns running safe mechanical fixes):
   1. Prunes unused `:require` clauses via AST-accurate `cst/prune-unused-requires-src` and syncs `:seon.code.ns/requires`.
   2. Auto-reorders non-cyclic `:used-before-defined` top-level forms via deterministic Kahn topological sort."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db [:set :symbol] [:vector :any]] :map], :cost/ticks 50000000}
  [ours-db theirs-db touched-nss tx-data]
  (let [cand-nss (sort (remove #(or (nil? %) (str/ends-with? (name %) "-test")) touched-nss))]
    (if (or (empty? cand-nss) (empty? tx-data))
      {:tx-data tx-data :auto-fixed [] :reordered-nss #{} :extra-paths #{}}
      (let [retracted-fkeys (retracted-form-keys tx-data)
            upserts-by-key (upserts-by-form-key tx-data)]
        (loop [nss cand-nss
               replace-forms {}
               extra-ops []
               auto-fixed []
               reordered-nss #{}
               extra-paths #{}]
          (if-let [nsym (first nss)]
            (let [hdr-key (str nsym "#ns")
                  base-forms (base-ns-forms ours-db nsym retracted-fkeys)
                  eff-map (effective-ns-forms theirs-db nsym hdr-key base-forms upserts-by-key)
                  hdr (get eff-map hdr-key)]
              (if (and hdr (:src hdr) (> (count eff-map) 1))
                (let [ns-path (or (:seon.code.ns/path (d/pull theirs-db '[:seon.code.ns/path] [:seon.code.ns/name nsym]))
                                  (:seon.code.ns/path (d/pull ours-db '[:seon.code.ns/path] [:seon.code.ns/name nsym]))
                                  (parse/ns->default-path nsym))
                      prune (prune-requires-fix ours-db nsym ns-path hdr-key eff-map)
                      reorder (used-before-defined-fix nsym ns-path hdr-key (:eff-map prune))]
                  (recur (rest nss)
                         (if-let [rh (:replace-hdr prune)]
                           (assoc replace-forms hdr-key rh)
                           replace-forms)
                         (into extra-ops (concat (:ops prune) (:ops reorder)))
                         (cond-> auto-fixed
                           (:auto-fixed prune) (conj (:auto-fixed prune))
                           (:auto-fixed reorder) (conj (:auto-fixed reorder)))
                         (if (:reordered? reorder) (conj reordered-nss nsym) reordered-nss)
                         (cond-> extra-paths
                           (:extra-path prune) (conj (:extra-path prune))
                           (:extra-path reorder) (conj (:extra-path reorder)))))
                (recur (rest nss) replace-forms extra-ops auto-fixed reordered-nss extra-paths)))
            {:tx-data (into (apply-form-replacements tx-data replace-forms) extra-ops)
             :auto-fixed auto-fixed
             :reordered-nss reordered-nss
             :extra-paths extra-paths}))))))