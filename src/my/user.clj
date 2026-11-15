(ns my.user
  "The agent's functions on the person it works for (Seon's my.user): the sessions working for them, and move their view. See docs/users.md#whats-on-screen.
    (my.user/agents)       every agent on main as this agent last heard it: [{:key :status :task :outcome}], by key
    (my.user/focus! \"k\" \"Open the test results\")   offer the person a button to the page of agent or task k"
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.agent :as agent] [seon.ctx :as ctx] [seon.ratom :as ratom] [seon.db]))

(defn- started
  "The agents join value as a context holds it (a map of agents, or one agent, or none), as a sequence of them. See docs/users.md#roots-agent."
  {:malli/schema [:=> [:cat :any] [:sequential :map]]}
  [v] (cond (nil? v) [] (:db/id v) [v] (map? v) (vec (vals v)) :else (vec v)))

(defn- below
  "The agents join value as holds, each with the ones it started, as deep as the pull joined them: [the agents that hold their key, how many
  are held as ids alone (where the pull stopped)]. See docs/users.md#roots-agent."
  {:malli/schema [:=> [:cat :any] [:tuple [:vector :map] nat-int?]]}
  [as]
  (reduce (fn [[ks n] a] (if (:seon.agent/key a)
                           (let [[ks2 n2] (below (:seon.agent/_parent a))] [(into (conj ks a) ks2) (+ n n2)])
                           [ks (inc n)]))
          [[] 0] (started as)))

(defn agents
  "Every session as this agent's record holds them, at the point its turn heard main: each {:key :status :task :outcome}, by key.
  Read from the record's join to the agents forked from it (root's session is the first agent: its :seon.agent/_parent is the tree), the agents
  it started and, under each, the ones it started, to :seon.config.render/agent-tree-depth levels: when the pull stopped short of some, a last entry
  {:more n :depth :seon.config.render/agent-tree-depth} says how many it left out, so the answer never cuts silently; it reads no db. An agent
  whose pattern holds no such join has none. What root's session asks when it looks for a session already on a person's request (its page's
  model reads none in each request). See docs/users.md#roots-agent."
  {:malli/schema [:=> [:cat] [:vector [:or [:map [:key :string] [:status {:optional true} :any] [:task {:optional true} [:maybe :string]] [:outcome {:optional true} :any]]
                                         [:map [:more pos-int?] [:depth :qualified-keyword]]]]]}
  []
  (let [[as n] (below (ratom/realize (get @ctx/ctx :seon.agent/_parent)))
        listed (->> as
                    (keep (fn [a] (when-let [k (:seon.agent/key a)]
                                    {:key k :status (:seon.agent/status a) :task (get-in a [:seon.agent/task :seon.task/id]) :outcome (get-in a [:seon.agent/task :seon.task/outcome])})))
                    (sort-by :key)
                    vec)]
    (cond-> listed (pos? n) (conj {:more n :depth :seon.config.render/agent-tree-depth}))))

(defn focus!
  "Offer the person a place: the page of the agent with key k, or of task k, as a button labelled label (plain words for them), which
  draws itself in their chat by its schema's render (:seon.user/offer). It writes nothing: the person's tap sets their web state there and
  opens it (seon.system/focus!). Without a label, the button says to open it. See docs/users.md#whats-on-screen."
  {:malli/schema [:function [:=> [:cat :string] :seon.user/offer] [:=> [:cat :string :string] :seon.user/offer]]}
  ([k] (focus! k "Open it"))
  ([k label]
   (with-meta {:seon.user/place (if (seon.db/eid (agent/db) :seon.agent/key k) [:seon.agent/key k] [:seon.task/id k]) :seon.user/label label}
     {:seon.render/schema :seon.user/offer})))
