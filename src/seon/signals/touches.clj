(ns seon.signals.touches
  "Pure eval touch & manual-lookup extractor plus live candidate-pool builder for Seon's scoreboard.
   Separates general entity/var/schema usage from manual lookups (`source`, `grep-src`, `d/pull`,
   `ns-outline`, `search`, `callers`, `callees`) per eval turn."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.ctx :as ctx]
            [seon.ctx.project :as proj]
            [seon.render.data :as rdata]
            [seon.render.fit :as fit]
            [seon.render.wire :as rwire]
            [seon.schema :as schema]
            [seon.schema.index :as six]))

(def ^:private ignored-nss
  #{"clojure.core" "clojure.string" "clojure.set" "clojure.edn" "clojure.java.io" "clojure.test"
    "datahike.api" "d" "str" "set" "io" "edn" "user" "result" "Math" "System" "Double" "Long" "String"})

(def ^:private ignored-kws
  #{:db/id :db/add :db/retract :db/retractEntity :db/ident :db/valueType :db/cardinality :db/unique :db/index})

(def ^:private lookup-op-re
  #"\(\s*(?:(?:user|seon\.fork\.helpers|seon\.repl|datahike\.api|d)/)?(source|grep-src|ns-outline|search|pull|callers|callees)\b")

(def ref-attrs
  "Canonical identity attributes resolved between EIDs and lookup refs."
  [:seon.code.var/name :seon.code.ns/name :seon.schema/key :seon.task/id :seon.code.doc/id])

(defn db-schema
  "Returns the Datahike schema map for `db` (or `{}`)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] :map]
   :cost/ticks 50000}
  [db]
  (or (:schema db) (when db (d/schema db)) {}))

(defn nonneg-long
  "Coerces finite non-negative numbers to `long`, defaulting to `0`."
  {:malli/schema [:=> [:cat :any] [:int {:min 0}]]
   :cost/ticks 5000}
  [x]
  (cond
    (integer? x) (max 0 (long x))
    (number? x) (let [v (double x)] (if (and (== v v) (>= v 0.0) (<= v 9.0e18)) (long v) 0))
    :else 0))

(defn- balanced-form [^String s start-idx]
  (let [n (min (count s) (+ start-idx 1200))]
    (loop [i (long start-idx) depth 0 in-str? false esc? false]
      (if (>= i n)
        (subs s start-idx n)
        (let [ch (.charAt s i)]
          (cond
            in-str? (recur (inc i) depth (or esc? (not= ch \")) (and (not esc?) (= ch \\)))
            (= ch \") (recur (inc i) depth true false)
            (= ch \() (recur (inc i) (inc depth) false false)
            (= ch \)) (if (<= depth 1) (subs s start-idx (inc i)) (recur (inc i) (dec depth) false false))
            :else (recur (inc i) depth false false)))))))

(defn- parse-sym [^String tok]
  (when (and (string? tok) (< 0 (count tok) 128))
    (let [[a b] (str/split tok #"/" 2)]
      (cond
        (and b (not (str/blank? a)) (not (str/blank? b)) (not (ignored-nss a))) (symbol a b)
        (and (nil? b) (str/includes? tok ".") (not (str/starts-with? tok "."))) (symbol tok)))))

(defn- parse-kw [^String tok]
  (when (and (string? tok) (str/starts-with? tok ":") (str/includes? tok "/"))
    (let [[a b] (str/split (subs tok 1) #"/" 2)]
      (when (and (not (str/blank? a)) (not (str/blank? b)))
        (let [kw (keyword a b)] (when-not (ignored-kws kw) kw))))))

(defn- snippet-refs [^String s op]
  (let [qsyms (keep #(parse-sym (or (nth % 1) (nth % 2)))
                    (re-seq #"(?:'([a-zA-Z][\w.-]*(?:/[\w.*?!+-]+)?)|\(quote\s+([a-zA-Z][\w.-]*(?:/[\w.*?!+-]+)?)\))" s))
        usyms (when-not op
                (keep #(parse-sym (second %)) (re-seq #"(?:^|[^a-zA-Z0-9._:/'-])([a-zA-Z][\w.-]*/[\w.*?!+-]+)" s)))
        kws (keep #(parse-kw (second %)) (re-seq #"(?:^|[^a-zA-Z0-9._:-])(:[a-zA-Z][\w.-]*/[\w.*?!-]+)" s))
        lrefs (into #{}
                    (keep (fn [[_ a v]]
                            (let [ak (parse-kw a)
                                  pv (cond
                                       (str/starts-with? v ":") (if (str/includes? v "/") (parse-kw v) (keyword (subs v 1)))
                                       (str/starts-with? v "'") (parse-sym (subs v 1))
                                       (and (str/starts-with? v "\"") (str/ends-with? v "\"") (> (count v) 2))
                                       (subs v 1 (dec (count v))))]
                              (when (and ak (some? pv)) [ak pv]))))
                    (re-seq #"\[\s*(:[a-zA-Z][\w.-]*/[\w.*?!-]+)\s+([^\s\]\)]+)\s*\]" s))
        strs (map second (re-seq #"\"([^\"\\]{2,140})\"" s))
        drefs (into #{} (keep #(when (and (or (str/starts-with? % "docs/") (str/starts-with? % "AGENTS.md"))
                                          (str/includes? % "#"))
                                 [:seon.code.doc/id %]))
                    strs)
        peids (if (= op :pull)
                (into #{} (keep #(let [n (Long/parseLong (second %))] (when (pos? n) n)))
                      (re-seq #"\s(\d{2,19})\s*\)" s))
                #{})
        qs (if (#{:grep-src :search} op)
             (into #{} (remove #(or (str/starts-with? % "docs/") (str/blank? %))) strs)
             #{})]
    {:syms (into #{} (concat qsyms usyms))
     :schemas (if (= op :pull) (into #{} (keep #(when (= :seon.schema/key (first %)) (second %))) lrefs) (set kws))
     :refs (set/union lrefs drefs) :eids peids :queries qs}))

(defn- manual-lookups [^String code-str]
  (if (or (not (string? code-str)) (str/blank? code-str))
    {:calls [] :syms #{} :schemas #{} :refs #{} :eids #{} :queries #{}}
    (let [m (re-matcher lookup-op-re code-str)]
      (loop [acc {:calls [] :syms #{} :schemas #{} :refs #{} :eids #{} :queries #{}}]
        (if-not (.find m)
          acc
          (let [op (keyword (.group m 1))
                r (snippet-refs (balanced-form code-str (.start m)) op)
                targets (vec (concat (sort-by str (:syms r)) (sort-by str (:schemas r))
                                     (sort-by pr-str (:refs r)) (sort (:eids r)) (sort (:queries r))))]
            (recur (-> acc
                       (update :calls conj {:op op :targets targets})
                       (update :syms into (:syms r)) (update :schemas into (:schemas r))
                       (update :refs into (:refs r)) (update :eids into (:eids r))
                       (update :queries into (:queries r))))))))))

(defn- resolve-db-ids [db {:keys [eids syms schemas refs queries]}]
  (let [dh (db-schema db)]
    (if-not (and db (seq dh))
      {:eids eids :syms syms :schemas schemas :refs refs}
      (let [avet-e (fn [a v] (when (dh a) (:e (first (d/datoms db :avet a v)))))
            ql (vec (keep #(when (and (string? %) (>= (count %) 4)) (str/lower-case %)) queries))
            qhits (when (and (seq ql) (dh :seon.code.var/name))
                    (for [dt (d/datoms db :aevt :seon.code.var/name)
                          :let [vs (:v dt) sl (when (symbol? vs) (str/lower-case (str vs)))]
                          :when (and sl (some #(str/includes? sl %) ql))]
                      [(:e dt) vs]))
            eids1 (into (set eids)
                        (concat (keep #(when (symbol? %) (avet-e (if (namespace %) :seon.code.var/name :seon.code.ns/name) %)) syms)
                                (keep #(when (qualified-keyword? %) (avet-e :seon.schema/key %)) schemas)
                                (keep (fn [[a v]] (when (qualified-keyword? a) (avet-e a v))) refs)
                                (map first qhits)))
            pulled (keep #(when (integer? %) (d/pull db ref-attrs %)) eids1)]
        {:eids eids1
         :syms (into (into (set syms) (map second qhits))
                     (mapcat #(remove nil? [(:seon.code.var/name %) (:seon.code.ns/name %)]))
                     pulled)
         :schemas (into (set schemas) (keep :seon.schema/key) pulled)
         :refs (into (set refs) (for [p pulled, a ref-attrs, :let [v (get p a)] :when (some? v)] [a v]))}))))

(defn- extract-one-eval [db ev]
  (let [code-s (when (string? (:seon.agent.eval/code ev)) (:seon.agent.eval/code ev))
        all-r (if code-s (snippet-refs code-s nil) {:syms #{} :schemas #{} :refs #{} :eids #{}})
        man-r (manual-lookups code-s)
        traw (let [t (:seon.agent.eval/touched ev)] (if (coll? t) t []))
        trefs (into #{} (filter #(and (vector? %) (= 2 (count %)) (qualified-keyword? (first %)))) traw)
        rsraw (let [rs (:seon.agent.eval/result-schemas ev)] (if (coll? rs) rs []))
        man-res (resolve-db-ids db man-r)
        all-res (resolve-db-ids
                 db
                 {:eids (set/union (:eids all-r) (:eids man-res)
                                   (into #{} (filter integer?) traw)
                                   (into #{} (keep #(when (integer? (second %)) (second %))) trefs)
                                   (into #{} (keep #(if (integer? %) % (when (and (map? %) (integer? (:db/id %))) (:db/id %)))) rsraw))
                  :syms (set/union (:syms all-r) (:syms man-res)
                                   (into #{} (keep (fn [[a v]] (when (and (#{:seon.code.var/name :seon.code.ns/name} a) (symbol? v)) v))) trefs))
                  :schemas (set/union (:schemas all-r) (:schemas man-res)
                                      (into #{} (keep (fn [[a v]] (when (and (= :seon.schema/key a) (qualified-keyword? v)) v))) trefs)
                                      (into (if (qualified-keyword? (:seon.agent.eval/result-schema ev)) #{(:seon.agent.eval/result-schema ev)} #{})
                                            (filter qualified-keyword?) rsraw))
                  :refs (set/union (:refs all-r) trefs (:refs man-res))
                  :queries (:queries man-r)})
        turn (nonneg-long (:seon.agent.eval/turn ev))
        handle (:seon.agent.eval/handle ev)]
    {:turn turn :handle handle :tx (nonneg-long (or (:_tx ev) (:tx ev) (:seon.agent.eval/tx ev)))
     :touched-eids (:eids all-res) :touched-syms (:syms all-res)
     :touched-schemas (:schemas all-res) :touched-refs (:refs all-res)
     :manual-lookup-eids (:eids man-res) :manual-lookup-syms (:syms man-res)
     :manual-lookup-schemas (:schemas man-res) :manual-lookup-refs (:refs man-res)
     :manual-lookup-queries (:queries man-r)
     :manual-lookup-calls (mapv #(assoc % :turn turn :handle handle) (:calls man-r))}))

(defn pull-agent-evals
  "Pulls an agent's `:seon.agent.eval/*` records with native datom `:_tx`, sorted chronologically."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :qualified-keyword] [:vector :map]]
   :cost/ticks 10000000}
  [db agent-id]
  (if-not (and db (qualified-keyword? agent-id) ((db-schema db) :seon.agent.eval/agent))
    []
    (->> (d/datoms db :avet :seon.agent.eval/agent agent-id)
         (map #(assoc (d/pull db '[:seon.agent.eval/id :seon.agent.eval/turn :seon.agent.eval/handle
                                   :seon.agent.eval/code :seon.agent.eval/touched
                                   :seon.agent.eval/result-schema :seon.agent.eval/result-schemas]
                              (:e %))
                      :_tx (long (:tx % 0))))
         (sort-by (juxt :_tx #(nonneg-long (:seon.agent.eval/turn %))))
         vec)))

(defn extract-eval-touches
  "Extracts touched EIDs, symbols, schemas, and refs per eval turn, separating general usage
   from manual lookups (`source`, `grep-src`, `d/pull`, `ns-outline`, `search`, `callers`, `callees`)."
  {:malli/schema [:function
                  [:=> [:cat :any] :map]
                  [:=> [:cat [:maybe :seon.host/db] :any] :map]
                  [:=> [:cat [:maybe :seon.host/db] :any [:maybe :map]] :map]]
   :cost/ticks 25000000}
  ([evals] (extract-eval-touches nil evals nil))
  ([db evals-or-agent] (extract-eval-touches db evals-or-agent nil))
  ([db evals-or-agent opts]
   (let [o (if (map? opts) opts {})
         raw (cond (and db (qualified-keyword? evals-or-agent)) (pull-agent-evals db evals-or-agent)
                   (sequential? evals-or-agent) (filterv map? evals-or-agent)
                   (map? evals-or-agent) [evals-or-agent]
                   :else [])
         ev-tx #(nonneg-long (or (:_tx %) (:tx %) (:seon.agent.eval/tx %)))
         filtered (vec (cond->> raw
                         (integer? (:after-tx o)) (filter #(> (ev-tx %) (long (:after-tx o))))
                         (integer? (:upto-tx o)) (filter #(<= (ev-tx %) (long (:upto-tx o))))
                         (integer? (:after-turn o)) (filter #(> (nonneg-long (:seon.agent.eval/turn %)) (long (:after-turn o))))))
         per-turn (mapv #(extract-one-eval db %) filtered)
         ks [:touched-eids :touched-syms :touched-schemas :touched-refs :manual-lookup-eids
             :manual-lookup-syms :manual-lookup-schemas :manual-lookup-refs :manual-lookup-queries]]
     (reduce (fn [acc t]
               (-> (reduce #(update %1 %2 into (get t %2)) acc ks)
                   (update :manual-lookup-calls into (:manual-lookup-calls t))))
             (assoc (zipmap ks (repeat #{})) :manual-lookup-calls [] :by-turn per-turn :eval-count (count per-turn))
             per-turn))))

(defn- scope-neighbors [db ag]
  (let [dh (db-schema db) sc (:seon.agent/scope ag)]
    (if-not (and sc (symbol? sc) (dh :seon.code.var/ns))
      []
      (let [p (str (name sc) ".")
            ok? #(let [s (str (:v %))] (or (= s (name sc)) (str/starts-with? s p)))
            mk (fn [a sk pri lim]
                 (when (dh a)
                   (into [] (comp (filter ok?) (take lim)
                                  (map #(cond-> {:entity (:e %) :schema sk :rules [:rule.scope/neighbor]
                                                 :priority pri :zone :workspace}
                                          (= a :seon.code.ns/name) (assoc :sym (:v %)))))
                         (d/datoms db :aevt a))))]
        (vec (concat (mk :seon.code.ns/name :seon.code.ns/entity 58 6)
                     (mk :seon.code.var/ns :seon.code.var/entity 55 20)))))))

(defn- enrich-candidate [main-db edb ix prior it]
  (when-let [eid (when (integer? (:entity it)) (:entity it))]
    (let [b (d/pull edb '[*] eid)
          ent (if (seq (dissoc b :db/id)) b (d/pull main-db '[*] eid))]
      (when (and (map? ent) (seq (dissoc ent :db/id)) (not (:seon.agent.eval/id ent)))
        (let [sk (or (when (qualified-keyword? (:schema it)) (:schema it))
                     (let [ms (six/most-specific ix (dissoc ent :db/id))] (when (qualified-keyword? ms) ms))
                     (:schema (first (six/facets-of ix (dissoc ent :db/id)))))
              sym (or (:sym it) (:seon.code.var/name ent) (:seon.code.ns/name ent))
              etx (max (long (or (:tx it) 0))
                       (reduce max 0 (map #(long (or (:tx %) 0)) (d/datoms edb :eavt eid)))
                       (long (get-in prior [:max-tx eid] 0)))]
          (cond-> (assoc it :entity eid :data ent :tx etx :touches (long (get-in prior [:counts eid] 0)))
            sk (assoc :schema sk) sym (assoc :sym sym)))))))

(defn build-candidate-pool
  "Builds the bounded candidate entity pool for `agent-id` at `(main-db, branch-db)` with prior
   eval touch counts/txs and pre-measured multi-level Hiccup render candidates."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword] [:vector :map]]
   :cost/ticks 40000000}
  [main-db branch-db agent-id]
  (let [edb (or branch-db main-db)
        ag (or (rdata/pull-agent-entity main-db edb agent-id) {:seon.agent/id agent-id :seon.agent/role :worker})
        ru-ps (:seon.ctx/projections (ctx/related-updates {:seon.db/main main-db :seon.db/branch edb :seon.agent/id agent-id
                                                           :seon.view/target :wire :seon.view/token-budget 100000}))
        world (proj/agent-world-datoms main-db edb agent-id)
        seen (into #{} (keep :entity) ru-ps)
        pins (:pinned world)
        w-extra (into [] (comp (remove #(or (= (key %) (:agent-eid world)) (seen (key %)))) (take 12)
                               (map (fn [[e tx]] {:entity e :tx tx :priority (if (pins e) 88 60)
                                                  :pinned? (boolean (pins e)) :zone :workspace :rules [:seed/world]})))
                      (sort-by (comp - val) (:connected world)))
        prior (reduce (fn [acc t]
                        (reduce (fn [a e] (-> a (update-in [:counts e] (fnil inc 0))
                                              (update-in [:max-tx e] #(max (long (or % 0)) (long (:tx t 0))))))
                                acc (:touched-eids t)))
                      {:counts {} :max-tx {}} (:by-turn (extract-eval-touches edb (pull-agent-evals edb agent-id) nil)))
        seen2 (into seen (map :entity) w-extra)
        p-extra (into [] (comp (remove #(or (= (key %) (:agent-eid world)) (seen2 (key %)))) (take 14)
                               (map (fn [[e tx]] {:entity e :tx tx :priority 66 :zone :workspace :rules [:rule.ctx/eval-frontier]})))
                      (sort-by (comp - val) (:max-tx prior)))
        seen3 (into seen2 (map :entity) p-extra)
        raw (concat (map #(dissoc % :candidates :data) ru-ps) w-extra p-extra
                    (remove #(seen3 (:entity %)) (scope-neighbors edb ag)))
        enriched (vec (keep #(enrich-candidate main-db edb (schema/entity-index edb) prior %) raw))
        by-e (into {} (map (juxt :entity identity))
                   (:selected (fit/fit-projections edb enriched {:target :wire :token-budget 1000000
                                                                 :fallback-renderers rwire/fallback-entity-renderers})))]
    (mapv #(if-let [m (get by-e (:entity %))]
             (assoc % :candidates (or (:candidates m) []) :tokens (long (or (:measured-tokens m) (:tokens m) 0)))
             %)
          enriched)))
