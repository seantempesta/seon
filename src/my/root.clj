(ns my.root
  "Root's verbs on tasks (lanes R1 and M1's proto.agents.root): write desired state on main;
  the reconciler proc does the rest. Each takes root's system (seon.root/start!)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.root :as root] [seon.task :as task] [seon.docs :as docs] [seon.problems :as problems] [clojure.java.io :as io] [seon.config :as config]
            [seon.reconcile :as reconcile] [seon.db :as pdb] [seon.template :as tpl]))

(defn create!
  "Write task declarations as held tasks. Answers the writer's answer, or the refusal."
  {:malli/schema [:=> [:cat :seon.root/sys [:vector :seon.task/declaration]] :map]}
  [sys decls]
  (let [tx (task/create-tx @(:conn sys) decls)]
    (if (:seon.error/type tx)
      tx
      (let [ps (task/citation-problems @(:conn sys) decls)]
        (cond-> @(root/transact-main! sys tx) (seq ps) (assoc :problems ps))))))

(defn run!
  "Release tasks ids: the one release (nothing autoruns)."
  {:malli/schema [:=> [:cat :seon.root/sys [:sequential :seon.task/id]] :map]}
  [sys ids] @(root/transact-main! sys (task/execution-tx ids :running)))

(defn cancel!
  "Cancel tasks ids: a live attempt is killed; tasks that need them stay blocked."
  {:malli/schema [:=> [:cat :seon.root/sys [:sequential :seon.task/id]] :map]}
  [sys ids] @(root/transact-main! sys (task/execution-tx ids :cancelled)))

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
         refused (into {} (filter (comp :seon.error/type val)) read)]
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
   (let [files (if dir (task-files dir) (task-files))
         tx (task/load-tx @(:conn sys) files)]
     (cond (:seon.error/type tx) tx
           (empty? tx) {:ok true :files (count files) :changed 0}
           :else (let [ps (task/citation-problems @(:conn sys) (vec (mapcat identity (vals files))))]
                   (cond-> (assoc @(root/transact-main! sys tx) :files (count files) :datoms (count tx))
                     (seq ps) (assoc :problems ps)))))))

(defn load-docs!
  "Load the wiki (the pages under dir of the checkout, docs by default) into main as section
  entities (seon.docs/load-tx): a changed section rewritten, a removed one retracted, an
  unchanged wiki writing nothing. Answers the writer's answer with the counts, or the
  :seon.docs/refused error naming a link to no section."
  {:malli/schema [:function [:=> [:cat :seon.root/sys] :map] [:=> [:cat :seon.root/sys :string] :map]]}
  ([sys] (load-docs! sys "docs"))
  ([sys dir]
   (let [pages (docs/pages (config/checkout) dir)
         tx (docs/load-tx @(:conn sys) dir pages)]
     (cond (:seon.error/type tx) tx
           (empty? tx) {:ok true :pages (count pages) :changed 0}
           :else (assoc @(root/transact-main! sys tx) :pages (count pages) :datoms (count tx))))))

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

(defn next-generation
  "What `bin/root next` starts (docs/source.md#the-next-generation): [checkout & args], the
  built generation's tree and the root arguments that resume this root's store and main in
  it, Seon's source now its tree; nil when no generation is built."
  {:malli/schema [:=> [:cat :seon.root/sys] [:maybe [:vector :string]]]}
  [sys]
  (when-let [{:keys [checkout]} (let [g (generation sys)] (when (:binary g) g))]
    (into [checkout]
          (mapcat (fn [[k v]] ["--set" (str k "=" (pr-str v))]))
          [[:seon.config.root/store-path (:store-path sys)]
           [:seon.config.root/main (name (pdb/conn-main (:conn sys)))]
           [:seon.config.source/root checkout]
           [:seon.config.source/dirs (config/value :seon.config.source/dirs)]
           [:seon.config.source/branch (config/value :seon.config.source/branch)]
           [:seon.config.source/generations-dir (config/value :seon.config.source/generations-dir)]
           [:seon.config.source/build-lock (config/value :seon.config.source/build-lock)]])))
