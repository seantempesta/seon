(ns seon.git-proc
  "The `:git` Flow proc (`:io` workload):
   - Auto-syncs branch commits onto local `refs/heads/seon/<branch>` via `git fast-import` (0 worktrees!).
   - Receives `:write-back` from `:transactor` after a green 3-way merge on `:main`, renders `:touched-paths`
     byte-for-byte from the immutable commit DB, writes them to disk, creates a local 2-parent Git merge
     commit on `HEAD`, and updates `:git/commit` via `:transactor`."
  (:require [clojure.core.async.flow :as flow]
            [datahike.api :as d]
            [seon.git :as git]))

(defn git-proc
  ([] {:params {:conn "Read access to store via :main conn"
                :cfg "Pure store cfg"
                :repo-dir "Git repository working tree"}
       :ins {:in "Git sync commands (`:sync-branch` and `:write-back`)"}
       :outs {:tx "Updates `:git/commit` on `:main` via `:transactor`"}
       :workload :io
       :doc "Keeps local Git (`main` + `refs/heads/seon/*`) 1-to-1 with Datahike commits."
       :ping-map-fn (fn [st]
                      {:repo-dir (:repo-dir st)
                       :last-sha (:last-sha st)
                       :synced-branches (count (:branch-refs st))
                       :commits-written (:commits st)})})
  ([{:keys [conn cfg repo-dir]}]
   {:conn conn
    :cfg cfg
    :repo-dir repo-dir
    :last-sha (when repo-dir
                (try (git/head-commit repo-dir) (catch Throwable _ nil)))
    :branch-refs {}
    :commits 0})
  ([st _] st)
  ([st _ msg]
   (if (or (nil? msg) (nil? (:repo-dir st)))
     [st nil]
     (case (:op msg)
       :sync-branch
       (try
         (let [b (:branch msg)
               exported (when (and b (not= b :main))
                          (git/export-branch-ref! (:conn st) (:cfg st) (:repo-dir st) b))]
           [(cond-> st
              exported (assoc-in [:branch-refs b] (:sha exported)))
            (when exported
              {::flow/report [(str "[:git] synced " (:ref exported) " @ " (subs (str (:sha exported)) 0 8))]})])
         (catch Throwable ex
           [st {::flow/error [{:proc :git :op :sync-branch :error (.getMessage ex)}]}]))

       :write-back
       (try
         (let [b (:branch msg)
               _ (when (and b (not= b :main))
                   (try (git/export-branch-ref! (:conn st) (:cfg st) (:repo-dir st) b)
                        (catch Throwable _ nil)))
               db (d/commit-as-db (:conn st) (:commit msg))
               res (git/write-and-commit-main! db
                                               (:repo-dir st)
                                               (:expected-sha msg)
                                               (:touched-paths msg)
                                               (:message msg)
                                               b)]
           [(cond-> st
              (:commit res) (assoc :last-sha (:commit res))
              (:committed? res) (update :commits inc))
            (cond-> {::flow/report [(str "[:git] wrote " (count (:touched-paths msg))
                                         " file(s) -> git commit " (:commit res))]}
              (:committed? res)
              (assoc :tx [{:op :set-git-commit :sha (:commit res)}]))])
         (catch Throwable ex
           [st {::flow/error [{:proc :git :op :write-back :error (.getMessage ex)}]}]))

       [st nil]))))
