(ns seon.e2e
  "Tasks end to end with real agent processes (sessions R1 and M1's proto.agents.e2e). Each run
  is a row of data that one runner works (run-scenario): its specs, its boot, what a fresh
  agent must print on main afterwards and what else it asserts. The rows: the six-task graph
  with a cancel, a root restart in two phases, the merge scenarios (seon.fixtures/scenarios),
  a swarm over shared definitions, a real model's run, and a task that always fails held for
  root, then released by root; a new run is one row. bin/check runs them as modes of target/seon:
    calc | resume DIR | resume2 DIR | scenario conflict|semantic|hairy|newns|sibling [model] | swarm N M (verdict SWARM) | model add|calc | held
    | loop DIR (the self-improvement loop's first turn, on a clone of this repository under DIR)
  Each run ends on what the system observes (the reconciler's quiescence report, an event,
  or a fact on main), never on a deadline. Prints one EDN line per phase; the last line is
  the run's numbers and its verdict."
  (:require [seon.root :as c] [my.root :as root] [seon.task :as task] [seon.context :as context] [seon.reconcile :as r]
            [seon.fixtures :as fx] [datahike.api :as d] [seon.lib :as lib] [clojure.string :as str] [seon.config] [clojure.java.shell :as sh]
            [clojure.core.async :as a] [seon.server :as server]))

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
  "A root proc's defect (a :platform problem) whose record main wrote after t, or nil:
  facts, not clocks."
  [db t]
  (some #(when (some (fn [dt] (> (:tx dt) t)) (d/datoms db :eavt (:e %) :seon.error/last-ms)) (into {} (d/entity db (:e %))))
        (filter #(= :platform (:v %)) (d/datoms db :aevt :seon.error/class))))

(defn await-quiet
  "The reconciler's quiescence report ({:t :stuck}) for main at or after t: nothing runs,
  nothing is left to judge, nothing is ready. Waits as long as work goes on, or until a
  root proc's defect is recorded on main after t ({:ended :defect :problem p}): a step
  that threw dropped its outputs, so the flight it carried never ends and quiescence never
  comes. No clock."
  [sys t]
  (let [db (await-db sys #(or (when-let [q (r/quiescent %)] (>= (:t q) t)) (defect % t)))]
    (if-let [q (and (r/quiescent db) (>= (:t (r/quiescent db)) t) (r/quiescent db))]
      q
      (let [p (defect db t)] (println :defect p) {:ended :defect :problem p}))))

(defn- release!
  "Write specs' program on main, then their tasks in task namespace tns (each with
  max-turns when set, each merge task after its merge task in after); answers the task ids,
  for root/run!."
  [sys {:keys [specs tns max-turns after]}]
  (doseq [tx (fx/program-txs (db sys) specs)] (println :program (select-keys @(c/transact-main! sys tx) [:ok :datoms])))
  (let [ds (mapv #(cond-> % max-turns (assoc :seon.task/config {:seon.config.agent/max-turns max-turns})) (fx/decls specs tns nil))]
    (println :create (select-keys (root/create! sys ds) [:ok :datoms]))
    (when (seq after)
      (println :after (select-keys @(c/transact-main! sys (vec (for [[m n] after] [:db/add [:seon.task/id (str tns "/" m)] :seon.task/needs [:seon.task/id (str tns "/" n)]]))) [:ok])))
    (mapv #(task/task-id tns (:seon.task/name %)) ds)))

(defn agents-reused
  "Agent ids that more than one attempt used (must be empty: every attempt is a new agent)."
  [d] (vec (for [[ag n] (frequencies (map :seon.attempt/agent (vals (task/attempts d)))) :when (> n 1)] ag)))

(defn- merge-counts [d]
  (let [ts (task/tasks d)
        merge-as (filter #(= :merge (get-in ts [(:seon.attempt/task %) :seon.task/kind])) (vals (task/attempts d)))]
    {:merge-attempts (frequencies (map :seon.attempt/status merge-as))
     :behind-reattempts (count (filter #(= :behind (:seon.attempt/status %)) merge-as))
     :conflicts-measured (reduce + (map #(count (:seon.attempt/conflicts % [])) merge-as))
     :held-tasks (count (filter #(= :held (:seon.task/execution %)) (vals ts)))
     :model-calls-in-merges (reduce + (map #(:seon.attempt/model-calls % 0) merge-as))}))

(defn turns-per-merged-task
  "Turns spent per task that reached main on d: for each task with a :merged attempt (a
  merge task, 0 turns of its own), the turns of every attempt of the other tasks on its
  branch (the work it merged), averaged; nil when none merged."
  [d]
  (let [ts (task/tasks d)
        by-task (group-by :seon.attempt/task (vals (task/attempts d)))
        merged (filter #(some (comp #{:merged} :seon.attempt/status) (by-task %)) (keys ts))
        work (fn [id] (for [[wid w] ts :when (and (not= wid id) (= (:seon.task/branch w) (:seon.task/branch (ts id)))) a (by-task wid)] a))]
    (when (seq merged)
      (lib/r2 (/ (double (reduce + (for [id merged a (concat (work id) (by-task id))] (:seon.attempt/turns a 0)))) (count merged))))))

(defn- numbers [sys t0]
  (let [as (vals (task/attempts (db sys)))
        merged (filter #(= :merged (:seon.attempt/status %)) as)]
    {:wall-ms (- (now) t0) :attempts (count as) :merged (count merged)
     :turns-per-merged-task (turns-per-merged-task (db sys))
     :model-calls (reduce + (map #(:seon.attempt/model-calls % 0) as))
     :tokens (reduce + (map #(:seon.attempt/tokens % 0) as))
     :root-real-mb (lib/r2 (lib/real-mb)) :writer (:writer (c/state sys))
     :root-errors (vec (take 3 (:root-errors (c/state sys))))}))

(defn- fresh
  "What a fresh agent forked from main prints for each of forms, sent once its :ready event
  comes."
  [sys forms]
  (let [ev (c/events sys) ag (c/spawn! sys {})]
    (loop [] (let [e (a/<!! ev)] (when-not (and (= :ready (:type e)) (= ag (:agent e))) (recur))))
    (a/untap (:events-mult sys) ev)
    (let [r (mapv :printed @(c/submit! sys ag forms))] (c/kill! sys ag) r)))

(defn- quiet! "await-quiet from main's t now." [sys] (await-quiet sys (:max-tx (db sys))))

(defn- verdict!
  "Stop root and give the run's verdict: ok, and no root proc defect recorded on the run's
  main (its own store), read before root stops; printed as LABEL OK or LABEL FAILED."
  ([sys ok] (verdict! sys ok "E2E"))
  ([sys ok label]
   (let [p (defect (db sys) 0) ok (and ok (nil? p))]
     (c/stop! sys)
     (when p (println :defect p))
     (println label (if ok "OK" "FAILED")) ok)))

(defn loop-turn!
  "The loop's turn on root sys, whose Seon source is on: a task redefines a platform
  function (seon.fixtures/generation, 1 -> 2) on its branch, its merge task merges it, the
  reconciler commits main's file on the merges branch, and the merge loads in place: root
  and every agent forked after it run the new definition, with no build and no restart
  (BUILD plan P4). Waits for quiescence. Answers {:commit sha (the merged attempt's)
  :commits [[attempt sha]] :commit-errors [[attempt message]] :turn-ms (release to quiet) :spans [[attempt start end] ms
  from release]
  :root (seon.fixtures/generation in root) :agent (in an agent forked after the merge)
  :quiescent q}. k: the scenario (fixtures/scenarios), :loop by default; :loop-2 is the
  second turn."
  ([sys] (loop-turn! sys :loop))
  ([sys k]
  (let [ids (release! sys (assoc (get fx/scenarios k) :tns (symbol (name k)) :max-turns 2))
        t0 (now)
        _ (root/run! sys ids)
        q (quiet! sys)
        turn-ms (- (now) t0)
        d (db sys)
        commits (vec (d/q '[:find ?a ?c :where [?e :seon.attempt/commit ?c] [?e :seon.attempt/id ?a]] d))]
    {:commit (second (first commits)) :commits commits
     :commit-errors (vec (d/q '[:find ?a ?m :where [?e :seon.attempt/commit-error ?m] [?e :seon.attempt/id ?a]] d))
     :turn-ms turn-ms
     :spans (vec (sort (for [[a s e] (d/q '[:find ?a ?s ?e :where [?x :seon.attempt/id ?a] [?x :seon.attempt/start-ms ?s] [?x :seon.attempt/end-ms ?e]] d)]
                         [a (- s t0) (- e t0)])))
     :root (seon.fixtures/generation) :agent (first (fresh sys "(seon.fixtures/generation)"))
     :quiescent q})))

(defn loop-run
  "The self-improvement loop, first turn: root boots on a clone of this repository (its
  committed HEAD, under the run's directory: never the live tree) with Seon's own source as
  main's program, and runs loop-turn!. Passes when the commit holds exactly that form's
  change, the merged attempt records its sha, and the merged definition is live in root and
  in an agent forked after the merge, with nothing built. The turn's time is printed with
  each attempt's span: under a second needs seon.merge/program-cost-follows-what-changed."
  [dir]
  (let [t0 (now)
        repo (.getParent (java.io.File. (System/getProperty "user.dir")))
        tree (str dir "/tree")
        git (fn [& args] (let [r (apply sh/sh "git" "-C" tree args)] (str/trim (str (:out r) (:err r)))))
        cloned (sh/sh "git" "clone" "-q" "--local" repo tree)
        head (git "rev-parse" "HEAD")
        sys (c/start! {:datoms 300 :max-running 2 :source {:root tree :dirs ["src"] :branch "jolt/seon-merges"}})
        src (d/q '[:find ?s . :where [?e :seon.root/source ?s]] (db sys))]
    (println :clone (:exit cloned) tree :head head)
    (println :source src)
    (let [{:keys [commits commit-errors turn-ms spans root agent] sha :commit} (loop-turn! sys)
          d (db sys)
          files (when sha (git "diff" "--name-only" head sha))
          diff (when sha (git "diff" "-U0" head sha "--" "src/seon/fixtures.clj"))
          changed (when diff (filterv #(and (#{\+ \-} (first %)) (not (str/starts-with? % "+++")) (not (str/starts-with? % "---"))) (str/split-lines diff)))
          ok (and (zero? (:exit cloned)) src (nil? (:error src)) (< 1000 (:forms src))
                  (= 1 (count commits)) (empty? commit-errors)
                  (= "src/seon/fixtures.clj" files)
                  (= ["-  [] 1)" "+  [] 2)"] changed)
                  (= head (git "rev-parse" "jolt/seon-merges~1"))
                  (= head (git "rev-parse" "HEAD"))
                  (= 2 root) (= "2" agent) (nil? (r/generation d)))]
      (doseq [[id s] (server/task-summaries d)] (println :task id s))
      (println :commits commits :commit-errors commit-errors :files files :changed changed)
      (println :live {:root root :agent agent :turn-ms turn-ms :spans spans})
      (println :numbers (numbers sys t0))
      (verdict! sys ok "LOOP"))))

(defn- model-provider
  "The config's OpenAI-compatible provider (:seon.config.ai/*), whatever the profile's kind."
  []
  (seon.config/provider (assoc seon.config/config :seon.config.ai/provider :openai-compat)))

(defn always-wrong
  "The scripted model that never gets it right: every reply defines app.calc/add as
  subtraction (inv1's repro: add always wrong, with twice needing it)."
  {:malli/schema [:=> [:cat :seon.model/request] :string]}
  [_]
  "add is subtraction, surely.\n```clojure\n(in-ns 'app.calc)\n(defn add {:malli/schema [:=> [:cat :int :int] :int]} [a b] (- a b))\n```\n")

(defn expectation
  "Scenario k's {:check :expect} (seon.fixtures/scenarios); model? is a real model's run."
  [k model?]
  (let [e (get fx/scenarios k)]
    (select-keys (if model? (merge e (:model e)) e) [:check :expect])))

(defn- all-done
  "Every task done on d; what is not, by id."
  [d] (let [not-done (into {} (remove (comp :done val)) (server/task-summaries d))] {:ok (empty? not-done) :not-done not-done}))

(defn- run-scenario
  "Work one row: boot root on (merge {:datoms 300} :boot), release :specs (release!), run
  (:during sys ev) on a tap of root's events taken before the release, and wait for
  quiescence; a row without :specs only boots and waits (a restart's second phase). Then a
  fresh agent runs :check on what main runs, which must print :expect, and (:ok run) judges
  the rest from {:sys :q (the quiescence report) :d (main)}, answering {:ok bool & what it
  measured}, printed as :checks. No agent may serve two attempts of one boot (a row without
  :specs spans two boots, whose agent ids both start at a1). Answers the verdict, printed
  under the row's :label (E2E when it names none)."
  [{:keys [boot specs during check expect ok label] :as row}]
  (let [t0 (now)
        sys (c/start! (merge {:datoms 300} boot))
        ev (when during (c/events sys))]
    (when specs (root/run! sys (release! sys row)))
    (when during (during sys ev) (a/untap (:events-mult sys) ev))
    (let [q (quiet! sys)
          _ (println :quiescent q)
          d (db sys)
          fresh-r (when check (fresh sys check))
          checks (if ok (ok {:sys sys :q q :d d}) {:ok true})
          reused (agents-reused d)]
      (doseq [[id s] (server/task-summaries d)] (println :task id s))
      (println :agents-reused reused :merges (merge-counts d) :fresh-agent fresh-r)
      (println :checks (dissoc checks :ok))
      (println :numbers (numbers sys t0))
      (verdict! sys (and (:ok checks) (or (nil? specs) (empty? reused)) (or (nil? expect) (= expect fresh-r))) (or label "E2E")))))

(defn- restart
  "A restart on main nm of the one store: phase 1 works the calc graph and SIGKILLs root
  once add has merged with attempts still running; phase 2 (a new root on the same store
  and main) reaps the lost attempts and finishes the graph."
  [phase2? nm]
  (let [boot {:max-running 2 :main (keyword nm)}]
    (if phase2?
      {:boot boot :ok (fn [{:keys [sys d]}] (assoc (all-done d) :recovered (get-in (c/state sys) [:store :recovered-branches])))}
      {:boot boot :specs fx/calc :tns 'calc
       :during (fn [sys _]
                 (await-db sys #(let [idx (r/index %)] (and (r/done? idx "calc/add-merge") (seq (r/running idx)))))
                 (println :running-at-kill (r/running (r/index (db sys))))
                 (flush)
                 (lib/c-kill (lib/c-getpid) 9))})))

(defn- calc
  "The six-task graph: release all, cancel neg once its agent is ready (neg sleeps 8 s on
  its first turn), the rest merges. square's branch records what its model read each turn
  and where the time went: its second turn read its first answer's failure as the
  assertion and what it got (square was (twice x), and twice is still its stub on square's
  branch: 0); each turn's text is made again from its basis (seon.context/render)."
  [path]
  {:boot (cond-> {:max-running 2} path (assoc :path path)) :specs fx/calc :tns 'calc
   :during (fn [sys ev]
             (loop [ready #{}]
               (let [e (a/<!! ev) ready (cond-> ready (= :ready (:type e)) (conj (:agent e)))]
                 (when-not (some #(and (ready (:seon.attempt/agent %)) (= "calc/neg" (:seon.attempt/task %))) (vals (task/attempts (db sys))))
                   (recur ready))))
             (println :cancel-neg (select-keys (root/cancel! sys ["calc/neg"]) [:ok])))
   :check "(app.calc/sum-sq 3 4)\n(count (my.db/q '[:find ?e :where [?e :seon.turn/id]]))" :expect ["25" "0"]
   :ok (fn [{:keys [sys q d]}]
         (let [idx (r/index d)
               done? (every? #(r/done? idx %) ["calc/add-merge" "calc/twice-merge" "calc/square-merge" "calc/sum-sq-merge"])
               blocked (r/blocked-by idx "calc/abs")
               sq-db (:db @(c/branch-db sys (task/default-branch "calc/square")))
               sq-turns (sort-by first (map (fn [[n e s]] [n (context/turn-text sq-db e) s])
                                            (d/q '[:find ?n ?e ?s :where [?e :seon.turn/n ?n] [?e :seon.turn/origin :model]
                                                   [?e :seon.turn/basis _] [?e :seon.turn/split ?s]] sq-db)))
               read-failure? (and (= 2 (count sq-turns))
                                  (str/includes? (second (second sq-turns)) ":assert \"(= 9 (app.calc/square 3))\"")
                                  (str/includes? (second (second sq-turns)) ":got \"(= 9 0)\"")
                                  (every? #(every? (set (keys (nth % 2))) [:tests-ms :context-ms :model-ms :eval-ms :record-ms]) sq-turns))]
           (cond-> {:ok (and done? (= #{"calc/neg-merge"} blocked) read-failure?
                             (= [{:task "calc/neg" :cause "cancelled" :blocks ["calc/abs" "calc/abs-merge" "calc/neg-merge"]}] (:stuck q)))
                    :done-all? done? :abs-blocked-by blocked :square-turns (count sq-turns) :square-read-its-failure? read-failure?
                    :square-splits (mapv #(nth % 2) sq-turns)}
             (not read-failure?) (assoc :square-second-context (second (second sq-turns))))))})

(defn- held-as
  "Did held (task name -> a word its reason holds) end held for root, each with its failure,
  in task namespace tns on d, and every other task done?"
  [d tns held]
  (let [ids (into {} (for [[nm word] held] [(str tns "/" nm) word]))
        ts (task/tasks d)
        rows (into {} (for [id (keys ids)] [id (select-keys (get ts id) [:seon.task/execution :seon.task/failure])]))]
    {:ok (and (every? (fn [[id word]] (let [{x :seon.task/execution f :seon.task/failure} (rows id)]
                                        (and (= :held x) (str/includes? (str (:reason f)) word))))
                      ids)
              (every? :done (vals (apply dissoc (server/task-summaries d) (keys ids)))))
     :held rows}))

(defn- scenario
  "A merge scenario of seon.fixtures/scenarios: its tasks with their merge tasks, each merge
  after its :after, all released; then what main runs, and the tasks it expects held for
  root (:held). model: a real model's run, with its own verdict."
  [k model]
  (let [k (keyword k) model? (= "model" model) {:keys [held]} (get fx/scenarios k)]
    (cond-> (merge (select-keys (get fx/scenarios k) [:specs :after])
                   {:boot (cond-> {:max-running 4} model? (assoc :provider (model-provider))) :tns (symbol (name k)) :max-turns 4}
                   (expectation k model?))
      held (assoc :ok (fn [{:keys [d]}] (held-as d (name k) held))))))

(defn- swarm
  "n tasks over 5 shared definitions, max-running m (more than 5, so tasks on one var run
  at once and their merges conflict): conflicts arose, each was resolved by a merge agent,
  every task is done, nothing is stuck, and a fresh agent on
  main gets every proof's value (s_k-test asserts (v_j 100) = 100+j). Verdict SWARM."
  [n m]
  {:boot {:max-running (parse-long (or m "8"))} :specs (fx/swarm-specs (parse-long (or n "20"))) :tns 'sw :label "SWARM"
   :check "(mapv #(% 100) [app.sw/v0 app.sw/v1 app.sw/v2 app.sw/v3 app.sw/v4])" :expect ["[100 101 102 103 104]"]
   :ok (fn [{:keys [q d]}]
         (let [c (:conflicts-measured (merge-counts d))]
           (-> (all-done d) (assoc :conflicts c) (update :ok #(and % (empty? (:stuck q)) (pos? c))))))})

(defn- model-run
  "Tasks of the calc graph worked by the config's OpenAI-compatible endpoint
  (:seon.config.ai/*, DeepSeek by default): which = add (one task) or calc."
  [which]
  (let [add? (= "add" which) provider (model-provider)]
    (println :provider (dissoc provider :api-key-env))
    {:boot {:max-running 6 :provider provider} :specs (if add? [(first fx/calc)] fx/calc) :tns 'calc :max-turns 4
     :check (if add? "(app.calc/add 1 2)" "[(app.calc/add 1 2) (app.calc/sum-sq 3 4) (app.calc/abs -5)]")
     :ok (fn [{:keys [d]}] (all-done d))}))

(defn- held
  "A task that always fails is held for root, and root's release runs it again on its branch,
  from the evidence: add (always wrong) is refused once and held (its failure, its
  evidence, the attempt closed); nothing else starts, no task is created, and the reconciler
  reports quiescence naming add as the root cause of twice and the merges; the problems read
  lists it. Root releases add (my.root/run!): one new attempt, on add's branch, whose agent
  took the task from main with add#1's evidence (seon.task/onto-tx), refused and held again.
  Each wait ends on the reconciler's quiescence report, with no deadline."
  []
  {:boot {:max-running 2 :provider {:kind :scripted :script 'seon.e2e/always-wrong}} :specs (vec (take 2 fx/calc)) :tns 'calc :max-turns 2
   :ok (fn [{:keys [sys q d]}]
         (let [blocks ["calc/add-merge" "calc/twice" "calc/twice-merge"]
               [row & more] (:stuck q)
               ts (task/tasks d)
               add (get ts "calc/add")
               first? (and (nil? more) (= "calc/add" (:task row)) (= "calc/add#1" (:attempt row)) (= blocks (:blocks row))
                           (= :held (:seon.task/execution add)) (= "calc/add#1" (:attempt (:seon.task/failure add)))
                           (= "calc/add#1" (:from (:seon.task/evidence add)))
                           (= #{"calc/add" "calc/add-merge" "calc/twice" "calc/twice-merge"} (set (keys ts)))
                           (= [[:closed]] (mapv (comp vector :seon.attempt/status) (vals (task/attempts d)))))
               problems (get-in (root/problems sys) [:classes :failed :rows])
               ;; root decides: run it again
               _ (println :release (select-keys (root/run! sys ["calc/add"]) [:ok]))
               q2 (quiet! sys)
               d2 (db sys)
               a2 (task/attempt d2 "calc/add#2")
               branch (task/default-branch "calc/add")
               on-branch (task/task (:db @(c/branch-db sys branch)) "calc/add")
               second? (and (= branch (get-in a2 [:seon.attempt/env :branch]))
                            ;; the agent took its task from main: add#1's evidence
                            (= "calc/add#1" (:from (:seon.task/evidence on-branch)))
                            (= "calc/add#2" (:attempt (:seon.task/failure (task/task d2 "calc/add"))))
                            (= :held (:seon.task/execution (task/task d2 "calc/add")))
                            (= 2 (count (task/attempts d2))) (= 4 (count (task/tasks d2)))
                            (= ["calc/add"] (mapv :task (:stuck q2))))]
           {:ok (and first? second? (= 1 (count (filter :open? problems))))
            :first-hold first? :release-on-the-same-branch second?
            :stuck (:stuck q) :stuck-after-release (:stuck q2)
            :quiet-after-last-end-ms (- (now) (apply max 0 (keep :seon.attempt/end-ms (vals (task/attempts d2)))))
            :problems-failed (count problems)}))})

(defn run
  "One e2e mode; answers whether it passed. No mode, or an unknown one: its usage, then
  E2E FAILED (false)."
  [mode [x y]]
  (case mode
    "loop" (loop-run x)
    ("calc" "resume" "resume2" "scenario" "swarm" "model" "held")
    (run-scenario (case mode
                    "calc" (calc x)
                    "resume" (restart false x)
                    "resume2" (restart true x)
                    "scenario" (scenario x y)
                    "swarm" (swarm x y)
                    "model" (model-run x)
                    "held" (held)))
    (do (println (str "e2e: no mode " mode "; usage: seon e2e loop|calc|resume|resume2|scenario|swarm|model|held [ARGS]"))
        (println "E2E FAILED")
        false)))
