(ns seon.oversight
  "Live fleet oversight, derived from Flow ping and database facts.

  Ping is the process-local half of the render census. The database
  already says which agents and runs exist; Flow says what their
  replaceable compute is doing right now. This namespace joins those
  two values into one render unit and never commits the result.

  The caller supplies the cluster's routing entry it already holds; the
  cluster graph rides that entry from the moment the graph is joined, before
  any proc resumes. A request without routing is a detached render: `unit`
  returns nil because historical or detached facts have no live graph to
  describe. A request WITH routing that cannot answer (no joined graph, no
  armed map) is `:seon.oversight/unavailable`, rendered visibly; only a
  present empty armed map means an empty fleet.

  Every armed agent graph contributes its mailbox and turn ping. A
  responsive turn proc with no current turn is parked; an open turn is
  mid-turn. Without either observation its state is unknown. Turn counts are
  derived from the same immutable database value. Buffer occupancy is
  Flow's channel data, and the cluster graph contributes the ordinary
  proc pass counts for the armer and render plumbing.

  Crash walk: every value here is disposable. A killed process takes
  its pings with it; the replacement's next render derives a new unit."
  (:require [clojure.core.async.flow :as flow]
            [clojure.datafy :as datafy]
            [clojure.string :as str]
            [seon.turn :as turn]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.render.block :as block]))

;;; Flow's ping contract is explicitly timeout-bounded because an active
;;; transform cannot answer until it returns. The window is a config fact;
;;; absence of a reply remains unknown rather than becoming a health claim.

(defn- context-cluster-name
  "The cluster the carried context's environment names, or nil.

  A forked branch holds its parent's cluster rows too, so no database row
  can name the cluster; the caller's context environment is the one place."
  {:malli/schema [:=> [:cat [:maybe :seon.sci.eval/ctx]] [:maybe :seon.boot/cluster-name]]}
  [ctx]
  (:seon.boot/cluster-name (env/of ctx)))

(defn- current-run-id
  "The agent's open turn id, or nil when none is open."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:maybe :seon.turn/id]]}
  [db agent-id]
  (db/q '[:find ?run-id .
         :in $ ?agent-id
         :where
         [?agent :seon.agent/id ?agent-id]
         [?run :seon.turn/agent ?agent]
         (not [?run :seon.turn/closed-tx])
         [?run :seon.turn/id ?run-id]]
       db agent-id))

(defn- occupancy
  "The count and capacity from one datafied Flow channel, or nil."
  {:malli/schema [:=> [:cat [:maybe :seon.oversight/flow-channel]]
                  [:maybe :seon.oversight/occupancy]]}
  [channel]
  (when-let [buffer (:buffer channel)]
    (cond-> {:seon.oversight/count (:count buffer)
             :seon.oversight/capacity (:capacity buffer)}
      (int? (:dropped buffer))
      (assoc :seon.oversight/dropped (:dropped buffer)))))

(defn proc-ping
  "Project one expected Flow proc and its optional ping reply.

  A missing reply is explicitly unknown; presence in the graph never implies
  health."
  {:malli/schema [:=> [:cat :seon.oversight/proc [:maybe :seon.oversight/flow-reply]]
                  :seon.oversight/proc-observation]}
  [proc-id reply]
  (if reply
    {:seon.oversight/proc proc-id
     :seon.oversight/ping :reply
     :seon.oversight/passes (::flow/count reply)
     :seon.oversight/buffers
     (into []
           (keep (fn [[port channel]]
                   (when-let [found (occupancy channel)]
                     (assoc found :seon.oversight/port port))))
           (::flow/ins reply))}
    {:seon.oversight/proc proc-id
     :seon.oversight/ping :unknown}))

