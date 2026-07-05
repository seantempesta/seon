(ns seon.agent.ctx.live-tile
  "The `:live-tile` context section — \"what your human currently sees\",
   rendered as a `;; ── live tile ──` comment-block. Symbol-wired into the
   composer layout (`seon.config/default-ctx-blocks`) as
   `'seon.agent.ctx.live-tile/live-tile-block`; loaded at boot so the symbol
   resolves for `seon.eval/lookup-value`.

   The agent sees the SAME wired value the human's surfaces render —
   derived every turn, nothing stored (reactive-context doctrine), so the
   agent can never believe its tile is blank when the human sees content.
   Self-contained: no spine read API, just the tile renderer +
   wired-content provenance."
  (:require
    [seon.agent.ctx :as ctx]
    [seon.agent.ctx.render-fns :as render-fns]
    [seon.db :as db]
    [seon.render :as render]
    [seon.render.live-tile :as live-tile]))

(defn- wired-fn-source
  "The program-graph source of the qualified fn `sym` driving the agent's
   canvas, or nil. Code-as-data: read the seeded `:seon.fn/source` row
   (keyed by the string `:seon.fn/sym` identity), NOT a file re-read — the
   same corpus the namespaces block renders from. Lets the agent see and
   edit the EXACT code behind its tile, inline in this section."
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
  "The MEANINGFUL canvas content on agent `id`'s `:live-tile` BLOCK entity
   (config-driven agent-init CP-3 move 11) — the block's decoded
   `:seon.render.live-tile/content` when it is a real value (a fn symbol or
   literal hiccup), or nil when the block is absent or carries the `:none`
   default. Reactive config-on-record: root-context seeds root's block with
   `system-view`; a non-root agent's block defaults `:none`, so the caller
   falls back to the agent-entity datom (byte-parity). Values arrive
   pr-str-encoded from the mixed-:or bridge → decode on read."
  [db id]
  (let [blk (some (fn [b] (when (= :live-tile (:seon.agent.ctx/name b)) b))
                  (:seon.agent/ctx
                    (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id id]})))
        c   (some->> (:seon.render.live-tile/content blk)
                     (db/decode-edn-value :seon.render.live-tile/content))]
    (when (and (some? c) (not= :none c)) c)))

