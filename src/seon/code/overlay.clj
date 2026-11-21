(ns seon.code.overlay
  "Hot-reload code overlay application, alias-clearing namespace rebinds, and Malli var arming."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [jolt.analyzer]
            [jolt.host]
            [seon.schema :as schema]
            [seon.code.reach :as reach :refer [changed-form-ops sort-forms-topologically]]
            [seon.code.io :as cio :refer [ns-forms]]))

(defn register-loaded-ns!
  {:malli/schema [:=> [:cat :symbol] :any]}
  [nsym]
  (when (and (symbol? nsym) (not= 'user nsym))
    (dosync (commute @#'clojure.core/*loaded-libs* conj nsym))))

(defn arm-defined-vars-in-ns!
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :symbol] :any]}
  [db nsym]
  (when-let [ns-obj (find-ns nsym)]
    (doseq [[_ v] (ns-interns ns-obj)]
      (when (and (not (:private (meta v))) (:malli/schema (meta v)))
        (schema/arm-var! db v)))))

(defn apply-overlay!
"Applies a branch/merge delta `{:upserts [...] :retracted-keys #{...}}` live into the current
 Chez/Jolt runtime. Automatically unmaps retracted vars AND rebinds forms in both retracted
 namespaces and any namespace whose `#ns` form updated (preventing stale `:refer` closures)."
{:malli/schema [:function
                [:=> [:cat :map] [:vector :map]]
                [:=> [:cat :any :map] [:vector :map]]
                [:=> [:cat :any :map :boolean] [:vector :map]]]}
([overlay] (apply-overlay! nil overlay false))
([db delta] (apply-overlay! db delta false))
([db {:keys [upserts retracted-keys]} tolerant?]
 (let [retracted-by-ns
       (reduce (fn [acc k]
                 (let [[ns-str var-str] (str/split k #"/" 2)]
                   (if (and ns-str var-str (not (str/includes? var-str "#")))
                     (let [nsym (symbol ns-str)]
                       (when-let [ns-obj (find-ns nsym)]
                         (ns-unmap ns-obj (symbol var-str)))
                       (update acc nsym (fnil conj #{}) var-str))
                     acc)))
               {}
               retracted-keys)
       all-unmapped-names (into #{} (mapcat identity) (vals retracted-by-ns))
       upsert-keys (into #{} (map :seon.code.form/key) upserts)
       retracted-nss (into #{}
                           (keep #(let [s (str %)]
                                    (when (str/ends-with? s "#ns")
                                      (symbol (subs s 0 (- (count s) 3))))))
                           retracted-keys)
       updated-ns-hdrs (into #{}
                             (keep #(when (str/ends-with? (str (:seon.code.form/key %)) "#ns")
                                      (:seon.code.form/ns %)))
                             upserts)
       _ (when (and (seq all-unmapped-names) (seq updated-ns-hdrs))
           (doseq [nsym updated-ns-hdrs
                   :let [ns-obj (find-ns nsym)]
                   :when ns-obj
                   vname all-unmapped-names]
             (let [vsym (symbol vname)]
               (when-let [v (get (ns-map ns-obj) vsym)]
                 (when (and (var? v) (contains? retracted-by-ns (ns-name (:ns (meta v)))))
                   (ns-unmap ns-obj vsym))))))
       rebind-forms
       (when (and db (or (seq all-unmapped-names) (seq retracted-nss)))
         (let [target-nss (into (set (keys retracted-by-ns)) updated-ns-hdrs)]
           (for [nsym target-nss
                 f (ns-forms db nsym)
                 :let [fk (:seon.code.form/key f)
                       src (str (:seon.code.form/src f))
                       rebind-all? (and (seq retracted-nss) (contains? updated-ns-hdrs nsym))
                       names-to-check (if (contains? updated-ns-hdrs nsym)
                                        all-unmapped-names
                                        (get retracted-by-ns nsym))]
                 :when (and (not (contains? upsert-keys fk))
                            (not (contains? retracted-keys fk))
                            (or rebind-all? (some #(str/includes? src %) names-to-check)))]
             f)))
       all-upserts (let [unloaded-forms (when db
                                        (let [unloaded-nss (into
                                                             #{}
                                                             (comp
                                                               (map :seon.code.form/ns)
                                                               (filter
                                                                 #(and
                                                                   %
                                                                   (nil? (find-ns %)))))
                                                             upserts)]
                                          (when (seq unloaded-nss)
                                            (for
                                              [nsym
                                               unloaded-nss
                                               f
                                               (ns-forms db nsym)
                                               :let
                                               [fk (:seon.code.form/key f)]
                                               :when
                                               (and
                                                 (not (contains? upsert-keys fk))
                                                 (not (contains? retracted-keys fk)))]
                                              f))))]
                   (vec
                     (vals
                       (reduce
                         (fn [m f] (assoc m (:seon.code.form/key f) f))
                         {}
                         (concat unloaded-forms rebind-forms upserts)))))
       touched-nss (volatile! #{})
       errors (volatile! [])]
   (doseq [{:keys [:seon.code.form/key :seon.code.form/ns :seon.code.form/src]} (sort-forms-topologically all-upserts)]
     (let [load-one (fn []
                      (let [ns-obj (create-ns ns)]
                        (when (str/ends-with? (str key) "#ns")
                          (doseq [[al _] (ns-aliases ns-obj)]
                            (ns-unalias ns-obj al)))
                        (binding [*ns* ns-obj]
                          (refer-clojure)
                          (let [rdr (java.io.PushbackReader. (java.io.StringReader. src))]
                            (loop []
                              (let [form (read {:eof ::eof} rdr)]
                                (when-not (= ::eof form)
                                  (eval form)
                                  (recur))))))
                        (register-loaded-ns! ns)
                        (vswap! touched-nss conj ns)))]
       (if tolerant?
         (try
           (load-one)
           (catch Throwable ex
             (vswap! errors conj {:key key :error (or (.getMessage ex) (str ex))})))
         (load-one))))
   (doseq [old-ns retracted-nss]
     (when (and (not (contains? @touched-nss old-ns)) (find-ns old-ns))
       (remove-ns old-ns)))
   (doseq [nsym @touched-nss]
     (arm-defined-vars-in-ns! db nsym))
   @errors)))

(defn load-delta!
  {:malli/schema [:function [:=> [:cat :seon.host/conn :any :any] :any] [:=> [:cat :seon.host/conn :any :any :boolean] :any]]}
  ([conn from-cid to-cid] (load-delta! conn from-cid to-cid false))
  ([conn from-cid to-cid tolerant?]
   (let [delta (changed-form-ops conn from-cid to-cid)
         to-db (or (try (d/commit-as-db conn to-cid) (catch Throwable _ nil)) @conn)
         errs (apply-overlay! to-db delta (boolean tolerant?))]
     {:loaded (mapv :seon.code.form/key (:upserts delta))
      :unmapped (vec (:retracted-keys delta))
      :errors errs
      :boot-affected? (:boot-affected? delta)})))

(defn expand-overlay-rebinds
  "Expands an overlay `{:upserts [...] :retracted-keys #{...}}` using `db` so IPC recipients
   without a DB handle (`ForkParent` `:advance-image` and COW test workers) receive every
   caller form that must recompile when a var or namespace is retracted/renamed."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :map] :map]}
  [db {:keys [upserts retracted-keys] :as overlay}]
  (if (or (nil? db) (empty? retracted-keys))
    overlay
    (let [retracted-by-ns
          (reduce (fn [acc k]
                    (let [[ns-str var-str] (str/split (str k) #"/" 2)]
                      (if (and ns-str var-str (not (str/includes? var-str "#")))
                        (update acc (symbol ns-str) (fnil conj #{}) var-str)
                        acc)))
                  {}
                  retracted-keys)
          retracted-nss (into #{}
                              (keep #(let [s (str %)]
                                       (when (str/ends-with? s "#ns")
                                         (symbol (subs s 0 (- (count s) 3))))))
                              retracted-keys)
          upsert-keys (into #{} (map :seon.code.form/key) upserts)
          updated-ns-hdrs (into #{}
                                (keep #(when (str/ends-with? (str (:seon.code.form/key %)) "#ns")
                                         (:seon.code.form/ns %)))
                                upserts)
          target-nss (into (set (keys retracted-by-ns)) updated-ns-hdrs)
          rebind-forms (when (or (seq retracted-by-ns) (seq retracted-nss))
                         (for [nsym target-nss
                               f (ns-forms db nsym)
                               :let [fk (:seon.code.form/key f)
                                     src (str (:seon.code.form/src f))
                                     rebind-all? (and (seq retracted-nss) (contains? updated-ns-hdrs nsym))
                                     unmapped-names (get retracted-by-ns nsym)]
                               :when (and (not (contains? upsert-keys fk))
                                          (not (contains? retracted-keys fk))
                                          (or rebind-all?
                                              (some #(str/includes? src %) unmapped-names)))]
                           f))]
      (if (seq rebind-forms)
        (assoc overlay :upserts (into (vec upserts) rebind-forms))
        overlay))))