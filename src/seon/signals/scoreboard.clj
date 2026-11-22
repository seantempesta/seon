(ns seon.signals.scoreboard
  "Empirical Context Precision/Recall Scoreboard & reproducible `d/as-of` Hyperparameter Lab.
   Extracts entity/var/schema touches and manual lookup calls (`source`, `grep-src`, `d/pull`,
   `ns-outline`, `search`, `:seon.agent.eval/touched`, `:seon.agent.eval/result-schemas`) per
   eval turn, scores shown blocks vs candidate pool for Precision, Token Precision, Recall,
   Recall Gap, and per-rule/per-renderer rollups, and compares ranking/ladder profiles via `d/as-of`."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.ctx :as ctx]
            [seon.ctx.project :as proj]
            [seon.render.data :as rdata]
            [seon.render.fit :as fit]
            [seon.render.wire :as rwire]
            [seon.schema :as schema]
            [seon.schema.index :as six]
            [seon.store :as store]))

(def default-hyperparam-profiles
  "Default hyperparameter profiles compared by `compare-as-of-hyperparams`."
  [{:label :default
    :weights {:urgency 0.50 :recency 0.30 :touches 0.20}
    :strategy :breadth-first
    :token-budget 2400}
   {:label :recency-heavy
    :weights {:urgency 0.20 :recency 0.65 :touches 0.15}
    :strategy :breadth-first
    :token-budget 2400}
   {:label :urgency-heavy
    :weights {:urgency 0.75 :recency 0.15 :touches 0.10}
    :strategy :breadth-first
    :token-budget 2400}
   {:label :depth-first
    :weights {:urgency 0.50 :recency 0.30 :touches 0.20}
    :strategy :depth-first
    :token-budget 2400}])

(def ^:private ignored-code-nss
  #{"clojure.core" "clojure.string" "clojure.set" "clojure.edn" "clojure.walk"
    "clojure.java.io" "clojure.test" "datahike.api" "d" "str" "set" "io" "edn"
    "user" "result" "Math" "System" "Double" "Long" "String" "Integer" "java.lang"})

(def ^:private ignored-schema-kws
  #{:db/id :db/add :db/retract :db/retractEntity :db/ident :db/valueType
    :db/cardinality :db/unique :db/index :db/doc :db/txInstant})

