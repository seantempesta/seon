(ns seon.handlers.eval
  "Renderers for `:seon.eval` entities — what the LLM sees of its own
   eval history, and what the human sees in the web UI's HTML pane.

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
    [clojure.string :as str]
    [seon.ai.tokens :as tokens]
    [seon.render :as render]))

;; Display budgets, in TOKENS (clipped via seon.ai.tokens/clip-str).
(def ^:private source-truncate 200)
(def ^:private result-summary-truncate 20)
(def ^:private error-summary-truncate 30)
(def ^:private activity-label-truncate 40)

(defn- short-result
  "One-line summary of a successful eval's `:seon.eval/result-edn`. The
   stored value is already a `pr-str` string. For vars / fn-vars
   datahike emits `#'ns/name`; that's already short. For everything
   else, take the first line and truncate. Nil-safe."
  [result-edn]
  (when result-edn
    (let [s (str result-edn)
          first-line (or (first (str/split-lines s)) "")]
      (tokens/clip-str first-line result-summary-truncate))))

(defn- short-error
  "One-line summary of a failed eval's `:seon.eval/error` (a `pr-str`
   string of a `seon.error/->map`). Best-effort: find `:seon.error/message`
   if present, otherwise the first line of the whole thing. Truncated."
  [error-str]
  (when error-str
    (let [s (str error-str)
          ;; Cheap regex extraction so we don't read-string an arbitrary
          ;; tagged-literal-bearing payload.
          msg (when-let [m (re-find #":seon\.error/message\s+\"((?:[^\"\\]|\\.)*)\"" s)]
                (second m))
          line (or msg (first (str/split-lines s)) s)]
      (tokens/clip-str line error-summary-truncate))))

(defn render-ai
  "One eval row for the LLM ctx.

   Source first (what the agent typed),
   result/error as a short tagged summary. See ns docstring for the
   display contract."
  {:malli/schema [:=> [:cat :seon.render/section-request] [:maybe :string]]}
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
                    true (conj (tokens/clip-str (str/trim src) source-truncate))
                    tail (conj tail))]
    (str/join "\n" lines)))

