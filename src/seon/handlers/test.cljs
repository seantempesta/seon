(ns seon.handlers.test
  "Renderers for `:seon.test` entities — `deftest`s the agent has
   defined (detect-and-tee writes `:seon.test/sym`+`:ns`+`:source` on
   eval) and runner result rows (`run!` upserts `:seon.test/last-*` on
   the same `:seon.test/sym` identity).

   Each entity renders WHAT THE TEST IS (its symbol, pass/fail status
   when a run has been recorded, and its source). A test with no
   recorded run renders as `untested-by-run` — honest absence, not a
   fabricated status.

   - AI pane: compact line — glyph + symbol + status; source below.
     Lead glyph: ✓ last run passed, ✗ last run failed, • no run recorded.
   - HTML pane: amber `test` badge, fully-qualified symbol, pass/fail
     pill, collapsible source."
  (:require
    [clojure.string :as str]))

(def ^:private source-inline-threshold 200)

(defn test-status
  "Returns `{:ran? bool, :passing? (bool|nil), :failure-summary str|nil}`
   from the recorded `:seon.test/last-*` fields. `:ran?` false ⇒ no run
   has been recorded for this test (passing? nil = no signal).

   SINGLE SOURCE of the pass/fail/no-run determination — both this ns's
   `render-ai`/`render-html` AND `seon.agent/test-block-ai`
   (whole-namespace render) derive their glyph/pill from it, so the
   `last-passed-at`/`last-failed-at` → ✓/✗/• logic lives in ONE place."
  [{:seon.test/keys [last-passed-at last-failed-at last-failure-summary]}]
  (cond
    (and (nil? last-passed-at) (nil? last-failed-at))
    {:ran? false :passing? nil :failure-summary nil}

    (and last-passed-at
         (or (nil? last-failed-at)
             (> (.getTime last-passed-at) (.getTime last-failed-at))))
    {:ran? true :passing? true :failure-summary nil}

    :else
    {:ran? true :passing? false :failure-summary last-failure-summary}))

(defn status-glyph
  "The single-char status glyph for a `:seon.test` entity — the ONLY
   place the ✓/✗/• literals are defined. `✓` last run passed, `✗` last
   run failed, `•` no run recorded. Used by this ns's renderers and by
   `seon.agent`'s whole-namespace render so the two never diverge."
  [entity]
  (let [{:keys [ran? passing?]} (test-status entity)]
    (cond
      (and ran? passing?)          "✓"
      (and ran? (false? passing?)) "✗"
      :else                        "•")))

(defn status-line
  "A `;;`-prefixed one-line status for the AI pane, e.g.
   `;; ✓ test passing`, `;; ✗ test failing: <summary>`, or
   `;; • test (no run recorded)`. Built on `status-glyph` so the glyph
   literals stay single-sourced; shared by `render-ai` here and
   `seon.agent/test-block-ai`."
  [entity]
  (let [{:keys [ran? passing? failure-summary]} (test-status entity)
        glyph (status-glyph entity)]
    (cond
      (and ran? passing?)
      (str ";; " glyph " test passing")

      (and ran? (false? passing?))
      (str ";; " glyph " test failing"
           (when (and failure-summary
                      (not (str/blank? failure-summary)))
             (str ": " failure-summary)))

      :else
      (str ";; " glyph " test (no run recorded)"))))

(defn render-ai
  "Compact summary with a leading glyph for at-a-glance status.

     ✓ test passing               last recorded run passed
     ✗ test failing: <summary>    last recorded run failed
     • test (no run recorded)     persisted but never run

   The glyph is the FIRST char of line 2 so it lands in the LLM's
   peripheral vision; the agent can scan a long ctx for ✗/• quickly."
  {:malli/schema [:=> [:cat :map] :seon.render/ai-response]}
  [{:seon.render/keys [entity]}]
  (let [sym      (or (:seon.test/sym entity) "?")
        src      (:seon.test/source entity)
        header   (str "[test " sym "]")
        src-line (when (and src (not (str/blank? src)))
                   (str/trim src))
        lines    (cond-> [header (status-line entity)]
                   src-line (conj src-line))]
    {:seon.render/ai (str/join "\n" lines)}))

(defn- status-pill
  "Tri-color pill — :ok = amber dot, :warn = amber-dim dot, :err = red dot."
  [label kind]
  (let [{dot-class :dot text-class :text} (case kind
                                            :ok   {:dot "text-amber-400"    :text "text-amber-300"}
                                            :warn {:dot "text-amber-600/70" :text "text-amber-500/80"}
                                            :err  {:dot "text-error"        :text "text-error"})]
    [:span {:class (str "inline-flex items-center gap-1 text-xs font-mono " text-class)}
     [:span {:class dot-class} "●"]
     label]))

(defn render-html
  "Interactive card. Header line + pass/fail pill + collapsible source.

   The pill is `passing` (amber), `failing` (red), or `no run` (amber-dim)
   derived from the recorded `:seon.test/last-*` fields. When the last
   run failed and carries a summary, a red one-line warning is shown."
  {:malli/schema [:=> [:cat :map] :seon.render/html-response]}
  [{:seon.render/keys [entity]}]
  (let [sym    (or (:seon.test/sym entity) "?")
        src    (or (:seon.test/source entity) "")
        long?  (> (count src) source-inline-threshold)
        anchor (str "seon-test-" (str/replace (str sym) #"[^A-Za-z0-9_-]" "_"))
        {:keys [ran? passing? failure-summary]} (test-status entity)
        {:keys [label kind]} (cond
                               (and ran? passing?)       {:label "passing" :kind :ok}
                               (and ran? (false? passing?)) {:label "failing" :kind :err}
                               :else                     {:label "no run"  :kind :warn})]
    {:seon.render/hiccup
     [:div {:id anchor :class "py-1"}
      [:div {:class "flex items-baseline gap-2 flex-wrap"}
       [:span {:class "text-xs font-mono font-semibold text-amber-400"} "test"]
       [:span {:class "text-xs font-mono text-text-100"} sym]
       (status-pill label kind)]
      (when (and ran? (false? passing?) failure-summary
                 (not (str/blank? failure-summary)))
        [:div {:class "mt-1 text-xs font-mono text-error/80 rounded bg-base-900 px-1.5 py-1"}
         (str "✗ " failure-summary)])
      (when (not (str/blank? src))
        [:details {:class "mt-1" :open (not long?)}
         [:summary {:class "text-xs font-mono text-text-500 cursor-pointer"}
          (if long? "source ▾" "source")]
         [:pre {:class "text-xs whitespace-pre-wrap mt-0.5 rounded bg-base-900 p-1.5 overflow-x-auto"}
          [:code {:class "language-clojure hljs"} (str/trim src)]]])]}))
