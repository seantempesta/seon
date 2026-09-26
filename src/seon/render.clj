(ns seon.render
  "The one typed projection selector and guarded SCI invocation boundary.

  AI and HTML each follow one chain: the value's declared identity (its
  error operation's returns composed with the error base pair, the root
  call's contracted returns, its owning row schema, or its attribute's named
  schema), then the prepared value floor. Zero matches is ordinary. Multiple matches are one deterministic flat
  error. Selected failures do not fall through to another producer.

  Every selected qualified symbol resolves to the live SCI Var in the cluster
  context and executes through `seon.sci.kernel`; there is no compiled renderer
  lane. A redefinition therefore changes the next call and a cold context
  re-derives the same symbol from its database program row."
  (:require [seon.error.refusal]
            [clojure.string]
            [datahike.db :as datahike.db]
            [malli.core :as m]
            [sci.core :as sci]
            [seon.ai.tokens :as tokens]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.id :as id]
            [seon.error :as error]
            [seon.render.hiccup :as hiccup]
            [seon.render.value :as render.value]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [malli.registry :as mr]
            [seon.schema.internal :as internal]
            [seon.sci.admit :as admit]
            [seon.sci.kernel :as sci.kernel])
  (:import [java.util Date]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; LOAD-CYCLE BOUNDARIES. `seon.render.web`, `seon.turn` and
;;; `seon.render.walk` all require `seon.render`, so this namespace cannot
;;; require them back. One resolution per var, realized at first use, instead
;;; of a `requiring-resolve` on every call (AGENTS §2.1).
(defonce ^:private render-web-derive-context!
  (delay (requiring-resolve 'seon.render.web/derive-context!)))
(defonce ^:private turn-opening-db
  (delay (requiring-resolve 'seon.turn/opening-db)))
(defonce ^:private render-walk-neighborhood
  (delay (requiring-resolve 'seon.render.walk/neighborhood)))


;;; ---------------------------------------------------------------------------
;;; Contract-derived renderer selection and guarded invocation
;;; ---------------------------------------------------------------------------

(defn- render-value
  [request]
  (get request :seon.render/value request))

(defn agent-render-profile
  "Derive the agent generic-value fit profile from effective config facts."
  {:malli/schema
   [:=> [:cat [:or :seon.config/effective
                :seon.config/missing-effective-error
                :seon.error/value]]
    [:or :seon.render.profile/profile
     :seon.config/missing-effective-error
     :seon.config/error :seon.db/invalid-read-error :seon.schema/validation-refusal]]}
  [effective]
  ;; A declared config or read refusal is returned unchanged. A profile built from a
  ;; refusal would carry nil budgets and every downstream render would then
  ;; read that absence as a policy (AGENTS.md section 2.4).
  (if (or (:seon.config/error-key effective)
          (:seon.config/missing-effective effective)
          (:seon.db/invalid-read effective)
          (:seon.schema/expected-value effective))
    effective
    (cond->
     {:seon.render.profile/id :seon.render.profile/agent
      :seon.render.profile/token-budget
      (:seon.config.render.agent/token-budget effective)
      :seon.render.profile/max-depth
      (:seon.config.render.agent/max-depth effective)
      :seon.render.profile/max-children
      (:seon.config.render.agent/max-children effective)
      :seon.render.profile/composition
      (:seon.config.render.agent/composition effective)}
      ;; The provenance ceiling (the floor's selection); absent before a store
      ;; adopts the dial, and then fit uses its declared default.
      (:seon.print/rendered-token-ceiling effective)
      (assoc :seon.print/rendered-token-ceiling
             (:seon.print/rendered-token-ceiling effective)))))

(def ^:private default-agent-profile
  (delay (agent-render-profile config/defaults)))

(defn request-projection
  "The schema projection one render request asks its selection questions of.

  PRECEDENCE: a projection supplied on the request first — an evaluation
  hands the one it evaluated under, so its contract and its rendered
  declarations come from one value — then the projection CARRIED BY the
  request's database value (`seon.db/carried-projection`), and only when
  neither is present the acquired SCI context's projection.

  Selection asked the ctx alone until 2026-09-16, which is that precedence
  inverted: the value being rendered was pulled from the database value, and
  every declaration question — which shapes it matches, whether a producer's
  output satisfies the output schema — was answered by a copy held somewhere
  else. It also made \"what would this render as under projection P\"
  unanswerable without mutating shared ctx state. A request with no database,
  no supplied projection and no ctx has nothing declared, and nothing is
  selected. A database that is a refused read is not an absent one: it
  throws that error as its `ex-data` rather than rendering against another
  projection."
  {:malli/schema [:=> [:cat :map] [:maybe :seon.schema/projection]]}
  [request]
  (let [database (:seon.db/db request)]
    (cond
      (:seon.error/at database)
      (throw (ex-info (:seon.error/message database "The render request's database is a refused read.")
                      database))
      :else (or (:seon.schema/projection request)
                (some-> database db/carried-projection)
                (some-> (:seon.sci.eval/ctx request) sci.kernel/context-projection)))))

(defn request-profile
  "Return the profile carried by one request, deriving it once when absent.
  A carried refusal is returned as this member's refusal; a carried value
  that is neither is the caller's mistake, refused at this entry by the
  member that carried it."
  {:malli/schema [:=> [:cat [:map [:seon.render/profile {:optional true}
                                   [:or :seon.render.profile/profile
                                    :seon.error/value]]]]
                  [:or :seon.render.profile/profile :seon.render/request-error]]}
  [request]
  (if-let [carried (:seon.render/profile request)]
    (cond-> carried
      (and (:seon.error/at carried) (not (:seon.render/refused-member carried)))
      (assoc :seon.render/refused-member :seon.render/profile))
      (if-let [projection (or (:seon.schema/projection request)
                              (some-> (:seon.db/db request) db/carried-projection)
                              (schema/handed-projection))]
        ;; The caller names the cluster, on the request or through its
        ;; context's environment: a forked branch carries its parent's
        ;; cluster rows, so no row can.
        (let [database (:seon.db/db request)
              cluster-name (or (:seon.boot/cluster-name request)
                               (:seon.boot/cluster-name (env/of (:seon.sci.eval/ctx request))))
              effective (cond
                          (nil? database) nil
                          (nil? cluster-name)
                          (seon.error.refusal/diagnostic (java.util.Date.) :seon.render/render 'seon.render/request-profile
                           {:seon.error/message "Rendering a database value requires a carried profile or a context whose environment names the cluster."
                            :seon.error/fix "Carry the caller's context or its render profile."
                            :seon.error/member :seon.boot/cluster-name
                            :seon.error/expected :seon.boot/cluster-name
                            :seon.error/offending (vec (sort (keys request)))})
                          :else (schema/call-with-projection
                                 projection #(config/effective database cluster-name)))]
          (cond
            (nil? effective) @default-agent-profile
            (:seon.error/at effective) (assoc effective :seon.render/refused-member :seon.render/profile)
            :else (agent-render-profile effective)))
        (let [observation
              (seon.error.refusal/diagnostic (java.util.Date.) :seon.render/render 'seon.render/request-profile
               {:seon.error/message "Rendering requires a carried profile or handed projection."
               :seon.error/fix "Supply the expected member and repeat the requested operation."
               :seon.render/refused-member :seon.schema/projection
               :seon.error/expected [:or :seon.render/profile :seon.schema/handed-projection]
               ;; the members the request HAS, never the request (it carries its world)
               :seon.error/offending (vec (sort (keys request)))})]
          observation))))

(defn- target-profile

  {:malli/schema [:=> [:cat :map] [:or :seon.render.profile/profile :seon.render/request-error]]}
  [request]
  (let [value (render-value request)
        database (:seon.db/db request)
        identity-requery
        (when (and database (map? value))
          (some (fn [attribute]
                  (when-let [entry (find value attribute)]
                    [attribute (val entry)]))
                (db/identity-attributes database)))]
    (cond-> (request-profile request)
    identity-requery
    (assoc :seon.print/requery-id identity-requery)

    (and (nil? identity-requery)
         (:seon.render.call/id request))
    (assoc :seon.print/requery-id
           [:seon.render.call/id (:seon.render.call/id request)])

    (and (nil? identity-requery)
         (nil? (:seon.render.call/id request)))
    (assoc :seon.print/requery-refusal
           "The rendered value has no stable requery identity."))))

(defn- render-argument
  [request]
  ;; The render boundary holds the database, so it is the CALLER of each
  ;; render function and passes it in the unit; caller arguments win over a
  ;; supplied default (root ruling 2026-09-25).
  (let [context (select-keys request
                             [:seon.db/db
                              :seon.sci.eval/ctx
                              :seon.agent/id
                              :seon.turn/id
                              :seon.render.call/id
                              :seon.sci.admit/caps
                              :seon.sci.eval/time-limit-ms
                              :seon.config/on-core-error
                              :seon.db/connection
                              :seon.render.data/total
                              :seon.render/distance
                              :seon.render/profile
                              :seon.render.value/root
                              :seon.render.data/cursor
                              :seon.render.walk/attribute
                              :seon.ai/partial
                              ;; the producers already rendering this
                              ;; chain — carried so a producer that
                              ;; delegates its own value onward cannot
                              ;; be selected for it a second time
                              :seon.render/rendering])
        profile (target-profile request)
        value (render-value request)
        context (cond-> context
                  profile (assoc :seon.render/profile profile)
                  (and (counted? value)
                       (not (contains? context :seon.render.data/total)))
                  (assoc :seon.render.data/total (count value)))]
    ;; The floor unit and the value are different data. A floor unit carries
    ;; only qualified render inputs; an arbitrary map remains wholly under
    ;; `:seon.render/value`. Merging a value into this unit made ordinary maps
    ;; with unqualified keys unconstructable at the one total floor.
    (assoc context :seon.render/value value)))

(defn- producer-argument
  [request]
  ;; Existing declared producers accept the qualified attributes of the value
  ;; they render together with render custody. Keep that established contract
  ;; for producer selection and invocation. The universal floor is the sole
  ;; exception below: arbitrary value keys never become its unit keys.
  (let [argument (render-argument request)
        value (:seon.render/value argument)
        ;; Transaction shape is for schema selection. Invocation preserves
        ;; the pulled value and the names reached through its refs.
        producer-value value]
    (if (map? value)
      (assoc (merge (dissoc argument :seon.render/value
                           :seon.render.call/id)
                    producer-value)
             :seon.render/value value)
      (dissoc argument :seon.render.call/id))))

(defn- floor-producer?
  [selected]
  (contains? #{'seon.render.value/render-ai
               'seon.render.value/render-html}
             selected))

(defn- source-return?
  [output]
  (letfn [(branches [output]
            (case output
              (:seon.render/source :seon.render/source-blocks) #{:source}
              (:nil :seon.error/value) #{}
              (case (when (vector? output) (first output))
                (:maybe :or) (into #{} (mapcat branches)
                                   (cond-> (rest output)
                                     (map? (second output)) rest))
                #{:other})))]
    (= #{:source} (branches output))))

(defn- source-producer?
  "Read source intent from input-compatible indexed return contracts."
  [projection selected arguments]
  (let [outputs (schema/function-matching-outputs-in
                 projection selected arguments)]
    (and (seq outputs) (every? source-return? outputs))))

(defn- ambiguity

  {:malli/schema [:=> [:cat [:maybe :seon.render/namespace] :seon.render/output [:sequential :qualified-symbol]] :seon.render/ambiguous-error]}
  [namespace-name output candidate-symbols]
  (let [observation
        (seon.error.refusal/diagnostic (java.util.Date.) :seon.render/render 'seon.render/ambiguity
         {:seon.error/message (str "More than one function in " namespace-name
                             " accepts this value and returns " output ".")
         :seon.error/fix "Supply the expected member and repeat the requested operation."
         :seon.render/candidates (vec candidate-symbols)
         :seon.error/member :seon.render/output
         :seon.error/expected "one applicable renderer"
         :seon.error/offending candidate-symbols
         :seon.error/data {:seon.render/namespace namespace-name
                           :seon.render/candidates (vec candidate-symbols)
                           :seon.render/output output}})]
    observation))

(defn transacted
  "Restore a pulled entity to the transaction shape used for selection."
  {:malli/schema [:=> [:cat :map] :map]}
  [entity]
  (render.value/transacted entity))

(defn- entity-owners
  "Each identity attribute's declared `:seon.program/row-schema`, and each
  such row schema's owned keys (its entries plus `:db/id`), once per projection."
  {:malli/schema [:=> [:cat :seon.schema/projection]
                  [:map [::identity-rows [:map-of :qualified-keyword :qualified-keyword]]
                   [::owned-keys [:map-of :qualified-keyword [:set :keyword]]]]]}
  [projection]
  (schema/projection-cache-value
   projection ::entity-owners
   (fn []
     (let [registry (:seon.schema.projection/registry projection)
           rows (into {} (keep (fn [attribute]
                                 (when-let [row (some-> (mr/schema registry attribute)
                                                        m/properties :seon.program/row-schema)]
                                   [attribute row])))
                      (keys (:seon.schema.projection/forms projection)))]
       {::identity-rows rows
        ::owned-keys (into {} (keep (fn [row]
                                      (when-let [definition (mr/schema registry row)]
                                        [row (into #{:db/id} (map first)
                                                   (internal/entity-entries definition))])))
                           (set (vals rows)))}))))

(defn- entity-owner
  "The one row schema owning map `value`: every identity attribute present
  names it and every present key is its entry or `:db/id`; else nil."
  {:malli/schema [:=> [:cat :seon.schema/projection :map] [:maybe :qualified-keyword]]}
  [projection value]
  (let [{rows ::identity-rows owned ::owned-keys} (entity-owners projection)
        named (into #{} (keep rows) (keys value))
        row (first named)]
    ;; A value's inline face keys are render data, not entity attributes.
    (when (and (= 1 (count named))
               (every? #(or ((get owned row #{}) %) (#{:seon.render/ai :seon.render/html} %)) (keys value)))
      row)))

(defn- named-alternatives
  "The named schemas `form` admits, flattened through `:or`/`:maybe`/`:and`
  and named unions, without `:seon.error/base`."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.schema/value] [:vector :qualified-keyword]]}
  [projection form]
  (let [forms (:seon.schema.projection/forms projection)
        branches (fn branches [seen form]
                   (cond
                     (and (qualified-keyword? form) (not (seen form)))
                     (let [named (get forms form)]
                       (cons form (when (and (vector? named) (#{:or :maybe} (first named)))
                                    (branches (conj seen form) named))))
                     (and (vector? form) (#{:or :maybe :and} (first form)))
                     (mapcat #(branches seen %) (remove map? (rest form)))))]
    (into [] (comp (remove #{:seon.error/base}) (distinct)) (branches #{} form))))

(defn- output-alternatives
  "The named schemas one contracted function declares it returns, once per
  projection: every arity's output's named alternatives. Nil for no contract."
  {:malli/schema [:=> [:cat :seon.schema/projection :qualified-symbol]
                  [:maybe [:vector :qualified-keyword]]]}
  [projection function-symbol]
  (schema/projection-cache-value
   projection [::output-alternatives function-symbol]
   (fn []
     (some->> (mr/schema (:seon.schema.projection/registry projection) function-symbol)
              m/-function-schema-arities
              (mapcat #(named-alternatives projection (m/form (:output (m/-function-info %)))))
              distinct
              vec))))

(defn- satisfied-alternatives
  "The declared return alternatives of `function-symbol` that `value`
  satisfies, by each schema's cached Malli validator. Nil for no contract."
  {:malli/schema [:=> [:cat :seon.schema/projection :qualified-symbol :seon.schema/value]
                  [:maybe [:vector :qualified-keyword]]]}
  [projection function-symbol value]
  (some->> (output-alternatives projection function-symbol)
           (filterv #((schema/projection-validator projection %) value))))

(defn- attribute-identity
  "The named schema a registered attribute gives the value at `path`: its
  declared component schema, else its named reference (or `:maybe` of one),
  else itself when it is an entity schema; an element of a collection
  attribute gets its `:vector`/`:set`/`:sequential`/`:maybe` element's name."
  {:malli/schema [:=> [:cat :seon.schema/projection [:maybe [:vector :seon.schema/value]]]
                  [:maybe :qualified-keyword]]}
  [projection path]
  (let [registry (:seon.schema.projection/registry projection)
        tail (peek path)
        parent (when (next path) (peek (pop path)))
        [attribute element?] (cond (qualified-keyword? tail) [tail false]
                                   (qualified-keyword? parent) [parent true])
        definition (some->> attribute (mr/schema registry))
        form (get (:seon.schema.projection/forms projection) attribute)
        element (fn [form] (when (and (vector? form) (#{:vector :set :sequential :maybe} (first form))
                                      (qualified-keyword? (peek form)))
                             (peek form)))]
    (when definition
      (or (:seon.db/component-schema (m/properties definition))
          (if element?
            (element form)
            (cond (qualified-keyword? form) form
                  (and (vector? form) (= :maybe (first form))) (element form)
                  (internal/entity-schema? definition) attribute))))))

(defn- error-alternatives
  "The alternatives an error value satisfies among its own operation's
  declared returns (`:seon.error/operation`); nil when that is uncontracted."
  {:malli/schema [:=> [:cat :seon.schema/projection :map] [:maybe [:vector :qualified-keyword]]]}
  [projection value]
  (let [operation (:seon.error/operation value)]
    (when (qualified-symbol? operation)
      (satisfied-alternatives projection operation value))))

(defn head-function
  "The contracted function a top-level call form names: its head symbol
  resolved in `namespace-name` of `ctx`. Macros, locals, literals and interop
  name no producer, so they answer nil."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.sci.eval/ctx :symbol :seon.schema/value]
                  [:maybe :qualified-symbol]]}
  [projection ctx namespace-name form]
  (when (and (seq? form) (symbol? (first form)))
    (when-let [namespace-object (sci/find-ns ctx namespace-name)]
      (let [resolved (sci/binding [sci/ns namespace-object]
                       (sci/resolve ctx (first form)))
            function-symbol (when (and (instance? sci.lang.Var resolved)
                                       (not (:macro (meta resolved))))
                              (sci/var->symbol resolved))]
        (when (and function-symbol
                   (get (:seon.schema.projection/function-contracts projection) function-symbol))
          function-symbol)))))

(defn- attribute-producer
  [projection request output]
  (when-let [attribute (:seon.render.walk/attribute request)]
    (some-> (mr/schema (:seon.schema.projection/registry projection) attribute)
            m/properties
            (get output))))

(defn- attribute-scoped?
  "Is this render request ABOUT one attribute?

  `:seon.render.walk/attribute` means exactly one thing on a render request:
  the attribute the request asks about. The walk hands that decision —
  `seon.render.walk/neighborhood` stamps it only for a member it synthesized
  FOR an attribute, never for a neighbour entity it merely REACHED through
  one, and a neighbour therefore reaches map-shape discovery and renders by
  its own shape. An attribute-scoped request resolves to that attribute's
  declared pair or to the generic printer at the floor; it never borrows the
  owning entity's declared form, so an attribute nobody curated is visibly
  generic (owner ruling, 2026-09-17, decision 9 option 1)."
  [request]
  (some? (:seon.render.walk/attribute request)))

(defn- render-invocation-argument
  "Supply an attribute declaration with that attribute's value."
  [projection request selected]
  (let [attribute (:seon.render.walk/attribute request)
        declared (when attribute
                   (into #{}
                         (keep #(attribute-producer projection request %))
                         [:seon.render/form (:seon.render/output request)]))
        value (render-value request)]
    (if (contains? declared selected)
      ;; The walk hands the owning entity; the debug page hands the
      ;; attribute's value, possibly as its pulled connected entities. Both
      ;; reach the producer in the attribute's transaction shape, which is
      ;; the shape its declared input accepts.
      (let [attribute-value
            (get (render.value/transacted
                  (if (and (map? value) (contains? value attribute))
                    value
                    {attribute value})
                  (:seon.db/db request))
                 attribute)
            unit (producer-argument request)]
        ;; The declared contract decides whether this pair takes the raw
        ;; attribute or its render unit with database custody.
        (if (and (not (schema/function-accepts-in? projection selected [attribute-value]))
                 (schema/function-accepts-in? projection selected [unit]))
          unit
          attribute-value))
      (if (floor-producer? selected)
        (render-argument request)
        (producer-argument request)))))

(defn- declared-producers
  "The renderers `value`'s declared identity names for `output`: the
  alternatives its own error operation returns, else the returns of the root
  call's contracted function, else its owning row schema. Distinct producers."
  {:malli/schema [:=> [:cat [:maybe :seon.schema/projection] :map :seon.schema/value :seon.render/output]
                  [:vector :qualified-symbol]]}
  [projection request value output]
  (let [registry (:seon.schema.projection/registry projection)
        function (:seon.render.call/function request)
        pairs (fn [schema-keys]
                (into [] (comp (keep #(when-let [s (mr/schema registry %)]
                                      (get (or (internal/entity-properties s) (m/properties s)) output)))
                               (filter qualified-symbol?) (distinct))
                      schema-keys))]
    (cond
      (nil? projection) []
      ;; An attribute request stays about that attribute: its own pair.
      (attribute-scoped? request) (into [] (keep identity) [(attribute-producer projection request output)])
      (not (map? value)) []
      (and (:seon.error/at value) (qualified-symbol? (:seon.error/operation value)))
      ;; The satisfied alternative's pair; a contracted operation none of whose
      ;; alternatives the error satisfies is a contract finding shown with the
      ;; base renderer; an uncontracted operation composes the base pair.
      (let [alternatives (error-alternatives projection value)
            found (pairs alternatives)]
        (cond (seq found) found
              (and alternatives (empty? alternatives) (#{:seon.render/ai :seon.render/html} output))
              [(if (= :seon.render/ai output) 'seon.error/render-undeclared-ai 'seon.error/render-undeclared-html)]
              :else (pairs [:seon.error/base])))
      (and function (identical? value (render-value request)))
      (pairs (satisfied-alternatives projection function value))
      ;; An attribute request stays an attribute request (AGENTS.md): an
      ;; entity nested in a value keeps its pulled shape, so its identity
      ;; owner's pair claims only a root value. A nested value takes only the
      ;; pair its reaching attribute declares (a component's schema).
      :else (pairs (or (when (empty? (:seon.render.data/path request))
                         (some-> (entity-owner projection value) vector))
                       (some->> (attribute-identity projection (:seon.render.data/path request))
                                (named-alternatives projection)
                                (filterv #((schema/projection-validator projection %) value))))))))

(defn- declared-producer
  {:malli/schema [:=> [:cat :seon.schema/projection :map :seon.schema/value :seon.render/output]
                  [:or :nil :qualified-symbol :seon.render/ambiguous-error]]}
  [projection request value output]
  (let [declared (declared-producers projection request value output)]
    (cond
      (attribute-scoped? request) (attribute-producer projection request output)
      (= 1 (count declared)) (first declared)
      (seq declared) (ambiguity nil output declared))))

(def ^:private selection-stage-order
  [:explicit-value :namespace :declared :floor])

(defn- selection-candidate
  [producer]
  {:seon.render.selection.candidate/producer producer
   :seon.render.selection.candidate/status :compatible})

(defn- selection-stage
  ([stage-name status]
   {:seon.render.selection.stage/name stage-name
    :seon.render.selection.stage/status status})
  ([stage-name status candidates]
   (assoc (selection-stage stage-name status)
          :seon.render.selection.stage/candidates (vec candidates)))
  ([stage-name status candidates error]
   (assoc (selection-stage stage-name status candidates)
          :seon.render.selection.stage/error error)))

(defn- selected-stage
  [stage selected]
  {:seon.render.selection/stage stage
   :seon.render.selection/selected selected})

(defn- finish-selection
  [stages selected remaining-stage-names]
  {:seon.render.selection/stages
   (into (vec stages)
         (map #(selection-stage % :not-consulted))
         remaining-stage-names)
   :seon.render.selection/selected selected})

(defn- floor-producer
  [request output]
  (case output
    :seon.render/form 'seon.render/render-form
    :seon.render/html 'seon.render.value/render-html
    (if (:seon.render.call/source-output? request)
      'seon.render/render-default-ai-source
      'seon.render.value/render-ai)))

(defn- explicit-value
  "A map's own inline render for OUTPUT's face: a renderer symbol, or the
  rendered data itself. Each face is independent; nil when the value names none."
  {:malli/schema [:=> [:cat :seon.schema/value :seon.render/output]
                  [:maybe [:tuple :seon.render/output :seon.schema/value]]]}
  [value output]
  (when (map? value) (find value output)))

(defn- explicit-value-stage
  [request output]
  (if-let [explicit (explicit-value (render-value request) output)]
    (selected-stage
     (if (qualified-symbol? (val explicit))
       (selection-stage :explicit-value :selected [(selection-candidate (val explicit))])
       (assoc (selection-stage :explicit-value :selected)
              :seon.render.selection.stage/value (val explicit)))
     (val explicit))
    {:seon.render.selection/stage (selection-stage :explicit-value :no-match)}))

(defn- inline-rendered
  "Inline rendered data at its face's edge: AI text or HTML Hiccup as given,
  any other data through that face's own floor emitter."
  {:malli/schema [:=> [:cat :map :seon.render/output :seon.schema/value]
                  [:or :seon.render/rendered :seon.render/error-result]]}
  [request output rendered]
  (cond
    (and (= :seon.render/ai output) (string? rendered)) rendered
    (and (= :seon.render/html output) (hiccup/hiccup? rendered)) rendered
    (= :seon.render/form output) rendered
    :else ((if (= :seon.render/html output) render.value/render-html render.value/render-ai)
           (cond-> (assoc (render-argument request) :seon.render/value rendered)
             (:seon.db/db request) (assoc :seon.db/db (:seon.db/db request))))))

(defn- viewing-namespace
  "The namespace of the agent the value is rendered for: the request's own
  :seon.render/namespace, else the rendering agent's assigned namespace."
  {:malli/schema [:=> [:cat :map] [:maybe :symbol]]}
  [request]
  (or (:seon.render/namespace request)
      (when-let [agent-id (:seon.agent/id request)]
        (some-> (:seon.db/db request)
                (db/pull [{:seon.agent/namespace [:seon.ns/name]}] [:seon.agent/id agent-id])
                (get-in [:seon.agent/namespace :seon.ns/name])))))

(defn- namespace-stage
  "The viewing agent's own render functions (owner, 2026-09-25): a public
  function of its namespace whose contract accepts this render argument and
  returns OUTPUT's face. Candidates are sorted; more than one is refused."
  [request projection output]
  (let [namespace-name (viewing-namespace request)
        output-schema (case output :seon.render/html :seon.render/hiccup output)
        argument (producer-argument request)
        evidence (if-not (and namespace-name (:seon.sci.eval/ctx request))
                   []
                   (into [] (comp (filter #(= namespace-name (symbol (namespace %)))) (distinct)
                                  (map (fn [candidate]
                                         (if (or (schema/function-accepts-and-returns-in?
                                                  projection candidate [argument] output-schema)
                                                 (and (= :seon.render/ai output)
                                                      (source-producer? projection candidate [argument])))
                                           (selection-candidate candidate)
                                           {:seon.render.selection.candidate/producer candidate
                                            :seon.render.selection.candidate/status :rejected
                                            :seon.render.selection.candidate/reason :no-same-arity-match}))))
                         (sort-by str (sci.kernel/public-functions-in (:seon.sci.eval/ctx request) namespace-name))))
        compatible (into [] (comp (filter #(= :compatible (:seon.render.selection.candidate/status %)))
                                  (map :seon.render.selection.candidate/producer))
                         evidence)]
    (case (count compatible)
      0 {:seon.render.selection/stage (selection-stage :namespace :no-match evidence)}
      1 (selected-stage (selection-stage :namespace :selected evidence) (first compatible))
      (let [selection-error (ambiguity namespace-name output compatible)]
        (selected-stage (selection-stage :namespace :ambiguous evidence selection-error) selection-error)))))

(defn- declared-stage
  [request projection value output]
  (let [producers (declared-producers projection request value output)
        evidence (mapv selection-candidate producers)]
    (case (count producers)
      0 {:seon.render.selection/stage (selection-stage :declared :no-match evidence)}
      1 (selected-stage (selection-stage :declared :selected evidence) (first producers))
      (let [selection-error (ambiguity nil output producers)]
        (selected-stage (selection-stage :declared :ambiguous evidence selection-error)
                        selection-error)))))

(defn- selection-stage-result
  [stage-name request projection value output]
  (case stage-name
    :explicit-value (explicit-value-stage request output)
    :namespace (if projection (namespace-stage request projection output)
                   {:seon.render.selection/stage (selection-stage :namespace :no-match)})
    :declared (declared-stage request projection value output)
    :floor (let [selected (floor-producer request output)]
             (selected-stage
              (selection-stage :floor :selected
                               [(selection-candidate selected)])
              selected))))

(defn selection
  "Explain and return the exact producer decision used for one render call."
  {:malli/schema
   [:=> [:catn [::request :seon.render/selection-request]]
    :seon.render/selection]}
  [{output :seon.render/output
    :as request}]
  (let [profile (request-profile request)]
    (if (:seon.render/refused-member profile)
      (finish-selection [] profile selection-stage-order)
      (let [request (assoc request :seon.render/profile profile)
            value (render-value request)
            projection (request-projection request)]
        (loop [stage-names selection-stage-order
               stages []]
          (let [stage-name (first stage-names)
                result (selection-stage-result
                        stage-name request projection value output)
                stages (conj stages (:seon.render.selection/stage result))]
            (if-let [selected (find result :seon.render.selection/selected)]
              (finish-selection stages (val selected) (rest stage-names))
              (recur (rest stage-names) stages))))))))

(defn selection-inspection
  "Inspect every priority stage while preserving the actual decision."
  {:malli/schema
   [:=> [:catn [::request :seon.render/selection-request]]
    :seon.render/selection]}
  [{output :seon.render/output
    :as request}]
  (let [decision (selection request)
        profile (request-profile request)]
    (if (:seon.render/refused-member profile)
      decision
      (let [request (assoc request :seon.render/profile profile)
            value (render-value request)
            projection (request-projection request)
            stages (mapv (fn [stage-name]
                           (:seon.render.selection/stage
                            (selection-stage-result
                             stage-name request projection value output)))
                         selection-stage-order)
            actual-stages (:seon.render.selection/stages decision)]
        (assoc decision :seon.render.selection/stages
               (mapv (fn [stage actual]
                       (if (= :not-consulted
                              (:seon.render.selection.stage/status actual))
                         (assoc stage :seon.render.selection.stage/status
                                :not-consulted)
                         actual))
                     stages actual-stages))))))

(defn- producer

  {:malli/schema [:=> [:cat :map :seon.render/output :qualified-keyword] [:or :qualified-symbol :seon.render/rendered :seon.render/error-result :seon.render/inline]]}
  [request output _output-schema]
  (:seon.render.selection/selected
   (selection (assoc request :seon.render/output output))))

(defn- invocation-argument-evidence
  [projection request selected]
  (let [argument (render-invocation-argument projection request selected)
        value (when (map? argument) (:seon.render/value argument))]
    (cond-> (if (map? argument)
              (dissoc argument
                      :seon.db/db
                      :seon.sci.eval/ctx
                      :seon.db/connection)
              argument)
      (map? value)
      (assoc :seon.render/value (dissoc value :seon.db/db)))))

(defn- call-static-evidence
  [request decision selected]
  (let [
        projection (request-projection request)
        argument (invocation-argument-evidence projection request selected)]
    {:seon.render/selection decision
     :seon.render.call/producer selected
     :seon.render/would-fall-to-floor? (floor-producer? selected)
     :seon.render.call/declaration-row
     (sci.kernel/program-function (:seon.sci.eval/ctx request) selected)
     :seon.render.call/argument argument}))

(defn- compatible-selection-candidate?
  [decision candidate-producer]
  (some (fn [stage]
          (some #(and (= candidate-producer
                         (:seon.render.selection.candidate/producer %))
                      (= :compatible
                         (:seon.render.selection.candidate/status %)))
                (:seon.render.selection.stage/candidates stage)))
        (:seon.render.selection/stages decision)))

(defn source-generation
  "The adopted program commit carried by this database; empty before adoption."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:or [:vector :seon.source/commit-id] :seon.db/error-result]]}
  [database]
  (db/q '[:find [?commit ...]
          :where [?cluster :seon.cluster/name _]
                 [?cluster :seon.source/commit-id ?commit]] database))

(defn- program-evidence-current?
  [snapshot evidence]
  (and (some? evidence)
       (every? (fn [[section rows]]
                 (every? (fn [[member row]]
                           (= row (get-in snapshot [section member]))) rows))
               evidence)))

(defn- render-program-evidence
  "The program rows a retained call reaches over recorded call edges."
  {:malli/schema [:=> [:cat :seon.db/database-value [:or :nil :map] :seon.fn/sym] :map]}
  [database snapshot selected]
  (let [symbols (db/stored-call-reach database selected)
        namespaces (set (map #(symbol (namespace %)) symbols))]
    {:functions (into {} (map #(vector % (get (:functions snapshot) %))) symbols)
     :namespaces (into {} (map #(vector % (get (:namespaces snapshot) %))) namespaces)}))

(defn call-cache-evidence
  "Describe a retained call's code, projection, and supplied input."
  {:malli/schema [:=> [:cat :map :qualified-symbol] :map]}
  [request selected]
  (let [ctx (:seon.sci.eval/ctx request)
        projection (sci.kernel/context-projection ctx)
        snapshot (some-> (:seon.sci.kernel/program-snapshot ctx) deref)
        previous (get (:seon.render/retained-calls request)
                      (:seon.render.call/id request))
        program (when snapshot
                  (if (and (= selected (first (::selection-input previous)))
                           (or (identical? snapshot (::program-snapshot previous))
                               (program-evidence-current? snapshot (::program-evidence previous)))
                           (::program-evidence previous))
                    (::program-evidence previous)
                    (render-program-evidence (:seon.db/db request) snapshot selected)))]
    {:seon.db/db (:seon.db/db request)
     ::program-snapshot snapshot
     ::program-evidence program
     ;; Private SCI definitions have no durable row. Their actual callable
     ;; identity changes on redefinition and never pretends to be program data.
     ::private-callable (when-not (get-in snapshot [:functions selected])
                          (some-> (sci/resolve ctx selected) deref))
     ::projection projection
     ::projection-evidence (select-keys projection
                                       [:seon.schema.projection/forms
                                        :seon.schema.projection/function-contracts])
     ::selection-input
     [selected
      (:seon.render/output request)
      (invocation-argument-evidence projection request selected)
      (select-keys request [:seon.sci.admit/caps
                            :seon.sci.eval/time-limit-ms
                            :seon.config/on-core-error
                            :seon.agent/id
                            :seon.render/namespace
                            :seon.render.call/source-output?])]}))

(declare same-call-cache-evidence?)

(defn retained-program-current?
  "Verify retained program and schema inputs against the acquired SCI context."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :map] :boolean]}
  [ctx previous]
  (let [snapshot (some-> (:seon.sci.kernel/program-snapshot ctx) deref)
        projection (sci.kernel/context-projection ctx)
        selected (first (::selection-input previous))]
    (and (some? (::program-evidence previous))
         (or (identical? snapshot (::program-snapshot previous))
             (program-evidence-current? snapshot (::program-evidence previous)))
         (= (::projection-evidence previous)
            (select-keys projection [:seon.schema.projection/forms
                                     :seon.schema.projection/function-contracts]))
         (or (get-in snapshot [:functions selected])
             (identical? (::private-callable previous)
                         (some-> (sci/resolve ctx selected) deref)))
         true)))

(defn same-committed-database?
  "True for committed values of the same connection generation and commit.
  Temporal and speculative values have no committed identity."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value] :boolean]}
  [previous current]
  (let [committed (datahike.db/committed-value-identity current)]
    (and (some? committed)
         (= committed (datahike.db/committed-value-identity previous)))))

(defn- invocation-cache-key
  [request selected evidence]
  [selected
   (:seon.render/output request)
   (hash (::program-evidence evidence))
   (hash (::projection-evidence evidence))
   (hash (::selection-input evidence))])

(defn- reusable-invocation
  [database bucket evidence]
  (some (fn [entry]
          (when (and (same-call-cache-evidence? entry evidence)
                     (db/read-evidence-current?
                      database (:seon.render.call/read-evidence entry)))
            entry))
        bucket))

(defn- retain-invocation!
  [captured cache-key retained-bucket entry]
  (when captured
    (swap! captured assoc cache-key
           (conj (into []
                       (remove #(same-call-cache-evidence? % entry))
                       retained-bucket)
                 entry))))

(defn same-invocation-evidence?
  "True when two invocation entries describe the same code and input."
  {:malli/schema [:=> [:cat :map :map] :boolean]}
  [previous current]
  (and (some? (::program-snapshot current))
       (some? (::program-evidence current))
       (some? (::projection current))
       (= (::program-evidence previous) (::program-evidence current))
       (identical? (::private-callable previous) (::private-callable current))
       (= (::projection-evidence previous) (::projection-evidence current))
       (= (::selection-input previous) (::selection-input current))))

(def ^:private same-call-cache-evidence? same-invocation-evidence?)

(defn refresh-read-evidence
  "Carry one retained call's read evidence forward onto a newer database.

  THE ONE REFRESHER. Every retained entry's evidence is rebuilt from its own
  `:datahike.read/dependency-plan` and source argument position, so a second
  hand-written copy of this walk is a defect, not a convenience."
  {:malli/schema [:=> [:cat :seon.db/database-value :map] :map]}
  [database previous]
  (assoc previous :seon.render.call/read-evidence
         (mapv (fn [retained current]
                 (assoc retained
                        :datahike.read/revision
                        (:datahike.read/revision current)))
               (:seon.render.call/read-evidence previous)
               (db/read-evidence
                (mapv
                 (fn [evidence]
                   {:seon.db/db database
                    :seon.db/source-argument-position
                    (:seon.db/source-argument-position evidence)
                    :datahike.read/dependency-plan
                    (:datahike.read/dependency-plan evidence)})
                 (:seon.render.call/read-evidence previous))))))

(defn- cost-shape-key
  "The row schema owning the rendered value, else the generic value schema."
  [projection request]
  (let [value (render-value request)]
    (or (when (and projection (map? value)) (entity-owner projection value))
        :seon.schema/value)))

(defn- render-cost-fact
  [request rendered]
  {:seon.render.cost/shape-key
   (cost-shape-key (request-projection request) request)
   :seon.render.cost/profile
   (get-in request [:seon.render/profile :seon.render.profile/id])
   :seon.render.cost/estimated-tokens
   (tokens/estimate (if (string? rendered) rendered (pr-str rendered)))
   :seon.render.cost/at (Date.)})

;;; ---------------------------------------------------------------------------
;;; The typed unknown one refused producer contributes
;;; ---------------------------------------------------------------------------

;;; A RENDER THAT DID NOT HAPPEN IS AN OBSERVATION, NOT AN ABSENCE (§2.4).
;;; Every producer runs under `:seon.sci.eval/time-limit-ms`, so a slower
;;; machine refuses a producer a faster one completes. While that refusal
;;; contributed nothing, the bound firing MOVED THE PROMPT BYTES — which is
;;; exactly why "same database value, same adopted commit, same profile ⇒
;;; same bytes" was unreachable (Opus review B5, PRD §5). The value below is
;;; what the refusal contributes instead, and it is STABLE BY CONSTRUCTION:
;;; only the producer, the call, the reason, the refusal's observed operation and the
;;; throwable's class. The kernel's diagnostic record — duration, entrances,
;;; allocation — is deliberately NOT carried: it is the one part of a refusal
;;; that differs run to run, and it would otherwise both move prompt bytes and
;;; contribute changing diagnostic evidence for the same broken renderer.

(def ^:private unknown-reason-by-outcome
  "The reason each guarded invocation outcome states about itself."
  {:time :time-limit
   :error :refused})

(defn- unknown-stable-evidence
  [{producer-symbol :seon.render.unknown/producer
    output :seon.render/output
    reason :seon.render.unknown/reason
    call-id :seon.render.call/id
    failure :seon.error/value}]
  (let [data (:seon.error/data failure)]
    (cond-> (sorted-map :seon.render.unknown/reason reason)
      producer-symbol (assoc :seon.render.unknown/producer producer-symbol)
      output (assoc :seon.render.unknown/output output)
      (qualified-symbol? (:seon.error/operation failure))
      (assoc :seon.render.unknown/refused-operation (:seon.error/operation failure))
      (string? (:seon.sci.eval/throwable data))
      (assoc :seon.render.unknown/throwable (:seon.sci.eval/throwable data))
      (vector? call-id) (assoc :seon.render.unknown/call call-id))))

(defn unknown
  "The ONE stable typed unknown a refused render producer contributes.

  `:seon.render.unknown/reason` records the invocation outcome: `:time-limit` when the
  producer ran past the request's `:seon.sci.eval/time-limit-ms`, `:refused`
  when the guarded invocation ended in a throwable or a declared-contract
  refusal, and `:unselected` when no producer ran at all. The refusal's observed operation and the throwable's class ride along when the boundary
  observed them, because they are what a repair starts from and both are
  stable spellings."
  {:malli/schema [:=> [:cat :seon.render/unknown-request] :seon.render/unknown]}
  [{producer-symbol :seon.render.unknown/producer
    reason :seon.render.unknown/reason
    :as request}]
  (let [stable (unknown-stable-evidence request)
        observation
        (merge stable
               (seon.error.refusal/diagnostic (Date.) :seon.render/invocation 'seon.render/unknown
                {:seon.error/message "The selected renderer did not return an observation."
                :seon.error/fix "Inspect the renderer's refusal and repair its declared output."
                :seon.error/member :seon.render/output
                :seon.error/expected :seon.render/rendered
                :seon.error/offending (:seon.error/value request)
                :seon.error/data stable}))]
    observation))

(defn- unknown-evidence-of
  [unit]
  (let [value (if (map? (:seon.render/value unit)) (:seon.render/value unit) unit)]
    (into (sorted-map)
          (filter (fn [entry]
                    (= "seon.render.unknown" (namespace (key entry)))))
          value)))

(defn unknown-ai
  "Render the declared unknown declared-schema and its stable observation fields.

  The declared-schema line follows the error message grammar. Sorted observation data
  keeps timing and other transient invocation measurements out of the shown
  text while the complete refusal remains available as diagnostic evidence."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds.", :gen/elements [nil false 0 "" :k [] {}]}]] [:string {:min 1}]]}
  [unit]
  (str "Declared schemas: [:seon.render/unknown]\n"
       (admit/canonical-edn (unknown-evidence-of unit))))

(defn unknown-html
  "`:seon.render/html` — one refused render as one labeled block.

  It keeps the `seon-render-unavailable` class it always had: that class is
  both the compact diagnostic face in the stylesheet and the ONE placeholder
  class `seon.render.lint` counts, so a page full of refusals is still a lint
  finding rather than a new unstyled, uncounted block. What is new is inside
  it — the same line the agent reads."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds.", :gen/elements [nil false 0 "" :k [] {}]}]] :seon.render/hiccup]}
  [unit]
  [:div {:class "seon-render-unavailable seon-render-unknown"}
   [:span {:class "seon-render-unknown-label"} "renderer unavailable"]
   [:code {:class "seon-render-unknown-detail"} (unknown-ai unit)]])

(defn refused
  "Read one refusal as a typed unknown, converting a foreign one in place.

  TOTAL: a refusal that never reached a producer — an ambiguous selection, a
  missing projection — is still an unavailable observation, so it becomes the
  same typed unknown with no producer to name and its observed refusal operation."
  {:malli/schema [:=> [:cat :seon.error/value] :seon.render/unknown]}
  [failure]
  (if (:seon.render.unknown/reason failure)
    failure
    (unknown {:seon.render.unknown/reason :unselected
              :seon.error/value failure})))

(defn unknown-output
  "One refused render's OUTPUT value for `output` — never absence, never nil."
  {:malli/schema [:=> [:cat :seon.render/output :seon.error/value]
                  :seon.render/rendered]}
  [output failure]
  (let [value (refused failure)]
    (if (= :seon.render/html output)
      (unknown-html {:seon.render/value value})
      (unknown-ai {:seon.render/value value}))))

(defn- invoke-selected
  "Run one selected producer and return the guarded invocation's WHOLE result.

  The result, not just its value: `:seon.sci.admit/record` is how the boundary
  says whether the producer returned at all, and reading it here is what lets
  `invocation-unknown` tell a refusal from a producer that legitimately
  RETURNED an ordinary `:seon.error` value. Asking the shape of the value
  instead would confuse the two forever."
  {:seon.fn/invokes #{:seon.render/ai :seon.render/html :seon.render/form}}
  [{ctx :seon.sci.eval/ctx
    caps :seon.sci.admit/caps
    time-limit-ms :seon.sci.eval/time-limit-ms
    on-core-error :seon.config/on-core-error
    :as request}
   selected]
  (let [projection (request-projection request)
        ;; The request's database is the one a declared :seon.db/db input
        ;; is supplied with: scoped on the invocation's environment, so the
        ;; supplier (seon.db/supplied-database-value) answers it, not the
        ;; connection's current value. Caller arguments still win.
        scoped (when-let [database (:seon.db/db request)]
                 (some-> (env/of ctx) (env/scope {:seon.db/db database})))
        ctx (if (env/environment? scoped) (env/carry-state ctx (env/environment-state scoped)) ctx)
        ;; RECORD WHAT IS RENDERING. A producer may hand its own value
        ;; to another producer — the value floor is the common case —
        ;; so the producers already on this chain travel with the
        ;; argument. `project-node*` refuses to select one that is
        ;; already there, which makes self-re-entrance unconstructable
        ;; rather than merely unlikely.
        request (update request :seon.render/rendering
                        (fnil conj #{}) selected)
        argument (render-invocation-argument projection request selected)]
    (schema/call-with-projection
     projection
     #(sci.kernel/invoke
       (cond->
        {:seon.sci.eval/ctx ctx
         :seon.fn/sym selected
         :seon.sci.eval/args [argument]
         :seon.sci.admit/unbounded? true
         :seon.sci.eval/time-limit-ms time-limit-ms
         :seon.sci.admit/caps caps
         :seon.config/on-core-error on-core-error}
         ;; A request without a database leaves the key absent, and the
         ;; runtime supplies it; never a stored nil.
         (:seon.db/db request) (assoc :seon.db/db (:seon.db/db request))
         (:seon.render.call/captured-reads request)
         (assoc :seon.db/read-evidence-sink
                (:seon.render.call/captured-reads request)))))))

(defn- invocation-unknown
  "The typed unknown for a producer that did not return, else nil."
  {:malli/schema [:=> [:cat :map :seon.render/output :qualified-symbol :map] [:or :nil :seon.render/unknown]]}

  [request output selected result]
  (when-let [reason (get unknown-reason-by-outcome
                         (get-in result [:seon.sci.admit/record
                                         :seon.eval/outcome]))]
    (unknown (cond-> {:seon.render.unknown/reason reason
                      :seon.render.unknown/producer selected}
               ;; A producer stopped by `time-limit` never produced a
               ;; value: absent is no key, never a stored nil.
               (:seon.sci.admit/value result)
               (assoc :seon.error/value (:seon.sci.admit/value result))
               output (assoc :seon.render/output output)
               (:seon.render.call/id request)
               (assoc :seon.render.call/id (:seon.render.call/id request))))))

(defn- invoked
  "One producer's returned value, or the typed unknown that replaces it."
  {:malli/schema [:=> [:cat :map :seon.render/output :qualified-symbol]
                  [:or :seon.render/rendered :seon.render/source-blocks :seon.render/error-result]]}

  [request output selected]
  (let [result (invoke-selected request selected)]
    (or (invocation-unknown request output selected result)
        (:seon.sci.admit/value result))))

(defn- valid-projection?
  [projection output value]
  (or (and (:seon.error/at value) (:seon.error/layer value) (:seon.error/operation value)) ;; debt: seon.sci.kernel/invoke declares :seon.error/value through its admitted result.
      ((schema/projection-validator projection output) value)))

(defn declared-projection
  "The declared render pair's answer for one value the floor reached, or nil.

  The floor (`seon.render.value`) asks this for each map it reaches, with the
  map's path on the request. The answer is `{:seon.print/node n}` for a
  producer's rendered output (a `:seon.print/projected` node), or
  `{:seon.error/value e}` for a refusal, unknown or failed producer, which
  the floor then selects as an ordinary value. A selected producer's output is
  terminal: it is never fed back into selection."
  {:malli/schema
   [:=> [:cat :map [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds.", :gen/elements [nil false 0 "" :k [] {}]}] :seon.render/output]
    [:maybe :map]]}
  [request value output]
  ;; A RENDER UNIT NEED NOT CARRY A CTX OR A DATABASE: no projection anywhere
  ;; means nothing is declared, and nothing is selected.
  (let [projection (request-projection request)
        ;; AN ATTRIBUTE SCOPES THE REQUEST'S SUBJECT, NOT THE VALUES BENEATH
        ;; IT: a nested value answers for its own declared shape.
        request (cond-> request
                  (seq (:seon.render.data/path request)) (dissoc :seon.render.walk/attribute))
        ;; A PRODUCER IS NEVER RE-ENTERED INSIDE ITS OWN WALK. A producer that
        ;; renders its value through the floor hands the floor the very value
        ;; whose schema selected it; `invoke-selected` records what it is
        ;; running, so the cycle is unconstructable rather than depth-capped
        ;; (measured 2026-08-07: 1024 frames of project-node → attempt-html →
        ;; prepare → project-node before this rule).
        rendering (:seon.render/rendering request #{})
        refused? #(or (:seon.render/refused-member %) (:seon.render/candidates %) (:seon.render.unknown/reason %))
        selected (when (and projection (map? value))
                   (if-let [explicit (explicit-value value output)]
                     (val explicit)
                     (declared-producer projection request value output)))
        selected (when-not (or (contains? rendering selected)
                               (and (= output :seon.render/ai)
                                    selected
                                    (or (not (refused? selected))
                                        (seq (get-in selected [:seon.error/data :seon.render/candidates])))
                                    (some
                                     #(source-producer?
                                       projection %
                                       [(producer-argument
                                         (assoc request :seon.render/value value))])
                                     (if (seq (get-in selected [:seon.error/data :seon.render/candidates]))
                                       (get-in selected [:seon.error/data :seon.render/candidates])
                                       [selected]))))
                   selected)]
    (cond
      (nil? selected) nil
      (refused? selected) {:seon.error/value selected}
      (not (qualified-symbol? selected))
      {:seon.print/node {:seon.print/face :seon.print/projected
                         :seon.render/output output
                         :seon.print/value (inline-rendered (assoc request :seon.render/value value) output selected)}}
      :else
      ;; A NESTED PRODUCER THAT DID NOT RETURN SAYS SO; an output outside
      ;; the face's contract is not shown as though it were.
      (let [node-request (assoc request :seon.render/value value)
            result (invoke-selected node-request selected)
            rendered (:seon.sci.admit/value result)
            unavailable (invocation-unknown node-request output selected result)]
        (cond
          unavailable {:seon.error/value unavailable}
          (and (valid-projection? projection output rendered) (not (refused? rendered))
               (not (:seon.render/invalid-output rendered)))
          {:seon.print/node {:seon.print/face :seon.print/projected
                             :seon.render.call/selected-producer selected
                             :seon.render/output output
                             :seon.print/value rendered}}
          :else nil)))))

(defn- invoke-producer

  {:malli/schema [:=> [:cat :map :seon.render/output :qualified-keyword] [:or :seon.render/rendered :seon.render/source-blocks :seon.render/error-result]]}
  [request output output-schema]
  (let [selected (or (:seon.render.call/selected-producer request)
                     (producer request output output-schema))]
    (cond
      (or (:seon.render/refused-member selected) (:seon.render/candidates selected) (:seon.render.unknown/reason selected))
      selected
      (qualified-symbol? selected) (invoked request output selected)
      :else (inline-rendered request output selected))))

(defn- raw-output
  {:malli/schema [:=> [:cat :seon.render/call-request :seon.render/output :qualified-symbol]
                  [:or :seon.render/rendered :seon.render/source-blocks :seon.render/error-result]]}
  [request output selected]
  (let [projection (request-projection request)
        rendered (invoked request output selected)
        ;; Optional source/observation contracts declare absence explicitly.
        ;; An ordinary text renderer returning nil has violated its contract.
        declared-absence?
        (and (nil? rendered)
             (some #(m/validate % nil {:registry (:seon.schema.projection/registry projection)})
                   (schema/function-matching-outputs-in
                    projection selected
                    [(render-invocation-argument projection request selected)])))]
    (case output
      :seon.render/ai
      (if (or declared-absence? (string? rendered) (or (:seon.render.unknown/reason rendered) (:seon.render/invalid-output rendered) (:seon.render/refused-member rendered))
              (valid-projection? projection :seon.render/source-blocks rendered))
        rendered
        (let [observation
              {:seon.error/fix "Return a value satisfying the renderer's declared output contract."
               :seon.error/at (Date.)
               :seon.error/layer :seon.render/output
               :seon.error/operation 'seon.render/raw-output
               :seon.render/invalid-output :ai
               :seon.error/message "The selected AI renderer did not return text."
               :seon.error/data {:seon.render/output rendered}
               :seon.error/expected "a value satisfying the requested render output"
               :seon.error/offending rendered}]
          observation))

      :seon.render/html
      (if (or declared-absence? (or (:seon.render.unknown/reason rendered) (:seon.render/invalid-output rendered) (:seon.render/refused-member rendered))
              (hiccup/hiccup? rendered))
        rendered
        (let [observation
              {:seon.error/fix "Return a value satisfying the renderer's declared output contract."
               :seon.error/at (Date.)
               :seon.error/layer :seon.render/output
               :seon.error/operation 'seon.render/raw-output
               :seon.render/invalid-output :html
               :seon.error/message "The selected HTML renderer did not return Hiccup."
               :seon.error/data {:seon.render/output rendered}
               :seon.error/expected "a value satisfying the requested render output"
               :seon.error/offending rendered}]
          observation))

      :seon.render/form
      (if (valid-projection? projection :seon.render/form rendered)
        rendered
        (let [observation
              {:seon.error/fix "Return a value satisfying the renderer's declared output contract."
               :seon.error/at (Date.)
               :seon.error/layer :seon.render/output
               :seon.error/operation 'seon.render/raw-output
               :seon.render/invalid-output :form
               :seon.error/message "The selected form renderer did not return a form."
               :seon.error/data {:seon.render/output rendered}
               :seon.error/expected "a value satisfying the requested render output"
               :seon.error/offending rendered}]
          observation)))))

(defn render-ai
  "Render one value as text through the unique selected live SCI Var."
  {:malli/schema [:=> [:cat :seon.render/call-request]
                  [:or :nil :string :seon.render/error-result]]
   :seon.fn/external-sink :ai-visible-text
  :seon.fn/projection-boundary :seon.render/ai}
  [request]
  (let [profile (request-profile request)]
    (if (:seon.render/refused-member profile)
      profile
      (let [request (assoc request :seon.render/profile profile)
            selected (or (:seon.render.call/selected-producer request)
                         (producer request :seon.render/ai :seon.render/ai))]
        (if (or (:seon.render/refused-member selected) (:seon.render/candidates selected) (:seon.render.unknown/reason selected))
          selected
          (raw-output request :seon.render/ai selected))))))

(defn render-html
  "Render one value as Hiccup through the unique selected live SCI Var."
  {:malli/schema [:=> [:cat :seon.render/call-request]
                  [:or :nil :seon.render/hiccup :seon.render/error-result]]
   :seon.fn/external-sink :html-response
  :seon.fn/projection-boundary :seon.render/html}
  [request]
  (let [profile (request-profile request)]
    (if (:seon.render/refused-member profile)
      profile
      (let [request (assoc request :seon.render/profile profile)
            selected (or (:seon.render.call/selected-producer request)
                         (producer request :seon.render/html
                                   :seon.render/html))]
        (if (or (:seon.render/refused-member selected) (:seon.render/candidates selected) (:seon.render.unknown/reason selected))
          selected
          (raw-output request :seon.render/html selected))))))

(defn- entity-lookup
  [database entity]
  (let [identity-attribute
        (->> (db/identity-attributes database)
             (filter #(contains? entity %))
             first)]
    (if identity-attribute
      [identity-attribute (get entity identity-attribute)]
      (:db/id entity))))

(defn- source-provenance-error

  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/request-error]}
  [unit]
  (let [value (:seon.render/value unit)
        observation
        (seon.error.refusal/diagnostic (java.util.Date.) :seon.render/render 'seon.render/source-provenance-error
         {:seon.error/message "Default AI source requires an entity identity or stored evaluation result reference."
         :seon.error/fix "Supply the expected member and repeat the requested operation."
         :seon.render/refused-member :seon.render.value/root
         :seon.error/member :seon.render.value/root
         :seon.error/expected :seon.render.walk/lookup
         ;; The anonymous value itself; a host object is named by its class,
         ;; never printed as an #object.
         :seon.error/offending (if (or (nil? value) (coll? value) (string? value) (number? value)
                                       (keyword? value) (symbol? value) (boolean? value))
                                 value
                                 (.getName (class value)))})]
    observation))

(defn render-form
  "Spell the structural read that reproduces one reached database value."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :seon.render/form :seon.render/request-error :seon.db/error-result]]}
  [unit]
  (let [value (:seon.render/value unit)
        root (or (:seon.render.value/root unit)
                 (when (map? value)
                   (entity-lookup (:seon.db/db unit) value)))
        cursor (:seon.render.data/cursor unit)
        path (:seon.render.data/path cursor)]
    (cond
      (nil? root) (source-provenance-error unit)
      (seq path) (list 'seon.render.data/pull-at
                       (list 'quote '[*]) root cursor)
      :else (list 'seon.db/pull (list 'quote '[*]) root))))

(defn render-default-ai-source
  "Return authored source that reproduces a value for terminal rendering."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :seon.render/source :seon.render/request-error :seon.db/error-result]]}
  [unit]
  (let [form (render-form unit)]
    (if (:seon.render/refused-member form)
      form
      (pr-str form))))

(defn render-form-value
  "Render one value as the Clojure form that reads it."
  {:malli/schema [:=> [:cat :seon.render/call-request]
                  [:or :seon.render/form :seon.render/error-result]]}
  [request]
  (let [profile (request-profile request)]
    (if (:seon.render/refused-member profile)
      profile
      (let [request (assoc request :seon.render/profile profile)
            projection (request-projection request)
            rendered (invoke-producer request :seon.render/form
                                      :seon.render/form)]
        (if (valid-projection? projection :seon.render/form rendered)
          rendered
          (let [observation
                {:seon.error/fix "Inspect the supplied value or continue from the reported traversal subject."
                 :seon.error/at (Date.)
                 :seon.error/layer :seon.render/output
                 :seon.error/operation 'seon.render/render-form-value
                 :seon.render/invalid-output :form
                 :seon.error/message "The selected form renderer did not return a form."
                 :seon.error/data {:seon.render/output rendered}
                 :seon.error/expected "a value satisfying the requested render output"
                 :seon.error/offending rendered}]
            observation))))))

(defn render-call
  "Reuse one retained projection while its input, code, and reads are current."
  {:malli/schema [:=> [:cat :seon.render/call-request]
                  [:or :nil :string :seon.render/hiccup
                   :seon.render/form :seon.render/error-result]]}
  [{database :seon.db/db
    output :seon.render/output
    call-id :seon.render.call/id
    retained-calls :seon.render/retained-calls
    captured-calls :seon.render/captured-calls
    candidate-call-ids :seon.render/candidate-call-ids
    :as request}]
  (let [profile (request-profile request)]
    (if (:seon.render/refused-member profile)
      profile
      (let [request (assoc request :seon.render/profile profile)
            previous (when (and call-id retained-calls)
                       (get retained-calls call-id))
            same-database? (and previous
                                (same-committed-database? database (:seon.db/db previous)))
            check-read-evidence?
            (and (not same-database?)
                 (or (nil? candidate-call-ids)
                     (contains? candidate-call-ids call-id)))
            previous-selected
            (get-in previous [:seon.render.call/static-evidence
                              :seon.render.call/producer])
            fast-evidence (when previous-selected
                            (call-cache-evidence request previous-selected))
            fast-reusable?
            (and previous
                 (same-call-cache-evidence? previous fast-evidence)
                 (or (not check-read-evidence?)
                     (db/read-evidence-current?
                      database (:seon.render.call/read-evidence previous))))]
        (if fast-reusable?
          (let [entry (merge (if check-read-evidence?
                               (refresh-read-evidence database previous)
                               previous)
                             fast-evidence)]
            (when (and call-id captured-calls)
              (swap! captured-calls assoc call-id entry))
            (:seon.render.call/output previous))
          (let [requested-candidate
                (:seon.render.call/selected-producer request)
                decision (or (:seon.render/selection-inspection request)
                             (if requested-candidate
                               (selection-inspection request)
                               (selection request)))
                selected
                (if requested-candidate
                  (if (compatible-selection-candidate? decision
                                                       requested-candidate)
                    requested-candidate
                    (let [observation
                          (seon.error.refusal/diagnostic (java.util.Date.) :seon.render/render 'seon.render/render-call
                           {:seon.error/message "The requested renderer is not an applicable candidate."
                           :seon.error/fix "Supply the expected member and repeat the requested operation."
                           :seon.render/refused-member :seon.render.call/selected-producer
                           :seon.error/expected :compatible
                           :seon.error/offending requested-candidate
                           :seon.render/selection-observation decision})]
                      observation))
                  (:seon.render.selection/selected decision))]
            (if (or (:seon.render/refused-member selected) (:seon.render/candidates selected) (:seon.render.unknown/reason selected))
              (assoc selected :seon.render/refused-member :seon.render.call/selected-producer)
              (let [static-evidence (call-static-evidence request decision
                                                          selected)
                    cache-evidence (call-cache-evidence request selected)
                    invocation-key (invocation-cache-key request selected
                                                         cache-evidence)
                    retained-invocations (:seon.render/invocations request)
                    captured-invocations
                    (:seon.render/captured-invocations request)
                    retained-invocation
                    (reusable-invocation database
                                         (get retained-invocations
                                              invocation-key)
                                         cache-evidence)
                    reusable? (and previous
                                   (same-call-cache-evidence?
                                    previous cache-evidence)
                                   (= static-evidence
                                      (:seon.render.call/static-evidence
                                       previous))
                                   (or (not check-read-evidence?)
                                       (db/read-evidence-current?
                                        database
                                        (:seon.render.call/read-evidence
                                         previous))))
                    captured (atom [])
                    invocation-reusable? (some? retained-invocation)
                    ;; Source intent is read against the argument the producer
                    ;; will actually receive: an attribute-declared producer
                    ;; takes the attribute's value, not the unit map.
                    source-output?
                    (and (= output :seon.render/ai)
                         (let [projection (request-projection request)]
                           (source-producer?
                            projection selected
                            [(render-invocation-argument projection request
                                                         selected)])))
                    raw (if invocation-reusable?
                          (or (when (:seon.render/source-blocks retained-invocation)
                                (select-keys retained-invocation [:seon.render/source-blocks]))
                              (:seon.render.call/source retained-invocation)
                              (:seon.render.call/output retained-invocation))
                          (raw-output
                           (assoc request
                                  :seon.render.call/selected-producer selected
                                  :seon.render.call/captured-reads captured)
                           output selected))
                    raw (if (or (:seon.render/refused-member raw)
                                (:seon.render.unknown/reason raw)
                                (:seon.render/invalid-output raw))
                          (assoc raw :seon.render/refused-member :seon.render/output)
                          raw)
                    source-blocks (when source-output? (:seon.render/source-blocks raw))
                    raw (if source-blocks
                          (clojure.string/join "\n\n" (map :seon.render/source source-blocks)) raw)
                    authored-source? (and source-output? (string? raw))
                    invocation-entry
                    (merge
                     (if invocation-reusable?
                       (refresh-read-evidence database retained-invocation)
                       (cond->
                        {:seon.render.call/read-evidence
                        (db/read-evidence
                         @captured {:seon.db/retain-read-results? true})
                        :seon.render.call/basis-transaction
                        (db/basis-t database)
                         :seon.render.call/output
                         (when-not source-output? raw)}
                         authored-source?
                         (assoc :seon.render.call/source raw)
                         source-blocks
                         (assoc :seon.render/source-blocks source-blocks)

                         (:seon.render.call/source-run-id request)
                         (assoc :seon.render.call/source-run-id
                                (:seon.render.call/source-run-id request))))
                     cache-evidence)
                    _ (retain-invocation!
                       captured-invocations invocation-key
                       (get retained-invocations invocation-key)
                       invocation-entry)
                    rendered (if authored-source?
                               (if captured-invocations
                                 (:seon.render.call/output invocation-entry)
                                 raw)
                               (if reusable?
                               (:seon.render.call/output previous)
                               raw))
                    entry (assoc
                           (merge
                            (when reusable?
                              (if check-read-evidence?
                                (refresh-read-evidence database previous)
                                previous))
                            invocation-entry)
                           :seon.render.call/static-evidence static-evidence
                           :seon.render.call/output rendered
                           :seon.render.call/invocation-key invocation-key)]
            (when (and call-id captured-calls)
              (swap! captured-calls assoc call-id entry))
            ;; Carry cost facts with the captured call. Context acquisition
            ;; commits the complete set once, after deriving from its DB value.
            ;; Render cost serves the agent-context consumer. A real prompt
            ;; request structurally carries the held run id through
            ;; the turn caller; web page, root, and debug renders do not. They
            ;; still retain call evidence, but a read-only page observation
            ;; must never transact.
            (when (and (not reusable?)
                       (not invocation-reusable?)
                       (some? rendered)
                       (not= rendered (:seon.render.call/output previous))
                       call-id
                       captured-calls
                       (:seon.turn/id request)
                       (:seon.db/connection request))
              (swap! captured-calls assoc-in [call-id :seon.db/tx-data]
                     [(render-cost-fact request rendered)]))
                rendered))))))))

(def ^:private branch-scope-keys
  [:datahike.cache/connection-id :datahike.cache/generation])

(defn branch-scope
  "The Datahike connection a ctx derives render values in, as Datahike names it.

  `:datahike.cache/connection-id` is `[store-id branch]` and
  `:datahike.cache/generation` is that connection's opening
  (`reference-code/datahike/src/datahike/store.cljc:44`,
  `connector.cljc:376`). A branch unlinked and reopened under the same name
  is a new generation, so it shares nothing with the old one. A speculative
  value (a `with` result, a transaction function's argument) keeps its
  basis's connection and generation (`datahike.db/speculative-cache-context`,
  `reference-code/datahike/src/datahike/db.cljc:444`), so it derives in its
  branch's cache, where every entry is validated by its read evidence and a
  speculative value never proves a committed read. A detached value (no
  context) has an empty scope."
  {:malli/schema
   [:=> [:cat :seon.sci.eval/ctx]
    [:map
     [:datahike.cache/connection-id {:optional true}
      [:vector {:min 2} [:or :uuid :string :keyword]]]
     [:datahike.cache/generation {:optional true} :uuid]]]}
  [ctx]
  (let [custody (:seon.sci.eval/custody ctx)
        database (if-let [connection (:seon.db/connection custody)]
                   @connection
                   (:seon.db/db custody))]
    (select-keys (:cache-context database) branch-scope-keys)))

(defn shared-cache
  "The render evidence cache of the branch this ctx derives in.

  The cache rides the context's environment reference, and a `sci/fork` onto
  another branch copies that environment (`seon.sci.eval/fork-cluster-ctx`).
  The cache therefore records the branch scope it was derived in, and a ctx
  whose `branch-scope` differs never reads or writes it: it allocates its own
  cache in its own environment reference. A fixture branch cannot replace the
  parent's `::packages`, and the fork's entries die with its context. Forks
  on the same branch share one cache. No registry beside the context holds
  it. Publication never holds a lock while deriving."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx] :seon.render/cache]}
  [ctx]
  (let [state (:seon.sci.eval/projection-state ctx)
        scope (branch-scope ctx)
        own? (fn [cache]
               (and cache (= scope (select-keys @cache branch-scope-keys))))
        current (:seon.render/cache @state)]
    (if (own? current)
      current
      (:seon.render/cache
       (swap! state #(if (own? (:seon.render/cache %))
                       %
                       (assoc % :seon.render/cache (atom scope))))))))

(defn acquire-context!
  "Fold saved shown text into the agent's context.

  With :seon.turn/id, return context as of that turn's opening: what its
  attempt saw. Without it, return current context from every stored
  evaluation, including the latest turn's results. Later replies cannot
  enter an earlier prompt merely because the caller supplies today's db."
  {:malli/schema [:=> [:cat :seon.render/context-request]
                  [:or :seon.render/acquired-context
                   :seon.render/context-change-result :seon.render.web/context-error]]}
  [request]
  (if (:seon.render/context-action request)
    (@render-web-derive-context! request)
    (let [database (:seon.db/db request)
          turn-id (:seon.turn/id request)
          basis (if turn-id
                  (@turn-opening-db database turn-id)
                  database)]
      (if (or (:seon.db/invalid-read basis) (:seon.turn/missing-opening-datom basis))
        (assoc basis :seon.render.web/refused-member :seon.turn/opened-tx)
        (@render-web-derive-context! (assoc request :seon.db/db basis))))))


(defn- namespace-owner
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.render/namespace]
                  [:or :nil :seon.agent/id :seon.db/error-result]]}
  [database namespace-name]
  (db/q '[:find ?agent-id .
          :in $ ?namespace-name
          :where
          [?namespace :seon.ns/name ?namespace-name]
          [?agent :seon.agent/namespace ?namespace]
          [?agent :seon.agent/id ?agent-id]]
        database namespace-name))

(defn renderer-failure
  "Prepare one refused render's OUTPUT values and its owner message.

  Both audiences read the SAME typed unknown, so the page and the prompt say
  the same thing about the same absence: `unknown-ai` names the declared-schema and stable observation for
  the agent, `unknown-html` the labeled block for a person. The anonymous
  sentence `Renderer unavailable.` that used to stand here named neither the
  producer nor why it stopped, so it taught the reader nothing and hid which
  renderer broke (§2.4).

  The namespace owner, when one is explicitly assigned, receives one durable
  message carrying the internal evidence with a fresh event id.
  An agentless namespace has no
  queryable stakeholders yet, so its transaction data is empty rather than
  guessed."
  {:malli/schema [:=> [:cat :seon.render/failure-request]
                  :seon.render/failure]}
  [{database :seon.db/db
    namespace-name :seon.render/namespace
    failure :seon.error/value}]
  (let [unavailable (refused failure)
        owner (namespace-owner database namespace-name)
        message-id (id/id (random-uuid) 8)
        message
        (str "A renderer in " namespace-name " failed. "
             (:seon.error/message unavailable)
             " " (unknown-ai {:seon.render/value unavailable})
             " Inspect the render failure and repair its declared contract.")]
    {:seon.render/ai (unknown-ai {:seon.render/value unavailable})
     :seon.render/html (unknown-html {:seon.render/value unavailable})
     :seon.db/tx-data
     (cond-> []
       owner
       (conj
        {:seon.message/id message-id
         :seon.message/to [:seon.agent/id owner]
         :seon.message/content message}))}))

;;; ---------------------------------------------------------------------------
;;; Ambient walk custody
;;; ---------------------------------------------------------------------------

(def ^:dynamic ^:private *walk-context* nil)

(defn call-with-walk-context
  "Call `body` with one agent's ambient walk custody."
  {:malli/schema
   [:=> [:catn [:seon.render.walk/context [:map [:seon.agent/id :seon.agent/id] [:seon.db/db {:optional true} :seon.db/database-value] [:seon.sci.admit/caps {:optional true} :seon.sci.admit/caps] [:seon.sci.eval/ctx {:optional true} :seon.sci.eval/ctx] [:seon.sci.eval/time-limit-ms {:optional true} :seon.sci.eval/time-limit-ms] [:seon.config/on-core-error {:optional true} :seon.config/on-core-error] [:seon.db/connection {:optional true} :seon.db/connection]]] [:seon.render.walk/body [:fn clojure.core/ifn?]]] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds.", :gen/elements [nil false 0 "" :k [] {}]}]]}
  [context body]
  (binding [*walk-context* context
            db/*conn*
            (or (:seon.db/connection context)
                db/*conn*)]
    (body)))

(defn- walk-error

  {:malli/schema [:=> [:cat :string] :string]}
  [message]
  (pr-str
   (let [observation
         {:seon.error/at (java.util.Date.)
          :seon.error/layer :seon.render/render
          :seon.error/operation 'seon.render/walk-error
          :seon.error/message message
          :seon.error/fix "Supply the expected member and repeat the requested operation."
          :seon.render/walk-operation 'seon.render/walk-error
          :seon.error/member :seon.render.walk/context
          :seon.error/expected "available walk custody"}]
     observation)))

(defn- ambient-database-value

  {:malli/schema [:=> [:cat] [:or :seon.db/database-value :seon.db/error-result]]}
  []
  (or (:seon.db/db *walk-context*)
      (db/db)))

(defn- repl-state
  [db agent-id]
  (let [basis (db/basis-t db)
        namespace-name
        (db/q '[:find ?name .
               :in $ ?agent-id
               :where
               [?agent :seon.agent/id ?agent-id]
               [?agent :seon.agent/namespace ?namespace]
               [?namespace :seon.ns/name ?name]]
             db agent-id)
        instant (:db/txInstant (db/pull db [:db/txInstant] basis))]
    {:seon.ns/name namespace-name
     :seon.render.history/basis-transaction basis
     :db/txInstant instant}))

(defn- selected-walk-units
  [units branch]
  (if (nil? branch)
    units
    (into []
          (filter
           (fn [unit]
             (let [path (:seon.render.walk/path unit)]
               (and (<= (count branch) (count path))
                    (= branch (subvec path 0 (count branch)))))))
          units)))

(defn walk
  "Return the calling agent's labeled database walk as text.

  With no arguments, root is the agent whose held run supplied this eval's
  custody, depth is 2, and the database value is dereferenced here from that
  cluster's live branch connection. Prompt assembly binds its exact immutable
  database value and calls this same function. `:branch` is a labeled PATH
  from the output and restricts the result to that `get-in` subtree.

  Failures are text the agent can inspect; this boundary never throws."
  {:malli/schema
   [:function
    [:=> [:cat] :string]
    [:=>
     [:cat
      [:map
       [:root {:optional true} :seon.render.walk/lookup]
       [:depth {:optional true} [:int {:min 0}]]
       [:branch
        {:optional true}
        [:vector [:or :keyword :int]]]]]
     :string]]}
  ([]
   (walk {}))
  ([options]
   (try
     (let [db (ambient-database-value)
           agent-id (:seon.agent/id *walk-context*)]
       (cond
         (nil? db)
         (walk-error "No live cluster database is bound to this evaluation.")

         (nil? agent-id)
         (walk-error "No calling agent is bound to this evaluation.")

         :else
         (let [cluster-name (:seon.boot/cluster-name
                             (env/of (:seon.sci.eval/ctx *walk-context*)))
               effective (when cluster-name
                           (config/effective db cluster-name))
               caps (or (:seon.sci.admit/caps *walk-context*)
                        (some-> effective config/result-caps))
               profile (when (and effective (not (:seon.error/at effective)))
                         (agent-render-profile effective))]
           (cond
             (:seon.error/at effective)
             (pr-str effective)

             (or (empty? caps) (some nil? (vals caps)))
             (walk-error
              (if cluster-name
                (str "Cluster " (pr-str cluster-name)
                     " has no complete render caps.")
                (str "Agent " (pr-str agent-id)
                     " has neither ambient render caps nor a context "
                     "environment naming its cluster.")))

             :else
             (let [root (get options :root
                             [:seon.agent/id agent-id])
                   depth (long (get options :depth 2))
                   branch (:branch options)
                   units
                   (@render-walk-neighborhood
                    (cond->
                     {:seon.db/db db
                      :seon.sci.eval/ctx (:seon.sci.eval/ctx *walk-context*)
                      :seon.agent/id agent-id
                      :seon.render.walk/lookup root
                      :seon.render/output :seon.render/ai
                      :seon.render/distance depth
                      :seon.sci.admit/caps caps
                      :seon.sci.eval/time-limit-ms
                      (:seon.sci.eval/time-limit-ms *walk-context*)
                      :seon.config/on-core-error
                      (:seon.config/on-core-error *walk-context*)}
                      (:seon.turn/id *walk-context*)
                      (assoc :seon.turn/id (:seon.turn/id *walk-context*))
                      (:seon.render/retained-calls *walk-context*)
                      (assoc :seon.render/retained-calls
                             (:seon.render/retained-calls *walk-context*))
                      (:seon.render/captured-calls *walk-context*)
                      (assoc :seon.render/captured-calls
                             (:seon.render/captured-calls *walk-context*))
                      profile (assoc :seon.render/profile profile)))
                   selected (selected-walk-units units branch)]
               (if (and branch (empty? selected))
                 (walk-error (str "No walk branch exists at "
                                  (pr-str branch) "."))
                 (pr-str
                  (cond->
                   {:seon.render.walk/lookup root
                    :seon.render/distance depth
                    :seon.render.walk/units selected
                    :seon.render/value (repl-state db agent-id)}
                    branch
                    (assoc :seon.render.walk/branch branch)))))))))
     (catch Throwable failure
       (walk-error (str "Walk failed: "
                        (or (ex-message failure)
                            (.getName (class failure)))))))))
