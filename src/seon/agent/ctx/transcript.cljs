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
   the free dynamic readline at the very bottom (below the cache breakpoint —
   busting there is free). It carries live time and, for root, bounded host
   telemetry. No historical row reads either input.

   The eval-row converter delegates to `seon.agent.ctx/format-eval-row` (which
   carries the fabrication-guard + the component caps); the message
   converter renders the REPL-comment `;;; ◀ from X` / `;;; ▶ to X` line."
  (:require
    [clojure.string :as str]
    [malli.core :as m]
    [seon.agent.message :as msg]
    [seon.agent.ctx :as ctx]
    [seon.agent.home :as home]
    [seon.ai.tokens :as tokens]
    [seon.config :as config]
    [seon.db :as db]
    [seon.db.protocol :as protocol]
    [seon.derive :as derive]
    [seon.eval :as seval]
    [seon.handlers.eval :as eval-handler]
    [seon.render :as render]
    [seon.schema :as schema]))

(def ^:private node-os (js/require "os"))

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
;; default is the REAL 3-level decay schedule — full-enough THIS turn + next
;; (offset 0→4096), partial at offset 2 (→1024), then a useful old-result
;; plateau at offset 5 (→512, keeping identity and diagnostic detail). This lets
;; blocks render FULL (escape-clipping) without unbounded transcript growth: an
;; old eval body shrinks as it ages out of the working set.
(schema/register! ::result-decay [:vector {:seon.db/component true
                                           :default [{::from-turn-offset 0 ::token-cap 4096}
                                                     {::from-turn-offset 2 ::token-cap 1024}
                                                     {::from-turn-offset 5 ::token-cap 512}]}
                                  :seon.db/ref]) ; of ::decay-level entities

;; The transcript window (turns kept verbatim before eviction into summaries).
(schema/register! ::turns-retained [:int {:default 25 :min 0}])

;; AI transcript rotation: retain at most 50 turns, then evict one complete
;; 25-turn prefix at a time. The settled chunk shares one budget charged on
;; actual rendered event text; the current append-only chunk is not charged.
(schema/register! ::turn-window-size    [:int {:default 50 :min 1 :max 200}])
(schema/register! ::turn-eviction-size  [:int {:default 25 :min 1}])
(schema/register! ::settled-token-cap   [:int {:default 8192 :min 0}])

(defn- schema-default
  "Read one colocated policy default from its current source declaration.

   Compile the collected definition itself, not `schema-key` through Malli's
   process-global registry: after database activation that registry is the
   last committed projection and may legitimately lag a newly loaded source
   declaration until reconciliation commits."
  [schema-key]
  (some-> (schema/schema-definition schema-key)
          m/schema
          m/properties
          :default))

(def ^:private default-turns-retained     (schema-default ::turns-retained))
(def ^:private default-turn-window-size   (schema-default ::turn-window-size))
(def ^:private default-turn-eviction-size (schema-default ::turn-eviction-size))
(def ^:private default-settled-token-cap  (schema-default ::settled-token-cap))

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
;; The transcript block's `::result-decay`, batched turn-window, and settled
;; budget datoms drive clipping AT RENDER TIME. `::tiers` remains registered
;; only while old projection manifests migrate; the AI transcript no longer
;; reads it.
;; ============================================================

