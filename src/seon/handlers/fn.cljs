(ns seon.handlers.fn
  "Renderers for `:seon.fn` entities — fns the agent has defined via
   `(defn …)` or `(def …)` forms.

   Each entity renders WHAT THE FN IS (signature, doc, status), not
   the eval source that created it (the owning `:seon.eval` already
   shows that text verbatim).

   - AI pane: compact line — glyph + symbol + arglists + status summary.
     Lead glyph: ✗ schema error, ⚠ specced+no-tests / unspecced, ✓ all
     good. Docstring on the next line if present.
   - HTML pane: amber `fn` badge, fully-qualified symbol, status pills
     (specced/private/tested/test-passing — `specced` derived from the
     presence of `:seon.fn/spec`), schema-error badge when present,
     monospace signature, docstring, collapsible source."
  (:require
    [clojure.string :as str]
    [datahike.api :as d]))

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

(defn- referencing-tests
  "Pull `:seon.test` rows whose `:seon.test/source` mentions `fn-sym`
   (substring match, v0 heuristic mirroring
   `seon.test.runner/tests-referring-to`). Returns a vector of pulled
   test entity maps. Safe when db is nil (returns []).

   We index via `:aevt :seon.test/source` directly rather than full
   datalog so we can call `d/pull` per matched eid only — fewer
   passes through the registry. The substring scan happens in CLJS."
  [db fn-sym]
  (if (or (nil? db) (nil? fn-sym))
    []
    (let [needle (str fn-sym)]
      (try
        (->> (d/datoms db :aevt :seon.test/source)
             (filter (fn [^js dt] (str/includes? (.-v dt) needle)))
             (map (fn [^js dt] (.-e dt)))
             distinct
             (map #(d/pull db '[:seon.test/sym
                                :seon.test/last-passed-at
                                :seon.test/last-failed-at
                                :seon.test/last-failure-summary] %))
             vec)
        (catch :default _ [])))))

(defn- test-passing?-row
  "True iff this test row has passed and has not failed more recently."
  [{:seon.test/keys [last-passed-at last-failed-at]}]
  (boolean
    (and last-passed-at
         (or (nil? last-failed-at)
             (> (.getTime last-passed-at) (.getTime last-failed-at))))))

(defn- test-status
  "Returns `{:tested? bool, :test-passing? (bool|nil), :failure-summary str|nil}`.
   When `:tested?` is false, `:test-passing?` is nil (no signal)."
  [db fn-sym]
  (let [rows (referencing-tests db fn-sym)]
    (cond
      (empty? rows)
      {:tested? false :test-passing? nil :failure-summary nil}

      (every? test-passing?-row rows)
      {:tested? true :test-passing? true :failure-summary nil}

      :else
      (let [failing (->> rows (remove test-passing?-row))
            fs      (some :seon.test/last-failure-summary failing)]
        {:tested? true :test-passing? false :failure-summary fs}))))

(defn render-ai
  "Compact summary with a leading glyph for at-a-glance status.

     ✓ specced ✓ tests passing   for the green path
     ⚠ specced, no tests          for specced fns without a referring test
     ⚠ unspecced                  for fns missing `:malli/schema`
     ✗ schema error: <reason>     for fns whose schema failed to parse
     ✗ tests failing: <summary>   for tests that referenced this fn and fail

   The glyph is the FIRST char of line 2 so it lands in the LLM's
   peripheral vision; the agent can scan a long ctx for ✗/⚠ quickly."
  {:malli/schema [:=> [:cat :map] :seon.render/ai-response]}
  [{:seon.db/keys [db] :seon.render/keys [entity]}]
  (let [sym       (or (:seon.fn/sym entity) "?")
        arglists  (:seon.fn/arglists entity)
        doc       (:seon.fn/doc entity)
        priv      (boolean (:seon.fn/private? entity))
        spec      (:seon.fn/spec entity)
        specced   (some? spec)
        schema-err (:seon.fn/schema-error entity)
        sig       (arglists-str sym arglists)
        {:keys [tested? test-passing? failure-summary]} (test-status db sym)
        status-line (cond
                      schema-err           (str ";; ✗ schema error: " schema-err)
                      (and tested? (false? test-passing?))
                      (str ";; ✗ tests failing"
                           (when failure-summary (str ": " failure-summary)))
                      (and specced tested? test-passing?)
                      ";; ✓ specced  ✓ tests passing"
                      (and specced (not tested?))
                      ";; ⚠ specced, no tests"
                      (and (not specced) tested? test-passing?)
                      ";; ⚠ unspecced  ✓ tests passing"
                      (not specced)        ";; ⚠ unspecced, no tests"
                      :else                ";; ⚠ unknown status")
        header    (str "[fn " sym "]"
                       (when sig (str "  " sig))
                       (when priv "  :private? true"))
        doc-line  (when-let [d (short-doc doc)] (str ";; " d))
        spec-line (when specced (str ";; spec: " spec))
        lines     (cond-> [header status-line]
                    spec-line (conj spec-line)
                    doc-line (conj doc-line))]
    {:seon.render/text (str/join "\n" lines)}))

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

   Status pills: `specced`, `private`, `tested`, `test-passing`. The
   last two are joined from `:seon.test` rows whose source references
   this fn (substring heuristic; matches P4 auto-test-run discovery).
   When `:seon.fn/schema-error` is set, the schema-error badge replaces
   the `tested` row with a red one-line warning."
  {:malli/schema [:=> [:cat :map] :seon.render/html-response]}
  [{:seon.db/keys [db] :seon.render/keys [entity]}]
  (let [sym      (or (:seon.fn/sym entity) "?")
        arglists (:seon.fn/arglists entity)
        doc      (:seon.fn/doc entity)
        priv     (boolean (:seon.fn/private? entity))
        spec     (:seon.fn/spec entity)
        specced  (some? spec)
        schema-err (:seon.fn/schema-error entity)
        src      (or (:seon.fn/source entity) "")
        sig      (arglists-str sym arglists)
        long?    (> (count src) source-inline-threshold)
        anchor   (str "seon-fn-" (str/replace (str sym) #"[^A-Za-z0-9_-]" "_"))
        {:keys [tested? test-passing? failure-summary]} (test-status db sym)]
    {:seon.render/hiccup
     [:div {:id anchor :class "py-1"}
      [:div {:class "flex items-baseline gap-2 flex-wrap"}
       [:span {:class "text-xs font-mono font-semibold text-amber-400"} "fn"]
       [:span {:class "text-xs font-mono text-text-100"} sym]
       (status-pill "specced" (if specced :ok :warn))
       (when priv (pill "private" true))
       (status-pill "tested" (if tested? :ok :warn))
       (when tested?
         (status-pill "test-passing" (if test-passing? :ok :err)))]
      (when schema-err
        [:div {:class "mt-1 text-xs font-mono text-error rounded bg-base-900 px-1.5 py-1"}
         (str "✗ schema error: " schema-err)])
      (when (and tested? (false? test-passing?) failure-summary)
        [:div {:class "mt-1 text-xs font-mono text-error/80 rounded bg-base-900 px-1.5 py-1"}
         (str "✗ " failure-summary)])
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
