(ns seon.ui.agent-view
  "The live per-agent view derived from one frozen database snapshot.

   The most recently agent-updated surface is selected in the primary panel.
   Every resolved
   context block with a nonblank HTML render appears as a compact card in the
   right rail; selecting one shows the same rendered hiccup in the primary
   panel. AI-only blocks are absent. Surface recency derives from database
   transactions and renderer read-sets; selection is browser-local state."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [seon.agent.ctx :as agent-ctx]
    [seon.agent.ctx.render-fns :as render-fns]
    [seon.db :as db]
    [seon.derive :as derive]
    [seon.render :as render]
    [seon.schema :as schema]
    [seon.ui.header :as header]))

(schema/register! ::changed-attrs [:set :qualified-keyword])
(schema/register! ::surface-attrs [:set :qualified-keyword])
(schema/register! ::structural-attrs [:set :qualified-keyword])
(schema/register! ::header-attrs [:set :qualified-keyword])
(schema/register! ::dependencies
  [:map
   [::surface-attrs ::surface-attrs]
   [::structural-attrs ::structural-attrs]
   [::header-attrs ::header-attrs]])

(declare agent-view)

(def ^:private state-display
  {:running    {:dot "●" :class "text-signal"   :label "running"}
   :idle       {:dot "●" :class "text-text-400" :label "idle"}
   :paused     {:dot "⚠" :class "text-warning"  :label "paused"}
   :terminated {:dot "✗" :class "text-text-500" :label "terminated"}})

(defn- status-chip
  "The agent's derived FSM state as a dot and label."
  [db agent-id]
  (let [state (try (derive/derive-state db agent-id) (catch :default _ :idle))
        {:keys [dot class label]} (or (state-display state)
                                      {:dot "●" :class "text-text-400"
                                       :label (name state)})]
    [:span {:class "flex items-center gap-1 text-xs font-mono"}
     [:span {:class class} dot]
     [:span {:class "text-text-200"} label]]))

(defn- selection-key
  "Stable browser selection key for one resolved context block."
  [block-name]
  (str "context-"
       (when-let [ns-part (namespace block-name)] (str ns-part "-"))
       (name block-name)))

