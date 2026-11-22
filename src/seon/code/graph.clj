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
                            :seon.schema/render
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
                  (when (and
                          (has? :seon.schema/render)
                          (:seon.schema/render ent)
                          (not=
                            (:seon.schema/render ent)
                            (:seon.schema/render cur)))
                    [[:db/add
                      eid
                      :seon.schema/render
                      (:seon.schema/render ent)]])
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
