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
  coalescing window: no step sleeps). The answer is joined with health and the events into root's
  process value (process-value), which is part of root's ctx (process!). Every page is the render
  of a ctx through the one walk (seon.render/walker): / root's (root-walk, root's ctx on main),
  /agent/{name} an agent's chat (drawn by root from its branch), /agent/{name}/debug every
  render of its ctx (its process's walk, else its branch's drawn by root), /agent/{name}/context
  what its model was sent, a name its id in this boot or its branch; each laid out by seon.render.ui/page-chrome into units. Then, per unit: inputs
  before render (an unchanged input renders nothing), bytes before send (an unchanged fragment
  sends nothing); a page renders only the rows a tab has scrolled to (:more raises them). The web
  proc serializes each patch once and fans it out; a tab that opened or fell behind gets a
  keyframe composed here from the retained fragments, rendering nothing."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.edn :as sedn] [seon.profile :as profile] [seon.contract :as contract :refer [defn defn-]] [clojure.string :as str]
            [clojure.core.async :as a] [clojure.core.async.flow :as flow] [datahike.api :as d]
            [seon.lib :as lib] [seon.render :as render] [seon.render.ui :as ui] [seon.print :as print] [seon.print.select :as select] [seon.repl :as repl] [seon.root :as c]
            [seon.web.html :as h] [seon.task :as task] [seon.reconcile :as reconcile] [seon.ctx :as ctx] [seon.ratom :as ratom] [seon.db] [datahike.index.interface]
            [seon.config :as config] [seon.step :as step] [seon.context :as context] [seon.problems :as problems]))

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

