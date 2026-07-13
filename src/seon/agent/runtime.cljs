(ns seon.agent.runtime
  "Process-local hosting for durable agents.

   Durable birth belongs to [[seon.agent]]. This namespace owns the inverse
   projection: reconstruct one existing agent's disposable compiler namespace,
   loop input, and message listener from database facts.
   Resume never allocates identity or re-runs cluster boot work; unhost removes
   every per-agent process handle."
  (:require
    [seon.agent.home :as home]
    [seon.agent.loop :as loop]
    [seon.ai.dispatch :as ai.dispatch]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.repl :as repl]
    [seon.schema :as schema]))

(schema/register! ::wake? [:boolean {:default true}])
(schema/register! ::llm-fn fn?)
(schema/register! ::compile-state :any)
(schema/register! ::resumed? :boolean)
(schema/register! ::unhosted? :boolean)
(schema/register! ::error :string)

(schema/register! ::resume-request
  [:map
   [:seon.agent/id :seon.agent/id]
   [::llm-fn {:optional true} ::llm-fn]
   [::compile-state {:optional true} ::compile-state]])

(schema/register! ::resume-response
  [:or
   [:map
    [:seon.agent/id :seon.agent/id]
    [:seon.agent/ns :symbol]
    [::resumed? [:= true]]]
   [:map
    [:seon.agent/id :seon.agent/id]
    [::resumed? [:= false]]
    [::error ::error]]])

(schema/register! ::unhost-request
  [:map [:seon.agent/id :seon.agent/id]])
(schema/register! ::unhost-response
  [:map
   [:seon.agent/id :seon.agent/id]
   [::unhosted? [:= true]]])

(defn wake-armed?
  "Whether `id` should install its automatic inbound-message listener.

   The durable `::wake?` fact defaults to true when the attribute or value is
   absent. It gates only the listener; MCP addressability is projected from
   durable agent facts and does not depend on this process handle."
  {:malli/schema [:=> [:catn [:seon.agent/id :seon.agent/id]] :boolean]}
  [id]
  (let [db-value (some-> db/*conn* deref)]
    (if (and db-value
             (contains? (db/installed-schema db-value) ::wake?))
      (let [value (::wake?
                    (db/entity {:seon.db/db db-value
                                :seon.db/ref [:seon.agent/id id]}))]
        (if (boolean? value) value true))
      true)))

(defn unhost!
  "Remove every process-local handle for one agent; idempotent."
  {:malli/schema [:=> [:cat ::unhost-request] ::unhost-response]}
  [{:seon.agent/keys [id]}]
  (loop/uninstall-wake-trigger! {:seon.agent/id id})
  {:seon.agent/id id ::unhosted? true})

(defn ^:async resume!
  "Reconstruct one existing, nonterminated agent in this process.

   The database entity must already exist. The function wires its deterministic
   home namespace into the shared bootstrap compiler and replaces any stale
   loop listener/input. No cluster seed, program replay,
   global instrumentation, identity allocation, or duplicate membership
   bookkeeping occurs here."
  {:malli/schema [:=> [:cat ::resume-request] ::resume-response]}
  [{:seon.agent/keys [id] ::keys [llm-fn compile-state]}]
  (let [entity (db/entity {:seon.db/ref [:seon.agent/id id]})]
    (cond
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
      (let [cs  (or compile-state (await (repl/ensure-bootstrap!)))
            llm (or llm-fn (ai.dispatch/llm-fn))
            ns  (home/home-ns id)]
        (await
          (db/with-agent id
            (fn ^:async resume-agent! []
              (await (seval/setup-agent-ns! cs ns id))
              (if (wake-armed? id)
                (loop/install-wake-trigger!
                  {:seon.agent/id id
                   :seon.agent/llm-fn llm
                   :seon.agent/compile-state cs})
                (loop/uninstall-wake-trigger! {:seon.agent/id id})))))
        {:seon.agent/id id
         :seon.agent/ns ns
         ::resumed? true}))))