(defn decay-cap-for-offset
  "The eval-result render token-cap for an eval at turn-`offset` (current-turn
   − the eval's turn), selected from the transcript block's `::result-decay`
   LEVELS (each `{::from-turn-offset ::token-cap}`): the level whose
   `::from-turn-offset` is the LARGEST ≤ `offset` wins; its `::token-cap` is
   the cap. Empty/absent levels → `default-cap` (the v1 default is the SINGLE
   level 0→4096, so every offset selects 4096).
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

(defn turn-window-cutoff
  "Oldest retained turn index for batched transcript rotation.

   `turn-count < window-size` keeps the complete history. At the window size,
   and every `eviction-size` turns thereafter, one complete oldest chunk is
   omitted. For 50/25 this yields cutoffs 0 through turn 49, 25 through turn
   74, 50 through turn 99, and so on."
  {:malli/schema [:=> [:catn [::turn-count :int]
                             [::window-size :int]
                             [::eviction-size :int]]
                  :int]}
  [turn-count window-size eviction-size]
  (let [n (max 0 turn-count)
        w (max 1 window-size)
        e (max 1 eviction-size)]
    (if (< n w)
      0
      (* e (inc (quot (- n w) e))))))

(defn clip-events-by-turn-window
  "Omit complete old event chunks according to [[turn-window-cutoff]].

   Every event must carry its database-derived `::turn-idx`; an event before
   the first turn is associated with turn zero. No retained event is rewritten
   and the complete source facts remain in the database."
  {:malli/schema [:=> [:catn [::turn-count :int]
                             [::window-size :int]
                             [::eviction-size :int]
                             [::events [:sequential :map]]]
                  [:vector :map]]}
  [turn-count window-size eviction-size events]
  (let [cutoff (turn-window-cutoff turn-count window-size eviction-size)]
    (filterv #(>= (or (::turn-idx %) 0) cutoff) events)))

(defn clip-rendered-events-by-settled-budget
  "Keep rendered events newest-first within one settled-chunk token budget.

   Rows at or after `active-start` are the current append-only chunk and always
   survive. Older retained rows share `token-cap`, charged on their complete
   rendered `::text` (source, narration, result, and error), not result EDN.
   Over-budget rows are omitted whole; chronological order and retained bytes
   do not change."
  {:malli/schema [:=> [:catn [::active-start :int]
                             [::token-cap :int]
                             [::rendered-events [:sequential :map]]]
                  [:vector :map]]}
  [active-start token-cap rows]
  (let [spent (volatile! 0)]
    (->> rows
         reverse
         (reduce
           (fn [kept row]
             (if (>= (or (get-in row [::event ::turn-idx]) 0) active-start)
               (conj kept row)
               (let [cost (tokens/estimate (::text row))]
                 (if (<= (+ @spent cost) token-cap)
                   (do (vswap! spent + cost) (conj kept row))
                   kept))))
           [])
         reverse
         vec)))

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
                             (if (boolean? escape?) escape? true))
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
                 ::turn-idx      (::turn-idx (last grp))
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

(defn- turn-index-at
  "Index of the latest turn that began no later than `at`, or zero when the
   event predates the first turn."
  [turn-ats at]
  (if (instance? js/Date at)
    (let [at-ms (.getTime ^js at)]
      (or
        (reduce-kv
          (fn [found idx turn-at]
            (if (<= (.getTime ^js turn-at) at-ms)
              idx
              (reduced found)))
          nil
          (vec turn-ats))
        0))
    0))

(defn- message->event
  "Convert one pulled message row into a transcript event, or nil."
  [my-eid own-id m]
  (let [from      (:seon.agent.message/from m)
        outbound? (= my-eid (:db/id from))]
    (when (or outbound? (inbound-msg? m my-eid))
      {::at         (:seon.agent.message/at m)
       ::kind       :message
       ::id         (:seon.agent.message/id m)
       ::entity     m
       ::outbound?  outbound?
       ::content    (:seon.agent.message/content m)
       ::from-label (ctx/message-label from own-id)
       ::to-labels  (->> (:seon.agent.message/to m)
                         (map #(ctx/message-label % own-id))
                         distinct vec)
       :seon.render/ai
       'seon.agent.ctx.transcript/message->renderable})))

(defn- eval->event
  "Convert one eval row into a transcript event at its optional turn index."
  ([turn-idx e]
   (eval->event turn-idx e (seval/result-live? (:seon.eval/id e))))
  ([turn-idx e result-live?]
  (cond->
    {::at           (:seon.eval/at e)
     ::kind         :eval
     ::entity       (into {} e)
     ::result-live? result-live?
     :seon.render/ai 'seon.agent.ctx.transcript/eval->renderable}
    (some? turn-idx) (assoc ::turn-idx turn-idx))))

(defn- with-ns-markers
  "Thread a `; in <ns>` marker into each EVAL event whose ns differs from
   the prior eval's (only evals carry an ns; message events pass through).
   A pure left-to-right pass over the already-time-sorted events."
  ([events] (with-ns-markers events ::none))
  ([events initial-ns]
   (first
     (reduce
       (fn [[out prev-ns] ev]
         (if (= :eval (::kind ev))
           (let [ek (:seon.eval/ns (::entity ev))
                 marker (when (and (some? ek) (not= prev-ns ek))
                          (str "; in " (name ek)))]
             [(conj out (assoc ev ::ns-marker marker)) (or ek prev-ns)])
           [(conj out ev) prev-ns]))
       [[] initial-ns]
       events))))

(defn- format-bytes
  "Compact binary size for the free dynamic tail."
  [n]
  (let [mib (/ (or n 0) 1048576)]
    (if (>= mib 1024)
      (str (.toFixed (/ mib 1024) 1) " GiB")
      (str (.toFixed mib 0) " MiB"))))

(defn host-telemetry
  "The bounded Unix host line for root's free dynamic tail.

   Load averages use the conventional 1/5/15-minute order; they are runnable
   queue averages, not CPU percentages. RSS and used heap come from this pod's
   Node process. Always comment-shaped and clipped below 50 estimated tokens."
  {:malli/schema [:=> [:cat] :string]}
  []
  (let [[one five fifteen] (js->clj (.loadavg node-os))
        memory              (.memoryUsage js/process)
        line                (str "; host · load 1m/5m/15m "
                                 (.toFixed one 2) "/"
                                 (.toFixed five 2) "/"
                                 (.toFixed fifteen 2)
                                 " · rss " (format-bytes (.-rss memory))
                                 " · heap " (format-bytes (.-heapUsed memory)))]
    (tokens/clip-str line 50)))

(defn readline
  "The folded live readline — DERIVED every render, never stored. The
   very-bottom of the transcript: the cursor (`ns=>` current ns) plus this
   turn's status/steering as a `;` line (turn · time · loop K/cap ·
   state · any cap-pressure steering) — ONE steering surface. Always
   present. Root additionally sees one bounded Unix host line. This is the
   free dynamic tail: the only transcript material that reads live process
   state, below the cache breakpoint where busting is free.

   Pressure steering escalates toward the per-loop cap — positive-framing:
   it tells the agent what to DO (finish, or message the result), never
   just that something is wrong."
  {:malli/schema [:=> [:catn [::input :map]] :string]}
  [{:seon.agent/keys [id]
    state :seon.derive/state
    cur-ns :seon.eval/ns
    n-turns ::turn-count
    run-turn-count :seon.agent.run/turn-count
    run-form-count :seon.agent.run/form-count
    mode :seon.config/repl-mode
    :as input}]
  (let [mode    (or mode :batch)
        ns-str  (if (keyword? cur-ns) (name cur-ns) (str cur-ns))
        run     (get-in input [:seon.agent/entity :seon.agent/run])
        ;; loop-k = work spent in the CURRENT open run, in the SAME
        ;; denomination the loop's bound checks (mode-denominated, repl-milestone rung-0
        ;; verdict 2026-07-10): `:batch` counts the run's turns, `:stream`
        ;; counts its FORMS (evals) — one form per stream turn, so prose
        ;; turns don't move the meter. 0 when idle. cap = the run's
        ;; bumpable turn-limit (renew! grows it), else the default when idle.
        loop-k  (cond
                  (not= :open (:seon.agent.run/status run)) 0
                  (= :stream mode) (or run-form-count 0)
                  :else (or run-turn-count 0))
        policy  (merge (config/default-run-policy)
                       (select-keys input
                                    [:seon.config.run/batch-turn-limit
                                     :seon.config.run/stream-form-limit
                                     :seon.config.run/deadline-ms]))
        cap     (or (:seon.agent.run/turn-limit run)
                    (if (= :stream mode)
                      (:seon.config.run/stream-form-limit policy)
                      (:seon.config.run/batch-turn-limit policy)))
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
         (when (= "root" id) (str (host-telemetry) "\n"))
         ns-str "=> ")))

(defn- ordered-events
  "The agent's full flat event stream — messages + evals UNIONed, sorted
   by FIXED stored `:at` (byte-stable), with `; in <ns>` markers threaded
   into evals where the ns changes. Ties (same `:at`) sort messages before
   evals for stable output. The caller flags any inbound newer than the
   agent's last own action as NEW (unanswered)."
  [{:seon.agent/keys [id entity]
    turns ::turns
    messages ::messages
    previous-ns ::previous-ns
    events ::events}]
  (if events
    events
    (let [my-eid (:db/id entity)
        turn-ats (mapv :seon.agent.turn/at turns)
        msgs (->> messages
                  (keep #(message->event my-eid id %))
                  (mapv #(assoc % ::turn-idx
                                (turn-index-at turn-ats (::at %)))))
        evs  (vec
               (for [{turn-idx ::turn-idx :as turn} turns
                     e (sort-by (juxt :seon.eval/at :db/id)
                                (:seon.agent.turn/evals turn))]
                 (eval->event turn-idx e
                              (seval/result-live? (:seon.eval/id e)))))
        kind-rank {:message 0 :eval 1}
        sorted (sort-by (juxt #(.getTime ^js (::at %))
                              #(kind-rank (::kind %) 9)
                              #(or (:db/id (::entity %)) (::id %) 0))
                        (concat msgs evs))]
      (with-ns-markers sorted (or previous-ns ::none)))))

(defn- query-member
  [query arguments max-work max-results max-result-weight]
  {::protocol/operation protocol/query-operation
   ::protocol/query-form query
   ::protocol/arguments arguments
   :datahike.resource/max-work max-work
   :datahike.resource/max-results max-results
   :datahike.resource/max-result-weight max-result-weight})

(defn- pull-member
  [selector entity-id max-work max-results max-result-weight]
  {::protocol/operation protocol/pull-operation
   ::protocol/selector selector
   ::protocol/entity-id entity-id
   :datahike.resource/max-work max-work
   :datahike.resource/max-results max-results
   :datahike.resource/max-result-weight max-result-weight})

(defn- member-value
  [member]
  (cond
    (not (true? (::protocol/success? member))) {::error member}
    (contains? member :datahike.query/result)
    {:datahike.query/result (:datahike.query/result member)}
    :else {::protocol/result (::protocol/result member)}))

(def ^:private turn-count-query
  '[:find (count ?turn) .
    :in $ ?agent-id
    :where
    [?agent :seon.agent/id ?agent-id]
    [?run :seon.agent.run/agent ?agent]
    [?turn :seon.agent.turn/run ?run]])

(def ^:private current-ns-query
  '{:find [?ns ?at ?eval]
    :in [$ ?agent-id]
    :where [[?agent :seon.agent/id ?agent-id]
            [?run :seon.agent.run/agent ?agent]
            [?turn :seon.agent.turn/run ?run]
            [?turn :seon.agent.turn/evals ?eval]
            [?eval :seon.eval/ok? true]
            [?eval :seon.eval/at ?at]
            [?eval :seon.eval/ns ?ns]]
    :order-by [?at :desc ?eval :desc]
    :limit 1})

(def ^:private last-action-query
  '[:find (max ?at) .
    :in $ ?agent-id
    :where
    [?agent :seon.agent/id ?agent-id]
    (or-join [?agent ?at]
      (and [?run :seon.agent.run/agent ?agent]
           [?turn :seon.agent.turn/run ?run]
           [?turn :seon.agent.turn/evals ?eval]
           [?eval :seon.eval/at ?at])
      (and [?message :seon.agent.message/from ?agent]
           [?message :seon.agent.message/at ?at]))])

(def ^:private run-turn-count-query
  '[:find (count ?turn) .
    :in $ ?run-id
    :where
    [?run :seon.agent.run/id ?run-id]
    [?turn :seon.agent.turn/run ?run]
    (not [?turn :seon.agent.turn/scheduled? true])])

(def ^:private run-form-count-query
  '[:find (count ?eval) .
    :in $ ?run-id
    :where
    [?run :seon.agent.run/id ?run-id]
    [?turn :seon.agent.turn/run ?run]
    (not [?turn :seon.agent.turn/scheduled? true])
    [?turn :seon.agent.turn/evals ?eval]])

(def ^:private eval-rows-query
  '[:find ?turn
          (pull ?eval [:db/id :seon.eval/id :seon.eval/at
                       :seon.eval/source :seon.eval/narration
                       :seon.eval/output :seon.eval/ok?
                       :seon.eval/result-edn :seon.eval/error
                       :seon.eval/error-data :seon.eval/ns
                       :seon.render/full?])
    :in $ [?turn ...]
    :where
    [?turn :seon.agent.turn/evals ?eval]
    [?eval :seon.eval/at _]])

(def ^:private message-selector
  '[:db/id :seon.agent.message/id :seon.agent.message/content
    :seon.agent.message/at :seon.agent.message/hops
    :seon.agent.message/origin
    {:seon.agent.message/from [:db/id :seon.user/id :seon.agent/id]}
    {:seon.agent.message/to [:db/id :seon.user/id :seon.agent/id]}])

(defn- turns-query [window-size]
  {:find '[?turn ?at ?scheduled? ?run ?run-id]
   :in '[$ ?agent-id]
   :where '[[?agent :seon.agent/id ?agent-id]
            [?run :seon.agent.run/agent ?agent]
            [?run :seon.agent.run/id ?run-id]
            [?turn :seon.agent.turn/run ?run]
            [?turn :seon.agent.turn/at ?at]
            [(get-else $ ?turn :seon.agent.turn/scheduled? false) ?scheduled?]]
   :order-by '[?at :desc ?turn :desc]
   :limit window-size})

