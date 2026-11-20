(ns seon.git-proc
  "The `:git` Flow proc (`:io` workload):
   - Auto-syncs branch commits onto local `refs/heads/seon/<branch>` via `git fast-import` (0 worktrees!).
   - Receives `:write-back` from `:transactor` after a green 3-way merge on `:main`, renders `:touched-paths`
     byte-for-byte from the immutable commit DB, writes them to disk, creates a local 2-parent Git merge
     commit on `HEAD`, updates `:git/commit` via `:transactor`, and automatically triggers atomic
     `seon.switchover/run-switch-over!` when `:boot-affected?` is true and `:auto-switch-over?` is enabled."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [datahike.api :as d]
            [seon.git :as git]
            [seon.store :as store]
            [seon.switchover :as switchover]))

(defn git-proc
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat :map] :map] [:=> [:cat :map :any] :map] [:=> [:cat :map :any :any] [:tuple :map :any]]]}
  ([] {:params {:conn "Read access to store via :main conn"
                :cfg "Pure store cfg"
                :repo-dir "Git repository working tree"
                :store-path "LMDB store path (passed to --take-over successor)"
                :port "Bound HTTP port (> 0 enables automatic :boot-affected? switch-over)"}
       :ins {:in "Git sync commands (`:sync-branch` and `:write-back`)"}
       :outs {:tx "Updates `:git/commit` and switch-over faults on `:main` via `:transactor`"}
       :workload :io
       :doc "Keeps local Git (`main` + `refs/heads/seon/*`) 1-to-1 with Datahike commits and triggers seamless core switch-over on :boot-affected? merges."
       :ping-map-fn (fn [st]
                      {:repo-dir (:repo-dir st)
                       :last-sha (:last-sha st)
                       :synced-branches (count (:branch-refs st))
                       :commits-written (:commits st)
                       :switching-over? (boolean (:switching-over? st))})})
  ([{:keys [conn cfg repo-dir store-path port auto-switch-over?]}]
  (let [sw-done-ch (a/chan 4)]
    {:conn conn
     :cfg cfg
     :repo-dir repo-dir
     :store-path store-path
     :port (long (or port 0))
     :auto-switch-over? auto-switch-over?
     :sw-done-ch sw-done-ch
     :switching-over? false
     :last-sha (when repo-dir
                 (try (git/head-commit repo-dir) (catch Throwable _ nil)))
     :branch-refs {}
     :commits 0
     ::flow/in-ports {:sw-done sw-done-ch}}))
  ([st _] st)
  ([st cid msg]
   (cond
     (nil? msg)
     [st nil]

     (= cid :sw-done)
     (let [st' (assoc st :switching-over? false)]
       (if (:ok? msg)
         [st' {::flow/report ["[:git] atomic switch-over successor spawned cleanly; awaiting hand-over."]}]
         [st' {:tx [{:op :record-fault
                     :proc :git
                     :class :switchover-failed
                     :severity :error
                     :subject "seon.switchover"
                     :message (str "Core binary switch-over failed (running server left untouched): " (:error msg))}]
               ::flow/report [(str "[:git] switch-over FAILED (running server untouched): " (:error msg))]}]))

     (nil? (:repo-dir st))
     [st nil]

     :else
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
               (or (:expected-sha msg) (:seon.git/commit (d/pull @(:conn st) '[:seon.git/commit] [:seon.git/repo :main])) (:last-sha st))
               (:touched-paths msg)
               (:message msg)
               b
               (:deleted-paths msg #{}))
               auto-sw? (and (:committed? res)
                             (:boot-affected? msg)
                             (not (:switching-over? st))
                             (pos? (:port st 0))
                             (if (some? (:auto-switch-over? st))
                             (boolean (:auto-switch-over? st))
                             (boolean (store/config-val @(:conn st) :seon.config/auto-switch-over?))))
               _ (when auto-sw?
                   (let [done-ch (:sw-done-ch st)
                         opts {:repo-dir (:repo-dir st)
                               :store-path (:store-path st)
                               :port (:port st)}]
                     (a/thread
                       (let [ans (switchover/run-switch-over! opts)]
                         (a/put! done-ch ans)))))]
           [(cond-> st
              (:commit res) (assoc :last-sha (:commit res))
              (:committed? res) (update :commits inc)
              auto-sw? (assoc :switching-over? true))
            (cond-> {::flow/report (cond-> [(str "[:git] wrote " (count (:touched-paths msg))
                                                 " file(s) -> git commit " (:commit res))]
                                     auto-sw?
                                     (conj "[:git] :boot-affected? merge committed -> staging atomic build + --self-test + --take-over..."))}
              (:committed? res)
              (assoc :tx [{:op :set-git-commit :sha (:commit res)}]))])
         (catch Throwable ex
           [st {::flow/error [{:proc :git :op :write-back :error (.getMessage ex)}]}]))

       [st nil]))))
