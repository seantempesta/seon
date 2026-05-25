(ns seon.handlers.fn
  "Renderers for `:seon.fn` entities — fns the agent has defined via
   `(defn …)` or `(def …)` forms.

   Each entity renders WHAT THE FN IS (signature, doc, status), not
   the eval source that created it (the owning `:seon.eval` already
   shows that text verbatim).

   - AI pane: two compact lines — header + arglists + flags, plus the
     first line of the docstring when present.
   - HTML pane: amber `fn` badge, fully-qualified symbol, status pills
     (specced?/private?), monospace signature, docstring, and a
     collapsible <details> with the full source. The 'try it' affordance
     is a deferred placeholder link until the eval-against-compile-state
     route exists (see report — out of scope for the rewrite)."
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
  "Two-ish-line summary. Example:

     [fn seon.agent.XAR-.../add]  ([x y])  :specced? false :private? false
     ;; Adds two numbers."
  {:malli/schema [:=> [:cat :map] :seon.render/ai-response]}
  [{:seon.render/keys [entity]}]
  (let [sym       (or (:seon.fn/sym entity) "?")
        arglists  (:seon.fn/arglists entity)
        doc       (:seon.fn/doc entity)
        priv      (boolean (:seon.fn/private? entity))
        specced   (boolean (:seon.fn/specced? entity))
        sig       (arglists-str sym arglists)
        header    (str "[fn " sym "]"
                       (when sig (str "  " sig))
                       "  :specced? " specced
                       "  :private? " priv)
        doc-line  (when-let [d (short-doc doc)] (str ";; " d))
        lines     (cond-> [header]
                    doc-line (conj doc-line))]
    {:seon.render/text (str/join "\n" lines)}))

(defn- pill
  "Tiny status pill — green dot when on, gray dot when off."
  [label on?]
  [:span {:class (str "inline-flex items-center gap-1 text-xs font-mono "
                      (if on? "text-amber-300" "text-text-500"))}
   [:span {:class (if on? "text-amber-400" "text-text-600")} "●"]
   label])

(defn render-html
  "Interactive card. Header line + signature + doc + collapsible source.

   The 'try it' link is a placeholder (no backend route yet — see
   handler-eval report). When the route lands we swap the href."
  {:malli/schema [:=> [:cat :map] :seon.render/html-response]}
  [{:seon.render/keys [entity]}]
  (let [sym      (or (:seon.fn/sym entity) "?")
        arglists (:seon.fn/arglists entity)
        doc      (:seon.fn/doc entity)
        priv     (boolean (:seon.fn/private? entity))
        specced  (boolean (:seon.fn/specced? entity))
        src      (or (:seon.fn/source entity) "")
        sig      (arglists-str sym arglists)
        long?    (> (count src) source-inline-threshold)
        anchor   (str "seon-fn-" (str/replace (str sym) #"[^A-Za-z0-9_-]" "_"))]
    {:seon.render/hiccup
     [:div {:id anchor :class "py-1"}
      [:div {:class "flex items-baseline gap-2 flex-wrap"}
       [:span {:class "text-xs font-mono font-semibold text-amber-400"} "fn"]
       [:span {:class "text-xs font-mono text-text-100"} sym]
       (pill "specced" specced)
       (pill "private" priv)]
      (when sig
        [:div {:class "text-xs font-mono text-amber-200/80 mt-0.5"} sig])
      (when-let [d (short-doc doc)]
        [:div {:class "text-xs text-text-300 italic mt-0.5"} d])
      (when (not (str/blank? src))
        [:details {:class "mt-1" :open (not long?)}
         [:summary {:class "text-xs font-mono text-text-500 cursor-pointer"}
          (if long? "source ▾" "source")]
         [:pre {:class "text-xs whitespace-pre-wrap mt-0.5 rounded bg-base-900 p-1.5 overflow-x-auto"}
          [:code {:class "language-clojure hljs"} (str/trim src)]]])]}))
