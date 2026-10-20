(ns seon.e2e
  "Tasks end to end with real agent processes (lanes R1 and M1's proto.agents.e2e): the
  six-task graph with a cancel, a root restart, n parallel tasks, the merge scenarios and a
  swarm over shared definitions. bin/check runs them as modes of target/seon:
    calc | resume DIR | resume2 DIR | par N | scenario conflict|semantic|hairy|newns|sibling [model] | swarm N M | model add|calc
  Prints one EDN line per phase; the last line is the run's numbers and its verdict."
  (:require [seon.root :as c] [my.root :as root] [seon.task :as task] [seon.reconcile :as r]
            [seon.fixtures :as fx] [datahike.api :as d] [seon.lib :as lib] [clojure.string :as str]))

(defn- now [] (System/currentTimeMillis))
(defn- db [sys] @(:conn sys))

(defn- wait-until
  "Poll (pred) every 50 ms until true or ms pass; answers whether it held."
  [pred ms]
  (let [end (+ (now) ms)] (loop [] (cond (pred) true (> (now) end) false :else (do (Thread/sleep 250) (recur))))))

(defn summary
  "Per task: execution, done, attempts as [id status outcome turns tokens], problem."
  [d]
  (let [idx (r/index d)]
    (into (sorted-map)
          (for [[id t] (:tasks idx)]
            [id (cond-> {:execution (:seon.task/execution t) :done (r/done? idx id)
                         :attempts (mapv (juxt :seon.attempt/id :seon.attempt/status :seon.attempt/outcome :seon.attempt/turns :seon.attempt/tokens)
                                         (sort-by :seon.attempt/id (map (:attempts idx) (get-in idx [:by-task id]))))}
                  (:seon.task/problem t) (assoc :problem (:seon.task/problem t)))]))))

(defn- seed! [sys specs]
  (doseq [tx (fx/program-txs specs)] (println :program (select-keys @(c/transact-main! sys tx) [:ok :datoms]))))

(defn agents-reused
  "Agent ids that more than one attempt used (must be empty: every attempt is a new agent)."
  [d] (vec (for [[ag n] (frequencies (map :seon.attempt/agent (vals (task/attempts d)))) :when (> n 1)] ag)))

