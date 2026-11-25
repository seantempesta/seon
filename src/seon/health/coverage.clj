(ns seon.health.coverage
  "NASA Rule & Renderer Coverage Checker, Schema Fate Matrix, Generality Lint,
   Empirical Trace-Miss Detector, and Rule Atlas over Datahike facts."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.ctx.diff :as diff]
            [seon.render.fit :as fit]
            [seon.rule :as rules]
            [seon.schema :as schema]
            [seon.schema.index :as six]
            [seon.store :as store :refer [db-schema]]))

(def known-runtime-wm-seeders
  "WM attributes seeded by runtime engines (`seon.ctx`, `seon.task.gate`, `seon.render.print.policy`)."
  #{:world/connected :world/tx :world/pinned :world/extra-ns :world/touched :world/touches :world/touched-tx
    :world/written? :world/energy :world/frontier :world/continuation :world/branch-caused-issue
    :turn/error-schema :turn/has-error? :turn/large-result? :gate/agent :gate/assigned-task :gate/needs
    :gate/task-done? :gate/requires-test :gate/var-exists? :gate/latest-pass? :gate/latest-digest
    :gate/latest-report :gate/blocked? :gate/blocker :policy/subject :ctx/total})

(def supported-query-inputs
  "`:in` symbols resolved by `seon.rule/resolve-query-inputs`."
  #{'$ '$main '$branch '$code '$task '$wm '?agent '?agent-eid '?focus '?focus-eid '?hierarchy '%})

(def ^:private rank-keys #{:rank/boost :rank/suppress})
(defonce ^:private ^:seon.flow/edge coverage-report-cache (store/lru-cache 16))

(def ^:private attr-lit-issue
  (merge (zipmap [:seon.agent/id :seon.agent/parent :seon.agent.eval/agent :seon.agent.call/caller
                  :seon.agent.call/callee :seon.agent.message/from :seon.agent.message/to
                  :seon.error/agent :seon.task/owner :seon.task/created-by] (repeat :hardcoded-agent-id))
         {:seon.task/id :hardcoded-task-id :seon.task/needs :hardcoded-task-id}
         (zipmap [:seon.code.var/name :seon.code.var/refs :seon.code.form/key :seon.test/var :seon.error/var :seon.fix/regression-test] (repeat :hardcoded-var-literal))
         (zipmap [:seon.code.ns/name :seon.code.var/ns :seon.code.form/ns :seon.agent/scope :seon.task/scope] (repeat :hardcoded-ns-literal))))

(def ^:private bad-kw-nss #{"agent" "worker" "repl" "review" "tune" "task" "subtask" "ns"})

(defn- eav1 [db e a] (when (and db e ((db-schema db) a)) (:v (first (d/datoms db :eavt e a)))))
(defn- avet1 [db a v] (when (and db ((db-schema db) a)) (:e (first (d/datoms db :avet a v)))))
(defn- lvar? [x] (and (symbol? x) (str/starts-with? (name x) "?")))
(defn- unwrap-sym [x] (if (and (seq? x) (= 'quote (first x)) (symbol? (second x))) (second x) x))
(defn- rval [g x] (cond (lvar? x) (get g x #{}) (some? x) #{x} :else #{}))
(defn- lit? [x] (and (some? x) (not= '_ x) (not= :seon.rule/nil x) (not (lvar? x))))

(defn- clip-doc [s n]
  (let [t (str/trim (str/replace (str (or s "")) #"\s+" " "))]
    (if (> (count t) n) (str (subs t 0 (dec n)) "…") t)))

(defn- clause-quad [cl]
  (when (and (vector? cl) (>= (count cl) 2))
    (let [db? (and (symbol? (first cl)) (str/starts-with? (name (first cl)) "$"))
          e (if db? (second cl) (first cl))
          a (if db? (nth cl 2 nil) (second cl))]
      (when (and (>= (count cl) (if db? 3 2))
                 (keyword? a)
                 (or (lvar? e) (integer? e) (and (vector? e) (= 2 (count e)) (qualified-keyword? (first e)))))
        [(if db? (first cl) '$) e a (if db? (nth cl 3 '_) (nth cl 2 '_))]))))

(defn- entity-clauses [clauses ent neg?]
  (let [nv #(if (or (= '_ %) (lvar? %)) '_ %)]
    (reduce
     (fn [acc cl]
       (cond
         (clause-quad cl)
         (let [[s e a v] (clause-quad cl)]
           (if (and (= ent e) (keyword? a)) (conj acc (if neg? [:not s a (nv v)] [s a (nv v)])) acc))
         (and (seq? cl) (#{'not 'not-join 'and 'or 'or-join} (first cl)))
         (let [h (first cl) sub (if (#{'not-join 'or-join} h) (drop 2 cl) (rest cl))]
           (set/union acc (entity-clauses sub ent (if (#{'not 'not-join} h) (not neg?) neg?))))
         :else acc))
     #{} clauses)))

(defn- proj-spec [ws grounds p]
  (let [pks #(if (sequential? %) (reduce (fn [s el] (cond (qualified-keyword? el) (conj s el) (map? el) (into s (filter qualified-keyword? (keys el))) :else s)) #{} %) #{})
        rv #(rval grounds (% p)) params (reduce merge {} (filter map? (rv :params))) ent (:entity p)
        [pat wa rva]
        (cond
          (and (vector? ent) (= 2 (count ent)) (qualified-keyword? (first ent))) [#{[:lookup (first ent)]} #{(first ent)} #{}]
          (lvar? ent)
          (let [cs (entity-clauses ws ent false)
                ra (into #{} (keep #(when-let [[_ _ a v] (clause-quad %)] (when (and (= ent v) (qualified-keyword? a)) a))) (tree-seq coll? seq ws))]
            [cs (into #{} (map #(if (= :not (first %)) (nth % 2) (second %))) cs) ra])
          :else [#{} #{} #{}])]
    {:zones (->> (rv :zone) (filter keyword?) distinct sort vec)
     :priorities (->> (rv :priority) (filter integer?) distinct sort vec)
     :fns (->> (rv :fn) (keep #(let [u (unwrap-sym %)] (when (qualified-symbol? u) u))) distinct sort vec)
     :params params :ui-params (apply dissoc params rank-keys)
     :schemas (into #{} (filter qualified-keyword?) (rv :schema))
     :attrs (set/union (into #{} (mapcat pks) (rv :pull)) wa) :ref-val-attrs rva :pattern pat}))

(defn- doc-id-lit [cl]
  (when (and (vector? cl) (>= (count cl) 4) (= :seon.code.doc/id (nth cl 2)) (string? (nth cl 3))) (nth cl 3)))

(defn- extract-doc-ref-checks [ws]
  (let [out (volatile! [])]
    (letfn [(walk [n]
              (cond
                (doc-id-lit n) (vswap! out conj {:kind :required :ids [(doc-id-lit n)]})
                (and (seq? n) (#{'or 'or-join} (first n)))
                (let [bs (if (= 'or-join (first n)) (drop 2 n) (rest n))
                      bids (mapv #(vec (keep doc-id-lit (tree-seq coll? seq %))) bs)]
                  (if (every? seq bids)
                    (vswap! out conj {:kind :any-of :ids (vec (distinct (apply concat bids)))})
                    (doseq [b bs] (walk b))))
                (and (seq? n) (#{'and 'not 'not-join} (first n)))
                (doseq [s (if (= 'not-join (first n)) (drop 2 n) (rest n))] (walk s))))]
      (doseq [cl ws] (walk cl)))
    @out))

(defn- mk-issue [rid iss lit]
  {:rule-id rid :issue iss :literal lit :message (str "Rule " rid " hardcodes " (name iss) " " (pr-str lit) " in :where.")})

(defn- ref-lit-issue [rid v]
  (when (and (vector? v) (= 2 (count v)) (not= [:seon.system/id :root] v) (lit? (second v)))
    (when-let [iss (get attr-lit-issue (first v))] (mk-issue rid iss v))))

(defn- lint-one-rule [r]
  (let [rid (:seon.rule/id r) ws (or (:where (rules/parse-query (:seon.rule/query r))) [])
        nodes (tree-seq coll? seq ws)
        fn-lv (into #{} (keep #(when (and (map? %) (lvar? (:fn %))) (:fn %))) (:seon.rule/assert r))
        doms (reduce (fn [m n] (if-let [[_ _ a v] (clause-quad n)] (if-let [iss (and (lvar? v) (attr-lit-issue a))] (assoc m v iss) m) m))
                     {'?agent :hardcoded-agent-id} nodes)
        out (volatile! []) add! #(when % (vswap! out conj %))]
    (doseq [n nodes]
      (cond
        (and (vector? n) (keyword? (nth (or (clause-quad n) []) 2 nil)))
        (let [[_ e a v] (clause-quad n)]
          (add! (ref-lit-issue rid e))
          (when-let [iss (and (lit? v) (attr-lit-issue a))]
            (let [u (unwrap-sym v)] (when (or (keyword? u) (symbol? u) (string? u)) (add! (mk-issue rid iss u))))))
        (and (vector? n) (seq? (first n)) (seq (first n)))
        (let [[op & args] (first n) ov (second n)]
          (cond
            (#{'= '== 'not= 'contains?} op)
            (let [dom (some doms args) foc? (some #(= '?focus %) args)]
              (doseq [a args :when (and (not (lvar? a)) (not= '?focus a)) :let [u (unwrap-sym a)]]
                (cond (and foc? (vector? u)) (add! (ref-lit-issue rid u))
                      (and dom (set? u)) (doseq [el u :when (and (lit? el) (not= :main el))] (add! (mk-issue rid dom (unwrap-sym el))))
                      (and dom (lit? u) (not= :main u) (not (boolean? u)) (not (number? u))) (add! (mk-issue rid dom u)))))
            (= 'ground op)
            (when-not (fn-lv ov)
              (let [u (unwrap-sym (first args))]
                (cond (vector? u) (add! (ref-lit-issue rid u))
                      (qualified-symbol? u) (add! (mk-issue rid :hardcoded-var-literal u))
                      (and (qualified-keyword? u) (bad-kw-nss (namespace u)))
                      (add! (mk-issue rid (if (#{"task" "subtask"} (namespace u)) :hardcoded-task-id :hardcoded-agent-id) u)))))
            :else
            (doseq [a args :when (and (seq? a) (= 'quote (first a)) (symbol? (second a))) :let [s (second a)]]
              (add! (mk-issue rid (if (namespace s) :hardcoded-var-literal :hardcoded-ns-literal) s)))))))
    (vec (distinct @out))))

(defn lint-rule-generality
  "Pure generality lint checking `:context` rules for hardcoded agent, task, var, or ns literals in `:where`."
  {:malli/schema [:=> [:cat [:sequential :map]] [:vector :map]] :cost/ticks 5000000}
  [rules]
  (->> rules (filter #(or (= :context (:seon.rule/category %)) (some map? (:seon.rule/assert %))))
       (mapcat lint-one-rule) (sort-by (juxt #(str (:rule-id %)) #(name (:issue %)) #(pr-str (:literal %)))) vec))

(defn- analyze-rule [r]
  (let [pq (rules/parse-query (:seon.rule/query r)) ws (or (:where pq) []) nodes (tree-seq coll? seq ws)
        ins (vec (or (seq (:in pq)) ['$]))
        grounds (reduce (fn [m n] (if (and (vector? n) (= 2 (count n)) (seq? (first n)) (= 'ground (ffirst n))
                                           (= 2 (count (first n))) (lvar? (second n)))
                                    (update m (second n) (fnil conj #{}) (second (first n))) m)) {} nodes)
        wm-r (->> nodes (reduce (fn [a n]
                                  (cond (and (vector? n) (>= (count n) 3) (= '$wm (first n))) (conj a (if (keyword? (nth n 2)) (nth n 2) :*))
                                        (and (seq? n) (some #(= '$wm %) n)) (let [ks (filter keyword? n)] (if (seq ks) (into a ks) (conj a :*)))
                                        :else a)) #{}) sort vec)
        asserts (or (:seon.rule/assert r) []) ps (mapv #(proj-spec ws grounds %) (filter map? asserts))]
    (with-meta
      {:id (:seon.rule/id r) :stratum (long (or (:seon.rule/stratum r) 50))
       :category (or (:seon.rule/category r) :present) :target (or (:seon.rule/target r) :any)
       :enabled? (if (contains? r :seon.rule/enabled?) (boolean (:seon.rule/enabled? r)) true)
       :zones (->> ps (mapcat :zones) distinct sort vec) :priorities (->> ps (mapcat :priorities) distinct sort vec)
       :wm-reads wm-r :wm-writes (->> asserts (filter #(and (vector? %) (= 3 (count %)) (keyword? (second %)))) (map second) distinct sort vec)
       :db-sources (->> ins (filter #(and (symbol? %) (str/starts-with? (name %) "$"))) distinct sort vec)
       :generality-issues (if (or (= :context (:seon.rule/category r)) (seq ps)) (lint-one-rule r) [])
       :doc (clip-doc (:seon.rule/doc r) 64)}
      {:seon.coverage/fns (->> ps (mapcat :fns) distinct sort vec) :seon.coverage/projections ps
       :seon.coverage/in-syms ins :seon.coverage/doc-checks (extract-doc-ref-checks ws)
       :seon.coverage/where-fns (into #{} (keep #(when (and (seq? %) (qualified-symbol? (first %))) (first %))) nodes)})))

(defn rule-atlas
  "Stratum-sorted vector of all `:seon.rule/*` entities via cached `seon.rule/load-rules`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] [:vector :map]] :cost/ticks 15000000}
  [db]
  (if-not (and db ((db-schema db) :seon.rule/id)) []
      (->> (rules/load-rules db) (map analyze-rule) (sort-by (juxt :stratum #(str (:id %)))) vec)))

(defn- stored-entity-schemas [db]
  (let [dh (db-schema db)]
    (if-not (and db (dh :seon.schema/key) (dh :seon.schema/form)) {}
        (let [stored (into #{} (map :v) (d/datoms db :aevt :seon.schema/key))
              ix (:entity-index (schema/registry-for-db db)) forms (:forms ix)
              by-s (reduce-kv (fn [acc a sks] (reduce #(if (stored %2) (update %1 %2 (fnil conj #{}) a) %1) acc sks)) {} (:entity-schemas ix))]
          (reduce (fn [acc sk]
                    (if (contains? acc sk) acc
                        (let [f (get forms sk)]
                          (if (and (coll? f) (some #(and (map? %) (true? (:seon.db/attributes %))) (tree-seq coll? seq f)))
                            (assoc acc sk #{}) acc))))
                  by-s stored)))))

(defn- ^:seon.flow/edge measure-renderer [rsym sk sample]
  (if-let [v (and (some? sample) (fit/resolve-renderer-var rsym))]
    (try
      (let [res (@v {sk sample}) h (cond (and (map? res) (contains? res :seon.ui/hiccup)) (:seon.ui/hiccup res) (vector? res) res)]
        (if h
          (let [m (fit/measure-hiccup h) ow (when (and (map? res) (number? (:seon.ui/min-width res))) (long (:seon.ui/min-width res)))
                mw (long (or ow (:min-width m))) eh (long (max 20 (* (max 1 (:lines m)) 20)))]
            {:measured? true :tokens (long (:tokens m)) :min-width mw :height eh :area (long (if ow (* mw eh) (:area m)))})
          {:measured? false :measure-error "No hiccup"}))
      (catch Throwable t {:measured? false :measure-error (or (.getMessage t) (str (class t)))}))
    {:measured? false}))

(defn- ^:seon.flow/edge schema-renderer-matrix* [db stored-es]
  (let [all-r (fit/discover-all-renderers db)
        rd-ks (fn [ve a] (if (and ve ((db-schema db) a)) (into #{} (keep #(eav1 db (:v %) :seon.schema/key)) (d/datoms db :eavt ve a)) #{}))]
    (mapv (fn [sk]
            (let [rsyms (get all-r sk []) sample (when (seq rsyms) (first (schema/sample-schema db sk 1)))
                  rs (mapv (fn [rsym]
                             (let [ve (avet1 db :seon.code.var/name rsym) m (measure-renderer rsym sk sample)]
                               (cond-> {:fn rsym :inputs (rd-ks ve :seon.code.var/input) :optional-inputs (rd-ks ve :seon.code.var/optional-input) :measured? (boolean (:measured? m))}
                                 (:measured? m) (assoc :tokens (:tokens m) :min-width (:min-width m) :height (:height m) :area (:area m))
                                 (:measure-error m) (assoc :measure-error (:measure-error m))))) rsyms)]
              {:schema sk :renderer-count (count rs) :renderers rs}))
          (sort (keys stored-es)))))

(defn ^:seon.flow/edge schema-renderer-matrix
  "Maps stored entity schemas to pure `:seon.ui/hiccup` renderers."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] [:vector :map]] :cost/ticks 40000000}
  [db]
  (schema-renderer-matrix* db (stored-entity-schemas db)))

(defn- apply-row-boxes [fh-map row]
  (let [rs (->> (:renderers row)
                (map (fn [r]
                       (let [bb (fit/cached-browser-box (:var r) (get fh-map (:var r)) 360)
                             w (long (if bb (or (:w bb) (:w r)) (:w r))) h (long (if bb (or (:h bb) (:h r)) (:h r)))]
                         (with-meta (assoc r :measured-by (if bb :browser (:measured-by r)) :w w :h h)
                                    {:area (long (if bb (or (:area bb) (* w h)) (* w h)))}))))
                (sort-by (fn [r] [(:tokens r) (:area (meta r) 0) (str (:var r))]))
                (mapv #(with-meta % nil)))
        skip? (contains? #{:never :internal} (:policy row))
        tight? (or skip? (boolean (some #(and (pos? (:w %)) (<= (:tokens %) 60) (<= (:w %) 360)) rs)))]
    (assoc row :renderers rs :fits-tight? tight?
           :status (cond skip? :policy-never (empty? rs) :missing-renderer (not tight?) :no-tight-renderer (empty? (:covering-rules row)) :unassigned-rule :else :complete))))

(defn- ^:seon.flow/edge build-schema-fate-matrix [db stored-es matrix atlas]
  (let [ix (:entity-index (schema/registry-for-db db))
        a->s (reduce-kv (fn [a sk ks] (reduce #(update %1 %2 (fnil conj #{}) sk) a ks)) {} stored-es)
        fn->s (reduce (fn [m {:keys [schema renderers]}] (reduce #(update %1 (:fn %2) (fnil conj #{}) schema) m renderers)) {} matrix)
        fh-map (into {} (for [rsym (keys fn->s)] [rsym (eav1 db (avet1 db :seon.code.form/key (str rsym)) :seon.code.form/hash)]))
        en (filterv #(not (false? (:enabled? %))) atlas)
        exp-fn (reduce (fn [m r] (reduce #(update %1 %2 (fnil conj #{}) (:id r)) m (:seon.coverage/fns (meta r)))) {} en)
        cov-sk (reduce (fn [m r]
                         (if (or (= :context (:category r)) (seq (:seon.coverage/projections (meta r))))
                           (let [sks (reduce #(set/union %1 (:schemas %2) (into #{} (mapcat a->s) (:attrs %2))
                                                         (into #{} (mapcat fn->s) (:fns %2))
                                                         (into #{} (mapcat (:targets ix)) (:ref-val-attrs %2)))
                                             #{} (:seon.coverage/projections (meta r)))]
                             (reduce #(if (contains? stored-es %2) (update %1 %2 (fnil conj #{}) (:id r)) %1) m sks))
                           m)) {} en)]
    (with-meta
      (mapv (fn [{:keys [schema renderers]}]
              (let [pol (or (eav1 db (avet1 db :seon.schema/key schema) :seon.schema/context-policy)
                            (six/schema-property ix schema :seon.schema/context-policy) :default)
                    rs (mapv (fn [r]
                               (let [w (long (or (:min-width r) 0))
                                     h (long (or (:height r) (if (pos? w) (max 20 (quot (long (or (:area r) 0)) w)) 0)))]
                                 {:var (:fn r) :tokens (long (or (:tokens r) 0)) :measured-by :static
                                  :w w :h h :explicit-rules (vec (sort (get exp-fn (:fn r) #{})))}))
                             renderers)]
                (apply-row-boxes fh-map {:schema schema :policy pol :renderers rs
                                         :covering-rules (vec (sort (get cov-sk schema #{})))})))
            matrix)
      {:seon.coverage/fh-map fh-map})))

(defn- footprints-overlap? [r1 r2]
  (let [w25? #(let [hi (max (double %1) (double %2)) lo (min (double %1) (double %2))] (or (zero? hi) (<= (/ (- hi lo) hi) 0.25)))
        m1? (and (number? (:min-width r1)) (number? (:tokens r1))) m2? (and (number? (:min-width r2)) (number? (:tokens r2)))]
    (cond (and (not m1?) (not m2?)) true (and m1? m2?) (and (w25? (:min-width r1) (:min-width r2)) (w25? (:tokens r1) (:tokens r2))) :else false)))

(defn- rule-projs [db r]
  (or (:seon.coverage/projections (meta r)) (seq (:projections r))
      (when (or (seq (:fns r)) (map? (:params r)) (seq (:schemas r)))
        [{:zones (vec (:zones r)) :priorities (vec (:priorities r))
          :fns (vec (keep #(let [u (unwrap-sym %)] (when (qualified-symbol? u) u)) (:fns r)))
          :params (or (:params r) {}) :ui-params (apply dissoc (or (:params r) {}) rank-keys)
          :schemas (into #{} (filter qualified-keyword?) (:schemas r)) :attrs #{} :pattern (or (:pattern r) #{})}])
      (when (and db (:id r) ((db-schema db) :seon.rule/id))
        (let [raw (d/pull db [:seon.rule/id :seon.rule/query :seon.rule/assert] [:seon.rule/id (:id r)])]
          (when (:seon.rule/id raw) (:seon.coverage/projections (meta (analyze-rule raw))))))
      []))

(defn- detect-renderer-holes* [db matrix atlas stored-es]
  (let [projs (mapcat #(rule-projs db %) (filter #(not (false? (:enabled? %))) atlas))
        sel-fns (into #{} (mapcat :fns) projs) ui-projs (filter #(seq (:ui-params %)) projs)
        a->s (if (and db (seq ui-projs)) (reduce-kv (fn [a sk ks] (reduce #(update %1 %2 (fnil conj #{}) sk) a ks)) {} (or stored-es (stored-entity-schemas db))) {})
        disambig (into #{} (mapcat #(set/union (:schemas %) (into #{} (mapcat a->s) (:attrs %)))) ui-projs)]
    (->> matrix
         (mapcat (fn [{:keys [schema renderers]}]
                   (let [rs (vec renderers) n (count rs)]
                     (cond
                       (zero? n) [{:class :missing-entity-renderer :severity :warning :schema schema :subject (str schema) :message (str schema " has 0 renderers.")}]
                       (= 1 n) [{:class :single-footprint-renderer :severity :info :schema schema :renderer (:fn (first rs)) :subject (str schema) :message (str schema " has 1 renderer.")}]
                       :else (for [i (range n) j (range (inc i) n) :let [r1 (nth rs i) r2 (nth rs j) f1 (:fn r1) f2 (:fn r2)]
                                   :when (and (footprints-overlap? r1 r2) (= (set (:inputs r1)) (set (:inputs r2)))
                                              (= (set (:optional-inputs r1)) (set (:optional-inputs r2)))
                                              (not (sel-fns f1)) (not (sel-fns f2)) (not (disambig schema)))]
                               {:class :ambiguous-renderer-pair :severity :warning :schema schema :renderers [f1 f2]
                                :footprints [(select-keys r1 [:tokens :min-width :area]) (select-keys r2 [:tokens :min-width :area])]
                                :subject (str schema ":" f1 "<->" f2) :message (str "Ambiguous: " f1 " vs " f2 ".")})))))
         (sort-by (juxt #(name (:class %)) :subject)) vec)))

(defn detect-renderer-holes
  "Audits `:missing-entity-renderer`, `:single-footprint-renderer`, and `:ambiguous-renderer-pair`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:vector :any] [:vector :any]] [:vector :map]] :cost/ticks 15000000}
  [db matrix atlas]
  (detect-renderer-holes* db matrix atlas nil))

(defn- rule-fn-exists? [db fsym]
  (or (some? (avet1 db :seon.code.var/name fsym))
      (boolean (when-let [o (find-ns (symbol (namespace fsym)))] (ns-resolve o (symbol (name fsym)))))))

(defn detect-rule-holes
  "Audits `:orphan-wm-consumer`, `:dead-rule-fn`, `:unknown-rule-input`, `:missing-rule-doc-ref`, and `:conflicting-rule-priority`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:vector :any]] [:vector :map]] :cost/ticks 25000000}
  [db atlas]
  (let [dh (db-schema db)
        en (filterv #(not (false? (:enabled? %))) (mapv #(if (and (map? %) (:seon.rule/id %)) (analyze-rule %) %) atlas))
        allowed (set/union known-runtime-wm-seeders (into #{} (mapcat :wm-writes) en))
        hole #(merge {:class %1 :severity %2 :subject %3 :message %4} %5)
        orphan (for [r en a (:wm-reads r) :when (and (keyword? a) (not= :* a) (not (allowed a)))]
                 (hole :orphan-wm-consumer :warning (str (:id r) "<-" a) (str (:id r) " reads unproduced $wm " a ".") {:rule (:id r) :attr a}))
        has-v? (boolean (and db (dh :seon.code.var/name) (first (d/datoms db :aevt :seon.code.var/name))))
        dead (when has-v?
               (vals (reduce #(if (contains? %1 (:subject %2)) %1 (assoc %1 (:subject %2) %2)) {}
                             (for [r en f (concat (or (:seon.coverage/fns (meta r)) (->> (rule-projs db r) (mapcat :fns) distinct))
                                                  (:seon.coverage/where-fns (meta r)))
                                   :when (and (qualified-symbol? f) (not (rule-fn-exists? db f)))]
                               (hole :dead-rule-fn :error (str (:id r) "->" f) (str (:id r) " references missing fn " f ".") {:rule (:id r) :fn f})))))
        unk-in (for [r en s (:seon.coverage/in-syms (meta r)) :when (not (supported-query-inputs s))]
                 (hole :unknown-rule-input :error (str (:id r) " :in " s) (str (:id r) " binds unsupported :in " s ".") {:rule (:id r)}))
        dids (when (and db (dh :seon.code.doc/id)) (not-empty (into #{} (map :v) (d/datoms db :aevt :seon.code.doc/id))))
        miss-doc (when dids (for [r en {:keys [kind ids]} (:seon.coverage/doc-checks (meta r))
                                  did (if (= kind :any-of) (when-not (some dids ids) [(first ids)]) (remove dids ids))]
                              (hole :missing-rule-doc-ref :error (str (:id r) "->" did) (str (:id r) " missing doc " (pr-str did) ".") {:rule (:id r)})))
        claims (for [r en p (rule-projs db r) :when (seq (:pattern p)) z (:zones p) f (:fns p)]
                 {:rule (:id r) :stratum (:stratum r) :target (or (:target r) :any) :zone z :fn f :pattern (:pattern p)})
        confs (distinct
               (for [grp (vals (group-by (juxt :stratum :zone :pattern) claims))
                     :let [v (vec grp) n (count v)]
                     i (range n) j (range (inc i) n) :let [c1 (nth v i) c2 (nth v j) t1 (:target c1) t2 (:target c2)]
                     :when (and (not= (:rule c1) (:rule c2)) (not= (:fn c1) (:fn c2)) (or (= t1 t2) (= :any t1) (= :any t2)))
                     :let [[a b] (if (neg? (compare (str (:rule c1)) (str (:rule c2)))) [c1 c2] [c2 c1])]]
                 (hole :conflicting-rule-priority :warning (str (:rule a) "<->" (:rule b) "@" (:stratum a) "/" (name (:zone a)))
                       (str (:rule a) " vs " (:rule b) " conflict in s" (:stratum a) " " (:zone a) ".")
                       {:rules [(:rule a) (:rule b)] :fns [(:fn a) (:fn b)] :stratum (:stratum a) :zone (:zone a)})))]
    (->> (concat orphan dead unk-in miss-doc confs) (sort-by (juxt #(name (:class %)) :subject)) vec)))

(defn ^:seon.flow/edge detect-trace-misses
  "Scans bounded evals for manual lookup calls on unprojected 1-2 hop neighbors, merged with open `:seon.ctx/miss`."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db]] [:vector :map]] [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db]] [:vector :map]]] :cost/ticks 40000000}
  ([db] (detect-trace-misses db db))
  ([main-db branch-db] ((requiring-resolve 'seon.health.corpus/detect-trace-misses) main-db branch-db)))

(defn- ^:seon.flow/edge compute-coverage-report [db include-atlas?]
  (let [atlas (rule-atlas db) stored-es (stored-entity-schemas db)
        matrix (schema-renderer-matrix* db stored-es) s-matrix (build-schema-fate-matrix db stored-es matrix atlas)
        rb (group-by :class (detect-renderer-holes* db matrix atlas stored-es))
        missing (vec (:missing-entity-renderer rb)) single (vec (:single-footprint-renderer rb)) ambiguous (vec (:ambiguous-renderer-pair rb))
        rule-holes (detect-rule-holes db atlas) hb (group-by :class rule-holes)
        gen-iss (vec (mapcat :generality-issues atlas)) emp-misses (detect-trace-misses db)
        unassigned (count (filter #(= :unassigned-rule (:status %)) s-matrix))
        rn (count (filter #(pos? (:renderer-count %)) matrix)) hc #(count (get hb %))
        counts {:entity-schemas (count matrix) :rendered-schemas rn :total-renderers (reduce + 0 (map :renderer-count matrix))
                :missing-renderers (count missing) :single-footprint (count single) :ambiguous-renderers (count ambiguous)
                :rules (count atlas) :enabled-rules (count (filter :enabled? atlas)) :rule-holes (count rule-holes)
                :orphan-wm-consumer (hc :orphan-wm-consumer) :dead-rule-fn (hc :dead-rule-fn)
                :conflicting-rule-priority (hc :conflicting-rule-priority) :unknown-rule-input (hc :unknown-rule-input)
                :missing-rule-doc-ref (hc :missing-rule-doc-ref) :unassigned-schemas unassigned
                :generality-issues (count gen-iss) :empirical-misses (count emp-misses)}
        summary (str (count matrix) " schemas (" rn " rendered, " (count missing) " missing, " (count single) " single, "
                     (count ambiguous) " ambiguous, " unassigned " unassigned) | " (count atlas) " rules ("
                     (:orphan-wm-consumer counts) " orphan-wm, " (:dead-rule-fn counts) " dead-fn, "
                     (:unknown-rule-input counts) " unknown-in, " (:missing-rule-doc-ref counts) " missing-doc, "
                     (:conflicting-rule-priority counts) " conflict, " (count gen-iss) " lint)")
        base (array-map :ok? (and (empty? ambiguous) (zero? (:dead-rule-fn counts)) (zero? (:orphan-wm-consumer counts))
                                  (zero? (:unknown-rule-input counts)) (zero? (:missing-rule-doc-ref counts)))
                        :summary summary :counts counts :schema-matrix s-matrix :empirical-misses emp-misses
                        :missing-renderers missing :ambiguous-renderers ambiguous :single-footprint single :rule-holes rule-holes)]
    (with-meta (if include-atlas? (assoc base :rule-atlas atlas) base) {:seon/schema :seon.health/coverage})))

(defn- attach-corpus [rep db]
  (let [cr ((requiring-resolve 'seon.health.corpus/corpus-report) db)
        abl-by (into {} (map (juxt :rule-id identity)) (:ablation cr))]
    (with-meta
      (cond-> (assoc rep :corpus (:corpus cr) :ablation (:ablation cr) :counts (merge (:counts rep) (:counts cr)))
        (contains? rep :rule-atlas)
        (update :rule-atlas (fn [xs] (mapv #(if-let [a (get abl-by (:id %))]
                                              (assoc % :ablation-needed? (:ablation-needed? a) :contributing-situations (:contributing-situations a)) %) xs))))
      {:seon/schema :seon.health/coverage})))

(defn- ^:seon.flow/edge refresh-browser-boxes [rep]
  (let [sm (:schema-matrix rep) fm (:seon.coverage/fh-map (meta sm))
        stale? #(when-let [b (fit/cached-browser-box (:var %) (fm (:var %)) 360)]
                  (or (not= :browser (:measured-by %)) (not= (long (or (:w b) (:w %))) (:w %)) (not= (long (or (:h b) (:h %))) (:h %))))]
    (if (and (seq sm) (seq fm) (some #(some stale? (:renderers %)) sm))
      (with-meta (assoc rep :schema-matrix (with-meta (mapv #(apply-row-boxes fm %) sm) {:seon.coverage/fh-map fm})) {:seon/schema :seon.health/coverage})
      rep)))

(defn ^:seon.flow/edge coverage-report
  "NASA coverage report (`:seon.health/coverage`, <16ms warm); `{:include-corpus? true}` adds 12-scenario corpus."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db]] :map] [:=> [:cat [:maybe :seon.host/db] [:maybe :map]] :map]] :cost/ticks 60000000}
  ([db] (coverage-report db nil))
  ([db opts]
   (let [inc-a? (not (false? (:include-atlas? opts))) inc-c? (boolean (:include-corpus? opts))
         use-c? (not (false? (:use-cache? opts))) csig (when use-c? (diff/code-db-sig db)) rsig (when use-c? (rules/rules-db-sig db))
         mc 0
         base (if (and csig rsig)
                (let [full (refresh-browser-boxes (store/lru-get! coverage-report-cache 16 [(store/store-id db) csig rsig mc] #(compute-coverage-report db true)))]
                  (if inc-a? full (with-meta (dissoc full :rule-atlas) {:seon/schema :seon.health/coverage})))
                (compute-coverage-report db inc-a?))]
     (if inc-c? (attach-corpus base db) base))))

(defn- ^:seon.flow/edge repl-agent-db [opts]
  (let [cv (requiring-resolve 'seon.fork.calls/*agent-ctx*) c (:conn (when cv @cv))]
    (cond (and (map? opts) (:db opts)) (if (= :main (:db opts)) (when c (store/head c :main)) (:db opts)) c @c :else nil)))

(defn ^:seon.flow/edge coverage
  "REPL helper: NASA rule & renderer coverage check (`{:include-atlas? false}` default)."
  {:seon.repl/export true :malli/schema [:function [:=> [:cat] :map] [:=> [:cat [:maybe :map]] :map]] :cost/ticks 60000000}
  ([] (coverage nil))
  ([opts] (let [m (if (map? opts) opts {})] (coverage-report (repl-agent-db m) (merge {:include-atlas? false} (dissoc m :db))))))

(defn ^:seon.flow/edge repl-rule-atlas
  "REPL helper: stratum-sorted Rule Atlas of all `:seon.rule/*` entities."
  {:seon.repl/export 'rule-atlas :malli/schema [:function [:=> [:cat] [:vector :map]] [:=> [:cat [:maybe :map]] [:vector :map]]] :cost/ticks 20000000}
  ([] (repl-rule-atlas nil))
  ([opts] (rule-atlas (repl-agent-db (when (map? opts) opts)))))
