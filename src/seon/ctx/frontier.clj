(ns seon.ctx.frontier
  "Pure graph-retrieval & turn-event derivation for Seon's layered context engine:
   truly local `O(1/(alpha*eps))` Andersen-Chung-Lang Local-Push heterogeneous PPR over
   Datahike `:eavt`/`:avet` indexes (zero full-DB graph builds!), Weighted RRF fusion,
   Turn `t-1` continuation extraction, and generic failing-turn schema discovery."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.store :as store :refer [db-schema]]
            [seon.render.text :refer [safe-long]]))

(defn- finite-pos [x dflt]
  (if (number? x)
    (let [v (double x)]
      (if (and (== v v) (not (Double/isInfinite v)) (pos? v)) v (double dflt)))
    (double dflt)))

(defn- excluded-attr? [a]
  (when (qualified-keyword? a)
    (let [ns-s (namespace a)]
      (or (= a :db/ident)
          (= ns-s "db")
          (= ns-s "seon.tx")
          (= ns-s "seon.schema")
          (= ns-s "seon.agent.eval")))))

(defn- run-local-push [nbrs-fn seeds opts]
  (let [o (if (map? opts) opts {})
        alpha (double (or (:alpha o) 0.40))
        eps (double (or (:eps o) 0.01))
        max-pushes (long (or (:max-pushes o) 48))
        beta (double (or (:beta o) 0.35))
        total-seed (reduce + 0.0 (map #(finite-pos % 0.0) (vals seeds)))]
    (if (<= total-seed 0.0)
      {}
      (let [deg-cache (java.util.HashMap. 64)
            get-nbrs (fn [u]
                       (if (.containsKey deg-cache u)
                         (.get deg-cache u)
                         (let [ns (or (nbrs-fn u) [])]
                           (.put deg-cache u ns)
                           ns)))
            r0 (reduce-kv (fn [m k v]
                            (let [w (finite-pos v 0.0)]
                              (if (pos? w) (assoc m k (/ w total-seed)) m)))
                          {} seeds)
            q0 (into clojure.lang.PersistentQueue/EMPTY (keys r0))
            in-q0 (set (keys r0))]
        (loop [p {} r r0 q q0 in-q in-q0 pushes 0]
          (if (or (empty? q) (>= pushes max-pushes))
            (reduce-kv (fn [acc k v]
                         (if (pos? (double v))
                           (let [deg (max 1 (count (get-nbrs k)))]
                             (assoc acc k (/ (double v) (Math/pow (double deg) beta))))
                           acc))
                       {} p)
            (let [u (peek q)
                  q1 (pop q)
                  in-q1 (disj in-q u)
                  ru (double (get r u 0.0))
                  nbrs (get-nbrs u)]
              (if (or (empty? nbrs) (<= ru eps))
                (recur (if (pos? ru) (update p u (fnil + 0.0) (* alpha ru)) p)
                       (dissoc r u) q1 in-q1 pushes)
                (let [wsum (double (reduce (fn [^double a [_ w]] (+ a (finite-pos w 0.0))) 0.0 nbrs))]
                  (if (<= wsum 0.0)
                    (recur (update p u (fnil + 0.0) (* alpha ru)) (dissoc r u) q1 in-q1 (inc pushes))
                    (let [p1 (update p u (fnil + 0.0) (* alpha ru))
                          rem-mass (* (- 1.0 alpha) ru)
                          [r2 q2 in-q2]
                          (reduce (fn [[rm qm iqm] [v w]]
                                    (let [fw (finite-pos w 0.0)
                                          delta (* rem-mass (/ fw wsum))
                                          nv (+ (double (get rm v 0.0)) delta)
                                          rm' (assoc rm v nv)]
                                      (if (and (> nv eps) (not (contains? iqm v)))
                                        [rm' (conj qm v) (conj iqm v)]
                                        [rm' qm iqm])))
                                  [(dissoc r u) q1 in-q1]
                                  nbrs)]
                      (recur p1 r2 q2 in-q2 (inc pushes)))))))))))))

(defn acl-forward-push
  "Pure Andersen-Chung-Lang Local-Push Personalized PageRank over a weighted adjacency map
   `{node [[neighbor weight] ...]}` and seed map `{node weight}`."
  {:malli/schema [:=> [:cat
                       [:map-of :int [:vector [:tuple :int [:double {:min 0.01 :max 10.0}]]]]
                       [:map-of :int [:double {:min 0.01 :max 10.0}]]
                       [:? [:maybe [:map
                                    [:alpha {:optional true} [:double {:min 0.05 :max 0.95}]]
                                    [:eps {:optional true} [:double {:min 0.0005 :max 0.5}]]
                                    [:max-pushes {:optional true} [:int {:min 1 :max 1000}]]
                                    [:beta {:optional true} [:double {:min 0.0 :max 1.0}]]]]]]
                  [:map-of :int number?]]
   :cost/ticks 20000000}
  ([adj seeds] (acl-forward-push adj seeds nil))
  ([adj seeds opts]
   (if (empty? adj)
     {}
     (run-local-push #(get adj %) seeds opts))))

(defn rrf-fuse
  "Pure Weighted Reciprocal Rank Fusion over ranked score maps `[{:weight w :scores {eid score}} ...]`."
  {:malli/schema [:=> [:cat
                       [:vector [:map
                                 [:weight [:double {:min 0.01 :max 10.0}]]
                                 [:scores [:map-of :int [:double {:min 0.0 :max 100.0}]]]]]
                       [:? [:maybe [:int {:min 1 :max 100}]]]]
                  [:map-of :int number?]]
   :cost/ticks 5000000}
  ([channels] (rrf-fuse channels 15))
  ([channels k]
   (let [kk (double (max 1 (safe-long k 15)))]
     (reduce (fn [acc {:keys [weight scores]}]
               (let [w (finite-pos weight 1.0)]
                 (if (empty? scores)
                   acc
                   (let [ranked (sort-by (fn [[e s]] [(- (double s)) (long e)]) scores)]
                     (first
                      (reduce (fn [[m rnk] [e s]]
                                (if (pos? (double s))
                                  [(update m e (fnil + 0.0) (/ w (+ kk (double rnk)))) (inc rnk)]
                                  [m rnk]))
                              [acc 1] ranked))))))
             {} channels))))

(def ^:private ref-fwd-weights
  {:seon.code.var/calls 1.5
   :seon.code.var/input 1.2
   :seon.code.var/output 1.2
   :seon.code.var/optional-input 1.0
   :seon.code.var/test-targets 1.3
   :seon.code.doc/references 1.0
   :seon.schema/keys 1.0
   :seon.schema/optional-keys 0.8
   :seon.schema/ref-targets 1.0
   :seon.task/needs 1.2})

(def ^:private ref-rev-weights
  [[:seon.code.var/calls 0.9]
   [:seon.code.var/input 0.7]
   [:seon.code.var/output 0.7]
   [:seon.code.var/optional-input 0.6]
   [:seon.code.var/test-targets 1.0]
   [:seon.code.doc/references 1.1]
   [:seon.schema/keys 0.6]
   [:seon.schema/optional-keys 0.5]
   [:seon.schema/ref-targets 0.6]
   [:seon.task/needs 0.8]])

(defn- local-db-neighbors [db dh rev-attrs u]
(if-not (integer? u)
  []
  (let [out (transient [])
        has-ns-name? (boolean (dh :seon.code.ns/name))
        has-var-ns? (boolean (dh :seon.code.var/ns))
        kind (volatile! nil)]
    (doseq [dt (d/datoms db :eavt u)
            :let [a (:a dt) v (:v dt)]]
      (cond
        (= :seon.code.var/name a) (vreset! kind :var)
        (= :seon.schema/key a) (vreset! kind :schema)
        (= :seon.code.doc/id a) (vreset! kind :doc)
        (= :seon.task/id a) (vreset! kind :task)
        (= :seon.code.ns/name a) (vreset! kind :ns))
      (cond
        (contains? ref-fwd-weights a)
        (when (and (integer? v) (not= v u))
          (conj! out [v (double (get ref-fwd-weights a))]))

        (and (= :seon.code.var/ns a) has-ns-name? (symbol? v))
        (when-let [ne (:e (first (d/datoms db :avet :seon.code.ns/name v)))]
          (when (not= ne u) (conj! out [ne 0.6])))

        (and (= :seon.code.ns/name a) has-var-ns? (symbol? v))
        (doseq [vdt (take 48 (d/datoms db :avet :seon.code.var/ns v))
                :let [ve (:e vdt)]
                :when (and (integer? ve) (not= ve u))]
          (conj! out [ve 0.35]))))
    (let [k @kind
          allowed-rev (case k
                        :var #{:seon.code.var/calls :seon.code.var/test-targets :seon.code.doc/references}
                        :schema #{:seon.code.var/input :seon.code.var/output :seon.code.var/optional-input :seon.schema/keys :seon.schema/optional-keys :seon.schema/ref-targets}
                        :task #{:seon.task/needs :seon.code.doc/references}
                        :doc #{:seon.code.doc/references}
                        nil)]
      (when allowed-rev
        (doseq [[a wr] rev-attrs
                :when (contains? allowed-rev a)
                dt (take 48 (d/datoms db :avet a u))
                :let [src (:e dt)]
                :when (and (integer? src) (not= src u))]
          (conj! out [src (double wr)]))))
    (persistent! out))))

(def ^:private code-ref-re
  #"(?:'([a-zA-Z][\w.-]*(?:/[\w.*?!+-]+)?)|\(quote\s+([a-zA-Z][\w.-]*(?:/[\w.*?!+-]+)?)\)|\b(seon\.[a-z0-9._-]+(?:/[a-zA-Z0-9*+!_?<>=.-]+)?)|:([a-zA-Z][\w.-]+/[\w.*?!+-]+))")

(defn extract-text-domain-eids
  "Extracts referenced var, namespace, schema, task, and doc EIDs from code/error/output text via `:avet` lookups."
  {:malli/schema [:=> [:cat :seon.host/db :map [:maybe :string]] :map]
   :cost/ticks 10000000}
  [db dh ^String text]
  (if (or (nil? text) (str/blank? text))
    {:vars #{} :schemas #{} :all #{}}
    (let [s (if (> (count text) 16000) (subs text 0 16000) text)
          has-var? (boolean (dh :seon.code.var/name))
          has-ns? (boolean (dh :seon.code.ns/name))
          has-sch? (boolean (dh :seon.schema/key))
          has-task? (boolean (dh :seon.task/id))
          has-doc? (boolean (dh :seon.code.doc/id))
          vars (volatile! #{})
          schemas (volatile! #{})
          all (volatile! #{})]
      (doseq [m (re-seq code-ref-re s)]
        (let [sym-s (or (nth m 1) (nth m 2) (nth m 3))
              kw-s (nth m 4)]
          (cond
            (and (string? sym-s) (< (count sym-s) 128))
            (if (str/includes? sym-s "/")
              (let [[a b] (str/split sym-s #"/" 2)]
                (when (and (seq a) (seq b) (not= a "result"))
                  (when-let [e (and has-var? (:e (first (d/datoms db :avet :seon.code.var/name (symbol a b)))))]
                    (vswap! vars conj e) (vswap! all conj e))))
              (when (and has-ns? (str/includes? sym-s "."))
                (when-let [e (:e (first (d/datoms db :avet :seon.code.ns/name (symbol sym-s))))]
                  (vswap! all conj e))))

            (and (string? kw-s) (< (count kw-s) 128))
            (let [[a b] (str/split kw-s #"/" 2)
                  kw (when (and (seq a) (seq b)) (keyword a b))]
              (when kw
                (when-let [se (and has-sch? (:e (first (d/datoms db :avet :seon.schema/key kw))))]
                  (vswap! schemas conj se) (vswap! all conj se))
                (when-let [te (and has-task? (:e (first (d/datoms db :avet :seon.task/id kw))))]
                  (vswap! all conj te)))))))
      (when (and has-doc? (str/includes? s "docs/"))
        (doseq [[_ dp] (re-seq #"(docs/[a-zA-Z0-9._/-]+\.md(?:#[a-zA-Z0-9._-]+)?)" s)]
          (when-let [de (:e (first (d/datoms db :avet :seon.code.doc/id dp)))]
            (vswap! all conj de))))
      {:vars @vars :schemas @schemas :all @all})))

(defn derive-turn-events
  "Derives generic failing-turn events (`:has-error?`, `:error-schemas`, `:large-result?`) purely from Datahike edges."
  {:malli/schema [:=> [:cat :seon.host/db :map [:sequential :map] [:sequential :map]] :map]
   :cost/ticks 10000000}
  [db dh latest-evs latest-live-calls]
  (let [failing (filterv #(and (string? (:seon.agent.eval/error %))
                               (not (str/blank? (:seon.agent.eval/error %))))
                         latest-evs)
        large? (boolean (some #(or (> (count (str (:seon.agent.eval/value %))) 8000)
                                   (> (count (str (:seon.agent.eval/out %))) 8000))
                              latest-evs))
        err-schemas
        (if (empty? failing)
          #{}
          (let [has-sch? (boolean (dh :seon.schema/key))
                has-in? (boolean (dh :seon.code.var/input))
                has-opt? (boolean (dh :seon.code.var/optional-input))
                has-out? (boolean (dh :seon.code.var/output))
                sch-eids (volatile! #{})
                add-var-schemas! (fn [ve]
                                   (when has-in?
                                     (doseq [dt (d/datoms db :eavt ve :seon.code.var/input)]
                                       (when (integer? (:v dt)) (vswap! sch-eids conj (:v dt)))))
                                   (when has-opt?
                                     (doseq [dt (d/datoms db :eavt ve :seon.code.var/optional-input)]
                                       (when (integer? (:v dt)) (vswap! sch-eids conj (:v dt)))))
                                   (when has-out?
                                     (doseq [dt (d/datoms db :eavt ve :seon.code.var/output)]
                                       (when (integer? (:v dt)) (vswap! sch-eids conj (:v dt))))))]
            (doseq [ev failing
                    :let [code-s (str (:seon.agent.eval/code ev))
                          err-s (str (:seon.agent.eval/error ev))
                          ex-c (extract-text-domain-eids db dh code-s)
                          ex-e (extract-text-domain-eids db dh err-s)]]
              (vswap! sch-eids set/union (:schemas ex-c) (:schemas ex-e))
              (doseq [ve (set/union (:vars ex-c) (:vars ex-e))]
                (add-var-schemas! ve))
              (when-let [uns (find-ns 'user)]
                (doseq [[_ hname] (re-seq #"user/([a-zA-Z0-9*+!_?<>=.-]+)" code-s)]
                  (when-let [uv (ns-resolve uns (symbol hname))]
                    (when-let [src-sym (:seon.repl/source (meta uv))]
                      (when-let [ve (and (dh :seon.code.var/name)
                                         (:e (first (d/datoms db :avet :seon.code.var/name src-sym))))]
                        (add-var-schemas! ve)))))))
            (when has-sch?
              (doseq [c latest-live-calls
                      :let [es (:eids c)]
                      :when (<= (count es) 16)
                      e es]
                (doseq [dt (d/datoms db :eavt e)
                        :let [a (:a dt)]
                        :when (and (qualified-keyword? a) (not (excluded-attr? a)))]
                  (when-let [se (:e (first (d/datoms db :avet :seon.schema/key a)))]
                    (vswap! sch-eids conj se)))))
            @sch-eids))]
    {:has-error? (boolean (seq failing))
     :error-schemas err-schemas
     :large-result? large?}))

(defn compute-continuation-scores
  "Extracts Turn `t-1` continuation entity scores from latest eval `:value`/`:out`/`:error`/`:reasoning`/`:thought`."
  {:malli/schema [:=> [:cat :seon.host/db :map [:sequential :map] :any :any] :map]
   :cost/ticks 10000000}
  [db dh latest-evs canon-fn archived?]
  (if (empty? latest-evs)
    {}
    (let [val-eids (into #{}
                         (comp (mapcat (fn [ev]
                                         (concat (:all (extract-text-domain-eids db dh (:seon.agent.eval/value ev)))
                                                 (:all (extract-text-domain-eids db dh (:seon.agent.eval/out ev)))
                                                 (:all (extract-text-domain-eids db dh (:seon.agent.eval/error ev))))))
                               (map canon-fn)
                               (remove archived?))
                         latest-evs)
          rsn-eids (into #{}
                         (comp (mapcat (fn [ev]
                                         (concat (:all (extract-text-domain-eids db dh (:seon.agent.eval/reasoning ev)))
                                                 (:all (extract-text-domain-eids db dh (:seon.agent.eval/thought ev))))))
                               (map canon-fn)
                               (remove archived?))
                         latest-evs)
          v-w (if (seq val-eids) (/ 2.0 (Math/sqrt (double (count val-eids)))) 0.0)
          r-w (if (seq rsn-eids) (/ 3.0 (Math/sqrt (double (count rsn-eids)))) 0.0)]
      (as-> {} m
        (reduce #(update %1 %2 (fnil + 0.0) v-w) m val-eids)
        (reduce #(update %1 %2 (fnil + 0.0) r-w) m rsn-eids)))))

(defn ^:seon.flow/edge compute-novel-frontier
  "Computes Weighted RRF novel working frontier (`1.3*Continuation + 1.0*ACL-PPR + 0.8*Unseen-Touches`, `k=15`)
   using truly local lazy `:eavt`/`:avet` neighbor expansion (zero full-DB graph build!)."
  {:malli/schema [:=> [:cat :seon.host/db :map [:set :any] [:set :any] :map :any] :map]
   :cost/ticks 20000000}
  [bdb energy written-set explicit-set cont-scores archived?]
  (let [seeds (reduce-kv (fn [m e v]
                           (if (and (integer? e) (> (double v) 0.05) (not (archived? e)))
                             (assoc m e (double v))
                             m))
                         {} energy)
        ppr-scores (if (seq seeds)
                     (store/without-agent-eval-reads
                      (fn []
                        (let [dh (db-schema bdb)
                              rev-attrs (filterv #(contains? dh (first %)) ref-rev-weights)]
                          (run-local-push #(local-db-neighbors bdb dh rev-attrs %)
                                          seeds
                                          {:alpha 0.40 :eps 0.01 :max-pushes 48 :beta 0.35}))))
                     {})
        seen-explicit (set/union written-set explicit-set)
        unseen-passive (reduce-kv (fn [m e v]
                                    (if (and (integer? e) (pos? (double v))
                                             (not (contains? seen-explicit e))
                                             (not (archived? e)))
                                      (assoc m e (double v))
                                      m))
                                  {} energy)
        fused (rrf-fuse [{:weight 1.3 :scores cont-scores}
                         {:weight 1.0 :scores ppr-scores}
                         {:weight 0.8 :scores unseen-passive}]
                        15)]
    (reduce-kv (fn [m e sc]
                 (if (and (integer? e)
                     (>= (double sc) 0.02)
                     (not (contains? seen-explicit e))
                     (not (archived? e))
                     (not (and ((db-schema bdb) :seon.code.var/private?)
                               (true? (:v (first (d/datoms bdb :eavt e :seon.code.var/private?)))))))
                   (assoc m e (double sc))
                   m))
               {} fused)))