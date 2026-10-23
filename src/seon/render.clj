(ns seon.render
  "Render selection and the one render call (W1's proto.render and proto.render.schema,
  ported onto next's registry: ../notes/archive/option3-W1-render.md §1).

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
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.print :as print] [seon.print.select :as select] [seon.config :as config]))

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
  :truncated?}), one line when shown :inline; the refusal's message when even its first part
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
