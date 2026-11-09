(ns my.root
  "Root's functions on tasks (from proto.agents.root): write desired state on main;
  the reconciler proc does the rest. Each takes root's system (seon.root/start!)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.edn :as sedn] [seon.contract :as contract :refer [defn defn-]] [seon.root :as root] [seon.task :as task] [seon.doc :as doc] [seon.problems :as problems] [clojure.java.io :as io] [clojure.string :as str] [seon.config :as config]
            [seon.reconcile :as reconcile] [seon.db :as pdb] [seon.template :as tpl] [seon.profile :as profile] [datahike.api :as d]
            [seon.judge :as judge] [seon.model :as model] [seon.turn :as turn] [clojure.edn :as edn] [seon.lib :as lib] [seon.fx :as fx] [seon.error :as err] [seon.source :as source] [seon.program :as program] [seon.agent]))

(defn- write-declared!
  "Declared state's tx written on main: the error that built it as it is; an empty tx
  writes nothing ({:ok true :changed 0}); else the writer's answer, with counts, the datoms
  written and the citation problems of decls (seon.task/citation-problems) when any."
  {:malli/schema [:=> [:cat :seon.root/sys :any :map [:maybe [:sequential :any]]] :map]}
  [sys tx counts decls]
  (cond (contract/error? tx) tx
        (empty? tx) (assoc counts :ok true :changed 0)
        :else (let [ps (when decls (task/citation-problems @(:conn sys) (vec decls)))]
                (cond-> (merge @(root/transact-main! sys tx) counts {:datoms (count tx)}) (seq ps) (assoc :problems ps)))))

(defn create!
  "Write task declarations as held tasks. Answers the writer's answer, or the error."
  {:malli/schema [:=> [:cat :seon.root/sys [:vector :seon.task/declaration]] :map]}
  [sys decls] (write-declared! sys (task/create-tx @(:conn sys) decls) {} decls))

