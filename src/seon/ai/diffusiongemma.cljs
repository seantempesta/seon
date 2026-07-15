(ns seon.ai.diffusiongemma
  "DiffusionGemma CONTROL backend — the transformers RunPod worker that
   keeps the per-step LogitsProcessor/accept_canvas seam (the FROZEN cuda
   worker's identifier — its code-buffer accept hook) (clamp / infill /
   eval-renoise). ^:async — returns Promises.

   TRANSPORT is a RunPod ASYNC JOB, not one round-trip: SUBMIT a job to
   `POST https://api.runpod.ai/v2/{EP}/run` then POLL
   `GET …/status/{job-id}` until COMPLETED / FAILED / CANCELLED. The
   cold-start (provision A100 + load the model, ~66s) lives INSIDE the
   poll loop (status IN_QUEUE / IN_PROGRESS), not the HTTP timeout — so
   the poll budget ([[*max-polls*]] × [[*poll-ms*]]) is generous, and the
   retry layer only re-fires on a genuinely failed submit/poll.

   This is the `:control` backend ONLY. The `:vllm` backend is NOT here —
   `:diffusiongemma`+`:vllm` reuses `seon.ai.openai-compat` unchanged (vLLM
   serves the OpenAI-compatible `/v1/chat/completions`). Backend selection
   is `seon.ai/dg-backend` (env SEON_DG_BACKEND, DB-ownable).

   ERRORS-ARE-VALUES (the `:seon.ai/error` envelope, `seon.ai`): a fetch
   throw (DNS/refused/reset — the cold-start transient) or a RunPod 5xx /
   429 maps to `:seon.ai/transport?` / `:seon.ai/status` so
   `seon.agent.turn/call-llm!` → `seon.retry/with-retry!` retries it with
   zero new retry code; a FAILED/CANCELLED job or an in-band per-mode
   `*_error` maps to a plain `:seon.ai/msg` (a PROCESSING error — never
   retried, matching the openai adapter's non-retryable-parse stance).

   CONFIG (read PER CALL, reactive-context — no cache; the key value is
   NEVER stored or logged): the endpoint id from `SEON_DG_ENDPOINT` (falling
   back to `DIFFGEMMA_EP`, the diffusion experiment driver's var, so one
   endpoint id serves both); the bearer key from the env var NAMED by
   `SEON_DG_API_KEY_ENV` (default `RUNPOD_API_KEY`), read from `process.env`
   at call time. The `:seon.ai/config` row's `:seon.ai/max-tokens` becomes
   the worker's `max_new_tokens` (honored like every other provider).

   One agent-facing fn: [[agent-adapter]] returns `(fn [ctx-string])`
   compatible with `seon.agent`'s `llm-fn` (defaults `mode=generate` +
   the FAST throughput knobs proven on the A100: entropy_bound 0.5,
   temp 0.8→0.4, 48-step cap)."
  (:require [clojure.string :as str]
            [seon.ai :as ai]
            [seon.config :as config]
            [seon.error :as error]
            [seon.platform :as platform]
            [seon.schema :as schema]))

