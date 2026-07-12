(ns seon.agent.turn
  "One agentic TURN, end-to-end — the unit the loop ([[seon.agent.loop]])
   drives once per LLM completion.

   A turn = open a `:seon.agent.turn` (stamping the agent's current run on
   `:seon.agent.turn/run` so the run's derived current-turn count can count
   it) → render the prompt → call the LLM → parse → eval-batch the forms →
   close the turn. The per-form eval isolation (every form runs; errors are
   envelopes) lives in [[seon.eval]]; `eval-count = n-ok + n-fail`.

   This namespace owns the `:seon.agent.turn/*` schema (its data-owner — a
   turn is a STANDALONE entity that points UP to its run; there is no
   session), and these fns:
     - `run-turn!`      — one full turn (the loop calls this)
     - `open-turn!` / `close-turn!` — the turn bracket (open-tx + close-tx)
     - `ask-and-eval!`  — LLM call + parse + eval-batch
     - `call-llm!`      — `(llm-fn prompt)` with one bounded transport retry
     - `render-prompt`   — ctx assembly
     - `turn-index`     — the agent's running turn number (logging)

   Dependency direction (acyclic): it references `:seon.agent.run/*` keywords
   (global registry, no require) and transacts via `seon.db` directly, so it
   does NOT require `seon.agent` (which would cycle: `seon.agent.loop`
   requires both). It MAY require ctx / eval / message / render."
  (:require
    [seon.ai :as ai]
    [seon.ai.tokens :as tokens]
    [seon.config :as config]
    [seon.retry :as retry]
    [seon.agent.ctx :as ctx]
    [seon.agent.home :as home]
    [my.blob :as blob]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.error :as error]
    [seon.eval :as seval]
    [seon.log :as seon-log]
    [seon.repl.internal :as repl-internal]
    [seon.schema :as schema]))

;; ============================================================
;; Causality graph — the :seon.agent.turn entity. Each render → LLM →
;; eval-batch cycle = one :seon.agent.turn, a STANDALONE entity that points
;; UP to its run via `:seon.agent.turn/run` (there is no session — a RUN is
;; the wake-episode grouping). Evals ride as component refs on the turn
;; (cascade-retract on turn retract). ALL counters are DERIVED, never
;; persisted (reactive-context).
;;
;; Identity attrs reference the canonical :seon.db/id shape (single source of
;; truth in seon.schema). The [:and {…} :seon.db/id] wrapping adds
;; {:seon.db/identity true} so the bridge writes :db.unique/identity.
;; ============================================================

(schema/register!
  :seon.agent.turn/id
  [:and {:seon.db/identity true
         :seon.db.id/generator :seon.db.id.generator/compact}
   ::db.id/compact-value])
