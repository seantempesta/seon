(ns w4-prewarm-viability
  "W4 supplement — can eager installation be moved OFF the turn's critical
  path by pre-warming forks?

  Eager re-creation costs tens to hundreds of milliseconds (main probe).
  That is only a latency problem if it must happen while the turn waits.
  A pre-warmed fork pool would fix that — but only if the turn's
  environment can be attached to a fork AFTER the corpus is installed into
  it. Interpreted fns pin the ctx OBJECT they were created against, so
  this is a real question with two candidate answers:

    A. `assoc` the environment onto the ctx after installing — the pinned
       ctx is the pre-assoc one, so the fn should read the OLD value;
    B. install a per-fork holder (a volatile created with the fork) into
       the ctx BEFORE installing the corpus, then set the holder at turn
       start — the pinned ctx contains the holder, so the fn should read
       the CURRENT contents.

  If A fails and B works, pre-warming is viable and eager's cost leaves
  the critical path. If both fail, eager is inescapably per-turn latency.

  Run: see docs/prds/sci-execution-runtime/research/env-phase1-w4-probes/RUN.md"
  (:require [sci.core :as sci]
            [sci.impl.vars :as sci-vars]))

(defn who-am-i
  "Host leaf declaring one turn-scoped environment member."
  [agent-id]
  {:saw/agent agent-id})

(def ^:private declarations
  {'my/who-am-i {:arity 1 :env-key :seon.cluster.agent/id}})

(defn- environment-of
  "Read the environment off the runtime ctx, dereferencing a holder when
  the ctx carries one (candidate B)."
  [ctx]
  (let [v (:seon.env/environment ctx)]
    (if (instance? clojure.lang.IDeref v) @v v)))

(defn- call-preparation-hook [ctx v args]
  (let [sym (sci-vars/toSymbol v)
        {:keys [arity env-key]} (get declarations sym)]
    (if (or (nil? arity) (>= (count args) arity))
      args
      (let [environment (environment-of ctx)]
        (if (contains? environment env-key)
          (conj (vec args) (get environment env-key))
          (reduced {:seon.error/kind :seon.env/unavailable
                    :seon.error/message (str "no " env-key)}))))))

(defn- base []
  (let [my-ns (sci/create-ns 'my)]
    (sci/init {:namespaces {'my {'who-am-i (sci/new-var 'who-am-i who-am-i
                                                        {:ns my-ns})}}
               :call-preparation-hook call-preparation-hook})))

(def ^:private program "(defn report [] (my/who-am-i))")

;;; A. assoc the environment AFTER installing the corpus into the fork.

(defn- assoc-after-install []
  (let [pre-warmed (assoc (sci/fork (base))
                          :seon.env/environment
                          {:seon.cluster.agent/id :agent-PREWARM})
        _ (sci/eval-string* pre-warmed program)
        at-turn (assoc pre-warmed :seon.env/environment
                       {:seon.cluster.agent/id :agent-TURN})
        result (sci/eval-string* at-turn "(report)")]
    {:expected :agent-TURN
     :actual (:saw/agent result)
     :works? (= :agent-TURN (:saw/agent result))}))

;;; B. a per-fork holder installed on the ctx BEFORE the corpus.

(defn- holder-set-at-turn-start []
  (let [holder (volatile! {:seon.cluster.agent/id :agent-PREWARM})
        pre-warmed (assoc (sci/fork (base)) :seon.env/environment holder)
        _ (sci/eval-string* pre-warmed program)
        _ (vreset! holder {:seon.cluster.agent/id :agent-TURN})
        result (sci/eval-string* pre-warmed "(report)")
        _ (vreset! holder {:seon.cluster.agent/id :agent-TURN-2})
        second-turn (sci/eval-string* pre-warmed "(report)")]
    {:expected :agent-TURN
     :actual (:saw/agent result)
     :works? (= :agent-TURN (:saw/agent result))
     :reusable-across-turns? (= :agent-TURN-2 (:saw/agent second-turn))}))

;;; B'. isolation check: two pre-warmed forks, two holders, concurrent.

(defn- holders-stay-isolated [threads]
  (let [b (base)
        forks (mapv (fn [i]
                      (let [h (volatile! {:seon.cluster.agent/id
                                          (keyword (str "agent-" i))})
                            f (assoc (sci/fork b) :seon.env/environment h)]
                        (sci/eval-string* f program)
                        [i f]))
                    (range threads))
        results (->> (for [[i f] forks]
                       (let [p (promise)]
                         (.start (Thread/ofVirtual)
                                 ^Runnable
                                 #(deliver p [(keyword (str "agent-" i))
                                              (:saw/agent
                                               (sci/eval-string* f "(report)"))]))
                         p))
                     (mapv deref))]
    {:forks threads
     :all-correct? (every? (fn [[e a]] (= e a)) results)
     :mismatches (vec (remove (fn [[e a]] (= e a)) results))}))

(defn run
  "Decide whether eager installation can leave the turn's critical path."
  []
  (let [a (assoc-after-install)
        b (holder-set-at-turn-start)]
    {:probe/name "w4 — pre-warmed fork viability"
     :probe/assoc-after-install a
     :probe/holder-set-at-turn-start b
     :probe/holder-isolation (holders-stay-isolated 16)
     :probe/finding
     (cond
       (:works? a) "assoc-after-install works; pre-warming needs no holder"
       (:works? b) "assoc-after-install FAILS (the fn pins the pre-assoc ctx) but a per-fork holder set at turn start WORKS: eager installation can be pre-warmed off the critical path, at the cost of one mutable per-fork box"
       :else "neither works; eager installation is inescapably per-turn latency")}))
