(ns seon.render.agent
  "The agent view — an agent's context AS the rendered view of its own
  namespace, at a distance.

  THE OWNER'S RULING, 2026-07-28 post-midnight #2, verbatim: \"I want to
  try building up the WHOLE render system this way. Namespace and
  distance centric context for agents.\" An agent's context IS
  `render(its namespace, distance N)`. This namespace is the PILOT of
  that principle at entity-graph distance — the staged half; code-graph
  hops arrive with N5's derived membership.

  It mirrors `seon.render.root` exactly, and the symmetry is the result
  rather than a coincidence: root is an agent, its page is its block
  set, and an ordinary agent's PROMPT is its block set asked for the
  other kind. So this file is a render function and a vector naming the
  blocks, and everything that makes them a PROMPT already exists —
  `seon.cluster.prompt` is untouched, because the neighbourhood view is
  simply one more block in the membership it already reduces.

  WHAT SHRANK, and this is the funeral (owner ruling: static context
  blocks shrink toward the scaffold). Two prompt blocks were restating
  neighbourhood facts and are gone, their doctrine moved into the family
  lenses where every consumer gets it:

  - `:interruption` → `seon.cluster.run/render-ai`. \"Interrupted at
    form N, k results missing, nothing was retried\" is a fact about a
    RUN. As a context block only the prompt ever saw it; as the run
    family's lens, a page, a debug view and another agent's
    neighbourhood are told the same true thing by the same function.
  - `:continuity` → `seon.cluster.run/render-receipt-ai`. The pause note
    IS the last form's admitted value, already durable in that receipt,
    so reading it back belongs to the receipt rather than to a block
    that queried around it.

  WHAT STAYED, and why it is not the same class: `:identity`,
  `:execution` and `:peers` are SCAFFOLD — who you are, how a reply is
  evaluated, how to address a peer. None is a fact reachable by
  following this agent's connections, and none goes stale, so none is
  something the walk could ever render.

  `:trigger` is no longer a scaffold block. The universal walk derives
  the `:seon.cluster.run/trigger` connection recorded on the run and
  follows it directly to the message. The run stays the ordinary owner
  of its cause rather than making a transaction a runtime unit, while the
  message a run answers is present through the same walk as every other
  neighbour.

  Crash walk: pure renders over a database value. A kill loses a prompt
  that re-derives."
  (:require [clojure.string :as str]
            [seon.ai.tokens :as tokens]
            [seon.db :as db]
            [seon.render.block :as block]
            [seon.render.route :as route]
            [seon.render.transcript :as transcript]
            [seon.render.walk :as walk]))

;;; ---------------------------------------------------------------------------
;;; The renders
;;; ---------------------------------------------------------------------------

(defn- transcript-unit
  [unit]
  (assoc unit :seon.render.transcript/token-budget
         (quot (long (get-in unit [:seon.sci.admit/caps
                                   :seon.config.eval.result/max-string]))
               tokens/chars-per-token)))

(defn agent-ai
  "`:seon.render/ai` — one agent, as its neighbours see it.

  The agent family's own lens, declared on `:seon.cluster.agent/agent`
  in `resources/seon/schema.edn`. Deliberately ONE sentence: an agent
  reached as a neighbour wants a name and whether it is busy, and
  everything else about it is its own neighbourhood's business, one hop
  further out.

  Presence is the state — an agent with no `/run` is idle, and there is
  no status attribute to read."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (when-let [id (get unit :seon.cluster.agent/id)]
    (let [status (str "Agent " id
                      (if (get unit :seon.cluster.agent/run)
                        " is running now."
                        " is idle."))
          history (when (and (:seon.db/db unit)
                             (:seon.sci.admit/caps unit))
                    (transcript/render-ai (transcript-unit unit)))]
      (str status (when (seq history) (str "\n" history))))))

(defn agent-html
  "`:seon.render/html` — one agent, with the same facts as its AI twin."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/hiccup]]}
  [unit]
  (when-let [text (agent-ai unit)]
    [:article {:class "seon-family-entry seon-agent-entry"}
     [:p (first (str/split-lines text))]
     (when (and (:seon.db/db unit) (:seon.sci.admit/caps unit))
       (transcript/render-html (transcript-unit unit)))]))