(defn- class-token?
  [attrs token]
  (boolean
    (some #{token}
          (some-> (:class attrs "") (str/split #"\s+")))))

(defn- find-face
  "First hiccup node carrying `class-token`, or nil."
  [node class-token]
  (when (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))]
      (if (class-token? attrs class-token)
        node
        (some #(find-face % class-token) children)))))

(defn- face
  "Project an existing dual-face tile without invoking its renderer again."
  [hiccup view]
  (or (find-face hiccup (case view
                          :compact "seon-tile-compact"
                          :expanded "seon-tile-expanded"))
      hiccup))

(defn- transcript-selection? [selection]
  (= selection "context-transcript"))

(defn- bottom-effect
  "Datastar effect that re-anchors a transcript scroller after its revision morph."
  [touch]
  (str "setTimeout(() => { $el.scrollTop = $el.scrollHeight }, 0); " touch))

(defn- primary-panel
  "One selectable primary-panel body. Transcript bodies follow their tail."
  [selection expanded touch]
  [:section (cond-> {:id (str "agent-view-primary-" selection)
                     :data-agent-primary selection
                     :data-show (str "$selected === '" selection "'")
                     :class (str "agent-view-surface tile-hero min-h-0 overflow-auto border "
                                 "border-base-800 rounded-md bg-base-900 p-2 h-full")}
              (transcript-selection? selection)
              (assoc :data-effect (bottom-effect touch)))
   expanded])

(defn- rail-button
  "A compact selectable card for a non-focused primary-panel body."
  [selection label compact touch]
  [:div {:id (str "agent-view-rail-" selection)
         :role "button"
         :tabindex "0"
         :aria-label (str "Show " label " in the primary view")
         :class (str "w-full text-left border border-base-800 rounded-md cursor-pointer "
                     "bg-base-900 overflow-hidden hover:border-base-700")
         :style (str "order:" (- touch))
         :data-show (str "$selected !== '" selection "'")
         :data-class (str "{'border-amber-700': $selected === '" selection "'}")
         (keyword "data-on:click") (str "$selected = '" selection
                                         "'; $manualselection = true")
         (keyword "data-on:keydown")
         (str "if ($event.key === 'Enter' || $event.key === ' ') "
              "{ $event.preventDefault(); $selected = '" selection
              "'; $manualselection = true }")}
   [:div {:class "px-2 py-1 border-b border-base-800 text-2xs text-text-400 font-mono"}
    label]
   ;; The HTML twin may itself contain buttons/forms. The rail is a visual
   ;; preview; interaction belongs to the selected primary render.
   [:div (cond-> {:class "overflow-hidden"
                  :aria-hidden "true"
                  :style "max-height:20rem;pointer-events:none"}
           (transcript-selection? selection)
           (assoc :data-effect (bottom-effect touch)))
    compact]])

(defn- repl-process-eid
  "The stable REPL process entity id in `dbv`."
  [dbv]
  (:db/id
    (db/entity {:seon.db/db dbv
                :seon.db/ref
                [:seon.db.process/id :seon.db.process/repl]})))

(defn- agent-attr-touch
  "Latest deliberate agent/REPL transaction touching `attr` on its entity."
  [dbv agent-id attr]
  (or
    (ffirst
      (db/query
        {:seon.db/db (db/history dbv)
         :seon.db/query
         '[:find (max ?tx)
           :in $ ?aid ?attr ?repl
           :where
           [?e :seon.agent/id ?aid]
           [?e ?attr _ ?tx]
           [?tx :seon.db/user ?author]
           [?author :seon.agent/id ?aid]
           [?tx :seon.db/process ?repl]]
         :seon.db/args [agent-id attr (repl-process-eid dbv)]}))
    0))

(defn- renderer-touch
  "Agent-scoped database touch coordinate for a symbolic HTML renderer."
  [dbv agent-id renderer]
  (if (symbol? renderer)
    (or (::render-fns/touch
          (render-fns/renderer-touch {:seon.db/db dbv
                                      :seon.agent/id agent-id
                                      :seon.render/html renderer}))
        0)
    0))

(defn- assistant-reply-touch
  "Latest real reply from this agent to the human user.

   Transcript evals and peer traffic still invalidate and reorder its content,
   but only a human-facing reply deliberately moves focus to the transcript."
  [dbv agent-id]
  (or
    (ffirst
      (db/query
        {:seon.db/db dbv
         :seon.db/query
         '[:find (max ?tx)
           :in $ ?aid ?repl
           :where
           [?agent :seon.agent/id ?aid]
           [?user :seon.user/id "user"]
           [?message :seon.agent.message/from ?agent]
           [?message :seon.agent.message/to ?user]
           [?message :seon.agent.message/content _ ?tx]
           [?tx :seon.db/user ?author]
           [(= ?author ?agent)]
           [?tx :seon.db/process ?repl]]
         :seon.db/args [agent-id (repl-process-eid dbv)]}))
    0))

(defn- canvas-touch
  "Canvas recency from its explicit slot and its derived tile renderer."
  [dbv agent-id]
  (let [pinned (try
                 (some-> (db/pull dbv [:seon.render.canvas/content]
                                  [:seon.agent/id agent-id])
                         :seon.render.canvas/content
                         (#(db/decode-edn-value
                             :seon.render.canvas/content %)))
                 (catch :default _ nil))
        derived (when-not (symbol? pinned)
                  (::render-fns/tile-sym
                    (render-fns/last-updated-tile
                      {:seon.db/db dbv :seon.agent/id agent-id})))
        renderer (when (symbol? (or pinned derived)) (or pinned derived))]
    (max (agent-attr-touch dbv agent-id :seon.render.canvas/content)
         (renderer-touch dbv agent-id renderer))))

(defn- canvas-renderer
  "The symbolic renderer currently driving the canvas, when it has one."
  [dbv agent-id]
  (let [pinned (try
                 (some-> (db/pull dbv [:seon.render.canvas/content]
                                  [:seon.agent/id agent-id])
                         :seon.render.canvas/content
                         (#(db/decode-edn-value
                             :seon.render.canvas/content %)))
                 (catch :default _ nil))
        derived (when-not (symbol? pinned)
                  (::render-fns/tile-sym
                    (render-fns/last-updated-tile
                      {:seon.db/db dbv :seon.agent/id agent-id})))
        renderer (or pinned derived)]
    (when (symbol? renderer) renderer)))

(defn- renderer-attrs [dbv renderer]
  (if (symbol? renderer)
    (set (::render-fns/attrs
           (render-fns/renderer-read-attrs
             {:seon.db/db dbv :seon.render/html renderer})))
    #{}))

(def ^:private structural-attrs
  "Changes that can add/remove/rebind surfaces, requiring a shell morph."
  #{:seon.agent/ctx
    :seon.agent.ctx/name
    :seon.agent.ctx/priority
    :seon.render/html
    :seon.fn/sym
    :seon.fn/spec
    :seon.fn/ns})

(defn structural-change?
  "Whether changed attrs can add, remove, or rebind an agent-view surface."
  {:malli/schema [:=> [:catn [::changed-attrs ::changed-attrs]] :boolean]}
  [changed-attrs]
  (boolean (seq (set/intersection structural-attrs changed-attrs))))

(def ^:private header-attrs
  "Stored inputs that materially change the fleet status header.

   The datom inventory is intentionally sampled on these meaningful changes,
   not on every program-graph bookkeeping transaction."
  #{:seon.agent/id
    :seon.agent.run/agent
    :seon.agent.run/closed-at
    :seon.agent.run/closed-reason
    :seon.agent.run/paused-at
    :seon.agent.run/resumed-at
    :seon.agent.turn/at
    :seon.agent.turn/status
    :seon.agent.turn/llm-usage
    :seon.eval/id
    :seon.eval/ok?})

(defn agent-view-dependencies
  "Cached-gradient dependency projection for one live agent feed.

   Values come from the same database-owned context root and analyzer-produced
   renderer read-sets as rendering. The feed refreshes this projection after a
   structural change; ordinary transactions can then be classified without
   rendering or rebuilding the context graph."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]
                             [:seon.agent/id :string]]
                  ::dependencies]}
  [dbv agent-id]
  (let [root (agent-ctx/context-root
               {:seon.db/db dbv :seon.agent/id agent-id})
        context-attrs
        (into #{}
              (comp
                (filter #(contains? % :seon.render/html))
                (map #(or (::render-fns/fn-sym %)
                          (:seon.render/html %)))
                (mapcat #(cond-> (renderer-attrs dbv %)
                           (symbol? %)
                           (conj :seon.fn/source :seon.fn/read-attrs))))
              (:seon.agent.ctx/children root))
        canvas-renderer* (canvas-renderer dbv agent-id)
        canvas-attrs (cond-> (renderer-attrs dbv canvas-renderer*)
                       (symbol? canvas-renderer*)
                       (conj :seon.fn/source :seon.fn/read-attrs))]
    {::surface-attrs (into #{:seon.render.canvas/content}
                           (concat context-attrs canvas-attrs))
     ::structural-attrs structural-attrs
     ::header-attrs header-attrs}))

