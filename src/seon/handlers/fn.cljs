(ns seon.handlers.fn
  "Renderers for `:seon.fn` entities — fns the agent has defined via
   `(defn …)` or `(def …)` forms.

   Each entity renders WHAT THE FN IS (signature, doc, status), not
   the eval source that created it (the owning `:seon.eval` already
   shows that text verbatim).

   - AI pane: compact line — glyph + symbol + arglists + schema status.
     Lead glyph: ✗ schema error, ⚠ unspecced, ✓ specced. Docstring on
     the next line if present.
   - HTML pane: amber `fn` badge, fully-qualified symbol, status pills
     (specced/private — `specced` derived from the presence of
     `:seon.fn/spec`), schema-error badge when present,
     monospace signature, docstring, collapsible source."
  (:require
    [clojure.string :as str]))

(def ^:private source-inline-threshold 200)

(defn- arglists-str
  "Render `:seon.fn/arglists` (a string like `\"([x y])\"`) into the
   conventional `(name [x y])` form. The stored arglists shape varies:
   sometimes `\"([x y])\"`, sometimes `\"([x y] [x y z])\"`, sometimes
   nil. Be defensive."
  [sym arglists]
  (when (and arglists (not (str/blank? arglists)))
    (let [a (str/trim arglists)]
      (cond
        ;; Already wrapped in outer parens: split inner forms.
        (and (str/starts-with? a "(") (str/ends-with? a ")"))
        (let [inner (subs a 1 (dec (count a)))]
          (str "(" sym " " inner ")"))
        :else
        (str "(" sym " " a ")")))))

(defn- short-doc [doc]
  (when (and doc (not (str/blank? doc)))
    (first (str/split-lines doc))))

(defn render-ai
  "Compact summary with a leading glyph for at-a-glance status.

     ✓ specced                   for the green path
     ⚠ unspecced                  for fns missing `:malli/schema`
     ✗ schema error: <reason>     for fns whose schema failed to parse

   The glyph is the FIRST char of line 2 so it lands in the LLM's
   peripheral vision; the agent can scan a long ctx for ✗/⚠ quickly."
  {:malli/schema [:=> [:cat :seon.render/section-request] [:maybe :string]]}
  [{:seon.render/keys [node entity]}]
  (let [entity    (or node entity)
        sym       (or (:seon.fn/sym entity) "?")
        arglists  (:seon.fn/arglists entity)
        doc       (:seon.fn/doc entity)
        priv      (boolean (:seon.fn/private? entity))
        spec      (:seon.fn/spec entity)
        specced   (some? spec)
        schema-err (:seon.fn/schema-error entity)
        sig       (arglists-str sym arglists)
        status-line (cond
                      schema-err           (str ";; ✗ schema error: " schema-err)
                      specced              ";; ✓ specced"
                      (not specced)        ";; ⚠ unspecced"
                      :else                ";; ⚠ unknown status")
        header    (str "[fn " sym "]"
                       (when sig (str "  " sig))
                       (when priv "  :private? true"))
        doc-line  (when-let [d (short-doc doc)] (str ";; " d))
        spec-line (when specced (str ";; spec: " spec))
        lines     (cond-> [header status-line]
                    spec-line (conj spec-line)
                    doc-line (conj doc-line))]
    (str/join "\n" lines)))

(defn- pill
  "Tiny status pill — green dot when on, gray dot when off."
  [label on?]
  [:span {:class (str "inline-flex items-center gap-1 text-xs font-mono "
                      (if on? "text-amber-300" "text-text-500"))}
   [:span {:class (if on? "text-amber-400" "text-text-600")} "●"]
   label])

(defn- status-pill
  "Tri-color pill — :ok = amber dot, :warn = amber-dim dot, :err = red dot."
  [label kind]
  (let [{dot-class :dot text-class :text} (case kind
                                            :ok   {:dot "text-amber-400"   :text "text-amber-300"}
                                            :warn {:dot "text-amber-600/70" :text "text-amber-500/80"}
                                            :err  {:dot "text-error"       :text "text-error"})]
    [:span {:class (str "inline-flex items-center gap-1 text-xs font-mono " text-class)}
     [:span {:class dot-class} "●"]
     label]))

(defn render-html
  "Interactive card. Header line + signature + doc + collapsible source.

   Status pills: `specced` and `private`. Test outcomes render on their own
   `:seon.test` facts; this card does not invent fn↔test edges from source
   substrings. A schema error renders as a red one-line warning."
  {:malli/schema [:=> [:cat :seon.render/section-request] [:maybe :seon.render.canvas/hiccup]]}
  [{:seon.render/keys [node entity]}]
  (let [entity   (or node entity)
        sym      (or (:seon.fn/sym entity) "?")
        arglists (:seon.fn/arglists entity)
        doc      (:seon.fn/doc entity)
        priv     (boolean (:seon.fn/private? entity))
        spec     (:seon.fn/spec entity)
        specced  (some? spec)
        schema-err (:seon.fn/schema-error entity)
        src      (or (:seon.fn/source entity) "")
        sig      (arglists-str sym arglists)
        long?    (> (count src) source-inline-threshold)
        anchor   (str "seon-fn-" (str/replace (str sym) #"[^A-Za-z0-9_-]" "_"))]
    [:div {:id anchor :class "py-1"}
     [:div {:class "flex items-baseline gap-2 flex-wrap"}
      [:span {:class "text-xs font-mono font-semibold text-amber-400"} "fn"]
      [:span {:class "text-xs font-mono text-text-100"} sym]
      (status-pill "specced" (if specced :ok :warn))
      (when priv (pill "private" true))]
     (when schema-err
       [:div {:class "mt-1 text-xs font-mono text-error rounded bg-base-900 px-1.5 py-1"}
        (str "✗ schema error: " schema-err)])
     (when sig
       [:div {:class "text-xs font-mono text-amber-200/80 mt-0.5"} sig])
     (when-let [d (short-doc doc)]
       [:div {:class "text-xs text-text-300 italic mt-0.5"} d])
     (when (not (str/blank? src))
       [:details {:class "mt-1" :open (not long?)}
        [:summary {:class "text-xs font-mono text-text-500 cursor-pointer"}
         (if long? "source ▾" "source")]
        [:pre {:class "text-xs whitespace-pre-wrap mt-0.5 rounded bg-base-900 p-1.5 overflow-x-auto"}
         [:code {:class "language-clojure hljs"} (str/trim src)]]])]))
