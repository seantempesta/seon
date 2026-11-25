(ns seon.code.reach.delta
  "Commit-delta and O(live-forms) AEVT form/schema/rule diffing extracted from `seon.code.reach`."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [jolt.analyzer]
            [jolt.host]
            [seon.store :as store]))

(def boot-root-keys
  #{"seon.main#ns"
    "seon.main/-main"
    "seon.main/start-flow-instance!"
    "seon.fork/start-fork-parent!"
    "seon.fork/run-fork-parent-loop!"
    "seon.store/open!"
    "seon.schema.datahike/declarations"
    "seon.switchover/run-switch-over!"})

(defn boot-file-path?
  {:malli/schema [:=> [:cat :string] :boolean], :cost/ticks 500000, :cost/bytes 524288}
  [^String path]
  (or (= path "deps.edn")
      (= path "src/seon/main.clj")
      (= path "bin/build")
      (= path "bin/env")
      (str/starts-with? path "native/")
      (str/starts-with? path "reference-code/")))

(defn sort-forms-topologically
  {:malli/schema [:=> [:cat [:sequential [:map [:seon.code.form/key :string] [:seon.code.form/ns :symbol] [:seon.code.form/order {:optional true} :int] [:seon.code.form/deps {:optional true} [:or [:set :symbol] [:sequential :symbol]]]]]] [:vector :map]], :cost/ticks 10000000}
  [forms]
  (let [by-ns (group-by :seon.code.form/ns (filter :seon.code.form/ns forms))
        ns-set (set (keys by-ns))
        ns-deps (into {}
                      (map (fn [[nsym fs]]
                             [nsym (into #{}
                                         (keep (fn [d]
                                                 (let [dns (some-> (namespace d) symbol)]
                                                   (when (and dns (not= dns nsym) (contains? ns-set dns))
                                                     dns))))
                                         (mapcat :seon.code.form/deps fs))]))
                      by-ns)
        visited (volatile! #{})
        ordered-nss (volatile! [])
        visit-ns (fn visit-ns [n path]
                   (when-not (or (contains? @visited n) (contains? path n))
                     (doseq [dep (sort (get ns-deps n))]
                       (visit-ns dep (conj path n)))
                     (vswap! visited conj n)
                     (vswap! ordered-nss conj n)))]
    (doseq [n (sort-by (fn [s] [(if (str/ends-with? (str s) "-test") 1 0) (str s)]) ns-set)]
      (visit-ns n #{}))
    (vec
     (mapcat (fn [nsym]
               (sort-by (fn [f]
                          [(if (str/ends-with? (:seon.code.form/key f "") "#ns") 0 1)
                           (:seon.code.form/order f 0)
                           (:seon.code.form/key f "")])
                        (get by-ns nsym)))
             @ordered-nss))))

(def rule-hash-attrs
  [:seon.rule/id :seon.rule/category :seon.rule/stratum :seon.rule/enabled?
   :seon.rule/target :seon.rule/query :seon.rule/when :seon.rule/assert])

(defonce ^:private ^:seon.flow/edge changed-form-ops-cache (store/lru-cache 32))

(defn- changed-form-ops-aevt
  [from-db to-db]
  (let [aevt-by-e (fn [db attr]
                    (let [m (java.util.HashMap. 4096)]
                      (when (contains? (:schema db) attr)
                        (doseq [dt (d/datoms db :aevt attr)] (.put m (:e dt) (:v dt))))
                      m))
        fk-from (aevt-by-e from-db :seon.code.form/key)
        fk-to   (aevt-by-e to-db :seon.code.form/key)
        fh-from (aevt-by-e from-db :seon.code.form/hash)
        fh-to   (aevt-by-e to-db :seon.code.form/hash)
        fo-from (aevt-by-e from-db :seon.code.form/order)
        fo-to   (aevt-by-e to-db :seon.code.form/order)
        to-key-set (into #{} (vals fk-to))
        retracted-keys (into #{} (remove to-key-set) (vals fk-from))
        touched-eids (volatile! #{})
        _ (doseq [[e k] fk-to]
            (let [ok (.get fk-from e)
                  oh (.get fh-from e) nh (.get fh-to e)
                  oo (.get fo-from e) no (.get fo-to e)]
              (when (or (not= ok k)
                        (not= oh nh)
                        (not= oo no)
                        (and (nil? nh)
                             (not= (:seon.code.form/src (d/pull from-db '[:seon.code.form/src] e))
                                   (:seon.code.form/src (d/pull to-db '[:seon.code.form/src] e)))))
                (vswap! touched-eids conj e))))
        sk-from (aevt-by-e from-db :seon.schema/key)
        sk-to   (aevt-by-e to-db :seon.schema/key)
        sf-from (aevt-by-e from-db :seon.schema/form)
        sf-to   (aevt-by-e to-db :seon.schema/form)
        to-sk-set (into #{} (vals sk-to))
        retracted-schemas (into #{} (remove to-sk-set) (vals sk-from))
        schema-keys (into #{} (keep (fn [[e k]] (when (or (not= (.get sk-from e) k) (not= (.get sf-from e) (.get sf-to e))) k))) sk-to)
        rk-from (aevt-by-e from-db :seon.rule/id)
        rk-to   (aevt-by-e to-db :seon.rule/id)
        to-rk-set (into #{} (vals rk-to))
        retracted-rules (into #{} (remove to-rk-set) (vals rk-from))
        rule-ids (into #{} (keep (fn [[e rid]]
                                   (when (or (not= (.get rk-from e) rid)
                                             (not= (d/pull from-db rule-hash-attrs e) (d/pull to-db rule-hash-attrs e)))
                                     rid)))
                       rk-to)
        fp-from (aevt-by-e from-db :seon.code.file/path)
        fp-to   (aevt-by-e to-db :seon.code.file/path)
        fha-from (aevt-by-e from-db :seon.code.file/hash)
        fha-to   (aevt-by-e to-db :seon.code.file/hash)
        boot-files? (boolean (or (some (fn [[e p]] (and (boot-file-path? (str p)) (or (not= (.get fp-from e) p) (not= (.get fha-from e) (.get fha-to e))))) fp-to)
                                 (some (fn [[e p]] (and (boot-file-path? (str p)) (not (.containsKey fp-to e)))) fp-from)))
        live-forms (->> @touched-eids
                        (keep (fn [eid]
                                (let [f (d/pull to-db '[:seon.code.form/key :seon.code.form/ns :seon.code.form/order
                                                        :seon.code.form/src :seon.code.form/hash :seon.code.form/deps
                                                        :seon.code.form/test?] eid)]
                                  (when (and (:seon.code.form/src f) (:seon.code.form/ns f) (:seon.code.form/key f)) f))))
                        sort-forms-topologically)
        live-keys (into #{} (map :seon.code.form/key) live-forms)
        boot-forms? (boolean (some #(contains? boot-root-keys %) (concat live-keys retracted-keys)))]
    {:upserts live-forms
     :retracted-keys (set/difference retracted-keys live-keys)
     :schema-keys schema-keys
     :retracted-schemas (set/difference retracted-schemas schema-keys)
     :rule-ids rule-ids
     :retracted-rules (set/difference retracted-rules rule-ids)
     :boot-affected? (or boot-files? boot-forms?)}))

(defn changed-form-ops
  {:malli/schema [:=> [:cat :seon.host/conn :any :any] :map]}
  [conn from-cid to-cid]
  (if (or (nil? from-cid) (nil? to-cid) (= from-cid to-cid))
    {:upserts [] :retracted-keys #{} :schema-keys #{} :retracted-schemas #{}
     :rule-ids #{} :retracted-rules #{} :boot-affected? false}
    (let [cur @conn
          sid (get-in cur [:config :store :id])
          ck (when (and sid (uuid? from-cid) (uuid? to-cid)) [sid from-cid to-cid])
          compute
          (fn []
            (let [to-db (cond (= to-cid (d/commit-id cur)) cur (uuid? to-cid) (or (d/commit-as-db conn to-cid) cur) :else cur)
                  from-db (when (uuid? from-cid) (d/commit-as-db conn from-cid))
                  tx-span (when (and from-db to-db)
                            (Math/abs (- (long (or (:max-tx to-db) 0)) (long (or (:max-tx from-db) 0)))))]
              (if (and from-db to-db (> (long tx-span) 100))
                (changed-form-ops-aevt from-db to-db)
                (let [ds (d/changes conn from-cid to-cid)
                      db to-db
                      id-map (:ident-ref-map db)
                      aid? (fn [dt kw] (or (= (:a dt) kw) (= (:a dt) (get id-map kw))))
                      retracted-keys (into #{} (keep #(when (and (not (:added %)) (aid? % :seon.code.form/key)) (:v %))) ds)
                      touched-eids (into #{} (keep #(when (and (:added %) (or (aid? % :seon.code.form/key) (aid? % :seon.code.form/src))) (:e %))) ds)
                      retracted-schemas (into #{} (keep #(when (and (not (:added %)) (aid? % :seon.schema/key)) (:v %))) ds)
                      schema-eids (into #{} (keep #(when (or (aid? % :seon.schema/key) (aid? % :seon.schema/form)) (:e %))) ds)
                      schema-keys (into #{} (keep #(:seon.schema/key (d/pull db '[:seon.schema/key] %))) schema-eids)
                      retracted-rules (into #{} (keep #(when (and (not (:added %)) (aid? % :seon.rule/id)) (:v %))) ds)
                      rule-eids (into #{} (keep #(when (some (fn [rk] (aid? % rk)) rule-hash-attrs) (:e %))) ds)
                      rule-ids (into #{} (keep #(:seon.rule/id (d/pull db '[:seon.rule/id] %))) rule-eids)
                      boot-files? (boolean (some #(when (aid? % :seon.code.file/path) (boot-file-path? (str (:v %)))) ds))
                      live-forms (->> touched-eids
                                      (keep (fn [eid]
                                              (let [f (d/pull db '[:seon.code.form/key :seon.code.form/ns :seon.code.form/order
                                                                   :seon.code.form/src :seon.code.form/hash :seon.code.form/deps
                                                                   :seon.code.form/test?] eid)]
                                                (when (and (:seon.code.form/src f) (:seon.code.form/ns f) (:seon.code.form/key f)) f))))
                                      sort-forms-topologically)
                      live-keys (into #{} (map :seon.code.form/key) live-forms)
                      boot-forms? (boolean (some #(contains? boot-root-keys %) (concat live-keys retracted-keys)))]
                  {:upserts live-forms
                   :retracted-keys (set/difference retracted-keys live-keys)
                   :schema-keys schema-keys
                   :retracted-schemas (set/difference retracted-schemas schema-keys)
                   :rule-ids rule-ids
                   :retracted-rules (set/difference retracted-rules rule-ids)
                   :boot-affected? (or boot-files? boot-forms?)}))))]
      (if ck (store/lru-get! changed-form-ops-cache 32 ck compute) (compute)))))