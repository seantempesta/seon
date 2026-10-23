(ns my.root
  "Root's functions on tasks (from proto.agents.root): write desired state on main;
  the reconciler proc does the rest. Each takes root's system (seon.root/start!)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.edn :as sedn] [seon.contract :as contract :refer [defn defn-]] [seon.root :as root] [seon.task :as task] [seon.docs :as docs] [seon.problems :as problems] [clojure.java.io :as io] [seon.config :as config]
            [seon.reconcile :as reconcile] [seon.db :as pdb] [seon.template :as tpl] [datahike.api :as d]))

(defn- write-declared!
  "Declared state's tx written on main: the refusal that built it as it is; an empty tx
  writes nothing ({:ok true :changed 0}); else the writer's answer, with counts, the datoms
  written and the citation problems of decls (seon.task/citation-problems) when any."
  {:malli/schema [:=> [:cat :seon.root/sys :any :map [:maybe [:sequential :any]]] :map]}
  [sys tx counts decls]
  (cond (contract/error? tx) tx
        (empty? tx) (assoc counts :ok true :changed 0)
        :else (let [ps (when decls (task/citation-problems @(:conn sys) (vec decls)))]
                (cond-> (merge @(root/transact-main! sys tx) counts {:datoms (count tx)}) (seq ps) (assoc :problems ps)))))

(defn create!
  "Write task declarations as held tasks. Answers the writer's answer, or the refusal."
  {:malli/schema [:=> [:cat :seon.root/sys [:vector :seon.task/declaration]] :map]}
  [sys decls] (write-declared! sys (task/create-tx @(:conn sys) decls) {} decls))

(defn request!
  "Work request decl (seon.template/request: design -> implement -> merge on one branch):
  the linked set written released in one transaction on main (seon.template/request-tx),
  so no agent forks before every task of it is set. Answers the writer's answer with the
  set's ids, or the refusal."
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
                       (contract/error :seon.root/not-held 'my.root/run!
                                       (str "task " id (if-let [e (x id)] (str " is " (name e)) " does not exist") ", not held: nothing to release")
                                       {:task id :execution (x id)})))]
    (cond-> (if (seq held)
              @(root/transact-main! sys (mapv #(vector :db/cas [:seon.task/id %] :seon.task/execution :held :running) held))
              {:ok true :changed 0})
      (seq refused) (assoc :not-held refused))))

(defn cancel!
  "Cancel tasks ids: a live attempt is killed; tasks that need them stay blocked."
  {:malli/schema [:=> [:cat :seon.root/sys [:sequential :seon.task/id]] :map]}
  [sys ids] @(root/transact-main! sys (task/execution-tx ids :cancelled)))

(defn gate!
  "Root's go for tasks ids: the process tests each one's change reaches (a shell, a fork, a
  root, the binary: never run automatically, owner's ruling 2026-10-23) run on the gate
  checkout (:seon.config.source/gate-checkout) before it merges, and a red refuses the merge.
  Set before the merge task's attempt is judged; a merge that went on unasked holds an open
  :unrun problem naming them."
  {:malli/schema [:=> [:cat :seon.root/sys [:sequential :seon.task/id]] :map]}
  [sys ids] @(root/transact-main! sys (vec (for [id ids] {:seon.task/id id :seon.task/gate? true}))))

(defn task-files
  "The task files under dir (:seon.config.root/tasks-dir by default), read:
  {owning-ns declarations or refusal}. A file's name is its owning namespace. Each work
  task is followed by its merge task (seon.template/with-merge, over all files, so a need
  on another file's work task is a need on its merge), derived here and never written in
  a file."
  {:malli/schema [:function [:=> [:cat] [:map-of :symbol :any]] [:=> [:cat :string] [:map-of :symbol :any]]]}
  ([] (task-files (config/value :seon.config.root/tasks-dir)))
  ([dir]
   (let [read (into (sorted-map)
                    (for [f (sort-by #(.getName %) (or (seq (.listFiles (io/file dir))) []))
                          :let [n (.getName f)] :when (.endsWith n ".edn")
                          :let [ns-sym (symbol (subs n 0 (- (count n) 4)))]]
                      [ns-sym (task/file-declarations ns-sym (slurp f))]))
         refused (into {} (filter (comp contract/error? val)) read)]
     (if (seq refused)
       read
       (merge (zipmap (keys read) (repeat []))
              (group-by :seon.task/ns (tpl/with-merge (into [] cat (vals read)))))))))

(defn load-tasks!
  "Reconcile the task files into main (seon.task/load-tx): new entries created held, changed
  ones updated, removed ones cancelled; unchanged ones write nothing. Answers the writer's
  answer with the counts, or the refusal. Releasing stays run!'s."
  {:malli/schema [:function [:=> [:cat :seon.root/sys] :map] [:=> [:cat :seon.root/sys :string] :map]]}
  ([sys] (load-tasks! sys nil))
  ([sys dir]
   (let [files (if dir (task-files dir) (task-files))]
     (write-declared! sys (task/load-tx @(:conn sys) files) {:files (count files)} (mapcat identity (vals files))))))

(defn load-docs!
  "Load the wiki (the pages under dir of the checkout, docs by default) into main as section
  entities (seon.docs/load-tx): a changed section rewritten, a removed one retracted, an
  unchanged wiki writing nothing. Answers the writer's answer with the counts, or the
  :seon.docs/refused error naming a link to no section."
  {:malli/schema [:function [:=> [:cat :seon.root/sys] :map] [:=> [:cat :seon.root/sys :string] :map]]}
  ([sys] (load-docs! sys "docs"))
  ([sys dir]
   (write-declared! sys (docs/load-dir-tx @(:conn sys) (config/checkout) dir) {:pages (count (docs/pages (config/checkout) dir))} nil)))

(defn problems
  "Every problem recorded on main, by class (seon.problems/report): recorded errors counted
  by signature (:platform :eval :agent :effect) and attempts' red proofs, cost verdicts,
  merges, deaths, errors and waste, each row with its producer, owning namespace, evidence
  as data, reproducing proofs and the tasks already answering it. Reads main and the api's fold (the true counts of repeats not yet on main); costs
  the problems, not the program."
  {:malli/schema [:=> [:cat :seon.root/sys] :seon.problems/report]}
  [sys] (problems/report @(:conn sys) (root/errors sys)))

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
