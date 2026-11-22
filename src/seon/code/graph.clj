(ns seon.code.graph
  "Canonical Code & Schema Ref Graph: Malli AST input/output extraction and idempotent
        codebase ref-linking + compiler-analysis backfill."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.cite :as cite]
            [seon.code.files :as files]
            [seon.schema :as schema]))

(defn var-tempid
  "Deterministic string tempid for a `:seon.code.var/entity` symbol `vsym`."
  {:malli/schema [:=> [:cat :qualified-symbol] :string],
   :cost/ticks 5000}
  [vsym]
  (str "var/" (namespace vsym) "/" (name vsym)))

(defn ns-tempid
  "Deterministic string tempid for a `:seon.code.ns/entity` symbol `nsym`."
  {:malli/schema [:=> [:cat :symbol] :string], :cost/ticks 5000}
  [nsym]
  (str "ns/" nsym))

(defn- walk-ast-schemas [node known-keys opt?]
  (if-not (map? node)
    {:required #{}, :optional #{}}
    (let [t (:type node)
          bucket (if opt? :optional :required)
          merge-acc (fn [a b]
                      {:required (into (:required a) (:required b)),
                       :optional (into (:optional a) (:optional b))})
          b0 (if (and
                   (= t :malli.core/schema)
                   (contains? known-keys (:value node)))
               (assoc
                 {:required #{}, :optional #{}}
                 bucket
                 #{(:value node)})
               {:required #{}, :optional #{}})
          b1 (cond->
               b0
               (:child node)
               (merge-acc
                 (walk-ast-schemas (:child node) known-keys opt?))
               (:key node)
               (merge-acc
                 (walk-ast-schemas (:key node) known-keys opt?))
               (and (= t :map-of) (:value node))
               (merge-acc
                 (walk-ast-schemas (:value node) known-keys opt?)))
          b2 (reduce
               (fn [acc ch]
                 (merge-acc acc (walk-ast-schemas ch known-keys opt?)))
               b1
               (:children node))]
      (if (map? (:keys node))
        (reduce-kv
          (fn [acc k entry]
            (let [is-map? (= t :map)
                  e-opt? (or
                           opt?
                           (and
                             is-map?
                             (boolean
                               (get-in
                                 entry
                                 [:properties :optional]))))
                  a1 (if (and is-map? (contains? known-keys k))
                       (update
                         acc
                         (if e-opt? :optional :required)
                         conj
                         k)
                       acc)]
              (merge-acc
                a1
                (walk-ast-schemas (:value entry) known-keys e-opt?))))
          b2
          (:keys node))
        b2))))

(defn extract-malli-io-keys
  "Extracts `{:input #{...} :optional-input #{...} :output #{...}}` from a `:malli/schema` form\n        via `malli.core/ast` (handles `:=>` & `:function`, never leaks `:examples`)."
  {:malli/schema
   [:function
    [:=> [:cat :any :any] :map]
    [:=> [:cat :any :any :any] :map]],
   :cost/ticks 1000000}
  ([schema-form registry]
    (extract-malli-io-keys schema-form registry nil))
  ([schema-form registry known-keys]
    (if-not (and
              (vector? schema-form)
              (contains? #{:function :=>} (first schema-form)))
      {:input #{}, :optional-input #{}, :output #{}}
      (let [reg (when (and registry (satisfies? mr/Registry registry))
                  registry)
            opts (if reg {:registry reg} {})
            ks (cond
                 (set? known-keys) known-keys
                 (coll? known-keys) (set known-keys)
                 :else (set (keys (:forms @schema/base-registry))))
            ast (try
                  (m/ast schema-form opts)
                  (catch clojure.lang.ExceptionInfo _ nil))
            arities (case
                      (:type ast)
                      :=>
                      [ast]
                      :function
                      (:children ast)
                      [])
            raw (reduce
                  (fn [acc ar]
                    (let [in-r (walk-ast-schemas (:input ar) ks false)
                          out-r (walk-ast-schemas
                                  (:output ar)
                                  ks
                                  false)]
                      {:input (into (:input acc) (:required in-r)),
                       :optional-input
                       (into (:optional-input acc) (:optional in-r)),
                       :output
                       (into
                         (:output acc)
                         (into (:required out-r) (:optional out-r)))}))
                  {:input #{}, :optional-input #{}, :output #{}}
                  arities)]
        (update raw :optional-input set/difference (:input raw))))))

(defn resolve-ns-requires-refs
  "Resolves internal namespace symbols in `reqs` to `:seon.code.ns/entity` refs."
  {:malli/schema
   [:function
    [:=> [:cat [:maybe :seon.host/db] :any] [:set :any]]
    [:=>
     [:cat [:maybe :seon.host/db] :any [:maybe :map]]
     [:set :any]]]}
  ([db reqs] (resolve-ns-requires-refs db reqs nil))
  ([db reqs batch-ctx]
    (let [dh-sch (when db (:schema db))
          has-ns? (and db (contains? dh-sch :seon.code.ns/name))
          ns->ref (:ns->ref batch-ctx)]
      (into
        #{}
        (keep
          (fn [r]
            (when (symbol? r)
              (or
                (when has-ns?
                  (:e
                    (first (d/datoms db :avet :seon.code.ns/name r))))
                (get ns->ref r)))))
        reqs))))

(defn- diff-many-attr [db eid attr desired]
  (let [cur (into #{} (map :v) (d/datoms db :eavt eid attr))
        des (or desired #{})]
    (concat
      (for [g (remove des cur)] [:db/retract eid attr g])
      (for [a (remove cur des)] [:db/add eid attr a]))))

(defn- backfill-schemas-tx [db]
  (let [dh-sch (:schema db)]
    (if-not (contains? dh-sch :seon.schema/key)
      []
      (let [{:keys [registry]} (schema/registry-for-db db)
            has? #(contains? dh-sch %)]
        (vec
          (mapcat
            (fn [dt]
              (let [eid (:e dt)
                    k (:v dt)
                    cur (d/pull
                          db
                          '[:seon.schema/form
                            :seon.schema/doc
                            :seon.schema/type]
                          eid)
                    ent (schema/enrich-schema-entity-tx
                          db
                          (assoc cur :seon.schema/key k)
                          registry)]
                (concat
                  (when (and
                          (has? :seon.schema/type)
                          (:seon.schema/type ent)
                          (not=
                            (:seon.schema/type ent)
                            (:seon.schema/type cur)))
                    [[:db/add
                      eid
                      :seon.schema/type
                      (:seon.schema/type ent)]])
                  (when (and
                          (has? :seon.schema/doc)
                          (:seon.schema/doc ent)
                          (not=
                            (:seon.schema/doc ent)
                            (:seon.schema/doc cur)))
                    [[:db/add
                      eid
                      :seon.schema/doc
                      (:seon.schema/doc ent)]])
                  (when (has? :seon.schema/keys)
                    (diff-many-attr
                      db
                      eid
                      :seon.schema/keys
                      (:seon.schema/keys ent)))
                  (when (has? :seon.schema/optional-keys)
                    (diff-many-attr
                      db
                      eid
                      :seon.schema/optional-keys
                      (:seon.schema/optional-keys ent)))
                  (when (has? :seon.schema/ref-targets)
                    (diff-many-attr
                      db
                      eid
                      :seon.schema/ref-targets
                      (:seon.schema/ref-targets ent))))))
            (d/datoms db :aevt :seon.schema/key)))))))

(defn- backfill-nss-tx [db]
  (let [dh-sch (:schema db)]
    (if-not (and
              (contains? dh-sch :seon.code.ns/name)
              (contains? dh-sch :seon.code.ns/requires)
              (contains? dh-sch :seon.code.ns/requires-ns))
      []
      (let [ns->eid (into
                      {}
                      (map (juxt :v :e))
                      (d/datoms db :aevt :seon.code.ns/name))]
        (vec
          (mapcat
            (fn [[_ eid]]
              (let [reqs (map
                           :v
                           (d/datoms
                             db
                             :eavt
                             eid
                             :seon.code.ns/requires))
                    desired (into
                              #{}
                              (keep
                                (fn 
                                  [r]
                                  (let 
                                    [te (get ns->eid r)]
                                    (when (and te (not= te eid)) te))))
                              reqs)]
                (diff-many-attr
                  db
                  eid
                  :seon.code.ns/requires-ns
                  desired)))
            ns->eid))))))

(defn- backfill-rules-tx [db]
  (let [dh-sch (:schema db)]
    (if-not (and
              (contains? dh-sch :seon.rule/id)
              (or
                (contains? dh-sch :seon.rule/schema)
                (contains? dh-sch :seon.rule/fn)))
      []
      (let [sch->eid (if (contains? dh-sch :seon.schema/key)
                       (into
                         {}
                         (map (juxt :v :e))
                         (d/datoms db :aevt :seon.schema/key))
                       {})
            vname->eid (if (contains? dh-sch :seon.code.var/name)
                         (into
                           {}
                           (map (juxt :v :e))
                           (d/datoms db :aevt :seon.code.var/name))
                         {})]
        (vec
          (mapcat
            (fn [dt]
              (let [eid (:e dt)
                    rm (d/pull
                         db
                         (quote [:seon.rule/query :seon.rule/when :seon.rule/assert])
                         eid)
                    {:keys [schemas fns]} (files/extract-rule-refs rm)
                    s-refs (into #{} (keep sch->eid) schemas)
                    f-refs (into #{} (keep vname->eid) fns)]
                (concat
                  (when (contains? dh-sch :seon.rule/schema)
                    (diff-many-attr db eid :seon.rule/schema s-refs))
                  (when (contains? dh-sch :seon.rule/fn)
                    (diff-many-attr db eid :seon.rule/fn f-refs)))))
            (d/datoms db :aevt :seon.rule/id)))))))

(defn- backfill-test-var-refs-tx [db]
  (let [dh-sch (:schema db)]
    (if-not (and
              (contains? dh-sch :seon.test/var)
              (contains? dh-sch :seon.test/var-ref)
              (contains? dh-sch :seon.code.var/name))
      []
      (let [vname->eid (into
                         {}
                         (map (juxt :v :e))
                         (d/datoms db :aevt :seon.code.var/name))
            cur-ref (into
                      {}
                      (map (juxt :e :v))
                      (d/datoms db :aevt :seon.test/var-ref))]
        (vec
          (keep
            (fn [dt]
              (let [eid (:e dt) target (get vname->eid (:v dt))]
                (when (and target (not= target (get cur-ref eid)))
                  [:db/add eid :seon.test/var-ref target])))
            (d/datoms db :aevt :seon.test/var)))))))

(defn- backfill-one-var-ops [db ctx vsym eid]
  (let [{:keys [has? vname->eid eid->ns eid->fk eid->sch test-eids fk->src sch->eid known-schs reg analyze-fn ns-filter]} ctx
        nsym (get eid->ns eid)]
    (when (or (nil? ns-filter) (contains? ns-filter nsym))
      (let [is-test? (or (contains? test-eids eid)
                         (and nsym (str/ends-with? (name nsym) "-test")))
            acct (when (and analyze-fn (not is-test?) nsym)
                   (when-let [src (get fk->src (get eid->fk eid))]
                     (analyze-fn nsym src true)))
            cur-refs (if (has? :seon.code.var/refs)
                       (into #{} (map :v) (d/datoms db :eavt eid :seon.code.var/refs))
                       #{})
            extra-refs (into #{} (remove #(or (= vsym %) (contains? cur-refs %))) (:refs acct))
            all-refs (into cur-refs extra-refs)
            calls (into #{} (keep (fn [s] (when (not= s vsym) (get vname->eid s)))) all-refs)
            malli-sch (get eid->sch eid)
            io-res (when malli-sch (extract-malli-io-keys malli-sch reg known-schs))
            in-refs (when io-res (into #{} (keep sch->eid) (:input io-res)))
            opt-refs (when io-res (into #{} (keep sch->eid) (:optional-input io-res)))
            out-refs (when io-res (into #{} (keep sch->eid) (:output io-res)))
            cur-catches (when (has? :seon.code.var/catches)
                          (:v (first (d/datoms db :eavt eid :seon.code.var/catches))))
            new-catches (when (and acct (seq (:catches acct))) (vec (:catches acct)))]
        (concat
         (when (has? :seon.code.var/refs)
           (for [r extra-refs] [:db/add eid :seon.code.var/refs r]))
         (when (has? :seon.code.var/calls) (diff-many-attr db eid :seon.code.var/calls calls))
         (when (has? :seon.code.var/input) (diff-many-attr db eid :seon.code.var/input in-refs))
         (when (has? :seon.code.var/optional-input) (diff-many-attr db eid :seon.code.var/optional-input opt-refs))
         (when (has? :seon.code.var/output) (diff-many-attr db eid :seon.code.var/output out-refs))
         (when (and acct (has? :seon.code.var/ops))
           (diff-many-attr db eid :seon.code.var/ops (set (:ops acct))))
         (when (and acct (has? :seon.code.var/catches))
           (cond
             (and new-catches (not= new-catches cur-catches))
             [[:db/add eid :seon.code.var/catches new-catches]]
             (and (nil? new-catches) (some? cur-catches))
             [[:db/retract eid :seon.code.var/catches cur-catches]])))))))

(defn- backfill-vars-tx [db {:keys [analyze-fn namespaces]}]
  (let [dh-sch (:schema db)]
    (if-not (contains? dh-sch :seon.code.var/name)
      []
      (let [has? #(contains? dh-sch %)
            vname->eid (into {} (map (juxt :v :e)) (d/datoms db :aevt :seon.code.var/name))
            fk->src (if (and analyze-fn (has? :seon.code.form/key) (has? :seon.code.form/src))
                      (let [ek (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.code.form/key))]
                        (reduce (fn [m dt] (if-let [k (get ek (:e dt))] (assoc m k (:v dt)) m))
                                {} (d/datoms db :aevt :seon.code.form/src)))
                      {})
            sch->eid (if (has? :seon.schema/key) (into {} (map (juxt :v :e)) (d/datoms db :aevt :seon.schema/key)) {})
            ctx {:has? has?
                 :vname->eid vname->eid
                 :eid->ns (if (has? :seon.code.var/ns) (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.code.var/ns)) {})
                 :eid->fk (if (has? :seon.code.var/form) (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.code.var/form)) {})
                 :eid->sch (if (has? :seon.code.var/schema) (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.code.var/schema)) {})
                 :test-eids (if (has? :seon.code.var/test?) (into #{} (keep #(when (:v %) (:e %))) (d/datoms db :aevt :seon.code.var/test?)) #{})
                 :fk->src fk->src
                 :sch->eid sch->eid
                 :known-schs (set (keys sch->eid))
                 :reg (:registry (schema/registry-for-db db))
                 :analyze-fn analyze-fn
                 :ns-filter (when (seq namespaces) (set namespaces))}]
        (vec (mapcat (fn [[vsym eid]] (backfill-one-var-ops db ctx vsym eid)) vname->eid))))))

(defn backfill-codebase-analysis-tx
  "Idempotent post-import & backfill pass linking all canonical code/schema/rule/doc/test refs\n        (and optionally running `analyze-fn` for compiler-exact `:calls`, `:ops`, `:catches`)."
  {:malli/schema
   [:function
    [:=> [:cat :seon.host/db] [:vector :any]]
    [:=> [:cat :seon.host/db :map] [:vector :any]]],
   :seon.eval/budget-override {:ticks 1000000000, :bytes 4294967296}}
  ([db] (backfill-codebase-analysis-tx db {}))
  ([db opts]
    (vec
      (concat
        (backfill-schemas-tx db)
        (backfill-nss-tx db)
        (backfill-vars-tx db opts)
        (backfill-rules-tx db)
        (cite/backfill-doc-refs-tx db)
        (backfill-test-var-refs-tx db)))))
