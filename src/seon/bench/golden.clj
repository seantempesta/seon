(ns seon.bench.golden
  "Unrigged 8-task `:golden/v1` benchmark suite and persistent cross-commit scoreboard."
  (:require [datahike.api :as d]
            [seon.bench.cluster :as cluster]
            [seon.fork.calls :as fcalls]
            [seon.git :as git]
            [seon.render.text :refer [round1]]
            [seon.store :as store :refer [db-schema]]
            [seon.bench.ab :as ab]))

(def ^:private run-pull-pattern
  '[:seon.bench.run/id :seon.bench.run/title :seon.bench.run/status
    :seon.bench.run/started-ms :seon.bench.run/finished-ms
    :seon.bench.run/total :seon.bench.run/passed :seon.bench.run/failed
    :seon.bench.run/trials :seon.bench.run/proxy-stats
    :seon.bench.run/suite-id :seon.bench.run/git-commit
    :seon.bench.run/main-tx :seon.bench.run/model])

(defn- suite-label [suite-id]
  (if (namespace suite-id)
    (str (namespace suite-id) "/" (name suite-id))
    (name suite-id)))

(defn golden-suite-v1
  "Returns the unrigged 8-task `:golden/v1` benchmark suite (1-2 sentence human prompts, zero recipes)."
  {:malli/schema [:=> [:cat] [:vector :map]] :cost/ticks 5000}
  []
  [{:id :golden/v1-split-calls
    :suite-id :golden/v1 :category :ns-split
    :run-tests? true :merge? true :require-merge? true :timeout-ms 180000
    :task {:id :golden/v1-split-calls :scope 'seon.transactor.calls
           :title "Split the oversized transactor calls namespace"
           :plan "The transactor calls namespace has grown past the namespace size budget. Extract cohesive helper logic into a sibling namespace so every namespace stays comfortably under budget without breaking callers or tests."}}
   {:id :golden/v1-metrics-schema
    :suite-id :golden/v1 :category :schema-proof
    :run-tests? true :merge? true :require-merge? true :timeout-ms 180000
    :task {:id :golden/v1-metrics-schema :scope 'seon.health.metrics
           :title "Replace loose map return contract on the agent eval summary helper"
           :plan "The public agent eval summary helper still declares an untyped map return contract instead of explicit result keys. Replace its loose map return contract with a structured contract and prove all pure functions in the metrics namespace pass generative checks."}}
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
   {:id :golden/v1-split-canvas-body
    :suite-id :golden/v1 :category :large-fn-refactor
    :run-tests? true :merge? true :require-merge? true :timeout-ms 180000
    :task {:id :golden/v1-split-canvas-body :scope 'seon.render.surface.canvas
           :title "Decompose the oversized canvas body renderer into cohesive helpers"
           :plan "The main canvas body rendering function exceeds the function size budget and triggers a static health warning. Extract cohesive section helpers so every function in the canvas namespace stays well under budget while preserving exact rendered output and passing tests."}}
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
    :min-delegated-calls 2
    :run-tests? false :merge? false :require-merge? false :timeout-ms 180000
    :body "Please delegate two quick read-only checks in parallel—one checking current health coverage status and one checking the largest namespaces—and give me a concise combined summary once both finish."}])

