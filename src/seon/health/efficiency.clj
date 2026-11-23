(ns seon.health.efficiency
  "Efficiency & telemetry advisories for System Health — fast AEVT/`d/pull` reads over Datahike facts.
   Pure scans for:
   - `:oversized-namespace` & `:nested-db-scan` (`static-efficiency-warnings`): code-structure waste.
   - `:slow-test` & `:cost-regression` (`test-efficiency-warnings`): live test perf regressions.
   - `:low-cache-hit` & `:heavy-eval` (`eval-efficiency-warnings`): live agent/eval KV-cache & allocation waste.
   `seon.health` re-exports thin delegating wrappers so its public API stays stable."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.store :as store]
            [seon.code.parse :as parse]
            [datahike.datom :as dd]
            [datahike.index.interface :as di]
            [konserve.core :as k]
            [clojure.core.cache :as cache]
            [datahike.versioning :as dv]))

(defn ns-in-scope?
  "True when namespace symbol `nsym` belongs to `scope` (exact match or descendant `scope.*`,
   with `'seon` matching all `seon.*` namespaces)."
  {:malli/schema [:=> [:cat [:maybe :symbol] [:maybe :symbol]] [:maybe :boolean]], :cost/ticks 50000}
  [scope nsym]
  (when (and scope nsym)
    (let [sc (name scope)
          ns-s (name nsym)]
      (or (= ns-s sc)
          (str/starts-with? ns-s (str sc "."))))))

