(ns seon.agent.runtime
  "Host process-local resources for durable agents.

   This namespace connects database-authored agent identities to ephemeral
   execution resources and cleanup. Durable lifecycle truth stays in database
   facts; process state is retained only where the host must manage a live
   runtime."
  (:require
    [seon.agent.home :as home]
    [seon.agent.loop :as loop]
    [seon.ai.dispatch :as ai.dispatch]
    [seon.db :as db]
    [seon.runtime.admission :as admission]
    [seon.schema :as schema]))

(schema/register! ::wake? [:boolean {:default true}])
(schema/register! ::llm-fn 'fn?)
(schema/register! ::resumed? :boolean)
(schema/register! ::unhosted? :boolean)
(schema/register! ::unhosted-ids [:vector :seon.agent/id])
(schema/register! ::error :string)

(schema/register! ::resume-request
  [:map
   [:seon.agent/id :seon.agent/id]
   [::llm-fn {:optional true} ::llm-fn]])

(schema/register! ::resume-response
  [:or
   [:map
    [:seon.agent/id :seon.agent/id]
    [:seon.agent/ns :symbol]
    [::resumed? [:= true]]]
   [:map
    [:seon.agent/id :seon.agent/id]
    [::resumed? [:= false]]
    [::error ::error]
    [:seon/error {:optional true} :map]]])

(schema/register! ::unhost-request
  [:map [:seon.agent/id :seon.agent/id]])
(schema/register! ::unhost-response
  [:map
   [:seon.agent/id :seon.agent/id]
   [::unhosted? [:= true]]])
(schema/register! ::unhost-all-response
  [:map {:closed true}
   [::unhosted-ids ::unhosted-ids]])

(defn- wake-armed?
  "Whether an acquired agent row enables its automatic message interest."
  [entity]
  (not= false (::wake? entity)))

(defn- database-error? [value]
  (and (map? value) (string? (:seon.error/message value))))

(defn unhost!
  "Remove every process-local handle for one agent; idempotent."
  {:malli/schema [:=> [:cat ::unhost-request] ::unhost-response]}
  [{:seon.agent/keys [id]}]
  (loop/uninstall-wake-trigger! {:seon.agent/id id})
  {:seon.agent/id id ::unhosted? true})

(defn unhost-all!
  "Remove every agent runtime hosted by this process."
  {:malli/schema [:=> [:cat] ::unhost-all-response]}
  []
  {::unhosted-ids
   (::loop/uninstalled-ids (loop/uninstall-all-wake-triggers!))})

(defn ^:async resume!
  "Reconstruct one existing, nonterminated agent in this process.

   The database entity must already exist. The function replaces any stale
   loop listener/input. The supervised execution child reconstructs the
   agent's compiler and authored program lazily. No cluster seed, program
   replay, global instrumentation, identity allocation, or duplicate
   membership bookkeeping occurs here."
  {:malli/schema [:=> [:cat ::resume-request] ::resume-response]}
  [{:seon.agent/keys [id] ::keys [llm-fn]}]
  (if-not (admission/available?)
    {:seon.agent/id id
     ::resumed? false
     ::error "resume!: runtime program generation is unavailable"
     :seon/error (:seon/error (admission/unavailable))}
    (let [entity (await
                  (db/pull
                   {::db/pull-pattern
                    [:seon.agent/id :seon.agent/terminated-at ::wake?
                     {:seon.agent/namespace [:seon.ns/name]}]
                    ::db/ref [:seon.agent/id id]}))]
      (cond
        (database-error? entity)
        {:seon.agent/id id
         ::resumed? false
         ::error "resume!: database authority read failed"
         :seon/error entity}

        (nil? entity)
        {:seon.agent/id id
         ::resumed? false
         ::error (str "resume!: no durable agent entity for " id)}

        (some? (:seon.agent/terminated-at entity))
        (do
          (unhost! {:seon.agent/id id})
          {:seon.agent/id id
           ::resumed? false
           ::error (str "resume!: agent " id " is terminated")})

        :else
        (let [llm (or llm-fn (ai.dispatch/llm-fn))
              ns  (home/starting-ns id entity)]
          (await
            (db/with-agent id
              (fn ^:async resume-agent! []
                (when (admission/available?)
                  (if (wake-armed? entity)
                    (do
                      (await
                       (loop/install-wake-trigger!
                        {:seon.agent/id id
                         :seon.agent/llm-fn llm}))
                      (loop/drive-run! {:seon.agent/id id}))
                    (loop/uninstall-wake-trigger! {:seon.agent/id id}))))))
          (if (admission/available?)
            {:seon.agent/id id
             :seon.agent/ns ns
             ::resumed? true}
            {:seon.agent/id id
             ::resumed? false
             ::error "resume!: runtime program generation became unavailable"
             :seon/error (:seon/error (admission/unavailable))}))))))