(defn- context-surface-specs
  "Unrendered HTML surface descriptors from the agent's context root."
  [dbv agent-id]
  (let [ctx  {:seon.db/db dbv :seon.agent/id agent-id}
        root (agent-ctx/context-root ctx)]
    (->> (:seon.agent.ctx/children root)
         (keep
           (fn [child]
             (when (contains? child :seon.render/html)
               (let [nm (:seon.agent.ctx/name child)
                     displayed-renderer (:seon.render/html child)
                     dependency-renderer (or (::render-fns/fn-sym child)
                                             displayed-renderer)]
                 {:seon.ui.surface/selection (selection-key nm)
                  :seon.ui.surface/label (name nm)
                  :seon.ui.surface/node child
                  :seon.ui.surface/root root
                  :seon.ui.surface/renderer displayed-renderer
                 :seon.ui.surface/read-attrs
                  (cond-> (renderer-attrs dbv dependency-renderer)
                    (symbol? dependency-renderer)
                    (conj :seon.fn/source :seon.fn/read-attrs))
                  :seon.ui.surface/touch
                  (renderer-touch dbv agent-id dependency-renderer)
                  :seon.ui.surface/focus-touch
                  (if (= nm :transcript)
                    (assistant-reply-touch dbv agent-id)
                    (renderer-touch dbv agent-id dependency-renderer))}))))
         vec)))

