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
    1. the value's own override key (:seon.render/html, in the map or its metadata): a render
       symbol (the value rendered itself, never one nested in another: there it is a schema's
       properties, data), or already-rendered hiccup;
    2. the render its schema declares: a schema's property {:seon.render/html sym} in
       resources/seon/schemas/*.edn (seon.contract/forms), the
       schema then its parents ([:and p ...], [:merge p ...]). The value's schema is never
       guessed: the producer names it (opts :schema, or the value's metadata
       :seon.render/schema), an error map's (one holding every key :seon.error/base requires)
       is its :seon.error/type (else :seon.error/base), and a pulled entity's is the schema with a declared render holding its identity
       attribute;
    3. the one value printer (seon.print/print-value), the default render.
  A nested value renders by a call ([render opts v] in hiccup, or render's result)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.print :as print] [seon.print.select :as select] [seon.config :as config]
            [clojure.string :as str] [clojure.walk] [datahike.api :as d] [datahike.reads :as reads] [datahike.db.utils :as dbu] [seon.web.html :as h] [seon.ratom :as ratom] [seon.profile :as profile]))

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

(defn model-declared
  "The model render symbol schema k (or its nearest parent) declares, {:seon.render/model sym}: what the printer shows of a
  value of that schema (docs/render.md#a-render-is-a-value-printed-once), or nil (the value itself)."
  {:malli/schema [:=> [:cat [:maybe :qualified-keyword]] [:maybe :symbol]]}
  [k]
  (when k
    (or (:seon.render/model (contract/properties k))
        (some model-declared (parents k)))))

(defn full-declared?
  "Does schema k declare its values print whole, {:seon.print/full true}: a value that is a page (docs/render.md#a-part-prints-as-its-render)?"
  {:malli/schema [:=> [:cat [:maybe :qualified-keyword]] :boolean]}
  [k] (boolean (and k (:seon.print/full (contract/properties k)))))

(defn model
  "What the printer shows in place of value v (seon.print/print-value's :render): {:value m} when v's schema (its metadata
  :seon.render/schema) declares a model render (`model-declared`), which makes a value
  (docs/render.md#a-render-is-a-value-printed-once) that the one printer then prints, at its budget, with its own cuts; else
  nil, for one metadata read. The render is called (f opts v). See docs/render.md#a-render-is-a-value-printed-once."
  {:malli/schema [:function [:=> [:cat :any] [:maybe [:map [:value :any]]]] [:=> [:cat :any :map] [:maybe [:map [:value :any]]]]]}
  ([v] (model v {}))
  ([v opts]
   (let [k (:seon.render/schema (meta v)) sym (model-declared k) full? (full-declared? k)]
     (when (or sym full?)
       ;; a schema that declares no render shows the value itself, no longer named by it (the printer would draw it by this again)
       (cond-> {:value (if sym ((requiring-resolve sym) opts v) (vary-meta v dissoc :seon.render/schema))} full? (assoc :full true))))))

(defn- identity-attr?
  "Is attribute a an upsert key ([:and {:seon.db/identity true} ...])?"
  {:malli/schema [:=> [:cat :any] :boolean]}
  [a] (boolean (:seon.db/identity (contract/properties a))))

(defn map-keys
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

(def ^:private error-keys
  "The keys :seon.error/base requires, read once from its form at load: what schema-of asks of a
  map before it draws it as an error. See docs/render.md#render-what-a-person-or-a-model-reads."
  (vec (keep #(when (and (vector? %) (not (:optional (when (map? (second %)) (second %))))) (first %))
             (rest (some #(when (and (vector? %) (= :map (first %))) %) (get contract/forms :seon.error/base))))))

(defn schema-of
  "The named schema of v: hint (its producer's), the one its metadata names, an error map's
  variant (error-keys), or the schema its identity attribute names. See docs/render.md#render-what-a-person-or-a-model-reads."
  {:malli/schema [:=> [:cat :any [:maybe :qualified-keyword]] [:maybe :qualified-keyword]]}
  [v hint]
  (or hint (:seon.render/schema (meta v))
      (when (map? v)
        (if-let [t (when (every? #(contains? v %) error-keys) (:seon.error/type v))]
          (if (contains? contract/forms t) t :seon.error/base)
          (some by-identity (keys v))))))

(declare render-of own-key)

(defn- nested
  "The printer's :render: a nested value whose schema declares a render, as a call of that render
  inline ({:text, the value in one line; :html, the call}), else nil. Its render is looked up by
  render-of (seon.render/schema-of: one metadata read or one map lookup per key, never a
  validation). A nested map holding a render symbol under :seon.render/html is data, printed as it
  is: it is what a schema's properties look like inside a printed schema form, and drawing it by
  that render called the render with the properties (an agent's registered :lab.ctx/steps form,
  its lab.ui/steps called with {:seon.render/html lab.ui/steps}, killed the agent drawing it)."
  {:malli/schema [:=> [:cat :any] [:maybe [:map [:text :string] [:html :any]]]]}
  [v]
  (when-let [h (when-not (symbol? (own-key v :seon.render/html)) (render-of v nil))]
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

(defn inputs
  "Which of :seon.ctx/ctx and :seon.db/db render var v's opts contract asks to receive: a render
  that declares either is a component, kept in the walk's cache, those keys filled in."
  {:malli/schema [:=> [:cat :any] [:set :qualified-keyword]]}
  [v] (into #{} (filter #{:seon.ctx/ctx :seon.db/db}) (tree-seq vector? seq (opts-form v))))

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
  "Text s as part of an element id, one to one: letters, digits, . and - as themselves, every other
  character _ and its code in hex (a.b/c -> a.b_2Fc, a_b -> a_5Fb)."
  {:malli/schema [:=> [:cat :string] :string]}
  [s] (apply str (map #(if (or (Character/isLetterOrDigit ^char %) (= \. %) (= \- %)) % (format "_%02X" (int %))) s)))

(defn key-id
  "Key k as its component's root element id, one to one: its render's name and the name of what it shows (seon.render.ui/eval-item of eval e12 ->
  \"eval-item-e12\"; a var cited, seon.render.ui/cited of demo/g -> \"cited-demo_2Fg\"), never an entity id, since the model reads ids and does not guess
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
  for the next flush!, found in the cache when it fires. A cold walk (root drawing a branch) is a
  walker drawn once."
  {:malli/schema [:=> [:cat :map :seon.host/fn [:vector :any]] :map]}
  [src db-fn root]
  {:src src :db db-fn :root root :cache (volatile! {}) :queue (volatile! #{}) :mounts (volatile! 0)
   :ledger (volatile! {}) :out (volatile! nil) :db-keys (volatile! #{}) :readers (volatile! {}) :forms (volatile! {}) :watched (volatile! #{}) :inputs (volatile! {}) :gone (volatile! #{}) :skipped (volatile! #{})})

(defn- led!
  "Count k (:hit, :miss, :same) for render var f in w's ledger, with the ticks and bytes a run took."
  {:malli/schema [:=> [:cat :map :any :keyword [:maybe :int] [:maybe :int]] :nil]}
  [w f k ticks bytes]
  (vswap! (:ledger w) update (symbol f) #(-> (or % {}) (update k (fnil inc 0)) (update :ticks (fnil + 0) (or ticks 0)) (update :bytes (fnil + 0) (or bytes 0))))
  nil)

(declare emit unmount!)

(defn- with-id
  "Hiccup hc with its root element's attributes merged with attrs: a component's key as its id and
  its render's name as its one class, in place of any class the render gave its root (its style is
  its var's :seon.render/style: `styled`). A render that drew nothing (nil) stays nothing: no element
  to hold it, so no part is placed for it (docs/render.md#a-component-answers-hiccup)."
  {:malli/schema [:=> [:cat :any :map] :any]}
  [hc attrs]
  (cond
    (nil? hc) nil
    (and (vector? hc) (keyword? (first hc)))
    (let [[tag & more] hc]
      (if (map? (first more)) (into [tag (merge (first more) attrs)] (rest more)) (into [tag attrs] more)))
    :else [:div attrs hc]))

(defn- forget-readers!
  "Component k out of walk w's index of readers, for the read keys it held. See docs/render.md#components."
  {:malli/schema [:=> [:cat :map [:vector :any] [:maybe [:set :any]]] :nil]}
  [w k read-keys]
  (doseq [rk read-keys] (vswap! (:readers w) update rk disj k)))

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
      (let [args (mapv ratom/fresh children)
            db? (contains? ks :seon.db/db)
            o (cond-> opts
                (contains? ks :seon.ctx/ctx) (assoc :seon.ctx/ctx (ratom/fresh (ratom/ref-view (:ref (:src w)) (:changes (:src w)))))
                db? (assoc :seon.db/db ((:db w))))
            ;; a render drawn for the first time in this walk: watched, so its redefinition draws it again
            _ (when (and (var? f) (not (contains? @(:watched w) f)))
                (vswap! (:watched w) conj f)
                (add-watch f (:watched w) (fn [_ _ _ _] (vswap! (:queue w) into (keep (fn [[ck c]] (when (= f (:f c)) ck))) @(:cache w)))))
            order (or (:order prev) (vswap! (:mounts w) inc))
            kids (volatile! [])
            id (key-id k)
            reads (volatile! nil)
            ;; its render and the drawing of its output in one: a lazy part of the hiccup reads (and
            ;; throws) only when it is drawn, and a db render's reads are all of what it drew
            one #(try (with-id (emit w (apply f o args) (assoc env :children kids :self k)) {:id (or (when (var? f) (:seon.render/list (meta f))) id) :class (name (first k))})
                      ;; a component that throws draws its error in its place: a call of the error's render on the error as data (what
                      ;; threw, and the thrown error's own data: a contract's problems), so the page writes that render and the model
                      ;; reads the call, the error in it, in the conversation a user message at its place; every other component draws
                      (catch Throwable e
                        (with-id [#'html {:schema :seon.render/component-defect :as :block}
                                  (contract/error :seon.render/component-defect #'render!
                                                  (str (symbol f) " threw: " (or (ex-message e) (str e)))
                                                  (cond-> {:seon.error/in (symbol f)}
                                                    (map? (ex-data e)) (assoc :seon.render/threw (ex-data e))))]
                                 {:id id :class "err" :data-role "user"})))
            draw #(if db? (let [[out rs] (d/with-reads one)] (vreset! reads rs) out) (one))
            a0 (profile/alloc-bytes)
            [out rx] (if-let [rx (:rx prev)]
                       (do (ratom/-set! rx :f draw) [(ratom/-run rx false) rx])
                       (ratom/run-in-reaction draw k (fn [k] (vswap! (:queue w) conj k)) {}))
            bytes (- (profile/alloc-bytes) a0)
            children @kids
            same? (and prev (= out (:out prev)))
            read-keys (some-> @reads seq d/reads-index)]
        (vswap! (:queue w) disj k)
        ;; who holds each child, moved only when its children did: one that lost its last parent goes at the flush's end
        (when (not= (seq children) (seq (:children prev)))
          (let [was (set (:children prev)) now (set children)]
            (doseq [c now :when (not (was c))] (vswap! (:cache w) update-in [c :parents] (fnil conj #{}) k))
            (doseq [c was :when (not (now c))] (vswap! (:cache w) update-in [c :parents] disj k) (vswap! (:gone w) conj c))))
        ;; what a reader of the tree keeps of those above it (`wire-of`) is not what it was: only they hold what moved
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
                                    :order order :id id :rev (if same? (:rev prev) (inc (:rev prev 0)))
                                    :reads read-keys})
        ;; the components that read the db: the ones a transaction's datoms are matched against
        (vswap! (:db-keys w) (if (seq @reads) conj disj) k)
        (when (:reads prev) (forget-readers! w k (:reads prev)))
        (doseq [rk read-keys] (vswap! (:readers w) update rk (fnil conj #{}) k))
        [::child k]))))

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
  "Is x, an element or a component call, for the page alone (its attributes or opts :data-for \"page\": what the model does not read)? See
  docs/render.md#a-part-prints-as-its-render."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [x] (and (vector? x) (map? (second x)) (= "page" (:data-for (second x)))))

(defn emit
  "Hiccup hc drawn in walk w: an element's children drawn; a component call rendered (render!) to
  its marker; any other call kept as written, its arguments real values (seon.ratom/realize: a
  call of the printer prints the value, and the model reads the call); a view at a value's place
  its real value drawn by its render (html). A walk no person reads (:model-only) draws nothing for the page alone."
  {:malli/schema [:=> [:cat :map :any :map] :any]}
  [w x env]
  (cond
    (or (nil? x) (string? x) (number? x) (boolean? x) (keyword? x) (char? x) (h/raw? x)) x
    (ratom/view? x) (html {} (ratom/unwrap x))
    (seq? x) (doall (map #(emit w % env) x))
    (and (:model-only w) (page-only? x)) (do (vswap! (:skipped w) conj (:self env)) nil)
    (and (vector? x) (keyword? (first x)))
    (let [[tag & more] x attrs (when (map? (first more)) (first more)) kids (if attrs (rest more) more)]
      (into (cond-> [tag] attrs (conj attrs)) (map #(emit w % env)) kids))
    ;; a form drawn before whose component nothing queued: its marker, with nothing asked of it (a list's earlier
    ;; members cost the list a lookup each). Only plain arguments: a view's equality reads the source
    (and (vector? x) (var? (first x)) (when-let [k (get @(:forms w) x)] (and (contains? @(:cache w) k) (not (contains? @(:queue w) k)))))
    (let [k (get @(:forms w) x)] (vswap! (:children env) conj k) [::child k])
    (and (vector? x) (seq (inputs-in w (first x))))
    (let [m (render! w (first x) (or (second x) {}) (drop 2 x) env)]
      (when (every? (some-fn number? string? keyword? nil?) (drop 2 x))
        (vswap! (:forms w) assoc x (second m))
        (vswap! (:cache w) update (second m) assoc :form x))
      m)
    (and (vector? x) (or (var? (first x)) (fn? (first x)))) (into [(first x)] (map ratom/realize) (rest x))
    :else (ratom/realize x)))

(defn- styled
  "Component output out as the page writes it: its root's class, its render's name, followed by the
  Tailwind classes render var f states (:seon.render/style, literal text in its source, so the
  stylesheet's scan builds them): the style of each render drawn, by reference, never in what the
  model reads; and the render's name as :data-render, which a block shows. See docs/render.md#where-things-go."
  {:malli/schema [:=> [:cat :any :any] :any]}
  [out f]
  (let [style (:seon.render/style (meta f))]
    (if (and (vector? out) (map? (second out)) (:class (second out)))
      (cond-> out
        style (update-in [1 :class] str " " style)
        (var? f) (assoc-in [1 :data-render] (str (symbol f))))
      out)))

(defn resolved
  "Component k's output in walk w with every child's marker replaced by that child's output: the
  page's hiccup below k. With html? true each component as the page writes it (styled)."
  {:malli/schema [:function [:=> [:cat :map [:vector :any]] :any] [:=> [:cat :map [:vector :any] :boolean] :any]]}
  ([w k] (resolved w k false))
  ([w k html?]
   (let [c (get @(:cache w) k)]
     (clojure.walk/postwalk (fn [x] (if (and (vector? x) (= ::child (first x))) (resolved w (second x) html?) x))
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
  {:malli/schema [:=> [:cat :map [:or [:= :all] [:maybe [:sequential :any]]]] [:map-of [:vector :any] [:enum :miss :same]]]}
  [w datoms]
  (let [env {:children (volatile! []) :ran (volatile! {})}]
    (if-let [root @(:out w)]
      (let [held? (fn held? [k] (or (= root k) (some held? (:parents (get @(:cache w) k)))))]
          (ratom/flush!)
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
        @(:ran env)))))

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
  the root that placed-units and placed-hiccup read. See docs/render.md#a-layout-places-its-lists."
  {:malli/schema [:function [:=> [:cat :map] [:vector [:tuple :string [:vector :any]]]]
                  [:=> [:cat :map [:sequential :string]] [:vector [:tuple :string [:vector :any]]]]]}
  ([w] (placed w nil))
  ([w sels]
  (let [found (volatile! [])
        want (some-> sels set)
        list (fn [k] (let [f (:f (get @(:cache w) k))] (when (var? f) (:seon.render/list (meta f)))))
        walk (fn walk [out]
               (clojure.walk/prewalk (fn [x] (when (and (vector? x) (keyword? (first x)) (map? (second x)) (:id (second x)))
                                               (doseq [m (tree-seq seq? seq (drop 2 x)) :when (and (vector? m) (= ::child (first m)) (some? (:out (get @(:cache w) (second m)))))]
                                                 (if-let [l (list (second m))]
                                                   ;; a list a reader of some lists did not ask for is not walked
                                                   (when (or (nil? want) (want (str "#" l))) (walk (:out (get @(:cache w) (second m)))))
                                                   (vswap! found conj [(str "#" (:id (second x))) (second m)]))))
                                       x)
                                     out))]
    (walk (:out (get @(:cache w) @(:out w))))
    (if sels (vec (mapcat (group-by first @found) sels)) @found))))

(defn placed-units
  "Walk w's render as the units root's render proc lays out (seon.render.ui/page-chrome): each
  component placed in a container of the root's output (placed), {:id :container :rev :html}: its
  element id, its container's selector, its revision with its children's (moved only when its
  output, or one below it, changed) and its html (seon.web.html, its children's resolved)."
  {:malli/schema [:=> [:cat :map] [:vector [:map [:id :string] [:container :string] [:rev :int] [:html :string]]]]}
  [w]
  (vec (for [[sel k] (placed w) :let [c (get @(:cache w) k)]]
         (let [r (tree-rev w k)
               ;; its html written once a revision (a render of 15 units re-written each flush cost
               ;; 2.2M ticks for a change that drew nothing): kept on its cache entry, by its revision
               html (if (= r (:unit-rev c)) (:unit-html c)
                        (let [h (h/html (resolved w k true))] (vswap! (:cache w) update k assoc :unit-rev r :unit-html h) h))]
           {:id (:id c) :container sel :rev r :html html}))))

(defn- strip-attrs
  "An element's attributes as the model reads them: its presentation (class, style) is the page's; an id names it, so the model need not guess it."
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [attrs] (not-empty (dissoc attrs :class :style)))

(defn- size
  "The characters x holds, as the one sizer counts them: its strings and the text of its other leaves, a mark for each collection
  and nothing printed: an estimate of what is shown, not a text made."
  {:malli/schema [:=> [:cat :any] nat-int?]}
  [x] (cond (coll? x) (reduce + 1 (map size x)) (nil? x) 0 :else (count (str x))))

(defn- tokens-of
  "What x costs the context in tokens: the one sizer (`seon.print.emit/chars-tokens`) over the characters x holds, the same count
  a part's head (`part-form`) and the tree (`tree`) give."
  {:malli/schema [:=> [:cat :any] nat-int?]}
  [x] (select/chars-tokens (size x)))

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
  "Component k of walk w as the form the model reads it, `[seon.render.ui/status {:ctx [:task :proved]} [:h2 ...] ...]`:
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
    (when (and (seq body) (not= "page" (:data-for (:opts c))))
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
  column 0), a string with a line break or a quote the prose marker (verbatim, not escaped), the rest as it is."
  {:malli/schema [:=> [:cat :any] :any]}
  [x]
  (cond
    (and (vector? x) (= :pre (first x)) (= 2 (count x)) (vector? (second x)) (= :code (first (second x))) (string? (last (second x))))
    [:pre {:seon.print/code (fence (last (second x)))}]
    ;; a vector's children without the nils a (when ..) leaves and with the lists a (for ..) makes spliced in, as the page's writer reads them
    (vector? x) (with-meta (into [] (comp (mapcat (fn [c] (if (seq? c) c [c]))) (remove nil?) (map printable)) x) (meta x))
    (and (string? x) (or (str/includes? x "\n") (str/includes? x "\""))) {:seon.print/prose x}
    (map? x) (if (:seon.print/code x) x (into (empty x) (map (fn [[k v]] [k (printable v)])) x))
    :else x))

(defn part-text
  "Part form f (`part-form`) printed as text the model reads: whole (:seon.print/full), the head and its path, then the
  hiccup, a verbatim block in its fence. See docs/render.md#a-part-prints-as-its-render."
  {:malli/schema [:=> [:cat :any] :string]}
  [f] (print/text (vary-meta (printable f) assoc :seon.print/full true)))

(defn parts-value
  "Parts m ({id form}) as the value an eval answers: each form ready to print (`printable`), the map named a :seon.render/parts,
  which prints whole (:seon.print/full on its schema), the stored value too. See docs/render.md#the-render-is-an-eval."
  {:malli/schema [:=> [:cat :map] :seon.render/parts]}
  [m] (with-meta (into (array-map) (map (fn [[id f]] [id (printable f)])) m) {:seon.render/schema :seon.render/parts :seon.print/full true}))

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
  (vec (for [[_ k] (placed w sels) :let [c (get @(:cache w) k)] :when (not= "page" (:data-for (:opts c)))] [(:id c) k])))

(defn revs
  "The revisions of the parts of walk w placed in the lists sels, {id tree-rev}: what moves when a part's output, or one below it,
  changed. Compared with an earlier answer it says whether anything moved, with no part made. See docs/render.md#the-render-is-an-eval."
  {:malli/schema [:=> [:cat :map [:sequential :string]] [:map-of :string :int]]}
  [w sels]
  (into {} (map (fn [[id k]] [id (tree-rev w k)])) (placed-ids w sels)))

(defn parts-since
  "The parts of walk w placed in the lists sels that moved since seen ({id tree-rev} of an earlier answer): {:parts {id form}
  :revs {id tree-rev} :gone [id]}. The walk already compared each part's output with its last (a revision moves only when it
  differs, `render!`), so nothing is compared or drawn here but the revisions (`revs`), and a part's form (`part-form`) is made only for a
  part that moved; nothing moved, nothing made. A walk new to its process has seen nothing: every part."
  {:malli/schema [:=> [:cat :map [:sequential :string] [:maybe :map]] [:map [:parts :map] [:revs :map] [:gone [:vector :string]]]]}
  [w sels seen]
  (let [ps (placed-ids w sels)
        revs (into {} (map (fn [[id k]] [id (tree-rev w k)])) ps)]
    {:parts (into (array-map) (keep (fn [[id k]] (when (not= (get seen id) (get revs id)) (when-let [f (part-form w k)] [id f])))) ps)
     :revs revs
     :gone (vec (remove revs (keys seen)))}))

(defn draw
  "Tree t (a ctx value: seon.ctx/build's :tree) drawn once by the layout var root-var over db, its
  diff changes: a walker over a reactive atom holding t, flushed once. Its caller reads it
  (placed-units, placed-hiccup, `wire`) and disposes it (dispose-walk!): root drawing an agent's branch, the
  model's request at a commit. opts :model-only true, for a reader that is the model: nothing for the page alone is drawn (`emit`), so
  that read costs what the model reads (docs/render.md#a-layout-places-its-lists)."
  {:malli/schema [:function [:=> [:cat :map :seon.host/fn :seon.host/db :any] :map]
                  [:=> [:cat :map :seon.host/fn :seon.host/db :any [:map [:model-only {:optional true} :boolean]]] :map]]}
  ([t changes db root-var] (draw t changes db root-var {}))
  ([t changes db root-var opts]
   (let [w (cond-> (walker {:ref (ratom/atom t) :changes changes} (constantly db) [root-var {}]) (:model-only opts) (assoc :model-only true))]
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
               :when (and (:seon.render/list m) (not (:seon.render/messages m)) (not= "page" (:data-for opts)))]
           (str "#" (:seon.render/list m))))))

;;; --- the model's reading of the tree: one generic conversion to provider-neutral messages ---------

(declare wire-of)

(defn- attrs-kids
  "Element x as [attrs children]."
  {:malli/schema [:=> [:cat :any] [:tuple [:maybe :map] [:sequential :any]]]}
  [x] (if (and (map? (second x)) (not (h/raw? (second x)))) [(second x) (drop 2 x)] [nil (rest x)]))

(defn- read-role
  "acc ({:text [strings] :fields {name text} :nested [nodes]}) with node x, inside an element that is a message, read: text as itself, a plain call as its last argument (a string as it
  is, a value printed: the page's writer expands the call, the model reads what it was given), a value printed, an element's children; a child marked `:data-field` its
  own field; a component or an element that is a message of its own left for after this one; what is for the page alone nothing."
  {:malli/schema [:=> [:cat :map :any] :map]}
  [acc x]
  (cond
    (or (nil? x) (false? x)) acc
    (string? x) (update acc :text conj x)
    (or (number? x) (keyword? x) (char? x) (true? x)) (update acc :text conj (str x))
    (h/raw? x) (update acc :text conj (str x))
    (seq? x) (reduce read-role acc x)
    (and (vector? x) (= ::child (first x))) (update acc :nested conj x)
    (page-only? x) acc
    (and (vector? x) (keyword? (first x)))
    (let [[attrs kids] (attrs-kids x)]
      (cond
        (:data-role attrs) (update acc :nested conj x)
        (:data-field attrs) (let [sub (reduce read-role {:text [] :nested []} kids)]
                              (-> acc
                                  (update :fields (fnil assoc (array-map)) (keyword (:data-field attrs)) (apply str (:text sub)))
                                  (update :nested into (:nested sub))))
        :else (reduce read-role acc kids)))
    (and (vector? x) (or (var? (first x)) (fn? (first x))))
    (let [v (peek x)] (update acc :text conj (if (string? v) v (print/text v))))
    :else (update acc :text conj (print/text x))))

(defn- message-of
  "The provider-neutral message of an element (attrs) read as acc (`read-role`), under env's :reply, or nil: an assistant's call from its id and its fields code and intent, an assistant's words from
  its fields content and reasoning (no words, no message), a tool's result its text, any other its text, or its fields as the one printer prints a map. See
  docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat :map :map :map] [:maybe :map]]}
  [attrs {:keys [text fields]} env]
  (let [role (:data-role attrs) reply (or (:data-reply attrs) (:reply env))]
    (case role
      "assistant" (cond
                    (:data-call-id attrs)
                    (cond-> {:role role :content "" :tool-calls [(cond-> {:id (:data-call-id attrs) :code (:code fields)} (:intent fields) (assoc :intent (:intent fields)))]}
                      reply (assoc :reply reply))
                    (or (contains? fields :content) (contains? fields :reasoning))
                    (cond-> {:role role :content (:content fields "")}
                      (contains? fields :reasoning) (assoc :reasoning (:reasoning fields))
                      reply (assoc :reply reply)))
      "tool" {:role role :tool-call-id (:data-call-id attrs) :content (apply str text)}
      {:role role :content (if (seq fields) (print/text fields (:context print/budgets)) (apply str text))})))

(defn- wire-walk
  "Node x of walk w (nil: a hiccup alone) as parts [ref message turn], in order: an element marked :data-role a message and then what it left for after (`read-role`), its :data-ref the ref of that
  message, a :data-ref on an element that is no message the ref of all within it (an example session); a :data-turn the turn of all within it; a component by its cached parts; what is for the page alone nothing. See
  docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat [:maybe :map] :any :map] [:vector :any]]}
  [w x env]
  (cond
    (and (vector? x) (= ::child (first x))) (wire-of w (second x) env)
    (seq? x) (into [] (mapcat #(wire-walk w % env)) x)
    (page-only? x) []
    (and (vector? x) (keyword? (first x)))
    (let [[attrs kids] (attrs-kids x)
          env (cond-> env (:data-turn attrs) (assoc :turn (:data-turn attrs)))]
      (if-let [role (:data-role attrs)]
        (let [acc (reduce read-role {:text [] :nested []} kids)
              m (message-of attrs acc env)
              inner (cond-> env (:data-reply attrs) (assoc :reply (:data-reply attrs)))]
          (into (if m [[(or (:ref env) (:data-ref attrs)) m (:turn env)]] [])
                (mapcat #(wire-walk w % inner)) (:nested acc)))
        (let [env (cond-> env (:data-ref attrs) (assoc :ref (:data-ref attrs)))]
          (into [] (mapcat #(wire-walk w % env)) kids))))
    :else []))

(defn- wire-of
  "Component k's parts (`wire-walk`), kept on its cache entry by its revision and let go when one below it moves (`render!`): a request reads what moved and what holds it, and takes the rest as it was read. See
  docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat :map [:vector :any] :map] [:vector :any]]}
  [w k env]
  (let [{:keys [rev wire-at wire out]} (get @(:cache w) k)
        at [rev env]]
    (if (= at wire-at)
      wire
      (let [ps (wire-walk w out env)]
        (vswap! (:cache w) update k assoc :wire-at at :wire ps)
        ps))))

(defn hiccup-wire
  "Hiccup x with no walk behind it as parts [ref messages turn], the same reading (`wire`): a piece a request adds to the tree. See
  docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat :any] [:vector :any]]}
  [x] (mapv (fn [[r m t]] [r [m] t]) (wire-walk nil x {})))

(defn wire
  "What the model reads of walk w: its lists that hold messages (a list var's :seon.render/messages) through one generic reading, in order, as parts [ref messages turn], the messages of one ref together. The one way
  the tree becomes provider-neutral messages; seon.model/wire-groups gives them the wire's structure. See docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat :map] [:vector :any]]}
  [w]
  (let [cache @(:cache w)
        ps (into [] (mapcat (fn [k] (let [{:keys [f]} (get cache k)] (when (and (var? f) (:seon.render/messages (meta f))) (wire-of w k {})))))
                 (:children (get cache @(:out w))))]
    (reduce (fn [acc [r m t]]
              (let [[r0 ms t0] (peek acc)]
                (if (and (seq acc) (some? r) (= r r0)) (conj (pop acc) [r0 (conj ms m) t0]) (conj acc [r [m] t]))))
            [] ps)))

(defn ledger
  "Walk w's ledger: by render var, its :miss (ran, output new), :same (ran, output =) and :hit
  (skipped) counts and the bytes its runs allocated."
  {:malli/schema [:=> [:cat :map] :map]}
  [w] @(:ledger w))

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
                          :tokens (tokens-of (:out c))
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
