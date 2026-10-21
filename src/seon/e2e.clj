(ns seon.e2e
  "Tasks end to end with real agent processes (lanes R1 and M1's proto.agents.e2e): the
  six-task graph with a cancel, a root restart, n parallel tasks, the merge scenarios, a
  swarm over shared definitions, and a task that always fails ending stuck. bin/check runs
  them as modes of target/seon:
    calc | resume DIR | resume2 DIR | par N | scenario conflict|semantic|hairy|newns|sibling [model] | swarm N M | model add|calc | stuck
    | loop DIR (the self-improvement loop's first turn, on a clone of this repository under DIR)
  Each run ends on what the system observes (the reconciler's quiescence report, or a fact
  on main), never on a deadline. Prints one EDN line per phase; the last line is the run's
  numbers and its verdict."
  (:require [seon.root :as c] [my.root :as root] [seon.task :as task] [seon.reconcile :as r]
            [seon.fixtures :as fx] [datahike.api :as d] [seon.lib :as lib] [clojure.string :as str] [clojure.java.shell :as sh]))

(defn- now [] (System/currentTimeMillis))
(defn- db [sys] @(:conn sys))

(defn await-db
  "Main's first db value (now or after a later commit) for which (pred db) holds: a watch
  on main's connection runs pred once per commit. No deadline, no polling."
  [sys pred]
  (let [p (promise) k (keyword "seon.e2e" (str "await-" (random-uuid)))
        a (:wrapped-atom (:conn sys))]
    (add-watch a k (fn [_ _ _ db] (when (pred db) (deliver p db))))
    (let [db @(:conn sys)] (when (pred db) (deliver p db)))
    (let [db @p] (remove-watch a k) db)))

(defn- defect
  "A root proc's defect recorded on main (a :platform problem) last seen at or after
  since-ms, or nil."
  [db since-ms]
  (some #(let [e (d/entity db (:e %))] (when (>= (:seon.error/last-ms e 0) since-ms) (into {} e)))
        (filter #(= :platform (:v %)) (d/datoms db :aevt :seon.error/class))))

(defn await-quiet
  "The reconciler's quiescence report ({:t :stuck}) for main at or after t: nothing runs,
  nothing is left to judge, nothing is ready. Waits as long as work goes on, or until a
  root proc's defect is recorded on main from now on ({:ended :defect :problem p}): a step
  that threw dropped its outputs, so the flight it carried never ends and quiescence never
  comes. No clock."
  [sys t]
  (let [since (System/currentTimeMillis)
        db (await-db sys #(or (when-let [q (r/quiescent %)] (>= (:t q) t)) (defect % since)))]
    (if-let [q (and (r/quiescent db) (>= (:t (r/quiescent db)) t) (r/quiescent db))]
      q
      (let [p (defect db since)] (println :defect p) {:ended :defect :problem p}))))

(defn- main-t [sys] (:max-tx (db sys)))

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
  (doseq [tx (fx/program-txs (db sys) specs)] (println :program (select-keys @(c/transact-main! sys tx) [:ok :datoms]))))

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

(defn- fresh [sys forms]
  (let [ag (c/spawn! sys {})]
    (Thread/sleep 300)
    (let [r (mapv :printed @(c/submit! sys ag forms))] (c/kill! sys ag) r)))

(defn- quiet! "await-quiet from main's t now." [sys] (await-quiet sys (main-t sys)))

(defn- verdict!
  "Stop root and give the run's verdict: ok, and no root proc defect recorded on the run's
  main (its own store), read before root stops."
  [sys ok]
  (let [p (defect (db sys) 0) ok (and ok (nil? p))]
    (c/stop! sys)
    (when p (println :defect p))
    (println (if ok "E2E OK" "E2E FAILED")) ok))

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
    (await-db sys #(some (fn [aid] (str/starts-with? aid "calc/neg#")) (r/running (r/index %))))
    (Thread/sleep 300)
    (println :cancel-neg (select-keys (root/cancel! sys ["calc/neg"]) [:ok]))
    (let [merges ["calc/add-merge" "calc/twice-merge" "calc/square-merge" "calc/sum-sq-merge"]
          q (quiet! sys)
          _ (println :quiescent q)
          done? (let [idx (r/index (db sys))] (every? (fn [id] (r/done? idx id)) merges))
          blocked (r/blocked-by (r/index (db sys)) "calc/abs")
          reused (agents-reused (db sys))
          fresh-r (fresh sys "(app.calc/sum-sq 3 4)\n(count (my.db/q '[:find ?e :where [?e :seon.turn/id]]))")]
      (println :done-all? done? :abs-blocked-by blocked :agents-reused reused :fresh-agent fresh-r)
      (doseq [[id s] (summary (db sys))] (println :task id s))
      (println :numbers (numbers sys t0))
      (verdict! sys (and done? (= #{"calc/neg-merge"} blocked) (empty? reused) (= "25" (first fresh-r)) (= "0" (second fresh-r))
                    (= [{:task "calc/neg" :cause "cancelled" :blocks ["calc/abs" "calc/abs-merge" "calc/neg-merge"]}] (:stuck q)))))))

(defn resume
  "Phase 1 of a restart: calc on a durable store; root SIGKILLs itself mid-run."
  [path]
  (let [sys (c/start! {:datoms 300 :max-running 2 :path path})
        ds (fx/decls fx/calc 'calc nil)]
    (seed! sys fx/calc)
    (root/create! sys ds)
    (root/run! sys (mapv #(task/task-id 'calc (:seon.task/name %)) ds))
    (await-db sys #(let [idx (r/index %)] (and (r/done? idx "calc/add-merge") (seq (r/running idx)))))
    (println :running-at-kill (r/running (r/index (db sys))))
    (flush)
    (lib/c-kill (lib/c-getpid) 9)))

(defn resume2
  "Phase 2: a new root on the store reaps the lost attempts and finishes the graph."
  [path]
  (let [t0 (now)
        sys (c/start! {:datoms 300 :max-running 2 :path path})
        _ (println :recovered (get-in (c/state sys) [:store :recovered-branches]))
        _ (println :quiescent (quiet! sys))
        ok (let [idx (r/index (db sys))] (every? (fn [id] (r/done? idx id)) (map (fn [n] (str "calc/" n "-merge")) ["add" "twice" "square" "sum-sq" "neg" "abs"])))]
    (doseq [[id s] (summary (db sys))] (println :task id s))
    (println :numbers (numbers sys t0))
    (verdict! sys ok)))

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
          q (quiet! sys)
          ;; a cheap observer: count the :merged status datoms (no pull of every attempt)
          ok (and (empty? (:stuck q)) (= n (count (filter (fn [dt] (= :merged (:v dt))) (d/datoms (db sys) :aevt :seon.attempt/status)))))]
      (println :all-merged? ok :release-to-all-merged-ms (- (now) t1))
      (println :not-merged (into {} (remove (comp :done val)) (summary (db sys))))
      (let [d (db sys) t (System/nanoTime)] (r/reconcile d) (println :reconcile-ms (/ (- (System/nanoTime) t) 1e6)))
      (println :numbers (numbers sys t0))
      (verdict! sys ok))))

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
    (println :quiescent (quiet! sys))
    (let [d (db sys)
          fresh-r (fresh sys check)]
      (doseq [[id s] (summary d)] (println :task id s))
      (println :agents-reused (agents-reused d) :merges (merge-counts d) :fresh-agent fresh-r)
      (println :numbers (numbers sys t0))
      (verdict! sys (and (empty? (agents-reused d)) (= expect fresh-r))))))

(defn loop-run
  "The self-improvement loop, first turn: root boots on a clone of this repository (its
  committed HEAD, under the run's directory: never the live tree) with Seon's own source as
  main's program; a task redefines a platform function (seon.fixtures/generation, 1 -> 2)
  on its branch, its merge task lands it, and the reconciler writes main's file back and
  commits it on the clone's jolt/seon-merges. Passes when the commit holds exactly that
  form's change and the merged attempt records its sha. The next generation (building that
  commit) is the caller's: it prints the clone and the sha."
  [dir]
  (let [t0 (now)
        repo (.getParent (java.io.File. (System/getProperty "user.dir")))
        tree (str dir "/tree")
        git (fn [& args] (let [r (apply sh/sh "git" "-C" tree args)] (str/trim (str (:out r) (:err r)))))
        cloned (sh/sh "git" "clone" "-q" "--local" repo tree)
        head (git "rev-parse" "HEAD")
        sys (c/start! {:datoms 300 :max-running 2 :source {:root tree :dirs ["src"] :branch "jolt/seon-merges"}})
        src (some-> (d/q '[:find ?s . :where [?e :seon.root/source ?s]] (db sys)) read-string)
        {:keys [specs]} (get fx/scenarios :loop)
        ds (fx/decls specs 'loop nil)]
    (println :clone (:exit cloned) tree :head head)
    (println :source src)
    (seed! sys specs)
    (println :create (select-keys (root/create! sys (mapv #(assoc % :seon.task/config {:seon.config.agent/max-turns 2}) ds)) [:ok]))
    (root/run! sys (mapv #(task/task-id 'loop (:seon.task/name %)) ds))
    (println :quiescent (quiet! sys))
    (let [d (db sys)
          commits (d/q '[:find ?a ?c :where [?e :seon.attempt/commit ?c] [?e :seon.attempt/id ?a]] d)
          errors (d/q '[:find ?a ?m :where [?e :seon.attempt/commit-error ?m] [?e :seon.attempt/id ?a]] d)
          sha (second (first commits))
          files (when sha (git "diff" "--name-only" head sha))
          diff (when sha (git "diff" "-U0" head sha "--" "src/seon/fixtures.clj"))
          changed (when diff (filterv #(and (#{\+ \-} (first %)) (not (str/starts-with? % "+++")) (not (str/starts-with? % "---"))) (str/split-lines diff)))
          ok (and (zero? (:exit cloned)) src (nil? (:error src)) (< 1000 (:forms src))
                  (= 1 (count commits)) (empty? errors)
                  (= "src/seon/fixtures.clj" files)
                  (= ["-  [] 1)" "+  [] 2)"] changed)
                  (= head (git "rev-parse" "jolt/seon-merges~1"))
                  (= head (git "rev-parse" "HEAD")))]
      (doseq [[id s] (summary d)] (println :task id s))
      (println :commits commits :commit-errors errors :files files :changed changed)
      (println :numbers (numbers sys t0))
      (println "NEXT" tree sha)
      (c/stop! sys)
      (println (if (and ok (nil? (defect d 0))) "LOOP OK" "LOOP FAILED"))
      (and ok (nil? (defect d 0))))))

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
          q (quiet! sys)
          ok (empty? (:stuck q))
          d (db sys)
          not-done (into {} (remove (comp :done val)) (summary d))
          fresh-r (fresh sys "(mapv #(% 100) [app.sw/v0 app.sw/v1 app.sw/v2 app.sw/v3 app.sw/v4])")]
      (println :quiet-stuck (:stuck q) :release-to-quiet-ms (- (now) t1) :not-done not-done)
      (println :agents-reused (agents-reused d) :merges (merge-counts d) :fresh-agent fresh-r)
      (println :numbers (numbers sys t0))
      (verdict! sys (and ok (empty? not-done) (empty? (agents-reused d)) (= ["[100 101 102 103 104]"] fresh-r))))))

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
    (let [q (quiet! sys)
          _ (println :quiescent q)
          ok (let [idx (r/index (db sys))] (every? #(r/done? idx %) ids))]
      (doseq [[id s] (summary (db sys))] (println :task id s))
      (println :fresh-agent (fresh sys (if (= which "add") "(app.calc/add 1 2)" "[(app.calc/add 1 2) (app.calc/sum-sq 3 4) (app.calc/abs -5)]")))
      (println :numbers (numbers sys t0))
      (verdict! sys ok))))

(defn always-wrong
  "The stand-in for a model that never gets it right: every reply defines app.calc/add as
  subtraction (inv1's repro: add always wrong, with twice needing it)."
  {:malli/schema [:=> [:cat :seon.model/request] :string]}
  [_]
  "add is subtraction, surely.\n```clojure\n(in-ns 'app.calc)\n(defn add {:malli/schema [:=> [:cat :int :int] :int]} [a b] (- a b))\n```\n")

(defn stuck
  "A task that always fails ends observed stuck: add (always wrong) attaches refinement
  chains until its budget is spent, then the reconciler records the stuck fact (cause and
  what it blocks: twice, needing add) and reports quiescence; this run returns on that
  report, with no deadline. Verdict: one stuck root, the last chain's implement, blocking
  twice and its merge; nothing held; no agent reused."
  []
  (let [t0 (now)
        specs (vec (take 2 fx/calc))
        sys (c/start! {:datoms 300 :max-running 2 :provider {:kind :scripted :script 'seon.e2e/always-wrong}})
        ds (mapv #(assoc % :seon.task/config {:seon.config.agent/max-turns 2}) (fx/decls specs 'calc nil))
        ids (mapv #(task/task-id 'calc (:seon.task/name %)) ds)]
    (seed! sys specs)
    (println :create (select-keys (root/create! sys ds) [:ok]))
    (root/run! sys ids)
    (let [q (quiet! sys)
          t-quiet (now)
          d (db sys)
          ends (keep :seon.attempt/end-ms (vals (task/attempts d)))
          [row & more] (:stuck q)]
      (doseq [[id s] (summary d)] (println :task id s))
      (println :stuck (:stuck q))
      (println :quiet-after-last-end-ms (- t-quiet (apply max 0 ends)) :problems-stuck (count (get-in (root/problems sys) [:classes :stuck :rows])))
      (println :numbers (numbers sys t0))
      (verdict! sys (and (nil? more) (= "calc/add-r2-implement" (:task row))
                    (= ["calc/add-r2-merge" "calc/add-r2-test" "calc/twice" "calc/twice-merge"] (:blocks row))
                    (not-any? #(= :held (:seon.task/execution %)) (vals (task/tasks d)))
                    (empty? (agents-reused d)))))))

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
    "model" (model-run (first args))
    "stuck" (stuck)
    "loop" (loop-run (first args))))