(defn- graph-procs
  "Every proc in `graph`, observed through one bounded ping: `:stopped` for
  each proc of a stopped graph, which holds no channels (datafy's `:chans`,
  flow/impl.clj:91) and whose ping throws (impl.clj:70). One stopped graph
  is an observation of that graph, never a failure of the whole status."
  {:malli/schema [:=> [:cat :seon.flow/graph :seon.config.flow/ping-timeout-ms]
                  [:tuple [:maybe [:map-of :keyword :seon.oversight/flow-reply]]
                   :seon.oversight/procs]]}
  [graph timeout-ms]
  (let [data (datafy/datafy graph)
        pids (sort (keys (:procs data)))]
    (if (seq (:chans data))
      (let [ping (flow/ping graph :timeout-ms timeout-ms)]
        [ping (mapv #(proc-ping % (get ping %)) pids)])
      [nil (mapv #(hash-map :seon.oversight/proc % :seon.oversight/ping :stopped) pids)])))

(defn- agent-story
  "One armed agent's current story, including every declared proc."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.config.flow/ping-timeout-ms
                       :seon.agent/id :seon.agent/armed]
                  :seon.oversight/agent]}
  [db timeout-ms agent-id entry]
  (let [[ping procs] (graph-procs (:seon.flow/graph entry) timeout-ms)
        mailbox (get ping :seon.agent/mailbox)
        turn (get ping :seon.agent/turn)
        run-id (current-run-id db agent-id)
        mailbox-occupancy
        (occupancy (get-in mailbox [::flow/ins :seon.agent/wake]))
        turn-occupancy
        (occupancy
         (or (get-in turn [::flow/ins :seon.agent/episode])
             ;; While the turn transform is active it cannot pong. The
             ;; mailbox's out is the same direct 1:1 channel, so occupancy
             ;; remains observable without inventing another counter.
             (get-in mailbox [::flow/outs :seon.agent/episode])))]
    (cond-> {:seon.agent/id agent-id
             :seon.oversight/procs procs
             :seon.turn.work/episode-runs
             (turn/episode-runs db agent-id)}
      run-id
      (assoc :seon.turn/id run-id)

      mailbox-occupancy
      (assoc :seon.oversight/mailbox mailbox-occupancy)

      turn-occupancy
      (assoc :seon.oversight/turn-buffer turn-occupancy)

      mailbox
      (assoc :seon.oversight/mailbox-passes (::flow/count mailbox))

      turn
      (assoc :seon.oversight/turn-passes (::flow/count turn)))))

(defn- plumbing-story
  "Every proc in the cluster graph, including a proc busy rendering us."
  {:malli/schema [:=> [:cat :seon.flow/graph :seon.config.flow/ping-timeout-ms]
                  :seon.oversight/plumbing]}
  [graph timeout-ms]
  ;; The render proc observes itself as unknown during a feed pass: it
  ;; cannot answer until the projection returns. Presence in the graph
  ;; plus absence of a pong never becomes a health claim.
  (second (graph-procs graph timeout-ms)))

(defn- live-state
  "Read the routing entry once: its live state, or the members it lacks.
  Both members are required by `:seon.oversight/live-state`; a missing one
  is unavailable evidence, never an empty fleet."
  {:malli/schema [:=> [:cat :seon.agent/routing]
                  [:or :seon.oversight/live-state :seon.oversight/unavailable]]}
  [routing]
  (let [snapshot @routing
        missing (into [] (remove #(find snapshot %))
                      [:seon.agent/armed :seon.flow/graph])]
    (if (seq missing)
      {:seon.oversight/missing missing}
      snapshot)))

(defn- fleet-value
  "The complete process-local fleet value at one database value, or the
  routing entry's declared unavailability, or the named cluster's config
  refusal. A caller naming no cluster lacks `:seon.boot/cluster-name`."
  {:malli/schema [:=> [:cat :seon.db/database-value [:maybe :seon.boot/cluster-name]
                       :seon.agent/routing]
                  [:or :seon.oversight/fleet :seon.oversight/unavailable
                   :seon.config/error :seon.schema/validation-refusal
                   :seon.db/invalid-read-error]]}
  [db cluster-name routing]
  (let [state (live-state routing)
        effective (when (and cluster-name (not (:seon.oversight/missing state)))
                    (config/effective db cluster-name))]
    (cond
      (:seon.oversight/missing state) state
      (nil? cluster-name) {:seon.oversight/missing [:seon.boot/cluster-name]}
      (:seon.error/at effective) effective
      :else
      (let [timeout-ms (:seon.config.flow/ping-timeout-ms effective)]
        {:seon.oversight/agents
         (into []
               (map (fn [[agent-id entry]]
                      (agent-story db timeout-ms agent-id entry)))
               (sort-by key (:seon.agent/armed state)))
         :seon.oversight/plumbing
         (plumbing-story (:seon.flow/graph state) timeout-ms)}))))

(defn flow-status
  "Return the current agent and plumbing Flow observations for one instance,
  under the cluster its context's environment names."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.boot/instance]
                  [:or :seon.oversight/fleet :seon.oversight/unavailable
                   :seon.config/error :seon.schema/validation-refusal
                   :seon.db/invalid-read-error]]}
  [db instance]
  (fleet-value db (context-cluster-name (:seon.sci.eval/ctx instance))
               (:seon.agent/routing instance)))

(defn unit
  "Build the live fleet render unit; nil only for a detached request."
  {:malli/schema [:=> [:cat :seon.oversight/request]
                  [:maybe :seon.oversight/unit]]}
  [source]
  (when-let [routing (:seon.agent/routing source)]
    (assoc source
           :seon.render/value (fleet-value (:seon.db/db source)
                                           (context-cluster-name (:seon.sci.eval/ctx source))
                                           routing)
           ;; The producer's declared return names the pair (:seon.oversight/fleet).
           :seon.render.call/function `fleet-value)))

(defn- ordinal
  "An English ordinal for a positive run count."
  {:malli/schema [:=> [:cat [:int {:min 1}]] :string]}
  [value]
  (let [n (long value)
        mod100 (mod n 100)
        suffix (if (<= 11 mod100 13)
                 "th"
                 (case (mod n 10)
                   1 "st"
                   2 "nd"
                   3 "rd"
                   "th"))]
    (str n suffix)))

(defn- agent-story-text
  "A stopped graph is stopped, whatever turn it left open; an open turn
  proves work; no turn plus a pong proves parked; else unknown."
  {:malli/schema [:=> [:cat :seon.oversight/agent] [:enum "stopped" "mid-turn" "parked" "unknown"]]}
  [agent]
  (cond
    (some #(= :stopped (:seon.oversight/ping %)) (:seon.oversight/procs agent)) "stopped"
    (:seon.turn/id agent) "mid-turn"
    (some? (:seon.oversight/turn-passes agent)) "parked"
    :else "unknown"))

(defn- unavailable-text
  "Name what the live fleet value lacked: routing members, the caller's
  cluster name, or the named cluster's configuration (its refusal)."
  {:malli/schema [:=> [:cat [:or :seon.oversight/unavailable :seon.error/value]] :string]}
  [unavailable]
  (if-let [missing (:seon.oversight/missing unavailable)]
    (str "Live fleet state is unavailable: the request lacks "
         (str/join ", " missing) ".")
    (str "Live fleet state is unavailable: "
         (or (:seon.error/message unavailable) (pr-str (:seon.error/operation unavailable))))))

(defn ai-story
  "Tell the fleet's current story in one concise line."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
  [unit]
  (let [value (:seon.render/value unit)
        agents (:seon.oversight/agents value)]
    (cond
      (or (:seon.oversight/missing value) (:seon.error/at value))
      (unavailable-text value)

      (empty? agents)
      "No agent graphs are armed."

      :else
      (str/join
       "; "
       (map
        (fn [agent]
          (let [agent-id (:seon.agent/id agent)
                story (agent-story-text agent)
                run-id (:seon.turn/id agent)
                episode-runs (:seon.turn.work/episode-runs agent)]
            (if (= "parked" story)
              (str agent-id ": parked")
              (str agent-id ": " story
                   (when run-id (str " on turn " run-id))
                   (when (and run-id (pos? episode-runs))
                     (str ", " (ordinal episode-runs)
                          " turn since the outside wake"))))))
        agents)))))

(defn- occupancy-text
  "A compact `count/capacity` channel readout."
  {:malli/schema [:=> [:cat [:maybe :seon.oversight/occupancy]] :string]}
  [found]
  (if found
    (str (:seon.oversight/count found)
         "/"
         (:seon.oversight/capacity found)
         (when (pos? (or (:seon.oversight/dropped found) 0))
           (str "; " (:seon.oversight/dropped found) " dropped")))
    "—"))

(defn html-table
  "Render the fleet story as the root page's live table."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [value (:seon.render/value unit)
        agents (:seon.oversight/agents value)
        plumbing (:seon.oversight/plumbing value)]
    (if (or (:seon.oversight/missing value) (:seon.error/at value))
      [:section {:id (block/surface-id :fleet-oversight)
                 :class "seon-card"}
       [:h2 "fleet"]
       [:p {:data-fleet-oversight "unavailable"
            :data-missing (str/join " " (or (:seon.oversight/missing value)
                                            [(:seon.error/operation value)]))}
        (unavailable-text value)]]
    [:section {:id (block/surface-id :fleet-oversight)
               :class "seon-card"}
     [:h2 "fleet"]
     [:table {:data-fleet-oversight "agents"}
      [:thead
       [:tr
        [:th "agent"]
        [:th "state"]
        [:th "current turn"]
        [:th "turn count"]
        [:th "mailbox"]
        [:th "turn buffer"]]]
      [:tbody
       (for [agent agents]
         (let [story (agent-story-text agent)]
           [:tr {:data-agent (:seon.agent/id agent)
                 :data-state story}
            [:td (:seon.agent/id agent)]
            [:td story]
            [:td (or (:seon.turn/id agent) "—")]
            [:td (:seon.turn.work/episode-runs agent)]
            [:td (occupancy-text (:seon.oversight/mailbox agent))]
            [:td (occupancy-text (:seon.oversight/turn-buffer agent))]]))]]
     [:dl
      [:dt "plumbing passes"]
      [:dd
       (str/join
        " · "
        (map (fn [proc]
               (str (:seon.oversight/proc proc)
                    " "
                    (or (:seon.oversight/passes proc)
                        (name (:seon.oversight/ping proc)))))
             plumbing))]]])))
