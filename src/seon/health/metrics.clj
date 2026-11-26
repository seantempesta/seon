(ns seon.health.metrics
"Learning metrics scorecards and fast trace-friction diagnostics over indexed Datahike facts."
(:require [clojure.string :as str]
          [datahike.api :as d]
          [datahike.datom :as dd]
          [datahike.index.interface :as di]
          [konserve.core :as k]
          [seon.code.mine :as mine]
          [seon.health.coverage :as coverage]
          [seon.health.efficiency :as efficiency]
          [seon.health.static :as hstatic]
          [seon.render.text :refer [one-line round1]]
          [seon.store :as store :refer [db-cache-sig db-schema]]))

(defn pct-val
  {:malli/schema [:=> [:cat [:sequential [:or :int :double]] [:double {:min 0.0, :max 1.0}]] :double], :cost/ticks 200000, :cost/bytes 524288}
  [xs p]
  (let [s (vec (sort xs)) n (count s)]
    (if (zero? n) 0.0 (double (nth s (min (dec n) (long (* p n))))))))

(defn extract-tool-names
  {:malli/schema [:=> [:cat :any] [:vector :string]], :cost/ticks 200000, :cost/bytes 524288}
  [code]
  (let [s (str code)
        ms (distinct (map second (re-seq #"user/([a-zA-Z0-9\-\!\?\*]+)" s)))]
    (cond
      (seq ms) (vec ms)
      (str/includes? s "(d/q ") ["d/q"]
      (str/includes? s "(d/datoms ") ["d/datoms"]
      (str/includes? s "(defn ") ["raw-defn"]
      (str/includes? s "(deftest ") ["raw-deftest"]
      :else ["other-clj"])))

(def discovery-tools #{"source" "grep-src" "ns-outline" "ns-requires" "search" "callers" "callees" "tests-for" "db" "main-db" "d/q" "d/datoms" "past-evals" "history" "health" "help"})

(def action-tools #{"edit-fn!" "add-fn!" "move-vars!" "remove-var!" "set-meta!" "add-require!" "prune-requires!" "register-schema!" "run-tests!" "ready!" "autofix-health!" "answer!" "raw-defn" "raw-deftest"})

(defonce ^:private ^:seon.flow/edge friction-cache (store/lru-cache 64))

(def ^:private friction-weights
  {:ooms 25
   :rogue-fs-count 25
   :upstream-errors 15
   :prefix-busts 12
   :empty-code-turns 8
   :repeated-errors 8
   :no-match-count 6
   :cst-errors 6
   :test-failure-messages 5
   :heavy-evals 4
   :eval-errors 3})

(def ^:private rogue-fs-pattern
  #"\(\s*(?:clojure\.core/)?spit\b|(?:^|[^\w-])(?:jolt\.)?fs/write\b|File(?:Writer|OutputStream)\.|Files/write|io/writer\b|\bgit\s+(?:commit|add|push)\b|\bbin/build\b")

(def ^:private safe-edit-pattern
  #"user/(?:add-fn!|edit-fn!|ui-patch!|fs-write|sh!)")

(defn- make-finding [kind slug scope title curated impact]
  {:id (keyword "hygiene" (mine/normalize-hygiene-slug slug)) :kind kind :scope scope
   :title title :summary title :curated curated :impact (long impact)})

(defn score-agent-friction
  "Pure friction score and severity level for one agent summary."
  {:malli/schema [:=> [:cat :map [:? number?]] [:map [:friction-score [:int {:min 0}]] [:level :seon.signal/level]]]
   :cost/ticks 4000}
  ([summary] (score-agent-friction summary 70.0))
  ([summary min-kv]
   (let [metric #(max 0 (long (or (get summary %) 0)))
         overrun? (boolean (:turn-overrun? summary))
         kv-pct (:kv-cache-hit-pct summary)
         low-kv? (and (number? kv-pct) (>= (+ (metric :cache-hit-tokens) (metric :cache-miss-tokens)) 2000) (< (double kv-pct) (double min-kv)))
         score (+ (if overrun? 35 0) (if low-kv? 15 0) (reduce-kv #(+ %1 (* %3 (metric %2))) 0 friction-weights))]
     {:friction-score (long score)
      :level (cond (or overrun? (pos? (metric :ooms)) (pos? (metric :rogue-fs-count)) (>= score 40)) :hot
                   (>= score 12) :warm :else :calm)})))

(def ^:private error-class-matchers
  [[:alloc-budget-overrun ["allocation budget"]] [:tick-budget-overrun ["tick budget"]]
   [:schema-contract-violation ["Schema contract violation"]] [:wrong-arity ["Wrong number of args"]]
   [:unresolved-var ["No such var" "Unable to resolve"]]
   [:reader-syntax ["Reader syntax error" "Unmatched delimiter" "EOF while reading"]]
   [:type-mismatch ["cannot be cast" "find not supported"]] [:private-var ["is not public"]]])

(defn classify-eval-error
  "Classifies an eval error string into a stable failure class."
  {:malli/schema [:=> {:examples [[["Eval exceeded allocation budget (limit 268435456 bytes)."] :alloc-budget-overrun]
                                   [["Wrong number of args (2) passed to: user/callers"] :wrong-arity]]}
                  [:cat [:maybe :string]] :keyword]
   :cost/ticks 200000}
  [err-str]
  (let [text (or err-str "")]
    (or (some (fn [[cls needles]] (when (some #(str/includes? text %) needles) cls)) error-class-matchers) :other)))

(def ^:private edit-code-pattern #"user/(?:edit-fn!|ui-patch!|add-fn!|remove-var!|move-vars!)|patch-form")

(def ^:private cst-error-pattern
  #"(?i):cst-parse-failed|:unbalanced|:source-sync-mismatch|:form-not-found|:ambiguous-match|:invalid-syntax|rewrite-clj|reader syntax error|unmatched delimiter|eof while reading")

(defn- first-eav [db eid attr] (:v (first (d/datoms db :eavt eid attr))))

(defn- pct-int [part total] (if (pos? total) (Math/round (* 100.0 (/ (double part) total))) 0))

(defn- ratio-scaled [num denom scale] (when (pos? denom) (/ (Math/round (* scale (/ (double num) denom))) scale)))

(defn- count-avet [db attr val pred] (if (and db ((db-schema db) attr)) (count (filter pred (d/datoms db :avet attr val))) 0))

(defn- kv-hit-pct [hits misses prompt]
  (cond (pos? (+ hits misses)) (round1 (/ (* 100.0 hits) (+ hits misses)))
        (and (pos? prompt) (pos? hits)) (round1 (/ (* 100.0 hits) prompt))))

(defn- pull-agent [db agent-id]
  (when (and db ((db-schema db) :seon.agent/id) (first (d/datoms db :avet :seon.agent/id agent-id)))
    (d/pull db [:db/id :seon.agent/id :seon.agent/branch :seon.agent/interactive? :seon.agent/merged-tx :seon.agent/ready?]
            [:seon.agent/id agent-id])))

(defn- lazy-str [db value]
  (if-not (dd/lazy-value? value)
    (when (some? value) (str value))
    (let [stored (when-let [backing (:store db)] (k/get backing (dd/lazy-key value) ::missing {:sync? true}))]
      (str (if (and stored (not= ::missing stored)) stored (dd/value-of value))))))

(defn- friction-cfg [db agent-id opts]
  (let [get-opt #(or (get opts %1) (when db (store/config-val db %3 %2)) %4)]
    {:max-turns (long (get-opt :max-tool-turns :seon.config/max-tool-turns agent-id 32))
     :max-bytes (long (get-opt :heavy-eval-bytes :seon.config/heavy-eval-bytes nil 134217728))
     :max-ms (double (get-opt :heavy-eval-ms :seon.config/heavy-eval-ms nil 5000))
     :min-kv (double (get-opt :min-kv-pct :seon.config/friction-min-kv-pct nil 70))}))

(defn- normalize-opts [opts]
  (let [raw (cond (keyword? opts) {:agents [opts]} (map? opts) opts :else {})]
    (if (and (:agent raw) (nil? (:agents raw))) (-> raw (dissoc :agent) (assoc :agents [(:agent raw)])) raw)))

(def ^:private zero-eval-acc
  (into {:max-ms 0.0 :error-classes (sorted-map) :turn-set #{} :prefix-set #{} :transitions [] :sigs {} :max-bytes 0 :tool-rows []}
        (map #(vector % 0) [:evals :max-turn :empty-code-turns :prompt-tokens :completion-tokens :cache-hit-tokens
                            :cache-miss-tokens :eval-errors :ooms :cst-errors :no-match-count :rogue-fs-count :heavy-evals])))

(defn- step-eval [branch-db max-bytes max-ms track-tools? acc datom]
  (let [eid (:e datom)
        bump #(update %1 %2 inc)
        as-long #(long (or % 0))
        {:seon.agent.eval/keys [turn call-index bytes ms prompt-tokens completion-tokens cache-hit-tokens
                                cache-miss-tokens handle code thought error value prefix-hash]}
        (persistent! (reduce #(if (= :seon.agent.eval/reasoning (:a %2)) %1 (assoc! %1 (:a %2) (:v %2)))
                             (transient {}) (d/datoms branch-db :eavt eid)))
        turn-num (as-long turn)
        call-idx (as-long call-index)
        eval-bytes (as-long bytes)
        hits (as-long cache-hit-tokens)
        eval-ms (double (or ms 0.0))
        code-str (or (some->> code (lazy-str branch-db)) "")
        thought-str (or (some->> thought (lazy-str branch-db)) "")
        empty-turn? (and (str/blank? code-str) (str/blank? thought-str))
        prose-reply? (and (str/blank? code-str) (not (str/blank? thought-str)))
        err-str (some->> error (lazy-str branch-db))
        val-str (cond (nil? value) nil
                      (not (dd/lazy-value? value)) (str value)
                      (re-find edit-code-pattern code-str) (lazy-str branch-db value))
        prefix (not-empty (str/trim (str (or prefix-hash ""))))
        has-err? (and err-str (not (str/blank? err-str)))
        diag (if (and has-err? val-str) (str err-str " " val-str) (or (when has-err? err-str) val-str))
        err-cls (when has-err? (classify-eval-error err-str))
        ord [turn-num call-idx eid]
        err-sig (when has-err? (-> (first (str/split-lines err-str)) str/trim (str/replace #"[0-9a-f]{8}-[0-9a-f-]{27}|0x[0-9a-f]+|\d+" "#")))]
    (cond-> (merge-with + acc {:evals 1 :prompt-tokens (as-long prompt-tokens) :completion-tokens (as-long completion-tokens)
                               :cache-hit-tokens hits :cache-miss-tokens (as-long cache-miss-tokens)})
      true (-> (update :max-turn max turn-num) (update :max-bytes max eval-bytes) (update :max-ms max eval-ms))
      (pos? turn-num) (update :turn-set conj turn-num)
      empty-turn? (bump :empty-code-turns)
      (and prose-reply? (or (not (:reply-ord acc)) (pos? (compare ord (:reply-ord acc)))))
      (assoc :reply-ord ord :last-reply (str/trim thought-str))
      prefix (update :prefix-set conj prefix)
      (and (zero? call-idx) (or prefix (pos? hits))) (update :transitions conj [turn-num eid prefix hits])
      (or (not (:last-ord acc)) (pos? (compare ord (:last-ord acc)))) (assoc :last-ord ord :last-val [handle code-str])
      has-err? (-> (bump :eval-errors) (update-in [:error-classes err-cls] (fnil inc 0)) (update-in [:sigs err-sig] (fnil inc 0)))
      (and has-err? (or (not (:err-ord acc)) (pos? (compare ord (:err-ord acc))))) (assoc :err-ord ord :err-val [handle err-str])
      (= :alloc-budget-overrun err-cls) (bump :ooms)
      (or (= :reader-syntax err-cls) (and diag (re-find cst-error-pattern diag))) (bump :cst-errors)
      (and diag (.contains ^String diag ":no-match") (re-find #":no-match\b" diag)) (bump :no-match-count)
      (and (seq code-str) (re-find rogue-fs-pattern code-str) (not (re-find safe-edit-pattern code-str))) (bump :rogue-fs-count)
      (or (> eval-bytes max-bytes) (> eval-ms max-ms)) (bump :heavy-evals)
      track-tools? (update :tool-rows conj [turn-num call-idx eid code-str err-str eval-bytes]))))

(defn- fold-agent-evals
  ([branch-db agent-id max-bytes max-ms track-tools?]
   (fold-agent-evals branch-db agent-id max-bytes max-ms track-tools? 0))
  ([branch-db agent-id max-bytes max-ms track-tools? after-tx]
   (if (and branch-db ((db-schema branch-db) :seon.agent.eval/agent))
     (binding [di/*read-key-attrs* #{}]
       (let [cutoff (long (or after-tx 0))]
         (reduce (fn [acc datom]
                   (if (and (pos? cutoff) (<= (long (:tx datom)) cutoff))
                     acc
                     (step-eval branch-db max-bytes max-ms track-tools? acc datom)))
                 zero-eval-acc
                 (d/datoms branch-db :avet :seon.agent.eval/agent agent-id))))
     zero-eval-acc)))

(defn eval-summary-by-agent
  "Summarizes one agent's evals in a single db using indexed `:avet :seon.agent.eval/agent` reads."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :seon.agent/id] :map] :cost/ticks 2000000}
  [db agent-id]
  (let [{:keys [evals eval-errors prompt-tokens completion-tokens cache-hit-tokens cache-miss-tokens max-bytes turn-set]}
        (fold-agent-evals db agent-id 134217728 5000.0 false)]
    {:agent agent-id :evals evals :turns (count turn-set) :errors eval-errors :error-rate-pct (pct-int eval-errors evals)
     :prompt-tokens prompt-tokens :completion-tokens completion-tokens :total-tokens (+ prompt-tokens completion-tokens)
     :cache-hit-tokens cache-hit-tokens :cache-miss-tokens cache-miss-tokens
     :cache-hit-rate-pct (pct-int cache-hit-tokens (+ cache-hit-tokens cache-miss-tokens)) :max-bytes max-bytes}))

(defn- ^:seon.flow/edge select-agent-targets [conn main-db opts]
  (let [branches (set (d/branches conn))
        window (if (contains? opts :window) (:window opts) 12)
        resolve-agent (fn [ent]
                        (let [branch (or (:seon.agent/branch ent) :main)]
                          (when-let [branch-db (when (branches branch) (try (store/head conn branch) (catch Throwable _ nil)))]
                            {:agent-id (:seon.agent/id ent) :branch branch :entity ent :branch-db branch-db})))]
    (cond
      (and (contains? opts :agents) (some? (:agents opts)))
      (vec (keep #(resolve-agent (or (pull-agent main-db %) {:seon.agent/id %})) (:agents opts)))
      (and (integer? window) (<= (long window) 0)) []
      :else
      (->> (d/datoms main-db :aevt :seon.agent/id) (sort-by (comp - :e))
           (keep (fn [datom]
                   (let [agent-id (:v datom) ns-str (when agent-id (namespace agent-id))]
                     (when (and agent-id (not= "example" ns-str) (not (and (:exclude-root? opts) (= :agent/root agent-id)))
                                (or (:include-interactive? opts) (and (not= "repl" ns-str) (not (first-eav main-db (:e datom) :seon.agent/interactive?)))))
                       (when-let [target (resolve-agent (pull-agent main-db agent-id))]
                         (when (and ((db-schema (:branch-db target)) :seon.agent.eval/agent)
                                    (first (d/datoms (:branch-db target) :avet :seon.agent.eval/agent agent-id)))
                           target))))))
           (take (if (integer? window) (long window) 12)) vec))))

(defn- tool-frustration-stats [rows]
  (let [bump-tool (fn [err alloc-mb prev acc tool]
                    (update-in acc [:tools tool]
                      (fn [{:keys [calls errs ooms retries mbs] :or {calls 0 errs 0 ooms 0 retries 0 mbs []}}]
                        {:tool tool :calls (inc calls) :errs (+ errs (if err 1 0))
                         :ooms (+ ooms (if (= :alloc-budget-overrun (when err (classify-eval-error err))) 1 0))
                         :retries (+ retries (if (some #{tool} prev) 1 0)) :mbs (conj mbs alloc-mb)})))
        step (fn [[state prev] [_ _ _ code err bytes]]
               (let [tools (extract-tool-names code)
                     disc? (some discovery-tools tools)
                     act? (some action-tools tools)
                     base (cond-> state (and disc? (not act?)) (update :disc inc) act? (update :act inc))]
                 [(reduce #(bump-tool err (/ (double bytes) 1048576.0) prev %1 %2) base tools) tools]))
        acc (reduce (fn [state {:keys [tool-rows]}] (first (reduce step [state nil] (sort-by #(subvec % 0 3) tool-rows))))
              {:disc 0 :act 0 :tools {}} rows)
        rank-tool (fn [{:keys [tool calls errs ooms retries mbs]}]
                    (let [err-pct (pct-int errs calls) oom-pct (pct-int ooms calls) ret-pct (pct-int retries calls)]
                      {:tool tool :calls calls :err% err-pct :oom% oom-pct :ret% ret-pct
                       :p95-mb (Math/round (pct-val mbs 0.95))
                       :frustration (Math/round (+ (* 3.0 err-pct) (* 2.0 oom-pct) (* 0.5 ret-pct)))}))]
    {:disc (:disc acc) :act (:act acc)
     :top-tools (->> (vals (:tools acc)) (filter #(>= (:calls %) 2)) (map rank-tool)
                  (sort-by (comp - :frustration)) (take 6) vec)}))

(defn- learning-scorecard [bounded targets]
  (let [rows (mapv (fn [{:keys [agent-id entity branch-db]}]
                     (let [{:keys [evals eval-errors cache-hit-tokens cache-miss-tokens error-classes turn-set prefix-set tool-rows]}
                           (fold-agent-evals branch-db agent-id 134217728 5000.0 true)]
                       {:id agent-id :evals evals :turns (count turn-set) :errs eval-errors
                        :merged? (boolean (:seon.agent/merged-tx entity)) :ready? (boolean (:seon.agent/ready? entity))
                        :hits cache-hit-tokens :miss cache-miss-tokens :prefix-busts (max 0 (dec (count prefix-set)))
                        :error-classes error-classes :tool-rows tool-rows}))
                   (reverse targets))
        attempted (count rows)
        merged-rows (filter :merged? rows)
        merged (count merged-rows)
        sum-by #(reduce + 0 (map % rows))
        turns (sum-by :turns) evals (sum-by :evals) errs (sum-by :errs)
        hits (sum-by :hits) misses (sum-by :miss)
        err-classes (into (sorted-map) (apply merge-with + {} (map :error-classes rows)))
        {:keys [disc act top-tools]} (tool-frustration-stats rows)]
    {:window bounded :attempted attempted :merged merged :merge-rate-pct (pct-int merged attempted)
     :total-turns turns :effective-turns-per-merged (ratio-scaled turns merged 10.0)
     :median-turns-merged (when (pos? merged) (Math/round (pct-val (mapv :turns merged-rows) 0.50)))
     :total-evals evals :avg-evals-per-turn (or (ratio-scaled evals turns 100.0) 0.0)
     :eval-error-rate-pct (pct-int errs evals)
     :oom-rate-pct (pct-int (get err-classes :alloc-budget-overrun 0) evals)
     :discovery-to-action-ratio (ratio-scaled disc act 100.0)
     :kv-cache-hit-pct (when (pos? (+ hits misses)) (pct-int hits (+ hits misses)))
     :prefix-busts (sum-by :prefix-busts) :error-classes err-classes
     :top-frustrating-tools top-tools
     :recent-agents (mapv #(dissoc % :hits :miss :error-classes :tool-rows) rows)}))

(defn agent-learning-metrics
  "Sliding-window task completion and tool-frustration scorecard over the latest `window` autonomous agents."
  {:malli/schema [:function [:=> [:cat :seon.host/conn :seon.host/db] :map] [:=> [:cat :seon.host/conn :seon.host/db :map] :map]]
   :seon.flow/edge true}
  ([conn db] (agent-learning-metrics conn db {}))
  ([conn db {:keys [window] :or {window 10}}]
   (let [bounded (long (max 1 (min 30 (or window 10))))]
     (store/lru-get! friction-cache 64 [:learning (db-cache-sig db) bounded]
       #(learning-scorecard bounded (select-agent-targets conn db {:window bounded :exclude-root? true}))))))

(defn- ^:seon.flow/edge system-findings [main-db renderers? static?]
  (if-not main-db
    []
    (vec (concat
           (when renderers?
             (for [{:keys [schema]} (:missing-renderers (coverage/coverage-report main-db {:include-atlas? false})) :when schema]
               (make-finding :missing-entity-renderer (str "renderer-" (namespace schema) "-" (name schema))
                             'seon.render.entities (str "Render " schema) [[:seon.schema/key schema]] 72)))
           (when static?
             (keep mine/problem->hygiene-finding
                   (concat (efficiency/static-efficiency-warnings main-db nil (hstatic/form-meta-snapshot main-db))
                           (hstatic/silent-catch-warnings main-db))))))))

(defn- trace-findings [rows min-kv]
  (for [row rows
        :let [agent-id (:agent row) metric #(long (or (get row %) 0))
              slug (str (namespace agent-id) "-" (name agent-id)) curated [[:seon.agent/id agent-id]] kv-pct (:kv-cache-hit-pct row)]
        [hit? kind tag scope title impact]
        [[(:turn-overrun? row) :turn-overrun "overrun" 'seon.reconciler (str "Overrun " (:max-turn row) "/" (:max-tool-turns row)) 98]
         [(pos? (metric :ooms)) :alloc-budget-overrun "oom" 'seon.health (str "OOM " (:ooms row) "x") 95]
         [(pos? (metric :rogue-fs-count)) :rogue-fs "rogue-fs" 'seon.fork.helpers (str "Rogue " (:rogue-fs-count row) "x") 94]
         [(pos? (metric :prefix-busts)) :prefix-cache-bust "prefix-bust" 'seon.render.wire (str "Bust " (:prefix-busts row) "x") 86]
         [(and (number? kv-pct) (>= (+ (metric :cache-hit-tokens) (metric :cache-miss-tokens)) 2000) (< (double kv-pct) (double min-kv)))
          :low-cache-hit "low-kv" 'seon.render.wire (str "KV " kv-pct "%") 80]
         [(>= (metric :no-match-count) 2) :no-match-churn "no-match" 'seon.code.cst (str ":no-match " (:no-match-count row) "x") 76]
         [(>= (metric :upstream-errors) 2) :upstream-error-spike "upstream" 'seon.model (str "Upstream " (:upstream-errors row) "x") 79]]
        :when hit?]
    (make-finding kind (str "trace-" tag "-" slug) scope (str title " " agent-id) curated impact)))

(defn ^:seon.flow/edge friction-findings
  "Canonical `seon.code.mine` findings from agent friction rows plus cached health/coverage scans."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:vector :seon.health/agent-friction] [:? [:maybe :map]]] [:vector :map]]
   :cost/ticks 30000000}
  ([main-db rows] (friction-findings main-db rows nil))
  ([main-db rows opts]
   (store/without-agent-eval-reads
     (fn []
       (let [opts (or opts {})
             min-kv (:min-kv (friction-cfg main-db nil opts))
             sys-f (or (:sys-findings opts) (system-findings main-db (not (false? (:include-renderers? opts))) (not (false? (:include-static? opts)))))]
         (->> (concat (trace-findings rows min-kv) sys-f) (group-by :id) vals
              (map #(apply max-key :impact %)) (sort-by #(vector (- (:impact %)) (str (:id %)))) vec))))))

(defn ^:seon.flow/edge propose-tasks-tx
  "Converts friction findings into deduplicated `:seon.task/*` transaction maps."
  {:malli/schema [:=> [:cat :seon.host/db :any [:? [:maybe :map]]] [:vector :map]] :cost/ticks 40000000}
  ([main-db source] (propose-tasks-tx main-db source nil))
  ([main-db source opts]
   (let [opts (or opts {})
         findings (cond (sequential? (:findings source)) (vec (:findings source)) (sequential? source) (vec source) :else (friction-findings main-db [] opts))]
     (mine/mine-hygiene-tasks-tx main-db (assoc (dissoc opts :findings) :findings findings)))))

(defn- format-snippet [handle code]
  (let [label (when handle (str/trim (str handle))) line (one-line code 100)]
    (cond (and (seq label) (seq line)) (str label ": " line) (seq label) label (seq line) line :else nil)))

(defn- analyze-agent-impl [branch-db agent-id opts]
  (let [main-db (or (:main-db opts) branch-db)
        {:keys [max-turns max-bytes max-ms min-kv]} (or (:cfg opts) (friction-cfg main-db agent-id opts))
        agent-ent (or (:agent-ent opts) (pull-agent main-db agent-id))
        after-tx (long (or (:after-tx opts) 0))
        after-main-tx (long (or (:after-main-tx opts) 0))
        after-main? #(or (zero? after-main-tx) (> (long (:tx %)) after-main-tx))
        folded (fold-agent-evals branch-db agent-id max-bytes max-ms false after-tx)
        turns (count (:turn-set folded))
        prefixes (count (:prefix-set folded))
        busts (count (filter (fn [[[_ _ prev-pfx prev-hits] [_ _ next-pfx next-hits]]]
                               (or (and prev-pfx next-pfx (not= prev-pfx next-pfx))
                                   (and (> prev-hits 512) (< next-hits (- prev-hits 256)))))
                             (partition 2 1 (sort-by (juxt first second) (:transitions folded)))))
        upstream (if (and (zero? after-main-tx) (:upstream-map opts))
                   (long (get (:upstream-map opts) agent-id 0))
                   (count-avet main-db :seon.error/source :model
                     #(and (after-main? %) (= agent-id (first-eav main-db (:e %) :seon.error/agent)))))
        call-datoms (if (and main-db ((db-schema main-db) :seon.agent.call/caller))
                      (filterv after-main? (d/datoms main-db :avet :seon.agent.call/caller agent-id))
                      [])
        settled-calls (count (filter #(= :resolved (first-eav main-db (:e %) :seon.agent.call/status)) call-datoms))
        test-fails (count-avet main-db :seon.agent.message/to agent-id
                     #(and (after-main? %) (= :test-failure (first-eav main-db (:e %) :seon.agent.message/type))))
        last-error (when-let [[handle err] (:err-val folded)]
                     (when-let [line (not-empty (one-line err 140))]
                       (if handle (str handle " ERR: " line) (str "ERR: " line))))
        base (dissoc folded :turn-set :prefix-set :transitions :sigs :last-ord :last-val
                     :err-ord :err-val :reply-ord :last-reply :max-bytes :tool-rows)
        extras {:agent agent-id
                :branch (or (:branch opts) (get-in branch-db [:config :branch]) :main)
                :turns turns
                :max-tool-turns max-turns
                :turn-overrun? (boolean (and (pos? max-turns) (or (> (:max-turn folded) max-turns) (> turns max-turns))))
                :kv-cache-hit-pct (kv-hit-pct (:cache-hit-tokens folded) (:cache-miss-tokens folded) (:prompt-tokens folded))
                :prefix-hash-count prefixes
                :prefix-busts (long (max busts (max 0 (dec prefixes))))
                :repeated-errors (long (reduce + 0 (map #(max 0 (dec (long %))) (vals (:sigs folded)))))
                :test-failure-messages test-fails
                :upstream-errors upstream
                :max-mb (round1 (/ (double (:max-bytes folded)) 1048576.0))
                :max-ms (round1 (:max-ms folded))
                :merged? (boolean (:seon.agent/merged-tx agent-ent))
                :ready? (boolean (:seon.agent/ready? agent-ent))
                :delegated-calls (count call-datoms)
                :settled-delegated-calls settled-calls
                :last-snippet (when-let [[handle code] (:last-val folded)] (format-snippet handle code))
                :last-error last-error}
        summary (cond-> (merge base extras) (:last-reply folded) (assoc :last-reply (:last-reply folded)))]
    (merge summary (score-agent-friction summary min-kv))))

(defn analyze-agent-friction
  "Pure indexed lazy `:eavt` trace-friction analyzer for a single agent."
  {:malli/schema [:=> [:cat :seon.host/db :keyword [:? [:maybe :map]]] :seon.health/agent-friction]
   :cost/ticks 15000000}
  ([branch-db agent-id] (analyze-agent-friction branch-db agent-id nil))
  ([branch-db agent-id opts]
   (store/without-agent-eval-reads #(analyze-agent-impl branch-db agent-id (or opts {})))))

(defn ^:seon.flow/edge agent-friction-summary
  "Resolves `agent-id`'s branch head and computes its trace-friction summary."
  {:malli/schema [:=> [:cat :seon.host/conn :seon.host/db :keyword [:? [:maybe :map]]] :seon.health/agent-friction] :cost/ticks 20000000}
  ([conn main-db agent-id] (agent-friction-summary conn main-db agent-id nil))
  ([conn main-db agent-id opts]
   (let [opts (or opts {}) agent-ent (pull-agent main-db agent-id)
         branch (or (:branch opts) (:seon.agent/branch agent-ent) :main)
         branch-db (or (when (and conn ((set (d/branches conn)) branch)) (store/head conn branch)) main-db)]
     (analyze-agent-friction branch-db agent-id (assoc opts :main-db main-db :branch branch :agent-ent agent-ent)))))

(defn- ^:seon.flow/edge build-friction-report [main-db main-sig selected opts]
  (let [started (System/nanoTime) schema-map (db-schema main-db)
        upstream-map (if (and (schema-map :seon.error/source) (schema-map :seon.error/agent))
                       (frequencies (keep #(first-eav main-db (:e %) :seon.error/agent) (d/datoms main-db :avet :seon.error/source :model))) {})
        opt-keys (select-keys opts [:max-tool-turns :heavy-eval-bytes :heavy-eval-ms :min-kv-pct])
        analyze (fn [{:keys [agent-id branch entity branch-db]}]
                  (store/lru-get! friction-cache 64
                    [:agent agent-id main-sig (db-cache-sig branch-db) (get upstream-map agent-id 0)
                     (boolean (:seon.agent/merged-tx entity)) (boolean (:seon.agent/ready? entity)) opt-keys]
                    #(analyze-agent-impl branch-db agent-id (assoc opts :main-db main-db :branch branch :agent-ent entity :upstream-map upstream-map))))
        rows (vec (sort-by #(vector (- (:friction-score %)) (str (:agent %))) (mapv analyze selected)))
        sys-findings (system-findings main-db (not (false? (:include-renderers? opts))) (not (false? (:include-static? opts))))
        findings (friction-findings main-db rows (assoc opts :sys-findings sys-findings))
        sum-rows #(reduce + 0 (keep % rows))
        kv-pct (kv-hit-pct (sum-rows :cache-hit-tokens) (sum-rows :cache-miss-tokens) 0)
        overruns (count (filter :turn-overrun? rows))
        levels (frequencies (map :level rows)) hot (get levels :hot 0) warm (get levels :warm 0)
        kinds (frequencies (map :kind findings))
        totals (into {:turn-overruns overruns :kv-cache-hit-pct kv-pct :missing-renderers (get kinds :missing-entity-renderer 0)
                      :static-debt (reduce + 0 (map #(get kinds % 0) [:oversized-namespace :large-function :nested-db-scan :silent-catch-all]))}
                     (map #(vector % (sum-rows %))
                          [:evals :turns :eval-errors :ooms :empty-code-turns :prefix-busts :upstream-errors :rogue-fs-count :no-match-count :cst-errors :heavy-evals]))]
    (with-meta {:level (cond (pos? hot) :hot (pos? warm) :warm :else :calm)
                :summary (str hot " hot · " warm " warm · " (count rows) " agents" (when kv-pct (str " · " kv-pct "% KV"))
                              " · " (:ooms totals) " OOM" (when (pos? overruns) (str " · " overruns " overrun")) " · " (count findings) " findings")
                :agents-scanned (count rows) :totals totals :agents rows :findings findings :ms (round1 (/ (double (- (System/nanoTime) started)) 1e6))}
               {:seon/schema :seon.health/friction})))

(defn ^:seon.flow/edge friction-report
  "Commit-keyed cached trace-friction report with cached static and coverage findings."
  {:malli/schema [:=> [:cat :seon.host/conn :seon.host/db [:? [:maybe :map]]] :seon.health/friction] :cost/ticks 40000000}
  ([conn main-db] (friction-report conn main-db nil))
  ([conn main-db opts]
   (store/without-agent-eval-reads
     (fn []
       (let [opts (normalize-opts opts) selected (select-agent-targets conn main-db opts) main-sig (db-cache-sig main-db)]
         (store/lru-get! friction-cache 64 [:report main-sig (mapv (juxt :agent-id (comp db-cache-sig :branch-db)) selected) opts]
                         #(build-friction-report main-db main-sig selected opts)))))))

(defn ^:seon.flow/edge repl-friction
  "REPL helper (`user/friction`). Accepts nil, an agent keyword, `{:agent ...}`, or `{:agents [...]}`."
  {:malli/schema [:=> [:cat [:? [:maybe [:or :keyword :map]]]] [:maybe :seon.health/friction]] :cost/ticks 40000000}
  ([] (repl-friction nil))
  ([opts]
   (when-let [{:keys [conn]} (some-> (requiring-resolve 'seon.fork.calls/*agent-ctx*) deref)]
     (let [opts (normalize-opts opts) main-db (or (store/head conn :main) @conn) report (friction-report conn main-db opts)]
       (if (:propose-tasks? opts)
         (with-meta (assoc report :proposed-tasks (propose-tasks-tx main-db report opts)) {:seon/schema :seon.health/friction})
         report)))))