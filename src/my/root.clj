(ns ^{:seon.ns/context-relevant? true} my.root
  "Root's tools: the laws root applies, the open merge requests, merging one,
  closing one without merging, and the task ends agents report. Call
  preparation supplies root's world.

  ## Example

  ```clojure
  (my.root/merge-requests)
  ```"
  (:require [seon.cluster.message :as message]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.context :as context]
            [seon.db :as db]
            [seon.merge :as merge]
            [seon.error.refusal :as refusal]
            [seon.task :as task])
  (:refer-clojure :exclude [run!]))

(def ^:private laws
  ["Read a merge request's diff before merging it (my.program/diff on its source branch): until criteria for automatic merges are written, root reviews every merge of code."
   "A task chain runs in order: model the data and specify the interface, then write the proofs, then implement the body; each step is its own task with its own proofs."
   "A merge request is an assignment: (my.root/merge-requests) lists the open ones. Merge one with (my.root/merge! {:seon.task/assignment key}); the branch is deleted once merged, closing the agent."
   "Close an assignment that must not merge with (my.root/close! {:seon.task/assignment key :seon.task/reason why}); it deletes the source branch, closing the agent."
   "An agent's end reaches you as a message; (my.root/ended) shows each with its proofs, calls and merge request."
   "A chain attempt that ends other than done bails to you: its problem task names it, the attempt is :closed and its task :held on the chain branch. Change the task there and run it again with (my.root/run! {:seon.task/assigned handle})."
   "A chain whose final merge its gate refused (a conflict, a red test, a file that does not analyze) keeps its merge branch, main's latest head with the chain's changes on top, and holds a merge step after its last task: (my.root/merge-steps) lists each with its refusal. Nothing runs it by itself; run it with (my.root/run! {:seon.task/assigned handle}), and when it is done my pass merges its branch, judging main's latest head again."
   "Merge main forward into a chain branch, as git merge main into a feature branch, at each restart batch and before a chain's final merge: (my.root/merge-main! {:seon.chain/branch b}) moves the declarations main changed since the branch's merge base and the branch did not, as one merge commit with main's head as a parent, which is then its merge base, so the branch interprets only its own changes; a declaration both changed is answered in :seon.program/conflict and stays the branch's for its agent to resolve; tasks, attempts and config never move, so the chain's final merge carries its own changes to them since its fork point and keeps main's."
   "A branch reads its own config and a merge never carries it: apply a changed dial to a chain branch with bin/seon config apply [NAME] PATH --branch B (or --chains for every chain branch a running graph holds); the agent's next dial read there sees it and main's is unchanged."])

(defn instructions
  "The laws root applies to merge requests and agents, as data.

  ## Example

  ```clojure
  (my.root/instructions)
  ```"
  {:malli/schema [:=> [:cat] :my.root/instructions]}
  []
  {:my.root/laws laws})

(defn- offered
  "Open assignment `request` with its merge and its close offered."
  {:malli/schema [:=> [:cat :my.root/open-assignment] :my.root/merge-request]}
  [request]
  (assoc request :seon.render/operations
         [(list 'my.root/merge! {:seon.task/assignment (:seon.task/assignment request)})
          (list 'my.root/close! {:seon.task/assignment (:seon.task/assignment request)
                                 :seon.task/reason "Why this change must not merge."})]))

(defn- with-branch-end
  "Open assignment `row` with the end its branch head holds when main holds none: an agent's requested merge
  waits there for my merge."
  {:malli/schema [:=> [:cat :seon.db/database-value :my.root/open-assignment] :my.root/open-assignment]}
  [database {branch :seon.task/branch key :seon.task/assignment :as row}]
  (if (:seon.task.attempt/end row)
    row
    (if-let [head (db/branch-database database branch)]
      (try (let [end (db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k] [?a :seon.task.attempt/end ?e]]
                           head key)]
             (cond-> row (keyword? end) (assoc :seon.task.attempt/end end)))
           (finally (source/release! [head])))
      row)))

