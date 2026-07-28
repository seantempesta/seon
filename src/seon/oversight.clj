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
            [datahike.api :as d]
            [seon.cluster.agent :as agent]
            [seon.cluster.work :as work]
            [seon.render :as render]
            [seon.render.block :as block]))

;;; Flow's ping contract is explicitly timeout-bounded because an active
;;; transform cannot answer until it returns. F1 measured the parked mailbox
;;; and turn replies in microseconds; twenty milliseconds leaves orders of
;;; magnitude of scheduling room while making "turn did not answer" useful
;;; rather than adding a second to every live render.
(def ^:private ping-timeout-ms 20)

(defn- connection-identity
  "The stable connection + generation portion of a committed db value."
  [db]
  (some-> (d/committed-value-identity db)
          (select-keys [:datahike.value/connection-id
                        :datahike.value/generation])))

(defn- running-instances
  "The cluster entry's process-local instances, if that owner is loaded."
  []
  ;; `seon.cluster` requires `seon.render.root`, which requires this
  ;; namespace. Requiring it here would make that real dependency cyclic.
  ;; The cluster registry is therefore resolved late, like a render
  ;; declaration. It is read only and matched by connection identity; no
  ;; caller can accidentally select "the" cluster.
  (some-> (find-var 'seon.cluster/running-instances) var-get deref))

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
  (d/q '[:find ?run-id .
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

(defn- agent-story
  "One armed agent's current story."
  [db agent-id entry]
  (let [ping (flow/ping (:seon.flow/graph entry)
                        :timeout-ms ping-timeout-ms)
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
             (get-in mailbox [::flow/outs ::agent/episode])))
        state (if (or run-id (nil? turn)) :mid-turn :parked)]
    (cond-> {:seon.cluster.agent/id agent-id
             :seon.oversight/state state
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
  [graph]
  (let [ping (flow/ping graph :timeout-ms ping-timeout-ms)
        pids (sort (keys (:procs (datafy/datafy graph))))]
    (mapv
     (fn [pid]
       (if-let [reply (get ping pid)]
         {:seon.oversight/proc pid
          :seon.oversight/state :responsive
          :seon.oversight/passes (::flow/count reply)
          :seon.oversight/buffers
          (into []
                (keep (fn [[port channel]]
                        (when-let [found (occupancy channel)]
                          (assoc found :seon.oversight/port port))))
                (::flow/ins reply))}
         ;; The render proc observes itself in this state during a feed
         ;; pass: it cannot answer until the projection returns. Presence
         ;; of the proc in the graph definition plus absence of a pong is
         ;; the story; no stored busy flag is needed.
         {:seon.oversight/proc pid
          :seon.oversight/state :mid-pass}))
     pids)))

(defn- fleet-value
  "The complete process-local fleet value at one database value."
  [db instance]
  (let [routing (:seon.cluster.agent/routing instance)
        armed (or (some-> routing deref ::agent/armed) {})]
    {:seon.oversight/agents
     (into []
           (map (fn [[agent-id entry]]
                  (agent-story db agent-id entry)))
           (sort-by key armed))
     :seon.oversight/plumbing
     (plumbing-story (:seon.flow/graph instance))}))

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
                state (:seon.oversight/state agent)
                run-id (:seon.cluster.run/id agent)
                episode-runs (:seon.cluster.work/episode-runs agent)]
            (if (= :parked state)
              (str agent-id ": parked")
              (str agent-id ": mid-turn"
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
         [:tr {:data-agent (:seon.cluster.agent/id agent)}
          [:td (:seon.cluster.agent/id agent)]
          [:td (name (:seon.oversight/state agent))]
          [:td (or (:seon.cluster.run/id agent) "—")]
          [:td (:seon.cluster.work/episode-runs agent)]
          [:td (occupancy-text (:seon.oversight/mailbox agent))]
          [:td (occupancy-text (:seon.oversight/turn-buffer agent))]])]]
     [:dl
      [:dt "plumbing passes"]
      [:dd
       (str/join
        " · "
        (map (fn [proc]
               (str (:seon.oversight/proc proc)
                    " "
                    (or (:seon.oversight/passes proc)
                        (name (:seon.oversight/state proc)))))
             plumbing))]]]))

(defn- projection
  "Build and route one live fleet projection, preserving omission."
  [source kind]
  (when-let [built (unit source)]
    (let [rendered (render/render {:seon.render/unit built
                                   :seon.render/kind kind})]
      (if-let [failure (:seon.error/kind rendered)]
        (throw (ex-info (:seon.error/message rendered) failure))
        (:seon.render/output rendered)))))

(defn block-ai
  "Build and route root's live fleet prose, or omit it."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [source]
  (projection source :seon.render/ai))

(defn block-html
  "Build and route root's live fleet table, or omit it."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/hiccup]]}
  [source]
  (projection source :seon.render/html))
