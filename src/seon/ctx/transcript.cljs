(ns seon.ctx.transcript
  "The `:transcript` context section + its `:seon.render/html` twin — the
   agent's PAST turns, grouped `<turn id=… evals=N/M>`, REPL-faithful.
   Symbol-wired into the composer layout (`seon.ctx/core-default-ctx`) as
   `'seon.ctx.transcript/transcript-section` (and the html twin
   `'seon.ctx.transcript/transcript-section-html`); loaded at boot so the
   symbols resolve for `seon.eval/lookup-value`.

   Shared eval-row rendering + caps + the core-authored derivation stay
   in the spine `seon.ctx` (`format-eval-row`, `cap-result`,
   `message-render-cap`, `core-authored-turn?`, `current-session`) — this
   ns owns only the turn-grouping walk, the per-turn block markup, and the
   transcript-specific defs."
  (:require
    [clojure.string :as str]
    [seon.ctx :as ctx]
    [seon.db :as db]
    [seon.warn :as warn]))

(def transcript-char-budget
  "Total rendered-chars cap for the transcript section (~6k tokens at
   chars/4). Why 24,000: the audit measured an UNBOUNDED transcript at
   90,468 chars by turn 58 — 83% of a 27k-token context, dominating
   both spend and the model's attention. 24k keeps the newest ~15
   worst-case eval rows (≤1.6KB each via `eval-render-cap`) or several
   dozen typical items whole — comfortably more than the 2–4 turns most
   questions need — while bounding context ≈ static sections + 6k tok.
   Retention is NEWEST-FIRST: oldest items drop beyond the budget and
   an elision note replaces them at the top."
  24000)

(def resume-marker-line
  "The session-resume boundary row (context-v4 §2.8): rendered ONCE per
   resume, between the last turn of a previous process and the first of
   the next. Everything above it ran in a process that no longer
   exists — its `result/<id>` vars are not dereferenceable."
  (str ";; ── session resumed — the turns above ran in a previous process; "
       "their result/<id> vars are gone (re-run a form to recompute a value) ──"))

(def transcript-header
  "A VISIBLE in-band header rendered as the FIRST lines inside the
   `<past-evals>` envelope (NOT an XML attribute — weak models skim
   attributes). It disclaims the runtime-owned annotations (the
   `=> value` line + the `;; result/<id>` handle): (a) these are PAST
   evaluations the runtime produced, not the agent's own output shape,
   and (b) the agent emits ONLY `;;` comments and forms, then STOPS and
   waits for the runtime to produce the real result. Mimicry safety: the
   agent's own forms render with NO tags, so there is nothing in its
   output shape to copy."
  (str ";; ↓↓ PAST EVALUATIONS — the runtime produced these `=> value` and\n"
       ";; `;; result/<id>` lines. READ them; NEVER write a `=>`, `⇒`, or\n"
       ";; `;; result/` line yourself — you emit ONLY ;; comments and forms,\n"
       ";; then STOP and wait for the runtime to produce the real result.\n"
       ";; To reuse any value, reference its result/<id> directly; it is a\n"
       ";; live var."))

(defn- session-turns
  "ALL :seon.agent.turn entities for `agent-id`, oldest-first across ALL
   sessions, each tagged with its owning `:seon.agent.session/id` and
   `:seon.ctx.transcript/core-authored?` ([[seon.ctx/core-authored-turn?]])
   — the turn-grouped transcript walk (context-v4 §2.8: prior-session
   turns render too, behind a resume boundary). Walks agent → sessions →
   turns. Each turn's `:seon.agent.turn/evals` ride along as the (lazy
   datahike) ref vector. Optional `db` snapshot (the composer threads
   its render db)."
  [agent-id db]
  (let [a (db/entity (cond-> {:seon.db/ref [:seon.agent/id agent-id]}
                       db (assoc :seon.db/db db)))]
    (vec
      (for [s (sort-by :seon.agent.session/at (:seon.agent/sessions a))
            t (sort-by :seon.agent.turn/at (:seon.agent.session/turns s))]
        {::turn                            t
         :seon.agent.session/id-of-session (:seon.agent.session/id s)
         ::core-authored?                  (ctx/core-authored-turn? t)}))))

(defn- sender-line
  "The `<user>…</user>` (human) or `<from agent=<id>>…</from>` (agent) line
   for a message's `from` ref (a pulled map carrying `:seon.user/id` /
   `:seon.agent/id`) and `content` string — the ONE place a triggering
   message becomes a transcript line, shared by [[woken-by-line]] (a
   turn's wake) and the pending-inbound render (the not-yet-threaded
   question). Content is bounded by [[seon.ctx/message-render-cap]]."
  [from content]
  (let [body (ctx/cap-result content ctx/message-render-cap)]
    (if (:seon.user/id from)
      (str "<user>" body "</user>")
      (str "<from agent=" (:seon.agent/id from) ">" body "</from>"))))

