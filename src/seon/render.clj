(ns seon.render
  "Render selection and the one render call (W1's proto.render and proto.render.schema,
  ported onto next's registry: notes/archive/option3-W1-render.md §1).

  A render is a plain function (value opts) -> its result, like a React component: opts are
  its props, all optional (:as, the room it is shown in, :inline, :block or :tile; :budget,
  its tokens; :handle, the form its cuts read the rest through), and a function that does not
  care about one ignores it (owner, 2026-10-23: \"It's all just functions and results\"). An
  :html render answers hiccup (serialized by seon.web.html, where a vector headed by a
  function is a call, expanded as Reagent does); an :ai render answers text, or the printer's
  result {:text :truncated?}. Selection, first match wins, and nothing else (owner,
  2026-10-20: \"Everything has a schema right so we say this renders this\"):
    1. the value's own override key (:seon.render/html or :seon.render/ai, in the map or its
       metadata): a render symbol, or already-rendered data;
    2. the render its schema declares: a schema's properties {:seon.render/html sym
       :seon.render/ai sym} in resources/seon/schemas/*.edn (seon.contract/forms), the
       schema then its parents ([:and p ...], [:merge p ...]). The value's schema is never
       guessed: the producer names it (opts :schema, or the value's metadata
       :seon.render/schema), an error map's is its :seon.error/type (else :seon.error/base),
       and a pulled entity's is the schema with a declared render holding its identity
       attribute;
    3. the one value printer (seon.print/print-value), the default render: text for :ai,
       hiccup for :html.
  A nested value renders by a call ([render v opts] in hiccup, or render's result)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.print :as print] [seon.print.select :as select] [seon.config :as config]
            [clojure.string :as str] [datahike.api :as d] [seon.ref :as ref] [seon.docs :as docs]))

(def faces "Each face's key." {:html :seon.render/html :ai :seon.render/ai})

(defn parents
  "The named schemas k's form extends ([:and p ...] or [:merge p ...])."
  {:malli/schema [:=> [:cat [:maybe :qualified-keyword]] [:vector :qualified-keyword]]}
  [k]
  (let [form (get contract/forms k)]
    (if (and (vector? form) (#{:and :merge} (first form)))
      (filterv #(and (qualified-keyword? %) (contains? contract/forms %)) (rest form))
      [])))

(defn declared
  "The render symbol schema k (or its nearest parent) declares for face, or nil."
  {:malli/schema [:=> [:cat [:maybe :qualified-keyword] :seon.render/face] [:maybe :symbol]]}
  [k face]
  (when k
    (or (get (contract/properties k) (faces face))
        (some #(declared % face) (parents k)))))

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
                 :when (or (declared k :html) (declared k :ai))
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

(declare render-of render)

(defn- nested
  "The printer's :render: a nested value whose schema declares a render, as that render's one
  line ({:text, its :ai render inline; :html, a call of its :html render inline}), else nil.
  One lookup per map (seon.render/schema-of)."
  {:malli/schema [:=> [:cat :any] [:maybe [:map [:text :string] [:html :any]]]]}
  [v]
  (let [h (render-of v :html nil) a (render-of v :ai nil)]
    (when (or h a)
      ;; one line, as the printer's row needs it: a render that ignores :inline is cut to one by the printer
      (let [text (print/line (:text (render v {:face :ai :as :inline :budget (config/value :seon.config.render/line-budget)}))
                             (config/value :seon.config.render/line-budget))]
        {:text text :html (if h [h v {:as :inline}] text)}))))

(defn- printer-opts
  "The printer's options for a render's opts: its budget (else :seon.config.render/value-budget),
  its handle, and its nested values' own renders (nested)."
  {:malli/schema [:=> [:cat :seon.render/opts] :map]}
  [opts] {:budget (or (:budget opts) (config/value :seon.config.render/value-budget)) :handle (:handle opts) :render nested})

(defn print-html
  "The one value printer as the default :html render: v's structural hiccup under opts' budget,
  or one line (seon.print/line) when shown :inline."
  {:malli/schema [:=> [:cat :any :seon.render/opts] :any]}
  [v opts]
  (if (= :inline (:as opts))
    [:code {:class "seon-inline"} (print/line v (or (:budget opts) (config/value :seon.config.render/line-budget)) (:handle opts))]
    (let [r (print/print-value v (assoc (printer-opts opts) :outputs #{:hiccup}))]
      (or (:html r) [:span {:class "seon-print-refused"} (str (:seon.error/message r))]))))

(defn print-ai
  "The one value printer as the default :ai render: v's text under opts' budget ({:text
  :truncated?}), one line when shown :inline; the error's message when even its first part
  does not fit."
  {:malli/schema [:=> [:cat :any :seon.render/opts] :seon.render/text]}
  [v opts]
  (if (= :inline (:as opts))
    (let [t (print/line v (or (:budget opts) (config/value :seon.config.render/line-budget)) (:handle opts))] {:text t :truncated? false})
    (let [r (print/print-value v (printer-opts opts))]
      {:text (or (:text r) (:seon.error/message r)) :truncated? (boolean (:truncated? r))})))

(defn- own-key
  "v's own override for face: in the map, else in its metadata."
  {:malli/schema [:=> [:cat :any :keyword] :any]}
  [v k] (or (when (map? v) (get v k)) (get (meta v) k)))

(defn render-of
  "The render function v takes for face: its own override (a symbol), else its schema's
  declared one (opts :schema, else seon.render/schema-of: one metadata read or one map lookup
  per key, never a validation), else nil (the printer). nil also when v's own key holds an
  already rendered result."
  {:malli/schema [:=> [:cat :any :seon.render/face [:maybe :qualified-keyword]] [:maybe :seon.host/fn]]}
  [v face hint]
  (let [own (own-key v (faces face))]
    (when-let [sym (if (symbol? own) own (when (nil? own) (declared (schema-of v hint) face)))]
      @(requiring-resolve sym))))

(defn render-name
  "Which render shows v for face, by name, for every outcome render-of's selection has: v's own
  override, the face's key when v's own key holds a rendered result, its schema's declared render,
  else the one printer's (print-ai, print-html). A walk's provenance (parts). Not a step of
  render-of, which the printer asks of every nested value: a shared step measured three more
  contracted calls a value (2,254 against 1,947 at 100 attempts,
  seon.render-cost-test/the-printer-renders-nested-values-in-one-pass)."
  {:malli/schema [:=> [:cat :any :seon.render/face [:maybe :qualified-keyword]] [:or :symbol :keyword]]}
  [v face hint]
  (let [own (own-key v (faces face))]
    (cond (symbol? own) own
          (some? own) (faces face)
          :else (or (declared (schema-of v hint) face) (if (= :ai face) `print-ai `print-html)))))

(defn render
  "v rendered: (render-fn v opts), the function its override or schema names for opts' :face
  (:html, the default, or :ai), else the printer's (print-html, print-ai); a value's own key
  holding a result answers that result. :html answers hiccup, :ai {:text :truncated?}."
  {:malli/schema [:=> [:cat :any :seon.render/opts] :any]}
  [v opts]
  (let [face (:face opts :html)
        own (own-key v (faces face))]
    (cond
      (and (some? own) (not (symbol? own))) (if (= :ai face) {:text (if (string? own) own (print/text own)) :truncated? false} own)
      :else (let [f (render-of v face (:schema opts))
                  r (if f (f v opts) ((if (= :ai face) print-ai print-html) v opts))]
              (if (and (= :ai face) (string? r)) {:text r :truncated? false} r)))))

(defn html
  "v's :html render under opts (its hiccup)."
  {:malli/schema [:=> [:cat :any :seon.render/opts] :any]}
  [v opts] (render v (assoc opts :face :html)))

(defn ai
  "v's :ai render under opts, as the text the model or root reads."
  {:malli/schema [:=> [:cat :any :seon.render/opts] :string]}
  [v opts] (:text (render v (assoc opts :face :ai))))

(defn rendered?
  "Does v's producer name an AI render for it (the value's own key, or its metadata's
  :seon.render/schema)? A key present in a map names nothing: a partial pull or tx data is
  data. The REPL (`printed`) and the model's context (seon.context) ask this one question."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [v] (boolean (or (symbol? (own-key v :seon.render/ai)) (declared (:seon.render/schema (meta v)) :ai))))

(defn printed
  "v as a REPL answers it, cuts reading through handle (result/h3, or a form that reads v):
  its producer's AI render when it is `rendered?`, else the one value printer's result under
  the reply budget. A render's text ends with the form that shows the whole value as data,
  (with-meta result/h3 nil), and says whether it cut anything. Answers the printer's result,
  or a render's {:text :tokens :truncated?}."
  {:malli/schema [:=> [:cat :any :any] [:or :seon.print/result [:map [:text :string] [:tokens nat-int?] [:truncated? :boolean]]]]}
  [v handle]
  (if (rendered? v)
    (let [{t :text :keys [truncated?]} (render v {:face :ai :handle handle :budget print/default-budget})]
      {:text t :tokens (select/estimate t) :truncated? (boolean truncated?)})
    (print/print-value v {:handle handle})))

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

(defn- forward
  "Attribute a read forward: a reverse ref (:ns/_name, pull's syntax) as its own attribute (:ns/name)."
  {:malli/schema [:=> [:cat :keyword] :keyword]}
  [a] (if (str/starts-with? (name a) "_") (keyword (namespace a) (str/replace-first (name a) "_" "")) a))

(defn prose?
  "Is attribute a's value prose (its schema's :seon.render/prose, or the schema it names)? Prose
  is parsed for its citations; any other string is a name or data, and cites nothing."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [a]
  (let [form (get contract/forms a)]
    (boolean (or (:seon.render/prose (contract/properties a)) (and (qualified-keyword? form) (prose? form))))))

(defn- texts
  "Node nd's prose, as [attribute text] pairs: a :value node's own value when its schema is
  prose, an entity node's prose attributes in name order."
  {:malli/schema [:=> [:cat :seon.render/node] [:vector [:tuple :keyword :string]]]}
  [{:seon.render/keys [kind value schema]}]
  (if (= :value kind)
    (if (and (string? value) (prose? schema)) [[schema value]] [])
    (vec (for [[a v] (sort-by (comp str key) value) :when (and (string? v) (prose? a))] [a v]))))

(defn- ref-kind
  "What citation c reaches, as a ref says it: a var, a namespace, a task, a doc section
  (path#anchor), a file, or :unresolved when its view says so."
  {:malli/schema [:=> [:cat :seon.ref/citation :map] :seon.render/kind]}
  [{:seon.ref/keys [name anchor]} view]
  (cond (:unresolved view) :unresolved
        (simple-symbol? name) :namespace
        (qualified-symbol? name) (if (:symbol view) :var :task)
        anchor :section
        :else :file))

(defn- address
  "Citation c's address: its name, or its path and anchor."
  {:malli/schema [:=> [:cat :seon.ref/citation] :string]}
  [{:seon.ref/keys [name path anchor]}] (if name (str name) (str path (when anchor (str "#" anchor)))))

(defn- cite
  "State st ({:refs, numbered: :seon.render/n its index + 1; :index, each ref's index by its
  address}) with what node nd's prose cites added: a citation already there gains this place; a
  new one is pulled once (seon.docs/cited, at the depth it is first cited) and numbered next; one
  that reaches nothing pulled is left out. Answers [st' nd'], nd' naming the numbers it cites
  (:seon.render/cites)."
  {:malli/schema [:=> [:cat :seon.host/db [:map [:refs [:vector :seon.render/ref]] [:index [:map-of :string nat-int?]]] :seon.render/node]
                  [:tuple [:map [:refs [:vector :seon.render/ref]] [:index [:map-of :string nat-int?]]] :seon.render/node]]}
  [db st nd]
  (let [[st ns]
        (reduce (fn [[{:keys [refs index] :as st} ns] [a c]]
                  (let [at (cond-> {:seon.render/eid (:seon.render/eid nd) :seon.render/attr a} (:seon.ref/line c) (assoc :seon.render/line (:seon.ref/line c)))
                        k (address c)]
                    (if-let [i (get index k)]
                      [(update-in st [:refs i :seon.render/cited-at] conj at) (conj ns (inc i))]
                      (if-let [v (docs/cited db c)]
                        (let [n (inc (count refs))]
                          [{:refs (conj refs {:seon.render/n n :seon.render/kind (ref-kind c v) :seon.render/address k
                                              :seon.render/depth (:seon.ref/depth c) :seon.render/value v :seon.render/cited-at [at]})
                            :index (assoc index k (dec n))}
                           (conj ns n)])
                        [st ns]))))
                [st []]
                (for [[a text] (texts nd) c (ref/references db text)] [a c]))]
    [st (cond-> nd (seq ns) (assoc :seon.render/cites (vec (distinct ns))))]))

(defn walk
  "Entity eid on db as one map: :seon.render/root, the entity's node (its own attributes less its
  units); :seon.render/nodes, a node per unit its entity schema declares (:seon.render/units:
  pull's attributes, a leading _ the reverse ref), in that order, one hop: a neighbour entity with
  its own attributes (its refs {:db/id}; its components whole, as pull gives them) or the entity's
  own value, each saying where it came from; :seon.render/refs, what the nodes' prose cites, each
  pulled once and numbered, every place that cites it named. Given refs already seen (a context's),
  they keep their numbers and are not pulled again. Two pulls: its own attributes, then its ref
  units. db is a branch's, or seon.union's for what spans branches (ids are issued store-wide). An
  eid with no attributes on db answers :seon.render/no-entity."
  {:malli/schema [:function [:=> [:cat :seon.host/db pos-int?] [:or :seon.render/walk :seon.render/no-entity]]
                  [:=> [:cat :seon.host/db pos-int? [:vector :seon.render/ref]] [:or :seon.render/walk :seon.render/no-entity]]]}
  ([db eid] (walk db eid []))
  ([db eid seen]
   (if-let [own (d/pull db '[*] eid)]
     (let [schema (entity-schema own nil)
           units (:seon.render/units (contract/properties schema))
           ref? (fn [a] (or (not= a (forward a)) (= :db.type/ref (get-in (d/schema db) [a :db/valueType]))))
           refs (when (some ref? units) (d/pull db (into [] (comp (filter ref?) (map (fn [a] {a ['*]}))) units) eid))
           from (fn [a] {:seon.render/eid eid :seon.render/attr a})
           root {:seon.render/kind :entity :seon.render/value (apply dissoc own units) :seon.render/schema schema :seon.render/eid eid}
           nodes (into [] (mapcat (fn [a]
                                    (if (ref? a)
                                      (let [v (get refs a) via (forward a)]
                                        (map (fn [m] {:seon.render/kind :entity :seon.render/value m :seon.render/eid (:db/id m)
                                                      :seon.render/schema (entity-schema m via) :seon.render/from (from a)})
                                             (if (map? v) [v] v)))
                                      (when (contains? own a)
                                        [{:seon.render/kind :value :seon.render/value (get own a) :seon.render/schema a
                                          :seon.render/eid eid :seon.render/from (from a)}]))))
                       units)
           st {:refs seen :index (into {} (map-indexed (fn [i r] [(:seon.render/address r) i])) seen)}
           [st root] (cite db st root)
           [st nodes] (reduce (fn [[st out] nd] (let [[st nd] (cite db st nd)] [st (conj out nd)])) [st []] nodes)]
       {:seon.render/root root :seon.render/nodes nodes :seon.render/refs (:refs st)})
     (contract/error :seon.render/no-entity `walk (str "no entity " eid " on this db") {:seon.render/eid eid}))))

(defn- shown
  "Node or ref x rendered :as as on both faces by its schema's renders (render: none declared, the
  one printer, whose nested values show by their own), with which render made each face
  (render-name) and whether the :ai face was cut."
  {:malli/schema [:=> [:cat :map :seon.render/as :seon.render/opts] :map]}
  [x as opts]
  (let [v (:seon.render/value x) schema (:seon.render/schema x)
        o (assoc opts :as as :schema schema)
        {:keys [text truncated?]} (render v (assoc o :face :ai))]
    (assoc x :seon.render/as as
           :seon.render/ai-by (render-name v :ai schema) :seon.render/html-by (render-name v :html schema)
           :seon.render/ai-text text :seon.render/truncated? (boolean truncated?)
           :seon.render/hiccup (render v (assoc o :face :html)))))

(defn rendered
  "Walk w with every part rendered on both faces (shown): its root :as :block, each unit and ref
  :as :tile, each saying which render made it, beside what it is and where it came from."
  {:malli/schema [:=> [:cat :seon.render/walk :seon.render/opts] :seon.render/walk]}
  [w opts]
  (-> w
      (update :seon.render/root shown :block opts)
      (update :seon.render/nodes (fn [us] (mapv #(shown % :tile opts) us)))
      (update :seon.render/refs (fn [rs] (mapv #(shown % :tile opts) rs)))))
