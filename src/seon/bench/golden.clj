(ns seon.bench.golden
  "Unrigged 8-task `:golden/v1` benchmark suite and persistent cross-commit scoreboard."
  (:require [datahike.api :as d]
            [seon.bench.cluster :as cluster]
            [seon.fork.calls :as fcalls]
            [seon.git :as git]
            [seon.render.text :refer [round1]]
            [seon.store :as st :refer [db-schema]]))

(def ^:private run-pat
  '[:seon.bench.run/id :seon.bench.run/title :seon.bench.run/status
    :seon.bench.run/started-ms :seon.bench.run/finished-ms
    :seon.bench.run/total :seon.bench.run/passed :seon.bench.run/failed
    :seon.bench.run/trials :seon.bench.run/proxy-stats
    :seon.bench.run/suite-id :seon.bench.run/git-commit
    :seon.bench.run/main-tx :seon.bench.run/model])

(defn golden-suite-v1
  "Returns the unrigged 8-task `:golden/v1` benchmark suite (1-2 sentence human prompts, zero recipes)."
  {:malli/schema [:=> [:cat] [:vector :map]] :cost/ticks 5000}
  []
  [{:id :golden/v1-split-rule
    :suite-id :golden/v1 :category :ns-split
    :run-tests? true :merge? true :require-merge? true :timeout-ms 180000
    :task {:id :golden/v1-split-rule :scope 'seon.rule
           :title "Split the oversized rule engine namespace"
           :plan "The rule engine namespace has grown past the namespace size budget. Extract cohesive helper logic into a sibling namespace so every namespace stays comfortably under budget without breaking callers or tests."}}
   {:id :golden/v1-metrics-schema
    :suite-id :golden/v1 :category :schema-proof
    :run-tests? true :merge? true :require-merge? true :timeout-ms 180000
    :task {:id :golden/v1-metrics-schema :scope 'seon.health.metrics
           :title "Tighten contracts and generative proofs on metrics helpers"
           :plan "Tighten function contracts and cost annotations on the pure learning-metrics helpers and ensure generative checks pass cleanly across all pure functions in that namespace."}}
   {:id :golden/v1-test-run-renderers
    :suite-id :golden/v1 :category :entity-renderers
    :run-tests? true :merge? true :require-merge? true :timeout-ms 180000
    :task {:id :golden/v1-test-run-renderers :scope 'seon.render.entities
           :title "Add compact and detailed renderers for stored test runs"
           :plan "Stored test run records currently have no UI renderers. Add pure compact chip and detailed card Hiccup renderers so test runs surface cleanly with distinct footprints and zero coverage gaps."}}
   {:id :golden/v1-silent-catch
    :suite-id :golden/v1 :category :hygiene-catch
    :run-tests? true :merge? true :require-merge? true :timeout-ms 180000
    :task {:id :golden/v1-silent-catch :scope 'seon.browser.preview
           :title "Eliminate silent broad exception catches in browser preview"
           :plan "Replace silent broad exception catches in the browser preview helpers with explicit failure data or proper boundary handling so static health reports zero silent catch warnings there."}}
   {:id :golden/v1-cutoff-livelock
    :suite-id :golden/v1 :category :bug-regression
    :run-tests? true :merge? true :require-merge? true :timeout-ms 180000
    :task {:id :golden/v1-cutoff-livelock :scope 'seon.task.gate
           :title "Ensure bounded review cutoff always pauses cleanly"
           :plan "Verify that a bounded review worker hitting its turn cutoff always pauses and notifies its parent even when there are no unread failure messages, and add a focused regression test covering that path."}}
   {:id :golden/v1-bench-rule
    :suite-id :golden/v1 :category :rule-authoring
    :run-tests? true :merge? true :require-merge? true :timeout-ms 180000
    :task {:id :golden/v1-bench-rule :scope 'seon.rule
           :title "Add a declarative context rule for benchmark runs"
           :plan "Author a declarative context rule that surfaces recent benchmark run summaries whenever an agent works on benchmark namespaces, and cover it with a unit test."}}
   {:id :golden/v1-mine-alloc
    :suite-id :golden/v1 :category :perf-alloc
    :run-tests? true :merge? true :require-merge? true :timeout-ms 180000
    :task {:id :golden/v1-mine-alloc :scope 'seon.code.mine
           :title "Reduce allocation overhead in hygiene finding scans"
           :plan "Reduce unnecessary allocations and redundant index scans when mining hygiene findings while preserving the exact finding output, and add a regression check confirming the improved budget."}}
   {:id :golden/v1-root-delegation
    :suite-id :golden/v1 :category :root-delegation
    :chat-agent :agent/root :branch :branch/root
    :run-tests? false :merge? false :require-merge? false :timeout-ms 180000
    :body "Please delegate two quick read-only checks in parallel—one checking current health coverage status and one checking the largest namespaces—and give me a concise combined summary once both finish."}])

