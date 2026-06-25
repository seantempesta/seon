(ns seon.ctx.live-tile
  "The `:live-tile` context section — \"what your human currently sees\",
   rendered as a `;; ── live tile ──` comment-block. Symbol-wired into the
   composer layout (`seon.ctx/core-default-ctx`) as
   `'seon.ctx.live-tile/live-tile-section`; loaded at boot so the symbol
   resolves for `seon.eval/lookup-value`.

   The agent sees the SAME wired value the human's surfaces render —
   derived every turn, nothing stored (reactive-context doctrine), so the
   agent can never believe its tile is blank when the human sees content.
   Self-contained: no spine read API, just the tile renderer +
   wired-content provenance."
  (:require
    [seon.ctx :as ctx]
    [seon.db :as db]
    [seon.render :as render]
    [seon.render.live-tile :as live-tile]))

(defn live-tile-section
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
  [{:seon.db/keys [db] :seon.agent/keys [id] entity :seon.agent/entity}]
  (let [{:seon.render/keys [hiccup ai error]}
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
      ;; Provenance for the header. The composer's entity pull cannot
      ;; name :seon.render.live-tile/content explicitly — datahike
      ;; THROWS on pulling an attr the conn never installed (installs
      ;; are lazy, at first transact), and a store predating the tile
      ;; key must still assemble context — so the slot is read here
      ;; behind the same `seon.db/installed-schema` gate
      ;; `live-tile/user-name` uses (load-bearing, not defensive fluff).
      (let [ent   (if (contains? (db/installed-schema db)
                                 :seon.render.live-tile/content)
                    (merge entity
                           (db/pull {:seon.db/db db
                                     :seon.db/pull-pattern
                                     '[:seon.render.live-tile/content]
                                     :seon.db/ref [:seon.agent/id id]}))
                    entity)
            wired (live-tile/wired-content {:seon.render/entity ent})
            ;; The body is a render twin (:ai text, or hiccup pr-str, or
            ;; an error envelope) — arbitrary content the human's tile
            ;; shows. It rides this comment-block as `;` lines (via
            ;; [[seon.ctx/quote-lines]]) so the whole section reads as
            ;; eval'able Clojure (the context IS one live REPL); the agent
            ;; reads the value, it never evaluates.
            body-comment (ctx/quote-lines body)]
        (str "; Your live tile — what your human currently sees (as-of this\n"
             "; turn's render; the human's view live-updates between turns).\n"
             "; Wired: " (live-tile/wired-label wired) "\n"
             ";\n"
             body-comment "\n"
             ";\n"
             "; To change it: redefine the wired fn, or transact a new value\n"
             "; (a qualified fn symbol or literal hiccup) onto\n"
             "; :seon.render.live-tile/content on your agent entity.")))))
