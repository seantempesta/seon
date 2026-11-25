(ns seon.code.mine
  "Mechanical Datahike miners & knowledge-graph analytics (pure tx-data generators + read-only reports):
   - `mine-schema-examples-tx`: backfills `:seon.schema/examples` from real EDN-safe, Malli-valid attribute values
     or compact entity maps (<= 200 printed chars, skipping refs, byte arrays, and base64 blobs).
   - `mine-docstring-refs-tx`: lifts backticked `seon.*/*` vars and `:seon.*/*` schema keys in var docstrings
     into missing `:seon.code.var/refs`, `:seon.code.var/calls`, and `:seon.code.var/keywords` edges.
   - `graph-density-report`: single-pass AEVT coverage of contracts, docs, schema edges, and examples.
   - `turn0-recall-precision-report`: Turn-0 projected context vs each modern agent's trajectory touches."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [malli.core :as m]
            [seon.health.efficiency :as efficiency]
            [seon.health.static :as static]
            [seon.schema :as schema]
            [seon.signals.scoreboard :as scoreboard]
            [datahike.datom :as dd]
            [datahike.index.interface :as di]
            [konserve.core :as k]
            [seon.code.reach :as reach]))

(defn- blob-string? [^String s]
  (or (str/starts-with? s "data:")
      (and (> (count s) 80) (boolean (re-find #"[A-Za-z0-9+/=]{64,}" s)))))

(defn- edn-safe? [v]
  (cond
    (or (nil? v) (boolean? v) (number? v) (keyword? v) (symbol? v) (uuid? v) (inst? v)) true
    (string? v) (and (<= (count v) 200) (not (blob-string? v)))
    (bytes? v) false
    (or (vector? v) (set? v) (list? v)) (and (<= (count v) 12) (every? edn-safe? v))
    (map? v) (and (<= (count v) 12) (every? (fn [[k val]] (and (edn-safe? k) (edn-safe? val))) v))
    :else false))

(defn- small-edn? [v]
  (and (edn-safe? v)
       (if (string? v)
         (<= (count v) 200)
         (<= (count (pr-str v)) 200))))

(defn- true-eids [db a]
  (if (contains? (:schema db) a)
    (into #{} (keep #(when (true? (:v %)) (:e %))) (d/datoms db :aevt a))
    #{}))

(defn- attr-eids [db a]
  (if (contains? (:schema db) a)
    (into #{} (map :e) (d/datoms db :aevt a))
    #{}))

(defn- entity-form? [form]
  (and (vector? form) (= :map (first form)) (map? (second form)) (true? (:seon.db/attributes (second form)))))

(defn- sample-attr-values [db k valid?]
  (let [vals (if (= :db.cardinality/many (get-in db [:schema k :db/cardinality]))
               (->> (d/datoms db :aevt k) (map :e) distinct (take 20)
                    (map (fn [e] (into #{} (map :v) (d/datoms db :eavt e k)))))
               (->> (d/datoms db :aevt k) (map :v) (take 60) distinct))]
    (into [] (comp (filter small-edn?) (filter valid?) (take 2)) vals)))

(defn- sample-entity-maps [db form valid?]
  (let [sch (:schema db)
        entries (keep (fn [e] (when (vector? e)
                                {:k (first e) :optional? (and (map? (second e)) (true? (:optional (second e))))}))
                      (drop 2 form))
        plain? #(and (contains? sch %) (not (contains? #{:db.type/ref :db.type/bytes} (get-in sch [% :db/valueType]))))
        attrs (filterv plain? (map :k entries))
        anchor (some #(when (and (not (:optional? %)) (plain? (:k %))) (:k %)) entries)]
    (if-not anchor
      []
      (->> (d/datoms db :aevt anchor) (map :e) distinct (take 20)
           (map #(dissoc (d/pull db attrs %) :db/id))
           (into [] (comp (filter small-edn?) (filter valid?) (take 2)))))))

(defn mine-schema-examples-tx
  "Pure tx-data backfilling `:seon.schema/examples` (up to 2 distinct real EDN-safe Malli-valid values or compact entity maps <= 200 chars)."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :map]] :cost/ticks 4000000000}
  [db]
  (if-not ((:schema db) :seon.schema/key)
    []
    (let [reg (:registry (schema/registry-for-db db)) has-ex (attr-eids db :seon.schema/examples)]
      (vec (for [dt (d/datoms db :aevt :seon.schema/key) :when (not (has-ex (:e dt)))
                 :let [k (:v dt) form (:v (first (d/datoms db :eavt (:e dt) :seon.schema/form)))
                       attr (get (:schema db) k)
                       vfn (delay (try (m/validator k {:registry reg}) (catch Throwable _ (constantly false))))
                       ok? #(try (@vfn %) (catch RuntimeException _ false))
                       exs (cond (entity-form? form) (sample-entity-maps db form ok?)
                                 (and attr (not (#{:db.type/ref :db.type/bytes} (:db/valueType attr)))) (sample-attr-values db k ok?)
                                 :else [])]
                 :when (seq exs)]
             {:seon.schema/key k :seon.schema/examples exs})))))

(defn doc-mentions
  "Backticked `seon.*/*` var symbols, `seon.*` namespace symbols, and `:seon.*/*` keywords mentioned in `doc`."
  {:malli/schema [:=> [:cat :string]
                  [:map [:syms [:set :qualified-symbol]] [:nss [:set :symbol]] [:keywords [:set :qualified-keyword]]]]
   :cost/ticks 5000000}
  [doc]
  {:syms (into #{}
               (map (fn [[_ s]] (let [i (str/index-of s "/")] (symbol (subs s 0 i) (subs s (inc i))))))
               (re-seq #"`(seon\.[a-z0-9._-]+/[a-zA-Z0-9*+!_?<>=.-]+)`" doc))
   :nss (into #{}
              (map (fn [[_ s]] (symbol s)))
              (re-seq #"`(seon\.[a-z0-9_-]+(?:\.[a-z0-9_-]+)*)`" doc))
   :keywords (into #{}
                   (map (fn [[_ s]] (keyword (subs s 1))))
                   (re-seq #"`(:seon\.[a-z0-9._-]+/[a-zA-Z0-9*+!_?<>=.-]+)`" doc))})

(defn mine-docstring-refs-tx
  "Pure O(N) flat-AEVT tx-data lifting backticked var docstring mentions into missing `:seon.code.var/{refs,calls,keywords}` edges (skipping self-refs and pure->edge refs)."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :map]] :cost/ticks 2000000000}
  [db]
  (let [sch (:schema db)]
    (if-not (and (sch :seon.code.var/doc) (sch :seon.code.var/name))
      []
      (let [v->e (into {} (map (juxt :v :e)) (d/datoms db :aevt :seon.code.var/name))
            e->v (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.code.var/name))
            sks (if (sch :seon.schema/key) (into #{} (map :v) (d/datoms db :aevt :seon.schema/key)) #{})
            edges (true-eids db :seon.code.var/flow-edge?)
            gens (if (sch :seon.code.var/schema) (into #{} (keep #(when (schema/generatable-function-schema? (:v %)) (:e %))) (d/datoms db :aevt :seon.code.var/schema)) #{})
            eav #(if (sch %2) (into #{} (map :v) (d/datoms db :eavt %1 %2)) #{})]
        (vec (for [dt (d/datoms db :aevt :seon.code.var/doc) :let [doc (str (:v dt))]
                   :when (or (str/includes? doc "`seon.") (str/includes? doc "`:seon."))
                   :let [e (:e dt) self (get e->v e) pg? (and (gens e) (not (edges e)))
                         {:keys [syms keywords]} (doc-mentions doc)
                         ms (keep #(when (not= % self) (when-let [te (get v->e %)] [% te])) syms)
                         hr (when (seq ms) (eav e :seon.code.var/refs))
                         hc (when (seq ms) (eav e :seon.code.var/calls))
                         hk (when (seq keywords) (eav e :seon.code.var/keywords))
                         nr (into #{} (keep (fn [[s te]] (when (and (not (hr s)) (not (and pg? (edges te)))) s))) ms)
                         nc (into #{} (keep (fn [[_ te]] (when-not (hc te) te))) ms)
                         nk (into #{} (filter #(and (not (hk %)) (sks %))) keywords)]
                   :when (and self (or (seq nr) (seq nc) (seq nk)))]
               (cond-> {:seon.code.var/name self} (seq nr) (assoc :seon.code.var/refs nr) (seq nc) (assoc :seon.code.var/calls nc) (seq nk) (assoc :seon.code.var/keywords nk))))))))

(defn- opaque-kinds [sch]
  (when (and (vector? sch) (= :=> (first sch)))
    (let [[_ cat-s ret] (if (map? (second sch)) (rest sch) sch)
          args (when (vector? cat-s) (remove map? (rest cat-s)))
          parts (cons ret args)]
      {:map? (boolean (some #(or (contains? #{:map 'map?} %) (and (vector? %) (= [:map] (filterv (complement map?) %)))) parts))
       :any? (boolean (some #(= :any %) parts))})))

(defn graph-density-report
  "Single-pass flat-AEVT knowledge-graph density over public non-test Seon vars (forks excluded) and `:seon.schema/*` entities."
  {:malli/schema [:=> [:cat :seon.host/db] :map] :cost/ticks 1000000000}
  [db]
  (let [t0 (System/nanoTime) sch (:schema db)
        hidden (into (true-eids db :seon.code.var/private?) (true-eids db :seon.code.var/test?))
        edge (true-eids db :seon.code.var/flow-edge?) foreign (static/foreign-nss db)
        pub (if (sch :seon.code.var/ns)
              (into #{} (keep (fn [dt] (let [n (:v dt)] (when (and (symbol? n) (not (hidden (:e dt))) (not (foreign n)) (not (str/ends-with? (name n) "-test"))) (:e dt)))))
                    (d/datoms db :aevt :seon.code.var/ns)) #{})
        cnt #(count (filter pub (attr-eids db %)))
        schemas (if (sch :seon.code.var/schema) (into [] (keep #(when (pub (:e %)) [(:e %) (:v %)])) (d/datoms db :aevt :seon.code.var/schema)) [])
        pk (keep (fn [[e s]] (when-not (edge e) (opaque-kinds s))) schemas)
        se (attr-eids db :seon.schema/key)]
    {:public-vars (count pub) :with-schema (count schemas) :with-doc (cnt :seon.code.var/doc)
     :with-input-edges (cnt :seon.code.var/input) :with-output-edges (cnt :seon.code.var/output)
     :with-examples (count (filter (fn [[_ s]] (and (vector? s) (map? (second s)) (contains? (second s) :examples))) schemas))
     :schemas-total (count se) :schemas-with-doc (count (filter se (attr-eids db :seon.schema/doc)))
     :schemas-with-examples (count (filter se (attr-eids db :seon.schema/examples)))
     :pure-opaque-map (count (filter :map? pk)) :pure-opaque-any (count (filter :any? pk))
     :ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e4)) 100.0)}))

(defn- modern-agent? [db aid]
  (and (qualified-keyword? aid)
       (not= "repl" (namespace aid))
       (when-let [e (:e (first (d/datoms db :avet :seon.agent/id aid)))]
         (boolean (or (and (contains? (:schema db) :seon.agent/tasks) (first (d/datoms db :eavt e :seon.agent/tasks)))
                      (first (d/datoms db :eavt e :seon.agent/scope)))))))

(defn- recent-agents [db {:keys [agents limit min-evals]}]
  (or (seq agents)
      (->> (d/datoms db :aevt :seon.agent.eval/agent)
           (reduce (fn [m dt] (update m (:v dt) (fn [[c tx]] [(inc (or c 0)) (max (or tx 0) (:tx dt))]))) {})
           (filter (fn [[a [c _]]] (and (>= c (or min-evals 3)) (modern-agent? db a))))
           (sort-by (fn [[a [_ tx]]] [(- tx) (str a)]))
           (map key)
           (take (or limit 3)))))

(defn turn0-recall-precision-report
  "Thin wrapper over `seon.signals.scoreboard/context-scoreboard` computing per-agent Precision, Recall, and recall gaps."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :map]] :map]
   :cost/ticks 5000000000}
  [db opts]
  (if-not (contains? (:schema db) :seon.agent.eval/agent)
    {:agents [] :precision 0.0 :recall 0.0 :recall-gap-items []}
    (let [options (or opts {})
          sb-opts {:token-budget (long (or (:token-budget options) 16000))}
          scores (mapv #(scoreboard/context-scoreboard db db % sb-opts) (recent-agents db options))
          rows (mapv (fn [sb]
                       {:agent (:agent sb)
                        :evals (:eval-count sb)
                        :shown (:shown-count sb)
                        :touched (max (long (or (:pool-touched-count sb) 0))
                                      (long (or (:shown-touched-count sb) 0)))
                        :hits (:shown-touched-count sb)
                        :covered (:shown-touched-count sb)
                        :precision (:precision sb)
                        :recall (:recall sb)})
                     scores)
          total-shown (reduce + 0 (map :shown rows))
          total-touched (reduce + 0 (map :touched rows))]
      {:agents rows
       :precision (if (pos? total-shown) (/ (double (reduce + 0 (map :hits rows))) total-shown) 0.0)
       :recall (if (pos? total-touched) (/ (double (reduce + 0 (map :covered rows))) total-touched) 0.0)
       :recall-gap-items (->> (mapcat :recall-gap-items scores)
                              (keep #(or (:sym %) (:schema %) (:entity %)))
                              frequencies
                              (sort-by (fn [[item freq]] [(- freq) (str item)]))
                              (take 15)
                              (mapv key))})))

(defn normalize-hygiene-slug
  "Lowercase kebab slug for `:hygiene/*` task IDs."
  {:malli/schema [:=> {:examples [[["Foo/Bar_baz!"] "foo-bar-baz"] [[""] "item"]]}
                  [:cat [:maybe :string]] [:string {:min 1}]]
   :cost/ticks 100000}
  [s]
  (let [r (-> (str (or s "")) str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-+|-+$" ""))
        t (if (> (count r) 56) (str/replace (subs r 0 56) #"-+$" "") r)]
    (if (str/blank? t) "item" t)))

(defn path->ns-sym
  "Maps a Clojure source path (`src/a/b_c.clj`) to its namespace symbol, or nil."
  {:malli/schema [:=> {:examples [[["src/seon/signals/cache.clj"] 'seon.signals.cache]
                                  [["/r/src/foo/bar_baz.cljc"] 'foo.bar-baz] [["README.md"] nil]]}
                  [:cat [:maybe :string]] [:maybe :symbol]]
   :cost/ticks 200000}
  [path]
  (when (string? path)
    (when-let [rel (second (re-find #"(?:^|/)(?:src|test)/([a-zA-Z0-9_./-]+)\.cljc?$" (str/replace path "\\" "/")))]
      (let [ns-s (-> rel (str/replace "/" ".") (str/replace "_" "-"))]
        (when-not (or (str/starts-with? ns-s ".") (str/ends-with? ns-s ".") (str/includes? ns-s ".."))
          (symbol ns-s))))))

(defn- common-parent-ns [nss]
  (let [ss (vec (distinct (sort nss)))]
    (if (<= (count ss) 1)
      (or (first ss) 'seon)
      (let [ps (mapv #(str/split (name %) #"\.") ss)
            sh (vec (take-while some? (apply map (fn [& xs] (when (apply = xs) (first xs))) ps)))]
        (if (>= (count sh) 2) (symbol (str/join "." sh)) (first ss))))))

(def ^:private excluded-dup-names
  #{"ns" "-main" "report" "render" "init" "start!" "stop!"
    "install-user-helpers!" "install-agent-helpers!" "install-repl-helpers!" "install-ctx-helpers!"
    "handle-req" "handle-ipc" "handle-timer" "call!" "record-summary!" "resolve-doc-refs"})

(defn- scan-hygiene-index [db]
(let [sch (:schema db) foreign (static/foreign-nss db)
      tests (java.util.HashSet. 512) edges (java.util.HashSet. 128)
      fk->fe (java.util.HashMap. 2800) form->hash (java.util.HashMap. 2800)
      vars-acc (transient [])]
  (when (sch :seon.code.var/test?) (doseq [dt (d/datoms db :aevt :seon.code.var/test?)] (when (:v dt) (.add tests (:e dt)))))
  (when (sch :seon.code.var/flow-edge?) (doseq [dt (d/datoms db :aevt :seon.code.var/flow-edge?)] (when (:v dt) (.add edges (:e dt)))))
  (when (sch :seon.code.form/key) (doseq [dt (d/datoms db :aevt :seon.code.form/key)] (.put fk->fe (:v dt) (:e dt))))
  (when (sch :seon.code.form/hash) (doseq [dt (d/datoms db :aevt :seon.code.form/hash)] (.put form->hash (:e dt) (:v dt))))
  (when (sch :seon.code.var/name)
    (doseq [dt (d/datoms db :aevt :seon.code.var/name)
            :let [eid (:e dt) sym (:v dt) ns-str (when (symbol? sym) (namespace sym)) nsym (when ns-str (symbol ns-str))]
            :when (and nsym (not (.contains tests eid)) (not (foreign nsym)) (not (str/ends-with? ns-str "-test")))]
      (let [fkey (str sym) form-eid (.get fk->fe fkey)]
        (conj! vars-acc {:e eid :fe form-eid :sym sym :ns nsym :fk fkey
                         :hash (when form-eid (.get form->hash form-eid)) :edge? (.contains edges eid)}))))
  {:vars (persistent! vars-acc)}))

(def ^:private raw-java-io-re
  #"(?:java\.io\.File\.|\(File\.|\(\.delete\b|ProcessBuilder\.|\(\.mkdirs\b)")

(def ^:private default-hygiene-template-plan
  "1. Inspect curated vars/namespaces, `user/callers`, and `user/tests-for`.\n2. Consolidate duplicates, migrate raw Java IO/process to `jolt.fs`/`jolt.process`, or split cleanly without breaking contracts.\n3. Prove `(user/run-tests!)` + `(user/health)` green, record `:summary`, then call `(user/ready!)`.")

(defn- mk-finding [kind slug scope title summary curated impact]
  {:id (keyword "hygiene" (normalize-hygiene-slug slug)) :kind kind :scope scope
   :title title :summary summary :curated curated :impact (long impact)})

(defn- detect-ast-and-dup-findings [db vars]
  (let [hr? (boolean ((:schema db) :seon.code.var/refs))
        clones (vec (for [[h xs] (group-by :hash vars) :let [ns (distinct (map :ns xs)) ss (vec (distinct (sort (map :sym xs))))]
                          :when (and h (> (count ns) 1) (> (count ss) 1))]
                      (mk-finding :ast-clone (str "ast-clone-" (name (first ss)) "-" (subs (str h) 0 (min 6 (count (str h)))))
                        (common-parent-ns ns) (str "Deduplicate AST clone " (name (first ss)))
                        (str "Identical form hash across " (str/join ", " (map str ss)) ". Consolidate into one helper.")
                        (mapv #(vector :seon.code.var/name %) ss) (+ 90 (* 10 (count ss))))))
        cs (into #{} (map #(set (map second (:curated %)))) clones)
        dups (vec (for [[nm xs] (group-by #(name (:sym %)) vars)
                        :when (and (> (count (distinct (map :ns xs))) 1) (not (excluded-dup-names nm))
                                   (not (str/starts-with? nm "render-")) (not (str/ends-with? nm "-proc"))
                                   (not (and (str/starts-with? nm "install-") (str/ends-with? nm "-helpers!"))))
                        :let [as (set (map :sym xs))
                              ind (if hr? (filterv (fn [{:keys [e sym]}] (not-any? #(and (not= (:v %) sym) (as (:v %))) (d/datoms db :eavt e :seon.code.var/refs))) xs) xs)
                              ns (distinct (map :ns ind)) ss (vec (distinct (sort (map :sym ind))))]
                        :when (and (> (count ns) 1) (> (count ss) 1) (not (cs (set ss))))]
                    (mk-finding :duplicate-helper (str "dup-helper-" nm) (common-parent-ns ns)
                      (str "Consolidate duplicate helper " nm " (" (count ns) " nss)")
                      (str "Duplicate helper `" nm "` in " (str/join ", " (map str ss)) ". Consolidate onto one helper.")
                      (mapv #(vector :seon.code.var/name %) ss) (+ 60 (* 12 (count ss))))))]
    (into clones dups)))

(defn- dedupe-rank [fs]
  (->> fs
       (reduce (fn [m f]
                 (let [k (:id f) p (get m k)]
                   (if (and k (or (nil? p) (> (long (:impact f 0)) (long (:impact p 0))))) (assoc m k f) m)))
               {})
       vals
       (sort-by (fn [f] [(- (long (:impact f 0))) (str (:id f))]))
       vec))

(defn ingest-jolt-metrics-findings
  "Maps `jlt-commons/jolt-metrics` EDN (`:queue`, `:duplicates`, `:hotspots`, `:files`) into structured findings with verified `:curated` refs."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :map]] [:vector :map]] :cost/ticks 100000000}
  [db me]
  (if-not (and db (map? me) (seq me))
    []
    (let [sch (:schema db)
          vs (if (sch :seon.code.var/name) (into #{} (map :v) (d/datoms db :aevt :seon.code.var/name)) #{})
          ns (if (sch :seon.code.ns/name) (into #{} (map :v) (d/datoms db :aevt :seon.code.ns/name)) #{})
          p->n (into {} (for [f (:files me) :let [n (or (some-> (:namespace f) symbol) (path->ns-sym (:path f)))] :when (and (:path f) n)] [(:path f) n]))
          ns-of #(or (some-> (:namespace %) symbol) (get p->n (:path %)) (path->ns-sym (:path %)))
          var-of #(when-let [n (ns-of %)] (when-let [nm (not-empty (str (or (:name %) "")))] (let [s (symbol (name n) nm)] (when (vs s) s))))
          cx (for [e (:queue me) :when (= :function (:kind e))
                   :let [cc (long (or (get-in e [:metrics :cyclomatic]) (:cyclomatic e) 0))
                         cg (long (or (get-in e [:metrics :cognitive]) (:cognitive e) 0)) sc (double (or (:score e) 0.0))
                         v (when (and (or (>= cc 20) (>= cg 30)) (or (= :critical (:severity e)) (>= cc 25) (>= cg 40) (>= sc 15.0))) (var-of e)) n (when v (symbol (namespace v)))]
                   :when (and v (not (str/ends-with? (name n) "-test")))]
               (mk-finding :high-complexity (str "complexity-" n "-" (name v)) n (str "Reduce complexity of " v " (CC " cc ", cog " cg ")")
                 (str v " has high complexity (CC " cc ", cognitive " cg "). Flatten control flow and extract pure helpers.")
                 [[:seon.code.var/name v]] (+ 70 (min 50 (long (Math/round (if (pos? sc) sc (+ cc (/ cg 2.0)))))))))
          ncd (for [{:keys [distance a b]} (:duplicates me) :let [d (double (or distance 1.0)) sa (var-of a) sb (var-of b)]
                    :when (and sa sb (not= sa sb) (<= d 0.25)) :let [[s1 s2] (vec (sort [sa sb])) x? (not= (namespace s1) (namespace s2))]
                    :when (or x? (<= d 0.12)) :let [ds (format "%.2f" d)]]
                (mk-finding :near-duplicate (str "ncd-clone-" (name s1) "-" (name s2)) (common-parent-ns [(symbol (namespace s1)) (symbol (namespace s2))])
                  (str "Deduplicate near-clone " s1 " / " s2 " (NCD " ds ")")
                  (str s1 " and " s2 " are near-clones (NCD " ds "). Consolidate shared logic into one helper.")
                  [[:seon.code.var/name s1] [:seon.code.var/name s2]] (+ 75 (long (Math/round (* 40.0 (- 1.0 (min 1.0 (max 0.0 d)))))) (if x? 15 0))))
          hots (vec (for [h (:hotspots me) :let [n (ns-of h)] :when (and n (ns n) (not (str/ends-with? (name n) "-test")))
                          :let [ch (long (or (:changes h) 0)) cc (long (or (:complexity h) 0)) r (double (or (:risk h) 0.0))]]
                      (mk-finding :coupling-hotspot (str "hotspot-" (name n)) n (str "De-couple hotspot " n " (" ch " changes, CC " cc ")")
                        (str n " is a churn hotspot (" ch " changes, CC " cc ", risk " (format "%.2f" r) "). Extract stable pure seams.")
                        [[:seon.code.ns/name n]] (+ 85 (long (Math/round (* 30.0 (min 2.0 (max 0.0 r)))))))))
          hs (into #{} (map :scope) hots)
          coup (for [f (:files me) :let [ce (long (or (:efferent-coupling f) 0)) n (when (>= ce 12) (ns-of f))]
                     :when (and n (ns n) (not (hs n)) (not (str/ends-with? (name n) "-test")))]
                 (mk-finding :coupling-hotspot (str "coupling-" (name n)) n (str "Reduce coupling in " n " (Ce " ce ")")
                   (str n " has high efferent coupling (Ce " ce "). Trim deps or extract helper modules.")
                   [[:seon.code.ns/name n]] (+ 60 (min 35 (* 2 ce)))))]
      (dedupe-rank (concat cx ncd hots coup)))))

(defonce ^:private ^:seon.flow/edge raw-io-form-cache (atom {}))

(defn problem->hygiene-finding
  "Maps a cached health warning (`:oversized-namespace`, `:large-function`, `:nested-db-scan`, `:silent-catch-all`) to a canonical hygiene finding, or nil."
  {:malli/schema [:=> [:cat :map] [:maybe :map]]
   :cost/ticks 200000}
  [{:keys [class ns var subject message]}]
  (let [msg (str message)]
    (case class
      :oversized-namespace
      (let [tokens (or (some-> (re-find #"~(\d+)\s+tokens" msg) second parse-long) 8500)]
        (mk-finding :oversized-namespace (str "split-ns-" (name ns)) ns
                    (str "Split oversized ns " ns " (~" tokens " tok)")
                    (str ns " is ~" tokens " tokens (> 8k cap). Extract cohesive child namespace(s).")
                    [[:seon.code.ns/name ns]]
                    (+ 70 (min 40 (quot (max 0 (- tokens 8000)) 100)))))

      :large-function
      (when-let [var-sym (or var (when (string? subject) (symbol subject)))]
        (let [[_ lines-str chars-str] (re-find #"\((\d+)L,\s*(\d+)\s+chars" msg)
              lines (or (some-> lines-str parse-long) 121)
              chars (or (some-> chars-str parse-long) 7201)]
          (mk-finding :large-function (str "split-fn-" ns "-" (name var-sym)) ns
                      (str "Split " var-sym " (" lines "L)")
                      (str var-sym " is " lines "L (" chars " chars > 120L budget). Extract pure helpers.")
                      [[:seon.code.var/name var-sym]]
                      (+ 65 (min 40 (max (quot (max 0 (- lines 120)) 5)
                                         (quot (max 0 (- chars 7200)) 400)))))))

      :nested-db-scan
      (mk-finding :nested-db-scan (str "nested-scan-" subject) ns
                  (str "Fix scan " subject)
                  (str "Nested full DB scan in " subject ". Hoist or use :avet.")
                  [(if var [:seon.code.var/name var] [:seon.code.ns/name ns])]
                  78)

      :silent-catch-all
      (when var
        (mk-finding :silent-catch-all (str "silent-catch-" ns "-" (name var)) ns
                    (str "Fix catch " var)
                    (str "Replace silent broad catch in " var " with typed error data or ^:seon.flow/edge.")
                    [[:seon.code.var/name var]]
                    74))

      nil)))

(defn- raw-io-src? [^String src]
  (boolean (and src
                (or (.contains src "File.")
                    (.contains src ".delete")
                    (.contains src "ProcessBuilder.")
                    (.contains src ".mkdirs"))
                (re-find raw-java-io-re src))))

(defn- resolve-raw-src [db raw]
  (if (dd/lazy-value? raw)
    (str (or (when-let [backing (:store db)] (k/get backing (dd/lazy-key raw) nil {:sync? true}))
             (dd/value-of raw)))
    (str raw)))

(defn- ^:seon.flow/edge detect-io-and-size-findings [db {:keys [vars]}]
  (let [eligible (when ((:schema db) :seon.code.form/src)
                   (filterv #(and (:fe %) (not= 'seon.code.mine (:ns %))
                                  (not (and (:edge? %) (#{'seon.store 'seon.browser} (:ns %)))))
                            vars))
        cached @raw-io-form-cache
        missed (java.util.HashSet. 32)
        _ (doseq [{:keys [fe hash]} eligible :when (not (and hash (contains? cached hash)))] (.add missed fe))
        fetched (java.util.HashMap. (max 16 (.size missed)))
        _ (when (pos? (.size missed))
            (binding [di/*read-key-attrs* #{}]
              (if (<= (.size missed) 150)
                (doseq [fe missed] (when-let [dt (first (d/datoms db :eavt fe :seon.code.form/src))] (.put fetched fe (raw-io-src? (resolve-raw-src db (:v dt))))))
                (doseq [dt (d/datoms db :aevt :seon.code.form/src) :let [fe (:e dt)] :when (.contains missed fe)]
                  (.put fetched fe (raw-io-src? (resolve-raw-src db (:v dt))))))))
        new-entries (volatile! {})
        io-by-ns (reduce (fn [acc {:keys [fe ns sym hash]}]
                           (let [hit? (if (and hash (contains? cached hash))
                                        (get cached hash)
                                        (let [found (boolean (.get fetched fe))] (when hash (vswap! new-entries assoc hash found)) found))]
                             (if hit? (update acc ns (fnil conj []) sym) acc)))
                         {} eligible)
        _ (when (seq @new-entries) (swap! raw-io-form-cache #(let [m (into % @new-entries)] (if (> (count m) 4096) @new-entries m))))
        io-findings (for [[nsym syms] io-by-ns :let [uniq (vec (distinct (sort syms)))]]
                      (mk-finding :raw-java-io (str "java-io-" (name nsym)) nsym (str "Migrate raw Java IO/process in " nsym)
                                  (str nsym " uses raw Java IO/process in " (str/join ", " (map str uniq)) ". Migrate to jolt.fs / jolt.process.")
                                  (mapv #(vector :seon.code.var/name %) uniq) (+ 75 (* 8 (count uniq)))))
        size-findings (keep #(when (#{:oversized-namespace :large-function} (:class %)) (problem->hygiene-finding %))
                            (efficiency/static-efficiency-warnings db nil (static/form-meta-snapshot db)))]
    (vec (concat io-findings size-findings))))

(defn mine-hygiene-findings
  "O(N) indexed Datahike scan detecting `:ast-clone`, `:duplicate-helper`, `:raw-java-io`, `:oversized-namespace`, and `:large-function` findings ranked by `:impact`."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :map]] :cost/ticks 250000000}
  [db]
  (let [idx (scan-hygiene-index db)]
    (dedupe-rank (concat (detect-ast-and-dup-findings db (:vars idx))
                         (detect-io-and-size-findings db idx)))))

(defn mine-hygiene-tasks-tx
  "Converts hygiene findings (`mine-hygiene-findings` + optional `:metrics-edn` / `:findings`) into idempotent `:seon.task/*` tx maps using `:template/hygiene-cleanup`. Skips existing/done tasks, attaches verified `:curated` refs + reaching tests, and separates rough plan from template footer with `---`."
  {:malli/schema [:function [:=> [:cat :seon.host/db] [:vector :map]] [:=> [:cat :seon.host/db [:maybe :map]] [:vector :map]]]
   :cost/ticks 400000000}
  ([db] (mine-hygiene-tasks-tx db nil))
  ([db opts]
   (let [o (if (map? opts) opts {}) tpl (or (:template o) :template/hygiene-cleanup)
         lim (if (contains? o :limit) (:limit o) 12) mt (long (or (:max-tests o) 8))
         ex-sc (set (:exclude-scopes o)) ex-id (set (:exclude-ids o)) sch (:schema db) htid? (boolean (sch :seon.task/id))
         footer (or (when (and htid? (first (d/datoms db :avet :seon.task/id tpl)))
                      (not-empty (:seon.task/plan (d/pull db '[:seon.task/plan] [:seon.task/id tpl]))))
                    default-hygiene-template-plan)
         ranked (dedupe-rank (if (contains? o :findings) (:findings o)
                               (concat (mine-hygiene-findings db) (when-let [me (:metrics-edn o)] (ingest-jolt-metrics-findings db me)))))
         ok? #(and (vector? %) (= 2 (count %)) (qualified-keyword? (first %)) (sch (first %)) (boolean (first (d/datoms db :avet (first %) (second %)))))
         seen (volatile! #{})
         out (for [{:keys [id scope title summary curated]} ranked
                   :let [cur (filterv ok? (distinct curated)) ck (when (seq cur) (set cur))]
                   :when (and (qualified-keyword? id) (not (ex-id id)) (not (and scope (ex-sc scope)))
                              (not (and htid? (first (d/datoms db :avet :seon.task/id id)))) (or (nil? ck) (not (@seen ck))))]
               (do (when ck (vswap! seen conj ck))
                   (let [tgts (or (seq (keep (fn [[a v]] (when (and (#{:seon.code.var/name :seon.code.ns/name} a) (symbol? v)) v)) cur)) (when (symbol? scope) [scope]))
                         ts (->> tgts (mapcat #(reach/reaching-tests db %)) distinct sort (take mt) set)]
                     (cond-> {:seon.task/id id :seon.task/template tpl :seon.task/title (str (or title (name id)))
                              :seon.task/plan (str (str/trim (str (or summary title (name id)))) "\n\n---\n" footer)
                              :seon.task/done? false :seon.task/created-by :seon.code/mine}
                       (symbol? scope) (assoc :seon.task/scope scope) (seq cur) (assoc :seon.task/curated cur) (seq ts) (assoc :seon.task/tests ts)))))]
     (if (and (integer? lim) (pos? (long lim))) (vec (take (long lim) out)) (vec out)))))