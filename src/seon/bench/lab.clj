(ns seon.bench.lab
  (:require [clojure.core.async :as a]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.bench.cluster :as cluster]))

(defn- ^:seon.flow/edge eval-edn [inst code]
  (let [res (cluster/instance-op! inst {:op :eval :agent :repl/lab-probe :branch :branch/lab-probe :code code})]
    (if (and (:ok? res) (string? (:value res)))
      (try (edn/read-string (:value res)) (catch Throwable _ {:raw (:value res) :ok? true}))
      {:ok? false :eval-res res})))

(defn- ^:seon.flow/edge probe-agent-detail [inst aid branch]
  (eval-edn inst
    (str "(let [conn (user/conn) mdb (user/main-db) bdb (or (when ((set (datahike.api/branches conn)) " (pr-str branch) ") (seon.store/head conn " (pr-str branch) ")) mdb) "
         "evs (->> (datahike.api/datoms bdb :avet :seon.agent.eval/agent " (pr-str aid) ") "
         "(map #(datahike.api/pull bdb '[:seon.agent.eval/turn :seon.agent.eval/call-index :seon.agent.eval/intent :seon.agent.eval/code :seon.agent.eval/error :seon.agent.eval/thought :seon.agent.eval/ms] (:e %))) "
         "(sort-by (juxt #(long (or (:seon.agent.eval/turn %) 0)) #(long (or (:seon.agent.eval/call-index %) 0)))) vec) "
         "errs (filterv #(not (clojure.string/blank? (str (:seon.agent.eval/error %)))) evs) "
         "ma (datahike.api/pull mdb '[:seon.agent/branch :seon.agent/paused? :seon.agent/merged-tx :seon.agent/tested-tx] [:seon.agent/id " (pr-str aid) "]) "
         "ed (seon.task.gate/branch-latest-edit-info mdb bdb ma) "
         "turns (count (distinct (keep :seon.agent.eval/turn evs))) "
         "clip (fn [s n] (let [t (str (or s \"\"))] (if (> (count t) n) (str (subs t 0 n) \"…\") t)))] "
         "{:ok? true :turns turns :evals (count evs) :errors (count errs) :has-edits? (boolean (:has-edits? ed)) "
         ":merged-tx (:seon.agent/merged-tx ma) :paused? (boolean (:seon.agent/paused? ma)) "
         ":trace (mapv (fn [e] (cond-> {:turn (:seon.agent.eval/turn e) :intent (:seon.agent.eval/intent e) :code (clip (:seon.agent.eval/code e) 220)} "
         "(not (clojure.string/blank? (str (:seon.agent.eval/error e)))) (assoc :error (clip (:seon.agent.eval/error e) 220)) "
         "(and (clojure.string/blank? (str (:seon.agent.eval/code e))) (not (clojure.string/blank? (str (:seon.agent.eval/thought e))))) (assoc :thought (clip (:seon.agent.eval/thought e) 260)))) evs)})")))

(defn- ^:seon.flow/edge probe-context-and-corpus [inst aid branch]
  (eval-edn inst
    (str "(let [conn (user/conn) mdb (user/main-db) bdb (or (when ((set (datahike.api/branches conn)) " (pr-str branch) ") (seon.store/head conn " (pr-str branch) ")) mdb) "
         "sim (seon.repl/simulate-context " (pr-str aid) " {:db bdb :main-db mdb :conn conn :wire? false}) "
         "cc (seon.repl/check-corpus {:db bdb :main-db mdb}) "
         "misses (filterv #(= " (pr-str aid) " (:agent %)) (seon.health.coverage/detect-trace-misses mdb bdb)) "
         "ch (try (seon.code/changed-form-ops conn (datahike.api/merge-base conn :main " (pr-str branch) ") (datahike.api/commit-id bdb)) (catch Throwable _ {})) "
         "mr (into #{} (map :seon.rule/id) (seon.rule/load-rules mdb)) "
         "br (into #{} (map :seon.rule/id) (seon.rule/load-rules bdb))] "
         "{:ok? true :selected-count (count (:selected sim)) :tokens (:tokens sim) :diff-vs-main (:diff-vs-main sim) "
         ":corpus-score (:corpus-score cc) :corpus-ok? (get-in cc [:delta-vs-main :ok?]) "
         ":generality-issues (:generality-issues cc) :misses (vec (take 8 misses)) "
         ":upserted-forms (vec (sort (keep :seon.code.form/key (:upserts ch)))) "
         ":new-rules (vec (sort (remove mr br)))})")))

