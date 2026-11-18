(ns seon.agents
  "The `:agents` Flow proc (`:io` workload):
   - Multiplexes any number of logical agents & interactive REPL sessions onto a bounded pool
     (`max-os-procs`) of COW forked OS processes via `seon.fork` + `::flow/input-filter`.
   - Zero `flow/update!`, zero `seon.sock`.
   - Handles both autonomous `:turn` runs (which exit to 0 OS PIDs as soon as the turn commits!)
     and interactive `:eval` calls (`bin/seon eval` / MCP / Web REPL) on any Datahike branch."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [seon.store :as store]))

(defn- reply! [ch val]
  (when ch
    (a/put! ch val)
    (a/close! ch)))

(defn- sync-agents-filter [st]
  (if (>= (count (:live st)) (:max-os-procs st))
    (assoc st ::flow/input-filter #{:fork-ev :response :eval})
    (dissoc st ::flow/input-filter)))

(defn agents-proc
  ([] {:params {:conn "Read access to store via :main conn"
                :cfg "Pure store cfg"
                :fork-out "Channel to 1-thread System Fork Parent"
                :fork-agent-in "Agent child events from Fork Parent"
                :max-os-procs "Max simultaneous forked OS agent processes"}
       :ins {:run "Autonomous turn requests `{:agent-id :branch}` from `:reconciler`"
             :eval "Interactive REPL requests `{:branch :agent-id :code :ns :keep-warm? :reply-ch}` from `:server`"
             :response "Model completions from `:model`"}
       :outs {:request "Model completion requests to `:model`"
              :tx "Agent spawns & inter-agent messages to `:transactor`"
              :exits "Process fork/exit lifecycle events to `:reconciler`"}
       :workload :io
       :doc "Manages the pool of COW forked agent REPL & autonomous turn OS processes."
       :ping-map-fn (fn [st]
                      {:max-os-procs (:max-os-procs st)
                       :live-os-procs (count (:live st))
                       :backpressuring-run? (contains? st ::flow/input-filter)
                       :live (into {}
                                   (map (fn [[k v]] [k (select-keys v [:pid :branch :mode :fork-ms :mem-mb])]))
                                   (:live st))
                       :turns-completed (:turns-done st)})})
  ([{:keys [conn cfg fork-out fork-agent-in max-os-procs]}]
   {:conn conn
    :cfg cfg
    :fork-out fork-out
    :max-os-procs (or max-os-procs 6)
    :live {}         ; {agent-id {:pid :branch :mode :fork-ms :mem-mb}}
    :model-reqs {}   ; {req-id os-pid}
    :eval-waiters {} ; {req-id {:reply-ch :keep-warm? :agent-id}}
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
     (case cid
       :run
       (let [{:keys [agent-id branch]} msg]
         (if (contains? (:live st) agent-id)
           [st nil]
           (do
             (a/put! (:fork-out st)
                     {:op :fork-agent
                      :mode :turn
                      :agent-id agent-id
                      :branch branch
                      :cfg (:cfg st)})
             [(sync-agents-filter (assoc-in st [:live agent-id] {:pid :forking :branch branch :mode :turn}))
              nil])))

       :eval
       (let [{:keys [branch agent-id code ns keep-warm? reply-ch]} msg
             b (or branch :main)
             aid (or agent-id (keyword "repl" (name b)))
             _ (when (not= b :main)
                 (store/branch! (:conn st) :main b))
             rseq (inc (:req-seq st))
             req-id (str "eval-" rseq)
             existing (get-in st [:live aid])]
         (if (and existing (= :repl (:mode existing)) (number? (:pid existing)))
           ;; Reuse warm interactive REPL process for this branch!
           (do
             (a/put! (:fork-out st)
                     {:op :to-child
                      :pid (:pid existing)
                      :msg {:op :eval :req-id req-id :code code :ns ns}})
             [(-> st
                  (assoc :req-seq rseq)
                  (assoc-in [:eval-waiters req-id] {:reply-ch reply-ch :keep-warm? keep-warm? :agent-id aid}))
              nil])
           ;; Fork fresh COW REPL on `b` (~5 ms!)
           (do
             (a/put! (:fork-out st)
                     {:op :fork-agent
                      :mode :repl
                      :agent-id aid
                      :branch b
                      :cfg (:cfg st)
                      :initial-eval {:req-id req-id :code code :ns ns}})
             [(-> st
                  (assoc :req-seq rseq)
                  (assoc-in [:live aid] {:pid :forking :branch b :mode :repl})
                  (assoc-in [:eval-waiters req-id] {:reply-ch reply-ch :keep-warm? keep-warm? :agent-id aid})
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
               st' (update-in st [:live aid] merge {:pid (:pid msg) :fork-ms (:fork-ms msg)})]
           [st' {:exits [{:ev :forked :agent-id aid :pid (:pid msg) :fork-ms (:fork-ms msg)}]
                 ::flow/report [(str "[:agents] COW forked " aid " -> PID " (:pid msg) " in " (:fork-ms msg) " ms")]}])

         :child-frame
         (let [f (:frame msg)
               aid (:agent-id msg)
               pid (:pid msg)]
           (case (:ev f)
             :agent-started
             [(update-in st [:live aid] merge {:mem-mb (:mem-mb f)}) nil]

             :model-request
             [(assoc-in st [:model-reqs (:req-id f)] pid)
              {:request [f]}]

             :tx-main
             [st {:tx [(dissoc f :ev)]}]

             :turn-committed
             [(update st :turns-done inc)
              {::flow/report [(str "[" aid "] committed turn #" (:turn f) " on " (:branch f)
                                   " @ tx " (:branch-tx f))]}]

             :eval-done
             (let [req-id (:req-id f)
                   waiter (get-in st [:eval-waiters req-id])]
               (reply! (:reply-ch waiter) (dissoc f :ev :req-id))
               (when-not (:keep-warm? waiter)
                 (a/put! (:fork-out st) {:op :to-child :pid pid :msg {:op :stop}}))
               [(update st :eval-waiters dissoc req-id)
                {:exits [{:ev :branch-moved :agent-id aid :branch (:branch f) :branch-tx (:branch-tx f)}]}])

             :child-exception
             [st {::flow/error [{:proc :agents :agent-id aid :error (:error f)}]}]

             [st nil]))

         :child-exited
         (let [aid (:agent-id msg)
               st' (sync-agents-filter (update st :live dissoc aid))]
           [st' {:exits [{:ev :exited :agent-id aid :pid (:pid msg) :raw-status (:raw-status msg)}]
                 ::flow/report [(str "[:agents] " aid " (PID " (:pid msg) ") exited -> 0 OS resources held")]}])

         [st nil])))))