(defn agent-header-html
  "Agent identity and state, derived from presence at this database value."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [db (:seon.db/db unit)
        agent-id (:seon.cluster.agent/id unit)
        agent (when (and db agent-id)
                (db/pull db
                        [:seon.cluster.agent/id
                         :seon.cluster.agent/namespace
                         :seon.cluster.agent/run]
                        [:seon.cluster.agent/id agent-id]))
        state (cond
                (nil? (:seon.cluster.agent/id agent)) :missing
                (:seon.cluster.agent/run agent) :running
                :else :idle)
        namespace (some->> (get-in agent
                                   [:seon.cluster.agent/namespace :db/id])
                           (db/q '[:find ?name .
                                  :in $ ?namespace
                                  :where [?namespace :seon.ns/name ?name]]
                                db))]
    [:header {:id (block/surface-id :agent-header)
              :class "seon-agent-header"
              :data-agent-state (name state)}
     [:div {:class "seon-agent-heading"}
      [:a {:class "seon-agent-back" :href (route/path ::route/root)}
       "← agents"]
      [:span {:class "seon-agent-name"} (or agent-id "unknown agent")]
      (when namespace
        [:span {:class "seon-agent-namespace"} (str namespace)])]
     [:span {:class "seon-agent-state"}
      [:span {:class "seon-agent-state-dot" :aria-hidden "true"} "●"]
      [:span (name state)]]]))

(defn- agent-entity-id
  [db agent-id]
  (db/q '[:find ?agent .
         :in $ ?agent-id
         :where [?agent :seon.cluster.agent/id ?agent-id]]
       db agent-id))

(defn- transcript-entity-ids
  "Entity ids in one agent's conversation, in commit order."
  [db agent-id limit]
  (when-let [agent (agent-entity-id db agent-id)]
    (->> (concat
          (db/q '[:find ?message
                 :in $ ?agent
                 :where [?message :seon.cluster.message/to ?agent]]
               db agent)
          (db/q '[:find ?message
                 :in $ ?agent
                 :where [?message :seon.cluster.message/from ?agent]]
               db agent)
          (db/q '[:find ?run
                 :in $ ?agent
                 :where [?run :seon.cluster.run/agent ?agent]]
               db agent)
          (db/q '[:find ?receipt
                 :in $ ?agent
                 :where
                 [?run :seon.cluster.run/agent ?agent]
                 [?receipt :seon.cluster.eval/run ?run]]
               db agent)
          (db/q '[:find ?error
                 :in $ ?agent
                 :where [?error :seon.error/agent ?agent]]
               db agent)
          (db/q '[:find ?error
                 :in $ ?agent
                 :where
                 [?run :seon.cluster.run/agent ?agent]
                 [?error :seon.error/run ?run]]
               db agent))
         (into (sorted-set) (map first))
         reverse
         (take limit)
         reverse
         vec)))

