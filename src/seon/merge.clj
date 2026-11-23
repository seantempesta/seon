(ns seon.merge
  "Generic Malli-driven O(|Delta_theirs|) 3-way merge over ALL Datahike entities:
  - Zero hardcoded entity tables: reads `:identity-attrs`, `:many-attrs`, and `:skipped-attrs`
    directly from `seon.schema/registry-for-db` (including any runtime-registered schemas!).
  - Automatically carries over any new Malli-derived Datahike `:db/ident` declarations created on
    `theirs-branch` (`:schema-tx`) before merging the entity datoms!
  - Performs attribute-level 3-way merge (`:db.cardinality/one` value 3-way + `:db.cardinality/many`
    conflict-free set union/retraction)."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.health :as health]
            [seon.schema :as schema]
            [seon.code.cst.ns :as cst-ns]
            [seon.code.parse :as parse]))

(defn entity->disk-path
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :qualified-keyword :any [:maybe :map]] [:maybe :string]]}
  [ours-db theirs-db id-attr id-val ent]
  (case id-attr
    :seon.code.file/path (:seon.code.file/path ent id-val)
    :seon.code.doc/id    (or (:seon.code.doc/path ent)
                             (:seon.code.doc/path (d/pull theirs-db '[:seon.code.doc/path] [:seon.code.doc/id id-val]))
                             (:seon.code.doc/path (d/pull ours-db '[:seon.code.doc/path] [:seon.code.doc/id id-val])))
    :seon.schema/key     (schema/schema-owner-path id-val)
    :seon.config/key     (when (= :seon.config/root id-val) "resources/seon/config/default.edn")
    :seon.task/id        (when (qualified-keyword? id-val)
                         "resources/seon/tasks.edn")
    :seon.rule/id        (when (qualified-keyword? id-val)
                         "resources/seon/rules.edn")
    :seon.agent/id       (or (:seon.agent/seed-path ent)
                             (when (contains? (:schema theirs-db) :seon.agent/seed-path)
                               (:seon.agent/seed-path (d/pull theirs-db '[:seon.agent/seed-path] [:seon.agent/id id-val])))
                             (when (contains? (:schema ours-db) :seon.agent/seed-path)
                               (:seon.agent/seed-path (d/pull ours-db '[:seon.agent/seed-path] [:seon.agent/id id-val]))))
    :seon.agent.eval/id  (let [aid (or (:seon.agent.eval/agent ent)
                                       (:seon.agent.eval/agent (d/pull theirs-db '[:seon.agent.eval/agent] [:seon.agent.eval/id id-val]))
                                       (:seon.agent.eval/agent (d/pull ours-db '[:seon.agent.eval/agent] [:seon.agent.eval/id id-val])))]
                           (when (and aid (= "example" (namespace aid)))
                             (or (when (contains? (:schema theirs-db) :seon.agent/seed-path)
                                   (:seon.agent/seed-path (d/pull theirs-db '[:seon.agent/seed-path] [:seon.agent/id aid])))
                                 (when (contains? (:schema ours-db) :seon.agent/seed-path)
                                   (:seon.agent/seed-path (d/pull ours-db '[:seon.agent/seed-path] [:seon.agent/id aid]))))))
    :seon.code.ns/name   (or (:seon.code.ns/path ent)
                             (:seon.code.ns/path (d/pull theirs-db '[:seon.code.ns/path] [:seon.code.ns/name id-val]))
                             (:seon.code.ns/path (d/pull ours-db '[:seon.code.ns/path] [:seon.code.ns/name id-val])))
    (:seon.code.form/key :seon.code.var/name)
    (let [nsym (or (:seon.code.form/ns ent) (:seon.code.var/ns ent)
                   (when (= :seon.code.form/key id-attr)
                     (or (:seon.code.form/ns (d/pull theirs-db '[:seon.code.form/ns] [:seon.code.form/key id-val]))
                         (:seon.code.form/ns (d/pull ours-db '[:seon.code.form/ns] [:seon.code.form/key id-val]))))
                   (when (= :seon.code.var/name id-attr)
                     (or (:seon.code.var/ns (d/pull theirs-db '[:seon.code.var/ns] [:seon.code.var/name id-val]))
                         (:seon.code.var/ns (d/pull ours-db '[:seon.code.var/ns] [:seon.code.var/name id-val])))))]
      (when nsym
        (or (:seon.code.ns/path (d/pull theirs-db '[:seon.code.ns/path] [:seon.code.ns/name nsym]))
            (:seon.code.ns/path (d/pull ours-db '[:seon.code.ns/path] [:seon.code.ns/name nsym])))))
    nil))

(defn- merge-one-entity [[id-attr id-val :as lookup-ref] b-map o-map t-map many-attrs]
  (cond
    (or (= t-map b-map) (= t-map o-map))
    {:ops [] :conflicts []}

    (nil? t-map)
    (if (= o-map b-map)
      {:ops [[:db/retractEntity lookup-ref]] :conflicts []}
      {:ops [] :conflicts [{:entity lookup-ref :reason :deleted-vs-modified :base b-map :ours o-map :theirs nil}]})

    (and (nil? b-map) (nil? o-map))
    {:ops [t-map] :conflicts []}

    :else
    (let [all-ks (disj (set/union (set (keys b-map)) (set (keys o-map)) (set (keys t-map))) id-attr)]
      (reduce
       (fn [acc k]
         (if (contains? many-attrs k)
           (let [bs (get b-map k #{})
                 os (get o-map k #{})
                 ts (get t-map k #{})
                 to-add (set/difference (set/difference ts bs) os)
                 to-rem (set/intersection (set/difference bs ts) os)
                 add-ops (mapv (fn [v] [:db/add lookup-ref k v]) (sort-by pr-str to-add))
                 rem-ops (mapv (fn [v] [:db/retract lookup-ref k v]) (sort-by pr-str to-rem))]
             (update acc :ops into (concat rem-ops add-ops)))
           (let [bv (get b-map k ::absent)
                 ov (get o-map k ::absent)
                 tv (get t-map k ::absent)]
             (cond
               (or (= tv bv) (= tv ov))
               acc

               (= ov bv)
               (if (= tv ::absent)
                 (update acc :ops conj [:db/retract lookup-ref k bv])
                 (update acc :ops conj {id-attr id-val k tv}))

               :else
               (update acc :conflicts conj
                       {:entity lookup-ref
                        :attribute k
                        :base (when-not (= bv ::absent) bv)
                        :ours (when-not (= ov ::absent) ov)
                        :theirs (when-not (= tv ::absent) tv)})))))
       {:ops [] :conflicts []}
       (sort all-ks)))))

(defn- bind-new-ref-tempids
  "Rewrites lookup-refs pointing to entities *created in the same `tx-data`* into deterministic
   string tempids so Datahike resolves new parent->child ref graphs atomically in one tx."
  [ours-db tx-data identity-attrs]
  (let [new-lrefs
        (into {}
              (keep (fn [op]
                      (when (map? op)
                        (some (fn [ida]
                                (when-let [v (get op ida)]
                                  (when-not (and (contains? (:schema ours-db) ida)
                                                 (first (d/datoms ours-db :avet ida v)))
                                    [[ida v] (str "new/" (namespace ida) "/" (name ida) "/" v)])))
                              identity-attrs))))
              tx-data)]
    (if (empty? new-lrefs)
      tx-data
      (let [rewrite-v (fn rw [v]
                        (cond
                          (and (vector? v) (= 2 (count v)) (contains? new-lrefs v))
                          (get new-lrefs v)
                          (set? v) (into #{} (map rw) v)
                          (vector? v) (mapv rw v)
                          :else v))]
        (mapv (fn [op]
                (cond
                  (map? op)
                  (let [self-tid (some (fn [ida]
                                         (when-let [v (get op ida)]
                                           (get new-lrefs [ida v])))
                                       identity-attrs)
                        m' (reduce-kv (fn [a k v] (assoc a k (rewrite-v v))) {} op)]
                    (cond-> m' self-tid (assoc :db/id self-tid)))

                  (vector? op)
                  (let [[cmd e a v] op]
                    (if (= cmd :db/retractEntity)
                      [cmd (rewrite-v e)]
                      [cmd (rewrite-v e) a (rewrite-v v)]))

                  :else op))
              tx-data)))))

(defn- topo-sort-ns-forms
  "Attempts deterministic Kahn topological sort of non-`#ns` forms in `nsym` by same-ns `:deps`,
   preserving original `:order` as tie-breaker. Returns ordered vec if acyclic, or `nil` on cycle."
  [nsym body-forms]
  (let [by-key (into {} (map (juxt :key identity)) body-forms)
        sym->key (into {}
                       (keep (fn [{:keys [key]}]
                               (when (and key (not (str/includes? key "#")))
                                 [(symbol key) key])))
                       body-forms)
        in-deg (volatile! (into {} (map (fn [f] [(:key f) 0])) body-forms))
        rdeps  (volatile! {})
        _ (doseq [{:keys [key deps]} body-forms]
            (let [local-deps (->> deps
                                  (keep #(get sym->key %))
                                  (remove #(= % key))
                                  distinct)]
              (doseq [dk local-deps]
                (vswap! in-deg update key (fnil inc 0))
                (vswap! rdeps update dk (fnil conj []) key))))
        orig-ord (into {} (map-indexed (fn [i f] [(:key f) i])) body-forms)]
    (loop [ready (vec (sort-by orig-ord (keep (fn [[k d]] (when (zero? d) k)) @in-deg)))
           deg @in-deg
           out []]
      (if (empty? ready)
        (when (= (count out) (count body-forms))
          (mapv by-key out))
        (let [cur (first ready)
              rest-r (subvec ready 1)
              kids (get @rdeps cur)
              [deg' newly] (reduce (fn [[dm nr] k]
                                     (let [nd (dec (get dm k 0))]
                                       [(assoc dm k nd) (if (zero? nd) (conj nr k) nr)]))
                                   [deg []]
                                   kids)
              next-ready (if (seq newly)
                           (vec (sort-by orig-ord (into rest-r newly)))
                           rest-r)]
          (recur next-ready deg' (conj out cur)))))))

(defn- auto-fix-touched-nss
  "Deterministic, safe auto-fix pass executed automatically during `three-way` merge on touched non-test
   namespaces (so agents never waste turns running safe mechanical fixes):
   1. Prunes unused `:require` clauses via AST-accurate `cst/prune-unused-requires-src` and syncs `:seon.code.ns/requires`.
   2. Auto-reorders non-cyclic `:used-before-defined` top-level forms via deterministic Kahn topological sort."
  [ours-db theirs-db touched-nss tx-data]
  (let [cand-nss (sort (remove #(or (nil? %) (str/ends-with? (name %) "-test")) touched-nss))]
    (if (or (empty? cand-nss) (empty? tx-data))
      {:tx-data tx-data :auto-fixed [] :reordered-nss #{} :extra-paths #{}}
      (let [retracted-fkeys (into #{}
                                  (keep (fn [op]
                                          (when (and (vector? op) (= :db/retractEntity (first op))
                                                     (vector? (second op)) (= :seon.code.form/key (first (second op))))
                                            (second (second op)))))
                                  tx-data)
            upserts-by-key (reduce (fn [acc op]
                                     (if (and (map? op) (:seon.code.form/key op))
                                       (update acc (:seon.code.form/key op) merge op)
                                       acc))
                                   {}
                                   tx-data)
            replace-forms (volatile! {})
            extra-ops (volatile! [])
            auto-fixed (volatile! [])
            reordered-nss (volatile! #{})
            extra-paths (volatile! #{})]
        (doseq [nsym cand-nss]
          (let [hdr-key (str nsym "#ns")
                base-forms (into {}
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
                                       :deps (set (:seon.code.form/deps p))}]))
                ns-upserts (filter (fn [[k m]]
                                     (or (= nsym (:seon.code.form/ns m))
                                         (= k hdr-key)
                                         (str/starts-with? k (str nsym "/"))))
                                   upserts-by-key)
                eff-map (reduce (fn [acc [k m]]
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
                                ns-upserts)
                hdr (get eff-map hdr-key)]
            (when (and hdr (:src hdr) (> (count eff-map) 1))
              (let [ns-path (or (:seon.code.ns/path (d/pull theirs-db '[:seon.code.ns/path] [:seon.code.ns/name nsym]))
                                (:seon.code.ns/path (d/pull ours-db '[:seon.code.ns/path] [:seon.code.ns/name nsym]))
                                (parse/ns->default-path nsym))
                    ordered (vec (sort-by (fn [f] [(if (= (:key f) hdr-key) 0 1) (:order f) (:key f)])
                                          (vals eff-map)))
                    ;; 1. Auto-prune unused :require clauses cleanly
                    full-src (str/join "\n\n" (keep :src ordered))
                    prune-res (try (cst-ns/prune-unused-requires-src full-src) (catch Throwable _ nil))
                    eff-map1 (if (and (:changed? prune-res) (seq (:removed prune-res)))
                               (let [reparsed (parse/read-clj ns-path (:src prune-res))
                                     new-hdr (first (:forms reparsed))
                                     old-reqs (set (:seon.code.ns/requires (d/pull ours-db '[:seon.code.ns/requires] [:seon.code.ns/name nsym])))
                                     new-reqs (set (:requires reparsed))]
                                 (vswap! replace-forms assoc hdr-key
                                         (assoc new-hdr
                                                :seon.code.form/order (:order hdr)
                                                :seon.code.form/lead (:lead hdr)))
                                 (doseq [gone (set/difference old-reqs new-reqs)]
                                   (vswap! extra-ops conj [:db/retract [:seon.code.ns/name nsym] :seon.code.ns/requires gone]))
                                 (when (seq new-reqs)
                                   (vswap! extra-ops conj {:seon.code.ns/name nsym :seon.code.ns/requires new-reqs}))
                                 (when ns-path (vswap! extra-paths conj ns-path))
                                 (vswap! auto-fixed conj {:class :unused-require :ns nsym :removed (:removed prune-res)})
                                 (assoc-in eff-map [hdr-key :src] (:seon.code.form/src new-hdr)))
                               eff-map)
                    ;; 2. Auto-reorder non-cyclic used-before-defined forms cleanly
                    ordered2 (vec (sort-by (fn [f] [(if (= (:key f) hdr-key) 0 1) (:order f) (:key f)])
                                           (vals eff-map1)))
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
                (when has-fwd?
                  (when-let [sorted-body (topo-sort-ns-forms nsym body-forms)]
                    (let [full-ord (into [(get eff-map1 hdr-key)] sorted-body)]
                      (doseq [[i f] (map-indexed vector full-ord)]
                        (when (not= (long i) (:order f))
                          (vswap! extra-ops conj {:seon.code.form/key (:key f) :seon.code.form/order (long i)})))
                      (vswap! reordered-nss conj nsym)
                      (when ns-path (vswap! extra-paths conj ns-path))
                      (vswap! auto-fixed conj {:class :used-before-defined :ns nsym :reordered? true}))))))))
        (let [rep @replace-forms
              tx1 (if (empty? rep)
                    tx-data
                    (let [replaced-ks (volatile! #{})
                          base (mapv (fn [op]
                                       (if-let [r (and (map? op) (get rep (:seon.code.form/key op)))]
                                         (do (vswap! replaced-ks conj (:seon.code.form/key op))
                                             (merge op r))
                                         op))
                                     tx-data)
                          unmatched (vals (apply dissoc rep @replaced-ks))]
                      (into base unmatched)))
              tx2 (into tx1 @extra-ops)]
          {:tx-data tx2
           :auto-fixed @auto-fixed
           :reordered-nss @reordered-nss
           :extra-paths @extra-paths})))))

(defn- ^:seon.flow/edge resolve-ours-db [store ours-branch ours-conn]
  (or (try (d/branch-as-db store ours-branch) (catch Throwable _ nil)) @ours-conn))

(defn- retracted-dh-ident-ops [ours-db theirs-db theirs-schemas theirs-keys]
  (into []
        (keep (fn [[id-attr id-val]]
                (when (and (= :seon.schema/key id-attr)
                           (not (contains? theirs-schemas id-val))
                           (contains? (:schema ours-db) id-val)
                           (not (contains? (:schema theirs-db) id-val)))
                  (when-let [eid (:e (first (d/datoms ours-db :avet :db/ident id-val)))]
                    [:db/retractEntity eid]))))
        theirs-keys))

(defn- order-merge-tx-ops
  "Orders merge tx ops into 3 safe phases: (1) upserts & attr retractions while targets still exist,
   (2) entity retractions, (3) schema :db/ident retractions."
  [ops retracted-dh-idents]
  (let [retract-ent? #(and (vector? %) (= :db/retractEntity (first %)))]
    (-> (filterv (complement retract-ent?) ops)
        (into (filterv retract-ent? ops))
        (into retracted-dh-idents))))

(defn- find-identity-ref [db eid id-attr-rank]
  (when db
    (loop [dts (d/datoms db :eavt eid)
           best-rank Integer/MAX_VALUE
           best-ref nil]
      (if-let [dt (first dts)]
        (let [r (get id-attr-rank (:a dt))]
          (if (and r (< (int r) best-rank))
            (recur (next dts) (int r) [(:a dt) (:v dt)])
            (recur (next dts) best-rank best-ref)))
        best-ref))))

(defn- eid->lookup-ref [db eid id-attr-rank]
(or (find-identity-ref db eid id-attr-rank) eid))

(defn- normalize-ref-val [db k v id-attr-rank]
(if (= :db.type/ref (get-in (:schema db) [k :db/valueType]))
  (let [norm1 (fn [x]
                (if (and (map? x) (:db/id x))
                  (let [r (eid->lookup-ref db (:db/id x) id-attr-rank)]
                    (if (map? r) (:db/id r) r))
                  x))]
    (cond
      (and (map? v) (:db/id v)) (norm1 v)
      (coll? v) (mapv norm1 v)
      :else v))
  v))

(defn- pull-clean-entity [db id-attr id-val many-attrs skipped-attrs id-attr-rank]
  (when (contains? (:schema db) id-attr)
    (when-let [dt (first (d/datoms db :avet id-attr id-val))]
      (let [raw (d/pull db '[*] (:e dt))]
        (reduce-kv
         (fn [acc k v]
           (if (or (contains? skipped-attrs k) (nil? v))
             acc
             (let [nv (normalize-ref-val db k v id-attr-rank)]
               (assoc acc k (if (contains? many-attrs k) (set nv) nv)))))
         {}
         raw)))))

(defn- touched-entities-in-changes [before-db after-db changes-datoms id-attr-rank]
(let [eids (sort (into #{} (comp (remove #(= :db/txInstant (:a %))) (map :e)) changes-datoms))]
(reduce
 (fn [acc eid]
   (if-let [lref (or (find-identity-ref after-db eid id-attr-rank)
                     (find-identity-ref before-db eid id-attr-rank))]
     (conj acc lref)
     acc))
 #{}
 eids)))

(defn three-way
  {:malli/schema [:=> [:cat :seon.host/conn :map :seon.store/branch] :seon.merge/plan]}
  [ours-conn _cfg theirs-branch]
  (let [ours-branch (get-in @ours-conn [:config :branch] :main)
        store (:store @ours-conn)
        ours-db (resolve-ours-db store ours-branch ours-conn)
        ours-cid (d/commit-id ours-db)
        theirs-db (d/branch-as-db store theirs-branch)
        theirs-cid (d/commit-id theirs-db)
        base-cid (d/merge-base ours-conn ours-branch theirs-branch)]
    (when-not base-cid
      (throw (ex-info (str "No common merge-base between " ours-branch " and " theirs-branch)
                      {:ours ours-branch :theirs theirs-branch})))
    (let [base-db (d/commit-as-db ours-conn base-cid)
          theirs-schemas (schema/db-schema-forms theirs-db)
          reg-union (schema/registry-for-db ours-db theirs-schemas)
          {:keys [identity-attrs many-attrs skipped-attrs]} (:merge-meta reg-union)
          new-dh-decls (->> (:declarations reg-union)
                            (remove #(contains? (:schema ours-db) (:db/ident %)))
                            vec)
          id-attr-rank (into {} (map-indexed (fn [i a] [a i])) identity-attrs)
          theirs-ds (if (= base-cid theirs-cid) [] (d/changes ours-conn base-cid theirs-cid))
          theirs-keys (touched-entities-in-changes base-db theirs-db theirs-ds id-attr-rank)
          changed-schema-forms (into {}
                                     (keep (fn [[id-attr id-val]]
                                             (when (= :seon.schema/key id-attr)
                                               (when-let [f (get theirs-schemas id-val)]
                                                 [id-val f]))))
                                     theirs-keys)
          touched-nss (into #{}
                            (keep (fn [[id-attr id-val]]
                                    (case id-attr
                                      :seon.code.ns/name id-val
                                      :seon.code.form/key
                                      (or (:seon.code.form/ns (d/pull theirs-db '[:seon.code.form/ns] [:seon.code.form/key id-val]))
                                          (:seon.code.form/ns (d/pull ours-db '[:seon.code.form/ns] [:seon.code.form/key id-val])))
                                      :seon.code.var/name
                                      (or (:seon.code.var/ns (d/pull theirs-db '[:seon.code.var/ns] [:seon.code.var/name id-val]))
                                          (:seon.code.var/ns (d/pull ours-db '[:seon.code.var/ns] [:seon.code.var/name id-val])))
                                      nil)))
                            theirs-keys)
          health-blocks (health/caused-by-branch ours-db theirs-db changed-schema-forms (vec touched-nss))
          ordered-theirs-keys (sort-by (fn [[id-attr id-val]]
                                         [(if (= :seon.code.ns/name id-attr) 0 1)
                                          (count (str/split (str id-val) #"\."))
                                          (pr-str id-attr)
                                          (pr-str id-val)])
                                       theirs-keys)
          {:keys [tx-data touched-paths entity-diffs conflicts]}
          (reduce
           (fn [acc [id-attr id-val :as lref]]
             (let [b-val (pull-clean-entity base-db id-attr id-val many-attrs skipped-attrs id-attr-rank)
                   t-val (pull-clean-entity theirs-db id-attr id-val many-attrs skipped-attrs id-attr-rank)
                   o-val (if (= base-cid ours-cid)
                         b-val
                         (pull-clean-entity ours-db id-attr id-val many-attrs skipped-attrs id-attr-rank))
                   {:keys [ops conflicts]} (merge-one-entity lref b-val o-val t-val many-attrs)
                   p (when (or (seq ops) (seq conflicts))
                       (entity->disk-path ours-db theirs-db id-attr id-val (or t-val b-val)))]
               (cond-> acc
                 (seq ops) (update :tx-data into ops)
                 (or (seq ops) (seq conflicts))
                 (update :entity-diffs conj {:entity lref
                                             :status (cond (seq conflicts) :conflict
                                                           (nil? t-val) :deleted
                                                           (nil? b-val) :added
                                                           :else :modified)
                                             :path p})
                 p (update :touched-paths conj p)
                 (seq conflicts) (update :conflicts into conflicts))))
           {:tx-data [] :touched-paths #{} :entity-diffs [] :conflicts []}
           ordered-theirs-keys)
          af (if (empty? conflicts)
               (auto-fix-touched-nss ours-db theirs-db touched-nss tx-data)
               {:tx-data tx-data :auto-fixed [] :reordered-nss #{} :extra-paths #{}})
          eff-health-blocks (remove (fn [hb]
                                      (and (= :used-before-defined (:class hb))
                                           (contains? (:reordered-nss af) (:ns hb))))
                                    health-blocks)
          all-conflicts (into conflicts
                              (map (fn [hb]
                                     {:entity [:seon.health/problem (:subject hb)]
                                      :reason (:class hb)
                                      :message (:message hb)}))
                              eff-health-blocks)
          final-tx (order-merge-tx-ops
          (bind-new-ref-tempids ours-db (:tx-data af) identity-attrs)
          (retracted-dh-ident-ops ours-db theirs-db theirs-schemas theirs-keys))
          touched-paths (into touched-paths (:extra-paths af))]
      (let [deleted-paths (into #{}
                                      (keep (fn [{:keys [status path]}]
                                              (when (and (= :deleted status) path) path)))
                                      entity-diffs)]
      {:ok? (empty? all-conflicts)
       :base-commit base-cid
       :ours-commit ours-cid
       :theirs-commit theirs-cid
       :schema-tx new-dh-decls
       :tx-data final-tx
       :auto-fixed (:auto-fixed af)
       :touched-paths touched-paths
       :deleted-paths deleted-paths
       :entity-diffs entity-diffs
       :conflicts all-conflicts}))))