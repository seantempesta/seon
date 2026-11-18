(ns seon.git-proc
  "The `:git` Flow proc (`:io` workload):
   Receives `:write-back` from `:transactor` after a green 3-way merge on `:main`,
   renders only `:touched-paths` byte-for-byte from the immutable commit DB,
   writes them to disk, commits to git, and updates `:git/commit` via `:transactor`."
  (:require [clojure.core.async.flow :as flow]
            [datahike.api :as d]
            [seon.git :as git]))

(defn git-proc
  ([] {:params {:conn "Read access to store via :main conn"
                :repo-dir "Git repository working tree"}
       :ins {:in "Write-back commands {:op :write-back :commit :expected-sha :touched-paths :message}"}
       :outs {:tx "Updates :git/commit on :main via :transactor"}
       :workload :io
       :doc "Keeps disk & git `main` 1-to-1 with Datahike `:main` on every merged change."
       :ping-map-fn (fn [st]
                      {:repo-dir (:repo-dir st)
                       :last-sha (:last-sha st)
                       :commits-written (:commits st)})})
  ([{:keys [conn repo-dir]}]
   {:conn conn
    :repo-dir repo-dir
    :last-sha (when repo-dir
                (try (git/head-commit repo-dir) (catch Throwable _ nil)))
    :commits 0})
  ([st _] st)
  ([st _ msg]
   (if (or (nil? msg) (nil? (:repo-dir st)))
     [st nil]
     (case (:op msg)
       :write-back
       (try
         (let [db (d/commit-as-db (:conn st) (:commit msg))
               res (git/write-and-commit-main! db
                                               (:repo-dir st)
                                               (:expected-sha msg)
                                               (:touched-paths msg)
                                               (:message msg))]
           [(cond-> st
              (:commit res) (assoc :last-sha (:commit res))
              (:committed? res) (update :commits inc))
            (cond-> {::flow/report [(str "[:git] wrote " (count (:touched-paths msg))
                                         " file(s) -> git commit " (:commit res))]}
              (:committed? res)
              (assoc :tx [{:op :set-git-commit :sha (:commit res)}]))])
         (catch Throwable ex
           [st {::flow/error [{:proc :git :error (.getMessage ex)}]}]))

       [st nil]))))
