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
            [seon.code.parse :as parse]))

(defn ns-in-scope?
  "True when namespace symbol `nsym` belongs to `scope` (exact match or descendant `scope.*`,
   with `'seon` matching all `seon.*` namespaces)."
  [scope nsym]
  (when (and scope nsym)
    (let [sc (name scope)
          ns-s (name nsym)]
      (or (= ns-s sc)
          (str/starts-with? ns-s (str sc "."))))))

(defn- full-db-scan-form? [x]
  (and (seq? x) (symbol? (first x))
       (let [nm (name (first x))]
         (or (and (= "datoms" nm) (= :aevt (nth x 2 nil)))
             (= "q" nm)))))

(defn- nested-db-scan-in-form?
  "True when a form executes `d/datoms :aevt` or `d/q` inside a loop body (`O(N * M)`)."
  ([x] (nested-db-scan-in-form? x 0))
  ([x depth]
   (cond
     (full-db-scan-form? x) (pos? depth)
     (and (seq? x) (symbol? (first x)))
     (let [nm (name (first x))]
       (cond
         (contains? #{"for" "doseq"} nm)
         (let [binds (second x)
               body (nnext x)
               bind-hit (when (vector? binds)
                          (loop [bs (seq binds) d depth]
                            (when bs
                              (let [k (first bs) v (second bs)]
                                (if (contains? #{:let :when :while} k)
                                  (or (nested-db-scan-in-form? v d) (recur (nnext bs) d))
                                  (or (nested-db-scan-in-form? v d) (recur (nnext bs) (inc d))))))))]
           (or bind-hit (boolean (some #(nested-db-scan-in-form? % (inc depth)) body))))
         (contains? #{"map" "mapv" "mapcat" "keep" "reduce" "run!" "filter" "filterv" "remove"} nm)
         (or (nested-db-scan-in-form? (second x) (inc depth))
             (boolean (some #(nested-db-scan-in-form? % depth) (nnext x))))
         :else
         (boolean (some #(nested-db-scan-in-form? % depth) (rest x)))))
     (coll? x) (boolean (some #(nested-db-scan-in-form? % depth) x))
     :else false)))

(defonce ^:private ^:seon.flow/edge nested-scan-cache
(store/lru-cache 2048))

(defn- cached-nested-db-scan? [form-hash s]
(let [compute (fn []
                (let [form (try (read-string s) (catch Throwable _ nil))]
                  (boolean (and form (nested-db-scan-in-form? form 0)))))]
  (if form-hash
    (store/lru-get! nested-scan-cache 2048 form-hash compute)
    (compute))))

(defn static-efficiency-warnings
  "Scans non-test `:seon.code.form/src` (indexed `:avet :seon.code.form/ns` when restricted to `scope`) to flag:
   1. `:oversized-namespace` (`> 8000` estimated tokens).
   2. `:nested-db-scan` (`d/datoms :aevt` or `d/q` inside a loop body — `O(N * M)` waste).
   Caches AST parse results per deterministic `:seon.code.form/hash`."
  {:malli/schema [:function [:=> [:cat :any] [:vector :map]] [:=> [:cat :any [:maybe :symbol]] [:vector :map]]]}
  ([db] (static-efficiency-warnings db nil))
  ([db scope]
   (if-not (and db (contains? (:schema db) :seon.code.form/src))
     []
     (let [br (get-in db [:config :branch] :main)
         chars-by-ns (volatile! {})
         hdr-by-ns (volatile! {})
         bodies-by-ns (volatile! {})
         nested (volatile! [])
         large-fns (volatile! [])
         check-one! (fn [n k h s]
                      (vswap! chars-by-ns update n (fnil + 0) (count s))
                      (if (and (string? k) (str/ends-with? k "#ns"))
                        (vswap! hdr-by-ns assoc n s)
                        (do
                          (vswap! bodies-by-ns update n (fnil conj []) s)
                          (let [tr (str/triml s)]
                            (when (and k (or (str/starts-with? tr "(defn ") (str/starts-with? tr "(defn- ")))
                              (let [lines (loop [i 0, n 1] (let [idx (.indexOf ^String s "\n" i)] (if (neg? idx) n (recur (inc idx) (inc n)))))]
                                (when (> lines 120)
                                  (vswap! large-fns conj
                                          (cond-> {:class :large-function
                                                   :severity :warning
                                                   :kind :static
                                                   :ns n
                                                   :subject k
                                                   :branch br
                                                   :message (str "Large function " k " (" lines " lines > 120-line budget). Extract pure helpers to keep edits fast and testable.")}
                                            (not (str/includes? k "#")) (assoc :var (symbol k))))))))))
                      (when (and k
                                 (not (contains? #{"seon.health/schema-violations" "seon.health.static/schema-violations"} k))
                                 (or (str/includes? s ":aevt") (str/includes? s "/q "))
                                 (cached-nested-db-scan? h s))
                        (vswap! nested conj
                                (cond-> {:class :nested-db-scan
                                         :severity :warning
                                         :kind :static
                                         :ns n
                                         :subject k
                                         :branch br
                                         :message (str "Nested full DB scan (`d/datoms :aevt` or `d/q`) inside a loop in " k
                                                       ". Hoist the scan outside the loop or use an indexed `:avet` lookup.")}
                                  (not (str/includes? k "#")) (assoc :var (symbol k))))))]
     (if scope
       (doseq [nsym (->> (d/datoms db :aevt :seon.code.ns/name)
                         (map :v)
                         (filter #(and (not (str/ends-with? (name %) "-test")) (ns-in-scope? scope %))))
               dt (d/datoms db :avet :seon.code.form/ns nsym)
               :let [p (d/pull db '[:seon.code.form/key :seon.code.form/hash :seon.code.form/src] (:e dt))]]
         (check-one! nsym (:seon.code.form/key p) (:seon.code.form/hash p) (str (:seon.code.form/src p))))
       (let [ns-by-e (reduce (fn [a dt] (assoc a (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.form/ns))
             key-by-e (reduce (fn [a dt] (assoc a (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.form/key))
             hash-by-e (if (contains? (:schema db) :seon.code.form/hash)
                         (reduce (fn [a dt] (assoc a (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.form/hash))
                         {})]
         (doseq [dt (d/datoms db :aevt :seon.code.form/src)
                 :let [e (:e dt)
                       n (get ns-by-e e)]
                 :when (and n (not (str/ends-with? (name n) "-test")))]
           (check-one! n (get key-by-e e) (get hash-by-e e) (str (:v dt))))))
     (let [oversized (->> @chars-by-ns
                          (keep (fn [[n ch]]
                                  (let [toks (quot ch 4)]
                                    (when (> toks 8000)
                                      {:class :oversized-namespace
                                       :severity :warning
                                       :kind :static
                                       :ns n
                                       :subject (str n)
                                       :branch br
                                       :message (str "Namespace " n " is ~" toks " tokens (> 8k cap). "
                                                     "Split into cohesive 2k-6k tok child namespaces so specialists load cleanly.")}))))
                          (sort-by :subject))
           unused-reqs
           (->> @hdr-by-ns
                (keep (fn [[n hdr]]
                        (let [decl (try (parse/parse-ns-decl (read-string hdr)) (catch Throwable _ nil))
                              body (str/join "\n" (get @bodies-by-ns n []))
                              lib-aliases (reduce-kv (fn [a al lib] (update a lib (fnil conj []) (name al))) {} (:aliases decl))
                              lib-refers  (reduce-kv (fn [a rf lib] (update a lib (fnil conj []) (name rf))) {} (:refers decl))
                              unused (vec
                                      (sort
                                       (keep (fn [lib]
                                               (let [als (get lib-aliases lib)
                                                     refs (get lib-refers lib)]
                                                 (when (or (seq als) (seq refs))
                                                   (let [used? (or (.contains body (str (name lib) "/"))
                                                               (.contains body (str (name lib) "."))
                                                               (some #(.contains body (str % "/")) als)
                                                               (some #(.contains ^String body ^String %) refs))]
                                                     (when-not used? lib)))))
                                             (:requires decl))))]
                          (when (seq unused)
                            {:class :unused-require
                             :severity :warning
                             :kind :static
                             :ns n
                             :subject (str n)
                             :branch br
                             :unused unused
                             :fix {:fn 'seon.code.edit/prune-requires! :args [n]}
                             :message (str "Unused :require(s) in " n ": " (str/join ", " unused)
                                           ". Fix automatically via (user/prune-requires! '" n ") or (user/autofix-health!).")}))))
                (sort-by :subject))]
       (vec (concat oversized (sort-by :subject @nested) unused-reqs (sort-by :subject @large-fns))))))))

(defn test-efficiency-warnings
  "Checks latest passing test verdicts per `:seon.test/var` for `:slow-test` and `:cost-regression`."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :map]]}
  [db]
  (if-not (and db (contains? (:schema db) :seon.test/var))
    []
    (let [br (get-in db [:config :branch] :main)
          vs (reduce (fn [a dt] (assoc a (:e dt) [(:v dt) (:tx dt)])) {} (d/datoms db :aevt :seon.test/var))
          ms (if (contains? (:schema db) :seon.test/ms)
               (reduce (fn [a dt] (assoc a (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.test/ms))
               {})
          tk (if (contains? (:schema db) :seon.test/ticks)
               (reduce (fn [a dt] (assoc a (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.test/ticks))
               {})
          by (if (contains? (:schema db) :seon.test/bytes)
               (reduce (fn [a dt] (assoc a (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.test/bytes))
               {})
          ps (if (contains? (:schema db) :seon.test/pass?)
               (reduce (fn [a dt] (assoc a (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.test/pass?))
               {})
          by-var (->> vs
                      (keep (fn [[e [v tx]]]
                              (when (and v (get ps e))
                                {:var v :tx tx
                                 :ms (double (or (get ms e) 0.0))
                                 :ticks (long (or (get tk e) 0))
                                 :bytes (long (or (get by e) 0))})))
                      (group-by :var))]
      (->> by-var
           (mapcat
            (fn [[vsym rows]]
              (let [ord (vec (sort-by :tx rows))
                    cur (peek ord)
                    nsym (when (namespace vsym) (symbol (namespace vsym)))
                    slow (when (> (:ms cur) 1000.0)
                           [{:class :slow-test
                             :severity :warning
                             :kind :live
                             :ns nsym
                             :var vsym
                             :subject (str vsym)
                             :branch br
                             :message (str "Slow test " vsym ": " (Math/round (:ms cur)) " ms, "
                                           (quot (:bytes cur) 1048576) " MB allocated (> 1000 ms budget).")}])
                    reg (when (>= (count ord) 2)
                          (let [prev (nth ord (- (count ord) 2))
                              dticks (- (:ticks cur) (:ticks prev))
                              dbytes (- (:bytes cur) (:bytes prev))
                              dms (- (:ms cur) (:ms prev))
                              tr (when (pos? (:ticks prev)) (/ (double (:ticks cur)) (:ticks prev)))
                              bratio (when (pos? (:bytes prev)) (/ (double (:bytes cur)) (:bytes prev)))
                              mratio (when (> (:ms prev) 20.0) (/ (double (:ms cur)) (:ms prev)))]
                          (when (or (and tr (> tr 2.5) (> dticks 5000000))
                                    (and bratio (> bratio 2.5) (> dbytes 33554432))
                                    (and mratio (> mratio 2.5) (> dms 200.0)))
                            [{:class :cost-regression
                              :severity :warning
                              :kind :live
                              :ns nsym
                              :var vsym
                              :subject (str vsym)
                              :branch br
                              :message (str "Cost regression in " vsym ": "
                                            (when (and tr (> tr 2.5))
                                              (str (format "%.1fx" tr) " ticks "))
                                            (when (and bratio (> bratio 2.5))
                                              (str (format "%.1fx" bratio) " bytes "))
                                            (when (and mratio (> mratio 2.5) (> dms 200.0))
                                              (str (format "%.1fx" mratio) " ms "))
                                            "(" (Math/round (:ms prev)) " ms -> " (Math/round (:ms cur)) " ms).")}])))]
                (concat slow reg))))
           (sort-by :subject)
           vec))))

(defn eval-efficiency-warnings
  "Inspects active agent eval records on `branch-db` (optionally scoped to `agent-id` and/or
   reusing already-fetched `prefetched-evals` to avoid redundant `d/pull` scans) for
   `:low-cache-hit`, `:prefix-cache-bust`, and `:heavy-eval`."
  {:malli/schema [:function
                  [:=> [:cat :any] [:vector :map]]
                  [:=> [:cat :any :any] [:vector :map]]
                  [:=> [:cat :any :any :any] [:vector :map]]]}
  ([branch-db] (eval-efficiency-warnings branch-db nil nil))
  ([branch-db agent-id] (eval-efficiency-warnings branch-db agent-id nil))
  ([branch-db agent-id prefetched-evals]
   (if-not (or (seq prefetched-evals)
               (and branch-db (contains? (:schema branch-db) :seon.agent.eval/agent)))
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
                                      :seon.agent.eval/cache-miss-tokens]
                                    %)
                           eids)))
           by-ag (group-by #(or (:seon.agent.eval/agent %) agent-id) evals)]
       (->> by-ag
            (mapcat
             (fn [[aid es]]
               (let [cache-es (filter #(or (pos? (long (or (:seon.agent.eval/cache-hit-tokens %) 0)))
                                           (pos? (long (or (:seon.agent.eval/cache-miss-tokens %) 0))))
                                      es)
                     hits (reduce + 0 (map #(long (or (:seon.agent.eval/cache-hit-tokens %) 0)) cache-es))
                     miss (reduce + 0 (map #(long (or (:seon.agent.eval/cache-miss-tokens %) 0)) cache-es))
                     tot (+ hits miss)
                     cache-warn (when (and (>= (count cache-es) 2) (>= tot 2000) (< (/ (double hits) tot) 0.60))
                                  [{:class :low-cache-hit
                                    :severity :warning
                                    :kind :live
                                    :agent aid
                                    :subject (str aid)
                                    :branch br
                                    :message (str "Low LLM KV-cache hit rate on " aid ": "
                                                  (Math/round (* 100.0 (/ (double hits) tot))) "% ("
                                                  hits "/" tot " tok across " (count cache-es) " turns). "
                                                  "Check for dynamic data leaking into static <walk-context>.")}])
                     turn-rows (->> es
                                    (filter #(or (nil? (:seon.agent.eval/call-index %))
                                                 (zero? (long (:seon.agent.eval/call-index %)))))
                                    (filter #(or (:seon.agent.eval/prefix-hash %)
                                                 (:seon.agent.eval/cache-hit-tokens %)))
                                    (sort-by #(long (or (:seon.agent.eval/turn %) 0))))
                     busts (vec
                            (keep
                             (fn [[prev cur]]
                               (let [t1 (long (or (:seon.agent.eval/turn prev) 0))
                                     t2 (long (or (:seon.agent.eval/turn cur) 0))
                                     p1 (:seon.agent.eval/prefix-hash prev)
                                     p2 (:seon.agent.eval/prefix-hash cur)
                                     h1 (:seon.agent.eval/cache-hit-tokens prev)
                                     h2 (:seon.agent.eval/cache-hit-tokens cur)
                                     hash-changed? (and (some? p1) (some? p2) (not= p1 p2))
                                     hit-dropped? (and (number? h1) (number? h2)
                                                       (> (long h1) 512)
                                                       (< (long h2) (- (long h1) 256)))]
                                 (when (and (= t2 (inc t1)) (or hash-changed? hit-dropped?))
                                   {:turn t2
                                    :prev-hash p1 :cur-hash p2
                                    :prev-hit h1 :cur-hit h2
                                    :reason (if hash-changed? :prefix-hash-changed :cache-hit-drop)})))
                             (partition 2 1 turn-rows)))
                     bust-warn (when (seq busts)
                                 (let [last-b (peek busts)]
                                   [{:class :prefix-cache-bust
                                     :severity :warning
                                     :kind :live
                                     :agent aid
                                     :subject (str aid "/t" (:turn last-b))
                                     :branch br
                                     :bust-count (count busts)
                                     :message (str "KV-cache prefix bust on " aid " (" (count busts) "x, latest turn " (:turn last-b)
                                                   (if (= :prefix-hash-changed (:reason last-b))
                                                     (str ": prefix-hash " (:prev-hash last-b) " -> " (:cur-hash last-b))
                                                     (str ": cache-hit dropped " (:prev-hit last-b) " -> " (:cur-hit last-b) " tok"))
                                                   "). Static <walk-context> mutated mid-session.")}]))
                     heavy-warn (->> es
                                     (filter (fn [ev]
                                               (or (> (long (or (:seon.agent.eval/bytes ev) 0)) 134217728)
                                                   (> (long (or (:seon.agent.eval/ticks ev) 0)) 50000000)
                                                   (str/includes? (str (:seon.agent.eval/error ev)) "Eval exceeded"))))
                                     (take-last 2)
                                     (map (fn [ev]
                                            {:class :heavy-eval
                                             :severity :warning
                                             :kind :live
                                             :agent aid
                                             :subject (str aid "/" (or (:seon.agent.eval/handle ev) (str "t" (:seon.agent.eval/turn ev))))
                                             :branch br
                                             :message (str "Heavy eval " (:seon.agent.eval/handle ev) " on " aid ": "
                                                           (quot (long (or (:seon.agent.eval/bytes ev) 0)) 1048576) " MB, "
                                                           (long (or (:seon.agent.eval/ticks ev) 0)) " ticks"
                                                           (when (:seon.agent.eval/error ev)
                                                             (str " (" (:seon.agent.eval/error ev) ")")))})))]
                 (concat cache-warn bust-warn heavy-warn))))
            vec)))))

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