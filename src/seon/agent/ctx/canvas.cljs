(ns seon.agent.ctx.canvas
  "The `:canvas` context section — \"what your human currently sees\",
   rendered as a `;; ── canvas ──` comment-block. Symbol-wired into the
   composer layout (`config manifest`) as
   `'seon.agent.ctx.canvas/canvas-block`; loaded at boot so the symbol
   resolves for `seon.eval/lookup-value`.

   The agent sees the SAME wired value the human's surfaces render —
   derived every turn, nothing stored (reactive-context doctrine), so the
   agent can never believe its canvas is blank when the human sees content.
   Self-contained: no spine read API, just the canvas renderer +
   wired-content provenance."
  (:require
    [seon.ai.tokens :as tokens]
    [seon.agent.ctx :as ctx]
    [seon.config :as config]
    [seon.db :as db]
    [seon.error :as err]
    [seon.render :as render]
    [seon.render.canvas :as canvas]))

(defn- clip-marker
  "A loud, token-denominated cut marker for one canvas-context value."
  [what budget total]
  (str "\n…⟨" what " clipped at " budget " of " total
       " tokens — narrow the canvas render⟩"))

(defn- wired-fn-source
  "The program-graph source of the qualified fn `sym` driving the agent's
   canvas, or nil. Code-as-data: read the seeded `:seon.fn/source` row
   (keyed by the string `:seon.fn/sym` identity), NOT a file re-read — the
   same corpus the namespaces block renders from. Lets the agent see and
   edit the exact code behind its canvas, inline in this section."
  [db sym]
  (when (symbol? sym)
    (ffirst (db/query
              {:seon.db/db    db
               :seon.db/query '[:find ?src :in $ ?sym
                                :where
                                [?e :seon.fn/sym ?sym]
                                [?e :seon.fn/source ?src]]
               :seon.db/args  [(str sym)]}))))