(defn- canvas-surface-spec [dbv agent-id]
  (let [renderer (canvas-renderer dbv agent-id)]
    {:seon.ui.surface/selection "canvas"
     :seon.ui.surface/label "canvas"
     :seon.ui.surface/renderer renderer
     :seon.ui.surface/read-attrs
     (cond-> (conj (renderer-attrs dbv renderer)
                   :seon.render.canvas/content)
       (symbol? renderer) (conj :seon.fn/source :seon.fn/read-attrs))
     :seon.ui.surface/touch (canvas-touch dbv agent-id)
     :seon.ui.surface/focus-touch (canvas-touch dbv agent-id)}))

(defn- surface-specs [dbv agent-id]
  (conj (context-surface-specs dbv agent-id)
        (canvas-surface-spec dbv agent-id)))

(defn- latest-focus
  "Most recently deliberately updated surface; canvas wins an untouched tie."
  [surfaces]
  (last
    (sort-by (juxt :seon.ui.surface/focus-touch
                   #(if (= "canvas" (:seon.ui.surface/selection %)) 1 0)
                   :seon.ui.surface/label)
             surfaces)))

(defn- render-surface
  "Render one descriptor once, then project its compact/expanded faces."
  [dbv agent-id spec]
  (let [selection (:seon.ui.surface/selection spec)
        h (if (= selection "canvas")
            (:seon.render/hiccup
              (render/render-agent-canvas
                {:seon.agent/id agent-id :seon.db/db dbv}))
            (let [root (:seon.ui.surface/root spec)
                  ctx  {:seon.db/db dbv
                        :seon.agent/id agent-id
                        :seon.agent/entity (:seon.agent/entity root)}]
              (render/render :seon.render/html ctx
                             (:seon.ui.surface/node spec))))
        h (if (= selection "canvas")
            (or h [:div {:class "p-2 text-text-500 text-xs"}
                   "No canvas render yet."])
            h)]
    (when h
      (assoc spec
             :seon.ui.surface/compact (face h :compact)
             :seon.ui.surface/expanded (face h :expanded)))))

(defn- focus-marker [selection touch]
  [:div {:id "agent-view-focus-revision"
         :style "display:none"
         :data-effect
         (str "if ($seenrevision !== " touch ") { "
              "if (!$manualselection) $selected = '" selection "'; "
              "$seenrevision = " touch " }")}])

(defn- surface-elements [surface]
  (let [selection (:seon.ui.surface/selection surface)
        label (:seon.ui.surface/label surface)
        touch (:seon.ui.surface/touch surface)]
    [(primary-panel selection (:seon.ui.surface/expanded surface) touch)
     (rail-button selection label (:seon.ui.surface/compact surface) touch)]))