(defn- messages-query [cutoff-at]
  (cond-> {:find [(list 'pull '?message message-selector)]
           :in '[$ ?agent-id]
           :where '[[?agent :seon.agent/id ?agent-id]
                    (or-join [?message ?agent]
                      [?message :seon.agent.message/from ?agent]
                      [?message :seon.agent.message/to ?agent])
                    [?message :seon.agent.message/at ?at]]}
    cutoff-at
    (assoc :in '[$ ?agent-id ?cutoff-at]
           :where '[[?agent :seon.agent/id ?agent-id]
                    (or-join [?message ?agent]
                      [?message :seon.agent.message/from ?agent]
                      [?message :seon.agent.message/to ?agent])
                    [?message :seon.agent.message/at ?at]
                    [(>= ?at ?cutoff-at)]])))

(defn- previous-ns-query [cutoff-at]
  {:find '[?ns ?eval-at ?eval]
   :in '[$ ?agent-id ?cutoff-at]
   :where '[[?agent :seon.agent/id ?agent-id]
            [?run :seon.agent.run/agent ?agent]
            [?turn :seon.agent.turn/run ?run]
            [?turn :seon.agent.turn/at ?turn-at]
            [(< ?turn-at ?cutoff-at)]
            [?turn :seon.agent.turn/evals ?eval]
            [?eval :seon.eval/at ?eval-at]
            [?eval :seon.eval/ns ?ns]]
   :order-by '[?eval-at :desc ?eval :desc]
   :limit 1})

