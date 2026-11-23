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

(defn doc-sections [db path]
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
       (map #(d/pull db '[:db/id :seon.code.form/key :seon.code.form/ns :seon.code.form/order
                          :seon.code.form/lead :seon.code.form/src :seon.code.form/hash
                          :seon.code.form/deps :seon.code.form/test?] (:e %)))
       (sort-by (fn [f]
                  [(if (str/ends-with? (str (:seon.code.form/key f)) "#ns") 0 1)
                   (long (or (:seon.code.form/order f) 0))
                   (str (:seon.code.form/key f))]))
       vec))

(defn ns-vars [db nsym]
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
  "Retracts removed values from `:db.cardinality/many` set attributes (`attrs`) on updated entities\n        (normalizing ref lookup-refs to integer eids so ref comparisons never false-retract)."
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
    (let [dh-sch (:schema db)
          norm-v (fn [a x]
                   (if (and
                         (=
                           :db.type/ref
                           (get-in dh-sch [a :db/valueType]))
                         (vector? x)
                         (= 2 (count x))
                         (contains? dh-sch (first x)))
                     (or
                       (:e
                         (first
                           (d/datoms db :avet (first x) (second x))))
                       x)
                     x))]
      (vec
        (mapcat
          (fn [ent]
            (let [id-val (get ent id-attr)
                  eid (when (contains? dh-sch id-attr)
                        (:e
                          (first (d/datoms db :avet id-attr id-val))))]
              (when eid
                (mapcat
                  (fn [a]
                    (when (contains? dh-sch a)
                      (let [old-set (into
                                      #{}
                                      (map :v)
                                      (d/datoms db :eavt eid a))
                            new-set (into
                                      #{}
                                      (map #(norm-v a %))
                                      (get ent a #{}))]
                        (for
                          [gone (set/difference old-set new-set)]
                          [:db/retract [id-attr id-val] a gone]))))
                  attrs))))
          ents)))))

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

(defn- import-clj-file-tx [db path text batch-ctx]
  (let [{:keys [ns doc requires tail forms vars]} (read-clj path text)
        dh-sch (when db (:schema db))
        has-attr? (fn [a] (or (nil? dh-sch) (contains? dh-sch a)))
        new-keys (into #{} (map :seon.code.form/key) forms)
        new-vars (into #{} (map :seon.code.var/name) vars)
        old-forms-by-k (when db (into {} (map (juxt :seon.code.form/key :seon.code.form/hash)) (ns-forms db ns)))
        form-by-k (into {} (map (juxt :seon.code.form/key identity)) forms)
        local-var-refs (into {}
                             (map (fn [v]
                                    (let [vs (:seon.code.var/name v)]
                                      [vs (or (when (and db (contains? dh-sch :seon.code.var/name))
                                                (:e (first (d/datoms db :avet :seon.code.var/name vs))))
                                              (analyze/var-tempid vs))])))
                             vars)
        eff-ctx (-> (or batch-ctx {})
                    (update :var->ref #(merge local-var-refs %))
                    (assoc :assign-tempid? true))
        ns-loaded? (and (not (:skip-compiler-analyze? batch-ctx))
                        (not (str/ends-with? (name ns) "-test"))
                        (boolean (find-ns ns)))
        pairs (mapv (fn [v]
                      (let [fk (:seon.code.var/form v)
                            f-ent (get form-by-k fk)
                            unchanged? (and old-forms-by-k (= (:seon.code.form/hash f-ent) (get old-forms-by-k fk)))
                            acct (when (and ns-loaded? (not unchanged?) (not (:seon.code.var/test? v)))
                                   (analyze/analyze-form-account ns (:seon.code.form/src f-ent) false))]
                        [(some? acct) (analyze/enrich-var-with-account db v acct parse/boot-root-keys eff-ctx)]))
                    vars)
        enriched-vars (mapv second pairs)
        stale-forms (when db
                      (for [old (ns-forms db ns)
                            :when (not (contains? new-keys (:seon.code.form/key old)))]
                        [:db/retractEntity [:seon.code.form/key (:seon.code.form/key old)]]))
        stale-vars (when db
                     (for [old (ns-vars db ns)
                           :when (not (contains? new-vars (:seon.code.var/name old)))]
                       [:db/retractEntity [:seon.code.var/name (:seon.code.var/name old)]]))
        stale-form-deps (retract-stale-many-ops db :seon.code.form/key [:seon.code.form/deps] forms)
        stale-var-a (analyze/stale-var-edge-retracts db (mapv second (filter first pairs)) {:preserve-analyzed? false})
        stale-var-p (analyze/stale-var-edge-retracts db (mapv second (remove first pairs)) {:preserve-analyzed? true})
        ns-chain (ns-parent-chain-tx ns)
        req-refs (when (and (seq requires) (has-attr? :seon.code.ns/requires-ns))
                   (analyze/resolve-ns-requires-refs db requires eff-ctx))
        ns-ent (cond-> (assoc (peek ns-chain) :seon.code.ns/path path :seon.code.ns/tail tail)
                 doc (assoc :seon.code.ns/doc doc)
                 (seq requires) (assoc :seon.code.ns/requires requires)
                 (seq req-refs) (assoc :seon.code.ns/requires-ns req-refs))
        stale-ns-reqs (retract-stale-many-ops db :seon.code.ns/name [:seon.code.ns/requires :seon.code.ns/requires-ns] [ns-ent])]
    (vec (concat stale-forms stale-vars stale-ns-reqs stale-form-deps stale-var-a stale-var-p
                 (pop ns-chain) [ns-ent] forms enriched-vars))))

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