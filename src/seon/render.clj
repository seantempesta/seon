(ns seon.render
  "Render selection and the one render call (W1's proto.render and proto.render.schema,
  ported onto next's registry: the session design papers in git history).

  A render is a plain function (opts & children) -> its result, Reagent's form: opts are
  its props, all optional (:as, the room it is shown in, :inline, :block or :tile; :budget,
  its tokens; :handle, the form its cuts read the rest through), and a function that does not
  care about one ignores it (owner: \"It's all just functions and results\"). A render answers
  hiccup (serialized by seon.web.html, where a vector headed by a function is a call, expanded
  as Reagent does), and the page and the model read the same hiccup (docs/render.md
  rev42.md §2). Selection, first match wins, and nothing else (DECISIONS, Renders attach to
  schemas; the UI and the context are renders, ported not reinvented):
    1. the value's own override key (:seon.render/hiccup, in the map or its metadata): a render
       symbol (the value rendered itself, never one nested in another: there it is a schema's
       properties, data), or already-rendered hiccup;
    2. the render its schema declares: a schema's property {:seon.render/hiccup sym} in
       resources/seon/schemas/*.edn (seon.contract/forms), the
       schema then its parents ([:and p ...], [:merge p ...]). The value's schema is never
       guessed: the producer names it (opts :schema, or the value's metadata
       :seon.render/schema), an error map's (one holding every key :seon.error/base requires)
       is its :seon.error/type (else :seon.error/base), and a pulled entity's is the schema with a declared render holding its identity
       attribute;
    3. the one value printer (seon.print/print-value), the default render.
  A nested value renders by a call ([render opts v] in hiccup, or render's result)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.db :as pdb :refer [parents own-keys extends? most-specific]] [seon.program :as program] [seon.print :as print] [seon.print.select :as select] [seon.config :as config]
            [clojure.string :as str] [clojure.walk] [datahike.api :as d] [datahike.reads :as reads] [datahike.db.utils :as dbu] [seon.web.html :as h] [seon.ratom :as ratom] [seon.profile :as profile] [editscript.core :as ed]))

(defn props
  "The properties of named schema k's form in the entity index ix (its :forms, the binary's with a branch's over them), or nil:
  the one place a render lookup reads a schema's properties, so recognition and the render it names read one index. See
  docs/render.md#one-rendering-system-drawn-from-the-data."
  {:malli/schema [:=> [:cat :map [:maybe :qualified-keyword]] [:maybe :map]]}
  [ix k] (let [f (get (:forms ix) k)] (when (and (vector? f) (map? (second f))) (second f))))

(defn declared
  "The render symbol schema k (or its nearest parent, in the entity index ix: seon.db/index-of) declares, or nil."
  {:malli/schema [:=> [:cat :map [:maybe :qualified-keyword]] [:maybe :symbol]]}
  [ix k]
  (when k
    (or (:seon.render/hiccup (props ix k))
        (some #(declared ix %) (parents ix k)))))

(defn model-declared
  "The model render symbol schema k (or its nearest parent, in the entity index ix) declares, {:seon.render/model sym}: what the printer shows of a
  value of that schema (docs/render.md#a-render-is-a-value-printed-once), or nil (the value itself)."
  {:malli/schema [:=> [:cat :map [:maybe :qualified-keyword]] [:maybe :symbol]]}
  [ix k]
  (when k
    (or (:seon.render/model (props ix k))
        (some #(model-declared ix %) (parents ix k)))))

(defn full-declared?
  "Does schema k declare its values print whole, {:seon.print/full true}: a value that is a page (docs/render.md#a-part-prints-as-its-render)?"
  {:malli/schema [:=> [:cat [:maybe :qualified-keyword]] :boolean]}
  [k] (boolean (and k (:seon.print/full (contract/properties k)))))

(defn- reference-value
  "The identity value v stands for when v is a reference, else nil: a map of one identity attribute (the entity index ix's
  :identity-schemas) and at most its :db/id, so two keys at most, which a caller checks before it asks (a message, three keys or more,
  costs no call), as a pull joins a ref (a message's sender {:seon.agent/key \"user\"}). The printer's one
  rule for it, read by both its hooks (`model` for text, `nested` for the page): a reference prints as its identity value, so what is
  read of a message's sender is the name it was before senders were refs, and the data stays a ref. See docs/render.md#a-render-is-a-value-printed-once."
  {:malli/schema [:=> [:cat :map :any] :any]}
  [ix v]
  (when (and (map? v) (<= 1 (count v) 2) (every? #(or (= :db/id %) (contains? (:identity-schemas ix) %)) (keys v)))
    (some (fn [[k x]] (when (not= :db/id k) x)) v)))

(defn model
  "What the printer shows in place of value v (seon.print/print-value's :render): {:value m} when v's schema (its metadata
  :seon.render/schema) declares a model render (`model-declared`), which makes a value
  (docs/render.md#a-render-is-a-value-printed-once) that the one printer then prints, at its budget, with its own cuts; else
  nil, for one metadata read. The render is called (f opts v). ix is the entity index the schema's parents are read in
  (seon.db/index-of): a caller printing through the printer closes over its own, #(model ix %). See docs/render.md#a-render-is-a-value-printed-once."
  {:malli/schema [:function [:=> [:cat :map :any] [:maybe [:map [:value :any]]]] [:=> [:cat :map :any :map] [:maybe [:map [:value :any]]]]]}
  ([ix v] (model ix v {}))
  ([ix v opts]
   (let [k (:seon.render/schema (meta v)) sym (model-declared ix k) full? (full-declared? k)]
     (if-some [r (when (and (nil? k) (map? v) (<= (count v) 2)) (reference-value ix v))]
       ;; a reference prints as its identity value (`reference-value`)
       {:value r}
     (when (or sym full?)
       ;; a schema that declares no render shows the value itself, no longer named by it (the printer would draw it by this again)
       (cond-> {:value (if sym ((requiring-resolve sym) opts v) (vary-meta v dissoc :seon.render/schema))} full? (assoc :full true)))))))

(defn stored-model
  "`model` of a stored value, an eval's record: nil when it no longer fits the schema it kept, so an old record prints as the data it is. See docs/render.md#the-page."
  {:malli/schema [:=> [:cat :map :any] [:maybe [:map [:value :any]]]]}
  [ix v] (if (and (map? v) (<= (count v) 2) (nil? (:seon.render/schema (meta v))))
           ;; a reference prints as its identity value (`reference-value`), in an eval's text as on the page
           (model ix v)
           (when (some-> (:seon.render/schema (meta v)) (contract/fits? v)) (model ix v))))

(def ^:private error-keys
  "The keys :seon.error/base requires, read once from its form at load: what schema-of asks of a
  map before it draws it as an error. See docs/render.md#render-what-a-person-or-a-model-reads."
  (vec (keep #(when (and (vector? %) (not (:optional (when (map? (second %)) (second %))))) (first %))
             (rest (some #(when (and (vector? %) (= :map (first %))) %) (get contract/forms :seon.error/base))))))

(defn schema-of
  "The named schema of v: hint (its producer's), the one its metadata names, an error map's
  variant (error-keys), or the schema its identity attribute names in the entity index ix (one that declares a render). See docs/render.md#render-what-a-person-or-a-model-reads."
  {:malli/schema [:=> [:cat :map :any [:maybe :qualified-keyword]] [:maybe :qualified-keyword]]}
  [ix v hint]
  (or hint (:seon.render/schema (meta v))
      (when (map? v)
        (if-let [t (when (every? #(contains? v %) error-keys) (:seon.error/type v))]
          (if (contains? contract/forms t) t :seon.error/base)
          ;; what the map is, as the write check reads it (the best match of the attributes it holds); one that names no one
          ;; schema with a render is drawn as the data it is. One owning schema, the common case, is a lookup a key and no call
          (let [ss (set (vals (pdb/owners ix (keys v))))
                s (if (next ss) (most-specific ix v) (first ss))]
            (when (contains? (:drawn-schemas ix) s) s))))))

(declare render-of own-key)

(defn- nested
  "The printer's :render: a nested value whose schema declares a render, as a call of that render
  inline ({:text, the value in one line; :html, the call}), else nil. Its render is looked up by
  render-of (seon.render/schema-of: one metadata read or one map lookup per key, never a
  validation). A nested map holding a render symbol under :seon.render/hiccup is data, printed as it
  is: it is what a schema's properties look like inside a printed schema form, and drawing it by
  that render called the render with the properties (an agent's registered :lab.ctx/steps form,
  its lab.ui/steps called with {:seon.render/hiccup lab.ui/steps}, killed the agent drawing it). A reference
  (`reference-value`: a map of identity attributes alone, a ref as a pull joins it) prints as its identity value, in the
  line of the value holding it and alone, never drawn by its schema's render: what is read of a message's sender is the
  name it was before senders were refs (:from \"user\"), and the data stays a ref."
  {:malli/schema [:=> [:cat :map :any] [:maybe [:map [:text :string] [:html :any]]]]}
  [ix v]
  ;; a reference prints as its identity value (`reference-value`, the printer's one rule), asked only of a map
  (if-some [r (when (and (map? v) (<= (count v) 2)) (reference-value ix v))]
    {:text (print/line r (config/value :seon.config.render/line-budget)) :html (str r)}
    (when-let [h (when-not (symbol? (own-key v :seon.render/hiccup)) (render-of ix v nil))]
      {:text (print/line (if (map? v) (into {} (map (fn [[k x]] [k (if-some [r (when (and (map? x) (<= (count x) 2)) (reference-value ix x))] r x)])) v) v)
                         (config/value :seon.config.render/line-budget))
       :html [h {:as :inline :seon.render/index ix} v]})))

(defn- nested-hiccup
  "The hiccup printer's :render: nested's, and a value whose own :seon.render/hiccup key holds hiccup
  (a page an agent built) as that hiccup, its text the hiccup as data in one line: one selection
  serves the page and the model, and the model reads the hiccup as the data it is."
  {:malli/schema [:=> [:cat :map :any] [:maybe [:map [:text :string] [:html :any]]]]}
  [ix v]
  (let [own (own-key v :seon.render/hiccup)]
    (if (and (some? own) (not (symbol? own)))
      {:text (print/line own (config/value :seon.config.render/line-budget)) :html own}
      (nested ix v))))

(defn- printer-opts
  "The printer's options for a render's opts: its budget (else :seon.config.render/value-budget),
  its handle, and its nested values' own renders (nested)."
  {:malli/schema [:=> [:cat :map :seon.render/opts] :map]}
  [ix opts] {:budget (or (:budget opts) (config/value :seon.config.render/value-budget)) :handle (:handle opts) :render #(nested ix %)})

(defn print-hiccup
  "The one value printer as the default hiccup render: v's structural hiccup under opts' budget,
  or one line (seon.print/line) when shown :inline. Its nested values' renders are looked up in the entity index opts holds
  (:seon.render/index: `hiccup` passes its ix)."
  {:malli/schema [:=> [:cat :seon.render/opts :any] :any]}
  [opts v]
  (if (= :inline (:as opts))
    [:code {:class "seon-inline"} (print/line v (or (:budget opts) (config/value :seon.config.render/line-budget)) (:handle opts))]
    (let [ix (:seon.render/index opts)
          r (print/print-value v (assoc (printer-opts ix opts) :outputs #{:hiccup} :render #(nested-hiccup ix %)))]
      (or (:html r) [:span {:class "seon-print-refused"} (str (:seon.error/message r))]))))

(defn- own-key
  "v's own override for face: in the map, else in its metadata."
  {:malli/schema [:=> [:cat :any :keyword] :any]}
  [v k] (or (when (map? v) (get v k)) (get (meta v) k)))

(declare inputs)

(defn render-of
  "The render function v takes, as the entity index ix reads schemas: its own override (a symbol), else its schema's declared one (opts :schema, else seon.render/schema-of: one metadata read or one map lookup
  per key, never a validation), else nil (the printer). nil also when v's own key holds an
  already rendered result."
  {:malli/schema [:=> [:cat :map :any [:maybe :qualified-keyword]] [:maybe :seon.host/fn]]}
  [ix v hint]
  (let [own (own-key v :seon.render/hiccup)]
    (if (symbol? own)
      ;; a value's own says what it was drawn by where it was made: an agent's definition no other process holds, drawn as the data it is there (nil, the printer)
      (some-> (when-let [n (some-> (namespace own) symbol find-ns)] (ns-resolve n (symbol (name own)))) deref)
      (when-let [sym (when (nil? own) (declared ix (schema-of ix v hint)))]
        ;; a render that reads the walk's ctx (an entity's page: an agent's, root's) renders the entity a walk is rooted on, not a value
        ;; an eval answered (a pulled agent, an entity: only a map with its :db/id is asked, so a plain value costs no lookup): that value
        ;; prints as the data it is
        (let [f (requiring-resolve sym)] (when-not (and (map? v) (:db/id v) (contains? (inputs f) :seon.ctx/ctx)) @f))))))

(defn declared-var
  "The var of the render schema k declares (its nearest parent's), or nil: a layout the walk draws
  is a component, and its var's contract says what it reads."
  {:malli/schema [:=> [:cat :map :qualified-keyword] :any]}
  [ix k] (some-> (declared ix k) requiring-resolve))

(defn render-for
  "The render symbol schema k (or its nearest parent, in the entity index ix) names for output o, a property of the schema
  (:seon.render/hiccup, data to hiccup; :seon.render/openai-compat, an entity to the model's messages), or nil. See
  docs/render.md#one-rendering-system-drawn-from-the-data."
  {:malli/schema [:=> [:cat :map [:maybe :qualified-keyword] :qualified-keyword] [:maybe :symbol]]}
  [ix k o]
  (when k (or (get (props ix k) o) (some #(render-for ix % o) (parents ix k)))))

(defn entity-render
  "The component call x, a view of a pulled entity, is run as: [f opts], f the hiccup render var of its schema as the entity index ix
  reads it (`schema-of`), opts' :key its identity value (so its element id names what it shows, never where the pull held it) and
  :seon.render/nested true (a parent placed it: a page's render answers it with the entity's entry), or nil for any other value. See
  docs/render.md#one-rendering-system-drawn-from-the-data."
  {:malli/schema [:=> [:cat :map :any] :any]}
  [ix x]
  (when (ratom/view? x)
    (let [v (ratom/untracked x)]
      (when (and (map? v) (:db/id v))
        (when-let [s (schema-of ix v nil)]
          (when-let [f (declared-var ix s)]
            [f (let [id (some #(get v %) (keep #(get (:schema-identities ix) %) (tree-seq (constantly true) #(parents ix %) s)))]
                 (cond-> {:seon.render/nested true} (some? id) (assoc :key id)))]))))))

(defn hiccup
  "v rendered: (render-fn opts v), the function its override or schema names, else the printer's
  (print-hiccup); a value's own key holding hiccup answers that hiccup. opts' :seon.render/index is the entity index its
  schemas are read in (seon.db/index-of), required: a walk's own, filled by `render!` and `emit`, passed on to the render it
  calls. Inside a walk, one render calling another is the interim stage 2a of docs/users.md removes."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.render/index :map]]] :any] :any]}
  [opts v]
  (let [own (own-key v :seon.render/hiccup) ix (:seon.render/index opts)]
    (if (and (some? own) (not (symbol? own)))
      own
      ;; a producer's schema a stored value no longer fits: it is drawn as the data it is
      ((or (when (or (nil? (:schema opts)) (contract/fits? (:schema opts) v)) (render-of ix v (:schema opts))) print-hiccup) opts v))))

;;; --- the walk: a render of components (docs/render.md#components) -------------------------------

(defn- opts-form
  "The opts schema form of render var v's contract (its first argument), or nil."
  {:malli/schema [:=> [:cat :any] :any]}
  [v]
  (when (var? v)
    (let [s (:malli/schema (meta v)) arity (if (= :function (first s)) (second s) s)]
      (when (vector? arity) (second (second arity))))))

(defn inputs
  "Which of :seon.ctx/ctx, :seon.db/db and :seon.render/drawn render var v's opts contract asks to receive: a render
  that declares any is a component, kept in the walk's cache, those keys filled in (`render!`)."
  {:malli/schema [:=> [:cat :any] [:set :qualified-keyword]]}
  [v] (into #{} (filter #{:seon.ctx/ctx :seon.db/db :seon.render/drawn}) (tree-seq vector? seq (opts-form v))))

(defn- inputs-in
  "The inputs render var f asks for (`inputs`), as walk w found them: kept by f with the value its root held then, so
  each call of a render in a draw costs a lookup, and a redefinition (a defn sets a new root with its new
  metadata) is read again; an alter-meta! that leaves the root is not seen until the walk is made again."
  {:malli/schema [:=> [:cat :map :any] [:set :qualified-keyword]]}
  [w f]
  (if-not (var? f)
    (inputs f)
    (let [v (var-get f) [v0 rs] (get @(:inputs w) f)]
      (if (and rs (identical? v v0)) rs (let [rs (inputs f)] (vswap! (:inputs w) assoc f [v rs]) rs)))))

(defn- child-key
  "A child as its component's key names it: a view by its path, an entity by its id, a name or
  number as itself, any other value by its hash."
  {:malli/schema [:=> [:cat :any] :any]}
  [x]
  (cond (ratom/view? x) (vec (.-path x))
        (and (map? x) (:db/id x)) (:db/id x)
        (or (keyword? x) (symbol? x) (string? x) (number? x) (nil? x) (boolean? x)) x
        :else (hash x)))

(defn comp-key
  "Component call [f opts & children]'s key: its var's name and opts' :key, Reagent's, the name of what it shows, else its children's keys.
  See docs/render.md#components."
  {:malli/schema [:=> [:cat :any :map [:sequential :any]] [:vector :any]]}
  [f opts children]
  (if-some [k (:key opts)]
    [(symbol f) k]
    (into [(symbol f)] (map child-key) children)))

(defn- id-safe
  "Text s as part of an element id, one to one: letters, digits and - as themselves, every other
  character _ and its code in hex (a.b/c -> a_2Eb_2Fc, a_b -> a_5Fb). A . is escaped too: #id selectors read it as a
  class (Datastar's patches select by #id). See docs/render.md#in-flight-output-streams."
  {:malli/schema [:=> [:cat :string] :string]}
  ;; one pass into one builder: a format a character cost 1k ticks and 10 KB an id (a component placed, 2026-11-10 profile)
  [s] (let [sb (StringBuilder.)]
        (dotimes [i (count s)]
          (let [c (.charAt ^String s i)]
            (if (or (Character/isLetterOrDigit c) (= \- c))
              (.append sb c)
              (let [h (Integer/toHexString (int c))] (.append sb "_") (when (< (count h) 2) (.append sb "0")) (.append sb (.toUpperCase ^String h))))))
        (.toString sb)))

(defn key-id
  "Key k as its component's root element id, one to one: its render's name and the name of what it shows (seon.render.ui/evaluation of eval e12 ->
  \"evaluation-e12\"; a var cited, seon.render.ui/cite of demo/g -> \"cite-demo_2Fg\"), never an entity id, since the model reads ids and does not guess
  them."
  {:malli/schema [:=> [:cat [:vector :any]] :string]}
  [k]
  (apply str (id-safe (name (first k)))
         (for [c (rest k)] (str "-" (id-safe (if (keyword? c) (str (namespace c) (when (namespace c) "/") (name c)) (str c)))))))

(defn walker
  "A walk of a render of ctx: its source (src, {:ref r :changes f}: the reference ctx is, seon.ctx/ctx, and its
  one diff, seon.ctx/changes), the db its db renders read (db-fn, called when one runs) and its
  root call. Holds, in this process, the one cache: each component's reaction, args, output and
  db reads ({key entry}), an index from read key to the components that read it, the marker of each form drawn, its render queue (the keys a change reached), what a flush's runs let go of (:gone), who left out a page's piece (:skipped, a walk :model-only), mount counter and ledger
  (calls, hits, misses and allocation by render var), and the render vars it watches: each it drew, once,
  whose redefinition (a defn at the REPL: Clojure's watch on a var's root) queues that var's components
  for the next flush!, found in the cache when it fires. It holds the entity index its renders read schemas in (:index,
  seon.db/index-of): the caller's ix when given (an agent passes its slot's: one copy, handed again by each flush! that is given
  one, never built again here), else built from its db here (:own-index?) and built again by a flush that moved a :schema/* datom
  (`flush!`), never otherwise. A cold walk (root drawing a branch) is a
  walker drawn once."
  {:malli/schema [:function [:=> [:cat :map :seon.host/fn [:vector :any]] :map] [:=> [:cat :map :seon.host/fn [:vector :any] :map] :map]]}
  ([src db-fn root] (assoc (walker src db-fn root (program/index-of-db (db-fn))) :own-index? true))
  ([src db-fn root ix]
  {:src src :db db-fn :root root :index (volatile! ix) :own-index? false :cache (volatile! {}) :queue (volatile! #{}) :mounts (volatile! 0)
   :ledger (volatile! {}) :out (volatile! nil) :db-keys (volatile! #{}) :readers (volatile! {}) :forms (volatile! {}) :drawn (ratom/atom {}) :watched (volatile! #{}) :inputs (volatile! {}) :gone (volatile! #{}) :skipped (volatile! #{}) :defects (volatile! [])}))

(defn- led!
  "Count k (:hit, :miss, :same) for render var f in w's ledger, with the ticks and bytes a run took."
  {:malli/schema [:=> [:cat :map :any :keyword [:maybe :int] [:maybe :int]] :nil]}
  [w f k ticks bytes]
  (vswap! (:ledger w) update (symbol f) #(-> (or % {}) (update k (fnil inc 0)) (update :ticks (fnil + 0) (or ticks 0)) (update :bytes (fnil + 0) (or bytes 0))))
  nil)

(declare emit unmount!)

(defn- css?
  "Whether a render's :seon.render/style is CSS rules rather than Tailwind classes. See docs/render.md#style."
  {:malli/schema [:=> [:cat [:maybe :string]] :boolean]}
  [style] (boolean (and style (str/includes? style "{"))))

(defn rules
  "The CSS rules of drawn, a walk's drawn renders, and of every other render of their namespaces (`css?`), [[render-name text] ...] by name: what is drawn decides which namespaces, not which rules, so a render's first draw, or a person's page asking for what only it draws, moves no rule. See docs/render.md#style."
  {:malli/schema [:=> [:cat [:map-of :any :any]] [:vector [:tuple :symbol :string]]]}
  [drawn]
  (let [vs (into (set (filter var? (keys drawn))) (comp (filter var?) (map (comp ns-name :ns meta)) (distinct) (mapcat (comp vals ns-publics))) (keys drawn))]
    (vec (keep (fn [f] (let [s (:seon.render/style (meta f))] (when (css? s) [(symbol f) s]))) (sort-by symbol vs)))))

(defn- with-id
  "Hiccup hc with its root element's attributes merged with attrs: a component's key as its id and
  its render's name as its first class, then any class the render gave its root (a root's classes were
  once replaced, so a layout written on a root silently did nothing: a page's messages touched; the model
  reads no class, seon.render's strip-attrs). Its var's :seon.render/style is added when its html is
  written (`styled`). A render that drew nothing (nil) stays nothing: no element to hold it, so no part
  is placed for it (docs/render.md#a-component-answers-hiccup)."
  {:malli/schema [:=> [:cat :any :map] :any]}
  [hc attrs]
  (cond
    (nil? hc) nil
    (and (vector? hc) (keyword? (first hc)))
    (let [[tag & more] hc
          own (when (map? (first more)) (:class (first more)))
          attrs (cond-> attrs (and (:class attrs) (string? own) (not (str/blank? own))) (update :class str " " own))]
      (if (map? (first more)) (into [tag (merge (first more) attrs)] (rest more)) (into [tag attrs] more)))
    :else [:div attrs hc]))

(defn- forget-readers!
  "Component k out of walk w's index of readers, for the read keys it held. See docs/render.md#components."
  {:malli/schema [:=> [:cat :map [:vector :any] [:maybe [:set :any]]] :nil]}
  [w k read-keys]
  (doseq [rk read-keys] (vswap! (:readers w) update rk disj k)))


(declare entity-schema)

(defn render!
  "Component [f opts & children] drawn in walk w: its marker [::child key], its output kept in w's
  cache. Skipped when its children are = to last time's (views by source and path) and nothing it
  read changed: no reaction and no transaction (`flush!`, by what it read) queued it;
  otherwise run in its reaction (seon.ratom/run-in-reaction), opts filled with the view of ctx
  (:seon.ctx/ctx) and the db (:seon.db/db) it declares, a db render under d/with-reads, and its
  output drawn (emit) with its root element's id its key. An output = to last time's is the
  same (:same), and moves its revision only when it differs. A child it no longer holds is
  let go at the end of the flush (`flush!`), so one another parent drew stays. A render that throws draws its error's
  render in its place (docs/failure-points.md: a component's render); every other component draws."
  {:malli/schema [:=> [:cat :map :any :map [:sequential :any] :map] [:tuple [:= ::child] [:vector :any]]]}
  [w f opts children env]
  (let [k (comp-key f opts children) prev (get @(:cache w) k) ks (inputs-in w f)]
    (vswap! (:children env) conj k)
    (if (and prev (= opts (:opts prev)) (= (count children) (count (:args prev)))
             (every? true? (map ratom/view= children (:args prev)))
             (not (contains? @(:queue w) k)))
      (do (led! w f :hit nil nil) [::child k])
      (let [;; an entity rendered by its schema's render: the render its schema names for each other output the walk asks
            ;; (:outputs, data on the walk; render-for) runs in the same run, its reads tracked with the hiccup's; a walk that asks for
            ;; the page alone pays nothing here
            others (disj (or (:outputs w) #{}) :seon.render/hiccup)
            ix @(:index w)
            ;; the entity: the component's argument (a walk is rooted on [render {} view], so its root has one too)
            ev (when (seq others) (first children))
            ;; as of now: a view holds the value it was made with, and a root's argument is the view the walk was made with
            e0 (some-> ev ratom/fresh ratom/untracked)
            ;; an entity's schema by its identity attributes, never a value's metadata: an agent's @ctx carries :seon.ctx/ctx's, which
            ;; names no model render, so the agent's own went unasked
            s0 (when (and (map? e0) (:db/id e0)) (entity-schema ix e0 nil))
            ;; in the entity's own component alone, the one its schema's render runs (`declared-var`, the branch's own when it
            ;; redefines it): a part handed the same view ([#'agent-parts {} c]) draws a piece of the entity's page and answers no
            ;; output for it, so the model render runs once a walk, never once a part
            fs (when (and s0 (= f (declared-var ix s0))) (into {} (keep (fn [o] (some->> (render-for ix s0 o) requiring-resolve (vector o)))) others))
            ;; output o's answer: its values, and each entity or call it places run as a component (the one the hiccup placed is a
            ;; hit; a call answers o), kept as its marker, so the parent orders and each child's answer stays its own
            place (fn [o env' xs] (mapv (fn [x] (if-let [[ef eo] (entity-render ix x)]
                                                  ;; placed for output o: a component no page holds, so its own hiccup is drawn for no reader
                                                  (render! w ef eo [x] (cond-> env' (not= o :seon.render/hiccup) (assoc ::for o)))
                                                  (if (and (vector? x) (or (var? (first x)) (fn? (first x))))
                                                    (render! w (first x) (assoc (or (second x) {}) :seon.render/as o) (vec (nnext x)) env')
                                                    x)))
                                        xs))
            args (mapv ratom/fresh children)
            db? (or (contains? ks :seon.db/db) (seq fs))
            outs (volatile! nil)
            o (cond-> opts
                (contains? ks :seon.ctx/ctx) (assoc :seon.ctx/ctx (if (:once w) @(:ref (:src w)) (ratom/fresh (ratom/ref-view (:ref (:src w)) (:changes (:src w))))))
                db? (assoc :seon.db/db ((:db w)))
                true (assoc :seon.render/index @(:index w))
                (contains? ks :seon.render/drawn) (assoc :seon.render/drawn (:drawn w)))
            ;; a render drawn for the first time in this walk: watched, so its redefinition draws it again
            _ (when (and (var? f) (not (contains? @(:watched w) f)))
                (vswap! (:watched w) conj f)
                (swap! (:drawn w) assoc f (var-get f))
                (add-watch f (:watched w) (fn [_ _ _ new]
                                            (vswap! (:queue w) into (keep (fn [[ck c]] (when (= f (:f c)) ck))) @(:cache w))
                                            (swap! (:drawn w) assoc f new))))
            order (or (:order prev) (vswap! (:mounts w) inc))
            kids (volatile! [])
            id (key-id k)
            reads (volatile! nil)
            kept (volatile! nil)
            ;; its render and the drawing of its output in one: a lazy part of the hiccup reads (and
            ;; throws) only when it is drawn, and a db render's reads are all of what it drew
            one #(try (let [env' (assoc env :children kids :self k)
                            ;; an entity's component reads its identity tracked, so it runs again when the entity first appears (a walk made
                            ;; before its agent's entity is on the branch) and its model render is looked up then
                            _ (when (ratom/view? ev) (get ev :db/id))
                            ;; a call an output placed answers that output (and what it places), kept as this component's answer to it
                            as (:seon.render/as opts)
                            out (cond (::for env) nil
                                      as (do (vswap! outs assoc as (place as env' (apply f (dissoc o :seon.render/as) args))) nil)
                                      :else (with-id (emit w (let [raw (apply f o args)] (vreset! kept (:seon.render/kept (meta raw))) raw) env') {:id (or (when (var? f) (:seon.render/list (meta f))) id) :class (name (first k))}))]
                        ;; an entity's own render for each other output, where no call answers that output for it
                        ;; each in a run of its own at this edge (docs/failure-points.md, a component's render): a model render that throws
                        ;; is that output's defect, never the page's, which the person still reads; its error is data both see: a problem
                        ;; (health, take-defects!) and, in its place in the output, what the error schema's own render for that output answers
                        (doseq [[op of] fs :when (not= op as)]
                          (vswap! outs assoc op
                                  (try (place op env' (of {:seon.db/db ((:db w)) :seon.render/index ix} (or (first args) ev)))
                                       (catch Throwable e
                                         (when (:interrupted (ex-data e)) (throw e))
                                         (let [defect (contract/error :seon.render/component-defect #'render!
                                                                      (str (symbol of) " threw: " (or (ex-message e) (str e)))
                                                                      (cond-> {:seon.error/in (symbol of)} (map? (ex-data e)) (assoc :seon.render/threw (ex-data e))))]
                                           (vswap! (:defects w) conj defect)
                                           ;; the error answers this output by the render its schema names for it (a model render of an error, as
                                           ;; every model render is a schema's), else nothing beside the problem
                                           (if-let [ef (some-> (render-for ix :seon.render/component-defect op) requiring-resolve)]
                                             (place op env' (ef {:seon.db/db ((:db w)) :seon.render/index ix} defect))
                                             []))))))
                        out)
                      ;; a component that throws draws its error in its place: a call of the error's render on the error as data (what
                      ;; threw, and the thrown error's own data: a contract's problems), so the page writes that render and the model
                      ;; reads the call, the error in it, in the conversation a user message at its place; every other component draws
                      (catch Throwable e
                        ;; an interrupt is no defect of this render: it passes
                        (when (:interrupted (ex-data e)) (throw e))
                        (let [defect (contract/error :seon.render/component-defect #'render!
                                                     (str (symbol f) " threw: " (or (ex-message e) (str e)))
                                                     (cond-> {:seon.error/in (symbol f)}
                                                       (map? (ex-data e)) (assoc :seon.render/threw (ex-data e))))]
                          (vreset! kept nil)
                          ;; health sees it (`take-defects!`)
                          (vswap! (:defects w) conj defect)
                          (with-id [#'hiccup {:schema :seon.render/component-defect :as :block :seon.render/index @(:index w)} defect]
                                   {:id id :class "err" :wire/role "user"}))))
            draw #(if (and db? (not (:once w))) (let [[out rs] (d/with-reads one)] (vreset! reads rs) out) (one))
            a0 (profile/alloc-bytes)
            [out rx] (cond
                       ;; a walk read once and let go (`draw`'s :once) runs again for nothing: no reaction, no read index
                       (:once w) [(binding [ratom/*ratom-context* nil] (draw)) nil]
                       (:rx prev) (let [rx (:rx prev)] (ratom/-set! rx :f draw) [(ratom/-run rx false) rx])
                       :else (ratom/run-in-reaction draw k (fn [k] (vswap! (:queue w) conj k)) {}))
            ;; run: its closure holds the db and the ctx view of this run, which a kept walk has no use for (a kept db value is a reference: docs/storage.md#a-kept-db-value-is-a-reference); render! sets it anew
            _ (when rx (ratom/-set! rx :f (constantly nil)))
            bytes (- (profile/alloc-bytes) a0)
            ;; a child the hiccup and the model output both placed is held once
            children (if (seq others) (vec (distinct @kids)) @kids)
            same? (and prev (= out (:out prev)))
            read-keys (some-> @reads seq d/reads-index)]
        (vswap! (:queue w) disj k)
        ;; who holds each child, moved only when its children did: one that lost its last parent goes at the flush's end
        (when (not= (seq children) (seq (:children prev)))
          (let [was (set (:children prev)) now (set children)]
            (doseq [c now :when (not (was c))] (vswap! (:cache w) update-in [c :parents] (fnil conj #{}) k))
            (doseq [c was :when (not (now c))] (vswap! (:cache w) update-in [c :parents] disj k) (vswap! (:gone w) conj c))))
        ;; what a reader of the tree keeps of those above it (`wire-parts`) is not what it was: only they hold what moved
        (when-not same?
          (let [up (fn up [x] (doseq [p (:parents (get @(:cache w) x))]
                                (when (:wire-at (get @(:cache w) p))
                                  (vswap! (:cache w) update p dissoc :wire-at)
                                  (up p))))]
            (up k)))
        (led! w f (if same? :same :miss) nil bytes)
        (some-> (:ran env) (vswap! assoc k (if same? :same :miss)))
        ;; merged: its written html (placed-units) stays while its revision does
        (vswap! (:cache w) update k merge {:f f :opts opts :args args :rx rx :out (if same? (:out prev) out) :children children
                                    :order order :id id :rev (if same? (:rev prev) (inc (:rev prev 0))) :kept @kept :outs @outs
                                    :reads read-keys})
        ;; the components that read the db: the ones a transaction's datoms are matched against
        (vswap! (:db-keys w) (if (seq @reads) conj disj) k)
        (when (:reads prev) (forget-readers! w k (:reads prev)))
        (doseq [rk read-keys] (vswap! (:readers w) update rk (fnil conj #{}) k))
        [::child k]))))

(defn kept-of
  "What component k of walk w kept as of its last run, its output's metadata :seon.render/kept: the data its hiccup was made of, for a reader that decides from it (a turn, the status), so one reaction serves both and an unchanged one
  costs nothing. Nil when it did not draw or threw. See docs/render.md#how-the-work-stands."
  {:malli/schema [:=> [:cat :map [:vector :any]] :any]}
  [w k] (:kept (get @(:cache w) k)))

(defn take-defects!
  "The components of walk w that threw since the last take, each its :seon.render/component-defect, for whoever records a problem (the agent's fold, root's api). See docs/health.md."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [w] (let [ds @(:defects w)] (when (seq ds) (vreset! (:defects w) [])) ds))

(defn unmount!
  "Component k gone from walk w: its reaction disposed, and each child it alone held with it. See docs/render.md#components."
  {:malli/schema [:=> [:cat :map [:vector :any]] :nil]}
  [w k]
  (when-let [c (get @(:cache w) k)]
    (some-> (:rx c) ratom/dispose!)
    (vswap! (:cache w) dissoc k)
    (vswap! (:queue w) disj k)
    (vswap! (:db-keys w) disj k)
    (forget-readers! w k (:reads c))
    (some->> (:form c) (vswap! (:forms w) dissoc))
    (doseq [ch (:children c) :when (contains? @(:cache w) ch)]
      (when (empty? (get-in (vswap! (:cache w) update-in [ch :parents] disj k) [ch :parents])) (unmount! w ch))))
  nil)

(defn- page-only?
  "Is x, an element or a component call, for the page alone (its attributes or opts :wire/for \"page\": what the model does not read)? See
  docs/render.md#a-part-prints-as-its-render."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [x] (and (vector? x) (map? (second x)) (= "page" (:wire/for (second x)))))

(defn emit
  "Hiccup hc drawn in walk w: an element's children drawn; a component call rendered (render!) to
  its marker; any other call kept as written, its arguments real values (seon.ratom/realize: a
  call of the printer prints the value, and the model reads the call); a view at a value's place
  its real value drawn by its render (html). A walk no person reads (:model-only) draws nothing for the page alone."
  {:malli/schema [:=> [:cat :map :any :map] :any]}
  [w x0 env0]
  ;; a local fn, no contracted call a node: this runs for every node of every output, and each earlier member of a list asks a lookup and nothing else
  ((fn go [x env]
     (cond
       (or (nil? x) (string? x) (number? x) (boolean? x) (keyword? x) (char? x)) x
       (vector? x)
       (let [head (first x) o (second x)]
         (cond
           (and (:model-only w) (map? o) (= "page" (:wire/for o))) (do (vswap! (:skipped w) conj (:self env)) nil)
           (keyword? head)
           (let [attrs (when (and (map? o) (not (instance? seon.ratom.View o)) (not (instance? seon.ratom.SortedView o))) o) kids (if attrs (nnext x) (next x))]
             (into (if attrs [head attrs] [head]) (map #(go % env)) kids))
           ;; a form drawn before whose component nothing queued: its marker, with nothing asked of it (a list's earlier
           ;; members cost the list a lookup each). Only plain arguments: a view's equality reads the source
           (and (var? head) (when-let [k (get @(:forms w) x)] (and (contains? @(:cache w) k) (not (contains? @(:queue w) k)))))
           (let [k (get @(:forms w) x)] (vswap! (:children env) conj k) [::child k])
           ;; whether it is a component, kept by its var's root (`inputs-in`): no contracted call for a plain call asked again
           (seq (cond (var? head) (let [[v0 rs] (get @(:inputs w) head)] (if (and rs (identical? (var-get head) v0)) rs (inputs-in w head)))
                      (fn? head) (inputs-in w head)))
           (let [m (render! w head (or o {}) (drop 2 x) env)]
             (when (every? (some-fn number? string? keyword? nil?) (drop 2 x))
               (vswap! (:forms w) assoc x (second m))
               (vswap! (:cache w) update (second m) assoc :form x))
             m)
           (or (var? head) (fn? head)) (into [head] (map ratom/realize) (rest x))
           :else (ratom/realize x)))
       (seq? x) (doall (map #(go % env) x))
       (h/raw? x) x
       ;; a pulled entity placed in hiccup is rendered by its schema's render as a component of its own, the view its argument
       (ratom/view? x) (let [ix @(:index w)]
                         (if-let [[f o] (entity-render ix x)] (render! w f o [x] env) (hiccup {:seon.render/index ix} (ratom/unwrap x))))
       :else (ratom/realize x)))
   x0 env0))

(defn- styled
  "Component output out as the page writes it: its root's class, its render's name, followed by the
  Tailwind classes render var f states (:seon.render/style, literal text in its source, so the
  stylesheet's scan builds them), unless that style is CSS rules, which the page's rules component draws (seon.render.ui/page-rules): the style of each render drawn, by reference, never in what the
  model reads; and the render's name as :data-render, which a block shows. See docs/render.md#where-things-go."
  {:malli/schema [:=> [:cat :any :any] :any]}
  [out f]
  (let [style (:seon.render/style (meta f))]
    (if (and (vector? out) (map? (second out)) (:class (second out)))
      (cond-> out
        (and style (not (css? style))) (update-in [1 :class] str " " style)
        (var? f) (assoc-in [1 :data-render] (str (symbol f))))
      out)))

(defn resolved
  "Component k's output in walk w with every child's marker replaced by that child's output: the
  page's hiccup below k. With html? true each component as the page writes it (styled)."
  {:malli/schema [:function [:=> [:cat :map [:vector :any]] :any] [:=> [:cat :map [:vector :any] :boolean] :any]]}
  ([w k] (resolved w k false))
  ([w k html?]
   (let [c (get @(:cache w) k)]
     ;; through elements and lists, never into an element's attributes: a :wire/result holds a whole record, and no marker sits there
     ((fn go [x] (cond (and (vector? x) (= ::child (first x))) (resolved w (second x) html?)
                       (vector? x) (mapv go x)
                       (seq? x) (doall (map go x))
                       :else x))
      (cond-> (:out c) html? (styled (:f c)))))))

(defn- render-reach
  "The vars whose work reaches any of vars names on db, names among them: each form naming one (:form/refs), and what
  that form defines, to a fixed point. Read only when a definition moved."
  {:malli/schema [:=> [:cat :seon.host/db [:set :symbol]] [:set :symbol]]}
  [db names]
  (loop [seen names forms #{} todo (seq names)]
    (if-let [v (first todo)]
      (let [fs (into [] (comp (map :e) (remove forms)) (d/datoms db :avet :form/refs v))
            up (into [] (comp (mapcat #(d/datoms db :eavt % :form/defines))
                              (mapcat #(d/datoms db :eavt (:v %) :var/name))
                              (map :v) (remove seen))
                     fs)]
        (recur (into seen up) (into forms fs) (into (rest todo) up)))
      seen)))

(defn- queue-moved-renders!
  "Each component of walk w whose render moved in datoms or reaches a var that did (render-reach) queued: a render is an input of
  what it draws, as the data is. It costs what a change of a definition reaches, and nothing else. See
  docs/render.md#reactive-renders."
  {:malli/schema [:=> [:cat :map [:sequential :any]] :nil]}
  [w datoms]
  (let [db ((:db w))
        moved (into #{} (keep #(when (= :var/form (:a %)) (:var/name (d/entity db (:e %))))) datoms)
        reach (render-reach db moved)]
    (doseq [[k c] @(:cache w) :when (and (var? (:f c)) (reach (symbol (:f c))))]
      (vswap! (:queue w) conj k))
    nil))

(defn flush!
  "Walk w drawn as of now, Reagent's batching flush (reagent.core/flush: the queued components rendered
  at once): its root on the first, then each component a change queued (a reaction's, after
  seon.ratom/flush!; a render var redefined) or whose db reads
  datoms touch (:all: every db render), in mount order, parents first, none the root no longer reaches through who holds it (it runs only if a parent draws it
  again); then those it let go that no parent drew go (a child moved between parents stays, whichever ran first). Answers what it ran, {key :miss} for an output new
  and {key :same} for one = to the last, the components it skipped left out ({} when nothing a
  render read changed). See docs/render.md#reactive-renders."
  {:malli/schema [:function [:=> [:cat :map [:or [:= :all] [:maybe [:sequential :any]]]] [:map-of [:vector :any] [:enum :miss :same]]]
                  [:=> [:cat :map [:or [:= :all] [:maybe [:sequential :any]]] [:maybe :map]] [:map-of [:vector :any] [:enum :miss :same]]]]}
  ([w datoms] (flush! w datoms nil))
  ([w datoms ix]
  (let [env {:children (volatile! []) :ran (volatile! {})}]
    (when ix (vreset! (:index w) ix))
    (if-let [root @(:out w)]
      (let [held? (fn held? [k] (or (= root k) (some held? (:parents (get @(:cache w) k)))))]
          (ratom/flush!)
          ;; a schema entry moved: what an entity is may have, so the index is built again from the db; no other write does
          (when (and (nil? ix) (:own-index? w) (or (= :all datoms) (some #(= "schema" (namespace (:a %))) datoms)))
            (vreset! (:index w) (program/index-of-db ((:db w)))))
          (if (= :all datoms)
            (vswap! (:queue w) into @(:db-keys w))
            (when (seq datoms)
              ;; a definition moved: what draws by it again. No contracted call on a move that moved none
              (when (some #(= :var/form (:a %)) datoms) (queue-moved-renders! w datoms))
              ;; each datom's keys once, then who read them: what moved, whatever the components
              (let [ks (into #{} (mapcat reads/datom-keys) datoms)]
                (if (contains? ks :all)
                  (vswap! (:queue w) into @(:db-keys w))
                  (doseq [rk ks] (vswap! (:queue w) into (get @(:readers w) rk #{})))))))
          (loop []
            (when-let [k (first (sort-by #(:order (get @(:cache w) %))
                                         (filter #(and (contains? @(:cache w) %) (held? %)) @(:queue w))))]
              (let [{:keys [f opts args]} (get @(:cache w) k)] (render! w f opts args (assoc env :children (volatile! []))))
              (recur)))
          (doseq [k @(:gone w) :when (empty? (:parents (get @(:cache w) k)))] (unmount! w k))
          (vreset! (:gone w) #{})
          @(:ran env))
      (let [[f opts & children] (:root w) [_ k] (render! w f (or opts {}) (vec children) env)]
        (vreset! (:out w) k)
        @(:ran env))))))

(defn- tree-rev
  "Component k's revision with its children's, below it: what moves when anything it shows changed
  (a child's own output changes the child's revision, not its parent's)."
  {:malli/schema [:=> [:cat :map [:vector :any]] :int]}
  [w k] (let [c (get @(:cache w) k)] (hash (into [(:rev c)] (map #(tree-rev w %)) (:children c)))))

(defn- placed
  "Walk w's components placed directly in a container of the root's output (an element with an id:
  its list), in order, [[sel k]]: its container's selector and its key; a list component (its var's
  :seon.render/list) is no
  part: its output is its list, the components in it placed there; a component that drew nothing (with-id) is
  placed nowhere. With sels, the element ids of some lists, those lists' alone, in sels' order. The one prewalk of
  the root that placed-units and placed-hiccup read. nested? true: each placed component's own components too, each in the element of
its parent that holds it, at any depth (the page's units), each [sel k parent-k] (parent-k nil in a root list); false: the lists' own
components (the model's parts, which nest their children themselves). See docs/render.md#a-layout-places-its-lists."
  {:malli/schema [:function [:=> [:cat :map] [:vector [:tuple :string [:vector :any]]]]
                  [:=> [:cat :map [:maybe [:sequential :string]]] [:vector [:tuple :string [:vector :any]]]]
                  [:=> [:cat :map [:maybe [:sequential :string]] :boolean] [:vector [:or [:tuple :string [:vector :any]] [:tuple :string [:vector :any] [:maybe [:vector :any]]]]]]]}
  ([w] (placed w nil false))
  ([w sels] (placed w sels false))
  ([w sels nested?]
  (let [found (volatile! [])
        want (some-> sels set)
        list (fn [k] (let [f (:f (get @(:cache w) k))] (when (var? f) (:seon.render/list (meta f)))))
        ;; x walked with sel, the selector of the nearest element above it with an id: a component's marker is placed there (a list
        ;; component is no part: its output is walked as the list's own)
        ;; parent, the placed component whose output x is in (nil in the root's)
        walk (fn walk [x sel parent]
               (cond (and (vector? x) (= ::child (first x)))
                     (let [k (second x) c (get @(:cache w) k)]
                       (when (some? (:out c))
                         (if-let [l (list k)]
                           ;; a list a reader of some lists did not ask for is not walked
                           (when (or (nil? want) (want (str "#" l))) (walk (:out c) sel parent))
                           (when sel
                             (vswap! found conj (if nested? [sel k parent] [sel k]))
                             ;; nested?: the components inside it, each in the element of its own that holds it, at any depth
                             (when nested? (walk (:out c) nil k))))))
                     (and (vector? x) (keyword? (first x)))
                     (let [attrs (when (map? (second x)) (second x))
                           sel (if-let [id (:id attrs)] (str "#" id) sel)]
                       (doseq [y (if attrs (drop 2 x) (rest x))] (walk y sel parent)))
                     (sequential? x) (doseq [y x] (walk y sel parent))
                     :else nil))]
    (walk (:out (get @(:cache w) @(:out w))) nil nil)
    (if sels (vec (mapcat (group-by first @found) sels)) @found))))

(defn placed-units
  "Walk w's render as the units root's render proc lays out (seon.render.ui/page-chrome): every component placed, at any depth
  (`placed` nested), {:id :container :parent :rev :html}: its element id, its container's selector (a root list's, or its parent's
  element), its parent's unit id (nil in a root list), its own revision (moved only when its own output changed: a child's change is
  the child's unit alone) and its html with each child's slot (seon.render.ui/slot's marker, which the render proc composes with the
  child's unit). A change costs the component that moved, at any depth. See docs/render.md#a-layout-places-its-lists."
  {:malli/schema [:=> [:cat :map] [:vector [:map [:id :string] [:container :string] [:parent [:maybe :string]] [:rev :int] [:html :string]]]]}
  [w]
  (vec (for [[sel k parent] (placed w nil true) :let [c (get @(:cache w) k)]]
           (let [r (:rev c)
                 ;; its html written once a revision: kept on its cache entry, by its revision
                 html (if (= r (:unit-rev c)) (:unit-html c)
                          (let [slotted ((fn go [x] (cond (and (vector? x) (= ::child (first x)))
                                                          (when-let [id (:id (get @(:cache w) (second x)))] (h/raw (str "<!--s:" id "-->")))
                                                          (vector? x) (mapv go x)
                                                          (seq? x) (doall (map go x))
                                                          :else x))
                                         (styled (:out c) (:f c)))
                                h (h/html slotted)]
                            (vswap! (:cache w) update k assoc :unit-rev r :unit-html h) h))]
             {:id (:id c) :container sel :parent (when parent (:id (get @(:cache w) parent))) :rev r :html html}))))

(defn- strip-attrs
  "An element's attributes as a reader of its hiccup reads them: its presentation (class, style) is the page's and its marks (:wire/*) the reading's; an id names it, so the model need not guess it."
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [attrs] (not-empty (reduce-kv (fn [m k _] (if (and (keyword? k) (= "wire" (namespace k))) (dissoc m k) m)) (dissoc attrs :class :style) attrs)))

(defn- size
  "The characters x holds, as the one sizer counts them: its strings and the text of its other leaves, a mark for each collection, no wire mark
  and nothing printed but an eval's result as the model reads it: an estimate of what is shown, not a text made."
  {:malli/schema [:=> [:cat :map :any] nat-int?]}
  [ix x0] ((fn go [x] (cond (and (map? x) (:wire/result x)) (count (print/record-text (:record (:wire/result x)) #(model ix %)))
                         (map? x) (reduce + 1 (for [[k v] x :when (not (and (keyword? k) (= "wire" (namespace k))))] (+ (go k) (go v))))
                         (coll? x) (reduce + 1 (map go x)) (nil? x) 0 :else (count (str x)))) x0))

(defn tokens-of
  "What x costs the context in tokens: the one sizer (`seon.print.emit/chars-tokens`) over the characters x holds, the same count
  a part's head (`part-form`) and the tree (`tree`) give."
  {:malli/schema [:=> [:cat :map :any] nat-int?]}
  [ix x] (select/chars-tokens (size ix x)))

(declare part-form)

(defn- body-forms
  "Part output out as the forms its printed hiccup holds, in w: a child component a part of its own (`part-form`),
  an element without its presentation, an element for the page alone left out, a call of a function its var named."
  {:malli/schema [:=> [:cat :map :any] [:maybe [:sequential :any]]]}
  [w out]
  (cond
    (nil? out) nil
    (and (vector? out) (= ::child (first out))) (when-let [f (part-form w (second out))] [(assoc-in f [1 :id] (:id (get @(:cache w) (second out))))])
    (page-only? out) nil
    (and (vector? out) (keyword? (first out)))
    (let [[tag & more] out m? (and (map? (first more)) (not (h/raw? (first more))))
          attrs (when m? (strip-attrs (first more)))]
      [(into (cond-> [tag] attrs (conj attrs)) (mapcat #(body-forms w %)) (if m? (rest more) more))])
    (and (vector? out) (or (var? (first out)) (fn? (first out)))) [(into [(if (var? (first out)) (symbol (first out)) (first out))] (rest out))]
    (seq? out) (mapcat #(body-forms w %) out)
    (h/raw? out) [(str out)]
    :else [out]))

(defn part-form
  "Component k of walk w as the form the model reads it, `[seon.render.ui/status {:ctx [:seon.agent/task :proved]} [:h2 ...] ...]`:
  the render's name, the ctx path it read (its opts' :at: (get-in @ctx path) answers the data it drew), then the hiccup
  it returned, each child component a part of its own and no presentation (class, style) in it; its root's id is the name it goes by, so it is said once. What is for the
  page alone is no form. See docs/render.md#a-part-prints-as-its-render."
  {:malli/schema [:=> [:cat :map [:vector :any]] :any]}
  [w k]
  (let [c (get @(:cache w) k)
        out (cond-> (:out c) (and (vector? (:out c)) (map? (second (:out c)))) (update 1 dissoc :id))
        ;; a part's own root element, a plain section or div, is the part itself: its children stand under the head
        wrapper? (and (vector? out) (#{:section :div} (first out)) (nil? (strip-attrs (if (map? (second out)) (second out) {}))))
        body (if wrapper?
               (mapcat #(body-forms w %) (if (map? (second out)) (drop 2 out) (rest out)))
               (body-forms w out))]
    (when (and (seq body) (not= "page" (:wire/for (:opts c))))
      (into [(symbol (:f c)) (if-let [at (:at (:opts c))] {:ctx at} {})] body))))

(defn fence
  "Source text s in a Markdown fence longer than any run of backticks inside it, so no line of s closes it: three, or one
  more than the longest run. Verbatim: s is not escaped."
  {:malli/schema [:=> [:cat :string] :string]}
  [s]
  (let [n (max 3 (inc (reduce max 0 (map count (re-seq #"`+" s)))))
        f (apply str (repeat n "`"))]
    (str f "clojure\n" s "\n" f)))

(defn printable
  "Form x ready to print: an element [:pre [:code s]] its source in a fence (the code marker, verbatim, later lines at
  column 0), a string with a line break or a quote the text marker (verbatim, not escaped), the rest as it is."
  {:malli/schema [:=> [:cat :any] :any]}
  [x]
  (cond
    (and (vector? x) (= :pre (first x)) (= 2 (count x)) (vector? (second x)) (= :code (first (second x))) (string? (last (second x))))
    [:pre {:seon.print/code (fence (last (second x)))}]
    ;; a vector's children without the nils a (when ..) leaves and with the lists a (for ..) makes spliced in, as the page's writer reads them
    (vector? x) (with-meta (into [] (comp (mapcat (fn [c] (if (seq? c) c [c]))) (remove nil?) (map printable)) x) (meta x))
    (and (string? x) (or (str/includes? x "\n") (str/includes? x "\""))) {:seon.print/plain x}
    (map? x) (if (:seon.print/code x) x (into (empty x) (map (fn [[k v]] [k (printable v)])) x))
    :else x))

(defn part-text
  "Part form f (`part-form`) printed as text the model reads: whole (:seon.print/full), the head and its path, then the
  hiccup, a verbatim block in its fence. See docs/render.md#a-part-prints-as-its-render."
  {:malli/schema [:=> [:cat :any] :string]}
  [f] (print/text (vary-meta (printable f) assoc :seon.print/full true)))

(defn placed-hiccup
  "The parts of walk w placed in the root's lists with element ids sels ([\"#p-task\"]), in order, as {id form}: what
  the model reads of each (`part-form`). See docs/render.md#a-part-prints-as-its-render."
  {:malli/schema [:=> [:cat :map [:sequential :string]] :map]}
  [w sels]
  (apply array-map (mapcat (fn [[_ k]] (when-let [f (part-form w k)] [(:id (get @(:cache w) k)) f])) (placed w sels))))

(defn- placed-ids
  "The parts of walk w placed in the lists sels that the model reads, [[id k]] in order: the element id and the cache key of each."
  {:malli/schema [:=> [:cat :map [:sequential :string]] [:vector [:tuple :string [:vector :any]]]]}
  [w sels]
  (vec (for [[_ k] (placed w sels) :let [c (get @(:cache w) k)] :when (not= "page" (:wire/for (:opts c)))] [(:id c) k])))

(defn keyed
  "Hiccup form x as a diff reads it: an element's children in their order, each under a name and read the same way, and under :own the element without them; an element holding text directly is text, one value, and so is an element holding a single child with no name of its own, and anything else. A child's name is what identifies it: its :key (Reagent's), else its id (a component's name-based id), else, among those with neither, its tag when no sibling shares it and its first words when one does. Names that are not distinct stay in :own. The form it read is its metadata, ::form. Local fns: no contracted call a node. See docs/render.md#a-change-is-edits-by-name."
  {:malli/schema [:=> [:cat :any] :any]}
  [x0]
  (letfn [(el? [x] (and (vector? x) (some? (first x))))
          (given [x] (when (and (el? x) (map? (second x))) (let [a (second x)] (or (:key a) (:id a)))))
          (words [x]
            (let [t (first (filter string? (tree-seq coll? seq x)))]
              (when t (str/join " " (keep-indexed (fn [i w] (when (< i 6) w)) (str/split (str/trim t) #"\s+"))))))
          (names [kids]
            (let [plain (remove given (filter el? kids))
                  tags (frequencies (map first plain))
                  derived (fn [c] (if (= 1 (tags (first c))) (first c) (words c)))
                  named (map (fn [c] (when (el? c) (or (given c) (derived c)))) kids)
                  counts (frequencies (remove nil? named))]
              (map (fn [n] (when (and (some? n) (not= :own n) (= 1 (counts n))) n)) named)))
          (go [x]
            (if-not (el? x)
              x
              (let [n (if (map? (second x)) 2 1)
                    kids (subvec x n)]
                (if (or (empty? kids) (some #(or (string? %) (number? %)) kids) (and (keyword? (first x)) (nil? (next kids)) (nil? (given (first kids)))))
                  x
                  (let [ns (names kids)]
                    (if-not (some some? ns)
                      x
                      (with-meta (apply array-map :own (into (subvec x 0 n) (keep (fn [[c k]] (when (nil? k) c))) (map vector kids ns))
                                        (mapcat (fn [[c k]] (when (some? k) [k (go c)])) (map vector kids ns)))
                        {::form x})))))))]
    (go x0)))

(defn- settled
  "Reads a and b (`keyed`), with each element whose named children changed places one value on both sides: map keys say nothing of order, so an edit could not, and that element is replaced at its name, the rest of the component still said by edits. Local fn: no contracted call a node. See docs/render.md#a-change-is-edits-by-name."
  {:malli/schema [:=> [:cat :any :any] [:tuple :any :any]]}
  [a0 b0]
  ((fn go [a b]
     (if-not (and (map? a) (map? b))
       [a b]
       (let [both (filter #(contains? b %) (keys a))]
         (if (not= both (filter #(contains? a %) (keys b)))
           [(::form (meta a)) (::form (meta b))]
           (reduce (fn [[x y] k] (let [[p q] (go (get a k) (get b k))] [(assoc x k p) (assoc y k q)]))
                   [a b] both)))))
   a0 b0))

(defn- named-edit
  "Edit e of a diff whose new read is b, with no position in its path: a path that enters one value of b (`keyed`'s) replaces that value whole, at its name. See docs/render.md#a-change-is-edits-by-name."
  {:malli/schema [:=> [:cat :any [:vector :any]] [:vector :any]]}
  [b [p :as e]]
  (loop [i 0 x b]
    (cond (= i (count p)) e
          (vector? x) [(subvec p 0 i) :r x]
          :else (recur (inc i) (get x (nth p i))))))

(defn- component-edits
  "What moved in component id from form was to form now, as edits with their paths in names, its id first, each value hiccup; or the component whole, when that is shorter or edits could not say it: nothing to compare with, or it left. See docs/render.md#a-change-is-edits-by-name."
  {:malli/schema [:=> [:cat :map :string :any :any] [:vector :any]]}
  [ix id was now]
  (cond
    (nil? was) [[[id] :+ now]]
    (nil? now) [[[id] :-]]
    :else
    (let [[a b] (settled (keyed was) (keyed now))
          whole [[[id] :r now]]
          es (do
               ;; :vec-timeout nil: left to its default a clock would decide what comes out, by :quick as by :a-star (editscript.util.common/with-vec-deadline)
               (mapv (fn [[p op v :as e]] (cond-> (assoc e 0 (into [id] p)) (= 3 (count e)) (assoc 2 (::form (meta v) v))))
                     (distinct (map #(named-edit b %) (ed/get-edits (ed/diff a b {:algo :quick :vec-timeout nil}))))))]
      (if (and (seq es) (< (size ix es) (size ix whole))) es whole))))

(defn page-edits
  "What moved of a page, [[id was now] ...] (`moved`, `forms-moved`), as edits (`component-edits`), in that order. See docs/render.md#a-change-is-edits-by-name."
  {:malli/schema [:=> [:cat :map [:sequential :any]] [:vector :any]]}
  [ix mv] (printable (into [] (mapcat (fn [[id was now]] (component-edits ix id was now))) mv)))

(defn forms-moved
  "What moved between two pages, {id form} before and after (`placed-hiccup`), as `moved` answers it: each component of after that differs, in its order, then each that left. See docs/render.md#a-change-is-edits-by-name."
  {:malli/schema [:=> [:cat [:maybe :map] :map] [:vector :any]]}
  [before after]
  (into (into [] (keep (fn [[id f]] (when (not= f (get before id)) [id (get before id) f]))) after)
        (keep (fn [[id f]] (when-not (contains? after id) [id f nil])))
        before))

(defn draw
  "Tree t (a ctx value: seon.ctx/build's :tree) drawn once by the layout var root-var over db, its
  diff changes: a walker over a reactive atom holding t, flushed once. Its caller reads it
  (placed-units, placed-hiccup, `wire`) and disposes it (dispose-walk!): root drawing an agent's branch, the
  model's request at a commit. opts :model-only true, for a reader that is the model: nothing for the page alone is drawn (`emit`), so
  that read costs what the model reads; :once true, for a caller that reads it and lets it go: no component watches what it read (docs/render.md#a-layout-places-its-lists);
  :index, the entity index (seon.db/index-of) the walk holds instead of building one from db (a one-shot render of an agent passes its held one)."
  {:malli/schema [:function [:=> [:cat :map :seon.host/fn :seon.host/db :any] :map]
                  [:=> [:cat :map :seon.host/fn :seon.host/db :any [:map [:model-only {:optional true} :boolean] [:once {:optional true} :boolean] [:index {:optional true} :map] [:outputs {:optional true} [:set :keyword]]]] :map]]}
  ([t changes db root-var] (draw t changes db root-var {}))
  ([t changes db root-var opts]
   (let [r (ratom/atom t)
         ;; the walk is rooted on the entity's render with the entity, a view of the tree, as its argument
         w (merge (if-let [ix (:index opts)]
                       (walker {:ref r :changes changes} (constantly db) [root-var {} (ratom/ref-view r changes)] ix)
                       (walker {:ref r :changes changes} (constantly db) [root-var {} (ratom/ref-view r changes)])) (select-keys opts [:model-only :once :outputs]))]
     (flush! w nil)
     w)))

(defn draw-all
  "Walk w as one that draws what is for the page too, each component that left a piece out queued, drawn at its next flush!: the walk
  whose reader was the model alone has a person reading it. See docs/render.md#a-layout-places-its-lists."
  {:malli/schema [:=> [:cat :map] :map]}
  [w]
  (vswap! (:queue w) into @(:skipped w))
  (vreset! (:skipped w) #{})
  (dissoc w :model-only))

(defn page-lists
  "The lists of walk w read as parts, their element ids (\"#p-task\"): each list component the root drew that holds no messages (a list var's
  :seon.render/messages) and is not for the page alone. What is in them is what the agent's pull holds, so nothing names them here. See
  docs/render.md#a-layout-places-its-lists."
  {:malli/schema [:=> [:cat :map] [:vector :string]]}
  [w]
  (let [cache @(:cache w)]
    (vec (for [k (:children (get cache @(:out w)))
               :let [{:keys [f opts]} (get cache k) m (when (var? f) (meta f))]
               :when (and (:seon.render/list m) (not (:seon.render/messages m)) (not= "page" (:wire/for opts)))]
           (str "#" (:seon.render/list m))))))

;;; --- the model's reading of the tree: one generic conversion to provider-neutral messages ---------

(defn result-text
  "What came of an eval as the model reads it, printed now by the one printer (seon.print/record-text, through `stored-model`), from its mark :wire/result {:record e}: :value-of, a function of db and e, when the value is rebuilt rather than kept, and
  :with, what a message that is not a call's answer says around it as {.. :response text}. A function of the record alone and of db at the t it names, so the same bytes cold as live. See docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat :map [:maybe :seon.host/db] :map] :string]}
  [ix db {:keys [record value-of with]}]
  (let [text (print/record-text (cond-> record value-of (assoc-in [:seon.eval/response :value] (value-of ix db record))) #(stored-model ix %))]
    (if with
      (str "{" (str/join " " (map (fn [[k v]] (str k " " (pr-str v))) with)) " :response " text "}")
      text)))

(defn ledger
  "Walk w's ledger: by render var, its :miss (ran, output new), :same (ran, output =) and :hit
  (skipped) counts and the bytes its runs allocated."
  {:malli/schema [:=> [:cat :map] :map]}
  [w] @(:ledger w))

(declare openai-compat dispose-walk!)

(defn render-pull
  "Pull (a ctx tree, as seon.ctx/build answers it) rendered on db by root render f, in the entity index ix, in one pass and let
  go: {:hiccup its hiccup, :messages its model output (empty unless outputs holds :seon.render/openai-compat)}. Pure in its arguments:
  no walk or state is kept, so it is how a past point is read (a turn's diff reads its page parts with outputs
  #{:seon.render/hiccup}, so no model render nests). See docs/render.md#one-rendering-system-drawn-from-the-data."
  {:malli/schema [:function [:=> [:cat :map :seon.host/db :any :map [:set :keyword]] [:map [:hiccup :any] [:messages [:vector :any]]]]
                  [:=> [:cat :map :seon.host/db :any :map [:set :keyword] :map] [:map [:hiccup :any] [:messages [:vector :any]]]]]}
  ([pull db f ix outputs] (render-pull pull db f ix outputs {}))
  ;; opts: the root call's (f opts view), and :seon.render/changes, the pull's diff (seon.ctx/changes for a ctx tree; without it a
  ;; component reading :seon.ctx/ctx reads nothing); {:seon.render/as :seon.render/openai-compat} makes the root a model output's call, its answer
  ;; (messages and what it places) the walk's model output, as a call a model output places is
  ([pull db f ix outputs opts]
  (let [r (ratom/atom pull)
        ;; the pull's own diff (opts' :seon.render/changes, seon.ctx/changes for a ctx tree): a view reads a pull's collections by it
        ch (or (:seon.render/changes opts) (fn [_ _] nil))
        w (assoc (walker {:ref r :changes ch} (constantly db) [f (dissoc opts :seon.render/changes) (ratom/ref-view r ch)] ix) :outputs outputs)]
    (try (flush! w nil) {:hiccup (resolved w @(:out w) false) :messages (openai-compat w)}
         (finally (dispose-walk! w))))))

(defn answer-of
  "Walk w's answer for output o (a schema property naming a render, asked in the walk's :outputs, other than hiccup): its root's
  (kept on its cache entry, :outs, made in the same run as its hiccup), each placed component's marker replaced by that component's
  own, as `resolved` places hiccup: the order is each render's. See docs/render.md#one-rendering-system-drawn-from-the-data."
  {:malli/schema [:=> [:cat :map :qualified-keyword] [:vector :any]]}
  [w o]
  (let [cache @(:cache w)]
    (vec ((fn go [k] (mapcat #(if (and (vector? %) (= ::child (first %))) (go (second %)) [%]) (get-in cache [k :outs o]))) @(:out w)))))

(defn answer-sources
  "Walk w's answer for output o as `answer-of` reads it, each value with the component whose own answer held it: [[value
  {:f :args}] ...] in the same order, :f the render or call that answered it and :args what it was called with (an entity
  rendered by its schema's render, its entity). What a reader that labels the answer by what made it reads (the debug page's
  sources). See docs/render.md#one-rendering-system-drawn-from-the-data."
  {:malli/schema [:=> [:cat :map :qualified-keyword] [:vector [:tuple :any :map]]]}
  [w o]
  (let [cache @(:cache w)]
    (vec ((fn go [k] (let [e (get cache k)]
                       (mapcat #(if (and (vector? %) (= ::child (first %))) (go (second %)) [[% (select-keys e [:f :args])]]) (get-in e [:outs o]))))
          @(:out w)))))

(defn openai-compat
  "The model's messages of walk w: its answer for :seon.render/openai-compat (`answer-of`). See
  docs/render.md#one-rendering-system-drawn-from-the-data."
  {:malli/schema [:=> [:cat :map] [:vector :seon.model/message]]}
  [w] (answer-of w :seon.render/openai-compat))

(defn tree
  "What draws what in walk w, from its root, as data that chains and prints nothing: each component its cache holds as
  {:render the render's name, :args what it was called with, :id its element id, :tokens the one sizer's over the characters of its own output
  (its children stand there as markers: their output is theirs), :reads? true when it read the db, :children
  [those it drew, in order]}. Whatever has been drawn so far: a walk of the lists a reader asked for draws no others. Nil
  for a walk not yet flushed. An agent's own is `my.render/tree`; root's draws of a branch (`draw`) answer alike. See
  docs/render.md#what-draws-what."
  {:malli/schema [:=> [:cat :map] [:maybe :seon.render/tree]]}
  [w]
  (let [cache @(:cache w)
        node (fn node [k]
               (when-let [c (get cache k)]
                 (cond-> {:render (symbol (:f c)) :args (:args c) :id (:id c)
                          :tokens (tokens-of @(:index w) (:out c))
                          :children (into [] (keep node) (:children c))}
                   (seq (:reads c)) (assoc :reads? true))))]
    (some-> @(:out w) node)))

(defn dispose-walk!
  "Every component of walk w disposed, and the render vars it watched and the inputs it kept (inputs-in) let go:
  a cold walk's end."
  {:malli/schema [:=> [:cat :map] :nil]}
  [w]
  (when-let [k @(:out w)] (unmount! w k))
  (doseq [f @(:watched w)] (remove-watch f (:watched w)))
  (vreset! (:watched w) #{})
  (vreset! (:inputs w) {})
  nil)

;; ---- entity schemas and patterns (docs/ctx.md#the-pattern) ----

(defn entity-schema
  "The schema of pulled entity e: the most specific entity schema its identity attributes name (one extending another,
  [:and parent [:map ...]], so the order of e's keys never decides), else the one naming via (the attribute it was
  reached by, read forward), else nil: no entity schema names it, and the one printer shows it. A record whose identity
  attributes name schemas that extend none of each other throws :seon.render/no-one-schema naming them. See
  docs/ctx.md#the-pattern. ix is the entity index schemas are read in (seon.db/index-of)."
  {:malli/schema [:=> [:cat :map :map [:maybe :keyword]] [:maybe :qualified-keyword]]}
  [ix e via]
  (let [s (most-specific ix e)]
    (cond (nil? s) (get (pdb/owners ix [via]) via)
          (keyword? s) s
          :else (throw (ex-info (str "no one entity schema names this entity: " (:ambiguous s) " extend none of each other")
                                (contract/error :seon.render/no-one-schema #'entity-schema "its identity attributes name entity schemas that extend none of each other"
                                                {:schemas (:ambiguous s)}))))))

(defn schema-property
  "Property p of schema k, else of the nearest schema it extends (`parents`, in the entity index ix), else nil. See docs/ctx.md#the-pattern."
  {:malli/schema [:=> [:cat :map [:maybe :qualified-keyword] :keyword] :any]}
  [ix k p]
  (when k (or (get (contract/properties k) p) (some #(schema-property ix % p) (parents ix k)))))

(defn- join-targets
  "The entity schemas join [k sub] of a pattern reaches, as the entity index ix says: sub itself when the pattern names a schema there,
  else a forward ref's targets (:targets, its schema's :seon.db/to) or a reverse ref's owner (:entity-schemas of the attribute it reverses)."
  {:malli/schema [:=> [:cat :map :any :any] [:sequential :any]]}
  [ix k sub]
  (if (keyword? sub)
    [sub]
    (let [a (if (vector? k) (first k) k)]
      (if (dbu/reverse-ref? a)
        (keep identity [(get (:entity-schemas ix) (dbu/reverse-ref a))])
        (get (:targets ix) a)))))

(defn history?
  "Whether entity schema s (in the entity index ix) is the conversation's: it declares :seon.render/openai-compat (the model reads its
  entities as its native messages) and no :seon.ctx/pattern of its own (eval, message, model call). An agent declares a model render
  and a pattern of its own: it has a page, and is drawn nested by its render's nested form, never as history. Read from the schemas'
  data, never a list of names nor a render's signature (Point's ruling for the owner, docs/decisions.md). See docs/render.md#the-page."
  {:malli/schema [:=> [:cat :map [:maybe :qualified-keyword]] :boolean]}
  [ix s]
  (boolean (and s (render-for ix s :seon.render/openai-compat) (nil? (schema-property ix s :seon.ctx/pattern)))))

(defn page-pattern
  "Pattern p as a page's parts read it: p without the joins whose target schema is history (`history?`: the conversation, which the
  model reads as its native messages, never a second time in its page). The one reading of what a page's parts pull, for the walk
  that places them (seon.render.ui/agent-parts) and the model's page at a basis (seon.ctx.wire/page-parts-through). See
  docs/render.md#the-page."
  {:malli/schema [:=> [:cat :map [:sequential :any]] [:vector :any]]}
  [ix p]
  (vec (keep (fn [x] (if (map? x)
                       (not-empty (into {} (remove (fn [[k sub]] (some #(history? ix %) (join-targets ix k sub)))) x))
                       x))
             p)))

(defn entity-pattern
  "Pulled entity e's pattern: its own :seon.ctx/pattern (an agent's edit), else the one its entity schema declares or a schema it extends
  does, in the entity index ix; nil for no entity (a point before it was made). See docs/ctx.md#the-pattern."
  {:malli/schema [:=> [:cat :map [:maybe :map]] [:maybe [:sequential :any]]]}
  [ix e] (when e (or (:seon.ctx/pattern e) (some->> (entity-schema ix e nil) (#(schema-property ix % :seon.ctx/pattern))))))

(defn render-var-of
  "The render var of entity e (a ctx's tree, an entity pulled by its pattern): its entity schema's :seon.render/hiccup (`entity-schema`,
  `declared-var`, so a branch that redefines that var renders by its own), the render a walk of e is rooted on as [f {} view]. An entity
  no schema names a render for throws :seon.render/no-render naming its identity attributes. See docs/render.md#the-page."
  {:malli/schema [:=> [:cat :map :map] :any]}
  [ix e]
  (let [s (entity-schema ix e nil)]
    (or (some->> s (declared-var ix))
        (throw (ex-info (str "no render renders this entity: its schema " s " names none")
                        (contract/error :seon.render/no-render #'render-var-of "no entity schema of this entity names a render (:seon.render/hiccup)"
                                        {:schema s :identity (vec (filter (:identity-schemas ix) (keys e)))}))))))

(defn forward
  "Attribute a read forward: a reverse ref (:ns/_name, pull's syntax) as its own attribute (:ns/name)."
  {:malli/schema [:=> [:cat :keyword] :keyword]}
  [a] (if (str/starts-with? (name a) "_") (keyword (namespace a) (str/replace-first (name a) "_" "")) a))

(def ^:private join-options
  "A join's options: Datahike's pull's (:as, :limit; :reverse, our fork's newest first) and ctx's one
  beside them, :by, the identity attribute a symbol value names (forward: each value read as [by v];
  reverse: the entities whose attribute holds this entity's by)."
  #{:as :limit :reverse :by})

(defn pattern
  "Pull pattern p, what a record holds (docs/ctx.md#the-pattern: Datahike's syntax, '* the entity's
  every attribute, else the attributes it names; {attr sub} a join, attr as [attr & options], sub
  a pattern or an entity schema's name, its declared one), as the walk takes it: {:star? :attrs
  :joins [{:attr :key :rev? :limit :reverse? :by :sub}]}, each join's attribute read forward, its
  key its :as else the attribute, its limit a count or a config key's value; :attrs also the name a
  reverse :by join reads. Throws :seon.ctx/invalid-pattern naming the clause it cannot read: no
  vector, a clause that is no '*, attribute or join, a reverse attribute outside a join, an option
  neither pull's nor :by, a :limit with no :reverse (a window is newest first), a join with no
  pattern, an entity schema with none or whose pattern reaches itself. An entity schema's name is read in the entity index ix (seon.db/index-of)."
  {:malli/schema [:function [:=> [:cat :map :any] :seon.ctx/spec] [:=> [:cat :map :any [:set :qualified-keyword]] :seon.ctx/spec]]}
  ([ix p] (pattern ix p #{}))
  ([ix p seen]
   (let [bad (fn [why clause] (throw (ex-info (str "pattern: " why) (contract/error :seon.ctx/invalid-pattern #'pattern why {:clause clause}))))
         reverse? #(str/starts-with? (name %) "_")]
     (when-not (vector? p) (bad "a pattern is a vector" p))
     (doseq [x p] (cond (= '* x) nil
                        (keyword? x) (when (reverse? x) (bad "a reverse attribute is read by a join: {:ns/_name pattern}" x))
                        (map? x) nil
                        :else (bad "a clause is '*, an attribute or a join" x)))
     (let [joins (vec (for [clause p :when (map? clause) [k sub] clause]
                        (let [[a & opts] (if (vector? k) k [k])
                              o (if (even? (count opts)) (apply hash-map opts) (bad "a join's options are pairs" k))
                              _ (when-let [x (first (remove join-options (keys o)))] (bad (str "no join option " x) k))
                              _ (when-not (qualified-keyword? a) (bad "a join names an attribute" k))
                              _ (when (and (:limit o) (not (:reverse o))) (bad "a window is newest first: :limit with :reverse true" k))
                              lim (:limit o)]
                          {:attr (forward a) :key (or (:as o) a) :rev? (reverse? a) :by (:by o) :reverse? (boolean (:reverse o))
                           :limit (if (keyword? lim) (config/value lim) lim)
                           :sub (cond (vector? sub) (pattern ix sub seen)
                                      ;; an entity schema's name: the pattern it declares
                                      (qualified-keyword? sub)
                                      (cond (seen sub) (bad (str sub "'s pattern reaches itself") clause)
                                            :else (pattern ix (or (schema-property ix sub :seon.ctx/pattern) (bad (str sub " declares no pattern") clause))
                                                           (conj seen sub)))
                                      :else (bad "a join's pattern is a vector, or an entity schema's name" clause))})))]
       {:star? (boolean (some #{'*} p))
        :attrs (into (into #{} (filter keyword?) p) (keep #(when (:rev? %) (:by %))) joins)
        :joins joins}))))
