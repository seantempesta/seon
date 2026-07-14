(ns seon.agent.ctx.transcript
  "The `:transcript` context section + its `:seon.render/html` twin — the
   WHOLE bottom of the agent's context.

   ONE flat, time-ordered EVENT LOG. The transcript is a chronological
   stream of EVENTS — inbound/outbound messages and evals — each carrying
   its own stored time (`:seon.agent.message/at` / `:seon.eval/at`), sorted
   by that instant, rendered through the SAME recursive `seon.render/render`
   handle every other section uses (ONE section model). Turn boundaries are
   NOT containers — there are no per-turn headers or synthetic process
   boundary rows.

   It reads as a REPL transcript: `;` comments + forms + BARE
   `⟹ <value> ⟸ result/<id>` runtime result lines (NOT comment-shaped, so a
   model can't fabricate one) + `;;;`-bracketed sections / `;;; ◀`/`;;; ▶`
   message lines + a live `ns=>` readline. Settled tradeoff
   (transcript-render redesign): the transcript is no longer re-evaluable
   Clojure — clarity + anti-fabrication win, because the old `; ⟹` shape was
   copied by agents into fabricated results.

   The masthead opens it, the events stream in time order, and the folded
   live readline at the very bottom carries the cursor (current ns) + this
   turn's status/steering. Symbol-wired into `config manifest` as
   `'seon.agent.ctx.transcript/transcript-block` (+ the html twin
   `'…/transcript-block-html`).

   BYTE-STABILITY: every past event renders byte-identical turn-to-turn —
   each time comes from the event's FIXED stored `:at` (never `now`), and
   the event list is `sort-by`'d before joining. The ONLY moving byte is
   the single current-time line in the readline at the very bottom (below
   the cache breakpoint — busting there is free). No render fn here calls
   `now` to produce displayed text except that one readline line.

   The eval-row converter delegates to `seon.agent.ctx/format-eval-row` (which
   carries the fabrication-guard + the component caps); the message
   converter renders the REPL-comment `;;; ◀ from X` / `;;; ▶ to X` line."
  (:require
    [clojure.string :as str]
    [seon.agent.message :as msg]
    [seon.agent.ctx :as ctx]
    [seon.ai.tokens :as tokens]
    [seon.db :as db]
    [seon.derive :as derive]
    [seon.eval :as seval]
    [seon.handlers.eval :as eval-handler]
    [seon.render :as render]
    [seon.schema :as schema]))

;; ============================================================
;; Config-driven agent-init CP-1 — transcript block config attrs. Tiers
;; and decay levels carry a VALUE per element → REIFIED component entities
;; (decision 22b), NOT `[:vector [:map …]]` blobs. Each tier/level becomes
;; its own entity, `:db/isComponent`-ref'd off the transcript block
;; (cascade-delete), queryable per-element. Nothing reads these yet
;; (purely additive).
;; ============================================================

;; The reified per-element leaf value shapes (register-once, decision 5).
;; MUST precede the ::tiers / ::result-decay refs (leaf-rule).
(schema/register! ::from-turn        :int)
(schema/register! ::to-turn          :int)
(schema/register! ::token-cap        [:int {:min 0}])
(schema/register! ::from-turn-offset [:int {:min 0}])

(schema/register! ::tier             ; ONE eviction tier entity
  [:map
   [::from-turn                :int]
   [::to-turn   {:optional true} :int]
   [::token-cap [:int {:min 0}]]])
(schema/register! ::decay-level      ; ONE eval-result decay level entity
  [:map
   [::from-turn-offset [:int {:min 0}]]
   [::token-cap        [:int {:min 0}]]])

;; Component refs off the transcript block → each tier/level is its own
;; entity, queryable per-element, cascade-deleted with the block.
(schema/register! ::tiers        [:vector {:seon.db/component true :default []} :seon.db/ref]) ; of ::tier entities
;; CP-5 (owner intent — evals "start larger and shrink over time"): the v1
;; default is the REAL 3-level decay schedule — near-full THIS turn + next
;; (offset 0→16384), partial at offset 2 (→1500), clipped to a stub at offset 5
;; (→200, keeping the `result/<id>` handle). This is the safety net that lets
;; blocks render FULL (escape-clipping) without unbounded transcript growth: an
;; old eval body shrinks as it ages out of the working set.
(schema/register! ::result-decay [:vector {:seon.db/component true
                                           :default [{::from-turn-offset 0 ::token-cap 16384}
                                                     {::from-turn-offset 2 ::token-cap 1500}
                                                     {::from-turn-offset 5 ::token-cap 200}]}
                                  :seon.db/ref]) ; of ::decay-level entities

;; The transcript window (turns kept verbatim before eviction into summaries).
(schema/register! ::turns-retained [:int {:default 8 :min 0}])

;; The folded live readline at the very bottom — the ONE line of the block
;; that reads the live `now`. Default true (byte-parity). `false` drops it:
;; the `seon.repl.autocomplete` projection profile renders the transcript
;; as a byte-exact function of the db VALUE alone (as-of replay), so the
;; one moving line is switched off there.
(schema/register! ::readline? [:boolean {:default true}])

;; RUNTIME-CACHE bytes — the `⟸ result/<id>` handles depend on exact
;; membership in the bounded result runtime
;; (`seval/result-live?`): the same db value renders different bytes after
;; eviction or a pod restart. Default true (byte-parity). `false` renders
;; every eval in the runtime-INDEPENDENT form (no handles), so an as-of export
;; reproduces inference bytes regardless of process/cache state — the
;; `seon.repl.autocomplete` profile sets it off.
(schema/register! ::result-handles? [:boolean {:default true}])

;; ============================================================
;; Config-driven agent-init CP-3 — reactive config-on-record reads.
;; The transcript block's `::result-decay` (move 4) and `::tiers` (move 5)
;; datoms drive the per-result cap and the eviction clip AT RENDER TIME.
;; v1 defaults reproduce today (single-level decay = 16384; empty tiers =
;; `:none`, render-all) → byte-parity holds.
;; ============================================================

(defn block-ent
  "The agent's `:seon.agent.ctx/name` `nm` context-BLOCK entity map from db
   value `db` (its config datoms — e.g. the transcript block's
   `::result-decay` / `::tiers`), or nil when the agent has no such block.
   Reactive config-on-record: the renderer reads its own config off this
   entity every render, never a const."
  [db agent-eid nm]
  (some (fn [b] (when (= nm (:seon.agent.ctx/name b)) b))
        (:seon.agent/ctx (db/entity {:seon.db/db db :seon.db/ref agent-eid}))))

(defn decay-cap-for-offset
  "The eval-result render token-cap for an eval at turn-`offset` (current-turn
   − the eval's turn), selected from the transcript block's `::result-decay`
   LEVELS (each `{::from-turn-offset ::token-cap}`): the level whose
   `::from-turn-offset` is the LARGEST ≤ `offset` wins; its `::token-cap` is
   the cap. Empty/absent levels → `default-cap` (the v1 default is the SINGLE
   level 0→16384, so every offset selects 16384 = byte-identical to today).
   A negative/nil offset is treated as 0."
  {:malli/schema [:=> [:catn [::levels [:sequential :map]] [::offset [:maybe :int]]
                       [::default-cap :int]] :int]}
  [levels offset default-cap]
  (let [off (max 0 (or offset 0))
        hit (->> levels
                 (filter #(<= (::from-turn-offset %) off))
                 (sort-by ::from-turn-offset)
                 last)]
    (or (::token-cap hit) default-cap)))

(defn tier-cap-for-turn
  "The token-cap a `::tier` schedule assigns an eval at `turn-offset` (current
   turn − the eval's turn), or nil when NO tier covers it (→ evict). Each tier
   is `{::from-turn ::to-turn? ::token-cap}` over an OFFSET range (`::from-turn`
   inclusive, `::to-turn` inclusive when present, else open-ended); the tier
   whose range contains `offset` wins (the SMALLEST `::from-turn` that still
   covers it — tiers are age-bands, oldest last). nil when none covers it."
  {:malli/schema [:=> [:catn [::tiers [:sequential :map]] [::offset :int]]
                  [:maybe :int]]}
  [tiers offset]
  (some (fn [{:keys [] :as tier}]
          (let [from (::from-turn tier)
                to   (::to-turn tier)]
            (when (and (>= offset from) (or (nil? to) (<= offset to)))
              (::token-cap tier))))
        (sort-by ::from-turn tiers)))

(defn clip-events-by-tiers
  "Age-band the ordered `events` by the transcript block's `::tiers` +
   `::turns-retained` window (config-on-record, read per render). EMPTY tiers
   (the v1 default) → render ALL events (today's `:none`, byte-parity — the
   window is tier-DRIVEN, nothing to evict without a tier). NON-EMPTY tiers
   activate the window (#62):

     - the last `retained` TURNS of events render verbatim (the volatile
       working set);
     - OLDER eval events are kept only while their tier (by turn-offset =
       max-turn − the eval's `::turn-idx`) still has token budget — each tier's
       `::token-cap` is a running budget spent NEWEST-first; an eval past its
       tier's budget (or covered by NO tier) is EVICTED;
     - MESSAGE events always render (the human conversation is never evicted).

   Byte-STABLE within a band: an eval's fate changes only when it crosses a
   turn-offset boundary, not every turn (the #62 age-band discipline). Reactive:
   change `::tiers`/`::turns-retained` and the next render re-bands, no apply."
  {:malli/schema [:=> [:catn [::tiers [:sequential :map]]
                             [::retained :int]
                             [::events [:sequential :map]]]
                  [:sequential :map]]}
  [tiers retained events]
  (if (empty? tiers)
    events
    (let [max-turn (transduce (keep ::turn-idx) max -1 events)]
      (if (neg? max-turn)
        events
        (let [budgets (volatile! {})]        ; tier from-turn → remaining tokens
          (->> events
               ;; NEWEST-first so each tier spends its budget on recent evals.
               reverse
               (reduce
                 (fn [kept ev]
                   (cond
                     ;; messages + non-eval events: always keep
                     (not= :eval (::kind ev)) (conj kept ev)
                     :else
                     (let [offset (- max-turn (or (::turn-idx ev) max-turn))]
                       (if (< offset retained)
                         ;; inside the retained window — verbatim
                         (conj kept ev)
                         ;; older — spend the covering tier's budget
                         (if-let [cap (tier-cap-for-turn tiers offset)]
                           (let [tier-key (->> tiers
                                               (filter #(and (>= offset (::from-turn %))
                                                             (or (nil? (::to-turn %))
                                                                 (<= offset (::to-turn %)))))
                                               (sort-by ::from-turn) first ::from-turn)
                                 spent    (get @budgets tier-key 0)
                                 cost     (tokens/estimate
                                            (str (:seon.eval/result-edn (::entity ev))))]
                             (if (<= (+ spent cost) cap)
                               (do (vswap! budgets assoc tier-key (+ spent cost))
                                   (conj kept ev))
                               kept))                    ; over budget → evict
                           kept))))) ; no covering tier → evict
                 [])
               ;; reduce built it newest-first; restore oldest-first order
               reverse
               vec))))))

;; ------------------------------------------------------------
;; Masthead — the transcript's in-band opener, rendered every turn as the
;; FIRST lines of the block. Block-specific cues ONLY (the surface label,
;; the oldest-first ordering, append-below); the live-REPL-session framing
;; lives ONCE in `seon.agent.ctx/system-text` (no re-teaching here). Never a
;; `don't write ⟹` prohibition — a negative example primes the mimicry it
;; forbids (standing owner rule). The ns slot rides the masthead so the
;; agent sees its own session name.
;; ------------------------------------------------------------

(defn mode-fragment
  "The COLOCATED one-line REPL-mode teaching for `mode` (`:batch` | `:stream`).

   Colocation principle: the instruction that describes the mode's behavior
   lives WITH the transcript block that renders under it, gated by the live
   `:seon.config/repl-mode` datom — so exactly ONE mode's text renders and
   the other mode's is ABSENT (never contradicted). `:batch` teaches that a
   typed result is stripped and the real value arrives interleaved next
   turn; `:stream` teaches that the turn ends at the first complete form and
   its value is already in the transcript on continuation (making the
   aborted-at-form boundary legible, not a silent cutoff). No reserved glyph
   is shown (a negative example primes the mimicry it forbids)."
  {:malli/schema [:=> [:catn [::mode :seon.config/repl-mode]] :string]}
  [mode]
  (case mode
    :stream
    (str "; Your turn ends at your first complete form — the runtime evaluates it and its\n"
         "; real value is already in the log when you continue. Write one form and stop.\n"
         "; A reply with NO form does nothing and a few in a row end your run — to say\n"
         "; something, (message/user \"…\"); to deliver a final answer, (complete \"…\").")
    ;; :batch (default)
    (str "; Write the forms you want run; never write out a result yourself — a result you\n"
         "; type is stripped, and the real value arrives interleaved on your next turn.")))

(defn masthead
  "The transcript's in-band opener for namespace label `ns-str`, rendered
   every turn as the block's first lines. Block-specific cues only — the
   surface label, the flat time-ordered event log (messages + evals,
   oldest-first, append-below), and the COLOCATED [[mode-fragment]] for the
   live `mode` (`:batch` | `:stream`); the live-REPL-session framing lives
   once in [[seon.agent.ctx/system-text]]."
  {:malli/schema [:=> [:catn [::ns-str :string] [::mode :seon.config/repl-mode]] :string]}
  [ns-str mode]
  (str "; seon · " ns-str " · live REPL\n"
       "; The flat, time-ordered log below is this REPL's history — your\n"
       "; messages and evals interleaved, oldest-first. Append below.\n"
       (mode-fragment mode)))

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
   stored `#inst` `inst`. The short time the `;;;` message lines carry (the
   readline carries the full localized date+tz). sv-SE → ISO-like
   `HH:mm:ss`.

   FAILS LOUD when `inst` is nil/not a Date: every transcript-body time
   MUST come from a fixed stored `:at` so the rendered prefix stays
   byte-stable across turns. A nil here means a caller lost an event's
   stored time — surface it, never paper over it with a live clock."
  {:malli/schema [:=> [:catn [::inst :any]] :string]}
  [inst]
  (when-not (instance? js/Date inst)
    (throw (ex-info (str "seon.agent.ctx.transcript/clock: missing stored time — "
                         "every transcript event time must derive from its "
                         "fixed :at (byte-stability); got " (pr-str inst))
                    {:seon.agent.ctx.transcript/inst inst})))
  (try (.toLocaleTimeString ^js inst "sv-SE" #js {:timeZone (ctx/host-timezone)})
       (catch :default _ (subs (.toISOString ^js inst) 11 19))))

;; ------------------------------------------------------------
;; Inbound gate — the SAME rule as the wake. The shared boolean lives in
;; `seon.agent.message` ([[seon.agent.message/waking-inbound?]] +
;; [[seon.agent.message/hop-live?]]) — ONE source of truth, no hand-copy.
;; (`seon.agent.message` does not require this ns, so there is no cycle;
;; the OLD copy existed only to dodge `seon.agent`, which DOES require us.)
;; The transcript drops dead-chain (hop-exhausted) messages too, so it
;; composes both clauses.
;; ------------------------------------------------------------

(defn- inbound-msg?
  "True iff message map `m` (pulled with its `from` ref carrying
   `:seon.user/id`/`:seon.agent/id`) is a WAKING, hop-live inbound for the
   agent whose eid is `my-eid`. Delegates to the shared wake rule so a
   `:core` nudge never renders as a fake inbound and a dead-chain message
   never renders as a live one."
  [m my-eid]
  (and (msg/waking-inbound? m my-eid)
       (msg/hop-live? m)))

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
   it. Content bounded by [[seon.agent.ctx/message-render-cap]]. `new?` marks an
   UNANSWERED inbound — one that arrived after the agent's last action (a
   fresh wake or a mid-call arrival) so the agent re-orients to it."
  {:malli/schema [:=> [:cat :seon.render/section-request] :string]}
  [{node :seon.render/node}]
  (let [{::keys [at from-label to-labels content id new? outbound? escape?]} node
        ;; CP-5 escape-clipping (#43, owner: "render the blocks in full"):
        ;; when the agent's `:seon.agent.ctx/escape-clipping?` is on (default
        ;; true), message content renders WHOLE past the per-message cap.
        ;; The flag rides the event (threaded off the RENDER db by
        ;; [[transcript-block]] — byte-exact); a direct call with no
        ;; threaded flag falls back to the live read.
        body (ctx/cap-result content ctx/message-render-cap
                             (if (some? escape?) escape? (ctx/escape-clipping?)))
        who  (if outbound?
               (str "▶ to " (str/join ", " to-labels))
               (str "◀ from " from-label))]
    (str ";;; " who " @ " (clock at)
         (when id (str " [" id "]"))
         (when new? " (NEW — unanswered; respond to this)")
         " — \"" body "\"")))

(defn eval->renderable
  "The `:seon.render/ai` converter for a transcript EVAL event — the
   canonical eval row. Delegates to [[seon.agent.ctx/format-eval-row]], which
   carries the component caps ([[seon.agent.ctx/eval-render-cap]] /
   [[seon.agent.ctx/result-body-render-cap]]) forward. A `::ns-marker?` true
   event prepends a `; in <ns>` line (emitted only where the eval ns
   changes from the prior eval). Only an exact bounded-runtime member
   (`::result-live?` true) renders a `result/<id>` handle; evicted and
   prior-process values remain visible without a dead handle."
  {:malli/schema [:=> [:cat :seon.render/section-request] :string]}
  [{node :seon.render/node}]
  (let [{::keys [entity result-live? ns-marker]} node
        row (ctx/format-eval-row entity (not (true? result-live?)))]
    (if ns-marker
      (str ns-marker "\n" row)
      row)))

;; ------------------------------------------------------------
;; Wall coalescing — a thrash burst never floods the transcript. TWO pure
;; render derivations over the already-ordered event stream (drop the cause
;; and they vanish — reactive-context):
;;
;;   1. CONTENT-FREE noise (empty / closing-delimiter-only source — a
;;      mis-split trailing `}`/`]` that parsed to NOTHING) is dropped
;;      outright: it is a segmentation artifact, never agent intent, and
;;      carries zero learning. (The durable complement is Core dropping
;;      these segments before they record — until then the display stays
;;      robust to whatever the log already holds.)
;;   2. A run of ≥[[coalesce-min-run]] CONSECUTIVE same-signature ERROR
;;      evals collapses into ONE honest line — `✗ 10× Unmatched delimiter` —
;;      expandable to the individual evals in the html twin, a single `;`
;;      summary comment in the (flat, eval'able) :ai twin.
;; ------------------------------------------------------------

(def coalesce-min-run
  "Fewest consecutive same-signature error evals that collapse into one
   coalesced summary; below this each error renders on its own."
  3)

(defn- noise-eval?
  "True iff an eval EVENT is a CONTENT-FREE segment — a source that is empty
   or only closing delimiters / whitespace (`}`, `]`, `)}`), AND either it
   FAILED to read (`ok? false` — a mis-split trailing delimiter that parsed
   to nothing; its narration is the model's mis-attributed `=>` prose, not
   real intent) OR it has no narration (a blank no-op line). The transcript
   never renders these. A comment-only row — blank source but real `;`
   narration the agent typed AND no read error — is NOT noise."
  [ev]
  (and (= :eval (::kind ev))
       (let [e (::entity ev)
             s (str/trim (str (:seon.eval/source e)))]
         (and (or (str/blank? s) (boolean (re-matches #"[)\]}]+" s)))
              (or (false? (:seon.eval/ok? e))
                  (str/blank? (str (:seon.eval/narration e))))))))

(defn- error-signature
  "A normalized error CLASS for a FAILED eval event (nil for ok / non-error
   / noise events). Strips position + the specific offending token so
   `Unmatched delimiter: }` and `Unmatched delimiter: ]` share one class —
   a run of one class is 'the same wall'."
  [ev]
  (when (and (= :eval (::kind ev)) (not (noise-eval? ev)))
    (let [e (::entity ev)]
      (when (false? (:seon.eval/ok? e))
        (-> (or (first (str/split-lines (str (:seon.eval/error e)))) "")
            (str/replace #"\[line[^\]]*\]" "")
            (str/replace #"\s+at line.*$" "")
            (str/replace #":\s*[`(\[{)\]}].*$" "")
            (str/replace #"`[^`]*`" "`…`")
            str/trim)))))

(defn coalesced->renderable
  "The `:seon.render/ai` converter for a COALESCED error run: ONE bare
   `⟹ ✗ N× …` runtime summary line standing in for N identical consecutive
   failures, so a thrash burst never floods the agent's own context (every
   collapsed form DEFINED NOTHING)."
  {:malli/schema [:=> [:cat :seon.render/section-request] :string]}
  [{node :seon.render/node}]
  (let [{::keys [signature count]} node]
    (str ctx/result-marker " ✗ " count "× " signature
         " — " count " consecutive failures collapsed; each DEFINED NOTHING. "
         "Fix the form once, not " count " times.")))

(defn- coalesce-events
  "Pure pass over the ordered event stream: drop content-free [[noise-eval?]]
   events, then collapse maximal runs of ≥[[coalesce-min-run]] CONSECUTIVE
   same-signature error events into one `::coalesced` event carrying the run's
   members. Real forms / ok evals / messages break a run."
  [events]
  (->> events
       (remove noise-eval?)
       (partition-by error-signature)
       (mapcat
         (fn [grp]
           (let [sig (error-signature (first grp))]
             (if (and sig (>= (count grp) coalesce-min-run))
               [{::kind          :coalesced
                 ::at            (::at (first grp))
                 ::signature     sig
                 ::count         (count grp)
                 ::members       (vec grp)
                 :seon.render/ai 'seon.agent.ctx.transcript/coalesced->renderable}]
               grp))))
       vec))

;; ------------------------------------------------------------
;; The agent's full event stream, derived once per render: messages +
;; evals, flattened, each carrying its FIXED stored time, sorted by it.
;; ------------------------------------------------------------

(defn- message->event
  "Convert one pulled message row into a transcript event, or nil."
  [my-eid own-id m]
  (let [from      (:seon.agent.message/from m)
        outbound? (= my-eid (:db/id from))]
    (when (or outbound? (inbound-msg? m my-eid))
      {::at         (:seon.agent.message/at m)
       ::kind       :message
       ::id         (:seon.agent.message/id m)
       ::outbound?  outbound?
       ::content    (:seon.agent.message/content m)
       ::from-label (ctx/message-label from own-id)
       ::to-labels  (->> (:seon.agent.message/to m)
                         (map #(ctx/message-label % own-id))
                         distinct vec)
       :seon.render/ai
       'seon.agent.ctx.transcript/message->renderable})))

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
            ;; Wildcard pull: an attr with zero datoms (e.g. origin on a
            ;; legacy row) is simply ABSENT (pulling it explicitly THROWS).
            ;; The gate treats absent origin as waking.
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
         (keep #(message->event my-eid own-id %)))))

(defn- eval->event
  "Convert one eval row into a transcript event at its optional turn index."
  [turn-idx e]
  (cond->
    {::at           (:seon.eval/at e)
     ::kind         :eval
     ::entity       (into {} e)
     ::result-live? (seval/result-live? (:seon.eval/id e))
     :seon.render/ai 'seon.agent.ctx.transcript/eval->renderable}
    (some? turn-idx) (assoc ::turn-idx turn-idx)))

(defn- eval-events
  "ALL of the agent's evals as transcript events across ALL its turns,
   oldest-first, each `{::at ::kind :eval ::entity ::result-live? ::turn-idx
   :seon.render/ai 'eval->renderable}`. Walks agent → runs → turns → evals
   (via [[seon.agent.ctx/agent-turns]]). `::result-live?` is derived from
   exact membership in the one bounded runtime cache, so an evicted result
   loses its handle even when its run was opened by this process, while a live
   member keeps its handle regardless of run. `::turn-idx` is the
   0-based enumeration index of the eval's TURN (oldest turn = 0), the handle
   the `::turns-retained` window + `::tiers` age-banding key on."
  [db id]
  (let [turns (ctx/agent-turns id db)]
    (vec
      (for [[ti t] (map-indexed vector turns)
            e      (sort-by :seon.eval/at (:seon.agent.turn/evals t))]
        (eval->event ti e)))))

(defn- agent-rec
  "The agent entity (lazy) for `id` against `db`."
  [id db]
  (db/entity-lazy (cond-> {:seon.db/ref [:seon.agent/id id]}
                    db (assoc :seon.db/db db))))

(defn- with-ns-markers
  "Thread a `; in <ns>` marker into each EVAL event whose ns differs from
   the prior eval's (only evals carry an ns; message events pass through).
   A pure left-to-right pass over the already-time-sorted events."
  [events]
  (first
    (reduce
      (fn [[out prev-ns] ev]
        (if (= :eval (::kind ev))
          (let [ek (:seon.eval/ns (::entity ev))
                marker (when (and (some? ek) (not= prev-ns ek))
                         (str "; in " (name ek)))]
            [(conj out (assoc ev ::ns-marker marker)) (or ek prev-ns)])
          [(conj out ev) prev-ns]))
      [[] ::none]
      events)))

(defn readline
  "The folded live readline — DERIVED every render, never stored. The
   very-bottom of the transcript: the cursor (`ns=>` current ns) plus this
   turn's status/steering as a `;` line (turn · time · loop K/cap ·
   state · any cap-pressure steering) — ONE steering surface. Always
   present. This is the ONLY line in the whole transcript that reads the
   live `now` (below the cache breakpoint — busting here is free).

   Pressure steering escalates toward the per-loop cap — positive-framing:
   it tells the agent what to DO (finish, or message the result), never
   just that something is wrong."
  {:malli/schema [:=> [:catn [::input :map]] :string]}
  [{:seon.agent/keys [id] db :seon.db/db}]
  (let [db      (or db @db/*conn*)
        state   (derive/derive-state db id)
        cur-ns  (ctx/current-ns {:seon.agent/id id :seon.db/db db})
        ns-str  (if (keyword? cur-ns) (name cur-ns) (str cur-ns))
        turns   (ctx/agent-turns id db)
        n-turns (count turns)
        run     (derive/current-run db id)
        run-eid (:db/id run)
        ;; loop-k = work spent in the CURRENT open run, in the SAME
        ;; denomination the loop's bound checks (mode-denominated, repl-milestone rung-0
        ;; verdict 2026-07-10): `:batch` counts the run's turns, `:stream`
        ;; counts its FORMS (evals) — one form per stream turn, so prose
        ;; turns don't move the meter. 0 when idle. cap = the run's
        ;; bumpable turn-limit (renew! grows it), else the default when idle.
        run-turns (when run-eid
                    (filter #(= run-eid (:db/id (:seon.agent.turn/run %)))
                            turns))
        loop-k  (cond
                  (nil? run-eid) 0
                  (= :stream (ctx/repl-mode db))
                  (reduce + 0 (map (comp count :seon.agent.turn/evals)
                                   (remove :seon.agent.turn/scheduled? run-turns)))
                  :else (count run-turns))
        cap     (or (:seon.agent.run/turn-limit run) ctx/default-turn-limit)
        ;; localized full date+tz so the agent can judge what's expensive.
        ;; This is the ONE legitimate live `now` in the transcript.
        now     (let [tz (ctx/host-timezone)]
                  (str (try (.toLocaleString (js/Date.) "sv-SE" #js {:timeZone tz})
                            (catch :default _ (.toISOString (js/Date.))))
                       " " tz))
        steer
        (cond
          (>= loop-k (max 1 (- cap 2)))
          (str "; loop " loop-k "/" cap " — you are near the per-loop cap. "
               "Wrap up: (complete \"…\") with what you have, or message the "
               "result to your human.\n")
          (>= loop-k (quot cap 2))
          (str "; loop " loop-k "/" cap " — past halfway through this loop. "
               "If you have what you need, (complete \"…\") or message the result.\n")
          :else "")]
    (str steer
         "; " ns-str " · turn " n-turns " · loop " loop-k "/" cap
         " · " (name (or state :idle)) " · " now " · agent " id "\n"
         ns-str "=> ")))

(defn- ordered-events
  "The agent's full flat event stream — messages + evals UNIONed, sorted
   by FIXED stored `:at` (byte-stable), with `; in <ns>` markers threaded
   into evals where the ns changes. Ties (same `:at`) sort messages before
   evals for stable output. The caller flags any inbound newer than the
   agent's last own action as NEW (unanswered)."
  [db own-id my-eid]
  (let [msgs (or (message-events db my-eid own-id) [])
        evs  (eval-events db own-id)
        kind-rank {:message 0 :eval 1}
        sorted (sort-by (juxt #(.getTime ^js (::at %))
                              #(kind-rank (::kind %) 9))
                        (concat msgs evs))]
    (with-ns-markers sorted)))

(defn transcript-block
  "The WHOLE bottom of the context: the [[masthead]], then the agent's
   flat TIME-ORDERED EVENT LOG (messages + evals, oldest-first, each
   rendered through the recursive [[seon.render/render]] handle via its
   converter — [[message->renderable]] / [[eval->renderable]]), then the
   folded live [[readline]].

   Turn boundaries are NOT containers — there are no per-turn headers or
   process-boundary rows. Exact cache membership, not run identity, decides
   whether each eval carries a handle.

   An inbound that arrived after the agent's last action (a fresh wake or
   a mid-call arrival) is flagged NEW — UNANSWERED — so the agent
   re-orients to it. The
   inbound gate is [[inbound-msg?]] — the SAME conditions as the wake, so a
   `:core` nudge never shows as a fake inbound.

   NO clipping yet (`:seon.render/clip :none`): the transcript renders ALL
   events; the sliding window lands later. Every past event renders
   byte-identical turn-to-turn (times from FIXED stored `:at`), so the
   prefix caches — only the readline's `now` changes between turns."
  {:malli/schema [:=> [:cat :seon.render/section-request] :string]}
  [{:seon.agent/keys [id] db :seon.db/db render-fn :seon.render/render :as input}]
  (let [db       (or db @db/*conn*)
        a        (agent-rec id db)
        my-eid   (:db/id a)
        own-id   id
        cur-ns   (ctx/current-ns {:seon.agent/id id :seon.db/db db})
        ns-str   (if (keyword? cur-ns) (name cur-ns) (str cur-ns))
        ;; Render handle: the recursive walker injects `:seon.render/render`
        ;; (ONE section model). When the section is called DIRECTLY (through
        ;; the `seon.agent/transcript-block` re-export), there
        ;; is no injected handle — fall back to a local ai render so the
        ;; same code path produces the same String.
        render*  (or render-fn #(render/render :seon.render/ai input %))
        events   (ordered-events db own-id my-eid)
        ;; The agent's LAST ACTION = newest :at over its OWN events (evals +
        ;; outbound messages). Events are already :at-ascending, so the last
        ;; own-event IS the newest; nil when the agent has not acted yet.
        last-action-at (some->> events
                                (filter (fn [ev] (or (= :eval (::kind ev))
                                                     (::outbound? ev))))
                                last
                                ::at)
        ;; Flag any INBOUND newer than the last action as NEW (UNANSWERED) —
        ;; a fresh wake or a mid-call arrival the agent re-orients to. With no
        ;; prior action (nil) every inbound is unanswered. Once the agent acts
        ;; on it the action's :at moves past it and the flag vanishes.
        events*  (mapv (fn [ev]
                         (if (and (= :message (::kind ev))
                                  (not (::outbound? ev))
                                  (or (nil? last-action-at)
                                      (> (.getTime ^js (::at ev))
                                         (.getTime ^js last-action-at))))
                           (assoc ev ::new? true)
                           ev))
                       events)
        ;; Coalesce: drop content-free noise + collapse consecutive
        ;; same-error runs to one line (a thrash burst can't flood the ctx).
        events*c (coalesce-events events*)
        ;; CP-3 moves 4+5: read the transcript block's reactive config once.
        ;; The injected node (this block's OWN map, `:seon.render/node` —
        ;; same convention as the warnings block's :seon.warn/ns override)
        ;; takes precedence; the stored block entity is the fallback for
        ;; direct callers and tests with no injected node. On the
        ;; render-context path node = the stored block, so this is
        ;; byte-identical — it additionally lets a PROFILE caller (the
        ;; seon.repl.autocomplete projection) pass per-render config.
        node     (:seon.render/node input)
        tblock   (block-ent db my-eid :transcript)
        ;; move 5 / CP-5 — the clip POLICY off `::tiers` + `::turns-retained`:
        ;; empty tiers (v1 default) → render-all (byte-parity); non-empty →
        ;; the age-banded window (last `retained` turns verbatim, older evals
        ;; kept within each tier's token budget).
        tiers    (or (::tiers node) (::tiers tblock))
        retained (or (::turns-retained node) (::turns-retained tblock) 8)
        events*t (clip-events-by-tiers (mapv #(into {} %) tiers) retained events*c)
        ;; move 4 / CP-5 — the per-eval RESULT-BODY cap off `::result-decay` ×
        ;; the eval's AGE (turn-offset = newest turn − the eval's `::turn-idx`).
        ;; The v1 default is the 3-level shrink schedule [0→16384, 2→1500,
        ;; 5→200]: a fresh eval renders near-full, an older one clips to a stub
        ;; (keeping its `result/<id>` handle) — the "start larger, shrink over
        ;; time" safety net for full block rendering. Byte-STABLE within a band
        ;; (the cap changes only at a level boundary). Injected as
        ;; `:seon.render/result-body-cap` onto each eval event's `::entity`,
        ;; which `eval->renderable` forwards to `format-eval-row`.
        levels   (or (::result-decay node) (::result-decay tblock))
        ;; Runtime-cache bytes OFF (`::result-handles?` false, node first):
        ;; every eval renders without a `result/<id>` handle, so the render is
        ;; a pure function of the db value across eviction/restarts (the
        ;; autocomplete profile's setting).
        handles? (let [nv (::result-handles? node)
                       tv (::result-handles? tblock)]
                   (cond (some? nv) nv
                         (some? tv) tv
                         :else      true))
        ;; A PROFILE may PIN escape-clipping as a block-config CONSTANT
        ;; (`:seon.agent.ctx/escape-clipping?` on the profile's block map) —
        ;; threaded onto each event so the converters never re-read the live
        ;; conn (deterministic over the db value). ABSENT (every stored
        ;; block — the attr lives on the AGENT entity, not blocks) → nil →
        ;; nothing stamped → the converters' live read, byte-parity.
        esc      (:seon.agent.ctx/escape-clipping? node)
        max-turn (transduce (keep ::turn-idx) max -1 events*t)
        events** (mapv (fn [ev]
                         (if (= :eval (::kind ev))
                           (let [offset (if (neg? max-turn) 0
                                            (- max-turn (or (::turn-idx ev) max-turn)))
                                 cap    (decay-cap-for-offset
                                          (mapv #(into {} %) levels) offset
                                          ctx/result-body-render-cap)]
                             (cond-> (update ev ::entity assoc
                                             :seon.render/result-body-cap cap)
                               (some? esc)
                               (update ::entity assoc
                                       :seon.agent.ctx/escape-clipping? esc)
                               (not handles?) (assoc ::result-live? false)))
                           (cond-> ev
                             (some? esc) (assoc ::escape? esc))))
                       events*t)
        ;; Render each event directly. Handle availability is already an exact
        ;; per-eval fact on the event; no run/process boundary is inferred.
        body
        (->> events**
             (map render*)
             (remove str/blank?)
             (str/join "\n"))
        head (masthead ns-str (ctx/repl-mode db))
        ;; ::readline? false (node first, stored block fallback) drops the
        ;; folded live readline — the ONE moving (`now`-reading) line — so a
        ;; profile render is a pure function of the db value. Default true.
        readline? (let [nv (::readline? node)
                        tv (::readline? tblock)]
                    (cond (some? nv) nv
                          (some? tv) tv
                          :else      true))
        tail (when readline? (readline input))]
    (->> [head body tail]
         (remove str/blank?)
         (str/join "\n\n"))))

;; ------------------------------------------------------------
;; HTML twin — the debug view's right-pane transcript card. The same flat
;; event stream, each event rendered through the recursive html handle via
;; its kind's html converter (`seon.handlers.message/render-html` /
;; `seon.handlers.eval/render-html` — resolved by the entity's schema
;; kind), oldest-first.
;; ------------------------------------------------------------

(defn recent-html-events
  "Bound the normal HTML transcript to `retained` recent turns.

   `turn-ats` is the ordered projection of `:seon.agent.turn/at` facts, not
   database entity views. Evals are selected by their derived `::turn-idx`.
   Messages at or after the oldest retained turn are kept, plus the single
   preceding message so the visible conversation starts with its immediate
   context. With no turns yet, retain the newest `retained` events. A zero
   window renders no history.

   This is a pure projection over database-derived turns/events. Call it before
   [[coalesce-events]] so an error run is bounded before it is summarized."
  {:malli/schema [:=> [:catn [::turn-ats [:sequential :inst]]
                             [::retained :int]
                             [::events [:sequential :map]]]
                  [:vector :map]]}
  [turn-ats retained events]
  (let [turn-ats (vec turn-ats)
        events   (vec events)
        retained (max 0 retained)]
    (cond
      (zero? retained) []
      (empty? turn-ats) (vec (take-last retained events))
      :else
      (let [first-turn-idx (max 0 (- (count turn-ats) retained))
            cutoff         (nth turn-ats first-turn-idx)
            cutoff-ms      (.getTime ^js cutoff)
            preceding      (->> events
                                (filter #(and (= :message (::kind %))
                                              (< (.getTime ^js (::at %)) cutoff-ms)))
                                last)
            recent         (filterv
                             (fn [ev]
                               (case (::kind ev)
                                 :eval (>= (or (::turn-idx ev) -1) first-turn-idx)
                                 :message (>= (.getTime ^js (::at ev)) cutoff-ms)
                                 false))
                             events)]
        (if preceding
          (into [preceding] recent)
          recent)))))

(defn- turn-index-at
  "Index of the latest turn that began no later than `at`, or nil."
  [turn-ats at]
  (when (instance? js/Date at)
    (let [at-ms (.getTime ^js at)]
      (reduce-kv
        (fn [found idx turn-at]
          (if (<= (.getTime ^js turn-at) at-ms)
            idx
            (reduced found)))
        nil
        (vec turn-ats)))))

(defn- recent-html-source-events
  "Bound message/eval reads before constructing the human transcript DOM.

   Each fact owner contributes at most 200 newest append-only entities. The
   retained-turn projection then removes older rows. This cap is deliberately
   HTML-only; the AI transcript's policy remains unchanged."
  [db own-id my-eid turn-ats]
  (let [messages (if my-eid
                   (msg/recent
                     {:seon.db/db db
                      :seon.agent/id own-id
                      :seon.agent.message/recent-limit 200})
                   [])
        evals (seval/recent
                {:seon.db/db db
                 :seon.agent/id own-id
                 :seon.eval/recent-limit 200})
        kind-rank {:message 0 :eval 1}]
    (->> (concat
           (keep #(message->event my-eid own-id %) messages)
           (map #(eval->event (turn-index-at turn-ats (:seon.eval/at %)) %)
                evals))
         (filter #(instance? js/Date (::at %)))
         (sort-by (juxt #(.getTime ^js (::at %))
                        #(kind-rank (::kind %) 9)))
         vec)))

(defn- coalesced-card-html
  "One fixed-size activity row for a coalesced error run.

   The normal transcript never embeds the member eval cards in a closed
  disclosure: their technical payload remains database data and the exact AI
  transcript remains available in the debug web UI."
  [{::keys [signature count]}]
  [:div {:class "agent-activity flex items-baseline gap-1.5 px-2 py-1 text-xs min-w-0"}
   [:span {:class "font-medium text-text-400 truncate"}
    (tokens/clip-str signature 30)]
   [:span {:class "font-mono text-error shrink-0"} (str count "× failed")]])

(defn transcript-block-html
  "The HTML TWIN of [[transcript-block]]: the agent's flat time-ordered
   event stream rendered as a professional chat (message bubbles + terse eval
   activity rows), oldest-first. The normal surface is bounded by the block's
   `::turns-retained` policy and never embeds eval source/result/error payloads
   in hidden DOM. Returns BARE hiccup; an empty transcript renders a friendly
   placeholder."
  {:malli/schema [:=> [:cat :seon.render/section-request] [:maybe :seon.render.canvas/hiccup]]}
  [{:seon.agent/keys [id] db :seon.db/db :as input}]
  (let [db       (or db @db/*conn*)
        a        (agent-rec id db)
        my-eid   (:db/id a)
        own-id   id
        node     (:seon.render/node input)
        tblock   (block-ent db my-eid :transcript)
        retained (or (::turns-retained node) (::turns-retained tblock) 8)
        ;; Project only the ordered fact the window policy needs. Datahike
        ;; entities are associative views, not Clojure maps; keeping them out
        ;; of the pure helper also keeps its public contract storage-agnostic.
        turn-ats (mapv :seon.agent.turn/at (ctx/agent-turns id db))
        ;; The HTML twin is a human navigation surface, not a second copy of
        ;; the full technical log. Bound first, then coalesce the visible
        ;; window so a historical error burst cannot re-enter through its
        ;; member cards.
        events   (->> (recent-html-source-events db own-id my-eid turn-ats)
                      (recent-html-events turn-ats retained)
                      coalesce-events)
        render-message
        (fn [ev]
          (when-let [mid (::id ev)]
            (render/render-entity-html
              (assoc input :seon.db/db db
                     :seon.render/node
                     (db/pull db '[* {:seon.agent.message/from
                                      [:db/id :seon.user/id :seon.agent/id]
                                      :seon.agent.message/to
                                      [:db/id :seon.user/id :seon.agent/id]}]
                              [:seon.agent.message/id mid])))))
        cards
        (->> events
             (keep
               (fn [ev]
                 (case (::kind ev)
                   :coalesced (coalesced-card-html ev)
                   ;; Message events carry projected fields, not the raw
                   ;; entity — re-pull the message by id so the html
                   ;; converter sees its full shape.
                   :message   (render-message ev)
                   :eval      (eval-handler/render-activity-html
                                (assoc input :seon.render/node (::entity ev)
                                       :seon.db/db db))
                   nil)))
             vec)
        latest-reply (some->> events
                              (filter #(and (= :message (::kind %))
                                            (::outbound? %)))
                              last
                              render-message)]
    (if (seq cards)
      [:div {:class "seon-card"}
       [:div {:class "seon-card-compact"}
        (or latest-reply (last cards))]
       (into [:div {:class "seon-card-expanded flex flex-col"}] cards)]
      [:div {:class "text-text-500 italic p-2 text-xs font-mono"}
       "no events yet — every message and eval this agent makes appears here live"])))
