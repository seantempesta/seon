(ns my.root
  "Root's functions on tasks (from proto.agents.root): write desired state on main;
  the reconciler proc does the rest. Each takes root's system (seon.root/start!)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.edn :as sedn] [seon.contract :as contract :refer [defn defn-]] [seon.root :as root] [seon.task :as task] [seon.docs :as docs] [seon.problems :as problems] [clojure.java.io :as io] [clojure.string :as str] [seon.config :as config]
            [seon.reconcile :as reconcile] [seon.db :as pdb] [seon.template :as tpl] [seon.watch :as watch] [datahike.api :as d]
            [seon.judge :as judge] [seon.model :as model] [seon.turn :as turn] [clojure.edn :as edn] [seon.lib :as lib] [seon.fx :as fx]))

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
  "Work request decl (seon.template/request: design -> implement -> merge on one branch):
  the linked set written released in one transaction on main (seon.template/request-tx),
  so no agent forks before every task of it is set. Answers the writer's answer with the
  set's ids, or the error."
  {:malli/schema [:=> [:cat :seon.root/sys :seon.task/declaration] :map]}
  [sys decl]
  (let [tx (tpl/request-tx @(:conn sys) decl)]
    (if (contract/error? tx)
      tx
      (assoc @(root/transact-main! sys tx)
             :tasks (mapv #(task/task-id (:seon.task/ns %) (:seon.task/name %)) (tpl/request decl))))))

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

(defn cancel!
  "Cancel tasks ids: a live run is killed; tasks that need them stay blocked."
  {:malli/schema [:=> [:cat :seon.root/sys [:sequential :seon.task/id]] :map]}
  [sys ids] @(root/transact-main! sys (task/execution-tx ids :cancelled)))

(defn gate!
  "Root's go for tasks ids: the process tests each one's change reaches (a shell, a fork, a
  root, the binary: never run automatically, DECISIONS, Process tests are not automatic) run on
  the gate
  checkout (:seon.config.source/gate-checkout). Before the merge task's run is judged,
  its deferred tests run before it merges and a red holds it (:seon.task/gate?). A run
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
                       (do @(root/transact-main! sys (cond-> [{:seon.run/id aid
                                                               :seon.run/deferred-results (vec (for [[t st] rs] {:seon.proof/test t :seon.proof/status st}))}]
                                                       (contract/error? r) (conj {:seon.run/id aid :seon.run/error r})))
                           [aid rs])))]
    {:asked (vec ids) :ran ran}))

(defn task-files
  "The task files under dir (:seon.config.root/tasks-dir by default), read:
  {owning-ns declarations or error}. A file's name is its owning namespace. Each work
  task is followed by its merge task (seon.template/with-merge, over all files, so a need
  on another file's work task is a need on its merge), derived here and never written in
  a file."
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
              (group-by :seon.task/ns (tpl/with-merge (into [] cat (vals read)))))))))

(defn tasks-read-tx
  "The write of outcome, the last read of the task files (:seon.root/tasks: {:files} or {:error}),
  on root. A refusal's error names it as its subject and is written with it, in one
  transaction; the first read whose outcome differs closes it, and the same outcome again
  writes no datom."
  {:malli/schema [:=> [:cat :seon.root/tasks] [:vector :map]]}
  [outcome] [{:seon.root/id :root :seon.root/tasks outcome}])

(defn load-answer
  "Answer r of a read of the task files, as load-tasks! answers it: a refusal (a file's or the
  reconcile's) names :seon.root/tasks as its subject, the read's outcome."
  {:malli/schema [:=> [:cat :map] :map]}
  [r] (cond-> r (contract/error? r) (assoc :seon.error/subject :seon.root/tasks)))

