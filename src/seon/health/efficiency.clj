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
  "Inspects active agent eval records on `branch-db` (optionally scoped to `agent-id`) for
   `:low-cache-hit`, `:prefix-cache-bust`, and `:heavy-eval`."
  {:malli/schema [:function
                  [:=> [:cat :any] [:vector :map]]
                  [:=> [:cat :any :any] [:vector :map]]]}
  ([branch-db] (eval-efficiency-warnings branch-db nil))
  ([branch-db agent-id]
   (if-not (and branch-db (contains? (:schema branch-db) :seon.agent.eval/agent))
     []
     (let [br (get-in branch-db [:config :branch] :main)
           eids (if agent-id
                  (mapv :e (d/datoms branch-db :avet :seon.agent.eval/agent agent-id))
                  (mapv :e (d/datoms branch-db :aevt :seon.agent.eval/agent)))
           evals (mapv #(d/pull branch-db
                                '[:seon.agent.eval/agent :seon.agent.eval/handle :seon.agent.eval/turn
                                  :seon.agent.eval/call-index :seon.agent.eval/prefix-hash
                                  :seon.agent.eval/ms :seon.agent.eval/ticks :seon.agent.eval/bytes
                                  :seon.agent.eval/error :seon.agent.eval/cache-hit-tokens
                                  :seon.agent.eval/cache-miss-tokens]
                                %)
                       eids)
           by-ag (group-by :seon.agent.eval/agent evals)]
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

(def ^:private learning-metrics-cache (store/lru-cache 8))

(defn classify-eval-error
  "Classifies a REPL eval error string into a stable failure class for trend & hill-climbing tracking."
  {:malli/schema [:=> {:examples [[["Eval exceeded allocation budget (limit 268435456 bytes)."] :alloc-budget-overrun]
                                   [["Wrong number of args (2) passed to: user/callers"] :wrong-arity]]}
                  [:cat :string] :keyword]
   :cost/ticks 200000
   :cost/bytes 524288}
  [err-str]
  (let [e (or err-str "")]
    (cond
      (str/includes? e "allocation budget")         :alloc-budget-overrun
      (str/includes? e "tick budget")               :tick-budget-overrun
      (str/includes? e "Schema contract violation") :schema-contract-violation
      (str/includes? e "Wrong number of args")      :wrong-arity
      (or (str/includes? e "No such var")
          (str/includes? e "Unable to resolve"))    :unresolved-var
      (or (str/includes? e "Reader syntax error")
          (str/includes? e "Unmatched delimiter")
          (str/includes? e "EOF while reading"))    :reader-syntax
      (or (str/includes? e "cannot be cast")
          (str/includes? e "find not supported"))   :type-mismatch
      (str/includes? e "is not public")             :private-var
      :else                                         :other)))

(defn- pct-val [xs p]
  (let [s (vec (sort xs)) n (count s)]
    (if (zero? n) 0.0 (double (nth s (min (dec n) (long (* p n))))))))

(defn- extract-tool-names [code]
  (let [s (str code)
        ms (distinct (map second (re-seq #"user/([a-zA-Z0-9\-\!\?\*]+)" s)))]
    (cond
      (seq ms) (vec ms)
      (str/includes? s "(d/q ") ["d/q"]
      (str/includes? s "(d/datoms ") ["d/datoms"]
      (str/includes? s "(defn ") ["raw-defn"]
      (str/includes? s "(deftest ") ["raw-deftest"]
      :else ["other-clj"])))

(def ^:private discovery-tools #{"source" "grep-src" "ns-outline" "ns-requires" "search" "callers" "callees" "tests-for" "db" "main-db" "d/q" "d/datoms" "past-evals" "history" "health" "help"})

(def ^:private action-tools #{"edit-fn!" "add-fn!" "move-vars!" "remove-var!" "set-meta!" "add-require!" "prune-requires!" "register-schema!" "run-tests!" "ready!" "autofix-health!" "canvas!" "answer!" "raw-defn" "raw-deftest"})

(defn agent-learning-metrics
  "Derived anti-gaming sliding-window task completion & tool-frustration scorecard over the latest
   `window` (default 10) autonomous agents in Datahike. Cached per `(store-id, max-tx, window)`."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/conn :seon.host/db] :map]
                  [:=> [:cat :seon.host/conn :seon.host/db :map] :map]]}
  ([conn db] (agent-learning-metrics conn db {}))
  ([conn db {:keys [window] :or {window 10}}]
   (let [w (long (max 1 (min 30 (or window 10))))
         ck [(get-in db [:config :store :id]) (:max-tx db) w]]
     (store/lru-get!
      learning-metrics-cache ck
      (fn []
        (let [all-brs (set (d/branches conn))
              ags (->> (d/datoms db :aevt :seon.agent/id)
                       (map #(d/pull db '[:db/id :seon.agent/id :seon.agent/role :seon.agent/branch
                                          :seon.agent/ready? :seon.agent/merged-tx] (:e %)))
                       (remove #(or (= :repl (:seon.agent/role %))
                                    (= "repl" (namespace (:seon.agent/id %)))
                                    (= "example" (namespace (:seon.agent/id %)))
                                    (= :agent/root (:seon.agent/id %))))
                       (sort-by (comp - :db/id)))
              chosen (volatile! [])
              _ (doseq [a ags
                        :while (< (count @chosen) w)
                        :let [aid (:seon.agent/id a)
                              br (:seon.agent/branch a)]
                        :when (contains? all-brs br)
                        :let [bdb (try (store/head conn br) (catch Throwable _ nil))]
                        :when (and bdb (contains? (:schema bdb) :seon.agent.eval/agent))
                        :let [eids (mapv :e (d/datoms bdb :avet :seon.agent.eval/agent aid))]
                        :when (pos? (count eids))]
                  (let [es (->> eids
                                (map #(d/pull bdb '[:seon.agent.eval/turn :seon.agent.eval/code
                                                    :seon.agent.eval/error :seon.agent.eval/bytes
                                                    :seon.agent.eval/prefix-hash
                                                    :seon.agent.eval/cache-hit-tokens
                                                    :seon.agent.eval/cache-miss-tokens] %))
                                (sort-by #(long (or (:seon.agent.eval/turn %) 0)))
                                vec)
                        turns (count (distinct (keep :seon.agent.eval/turn es)))
                        errs (keep :seon.agent.eval/error es)
                        hits (reduce + 0 (map #(long (or (:seon.agent.eval/cache-hit-tokens %) 0)) es))
                        miss (reduce + 0 (map #(long (or (:seon.agent.eval/cache-miss-tokens %) 0)) es))
                        phashes (distinct (keep :seon.agent.eval/prefix-hash es))
                        busts (max 0 (dec (count phashes)))]
                    (vswap! chosen conj
                            {:id aid :evals (count es) :turns turns :errs (count errs)
                             :merged? (boolean (:seon.agent/merged-tx a))
                             :ready? (boolean (:seon.agent/ready? a))
                             :hits hits :miss miss :prefix-busts busts
                             :eval-rows es})))
              rows (vec (reverse @chosen))
              attempted (count rows)
              merged-rows (filter :merged? rows)
              merged (count merged-rows)
              tot-turns (reduce + 0 (map :turns rows))
              tot-evals (reduce + 0 (map :evals rows))
              tot-errs (reduce + 0 (map :errs rows))
              tot-hits (reduce + 0 (map :hits rows))
              tot-miss (reduce + 0 (map :miss rows))
              tot-tok (+ tot-hits tot-miss)
              tot-busts (reduce + 0 (map :prefix-busts rows))
              all-err-strs (mapcat #(keep :seon.agent.eval/error (:eval-rows %)) rows)
              err-classes (into (sorted-map) (frequencies (map classify-eval-error all-err-strs)))
              oom-count (get err-classes :alloc-budget-overrun 0)
              ;; Per-tool frustration & discovery/action ratio
              acc-tools
              (reduce
               (fn [acc r]
                 (let [es (:eval-rows r)]
                   (reduce
                    (fn [a [idx e]]
                      (let [ts (extract-tool-names (:seon.agent.eval/code e))
                            prev-ts (when (pos? idx) (extract-tool-names (:seon.agent.eval/code (nth es (dec idx)))))
                            err (:seon.agent.eval/error e)
                            oom? (= :alloc-budget-overrun (when err (classify-eval-error err)))
                            mb (/ (double (long (or (:seon.agent.eval/bytes e) 0))) 1048576.0)
                            disc? (boolean (some discovery-tools ts))
                            act? (boolean (some action-tools ts))
                            a1 (cond-> a
                                 (and disc? (not act?)) (update :disc inc)
                                 act? (update :act inc))]
                        (reduce
                         (fn [m t]
                           (let [ret? (boolean (some #{t} prev-ts))
                                 cur (get-in m [:tools t] {:tool t :calls 0 :errs 0 :ooms 0 :retries 0 :mbs []})]
                             (assoc-in m [:tools t]
                                       (-> cur
                                           (update :calls inc)
                                           (update :errs + (if err 1 0))
                                           (update :ooms + (if oom? 1 0))
                                           (update :retries + (if ret? 1 0))
                                           (update :mbs conj mb)))))
                         a1 ts)))
                    acc (map-indexed vector es))))
               {:disc 0 :act 0 :tools {}} rows)
              top-tools (->> (vals (:tools acc-tools))
                             (filter #(>= (:calls %) 2))
                             (map (fn [{:keys [tool calls errs ooms retries mbs]}]
                                    (let [ep (Math/round (* 100.0 (/ (double errs) calls)))
                                          op (Math/round (* 100.0 (/ (double ooms) calls)))
                                          rp (Math/round (* 100.0 (/ (double retries) calls)))
                                          fr (Math/round (+ (* 3.0 ep) (* 2.0 op) (* 0.5 rp)))]
                                      {:tool tool :calls calls :err% ep :oom% op :ret% rp
                                       :p95-mb (Math/round (pct-val mbs 0.95))
                                       :frustration fr})))
                             (sort-by (comp - :frustration))
                             (take 6)
                             vec)]
          {:window w
           :attempted attempted
           :merged merged
           :merge-rate-pct (if (pos? attempted) (Math/round (* 100.0 (/ (double merged) attempted))) 0)
           :total-turns tot-turns
           :effective-turns-per-merged (when (pos? merged) (/ (Math/round (* 10.0 (/ (double tot-turns) merged))) 10.0))
           :median-turns-merged (when (pos? merged) (Math/round (pct-val (map :turns merged-rows) 0.50)))
           :total-evals tot-evals
           :avg-evals-per-turn (if (pos? tot-turns) (/ (Math/round (* 100.0 (/ (double tot-evals) tot-turns))) 100.0) 0.0)
           :eval-error-rate-pct (if (pos? tot-evals) (Math/round (* 100.0 (/ (double tot-errs) tot-evals))) 0)
           :oom-rate-pct (if (pos? tot-evals) (Math/round (* 100.0 (/ (double oom-count) tot-evals))) 0)
           :discovery-to-action-ratio (when (pos? (:act acc-tools))
                                        (/ (Math/round (* 100.0 (/ (double (:disc acc-tools)) (:act acc-tools)))) 100.0))
           :kv-cache-hit-pct (when (pos? tot-tok) (Math/round (* 100.0 (/ (double tot-hits) tot-tok))))
           :prefix-busts tot-busts
           :error-classes err-classes
           :top-frustrating-tools top-tools
           :recent-agents (mapv #(dissoc % :eval-rows :hits :miss) rows)}))))))