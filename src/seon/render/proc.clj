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

  No beat and no ping (owner: \"a reactive system where we know if there's a
  problem with the proc\"): health is what flows, each proc's recorded defects; a value no
  event carries is its owner's to emit. A pass: an event asks the api for its view; while that request is in
  flight, more events only queue, so a burst becomes one pass (the round trip is the
  coalescing window: no step sleeps). The answer is joined with health and the events into root's
  process value (process-value), which is part of root's ctx (process!). Every page is the render
  of a ctx through the one walk (seon.render/walker): / root's (root-walk, root's ctx on main),
  /agent/{name} an agent's page, the one render of its ctx the model reads too (its process's walk,
  else its branch's drawn by root), /agent/{name}/debug its @ctx printed, /agent/{name}/context
  what its model was sent, a name its id in this boot or its branch; each laid out by seon.render.ui/page-chrome into units. Then, per unit: inputs
  before render (an unchanged input renders nothing), bytes before send (an unchanged fragment
  sends nothing); a page renders only the rows a tab has scrolled to (:more raises them). The web
  proc serializes each patch once and fans it out; a tab that opened or fell behind gets a
  keyframe composed here from the retained fragments, rendering nothing."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.edn :as sedn] [seon.profile :as profile] [seon.contract :as contract :refer [defn defn-]] [clojure.string :as str]
            [clojure.core.async :as a] [clojure.core.async.flow :as flow] [datahike.api :as d]
            [seon.lib :as lib] [seon.render :as render] [seon.program :as program] [seon.render.ui :as ui] [seon.print :as print] [seon.print.select :as select] [seon.repl :as repl] [seon.root :as c]
            [seon.web.html :as h] [seon.task :as task] [seon.reconcile :as reconcile] [seon.ctx :as ctx] [seon.ratom :as ratom] [seon.db] [datahike.index.interface]
            [seon.config :as config] [seon.step :as step] [seon.problems :as problems]))

(defn- event-cap "Events the stream keeps (:seon.config.render.list/stream)." {:malli/schema [:=> [:cat] pos-int?]} [] (config/value :seon.config.render.list/stream))
(defn- history-cap "Rows a page renders at first, and more each time its list's end scrolls into view (:seon.config.render.list/history)." {:malli/schema [:=> [:cat] pos-int?]} [] (config/value :seon.config.render.list/history))

;;; --- requests -------------------------------------------------------------------------

(defn- ask
  "Ask the api for req under kind (seon.step/ask) unless kind is already in flight: the
  answer's pass covers what queued meanwhile (the coalescing window). No clock re-asks: the
  api answers every request it took, a defect included."
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
    (cond-> {:writer (:writer n) :fork-server (:fork-server n) :store (:store n)
     :root-errors-total (:root-errors-total v 0)
     :health (proc-health-of st (:root-errors v []))
     ;; a stop that arrived before main recorded how the work ended says so once main does
     :events (let [evs (:events st []) ids (into #{} (keep :agent) evs)
                   idx (:idx st)
                   outcome (if (seq ids)
                             (into {} (for [[_ t] (:tasks idx) :let [a (get (:agents idx) (:seon.task/agent t))] :when (and a (ids (keyword (:seon.agent/key a))))]
                                        [(keyword (:seon.agent/key a)) (:seon.task/outcome t)]))
                             {})]
               (mapv #(ui/ended-as % (:agent %) outcome) evs))}
      (:boot v) (assoc :boot (:boot v)))))

;;; --- main --------------------------------------------------------------------------------

(defn- main-info
  "St with main as db (when newer than the one held): its t, its db, and the reconciler's index of
  it held and moved by what changed (seon.reconcile/index over the watched datoms that differ:
  seon.render-cost-test/a-move-of-main-costs-what-moved), whose tasks
  give how each agent's work ended (`process-value` reads them for its events). Nothing per task or per datom of main is read again. moved: the datoms that differ
  between the main held and db, read once by advance-main; the index moves by those of its watched
  attributes, so no second diff of each of them is made (one descent apiece: 90 attributes, 14.6 MB)."
  {:malli/schema [:function [:=> [:cat :map :seon.host/db] :map]
                  [:=> [:cat :map :seon.host/db [:maybe [:sequential :seon.host/datom]]] :map]]}
  ([st db] (main-info st db nil))
  ([st db moved]
  ;; a db no newer than the one held (a first read answered after a commit already came) changes nothing
  (if (<= (:max-tx db) (get-in st [:main :t] -1))
    st
    (let [held (:main-db st)
          idx (if (and (:idx st) held) (if moved (reconcile/index (:idx st) held db moved) (reconcile/index (:idx st) held db)) (reconcile/index db))]
      (assoc st :main {:t (:max-tx db)} :main-db db :idx idx)))))

;;; --- units, rendered incrementally ---------------------------------------------------------

(defn- render-unit
  "A unit as [hiccup html kept]: its html as drawn, else its draw's hiccup (the debug page's turn: draw,
  a function of what its last draw kept, kept, answers [hiccup kept']), else its input by the render its
  schema declares, its unit named (its element's id: :unit). The hiccup and kept are held beside the html.
  step-units calls it only when the unit's input changed."
  {:malli/schema [:=> [:cat :map :map :any] [:tuple :any :string :any]]}
  [ix {:keys [id input schema html hiccup draw]} kept]
  (cond html [hiccup html nil]
        draw (let [[hc k] (draw kept)] [hc (h/html hc) k])
        :else (let [hc (render/hiccup {:schema schema :unit id :seon.render/index ix} input)] [hc (h/html hc) nil])))

(defn- units-index
  "The entity index a view's units are rendered in: main's as root's walk holds it (built from main, again when a schema entry
  moves), so a render main declares or changes is the one a unit runs; the binary's only before main is read. See
  docs/render.md#one-rendering-system-drawn-from-the-data."
  {:malli/schema [:=> [:cat :map] :map]}
  [st] (or (some-> (:root-walk st) :w :index deref) (seon.db/index)))

(defn- step-units
  "Run a layout's units, in the order the layout shows them, against what the view retained: a
  unit whose input is unchanged is skipped; one whose bytes are unchanged sends nothing; a new one
  with a container is added to it where the layout puts it: after the unit before it in its
  container when one is held or placed in this patch and a unit after it is held (a part that sorts
  into the middle), before what the container holds when none is before it (a list's newest row
  at its top, a conversation's older parts), else at its end. Answers [view' patch counts]."
  {:malli/schema [:=> [:cat :map [:maybe :map] [:sequential :map]] [:tuple :map :map :map]]}
  [ix view units]
  ;; a page of no units (the debug page before a model turn) is held with none, never without :units
  (let [view (or view {:units {}})
        held (:units view)
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
                   (let [[hiccup html kept] (render-unit ix u (:kept old))
                         view (assoc-in view [:units id] (cond-> {:input input :html html :hiccup hiccup} container (assoc :container container) (:parent u) (assoc :parent (:parent u)) (some? kept) (assoc :kept kept)))]
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
                       :else [[view (-> patch (update :frags conj [id html]) (assoc-in [:was id] (:html old))) (update n :rendered inc)] prev placed])))))
             [[(or view {}) {:frags [] :appends [] :prepends [] :afters [] :removes []} {:rendered 0 :same 0}] {} {}]
             units))))

