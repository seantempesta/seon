(ns seon.ctx.prompt
  "The `:prompt` context section — the §2.9 status line + clean REPL
   prompt (the volatile tail's end). Symbol-wired into the composer
   layout (`seon.ctx/core-default-ctx`) as
   `'seon.ctx.prompt/prompt-section`; loaded at boot so the symbol
   resolves for `seon.eval/lookup-value`. Reads the shared derived API
   (`current-ns` / `current-session` / `turns-since-inbound` /
   `turns-cap` / `messages` / `host-timezone`) from the spine `seon.ctx`."
  (:require
    [seon.ctx :as ctx]))

(defn inbox-count
  "Count of UNANSWERED inbound messages in `msgs` (the agent's derived
   conversation, oldest-first): inbound items (from ≠ me) strictly
   after my own latest outbound message — every inbound when I have
   never replied. The `inbox K` slot of the status line (§2.9). NOTE:
   ANY outbound from me counts here, INCLUDING the per-turn self-fold
   (from = to = me) — so this window closes after turn 1 of a wake.
   The MID-TASK gate is [[seon.ctx/task-in-progress?]], which mirrors the
   loop's reply semantics instead (opus-live-tests 2026-06-12
   finding 1: sections gated on inbox-count were first-turn-only)."
  {:malli/schema [:=> [:catn [:seon.ctx.prompt/msgs [:vector :map]]
                       [:seon.ctx.prompt/own-id :string]]
                  :int]}
  [msgs own-id]
  (let [outbound? #(= own-id (:seon.agent/id (:seon.agent.message/from %)))
        after-out (->> msgs
                       reverse
                       (take-while (complement outbound?)))]
    (count (remove outbound? after-out))))

(defn localized-now
  "The current wall-clock time rendered in the HUMAN'S timezone (the
   pod host's IANA tz) — `2026-06-11 14:23:08 Europe/Madrid`. The
   sv-SE locale gives the ISO-like `YYYY-MM-DD HH:mm:ss` shape."
  []
  (let [tz (ctx/host-timezone)]
    (str (try (.toLocaleString (js/Date.) "sv-SE" #js {:timeZone tz})
              (catch :default _ (.toISOString (js/Date.))))
         " " tz)))

(defn prompt-section
  "The final two lines of every prompt (context-v4 §2.9): one status
   line, then a CLEAN REPL prompt —

     ;; ── my.agent.kXQ · turn 6 · 3 since-user (cap 20) · 2026-06-11 14:23:08 Europe/Madrid · inbox 1 · agent kXQ-2606101814 ──
     my.agent.kXQ=>

   Every per-turn-volatile byte lives HERE at the context tail so the
   sections above stay a stable provider-cacheable prefix
   (context-audit 2026-06-09 §4). The agent id lands here (moved OUT
   of `<system>`, §2.1 — the system block is one shared cacheable
   artifact across the cluster). `inbox K` = unanswered inbound
   messages. Turn-pressure nudges render ABOVE the status line when
   escalating (wrap up at halfway, FINAL WARNING before
   `run-agentic-loop!` cuts the loop off) — normally the section is
   exactly the two lines. The final line is EXACTLY `<current-ns>=> `
   — no trailing metadata; the agent completes the next REPL input.
   Always present (never blank)."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.agent/keys [id] db :seon.db/db}]
  (let [ns       (ctx/current-ns {:seon.agent/id id :seon.db/db db})
        ;; current-ns returns a keyword (latest eval's :seon.eval/ns) or a
        ;; symbol (home-ns fallback) — render without the keyword colon,
        ;; like a real REPL prompt.
        ns-str   (if (keyword? ns) (name ns) (str ns))
        sess     (ctx/current-session id db)
        n-turns  (count (:seon.agent.session/turns sess))
        since-u  (ctx/turns-since-inbound {:seon.agent/id id :seon.db/db db})
        cap      (ctx/turns-cap id db)
        inbox    (inbox-count (ctx/messages {:seon.agent/id id :seon.db/db db})
                              id)
        pressure
        (cond
          (>= since-u (max 1 (- cap 3)))
          (str ";; ⚠⚠⚠ FINAL WARNING — turn " since-u "/" cap " since your\n"
               ";; human last spoke. You WILL hit the cap in a turn or two.\n"
               ";; STOP researching. TRANSACT THE :assistant MESSAGE NOW with\n"
               ";; whatever you have — even partial. Your human gets NOTHING\n"
               ";; if you don't reply.\n")
          (>= since-u (quot cap 2))
          (str ";; ⚠ Turn " since-u "/" cap " since your human last spoke —\n"
               ";; past halfway. You probably have enough. Stop reading new\n"
               ";; things; compose the :assistant reply with what you found.\n")
          (>= since-u 5)
          (str ";; Turn " since-u "/" cap " since your human last spoke —\n"
               ";; most questions need 2–4 turns. If you have the answer,\n"
               ";; reply now.\n")
          :else "")]
    (str pressure
         ";; ── " ns-str " · turn " n-turns " · " since-u
         " since-user (cap " cap ") · " (localized-now)
         " · inbox " inbox " · agent " id " ──\n"
         ns-str "=> ")))