(defn refusal-tx
  "The write of load error e at ms on main db: a :platform error (seon.root/platform-tx) and, in
  the same transaction, the outcome its subject holds (a task-file refusal's, tasks-read-tx)."
  {:malli/schema [:=> [:cat :seon.host/db :map :int] [:vector :any]]}
  [db e ms]
  (cond-> (root/platform-tx db e ms)
    (= :seon.root/tasks (:seon.error/subject e)) (into (tasks-read-tx {:error (:seon.error/message e)}))))

(defn load-tasks!
  "Reconcile the task files into main (seon.task/load-tx): new entries created held, changed
  ones updated, removed ones cancelled; unchanged ones write nothing. A good read writes its
  outcome, root's :seon.root/tasks (tasks-read-tx), with the declarations when it differs from the
  one held; a refused one answers its error (load-answer), whose recorder writes both
  (refusal-tx). Answers the writer's answer with the counts, or the error. Releasing stays run!'s."
  {:malli/schema [:function [:=> [:cat :seon.root/sys] :map] [:=> [:cat :seon.root/sys :string] :map]]}
  ([sys] (load-tasks! sys nil))
  ([sys dir]
   (let [db @(:conn sys)
         files (if dir (task-files dir) (task-files))
         tx (task/load-tx db files)
         outcome {:files (count files)}
         held (some->> (pdb/eid db :seon.root/id :root) (d/pull db [:seon.root/tasks]) :seon.root/tasks)]
     ;; the outcome rides in the declarations' transaction, and only when it changed: an unchanged
     ;; good read still writes nothing
     (load-answer (write-declared! sys (cond-> tx (and (not (contract/error? tx)) (not= outcome held)) (into (tasks-read-tx outcome)))
                                   {:files (count files)} (mapcat identity (vals files)))))))

(defn load-docs!
  "Load the wiki (the pages under dir of the checkout, docs by default) into main as section
  entities (seon.docs/load-tx): a changed section rewritten, a removed one retracted, an
  unchanged wiki writing nothing. Answers the writer's answer with the counts."
  {:malli/schema [:function [:=> [:cat :seon.root/sys] :map] [:=> [:cat :seon.root/sys :string] :map]]}
  ([sys] (load-docs! sys "docs"))
  ([sys dir]
   (write-declared! sys (docs/load-dir-tx @(:conn sys) (config/checkout) dir (root/wiki-baseline sys (config/checkout) dir)) {:pages (count (docs/pages (config/checkout) dir))} nil)))

(defn problems
  "Every open problem on main, by class (seon.problems/health): recorded errors counted
  by signature (:platform :eval :agent :effect) and runs' red proofs, cost verdicts,
  merges, deaths, errors and waste, each row with its producer, owning namespace, evidence
  as data, reproducing proofs and the tasks already answering it; and what the program's
  facts make true now (:dead-citation). Reads main and the api's fold (the true counts of
  repeats not yet on main)."
  {:malli/schema [:=> [:cat :seon.root/sys] :seon.problems/report]}
  [sys] (problems/health @(:conn sys) (root/errors sys)))

(defn- loader
  "Which of main's loaders reads checkout path (relative): :docs (a page under docs/), :tasks
  (a task file), :source (Seon's source, :seon.config.source/dirs), or nil."
  {:malli/schema [:=> [:cat :string] [:maybe [:enum :docs :tasks :source]]]}
  [path]
  (let [under? (fn [dir] (str/starts-with? path (str dir "/")))]
    (cond (and (under? "docs") (str/ends-with? path ".md")) :docs
          (and (str/starts-with? (str (config/checkout) "/" path) (str (config/value :seon.config.root/tasks-dir) "/"))
               (str/ends-with? path ".edn")) :tasks
          (some under? (config/value :seon.config.source/dirs)) :source)))

(def ^:private kinds "Main's loaders, each a kind of what changed on disk." #{:docs :tasks :source})

(defn- load-kinds!
  "Each of ks loaded into main once by its own loader: the wiki (load-docs!), the task files
  (load-tasks!), Seon's source (seon.root/import-source!: main's program follows the tree,
  imported only when it reads otherwise; none when root follows no source). Answers each
  loader's answer and :opened, the derived problems the loads made true (seon.problems/caused
  of main before and after)."
  {:malli/schema [:=> [:cat :seon.root/sys [:set [:enum :docs :tasks :source]]] [:map [:loaded :map] [:opened [:set :string]]]]}
  [sys ks]
  (let [before @(:conn sys)
        source (get-in sys [:bindings :source])
        loaded (into {} (for [k (sort ks) :when (or (not= :source k) source)]
                          [k (case k
                               :docs (load-docs! sys)
                               :tasks (load-tasks! sys)
                               :source (root/import-source! sys source))]))]
    {:loaded loaded :opened (problems/caused before @(:conn sys))}))