(defn- merge-counts [d]
  (let [ts (task/tasks d)
        merge-as (filter #(= :merge (get-in ts [(:seon.attempt/task %) :seon.task/kind])) (vals (task/attempts d)))]
    {:merge-attempts (frequencies (map :seon.attempt/status merge-as))
     :behind-reattempts (count (filter #(= :behind (:seon.attempt/status %)) merge-as))
     :conflicts-measured (reduce + (map #(count (:seon.attempt/conflicts % [])) merge-as))
     :attached-tasks (count (filter #(pos? (:seon.task/generation % 0)) (vals ts)))
     :model-calls-in-merges (reduce + (map #(:seon.attempt/model-calls % 0) merge-as))}))

(defn- numbers [sys t0]
  (let [as (vals (task/attempts (db sys)))
        merged (filter #(= :merged (:seon.attempt/status %)) as)]
    {:wall-ms (- (now) t0) :attempts (count as) :merged (count merged)
     :turns-per-merged-task (when (seq merged) (lib/r2 (/ (double (reduce + (map #(:seon.attempt/turns % 0) merged))) (count merged))))
     :model-calls (reduce + (map #(:seon.attempt/model-calls % 0) as))
     :tokens (reduce + (map #(:seon.attempt/tokens % 0) as))
     :root-real-mb (lib/r2 (lib/real-mb)) :writer (:writer (c/state sys))
     :root-errors (vec (take 3 (:root-errors (c/state sys))))}))

(defn- settled? [sys]
  (let [idx (r/index (db sys)) ts (:tasks idx)]
    (and (seq ts) (empty? (r/running idx))
         (every? (fn [[id t]] (or (r/done? idx id) (#{:held :cancelled} (:seon.task/execution t)))) ts))))

(defn- fresh [sys forms]
  (let [ag (c/spawn! sys {})]
    (Thread/sleep 300)
    (let [r (mapv :printed @(c/submit! sys ag forms))] (c/kill! sys ag) r)))

(defn- verdict [ok] (println (if ok "E2E OK" "E2E FAILED")) ok)

(defn calc
  "The six-task graph: release all, cancel neg mid-run, wait for the rest to merge."
  [path]
  (let [t0 (now)
        sys (c/start! (cond-> {:datoms 300 :max-running 2} path (assoc :path path)))
        ds (fx/decls fx/calc 'calc nil)
        ids (mapv #(task/task-id 'calc (:seon.task/name %)) ds)]
    (seed! sys fx/calc)
    (println :create (select-keys (root/create! sys ds) [:ok :datoms]))
    (root/run! sys ids)
    ;; neg sleeps 8 s on its first turn: cancel it while its agent runs
    (wait-until #(some (fn [aid] (str/starts-with? aid "calc/neg#")) (r/running (r/index (db sys)))) 20000)
    (Thread/sleep 300)
    (println :cancel-neg (select-keys (root/cancel! sys ["calc/neg"]) [:ok]))
    (let [merges ["calc/add-merge" "calc/twice-merge" "calc/square-merge" "calc/sum-sq-merge"]
          done? (wait-until #(let [idx (r/index (db sys))] (every? (fn [id] (r/done? idx id)) merges)) 60000)
          _ (Thread/sleep 500)
          blocked (r/blocked-by (r/index (db sys)) "calc/abs")
          reused (agents-reused (db sys))
          fresh-r (fresh sys "(app.calc/sum-sq 3 4)\n(count (my.db/q '[:find ?e :where [?e :seon.turn/id]]))")]
      (println :done-all? done? :abs-blocked-by blocked :agents-reused reused :fresh-agent fresh-r)
      (doseq [[id s] (summary (db sys))] (println :task id s))
      (println :numbers (numbers sys t0))
      (c/stop! sys)
      (verdict (and done? (= #{"calc/neg-merge"} blocked) (empty? reused) (= "25" (first fresh-r)) (= "0" (second fresh-r)))))))

(defn resume
  "Phase 1 of a restart: calc on a durable store; root SIGKILLs itself mid-run."
  [path]
  (let [sys (c/start! {:datoms 300 :max-running 2 :path path})
        ds (fx/decls fx/calc 'calc nil)]
    (seed! sys fx/calc)
    (root/create! sys ds)
    (root/run! sys (mapv #(task/task-id 'calc (:seon.task/name %)) ds))
    (wait-until #(r/done? (r/index (db sys)) "calc/add-merge") 30000)
    (wait-until #(seq (r/running (r/index (db sys)))) 10000)
    (println :running-at-kill (r/running (r/index (db sys))))
    (flush)
    (lib/c-kill (lib/c-getpid) 9)))

(defn resume2
  "Phase 2: a new root on the store reaps the lost attempts and finishes the graph."
  [path]
  (let [t0 (now)
        sys (c/start! {:datoms 300 :max-running 2 :path path})
        _ (println :recovered (get-in (c/state sys) [:store :recovered-branches]))
        ok (wait-until #(let [idx (r/index (db sys))] (every? (fn [id] (r/done? idx id)) (map (fn [n] (str "calc/" n "-merge")) ["add" "twice" "square" "sum-sq" "neg" "abs"]))) 90000)]
    (doseq [[id s] (summary (db sys))] (println :task id s))
    (println :numbers (numbers sys t0))
    (c/stop! sys)
    (verdict ok)))

(defn par
  "n independent tasks at once, max-running n."
  [n]
  (let [t0 (now)
        sys (c/start! {:datoms 300 :max-running n})
        specs (fx/par n)
        ds (fx/decls specs 'par nil)]
    (seed! sys specs)
    (root/create! sys ds)
    (let [t1 (now)
          _ (root/run! sys (mapv #(task/task-id 'par (:seon.task/name %)) ds))
          ;; a cheap observer: count the :merged status datoms (no pull of every attempt)
          ok (wait-until #(= n (count (filter (fn [dt] (= :merged (:v dt))) (d/datoms (db sys) :aevt :seon.attempt/status)))) 180000)]
      (println :all-merged? ok :release-to-all-merged-ms (- (now) t1))
      (println :not-merged (into {} (remove (comp :done val)) (summary (db sys))))
      (let [d (db sys) t (System/nanoTime)] (r/reconcile d) (println :reconcile-ms (/ (- (System/nanoTime) t) 1e6)))
      (println :numbers (numbers sys t0))
      (c/stop! sys)
      (verdict ok))))

(defn scenario
  "A merge scenario of fixtures/scenarios (conflict | semantic | hairy): its tasks with their
  merge tasks, each merge after its :after, all released; then what main runs."
  [k provider check expect]
  (let [t0 (now)
        {:keys [specs after]} (get fx/scenarios k)
        sys (c/start! (cond-> {:datoms 300 :max-running 4} provider (assoc :provider provider)))
        tns (symbol (name k))
        ds (fx/decls specs tns nil)]
    (seed! sys specs)
    (println :create (select-keys (root/create! sys (mapv #(assoc % :seon.task/config {:seon.config.agent/max-turns 4}) ds)) [:ok]))
    (println :after (select-keys @(c/transact-main! sys (vec (for [[m n] after] [:db/add [:seon.task/id (str tns "/" m)] :seon.task/needs [:seon.task/id (str tns "/" n)]]))) [:ok]))
    (root/run! sys (mapv #(task/task-id tns (:seon.task/name %)) ds))
    (println :settled? (wait-until #(settled? sys) 120000))
    (Thread/sleep 300)
    (let [d (db sys)
          fresh-r (fresh sys check)]
      (doseq [[id s] (summary d)] (println :task id s))
      (println :agents-reused (agents-reused d) :merges (merge-counts d) :fresh-agent fresh-r)
      (println :numbers (numbers sys t0))
      (c/stop! sys)
      (verdict (and (empty? (agents-reused d)) (= expect fresh-r))))))

(defn swarm
  "n tasks over 5 shared definitions, max-running m: every conflict resolved by a merge
  agent or ended in attached tasks; no agent reused."
  [n m]
  (let [t0 (now)
        specs (fx/swarm-specs n)
        sys (c/start! {:datoms 300 :max-running m})
        ds (fx/decls specs 'sw nil)]
    (seed! sys specs)
    (root/create! sys ds)
    (let [t1 (now)
          _ (root/run! sys (mapv #(task/task-id 'sw (:seon.task/name %)) ds))
          ok (wait-until #(settled? sys) 300000)
          d (db sys)
          not-done (into {} (remove (comp :done val)) (summary d))
          fresh-r (fresh sys "(mapv #(% 100) [app.sw/v0 app.sw/v1 app.sw/v2 app.sw/v3 app.sw/v4])")]
      (println :settled? ok :release-to-settled-ms (- (now) t1) :not-done not-done)
      (println :agents-reused (agents-reused d) :merges (merge-counts d) :fresh-agent fresh-r)
      (println :numbers (numbers sys t0))
      (c/stop! sys)
      (verdict (and ok (empty? not-done) (empty? (agents-reused d)) (= ["[100 101 102 103 104]"] fresh-r))))))

(defn model-run
  "Tasks of the calc graph worked by DeepSeek (SEON_AI_*): which = add (one task) or calc."
  [which]
  (let [t0 (now)
        provider {:kind :openai-compat :base-url (System/getenv "SEON_AI_BASE_URL") :model (System/getenv "SEON_AI_MODEL")
                  :api-key-env (System/getenv "SEON_AI_API_KEY_ENV")}
        specs (if (= which "add") [(first fx/calc)] fx/calc)
        sys (c/start! {:datoms 300 :max-running 6 :provider provider})
        ds (fx/decls specs 'calc nil)
        ids (mapv #(task/task-id 'calc (:seon.task/name %)) ds)]
    (println :provider (dissoc provider :api-key-env))
    (seed! sys specs)
    (root/create! sys (mapv #(assoc % :seon.task/config {:seon.config.agent/max-turns 4}) ds))
    (root/run! sys ids)
    (let [ok (wait-until #(let [d (db sys)] (let [idx (r/index d)] (every? (fn [id] (or (r/done? idx id) (= :held (get-in idx [:tasks id :seon.task/execution])))) ids))) 300000)]
      (doseq [[id s] (summary (db sys))] (println :task id s))
      (println :fresh-agent (fresh sys (if (= which "add") "(app.calc/add 1 2)" "[(app.calc/add 1 2) (app.calc/sum-sq 3 4) (app.calc/abs -5)]")))
      (println :numbers (numbers sys t0))
      (c/stop! sys)
      (verdict ok))))

(defn run
  "One e2e mode; answers whether it passed."
  [mode args]
  (case mode
    "calc" (calc (first args))
    "resume" (resume (first args))
    "resume2" (resume2 (first args))
    "par" (par (parse-long (first args)))
    "scenario" (let [k (keyword (first args))
                     pv (when (= "model" (second args))
                          {:kind :openai-compat :base-url (System/getenv "SEON_AI_BASE_URL") :model (System/getenv "SEON_AI_MODEL")
                           :api-key-env (System/getenv "SEON_AI_API_KEY_ENV")})]
                 (case k
                   :conflict (scenario k pv "(app.m/f 41)" ["42"])
                   :semantic (scenario k pv "[(app.m/scale 3) (app.m/six)]" ["[9 6]"])
                   :hairy (scenario k pv "(mapv #(% 10) [app.m/f1 app.m/f2 app.m/f3 app.m/f4])" ["[11 12 13 14]"])
                   ;; main runs the new namespaces as written: the function, data still data,
                   ;; the var still dynamic, the test (its :refer) passing, user's def absent,
                   ;; and the test reaching triple
                   :sibling (scenario k pv "[(app.s/f 1) (app.s/g 10)]" ["[2 30]"])
                   :newns (scenario k pv (str "[(app.newns/triple 3) app.newns/ops app.newns/syms"
                                              " (:dynamic (meta #'app.newns/*depth*)) (binding [app.newns/*depth* 1] app.newns/*depth*)"
                                              " ((juxt :test :pass :fail :error) (binding [clojure.test/*test-out* (java.io.StringWriter.)] (clojure.test/test-ns 'app.newns-test)))"
                                              " (some? (resolve 'user/scratch-x))"
                                              " (seon.program/reaching-tests (seon.agent/db) #{'app.newns/triple})]")
                                    ["[9 (inc 1) [a b] true 1 [1 1 0 0] false #{app.newns-test/triple-test}]"])))
    "swarm" (swarm (parse-long (first args)) (parse-long (or (second args) "8")))
    "model" (model-run (first args))))
