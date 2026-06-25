(ns seon.ctx.transcript
  "The `:transcript` context section + its `:seon.render/html` twin — the
   WHOLE bottom of the agent's context.

   ONE flat, time-ordered EVENT LOG. The transcript is a chronological
   stream of EVENTS — inbound/outbound messages and evals — each carrying
   its own stored time (`:seon.agent.message/at` / `:seon.eval/at`), sorted
   by that instant, rendered through the SAME recursive `seon.render/render`
   handle every other section uses (ONE section model). Turn boundaries are
   NOT containers — there are no per-turn headers; the only structural
   marker is the session-resume boundary, interleaved by time.

   It reads as an eval'able REPL transcript: `;;` comments + forms +
   `;;=>`-commented results + `;;;` runtime-structure lines + a live `ns=>`
   readline. Re-evaluating the forms (comments pass through) reproduces the
   agent's state — the context IS a replayable program (the north star).

   The masthead opens it, the events stream in time order, and the folded
   live readline at the very bottom carries the cursor (current ns) + this
   turn's status/steering. Symbol-wired into `seon.ctx/core-default-ctx` as
   `'seon.ctx.transcript/transcript-section` (+ the html twin
   `'…/transcript-section-html`).

   BYTE-STABILITY: every past event renders byte-identical turn-to-turn —
   each time comes from the event's FIXED stored `:at` (never `now`), and
   the event list is `sort-by`'d before joining. The ONLY moving byte is
   the single current-time line in the readline at the very bottom (below
   the cache breakpoint — busting there is free). No render fn here calls
   `now` to produce displayed text except that one readline line.

   The eval-row converter delegates to `seon.ctx/format-eval-row` (which
   carries the fabrication-guard + the component caps); the message
   converter renders the REPL-comment `;;; ◀ from X` / `;;; ▶ to X` line."
  (:require
    [clojure.string :as str]
    [seon.ctx :as ctx]
    [seon.db :as db]
    [seon.render :as render]
    [seon.warn :as warn]))

