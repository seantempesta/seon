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
    [malli.core :as m]
    [seon.agent.ctx :as agent-ctx]
    [seon.agent.ctx.render-fns :as render-fns]
    [seon.db :as db]
    [seon.derive :as derive]
    [seon.error :as err]
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
(schema/register! ::selection [:string {:min 1}])
(schema/register! ::label [:string {:min 1}])
(schema/register! ::read-attrs [:set :qualified-keyword])
(schema/register! ::touch :int)
(schema/register! ::focus-touch :int)
(schema/register! ::surface
  [:map
   [::selection ::selection]
   [::label ::label]
   [::read-attrs ::read-attrs]
   [::touch ::touch]
   [::focus-touch ::focus-touch]])
(schema/register! ::surface-catalog [:vector {:min 1} ::surface])
(schema/register! ::face [:enum :compact :expanded])
(schema/register! ::materialize-surface-request
  [:map
   [:seon.db/db :seon.db/db-val]
   [:seon.agent/id :string]
   [::selection ::selection]
   [::face ::face]])
(schema/register! ::materialized-surface
  [:or [:enum nil] :seon.render.canvas/hiccup])

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

(def ^:private literal-canvas
  "Marker for a valid literal canvas pin, which has no renderer read-set."
  ::literal-canvas)

(defn- pinned-canvas-renderer
  "The decoded pinned canvas renderer, literal marker, or nil when absent.

   Uses a missing-safe query rather than catching lookup/decode failures. A
   malformed persisted value is a core database invariant failure: record it
   and throw so dependency routing cannot silently stop updating the canvas."
  [dbv agent-id]
  (let [stored
        (ffirst
          (db/query
            {:seon.db/db dbv
             :seon.db/query
             '[:find ?content
               :in $ ?aid
               :where
               [?agent :seon.agent/id ?aid]
               [?agent :seon.render.canvas/content ?content]]
             :seon.db/args [agent-id]}))
        pinned (when (some? stored)
                 (db/decode-edn-value :seon.render.canvas/content stored))]
    (cond
      (nil? stored) nil
      (symbol? pinned) pinned
      (m/validate :seon.render.canvas/content pinned) literal-canvas
      :else
      (let [e (ex-info
                (str "Malformed :seon.render.canvas/content for agent "
                     agent-id ": " (pr-str pinned))
                {:seon.agent/id agent-id
                 :seon.render.canvas/content pinned
                 :seon.error/kind :core-bug})]
        (err/record! {:seon.error/raw e :seon.error/fault :core})
        (throw e)))))

(defn- canvas-touch
  "Canvas recency from its explicit slot and its derived tile renderer."
  [dbv agent-id]
  (let [pinned (pinned-canvas-renderer dbv agent-id)
        derived (when (nil? pinned)
                  (::render-fns/tile-sym
                    (render-fns/last-updated-tile
                      {:seon.db/db dbv :seon.agent/id agent-id})))
        renderer (when (symbol? (or pinned derived)) (or pinned derived))]
    (max (agent-attr-touch dbv agent-id :seon.render.canvas/content)
         (renderer-touch dbv agent-id renderer))))

(defn- canvas-renderer
  "The symbolic renderer currently driving the canvas, when it has one."
  [dbv agent-id]
  (let [pinned (pinned-canvas-renderer dbv agent-id)
        derived (when (nil? pinned)
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

(defn- context-surface-sources
  "Unrendered HTML surface sources from the agent's context root."
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
                 {::selection (selection-key nm)
                  ::label (name nm)
                  ::node child
                  ::root root
                  ::read-attrs
                  (cond-> (renderer-attrs dbv dependency-renderer)
                    (symbol? dependency-renderer)
                    (conj :seon.fn/source :seon.fn/read-attrs))
                  ::touch
                  (renderer-touch dbv agent-id dependency-renderer)
                  ::focus-touch
                  (if (= nm :transcript)
                    (assistant-reply-touch dbv agent-id)
                    (renderer-touch dbv agent-id dependency-renderer))}))))
         vec)))

(defn- canvas-surface-source [dbv agent-id]
  (let [renderer (canvas-renderer dbv agent-id)
        touch (canvas-touch dbv agent-id)]
    {::selection "canvas"
     ::label "canvas"
     ::read-attrs
     (cond-> (conj (renderer-attrs dbv renderer)
                   :seon.render.canvas/content)
       (symbol? renderer) (conj :seon.fn/source :seon.fn/read-attrs))
     ::touch touch
     ::focus-touch touch}))

(defn- surface-sources [dbv agent-id]
  (conj (context-surface-sources dbv agent-id)
        (canvas-surface-source dbv agent-id)))

(def ^:private catalog-keys
  #{::selection ::label ::read-attrs ::touch ::focus-touch})

(defn- catalog-from-sources
  "Public surface facts projected away from their private render sources."
  [sources]
  (mapv #(select-keys % catalog-keys) sources))

(defn surface-catalog
  "Cheap surface facts for one agent without invoking content renderers."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]
                             [:seon.agent/id :string]]
                  ::surface-catalog]}
  [dbv agent-id]
  (catalog-from-sources (surface-sources dbv agent-id)))

