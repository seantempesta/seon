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
     - `render-prompt` / `prefetch-and-render-prompt!` — ctx assembly
     - `turn-index`     — the agent's running turn number (debug-file naming)

   Dependency direction (acyclic): it references `:seon.agent.run/*` keywords
   (global registry, no require) and transacts via `seon.db` directly, so it
   does NOT require `seon.agent` (which would cycle: `seon.agent.loop`
   requires both). It MAY require ctx / eval / message / render."
  (:require
    [clojure.string :as str]
    [seon.ai :as ai]
    [seon.ctx :as ctx]
    [seon.ctx.relevant :as ctx-relevant]
    [seon.db :as db]
    [seon.debug :as debug]
    [seon.embed :as embed]
    [seon.embed.stash :as embed-stash]
    [seon.eval :as seval]
    [seon.log :as seon-log]
    [seon.repl :as repl]
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

(schema/register! :seon.agent.turn/id           [:and {:seon.db/identity true} :seon.db/id])
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
;; Three-tier storage: the datom carries the prompt's char count + a file
;; pointer (blob tier); the full prompt is never a datom.
(schema/register! :seon.agent.turn/prompt-chars :int)
(schema/register! :seon.agent.turn/prompt-file  :string)
;; :seon.agent.turn/debug-dir (the per-turn capture-dir pointer, absent when
;; capture off) is registered by its writer, [[seon.debug]] (required here).
;; Honest record of the bounded LLM transport retry (always 1 when present;
;; ABSENT = no retry — optional-is-absent).
(schema/register! :seon.agent.turn/llm-retries  :int)
;; Tier-2 provider telemetry, EDN-stringified (:map is unbridgeable — a :map
;; close-tx fails the schema bridge): the usage map + the unrecognized
;; top-level provider fields. Both ABSENT on a stub-LLM turn.
(schema/register! :seon.agent.turn/llm-usage    :string)
(schema/register! :seon.agent.turn/llm-meta     :string)
(schema/register! :seon.agent.turn/evals        [:vector {:seon.db/component true} :seon.db/ref])

(schema/register! :seon.agent.turn
  [:map {:seon.db/entity true}
   [:seon.agent.turn/id           :seon.agent.turn/id]
   [:seon.agent.turn/at           :seon.agent.turn/at]
   [:seon.agent.turn/status       :seon.agent.turn/status]
   [:seon.agent.turn/run          {:optional true} :seon.agent.turn/run]
   [:seon.agent.turn/prompt-chars {:optional true} :seon.agent.turn/prompt-chars]
   [:seon.agent.turn/prompt-file  {:optional true} :seon.agent.turn/prompt-file]
   [:seon.agent.turn/debug-dir    {:optional true} :seon.agent.turn/debug-dir]
   [:seon.agent.turn/llm-retries  {:optional true} :seon.agent.turn/llm-retries]
   [:seon.agent.turn/llm-usage    {:optional true} :seon.agent.turn/llm-usage]
   [:seon.agent.turn/llm-meta     {:optional true} :seon.agent.turn/llm-meta]
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
;; Turn index — the agent's running turn number, for debug-file naming +
;; logging. Derived: count of ALL the agent's turns (across every run).
;; Monotonic + unique-per-turn; nothing stored.
;; ============================================================

(defn turn-index
  "The agent's NEXT turn number — `count` of every `:seon.agent.turn` the
   agent owns (walked agent → runs → turns). Used for debug-capture file
   names + log lines (uniqueness, not run-position). Derived, not persisted."
  {:malli/schema [:=> [:catn [:seon.agent/id :seon.db/id]] :int]}
  [agent-id]
  (count (ctx/agent-turns agent-id nil)))

;; ============================================================
;; Prompt assembly (sync, with an optional async embedding prefetch).
;; ============================================================

(defn render-prompt
  "Sync — the agent's full LLM context, rendered as a bare String. Thin
   delegate to [[seon.ctx/render-context]] (the SINGLE producer the human
   inspector `seon.agent.inspect/ctx-preview` also routes through, so the
   debug view and the model's prompt are byte-identical by construction).
   Renders against the frozen `db` value the loop pinned for this TURN (the
   one basis-t the bound-checks + render share, §8a); defaults to `@*conn*`
   when called without one."
  ([agent-id] (render-prompt agent-id @db/*conn*))
  ([agent-id db]
   (ctx/render-context {:seon.agent/id agent-id :seon.db/db db})))

(defn embed-retrieval-on?
  "True when embedding-retrieval is enabled — the env var `SEON_EMBED` is
   PRESENT (any value). The SAME single switch the wire-server reads, so one
   env var gates the feature across both processes. UNSET ⇒ the prefetch
   never fires and `render-prompt` runs the byte-identical-OFF path."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (some? (.. js/process -env -SEON_EMBED)))

(defn ^:async prefetch-and-render-prompt!
  "Render this turn's prompt over the frozen `db` value (the basis-t the loop
   pinned for the turn), OPTIONALLY prefetching embedding-retrieval hits first.
   DEFAULT-OFF (byte-identical): when [[embed-retrieval-on?]] is false this is
   exactly `(render-prompt agent-id db)`. When ON: derive the query from the
   frozen db's latest live inbound, KNN over the WHOLE embedding index
   (kind-general), stash the hits, then run the SYNC `render-prompt` over the
   SAME db inside that scope so the `:relevant-source` section reads them
   without making `assemble-context` async. FAIL-SOFT to nil hits on any error
   (section renders blank)."
  [agent-id db]
  (if-not (embed-retrieval-on?)
    (render-prompt agent-id db)
    (let [query (ctx/retrieval-query {:seon.db/db db :seon.agent/id agent-id})
          hits  (if (str/blank? query)
                  nil
                  (-> (.then
                        (embed/search-pull
                          {:seon.embed/query query
                           :seon.embed/k ctx-relevant/top-k
                           :seon.embed/db db})
                        (fn [{:seon.embed/keys [hits]}] hits))
                      (.catch (fn [e]
                                (js/console.warn
                                  "[seon.agent.turn] embed prefetch failed (fail-soft → no hits):"
                                  (or (.-message e) (str e)))
                                nil))))
          hits  (await hits)]
      (embed-stash/with-hits hits #(render-prompt agent-id db)))))

;; ============================================================
;; The turn bracket — open-turn! folds the prompt projection + the current
;; run ref (:seon.agent.turn/run) into the open-tx; close-turn! folds
;; telemetry. The turn manages ONLY `:seon.agent.turn/status` — the agent's
;; DERIVED state is a projection of its RUN (opened/closed by the loop and the
;; lifecycle verbs), so a turn never touches agent state; it just runs.
;; ============================================================

(declare close-turn!)

(defn ^:async open-turn!
  "Open a STANDALONE `:seon.agent.turn` entity with `prompt-text` attached and
   the agent's current run STAMPED on `:seon.agent.turn/run` (the run's
   derived current-turn count counts it). Awaits `body-fn` (a 0-arg thunk
   returning Promise<map>) via `close-turn!`. Returns whatever `body-fn`
   returned, so the caller can read `:seon.agent/eval-count`. Touches NO agent
   state — the run lifecycle is the loop's / the verbs' concern.

   When `id-of-run` is present the open-tx LEADS with the WORK FENCE
   ([[seon.db/cas-assert]] on `:seon.agent/id`'s `:seon.agent/run`): a
   turn-open for a superseded/watchdog-closed run (the pointer moved or was
   retracted before the LLM call) aborts at the writer — no zombie turn entity
   lands, and the caller sees `ok? false`. If the open-tx fails (fence or
   otherwise) there is NO turn entity — returns the error envelope (no LLM
   call)."
  [{:seon.agent/keys [id]
    :seon.agent.run/keys [id-of-run]
    :seon.agent.turn/keys [id-of-turn prompt-text prompt-file]}
   body-fn]
  (let [turn-row
        (cond->
          {:seon.agent.turn/id           id-of-turn
           :seon.agent.turn/at           (js/Date.)
           :seon.agent.turn/status       :running
           :seon.agent.turn/prompt-chars (count (str prompt-text))}
          prompt-file (assoc :seon.agent.turn/prompt-file prompt-file)
          id-of-run  (assoc :seon.agent.turn/run [:seon.agent.run/id id-of-run]))
        open-result
        (await
          (db/transact!
            {:seon.db/tx-data
             (if id-of-run
               [(db/cas-assert [:seon.agent/id id] :seon.agent/run
                               [:seon.agent.run/id id-of-run])
                turn-row]
               [turn-row])}))]
    (if (false? (:seon.db/ok? open-result))
      open-result
      (close-turn! id id-of-turn body-fn))))

(defn ^:async ^:private close-turn!
  "Internal — the body of `open-turn!` after the open-tx succeeded. await
   body-fn, close the turn `:status :done` on success, flip it to `:error` on
   throw (then re-throw so the loop sees the failure). Touches ONLY the turn
   status; the agent's derived state follows its RUN, never the turn."
  [id id-of-turn body-fn]
  (try
    (let [result (await (body-fn))
          close  (await
                   (db/transact!
                     {:seon.db/tx-data
                      [(merge {:seon.agent.turn/id id-of-turn :seon.agent.turn/status :done}
                              (select-keys result [:seon.agent.turn/status
                                                   :seon.agent.turn/llm-retries
                                                   :seon.agent.turn/llm-usage
                                                   :seon.agent.turn/llm-meta
                                                   :seon.agent.turn/debug-dir]))]}))]
      (when (false? (:seon.db/ok? close))
        (js/console.error
          (str "seon.agent.turn/close-turn!: turn close-tx FAILED for "
               id " turn " id-of-turn ". " (pr-str (:seon.db/error close)))))
      result)
    (catch :default e
      ;; Mark the turn :error (best-effort) and re-throw. The agent's state
      ;; is the loop's concern — its catch/finally resets to :idle.
      (try
        (await (db/transact!
                 {:seon.db/tx-data
                  [{:seon.agent.turn/id id-of-turn :seon.agent.turn/status :error}]}))
        (catch :default _ nil))
      (throw e))))

;; ============================================================
;; The LLM call + eval. ask-and-eval-reply! parses the reply and
;; eval-batches the forms — the raw reply is NEVER folded into a self→self
;; message (notes-to-self are eval narration, not message rows).
;; ============================================================

(defn ^:async ^:private ask-and-eval-reply!
  "Internal — the successful-LLM-reply half of `ask-and-eval!`: parse the
   reply and eval-batch the forms. `id` / `turn-idx` / `id-of-turn` are
   LOCALS threaded down from `run-turn!` (captured before the LLM await), so
   debug capture pairs this verbatim reply with the same turn's prompt. When
   capture is ON, the returned map carries `:seon.agent.turn/debug-dir`."
  [resp id id-of-turn turn-idx compile-state run-id]
  (let [reply-text (or (:text resp) "")
        debug-dir  (debug/capture-response! id turn-idx id-of-turn
                                            reply-text resp)
        parsed     (repl/parse-forms reply-text)
        batch      (await (seval/eval-batch! compile-state parsed
                                             (ctx/home-ns id) id id-of-turn run-id))]
    (cond->
      ;; ATTEMPTED forms (ok + failed), not just n-ok: the loop's zero-forms
      ;; halt means "no actionable forms" — NOT "every form errored". A
      ;; failed eval must yield a next turn that shows the error.
      {:seon.agent/eval-count (+ (:seon.eval/n-ok batch)
                                 (:seon.eval/n-fail batch))}
      debug-dir (assoc :seon.agent.turn/debug-dir debug-dir))))

(def llm-transport-retry-backoff-ms
  "Backoff before the single transport-error LLM retry. Small on purpose: a
   transient \"fetch failed\" usually heals immediately."
  2000)

(defn- transport-error?
  "True when `resp` failed TRANSPORT-shaped (`:seon.ai/transport?` — the
   provider fetch threw before any HTTP status). HTTP 4xx/5xx, parse
   failures, and wall-clock timeouts are NOT transport errors."
  [resp]
  (true? (get-in resp [:seon.ai/error :seon.ai/transport?])))

(defn ^:async ^:private call-llm!
  "Internal — `(llm-fn prompt-text)` with ONE bounded retry on a
   transport-shaped provider failure. When the retry fires, the resp carries
   `:seon.agent.turn/llm-retries 1` so the turn record is honest."
  [id id-of-turn llm-fn prompt-text]
  (let [resp (await (llm-fn prompt-text))]
    (if-not (transport-error? resp)
      resp
      (do
        (log id id-of-turn "llm transport error — one retry in"
             (str llm-transport-retry-backoff-ms "ms — "
                  (get-in resp [:seon.ai/error :seon.ai/msg])))
        (await (js/Promise.
                 (fn [resolve]
                   (js/setTimeout resolve llm-transport-retry-backoff-ms))))
        (assoc (await (llm-fn prompt-text))
               :seon.agent.turn/llm-retries 1)))))

(defn ^:async ask-and-eval!
  "Body of `open-turn!`. Calls the LLM (via [[call-llm!]]), parses the reply,
   eval-batches the forms (each as a `:seon.agent.turn/evals` component), and
   returns `{:seon.agent/eval-count n}` (plus optional telemetry) for
   `open-turn!` to fold into the close-tx. An LLM-call failure
   (`:seon.ai/error`) closes the turn `:status :error` (render derives a
   system line from the status — no self→self message row)."
  [{:seon.agent/keys [id llm-fn compile-state]
    run-id :seon.agent.run/id
    :seon.agent.turn/keys  [id-of-turn turn-idx prompt-text]}]
  (let [resp    (await (call-llm! id id-of-turn llm-fn prompt-text))
        retries (:seon.agent.turn/llm-retries resp)
        raw     (:seon.ai/raw resp)
        usage   (:seon.ai/usage raw)
        pfields (:seon.ai/provider-fields raw)]
    (if-let [err (:seon.ai/error resp)]
      (do
        (log id turn-idx "llm error — turn :error"
             (str (when retries (str "(after " retries " retry) "))
                  (:seon.ai/msg err)))
        (cond->
          {:seon.agent/eval-count 0
           :seon.agent.turn/status :error}
          retries (assoc :seon.agent.turn/llm-retries retries)))
      (cond-> (await (ask-and-eval-reply! resp id id-of-turn turn-idx compile-state run-id))
        retries     (assoc :seon.agent.turn/llm-retries retries)
        (seq usage) (assoc :seon.agent.turn/llm-usage (pr-str usage))
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
   `{:seon.agent.turn/status :error :seon.error/data <str>}`."
  [{:seon.agent/keys [id llm-fn compile-state] run-id :seon.agent.run/id db :seon.db/db}]
  (let [db         (or db @db/*conn*)
        turn-id    (db/new-id!)
        turn-idx   (turn-index id)
        prompt     (await (prefetch-and-render-prompt! id db))
        full-prompt (ai/debug-full-prompt {:seon.ai/ctx prompt})
        prompt-file (debug/capture-prompt! id turn-idx turn-id full-prompt)]
    (log id turn-idx "open" turn-id "+" (count prompt) "ctx-chars")
    (try
      (let [result (await
                     (db/with-agent id
                       (fn []
                         (db/with-tx-context
                           {:seon.db/agent-id   id
                            :seon.db/turn-id    turn-id
                            :seon.db/origin     :system}
                           (fn []
                             (open-turn!
                               (cond->
                                 {:seon.agent/id           id
                                  :seon.agent.run/id-of-run run-id
                                  :seon.agent.turn/id-of-turn    turn-id
                                  :seon.agent.turn/prompt-text   full-prompt}
                                 prompt-file
                                 (assoc :seon.agent.turn/prompt-file prompt-file))
                               #(ask-and-eval! {:seon.agent/id            id
                                                :seon.agent/llm-fn        llm-fn
                                                :seon.agent/compile-state compile-state
                                                :seon.agent.run/id        run-id
                                                :seon.agent.turn/id-of-turn     turn-id
                                                :seon.agent.turn/turn-idx       turn-idx
                                                :seon.agent.turn/prompt-text    prompt})))))))]
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
          (let [n-ok (or (:seon.agent/eval-count result) 0)]
            (log id turn-idx (name (or (:seon.agent.turn/status result) :done)) n-ok
                 (if (:seon.agent.turn/status result) "llm-error" "ok"))
            (assoc (db/pull {:seon.db/pull-pattern
                             '[* {:seon.agent.turn/evals [*]}]
                             :seon.db/ref [:seon.agent.turn/id turn-id]})
                   :seon.agent/eval-count n-ok))))
      (catch :default e
        ;; Catastrophic turn failure → return the :error shape. State is the
        ;; loop's concern (its finally resets :idle); the turn never touches it.
        (log id turn-idx "run-turn! error" (str e))
        {:seon.agent.turn/status :error
         :seon.error/data (str e)}))))
