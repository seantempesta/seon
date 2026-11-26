(ns seon.git-proc
  "The `:git` Flow proc (`:io` workload):
  - Auto-syncs branch commits onto local `refs/heads/seon/<branch>` via `git fast-import` (0 worktrees!).
  - Honors `:commit-git?` (`false` makes `:sync-branch`, `:diff-branch`, `:write-back`, and `:delete-branch-ref` no-ops).
  - Deletes pruned `refs/heads/seon/<branch>` refs via `:op :delete-branch-ref` (single `:branch` or batch `:branches`).
  - Receives `:write-back` from `:transactor` after a green 3-way merge on `:main`, renders `:touched-paths`
    byte-for-byte from the immutable commit DB, writes them to disk, creates a local 2-parent Git merge
    commit on `HEAD`, updates `:git/commit` via `:transactor`, and automatically triggers atomic
    `seon.switchover/run-switch-over!` when `:boot-affected?` is true and `:auto-switch-over?` is enabled."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [datahike.api :as d]
            [seon.git :as git]
            [seon.store :as store]
            [seon.switchover :as switchover]
            [seon.git.forks :as forks]))

(defn- reply! [ch val]
  (when ch
    (a/put! ch val)
    (a/close! ch)))

(defn- handle-diff-branch!
  "Single-owner `:diff-branch` handler for `git-proc`: exports branch ref via `--force` and returns git diff (no-op when `:commit-git?` is false)."
  [st {:keys [branch plan reply-ch]}]
  (cond
  (or (nil? branch) (= branch :main))
  (do (reply! reply-ch {:ok? (:ok? plan) :branch branch :touched-paths (:touched-paths plan)
                        :entity-diffs (:entity-diffs plan) :conflicts (:conflicts plan)
                        :git-ref nil :diff nil})
      [st nil])

  (false? (:commit-git? st))
  (do (reply! reply-ch {:ok? (:ok? plan) :branch branch :touched-paths (:touched-paths plan)
                        :entity-diffs (:entity-diffs plan) :conflicts (:conflicts plan)
                        :git-ref nil :diff nil :skipped :skipped-commit-git-false})
      [st {::flow/report [(str "[:git] :diff-branch :skipped-commit-git-false (" branch ")")]}])

  :else
  (try
    (let [exported (git/export-branch-ref! (:conn st) (:cfg st) (:repo-dir st) branch plan)
          git-diff (git/git! (:repo-dir st) "diff" "HEAD" (:ref exported))]
      (reply! reply-ch {:ok? (:ok? plan) :branch branch :touched-paths (:touched-paths plan)
                        :entity-diffs (:entity-diffs plan) :conflicts (:conflicts plan)
                        :git-ref (:ref exported) :diff git-diff})
      [(assoc-in st [:branch-refs branch] (:sha exported)) nil])
    (catch Throwable ex
      (reply! reply-ch {:ok? false :branch branch :error (or (.getMessage ex) (str ex))})
      [st {::flow/error [{:proc :git :op :diff-branch :error (.getMessage ex)}]}]))))

(defn- handle-sync-branch! [st msg]
  (if (false? (:commit-git? st))
    (do (reply! (:reply-ch msg) {:ok? true :skipped :skipped-commit-git-false :branch (:branch msg)})
        [st {::flow/report [(str "[:git] :sync-branch :skipped-commit-git-false (" (:branch msg) ")")]}])
    (try
      (let [b (:branch msg)
            exported (when (and b (not= b :main))
                       (git/export-branch-ref! (:conn st) (:cfg st) (:repo-dir st) b))]
        (reply! (:reply-ch msg) {:ok? true :branch b :exported exported})
        [(cond-> st
           exported (assoc-in [:branch-refs b] (:sha exported)))
         (when exported
           {::flow/report [(str "[:git] synced " (:ref exported) " @ " (subs (str (:sha exported)) 0 8))]})])
      (catch Throwable ex
        (reply! (:reply-ch msg) {:ok? false :branch (:branch msg) :error (or (.getMessage ex) (str ex))})
        [st {::flow/error [{:proc :git :op :sync-branch :error (.getMessage ex)}]}]))))

