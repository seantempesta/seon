(ns seon.code.io
  "Lossless Datahike <-> file import/export for Clojure, Markdown, schemas, configs, tasks, and agents."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [seon.code.files :as files]
            [seon.code.parse :as parse :refer [clj-path? md-path? ns-parent-chain-tx parse-md-sections read-clj]]
            [seon.store :as store]
            [clojure.string :as str]
            [seon.cite :as cite]
            [seon.code.analyze :as analyze]))

(defn doc-sections
  {:malli/schema [:=> [:cat :seon.host/db :string] [:vector :map]]}
  [db path]
(->> (d/datoms db :avet :seon.code.doc/path path)
     (map (fn [dt]
            (reduce (fn [m d1]
                      (let [a (:a d1)]
                        (if (contains? #{:seon.code.doc/id :seon.code.doc/path :seon.code.doc/order
                                         :seon.code.doc/heading :seon.code.doc/body} a)
                          (assoc m a (:v d1))
                          m)))
                    {}
                    (d/datoms db :eavt (:e dt)))))
     (sort-by (juxt :seon.code.doc/order :seon.code.doc/id))
     vec))

(defn render-doc [db path]
  (let [secs (doc-sections db path)]
    (when (seq secs)
      (apply str (map (fn [s] (str (:seon.code.doc/heading s "") (:seon.code.doc/body s ""))) secs)))))

(defn ns-forms
  "Returns all forms of namespace `nsym` sorted with `#ns` strictly first, then by `:seon.code.form/order`."
  {:malli/schema [:=> [:cat :seon.host/db :symbol] [:vector :map]]}
  [db nsym]
  (->> (d/datoms db :avet :seon.code.form/ns nsym)
       (map (fn [dt]
       (let [eid (:e dt)]
         (reduce (fn [m d1]
                   (let [a (:a d1)]
                     (case a
                       (:seon.code.form/key :seon.code.form/ns :seon.code.form/order
                        :seon.code.form/lead :seon.code.form/src :seon.code.form/hash
                        :seon.code.form/test?)
                       (assoc m a (:v d1))
                       :seon.code.form/deps
                       (update m a (fnil conj []) (:v d1))
                       m)))
                 {:db/id eid}
                 (d/datoms db :eavt eid)))))
       (sort-by (fn [f]
                  [(if (str/ends-with? (str (:seon.code.form/key f)) "#ns") 0 1)
                   (long (or (:seon.code.form/order f) 0))
                   (str (:seon.code.form/key f))]))
       vec))

