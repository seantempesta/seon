(ns seon.health.coverage.rules
  "Declarative rule generality linter and static rule-atlas analyzer extracted from `seon.health.coverage`."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.ctx.diff :as diff]
            [seon.render.fit :as fit]
            [seon.rule :as rules]
            [seon.schema :as schema]
            [seon.schema.index :as six]
            [seon.store :as store :refer [db-schema]]
            [seon.rule.query :as rq]
            [seon.render.text :refer [one-line]]))

(def rank-keys #{:rank/boost :rank/suppress})

(def ^:private attr-lit-issue
  (merge (zipmap [:seon.agent/id :seon.agent/parent :seon.agent.eval/agent :seon.agent.call/caller
                  :seon.agent.call/callee :seon.agent.message/from :seon.agent.message/to
                  :seon.error/agent :seon.task/owner :seon.task/created-by] (repeat :hardcoded-agent-id))
         {:seon.task/id :hardcoded-task-id :seon.task/needs :hardcoded-task-id}
         (zipmap [:seon.code.var/name :seon.code.var/refs :seon.code.form/key :seon.test/var :seon.error/var :seon.fix/regression-test] (repeat :hardcoded-var-literal))
         (zipmap [:seon.code.ns/name :seon.code.var/ns :seon.code.form/ns :seon.agent/scope :seon.task/scope] (repeat :hardcoded-ns-literal))))

(def ^:private bad-kw-nss #{"agent" "worker" "repl" "review" "tune" "task" "subtask" "ns"})

(defn- lvar? [x] (rq/lvar? x))

(defn unwrap-sym
  {:doc "Unwraps `(quote sym)` to `sym`, returning other values unchanged.", :malli/schema [:=> {:examples (quote [[[(quote foo)] foo] [[42] 42]])} [:cat :any] :any], :cost/ticks 50000}
  [x] (if (and (seq? x) (= 'quote (first x)) (symbol? (second x))) (second x) x))

(defn- rval [g x] (cond (lvar? x) (get g x #{}) (some? x) #{x} :else #{}))

(defn- lit? [x] (and (some? x) (not= '_ x) (not= :seon.rule/nil x) (not (lvar? x))))

(defn- clip-doc [s n] (one-line s n))

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

(defn analyze-rule
  {:doc "Analyzes a single `:seon.rule/*` entity into static metadata, projections, and generality issues.", :malli/schema [:=> [:cat :map] :map], :cost/ticks 2000000}
  [r]
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