(defn load-errors
  "The typed errors among the answers of load-kinds! (a refused task file, a refused load): each
  is to be recorded, never dropped."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [loaded] (filterv contract/error? (vals loaded)))

(defn- loader-dirs
  "Where each of main's loaders reads under checkout co: {kind [dir]}, absolute."
  {:malli/schema [:=> [:cat :string] [:map-of [:enum :docs :tasks :source] [:vector :string]]]}
  [co]
  {:docs [(str co "/docs")] :tasks [(config/value :seon.config.root/tasks-dir)]
   :source (mapv #(str co "/" %) (config/value :seon.config.source/dirs))})

(defn watch-loads
  "What a watcher's batch makes root load, each kind with the first record of the batch that made
  it ({kind [record-kind path]}: what root's watch fact keeps as :last-load, so a load says why): a changed path under checkout co by its loader; the
  checkout's own branch moving (own, its ref under refs: a commit or merge on it) the source, and
  no other ref (another worktree's commit is not this root's); a rescan or a moved root (FSEvents
  coalesced or dropped what happened under it) the loaders reading under that path (a rescan of
  target/ none, of the checkout every kind), and of refs the source alone."
  {:malli/schema [:=> [:cat :string [:maybe :string] [:maybe :string] [:vector [:tuple :keyword :string]]]
                  [:map-of [:enum :docs :tasks :source] [:tuple :keyword :string]]]}
  [co refs own batch]
  (let [dirs (loader-dirs co)
        overlap? (fn [p d] (or (= p d) (str/starts-with? d (str p "/")) (str/starts-with? p (str d "/"))))]
    (reduce (fn [m [kind path :as r]]
              (let [under-refs? (and refs (or (= path refs) (str/starts-with? path (str refs "/"))))
                    ks (cond (and (#{:rescan :root} kind) under-refs?) [:source]
                             (#{:rescan :root} kind) (for [[k ds] dirs :when (some #(overlap? path %) ds)] k)
                             under-refs? (when (= path own) [:source])
                             (str/starts-with? path (str co "/")) (keep identity [(loader (str/replace-first path (str co "/") ""))])
                             :else [])]
                ;; the first record that made each kind keeps it
                (reduce (fn [m k] (if (contains? m k) m (assoc m k r))) m ks)))
            {} batch)))

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
  "Root watches its checkout and its repository's refs (seon.watch, FSEvents: every writer,
  a Claude session, an agent, the owner's editor, git), and loads on its own branch's ref only
  (watch-loads: the repository is shared by every worktree, whose commits are not this root's). What changed while root was down is
  boot's to load, as it always was: the wiki (seon.root/wiki-tx) and the source
  (seon.root/import-source!); task files are loaded on their change, or by load-tasks!, never
  at boot. Then each batch loads what it makes root load (watch-loads),
  and a batch that loads, its paths and any rescan are counted in root's :seon.root/watch fact. A
  watcher that cannot start or dies is a :platform error on main (seon.root/platform-tx), and
  root goes on unwatched: nothing falls back to a poll. Answers the watcher, or the error."
  {:malli/schema [:=> [:cat :seon.root/sys] :any]}
  [sys]
  (let [co (config/checkout)
        refs (fx/git-refs co)
        own (fx/head-ref co)
        roots (vec (remove nil? [co refs]))
        fact (fn [f] (root/transact-main! sys [{:seon.root/id :root :seon.root/watch f}]))
        fresh {:roots roots :boot (:boot sys) :batches 0 :paths 0 :rescans 0}
        held #(or (:seon.root/watch (d/pull @(:conn sys) [:seon.root/watch] [:seon.root/id :root])) fresh)
        died (fn [e] @(root/transact-main! sys (watch-died-tx @(:conn sys) (held) e (System/currentTimeMillis))))
        w (watch/start! roots
                        (fn [batch]
                          (let [why (watch-loads co refs own batch)
                                ks (set (keys why))
                                rescan (last (filter (comp #{:rescan :root} first) batch))]
                            ;; a batch no loader reads (root's own store under runs/, target/) writes
                            ;; nothing, so root's writes never feed its watcher
                            (when (seq ks)
                              (let [{:keys [loaded opened]} (load-kinds! sys ks)]
                                ;; a loader's typed error (a refused task file) is a recorded :platform error; the
                                ;; problems the load made true are shown by health, and the last are kept in the fact
                                (doseq [e (load-errors loaded)]
                                  @(root/transact-main! sys (refusal-tx @(:conn sys) e (System/currentTimeMillis))))
                                @(fact (cond-> (-> (held) (update :batches inc) (update :paths + (count batch)) (assoc :last-opened (vec (sort opened)) :last-load why))
                                         rescan (-> (update :rescans inc) (assoc :last-rescan {:kind (first rescan) :path (second rescan)}))))))))
                        died)]
    (if (contract/error? w)
      (do (died w) w)
      (do @(fact fresh) w))))

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
  where it boots (seon.program/load-all!)."
  {:malli/schema [:function [:=> [:cat :seon.root/sys] [:vector :string]] [:=> [:cat :seon.root/sys [:maybe :string]] [:vector :string]]]}
  ([sys] (resume-args sys (get-in sys [:bindings :source :root])))
  ([sys source-root]
   (into [] (mapcat (fn [[k v]] ["--set" (str k "=" (sedn/encode v))]))
         (cond-> [[:seon.config.root/store-path (:store-path sys)]
                  [:seon.config.root/main (name (pdb/conn-main (:conn sys)))]
                  [:seon.config.source/dirs (config/value :seon.config.source/dirs)]
                  [:seon.config.source/branch (config/value :seon.config.source/branch)]
                  [:seon.config.source/generations-dir (config/value :seon.config.source/generations-dir)]]
           source-root (conj [:seon.config.source/root source-root])))))

(defn build!
  "Build the next generation from main's last merges commit, for what cannot load in place
  (a Jolt, Chez or dependency pin, deps.edn, native code; a merge loads in place:
  seon.program/load-changes!). Waits for the :build effect (seon.fx/build), records what it
  answered on main (:seon.root/generation) and answers it: what `bin/root next` starts. nil
  when no merge was committed."
  {:malli/schema [:=> [:cat :seon.root/sys] [:maybe :seon.root/generation]]}
  [sys]
  (when-let [commit (reconcile/last-commit @(:conn sys))]
    (let [r @(root/effect! sys :build {:repo (get-in sys [:bindings :source :root]) :commit commit})
          g (if (contract/error? r)
              {:commit commit :ms (/ (:seon.fx/us r 0) 1000.0) :error (:seon.error/message r)}
              (:value r))]
      @(root/transact-main! sys [{:seon.root/id :root :seon.root/generation g}])
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
                  (contract/error? r) {:rows rows :usd spent :error r}
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
