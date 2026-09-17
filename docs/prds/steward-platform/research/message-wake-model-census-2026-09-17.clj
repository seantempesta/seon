; Read-only JVM form for mcp__seon__eval_clj, cluster default, root repository.
; prn preserves the bounded evidence when the returned-value projector refuses.
(let [database (seon.db/db (seon.operator/connection "default"))
      history (seon.db/history database)
      agents (seon.db/q '[:find [?id ...] :where [_ :seon.agent/id ?id]] database)]
  (prn
   {:seon.message/history-counts
    (into {} (map (fn [a]
                    [a (frequencies (map :added (seon.db/datoms history :avet a)))])
                  [:seon.message/to :seon.message/inbox]))
    :seon.message/agents
    (mapv (fn [agent]
            {:seon.agent/id agent
             :seon.message/all-wakes
             (count (seon.turn/unanswered-wakes
                     database agent {:seon.turn.work/answered? :any}))
             :seon.message/unanswered
             (count (seon.turn/unanswered-wakes database agent {}))
             :seon.turn/basis-t (seon.turn/latest-answering-turn-t database agent)})
          (sort agents))
    :seon.message/listened (seon.cluster.wake/wake-attributes database)
    :seon.eval/refreshes-count
    (count (seon.db/datoms database :avet :seon.cluster.eval/refreshes))}))