(defn select-golden-tasks
  "Filters `(golden-suite-v1)` by optional `:only`/`:ids`, `:categories`, and `:timeout-ms`."
  {:malli/schema [:=> [:cat [:? [:maybe :map]]] [:vector :map]] :cost/ticks 10000}
  ([] (golden-suite-v1))
  ([opts]
   (let [o (if (map? opts) opts {})
         ids (when-let [xs (or (:only o) (:ids o))] (set xs))
         cats (when-let [cs (:categories o)] (set cs))
         tms (:timeout-ms o)]
     (->> (golden-suite-v1)
          (filter #(and (or (nil? ids) (ids (:id %)))
                        (or (nil? cats) (cats (:category %)))))
          (mapv #(cond-> % (pos-int? tms) (assoc :timeout-ms tms)))))))

(defn- median [xs]
  (let [vs (vec (sort (keep #(when (number? %) (double %)) xs)))
        n (count vs)]
    (when (pos? n)
      (let [m (quot n 2)]
        (round1 (if (odd? n) (nth vs m) (/ (+ (nth vs (dec m)) (nth vs m)) 2.0)))))))

(defn- trial-merged? [t]
  (boolean (or (:merged? t) (= :merged (:status t)))))

(defn- trial-passed? [t]
  (boolean (or (trial-merged? t) (= :passed (:status t)))))

(defn- summarize-run [r]
  (let [trials (vec (filter map? (when (sequential? (:seon.bench.run/trials r)) (:seon.bench.run/trials r))))
        total (long (or (when (number? (:seon.bench.run/total r)) (:seon.bench.run/total r)) (count trials)))
        passed (long (if (seq trials)
                       (count (filter trial-passed? trials))
                       (or (when (number? (:seon.bench.run/passed r)) (:seon.bench.run/passed r)) 0)))
        merged (count (filter trial-merged? trials))
        merge-elig (let [me (remove #(= :root-delegation (:category %)) trials)]
                     (if (seq me) (count me) total))
        m-rate (if (pos? merge-elig) (round1 (* 100.0 (/ (double merged) merge-elig))) 0.0)
        p-rate (if (pos? total) (round1 (* 100.0 (/ (double passed) total))) 0.0)
        m-turns (keep (fn [t] (when (trial-merged? t) (or (when (number? (:turns t)) (:turns t))
                                                          (when (number? (:evals t)) (:evals t)))))
                      trials)
        walls (keep #(when (and (number? (:elapsed-ms %)) (pos? (double (:elapsed-ms %))))
                       (/ (double (:elapsed-ms %)) 1000.0)) trials)
        kh (reduce + 0 (keep #(when (number? (:kv-hit-tokens %)) (long (:kv-hit-tokens %))) trials))
        km (reduce + 0 (keep #(when (number? (:kv-miss-tokens %)) (long (:kv-miss-tokens %))) trials))
        ps (when (map? (:seon.bench.run/proxy-stats r)) (:seon.bench.run/proxy-stats r))
        kv (cond (pos? (+ kh km)) (round1 (* 100.0 (/ (double kh) (+ kh km))))
                 (and (number? (:cache-hit-tokens ps)) (number? (:prompt-tokens ps)) (pos? (long (:prompt-tokens ps))))
                 (round1 (* 100.0 (/ (double (:cache-hit-tokens ps)) (long (:prompt-tokens ps)))))
                 :else nil)
        up (reduce + 0 (keep #(when (number? (:upstream-errors %)) (max 0 (long (:upstream-errors %)))) trials))
        live (count (filter #(or (true? (:turn-overrun? %)) (= :timeout (:status %))) trials))
        ooms (reduce + 0 (keep #(when (number? (:ooms %)) (max 0 (long (:ooms %)))) trials))
        by-cat (into {}
                     (for [[cat ts] (group-by :category (filter #(keyword? (:category %)) trials))]
                       [cat {:total (count ts)
                             :passed (count (filter trial-passed? ts))
                             :merged (count (filter trial-merged? ts))
                             :median-turns (median (keep #(or (:turns %) (:evals %)) ts))}]))]
    (cond-> {:run-id (:seon.bench.run/id r)
             :git-commit (when (string? (:seon.bench.run/git-commit r)) (:seon.bench.run/git-commit r))
             :main-tx (when (number? (:seon.bench.run/main-tx r)) (long (:seon.bench.run/main-tx r)))
             :model (when (string? (:seon.bench.run/model r)) (:seon.bench.run/model r))
             :status (let [st (:seon.bench.run/status r)] (if (keyword? st) st :failed))
             :started-ms (long (or (when (number? (:seon.bench.run/started-ms r)) (:seon.bench.run/started-ms r)) 0))
             :total (max 0 total) :passed (max 0 passed) :merged (max 0 merged)
             :merge-rate-pct m-rate :overall-pass-rate-pct p-rate
             :median-turns-to-merge (median m-turns) :median-wall-s (median walls)
             :kv-cache-hit-pct kv :upstream-errors up :livelocks live :ooms ooms}
      (seq by-cat) (assoc :by-category by-cat))))

(defn- compute-delta [cur prev]
  (when (and (map? cur) (map? prev))
    (let [fdiff (fn [k] (when (and (number? (cur k)) (number? (prev k)))
                          (round1 (- (double (cur k)) (double (prev k))))))
          idiff (fn [k] (when (and (number? (cur k)) (number? (prev k)))
                          (- (long (cur k)) (long (prev k)))))]
      (into {} (remove (comp nil? val))
            {:merge-rate-pct (fdiff :merge-rate-pct)
             :overall-pass-rate-pct (fdiff :overall-pass-rate-pct)
             :median-turns-to-merge (fdiff :median-turns-to-merge)
             :median-wall-s (fdiff :median-wall-s)
             :kv-cache-hit-pct (fdiff :kv-cache-hit-pct)
             :upstream-errors (idiff :upstream-errors)
             :livelocks (idiff :livelocks)
             :ooms (idiff :ooms)}))))

(defn compute-scoreboard
  "Pure derived cross-commit `:seon.bench/scoreboard` from `:seon.bench.run/entity` maps."
  {:malli/schema [:=> [:cat [:sequential :map] [:? [:maybe :map]]] :seon.bench/scoreboard] :cost/ticks 50000}
  ([runs] (compute-scoreboard runs nil))
  ([runs opts]
   (let [o (if (map? opts) opts {})
         sid (if (keyword? (:suite-id o)) (:suite-id o) :golden/v1)
         inc-leg? (or (true? (:include-legacy? o)) (= :all sid))
         matched (->> runs
                      (filter map?)
                      (filter #(or inc-leg? (= sid (:seon.bench.run/suite-id %))))
                      (sort-by (fn [r] [(long (or (when (number? (:seon.bench.run/started-ms r)) (:seon.bench.run/started-ms r)) 0))
                                        (long (or (when (number? (:seon.bench.run/main-tx r)) (:seon.bench.run/main-tx r)) 0))
                                        (str (:seon.bench.run/id r))])))
         hist (mapv summarize-run matched)
         n (count hist)
         latest (peek hist)
         prev (when (>= n 2) (nth hist (- n 2)))
         delta (compute-delta latest prev)
         mr (double (or (:merge-rate-pct latest) 0.0))
         pr (double (or (:overall-pass-rate-pct latest) 0.0))
         mt (:median-turns-to-merge latest)
         mw (:median-wall-s latest)
         kv (:kv-cache-hit-pct latest)
         up (long (or (:upstream-errors latest) 0))
         lv (long (or (:livelocks latest) 0))
         oo (long (or (:ooms latest) 0))
         lvl (cond (zero? n) :warm
                   (or (< mr 50.0) (pos? oo) (pos? lv)) :hot
                   (and (>= mr 75.0) (zero? oo) (zero? lv)) :calm
                   :else :warm)
         sum (if (zero? n)
               (str (name sid) ": no recorded runs yet")
               (str (name sid) ": " mr "% merge (" (:merged latest) "/" (:total latest) ")"
                    (when mt (str " · med " mt "t"))
                    (when mw (str " · " mw "s"))
                    (when kv (str " · " kv "% KV"))
                    " · " oo " OOM"
                    (when (pos? lv) (str " · " lv " livelock"))))]
     (with-meta
       (cond-> {:suite-id sid :level lvl :summary sum :runs-count n
                :merge-rate-pct mr :overall-pass-rate-pct pr
                :median-turns-to-merge mt :median-wall-s mw :kv-cache-hit-pct kv
                :upstream-errors up :livelocks lv :ooms oo
                :delta-vs-prev delta :latest latest :history hist}
         (seq (:by-category latest)) (assoc :by-category (:by-category latest)))
       {:seon/schema :seon.bench/scoreboard}))))

(defn golden-scoreboard
  "Pure derived `:seon.bench/scoreboard` queried from `:seon.bench.run/entity` records in `db`."
  {:malli/schema [:=> [:cat :seon.host/db [:? [:maybe :map]]] :seon.bench/scoreboard] :cost/ticks 15000000}
  ([db] (golden-scoreboard db nil))
  ([db opts]
   (st/without-agent-eval-reads
    (fn []
      (let [runs (if (and db ((db-schema db) :seon.bench.run/id))
                   (mapv #(d/pull db run-pat (:e %)) (d/datoms db :aevt :seon.bench.run/id))
                   [])]
        (compute-scoreboard runs opts))))))

(defn ^:seon.flow/edge run-golden-suite!
  "Launches the unrigged 8-task `:golden/v1` suite with commit/tx/model provenance persisted to `:seon.bench.run/entity`."
  {:malli/schema [:=> [:cat [:? [:maybe :map]]] :map] :cost/ticks 40000000}
  ([] (run-golden-suite! nil))
  ([opts]
   (let [o (if (map? opts) opts {})
         ctx (some-> (requiring-resolve 'seon.fork.calls/*agent-ctx*) deref)
         conn (:conn ctx)
         mdb (when conn (or (st/head conn :main) @conn))
         git-sha (or (:git-commit o)
                     (when (and mdb ((db-schema mdb) :seon.git/repo) (first (d/datoms mdb :avet :seon.git/repo :main)))
                       (:seon.git/commit (d/pull mdb '[:seon.git/commit] [:seon.git/repo :main])))
                     (try (git/head-commit ".") (catch Throwable _ nil)))
         main-tx (or (:main-tx o) (when mdb (:max-tx mdb)))
         model (or (:model o) (when mdb (st/config-val mdb nil :seon.config/model)))
         sid (or (:suite-id o) :golden/v1)
         tasks (select-golden-tasks o)
         run-opts (cond-> (merge {:proxy? true :seed-store "runs/store" :suite-id sid
                                  :title (str "Golden Suite " (name sid) " (" (count tasks) " tasks)")
                                  :tasks tasks}
                                 (dissoc o :only :ids :categories :sync? :publish-fn))
                    git-sha (assoc :git-commit (str git-sha))
                    main-tx (assoc :main-tx (long main-tx))
                    model (assoc :model (str model)))]
     (cond (:sync? o) (cluster/run-parallel-trial! run-opts)
           (:publish-fn o) (cluster/start-bench-run-async! run-opts (:publish-fn o))
           :else (fcalls/start-bench-run! run-opts)))))

(defn ^:seon.flow/edge repl-golden-scoreboard
  "REPL helper (`user/golden-scoreboard`): computes the cross-commit `:golden/v1` scoreboard from `:main`."
  {:malli/schema [:=> [:cat [:? [:maybe :map]]] [:maybe :seon.bench/scoreboard]] :cost/ticks 15000000}
  ([] (repl-golden-scoreboard nil))
  ([opts]
   (when-let [{:keys [conn]} (some-> (requiring-resolve 'seon.fork.calls/*agent-ctx*) deref)]
     (golden-scoreboard (or (st/head conn :main) @conn) opts))))