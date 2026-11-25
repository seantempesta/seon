(ns seon.web.sse
  "Extracted child namespace of `seon.web`."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [hasch.core :as hasch]
            [ring-chez.adapter :as adapter]
            [seon.render :as render]
            [seon.render.ui :as ui]
            [seon.web.css :as css :refer [datastar-js-text sticky-scroll-js viewport-sync-js]]
            [seon.web.styles :as styles]
            [seon.web.actions :as actions]
            [seon.render.data :as rdata]
            [seon.render.walk :as rwalk]
            [seon.render.surface.entity :as sentity]
            [seon.web.router :as router]
            [jolt.io-poller :as poller]
            [ring-chez.middleware.compress :as compress]
            [ring-chez.sse :as rsse]))

(defn page-shell
  {:malli/schema [:function [:=> [:cat :map] :string] [:=> [:cat :map [:maybe :seon.host/db]] :string]], :seon.gen/skip true}
  ([walk] (page-shell walk nil))
  ([walk db]
   (let [header   (get-in walk [:units "seon-header" :html] "")
         sidebar  (get-in walk [:units "seon-sidebar" :html] "")
         view     (rwalk/compose-view-html walk db)
         stream   (get-in walk [:units "seon-stream-unit" :html] "")
         composer (get-in walk [:units "seon-composer-unit" :html] "")]
     (str "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
          "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
          "<title>seon</title>"
          "<link rel=\"icon\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'%3E%3Ccircle cx='8' cy='8' r='6' fill='%23f0b429'/%3E%3C/svg%3E\">"
          (or (get-in walk [:units "seon-css" :html])
              (str "<style id=\"seon-css\">" ((var-get #'styles/full-css)) "</style>"))
          "<script type=\"module\" src=\"/datastar.js\"></script>"
          "<script>" sticky-scroll-js "</script>"
          "<script>" viewport-sync-js "</script><script>" css/hovercard-js "</script><script>" css/url-sync-js "</script>"
          "</head><body>"
          "<div id=\"seon-app\" class=\"app-shell\" data-signals=\"{showThinking:false,statusOpen:false,aggRate:''}\" data-thinking=\"closed\" data-attr:data-thinking=\"$showThinking?'open':'closed'\" data-status=\"closed\" data-attr:data-status=\"$statusOpen?'open':'closed'\">"
          header
          "<div class=\"app-body\">"
          sidebar
          "<main id=\"seon-main\" class=\"stage-scroll\">"
          view
          "</main>"
          "<div id=\"seon-bottom-dock\" class=\"bottom-dock\">"
          composer
          "</div>"
          stream
          "</div></div>"
          "<div hidden data-init=\"@get('/events',{filterSignals:{include:/^$/},retry:'always',retryMaxCount:Infinity,openWhenHidden:true})\"></div>"
          "<div id=\"seon-hovercard\" popover=\"hint\" role=\"tooltip\" class=\"hovercard\"></div></body></html>"))))

(defn- walk->summary [walk]
  (mapv (fn [uid]
          (let [u (get-in walk [:units uid])]
            {:id uid :reads-count (:reads-count u 0) :reads-idx (:reads-idx u)
             :hit? (boolean (:hit? u)) :ms (or (:ms u) 0.0)}))
        (remove #(= "seon-stream-unit" %) (:order walk))))

(defn advance-walk-and-broadcast!
  {:malli/schema [:function [:=> [:cat :map :any] :any] [:=> [:cat :map :any :any] :any]], :seon.gen/skip true, :seon.flow/edge true}
  ([st tx-datoms] (advance-walk-and-broadcast! st tx-datoms nil))
  ([st tx-datoms reason]
   (let [t0 (System/nanoTime)
         db @(:conn st)
         vs (ui/user-view-state db)
         view-sig [(:view-mode vs) (:viewing-id vs) (:as-of-tx vs) (:viewport-w vs) (:inspect-ref vs) (:lab-prefs vs)]
         use-vt? (rwalk/discrete-view-transition? (:last-view-sig st) view-sig reason)
         clear-stale? (and (seq (:errors st)) (not (#{:stream :call-error :error :report} reason)))
         st-render (if clear-stale? (assoc st :errors []) st)
         specs (ui/page-unit-specs st-render)
         {:keys [walk patches render-errors]} (render/step-walk (:walk st) db tx-datoms specs
                                                                (when use-vt? {:use-view-transition? true :vt-unit-ids #{"seon-view-unit"}}))
         patches (rwalk/effective-patches (:walk st) walk patches db use-vt?)
         agg-rate (ui/aggregate-stream-rate (:live-streams st))
         sig-patch (when (not= agg-rate (:last-agg-rate st "")) (render/datastar-patch-signals {:aggRate agg-rate}))
         out-chunks (cond-> (vec patches) sig-patch (conj sig-patch))
         ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e4)) 100.0)
         fault-reason? (and (vector? reason) (#{:fault :retract-fault :resolve-fault} (first reason)))
         st1 (cond-> (-> st-render (assoc :walk walk :walk-tx (:max-tx db) :last-view-sig view-sig :last-agg-rate agg-rate) (update :frames inc))
               (not= tx-datoms []) (assoc :walk-summary (walk->summary walk))
               (seq render-errors) (assoc :errors (vec (take 20 render-errors))))
         st2 (if (and (seq patches) (not fault-reason?) (:server-in-ch st1))
               (let [vm (or (:view-mode vs) :session)
                     fid (hasch/uuid [:perf :slow-render vm])
                     prev (get-in st1 [:perf-alerts fid])
                     persisted? (boolean (first (d/datoms db :avet :seon.error/id fid)))]
                 (cond
                   (and (> ms 40.0) (or (nil? prev) (> ms (* 1.25 (:ms prev 0.0)))))
                   (do (a/put! (:server-in-ch st1)
                               {:req {:op :record-fault :id fid :class :slow-render :severity :warning :proc :web
                                      :ns 'seon.render.ui :subject (str "seon.render.ui/render:" (name vm))
                                      :message (str "Slow UI render on :" (name vm) " view: " ms " ms (> 40 ms frame budget).")}})
                       (assoc-in st1 [:perf-alerts fid] {:ms ms}))
                   (and (<= ms 35.0) (or prev persisted?))
                   (do (a/put! (:server-in-ch st1) {:req {:op :retract-fault :id fid}})
                       (update st1 :perf-alerts dissoc fid))
                   :else st1))
               st1)]
     (when-let [lv (:live-st-vol st2)] (vreset! lv st2))
     (when-let [sp (:set-page! st2)] (sp (page-shell walk db)))
     (when (and (seq out-chunks) (:broadcast! st2)) ((:broadcast! st2) (apply str out-chunks)))
     st2)))

(defn- viewing-affected? [st aid]
  (let [{:keys [viewing-id view-mode]} (when-let [c (:conn st)] (ui/user-view-state @c))]
    (or (contains? #{:context :debug :system :workspace} view-mode) (= aid viewing-id))))

(defn handle-exit-msg
  {:malli/schema [:=> [:cat :map :map] :any], :seon.gen/skip true, :seon.flow/edge true}
  [st {:keys [ev agent-id phase]}]
(case ev
  (:forked :turn-busy)
  (let [now-ms (System/currentTimeMillis)
        st1 (cond-> (assoc-in st [:agent-states agent-id] :active)
              (and (= :turn-busy ev) (not (contains? (:live-streams st) agent-id)))
              (assoc-in [:live-streams agent-id]
                        {:status :working :phase (or phase :working)
                         :started-ms now-ms :last-ms now-ms :step-chars 0 :chars 0 :reasoning "" :text ""})
              (and phase (contains? (:live-streams st) agent-id))
              (assoc-in [:live-streams agent-id :phase] phase))]
    (advance-walk-and-broadcast! st1 []))
  (:turn-idle :exited)
  (let [st1 (-> st (update :agent-states dissoc agent-id) (update :live-streams dissoc agent-id))]
    (advance-walk-and-broadcast! st1 (if (and (= :turn-idle ev) (viewing-affected? st1 agent-id)) nil [])))
  :branch-moved
  (let [st1 (if (and phase (contains? (:live-streams st) agent-id)) (assoc-in st [:live-streams agent-id :phase] phase) st)]
    (cond (viewing-affected? st1 agent-id) (advance-walk-and-broadcast! st1 nil)
          (not= st1 st) (advance-walk-and-broadcast! st1 [])
          :else st1))
  st))

(defn handle-stream-msg
  {:malli/schema [:=> [:cat :map :map] :any], :seon.gen/skip true, :seon.flow/edge true}
  [st msg]
(let [{:keys [agent-id status text phase context-tokens max-context-tokens]} msg]
  (if (= status :error)
    (advance-walk-and-broadcast!
      (-> st
          (update :live-streams dissoc agent-id)
          (update :agent-states dissoc agent-id)
          (update :errors #(vec (take 20 (cons (select-keys msg [:agent-id :req-id :seon.error/kind :seon.error/id :retry-after-ms :message]) %)))))
      [] :stream)
    (let [now-ms (System/currentTimeMillis) now-ns (System/nanoTime)
          prev (get-in st [:live-streams agent-id]) prev-phase (:phase prev)
          tstr (str (or text "")) dlen (long (count tstr))
          started (long (or (:started-ms prev) now-ms))
          chars (long (or (:chars prev) 0)) step-chars (long (or (:step-chars prev) 0))
          ctx-toks (long (or (when (and (number? context-tokens) (pos? (long context-tokens))) context-tokens) (:context-tokens prev) 0))
          max-ctx (or (when (and (number? max-context-tokens) (pos? (long max-context-tokens))) (long max-context-tokens)) (:max-context-tokens prev))
          with-ctx (fn [m] (when m (cond-> m (pos? ctx-toks) (assoc :context-tokens ctx-toks) max-ctx (assoc :max-context-tokens max-ctx))))
          entry (with-ctx
                  (case status
                    :ctx-meta (or prev {:status :thinking :phase :thinking :started-ms now-ms :last-ms now-ms :step-chars 0 :chars 0 :text "" :reasoning ""})
                    :done (when prev (assoc prev :status :running-tools :phase (or phase :tools) :last-ms now-ms :step-chars 0))
                    :thinking (let [pr (str (:reasoning prev ""))]
                                {:status :thinking :phase (or phase :thinking) :started-ms now-ms :last-ms now-ms :step-chars 0 :chars chars :text ""
                                 :reasoning (if (str/blank? pr) "" (str pr "\n\n── next step ──\n"))})
                    :reasoning-token {:status :reasoning :phase (or phase :thinking) :started-ms started :last-ms now-ms
                                      :step-chars (+ step-chars dlen) :chars (+ chars dlen)
                                      :text (or (:text prev) "") :reasoning (str (or (:reasoning prev) "") tstr)}
                    (:tools :eval :testing :merging :working)
                    (assoc (or prev {:reasoning "" :text "" :chars 0 :step-chars 0})
                           :status status :phase (or phase (if (= status :eval) :tools status)) :started-ms started :last-ms now-ms)
                    {:status status :phase (or phase :writing) :started-ms started :last-ms now-ms
                     :step-chars (+ step-chars dlen) :chars (+ chars dlen)
                     :reasoning (or (:reasoning prev) "") :text (str (or (:text prev) "") tstr)}))
          st1 (if entry
                (cond-> (assoc-in st [:live-streams agent-id] entry)
                  (and (not= status :done) (not= :active (get-in st [:agent-states agent-id])))
                  (assoc-in [:agent-states agent-id] :active))
                st)
          delta-only? (and (or (= status :token) (= status :reasoning-token)) (= prev-phase (:phase entry)) (= (:agent-states st) (:agent-states st1)))
          due? (or (not delta-only?) (>= (- now-ns (long (:last-stream-emit-ns st 0))) 80000000))]
      (if due? (-> st1 (assoc :last-stream-emit-ns now-ns) (advance-walk-and-broadcast! [] :stream)) st1)))))