(schema/register! :seon.agent.turn/at           :inst)
;; A turn is running/done/error — DISTINCT from the agent FSM state
;; (idle/running/…): a turn is a single completion, the agent is the actor.
(schema/register! :seon.agent.turn/status       [:enum :running :done :error])
;; The RUN this turn belongs to. Each turn-open STAMPS the agent's current
;; open run here, so the run's derived current-turn (`count turns where
;; :seon.agent.turn/run = run-eid`) is derivable. STORED — the spine that
;; links a turn back to its run → agent. References the canonical ref shape;
;; never inline.
(schema/register! :seon.agent.turn/run          :seon.db/ref)
;; PRESENCE marker: this turn is a SCHEDULE FIRE (a cron fn ran on the run),
;; NOT an LLM drive. It still stamps `:seon.agent.turn/run` so the transcript's
;; agent→runs→turns walk RENDERS its evals (the agent must SEE the schedule
;; fired and ran THIS) — but [[seon.derive/run-turn-count]] EXCLUDES it, so a
;; cron fire never burns a turn from the run's WORK budget (turn-limit). Absent
;; on every ordinary LLM turn (optional = absent); set `true` only on fires.
(schema/register! :seon.agent.turn/scheduled?   :boolean)
;; Three-tier storage: the datom carries the prompt's char count (display
;; converts to tokens); the full prompt is never a datom — it lives in the
;; blob store via `:seon.agent.turn/prompt-blob` below.
(schema/register! :seon.agent.turn/prompt-chars :int)
;; Observability capture — ALWAYS ON, no debug flag (observability.md).
;; `rendered-as-of` is the ONE coordinate tx-meta cannot provide: the
;; PRE-turn basis-t of the frozen db the prompt rendered from (other
;; agents' txs interleave on the shared conn, so the turn's own
;; creation-tx is NOT what the model saw). `(db/as-of conn t)` at this t
;; reproduces the structured context exactly.
(schema/register! :seon.agent.turn/rendered-as-of :int)
;; Blob REFS to the byte ground truth (three-tier rule): the prompt blob
;; is what the model actually SAW (survives render-code changes); the
;; reply blob is the raw LLM reply (not derivable from anything). Refs to
;; the `:my.blob/hash` projection entity — the text is never a datom.
(schema/register! :seon.agent.turn/prompt-blob :seon.db/ref)
(schema/register! :seon.agent.turn/reply-blob  :seon.db/ref)
;; WHY the turn errored, as a bounded edn/message string — present only
;; on an errored turn, so capture never depends on success.
(schema/register! :seon.agent.turn/error :string)
;; Honest record of the bounded LLM transport retry (the retry COUNT when
;; present; ABSENT = no retry — optional-is-absent).
(schema/register! :seon.agent.turn/llm-retries  :int)
;; Tier-2 provider telemetry, EDN-stringified (:map is unbridgeable — a :map
;; close-tx fails the schema bridge): the usage map + the unrecognized
;; top-level provider fields. Both ABSENT on a stub-LLM turn.
(schema/register! :seon.agent.turn/llm-usage    :string)
(schema/register! :seon.agent.turn/llm-meta     :string)
;; repl-mode telemetry. `:batch`: how many model-authored
;; result-claims were STRIPPED from this turn's reply at the boundary
;; (absent = none — optional-is-absent; the fabrication count survives even
;; though the fabrication itself does not enter the record). `:stream`:
;; the turn's `:seon.agent.turn/llm-usage` numbers are
;; CLIENT-SIDE estimates (the aborted stream lost the provider's usage
;; chunk) — marked so a reader never treats them as provider-reported.
(schema/register! :seon.agent.turn/results-stripped :int)
(schema/register! :seon.agent.turn/usage-estimated? :boolean)
(schema/register! :seon.agent.turn/evals        [:vector {:seon.db/component true} :seon.db/ref])

;; Entity shape. NB: seon.db validates per-ATTRIBUTE, not entity-level, so the
;; non-:optional entries below are documentation, not an enforced required-key
;; check — a tx omitting `at`/`status` still writes.
(schema/register! :seon.agent.turn
  [:map {:seon.db/entity true}
   [:seon.agent.turn/id           :seon.agent.turn/id]
   [:seon.agent.turn/at           :seon.agent.turn/at]
   [:seon.agent.turn/status       :seon.agent.turn/status]
   [:seon.agent.turn/run          {:optional true} :seon.agent.turn/run]
   [:seon.agent.turn/scheduled?   {:optional true} :seon.agent.turn/scheduled?]
   [:seon.agent.turn/prompt-chars {:optional true} :seon.agent.turn/prompt-chars]
   [:seon.agent.turn/rendered-as-of {:optional true} :seon.agent.turn/rendered-as-of]
   [:seon.agent.turn/prompt-blob  {:optional true} :seon.agent.turn/prompt-blob]
   [:seon.agent.turn/reply-blob   {:optional true} :seon.agent.turn/reply-blob]
   [:seon.agent.turn/error        {:optional true} :seon.agent.turn/error]
   [:seon.agent.turn/llm-retries  {:optional true} :seon.agent.turn/llm-retries]
   [:seon.agent.turn/llm-usage    {:optional true} :seon.agent.turn/llm-usage]
   [:seon.agent.turn/llm-meta     {:optional true} :seon.agent.turn/llm-meta]
   [:seon.agent.turn/results-stripped {:optional true} :seon.agent.turn/results-stripped]
   [:seon.agent.turn/usage-estimated? {:optional true} :seon.agent.turn/usage-estimated?]
   [:seon.agent.turn/evals        {:optional true} :seon.agent.turn/evals]])

;; ============================================================
;; Turn-level logging + render-input shaping.
;; ============================================================

(defn- log [agent-id turn-n stage & info]
  (seon-log/info-console!
    (str "seon.agent.turn/" agent-id)
    (str "turn " turn-n " ▸ " stage)
    (if (= 1 (count info)) (first info) (vec info))))

;; ============================================================
;; Observability capture helpers — always-on blob capture + the error
;; projection. Both are errors-as-values: a failed capture logs and
;; yields nil, NEVER wedges the turn (never-crash).
;; ============================================================

(defn ^:async ^:private capture-blob!
  "Best-effort blob capture of `content` — a turn-stampable ref or nil.

   `(await (my.blob/put! …))` with the given media hint; on success
   returns the `[:my.blob/hash h]` lookup-ref the turn entity stores
   (`:seon.agent.turn/prompt-blob` / `reply-blob`). Any failure — disk,
   projection tx, throw — logs a warning and returns nil so the turn
   proceeds without the ref. Content-addressed ⇒ idempotent."
  [content media]
  (try
    (let [{ok? :my.blob/ok? hash :my.blob/hash err :my.blob/error}
          (await (blob/put! {:my.blob/content (str content)
                             :my.blob/media   media}))]
      (if ok?
        [:my.blob/hash hash]
        (do (js/console.warn
              "[seon.agent.turn] blob capture failed (turn continues):" err)
            nil)))
    (catch :default e
      (js/console.warn
        "[seon.agent.turn] blob capture failed (turn continues):"
        (or (some-> e .-message) (str e)))
      nil)))

(def ^:private turn-error-max-chars 4096)

(defn- turn-error-str
  "Bounded `:seon.agent.turn/error` string for any failure value.

   Maps (an `:seon.ai/error`, a `:seon.db/error`) pr-str to edn; a thrown
   error keeps its best-effort message. Truncated to ~1K tokens so the
   datom stays a projection, never a dump."
  [x]
  (let [s (if (map? x)
            (try (pr-str x) (catch :default _ (str x)))
            (error/->message x))]
    (subs s 0 (min turn-error-max-chars (count s)))))

;; ============================================================
;; Turn index — the agent's running turn number, for logging. Derived:
;; count of ALL the agent's turns (across every run). Monotonic +
;; unique-per-turn; nothing stored.
;; ============================================================

(defn turn-index
  "The agent's NEXT turn number — count of every turn it owns.

   Walked agent → runs → turns. Used for log lines (uniqueness, not
   run-position). Derived, not persisted."
  {:malli/schema [:=> [:catn [:seon.agent/id :seon.agent/id]] :int]}
  [agent-id]
  (count (ctx/agent-turns agent-id nil)))

;; ============================================================
;; Prompt assembly.
;; ============================================================

(defn render-prompt
  "The agent's full LLM context, rendered as a bare String (sync).

   Thin delegate to [[seon.agent.ctx/render-context]] (the SINGLE producer the human
   web UI `seon.agent.debug/ctx-preview` also routes through, so the
   debug view and the model's prompt are byte-identical by construction).
   Renders against the frozen `db` value the loop pinned for this TURN (the
   one basis-t the bound-checks + render share, §8a); defaults to `@*conn*`
   when called without one."
  {:malli/schema [:function
                  [:=> [:catn [:seon.agent/id :seon.agent/id]] :string]
                  [:=> [:catn [:seon.agent/id :seon.agent/id] [:seon.db/db :any]] :string]]}
  ([agent-id] (render-prompt agent-id @db/*conn*))
  ([agent-id db]
   (ctx/render-context {:seon.agent/id agent-id :seon.db/db db})))

;; ============================================================
;; The turn bracket — open-turn! folds the prompt projection + the current
;; run ref (:seon.agent.turn/run) into the open-tx; close-turn! folds
;; telemetry. The turn manages ONLY `:seon.agent.turn/status` — the agent's
;; DERIVED state is a projection of its RUN (opened/closed by the loop and the
;; lifecycle functions), so a turn never touches agent state; it just runs.
;; ============================================================

(declare close-turn!)

(defn ^:async open-turn!
  "Open a STANDALONE `:seon.agent.turn`, run `body-fn`, close it.

   `prompt-text` is attached and
   the agent's current run STAMPED on `:seon.agent.turn/run` (the run's
   derived current-turn count counts it). This function owns turn identity:
   it allocates and commits the open row before invoking `body-fn` with the
   committed id. Only the pure transaction builder can retry. On success it
   returns the body result carrying `:seon.agent.turn/id`; if the body throws,
   it marks the committed turn `:error` and rejects with that id in ex-data.
   Touches NO agent state — the run lifecycle is the loop's / the functions'
   concern.

   When `scheduled?` is true, the turn is stamped `:seon.agent.turn/scheduled?
   true` (a cron-fire turn): it still carries the run stamp so its evals RENDER
   in the transcript, but [[seon.derive/run-turn-count]] EXCLUDES it from the
   run's work budget — a schedule fire never burns a turn from turn-limit.

   When `id-of-run` is present the open-tx LEADS with the WORK FENCE
   ([[seon.db/cas-assert]] on `:seon.agent/id`'s `:seon.agent/run`): a
   turn-open for a superseded/watchdog-closed run (the pointer moved or was
   retracted before the LLM call) aborts at the writer — no zombie turn entity
   lands, and the caller sees `ok? false`. If the open-tx fails (fence or
   otherwise) there is NO turn entity — returns the error envelope (no LLM
   call)."
  {:malli/schema [:=> [:catn [:turn-input :map] [:body-fn :any]] :any]}
  [{:seon.agent/keys [id]
    :seon.agent.run/keys [id-of-run]
    :seon.agent.turn/keys [prompt-text scheduled?
                           rendered-as-of prompt-blob]}
   body-fn]
  (let [conn db/*conn*
        turn-row
        (cond->
          {:seon.agent.turn/at           (js/Date.)
           :seon.agent.turn/status       :running
           :seon.agent.turn/prompt-chars (count (str prompt-text))}
          rendered-as-of (assoc :seon.agent.turn/rendered-as-of rendered-as-of)
          prompt-blob    (assoc :seon.agent.turn/prompt-blob prompt-blob)
          scheduled?  (assoc :seon.agent.turn/scheduled? true)
          id-of-run  (assoc :seon.agent.turn/run [:seon.agent.run/id id-of-run]))
        allocation
        (await
          (db.id/allocate!
            {::db.id/allocations
             [{::db.id/key ::turn-allocation
               ::db.id/identity-attr :seon.agent.turn/id}]
             ::db.id/transaction-builder
             (fn [{turn-id ::turn-allocation}]
               {:seon.db/tx-data
                (cond-> []
                  id-of-run
                  (conj (db/cas-assert [:seon.agent/id id] :seon.agent/run
                                       [:seon.agent.run/id id-of-run]))
                  true
                  (conj (assoc turn-row :seon.agent.turn/id turn-id)))})
             :seon.db/conn conn}))]
    (if (false? (:seon.db/ok? allocation))
      allocation
      (let [turn-id (get-in allocation [::db.id/ids ::turn-allocation])]
        (await
          (db/with-tx-context
            {::current-id turn-id}
            (fn ^:async close-allocated-turn! []
              (await (close-turn! id turn-id body-fn)))))))))

(defn ^:async ^:private close-turn!
  "Close an allocated turn while retaining its committed identity.

   Invokes `body-fn` with the committed turn id, closes `:done` on success,
   and marks `:error` when the body throws. A body failure is rethrown as an
   ex-info whose data carries `:seon.agent.turn/id`, preserving the historical
   rejection contract while making the already-committed row discoverable.
   Allocation failure is handled before this function."
  [id id-of-turn body-fn]
  (try
    (let [result (assoc (or (await (body-fn id-of-turn)) {})
                        :seon.agent.turn/id id-of-turn)
          close  (await
                   (db/transact!
                     {:seon.db/tx-data
                      [(merge {:seon.agent.turn/id id-of-turn :seon.agent.turn/status :done}
                              (select-keys result [:seon.agent.turn/status
                                                   :seon.agent.turn/llm-retries
                                                   :seon.agent.turn/llm-usage
                                                   :seon.agent.turn/llm-meta
                                                   :seon.agent.turn/results-stripped
                                                   :seon.agent.turn/usage-estimated?
                                                   :seon.agent.turn/reply-blob
                                                   :seon.agent.turn/error]))]}))]
      (when (false? (:seon.db/ok? close))
        (js/console.error
          (str "seon.agent.turn/close-turn!: turn close-tx FAILED for "
               id " turn " id-of-turn ". " (pr-str (:seon.db/error close)))))
      result)
    (catch :default e
      ;; Mark the turn :error best-effort, then preserve the pre-allocation
      ;; propagation contract. Scheduled/direct callers still observe a
      ;; rejected Promise; the committed id travels with that rejection.
      (let [message (turn-error-str e)]
        (try
          (await (db/transact!
                   {:seon.db/tx-data
                    [{:seon.agent.turn/id     id-of-turn
                      :seon.agent.turn/status :error
                      :seon.agent.turn/error  message}]}))
          (catch :default _ nil))
        (throw
          (ex-info message
                   {:seon.agent.turn/id     id-of-turn
                    :seon.agent.turn/status :error
                    :seon.error/data        message}
                   e))))))

;; ============================================================
;; The LLM call + eval. ask-and-eval-reply! parses the reply and
;; eval-batches the forms — the raw reply is NEVER folded into a self→self
;; message (notes-to-self are eval narration, not message rows).
;; ============================================================

(defn ^:async ^:private ask-and-eval-reply!
  "Internal — the successful-LLM-reply half of `ask-and-eval!`: parse the
   reply and eval-batch the forms. `id` / `id-of-turn` are LOCALS threaded
   down from `run-turn!` (captured before the LLM await), so the always-on
   blob capture pairs this verbatim reply with the same turn's prompt."
  [resp id id-of-turn compile-state run-id stream? start-ns]
  (let [raw-reply  (or (:text resp) "")
        ;; repl-mode reply-boundary fix-up: DELETE every model-authored
        ;; result-claim BEFORE persist + eval (`:batch`; `:stream`
        ;; structurally has none — its stream aborted at the
        ;; first form's close, so there is no fabricated tail — but the
        ;; strip is idempotent and safe on both). The forms eval as normal;
        ;; the next turn's transcript interleaves the REAL `⟹` rows.
        {reply-text :seon.agent.ctx/strip-text
         n-stripped :seon.agent.ctx/strip-count}
        (ctx/strip-result-claims raw-reply)
        ;; Always-on reply capture — the (cleaned) reply is the byte ground
        ;; truth that goes to the blob store (best-effort; a lost capture
        ;; never wedges the turn). The fabrication never enters the record.
        reply-blob (await (capture-blob! reply-text :reply))
        ;; Link the reply blob onto the turn NOW, not only at close-turn!:
        ;; a turn that dies mid-eval (e.g. a `:core` crash under the
        ;; on-core-error dial) otherwise strands the captured blob with no
        ;; ref — `agent-debug/turn` can't read the raw reply back. Best-effort
        ;; like the capture; close-turn!'s later merge re-asserts the same
        ;; ref (idempotent upsert). Drill finding, error-workflow 2026-07-05.
        _          (when reply-blob
                     (try
                       (await (db/transact!
                                {:seon.db/tx-data
                                 [{:seon.agent.turn/id         id-of-turn
                                   :seon.agent.turn/reply-blob reply-blob}]}))
                       (catch :default e
                         (js/console.warn
                           "[seon.agent.turn] eager reply-blob link failed (turn continues):"
                           e))))
        parsed     (repl-internal/parse-forms reply-text)
        ;; `:stream` single-form close (repl-milestone rung-0 verdict, 2026-07-10):
        ;; the stream aborts at the FIRST complete form, but the delta that
        ;; completed it can carry a tail that parses into extra entries
        ;; (typically `:read` errors from a partial next line). The turn is
        ;; ONE form: keep everything through the first `:form` entry and
        ;; treat the tail as prose — it stays byte-intact in the reply blob,
        ;; it just never evals. `:batch` evals the full parse as before.
        parsed     (if stream?
                     (let [i (reduce (fn [idx e]
                                       (if (= :form (:seon.repl/kind e))
                                         (reduced idx)
                                         (inc idx)))
                                     0 parsed)]
                       (if (< i (count parsed))
                         (vec (take (inc i) parsed))
                         parsed))
                     parsed)
        ;; The batch starts where the agent IS (the derived current-ns the
        ;; cursor + namespaces block already show), NOT the home ns — an
        ;; `(in-ns …)` in a PRIOR turn must hold across the turn boundary
        ;; (namespaces-milestone rung-1 root cause, 2026-07-10: seeding home here made every
        ;; `:stream` turn silently define into my.agent.*, cursor flip-flop,
        ;; ns-interns nil, cross-ns resolution failures).
        batch      (await (seval/eval-batch! compile-state parsed
                                             (or start-ns (home/home-ns id))
                                             id id-of-turn run-id))]
    (cond->
      ;; ATTEMPTED forms (ok + failed), not just n-ok: the loop's zero-forms
      ;; halt means "no actionable forms" — NOT "every form errored". A
      ;; failed eval must yield a next turn that shows the error.
      {:seon.agent/eval-count (+ (:seon.eval/n-ok batch)
                                 (:seon.eval/n-fail batch))}
      reply-blob      (assoc :seon.agent.turn/reply-blob reply-blob)
      (pos? n-stripped) (assoc :seon.agent.turn/results-stripped n-stripped))))

;; LLM retry tuning. The agent loop is the SOLE retry authority (the
;; adapters ship `maxRetries 0`); these shape the exponential-backoff
;; strategy fed to [[seon.retry/with-retry!]]. `SEON_AI_MAX_RETRIES`
;; (default 4) caps the retry COUNT; the per-wait clamp + total-duration
;; ceiling bound worst-case latency so a transient blip never hangs the
;; run loop.
(def ^:private llm-retry-base-ms      500)
(def ^:private llm-retry-factor       2)
(def ^:private llm-retry-jitter       0.5)
(def ^:private llm-retry-max-delay-ms 20000)
(def ^:private llm-retry-total-cap-ms 60000)
(def ^:private llm-retry-default-n    4)

(defn- llm-retryable?
  "True when an LLM `resp` failed with a TRANSIENT provider error worth a
   bounded retry: a TRANSPORT-shaped fetch throw (`:seon.ai/transport?`),
   HTTP 429 (rate limit), or any HTTP 5xx (502/503/504 overload/gateway).
   A non-transient error — HTTP 4xx other than 429 (400/401/403/404 are
   real, surface them), a refusal, an unparseable body, a config gap — and
   a wall-clock timeout (already burned its full budget) are NOT retried.
   A success (no `:seon.ai/error`) is never retried."
  [resp]
  (let [err    (:seon.ai/error resp)
        status (:seon.ai/status err)]
    (boolean
      (and err
           (or (true? (:seon.ai/transport? err))
               (= 429 status)
               (and (int? status) (<= 500 status 599)))))))

(defn- llm-retry-strategy
  "The backoff strategy for an LLM provider retry: exponential (base ×2),
   jittered, per-wait-clamped, capped at the agent's effective max-retries and
   a total-duration ceiling. The retry COUNT is READ per agent via
   [[seon.ai/agent-max-retries]] (config-driven agent-init, move 10) —
   `::agent-max-retries :inherit` (the default) → `llm-retry-default-n` (4),
   the same value the old `SEON_AI_MAX_RETRIES` env read produced = parity."
  [id]
  (-> (retry/multiplicative-strategy llm-retry-base-ms llm-retry-factor)
      (retry/randomize-strategy llm-retry-jitter)
      (retry/clamp-delay llm-retry-max-delay-ms)
      (retry/max-retries (ai/agent-max-retries id llm-retry-default-n))
      (retry/max-duration llm-retry-total-cap-ms)))

(defn ^:async ^:private bounded-llm-attempt!
  "ONE adapter attempt under the per-attempt wall-clock cap.

   Races `(llm-fn prompt-text)` against [[seon.config/llm-attempt-timeout-ms]]
   (`SEON_LLM_ATTEMPT_TIMEOUT_MS`, default 2 min) via the ONE racer
   ([[seon.eval/race-timeout]]) — the inner bound that keeps a single attempt
   from parking the turn when the adapter's own `:seon.ai/timeout-ms` is
   unset/huge. A timed-out attempt resolves to a `:seon.ai/error` VALUE
   (`:seon.ai/timeout? true` — never a throw), so [[llm-retryable?]]
   classifies it exactly like an adapter-side timeout. The cap frees the
   AWAITER only — the underlying request keeps running (no preemption);
   a late settler's turn writes are aborted by the run's in-tx CAS
   work-fence.

   `stream?` (repl-mode `:stream`) hands the llm-fn the WIDENED map arg
   `{:seon.ai/ctx … :seon.ai/stream? true}` so the adapter consumes the
   SDK stream and aborts at the first complete form; `false` passes the
   bare ctx string (back-compat batch shape)."
  [llm-fn prompt-text stream?]
  (let [ms  (config/llm-attempt-timeout-ms)
        arg (if stream?
              {:seon.ai/ctx prompt-text :seon.ai/stream? true}
              prompt-text)
        v   (await (seval/race-timeout (llm-fn arg) ms))]
    (if (seval/timed-out? v)
      {:seon.ai/error {:seon.ai/msg      (str "LLM attempt exceeded the per-attempt "
                                              "cap (" ms "ms, SEON_LLM_ATTEMPT_"
                                              "TIMEOUT_MS) — awaiter freed; the "
                                              "request may still settle in the "
                                              "background (CAS-fenced)")
                       :seon.ai/timeout? true}}
      v)))

(defn ^:async ^:private call-llm!
  "Internal — `(llm-fn prompt-text)` with bounded retry-with-backoff on a
   TRANSIENT provider failure ([[llm-retryable?]]). Delegates the mechanics
   to [[seon.retry/with-retry!]] (this is the SOLE LLM retry authority —
   no parallel path). Each attempt is individually wall-clock-capped
   ([[bounded-llm-attempt!]]), so retry count, total backoff AND single-attempt
   latency are all bounded — a call-llm! can never park the turn. Honors a
   server `Retry-After` (`:seon.ai/retry-after-ms`, clamped to the per-wait
   ceiling) over the strategy's delay. On exhaustion the last (error) resp
   flows through unchanged — the turn surfaces its `:seon.ai/error` as a
   value, never a throw. When any retry fired, the resp carries
   `:seon.agent.turn/llm-retries n` so the turn record is honest."
  [id id-of-turn llm-fn prompt-text stream?]
  (let [{:seon.retry/keys [result retries]}
        (await
          (retry/with-retry!
            {:seon.retry/thunk    (fn [] (bounded-llm-attempt! llm-fn prompt-text stream?))
             :seon.retry/strategy (llm-retry-strategy id)
             :seon.retry/retry?   llm-retryable?
             :seon.retry/override (fn [resp]
                                    (some-> (get-in resp [:seon.ai/error
                                                          :seon.ai/retry-after-ms])
                                            (min llm-retry-max-delay-ms)))
             :seon.retry/on-retry
             (fn [{:seon.retry/keys [attempt delay-ms result]}]
               (log id id-of-turn "llm transient error — retry"
                    (str attempt " in " delay-ms "ms — "
                         (get-in result [:seon.ai/error :seon.ai/msg]))))}))]
    (cond-> result
      (pos? retries) (assoc :seon.agent.turn/llm-retries retries))))

(defn ^:async ask-and-eval!
  "Call the LLM, parse the reply, eval-batch the forms (turn body).

   The body of `open-turn!`: calls the LLM (via [[call-llm!]]), parses the reply,
   eval-batches the forms (each as a `:seon.agent.turn/evals` component), and
   returns `{:seon.agent/eval-count n}` (plus optional telemetry) for
   `open-turn!` to fold into the close-tx. An LLM-call failure
   (`:seon.ai/error`) closes the turn `:status :error` (render derives a
   system line from the status — no self→self message row)."
  {:malli/schema [:=> [:catn [:input :map]] :map]}
  [{:seon.agent/keys [id llm-fn compile-state]
    run-id :seon.agent.run/id
    stream? :seon.ai/stream?
    start-ns :seon.eval/start-ns
    :seon.agent.turn/keys  [id-of-turn turn-idx prompt-text]}]
  (let [resp    (await (call-llm! id id-of-turn llm-fn prompt-text (boolean stream?)))
        retries (:seon.agent.turn/llm-retries resp)
        raw     (:seon.ai/raw resp)
        usage   (:seon.ai/usage raw)
        estimated? (:seon.ai/estimated? raw)
        pfields (:seon.ai/provider-fields raw)]
    (if-let [err (:seon.ai/error resp)]
      (do
        (log id turn-idx "llm error — turn :error"
             (str (when retries (str "(after " retries " retry) "))
                  (:seon.ai/msg err)))
        (cond->
          {:seon.agent/eval-count 0
           :seon.agent.turn/status :error
           ;; Capture the failure as data — the error record must not
           ;; depend on turn success (observability.md).
           :seon.agent.turn/error  (turn-error-str err)}
          retries (assoc :seon.agent.turn/llm-retries retries)))
      (cond-> (await (ask-and-eval-reply! resp id id-of-turn compile-state run-id
                                          (boolean stream?) start-ns))
        retries     (assoc :seon.agent.turn/llm-retries retries)
        (seq usage) (assoc :seon.agent.turn/llm-usage (pr-str usage))
        estimated?  (assoc :seon.agent.turn/usage-estimated? true)
        (seq pfields) (assoc :seon.agent.turn/llm-meta (pr-str pfields))))))

(defn ^:async run-turn!
  "One full turn end-to-end. Map-in / map-out.

   Input keys:
     :seon.agent/id             agent id string
     :seon.agent/llm-fn         ctx-string -> Promise<{:text \"…\"}>
     :seon.agent/compile-state  bootstrap compile-state
     :seon.agent.run/id         the OPEN run this turn belongs to (the loop
                                passes its run-id; stamped on the turn)
     :seon.db/db                the FROZEN db value the loop pinned for this
                                turn (§8a — the prompt render + the loop's
                                bound-checks share ONE basis-t); defaults to
                                `@*conn*` when absent (gym/bootstrap callers)

   Wraps the pipeline in a `with-tx-context` scope so every transact (incl.
   the per-form txs inside `eval-batch!`) auto-tags with the causality
   bundle. Returns the closed turn entity pulled with evals inlined, plus
   `:seon.agent/eval-count`. On catastrophic error returns
   `{:seon.agent.turn/status :error :seon.error/data <str>}` and retains
   `:seon.agent.turn/id` when the turn row was already committed."
  {:malli/schema [:=> [:catn [:input :map]] :map]}
  [{:seon.agent/keys [id llm-fn compile-state] run-id :seon.agent.run/id db :seon.db/db}]
  (let [db         (or db @db/*conn*)
        ;; repl-mode read off the DB DATOM (config-through-DB), pinned to
        ;; the SAME frozen db the prompt renders from — the turn loop never
        ;; reads the config accessor.
        stream?    (= :stream (ctx/repl-mode db))
        turn-idx   (turn-index id)
        prompt     (render-prompt id db)
        full-prompt (ai/debug-full-prompt {:seon.ai/ctx prompt})
        ;; Always-on observability capture: the frozen db's basis-t (the
        ;; coordinate that makes the context re-derivable via as-of) + the
        ;; assembled prompt verbatim as a blob. Both land on the turn's
        ;; open-tx; a failed blob write yields nil and the turn proceeds.
        rendered-as-of (db/basis-t db)
        ;; Where the batch STARTS: the agent's derived current-ns over the
        ;; SAME frozen db the prompt rendered from — so the ns the cursor
        ;; showed the agent is the ns its forms run in (an in-ns in a prior
        ;; turn holds; ctx/current-ns already falls back to home).
        start-ns   (let [c (ctx/current-ns {:seon.agent/id id :seon.db/db db})]
                     (when c (symbol (if (keyword? c) (name c) (str c)))))
        prompt-blob    (await (capture-blob! full-prompt :prompt))]
    ;; ctx-tokens = the assembled context ONLY; the system text rides the
    ;; adapter's system message, so it is reported as its own count here
    ;; (repl-milestone rung-0 defect: the old line silently under-reported the fixed
    ;; prefix by the system prompt's size).
    (try
      (let [result (await
                     (db/with-agent id
                       (fn []
                         (db/with-tx-context
                           {:seon.db/user [:seon.agent/id id]
                            :seon.db/process
                            [:seon.db.process/id :seon.db.process/repl]}
                           (fn []
                             (open-turn!
                               (cond->
                                 {:seon.agent/id           id
                                  :seon.agent.run/id-of-run run-id
                                  :seon.agent.turn/prompt-text   full-prompt
                                  :seon.agent.turn/rendered-as-of rendered-as-of}
                                 prompt-blob
                                 (assoc :seon.agent.turn/prompt-blob prompt-blob))
                               (fn ^:async run-allocated-turn! [turn-id]
                                 (log id turn-idx "open" turn-id "+"
                                      (tokens/estimate prompt) "ctx-tokens" "+"
                                      (tokens/estimate
                                        (ai/effective-system-prompt {}))
                                      "system-tokens")
                                 (await
                                   (ask-and-eval!
                                     {:seon.agent/id            id
                                      :seon.agent/llm-fn        llm-fn
                                      :seon.agent/compile-state compile-state
                                      :seon.agent.run/id        run-id
                                      :seon.ai/stream?          stream?
                                      :seon.eval/start-ns       start-ns
                                      :seon.agent.turn/id-of-turn turn-id
                                      :seon.agent.turn/turn-idx turn-idx
                                      :seon.agent.turn/prompt-text prompt})))))))))]
        (if (false? (:seon.db/ok? result))
          ;; The open-tx FAILED — there is NO turn entity to pull (the
          ;; lookup-ref is unresolvable). Return the same `:error` shape the
          ;; catch returns, so the loop closes the run `:error` instead of
          ;; pulling nil → `{:seon.agent/eval-count 0}` (no status) and
          ;; recur-ing `:turn-ok` forever (a tight retry storm on a write
          ;; outage). run-turn! ALWAYS carries a status on its error paths.
          (do (log id turn-idx "open-turn! failed → turn :error"
                   (pr-str (:seon.db/error result)))
              {:seon.agent.turn/status :error
               :seon.error/data        (pr-str (:seon.db/error result))})
          (let [turn-id (:seon.agent.turn/id result)
                n-ok (or (:seon.agent/eval-count result) 0)]
            (log id turn-idx (name (or (:seon.agent.turn/status result) :done)) n-ok
                 (if (:seon.agent.turn/status result) "llm-error" "ok"))
            (assoc (db/pull {:seon.db/pull-pattern
                             '[* {:seon.agent.turn/evals [*]}]
                             :seon.db/ref [:seon.agent.turn/id turn-id]})
                   :seon.agent/eval-count n-ok))))
      (catch :default e
        ;; Catastrophic turn failure → return the :error shape. State is the
        ;; loop's concern (its finally resets :idle); the turn never touches it.
        ;; A body failure carries the identity already committed by open-turn!.
        (let [failure-data (ex-data e)
              turn-id     (:seon.agent.turn/id failure-data)]
          (log id turn-idx "run-turn! error" (str e))
          (cond-> {:seon.agent.turn/status :error
                   :seon.error/data        (str e)}
            turn-id (assoc :seon.agent.turn/id turn-id)))))))
