(ns seon.render.proc
  "Root's render proc (:render, a :compute step-fn, declared in resources/seon/system.edn):
  the views proc of W1 §5.1, and proto/web's render loop (proto.web/step) made a step-fn.
  proto/web ran it as a raw thread around an atom; here it is a function of
  (state, in, msg) -> [state' outputs], and every effect is a message:

    in :events   every event of root (a tap of the api's events), drained per step
    in :answers  the api's answers to this proc's requests (root's view; main's db; an
                 agent's branch db), and to the web proc's calls
    in :from-web the views open in tabs, and requests for a page or a keyframe
    out :to-api  requests ({:op :request :req .. :route [:render :answers] :tag ..})
    out :to-web  patches per view, keyframes, pages, the agents' index

  No beat and no ping (owner, 2026-10-21: "a reactive system where we know if there's a
  problem with the proc"): health is what flows, each proc's recorded defects; a value no
  event carries is its owner's to emit. A pass: an event asks the api for its view; while that request is in
  flight, more events only queue, so a burst becomes one pass (the round trip is the
  coalescing window: no step sleeps). The answer is joined with health, main's tasks and
  attempts and the events into the system value (:seon.render/system), whose :ai face is
  root's context. Each page a tab watches is an entity's (seon.render/page: / root's, /agent/{id}
  an agent's in root's boot, /walk/{eid} any entity's), walked on main and, for an agent, on its
  branch as last read, and laid out by seon.render.ui/page into units. Then, per unit: inputs
  before render (an unchanged input renders nothing), bytes before send (an unchanged fragment
  sends nothing); a page renders only the rows a tab has scrolled to (:more raises them). The web
  proc serializes each patch once and fans it out; a tab that opened or fell behind gets a
  keyframe composed here from the retained fragments, rendering nothing."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.edn :as sedn] [seon.profile :as profile] [seon.contract :refer [defn defn-]] [seon.problems :as problems] [clojure.string :as str]
            [clojure.core.async :as a] [clojure.core.async.flow :as flow] [datahike.api :as d]
            [seon.lib :as lib] [seon.render :as render] [seon.render.ui :as ui] [seon.render.ai :as ai] [seon.print.select :as select] [seon.repl :as repl] [seon.root :as c]
            [seon.web.html :as h] [seon.task :as task] [seon.reconcile :as reconcile]
            [seon.config :as config] [seon.step :as step]))

(defn- event-cap "Events the stream keeps (:seon.config.render.list/stream)." {:malli/schema [:=> [:cat] pos-int?]} [] (config/value :seon.config.render.list/stream))
(defn- history-cap "Rows a page renders at first, and more each time its list's end scrolls into view (:seon.config.render.list/history)." {:malli/schema [:=> [:cat] pos-int?]} [] (config/value :seon.config.render.list/history))

;;; --- requests -------------------------------------------------------------------------

(defn- ask
  "Ask the api for req under kind (seon.step/ask) unless kind is already in flight: the
  answer's pass covers what queued meanwhile (the coalescing window). No clock re-asks: the
  api answers every request it took, a defect included (seon.root/a-proc-answers-every-request-it-took)."
  {:malli/schema [:=> [:cat :map :seon.host/volatile :any :map] :map]}
  [st o kind req]
  (if (some #{kind} (vals (:pending st)))
    st
    (let [[st m] (step/ask st [:render :answers] kind req)] (step/out! o :to-api m) st)))

(defn- ask-state "Ask for root's view." {:malli/schema [:=> [:cat :map :seon.host/volatile] :map]}
  [st o] (ask st o :state {:op :state}))

;;; --- the system value ------------------------------------------------------------------

(defn- proc-health-of
  "Each declared root proc (the built system's, once it is) with the defects root's view
  holds of it: {pid {:defects n :newest message}}; agent procs are not counted here."
  {:malli/schema [:=> [:cat :map [:sequential :map]] :map]}
  [st root-errors]
  (let [sysp (:system-p st)
        pids (when (and sysp (realized? sysp)) (keys (:procs @sysp)))
        by (group-by :pid root-errors)]
    (into (sorted-map-by #(compare (str %1) (str %2)))
          (for [pid pids :when (keyword? pid)]
            [pid (let [es (get by pid)] (cond-> {:defects (count es)} (seq es) (assoc :newest (:message (peek (vec es))))))]))))

(defn system-value
  "The whole system as one value (:seon.render/system), from the render proc's state:
  root's view (the api's), every proc's health, main (t, datoms, tasks, attempts),
  the events, the render's own numbers."
  {:malli/schema [:=> [:cat :map :int :int] :seon.render/system]}
  [st now-ns now-ms]
  (let [v (:view st)
        ;; root's numbers as root's own `state` shows them (the one decoration)
        n (c/view-numbers v)
        agents (:agents v {})]
    {:agents agents
     :writer (:writer n) :fork-server (:fork-server n) :store (:store n)
     :boot (:boot v) :root-errors (:root-errors v []) :root-errors-total (:root-errors-total v 0)
     ;; health is what flows: each declared proc with the defects root recorded of it (a
     ;; step that threw); no ping, no beat
     :health (proc-health-of st (:root-errors v []))
     ;; merges into main: root's merge! of a branch (its events) and the reconciler's
     ;; merged attempts (main's facts)
     :main (assoc (:main st {}) :merges (+ (:merges st 0) (count (filter #(= :merged (:seon.attempt/status %)) (vals (:attempts st))))))
     :recorded (:recorded st)
     :tasks (:tasks st {}) :attempts (:attempts st {}) :task-rows (:task-rows st {}) :agent-attempt (:agent-attempt st {})
     ;; a stop that arrived before main recorded how the attempt ended says so once main does
     :events (mapv #(ui/ended-as % (:agent %) (:agent-attempt st {})) (:events st [])) :events-seen (:ev-seq st 0)
     :config (:config st)
     :now-ms now-ms}))

;;; --- main --------------------------------------------------------------------------------

(defn task-rows
  "Each task's row as observed, read from the reconciler's index (`seon.reconcile/task-state`):
  {id {:state :blocked-by #{id} :needs :attempts n :tokens n :newest attempt :kind :green n
  :red n :allowed-red n :proofs n}}. The desired execution stays on the task; this is what
  happened."
  {:malli/schema [:=> [:cat :seon.reconcile/index] :map]}
  [idx]
  (into {} (for [[id t] (:tasks idx)
                 :let [as (sort-by :seon.attempt/start-ms (map (:attempts idx) (get-in idx [:by-task id])))
                       results (:seon.attempt/results (last as) {})
                       own (get-in idx [:acc id] #{})]]
             [id {:state (reconcile/task-state idx id) :blocked-by (reconcile/blocked-by idx id) :needs (:seon.task/needs t)
                  :attempts (count as)
                  ;; unknown is not 0: an attempt records its tokens at its end
                  :tokens (when-let [ts (seq (keep :seon.attempt/tokens as))] (reduce + ts)) :newest (last as) :kind (:seon.task/kind t)
                  ;; its own proofs (red blocks it) apart from other tasks' (an allowed red)
                  :green (count (filter #(= :pass (get results %)) own)) :red (count (filter #(and (contains? results %) (not= :pass (get results %))) own))
                  :allowed-red (count (filter (fn [[p r]] (and (not= :pass r) (not (contains? own p)))) results))
                  :proofs (count own)}])))

(defn- main-info
  "What the tiles show of main's db value: t, program vars, tasks and attempts, read when
  main's t moved: O(vars) for the vars (an index count), O(tasks) for the rest, nothing per
  datom of main (seon.render-cost-test/main-info-reads-what-the-page-shows: was a count of
  every datom, 15 ms at 47k datoms, 174 ms on the owner's main). Main's datom count is root's
  state's on demand ((c/state sys {:datoms true}))."
  {:malli/schema [:=> [:cat :map :seon.host/db] :map]}
  [st db]
  (if (= (:max-tx db) (get-in st [:main :t]))
    st
    (let [tasks (task/tasks db) idx (reconcile/index db) attempts (:attempts idx)
          vars (count (d/datoms db :aevt :var/name))]
      (assoc st :main {:t (:max-tx db) :vars vars}
             :main-db db :tasks tasks :attempts attempts
             ;; the config in force, as root wrote it on main's root entity (one pull)
             :config (config/from-db db)
             ;; main's open problems, recorded (D2's one read; ~2.7 ms, costs the problems) and
             ;; derived (dead citations: a set of var names and a lookup per cited name,
             ;; docs/health.md#cost), read when main moved
             :recorded (problems/health db)
             :task-rows (task-rows idx)
             ;; how each agent's work ended, for the words its card and its events use
             :agent-attempt (into {} (for [a (sort-by :seon.attempt/start-ms (vals attempts))] [(:seon.attempt/agent a) a]))))))

;;; --- units, rendered incrementally ---------------------------------------------------------

(defn- render-unit
  "A unit's HTML: its input by the render its schema declares, its unit named (its element's id: :unit)."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:keys [id input schema]}]
  (h/html (render/html input {:schema schema :unit id})))

(defn- step-units
  "Run a layout's units, in the order the layout shows them, against what the view retained: a
  unit whose input is unchanged is skipped; one whose bytes are unchanged sends nothing; a new one
  with a container is added to it, before what the container holds when a unit after it in the
  layout is already there (a list's newest row), else after (a list's older rows, loaded as they
  scroll into view). Answers [view' patch counts]."
  {:malli/schema [:=> [:cat [:maybe :map] [:sequential :map]] [:tuple :map :map :map]]}
  [view units]
  (let [held (:units (or view {}))
        ;; for each unit, whether a unit after it in its container is held already
        before? (second (reduce (fn [[seen acc] {:keys [id container]}]
                                  [(cond-> seen (and container (contains? held id)) (conj container))
                                   (assoc acc id (contains? seen container))])
                                [#{} {}] (rseq (vec units))))]
    (reduce (fn [[view patch n] {:keys [id input container] :as u}]
              (let [old (get-in view [:units id])]
                (if (and old (= input (:input old)))
                  [view patch n]
                  (let [html (render-unit u)
                        view (assoc-in view [:units id] (cond-> {:input input :html html} container (assoc :container container)))]
                    (cond
                      (= html (:html old)) [view patch (update n :same inc)]
                      (and container (nil? old)) [view (update patch (if (before? id) :prepends :appends) conj [container id]) (update n :rendered inc)]
                      :else [view (update patch :frags conj [id html]) (update n :rendered inc)])))))
            [(or view {}) {:frags [] :appends [] :prepends [] :removes []} {:rendered 0 :same 0}]
            units)))

(defn compose
  "html with every slot marker replaced by its unit's retained HTML, recursively."
  {:malli/schema [:=> [:cat :string :map] :string]}
  [html units]
  (str/replace html #"<!--s:([^>]*?)-->" (fn [[_ id]] (compose (get-in units [id :html] "") units))))

(defn- finish-patch
  "A patch's appends and prepends as HTML (a container's new rows before what it holds as one
  prepend, in the layout's order), and the removes of the contained units the layout no longer has."
  {:malli/schema [:=> [:cat :map :map [:set :string]] [:tuple :map :map]]}
  [view patch live-ids]
  (let [gone (for [[id u] (:units view) :when (and (:container u) (not (live-ids id)))] id)
        view (update view :units #(apply dissoc % gone))
        html #(compose (get-in view [:units % :html]) (:units view))]
    [view (-> patch
              (update :appends (fn [as] (vec (for [[c el] as] [c (html el)]))))
              (update :prepends (fn [ps] (vec (for [[c group] (group-by first ps)] [c (apply str (map (comp html second) group))]))))
              (assoc :removes (vec gone)))]))

;;; --- a location's page ------------------------------------------------------------------------

(defn- location
  "The entity view names on main db (root's view st holds its boot): root's for :root, agent id's
  in root's boot for an agent's id, else the entity id it is; nil when main holds none."
  {:malli/schema [:=> [:cat :map :seon.host/db :any] [:maybe pos-int?]]}
  [st db view]
  (cond (= :root view) (:db/id (d/entity db [:seon.root/id :root]))
        (keyword? view) (when-let [boot (get-in st [:view :boot])] (:db/id (d/entity db [:seon.agent/key (task/agent-key boot view)])))
        :else view))

(defn- this-boots
  "Agent entity e's id when it is an agent of root's boot (its key is that boot's), else nil: an
  id like :a3 starts again each boot, so only this boot's names a process root holds."
  {:malli/schema [:=> [:cat :map :any] [:maybe :keyword]]}
  [st e]
  (let [id (:seon.agent/id e) boot (get-in st [:view :boot])]
    (when (and id boot (= (:seon.agent/key e) (task/agent-key boot id))) id)))

(defn- page-value
  "View's page: the entity it names walked on main and, for an agent, its branch as this proc last
  read it (seon.render/page), laid out by seon.render.ui/page with its focus's input at the top
  (root's: spawn and evaluate in every agent, with main's open problems a tile; an agent's: its
  REPL and its messages) and the rows its view has scrolled to; nil when main is not read yet or
  holds no such entity."
  {:malli/schema [:=> [:cat :map :any] [:maybe :map]]}
  [st view]
  (let [db (:main-db st)
        eid (when db (location st db view))]
    (when eid
      (let [w (render/page (into [db] (keep identity) [(get-in st [:branches view :db])]) eid)
            agent (get-in w [:seon.render/root :seon.render/value])
            task (some #(when (= :seon.task/task-fact (:seon.render/schema %)) (:seon.render/value %)) (:seon.render/nodes w))
            input (cond (= :root view) {:schema :seon.render/root-input :value {}}
                        (:seon.agent/id agent)
                        {:schema :seon.render/agent-input
                         :value {:id (:seon.agent/id agent)
                                 ;; root's view of it in this boot: an agent of another boot has no process
                                 :live? (boolean (when-let [id (this-boots st agent)] (ui/live (ui/shown-status (get-in st [:view :agents id] {})))))
                                 :held (when (= :held (:seon.task/execution task)) (:seon.task/id task))}})
            ;; root's focus: main's open problems, a part of its page by their own render
            w (cond-> w (= :root view) (update :seon.render/nodes conj {:seon.render/kind :value :seon.render/value (:recorded st)
                                                                        :seon.render/schema :seon.problems/report :seon.render/eid eid}))]
        (when-not (:seon.error/type w)
          (ui/page w {:shown (get-in st [:shown view] (history-cap)) :view (ui/view-param view) :input input}))))))

;;; --- the pass ------------------------------------------------------------------------------------

(defn- pass-view
  "One view rendered: [st' patch-message-or-nil]; a view whose entity main does not hold yet renders nothing."
  {:malli/schema [:=> [:cat :map :any] [:tuple :map [:maybe :map]]]}
  [st view-id]
  (if-let [out (page-value st view-id)]
    (let [units (:seon.render/units out)
          skeleton (h/html (:seon.render/html out))
          [view patch n] (step-units (get-in st [:views view-id]) units)
          [view patch] (finish-patch view patch (set (map :id units)))
          view (assoc view :skeleton skeleton)
          st (-> st (assoc-in [:views view-id] view)
                 (update-in [:counts :rendered] (fnil + 0) (:rendered n)) (update-in [:counts :same] (fnil + 0) (:same n)))]
      [st (when (or (seq (:frags patch)) (seq (:appends patch)) (seq (:prepends patch)) (seq (:removes patch)))
            (assoc patch :op :patch :view view-id))])
    [st nil]))

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
    (let [t0 (profile/now-ns)
          now-ms (System/currentTimeMillis)
          ;; the stream, newest first: each event numbered, its row a unit of root's layout
          seq0 (:ev-seq st 0)
          evs (map-indexed (fn [i e] (assoc e :seq (+ seq0 i 1))) (:new-events st))
          st (cond-> st
               (seq evs) (assoc :events (vec (take (event-cap) (concat (reverse evs) (:events st))))
                                :ev-seq (+ seq0 (count evs)))
               true (assoc :new-events []))
          sys (system-value st t0 now-ms)
          ;; root's context: the system's AI face, also a unit of root's layout
          ctx (render/ai sys {:schema :seon.render/system})
          sys (assoc sys :context {:text ctx :tokens (select/estimate ctx)} :problem-areas (ai/problem-areas sys))
          st (assoc st :context ctx)
          views (into (:watch st #{}) (concat (vals (:waiting st)) (:kf-wanted st)))
          [st patches] (reduce (fn [[st ps] v]
                                 (let [[st p] (pass-view st v)]
                                   [st (cond-> ps p (conj p))]))
                               [st []] (sort-by str views))
          idx (index-of (:agents sys))
          ms (profile/ms-since t0)]
      (doseq [p patches] (step/out! o :to-web p))
      (when (not= idx (:index st)) (step/out! o :to-web {:op :index :agents idx}))
      (doseq [[k v] (:waiting st)] (step/out! o :to-web {:op :page :k k :view v :html (page-html st v)}))
      (doseq [v (:kf-wanted st)] (step/out! o :to-web {:op :keyframe :view v :html (page-html st v)}))
      (-> st (assoc :index idx :waiting {} :kf-wanted #{})
          (update :passes (fnil inc 0))
          (cond-> (seq patches) (assoc :render-ms (lib/r2 ms) :patches (reduce + (map #(+ (count (:frags %)) (count (:appends %)) (count (:prepends %)) (count (:removes %))) patches))))
          (update :pass-ms #(lib/push-window % ms 200))
          (update (if (seq patches) :patch-pass-ms :quiet-pass-ms) #(lib/push-window % ms 200))
          ;; how long the oldest new event waited: its stamp (where it happened) to this pass's end
          (cond-> (seq evs) (update :event-age-ms #(lib/push-window % (- (System/currentTimeMillis) (reduce min (map :at-ms evs))) 200)))
          (update :patch-bytes #(if (seq patches) (lib/push-window % (reduce + (map (fn [p] (+ (reduce + (map (comp count second) (:frags p)))
                                                                                          (reduce + (map (comp count second) (:appends p)))
                                                                                          (reduce + (map (comp count second) (:prepends p)))))
                                                                                    patches)) 200) %))))))

(defn stats
  "The render proc's own numbers: passes, pass ms (all, the ones that patched, the quiet
  ones) as seon.lib/summ over the last 200 (none yet: nil), patch bytes, units rendered and skipped."
  {:malli/schema [:=> [:cat :map] :map]}
  [st]
  (let [q #(some-> % lib/summ)]
    {:passes (:passes st 0) :pass-ms (q (:pass-ms st)) :patch-pass-ms (q (:patch-pass-ms st)) :quiet-pass-ms (q (:quiet-pass-ms st))
     :patch-bytes (q (:patch-bytes st)) :event-age-ms (q (:event-age-ms st)) :rendered (get-in st [:counts :rendered] 0) :same-bytes (get-in st [:counts :same] 0)
     :agents (count (get-in st [:view :agents])) :views (vec (keys (:views st)))}))

;;; --- the step-fn -------------------------------------------------------------------------------

(defn- drain
  "x and whatever else is queued on ch now (at most n), without waiting."
  {:malli/schema [:=> [:cat :any :seon.host/chan :int] [:vector :any]]}
  [x ch n] (loop [acc [x]] (if-let [y (and (< (count acc) n) (a/poll! ch))] (recur (conj acc y)) acc)))

(defn- on-event
  "An event: kept for the stream (merges counted), and the view asked for; an agent's commit
  recorded by its id (ask-branches reads its branch again)."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [st ev]
  (cond-> (update st :new-events conj ev)
    (and (= :merged (:type ev)) (get-in ev [:result :merged?])) (update :merges (fnil inc 0))
    ;; an agent's commit moves its branch: a page of it reads the branch again (its turns and evaluations are written there)
    (and (= :commit (:type ev)) (:agent ev)) (assoc-in [:commits (:agent ev)] (:t ev))))

(defn- ask-branches
  "Ask for the branch db of each watched view's agent when it moved since this proc read it: the
  branch main records of the agent its view names (:seon.agent/branch; kept in the store after it
  ended); it moved when its t in root's view or its last commit event changed. A read in flight is
  left: its answer's pass asks again."
  {:malli/schema [:=> [:cat :map :seon.host/volatile] :map]}
  [st o]
  (let [db (:main-db st)]
    (reduce (fn [st view]
              (let [e (when-let [eid (when db (location st db view))] (d/entity db eid))
                    b (:seon.agent/branch e)
                    id (when e (this-boots st e))
                    at [b (get-in st [:view :agents id :t]) (get-in st [:commits id])]
                    kind [:branch view]]
                (if (and b (not= at (get-in st [:branches view :read])) (not (some #{kind} (vals (:pending st)))))
                  (-> st (assoc-in [:branches view :read] at) (ask o kind {:op :write :req {:op :branch-db :branch b}}))
                  st)))
            ;; the views a tab watches, and a page or keyframe waiting for its pass
            st (into (:watch st #{}) (concat (vals (:waiting st)) (:kf-wanted st))))))

(defn- web-answered
  "The web proc's call (tag [:web var agent form?]) answered r: shown in the stream; root's
  function (its form) recorded on main with this answer, as root's REPL records one."
  {:malli/schema [:=> [:cat :map :seon.host/volatile [:vector :any] :any] :map]}
  [st o [_ v agent form] r]
  (let [err (:seon.error/message r)
        ;; its text as the REPL answers it: the error's render, else the answer's (the
        ;; stream's row cuts it to a cell, seon.render.ui/event-detail)
        detail (if err (render/ai r {}) (:text (render/printed r nil)))]
    (when form
      (let [it (cond-> {:id 0 :tag (if err :err :ret) :form form :origin :ui :printed detail} err (assoc :err r))]
        (step/out! o :to-api {:op :request :req {:op :write :req {:op :tx :branch :db :tx-data (repl/record-tx {:seon.root/id :root} (str "root-ui-" (profile/now-ns)) [it] nil)}}
                              :route [:render :answers] :tag [:web-record]})))
    (ask-state (on-event st {:type :ui :call (str v) :agent agent :at-ms (System/currentTimeMillis) :error err :detail detail}) o)))

(defn- answered
  "The api's answer to request kind."
  {:malli/schema [:=> [:cat :map :seon.host/volatile :any :any] :map]}
  [st o kind r]
  (cond
    ;; the view's request answered an error (a defect, for a request a step lost): taken,
    ;; and the view stays as it was; the next event asks again
    (and (= :state kind) (seon.contract/error? r)) st
    (= :state kind)
    (let [st (assoc st :view r)
          tx (get-in r [:writer :tx-count])
          st (if (not= tx (:main-tx st)) (-> st (assoc :main-tx tx) (ask o :main {:op :write :req {:op :branch-db :branch :db}})) st)]
      ;; a watched agent's branch moved: read its branch db (its turns, evaluations and task)
      (pass (ask-branches st o) o))
    (= :main kind) (if (seon.contract/error? r) st (pass (ask-branches (main-info st (:db r)) o) o))
    ;; an agent's branch db; a branch that is gone (merged and removed) is recorded as such
    (and (vector? kind) (= :branch (first kind)))
    (let [z [:branches (second kind)]
          st (assoc-in st (conj z :db-error) (:seon.error/message r))]
      (pass (if (seon.contract/error? r) st (assoc-in st (conj z :db) (:db r))) o))
    :else st))

(defn render-proc
  "Root's render proc (see the namespace doc)."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:events-mult "the api's events, a mult this proc taps" :graph "a promise of the root graph (unused: nothing is pinged)"
                :system "a promise of the built system (its declared procs, for the health tile)"}
       :ins {:answers "the api's answers" :from-web "views open, page and keyframe requests"}
       :outs {:to-api "requests to the api" :to-web "patches, keyframes, pages, the agents' index"}
       ;; a pass renders what changed; it never waits. :mixed: one thread of its own for
       ;; its life, joined at stop. :compute futurized each pass onto Jolt's cached pool,
       ;; which grows when a pass is submitted before the last worker is idle again
       ;; (session THREADS: root's threads are a declared number)
       :workload :mixed
       :ping-map-fn stats})
  ([args]
   ;; no beat: a pass happens on an event, an api answer or a tab's request, never on a clock
   (let [events (a/chan (a/sliding-buffer 4096))]
     (a/tap (:events-mult args) events)
     {:events-ch events :mult (:events-mult args) :system-p (:system args) :rid 0 :pending {} :watch #{} :waiting {} :kf-wanted #{}
      :new-events [] :events [] :views {} :branches {} :merges 0
      ::flow/in-ports {:events events}}))
  ([st tr]
   (when (= tr ::flow/stop)
     (a/untap (:mult st) (:events-ch st)))
   st)
  ([st in m]
   (let [o (volatile! {})
         busy? (seq (:pending st))
         st (case in
              :events (if (nil? m) st (ask-state (reduce on-event st (drain m (:events-ch st) 2048)) o))
              :answers (let [[st' kind] (step/answered st (:tag m))
                             r (:r m)
                             err (:seon.error/message r)]
                         (cond
                           kind (answered st' o kind r)
                           (and (vector? (:tag m)) (= :web (first (:tag m)))) (web-answered st o (:tag m) r)
                           :else st))
              :from-web (case (:op m)
                          :views (ask-state (assoc st :watch (:views m) :tabs (:tabs m)) o)
                          ;; a UI call root refused before it became a request: answered as root's are
                          :refused (web-answered st o (:tag m) (:r m))
                          :page (if (and (get-in st [:views (:view m)]) (contains? (:watch st) (:view m)))
                                  (do (step/out! o :to-web {:op :page :k (:k m) :view (:view m) :html (page-html st (:view m))}) st)
                                  (ask-state (assoc-in st [:waiting (:k m)] (:view m)) o))
                          :keyframe (if (get-in st [:views (:view m)])
                                      (do (step/out! o :to-web {:op :keyframe :view (:view m) :html (page-html st (:view m))}) st)
                                      (ask-state (update st :kf-wanted conj (:view m)) o))
                          :context (do (step/out! o :to-web {:op :text :k (:k m) :text (:context st "")}) st)
                          ;; the end of a view's list scrolled into view: the next rows are rendered
                          :more (pass (update-in st [:shown (:view m)] (fnil + (history-cap)) (history-cap)) o)
                          :stats (do (step/out! o :to-web {:op :text :k (:k m) :text (sedn/encode (stats st))}) st)
                          st)
              st)]
     ;; nothing asked is in flight after what was, or after what went to the tabs: their
     ;; views are current (the web proc says so on each feed; seon.check/idle waits on it)
     (when (and (empty? (:pending st)) (or busy? (seq (:to-web @o)))) (step/out! o :to-web {:op :settled}))
     [st @o])))
