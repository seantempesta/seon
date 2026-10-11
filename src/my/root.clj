(ns ^{:seon.ns/context-relevant? true} my.root
  "Root's tools: the laws root applies, the open merge requests, merging one,
  closing one without merging, and the task ends agents report. Call
  preparation supplies root's world.

  Example:
  (my.root/merge-requests)"
  (:require [seon.cluster.message :as message]
            [seon.cluster.source :as source]
            [seon.config :as config]
            [seon.context :as context]
            [seon.db :as db]
            [seon.error.refusal :as refusal]
            [seon.task :as task]))

(def ^:private laws
  ["Read a merge request's diff before merging it (my.program/diff on its source branch): until criteria for automatic merges are written, root reviews every merge."
   "A task chain runs in order: model the data and specify the interface, then write the proofs, then implement the body; each step is its own task with its own proofs."
   "Merge with (my.root/merge! {:seon.task/merge id}); it deletes the merge branch and the source branch, closing the agent."
   "Close a merge request that must not merge with (my.root/close! {:seon.task/merge id :seon.task/reason why}); it deletes the source branch, closing the agent."
   "An agent's end reaches you as a message; (my.root/ended) shows each with its proofs, calls and merge request."])

(defn instructions
  "The laws root applies to merge requests and agents, as data.

  Example:
  (my.root/instructions)"
  {:malli/schema [:=> [:cat] :my.root/instructions]}
  []
  {:my.root/laws laws})

(def ^:private request-selector
  [:seon.task/merge-request :seon.task/merge :seon.task/branch :seon.task/commit
   :seon.task/proved :seon.task/run-id :seon.task/program])

(defn- offered
  "`request` with the merge and the close of its merge task offered."
  {:malli/schema [:=> [:cat :seon.task/merge-request-view] :my.root/merge-request]}
  [request]
  (assoc request :seon.render/operations
         [(list 'my.root/merge! {:seon.task/merge (:seon.task/merge request)})
          (list 'my.root/close! {:seon.task/merge (:seon.task/merge request)
                                 :seon.task/reason "Why this change must not merge."})]))

(defn merge-requests
  "The open merge requests on my cluster: every merge request whose merge task
  records no outcome yet, with its branch, commit, proofs and run, each
  offering its merge and its close.

  Example:
  (my.root/merge-requests)"
  {:malli/schema [:=> [:cat :my.root/merge-request-read] [:or :my.root/merge-requests :seon.db/error-result]]}
  [{database :seon.db/db}]
  (let [found (db/q '[:find [(pull ?r ?selector) ...]
                      :in $ ?selector
                      :where [?r :seon.task/merge-request] [?r :seon.task/merge ?handle] [?t :seon.task/id ?handle]
                      (not [?t :seon.task/outcome])]
                    database request-selector)]
    ;; a pulled cardinality-many value is a vector; :seon.task/proved is a set
    (if (db/read-refusal? found)
      found
      (mapv #(offered (update % :seon.task/proved set)) (sort-by :seon.task/merge-request found)))))

(defn- open-merge-request
  "The open merge request of merge task `merge-task` my cluster records last, or the refusal naming it."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/merge] [:or :seon.task/merge-request-view :seon.error/value]]}
  [database merge-task]
  (let [found (db/q '[:find ?tx (pull ?r ?selector)
                      :in $ ?selector ?handle
                      :where [?r :seon.task/merge ?handle] [?r :seon.task/merge-request _ ?tx]
                      [?t :seon.task/id ?handle] (not [?t :seon.task/outcome])]
                    database request-selector merge-task)]
    (cond (db/read-refusal? found) found
          (empty? found)
          (refusal/diagnostic (java.util.Date.) :seon.program/write 'my.root/merge!
                              {:seon.error/message (str "Merge task " merge-task " has no open merge request.")
                               :seon.error/member :seon.task/merge :seon.error/offending merge-task
                               :seon.render/operations ['(my.root/merge-requests)]})
          :else (update (second (last (sort-by first found))) :seon.task/proved set))))

(defn merge!
  "Merge the open merge request of merge task `:seon.task/merge` into my
  cluster (`seon.cluster.source/land!`): its proofs and the tests reaching
  the change run on a fresh merge branch of the head I hold, the tested
  program merges, the merge request closes `:merged` (`seon.task/close!`,
  deleting the merge branch and the source branch), and the declarations are
  written back into the checkout `:seon.fn/root` (the cluster's
  `:seon.config.fs/working-root` when absent) in one git commit. Answers the
  landing with each step's milliseconds, or the first refusal as data; a red
  gate leaves the merge request open.

  Example:
  (my.root/merge! {:seon.task/merge \"0123456789ab\"})"
  {:malli/schema [:=> [:cat :my.root/merge-request-request]
                  [:or :seon.source/landed :seon.source/conflict-error :seon.error/value]]}
  [{context :my.program/context merge-task :seon.task/merge root :seon.fn/root}]
  (let [database (db/db (:seon.db/connection context))
        request (open-merge-request database merge-task)
        effective (when-not (or root (:seon.error/at request)) (config/effective database (:seon.cluster/name context)))
        checkout (or root (some-> (:seon.config.fs/working-root effective) java.io.File. .getCanonicalPath))]
    (cond (:seon.error/at request) request
          (:seon.error/at effective) effective
          :else (source/land! context request checkout))))

(defn close!
  "Close the open merge request of merge task `:seon.task/merge` without
  merging it, saying why (`:seon.task/reason`): it records `:closed` and
  deletes the source branch, closing its agent (`seon.task/close!`). Answers
  the closed merge request.

  Example:
  (my.root/close! {:seon.task/merge \"0123456789ab\" :seon.task/reason \"The change belongs in seon.db.\"})"
  {:malli/schema [:=> [:cat :my.root/close-request] [:or :seon.task/closed :seon.error/value]]}
  [{context :my.program/context merge-task :seon.task/merge reason :seon.task/reason}]
  (let [connection (:seon.db/connection context)
        request (open-merge-request (db/db connection) merge-task)]
    (if (:seon.error/at request)
      request
      (task/close! (cond-> (select-keys context [:seon.db/connection :seon.store/store :seon.agent/routing])
                     (:seon.env/environment context) (assoc :seon.env/environment (:seon.env/environment context)))
                   merge-task :closed reason))))

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