(defn- full-error
  "The FULL error message (untruncated, unlike `short-error`) of a stored
   `:seon.eval/error` pr-str string — the text the agent READS to
   self-correct. Pulls `:seon.error/message` by the same cheap regex — no
   `read-string` of an arbitrary tagged payload — and falls back to the
   whole string when the shape isn't recognized. Nil-safe."
  [error-str]
  (let [s (str error-str)
        msg (when-let [m (re-find #":seon\.error/message\s+\"((?:[^\"\\]|\\.)*)\"" s)]
              (second m))]
    (or msg s)))

(defn- compact-operation
  "A short operation label derived from eval source, never its arguments."
  [source]
  (when-let [[_ op] (re-find #"^\s*\(\s*([^\s\[\](){}]+)" (or source ""))]
    (let [[owner fname] (str/split op #"/" 2)
          owner-tail (some-> owner (str/split #"\.") last)]
      (if fname (str owner-tail "/" fname) op))))

(defn- activity-label
  "A terse human activity label for one eval entity.

   Narration wins because it records the agent's stated intent. Without it,
   identify only the operation name; arguments and source never enter the
   normal transcript row."
  [entity]
  (let [narration (:seon.eval/narration entity)
        source    (:seon.eval/source entity)
        label     (if (and narration (not (str/blank? narration)))
                    (-> narration str/trim str/split-lines first)
                    (if-let [op (compact-operation source)]
                      (str "ran " op)
                      "worked"))]
    (tokens/clip-str label activity-label-truncate)))

(defn render-activity-html
  "The fixed-size eval activity row used by the normal agent transcript.

   This projection deliberately contains no source, result, or error body and
   no closed disclosure subtree. Historical technical payloads therefore do
   not inflate every live agent-view morph. The exact AI transcript remains in
   the debug web UI; [[render-html]] remains the explicit technical entity
   renderer for surfaces that deliberately request eval detail."
  {:malli/schema [:=> [:cat :seon.render/section-request] [:maybe :seon.render.canvas/hiccup]]}
  [{:seon.render/keys [node entity]}]
  (let [entity (or node entity)
        eid    (:seon.eval/id entity)
        ok?    (boolean (:seon.eval/ok? entity))
        dur    (:seon.eval/duration-ms entity)]
    [:div {:class "agent-activity flex items-baseline gap-1.5 px-2 py-1 text-xs min-w-0"
           :title (when eid (str "eval " eid))}
     [:span {:class "font-medium text-text-400 truncate"}
      (activity-label entity)]
     (when dur
       [:span {:class "font-mono text-text-600 shrink-0"} (str dur "ms")])
     [:span {:class (str "font-mono shrink-0 "
                         (if ok? "text-success" "text-error"))}
      (if ok? "done" "failed")]]))

(defn render-html
  "Technical eval detail card for deliberate inspection surfaces.

   Every part routes through the
   typed `seon.render/block` renderer so each kind gets first-class TLC.

   Technical material is inside a closed `<details>` disclosure:

   - Summary: narration when present, otherwise `agent activity`, plus duration
     and success/error state.
   - Expanded source → a highlighted Clojure card (`{:seon.render/source …}`,
     server-side `seon.ui.clojure/clj->hiccup` — no client highlight.js).
   - On :ok — a collapsible `<details>`: a one-line `=> <short>` summary;
     expanded shows the full result skeleton as a highlighted Clojure
     block (the stored `:seon.eval/result-edn` is the bounded data
     projection; the live value lives at `result/<id>`).
   - On :error — a FAILED EVAL (the agent's code didn't work — normal,
     agents learn from it), NOT a render fault. Rendered as calm eval-card
     content in the normal chrome: a collapsible `<details>` whose summary
     is a one-line `✗ <short>`, expanded to an error-tinted `✗ eval failed`
     block with the FULL `:seon.error/message`. It deliberately does NOT
     route through the `error-card` seam — that seam is the never-throw
     backstop for actual RENDER throws and its header reads 'render error',
     which would mislabel (and alarm about) an ordinary eval error."
  {:malli/schema [:=> [:cat :seon.render/section-request] [:maybe :seon.render.canvas/hiccup]]}
  [{:seon.render/keys [node entity]}]
  (let [entity    (or node entity)
        eid       (:seon.eval/id entity)
        narration (:seon.eval/narration entity)
        src       (or (:seon.eval/source entity) "")
        ok?       (boolean (:seon.eval/ok? entity))
        res-edn   (:seon.eval/result-edn entity)
        err-str   (:seon.eval/error entity)
        dur       (:seon.eval/duration-ms entity)
        status-class (if ok? "text-success" "text-error")
        activity-label (activity-label entity)]
    [:div {:class "py-0.5 min-w-0"}
     [:details {:class "agent-activity rounded border border-base-800 bg-base-950/40"}
      [:summary {:class (str "cursor-pointer px-2 py-1 text-xs min-w-0 "
                            "text-text-400 hover:text-text-200")}
       [:span {:class "font-medium"} activity-label]
       (when dur
         [:span {:class "font-mono text-text-600"} (str " · " dur "ms")])
       [:span {:class (str "font-mono " status-class)}
        (if ok? " · done" " · failed")]]
      [:div {:class "px-2 pb-2 pt-1 border-t border-base-800 min-w-0 overflow-hidden"}
       [:div {:class "text-2xs font-mono text-text-600 mb-1"} (str "eval " eid)]
       (when (and narration (not (str/blank? narration)))
         [:div {:class "markdown mb-1 text-xs"}
          (render/block :html {:seon.render/markdown (str/trim narration)})])
       [:div {:class "text-2xs font-mono text-text-500 mb-0.5"} "code"]
       (render/block :html {:seon.render/source (str/trim src)})
       (cond
         ok?
         (when-let [r (short-result res-edn)]
           [:details {:class "mt-1"}
            [:summary {:class "text-xs font-mono text-amber-300/70 cursor-pointer"}
             (str "result · " r)]
            [:div {:class "mt-1 min-w-0 overflow-hidden"}
             (render/block :html {:seon.render/source (str res-edn)})]])

         (string? err-str)
         [:details {:class "mt-1"}
          [:summary {:class "text-xs font-mono text-error cursor-pointer"}
           (str "error · " (short-error err-str))]
          [:div {:class (str "mt-1 p-2 rounded border border-error/30 "
                             "bg-error/5 min-w-0 overflow-hidden")}
           [:pre {:class (str "text-xs font-mono text-text-300 "
                              "whitespace-pre-wrap break-words")}
            (full-error err-str)]]]

         :else
         [:div {:class "text-xs font-mono text-error mt-1"}
          "error details unavailable"])]]]))
