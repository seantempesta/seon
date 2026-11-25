(ns seon.transactor.merge
  "3-way merge execution (`handle-merge-op!`), runtime/ForkParent image sync (`sync-runtime-and-fork-parent!`), and transactor reply/notice helpers."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.fork :as fork]
            [seon.merge :as merge]
            [seon.store :as store]
            [seon.task.gate :as gate]
            [clojure.string :as str]
            [seon.error :as err]
            [seon.ctx.touches :as ctouches]
            [seon.task.refine :as refine]))

(defn reply!
  {:malli/schema [:=> [:cat :map :any] :any]}
  [msg val]
  (when-let [ch (:reply-ch msg)]
    (a/put! ch val)
    (a/close! ch)))

(defn db-notice
  {:malli/schema [:=> [:cat :any :seon.host/db :any :any] :map]}
  [prev-cid db tx-data reason]
  {:prev-commit prev-cid
   :commit (d/commit-id db)
   :tx (:max-tx db)
   :tx-data (vec tx-data)
   :reason reason})

(defn sync-runtime-and-fork-parent!
{:malli/schema [:=> [:cat :seon.host/conn :any :any :any] :map]}
[conn fork-out prev-cid new-cid]
(if (and prev-cid new-cid (not= prev-cid new-cid))
  (let [db @conn
        delta (code/changed-form-ops conn prev-cid new-cid)]
    (when (or (seq (:upserts delta)) (seq (:retracted-keys delta)))
      (let [expanded (code/expand-overlay-rebinds db delta)
            full-upserts (:upserts expanded)
            applied-upserts
            (try
              (fork/apply-form-overlay! db expanded)
              full-upserts
              (catch Throwable _
                (let [touched-nss (into #{} (keep :seon.code.form/ns) full-upserts)
                      full-ns-forms (vec (mapcat #(code/ns-forms db %) touched-nss))]
                  (code/apply-overlay! db (assoc delta :upserts full-ns-forms) true)
                  full-ns-forms)))]
        (when fork-out
          (a/put! fork-out {:upserts applied-upserts
                            :retracted-keys (:retracted-keys delta)
                            :op :advance-image
                            :commit new-cid}))))
    {:boot-affected? (boolean (:boot-affected? delta))})
  {:boot-affected? false}))

(defn- merge-tx-meta [branch eff-aid message]
  (cond-> {:seon.tx/merged branch :seon.tx/reason (or message (str "Merge " branch))}
    eff-aid (assoc :seon.tx/agent eff-aid)))

(defn- merge-eff-agent-id [db branch agent-id]
  (or agent-id
      (when (not= branch :main)
        (some (fn [dt]
                (let [a (d/pull db (quote [:seon.agent/id :seon.agent/branch :seon.agent/interactive?]) (:e dt))]
                  (when (and (= branch (:seon.agent/branch a))
                             (not (:seon.agent/interactive? a)))
                    (:seon.agent/id a))))
              (d/datoms db :aevt :seon.agent/id)))))

(defn- repl-aids-to-archive [db branch rest-ops]
  (when (and (not= branch :main) (contains? (:schema db) :seon.agent/archived?))
    (let [from-db (keep (fn [dt]
                          (let [a (d/pull db (quote [:seon.agent/id :seon.agent/branch :seon.agent/interactive?]) (:e dt))]
                            (when (and (= branch (:seon.agent/branch a))
                                       (:seon.agent/interactive? a)
                                       (not= :repl/main (:seon.agent/id a)))
                              (:seon.agent/id a))))
                        (d/datoms db :aevt :seon.agent/id))
          from-ops (keep (fn [op]
                           (when (and (map? op)
                                      (:seon.agent/id op)
                                      (= branch (:seon.agent/branch op))
                                      (or (:seon.agent/interactive? op)
                                          (= "repl" (namespace (:seon.agent/id op))))
                                      (not= :repl/main (:seon.agent/id op)))
                             (:seon.agent/id op)))
                         rest-ops)]
      (vec (distinct (concat from-db from-ops))))))

(defn- mark-passed-tasks-done [ops passed-tasks]
  (if (empty? passed-tasks)
    ops
    (let [pset (set passed-tasks)
          seen (volatile! #{})
          ops1 (mapv (fn [op]
                       (if (and (map? op) (contains? pset (:seon.task/id op)))
                         (do (vswap! seen conj (:seon.task/id op))
                             (assoc op :seon.task/done? true))
                         op))
                     ops)
          extra (keep (fn [tid]
                        (when-not (contains? @seen tid)
                          {:seon.task/id tid :seon.task/done? true}))
                      passed-tasks)]
      (into ops1 extra))))

(defn- rebind-committed-schema-tempids
  "After `r2` commits new `:seon.schema/key` entities, rewrites their `new/seon.schema/key/...`
   tempids in `rest-ops` to committed lookup refs `[:seon.schema/key k]` so `r3` never creates orphan entities."
  [schema-ops rest-ops]
  (let [tid->lref (into {}
                        (keep (fn [op]
                                (when (and (map? op) (string? (:db/id op)) (:seon.schema/key op))
                                  [(:db/id op) [:seon.schema/key (:seon.schema/key op)]])))
                        schema-ops)]
    (if (empty? tid->lref)
      rest-ops
      (let [rw (fn rw [x]
                 (cond
                   (and (string? x) (contains? tid->lref x)) (get tid->lref x)
                   (set? x) (into #{} (map rw) x)
                   (vector? x) (mapv rw x)
                   (map? x) (reduce-kv (fn [a k v] (assoc a k (rw v))) {} x)
                   :else x))]
        (mapv rw rest-ops)))))

(defn- format-merge-failure-body [branch msg-type conflicts]
  (str (if (= :health-block msg-type) (str "Health error(s) on " branch) (str "3-way merge conflict on " branch))
       " — fix and reply concisely:\n"
       (str/join "\n" (for [c (take 8 conflicts)] (str "- " (pr-str (:entity c)) " [" (name (or (:reason c) :conflict)) "]" (when-let [m (:message c)] (str ": " m)))))))

(defn- ^:seon.flow/edge clear-branch-ready! [cfg branch aid]
  (when (and cfg branch (not= branch :main) aid)
    (try
      (let [bconn (store/connect cfg branch)]
        (try
          (d/transact bconn {:tx-data [{:seon.agent/id aid :seon.agent/ready? false}]})
          true
          (finally (d/release bconn))))
      (catch Throwable _ false))))

(defn- merge-git-commit-message [db branch plan boot-touched? fallback-msg]
  (if-let [f (when (find-ns 'seon.git) (ns-resolve 'seon.git 'format-merge-commit-message))]
    (f db branch plan {:boot-affected? boot-touched?})
    (or fallback-msg (str "Merge " branch))))

(def ^:private sync-domain-id-attrs
  #{:seon.code.form/key :seon.code.var/name :seon.code.ns/name
    :seon.code.doc/id :seon.code.file/path :seon.schema/key
    :seon.rule/id :seon.fix/id :seon.config/key :seon.task/id :seon.summary/id})

(defn- sync-domain-op? [op]
  (cond
    (map? op)
    (boolean (some #(contains? op %) sync-domain-id-attrs))
    (and (vector? op) (contains? #{:db/add :db/retract :db/retractEntity} (first op)) (vector? (second op)))
    (contains? sync-domain-id-attrs (first (second op)))
    :else false))

(defn- extract-review-note-body [conn reviewer reviewer-branch sync-res]
  (let [rdb (when (and reviewer-branch (not= reviewer-branch :main) (store/branch? conn reviewer-branch)) (store/head conn reviewer-branch))
        evs (when (and rdb reviewer (contains? (:schema rdb) :seon.agent.eval/agent))
              (->> (d/datoms rdb :avet :seon.agent.eval/agent reviewer)
                   (map #(d/pull rdb '[:seon.agent.eval/turn :seon.agent.eval/call-index :seon.agent.eval/code :seon.agent.eval/thought] (:e %)))
                   (sort-by (juxt #(or (:seon.agent.eval/turn %) 0) #(or (:seon.agent.eval/call-index %) 0))) vec))
        prose (or (->> evs (filter #(and (str/blank? (str (:seon.agent.eval/code %))) (not (str/blank? (str (:seon.agent.eval/thought %)))))) (map :seon.agent.eval/thought) last)
                  (->> evs (filter #(not (str/blank? (str (:seon.agent.eval/thought %))))) (map :seon.agent.eval/thought) last))
        tp (when (string? prose) (let [t (str/trim prose)] (when-not (str/blank? t) (if (> (count t) 600) (str (subs t 0 597) "...") t))))]
    (str "Review by " (or reviewer :reviewer) " merged; resuming."
         (when (pos? (long (:ops sync-res 0))) (str " Synced " (:ops sync-res) " op(s) from :main."))
         (when (seq (:conflicts sync-res)) (str " Skipped " (count (:conflicts sync-res)) " conflicting :main op(s)."))
         (when-let [e (:error sync-res)] (str " WARNING: :main sync failed (" e ")."))
         (when tp (str "\n\nReviewer note:\n" tp)))))

(defn- ^:seon.flow/edge safe-branch-head [conn branch fallback]
  (or (try (store/head conn branch) (catch Throwable _ nil)) fallback))

(defn- ready-branch-agents-to-stamp [cur-db branch-db branch eff-aid]
(let [pull-a (fn [db]
               (when (and db (contains? (:schema db) :seon.agent/id))
                 (into {} (keep (fn [dt]
                                  (let [a (d/pull db [:seon.agent/id :seon.agent/branch
                                                      :seon.agent/ready? :seon.agent/archived?] (:e dt))]
                                    (when-let [id (:seon.agent/id a)] [id a]))))
                       (d/datoms db :aevt :seon.agent/id))))
      cm (or (pull-a cur-db) {})
      bm (if (and branch-db (not (identical? branch-db cur-db))) (or (pull-a branch-db) {}) {})
      ready (keep (fn [id]
                    (let [ca (cm id) ba (bm id)]
                      (when (and (= branch (or (:seon.agent/branch ca) (:seon.agent/branch ba)))
                                 (or (:seon.agent/ready? ba) (:seon.agent/ready? ca))
                                 (not (or (:seon.agent/archived? ca) (:seon.agent/archived? ba))))
                        id)))
                  (into (set (keys cm)) (keys bm)))]
  (vec (distinct (keep identity (cons eff-aid ready))))))

(defn- resolve-branch-merge-errors [db branch]
  (when (contains? (:schema db) :seon.error/kind)
    (keep (fn [dt]
            (let [p (d/pull db [:seon.error/id :seon.error/status :seon.error/branch] (:e dt))]
              (when (and (= :open (:seon.error/status p)) (= branch (:seon.error/branch p)))
                {:seon.error/id (:seon.error/id p) :seon.error/status :resolved})))
          (d/datoms db :avet :seon.error/kind :merge-error))))

(defn- split-schema-ops [tx-ops]
  (let [s-op? #(and (map? %) (contains? % :seon.schema/key))
        schema-ops (filterv s-op? tx-ops)]
    [schema-ops (rebind-committed-schema-tempids schema-ops (filterv (complement s-op?) tx-ops))]))

(defn- apply-domain-sync-tx! [bconn plan tx-meta]
  (let [s-tx (vec (:schema-tx plan))
        d-ops (filterv sync-domain-op? (:tx-data plan))
        [schema-ops rest-ops] (split-schema-ops d-ops)
        tx-with (fn [ops]
                  (if tx-meta
                    (binding [store/*tx-meta* tx-meta] (d/transact bconn {:tx-data ops}))
                    (d/transact bconn {:tx-data ops})))]
    (when (seq s-tx) (d/transact bconn {:tx-data s-tx}))
    (when (seq schema-ops) (tx-with schema-ops))
    (cond
      (seq rest-ops) (tx-with rest-ops)
      tx-meta (let [allowed (select-keys tx-meta (keys (:schema @bconn)))]
                (when (seq allowed)
                  (d/transact bconn {:tx-data [(assoc allowed :db/id :db/current-tx)]}))))
    (+ (count s-tx) (count d-ops))))

(defn ^:seon.flow/edge sync-main-into-branch!
  "Syncs newly merged `:main` domain entities into `target-branch` via 3-way merge (`:skip-health? true`)
   while preserving 100% of `target-branch`'s eval history, edits, and `:seon.agent/memory`, stamping
   `:seon.tx/merged :main` + `:seon.tx/synced-commit` on every synced transaction."
  {:malli/schema [:=> [:cat :seon.host/conn :map :keyword [:maybe :keyword]] :map]}
  [main-conn cfg target-branch reviewer]
  (if (or (nil? target-branch) (= target-branch :main) (not (store/branch? main-conn target-branch)))
    {:synced? false :ops 0 :conflicts []}
    (let [bconn (store/connect cfg target-branch)]
      (try
        (let [plan (merge/three-way bconn cfg :main {:skip-health? true})
              dom-conflicts (filterv #(and (vector? (:entity %))
                                           (contains? sync-domain-id-attrs (first (:entity %))))
                                     (:conflicts plan))
              tx-meta (cond-> {:seon.tx/merged :main
                               :seon.tx/reason (str "Sync :main into " target-branch
                                                    (when reviewer (str " after review by " reviewer)))}
                        (:theirs-commit plan) (assoc :seon.tx/synced-commit (:theirs-commit plan))
                        reviewer (assoc :seon.tx/agent reviewer))
              n-ops (apply-domain-sync-tx! bconn plan tx-meta)]
          {:synced? true :ops n-ops :conflicts dom-conflicts :synced-commit (:theirs-commit plan) :branch-tx (:max-tx @bconn)})
        (finally
          (d/release bconn))))))

(defn sync-coordinator-branches!
  "Propagates newly merged domain ops from `:main` onto active router branches without touching evals or memory."
  {:malli/schema [:=> [:cat :seon.host/conn :map :map :keyword] :int]}
  [main-conn cfg plan merged-branch]
  (let [mdb @main-conn
        has-ops? (or (seq (:schema-tx plan)) (some sync-domain-op? (:tx-data plan)))]
    (if-not has-ops?
      0
      (let [coord-brs (->> (when (contains? (:schema mdb) :seon.agent/router?) (d/datoms mdb :avet :seon.agent/router? true))
                           (keep (fn [dt]
                                   (let [a (d/pull mdb '[:seon.agent/branch :seon.agent/archived?] (:e dt))
                                         br (:seon.agent/branch a)]
                                     (when (and br (not= br :main) (not= br merged-branch) (not (:seon.agent/archived? a)))
                                       br))))
                           distinct vec)]
        (reduce
         (fn [n cbr]
           (try
             (let [bconn (store/connect cfg cbr)]
               (try (apply-domain-sync-tx! bconn plan nil) (inc n)
                    (finally (d/release bconn))))
             (catch Throwable _ n)))
         0 coord-brs)))))

(defn- next-msg-seq [st db]
  (inc (long (or (:msg-seq st) (count (d/datoms db :aevt :seon.agent.message/id))))))

(defn ^:seon.flow/edge handle-transactor-error!
  "Handles an uncaught transactor op failure cleanly; for `:merge`, records a schema-validated `:seon.error/*` fault with `:agent`/`:branch`/`:agent-ref`, clears branch `:ready? false`, and notifies the agent so `:reconciler` never wedges in `:merging`."
  {:malli/schema [:=> [:cat :map :map :any :any] [:tuple :map :map]]}
  [st msg prev-cid ex]
  (let [emsg (or (when (instance? Throwable ex) (.getMessage ^Throwable ex)) (str ex) "Transactor error")]
    (reply! msg {:ok? false :error emsg})
    (let [{:keys [conn cfg]} st
          b (:branch msg)]
      (if (and (= :merge (:op msg)) conn b (not= b :main))
        (let [db0 @conn
              eff-aid (merge-eff-agent-id db0 b (:agent-id msg))
              _ (clear-branch-ready! cfg b eff-aid)
              mseq (next-msg-seq st db0)
              ag-eid (when eff-aid (:e (first (d/datoms db0 :avet :seon.agent/id eff-aid))))
              fault (cond-> (err/error-tx (cond-> {:kind :merge-error :severity :error :source :transactor
                                                   :status :open :message emsg :branch b
                                                   :data (cond-> {:proc :transactor :op :merge :branch b}
                                                           eff-aid (assoc :subject (str eff-aid)))}
                                            eff-aid (assoc :agent eff-aid)))
                      (and ag-eid (contains? (:schema db0) :seon.error/agent-ref))
                      (assoc :seon.error/agent-ref ag-eid))
              tx-data (cond-> [fault]
                        eff-aid (conj {:seon.agent.message/id (random-uuid)
                                       :seon.agent.message/seq mseq
                                       :seon.agent.message/from :transactor
                                       :seon.agent.message/to eff-aid
                                       :seon.agent.message/type :merge-conflict
                                       :seon.agent.message/body (str "Merge failed on " b ": " emsg)}))
              r (try (d/transact conn {:tx-data tx-data}) (catch Throwable _ nil))
              db (if r (:db-after r) db0)
              cid (d/commit-id db)]
          [(if r
             (-> st (assoc :last-commit cid :last-tx (:max-tx db))
                 (update :writes (fnil inc 0))
                 (cond-> eff-aid (assoc :msg-seq mseq)))
             st)
           (cond-> {::flow/error [{:proc :transactor :op :merge :branch b :error emsg}]}
             r (assoc :db [(db-notice prev-cid db (:tx-data r) [:merge-error b])]))])
        [st {::flow/error [{:proc :transactor :op (:op msg) :error emsg}]}]))))

(defn- handle-merge-conflict! [st msg prev-cid branch agent-id plan]
  (let [{:keys [conn cfg]} st
        conflicts (vec (:conflicts plan))
        eff-aid (merge-eff-agent-id @conn branch agent-id)
        health-only? (and (seq conflicts)
                          (every? #(and (vector? (:entity %)) (= :seon.health/problem (first (:entity %)))) conflicts))
        msg-type (if health-only? :health-block :merge-conflict)]
    (if-not eff-aid
      (do (reply! msg {:ok? false :conflicts conflicts})
          [st {::flow/report [(str "[:transactor] merge CONFLICT on " branch ": " conflicts)]}])
      (let [_ (clear-branch-ready! cfg branch eff-aid)
            mseq (next-msg-seq st @conn)
            mid (random-uuid)
            msg-ent {:seon.agent.message/id mid
                     :seon.agent.message/seq mseq
                     :seon.agent.message/from :transactor
                     :seon.agent.message/to eff-aid
                     :seon.agent.message/type msg-type
                     :seon.agent.message/body (format-merge-failure-body branch msg-type conflicts)}
            r (d/transact conn {:tx-data [msg-ent]})
            db (:db-after r)
            cid (d/commit-id db)]
        (reply! msg {:ok? false :conflicts conflicts :notified eff-aid :type msg-type :message-id mid})
        [(-> st (assoc :last-commit cid :last-tx (:max-tx db) :msg-seq mseq) (update :writes inc))
         {:db [(db-notice prev-cid db (:tx-data r) [msg-type branch eff-aid])]
          ::flow/report [(str "[:transactor] merge " (if health-only? "HEALTH-BLOCK" "CONFLICT")
                                                " on " branch " (notified " eff-aid "): " conflicts)]}]))))

(defn- handle-fork-merge-back!
[st msg prev-cid branch fpt]
(let [{:keys [conn cfg]} st
      {:keys [agent-id target-id target-branch]} fpt
      tconn (store/connect cfg target-branch)
      plan (try (merge/three-way tconn cfg branch {:exclude-agent agent-id :include-session-for target-id})
                (catch Throwable ex (d/release tconn) (throw ex)))]
  (if-not (:ok? plan)
    (do (d/release tconn)
        (handle-merge-conflict! st msg prev-cid branch agent-id plan))
    (let [b-db (safe-branch-head conn branch @conn)
          eff-btx (or (:branch-tx msg) (:max-tx b-db))
          gate-res (when agent-id (gate/evaluate-agent-gate @conn b-db agent-id))
          done-tasks (refine/passed-non-template-tasks @conn (:passed-tasks gate-res))
          s-tx (vec (:schema-tx plan))
          tx-ops (vec (:tx-data plan))
          auto-ops (refine/auto-anchor-parent-proofs-tx @conn b-db agent-id)
          has-edits? (boolean (or (seq s-tx) (seq tx-ops) (seq done-tasks) (seq auto-ops)))
          _ (try
              (when has-edits?
                (let [[schema-ops raw-rest] (split-schema-ops tx-ops)
                      rest-ops (refine/strip-task-ops raw-rest done-tasks)
                      tmeta {:seon.tx/merged :fork-review :seon.tx/agent agent-id
                             :seon.tx/reason (str "Merge forked review " agent-id " into " target-branch)}]
                  (when (seq s-tx) (d/transact tconn {:tx-data s-tx}))
                  (when (seq schema-ops) (binding [store/*tx-meta* tmeta] (d/transact tconn {:tx-data schema-ops})))
                  (when (seq rest-ops) (binding [store/*tx-meta* tmeta] (d/transact tconn {:tx-data rest-ops})))
                  (when (seq auto-ops) (binding [store/*tx-meta* tmeta] (d/transact tconn {:tx-data auto-ops})))))
              (finally (d/release tconn)))
          db0 @conn
          summary (when (contains? (:schema b-db) :seon.agent/summary)
                    (not-empty (str/trim (str (:seon.agent/summary (d/pull b-db '[:seon.agent/summary] [:seon.agent/id agent-id]))))))
          note-body (if summary
                      (str "Review by " agent-id " merged into " target-branch
                           (when (seq (:entity-diffs plan)) (str " (" (count (:entity-diffs plan)) " entity diff(s))"))
                           ":\n" summary)
                      (extract-review-note-body conn agent-id branch {:ops (count tx-ops) :conflicts []}))
          ;; Sync any edited :seon.task/* entities back to :main so task clarity/curated refs stay visible everywhere
          resolved-errs (resolve-branch-merge-errors db0 branch)
          mseq (next-msg-seq st db0)
          mid (random-uuid)
          ta-paused? (true? (:seon.agent/paused? (d/pull db0 '[:seon.agent/paused?] [:seon.agent/id target-id])))
          main-tx (cond-> (vec (concat resolved-errs
                                     [(cond-> {:seon.agent/id agent-id :seon.agent/ready? true :seon.agent/paused? true}
                                        eff-btx (assoc :seon.agent/tested-tx (long eff-btx) :seon.agent/merged-tx (long eff-btx))
                                        (and summary (contains? (:schema db0) :seon.agent/summary)) (assoc :seon.agent/summary summary))
                                      {:seon.agent.message/id mid :seon.agent.message/seq mseq
                                       :seon.agent.message/from agent-id :seon.agent.message/to target-id
                                       :seon.agent.message/type :review-note :seon.agent.message/body note-body}]))
                  ta-paused? (conj {:seon.agent/id target-id :seon.agent/paused? false})
                  (seq done-tasks) (into (map (fn [tid] {:seon.task/id tid :seon.task/done? true}) done-tasks)))
          r (d/transact conn {:tx-data main-tx})
          db (:db-after r)
          cid (d/commit-id db)
          _ (ctouches/clear-live-reads! (get-in cfg [:store :id]) branch agent-id)
          pruned (:pruned-branches (meta (store/prune-merged-branches! conn)))
          _ (store/sync! cfg)
          ans {:ok? true :fork-merged? true :branch branch :target-branch target-branch :target target-id
               :commit cid :tx (:max-tx db) :boot-affected? false :touched-paths (:touched-paths plan)
               :entity-diffs (:entity-diffs plan) :message-id mid :summary summary}]
      (reply! msg ans)
      [(-> st (assoc :last-commit cid :last-tx (:max-tx db) :msg-seq mseq) (update :writes inc))
       (cond-> {:db [(db-notice prev-cid db (:tx-data r) [:fork-merged branch target-branch])]
              ::flow/report [(str "[:transactor] FORK-MERGED " branch " -> " target-branch " (" (count (:entity-diffs plan)) " entities, resumed " target-id ")")]}
       (seq pruned)
       (assoc :git [{:op :delete-branch-ref :branches (vec pruned)}]))]))))

(defn handle-merge-op!
{:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]] :map :any] [:tuple :map :any]]}
[st msg prev-cid]
(if-let [fpt (store/fork-parent-target @(:conn st) (:branch msg) (:agent-id msg))]
  (if (store/branch? (:conn st) (:target-branch fpt))
  (handle-fork-merge-back! st msg prev-cid (:branch msg) fpt)
  (handle-merge-conflict! st msg prev-cid (:branch msg) (:agent-id msg)
    {:ok? false :conflicts [{:entity [:seon.agent/id (:target-id fpt)] :reason :missing-target-branch
                             :message (str "Fork parent branch " (:target-branch fpt) " no longer exists")}]}))
  (let [{:keys [conn cfg repo-dir fork-out]} st
      {:keys [branch agent-id branch-tx message]} msg
      plan (merge/three-way conn cfg branch)]
  (if-not (:ok? plan)
    (handle-merge-conflict! st msg prev-cid branch agent-id plan)
    (let [old-git (:seon.git/commit (d/pull @conn '[:seon.git/commit] [:seon.git/repo :main]))
        eff-aid (merge-eff-agent-id @conn branch agent-id)
        eff-btx (or branch-tx (:max-tx (safe-branch-head conn branch nil)))
        r1 (when (seq (:schema-tx plan))
             (d/transact conn {:tx-data (:schema-tx plan)}))
        [schema-ops rest-ops] (split-schema-ops (:tx-data plan))
        r2 (when (seq schema-ops)
             (d/transact conn {:tx-data schema-ops}))
        cur-db @conn
        branch-db (safe-branch-head conn branch cur-db)
        upserted-vars (into #{} (keep :seon.code.var/name) rest-ops)
        retracted-vars (into #{} (keep (fn [op]
                                 (when (and (vector? op) (= :db/retractEntity (first op))
                                            (vector? (second op)) (= :seon.code.var/name (first (second op))))
                                   (second (second op)))))
                       rest-ops)
        gate-res (when eff-aid
                   (gate/evaluate-agent-gate cur-db branch-db eff-aid
                                             {:upserted-vars upserted-vars
                                              :retracted-vars retracted-vars}))
        repl-aids (repl-aids-to-archive cur-db branch rest-ops)
        tx-data (-> (let [stamp-aids (when eff-btx (ready-branch-agents-to-stamp cur-db branch-db branch eff-aid))]
                    (cond-> rest-ops
                      (seq stamp-aids)
                      (into (map (fn [aid] {:seon.agent/id aid :seon.agent/ready? true
                                            :seon.agent/tested-tx (long eff-btx) :seon.agent/merged-tx (long eff-btx)})
                                 stamp-aids))
                      (seq repl-aids)
                      (into (map #(hash-map :seon.agent/id % :seon.agent/archived? true) repl-aids))))
                    (mark-passed-tasks-done (:passed-tasks gate-res))
                    (into (resolve-branch-merge-errors cur-db branch)))
        r3 (when (seq tx-data)
             (binding [store/*tx-meta* (merge-tx-meta branch eff-aid message)]
               (d/transact conn {:tx-data tx-data})))
        all-ds (concat (:tx-data r1) (:tx-data r2) (:tx-data r3))
        db @conn
        cid (d/commit-id db)
        {:keys [boot-affected?]} (sync-runtime-and-fork-parent! conn fork-out prev-cid cid)
        boot-touched? (or boot-affected? (boolean (some code/boot-file-path? (:touched-paths plan))))
        _ (sync-coordinator-branches! conn cfg plan branch)
        _ (doseq [a (distinct (keep identity (cons eff-aid repl-aids)))]
            (ctouches/clear-live-reads! (get-in cfg [:store :id]) branch a))
        pruned (:pruned-branches (meta (store/prune-merged-branches! conn)))
        _ (store/sync! cfg)
        ans (cond-> {:ok? true
                     :branch branch
                     :commit cid
                     :tx (:max-tx db)
                     :boot-affected? boot-touched?
                     :touched-paths (:touched-paths plan)
                     :auto-fixed (:auto-fixed plan [])
                     :entity-diffs (:entity-diffs plan)}
              gate-res (assoc :gate gate-res))]
    (reply! msg ans)
    [(-> st (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
     (let [git-ops (cond-> []
                      (and (:commit-git? st) repo-dir (seq (:touched-paths plan)))
                      (conj {:op :write-back
                             :branch branch
                             :commit cid
                             :expected-sha old-git
                             :touched-paths (:touched-paths plan)
                             :deleted-paths (:deleted-paths plan #{})
                             :boot-affected? boot-touched?
                             :message (merge-git-commit-message db branch plan boot-touched? message)})
                      (seq pruned)
                      (conj {:op :delete-branch-ref :branches (vec pruned)}))]
     (cond-> {:db [(db-notice prev-cid db all-ds [:merged branch])]
              ::flow/report [(str "[:transactor] MERGED " branch " -> :main @ tx " (:max-tx db)
                                  " (" (count (:entity-diffs plan)) " entities, "
                                  (count (:touched-paths plan)) " files"
                                  (when boot-touched? ", BOOT-AFFECTED") ")")]}
       (seq git-ops)
       (assoc :git git-ops)))])))))

(defn ^:seon.flow/edge handle-resume-reviewed-target!
  "Atomically finishes a review handoff: syncs post-review `:main` into `target-branch`, unpauses `target`,
   and sends a concise `:review-note` message (waking `target` even if 100% of evals were retracted and
   resetting `tool-streak` to 0)."
  {:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]] :map :any] [:tuple :map :any]]}
  [st msg prev-cid]
  (let [{:keys [conn cfg]} st
        {:keys [reviewer reviewer-branch target target-branch]} msg
        db0 @conn
        ta (when (and target (first (d/datoms db0 :avet :seon.agent/id target)))
             (d/pull db0 '[:seon.agent/id :seon.agent/branch :seon.agent/paused?] [:seon.agent/id target]))]
    (if-not (and (:seon.agent/id ta) (:seon.agent/paused? ta))
      (do (reply! msg {:ok? true :already-resumed? true :target target})
          [st nil])
      (let [tb (or target-branch (:seon.agent/branch ta) :main)
            sync-res (try (sync-main-into-branch! conn cfg tb reviewer)
                          (catch Throwable ex {:synced? false :ops 0 :conflicts [] :error (or (.getMessage ex) (str ex))}))
            mseq (next-msg-seq st db0)
            mid (random-uuid)
            note-body (extract-review-note-body conn reviewer reviewer-branch sync-res)
            tx-data [{:seon.agent/id target :seon.agent/paused? false}
                     {:seon.agent.message/id mid :seon.agent.message/seq mseq
                      :seon.agent.message/from (or reviewer :reviewer)
                      :seon.agent.message/to target
                      :seon.agent.message/type :review-note
                      :seon.agent.message/body note-body}]
            r (d/transact conn {:tx-data tx-data})
            db (:db-after r)
            cid (d/commit-id db)]
        (reply! msg {:ok? true :target target :reviewer reviewer :synced sync-res :message-id mid :tx (:max-tx db)})
        [(-> st (assoc :last-commit cid :last-tx (:max-tx db) :msg-seq mseq) (update :writes (fnil inc 0)))
         (cond-> {:db [(db-notice prev-cid db (:tx-data r) [:resume-reviewed-target target reviewer])]
                  ::flow/report [(str "[:transactor] resumed reviewed target " target " after " reviewer
                                      " (synced " (:ops sync-res 0) " op(s) from :main)")]}
           (:error sync-res)
           (assoc ::flow/error [{:proc :transactor :op :resume-reviewed-target :target target :branch tb :error (:error sync-res)}]))]))))