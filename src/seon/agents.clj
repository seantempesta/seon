(ns seon.agents
  "The `:agents` Flow proc (`:io` workload):
   - Multiplexes logical agents & interactive REPL sessions onto a bounded pool (`max-os-procs`)
     of persistent COW forked OS processes via `seon.fork` + `::flow/input-filter`.
   - Keeps an active agent's forked OS process ALIVE across turns (preserving its live runtime,
     `*ns*`, vars, and caches across multi-turn work!).
   - Transparently LRU-evicts idle warm processes when `max-os-procs` is needed by another waking
     agent, and releases an agent's OS process cleanly once `:merged` (`0` idle OS bloat!)."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [seon.render.ui :as ui]
            [seon.store :as store]))

(defn- reply! [ch val]
  (when ch
    (a/put! ch val)
    (a/close! ch)))

(defn- busy-count [live-map]
  (count (filter (fn [[_ v]] (:busy? v)) live-map)))

(defn- sync-agents-filter [st]
  ;; Only backpressure `:run` when ALL `max-os-procs` slots are actively `:busy?`!
  ;; If some slots are warm `:idle`, `:run` can LRU-evict an idle slot on demand!
  (if (>= (busy-count (:live st)) (:max-os-procs st))
    (assoc st ::flow/input-filter #{:fork-ev :response :eval})
    (dissoc st ::flow/input-filter)))

(defn- maybe-evict-lru-idle! [st]
  (if (< (count (:live st)) (:max-os-procs st))
    st
    (if-let [[victim-id victim]
             (->> (:live st)
                  (filter (fn [[_ v]] (and (not (:busy? v)) (number? (:pid v)))))
                  (sort-by (fn [[_ v]] (:last-active v 0)))
                  first)]
      (do
        (a/put! (:fork-out st) {:op :to-child :pid (:pid victim) :msg {:op :stop}})
        (update st :live dissoc victim-id))
      st)))

(defn agents-proc
  ([] {:params {:conn "Read access to store via :main conn"
                :cfg "Pure store cfg"
                :fork-out "Channel to 1-thread System Fork Parent"
                :fork-agent-in "Agent child events from Fork Parent"
                :max-os-procs "Max simultaneous forked OS agent processes"}
       :ins {:run "Autonomous turn requests `{:agent-id :branch}` or `{:op :release-agent :agent-id}` from `:reconciler`"
             :eval "Interactive REPL requests `{:branch :agent-id :code :tool-calls :ns :keep-warm? :reply-ch}` from `:server`"
             :response "Model completions from `:model`"}
       :outs {:request "Model completion requests to `:model`"
              :tx "Agent spawns & inter-agent messages to `:transactor`"
              :git "Auto-sync branch refs to `:git`"
              :exits "Process fork/idle/exit lifecycle events to `:reconciler`"}
       :workload :io
       :doc "Manages persistent COW forked agent runtimes with LRU eviction & multi-tool turn execution."
       :ping-map-fn (fn [st]
                      {:max-os-procs (:max-os-procs st)
                       :live-os-procs (count (:live st))
                       :busy-os-procs (busy-count (:live st))
                       :backpressuring-run? (contains? st ::flow/input-filter)
                       :live (into {}
                                   (map (fn [[k v]]
                                          [k (select-keys v [:pid :branch :mode :busy? :fork-ms :mem-mb :turns])]))
                                   (:live st))
                       :turns-completed (:turns-done st)})})
  ([{:keys [conn cfg fork-out fork-agent-in max-os-procs]}]
   {:conn conn
    :cfg cfg
    :fork-out fork-out
    :max-os-procs (or max-os-procs 6)
    :live {}         ; {agent-id {:pid :branch :mode :busy? :last-active :turns :fork-ms :mem-mb}}
    :model-reqs {}   ; {req-id os-pid}
    :eval-waiters {} ; {req-id {:reply-ch :keep-warm? :agent-id}}
    :pending-eval {} ; {agent-id [{:req-id :code :tool-calls :ns}]} when forked mid-init
    :clock 0
    :req-seq 0
    :turns-done 0
    ::flow/in-ports {:fork-ev fork-agent-in}})
  ([st transition]
   (when (= transition ::flow/stop)
     (doseq [[_ {:keys [pid]}] (:live st) :when (number? pid)]
       (a/put! (:fork-out st) {:op :to-child :pid pid :msg {:op :stop}})))
   st)
  ([st cid msg]
   (if (nil? msg)
     [st nil]
     (let [tick (inc (:clock st))
           st (assoc st :clock tick)]
       (case cid
         :run
         (if (= :release-agent (:op msg))
           (let [aid (:agent-id msg)
                 existing (get-in st [:live aid])]
             (when (and existing (number? (:pid existing)))
               (a/put! (:fork-out st) {:op :to-child :pid (:pid existing) :msg {:op :stop}}))
             [(sync-agents-filter (update st :live dissoc aid)) nil])
           (let [{:keys [agent-id branch]} msg
                 existing (get-in st [:live agent-id])]
             (cond
               ;; Already actively running a turn -> ignore duplicate wake
               (:busy? existing)
               [st nil]

               ;; Warm persistent OS process already alive for this agent -> reuse in 0.1 ms (0 forks)!
               (and existing (number? (:pid existing)))
               (do
                 (a/put! (:fork-out st)
                         {:op :to-child
                          :pid (:pid existing)
                          :msg {:op :run-turn}})
                 [(-> st
                      (assoc-in [:live agent-id :busy?] true)
                      (assoc-in [:live agent-id :last-active] tick)
                      sync-agents-filter)
                  {:exits [{:ev :turn-busy :agent-id agent-id :pid (:pid existing)}]}])

               ;; Fork fresh COW agent process on `branch` (evicting oldest idle process if pool is full)
               :else
               (let [st1 (maybe-evict-lru-idle! st)]
                 (a/put! (:fork-out st1)
                         {:op :fork-agent
                          :mode :turn
                          :agent-id agent-id
                          :branch branch
                          :cfg (:cfg st1)})
                 [(-> st1
                      (assoc-in [:live agent-id] {:pid :forking
                                                  :branch branch
                                                  :mode :turn
                                                  :busy? true
                                                  :turns 0
                                                  :last-active tick})
                      sync-agents-filter)
                  nil]))))

         :eval
         (let [{:keys [branch agent-id code tool-calls ns keep-warm? reply-ch]} msg
               b (or branch :main)
               aid (or agent-id (keyword "repl" (name b)))
               _ (when (not= b :main)
                   (store/branch! (:conn st) :main b))
               rseq (inc (:req-seq st))
               req-id (str "eval-" rseq)
               existing (get-in st [:live aid])
               keep? (if (some? keep-warm?) keep-warm? (some? agent-id))]
           (cond
             (and existing (number? (:pid existing)) (not (:busy? existing)))
             (do
               (a/put! (:fork-out st)
                       {:op :to-child
                        :pid (:pid existing)
                        :msg {:op :eval :req-id req-id :code code :tool-calls tool-calls :ns ns}})
               [(-> st
                    (assoc :req-seq rseq)
                    (assoc-in [:live aid :busy?] true)
                    (assoc-in [:live aid :last-active] tick)
                    (assoc-in [:eval-waiters req-id] {:reply-ch reply-ch :keep-warm? keep? :agent-id aid}))
                nil])

             :else
             (let [st1 (maybe-evict-lru-idle! st)]
               (a/put! (:fork-out st1)
                       {:op :fork-agent
                        :mode :repl
                        :agent-id aid
                        :branch b
                        :cfg (:cfg st1)
                        :initial-eval {:req-id req-id :code code :tool-calls tool-calls :ns ns}})
               [(-> st1
                    (assoc :req-seq rseq)
                    (assoc-in [:live aid] {:pid :forking
                                           :branch b
                                           :mode :repl
                                           :busy? true
                                           :turns 0
                                           :last-active tick})
                    (assoc-in [:eval-waiters req-id] {:reply-ch reply-ch :keep-warm? keep? :agent-id aid})
                    sync-agents-filter)
                nil])))

         :response
         (let [pid (get-in st [:model-reqs (:req-id msg)])]
           (when pid
             (a/put! (:fork-out st)
                     {:op :to-child
                      :pid pid
                      :msg {:op :model-response
                            :req-id (:req-id msg)
                            :response (:response msg)}}))
           [(update st :model-reqs dissoc (:req-id msg)) nil])

         :fork-ev
         (case (:ev msg)
           :forked
           (let [aid (:agent-id msg)
                 st' (if (contains? (:live st) aid)
                       (update-in st [:live aid] merge {:pid (:pid msg) :fork-ms (:fork-ms msg)})
                       st)]
             [st' {:exits [{:ev :forked
                            :agent-id aid
                            :pid (:pid msg)
                            :busy? (get-in st' [:live aid :busy?] true)
                            :fork-ms (:fork-ms msg)}]
                   ::flow/report [(str "[:agents] COW forked " aid " -> PID " (:pid msg) " in " (:fork-ms msg) " ms")]}])

           :child-frame
           (let [f (:frame msg)
                 aid (:agent-id msg)
                 pid (:pid msg)]
             (case (:ev f)
               :agent-started
               [(if (contains? (:live st) aid)
                  (update-in st [:live aid] merge {:mem-mb (:mem-mb f) :ceiling (:ceiling f)})
                  st)
                nil]

               :model-request
               (let [main-db @(:conn st)
                     b (or (:branch f) :main)
                     bdb (or (try (store/head (:conn st) b) (catch Throwable _ nil)) main-db)
                     wire (ui/build-live-wire-request main-db bdb aid {})]
                 [(assoc-in st [:model-reqs (:req-id f)] pid)
                  {:request [(assoc f :wire-messages (:messages wire))]}])

               :tx-main
               [st {:tx [(dissoc f :ev)]}]

               :turn-committed
               (let [st' (-> st
                             (update :turns-done inc)
                             (cond-> (contains? (:live st) aid)
                               (-> (assoc-in [:live aid :busy?] false)
                                   (assoc-in [:live aid :last-active] tick)
                                   (update-in [:live aid :turns] (fnil inc 0))))
                             sync-agents-filter)]
                 [st' {:exits [{:ev :turn-idle
                                :agent-id aid
                                :pid pid
                                :branch (:branch f)
                                :branch-tx (:branch-tx f)}]
                       :git (if (not= :main (:branch f)) [{:op :sync-branch :branch (:branch f)}] [])
                       ::flow/report [(str "[" aid "] committed turn #" (:turn f) " on " (:branch f)
                                           " @ tx " (:branch-tx f) " (PID " pid " kept warm)")]}])

               :eval-done
               (let [req-id (:req-id f)
                     waiter (get-in st [:eval-waiters req-id])]
                 (reply! (:reply-ch waiter) (dissoc f :ev :req-id))
                 (when-not (:keep-warm? waiter)
                   (a/put! (:fork-out st) {:op :to-child :pid pid :msg {:op :stop}}))
                 [(-> st
                      (update :eval-waiters dissoc req-id)
                      (cond-> (and (:keep-warm? waiter) (contains? (:live st) aid))
                        (-> (assoc-in [:live aid :busy?] false)
                            (assoc-in [:live aid :last-active] tick)))
                      sync-agents-filter)
                  {:exits [{:ev :branch-moved :agent-id aid :branch (:branch f) :branch-tx (:branch-tx f)}]
                   :git (if (not= :main (:branch f)) [{:op :sync-branch :branch (:branch f)}] [])}])

               :child-exception
               [st {::flow/error [{:proc :agents :agent-id aid :error (:error f)}]}]

               [st nil]))

           :child-exited
           (let [aid (:agent-id msg)
                 cur-pid (get-in st [:live aid :pid])
                 ;; Only remove `:live` entry if this exited PID is still the current PID for `aid`!
                 st' (if (= cur-pid (:pid msg))
                       (sync-agents-filter (update st :live dissoc aid))
                       st)]
             [st' {:exits [{:ev :exited :agent-id aid :pid (:pid msg) :raw-status (:raw-status msg)}]
                   ::flow/report [(str "[:agents] " aid " (PID " (:pid msg) ") exited -> 0 OS resources held")]}])

           [st nil]))))))
