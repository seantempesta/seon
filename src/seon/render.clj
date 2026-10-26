(ns seon.render
  "Render selection and the one render call (W1's proto.render and proto.render.schema,
  ported onto next's registry: notes/archive/option3-W1-render.md §1).

  A render is a plain function (opts & children) -> its result, Reagent's form: opts are
  its props, all optional (:as, the room it is shown in, :inline, :block or :tile; :budget,
  its tokens; :handle, the form its cuts read the rest through), and a function that does not
  care about one ignores it (owner: \"It's all just functions and results\"). A render answers
  hiccup (serialized by seon.web.html, where a vector headed by a function is a call, expanded
  as Reagent does), and the page and the model read the same hiccup (notes/one-derivation-design-
  rev42.md §2). Selection, first match wins, and nothing else (DECISIONS, Renders attach to
  schemas; the UI and the context are renders, ported not reinvented):
    1. the value's own override key (:seon.render/html, in the map or its metadata): a render
       symbol, or already-rendered hiccup;
    2. the render its schema declares: a schema's property {:seon.render/html sym} in
       resources/seon/schemas/*.edn (seon.contract/forms), the
       schema then its parents ([:and p ...], [:merge p ...]). The value's schema is never
       guessed: the producer names it (opts :schema, or the value's metadata
       :seon.render/schema), an error map's is its :seon.error/type (else :seon.error/base),
       and a pulled entity's is the schema with a declared render holding its identity
       attribute;
    3. the one value printer (seon.print/print-value), the default render.
  A nested value renders by a call ([render opts v] in hiccup, or render's result)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.print :as print] [seon.print.select :as select] [seon.config :as config]
            [clojure.string :as str] [clojure.walk] [datahike.api :as d] [datahike.db.utils :as dbu] [seon.web.html :as h] [seon.ratom :as ratom] [seon.profile :as profile]))

(defn parents
  "The named schemas k's form extends ([:and p ...] or [:merge p ...])."
  {:malli/schema [:=> [:cat [:maybe :qualified-keyword]] [:vector :qualified-keyword]]}
  [k]
  (let [form (get contract/forms k)]
    (if (and (vector? form) (#{:and :merge} (first form)))
      (filterv #(and (qualified-keyword? %) (contains? contract/forms %)) (rest form))
      [])))

(defn declared
  "The render symbol schema k (or its nearest parent) declares, or nil."
  {:malli/schema [:=> [:cat [:maybe :qualified-keyword]] [:maybe :symbol]]}
  [k]
  (when k
    (or (:seon.render/html (contract/properties k))
        (some declared (parents k)))))

(defn- identity-attr?
  "Is attribute a an upsert key ([:and {:seon.db/identity true} ...])?"
  {:malli/schema [:=> [:cat :any] :boolean]}
  [a] (boolean (:seon.db/identity (contract/properties a))))

(defn- map-keys
  "The keys a schema's [:map ...] form names (inline, or in its [:and ...] members)."
  {:malli/schema [:=> [:cat :any] [:vector :any]]}
  [form]
  (cond (and (vector? form) (= :map (first form))) (vec (keep #(when (vector? %) (first %)) (rest form)))
        (and (vector? form) (#{:and :merge} (first form))) (vec (mapcat #(map-keys (if (keyword? %) (get contract/forms %) %)) (rest form)))
        :else []))

(def by-identity
  "{identity-attribute schema}: for each schema with a declared render, the upsert keys its
  map names: a pulled entity holding that key is a value of that schema. Derived once from
  the registry."
  (into {} (for [[k form] contract/forms
                 :when (declared k)
                 a (map-keys form) :when (identity-attr? a)]
             [a k])))

(defn schema-of
  "The named schema of v: hint (its producer's), the one its metadata names, an error map's
  variant (its registered :seon.error/type, else :seon.error/base), or the schema its
  identity attribute names."
  {:malli/schema [:=> [:cat :any [:maybe :qualified-keyword]] [:maybe :qualified-keyword]]}
  [v hint]
  (or hint (:seon.render/schema (meta v))
      (when (map? v)
        (if-let [t (:seon.error/type v)]
          (if (contains? contract/forms t) t :seon.error/base)
          (some by-identity (keys v))))))

(declare render-of own-key)

(defn- nested
  "The printer's :render: a nested value whose schema declares a render, as a call of that render
  inline ({:text, the value in one line; :html, the call}), else nil. Its render is looked up by
  render-of (seon.render/schema-of: one metadata read or one map lookup per key, never a
  validation)."
  {:malli/schema [:=> [:cat :any] [:maybe [:map [:text :string] [:html :any]]]]}
  [v]
  (when-let [h (render-of v nil)]
    {:text (print/line v (config/value :seon.config.render/line-budget)) :html [h {:as :inline} v]}))

(defn- nested-html
  "The HTML printer's :render: nested's, and a value whose own :seon.render/html key holds hiccup
  (a page an agent built) as that hiccup, its text the hiccup as data in one line: one selection
  serves the page and the model, and the model reads the hiccup as the data it is."
  {:malli/schema [:=> [:cat :any] [:maybe [:map [:text :string] [:html :any]]]]}
  [v]
  (let [own (own-key v :seon.render/html)]
    (if (and (some? own) (not (symbol? own)))
      {:text (print/line own (config/value :seon.config.render/line-budget)) :html own}
      (nested v))))

(defn- printer-opts
  "The printer's options for a render's opts: its budget (else :seon.config.render/value-budget),
  its handle, and its nested values' own renders (nested)."
  {:malli/schema [:=> [:cat :seon.render/opts] :map]}
  [opts] {:budget (or (:budget opts) (config/value :seon.config.render/value-budget)) :handle (:handle opts) :render nested})

(defn print-html
  "The one value printer as the default :html render: v's structural hiccup under opts' budget,
  or one line (seon.print/line) when shown :inline."
  {:malli/schema [:=> [:cat :seon.render/opts :any] :any]}
  [opts v]
  (if (= :inline (:as opts))
    [:code {:class "seon-inline"} (print/line v (or (:budget opts) (config/value :seon.config.render/line-budget)) (:handle opts))]
    (let [r (print/print-value v (assoc (printer-opts opts) :outputs #{:hiccup} :render nested-html))]
      (or (:html r) [:span {:class "seon-print-refused"} (str (:seon.error/message r))]))))

(defn- own-key
  "v's own override for face: in the map, else in its metadata."
  {:malli/schema [:=> [:cat :any :keyword] :any]}
  [v k] (or (when (map? v) (get v k)) (get (meta v) k)))

(defn render-of
  "The render function v takes: its own override (a symbol), else its schema's declared one (opts :schema, else seon.render/schema-of: one metadata read or one map lookup
  per key, never a validation), else nil (the printer). nil also when v's own key holds an
  already rendered result."
  {:malli/schema [:=> [:cat :any [:maybe :qualified-keyword]] [:maybe :seon.host/fn]]}
  [v hint]
  (let [own (own-key v :seon.render/html)]
    (when-let [sym (if (symbol? own) own (when (nil? own) (declared (schema-of v hint))))]
      @(requiring-resolve sym))))

(defn declared-var
  "The var of the render schema k declares (its nearest parent's), or nil: a layout the walk draws
  is a component, and its var's contract says what it reads."
  {:malli/schema [:=> [:cat :qualified-keyword] :any]}
  [k] (some-> (declared k) requiring-resolve))

(defn html
  "v rendered: (render-fn opts v), the function its override or schema names, else the printer's
  (print-html); a value's own key holding hiccup answers that hiccup."
  {:malli/schema [:=> [:cat :seon.render/opts :any] :any]}
  [opts v]
  (let [own (own-key v :seon.render/html)]
    (if (and (some? own) (not (symbol? own)))
      own
      ((or (render-of v (:schema opts)) print-html) opts v))))

;;; --- the walk: a render of components (docs/render.md#components) -------------------------------

(defn- opts-form
  "The opts schema form of render var v's contract (its first argument), or nil."
  {:malli/schema [:=> [:cat :any] :any]}
  [v]
  (when (var? v)
    (let [s (:malli/schema (meta v)) arity (if (= :function (first s)) (second s) s)]
      (when (vector? arity) (second (second arity))))))

(defn reads
  "What render var v's opts contract declares it reads, of :seon.ctx/ctx and :seon.db/db: a render
  that declares either is a component, kept in the walk's cache, those keys filled in."
  {:malli/schema [:=> [:cat :any] [:set :qualified-keyword]]}
  [v] (into #{} (filter #{:seon.ctx/ctx :seon.db/db}) (tree-seq vector? seq (opts-form v))))

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
  "Component call [f opts & children]'s key: its var's name and its children's keys."
  {:malli/schema [:=> [:cat :any [:sequential :any]] [:vector :any]]}
  [f children] (into [(symbol f)] (map child-key) children))

(defn- id-safe
  "Text s as part of an element id, one to one: letters, digits, . and - as themselves, every other
  character _ and its code in hex (a.b/c -> a.b_2Fc, a_b -> a_5Fb)."
  {:malli/schema [:=> [:cat :string] :string]}
  [s] (apply str (map #(if (or (Character/isLetterOrDigit ^char %) (= \. %) (= \- %)) % (format "_%02X" (int %))) s)))

(defn key-id
  "Key k as its component's root element id, one to one: its render's name and its children's
  keys (seon.render.ui/eval-item over eid 1753 -> \"eval-item-1753\"; a var cited,
  seon.render.ui/cited over run 9 and demo/g -> \"cited-9-demo_2Fg\")."
  {:malli/schema [:=> [:cat [:vector :any]] :string]}
  [k]
  (apply str (id-safe (name (first k)))
         (for [c (rest k)] (str "-" (id-safe (if (keyword? c) (str (namespace c) (when (namespace c) "/") (name c)) (str c)))))))

(defn walker
  "A walk of a render of ctx: its source (src, {:ref r :changes f}: the reference ctx is, seon.ctx/ctx, and its
  one diff, seon.ctx/changes), the db its db renders read (db-fn, called when one runs) and its
  root call. Holds, in this process, the one cache: each component's reaction, args, output and
  db reads ({key entry}), its render queue (the keys a change reached), mount counter and ledger
  (calls, hits, misses and allocation by render var). A cold walk (root drawing a branch) is a
  walker drawn once."
  {:malli/schema [:=> [:cat :map :seon.host/fn [:vector :any]] :map]}
  [src db-fn root]
  {:src src :db db-fn :root root :cache (volatile! {}) :queue (volatile! #{}) :mounts (volatile! 0)
   :ledger (volatile! {}) :out (volatile! nil) :db-keys (volatile! #{})})

(defn- led!
  "Count k (:hit, :miss, :same) for render var f in w's ledger, with the ticks and bytes a run took."
  {:malli/schema [:=> [:cat :map :any :keyword [:maybe :int] [:maybe :int]] :nil]}
  [w f k ticks bytes]
  (vswap! (:ledger w) update (symbol f) #(-> (or % {}) (update k (fnil inc 0)) (update :ticks (fnil + 0) (or ticks 0)) (update :bytes (fnil + 0) (or bytes 0))))
  nil)

(declare emit unmount!)

(defn- with-id
  "Hiccup hc with its root element's id id (a component's key)."
  {:malli/schema [:=> [:cat :any :string] :any]}
  [hc id]
  (if (and (vector? hc) (keyword? (first hc)))
    (let [[tag & more] hc]
      (if (map? (first more)) (into [tag (assoc (first more) :id id)] (rest more)) (into [tag {:id id}] more)))
    [:div {:id id} hc]))

(defn render!
  "Component [f opts & children] drawn in walk w: its marker [::child key], its output kept in w's
  cache. Skipped when its children are = to last time's (views by source and path) and nothing it
  read changed: no reaction queued it and no transaction (w's :datoms) touched its db reads;
  otherwise run in its reaction (seon.ratom/run-in-reaction), opts filled with the view of ctx
  (:seon.ctx/ctx) and the db (:seon.db/db) it declares, a db render under d/with-reads, and its
  output drawn (emit) with its root element's id its key. An output = to last time's is the
  same (:same), and moves its revision only when it differs. A render that throws draws its error's
  render in its place (docs/failure-points.md: a component's render); every other component draws."
  {:malli/schema [:=> [:cat :map :any :map [:sequential :any] :map] [:tuple [:= ::child] [:vector :any]]]}
  [w f opts children env]
  (let [k (comp-key f children) prev (get @(:cache w) k) ks (reads f)]
    (vswap! (:children env) conj k)
    (if (and prev (= opts (:opts prev)) (= (count children) (count (:args prev)))
             (every? true? (map ratom/view= children (:args prev)))
             (not (contains? @(:queue w) k))
             (not (and (:reads prev) (seq (:datoms env)) (d/reads-touched? (:reads prev) (:datoms env)))))
      (do (led! w f :hit nil nil) [::child k])
      (let [args (mapv ratom/fresh children)
            db? (contains? ks :seon.db/db)
            o (cond-> opts
                (contains? ks :seon.ctx/ctx) (assoc :seon.ctx/ctx (ratom/fresh (ratom/ref-view (:ref (:src w)) (:changes (:src w)))))
                db? (assoc :seon.db/db ((:db w))))
            order (or (:order prev) (vswap! (:mounts w) inc))
            kids (volatile! [])
            id (key-id k)
            reads (volatile! nil)
            ;; its render and the drawing of its output in one: a lazy part of the hiccup reads (and
            ;; throws) only when it is drawn, and a db render's reads are all of what it drew
            one #(try (with-id (emit w (apply f o args) (assoc env :children kids)) id)
                      (catch Throwable e
                        (with-id [:div {:class "err"} (html {} (contract/error :seon.render/component-defect #'render!
                                                                               (str (symbol f) " threw: " (or (ex-message e) (str e))) {:seon.error/in (symbol f)}))]
                                 id)))
            draw #(if db? (let [[out rs] (d/with-reads one)] (vreset! reads rs) out) (one))
            a0 (profile/alloc-bytes)
            [out rx] (if-let [rx (:rx prev)]
                       (do (ratom/-set! rx :f draw) [(ratom/-run rx false) rx])
                       (ratom/run-in-reaction draw k (fn [k] (vswap! (:queue w) conj k)) {}))
            bytes (- (profile/alloc-bytes) a0)
            children @kids
            same? (and prev (= out (:out prev)))]
        (vswap! (:queue w) disj k)
        (doseq [c (:children prev) :when (not (some #{c} children))] (unmount! w c))
        (led! w f (if same? :same :miss) nil bytes)
        ;; merged: its written html (placed-units) stays while its revision does
        (vswap! (:cache w) update k merge {:f f :opts opts :args args :rx rx :out (if same? (:out prev) out) :children children
                                    :order order :id id :rev (if same? (:rev prev) (inc (:rev prev 0)))
                                    :reads (some-> @reads seq d/reads-index)})
        ;; the components that read the db: the ones a transaction's datoms are matched against
        (vswap! (:db-keys w) (if (seq @reads) conj disj) k)
        [::child k]))))

(defn unmount!
  "Component k gone from walk w: its reaction disposed, its children with it."
  {:malli/schema [:=> [:cat :map [:vector :any]] :nil]}
  [w k]
  (when-let [c (get @(:cache w) k)]
    (some-> (:rx c) ratom/dispose!)
    (vswap! (:cache w) dissoc k)
    (vswap! (:queue w) disj k)
    (vswap! (:db-keys w) disj k)
    (doseq [ch (:children c)] (unmount! w ch)))
  nil)

(defn emit
  "Hiccup hc drawn in walk w: an element's children drawn; a component call rendered (render!) to
  its marker; any other call kept as written, its arguments real values (seon.ratom/realize: a
  call of the printer prints the value, and the model reads the call); a view at a value's place
  its real value drawn by its render (html)."
  {:malli/schema [:=> [:cat :map :any :map] :any]}
  [w x env]
  (cond
    (or (nil? x) (string? x) (number? x) (boolean? x) (keyword? x) (char? x) (h/raw? x)) x
    (ratom/view? x) (html {} (ratom/unwrap x))
    (seq? x) (doall (map #(emit w % env) x))
    (and (vector? x) (keyword? (first x)))
    (let [[tag & more] x attrs (when (map? (first more)) (first more)) kids (if attrs (rest more) more)]
      (into (cond-> [tag] attrs (conj attrs)) (map #(emit w % env)) kids))
    (and (vector? x) (seq (reads (first x)))) (render! w (first x) (or (second x) {}) (drop 2 x) env)
    (and (vector? x) (or (var? (first x)) (fn? (first x)))) (into [(first x)] (map ratom/realize) (rest x))
    :else (ratom/realize x)))

(defn resolved
  "Component k's output in walk w with every child's marker replaced by that child's output: the
  page's hiccup below k."
  {:malli/schema [:=> [:cat :map [:vector :any]] :any]}
  [w k]
  (clojure.walk/postwalk (fn [x] (if (and (vector? x) (= ::child (first x))) (resolved w (second x)) x))
                         (:out (get @(:cache w) k))))

(defn frame!
  "Walk w drawn as of now: its root on the first frame, then each component a change queued (a
  reaction's, after seon.ratom/flush!: Reagent's batching, one frame a commit) or whose db reads
  datoms touch, in mount order, parents first. Answers the root's key."
  {:malli/schema [:=> [:cat :map [:maybe [:sequential :any]]] [:vector :any]]}
  [w datoms]
  (let [env {:children (volatile! []) :datoms datoms}]
    (if-let [root @(:out w)]
      (do (ratom/flush!)
          (when (seq datoms)
            (doseq [k @(:db-keys w) :let [c (get @(:cache w) k)] :when (d/reads-touched? (:reads c) datoms)]
              (vswap! (:queue w) conj k)))
          (loop []
            (when-let [k (first (sort-by #(:order (get @(:cache w) %)) (filter #(contains? @(:cache w) %) @(:queue w))))]
              (let [{:keys [f opts args]} (get @(:cache w) k)] (render! w f opts args (assoc env :children (volatile! []))))
              (recur)))
          root)
      (let [[f opts & children] (:root w) [_ k] (render! w f (or opts {}) (vec children) env)]
        (vreset! (:out w) k)
        k))))

(defn- tree-rev
  "Component k's revision with its children's, below it: what moves when anything it shows changed
  (a child's own output changes the child's revision, not its parent's)."
  {:malli/schema [:=> [:cat :map [:vector :any]] :int]}
  [w k] (let [c (get @(:cache w) k)] (hash (into [(:rev c)] (map #(tree-rev w %)) (:children c)))))

(defn placed-units
  "Walk w's render as the units root's render proc lays out (seon.render.proc/agent-view): each
  component placed directly in a container of the root's output (an element with an id: its list),
  in order, {:id :container :rev :html}: its element id, its container's selector, its revision
  with its children's (moved only when its output, or one below it, changed) and its html (seon.web.html, its children's resolved)."
  {:malli/schema [:=> [:cat :map] [:vector [:map [:id :string] [:container :string] [:rev :int] [:html :string]]]]}
  [w]
  (let [root (get @(:cache w) @(:out w))
        found (volatile! [])]
    (clojure.walk/prewalk (fn [x] (when (and (vector? x) (keyword? (first x)) (map? (second x)) (:id (second x)))
                                    (doseq [m (tree-seq seq? seq (drop 2 x)) :when (and (vector? m) (= ::child (first m)))]
                                      (vswap! found conj [(str "#" (:id (second x))) (second m)])))
                            x)
                          (:out root))
    (vec (for [[sel k] @found :let [c (get @(:cache w) k)]]
           (let [r (tree-rev w k)
                 ;; its html written once a revision (a render of 15 units re-written each frame cost
                 ;; 2.2M ticks for a change that drew nothing): kept on its cache entry, by its revision
                 html (if (= r (:unit-rev c)) (:unit-html c)
                          (let [h (h/html (resolved w k))] (vswap! (:cache w) update k assoc :unit-rev r :unit-html h) h))]
             {:id (:id c) :container sel :rev r :html html})))))

(defn placed-hiccup
  "Walk w's components placed in the root's list with element id sel (\"#p-task\"), in order, as
  [[id hiccup]]: each one's output with its children's resolved, exactly as its render returned it
  (a call of the printer kept as the call: the model reads the data)."
  {:malli/schema [:=> [:cat :map :string] [:vector [:tuple :string :any]]]}
  [w sel]
  (let [root (get @(:cache w) @(:out w))
        found (volatile! [])]
    (clojure.walk/prewalk (fn [x] (when (and (vector? x) (keyword? (first x)) (map? (second x)) (= sel (str "#" (:id (second x)))))
                                    (doseq [m (tree-seq seq? seq (drop 2 x)) :when (and (vector? m) (= ::child (first m)))]
                                      (vswap! found conj (second m))))
                            x)
                          (:out root))
    (vec (for [k @found] [(:id (get @(:cache w) k)) (resolved w k)]))))

(defn draw
  "Tree t (a ctx value: seon.ctx/build's :tree) drawn once by the layout var root-var over db, its
  diff changes: a walker over a reactive atom holding t, framed. Its caller reads it
  (placed-units, placed-hiccup) and disposes it (dispose-walk!): root drawing an agent's branch, the
  model's page at a commit."
  {:malli/schema [:=> [:cat :map :seon.host/fn :seon.host/db :any] :map]}
  [t changes db root-var]
  (let [w (walker {:ref (ratom/atom t) :changes changes} (constantly db) [root-var {}])]
    (frame! w nil)
    w))

(defn ledger
  "Walk w's ledger: by render var, its :miss (ran, output new), :same (ran, output =) and :hit
  (skipped) counts and the bytes its runs allocated."
  {:malli/schema [:=> [:cat :map] :map]}
  [w] @(:ledger w))

(defn dispose-walk!
  "Every component of walk w disposed: a cold walk's end."
  {:malli/schema [:=> [:cat :map] :nil]}
  [w] (when-let [k @(:out w)] (unmount! w k)) nil)

;; ---- the walk: an entity and the units its schema declares (docs/render.md#the-walk) ----

(def entity-schemas
  "{attribute schema}: each attribute an entity schema ({:seon.db/attributes true}) names, to that
  schema. Derived once from the registry (as by-identity); an identity or ref attribute is named
  by one entity schema (seon.render-test/a-walk-is-its-entity-and-its-units)."
  (into {} (for [[k form] contract/forms :when (:seon.db/attributes (contract/properties k)) a (map-keys form)] [a k])))

(def ^:private identity-schemas
  "{identity-attribute schema}: the entity schemas by the upsert keys they name."
  (into {} (filter (comp identity-attr? key)) entity-schemas))

(defn entity-schema
  "The schema of pulled entity e: the entity schema naming one of its identity attributes, else
  the one naming via (the attribute it was reached by, read forward), else nil: no entity schema
  names it, and the one printer shows it."
  {:malli/schema [:=> [:cat :map [:maybe :keyword]] [:maybe :qualified-keyword]]}
  [e via] (or (some identity-schemas (keys e)) (get entity-schemas via)))

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
  pattern, an entity schema with none or whose pattern reaches itself."
  {:malli/schema [:function [:=> [:cat :any] :seon.ctx/spec] [:=> [:cat :any [:set :qualified-keyword]] :seon.ctx/spec]]}
  ([p] (pattern p #{}))
  ([p seen]
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
                           :sub (cond (vector? sub) (pattern sub seen)
                                      ;; an entity schema's name: the pattern it declares
                                      (qualified-keyword? sub)
                                      (cond (seen sub) (bad (str sub "'s pattern reaches itself") clause)
                                            :else (pattern (or (some-> sub contract/properties :seon.ctx/pattern) (bad (str sub " declares no pattern") clause))
                                                           (conj seen sub)))
                                      :else (bad "a join's pattern is a vector, or an entity schema's name" clause))})))]
       {:star? (boolean (some #{'*} p))
        :attrs (into (into #{} (filter keyword?) p) (keep #(when (:rev? %) (:by %))) joins)
        :joins joins}))))

(def ^:private units-by-schema
  "{entity-schema [attribute]}: each entity schema's joins as the attributes they read, in pull's
  syntax (a leading _ the reverse ref), read once from the registry by pattern (as
  entity-schemas is derived)."
  (into {} (for [[s _] contract/forms
                 :let [p (:seon.ctx/pattern (contract/properties s))]
                 :when (and p (:seon.db/attributes (contract/properties s)))]
             [s (mapv (fn [j] (if (:rev? j) (keyword (namespace (:attr j)) (str "_" (name (:attr j)))) (:attr j)))
                      (:joins (pattern p #{s})))])))

(defn units
  "Entity schema s's joins as the attributes they read, in pull's syntax (a leading _ the reverse
  ref): the joins of the pattern it declares (:seon.ctx/pattern, read by pattern), [] with none."
  {:malli/schema [:=> [:cat [:maybe :qualified-keyword]] [:vector :keyword]]}
  [s] (get units-by-schema s []))

(defn- relevant
  "Entity value m of schema, as a walk shows it: only the attributes its schema declares
  relevant (:seon.render/keys) when it declares them, else every one it holds. What is not
  relevant is never pulled into a view (DECISIONS, The context is data, made relevant at its
  producer; renders do not turn it into prose)."
  {:malli/schema [:=> [:cat [:maybe :qualified-keyword] :map] :map]}
  [schema m]
  (if-let [ks (some-> schema contract/properties :seon.render/keys)] (select-keys m ks) m))

(defn- one
  "Entity eid on db as a page lists it: its node (its relevant attributes, less its units) and one
  node per join its entity schema's pattern declares (units: pull's attributes, a leading _ the
  reverse ref), one hop: a neighbour entity ({:kind :entity :eid}, its relevant attributes as
  :value, and :tx, the transaction of the datom that links it: the order it was written) or the
  entity's own value of a non-ref unit ({:kind :value}), each saying the attribute it came by
  (:from); nil when db holds none of it. With limit (a page's rows shown), a unit reached by a
  reverse ref (its rows) is pulled whole only for its newest limit entities; the older are listed
  from the ref's index alone (:seon.render/listed: their entity, transaction and unit's schema,
  nothing read of them), so a page counts them and costs the rows it shows."
  {:malli/schema [:=> [:cat :seon.host/db pos-int? [:maybe pos-int?]] [:maybe [:tuple :seon.render/node [:vector :seon.render/node]]]]}
  [db eid limit]
  (when-let [own (d/pull db '[*] eid)]
    (let [schema (entity-schema own nil)
          units (units schema)
          ref? (fn [a] (or (not= a (forward a)) (dbu/ref? db a)))
          ;; each ref unit's linking datoms, with the transaction that wrote each, newest first: a
          ;; reverse ref's from its index (one read for every row: a ref is in :avet)
          linked (fn [a] (let [f (forward a)]
                           (if (= a f)
                             (map (juxt :v :tx) (d/datoms db :eavt eid a))
                             (sort-by (comp - second) (map (juxt :e :tx) (d/datoms db :avet f eid))))))
          from (fn [a] {:seon.render/eid eid :seon.render/attr a})]
      [(cond-> {:seon.render/kind :entity :seon.render/value (relevant schema (apply dissoc own units)) :seon.render/eid eid}
         schema (assoc :seon.render/schema schema))
       (into [] (mapcat (fn [a]
                          (if (ref? a)
                            (let [fa (forward a) unit-schema (get entity-schemas fa) forward? (= a fa)]
                              (for [[i [e tx]] (map-indexed vector (linked a))]
                                (if (or (nil? limit) forward? (< i limit) (nil? unit-schema))
                                  (let [m (d/pull db '[*] e) s (entity-schema m fa)]
                                    (cond-> {:seon.render/kind :entity :seon.render/value (relevant s (dissoc m :db/id)) :seon.render/eid e
                                             :seon.render/from (from a) :seon.render/tx tx}
                                      s (assoc :seon.render/schema s)))
                                  ;; a row not shown: listed from the index alone, never read
                                  {:seon.render/kind :entity :seon.render/value {} :seon.render/eid e
                                   :seon.render/from (from a) :seon.render/tx tx :seon.render/schema unit-schema :seon.render/listed true})))
                            (when (contains? own a)
                              [{:seon.render/kind :value :seon.render/value (get own a) :seon.render/schema a :seon.render/eid eid :seon.render/from (from a)}]))))
             units)])))

(defn walk
  "Entity eid as a page lists it, on dbs (main first, then the branch that records what main does
  not: an agent's): the default view of any entity (docs/render.md#the-walk). Its node from the
  first db holding it (what root observed of an agent on main wins over a branch's older copy);
  the nodes of its units one hop (one), and of each unit its schema names a part
  (:seon.render/parts: an agent's run) one hop more, each entity once, from the first db holding
  it, newest first by the transaction that linked it (transactions are issued store-wide, so they
  order entities across branches; an id does not: a content id is a hash). Each node says the
  db it came from (:seon.render/db, its index in dbs). No db holding it: :seon.render/no-entity.
  opts :limit, the rows a page shows: each unit's older rows read without their data values (`one`)."
  {:malli/schema [:function [:=> [:cat [:vector :seon.host/db] pos-int?] [:or :seon.render/walk :seon.render/no-entity]]
                  [:=> [:cat [:vector :seon.host/db] pos-int? [:map [:limit {:optional true} pos-int?]]] [:or :seon.render/walk :seon.render/no-entity]]]}
  ([dbs eid] (walk dbs eid {}))
  ([dbs eid {:keys [limit]}]
  (let [;; each db's walk of e; a listed row carries the db that linked it (nothing else names it)
        walks (fn [e] (keep-indexed (fn [i db] (when-let [[r ns] (one db e limit)]
                                                 [r (mapv #(cond-> % (:seon.render/listed %) (assoc :seon.render/db i)) ns) i]))
                                    dbs))
        ws (walks eid)
        ;; the first db holding entity e: one index read a db
        held (fn [e] (first (keep-indexed (fn [i db] (when (seq (d/datoms db :eavt e)) i)) dbs)))]
    (if-let [[root _ i] (first ws)]
      (let [parts (set (:seon.render/parts (contract/properties (:seon.render/schema root))))
            near (mapcat second ws)
            nodes (concat near
                          (for [nd near :when (and (= :entity (:seon.render/kind nd)) (parts (get-in nd [:seon.render/from :seon.render/attr])))
                                [_ subs] (walks (:seon.render/eid nd)) sub subs :when (not= eid (:seon.render/eid sub))]
                            sub))
            ;; a listed row is the db's that linked it (nothing of it read), the rest the first db holding it
            first-db (fn [nd] (cond (:seon.render/listed nd) (:seon.render/db nd)
                                    (= :entity (:seon.render/kind nd)) (or (held (:seon.render/eid nd)) i)
                                    :else i))
            key-of (fn [nd] (if (= :entity (:seon.render/kind nd)) (:seon.render/eid nd) [(:seon.render/eid nd) (get-in nd [:seon.render/from :seon.render/attr])]))]
        {:seon.render/root (assoc root :seon.render/db i)
         :seon.render/nodes (->> (vals (reduce (fn [m nd] (let [k (key-of nd)] (if (contains? m k) m (assoc m k (assoc nd :seon.render/db (first-db nd)))))) {} nodes))
                                 ;; each node's key made once (sort-by would make it once per comparison)
                                 (map (fn [nd] [[(- (:seon.render/tx nd 0)) (get-in nd [:seon.render/from :seon.render/attr])] nd]))
                                 (sort-by first)
                                 (mapv second))})
      (contract/error :seon.render/no-entity #'seon.render/walk (str "no entity " eid " on these dbs") {:seon.render/eid eid})))))