(defn compose
  "html with every slot marker replaced by its unit's retained HTML, recursively."
  {:malli/schema [:=> [:cat :string :map] :string]}
  [html units]
  (str/replace html #"<!--s:([^>]*?)-->" (fn [[_ id]] (compose (get-in units [id :html] "") units))))

(def ^:private slot-re #"<!--s:([^>]*?)-->")

(defn- order-only?
  "Whether html differs from was only in which child each slot holds: the same text with every slot where it was, and the same slots.
  A slot that moved into another element is no reorder. See docs/render.md#a-layout-places-its-lists."
  {:malli/schema [:=> [:cat [:maybe :string] [:maybe :string]] :boolean]}
  [was html]
  (boolean (and was html (not= was html) (= (str/replace was slot-re "<!--s-->") (str/replace html slot-re "<!--s-->"))
                (= (sort (map second (re-seq slot-re was))) (sort (map second (re-seq slot-re html)))))))

(defn- kept-run
  "The longest run of xs, numbers, that rises (patience sorting, n log n): the indexes into xs that stay where they are. See
  docs/render.md#a-layout-places-its-lists."
  {:malli/schema [:=> [:cat [:vector :int]] [:set :int]]}
  [xs]
  (let [[tails prev] (reduce (fn [[tails prev] i]
                               (let [x (nth xs i)
                                     j (loop [lo 0 hi (count tails)] (if (< lo hi) (let [m (quot (+ lo hi) 2)] (if (< (nth xs (nth tails m)) x) (recur (inc m) hi) (recur lo m))) lo))]
                                 [(if (= j (count tails)) (conj tails i) (assoc tails j i)) (assoc prev i (when (pos? j) (nth tails (dec j))))]))
                             [[] {}] (range (count xs)))]
    (loop [i (peek tails) out #{}] (if (some? i) (recur (get prev i) (conj out i)) out))))

(defn- moves
  "The units that changed place among those view held in their container, as order (container -> ids, this step's) has them against
  was (the last step's): in each container the held ones that stay in order are the longest run that keeps it (`kept-run`), each
  other is taken out and put back after the unit now before it, or before the one now after it. [[id container]] in order. See
  docs/render.md#a-layout-places-its-lists."
  {:malli/schema [:=> [:cat [:maybe :map] :map] [:vector [:tuple :string :string]]]}
  [was order]
  (vec (for [[c ids] order
             :let [old (get was c) at (zipmap old (range)) held (filterv at ids)
                   stay (into #{} (map held) (kept-run (mapv at held)))]
             id held :when (not (stay id))]
         [id c])))

(defn- finish-patch
  "A patch's units as HTML, each composed with its children's (a unit's slots), a unit inside an ancestor the patch also sends left
  to it, its appends and prepends (a container's new rows before what it holds as one prepend, in the layout's order), the removes
  of the contained units the layout no longer has, and the moves of those that changed place: a unit whose html changed only in its
  children's order is not sent, its children that moved are (`moves`), so a reorder costs what moved. Units, this step's in the
  layout's order. See docs/render.md#a-layout-places-its-lists."
  {:malli/schema [:=> [:cat :map :map [:sequential :map]] [:tuple :map :map]]}
  [view patch units]
  (let [live-ids (set (map :id units))
        gone (for [[id u] (:units view) :when (and (:container u) (not (live-ids id)))] id)
        order (reduce (fn [m {:keys [id container]}] (cond-> m container (update container (fnil conj []) id))) {} units)
        was (:order view)
        view (-> view (update :units #(apply dissoc % gone)) (assoc :order order))
        html #(compose (get-in view [:units % :html]) (:units view))
        ;; a unit whose html moved only in its children's order: its children's moves say it
        reordered (into #{} (keep (fn [[id h]] (when (order-only? (get-in patch [:was id]) h) id))) (:frags patch))
        patch (update patch :frags #(vec (remove (comp reordered first) %)))
        ;; a unit sent inside an ancestor this patch also sends (a parent re-drawn carries its children, composed) is not sent again
        sent (into #{} (concat (map first (:frags patch)) (map second (:appends patch)) (map second (:prepends patch)) (map second (:afters patch))))
        inside? (fn inside? [id] (when-let [p (get-in view [:units id :parent])] (or (contains? sent p) (inside? p))))
        ;; what moved, unless an ancestor sent carries it; a moved unit is sent whole by its move, its own frag and its children's with it
        mv (vec (remove (comp inside? first) (moves was order)))
        moved (set (map first mv))
        under? (fn under? [id] (when-let [p (get-in view [:units id :parent])] (or (contains? moved p) (under? p))))
        own (fn [k xs] (vec (remove #(or (inside? (nth % k)) (moved (nth % k)) (under? (nth % k))) xs)))
        patch (-> patch (update :frags #(own 0 %)) (update :appends #(own 1 %)) (update :prepends #(own 1 %)) (update :afters #(own 1 %)))
        ;; its neighbours in the container's new order: by element id, so a unit whose parent element has no id of its own lands in it
        beside (fn [id c] (let [ids (get order c) i (.indexOf ^java.util.List ids id)] [(when (pos? i) (nth ids (dec i))) (get ids (inc i))]))]
    [view (-> patch
              (dissoc :was)
              ;; each sent unit's html with its children's composed into its slots
              (update :frags (fn [fs] (vec (for [[id _] fs] [id (html id)]))))
              (update :appends (fn [as] (vec (for [[c el] as] [c (html el)]))))
              (update :prepends (fn [ps] (vec (for [[c group] (group-by first ps)] [c (apply str (map (comp html second) group))]))))
              ;; a unit placed after its sibling: by the sibling's element id
              (update :afters (fn [as] (vec (for [[sib el] as] [(str "#" sib) (html el)]))))
              (assoc :moves (vec (for [[id c] mv :let [[b a] (beside id c)]] (if b [id (str "#" b) "after" (html id)] [id (str "#" a) "before" (html id)]))))
              (assoc :removes (vec gone)))]))

;;; --- a location's page ------------------------------------------------------------------------

(defn- first-of
  "A vector view's kind ([:chat v], [:debug v]), else nil."
  {:malli/schema [:=> [:cat :any] :any]}
  [view] (when (vector? view) (first view)))

(defn- address-of
  "The text of a keyword address (view-of made it of the path): \"b0/a1\", the name with its namespace."
  {:malli/schema [:=> [:cat :keyword] :string]}
  [k] (str/replace-first (str k) ":" ""))

(defn- session-of
  "The newest agent entity on main db that address names, nil when it names none: the one resolver of an agent's page,
  by whichever name a person holds, the stable one first. Its key (:seon.agent/key, boot/id: the session, which stays
  when a root restarts), its id in this boot (:a3, or the session's agent a process of this boot continues), its task's id, its
  branch (:task-x, or main/task-x), else, as an id on main (the link /agent/main/<name>), the agent of that id that has no
  branch: its record is main's. Entity ids are issued in the order written, so the newest is the largest. See docs/render.md#a-sessions-address-and-the-agents-list."
  {:malli/schema [:=> [:cat :seon.host/db :string] [:maybe pos-int?]]}
  [db address]
  (let [newest (fn [q & args] (apply d/q q db args))
        eid (fn [lookup] (:db/id (d/entity db lookup)))
        id (keyword address)
        branch (keyword (str/replace address #"^main/" ""))]
    (or (eid [:seon.agent/key address])
        (eid [:seon.user/name address])
        (when-let [t (eid [:seon.task/id address])]
          (newest '[:find (max ?a) . :in $ ?t :where [?a :seon.agent/task ?t]] t))
        (some #(newest '[:find (max ?e) . :in $ ?b :where [?e :seon.agent/branch ?b]] %) [id branch])
        (when-let [[_ n] (re-matches #"main/(.+)" address)]
          (when-let [e (eid [:seon.agent/key n])] (when (nil? (:seon.agent/branch (d/entity db e))) e))))))

(defn- location
  "The entity view names on main db: root's for :root, the agent a keyword address names for an agent's page (session-of), else the entity
  id it is; nil when main names none (no boot fact yet, or no agent of that address). See docs/render.md#the-page."
  {:malli/schema [:=> [:cat :seon.host/db :any] [:maybe pos-int?]]}
  [db view]
  (cond (vector? view) (location db (second view))
        ;; root's page is root's agent's (its session, the agent keyed "root", made at boot), drawn as any agent's is
        (= :root view) (or (:db/id (d/entity db [:seon.agent/key "root"])) (:db/id (d/entity db [:seon.root/id :root])))
        ;; home: whoever is signed in (docs/users.md#whats-on-screen)
        (= :home view) (let [u (:seon.root/signed-in (d/entity db [:seon.root/id :root])) f (:seon.user/focus u)]
                         ;; a focused task is its agent's page once its agent exists, else the person's own
                         (:db/id (or (if (:seon.task/id f) (:seon.task/agent f) f) u)))
        (keyword? view) (session-of db (address-of view))
        :else view))

(defn- focus-tx
  "The transaction that moves the signed-in user's focus to the newest of opened (the views a tab just opened): the agent its page is, or
  root's (:root or :home) retracting it; nil when none of them names a page, the user is not signed in, or the focus already is that. See docs/users.md#whats-on-screen."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:sequential :any]] [:maybe [:vector :any]]]}
  [db opened]
  (when-let [u (some-> db (d/entity [:seon.root/id :root]) :seon.root/signed-in)]
    (let [named (fn [v] (let [x (if (vector? v) (second v) v)]
                          (cond (#{:root :home} x) ::root
                                (keyword? x) (let [e (some->> (location db x) (d/entity db))] (when (:seon.agent/key e) (:seon.agent/key e))))))
          to (last (keep named opened))
          held (:seon.agent/key (:seon.user/focus u))
          who [:seon.user/name (:seon.user/name u)]]
      (cond (nil? to) nil
            (= ::root to) (when held [[:db.fn/retractAttribute who :seon.user/focus]])
            (not= to held) [[:db/add who :seon.user/focus [:seon.agent/key to]]]))))

(defn- focus
  "The focus of view's page for its bar and its head (seon.render.ui/focus-of): :root, else the agent its address names on main db:
  its address (its key: every link of its pages is /agent/<key>, which survives a root's restart), its label (its id, with the
  boot's when it is not this boot's) and the address this view was asked by. The address itself when main names none."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :any] :any]}
  [db view]
  (let [v (if (vector? view) (second view) view)
        e (when (and db (keyword? v) (not= :root v)) (some->> (location db v) (d/entity db)))]
    (cond (= :root v) :root
          (:seon.agent/key e) {:address (:seon.agent/key e)
                               :label (:seon.agent/key e)
                               :view v}
          :else v)))

(defn- debug-value
  "View [:debug id]'s page: the timeline of agent id's requests on its branch as this proc last read it
  (its model calls, each with its basis), the one shown at the view's :at (a model call's eid; absent,
  :latest), drawn by seon.render.ui/debug-turn over seon.render.ui/debug-input when its input changes: a past
  turn's is its eid (made again from its basis, it never changes), latest's the branch's newest transaction (drawn
  again each time the branch moves, and only while a tab watches the view). nil until the branch is read;
  an agent with no branch on main (the user's) or whose branch could not be read has no turn to show, and
  its page says so, never a wait. See docs/render.md#the-debug-page-is-the-wire-request."
  {:malli/schema [:=> [:cat :map [:tuple [:= :debug] :any]] [:maybe :map]]}
  [st view]
  (cond
    (= :root (second view))
    ;; root has no model turn: what it reads is the system's value printed, held each pass (:context)
    (when-let [ctx (:context st)]
      (ui/debug :root nil nil {:id "d-context" :input ctx :schema :seon.render/root-context}))
    (nil? (get-in st [:branches view :db]))
    (let [db (:main-db st) eid (when db (location db view))]
      (when (or (get-in st [:branches view :error]) (and eid (nil? (:seon.agent/branch (d/entity db eid)))))
        (ui/debug (focus (:main-db st) view) nil nil nil)))
    :else
    (let [bdb (get-in st [:branches view :db])
          ;; in the order written: entity ids are issued so
          calls (vec (sort (d/q '[:find [?e ...] :where [?e :seon.model-call/basis _]] bdb)))
          pulled (mapv #(d/pull bdb [:seon.model-call/basis :seon.model-call/tokens :seon.model-call/cache-hit-tokens] %) calls)
          bases (mapv :seon.model-call/basis pulled)
          chosen (get-in st [:tab view :at] :latest)
          gone (when-not (or (= :latest chosen) (some #{chosen} calls)) chosen)
          at (if gone :latest chosen)
          opened (get-in st [:tab view :open] #{})
          raw? (contains? opened "d-turn-raw")
          lb (peek bases)
          to-come? (ui/to-come? bdb lb)]
      (ui/debug (focus (:main-db st) view)
                (cond-> {:turns (mapv (fn [e {:seon.model-call/keys [basis tokens cache-hit-tokens]}]
                                                  (cond-> {:e e :turn (:turn basis) :origin (:origin basis)} tokens (assoc :tokens tokens) cache-hit-tokens (assoc :cached cache-hit-tokens)))
                                                calls pulled) :at at :to-come? to-come?} gone (assoc :gone gone))
                (when (seq calls)
                  {:id "d-turn" :input [at (when (= :latest at) (:max-tx bdb)) opened]
                   :draw (fn [_] [(ui/debug-turn {:unit "d-turn" :seon.render/index (program/index-of-db bdb)} (-> (ui/debug-input bdb at calls raw?) (assoc :view (ui/view-param view) :opened opened))) nil])})
                nil))))

(def ^:private gone "The agent statuses of an ended process (:seon.agent/status)." (:seon.status/gone (contract/properties :seon.agent/status)))

(defn- agent-of
  "The agent entity view names on main db, and its id in root's boot (nil for an earlier boot's):
  [entity id], or nil when view names no agent. An agent's chat ([:chat v]) names it; its context
  view ([:debug v]) is a page of its model's requests, not of the agent."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :any] [:maybe [:tuple :any [:maybe :keyword]]]]}
  [db view]
  (when-let [eid (when db (let [v (if (= :chat (first-of view)) (second view) view)] (when-not (vector? v) (location db v))))]
    (let [e (d/entity db eid)] (when (:seon.agent/key e) [e (task/this-boots db e)]))))

(defn- kept-walk
  "A walk kept across moves: entity e's ctx on db (seon.ctx/build, its tree as mark makes it) in a reactive
  atom, drawn once by its entity's render (seon.render/render-var-of: its tree, with ident, the identity the caller
  holds of it, for a branch that holds no entity yet) over the db it holds: {:w :a :c :at}, :at a volatile of that db.
  Root's own page (root-walk), each user's (`user-walk`) and each watched agent's branch (`branch-walk!`) keep one; `advance-walk!`
  moves it. Walk-opts go on the walk (:id-prefix, `user-walk`'s). See docs/render.md#a-page-is-a-kept-walk."
  {:malli/schema [:function [:=> [:cat :seon.host/db [:maybe pos-int?] :any :map :seon.host/fn] :map]
                  [:=> [:cat :seon.host/db [:maybe pos-int?] :any :map :seon.host/fn [:map [:id-prefix {:optional true} :string]]] :map]]}
  ([db e branch ident mark] (kept-walk db e branch ident mark {}))
  ([db e branch ident mark walk-opts]
  (let [ix (program/index-of-db db)
        c (assoc-in (ctx/build ix db e branch) [:ix :own-index?] true) at (volatile! db) a (ratom/atom (mark (:tree c)))
        w (merge (render/walker {:ref a :changes ctx/changes} (fn [] @at) [(render/render-var-of ix (merge ident (:tree c))) {} (ratom/ref-view a ctx/changes)] ix) walk-opts)]
    (render/flush! w nil)
    {:w w :a a :c c :at at})))

(defn- advance-walk!
  "Kept walk kw (`kept-walk`) as of db, a later value of its branch: its ctx patched by what moved
  (seon.ctx/advance), its atom reset to the tree mark makes of it and the moved datoms flushed, so the
  components their change reached are drawn and nothing else; at the db it holds, only what mark changed.
  Answers kw with its ctx. See docs/render.md#a-page-is-a-kept-walk."
  {:malli/schema [:=> [:cat :map :seon.host/db :any :seon.host/fn] :map]}
  [{:keys [w a c at] :as kw} db branch mark]
  (let [held @at
        same? (identical? held db)
        moved (if same? [] (ctx/moved held db nil))
        c' (if same? c (ctx/patch c db moved branch))]
    (vreset! at db)
    (reset! a (mark (:tree c')))
    (render/flush! w moved)
    (assoc kw :c c')))

(defn- branch-walk!
  "The kept walk of agent e's page (e on main) on its branch, as of bdb, the evals main records it opened
  (:seon.agent/opened) drawn opened: one a branch, held in its read's cell (:branch-reads b :walk, a
  volatile) so every view of the agent (its chat drawn from its branch, @ctx, the debug page's latest)
  draws from it; built at the first, after it advanced (`advance-walk!`), made again when the agent's entity on
  the branch is not the one it holds (none yet at its first draw); at the same db and opened, nothing at all. Answers {:w :c}. See docs/render.md#a-page-is-a-kept-walk."
  {:malli/schema [:=> [:cat :seon.host/volatile :seon.host/db :any] :map]}
  [cell bdb e]
  (let [opened (set (:seon.agent/opened e))
        branch (or (:seon.agent/branch e) :db)
        mark (fn [t] (cond-> t (seq opened) (update :seon.eval/_agent #(reduce-kv (fn [m k ev] (if (opened (:seon.eval/id ev)) (assoc m k (assoc ev :seon.eval/opened true)) m)) % %))))
        kw @cell]
    (binding [datahike.index.interface/*reads* nil]
      (let [eid (seon.db/eid bdb :seon.agent/key (:seon.agent/key e))]
      (cond
        ;; a walk of another entity than the branch holds now (none, before the agent's first write to its
        ;; branch; another, a branch made again) is made again: a patch reaches only what its ctx holds
        (or (nil? kw) (not= eid (get-in kw [:c :tree :db/id])))
        (do (some-> kw :w render/dispose-walk!)
            (vreset! cell (assoc (kept-walk bdb eid branch {:seon.agent/key (:seon.agent/key e)} mark) :opened opened)))
        (and (identical? @(:at kw) bdb) (= opened (:opened kw))) kw
        :else (vreset! cell (assoc (advance-walk! kw bdb branch mark) :opened opened)))))))

(defn- live?
  "Is agent id a process root's view holds, running (it answers what it is asked)?"
  {:malli/schema [:=> [:cat :map :any] :boolean]}
  [st id] (let [a (when id (get-in st [:view :agents id]))] (boolean (and a (not (gone (:status a)))))))

(defn- ask-parts
  "Ask each live agent a watched chat shows for its page's parts ({:op :render}, seon.repl/render-ctx)
  when its branch moved since it was last asked (that view's branch read's t), one ask in flight an
  agent, held by its id, so every view of it shows one answer: the agent draws its own page, by its
  own renders, the parts its model reads."
  {:malli/schema [:=> [:cat :map :seon.host/volatile] :map]}
  [st o]
  (reduce (fn [st view]
            (let [[_ id] (agent-of (:main-db st) view)
                  t (some-> (get-in st [:branches view :db]) :max-tx)]
              (if (and t (= :chat (first-of view)) (live? st id) (not= t (get-in st [:parts id :asked])))
                (-> (assoc-in st [:parts id :asked] t) (ask o [:parts id] {:op :render :id id}))
                st)))
          st (into (:watch st #{}) (concat (vals (:waiting st)) (:kf-wanted st)))))

(defn- user-walk
  "The kept walk of user e's page on main db, by its declared layout, made at its first draw and held in the proc's cell (:user-walks, one a user), or nil when e is no user. `user-walks` advances it as main moves. See docs/render.md#a-page-is-a-kept-walk."
  {:malli/schema [:=> [:cat :map :seon.host/db :any] [:maybe :map]]}
  [st db e]
  (when-let [cell (when (and (:seon.agent/key e) (nil? (:seon.agent/branch e))) (:user-walks st))]
    (let [n (:seon.agent/key e)]
      (or (get @cell n)
          ;; a user's walk draws the agents list the frame puts on every page, beside another walk's units: its elements are named apart
          ;; (a page's units are keyed by element id, and an agent's entry is on root's page twice: the list and root's sessions)
          (let [kw (kept-walk db (:db/id e) :main {} identity (if (:seon.user/name e) {:id-prefix "u-"} {}))]
            (vswap! cell assoc n kw)
            kw)))))

(defn- list-units
  "The units of list sel among units (seon.render/placed-units, a parent before its children): those placed in it and every unit
  nested in one of them, at any depth. See docs/render.md#a-layout-places-its-lists."
  {:malli/schema [:=> [:cat [:sequential :map] :string] [:vector :map]]}
  [units sel]
  (first (reduce (fn [[out ids] u] (if (or (= sel (:container u)) (contains? ids (:parent u))) [(conj out u) (conj ids (:id u))] [out ids]))
                 [[] #{}] units)))

(defn- user-walks
  "St with each user's kept walk advanced to main db, and the views that show one dirty, as root's own is (`root-walk`): the components the move reached are drawn, nothing else. See docs/render.md#a-page-is-a-kept-walk."
  {:malli/schema [:=> [:cat :map :seon.host/db] :map]}
  [st db]
  (let [cell (or (:user-walks st) (volatile! {}))
        ;; the signed-in user's agents list is every page's (`user-sides`): its change redraws every view
        signed (some-> db (d/entity [:seon.root/id :root]) :seon.root/signed-in :seon.user/name)
        side (fn [] (when-let [kw (get @cell signed)] (into {} (for [u (list-units (render/placed-units (:w kw)) "#c-side")] [(:id u) (:rev u)]))))
        before (side)]
    (doseq [[n kw] @cell] (vswap! cell assoc n (advance-walk! kw db :main identity)))
    (cond-> st
      (not= before (side)) (update :dirty (fnil into #{}) (keys (:views st)))
      (seq @cell) (update :dirty (fnil into #{})
                          ;; the views of the agents these walks draw (each branchless: a user, root's session): their pages moved with main
                          (filter (fn [v] (let [x (if (vector? v) (second v) v)]
                                            (when (keyword? x) (some->> (location db x) (d/entity db) :seon.agent/key (contains? @cell))))) (keys (:views st)))))))

(defn- user-sides
  "The agents list every page shows: the signed-in user's (root's :seon.root/signed-in), as that user's kept walk drew it (seon.render.ui/agents-list,
  its page's c-side): root, then what the user started, each with what it started under it. Nothing before main is read or with no one signed in.
  See docs/users.md#whats-on-screen."
  {:malli/schema [:=> [:cat :map [:maybe :seon.host/db]] [:vector :map]]}
  [st db]
  (if-let [kw (when-let [u (some-> db (d/entity [:seon.root/id :root]) :seon.root/signed-in)] (user-walk st db u))]
    (list-units (render/placed-units (:w kw)) "#c-side")
    []))

(defn- agent-view
  "View's page when it names an agent (entity eid on main db): its components as its process drew
  them (ask-parts, seon.repl/render-ctx: the units of its walk) at the branch root read or later, else, with no process
  to ask, none that answered or one still busy with its run, as root draws them from its branch (seon.ctx/build, drawn once by the same walk
  and layout: seon.render/draw) with main's renders, the page saying so. Everything the page shows
  of the agent is its ctx: its run's status and verdict reach its branch by take!, never read here
  on main. Root's agents (the sidebar) are root's chrome; its input (seon.render.ui/agent-input)."
  {:malli/schema [:=> [:cat :map :any :seon.host/db pos-int?] :map]}
  [st view db eid]
  (let [;; [:chat v]: the agent's page, the render of its ctx; else (its debug view) its ctx printed
        chat? (or (vector? view) (= :root view))
        [e id] (agent-of db (if (vector? view) (second view) view))
        held (get-in st [:parts id])
        berr (get-in st [:branches view :error])
        ;; its process's drawing, when it drew at the branch root read or later: a process works its whole run as one
        ;; request, so until it answers root draws what its branch holds now (a person's message, each eval as it lands)
        own? (and chat? (live? st id) (vector? (:units held))
                  (<= (or (some-> (get-in st [:branches view :db]) :max-tx) 0) (or (:t held) 0)))
        ;; an agent with no branch (the user's, an example) is read from main, its reads tracked as the page's own
        branchless? (nil? (:seon.agent/branch e))
        bdb (if branchless? db (get-in st [:branches view :db]))
        quiet (fn [f] (if branchless? (f) (binding [datahike.index.interface/*reads* nil] (f))))
        ;; the branch's record, drawn outside the read set: a move of the branch marks this page dirty
        ;; when root reads it (take-read), and the branch's reads are not main's
        ;; the evals a reader opened (main's :seon.agent/opened, read here, so its commit passes the
        ;; page again): drawn opened, as the agent draws them once it takes the fact
        opened (set (:seon.agent/opened e))
        ;; the branch's kept walk (branch-walk!), one a branch; an agent read from main is drawn once, its reads the page's
        cell (get-in st [:branch-reads (get-in st [:branches view :branch]) :walk])
        walked #(when (and cell (not branchless?)) (branch-walk! cell bdb e))
        bix (delay (program/index-of-db bdb))
        cold-tree #(quiet (fn [] (:tree (ctx/build @bix bdb (seon.db/eid bdb :seon.agent/key (:seon.agent/key e)) (or (:seon.agent/branch e) :db)))))
        ;; a branchless agent's kept walk (`user-walk`: a user's, root's session's) tracks its own reads and `user-walks` marks its views
        ;; dirty when main moves it: its first draw here is no read of this page's
        branch-units (fn [] (if-let [kw (or (walked) (binding [datahike.index.interface/*reads* nil] (user-walk st db e)))]
                              (binding [datahike.index.interface/*reads* nil] (render/placed-units (:w kw)))
                              (let [t (cond-> (cold-tree) (seq opened) (update :seon.eval/_agent #(reduce-kv (fn [m k ev] (if (opened (:seon.eval/id ev)) (assoc m k (assoc ev :seon.eval/opened true)) m)) % %)))
                                    w (quiet #(render/draw t ctx/changes bdb (render/render-var-of @bix (merge {:seon.agent/key (:seon.agent/key e)} t)) {:index @bix}))]
                                (try (render/placed-units w) (finally (render/dispose-walk! w))))))
        ;; @ctx's part pairs (seon.render.ui/ctx-parts): what the model reads of each part of the task's list, beside
        ;; its unit as the page draws it, from one walk of the page
        ;; the part texts the tab opened (/open: a long one is drawn closed, ui/ctx-parts)
        opened-parts (into #{} (filter #(str/starts-with? % "d-part-")) (get-in st [:tab view :open] #{}))
        pairs (fn [w] (let [all (render/placed-units w)
                            by (into {} (map (juxt :id identity)) all)
                            lists (render/page-lists w)
                            ;; each list's units, the ones nested in them too: a nested one's change is the pair's
                            us (vec (mapcat #(list-units all %) lists))
                            ;; the page's rules come with the parts they style
                            rules (list-units all "#p-rules")
                            ;; a part's unit as the page shows it, its nested units composed into it
                            html #(compose (:html %) by)]
                        {:id "d-parts" :container "#c-rows" :rev (hash [(mapv (juxt :id :rev) (concat us rules)) opened-parts])
                         :html (h/html (ui/ctx-parts (render/placed-hiccup w lists) (into {} (map (juxt :id html)) (remove :parent us)) opened-parts
                                                     (apply str (map html (remove :parent rules)))))}))
        ;; the debug view: @ctx, its part pairs and the agent's record as data by the one printer (its :seon.ctx/ctx render)
        ctx-units (fn [] (let [kw (walked)
                               t (if kw (:tree (:c kw)) (cold-tree))
                               part-unit (if kw
                                           (quiet #(pairs (:w kw)))
                                           (let [w (quiet #(render/draw t ctx/changes bdb (render/render-var-of @bix (merge {:seon.agent/key (:seon.agent/key e)} t)) {:index @bix}))]
                                             (try (quiet #(pairs w)) (finally (render/dispose-walk! w)))))]
                           [part-unit
                            {:id "d-ctx" :container "#c-rows" :rev (:t t)
                             :html (h/html [:li {:id "d-ctx"} (quiet #(render/hiccup {:seon.render/index (if kw @(:index (:w kw)) @bix) :db bdb} (with-meta t {:seon.render/schema :seon.ctx/ctx})))])}]))
        ;; its process's draw and root's each count revisions of their own: a unit says whose it is, so its process's answer replaces root's first draw at the same number
        units (if own?
                (mapv #(assoc % :own? true) (:units held))
                (cond (and bdb (not chat?)) (ctx-units)
                      bdb (branch-units)
                      ;; a branch that could not be read: its error, by the error's render
                      berr [{:id "p-branch-error" :container "#p-task" :rev 0 :html (h/html [:section {:id "p-branch-error"} (render/hiccup {:seon.render/index (seon.db/index)} berr)])}]))
        ;; root's agents list, as root's walk drew it, and the agent's head from its facts on main
        ;; the signed-in user's agents list, the one every page shows (`user-sides`), and the agent's head from its facts on main
        ;; every agent's page draws its own agents list; the frame shows the signed-in user's on every page, one walk's (`user-sides`)
        own-side (set (map :id (list-units (or units []) "#c-side")))
        units (vec (remove (comp own-side :id) units))
        sides (user-sides st db)
        ;; root's session (the agent keyed "root", the reserved key `location` names for root's page): beside its page, the
        ;; system's own view (its head, process and problems: root's runtime values, never written) from the system's walk, placed by
        ;; the frame as the agents list is, the same mechanism
        system? (= "root" (:seon.agent/key e))
        system (when (and chat? system?) (some-> (:root-walk st) :w render/placed-units))
        head (cond-> (or (d/pull db [:seon.user/name :seon.agent/status :seon.agent/branch :seon.agent/description :seon.agent/max-turns {:seon.agent/task [:seon.task/id :seon.task/outcome :seon.task/reason :seon.task/failure]} {[:seon.process/_agent :as :processes] [:seon.process/pid :seon.process/forked-ms]}] eid) {:seon.agent/key (:seon.agent/key e)})
               ;; an earlier boot's process recorded no end (its root went away): its pid names nothing now
               (not (task/this-boots db e)) (dissoc :processes))
        head-unit {:id "p-agent-head" :container "#p-head" :rev (hash head)
                   :html (h/html [:section {:id "p-agent-head" :class "rounded-md bg-base-900 p-3 text-sm text-text-100"} (ui/agent-head {} head)])}]
    (ui/page-chrome {:units (vec (concat sides [head-unit] system units)) :view (ui/view-param view) :tab (if chat? :chat :debug)
                      ;; never a wait: drawn from its branch until its process answers, the page says so
                      :caption (when (and chat? (not own?))
                                 (when (:error held) (str "Its process didn't answer, so this may be behind: " (:seon.error/message (:error held)))))
                      ;; its REPL and its messages while its process runs; its held task's release
                      :input (if (or (:seon.user/name e) system?) {:schema :seon.render/root-input :value {}} {:schema :seon.render/agent-input
                              :value {:id (keyword (:seon.agent/key e)) :address (keyword (:seon.agent/key e)) :live? (live? st id) :focus? chat?
                              ;; what its process can answer: a form running is interrupted (busy)
                              :busy? (= :busy (:seon.agent/status e)) :talks? (some? (:seon.agent/task e))
                                      :held (d/q '[:find ?id . :in $ ?a :where [?a :seon.agent/task ?t] [?t :seon.task/execution :held] [?t :seon.task/id ?id]] db eid)}})}
                     ;; its tabs name it by its id in this boot, else by its branch (an earlier boot's agent)
                     (focus db view))))

(defn- root-walk
  "St with root's walk current as of main db: root's ctx (seon.ctx/build of root's entity on main)
  held in a reactive atom its walk draws by the layout root's entity names (:seon.render/root-ctx), built at main's
  first read, then advanced by what each move of main changed (seon.ctx/advance, its datoms
  flushed): the walk draws the components their change reached, nothing else. Root's page is dirty when it moves; the agents
  list every page shows is the signed-in user's (`user-sides`), not root's."
  {:malli/schema [:=> [:cat :map :seon.host/db [:maybe :seon.host/db]] :map]}
  [st db held]
  (let [root (:db/id (d/entity db [:seon.root/id :root]))
        rw (:root-walk st)]
    (cond
      (nil? root) st
      (nil? rw)
      (-> (assoc st :root-walk (kept-walk db root :main {} identity))
          (update :dirty (fnil into #{}) (keys (:views st))))
      (nil? held) st
      :else
      (let [{:keys [a]} rw
            ;; root's process (process!) is root's own data beside the pull: kept across a move of main
            p (:process @a) h (:health @a)
            rw (advance-walk! rw db :main #(cond-> % p (assoc :process p) h (assoc :health h)))]
        (update (assoc st :root-walk rw) :dirty (fnil conj #{}) :root)))))

(defn- process!
  "St with root's process value p (root's ruling on root's process values, option a: the writer's, the fork
  server's and the store's numbers, each proc's defects and the event stream are root's own data,
  part of root's ctx beside its db pull, never written) in root's walk: when it differs from what
  the walk holds, assoc'd into root's ctx and flushed, so the one component that reads it runs again."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [st p]
  (if-let [{:keys [w a]} (:root-walk st)]
    (if (= p (:process @a))
      st
      (do (reset! a (assoc @a :process p))
          (render/flush! w nil)
          (update st :dirty (fnil conj #{}) :root)))
    st))

(defn- health!
  "St with main's health (the report the problems tile draws) in root's walk, beside root's process, as
  of the main it holds: derived data, read only when a reader is there (a tab, a waiting page, a
  `:context` request: `pass`, `with-context`), never per commit. It is kept with the point of the
  main it was read at (:health-at, `seon.db/point`, never a db: a kept db pins its version of the
  index); it is changed by the datoms that moved between that main (made again with
  `seon.db/at-commit`) and this one (`seon.problems/health-moved`, which reads the runs and sections
  they touched and not the closed history, however many commits the gap holds), or read whole when
  none is held or the held one was read from a db ahead of its commit. Its counts are the ones the
  fold it was last asked with puts it at (`seon.problems/with-fold`): a repeat of a written signature,
  which main does not hold yet, is counted there, so the banner, root's context and the api's answer are
  one count, and a fold that moved with main still counts it again. When no class changed, the held
  report stays and nothing is flushed.
  See docs/render.md#health-is-what-flows."
  {:malli/schema [:=> [:cat :map] :map]}
  [st]
  (if-let [{:keys [w a]} (:root-walk st)]
    (let [db (:main-db st) pt (seon.db/point db) from (:health-at st) prev (:health @a) fold (:fold st)]
      ;; the held report stands while both what it was read from and the counts it was put at do
      (if (and (= pt from) (= fold (:health-fold st)))
        st
        (let [base (when (and prev from (not= pt from)) (seon.db/at-commit (:conn st) (first from)))
              ;; the commit's own value, as the held health was read from it; a db ahead of its commit is read whole
              h (cond (and prev (= pt from)) prev
                      (and base (= (:max-tx base) (second from))) (problems/health-moved prev base db (apply concat (d/datoms-diff base db :eavt [])))
                      :else (problems/health db))
              h (if (seq fold) (problems/with-fold h db fold) h)
              st (assoc st :health-at pt :health-fold fold)]
          (if (= (:classes h) (:classes prev))
            st
            (do (reset! a (assoc @a :health h))
                (render/flush! w nil)
                (update st :dirty (fnil conj #{}) :root))))))
    st))

(defn- with-context
  "St with root's context (what root reads: the render of its ctx, each component's hiccup by its id,
  printed once at root's context budget, what is wrong first: its tiles, its evals, its head, its
  agents) current for root's walk as of main's t. Derived data, kept as :context under :context-key
  (the walk's value and main's t) and printed again only when either moved: the one print serves
  every reader (root's debug page, the :context request), and a pass nobody reads it in costs
  nothing (docs/render.md#the-page)."
  {:malli/schema [:=> [:cat :map] :map]}
  [st]
  (if-let [{:keys [w a]} (:root-walk st)]
    (let [v @a t (:max-tx (:main-db st)) k (:context-key st)]
      (if (and k (identical? v (k 0)) (= t (k 1)))
        st
        (let [r (print/print-value (render/placed-hiccup w ["#p-tiles" "#c-rows" "#p-head" "#c-side"])
                                   {:budget (config/value :seon.config.render/root-context-budget)})
              ctx (or (:text r) (:seon.error/message r))]
          (assoc st :context {:text ctx :tokens (select/estimate ctx)} :context-key [v t]))))
    st))

(defn- second-of
  "The name inside a view, [:chat v]'s v, else nil."
  {:malli/schema [:=> [:cat :any] :any]}
  [view] (when (vector? view) (second view)))

(defn- page-value
  "View's page: root's (its agent's, agent-view), an agent's debug page (debug-value) or an agent's
  (agent-view); nil when main is not read yet, :seon.render/no-location when main names no agent
  for it (an agent's id of no agent in this boot)."
  {:malli/schema [:=> [:cat :map :any] [:maybe :map]]}
  [st view]
  (let [db (:main-db st)
        eid (when db (location db view))]
    (cond
     ;; root's page is its session's, made at boot: none yet, nothing to draw
     (= :root view) (when (and eid (:seon.agent/key (d/entity db eid))) (agent-view st view db eid))
     (and eid (= :debug (first-of view))) (debug-value st view)
     (and eid (= :chat (first-of view)) (agent-of db (second view))) (agent-view st view db eid)
     (and eid (agent-of db view)) (agent-view st view db eid)
     (= :home (second-of view)) (ui/page-chrome {:units [{:id "p-sign-in" :container "#p-head" :rev 0 :html (h/html (ui/sign-in {:seon.db/db db}))}] :view (ui/view-param view) :tab :chat} :root)
     db (contract/error :seon.render/no-location #'seon.render.proc/page-value
                        (str "no " (let [v (if (vector? view) (second view) view)] (if (keyword? v) (str "agent " (address-of v)) (ui/kname v)))
                             " on main: an address names an agent by its key (boot/id), its id in this boot, its task or its branch; the home page lists every session")
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
    {:tab (get-in st [:tab view]) :error (get-in st [:branches view :error])
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
  the views read."
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
        (cond-> (user-walks (root-walk (main-info st db moved) db held) db)
          (seq moved) (update :dirty (fnil into #{}) (touched st moved)))))))

(defn- error-page
  "A view's page that is its error e, by the error's render, in the root every page has (#app): a page sent
  whole later replaces the tab's #app, so a page without it could never become its view's page."
  {:malli/schema [:=> [:cat :map] :any]}
  [e] [:div {:id "app" :class "mx-auto flex max-w-screen-xl flex-col gap-3 p-3 sm:p-4"}
       (render/hiccup {:seon.render/index (seon.db/index)} e)
       ;; the nearest way to look: every session, from the home page
       (when (= :seon.render/no-location (:seon.error/type e)) [:p [:a {:href "/root"} "◆ seon: every session on main"]])])

(defn- pass-view
  "One view rendered: [st' patch-message-or-nil]; a view whose entity main does not hold yet renders
  nothing. Its walk runs inside d/with-reads, and what it read is kept (reindex): one index for
  the dbs it reads, main's and its agent's branch's (a datom of either is matched against both: a
  needless walk at worst, never a missed one). A unit is drawn only when its input changed
  (step-units). A view whose patch would replace an element its tabs never held (a new unit in no list) is sent
  whole, a keyframe."
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
    (:seon.error/type out) [(let [hc (error-page out)]
                                          ;; an address naming no agent answers 404 (:status, which the page's answer carries to the edge)
                                          (hold st (cond-> {:skeleton (h/html hc) :layout hc :units {}} (= :seon.render/no-location (:seon.error/type out)) (assoc :status 404)))) nil]
    :else
    (let [units (:seon.render/units out)
          [view patch n] (step-units (units-index st) held units)
          [view patch] (finish-patch view patch units)
          skel (h/html (:seon.render/hiccup out))
          view (assoc view :skeleton skel :layout (:seon.render/hiccup out))]
      [(-> (hold st view) (update-in [:counts :rendered] (fnil + 0) (:rendered n)) (update-in [:counts :same] (fnil + 0) (:same n)))
       (cond
         ;; a patch replaces only elements the tab holds: a unit new to it in no container (its slot in the layout,
         ;; not a list's) has no element there to replace, so the tab is sent the page whole
         (and held (some #(not (contains? (:units held) (first %))) (:frags patch))) {:op :keyframe :whole true :view view-id :html (compose skel (:units view))}
         (or (seq (:frags patch)) (seq (:appends patch)) (seq (:prepends patch)) (seq (:afters patch)) (seq (:moves patch)) (seq (:removes patch)))
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
    (let [r (pass-view st view-id)]
      ;; a component that threw in a kept walk is a problem root records, as a view that threw is
      (doseq [w (concat (some-> (:root-walk (first r)) :w vector) (keep #(some-> (:walk (val %)) deref :w) (:branch-reads (first r))) (map :w (some-> (:user-walks (first r)) deref vals)))
              d (render/take-defects! w)]
        (step/out! o :to-api {:op :error :pid :render :message (:seon.error/message d) :data d}))
      r)
    (catch Exception ex
      (let [held (get-in st [:views view-id])
            d (clojure.main/ex-triage (assoc (Throwable->map ex) :clojure.error/phase :execution))
            named (java.net.URLDecoder/decode (ui/view-param view-id) "UTF-8")
            e (contract/error :seon.render/view-defect #'pass-view (str "the page " named " threw rendering: " (step/error-msg ex))
                              (cond-> {:view named :class (.getName (class ex))}
                                (:clojure.error/symbol d) (assoc :seon.error/in (:clojure.error/symbol d))))
            hc (error-page e)]
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

(defn- drop-read
  "St without branch b's read (no view shows it): its db let go and its kept walk (branch-walk!) disposed."
  {:malli/schema [:=> [:cat :map :any] :map]}
  [st b]
  (some-> (get-in st [:branch-reads b :walk]) deref :w render/dispose-walk!)
  (update st :branch-reads dissoc b))

(defn- drop-view
  "St without view v (no tab watches it, nothing waits on it): its page, its reads in :read-index,
  its branch read (and the branch's db, when no other view shows that branch: a db kept for a page
  nobody has open is a version of the index pinned for nothing, docs/machine.md#roots-heap), its
  rows and opens."
  {:malli/schema [:=> [:cat :map :any] :map]}
  [st v]
  (let [b (get-in st [:branches v :branch])
        st (-> (reindex st v (get-in st [:views v :reads]) #{})
               (update :views dissoc v) (update :branches dissoc v) (update :tab dissoc v)
               (update :dirty #(disj (or % #{}) v)))]
    (cond-> st (and b (not-any? #(= b (:branch %)) (vals (:branches st)))) (drop-read b))))

(defn- index-of
  "What the web proc needs to turn a UI call into root's request (each agent's pid, branch
  and status) and to find an agent's tabs by its stable key (:key, its id's name). See
  docs/render.md#in-flight-output-streams."
  {:malli/schema [:=> [:cat :map] :map]}
  [agents]
  (into {} (for [[id a] agents]
             [id (assoc (select-keys a [:pid :branch :status]) :key (name id))])))

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
          views (into (:watch st #{}) (concat (vals (:waiting st)) (:kf-wanted st)))
          ;; what root reads (its context) is printed only for a page that shows it ([:debug :root]) or
          ;; when asked (:context, below): a pass nobody reads it in prints nothing
          st (cond-> st (seq views) health!)
          st (cond-> st (contains? views [:debug :root]) with-context)
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
      (doseq [[k v] (:waiting st) :when (ready? st v)] (step/out! o :to-web (cond-> {:op :page :k k :view v :html (view-html st v)} (get-in st [:views v :status]) (assoc :status (get-in st [:views v :status])))))
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
      (let [rec (repl/record-details form (if err {:ex r} {:value r}) {})]
        (step/out! o :to-api {:op :request :req {:op :write :req {:op :tx :branch :db :tx-data (repl/record-tx {:seon.root/id :root} (str "root-ui-" (profile/now-ns)) (task/sender (config/value :seon.config.root/user-name)) rec)}}
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
    ;; root's view or main's read answered an error (a defect, for a request a step lost): every page and keyframe waiting on it is
    ;; answered by that error drawn (every wait ends, with its value or a typed error), held as its view's page with an :extra no
    ;; pass matches (walk?), so every pass walks it until a read draws it; a view already drawn stays as it was; the next event asks again
    (and (#{:state :main} kind) (seon.contract/error? r))
    (let [vs (distinct (concat (vals (:waiting st)) (:kf-wanted st)))
          hc (error-page r)]
      (pass (-> (reduce (fn [st v] (cond-> st (nil? (get-in st [:views v])) (assoc-in [:views v] {:skeleton (h/html hc) :layout hc :units {} :status 503 :extra ::read-failed})))
                        st vs))
            o))
    (= :state kind)
    ;; an agent whose process ended keeps no parts (a later process of its id draws its own)
    (let [st (-> (assoc st :view r)
                 (update :parts #(into {} (filter (fn [[id]] (live? {:view r} id))) %)))
          ;; main's first read; after it, main comes in the writer's commits (:main)
          st (if (and (nil? (:main-db st)) (not (some #{:main} (vals (:pending st))))) (ask st o :main {:op :write :req {:op :branch-db :branch :db}}) st)]
      ;; a watched agent's branch moved: read its branch db (its turns, evaluations and task)
      (pass (ask-branches st o) o))
    (= :main kind) (pass (ask-branches (advance-main st (:db r)) o) o)
    ;; an agent drew its page's parts (or did not: gone, refused): held for its view, which renders again
    (and (vector? kind) (= :parts (first kind)))
    (let [id (second kind)
          views (filter #(and (= :chat (first-of %)) (= id (second (agent-of (:main-db st) %)))) (into (:watch st #{}) (concat (vals (:waiting st)) (:kf-wanted st))))]
      (pass (-> (update-in st [:parts id] #(if (seon.contract/error? r) (-> % (dissoc :units :t) (assoc :error r)) (-> % (dissoc :error) (assoc :units (:units r) :t (:t r)))))
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
          ;; an answer for a branch no view shows any more (its page was dropped while it was asked) is let go
          st (if (some #(= b (:branch %)) (vals (:branches st)))
               ;; its kept walk (branch-walk!) stays with the branch across reads
               (assoc-in st [:branch-reads b] (cond-> {:at asking :walk (or (get-in st [:branch-reads b :walk]) (volatile! nil))}
                                                err? (assoc :error r) (not err?) (assoc :db (:db r))))
               (drop-read st b))
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
             :main "main's commit notices from the writer {:t :prev :ops :commit}, read with seon.db/at-commit: each one's datoms find the pages whose reads they touched"}
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
     {:user-walks (volatile! {}) :events-ch events :mult (:events-mult args) :system-p (:system args) :conn (:conn args) :rid 0 :pending {} :watch #{} :waiting {} :kf-wanted #{}
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
              :main (pass (if (some-> (:main-db st) :max-tx (>= (:t m)))
                            st ; a notice no newer than the main held reads nothing from the store
                            (advance-main st (seon.db/at-commit (:conn st) (:commit m))))
                          o)
              :answers (let [[st' kind] (step/answered st (:tag m))
                             r (:r m)
                             err (:seon.error/message r)]
                         (cond
                           kind (answered st' o kind r)
                           (and (vector? (:tag m)) (= :web (first (:tag m)))) (web-answered st o (:tag m) r)
                           :else st))
              :from-web (case (:op m)
                          :views (let [w (:views m)
                                       _ (when-let [tx (focus-tx (:main-db st) (remove #(contains? (:watch st) %) w))]
                                           (step/out! o :to-api {:op :request :req {:op :write :req {:op :tx :branch :db :tx-data tx}} :route [:render :answers] :tag [:focus]}))
                                       ;; a view no tab watches keeps nothing: its retained page, its branch read, its rows
                                       drop #(into {} (filter (fn [[v]] (contains? w v))) %)]
                                   (ask-state (-> (reduce drop-view st (remove #(contains? w %) (keys (:views st))))
                                                  (assoc :watch w :tabs (:tabs m)))
                                              o))
                          ;; a UI call root refused before it became a request: answered as root's are
                          :refused (web-answered st o (:tag m) (:r m))
                          :page (if (and (get-in st [:views (:view m)]) (contains? (:watch st) (:view m)))
                                  (do (step/out! o :to-web (cond-> {:op :page :k (:k m) :view (:view m) :html (view-html st (:view m))} (get-in st [:views (:view m) :status]) (assoc :status (get-in st [:views (:view m) :status])))) st)
                                  (ask-state (assoc-in st [:waiting (:k m)] (:view m)) o))
                          :keyframe (if (ready? st (:view m))
                                      (do (step/out! o :to-web {:op :keyframe :view (:view m) :html (view-html st (:view m))}) st)
                                      (ask-state (update st :kf-wanted conj (:view m)) o))
                          ;; main's health for the api (seon.root's :health, my.root/problems): this proc's,
                          ;; brought to the main the asker read (advance-main, health!), never read whole again, and
                          ;; at the counts the api's fold holds (with-fold: a repeat of a written signature is counted
                          ;; there, not yet on main), which it keeps to bring its next move of main from what it held
                          :health (let [st (-> st (advance-main (:db m)) (assoc :fold (:fold m)) health!)
                                        h (some-> (:root-walk st) :a deref :health)]
                                    ;; none held (no root walk yet): read whole, as health! does when it holds none
                                    (step/out! o :to-api {:op :health-answer :rid (:rid m) :db (:main-db st)
                                                          :r (or h (problems/health (:main-db st)))})
                                    (pass st o))
                          :context (let [st (-> st health! with-context)] (step/out! o :to-web {:op :text :k (:k m) :text (get-in st [:context :text] "")}) st)
                          ;; the end of a view's list scrolled into view: the next rows are rendered
                          :more (if (contains? (:watch st) (:view m)) (pass (update-in st [:tab (:view m) :shown] (fnil + (history-cap)) (history-cap)) o) st)
                          ;; the debug timeline stepped to a turn, or to latest
                          :at (if (contains? (:watch st) (:view m)) (pass (assoc-in st [:tab (:view m) :at] (:turn m)) o) st)
                          ;; a row's <details> opened or closed: its whole content is rendered while it is open
                          :toggle (if (contains? (:watch st) (:view m))
                                  (pass (update-in st [:tab (:view m) :open] (fnil (if (:open m) conj disj) #{}) (:unit m)) o)
                                  st)
                          :stats (do (step/out! o :to-web {:op :text :k (:k m) :text (sedn/encode (stats st))}) st)
                          st)
              st)]
     ;; nothing asked is in flight after what was, or after what went to the tabs: their
     ;; views are current (the web proc says so on each feed; seon.check/idle waits on it)
     (when (and (empty? (:pending st)) (or busy? (seq (:to-web @o)))) (step/out! o :to-web {:op :settled}))
     [st @o])))
