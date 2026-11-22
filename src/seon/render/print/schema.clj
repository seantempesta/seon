(ns seon.render.print.schema
  "Stage 1 of 4-Stage Printer: Sub-schema fingerprinting (`seon.schema.index`), `:seon.render/hiccup` dispatch, and DB handle inspection."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.schema.index :as six]))

(defn valid-ix?
  "True when `ix` has the compiled structure returned by `seon.schema.index/build-entity-index`."
  {:malli/schema [:=> [:cat :any] :boolean]
   :cost/ticks 20000
   :cost/bytes 8192}
  [ix]
  (and (map? ix) (map? (:entity-schemas ix)) (map? (:forms ix)) (map? (:value-schemas ix))
       (map? (:required-keys ix)) (map? (:parents ix)) (map? (:schema-identities ix))))

(defn- db-summary [db]
  (let [cfg (:config db) sid (get-in cfg [:store :id])]
    (cond-> {:db-type (cond (instance? datahike.db.AsOfDB db) :as-of
                            (instance? datahike.db.HistoricalDB db) :history :else :db)
             :branch (get cfg :branch :main) :max-tx (:max-tx db)
             :max-eid (:max-eid db) :attrs-count (count (:schema db))}
      sid (assoc :store-id sid)
      (:time-point db) (assoc :time-point (:time-point db)))))

(defn handle-info
  "Recognizes live or tagged Datahike DB and Connection handles and returns a concise summary map."
  {:malli/schema [:=> [:cat :any] [:maybe :map]]
   :cost/ticks 50000
   :cost/bytes 32768}
  [x]
  (let [thk (when (instance? clojure.lang.IMeta x) (:seon.print/kind (meta x)))]
    (cond
      (contains? #{:datahike/db :datahike/as-of-db :datahike/historical-db} thk)
      {:kind :datahike/db :schema :seon.host/db :summary (if (map? x) (dissoc x :seon.print/kind) {:value x})}
      (= :datahike/conn thk)
      {:kind :datahike/conn :schema :seon.host/conn :summary (if (map? x) (dissoc x :seon.print/kind) {:value x})}
      (or (instance? datahike.db.DB x) (instance? datahike.db.AsOfDB x) (instance? datahike.db.HistoricalDB x))
      {:kind :datahike/db :schema :seon.host/db :summary (db-summary x)}
      (instance? datahike.connector.Connection x)
      {:kind :datahike/conn :schema :seon.host/conn :summary (assoc (db-summary @x) :conn? true)}
      :else nil)))

(defn lookup-ref-schema
  "Resolves the entity schema key for a `[id-attr val]` Datahike lookup ref."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :any :any] [:maybe :keyword]]
   :cost/ticks 50000
   :cost/bytes 16384}
  [db ix attr]
  (when (qualified-keyword? attr)
    (or (when (valid-ix? ix) (get-in ix [:identity-schemas attr]))
        (when (= attr :seon.health/summary) :seon.health/report)
        (when (and db (get-in db [:schema attr :db/unique])) :seon.store/ref))))