(defn should-intervene?
  "Pure trigger predicate deciding when the Supervisor pauses a running Worker and forks `:template/tune-context`."
  {:malli/schema [:=> {:examples [[[{:status :runnable :turns 5 :errors 2 :has-edits? false} {:trigger-on-errors 2 :trigger-on-empty-turns 8}] :error-threshold]
                                  [[{:status :runnable :turns 8 :errors 0 :has-edits? false} {:trigger-on-errors 2 :trigger-on-empty-turns 8}] :empty-turns]
                                  [[{:status :merged :turns 6 :errors 0 :has-edits? true} {:trigger-on-errors 2 :trigger-on-empty-turns 8}] nil]
                                  [[{:status :waiting :turns 4 :errors 1 :has-edits? false} {:trigger-on-errors 2 :trigger-on-empty-turns 8}] :settled-not-merged]]}
                  [:cat :map :map] [:maybe :keyword]]
   :cost/ticks 50000}
  [{:keys [status turns errors has-edits?]} {:keys [trigger-on-errors trigger-on-empty-turns force-tune-after-turns]}]
  (let [t (long (or turns 0)) e (long (or errors 0))
        err-lim (long (or trigger-on-errors 2))
        emp-lim (long (or trigger-on-empty-turns 8))]
    (cond
      (= :merged status) nil
      (and (pos-int? force-tune-after-turns) (>= t (long force-tune-after-turns))) :forced-turn
      (and (pos? err-lim) (>= e err-lim)) :error-threshold
      (and (pos? emp-lim) (>= t emp-lim) (not has-edits?)) :empty-turns
      (contains? #{:waiting :gate-blocked :failed} status) :settled-not-merged
      :else nil)))

(defn- ^:seon.flow/edge run-tuner-intervention! [inst tid worker-id worker-br tuner-id watch opts t0]
  (let [{:keys [tuner-timeout-ms resume-timeout-ms tuner-max-turns]} opts
        _ (cluster/instance-op! inst {:op :transact :tx-data [{:seon.agent/id worker-id :seon.agent/paused? true}] :reason :lab-pause-worker})
        _ (a/<!! (a/timeout 1500))
        pre-det (probe-agent-detail inst worker-id worker-br)
        pre-ctx (probe-context-and-corpus inst worker-id worker-br)
        err-hint (->> (:trace pre-det) (keep :error) last)
        tuner-goal (str "Supervisor Pause-and-Fork for " worker-id " (trigger: " (name (:reason watch))
                        ", turns: " (:turns pre-det) ", errors: " (:errors pre-det)
                        (when err-hint (str ", latest error: " err-hint))
                        "). Diagnose the miss, fix the GENERAL rule/renderer/schema class, verify <target-context-preview> + (user/check-corpus) 12/12 (0 generality issues), roll back bad turns via @user/ctx, and reply concisely to merge back and resume " worker-id ".")
        spawn-res (cluster/instance-op! inst {:op :spawn-agent :id tuner-id :fork-from [:seon.agent/id worker-id]
                                              :merge-target [:seon.agent/id worker-id] :tasks [:template/tune-context]
                                              :auto-merge? true :pause-parent? true
                                              :config {:seon.config/max-tool-turns tuner-max-turns} :goal tuner-goal})
        tuner-br (:branch spawn-res)
        tuner-wait (cluster/wait-for-agent! inst tuner-id tuner-timeout-ms)
        tuner-row (some #(when (= tuner-id (:id %)) %) (get-in tuner-wait [:status :agents]))
        tuner-det (probe-agent-detail inst tuner-id tuner-br)
        post-tune-w (probe-agent-detail inst worker-id worker-br)
        post-tune-ctx (probe-context-and-corpus inst worker-id worker-br)
        _ (when (:paused? post-tune-w)
            (cluster/instance-op! inst {:op :transact :tx-data [{:seon.agent/id worker-id :seon.agent/paused? false}] :reason :lab-resume-worker}))
        resume-wait (cluster/wait-for-agent! inst worker-id resume-timeout-ms)
        final-row (some #(when (= worker-id (:id %)) %) (get-in resume-wait [:status :agents]))
        final-det (probe-agent-detail inst worker-id worker-br)
        final-health (cluster/instance-op! inst {:op :health})]
    {:ok? (= :merged (:status final-row)) :phase :supervised-cycle
     :task tid :worker worker-id :tuner tuner-id :trigger (:reason watch)
     :merged? (= :merged (:status final-row)) :final-worker-status (:status final-row)
     :tuner-status (:status tuner-row) :tuner-merged? (= :merged (:status tuner-row))
     :rolled-back-evals? (< (long (:evals post-tune-w 0)) (long (:evals pre-det 0)))
     :pre-worker pre-det :pre-context pre-ctx
     :tuner-detail tuner-det :post-tune-worker-evals (:evals post-tune-w) :post-tune-context post-tune-ctx
     :final-worker final-det :health-ok? (boolean (:ok? final-health))
     :elapsed-s (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e8)) 10.0)}))

(defn- ^:seon.flow/edge watch-worker-until-trigger! [inst worker-id worker-br timeout-ms trig-opts]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (a/<!! (a/timeout 1500))
      (let [st (cluster/instance-op! inst {:op :status})
            row (some #(when (= worker-id (:id %)) %) (:agents st))
            wstatus (or (:status row) :runnable)
            det (probe-agent-detail inst worker-id worker-br)
            reason (should-intervene? (assoc det :status wstatus) trig-opts)]
        (cond
          (= :merged wstatus) {:outcome :cold-merged :status wstatus :detail det}
          reason {:outcome :intervene :reason reason :status wstatus :detail det}
          (>= (System/currentTimeMillis) deadline) {:outcome :intervene :reason :timeout :status wstatus :detail det}
          :else (recur))))))