(defn- woken-by-line
  "The `<user>` (or `<from agent=…>`) line for a turn — the message whose
   wake opened it: `:seon.agent.turn/woken-by` → `:seon.agent.message/content`,
   with the tag chosen by the sender's ref kind (a `:seon.user/id` ref =
   the human → `<user>`; an agent ref → `<from agent=…>`).
   Returns nil when the turn has no woken-by (boot/manual turns get no
   `<user>` line). The human → `<user>…</user>`; this agent itself or
   another agent → `<from agent=<id>>…</from>` so a human vs agent
   trigger is unambiguous. Content is bounded by [[seon.ctx/message-render-cap]]."
  [turn own-id]
  (when-let [wb (:seon.agent.turn/woken-by turn)]
    (when-let [content (:seon.agent.message/content wb)]
      (sender-line (:seon.agent.message/from wb) content))))

(defn- render-turn
  "Render ONE turn as a `<turn id=… evals=N/M>` block: the woken-by
   `<user>`/`<from>` line (omitted when absent), then each eval rendered
   REPL-faithful by [[seon.ctx/format-eval-row]] (a `;; in <ns>` marker
   injected only where the eval ns changes, so ns switches stay visible
   without a per-row prompt prefix). `evals=N/M` is ok-count / total.
   PRIOR-SESSION turns (`prior?` true) render their evals WITHOUT
   `result/<id>` handles (the vars died with the process). CORE-AUTHORED
   turns render their eval bodies at [[seon.ctx/core-eval-render-cap]]
   (tagged via `core?`).

   `repeat-wake?` true ⇒ this turn was woken by the SAME message as the
   PREVIOUS turn (the agent is taking multiple turns on ONE wake) — the
   `<user>` line is SUPPRESSED so the question renders ONCE per wake, not
   re-printed every continuation turn. Without this, a 5-turn answer
   shows the question 5 times and a weak model reads it as 'asked 5
   times' (live-observed 2026-06-21)."
  [{turn ::turn core? ::core-authored?} own-id prior? repeat-wake?]
  (let [evals  (->> (:seon.agent.turn/evals turn)
                    (sort-by :seon.eval/at)
                    (mapv #(assoc (into {} %) :seon.ctx/core-authored? core?)))
        n-tot  (count evals)
        n-ok   (count (filter :seon.eval/ok? evals))
        tid    (:seon.agent.turn/id turn)
        user-ln (when-not repeat-wake? (woken-by-line turn own-id))
        eval-rows
        (loop [[e & more] evals prev-ns ::none out []]
          (if (nil? e)
            out
            (let [ns-kw    (:seon.eval/ns e)
                  ns-marker (when (and (some? ns-kw) (not= prev-ns ns-kw))
                              (str ";; in " (name ns-kw)))
                  r         (ctx/format-eval-row e prior?)
                  row-text  (if ns-marker (str ns-marker "\n" r) r)]
              (recur more (or ns-kw prev-ns) (conj out row-text)))))]
    (->> [(str "<turn id=" tid " evals=" n-ok "/" n-tot ">")
          user-ln
          (when (seq eval-rows) (str/join "\n" eval-rows))
          "</turn>"]
         (remove nil?)
         (str/join "\n"))))

(defn- pending-inbound-line
  "The PENDING inbound — the message the agent is being woken to answer
   RIGHT NOW, rendered as a trailing `<user>`/`<from>` line so the agent
   sees the question it must respond to. Load-bearing: a turn's prompt is
   rendered BEFORE that turn opens (run-turn! does render-prompt → then
   with-turn!), so the incoming message is NOT yet any turn's woken-by at
   render time — without this line the agent would never see the question
   it is answering (the old message-interleave surfaced every message;
   the turn-grouped render only shows woken-by). Returns the line only
   when the latest LIVE inbound (to ∋ me, from ≠ me, hops < `warn/hop-cap`)
   is NOT already some turn's woken-by; nil otherwise (already threaded,
   or no live inbound). One reactive query, nothing stored."
  [db my-eid turns]
  (when my-eid
    (let [latest (->> (db/query
                        {:seon.db/db db
                         :seon.db/query
                         '[:find ?m ?at ?content ?f
                           :in $ ?me ?cap
                           :where
                           [?m :seon.agent.message/to ?me]
                           [?m :seon.agent.message/from ?f]
                           [(not= ?f ?me)]
                           [(get-else $ ?m :seon.agent.message/hops 0) ?h]
                           [(< ?h ?cap)]
                           [?m :seon.agent.message/at ?at]
                           [?m :seon.agent.message/content ?content]]
                         :seon.db/args [my-eid warn/hop-cap]})
                      (sort-by #(.getTime ^js (second %)))
                      last)]
      (when latest
        (let [[m-eid _ content f-eid] latest
              woken-eids (into #{}
                               (keep #(:db/id (:seon.agent.turn/woken-by (::turn %))))
                               turns)]
          ;; Render only when this inbound has NOT yet opened a turn — i.e.
          ;; it is the question the CURRENT (about-to-open) turn answers.
          (when-not (contains? woken-eids m-eid)
            (let [from (db/entity {:seon.db/db db :seon.db/ref f-eid})]
              (sender-line {:seon.user/id (:seon.user/id from)
                            :seon.agent/id (:seon.agent/id from)}
                           content))))))))

(defn transcript-section
  "The THREADED TRANSCRIPT (transcript-redesign-2026-06-18) — the agent's
   PAST turns, oldest-first, grouped by turn: one `<turn id=… evals=N/M>`
   block per turn carrying the woken-by `<user>`/`<from>` line and the
   turn's evals rendered REPL-faithful ([[seon.ctx/format-eval-row]] —
   `;;` preamble, the form, a `=> value ;; result/<id>` output line, or a
   `=> ✗` failure line). The whole thing wraps in `<past-evals>` with a
   VISIBLE [[transcript-header]] as its first in-band lines (the disclaimer
   is a header, not an attribute — weak models skim attributes).

   Only ENVELOPE tags (`<past-evals>`/`<turn>`/`<user>`/`<from>`) are
   XML — the agent's own output renders as plain comments + forms +
   REPL output, with NO `<eval>` tag, so there is nothing in its output
   shape to mimic (the live `<your-ns>=>` cursor at the very END of the
   context is the strongest 'type here' signal).

   SESSION RESUME: turns from PREVIOUS sessions render too
   ([[session-turns]] walks all sessions), separated by ONE
   [[resume-marker-line]] per resume; prior-session turns render their
   evals WITHOUT `result/<id>` handles (the vars died with the process).

   Budget eviction is OLDEST-TURN-FIRST: the newest turns are kept whole
   (always at least one); older turns drop beyond
   [[transcript-char-budget]] and an elision note replaces them at the
   top. Each AGENT eval row is bounded by [[seon.ctx/eval-render-cap]];
   CORE-AUTHORED turns (the creation-turn tutorial — tagged by
   [[session-turns]]) render their evals at
   [[seon.ctx/core-eval-render-cap]]."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.agent/keys [id] db :seon.db/db}]
  (let [db       (or db @db/*conn*)
        cur-sess (:seon.agent.session/id (ctx/current-session id db))
        turns    (session-turns id db)
        my-eid   (:db/id (db/entity {:seon.db/db db
                                     :seon.db/ref [:seon.agent/id id]}))
        ;; The question being answered NOW — not yet any turn's woken-by
        ;; (the prompt renders BEFORE its turn opens). ALWAYS kept.
        pending  (pending-inbound-line db my-eid turns)
        ;; Build the rendered rows oldest-first; a resume marker rides as
        ;; its own row just before a turn whose session differs from the
        ;; previous turn's.
        turn-items
        (loop [[t & more] turns prev-sess ::none prev-wb ::none out []]
          (if (nil? t)
            out
            (let [sess   (:seon.agent.session/id-of-session t)
                  prior? (and (some? cur-sess) (not= sess cur-sess))
                  ;; The message-eid that woke this turn. When it matches
                  ;; the previous turn's, this is a CONTINUATION of the same
                  ;; wake (multiple turns on one question) — suppress the
                  ;; repeated <user> line so the question renders once.
                  wb     (:db/id (:seon.agent.turn/woken-by (::turn t)))
                  repeat-wake? (and (some? wb) (= wb prev-wb))
                  marker (when (and (not= prev-sess ::none)
                                    (not= prev-sess sess))
                           {:seon.ctx/kind :seon.ctx/marker
                            :seon.render/text resume-marker-line})
                  row    {:seon.ctx/kind :seon.ctx/turn
                          :seon.render/text (render-turn t id prior? repeat-wake?)}]
              (recur more sess wb (into out (if marker [marker row] [row]))))))
        ;; The pending-inbound line rides as an always-kept item at the END.
        items (cond-> turn-items
                pending (conj {:seon.ctx/kind :seon.ctx/pending
                               :seon.render/text pending}))]
    (if (seq items)
      (let [rendered (mapv :seon.render/text items)
            ;; Resume markers are ALWAYS kept (they orient the agent); only
            ;; TURN blocks are evictable.
            exempt?  (mapv #(not= :seon.ctx/turn (:seon.ctx/kind %)) items)
            kept-chars (transduce
                         (keep-indexed
                           (fn [i s] (when (exempt? i) (+ (count s) 2))))
                         + 0 rendered)
            ;; NEWEST-FIRST retention over the TURN blocks: walk from the
            ;; end accumulating rendered chars; keep the newest turns WHOLE
            ;; (always at least one), drop everything older — eviction is
            ;; OLDEST-FIRST by construction.
            kept-turn (loop [i (dec (count rendered)) acc kept-chars kept #{}]
                        (if (neg? i)
                          kept
                          (if (exempt? i)
                            (recur (dec i) acc kept)
                            (let [acc' (+ acc (count (rendered i)) 2)]
                              (if (and (seq kept)
                                       (> acc' transcript-char-budget))
                                kept
                                (recur (dec i) acc' (conj kept i)))))))
            kept-idx (filterv #(or (exempt? %) (kept-turn %))
                              (range (count rendered)))
            elided   (- (count rendered) (count kept-idx))
            kept     (mapv rendered kept-idx)]
        (str "<past-evals>\n" transcript-header "\n"
             (when (pos? elided)
               (str ";; … " elided " older turn" (when (not= 1 elided) "s")
                    " elided (transcript capped at " transcript-char-budget
                    " chars; the full log is in the db — "
                    "(seon.agent/messages) / (seon.agent/evals))\n\n"))
             (str/join "\n" kept)
             "\n</past-evals>"))
      "")))

(defn- turn-card-hiccup
  "ONE turn rendered as a card (debug-view-section-twins-2026-06-18): the
   `turn N` header + woken-by `<user>`/`<from>` line, then the turn's
   evals as the REPL-faithful text [[render-turn]] already produces,
   dropped into a `[:pre]`. Lifts the card framing from the inspector's
   `card-block` (border-l rail, mono text). `prior?` strips result/<id>
   handles for prior-session turns."
  [{turn ::turn :as t} own-id prior? n repeat-wake?]
  (let [tid   (:seon.agent.turn/id turn)
        evals (:seon.agent.turn/evals turn)
        body  (render-turn t own-id prior? repeat-wake?)]
    [:div {:class "border-l-2 border-amber-700/40 pl-2 py-1 mb-1"}
     [:div {:class "text-xs font-mono text-text-400"}
      [:span {:class "font-semibold text-text-300"} (str "turn " n)]
      [:span {:class "text-text-500"} (str "  id=" tid
                                           "  evals=" (count evals))]]
     [:pre {:class (str "mt-0.5 text-xs font-mono whitespace-pre-wrap "
                        "break-all text-text-100")}
      body]]))

(defn transcript-section-html
  "The HTML TWIN of [[transcript-section]] (debug-view-section-twins-
   2026-06-18): the agent's OWN turns/evals/messages rendered as cards,
   oldest-first (the same [[session-turns]] walk the text twin uses, so
   it is STRUCTURALLY agent-scoped — core `:seon.test` index entities are
   never in the agent's session turns and so never appear here). Render
   order = the transcript's turn order. Returns the standard
   `:seon.render/html-response` map (`{:seon.render/hiccup …}`); an empty
   transcript renders a friendly placeholder card rather than vanishing.

   This replaces the old per-entity last-64-by-tx-time right-pane window
   (`render/visible-entities`), which flooded with the core's own test
   captures and no longer mirrored the prompt."
  {:malli/schema [:=> [:cat :map] :seon.render/html-response]}
  [{:seon.agent/keys [id] db :seon.db/db :seon.ctx/keys [section]}]
  (let [db       (or db @db/*conn*)
        cur-sess (:seon.agent.session/id (ctx/current-session id db))
        turns    (session-turns id db)
        cards
        (->> turns
             (map-indexed
               (fn [i t]
                 (let [sess   (:seon.agent.session/id-of-session t)
                       prior? (and (some? cur-sess) (not= sess cur-sess))
                       wb     (:db/id (:seon.agent.turn/woken-by (::turn t)))
                       prev-wb (when (pos? i)
                                 (:db/id (:seon.agent.turn/woken-by
                                           (::turn (nth turns (dec i))))))
                       repeat-wake? (and (some? wb) (= wb prev-wb))]
                   (turn-card-hiccup t id prior? (inc i) repeat-wake?))))
             vec)]
    {:seon.render/hiccup
     (if (seq cards)
       (into [:div {:class "flex flex-col"}] cards)
       [:div {:class "text-text-500 italic p-2 text-xs font-mono"}
        "no turns yet — every turn this agent takes appears here live"])}))