(defn live-tile-block
  "The `:live-tile` section — what your human currently sees.

   Invokes the agent's wired tile value against THIS TURN's db
   value through `seon.render/render-agent-tile` (the ONE tile entry
   point — same resolution, same render the human surfaces use) and
   renders:

     header — the wired identity (`seon.render.live-tile/wired-label`:
              fn name, or \"literal hiccup on your entity\") so the
              agent always sees HOW to change the display;
     body   — the `:seon.render/ai` twin for fns; the literal hiccup
              VERBATIM for static values (\"you see exactly what's
              wired\" — a fn that omits the twin gets its hiccup
              verbatim too, which is itself the nudge to add one);
              the `:seon.error/*` envelope when the renderer THROWS
              (a broken tile must never silently vanish — vanish is
              indistinguishable from unwired, banned).

   PER-TURN SEMANTICS (correct BY DESIGN — do not \"fix\" with stored
   presentation state or mid-turn refreshes): the body is as-of the
   db value this prompt was assembled from. The human's tile
   live-updates per relevant tx, so between turns the human may
   briefly see FRESHER data than this twin; the next turn's section
   re-derives from the then-current db.

   Renders nothing only when no tile resolves at all (agent entity
   missing) — every created agent is welcome-wired, so in practice
   the section is always present; the unwired branch is the
   correctness floor."
  {:malli/schema [:=> [:cat :seon.render/section-request] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (try
    (let [{:seon.render/keys [hiccup ai error]}
          ;; The ONE tile entry point — SCI wall-clock-bounded for
          ;; agent-authored tile fns, and on ANY throw it returns the
          ;; legible `error-response` (never throws past here). This is
          ;; the safety the live tile rides; the body below is always a
          ;; clean twin, an error twin, or the welcome card — never raw.
          (render/render-agent-tile {:seon.agent/id id :seon.db/db db})
          body (cond
                 ;; Renderer THREW — your human is staring at an error
                 ;; tile RIGHT NOW. Say so LOUDLY and first (the agent must
                 ;; not skim past it), then the message + flattened ex-data
                 ;; (the agent-actionable parts; raw js error / 4KB stack
                 ;; dropped), then the fallback twin.
                 (some? error)
                 (str "⚠ YOUR CANVAS IS BROKEN — your human currently sees an\n"
                      "error tile, not your content. Fix the fn/hiccup driving\n"
                      ":seon.render.live-tile/content (its source is below).\n"
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
        ;; :seon.agent/id, NOT the entity itself). The tile slot is read
        ;; behind the same `seon.db/installed-schema` gate
        ;; `live-tile/user-name` uses: datahike THROWS on pulling an attr
        ;; the conn never installed (installs are lazy, at first
        ;; transact), so a fresh store predating any tile transact must
        ;; resolve to `{}` → the core welcome (load-bearing, not
        ;; defensive fluff). `wired-content` needs a map; never a nil.
        (let [ent   (if (contains? (db/installed-schema db)
                                   :seon.render.live-tile/content)
                      (let [agent-content
                            (or (db/pull {:seon.db/db db
                                          :seon.db/pull-pattern
                                          '[:seon.render.live-tile/content]
                                          :seon.db/ref [:seon.agent/id id]})
                                {})
                            ;; CP-3 move 11: the canvas content is READ off the
                            ;; agent's `:live-tile` BLOCK entity (root-context's
                            ;; mechanism — root's block carries `system-view`).
                            ;; A meaningful block content (not `:none`/absent)
                            ;; wins; otherwise FALL BACK to the agent-entity
                            ;; datom (today's behavior) so a non-root agent's
                            ;; welcome-wired tile is byte-identical. The
                            ;; hardcoded root branch (client.cljs) still writes
                            ;; the agent entity too until CP-4 — both carry
                            ;; `system-view`, so reading either is identical.
                            blk-content (block-content db id)]
                        (if (some? blk-content)
                          {:seon.render.live-tile/content blk-content}
                          agent-content))
                      {})
              ;; No pin → the derived last-updated tile, so the header's
              ;; provenance names the SAME value render-agent-tile just
              ;; rendered (one resolution, two readers). Guarded like the
              ;; render side: derivation failure → welcome provenance.
              derived (when (nil? (:seon.render.live-tile/content ent))
                        (try (::render-fns/tile-sym
                               (render-fns/last-updated-tile
                                 {:seon.db/db db :seon.agent/id id}))
                             (catch :default _ nil)))
              wired (live-tile/wired-content
                      (cond-> {:seon.render/entity ent}
                        (some? derived)
                        (assoc :seon.render.live-tile/derived derived)))
              ;; The body is a render twin (:ai text, or hiccup pr-str, or
              ;; an error envelope) — arbitrary content the human's tile
              ;; shows. It rides this comment-block as `;` lines (via
              ;; [[seon.agent.ctx/quote-lines]]) so the whole section reads as
              ;; eval'able Clojure (the context IS one live REPL); the agent
              ;; reads the value, it never evaluates.
              body-comment (ctx/quote-lines body)
              ;; FN-symbol canvas → show its SOURCE inline (code-as-data),
              ;; so the agent sees the exact code driving the tile and can
              ;; edit it without a lookup. Literal-hiccup canvases have no
              ;; fn; the body already IS the value verbatim.
              fn-src       (wired-fn-source db (:seon.render.live-tile/value
                                                wired))]
          (str "; Your live tile — what your human currently sees (as-of this\n"
               "; turn's render; the human's view live-updates between turns).\n"
               "; Wired: " (live-tile/wired-label wired) "\n"
               ";\n"
               body-comment "\n"
               ";\n"
               (when (some? fn-src)
                 (str "; Source driving your canvas (redefine it to change what\n"
                      "; your human sees):\n"
                      (ctx/quote-lines fn-src) "\n"
                      ";\n"))
               "; ── THIS canvas is your PRIMARY surface ── your human WATCHES\n"
               "; this tile; messages are backup narration that scrolls away.\n"
               "; Anything worth seeing at a glance — a status, a plan, goals, a\n"
               "; checklist, a recommendation, a data breakdown, a result table,\n"
               "; progress — belongs HERE as a board/view, not recited in a\n"
               "; paragraph: a PLANNING / GOAL / STATUS ask answered only in prose\n"
               "; (or only as steps) leaves this canvas blank — render the board\n"
               "; FIRST, then narrate. UNPINNED, this canvas is DERIVED: it shows\n"
               "; your LAST-UPDATED tile fn — redefine a tile fn, or write data\n"
               "; whose attrs its source names, and your human's focus follows\n"
               "; automatically. PIN it with ONE transact of either literal\n"
               "; hiccup (instant) or a qualified tile-FN symbol (re-derives\n"
               "; every render, so a live count/query stays current); retract\n"
               "; :seon.render.live-tile/content to fall back to derived:\n"
               ";\n"
               ";   (seon.db/transact!\n"
               ";     {:seon.db/tx-data\n"
               ";      [{:seon.agent/id (seon.db/current-agent-id)\n"
               ";        :seon.render.live-tile/content\n"
               ";        <hiccup-vector  OR  'my.agent." id "/your-tile-fn>}]})\n"
               ";\n"
               "; COMPOSE it from the toolkit instead of hand-rolling [:div …]:\n"
               ";   my.ui   — dual-render status-line / kv-table / section (static)\n"
               ";   my.tile — INTERACTIVE button / input / select / toggle / form\n"
               ";             (controls that call YOUR fns back — buttons WORK)\n"
               ";   my.data — sum-by / max-by / group-sum over stored rows\n"
               "; A tile fn returns {:seon.render/hiccup … :seon.render/ai …}: the\n"
               "; human sees the hiccup, YOU see the :ai twin (above). The\n"
               "; `ui-live-tiles` skill is the full cookbook + the CSS safelist\n"
               "; (only safelisted classes exist at runtime; classless semantic\n"
               "; hiccup — [:table] [:ul] [:h2] [:p] [:pre [:code …]] — is styled\n"
               "; for free). Evolve YOUR fn; never edit the shared core welcome tile."))))
    ;; CONTRACT: this section NEVER vanishes and NEVER surfaces a bare
    ;; ⚠/malli code. `render-agent-tile` is already throw-safe, so this
    ;; backstop only fires on an UNEXPECTED failure (e.g. a db read) —
    ;; and even then the agent reads a clear, actionable safe-state, not
    ;; a swallowed error keyword. Self-heals on the next clean render.
    (catch :default e
      (str "; Your live tile — loading (safe-state placeholder this turn).\n"
           "; The per-turn tile derivation hit an unexpected error and\n"
           "; degraded gracefully; your human sees the calm core welcome\n"
           "; card, never a broken panel. This is a transient render\n"
           "; hiccup that self-heals next turn.\n"
           ";\n"
           "; Diagnostic: " (ex-message e) "\n"
           ";\n"
           "; To (re)wire your tile, transact a qualified fn symbol or\n"
           "; literal hiccup onto :seon.render.live-tile/content on your\n"
           "; agent entity."))))
