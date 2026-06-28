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
               "; this tile; your messages are backup narration that scrolls\n"
               "; away. Anything worth seeing at a glance — a status, a plan, a\n"
               "; data breakdown, a result table, progress — belongs HERE, not\n"
               "; recited in a paragraph. Set it with ONE transact (copy-paste,\n"
               "; swap in your content):\n"
               ";\n"
               ";   ; EASIEST — COMPOSE my.ui dual-render helpers (each returns\n"
               ";   ; {:seon.render/hiccup … :seon.render/ai …}, so the human's\n"
               ";   ; styled view and the text you see can't drift). Read my.ui\n"
               ";   ; for the set; status-line / kv-table stack into a section:\n"
               ";   (seon.db/transact!\n"
               ";     {:seon.db/tx-data\n"
               ";      [{:seon.agent/id (seon.db/current-agent-id)\n"
               ";        :seon.render.live-tile/content\n"
               ";        (:seon.render/hiccup\n"
               ";         (my.ui/section\n"
               ";           {:my.ui/title \"Status\"\n"
               ";            :my.ui/blocks\n"
               ";            [(my.ui/status-line {:my.ui/label \"State\"\n"
               ";                                 :my.ui/value \"green\"\n"
               ";                                 :my.ui/tone :success})]}))}]})\n"
               ";\n"
               ";   ; (a) literal hiccup — instant, no fn needed:\n"
               ";   (seon.db/transact!\n"
               ";     {:seon.db/tx-data\n"
               ";      [{:seon.agent/id (seon.db/current-agent-id)\n"
               ";        :seon.render.live-tile/content\n"
               ";        [:div {:class \"p-3 flex flex-col gap-1\"}\n"
               ";         [:h2 {:class \"text-sm font-bold text-signal\"} \"Status\"]\n"
               ";         [:p {:class \"text-xs text-text-200\"} \"All systems go.\"]]}]})\n"
               ";\n"
               ";   ; (b) a tile FN in your home ns — re-derives every render so\n"
               ";   ; a live count / query result stays current with no rewrite.\n"
               ";   ; Return {:seon.render/hiccup … :seon.render/ai …}: the human\n"
               ";   ; sees the tile, YOU see its :ai twin in this section.\n"
               ";   (defn status-tile [m]\n"
               ";     {:seon.render/hiccup [:div {:class \"p-3\"}\n"
               ";                           [:h2 {:class \"text-sm font-bold\"} \"Status\"]\n"
               ";                           [:p {:class \"text-xs\"} \"Green.\"]]\n"
               ";      :seon.render/ai     \"Your human sees a green status card.\"})\n"
               ";   (seon.db/transact!\n"
               ";     {:seon.db/tx-data\n"
               ";      [{:seon.agent/id (seon.db/current-agent-id)\n"
               ";        :seon.render.live-tile/content '" (str "my.agent." id "/status-tile") "}]})\n"
               ";\n"
               "; SAFELIST — ONLY these CSS classes exist at runtime; anything\n"
               "; else is INVISIBLE (the CSS is built ahead of time, you emit\n"
               "; hiccup at runtime). Classless semantic hiccup ([:table] [:ul]\n"
               "; [:h2] [:p] [:pre [:code …]]) is styled for free — prefer it.\n"
               ";   layout: flex flex-col flex-row grid grid-cols-{2,3,4} gap-{1-4}\n"
               ";           items-{center,start,baseline} justify-{between,end,center}\n"
               ";           w-full h-full min-w-0 shrink-0\n"
               ";   space : p-{0-4} px-{1-4} py-{1-4} mt-{1,2} mb-{1,2}\n"
               ";   text  : text-{2xs,xs,sm,base,lg} font-mono font-semibold font-bold\n"
               ";           italic uppercase truncate whitespace-pre-wrap tabular-nums\n"
               ";   color : text-text-{50..700} text-{signal,success,error,warning,info}\n"
               ";           text-amber-{300,400,500} bg-base-{800,850,900,950}\n"
               ";   border: border border-{t,b} border-base-{700,800} rounded rounded-md\n"
               ";           divide-y overflow-{hidden,auto}\n"
               ";\n"
               "; A quick conversational answer CAN still be a plain markdown\n"
               "; reply (it renders as a card on the core default tile) — but the\n"
               "; moment you have data, a list, a table, or progress, SET your\n"
               "; tile; that is what your human keeps in view. Evolve it by\n"
               "; redefining YOUR fn — never edit the shared core welcome tile."))))
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