(defn agent-view-changes
  "Complete ID-addressed elements affected by one coalesced transaction batch.

   Structural program/context changes return the full `#app-view`. Ordinary
   data changes render only intersecting surface read-sets plus the header and
   focus-revision controller. Unknown/unrelated attrs never rerender a surface."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]
                             [:seon.agent/id :string]
                             [::changed-attrs ::changed-attrs]]
                  [:vector :any]]}
  [dbv agent-id changed-attrs]
  (if (structural-change? changed-attrs)
    [(agent-view dbv agent-id)]
    (let [specs (surface-specs dbv agent-id)
          affected-specs
          (filterv #(seq (set/intersection
                           changed-attrs
                           (:seon.ui.surface/read-attrs %)))
                   specs)
          affected (mapv #(render-surface dbv agent-id %) affected-specs)
          latest (latest-focus specs)]
      ;; A formerly-present conditional renderer returning nil requires a shell
      ;; morph so Datastar removes both of its old faces. Ordinary updates stay
      ;; on the small, ID-addressed path.
      (if (some nil? affected)
        [(agent-view dbv agent-id)]
        (into (cond-> [(focus-marker (:seon.ui.surface/selection latest)
                                      (:seon.ui.surface/focus-touch latest))]
                (seq (set/intersection header-attrs changed-attrs))
                (conj (header/system-header dbv)))
              (mapcat surface-elements)
              affected)))))

(defn agent-view
  "Render one agent's canvas and HTML context blocks from `db`."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]
                             [:seon.agent/id :string]]
                  :any]}
  [db agent-id]
  (try
    (let [surfaces (->> (surface-specs db agent-id)
                        (keep #(render-surface db agent-id %))
                        (sort-by (juxt (comp - :seon.ui.surface/touch)
                                       :seon.ui.surface/label))
                        vec)
          latest (or (latest-focus surfaces)
                     {:seon.ui.surface/selection "canvas"
                      :seon.ui.surface/focus-touch 0})
          latest-selection (:seon.ui.surface/selection latest)
          latest-touch (:seon.ui.surface/focus-touch latest)]
      [:main {:id "app-view"
              :class "flex flex-col gap-2 w-full min-h-0 flex-1 overflow-hidden"
              :data-signals__ifmissing
              (str "{selected: '" latest-selection
                   "', seenrevision: " latest-touch
                   ", manualselection: false}")
              :data-effect (str "if ($selected !== 'canvas' && "
                                "!document.querySelector('[data-agent-primary=\"' + "
                                "$selected + '\"]')) { $selected = 'canvas'; "
                                "$manualselection = false }")}
       (header/system-header db)
       header/header-spacer
       (focus-marker latest-selection latest-touch)
       [:header {:id "agent-view-header"
                 :class "flex items-center justify-between border-b border-base-800 pb-1"}
        [:div {:class "flex items-center gap-2 min-w-0"}
         [:a {:href "/agents" :class "text-text-400 text-xs font-mono shrink-0"}
          "← all agents"]
         [:span {:class "text-text-500 text-2xs uppercase tracking-wider"} "agent"]
         [:span {:class "text-signal text-sm font-semibold font-mono truncate"} agent-id]
         [:a {:href (str "/agent/" agent-id "/debug")
              :class "text-text-500 hover:text-amber-300 text-2xs font-mono"}
          "debug"]]
        (status-chip db agent-id)]
       [:div {:id "agent-view-layout"
              :class "grid grid-cols-3 gap-2 min-h-0 flex-1"}
        [:div {:id "agent-view-primary"
               :class "col-span-2 min-h-0 h-full overflow-hidden"}
         (doall
           (map (fn [{selection :seon.ui.surface/selection
                      expanded :seon.ui.surface/expanded
                      touch :seon.ui.surface/touch}]
                  (primary-panel selection expanded touch))
                surfaces))]
        [:aside {:id "agent-view-context"
                 :class (str "agent-view-rail col-span-1 flex flex-col gap-2 "
                             "min-h-0 h-full overflow-y-auto")}
         (doall
           (map (fn [{selection :seon.ui.surface/selection
                      label :seon.ui.surface/label
                      compact :seon.ui.surface/compact
                      touch :seon.ui.surface/touch}]
                  (rail-button selection label compact touch))
                surfaces))]]])
    (catch :default e
      [:main {:id "app-view" :class "flex flex-col gap-3 w-full"}
       [:div {:id "agent-view-error" :class "text-error text-xs font-mono"}
        (str "render error: " (.-message e))]])))
