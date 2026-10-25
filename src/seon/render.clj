(ns seon.render
  "Render selection and the one render call (W1's proto.render and proto.render.schema,
  ported onto next's registry: notes/archive/option3-W1-render.md §1).

  A render is a plain function (value opts) -> its result, like a React component: opts are
  its props, all optional (:as, the room it is shown in, :inline, :block or :tile; :budget,
  its tokens; :handle, the form its cuts read the rest through), and a function that does not
  care about one ignores it (owner: \"It's all just functions and results\"). An
  :html render answers hiccup (serialized by seon.web.html, where a vector headed by a
  function is a call, expanded as Reagent does); an :ai render answers text, or the printer's
  result {:text :truncated?}. Selection, first match wins, and nothing else (DECISIONS,
  Renders attach to schemas; the UI and the context are renders, ported not reinvented):
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
            [clojure.string :as str] [datahike.api :as d]))

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
  Each face's render is looked up by render-of (seon.render/schema-of: one metadata read or
  one map lookup per key, never a validation)."
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
    (select-keys (print/line-prose v (or (:budget opts) (config/value :seon.config.render/line-budget)) (:handle opts)) [:text :truncated?])
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
              ;; a record's part of the conversation (messages) reads as their contents
              (cond (and (= :ai face) (string? r)) {:text r :truncated? false}
                    (and (= :ai face) (vector? r)) {:text (str/join "\n\n" (map :content r)) :truncated? false :messages r}
                    :else r)))))

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
  node per unit its entity schema declares (:seon.render/units, pull's attributes, a leading _ the
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
          units (:seon.render/units (contract/properties schema))
          types (d/schema db)
          ref? (fn [a] (or (not= a (forward a)) (= :db.type/ref (get-in types [a :db/valueType]))))
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
      (contract/error :seon.render/no-entity `walk (str "no entity " eid " on these dbs") {:seon.render/eid eid})))))
