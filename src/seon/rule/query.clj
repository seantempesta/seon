(ns seon.rule.query
  "Static Datalog `:seon.rule/query` analysis and sound AND-OR unsatisfiability pre-skip with `datahike.reads` forwarding."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [datahike.index.interface :as di]
            [seon.store :as store]))

(def ^:private db-srcs #{'$ '$main '$branch '$code '$task})
(defonce ^:private query-meta-cache (store/lru-cache 256))

(defn- dsch [db]
  (cond
    (map? (:schema db)) (:schema db)
    (and db (not (map? db))) (try (d/schema db) (catch Throwable _ {}))
    :else {}))

(defn lvar?
  "True when `x` is a logic variable symbol starting with `?`."
  {:malli/schema [:=> [:cat :any] :boolean]
   :cost/ticks 500}
  [x]
  (and (symbol? x) (str/starts-with? (name x) "?")))

(defn- scalar-const? [x]
  (or (keyword? x) (string? x) (number? x) (boolean? x) (uuid? x)))

(defn- const-form? [x]
  (not (some #(or (= '_ %) (lvar? %)) (tree-seq coll? seq x))))

(defn parse-query
  "Normalizes a vector or map Datahike Datalog query into `{:find :keys :in :where}`."
  {:malli/schema [:=> [:cat :any] :map]
   :cost/ticks 50000}
  [q]
  (let [secs #{:find :keys :syms :strs :with :in :where}]
    (cond
      (map? q) (select-keys q [:find :keys :syms :strs :with :in :where])
      (sequential? q)
      (loop [xs q, cur nil, acc {}]
        (if (empty? xs)
          acc
          (let [x (first xs)]
            (if (contains? secs x)
              (recur (rest xs) x (assoc acc x []))
              (recur (rest xs) cur (if cur (update acc cur (fnil conj []) x) acc))))))
      :else {})))

(defn clause-req
  "Extracts a sound AND-OR satisfiability requirement tree from a Datalog `:where` clause."
  {:malli/schema [:=> [:cat :any [:set :any]] [:maybe [:vector :any]]]
   :cost/ticks 100000}
  [cl ags]
  (cond
    (vector? cl)
    (let [n (count cl)
          h (when (pos? n) (nth cl 0))
          a (when (>= n 3) (nth cl 2))
          v0 (when (>= n 4) (nth cl 3))]
      (cond
        (and (>= n 3) (contains? db-srcs h) (keyword? a))
        (let [e (nth cl 1)
              v (cond (< n 4) ::any (= '?agent v0) ::agent (scalar-const? v0) v0 :else ::any)]
          (cond
            (and (contains? ags [h e]) (not= a :seon.agent/id)) [:ag h a v]
            (and (>= n 4) (contains? ags [h v0])) [:rev h a]
            :else [:db h a v]))
        (and (>= n 3) (= '$wm h) (keyword? a))
        [:wm a (if (and (>= n 4) (scalar-const? v0)) v0 ::any)]
        :else nil))
    (seq? cl)
    (let [op (first cl)]
      (cond
        (= 'and op)
        (let [ags' (reduce (fn [s c]
                             (if (and (vector? c) (>= (count c) 4)
                                      (contains? db-srcs (nth c 0))
                                      (lvar? (nth c 1))
                                      (= :seon.agent/id (nth c 2))
                                      (= '?agent (nth c 3)))
                               (conj s [(nth c 0) (nth c 1)])
                               s))
                           ags (rest cl))
              xs (vec (keep #(clause-req % ags') (rest cl)))]
          (when (seq xs) (if (= 1 (count xs)) (first xs) (into [:and] xs))))
        (or (= 'or op) (= 'or-join op))
        (let [bs (if (= 'or op) (rest cl) (drop 2 cl))
              xs (mapv #(clause-req % ags) bs)]
          (when (and (seq xs) (every? some? xs))
            (if (= 1 (count xs)) (first xs) (into [:or] xs))))
        :else nil))
    :else nil))

(defn- extract-focus-eq [ws]
  (some (fn [cl]
          (when (and (vector? cl) (= 1 (count cl)) (seq? (first cl))
                     (= '= (first (first cl))) (= 3 (count (first cl))))
            (let [[_ a b] (first cl)]
              (cond
                (and (= '?focus a) (const-form? b)) b
                (and (= '?focus b) (const-form? a)) a))))
        ws))

(defn query-meta
  "Analyzes and caches metadata, WM/DB requirements, focus constraints, and input specs for a rule query."
  {:malli/schema [:=> [:cat :any] :map], :cost/ticks 200000, :seon.flow/edge nil}
  [q]
  (if-not (or (vector? q) (map? q))
    {:pq {} :ins [] :wm-attrs #{} :wm-req #{} :wm-const-req [] :wm-not-req []
     :req-tree nil :focus-eq nil :db-keys #{} :fn-syms [] :uses-agent? false :eff-q nil :eff-ins []}
    (store/lru-get! query-meta-cache 256 q
      (fn []
        (let [pq (parse-query q)
              ws (if (sequential? (:where pq)) (:where pq) [])
              raw-in-val (:in pq)
              raw-ins (vec (if (and (sequential? raw-in-val) (seq raw-in-val)) raw-in-val ['$]))
              ins-set (set raw-ins)
              fn-syms (vec (distinct (keep #(when (and (seq? %) (qualified-symbol? (first %))) (first %))
                                           (tree-seq coll? seq ws))))
              _ (doseq [s fn-syms] (try (requiring-resolve s) (catch Throwable _ nil)))
              wm-v? #(and (vector? %) (>= (count %) 3) (= '$wm (nth % 0)) (keyword? (nth % 2)))
              wm-attrs (if-not (ins-set '$wm) #{}
                         (reduce (fn [a n]
                                   (cond
                                     (and (vector? n) (>= (count n) 3) (= '$wm (nth n 0)))
                                     (conj a (if (keyword? (nth n 2)) (nth n 2) :*))
                                     (and (seq? n) (some #(= '$wm %) n))
                                     (let [ks (filter keyword? n)] (if (seq ks) (into a ks) (conj a :*)))
                                     :else a))
                                 #{} (tree-seq coll? seq ws)))
              wm-req (into #{} (keep #(when (wm-v? %) (nth % 2))) ws)
              wm-const-req (vec (keep #(when (and (wm-v? %) (>= (count %) 4) (scalar-const? (nth % 3)))
                                         [(nth % 2) (nth % 3)]) ws))
              pos-by-e (reduce #(if (and (wm-v? %2) (lvar? (nth %2 1)))
                                  (update %1 (nth %2 1) (fnil conj []) (nth %2 2))
                                  %1)
                               {} ws)
              wm-not-req (vec (keep (fn [cl]
                                      (when (and (seq? cl) (= 'not (first cl)) (= 2 (count cl)))
                                        (let [in (second cl)]
                                          (when (and (wm-v? in) (lvar? (nth in 1)))
                                            (when-let [a1 (first (get pos-by-e (nth in 1)))]
                                              [a1 (nth in 2) (if (and (>= (count in) 4) (scalar-const? (nth in 3)))
                                                               (nth in 3) ::any)])))))
                                    ws))
              req-tree (clause-req (cons 'and ws) #{})
              focus-eq (extract-focus-eq ws)
              db-keys (reduce (fn [a s]
                                (case s
                                  $main (conj a :main) $branch (conj a :branch)
                                  $code (conj a :code) $task (conj a :task)
                                  ($ %) (conj a :branch)
                                  (?agent-eid ?focus-eid) (conj a :main :branch)
                                  a))
                              #{} raw-ins)
              need-$? (and (ins-set '%) (not (ins-set '$)))
              eff-q (if-not need-$? q
                      (cond
                        (map? q) (update q :in #(into ['$ ] (if (sequential? %) % [])))
                        (sequential? q) (let [v (vec q) i (.indexOf v :in)]
                                          (if (neg? i) v (vec (concat (subvec v 0 (inc i)) ['$ ] (subvec v (inc i))))))
                        :else q))]
          {:pq pq :ins raw-ins :wm-attrs wm-attrs :wm-req wm-req :wm-const-req wm-const-req :wm-not-req wm-not-req
           :req-tree req-tree :focus-eq focus-eq :db-keys db-keys :fn-syms fn-syms
           :db-fn-call? (boolean (some #(and (seq? %) (qualified-symbol? (first %)) (some db-srcs (rest %)))
                                       (tree-seq coll? seq ws)))
           :uses-agent? (boolean (or (ins-set '?agent) (ins-set '?agent-eid) (ins-set '?focus) (ins-set '?focus-eid)))
           :eff-q eff-q :eff-ins (if need-$? (into ['$ ] raw-ins) raw-ins)})))))

(defn wm-req-miss?
  "True when working memory `wm` provably fails top-level `$wm` requirements in `qm`."
  {:malli/schema [:=> [:cat :map :map] :boolean]
   :cost/ticks 50000}
  [wm qm]
  (let [aev (:aev wm) ave (:ave wm) eav (:eav wm)]
    (boolean
      (or (and (map? aev) (some #(not (contains? aev %)) (:wm-req qm)))
          (and (nil? aev) (boolean (seq (:wm-req qm))))
          (and (map? ave) (some (fn [[a v]] (not (contains? (get ave a) v))) (:wm-const-req qm)))
          (and (map? eav) (map? aev)
               (some (fn [[a1 a2 v2]]
                       (every? (fn [e]
                                 (if (= v2 ::any)
                                   (contains? (get eav e) a2)
                                   (contains? (get-in eav [e a2]) v2)))
                               (keys (get aev a1))))
                     (:wm-not-req qm)))))))

(defn req-tree-empty?
  "Returns true when `tree` is provably unsatisfiable against `wm`/`dbs`/`aid`, recording minimal `datahike.reads` witnesses."
  {:malli/schema [:=> [:cat [:maybe [:vector :any]] :map [:map-of :keyword [:maybe :seon.host/db]] [:maybe :keyword]] :boolean]
   :cost/ticks 500000}
  [tree wm dbs aid]
  (boolean
    (when (seq tree)
      (let [eff (or (:branch dbs) (:code dbs) (:main dbs))
            rdb #(case %
                   $ eff
                   $main (or (:main dbs) eff)
                   $branch (or (:branch dbs) (:main dbs) eff)
                   $code (or (:code dbs) (:branch dbs) (:main dbs))
                   $task (or (:task dbs) (:branch dbs) (:main dbs))
                   nil)
            acache (volatile! {})
            ag-eid (fn [db]
                     (let [m @acache]
                       (if (contains? m db)
                         (get m db)
                         (let [r (when (and aid db ((dsch db) :seon.agent/id))
                                   (:e (first (d/datoms db :avet :seon.agent/id aid))))]
                           (vswap! acache assoc db r)
                           r))))
            go (fn go [node]
                 (when (and (vector? node) (seq node))
                   (case (nth node 0)
                     :and (some go (rest node))
                     :or (let [ws (mapv go (rest node))]
                           (when (and (seq ws) (every? some? ws))
                             (into [] cat ws)))
                     :wm (when (>= (count node) 3)
                           (let [[_ a v] node]
                             (when (if (= v ::any)
                                     (not (contains? (:aev wm) a))
                                     (not (contains? (get (:ave wm) a) v)))
                               [])))
                     :db (when (>= (count node) 4)
                           (let [[_ src a v0] node
                                 db (rdb src)
                                 sp (and db (get (dsch db) a))
                                 v (if (= v0 ::agent) (or aid ::any) v0)]
                             (when sp
                               (if (and (= a :seon.agent/id) (= v0 ::agent) aid)
                                 (when (nil? (ag-eid db))
                                   [#(first (d/datoms db :avet :seon.agent/id aid))])
                                 (let [av? (and (not= v ::any)
                                                (or (:db/index sp) (:db/unique sp) (= :db.type/ref (:db/valueType sp))))]
                                   (when (nil? (first (if av? (d/datoms db :avet a v) (d/datoms db :aevt a))))
                                     [#(first (if av? (d/datoms db :avet a v) (d/datoms db :aevt a)))]))))))
                     :ag (when (>= (count node) 4)
                           (let [[_ src a v0] node
                                 db (rdb src)
                                 dh (dsch db)
                                 sp (and db (get dh a))
                                 v (if (= v0 ::agent) (or aid ::any) v0)]
                             (when (and aid sp (dh :seon.agent/id))
                               (if-let [e (ag-eid db)]
                                 (let [ds (d/datoms db :eavt e a)]
                                   (when (if (= v ::any) (nil? (first ds)) (not-any? #(= v (:v %)) ds))
                                     [#(first (d/datoms db :avet :seon.agent/id aid))
                                      #(first (d/datoms db :eavt e a))]))
                                 [#(first (d/datoms db :avet :seon.agent/id aid))]))))
                     :rev (when (>= (count node) 3)
                            (let [[_ src a] node
                                  db (rdb src)
                                  dh (dsch db)
                                  sp (and db (get dh a))]
                              (when (and aid sp (= :db.type/ref (:db/valueType sp)) (dh :seon.agent/id))
                                (if-let [e (ag-eid db)]
                                  (when (nil? (first (d/datoms db :avet a e)))
                                    [#(first (d/datoms db :avet :seon.agent/id aid))
                                     #(first (d/datoms db :avet a e))])
                                  [#(first (d/datoms db :avet :seon.agent/id aid))]))))
                     nil)))]
        (when-let [ws (binding [di/*reads* nil] (go tree))]
          (doseq [f ws] (f))
          true)))))
