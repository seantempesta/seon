(ns seon.health.static
  "Static code-structure checkers for System Health computed from indexed `:seon.code.*` facts."
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

(def blocking-syms
  #{'clojure.core.async/<!!
    'clojure.core.async/>!!
    'clojure.core.async/alts!!
    'Thread/sleep})

(def top-level-state-syms
  #{'clojure.core/atom
    'clojure.core/volatile!
    'clojure.core/promise
    'clojure.core/agent})

(defn- record-aevt-reads! [attrs]
  (when di/*reads*
    (doseq [a attrs]
      (di/record-read! (dd/datom 0 a nil 0) (dd/datom Long/MAX_VALUE a nil Long/MAX_VALUE)))))

(defn schema-violations
  "Checks `changed-forms` against `ours-db` for `:retyped-attribute` and `:schema-breaks-stored-data`."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:vector :seon.health/problem]]}
  [ours-db changed-forms]
  (if (empty? changed-forms)
    []
    (let [reg-info (schema/registry-for-db ours-db changed-forms)
          {:keys [forms registry declarations]} reg-info
          changed-keys (set (keys changed-forms))
          br (get-in ours-db [:config :branch] :main)
          reached-keys
          (loop [frontier changed-keys seen changed-keys]
            (let [parents (into #{} (keep (fn [[k f]]
                                            (when (and (not (contains? seen k))
                                                       (some #(contains? frontier %) (schema/schema-refs-in-form f)))
                                              k)))
                                forms)]
              (if (empty? parents) seen (recur parents (into seen parents)))))
          decl-by-id (or (:decl-by-id reg-info) (into {} (map (juxt :db/ident identity)) declarations))
          installed (:schema ours-db)
          retyped
          (vec (keep (fn [k]
                       (when-let [o (get installed k)]
                         (when-let [n (get decl-by-id k)]
                           (when (or (not= (:db/valueType o) (:db/valueType n))
                                     (not= (:db/cardinality o) (:db/cardinality n)))
                             {:class :retyped-attribute :severity :error :kind :static
                              :ns (symbol (namespace k)) :subject (str k) :branch br
                              :message (str "Cannot retype installed Datahike attribute " k " from "
                                            [(:db/valueType o) (:db/cardinality o)] " to "
                                            [(:db/valueType n) (:db/cardinality n)] ". Migrate accretively.")}))))
                     reached-keys))
          refused
          (vec (mapcat
                (fn [attr]
                  (when (contains? installed attr)
                    (when-let [{:keys [schema valid?]} (schema/attr-validator-for reg-info attr)]
                      (->> (d/datoms ours-db :aevt attr)
                           (keep (fn [dt]
                                   (when-not (valid? (:v dt))
                                     {:class :schema-breaks-stored-data :severity :error :kind :static
                                      :ns (symbol (namespace attr)) :subject (str attr) :branch br
                                      :message (str "Schema change to " attr " rejects stored value "
                                                    (pr-str (:v dt)) " on entity " (:e dt) ": "
                                                    (pr-str (me/humanize (m/explain schema (:v dt) {:registry registry}))))})))
                           (take 10)))))
                reached-keys))]
      (into retyped refused))))

(defn unschemed-attributes
  "Flags installed `:seon.*/*` attributes lacking a registered `:seon.schema/key` (ignoring cleanly retired ones)."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :seon.health/problem]]}
  [db]
  (let [br (get-in db [:config :branch] :main)
        registered (into #{} (map :v) (d/datoms db :aevt :seon.schema/key))
        hdb (when (get-in db [:config :keep-history?]) (d/history db))
        cleanly-retired? (fn [attr]
                           (boolean
                            (and hdb
                                 (nil? (first (d/datoms db :aevt attr)))
                                 (some? (first (d/datoms hdb :avet :seon.schema/key attr))))))]
    (->> (keys (:schema db))
         (filter #(and (qualified-keyword? %)
                       (str/starts-with? (namespace %) "seon.")
                       (not (contains? registered %))
                       (not (cleanly-retired? %))))
         sort
         (mapv (fn [attr]
                 {:class :unschemed-attribute :severity :error :kind :static
                  :ns (symbol (namespace attr)) :subject (str attr) :branch br
                  :message (str "Datahike attribute " attr " has no registered Malli :seon.schema/key. "
                                "Register it via Malli schemas instead of installing raw Datahike attributes.")})))))

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
    (let [raw (get @declare-cache h ::miss)
          cached (if (and (map? raw) (contains? raw :seon.store/lru-val)) (:seon.store/lru-val raw) raw)]
      (if-not (identical? ::miss cached)
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

(def ^:private broad-catch-classes #{"*" "Throwable" "java.lang.Throwable" "Exception" "java.lang.Exception"})

(defonce ^:private ^:seon.flow/edge ubd-ns-cache (store/lru-cache 256))

(defonce ^:private ^:seon.flow/edge form-meta-snap-cache (store/lru-cache 16))

(defonce ^:private ^:seon.flow/edge var-snap-cache (store/lru-cache 16))

(defn- used-before-defined-all [db br snap]
  (let [by-ns (:src-forms-by-ns snap)
        has-deps? (contains? (:schema db) :seon.code.form/deps)
        pending (volatile! [])
        out (transient [])
        new-entries (volatile! {})]
    (doseq [[nsym raw-forms] (sort-by key by-ns)]
      (let [sig (when (every? :hash raw-forms) (mapv (juxt :key :order :hash) (sort-by :order raw-forms)))
            hit (when (and has-deps? sig) (get @ubd-ns-cache [br nsym sig] ::miss))]
        (if (and sig (not (identical? ::miss hit)))
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

(defn- compute-var-snapshot [db schemas? catches?]
  (let [schema-map (:schema db)]
    (if-not (and db (contains? schema-map :seon.code.var/name))
      {:vmap {} :all-var-syms #{} :eid->name (java.util.HashMap.) :eid->ns (java.util.HashMap.)
       :edge-vars #{} :var-schemas [] :var-catches [] :var-name-sig [0 0]}
      (let [eid->name (java.util.HashMap. 1200)
            eid->ns (java.util.HashMap. 1200)
            test-eids (java.util.HashSet. 256)
            foreign (foreign-nss db)
            edge-eids (java.util.HashSet. 64)
            all-syms (transient #{})
            cnt (volatile! 0) mtx (volatile! 0)]
        (doseq [dt (d/datoms db :aevt :seon.code.var/name)]
          (let [e (:e dt) v (:v dt) tx (long (:tx dt))]
            (.put eid->name e v)
            (conj! all-syms v)
            (vswap! cnt inc)
            (when (> tx @mtx) (vreset! mtx tx))))
        (when (contains? schema-map :seon.code.var/ns)
          (doseq [dt (d/datoms db :aevt :seon.code.var/ns)] (.put eid->ns (:e dt) (:v dt))))
        (when (contains? schema-map :seon.code.var/test?)
          (doseq [dt (d/datoms db :aevt :seon.code.var/test?)] (when (:v dt) (.add test-eids (:e dt)))))
        (when (contains? schema-map :seon.code.var/flow-edge?)
          (doseq [dt (d/datoms db :aevt :seon.code.var/flow-edge?)] (when (:v dt) (.add edge-eids (:e dt)))))
        (let [vmap (transient {}) edge-vars (transient #{})
              it (.iterator (.entrySet eid->name))]
          (loop []
            (when (.hasNext it)
              (let [entry ^java.util.Map$Entry (.next it)
                    e (.getKey entry) sym (.getValue entry) n (.get eid->ns e)
                    edge? (.contains edge-eids e)]
                (when edge? (conj! edge-vars sym))
                (when (and n (not (.contains test-eids e)) (not (str/ends-with? (name n) "-test")) (not (contains? foreign n)))
                  (assoc! vmap sym {:db/id e :seon.code.var/name sym :seon.code.var/ns n :seon.code.var/flow-edge? edge?}))
                (recur))))
          (let [var-schemas
                (if (and schemas? (contains? schema-map :seon.code.var/schema))
                  (let [out (transient [])]
                    (doseq [dt (d/datoms db :aevt :seon.code.var/schema)]
                      (let [e (:e dt)]
                        (conj! out [e (.get eid->name e) (.get eid->ns e) (.contains edge-eids e) (:v dt)])))
                    (persistent! out))
                  [])
                var-catches
                (if (and catches? (contains? schema-map :seon.code.var/catches)
                         (first (d/datoms db :aevt :seon.code.var/catches)))
                  (let [out (transient [])]
                    (doseq [dt (d/datoms db :aevt :seon.code.var/catches)]
                      (let [e (:e dt) n (.get eid->ns e)
                            test? (or (.contains test-eids e)
                                      (boolean (when n (or (str/ends-with? (name n) "-test") (contains? foreign n)))))]
                        (conj! out [e (.get eid->name e) n test? (.contains edge-eids e) (:v dt)])))
                    (persistent! out))
                  [])]
            {:vmap (persistent! vmap) :all-var-syms (persistent! all-syms)
             :eid->name eid->name :eid->ns eid->ns :edge-vars (persistent! edge-vars)
             :var-schemas var-schemas :var-catches var-catches :var-name-sig [@cnt @mtx]}))))))

(defn var-snapshot
  "Lean var snapshot (zero `:refs`/`:arglists` full scans, cached by `diff/code-db-sig`)."
  {:malli/schema [:function [:=> [:cat :seon.host/db] :map] [:=> [:cat :seon.host/db [:maybe :map]] :map]]}
  ([db] (var-snapshot db nil))
  ([db {:keys [schemas? catches?]}]
   (let [s? (boolean schemas?) c? (boolean catches?)]
     (if-let [csig (diff/code-db-sig db)]
       (do (record-aevt-reads! (cond-> [:seon.code.var/name :seon.code.var/ns :seon.code.var/test? :seon.code.var/flow-edge? :seon.code.ns/origin]
                                 s? (conj :seon.code.var/schema)
                                 c? (conj :seon.code.var/catches)))
           (store/lru-get! var-snap-cache 16 [csig s? c?] #(binding [di/*reads* nil] (compute-var-snapshot db s? c?))))
       (compute-var-snapshot db s? c?)))))

(defn src-var-map
  "Builds `{var-sym {:db/id :seon.code.var/name :seon.code.var/ns :seon.code.var/flow-edge?}}` for non-test vars."
  {:malli/schema [:=> [:cat :seon.host/db] :map]}
  [db]
  (:vmap (var-snapshot db nil)))

(defn- synthetic-blocking-in-flow-procs [br vmap proc-vars]
  (let [hits (transient [])]
    (doseq [[proc-sym _] proc-vars]
      (let [queue (java.util.ArrayDeque.) visited (java.util.HashSet.)]
        (.add queue [proc-sym [proc-sym]])
        (.add visited proc-sym)
        (loop []
          (when-not (.isEmpty queue)
            (let [[cur path] (.removeFirst queue)
                  cur-v (get vmap cur)
                  refs (:seon.code.var/refs cur-v #{})]
              (when-not (:seon.code.var/flow-edge? cur-v)
                (let [direct-blocks (filter blocking-syms refs)]
                  (when (seq direct-blocks)
                    (conj! hits
                           {:class :blocking-in-flow-proc :severity :error :kind :static
                            :ns (symbol (namespace proc-sym)) :subject (str proc-sym) :var cur :branch br
                            :message (str "Flow proc " proc-sym " reaches blocking op " (str/join ", " (sort direct-blocks))
                                          " via " (str/join " -> " path) ". Use Flow out-ports or mark an edge var ^:seon.flow/edge.")}))
                  (doseq [n (sort (filter #(and (contains? vmap %) (not (.contains visited %))) refs))]
                    (.add visited n)
                    (.addLast queue [n (conj path n)]))))
              (recur))))))
    (persistent! hits)))

(defn blocking-in-flow-procs
  "Flags Flow procs (`*-proc` in `src/`) reaching blocking ops without `^:seon.flow/edge` (indexed reverse BFS)."
  {:malli/schema [:function [:=> [:cat :seon.host/db] [:vector :seon.health/problem]] [:=> [:cat :seon.host/db :map] [:vector :seon.health/problem]]]}
  ([db] (blocking-in-flow-procs db (src-var-map db)))
  ([db vmap]
   (let [br (get-in db [:config :branch] :main)
         proc-vars (sort-by key (filter (fn [[sym v]] (and (str/ends-with? (name sym) "-proc") (not (:seon.code.var/flow-edge? v)))) vmap))]
     (cond
       (empty? proc-vars) []
       (every? #(contains? % :seon.code.var/refs) (vals vmap)) (synthetic-blocking-in-flow-procs br vmap proc-vars)
       (not (contains? (:schema db) :seon.code.var/refs)) []
       :else
       (let [proc-set (into #{} (map key) proc-vars)
             eid->sym (java.util.HashMap. 64)
             resolve-sym (fn [e]
                           (let [hit (.get eid->sym e)]
                             (if (some? hit)
                               (when-not (identical? hit ::nil) hit)
                               (let [s (:v (first (d/datoms db :eavt e :seon.code.var/name)))]
                                 (.put eid->sym e (if (some? s) s ::nil))
                                 s))))
             direct (reduce (fn [acc bsym]
                              (reduce (fn [m dt]
                                        (let [csym (resolve-sym (:e dt))
                                              cv (when csym (get vmap csym))]
                                          (if (and cv (not (:seon.code.var/flow-edge? cv)))
                                            (update m csym (fnil conj #{}) bsym)
                                            m)))
                                      acc (d/datoms db :avet :seon.code.var/refs bsym)))
                            {} (sort blocking-syms))
             hits (transient [])]
         (doseq [[leaf bset] (sort-by key direct)]
           (let [queue (java.util.ArrayDeque.) visited (java.util.HashSet.)]
             (.add queue [leaf [leaf]])
             (.add visited leaf)
             (loop []
               (when-not (.isEmpty queue)
                 (let [[cur rev-path] (.removeFirst queue)]
                   (when (contains? proc-set cur)
                     (conj! hits
                            {:class :blocking-in-flow-proc :severity :error :kind :static
                             :ns (symbol (namespace cur)) :subject (str cur) :var leaf :branch br
                             :message (str "Flow proc " cur " reaches blocking op " (str/join ", " (sort bset))
                                           " via " (str/join " -> " (rseq rev-path))
                                           ". Use Flow out-ports or mark an edge var ^:seon.flow/edge.")}))
                   (doseq [dt (d/datoms db :avet :seon.code.var/refs cur)]
                     (when-let [caller (resolve-sym (:e dt))]
                       (let [cv (get vmap caller)]
                         (when (and cv (not (:seon.code.var/flow-edge? cv)) (not (.contains visited caller)))
                           (.add visited caller)
                           (.addLast queue [caller (conj rev-path caller)])))))
                   (recur))))))
         (vec (sort-by (juxt :subject #(str (:var %))) (persistent! hits))))))))

(defn state-outside-flow
  "Flags top-level `def`/`defonce` vars in `src/` holding mutable state without `^:seon.flow/edge` (indexed `:avet`)."
  {:malli/schema [:function [:=> [:cat :seon.host/db] [:vector :seon.health/problem]] [:=> [:cat :seon.host/db :map] [:vector :seon.health/problem]]]}
  ([db] (state-outside-flow db (src-var-map db)))
  ([db vmap]
   (let [br (get-in db [:config :branch] :main)
         mk-prob (fn [sym v bad]
                   {:class :state-outside-flow :severity :warning :kind :static
                    :ns (:seon.code.var/ns v) :subject (str sym) :var sym :branch br
                    :message (str "Top-level mutable state (" (str/join ", " (sort bad))
                                  ") outside Flow in " sym ". Move into Flow proc state or mark ^:seon.flow/edge.")})]
     (cond
       (empty? vmap) []
       (every? #(contains? % :seon.code.var/refs) (vals vmap))
       (->> vmap
            (keep (fn [[sym v]]
                    (when (and (empty? (:seon.code.var/arglists v)) (not (:seon.code.var/flow-edge? v)))
                      (let [bad (filter top-level-state-syms (:seon.code.var/refs v #{}))]
                        (when (seq bad) (mk-prob sym v bad))))))
            (sort-by :subject) vec)
       (not (contains? (:schema db) :seon.code.var/refs)) []
       :else
       (let [has-args? (contains? (:schema db) :seon.code.var/arglists)
             by-eid (reduce (fn [acc st-sym]
                              (reduce (fn [m dt] (update m (:e dt) (fnil conj #{}) st-sym))
                                      acc (d/datoms db :avet :seon.code.var/refs st-sym)))
                            {} top-level-state-syms)]
         (->> by-eid
              (keep (fn [[e bad]]
                      (when-let [sym (:v (first (d/datoms db :eavt e :seon.code.var/name)))]
                        (when-let [v (get vmap sym)]
                          (when-not (:seon.code.var/flow-edge? v)
                            (let [args (if (contains? v :seon.code.var/arglists)
                                         (:seon.code.var/arglists v)
                                         (when has-args? (:v (first (d/datoms db :eavt e :seon.code.var/arglists)))))]
                              (when (empty? args) (mk-prob sym v bad))))))))
              (sort-by :subject) vec))))))

(defn silent-catch-warnings
  "Flags non-`^:seon.flow/edge` `src/` vars with broad catch-all clauses answering `:constant`."
  {:malli/schema [:function [:=> [:cat :seon.host/db] [:vector :seon.health/problem]] [:=> [:cat :seon.host/db [:maybe :symbol]] [:vector :seon.health/problem]] [:=> [:cat :seon.host/db [:maybe :symbol] [:maybe :map]] [:vector :seon.health/problem]]]}
  ([db] (silent-catch-warnings db nil nil))
  ([db scope] (silent-catch-warnings db scope nil))
  ([db scope var-snap]
   (let [catches-rows
         (cond
           var-snap (:var-catches var-snap)
           (and db (contains? (:schema db) :seon.code.var/catches) (first (d/datoms db :aevt :seon.code.var/catches)))
           (:var-catches (var-snapshot db {:schemas? false :catches? true}))
           :else nil)]
     (if (empty? catches-rows)
       []
       (let [br (get-in db [:config :branch] :main)
             scope-s (when scope (name scope))]
         (->> catches-rows
              (keep (fn [[_ vsym nsym test? edge? clauses]]
                      (when (and vsym nsym (not test?) (not edge?)
                                 (or (nil? scope-s) (= (name nsym) scope-s) (str/starts-with? (name nsym) (str scope-s "."))))
                        (when-let [hit (some #(when (and (= :constant (:answers %)) (contains? broad-catch-classes (:class %))) %) clauses)]
                          {:class :silent-catch-all :severity :warning :kind :static
                           :ns nsym :var vsym :subject (str vsym) :branch br
                           :message (str "Silent catch-all in " vsym " (catch " (:class hit)
                                         " -> :constant). Surface typed error data or mark ^:seon.flow/edge.")}))))
              (sort-by :subject) vec))))))
