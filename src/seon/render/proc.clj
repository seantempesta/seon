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

  No beat and no ping (owner: "a reactive system where we know if there's a
  problem with the proc"): health is what flows, each proc's recorded defects; a value no
  event carries is its owner's to emit. A pass: an event asks the api for its view; while that request is in
  flight, more events only queue, so a burst becomes one pass (the round trip is the
  coalescing window: no step sleeps). The answer is joined with health, main's tasks and
  runs and the events into the system value (:seon.render/system), whose printed value (seon.render.system/system-data) is
  root's context. Each page a tab watches is an entity's (seon.render/walk: / root's, /agent/{id}
  an agent's in root's boot, /walk/{eid} any entity's), walked on main and, for an agent, on its
  branch as last read, and laid out by seon.render.ui/page into units. Then, per unit: inputs
  before render (an unchanged input renders nothing), bytes before send (an unchanged fragment
  sends nothing); a page renders only the rows a tab has scrolled to (:more raises them). The web
  proc serializes each patch once and fans it out; a tab that opened or fell behind gets a
  keyframe composed here from the retained fragments, rendering nothing."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.edn :as sedn] [seon.profile :as profile] [seon.contract :as contract :refer [defn defn-]] [seon.problems :as problems] [clojure.string :as str]
            [clojure.core.async :as a] [clojure.core.async.flow :as flow] [datahike.api :as d]
            [seon.lib :as lib] [seon.render :as render] [seon.render.ui :as ui] [seon.render.system :as system] [seon.print :as print] [seon.print.select :as select] [seon.repl :as repl] [seon.root :as c]
            [seon.web.html :as h] [seon.task :as task] [seon.reconcile :as reconcile] [seon.ctx :as ctx] [seon.db] [datahike.index.interface]
            [seon.config :as config] [seon.step :as step] [seon.context :as context]))

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
  root's view (the api's), every proc's health, main (t, datoms, tasks, runs),
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
     ;; merges into main: the reconciler's merged runs (main's facts)
     :main (assoc (:main st {}) :merges (count (filter #(= :merged (:seon.run/status %)) (vals (:runs st)))))
     :recorded (:recorded st)
     :tasks (:tasks st {}) :runs (:runs st {}) :task-rows (:task-rows st {}) :agent-run (:agent-run st {})
     ;; a stop that arrived before main recorded how the run ended says so once main does
     :events (mapv #(ui/ended-as % (:agent %) (:agent-run st {})) (:events st [])) :events-seen (:ev-seq st 0)
     :config (:config st)
     :now-ms now-ms}))

;;; --- main --------------------------------------------------------------------------------

(defn task-rows
  "Each task's row as observed, read from the reconciler's index (`seon.reconcile/task-state`):
  {id {:state :blocked-by #{id} :needs :runs n :tokens n :newest run :kind :green n
  :red n :allowed-red n :proofs n}}. The desired execution stays on the task; this is what
  happened."
  {:malli/schema [:=> [:cat :seon.reconcile/index] :map]}
  [idx]
  (into {} (for [[id t] (:tasks idx)
                 :let [as (sort-by :seon.run/start-ms (map (:runs idx) (get-in idx [:by-task id])))
                       results (:seon.run/results (last as) {})
                       own (get-in idx [:acc id] #{})]]
             [id {:state (reconcile/task-state idx id) :blocked-by (reconcile/blocked-by idx id) :needs (:seon.task/needs t)
                  :runs (count as)
                  ;; unknown is not 0: a run records its tokens at its end
                  :tokens (when-let [ts (seq (keep :seon.run/tokens as))] (reduce + ts)) :newest (last as) :kind (:seon.task/kind t)
                  ;; its own proofs (red blocks it) apart from other tasks' (an allowed red)
                  :green (count (filter #(= :pass (get results %)) own)) :red (count (filter #(and (contains? results %) (not= :pass (get results %))) own))
                  :allowed-red (count (filter (fn [[p r]] (and (not= :pass r) (not (contains? own p)))) results))
                  :proofs (count own)}])))

(defn- main-info
  "What the tiles show of main's db value: t, program vars, tasks and runs, read when
  main's t moved: O(vars) for the vars (an index count), O(tasks) for the rest, nothing per
  datom of main (seon.render-cost-test/main-info-reads-what-the-page-shows: was a count of
  every datom, 15 ms at 47k datoms, 174 ms on the owner's main). Main's datom count is root's
  state's on demand ((c/state sys {:datoms true}))."
  {:malli/schema [:=> [:cat :map :seon.host/db] :map]}
  [st db]
  ;; a db no newer than the one held (a first read answered after a commit already came) changes nothing
  (if (<= (:max-tx db) (get-in st [:main :t] -1))
    st
    (let [tasks (task/tasks db) idx (reconcile/index db) runs (:runs idx)
          vars (count (d/datoms db :aevt :var/name))]
      (assoc st :main {:t (:max-tx db) :vars vars}
             :main-db db :tasks tasks :runs runs
             ;; the config in force, as root wrote it on main's root entity (one pull)
             :config (config/from-db db)
             ;; main's open problems, recorded (D2's one read; ~2.7 ms, costs the problems) and
             ;; derived (dead citations: a set of var names and a lookup per cited name,
             ;; docs/health.md#cost), read when main moved
             :recorded (problems/health db)
             :task-rows (task-rows idx)
             ;; how each agent's work ended, for the words its card and its events use
             :agent-run (into {} (for [a (sort-by :seon.run/start-ms (vals runs))] [(:seon.run/agent a) a]))))))

;;; --- units, rendered incrementally ---------------------------------------------------------

(defn- render-unit
  "A unit as [hiccup html]: its input by the render its schema declares, its unit named (its
  element's id: :unit); a component's from the proc's cache (comp-entry). The hiccup is kept
  beside the html."
  {:malli/schema [:=> [:cat :map] [:tuple :any :string]]}
  [{:keys [id input schema html hiccup]}]
  (if html
    [hiccup html]
    (let [hc (render/html {:schema schema :unit id} input)] [hc (h/html hc)])))

(defn- step-units
  "Run a layout's units, in the order the layout shows them, against what the view retained: a
  unit whose input is unchanged is skipped; one whose bytes are unchanged sends nothing; a new one
  with a container is added to it where the layout puts it: after the unit before it in its
  container when one is held or placed in this patch and a unit after it is held (a part that sorts
  into the middle), before what the container holds when none is before it (a list's newest row
  at its top, a conversation's older parts), else at its end. Answers [view' patch counts]."
  {:malli/schema [:=> [:cat [:maybe :map] [:sequential :map]] [:tuple :map :map :map]]}
  [view units]
  (let [held (:units (or view {}))
        ;; for each unit, whether a unit after it in its container is held already
        before? (second (reduce (fn [[seen acc] {:keys [id container]}]
                                  [(cond-> seen (and container (contains? held id)) (conj container))
                                   (assoc acc id (contains? seen container))])
                                [#{} {}] (rseq (vec units))))]
    (first
     (reduce (fn [[[view patch n] prev placed] {:keys [id input container] :as u}]
               (let [old (get-in view [:units id])
                     p (get prev container)
                     prev (cond-> prev container (assoc container id))]
                 (if (and old (= input (:input old)))
                   [[view patch n] prev placed]
                   (let [[hiccup html] (render-unit u)
                         view (assoc-in view [:units id] (cond-> {:input input :html html :hiccup hiccup} container (assoc :container container)))]
                     (cond
                       (= html (:html old)) [[view patch (update n :same inc)] prev placed]
                       (and container (nil? old))
                       ;; where it goes: after the unit before it (held, or placed after one), into the
                       ;; prepend before it (a run of new units at the container's start), or at the end
                       (let [how (cond (not (before? id)) :appends
                                       (and p (not= :prepends (get placed p))) :afters
                                       (and p (= :prepends (get placed p))) :prepends
                                       :else :prepends)]
                         [[view (update patch how conj (if (= :afters how) [p id] [container id])) (update n :rendered inc)] prev (assoc placed id how)])
                       :else [[view (update patch :frags conj [id html]) (update n :rendered inc)] prev placed])))))
             [[(or view {}) {:frags [] :appends [] :prepends [] :afters [] :removes []} {:rendered 0 :same 0}] {} {}]
             units))))

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
              ;; a unit placed after its sibling: by the sibling's element id
              (update :afters (fn [as] (vec (for [[sib el] as] [(str "#" sib) (html el)]))))
              (assoc :removes (vec gone)))]))

;;; --- a location's page ------------------------------------------------------------------------

(defn- location
  "The entity view names on main db: root's for :root, the agent of that id in root's boot (its
  key, seon.task/agent-key, or the session's agent it continues) for an agent's id, else the
  entity id it is; nil when main names none (no boot fact yet, or no agent of that id in this boot)."
  {:malli/schema [:=> [:cat :seon.host/db :any] [:maybe pos-int?]]}
  [db view]
  (cond (vector? view) (location db (second view))
        (= :root view) (:db/id (d/entity db [:seon.root/id :root]))
        ;; the process's own key in this boot, else the session's agent it continues
        (keyword? view) (when-let [boot (task/boot-of db)]
                          (or (:db/id (d/entity db [:seon.agent/key (task/agent-key boot view)]))
                              (some (fn [dt] (when (= view (task/this-boots db (d/entity db (:e dt)))) (:e dt)))
                                    (d/datoms db :avet :seon.agent/id view))))
        :else view))

(defn- debug-value
  "View [:debug id]'s page: what agent id's model was sent, each model turn that recorded it
  (:seon.turn/sent) on its branch as this proc last read it, newest first, only the rows its tab
  scrolled to (:shown, as the agent's page: the rest are counted, and load as their end scrolls
  into view). Each row is a component (seon.render.ui/component, as [:debug open? view]): it
  reads its turn and the one before it, shows what its turn added, and its whole request while
  its tab has it open (:open, seon.web's /open); a finished turn is never touched again, so its
  row renders once. nil until the branch is read."
  {:malli/schema [:=> [:cat :map [:tuple [:= :debug] :any]] [:maybe :map]]}
  [st view]
  (if (= :root (second view))
    ;; root has no model turn: what it reads is the system's value printed, held each pass (:context)
    (when-let [ctx (:context st)]
      (ui/debug :root [] 0 {:id "d-context" :input ctx :schema :seon.render/root-context}))
  (when-let [bdb (get-in st [:branches view :db])]
    (let [open (get-in st [:open view] #{})
          shown (get-in st [:shown view] (history-cap))
          ;; newest first: entity ids are issued in the order turns were written
          ts (->> (d/q '[:find [?e ...] :where [?e :seon.turn/sent _]] bdb) sort reverse vec)
          on (take shown ts)
          vp (ui/view-param view)]
      (ui/debug (second view)
                (vec (for [e on :let [id (ui/comp-id e [:debug])]] {:id id :comp [1 e [:debug (contains? open id) vp]]}))
                (- (count ts) (count on)) nil)))))

(def ^:private gone "The agent statuses of an ended process (:seon.agent/status)." (:seon.status/gone (contract/properties :seon.agent/status)))

(defn- agent-of
  "The agent entity view names on main db, and its id in root's boot (nil for an earlier boot's):
  [entity id], or nil when view names no agent."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :any] [:maybe [:tuple :any [:maybe :keyword]]]]}
  [db view]
  (when-let [eid (when (and db (not (vector? view))) (location db view))]
    (let [e (d/entity db eid)] (when (:seon.agent/key e) [e (task/this-boots db e)]))))

(defn- live?
  "Is agent id a process root's view holds, running (it answers what it is asked)?"
  {:malli/schema [:=> [:cat :map :any] :boolean]}
  [st id] (let [a (when id (get-in st [:view :agents id]))] (boolean (and a (not (gone (:status a)))))))

(defn- ask-parts
  "Ask each live agent a watched view shows for its page's parts ({:op :render}, seon.repl/parts)
  when its branch moved since it was last asked (that view's branch read's t), one ask in flight an
  agent, held by its id, so every view of it shows one answer: the agent draws its own page, by its
  own renders."
  {:malli/schema [:=> [:cat :map :seon.host/volatile] :map]}
  [st o]
  (reduce (fn [st view]
            (let [[_ id] (agent-of (:main-db st) view)
                  t (some-> (get-in st [:branches view :db]) :max-tx)]
              (if (and t (live? st id) (not= t (get-in st [:parts id :asked])))
                (-> (assoc-in st [:parts id :asked] t) (ask o [:parts id] {:op :render :id id}))
                st)))
          st (into (:watch st #{}) (concat (vals (:waiting st)) (:kf-wanted st)))))

(defn- agent-page
  "View's page when it names an agent (entity eid on main db): its components as its process drew
  them (ask-parts, seon.repl/parts: the units of its walk), else, with no process to ask or none
  that answered, as root draws them from its branch (seon.ctx/build, drawn once by the same walk
  and layout: seon.render/draw) with main's renders, the page saying so. Everything the page shows
  of the agent is its ctx: its run's status and verdict reach its branch by take!, never read here
  on main. Root's agents (the sidebar) are root's chrome; its input (seon.render.ui/agent-chrome)."
  {:malli/schema [:=> [:cat :map :any :seon.host/db pos-int?] :map]}
  [st view db eid]
  (let [[e id] (agent-of db view)
        held (get-in st [:parts id])
        own? (and (live? st id) (vector? (:units held)))
        berr (get-in st [:branches view :error])
        bdb (get-in st [:branches view :db])
        ;; the branch's record, drawn outside the read set: a move of the branch marks this page dirty
        ;; when root reads it (take-read), and the branch's reads are not main's
        branch-units (fn [] (binding [datahike.index.interface/*reads* nil]
                              (let [w (render/draw (:tree (ctx/build bdb (seon.db/eid bdb :seon.agent/key (:seon.agent/key e)) (:seon.agent/branch e)))
                                                   ctx/changes bdb (requiring-resolve (render/declared :seon.render/render-ctx)))]
                                (try (render/page-units w) (finally (render/dispose-walk! w))))))
        units (if own?
                (:units held)
                (cond bdb (branch-units)
                      ;; a branch that could not be read: its error, by the error's render
                      berr [{:id "p-branch-error" :container "#p-task" :rev 0 :html (h/html [:section {:id "p-branch-error"} (render/html {} berr)])}]))
        root (:db/id (d/entity db [:seon.root/id :root]))]
    (ui/agent-chrome {:units (or units []) :view (ui/view-param view) :head [0 eid]
                      ;; never a wait: drawn from its branch until its process answers, the page says so
                      :caption (when-not own?
                                 (str "drawn by root from its branch with main's renders: "
                                      (cond (:error held) (str "its process did not answer (" (:seon.error/message (:error held)) ")")
                                            (live? st id) "its process has not answered yet"
                                            :else "its process is not running")))
                      ;; its REPL and its messages while its process runs; its held task's release
                      :input {:schema :seon.render/agent-input
                              :value {:id (:seon.agent/id e) :live? (live? st id)
                                      :held (d/q '[:find ?id . :in $ ?a :where [?r :seon.run/agent ?a] [?r :seon.run/task ?t] [?t :seon.task/execution :held] [?t :seon.task/id ?id]] db eid)}}
                      ;; root, then its agents: page chrome, read on main
                      :sides (vec (when root (cons {:eid root :db 0}
                                                   (for [nd (:seon.render/nodes (render/walk [db] root)) :when (= :seon.db/agent-fact (:seon.render/schema nd))]
                                                     {:eid (:seon.render/eid nd) :db 0}))))}
                     (:seon.agent/id e))))

(defn- page-value
  "View's page: the entity it names walked on main and, for an agent, its branch as this proc last
  read it (seon.render/walk), laid out by seon.render.ui/page with its focus's input at the top
  (root's: spawn and evaluate in every agent, with main's open problems a tile; an agent's: its
  REPL and its messages) and the rows its view has scrolled to; nil when main is not read yet,
  :seon.render/no-location when main names no entity for it (an agent's id of no agent in this
  boot), :seon.render/no-entity when it names an entity no db holds."
  {:malli/schema [:=> [:cat :map :any] [:maybe :map]]}
  [st view]
  (let [db (:main-db st)
        eid (when db (location db view))]
    (cond
     (and eid (vector? view)) (debug-value st view)
     (and eid (agent-of db view)) (agent-page st view db eid)
     (and db (nil? eid))
     (contract/error :seon.render/no-location #'seon.render.proc/page-value
                     (str "no " (if (keyword? view) (str "agent " (ui/kname view) " in root's boot " (task/boot-of db)) (ui/kname view))
                          " on main: an agent's id names one of this boot's (ids start again each boot); an earlier boot's agent's page is"
                          " /walk/<its eid>, linked from its root's page")
                     {:view (ui/kname view)})
     eid
      (let [dbs (into [db] (keep identity) [(get-in st [:branches view :db])])
            ;; the rows its tab shows read whole, the older listed (counted) without their data values
            w (render/walk dbs eid {:limit (get-in st [:shown view] (history-cap))})
            ;; a branch that could not be read: its error, by the error's render
            berr (get-in st [:branches view :error])
            w (cond-> w (and berr (not (:seon.error/type w)))
                (update :seon.render/nodes conj {:seon.render/kind :value :seon.render/value berr :seon.render/eid eid}))]
        (if (:seon.error/type w)
          w
          ;; the entity's own view, shown as its page: a view that answers a layout lays it out, its
          ;; input and tiles its own (root's, an agent's: seon.render.ui/root, agent), from what this
          ;; proc holds of the system (root's view of its agents, main's recorded problems); any other
          ;; entity's page is the walk's (seon.render.ui/page)
          (let [opts {:shown (get-in st [:shown view] (history-cap)) :view (ui/view-param view)
                      :agents (get-in st [:view :agents] {}) :recorded (:recorded st)}
                {:seon.render/keys [value schema]} (:seon.render/root w)
                own (when (render/declared schema)
                      (render/html {:as :page :schema schema :page (assoc opts :walk w :dbs dbs :open (get-in st [:open view] #{}))} value))]
            (if (and (map? own) (:seon.render/units own)) own (ui/page opts w))))))))

(defn- ready?
  "Is view's page whole to answer: rendered, and, when its entity is an agent with a branch, that
  branch read (or why it could not be)? A page answered before its branch is read would show the
  agent with none of what its branch records."
  {:malli/schema [:=> [:cat :map :any] :boolean]}
  [st view]
  (let [db (:main-db st)
        b (when db (some->> (location db view) (d/entity db) :seon.agent/branch))]
    (boolean (and (get-in st [:views view])
                  (or (nil? b) (get-in st [:branches view :db]) (get-in st [:branches view :error]))))))

;;; --- the pass ------------------------------------------------------------------------------------

(defn- extra-of
  "What view's page reads besides its dbs: the rows its tab scrolled to and opened, its branch's read
  (whether there is one) and error, its agent's liveness in root's view of this boot, and for root's page main's open
  problems. A page whose dbs no transaction touched walks again only when this changed."
  {:malli/schema [:=> [:cat :map :any] :map]}
  [st view]
  (let [db (:main-db st)
        id (when db (when-let [eid (location db view)] (task/this-boots db (d/entity db eid))))]
    {:shown (get-in st [:shown view]) :open (get-in st [:open view]) :error (get-in st [:branches view :error])
     ;; whether its branch is read at all: what moved on it after that is its datoms-diff
     :branch? (some? (get-in st [:branches view :db]))
     :live (when id (ui/live (ui/shown-status (get-in st [:view :agents id] {}))))
     ;; main's recorded problems, as this proc holds them (a page's own render may show them: root's)
     :recorded (:recorded st)}))

(defn- walk?
  "Does view's page walk this pass: not held yet, a transaction touched what it read (:dirty),
  or what it reads besides its dbs changed (extra-of)."
  {:malli/schema [:=> [:cat :map :any] :boolean]}
  [st view]
  (let [v (get-in st [:views view])]
    (boolean (or (nil? v) (contains? (:dirty st) view) (not= (:extra v) (extra-of st view))))))

(defn- unindex
  "Index ix (key -> set of x) without x under keys ks; a key left with none is gone."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe [:sequential :any]] :any] :map]}
  [ix ks x]
  (reduce (fn [ix k] (let [vs (disj (get ix k) x)] (if (seq vs) (assoc ix k vs) (dissoc ix k)))) (or ix {}) ks))

(defn- index
  "Index ix (key -> set of x) with x under keys ks."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe [:sequential :any]] :any] :map]}
  [ix ks x]
  (reduce (fn [ix k] (update ix k (fnil conj #{}) x)) (or ix {}) ks))

(defn- index-each
  "Index ix (component -> set of views) with view under each of cks."
  {:malli/schema [:=> [:cat [:maybe :map] [:sequential :any] :any] :map]}
  [ix cks view] (reduce (fn [ix ck] (update ix ck (fnil conj #{}) view)) (or ix {}) cks))

(defn- reindex
  "St with view-id's reads (keys, d/reads-index) in the proc's one index from a read key to the
  views that read it (:read-index), its earlier reads (old) taken out."
  {:malli/schema [:=> [:cat :map :any [:maybe [:set :any]] [:set :any]] :map]}
  [st view-id old keys]
  (assoc st :read-index (-> (:read-index st) (unindex (seq old) view-id) (index (seq keys) view-id))))

(defn touched
  "The views a transaction's datoms touched: those whose last walk read a key one of the datoms
  has (d/datom-keys; a schema datom touches every view). One lookup a key a datom, whatever
  the views read (task seon.render/a-view-renders-once-per-change-of-its-reads)."
  {:malli/schema [:=> [:cat :map [:sequential :any]] [:set :any]]}
  [st datoms]
  (let [ix (:read-index st {})]
    (into #{} (mapcat (fn [d] (mapcat #(if (= :all %) (keys (:views st)) (get ix %)) (d/datom-keys d)))) datoms)))

(defn- db-key
  "The db a view's component reads, by its index in the page's dbs: main (0), or the agent's
  branch as this proc last read it (1, named by its branch, so every view of that agent shares
  its components)."
  {:malli/schema [:=> [:cat :map :any nat-int?] :any]}
  [st view i]
  (if (zero? i) :main (or (get-in st [:branches view :branch]) [:view view])))

(defn- comp-entry
  "Component [i eid as] of view's page from the proc's one cache of components, keyed by
  [db eid as], rendered inside d/with-reads when the cache has none (what it read is indexed by
  db: :comp-index): [st' {:html :keys :rev}], a new :rev each time it renders."
  {:malli/schema [:=> [:cat :map :any :seon.host/db [:tuple nat-int? pos-int? :any]] [:tuple :map :map]]}
  [st view db [i eid as]]
  (let [dk (db-key st view i) ck [dk eid as]]
    (if-let [e (get-in st [:components ck])]
      [st e]
      ;; serialized inside the read, so a lazy part is read (and recorded) there
      (let [[[hiccup html] reads] (d/with-reads #(let [hc (ui/component db eid as)] [hc (h/html hc)]))
            ks (d/reads-index reads)
            rev (inc (:comp-rev st 0))
            e {:html html :hiccup hiccup :keys ks :rev rev}]
        [(-> st (assoc-in [:components ck] e) (assoc :comp-rev rev) (update-in [:counts :components] (fnil inc 0))
             (update-in [:comp-index dk] index (seq ks) ck))
         e]))))

(defn comps-touched
  "The cached components a transaction's datoms on db dk touched (d/datom-keys against what each
  read: :comp-index; a schema datom touches every one of dk's): one lookup a key a datom."
  {:malli/schema [:=> [:cat :map :any [:sequential :any]] [:set :any]]}
  [st dk datoms]
  (let [ix (get-in st [:comp-index dk] {})]
    (into #{} (mapcat (fn [d] (mapcat #(if (= :all %) (into #{} cat (vals ix)) (get ix %)) (d/datom-keys d)))) datoms)))

(defn- comps-of
  "The cached components read from db dk: every one, when that db is read anew with nothing held
  to diff against (a first read, a read after an error)."
  {:malli/schema [:=> [:cat :map :any] [:set :any]]}
  [st dk] (into #{} (filter #(= dk (first %))) (keys (:components st))))

(defn- drop-comps
  "St without cached components cks and their reads in :comp-index."
  {:malli/schema [:=> [:cat :map [:sequential :any]] :map]}
  [st cks]
  (reduce (fn [st [dk :as ck]]
            (-> st (update :components dissoc ck) (update-in [:comp-index dk] unindex (seq (get-in st [:components ck :keys])) ck)))
          st cks))

(defn- invalidate
  "St without cached components cks (they render again when a page next shows them), and every
  view holding one of them (:comp-views) dirty."
  {:malli/schema [:=> [:cat :map [:set :any]] :map]}
  [st cks]
  (if (empty? cks)
    st
    (-> (drop-comps st (seq cks))
        (update :dirty (fnil into #{}) (mapcat #(get-in st [:comp-views %]) cks)))))

(defn- advance-main
  "St with main as db when db is newer than the main it holds (main-info): the components and views
  its datoms touched since (the datoms-diff of the two, O(changed), as a branch's read is matched)
  render again. The one way main moves here: the writer's commit notice, main read for a screen,
  main's first read. A db no newer changes nothing."
  {:malli/schema [:=> [:cat :map :seon.host/db] :map]}
  [st db]
  (let [held (:main-db st)]
    (if (and held (<= (:max-tx db) (:max-tx held)))
      st
      (let [moved (when held (apply concat (d/datoms-diff held db :eavt [])))]
        (cond-> (main-info st db)
          (seq moved) (-> (update :dirty (fnil into #{}) (touched st moved))
                          (invalidate (comps-touched st :main moved))))))))

(defn- forget
  "St without the cached components no view holds (:comp-views): the cache holds what pages show,
  nothing else."
  {:malli/schema [:=> [:cat :map] :map]}
  [st]
  (drop-comps st (remove #(seq (get-in st [:comp-views %])) (keys (:components st)))))

(defn- hold-comps
  "St with view-id holding components cks now (:comp-views, each component to the views holding
  it), in place of what it held before (old)."
  {:malli/schema [:=> [:cat :map :any [:sequential :any] [:sequential :any]] :map]}
  [st view-id old cks]
  (assoc st :comp-views (-> (reduce (fn [ix ck] (unindex ix [ck] view-id)) (:comp-views st) old)
                            (index-each cks view-id))))

(defn- pass-view
  "One view rendered: [st' patch-message-or-nil]; a view whose entity main does not hold yet renders
  nothing. Its walk runs inside d/with-reads, and what it read is kept (reindex): one index for
  the dbs it reads, main's and its agent's branch's (a datom of either is matched against both: a
  needless walk at worst, never a missed one). Each entity part is a component (comp-entry):
  rendered once into the proc's cache and again only when a transaction touches what it read, its
  html shared by every page that shows it."
  {:malli/schema [:=> [:cat :map :any] [:tuple :map [:maybe :map]]]}
  [st view-id]
  (let [held (get-in st [:views view-id])
        [out reads] (d/with-reads #(page-value st view-id))
        st (-> st (update-in [:counts :walked] (fnil inc 0)) (update :last-walked (fnil conj []) view-id) (update :dirty disj view-id))
        read-keys (d/reads-index reads)
        cks (fn [vw] (keep #(get-in % [:input :comp]) (vals (:units vw))))
        hold (fn [st v] (-> (reindex st view-id (:reads held) read-keys)
                            (hold-comps view-id (cks held) (cks v))
                            (assoc-in [:views view-id] (assoc v :reads read-keys :extra (extra-of st view-id)))))]
   (cond
    ;; main not read yet: nothing held, nothing indexed; the next pass walks it
    (nil? out) [st nil]
    ;; a location naming no entity: its page is that error, by its render
    (:seon.error/type out) [(let [hc (render/html {} out)] (hold st {:skeleton (h/html hc) :layout hc :units {}})) nil]
    :else
    (let [dbs (into [(:main-db st)] (keep identity) [(get-in st [:branches view-id :db])])
          ;; each component's html from the cache (rendered there when missing); its input names
          ;; it and its revision, so a unit renders again only when its component did
          [st units] (reduce (fn [[st us] {:keys [id comp] :as u}]
                               (if comp
                                 (let [[st e] (comp-entry st view-id (nth dbs (first comp) (first dbs)) comp)]
                                   [st (conj us (assoc u :input {:comp [(db-key st view-id (first comp)) (second comp) (peek comp)] :rev (:rev e)} :html (:html e) :hiccup (:hiccup e)))])
                                 [st (conj us u)]))
                             [st []] (:seon.render/units out))
          [view patch n] (step-units held units)
          [view patch] (finish-patch view patch (set (map :id units)))
          view (assoc view :skeleton (h/html (:seon.render/html out)) :layout (:seon.render/html out))]
      [(-> (hold st view) (update-in [:counts :rendered] (fnil + 0) (:rendered n)) (update-in [:counts :same] (fnil + 0) (:same n)))
       (when (or (seq (:frags patch)) (seq (:appends patch)) (seq (:prepends patch)) (seq (:afters patch)) (seq (:removes patch)))
         (assoc patch :op :patch :view view-id))]))))

(defn- view-pass
  "View-id rendered (pass-view), or, when its render throws, its page is that error: the edge of a
  failure point (docs/failure-points.md: a render is code, and agents write renders), so one view's
  defect stays that view's and every other view of the pass renders. The error, a
  :seon.render/view-defect (what threw, the view, the function it threw in), is held as the view's
  page, so the pages waiting on it are answered with it, and recorded as a platform problem through
  the api's proc error ({:op :error}, no message taken to answer again) when it is new: the same
  error again is not sent again, so its record cannot walk the view into another. The view keeps
  the reads it last rendered from, so a commit touching them walks it again; the components it
  held are let go."
  {:malli/schema [:=> [:cat :map :seon.host/volatile :any] [:tuple :map [:maybe :map]]]}
  [st o view-id]
  (try
    (pass-view st view-id)
    (catch Exception ex
      (let [held (get-in st [:views view-id])
            d (clojure.main/ex-triage (assoc (Throwable->map ex) :clojure.error/phase :execution))
            named (java.net.URLDecoder/decode (ui/view-param view-id) "UTF-8")
            e (contract/error :seon.render/view-defect #'pass-view (str "the page " named " threw rendering: " (step/error-msg ex))
                              (cond-> {:view named :class (.getName (class ex))}
                                (:clojure.error/symbol d) (assoc :seon.error/in (:clojure.error/symbol d))))
            hc (render/html {} e)]
        (when-not (= e (:error held))
          (step/out! o :to-api {:op :error :pid :render :message (:seon.error/message e) :data e}))
        [(-> st (update :dirty disj view-id)
             (hold-comps view-id (keep #(get-in % [:input :comp]) (vals (:units held))) [])
             (assoc-in [:views view-id] {:skeleton (h/html hc) :layout hc :units {} :error e
                                         :reads (:reads held) :extra (extra-of st view-id)}))
         nil]))))

(defn page-html
  "The composed document of view-id from its retained fragments (renders nothing)."
  {:malli/schema [:=> [:cat :map :any] [:maybe :string]]}
  [st view-id]
  (when-let [{:keys [skeleton units]} (get-in st [:views view-id])]
    (compose skeleton units)))

(defn- drop-view
  "St without view v (no tab watches it, nothing waits on it): its page, its reads in :read-index,
  the components it held (those no other view holds leave the cache: O(what it held)), its
  branch read, its rows and opens."
  {:malli/schema [:=> [:cat :map :any] :map]}
  [st v]
  (let [cks (keep #(get-in % [:input :comp]) (vals (get-in st [:views v :units])))]
  (-> (reindex st v (get-in st [:views v :reads]) #{})
      (hold-comps v cks [])
      ;; a component no other view holds now leaves the cache with it
      (as-> st (drop-comps st (remove #(seq (get-in st [:comp-views %])) (distinct cks))))
      (update :views dissoc v) (update :branches dissoc v) (update :shown dissoc v) (update :open dissoc v)
      (update :dirty #(disj (or % #{}) v)))))

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
          ;; root's context: the system as one value, printed once, also a unit of root's layout
          ctx (let [r (print/print-value (system/system-data sys) {:budget (config/value :seon.config.render/root-context-budget)})]
                (or (:text r) (:seon.error/message r)))
          sys (assoc sys :context {:text ctx :tokens (select/estimate ctx)})
          st (assoc st :context (:context sys))
          views (into (:watch st #{}) (concat (vals (:waiting st)) (:kf-wanted st)))
          st (assoc (ask-parts st o) :last-walked [])
          [st patches] (reduce (fn [[st ps] v]
                                 (let [[st p] (view-pass st o v)]
                                   [st (cond-> ps p (conj p))]))
                               [st []] (filter #(walk? st %) (sort-by str views)))
          idx (index-of (:agents sys))
          ms (profile/ms-since t0)]
      (doseq [p patches] (step/out! o :to-web p))
      (when (not= idx (:index st)) (step/out! o :to-web {:op :index :agents idx}))
      ;; a page or keyframe is answered once its view is whole (ready?); one asked before main or its
      ;; branch was read waits for the pass that read's answer brings
      (doseq [[k v] (:waiting st) :when (ready? st v)] (step/out! o :to-web {:op :page :k k :view v :html (page-html st v)}))
      (doseq [v (:kf-wanted st) :when (ready? st v)] (step/out! o :to-web {:op :keyframe :view v :html (page-html st v)}))
      (-> st
          (assoc :index idx
                    :waiting (into {} (remove (fn [[_ v]] (ready? st v))) (:waiting st))
                    :kf-wanted (into #{} (remove #(ready? st %)) (:kf-wanted st)))
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
  ones) as seon.lib/summ over the last 200 (none yet: nil), patch bytes, units rendered and
  skipped, its asks in flight (:pending: each answer steps it, and sends a settled mark when
  the last one is answered) and main's t as it holds it."
  {:malli/schema [:=> [:cat :map] :map]}
  [st]
  (let [q #(some-> % lib/summ)]
    {:passes (:passes st 0) :pass-ms (q (:pass-ms st)) :patch-pass-ms (q (:patch-pass-ms st)) :quiet-pass-ms (q (:quiet-pass-ms st))
     :patch-bytes (q (:patch-bytes st)) :event-age-ms (q (:event-age-ms st)) :rendered (get-in st [:counts :rendered] 0) :same-bytes (get-in st [:counts :same] 0)
     :agents (count (get-in st [:view :agents])) :views (vec (keys (:views st)))
     :pending (count (:pending st)) :main-t (get-in st [:main :t])}))

;;; --- the step-fn -------------------------------------------------------------------------------

(defn- drain
  "x and whatever else is queued on ch now (at most n), without waiting."
  {:malli/schema [:=> [:cat :any :seon.host/chan :int] [:vector :any]]}
  [x ch n] (loop [acc [x]] (if-let [y (and (< (count acc) n) (a/poll! ch))] (recur (conj acc y)) acc)))

(defn- on-event
  "An event: kept for the stream, and the view asked for; an agent's commit
  recorded by its id (ask-branches reads its branch again)."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [st ev]
  (cond-> (update st :new-events conj ev)
    ;; an agent's commit moves its branch: a page of it reads the branch again (its turns and evaluations are written there)
    (and (= :commit (:type ev)) (:agent ev)) (assoc-in [:commits (:agent ev)] (:t ev))))

(defn- take-read
  "St with view showing branch b as its one read holds it (:branch-reads b: its db or why it could
  not be read), dirty when that changed what the view held."
  {:malli/schema [:=> [:cat :map :any :keyword] :map]}
  [st view b]
  (let [{:keys [db error at]} (get-in st [:branch-reads b])
        held (get-in st [:branches view])]
    (if (and (= at (:read held)) (identical? db (:db held)) (= error (:error held)))
      st
      (-> st (assoc-in [:branches view] (cond-> {:branch b :read at} db (assoc :db db) error (assoc :error error)))
          (update :dirty (fnil conj #{}) view)))))

(defn- ask-branches
  "Ask for the branch db of each watched view's agent when it moved since this proc read it: the
  branch main records of the agent its view names (:seon.agent/branch; kept in the store after it
  ended); it moved when its t in root's view or its last commit event changed. One read per
  branch (:branch-reads), every view of the agent showing it, so no view renders the branch's
  components from an older read than another (the branch review's F2). A read in flight is left:
  its answer's pass asks again."
  {:malli/schema [:=> [:cat :map :seon.host/volatile] :map]}
  [st o]
  (let [db (:main-db st)]
    (reduce (fn [st view]
              (let [e (when-let [eid (when db (location db view))] (d/entity db eid))
                    b (:seon.agent/branch e)
                    id (when e (task/this-boots db e))
                    at [b (get-in st [:view :agents id :t]) (get-in st [:commits id])]
                    kind [:branch b]
                    st (cond-> st b (assoc-in [:branches view :branch] b))]
                (cond
                  (nil? b) st
                  (= at (get-in st [:branch-reads b :at])) (take-read st view b)
                  (some #{kind} (vals (:pending st))) st
                  :else (-> st (assoc-in [:branch-reads b :asking] at) (ask o kind {:op :write :req {:op :branch-db :branch b}})))))
            ;; the views a tab watches, and a page or keyframe waiting for its pass
            st (into (:watch st #{}) (concat (vals (:waiting st)) (:kf-wanted st))))))

(defn- web-answered
  "The web proc's call (tag [:web var agent form?]) answered r: shown in the stream; root's
  function (its form) recorded on main with this answer, as root's REPL records one."
  {:malli/schema [:=> [:cat :map :seon.host/volatile [:vector :any] :any] :map]}
  [st o [_ v agent form] r]
  (let [err (:seon.error/message r)
        ;; its text as the REPL answers it, by the one printer (the stream's row cuts it to a
        ;; cell, seon.render.ui/event-detail)
        detail (print/text r)]
    (when form
      (let [rec (repl/record-details (:main-db st) form (if err {:ex r} {:value r}) {} nil)]
        (step/out! o :to-api {:op :request :req {:op :write :req {:op :tx :branch :db :tx-data (repl/record-tx {:seon.root/id :root} (str "root-ui-" (profile/now-ns)) "user" rec)}}
                              :route [:render :answers] :tag [:web-record]})))
    (ask-state (cond-> (on-event st {:type :ui :call (str v) :agent agent :at-ms (System/currentTimeMillis) :error err :detail detail})
                 ;; an eval opened is a fact on main the agent takes when it draws: ask it to draw again
                 (= 'seon.root/opened! (symbol v)) (update-in [:parts agent] dissoc :asked))
               o)))

(defn- answered
  "The api's answer to request kind."
  {:malli/schema [:=> [:cat :map :seon.host/volatile :any :any] :map]}
  [st o kind r]
  (cond
    ;; the view's request answered an error (a defect, for a request a step lost): taken,
    ;; and the view stays as it was; the next event asks again
    (and (= :state kind) (seon.contract/error? r)) st
    (= :state kind)
    ;; an agent whose process ended keeps no parts (a later process of its id draws its own)
    (let [st (-> (assoc st :view r)
                 (update :parts #(into {} (filter (fn [[id]] (live? {:view r} id))) %)))
          ;; main's first read; after it, main comes in the writer's commits (:main)
          st (if (and (nil? (:main-db st)) (not (some #{:main} (vals (:pending st))))) (ask st o :main {:op :write :req {:op :branch-db :branch :db}}) st)]
      ;; a watched agent's branch moved: read its branch db (its turns, evaluations and task)
      (pass (ask-branches st o) o))
    (= :main kind) (if (seon.contract/error? r) st (pass (ask-branches (advance-main st (:db r)) o) o))
    ;; an agent drew its page's parts (or did not: gone, refused): held for its view, which renders again
    (and (vector? kind) (= :parts (first kind)))
    (let [id (second kind)
          views (filter #(= id (second (agent-of (:main-db st) %))) (into (:watch st #{}) (concat (vals (:waiting st)) (:kf-wanted st))))]
      (pass (-> (update-in st [:parts id] #(if (seon.contract/error? r) (-> % (dissoc :units) (assoc :error r)) (-> % (dissoc :error) (assoc :units (:units r)))))
                (update :dirty (fnil into #{}) views))
            o))
    ;; an agent's branch db, the one read of it every view of the agent shows; a branch that is gone
    ;; (merged and removed) is recorded as such
    (and (vector? kind) (= :branch (first kind)))
    (let [b (second kind)
          {held :db asking :asking} (get-in st [:branch-reads b])
          err? (seon.contract/error? r)
          ;; what moved on the branch: the datoms either db holds alone (O(changed))
          moved (when (and held (not err?)) (apply concat (d/datoms-diff held (:db r) :eavt [])))
          st (assoc-in st [:branch-reads b] (cond-> {:at asking} err? (assoc :error r) (not err?) (assoc :db (:db r))))
          st (reduce #(take-read %1 %2 b) st (filter #(= b (get-in st [:branches % :branch])) (keys (:branches st))))]
      (pass (cond-> st
              ;; read anew with nothing held to diff against (a first read, a read after an error):
              ;; what was cached from that branch is not known to be current
              (nil? held) (as-> st (invalidate st (comps-of st b)))
              (seq moved) (-> (update :dirty (fnil into #{}) (touched st moved))
                              (invalidate (comps-touched st b moved))))
            o))
    :else st))

(defn render-proc
  "Root's render proc (see the namespace doc)."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:events-mult "the api's events, a mult this proc taps" :graph "a promise of the root graph (unused: nothing is pinged)"
                :system "a promise of the built system (its declared procs, for the health tile)"}
       :ins {:answers "the api's answers" :from-web "views open, page and keyframe requests"
             :main "main's commits from the writer {:t :ops :db}: each one's datoms find the pages whose reads they touched"}
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
      :new-events [] :events [] :views {} :branches {}
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
              ;; a commit on main: the pages its datoms touched walk in the pass
              ;; a commit on main (a sliding buffer of one: the newest, its datoms since the main held
              ;; found by their diff, so a lagging proc never parks the writer)
              :main (pass (advance-main st (:db m)) o)
              :answers (let [[st' kind] (step/answered st (:tag m))
                             r (:r m)
                             err (:seon.error/message r)]
                         (cond
                           kind (answered st' o kind r)
                           (and (vector? (:tag m)) (= :web (first (:tag m)))) (web-answered st o (:tag m) r)
                           :else st))
              :from-web (case (:op m)
                          :views (let [w (:views m)
                                       ;; a view no tab watches keeps nothing: its retained page, its branch read, its rows
                                       drop #(into {} (filter (fn [[v]] (contains? w v))) %)]
                                   (ask-state (-> (reduce drop-view st (remove #(contains? w %) (keys (:views st))))
                                                  (assoc :watch w :tabs (:tabs m))
                                                  forget)
                                              o))
                          ;; a UI call root refused before it became a request: answered as root's are
                          :refused (web-answered st o (:tag m) (:r m))
                          :page (if (and (get-in st [:views (:view m)]) (contains? (:watch st) (:view m)))
                                  (do (step/out! o :to-web {:op :page :k (:k m) :view (:view m) :html (page-html st (:view m))}) st)
                                  (ask-state (assoc-in st [:waiting (:k m)] (:view m)) o))
                          :keyframe (if (ready? st (:view m))
                                      (do (step/out! o :to-web {:op :keyframe :view (:view m) :html (page-html st (:view m))}) st)
                                      (ask-state (update st :kf-wanted conj (:view m)) o))
                          :context (do (step/out! o :to-web {:op :text :k (:k m) :text (get-in st [:context :text] "")}) st)
                          ;; the end of a view's list scrolled into view: the next rows are rendered
                          :more (if (contains? (:watch st) (:view m)) (pass (update-in st [:shown (:view m)] (fnil + (history-cap)) (history-cap)) o) st)
                          ;; a row's <details> opened or closed: its whole content is rendered while it is open
                          :toggle (if (contains? (:watch st) (:view m))
                                  (pass (update-in st [:open (:view m)] (fnil (if (:open m) conj disj) #{}) (:unit m)) o)
                                  st)
                          :stats (do (step/out! o :to-web {:op :text :k (:k m) :text (sedn/encode (stats st))}) st)
                          st)
              st)]
     ;; nothing asked is in flight after what was, or after what went to the tabs: their
     ;; views are current (the web proc says so on each feed; seon.check/idle waits on it)
     (when (and (empty? (:pending st)) (or busy? (seq (:to-web @o)))) (step/out! o :to-web {:op :settled}))
     [st @o])))
