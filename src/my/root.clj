(ns ^{:seon.ns/context-relevant? true} my.root
  "Root's tools: the laws root applies, the open merge requests, merging one,
  closing one without merging, and the task ends agents report. Call
  preparation supplies root's world.

  Example:
  (my.root/merge-requests)"
  (:require [seon.cluster.message :as message]
            [seon.cluster.source :as source]
            [seon.context :as context]
            [seon.db :as db]
            [seon.merge :as merge]
            [seon.error.refusal :as refusal]
            [seon.task :as task]))

(def ^:private laws
  ["Read a merge request's diff before merging it (my.program/diff on its source branch): until criteria for automatic merges are written, root reviews every merge of code."
   "A task chain runs in order: model the data and specify the interface, then write the proofs, then implement the body; each step is its own task with its own proofs."
   "A merge request is an assignment: (my.root/merge-requests) lists the open ones. Merge one with (my.root/merge! {:seon.task/assignment key}); the branch is deleted once merged, closing the agent."
   "Close an assignment that must not merge with (my.root/close! {:seon.task/assignment key :seon.task/reason why}); it deletes the source branch, closing the agent."
   "An agent's end reaches you as a message; (my.root/ended) shows each with its proofs, calls and merge request."])

(defn instructions
  "The laws root applies to merge requests and agents, as data.

  Example:
  (my.root/instructions)"
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

(defn merge-requests
  "The open merge requests on my cluster: every assignment with no outcome yet (`seon.task/close!`,
  `seon.merge/merge-tx`), with its task, branch, merge base and its end (main's, else its branch head's), each
  offering its merge and its close.

  Example:
  (my.root/merge-requests)"
  {:malli/schema [:=> [:cat :my.root/merge-request-read] [:or :my.root/merge-requests :seon.db/error-result]]}
  [{database :seon.db/db}]
  (let [found (db/q '[:find [(pull ?a [:seon.task/assignment :seon.task/assigned :seon.task/branch :seon.task/merge-base
                                       :seon.task.attempt/end]) ...]
                      :where [?a :seon.task/assignment] [?a :seon.task/branch] (not [?a :seon.task/outcome])]
                    database)]
    (if (db/read-refusal? found)
      found
      (mapv #(offered (with-branch-end database (dissoc % :db/id))) (sort-by :seon.task/assignment found)))))

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

  Example:
  (my.root/merge! {:seon.task/assignment \"0123456789ab\"})"
  {:malli/schema [:=> [:cat :my.root/merge-request-request]
                  [:or :seon.merge/merged :seon.source/conflict-error :seon.error/value]]}
  [{context :my.program/context key :seon.task/assignment}]
  (let [database (db/db (:seon.db/connection context))
        open (open-assignment database key 'my.root/merge!)
        checkout (when-not (:seon.error/at open) (some->> (:seon.cluster/name context) (merge/checkout database)))]
    (if (:seon.error/at open)
      open
      (if (string? checkout) (merge/merge! context key checkout) (merge/merge! context key)))))

(defn close!
  "Close the open assignment `:seon.task/assignment` without merging it, saying why (`:seon.task/reason`): it
  records `:closed` and deletes the source branch, closing its agent (`seon.task/close!`). Answers the closed
  assignment.

  Example:
  (my.root/close! {:seon.task/assignment \"0123456789ab\" :seon.task/reason \"The change belongs in seon.db.\"})"
  {:malli/schema [:=> [:cat :my.root/close-request] [:or :seon.task/closed :seon.error/value]]}
  [{context :my.program/context key :seon.task/assignment reason :seon.task/reason}]
  (let [connection (:seon.db/connection context)
        open (open-assignment (db/db connection) key 'my.root/close!)]
    (if (:seon.error/at open)
      open
      (task/close! (cond-> (select-keys context [:seon.db/connection :seon.store/store :seon.agent/routing])
                     (:seon.env/environment context) (assoc :seon.env/environment (:seon.env/environment context)))
                   key :closed reason))))

(defn ended
  "The task ends in my unread inbox, each as the value its end message stands
  for (`seon.context/ended`): the task, how it ended, its proofs, the agent's
  calls and errors, and its merge request.

  Example:
  (my.root/ended)"
  {:malli/schema [:=> [:cat :seon.agent/db-request] [:or :my.root/ended :seon.error/value]]}
  [{database :seon.db/db agent-id :seon.agent/id}]
  (let [items (message/inbox database agent-id)]
    (if (:seon.error/at items)
      items
      (let [ends (mapv #(context/ended database %) (filter :seon.task/end items))]
        (or (some #(when (:seon.error/at %) %) ends) ends)))))
