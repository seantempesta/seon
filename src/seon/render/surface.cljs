(ns seon.render.surface
  "The shared database-derived surface catalog, focus, and materializer.

   One frozen database value determines which surfaces an agent has, which
   deliberate update owns the shared focus, and the compact or expanded face
   of one selected surface. Agent pages, root fleet cards, and debug HTML
   twins all consume this namespace; none reconstruct focus or invoke a
   second preview path."
  (:require
    [clojure.string :as str]
    [malli.core :as m]
    [seon.agent.ctx :as agent-ctx]
    [seon.agent.ctx.render-fns :as render-fns]
    [seon.db :as db]
    [seon.error :as err]
    [seon.render :as render]
    [seon.schema :as schema]
    [seon.web.view-unit :as view-unit]))

(schema/register! ::selection [:string {:min 1}])
(schema/register! ::label [:string {:min 1}])
(schema/register! ::read-attrs [:set :qualified-keyword])
(schema/register! ::touch :int)
(schema/register! ::focus-touch :int)
(schema/register! ::surface
  [:map
   [::selection ::selection]
   [::label ::label]
   [:seon.agent.ctx/name {:optional true} :seon.agent.ctx/name]
   [::read-attrs ::read-attrs]
   [::touch ::touch]
   [::focus-touch ::focus-touch]])
(schema/register! ::surface-catalog [:vector {:min 1} ::surface])
(schema/register! ::face [:enum :compact :expanded])
(schema/register! ::compact :seon.render.canvas/hiccup)
(schema/register! ::expanded :seon.render.canvas/hiccup)
(schema/register! ::materialized-surface
  [:map
   [::selection ::selection]
   [::label ::label]
   [:seon.agent.ctx/name {:optional true} :seon.agent.ctx/name]
   [::read-attrs ::read-attrs]
   [::touch ::touch]
   [::focus-touch ::focus-touch]
   [:seon.db/read-observations :seon.db/read-observations]
   [::compact ::compact]
   [::expanded ::expanded]])
(schema/register! ::materialized-surfaces [:vector ::materialized-surface])
(schema/register! ::selections [:set ::selection])
(schema/register! ::materialize-surfaces-request
  [:map
   [:seon.db/db :seon.db/db-val]
   [:seon.agent/id :seon.agent/id]
   [::selections {:optional true} ::selections]])
(schema/register! ::materialize-surface-request
  [:map
   [:seon.db/db :seon.db/db-val]
   [:seon.agent/id :seon.agent/id]
   [::selection ::selection]
   [::face ::face]])
(schema/register! ::materialized-face
  [:maybe :seon.render.canvas/hiccup])

(defn selection-key
  "Stable browser selection key for one resolved context block."
  {:malli/schema [:=> [:catn [:seon.agent.ctx/name :seon.agent.ctx/name]]
                  ::selection]}
  [block-name]
  (str "context-"
       (view-unit/coordinate-token
         {:seon.agent.ctx/name block-name})))

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
  "Project an existing dual-face surface without invoking its renderer again."
  [hiccup view]
  (or (find-face hiccup (case view
                          :compact "seon-card-compact"
                          :expanded "seon-card-expanded"))
      hiccup))

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

(defn- conversation-touch
  "Latest human conversation message involving this agent."
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
           [?message :seon.agent.message/content _ ?tx]
           (or-join [?message ?agent ?user ?tx ?repl]
             (and
               [?message :seon.agent.message/from ?user]
               [?message :seon.agent.message/to ?agent])
             (and
               [?message :seon.agent.message/from ?agent]
               [?message :seon.agent.message/to ?user]
               [?tx :seon.db/user ?agent]
               [?tx :seon.db/process ?repl]))]
         :seon.db/args [agent-id (repl-process-eid dbv)]}))
    0))

(def ^:private literal-canvas
  "Marker for a valid literal canvas pin, which has no renderer read-set."
  ::literal-canvas)

