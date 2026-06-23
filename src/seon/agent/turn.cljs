(ns seon.agent.turn
  "One agentic TURN, end-to-end — the unit the FSM loop ([[seon.agent.fsm]])
   drives once per LLM completion.

   A turn = open a `:seon.agent.turn` (stamping the agent's current
   `:seon.agent/wake` so the loop's sliding cap can count it) → render the
   prompt → call the LLM → parse → eval-batch the forms → close the turn. The
   per-form eval isolation (every form runs; errors are envelopes) lives in
   [[seon.eval]]; `eval-count = n-ok + n-fail`.

   This namespace owns the `:seon.agent.session/*` + `:seon.agent.turn/*`
   schemas (their data-owner), and these fns:
     - `run-turn!`      — one full turn (the loop calls this)
     - `open-turn!` / `close-turn!` — the turn bracket (open-tx + close-tx)
     - `ask-and-eval!`  — LLM call + parse + eval-batch
     - `call-llm!`      — `(llm-fn prompt)` with one bounded transport retry
     - `render-prompt` / `prefetch-and-render-prompt!` — ctx assembly
     - `current-session` / `ensure-session!` / `start-session!` / `turn-index`

   Dependency direction (acyclic): it references `:seon.agent/*` keywords
   (state/wake — global registry, no require) and transacts via `seon.db`
   directly, so it does NOT require `seon.agent` (which would cycle:
   `seon.agent` re-exports nothing from here, and `seon.agent.fsm` requires
   both). It MAY require ctx / eval / message / render."
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
    [seon.render :as render]
    [seon.repl :as repl]
    [seon.schema :as schema]))

;; ============================================================
;; Causality graph — :seon.agent.session + :seon.agent.turn entities. One
;; pod run = one :seon.agent.session. Each render → LLM → eval-batch cycle =
;; one :seon.agent.turn. Both ride as component refs on their parents
;; (cascade-retract on parent retract). ALL counters are DERIVED, never
;; persisted (reactive-context).
;;
;; Identity attrs reference the canonical :seon.db/id shape (single source of
;; truth in seon.schema). The [:and {…} :seon.db/id] wrapping adds
;; {:seon.db/identity true} so the bridge writes :db.unique/identity.
;; ============================================================

(schema/register! :seon.agent.session/id    [:and {:seon.db/identity true} :seon.db/id])
(schema/register! :seon.agent.session/at    :inst)
;; :db/isComponent on the ref vectors — retracting a session/turn
;; cascade-retracts its child entities, and one nested pull on the agent
;; walks the whole causality chain inline.
(schema/register! :seon.agent.session/turns [:vector {:seon.db/component true} :seon.db/ref])

(schema/register! :seon.agent.turn/id           [:and {:seon.db/identity true} :seon.db/id])
(schema/register! :seon.agent.turn/at           :inst)
;; A turn is running/done/error — DISTINCT from the agent FSM state
;; (idle/active/…): a turn is a single completion, the agent is the actor.
(schema/register! :seon.agent.turn/status       [:enum :running :done :error])
;; The wake-episode this turn belongs to. Each turn-open STAMPS the agent's
;; current `:seon.agent/wake` here, so the per-loop count (`count turns where
;; wake = my-wake`) is derivable. STORED — coordination metadata, not
;; derivable. References the canonical id shape; never inline.
(schema/register! :seon.agent.turn/wake         :seon.db/id)
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

(schema/register! :seon.agent.session
  [:map {:seon.db/entity true}
   [:seon.agent.session/id    :seon.agent.session/id]
   [:seon.agent.session/at    :seon.agent.session/at]
   [:seon.agent.session/turns {:optional true} :seon.agent.session/turns]])

