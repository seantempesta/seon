(ns seon.health.static.forms
  "Form-level static health snapshots, missing-ns checks, and used-before-defined analysis extracted from `seon.health.static`."
  (:require [clojure.core.cache :as cache]
            [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [datahike.datom :as dd]
            [datahike.index.interface :as di]
            [edamame.core :as eda]
            [malli.core :as m]
            [malli.error :as me]
            [seon.ctx.diff :as diff]
            [seon.schema :as schema]
            [seon.store :as store]))

(defn record-aevt-reads!
  {:doc "Records full `:aevt` read ranges on `datahike.index.interface/*reads*` for `attrs`.", :malli/schema [:=> [:cat [:sequential :keyword]] :nil], :seon.flow/edge true}
  [attrs]
  (when di/*reads*
    (doseq [a attrs]
      (di/record-read! (dd/datom 0 a nil 0) (dd/datom Long/MAX_VALUE a nil Long/MAX_VALUE)))))

(defonce ^:private ^:seon.flow/edge declare-cache (store/lru-cache 512))

(defn- parse-declare-syms [s]
  (let [tr (str/triml s)]
    (when (str/starts-with? tr "(declare ")
      (let [f (try (eda/parse-string tr {:read-cond :allow :features #{:clj} :readers (fn [_] identity)})
                   (catch clojure.lang.ExceptionInfo _ nil))]
        (when (seq? f)
          (vec (filter symbol? (rest f))))))))

(defn- cached-declare-syms [db e h]
  (if h
    (let [raw (get @declare-cache h :seon.health.static/miss)
          cached (if (and (map? raw) (contains? raw :seon.store/lru-val)) (:seon.store/lru-val raw) raw)]
      (if-not (identical? :seon.health.static/miss cached)
        cached
        (let [s (str (:v (first (d/datoms db :eavt e :seon.code.form/src))))]
          (store/lru-get! declare-cache 512 h #(parse-declare-syms s)))))
    (parse-declare-syms (str (:v (first (d/datoms db :eavt e :seon.code.form/src)))))))

(defn- ns-used-before-defined [db br nsym raw-forms ^java.util.HashMap deps-hm]
  (let [forms (sort-by :order raw-forms)
        def-orders (into {} (keep (fn [{:keys [key order]}]
                                    (when (and key (not (str/includes? key "#"))) [(symbol key) order])))
                         forms)
        candidates (keep (fn [{:keys [e key order deps]}]
                           (let [ds (or deps (when deps-hm (.get deps-hm e)))
                                 fwd (when (seq ds)
                                       (->> ds (filter #(when-let [dord (get def-orders %)] (> dord order))) sort))]
                             (when (seq fwd) {:key key :order order :fwd fwd})))
                         forms)
        expr-forms (filter #(and (:key %) (str/includes? (:key %) "#expr#")) forms)
        decls (reduce
               (fn [acc {:keys [e key order hash]}]
                 (if-let [syms (seq (cached-declare-syms db e hash))]
                   (reduce (fn [m sym]
                             (let [qsym (symbol (name nsym) (name sym))]
                               (-> m (update :orders assoc qsym order) (update :entries conj {:key key :sym qsym}))))
                           acc syms)
                   acc))
               {:orders {} :entries []} expr-forms)
        decl-orders (:orders decls)
        dangling (keep (fn [{:keys [key sym]}]
                         (when-not (contains? def-orders sym)
                           {:class :dangling-declare :severity :error :kind :static
                            :ns nsym :subject key :branch br
                            :message (str "Form " key " declares " sym " which is never defined in " nsym ". Remove dead declare or define it.")}))
                       (:entries decls))
        fwd-errs (when (seq candidates)
                   (keep (fn [{:keys [key order fwd]}]
                           (let [bad (filter #(let [decl (get decl-orders %)] (or (nil? decl) (> decl order))) fwd)]
                             (when (seq bad)
                               {:class :used-before-defined :severity :error :kind :static
                                :ns nsym :subject key :branch br
                                :fix {:fn 'seon.code.edit/reorder-ns-forms! :args [nsym]}
                                :message (str "Form " key " references " (str/join ", " bad)
                                              " before definition (breaks cold AOT builds). Run (user/autofix-health!) to topologically reorder, or add (declare ...).")})))
                         candidates))]
    (concat dangling fwd-errs)))

(defonce ^:private ^:seon.flow/edge ubd-ns-cache (store/lru-cache 256))

(defonce ^:private ^:seon.flow/edge form-meta-snap-cache (store/lru-cache 16))

(defn- used-before-defined-all [db br snap]
  (let [by-ns (:src-forms-by-ns snap)
        has-deps? (contains? (:schema db) :seon.code.form/deps)
        pending (volatile! [])
        out (transient [])
        new-entries (volatile! {})]
    (doseq [[nsym raw-forms] (sort-by key by-ns)]
      (let [sig (when (every? :hash raw-forms) (mapv (juxt :key :order :hash) (sort-by :order raw-forms)))
            hit (when (and has-deps? sig) (get @ubd-ns-cache [br nsym sig] :seon.health.static/miss))]
        (if (and sig (not (identical? :seon.health.static/miss hit)))
          (doseq [x hit] (conj! out x))
          (vswap! pending conj [nsym sig raw-forms]))))
    (when (seq @pending)
      (let [miss-n (count @pending)
            deps-hm (java.util.HashMap. (if (> miss-n 25) 1400 (* 16 miss-n)))]
        (when has-deps?
          (if (> miss-n 25)
            (let [e->ns (java.util.HashMap. 1400)]
              (doseq [[nsym _ fs] @pending :let [ns-s (name nsym)] {:keys [e]} fs] (.put e->ns e ns-s))
              (doseq [dt (d/datoms db :aevt :seon.code.form/deps)
                      :let [e (:e dt) ns-s (.get e->ns e)]
                      :when (and ns-s (= ns-s (namespace (:v dt))))]
                (let [^java.util.ArrayList al (or (.get deps-hm e) (let [a (java.util.ArrayList. 4)] (.put deps-hm e a) a))]
                  (.add al (:v dt)))))
            (doseq [[_ _ raw-forms] @pending {:keys [e]} raw-forms]
              (let [ds (into #{} (map :v) (d/datoms db :eavt e :seon.code.form/deps))]
                (when (seq ds) (.put deps-hm e ds))))))
        (doseq [[nsym sig raw-forms] @pending]
          (let [res (vec (ns-used-before-defined db br nsym raw-forms deps-hm))]
            (doseq [x res] (conj! out x))
            (when (and has-deps? sig) (vswap! new-entries assoc [br nsym sig] res))))
        (when (seq @new-entries)
          (swap! ubd-ns-cache
                 (fn [c]
                   (let [c (if (satisfies? cache/CacheProtocol c) c (cache/lru-cache-factory {} :threshold 256))]
                     (reduce-kv cache/miss c @new-entries)))))))
    (when (and di/*reads* has-deps? (<= (count @pending) 25))
      (record-aevt-reads! [:seon.code.form/deps]))
    (persistent! out)))

(defn foreign-nss
  "Set of namespace symbols whose `:seon.code.ns/origin` is set and not `:seon`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] [:set :symbol]] :cost/ticks 5000000}
  [db]
  (if-not (and db (contains? (:schema db) :seon.code.ns/origin))
    #{}
    (into #{}
          (keep (fn [dt]
                  (when-not (= :seon (:v dt))
                    (:v (first (d/datoms db :eavt (:e dt) :seon.code.ns/name))))))
          (d/datoms db :aevt :seon.code.ns/origin))))

(defn- compute-form-meta-snapshot [db]
  (let [schema-map (:schema db)]
    (if-not (and db (contains? schema-map :seon.code.form/key) (contains? schema-map :seon.code.form/ns))
      {:src-forms-by-ns {} :all-nss #{} :has-hdr-nss #{} :form-order-sig [0 0] :form-hash-sig [0 0]}
      (let [eid->ns (java.util.HashMap. 1400)
            eid->key (java.util.HashMap. 1400)
            eid->ord (java.util.HashMap. 1400)
            eid->hash (java.util.HashMap. 1400)
            ord-cnt (volatile! 0) ord-mtx (volatile! 0)
            hash-cnt (volatile! 0) hash-mtx (volatile! 0)
            foreign (foreign-nss db)]
        (doseq [dt (d/datoms db :aevt :seon.code.form/ns)] (.put eid->ns (:e dt) (:v dt)))
        (doseq [dt (d/datoms db :aevt :seon.code.form/key)] (.put eid->key (:e dt) (:v dt)))
        (when (contains? schema-map :seon.code.form/order)
          (doseq [dt (d/datoms db :aevt :seon.code.form/order)]
            (.put eid->ord (:e dt) (:v dt))
            (vswap! ord-cnt inc)
            (let [t (long (:tx dt))] (when (> t @ord-mtx) (vreset! ord-mtx t)))))
        (when (contains? schema-map :seon.code.form/hash)
          (doseq [dt (d/datoms db :aevt :seon.code.form/hash)]
            (.put eid->hash (:e dt) (:v dt))
            (vswap! hash-cnt inc)
            (let [t (long (:tx dt))] (when (> t @hash-mtx) (vreset! hash-mtx t)))))
        (let [all-nss (transient #{}) has-hdr (transient #{})
              by-ns (java.util.HashMap. 160)
              it (.iterator (.entrySet eid->ns))]
          (loop []
            (when (.hasNext it)
              (let [entry ^java.util.Map$Entry (.next it)
                    e (.getKey entry) nsym (.getValue entry)]
                (when nsym
                  (conj! all-nss nsym)
                  (let [k (.get eid->key e)]
                    (when (and (string? k) (str/ends-with? k "#ns")) (conj! has-hdr nsym))
                    (when-not (or (str/ends-with? (name nsym) "-test") (contains? foreign nsym))
                      (let [arr (or (.get by-ns nsym) (let [a (java.util.ArrayList. 16)] (.put by-ns nsym a) a))]
                        (.add ^java.util.ArrayList arr
                              {:e e :key k :order (or (.get eid->ord e) 0) :hash (.get eid->hash e)})))))
                (recur))))
          (let [out (transient {}) it2 (.iterator (.entrySet by-ns))]
            (loop []
              (when (.hasNext it2)
                (let [entry ^java.util.Map$Entry (.next it2)]
                  (assoc! out (.getKey entry) (vec ^java.util.ArrayList (.getValue entry)))
                  (recur))))
            {:src-forms-by-ns (persistent! out) :all-nss (persistent! all-nss) :has-hdr-nss (persistent! has-hdr)
             :form-order-sig [@ord-cnt @ord-mtx] :form-hash-sig [@hash-cnt @hash-mtx]}))))))

(defn form-meta-snapshot
  "Scans `:seon.code.form/{ns,key,order,hash}` ONCE (cached by `diff/code-db-sig`)."
  {:malli/schema [:=> [:cat :seon.host/db] :map]}
  [db]
  (if-let [csig (diff/code-db-sig db)]
    (do (record-aevt-reads! [:seon.code.form/ns :seon.code.form/key :seon.code.form/order :seon.code.form/hash :seon.code.ns/origin])
        (store/lru-get! form-meta-snap-cache 16 csig #(binding [di/*reads* nil] (compute-form-meta-snapshot db))))
    (compute-form-meta-snapshot db)))

(defn missing-ns-forms
  "Flags namespaces in `:seon.code.form/ns` (or `ns-syms`) lacking a `<ns>#ns` header."
  {:malli/schema [:function [:=> [:cat :seon.host/db] [:vector :seon.health/problem]] [:=> [:cat :seon.host/db [:maybe [:sequential :symbol]]] [:vector :seon.health/problem]] [:=> [:cat :seon.host/db [:maybe [:sequential :symbol]] [:maybe :map]] [:vector :seon.health/problem]]]}
  ([db] (missing-ns-forms db nil nil))
  ([db ns-syms] (missing-ns-forms db ns-syms nil))
  ([db ns-syms form-snap]
   (if-not (and db (contains? (:schema db) :seon.code.form/key))
     []
     (let [br (get-in db [:config :branch] :main)
           missing
           (if ns-syms
             (->> ns-syms (remove nil?) distinct
                  (filter #(and (first (d/datoms db :avet :seon.code.form/ns %))
                                (not (first (d/datoms db :avet :seon.code.form/key (str % "#ns"))))))
                  sort)
             (let [snap (or form-snap (form-meta-snapshot db))]
               (sort (set/difference (:all-nss snap) (:has-hdr-nss snap)))))]
       (mapv (fn [nsym]
               {:class :missing-ns-form :severity :error :kind :static
                :ns nsym :subject (str nsym) :branch br
                :message (str "Namespace " nsym " has persisted forms but no (ns " nsym " ...) header form. "
                              "Evaluate (ns " nsym " (:require ...)) so the file compiles cleanly on cold AOT builds.")})
             missing)))))

(defn used-before-defined
  "Flags forms in `src/` (or `ns-syms`) referencing a same-namespace var before definition without `(declare ...)`."
  {:malli/schema [:function [:=> [:cat :seon.host/db] [:vector :seon.health/problem]] [:=> [:cat :seon.host/db [:maybe [:sequential :symbol]]] [:vector :seon.health/problem]] [:=> [:cat :seon.host/db [:maybe [:sequential :symbol]] [:maybe :map]] [:vector :seon.health/problem]]]}
  ([db] (used-before-defined db nil nil))
  ([db ns-syms] (used-before-defined db ns-syms nil))
  ([db ns-syms form-snap]
   (if-not (and db (contains? (:schema db) :seon.code.form/key))
     []
     (let [br (get-in db [:config :branch] :main)]
       (if ns-syms
         (let [by-ns (into {}
                           (for [nsym (distinct ns-syms)
                                 :when (and nsym (not (str/ends-with? (name nsym) "-test")))
                                 :let [fs (mapv (fn [dt]
                                                  (let [e (:e dt)
                                                        p (d/pull db '[:seon.code.form/key :seon.code.form/order :seon.code.form/hash] e)]
                                                    {:e e :key (:seon.code.form/key p) :order (or (:seon.code.form/order p) 0) :hash (:seon.code.form/hash p)}))
                                                (d/datoms db :avet :seon.code.form/ns nsym))]
                                 :when (seq fs)]
                             [nsym fs]))]
           (used-before-defined-all db br {:src-forms-by-ns by-ns}))
         (used-before-defined-all db br (or form-snap (form-meta-snapshot db))))))))
