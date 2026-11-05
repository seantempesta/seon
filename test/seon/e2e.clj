(ns seon.e2e
  "Tasks end to end with real agent processes (sessions R1 and M1's proto.agents.e2e). Each run
  is a row of data that one runner works (run-scenario): its specs, its boot, what a fresh
  agent must print on main afterwards and what else it asserts. The rows: the six-task graph
  with a cancel, a root restart in two phases, the merge scenarios (seon.fixtures/scenarios),
  a swarm over shared definitions, a real model's run, and a task that always fails parked
  at its cap, then woken by the owner's message; a new run is one row. bin/check runs them as modes of target/seon:
    calc | resume DIR | resume2 DIR | scenario conflict|semantic|hairy|newns|sibling [model] | swarm N M (verdict SWARM) | model add|calc | held
    | loop DIR (the self-improvement loop's first turn, on a clone of this repository under DIR)
  Each run ends on what the system observes (the reconciler's quiescence report, an event,
  or a fact on main), never on a deadline. Prints one EDN line per phase; the last line is
  the run's numbers and its verdict."
  (:require [seon.await :as await] [seon.root :as c] [my.root :as root] [seon.task :as task] [seon.turn :as turn] [seon.context :as context] [seon.reconcile :as r]
            [seon.fixtures :as fx] [datahike.api :as d] [seon.lib :as lib] [clojure.string :as str] [seon.config] [clojure.java.shell :as sh]
            [clojure.core.async :as a] [seon.server :as server] [seon.contract :as contract] [seon.test-store :as ts]))

(defn- now [] (System/currentTimeMillis))
(defn- db [sys] @(:conn sys))

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
  "Agent keys that serve tasks of more than one branch (must be empty): a session's tasks (a
  task and the tasks on its branch it needs, a work task and its merge: seon.task/session-key)
  continue its one agent, and no agent serves two sessions. The branch stands for the session (a
  session is one branch): two sessions on one branch with no :needs between them sharing an agent
  pass unseen. session-key cannot judge it, since it answers the agent this check asks about."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :string]]}
  [d] (vec (for [[k ts] (group-by :seon.task/agent (vals (task/tasks d)))
                 :when (and k (< 1 (count (distinct (map :seon.task/branch ts)))))]
             k)))

(defn- branch-db-of
  "A function from an agent key to the db of its branch on sys: an agent's turns and model calls are written there (main holds its
  start and its end, as the task's outcome), d being main."
  {:malli/schema [:=> [:cat :seon.root/sys :seon.host/db] fn?]}
  [sys d]
  (fn [k] (:db @(c/branch-db sys (:seon.agent/branch (d/entity d [:seon.agent/key k]))))))

(defn- agent-usages
  "What the model calls of the agents of tasks ts spent: [{:model-calls n :tokens t}], one per distinct agent, read on the db that
  db-of answers for its key."
  {:malli/schema [:=> [:cat fn? [:sequential :map]] [:vector :map]]}
  [db-of ts]
  (vec (for [k (distinct (keep :seon.task/agent ts))] (turn/agent-usage (db-of k) k))))

