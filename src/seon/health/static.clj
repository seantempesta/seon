(ns seon.health.static
  "Static code-structure checkers for System Health computed from indexed `:seon.code.*` facts."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [datahike.index.interface :as di]
            [malli.core :as m]
            [malli.error :as me]
            [seon.ctx.diff :as diff]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.health.static.forms :as sforms]))

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

(defn- record-aevt-reads! [& args] (apply sforms/record-aevt-reads! args))

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

(def ^:private broad-catch-classes #{"*" "Throwable" "java.lang.Throwable" "Exception" "java.lang.Exception"})

(defonce ^:private ^:seon.flow/edge var-snap-cache (store/lru-cache 16))

(defn ^{:seon.code/reexport 'seon.health.static.forms/foreign-nss} foreign-nss [& args] (apply sforms/foreign-nss args))

(defn ^{:seon.code/reexport 'seon.health.static.forms/form-meta-snapshot} form-meta-snapshot [& args] (apply sforms/form-meta-snapshot args))

(defn ^{:seon.code/reexport 'seon.health.static.forms/missing-ns-forms} missing-ns-forms [& args] (apply sforms/missing-ns-forms args))

(defn ^{:seon.code/reexport 'seon.health.static.forms/used-before-defined} used-before-defined [& args] (apply sforms/used-before-defined args))

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