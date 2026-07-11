(ns seon.ai.typeahead
  "Typeahead STEP-LOOP provider — the diffusion worker driven form-by-form.

   The swap-in generation surface of typeahead-design.md P3b: selected by
   `SEON_AI_PROVIDER=typeahead` (OFF by default — nothing in a default
   `.env` activates it), same endpoint/key config as `:diffusiongemma`
   (`SEON_DG_ENDPOINT`; a full `http(s)://…` value is a local worker).

   One provider call = one STEP LOOP. Each round submits `mode=step` to
   the worker (via the ONE wire path, [[seon.ai.diffusiongemma/complete]])
   with `{prompt: the rendered context, committed, draft, offers,
   policy, null_render}` — offers/policy derived from the SAME data the
   rendered menu shows (`seon.agent.ctx.menu/verb-offers` + `/policy`),
   so glyph N on the wire is glyph N in the prompt, and `null_render`
   ([[null-render]] — the prompt minus its transcript event log) is the
   null-intent baseline the worker calibrates glyph posteriors against
   before any auto-offer thresholding. The worker answers
   `{transition, new_draft, locked, glyph, readouts, …}`; locked forms
   thread forward as the next round's `committed`, the draft carries
   over, and the loop stops on `done`, repeated `stuck`, or the policy
   `:seon.typeahead/max-rounds` cap.

   THE POD IS THE AUTHORITATIVE EVAL SESSION: worker-side locking is
   parse-gated only and is NOT trusted as proof. The provider assembles
   its reply text from the locked forms exactly as an LLM reply would
   carry them; the existing turn pipeline then evals them through
   `seon.eval` as usual (tee, instrumentation, error envelopes
   unchanged). The provider produces reply TEXT; the loop owns eval —
   which also means no mid-loop `;; => result` feedback is possible here
   (results reach the model next turn via the transcript's real `⟹`
   rows; see the P3b report for the seam mismatch).

   Errors are values (`:seon.ai/error`, shaped by the diffusiongemma
   adapter): a worker transport failure / 5xx is retryable by
   `seon.agent.turn/call-llm!` (the SOLE retry authority — this ns adds
   NO retry loop; one attempt per step); an in-band `gen_error` is a
   processing error, never retried.

   Per-step OBSERVABILITY: each round transacts one small
   `:seon.typeahead/*` step projection (transition, glyph, calibrated
   margin, EOS logprob, forwards — datoms are projections; full
   posteriors/events are NOT persisted). [[steps-tile-html]] renders the
   last call's trace as the agent-page tile; the block installs itself
   (once, via the ONE `ctx/install!` verb) on the first recorded step."
  (:require
    [clojure.string :as str]
    [seon.agent.ctx :as ctx]
    [seon.agent.ctx.menu :as menu]
    [seon.ai :as ai]
    [seon.ai.diffusiongemma :as dg]
    [seon.db :as db]
    [seon.schema :as schema]))

