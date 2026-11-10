(ns seon.task
  "Tasks as data (TASK-CHAIN §1, the target model): one entity per task on main, its
  desired state written by root; runs and proof results are observed state, written
  by their observer (root's executor) when the event happens. There is no chain entity:
  `:seon.task/needs` is the one dependency edge.

  This namespace is the one writer of those facts (tx-data builders; root transacts
  them) and the one reader (pull shapes for the reconciler and the agent). From sessions R1
  and M1's proto.agents.task; the attributes are derived from resources/seon/schemas (session D1)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [datahike.api :as d] [datahike.db.utils :as dbu] [clojure.string :as str] [clojure.edn :as edn] [seon.merge :as pm] [seon.db :as pdb] [seon.edn :as sedn]
            [seon.print :as print] [seon.ref :as ref] [seon.program :as program] [seon.config :as cfg] [seon.lib :as lib] [clojure.walk :as walk]))

;; ---- identity --------------------------------------------------------------------

(defn entity-tx
  "Make entity e state exactly want on attributes attrs: per attribute, the values e holds
  that want lacks retracted and the ones it lacks asserted, so an entity that already states
  want writes nothing. In want a set is a card-many attribute's values, any other value one
  value, nil none; a ref is an entity id (a tempid or lookup ref is always asserted)."
  {:malli/schema [:=> [:cat :seon.host/db :int [:sequential :keyword] :map] [:vector :any]]}
  [db e attrs want]
  (vec (for [a attrs
             :let [v (get want a) new (cond (set? v) v (some? v) #{v} :else #{})
                   old (into #{} (map :v) (d/datoms db :eavt e a))]
             tx (concat (for [x old :when (not (new x))] [:db/retract e a x])
                        (for [x new :when (not (old x))] [:db/add e a x]))]
         tx)))

(defn task-id
  "The task's handle, ns/name: its identity, derived from its namespace and name."
  {:malli/schema [:=> [:cat :seon.task/ns :seon.task/name] :seon.task/id]}
  [ns-sym nm] (str ns-sym "/" nm))

(defn merges?
  "Whether a task of kind merges main into its branch at each green and its green branch into
  main: a merge task, and a whole one (seon.template/request: one agent works the request
  through and merges it)."
  {:malli/schema [:=> [:cat [:maybe :keyword]] :boolean]}
  [kind] (contains? #{:merge :whole} kind))

(defn default-branch
  "The branch a task's runs check out when it names none: task-<ns>-<name>."
  {:malli/schema [:=> [:cat :seon.task/id] :seon.task/branch]}
  [id] (keyword (str "task-" (str/replace id #"[^A-Za-z0-9-]" "-"))))

;; ---- the writer ------------------------------------------------------------------

(defn- cited-texts
  "The texts a declaration cites from, each its own Markdown text: its plan and its instructions. Never one text:
  a plan that quotes a backtick run (\"a fenced block's ``` marks\") opened a code span that took the
  instructions' delimiters, so their ```docs/agent.md#working-a-task``` was cited at summary depth, its heading alone."
  {:malli/schema [:=> [:cat [:map [:seon.task/plan {:optional true} [:maybe :string]] [:seon.task/instructions {:optional true} [:maybe :string]]]] [:vector :string]]}
  [decl] (into [] (keep decl) [:seon.task/plan :seon.task/instructions]))

(defn- texts-citations
  "The citations of texts, each parsed on its own (seon.ref/parse), one vector."
  {:malli/schema [:=> [:cat [:vector :string]] [:vector :seon.ref/citation]]}
  [texts] (into [] (mapcat #(:seon.ref/citations (ref/parse nil %))) texts))

(defn- text-citations
  "What a declaration's text cites, read on db: its plan and instructions parsed once (seon.ref/parse),
  each target once at its strongest depth (seon.ref/cited), those db keeps as references (seon.ref/kept: a
  text word only when it names something, no alias form), a line reference and an anchor with no page
  left out (they name nothing to pull)."
  {:malli/schema [:=> [:cat :seon.host/db [:map [:seon.task/plan {:optional true} [:maybe :string]] [:seon.task/instructions {:optional true} [:maybe :string]]]] [:vector :seon.ref/citation]]}
  [db decl]
  (into [] (remove :seon.ref/problem-kind) (ref/kept db (ref/cited (texts-citations (cited-texts decl))))))

(defn gone
  "What declaration decl's text cites (its delimited citations, `text-citations`, a resolve-only word left out: it may name
  what a plan will make) that db no longer holds, each as its target's identity value: a cite ends with its target
  (docs/storage.md#the-write-checks-every-entity-it-touches), so the text is what still names it. See
  docs/health.md#a-task-that-cites-nothing."
  {:malli/schema [:=> [:cat :seon.host/db [:map [:seon.task/plan {:optional true} [:maybe :string]] [:seon.task/instructions {:optional true} [:maybe :string]]]] [:vector :any]]}
  [db decl]
  (into [] (keep #(when-not (:seon.ref/resolve-only %) (let [[a v] (ref/target db %)] (when-not (pdb/eid db a v) v)))) (text-citations db decl)))

(defn cite-tx
  "The cite entity of entity of (a lookup ref, or the tempid of the write that makes it; of-id its identity
  value) for citation c at place order: its target (seon.ref/target on db, the db the write holds: a lookup that resolves
  against what the write made before it, so a task's own name is the task it makes), its depth, its content id
  \"<of-id> <target>\", upserted by it. See docs/references.md#an-entity-cites."
  {:malli/schema [:=> [:cat :seon.host/db :any :string [:map [:seon.ref/depth :seon.ref/depth]] :int] :map]}
  [db of of-id c order]
  (let [t (ref/target db c)]
    {:seon.cite/id (str of-id " " (second t)) :seon.cite/of of :seon.cite/target t
     :seon.cite/depth (:seon.ref/depth c) :seon.cite/order order}))

(def ^:private cite-pull
  "What a reader that is no draw reads of a cite: its entity id (what a rewrite retracts), its own attributes and its target's
  identity (seon.ref/target-attrs). See docs/references.md#an-entity-cites."
  [:db/id :seon.cite/id :seon.cite/of :seon.cite/depth :seon.cite/order {:seon.cite/target (into [:db/id] ref/target-attrs)}])

(defn cites-of
  "The cite entities entity e holds on db (:seon.cite/of), pulled with their targets' identities (cite-pull), in their
  order: what a reader that is no draw reads of what e cites (the judge, a problem, my.agent/cite!). See
  docs/references.md#an-entity-cites."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :int]] [:vector :map]]}
  [db e]
  (if e
    (vec (sort-by :seon.cite/order (for [dt (d/datoms db :avet :seon.cite/of e)] (d/pull db cite-pull (:e dt)))))
    []))

(defn cited-name
  "What cite c (as cites-of reads it) names, its target's identity value (seon.ref/target-value): a section's address, a
  var's name, a task's id, a schema key, a namespace's name or a file's path. See docs/references.md#an-entity-cites."
  {:malli/schema [:=> [:cat :map] :any]}
  [c] (ref/target-value (:seon.cite/target c)))

(defn task-cites
  "What task id's page draws of what it cites, read on db: its own cites, then each need's (one level, a
  merge task's left out, as the task-fact's :needs join holds them), each target once at the strongest depth
  it is cited at, in that order. What a reader that is no draw takes (my.task/data, the turn's waste log);
  the page reads the same from @ctx. See docs/references.md#task-references."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/id] [:vector :map]]}
  [db id]
  (let [e (pdb/eid db :seon.task/id id)
        needs (when e (sort-by :seon.task/id (remove #(= :merge (:seon.task/kind %)) (map #(d/pull db [:db/id :seon.task/id :seon.task/kind] (:v %)) (d/datoms db :eavt e :seon.task/needs)))))
        cs (concat (cites-of db e) (mapcat #(cites-of db (:db/id %)) needs))
        target #(get-in % [:seon.cite/target :db/id])
        by (group-by target cs)]
    (mapv (fn [t] (apply max-key #(ref/strength (:seon.cite/depth %)) (rseq (vec (by t))))) (distinct (map target cs)))))

(defn cites-tx
  "The facts that make entity of (as cite-tx takes it) cite exactly citations cs on db: one cite entity per
  target (cite-tx), at the strongest depth any of cs asks for it and its first's place, and each cite of of on db no
  longer among them retracted whole. The one writer of a text's cites, called where the text is written (create-tx,
  load-tx, rederive-tx), after what the same write makes. See docs/references.md#an-entity-cites."
  {:malli/schema [:=> [:cat :seon.host/db :any :string [:sequential [:map [:seon.ref/depth :seon.ref/depth]]]] [:vector :any]]}
  [db of of-id cs]
  (let [{:keys [by order]} (reduce (fn [{:keys [by order] :as acc} c]
                                     (let [m (cite-tx db of of-id c (count order)) id (:seon.cite/id m)]
                                       (if-let [prev (by id)]
                                         (assoc-in acc [:by id :seon.cite/depth] (max-key ref/strength (:seon.cite/depth prev) (:seon.cite/depth m)))
                                         {:by (assoc by id m) :order (conj order id)})))
                                   {:by {} :order []} cs)
        e (when (vector? of) (pdb/eid db (first of) (second of)))]
    (into (mapv by order)
          (for [c (cites-of db e) :when (not (by (:seon.cite/id c)))] [:db/retractEntity (:db/id c)]))))

(defn rederive-tx
  "The facts that make task t's cites those its text states now (`text-citations` over its plan and
  instructions, `cites-tx`): what a write of its plan or instructions adds, as task creation does
  (my.task/update!). See docs/references.md#task-references."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:vector :any]]}
  [db t]
  (cites-tx db [:seon.task/id (:seon.task/id t)] (:seon.task/id t) (text-citations db t)))

(defn agent-cites-tx
  "The cites a new agent (of, its lookup ref or the tempid of the write that makes it; k its key) is made
  with: what every agent cites, :seon.config.agent/cites, each address read by seon.ref/address-citation,
  written on its record once, by its first run on its branch (seon.agent/begin-run!), so its own my.agent/cite!
  and uncite! change them. See
  docs/references.md#an-entity-cites."
  {:malli/schema [:=> [:cat :seon.host/db :any :string] [:vector :any]]}
  [db of k]
  (vec (map-indexed (fn [i c] (cite-tx db of k c i)) (keep ref/address-citation (cfg/value :seon.config.agent/cites)))))

(defn- cycle-in
  "A task id on a needs cycle among decls (plus the needs already on main, which cannot
  reach a new task), or nil."
  {:malli/schema [:=> [:cat [:map-of {:gen/max 4} :seon.task/id :seon.task/needs]] [:maybe :seon.task/id]]}
  [graph]
  (let [visit (fn visit [seen id]
                (cond (contains? seen id) id
                      :else (some #(visit (conj seen id) %) (get graph id))))]
    (some #(visit #{} %) (keys graph))))

(defn- invalid
  "What raiser op (by its var: the compile resolves it) refuses, with message m."
  {:malli/schema [:=> [:cat :seon.host/var :string] :map]}
  [op m] {:seon.error/type :seon.task/refused :seon.error/operation (symbol op) :seon.error/message m})

(defn- needs-problem
  "Why graph (task id -> needs) cannot be written: a need known? does not hold, or a cycle;
  nil when neither."
  {:malli/schema [:=> [:cat [:map-of {:gen/max 4} :seon.task/id :seon.task/needs] ifn?] [:maybe :string]]}
  [graph known?]
  (if-let [missing (seq (for [[_ ns] graph n ns :when (not (known? n))] n))]
    (str "needs name no task: " (str/join ", " missing))
    (some->> (cycle-in graph) (str "needs form a cycle through "))))

(defn create-tx
  "The transaction writing decls (task declarations) as held tasks: each gets its id and
  its branch; needs refer by id, to a task of this write or one already on db. A cycle
  or a need that names no task is the :seon.task/refused error."
  {:malli/schema [:=> [:cat :seon.host/db [:vector :seon.task/declaration]]
                  [:or [:vector :map] [:map [:seon.error/type [:= :seon.task/refused]] [:seon.error/message :string]]]]}
  [db decls]
  (let [ids (mapv #(task-id (:seon.task/ns %) (:seon.task/name %)) decls)
        graph (zipmap ids (map #(set (:seon.task/needs %)) decls))]
    (if-let [p (needs-problem graph #(or (graph %) (pdb/eid db :seon.task/id %)))]
      (invalid #'seon.task/create-tx p)
      (let [tasks (mapv (fn [id {:seon.task/keys [config needs evidence] :as decl}]
                    (cond-> (-> decl (dissoc :seon.task/config :seon.task/needs :seon.task/evidence)
                                (assoc :db/id id :seon.task/id id :seon.task/execution :held)
                                (update :seon.task/branch #(or % (default-branch id)))
                                ;; a declaration that names no kind is work: the task holds it (:seon.task/task-fact requires it)
                                (update :seon.task/kind #(or % :work)))
                      (seq needs) (assoc :seon.task/needs (mapv #(if (graph %) % [:seon.task/id %]) needs))
                      evidence (assoc :seon.task/evidence evidence)
                      (seq config) (merge config)))
                  ids decls)
            ;; what each text cites, read on the db the tasks make (a task this write declares is a task it may
            ;; cite), its cite entities naming the task by its tempid
            db' (:db-after (pdb/speculate db tasks))]
        (into tasks (mapcat (fn [id decl] (cites-tx db' id id (text-citations db' decl))) ids decls))))))

(defn execution-tx
  "Set the desired execution of tasks ids (:running is root's release, run!)."
  {:malli/schema [:=> [:cat [:sequential :seon.task/id] :seon.task/execution] [:vector :map]]}
  [ids execution]
  (mapv (fn [id] {:seon.task/id id :seon.task/execution execution}) ids))

(defn env-tx
  "Change task id's desired environment (branch, model, max-turns): a running run in
  another environment is restarted clean by the reconciler."
  {:malli/schema [:=> [:cat :seon.task/id [:map [:branch {:optional true} :keyword] [:model {:optional true} :string]
                                           [:max-turns {:optional true} pos-int?]]] [:vector :map]]}
  [id {:keys [branch model max-turns]}]
  [(cond-> {:seon.task/id id}
     branch (assoc :seon.task/branch branch) model (assoc :seon.task/model model)
     max-turns (assoc :seon.config.agent/max-turns max-turns))])

(defn earlier-agents-tx
  "A new boot's root is no longer the root of the agents an earlier boot started: their link to
  root retracted (their processes ended with that boot; history keeps that root started them), so
  root's agents are this boot's (root's walk, :seon.agent/_root), and the user, which outlives a boot; one still shown live (:busy,
  :idle, :spawning: a root that crashed or was stopped wrote no end) is :dead, its running form
  retracted; and a stall one showed is over, its :seon.process/stall retracted with its process (an
  open :stalled problem never outlives it)."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :any]]}
  [db] (let [live (:seon.status/live (contract/properties :seon.agent/status))
             agents (remove (fn [[a]] (:seon.user/name (d/entity db a))) (d/q '[:find ?a ?r :where [?r :seon.root/id :root] [?a :seon.agent/root ?r]] db))]
         (-> (vec (for [[a r] agents] [:db/retract a :seon.agent/root r]))
             (into (mapcat (fn [[a]] (when (live (:seon.agent/status (d/entity db a)))
                                       [[:db/add a :seon.agent/status :dead]])))
                   agents)
             (into (for [{p :e} (d/datoms db :aevt :seon.process/running)] [:db.fn/retractAttribute p :seon.process/running]))
             (into (for [{p :e} (d/datoms db :aevt :seon.process/stall)] [:db.fn/retractAttribute p :seon.process/stall])))))

(defn boot-of
  "Root's boot on main db, as its entity records it (:seon.root/boot), or nil before root's boot fact."
  {:malli/schema [:=> [:cat :seon.host/db] [:maybe :string]]}
  [db] (:seon.root/boot (d/entity db [:seon.root/id :root])))

(defn this-boots
  "Agent entity e's id when it is an agent of root's boot on main db, else nil: an agent's id is its key
  (a content id, unique forever), so what this boot tells is whether a process of this boot holds it (its
  :seon.agent/boot, seon.task/start-tx)."
  {:malli/schema [:=> [:cat :seon.host/db :any] [:maybe :keyword]]}
  [db e]
  ;; e an entity of main (its key: a walk shows only an agent's relevant attributes)
  (let [k (:seon.agent/key e) boot (boot-of db)]
    (when (and k boot (= boot (:seon.agent/boot e))) (keyword k))))

(def ^:private agent-attrs
  "Each member of root's view of an agent and the attribute recording it on the agent (agent-tx)."
  {:status :seon.agent/status :task :seon.agent/description :branch :seon.agent/branch :boot :seon.agent/boot})

(def ^:private process-attrs
  "Each member of root's view of an agent's process and the attribute recording it on the process
  entity (agent-tx)."
  {:pid :seon.process/pid :running :seon.process/running
   :forked-ms :seon.process/forked-ms :ended-ms :seon.process/ended-ms :stalled :seon.process/stall})

(defn newest-process
  "The newest of processes ps (entities or pulled maps of :seon.process/agent's reverse) by when
  it forked, or nil when none says when: the agent's current process, whose telemetry the agent's
  record no longer holds (docs/ctx.md#the-agents-entity-holds-its-work). An agent has one process per
  spawn, written with its pid and fork time together (agent-tx)."
  {:malli/schema [:=> [:cat [:maybe [:sequential :any]]] [:maybe :any]]}
  [ps] (some->> (seq (filter :seon.process/forked-ms ps)) (apply max-key :seon.process/forked-ms)))

(defn session-key
  "The agent key of task id's session on db, or nil when none of its chain has had an agent: the
  session is id and the tasks on its branch it needs, transitively (a request's stages, a work
  task and its merge: design agent-loop section 1a), and its agent is the newest started among
  the chain's tasks' agents (:seon.task/agent). Work started for the session continues it, so its
  conversation and messages go on (seon.ctx/turn-members). One process at a time holds it: a session is one
  branch, and root refuses a spawn on a branch a live agent holds. Reads the chain's tasks."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/id] [:maybe :string]]}
  [db id]
  (let [e0 (d/entity db [:seon.task/id id])
        br (:seon.task/branch e0)
        chain (loop [seen #{} todo (if e0 [e0] [])]
                (if-let [e (first todo)]
                  (if (seen (:db/id e))
                    (recur seen (rest todo))
                    (recur (conj seen (:db/id e)) (into (rest todo) (filter #(= br (:seon.task/branch %)) (:seon.task/needs e)))))
                  seen))
        newest (->> chain
                    (keep #(:seon.task/agent (d/entity db %)))
                    (sort-by #(:seon.agent/started-ms % 0))
                    last)]
    (:seon.agent/key newest)))

(defn agent-tx
  "What root observes of agent id, as facts: on the agent's entity (its key is its id's name) the root that
  started it and its status, task's id or description and branch; on its process's entity
  (:seon.process/*, one per spawn, pointing at the agent) its pid, memory, the form running, when it
  forked and ended and a stall it shows: each member of root's view m, written when set and retracted
  when m holds it as nil (the form running, once it returned). Root writes it when its
  view of the agent changes (seon.root/record-agents)."
  {:malli/schema [:=> [:cat :seon.agent/id :map] [:vector :any]]}
  [id m]
  (let [k (name id)
        v (fn [member x] (case member :running (:form x) :forked-ms (double x) x))
        pk (some->> (:pid m) (str k "/"))]
    (-> [(into {:seon.agent/key k :seon.agent/root [:seon.root/id :root]}
               (for [[member a] agent-attrs :let [x (some->> (get m member) (v member))] :when (some? x)] [a x]))]
        (into (for [[member a] agent-attrs :when (and (contains? m member) (nil? (get m member)))]
                [:db.fn/retractAttribute [:seon.agent/key k] a]))
        ;; one process entity per spawn, keyed by the agent's key and its pid; none before the fork gives a pid
        (into (when pk
                (cons (into {:seon.process/id pk :seon.process/agent [:seon.agent/key k]}
                            (for [[member a] process-attrs :let [x (some->> (get m member) (v member))] :when (some? x)] [a x]))
                      (for [[member a] process-attrs :when (and (contains? m member) (nil? (get m member)))]
                        [:db.fn/retractAttribute [:seon.process/id pk] a])))))))

(defn- canonical
  "Value v with every map sorted by its keys' text, so the same value is the same text however it was built."
  {:malli/schema [:=> [:cat :any] :any]}
  [v] (walk/postwalk #(if (map? %) (into (sorted-map-by (fn [a b] (compare (str a) (str b)))) %) %) v))

(defn config-tx
  "Config c and its sources as facts on main (platform records: never carried by a merge in
  either direction): the config as an entity of its own, named by the content id of c (`:seon.config/id`, so the same config
  is one entity on every branch), holding every key (each collection-valued key, the manifest among them, as data);
  main's root entity with the layer each key came from and its one ref to that entity (`:seon.root/config`); and an
  entity per provider and per model (:seon.ai.model/provider-id, :seon.ai.model/id). Root's boot writes it
  with the boot fact, and again when a config entry moves (seon.config/moved). See docs/config.md#facts-on-main."
  {:malli/schema [:=> [:cat :seon.config/config :seon.config/sources] [:vector :map]]}
  [c srcs]
  (into [{:seon.root/id :root :seon.config/sources srcs :seon.root/config "config"}
         (assoc c :db/id "config" :seon.config/id (lib/id (lib/digest (sedn/encode (canonical c)))))]
        (concat (for [[id p] (:seon.config.ai/providers c)]
                  (-> (select-keys p [:seon.config.ai/base-url :seon.config.ai/api-key-variable :seon.ai.model/output-token-wire-key])
                      (assoc :seon.ai.model/provider-id id)))
                (for [[id m] (:seon.config.ai/models c)]
                  (-> (dissoc m :seon.config.ai/max-in-flight :seon.config.ai/max-tokens) (assoc :seon.ai.model/id id))))))

(defn boot-tx
  "Root's boot: its id for this process lifetime (the config in force, max-running among its keys, is written with it,
  config-tx), and the user, name, an agent with no process, one entity across boots (:seon.user/name, its lookup ref): the evals and messages it
  makes name it (:seon.eval/by its id), and root's page lists it with its agents; until there is a login, it is who is signed in (:seon.root/signed-in). See docs/users.md#what-each-thing-is."
  {:malli/schema [:=> [:cat :string :string] [:vector :any]]}
  [boot name]
  (into [{:db/id "user" :seon.agent/key name :seon.user/name name}
         {:seon.root/id :root :seon.root/boot boot :seon.root/signed-in "user"}]
        (agent-tx (keyword name) {})))

(defn call-ref
  "The model call of turn n of the agent with key k, as a ref in a transaction: a record of that turn (an eval, a message) names it, which
  makes it when the turn has not begun, so agent -> call -> its records is one pull. See docs/turn.md#the-turn-is-a-number."
  {:malli/schema [:=> [:cat :string nat-int?] :map]}
  [k n] {:seon.model-call/id (lib/call-id k n) :seon.model-call/agent {:seon.agent/key k} :seon.model-call/n n})

(defn message-tx
  "Message mid from from to the agents with keys tos, its body, sent at sent-ms: the one message entity (mid in seon.lib/id's form: a fresh unique id for a message sent, seon.lib/unique-id, or a content id where the same facts must make the one message, as the reconciler's ask of a task at a t), `to` naming each
  recipient's agent entity by key. Written by root's writer on main at the send, and by the recipient on its branch, with its own ref only,
  when its next act delivers it (seon.agent/deliver!). See docs/users.md#messages."
  {:malli/schema [:=> [:cat :string :string [:sequential :string] :any :int] [:vector :map]]}
  [mid from tos body sent-ms]
  [{:seon.message/id mid :seon.message/from from :seon.message/body body :seon.message/sent-ms sent-ms
    :seon.message/to (mapv #(vector :seon.agent/key %) tos)}])

(defn undelivered
  "The messages main holds to the agent with key k that branch-db, its branch, does not hold: each {:id :from :body :sent-ms}, in main's
  order (by transaction, then id). The agent's ref on main is the fact (a seek of :seon.message/to on its entity, never a scan of the
  messages), and the message entity on the branch (one lookup of its id) is its delivery (docs/users.md#messages), written there by seon.agent/deliver! or brought down
  by a merge or sync of main: a message the agent removed (its ref retracted, the entity kept) stays delivered, and one already on main when the branch forked is
  delivered at the fork. The one reading of it: the agent's next act (seon.agent/undelivered) and root's check that a
  parked agent is owed one. O(m) seeks on main and O(m) id lookups on the branch, m the messages to the agent. See docs/users.md#messages."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :string] [:vector [:map [:id :string] [:from :string] [:body :string] [:sent-ms :int]]]]}
  [main branch-db k]
  (if-let [me (pdb/eid main :seon.agent/key k)]
    (->> (d/datoms main :avet :seon.message/to me)
         (map (fn [dt] [(:tx dt) (d/entity main (:e dt))]))
         (remove (fn [[_ m]] (pdb/eid branch-db :seon.message/id (:seon.message/id m))))
         (sort-by (fn [[tx m]] [tx (:seon.message/id m)]))
         (mapv (fn [[_ m]] {:id (:seon.message/id m) :from (:seon.message/from m) :body (:seon.message/body m) :sent-ms (:seon.message/sent-ms m)})))
    []))

(def judged-attrs
  "The attributes root sets on a task when they happen (its judging, seon.reconcile/judge-tx; a refused start), and so
  retracts when they did not: otherwise an earlier end's error or deferred results read as this one's. Beside
  :seon.task/reasons, which a start keeps (the same verdict twice holds, seon.reconcile/judgment) and a person's
  continue retracts (my.root/run!). See docs/reconcile.md#a-task-holds-how-its-work-ended."
  [:seon.task/reason :seon.task/commit :seon.task/commit-error :seon.task/merged-t :seon.task/error :seon.task/deferred
   :seon.task/deferred-results :seon.task/gate-unread :seon.task/changed :seon.task/conflicts :seon.task/unmerged
   :seon.task/task-conflicts :seon.task/failures :seon.task/costs :seon.task/waste])

(defn outcome-tx
  "The write that moves task id's outcome from `from` to `to` (Datahike's compare-and-swap: a racing observer, a
  cancel or a newer start that moved it leaves it, and refuses the whole write), setting the judged attributes
  that are in want (a map of :seon.task/* members, with :seon.task/reasons among them when judged) and retracting every other
  that db holds, as seon.program/def-tx does. See docs/reconcile.md#a-task-holds-how-its-work-ended."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/id [:maybe :seon.task/outcome] :seon.task/outcome :map] [:vector :any]]}
  [db id from to want]
  (let [e (pdb/eid db :seon.task/id id)
        attrs (cond-> judged-attrs (contains? want :seon.task/reasons) (conj :seon.task/reasons))]
    (into [[:db/cas [:seon.task/id id] :seon.task/outcome from to]]
          (entity-tx db e attrs (cond-> want (empty? (:seon.task/reasons want [nil])) (dissoc :seon.task/reasons))))))

(defn move-tx
  "The write that moves task id's outcome from `from` to `to` after its judging (compare-and-swap, as outcome-tx), setting the
  members m beside it and retracting nothing: what judging stored of the task (its deferred tests, its changed keys) stays
  for the merge, the gate and the problems that read it. See docs/reconcile.md#a-task-holds-how-its-work-ended."
  {:malli/schema [:=> [:cat :seon.task/id [:maybe :seon.task/outcome] :seon.task/outcome :map] [:vector :any]]}
  [id from to m]
  (cond-> [[:db/cas [:seon.task/id id] :seon.task/outcome from to]]
    (seq m) (conj (assoc m :seon.task/id id))))

(defn start-tx
  "Agent `agent` (its id; nil: root refused the spawn, and no agent exists) begins task id in root's boot, its turn budget raised by raise (a wake's allowance) turns: the task's
  outcome :running from what it was, naming the agent (its id's name is its key, so a session's agent that continues keeps it, seon.task/session-key), the judged attributes cleared; the agent's entity (key, task, branch and the rest of its environment,
  its boot and when it started, and who started the task now holding it, :seon.agent/agents), its turn budget set when it is new (a raised budget stays, docs/turn.md#the-cap) and raised by raise in the same write.
  A refused spawn is the task's outcome :refused with root's reason and error, and no agent."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.task/id :string [:maybe :keyword] :seon.task/env :int] [:vector :any]]
                  [:=> [:cat :seon.host/db :seon.task/id :string [:maybe :keyword] :seon.task/env :int nat-int?] [:vector :any]]]}
  ([db id boot agent env start-ms] (start-tx db id boot agent env start-ms 0))
  ([db id boot agent {:keys [branch model max-turns holder]} start-ms raise]
   (let [k (when agent (name agent))
         ae (when k (d/entity db [:seon.agent/key k]))
         from (:seon.task/outcome (d/entity db [:seon.task/id id]))]
     (into (outcome-tx db id from :running {})
           (filterv some?
                    [(when k {:seon.task/id id :seon.task/agent k})
                     (when-let [by (when k (:db/id (:seon.task/started-by (d/entity db [:seon.task/id id]))))] [:db/add by :seon.agent/agents k])
                     (when k (cond-> {:db/id k :seon.agent/key k :seon.agent/task [:seon.task/id id]
                                      :seon.agent/branch branch :seon.agent/boot boot :seon.agent/started-ms start-ms}
                               model (assoc :seon.agent/model model)
                               holder (assoc :seon.agent/holder holder)
                               (not (:seon.agent/max-turns ae)) (assoc :seon.agent/max-turns max-turns)
                               (and (:seon.agent/max-turns ae) (pos? raise)) (assoc :seon.agent/max-turns (+ (:seon.agent/max-turns ae) raise))))
                     (when (and ae (not model) (:seon.agent/model ae)) [:db.fn/retractAttribute [:seon.agent/key k] :seon.agent/model])
                     (when (and ae (not holder) (:seon.agent/holder ae)) [:db.fn/retractAttribute [:seon.agent/key k] :seon.agent/holder])
                     (when (and ae (:seon.agent/ended-ms ae)) [:db.fn/retractAttribute [:seon.agent/key k] :seon.agent/ended-ms])])))))

(defn refused-tx
  "Root refused to spawn task id's agent (the typed error e: the branch is held, or too many live agents): the task's outcome
  :refused with root's reason and the error, from what it was; no agent is made. See docs/reconcile.md#a-task-holds-how-its-work-ended."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/id :map] [:vector :any]]}
  [db id e]
  (outcome-tx db id (:seon.task/outcome (d/entity db [:seon.task/id id])) :refused
              {:seon.task/reason (:seon.error/message e) :seon.task/error e}))

(defn carry-tx
  "The task content (seon.merge/carried-platform-attrs) branch changed since base, carried
  onto main (DECISIONS, A task edited on two live branches is a conflict for root (D4)): a member
  the branch changed and main did not overwrites
  main's, so the next agents start from it; one main also changed since base, to another
  value, is a conflict for root (never resolved here). Tasks main does not hold are left.
  A set member (the proofs) is compared and written as a set. Answers {:tx :conflicts [{:task
  :attr :base :ours :theirs}]}. Cost: the carried datoms of
  the three dbs, read once."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :seon.host/db] [:map [:tx [:vector :any]] [:conflicts :seon.task/task-conflicts]]]}
  [base branch main]
  (let [many (fn [a] (dbu/multival? main a))
        ;; each member's value: a set member's values as a set, a single member's value
        vs (fn [db] (reduce (fn [m [k v]] (if (many (second k)) (update m k (fnil conj #{}) v) (assoc m k v))) {}
                            (for [a (sort pm/carried-platform-attrs) dt (d/datoms db :aevt a)] [[(:e dt) a] (:v dt)])))
        b (vs base) o (vs branch) m (vs main)
        ;; the facts that make member a of task id hold ov where main holds mv
        write (fn [id a ov mv] (if (many a)
                                 (concat (for [v (sort-by str (remove (set ov) mv))] [:db/retract [:seon.task/id id] a v])
                                         (for [v (sort-by str (remove (set mv) ov))] [:db/add [:seon.task/id id] a v]))
                                 [(if (some? ov) [:db/add [:seon.task/id id] a ov] [:db/retract [:seon.task/id id] a mv])]))]
    (reduce (fn [acc [e a :as k]]
              (let [id (:seon.task/id (d/entity branch e)) bv (b k) ov (o k) mv (m k)]
                (cond (or (nil? id) (nil? (pdb/eid main :seon.task/id id)) (= mv ov)) acc
                      (= mv bv) (update acc :tx into (write id a ov mv))
                      :else (update acc :conflicts conj {:task id :attr a :base bv :ours ov :theirs mv}))))
            {:tx [] :conflicts []}
            (sort (for [k (into (set (keys b)) (keys o)) :when (not= (b k) (o k))] k)))))

(defn member-tx
  "The facts that make member of task t (as seon.task/task reads it) (apply f its value args), as
  my.task/update! writes them: a set member (:seon.task/proofs) its values added and retracted, a
  single member its new value. A value its member's schema does not admit (proofs that are no
  set of qualified symbols, a plan that is no string) is the update's contract error, writing nothing."
  {:malli/schema [:=> [:cat [:map [:seon.task/id :seon.task/id]] [:enum :seon.task/plan :seon.task/instructions :seon.task/proofs :seon.task/cause] :seon.host/fn [:sequential :any]]
                  [:or [:vector :any] :seon.task/invalid-member]]}
  [t member f args]
  (let [id [:seon.task/id (:seon.task/id t)]
        old (if (= :seon.task/proofs member) (set (get t member)) (get t member))
        new (apply f old args)]
    (cond
      (not (contract/valid? member new))
      (contract/error :seon.task/invalid-member #'seon.task/member-tx (str member " would be a value its schema does not admit: :value") {:member member :value new})
      (= :seon.task/proofs member)
      (into (mapv #(vector :db/retract id member %) (sort-by str (remove new old)))
            (map #(vector :db/add id member %)) (sort-by str (remove old new)))
      :else [[:db/add id member new]])))

(defn onto-tx
  "The facts that put task id as main holds it, and every task it needs (transitively) that
  branch does not hold, onto branch: a kept branch resumed for a run starts from main's
  task. Root released it after holding it (seon.reconcile/hold-tx), so main's holds the
  failure and evidence the last run left, root's edits, and the branch's own carried at
  its run's end (carry-tx); or the branch was forked before main held the task or its
  needs. Another task the branch holds is left as the branch has it. Empty when the branch
  holds them all as main does. Cost: the closure's tasks, pulled once each."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :seon.task/id] [:vector :map]]}
  [main branch id]
  (let [;; how a task's work stands and ended is main's alone (judged-attrs): a branch's copy holds the task, not that
        pull #(apply dissoc (d/pull %1 '[* {:seon.task/needs [:seon.task/id]}] [:seon.task/id %2])
                     :seon.task/agent :seon.task/outcome :seon.task/reasons judged-attrs)
        held? #(and (pdb/eid branch :seon.task/id %)
                    (or (not= id %) (= (dissoc (pull main %) :db/id) (dissoc (pull branch %) :db/id))))
        closure (loop [todo [id] seen #{}]
                  (if-let [[x & more] (seq todo)]
                    (if (or (seen x) (held? x))
                      (recur more seen)
                      (recur (into (vec more) (map :seon.task/id (:seon.task/needs (d/pull main '[{:seon.task/needs [:seon.task/id]}] [:seon.task/id x]))))
                             (conj seen x)))
                    seen))]
    (vec (for [x (sort closure)
               :let [t (pull main x)]]
           (cond-> (-> t (dissoc :db/id :seon.task/needs) (assoc :db/id x))
             (seq (:seon.task/needs t))
             (assoc :seon.task/needs (mapv (fn [{n :seon.task/id}] (if (closure n) n [:seon.task/id n])) (:seon.task/needs t))))))))

;; ---- the reader ------------------------------------------------------------------

(def ^:private task-pull '[* {:seon.task/needs [:seon.task/id]} {:seon.task/agent [:seon.agent/key]}])

(defn- task-value
  "A pulled task's own members (needs as a set of ids, proofs and references sets), without
  the declaration its file stated (load-tx's to compare). The plan's embedding is its own
  entity (docs/vector.md#a-vector-is-its-own-entity), never a member."
  {:malli/schema [:=> [:cat :seon.task/pulled] :seon.task/own]}
  [t]
  (cond-> (-> t
              (dissoc :db/id :seon.task/declared)
              (update :seon.task/needs #(set (map :seon.task/id %)))
              (update :seon.task/proofs set)
              (update :seon.task/kind #(or % :work)))
    (:seon.task/agent t) (update :seon.task/agent :seon.agent/key)
    ;; a pull answers a card-many attribute as a vector; these are sets on the task's schema
    (:seon.task/deferred t) (update :seon.task/deferred set)
    (:seon.task/changed t) (update :seon.task/changed set)
    (:seon.task/unmerged t) (update :seon.task/unmerged set)))

(def placeholder-assertion
  "The assertion seon.ctx writes into a proof's test that does not exist yet (its :proof-unknown
  `do`); seon.problems/uncited-tasks reads a proof still holding it as a placeholder."
  "write the assertion: the behaviour, on data")

(defn accumulate
  "Task id's accumulated plan and proofs over its needs (owner: a task inherits its
  prerequisites' plan and proofs and adds its own, so a refinement only tightens): {:plan text
  :proofs set}; what it cites is its cites' and its needs' (the task-fact's :cites joins, docs/ctx.md#the-pattern).
  own: id -> own task value."
  {:malli/schema [:=> [:cat ifn? :seon.task/id] [:map [:plan :string] [:proofs :seon.task/proofs]]]}
  [own id]
  (let [order (fn order [acc id]
                ;; prerequisites first, each once (a diamond reaches one twice)
                (if (some #{id} acc) acc
                    (conj (reduce order acc (sort (:seon.task/needs (own id)))) id)))
        chain (filter own (order [] id))]
    ;; a merge task's plan is about merging: it is its own, never a later task's (T5b: a work
    ;; agent was told to merge); its proofs flow on like every task's
    {:plan (str/join "\n\n" (distinct (remove str/blank? (map #(:seon.task/plan (own %))
                                                               (remove #(and (not= % id) (= :merge (:seon.task/kind (own %)))) chain)))))
     :proofs (into #{} (mapcat #(:seon.task/proofs (own %))) chain)}))

(defn pulled-accumulated
  "Pulled task t's accumulated plan and proofs (`accumulate`), over the needs its record joins (:seon.task/needs,
  one level: a pattern does not reach itself, docs/ctx.md#the-pattern): what the task part and the status
  read from ctx, never the db. {:plan text :proofs set}."
  {:malli/schema [:=> [:cat [:map [:seon.task/id :seon.task/id]]] [:map [:plan :string] [:proofs :seon.task/proofs]]]}
  [t]
  (let [own-of (fn [x] (-> (select-keys x [:seon.task/id :seon.task/plan :seon.task/kind :seon.task/proofs])
                           (update :seon.task/proofs set) (update :seon.task/kind #(or % :work))))
        needs (filter :seon.task/id (vals (:seon.task/needs t)))
        own (into {(:seon.task/id t) (assoc (own-of t) :seon.task/needs (set (map :seon.task/id needs)))}
                  (for [n needs] [(:seon.task/id n) (assoc (own-of n) :seon.task/needs #{})]))]
    (accumulate own (:seon.task/id t))))

(defn tasks
  "Every task on db, by id, each with its :seon.task/accumulated plan and proofs."
  {:malli/schema [:=> [:cat :seon.host/db] [:map-of :seon.task/id :seon.task/task]]}
  [db]
  (let [own (into {} (for [t (d/q '[:find [(pull ?e pattern) ...] :in $ pattern :where [?e :seon.task/id]] db task-pull)]
                       [(:seon.task/id t) (task-value t)]))]
    (into {} (for [[id t] own] [id (assoc t :seon.task/accumulated (accumulate own id))]))))

(defn task
  "Task id on db with its :seon.task/accumulated plan and proofs, or nil (reads id and
  its prerequisites only)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/id] [:maybe :seon.task/task]]}
  [db id]
  (let [own (fn [i] (some-> (d/pull db task-pull [:seon.task/id i]) task-value))]
    ;; the value names its schema, so a page draws it by its declared render (seon.render/hiccup)
    (some-> (own id) (assoc :seon.task/accumulated (accumulate own id)) (vary-meta assoc :seon.render/schema :seon.task/task))))

(def ^:private agent-pull "The attributes of an agent the reconciler's index reads. See docs/reconcile.md#a-task-holds-how-its-work-ended."
  '[:seon.agent/key :seon.agent/branch :seon.agent/model :seon.agent/holder :seon.agent/max-turns
                           :seon.agent/boot :seon.agent/started-ms])

(defn- agent-value
  "A pulled agent as :seon.reconcile/agent: what root decides on of it, its environment (the one desired-env compares)
  beside its boot."
  {:malli/schema [:=> [:cat :seon.db/agent-fact] :seon.reconcile/agent]}
  [a]
  (let [{:seon.agent/keys [branch model max-turns holder]} a]
    ;; an environment only with a branch and max-turns: a task's start always writes both (seon.task/start-tx), an agent
    ;; root starts with no task has neither. A branch alone is a valid agent-fact its contract must answer for (the
    ;; generated check), so it has no environment (the audit of 2026-11-09 asked for a loud refusal: the contract is no place for it)
    (cond-> (select-keys a [:seon.agent/key :seon.agent/boot])
      (and branch max-turns) (assoc :seon.agent/env (cond-> {:branch branch :model model :max-turns max-turns} holder (assoc :holder holder))))))

(defn agents
  "Every agent root started on db, by key: the entities a start recorded (start-tx: :seon.agent/boot among them). See docs/reconcile.md#a-task-holds-how-its-work-ended."
  {:malli/schema [:=> [:cat :seon.host/db] [:map-of :string :seon.reconcile/agent]]}
  [db]
  (into {} (for [{e :e} (d/datoms db :aevt :seon.agent/boot)
                 :let [a (d/pull db agent-pull e)]]
             [(:seon.agent/key a) (agent-value a)])))

(def finished
  "Outcomes that make its task done: a task's outcomes are declared once, on :seon.task/outcome's schema, and read
  once, here (seon.main-test/a-tasks-outcomes-are-read-once)."
  (:seon.status/finished (contract/properties :seon.task/outcome)))

(def judged "Outcomes root has the next step to take on (a merge ready, or at its gate)." (:seon.status/judged (contract/properties :seon.task/outcome)))

(def failed "Outcomes of a death (its agent died or was lost)." (:seon.status/failed (contract/properties :seon.task/outcome)))

(defn done?
  "Has task entity te finished (finished: done or merged)? One datom read; the reconciler's index
  answers the same from its own facts (seon.reconcile/task-state)."
  {:malli/schema [:=> [:cat :seon.host/db :int] :boolean]}
  [db te]
  (boolean (finished (:v (first (d/datoms db :eavt te :seon.task/outcome))))))

(defn running
  "The tasks whose agents work on db (outcome :running), each as {:task id :agent the agent's id :key its key :env its
  environment}: one AVET seek of the outcome, the task and its agent pulled."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector [:map [:task :seon.task/id] [:agent {:optional true} :keyword] [:key {:optional true} :string] [:env {:optional true} :seon.task/env]]]]}
  [db]
  (vec (for [{e :e} (d/datoms db :avet :seon.task/outcome :running)
             :let [t (d/pull db [:seon.task/id {:seon.task/agent agent-pull}] e)
                   a (some-> (:seon.task/agent t) agent-value)]]
         (cond-> {:task (:seon.task/id t)}
           a (assoc :agent (keyword (:seon.agent/key a)) :key (:seon.agent/key a) :env (:seon.agent/env a))))))

(defn root
  "Root's boot and the config keys it reads on db: {:boot :max-running :max-merging :gate-checkout :started-by}, the config's
  from the entity :seon.root/config refs (a key it lacks is absent), nil before the first boot."
  {:malli/schema [:=> [:cat :seon.host/db] [:maybe [:map [:boot :string] [:max-running {:optional true} :int] [:max-merging {:optional true} :int]
                                                     [:gate-checkout {:optional true} :string] [:started-by {:optional true} :string]]]]}
  [db]
  (when-let [r (d/pull db [:seon.root/boot :seon.root/started-by {:seon.root/config [:seon.config.agent/max-running :seon.config.merge/max-running :seon.config.source/gate-checkout]}]
                       [:seon.root/id :root])]
    (let [c (:seon.root/config r)]
      (cond-> {:boot (:seon.root/boot r)}
        (:seon.config.agent/max-running c) (assoc :max-running (:seon.config.agent/max-running c))
        (:seon.config.merge/max-running c) (assoc :max-merging (:seon.config.merge/max-running c))
        (:seon.config.source/gate-checkout c) (assoc :gate-checkout (:seon.config.source/gate-checkout c))
        (:seon.root/started-by r) (assoc :started-by (:seon.root/started-by r))))))

(defn proof?
  "Does task t (as `task` answers it) owe test var v green: its accumulated proofs, each a test var, never excused as main's red. See docs/merging.md#a-merge-runs-what-the-change-reaches."
  {:malli/schema [:=> [:cat [:maybe :map] :qualified-symbol] :boolean]}
  [t v] (contains? (set (:proofs (:seon.task/accumulated t))) v))

(defn unjudged
  "Of tests, the process tests this branch's change reaches, those no gate will run for task t on db, and why:
  {:tests :why}, or nil when the gate runs them all. A design task may leave its proofs red; a root with no checkout has
  nowhere to run them; a test can't be its own gate (:seon.root/started-by). See docs/merging.md#a-merge-runs-what-the-change-reaches."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :map] [:sequential :qualified-symbol]] [:maybe [:map [:tests [:vector :qualified-symbol]] [:why :string]]]]}
  [db t tests]
  (let [{:keys [gate-checkout started-by]} (root db)
        all (fn [why] {:tests (vec tests) :why why})]
    (cond
      (empty? tests) nil
      (not (#{:work :merge :whole} (:seon.task/kind t))) (all (str "a " (some-> (:seon.task/kind t) name) " task isn't judged by the gate"))
      (nil? gate-checkout) (all "this root has no checkout to run them on (:seon.config.source/gate-checkout, or a source root)")
      (some #(= started-by (str %)) tests) {:tests (filterv #(= started-by (str %)) tests) :why "this root was started by it, and a test can't be its own gate"})))

(defn entity-value
  "The task, agent or root that entity e of db is now, as the reconciler's index holds it:
  [:task own-value] (no accumulation), [:agent agent-value], [:root (root db)], or nil
  when e is none of them (retracted, another kind, an agent root never started, or root
  before its first boot: (root db) is nil then, as on a store an earlier build left with
  a quiescent fact and no boot). One pull of e."
  {:malli/schema [:=> [:cat :seon.host/db :int]
                  [:maybe [:or [:tuple [:= :task] :seon.task/own] [:tuple [:= :agent] :seon.reconcile/agent] [:tuple [:= :root] :map]]]]}
  [db e]
  (let [k (d/pull db [:seon.task/id :seon.agent/boot :seon.root/id] e)]
    (cond (:seon.task/id k) [:task (task-value (d/pull db task-pull e))]
          ;; an agent its start recorded: a branch's record of its own agent, with no boot, is none
          (:seon.agent/boot k) [:agent (agent-value (d/pull db agent-pull e))]
          (:seon.root/id k) (some->> (root db) (vector :root)))))

(defn tasks-of-agent
  "The task entities db names agent entity e as their agent (:seon.task/agent): the tasks whose state an agent's boot moves.
  An agent holds two on a stage. See docs/reconcile.md#the-reconcilers-index."
  {:malli/schema [:=> [:cat :seon.host/db :int] [:vector :int]]}
  [db e]
  (mapv :e (d/datoms db :avet :seon.task/agent e)))

(defn entity-ids
  "Every task, agent and root entity of db (the reconciler's list, before it watches)."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :int]]}
  [db]
  (vec (concat (map :e (d/datoms db :aevt :seon.root/id)) (map :e (d/datoms db :aevt :seon.task/id))
               (map :e (d/datoms db :aevt :seon.agent/boot)))))

;; ---- task files: declarations as reviewable data -----------------------------------
;; resources/seon/tasks/<ns>.edn holds one vector of :seon.task/declaration entries (without
;; :seon.task/ns: the file's name is the owning namespace). Read with clojure.edn, nothing
;; evaluated. Loading reconciles the files into main: a new entry is created held; an entry
;; equal to the declaration main remembers (:seon.task/declared) writes nothing, so what main
;; learned since (the improved plan) stays; a changed entry writes what it states and
;; retracts what it dropped; an entry no file declares any more is cancelled and forgets its
;; declaration, so declaring it again revives it. Execution is root's (my.root/run!), except
;; that revival.

(def ^:private stated-attributes
  "The task attributes a declaration states (its members, config flattened), as stored."
  [:seon.task/plan :seon.task/instructions :seon.task/proofs :seon.task/branch :seon.task/model
   :seon.task/kind :seon.task/evidence :seon.task/examples
   :seon.config.agent/max-turns :seon.config.merge/max-conflicts :seon.config.merge/max-merge-turns])

(defn- file-refused
  {:malli/schema [:=> [:cat :symbol :string] :map]}
  [ns-sym m] (invalid #'seon.task/file-refused (str "task file " ns-sym ".edn: " m)))

(defn file-declarations
  "The declarations of task file text, owned by namespace ns-sym (the file's name), each
  with its :seon.task/ns; or the :seon.task/refused error when the text is not exactly one
  EDN vector of declarations."
  {:malli/schema [:=> [:cat :symbol :string]
                  [:or [:vector :seon.task/declaration] [:map [:seon.error/type [:= :seon.task/refused]] [:seon.error/message :string]]]]}
  [ns-sym text]
  (let [r (java.io.PushbackReader. (java.io.StringReader. text))
        forms (try (vec (take-while #(not= ::eof %) (repeatedly #(edn/read {:eof ::eof} r))))
                   (catch Exception e (ex-message e)))]
    (cond
      (string? forms) (file-refused ns-sym (str "not readable EDN: " forms))
      (not (and (= 1 (count forms)) (vector? (first forms)))) (file-refused ns-sym "not exactly one vector of declarations")
      :else (let [ds (mapv #(if (map? %) (assoc % :seon.task/ns ns-sym) %) (first forms))]
              (if-let [bad (first (remove #(contract/valid? :seon.task/declaration %) ds))]
                (file-refused ns-sym (str "an entry is not a :seon.task/declaration: " (print/text bad (:part print/budgets))))
                ds)))))

(defn- held-tasks
  "What load-tx judges of the tasks on db, id -> {:seon.task/ns :seon.task/execution :seon.task/needs (ids)}: four
  :aevt scans (~2.5 MB for 452 tasks), where `tasks` pulled every member of every task (46 MB) for these four."
  {:malli/schema [:=> [:cat :seon.host/db] [:map-of :seon.task/id :map]]}
  [db]
  (let [scan (fn [a] (reduce (fn [m dt] (update m (:e dt) (fnil conj []) (:v dt))) {} (d/datoms db :aevt a)))
        ids (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.task/id))
        ns-of (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.task/ns))
        ex-of (into {} (map (juxt :e :v)) (d/datoms db :aevt :seon.task/execution))
        needs (scan :seon.task/needs)]
    (into {} (for [[e id] ids]
               [id (cond-> {:seon.task/needs (into #{} (map ids) (needs e))}
                     (ns-of e) (assoc :seon.task/ns (ns-of e))
                     (ex-of e) (assoc :seon.task/execution (ex-of e)))]))))

(defn load-tx
  "The transaction reconciling task files into main db. files: {owning-ns declarations or
  error}, every file read (a file not given is not judged). Answers the tx-data, or the
  first error: a file's own, or needs that name no task or form a cycle."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :symbol :any]] [:or [:vector :any] [:map [:seon.error/type :keyword]]]]}
  [db files]
  (if-let [r (first (filter :seon.error/type (vals files)))]
    r
    (let [decls (into {} (for [ds (vals files) d ds] [(task-id (:seon.task/ns d) (:seon.task/name d)) d]))
          owners (set (keys files))
          stored (into {} (d/q '[:find ?id ?s :where [?e :seon.task/declared ?s] [?e :seon.task/id ?id]] db))
          all (held-tasks db)
          new (vec (for [[id d] decls :when (not (all id))] d))
          changed (vec (for [[id d] decls :when (and (all id) (not= d (some-> (stored id) sedn/decode)))] [id d]))
          removed (vec (for [[id _] stored :when (and (not (decls id)) (owners (:seon.task/ns (all id))))] id))
          graph (merge (into {} (for [[id t] all] [id (:seon.task/needs t)]))
                       (into {} (for [[id d] decls] [id (set (:seon.task/needs d))])))
          declared (fn [id d] {:seon.task/id id :seon.task/declared (sedn/encode d)})]
      (if-let [p (needs-problem graph graph)]
        (file-refused (first owners) p)
        (let [created (when (seq new) (create-tx db new))
              ;; a changed task's text may cite a task this write makes: its cites are read on the db the new tasks make,
              ;; written after them, so the lookup resolves in the write
              db' (if (seq new) (:db-after (pdb/speculate db (filterv #(and (map? %) (:seon.task/id %)) created))) db)]
        (vec (concat
               created
               (for [d new] (declared (task-id (:seon.task/ns d) (:seon.task/name d)) d))
               (mapcat (fn [[id d]]
                         ;; what it states now: members, config flattened, references derived, branch
                         ;; defaulted, needs as entity ids (a task new in this write by its tempid, its id)
                         (let [want (-> (merge (dissoc d :seon.task/ns :seon.task/name :seon.task/config) (:seon.task/config d))
                                        (update :seon.task/branch #(or % (default-branch id)))
                                        (assoc :seon.task/needs (into #{} (map #(or (pdb/eid db :seon.task/id %) %)) (:seon.task/needs d))))]
                           (cond-> (-> (entity-tx db (pdb/eid db :seon.task/id id) (conj stated-attributes :seon.task/needs) want)
                                       (conj [:db/add [:seon.task/id id] :seon.task/declared (sedn/encode d)])
                                       ;; what its text cites now
                                       (into (cites-tx db' [:seon.task/id id] id (text-citations db' d))))
                             ;; declared again after its removal: revived
                             (and (nil? (stored id)) (= :cancelled (:seon.task/execution (all id))))
                             (conj [:db/add [:seon.task/id id] :seon.task/execution :held]))))
                       changed)
               (mapcat (fn [id] [[:db/add [:seon.task/id id] :seon.task/execution :cancelled]
                                 [:db/retract [:seon.task/id id] :seon.task/declared (stored id)]])
                       removed))))))))
