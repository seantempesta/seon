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
    [seon.agent.ctx :as ctx]
    [seon.agent.ctx.render-fns :as render-fns]
    [seon.db :as db]
    [seon.error :as err]
    [seon.render :as render]
    [seon.render.canvas :as canvas]))

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

(defn- block-content
  "The MEANINGFUL canvas content on agent `id`'s `:canvas` BLOCK entity
   (config-driven agent-init CP-3 move 11) — the block's decoded
   `:seon.render.canvas/content` when it is a real value (a fn symbol or
   literal hiccup), or nil when the block is absent or carries the `:none`
   default. Reactive config-on-record: root-context seeds root's block with
   `system-view`; a non-root agent's block defaults `:none`, so the caller
   falls back to the agent-entity datom (byte-parity). Values arrive
   pr-str-encoded from the mixed-:or bridge → decode on read."
  [db id]
  (let [blk (some (fn [b] (when (= :canvas (:seon.agent.ctx/name b)) b))
                  (:seon.agent/ctx
                    (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id id]})))
        c   (some->> (:seon.render.canvas/content blk)
                     (db/decode-edn-value :seon.render.canvas/content))]
    (when (and (some? c) (not= :none c)) c)))

(defn canvas-block
  "Show and explain the agent's current live canvas.

   The `:canvas` section — what your human currently sees and the complete,
   compact operational contract for changing it.

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
    (let [{:seon.render/keys [hiccup ai error]}
          ;; The one canvas entry point — SCI wall-clock-bounded for
          ;; agent-authored canvas fns, and on any throw it returns the
          ;; legible `error-response` (never throws past here). This is
          ;; the safety the canvas rides; the body below is always a
          ;; clean twin, an error twin, or the welcome card — never raw.
          (render/render-agent-canvas {:seon.agent/id id :seon.db/db db})
          body (cond
                 ;; Renderer THREW — your human is staring at an error
                 ;; canvas right now. Say so loudly and first (the agent must
                 ;; not skim past it), then the message + flattened ex-data
                 ;; (the agent-actionable parts; raw js error / 4KB stack
                 ;; dropped), then the fallback twin.
                 (some? error)
                 (str "⚠ YOUR CANVAS IS BROKEN — your human currently sees an\n"
                      "a fallback card, not your content. Fix the fn/hiccup driving\n"
                      ":seon.render.canvas/content (its source is below).\n"
                      "Why: " (:seon.error/message error) "\n"
                      (pr-str (select-keys error [:seon.error/data
                                                  :seon.error/ex-data]))
                      (when (some? ai) (str "\n" ai)))

                 (some? ai)     ai
                 (some? hiccup) (pr-str hiccup))]
      (if (nil? body)
        ""
        ;; Provenance for the header. Resolve the agent entity from the
        ;; db by `:seon.agent/id` (the composer injects :seon.db/db +
        ;; :seon.agent/id, not the entity itself). The canvas pin is read
        ;; behind the same `seon.db/installed-schema` gate
        ;; `canvas/user-name` uses: datahike THROWS on pulling an attr
        ;; the conn never installed (installs are lazy, at first
        ;; transact), so a fresh database predating any canvas transact must
        ;; resolve to `{}` → the core welcome (load-bearing, not
        ;; defensive fluff). `wired-content` needs a map; never a nil.
        (let [ent   (if (contains? (db/installed-schema db)
                                   :seon.render.canvas/content)
                      (let [agent-content
                            (or (db/pull {:seon.db/db db
                                          :seon.db/pull-pattern
                                          '[:seon.render.canvas/content]
                                          :seon.db/ref [:seon.agent/id id]})
                                {})
                            ;; CP-3 move 11: the canvas content is READ off the
                            ;; agent's `:canvas` BLOCK entity (root-context's
                            ;; mechanism — root's block carries `system-view`).
                            ;; A meaningful block content (not `:none`/absent)
                            ;; wins; otherwise FALL BACK to the agent-entity
                            ;; datom (today's behavior) so a non-root agent's
                            ;; welcome-wired canvas is byte-identical. The
                            ;; hardcoded root branch (client.cljs) still writes
                            ;; the agent entity too until CP-4 — both carry
                            ;; `system-view`, so reading either is identical.
                            blk-content (block-content db id)]
                        (if (some? blk-content)
                          {:seon.render.canvas/content blk-content}
                          agent-content))
                      {})
              ;; No pin → the derived last-updated surface, so the header's
              ;; provenance names the SAME value render-agent-canvas just
              ;; rendered (one resolution, two readers). Guarded like the
              ;; render side: derivation failure → welcome provenance.
              derived (when (nil? (:seon.render.canvas/content ent))
                        (try (::render-fns/surface-sym
                               (render-fns/last-updated-surface
                                 {:seon.db/db db :seon.agent/id id}))
                             (catch :default _ nil)))
              wired (canvas/wired-content
                      (cond-> {:seon.render/entity ent}
                        (some? derived)
                        (assoc :seon.render.canvas/derived derived)))
              ;; The body is a render twin (:ai text, or hiccup pr-str, or
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
                             (wired-fn-source db wired-value))]
          (str "; Your canvas — what your human currently sees (as-of this\n"
               "; turn's render; the human's view live-updates between turns).\n"
               "; Wired: " (canvas/wired-label wired) "\n"
               ";\n"
               body-comment "\n"
               ";\n"
               (when (some? fn-src)
                 (str "; Source driving your canvas (redefine it to change what\n"
                      "; your human sees):\n"
                      (ctx/quote-lines fn-src) "\n"
                      ";\n"))
               "; To deliberately UPDATE THE CANVAS, reuse my.canvas/show! and verify\n"
               "; its returned :seon.db/ok?. Defining a render fn alone only creates\n"
               "; an auto-run context surface; it does NOT deliberately wire canvas.\n"
               "; Canvas values and operations (the complete contract):\n"
               "; 1. STATIC — show a literal hiccup vector. Use semantic hiccup\n"
               ";    such as [:section [:h2 \"Title\"] [:p \"State\"]]. Raw HTML\n"
               ";    and <script> strings are escaped; arbitrary browser JS is not\n"
               ";    a canvas API.\n"
               "; 2. LIVE — define a schema'd fn in your home ns that accepts\n"
               ";    :seon.render/system-input and returns (my.canvas/view {...}).\n"
               ";    Query database state from its injected :seon.db/db value, then\n"
               ";    EXPLICITLY wire its qualified fn symbol below. The feed redraws after\n"
               ";    every transaction.\n"
               "; 3. INTERACTIVE — compose my.canvas/button, input, select, toggle,\n"
               ";    or form into that live fn. A handler is YOUR schema'd home-ns\n"
               ";    fn symbol, never a URL/string. Button handlers receive one\n"
               ";    map directly: the VALUE of :my.canvas/data, never a wrapper\n"
               ";    keyed by :my.canvas/data. Do not re-register my.canvas schemas.\n"
               ";    Form fields are qualified\n"
               ";    keywords and arrive as one map with those exact keys.\n"
               ";    The /agent/<id>/call capability gate invokes it,\n"
               ";    its DB transaction changes state, and the normal feed redraws.\n"
               ";    Controls return reusable hiccup directly; my.canvas/view supplies\n"
               ";    required :my.canvas/content and an optional :my.canvas/ai twin.\n"
               ";    Handler fns need concrete Malli contracts.\n"
               ";    Always inspect :seon.db/ok? before claiming an action worked.\n"
               ";    For common agent-local state, my.canvas/state reads selected\n"
               ";    qualified attrs and my.canvas/save! writes qualified values; both\n"
               ";    inject your agent id (state also injects the render db).\n"
               "; 4. COMPOSE — reuse my.canvas directly, or define higher-level helpers\n"
               ";    in YOUR namespace from the same constructs. my.data aggregates DB\n"
               ";    rows. Use only fully namespaced\n"
               ";    data keys and registered schemas. Pull `ui-canvas` only when\n"
               ";    you need the detailed cookbook and CSS vocabulary.\n"
               ";\n"
               "; Pin either literal hiccup or a live qualified fn symbol:\n"
               ";   (my.canvas/show! {:my.canvas/content\n"
               ";                  <hiccup-vector OR 'my.agent." id "/your-fn>})\n"
               "; Read (my.canvas/pinned {}) to inspect the explicit pin; call\n"
               "; (my.canvas/clear! {}) to resume automatic derived selection.\n"
               "; Evolve YOUR fn; never edit the shared core welcome canvas."))))
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
