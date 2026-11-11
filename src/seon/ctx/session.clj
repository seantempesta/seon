(ns seon.ctx.session
  "Example sessions: a finished session of another task, kept as EDN and loaded under example keys, that a task names for its agent to read (docs/render.md#the-models-context-a-cached-prefix-then-the-turn)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.program :as program]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.task :as task] [seon.lib :as lib]
            [seon.db :as pdb]
            [seon.ratom :as ratom]
            [seon.ctx :as ctx]))

(defn examples-of
  "The keys of the example sessions task id names (:seon.task/examples, agents keyed \"example/<id>\") on db, in key order; none
  when the task names none. See docs/tasks.md#a-task-names-example-sessions."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/id] [:vector :string]]}
  [db id]
  (vec (sort (d/q '[:find [?k ...] :in $ ?id :where [?t :seon.task/id ?id] [?t :seon.task/examples ?x] [?x :seon.agent/key ?k]] db id))))

(defn example-t
  "The newest transaction of example session k on db: its agent's and its records'. The session never
  changes after it, so a replay draws it as of this t. See
  docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat :seon.host/db :string] :int]}
  [db k]
  (let [a (pdb/eid db :seon.agent/key k)]
    (reduce max 0 (keep #(d/q % db a) '[[:find (max ?tx) . :in $ ?a :where [?a :seon.agent/key _ ?tx]]
                                         [:find (max ?tx) . :in $ ?a :where [_ :seon.eval/agent ?a ?tx]]
                                         [:find (max ?tx) . :in $ ?a :where [_ :seon.message/to ?a ?tx]]
                                         [:find (max ?tx) . :in $ ?a :where [?m :seon.model-call/agent ?a] [?m :seon.model-call/reply _ ?tx]]]))))

(defn session-open
  "What opens the agent's own session, id: every conversation opens on a user message, never on a blank assistant one. See docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat :string] :string]}
  [id] (str "<session id=\"" id "\" from=\"seon\">\nYour own session: the work you are asked to do."))

(defn example-open
  "What opens example session k, whose request was desc (Seon's framing, not a person's: a user message is the one turn every provider renders in place). See
  docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat :string [:maybe :string]] :string]}
  [k desc] (str "<session id=\"" k "\" kind=\"example\" from=\"seon\">\nA finished session of another task, shown for how its work was done; not yours. Its request:\n" desc))

(defn session-examples
  "The keys of the example sessions agent record c reads: those of the task its first model turn worked (a later stage of the session keeps the examples it began with), else of its task now. See
  docs/tasks.md#a-task-names-example-sessions."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:vector :string]]}
  [db c]
  (let [first-turn (some->> (ratom/realize (:seon.agent/key c)) (ctx/call-of db 1))
        id (or (when first-turn (:task (:seon.model-call/basis (d/pull db [:seon.model-call/basis] first-turn))))
               (get-in c [:seon.agent/task :seon.task/id]))]
    ;; read from the task's datoms, so a task naming none costs no contracted call
    (if-let [te (when id (pdb/eid db :seon.task/id id))]
      (if (seq (d/datoms db :eavt te :seon.task/examples)) (examples-of db id) [])
      [])))

(def ^:private session-kinds
  "The kinds of record `session-records` answers, each with the attributes that name it, its agent and its model call. See
  docs/tasks.md#a-task-names-example-sessions."
  {:seon.db/eval-fact [:seon.eval/id :seon.eval/agent :seon.eval/call]
   :seon.message/fact [:seon.message/id :seon.message/to nil]
   :seon.model-call/fact [:seon.model-call/id :seon.model-call/agent nil]})

(defn- in-example
  "Id x of a record as example ek keeps it: the content id of the two (seon.lib/id), so two examples of one session keep
  their ids apart and every id stays one any Clojure reader and any provider takes. See docs/tasks.md#a-task-names-example-sessions."
  {:malli/schema [:=> [:cat :string :string] :string]}
  [ek x] (lib/id (lib/digest (str ek "/" x))))

