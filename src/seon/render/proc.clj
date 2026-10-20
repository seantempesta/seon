(ns seon.render.proc
  "Root's render proc (:render, a :compute step-fn, declared in resources/seon/system.edn):
  the views proc of W1 §5.1, and proto/web's render loop (proto.web/step) made a step-fn.
  proto/web ran it as a raw thread around an atom; here it is a function of
  (state, in, msg) -> [state' outputs], and every effect is a message:

    in :events   every event of root (a tap of the api's events), drained per step
    in :tick     an edge sampler's beat (every SAMPLE-MS): every root proc's health, as
                 Flow's ping of it (blocking reads belong to the edge, not the step)
    in :answers  the api's answers to this proc's requests (root's view; main's db; an
                 agent's branch db), and to the web proc's calls
    in :from-web the views open in tabs, and requests for a page or a keyframe
    out :to-api  requests ({:op :request :req .. :route [:render :answers] :tag ..})
    out :to-web  patches per view, keyframes, pages, the agents' index

  A pass: an event (or a beat) asks the api for its view; while that request is in
  flight, more events only queue, so a burst becomes one pass (the round trip is the
  coalescing window: no step sleeps). The answer is joined with health, main's tasks and
  attempts, and the series into the system value (:seon.render/system); its declared
  render (seon.render.ui/system-page) answers the layout and its units. Then, per unit:
  inputs before render (an unchanged input renders nothing), bytes before send (an
  unchanged fragment sends nothing). The web proc serializes each patch once and fans it
  out; a tab that opened or fell behind gets a keyframe composed here from the retained
  fragments, rendering nothing."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [clojure.string :as str] [clojure.edn :as edn]
            [clojure.core.async :as a] [clojure.core.async.flow :as flow] [datahike.api :as d]
            [seon.lib :as lib] [seon.render :as render] [seon.render.ui :as ui] [seon.render.ai :as ai]
            [seon.web.html :as h] [seon.task :as task] [seon.context :as context] [seon.print :as print]))

(def SAMPLE-MS "The sampler's beat: health pings and the tiles' series." 500)
(def EVENT-CAP "Events the stream keeps." 60)
(def SERIES-CAP "Points a sparkline keeps." 60)
(def RETIRE-MS "An ended agent leaves the grid this long after it ended." 90000)
(def HISTORY-CAP "Evaluations an agent's page shows." 100)

;;; --- the edge sampler ---------------------------------------------------------------

(defn- sample!
  "One beat: every root proc's health (Flow's ping of it, :unknown past bound-ms), the
  agents' stand-ins left out (their status is the api's view)."
  {:malli/schema [:=> [:cat :any [:sequential :keyword] :int] :map]}
  [g pids bound-ms]
  (into {} (for [pid pids]
             [pid (if-let [m (flow/ping-proc g pid :timeout-ms bound-ms)]
                    {:status (::flow/status m) :count (::flow/count m) :state (::flow/state m)}
                    :unknown)])))

(defn- start-sampler!
  "The edge reader: a thread whose blocking reads (the clock, each proc's ping) feed the
  :tick in-port (a sliding buffer of one: a late beat is replaced, never queued). It ends
  when stop is delivered."
  {:malli/schema [:=> [:cat :any :any :seon.host/chan :seon.host/chan] :any]}
  [graph-p system-p tick stop]
  (doto (Thread. (fn []
                   (loop []
                     (when-not (a/poll! stop)
                       (let [g @graph-p
                             pids (sort-by str (keys (:procs @system-p)))
                             t0 (lib/now-ns)
                             h (sample! g pids 250)]
                         (a/>!! tick {:health h :ms (System/currentTimeMillis) :ping-ms (lib/ms-since t0)})
                         (let [[_ c] (a/alts!! [stop (a/timeout SAMPLE-MS)])] (when-not (= c stop) (recur))))))))
    (.setDaemon true) (.start)))

;;; --- requests -------------------------------------------------------------------------

(defn- ask
  "st with a request to the api pending under kind, and the request message."
  {:malli/schema [:=> [:cat :map :any :map] [:tuple :map :map]]}
  [st kind req]
  (let [rid (inc (:rid st))]
    [(-> st (assoc :rid rid) (assoc-in [:pending rid] kind) (assoc-in [:asked rid] (System/currentTimeMillis)))
     {:op :request :req req :route [:render :answers] :tag rid}]))

(def ASK-BOUND-MS
  "A request unanswered this long counts as gone and is asked again: a step that threw
  keeps its old state (Flow's rule), so its answer's pass never ran; the defect itself is
  in root's errors, shown on the next pass."
  2000)

(defn- pending? {:malli/schema [:=> [:cat :map :any] :boolean]}
  [st kind]
  (let [now (System/currentTimeMillis)]
    (boolean (some (fn [[rid k]] (and (= kind k) (< (- now (get-in st [:asked rid] now)) ASK-BOUND-MS))) (:pending st)))))

(defn- ask-state
  "Ask for root's view unless that request is already in flight (then the answer's pass
  covers what queued meanwhile: the coalescing window)."
  {:malli/schema [:=> [:cat :map :seon.host/volatile] :map]}
  [st o]
  (if (pending? st :state)
    st
    (let [[st m] (ask st :state {:op :state})] (vswap! o update :to-api (fnil conj []) m) st)))

(defn- ask-db
  "Ask the writer (through the api) for branch's db, unless in flight."
  {:malli/schema [:=> [:cat :map :seon.host/volatile :any :keyword] :map]}
  [st o kind branch]
  (if (pending? st kind)
    st
    (let [[st m] (ask st kind {:op :write :req {:op :branch-db :branch branch}})] (vswap! o update :to-api (fnil conj []) m) st)))

;;; --- the system value ------------------------------------------------------------------

(defn- recent-tx-per-s
  "Writer transactions in the last second, from the api's window of [ns n]."
  {:malli/schema [:=> [:cat [:maybe [:sequential :any]] :int] :double]}
  [recent now] (double (reduce + 0 (keep (fn [[t n]] (when (< (- now t) 1000000000) n)) recent))))

(defn- order
  "The grid's agents: by id, the ones ended over RETIRE-MS ago left out."
  {:malli/schema [:=> [:cat :map :int] [:vector :keyword]]}
  [agents now-ms]
  (vec (sort-by (fn [id] [(count (str id)) (str id)])
                (for [[id a] agents :when (not (and (:ended-ms a) (> (- now-ms (:ended-ms a)) RETIRE-MS)))] id))))

(defn system-value
  "The whole system as one value (:seon.render/system), from the render proc's state:
  root's view (the api's), every proc's health, main (t, datoms, tasks, attempts), the
  series, the events, the render's own numbers."
  {:malli/schema [:=> [:cat :map :int :int] :seon.render/system]}
  [st now-ns now-ms]
  (let [v (:view st)
        {:keys [batches commits batched-tx lag-ms fork-ms]} (:stats v)
        tpb (when batches (lib/r2 (/ (double (or batched-tx 0)) batches)))
        agents (:agents v {})]
    {:agents agents
     :order (order agents now-ms)
     :writer (-> (:writer v) (dissoc :recent :batch-ms)
                 (assoc :tx-per-s (recent-tx-per-s (get-in v [:writer :recent]) now-ns)
                        :batch-p50-ms (lib/p50 (get-in v [:writer :batch-ms])) :tx-per-batch tpb))
     :server (assoc (:server v) :lag-ms (lib/p50 lag-ms) :fork-ms-p50 (lib/p50 fork-ms))
     :store (cond-> (:store v) batches (assoc :batches batches :commits commits :tx-per-batch tpb))
     :boot (:boot v) :root-errors (:root-errors v [])
     :health (:health st {}) :health-ms (:health-ms st)
     ;; merges into main: root's merge! of a branch (its events) and the reconciler's
     ;; merged attempts (main's facts)
     :main (assoc (:main st {}) :merges (+ (:merges st 0) (count (filter #(= :merged (:seon.attempt/status %)) (vals (:attempts st))))))
     :tasks (:tasks st {}) :attempts (:attempts st {})
     :series (:series st {}) :events (:events st [])
     :web (:web-sample st {})
     :now-ms now-ms}))

;;; --- main --------------------------------------------------------------------------------

(defn- main-info
  "What the tiles show of main's db value: t, datoms, program vars, tasks and attempts
  (read when main's t moved: O(datoms) for the count, O(tasks) for the rest)."
  {:malli/schema [:=> [:cat :map :seon.host/db] :map]}
  [st db]
  (if (= (:max-tx db) (get-in st [:main :t]))
    st
    (assoc st :main {:t (:max-tx db) :datoms (count (d/datoms db :eavt)) :vars (count (d/datoms db :aevt :var/name))}
           :main-db db :tasks (task/tasks db) :attempts (task/attempts db))))

;;; --- units, rendered incrementally ---------------------------------------------------------

(defn- render-unit
  "A unit's HTML: its render applied to its input (an agent's card takes the entry with its
  id), or the input's schema-declared render."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:keys [input render schema agent]}]
  (let [v (if agent (assoc input :id agent) input)
        env {:seon.render/schema schema}]
    (h/html (if render
              (:seon.render/html ((requiring-resolve render) v env))
              (render/html v env)))))

(defn- step-units
  "Run a layout's units against what the view retained: a unit whose input is unchanged is
  skipped; one whose bytes are unchanged sends nothing. Answers [view' patch counts]."
  {:malli/schema [:=> [:cat [:maybe :map] [:sequential :map]] [:tuple :map :map :map]]}
  [view units]
  (reduce (fn [[view patch n] {:keys [id input shell container] :as u}]
            (let [old (get-in view [:units id])]
              (if (and old (= input (:input old)))
                [view patch n]
                (let [html (render-unit u)
                      view (assoc-in view [:units id] {:input input :html html})
                      view (cond-> view (and shell (not (get-in view [:units shell])))
                                   (assoc-in [:units shell] {:html (h/html (ui/card-shell (:agent u)))}))]
                  (cond
                    (:keyframe-only u) [view patch n]
                    (= html (:html old)) [view patch (update n :same inc)]
                    (and container (nil? old)) [view (update patch :appends conj [container shell]) (update n :rendered inc)]
                    :else [view (update patch :frags conj [id html]) (update n :rendered inc)])))))
          [(or view {}) {:frags [] :appends [] :removes []} {:rendered 0 :same 0}]
          units))

(defn compose
  "html with every slot marker replaced by its unit's retained HTML, recursively."
  {:malli/schema [:=> [:cat :string :map] :string]}
  [html units]
  (str/replace html #"<!--s:([^>]*?)-->" (fn [[_ id]] (compose (get-in units [id :html] "") units))))

(defn- finish-patch
  "A patch's appends composed (a new card's shell with its body), and the removes of the
  units the layout no longer has."
  {:malli/schema [:=> [:cat :map :map [:set :string]] [:tuple :map :map]]}
  [view patch live-ids]
  (let [gone (remove live-ids (filter #(str/starts-with? % "cb-") (keys (:units view))))
        shells (map #(str "card-" (subs % 3)) gone)
        view (update view :units #(apply dissoc % (concat gone shells)))]
    [view (-> patch
              (update :appends (fn [as] (vec (for [[c shell] as] [c (compose (get-in view [:units shell :html]) (:units view))]))))
              (assoc :removes (vec shells)))]))

;;; --- one agent, zoomed ------------------------------------------------------------------------

(defn- attempt-of
  "The newest attempt agent id works (from main's attempts), or nil."
  {:malli/schema [:=> [:cat :map :keyword] [:maybe :map]]}
  [attempts id]
  (last (sort-by :seon.attempt/start-ms (filter #(= id (:seon.attempt/agent %)) (vals attempts)))))

(defn- eval-records
  "The evaluations recorded on branch db for agent id: [[eval-id record] ...], oldest
  first, the newest HISTORY-CAP."
  {:malli/schema [:=> [:cat :seon.host/db :keyword] [:vector :any]]}
  [db id]
  (let [rows (d/q '[:find ?eid ?r :in $ ?id :where [?a :seon.agent/id ?id] [?e :seon.cluster.eval/agent ?a]
                    [?e :seon.cluster.eval/id ?eid] [?e :seon.cluster.eval/record ?r]] db id)
        key (fn [[eid]] (let [[run ord] (str/split eid #"/")] [(or (some-> run (str/split #"-") last parse-long) 0) (or (parse-long (str ord)) 0)]))]
    (vec (take-last HISTORY-CAP (sort-by key rows)))))

(defn- context-of
  "The current-state block the agent's model reads, printed under a display budget, or
  why there is none."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :map] :map] :map]}
  [db attempt ag]
  (let [tid (:seon.attempt/task attempt)
        t (when (and db tid) (task/task db tid))]
    (cond
      (nil? attempt) {:why "it works no task (spawned by hand, not by the reconciler): its model has no context"}
      (nil? t) {:why (str "task " tid " is not on its branch yet")}
      :else
      (let [block (context/current-state db t {:results (:seon.attempt/results attempt {})
                                               :reds (vec (for [r (get-in ag [:tests :reports]) :when (not= :pass (:type r))]
                                                            (assoc (select-keys r [:message :expected :actual]) :test (:var r))))
                                               :last-items (vec (take-last 3 (:evals ag)))
                                               :turn (or (:seon.attempt/turns attempt) 0)
                                               :max-turns (get-in attempt [:seon.attempt/env :max-turns] 0)})
            text (print/text block 2000)]
        {:text text :tokens (ai/tokens text)}))))

(defn- zoom-value
  "The agent page's value (:seon.render/agent-page) for agent id, and the zoom's state with
  each new evaluation's row rendered once."
  {:malli/schema [:=> [:cat :map :keyword] [:tuple :map :map]]}
  [st id]
  (let [ag (assoc (get-in st [:view :agents id] {:status :unknown}) :id id)
        z (get-in st [:zoom id] {})
        attempt (attempt-of (:attempts st) id)
        db (:db z)
        records (if db (eval-records db id) (vec (map-indexed (fn [i e] [(str "v/" i) e]) (:evals ag))))
        rows (reduce (fn [rows [eid r]]
                       (if (contains? rows eid)
                         rows
                         (assoc rows eid (h/html (:seon.render/html (ui/history-row (if (string? r) (edn/read-string r) r) {:seon.render/budget 400}))))))
                     (select-keys (:rows z {}) (map first records)) records)
        ctx-key [(:t z) (:seon.attempt/id attempt) (:seon.attempt/turns attempt) (:tests ag)]
        ctx-key (conj ctx-key (some? db) (get-in st [:main :t]))
        ;; its branch's db, else main's (the task is main's; a merged branch may be gone)
        ctx (if (= ctx-key (:ctx-key z)) (:ctx z)
                (cond-> (context-of (or db (:main-db st)) attempt ag)
                  (and (nil? db) (:main-db st)) (assoc :from :main)))]
    [(assoc-in st [:zoom id] (assoc z :rows rows :ctx-key ctx-key :ctx ctx))
     {:agent ag :attempt attempt :task (when attempt (get (:tasks st) (:seon.attempt/task attempt)))
      :context ctx :history (vec (reverse (map #(get rows (first %)) records)))}]))

;;; --- the pass ------------------------------------------------------------------------------------

(defn- pass-view
  "One view rendered: [st' patch-message-or-nil]."
  {:malli/schema [:=> [:cat :map :any :map] [:tuple :map [:maybe :map]]]}
  [st view-id sys]
  (let [[st value schema] (if (= :root view-id)
                            [st sys :seon.render/system]
                            (let [[st v] (zoom-value st view-id)] [st v :seon.render/agent-page]))
        out (render/render-face :html value {:seon.render/schema schema})
        units (:seon.render/units out)
        skeleton (h/html (:seon.render/html out))
        [view patch n] (step-units (get-in st [:views view-id]) units)
        [view patch] (finish-patch view patch (set (map :id units)))
        view (assoc view :skeleton skeleton)
        st (-> st (assoc-in [:views view-id] view)
               (update-in [:counts :rendered] (fnil + 0) (:rendered n)) (update-in [:counts :same] (fnil + 0) (:same n)))]
    [st (when (or (seq (:frags patch)) (seq (:appends patch)) (seq (:removes patch)))
          (assoc patch :op :patch :view view-id))]))

(defn page-html
  "The composed document of view-id from its retained fragments (renders nothing)."
  {:malli/schema [:=> [:cat :map :any] [:maybe :string]]}
  [st view-id]
  (when-let [{:keys [skeleton units]} (get-in st [:views view-id])]
    (compose skeleton units)))

(defn- index-of
  "What the web proc needs to turn a UI call into root's request: each agent's pid, branch
  and status."
  {:malli/schema [:=> [:cat :map] :map]}
  [agents] (into {} (for [[id a] agents] [id (select-keys a [:pid :branch :status])])))

(defn- pass
  "Render every view a tab watches (or a page or keyframe waits for), and answer the
  pages and keyframes that waited."
  {:malli/schema [:=> [:cat :map :seon.host/volatile] :map]}
  [st o]
  (if-not (:view st)
    st
    (let [t0 (lib/now-ns)
          now-ms (System/currentTimeMillis)
          ;; the stream: each new event's row rendered once
          evs (:new-events st)
          ;; each new event's row rendered once, with its own id: the stream patch prepends the
          ;; new rows and removes the ones past the cap (not the whole list)
          seq0 (:ev-seq st 0)
          evs (map-indexed (fn [i e] (assoc e :seq (+ seq0 i 1))) evs)
          new-rows (map (fn [e] [(:seq e) (h/html (:seon.render/html (ui/event-row e {})))]) evs)
          rows (vec (concat (reverse new-rows) (:event-rows st)))
          st (cond-> st
               (seq evs) (assoc :events (vec (take EVENT-CAP (concat (reverse evs) (:events st))))
                                :event-rows (vec (take EVENT-CAP rows))
                                :ev-seq (+ seq0 (count evs)))
               true (assoc :new-events []))
          ev-patch (when (seq new-rows)
                     {:prepends [["#events" (apply str (map second (reverse new-rows)))]]
                      :removes (mapv #(str "ev-" (first %)) (drop EVENT-CAP rows))})
          sys (system-value st t0 now-ms)
          ;; root's context: the system's AI face, also a unit of root's layout
          ctx (render/ai sys {:seon.render/schema :seon.render/system :seon.render/budget 700})
          sys (assoc sys :context {:text ctx :tokens (ai/tokens ctx)})
          st (assoc st :context ctx)
          views (into (:watch st #{}) (concat (vals (:waiting st)) (:kf-wanted st)))
          [st patches] (reduce (fn [[st ps] v]
                                 (let [[st p] (pass-view st v (assoc sys :event-rows (mapv second (:event-rows st))))
                                       p (if (and (= :root v) ev-patch)
                                           (-> (or p {:op :patch :view :root :frags [] :appends [] :removes []})
                                               (assoc :prepends (:prepends ev-patch))
                                               (update :removes into (:removes ev-patch)))
                                           p)]
                                   [st (cond-> ps p (conj p))]))
                               [st []] (sort-by str views))
          idx (index-of (:agents sys))
          ms (lib/ms-since t0)]
      (doseq [p patches] (vswap! o update :to-web (fnil conj []) p))
      (when (not= idx (:index st)) (vswap! o update :to-web (fnil conj []) {:op :index :agents idx}))
      (doseq [[k v] (:waiting st)] (vswap! o update :to-web (fnil conj []) {:op :page :k k :view v :html (page-html st v)}))
      (doseq [v (:kf-wanted st)] (vswap! o update :to-web (fnil conj []) {:op :keyframe :view v :html (page-html st v)}))
      (-> st (assoc :index idx :waiting {} :kf-wanted #{})
          (update :passes (fnil inc 0))
          (cond-> (seq patches) (assoc :render-ms (lib/r2 ms) :patches (reduce + (map #(+ (count (:frags %)) (count (:appends %)) (count (:prepends %)) (count (:removes %))) patches))))
          (update :pass-ms #(lib/push-window % ms 200))
          (update (if (seq patches) :patch-pass-ms :quiet-pass-ms) #(lib/push-window % ms 200))
          (update :patch-bytes #(if (seq patches) (lib/push-window % (reduce + (map (fn [p] (+ (reduce + (map (comp count second) (:frags p)))
                                                                                          (reduce + (map (comp count second) (:appends p)))
                                                                                          (reduce + (map (comp count second) (:prepends p)))))
                                                                                    patches)) 200) %))))))

(defn stats
  "The render proc's own numbers: passes, pass ms (all, the ones that patched, the quiet
  ones) as p50/p95/max over the last 200, patch bytes, units rendered and skipped."
  {:malli/schema [:=> [:cat :map] :map]}
  [st]
  (let [q (fn [xs] (when (seq xs) (let [v (vec (sort xs)) n (count v)]
                                    {:n n :p50 (lib/r2 (v (quot n 2))) :p95 (lib/r2 (v (min (dec n) (int (* 0.95 n))))) :max (lib/r2 (peek v))})))]
    {:passes (:passes st 0) :pass-ms (q (:pass-ms st)) :patch-pass-ms (q (:patch-pass-ms st)) :quiet-pass-ms (q (:quiet-pass-ms st))
     :patch-bytes (q (:patch-bytes st)) :rendered (get-in st [:counts :rendered] 0) :same-bytes (get-in st [:counts :same] 0)
     :agents (count (get-in st [:view :agents])) :views (vec (keys (:views st)))}))

;;; --- the step-fn -------------------------------------------------------------------------------

(defn- drain
  "x and whatever else is queued on ch now (at most n), without waiting."
  {:malli/schema [:=> [:cat :any :seon.host/chan :int] [:vector :any]]}
  [x ch n] (loop [acc [x]] (if-let [y (and (< (count acc) n) (a/poll! ch))] (recur (conj acc y)) acc)))

(defn- sample
  "A beat: health, and the series the tiles draw (one point a beat)."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [st {:keys [health ms ping-ms]}]
  (let [v (:view st)
        push (fn [xs x] (vec (take-last SERIES-CAP (conj (or xs []) (double (or x 0))))))]
    (-> st (assoc :health health :health-ms ms :ping-ms ping-ms)
        (update-in [:series :tx] push (recent-tx-per-s (get-in v [:writer :recent]) (lib/now-ns)))
        (update-in [:series :fork] push (lib/p50 (get-in v [:stats :fork-ms])))
        (update-in [:series :datoms] push (get-in st [:main :datoms]))
        (assoc :web-sample {:passes (:passes st 0) :render-ms (:render-ms st) :patches (:patches st 0)
                            :tabs (:tabs st 0) :bytes-per-s (:bytes-per-s st 0)}))))

(defn- on-event
  "An event: kept for the stream (merges counted), and the view asked for."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [st ev]
  (cond-> (update st :new-events conj ev)
    (and (= :merged (:type ev)) (get-in ev [:result :merged?])) (update :merges (fnil inc 0))))

(defn- answered
  "The api's answer to request kind."
  {:malli/schema [:=> [:cat :map :seon.host/volatile :any :any] :map]}
  [st o kind r]
  (cond
    (= :state kind)
    (let [st (assoc st :view r)
          tx (get-in r [:writer :tx-count])
          st (if (not= tx (:main-tx st)) (-> st (assoc :main-tx tx) (ask-db o :main :db)) st)
          ;; a watched agent's branch moved: read its branch db (its evaluations, its task)
          st (reduce (fn [st id]
                       (let [a (get-in r [:agents id])]
                         (if (and a (:branch a) (not= (:t a) (get-in st [:zoom id :t])))
                           (-> st (assoc-in [:zoom id :t] (:t a)) (ask-db o [:branch id] (:branch a)))
                           st)))
                     st (filter keyword? (:watch st)))]
      (pass st o))
    (= :main kind) (if (:ok r) (pass (main-info st (:db r)) o) st)
    ;; an agent's branch db; a branch that is gone (merged and removed) is recorded as such
    (and (vector? kind) (= :branch (first kind)))
    (let [z [:zoom (second kind)]
          st (assoc-in st (conj z :db-error) (when-not (:ok r) (:error r)))]
      (pass (if (:ok r) (assoc-in st (conj z :db) (:db r)) st) o))
    :else st))

(defn render-proc
  "Root's render proc (see the namespace doc)."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:events-mult "the api's events, a mult this proc taps" :graph "a promise of the root graph"
                :system "a promise of the built system (its procs are pinged)"}
       :ins {:answers "the api's answers" :from-web "views open, page and keyframe requests"}
       :outs {:to-api "requests to the api" :to-web "patches, keyframes, pages, the agents' index"}
       ;; a pass renders what changed; it never waits
       :workload :compute
       :ping-map-fn (fn [st] {:passes (:passes st 0) :views (count (:views st)) :watching (count (:watch st))
                              :pending (count (:pending st)) :rendered (get-in st [:counts :rendered] 0)
                              :same (get-in st [:counts :same] 0) :pass-p50-ms (lib/p50 (:pass-ms st))})})
  ([args]
   (let [events (a/chan (a/sliding-buffer 4096))
         tick (a/chan (a/sliding-buffer 1))
         stop (a/promise-chan)]
     (a/tap (:events-mult args) events)
     (start-sampler! (:graph args) (:system args) tick stop)
     {:events-ch events :stop stop :mult (:events-mult args) :rid 0 :pending {} :watch #{} :waiting {} :kf-wanted #{}
      :new-events [] :events [] :event-rows [] :views {} :zoom {} :series {} :merges 0
      ::flow/in-ports {:events events :tick tick}}))
  ([st tr]
   (when (= tr ::flow/stop)
     (a/put! (:stop st) true)
     (a/untap (:mult st) (:events-ch st)))
   st)
  ([st in m]
   (let [o (volatile! {})
         st (case in
              :events (if (nil? m) st (ask-state (reduce on-event st (drain m (:events-ch st) 2048)) o))
              :tick (if (nil? m) st (ask-state (sample st m) o))
              :answers (if-let [kind (get-in st [:pending (:tag m)])]
                         (answered (-> st (update :pending dissoc (:tag m)) (update :asked dissoc (:tag m))) o kind (:r m))
                         ;; the web proc's calls are answered here: shown in the stream
                         (if (and (vector? (:tag m)) (= :web (first (:tag m))))
                           (ask-state (on-event st {:type :ui :call (str (nth (:tag m) 2)) :agent (nth (:tag m) 3 nil)
                                                    :at-ms (System/currentTimeMillis)
                                                    :detail (let [r (:r m)] (cond (and (map? r) (string? (:error r))) (str "error: " (:error r))
                                                                                  (map? r) (print/text (dissoc r :reports) 40)
                                                                                  :else nil))
                                                    :error (when (and (map? (:r m)) (string? (:error (:r m)))) (:error (:r m)))})
                                      o)
                           st))
              :from-web (case (:op m)
                          :views (ask-state (assoc st :watch (:views m) :tabs (:tabs m) :bytes-per-s (:bytes-per-s m)) o)
                          :page (if (and (get-in st [:views (:view m)]) (contains? (:watch st) (:view m)))
                                  (do (vswap! o update :to-web (fnil conj []) {:op :page :k (:k m) :view (:view m) :html (page-html st (:view m))}) st)
                                  (ask-state (assoc-in st [:waiting (:k m)] (:view m)) o))
                          :keyframe (if (get-in st [:views (:view m)])
                                      (do (vswap! o update :to-web (fnil conj []) {:op :keyframe :view (:view m) :html (page-html st (:view m))}) st)
                                      (ask-state (update st :kf-wanted conj (:view m)) o))
                          :context (do (vswap! o update :to-web (fnil conj []) {:op :text :k (:k m) :text (:context st "")}) st)
                          :stats (do (vswap! o update :to-web (fnil conj []) {:op :text :k (:k m) :text (pr-str (stats st))}) st)
                          st)
              st)]
     [st @o])))