(defn- handle-delete-branch-ref! [st msg]
  (let [bs (vec (distinct (remove nil? (concat (when (:branch msg) [(:branch msg)]) (:branches msg)))))]
    (if (false? (:commit-git? st))
      (do (reply! (:reply-ch msg) {:ok? true :skipped :skipped-commit-git-false :deleted [] :deleted-refs [] :results []})
          [st {::flow/report [(str "[:git] :delete-branch-ref :skipped-commit-git-false (" (count bs) " branch(es))")]}])
      (try
        (let [results (mapv #(git/delete-branch-ref! (:repo-dir st) %) bs)
              deleted (vec (keep #(when (:deleted? %) (:branch %)) results))
              deleted-refs (vec (keep #(when (:deleted? %) (:ref %)) results))
              st' (update st :branch-refs #(apply dissoc % bs))]
          (reply! (:reply-ch msg) {:ok? true :deleted deleted :deleted-refs deleted-refs :results results})
          [st' (when (seq deleted)
                 {::flow/report [(str "[:git] deleted " (count deleted) " ref(s): " (pr-str deleted-refs))]})])
        (catch Throwable ex
          (reply! (:reply-ch msg) {:ok? false :branches bs :error (or (.getMessage ex) (str ex))})
          [st {::flow/error [{:proc :git :op :delete-branch-ref :branches bs :error (.getMessage ex)}]}])))))

(defn- handle-write-back!
  {:seon.flow/edge true}
  [st msg]
  (if (false? (:commit-git? st))
    (do (reply! (:reply-ch msg) {:ok? true :committed? false :skipped :skipped-commit-git-false :branch (:branch msg)})
        [st {::flow/report [(str "[:git] :write-back :skipped-commit-git-false (" (:branch msg) ")")]}])
    (try
      (let [b (:branch msg)
            _ (when (and b (not= b :main))
                (try (git/export-branch-ref! (:conn st) (:cfg st) (:repo-dir st) b)
                     (catch Throwable _ nil)))
            db (d/commit-as-db (:conn st) (:commit msg))
            res (git/write-and-commit-main! db
                  (:repo-dir st)
                  (or (:last-sha st) (:expected-sha msg)
                      (:seon.git/commit (d/pull @(:conn st) (quote [:seon.git/commit]) [:seon.git/repo :main])))
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
        (reply! (:reply-ch msg) (assoc res :ok? true))
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
        (reply! (:reply-ch msg) {:ok? false :branch (:branch msg) :error (or (.getMessage ex) (str ex))})
        [st {::flow/error [{:proc :git :op :write-back :error (.getMessage ex)}]}]))))

(defn- handle-push! [st msg]
  (let [repo (:repo-dir st)]
    (if-not repo
      (do (reply! (:reply-ch msg) {:ok? false :error "No repo-dir configured"}) [st nil])
      (do (reply! (:reply-ch msg)
                  (try (forks/push-all! repo {:dry-run? (boolean (:dry-run? msg))})
                       (catch Throwable ex {:ok? false :error (or (.getMessage ex) (str ex))})))
          [st nil]))))

(defn- handle-switch-over! [st msg]
  (cond
    (:switching-over? st)
    (do (reply! (:reply-ch msg) {:ok? false :error "Switch-over already in progress"}) [st nil])

    (and (nil? (:on-switch-over msg)) (not (pos? (:port st 0))))
    (do (reply! (:reply-ch msg) {:ok? false :error "Switch-over not configured on this instance"}) [st nil])

    :else
    (let [done-ch (:sw-done-ch st)
          sw-fn (or (:on-switch-over msg)
                    #(switchover/run-switch-over! {:repo-dir (:repo-dir st) :store-path (:store-path st) :port (:port st)}))
          rch (:reply-ch msg)]
      (a/io-thread
        (let [ans (try (sw-fn) (catch Throwable ex {:ok? false :error (or (.getMessage ex) (str ex))}))]
          (reply! rch ans)
          (a/put! done-ch ans)))
      [(assoc st :switching-over? true)
       {::flow/report ["[:git] initiating atomic build + --self-test + --take-over switch-over..."]}])))

(defn git-proc
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat :map] :map] [:=> [:cat :map :any] :map] [:=> [:cat :map :any :any] [:tuple :map :any]]], :seon.flow/edge true}
  ([] {:params {:conn "Read access to store via :main conn"
                :cfg "Pure store cfg"
                :repo-dir "Git repository working tree"
                :store-path "LMDB store path (passed to --take-over successor)"
                :port "Bound HTTP port (> 0 enables automatic :boot-affected? switch-over)"
                :commit-git? "Whether git ref sync, deletion, and write-back are enabled (default true)"}
       :ins {:in "Git sync commands (`:sync-branch`, `:diff-branch`, `:delete-branch-ref`, and `:write-back`)"}
       :outs {:tx "Updates `:git/commit` and switch-over faults on `:main` via `:transactor`"}
       :workload :io
       :doc "Keeps local Git (`main` + `refs/heads/seon/*`) 1-to-1 with Datahike commits and triggers seamless core switch-over on :boot-affected? merges."
       :ping-map-fn (fn [st]
                      {:repo-dir (:repo-dir st)
                       :commit-git? (not (false? (:commit-git? st)))
                       :last-sha (:last-sha st)
                       :synced-branches (count (:branch-refs st))
                       :commits-written (:commits st)
                       :switching-over? (boolean (:switching-over? st))})})
  ([{:keys [conn cfg repo-dir store-path port commit-git? auto-switch-over?]}]
   (let [sw-done-ch (a/chan 4)]
     {:conn conn
      :cfg cfg
      :repo-dir repo-dir
      :store-path store-path
      :port (long (or port 0))
      :commit-git? (if (some? commit-git?) (boolean commit-git?) true)
      :auto-switch-over? auto-switch-over?
      :sw-done-ch sw-done-ch
      :switching-over? false
      :last-sha (when repo-dir
                  (try (git/head-commit repo-dir) (catch Throwable _ nil)))
      :branch-refs {}
      :commits 0
      ::flow/in-ports {:sw-done sw-done-ch}}))
  ([st transition]
  (when (= transition ::flow/stop)
    (when-let [ch (:sw-done-ch st)] (a/close! ch)))
  st)
  ([st cid msg]
   (cond
     (nil? msg)
     [st nil]

     (= cid :sw-done)
     (let [st' (assoc st :switching-over? false)]
       (if (:ok? msg)
         (let [resolve-ops (when-let [conn (:conn st)]
                           (let [db @conn]
                             (when (contains? (:schema db) :seon.error/kind)
                               (vec (for [dt (d/datoms db :avet :seon.error/kind :switchover-failed)
                                          :let [e (d/pull db (quote [:seon.error/id :seon.error/status]) (:e dt))]
                                          :when (and (:seon.error/id e) (not= :resolved (:seon.error/status e)))]
                                      {:op :resolve-fault :id (:seon.error/id e)})))))]
         [st' (cond-> {::flow/report ["[:git] atomic switch-over successor spawned cleanly; awaiting hand-over."]}
                (seq resolve-ops) (assoc :tx resolve-ops))])
         [st' {:tx [{:op :record-fault
                     :proc :git
                     :class :switchover-failed
                     :severity :error
                     :subject "seon.switchover"
                     :message (str "Core binary switch-over failed (running server left untouched): " (:error msg))}]
               ::flow/report [(str "[:git] switch-over FAILED (running server untouched): " (:error msg))]}]))

     (and (nil? (:repo-dir st)) (not= :switch-over (:op msg)))
     (do (when-let [rch (:reply-ch msg)]
           (if (= :delete-branch-ref (:op msg))
             (reply! rch {:ok? true :deleted [] :deleted-refs [] :results [] :skipped :no-repo-dir})
             (let [p (:plan msg)]
               (reply! rch {:ok? (boolean (:ok? p)) :branch (:branch msg)
                            :touched-paths (:touched-paths p) :entity-diffs (:entity-diffs p)
                            :conflicts (:conflicts p) :git-ref nil :diff nil}))))
         [st nil])

     :else
     (case (:op msg)
       :sync-branch (handle-sync-branch! st msg)
       :diff-branch (handle-diff-branch! st msg)
       :delete-branch-ref (handle-delete-branch-ref! st msg)
       :write-back (handle-write-back! st msg)
       :push (handle-push! st msg)
       :switch-over (handle-switch-over! st msg)
       [st nil]))))