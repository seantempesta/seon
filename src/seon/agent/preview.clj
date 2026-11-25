(ns seon.agent.preview
  "Extracted child namespace of `seon.agent`."
  (:require [hasch.core :as hasch]
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

(defn user-viewport-dims
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] :map]}
  [main-db]
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

(defn finish-model-request
  {:malli/schema [:=> [:cat :map :seon.host/db :seon.host/db :keyword :any :any :any :any] :any]}
  [st main-db bdb aid pid frame snap preview-frames]
  (let [vis? (or (boolean (:vision? st))
                 (and (:browser-enabled? st)
                      (model/vision-capable-model? (store/config-val main-db aid :seon.config/ai-model))))
        prev-shown (get-in st [:live aid :shown])
        {:keys [wire st tx]} (build-wire-monitored st main-db bdb aid
                                                   (cond-> {:vision? vis?}
                                                     (seq prev-shown) (assoc :delta-already-shown prev-shown)
                                                     snap (assoc :viewport-screenshot snap)))
        final-msgs (preview/attach-preview-frames-to-messages (:messages wire) preview-frames vis?)
        router? (boolean (or (:router? frame)
                             (:seon.agent/router? (datahike.api/pull main-db (quote [:seon.agent/router?]) [:seon.agent/id aid]))))]
    [(cond-> (assoc-in st [:model-reqs (:req-id frame)] {:pid pid :prefix-hash (:prefix-hash wire)})
       (and (contains? (:live st) aid) (map? (:shown wire)))
       (assoc-in [:live aid :shown] (merge prev-shown (:shown wire))))
     (cond-> {:request [(assoc frame :router? router? :wire-messages final-msgs)]}
       (seq tx) (assoc :tx tx))]))

(defn plan-derived-previews
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :any :any] :any]}
  [main-db bdb evals theme]
  (let [edited-vars (take 2 (preview/recent-edited-renderer-vars main-db bdb evals))
        widths (or (store/config-val main-db :seon.config/preview-widths) [360 640])]
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

(defn handle-browser-res
  {:malli/schema [:=> [:cat :map :map] :any], :seon.gen/skip true}
  [st msg]
  (let [req-id (:req-id msg)]
    (if-let [entry (get-in st [:pending-browser req-id])]
      (maybe-complete-pending-browser st req-id (assoc entry :shot-done? true :snap (:snapshot msg)))
      [st nil])))

(defn handle-preview-res
  {:malli/schema [:=> [:cat :map :map] :any], :seon.gen/skip true, :seon.flow/edge true}
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

(defn latest-turn-painted-ui?
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] :any] :boolean]}
  [main-db bdb evals]
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
