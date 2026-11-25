(ns seon.agent
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
            [seon.store :as store]
            [seon.ctx.touches :as ctouches]
            [seon.browser.preview :as preview]
            [seon.render.data :as rdata]
            [clojure.string :as str]
            [seon.model :as model]
            [seon.render.fit :as fit]))

(defn- build-wire-monitored [st main-db bdb aid opts]
  (let [t0 (System/nanoTime)
        b0 (code/alloc-bytes)
        wire (ui/build-live-wire-request main-db bdb aid (assoc (or opts {}) :conn (:conn st)))
        ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e4)) 100.0)
        dbytes (max 0 (- (code/alloc-bytes) b0))
        mb (quot dbytes 1048576)
        fid (hasch/uuid [:perf :slow-wire-build aid])
        prev (get-in st [:perf-alerts fid])
        persisted? (boolean (first (datahike.api/datoms main-db :avet :seon.error/id fid)))
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
            :agent aid
            :ns (quote seon.render.ui)
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
    (assoc st ::flow/input-filter #{:fork-ev :response :browser-res :preview-res :eval :tx-reply})
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

(defn- deliver-model-response [st msg]
  (let [req-info (get-in st [:model-reqs (:req-id msg)])
        pid (if (map? req-info) (:pid req-info) req-info)
        ph (when (map? req-info) (:prefix-hash req-info))
        resp (:response msg)
        err-ent (:error-entity resp)
        err-kind (or (:seon.error/kind err-ent) (:seon.error/kind resp) (:error resp))]
    (when pid
      (a/put! (:fork-out st)
              {:op :to-child
               :pid pid
               :msg (cond-> {:op :model-response
                             :req-id (:req-id msg)
                             :response resp}
                      ph (assoc :prefix-hash ph))}))
    [(update st :model-reqs dissoc (:req-id msg))
     (when (or (map? err-ent) err-kind)
       {:clojure.core.async.flow/report
        [(str "[:agents] model error on " (:agent-id msg)
              " [" (if err-kind (name err-kind) "unknown") "]"
              (when (pos? (long (or (:retry-after-ms resp) 0)))
                (str " retry-after=" (:retry-after-ms resp) "ms")))]
        :exits [{:ev :model-error
                 :agent-id (:agent-id msg)
                 :req-id (:req-id msg)
                 :seon.error/kind err-kind
                 :retry-after-ms (long (or (:retry-after-ms resp) 0))
                 :seon.error/id (:seon.error/id err-ent)}]})]))

(defn- handle-run
  [st tick msg]
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
           nil])))))

(defn- handle-eval
  [st tick msg]
  (let [{:keys [branch agent-id agent code tool-calls ns keep-warm? reply-ch]} msg
       raw-b (or branch :branch/repl)
       b (if (= raw-b :main) :branch/repl raw-b)
       aid (or agent-id agent (keyword "repl" (name b)))
       _ (store/branch! (:conn st) :main b)
       rseq (inc (:req-seq st))
       req-id (str "eval-" rseq)
       existing (get-in st [:live aid])
       keep? (if (some? keep-warm?) (boolean keep-warm?) true)]
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
         nil]))))

(defn- handle-forked
  [st msg]
  (let [aid (:agent-id msg)
        st' (if (contains? (:live st) aid)
              (update-in st [:live aid] merge {:pid (:pid msg) :fork-ms (:fork-ms msg)})
              st)]
    [st' {:exits [{:ev :forked
                   :agent-id aid
                   :pid (:pid msg)
                   :busy? (get-in st' [:live aid :busy?] true)
                   :fork-ms (:fork-ms msg)}]
          :clojure.core.async.flow/report [(str "[:agents] COW forked " aid " -> PID " (:pid msg) " in " (:fork-ms msg) " ms")]}]))

(defn- handle-child-exited
  [st msg]
  (let [aid (:agent-id msg)
        cur-pid (get-in st [:live aid :pid])
        st' (if (= cur-pid (:pid msg))
              (sync-agents-filter (update st :live dissoc aid))
              st)]
    [st' {:exits [{:ev :exited :agent-id aid :pid (:pid msg) :raw-status (:raw-status msg)}]
          :clojure.core.async.flow/report [(str "[:agents] " aid " (PID " (:pid msg) ") exited -> 0 OS resources held")]}]))