(defn- merge-counts
  {:malli/schema [:=> [:cat :seon.host/db fn?] :map]}
  [d db-of]
  (let [ts (vals (task/tasks d))
        merge-ts (filter #(= :merge (:seon.task/kind %)) ts)
        behinds (fn [t] (or (d/q '[:find (count ?tx) . :in $ ?id :where [?t :seon.task/id ?id] [?t :seon.task/outcome :behind ?tx true]] (d/history d) (:seon.task/id t)) 0))]
    {:merge-outcomes (frequencies (map :seon.task/outcome merge-ts))
     :behind-reruns (reduce + (map behinds merge-ts))
     :conflicts-measured (reduce + (map #(count (:seon.task/conflicts % [])) ts))
     :held-tasks (count (filter #(= :held (:seon.task/execution %)) ts))
     :model-calls-in-merges (reduce + (map :model-calls (agent-usages db-of merge-ts)))}))

(defn turns-per-merged-task
  "Turns spent per task that reached main on d: for each task whose outcome is :merged (a merge
  task, 0 turns of its own), the turns (:seon.agent/turn, agent-wide) of every distinct agent of
  the other tasks on its branch (the work it merged) and of its own, averaged; nil when none merged. The turns are read on
  the db that db-of answers for an agent's key (its branch's, where its turns are written; d, main, by default)."
  {:malli/schema [:function [:=> [:cat :seon.host/db] [:maybe :double]] [:=> [:cat :seon.host/db fn?] [:maybe :double]]]}
  ([d] (turns-per-merged-task d (constantly d)))
  ([d db-of]
  (let [ts (task/tasks d)
        merged (filter #(= :merged (:seon.task/outcome (ts %))) (keys ts))
        turns (fn [k] (:seon.agent/turn (d/entity (db-of k) [:seon.agent/key k]) 0))
        agents (fn [id] (distinct (keep :seon.task/agent (cons (ts id) (for [[wid w] ts :when (and (not= wid id) (= (:seon.task/branch w) (:seon.task/branch (ts id))))] w)))))]
    (when (seq merged)
      (lib/r2 (/ (double (reduce + (for [id merged k (agents id)] (turns k)))) (count merged)))))))

(defn- numbers [sys t0]
  (let [d (db sys)
        db-of (branch-db-of sys d)
        ts (vals (task/tasks d))
        us (agent-usages db-of ts)]
    {:wall-ms (- (now) t0) :agents (count us) :merged (count (filter #(= :merged (:seon.task/outcome %)) ts))
     :turns-per-merged-task (turns-per-merged-task d db-of)
     :model-calls (reduce + (map :model-calls us))
     :tokens (reduce + (map #(:tokens % 0) us))
     :root-real-mb (lib/r2 (lib/real-mb)) :writer (:writer (c/state sys))
     :root-errors (vec (take 3 (:root-errors (c/state sys))))}))

(defn- fresh
  "What a fresh agent forked from main shows for forms, sent once its :ready event comes: one
  request, whose response is its last form's value, as [printed] (seon.test-store/value-of
  printed as Clojure prints it), the response itself when it shows no value that reads back
  (an error, a var, nil), or the request's error."
  [sys forms]
  (let [ev (c/events sys) ag (c/spawn! sys {})]
    (loop [] (let [e (a/<!! ev)] (when-not (and (= :ready (:type e)) (= ag (:agent e))) (recur))))
    (a/untap (:events-mult sys) ev)
    (let [r @(c/submit! sys ag forms)] (c/kill! sys ag)
      (if-some [v (when-not (or (contract/error? r) (:ex r)) (ts/value-of r))] [(pr-str v)] r))))

(defn- quiet! "await/await-quiet from main's t now." [sys] (await/await-quiet sys (:max-tx (db sys))))

(defn- verdict!
  "Stop root and give the run's verdict: ok, and no root proc defect recorded on the run's
  main (its own store), read before root stops; printed as LABEL OK or LABEL FAILED."
  ([sys ok] (verdict! sys ok "E2E"))
  ([sys ok label]
   (let [p (c/defect (db sys) 0) ok (and ok (nil? p))]
     (c/stop! sys)
     (when p (println :defect p))
     (println label (if ok "OK" "FAILED")) ok)))

(defn loop-turn!
  "The loop's turn on root sys, whose Seon source is on: a task redefines a platform
  function (seon.generation/generation, 1 -> 2) on its branch, its merge task merges it, the
  reconciler commits main's file on the merges branch, and the merge loads in place: root
  and every agent forked after it run the new definition, with no build and no restart
  (BUILD plan P4). Waits for quiescence. Answers {:commit sha (the merged task's)
  :commits [[task sha]] :commit-errors [[task message]] :turn-ms (release to quiet) :spans [[agent start end] ms
  from release]
  :root (seon.generation/generation in root) :agent (in an agent forked after the merge)
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
        commits (vec (d/q '[:find ?a ?c :where [?e :seon.task/commit ?c] [?e :seon.task/id ?a]] d))]
    {:commit (second (first commits)) :commits commits
     :commit-errors (vec (d/q '[:find ?a ?m :where [?e :seon.task/commit-error ?m] [?e :seon.task/id ?a]] d))
     :turn-ms turn-ms
     :spans (vec (sort (for [[a s e] (d/q '[:find ?a ?s ?e :where [?x :seon.agent/key ?a] [?x :seon.agent/started-ms ?s] [?x :seon.agent/ended-ms ?e]] d)]
                         [a (- s t0) (- e t0)])))
     :root (seon.generation/generation) :agent (first (fresh sys "(seon.generation/generation)"))
     :quiescent q})))

(defn loop-run
  "The self-improvement loop, first turn: root boots on a clone of this repository (its
  committed HEAD, under the run's directory: never the live tree) with Seon's own source as
  main's program, and runs loop-turn!. Passes when the commit holds exactly that form's
  change, the merged run records its sha, and the merged definition is live in root and
  in an agent forked after the merge, with nothing built. The turn's time is printed with
  each run's span: under a second needs seon.merge/program-cost-follows-what-changed."
  [dir]
  (let [t0 (now)
        repo (seon.config/checkout)
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
          diff (when sha (git "diff" "-U0" head sha "--" "src/seon/generation.clj"))
          changed (when diff (filterv #(and (#{\+ \-} (first %)) (not (str/starts-with? % "+++")) (not (str/starts-with? % "---"))) (str/split-lines diff)))
          ok (and (zero? (:exit cloned)) src (nil? (:error src)) (< 1000 (:forms src))
                  (= 1 (count commits)) (empty? commit-errors)
                  (= "src/seon/generation.clj" files)
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
  {:malli/schema [:=> [:cat :seon.model/request] :seon.model/script-reply]}
  [_]
  (let [prose "add is subtraction, surely."]
    {:text prose
     :calls [{:prose prose :code "(in-ns 'app.calc)"}
             {:prose prose :code "(defn add {:malli/schema [:=> [:cat :int :int] :int]} [a b] (- a b))"}]}))

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
  measured}, printed as :checks. No agent may serve two sessions of one boot (agents-reused;
  a row without :specs spans two boots, whose agent ids both start at a1). Answers the verdict, printed
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
      (println :agents-reused reused :merges (merge-counts d (branch-db-of sys d)) :fresh-agent fresh-r)
      (println :checks (dissoc checks :ok))
      (println :numbers (numbers sys t0))
      (verdict! sys (and (:ok checks) (or (nil? specs) (empty? reused)) (or (nil? expect) (= expect fresh-r))) (or label "E2E")))))

(defn- restart
  "A restart on main nm of the one store: phase 1 works the calc graph and SIGKILLs root
  once add has merged with runs still running; phase 2 (a new root on the same store
  and main) reaps the lost runs and finishes the graph."
  [phase2? nm]
  (let [boot {:max-running 2 :main (keyword nm)}]
    (if phase2?
      {:boot boot :ok (fn [{:keys [sys d]}] (assoc (all-done d) :recovered (get-in (c/state sys) [:store :recovered-branches])))}
      {:boot boot :specs fx/calc :tns 'calc
       :during (fn [sys _]
                 (c/await-db sys #(let [idx (r/index %)] (and (r/done? idx "calc/add-merge") (seq (:live idx)))))
                 (println :running-at-kill (:live (r/index (db sys))))
                 (flush)
                 (lib/c-kill (lib/c-getpid) 9))})))

(defn- calc
  "The six-task graph: release all, cancel neg once its agent is ready (neg's first turn derefs a
  promise nothing delivers: seon.fixtures/calc-cancel), the rest merges. square's branch records what its model read each turn
  and where the time went: its second turn read its first answer's failure as the
  assertion and what it got (square was (twice x), and twice is still its stub on square's
  branch: 0); each turn's text is every message it sent, read back from its refs (seon.context/parts-of)."
  [path]
  {:boot (cond-> {:max-running 2 :provider {:kind :scripted :script 'seon.fixtures/calc-cancel}} path (assoc :path path)) :specs fx/calc :tns 'calc
   :during (fn [sys ev]
             (loop [ready #{}]
               (let [e (a/<!! ev) ready (cond-> ready (= :ready (:type e)) (conj (:agent e)))]
                 (when-not (some #(and (ready (:agent %)) (= "calc/neg" (:task %))) (task/running (db sys)))
                   (recur ready))))
             (println :cancel-neg (select-keys (root/cancel! sys ["calc/neg"]) [:ok])))
   :check "[(app.calc/sum-sq 3 4) (count (my.db/q '[:find ?e :where [?e :seon.model-call/id]]))]" :expect ["[25 0]"]
   :ok (fn [{:keys [sys q d]}]
         (let [idx (r/index d)
               done? (every? #(r/done? idx %) ["calc/add-merge" "calc/twice-merge" "calc/square-merge" "calc/sum-sq-merge"])
               blocked (r/blocked-by idx "calc/abs")
               sq-db (:db @(c/branch-db sys (task/default-branch "calc/square")))
               sq-turns (sort-by first (map (fn [[n e s]] [n (str/join "\n\n" (keep :content (into [] (mapcat second) (context/parts-of sq-db (:seon.model-call/sent (d/pull sq-db [:seon.model-call/sent] e)))))) s])
                                            (d/q '[:find ?n ?e ?s :where [?e :seon.model-call/n ?n] [?e :seon.model-call/origin :model]
                                                   [?e :seon.model-call/basis _] [?e :seon.model-call/split ?s]] sq-db)))
               read-failure? (and (= 2 (count sq-turns))
                                  (str/includes? (second (second sq-turns)) ":assert (= 9 (app.calc/square 3))")
                                  (str/includes? (second (second sq-turns)) ":got (= 9 0)")
                                  (every? #(every? (set (keys (nth % 2))) [:tests-ms :context-ms :model-ms :eval-ms :record-ms]) sq-turns))]
           (cond-> {:ok (and done? (= #{"calc/neg-merge"} blocked) read-failure?
                             (= [{:task "calc/neg" :cause "cancelled" :blocks ["calc/abs" "calc/abs-merge" "calc/neg-merge"]}] (:root-causes q)))
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
  at once and their merges conflict): conflicts arose (each met by a work agent merging main as
  it moved, or by a merge agent, whichever branch main reached first) and still
  every task is done, nothing is held for root, and a fresh agent on
  main gets every proof's value (s_k-test asserts (v_j 100) = 100+j). Verdict SWARM."
  [n m]
  {:boot {:max-running (parse-long (or m "8"))} :specs (fx/swarm-specs (parse-long (or n "20"))) :tns 'sw :label "SWARM"
   :check "(mapv #(% 100) [app.sw/v0 app.sw/v1 app.sw/v2 app.sw/v3 app.sw/v4])" :expect ["[100 101 102 103 104]"]
   :ok (fn [{:keys [sys q d]}]
         (let [c (:conflicts-measured (merge-counts d (branch-db-of sys d)))]
           (-> (all-done d) (assoc :conflicts c) (update :ok #(and % (empty? (:root-causes q)) (pos? c))))))})

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
  "A task that always fails parks its session at its cap, and only a raised budget and a release
  continue it (design agent-loop section 2: always park; docs/turn.md#the-cap): add (always wrong) runs out of turns,
  its task parked with outcome :cap, its process kept; nothing else starts, no task is created,
  and the reconciler reports quiescence naming add as the root cause of twice and the merges
  (parked, waiting for a message). A message does not wake a task at its cap. The owner raises add's agent's
  :seon.agent/max-turns, holds the task (my.root/hold!) and releases it (my.root/run!): the same agent (key and branch) works on, its turn
  count continuing from the cap, and parks again at the raised cap and is named again. Each wait ends on the
  reconciler's quiescence report, with no deadline."
  []
  {:boot {:max-running 2 :provider {:kind :scripted :script 'seon.e2e/always-wrong}} :specs (vec (take 2 fx/calc)) :tns 'calc :max-turns 2
   :ok (fn [{:keys [sys q d]}]
         (let [blocks ["calc/add-merge" "calc/twice" "calc/twice-merge"]
               cause "parked: cap, waiting for a message"
               [row & more] (:root-causes q)
               ts (task/tasks d)
               k (:seon.task/agent (get ts "calc/add"))
               turn-of (fn [d] (:seon.agent/turn (d/entity d [:seon.agent/key k]) 0))
               first? (and (nil? more) (= {:task "calc/add" :cause cause :agent k :blocks blocks} row)
                           (= :cap (:seon.task/outcome (get ts "calc/add")))
                           (= :running (:seon.task/execution (get ts "calc/add")))
                           (= #{"calc/add" "calc/add-merge" "calc/twice" "calc/twice-merge"} (set (keys ts)))
                           (= 1 (count (task/agents d))))
               turns1 (turn-of (:db @(c/branch-db sys (task/default-branch "calc/add"))))
               ;; the owner raises the budget, holds the task and releases it: the same agent works on
               _ (println :raise (select-keys @(c/transact-main! sys [[:db/add [:seon.agent/key k] :seon.agent/max-turns 4]]) [:ok]))
               _ (println :hold (select-keys (root/hold! sys ["calc/add"] "its budget is raised") [:ok]))
               _ (println :held (:root-causes (quiet! sys)))
               ended-of (fn [d] (:seon.agent/ended-ms (d/entity d [:seon.agent/key k])))
               ended1 (ended-of (db sys))
               _ (println :release (select-keys (root/run! sys ["calc/add"]) [:ok]))
               ;; the release is news to the reconciler a moment after its write: the wait is on the agent's next end
               _ (c/await-db sys "add parked at its raised cap, a second time"
                             #(and (= :cap (:seon.task/outcome (d/entity % [:seon.task/id "calc/add"]))) (< (or ended1 0) (or (ended-of %) 0))))
               q2 (quiet! sys)
               d2 (db sys)
               ts2 (task/tasks d2)
               turns2 (turn-of (:db @(c/branch-db sys (task/default-branch "calc/add"))))
               second? (and (= :cap (:seon.task/outcome (get ts2 "calc/add")))
                            (= k (:seon.task/agent (get ts2 "calc/add")))
                            (= 1 (count (task/agents d2))) (= 4 (count ts2))
                            (= 2 turns1) (= 4 turns2)
                            (= [{:task "calc/add" :cause cause :agent k :blocks blocks}] (:root-causes q2)))]
           {:ok (and first? second?)
            :first-park first? :a-raised-budget-and-a-release-continue-the-same-agent second?
            :turns [turns1 turns2]
            :root-causes (:root-causes q) :root-causes-after-release (:root-causes q2)
            :tasks (into (sorted-map) (map (fn [[id t]] [id (select-keys t [:seon.task/outcome :seon.task/agent])])) ts2)}))})

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
