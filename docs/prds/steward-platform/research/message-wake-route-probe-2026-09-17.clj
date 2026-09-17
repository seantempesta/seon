;; Read-only JVM form. Run against default before and after the orchestrator's
;; single publication. Candidate-route is a derivation, not deployed behavior.
(let [database (seon.db/db (seon.operator/connection "default"))
      agent "juniper"
      eid (:db/id (seon.db/pull database [:db/id] [:seon.agent/id agent]))
      basis (seon.turn/latest-answering-turn-t database agent)
      routed (seon.cluster.wake/agent-wake-datoms database eid #{:seon.message/to})]
  {:probe/basis (seon.db/basis-t database)
   :probe/answering-t basis
   :probe/listened (seon.cluster.wake/wake-attributes database)
   :probe/current-all (seon.turn/unanswered-wakes database agent {:seon.turn.work/answered? :any})
   :probe/permanent-route
   (mapv (fn [[entity t attribute]]
           (assoc (seon.db/pull database [:seon.message/id] entity)
                  :probe/t t :probe/answered? (<= t basis))) routed)
   :probe/installed-subject (get (:schema database) :seon.message/about)})
