(ns seon.agent.turn
  "Pod-owned leaves for the portable claim-native turn driver.

   This namespace owns turn schemas plus render, LLM, publication, and
   scheduled-eval leaves. Cursor and receipt policy live in
   `seon.agent.turn.core`; the portable driver owns orchestration."
  (:require
    [my.plan :as plan]
    [seon.ai :as ai]
    [seon.agent.run.core :as run.core]
    [seon.agent.home :as home]
    [seon.agent.turn.core :as turn.core]
    [seon.agent.turn.llm :as turn.llm]
    [my.blob :as blob]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.error :as error]
    [seon.eval :as seval]
    [seon.execution :as execution]
    [seon.execution.host :as execution.host]
    [seon.agent.ctx.driver :as ctx.driver]
    [seon.log :as seon-log]
    [seon.render :as render]
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
(schema/register! :seon.agent.turn/at
                  [:inst {:seon.db/index true}])
;; A turn is running/done/error/interrupted — DISTINCT from the agent FSM state
;; (idle/running/…): a turn is a single completion, the agent is the actor.
;; `:interrupted` is asserted only by unexpected-exit recovery when no runtime
;; remains to close the committed turn normally.
(schema/register! :seon.agent.turn/status
                  [:enum :running :done :error :interrupted])
(schema/register! :seon.agent.turn/phase
                  [:enum :rendered :attempt-open :reply-ready
                   :evaling :evaled :published])
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
;; The transaction basis of the immutable database value used for this turn.
;; The database's own transaction ref is sufficient for normal historical
;; reconstruction through `db/as-of`; transaction metadata supplies provenance.
(schema/register! :seon.agent.turn/rendered-tx :seon.db/ref)
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
;; Finite provider usage telemetry is stored as ordinary long attributes on
;; the turn. The two provider dialects remain presence patterns; no dialect
;; discriminator is stored. Unrecognized raw fields are not persisted.
(schema/register! :seon.agent.turn.usage/prompt-tokens [:int {:min 0}])
(schema/register! :seon.agent.turn.usage/completion-tokens [:int {:min 0}])
(schema/register! :seon.agent.turn.usage/cached-tokens [:int {:min 0}])
(schema/register! :seon.agent.turn.usage/input-tokens [:int {:min 0}])
(schema/register! :seon.agent.turn.usage/output-tokens [:int {:min 0}])
(schema/register! :seon.agent.turn.usage/cache-read-input-tokens [:int {:min 0}])
(schema/register! :seon.agent.turn.usage/cache-creation-input-tokens [:int {:min 0}])
;; Provider fields are the deliberately open remainder of a response object,
;; so this is honestly EDN rather than a hidden closed shape.
(schema/register! :seon.agent.turn/llm-meta     :string)
;; repl-mode telemetry. In `:stream`, the turn's
;; `:seon.agent.turn.usage/*` numbers are
;; CLIENT-SIDE estimates (the aborted stream lost the provider's usage
;; chunk) — marked so a reader never treats them as provider-reported.
(schema/register! :seon.agent.turn/usage-estimated? :boolean)
(schema/register! :seon.agent.turn/evals        [:vector {:seon.db/component true} :seon.db/ref])
(schema/register! :seon.agent.turn/llm-attempts [:vector {:seon.db/component true} :seon.db/ref])

(defn- usage-count [usage key]
  (let [value (get usage key)]
    (when (and (int? value) (not (neg? value))) value)))

(defn- persisted-usage
  "Project provider usage to finite ordinary turn attributes."
  [usage]
  (cond
    (contains? usage :prompt_tokens)
    (let [direct (usage-count usage :prompt_cache_hit_tokens)
          nested (usage-count (:prompt_tokens_details usage) :cached_tokens)
          conflict? (and (some? direct) (some? nested) (not= direct nested))
          cached (when-not conflict? (or direct nested))]
      (cond->
        {::usage-attributes
         (cond-> {}
           (usage-count usage :prompt_tokens)
           (assoc :seon.agent.turn.usage/prompt-tokens
                  (usage-count usage :prompt_tokens))
           (usage-count usage :completion_tokens)
           (assoc :seon.agent.turn.usage/completion-tokens
                  (usage-count usage :completion_tokens))
           cached
           (assoc :seon.agent.turn.usage/cached-tokens cached))}
        conflict?
        (assoc ::usage-error
               {:seon.error/message
                (str "Provider cache usage fields disagree: " direct
                     " direct versus " nested " nested.")})))

    (contains? usage :input_tokens)
    {::usage-attributes
     (into {}
           (keep (fn [key]
                   (when-some [value (usage-count usage key)]
                     [(get {:input_tokens
                            :seon.agent.turn.usage/input-tokens
                            :output_tokens
                            :seon.agent.turn.usage/output-tokens
                            :cache_read_input_tokens
                            :seon.agent.turn.usage/cache-read-input-tokens
                            :cache_creation_input_tokens
                            :seon.agent.turn.usage/cache-creation-input-tokens}
                           key)
                      value])))
           [:input_tokens :output_tokens :cache_read_input_tokens
            :cache_creation_input_tokens])}

    :else {::usage-attributes {}}))

;; One bounded, queryable transport fact per provider attempt. The turn owns
;; these component rows; the effective config remains derived from the parent
;; turn's database value and is projected here only as the non-secret values
;; actually used. Absence remains absence.
(schema/register!
 :seon.ai.attempt/id
 [:and {:seon.db/identity true
        :seon.db.id/generator :seon.db.id.generator/compact}
  ::db.id/compact-value])
(schema/register! :seon.ai.attempt/ordinal :int)
(schema/register! :seon.ai.attempt/fallback-variant
                  :seon.config/model-variant)
(schema/register! :seon.ai.attempt/provider :seon.ai/provider)
(schema/register! :seon.ai.attempt/adapter :seon.ai/adapter)
(schema/register! :seon.ai.attempt/requested-model :seon.ai/model)
(schema/register! :seon.ai.attempt/temperature :seon.ai/temperature)
(schema/register! :seon.ai.attempt/max-tokens :seon.ai/max-tokens)
(schema/register! :seon.ai.attempt/completion-limit-field
                  :seon.ai/completion-limit-field)
(schema/register! :seon.ai.attempt/thinking :seon.ai/thinking)
(schema/register! :seon.ai.attempt/endpoint :seon.ai/endpoint)
(schema/register! :seon.ai.attempt/adapter-timeout-ms :seon.ai/timeout-ms)
(schema/register! :seon.ai.attempt/outer-timeout-ms :int)
(schema/register! :seon.ai.attempt/stream? :boolean)
(schema/register! :seon.ai.attempt/extra-body-digest :seon.ai/extra-body-digest)
(schema/register! :seon.ai.attempt/dg-backend :seon.ai/dg-backend)
(schema/register! :seon.ai.attempt/api-key-env :seon.ai/api-key-env)
(schema/register! :seon.ai.attempt/credential-class :seon.ai/credential-class)
(schema/register! :seon.ai.attempt/outcome
                  [:enum :open :success :provider-error :adapter-timeout
                   :outer-timeout :crashed])
(schema/register! :seon.ai.attempt/config-digest
                  [:string {:min 64 :max 64}])
(schema/register! :seon.ai.attempt/deadline-at :inst)
(schema/register! :seon.ai.attempt/retry-after-ms [:int {:min 0}])
(schema/register! :seon.ai.attempt/error-status :seon.ai/status)
(schema/register! :seon.ai.attempt/response-model :seon.ai/response-model)
(schema/register! :seon.ai.attempt/system-fingerprint :seon.ai/system-fingerprint)
(schema/register! :seon.ai.attempt/request-id :seon.ai/request-id)
(schema/register! :seon.ai.attempt/evidence-error [:string {:min 1}])
(schema/register! :seon.ai.attempt/finish-reason [:string {:min 1}])
(schema/register! :seon.ai.attempt/truncated? :boolean)
(schema/register! :seon.ai.attempt/usage :string)
(schema/register! :seon.ai.attempt/entity
  [:map {:seon.db/entity true}
   [:seon.ai.attempt/id :seon.ai.attempt/id]
   [:seon.ai.attempt/ordinal :seon.ai.attempt/ordinal]
   [:seon.ai.attempt/config-digest :seon.ai.attempt/config-digest]
   [:seon.ai.attempt/deadline-at :seon.ai.attempt/deadline-at]
   [:seon.ai.attempt/fallback-variant
    {:optional true} :seon.ai.attempt/fallback-variant]
   [:seon.ai.attempt/provider :seon.ai.attempt/provider]
   [:seon.ai.attempt/adapter :seon.ai.attempt/adapter]
   [:seon.ai.attempt/requested-model {:optional true} :seon.ai.attempt/requested-model]
   [:seon.ai.attempt/temperature {:optional true} :seon.ai.attempt/temperature]
   [:seon.ai.attempt/max-tokens {:optional true} :seon.ai.attempt/max-tokens]
   [:seon.ai.attempt/completion-limit-field
    {:optional true} :seon.ai.attempt/completion-limit-field]
   [:seon.ai.attempt/thinking {:optional true} :seon.ai.attempt/thinking]
   [:seon.ai.attempt/endpoint {:optional true} :seon.ai.attempt/endpoint]
   [:seon.ai.attempt/adapter-timeout-ms {:optional true} :seon.ai.attempt/adapter-timeout-ms]
   [:seon.ai.attempt/outer-timeout-ms :seon.ai.attempt/outer-timeout-ms]
   [:seon.ai.attempt/stream? :seon.ai.attempt/stream?]
   [:seon.ai.attempt/extra-body-digest {:optional true} :seon.ai.attempt/extra-body-digest]
   [:seon.ai.attempt/dg-backend {:optional true} :seon.ai.attempt/dg-backend]
   [:seon.ai.attempt/api-key-env {:optional true} :seon.ai.attempt/api-key-env]
   [:seon.ai.attempt/credential-class {:optional true} :seon.ai.attempt/credential-class]
   [:seon.ai.attempt/outcome :seon.ai.attempt/outcome]
   [:seon.ai.attempt/error-status {:optional true} :seon.ai.attempt/error-status]
   [:seon.ai.attempt/response-model {:optional true} :seon.ai.attempt/response-model]
   [:seon.ai.attempt/system-fingerprint {:optional true} :seon.ai.attempt/system-fingerprint]
   [:seon.ai.attempt/request-id {:optional true} :seon.ai.attempt/request-id]
   [:seon.ai.attempt/evidence-error {:optional true} :seon.ai.attempt/evidence-error]
   [:seon.ai.attempt/finish-reason {:optional true} :seon.ai.attempt/finish-reason]
   [:seon.ai.attempt/truncated? {:optional true} :seon.ai.attempt/truncated?]
   [:seon.ai.attempt/usage {:optional true} :seon.ai.attempt/usage]
   [:seon.ai.attempt/retry-after-ms
    {:optional true} :seon.ai.attempt/retry-after-ms]])

;; Entity shape. NB: seon.db validates per-ATTRIBUTE, not entity-level, so the
;; non-:optional entries below are documentation, not an enforced required-key
;; check — a tx omitting `at`/`status` still writes.
(schema/register! :seon.agent.turn
  [:map {:seon.db/entity true}
   [:seon.agent.turn/id           :seon.agent.turn/id]
   [:seon.agent.turn/at           :seon.agent.turn/at]
   [:seon.agent.turn/status       :seon.agent.turn/status]
   [:seon.agent.turn/phase        :seon.agent.turn/phase]
   [:seon.agent.turn/run          {:optional true} :seon.agent.turn/run]
   [:seon.agent.turn/scheduled?   {:optional true} :seon.agent.turn/scheduled?]
   [:seon.agent.turn/prompt-chars {:optional true} :seon.agent.turn/prompt-chars]
   [:seon.agent.turn/rendered-tx   {:optional true} :seon.agent.turn/rendered-tx]
   [:seon.agent.turn/prompt-blob  {:optional true} :seon.agent.turn/prompt-blob]
   [:seon.agent.turn/reply-blob   {:optional true} :seon.agent.turn/reply-blob]
   [:seon.agent.turn/error        {:optional true} :seon.agent.turn/error]
   [:seon.agent.turn/llm-retries  {:optional true} :seon.agent.turn/llm-retries]
   [:seon.agent.turn.usage/prompt-tokens {:optional true}
    :seon.agent.turn.usage/prompt-tokens]
   [:seon.agent.turn.usage/completion-tokens {:optional true}
    :seon.agent.turn.usage/completion-tokens]
   [:seon.agent.turn.usage/cached-tokens {:optional true}
    :seon.agent.turn.usage/cached-tokens]
   [:seon.agent.turn.usage/input-tokens {:optional true}
    :seon.agent.turn.usage/input-tokens]
   [:seon.agent.turn.usage/output-tokens {:optional true}
    :seon.agent.turn.usage/output-tokens]
   [:seon.agent.turn.usage/cache-read-input-tokens {:optional true}
    :seon.agent.turn.usage/cache-read-input-tokens]
   [:seon.agent.turn.usage/cache-creation-input-tokens {:optional true}
    :seon.agent.turn.usage/cache-creation-input-tokens]
   [:seon.agent.turn/llm-meta     {:optional true} :seon.agent.turn/llm-meta]
   [:seon.agent.turn/usage-estimated? {:optional true} :seon.agent.turn/usage-estimated?]
   [:seon.agent.turn/evals        {:optional true} :seon.agent.turn/evals]
   [:seon.agent.turn/llm-attempts {:optional true} :seon.agent.turn/llm-attempts]])

;; ============================================================
;; Turn-level logging + render-input shaping.
;; ============================================================

(defn- log [agent-id stage & info]
  (seon-log/info-console!
    (str "seon.agent.turn/" agent-id)
    (str "turn ▸ " stage)
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
        (do (seon-log/warn!
              {:seon.log/source ::blob-capture
               :seon.log/message
               (str "blob capture failed (turn continues): " err)})
            nil)))
    (catch :default e
      (seon-log/warn!
        {:seon.log/source ::blob-capture
         :seon.log/message
         (str "blob capture failed (turn continues): "
              (or (some-> e .-message) (str e)))})
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
;; Prompt assembly.
;; ============================================================

(schema/register!
  ::prompt-error
  [:map
   [:seon.error/message :string]
   [:seon.error/kind :keyword]
   [:seon.error/data {:optional true} :map]])
(schema/register!
  ::rendered-prompt
  [:map
   [:seon.render/text :string]
   [:seon.ai/system-prompt :string]
   [:seon.ai/config-resolution :seon.ai/config-resolution]
   [:seon.config/repl-mode :seon.config/repl-mode]
   [:seon.eval/ns :seon.ns/name]
   [:seon.agent.ctx/rendered-blocks {:optional true} [:vector :map]]])
(schema/register! ::prompt-result [:or ::rendered-prompt ::prompt-error])

(def ^:private eval-batch-function
  'seon.execution.runtime/eval-batch!)

(defn- execution-child-retired?
  [value]
  (or (true? (::execution/child-retired? value))
      (true? (get-in value [:seon.error/data
                            ::execution/child-retired?]))))

(defn- execution-child-evidence [value]
  (when (execution-child-retired? value)
    (let [data (:seon.error/data value)
          nested (when (map? data) (:seon.error/data data))]
      (cond
        (and (map? nested)
             (true? (::execution/child-retired? nested))) nested
        (map? data) data
        :else value))))

(declare invoke-prompt-calls!)

(defn- supports-arity?
  "Read the original CLJS callable through Malli's instrumentation wrapper."
  [function-value arity]
  (let [function-value
        (or (aget function-value "malli$instrument$original")
            function-value)
        max-fixed (aget function-value "cljs$lang$maxFixedArity")
        fixed (aget function-value
                    (str "cljs$core$IFn$_invoke$arity$" arity))
        variadic (aget function-value
                       "cljs$core$IFn$_invoke$arity$variadic")]
    (if (number? max-fixed)
      (or (fn? fixed)
          (and (fn? variadic) (>= arity max-fixed)))
      (= arity (.-length function-value)))))

(defn ^:async ^:private invoke-authored-render!
  "Invoke one authored render through the guarded host door."
  [database agent-id
   {function-symbol ::render/function-symbol
    arguments ::render/arguments}]
  (try
    (let [invocations
          (await
           (execution/prepare-invocations!
            {:seon.db/db database
             ::execution/invocation-plans
             [(execution/invocation-plan
               agent-id function-symbol arguments)]}))
          result (await (execution.host/invoke! (first invocations)))]
      (if (::execution/ok? result)
        (::execution/value result)
        (::execution/error result)))
    (catch :default exception
      (error/->map exception))))

(defn ^:async ^:private invoke-prompt-call!
  "Run a core prompt directly or an authored render through its seam."
  [database agent-id render-door call]
  (let [function-symbol (::execution/function-symbol call)]
    (if-not (error/agent-authored-sym? function-symbol)
      (try
        (if-let [function-value (seval/lookup-value function-symbol)]
          (let [base-arguments (::execution/arguments call)
                arguments
                (cond-> base-arguments
                  (and (::execution/invoke-selected? call)
                       (supports-arity?
                        function-value (inc (count base-arguments))))
                  (conj #(invoke-prompt-calls!
                          database agent-id render-door %)))
                value (await (apply function-value arguments))]
            {::execution/ok? true ::execution/value value})
          {::execution/ok? false
           ::execution/error
           {:seon.error/message
            "The selected compiled prompt function is not loaded."
            :seon.error/kind :core-bug
            :seon.error/data
            {::execution/function-symbol function-symbol}}})
        (catch :default exception
          (error/record! {::error/raw exception ::error/fault :core})
          {::execution/ok? false
           ::execution/error (error/->map exception)}))
      {::execution/ok? true
       ::execution/value
       (await
        ((::render/invoke-authored! render-door)
         {::render/function-symbol function-symbol
          ::render/arguments (::execution/arguments call)}))})))

(defn ^:async ^:private invoke-prompt-calls!
  [database agent-id render-door calls]
  (let [results
        (await
         (js/Promise.all
          (clj->js
           (mapv #(invoke-prompt-call!
                   database agent-id render-door %)
                 calls))))]
    (vec (array-seq results))))

(defn ^:async render-prompt
  "Render one agent prompt inside its isolated execution child.

   The caller supplies the immutable database value. The trusted
   compiled prompt owner performs every prompt read and selected function call
   at that value and returns the ordinary rendered prompt map. This
   orchestration tail validates that the child did not move database values and
   preserves the exact context and system text consumed by the LLM."
  {:malli/schema
   [:function
    [:=> [:cat :seon.agent/id :seon.db/db]
     ::prompt-result]
    [:=> [:cat :seon.agent/id :seon.db/db
          :seon.agent.ctx/profile]
     ::prompt-result]
    [:=> [:cat :seon.agent/id :seon.db/db
          :seon.agent.ctx/profile [:or :nil :seon.agent.run/id]]
     ::prompt-result]]}
  ([agent-id database]
   (await (render-prompt agent-id database [])))
  ([agent-id database profile]
   (await (render-prompt agent-id database profile nil)))
  ([agent-id database profile run-id]
   (let [request (cond-> {:seon.agent/id agent-id}
                   (seq profile)
                   (assoc :seon.agent.ctx/profile (vec profile))
                   run-id
                   (assoc :seon.agent.run/id run-id))
         invoke-authored!
         {::render/invoke-authored!
          #(invoke-authored-render! database agent-id %)}
         invoke-selected!
         #(invoke-prompt-calls! database agent-id invoke-authored! %)
         rendered
         (await
          (ctx.driver/render-prompt!
           (assoc request ::db/db database)
           invoke-selected!))
         response {:seon.db/db (:seon.db/db rendered)
                   ::execution/message execution/result-message
                   ::execution/result (dissoc rendered :seon.db/db)}]
     (cond
       (not= database (:seon.db/db response))
       {:seon.error/message
        "The execution child returned a prompt from another database value."
        :seon.error/kind :core-bug
        :seon.error/data
        {:seon.db/expected-db database
         :seon.db/db (:seon.db/db response)}}

       (= execution/result-message (::execution/message response))
       (let [rendered (::execution/result response)
             text (:seon.render/text rendered)
             system-prompt (:seon.ai/system-prompt rendered)
             resolution (:seon.ai/config-resolution rendered)]
         (cond
           (and (map? rendered) (string? (:seon.error/message rendered)))
           rendered

           (and (string? text)
                (string? system-prompt)
                (schema/valid-candidate-value?
                 :seon.ai/config-resolution resolution))
           rendered

           :else
           {:seon.error/message
            "The execution child returned an invalid rendered prompt."
            :seon.error/kind :core-bug}))

       :else
       (or (::execution/error response)
           {:seon.error/message "The execution child did not return a prompt."
            :seon.error/kind :core-bug})))))

(defn ^:async eval-parsed!
  "Evaluate parsed model output in the agent's existing execution child."
  {:malli/schema
   [:=> [:cat :seon.agent/id :seon.db/db
         [:vector :map] :symbol :seon.agent.turn/id
         [:or :nil :seon.agent.run/id]]
    :map]}
  [agent-id database parsed starting-ns turn-id run-id]
  (let [executable-count (count (filter #(contains? #{:form :read}
                                                     (:seon.repl/kind %))
                                        parsed))
        request (cond-> {:seon.eval/parsed parsed
                         :seon.eval/starting-ns starting-ns
                         :seon.agent.turn/id-of-turn turn-id}
                  run-id (assoc :seon.agent.run/id-of-run run-id))
        response
        (await
         (apply execution.host/invoke-compiled!
                (cond-> [database agent-id eval-batch-function [request]]
                  run-id (conj {:seon.agent.run/id run-id}))))]
    (cond
      (not= database (:seon.db/db response))
      {:seon.error/message
       "The execution child returned eval results from another database value."
       :seon.error/kind :core-bug
       :seon.error/data
       {:seon.db/expected-db database
        :seon.db/db (:seon.db/db response)}}

      (and (= execution/result-message (::execution/message response))
           (map? (::execution/result response)))
      (let [result (::execution/result response)
            attempted (+ (or (:seon.eval/n-ok result) 0)
                         (or (:seon.eval/n-fail result) 0))]
        (if (and (pos? executable-count) (zero? attempted))
          (let [failure
                {:seon.error/message
                 "The execution tier dropped an executable eval batch without recording a receipt."
                 :seon.error/kind :core-bug
                 :seon.error/data
                 {:seon.agent/id agent-id
                  :seon.agent.turn/id turn-id
                  :seon.eval/executable-count executable-count
                  :seon.eval/recorded-count attempted}}
                exception (ex-info (:seon.error/message failure) failure)]
            (error/record! {::error/raw exception ::error/fault :core})
            failure)
          result))

      :else
      (or (::execution/error response)
          {:seon.error/message "The execution child did not return eval results."
           :seon.error/kind :core-bug}))))

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
   lands, and the caller receives the direct database error. If the open-tx
   fails (fence or otherwise) there is NO turn entity — returns the error value
   (no LLM call)."
  {:malli/schema [:=> [:catn [:turn-input :map] [:body-fn :any]] :any]}
  [{:seon.agent/keys [id]
    :seon.agent.run/keys [id-of-run]
    :seon.agent.turn/keys [prompt-text scheduled? prompt-blob]
    database :seon.db/db}
   body-fn]
  (let [turn-row
        (cond->
          {:seon.agent.turn/at           (js/Date.)
           :seon.agent.turn/status       :running
           :seon.agent.turn/prompt-chars (count (str prompt-text))
           :seon.agent.turn/rendered-tx  (:t database)}
          prompt-blob    (assoc :seon.agent.turn/prompt-blob prompt-blob)
          scheduled?  (assoc :seon.agent.turn/scheduled? true)
          id-of-run  (assoc :seon.agent.turn/run [:seon.agent.run/id id-of-run]))
        allocation
        (await
          (db.id/allocate!
            {::db.id/allocations
             [{::db.id/key ::turn-allocation
               ::db.id/identity-attr :seon.agent.turn/id}]
             :seon.db/db database
             ::db.id/transaction-builder
             (fn [{turn-id ::turn-allocation}]
               {:seon.db/tx-data
                (cond-> []
                  id-of-run
                  (conj (db/cas-assert [:seon.agent/id id] :seon.agent/run
                                       [:seon.agent.run/id id-of-run]))
                  true
                  (conj (assoc turn-row :seon.agent.turn/id turn-id)))})}))]
    (if (:seon.error/message allocation)
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
                                                   :seon.agent.turn.usage/prompt-tokens
                                                   :seon.agent.turn.usage/completion-tokens
                                                   :seon.agent.turn.usage/cached-tokens
                                                   :seon.agent.turn.usage/input-tokens
                                                   :seon.agent.turn.usage/output-tokens
                                                   :seon.agent.turn.usage/cache-read-input-tokens
                                                   :seon.agent.turn.usage/cache-creation-input-tokens
                                                   :seon.agent.turn/llm-meta
                                                   :seon.agent.turn/usage-estimated?
                                                   :seon.agent.turn/llm-attempts
                                                   :seon.agent.turn/reply-blob
                                                   :seon.agent.turn/error]))]}))]
      (when (:seon.error/message close)
        (seon-log/error!
          {:seon.log/source ::close-turn
           :seon.log/agent id
           :seon.log/message (str "turn close-tx FAILED for turn " id-of-turn)
           :seon.log/data {::close close}}))
      ;; Pin the close transaction's returned database value onto the body
      ;; result: the final turn pull consumes exactly this value, so a
      ;; transaction landing between close and pull cannot alter the
      ;; returned turn (frozen-turn-inputs acceptance 3). Absent on a
      ;; failed close-tx — run-turn-body! then fails the turn loudly.
      (cond-> result
        (:db-after close) (assoc ::close-db-after (:db-after close))))
    (catch :default e
      ;; Mark the turn :error best-effort, then preserve the pre-allocation
      ;; propagation contract. Scheduled/direct callers still observe a
      ;; rejected Promise; the committed id travels with that rejection.
      (let [message (turn-error-str e)
            failure-data (ex-data e)
            child-retired? (execution-child-retired? failure-data)
            child-evidence (execution-child-evidence failure-data)]
        (when-not child-retired?
          (try
            (await (db/transact!
                    {:seon.db/tx-data
                     [{:seon.agent.turn/id     id-of-turn
                       :seon.agent.turn/status :error
                       :seon.agent.turn/error  message}]}))
            (catch :default _ nil)))
        (throw
          (ex-info message
                   (cond->
                    {:seon.agent.turn/id     id-of-turn
                     :seon.agent.turn/status :error
                     :seon.error/data        message}
                     child-retired?
                     (assoc ::execution/child-retired? true
                            :seon.error/data child-evidence))
                   e))))))

