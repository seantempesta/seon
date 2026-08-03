(ns seon.oversight
  "Live fleet oversight, derived from Flow ping and database facts.

  Ping is the process-local half of the render census. The database
  already says which agents and runs exist; Flow says what their
  replaceable compute is doing right now. This namespace joins those
  two values into one render unit and never commits the result.

  The cluster instance is resolved by the database value's attached
  Datahike connection identity, never by assuming one ambient cluster.
  A caller may also supply `:seon.boot/instance` explicitly. When no
  instance owns the database value, `unit` returns nil: omission is the
  honest projection because historical or detached facts have no live
  graph to describe.

  Every armed agent graph contributes its mailbox and turn ping. A
  responsive turn proc with no current run is parked; a missing turn
  reply or a current run is mid-turn. Current run and episode count are
  derived from the same immutable database value. Buffer occupancy is
  Flow's channel data, and the cluster graph contributes the ordinary
  proc pass counts for the armer and render plumbing.

  Crash walk: every value here is disposable. A killed process takes
  its pings with it; the replacement's next render derives a new unit."
  (:require [clojure.core.async.flow :as flow]
            [clojure.datafy :as datafy]
            [clojure.string :as str]
            [seon.cluster.agent :as agent]
            [seon.cluster.work :as work]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render.block :as block]))

;;; Flow's ping contract is explicitly timeout-bounded because an active
;;; transform cannot answer until it returns. The window is a config fact;
;;; absence of a reply remains unknown rather than becoming a health claim.

(defn- cluster-name
  [db]
  (db/q '[:find ?name .
         :where [_ :seon.cluster/name ?name]]
       db))

(defn- ping-timeout-ms
  [db]
  (:seon.config.flow/ping-timeout-ms
   (config/effective db (cluster-name db))))

(defn- connection-identity
  "The stable connection + generation portion of a committed db value."
  [db]
  (some-> (db/committed-value-identity db)
          (select-keys [:datahike.value/connection-id
                        :datahike.value/generation])))

(defn- running-instances
  "The cluster entry's process-local instances, if that owner is loaded."
  []
  ;; This namespace is loaded by `seon.cluster`; requiring that owner here
  ;; would make the dependency cyclic. Resolve the read-only registry late and
  ;; match by connection identity, never by assuming "the" cluster.
  (some-> (ns-resolve 'seon.cluster 'running-instances) var-get deref))

(defn- owning-instance
  "The running instance whose branch connection owns `db`, or nil."
  [db]
  (when-let [wanted (connection-identity db)]
    (some (fn [[_ instance]]
            (let [connection (:seon.boot/cluster-connection instance)]
              (when (and connection
                         (= wanted (connection-identity @connection)))
                instance)))
          (running-instances))))

(defn- current-run-id
  "The run the agent currently points at, or nil."
  [db agent-id]
  (db/q '[:find ?run-id .
         :in $ ?agent-id
         :where
         [?agent :seon.cluster.agent/id ?agent-id]
         [?agent :seon.cluster.agent/run ?run]
         [?run :seon.cluster.run/id ?run-id]]
       db agent-id))

(defn- occupancy
  "The count and capacity from one datafied Flow channel, or nil."
  [channel]
  (when-let [buffer (:buffer channel)]
    {:seon.oversight/count (:count buffer)
     :seon.oversight/capacity (:capacity buffer)}))

