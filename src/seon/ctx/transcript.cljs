(ns seon.ctx.transcript
  "The `:transcript` context section + its `:seon.render/html` twin — the
   WHOLE bottom of the agent's context.

   ONE eval'able REPL transcript: `;;` comments + forms + `;;=>`-commented
   results + `;;;` runtime-structure lines + a live `ns=>` readline. No
   XML. Re-evaluating the forms (comments pass through) reproduces the
   agent's state — the context IS a replayable program (the north star).

   This section ABSORBS the old prompt + turns + status sections: the
   masthead opens it, each turn opens with a `;;; ── turn N · … ──`
   header, and the folded live readline at the very bottom carries the
   cursor (current ns) + this turn's status/steering. Symbol-wired into
   `seon.ctx/core-default-ctx` as `'seon.ctx.transcript/transcript-section`
   (+ the html twin `'…/transcript-section-html`).

   Shared eval-row rendering + caps stay in the spine `seon.ctx`
   (`format-eval-row`, `cap-result`, `message-render-cap`,
   `current-session`) — this ns owns the turn-grouping walk, the
   comment-block markup, and the readline."
  (:require
    [clojure.string :as str]
    [seon.ctx :as ctx]
    [seon.db :as db]
    [seon.warn :as warn]))

(def transcript-char-budget
  "Total rendered-chars cap for the transcript section (~6k tokens at
   chars/4) — bounds an otherwise-unbounded transcript that would
   dominate both spend and the model's attention as turns accrue. Keeps
   the newest several dozen typical items (or ~15 worst-case eval rows)
   whole — comfortably more than the 2–4 turns most questions need.
   Retention is NEWEST-FIRST: oldest items drop beyond the budget and an
   elision note replaces them at the top. Override via env
   SEON_TRANSCRIPT_CHAR_BUDGET."
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
         ";;; DB every turn, so it is never stale. You write Clojure forms and ;;\n"
         ";;; comments. After each form the runtime evaluates it and shows the value\n"
         ";;; on the next line as `;;=> …` — that is how your results arrive, on the\n"
         ";;; turn after you write the form. So just write the form; read its `;;=>`\n"
         ";;; next turn. Append below.")))

(def resume-marker-line
  "The session-resume boundary: rendered ONCE per resume, between the
   last turn of a previous process and the first of the next, as a `;;;`
   runtime-structure comment. Everything above it ran in a process that no
   longer exists — its `result/<id>` vars are not dereferenceable
   (re-run a form to recompute a value)."
  ";;; ── session resumed — the turns above ran in a previous process; their result/<id> vars are gone (re-run a form to recompute) ──")

;; ------------------------------------------------------------
;; Inbound gate — the SAME predicate as the wake, so a `:core` substrate
;; nudge never renders as a fake inbound.
;;
;; FALLBACK: the canonical gate is `seon.agent/inbound-msg-datom?`, but
;; `seon.agent` REQUIRES this ns, so requiring it back here is a require
;; CYCLE. Compiling clean beats a cycle, so we keep a small LOCAL
;; predicate over the same conditions.
;; TODO unify with seon.agent/inbound-msg-datom? once the shared gate
;; moves to a cycle-free ns.
;; ------------------------------------------------------------

(defn- inbound-msg?
  "True iff message map `m` (pulled with its `from` ref carrying
   `:seon.user/id`/`:seon.agent/id`) is a WAKING inbound for the agent
   whose eid is `my-eid`: from ≠ me, origin ∈ {:human :agent} (absent
   origin = legacy human/agent, waking), handled? ≠ true, hops < hop-cap.
   Mirrors seon.agent/inbound-msg-datom? + the hop-cap clause — ONE gate
   so a `:core` nudge never renders as a fake inbound (see TODO above)."
  [m my-eid]
  (let [from   (:seon.agent.message/from m)
        origin (:seon.agent.message/origin m)
        hops   (or (:seon.agent.message/hops m) 0)]
    (and (not= my-eid (:db/id from))
         (not= :core origin)
         (not (true? (:seon.agent.message/handled? m)))
         (< hops warn/hop-cap))))