(defn ns-resource-profile
  "Computes the empirical resource profile for namespace `nsym` from Datahike `:seon.code.var/*`
   contracts/budgets and `:seon.test/*` verdicts: `{:ns :vars :contracted :budgeted :tests :max-ms :max-ticks :max-bytes}`."
  {:malli/schema [:=> [:cat :seon.host/db :symbol] :map]}
  [db nsym]
  (let [var-ents (mapv #(d/pull db '[:seon.code.var/name :seon.code.var/private? :seon.code.var/schema
                                     :seon.code.var/ticks-budget :seon.code.var/bytes-budget] (:e %))
                       (d/datoms db :avet :seon.code.var/ns nsym))
        var-set (into #{} (map :seon.code.var/name) var-ents)
        test-rows (if (contains? (:schema db) :seon.test/var)
                    (keep (fn [dt]
                            (let [v (:v dt)]
                              (when (or (contains? var-set v)
                                        (and (namespace v) (ns-in-scope? nsym (symbol (namespace v)))))
                                (d/pull db '[:seon.test/var :seon.test/pass? :seon.test/ms
                                             :seon.test/ticks :seon.test/bytes] (:e dt)))))
                          (d/datoms db :aevt :seon.test/var))
                    [])]
    {:ns nsym
     :vars (count var-ents)
     :public-vars (count (remove :seon.code.var/private? var-ents))
     :contracted (count (filter :seon.code.var/schema var-ents))
     :budgeted (count (filter #(or (:seon.code.var/ticks-budget %) (:seon.code.var/bytes-budget %)) var-ents))
     :test-runs (count test-rows)
     :max-ms (reduce max 0.0 (map #(double (or (:seon.test/ms %) 0.0)) test-rows))
     :max-ticks (reduce max 0 (map #(long (or (:seon.test/ticks %) 0)) test-rows))
     :max-bytes (reduce max 0 (map #(long (or (:seon.test/bytes %) 0)) test-rows))}))

(defonce ^:private ^:seon.flow/edge form-static-cache (store/lru-cache 2048))

(defonce ^:private ^:seon.flow/edge ns-unused-req-cache (store/lru-cache 256))

(defn- safe-read-form [s]
  (try (read-string s) (catch RuntimeException _ nil)))

(defn- resolve-src-value [db v]
  (if (dd/lazy-value? v)
    (let [st (:store db)]
      (or (when st
            (let [res (k/get st (dd/lazy-key v) ::missing {:sync? true})]
              (when-not (identical? ::missing res) (str res))))
          (str (dd/resolve-lazy v))))
    (str v)))

(defn- fetch-missing-srcs [db ^java.util.HashSet needed-eids]
  (let [src-hm (java.util.HashMap. (max 16 (.size needed-eids)))]
    (when (pos? (.size needed-eids))
      (if (<= (.size needed-eids) 150)
        (let [it (.iterator needed-eids)]
          (loop []
            (when (.hasNext it)
              (let [e (.next it)]
                (when-let [dt (first (d/datoms db :eavt e :seon.code.form/src))]
                  (.put src-hm e (str (:v dt)))))
              (recur))))
        (binding [di/*read-key-attrs* #{}]
          (doseq [dt (d/datoms db :aevt :seon.code.form/src)]
            (let [e (:e dt)]
              (when (.contains needed-eids e)
                (.put src-hm e (resolve-src-value db (:v dt)))))))))
    src-hm))

(defn- emit-form-advisories! [br nsym k {:keys [chars defn? lines nested-scan?]} nested large-fns]
  (when (and k defn?)
    (when (and (= 1 lines) (> chars 280))
      (vswap! large-fns conj
              (cond-> {:class :minified-form :severity :error :kind :static
                       :ns nsym :subject k :branch br
                       :message (str "Minified 1-line function " k " (" chars " chars on 1 line). Pass formatted source strings or pretty-print so code stays readable and line metrics cannot be gamed.")}
                (not (str/includes? k "#")) (assoc :var (symbol k)))))
    (when (or (> lines 120) (> chars 7200))
      (vswap! large-fns conj
              (cond-> {:class :large-function :severity :warning :kind :static
                       :ns nsym :subject k :branch br
                       :message (str "Large function " k " (" lines " lines, " chars " chars > 120-line / 7.2k-char budget). Extract pure helpers to keep edits fast and testable.")}
                (not (str/includes? k "#")) (assoc :var (symbol k))))))
  (when (and k nested-scan?
             (not (contains? #{"seon.health/schema-violations" "seon.health.static/schema-violations"} k)))
    (vswap! nested conj
            (cond-> {:class :nested-db-scan :severity :warning :kind :static
                     :ns nsym :subject k :branch br
                     :message (str "Nested full DB scan (`d/datoms :aevt` or `d/q`) inside a loop in " k
                                   ". Hoist the scan outside the loop or use an indexed `:avet` lookup.")}
              (not (str/includes? k "#")) (assoc :var (symbol k))))))

(defn- collect-forms-by-ns [db scope]
  (if scope
    (into {}
          (for [nsym (->> (d/datoms db :aevt :seon.code.ns/name)
                          (map :v)
                          (filter #(and (not (str/ends-with? (name %) "-test")) (ns-in-scope? scope %))))
                :let [fs (mapv (fn [dt]
                                 (let [p (d/pull db '[:seon.code.form/key :seon.code.form/hash] (:e dt))]
                                   {:e (:e dt) :key (:seon.code.form/key p) :hash (:seon.code.form/hash p)}))
                               (d/datoms db :avet :seon.code.form/ns nsym))]
                :when (seq fs)]
            [nsym fs]))
    (let [eid->key (java.util.HashMap. 1400)
          eid->hash (java.util.HashMap. 1400)
          by-ns (java.util.HashMap. 160)]
      (when (contains? (:schema db) :seon.code.form/key)
        (doseq [dt (d/datoms db :aevt :seon.code.form/key)] (.put eid->key (:e dt) (:v dt))))
      (when (contains? (:schema db) :seon.code.form/hash)
        (doseq [dt (d/datoms db :aevt :seon.code.form/hash)] (.put eid->hash (:e dt) (:v dt))))
      (doseq [dt (d/datoms db :aevt :seon.code.form/ns)
              :let [nsym (:v dt) e (:e dt)]
              :when (and nsym (not (str/ends-with? (name nsym) "-test")))]
        (let [arr (or (.get by-ns nsym) (let [a (java.util.ArrayList. 16)] (.put by-ns nsym a) a))]
          (.add ^java.util.ArrayList arr {:e e :key (.get eid->key e) :hash (.get eid->hash e)})))
      (into {} (map (fn [e] [(.getKey ^java.util.Map$Entry e) (vec ^java.util.ArrayList (.getValue ^java.util.Map$Entry e))])) (.entrySet by-ns)))))

(defn- batch-cache-put! [cache-atom threshold entries]
  (when (seq entries)
    (swap! cache-atom
           (fn [c]
             (let [c (if (satisfies? cache/CacheProtocol c) c (cache/lru-cache-factory {} :threshold (long threshold)))]
               (reduce-kv cache/miss c entries))))))

(defn- compute-unused-requires [forms decl get-src!]
  (let [bodies (into [] (keep (fn [{:keys [e key]}]
                                (when-not (and (string? key) (str/ends-with? key "#ns"))
                                  (get-src! e))))
                     forms)
        lib-aliases (reduce-kv (fn [a al lib] (update a lib (fnil conj []) (name al))) {} (:aliases decl))
        lib-refers (reduce-kv (fn [a rf lib] (update a lib (fnil conj []) (name rf))) {} (:refers decl))]
    (vec
     (sort
      (keep (fn [lib]
              (let [als (get lib-aliases lib)
                    refs (get lib-refers lib)]
                (when (or (seq als) (seq refs))
                  (let [ls (str (name lib) "/")
                        ld (str (name lib) ".")]
                    (when-not (some (fn [^String b]
                                      (or (.contains b ls) (.contains b ld)
                                          (some #(.contains b (str % "/")) als)
                                          (some #(.contains b ^String %) refs)))
                                    bodies)
                      lib)))))
            (:requires decl))))))

(defn- suppress-heavy-eval? [branch-db br aid opts]
  (let [call-db (or (:main-db opts) branch-db)
        resolved? (when (and call-db aid (contains? (:schema call-db) :seon.agent.call/callee))
                    (let [sts (mapv #(:v (first (d/datoms call-db :eavt (:e %) :seon.agent.call/status)))
                                    (d/datoms call-db :avet :seon.agent.call/callee aid))]
                      (and (seq sts) (some #(= :resolved %) sts) (not-any? #(= :pending %) sts))))
        no-edits? (when (not= br :main)
                    (if (contains? opts :branch-edited)
                      (empty? (:branch-edited opts))
                      (if-not (and (instance? datahike.db.DB branch-db) (:store branch-db))
                        true
                        (let [fc (atom branch-db)
                              cid (d/commit-id branch-db)
                              base (when (and cid (contains? (dv/branches fc) :main))
                                     (dv/merge-base fc :main br))]
                          (not (and base (not= base cid)
                                    (some #(contains? #{:seon.code.form/key :seon.code.form/src :seon.code.form/hash
                                                        :seon.code.var/name :seon.code.ns/name} (:a %))
                                          (dv/changes branch-db base cid))))))))]
    (boolean (or resolved? no-edits?))))

(defn- heavy-eval-notice [branch-db br aid es opts]
  (let [heavy (filterv (fn [ev]
                         (or (> (long (or (:seon.agent.eval/bytes ev) 0)) 134217728)
                             (> (long (or (:seon.agent.eval/ticks ev) 0)) 50000000)
                             (str/includes? (str (:seon.agent.eval/error ev)) "Eval exceeded")))
                       es)]
    (when (and (seq heavy) (not (suppress-heavy-eval? branch-db br aid opts)))
      (let [n (count heavy)
            max-mb (quot (reduce max 0 (map #(long (or (:seon.agent.eval/bytes %) 0)) heavy)) 1048576)]
        [{:class :heavy-eval :severity :warning :kind :live :agent aid
          :subject (str aid) :branch br :count n :max-mb max-mb
          :message (str "Telemetry notice (non-blocking, immutable eval ledger — do not retry or retract): "
                        n " heavy evals on branch (max " max-mb " MB)")}]))))

(defn eval-efficiency-warnings
"Inspects agent evals on `branch-db` for `:low-cache-hit`, `:prefix-cache-bust`, and `:heavy-eval`."
{:malli/schema [:function
                [:=> [:cat :any] [:vector :map]]
                [:=> [:cat :any :any] [:vector :map]]
                [:=> [:cat :any :any :any] [:vector :map]]
                [:=> [:cat :any :any :any [:maybe :map]] [:vector :map]]]}
([branch-db] (eval-efficiency-warnings branch-db nil nil nil))
([branch-db agent-id] (eval-efficiency-warnings branch-db agent-id nil nil))
([branch-db agent-id prefetched-evals] (eval-efficiency-warnings branch-db agent-id prefetched-evals nil))
([branch-db agent-id prefetched-evals opts]
 (if-not (or (seq prefetched-evals) (and branch-db (contains? (:schema branch-db) :seon.agent.eval/agent)))
   []
   (let [br (if branch-db (get-in branch-db [:config :branch] :main) :main)
         evals (if (some? prefetched-evals)
                 (if agent-id
                   (filterv #(or (= agent-id (:seon.agent.eval/agent %)) (nil? (:seon.agent.eval/agent %))) prefetched-evals)
                   (vec prefetched-evals))
                 (let [eids (if agent-id
                              (mapv :e (d/datoms branch-db :avet :seon.agent.eval/agent agent-id))
                              (mapv :e (d/datoms branch-db :aevt :seon.agent.eval/agent)))]
                   (mapv #(d/pull branch-db
                                  '[:seon.agent.eval/agent :seon.agent.eval/handle :seon.agent.eval/turn
                                    :seon.agent.eval/call-index :seon.agent.eval/prefix-hash
                                    :seon.agent.eval/ms :seon.agent.eval/ticks :seon.agent.eval/bytes
                                    :seon.agent.eval/error :seon.agent.eval/cache-hit-tokens
                                    :seon.agent.eval/cache-miss-tokens] %)
                         eids)))
         by-ag (group-by #(or (:seon.agent.eval/agent %) agent-id) evals)]
     (->> by-ag
          (mapcat
           (fn [[aid es]]
             (let [cache-es (filter #(or (pos? (long (or (:seon.agent.eval/cache-hit-tokens %) 0)))
                                         (pos? (long (or (:seon.agent.eval/cache-miss-tokens %) 0)))) es)
                   hits (reduce + 0 (map #(long (or (:seon.agent.eval/cache-hit-tokens %) 0)) cache-es))
                   miss (reduce + 0 (map #(long (or (:seon.agent.eval/cache-miss-tokens %) 0)) cache-es))
                   tot (+ hits miss)
                   cache-warn (when (and (>= (count cache-es) 2) (>= tot 2000) (< (/ (double hits) tot) 0.60))
                                [{:class :low-cache-hit :severity :warning :kind :live :agent aid
                                  :subject (str aid) :branch br
                                  :message (str "Low LLM KV-cache hit rate on " aid ": "
                                                (Math/round (* 100.0 (/ (double hits) tot))) "% ("
                                                hits "/" tot " tok across " (count cache-es) " turns). "
                                                "Check for dynamic data leaking into static <walk-context>.")}])
                   turn-rows (->> es
                                  (filter #(let [ci (:seon.agent.eval/call-index %)] (or (nil? ci) (zero? (long ci)))))
                                  (filter #(or (:seon.agent.eval/prefix-hash %) (:seon.agent.eval/cache-hit-tokens %)))
                                  (sort-by #(long (or (:seon.agent.eval/turn %) 0))))
                   busts (vec (keep (fn [[prev cur]]
                                      (let [t1 (long (or (:seon.agent.eval/turn prev) 0))
                                            t2 (long (or (:seon.agent.eval/turn cur) 0))
                                            p1 (:seon.agent.eval/prefix-hash prev) p2 (:seon.agent.eval/prefix-hash cur)
                                            h1 (:seon.agent.eval/cache-hit-tokens prev) h2 (:seon.agent.eval/cache-hit-tokens cur)
                                            hc? (and (some? p1) (some? p2) (not= p1 p2))
                                            hd? (and (number? h1) (number? h2) (> (long h1) 512) (< (long h2) (- (long h1) 256)))]
                                        (when (and (= t2 (inc t1)) (or hc? hd?))
                                          {:turn t2 :prev-hash p1 :cur-hash p2 :prev-hit h1 :cur-hit h2
                                           :reason (if hc? :prefix-hash-changed :cache-hit-drop)})))
                                    (partition 2 1 turn-rows)))
                   bust-warn (when (seq busts)
                               (let [b (peek busts)]
                                 [{:class :prefix-cache-bust :severity :warning :kind :live :agent aid
                                   :subject (str aid "/t" (:turn b)) :branch br :bust-count (count busts)
                                   :message (str "KV-cache prefix bust on " aid " (" (count busts) "x, latest turn " (:turn b)
                                                 (if (= :prefix-hash-changed (:reason b))
                                                   (str ": prefix-hash " (:prev-hash b) " -> " (:cur-hash b))
                                                   (str ": cache-hit dropped " (:prev-hit b) " -> " (:cur-hit b) " tok"))
                                                 "). Static <walk-context> mutated mid-session.")}]))]
               (concat cache-warn bust-warn (heavy-eval-notice branch-db br aid es opts)))))
          vec)))))

(defn test-snapshot
"Two flat AEVT scans (`:seon.test/var` + `:seon.test/pass?`) returning `{:top2 :var-sig :pass-sig :supersession}`."
{:malli/schema [:=> [:cat [:maybe :seon.host/db]] :map]
 :cost/ticks 25000000}
[db]
(if-not (and db (contains? (:schema db) :seon.test/var))
  {:top2 (java.util.HashMap.) :var-sig [0 0] :pass-sig [0 0] :supersession {:var->green-tx {} :eid->pass-tx {}}}
  (let [var-by-eid (java.util.HashMap. 8192)
        top2 (java.util.HashMap. 768)
        var->green (java.util.HashMap. 768)
        fail-tx (transient {})
        ^longs sigs (long-array 4)]
    (doseq [dt (d/datoms db :aevt :seon.test/var)]
      (.put var-by-eid (:e dt) (:v dt))
      (aset sigs 0 (unchecked-inc (aget sigs 0)))
      (let [t (long (:tx dt))] (when (> t (aget sigs 1)) (aset sigs 1 t))))
    (when (contains? (:schema db) :seon.test/pass?)
      (doseq [dt (d/datoms db :aevt :seon.test/pass?)]
        (let [e (:e dt) tx (long (:tx dt))]
          (aset sigs 2 (unchecked-inc (aget sigs 2)))
          (when (> tx (aget sigs 3)) (aset sigs 3 tx))
          (if (:v dt)
            (when-let [v (.get var-by-eid e)]
              (let [g (.get var->green v)]
                (when (or (nil? g) (> tx (long g))) (.put var->green v tx)))
              (let [^longs sl (.get top2 v)]
                (if (nil? sl)
                  (.put top2 v (long-array [e tx -1 -1]))
                  (let [t1 (aget sl 1) t2 (aget sl 3)]
                    (cond
                      (or (> tx t1) (and (= tx t1) (> e (aget sl 0))))
                      (do (aset sl 2 (aget sl 0)) (aset sl 3 t1) (aset sl 0 e) (aset sl 1 tx))
                      (or (> tx t2) (and (= tx t2) (> e (aget sl 2))))
                      (do (aset sl 2 e) (aset sl 3 tx)))))))
            (assoc! fail-tx e tx)))))
    {:top2 top2 :var-sig [(aget sigs 0) (aget sigs 1)] :pass-sig [(aget sigs 2) (aget sigs 3)]
     :supersession {:var->green-tx (into {} (map (fn [^java.util.Map$Entry en] [(.getKey en) (.getValue en)])) (.entrySet var->green))
                    :eid->pass-tx (persistent! fail-tx)}})))

(defn test-efficiency-warnings
"Checks latest passing test verdicts per `:seon.test/var` for `:slow-test` and `:cost-regression` in O(|live-vars|).
 Reuses optional `test-snap` `:top2` and fetches `:ms`/`:ticks`/`:bytes` in ascending eid order via indexed `:eavt`."
{:malli/schema [:function
                [:=> [:cat :seon.host/db] [:vector :map]]
                [:=> [:cat :seon.host/db [:maybe :map]] [:vector :map]]]}
([db] (test-efficiency-warnings db nil))
([db test-snap]
 (if-not (and db (contains? (:schema db) :seon.test/var))
   []
   (let [br (get-in db [:config :branch] :main)
         sm (:schema db)
         has-ms? (contains? sm :seon.test/ms)
         has-tk? (contains? sm :seon.test/ticks)
         has-by? (contains? sm :seon.test/bytes)
         top2 ^java.util.HashMap (or (:top2 test-snap) (:top2 (test-snapshot db)))
         rd (fn [e]
              {:ms (if has-ms? (double (or (:v (first (d/datoms db :eavt e :seon.test/ms))) 0.0)) 0.0)
               :ticks (if has-tk? (long (or (:v (first (d/datoms db :eavt e :seon.test/ticks))) 0)) 0)
               :bytes (if has-by? (long (or (:v (first (d/datoms db :eavt e :seon.test/bytes))) 0)) 0)})
         ordered (sort-by (fn [^java.util.Map$Entry en] (aget ^longs (.getValue en) 0)) (.entrySet top2))
         cands (volatile! [])
         out (volatile! [])]
     (doseq [^java.util.Map$Entry en ordered]
       (let [vsym (.getKey en)
             ^longs slot (.getValue en)
             cur (rd (aget slot 0))]
         (when (or (> (:ms cur) 200.0) (> (:ticks cur) 5000000) (> (:bytes cur) 33554432))
           (let [nsym (when (namespace vsym) (symbol (namespace vsym)))
                 e2 (aget slot 2)]
             (when (> (:ms cur) 1000.0)
               (vswap! out conj {:class :slow-test :severity :warning :kind :live :ns nsym :var vsym
                                 :subject (str vsym) :branch br
                                 :message (str "Slow test " vsym ": " (Math/round (:ms cur)) " ms, "
                                               (quot (:bytes cur) 1048576) " MB allocated (> 1000 ms budget).")}))
             (when (pos? e2)
               (vswap! cands conj [e2 vsym nsym cur]))))))
     (doseq [[e2 vsym nsym cur] (sort-by first @cands)]
       (let [prev (rd e2)
             dticks (- (:ticks cur) (:ticks prev))
             dbytes (- (:bytes cur) (:bytes prev))
             dms (- (:ms cur) (:ms prev))
             tr (when (pos? (:ticks prev)) (/ (double (:ticks cur)) (:ticks prev)))
             bratio (when (pos? (:bytes prev)) (/ (double (:bytes cur)) (:bytes prev)))
             mratio (when (> (:ms prev) 20.0) (/ (double (:ms cur)) (:ms prev)))]
         (when (or (and tr (> tr 2.5) (> dticks 5000000))
                   (and bratio (> bratio 2.5) (> dbytes 33554432))
                   (and mratio (> mratio 2.5) (> dms 200.0)))
           (vswap! out conj {:class :cost-regression :severity :warning :kind :live :ns nsym :var vsym
                             :subject (str vsym) :branch br
                             :message (str "Cost regression in " vsym ": "
                                           (when (and tr (> tr 2.5)) (str (format "%.1fx" tr) " ticks "))
                                           (when (and bratio (> bratio 2.5)) (str (format "%.1fx" bratio) " bytes "))
                                           (when (and mratio (> mratio 2.5) (> dms 200.0)) (str (format "%.1fx" mratio) " ms "))
                                           "(" (Math/round (:ms prev)) " ms -> " (Math/round (:ms cur)) " ms).")}))))
     (vec (sort-by :subject @out))))))

(defn- metrics->info [{:seon.code.form/keys [lines chars nested-scan?]}]
  (let [ln (long (or lines 0))]
    {:ns-hdr? false :chars (long (or chars 0)) :defn? (pos? ln) :lines ln :nested-scan? (true? nested-scan?)}))

(defn- analyze-form-static [ns-hdr? ^String s]
  (if ns-hdr?
  {:ns-hdr? true :chars (.length s) :decl (when-let [f (safe-read-form s)] (parse/parse-ns-decl f))}
  (metrics->info (parse/form-static-metrics s nil))))

(defn static-efficiency-warnings
  "Scans non-test `:seon.code.form/src` (cached per `:seon.code.form/hash` + per-ns `:unused-require` cache,
   reusing optional `form-snap`) to flag `:oversized-namespace`, `:minified-form`, `:large-function`,
   `:nested-db-scan`, and `:unused-require`."
  {:malli/schema [:function
                  [:=> [:cat :any] [:vector :map]]
                  [:=> [:cat :any [:maybe :symbol]] [:vector :map]]
                  [:=> [:cat :any [:maybe :symbol] [:maybe :map]] [:vector :map]]]}
  ([db] (static-efficiency-warnings db nil nil))
  ([db scope] (static-efficiency-warnings db scope nil))
  ([db scope form-snap]
   (if-not (and db (contains? (:schema db) :seon.code.form/src))
     []
     (let [br (get-in db [:config :branch] :main)
           forms-by-ns (if (and (nil? scope) form-snap)
                         (:src-forms-by-ns form-snap)
                         (collect-forms-by-ns db scope))
           fcache @form-static-cache
           rcache @ns-unused-req-cache
           has-rcache? (pos? (count rcache))
           needed-eids (java.util.HashSet. 256)
           req-sigs (java.util.HashMap. 160)
           pinfo (java.util.HashMap. 256)]
       (doseq [[nsym forms] forms-by-ns]
         (let [rsig (when (every? :hash forms) [nsym (mapv (juxt :key :hash) (sort-by :key forms))])
               _ (when rsig (.put req-sigs nsym rsig))
               rcached? (and has-rcache? rsig (contains? rcache rsig))
               hdr-form (some #(when (and (string? (:key %)) (str/ends-with? (:key %) "#ns")) %) forms)]
           (doseq [{:keys [e key hash]} forms]
             (let [hdr? (boolean (and (string? key) (str/ends-with? key "#ns")))]
               (when-not (and hash (contains? fcache [hash hdr?]))
               (when-not (and (not hdr?) (.containsKey pinfo e))
               (.add needed-eids e)))))
           (when (and (not rcached?) hdr-form)
             (let [hc (when-let [hh (:hash hdr-form)] (get fcache [hh true]))]
               (when (or (nil? hc) (let [d (:decl hc)] (or (seq (:aliases d)) (seq (:refers d)))))
                 (doseq [{:keys [e key]} forms]
                   (when-not (and (string? key) (str/ends-with? key "#ns"))
                     (.add needed-eids e))))))))
       (let [src-hm (fetch-missing-srcs db needed-eids)
             _ (when (and di/*reads* (zero? (.size needed-eids)))
                 (di/record-read! (dd/datom 0 :seon.code.form/src nil 0)
                                  (dd/datom Long/MAX_VALUE :seon.code.form/src nil Long/MAX_VALUE)))
             get-src! (fn [e] (or (.get src-hm e)
                                  (let [s (str (:v (first (d/datoms db :eavt e :seon.code.form/src))))]
                                    (.put src-hm e s) s)))
             new-f (volatile! {})
             new-r (volatile! {})
             oversized (volatile! [])
             unused-reqs (volatile! [])
             nested (volatile! [])
             large-fns (volatile! [])]
         (doseq [[nsym forms] forms-by-ns]
           (let [hdr-decl (volatile! nil)
                 total-chars (volatile! 0)]
             (doseq [{:keys [e key hash]} forms]
               (let [hdr? (boolean (and (string? key) (str/ends-with? key "#ns")))
                     ck (when hash [hash hdr?])
                     h (if ck (get fcache ck ::miss) ::miss)
                     info (if-not (identical? ::miss h)
                            h
                            (let [v (or (.get pinfo e) (analyze-form-static hdr? (get-src! e)))]
                              (when ck (vswap! new-f assoc ck v))
                              v))]
                 (vswap! total-chars + (long (:chars info 0)))
                 (if hdr? (vreset! hdr-decl (:decl info)) (emit-form-advisories! br nsym key info nested large-fns))))
             (let [toks (quot @total-chars 4)]
               (when (> toks 8000)
                 (vswap! oversized conj
                         {:class :oversized-namespace :severity :warning :kind :static
                          :ns nsym :subject (str nsym) :branch br
                          :message (str "Namespace " nsym " is ~" toks " tokens (> 8k cap). "
                                        "Split into cohesive 2k-6k tok child namespaces so specialists load cleanly.")})))
             (when-let [decl @hdr-decl]
               (when (or (seq (:aliases decl)) (seq (:refers decl)))
                 (let [rsig (.get req-sigs nsym)
                       unused (if rsig
                                (let [h (get rcache rsig ::miss)]
                                  (if-not (identical? ::miss h)
                                    h
                                    (let [v (compute-unused-requires forms decl get-src!)]
                                      (vswap! new-r assoc rsig v)
                                      v)))
                                (compute-unused-requires forms decl get-src!))]
                   (when (seq unused)
                     (vswap! unused-reqs conj
                             {:class :unused-require :severity :warning :kind :static
                              :ns nsym :subject (str nsym) :branch br :unused unused
                              :fix {:fn 'seon.code.edit/prune-requires! :args [nsym]}
                              :message (str "Unused :require(s) in " nsym ": " (str/join ", " unused)
                                            ". Fix automatically via (user/prune-requires! '" nsym ") or (user/autofix-health!).")})))))))
         (batch-cache-put! form-static-cache 2048 @new-f)
         (batch-cache-put! ns-unused-req-cache 256 @new-r)
         (vec (concat (sort-by :subject @oversized)
                      (sort-by :subject @nested)
                      (sort-by :subject @unused-reqs)
                      (sort-by :subject @large-fns))))))))

(defn- load-persisted-metrics [db scope forms-by-ns]
  (let [pinfo (java.util.HashMap. 256)]
    (when (contains? (:schema db) :seon.code.form/chars)
      (if scope
        (doseq [[_ fs] forms-by-ns, {:keys [e key chars lines nested-scan?]} fs
                :when (and (some? chars) (not (and (string? key) (str/ends-with? key "#ns"))))]
          (.put pinfo e (metrics->info {:seon.code.form/chars chars
                                        :seon.code.form/lines lines
                                        :seon.code.form/nested-scan? nested-scan?})))
        (let [eid->lines (java.util.HashMap. 1400)
              eid->nested (java.util.HashMap. 1400)]
          (when (contains? (:schema db) :seon.code.form/lines)
            (doseq [dt (d/datoms db :aevt :seon.code.form/lines)] (.put eid->lines (:e dt) (:v dt))))
          (when (contains? (:schema db) :seon.code.form/nested-scan?)
            (doseq [dt (d/datoms db :aevt :seon.code.form/nested-scan?)] (.put eid->nested (:e dt) (:v dt))))
          (doseq [dt (d/datoms db :aevt :seon.code.form/chars)
                  :let [e (:e dt)]]
            (.put pinfo e (metrics->info {:seon.code.form/chars (:v dt)
                                          :seon.code.form/lines (.get eid->lines e)
                                          :seon.code.form/nested-scan? (.get eid->nested e)}))))))
    pinfo))