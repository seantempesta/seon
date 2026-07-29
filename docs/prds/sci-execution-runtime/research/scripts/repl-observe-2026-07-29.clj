;; observe.clj — the lenses for a live Seon cluster, one function each.
;;
;;   tmp/repl-experiments/px <cluster> '(load-file "tmp/repl-experiments/observe.clj")'
;;   tmp/repl-experiments/px <cluster> '(observe/system "<cluster>")'
;;
;; Every function takes a cluster NAME and reads the process registry, so one
;; prepl connection observes every cluster this JVM hosts. Nothing here writes.
(ns observe
  (:require [clojure.core.async.flow :as flow]
            [datahike.api :as d]
            [seon.cluster :as cluster]))

(defn clusters
  "Every cluster this JVM hosts. The registry is the truth; an advertisement
  file is only its published shadow."
  []
  (sort (keys @@#'cluster/running-instances)))

(defn instance [name] (get @@#'cluster/running-instances name))
(defn conn [name] (:seon.boot/cluster-connection (instance name)))
(defn db [name] @(conn name))

;;; --- lens 1: the tower ------------------------------------------------------

(defn system
  "Is this cluster whole? Absence marks where boot stopped — a DEGRADED
  instance is registered with its REPL up and its upper layers missing."
  [name]
  (let [i (instance name)]
    {:layers (into {} (map (fn [k] [k (some? (get i k))]))
                   [:seon.boot/prepl-server :seon.store/store
                    :seon.boot/cluster-connection :seon.flow/graph
                    :seon.render.web/served])
     :ready-ms (:seon.boot/ready-ms i)
     :advertisement (:seon.boot/advertisement i)
     :readiness (cluster/readiness i)}))

;;; --- lens 2: the graphs -----------------------------------------------------

(defn- ping-summary [graph]
  (into {}
        (map (fn [[pid s]]
               [pid {:status (:clojure.core.async.flow/status s)
                     :passes (:clojure.core.async.flow/count s)
                     :state (:clojure.core.async.flow/state s)
                     :queued (into {}
                                   (map (fn [[c v]] [c (get-in v [:buffer :count])]))
                                   (merge (:clojure.core.async.flow/ins s)
                                          (:clojure.core.async.flow/outs s)))}]))
        (flow/ping graph)))

(defn plumbing
  "The per-cluster shared graph: armer, render, fault committer."
  [name]
  (ping-summary (:seon.flow/graph (instance name))))

(defn agents
  "Every agent's OWN graph, pinged. `:passes` is the proc's step count, so a
  turn proc that never moved and one that ran a hundred episodes are told
  apart without a single stored counter."
  [name]
  (into {}
        (map (fn [[id entry]] [id (ping-summary (:seon.flow/graph entry))]))
        (:seon.cluster.agent/armed
         @(:seon.cluster.agent/routing (instance name)))))

;;; --- lens 3: the facts ------------------------------------------------------

(defn roster
  "What agents exist, and what each is doing RIGHT NOW — derived, not stored.
  An agent with no run key is idle; presence of the run pointer IS busy."
  [name]
  (let [database (db name)]
    (sort-by :agent
             (for [id (d/q '[:find [?id ...] :where [?a :seon.cluster.agent/id ?id]]
                           database)]
               (let [run (d/q '[:find (pull ?run [:seon.cluster.run/id
                                                  :seon.cluster.run/process
                                                  :seon.cluster.run/plan-digest
                                                  :seon.cluster.run/closed-at]) .
                                :in $ ?id
                                :where [?a :seon.cluster.agent/id ?id]
                                [?a :seon.cluster.agent/run ?run]]
                              database id)]
                 (cond-> {:agent id}
                   run (assoc :run (:seon.cluster.run/id run)
                              :held-by (:seon.cluster.run/process run)
                              :planned? (some? (:seon.cluster.run/plan-digest run)))))))))

(defn last-turn
  "What the newest run of `agent-id` committed, ordinal by ordinal.
  The first thing to read when an agent 'did nothing' — an error receipt
  here is the whole story most of the time."
  [name agent-id]
  (let [database (db name)
        run-id (->> (d/q '[:find ?id ?at
                           :in $ ?agent-id
                           :where [?a :seon.cluster.agent/id ?agent-id]
                           [?r :seon.cluster.run/agent ?a]
                           [?r :seon.cluster.run/id ?id]
                           [?r :seon.cluster.run/opened-at ?at]]
                         database agent-id)
                    (sort-by second) last first)]
    (when run-id
      {:run run-id
       :forms (sort-by :seon.cluster.run.form/ordinal
                       (d/q '[:find [(pull ?f [:seon.cluster.run.form/ordinal
                                               :seon.cluster.run.form/source]) ...]
                              :in $ ?run-id
                              :where [?r :seon.cluster.run/id ?run-id]
                              [?f :seon.cluster.run.form/run ?r]]
                            database run-id))
       :receipts (sort-by :seon.cluster.eval/ordinal
                          (d/q '[:find [(pull ?e [:seon.cluster.eval/ordinal
                                                  :seon.cluster.eval/result-edn
                                                  :seon.cluster.eval/error
                                                  :seon.cluster.eval/interrupted-at]) ...]
                                 :in $ ?run-id
                                 :where [?r :seon.cluster.run/id ?run-id]
                                 [?e :seon.cluster.eval/run ?r]]
                               database run-id))})))

;;; --- lens 4: the transaction stream -----------------------------------------

(defn watch!
  "Watch what a cluster COMMITS, attribute by attribute, without touching it.
  Returns a zero-arg stop fn. The listener must be total and fast: Datahike
  fires it inside the transaction's critical path, so a slow or throwing
  listener stalls the transaction that triggered it (`wake.cljc:6-25`)."
  [name key f]
  (d/listen (conn name) key
            (fn [report]
              (try (f (mapv (juxt :e :a :v :added) (:tx-data report)))
                   (catch Throwable _ nil))))
  (fn [] (d/unlisten (conn name) key)))

(defn recent
  "The attributes committed in the last `n` transactions — the cheap
  'what just happened here' without installing a listener."
  [name n]
  (let [database (db name)
        t (:max-tx database)]
    (sort-by first
             (d/q '[:find ?tx (distinct ?a)
                    :in $ ?floor
                    :where [?e ?a _ ?tx] [(> ?tx ?floor)]]
                  (d/history database) (- t n)))))

;;; --- lens 5: cross-cluster --------------------------------------------------

(defn diff
  "Run one probe against every cluster and group the clusters by answer.
  The point of a multi-cluster JVM: 'is this cluster special, or is this
  how Seon is?' becomes one form."
  [probe]
  (reduce (fn [acc name]
            (let [v (try (probe name) (catch Throwable t {:threw (ex-message t)}))]
              (update acc v (fnil conj []) name)))
          {} (clusters)))