(defn- pinned-canvas-renderer
  "The decoded pinned canvas renderer, literal marker, or nil when absent."
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

(defn- canvas-renderer
  "The symbolic renderer currently driving the canvas, when it has one."
  [dbv agent-id]
  (let [pinned (pinned-canvas-renderer dbv agent-id)
        derived (when (nil? pinned)
                  (::render-fns/surface-sym
                    (render-fns/last-updated-surface
                      {:seon.db/db dbv :seon.agent/id agent-id})))
        renderer (or pinned derived)]
    (when (symbol? renderer) renderer)))

(defn- canvas-touch-for-renderer
  "Canvas recency from its explicit slot and known symbolic renderer."
  [dbv agent-id renderer]
  (max (agent-attr-touch dbv agent-id :seon.render.canvas/content)
       (renderer-touch dbv agent-id renderer)))

(defn- renderer-attrs
  [dbv renderer]
  (if (symbol? renderer)
    (set (::render-fns/attrs
           (render-fns/renderer-read-attrs
             {:seon.db/db dbv :seon.render/html renderer})))
    #{}))

(defn- agent-present?
  "Whether the frozen database contains the requested agent identity."
  [dbv agent-id]
  (boolean
    (seq (db/query
           {:seon.db/db dbv
            :seon.db/query
            '[:find ?agent
              :in $ ?aid
              :where [?agent :seon.agent/id ?aid]]
            :seon.db/args [agent-id]}))))

(defn- known-renderer-by-block
  "Symbolic HTML renderers joined through the program graph, keyed by block."
  [dbv agent-id]
  (into {}
        (map (fn [[block-id renderer]]
               [block-id (symbol renderer)]))
        (db/query
          {:seon.db/db dbv
           :seon.db/query
           '[:find ?block ?renderer
             :in $ ?aid
             :where
             [?agent :seon.agent/id ?aid]
             [?agent :seon.agent/ctx ?block]
             [?block :seon.render/html ?renderer]
             [_ :seon.fn/sym ?renderer]]
           :seon.db/args [agent-id]})))

(defn- stored-renderer-symbols
  "Stored block-slot symbols projected by joining their program-graph rows."
  [dbv agent-id]
  (into #{}
        (map (comp symbol first))
        (db/query
          {:seon.db/db dbv
           :seon.db/query
           '[:find ?renderer
             :in $ ?aid [?slot ...]
             :where
             [?agent :seon.agent/id ?aid]
             [?agent :seon.agent/ctx ?block]
             [?block ?slot ?renderer]
             [_ :seon.fn/sym ?renderer]]
           :seon.db/args
           [agent-id [:seon.render/ai :seon.render/html]]})))

(defn- context-surface-metadata
  "Surface facts from a block name and its dependency renderer identity."
  [dbv agent-id block-name priority dependency-renderer]
  (let [touch (renderer-touch dbv agent-id dependency-renderer)]
    {::selection (selection-key block-name)
     ::label (name block-name)
     :seon.agent.ctx/name block-name
     :seon.agent.ctx/priority priority
     ::read-attrs
     (cond-> (renderer-attrs dbv dependency-renderer)
       (symbol? dependency-renderer)
       (conj :seon.fn/source :seon.fn/read-attrs))
     ::touch touch
     ::focus-touch
     (if (= block-name :transcript)
       (conversation-touch dbv agent-id)
       touch)}))

(defn- context-surface-sources
  "Unrendered HTML surface sources from the agent's context root."
  [dbv agent-id]
  (let [ctx  {:seon.db/db dbv :seon.agent/id agent-id}
        root (agent-ctx/context-root ctx)]
    (->> (:seon.agent.ctx/children root)
         (keep
           (fn [child]
             (when (contains? child :seon.render/html)
               (let [dependency-renderer
                     (or (::render-fns/fn-sym child)
                         (:seon.render/html child))]
                 (assoc
                   (context-surface-metadata
                     dbv agent-id
                     (:seon.agent.ctx/name child)
                     (:seon.agent.ctx/priority child)
                     dependency-renderer)
                   ::node child
                   ::root root)))))
         vec)))