(defn- chain-requests
  "Every chain attempt that has ended and holds no outcome on its chain branch (`seon.task/chain-attempts`), read
  through `store`, as an open assignment on that branch: a running attempt is not a merge request."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/store]
                  [:or [:vector :my.root/open-assignment] :seon.db/invalid-read-error]]}
  [database store]
  (let [attempts (task/chain-attempts database store)]
    (if (:seon.error/at attempts)
      attempts
      (into [] (comp (filter :seon.task.attempt/end) (remove :seon.task/outcome)
                     (map #(-> % (assoc :seon.task/branch (:seon.chain/branch %)) (dissoc :seon.chain/branch))))
            attempts))))

(defn merge-requests
  "The open merge requests on my cluster: every assignment with no outcome yet (`seon.task/close!`,
  `seon.merge/merge-tx`), on main and every ended chain attempt on its chain branch, with its task, branch
  and its end (main's, else its branch head's), each offering its merge and its close.

  ## Example

  ```clojure
  (my.root/merge-requests)
  ```"
  {:malli/schema [:=> [:cat :my.root/merge-request-read] [:or :my.root/merge-requests :seon.db/error-result]]}
  [{database :seon.db/db store :seon.store/store}]
  (let [found (db/q '[:find [(pull ?a [:seon.task/assignment :seon.task/assigned :seon.task/branch :seon.task.attempt/end]) ...]
                      :where [?a :seon.task/assignment] [?a :seon.task/branch] (not [?a :seon.task/outcome])]
                    database)
        chained (if store (when-not (db/read-refusal? found) (chain-requests database store)) [])]
    (cond (db/read-refusal? found) found
          (:seon.error/at chained) chained
          :else (into (mapv #(offered (with-branch-end database (dissoc % :db/id))) (sort-by :seon.task/assignment found))
                      (map offered) chained))))