(defn select-golden-tasks
  "Filters `(golden-suite-v1)` by optional `:only`/`:ids`, `:categories`, and `:task-timeout-ms`/`:timeout-ms`."
  {:malli/schema [:=> [:cat [:? [:maybe :map]]] [:vector :map]] :cost/ticks 10000}
  ([] (golden-suite-v1))
  ([opts]
   (let [options (if (map? opts) opts {})
         selected-ids (when-let [xs (or (:only options) (:ids options))] (set xs))
         selected-cats (when-let [cs (:categories options)] (set cs))
         timeout-ms (or (:task-timeout-ms options) (:timeout-ms options))]
     (->> (golden-suite-v1)
          (filter #(and (or (nil? selected-ids) (selected-ids (:id %)))
                        (or (nil? selected-cats) (selected-cats (:category %)))))
          (mapv #(cond-> % (pos-int? timeout-ms) (assoc :timeout-ms (long timeout-ms))))))))

(defn- median [& args] (apply ab/median args))

(defn- trial-merged? [& args] (apply ab/trial-merged? args))

(defn- trial-passed? [trial]
  (boolean (or (trial-merged? trial) (= :passed (:status trial)))))

(defn- summarize-run [run-rec]
  (let [trials (vec (filter map? (when (sequential? (:seon.bench.run/trials run-rec))
                                   (:seon.bench.run/trials run-rec))))
        total (long (or (when (number? (:seon.bench.run/total run-rec)) (:seon.bench.run/total run-rec))
                        (count trials)))
        passed (long (if (seq trials)
                       (count (filter trial-passed? trials))
                       (or (when (number? (:seon.bench.run/passed run-rec)) (:seon.bench.run/passed run-rec)) 0)))
        merged (count (filter trial-merged? trials))
        merge-eligible (let [eligible (remove #(= :root-delegation (:category %)) trials)]
                         (if (seq eligible) (count eligible) total))
        merge-rate-pct (if (pos? merge-eligible) (round1 (* 100.0 (/ (double merged) merge-eligible))) 0.0)
        pass-rate-pct (if (pos? total) (round1 (* 100.0 (/ (double passed) total))) 0.0)
        merge-turns (keep (fn [trial]
                            (when (trial-merged? trial)
                              (or (when (number? (:turns trial)) (:turns trial))
                                  (when (number? (:evals trial)) (:evals trial)))))
                          trials)
        wall-seconds (keep #(when (and (number? (:elapsed-ms %)) (pos? (double (:elapsed-ms %))))
                              (/ (double (:elapsed-ms %)) 1000.0))
                           trials)
        kv-hit-tokens (reduce + 0 (keep #(when (number? (:kv-hit-tokens %)) (long (:kv-hit-tokens %))) trials))
        kv-miss-tokens (reduce + 0 (keep #(when (number? (:kv-miss-tokens %)) (long (:kv-miss-tokens %))) trials))
        proxy-stats (when (map? (:seon.bench.run/proxy-stats run-rec)) (:seon.bench.run/proxy-stats run-rec))
        kv-hit-pct (cond
                     (pos? (+ kv-hit-tokens kv-miss-tokens))
                     (round1 (* 100.0 (/ (double kv-hit-tokens) (+ kv-hit-tokens kv-miss-tokens))))
                     (and (number? (:cache-hit-tokens proxy-stats))
                          (number? (:prompt-tokens proxy-stats))
                          (pos? (long (:prompt-tokens proxy-stats))))
                     (round1 (* 100.0 (/ (double (:cache-hit-tokens proxy-stats))
                                         (long (:prompt-tokens proxy-stats)))))
                     :else nil)
        upstream-errors (reduce + 0 (keep #(when (number? (:upstream-errors %)) (max 0 (long (:upstream-errors %)))) trials))
        livelocks (count (filter #(true? (:turn-overrun? %)) trials))
        timeouts (count (filter #(= :timeout (:status %)) trials))
        ooms (reduce + 0 (keep #(when (number? (:ooms %)) (max 0 (long (:ooms %)))) trials))
        by-category (into {}
                          (for [[category cat-trials] (group-by :category (filter #(keyword? (:category %)) trials))]
                            [category {:total (count cat-trials)
                                       :passed (count (filter trial-passed? cat-trials))
                                       :merged (count (filter trial-merged? cat-trials))
                                       :median-turns (median (keep #(or (:turns %) (:evals %)) cat-trials))}]))]
    (cond-> {:run-id (:seon.bench.run/id run-rec)
             :git-commit (when (string? (:seon.bench.run/git-commit run-rec)) (:seon.bench.run/git-commit run-rec))
             :main-tx (when (number? (:seon.bench.run/main-tx run-rec)) (long (:seon.bench.run/main-tx run-rec)))
             :model (when (string? (:seon.bench.run/model run-rec)) (:seon.bench.run/model run-rec))
             :status (let [status (:seon.bench.run/status run-rec)] (if (keyword? status) status :failed))
             :started-ms (long (or (when (number? (:seon.bench.run/started-ms run-rec)) (:seon.bench.run/started-ms run-rec)) 0))
             :total (max 0 total)
             :merge-eligible (max 0 merge-eligible)
             :passed (max 0 passed)
             :merged (max 0 merged)
             :merge-rate-pct merge-rate-pct
             :overall-pass-rate-pct pass-rate-pct
             :median-turns-to-merge (median merge-turns)
             :median-wall-s (median wall-seconds)
             :kv-cache-hit-pct kv-hit-pct
             :upstream-errors upstream-errors
             :livelocks livelocks
             :timeouts timeouts
             :ooms ooms}
      (seq by-category) (assoc :by-category by-category))))

(defn- compute-delta [current previous]
  (when (and (map? current) (map? previous))
    (let [float-diff (fn [metric-key]
                       (when (and (number? (current metric-key)) (number? (previous metric-key)))
                         (round1 (- (double (current metric-key)) (double (previous metric-key))))))
          int-diff (fn [metric-key]
                     (when (and (number? (current metric-key)) (number? (previous metric-key)))
                       (- (long (current metric-key)) (long (previous metric-key)))))]
      (into {} (remove (comp nil? val))
            {:merge-rate-pct (float-diff :merge-rate-pct)
             :overall-pass-rate-pct (float-diff :overall-pass-rate-pct)
             :median-turns-to-merge (float-diff :median-turns-to-merge)
             :median-wall-s (float-diff :median-wall-s)
             :kv-cache-hit-pct (float-diff :kv-cache-hit-pct)
             :upstream-errors (int-diff :upstream-errors)
             :livelocks (int-diff :livelocks)
             :timeouts (int-diff :timeouts)
             :ooms (int-diff :ooms)}))))

(defn compute-scoreboard
  "Pure derived cross-commit `:seon.bench/scoreboard` from `:seon.bench.run/entity` maps."
  {:malli/schema [:=> [:cat [:sequential :map] [:? [:maybe :map]]] :seon.bench/scoreboard] :cost/ticks 50000}
  ([runs] (compute-scoreboard runs nil))
  ([runs opts]
   (let [options (if (map? opts) opts {})
         suite-id (if (keyword? (:suite-id options)) (:suite-id options) :golden/v1)
         include-legacy? (or (true? (:include-legacy? options)) (= :all suite-id))
         matched (->> runs
                      (filter map?)
                      (filter #(or include-legacy? (= suite-id (:seon.bench.run/suite-id %))))
                      (sort-by (fn [run-rec]
                                 [(long (or (when (number? (:seon.bench.run/started-ms run-rec))
                                              (:seon.bench.run/started-ms run-rec)) 0))
                                  (long (or (when (number? (:seon.bench.run/main-tx run-rec))
                                              (:seon.bench.run/main-tx run-rec)) 0))
                                  (str (:seon.bench.run/id run-rec))])))
         history (mapv summarize-run matched)
         runs-count (count history)
         latest (peek history)
         previous (when (>= runs-count 2) (nth history (- runs-count 2)))
         delta (compute-delta latest previous)
         merge-rate-pct (double (or (:merge-rate-pct latest) 0.0))
         pass-rate-pct (double (or (:overall-pass-rate-pct latest) 0.0))
         median-turns (:median-turns-to-merge latest)
         median-wall-s (:median-wall-s latest)
         kv-hit-pct (:kv-cache-hit-pct latest)
         upstream-errors (long (or (:upstream-errors latest) 0))
         livelocks (long (or (:livelocks latest) 0))
         timeouts (long (or (:timeouts latest) 0))
         ooms (long (or (:ooms latest) 0))
         level (cond
                 (zero? runs-count) :warm
                 (or (< merge-rate-pct 50.0) (pos? ooms) (pos? livelocks)) :hot
                 (and (>= merge-rate-pct 75.0) (zero? ooms) (zero? livelocks) (zero? timeouts)) :calm
                 :else :warm)
         label (suite-label suite-id)
         merge-denom (or (:merge-eligible latest) (:total latest) 0)
         summary (if (zero? runs-count)
                   (str label ": no recorded runs yet")
                   (str label ": " merge-rate-pct "% merge (" (:merged latest) "/" merge-denom ")"
                        (when median-turns (str " · med " median-turns "t"))
                        (when median-wall-s (str " · " median-wall-s "s"))
                        (when kv-hit-pct (str " · " kv-hit-pct "% KV"))
                        " · " ooms " OOM"
                        (when (pos? livelocks) (str " · " livelocks " livelock"))
                        (when (pos? timeouts) (str " · " timeouts " timeout"))))]
     (with-meta
       (cond-> {:suite-id suite-id :level level :summary summary :runs-count runs-count
                :merge-rate-pct merge-rate-pct :overall-pass-rate-pct pass-rate-pct
                :median-turns-to-merge median-turns :median-wall-s median-wall-s
                :kv-cache-hit-pct kv-hit-pct :upstream-errors upstream-errors
                :livelocks livelocks :timeouts timeouts :ooms ooms
                :delta-vs-prev delta :latest latest :history history}
         (seq (:by-category latest)) (assoc :by-category (:by-category latest)))
       {:seon/schema :seon.bench/scoreboard}))))

(defn golden-scoreboard
  "Pure derived `:seon.bench/scoreboard` queried from `:seon.bench.run/entity` records in `db`."
  {:malli/schema [:=> [:cat :seon.host/db [:? [:maybe :map]]] :seon.bench/scoreboard] :cost/ticks 15000000}
  ([db] (golden-scoreboard db nil))
  ([db opts]
   (store/without-agent-eval-reads
    (fn []
      (let [runs (if (and db ((db-schema db) :seon.bench.run/id))
                   (mapv #(d/pull db run-pull-pattern (:e %)) (d/datoms db :aevt :seon.bench.run/id))
                   [])]
        (compute-scoreboard runs opts))))))

(defn ^:seon.flow/edge run-golden-suite!
  "Launches the unrigged 8-task `:golden/v1` suite with commit/tx/model provenance persisted to `:seon.bench.run/entity`."
  {:malli/schema [:=> [:cat [:? [:maybe :map]]] :map] :cost/ticks 40000000}
  ([] (run-golden-suite! nil))
  ([opts]
   (let [options (if (map? opts) opts {})
         ctx (some-> (requiring-resolve 'seon.fork.calls/*agent-ctx*) deref)
         conn (:conn ctx)
         main-db (when conn (or (store/head conn :main) @conn))
         git-sha (or (:git-commit options)
                     (when (and main-db ((db-schema main-db) :seon.git/repo)
                                (first (d/datoms main-db :avet :seon.git/repo :main)))
                       (:seon.git/commit (d/pull main-db '[:seon.git/commit] [:seon.git/repo :main])))
                     (try (git/head-commit ".") (catch Throwable _ nil)))
         main-tx (or (:main-tx options) (when main-db (:max-tx main-db)))
         model-name (or (:model options) (when main-db (store/config-val main-db nil :seon.config/ai-model)))
         suite-id (or (:suite-id options) :golden/v1)
         tasks (select-golden-tasks options)
         run-opts (cond-> (merge {:proxy? true :seed-store "runs/store" :suite-id suite-id
                                  :title (str "Golden Suite " (suite-label suite-id) " (" (count tasks) " tasks)")
                                  :tasks tasks}
                                 (dissoc options :only :ids :categories :sync? :publish-fn))
                    git-sha (assoc :git-commit (str git-sha))
                    main-tx (assoc :main-tx (long main-tx))
                    model-name (assoc :model (str model-name)))]
     (cond (:sync? options) (cluster/run-parallel-trial! run-opts)
           (:publish-fn options) (cluster/start-bench-run-async! run-opts (:publish-fn options))
           :else (fcalls/start-bench-run! run-opts)))))

(defn ^:seon.flow/edge repl-golden-scoreboard
  "REPL helper (`user/golden-scoreboard`): computes the cross-commit `:golden/v1` scoreboard from `:main`."
  {:malli/schema [:=> [:cat [:? [:maybe :map]]] [:maybe :seon.bench/scoreboard]] :cost/ticks 15000000}
  ([] (repl-golden-scoreboard nil))
  ([opts]
   (when-let [{:keys [conn]} (some-> (requiring-resolve 'seon.fork.calls/*agent-ctx*) deref)]
     (golden-scoreboard (or (store/head conn :main) @conn) opts))))

(defn ^{:seon.code/reexport 'seon.bench.ab/build-ab-tasks} build-ab-tasks [& args] (apply ab/build-ab-tasks args))

(defn ^{:seon.code/reexport 'seon.bench.ab/summarize-ab} summarize-ab [& args] (apply ab/summarize-ab args))

(defn ^:seon.flow/edge run-ab!
  "REPL helper (`user/run-ab!`): runs paired A/B isolated Seon trials directly inside Seon.
   Pass `:tasks` (defaults to `(select-golden-tasks opts)`), `:arms` (map `{:control {}}` or seq `[:control]` / `[[:control {}]]`),
   `:sync? true` for blocking REPL results with `:ab-summary`, or `:sync? false` to stream live to `#cv-bench-runs`."
  {:malli/schema [:=> [:cat [:? [:maybe :map]]] :map] :cost/ticks 40000000}
  ([] (run-ab! nil))
  ([opts]
   (let [o (if (map? opts) opts {})
         ctx (some-> (requiring-resolve 'seon.fork.calls/*agent-ctx*) deref)
         conn (:conn ctx)
         main-db (when conn (or (store/head conn :main) @conn))
         base-tasks (or (seq (:tasks o)) (seq (:base-tasks o)) (select-golden-tasks o))
         arms (or (:arms o) [[:control {:seon.config/auto-refine-tasks? false :seon.config/max-tool-turns 24}]
                             [:treatment {:seon.config/auto-refine-tasks? true :seon.config/max-tool-turns 24}]])
         trials (build-ab-tasks base-tasks arms o)
         suite-id (or (:suite-id o) :ab/experiment)
         git-sha (or (:git-commit o)
                     (when (and main-db ((db-schema main-db) :seon.git/repo)
                                (first (d/datoms main-db :avet :seon.git/repo :main)))
                       (:seon.git/commit (d/pull main-db '[:seon.git/commit] [:seon.git/repo :main]))))
         main-tx (or (:main-tx o) (when main-db (:max-tx main-db)))
         model-name (or (:model o) (when main-db (store/config-val main-db nil :seon.config/ai-model)))
         base-run-opts (cond-> (merge {:proxy? (if (contains? o :proxy?) (boolean (:proxy? o)) true)
                                       :seed-store "runs/store" :suite-id suite-id
                                       :title (or (:title o) (str "Paired A/B (" (count base-tasks) " tasks × " (count arms) " arms)"))
                                       :max-os-procs 3 :max-test-workers 2}
                                      (dissoc o :tasks :base-tasks :arms :only :ids :categories :sync? :by-pair? :publish-fn :include-trace?))
                         git-sha (assoc :git-commit (str git-sha))
                         main-tx (assoc :main-tx (long main-tx))
                         model-name (assoc :model (str model-name)))]
     (cond
       (empty? trials)
       {:ok? false :error :no-trials
        :hint "No trials produced — pass non-empty :tasks and valid :arms (map {:control {}} or seq [:control] / [[:control {}]])."}

       (:sync? o)
       (let [by-pair? (not (false? (:by-pair? o)))
             batches (if by-pair?
                       (mapv (fn [pid] (filterv #(= pid (:pair-id %)) trials))
                             (distinct (map :pair-id trials)))
                       [trials])
             res-list (mapv #(cluster/run-parallel-trial! (assoc base-run-opts :tasks %)) batches)
             all-results (vec (mapcat :results res-list))]
         {:ok? (every? :ok? all-results)
          :count (count all-results)
          :elapsed-ms (round1 (reduce + 0.0 (map #(double (or (:elapsed-ms %) 0.0)) res-list)))
          :ab-summary (summarize-ab all-results)
          :results all-results})

       :else
       (let [run-opts (assoc base-run-opts :tasks trials)]
         (if (:publish-fn o)
           (cluster/start-bench-run-async! run-opts (:publish-fn o))
           (fcalls/start-bench-run! run-opts)))))))