(defn process-value
  "Root's process as one value (:seon.render/process), from the render proc's state: the writer's,
  the fork server's and the store's numbers as root's own `state` shows them (seon.root/view-numbers),
  each declared proc's defects (health is what flows: no ping, no beat) and the events, newest
  first. Root's own data, part of root's ctx (process!), never written."
  {:malli/schema [:=> [:cat :map] :seon.render/process]}
  [st]
  (let [v (:view st)
        n (c/view-numbers v)]
    {:writer (:writer n) :fork-server (:fork-server n) :store (:store n)
     :root-errors-total (:root-errors-total v 0)
     :health (proc-health-of st (:root-errors v []))
     ;; a stop that arrived before main recorded how the run ended says so once main does
     :events (mapv #(ui/ended-as % (:agent %) (:agent-run st {})) (:events st []))}))

;;; --- main --------------------------------------------------------------------------------

(defn- main-info
  "St with main as db (when newer than the one held): its t, its db, and the reconciler's index of
  it held and moved by what changed (seon.reconcile/index over the watched datoms that differ:
  seon.render-cost-test/a-move-of-main-costs-what-moved), whose runs
  give how each agent's work ended (:agent-run, the words root's process tile uses for its
  events). Nothing per task or per datom of main is read again."
  {:malli/schema [:=> [:cat :map :seon.host/db] :map]}
  [st db]
  ;; a db no newer than the one held (a first read answered after a commit already came) changes nothing
  (if (<= (:max-tx db) (get-in st [:main :t] -1))
    st
    (let [held (:main-db st)
          idx (if (and (:idx st) held) (reconcile/index (:idx st) held db) (reconcile/index db))]
      (assoc st :main {:t (:max-tx db)} :main-db db :idx idx
             :agent-run (into {} (for [a (sort-by :seon.run/start-ms (vals (:runs idx)))] [(:seon.run/agent a) a]))))))

;;; --- units, rendered incrementally ---------------------------------------------------------

(defn- render-unit
  "A unit as [hiccup html]: its html as drawn, else its draw thunk's hiccup (a debug turn's row),
  else its input by the render its schema declares, its unit named (its element's id: :unit). The
  hiccup is kept beside the html. step-units calls it only when the unit's input changed."
  {:malli/schema [:=> [:cat :map] [:tuple :any :string]]}
  [{:keys [id input schema html hiccup draw]}]
  (cond html [hiccup html]
        draw (let [hc (draw)] [hc (h/html hc)])
        :else (let [hc (render/html {:schema schema :unit id} input)] [hc (h/html hc)])))

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

(defn- first-of
  "A vector view's kind ([:chat v], [:debug v]), else nil."
  {:malli/schema [:=> [:cat :any] :any]}
  [view] (when (vector? view) (first view)))

(defn- location
  "The entity view names on main db: root's for :root, the agent of that id in root's boot (its
  key, seon.task/agent-key, or the session's agent it continues) for an agent's id, the newest
  agent of a branch for its branch (:main/task-x, a durable name: an agent of any boot, ended or
  not, opens from the store; a query over the agents' branches, asked only for a branch's name,
  until the agent is keyed by its branch, the schema design's D1), else the entity id it is; nil when main names none (no boot fact
  yet, or no agent of that id in this boot). See docs/render.md#the-page."
  {:malli/schema [:=> [:cat :seon.host/db :any] [:maybe pos-int?]]}
  [db view]
  (cond (vector? view) (location db (second view))
        (= :root view) (:db/id (d/entity db [:seon.root/id :root]))
        ;; a branch: the newest agent that worked it (entity ids are issued in the order written)
        (and (keyword? view) (namespace view)) (d/q '[:find (max ?e) . :in $ ?b :where [?e :seon.agent/branch ?b]] db view)
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
  into view). Each row is a unit drawn by seon.render.ui/sent-request when its input (its turn,
  whether its tab has it open) changes: it reads its turn and the one before it, shows what its
  turn added, and its whole request while open (:open, seon.web's /open); a finished turn is
  never written again, so its row renders once. nil until the branch is read; an agent with no branch on main (the user's) or
  whose branch could not be read has no turn to show, and its page says so, never a wait."
  {:malli/schema [:=> [:cat :map [:tuple [:= :debug] :any]] [:maybe :map]]}
  [st view]
  (cond
    (= :root (second view))
    ;; root has no model turn: what it reads is the system's value printed, held each pass (:context)
    (when-let [ctx (:context st)]
      (ui/debug :root [] 0 {:id "d-context" :input ctx :schema :seon.render/root-context}))
    (nil? (get-in st [:branches view :db]))
    (let [db (:main-db st) eid (when db (location db view))]
      (when (or (get-in st [:branches view :error]) (and eid (nil? (:seon.agent/branch (d/entity db eid)))))
        (ui/debug (second view) [] 0 nil)))
    :else
  (let [bdb (get-in st [:branches view :db])]
    (let [open (get-in st [:open view] #{})
          shown (get-in st [:shown view] (history-cap))
          ;; newest first: entity ids are issued in the order turns were written
          ts (->> (d/q '[:find [?e ...] :where [?e :seon.turn/sent _]] bdb) sort reverse vec)
          on (take shown ts)
          vp (ui/view-param view)]
      (ui/debug (second view)
                (vec (for [e on :let [id (ui/comp-id e [:debug]) open? (contains? open id)]]
                       {:id id :input [e open?] :draw #(ui/sent-request {:unit id} (ui/debug-input bdb e open? vp))}))
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
  "Ask each live agent a watched view shows for its page's parts ({:op :render}, seon.repl/render-ctx)
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

(defn- agent-view
  "View's page when it names an agent (entity eid on main db): its components as its process drew
  them (ask-parts, seon.repl/render-ctx: the units of its walk), else, with no process to ask or none
  that answered, as root draws them from its branch (seon.ctx/build, drawn once by the same walk
  and layout: seon.render/draw) with main's renders, the page saying so. Everything the page shows
  of the agent is its ctx: its run's status and verdict reach its branch by take!, never read here
  on main. Root's agents (the sidebar) are root's chrome; its input (seon.render.ui/agent-input)."
  {:malli/schema [:=> [:cat :map :any :seon.host/db pos-int?] :map]}
  [st view db eid]
  (let [;; [:chat v]: the agent's chat, drawn by root from its branch with the chat's layout
        chat? (vector? view)
        [e id] (agent-of db (if chat? (second view) view))
        held (get-in st [:parts id])
        own? (and (not chat?) (live? st id) (vector? (:units held)))
        berr (get-in st [:branches view :error])
        bdb (get-in st [:branches view :db])
        ;; the branch's record, drawn outside the read set: a move of the branch marks this page dirty
        ;; when root reads it (take-read), and the branch's reads are not main's
        ;; the evals a reader opened (main's :seon.agent/opened, read here, so its commit passes the
        ;; page again): drawn opened, as the agent draws them once it takes the fact
        opened (set (:seon.agent/opened e))
        branch-units (fn [] (let [t (binding [datahike.index.interface/*reads* nil]
                                      (:tree (ctx/build bdb (seon.db/eid bdb :seon.agent/key (:seon.agent/key e)) (:seon.agent/branch e))))
                                  t (cond-> t (seq opened) (update :evals #(reduce-kv (fn [m k ev] (if (opened (:seon.eval/id ev)) (assoc m k (assoc ev :seon.eval/opened true)) m)) % %)))
                                  w (binding [datahike.index.interface/*reads* nil]
                                      (render/draw t ctx/changes bdb (render/declared-var (if chat? :seon.render/chat-ctx :seon.render/render-ctx))))]
                              (try (render/placed-units w) (finally (render/dispose-walk! w)))))
        units (if own?
                (:units held)
                (cond bdb (branch-units)
                      ;; a branch that could not be read: its error, by the error's render
                      berr [{:id "p-branch-error" :container "#p-task" :rev 0 :html (h/html [:section {:id "p-branch-error"} (render/html {} berr)])}]))
        ;; root's agents list, as root's walk drew it, and the agent's head from its facts on main
        sides (filterv #(= "#c-side" (:container %)) (some-> (:root-walk st) :w render/placed-units))
        head (d/pull db [:seon.agent/id :seon.agent/status :seon.agent/branch :seon.agent/pid :seon.agent/description] eid)
        head-unit {:id "p-agent-head" :container "#p-head" :rev (hash head)
                   :html (h/html [:section {:id "p-agent-head" :class "rounded-md bg-base-900 p-3 text-sm text-text-100"} (ui/agent {} head)])}]
    (ui/page-chrome {:units (vec (concat sides [head-unit] units)) :view (ui/view-param view) :tab (if chat? :chat :debug)
                      ;; never a wait: drawn from its branch until its process answers, the page says so
                      :caption (when-not (or own? chat?)
                                 (str "drawn by root from its branch with main's renders: "
                                      (cond (:error held) (str "its process did not answer (" (:seon.error/message (:error held)) ")")
                                            (live? st id) "its process has not answered yet"
                                            :else "its process is not running")))
                      ;; its REPL and its messages while its process runs; its held task's release
                      :input {:schema :seon.render/agent-input
                              :value {:id (:seon.agent/id e) :live? (live? st id)
                                      :held (d/q '[:find ?id . :in $ ?a :where [?r :seon.run/agent ?a] [?r :seon.run/task ?t] [?t :seon.task/execution :held] [?t :seon.task/id ?id]] db eid)}}}
                     ;; its tabs name it by its id in this boot, else by its branch (an earlier boot's agent)
                     (or id (:seon.agent/branch e) (:seon.agent/id e)))))

(defn- root-walk
  "St with root's walk current as of main db: root's ctx (seon.ctx/build of root's entity on main)
  held in a reactive atom its walk draws by root's layout (:seon.render/root-ctx), built at main's
  first read, then advanced by what each move of main changed (seon.ctx/advance, its datoms
  framed): the walk draws the components their change reached, nothing else. Every page whose
  agents list it shows (root's, each agent's) is dirty when one of its units moved."
  {:malli/schema [:=> [:cat :map :seon.host/db [:maybe :seon.host/db]] :map]}
  [st db held]
  (let [root (:db/id (d/entity db [:seon.root/id :root]))
        rw (:root-walk st)
        side (fn [w] (into {} (for [u (render/placed-units w) :when (= "#c-side" (:container u))] [(:id u) (:rev u)])))]
    (cond
      (nil? root) st
      (nil? rw)
      (let [c (ctx/build db root :main) a (ratom/atom (:tree c)) at (volatile! db)
            w (render/walker {:ref a :changes ctx/changes} (fn [] @at) [(render/declared-var :seon.render/root-ctx) {}])]
        (render/frame! w nil)
        (-> (assoc st :root-walk {:w w :a a :c c :at at})
            (update :dirty (fnil into #{}) (keys (:views st)))))
      (nil? held) st
      :else
      (let [{:keys [w a c at]} rw
            c' (ctx/advance c held db :main)
            before (side w)]
        (vreset! at db)
        ;; root's process (process!) is root's own data beside the pull: kept across a move of main
        (reset! a (cond-> (:tree c') (:process @a) (assoc :process (:process @a)) (:health @a) (assoc :health (:health @a))))
        (render/frame! w (ctx/moved held db nil))
        (cond-> (assoc-in st [:root-walk :c] c')
          true (update :dirty (fnil conj #{}) :root)
          (not= before (side w)) (update :dirty (fnil into #{}) (keys (:views st))))))))

(defn- process!
  "St with root's process value p (root's ruling on root's process values, option a: the writer's, the fork
  server's and the store's numbers, each proc's defects and the event stream are root's own data,
  part of root's ctx beside its db pull, never written) in root's walk: when it differs from what
  the walk holds, assoc'd into root's ctx and framed, so the one component that reads it runs again."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [st p]
  (if-let [{:keys [w a]} (:root-walk st)]
    (if (= p (:process @a))
      st
      (do (reset! a (assoc @a :process p))
          (render/frame! w nil)
          (update st :dirty (fnil conj #{}) :root)))
    st))

(defn- health!
  "St with main's health (the report the problems tile draws) in root's walk, beside root's process:
  the held one changed by the datoms that moved between the main held and db (seon.problems/health-moved,
  which reads the runs and sections they touched and not the closed history), or read whole when none is
  held. When no class changed, the held report stays and nothing is framed."
  {:malli/schema [:=> [:cat :map [:maybe :seon.host/db] :seon.host/db [:maybe [:sequential :any]]] :map]}
  [st held db moved]
  (if-let [{:keys [w a]} (:root-walk st)]
    (let [prev (:health @a)
          h (if (and prev held moved) (problems/health-moved prev held db moved) (problems/health db))]
      (if (= (:classes h) (:classes prev))
        st
        (do (reset! a (assoc @a :health h))
            (render/frame! w nil)
            (update st :dirty (fnil conj #{}) :root))))
    st))

(defn- root-page
  "Root's page: its walk's units (root-walk) in root's chrome, its input at the top; nil until main
  is read."
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [st]
  (when-let [{:keys [w]} (:root-walk st)]
    (ui/page-chrome {:units (render/placed-units w) :view (ui/view-param :root) :tab :chat :input {:schema :seon.render/root-input :value {}}} :root)))

(defn- page-value
  "View's page: root's (root-page), an agent's debug page (debug-value) or an agent's
  (agent-view); nil when main is not read yet, :seon.render/no-location when main names no agent
  for it (an agent's id of no agent in this boot)."
  {:malli/schema [:=> [:cat :map :any] [:maybe :map]]}
  [st view]
  (let [db (:main-db st)
        eid (when db (location db view))]
    (cond
     (= :root view) (root-page st)
     (and eid (= :debug (first-of view))) (debug-value st view)
     (and eid (= :chat (first-of view)) (agent-of db (second view))) (agent-view st view db eid)
     (and eid (agent-of db view)) (agent-view st view db eid)
     db (contract/error :seon.render/no-location #'seon.render.proc/page-value
                        (str "no " (let [v (if (vector? view) (second view) view)] (if (keyword? v) (str "agent " (ui/kname v) " in root's boot " (task/boot-of db)) (ui/kname v)))
                             " on main: an agent's id names one of this boot's (ids start again each boot)")
                        {:view (ui/kname view)}))))

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
  (whether there is one) and error, and its agent's liveness in root's view of this boot. A page whose dbs no transaction touched walks again only when this changed."
  {:malli/schema [:=> [:cat :map :any] :map]}
  [st view]
  (let [db (:main-db st)
        id (when db (when-let [eid (location db view)] (task/this-boots db (d/entity db eid))))]
    {:shown (get-in st [:shown view]) :open (get-in st [:open view]) :error (get-in st [:branches view :error])
     ;; whether its branch is read at all: what moved on it after that is its datoms-diff
     :branch? (some? (get-in st [:branches view :db]))
     :live (when id (ui/live (ui/shown-status (get-in st [:view :agents id] {}))))}))

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

(defn- advance-main
  "St with main as db when db is newer than the main it holds (main-info): root's walk advanced
  (root-walk) and the views its datoms touched since (the datoms-diff of the two, O(changed), as a branch's read is matched)
  render again. The one way main moves here: the writer's commit notice, main read for a screen,
  main's first read. A db no newer changes nothing."
  {:malli/schema [:=> [:cat :map :seon.host/db] :map]}
  [st db]
  (let [held (:main-db st)]
    (if (and held (<= (:max-tx db) (:max-tx held)))
      st
      (let [moved (when held (apply concat (d/datoms-diff held db :eavt [])))]
        (cond-> (-> (root-walk (main-info st db) db held) (health! held db moved))
          (seq moved) (update :dirty (fnil into #{}) (touched st moved)))))))

(defn- pass-view
  "One view rendered: [st' patch-message-or-nil]; a view whose entity main does not hold yet renders
  nothing. Its walk runs inside d/with-reads, and what it read is kept (reindex): one index for
  the dbs it reads, main's and its agent's branch's (a datom of either is matched against both: a
  needless walk at worst, never a missed one). A unit is drawn only when its input changed
  (step-units)."
  {:malli/schema [:=> [:cat :map :any] [:tuple :map [:maybe :map]]]}
  [st view-id]
  (let [held (get-in st [:views view-id])
        [out reads] (d/with-reads #(page-value st view-id))
        st (-> st (update-in [:counts :walked] (fnil inc 0)) (update :last-walked (fnil conj []) view-id) (update :dirty disj view-id))
        read-keys (d/reads-index reads)
        hold (fn [st v] (-> (reindex st view-id (:reads held) read-keys)
                            (assoc-in [:views view-id] (assoc v :reads read-keys :extra (extra-of st view-id)))))]
   (cond
    ;; main not read yet: nothing held, nothing indexed; the next pass walks it
    (nil? out) [st nil]
    ;; a location naming no entity: its page is that error, by its render
    (:seon.error/type out) [(let [hc (render/html {} out)] (hold st {:skeleton (h/html hc) :layout hc :units {}})) nil]
    :else
    (let [units (:seon.render/units out)
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
             (assoc-in [:views view-id] {:skeleton (h/html hc) :layout hc :units {} :error e
                                         :reads (:reads held) :extra (extra-of st view-id)}))
         nil]))))

(defn view-html
  "The composed document of view-id from its retained fragments (renders nothing)."
  {:malli/schema [:=> [:cat :map :any] [:maybe :string]]}
  [st view-id]
  (when-let [{:keys [skeleton units]} (get-in st [:views view-id])]
    (compose skeleton units)))

(defn- drop-view
  "St without view v (no tab watches it, nothing waits on it): its page, its reads in :read-index,
  its branch read, its rows and opens."
  {:malli/schema [:=> [:cat :map :any] :map]}
  [st v]
  (-> (reindex st v (get-in st [:views v :reads]) #{})
      (update :views dissoc v) (update :branches dissoc v) (update :shown dissoc v) (update :open dissoc v)
      (update :dirty #(disj (or % #{}) v))))

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
          ;; the stream, newest first: each event numbered, its row a unit of root's layout
          seq0 (:ev-seq st 0)
          evs (map-indexed (fn [i e] (assoc e :seq (+ seq0 i 1))) (:new-events st))
          st (cond-> st
               (seq evs) (assoc :events (vec (take (event-cap) (concat (reverse evs) (:events st))))
                                :ev-seq (+ seq0 (count evs)))
               true (assoc :new-events []))
          st (process! st (process-value st))
          ;; what root reads: its page, the render of its ctx, each component's hiccup by its id (as an
          ;; agent's screen is its page), printed once at root's context budget, what is wrong first
          ;; (its tiles: its process, main's problems and runs), then its evals, its head, its agents
          ctx (when-let [w (:w (:root-walk st))]
                (let [r (print/print-value (apply array-map (mapcat identity (render/placed-hiccup w ["#p-tiles" "#c-rows" "#p-head" "#c-side"])))
                                           {:budget (config/value :seon.config.render/root-context-budget)})]
                  (or (:text r) (:seon.error/message r))))
          st (assoc st :context (when ctx {:text ctx :tokens (select/estimate ctx)}))
          views (into (:watch st #{}) (concat (vals (:waiting st)) (:kf-wanted st)))
          st (assoc (ask-parts st o) :last-walked [])
          [st patches] (reduce (fn [[st ps] v]
                                 (let [[st p] (view-pass st o v)]
                                   [st (cond-> ps p (conj p))]))
                               [st []] (filter #(walk? st %) (sort-by str views)))
          idx (index-of (get-in st [:view :agents] {}))
          ms (profile/ms-since t0)]
      (doseq [p patches] (step/out! o :to-web p))
      (when (not= idx (:index st)) (step/out! o :to-web {:op :index :agents idx}))
      ;; a page or keyframe is answered once its view is whole (ready?); one asked before main or its
      ;; branch was read waits for the pass that read's answer brings
      (doseq [[k v] (:waiting st) :when (ready? st v)] (step/out! o :to-web {:op :page :k k :view v :html (view-html st v)}))
      (doseq [v (:kf-wanted st) :when (ready? st v)] (step/out! o :to-web {:op :keyframe :view v :html (view-html st v)}))
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
      (pass (cond-> st (seq moved) (update :dirty (fnil into #{}) (touched st moved))) o))
    :else st))

(defn render-proc
  "Root's render proc (see the namespace doc)."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:events-mult "the api's events, a mult this proc taps" :graph "a promise of the root graph (unused: nothing is pinged)"
                :system "a promise of the built system (its declared procs, for the health tile)"
                :conn "the store main's commit notices are read from"}
       :ins {:answers "the api's answers" :from-web "views open, page and keyframe requests"
             :main "main's commit notices from the writer {:t :prev :ops :commit}, read with d/commit-as-db: each one's datoms find the pages whose reads they touched"}
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
     {:events-ch events :mult (:events-mult args) :system-p (:system args) :conn (:conn args) :rid 0 :pending {} :watch #{} :waiting {} :kf-wanted #{}
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
              :main (pass (advance-main st (d/commit-as-db (:conn st) (:commit m))) o)
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
                                                  (assoc :watch w :tabs (:tabs m)))
                                              o))
                          ;; a UI call root refused before it became a request: answered as root's are
                          :refused (web-answered st o (:tag m) (:r m))
                          :page (if (and (get-in st [:views (:view m)]) (contains? (:watch st) (:view m)))
                                  (do (step/out! o :to-web {:op :page :k (:k m) :view (:view m) :html (view-html st (:view m))}) st)
                                  (ask-state (assoc-in st [:waiting (:k m)] (:view m)) o))
                          :keyframe (if (ready? st (:view m))
                                      (do (step/out! o :to-web {:op :keyframe :view (:view m) :html (view-html st (:view m))}) st)
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
