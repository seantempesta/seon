(ns seon.agent.ctx.live-tile
  "The `:live-tile` context section — \"what your human currently sees\",
   rendered as a `;; ── live tile ──` comment-block. Symbol-wired into the
   composer layout (`seon.agent.ctx/default-seed-blocks`) as
   `'seon.agent.ctx.live-tile/live-tile-block`; loaded at boot so the symbol
   resolves for `seon.eval/lookup-value`.

   The agent sees the SAME wired value the human's surfaces render —
   derived every turn, nothing stored (reactive-context doctrine), so the
   agent can never believe its tile is blank when the human sees content.
   Self-contained: no spine read API, just the tile renderer +
   wired-content provenance."
  (:require
    [seon.agent.ctx :as ctx]
    [seon.db :as db]
    [seon.render :as render]
    [seon.render.live-tile :as live-tile]))

(defn live-tile-block
  "The `:live-tile` awareness section — what your human currently
   sees. Invokes the agent's wired tile value against THIS TURN's db
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
  {:malli/schema [:=> [:cat :map] :string]}
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
                 ;; Renderer threw: the twin says it's broken; the
                 ;; envelope (sans the raw js error / 4KB stack — the
                 ;; message + flattened ex-data are the agent-actionable
                 ;; parts) says what the exception said.
                 (some? error)
                 (str ai "\n"
                      (pr-str (select-keys error [:seon.error/message
                                                  :seon.error/data
                                                  :seon.error/ex-data])))

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
                      (or (db/pull {:seon.db/db db
                                    :seon.db/pull-pattern
                                    '[:seon.render.live-tile/content]
                                    :seon.db/ref [:seon.agent/id id]})
                          {})
                      {})
              wired (live-tile/wired-content {:seon.render/entity ent})
              ;; The body is a render twin (:ai text, or hiccup pr-str, or
              ;; an error envelope) — arbitrary content the human's tile
              ;; shows. It rides this comment-block as `;` lines (via
              ;; [[seon.agent.ctx/quote-lines]]) so the whole section reads as
              ;; eval'able Clojure (the context IS one live REPL); the agent
              ;; reads the value, it never evaluates.
              body-comment (ctx/quote-lines body)]
          (str "; Your live tile — what your human currently sees (as-of this\n"
               "; turn's render; the human's view live-updates between turns).\n"
               "; Wired: " (live-tile/wired-label wired) "\n"
               ";\n"
               body-comment "\n"
               ";\n"
               "; PRESENT RICHLY, OR JUST REPLY IN MARKDOWN. You never have to\n"
               "; build a tile: while you're on the core default, your LATEST\n"
               "; REPLY to your human renders as a real markdown card on the\n"
               "; canvas — so a clean `## heading` / `- list` / `**bold**` answer\n"
               "; already looks good with zero extra work. Build a tile only when\n"
               "; you want to show something richer than your words.\n"
               ";\n"
               "; Two ways to set a tile (copy-paste, swap in your content):\n"
               ";\n"
               ";   ; (a) literal hiccup — instant, no fn needed:\n"
               ";   (seon.db/transact!\n"
               ";     {:seon.db/tx-data\n"
               ";      [{:seon.agent/id " (pr-str id) "\n"
               ";        :seon.render.live-tile/content\n"
               ";        [:div {:class \"p-3 flex flex-col gap-1\"}\n"
               ";         [:h2 {:class \"text-sm font-bold text-signal\"} \"Status\"]\n"
               ";         [:p {:class \"text-xs text-text-200\"} \"All systems go.\"]]}]})\n"
               ";\n"
               ";   ; (b) a tile FN in your home ns — re-derives every render\n"
               ";   ; (a `(defn status-tile …)` you eval lands at\n"
               ";   ; " (str "my.agent." id "/status-tile") "); return the\n"
               ";   ; {:seon.render/hiccup … :seon.render/ai …} envelope so your\n"
               ";   ; human sees the tile and YOU see its :ai twin here:\n"
               ";   (defn status-tile [m]\n"
               ";     {:seon.render/hiccup [:div {:class \"p-3\"}\n"
               ";                           [:h2 {:class \"text-sm font-bold\"} \"Status\"]\n"
               ";                           [:p {:class \"text-xs\"} \"Green.\"]]\n"
               ";      :seon.render/ai     \"Your human sees a green status card.\"})\n"
               ";   (seon.db/transact!\n"
               ";     {:seon.db/tx-data\n"
               ";      [{:seon.agent/id " (pr-str id) "\n"
               ";        :seon.render.live-tile/content '" (str "my.agent." id "/status-tile") "}]})\n"
               ";\n"
               "; Evolve a tile by redefining YOUR fn — seon.render.live-tile is\n"
               "; a shared core default (the welcome tile), so build your own\n"
               "; rather than editing it."))))
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
