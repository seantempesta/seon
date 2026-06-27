(ns seon.handlers.eval
  "Renderers for `:seon.eval` entities — what the LLM sees of its own
   eval history, and what the human sees in the inspector's HTML pane.

   Stamped on every `:seon.eval` entity by `seon.eval/record-eval!` via:
     :seon.render/ai   'seon.handlers.eval/render-ai
     :seon.render/html 'seon.handlers.eval/render-html

   The transcript section's html twin (`seon.agent.ctx/transcript-block-html`)
   resolves these per-eval symbols (via `seon.render/render-entity-html` /
   `render-entity-ai`, which call each symbol through
   `seon.eval/lookup-value`) to render the agent's evals as right-pane
   cards. One entity, two render shapes — no separate 'what the LLM sees'
   vs 'what the human sees' store. (The agent's prompt TEXT comes from the
   one composer `seon.agent/assemble-context`.)

   ## Display order

   Each eval row is shown as:

     ;; <narration>             ; optional, only if non-blank
     [eval <id> <ms> :ok|:error]
     <source>                   ; LITERAL :seon.eval/source — never pr-str'd
     => <short result>          ; on :ok; var refs become #'ns/name
     :error <short cause>       ; on :error; short = first line, truncated

   Source comes from `:seon.eval/source` (the actual text the agent
   typed). The historical bug this guards against: rendering
   `(:seon.eval/result-edn entity)` first, which is `pr-str` of the
   eval RESULT — for a syntax-quoted form like `` `(foo) `` that
   shows the macroexpansion `(cljs.core/sequence ...)`, not the
   readable thing the agent wrote."
  (:require
    [clojure.string :as str]))

(def ^:private source-truncate 800)
(def ^:private result-summary-truncate 80)
(def ^:private error-summary-truncate 120)

(defn- truncate
  [s n]
  (let [s (str s)]
    (if (> (count s) n)
      (str (subs s 0 n) " …")
      s)))

(defn- short-result
  "One-line summary of a successful eval's `:seon.eval/result-edn`. The
   stored value is already a `pr-str` string. For vars / fn-vars
   datahike emits `#'ns/name`; that's already short. For everything
   else, take the first line and truncate. Nil-safe."
  [result-edn]
  (when result-edn
    (let [s (str result-edn)
          first-line (or (first (str/split-lines s)) "")]
      (truncate first-line result-summary-truncate))))

(defn- short-error
  "One-line summary of a failed eval's `:seon.eval/error` (a `pr-str`
   string of a `seon.error/->map`). Best-effort: find `:seon.error/message`
   if present, otherwise the first line of the whole thing. Truncated."
  [error-str]
  (when error-str
    (let [s (str error-str)
          ;; Cheap regex extraction so we don't read-string an arbitrary
          ;; tagged-literal-bearing payload.
          msg (when-let [m (re-find #":seon\.error/message\s+\"([^\"]*)\"" s)]
                (second m))
          line (or msg (first (str/split-lines s)) s)]
      (truncate line error-summary-truncate))))

(defn render-ai
  "One eval row for the LLM ctx. Source first (what the agent typed),
   result/error as a short tagged summary. See ns docstring for the
   display contract."
  {:malli/schema [:=> [:cat :map] [:maybe :string]]}
  [{:seon.render/keys [node entity]}]
  (let [entity    (or node entity)
        eid       (:seon.eval/id entity)
        narration (:seon.eval/narration entity)
        src       (or (:seon.eval/source entity) "")
        ok?       (boolean (:seon.eval/ok? entity))
        res-edn   (:seon.eval/result-edn entity)
        err-str   (:seon.eval/error entity)
        dur       (:seon.eval/duration-ms entity)
        header    (str "[eval " eid
                       (when dur (str " " dur "ms"))
                       " " (if ok? ":ok" ":error") "]")
        tail      (cond
                    ok?              (when-let [r (short-result res-edn)]
                                       (str "=> " r))
                    (string? err-str) (str ":error " (short-error err-str))
                    :else            ":error <no detail>")
        lines     (cond-> []
                    (and narration (not (str/blank? narration)))
                    (conj (str ";; " (str/trim narration)))
                    true (conj header)
                    true (conj (truncate (str/trim src) source-truncate))
                    tail (conj tail))]
    (str/join "\n" lines)))

(defn render-html
  "Hiccup card for the inspector's HTML pane.

   - Narration as muted text comment (if present).
   - Header line: eval id + duration + status pill.
   - Source as `<pre><code class=\"language-clojure\">…</code></pre>`
     so highlight.js (loaded from CDN in inspector-shell) colorizes it.
   - On :ok — a single `=> <short>` line in dim amber.
   - On :error — short summary inline + a collapsible `<details>` with
     the full pr-str'd error map for forensics."
  {:malli/schema [:=> [:cat :map] [:maybe :seon.render.live-tile/hiccup]]}
  [{:seon.render/keys [node entity]}]
  (let [entity    (or node entity)
        eid       (:seon.eval/id entity)
        narration (:seon.eval/narration entity)
        src       (or (:seon.eval/source entity) "")
        ok?       (boolean (:seon.eval/ok? entity))
        res-edn   (:seon.eval/result-edn entity)
        err-str   (:seon.eval/error entity)
        dur       (:seon.eval/duration-ms entity)
        status-class (if ok? "text-amber-400" "text-error")]
    [:div {:class "py-1"}
     ;; Narration is markdown emitted by the agent (`## heading`,
     ;; `**bold**`, lists). marked.js + the inspector-shell's
     ;; MutationObserver expand `[data-markdown]` to real HTML on
     ;; load and after every SSE morph. AI pane keeps it raw — LLMs
     ;; read markdown fine as text.
     (when (and narration (not (str/blank? narration)))
       [:div {:class "markdown mb-0.5"
              :data-markdown (str/trim narration)}])
     [:div {:class "flex items-baseline gap-2"}
      [:span {:class "text-xs font-mono font-semibold text-amber-500"}
       (str "eval " eid)]
      (when dur
        [:span {:class "text-xs text-text-500"} (str dur "ms")])
      [:span {:class (str "text-xs font-mono " status-class)}
       (if ok? ":ok" ":error")]]
     [:pre {:class "text-xs whitespace-pre-wrap mt-0.5 rounded bg-base-900 p-1.5 overflow-x-auto"}
      [:code {:class "language-clojure hljs"} (str/trim src)]]
     (cond
       ok?
       (when-let [r (short-result res-edn)]
         [:div {:class "text-xs font-mono text-amber-300/70 mt-1"}
          (str "=> " r)])

       (string? err-str)
       [:details {:class "mt-1"}
        [:summary {:class "text-xs font-mono text-error cursor-pointer"}
         (str ":error " (short-error err-str))]
        [:pre {:class "text-xs font-mono whitespace-pre-wrap text-error/80 mt-1 p-1.5 rounded bg-base-900"}
         err-str]]

       :else
       [:div {:class "text-xs font-mono text-error mt-1"}
        ":error <no detail>"])]))