(defn canvas-block
  "Show and explain the agent's current live canvas.

   The `:canvas` section — what your human currently sees and the compact
   operational contract for changing it. Agent-authored twins and source are
   independently bounded by the one render-fn token cap; a canvas cannot
   consume an unbounded share of every later turn.

   Invokes the agent's wired canvas value against this turn's db
   value through `seon.render/render-agent-canvas` (the one canvas entry
   point — same resolution, same render the human surfaces use) and
   renders:

     header — the wired identity (`seon.render.canvas/wired-label`:
              fn name, or \"literal hiccup on your entity\") so the
              agent always sees HOW to change the display;
     body   — the `:seon.render/ai` twin for fns; the literal hiccup
              VERBATIM for static values (\"you see exactly what's
              wired\" — a fn that omits the twin gets its hiccup
              verbatim too, which is itself the nudge to add one);
              the `:seon.error/*` envelope when the renderer THROWS
              (a broken canvas must never silently vanish — vanish is
              indistinguishable from unwired, banned).

   PER-TURN SEMANTICS (correct BY DESIGN — do not \"fix\" with stored
   presentation state or mid-turn refreshes): the body is as-of the
   db value this prompt was assembled from. The human's canvas
   live-updates per relevant tx, so between turns the human may
   briefly see FRESHER data than this twin; the next turn's section
   re-derives from the then-current db.

   Renders nothing only when no canvas resolves at all (agent entity
   missing) — every created agent is welcome-wired, so in practice
   the section is always present; the unwired branch is the
   correctness floor."
  {:malli/schema [:=> [:cat :seon.render/section-request] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (try
    (let [{:seon.render/keys [hiccup ai error]
           wired :seon.render.canvas/wired}
          ;; The one canvas entry point — SCI wall-clock-bounded for
          ;; agent-authored canvas fns, and on any throw it returns the
          ;; legible `error-response` (never throws past here). This is
          ;; the safety the canvas rides; the body below is always a
          ;; clean twin, an error twin, or the welcome card — never raw.
          (render/render-agent-canvas {:seon.agent/id id :seon.db/db db})
          cap  (config/render-fn-token-cap)
          body-kind (cond
                      (some? error)  :error
                      (some? ai)     :ai
                      (some? hiccup) :hiccup)
          body (cond
                 ;; Renderer THREW — your human is staring at an error
                 ;; canvas right now. Say so loudly and first (the agent must
                 ;; not skim past it), then the message + flattened ex-data
                 ;; (the agent-actionable parts; raw js error / 4KB stack
                 ;; dropped), then the fallback twin.
                 (some? error)
                 (str "Render failed; your human sees the fallback card.\n"
                      "Fix the renderer or pin a working canvas. Cause: "
                      (:seon.error/message error) "\n"
                      (pr-str (select-keys error [:seon.error/data
                                                  :seon.error/ex-data]))
                      (when (some? ai) (str "\n" ai)))

                 (some? ai)     ai
                 (some? hiccup) (pr-str hiccup))
          body (when (some? body)
                 (tokens/clip-str body cap (partial clip-marker "canvas twin")))]
      (if (nil? body)
        ""
        (let [;; The body is a render twin (:ai text, or hiccup pr-str, or
              ;; an error envelope) — arbitrary content the human's canvas
              ;; shows. It rides this comment-block as `;` lines (via
              ;; [[seon.agent.ctx/quote-lines]]) so the whole section reads as
              ;; eval'able Clojure (the context IS one live REPL); the agent
              ;; reads the value, it never evaluates.
              body-comment (ctx/quote-lines body)
              ;; FN-symbol canvas → show its SOURCE inline (code-as-data),
              ;; so the agent sees the exact code driving the canvas and can
              ;; edit it without a lookup. Literal-hiccup canvases have no
              ;; fn; the body already IS the value verbatim.
              wired-value  (:seon.render.canvas/value wired)
              ;; Agents can evolve only their own renderer. Embedding the
              ;; shared welcome/system source wastes context and invites the
              ;; exact core-edit mistake the block warns against.
              fn-src       (when (err/agent-authored-sym? wired-value)
                             (some-> (wired-fn-source db wired-value)
                                     (tokens/clip-str
                                       cap (partial clip-marker "canvas source"))))
              body-label   (case body-kind
                             :error "Render status:"
                             :ai "Rendered meaning (:seon.render/ai; paired HTML is on screen):"
                             :hiccup "Rendered Hiccup (exact human view):"
                             "Rendered output:")]
          (str "; CANVAS — current human-facing view\n"
               "; Renderer: " (canvas/wired-label wired) "\n"
               "; Snapshot: this prompt; the browser refreshes after relevant transactions.\n"
               "; " body-label "\n"
               body-comment "\n"
               (when (some? fn-src)
                 (str ";\n"
                      "; Agent-authored renderer source:\n"
                      (ctx/quote-lines fn-src) "\n"))
               ";\n"
               "; Change: (my.canvas/show! {:my.canvas/content <hiccup-or-qualified-fn>})\n"
               "; Live fn: :seon.render/system-input → my.canvas/view; query its :seon.db/db.\n"
               "; Actions: my.canvas controls call schema'd home-ns handlers; writes redraw.\n"
               "; Inspect/auto: (my.canvas/pinned {}) / (my.canvas/clear! {})."))))
    ;; CONTRACT: this section NEVER vanishes and NEVER surfaces a bare
    ;; ⚠/malli code. `render-agent-canvas` is already throw-safe, so this
    ;; backstop only fires on an UNEXPECTED failure (e.g. a db read) —
    ;; and even then the agent reads a clear, actionable safe-state, not
    ;; a swallowed error keyword. Self-heals on the next clean render.
    (catch :default e
      (str "; Your canvas — loading (safe-state placeholder this turn).\n"
           "; The per-turn canvas derivation hit an unexpected error and\n"
           "; degraded gracefully; your human sees the calm core welcome\n"
           "; card, never a broken panel. This is a transient render\n"
           "; hiccup that self-heals next turn.\n"
           ";\n"
           "; Diagnostic: " (ex-message e) "\n"
           ";\n"
           "; To (re)wire your canvas, transact a qualified fn symbol or\n"
           "; literal hiccup onto :seon.render.canvas/content on your\n"
           "; agent entity."))))
