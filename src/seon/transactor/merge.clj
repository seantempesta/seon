(ns seon.transactor.merge
  "3-way merge execution (`handle-merge-op!`), runtime/ForkParent image sync (`sync-runtime-and-fork-parent!`), and transactor reply/notice helpers."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.fork :as fork]
            [seon.merge :as merge]
            [seon.store :as store]))

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
        (let [retracted-by-ns
              (reduce (fn [acc k]
                        (let [[ns-str var-str] (str/split k #"/" 2)]
                          (if (and ns-str var-str (not (str/includes? var-str "#")))
                            (update acc (symbol ns-str) (fnil conj #{}) var-str)
                            acc)))
                      {}
                      (:retracted-keys delta))
              upsert-keys (into #{} (map :seon.code.form/key) (:upserts delta))
              rebind-forms (when (seq retracted-by-ns)
                             (for [[nsym unmapped-names] retracted-by-ns
                                   f (code/ns-forms db nsym)
                                   :let [fk (:seon.code.form/key f)
                                         src (str (:seon.code.form/src f))]
                                   :when (and (not (contains? upsert-keys fk))
                                              (not (contains? (:retracted-keys delta) fk))
                                              (some #(str/includes? src %) unmapped-names))]
                               f))
              full-upserts (if (seq rebind-forms)
                             (into (vec (:upserts delta)) rebind-forms)
                             (:upserts delta))]
          (let [applied-upserts
              (try
                (fork/apply-form-overlay! db (assoc delta :upserts full-upserts))
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
                              :commit new-cid})))))
      {:boot-affected? (boolean (:boot-affected? delta))})
    {:boot-affected? false}))

(defn sync-coordinator-branches!
  "Propagates newly merged code/doc/schema/config/task ops from `:main` onto active non-`:main`
   `:role :coordinator` branches (e.g. `:branch/root`) without touching coordinator evals or memory."
  {:malli/schema [:=> [:cat :seon.host/conn :map :map :keyword] :int]}
  [main-conn cfg plan merged-branch]
  (let [mdb @main-conn
        sync-op? (fn [op]
                   (cond
                     (map? op)
                     (boolean (some #(or (contains? op %) false)
                                    [:seon.code.form/key :seon.code.var/name :seon.code.ns/name
                                     :seon.code.doc/id :seon.code.file/path :seon.schema/key
                                     :seon.config/key :seon.task/id :seon.summary/id]))
                     (and (vector? op) (= :db/retractEntity (first op)) (vector? (second op)))
                     (let [attr (first (second op))]
                       (contains? #{:seon.code.form/key :seon.code.var/name :seon.code.ns/name
                                    :seon.code.doc/id :seon.code.file/path :seon.schema/key
                                    :seon.task/id} attr))
                     :else false))
        s-tx (vec (:schema-tx plan))
        d-tx (filterv sync-op? (:tx-data plan))]
    (if (and (empty? s-tx) (empty? d-tx))
      0
      (let [coord-brs (->> (d/datoms mdb :avet :seon.agent/role :coordinator)
                           (keep (fn [dt]
                                   (let [a (d/pull mdb '[:seon.agent/branch :seon.agent/archived?] (:e dt))
                                         br (:seon.agent/branch a)]
                                     (when (and br (not= br :main) (not= br merged-branch) (not (:seon.agent/archived? a)))
                                       br))))
                           distinct
                           vec)]
        (reduce
         (fn [n cbr]
           (try
             (let [bconn (store/connect cfg cbr)]
               (try
                 (when (seq s-tx) (d/transact bconn {:tx-data s-tx}))
                 (when (seq d-tx) (d/transact bconn {:tx-data d-tx}))
                 (inc n)
                 (finally (d/release bconn))))
             (catch Throwable _ n)))
         0
         coord-brs)))))

(defn handle-merge-op!
  {:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]] :map :any] [:tuple :map :any]]}
  [st msg prev-cid]
  (let [{:keys [conn cfg repo-dir fork-out]} st
        {:keys [branch agent-id branch-tx message]} msg
        plan (merge/three-way conn cfg branch)]
    (if-not (:ok? plan)
      (do (reply! msg {:ok? false :conflicts (:conflicts plan)})
          [st {::flow/report [(str "[:transactor] merge CONFLICT on " branch ": " (:conflicts plan))]}])
      (let [old-git (:seon.git/commit (d/pull @conn '[:seon.git/commit] [:seon.git/repo :main]))
            eff-aid (or agent-id
                        (when (not= branch :main)
                          (some (fn [dt]
                                  (let [a (d/pull @conn '[:seon.agent/id :seon.agent/branch :seon.agent/role] (:e dt))]
                                    (when (and (= branch (:seon.agent/branch a))
                                               (not= :repl (:seon.agent/role a)))
                                      (:seon.agent/id a))))
                                (d/datoms @conn :aevt :seon.agent/id))))
            eff-btx (or branch-tx
                        (try (:max-tx (store/head conn branch)) (catch Throwable _ nil)))
            agent-ent (when eff-aid (d/pull @conn '[:seon.agent/task] [:seon.agent/id eff-aid]))
            r1 (when (seq (:schema-tx plan))
                 (d/transact conn {:tx-data (:schema-tx plan)}))
            schema-ops (filterv #(and (map? %) (contains? % :seon.schema/key)) (:tx-data plan))
            rest-ops   (filterv #(not (and (map? %) (contains? % :seon.schema/key))) (:tx-data plan))
            r2 (when (seq schema-ops)
                 (d/transact conn {:tx-data schema-ops}))
            task-id (:seon.agent/task agent-ent)
            task-proofs-ok? (when task-id
                              (let [cur-db @conn
                                    t-ent (d/pull cur-db '[:seon.task/tests] [:seon.task/id task-id])
                                    req-tests (seq (:seon.task/tests t-ent))]
                                (if-not req-tests
                                  true
                                  (let [upserted-vars (into #{} (keep :seon.code.var/name) rest-ops)]
                                    (every? (fn [tsym]
                                              (let [exists? (or (contains? upserted-vars tsym)
                                                                (boolean (first (d/datoms cur-db :avet :seon.code.var/name tsym))))
                                                    passed? (boolean (some #(:seon.test/pass? (d/pull cur-db '[:seon.test/pass?] (:e %)))
                                                                           (d/datoms cur-db :avet :seon.test/var tsym)))]
                                                (and exists? passed?)))
                                            req-tests)))))
            tx-data (let [repl-aids
                          (when (and (not= branch :main) (contains? (:schema @conn) :seon.agent/archived?))
                            (let [from-db (keep (fn [dt]
                                                  (let [a (d/pull @conn '[:seon.agent/id :seon.agent/branch :seon.agent/role] (:e dt))]
                                                    (when (and (= branch (:seon.agent/branch a))
                                                               (= :repl (:seon.agent/role a))
                                                               (not= :repl/main (:seon.agent/id a)))
                                                      (:seon.agent/id a))))
                                                (d/datoms @conn :aevt :seon.agent/id))
                                  from-ops (keep (fn [op]
                                                   (when (and (map? op)
                                                              (:seon.agent/id op)
                                                              (= branch (:seon.agent/branch op))
                                                              (or (= :repl (:seon.agent/role op))
                                                                  (= "repl" (namespace (:seon.agent/id op))))
                                                              (not= :repl/main (:seon.agent/id op)))
                                                     (:seon.agent/id op)))
                                                 rest-ops)]
                              (vec (distinct (concat from-db from-ops)))))]
                      (cond-> rest-ops
                        (and eff-aid eff-btx)
                        (conj {:seon.agent/id eff-aid
                               :seon.agent/ready? true
                               :seon.agent/tested-tx (long eff-btx)
                               :seon.agent/merged-tx (long eff-btx)})
                        (and task-id task-proofs-ok?)
                        (conj {:seon.task/id task-id :seon.task/done? true})
                        (seq repl-aids)
                        (into (map (fn [raid] {:seon.agent/id raid :seon.agent/archived? true}) repl-aids))))
            r3 (when (seq tx-data)
            (binding [store/*tx-meta* (cond-> {:seon.tx/merged branch
                                               :seon.tx/reason (or message (str "Merge " branch))}
                                        eff-aid (assoc :seon.tx/agent eff-aid))]
              (d/transact conn {:tx-data tx-data})))
            all-ds (concat (:tx-data r1) (:tx-data r2) (:tx-data r3))
            db @conn
            cid (d/commit-id db)
            {:keys [boot-affected?]} (sync-runtime-and-fork-parent! conn fork-out prev-cid cid)
            boot-touched? (or boot-affected? (boolean (some code/boot-file-path? (:touched-paths plan))))
            _ (sync-coordinator-branches! conn cfg plan branch)
            _ (store/sync! cfg)
            ans {:ok? true
                 :branch branch
                 :commit cid
                 :tx (:max-tx db)
                 :boot-affected? boot-touched?
                 :touched-paths (:touched-paths plan)
                 :auto-fixed (:auto-fixed plan [])
                 :entity-diffs (:entity-diffs plan)}]
        (reply! msg ans)
        [(-> st (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
         (cond-> {:db [(db-notice prev-cid db all-ds [:merged branch])]
                  ::flow/report [(str "[:transactor] MERGED " branch " -> :main @ tx " (:max-tx db)
                                      " (" (count (:entity-diffs plan)) " entities, "
                                      (count (:touched-paths plan)) " files"
                                      (when boot-touched? ", BOOT-AFFECTED") ")")]}
           (and (:commit-git? st) repo-dir (seq (:touched-paths plan)))
           (assoc :git [{:op :write-back
                         :branch branch
                         :commit cid
                         :expected-sha old-git
                         :touched-paths (:touched-paths plan)
                         :deleted-paths (:deleted-paths plan #{})
                         :boot-affected? boot-touched?
                         :message (or message (str "Merge " branch))}]))]))))