(def transcript-char-budget
  "Total rendered-chars cap for the transcript section (~6k tokens at
   chars/4). RETAINED as the eviction knob but currently OFF for the
   transcript (`:seon.render/clip :none` — the transcript renders ALL
   events until the agent manages its own context; the sliding window
   lands later). Override via env SEON_TRANSCRIPT_CHAR_BUDGET."
  (or (some-> (.. js/process -env -SEON_TRANSCRIPT_CHAR_BUDGET)
              js/parseInt
              (#(when-not (js/isNaN %) %)))
      24000))

;; ------------------------------------------------------------
;; Masthead — the positive-framing opener. Rendered every turn as the
;; FIRST lines of the transcript. It teaches the live-and-current REPL by
;; LEADING WITH WHAT TO DO (never a `don't write ;;=>` prohibition — a
;; negative example primes the very mimicry it forbids, standing owner
;; rule). The ns slot is the only volatile byte; it rides the masthead so
;; the agent sees its own session name.
;; ------------------------------------------------------------

(defn masthead
  "The transcript masthead for namespace label `ns-str` — the
   positive-framing opener, rendered every turn. Single source: the agent
   never sees a `;;=>` shape it isn't told the RUNTIME writes, so there is
   nothing in its own output to mimic. LEADS with what to do; reinforces
   the REPL is LIVE and ALWAYS CURRENT (re-derived from the DB every turn,
   never a stale replay)."
  [ns-str]
  (let [bar (apply str (repeat 24 "═"))]
    (str ";;; " bar " seon · " ns-str " · live REPL " bar "\n"
         ";;; This is your live REPL — a Clojure session backed by the database.\n"
         ";;; The history below is real and ALWAYS current: it re-derives from the\n"
         ";;; DB every turn, so it is never stale. It is a flat, time-ordered log of\n"
         ";;; events — your messages and your evals, oldest-first. You write Clojure\n"
         ";;; forms and ;; comments. After each form the runtime evaluates it and\n"
         ";;; shows the value on the next line as `;;=> …` — that is how your results\n"
         ";;; arrive, on the turn after you write the form. So just write the form;\n"
         ";;; read its `;;=>` next turn. Append below.")))

(def resume-marker-line
  "The session-resume boundary: rendered ONCE per resume, between the
   last event of a previous process and the first of the next, as a `;;;`
   runtime-structure comment. Everything above it ran in a process that no
   longer exists — its `result/<id>` vars are not dereferenceable
   (re-run a form to recompute a value)."
  ";;; ── session resumed — the events above ran in a previous process; their result/<id> vars are gone (re-run a form to recompute) ──")

;; ------------------------------------------------------------
;; Time — every displayed event time derives from the event's FIXED
;; stored `:at` (byte-stable). `clock` FAILS LOUD on nil: a render fn
;; must never silently inject a live `now` into transcript-body text
;; (that would bust the cache prefix every turn). The one legitimate
;; live `now` is the readline at the very bottom, which uses `js/Date`
;; directly, not `clock`.
;; ------------------------------------------------------------

(defn clock
  "An `HH:mm:ss` wall-clock time in the host (human's) timezone for a
   stored `#inst` `inst`. The short time the `;;;` event lines carry (the
   readline carries the full localized date+tz). sv-SE → ISO-like
   `HH:mm:ss`.

   FAILS LOUD when `inst` is nil/not a Date: every transcript-body time
   MUST come from a fixed stored `:at` so the rendered prefix stays
   byte-stable across turns. A nil here means a caller lost an event's
   stored time — surface it, never paper over it with a live clock."
  [inst]
  (when-not (instance? js/Date inst)
    (throw (ex-info (str "seon.ctx.transcript/clock: missing stored time — "
                         "every transcript event time must derive from its "
                         "fixed :at (byte-stability); got " (pr-str inst))
                    {:seon.ctx.transcript/inst inst})))
  (try (.toLocaleTimeString ^js inst "sv-SE" #js {:timeZone (ctx/host-timezone)})
       (catch :default _ (subs (.toISOString ^js inst) 11 19))))

;; ------------------------------------------------------------
;; Inbound gate — the SAME predicate as the wake, so a `:core` substrate
;; nudge never renders as a fake inbound.
;;
;; FALLBACK: the canonical gate is `seon.agent/inbound-msg-datom?`, but
;; `seon.agent` REQUIRES this ns, so requiring it back here is a require
;; CYCLE. Compiling clean beats a cycle, so we keep a small LOCAL
;; predicate over the same conditions.
;; ------------------------------------------------------------

(defn- inbound-msg?
  "True iff message map `m` (pulled with its `from` ref carrying
   `:seon.user/id`/`:seon.agent/id`) is a WAKING inbound for the agent
   whose eid is `my-eid`: from ≠ me, origin ∈ {:human :agent} (absent
   origin = legacy human/agent, waking), handled? ≠ true, hops < hop-cap.
   Mirrors seon.agent/inbound-msg-datom? + the hop-cap clause — ONE gate
   so a `:core` nudge never renders as a fake inbound."
  [m my-eid]
  (let [from   (:seon.agent.message/from m)
        origin (:seon.agent.message/origin m)
        hops   (or (:seon.agent.message/hops m) 0)]
    (and (not= my-eid (:db/id from))
         (not= :core origin)
         (not (true? (:seon.agent.message/handled? m)))
         (< hops warn/hop-cap))))

;; ------------------------------------------------------------
;; Converters — bare-String render fns, the schema-default for an event.
;; Each takes the standard `{:seon.render/node …}` input the recursive
;; `render` injects, and returns a String. The transcript section points
;; each event's `:seon.render/ai` slot at one of these.
;; ------------------------------------------------------------

(defn message->renderable
  "The `:seon.render/ai` converter for a transcript MESSAGE event: ONE
   `;;; ◀ from X @ time — \"…\"` (inbound) or `;;; ▶ to X @ time — \"…\"`
   (outbound) runtime-structure line. The `from`/`to` labels resolve by
   ref kind (`user`/`assistant`/`agent-<id>`). The message's transactable
   handle (`:seon.agent.message/id`) rides the line so the agent can pull
   it. Content bounded by [[seon.ctx/message-render-cap]]. `new?` marks a
   message that arrived mid-LLM-call (after the last event of the prior
   turn) so the agent knows it is acting on it for the first time."
  {:malli/schema [:=> [:cat :map] :string]}
  [{node :seon.render/node}]
  (let [{::keys [at from-label to-labels content id new? outbound?]} node
        body (ctx/cap-result content ctx/message-render-cap)
        who  (if outbound?
               (str "▶ to " (str/join ", " to-labels))
               (str "◀ from " from-label))]
    (str ";;; " who " @ " (clock at)
         (when id (str " [" id "]"))
         (when new? " (NEW — arrived while you were working)")
         " — \"" body "\"")))

(defn eval->renderable
  "The `:seon.render/ai` converter for a transcript EVAL event — the
   canonical eval row. Delegates to [[seon.ctx/format-eval-row]], which
   carries the fabrication-guard ([[seon.ctx/neutralize-result-claims]])
   and the component caps ([[seon.ctx/eval-render-cap]] /
   [[seon.ctx/result-body-render-cap]]) forward. A `::ns-marker?` true
   event prepends a `;; in <ns>` line (emitted only where the eval ns
   changes from the prior eval). PRIOR-SESSION evals (`::prior?` true)
   render their value WITHOUT the `result/<id>` handle (their vars died
   with the restart; the resume marker says so once)."
  {:malli/schema [:=> [:cat :map] :string]}
  [{node :seon.render/node}]
  (let [{::keys [entity prior? ns-marker]} node
        row (ctx/format-eval-row entity (boolean prior?))]
    (if ns-marker
      (str ns-marker "\n" row)
      row)))

;; ------------------------------------------------------------
;; The agent's full event stream, derived once per render: messages +
;; evals, flattened, each carrying its FIXED stored time, sorted by it.
;; ------------------------------------------------------------

(defn- message-events
  "ALL of the agent's WAKING-inbound + outbound messages as transcript
   events, each `{::at ::kind :message :seon.render/ai 'message->renderable
   ::from-label ::to-labels ::content ::id ::outbound?}`. Inbound messages
   pass the [[inbound-msg?]] gate (so a `:core` nudge never renders as a
   fake inbound); outbound messages (from me) always render. ONE query."
  [db my-eid own-id]
  (when my-eid
    (->> (db/query
           {:seon.db/db db
            :seon.db/query
            ;; Wildcard pull: an attr with zero datoms (e.g. handled?
            ;; before consumption) is simply ABSENT (pulling it explicitly
            ;; THROWS). The gate reads absent handled? as "not handled".
            '[:find (pull ?m [* {:seon.agent.message/from
                                 [:db/id :seon.user/id :seon.agent/id]
                                 :seon.agent.message/to
                                 [:db/id :seon.user/id :seon.agent/id]}])
              :in $ ?me
              :where
              (or-join [?m ?me]
                [?m :seon.agent.message/from ?me]
                [?m :seon.agent.message/to ?me])
              [?m :seon.agent.message/at _]]
            :seon.db/args [my-eid]})
         (map first)
         (keep
           (fn [m]
             (let [from      (:seon.agent.message/from m)
                   outbound? (= my-eid (:db/id from))]
               (when (or outbound? (inbound-msg? m my-eid))
                 {::at        (:seon.agent.message/at m)
                  ::kind      :message
                  ::id        (:seon.agent.message/id m)
                  ::outbound? outbound?
                  ::content   (:seon.agent.message/content m)
                  ::from-label (ctx/message-label from own-id)
                  ::to-labels (->> (:seon.agent.message/to m)
                                   (map #(ctx/message-label % own-id))
                                   distinct vec)
                  :seon.render/ai 'seon.ctx.transcript/message->renderable})))))))

(defn- eval-events
  "ALL of the agent's evals as transcript events across ALL sessions,
   oldest-first, each `{::at ::kind :eval ::entity ::session-id ::prior?
   :seon.render/ai 'eval->renderable}`. Walks agent → sessions → turns →
   evals (the only path; standalone evals don't exist). `::session-id`
   tags each so the section can interleave a resume marker at session
   boundaries; `::prior?` marks evals from a session other than the
   current one (their `result/<id>` vars died)."
  [db agent cur-sess]
  (vec
    (for [s (sort-by :seon.agent.session/at (:seon.agent/sessions agent))
          t (sort-by :seon.agent.turn/at (:seon.agent.session/turns s))
          e (sort-by :seon.eval/at (:seon.agent.turn/evals t))]
      (let [sid (:seon.agent.session/id s)]
        {::at         (:seon.eval/at e)
         ::kind       :eval
         ::entity     (into {} e)
         ::session-id sid
         ::prior?     (and (some? cur-sess) (not= sid cur-sess))
         :seon.render/ai 'seon.ctx.transcript/eval->renderable}))))

(defn- agent-rec
  "The agent entity (lazy) for `id` against `db`."
  [id db]
  (db/entity-lazy (cond-> {:seon.db/ref [:seon.agent/id id]}
                    db (assoc :seon.db/db db))))

(defn- with-ns-markers
  "Thread a `;; in <ns>` marker into each EVAL event whose ns differs from
   the prior eval's (only evals carry an ns; message events pass through).
   A pure left-to-right pass over the already-time-sorted events."
  [events]
  (first
    (reduce
      (fn [[out prev-ns] ev]
        (if (= :eval (::kind ev))
          (let [ek (:seon.eval/ns (::entity ev))
                marker (when (and (some? ek) (not= prev-ns ek))
                         (str ";; in " (name ek)))]
            [(conj out (assoc ev ::ns-marker marker)) (or ek prev-ns)])
          [(conj out ev) prev-ns]))
      [[] ::none]
      events)))

(defn readline
  "The folded live readline — DERIVED every render, never stored. The
   very-bottom of the transcript: the cursor (`ns=>` current ns) plus this
   turn's status/steering as a `;;;` line (turn · time · loop K/cap ·
   state · any cap-pressure steering) — ONE steering surface. Always
   present. This is the ONLY line in the whole transcript that reads the
   live `now` (below the cache breakpoint — busting here is free).

   Pressure steering escalates toward the per-loop cap — positive-framing:
   it tells the agent what to DO (finish, park, message), never just that
   something is wrong."
  [{:seon.agent/keys [id] db :seon.db/db}]
  (let [db      (or db @db/*conn*)
        a       (agent-rec id db)
        state   (:seon.agent/state a)
        cur-ns  (ctx/current-ns {:seon.agent/id id :seon.db/db db})
        ns-str  (if (keyword? cur-ns) (name cur-ns) (str cur-ns))
        sess    (ctx/current-session id db)
        n-turns (count (:seon.agent.session/turns sess))
        wake    (:seon.agent/wake a)
        loop-k  (if wake
                  (->> (:seon.agent.session/turns sess)
                       (filter #(= wake (:seon.agent.turn/wake %)))
                       count)
                  n-turns)
        cap     (ctx/effective-cap id db)
        ;; localized full date+tz so the agent can judge what's expensive.
        ;; This is the ONE legitimate live `now` in the transcript.
        now     (let [tz (ctx/host-timezone)]
                  (str (try (.toLocaleString (js/Date.) "sv-SE" #js {:timeZone tz})
                            (catch :default _ (.toISOString (js/Date.))))
                       " " tz))
        steer
        (cond
          (>= loop-k (max 1 (- cap 2)))
          (str ";;; loop " loop-k "/" cap " — you are near the per-loop cap. "
               "Wrap up: (complete \"…\") to finish, or (wait \"note\") to "
               "park until the next message.\n")
          (>= loop-k (quot cap 2))
          (str ";;; loop " loop-k "/" cap " — past halfway through this loop. "
               "If you have what you need, (complete \"…\") or message the result.\n")
          :else "")]
    (str steer
         ";;; ── " ns-str " · turn " n-turns " · loop " loop-k "/" cap
         " · " (name (or state :idle)) " · " now " · agent " id " ──\n"
         ns-str "=> ")))

(defn- ordered-events
  "The agent's full flat event stream — messages + evals UNIONed, sorted
   by FIXED stored `:at` (byte-stable), with `;; in <ns>` markers threaded
   into evals where the ns changes. Ties (same `:at`) sort messages before
   evals for stable output. `last-event-at` (the newest event's `:at`)
   lets the caller flag any message that arrived AFTER it as NEW."
  [db agent my-eid own-id cur-sess]
  (let [msgs (or (message-events db my-eid own-id) [])
        evs  (eval-events db agent cur-sess)
        kind-rank {:message 0 :eval 1}
        sorted (sort-by (juxt #(.getTime ^js (::at %))
                              #(kind-rank (::kind %) 9))
                        (concat msgs evs))]
    (with-ns-markers sorted)))

(defn transcript-section
  "The WHOLE bottom of the context: the [[masthead]], then the agent's
   flat TIME-ORDERED EVENT LOG (messages + evals, oldest-first, each
   rendered through the recursive [[seon.render/render]] handle via its
   converter — [[message->renderable]] / [[eval->renderable]]), then the
   folded live [[readline]].

   Turn boundaries are NOT containers — there are no per-turn headers; the
   only structural marker is the [[resume-marker-line]], interleaved ONCE
   at each session boundary (events from a previous process render with
   `::prior?` true, so their evals carry no `result/<id>` handle).

   A message that landed AFTER the newest event (mid-LLM-call) is flagged
   NEW so the agent knows it is acting on it for the first time. The
   inbound gate is [[inbound-msg?]] — the SAME conditions as the wake, so a
   `:core` nudge never shows as a fake inbound.

   NO clipping yet (`:seon.render/clip :none`): the transcript renders ALL
   events; the sliding window lands later. Every past event renders
   byte-identical turn-to-turn (times from FIXED stored `:at`), so the
   prefix caches — only the readline's `now` changes between turns."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.agent/keys [id] db :seon.db/db render-fn :seon.render/render :as input}]
  (let [db       (or db @db/*conn*)
        a        (agent-rec id db)
        my-eid   (:db/id a)
        own-id   id
        cur-sess (:seon.agent.session/id (ctx/current-session id db))
        cur-ns   (ctx/current-ns {:seon.agent/id id :seon.db/db db})
        ns-str   (if (keyword? cur-ns) (name cur-ns) (str cur-ns))
        ;; Render handle: the recursive walker injects `:seon.render/render`
        ;; (ONE section model). When the section is called DIRECTLY (the
        ;; gym driver, the `seon.agent/transcript-section` re-export), there
        ;; is no injected handle — fall back to a local ai render so the
        ;; same code path produces the same String.
        render*  (or render-fn #(render/render :seon.render/ai input %))
        events   (ordered-events db a my-eid own-id cur-sess)
        last-at  (some-> (last events) ::at)
        ;; Flag any INBOUND message that arrived after the newest event as
        ;; NEW (it landed mid-LLM-call, the agent acts on it for the first
        ;; time this turn).
        events*  (mapv (fn [ev]
                         (if (and (= :message (::kind ev))
                                  (not (::outbound? ev))
                                  (some? last-at)
                                  (> (.getTime ^js (::at ev))
                                     (.getTime ^js last-at)))
                           (assoc ev ::new? true)
                           ev))
                       events)
        ;; Render each event, interleaving a resume marker before the first
        ;; event of a session that differs from the prior event's session.
        body
        (->> (reduce
               (fn [[rows prev-sid] ev]
                 (let [sid    (::session-id ev)
                       marker (when (and (= :eval (::kind ev))
                                         (some? prev-sid)
                                         (some? sid)
                                         (not= prev-sid sid))
                                resume-marker-line)
                       text   (render* ev)
                       rows'  (cond-> rows
                                marker (conj marker)
                                true   (conj text))]
                   [rows' (if (= :eval (::kind ev)) sid prev-sid)]))
               [[] nil]
               events*)
             first
             (remove str/blank?)
             (str/join "\n"))
        head (masthead ns-str)
        tail (readline input)]
    (if (str/blank? body)
      (str head "\n\n" tail)
      (str head "\n\n" body "\n\n" tail))))

;; ------------------------------------------------------------
;; HTML twin — the inspector's right-pane transcript card. The same flat
;; event stream, each event rendered through the recursive html handle via
;; its kind's html converter (`seon.handlers.message/render-html` /
;; `seon.handlers.eval/render-html` — resolved by the entity's schema
;; kind), oldest-first.
;; ------------------------------------------------------------

(defn transcript-section-html
  "The HTML TWIN of [[transcript-section]]: the agent's flat time-ordered
   event stream rendered as cards (message bubbles + eval cards),
   oldest-first. Each event's UNDERLYING entity (`:seon.agent.message` /
   `:seon.eval`) is rendered through `seon.render/render-entity-html`,
   which resolves the entity's schema-kind html converter. Returns BARE
   hiccup; an empty transcript renders a friendly placeholder."
  {:malli/schema [:=> [:cat :map] [:maybe :seon.render.live-tile/hiccup]]}
  [{:seon.agent/keys [id] db :seon.db/db :as input}]
  (let [db       (or db @db/*conn*)
        a        (agent-rec id db)
        my-eid   (:db/id a)
        own-id   id
        cur-sess (:seon.agent.session/id (ctx/current-session id db))
        events   (ordered-events db a my-eid own-id cur-sess)
        cards
        (->> events
             (keep
               (fn [ev]
                 (let [entity (case (::kind ev)
                                ;; Message events carry projected fields, not
                                ;; the raw entity — re-pull the message by id
                                ;; so the html converter sees its full shape.
                                :message (when-let [mid (::id ev)]
                                           (db/pull db '[* {:seon.agent.message/from
                                                            [:db/id :seon.user/id :seon.agent/id]
                                                            :seon.agent.message/to
                                                            [:db/id :seon.user/id :seon.agent/id]}]
                                                     [:seon.agent.message/id mid]))
                                :eval    (::entity ev)
                                nil)]
                   (when entity
                     (render/render-entity-html
                       (assoc input :seon.render/node entity :seon.db/db db))))))
             vec)]
    (if (seq cards)
      (into [:div {:class "flex flex-col"}] cards)
      [:div {:class "text-text-500 italic p-2 text-xs font-mono"}
       "no events yet — every message and eval this agent makes appears here live"])))