(defn ^:seon.flow/edge run-supervisor-cycle!
  "Live Pause-and-Fork Supervisor Lab: runs cold Worker on `:task`, intervenes with `:template/tune-context` on error/stall/miss, verifies rollback + context diff + corpus, and resumes Worker."
  {:seon.repl/export true
   :malli/schema [:=> [:cat :map] :map]
   :seon.eval/budget-override {:ticks 2000000000 :bytes 1073741824}}
  [{:keys [task timeout-ms tuner-timeout-ms resume-timeout-ms seed-store keep-dir?
           max-tool-turns tuner-max-turns trigger-on-errors trigger-on-empty-turns force-tune-after-turns]
    :or {timeout-ms 180000 tuner-timeout-ms 150000 resume-timeout-ms 180000 seed-store "runs/store"
         keep-dir? false max-tool-turns 20 tuner-max-turns 14 trigger-on-errors 2 trigger-on-empty-turns 8}
    :as opts}]
  (let [inner (if (map? (:task task)) (:task task) (or task opts))
        tid (or (:id inner) :lab/task)
        slug (str/replace (name tid) #"[^A-Za-z0-9._-]" "-")
        worker-id (keyword "bench" (str "w-" slug))
        worker-br (keyword "branch" (str "w-" slug))
        tuner-id (keyword "tune" (str "t-" slug))
        t0 (System/nanoTime)
        inst (cluster/start-instance! {:id (keyword "lab" slug) :seed-store seed-store :max-os-procs 3 :max-test-workers 2 :timeout-ms 60000})]
    (try
      (let [pre-ags (:agents (cluster/instance-op! inst {:op :status}))
            p-tx (vec (keep #(when (and (:id %) (not= :paused (:status %))) {:seon.agent/id (:id %) :seon.agent/paused? true}) pre-ags))]
        (when (seq p-tx) (cluster/instance-op! inst {:op :transact :tx-data p-tx :reason :lab-isolate})))
      (cluster/instance-op! inst (assoc (select-keys inner [:id :scope :title :plan :tests :needs]) :op :create-task))
      (cluster/instance-op! inst (cond-> {:op :spawn-agent :id worker-id :branch worker-br :task tid
                                          :goal (str (or (:title inner) (name tid))) :auto-merge? true
                                          :config {:seon.config/auto-refine-tasks? false :seon.config/max-tool-turns max-tool-turns}}
                                   (:scope inner) (assoc :scope (:scope inner))))
      (cluster/instance-op! inst {:op :send-message :from :user :to worker-id :type :chat
                                  :body "Work your assigned task, verify your changes pass tests and health, then reply concisely with your summary."})
      (let [watch (watch-worker-until-trigger! inst worker-id worker-br timeout-ms
                                               {:trigger-on-errors trigger-on-errors :trigger-on-empty-turns trigger-on-empty-turns
                                                :force-tune-after-turns force-tune-after-turns})]
        (if (= :cold-merged (:outcome watch))
          {:ok? true :phase :cold-merged :task tid :worker worker-id :merged? true
           :worker-pre (:detail watch) :elapsed-s (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e8)) 10.0)}
          (run-tuner-intervention! inst tid worker-id worker-br tuner-id watch
                                   {:tuner-timeout-ms tuner-timeout-ms :resume-timeout-ms resume-timeout-ms :tuner-max-turns tuner-max-turns} t0)))
      (finally
        (cluster/stop-instance! inst {:keep-dir? keep-dir?})))))