(defn ^:async ^:private pod-transport!
  "ONE adapter attempt under the per-attempt wall-clock cap.

   Races one signal-bearing request against
   the resolution's frozen `:seon.ai/agent-attempt-timeout-ms` (resolved once
   at the turn's acquisition,
   via the ONE racer
   ([[seon.eval/race-timeout]]) — the inner bound that keeps a single attempt
   from parking the turn when the adapter's own `:seon.ai/timeout-ms` is
   unset/huge. A timed-out attempt resolves to a `:seon.ai/error` VALUE
   (`:seon.ai/timeout? true` — never a throw), so [[llm-retryable?]]
   classifies it exactly like an adapter-side timeout. When the cap wins it
   aborts the attempt's provider signal; the run's in-tx CAS work-fence remains
   the final publication backstop for any late settler.

   `stream?` (repl-mode `:stream`) hands the llm-fn the WIDENED map arg
   canonical request map. In repl-mode `:stream`, `:seon.ai/stream? true` asks
   the adapter to consume the SDK stream and stop it at the first complete
   form. Every retry gets a fresh controller."
  [{resolution :seon.ai/config-resolution
    prompt-text :seon.ai/ctx
    system-prompt :seon.ai/system-prompt
    stream? :seon.ai/stream?
    ms :seon.ai/request-timeout-ms
    llm-fn :seon.agent/llm-fn}]
  (let [
        controller (js/AbortController.)
        signal     (.-signal controller)
        arg        (cond-> {:seon.ai/ctx          prompt-text
                            :seon.ai/abort-signal signal
                            :seon.ai/config-resolution resolution}
                     (string? system-prompt)
                     (assoc :seon.ai/system-prompt system-prompt)
                     stream? (assoc :seon.ai/stream? true))
        v          (await (seval/race-timeout
                            (llm-fn arg)
                            ms
                            (fn [] (.abort controller))))]
    (let [outer-timeout? (seval/timed-out? v)
          response
          (if outer-timeout?
            {:seon.ai/error
             {:seon.ai/msg      (str "LLM attempt exceeded the per-attempt "
                                     "cap (" ms "ms) — provider request cancelled")
              :seon.ai/timeout? true}}
            v)]
      (cond-> response
        outer-timeout?
        (assoc-in [:seon.ai/error :seon.ai/outer-timeout?] true)))))