(defn- latest-focus-surface
  "Most recently deliberately updated surface; canvas wins an untouched tie."
  [surfaces]
  (last
    (sort-by (juxt ::focus-touch
                   #(if (= "canvas" (::selection %)) 1 0)
                   ::label)
             surfaces)))

(defn latest-focus-selection
  "Selection of the most recently deliberately updated catalog surface."
  {:malli/schema [:=> [:catn [::surface-catalog ::surface-catalog]]
                  ::selection]}
  [catalog]
  (::selection (latest-focus-surface catalog)))

(defn- render-surface-hiccup
  "Invoke one current surface source exactly once, returning raw hiccup."
  [dbv agent-id source]
  (let [selection (::selection source)]
    (if (= selection "canvas")
      (:seon.render/hiccup
        (render/render-agent-canvas
          {:seon.agent/id agent-id :seon.db/db dbv}))
      (let [root (::root source)
            ctx  {:seon.db/db dbv
                  :seon.agent/id agent-id
                  :seon.agent/entity (:seon.agent/entity root)}]
        (render/render :seon.render/html ctx (::node source))))))

(defn- current-surface-source
  "Current render source selected without deriving unrelated surface metadata."
  [dbv agent-id selection]
  (if (= selection "canvas")
    {::selection "canvas"}
    (let [root (agent-ctx/context-root
                 {:seon.db/db dbv :seon.agent/id agent-id})]
      (some (fn [child]
              (when (and (contains? child :seon.render/html)
                         (= selection
                            (selection-key (:seon.agent.ctx/name child))))
                {::selection selection ::node child ::root root}))
            (:seon.agent.ctx/children root)))))

(defn materialize-surface
  "Materialize one current surface face, or nil when it is unavailable."
  {:malli/schema [:=> [:cat ::materialize-surface-request]
                  ::materialized-surface]}
  [{dbv :seon.db/db agent-id :seon.agent/id
    selection ::selection requested-face ::face}]
  (when-let [source (current-surface-source dbv agent-id selection)]
    (some-> (render-surface-hiccup dbv agent-id source)
            (face requested-face))))

(defn- render-surface
  "Render one source once, then project its compact and expanded faces."
  [dbv agent-id source]
  (let [selection (::selection source)
        rendered (render-surface-hiccup dbv agent-id source)
        rendered (if (= selection "canvas")
                   (or rendered [:div {:class "p-2 text-text-500 text-xs"}
                                 "No canvas render yet."])
                   rendered)]
    (when rendered
      (assoc source
             ::compact (face rendered :compact)
             ::expanded (face rendered :expanded)))))

(defn- focus-marker [selection touch]
  [:div {:id "agent-view-focus-revision"
         :style "display:none"
         :data-effect
         (str "if ($seenrevision !== " touch ") { "
              "if (!$manualselection) $selected = '" selection "'; "
              "$seenrevision = " touch " }")}])

(defn- surface-elements [surface]
  (let [selection (::selection surface)
        label (::label surface)
        touch (::touch surface)]
    [(primary-panel selection (::expanded surface) touch)
     (rail-button selection label (::compact surface) touch)]))

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
    (let [sources (surface-sources dbv agent-id)
          catalog (catalog-from-sources sources)
          affected-sources
          (filterv #(seq (set/intersection
                           changed-attrs
                           (::read-attrs %)))
                   sources)
          affected (mapv #(render-surface dbv agent-id %) affected-sources)
          latest-selection (latest-focus-selection catalog)
          latest (some #(when (= latest-selection (::selection %)) %) catalog)]
      ;; A formerly-present conditional renderer returning nil requires a shell
      ;; morph so Datastar removes both of its old faces. Ordinary updates stay
      ;; on the small, ID-addressed path.
      (if (some nil? affected)
        [(agent-view dbv agent-id)]
        (into (cond-> [(focus-marker (::selection latest)
                                      (::focus-touch latest))]
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
    (let [sources (surface-sources db agent-id)
          catalog (catalog-from-sources sources)
          surfaces (->> sources
                        (keep #(render-surface db agent-id %))
                        (sort-by (juxt (comp - ::touch)
                                       ::label))
                        vec)
          present-selections (into #{} (map ::selection) surfaces)
          present-catalog (filterv #(contains? present-selections
                                               (::selection %))
                                   catalog)
          latest-selection (latest-focus-selection present-catalog)
          latest (some #(when (= latest-selection (::selection %)) %)
                       present-catalog)
          latest-touch (::focus-touch latest)]
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
           (map (fn [{selection ::selection
                      expanded ::expanded
                      touch ::touch}]
                  (primary-panel selection expanded touch))
                surfaces))]
        [:aside {:id "agent-view-context"
                 :class (str "agent-view-rail col-span-1 flex flex-col gap-2 "
                             "min-h-0 h-full overflow-y-auto")}
         (doall
           (map (fn [{selection ::selection
                      label ::label
                      compact ::compact
                      touch ::touch}]
                  (rail-button selection label compact touch))
                surfaces))]]])
    (catch :default e
      [:main {:id "app-view" :class "flex flex-col gap-3 w-full"}
       [:div {:id "agent-view-error" :class "text-error text-xs font-mono"}
        (str "render error: " (.-message e))]])))
