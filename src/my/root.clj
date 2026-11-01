(ns my.root
  "Root's functions on tasks (from proto.agents.root): write desired state on main;
  the reconciler proc does the rest. Each takes root's system (seon.root/start!)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.edn :as sedn] [seon.contract :as contract :refer [defn defn-]] [seon.root :as root] [seon.task :as task] [seon.doc :as docs] [seon.problems :as problems] [clojure.java.io :as io] [clojure.string :as str] [seon.config :as config]
            [seon.reconcile :as reconcile] [seon.db :as pdb] [seon.template :as tpl] [seon.watch :as watch] [datahike.api :as d]
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
  under :not-held, and only the held ones are written. Answers the writer's answer (or
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
              @(root/transact-main! sys (mapv #(vector :db/cas [:seon.task/id %] :seon.task/execution :held :running) held))
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
  "Write the source files of the merged runs ids (run ids) onto the merges branch again: those a
  failed write-back left (the :seon.git/commit-failed error on the run, naming them), read from
  main as it is now and committed in one :git effect like the reconciler's. What the git effect
  answers is recorded by seon.reconcile/commit-tx: the sha on each run, which clears the error (and
  holds a new one for any file main has no text for), or the error again. A run with no lost
  write-back is answered :seon.root/nothing-lost under :not-lost and left out; no source root, or no
  text on main for any of the files, is answered at once and recorded nowhere (the runs keep their
  error). Answers {:written ids :sha}, the git effect's error, or the precondition's, with :not-lost
  when any."
  {:malli/schema [:=> [:cat :seon.root/sys [:sequential :seon.run/id]] :map]}
  [sys ids]
  (let [db @(:conn sys)
        runs (into {} (for [id ids] [id (task/run db id)]))
        lost (filterv #(some? (reconcile/lost-write-back (runs %))) ids)
        not-lost (vec (for [id ids :when (not-any? #{id} lost)]
                        (contract/error :seon.root/nothing-lost #'my.root/write-back!
                                        (str "run " id (if (runs id) " has no write-back to repeat" " does not exist")) {:run id})))
        paths (vec (sort (distinct (mapcat #(:seon.git/files (reconcile/lost-write-back (runs %))) lost))))
        root (config/value :seon.config.source/root)
        files (when (and (seq lost) root) (source/files db paths))
        members (vec (for [id lost] {:task (:seon.run/task (runs id)) :run id}))
        r (cond (empty? lost) {:written []}
                (nil? root) (contract/error :seon.root/no-source-root #'my.root/write-back!
                                            "no source root declared (:seon.config.source/root): nothing is written back here" {:runs lost})
                (empty? files) (contract/error :seon.root/no-source-text #'my.root/write-back!
                                               (str "main holds no text for " (str/join ", " paths) ": nothing to write back") {:paths paths})
                :else @(root/effect! sys :git {:dir root :branch (config/value :seon.config.source/branch) :files files
                                               :message (source/commit-message members)}))
        tx (when (seq files) (reconcile/commit-tx db r members paths (keys files)))]
    (when (seq tx) @(root/transact-main! sys tx))
    (cond-> (if (contract/error? r) r {:written lost :sha (get-in r [:value :sha])})
      (seq not-lost) (assoc :not-lost not-lost))))

(defn gate!
  "The process tests each of tasks ids' change reaches (a fork, a root, the binary) run on the
  gate checkout (:seon.config.source/gate-checkout). A merge's gate runs them before it merges
  with nobody asking (seon.reconcile's :gate), so the :seon.task/gate? this sets is no longer
  read; it stays until THE PLAN step 0.7 has exercised the gate (task
  seon.proofs/prove-is-the-one-runner). A run
  that already merged unasked (its open :unrun problem) has them run now, through the
  :tests effect on the merges branch's head, which holds its change; their results are
  recorded on it (:seon.run/deferred-results), which closes the problem when each passed.
  Answers {:asked ids :ran {run {test status}}}; without a gate checkout declared, the
  merged ones' tests are each :error, with :seon.root/no-gate-checkout on the run."
  {:malli/schema [:=> [:cat :seon.root/sys [:sequential :seon.task/id]] :map]}
  [sys ids]
  @(root/transact-main! sys (vec (for [id ids] {:seon.task/id id :seon.task/gate? true})))
  (let [want (set ids)
        unrun (for [[aid a] (sort-by key (task/runs @(:conn sys)))
                    :when (and (want (:seon.run/task a)) (= :merged (:seon.run/status a))
                               (seq (:seon.run/deferred a))
                               (not-every? #(= :pass (get (:seon.run/deferred-results a) %)) (:seon.run/deferred a)))]
                [aid (vec (sort-by str (:seon.run/deferred a)))])
        ran (into {} (for [[aid tests] unrun
                           :let [repo (config/value :seon.config.source/gate-checkout)
                                 r (if repo
                                     @(root/effect! sys :tests {:repo repo :branch (config/value :seon.config.source/branch) :files {} :tests tests})
                                     (contract/error :seon.root/no-gate-checkout #'my.root/gate!
                                                     "no gate checkout declared (:seon.config.source/gate-checkout): the process tests cannot run here" {:tests tests}))
                                 rs (if (contract/error? r) (zipmap tests (repeat :error)) (get-in r [:value :results]))]]
                       (do @(root/transact-main! sys (err/asked-tx @(:conn sys) r :seon.run/deferred-results
                                                                   (cond-> [{:seon.run/id aid
                                                                             :seon.run/deferred-results (vec (for [[t st] rs] {:seon.proof/test t :seon.proof/status st}))}]
                                                                     (contract/error? r) (conj {:seon.run/id aid :seon.run/error r}))))
                           [aid rs])))]
    {:asked (vec ids) :ran ran}))

(defn task-files
  "The task files under dir (:seon.config.root/tasks-dir by default), read:
  {owning-ns declarations or error}. A file's name is its owning namespace. Each
  declaration is one :whole task (seon.template/request) that merges itself, so a need on
  another file's task is a need on it; the kind is derived here and never written in a file."
  {:malli/schema [:function [:=> [:cat] [:map-of :symbol :any]] [:=> [:cat :string] [:map-of :symbol :any]]]}
  ([] (task-files (config/value :seon.config.root/tasks-dir)))
  ([dir]
   (let [read (into (sorted-map)
                    (for [[n text] (lib/dir-texts dir ".edn")
                          :let [ns-sym (symbol (subs n 0 (- (count n) 4)))]]
                      [ns-sym (task/file-declarations ns-sym text)]))
         refused (into {} (filter (comp contract/error? val)) read)]
     (if (seq refused)
       read
       (merge (zipmap (keys read) (repeat []))
              (group-by :seon.task/ns (into [] (mapcat tpl/request) (apply concat (vals read)))))))))

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

(defn load-error-tx
  "The write of load error e at ms on main db: a :platform error (seon.root/platform-tx) and, in
  the same transaction, the outcome its subject holds (a refused task-file read's, tasks-read-tx)."
  {:malli/schema [:=> [:cat :seon.host/db :map :int] [:vector :any]]}
  [db e ms]
  (cond-> (root/platform-tx db e ms)
    (= :seon.root/tasks (:seon.error/subject e)) (into (tasks-read-tx {:error (:seon.error/message e)}))))

(def ^:private load-lock
  "The one lock every loader of main's declared state takes around its read of main's db and the write
  decided from it (load-tasks!, load-docs!, load-kinds!, the watcher's batches): a load writes what it
  read against the db it read, so two interleaved would write the second's decisions against a db the
  first had changed (docs/health.md#every-writer-root-loads-what-changed). Reentrant."
  (Object.))

(defn load-tasks!
  "Reconcile the task files into main (seon.task/load-tx): new entries created held, changed
  ones updated, removed ones cancelled; unchanged ones write nothing. A good read writes its
  outcome, root's :seon.root/tasks (tasks-read-tx), with the declarations when it differs from the
  one held; a refused one answers its error (load-answer), whose recorder writes both
  (load-error-tx). Answers the writer's answer with the counts, or the error. Releasing stays run!'s."
  {:malli/schema [:function [:=> [:cat :seon.root/sys] :map] [:=> [:cat :seon.root/sys :string] :map]]}
  ([sys] (load-tasks! sys nil))
  ([sys dir]
   (locking load-lock
     (let [db @(:conn sys)
           files (if dir (task-files dir) (task-files))
           tx (task/load-tx db files)
           outcome {:files (count files)}
           held (some->> (pdb/eid db :seon.root/id :root) (d/pull db [:seon.root/tasks]) :seon.root/tasks)]
       ;; the outcome rides in the declarations' transaction, and only when it changed: an unchanged
       ;; good read still writes nothing
       (load-answer (write-declared! sys (cond-> tx (and (not (contract/error? tx)) (not= outcome held)) (into (tasks-read-tx outcome)))
                                     {:files (count files)} (mapcat identity (vals files))))))))

(defn load-docs!
  "Load the wiki (the pages under dir of the checkout, docs by default) into main as section
  entities (seon.doc/load-tx): a changed section rewritten, a removed one retracted, an
  unchanged wiki writing nothing. Answers the writer's answer with the counts."
  {:malli/schema [:function [:=> [:cat :seon.root/sys] :map] [:=> [:cat :seon.root/sys :string] :map]]}
  ([sys] (load-docs! sys "docs"))
  ([sys dir]
   (locking load-lock
     ;; the boot's digest of the wiki no longer says what main holds: dropped, so the next boot loads
     (let [db @(:conn sys)]
       (write-declared! sys (into (when-let [e (pdb/eid db :seon.root/id :root)] [[:db.fn/retractAttribute e :seon.root/wiki]])
                                  (docs/load-dir-tx db (config/checkout) dir (root/wiki-baseline sys (config/checkout) dir)))
                        {:pages (count (docs/pages (config/checkout) dir))} nil)))))

(defn problems
  "Every open problem on main, by class (seon.problems/health): recorded errors counted
  by signature (:platform :eval :agent :effect) and runs' red proofs, cost verdicts,
  merges, deaths, errors and waste, each row with its producer, owning namespace, evidence
  as data, reproducing proofs and the tasks already answering it; and what the program's
  facts make true now (:dead-citation). Reads main and the api's fold (the true counts of
  repeats not yet on main)."
  {:malli/schema [:=> [:cat :seon.root/sys] :seon.problems/report]}
  [sys] (problems/health @(:conn sys) (root/errors sys)))

(defn- load-kinds!
  "Each of ks loaded into main once by its own loader: the wiki (load-docs!), the task files
  (load-tasks!), Seon's source (seon.root/import-source!: main's program follows the tree,
  imported only when it reads otherwise; none when root follows no source). Answers each
  loader's answer and :opened, the derived problems the loads made true (seon.problems/caused
  of main before and after). With changed (source-changes) the source loads only those files."
  {:malli/schema [:function [:=> [:cat :seon.root/sys [:set [:enum :docs :tasks :source]]] [:map [:loaded :map] [:opened [:set :string]]]]
                  [:=> [:cat :seon.root/sys [:set [:enum :docs :tasks :source]] [:maybe [:set :string]]] [:map [:loaded :map] [:opened [:set :string]]]]]}
  ([sys ks] (load-kinds! sys ks nil))
  ([sys ks changed]
  (locking load-lock
  (let [before @(:conn sys)
        source (get-in sys [:bindings :source])
        loaded (into {} (for [k (sort ks) :when (or (not= :source k) source)]
                          [k (case k
                               :docs (load-docs! sys)
                               :tasks (load-tasks! sys)
                               :source (root/import-source! sys source changed))]))]
    {:loaded loaded :opened (problems/caused before @(:conn sys))}))))

(defn load-errors
  "The typed errors among the answers of load-kinds! (a refused task file, a refused load): each
  is to be recorded, never dropped."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [loaded] (filterv contract/error? (vals loaded)))

(defn- source-root
  "Seon's own source tree the import reads (:seon.config.source/root), canonical, or nil when root
  follows none."
  {:malli/schema [:=> [:cat] [:maybe :string]]}
  [] (some-> (get config/config :seon.config.source/root) java.io.File. .getCanonicalPath))

(defn- loader-dirs
  "Where each of main's loaders reads: {kind [dir]}, absolute. The wiki and the task files are the
  checkout co's; the source is under src, the source root the import reads (Seon's own source
  tree, the write-back checkout: seon.fx/read-source; co when root follows none)."
  {:malli/schema [:=> [:cat :string :string] [:map-of [:enum :docs :tasks :source] [:vector :string]]]}
  [co src]
  {:docs [(str co "/docs")] :tasks [(config/value :seon.config.root/tasks-dir)]
   :source (mapv #(str src "/" %) (config/value :seon.config.source/dirs))})

(defn- loader
  "Which of main's loaders reads path (absolute), for checkout co and source root src: :docs (a
  page, .md, under the wiki's dir), :tasks (a task file, .edn, under the task dir, which a test root
  keeps outside its checkout), :source (under a source dir), or nil: the dirs are loader-dirs, the ones watched."
  {:malli/schema [:=> [:cat :string :string :string] [:maybe [:enum :docs :tasks :source]]]}
  [co src path]
  (let [dirs (loader-dirs co src)
        under? (fn [k] (some #(str/starts-with? path (str % "/")) (get dirs k)))]
    (cond (and (under? :docs) (str/ends-with? path ".md")) :docs
          (and (under? :tasks) (str/ends-with? path ".edn")) :tasks
          (under? :source) :source)))

(defn watch-loads
  "What a watcher's batch makes root load, each kind with the first record of the batch that made
  it ({kind [record-kind path]}: what root's watch fact keeps as :last-load, so a load says why): a changed path by its loader; the
  checkout's own branch or the source root's moving (own, their refs under refs: a commit or
  merge on either) the source, and no other ref (another worktree's commit is not this root's); a rescan or a moved root (FSEvents
  coalesced or dropped what happened under it) the loaders reading under that path (a rescan of
  a loader's dir that loader), and of refs the source alone."
  {:malli/schema [:=> [:cat :string :string [:maybe :string] [:set :string] [:vector [:tuple :keyword :string]]]
                  [:map-of [:enum :docs :tasks :source] [:tuple :keyword :string]]]}
  [co src refs own batch]
  (let [dirs (loader-dirs co src)
        overlap? (fn [p d] (or (= p d) (str/starts-with? d (str p "/")) (str/starts-with? p (str d "/"))))]
    (reduce (fn [m [kind path :as r]]
              (let [under-refs? (and refs (or (= path refs) (str/starts-with? path (str refs "/"))))
                    ks (cond (and (#{:rescan :root} kind) under-refs?) [:source]
                             (#{:rescan :root} kind) (for [[k ds] dirs :when (some #(overlap? path %) ds)] k)
                             under-refs? (when (own path) [:source])
                             :else (keep identity [(loader co src path)]))]
                ;; the first record that made each kind keeps it
                (reduce (fn [m k] (if (contains? m k) m (assoc m k r))) m ks)))
            {} batch)))

(defn source-changes
  "The source files a watcher's batch changed, as paths relative to src, the source root the
  import reads (:seon.config.source/root: .worktrees/merges for the standing root, else co), when the batch
  made root load the source by files alone: every record that makes the source load is a changed
  path to a .clj or .edn file under a source dir (a directory, made or removed, is not one). nil when any is a rescan, a moved root or a branch's own ref moving
  (a commit, a merge): the whole tree is then read. Cost: the batch."
  {:malli/schema [:=> [:cat :string :string [:maybe :string] [:set :string] [:vector [:tuple :keyword :string]]] [:maybe [:set :string]]]}
  [co src refs own batch]
  (let [source (filter #(contains? (watch-loads co src refs own [%]) :source) batch)]
    (when (and (seq source) (every? (fn [[kind path]] (and (= :path kind) (= :source (loader co src path)) (re-find #"\.(clj|edn)$" path))) source))
      (into #{} (map (fn [[_ path]] (str/replace-first path (str src "/") ""))) source))))

(defn watch-died-tx
  "The write of watcher error e at ms on main db, with held root's current :seon.root/watch fact: a :platform error
  (seon.root/platform-tx) whose subject is the watch fact, and that fact with its :error, in one
  transaction. Only a live watcher writes the fact after it (a new boot's start, a batch), so
  that write closes the error (seon.problems/closed?) and nothing is written to close it."
  {:malli/schema [:=> [:cat :seon.host/db :seon.root/watch :map :int] [:vector :map]]}
  [db held e ms]
  (conj (root/platform-tx db (assoc e :seon.error/subject :seon.root/watch) ms)
        {:seon.root/id :root :seon.root/watch (assoc held :error (:seon.error/message e))}))

(defn watch!
  "Root watches what its loaders read, loader-dirs (the wiki, the task files, the source dirs),
  and its repository's refs (seon.watch, FSEvents: every writer, a Claude session, an agent, the
  owner's editor, git), never the whole checkout, whose builds (target/), store (runs/) and, in
  the main tree, other worktrees no loader reads. It loads on its own branch's ref only
  (watch-loads: the repository is shared by every worktree, whose commits are not this root's). What changed while root was down is
  boot's to load, as it always was: the wiki (seon.root/wiki-tx) and the source
  (seon.root/import-source!), and here the task files, once the watcher is ready (a fresh or
  erased store holds none, and a file changed while root was down is read; an unchanged read
  writes nothing). The boot's load and the batches' run one at a time, so a change after ready
  is read by the boot's load, a batch's, or both, in order. Then each batch loads what it makes root load (watch-loads),
  and a batch that loads, its paths and any rescan are counted in root's :seon.root/watch fact (which
  closes an earlier error). A watcher that cannot start or dies is a :platform error on main
  (seon.root/platform-tx), and root goes on unwatched: nothing falls back to a poll. A batch whose
  handler throws is the same kind of error (:seon.watch/batch-failed) and the watcher reads the next
  batch. Answers the watcher, or the error."
  {:malli/schema [:=> [:cat :seon.root/sys] :any]}
  [sys]
  (let [co (config/checkout)
        refs (fx/git-refs co)
        src (or (source-root) co)
        ;; a source root follows main's ref, never co's checked-out branch (seon.fx/follow-main),
        ;; so main's ref moving is this root's; without one, root reads co's own branch
        own (if-let [s (source-root)]
              (into #{} (remove nil?) [(some-> refs (str "/heads/main")) (fx/head-ref s)])
              (into #{} (keep fx/head-ref) [co]))
        roots (vec (distinct (remove nil? (concat (mapcat val (sort-by key (loader-dirs co src))) [refs]))))
        fact (fn [f] (root/transact-main! sys [{:seon.root/id :root :seon.root/watch f}]))
        fresh {:roots roots :boot (:boot sys) :batches 0 :paths 0 :rescans 0}
        held #(or (:seon.root/watch (d/pull @(:conn sys) [:seon.root/watch] [:seon.root/id :root])) fresh)
        died (fn [e] @(root/transact-main! sys (watch-died-tx @(:conn sys) (held) e (System/currentTimeMillis))))
        ;; a loader's typed error (a refused task file) is a recorded :platform error; the problems
        ;; the load made true are shown by health. One load at a time (load-lock, the REPL's loads too): a load
        ;; writes what it read against the db it read, so the boot's and a first batch's must not interleave (a stale
        ;; one written after a newer one would undo it, and its event is spent)
        load! (fn [ks changed]
                (locking load-lock
                  (let [r (load-kinds! sys ks changed)]
                    (doseq [e (load-errors (:loaded r))]
                      @(root/transact-main! sys (load-error-tx @(:conn sys) e (System/currentTimeMillis))))
                    r)))
        w (watch/start! roots
                        (fn [batch]
                          (let [why (watch-loads co src refs own batch)
                                ks (set (keys why))
                                changed (when (contains? ks :source) (source-changes co src refs own batch))
                                rescan (last (filter (comp #{:rescan :root} first) batch))]
                            ;; a batch no loader reads (root's own store under runs/, target/) writes
                            ;; nothing, so root's writes never feed its watcher
                            (when (seq ks)
                              ;; the problems the load made true, the last kept in the fact
                              (let [{:keys [opened]} (load! ks changed)]
                                @(fact (cond-> (-> (held) (dissoc :error) (update :batches inc) (update :paths + (count batch)) (assoc :last-opened (vec (sort opened)) :last-load why))
                                         rescan (-> (update :rescans inc) (assoc :last-rescan {:kind (first rescan) :path (second rescan)}))))))))
                        died died)]
    (if (contract/error? w)
      (do (died w) (load! #{:tasks} nil) w)
      (do @(fact fresh) (load! #{:tasks} nil) w))))

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
  :seon.config.ai/judge-pass-usd; at the bound the pass stops and writes :seon.judge/stopped
  on the pass's own fact. A reply whose usage cannot be priced, a model error or a scoring
  error ends the pass with :error. A fact held for the request but scored by other scoring
  (seon.judge/scorer) is scored again from its calls, with no model call (:reused?
  :rescored?). Answers {:rows [{:task :acted :right-var :right-test :waste :uncompiled :green
  :usd :reused? :rescored?}] :usd :stopped :error}."
  {:malli/schema [:=> [:cat :seon.root/sys [:sequential :seon.task/id]] :map]}
  [sys ids]
  (let [bound (config/value :seon.config.ai/judge-pass-usd)
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
              (let [{:keys [calls version usd]} (:seon.judge/score held)
                    sc (score db t calls)]
                (if (contract/error? sc)
                  {:rows rows :usd spent :error sc}
                  (let [f {:seon.judge/digest dg :seon.judge/score (cond-> (assoc sc :version version :calls calls) usd (assoc :usd usd))}]
                    @(root/transact-main! sys [f])
                    (recur more (conj rows (row (assoc f :seon.judge/task id) :rescored)) spent))))
              (>= spent bound)
              (let [stopped {:usd spent :bound bound :left (vec (cons id more))}]
                @(root/transact-main! sys [{:seon.judge/digest (judge/digest {:pass ids :version version}) :seon.judge/stopped stopped}])
                {:rows rows :usd spent :stopped stopped})
              :else
              (let [r @(root/call! sys {:op :fx :kind :model :req {:messages (:messages req) :task id :turn 0 :model (:model req)}})
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

(defn file-findings!
  "Write main's :long-form findings as held tasks (findings-tx over main and its reach graph), for root or
  the owner to release (my.root/run!): the system's long parts become its work (task
  seon.template/a-finding-is-a-task). Answers the task ids filed, [] when every finding has its task."
  {:malli/schema [:=> [:cat :seon.root/sys] [:vector :string]]}
  [sys]
  (let [db @(:conn sys)
        g (program/reach-indexed (program/reach-graph db (fx/declared-refs (config/value :seon.config.root/system-path)))
                                 (second (seon.agent/test-inputs db)))
        tx (findings-tx db g)]
    (when (seq tx) @(root/transact-main! sys tx))
    (vec (keep :seon.task/id tx))))