(defn- stored-context-surface-metadata
  "Stored HTML block facts without projecting or decoding either render body."
  [dbv agent-id]
  (let [renderer-by-block (known-renderer-by-block dbv agent-id)]
    (->> (db/query
           {:seon.db/db dbv
            :seon.db/query
            '[:find ?block ?name ?priority
              :in $ ?aid
              :where
              [?agent :seon.agent/id ?aid]
              [?agent :seon.agent/ctx ?block]
              [?block :seon.agent.ctx/name ?name]
              [?block :seon.agent.ctx/priority ?priority]
              [?block :seon.render/html]]
            :seon.db/args [agent-id]})
         (sort-by (juxt #(nth % 2) (comp str second)))
         (mapv (fn [[block-id block-name priority]]
                 (context-surface-metadata
                   dbv agent-id block-name priority
                   (get renderer-by-block block-id)))))))

(defn- derived-context-surface-metadata
  "Auto-run HTML surface facts from program metadata, never renderer output."
  [dbv agent-id pinned-renderers]
  (let [current-ns (some-> (agent-ctx/current-ns
                             {:seon.db/db dbv :seon.agent/id agent-id})
                           name keyword)]
    (->> (render-fns/derived-blocks
           (cond-> {:seon.db/db dbv
                    ::render-fns/pinned-syms pinned-renderers}
             current-ns (assoc ::render-fns/current-ns current-ns)))
         (keep (fn [child]
                 (when (contains? child :seon.render/html)
                   (context-surface-metadata
                     dbv agent-id
                     (:seon.agent.ctx/name child)
                     (:seon.agent.ctx/priority child)
                     (::render-fns/fn-sym child)))))
         vec)))

(defn- canvas-surface-source
  [dbv agent-id]
  (let [renderer (canvas-renderer dbv agent-id)
        touch (canvas-touch-for-renderer dbv agent-id renderer)]
    {::selection "canvas"
     ::label "canvas"
     ::read-attrs
     (cond-> (conj (renderer-attrs dbv renderer)
                   :seon.render.canvas/content)
       (symbol? renderer) (conj :seon.fn/source :seon.fn/read-attrs))
     ::touch touch
     ::focus-touch touch}))

(defn- catalog-surface-sources
  "Metadata-only surface discovery for one present or missing agent."
  [dbv agent-id]
  (if-not (agent-present? dbv agent-id)
    [(let [touch (canvas-touch-for-renderer dbv agent-id nil)]
       {::selection "canvas"
        ::label "canvas"
        ::read-attrs #{:seon.render.canvas/content}
        ::touch touch
        ::focus-touch touch})]
    (let [canvas-renderer* (canvas-renderer dbv agent-id)
          pinned-renderers (cond-> (stored-renderer-symbols dbv agent-id)
                             (symbol? canvas-renderer*)
                             (conj canvas-renderer*))
          contexts (->> (concat
                          (stored-context-surface-metadata dbv agent-id)
                          (derived-context-surface-metadata
                            dbv agent-id pinned-renderers))
                        (sort-by (juxt :seon.agent.ctx/priority
                                       (comp str :seon.agent.ctx/name)))
                        vec)
          canvas-touch* (canvas-touch-for-renderer
                          dbv agent-id canvas-renderer*)
          canvas {::selection "canvas"
                  ::label "canvas"
                  ::read-attrs
                  (cond-> (conj (renderer-attrs dbv canvas-renderer*)
                                :seon.render.canvas/content)
                    (symbol? canvas-renderer*)
                    (conj :seon.fn/source :seon.fn/read-attrs))
                  ::touch canvas-touch*
                  ::focus-touch canvas-touch*}]
      (conj contexts canvas))))

(defn- surface-sources
  [dbv agent-id]
  (conj (context-surface-sources dbv agent-id)
        (canvas-surface-source dbv agent-id)))

(def ^:private catalog-keys
  #{::selection ::label :seon.agent.ctx/name
    ::read-attrs ::touch ::focus-touch})

(defn- catalog-from-sources
  "Public surface facts projected away from their private render sources."
  [sources]
  (mapv #(select-keys % catalog-keys) sources))

(defn surface-catalog
  "Cheap surface facts for one agent without invoking content renderers."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]
                             [:seon.agent/id :seon.agent/id]]
                  ::surface-catalog]}
  [dbv agent-id]
  (catalog-from-sources (catalog-surface-sources dbv agent-id)))

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
  "Current render source selected without deriving unrelated metadata."
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
                  ::materialized-face]}
  [{dbv :seon.db/db agent-id :seon.agent/id
    selection ::selection requested-face ::face}]
  (when (agent-present? dbv agent-id)
    (when-let [source (current-surface-source dbv agent-id selection)]
      (some-> (render-surface-hiccup dbv agent-id source)
              (face requested-face)))))

(defn- render-surface
  "Render one source once, then project its compact and expanded faces."
  [dbv agent-id source]
  (let [selection (::selection source)
        capture (db/capture-reads
                  {:seon.db/db dbv
                   :seon.db/thunk #(render-surface-hiccup dbv agent-id source)})
        rendered (:seon.db/result capture)
        rendered (if (= selection "canvas")
                   (or rendered [:div {:class "p-2 text-text-500 text-xs"}
                                 "No canvas render yet."])
                   rendered)]
    (when rendered
      (assoc (select-keys source catalog-keys)
             :seon.db/read-observations
             (:seon.db/read-observations capture)
             ::compact (face rendered :compact)
             ::expanded (face rendered :expanded)))))

(defn materialize-surfaces
  "Materialize selected surfaces once each, projecting both faces."
  {:malli/schema [:=> [:cat ::materialize-surfaces-request]
                  ::materialized-surfaces]}
  [{dbv :seon.db/db agent-id :seon.agent/id selections ::selections}]
  (if-not (agent-present? dbv agent-id)
    []
    (->> (surface-sources dbv agent-id)
         (filter #(or (nil? selections)
                      (contains? selections (::selection %))))
         (keep #(render-surface dbv agent-id %))
         vec)))
