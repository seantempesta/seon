(ns seon.transactor.calls
  "Flow-native call-graph handlers for `:transactor` (`:create-call`, `:await-calls`, `:answer-call`,
   `:fail-call`, `:cancel-call`, `:settle-calls`) with O(|pending|) deadlock cycle detection,
   Malli result-schema validation, and cascading descendant cancellation."
  (:require [clojure.core.async.flow :as flow]
            [datahike.api :as d]
            [seon.schema :as schema]
            [seon.store :as store]))

(defn pull-call [db call-id]
  (when (and db call-id (contains? (:schema db) :seon.agent.call/id)
             (first (d/datoms db :avet :seon.agent.call/id call-id)))
    (d/pull db '[:seon.agent.call/id :seon.agent.call/caller :seon.agent.call/callee
                 :seon.agent.call/parent-call :seon.agent.call/message-id
                 :seon.agent.call/prompt :seon.agent.call/result-schema
                 :seon.agent.call/status :seon.agent.call/result :seon.agent.call/error
                 :seon.agent.call/created-tx :seon.agent.call/settled-tx :seon.agent.call/delivered?]
            [:seon.agent.call/id call-id])))

(defn call-cycle-chain
  "Checks in O(|pending-calls|) whether adding edge `caller -> callee` forms a deadlock cycle.
   Returns the cycle path `[callee ... caller callee]` if cyclic, else nil."
  [db caller callee]
  (cond
    (= caller callee) [caller callee]
    (not (contains? (:schema db) :seon.agent.call/status)) nil
    :else
    (let [pending-edges
          (reduce (fn [acc dt]
                    (let [c (d/pull db '[:seon.agent.call/caller :seon.agent.call/callee] (:e dt))]
                      (if (and (:seon.agent.call/caller c) (:seon.agent.call/callee c))
                        (update acc (:seon.agent.call/caller c) (fnil conj #{}) (:seon.agent.call/callee c))
                        acc)))
                  {}
                  (d/datoms db :avet :seon.agent.call/status :pending))]
      (loop [stack [[callee [callee]]]
             seen #{callee}]
        (when (seq stack)
          (let [[node path] (peek stack)
                rest-stack (pop stack)
                nbrs (get pending-edges node)]
            (if (contains? nbrs caller)
              (conj path caller)
              (let [fresh (remove seen nbrs)]
                (recur (into rest-stack (map (fn [n] [n (conj path n)]) fresh))
                       (into seen fresh))))))))))

(defn pending-descendant-calls
  "Returns `[root-id ...]` of `root-call-id` plus all `:pending` descendant calls via `:parent-call`."
  [db root-call-id]
  (loop [queue [root-call-id]
         seen #{root-call-id}
         acc []]
    (if (empty? queue)
      acc
      (let [cur (first queue)
            c (pull-call db cur)
            kids (if (contains? (:schema db) :seon.agent.call/parent-call)
                   (keep (fn [dt]
                           (let [k (d/pull db '[:seon.agent.call/id :seon.agent.call/status] (:e dt))]
                             (when (= :pending (:seon.agent.call/status k))
                               (:seon.agent.call/id k))))
                         (d/datoms db :avet :seon.agent.call/parent-call cur))
                   [])
            fresh (remove seen kids)]
        (recur (into (vec (rest queue)) fresh)
               (into seen fresh)
               (cond-> acc (= :pending (:seon.agent.call/status c)) (conj cur)))))))

(defn infer-ns-scope [db callee explicit-scope]
  (or explicit-scope
      (when (and (keyword? callee) (= "ns" (namespace callee)))
        (let [cand (symbol (name callee))]
          (when (first (d/datoms db :avet :seon.code.ns/name cand))
            cand)))))

(defn format-call-request-body [call-id caller prompt result-schema]
  (str "Structured call from " caller " (call-id: " (pr-str call-id) ")\n"
       (when result-schema
         (str "Required result schema: " (pr-str result-schema) "\n"))
       "\n" (str prompt) "\n\n"
       "When finished, settle this call via:\n"
       "  (user/answer! " (pr-str call-id) " <value>)\n"
       "or if out of scope / blocked:\n"
       "  (user/fail! " (pr-str call-id) " {:class :out-of-scope :message \"...\"})"))

(defn call-summary-map [c]
  (cond-> {:call-id (:seon.agent.call/id c)
           :callee (:seon.agent.call/callee c)
           :status (:seon.agent.call/status c)
           :ok? (= :resolved (:seon.agent.call/status c))}
    (contains? c :seon.agent.call/result) (assoc :result (:seon.agent.call/result c))
    (contains? c :seon.agent.call/error)  (assoc :error (:seon.agent.call/error c))))

(defn format-call-results-body [calls]
  (let [by-id (into {} (map (fn [c] [(:seon.agent.call/id c) (call-summary-map c)])) calls)]
    (str "Resolved call results (" (count calls) "):\n"
         (pr-str by-id))))

(defn handle-call-op!
  "Dispatches `:create-call`, `:await-calls`, `:answer-call`, `:fail-call`, `:cancel-call`,
   and `:settle-calls` for `:transactor`. Returns `[st' outputs]`."
  {:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]] :map :any :map] [:tuple :map :any]]}
  [st msg prev-cid {:keys [reply! db-notice focus-tx]}]
  (let [{:keys [conn]} st]
    (case (:op msg)
      :create-call
      (let [{:keys [caller callee prompt parent-call result-schema await? scope role goal auto-merge?]} msg
            db0 @conn
            cycle-path (call-cycle-chain db0 caller callee)]
        (cond
          cycle-path
          (do (reply! msg {:ok? false
                           :error {:class :cycle
                                   :caller caller
                                   :callee callee
                                   :chain cycle-path
                                   :message (str "Deadlock cycle rejected: " (pr-str cycle-path))}})
              [st nil])

          :else
          (let [callee-exists? (boolean (first (d/datoms db0 :avet :seon.agent/id callee)))
                inferred-scope (infer-ns-scope db0 callee scope)]
            (if (and (not callee-exists?) (nil? inferred-scope))
              (do (reply! msg {:ok? false
                               :error {:class :unknown-callee
                                       :callee callee
                                       :message (str "Callee " callee " does not exist. Pass {:scope 'ns.name} or use :ns/<existing-ns> to lazy-spawn a specialist.")}})
                  [st nil])
              (let [call-id (random-uuid)
                    mid (random-uuid)
                    mseq (inc (:msg-seq st))
                    spawn-branch (when-not callee-exists?
                                   (keyword "branch" (str (or (namespace callee) "agent") "." (name callee))))
                    ns-tx (when (and (not callee-exists?) inferred-scope)
                            (focus-tx inferred-scope))
                    spawn-ent (when-not callee-exists?
                    (cond-> {:seon.agent/id callee
                             :seon.agent/parent caller
                             :seon.agent/branch spawn-branch
                             :seon.agent/scope inferred-scope
                             :seon.agent/focus (str "ns/" inferred-scope)
                             :seon.agent/role (or role :worker)
                             :seon.agent/goal (or goal (str "Subsystem owner for namespace " inferred-scope " and its children. Research questions deeply (delegating to child :ns/<sub-ns> specialists when helpful), critique weak answers, edit/test code on your branch when asked to fix or refactor, and settle incoming calls via (user/answer! call-id val)."))
                             :seon.agent/auto-merge? (if (some? auto-merge?) (boolean auto-merge?) true)}
                      (first (d/datoms db0 :avet :seon.agent/id :example/recursive-fanout))
                      (assoc :seon.agent/examples #{:example/recursive-fanout})))
                    call-ent (cond-> {:seon.agent.call/id call-id
                                      :seon.agent.call/caller caller
                                      :seon.agent.call/callee callee
                                      :seon.agent.call/message-id mid
                                      :seon.agent.call/prompt (str (or prompt ""))
                                      :seon.agent.call/status :pending
                                      :seon.agent.call/created-tx (long (:max-tx db0))
                                      :seon.agent.call/delivered? false}
                               parent-call (assoc :seon.agent.call/parent-call parent-call)
                               result-schema (assoc :seon.agent.call/result-schema result-schema))
                    msg-ent {:seon.agent.message/id mid
                             :seon.agent.message/seq mseq
                             :seon.agent.message/from caller
                             :seon.agent.message/to callee
                             :seon.agent.message/type :call
                             :seon.agent.message/body (format-call-request-body call-id caller prompt result-schema)}
                    await-ent (when (and await? caller (not= caller :user))
                                {:seon.agent/id caller :seon.agent/awaiting call-id})
                    tx-data (vec (concat ns-tx (remove nil? [spawn-ent call-ent msg-ent await-ent])))
                    r (d/transact conn {:tx-data tx-data})
                    _ (when spawn-branch (store/branch! conn :main spawn-branch))
                    db (:db-after r)
                    cid (d/commit-id db)]
                (reply! msg {:ok? true
                             :call-id call-id
                             :caller caller
                             :callee callee
                             :status :pending
                             :spawned? (boolean spawn-ent)
                             :awaiting? (boolean await-ent)
                             :tx (:max-tx db)})
                [(-> st (assoc :last-commit cid :last-tx (:max-tx db) :msg-seq mseq) (update :writes inc))
                 {:db [(db-notice prev-cid db (:tx-data r) [:create-call caller callee])]
                  ::flow/report [(str "[:transactor] call " call-id " " caller " -> " callee
                                      (when spawn-ent (str " (lazy-spawned " inferred-scope ")"))
                                      (when await-ent " [awaiting]"))]}])))))

      :await-calls
      (let [{:keys [caller call-ids]} msg
            ids (vec (distinct call-ids))
            db0 @conn
            pulled (mapv #(vector % (pull-call db0 %)) ids)
            missing (keep (fn [[id c]] (when-not c id)) pulled)]
        (cond
          (empty? ids)
          (do (reply! msg {:ok? true :settled? true :results {}})
              [st nil])

          (seq missing)
          (do (reply! msg {:ok? false :error {:class :unknown-call :missing (vec missing)}})
              [st nil])

          :else
          (let [calls (mapv second pulled)
                pending (filterv #(= :pending (:seon.agent.call/status %)) calls)]
            (if (empty? pending)
              (let [deliver-tx (mapv (fn [c] {:seon.agent.call/id (:seon.agent.call/id c)
                                              :seon.agent.call/delivered? true})
                                     calls)
                    r (d/transact conn {:tx-data deliver-tx})
                    db (:db-after r)
                    cid (d/commit-id db)
                    res-map (into {} (map (fn [c] [(:seon.agent.call/id c) (call-summary-map c)])) calls)]
                (reply! msg {:ok? true :settled? true :results res-map :tx (:max-tx db)})
                [(-> st (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
                 {:db [(db-notice prev-cid db (:tx-data r) [:await-settled caller])]}])
              (let [tx-data (mapv (fn [id] [:db/add [:seon.agent/id caller] :seon.agent/awaiting id]) ids)
                    r (d/transact conn {:tx-data tx-data})
                    db (:db-after r)
                    cid (d/commit-id db)]
                (reply! msg {:ok? true
                             :settled? false
                             :awaiting (mapv :seon.agent.call/id pending)
                             :tx (:max-tx db)
                             :note "Suspended cleanly in Datahike (0 blocked threads); will auto-resume when all awaited calls settle."})
                [(-> st (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
                 {:db [(db-notice prev-cid db (:tx-data r) [:await-calls caller])]}])))))

      :answer-call
      (let [{:keys [call-id result]} msg
            db0 @conn
            c (pull-call db0 call-id)]
        (cond
          (nil? c)
          (do (reply! msg {:ok? false :error {:class :unknown-call :call-id call-id}})
              [st nil])

          (not= :pending (:seon.agent.call/status c))
          (do (reply! msg {:ok? false :error {:class :already-settled :call-id call-id :status (:seon.agent.call/status c)}})
              [st nil])

          :else
          (let [rsch (:seon.agent.call/result-schema c)
                check (when rsch (schema/validate-against db0 rsch result))]
            (if (and check (not (:ok? check)))
              (do (reply! msg {:ok? false
                               :error {:class :schema-mismatch
                                       :call-id call-id
                                       :schema rsch
                                       :explain (:explain check)
                                       :message (str "Result failed Malli schema " rsch ": " (pr-str (:explain check)))}})
                  [st nil])
              (let [r (d/transact conn {:tx-data [{:seon.agent.call/id call-id
                                                   :seon.agent.call/status :resolved
                                                   :seon.agent.call/result result
                                                   :seon.agent.call/settled-tx (long (:max-tx db0))}]})
                    db (:db-after r)
                    cid (d/commit-id db)]
                (reply! msg {:ok? true :call-id call-id :status :resolved :tx (:max-tx db)})
                [(-> st (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
                 {:db [(db-notice prev-cid db (:tx-data r) [:answer-call call-id])]
                  ::flow/report [(str "[:transactor] call " call-id " RESOLVED by " (:seon.agent.call/callee c))]}])))))

      :fail-call
      (let [{:keys [call-id error]} msg
            db0 @conn
            c (pull-call db0 call-id)]
        (cond
          (nil? c)
          (do (reply! msg {:ok? false :error {:class :unknown-call :call-id call-id}})
              [st nil])

          (not= :pending (:seon.agent.call/status c))
          (do (reply! msg {:ok? false :error {:class :already-settled :call-id call-id :status (:seon.agent.call/status c)}})
              [st nil])

          :else
          (let [err-val (if (map? error) error {:class :failed :message (str error)})
                r (d/transact conn {:tx-data [{:seon.agent.call/id call-id
                                               :seon.agent.call/status :failed
                                               :seon.agent.call/error err-val
                                               :seon.agent.call/settled-tx (long (:max-tx db0))}]})
                db (:db-after r)
                cid (d/commit-id db)]
            (reply! msg {:ok? true :call-id call-id :status :failed :tx (:max-tx db)})
            [(-> st (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
             {:db [(db-notice prev-cid db (:tx-data r) [:fail-call call-id])]
              ::flow/report [(str "[:transactor] call " call-id " FAILED (" (:class err-val) ")")]}])))

      :cancel-call
      (let [{:keys [call-id reason]} msg
            db0 @conn
            targets (pending-descendant-calls db0 call-id)]
        (if (empty? targets)
          (do (reply! msg {:ok? true :cancelled []})
              [st nil])
          (let [err-val {:class :cancelled :reason (str (or reason "cancelled"))}
                tx-data (mapv (fn [id]
                                {:seon.agent.call/id id
                                 :seon.agent.call/status :cancelled
                                 :seon.agent.call/error err-val
                                 :seon.agent.call/settled-tx (long (:max-tx db0))})
                              targets)
                r (d/transact conn {:tx-data tx-data})
                db (:db-after r)
                cid (d/commit-id db)]
            (reply! msg {:ok? true :cancelled targets :tx (:max-tx db)})
            [(-> st (assoc :last-commit cid :last-tx (:max-tx db)) (update :writes inc))
             {:db [(db-notice prev-cid db (:tx-data r) [:cancel-call call-id])]
              ::flow/report [(str "[:transactor] cancelled " (count targets) " call(s) under " call-id)]}])))

      :settle-calls
      (let [{:keys [caller call-ids clear-awaiting?]} msg
            db0 @conn
            calls (vec (keep (fn [id]
                               (let [c (pull-call db0 id)]
                                 (when (and c (not= :pending (:seon.agent.call/status c)) (not (:seon.agent.call/delivered? c)))
                                   c)))
                             (distinct call-ids)))]
        (if (empty? calls)
          (do (reply! msg {:ok? true :delivered 0})
              [st nil])
          (let [mseq (inc (:msg-seq st))
                mid (random-uuid)
                mark-ops (mapv (fn [c] {:seon.agent.call/id (:seon.agent.call/id c)
                                        :seon.agent.call/delivered? true})
                               calls)
                cur-awaiting (when clear-awaiting?
                               (set (:seon.agent/awaiting (d/pull db0 '[:seon.agent/awaiting] [:seon.agent/id caller]))))
                retract-ops (when clear-awaiting?
                              (vec (for [c calls
                                         :let [cid (:seon.agent.call/id c)]
                                         :when (contains? cur-awaiting cid)]
                                     [:db/retract [:seon.agent/id caller] :seon.agent/awaiting cid])))
                msg-ent {:seon.agent.message/id mid
                         :seon.agent.message/seq mseq
                         :seon.agent.message/from :seon/calls
                         :seon.agent.message/to caller
                         :seon.agent.message/type :call-result
                         :seon.agent.message/body (format-call-results-body calls)}
                tx-data (vec (concat retract-ops mark-ops [msg-ent]))
                r (d/transact conn {:tx-data tx-data})
                db (:db-after r)
                cid (d/commit-id db)]
            (reply! msg {:ok? true :delivered (count calls) :message-id mid :tx (:max-tx db)})
            [(-> st (assoc :last-commit cid :last-tx (:max-tx db) :msg-seq mseq) (update :writes inc))
             {:db [(db-notice prev-cid db (:tx-data r) [:settle-calls caller])]
              ::flow/report [(str "[:transactor] delivered " (count calls) " call result(s) -> " caller)]}]))))))