(defn request!
  "Work request decl as the tasks template makes of it (seon.template/request, the default: one
  task that designs, builds and merges itself; seon.template/design-build or
  seon.template/stages for an experiment), written released in one transaction on main (seon.template/request-tx), so no
  agent forks before every task of it is set. Answers the writer's answer with the tasks'
  ids, or the error."
  {:malli/schema [:function [:=> [:cat :seon.root/sys :seon.task/declaration] :map]
                  [:=> [:cat :seon.root/sys :seon.task/declaration ifn?] :map]]}
  ([sys decl] (request! sys decl tpl/request))
  ([sys decl template]
   (let [ds (template decl)
         tx (tpl/request-tx @(:conn sys) ds)]
     (if (contract/error? tx)
       tx
       (assoc @(root/transact-main! sys tx)
              :tasks (mapv #(task/task-id (:seon.task/ns %) (:seon.task/name %)) ds))))))

(defn run!
  "Release tasks ids: the one release (nothing autoruns). Each held task becomes running,
  by compare-and-swap on main (a cancel! between the read and the write refuses the write);
  each id that is not held (unknown, running, cancelled) is answered :seon.root/not-held
  under :not-held, and only the held ones are written. A release is a person's continue: the reasons root last gave
  (:seon.task/reasons) are retracted, so the same again is news to the next judgment (seon.reconcile/judgment); to go
  on past a turn cap, raise the agent's :seon.agent/max-turns first (docs/turn.md#the-cap). Answers the writer's answer (or
  {:ok true :changed 0} when none is held) with :not-held when any."
  {:malli/schema [:=> [:cat :seon.root/sys [:sequential :seon.task/id]] :map]}
  [sys ids]
  (let [db @(:conn sys)
        x (into {} (for [id ids] [id (some->> (pdb/eid db :seon.task/id id) (d/pull db [:seon.task/execution]) :seon.task/execution)]))
        held (filterv #(= :held (x %)) ids)
        refused (vec (for [id ids :when (not= :held (x id))]
                       (contract/error :seon.root/not-held #'my.root/run!
                                       (str "task " id (if-let [e (x id)] (str " is " (name e)) " does not exist") ", not held: nothing to release")
                                       {:task id :execution (x id)})))]
    (cond-> (if (seq held)
              @(root/transact-main! sys (into [] (mapcat #(vector [:db/cas [:seon.task/id %] :seon.task/execution :held :running] [:db.fn/retractAttribute [:seon.task/id %] :seon.task/reasons])) held))
              {:ok true :changed 0})
      (seq refused) (assoc :not-held refused))))

(defn take-back-tx
  "The write that takes the running tasks among ids back from their runs on db: each is held by
  compare-and-swap from :running (a cancel! or a hold between the read and the write refuses the write),
  with its failure naming reason; the reconciler kills a held task's live run (seon.reconcile/classify)
  and keeps the task, which run! releases again (its failure, the reason, stays like any hold's: the
  next run continues from it). Answers {:tx :taken ids :not-running errors}: a task that is held,
  cancelled, unknown, never released or already done (merged work keeps :running) is answered
  :seon.root/not-running."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :seon.task/id] :string] :map]}
  [db ids reason]
  (let [x (into {} (for [id ids] [id (some->> (pdb/eid db :seon.task/id id) (d/pull db [:seon.task/execution]) :seon.task/execution)]))
        idx (reconcile/index db)
        done? (fn [id] (contains? #{:done :merged} (reconcile/task-state idx id)))
        taken (filterv #(and (= :running (x %)) (not (done? %))) ids)]
    {:taken taken
     :tx (vec (mapcat (fn [id] [[:db/cas [:seon.task/id id] :seon.task/execution :running :held]
                                {:seon.task/id id :seon.task/failure {:reason reason}}]) taken))
     :not-running (vec (for [id ids :when (not-any? #{id} taken)]
                         (contract/error :seon.root/not-running #'my.root/take-back-tx
                                         (str "task " id (cond (nil? (x id)) " does not exist" (and (= :running (x id)) (done? id)) " is done" :else (str " is " (name (x id)))) ", not running: nothing to take back")
                                         {:task id :execution (x id)})))}))

(defn hold!
  "Take the running tasks among ids back from their runs (take-back-tx): the live run is killed, the
  task is held with reason and keeps its branch, and run! releases it again (the next run
  continues the branch). Where cancel! is final, this is a pause root can end. Answers the writer's answer
  (or {:ok true :changed 0} when none is running) with :not-running when any."
  {:malli/schema [:=> [:cat :seon.root/sys [:sequential :seon.task/id] :string] :map]}
  [sys ids reason]
  (let [{:keys [tx not-running]} (take-back-tx @(:conn sys) ids reason)]
    (cond-> (if (seq tx) @(root/transact-main! sys tx) {:ok true :changed 0})
      (seq not-running) (assoc :not-running not-running))))

(defn cancel-tx
  "The write that cancels the tasks among ids that exist on db, and an error for each id that names no
  task (:seon.root/unknown-task): a cancel never makes a task, since an id upserted alone is an entity
  with no namespace, name or plan that every reader of tasks then refuses (a bare name given for
  ns/name once made two such and stopped root's boot). Answers {:tx :unknown errors}."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :seon.task/id]] :map]}
  [db ids]
  (let [known? #(some? (pdb/eid db :seon.task/id %))]
    {:tx (task/execution-tx (filterv known? ids) :cancelled)
     :unknown (vec (for [id ids :when (not (known? id))]
                     (contract/error :seon.root/unknown-task #'my.root/cancel-tx
                                     (str "task " id " does not exist (a task's id is ns/name): nothing to cancel")
                                     {:task id})))}))

(defn cancel!
  "Cancel the tasks among ids (cancel-tx): a live run is killed; tasks that need them stay blocked. An id
  that names no task is answered :seon.root/unknown-task under :unknown and written nowhere. Answers the
  writer's answer (or {:ok true :changed 0} when none exists) with :unknown when any."
  {:malli/schema [:=> [:cat :seon.root/sys [:sequential :seon.task/id]] :map]}
  [sys ids]
  (let [{:keys [tx unknown]} (cancel-tx @(:conn sys) ids)]
    (cond-> (if (seq tx) @(root/transact-main! sys tx) {:ok true :changed 0})
      (seq unknown) (assoc :unknown unknown))))

(defn write-back!
  "Sync main's changed files to disk: the files the merged tasks ids (none: every merged task whose files no commit holds) wait on, read from main as
  it is now and committed on the merges branch in one :git effect. What the git effect answers is recorded by seon.reconcile/commit-tx: the sha on each task, which
  clears the wait (and holds a new one for any file main has no text for), or the error again. A task with no such files is answered :seon.root/nothing-lost under
  :not-lost and left out; no source root, or no text on main for any of the files, is answered at once and recorded nowhere (the tasks keep their
  error). Answers {:written ids :paths paths :sha}, the git effect's error, or the precondition's, with :not-lost when any. See docs/source.md#commit-the-merges-branch."
  {:malli/schema [:function [:=> [:cat :seon.root/sys] :map] [:=> [:cat :seon.root/sys [:sequential :seon.task/id]] :map]]}
  ([sys] (write-back! sys (vec (for [e (d/q '[:find [?e ...] :where [?e :seon.task/error]] @(:conn sys))
                                     :let [t (d/pull @(:conn sys) [:seon.task/id :seon.task/outcome :seon.task/error] e)]
                                     :when (reconcile/lost-write-back t)]
                                 (:seon.task/id t)))))
  ([sys ids]
  (let [db @(:conn sys)
        tasks (into {} (for [id ids] [id (d/pull db [:seon.task/outcome :seon.task/error] [:seon.task/id id])]))
        lost (filterv #(some? (reconcile/lost-write-back (tasks %))) ids)
        not-lost (vec (for [id ids :when (not-any? #{id} lost)]
                        (contract/error :seon.root/nothing-lost #'my.root/write-back!
                                        (str "task " id (if (:seon.task/outcome (tasks id)) " has no write-back to repeat" " does not exist")) {:task id})))
        paths (vec (sort (distinct (mapcat #(:seon.git/files (reconcile/lost-write-back (tasks %))) lost))))
        root (config/value :seon.config.source/root)
        files (when (and (seq lost) root) (doc/files db paths))
        members (vec (for [id lost] {:task id}))
        r (cond (empty? lost) {:written []}
                (nil? root) (contract/error :seon.root/no-source-root #'my.root/write-back!
                                            "no source root declared (:seon.config.source/root): nothing is written back here" {:tasks lost})
                (empty? files) (contract/error :seon.root/no-source-text #'my.root/write-back!
                                               (str "main holds no text for " (str/join ", " paths) ": nothing to write back") {:paths paths})
                :else @(root/effect! sys :git {:dir root :branch (config/value :seon.config.source/branch) :files files :modes (source/modes db (keys files))
                                               :message (source/commit-message members)}))
        tx (when (seq files) (reconcile/commit-tx db r members paths (keys files)))]
    (when (seq tx) @(root/transact-main! sys tx))
    (cond-> (if (contract/error? r) r {:written lost :paths (vec (keys files)) :sha (get-in r [:value :sha])})
      (seq not-lost) (assoc :not-lost not-lost)))))

(defn gate!
  "The process tests each of tasks ids' change reaches (a fork, a root, the binary) run on the
  gate checkout (:seon.config.source/gate-checkout). A merge's gate runs them before it merges
  with nobody asking (seon.reconcile's :gate), so the :seon.task/gate? this sets is no longer
  read; it stays until THE PLAN step 0.7 has exercised the gate (task
  seon.proofs/prove-is-the-one-runner). A task
  that already merged unasked (its open :unrun problem) has them run now, through the
  :tests effect on the merges branch's head, which holds its change; their results are
  recorded on it (:seon.task/deferred-results), which closes the problem when each passed.
  Answers {:asked ids :ran {task {test status}}}; without a gate checkout declared, the
  merged ones' tests are each :error, with :seon.root/no-gate-checkout on the task."
  {:malli/schema [:=> [:cat :seon.root/sys [:sequential :seon.task/id]] :map]}
  [sys ids]
  @(root/transact-main! sys (vec (for [id ids] {:seon.task/id id :seon.task/gate? true})))
  (let [want (set ids)
        db @(:conn sys)
        unrun (for [id (sort want)
                    :let [t (d/pull db [:seon.task/outcome :seon.task/deferred :seon.task/deferred-results] [:seon.task/id id])]
                    :when (and (= :merged (:seon.task/outcome t))
                               (seq (:seon.task/deferred t))
                               (not-every? #(= :pass (get (:seon.task/deferred-results t) %)) (:seon.task/deferred t)))]
                [id (vec (sort-by str (:seon.task/deferred t)))])
        ran (into {} (for [[aid tests] unrun
                           :let [repo (config/value :seon.config.source/gate-checkout)
                                 r (if repo
                                     @(root/effect! sys :tests {:repo repo :branch (config/value :seon.config.source/branch) :files {} :tests tests})
                                     (contract/error :seon.root/no-gate-checkout #'my.root/gate!
                                                     "no gate checkout declared (:seon.config.source/gate-checkout): the process tests cannot run here" {:tests tests}))
                                 rs (if (contract/error? r) (zipmap tests (repeat :error)) (get-in r [:value :results]))]]
                       (do @(root/transact-main! sys (err/asked-tx @(:conn sys) r :seon.task/deferred-results
                                                                   (cond-> [{:seon.task/id aid :seon.task/deferred-results rs}]
                                                                     (contract/error? r) (conj {:seon.task/id aid :seon.task/error r}))))
                           [aid rs])))]
    {:asked (vec ids) :ran ran}))

(defn tasks-read-tx
  "The write of outcome, the last read of the task files (:seon.root/tasks: {:files} or {:error}),
  on root. A refused read's error names it as its subject and is written with it, in one
  transaction; the first read whose outcome differs closes it, and the same outcome again
  writes no datom."
  {:malli/schema [:=> [:cat :seon.root/tasks] [:vector :map]]}
  [outcome] [{:seon.root/id :root :seon.root/tasks outcome}])

(defn load-answer
  "Answer r of a read of the task files, as load-tasks! answers it: a refused one (a file's or the
  reconcile's) names :seon.root/tasks as its subject, the read's outcome."
  {:malli/schema [:=> [:cat :map] :map]}
  [r] (cond-> r (contract/error? r) (assoc :seon.error/subject :seon.root/tasks)))

(def ^:private load-lock
  "The one lock every loader of main's declared state takes around its read of main's db and the write
  decided from it (load-tasks!, load-docs!, load-example!, import!: two clients may call them at once): a load writes what it
  read against the db it read, so two interleaved would write the second's decisions against a db the
  first had changed (docs/source.md#indexing-a-tree). Reentrant."
  (Object.))

(defn load-tasks!
  "Reconcile the task files into main (seon.task/load-tx): new entries created held, changed
  ones updated, removed ones cancelled; unchanged ones write nothing. A good read writes its
  outcome, root's :seon.root/tasks (tasks-read-tx), with the declarations when it differs from the
  one held; a refused one answers its error (load-answer) to whoever asked. A person's re-index
  (docs/source.md#indexing-a-tree). Answers the writer's answer with the counts, or the error. Releasing stays run!'s."
  {:malli/schema [:function [:=> [:cat :seon.root/sys] :map] [:=> [:cat :seon.root/sys :string] :map]]}
  ([sys] (load-tasks! sys nil))
  ([sys dir]
   (locking load-lock
     (let [db @(:conn sys)
           files (if dir (root/task-files dir) (root/task-files))
           tx (task/load-tx db files)
           outcome {:files (count files)}
           held (some->> (pdb/eid db :seon.root/id :root) (d/pull db [:seon.root/tasks]) :seon.root/tasks)]
       ;; the outcome rides in the declarations' transaction, and only when it changed: an unchanged
       ;; good read still writes nothing
       (load-answer (write-declared! sys (cond-> tx (and (not (contract/error? tx)) (not= outcome held)) (into (tasks-read-tx outcome)))
                                     {:files (count files)} (mapcat identity (vals files))))))))

(def ^:private example-identities
  "The attributes that name a record of an example session, each unique. See docs/tasks.md#a-task-names-example-sessions."
  [:seon.agent/key :seon.eval/id :seon.message/id :seon.model-call/id])

(defn example-txs
  "The tx-data of the example session file at path (seon.ctx.session/session-tx's answer written with pr-str: a
  non-empty vector of transactions, the first writing the example agent keyed \"example/<id>\"), read as
  seon.root/task-files reads a task file (seon.lib/dir-texts, so a file gone when read is a file not found), or the
  :seon.root/example-refused error naming path: not found, not readable EDN, or not that vector. The one edge
  of an example file (docs/failure-points.md); the read is of the directory's files named like path, the
  one by that name taken."
  {:malli/schema [:=> [:cat :string] [:or [:vector [:vector :map]] :seon.root/example-refused]]}
  [path]
  (let [f (java.io.File. path)
        n (.getName f)
        text (get (lib/dir-texts (or (.getParent f) ".") n) n)
        refuse #(contract/error :seon.root/example-refused #'my.root/example-txs (str "example file " path ": " %) {:path path})
        [ok x] (if text (try [:read (edn/read-string text)] (catch Exception e [:unreadable (ex-message e)])) [:missing])]
    (cond (= :missing ok) (refuse "not found")
          (= :unreadable ok) (refuse (str "not readable EDN: " x))
          (not (and (vector? x) (seq x) (every? #(and (vector? %) (seq %) (every? map? %)) x) (every? (fn [m] (some #(contains? m %) example-identities)) (mapcat identity x))
                    (string? (:seon.agent/key (ffirst x))) (str/starts-with? (:seon.agent/key (ffirst x)) "example/")))
          (refuse "not a non-empty vector of transactions whose first writes an agent keyed \"example/<id>\" (seon.ctx.session/session-tx)")
          :else x)))

(defn load-example!
  "Transact the example session in the EDN file at path (`example-txs`: seon.ctx.session/session-tx's answer, written
  with pr-str) onto main, one transaction each in order, so its records keep the order they ran in; the error
  `example-txs` answers when the file is not one. A task names it by `[:seon.agent/key \"example/<id>\"]` in
  :seon.task/examples. Loading is idempotent: a session whose agent and every record main already holds
  writes nothing ({:ok true :changed 0 :batches 0}); else the last writer's answer with :batches, how many it
  wrote. The example agent has no branch (only the writer makes a branch): its records are main's, and its pages
  open at /agent/main/example-<id>, the page of a branchless agent on main (seon.render.proc/location). See docs/tasks.md#a-task-names-example-sessions."
  {:malli/schema [:=> [:cat :seon.root/sys :string] :map]}
  [sys path]
  (let [txs (example-txs path)]
    (if (contract/error? txs)
      txs
      (locking load-lock
        (let [db @(:conn sys)
              held? (fn [m] (let [a (some #(when (contains? m %) %) example-identities)] (pdb/eid db a (get m a))))]
          (if (every? held? (mapcat identity txs))
            {:ok true :changed 0 :batches 0}
            (assoc (reduce (fn [_ tx] (let [r @(root/transact-main! sys tx)] (if (contract/error? r) (reduced r) r))) nil txs)
                   :batches (count txs))))))))

(defn load-docs!
  "Re-index the wiki (the pages under dir of the checkout, docs by default) into main as section
  entities (seon.doc/load-tx), a person's explicit act: a changed section rewritten, a removed one retracted, an
  unchanged wiki writing nothing. Answers the writer's answer with the counts. See docs/source.md#indexing-a-tree."
  {:malli/schema [:function [:=> [:cat :seon.root/sys] :map] [:=> [:cat :seon.root/sys :string] :map]]}
  ([sys] (load-docs! sys "docs"))
  ([sys dir]
   (locking load-lock
     (let [db @(:conn sys)]
       (write-declared! sys (doc/load-dir-tx db (config/checkout) dir (root/wiki-baseline sys (config/checkout) dir))
                        {:pages (count (doc/pages (config/checkout) dir))} nil)))))

(defn problems
  "Every open problem on main, by class (seon.problems/health): recorded errors counted
  by signature (:platform :eval :agent :effect) and runs' red proofs, cost verdicts,
  merges, deaths, errors and waste, each row with its producer, owning namespace, evidence
  as data, reproducing proofs and the tasks already answering it; and what the program's
  facts make true now (:dead-citation). Reads main and the api's fold (the true counts of
  repeats not yet on main). The render proc's health, kept by what each move of main changed
  (seon.render.proc/health!), brought to main as this call reads it: a read costs what main changed
  since the last, never the whole program (seon.problems/health). See docs/health.md#cost."
  {:malli/schema [:=> [:cat :seon.root/sys] :seon.problems/report]}
  [sys] @(root/call! sys {:op :health :db @(:conn sys)}))

(defn import!
  "Re-index Seon's source into main now, a person's explicit act (docs/source.md#indexing-a-tree): with paths (relative
  to the source root) only those files, else the tree's changes since main's last index (the :source read,
  seon.fx/read-source), as a seed indexes it (seon.root/import-of), written on main with root's :seon.root/source fact
  in one transaction, which keeps what this boot recorded of its binary. Answers the fact written, main's fact when
  nothing differs, the error that stopped it (a read that failed, a file that does not load), or nil when root has no
  source tree."
  {:malli/schema [:function [:=> [:cat :seon.root/sys] :any]
                  [:=> [:cat :seon.root/sys [:maybe [:set :string]]] :any]]}
  ([sys] (import! sys nil))
  ([sys paths]
   (when-let [{:keys [root dirs] :as source} (get-in sys [:bindings :source])]
     (locking load-lock
       (let [t0 (profile/now-ns)
             db @(:conn sys)
             held (:seon.root/source (d/pull db [:seon.root/source] [:seon.root/id :root]))
             paths (when (:digest held) (some-> paths set))
             r @(root/effect! sys :source (cond-> {:root root :dirs dirs :paths paths}
                                           (and (nil? paths) (:digest held) (:tree held)) (assoc :since (:tree held) :since-dirs (:dirs held []))))
             {:keys [tx fact error]} (root/import-of db held r source t0)]
         (cond (contract/error? r) r
               error error
               (nil? tx) held
               :else (let [fact (merge fact (select-keys held [:boot :binary :program :inputs]))
                           w @(root/transact-main! sys (conj tx {:seon.root/id :root :seon.root/source fact}))]
                       (if (contract/error? w) w fact))))))))

(defn union
  "Every branch's history as one db (seon.union): (d/q '[:find ... :where ...] (my.root/union
  sys)) asks what happened across every branch; each datom's transaction names its branch
  (:seon.tx/branch). Built at the first ask (about 1 s at 50k datoms), then kept current by
  every commit (about 1 ms each)."
  {:malli/schema [:=> [:cat :seon.root/sys] :seon.host/db]}
  [sys] (:db @(root/union sys)))

(defn generation
  "The next generation on main (seon.reconcile/generation): the last merges commit built,
  {:commit :checkout :binary :ms}, or why its build was refused; nil before any."
  {:malli/schema [:=> [:cat :seon.root/sys] [:maybe :seon.root/generation]]}
  [sys] (reconcile/generation @(:conn sys)))

(defn resume-args
  "The root arguments (bin/root start ARGS) that resume this root's store and main, Seon's
  source at source-root (its tree's own, by default): a restart catches up on main's merges
  where it boots (seon.program/load-all!), and the test that started this boot, when one did
  (:seon.root/started-by): the next generation is still that test's root."
  {:malli/schema [:function [:=> [:cat :seon.root/sys] [:vector :string]] [:=> [:cat :seon.root/sys [:maybe :string]] [:vector :string]]]}
  ([sys] (resume-args sys (get-in sys [:bindings :source :root])))
  ([sys source-root]
   (-> (into [] (mapcat (fn [[k v]] ["--set" (str k "=" (sedn/encode v))]))
             (cond-> [[:seon.config.root/store-path (:store-path sys)]
                      [:seon.config.root/main (name (pdb/conn-main (:conn sys)))]
                      [:seon.config.source/dirs (config/value :seon.config.source/dirs)]
                      [:seon.config.source/branch (config/value :seon.config.source/branch)]
                      [:seon.config.source/generations-dir (config/value :seon.config.source/generations-dir)]]
               source-root (conj [:seon.config.source/root source-root])))
       (into ["--profile" (name (config/value :seon.config.root/profile))])
       (into (when-let [t (:started-by (task/root @(:conn sys)))] ["--started-by" t])))))

(defn build!
  "Build the next generation from main's last merges commit, for what cannot load in place
  (a Jolt, Chez or dependency pin, deps.edn, native code; a merge loads in place:
  seon.program/load-changes!). Waits for the :build effect (seon.fx/build), records what it
  answered on main (:seon.root/generation, and a failure's :effect row naming it:
  seon.error/asked-tx) and answers it: what `bin/root next` starts. nil
  when no merge was committed."
  {:malli/schema [:=> [:cat :seon.root/sys] [:maybe :seon.root/generation]]}
  [sys]
  (when-let [commit (reconcile/last-commit @(:conn sys))]
    (let [r @(root/effect! sys :build {:repo (get-in sys [:bindings :source :root]) :commit commit})
          g (if (contract/error? r)
              {:commit commit :ms (/ (:seon.fx/us r 0) 1000.0) :error (:seon.error/message r)}
              (:value r))]
      @(root/transact-main! sys (err/asked-tx @(:conn sys) r :seon.root/generation [{:seon.root/id :root :seon.root/generation g}]))
      g)))

(defn next-generation
  "What `bin/root next` starts (docs/source.md#the-next-generation): [checkout & args], the
  built generation's tree and the arguments that resume this root's store and main in it,
  Seon's source now its tree; nil when no generation is built (my.root/build!)."
  {:malli/schema [:=> [:cat :seon.root/sys] [:maybe [:vector :string]]]}
  [sys]
  (when-let [{:keys [checkout]} (let [g (generation sys)] (when (:binary g) g))]
    (into [checkout] (resume-args sys checkout))))

(defn first-moves!
  "Score the first move a model makes on each task of ids (task ids on main): per task, its
  first-turn request (seon.judge/request) and the :seon.judge fact already on main for that
  request's digest; only when there is none, one model call through root's :model owner,
  whose reply's eval calls are scored from the compiler's facts in a disposable agent of
  their own (seon.judge/first-move: analysis runs a reply's ns form, so never in root) and
  written on main with its usage and cost. No loop: a call answers one reply, and nothing of
  it runs. A paid call is made only while the pass has spent under
  :seon.config.ai/judge-pass-usd as the task resolves it (its own setting first: seon.config/resolve-in, so a
  test bounds its pass on its task, never on root's config); at the bound the pass stops and writes :seon.judge/stopped
  on the pass's own fact. A reply whose usage cannot be priced, a model error or a scoring
  error ends the pass with :error. A fact held for the request but scored by other scoring
  (seon.judge/scorer) is scored again from its calls, with no model call (:reused?
  :rescored?). Answers {:rows [{:task :acted :right-var :right-test :waste :uncompiled :green
  :usd :reused? :rescored?}] :usd :stopped :error}."
  {:malli/schema [:=> [:cat :seon.root/sys [:sequential :seon.task/id]] :map]}
  [sys ids]
  (let [bound (fn [db id] (config/value :seon.config.ai/judge-pass-usd {:task (d/pull db [:seon.config.ai/judge-pass-usd] [:seon.task/id id])}))
        src (:seon.root/source (d/pull @(:conn sys) [:seon.root/source] [:seon.root/id :root]))
        version (select-keys src [:root :commit])
        ;; the score of calls against task t's subject, made in an agent of its own (a reply's
        ;; ns forms run there, so no task's reading leaks into the next), ended once it answers;
        ;; the agent answers the score as seon.edn text, which its response prints as a string
        score (fn [db t calls]
                (let [ag (root/spawn! sys {})
                      r (try @(root/submit! sys ag (pr-str (list 'seon.edn/encode (list 'seon.judge/first-move (list 'quote (judge/subject db t)) calls))))
                             (finally (root/kill! sys ag)))]
                  (if (and (map? r) (not (:ex r)) (:text r))
                    (assoc (sedn/decode (:value (edn/read-string {:default tagged-literal} (:text r)))) :scorer (judge/scorer db))
                    (contract/error :seon.judge/unscored #'my.root/first-moves! "the scoring agent answered no score" {:response r}))))
        row (fn [f reused?] (merge {:task (:seon.judge/task f) :reused? (boolean reused?)} (when (= :rescored reused?) {:rescored? true})
                                   (select-keys (:seon.judge/score f) [:acted :right-var :right-test :waste :uncompiled :green :usd])))]
    (loop [[id & more] ids rows [] spent 0.0]
        (if (nil? id)
          {:rows rows :usd spent}
          (let [db @(:conn sys)
                t (task/task db id)
                req (judge/request db t)
                dg (judge/digest req)
                held (d/pull db '[*] [:seon.judge/digest dg])]
            (cond
              (= (judge/scorer db) (:scorer (:seon.judge/score held))) (recur more (conj rows (row held true)) spent)
              ;; held, but scored by other scoring: scored again from its calls, no model call
              (:seon.judge/score held)
              (let [{:keys [calls version usage usd]} (:seon.judge/score held)
                    sc (score db t calls)]
                (if (contract/error? sc)
                  {:rows rows :usd spent :error sc}
                  (let [f {:seon.judge/digest dg :seon.judge/score (cond-> (assoc sc :version version :calls calls) usage (assoc :usage usage) usd (assoc :usd usd))}]
                    @(root/transact-main! sys [f])
                    (recur more (conj rows (row (assoc f :seon.judge/task id) :rescored)) spent))))
              (>= spent (bound db id))
              (let [stopped {:usd spent :bound (bound db id) :left (vec (cons id more))}]
                @(root/transact-main! sys [{:seon.judge/digest (judge/digest {:pass ids :version version}) :seon.judge/stopped stopped}])
                {:rows rows :usd spent :stopped stopped})
              :else
              ;; the judge's model names its provider as a turn's does (seon.config/provider-at): the one way a call names its model
              (let [r @(root/call! sys {:op :fx :kind :model :req {:messages (:messages req) :task id :turn 0 :provider (config/provider-at db db id (:model req))}})
                    c (:value r)
                    calls (mapv :code (:tool-calls c))
                    sc (when-not (contract/error? r) (score db t calls))
                    usage (when c (turn/usage c))
                    usd (when c (model/cost (:model req) usage))]
                (cond
                  ;; a failed call is recorded here, closed by the next judge fact (seon.error/asked-tx)
                  (contract/error? r) (do @(root/transact-main! sys (err/asked-tx @(:conn sys) r :seon.judge/digest []))
                                          {:rows rows :usd spent :error r})
                  (contract/error? sc) {:rows rows :usd spent :error sc}
                  :else
                  (let [f (judge/fact dg id version calls sc usage usd)]
                    @(root/transact-main! sys [f])
                    ;; a cost the provider's usage cannot price is unknown, never 0: the bound
                    ;; cannot hold over it, so the pass ends there, recorded
                    (if usd
                      (recur more (conj rows (row f false)) (+ spent usd))
                      {:rows (conj rows (row f false)) :usd spent
                       :error (contract/error :seon.judge/cost-unknown #'my.root/first-moves!
                                              (str "the reply's usage cannot be priced (model " (:model req) "): the pass stops") {:task id :usage usage})}))))))))))

(defn findings-tx
  "The held tasks the :long-form findings over db's program ask for (seon.problems/long-forms over every
  var, src and test alike), each from its template (seon.template/finding) with the tests reaching its var
  in reach graph g (seon.program/reach-indexed) as its proofs (a test, itself), and none for a var whose task db holds:
  tx-data, [] when there is nothing to file. See docs/health.md#a-form-too-long."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:sequential :any]]}
  [db g]
  (let [decls (vec (for [r (problems/long-forms db {} (into #{} (map :v) (d/datoms db :aevt :var/name)))
                         :let [v (:var r)
                               ;; a long test is its own proof: no test reaches a test
                               decl (tpl/finding r (if (contains? (:tests g) v) #{v} (set (program/tests-reaching g v))))]
                         :when (not (pdb/eid db :seon.task/id (task/task-id (:seon.task/ns decl) (:seon.task/name decl))))]
                     decl))]
    (if (seq decls) (task/create-tx db decls) [])))

(defn slow-tests-tx
  "The held tasks the slow tests of slow ({test ms}: seon.fx/slow-tests) ask for, each from its template
  (seon.template/slow-test), none for a test whose task db holds: tx-data, [] when there is nothing to file.
  See docs/merging.md#a-slow-test-runs-last."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :qualified-symbol :int] :int] [:sequential :any]]}
  [db slow bound]
  (let [decls (vec (for [[t ms] (sort-by key slow)
                         :let [decl (tpl/slow-test t ms bound)]
                         :when (not (pdb/eid db :seon.task/id (task/task-id (:seon.task/ns decl) (:seon.task/name decl))))]
                     decl))]
    (if (seq decls) (task/create-tx db decls) [])))

(defn file-slow-tests!
  "Write main's slow tests as held tasks (slow-tests-tx over the test vars of main, by the verdict cache), so a
  test the cache shows slow is work with its metric known ahead of time; bin/update calls it once root is up.
  Answers the task ids filed, [] when every slow test has its task. See docs/merging.md#a-slow-test-runs-last."
  {:malli/schema [:=> [:cat :seon.root/sys] [:vector :string]]}
  [sys]
  (let [db @(:conn sys)
        tests (into {} (for [d (d/datoms db :aevt :var/name) :when (str/ends-with? (or (namespace (:v d)) "") "-test")] [(:v d) nil]))
        tx (slow-tests-tx db (fx/slow-tests tests) (config/value :seon.config.proofs/slow-test-ms))]
    (when (seq tx) @(root/transact-main! sys tx))
    (vec (keep :seon.task/id tx))))

(defn file-findings!
  "Write main's :long-form findings as held tasks (findings-tx over main and its reach graph), for root or
  the owner to release (my.root/run!): the system's long parts become its work. Answers the task ids filed, [] when every finding has its task."
  {:malli/schema [:=> [:cat :seon.root/sys] [:vector :string]]}
  [sys]
  (let [db @(:conn sys)
        g (program/reach-indexed (program/reach-graph db (fx/declared-refs seon.system/built-text))
                                 (second (seon.agent/test-inputs db)))
        tx (findings-tx db g)]
    (when (seq tx) @(root/transact-main! sys tx))
    (vec (keep :seon.task/id tx))))