(defn- clock
  "An `HH:mm:ss` wall-clock time in the host (human's) timezone for an
   `#inst` (or now when nil). The short time the `;;;` turn headers + the
   `;;; ◀` inbound lines carry (the readline carries the full localized
   date+tz). sv-SE → ISO-like `HH:mm:ss`."
  [inst]
  (let [d (or inst (js/Date.))]
    (try (.toLocaleTimeString ^js d "sv-SE" #js {:timeZone (ctx/host-timezone)})
         (catch :default _ (subs (.toISOString ^js d) 11 19)))))

;; ------------------------------------------------------------
;; The agent's full message stream, derived once per render — used both
;; for the per-turn inbound head-lines and (eventually) any tail steering.
;; ------------------------------------------------------------

(defn- inbound-messages
  "ALL waking inbound messages to `my-eid`, oldest-first, each a map
   {::at ::content ::from-label}. ONE query, nothing stored; the
   per-turn walk slices this by time-window so each renders exactly once."
  [db my-eid]
  (when my-eid
    (->> (db/query
           {:seon.db/db db
            :seon.db/query
            ;; Wildcard pull (not an explicit attr list): an attr with zero
            ;; datoms — e.g. :seon.agent.message/handled? before any message
            ;; is consumed — is simply ABSENT from the result (pulling it
            ;; explicitly THROWS "not defined in current schema"). The gate
            ;; reads absent handled? as "not handled" — correct.
            '[:find (pull ?m [* {:seon.agent.message/from
                                 [:db/id :seon.user/id :seon.agent/id]}])
              :in $ ?me
              :where
              [?m :seon.agent.message/to ?me]
              [?m :seon.agent.message/at _]]
            :seon.db/args [my-eid]})
         (map first)
         (filter #(inbound-msg? % my-eid))
         (sort-by #(.getTime ^js (:seon.agent.message/at %)))
         (mapv (fn [m]
                 (let [from (:seon.agent.message/from m)]
                   {::at      (:seon.agent.message/at m)
                    ::content (:seon.agent.message/content m)
                    ::from-label (cond
                                   (:seon.user/id from) ":user"
                                   (:seon.agent/id from) (str ":agent/" (:seon.agent/id from))
                                   :else "?")}))))))

(defn- inbound-line
  "ONE inbound message as a `;;; ◀ from X @ time — \"…\"` runtime-structure
   line. `new?` marks a message that arrived mid-LLM-call (it landed
   after the prior turn but before this one closed) — rendered with a
   `(NEW — arrived while you were working)` note so the agent knows it is
   acting on it for the first time. Content bounded by
   [[seon.ctx/message-render-cap]]."
  [{::keys [at content from-label]} new?]
  (let [body (ctx/cap-result content ctx/message-render-cap)]
    (str ";;; ◀ from " from-label " @ " (clock at)
         (when new? " (NEW — arrived while you were working)")
         " — \"" body "\"")))