;; ============================================================
;; Step projections — one row per mode=step round. Attribute keywords
;; live under :seon.typeahead/* (the design-pinned driver keyword ns —
;; the policy row + offer shapes in seon.agent.ctx.menu share it).
;; Small scalars only: posterior vectors / event lists stay off the DB
;; (three-tier rule — they ride :seon.ai/raw for the turn that made
;; them and are otherwise derivable by re-running the step).
;; ============================================================

(schema/register! :seon.typeahead/call :string)          ; one provider invocation
(schema/register! :seon.typeahead/step-idx :int)
(schema/register! :seon.typeahead/at :inst)
(schema/register! :seon.typeahead/transition :keyword)   ; :progress :expand :grow :repair :stuck :done
(schema/register! :seon.typeahead/margin :double)        ; calibrated glyph margin (nats)
(schema/register! :seon.typeahead/eos-logprob :double)   ; done-ness meter readout
(schema/register! :seon.typeahead/forwards :int)         ; decoder forwards this step
(schema/register! :seon.typeahead/locked-count :int)
(schema/register! :seon.typeahead/agent :seon.db/ref)

(schema/register! :seon.typeahead/step
  [:map {:seon.db/entity true}
   [:seon.typeahead/call         :seon.typeahead/call]
   [:seon.typeahead/step-idx     :seon.typeahead/step-idx]
   [:seon.typeahead/at           :seon.typeahead/at]
   [:seon.typeahead/transition   :seon.typeahead/transition]
   [:seon.typeahead/locked-count :seon.typeahead/locked-count]
   [:seon.typeahead/glyph        {:optional true} :seon.typeahead/glyph]
   [:seon.typeahead/margin       {:optional true} :seon.typeahead/margin]
   [:seon.typeahead/eos-logprob  {:optional true} :seon.typeahead/eos-logprob]
   [:seon.typeahead/forwards     {:optional true} :seon.typeahead/forwards]
   [:seon.typeahead/agent        {:optional true} :seon.db/ref]])

;; ============================================================
;; Pure shape fns — wire conversion, projection, reply assembly.
;; Public for tests.
;; ============================================================

(schema/register! ::call-id :seon.typeahead/call)
(schema/register! ::step-idx :seon.typeahead/step-idx)
;; The worker's step output map — a third-party wire boundary (same
;; stance as seon.ai.diffusiongemma's ::worker-output).
(schema/register! ::step-output :map)
(schema/register! ::locked-forms [:vector :string])
(schema/register! ::final-draft :string)
(schema/register! ::outcome [:enum :done :gave-up :round-cap])
(schema/register! ::steps [:vector :seon.typeahead/step])
(schema/register! ::opts :map)

(defn offers->wire
  "Menu offers as the worker's string-keyed `offers` wire maps."
  {:malli/schema [:=> [:catn [::offers :seon.agent.ctx.menu/offers-view]]
                  :seon.ai.diffusiongemma/offers]}
  [offers]
  (mapv (fn [{:seon.typeahead/keys [glyph label template]}]
          {"glyph" glyph "label" label "template" template})
        offers))

(defn policy->wire
  "The policy row's knobs as the worker Policy's snake_case wire map.

   Only knobs the worker `Policy` dataclass KNOWS may ride
   (`Policy(**policy)` TypeErrors on unknown keys): auto-offer margin,
   probe budget (→ `probe_lengths`), menu cap (→ `glyph_page_size`) and
   the round budget. `:seon.typeahead/worst-token-gate` (a probability)
   deliberately does NOT map onto `worst_entropy_gate` (nats) — the
   units differ; the worker keeps its measured default."
  {:malli/schema [:=> [:catn [::policy :seon.agent.ctx.menu/policy-view]]
                  :seon.ai.diffusiongemma/policy]}
  [{:seon.typeahead/keys [auto-offer-margin probe-budget menu-cap max-rounds]}]
  {"auto_offer_margin" auto-offer-margin
   "probe_lengths"     probe-budget
   "glyph_page_size"   menu-cap
   "max_rounds"        max-rounds})

;; The transcript section's ai-view brackets (seon.agent.ctx/block-bracket-ai
;; with the :transcript block name) — the delimiters [[null-render]] keys on.
(def ^:private transcript-begin ";;; ┌─ transcript ─")
(def ^:private transcript-end   ";;; └─ end transcript ─")

(defn- strip-section
  "`prompt` with the whole `;;; ┌─ <name> ─ … ;;; └─ end <name> ─`
   section removed (blank-line seam preserved); unchanged when absent."
  [prompt section-name]
  (let [begin (str ";;; ┌─ " section-name " ─")
        end   (str ";;; └─ end " section-name " ─")
        i     (.indexOf prompt begin)
        j     (when-not (neg? i) (.indexOf prompt end i))]
    (if (or (neg? i) (nil? j) (neg? j))
      prompt
      (str (str/trimr (subs prompt 0 i))
           "\n\n"
           (str/triml (subs prompt (+ j (count end))))))))

;; Sections DERIVED from the current task intent — the plan tree the
;; intent spawned and its glyph-ledger view. They restate the task, so a
;; baseline keeping them would already carry the intent and calibration
;; would cancel the very signal it protects (verified on a captured acme
;; prompt blob: the intent text recurs in both).
(def ^:private intent-derived-sections ["plan" "plan-ledger"])

(defn null-render
  "The null-intent calibration render derived from the rendered prompt.

   The design's calibration rule (typeahead-design \"The glyph
   vocabulary\"): auto-offers threshold CALIBRATED glyph margins — raw
   posteriors minus a baseline measured under the SAME render with the
   task intent removed (position bias is measured and real: first-slot
   inflation −0.0 vs −6.4). Derivation (the documented choice):

   - inside the `transcript` section, the EVENT LOG — everything from
     the first `;;; ◀`/`;;; ▶` message line through the readline's
     status line — is dropped; the masthead teaching and the trailing
     `ns=>` cursor stay,
   - the intent-DERIVED sections (`plan`, `plan-ledger` — the plan tree
     the task spawned, which restates it) are dropped whole,
   - every other section (the `recent-verbs` menu, ns cards,
     orientation) rides verbatim, so the baseline sees the identical
     offer scaffolding minus the intent.

   A prompt with no transcript event log returns with only the derived
   sections stripped (nothing else to null out). Known residue: an
   agent-authored live-tile/findings body that quotes the task is NOT
   stripped — a limitation, not a mechanism."
  {:malli/schema [:=> [:catn [::prompt :string]] :string]}
  [prompt]
  (let [prompt (reduce strip-section prompt intent-derived-sections)
        i (.indexOf prompt transcript-begin)
        j (.lastIndexOf prompt transcript-end)]
    (if (or (neg? i) (neg? j) (<= j i))
      prompt
      (let [body-start (+ i (count transcript-begin))
            head   (subs prompt 0 body-start)
            body   (subs prompt body-start j)
            tail   (subs prompt j)
            lines  (str/split-lines body)
            msg-i  (first (keep-indexed
                            (fn [k l] (when (re-find #"^;;; [◀▶] " l) k))
                            lines))
            cursor (last (remove str/blank? lines))]
        (if (nil? msg-i)
          prompt
          (str head
               (str/join "\n" (concat (take msg-i lines) [cursor]))
               "\n" tail))))))

(defn step-projection
  "One step-output's small datom projection, agent-ref-free.

   The caller adds the `:seon.typeahead/agent` ref when an agent is in
   scope."
  {:malli/schema [:=> [:catn [::call-id ::call-id]
                       [::step-idx ::step-idx]
                       [::step-output ::step-output]]
                  :seon.typeahead/step]}
  [call-id idx output]
  (let [ro (:readouts output)]
    (cond->
      {:seon.typeahead/call         call-id
       :seon.typeahead/step-idx     idx
       :seon.typeahead/at           (js/Date.)
       :seon.typeahead/transition   (keyword (or (:transition output) "unknown"))
       :seon.typeahead/locked-count (count (:locked output))}
      (string? (:glyph output))
      (assoc :seon.typeahead/glyph (:glyph output))
      (number? (:glyph_margin ro))
      (assoc :seon.typeahead/margin (double (:glyph_margin ro)))
      (number? (:eos_logprob_tail ro))
      (assoc :seon.typeahead/eos-logprob (double (:eos_logprob_tail ro)))
      (number? (:forwards output))
      (assoc :seon.typeahead/forwards (int (:forwards output))))))

(defn assemble-reply
  "Locked forms + any unfinished tail draft as one LLM-reply-shaped text.

   Exactly what a text LLM's reply would carry: the eval-ready forms,
   blank-line separated, the honest unproven tail (when the loop ended
   before `done`) last — the turn pipeline parses + evals it all, and a
   broken tail surfaces as a normal eval error."
  {:malli/schema [:=> [:catn [::locked-forms ::locked-forms]
                       [::final-draft ::final-draft]]
                  :string]}
  [locked-forms final-draft]
  (->> (conj locked-forms (str/trim final-draft))
       (remove str/blank?)
       (str/join "\n\n")))

;; ============================================================
;; Step-row persistence + the self-installing tile block.
;; ============================================================

(defn- ^:async record-step!
  "Transact one step projection row; never throws, never blocks the loop.

   The agent ref rides from the ambient `db/with-agent` turn scope when
   present (outside a scope the row still records, unattributed)."
  [proj]
  (let [row (cond-> proj
              (db/current-agent-id)
              (assoc :seon.typeahead/agent
                     [:seon.agent/id (db/current-agent-id)]))
        res (await (db/transact! {:seon.db/tx-data [row]}))]
    (when (false? (:seon.db/ok? res))
      (js/console.warn "[seon.ai.typeahead] step projection failed:"
                       (pr-str (:seon.db/error res))))
    nil))

(defn- tile-installed?
  "Whether agent `id`'s ctx already carries the `:typeahead-steps` block
   in db value `db`."
  [db id]
  (boolean
    (and db id
         (contains? (db/installed-schema db) :seon.agent.ctx/name)
         (seq (db/query {:seon.db/db    db
                         :seon.db/query '[:find ?b
                                          :in $ ?id
                                          :where
                                          [?a :seon.agent/id ?id]
                                          [?a :seon.agent/ctx ?b]
                                          [?b :seon.agent.ctx/name :typeahead-steps]]
                         :seon.db/args  [id]})))))

(def tile-block
  "The `:typeahead-steps` ctx block — html-only (a tile, no prompt text)."
  {:seon.agent.ctx/name     :typeahead-steps
   :seon.agent.ctx/priority 95
   :seon.render/html        'seon.ai.typeahead/steps-tile-html})

(defn- ^:async ensure-tile!
  "Install the step-trace tile block once for the agent in scope.

   Idempotent by presence check (a re-install would churn the agent's
   whole ctx block set); a no-scope call or a failed install is a quiet
   no-op — the tile is observability, never load-bearing."
  []
  (when-let [id (db/current-agent-id)]
    (when-not (tile-installed? (some-> db/*conn* deref) id)
      (let [res (await (ctx/install! tile-block))]
        (when (false? (:seon.agent.ctx/ok? res))
          (js/console.warn "[seon.ai.typeahead] tile install failed:"
                           (:seon.agent.ctx/error res))))))
  nil)

;; ============================================================
;; The step loop — the provider body.
;; ============================================================

(defn- loop-raw
  "The `:seon.ai/raw` payload for a finished loop: the reply text plus
   the compact step trace (small projections, not full worker outputs)."
  [reply call-id outcome steps]
  {:seon.ai/text reply
   ::call-id     call-id
   ::outcome     outcome
   ::steps       steps})

(defn ^:async ^:private step-loop!
  "Run the mode=step FSM loop for one rendered prompt; return the
   turn-loop reply shape `{:text … :seon.ai/raw …}` (plus
   `:seon.ai/error` when a step failed)."
  [opts prompt]
  (let [db         (some-> db/*conn* deref)
        agent-id   (db/current-agent-id)
        policy     (menu/policy db)
        offers     (if (and db agent-id) (menu/verb-offers db agent-id) [])
        ;; offers ride with the null-intent calibration render — the worker
        ;; gates the glyph baseline on BOTH (cursor.py: `offers and
        ;; null_render`), so auto-offers can actually fire (P5; P4 measured
        ;; uptake 0.0 with this unwired).
        wire       (cond-> {::dg/mode   :step
                            ::dg/prompt prompt
                            ::dg/policy (policy->wire policy)}
                     (seq offers) (assoc ::dg/offers (offers->wire offers)
                                         ::dg/null-render (null-render prompt)))
        max-rounds (max 1 (:seon.typeahead/max-rounds policy))
        call-id    (str (random-uuid))]
    (loop [round 0, committed "", draft "", locked-all [], stuck 0, steps []]
      (if (>= round max-rounds)
        (let [reply (assemble-reply locked-all draft)]
          (await (ensure-tile!))
          {:text reply :seon.ai/raw (loop-raw reply call-id :round-cap steps)})
        (let [resp (await (dg/complete (merge wire opts
                                              {::dg/committed committed
                                               ::dg/draft     draft})))]
          (if-let [err (:seon.ai/error resp)]
            ;; One failing step fails the provider call as a value; the
            ;; turn loop's retry classification applies to the whole call.
            {:text "" :seon.ai/raw resp :seon.ai/error err}
            (let [out         (::dg/worker-output resp)
                  proj        (step-projection call-id round out)
                  _           (await (record-step! proj))
                  steps'      (conj steps proj)
                  locked      (mapv str (:locked out))
                  locked-all' (into locked-all locked)
                  committed'  (->> (cons committed locked)
                                   (remove str/blank?)
                                   (str/join "\n"))
                  draft'      (str (:new_draft out))
                  transition  (:transition out)
                  stuck'      (if (= "stuck" transition) (inc stuck) 0)]
              (cond
                (= "done" transition)
                (let [reply (assemble-reply locked-all' "")]
                  (await (ensure-tile!))
                  {:text reply :seon.ai/raw (loop-raw reply call-id :done steps')})

                ;; Two stuck rounds in a row = the model is not moving;
                ;; stop with whatever locked instead of burning forwards.
                (>= stuck' 2)
                (let [reply (assemble-reply locked-all' draft')]
                  (await (ensure-tile!))
                  {:text reply :seon.ai/raw (loop-raw reply call-id :gave-up steps')})

                :else
                (recur (inc round) committed' draft' locked-all' stuck' steps')))))))))

(defn agent-adapter
  "A fn-of-ctx-string suitable for `seon.agent`'s `llm-fn`.

   The returned fn runs the step loop against the configured worker
   (`SEON_DG_ENDPOINT`) and returns a Promise of `{:text …
   :seon.ai/raw …}` — plus a top-level `:seon.ai/error` when a step
   failed. Optional `opts` merge into every step request (e.g.
   `{:seon.ai.diffusiongemma/seed 7}`). This adapter buffers (the loop
   IS its structure), so it uses the ctx and ignores `:seon.ai/stream?`."
  {:malli/schema
   [:function
    [:=> [:cat] :any]
    [:=> [:catn [::opts ::opts]] :any]]}
  ([] (agent-adapter {}))
  ([opts] (fn [arg] (step-loop! opts (ai/llm-arg->ctx arg)))))

;; ============================================================
;; The agent-page tile — the last call's step trace, derived at render
;; (reactive: no step rows → nil, the tile body vanishes).
;; ============================================================

(def ^:private transition-display
  "Transition keyword → dot glyph + Phosphor text class."
  {:done     {:dot "●" :class "text-signal"}
   :expand   {:dot "●" :class "text-signal"}
   :progress {:dot "●" :class "text-text-200"}
   :grow     {:dot "●" :class "text-text-400"}
   :repair   {:dot "⚠" :class "text-warning"}
   :stuck    {:dot "✗" :class "text-error"}})

(defn- last-call-steps
  "The agent's most recent call's step rows, step-idx-ordered. nil when
   the attr is uninstalled or the agent has no steps."
  [db id]
  (when (and db id (contains? (db/installed-schema db) :seon.typeahead/call))
    (let [rows (->> (db/query {:seon.db/db    db
                               :seon.db/query '[:find [?e ...]
                                                :in $ ?aid
                                                :where
                                                [?a :seon.agent/id ?aid]
                                                [?e :seon.typeahead/agent ?a]]
                               :seon.db/args  [id]})
                    (map #(db/pull db '[*] %))
                    (filter :seon.typeahead/at))]
      (when (seq rows)
        (let [call (->> rows
                        (sort-by #(.getTime ^js (:seon.typeahead/at %)))
                        last
                        :seon.typeahead/call)]
          (->> rows
               (filter #(= call (:seon.typeahead/call %)))
               (sort-by :seon.typeahead/step-idx)
               vec))))))

(defn- eos-meter
  "The done-ness meter — `eos-logprob` (measured −7 more-work … −2.8
   done) as a small horizontal bar."
  [lp]
  (let [pct (-> (* 100 (/ (+ lp 8) 8)) (max 2) (min 100) js/Math.round)]
    [:div {:class "flex items-center gap-1"}
     [:div {:class "w-16 h-1.5 bg-base-800 rounded overflow-hidden"}
      [:div {:class "h-full bg-signal"
             :style (str "width:" pct "%")}]]
     [:span {:class "text-text-500 text-2xs font-mono"} (str lp)]]))

(defn- step-row
  "One rendered step line: idx, ● transition, glyph, margin, locks, EOS."
  [{:seon.typeahead/keys [step-idx transition glyph margin eos-logprob
                          locked-count forwards]}]
  (let [{:keys [dot class]} (get transition-display transition
                                 {:dot "●" :class "text-text-400"})]
    [:div {:class "flex items-center gap-3 text-xs font-mono py-0.5"}
     [:span {:class "text-text-500 w-6 text-right"} (str step-idx)]
     [:span {:class (str class " w-24")} (str dot " " (name transition))]
     [:span {:class "text-signal w-8"} (or glyph "·")]
     [:span {:class "text-text-400 w-20"}
      (if margin (str "Δ" margin) "Δ —")]
     [:span {:class "text-text-400 w-16"}
      (str "⊢ " locked-count (when forwards (str " ·" forwards "f")))]
     (when eos-logprob (eos-meter eos-logprob))]))

(defn steps-tile-html
  "The typeahead step-trace tile — the last provider call's FSM steps.

   Derived at render from the agent's `:seon.typeahead/*` step rows
   (latest call only): per step the transition (dot+text), any emitted
   glyph, the calibrated margin, locked-form count + decoder forwards,
   and the EOS done-ness meter. nil when the agent has no step rows —
   the tile body vanishes (reactive-context)."
  {:malli/schema [:=> [:cat :seon.render/section-request]
                  [:maybe :seon.render.live-tile/hiccup]]}
  [{db :seon.db/db id :seon.agent/id}]
  (let [db    (or db (some-> db/*conn* deref))
        steps (last-call-steps db id)]
    (when (seq steps)
      [:div {:class "flex flex-col gap-1"}
       [:div {:class "flex items-center justify-between"}
        [:span {:class "text-text-500 text-2xs uppercase tracking-wider"}
         "typeahead steps"]
        [:span {:class "text-text-500 text-2xs font-mono"}
         (str (count steps) " step" (when (not= 1 (count steps)) "s"))]]
       (into [:div {:class "flex flex-col"}]
             (map step-row steps))])))