(def ^:private lookup-op-re
  #"\(\s*(?:(?:user|seon\.fork\.helpers|seon\.repl|datahike\.api|d)/)?(source|grep-src|ns-outline|search|pull|callers|callees)\b")

(defn- db-schema [db]
  (or (:schema db) (when db (d/schema db)) {}))

(defn- round4 [x]
  (if (number? x)
    (let [v (double x)]
      (if (and (== v v) (not (Double/isInfinite v)))
        (/ (double (Math/round (* v 10000.0))) 10000.0)
        0.0))
    0.0))

(defn- round1 [x]
  (if (number? x)
    (let [v (double x)]
      (if (and (== v v) (not (Double/isInfinite v)))
        (/ (double (Math/round (* v 10.0))) 10.0)
        0.0))
    0.0))

(defn- clamp01 [x]
  (max 0.0 (min 1.0 (round4 x))))

(defn- to-set [x]
  (cond (set? x) x (coll? x) (set x) (some? x) #{x} :else #{}))

(defn- safe-nonneg-long [x]
  (cond
    (integer? x) (max 0 (long x))
    (number? x) (let [v (double x)]
                  (if (and (== v v) (not (Double/isInfinite v)) (>= v 0.0) (<= v 9.0e18))
                    (long v)
                    0))
    :else 0))

(defn- safe-as-of
  {:seon.flow/edge true}
  [db tx]
  (if (and db (integer? tx) (not (instance? datahike.db.AsOfDB db)))
    (try (d/as-of db (long tx)) (catch Throwable _ db))
    db))

(defn- normalize-profile-weights [w]
  (if-not (map? w)
    nil
    (let [conv (or (:convergence w) (:touches w))]
      (cond-> (select-keys w [:urgency :recency :convergence])
        (number? conv) (assoc :convergence (double conv))))))

;; Pure code & eval touch extraction

(defn- extract-balanced-form [^String s start-idx]
  (let [n (min (count s) (+ start-idx 1400))]
    (loop [i (long start-idx) depth 0 in-str? false esc? false]
      (if (>= i n)
        (subs s start-idx n)
        (let [ch (.charAt s i)]
          (cond
            in-str?
            (cond
              esc? (recur (inc i) depth true false)
              (= ch \\) (recur (inc i) depth true true)
              (= ch \") (recur (inc i) depth false false)
              :else (recur (inc i) depth true false))
            (= ch \") (recur (inc i) depth true false)
            (= ch \() (recur (inc i) (inc depth) false false)
            (= ch \)) (if (<= depth 1)
                        (subs s start-idx (inc i))
                        (recur (inc i) (dec depth) false false))
            :else (recur (inc i) depth false false)))))))

(defn- parse-symbol-token [^String tok]
  (when (and (string? tok) (not (str/blank? tok)) (< (count tok) 128))
    (let [parts (str/split tok #"/" 2)]
      (if (= 2 (count parts))
        (let [[ns-s nm-s] parts]
          (when (and (not (str/blank? ns-s)) (not (str/blank? nm-s))
                     (not (contains? ignored-code-nss ns-s)))
            (symbol ns-s nm-s)))
        (when (and (str/includes? tok ".") (not (str/starts-with? tok ".")))
          (symbol tok))))))

(defn- parse-kw-token [^String tok]
  (when (and (string? tok) (str/starts-with? tok ":") (str/includes? tok "/"))
    (let [body (subs tok 1)
          [ns-s nm-s] (str/split body #"/" 2)]
      (when (and (not (str/blank? ns-s)) (not (str/blank? nm-s)))
        (let [kw (keyword ns-s nm-s)]
          (when-not (contains? ignored-schema-kws kw) kw))))))

(defn- extract-lookup-refs-from-code [^String s]
  (let [kw-kw (for [[_ a v] (re-seq #"\[\s*(:[a-zA-Z][a-zA-Z0-9._-]*/[a-zA-Z0-9._*?!-]+)\s+(:[a-zA-Z0-9._-]+(?:/[a-zA-Z0-9._*?!-]+)?)\s*\]" s)
                    :let [ak (parse-kw-token a)
                          vk (if (str/includes? v "/") (parse-kw-token v) (keyword (subs v 1)))]
                    :when (and ak vk)]
                [ak vk])
        kw-sym (for [[_ a v] (re-seq #"\[\s*(:[a-zA-Z][a-zA-Z0-9._-]*/[a-zA-Z0-9._*?!-]+)\s+(?:'|\(quote\s+)([a-zA-Z][a-zA-Z0-9._-]*(?:/[a-zA-Z0-9._*?!+-]+)?)\)?\s*\]" s)
                     :let [ak (parse-kw-token a) sv (parse-symbol-token v)]
                     :when (and ak sv)]
                 [ak sv])
        kw-str (for [[_ a v] (re-seq #"\[\s*(:[a-zA-Z][a-zA-Z0-9._-]*/[a-zA-Z0-9._*?!-]+)\s+\"([^\"]{1,160})\"\s*\]" s)
                     :let [ak (parse-kw-token a)]
                     :when ak]
                 [ak v])]
    (into #{} (concat kw-kw kw-sym kw-str))))

(defn- extract-refs-in-snippet [^String s op]
  (let [quoted-syms (keep (fn [[_ a b]] (parse-symbol-token (or a b)))
                          (re-seq #"(?:'([a-zA-Z][a-zA-Z0-9._-]*(?:/[a-zA-Z0-9._*?!+-]+)?)|\(quote\s+([a-zA-Z][a-zA-Z0-9._-]*(?:/[a-zA-Z0-9._*?!+-]+)?)\))" s))
        qual-syms (when (nil? op)
                    (keep (fn [[_ tok]] (parse-symbol-token tok))
                          (re-seq #"(?<![a-zA-Z0-9._:/'-])([a-zA-Z][a-zA-Z0-9._-]*/[a-zA-Z0-9._*?!+-]+)" s)))
        kws (keep (fn [[_ tok]] (parse-kw-token tok))
                  (re-seq #"(?<![a-zA-Z0-9._:-])(:[a-zA-Z][a-zA-Z0-9._-]*/[a-zA-Z0-9._*?!-]+)" s))
        lrefs (extract-lookup-refs-from-code s)
        strs (map second (re-seq #"\"([^\"\\]{2,140})\"" s))
        doc-refs (into #{} (keep #(when (and (or (str/starts-with? % "docs/") (str/starts-with? % "AGENTS.md"))
                                             (str/includes? % "#"))
                                    [:seon.code.doc/id %]))
                       strs)
        pull-eids (if (= op :pull)
                    (into #{} (keep (fn [[_ ds]]
                                      (let [n (Long/parseLong ds)]
                                        (when (pos? n) n))))
                          (re-seq #"\s(\d{2,19})\s*\)" s))
                    #{})
        queries (if (contains? #{:grep-src :search} op)
                  (into #{} (remove #(or (str/starts-with? % "docs/") (str/blank? %))) strs)
                  #{})]
    {:syms (into #{} (concat quoted-syms qual-syms))
     :schemas (if (= op :pull)
                (into #{} (keep (fn [[a v]] (when (= :seon.schema/key a) v))) lrefs)
                (set kws))
     :refs (set/union lrefs doc-refs)
     :eids pull-eids
     :queries queries}))

(defn- extract-manual-lookups [^String code-str]
  (if (or (not (string? code-str)) (str/blank? code-str))
    {:calls [] :syms #{} :schemas #{} :refs #{} :eids #{} :queries #{}}
    (let [m (re-matcher lookup-op-re code-str)]
      (loop [acc {:calls [] :syms #{} :schemas #{} :refs #{} :eids #{} :queries #{}}]
        (if (.find m)
          (let [op (keyword (.group m 1))
                form-s (extract-balanced-form code-str (.start m))
                r (extract-refs-in-snippet form-s op)
                targets (vec (concat (sort-by str (:syms r))
                                     (sort-by str (:schemas r))
                                     (sort-by pr-str (:refs r))
                                     (sort (:eids r))
                                     (sort (:queries r))))]
            (recur (-> acc
                       (update :calls conj {:op op :targets targets})
                       (update :syms into (:syms r))
                       (update :schemas into (:schemas r))
                       (update :refs into (:refs r))
                       (update :eids into (:eids r))
                       (update :queries into (:queries r)))))
          acc)))))

(defn- resolve-db-identities [db {:keys [eids syms schemas refs queries]}]
  (if-not (and db (map? (db-schema db)) (seq (db-schema db)))
    {:eids eids :syms syms :schemas schemas :refs refs}
    (let [dh (db-schema db)
          eids-v (volatile! (set eids))
          syms-v (volatile! (set syms))
          schs-v (volatile! (set schemas))
          refs-v (volatile! (set refs))
          avet-e (fn [a v] (when (contains? dh a) (:e (first (d/datoms db :avet a v)))))]
      (doseq [s syms]
        (when-let [e (if (and (symbol? s) (namespace s))
                       (avet-e :seon.code.var/name s)
                       (when (symbol? s) (avet-e :seon.code.ns/name s)))]
          (vswap! eids-v conj e)))
      (doseq [sk schemas]
        (when-let [e (when (qualified-keyword? sk) (avet-e :seon.schema/key sk))]
          (vswap! eids-v conj e)))
      (doseq [[a v] refs]
        (when (and (qualified-keyword? a) (contains? dh a))
          (when-let [e (avet-e a v)]
            (vswap! eids-v conj e))))
      (doseq [q queries
              :when (and (string? q) (>= (count q) 4))]
        (when (contains? dh :seon.code.var/name)
          (let [ql (str/lower-case q)]
            (doseq [dt (d/datoms db :aevt :seon.code.var/name)
                    :let [vs (:v dt)]
                    :when (and (symbol? vs) (str/includes? (str/lower-case (str vs)) ql))]
              (vswap! eids-v conj (:e dt))
              (vswap! syms-v conj vs)))))
      (doseq [e @eids-v
              :when (integer? e)]
        (let [p (d/pull db '[:seon.code.var/name :seon.code.ns/name :seon.schema/key
                             :seon.task/id :seon.code.doc/id] e)]
          (when-let [vn (:seon.code.var/name p)]
            (vswap! syms-v conj vn) (vswap! refs-v conj [:seon.code.var/name vn]))
          (when-let [nn (:seon.code.ns/name p)]
            (vswap! syms-v conj nn) (vswap! refs-v conj [:seon.code.ns/name nn]))
          (when-let [sk (:seon.schema/key p)]
            (vswap! schs-v conj sk) (vswap! refs-v conj [:seon.schema/key sk]))
          (when-let [tid (:seon.task/id p)]
            (vswap! refs-v conj [:seon.task/id tid]))
          (when-let [did (:seon.code.doc/id p)]
            (vswap! refs-v conj [:seon.code.doc/id did]))))
      {:eids @eids-v :syms @syms-v :schemas @schs-v :refs @refs-v})))

(defn- extract-one-eval [db ev]
  (let [code-s (when (string? (:seon.agent.eval/code ev)) (:seon.agent.eval/code ev))
        all-r (if code-s (extract-refs-in-snippet code-s nil) {:syms #{} :schemas #{} :refs #{} :eids #{}})
        man-r (extract-manual-lookups code-s)
        touched-raw (let [t (:seon.agent.eval/touched ev)] (if (coll? t) t []))
        t-eids (into #{} (filter integer?) touched-raw)
        t-refs (into #{} (filter #(and (vector? %) (= 2 (count %)) (qualified-keyword? (first %)))) touched-raw)
        t-ref-eids (into #{} (keep (fn [[_ v]] (when (integer? v) v))) t-refs)
        t-ref-schs (into #{} (keep (fn [[a v]] (when (and (= :seon.schema/key a) (qualified-keyword? v)) v))) t-refs)
        t-ref-syms (into #{} (keep (fn [[a v]] (when (and (contains? #{:seon.code.var/name :seon.code.ns/name} a) (symbol? v)) v))) t-refs)
        rs-raw (let [rs (:seon.agent.eval/result-schemas ev)] (if (coll? rs) rs []))
        rs-eids (into #{} (keep #(cond (integer? %) % (and (map? %) (integer? (:db/id %))) (:db/id %) :else nil)) rs-raw)
        rs-kws (into (if (qualified-keyword? (:seon.agent.eval/result-schema ev)) #{(:seon.agent.eval/result-schema ev)} #{})
                     (filter qualified-keyword?) rs-raw)
        man-res (resolve-db-identities db man-r)
        all-res (resolve-db-identities db {:eids (set/union (:eids all-r) t-eids t-ref-eids rs-eids (:eids man-res))
                                           :syms (set/union (:syms all-r) t-ref-syms (:syms man-res))
                                           :schemas (set/union (:schemas all-r) t-ref-schs rs-kws (:schemas man-res))
                                           :refs (set/union (:refs all-r) t-refs (:refs man-res))
                                           :queries (:queries man-r)})
        turn (safe-nonneg-long (:seon.agent.eval/turn ev))
        handle (:seon.agent.eval/handle ev)
        tx (safe-nonneg-long (or (:_tx ev) (:tx ev) (:seon.agent.eval/tx ev)))]
    {:turn turn :handle handle :tx tx
     :touched-eids (:eids all-res)
     :touched-syms (:syms all-res)
     :touched-schemas (:schemas all-res)
     :touched-refs (:refs all-res)
     :manual-lookup-eids (:eids man-res)
     :manual-lookup-syms (:syms man-res)
     :manual-lookup-schemas (:schemas man-res)
     :manual-lookup-refs (:refs man-res)
     :manual-lookup-queries (:queries man-r)
     :manual-lookup-calls (mapv #(assoc % :turn turn :handle handle) (:calls man-r))}))

(defn- pull-agent-evals-with-tx [db agent-id]
  (if-not (and db (qualified-keyword? agent-id) (contains? (db-schema db) :seon.agent.eval/agent))
    []
    (->> (d/datoms db :avet :seon.agent.eval/agent agent-id)
         (map (fn [dt]
                (let [p (d/pull db '[:seon.agent.eval/id :seon.agent.eval/turn :seon.agent.eval/handle
                                     :seon.agent.eval/code :seon.agent.eval/touched
                                     :seon.agent.eval/result-schema :seon.agent.eval/result-schemas]
                                (:e dt))]
                  (assoc p :_tx (long (:tx dt 0))))))
         (sort-by (juxt :_tx #(safe-nonneg-long (:seon.agent.eval/turn %))))
         vec)))

(defn extract-eval-touches
  "Pure function over `evals` (or `(db agent-id [opts])`) extracting touched entity IDs, var/ns
   symbols, schema keys, and lookup refs per eval turn, separating general usage from manual lookup
   calls (`source`, `grep-src`, `d/pull`, `ns-outline`, `search`, `callers`, `callees`)."
  {:malli/schema [:function
                  [:=> [:cat :any] :map]
                  [:=> [:cat [:maybe :seon.host/db] :any] :map]
                  [:=> [:cat [:maybe :seon.host/db] :any [:maybe :map]] :map]]
   :cost/ticks 25000000}
  ([evals] (extract-eval-touches nil evals nil))
  ([db evals-or-agent] (extract-eval-touches db evals-or-agent nil))
  ([db evals-or-agent opts]
   (let [o (if (map? opts) opts {})
         raw (cond
               (and db (qualified-keyword? evals-or-agent)) (pull-agent-evals-with-tx db evals-or-agent)
               (sequential? evals-or-agent) (filterv map? evals-or-agent)
               (map? evals-or-agent) [evals-or-agent]
               :else [])
         after-tx (when (integer? (:after-tx o)) (long (:after-tx o)))
         upto-tx (when (integer? (:upto-tx o)) (long (:upto-tx o)))
         after-turn (when (integer? (:after-turn o)) (long (:after-turn o)))
         filtered (cond->> raw
                    after-tx (filter #(> (safe-nonneg-long (or (:_tx %) (:tx %) (:seon.agent.eval/tx %))) after-tx))
                    upto-tx (filter #(<= (safe-nonneg-long (or (:_tx %) (:tx %) (:seon.agent.eval/tx %))) upto-tx))
                    after-turn (filter #(> (safe-nonneg-long (:seon.agent.eval/turn %)) after-turn))
                    true vec)
         per-turn (mapv #(extract-one-eval db %) filtered)]
     (reduce (fn [acc t]
               (-> acc
                   (update :touched-eids into (:touched-eids t))
                   (update :touched-syms into (:touched-syms t))
                   (update :touched-schemas into (:touched-schemas t))
                   (update :touched-refs into (:touched-refs t))
                   (update :manual-lookup-eids into (:manual-lookup-eids t))
                   (update :manual-lookup-syms into (:manual-lookup-syms t))
                   (update :manual-lookup-schemas into (:manual-lookup-schemas t))
                   (update :manual-lookup-refs into (:manual-lookup-refs t))
                   (update :manual-lookup-queries into (:manual-lookup-queries t))
                   (update :manual-lookup-calls into (:manual-lookup-calls t))))
             {:touched-eids #{}
              :touched-syms #{}
              :touched-schemas #{}
              :touched-refs #{}
              :manual-lookup-eids #{}
              :manual-lookup-syms #{}
              :manual-lookup-schemas #{}
              :manual-lookup-refs #{}
              :manual-lookup-queries #{}
              :manual-lookup-calls []
              :by-turn per-turn
              :eval-count (count per-turn)}
             per-turn))))

;; Pure Precision / Token-Precision / Recall / Recall-Gap Scoring

(defn- item-identities [item]
  (let [data (if (map? (:data item)) (:data item) {})
        eid (or (when (integer? (:entity item)) (:entity item))
                (when (integer? (:eid item)) (:eid item))
                (when (integer? (:db/id data)) (:db/id data)))
        eids (if eid #{eid} #{})
        sym-cands (remove nil? [(:sym item) (:seon.code.var/name data) (:seon.code.ns/name data)
                                (when (and (vector? (:ref item)) (= 2 (count (:ref item)))
                                           (contains? #{:seon.code.var/name :seon.code.ns/name} (first (:ref item))))
                                  (second (:ref item)))])
        syms (into #{} (filter symbol?) sym-cands)
        ns-syms (into #{} (keep #(when (and (symbol? %) (nil? (namespace %)) (str/includes? (name %) ".")) %)) syms)
        refs (cond-> #{}
               (and (vector? (:ref item)) (= 2 (count (:ref item)))) (conj (:ref item))
               (:seon.code.var/name data) (conj [:seon.code.var/name (:seon.code.var/name data)])
               (:seon.code.ns/name data) (conj [:seon.code.ns/name (:seon.code.ns/name data)])
               (:seon.schema/key data) (conj [:seon.schema/key (:seon.schema/key data)])
               (:seon.task/id data) (conj [:seon.task/id (:seon.task/id data)])
               (:seon.code.doc/id data) (conj [:seon.code.doc/id (:seon.code.doc/id data)]))
        sch-val (or (:seon.schema/key data)
                    (when (and (empty? syms) (empty? refs) (qualified-keyword? (:schema item)))
                      (:schema item)))]
    {:key (or eid (:entity item) (first syms) (first refs) (:id item) (:schema item))
     :eids eids
     :syms syms
     :ns-syms ns-syms
     :refs refs
     :schema-target (when (qualified-keyword? sch-val) sch-val)}))

(defn- item-matches-targets? [ident {:keys [eids syms ns-from-syms schemas refs]}]
  (boolean
   (or (some eids (:eids ident))
       (some syms (:syms ident))
       (some ns-from-syms (:ns-syms ident))
       (some refs (:refs ident))
       (and (:schema-target ident) (contains? schemas (:schema-target ident))))))

(defn- item-tokens [item]
  (safe-nonneg-long (or (:tokens item) (:measured-tokens item) 0)))

(defn- item-rules [item]
  (let [rs (cond
             (coll? (:rules item)) (:rules item)
             (some? (:rule item)) [(:rule item)]
             :else [])]
    (vec (distinct (remove nil? rs)))))

(defn- item-fn [item]
  (or (:chosen-fn item) (:fn item) :unknown))

(defn- finalize-rollup [acc]
  (reduce-kv
   (fn [m k {:keys [shown-count touched-count tokens touched-tokens]}]
     (assoc m k {:shown-count shown-count
                 :touched-count touched-count
                 :precision (if (pos? shown-count) (clamp01 (/ (double touched-count) (double shown-count))) 0.0)
                 :tokens tokens
                 :touched-tokens touched-tokens
                 :token-precision (if (pos? tokens) (clamp01 (/ (double touched-tokens) (double tokens))) 0.0)}))
   {}
   acc))

(defn score-shown-blocks
  "Pure empirical scorer computing Precision, Token Precision, Recall, Recall Gap (manual lookups
   dropped from `shown-blocks` despite being in `candidate-pool`), F1, and `:by-rule` / `:by-fn` rollups."
  {:malli/schema [:=> [:cat [:vector :map] [:vector :map] [:maybe :map]] :seon.signal/scoreboard]
   :cost/ticks 15000000}
  [shown-blocks candidate-pool later-touches]
  (let [lt (if (map? later-touches) later-touches {})
        t-syms (to-set (:touched-syms lt))
        t-targets {:eids (to-set (:touched-eids lt))
                   :syms t-syms
                   :ns-from-syms (into #{} (keep #(when (and (symbol? %) (namespace %)) (symbol (namespace %)))) t-syms)
                   :schemas (to-set (:touched-schemas lt))
                   :refs (to-set (:touched-refs lt))}
        m-syms (to-set (:manual-lookup-syms lt))
        m-targets {:eids (to-set (:manual-lookup-eids lt))
                   :syms m-syms
                   :ns-from-syms (into #{} (keep #(when (and (symbol? %) (namespace %)) (symbol (namespace %)))) m-syms)
                   :schemas (to-set (:manual-lookup-schemas lt))
                   :refs (to-set (:manual-lookup-refs lt))}
        shown-evals (mapv (fn [b]
                            (let [id (item-identities b)]
                              {:item b :id id :tokens (item-tokens b)
                               :touched? (item-matches-targets? id t-targets)}))
                          shown-blocks)
        shown-keys (into #{} (map (comp :key :id)) shown-evals)
        shown-n (count shown-evals)
        shown-touched-n (count (filter :touched? shown-evals))
        shown-toks (long (reduce + 0 (map :tokens shown-evals)))
        touched-toks (long (reduce + 0 (map #(if (:touched? %) (:tokens %) 0) shown-evals)))
        precision (if (pos? shown-n) (clamp01 (/ (double shown-touched-n) (double shown-n))) 0.0)
        tok-precision (if (pos? shown-toks) (clamp01 (/ (double touched-toks) (double shown-toks))) 0.0)
        ;; Pool unioned with shown so recall is always cleanly bounded in 0.0..1.0
        pool-by-key (reduce (fn [m it]
                              (let [id (item-identities it) k (:key id)]
                                (if (contains? m k) m (assoc m k {:item it :id id}))))
                            {} (concat candidate-pool shown-blocks))
        pool-entries (vals pool-by-key)
        pool-touched (filterv #(item-matches-targets? (:id %) t-targets) pool-entries)
        pool-touched-n (count pool-touched)
        surfaced-touched-n (count (filter #(contains? shown-keys (:key (:id %))) pool-touched))
        recall (if (pos? pool-touched-n) (clamp01 (/ (double surfaced-touched-n) (double pool-touched-n))) 0.0)
        f1 (if (pos? (+ precision recall))
             (clamp01 (/ (* 2.0 precision recall) (+ precision recall)))
             0.0)
        gap-items (vec (keep (fn [cand]
                               (let [id (item-identities cand)]
                                 (when (and (not (contains? shown-keys (:key id)))
                                            (item-matches-targets? id m-targets))
                                   (cond-> {:entity (:entity cand)
                                            :rules (item-rules cand)
                                            :tokens (item-tokens cand)
                                            :reason :dropped-from-shown}
                                     (first (:syms id)) (assoc :sym (first (:syms id)))
                                     (:schema cand) (assoc :schema (:schema cand))))))
                             candidate-pool))
        by-rule-raw (reduce (fn [acc {:keys [item tokens touched?]}]
                              (reduce (fn [a r]
                                        (-> a
                                            (update-in [r :shown-count] (fnil inc 0))
                                            (update-in [r :touched-count] (fnil + 0) (if touched? 1 0))
                                            (update-in [r :tokens] (fnil + 0) tokens)
                                            (update-in [r :touched-tokens] (fnil + 0) (if touched? tokens 0))))
                                      acc (or (seq (item-rules item)) [:unattributed])))
                            {} shown-evals)
        by-fn-raw (reduce (fn [acc {:keys [item tokens touched?]}]
                            (let [f (item-fn item)]
                              (-> acc
                                  (update-in [f :shown-count] (fnil inc 0))
                                  (update-in [f :touched-count] (fnil + 0) (if touched? 1 0))
                                  (update-in [f :tokens] (fnil + 0) tokens)
                                  (update-in [f :touched-tokens] (fnil + 0) (if touched? tokens 0)))))
                          {} shown-evals)
        gap-n (count gap-items)
        lvl (cond
              (or (>= gap-n 3) (and (pos? shown-n) (< precision 0.25))) :hot
              (or (pos? gap-n) (and (pos? shown-n) (< precision 0.50))) :warm
              :else :calm)
        summary (str "P " (round1 (* 100.0 precision)) "% · tok-P " (round1 (* 100.0 tok-precision))
                     "% · R " (round1 (* 100.0 recall)) "% · " gap-n " recall gap" (when (not= 1 gap-n) "s"))]
    (with-meta
      {:level lvl
       :precision precision
       :token-precision tok-precision
       :recall recall
       :f1 f1
       :shown-count shown-n
       :shown-touched-count shown-touched-n
       :shown-tokens shown-toks
       :touched-tokens touched-toks
       :pool-size (count pool-entries)
       :pool-touched-count pool-touched-n
       :recall-gap-count gap-n
       :recall-gap-items gap-items
       :by-rule (finalize-rollup by-rule-raw)
       :by-fn (finalize-rollup by-fn-raw)
       :summary summary}
      {:seon/schema :seon.signal/scoreboard})))

;; Live DB Candidate Pool Builder & d/as-of Hyperparameter Lab

(defn- entity-max-tx [db eid]
  (if (integer? eid)
    (reduce max 0 (map #(long (or (:tx %) 0)) (d/datoms db :eavt eid)))
    0))

(defn- prior-eval-touch-stats [db agent-id]
  (let [evs (pull-agent-evals-with-tx db agent-id)
        per-turn (:by-turn (extract-eval-touches db evs nil))]
    (reduce (fn [acc t]
              (let [tx (long (:tx t 0))]
                (reduce (fn [a e]
                          (-> a
                              (update-in [:counts e] (fnil inc 0))
                              (update-in [:max-tx e] #(max (long (or % 0)) tx))))
                        acc (:touched-eids t))))
            {:counts {} :max-tx {}} per-turn)))

(defn- scope-neighbor-eids [db ag]
  (let [dh (db-schema db)
        sc (:seon.agent/scope ag)]
    (if-not (and sc (symbol? sc) (contains? dh :seon.code.var/ns))
      []
      (let [prefix (str (name sc) ".")
            ns-eids (if (contains? dh :seon.code.ns/name)
                      (into [] (comp (filter #(let [s (str (:v %))] (or (= s (name sc)) (str/starts-with? s prefix))))
                                     (take 6)
                                     (map (fn [dt] {:entity (:e dt) :schema :seon.code.ns/entity :sym (:v dt)
                                                    :rules [:rule.scope/neighbor] :priority 58 :zone :workspace})))
                            (d/datoms db :aevt :seon.code.ns/name))
                      [])
            var-eids (into [] (comp (filter #(let [s (str (:v %))] (or (= s (name sc)) (str/starts-with? s prefix))))
                                    (take 20)
                                    (map (fn [dt] {:entity (:e dt) :schema :seon.code.var/entity
                                                   :rules [:rule.scope/neighbor] :priority 55 :zone :workspace})))
                           (d/datoms db :aevt :seon.code.var/ns))]
        (into ns-eids var-eids)))))

(defn- infer-ent-schema [ix ent fallback]
  (or (when (qualified-keyword? fallback) fallback)
      (let [ms (six/most-specific ix (dissoc ent :db/id))]
        (when (qualified-keyword? ms) ms))
      (:schema (first (six/facets-of ix (dissoc ent :db/id))))))

(defn- build-lab-candidate-pool [main-db branch-db agent-id]
  (let [eff-db (or branch-db main-db)
        ag (or (rdata/pull-agent-entity main-db eff-db agent-id) {:seon.agent/id agent-id :seon.agent/role :worker})
        ru (ctx/related-updates {:seon.db/main main-db :seon.db/branch eff-db :seon.agent/id agent-id
                                 :seon.view/target :wire :seon.view/token-budget 100000})
        world (proj/agent-world-datoms main-db eff-db agent-id)
        ru-ps (:seon.ctx/projections ru)
        seen (into #{} (keep :entity) ru-ps)
        pins (:pinned world)
        aeid (:agent-eid world)
        world-extra (into [] (comp (remove #(or (= (key %) aeid) (contains? seen (key %))))
                                   (take 12)
                                   (map (fn [[e tx]]
                                          {:entity e :tx tx :priority (if (contains? pins e) 88 60)
                                           :pinned? (contains? pins e) :zone :workspace :rules [:seed/world]})))
                          (sort-by (comp - val) (:connected world)))
        seen2 (into seen (map :entity) world-extra)
        scope-extra (into [] (remove #(contains? seen2 (:entity %))) (scope-neighbor-eids eff-db ag))
        raw (concat (map #(dissoc % :candidates :data) ru-ps) world-extra scope-extra)
        ix (schema/entity-index eff-db)
        prior (prior-eval-touch-stats eff-db agent-id)
        enriched
        (vec
         (keep (fn [item]
                 (when-let [eid (when (integer? (:entity item)) (:entity item))]
                   (let [b (d/pull eff-db '[*] eid)
                         ent (if (seq (dissoc b :db/id)) b (d/pull main-db '[*] eid))]
                     (when (and (map? ent) (seq (dissoc ent :db/id))
                                (not (contains? ent :seon.agent.eval/id)))
                       (let [sk (infer-ent-schema ix ent (:schema item))
                             sym (or (:sym item) (:seon.code.var/name ent) (:seon.code.ns/name ent))
                             etx (max (long (or (:tx item) 0))
                                      (entity-max-tx eff-db eid)
                                      (long (get-in prior [:max-tx eid] 0)))
                             touches (long (get-in prior [:counts eid] 0))]
                         (cond-> (assoc item :entity eid :data ent :tx etx :touches touches)
                           sk (assoc :schema sk)
                           sym (assoc :sym sym)))))))
               raw))
        ;; Pre-discover and measure multi-level complete-thought candidates ONCE
        measured (:selected (fit/fit-projections eff-db enriched
                                                 {:target :wire :token-budget 1000000
                                                  :fallback-renderers rwire/fallback-entity-renderers}))]
    ;; Keep original full candidate list with measured multi-level candidates attached
    (let [by-e (into {} (map (juxt :entity identity)) measured)]
      (mapv (fn [item]
              (if-let [m (get by-e (:entity item))]
                (assoc item
                       :candidates (or (:candidates m) [])
                       :tokens (long (or (:measured-tokens m) (:tokens m) 0)))
                item))
            enriched))))

(defn context-scoreboard
  "Live Datahike empirical scoreboard for `agent-id` over its projected context and eval history.
   Optional `opts`: `{:tx <as-of-tx> :token-budget 2400 :strategy :breadth-first :weights {...}}`."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db :qualified-keyword] :seon.signal/scoreboard]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword] :seon.signal/scoreboard]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword [:maybe :map]] :seon.signal/scoreboard]]
   :cost/ticks 60000000}
  ([db agent-id] (context-scoreboard db db agent-id nil))
  ([main-db branch-db agent-id] (context-scoreboard main-db branch-db agent-id nil))
  ([main-db branch-db agent-id opts]
   (let [t0 (System/nanoTime)
         o (if (map? opts) opts {})
         bdb (or branch-db main-db)
         as-of-tx (when (integer? (or (:tx o) (:as-of-tx o))) (long (or (:tx o) (:as-of-tx o))))
         m-at (if as-of-tx (safe-as-of main-db as-of-tx) main-db)
         b-at (if as-of-tx (safe-as-of bdb as-of-tx) bdb)
         pool (build-lab-candidate-pool m-at b-at agent-id)
         ws (normalize-profile-weights (:weights o))
         ranked (fit/rank-candidates pool (when ws {:weights ws}))
         ladder (fit/upgrade-ladder ranked {:target :wire
                                            :strategy (or (:strategy o) :breadth-first)
                                            :token-budget (long (or (:token-budget o) 16000))})
         ev-db (if (seq (d/datoms bdb :avet :seon.agent.eval/agent agent-id)) bdb main-db)
         touches (extract-eval-touches ev-db agent-id (when as-of-tx {:after-tx as-of-tx}))
         sb (score-shown-blocks (:selected ladder) pool touches)
         ms (round1 (/ (double (- (System/nanoTime) t0)) 1000000.0))]
     (with-meta
       (assoc sb
              :agent agent-id
              :tx (or as-of-tx (long (or (:max-tx bdb) 0)))
              :eval-count (:eval-count touches)
              :manual-lookup-count (count (:manual-lookup-calls touches))
              :ms ms)
       {:seon/schema :seon.signal/scoreboard}))))

(defn- eval-profile-against-pool [pool later-touches prof]
  (let [label (or (:label prof) :unnamed)
        ws (normalize-profile-weights (:weights prof))
        strategy (if (= :depth-first (:strategy prof)) :depth-first :breadth-first)
        budget (safe-nonneg-long (or (:token-budget prof) 2400))
        ranked (fit/rank-candidates pool (when ws {:weights ws}))
        ladder-opts (cond-> {:target :wire :strategy strategy :token-budget budget}
                      (integer? (:max-items prof)) (assoc :max-items (long (:max-items prof))))
        ladder (fit/upgrade-ladder ranked ladder-opts)
        sb (score-shown-blocks (:selected ladder) pool later-touches)]
    (assoc sb
           :label label
           :weights (or (:weights prof) fit/default-rank-weights)
           :strategy strategy
           :token-budget budget
           :selected-count (count (:selected ladder))
           :dropped-count (count (:dropped ladder)))))

(defn compare-as-of-hyperparams
  "Reproducible `d/as-of` A/B hyperparameter lab: rewinds `db` to `tx`, builds the candidate pool
   once, runs `rank-candidates` + `upgrade-ladder` for every profile in `profiles`, extracts actual
   eval touches strictly after `tx`, scores every profile, and picks the deterministic `:winner`."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db :qualified-keyword :int] :map]
                  [:=> [:cat :seon.host/db :qualified-keyword :int [:maybe [:vector :map]]] :map]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword :int [:maybe [:vector :map]]] :map]]
   :cost/ticks 80000000}
  ([db agent-id tx] (compare-as-of-hyperparams db db agent-id tx default-hyperparam-profiles))
  ([db agent-id tx profiles] (compare-as-of-hyperparams db db agent-id tx profiles))
  ([main-db branch-db agent-id tx profiles]
   (let [t0 (System/nanoTime)
         bdb (or branch-db main-db)
         profs (if (and (sequential? profiles) (seq profiles)) (vec profiles) default-hyperparam-profiles)
         m-at (safe-as-of main-db tx)
         b-at (safe-as-of bdb tx)
         pool (build-lab-candidate-pool m-at b-at agent-id)
         ev-db (if (seq (d/datoms bdb :avet :seon.agent.eval/agent agent-id)) bdb main-db)
         later (extract-eval-touches ev-db agent-id {:after-tx (long tx)})
         results (mapv #(eval-profile-against-pool pool later %) profs)
         winner-row (first (sort-by (fn [r]
                                      [(- (double (:f1 r 0.0)))
                                       (long (:recall-gap-count r 0))
                                       (- (double (:token-precision r 0.0)))
                                       (- (double (:recall r 0.0)))
                                       (long (:shown-tokens r 0))
                                       (str (:label r))])
                                    results))
         actual-n (count (set/union (:touched-eids later) (:touched-syms later) (:touched-schemas later)))
         ms (round1 (/ (double (- (System/nanoTime) t0)) 1000000.0))]
     {:tx (long tx)
      :agent agent-id
      :pool-size (count pool)
      :actual-touched-count actual-n
      :later-eval-count (:eval-count later)
      :manual-lookup-count (count (:manual-lookup-calls later))
      :profiles results
      :winner (:label winner-row)
      :ms ms})))

(defn- resolve-agent-dbs
  {:seon.flow/edge true}
  [ctx-fn aid]
  (let [{:keys [conn branch agent-id]} (ctx-fn)
        eff-aid (or (when (qualified-keyword? aid) aid) agent-id :agent/root)
        bdb @conn
        mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))
        ag-br (when (contains? (db-schema mdb) :seon.agent/branch)
                (:seon.agent/branch (d/pull mdb '[:seon.agent/branch] [:seon.agent/id eff-aid])))
        target-bdb (if (and ag-br (not= ag-br branch))
                     (or (try (store/head conn ag-br) (catch Throwable _ nil)) bdb)
                     bdb)]
    {:mdb mdb :bdb target-bdb :aid eff-aid}))

(defn- default-mid-tx [mdb bdb aid]
  (let [ev-db (if (seq (d/datoms bdb :avet :seon.agent.eval/agent aid)) bdb mdb)
        txs (vec (sort (distinct (map #(long (:tx % 0)) (d/datoms ev-db :avet :seon.agent.eval/agent aid)))))]
    (cond
      (>= (count txs) 2) (nth txs (quot (dec (count txs)) 2))
      (= 1 (count txs)) (dec (first txs))
      :else (long (or (:max-tx bdb) 0)))))

(defn install-user-helpers!
  "Installs `user/context-scoreboard` and `user/compare-hyperparams` REPL helpers into namespace `u`."
  {:seon.flow/edge true
   :malli/schema [:=> [:cat :any :any] :any]}
  [u ctx-fn]
  (when (and (instance? clojure.lang.Namespace u) (fn? ctx-fn))
    (let [v1 (intern u 'context-scoreboard
                     (fn csb
                       ([] (csb nil {}))
                       ([arg] (if (map? arg) (csb nil arg) (csb arg {})))
                       ([agent-id opts]
                        (let [{:keys [mdb bdb aid]} (resolve-agent-dbs ctx-fn agent-id)]
                          (context-scoreboard mdb bdb aid opts)))))
          v2 (intern u 'compare-hyperparams
                     (fn chp
                       ([]
                        (let [{:keys [mdb bdb aid]} (resolve-agent-dbs ctx-fn nil)]
                          (compare-as-of-hyperparams mdb bdb aid (default-mid-tx mdb bdb aid) default-hyperparam-profiles)))
                       ([agent-id]
                        (let [{:keys [mdb bdb aid]} (resolve-agent-dbs ctx-fn agent-id)]
                          (compare-as-of-hyperparams mdb bdb aid (default-mid-tx mdb bdb aid) default-hyperparam-profiles)))
                       ([agent-id tx]
                        (let [{:keys [mdb bdb aid]} (resolve-agent-dbs ctx-fn agent-id)]
                          (compare-as-of-hyperparams mdb bdb aid (long tx) default-hyperparam-profiles)))
                       ([agent-id tx profiles]
                        (let [{:keys [mdb bdb aid]} (resolve-agent-dbs ctx-fn agent-id)]
                          (compare-as-of-hyperparams mdb bdb aid (long tx) profiles)))))]
      (alter-meta! v1 merge {:arglists '([] [agent-id-or-opts] [agent-id opts])
                             :doc "Compute empirical context Precision, Token Precision, Recall, Recall Gap, and per-rule/fn rollups."})
      (alter-meta! v2 merge {:arglists '([] [agent-id] [agent-id tx] [agent-id tx profiles])
                             :doc "Run reproducible d/as-of A/B hyperparameter sweep across rank weights, ladder strategies, and token budgets."})
      [v1 v2])))