(schema/register! :seon.agent.turn
  [:map {:seon.db/entity true}
   [:seon.agent.turn/id           :seon.agent.turn/id]
   [:seon.agent.turn/at           :seon.agent.turn/at]
   [:seon.agent.turn/status       :seon.agent.turn/status]
   [:seon.agent.turn/wake         {:optional true} :seon.agent.turn/wake]
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

(defn- per-agent-shape?
  "True when `sym` is in the agent's own home namespace. Per-agent fns get
   the per-agent input shape (entity pre-pulled under a namespaced key);
   everything else gets the system shape (`:seon.agent/id` + DB)."
  [sym agent-id]
  (and (qualified-symbol? sym)
       (str/starts-with? (namespace sym)
                         (str "my.agent." agent-id))))

(defn- ai-render-input
  "Build the input map for the agent's `:seon.render/ai` dispatch. Two
   shapes, picked by symbol namespace."
  [sym db agent-id ent]
  (if (per-agent-shape? sym agent-id)
    {:seon.db/db                                 db
     (keyword (str "my.agent." agent-id) "ctx") ent}
    {:seon.db/db    db
     :seon.agent/id agent-id}))

;; ============================================================
;; Sessions — one per pod run for a resumed agent.
;; ============================================================

(defn current-session
  "The agent's current `:seon.agent.session` entity (latest by `:at`), or
   nil if none yet. Re-export of [[seon.ctx/current-session]]."
  [agent-id]
  (ctx/current-session agent-id))

(defn turn-index
  "Zero-indexed next turn slot for the session — derived from the current
   count of `:seon.agent.session/turns`. Not persisted (storing would let it
   desync from reality)."
  [session-id]
  (count (:seon.agent.session/turns
           (db/entity {:seon.db/ref [:seon.agent.session/id session-id]}))))

(defonce ^:private !sessions-opened-this-run
  ;; Session ids opened by THIS pod process. `defonce` — survives hot reload
  ;; (a reload is the same pod run), empty on a fresh Node boot. A pod
  ;; restart always opens a FRESH session for a resumed agent: the agent
  ;; entity, purpose, and messages persist (messages are global), but evals
  ;; are session-scoped — the intended resume shape.
  (atom #{}))

(defn ^:async start-session!
  "Open a new `:seon.agent.session` for `agent-id` and append to
   `:seon.agent/sessions`. Records the id in `!sessions-opened-this-run`.
   Returns the new session entity."
  [agent-id]
  (let [session-id (db/new-id!)]
    (await (db/transact!
             {:seon.db/tx-data
              [{:seon.agent/id agent-id
                :seon.agent/sessions
                [{:seon.agent.session/id session-id
                  :seon.agent.session/at (js/Date.)}]}]}))
    (swap! !sessions-opened-this-run conj session-id)
    (db/entity {:seon.db/ref [:seon.agent.session/id session-id]})))

(defn ^:async ensure-session!
  "Return the agent's current session, opening one if THIS pod process
   hasn't opened one yet. Idempotent within a pod run; a session found in
   the DB but opened by a previous pod run is NOT reused — every pod boot
   starts a fresh session for a resumed agent."
  [agent-id]
  (let [sess (current-session agent-id)]
    (if (and sess
             (contains? @!sessions-opened-this-run (:seon.agent.session/id sess)))
      sess
      (await (start-session! agent-id)))))

;; ============================================================
;; Prompt assembly (sync, with an optional async embedding prefetch).
;; ============================================================

(defn render-prompt
  "Sync — resolve the agent's `:seon.render/ai` slot (default
   `seon.agent/assemble-context`) and call it. A STRING slot renders
   verbatim; a symbol is resolved and called. Returns the prompt string
   (empty when the symbol can't be resolved)."
  [agent-id]
  (let [ent  (db/entity {:seon.db/ref [:seon.agent/id agent-id]})
        slot (or (some->> (:seon.render/ai ent)
                          (db/decode-edn-value :seon.render/ai))
                 'seon.agent/assemble-context)]
    (if (string? slot)
      slot
      (let [input (ai-render-input slot @db/*conn* agent-id ent)]
        (or (:seon.render/text (render/ai-render slot input)) "")))))

(defn embed-retrieval-on?
  "True when embedding-retrieval is enabled — the env var `SEON_EMBED` is
   PRESENT (any value). The SAME single switch the wire-server reads, so one
   env var gates the feature across both processes. UNSET ⇒ the prefetch
   never fires and `render-prompt` runs the byte-identical-OFF path."
  []
  (some? (.. js/process -env -SEON_EMBED)))

(defn ^:async prefetch-and-render-prompt!
  "Render this turn's prompt, OPTIONALLY prefetching embedding-retrieval hits
   first. DEFAULT-OFF (byte-identical): when [[embed-retrieval-on?]] is false
   this is exactly `(render-prompt agent-id)`. When ON: derive the query from
   the latest live inbound, KNN over the WHOLE embedding index (kind-general),
   stash the hits, then run the SYNC `render-prompt` inside that scope so the
   `:relevant-source` section reads them without making `assemble-context`
   async. FAIL-SOFT to nil hits on any error (section renders blank)."
  [agent-id]
  (if-not (embed-retrieval-on?)
    (render-prompt agent-id)
    (let [db    @db/*conn*
          query (ctx/retrieval-query {:seon.db/db db :seon.agent/id agent-id})
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
      (embed-stash/with-hits hits #(render-prompt agent-id)))))

;; ============================================================
;; The turn bracket — open-turn! folds the prompt projection + the agent's
;; current :seon.agent/wake into the open-tx; close-turn! folds telemetry.
;; The turn manages ONLY `:seon.agent.turn/status` — the agent's FSM
;; `:seon.agent/state` is the LOOP's concern ([[seon.agent.fsm/run-loop!]]
;; sets :active before the first turn and :idle in its finally), so the turn
;; must leave state UNTOUCHED (a per-turn :idle reset would halt the loop
;; after one turn). A turn run OUTSIDE a loop (the creation-evals bootstrap)
;; therefore leaves state at whatever it was (:idle), which is correct.
;; ============================================================

(declare close-turn!)

(defn ^:async open-turn!
  "Open a `:seon.agent.turn` on the given session with `prompt-text` attached
   and the agent's current `:seon.agent/wake` STAMPED on
   `:seon.agent.turn/wake` (the loop's sliding cap derives its per-loop count
   from it). Awaits `body-fn` (a 0-arg thunk returning Promise<map>) via
   `close-turn!`. Returns whatever `body-fn` returned, so the caller can read
   `:seon.agent/eval-count`. Does NOT touch `:seon.agent/state` — that is the
   loop's. If the open-tx fails there is NO turn entity — returns the error
   envelope (no LLM call)."
  [{:seon.agent/keys [id]
    :seon.agent.session/keys [id-of-session]
    :seon.agent.turn/keys [id-of-turn prompt-text prompt-file]}
   body-fn]
  (let [wake (:seon.agent/wake
               (db/entity {:seon.db/ref [:seon.agent/id id]}))
        open-result
        (await
          (db/transact!
            {:seon.db/tx-data
             [{:seon.agent.session/id id-of-session
               :seon.agent.session/turns
               [(cond->
                  {:seon.agent.turn/id           id-of-turn
                   :seon.agent.turn/at           (js/Date.)
                   :seon.agent.turn/status       :running
                   :seon.agent.turn/prompt-chars (count (str prompt-text))}
                  prompt-file (assoc :seon.agent.turn/prompt-file prompt-file)
                  wake (assoc :seon.agent.turn/wake wake))]}]}))]
    (if (false? (:seon.db/ok? open-result))
      open-result
      (close-turn! id id-of-turn body-fn))))

(defn ^:async ^:private close-turn!
  "Internal — the body of `open-turn!` after the open-tx succeeded. await
   body-fn, close the turn `:status :done` on success, flip it to `:error` on
   throw (then re-throw so the loop sees the failure). Touches ONLY the turn
   status; the agent's `:seon.agent/state` is the loop's concern."
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
  [resp id id-of-turn turn-idx compile-state]
  (let [reply-text (or (:text resp) "")
        debug-dir  (debug/capture-response! id turn-idx id-of-turn
                                            reply-text resp)
        parsed     (repl/parse-forms reply-text)
        batch      (await (seval/eval-batch! compile-state parsed
                                             (ctx/home-ns id) id id-of-turn))]
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
      (cond-> (await (ask-and-eval-reply! resp id id-of-turn turn-idx compile-state))
        retries     (assoc :seon.agent.turn/llm-retries retries)
        (seq usage) (assoc :seon.agent.turn/llm-usage (pr-str usage))
        (seq pfields) (assoc :seon.agent.turn/llm-meta (pr-str pfields))))))

(defn ^:async run-turn!
  "One full turn end-to-end. Map-in / map-out.

   Input keys:
     :seon.agent/id             agent id string
     :seon.agent/llm-fn         ctx-string -> Promise<{:text \"…\"}>
     :seon.agent/compile-state  bootstrap compile-state

   Wraps the pipeline in a `with-tx-context` scope so every transact (incl.
   the per-form txs inside `eval-batch!`) auto-tags with the causality
   bundle. Returns the closed turn entity pulled with evals inlined, plus
   `:seon.agent/eval-count`. On catastrophic error returns
   `{:seon.agent.turn/status :error :seon.error/data <str>}`."
  [{:seon.agent/keys [id llm-fn compile-state]}]
  (let [session    (await (ensure-session! id))
        session-id (:seon.agent.session/id session)
        turn-id    (db/new-id!)
        turn-idx   (turn-index session-id)
        prompt     (await (prefetch-and-render-prompt! id))
        full-prompt (ai/debug-full-prompt {:seon.ai/ctx prompt})
        prompt-file (debug/capture-prompt! id turn-idx turn-id full-prompt)]
    (log id turn-idx "open" turn-id "+" (count prompt) "ctx-chars")
    (try
      (let [result (await
                     (db/with-agent id
                       (fn []
                         (db/with-tx-context
                           {:seon.db/agent-id   id
                            :seon.db/session-id session-id
                            :seon.db/turn-id    turn-id
                            :seon.db/origin     :system}
                           (fn []
                             (open-turn!
                               (cond->
                                 {:seon.agent/id           id
                                  :seon.agent.session/id-of-session session-id
                                  :seon.agent.turn/id-of-turn    turn-id
                                  :seon.agent.turn/prompt-text   full-prompt}
                                 prompt-file
                                 (assoc :seon.agent.turn/prompt-file prompt-file))
                               #(ask-and-eval! {:seon.agent/id            id
                                                :seon.agent/llm-fn        llm-fn
                                                :seon.agent/compile-state compile-state
                                                :seon.agent.turn/id-of-turn     turn-id
                                                :seon.agent.turn/turn-idx       turn-idx
                                                :seon.agent.turn/prompt-text    prompt})))))))
            n-ok (or (:seon.agent/eval-count result) 0)]
        (log id turn-idx (name (or (:seon.agent.turn/status result) :done)) n-ok
             (if (:seon.agent.turn/status result) "llm-error" "ok"))
        (assoc (db/pull {:seon.db/pull-pattern
                         '[* {:seon.agent.turn/evals [*]}]
                         :seon.db/ref [:seon.agent.turn/id turn-id]})
               :seon.agent/eval-count n-ok))
      (catch :default e
        ;; Catastrophic turn failure → return the :error shape. State is the
        ;; loop's concern (its finally resets :idle); the turn never touches it.
        (log id turn-idx "run-turn! error" (str e))
        {:seon.agent.turn/status :error
         :seon.error/data (str e)}))))