(defn- render-node
  [db caps entity-id distance]
  (walk/neighborhood
   {:seon.db/db db
    :seon.render.walk/lookup entity-id
    :seon.render/kind :seon.render/html
    :seon.render/floor `block/data-panel
    :seon.render/overrides {}
    :seon.render/distance distance
    :seon.sci.admit/caps caps}))

(defn- node-output
  [node]
  (if-let [failure (:seon.error/value node)]
    [:p {:class "seon-neighborhood-error"}
     (:seon.error/message failure)]
    (:seon.render/output node)))

(defn- node-html
  [node]
  (let [children (into []
                       (keep node-html)
                       (:seon.render.walk/neighbours node))
        attribute (:seon.render.walk/attribute node)
        output (node-output node)]
    (when (or output (seq children))
      [:li {:class "seon-neighborhood-entry"}
       (when attribute
         [:span {:class "seon-neighborhood-connection"} (str attribute)])
       output
       (when (seq children)
         (into [:ul {:class "seon-neighborhood-list"}] children))])))

(defn- transcript-role
  [agent-entity unit]
  (cond
    (:seon.cluster.message/id unit)
    (let [from (get-in unit [:seon.cluster.message/from :db/id])]
      (cond
        (nil? from) :human
        (= agent-entity from) :agent
        :else :peer))

    (:seon.error/id unit) :system
    (or (:seon.cluster.run/id unit)
        (:seon.cluster.eval/id unit)) :activity
    :else :context))

(defn- transcript-entry
  [db caps agent-entity entity-id]
  (let [unit (db/pull db '[*] entity-id)
        role (transcript-role agent-entity unit)]
    [:li {:class (str "seon-transcript-entry seon-transcript-" (name role))
          :data-transcript-entity (str entity-id)
          :data-transcript-role (name role)}
     (node-output (render-node db caps entity-id 0))]))

(defn transcript-html
  "The agent's messages, runs, receipts, and errors in commit order."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [db (:seon.db/db unit)
        agent-id (:seon.cluster.agent/id unit)
        caps (:seon.sci.admit/caps unit)
        agent-entity (when (and db agent-id)
                       (agent-entity-id db agent-id))
        entity-ids (when agent-entity
                     (transcript-entity-ids
                      db agent-id
                      (:seon.config.eval.result/max-collection caps)))]
    [:section {:id (block/surface-id :transcript)
               :class "seon-transcript"}
     [:h2 {:class "seon-agent-section-label"} "transcript"]
     (if (seq entity-ids)
       (into [:ol {:class "seon-transcript-list"}]
             (map (partial transcript-entry db caps agent-entity))
             entity-ids)
       [:p {:class "seon-agent-empty"}
        (if agent-entity
          "No conversation yet. Send a message above to begin."
          "This agent does not exist. Return to the agent list.")])]))

(defn namespace-ai
  "`:seon.render/ai` — THE PILOT: this agent's world, at a distance.

  One block, and it replaces a growing family of hand-written
  \"tell the agent about X\" projections with the general mechanism: the
  agent's own entity is rendered by its lens, its connections are
  followed one hop per distance, and every neighbour is rendered by ITS
  owner's lens. A run says what a run says, a receipt says what a
  receipt says, an error says what an error says — here and on the page
  and in a debug view, by the same functions.

  THE BAR, and the reason this block is four lines of body: \"write a new
  function to change it.\" To change what an agent sees about its runs,
  an agent writes `seon.cluster.run/render-ai` — one defn, resolved late
  on the next render. To change how far it sees, it puts a different
  `:seon.render/distance` on the request. There is nothing to register
  and no API to learn, and if either statement stops being true the
  design has regressed.

  DISTANCE IS READ HERE and spent by the walk, which is exactly the call
  convention: this projection was CALLED with a hop budget and chose to
  spend it on its neighbourhood. A projection that ignored it would be
  equally correct and would simply reach nothing.

  Nil when the agent has no connections worth a sentence — the walk
  returns a node whose prose is blank, and an agent alone in a fresh
  cluster costs its prompt nothing."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (let [db (get unit :seon.db/db)
        agent-id (get unit :seon.cluster.agent/id)
        caps (get unit :seon.sci.admit/caps)]
    (when (and db agent-id caps)
      (when-let [text
                 (walk/prose db
                  (walk/neighborhood
                   (cond-> {:seon.db/db db
                            :seon.render.walk/lookup
                            [:seon.cluster.agent/id agent-id]
                            :seon.render/kind :seon.render/ai
                            :seon.render/floor `block/data-prose
                            :seon.sci.admit/caps caps}
                     (get unit :seon.render/distance)
                     (assoc :seon.render/distance
                            (get unit :seon.render/distance)))))]
        (str "Your namespace, as it stands right now:\n" text)))))

(defn namespace-html
  "`:seon.render/html` — this agent's world through the family twins."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/hiccup]]}
  [unit]
  (let [db (get unit :seon.db/db)
        agent-id (get unit :seon.cluster.agent/id)
        caps (get unit :seon.sci.admit/caps)]
    (when (and db agent-id caps)
      (when-let [content
                 (node-html
                  (walk/neighborhood
                   (cond-> {:seon.db/db db
                            :seon.render.walk/lookup
                            [:seon.cluster.agent/id agent-id]
                            :seon.render/kind :seon.render/html
                            :seon.render/floor `block/data-panel
                            :seon.render/overrides {}
                            :seon.sci.admit/caps caps}
                     (get unit :seon.render/distance)
                     (assoc :seon.render/distance
                            (get unit :seon.render/distance)))))]
        [:section {:id (block/surface-id :namespace)
                   :class "seon-card seon-neighborhood"}
         [:h2 "namespace"]
         [:ul {:class "seon-neighborhood-list"} content]]))))