;;; CLAIM-NATIVE POD PHASE LEAF

(defn ^:async render-phase!
  "Render and commit one addressable `:rendered` turn under a held epoch."
  [{:seon.agent.driver/keys [run]
    claim-epoch :seon.agent.run/claim-epoch
    database :seon.db/db}]
  (let [agent-id (:seon.agent/id run)
        run-id (:seon.agent.run/id run)
        rendered (await (render-prompt agent-id database [] run-id))]
    (if (:seon.error/message rendered)
      rendered
      (let [prompt (:seon.render/text rendered)
            system-prompt (:seon.ai/system-prompt rendered)
            full-prompt
            (ai/debug-full-prompt
             {:seon.ai/ctx prompt
              :seon.ai/system-prompt system-prompt})
            prompt-blob (await (capture-blob! full-prompt :prompt))
            allocation
            (await
             (db.id/allocate!
              {::db/db database
               ::db.id/allocations
               [{::db.id/key ::claim-turn
                 ::db.id/identity-attr :seon.agent.turn/id}]
               ::db.id/transaction-builder
               (fn [{turn-id ::claim-turn}]
                 {::db/tx-data
                  (into
                   (run.core/run-fence agent-id run-id claim-epoch)
                   [(cond->
                     {:seon.agent.turn/id turn-id
                      :seon.agent.turn/at (js/Date.)
                      :seon.agent.turn/status :running
                      :seon.agent.turn/phase :rendered
                      :seon.agent.turn/run [:seon.agent.run/id run-id]
                      :seon.agent.turn/prompt-chars (count full-prompt)
                      :seon.agent.turn/rendered-tx (:t database)}
                      prompt-blob
                      (assoc :seon.agent.turn/prompt-blob prompt-blob))])})}))]
        (if (:seon.error/message allocation)
          allocation
          {:seon.db/db (:db-after allocation)
           :seon.agent.turn/id
           (get-in allocation [::db.id/ids ::claim-turn])})))))