(defn- example-record
  "An eval m as example ek keeps it: its call id in the example's (`in-example`; a handle names its answer: it stays). See docs/tasks.md#a-task-names-example-sessions."
  {:malli/schema [:=> [:cat :string :map] :map]}
  [ek m]
  (cond-> m
    (:seon.eval/call-id m) (update :seon.eval/call-id #(in-example ek %))))

(defn- who
  "The agent key a sender or evaluator x names, as a record holds it: the joined entity's :seon.agent/key; a string, as a session
  recorded before senders were refs holds it (a store kept from then: an example exported from it), the key it is, but \"root\" and
  \"seon\", which were the system's names; nil for the system. See docs/tasks.md#a-task-names-example-sessions."
  {:malli/schema [:=> [:cat :any] [:maybe :string]]}
  [x] (cond (string? x) (when-not (#{"root" "seon"} x) x) (map? x) (:seon.agent/key x) :else nil))

(defn- example-rekey
  "Record m of one of `session-kinds` (its id, agent and model call attributes id-k, agent-k and call-k) as example ek keeps it: its db
  attributes dropped, its id the example's (`in-example`; a model call's seon.lib/call-id of ek and its turn), its agent ek's, its model call ek's of its turn, an eval as `example-record` has it. See
  docs/tasks.md#a-task-names-example-sessions. Its sender or evaluator (:seon.message/from, :seon.eval/by, as its pull joins them) is that entity by its identity (seon.task/sender, an upsert, so it resolves where the example is loaded): the
  original agent itself, orig, is ek; another agent (a user) keeps its key; the system stays the system; a string, from a session recorded before
  senders were refs, is read by `who`."
  {:malli/schema [:=> [:cat :string :string :map [:tuple :qualified-keyword :qualified-keyword [:maybe :qualified-keyword]]] :map]}
  [ek orig m [id-k agent-k call-k]]
  (-> (apply dissoc m :turn (filter #(= "db" (namespace %)) (keys m)))
      (cond-> (:seon.message/from m) (update :seon.message/from #(task/sender (let [k (who %)] (if (= orig k) ek k))))
              (:seon.eval/by m) (update :seon.eval/by #(task/sender (let [k (who %)] (if (= orig k) ek k)))))
      (cond-> call-k (assoc call-k (task/call-ref ek (:turn m 0))))
      ;; a model call is `<agent key>/<n>` (docs/turn.md#the-turn-is-a-number), the example's too: the page finds it by that
      (as-> m (if-let [n (:seon.model-call/n m)] (assoc m id-k (lib/call-id ek n)) (update m id-k #(in-example ek %))))
      (assoc agent-k (cond-> [:seon.agent/key ek] (= :seon.message/to agent-k) vector))
      (cond-> (:seon.eval/code m) (->> (example-record ek)))))

(defn- session-records
  "The conversation of agent record c on db, [record kind] in its order (turn by turn: its messages, its reply, its evals). A record keeps the turn it names as :turn. See docs/tasks.md#a-task-names-example-sessions."
  {:malli/schema [:=> [:cat :map :seon.host/db] [:vector [:tuple :map :qualified-keyword]]]}
  [c db]
  (let [placed (ctx/placed-messages c)]
   (vec (for [[n cid] (ctx/turns c)
             :let [messages (get placed n) evals (ctx/turn-evals c cid)
                   call (get-in c [:seon.model-call/_agent cid])]
             r (concat (for [[m] messages] [(assoc (select-keys (get-in c [:seon.message/_to m]) [:seon.message/id :seon.message/from :seon.message/body]) :turn n) :seon.message/fact])
                       (when (= :model (:seon.model-call/origin call))
                         [[(select-keys call [:seon.model-call/id :seon.model-call/n :seon.model-call/origin :seon.model-call/reply :seon.model-call/reasoning :seon.model-call/truncated]) :seon.model-call/fact]])
                       (for [[e] evals :let [ev (get-in c [:seon.eval/_agent e]) whole (pdb/response-of db ev)]]
                         [(assoc (pdb/with-response (dissoc ev :db/id :seon.eval/agent :seon.eval/call :seon.eval/opened :seon.eval/result :at) whole) :turn n) :seon.db/eval-fact]))]
         r))))

(defn- session-request
  "The request agent a answered, for an example: its task's plan, else its own description; nil when it has neither
  (a blank one is none). See docs/tasks.md#a-task-names-example-sessions."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:maybe :string]]}
  [db a]
  (->> [(:seon.task/plan (:seon.agent/task (d/pull db [{:seon.agent/task [:seon.task/plan]}] a)))
        (:seon.agent/description (d/pull db [:seon.agent/description] a))]
       (remove str/blank?) first))

(defn session-tx
  "Agent key's session on db as example id: the transactions that recreate it as an example, a vector of
  tx-data, one per record in the order of its conversation (each names its turn's model call, so the copy reads
  in that order), or the `:seon.ctx/session-refused` error when no agent has key, it has no request to
  show (its task has no plan and it has no description) or a record is of a kind an example does not hold.
  The first transaction writes the example agent, an ended agent like any (key \"example/<id>\", id
  :example-<id>, its description the request, `session-request`, its turn the last its records carry); the model's evals
  are kept, an eval the harness made in the agent is not; each other holds one record,
  re-keyed under the example's key (`example-rekey`: ids and call ids,
  so none meets a live agent's, nor another example's). Pure data: written with pr-str, read back,
  `my.root/load-example!` transacts it. See docs/tasks.md#a-task-names-example-sessions."
  {:malli/schema [:=> [:cat :seon.host/db :string :string]
                  [:or [:vector [:vector :map]] :seon.ctx/session-refused]]}
  [db key id]
  (let [a (pdb/eid db :seon.agent/key key)
        refuse #(contract/error :seon.ctx/session-refused #'seon.ctx.session/session-tx % {:key key})
        ek (str "example/" id)
        request (when a (session-request db a))
        ;; the model's own evals (a call id) and its messages and replies: not an eval the harness made in the agent
        ;; an eval of @ctx keeps its code, and its value as the marker of what it was: its basis is a commit of the original branch, which a copy on another cannot rebuild
        cannot (fn [[v k]] (if (and (= :seon.db/eval-fact k) (:seon.eval/basis v))
                             [(-> v (dissoc :seon.eval/basis :seon.eval/schema)
                                  (as-> v' (ctx/revalued nil v' {:seon.value/gone {:type "the agent's record at a basis a copy cannot rebuild"}})))
                              k]
                             [v k]))
        rs (when a (map cannot (remove (fn [[v k]] (and (= :seon.db/eval-fact k) (not (:seon.eval/call-id v)))) (session-records (ctx/agent-ctx (program/index-of-db db) db a) db))))
        unknown (first (remove #(session-kinds (second %)) rs))]
    (cond (nil? a) (refuse (str "no agent is keyed \"" key "\""))
          (nil? request) (refuse (str "agent \"" key "\" has no request to show: its task has no plan and it has no description"))
          unknown (refuse (str "agent \"" key "\" holds a record of kind " (second unknown) ", which an example does not"))
          ;; its turn is the last its records carry (docs/turn.md#the-turn-is-a-number): the page draws a component for each
          :else (into [[{:seon.agent/key ek :seon.agent/status :ended :seon.agent/description request
                         :seon.agent/turn (reduce max 0 (map (fn [[v]] (or (:turn v) (:seon.model-call/n v) 0)) rs))}]]
                      (for [[v k] rs]
                        [(example-rekey ek key v (session-kinds k))])))))