(defn- handle-turn-committed
  [st tick msg]
  (let [f (:frame msg)
        aid (:agent-id msg)
        pid (:pid msg)
        _ (doseq [tr (:tool-results f)
                :let [eids (:touched-eids tr)]
                :when (seq eids)]
          (ctouches/record-live-reads-by-key! (get-in st [:cfg :store :id]) (:branch f) aid (long (or (:turn f) 1)) eids))
        st' (-> st
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
          :clojure.core.async.flow/report [(str "[" aid "] committed turn #" (:turn f) " on " (:branch f)
                                               " @ tx " (:branch-tx f) " (PID " pid " kept warm)")]}]))

(defn- handle-eval-done
  [st tick msg]
  (let [f (:frame msg)
        aid (:agent-id msg)
        pid (:pid msg)
        req-id (:req-id f)
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
      :git (if (not= :main (:branch f)) [{:op :sync-branch :branch (:branch f)}] [])}]))

(defn- handle-child-exception
  [st msg]
  (let [f (:frame msg)
        aid (:agent-id msg)
        pid (:pid msg)
        err-str (str (or (:error f) "Child worker exception"))
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
                :agent aid
                :subject (str aid)
                :message err-str}]
          :exits [{:ev :turn-idle :agent-id aid :pid pid}]
          :clojure.core.async.flow/report [(str "[:agents] child exception on " aid ": " err-str)]}]))

(defn- user-viewport-dims [main-db]
  (let [dh (store/db-schema main-db)
        u (when (and main-db (contains? dh :seon.user/id)
                     (first (datahike.api/datoms main-db :avet :seon.user/id :user/default)))
            (datahike.api/pull main-db (quote [:seon.user/viewport-w :seon.user/viewport-h :seon.user/theme
                                               {:seon.user/viewing [:seon.agent/id]}])
                               [:seon.user/id :user/default]))
        vw (let [v (:seon.user/viewport-w u)] (if (and (number? v) (pos? (long v))) (long v) 1280))
        vh (let [v (:seon.user/viewport-h u)] (if (and (number? v) (pos? (long v))) (long v) 820))
        th (if (= :light (:seon.user/theme u)) :light :dark)]
    {:width vw :height vh :theme th :viewing-agent (get-in u [:seon.user/viewing :seon.agent/id])}))

(defn- finish-model-request [st main-db bdb aid pid frame snap preview-frames]
  (let [vis? (or (boolean (:vision? st))
                 (and (:browser-enabled? st)
                      (model/vision-capable-model? (store/config-val main-db aid :seon.config/ai-model))))
        {:keys [wire st tx]} (build-wire-monitored st main-db bdb aid
                                                   (cond-> {:vision? vis?}
                                                     snap (assoc :viewport-screenshot snap)))
        final-msgs (preview/attach-preview-frames-to-messages (:messages wire) preview-frames vis?)
        router? (boolean (or (:router? frame)
                             (:seon.agent/router? (datahike.api/pull main-db (quote [:seon.agent/router?]) [:seon.agent/id aid]))))]
    [(assoc-in st [:model-reqs (:req-id frame)] {:pid pid :prefix-hash (:prefix-hash wire)})
     (cond-> {:request [(assoc frame :router? router? :wire-messages final-msgs)]}
       (seq tx) (assoc :tx tx))]))

(defn- plan-derived-previews [main-db bdb evals theme]
  (let [edited-vars (take 2 (preview/recent-edited-renderer-vars main-db bdb evals))
        widths [360 640]]
    (reduce (fn [acc vsym]
              (let [ev (preview/eval-renderer-preview bdb vsym)]
                (if-not (:ok? ev)
                  acc
                  (if-let [hit (preview/cached-preview-frames vsym (:form-hash ev) (:input-hash ev) widths theme)]
                    (update acc :cached-frames into hit)
                    (update acc :to-render conj {:var vsym :ev ev :widths widths :theme theme})))))
            {:cached-frames [] :to-render []}
            edited-vars)))

(defn- maybe-complete-pending-browser [st req-id entry]
  (if (and (:shot-done? entry) (zero? (long (or (:pending-previews entry) 0))))
    (let [{:keys [pid aid frame snap preview-frames]} entry
          main-db @(:conn st)
          b (or (:branch frame) :main)
          bdb (rdata/branch-db-for main-db (:conn st) b)]
      (finish-model-request (update st :pending-browser dissoc req-id)
                            main-db bdb aid pid frame snap preview-frames))
    [(assoc-in st [:pending-browser req-id] entry) nil]))

(defn- handle-browser-res
  [st msg]
  (let [req-id (:req-id msg)]
    (if-let [entry (get-in st [:pending-browser req-id])]
      (maybe-complete-pending-browser st req-id (assoc entry :shot-done? true :snap (:snapshot msg)))
      [st nil])))