(defn proc-ping
  "Project one expected Flow proc and its optional ping reply.

  A missing reply is explicitly unknown; presence in the graph never implies
  health."
  {:malli/schema [:=> [:cat :any [:maybe :map]] :map]}
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

(defn- agent-story
  "One armed agent's current story."
  [db timeout-ms agent-id entry]
  (let [ping (flow/ping (:seon.flow/graph entry)
                        :timeout-ms timeout-ms)
        mailbox (get ping ::agent/mailbox)
        turn (get ping ::agent/turn)
        run-id (current-run-id db agent-id)
        mailbox-occupancy
        (occupancy (get-in mailbox [::flow/ins ::agent/wake]))
        turn-occupancy
        (occupancy
         (or (get-in turn [::flow/ins ::agent/episode])
             ;; While the turn transform is active it cannot pong. The
             ;; mailbox's out is the same direct 1:1 channel, so occupancy
             ;; remains observable without inventing another counter.
             (get-in mailbox [::flow/outs ::agent/episode])))]
    (cond-> {:seon.cluster.agent/id agent-id
             :seon.cluster.work/episode-runs
             (work/episode-runs db agent-id)}
      run-id
      (assoc :seon.cluster.run/id run-id)

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
  [graph timeout-ms]
  (let [ping (flow/ping graph :timeout-ms timeout-ms)
        pids (sort (keys (:procs (datafy/datafy graph))))]
    (mapv
     (fn [pid]
       ;; The render proc observes itself as unknown during a feed pass: it
       ;; cannot answer until the projection returns. Presence in the graph
       ;; plus absence of a pong never becomes a health claim.
       (proc-ping pid (get ping pid)))
     pids)))

(defn- fleet-value
  "The complete process-local fleet value at one database value."
  [db instance]
  (let [routing (:seon.cluster.agent/routing instance)
        armed (or (some-> routing deref ::agent/armed) {})
        timeout-ms (ping-timeout-ms db)]
    {:seon.oversight/agents
     (into []
           (map (fn [[agent-id entry]]
                  (agent-story db timeout-ms agent-id entry)))
           (sort-by key armed))
     :seon.oversight/plumbing
     (plumbing-story (:seon.flow/graph instance) timeout-ms)}))

(defn flow-status
  "Return the current agent and plumbing Flow observations for one instance."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.boot/instance]
                  :map]}
  [db instance]
  (fleet-value db instance))

(defn cluster-flow-status
  "Return only the selected cluster graph's bounded Flow observations."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.boot/instance]
                  :map]}
  [db instance]
  {:seon.oversight/plumbing
   (plumbing-story (:seon.flow/graph instance) (ping-timeout-ms db))})

(defn unit
  "Build the live fleet render unit, or omit it without a cluster."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/unit]]}
  [source]
  (let [db (:seon.db/db source)
        instance (or (:seon.boot/instance source)
                     (owning-instance db))]
    (when (and db
               (:seon.cluster.agent/routing instance)
               (:seon.flow/graph instance))
      (assoc source
             :seon.render/value (fleet-value db instance)
             :seon.render/ai `ai-story
             :seon.render/html `html-table))))

(defn- ordinal
  "An English ordinal for a positive run count."
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
  "The presentation implied by run and turn-ping presence."
  [agent]
  (if (and (nil? (:seon.cluster.run/id agent))
           (contains? agent :seon.oversight/turn-passes))
    "parked"
    "mid-turn"))

(defn ai-story
  "Tell the fleet's current story in one concise line."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
  [unit]
  (let [agents (:seon.oversight/agents (:seon.render/value unit))]
    (if (empty? agents)
      "No agent graphs are armed."
      (str/join
       "; "
       (map
        (fn [agent]
          (let [agent-id (:seon.cluster.agent/id agent)
                story (agent-story-text agent)
                run-id (:seon.cluster.run/id agent)
                episode-runs (:seon.cluster.work/episode-runs agent)]
            (if (= "parked" story)
              (str agent-id ": parked")
              (str agent-id ": " story
                   (when run-id (str " on run " run-id))
                   (when (pos? episode-runs)
                     (str ", " (ordinal episode-runs)
                          " run this episode"))))))
        agents)))))

(defn- occupancy-text
  "A compact `count/capacity` channel readout."
  [found]
  (if found
    (str (:seon.oversight/count found)
         "/"
         (:seon.oversight/capacity found))
    "—"))

(defn html-table
  "Render the fleet story as the root page's live table."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [value (:seon.render/value unit)
        agents (:seon.oversight/agents value)
        plumbing (:seon.oversight/plumbing value)]
    [:section {:id (block/surface-id :fleet-oversight)
               :class "seon-card"}
     [:h2 "fleet"]
     [:table {:data-fleet-oversight "agents"}
      [:thead
       [:tr
        [:th "agent"]
        [:th "state"]
        [:th "current run"]
        [:th "episode"]
        [:th "mailbox"]
        [:th "turn buffer"]]]
      [:tbody
       (for [agent agents]
         (let [story (agent-story-text agent)]
           [:tr {:data-agent (:seon.cluster.agent/id agent)
                 :data-state story}
            [:td (:seon.cluster.agent/id agent)]
            [:td story]
            [:td (or (:seon.cluster.run/id agent) "—")]
            [:td (:seon.cluster.work/episode-runs agent)]
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
                        "mid-pass")))
             plumbing))]]]))