(defn ^:async llm-phase!
  "Pod leaf over the one portable durable LLM phase."
  [{:seon.agent.driver/keys [run] :as request}]
  (let [llm-fn (:seon.agent/llm-fn request)]
    (await
     (turn.llm/llm-phase!
      (assoc request
             :seon.agent.turn/now! #(js/Date.)
             :seon.agent.turn/resolve-context!
             (fn [agent-id database run-id]
               (-> (render-prompt agent-id database [] run-id)
                   (.then
                    (fn [rendered]
                      (-> (db/pull
                           {::db/db database
                            ::db/pull-pattern
                            [:seon.config.llm-retry/maximum-wait-ms
                             :seon.config.llm-retry/maximum-total-wait-ms
                             :seon.config.llm-retry/default-retries]
                            ::db/ref
                            [:seon.config/id "cluster"]})
                          (.then #(merge rendered %)))))))
             :seon.agent.turn/transport!
             #(pod-transport! (assoc % :seon.agent/llm-fn llm-fn)))))))

(defn ^:async publish-phase!
  "Publish the evaled program and close its turn under the held epoch."
  [{:seon.agent.driver/keys [run]
    claim-epoch :seon.agent.run/claim-epoch
    database :seon.db/db}]
  (let [agent-id (:seon.agent/id run)
        run-id (:seon.agent.run/id run)
        current-turn (:seon.agent.run/current-turn run)
        turn-id (:seon.agent.turn/id current-turn)
        turn
        (await
         (db/pull
          {::db/db database
           ::db/pull-pattern
           [:seon.agent.turn/id
            {:seon.agent.turn/reply-blob [:my.blob/hash]}
            {:seon.agent.turn/evals
             [:seon.eval/id :seon.eval/status :seon.eval/ok?]}]
           ::db/ref [:seon.agent.turn/id turn-id]}))
        reply
        (blob/get
         {:my.blob/hash
          (get-in turn [:seon.agent.turn/reply-blob :my.blob/hash])})]
    (if-not (:my.blob/ok? reply)
      {:seon.error/message (:my.blob/error reply)
       :seon.error/kind :core-bug}
      (let [program
            (turn.core/reply-program
             (:my.blob/content reply) false (home/home-ns agent-id))
            evals (:seon.agent.turn/evals turn)
            batch
            {:seon.eval/ids (mapv :seon.eval/id evals)
             :seon.eval/n-ok (count (filter :seon.eval/ok? evals))
             :seon.eval/n-fail (count (remove :seon.eval/ok? evals))}
            publication
            (await
             (plan/publish-generated-program!
              {::db/db database
               :seon.agent.run/id run-id
               :seon.agent.turn/id turn-id
               :my.plan/program program
               :my.plan/eval-batch batch}))]
        (if (false? (:my.plan/ok? publication))
          {:seon.error/message (:my.plan/error publication)
           :seon.error/kind :core-bug}
          (let [head (await (db/db))
                report
                (await
                 (db/transact!
                  {::db/db head
                   ::db/tx-data
                   (turn.core/advance-phase-tx-data
                    (run.core/run-fence agent-id run-id claim-epoch)
                    turn-id :evaled :published
                    [{:seon.agent.turn/id turn-id
                      :seon.agent.turn/status :done}])}))]
            (if (:seon.error/message report)
              report
              {:seon.db/db (:db-after report)})))))))