(defn- query-result [member]
  (:datahike.query/result (member-value member)))

(defn- acquisition-error [members]
  (some (fn [member] (::error (member-value member))) members))

(defn- database-error [value]
  (when (and (map? value) (string? (:seon.error/message value))) value))

(defn- ^:async acquire-transcript
  [{:seon.agent/keys [id entity] :as input}]
  (let [coordinate (or (::db/coordinate input)
                       (::db/coordinate (db/current-tx-context)))
        node (:seon.render/node input)
        window-size (or (::turn-window-size node) default-turn-window-size)
        run (:seon.agent/run entity)
        run-id (:seon.agent.run/id run)
        open? (= :open (:seon.agent.run/status run))
        stage-one-members
        (cond->
          [(pull-member [:seon.config/repl-mode
                         :seon.config.run/batch-turn-limit
                         :seon.config.run/stream-form-limit
                         :seon.config.run/deadline-ms]
                        [:seon.config/id config/cluster-config-id]
                        256 32 4096)
           ;; Datahike charges intermediate relations to max-results and their
           ;; retained structure to max-result-weight. Both remain finite;
           ;; the grown-query fixture calibrates them independently.
           (query-member turn-count-query [id] 1000000 1000000 4096)
           (query-member (turns-query window-size) [id] 1000000 1000000 65536)
           (query-member current-ns-query [id] 500000 500000 8192)
           (query-member last-action-query [id] 500000 500000 8192)]
          open? (conj (query-member run-turn-count-query [run-id]
                                    500000 500000 4096)
                      (query-member run-form-count-query [run-id]
                                    500000 500000 4096)))
        stage-one
        (if coordinate
          (await (db/execute-many {::db/coordinate coordinate
                                   ::db/members stage-one-members
                                   ::db/max-result-weight 131072}))
          {::error {:seon.error/message
                    "Transcript acquisition requires an exact database coordinate."
                    :seon.error/kind :core-bug}})]
    (if-let [error (or (::error stage-one)
                       (database-error stage-one)
                       (when-not (= coordinate (::db/coordinate stage-one))
                         {:seon.error/message
                          "Transcript acquisition moved database coordinates."
                          :seon.error/kind :core-bug})
                       (acquisition-error (::db/results stage-one)))]
      (assoc input ::error error)
      (let [[config-member turn-count-member turns-member ns-member action-member
             run-turn-member run-form-member] (::db/results stage-one)
            stored-config (or (::protocol/result (member-value config-member)) {})
            turn-count (or (query-result turn-count-member) 0)
            newest-rows (->> (query-result turns-member)
                             (sort-by (juxt second first))
                             vec)
            first-index (- turn-count (count newest-rows))
            cutoff-index (turn-window-cutoff turn-count window-size
                                             (or (::turn-eviction-size node)
                                                 default-turn-eviction-size))
            turns (->> newest-rows
                       (map-indexed
                         (fn [offset [turn at scheduled? run-eid row-run-id]]
                           {::turn-idx (+ first-index offset)
                            :db/id turn
                            :seon.agent.turn/at at
                            :seon.agent.turn/scheduled? scheduled?
                            :seon.agent.turn/run
                            {:db/id run-eid :seon.agent.run/id row-run-id}}))
                       (filterv #(>= (::turn-idx %) cutoff-index)))
            cutoff-at (:seon.agent.turn/at (first turns))
            rotated? (pos? cutoff-index)
            stage-two-members
            (cond-> []
              (seq turns)
              (conj (query-member eval-rows-query (mapv :db/id turns)
                                  1000000 1000000 524288))
              true
              (conj (query-member (messages-query cutoff-at)
                                  (cond-> [id] cutoff-at (conj cutoff-at))
                                  1000000 1000000 262144))
              rotated?
              (conj (query-member (previous-ns-query cutoff-at) [id cutoff-at]
                                  500000 500000 8192)))
            stage-two (await (db/execute-many
                               {::db/coordinate coordinate
                                ::db/members stage-two-members
                                ::db/max-result-weight 790528}))]
        (if-let [error (or (::error stage-two)
                           (database-error stage-two)
                           (when-not (= coordinate (::db/coordinate stage-two))
                             {:seon.error/message
                              "Transcript acquisition moved database coordinates."
                              :seon.error/kind :core-bug})
                           (acquisition-error (::db/results stage-two)))]
          (assoc input ::error error)
          (let [[eval-member message-member previous-ns-member]
                (if (seq turns)
                  (::db/results stage-two)
                  (into [nil] (::db/results stage-two)))
                evals-by-turn (group-by first (or (query-result eval-member) []))
                turns (mapv (fn [turn]
                              (assoc turn :seon.agent.turn/evals
                                     (->> (get evals-by-turn (:db/id turn))
                                          (map second)
                                          (sort-by (juxt :seon.eval/at :db/id))
                                          vec)))
                            turns)
                current-ns (or (ffirst (query-result ns-member))
                               (home/home-ns id))
                previous-ns (ffirst (query-result previous-ns-member))
                mode (or (:seon.config/repl-mode stored-config) :batch)
                policy (merge (config/default-run-policy) stored-config)
                state (derive/state-from-primitives
                        (cond-> {:seon.agent.run/open? open?}
                          (:seon.agent/terminated-at entity)
                          (assoc :seon.agent/terminated-at
                                 (:seon.agent/terminated-at entity))
                          (:seon.agent.run/paused-at run)
                          (assoc :seon.agent.run/paused-at
                                 (:seon.agent.run/paused-at run))))
                effective-node
                (assoc node :seon.agent.ctx/escape-clipping?
                       (if (contains? node :seon.agent.ctx/escape-clipping?)
                         (:seon.agent.ctx/escape-clipping? node)
                         (if (boolean? (:seon.agent.ctx/escape-clipping? entity))
                           (:seon.agent.ctx/escape-clipping? entity)
                           true)))]
            (merge input policy
                   {:seon.render/node effective-node
                    :seon.config/repl-mode mode
                    :seon.derive/state state
                    :seon.eval/ns current-ns
                    ::turn-count turn-count
                    ::turns turns
                    ::messages (mapv first (or (query-result message-member) []))
                    ::last-action-at (query-result action-member)
                    ::previous-ns previous-ns
                    :seon.agent.run/turn-count
                    (or (query-result run-turn-member) 0)
                    :seon.agent.run/form-count
                    (or (query-result run-form-member) 0)})))))))

(declare format-transcript-block)

(defn ^:async transcript-block
  "Acquire and render the transcript at the active database coordinate."
  {:malli/schema [:=> [:cat :seon.render/section-request :any] :string]}
  [input _invoke-selected!]
  (format-transcript-block (await (acquire-transcript input))))

(defn- format-transcript-block
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

   The AI window rotates only in complete configured chunks and caps the
   settled chunk by actual rendered tokens. Retained events are never rewritten
   or summarized. Within a decay band and between rotation boundaries, every
   past event is byte-identical; only the append-only edge and free dynamic
   readline move."
  [{:seon.agent/keys [id entity]
    cur-ns :seon.eval/ns
    last-action-at ::last-action-at
    turn-count ::turn-count
    render-fn :seon.render/render
    :as input}]
  (if-let [error (::error input)]
    (str "[transcript] render failed: " (pr-str error))
    (let [node     (:seon.render/node input)
        ns-str   (if (keyword? cur-ns) (name cur-ns) (str cur-ns))
        ;; Render handle: the recursive walker injects `:seon.render/render`
        ;; (ONE section model). When the section is called DIRECTLY (through
        ;; the `seon.agent/transcript-block` re-export), there
        ;; is no injected handle — fall back to a local ai render so the
        ;; same code path produces the same String.
        render*  (or render-fn #(render/render :seon.render/ai input %))
        events   (ordered-events input)
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
        ;; Batched rotation is derived from turn facts. At 50 turns it removes
        ;; the oldest complete 25-turn chunk; at 75 it removes the next one.
        ;; This creates 24 append-only/cache-stable turns between membership
        ;; changes instead of sliding one event out on every turn.
        window-size   (or (::turn-window-size node)
                          default-turn-window-size)
        eviction-size (or (::turn-eviction-size node)
                          default-turn-eviction-size)
        settled-cap   (or (::settled-token-cap node)
                          default-settled-token-cap)
        events*t      (clip-events-by-turn-window
                        turn-count window-size eviction-size events*c)
        ;; move 4 / CP-5 — the per-eval RESULT-BODY cap off `::result-decay` ×
        ;; the eval's AGE (turn-offset = newest turn − the eval's `::turn-idx`).
        ;; The default 3-level schedule is [0→4096, 2→1024, 5→512]: a fresh
        ;; result is large enough to work with and an old result retains useful
        ;; identity + diagnostics. Byte-STABLE within a band
        ;; (the cap changes only at a level boundary). Injected as
        ;; `:seon.render/result-body-cap` onto each eval event's `::entity`,
        ;; which `eval->renderable` forwards to `format-eval-row`.
        levels   (or (::result-decay node) [])
        ;; Runtime-cache bytes OFF (`::result-handles?` false, node first):
        ;; every eval renders without a `result/<id>` handle, so the render is
        ;; a pure function of the db value across eviction/restarts (the
        ;; autocomplete profile's setting).
        handles? (let [nv (::result-handles? node)]
                   (if (some? nv) nv true))
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
        active-start (if (< turn-count window-size)
                       0
                       (* eviction-size (quot turn-count eviction-size)))
        body
        (->> events**
             (keep (fn [ev]
                     (let [text (render* ev)]
                       (when-not (str/blank? text)
                         {::event ev ::text text}))))
             (clip-rendered-events-by-settled-budget active-start settled-cap)
             (map ::text)
             (str/join "\n"))
        head (masthead ns-str (or (:seon.config/repl-mode input) :batch))
        ;; ::readline? false (node first, stored block fallback) drops the
        ;; folded live readline — the ONE moving (`now`-reading) line — so a
        ;; profile render is a pure function of the db value. Default true.
        readline? (let [nv (::readline? node)]
                    (if (some? nv) nv true))
        tail (when readline? (readline input))]
      (->> [head body tail]
           (remove str/blank?)
           (str/join "\n\n")))))

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

(defn- message-card-html
  "Render one already-acquired message row without another database read."
  [agent-id message]
  (let [from-label (ctx/message-label (:seon.agent.message/from message) agent-id)
        to-labels (->> (:seon.agent.message/to message)
                       (map #(ctx/message-label % agent-id))
                       distinct
                       (str/join ", "))
        user? (= "user" from-label)
        body (or (:seon.agent.message/content message) "")]
    [:div {:class "py-1 flex"}
     [:div {:class (str "seon-bubble max-w-[78%] min-w-0 rounded px-2.5 py-1.5 "
                        (if user?
                          "ml-auto bg-amber-950/40 border border-amber-800/40"
                          "mr-auto bg-base-900 border border-base-800"))}
      [:div {:class "flex items-baseline gap-2 flex-wrap"}
       [:span {:class "text-xs font-mono font-semibold"} from-label]
       (when-not (str/blank? to-labels)
         [:span {:class "text-xs font-mono text-text-500"}
          (str "→ " to-labels)])]
      [:div {:class "markdown mt-0.5 min-w-0"}
       (render/block :html {:seon.render/markdown (str/trim body)})]]]))

(defn- format-transcript-html
  [{:seon.agent/keys [id] turns ::turns :as input}]
  (let [node (:seon.render/node input)
        retained (or (::turns-retained node) default-turns-retained)
        turn-ats (mapv :seon.agent.turn/at turns)
        events (->> (ordered-events input)
                    (recent-html-events turn-ats retained)
                    coalesce-events)
        render-message
        (fn [event]
          (message-card-html id (::entity event)))
        cards
        (->> events
             (keep
               (fn [event]
                 (case (::kind event)
                   :coalesced (coalesced-card-html event)
                   :message (render-message event)
                   :eval (eval-handler/render-activity-html
                           {:seon.render/node (::entity event)})
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

(defn ^:async transcript-block-html
  "The HTML TWIN of [[transcript-block]]: the agent's flat time-ordered
   event stream rendered as a professional chat (message bubbles + terse eval
   activity rows), oldest-first. The normal surface is bounded by the block's
   `::turns-retained` policy and never embeds eval source/result/error payloads
   in hidden DOM. Returns BARE hiccup; an empty transcript renders a friendly
   placeholder."
  {:malli/schema [:=> [:cat :seon.render/section-request :any]
                  [:maybe :seon.render.canvas/hiccup]]}
  [input _invoke-selected!]
  (let [acquired (await (acquire-transcript input))]
    (if-let [error (::error acquired)]
      [:div {:class "text-error p-2 text-xs font-mono"}
       (str "transcript render failed: " (pr-str error))]
      (format-transcript-html acquired))))