(defn- turn-header
  "The `;;; ── turn N · <time> · loop K/cap · <ns> ──` opener for ONE
   turn — the runtime-structure channel (`;;;`). `n` is the
   monotonic display turn number (derived position across all sessions),
   `loop-k`/`cap` the sliding-window per-loop count (turns sharing this
   turn's `:seon.agent.turn/wake`), `ns-str` the ns the turn ran in."
  [n at loop-k cap ns-str]
  (let [pad (apply str (repeat (max 3 (- 60 (count ns-str))) "─"))]
    (str ";;; ── turn " n " · " (clock at)
         (when (and loop-k cap) (str " · loop " loop-k "/" cap))
         " · " ns-str " " pad)))

(defn- render-turn
  "Render ONE turn as a comment-block: the `;;; ── turn N · … ──`
   header, then any `;;; ◀` inbound head-lines for messages this turn
   first sees, then each eval rendered REPL-faithful by
   [[seon.ctx/format-eval-row]] (a `;; in <ns>` marker injected only where
   the eval ns changes). PRIOR-SESSION turns (`prior?` true) render their
   evals WITHOUT `result/<id>` handles (the vars died with the process)."
  [{turn ::turn n ::n loop-k ::loop-k cap ::cap inbounds ::inbounds} prior?]
  (let [evals  (->> (:seon.agent.turn/evals turn)
                    (sort-by :seon.eval/at)
                    (mapv #(into {} %)))
        ns-kw  (or (->> evals (filter :seon.eval/ok?) (map :seon.eval/ns) (remove nil?) last)
                   (:seon.eval/ns (first evals)))
        ns-str (if ns-kw (name ns-kw) "?")
        at     (:seon.agent.turn/at turn)
        header (turn-header n at loop-k cap ns-str)
        in-lns (mapv (fn [{new? ::new? :as in}] (inbound-line in new?)) inbounds)
        eval-rows
        (loop [[e & more] evals prev-ns ::none out []]
          (if (nil? e)
            out
            (let [ek       (:seon.eval/ns e)
                  ns-marker (when (and (some? ek) (not= prev-ns ek))
                              (str ";; in " (name ek)))
                  r         (ctx/format-eval-row e prior?)
                  row-text  (if ns-marker (str ns-marker "\n" r) r)]
              (recur more (or ek prev-ns) (conj out row-text)))))]
    (->> (concat [header] in-lns
                 (when (seq eval-rows) [(str/join "\n" eval-rows)]))
         (remove nil?)
         (str/join "\n"))))

(defn- agent-rec
  "The agent entity (lazy) for `id` against `db`."
  [id db]
  (db/entity-lazy (cond-> {:seon.db/ref [:seon.agent/id id]}
                    db (assoc :seon.db/db db))))

(defn- session-turns
  "ALL :seon.agent.turn entities for `agent-id`, oldest-first across ALL
   sessions, each tagged with its owning `:seon.agent.session/id`. Each
   turn's `:seon.agent.turn/evals` ride along as the lazy ref vector.
   Optional `db` snapshot (the composer threads its render db)."
  [agent-id db]
  (let [a (agent-rec agent-id db)]
    (vec
      (for [s (sort-by :seon.agent.session/at (:seon.agent/sessions a))
            t (sort-by :seon.agent.turn/at (:seon.agent.session/turns s))]
        {::turn                            t
         :seon.agent.session/id-of-session (:seon.agent.session/id s)}))))

(defn readline
  "The folded live readline — DERIVED every render, never stored. The
   very-bottom of the transcript: the cursor (`ns=>` current ns) plus this
   turn's status/steering as a `;;;` line (turn · time · loop K/cap ·
   state · any cap-pressure steering) — ONE steering surface. Always
   present.

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
        now     (let [tz (ctx/host-timezone)]
                  (str (try (.toLocaleString (js/Date.) "sv-SE" #js {:timeZone tz})
                            (catch :default _ (.toISOString (js/Date.))))
                       " " tz))
        steer
        (cond
          (>= loop-k (max 1 (- cap 2)))
          (str ";;; loop " loop-k "/" cap " — you are near the per-loop cap. "
               "Wrap up: (complete \"…\") to finish, or (agent/wait \"note\") to "
               "park until the next message.\n")
          (>= loop-k (quot cap 2))
          (str ";;; loop " loop-k "/" cap " — past halfway through this loop. "
               "If you have what you need, (complete \"…\") or message the result.\n")
          :else "")]
    (str steer
         ";;; ── " ns-str " · turn " n-turns " · loop " loop-k "/" cap
         " · " (name state) " · " now " · agent " id " ──\n"
         ns-str "=> ")))

(defn transcript-section
  "The WHOLE bottom of the context: the
   [[masthead]], then the agent's PAST turns oldest-first as comment-blocks
   ([[render-turn]] — `;;; ── turn N · … ──` header, `;;; ◀` inbound
   head-lines, then `;;` comments + forms + `;;=>` results via
   [[seon.ctx/format-eval-row]]), then the folded live [[readline]].

   Every inbound message renders EXACTLY ONCE, at the head of the turn that
   first sees it (by TIME-WINDOW: between the prior turn's `:at` and this
   turn's `:at`); a message that landed during the most-recent turn's
   LLM-call renders at the readline-adjacent end flagged NEW. The gate is
   [[inbound-msg?]] — the SAME conditions as the wake, so a `:core` nudge
   never shows as a fake inbound.

   SESSION RESUME: turns from PREVIOUS sessions render too, separated by
   ONE [[resume-marker-line]] per resume; prior-session evals render
   WITHOUT `result/<id>` handles. Budget eviction is OLDEST-TURN-FIRST;
   the masthead + readline are ALWAYS kept (they orient the agent).
   Per-eval caps SPLIT BY COMPONENT ([[seon.ctx/format-eval-row]]): echoed
   source + stdout at [[seon.ctx/eval-render-cap]]; the citable result
   body at [[seon.ctx/result-body-render-cap]]."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.agent/keys [id] db :seon.db/db :as input}]
  (let [db       (or db @db/*conn*)
        cur-sess (:seon.agent.session/id (ctx/current-session id db))
        a        (agent-rec id db)
        my-eid   (:db/id a)
        wake     (:seon.agent/wake a)
        cap      (ctx/effective-cap id db)
        cur-ns   (ctx/current-ns {:seon.agent/id id :seon.db/db db})
        ns-str   (if (keyword? cur-ns) (name cur-ns) (str cur-ns))
        turns    (session-turns id db)
        inbounds (or (inbound-messages db my-eid) [])
        head     (masthead ns-str)
        ;; Newest turn's :at — anything inbound AFTER it is NEW (arrived
        ;; mid-LLM-call, not yet acted on); it rides the LAST turn's head
        ;; flagged NEW (the turn the agent can first act on it in).
        last-at  (some-> (peek turns) ::turn :seon.agent.turn/at)
        ;; Assign each inbound to the FIRST turn whose :at ≥ the inbound's
        ;; :at (the turn that first sees it). Inbounds after the last turn
        ;; ride the last turn flagged NEW. ONE pass, oldest-first.
        turn-ats (mapv #(.getTime ^js (:seon.agent.turn/at (::turn %))) turns)
        inbound-by-turn
        (reduce
          (fn [acc in]
            (let [t (.getTime ^js (::at in))
                  idx (or (some (fn [i] (when (>= (turn-ats i) t) i))
                                (range (count turns)))
                          (when (seq turns) (dec (count turns))))]
              (if idx
                (update acc idx (fnil conj [])
                        (assoc in ::new? (and (some? last-at)
                                              (> t (.getTime ^js last-at)))))
                acc)))
          {} inbounds)
        ;; Build rendered rows oldest-first; a resume marker rides as its
        ;; own row just before a turn whose session differs from the prior.
        turn-items
        (loop [i 0 [t & more] turns prev-sess ::none out []]
          (if (nil? t)
            out
            (let [sess   (:seon.agent.session/id-of-session t)
                  prior? (and (some? cur-sess) (not= sess cur-sess))
                  loop-k (when (and wake (= wake (:seon.agent.turn/wake (::turn t))))
                           ;; position among this-wake turns up to & incl i
                           (inc (count (filter #(= wake (:seon.agent.turn/wake (::turn %)))
                                               (take i turns)))))
                  enriched (assoc t ::n (inc i) ::loop-k loop-k ::cap (when loop-k cap)
                                    ::inbounds (get inbound-by-turn i []))
                  marker (when (and (not= prev-sess ::none) (not= prev-sess sess))
                           {:seon.ctx/kind :seon.ctx/marker
                            :seon.render/text resume-marker-line})
                  row    {:seon.ctx/kind :seon.ctx/turn
                          :seon.render/text (render-turn enriched prior?)}]
              (recur (inc i) more sess (into out (if marker [marker row] [row]))))))
        tail (readline input)]
    (if (seq turn-items)
      (let [rendered (mapv :seon.render/text turn-items)
            exempt?  (mapv #(not= :seon.ctx/turn (:seon.ctx/kind %)) turn-items)
            kept-chars (transduce
                         (keep-indexed
                           (fn [i s] (when (exempt? i) (+ (count s) 2))))
                         + 0 rendered)
            ;; NEWEST-FIRST retention over TURN blocks; markers always kept.
            kept-turn (loop [i (dec (count rendered)) acc kept-chars kept #{}]
                        (if (neg? i)
                          kept
                          (if (exempt? i)
                            (recur (dec i) acc kept)
                            (let [acc' (+ acc (count (rendered i)) 2)]
                              (if (and (seq kept) (> acc' transcript-char-budget))
                                kept
                                (recur (dec i) acc' (conj kept i)))))))
            kept-idx (filterv #(or (exempt? %) (kept-turn %)) (range (count rendered)))
            elided   (- (count rendered) (count kept-idx))
            kept     (mapv rendered kept-idx)]
        (str head "\n\n"
             (when (pos? elided)
               (str ";;; … " elided " older turn" (when (not= 1 elided) "s")
                    " elided (transcript capped at " transcript-char-budget
                    " chars; the full log is in the db — "
                    "(seon.agent/messages) / (seon.agent/evals))\n\n"))
             (str/join "\n\n" kept)
             "\n\n" tail))
      ;; No turns yet (a fresh agent's first wake). Any pending inbound has
      ;; NO turn to attach to — without surfacing it here the agent sees an
      ;; empty REPL and onboards/greets instead of working the task already
      ;; in its inbox. Render every pending inbound as a `;;; ◀` line flagged
      ;; NEW (by definition unseen — no turn has acted on it) between the
      ;; masthead and the readline, so the task sits right above the cursor.
      ;; Idle case preserved: no pending inbound → no `◀` lines → masthead +
      ;; readline exactly as before, so a fresh agent with nothing pending
      ;; still greets its human.
      (let [in-lns (mapv #(inbound-line % true) inbounds)]
        (if (seq in-lns)
          (str head "\n\n" (str/join "\n" in-lns) "\n\n" tail)
          (str head "\n\n" tail))))))

(defn- turn-card-hiccup
  "ONE turn rendered as a card: the `turn N` header, then the turn's
   evals as the comment-block text
   [[render-turn]] produces, in a `[:pre]`. `prior?` strips result/<id>
   handles for prior-session turns."
  [{turn ::turn :as enriched} prior? n]
  (let [tid   (:seon.agent.turn/id turn)
        evals (:seon.agent.turn/evals turn)
        body  (render-turn (assoc enriched ::n n) prior?)]
    [:div {:class "border-l-2 border-amber-700/40 pl-2 py-1 mb-1"}
     [:div {:class "text-xs font-mono text-text-400"}
      [:span {:class "font-semibold text-text-300"} (str "turn " n)]
      [:span {:class "text-text-500"} (str "  id=" tid
                                           "  evals=" (count evals))]]
     [:pre {:class (str "mt-0.5 text-xs font-mono whitespace-pre-wrap "
                        "break-all text-text-100")}
      body]]))

(defn transcript-section-html
  "The HTML TWIN of [[transcript-section]]: the agent's OWN turns/evals
   rendered as cards, oldest-first
   (the same [[session-turns]] walk the text twin uses — structurally
   agent-scoped). Returns the standard `:seon.render/html-response` map; an
   empty transcript renders a friendly placeholder."
  {:malli/schema [:=> [:cat :map] :seon.render/html-response]}
  [{:seon.agent/keys [id] db :seon.db/db}]
  (let [db       (or db @db/*conn*)
        cur-sess (:seon.agent.session/id (ctx/current-session id db))
        turns    (session-turns id db)
        cards
        (->> turns
             (map-indexed
               (fn [i t]
                 (let [sess   (:seon.agent.session/id-of-session t)
                       prior? (and (some? cur-sess) (not= sess cur-sess))]
                   (turn-card-hiccup (assoc t ::inbounds []) prior? (inc i)))))
             vec)]
    {:seon.render/hiccup
     (if (seq cards)
       (into [:div {:class "flex flex-col"}] cards)
       [:div {:class "text-text-500 italic p-2 text-xs font-mono"}
        "no turns yet — every turn this agent takes appears here live"])}))
