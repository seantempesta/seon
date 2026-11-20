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
            [hasch.core :as hasch]
            [seon.code :as code]
            [seon.render.ui :as ui]
            [seon.store :as store]))

(defn- build-wire-monitored [st main-db bdb aid opts]
  (let [t0 (System/nanoTime)
        b0 (code/alloc-bytes)
        wire (ui/build-live-wire-request main-db bdb aid (assoc (or opts {}) :conn (:conn st)))
        ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e4)) 100.0)
        dbytes (max 0 (- (code/alloc-bytes) b0))
        mb (quot dbytes 1048576)
        fid (hasch/uuid [:perf :slow-wire-build aid])
        prev (get-in st [:perf-alerts fid])
        persisted? (boolean (first (datahike.api/datoms main-db :avet :seon.health.fault/id fid)))
        slow? (or (> ms 120.0) (> dbytes 83886080))]
    (cond
      (and slow? (or (nil? prev) (> ms (* 1.25 (:ms prev 0.0)))))
      {:wire wire
       :st (assoc-in st [:perf-alerts fid] {:ms ms :mb mb})
       :tx [{:op :record-fault
             :id fid
             :class :slow-wire-build
             :severity :warning
             :proc :agents
             :ns 'seon.render.ui
             :subject (str "seon.render.ui/build-live-wire-request:" (name aid))
             :message (str "Slow wire context build for " aid ": " ms " ms, " mb " MB (> 120 ms / 80 MB budget).")}]}

      (and (not slow?) (or prev persisted?))
      {:wire wire
       :st (update st :perf-alerts dissoc fid)
       :tx [{:op :retract-fault :id fid}]}

      :else
      {:wire wire :st st :tx nil})))

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
    (assoc st ::flow/input-filter #{:fork-ev :response :browser-res :eval :tx-reply})
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
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat :map] :map] [:=> [:cat :map :any] :map] [:=> [:cat :map :any :any] [:tuple :map :any]]]}
  ([] {:params {:conn "Read access to store via :main conn"
                :cfg "Pure store cfg"
                :fork-out "Channel to 1-thread System Fork Parent"
                :fork-agent-in "Agent child events from Fork Parent"
                :max-os-procs "Max simultaneous forked OS agent processes"
                :browser-enabled? "Boolean: capture live WKWebView tail screenshot before each model turn"
                :vision? "Boolean: attach base64 image_url block to ephemeral tail message"}
       :ins {:run "Autonomous turn requests `{:agent-id :branch}` or `{:op :release-agent :agent-id}` from `:reconciler`"
             :eval "Interactive REPL requests `{:branch :agent-id :code :tool-calls :ns :keep-warm? :reply-ch}` from `:server`"
             :response "Model completions from `:model`"
             :browser-res "Fresh live WKWebView snapshots `{:req-id :agent-id :snapshot}` from `:browser`"}
       :outs {:request "Model completion requests to `:model`"
              :browser-req "On-demand snapshot requests to `:browser`"
              :tx "Agent spawns & inter-agent messages to `:transactor`"
              :git "Auto-sync branch refs to `:git`"
              :exits "Process fork/idle/exit lifecycle events to `:reconciler`"}
       :workload :io
       :doc "Manages persistent COW forked agent runtimes with LRU eviction, live WKWebView viewport vision & multi-tool turn execution."
       :ping-map-fn (fn [st]
                      {:max-os-procs (:max-os-procs st)
                       :live-os-procs (count (:live st))
                       :busy-os-procs (busy-count (:live st))
                       :backpressuring-run? (contains? st ::flow/input-filter)
                       :browser-enabled? (boolean (:browser-enabled? st))
                       :vision? (boolean (:vision? st))
                       :live (into {}
                                   (map (fn [[k v]]
                                          [k (select-keys v [:pid :branch :mode :busy? :fork-ms :mem-mb :turns])]))
                                   (:live st))
                       :turns-completed (:turns-done st)})})
  ([{:keys [conn cfg fork-out fork-agent-in max-os-procs browser-enabled? vision?]}]
   (let [tx-reply-ch (a/chan 256)]
     {:conn conn
      :cfg cfg
      :fork-out fork-out
      :tx-reply-ch tx-reply-ch
      :max-os-procs (or max-os-procs 6)
      :browser-enabled? (boolean browser-enabled?)
      :vision? (boolean vision?)
      :live {}
      :model-reqs {}
      :pending-browser {}
      :eval-waiters {}
      :pending-eval {}
      :clock 0
      :req-seq 0
      :turns-done 0
      ::flow/in-ports {:fork-ev fork-agent-in :tx-reply tx-reply-ch}}))
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
               (:busy? existing)
               [st nil]

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
               raw-b (or branch :branch/repl)
               b (if (= raw-b :main) :branch/repl raw-b)
               aid (or agent-id (keyword "repl" (name b)))
               _ (store/branch! (:conn st) :main b)
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
         (let [req-info (get-in st [:model-reqs (:req-id msg)])
             pid (if (map? req-info) (:pid req-info) req-info)
             ph (when (map? req-info) (:prefix-hash req-info))]
         (when pid
           (a/put! (:fork-out st)
                   {:op :to-child
                    :pid pid
                    :msg (cond-> {:op :model-response
                                  :req-id (:req-id msg)
                                  :response (:response msg)}
                           ph (assoc :prefix-hash ph))}))
         [(update st :model-reqs dissoc (:req-id msg)) nil])

         :tx-reply
         (do
           (a/put! (:fork-out st)
                   {:op :to-child
                    :pid (:pid msg)
                    :msg {:op :tx-reply
                          :req-id (:req-id msg)
                          :reply (:reply msg)}})
           [st nil])

         :browser-res
         (let [req-id (:req-id msg)
               snap (:snapshot msg)]
           (if-let [{:keys [pid aid frame]} (get-in st [:pending-browser req-id])]
             (let [main-db @(:conn st)
                   b (or (:branch frame) :main)
                   bdb (or (try (store/head (:conn st) b) (catch Throwable _ nil)) main-db)
                   {:keys [wire st tx]} (build-wire-monitored st main-db bdb aid
                                                              {:viewport-screenshot snap
                                                               :vision? (boolean (:vision? st))})]
               [(-> st
                    (update :pending-browser dissoc req-id)
                    (assoc-in [:model-reqs req-id] pid))
                (cond-> {:request [(assoc frame :wire-messages (:messages wire))]}
                  (seq tx) (assoc :tx tx))])
             [st nil]))

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
                   want-shot? (and (:browser-enabled? st)
                                   (boolean (store/config-val main-db aid :seon.config/viewport-screenshot?)))]
               (if want-shot?
                 [(assoc-in st [:pending-browser (:req-id f)] {:pid pid :aid aid :frame f})
                  {:exits [{:ev :branch-moved :agent-id aid :branch (:branch f)}]
                   :browser-req [{:op :snapshot :req-id (:req-id f) :agent-id aid :wait-ms 180}]}]
                 (let [b (or (:branch f) :main)
                       bdb (or (try (store/head (:conn st) b) (catch Throwable _ nil)) main-db)
                       {:keys [wire st tx]} (build-wire-monitored st main-db bdb aid {})]
                   [(assoc-in st [:model-reqs (:req-id f)] pid)
                    (cond-> {:request [(assoc f :wire-messages (:messages wire))]}
                      (seq tx) (assoc :tx tx))])))

               :tx-main
               [st {:tx [(dissoc f :ev)]}]

               :tx-main-sync
               (let [reply-ch (a/chan 1)
                     tr-ch (:tx-reply-ch st)
                     rid (:req-id f)]
                 (a/take! reply-ch (fn [ans]
                                     (a/put! tr-ch {:pid pid :req-id rid :reply ans})))
                 [st {:tx [(assoc (dissoc f :ev :req-id) :reply-ch reply-ch)]}])

               :branch-render
               [st {:exits [(assoc f :agent-id aid)]}]

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
                  {:exits (cond-> [{:ev :branch-moved :agent-id aid :branch (:branch f) :branch-tx (:branch-tx f)}]
                            (:keep-warm? waiter)
                            (conj {:ev :turn-idle :agent-id aid :pid pid :branch (:branch f) :branch-tx (:branch-tx f)}))
                   :git (if (not= :main (:branch f)) [{:op :sync-branch :branch (:branch f)}] [])}])

               :child-exception
               (let [err-str (str (or (:error f) "Child worker exception"))
                     matching-w (filter (fn [[_ w]] (= aid (:agent-id w))) (:eval-waiters st))
                     _ (doseq [[_ w] matching-w]
                         (reply! (:reply-ch w) {:ok? false :agent-id aid :error err-str}))
                     st' (-> st
                             (update :eval-waiters #(apply dissoc % (map first matching-w)))
                             (cond-> (contains? (:live st) aid)
                               (assoc-in [:live aid :busy?] false))
                             sync-agents-filter)]
                 [st' {:tx [{:op :record-fault
                             :proc :agents
                             :class :child-exception
                             :severity :error
                             :subject (str aid)
                             :message err-str}]
                       :exits [{:ev :turn-idle :agent-id aid :pid pid}]
                       ::flow/report [(str "[:agents] child exception on " aid ": " err-str)]}])

               [st nil]))

           :child-exited
           (let [aid (:agent-id msg)
                 cur-pid (get-in st [:live aid :pid])
                 st' (if (= cur-pid (:pid msg))
                       (sync-agents-filter (update st :live dissoc aid))
                       st)]
             [st' {:exits [{:ev :exited :agent-id aid :pid (:pid msg) :raw-status (:raw-status msg)}]
                   ::flow/report [(str "[:agents] " aid " (PID " (:pid msg) ") exited -> 0 OS resources held")]}])

           [st nil]))))))
