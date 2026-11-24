(ns seon.health.friction
  "Fast (< 30 ms / < 30 MB) trace friction miner, zero-sample renderer & zero-`:src` static-debt scans."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [datahike.datom :as dd]
            [datahike.index.interface :as di]
            [konserve.core :as k]
            [seon.code.mine :as mine]
            [seon.health.metrics :as hm]
            [seon.health.static :as hs]
            [seon.render.text :refer [one-line round1]]
            [seon.store :as st :refer [db-cache-sig db-schema]]))

(defonce ^:private ^:seon.flow/edge cache* (st/lru-cache 64))

(def ^:private broad-c #{"*" "Throwable" "java.lang.Throwable" "Exception" "java.lang.Exception"})

(def ^:private wts {:ooms 25 :rogue-fs-count 25 :upstream-errors 15 :prefix-busts 12 :empty-code-turns 8
                    :repeated-errors 8 :no-match-count 6 :cst-errors 6 :test-failure-messages 5 :heavy-evals 4 :eval-errors 3})

(defn- eav1 [db e a]
  (:v (first (d/datoms db :eavt e a))))

(defn- pull-ag [db aid]
  (when (and db ((db-schema db) :seon.agent/id) (first (d/datoms db :avet :seon.agent/id aid)))
    (d/pull db [:db/id :seon.agent/id :seon.agent/branch :seon.agent/interactive? :seon.agent/merged-tx :seon.agent/ready?] [:seon.agent/id aid])))

(defn- lstr [db v]
  (if-not (dd/lazy-value? v) (when (some? v) (str v))
    (let [r (when-let [s (:store db)] (k/get s (dd/lazy-key v) ::m {:sync? true}))]
      (str (if (and r (not= ::m r)) r (dd/value-of v))))))

(defn- cfg-of [db aid o]
  (let [g #(or (o %1) (when db (st/config-val db (when (= %2 :seon.config/max-tool-turns) aid) %2)) %3)]
    {:mtt (long (g :max-tool-turns :seon.config/max-tool-turns 32)) :hb (long (g :heavy-eval-bytes :seon.config/heavy-eval-bytes 134217728))
     :hm (double (g :heavy-eval-ms :seon.config/heavy-eval-ms 5000)) :kv (double (g :min-kv-pct :seon.config/friction-min-kv-pct 70))}))

(defn- fnd [k sl sc t c i]
  {:id (keyword "hygiene" (mine/normalize-hygiene-slug sl)) :kind k :scope sc :title t :summary t :curated c :impact (long i)})

(def ^:private r-rog #"\(\s*(?:clojure\.core/)?spit\b|(?:jolt\.|(?<![\w-]))fs/write\b|File(?:Writer|OutputStream)\.|Files/write|io/writer\b|\bgit\s+(?:commit|add|push)\b|\bbin/build\b")

(def ^:private r-safe #"user/(?:add-fn!|edit-fn!|ui-patch!|fs-write|sh!)")

(def ^:private r-edit #"user/(?:edit-fn!|ui-patch!|add-fn!|remove-var!|move-vars!)|patch-form")

(def ^:private r-cst #"(?i):(?:cst-parse-failed|unbalanced|source-sync-mismatch|form-not-found|ambiguous-match|invalid-syntax)|rewrite-clj|reader syntax error|unmatched delimiter|eof while reading")

(defn- cnt [db a v p]
  (if (and db ((db-schema db) a)) (count (filter p (d/datoms db :avet a v))) 0))

(defn score-agent-friction
  "Pure friction score and level."
  {:malli/schema [:=> [:cat :map [:? number?]] [:map [:friction-score [:int {:min 0}]] [:level :seon.signal/level]]] :cost/ticks 4000}
  ([s] (score-agent-friction s 70.0))
  ([s mkv]
   (let [n #(max 0 (long (or (get s %) 0))) ov? (boolean (:turn-overrun? s)) kv (:kv-cache-hit-pct s)
         lo? (and (number? kv) (>= (+ (n :cache-hit-tokens) (n :cache-miss-tokens)) 2000) (< (double kv) (double mkv)))
         sc (+ (if ov? 35 0) (if lo? 15 0) (reduce-kv #(+ %1 (* %3 (n %2))) 0 wts))]
     {:friction-score (long sc) :level (cond (or ov? (pos? (n :ooms)) (pos? (n :rogue-fs-count)) (>= sc 40)) :hot (>= sc 12) :warm :else :calm)})))

(defn- step-eval [bdb hb hm a dt]
  (let [e (:e dt) I #(update %1 %2 inc) L #(long (or % 0))
        {:seon.agent.eval/keys [turn call-index bytes ms prompt-tokens completion-tokens cache-hit-tokens cache-miss-tokens handle code error value prefix-hash]}
        (persistent! (reduce #(if (= :seon.agent.eval/reasoning (:a %2)) %1 (assoc! %1 (:a %2) (:v %2))) (transient {}) (d/datoms bdb :eavt e)))
        tv (L turn) ci (L call-index) bv (L bytes) ht (L cache-hit-tokens) mv (double (or ms 0.0))
        cd (or (some->> code (lstr bdb)) "") er (some->> error (lstr bdb))
        vs (cond (nil? value) nil (not (dd/lazy-value? value)) (str value) (re-find r-edit cd) (lstr bdb value))
        ph (not-empty (str/trim (str (or prefix-hash "")))) er? (and er (not (str/blank? er)))
        tx (when (or er? vs) (str er " " vs)) cls (when er? (hm/classify-eval-error er)) ord [tv ci e]
        sig (when er? (-> (first (str/split-lines er)) str/trim (str/replace #"[0-9a-f]{8}-[0-9a-f-]{27}|0x[0-9a-f]+|\d+" "#")))]
    (cond-> (-> a (I :evals) (update :max-turn max tv) (update :prompt-tokens + (L prompt-tokens)) (update :completion-tokens + (L completion-tokens))
                (update :cache-hit-tokens + ht) (update :cache-miss-tokens + (L cache-miss-tokens)) (update :max-mb max bv) (update :max-ms max mv))
      (pos? tv) (update :ts conj tv) (str/blank? cd) (I :empty-code-turns) ph (update :ps conj ph)
      (and (zero? ci) (or ph (pos? ht))) (update :tr conj [tv e ph ht])
      (or (not (:lk a)) (pos? (compare ord (:lk a)))) (assoc :lk ord :lv [handle cd])
      er? (-> (I :eval-errors) (update-in [:error-classes cls] (fnil inc 0)) (update-in [:sg sig] (fnil inc 0)))
      (and er? (or (not (:ek a)) (pos? (compare ord (:ek a))))) (assoc :ek ord :le [handle er])
      (= :alloc-budget-overrun cls) (I :ooms) (or (= :reader-syntax cls) (and tx (re-find r-cst tx))) (I :cst-errors)
      (and tx (re-find #":no-match\b" tx)) (I :no-match-count) (and (seq cd) (re-find r-rog cd) (not (re-find r-safe cd))) (I :rogue-fs-count)
      (or (> bv hb) (> mv hm)) (I :heavy-evals))))

(defn- analyze-impl [bdb aid o]
  (let [mdb (or (:main-db o) bdb) {:keys [mtt hb hm kv]} (or (:cfg o) (cfg-of mdb aid o)) ag (or (:agent-ent o) (pull-ag mdb aid))
        a0 (into {:max-ms 0.0 :error-classes (sorted-map) :ts #{} :ps #{} :tr [] :sg {}} (map #(vector % 0) [:evals :max-turn :empty-code-turns :prompt-tokens :completion-tokens :cache-hit-tokens :cache-miss-tokens :eval-errors :ooms :cst-errors :no-match-count :rogue-fs-count :heavy-evals :max-mb]))
        a (if (and bdb ((db-schema bdb) :seon.agent.eval/agent)) (binding [di/*read-key-attrs* #{}] (reduce #(step-eval bdb hb hm %1 %2) a0 (d/datoms bdb :avet :seon.agent.eval/agent aid))) a0)
        nt (count (:ts a)) phs (count (:ps a)) ht (:cache-hit-tokens a) cm (:cache-miss-tokens a) pt (:prompt-tokens a)
        kvp (cond (pos? (+ ht cm)) (round1 (/ (* 100.0 ht) (+ ht cm))) (and (pos? pt) (pos? ht)) (round1 (/ (* 100.0 ht) pt)))
        bs (count (filter (fn [[[_ _ p1 h1] [_ _ p2 h2]]] (or (and p1 p2 (not= p1 p2)) (and (> h1 512) (< h2 (- h1 256))))) (partition 2 1 (sort-by (juxt first second) (:tr a)))))
        up (long (or (get (:up-map o) aid) (if (:up-map o) 0 (cnt mdb :seon.error/source :model #(= aid (eav1 mdb (:e %) :seon.error/agent))))))
        b (assoc (dissoc a :ts :ps :tr :sg :lk :lv :ek :le) :agent aid :branch (or (:branch o) (get-in bdb [:config :branch]) :main)
                 :turns nt :max-tool-turns mtt :turn-overrun? (boolean (and (pos? mtt) (or (> (:max-turn a) mtt) (> nt mtt))))
                 :kv-cache-hit-pct kvp :prefix-hash-count phs :prefix-busts (long (max bs (max 0 (dec phs))))
                 :repeated-errors (long (reduce + 0 (map #(max 0 (dec (long %))) (vals (:sg a)))))
                 :test-failure-messages (cnt mdb :seon.agent.message/to aid #(= :test-failure (eav1 mdb (:e %) :seon.agent.message/type)))
                 :upstream-errors up :max-mb (round1 (/ (double (:max-mb a)) 1048576.0)) :max-ms (round1 (:max-ms a))
                 :merged? (boolean (:seon.agent/merged-tx ag)) :ready? (boolean (:seon.agent/ready? ag))
                 :delegated-calls (cnt mdb :seon.agent.call/caller aid any?)
                 :last-snippet (when-let [[h c] (:lv a)] (str h ": " (one-line c 100)))
                 :last-error (when-let [[h e] (:le a)] (str h " ERR: " (one-line e 140))))]
    (merge b (score-agent-friction b kv))))

(defn analyze-agent-friction
  "Pure indexed lazy `:eavt` friction analyzer."
  {:malli/schema [:=> [:cat :seon.host/db :keyword [:? [:maybe :map]]] :seon.health/agent-friction] :cost/ticks 15000000}
  ([bdb aid] (analyze-agent-friction bdb aid nil))
  ([bdb aid opts] (st/without-agent-eval-reads #(analyze-impl bdb aid (or opts {})))))

(defn ^:seon.flow/edge agent-friction-summary
  "Resolves `aid`'s branch head and analyzes friction."
  {:malli/schema [:=> [:cat :seon.host/conn :seon.host/db :keyword [:? [:maybe :map]]] :seon.health/agent-friction] :cost/ticks 20000000}
  ([conn mdb aid] (agent-friction-summary conn mdb aid nil))
  ([conn mdb aid opts]
   (let [o (or opts {}) ag (pull-ag mdb aid) br (or (:branch o) (:seon.agent/branch ag) :main)]
     (analyze-agent-friction (or (when (and conn ((set (d/branches conn)) br)) (st/head conn br)) mdb) aid (assoc o :main-db mdb :branch br :agent-ent ag)))))

(defn- fast-scans [db r? s?]
  (let [dh (db-schema db) A #(when (and db (dh %)) (d/datoms db :aevt %)) E #(eav1 db %1 %2)]
    (concat
     (when (and r? db (dh :seon.schema/form) (dh :seon.code.var/input))
       (let [ek (into {} (map (juxt :e :v)) (A :seon.schema/key)) he (:e (first (d/datoms db :avet :seon.schema/key :seon.ui/hiccup)))
             po (set (for [d (when he (d/datoms db :avet :seon.code.var/output he)) :let [v (:e d)] :when (not-any? #(and (dh %) (E v %)) [:seon.code.var/ops :seon.code.var/private? :seon.code.var/test? :seon.code.var/flow-edge?])] v))]
         (for [d (A :seon.schema/form) :let [se (:e d) sk (ek se) f (:v d)] :when (and sk (vector? f) (= :map (first f)) (true? (:seon.db/attributes (second f))) (not-any? #(po (:e %)) (d/datoms db :avet :seon.code.var/input se)))]
           (fnd :missing-entity-renderer (str "renderer-" (namespace sk) "-" (name sk)) 'seon.render.entities (str "Render " sk) [[:seon.schema/key sk]] 72))))
     (when (and s? db (dh :seon.code.form/ns))
       (let [fn? (hs/foreign-nss db) ok? #(and (symbol? %) (not (fn? %)) (not (str/ends-with? (name %) "-test")))
             en (into {} (keep #(when (ok? (:v %)) [(:e %) (:v %)])) (A :seon.code.form/ns))]
         (concat
          (for [[n ch] (reduce #(if-let [n (en (:e %2))] (assoc %1 n (+ (get %1 n 0) (long (:v %2)))) %1) {} (A :seon.code.form/chars)) :let [tk (quot ch 4)] :when (> tk 8000)]
            (fnd :oversized-namespace (str "split-ns-" (name n)) n (str "Split " n " (~" tk "t)") [[:seon.code.ns/name n]] (+ 70 (min 40 (quot (- tk 8000) 100)))))
          (for [d (A :seon.code.form/lines) :let [l (long (:v d)) n (en (:e d)) k (when (and n (> l 120)) (E (:e d) :seon.code.form/key))] :when (and k (not (str/includes? k "#")))]
            (fnd :large-function (str "split-fn-" n "-" (name (symbol k))) n (str "Split " k " (" l "L)") [[:seon.code.var/name (symbol k)]] (+ 65 (min 40 (quot (- l 120) 5)))))
          (for [d (A :seon.code.form/nested-scan?) :when (true? (:v d)) :let [n (en (:e d)) k (when n (E (:e d) :seon.code.form/key))] :when (and k (not (#{"seon.health/schema-violations" "seon.health.static/schema-violations"} k)))]
            (fnd :nested-db-scan (str "nested-scan-" k) n (str "Fix scan " k) [(if (str/includes? k "#") [:seon.code.ns/name n] [:seon.code.var/name (symbol k)])] 78))
          (for [d (A :seon.code.var/catches) :let [e (:e d)] :when (and (not-any? #(E e %) [:seon.code.var/flow-edge? :seon.code.var/test?]) (some #(and (= :constant (:answers %)) (broad-c (:class %))) (:v d)))
                :let [vs (E e :seon.code.var/name) n (some-> vs namespace symbol)] :when (and vs (ok? n))]
            (fnd :silent-catch-all (str "silent-catch-" n "-" (name vs)) n (str "Fix catch " vs) [[:seon.code.var/name vs]] 74))))))))

(defn friction-findings
  "Pure canonical `seon.code.mine` findings."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:vector :seon.health/agent-friction] [:? [:maybe :map]]] [:vector :map]] :cost/ticks 30000000}
  ([mdb rows] (friction-findings mdb rows nil))
  ([mdb rows opts]
   (st/without-agent-eval-reads
    (fn []
      (let [o (or opts {}) mkv (:kv (cfg-of mdb nil o))
            tfs (for [r rows :let [a (:agent r) n #(long (or (r %) 0)) sl (str (namespace a) "-" (name a)) c [[:seon.agent/id a]] kv (:kv-cache-hit-pct r)]
                      [ok? k tg sc t i]
                      [[(:turn-overrun? r) :turn-overrun "overrun" 'seon.reconciler (str "Overrun " (:max-turn r) "/" (:max-tool-turns r)) 98]
                       [(pos? (n :ooms)) :alloc-budget-overrun "oom" 'seon.health (str "OOM " (:ooms r) "x") 95]
                       [(pos? (n :rogue-fs-count)) :rogue-fs "rogue-fs" 'seon.fork.helpers (str "Rogue " (:rogue-fs-count r) "x") 94]
                       [(pos? (n :prefix-busts)) :prefix-cache-bust "prefix-bust" 'seon.render.wire (str "Bust " (:prefix-busts r) "x") 86]
                       [(and (number? kv) (>= (+ (n :cache-hit-tokens) (n :cache-miss-tokens)) 2000) (< (double kv) (double mkv))) :low-cache-hit "low-kv" 'seon.render.wire (str "KV " kv "%") 80]
                       [(>= (n :no-match-count) 2) :no-match-churn "no-match" 'seon.code.cst (str ":no-match " (:no-match-count r) "x") 76]
                       [(>= (n :upstream-errors) 2) :upstream-error-spike "upstream" 'seon.model (str "Upstream " (:upstream-errors r) "x") 79]] :when ok?]
                  (fnd k (str "trace-" tg "-" sl) sc (str t " " a) c i))]
        (->> (concat tfs (fast-scans mdb (not (false? (:include-renderers? o))) (not (false? (:include-static? o)))))
             (group-by :id) vals (map #(apply max-key :impact %)) (sort-by #(vector (- (:impact %)) (str (:id %)))) vec))))))

(defn propose-tasks-tx
  "Converts findings to deduplicated `:seon.task/*` tx maps."
  {:malli/schema [:=> [:cat :seon.host/db :any [:? [:maybe :map]]] [:vector :map]] :cost/ticks 40000000}
  ([mdb src] (propose-tasks-tx mdb src nil))
  ([mdb src opts]
   (let [o (or opts {}) fs (cond (sequential? (:findings src)) (vec (:findings src)) (sequential? src) (vec src) :else (friction-findings mdb [] o))]
     (mine/mine-hygiene-tasks-tx mdb (assoc (dissoc o :findings) :findings fs)))))

(defn ^:seon.flow/edge friction-report
  "Commit-keyed cached trace friction report."
  {:malli/schema [:=> [:cat :seon.host/conn :seon.host/db [:? [:maybe :map]]] :seon.health/friction] :cost/ticks 40000000}
  ([conn mdb] (friction-report conn mdb nil))
  ([conn mdb opts]
   (st/without-agent-eval-reads
    (fn []
      (let [o (or opts {}) brs (set (d/branches conn)) w (:window o 12)
            mk (fn [a] (let [br (or (:seon.agent/branch a) :main)] (when (brs br) {:aid (:seon.agent/id a) :branch br :ent a :bdb (st/head conn br)})))
            sel (if (seq (:agents o)) (vec (keep #(mk (or (pull-ag mdb %) {:seon.agent/id %})) (:agents o)))
                  (let [cs (for [d (sort-by (comp - :e) (d/datoms mdb :aevt :seon.agent/id)) :let [id (:v d)]
                                 :when (and id (not= "example" (namespace id)) (or (:include-interactive? o) (and (not= "repl" (namespace id)) (not (eav1 mdb (:e d) :seon.agent/interactive?)))))
                                 :let [s (mk (pull-ag mdb id))] :when (and s ((db-schema (:bdb s)) :seon.agent.eval/agent) (first (d/datoms (:bdb s) :avet :seon.agent.eval/agent id)))] s)]
                    (vec (if (pos-int? w) (take w cs) cs))))]
        (st/lru-get! cache* 64 [:rep (db-cache-sig mdb) (mapv (juxt :aid (comp db-cache-sig :bdb)) sel) o]
         (fn []
           (let [t0 (System/nanoTime) dh (db-schema mdb)
                 up-m (if (and (dh :seon.error/source) (dh :seon.error/agent)) (frequencies (keep #(eav1 mdb (:e %) :seon.error/agent) (d/datoms mdb :avet :seon.error/source :model))) {})
                 rows (->> sel (mapv (fn [{:keys [aid branch ent bdb]}] (st/lru-get! cache* 64 [:ag aid (db-cache-sig bdb) (up-m aid 0) (boolean (:seon.agent/merged-tx ent)) (select-keys o [:max-tool-turns :heavy-eval-bytes :heavy-eval-ms :min-kv-pct])] #(analyze-impl bdb aid (assoc o :main-db mdb :branch branch :agent-ent ent :up-map up-m)))))
                           (sort-by #(vector (- (:friction-score %)) (str (:agent %)))) vec)
                 fs (friction-findings mdb rows o) S #(reduce + 0 (keep % rows)) ht (S :cache-hit-tokens) mt (S :cache-miss-tokens)
                 kv (when (pos? (+ ht mt)) (round1 (/ (* 100.0 ht) (+ ht mt)))) ovs (count (filter :turn-overrun? rows))
                 lv (frequencies (map :level rows)) hot (lv :hot 0) warm (lv :warm 0) fk (frequencies (map :kind fs))
                 tot (into {:turn-overruns ovs :kv-cache-hit-pct kv :missing-renderers (fk :missing-entity-renderer 0)
                            :static-debt (reduce + 0 (map #(fk % 0) [:oversized-namespace :large-function :nested-db-scan :silent-catch-all]))}
                           (map #(vector % (S %)) [:evals :turns :eval-errors :ooms :empty-code-turns :prefix-busts :upstream-errors :rogue-fs-count :no-match-count :cst-errors :heavy-evals]))]
             (with-meta {:level (cond (pos? hot) :hot (pos? warm) :warm :else :calm)
                         :summary (str hot " hot · " warm " warm · " (count rows) " agents" (when kv (str " · " kv "% KV")) " · " (:ooms tot) " OOM" (when (pos? ovs) (str " · " ovs " overrun")) " · " (count fs) " findings")
                         :agents-scanned (count rows) :totals tot :agents rows :findings fs :ms (round1 (/ (double (- (System/nanoTime) t0)) 1e6))}
                        {:seon/schema :seon.health/friction})))))))))

(defn ^:seon.flow/edge repl-friction
  "REPL helper (`user/friction`)."
  {:malli/schema [:=> [:cat [:? [:maybe :map]]] [:maybe :seon.health/friction]] :cost/ticks 40000000}
  ([] (repl-friction nil))
  ([opts]
   (when-let [{:keys [conn]} (some-> (requiring-resolve 'seon.fork.calls/*agent-ctx*) deref)]
     (let [o (or opts {}) mdb (or (st/head conn :main) @conn) rep (friction-report conn mdb o)]
       (if (:propose-tasks? o) (with-meta (assoc rep :proposed-tasks (propose-tasks-tx mdb rep o)) {:seon/schema :seon.health/friction}) rep)))))