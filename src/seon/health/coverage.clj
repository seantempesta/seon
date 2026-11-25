(ns seon.health.coverage
  "Rule & Renderer Exhaustive Coverage Checker + Rule Atlas over Datahike facts."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.ctx.diff :as diff]
            [seon.render.fit :as fit]
            [seon.rule :as rules]
            [seon.schema :as schema]
            [seon.store :as store :refer [db-schema]]))

(def known-runtime-wm-seeders
  "WM attributes seeded by runtime engines (`seon.ctx`, `seon.task.gate`, `seon.render.print.policy`)."
  #{:world/connected :world/tx :world/pinned :world/extra-ns
    :world/touched :world/touches :world/touched-tx :world/written?
    :world/energy :world/frontier :world/continuation :world/branch-caused-issue
    :turn/error-schema :turn/has-error? :turn/large-result?
    :gate/agent :gate/assigned-task :gate/needs :gate/task-done?
    :gate/requires-test :gate/var-exists? :gate/latest-pass?
    :gate/latest-digest :gate/latest-report :gate/blocked? :gate/blocker
    :policy/subject :ctx/total})

(def ^:private rank-keys #{:rank/boost :rank/suppress})

(defonce ^:private ^:seon.flow/edge coverage-report-cache (store/lru-cache 16))

(defn- lvar? [x] (and (symbol? x) (str/starts-with? (name x) "?")))

(defn- unwrap-sym [x] (if (and (seq? x) (= 'quote (first x)) (symbol? (second x))) (second x) x))

(defn- rval [g x] (cond (lvar? x) (get g x #{}) (some? x) #{x} :else #{}))

(defn- clip-doc [s n]
  (let [t (str/trim (str/replace (str (or s "")) #"\s+" " "))]
    (if (> (count t) n) (str (subs t 0 (dec n)) "…") t)))

(defn- entity-clauses [clauses ent neg?]
  (let [nv #(if (or (= '_ %) (lvar? %)) '_ %)]
    (reduce
     (fn [acc cl]
       (cond
         (and (vector? cl) (>= (count cl) 2))
         (let [db? (and (symbol? (first cl)) (str/starts-with? (name (first cl)) "$"))
               [s e a v] (if db? [(first cl) (second cl) (nth cl 2 nil) (nth cl 3 '_)]
                             ['$ (first cl) (second cl) (nth cl 2 '_)])]
           (if (and (= ent e) (keyword? a))
             (conj acc (if neg? [:not s a (nv v)] [s a (nv v)])) acc))
         (and (seq? cl) (#{'not 'not-join 'and 'or 'or-join} (first cl)))
         (let [h (first cl) sub (if (#{'not-join 'or-join} h) (drop 2 cl) (rest cl))]
           (set/union acc (entity-clauses sub ent (if (#{'not 'not-join} h) (not neg?) neg?))))
         :else acc))
     #{} clauses)))

(defn- proj-spec [ws grounds p]
  (let [pks #(if (sequential? %)
               (reduce (fn [s el] (cond (qualified-keyword? el) (conj s el)
                                        (map? el) (into s (filter qualified-keyword? (keys el))) :else s)) #{} %) #{})
        rv #(rval grounds (% p))
        params (reduce merge {} (filter map? (rv :params)))
        ent (:entity p)
        [pat wa] (cond
                   (and (vector? ent) (= 2 (count ent)) (qualified-keyword? (first ent)))
                   [#{[:lookup (first ent)]} #{(first ent)}]
                   (lvar? ent)
                   (let [cs (entity-clauses ws ent false)]
                     [cs (into #{} (map #(if (= :not (first %)) (nth % 2) (second %))) cs)])
                   :else [#{} #{}])]
    {:zones (->> (rv :zone) (filter keyword?) distinct sort vec)
     :priorities (->> (rv :priority) (filter integer?) distinct sort vec)
     :fns (->> (rv :fn) (keep #(let [u (unwrap-sym %)] (when (qualified-symbol? u) u))) distinct sort vec)
     :params params :ui-params (apply dissoc params rank-keys)
     :schemas (into #{} (filter qualified-keyword?) (rv :schema))
     :attrs (set/union (into #{} (mapcat pks) (rv :pull)) wa) :pattern pat}))

(defn- extract-where-fn-syms [where-clauses]
  (into #{}
        (keep (fn [node]
                (when (and (seq? node) (qualified-symbol? (first node)))
                  (first node))))
        (tree-seq coll? seq where-clauses)))

(defn- doc-id-literal [clause]
  (when (and (vector? clause) (>= (count clause) 4)
             (= :seon.code.doc/id (nth clause 2))
             (string? (nth clause 3)))
    (nth clause 3)))

(defn- extract-doc-ref-checks [where-clauses]
  (let [out (volatile! [])]
    (letfn [(walk [node]
              (cond
                (doc-id-literal node)
                (vswap! out conj {:kind :required :ids [(doc-id-literal node)]})

                (and (seq? node) (#{(quote or) (quote or-join)} (first node)))
                (let [branches (if (= (quote or-join) (first node)) (drop 2 node) (rest node))
                      branch-doc-ids (mapv (fn [b] (vec (keep doc-id-literal (tree-seq coll? seq b)))) branches)]
                  (if (every? seq branch-doc-ids)
                    (vswap! out conj {:kind :any-of :ids (vec (distinct (apply concat branch-doc-ids)))})
                    (doseq [b branches] (walk b))))

                (and (seq? node) (#{(quote and) (quote not) (quote not-join)} (first node)))
                (doseq [sub (if (= (quote not-join) (first node)) (drop 2 node) (rest node))]
                  (walk sub))

                :else nil))]
      (doseq [cl where-clauses] (walk cl)))
    @out))

(defn- analyze-rule [r]
  (let [pq (rules/parse-query (:seon.rule/query r))
        ws (or (:where pq) [])
        nodes (tree-seq coll? seq ws)
        ins (vec (or (seq (:in pq)) [(quote $)]))
        grounds (reduce
                 (fn [m n]
                   (if (and (vector? n) (= 2 (count n)) (seq? (first n))
                            (= (quote ground) (ffirst n)) (= 2 (count (first n))) (lvar? (second n)))
                     (update m (second n) (fnil conj #{}) (second (first n))) m))
                 {} nodes)
        wm-r (->> nodes
                  (reduce
                   (fn [a n]
                     (cond
                       (and (vector? n) (>= (count n) 3) (= (quote $wm) (first n)))
                       (conj a (if (keyword? (nth n 2)) (nth n 2) :*))
                       (and (seq? n) (some #(= (quote $wm) %) n))
                       (let [ks (filter keyword? n)] (if (seq ks) (into a ks) (conj a :*)))
                       :else a))
                   #{})
                  sort vec)
        asserts (or (:seon.rule/assert r) [])
        ps (mapv #(proj-spec ws grounds %) (filter map? asserts))]
    (with-meta
      {:id (:seon.rule/id r) :stratum (long (or (:seon.rule/stratum r) 50))
       :category (or (:seon.rule/category r) :present) :target (or (:seon.rule/target r) :any)
       :enabled? (if (contains? r :seon.rule/enabled?) (boolean (:seon.rule/enabled? r)) true)
       :zones (->> ps (mapcat :zones) distinct sort vec)
       :priorities (->> ps (mapcat :priorities) distinct sort vec)
       :wm-reads wm-r
       :wm-writes (->> asserts (filter #(and (vector? %) (= 3 (count %)) (keyword? (second %))))
                       (map second) distinct sort vec)
       :db-sources (->> ins (filter #(and (symbol? %) (str/starts-with? (name %) "$"))) distinct sort vec)
       :doc (clip-doc (:seon.rule/doc r) 64)}
      {:seon.coverage/fns (->> ps (mapcat :fns) distinct sort vec)
       :seon.coverage/projections ps
       :seon.coverage/in-syms ins
       :seon.coverage/doc-checks (extract-doc-ref-checks ws)
       :seon.coverage/where-fns (extract-where-fn-syms ws)})))

(defn rule-atlas
  "Reuses cached `seon.rule/load-rules` returning a deterministic, stratum-sorted vector of all `:seon.rule/*` entities."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] [:vector :map]] :cost/ticks 15000000}
  [db]
  (if-not (and db (contains? (db-schema db) :seon.rule/id))
    []
    (->> (rules/load-rules db)
         (map analyze-rule)
         (sort-by (juxt :stratum #(str (:id %))))
         vec)))

(defn- stored-entity-schemas [db]
  (let [dh (db-schema db)]
    (if-not (and db (dh :seon.schema/key) (dh :seon.schema/form))
      {}
      (let [stored (into #{} (map :v) (d/datoms db :aevt :seon.schema/key))
            ix (:entity-index (schema/registry-for-db db))
            forms (:forms ix)
            by-s (reduce-kv
                  (fn [acc a sks]
                    (reduce (fn [m sk] (if (contains? stored sk) (update m sk (fnil conj #{}) a) m)) acc sks))
                  {} (:entity-schemas ix))]
        (reduce (fn [acc sk]
                  (if (contains? acc sk)
                    acc
                    (let [f (get forms sk)]
                      (if (and (coll? f) (some #(and (map? %) (true? (:seon.db/attributes %))) (tree-seq coll? seq f)))
                        (assoc acc sk #{})
                        acc))))
                by-s stored)))))

(defn- ^:seon.flow/edge measure-renderer [rsym sk sample]
  (if-let [v (and (some? sample) (fit/resolve-renderer-var rsym))]
    (try
      (let [res (@v {sk sample})
            h (cond (and (map? res) (contains? res :seon.ui/hiccup)) (:seon.ui/hiccup res) (vector? res) res)]
        (if h
          (let [m (fit/measure-hiccup h)
                ow (when (and (map? res) (number? (:seon.ui/min-width res))) (long (:seon.ui/min-width res)))
                mw (long (or ow (:min-width m)))]
            {:measured? true :tokens (long (:tokens m)) :min-width mw
             :area (long (if ow (* mw (max 20 (* (max 1 (:lines m)) 20))) (:area m)))})
          {:measured? false :measure-error "No hiccup"}))
      (catch Throwable t {:measured? false :measure-error (or (.getMessage t) (str (class t)))}))
    {:measured? false}))

(defn- ^:seon.flow/edge inspect-renderer [db dh _ps sk sample rsym]
  (let [ve (when (dh :seon.code.var/name) (:e (first (d/datoms db :avet :seon.code.var/name rsym))))
        rd-ks (fn [a] (if (and ve (dh a))
                        (into #{} (keep #(:v (first (d/datoms db :eavt (:v %) :seon.schema/key)))) (d/datoms db :eavt ve a))
                        #{}))
        m (measure-renderer rsym sk sample)]
    (cond-> {:fn rsym :inputs (rd-ks :seon.code.var/input)
             :optional-inputs (rd-ks :seon.code.var/optional-input)
             :measured? (boolean (:measured? m))}
      (:measured? m) (assoc :tokens (:tokens m) :min-width (:min-width m) :area (:area m))
      (:measure-error m) (assoc :measure-error (:measure-error m)))))

(defn- ^:seon.flow/edge schema-renderer-matrix* [db stored-es]
  (let [dh (db-schema db)
        ps (cond-> [] (dh :seon.code.var/input) (conj {:seon.code.var/input [:seon.schema/key]})
                      (dh :seon.code.var/optional-input) (conj {:seon.code.var/optional-input [:seon.schema/key]}))
        all-r (fit/discover-all-renderers db)]
    (mapv (fn [sk]
            (let [rsyms (get all-r sk [])
                  sample (when (seq rsyms) (first (schema/sample-schema db sk 1)))
                  rs (mapv #(inspect-renderer db dh ps sk sample %) rsyms)]
              {:schema sk :renderer-count (count rs) :renderers rs}))
          (sort (keys stored-es)))))

(defn ^:seon.flow/edge schema-renderer-matrix
  "Maps stored entity schemas to pure `:seon.ui/hiccup` renderers via batched `fit/discover-all-renderers`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] [:vector :map]] :cost/ticks 40000000}
  [db]
  (schema-renderer-matrix* db (stored-entity-schemas db)))

(defn- footprints-overlap? [r1 r2]
  (let [w25? #(let [hi (max (double %1) (double %2)) lo (min (double %1) (double %2))]
                (or (zero? hi) (<= (/ (- hi lo) hi) 0.25)))
        m1? (and (number? (:min-width r1)) (number? (:tokens r1)))
        m2? (and (number? (:min-width r2)) (number? (:tokens r2)))]
    (cond (and (not m1?) (not m2?)) true
          (and m1? m2?) (and (w25? (:min-width r1) (:min-width r2)) (w25? (:tokens r1) (:tokens r2)))
          :else false)))

(defn- rule-projs [db r]
  (or (:seon.coverage/projections (meta r)) (seq (:projections r))
      (when (or (seq (:fns r)) (map? (:params r)) (seq (:schemas r)))
        [{:zones (vec (:zones r)) :priorities (vec (:priorities r))
          :fns (vec (keep #(let [u (unwrap-sym %)] (when (qualified-symbol? u) u)) (:fns r)))
          :params (or (:params r) {}) :ui-params (apply dissoc (or (:params r) {}) rank-keys)
          :schemas (into #{} (filter qualified-keyword?) (:schemas r)) :attrs #{} :pattern (or (:pattern r) #{})}])
      (when (and db (:id r) (contains? (db-schema db) :seon.rule/id))
        (let [raw (d/pull db [:seon.rule/id :seon.rule/query :seon.rule/assert] [:seon.rule/id (:id r)])]
          (when (:seon.rule/id raw) (:seon.coverage/projections (meta (analyze-rule raw))))))
      []))

(defn- schema-holes [sel-fns disambig {:keys [schema renderers]}]
  (let [rs (vec renderers) n (count rs)]
    (cond
      (zero? n) [{:class :missing-entity-renderer :severity :warning :schema schema :subject (str schema)
                  :message (str schema " has 0 pure renderers.")}]
      (= 1 n) [{:class :single-footprint-renderer :severity :info :schema schema :renderer (:fn (first rs))
                :subject (str schema) :message (str schema " has 1 renderer (" (:fn (first rs)) ").")}]
      :else
      (for [i (range n) j (range (inc i) n)
            :let [r1 (nth rs i) r2 (nth rs j) f1 (:fn r1) f2 (:fn r2)]
            :when (and (footprints-overlap? r1 r2) (= (set (:inputs r1)) (set (:inputs r2)))
                       (= (set (:optional-inputs r1)) (set (:optional-inputs r2)))
                       (not (sel-fns f1)) (not (sel-fns f2)) (not (disambig schema)))]
        {:class :ambiguous-renderer-pair :severity :warning :schema schema :renderers [f1 f2]
         :footprints [(select-keys r1 [:tokens :min-width :area]) (select-keys r2 [:tokens :min-width :area])]
         :subject (str schema ":" f1 "<->" f2) :message (str "Ambiguous: " f1 " vs " f2 " on " schema ".")}))))

(defn- detect-renderer-holes* [db matrix atlas stored-es]
  (let [projs (mapcat #(rule-projs db %) (filter #(not (false? (:enabled? %))) atlas))
        sel-fns (into #{} (mapcat :fns) projs)
        ui-projs (filter #(seq (:ui-params %)) projs)
        a->s (if (and db (seq ui-projs))
               (reduce-kv (fn [a sk ks] (reduce #(update %1 %2 (fnil conj #{}) sk) a ks)) {} (or stored-es (stored-entity-schemas db)))
               {})
        disambig (into #{} (mapcat #(set/union (:schemas %) (into #{} (mapcat a->s) (:attrs %)))) ui-projs)]
    (->> matrix (mapcat #(schema-holes sel-fns disambig %)) (sort-by (juxt #(name (:class %)) :subject)) vec)))

(defn detect-renderer-holes
  "Pure audit detecting `:missing-entity-renderer`, `:single-footprint-renderer`, and `:ambiguous-renderer-pair`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:vector :any] [:vector :any]] [:vector :map]] :cost/ticks 15000000}
  [db matrix atlas]
  (detect-renderer-holes* db matrix atlas nil))

(defn- claim-conflicts [cs]
  (let [v (vec cs) n (count v)]
    (for [i (range n) j (range (inc i) n)
          :let [c1 (nth v i) c2 (nth v j) t1 (:target c1) t2 (:target c2)]
          :when (and (not= (:rule c1) (:rule c2)) (not= (:fn c1) (:fn c2)) (or (= t1 t2) (= :any t1) (= :any t2)))]
      (let [[a b] (if (neg? (compare (str (:rule c1)) (str (:rule c2)))) [c1 c2] [c2 c1])]
        {:class :conflicting-rule-priority :severity :warning
         :rules [(:rule a) (:rule b)] :fns [(:fn a) (:fn b)] :stratum (:stratum a) :zone (:zone a)
         :subject (str (:rule a) "<->" (:rule b) "@" (:stratum a) "/" (name (:zone a)))
         :message (str (:rule a) " vs " (:rule b) " conflict in s" (:stratum a) " " (:zone a) ".")}))))

(defn- rule-fn-exists? [db fsym]
  (or (some? (first (d/datoms db :avet :seon.code.var/name fsym)))
      (boolean (when-let [ns-obj (find-ns (symbol (namespace fsym)))]
                 (ns-resolve ns-obj (symbol (name fsym)))))))

(def supported-query-inputs
  "Exact `:in` input symbols resolved by `seon.rule/resolve-query-inputs`."
  #{'$ '$main '$branch '$code '$task '$wm '?agent '?agent-eid '?focus '?focus-eid '?hierarchy '%})

(defn detect-rule-holes
  "Pure audit detecting `:orphan-wm-consumer`, `:dead-rule-fn`, `:unknown-rule-input`, `:missing-rule-doc-ref`, and `:conflicting-rule-priority`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:vector :any]] [:vector :map]] :cost/ticks 25000000}
  [db atlas]
  (let [dh (db-schema db)
        normalized (mapv #(if (and (map? %) (:seon.rule/id %)) (analyze-rule %) %) atlas)
        enabled (filterv #(not (false? (:enabled? %))) normalized)
        allowed (set/union known-runtime-wm-seeders (into #{} (mapcat :wm-writes) enabled))
        orphan (for [r enabled a (:wm-reads r) :when (and (keyword? a) (not= :* a) (not (allowed a)))]
                 {:class :orphan-wm-consumer :severity :warning :rule (:id r) :attr a
                  :subject (str (:id r) "<-" a) :message (str (:id r) " reads unproduced $wm " a ".")})
        has-vars? (boolean (and db (dh :seon.code.var/name) (first (d/datoms db :aevt :seon.code.var/name))))
        dead-proj (when has-vars?
                    (for [r enabled
                          f (or (:seon.coverage/fns (meta r)) (->> (rule-projs db r) (mapcat :fns) distinct sort))
                          :when (and (qualified-symbol? f) (not (rule-fn-exists? db f)))]
                      {:class :dead-rule-fn :severity :error :rule (:id r) :fn f
                       :subject (str (:id r) "->" f) :message (str (:id r) " asserts missing :fn " f ".")}))
        dead-where (when has-vars?
                     (for [r enabled
                           fsym (:seon.coverage/where-fns (meta r))
                           :when (not (rule-fn-exists? db fsym))]
                       {:class :dead-rule-fn :severity :error :rule (:id r) :fn fsym
                        :subject (str (:id r) "->" fsym) :message (str (:id r) " calls missing helper fn " fsym " in :where.")}))
        dead (vals (reduce (fn [acc h] (if (contains? acc (:subject h)) acc (assoc acc (:subject h) h)))
                           {} (concat dead-proj dead-where)))
        unk-in (for [r enabled
                     sym (:seon.coverage/in-syms (meta r))
                     :when (not (contains? supported-query-inputs sym))]
                 {:class :unknown-rule-input :severity :error :rule (:id r)
                  :subject (str (:id r) " :in " sym)
                  :message (str "Rule " (:id r) " binds unsupported :in symbol " sym " (not in seon.rule/resolve-query-inputs).")})
        doc-ids (when (and db (contains? dh :seon.code.doc/id))
                  (let [s (into #{} (map :v) (d/datoms db :aevt :seon.code.doc/id))]
                    (when (seq s) s)))
        miss-doc (when doc-ids
                   (mapcat (fn [r]
                             (mapcat (fn [{:keys [kind ids]}]
                                       (if (= kind :any-of)
                                         (when-not (some doc-ids ids)
                                           [{:class :missing-rule-doc-ref :severity :error :rule (:id r)
                                             :subject (str (:id r) "->" (first ids))
                                             :message (str "Rule " (:id r) " references none of existing :seon.code.doc/id targets " (pr-str ids) ".")}])
                                         (for [did ids :when (not (contains? doc-ids did))]
                                           {:class :missing-rule-doc-ref :severity :error :rule (:id r)
                                            :subject (str (:id r) "->" did)
                                            :message (str "Rule " (:id r) " references missing :seon.code.doc/id " (pr-str did) ".")})))
                                     (:seon.coverage/doc-checks (meta r))))
                           enabled))
        claims (for [r enabled p (rule-projs db r) :when (seq (:pattern p)) z (:zones p) f (:fns p)]
                 {:rule (:id r) :stratum (:stratum r) :target (or (:target r) :any) :zone z :fn f :pattern (:pattern p)})
        confs (distinct (mapcat claim-conflicts (vals (group-by (juxt :stratum :zone :pattern) claims))))]
    (->> (concat orphan dead unk-in miss-doc confs) (sort-by (juxt #(name (:class %)) :subject)) vec)))

(defn- ^:seon.flow/edge compute-coverage-report [db include-atlas?]
  (let [dh (db-schema db)
        raw-rules (if (and db (contains? dh :seon.rule/id)) (rules/load-rules db) [])
        atlas (->> raw-rules (map analyze-rule) (sort-by (juxt :stratum #(str (:id %)))) vec)
        stored-es (stored-entity-schemas db)
        matrix (schema-renderer-matrix* db stored-es)
        rb (group-by :class (detect-renderer-holes* db matrix atlas stored-es))
        missing (vec (:missing-entity-renderer rb))
        single (vec (:single-footprint-renderer rb))
        ambiguous (vec (:ambiguous-renderer-pair rb))
        rule-holes (detect-rule-holes db atlas)
        hb (group-by :class rule-holes)
        orphan (vec (:orphan-wm-consumer hb))
        dead (vec (:dead-rule-fn hb))
        confs (vec (:conflicting-rule-priority hb))
        unk-in (vec (:unknown-rule-input hb))
        miss-doc (vec (:missing-rule-doc-ref hb))
        rn (count (filter #(pos? (:renderer-count %)) matrix))
        counts {:entity-schemas (count matrix) :rendered-schemas rn :total-renderers (reduce + 0 (map :renderer-count matrix))
                :missing-renderers (count missing) :single-footprint (count single) :ambiguous-renderers (count ambiguous)
                :rules (count atlas) :enabled-rules (count (filter :enabled? atlas)) :rule-holes (count rule-holes)
                :orphan-wm-consumer (count orphan) :dead-rule-fn (count dead) :conflicting-rule-priority (count confs)
                :unknown-rule-input (count unk-in) :missing-rule-doc-ref (count miss-doc)}
        summary (str (count matrix) " entity schemas (" rn " rendered, " (count missing) " missing, "
                     (count single) " single-footprint, " (count ambiguous) " ambiguous) | "
                     (count atlas) " rules (" (count orphan) " orphan-wm, " (count dead) " dead-fn, "
                     (count unk-in) " unknown-in, " (count miss-doc) " missing-doc, " (count confs) " conflict)")
        base (array-map :ok? (and (empty? ambiguous) (empty? dead) (empty? orphan)
                                  (empty? unk-in) (empty? miss-doc))
                        :summary summary :counts counts :missing-renderers missing :ambiguous-renderers ambiguous
                        :single-footprint single :rule-holes rule-holes)]
    (with-meta (if include-atlas? (assoc base :rule-atlas atlas) base)
               {:seon/schema :seon.health/coverage})))

(defn ^:seon.flow/edge coverage-report
  "Exhaustive rule & renderer coverage report (`:seon.health/coverage`), cached by `[code-db-sig rules-db-sig]`."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db]] :map] [:=> [:cat [:maybe :seon.host/db] [:maybe :map]] :map]]
   :cost/ticks 60000000}
  ([db] (coverage-report db nil))
  ([db opts]
   (let [inc-a? (not (false? (:include-atlas? opts)))
         csig (diff/code-db-sig db)
         rsig (rules/rules-db-sig db)]
     (if (and csig rsig)
       (let [full (store/lru-get! coverage-report-cache 16 [csig rsig] #(compute-coverage-report db true))]
         (if inc-a? full (with-meta (dissoc full :rule-atlas) {:seon/schema :seon.health/coverage})))
       (compute-coverage-report db inc-a?)))))

(defn- ^:seon.flow/edge repl-agent-db [opts]
  (let [ctx-var (requiring-resolve (quote seon.fork.calls/*agent-ctx*))
        conn (:conn (when ctx-var @ctx-var))]
    (cond
      (and (map? opts) (:db opts)) (if (= :main (:db opts)) (when conn (store/head conn :main)) (:db opts))
      conn @conn
      :else nil)))

(defn ^:seon.flow/edge coverage
  "REPL helper: runs NASA-style rule & renderer coverage check (`{:include-atlas? false}` by default)."
  {:seon.repl/export true
   :malli/schema [:function [:=> [:cat] :map] [:=> [:cat [:maybe :map]] :map]]
   :cost/ticks 60000000}
  ([] (coverage nil))
  ([opts]
   (let [m (if (map? opts) opts {})
         db (repl-agent-db m)]
     (coverage-report db (merge {:include-atlas? false} (dissoc m :db))))))

(defn ^:seon.flow/edge repl-rule-atlas
  "REPL helper: returns deterministic stratum-sorted Rule Atlas of all `:seon.rule/*` entities."
  {:seon.repl/export (quote rule-atlas)
   :malli/schema [:function [:=> [:cat] [:vector :map]] [:=> [:cat [:maybe :map]] [:vector :map]]]
   :cost/ticks 20000000}
  ([] (repl-rule-atlas nil))
  ([opts]
   (rule-atlas (repl-agent-db (when (map? opts) opts)))))
