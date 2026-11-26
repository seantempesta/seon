(ns seon.repl.search
  "Extracted child namespace of `seon.repl`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.fork.calls :as fcalls :refer [*agent-ctx*]]
            [seon.store :as store]
            [seon.ctx.diff :as diff]))

(def ^:private grep-src-cache (store/lru-cache 8))

(defn- cached-form-index [bdb]
  (let [ck (or (diff/code-db-sig bdb) [(get-in bdb [:config :store :id]) (get-in bdb [:config :branch] :main) (:max-tx bdb)])]
    (store/lru-get! grep-src-cache 8 ck
      (fn []
        (let [by-e (volatile! {}) out (volatile! [])]
          (doseq [dt (d/datoms bdb :aevt :seon.code.form/key)] (vswap! by-e assoc (:e dt) {:key (str (:v dt))}))
          (doseq [dt (d/datoms bdb :aevt :seon.code.form/ns)]
            (when (contains? @by-e (:e dt)) (vswap! by-e assoc-in [(:e dt) :ns] (name (:v dt)))))
          (doseq [dt (d/datoms bdb :aevt :seon.code.form/src)
                  :let [info (get @by-e (:e dt))] :when info
                  :let [src (str (:v dt)) fk (:key info)]]
            (vswap! out conj {:key fk :sym (when (and (string? fk) (not (str/blank? fk))) (symbol fk))
                              :ns (or (:ns info) (first (str/split fk #"/|#"))) :src src :low (str/lower-case src)}))
          @out)))))

(defn- grep-schema-hits [bdb ns-pref compiled multi? match-low? rem-lim]
  (if-not (and (pos? rem-lim) (contains? (:schema bdb) :seon.schema/key)
               (some #(str/includes? (str/lower-case (:raw %)) "seon.") compiled))
    []
    (let [by-e (volatile! {}) sm (:schema bdb) out (volatile! [])]
      (doseq [dt (d/datoms bdb :aevt :seon.schema/key)] (vswap! by-e assoc (:e dt) {:key (:v dt)}))
      (when (:seon.schema/form sm)
        (doseq [dt (d/datoms bdb :aevt :seon.schema/form)]
          (when (contains? @by-e (:e dt)) (vswap! by-e assoc-in [(:e dt) :form] (pr-str (:v dt))))))
      (when (:seon.schema/doc sm)
        (doseq [dt (d/datoms bdb :aevt :seon.schema/doc)]
          (when (contains? @by-e (:e dt)) (vswap! by-e assoc-in [(:e dt) :doc] (str (:v dt))))))
      (doseq [{:keys [key form doc]} (sort-by #(str (:key %)) (vals @by-e))
              :while (< (count @out) rem-lim)
              :let [kns (when (qualified-keyword? key) (namespace key))]
              :when (or (nil? ns-pref) (= kns ns-pref) (and kns (str/starts-with? kns (str ns-pref "."))))
              :let [txt (str key " " (or form "") (when (and doc (not (str/blank? doc))) (str " — " doc)))
                    hit-p (some #(when (match-low? % txt (str/lower-case txt)) (:raw %)) compiled)]
              :when hit-p]
        (vswap! out conj (cond-> {:key (str key) :schema key :ns "seon.schema" :line 1
                                  :text (if (> (count txt) 140) (str (subs txt 0 140) "…") txt)}
                           multi? (assoc :pattern hit-p))))
      @out)))

(defn- search-db-hits [db q]
  (let [sm (:schema db) vs (volatile! []) ss (volatile! []) ds (volatile! [])
        hit? #(str/includes? (str/lower-case (str %)) q)
        e-v (fn [e a] (:v (first (d/datoms db :eavt e a))))]
    (when (:seon.code.var/name sm) (doseq [dt (d/datoms db :aevt :seon.code.var/name)] (when (hit? (:v dt)) (vswap! vs conj (:v dt)))))
    (when (:seon.code.var/doc sm) (doseq [dt (d/datoms db :aevt :seon.code.var/doc)] (when (hit? (:v dt)) (when-let [v (e-v (:e dt) :seon.code.var/name)] (vswap! vs conj v)))))
    (when (:seon.schema/key sm) (doseq [dt (d/datoms db :aevt :seon.schema/key)] (when (hit? (:v dt)) (vswap! ss conj (:v dt)))))
    (when (:seon.schema/doc sm) (doseq [dt (d/datoms db :aevt :seon.schema/doc)] (when (hit? (:v dt)) (when-let [k (e-v (:e dt) :seon.schema/key)] (vswap! ss conj k)))))
    (when (:seon.schema/form sm) (doseq [dt (d/datoms db :aevt :seon.schema/form)] (when (hit? (pr-str (:v dt))) (when-let [k (e-v (:e dt) :seon.schema/key)] (vswap! ss conj k)))))
    (when (:seon.code.doc/id sm) (doseq [dt (d/datoms db :aevt :seon.code.doc/id)] (when (hit? (:v dt)) (vswap! ds conj (:v dt)))))
    {:vars @vs :schemas @ss :docs @ds}))

(defn ns-size-summary
  "O(N) zero-source summary of namespaces ranked by estimated token count (`{:ns :forms :vars :chars :tokens}`).
   Opts: `:prefix` (string/symbol), `:include-tests?` (default false), `:limit`/`:top` (default 30)."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db] [:vector :map]]
                  [:=> [:cat :seon.host/db [:maybe :map]] [:vector :map]]]
   :cost/ticks 25000000
   :seon.eval/budget-override {:ticks 200000000 :bytes 536870912}}
  ([db] (ns-size-summary db nil))
  ([db opts]
   (let [o (if (map? opts) opts {})
         sm (:schema db)
         pref (when-let [p (or (:prefix o) (:ns o))] (str/lower-case (if (symbol? p) (name p) (str p))))
         inc-tests? (boolean (:include-tests? o))
         lim (long (or (:limit o) (:top o) 30))]
     (if-not (and db (contains? sm :seon.code.form/ns))
       []
       (let [fe->ns (java.util.HashMap. 4096)
             forms-by-ns (volatile! {})
             chars-by-ns (volatile! {})
             vars-by-ns (volatile! {})]
         (doseq [dt (d/datoms db :aevt :seon.code.form/ns)]
           (let [nsym (:v dt)]
             (.put fe->ns (:e dt) nsym)
             (vswap! forms-by-ns update nsym (fnil inc 0))))
         (when (contains? sm :seon.code.form/chars)
           (doseq [dt (d/datoms db :aevt :seon.code.form/chars)]
             (when-let [nsym (.get fe->ns (:e dt))]
               (vswap! chars-by-ns update nsym (fnil + 0) (long (:v dt))))))
         (when (contains? sm :seon.code.var/ns)
           (doseq [dt (d/datoms db :aevt :seon.code.var/ns)]
             (vswap! vars-by-ns update (:v dt) (fnil inc 0))))
         (->> (keys @forms-by-ns)
              (filter (fn [nsym]
                        (let [ns-s (name nsym)]
                          (and (or inc-tests? (not (str/ends-with? ns-s "-test")))
                               (or (nil? pref) (str/includes? (str/lower-case ns-s) pref))))))
              (map (fn [nsym]
                     (let [ch (long (get @chars-by-ns nsym 0))]
                       {:ns nsym
                        :forms (long (get @forms-by-ns nsym 0))
                        :vars (long (get @vars-by-ns nsym 0))
                        :chars ch
                        :tokens (quot ch 4)})))
              (sort-by (fn [{:keys [tokens ns]}] [(- tokens) (str ns)]))
              (take (max 1 lim))
              vec))))))

(defonce ^:private ^:seon.flow/edge branch-code-delta-cache (store/lru-cache 32))

(defn- ^:seon.flow/edge branch-code-delta [conn bdb mdb branch]
  (let [bcid (d/commit-id bdb)
        mcid (d/commit-id mdb)
        st (:store bdb)
        sid (get-in bdb [:config :store :id])]
    (if (or (nil? bcid) (nil? mcid) (= bcid mcid)
            (not (get-in bdb [:config :commit-graph?] true)))
      {:main-ahead? false :dirty? false :nss #{}}
      (store/lru-get! branch-code-delta-cache 32 [sid branch bcid mcid]
        (fn []
          (let [base-cid (when (and st (d/commit-graph-record st mcid) (d/commit-graph-record st bcid))
                           (d/merge-base bdb mcid bcid))]
            (if (or (nil? base-cid) (= base-cid mcid))
              {:main-ahead? false :dirty? true :nss #{}}
              (let [changes (when (d/commit-graph-record st base-cid) (d/changes conn base-cid bcid))]
                (if (nil? changes)
                  {:main-ahead? true :dirty? true :nss #{}}
                  (let [code-eids (volatile! #{})
                        dirty? (volatile! false)]
                    (doseq [dt changes
                            :let [raw-a (:a dt) a (if (keyword? raw-a) raw-a (get-in bdb [:ref-ident-map raw-a] raw-a))]]
                      (when (or (= a :seon.code.form/hash)
                                (= a :seon.code.form/src)
                                (= a :seon.code.form/key)
                                (= a :seon.code.var/name)
                                (= a :seon.schema/form))
                        (vreset! dirty? true)
                        (when (or (= a :seon.code.form/hash) (= a :seon.code.form/src) (= a :seon.code.form/key))
                          (vswap! code-eids conj (:e dt)))))
                    (let [base-db-delay (delay (d/commit-as-db conn base-cid))
                          nss (if (empty? @code-eids)
                                #{}
                                (into #{}
                                      (keep (fn [e]
                                              (or (:v (first (d/datoms bdb :eavt e :seon.code.form/ns)))
                                                  (when-let [base-db @base-db-delay]
                                                    (:v (first (d/datoms base-db :eavt e :seon.code.form/ns)))))))
                                      @code-eids))]
                      {:main-ahead? true :dirty? @dirty? :nss nss})))))))))))

(defn ^:seon.flow/edge effective-code-db
  "Returns the freshest code-reading DB for `(conn, branch)` (optionally scoped to single-ns `target`).
   When `:main` advanced past `merge-base(main, branch)` and `branch` has no code edits (or did not edit `target`'s ns),
   returns `:main` so coordinators and clean branches immediately see merged code, vars, and tests."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/conn :keyword] :seon.host/db]
                  [:=> [:cat :seon.host/conn :keyword :any] :seon.host/db]]
   :cost/ticks 15000000}
  ([conn branch] (effective-code-db conn branch nil))
  ([conn branch target]
   (let [bdb @conn]
     (if (= branch :main)
       bdb
       (let [mdb (when (store/branch? conn :main) (store/head conn :main))]
         (if (or (nil? mdb) (= (d/commit-id mdb) (d/commit-id bdb)))
           bdb
           (let [{:keys [main-ahead? dirty? nss]} (branch-code-delta conn bdb mdb branch)]
             (if-not main-ahead?
               bdb
               (if-not dirty?
                 mdb
                 (let [tns (cond
                             (and (symbol? target) (namespace target)) (symbol (namespace target))
                             (symbol? target) target
                             (string? target) (let [s (symbol target)] (if (namespace s) (symbol (namespace s)) s))
                             :else nil)]
                   (if (and tns (seq nss) (not (contains? nss tns)))
                     mdb
                     bdb)))))))))))

(defn search
  "Searches vars (name + doc), schemas (key + doc + form), and docs by case-insensitive substring. Optional 2nd arg filters to a namespace symbol (or `{:ns 'seon.foo}`)."
  {:malli/schema [:function [:=> [:cat :any] [:maybe :map]] [:=> [:cat :any :any] [:maybe :map]]]}
  ([substr] (search substr nil))
  ([substr scope-or-opts]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [db (effective-code-db conn (or branch :main))
         q (str/lower-case (str substr))
         raw-ns (cond (map? scope-or-opts) (:ns scope-or-opts) (integer? scope-or-opts) nil :else scope-or-opts)
         ns-pref (when raw-ns (if (string? raw-ns) raw-ns (name raw-ns)))
         in-ns? (fn [x]
                  (if-not ns-pref
                    true
                    (let [xs (cond (qualified-symbol? x) (namespace x)
                                   (qualified-keyword? x) (namespace x)
                                   :else (str x))]
                      (and xs (or (= xs ns-pref) (str/starts-with? xs (str ns-pref ".")))))))
         h (search-db-hits db q)]
     {:vars (->> (:vars h) (filter in-ns?) distinct sort (take 25) vec)
      :schemas (->> (:schemas h) (filter in-ns?) distinct sort (take 20) vec)
      :docs (->> (:docs h) distinct sort (take 20) vec)}))))

(defn grep-src
  "Cached single- or multi-pattern grep across forms (falls back to `:seon.schema/*` when pattern mentions `seon.`). Accepts `{:ns 'ns :limit 30}` or a bare namespace symbol as 2nd arg."
  {:malli/schema [:function [:=> [:cat :any] [:maybe [:vector :map]]] [:=> [:cat :any :any] [:maybe [:vector :map]]]]}
  ([pattern] (grep-src pattern {}))
  ([pattern opts-or-ns]
   (let [{:keys [ns limit] :or {limit 30}} (cond (map? opts-or-ns) opts-or-ns (integer? opts-or-ns) {:limit (max 1 (long opts-or-ns))} :else {:ns opts-or-ns})]
     (when-let [{:keys [conn branch]} *agent-ctx*]
       (let [bdb (effective-code-db conn (or branch :main))
             ns-pref (when ns (if (string? ns) ns (name ns)))
             multi? (and (coll? pattern) (not (map? pattern)))
             compiled (mapv #(if (instance? java.util.regex.Pattern %) {:raw (str %) :re? true :re %} {:raw (str %) :re? false :low (str/lower-case (str %))})
                            (if multi? (vec pattern) [pattern]))
             mlow? (fn [{:keys [re? re low]} ^String s ^String ls] (if re? (boolean (re-find re s)) (str/includes? ls low)))
             mline? (fn [{:keys [re? re low]} ^String l] (if re? (boolean (re-find re l)) (str/includes? (str/lower-case l) low)))
             hits (volatile! [])]
         (doseq [{:keys [key sym src low] :as e} (cached-form-index bdb)
                 :while (< (count @hits) limit) :let [ens (:ns e)]
                 :when (or (nil? ns-pref) (= ens ns-pref) (str/starts-with? (str ens) (str ns-pref ".")))
                 :let [mp (filterv #(mlow? % src low) compiled)] :when (seq mp)]
           (doseq [[i line] (map-indexed vector (str/split-lines src))
                   :while (< (count @hits) limit)
                   :let [hp (some #(when (mline? % line) (:raw %)) mp)] :when hp
                   :let [t (str/trim line)]]
             (vswap! hits conj (cond-> {:key key :sym sym :ns ens :line (inc i)
                                        :text (if (> (count t) 120) (str (subs t 0 120) "…") t)}
                                 multi? (assoc :pattern hp)))))
         (into @hits (grep-schema-hits bdb ns-pref compiled multi? mlow? (- (long limit) (count @hits)))))))))

(defn ns-list
  "Lists namespaces ranked by token size (`{:ns :forms :vars :chars :tokens}`). Pass optional substring/prefix or `{:prefix ... :include-tests? true :top 30}`."
  {:malli/schema [:function [:=> [:cat] [:maybe [:vector :map]]] [:=> [:cat :any] [:maybe [:vector :map]]]]
   :seon.eval/budget-override {:ticks 200000000 :bytes 536870912}}
  ([] (ns-list {}))
  ([filter-or-opts]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [opts (cond (map? filter-or-opts) filter-or-opts
                      (or (string? filter-or-opts) (symbol? filter-or-opts)) {:prefix filter-or-opts :include-tests? true :limit 50}
                      :else {})]
       (ns-size-summary (effective-code-db conn (or branch :main)) opts)))))