(defn resolve-lookup-ref
  "Safely looks up an indexed `[attr id-val]` ref in `db` when indexed."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :keyword :any] :map]
   :cost/ticks 500000
   :cost/bytes 262144}
  [db attr id-val]
  (let [idx? (and db (not= attr :seon.health/summary)
                  (or (get-in db [:schema attr :db/unique]) (get-in db [:schema attr :db/index])))
        eid (when idx? (:e (first (d/datoms db :avet attr id-val))))
        pulled (when eid (dissoc (d/pull db '[*] eid) :db/id))]
    {:indexed? (boolean idx?) :eid eid :pulled pulled}))

(defn schema-renderer-sym
  "Looks up the `:seon.render/hiccup` renderer symbol for schema `sk` in `ix` or `db`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :any :keyword] [:maybe :symbol]]
   :cost/ticks 200000
   :cost/bytes 65536}
  [db ix sk]
  (or (when (valid-ix? ix) (six/schema-property ix sk :seon.render/hiccup))
      (when (and db (contains? (:schema db) :seon.schema/key))
        (let [e (d/pull db '[:seon.schema/render :seon.schema/form] [:seon.schema/key sk])]
          (or (:seon.schema/render e)
              (when (and (vector? (:seon.schema/form e)) (map? (second (:seon.schema/form e))))
                (:seon.render/hiccup (second (:seon.schema/form e)))))))))

(defn- val-first-arity? [args1]
  (when (and (vector? args1) (= 2 (count args1)))
    (let [a0 (first args1) a1 (second args1)]
      (cond (and (map? a1) (not (map? a0))) true
            (symbol? a1) (let [sn (name a1)] (or (str/includes? sn "opts") (= sn "_opts")))
            :else false))))

(defn fingerprint-map
  "Returns `{:raw-fs :ms :sch :all-fs}` for map `val` using compiled schema index `ix`."
  {:malli/schema [:=> [:cat :any :map [:maybe :keyword]] :map]
   :cost/ticks 1000000
   :cost/bytes 262144}
  [ix val fallback-sch]
  (let [raw-fs (if (valid-ix? ix) (six/facets-of ix val) [])
        ms (when (valid-ix? ix) (six/most-specific ix val))
        sch (cond (qualified-keyword? ms) ms
                  (seq raw-fs) (:schema (first raw-fs))
                  (instance? clojure.lang.IMeta val) (:seon/schema (meta val))
                  :else fallback-sch)
        all-fs (if (seq raw-fs) (mapv :schema raw-fs) (if sch [sch] []))]
    {:raw-fs raw-fs :ms ms :sch sch :all-fs all-fs}))

(defn entity-cycle-id
  "Builds a stable `[attr id]` cycle key for entity map `m` under schema `sk`."
  {:malli/schema [:=> [:cat :any [:maybe :keyword] :any] [:maybe [:vector :any]]]
   :cost/ticks 50000
   :cost/bytes 16384}
  [ix sk m]
  (when (map? m)
    (or (when-let [eid (:db/id m)] [:eid eid])
        (when-let [ida (when (and sk (valid-ix? ix)) (get-in ix [:schema-identities sk]))]
          (when (contains? m ida) [ida (get m ida)])))))

(defn detect-elem-info
  "Detects homogeneous element schema and tabular columns from a bounded collection sample."
  {:malli/schema [:=> [:cat :any :any] :map]
   :cost/ticks 500000
   :cost/bytes 131072}
  [ix sample]
  (let [s (if (sequential? sample) (vec (take 5 sample)) [])]
    (cond
      (empty? s) {:element-schema nil :tabular? false :columns nil}
      (every? #(instance? datahike.datom.Datom %) s) {:element-schema :datahike/datom :tabular? false :columns nil}
      (every? map? s)
      (let [ss (when (valid-ix? ix) (mapv #(six/most-specific ix %) s))
            s0 (first ss)
            esh (when (and (qualified-keyword? s0) (every? #(= s0 %) ss)) s0)
            tab? (and (>= (count s) 2) (>= (count (apply set/intersection (map #(set (keys %)) s))) 2))]
        {:element-schema esh :tabular? tab?
         :columns (when tab? (->> s (mapcat keys) distinct (sort-by str) (take 12) vec))})
      :else {:element-schema nil :tabular? false :columns nil})))

(defn ^:seon.flow/edge resolve-sym
  "Resolves a qualified Clojure var symbol at runtime (requiring its namespace if needed)."
  {:malli/schema [:=> [:cat [:maybe :symbol]] :any]
   :cost/ticks 500000
   :cost/bytes 131072}
  [sym]
  (when (qualified-symbol? sym)
    (or (try (resolve sym) (catch Throwable _ nil))
        (try (requiring-resolve sym) (catch Throwable _ nil)))))

(defn invoke-sub-renderer
  "Fault-isolated dispatch to a sub-schema's `:seon.render/hiccup` renderer `[ropts val]` or `[val ropts]`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :keyword :symbol :map :any] [:maybe :map]]
   :cost/ticks 2000000
   :cost/bytes 1048576}
  [db sk rsym opts val]
  (let [rv (resolve-sym rsym)
      rfn (when (and rv (bound? rv)) @rv)]
  (when (fn? rfn)
    (let [vf? (val-first-arity? (first (:arglists (meta rv))))
          ropts (assoc opts :db db :schema sk)]
      (try
        {:ok? true :hiccup (try (if vf? (rfn val ropts) (rfn ropts val))
                                (catch clojure.lang.ArityException _ (rfn val)))}
        (catch Throwable ex {:ok? false :error (or (.getMessage ex) (str ex))}))))))