;; ============================================================
;; Schemas — the worker's mode + knob vocabulary, the generic control
;; request, and the normalized response. The shared :seon.ai/* field
;; vocabulary (text, error envelope) lives in seon.ai.
;; ============================================================

(schema/register! ::mode [:enum :generate :guided :clamp-smoke :infill :introspect :probe
                          :fill :rank :step])
(schema/register! ::prompt :string)
(schema/register! ::max-new-tokens :int)
(schema/register! ::trace [:enum :code-buffer :entropy])
;; denoise tuning — the worker's _gen_overrides. entropy_bound is the
;; commit-rate dial; all optional, absent = the worker's gen-config defaults.
(schema/register! ::entropy-bound :double)
(schema/register! ::max-denoising-steps :int)
(schema/register! ::t-min :double)
(schema/register! ::t-max :double)
(schema/register! ::stability-threshold :int)
(schema/register! ::confidence-threshold :double)
;; clamp_smoke / infill inputs — third-party-shaped (the worker's code-buffer
;; positions), so a :map boundary.
(schema/register! ::clamp-text [:map-of :string :string])  ; {code-buffer-pos → token-string}
(schema/register! ::prefix :string)
(schema/register! ::suffix :string)
(schema/register! ::max-hole-tokens :int)
(schema/register! ::expect-contains :string)
;; guided-mode inputs (the worker's guided loop — round-denoise → oracle
;; check → repair → lock-and-execute → hint-scramble → in-loop checks).
;; JSON-ready third-party shapes: strings/numbers/bools/arrays only, the
;; checks entries string-keyed ({"call" …, "expect" …}) — NO kebab maps.
(schema/register! ::phase :string)                       ; grammar phase, e.g. "schemas"|"tests"|"functions"
(schema/register! ::hints :boolean)                      ; clamp `; fix:` hint comments on scrambled spans
(schema/register! ::repair :boolean)                     ; auto-repair provable near-misses ($0 forwards)
(schema/register! ::checks [:vector [:map-of :string :string]]) ; T3 behavioral [{call, expect}]
(schema/register! ::prelude :string)                     ; forms eval'd into the session before checks
(schema/register! ::max-rounds :int)
(schema/register! ::max-attempts :int)
(schema/register! ::seed :int)
;; typeahead cursor inputs (mode=step, worker.py `_cursor` — see
;; typeahead-design.md "Wire modes"). All wire-shaped: string-keyed offer
;; maps ({"glyph" "①" "label" … "template" [["clamp" "…"]["free" 24]]})
;; and a string-keyed policy map of the worker Policy's snake_case knobs
;; (Policy(**payload["policy"]) — an unknown key TypeErrors worker-side,
;; so only KNOWN knobs may ride).
(schema/register! ::committed :string)               ; locked forms so far
(schema/register! ::draft :string)                   ; the in-progress code-buffer text
;; "prefill" is the EDIT-WITH-PREFILL segment (planner-worker-design W2):
;; the hole starts holding the given text — the model edits, not regenerates.
(schema/register! ::template-segment
  [:or [:tuple [:= "clamp"] :string] [:tuple [:= "free"] :int]
   [:tuple [:= "prefill"] :string]])
(schema/register! ::offer
  [:map-of :string [:or :string [:vector ::template-segment]]])
(schema/register! ::offers [:vector ::offer])
;; The draft-head argument affordance map: head fn sym → its prefilled
;; template. Registry-derived seon-side (seon.ai.typeahead); the worker
;; expands it when the draft is an opened call to a listed head.
(schema/register! ::prefills [:map-of :string [:vector ::template-segment]])
(schema/register! ::policy [:map-of :string [:or :double :int :boolean]])
(schema/register! ::null-render :string)             ; glyph-calibration baseline render

;; The generic control request: a mode + the knobs it uses.
(schema/register! ::request
  [:map
   [::mode ::mode]
   [::prompt              {:optional true} ::prompt]
   [::max-new-tokens      {:optional true} ::max-new-tokens]
   [::trace               {:optional true} ::trace]
   [::entropy-bound       {:optional true} ::entropy-bound]
   [::max-denoising-steps {:optional true} ::max-denoising-steps]
   [::t-min               {:optional true} ::t-min]
   [::t-max               {:optional true} ::t-max]
   [::stability-threshold {:optional true} ::stability-threshold]
   [::confidence-threshold {:optional true} ::confidence-threshold]
   [::clamp-text          {:optional true} ::clamp-text]
   [::prefix              {:optional true} ::prefix]
   [::suffix              {:optional true} ::suffix]
   [::max-hole-tokens     {:optional true} ::max-hole-tokens]
   [::expect-contains     {:optional true} ::expect-contains]
   [::phase               {:optional true} ::phase]
   [::hints               {:optional true} ::hints]
   [::repair              {:optional true} ::repair]
   [::checks              {:optional true} ::checks]
   [::prelude             {:optional true} ::prelude]
   [::max-rounds          {:optional true} ::max-rounds]
   [::max-attempts        {:optional true} ::max-attempts]
   [::seed                {:optional true} ::seed]
   [::committed           {:optional true} ::committed]
   [::draft               {:optional true} ::draft]
   [::offers              {:optional true} ::offers]
   [::prefills            {:optional true} ::prefills]
   [::policy              {:optional true} ::policy]
   [::null-render         {:optional true} ::null-render]
   [:seon.ai/abort-signal {:optional true} :seon.ai/abort-signal]])

;; The worker `output` map is Google/RunPod's shape, not seon's — a :map
;; boundary (like :seon.ai/provider-fields). We surface a normalized
;; :seon.ai/text (output.text / completion_text / middle_text per mode)
;; for the agent loop, the RAW worker output for experiments and deterministic
;; adapter tests, and the errors-as-values envelope.
(schema/register! ::worker-output :map)
(schema/register! ::response
  [:map
   [:seon.ai/text :string]                          ; "" on non-text modes / on error
   [::worker-output {:optional true} ::worker-output]
   [:seon.ai/error  {:optional true} :seon.ai/error]])

(schema/register! ::opts :map)

;; ============================================================
;; Config — endpoint + key resolution. Read per call; never stored.
;; ============================================================

(def ^:private runpod-root "https://api.runpod.ai/v2")
(def ^:private default-key-env "RUNPOD_API_KEY")
(def ^:private label "DiffusionGemma")

;; The FAST throughput knobs proven on the A100 (~500 tok/s, correct
;; Clojure) — the agent-loop defaults; any opt or per-call field wins.
(def ^:private default-gen-opts
  {::mode :generate ::entropy-bound 0.5 ::t-max 0.8 ::t-min 0.4
   ::max-denoising-steps 48})

;; Poll cadence — overridable for tests (root set!, like *fetch*). The
;; cold-start (~66s) lives in this loop; 200 × 3s ≈ 10min total budget.
;; A LOCAL (full-URL) worker answers in ~0.5–3 s per step, so a 3 s poll
;; quantizes EVERY step's wall (W2 plan-pass measurement: 0.9 s of gen
;; billed ~3 s of wall) — local endpoints poll at *local-poll-ms* with a
;; proportionally larger budget (same ~10 min total).
(def ^:dynamic *poll-ms* 3000)
(def ^:dynamic *local-poll-ms* 250)
(def ^:dynamic *max-polls* 200)

;; Test seam ONLY — bound to a `(fn [url init]) → Promise<js/Response>`,
;; the adapter calls it instead of Node's native fetch so wire/error tests
;; drive submit→poll WITHOUT a network. nil (default) → js/fetch. A
;; dynamic var (root set! in tests) for the same reason as the openai
;; adapter's *fetch*: the instrumented ^:async body runs past a binding's
;; synchronous unwind.
(def ^:dynamic *fetch* nil)

(defn- endpoint-id
  "The RunPod endpoint id — `SEON_DG_ENDPOINT`, falling back to
   `DIFFGEMMA_EP` (the diffusion experiment driver's var) so ONE endpoint
   id set in `.env` serves BOTH the Python driver and this provider. nil
   when neither is set."
  []
  ;; SEON_DG_* names kept for continuity — the local process is now
  ;; named `diffusion-server` (bin/seon), but the env contract is frozen.
  (or (config/env-string "SEON_DG_ENDPOINT")
      (config/env-string "DIFFGEMMA_EP")))

(defn- base-url
  "The worker base URL, or nil when no endpoint is configured.

   A bare RunPod endpoint id (`\"u50y7khhos5t7o\"`) resolves under
   `https://api.runpod.ai/v2/{EP}`; a full `http(s)://…` value is used
   AS the base — that is how a LOCAL worker speaking the same wire
   contract (e.g. the dg_mlx MLX worker on
   `http://127.0.0.1:17860`) plugs in with zero other changes."
  []
  (when-let [ep (endpoint-id)]
    (if (str/starts-with? ep "http")
      ep
      (str runpod-root "/" ep))))

(defn- resolved-api-key
  "The bearer key for this call, or nil — read from process.env at call
   time off the var NAMED by SEON_DG_API_KEY_ENV (default RUNPOD_API_KEY).
   The key value is never transacted or logged."
  []
  ;; SEON_DG_* env names kept for continuity (process renamed diffusion-server).
  (platform/env-val (or (config/env-string "SEON_DG_API_KEY_ENV") default-key-env)))

(defn- local-endpoint?
  "Whether the configured endpoint is a full `http(s)://…` worker URL.

   A LOCAL worker (e.g. dg_mlx on `http://127.0.0.1:17860`) speaks the
   same wire contract but needs NO RunPod bearer key — the key
   requirement applies only to bare RunPod endpoint ids."
  []
  (boolean (some-> (endpoint-id) (str/starts-with? "http"))))

(defn api-configured?
  "Whether the endpoint id resolves, plus a bearer key when required.

   A bare RunPod endpoint id needs the bearer key too; a full-URL local
   worker needs no key. `seon.client/current-llm-fn` uses this to fall
   back to the stub llm-fn when the worker isn't configured."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (boolean (and (endpoint-id)
                (or (local-endpoint?) (resolved-api-key)))))

;; ============================================================
;; Request → the worker's snake_case JSON payload.
;; ============================================================

;; ::request attr → the worker payload's JSON key (gpu_worker.py's
;; **kwargs). Keyword values (mode, trace) serialize via name; the
;; clamp-text map rides as a JS object.
(def ^:private payload-keys
  {::mode                "mode"
   ::prompt              "prompt"
   ::max-new-tokens      "max_new_tokens"
   ::trace               "trace"
   ::entropy-bound       "entropy_bound"
   ::max-denoising-steps "max_denoising_steps"
   ::t-min               "t_min"
   ::t-max               "t_max"
   ::stability-threshold "stability_threshold"
   ::confidence-threshold "confidence_threshold"
   ::clamp-text          "clamp_text"
   ::prefix              "prefix"
   ::suffix              "suffix"
   ::max-hole-tokens     "max_hole_tokens"
   ::expect-contains     "expect_contains"
   ::phase               "phase"
   ::hints               "hints"
   ::repair              "repair"
   ::checks              "checks"
   ::prelude             "prelude"
   ::max-rounds          "max_rounds"
   ::max-attempts        "max_attempts"
   ::seed                "seed"
   ::committed           "committed"
   ::draft               "draft"
   ::offers              "offers"
   ::prefills            "prefills"
   ::policy              "policy"
   ::null-render         "null_render"})

(defn- ->json-value
  "A field value as the worker's JSON wants it: a keyword (mode / trace)
   becomes its snake_case name (`:clamp-smoke` → \"clamp_smoke\"); any
   other value (string / number / the clamp-text map) rides as-is."
  [v]
  (if (keyword? v)
    (str/replace (name v) "-" "_")
    v))

(defn request->payload
  "The `::request` map as the worker's snake_case payload.

   Only the present fields; mode/trace keywords → their snake_case name
   (`:clamp-smoke` → \"clamp_smoke\"). Public for tests + live debugging."
  {:malli/schema [:=> [:cat ::request] :map]}
  [request]
  (reduce-kv
    (fn [m attr json-key]
      (if-some [v (get request attr)]
        (assoc m json-key (->json-value v))
        m))
    {}
    payload-keys))

;; ============================================================
;; Worker output → the normalized response.
;; ============================================================

;; mode → the worker output field carrying the generated text.
(def ^:private text-key-by-mode
  {:generate :text :guided :text :clamp-smoke :completion_text :infill :middle_text})

;; in-band per-mode failure keys the worker returns on a COMPLETED job
;; (it does NOT raise to the HTTP layer for a generation failure).
;; :guided errors ride `gen_error` too; a guided `done:false` is NOT an
;; error — it is an honest partial whose `text` + loop metadata (done,
;; attempts, rounds, locked_forms, repairs, checks_passed, decoder_forwards,
;; tok_per_s) surface via `::worker-output` on `:seon.ai/raw` unchanged.
(def ^:private mode-error-keys [:gen_error :clamp_smoke_error :infill_error])

(defn normalize-output
  "Map a COMPLETED worker `output` map to a `::response`.

   An in-band per-mode `*_error` → a processing `:seon.ai/error` (NOT
   transport — not retried). Otherwise `:seon.ai/text` from the mode's text
   field (\"\" on non-text modes), the whole output under `::worker-output`.
   Public for tests."
  {:malli/schema [:=> [:catn [::mode ::mode]
                       [::worker-output [:maybe ::worker-output]]] ::response]}
  [mode output]
  (let [output (or output {})]
    (if-let [emsg (some #(get output %) mode-error-keys)]
      (let [err {:seon.ai/msg (str label " " (name mode) " error: " emsg)}]
        (ai/log-error! label err)
        {:seon.ai/text "" :seon.ai/error err})
      (let [text (get output (get text-key-by-mode mode))]
        {:seon.ai/text   (if (string? text) text "")
         ::worker-output output}))))

;; ============================================================
;; Error envelopes — onto the shared :seon.ai/error vocabulary.
;; ============================================================

(defn- config-error
  "Envelope for a call-time config gap (no endpoint / no key). Never
   transport-flagged. Logged loudly, returned as a value."
  [msg]
  (let [err {:seon.ai/msg msg}]
    (ai/log-error! label err)
    {:seon.ai/text "" :seon.ai/error err}))

(defn- transport-error
  "Envelope for a fetch THROW (DNS/refused/reset — the cold-start
   transient). Transport-flagged → retryable by the agent turn loop."
  [phase e]
  (let [err {:seon.ai/msg        (str label " " phase " connection failed: "
                                      (error/->message e))
             :seon.ai/transport? true}]
    (ai/log-error! label err)
    {:seon.ai/text "" :seon.ai/error err}))

(defn- http-error
  "Envelope for a RunPod non-2xx (`status`) on submit/poll. 5xx/429 are
   retryable via the turn loop's `llm-retryable?` (on :status); a parsed
   `Retry-After` rides through. Never transport-flagged."
  [phase status retry-after body]
  (let [ra  (ai/parse-retry-after-ms retry-after)
        err (cond-> {:seon.ai/msg    (str label " " phase " HTTP " status ": "
                                          (pr-str body))
                     :seon.ai/status status}
              ra (assoc :seon.ai/retry-after-ms ra))]
    (ai/log-error! label err)
    {:seon.ai/text "" :seon.ai/error err}))

(defn- job-error
  "Envelope for a FAILED/CANCELLED job, or a poll-budget exhaustion — a
   processing error (NOT transport-flagged, not retried)."
  [msg]
  (let [err {:seon.ai/msg (str label " " msg)}]
    (ai/log-error! label err)
    {:seon.ai/text "" :seon.ai/error err}))

(defn- abort-error
  "Non-retryable errors-as-data result for an externally aborted attempt."
  []
  {:seon.ai/text ""
   :seon.ai/error {:seon.ai/msg      (str label " request aborted")
                   :seon.ai/timeout? true}})

;; ============================================================
;; The submit + poll loop.
;; ============================================================

(defn- fetch-fn [] (or *fetch* js/fetch))

(defn- ^:async sleep!
  "Resolve after `ms`, or immediately when `signal` aborts."
  [ms signal]
  (js/Promise.
    (fn [resolve _]
      (if (ai/aborted? signal)
        (resolve nil)
        (let [!timer (volatile! nil)
              on-abort (fn []
                         (js/clearTimeout @!timer)
                         (resolve nil))]
          (vreset! !timer
                   (js/setTimeout
                     (fn []
                       (when signal
                         (.removeEventListener signal "abort" on-abort))
                       (resolve nil))
                     ms))
          (when signal
            (.addEventListener signal "abort" on-abort #js{:once true})))))))

(defn- ^:async fetch-resp
  "Call the injectable fetch and parse the JSON body. Returns
   `{:status :retry-after :body}`; a non-JSON body parses to `{}`. May
   THROW on a network failure (the caller maps it to transport)."
  [url init]
  (let [resp (await ((fetch-fn) url init))
        body (await (-> (.json resp) (.catch (fn [_] #js{}))))]
    {:status      (.-status resp)
     :retry-after (some-> resp .-headers (.get "retry-after"))
     :body        (js->clj body :keywordize-keys true)}))

(defn- auth-headers
  "Bearer headers when a `key` resolved; bare headers for a keyless
   LOCAL worker (see [[local-endpoint?]])."
  [key]
  (if key
    #js{"Authorization" (str "Bearer " key)}
    #js{}))

(defn- ^:async submit!
  "POST the job; returns the parsed `{:status :retry-after :body}`."
  [base key payload signal]
  (let [init #js{:method  "POST"
                 :headers (doto (auth-headers key)
                            (aset "Content-Type" "application/json"))
                 :body    (.stringify js/JSON (clj->js {:input payload}))}]
    (when signal (aset init "signal" signal))
    (fetch-resp (str base "/run") init)))

(defn- ^:async status!
  "GET the job status; returns the parsed `{:status :retry-after :body}`."
  [base key jid signal]
  (let [init #js{:method "GET" :headers (auth-headers key)}]
    (when signal (aset init "signal" signal))
    (fetch-resp (str base "/status/" jid) init)))

(defn- request-cancel!
  "Best-effort RunPod cancellation for an admitted remote job.

   The attempt's signal is already aborted, so the cleanup request deliberately
   has no signal. Its rejection is consumed locally; retry remains owned by the
   turn and cancellation cleanup never creates another provider attempt."
  [base key jid]
  (try
    (-> ((fetch-fn)
          (str base "/cancel/" jid)
          #js{:method "POST" :headers (auth-headers key)})
        (.catch (fn [e]
                  (js/console.warn
                    (str "[seon.ai.diffusiongemma] remote cancel failed for " jid ":")
                    e))))
    (catch :default e
      (js/console.warn
        (str "[seon.ai.diffusiongemma] remote cancel threw for " jid ":")
        e)))
  nil)

(defn- ^:async poll-to-terminal!
  "Poll `…/status/{jid}` until the job is terminal or the budget is
   exhausted; map the terminal state to a `::response`. A throw here
   propagates to [[complete]]'s catch (→ transport, retried)."
  [base key jid mode signal]
  (let [poll-ms   (if (local-endpoint?)
                    (min *poll-ms* *local-poll-ms*)   ; a test's 0 stays 0
                    *poll-ms*)
        max-polls (if (local-endpoint?)
                    (max *max-polls*
                         (quot (* *max-polls* *poll-ms*) (max poll-ms 1)))
                    *max-polls*)]
    (loop [polls 0]
      (if (ai/aborted? signal)
        (abort-error)
        (let [{:keys [status retry-after body]} (await (status! base key jid signal))]
          (if (>= status 400)
            (http-error "status" status retry-after body)
            (case (:status body)
              "COMPLETED"           (normalize-output mode (:output body))
              ("FAILED" "CANCELLED") (job-error (str "job " (:status body) ": " (pr-str body)))
              ;; IN_QUEUE / IN_PROGRESS / unknown → keep polling within budget.
              (if (>= polls max-polls)
                (job-error (str "job " jid " did not complete within " max-polls
                                " polls (last status " (pr-str (:status body)) ")"))
                (do (await (sleep! poll-ms signal))
                    (recur (inc polls)))))))))))

(defn ^:async complete
  "Submit a control-worker job and poll it to completion.

   Returns a Promise of a `::response`: `:seon.ai/text` normalized from the mode's
   output field, the RAW worker `output` under `::worker-output`, or the
   errors-as-values `:seon.ai/error` envelope (config gap / transport /
   HTTP status / failed job) — never a throw to the agent loop.

   Config gaps (no SEON_DG_ENDPOINT, no resolvable key) resolve to a
   legible config error. A fetch throw → transport-flagged (retryable);
   a RunPod 5xx/429 → status (retryable); a FAILED job / in-band
   `*_error` → a plain processing error (not retried)."
  {:malli/schema [:=> [:cat ::request] ::response]}
  [{::keys [mode] :as request}]
  (let [base (base-url)
        key  (resolved-api-key)
        signal (:seon.ai/abort-signal request)]
    (cond
      (nil? base)
      (config-error
        (str label " endpoint not configured — set SEON_DG_ENDPOINT (or "
             "DIFFGEMMA_EP) to the RunPod endpoint id (e.g. \"u50y7khhos5t7o\")"))

      ;; A bare RunPod endpoint id needs the bearer key; a full-URL local
      ;; worker does not (its wire has no auth).
      (and (nil? key) (not (local-endpoint?)))
      (config-error
        (str label " API key not found in process.env — set RUNPOD_API_KEY "
             "(or point SEON_DG_API_KEY_ENV at the env var holding the key)"))

      (ai/aborted? signal)
      (abort-error)

      :else
      (let [payload (request->payload request)
            !job-id (volatile! nil)
            !cancel-requested? (volatile! false)
            cancel-known! (fn []
                            (when (and @!job-id
                                       (not @!cancel-requested?))
                              (vreset! !cancel-requested? true)
                              (request-cancel! base key @!job-id)))
            arm-cancel! (fn []
                          (when (and signal @!job-id)
                            (if (ai/aborted? signal)
                              (cancel-known!)
                              (.addEventListener signal "abort" cancel-known!
                                                 #js{:once true}))))]
        (try
          (let [{:keys [status retry-after body]} (await (submit! base key payload signal))
                _ (when-let [jid (:id body)] (vreset! !job-id jid))
                _ (arm-cancel!)
                result
                (if (>= status 400)
                  (http-error "submit" status retry-after body)
                  (if-let [jid @!job-id]
                    (case (:status body)
                      ;; rare: /run already terminal — handle without polling.
                      "COMPLETED"            (normalize-output mode (:output body))
                      ("FAILED" "CANCELLED") (job-error (str "job " (:status body) ": " (pr-str body)))
                      (await (poll-to-terminal! base key jid mode signal)))
                    (job-error (str "submit returned no job id: " (pr-str body)))))]
            (if (ai/aborted? signal)
              (do (cancel-known!) (abort-error))
              result))
          (catch :default e
            (if (ai/aborted? signal)
              (do (cancel-known!) (abort-error))
              (transport-error "submit/poll" e)))
          (finally
            (when signal
              (.removeEventListener signal "abort" cancel-known!))))))))

;; ============================================================
;; Adapter for seon.agent — (fn [ctx-string]) → Promise<{:text … :raw …}>.
;; ============================================================

(defn ^:async ^:private complete+wrap
  "Call [[complete]] with the FAST gen defaults + `opts`, the ctx as the
   prompt; wrap into the turn-loop shape, lifting `:seon.ai/error` to the
   top level. Honors the `:seon.ai/config` row's `:seon.ai/max-tokens`
   (read PER CALL via `seon.ai/current`, reactive-context — no cache) as
   `::max-new-tokens`, so a downstream deployment retunes the output cap
   like any other provider; precedence is explicit opt > config row >
   the worker's gen-config default (no default-gen-opts cap)."
  [opts arg]
  (let [ctx-text (ai/llm-arg->ctx arg)
        signal   (ai/llm-arg->abort-signal arg)
        cfg-max (:seon.ai/max-tokens (ai/current))
        request (cond-> (merge default-gen-opts opts {::prompt ctx-text})
                  (and cfg-max (not (contains? opts ::max-new-tokens)))
                  (assoc ::max-new-tokens cfg-max)
                  signal (assoc :seon.ai/abort-signal signal))
        resp    (await (complete request))]
    (cond-> {:text        (:seon.ai/text resp)
             :seon.ai/raw resp}
      (:seon.ai/error resp) (assoc :seon.ai/error (:seon.ai/error resp)))))

(defn agent-adapter
  "A fn-of-ctx-string suitable for `seon.agent`'s `llm-fn`.

   The returned fn submits a `generate` job (ctx as the prompt) with the
   A100-proven fast knobs (entropy_bound 0.5, temp 0.8→0.4, 48-step cap)
   and returns a Promise of `{:text … :seon.ai/raw <response>}` — plus a
   top-level `:seon.ai/error` when the call failed. Optional `opts`
   override the gen defaults (e.g. `{:seon.ai.diffusiongemma/entropy-bound
   0.3}`)."
  {:malli/schema
   [:function
    [:=> [:cat] :any]
    [:=> [:catn [::opts ::opts]] :any]]}
  ([] (agent-adapter {}))
  ;; Accept the widened string-or-map llm-fn arg (repl-mode); this adapter
  ;; buffers, so it uses the ctx and ignores `:seon.ai/stream?`.
  ([opts] (fn [arg] (complete+wrap opts arg))))