(defn- handle-preview-res
  [st {:keys [req-id var form-hash input-hash source widths theme tokens has-examples? input-schemas siblings res]}]
  (if-let [entry (get-in st [:pending-browser req-id])]
    (let [meta-info {:tokens tokens :has-examples? has-examples? :input-schemas input-schemas :siblings siblings}
          frames (when (and (map? res) (:ok? res) (seq (:frames res)))
                   (let [fs (mapv #(preview/normalize-ephemeral-frame var source % meta-info) (:frames res))]
                     (doseq [f fs :when (map? (:dom f))]
                       (fit/record-browser-box! var form-hash (long (:width f)) (:dom f)))
                     (preview/cache-preview-frames! var form-hash input-hash widths theme fs)
                     fs))
          entry1 (-> entry
                     (update :pending-previews #(max 0 (dec (long (or % 1)))))
                     (cond-> (seq frames) (update :preview-frames (fnil into []) frames)))]
      (maybe-complete-pending-browser st req-id entry1))
    [st nil]))

(defn should-capture-viewport?
  "Pure viewport-screenshot gating policy: honors explicit per-agent `:agent-override` (`true`/`false`),
   otherwise when `:browser-enabled?` and `:global-enabled?` are true, captures only for `:agent/root`,
   the `:viewing-agent` shown to `:user/default`, or an agent with `:has-ui-previews?`."
  {:malli/schema [:=> {:examples [[[{:browser-enabled? true :global-enabled? true :agent-override nil :agent-id :agent/root :viewing-agent :agent/other :has-ui-previews? false}] true]
                                  [[{:browser-enabled? true :global-enabled? true :agent-override nil :agent-id :agent/ui :viewing-agent :agent/ui :has-ui-previews? false}] true]
                                  [[{:browser-enabled? true :global-enabled? true :agent-override nil :agent-id :agent/ui :viewing-agent :agent/root :has-ui-previews? true}] true]
                                  [[{:browser-enabled? true :global-enabled? true :agent-override nil :agent-id :ns/seon.store :viewing-agent :agent/root :has-ui-previews? false}] false]
                                  [[{:browser-enabled? true :global-enabled? true :agent-override false :agent-id :agent/root :viewing-agent :agent/root :has-ui-previews? true}] false]
                                  [[{:browser-enabled? true :global-enabled? false :agent-override true :agent-id :ns/seon.store :viewing-agent :agent/root :has-ui-previews? false}] true]
                                  [[{:browser-enabled? false :global-enabled? true :agent-override true :agent-id :agent/root :viewing-agent :agent/root :has-ui-previews? true}] false]]}
                  [:cat [:map
                         [:browser-enabled? {:optional true} [:maybe :boolean]]
                         [:global-enabled? {:optional true} [:maybe :boolean]]
                         [:agent-override {:optional true} [:maybe :boolean]]
                         [:agent-id {:optional true} [:maybe :keyword]]
                         [:viewing-agent {:optional true} [:maybe :keyword]]
                         [:has-ui-previews? {:optional true} [:maybe :boolean]]]]
                  :boolean]
   :cost/ticks 500000 :cost/bytes 131072}
  [{:keys [browser-enabled? global-enabled? agent-override agent-id viewing-agent has-ui-previews?]}]
  (boolean
   (and browser-enabled?
        (cond
          (some? agent-override) agent-override
          (not global-enabled?) false
          :else (or (= :agent/root agent-id)
                    (and (some? viewing-agent) (= viewing-agent agent-id))
                    has-ui-previews?)))))

(defn- handle-fork-refused [st msg]
  (let [explicit-aid (:agent-id msg)
        code (:code msg)
        forking-aids (if explicit-aid
                       [explicit-aid]
                       (vec (keep (fn [[aid v]] (when (= :forking (:pid v)) aid)) (:live st))))
        err-str (str "COW fork refused (code " code ")"
                     (when (seq forking-aids) (str " for " (pr-str forking-aids))))
        aid-set (set forking-aids)
        matching-w (filter (fn [[_ w]] (contains? aid-set (:agent-id w))) (:eval-waiters st))
        _ (doseq [[_ w] matching-w]
            (reply! (:reply-ch w) {:ok? false :agent-id (:agent-id w) :error err-str :class :fork-refused}))
        st' (-> st
                (update :live #(apply dissoc % forking-aids))
                (update :eval-waiters #(apply dissoc % (map first matching-w)))
                sync-agents-filter)
        faults (if (seq forking-aids)
                 (mapv (fn [aid]
                         {:op :record-fault :proc :agents :class :fork-refused :severity :error
                          :agent aid :subject (str aid) :message err-str})
                       forking-aids)
                 [{:op :record-fault :proc :agents :class :fork-refused :severity :error
                   :subject "seon.agent/fork" :message err-str}])
        exits (mapv (fn [aid] {:ev :exited :agent-id aid :pid :forking :raw-status code}) forking-aids)]
    [st' (cond-> {:tx faults
                  :clojure.core.async.flow/report [(str "[:agents] " err-str)]}
           (seq exits) (assoc :exits exits))]))

(defn- latest-turn-painted-ui? [main-db bdb evals]
  (let [evs (if (sequential? evals) (filterv map? evals) [])
        max-t (reduce max 0 (map #(long (or (:seon.agent.eval/turn %) 0)) evs))
        cur-evs (filter #(= max-t (long (or (:seon.agent.eval/turn %) 0))) evs)
        in-code? (some (fn [e]
                         (let [c (str (or (:seon.agent.eval/code e) ""))]
                           (or (str/includes? c "set-canvas!")
                               (str/includes? c "set-status!")
                               (str/includes? c ":seon.agent/canvas-hiccup")
                               (str/includes? c ":seon.agent/canvas-fn")
                               (str/includes? c ":seon.ui.status/id"))))
                       cur-evs)]
    (boolean
     (or in-code?
         (when (and main-db bdb (not (identical? main-db bdb)))
           (let [ptx (reduce max 0 (map #(long (or (:seon.agent.eval/tx %)
                                                   (when (integer? (:db/id %))
                                                     (some-> (first (datahike.api/datoms bdb :eavt (:db/id %) :seon.agent.eval/turn)) :tx long))
                                                   0))
                                        (filter #(< (long (or (:seon.agent.eval/turn %) 0)) max-t) evs)))
                 ws (ctouches/branch-write-eids main-db bdb nil)
                 dh (store/db-schema bdb)]
             (some (fn [[e {:keys [max-tx]}]]
                     (when (or (empty? evs) (> (long (or max-tx 0)) ptx))
                       (some #(and (contains? dh %) (first (datahike.api/datoms bdb :eavt e %)))
                             [:seon.agent/canvas-hiccup :seon.agent/canvas-fn :seon.ui.status/id])))
                   ws)))))))

(defn- handle-model-request
  {:seon.flow/edge true}
  [st msg]
  (let [f (:frame msg)
        aid (:agent-id msg)
        pid (:pid msg)
        main-db @(:conn st)
        b (or (:branch f) :main)
        bdb (rdata/branch-db-for main-db (:conn st) b)
        evals (rdata/agent-evals-in bdb aid)
        {:keys [width height theme viewing-agent]} (user-viewport-dims main-db)
        {:keys [cached-frames to-render]} (if (:browser-enabled? st)
                                            (plan-derived-previews main-db bdb evals theme)
                                            {:cached-frames [] :to-render []})
        painted-ui? (when (:browser-enabled? st) (latest-turn-painted-ui? main-db bdb evals))
        dh (store/db-schema main-db)
        agent-ov (when (and main-db aid (contains? dh :seon.config/viewport-screenshot?)
                            (first (datahike.api/datoms main-db :avet :seon.agent/id aid)))
                   (:seon.config/viewport-screenshot?
                    (datahike.api/pull main-db (quote [:seon.config/viewport-screenshot?]) [:seon.agent/id aid])))
        want-shot? (should-capture-viewport?
                    {:browser-enabled? (:browser-enabled? st)
                     :global-enabled? (boolean (store/config-val main-db nil :seon.config/viewport-screenshot?))
                     :agent-override agent-ov
                     :agent-id aid
                     :viewing-agent viewing-agent
                     :has-ui-previews? (boolean (or painted-ui? (seq cached-frames) (seq to-render)))})
        preview-ch (:preview-res-ch st)
        preview-reqs (when (and (seq to-render) preview-ch)
                       (mapv (fn [{:keys [var ev widths theme]}]
                               (let [rch (a/chan 1)]
                                 (a/go
                                   (let [[res _] (a/alts! [rch (a/timeout 8000)])]
                                     (a/>! preview-ch {:req-id (:req-id f)
                                                       :var var
                                                       :form-hash (:form-hash ev)
                                                       :input-hash (:input-hash ev)
                                                       :source (:source ev)
                                                       :tokens (:tokens ev)
                                                       :has-examples? (:has-examples? ev)
                                                       :input-schemas (:input-schemas ev)
                                                       :siblings (:siblings ev)
                                                       :widths widths
                                                       :theme theme
                                                       :res res})))
                                 {:op :render-hiccup-frames
                                  :html (:html ev)
                                  :widths widths
                                  :theme theme
                                  :label-prefix (name var)
                                  :reply-ch rch}))
                             to-render))
        browser-reqs (cond-> (vec preview-reqs)
                       want-shot?
                       (conj {:op :snapshot
                              :req-id (:req-id f)
                              :agent-id aid
                              :width width
                              :height height
                              :wait-ms 180}))]
    (if (seq browser-reqs)
      [(assoc-in st [:pending-browser (:req-id f)]
                 {:pid pid :aid aid :frame f
                  :shot-done? (not want-shot?)
                  :snap nil
                  :pending-previews (count preview-reqs)
                  :preview-frames (vec cached-frames)})
       {:exits [{:ev :branch-moved :agent-id aid :branch (:branch f)}]
        :browser-req browser-reqs}]
      (finish-model-request st main-db bdb aid pid f nil cached-frames))))

(defn- handle-child-frame
  [st tick msg]
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
      (handle-model-request st msg)

      :tx-main
      [st {:tx [(dissoc f :ev)]}]

      :tx-main-sync
      (let [reply-ch (a/chan 1)
            tr-ch (:tx-reply-ch st)
            rid (:req-id f)]
        (a/take! reply-ch (fn [ans]
                            (a/put! tr-ch {:pid pid :req-id rid :reply ans})))
        [st {:tx [(assoc (dissoc f :ev :req-id) :reply-ch reply-ch)]}])

      :turn-committed
      (handle-turn-committed st tick msg)

      :eval-done
      (handle-eval-done st tick msg)

      (:child-exception :agent-error)
      (handle-child-exception st msg)

      [st nil])))

(defn- handle-fork-ev
  [st tick msg]
  (case (:ev msg)
    :forked (handle-forked st msg)
    :fork-refused (handle-fork-refused st msg)
    :child-frame (handle-child-frame st tick msg)
    :child-exited (handle-child-exited st msg)
    [st nil]))

(defn agents-proc
  {:malli/schema [:function
                  [:=> [:cat] :map]
                  [:=> [:cat :map] :map]
                  [:=> [:cat :map :any] :map]
                  [:=> [:cat :map :any :any] [:tuple :map :any]]]}
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
              :browser-req "On-demand snapshot & preview frame requests to `:browser`"
              :tx "Agent spawns & inter-agent messages to `:transactor`"
              :git "Auto-sync branch refs to `:git`"
              :exits "Process fork/idle/exit lifecycle events to `:reconciler`"}
       :workload :io
       :doc "Manages persistent COW forked agent runtimes with LRU eviction, live WKWebView viewport vision & derived preview frames."
       :ping-map-fn (fn [st]
                      {:max-os-procs (:max-os-procs st)
                       :live-os-procs (count (:live st))
                       :busy-os-procs (busy-count (:live st))
                       :backpressuring-run? (contains? st :clojure.core.async.flow/input-filter)
                       :browser-enabled? (boolean (:browser-enabled? st))
                       :vision? (boolean (:vision? st))
                       :live (into {}
                                   (map (fn [[k v]]
                                          [k (select-keys v [:pid :branch :mode :busy? :fork-ms :mem-mb :turns])]))
                                   (:live st))
                       :turns-completed (:turns-done st)})})
  ([{:keys [conn cfg fork-out fork-agent-in max-os-procs browser-enabled? vision?]}]
   (let [tx-reply-ch (a/chan 256)
         preview-res-ch (a/chan 64)]
     {:conn conn
      :cfg cfg
      :fork-out fork-out
      :tx-reply-ch tx-reply-ch
      :preview-res-ch preview-res-ch
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
      :clojure.core.async.flow/in-ports {:fork-ev fork-agent-in :tx-reply tx-reply-ch :preview-res preview-res-ch}}))
  ([st transition]
   (when (= transition :clojure.core.async.flow/stop)
     (doseq [[_ {:keys [pid]}] (:live st) :when (number? pid)]
       (a/put! (:fork-out st) {:op :to-child :pid pid :msg {:op :stop}})))
   st)
  ([st cid msg]
   (if (nil? msg)
     [st nil]
     (let [tick (inc (:clock st))
           st (assoc st :clock tick)]
       (case cid
         :run (handle-run st tick msg)

         :eval (handle-eval st tick msg)

         :response (deliver-model-response st msg)

         :tx-reply
         (do
           (a/put! (:fork-out st)
                   {:op :to-child
                    :pid (:pid msg)
                    :msg {:op :tx-reply
                          :req-id (:req-id msg)
                          :reply (:reply msg)}})
           [st nil])

         :browser-res (handle-browser-res st msg)

         :preview-res (handle-preview-res st msg)

         :fork-ev (handle-fork-ev st tick msg))))))