(defn- open-assignment
  "Assignment `key` on my cluster when it records no outcome, else the refusal naming it."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/assignment :qualified-symbol]
                  [:or :map :seon.error/value]]}
  [database key operation]
  (let [row (db/pull database [:seon.task/branch :seon.task/outcome] [:seon.task/assignment key])]
    (if (and (:seon.task/branch row) (not (:seon.task/outcome row)))
      row
      (refusal/diagnostic (java.util.Date.) :seon.program/write operation
                          {:seon.error/message (str "Assignment " key " is not an open merge request.")
                           :seon.error/member :seon.task/assignment :seon.error/offending key
                           :seon.render/operations ['(my.root/merge-requests)]}))))

(defn merge!
  "Merge the open assignment `:seon.task/assignment` into my cluster (`seon.merge/merge!`): its branch's facts,
  and, when its agent requested the merge, its declarations, gated by the proofs and the tests reaching the
  change on a fresh merge branch of the head I hold, the files proven against the cluster's checkout
  (`:seon.config.fs/working-root`). The assignment closes `:merged` in the merge's transaction; the next
  lifecycle pass publishes its declarations into the checkout in one git commit (`seon.merge/publish!`), then
  deletes its branch. Answers the merge, or the first refusal as data; a red gate leaves the assignment open.

  ## Example

  ```clojure
  (my.root/merge! {:seon.task/assignment \"0123456789ab\"})
  ```"
  {:malli/schema [:=> [:cat :my.root/merge-request-request]
                  [:or :seon.merge/merged :seon.source/conflict-error :seon.error/value]]}
  [{context :my.program/context key :seon.task/assignment}]
  (let [database (db/db (:seon.db/connection context))
        open (open-assignment database key 'my.root/merge!)
        checkout (when-not (:seon.error/at open) (some->> (:seon.cluster/name context) (merge/checkout database)))]
    (if (:seon.error/at open)
      open
      (let [merged (if (string? checkout) (merge/merge! context key checkout) (merge/merge! context key))]
        ;; a hand merge holds no merge step: the merge branch a refusal kept is retired (MERGE-STEP)
        (when-let [kept (and (:seon.error/at merged) (:seon.source/candidate merged))]
          (registry/retire-branch! {:seon.store/store (:seon.store/store context) :seon.store/branch kept}))
        merged))))

(defn close!
  "Close the open assignment `:seon.task/assignment` without merging it, saying why (`:seon.task/reason`): it
  records `:closed` and deletes the source branch, closing its agent (`seon.task/close!`). A chain attempt closes
  on its chain branch with its task held (`seon.task/hold!`); the branch and chain fact stay for root. Answers the
  closed assignment.

  ## Example

  ```clojure
  (my.root/close! {:seon.task/assignment \"0123456789ab\" :seon.task/reason \"The change belongs in seon.db.\"})
  ```"
  {:malli/schema [:=> [:cat :my.root/close-request] [:or :seon.task/closed :seon.error/value]]}
  [{context :my.program/context key :seon.task/assignment reason :seon.task/reason}]
  (let [connection (:seon.db/connection context)
        database (db/db connection)
        chained (when-let [store (:seon.store/store context)]
                  (some #(when (and (= key (:seon.task/assignment %)) (not (:seon.task/outcome %))) %)
                        (let [attempts (task/chain-attempts database store)] (if (:seon.error/at attempts) [] attempts))))
        open (when-not chained (open-assignment database key 'my.root/close!))]
    (cond
      chained (task/hold! (select-keys context [:seon.store/store]) (:seon.chain/branch chained) key reason)
      (:seon.error/at open) open
      :else (task/close! (cond-> (select-keys context [:seon.db/connection :seon.store/store :seon.agent/routing])
                           (:seon.env/environment context) (assoc :seon.env/environment (:seon.env/environment context)))
                         key :closed reason))))

(defn run!
  "Run held chain task `:seon.task/assigned` again (TASK-CHAIN §2.7, `seon.task/run-held!`): one transaction on its
  chain branch changing `:held` to `:running` once its runnable view validates (else the `:unrunnable` refusal naming
  the offending keys, writing nothing), which my route on that branch hears; my next pass starts its next
  attempt there with a new agent. Answers the task, its branch, its execution and the number and key of the attempt my next pass
  starts (`seon.task/open-attempt?`).

  ## Example

  ```clojure
  (my.root/run! {:seon.task/assigned \"0123456789ab\"})
  ```"
  {:malli/schema [:=> [:cat :my.root/run-request]
                  [:or [:map [:seon.task/assigned :seon.task/handle] [:seon.chain/branch :seon.chain/branch]
                        [:seon.task/execution :seon.task/execution]
                        [:seon.task.attempt/number :seon.task.attempt/number] [:seon.task/assignment :seon.task/assignment]]
                   :seon.error/value]]}
  [{context :my.program/context handle :seon.task/assigned}]
  (task/run-held! (select-keys context [:seon.db/connection :seon.store/store]) handle))

(defn merge-steps
  "The held merge steps on my cluster's chains (MERGE-STEP): each chain task naming its own branch, a refused final
  merge's kept merge branch, that is `:held`, with its chain branch, its refusal and refused tests, offering its run.

  ## Example

  ```clojure
  (my.root/merge-steps)
  ```"
  {:malli/schema [:=> [:cat :my.root/merge-request-read]
                  [:or [:vector [:map [:seon.task/id :seon.task/id] [:seon.task/branch :seon.task/branch]
                                 [:seon.chain/branch :seon.chain/branch]
                                 [:seon.task/refusal {:optional true} :seon.task/refusal]
                                 [:seon.task/refused-tests {:optional true} :seon.task/refused-tests]
                                 [:seon.render/operations :seon.render/operations]]]
                   :seon.error/value]]}
  [{database :seon.db/db store :seon.store/store}]
  (let [chains (db/q '[:find ?b ?h :where [?c :seon.chain/branch ?b] [?c :seon.chain/first ?h]] database)]
    (if (db/read-refusal? chains)
      chains
      (into [] (mapcat (fn [[branch first-handle]]
                         (when (contains? (registry/branches store) branch)
                           (registry/on-branch store branch
                                      (fn [connection]
                                        (let [head (db/db connection)]
                                          (into [] (comp (map #(db/pull head [:seon.task/id :seon.task/branch :seon.task/execution
                                                                              :seon.task/refusal :seon.task/refused-tests]
                                                                        [:seon.task/id %]))
                                                         (filter #(and (:seon.task/branch %) (= :held (:seon.task/execution %))))
                                                         (map #(-> (dissoc % :db/id :seon.task/execution)
                                                                   (assoc :seon.chain/branch branch
                                                                          :seon.render/operations
                                                                          [(list 'my.root/run! {:seon.task/assigned (:seon.task/id %)})]))))
                                                (task/chain-handles head first-handle))))))))
            (sort chains)))))

(defn merge-main!
  "Merge my cluster's main forward into chain branch `:seon.chain/branch` (`seon.merge/merge-main!`): the program
  declarations main changed since the branch's merge base and the branch did not move onto the branch, and its
  merge base advances to main's head. A declaration both changed stays the branch's and is answered in
  `:seon.program/conflict`. Answers what moved, the conflicts and main's commit, or the refusal as data.

  ## Example

  ```clojure
  (my.root/merge-main! {:seon.chain/branch :chain-f1f6dc8e6652})
  ```"
  {:malli/schema [:=> [:cat [:map [:my.program/context :my.program/context] [:seon.chain/branch :seon.chain/branch]]]
                  [:or [:map [:seon.store/branch :seon.chain/branch] [:seon.source/commit-id :seon.source/commit-id]
                        [:seon.program/identities :seon.program/identity-set]
                        [:seon.program/conflict :seon.program/conflict]
                        [:seon.program/conflict-digests :seon.program/conflict-digests]]
                   :seon.error/value]]}
  [{context :my.program/context branch :seon.chain/branch}]
  (merge/merge-main! context branch))

(defn ended
  "The task ends in my unread inbox, each as the value its end message stands
  for (`seon.context/ended`): the task, how it ended, its proofs, the agent's
  calls and errors, and its merge request.

  ## Example

  ```clojure
  (my.root/ended)
  ```"
  {:malli/schema [:=> [:cat :seon.agent/db-request] [:or :my.root/ended :seon.error/value]]}
  [{database :seon.db/db agent-id :seon.agent/id}]
  (let [items (message/inbox database agent-id)]
    (if (:seon.error/at items)
      items
      (let [ends (mapv #(context/ended database %) (filter :seon.task/end items))]
        (or (some #(when (:seon.error/at %) %) ends) ends)))))