(defn ns-vars
  {:malli/schema [:=> [:cat :seon.host/db :symbol] [:vector :map]]}
  [db nsym]
(let [dh-sch (:schema db)]
  (->> (d/datoms db :avet :seon.code.var/ns nsym)
       (map (fn [dt]
              (let [eid (:e dt)]
                (reduce (fn [m d1]
                          (let [a (:a d1)
                                adef (get dh-sch a)
                                v (if (= :db.type/ref (:db/valueType adef))
                                    {:db/id (:v d1)}
                                    (:v d1))]
                            (if (= :db.cardinality/many (:db/cardinality adef))
                              (update m a (fnil conj []) v)
                              (assoc m a v))))
                        {:db/id eid}
                        (d/datoms db :eavt eid)))))
       (sort-by #(str (:seon.code.var/name %)))
       vec)))

(defn render-schema-file
  "Renders all `:seon.schema/key` entities whose owner path is `path` into a clean EDN map."
  [db path]
  (files/render-schema-file db path))

(defn retract-stale-many-ops
"Retracts removed values from `:db.cardinality/many` set attributes (`attrs`) on updated entities
 (normalizing ref lookup-refs to integer eids so ref comparisons never false-retract)."
{:malli/schema
 [:=>
  [:cat
   [:maybe :seon.host/db]
   :qualified-keyword
   [:sequential :qualified-keyword]
   [:sequential :map]]
  [:maybe [:vector :any]]]}
[db id-attr attrs ents]
(when db
  (if (empty? ents)
    []
    (let [dh-sch (:schema db)
          valid-attrs (filterv #(contains? dh-sch %) attrs)
          norm-v (fn [a x]
                   (if (and (= :db.type/ref (get-in dh-sch [a :db/valueType]))
                            (vector? x) (= 2 (count x)) (contains? dh-sch (first x)))
                     (or (:e (first (d/datoms db :avet (first x) (second x)))) x)
                     x))]
      (if (or (empty? valid-attrs) (not (contains? dh-sch id-attr)))
        []
        (vec
         (mapcat
          (fn [ent]
            (let [id-val (get ent id-attr)
                  eid (:e (first (d/datoms db :avet id-attr id-val)))]
              (when eid
                (mapcat
                 (fn [a]
                   (let [old-set (into #{} (map :v) (d/datoms db :eavt eid a))
                         new-set (into #{} (map #(norm-v a %)) (get ent a #{}))]
                     (for [gone (set/difference old-set new-set)]
                       [:db/retract [id-attr id-val] a gone])))
                 valid-attrs))))
          ents)))))))

(defn render-ns [db nsym]
  (when-let [ns-ent (d/pull db '[:seon.code.ns/name :seon.code.ns/tail] [:seon.code.ns/name nsym])]
    (when (:seon.code.ns/name ns-ent)
      (let [fs (ns-forms db nsym)]
        (apply str
               (concat (mapcat (fn [[i f]]
                                 (let [lead (:seon.code.form/lead f "")
                                       eff-lead (if (and (pos? i) (= "" lead)) "\n\n" lead)]
                                   [eff-lead (:seon.code.form/src f "")]))
                               (map-indexed vector fs))
                       [(:seon.code.ns/tail ns-ent "")]))))))

(defn render-path
  {:malli/schema [:=> [:cat :seon.host/db :string] [:maybe :string]]}
  [db path]
  (cond
    (first (d/datoms db :avet :seon.code.ns/path path))
    (let [ns-dt (first (d/datoms db :avet :seon.code.ns/path path))]
      (render-ns db (:seon.code.ns/name (d/pull db '[:seon.code.ns/name] (:e ns-dt)))))

    (md-path? path)
    (or (render-doc db path)
        (:seon.code.file/text (d/pull db '[:seon.code.file/text] [:seon.code.file/path path])))

    (files/schema-file-path? path)
    (files/render-schema-file db path)

    (files/config-file-path? path)
    (files/render-config-file db path)

    (files/task-file-path? path)
    (files/render-tasks-file db path)

    (files/rule-file-path? path)
    (files/render-rules-file db path)

    (files/agent-file-path? path)
    (files/render-agent-file db path)

    :else
    (:seon.code.file/text (d/pull db '[:seon.code.file/text] [:seon.code.file/path path]))))

(defn- ns-form-index [db nsym]
  (when (and db (contains? (:schema db) :seon.code.form/ns))
    (reduce
     (fn [acc dt]
       (let [eid (:e dt)
             info (reduce (fn [m d1]
                            (let [a (:a d1)]
                              (case a
                                :seon.code.form/key   (assoc m :key (:v d1))
                                :seon.code.form/hash  (assoc m :hash (:v d1))
                                :seon.code.form/order (assoc m :order (:v d1))
                                :seon.code.form/lead  (assoc m :lead (:v d1))
                                :seon.code.form/src   (assoc m :src (:v d1))
                                :seon.code.form/test? (assoc m :test? (:v d1))
                                :seon.code.form/deps  (update m :deps (fnil conj #{}) (:v d1))
                                m)))
                          {:eid eid :deps #{}}
                          (d/datoms db :eavt eid))]
         (if-let [k (:key info)]
           (assoc acc k info)
           acc)))
     {}
     (d/datoms db :avet :seon.code.form/ns nsym))))

(defn- ns-var-index [db nsym]
  (when (and db (contains? (:schema db) :seon.code.var/ns))
    (reduce
     (fn [acc dt]
       (let [eid (:e dt)
             info (reduce (fn [m d1]
                            (case (:a d1)
                              :seon.code.var/name   (assoc m :name (:v d1))
                              :seon.code.var/form   (assoc m :form (:v d1))
                              :seon.code.var/ns-ref (assoc m :ns-ref (:v d1))
                              m))
                          {:eid eid}
                          (d/datoms db :eavt eid))]
         (if-let [vn (:name info)]
           (assoc acc vn info)
           acc)))
     {}
     (d/datoms db :avet :seon.code.var/ns nsym))))

(defn- existing-ns-state [db nsym]
  (when (and db (contains? (:schema db) :seon.code.ns/name))
    (when-let [eid (:e (first (d/datoms db :avet :seon.code.ns/name nsym)))]
      (reduce (fn [m d1]
                (case (:a d1)
                  :seon.code.ns/path        (assoc m :path (:v d1))
                  :seon.code.ns/tail        (assoc m :tail (:v d1))
                  :seon.code.ns/doc         (assoc m :doc (:v d1))
                  :seon.code.ns/parent      (assoc m :parent (:v d1))
                  :seon.code.ns/requires    (update m :requires (fnil conj #{}) (:v d1))
                  :seon.code.ns/requires-ns (update m :requires-ns (fnil conj #{}) (:v d1))
                  m))
              {:eid eid :requires #{} :requires-ns #{}}
              (d/datoms db :eavt eid)))))

(defn- ns-delta-ops [db dh-sch ns path tail doc requires req-refs old-ns]
  (let [ns-eid (:eid old-ns)
        ns-chain (ns-parent-chain-tx ns)
        raw-parents (pop ns-chain)
        existing-parent-eids (when (and db (contains? dh-sch :seon.code.ns/name) (seq raw-parents))
                               (reduce (fn [m p]
                                         (if-let [pe (:e (first (d/datoms db :avet :seon.code.ns/name (:seon.code.ns/name p))))]
                                           (assoc m (:seon.code.ns/name p) pe)
                                           m))
                                       {}
                                       raw-parents))
        new-parents (if (seq existing-parent-eids)
                      (vec (keep (fn [p]
                                   (when-not (contains? existing-parent-eids (:seon.code.ns/name p))
                                     (let [psym (parse/parent-ns-sym (:seon.code.ns/name p))
                                           pe (when psym (get existing-parent-eids psym))]
                                       (cond-> p pe (assoc :seon.code.ns/parent pe)))))
                                 raw-parents))
                      raw-parents)
        parent-sym (parse/parent-ns-sym ns)
        parent-eid (when parent-sym (get existing-parent-eids parent-sym))
        base-ns-ent (cond-> (peek ns-chain)
                      parent-eid (assoc :seon.code.ns/parent parent-eid)
                      ns-eid (dissoc :db/id))
        ns-ent (cond-> (assoc base-ns-ent :seon.code.ns/path path :seon.code.ns/tail tail)
                 doc (assoc :seon.code.ns/doc doc)
                 (seq requires) (assoc :seon.code.ns/requires requires)
                 (seq req-refs) (assoc :seon.code.ns/requires-ns req-refs))
        ns-unchanged? (and old-ns
                           (= path (:path old-ns))
                           (= tail (:tail old-ns))
                           (= doc (:doc old-ns))
                           (= (set requires) (:requires old-ns))
                           (= (set req-refs) (:requires-ns old-ns))
                           (or (nil? parent-eid) (= parent-eid (:parent old-ns))))
        stale-ns-reqs (when-not ns-unchanged?
                        (retract-stale-many-ops db :seon.code.ns/name [:seon.code.ns/requires :seon.code.ns/requires-ns] [ns-ent]))]
    {:stale-ns-reqs stale-ns-reqs
     :new-parents new-parents
     :ns-ents (when-not ns-unchanged? [ns-ent])}))

(defn- import-clj-file-tx [db path text batch-ctx]
(let [{:keys [ns doc requires tail forms vars]} (read-clj path text)
      dh-sch (when db (:schema db))
      has-attr? (fn [a] (or (nil? dh-sch) (contains? dh-sch a)))
      new-keys (into #{} (map :seon.code.form/key) forms)
      new-vars (into #{} (map :seon.code.var/name) vars)
      old-forms-idx (ns-form-index db ns)
      old-vars-idx (ns-var-index db ns)
      old-ns (existing-ns-state db ns)
      ns-eid (:eid old-ns)
      form-by-k (into {} (map (juxt :seon.code.form/key identity)) forms)
      existing-var-eids (when old-vars-idx
                          (reduce-kv (fn [m k v] (assoc m k (:eid v))) {} old-vars-idx))
      local-var-refs (into {}
                           (map (fn [v]
                                  (let [vs (:seon.code.var/name v)]
                                    [vs (or (get existing-var-eids vs)
                                            (analyze/var-tempid vs))])))
                           vars)
      changed-forms (if-not old-forms-idx
                      forms
                      (filterv (fn [f]
                                 (let [old (get old-forms-idx (:seon.code.form/key f))]
                                   (not (and old
                                             (= (:seon.code.form/hash f) (:hash old))
                                             (= (:seon.code.form/src f) (:src old))
                                             (= (:seon.code.form/lead f) (:lead old))
                                             (= (:seon.code.form/order f) (:order old))
                                             (= (set (:seon.code.form/deps f)) (:deps old))
                                             (= (boolean (:seon.code.form/test? f)) (boolean (:test? old)))))))
                               forms))
      var-set-delta (when old-vars-idx
                      (let [old-ks (set (keys old-vars-idx))]
                        (when (not= old-ks new-vars)
                          (set/union (set/difference old-ks new-vars)
                                     (set/difference new-vars old-ks)))))
      changed-vars (if-not (and old-forms-idx old-vars-idx)
                     vars
                     (filterv (fn [v]
                                (let [vs (:seon.code.var/name v)
                                      fk (:seon.code.var/form v)
                                      f-ent (get form-by-k fk)
                                      old-f (get old-forms-idx fk)
                                      old-v (get old-vars-idx vs)
                                      refs-delta? (and var-set-delta
                                                       (some #(contains? var-set-delta %) (:seon.code.var/refs v)))]
                                  (or refs-delta?
                                      (not (and old-f old-v
                                                (= (:seon.code.form/hash f-ent) (:hash old-f))
                                                (= (:seon.code.form/src f-ent) (:src old-f))
                                                (= (:form old-v) fk)
                                                (or (nil? ns-eid) (= (:ns-ref old-v) ns-eid)))))))
                              vars))
      need-reg? (and (nil? (:registry batch-ctx))
                     (boolean (some :seon.code.var/schema changed-vars)))
      reg-info (when need-reg? (store/fast-registry-for-db db))
      known-sch (or (:known-schemas batch-ctx)
                    (when reg-info
                      (into (set (keys (:forms reg-info))) (keys (:schema->ref batch-ctx)))))
      eff-ctx (cond-> (-> (or batch-ctx {})
                          (update :var->ref #(merge local-var-refs %))
                          (assoc :assign-tempid? true
                                 :existing-var-eids existing-var-eids
                                 :var-lookup-cache (volatile! {})
                                 :schema-lookup-cache (volatile! {})))
                ns-eid (assoc :ns-eid ns-eid)
                reg-info (assoc :registry (:registry reg-info))
                known-sch (assoc :known-schemas known-sch))
      ns-loaded? (and (not (:skip-compiler-analyze? batch-ctx))
                      (not (str/ends-with? (name ns) "-test"))
                      (boolean (find-ns ns)))
      pairs (mapv (fn [v]
                    (let [fk (:seon.code.var/form v)
                          f-ent (get form-by-k fk)
                          acct (when (and ns-loaded? (not (:seon.code.var/test? v)))
                                 (analyze/analyze-form-account ns (:seon.code.form/src f-ent) false))]
                      [(some? acct) (analyze/enrich-var-with-account db v acct parse/boot-root-keys eff-ctx)]))
                  changed-vars)
      enriched-vars (mapv second pairs)
      stale-forms (when old-forms-idx
                    (for [[fk _] old-forms-idx :when (not (contains? new-keys fk))]
                      [:db/retractEntity [:seon.code.form/key fk]]))
      stale-vars (when old-vars-idx
                   (for [[vn _] old-vars-idx :when (not (contains? new-vars vn))]
                     [:db/retractEntity [:seon.code.var/name vn]]))
      stale-form-deps (retract-stale-many-ops db :seon.code.form/key [:seon.code.form/deps] changed-forms)
      stale-var-a (analyze/stale-var-edge-retracts db (mapv second (filter first pairs))
                    {:preserve-analyzed? false :existing-var-eids existing-var-eids})
      stale-var-p (analyze/stale-var-edge-retracts db (mapv second (remove first pairs))
                    {:preserve-analyzed? true :existing-var-eids existing-var-eids})
      req-refs (when (and (seq requires) (has-attr? :seon.code.ns/requires-ns))
                 (analyze/resolve-ns-requires-refs db requires eff-ctx))
      {:keys [stale-ns-reqs new-parents ns-ents]} (ns-delta-ops db dh-sch ns path tail doc requires req-refs old-ns)]
  (vec (concat stale-forms stale-vars stale-ns-reqs stale-form-deps stale-var-a stale-var-p
               new-parents ns-ents changed-forms enriched-vars))))

(defn file-import-tx
  "Imports any tracked file path into Datahike tx-data with unified graph ref enrichment."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] :string :string :boolean] [:vector :any]]
                  [:=> [:cat [:maybe :seon.host/db] :string :string :boolean [:maybe :map]] [:vector :any]]]}
  ([db path text exec?] (file-import-tx db path text exec? nil))
  ([db path text exec? batch-ctx]
   (cond
     (clj-path? path)
     (import-clj-file-tx db path text batch-ctx)

     (md-path? path)
     (let [secs (cite/enrich-doc-sections db (parse-md-sections path text) batch-ctx)
           new-ids (into #{} (map :seon.code.doc/id) secs)
           stale-docs (when db
                        (for [old (doc-sections db path)
                              :when (not (contains? new-ids (:seon.code.doc/id old)))]
                          [:db/retractEntity [:seon.code.doc/id (:seon.code.doc/id old)]]))
           stale-refs (when (and db (contains? (:schema db) :seon.code.doc/references))
                        (retract-stale-many-ops db :seon.code.doc/id [:seon.code.doc/references] secs))]
       (vec (concat stale-docs stale-refs secs)))

     (files/schema-file-path? path) (files/import-schema-file-tx db path text)
     (files/config-file-path? path) [(store/read-aero-config-tx db text)
                                     {:seon.code.file/path path :seon.code.file/text text :seon.code.file/exec? false}]
     (files/task-file-path? path)   (files/import-tasks-tx db path text)
     (files/rule-file-path? path)   (files/import-rules-tx db path text batch-ctx)
     (files/agent-file-path? path)  (files/import-agents-tx db path text)
     :else [{:seon.code.file/path path :seon.code.file/text text :seon.code.file/exec? (boolean exec?)}])))