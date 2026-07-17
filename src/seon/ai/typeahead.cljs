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
   rendered menu shows (the one structured `seon.agent.ctx.menu` acquisition),
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
   margin, EOS logprob, forwards, wall, entropy, draft preview — datoms
   are projections; full posteriors/events are NOT persisted). The
   `:typeahead-steps` ctx block (`seon.agent.ctx.typeahead-steps` — the
   render twin; this ns stays hiccup-free) derives the last call's
   trace from these rows. The block is NEVER installed by this loop —
   enabling it is an explicit per-agent `ctx/install!` (owner
   constraint; the enable story lives in typeahead-design.md)."
  (:require
    [cljs.reader :as reader]
    [clojure.string :as str]
    [malli.core :as m]
    [seon.agent.ctx.menu :as menu]
    [seon.ai :as ai]
    [seon.ai.diffusiongemma :as dg]
    [seon.ai.tokens :as tokens]
    [seon.db :as db]
    [seon.instrument :as instrument]
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
(schema/register! :seon.typeahead/gen-s :double)          ; wall seconds this step (wire gen_s)
(schema/register! :seon.typeahead/entropy-worst :double)  ; worst free-region token entropy (nats)
(schema/register! :seon.typeahead/draft-preview :string)  ; truncated post-step draft text
(schema/register! :seon.typeahead/draft-tokens :int)      ; FULL draft size, tokens/estimate
(schema/register! :seon.typeahead/prompt-tokens :int)     ; the call's render size, tokens/estimate
(schema/register! :seon.typeahead/worker-sha :string)     ; the diffusion-server build that answered (wire field `worker_sha`, name kept for continuity)
(schema/register! :seon.typeahead/agent :seon.db/ref)
;; The code-buffer picture (additive, P6+ surface upgrade). Bounded
;; projections of the wire's buffer_text/buffer_spans/offer_status/
;; expansion — spans/offers/holes ride as EDN strings (the
;; :seon.eval/result-edn pattern), capped at write time; full
;; round-by-round traces stay on :seon.ai/raw, never datoms.
(schema/register! :seon.typeahead/buffer-preview :string)  ; capped buffer_text
(schema/register! :seon.typeahead/buffer-spans :string)    ; EDN [{:start :end :status}], ≤64, clipped to the preview
(schema/register! :seon.typeahead/offers-edn :string)      ; EDN per-offer status (fired/suppressed/below-margin)
(schema/register! :seon.typeahead/holes-edn :string)       ; EDN per-hole {:worst :mean :accepted :round :chosen-length :snapped}
(schema/register! :seon.typeahead/rounds-used :int)        ; expansion settle rounds burned
(schema/register! :seon.typeahead/round-budget :int)       ; expansion settle round budget
(schema/register! :seon.typeahead/committed-tokens :int)   ; committed text after this step, tokens/estimate
;; The per-step PLAN PASS (planner-worker-design W2) — pass rows are step
;; projections marked plan-pass?, step-idx -1 (they precede round 0);
;; prefill-tokens is the prefilled document's size (tokens/estimate).
(schema/register! :seon.typeahead/plan-pass? :boolean)
(schema/register! :seon.typeahead/prefill-tokens :int)
;; A budget-skipped pass records WHY (W3 scope-down follow-up: the
;; demotion measurement needs skips visible, not silent).
(schema/register! :seon.typeahead/pass-skip :string)

(schema/register! :seon.typeahead/step
  [:map {:seon.db/entity true}
   [:seon.typeahead/call          :seon.typeahead/call]
   [:seon.typeahead/step-idx      :seon.typeahead/step-idx]
   [:seon.typeahead/at            :seon.typeahead/at]
   [:seon.typeahead/transition    :seon.typeahead/transition]
   [:seon.typeahead/locked-count  :seon.typeahead/locked-count]
   [:seon.typeahead/glyph         {:optional true} :seon.typeahead/glyph]
   [:seon.typeahead/margin        {:optional true} :seon.typeahead/margin]
   [:seon.typeahead/eos-logprob   {:optional true} :seon.typeahead/eos-logprob]
   [:seon.typeahead/forwards      {:optional true} :seon.typeahead/forwards]
   [:seon.typeahead/gen-s         {:optional true} :seon.typeahead/gen-s]
   [:seon.typeahead/entropy-worst {:optional true} :seon.typeahead/entropy-worst]
   [:seon.typeahead/draft-preview {:optional true} :seon.typeahead/draft-preview]
   [:seon.typeahead/draft-tokens  {:optional true} :seon.typeahead/draft-tokens]
   [:seon.typeahead/prompt-tokens {:optional true} :seon.typeahead/prompt-tokens]
   [:seon.typeahead/worker-sha    {:optional true} :seon.typeahead/worker-sha]
   [:seon.typeahead/buffer-preview {:optional true} :seon.typeahead/buffer-preview]
   [:seon.typeahead/buffer-spans  {:optional true} :seon.typeahead/buffer-spans]
   [:seon.typeahead/offers-edn    {:optional true} :seon.typeahead/offers-edn]
   [:seon.typeahead/holes-edn     {:optional true} :seon.typeahead/holes-edn]
   [:seon.typeahead/rounds-used   {:optional true} :seon.typeahead/rounds-used]
   [:seon.typeahead/round-budget  {:optional true} :seon.typeahead/round-budget]
   [:seon.typeahead/committed-tokens {:optional true} :seon.typeahead/committed-tokens]
   [:seon.typeahead/plan-pass?    {:optional true} :seon.typeahead/plan-pass?]
   [:seon.typeahead/prefill-tokens {:optional true} :seon.typeahead/prefill-tokens]
   [:seon.typeahead/pass-skip     {:optional true} :seon.typeahead/pass-skip]
   [:seon.typeahead/auto-offer-margin {:optional true} :seon.typeahead/auto-offer-margin]
   [:seon.typeahead/max-rounds    {:optional true} :seon.typeahead/max-rounds]
   [:seon.typeahead/agent         {:optional true} :seon.db/ref]])

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
;; intent spawned. It restates the task, so a baseline keeping it would
;; already carry the intent and calibration would cancel the very signal
;; it protects (verified on a captured acme prompt blob). (`plan-ledger`
;; was here too until the block retired 2026-07-11 — `:plan` is THE plan
;; surface now.)
(def ^:private intent-derived-sections ["plan"])

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
   - the intent-DERIVED section (`plan` — the plan tree the task
     spawned, which restates it) is dropped whole,
   - every other section (the `function-menu` menu, ns cards,
     orientation) rides verbatim, so the baseline sees the identical
     offer scaffolding minus the intent.

   A prompt with no transcript event log returns with only the derived
   sections stripped (nothing else to null out). Known residue: an
   agent-authored canvas/findings body that quotes the task is NOT
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

(def ^:private draft-preview-chars
  "Cap on the persisted draft PREVIEW string (a projection; the size is
   reported in tokens — `:seon.typeahead/draft-tokens` covers the full
   draft)."
  160)

(def ^:private buffer-preview-chars
  "Cap on the persisted code-buffer preview (the surface's centerpiece pane;
   displayed sizes are tokens). The wire may carry up to 4000 chars —
   the datom keeps a bounded projection; the full text rides
   `:seon.ai/raw` for the turn that made it."
  600)

(defn- clip-spans
  "Wire `buffer_spans` → compact EDN-ready `[start end status-kw]` tuples,
   clipped to the persisted preview `cap`, ≤64 entries. Zero-width spans
   (the frontier cursor) survive the clip."
  [spans cap]
  (into []
        (comp (map (fn [{:keys [start end status]}]
                     [(min (max 0 start) cap) (min (max 0 end) cap)
                      (keyword status)]))
              (filter (fn [[a b st]] (or (< a b) (= st :frontier))))
              (take 64))
        spans))

(defn- add-buffer
  "Assoc the bounded code-buffer projection when the step output carries
   the buffer picture."
  [m output]
  (let [bt (:buffer_text output)]
    (if (and (string? bt) (not (str/blank? bt)))
      (let [cap (min (count bt) buffer-preview-chars)]
        (assoc m
               :seon.typeahead/buffer-preview (subs bt 0 cap)
               :seon.typeahead/buffer-spans
               (pr-str (clip-spans (:buffer_spans output) cap))))
      m)))

(defn- add-offers
  "Assoc the per-offer status projection (fired / suppressed /
   below-margin, calibrated lift) + the auto-offer threshold."
  [m output ro]
  (cond-> m
    (seq (:offer_status output))
    (assoc :seon.typeahead/offers-edn
           (pr-str
             (into []
                   (comp (take 10)
                         (map (fn [{:keys [glyph label cal raw state reason]}]
                                (cond-> {:seon.typeahead/glyph (str glyph)
                                         :seon.typeahead/state (keyword state)}
                                  (string? label) (assoc :seon.typeahead/label label)
                                  (number? cal)   (assoc :seon.typeahead/cal cal)
                                  (number? raw)   (assoc :seon.typeahead/raw raw)
                                  reason (assoc :seon.typeahead/reason
                                                (keyword reason))))))
                   (:offer_status output))))
    (number? (:auto_offer_margin ro))
    (assoc :seon.typeahead/auto-offer-margin
           (double (:auto_offer_margin ro)))))

(defn- add-holes
  "Assoc the expansion's per-hole projection (entropy, CAL-chosen length,
   accepted/snap) + settle-round usage when this step EXPANDed."
  [m output]
  (let [fr     (:expansion output)
        holes  (:hole_confidence fr)
        chosen (into {} (map (juxt :hole :chosen)) (:probes fr))]
    (cond-> m
      (seq holes)
      (assoc :seon.typeahead/holes-edn
             (pr-str
               (into []
                     (comp (take 16)
                           (map-indexed
                             (fn [i {:keys [mean worst accepted round snapped]}]
                               (cond-> {:seon.typeahead/accepted (boolean accepted)}
                                 (number? worst) (assoc :seon.typeahead/worst worst)
                                 (number? mean)  (assoc :seon.typeahead/mean mean)
                                 (number? round) (assoc :seon.typeahead/round round)
                                 (some? (chosen i))
                                 (assoc :seon.typeahead/chosen-length (chosen i))
                                 snapped (assoc :seon.typeahead/snapped true)))))
                     holes)))
      (number? (:settle_rounds_used fr))
      (assoc :seon.typeahead/rounds-used (int (:settle_rounds_used fr)))
      (number? (:settle_round_budget fr))
      (assoc :seon.typeahead/round-budget (int (:settle_round_budget fr))))))

(defn step-projection
  "One step-output's small datom projection, agent-ref-free.

   The caller adds the `:seon.typeahead/agent` ref (and the call-level
   `:seon.typeahead/prompt-tokens`) when in scope."
  {:malli/schema [:=> [:catn [::call-id ::call-id]
                       [::step-idx ::step-idx]
                       [::step-output ::step-output]]
                  :seon.typeahead/step]}
  [call-id idx output]
  (let [ro    (:readouts output)
        draft (str (:new_draft output))]
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
      (assoc :seon.typeahead/forwards (int (:forwards output)))
      (number? (:gen_s output))
      (assoc :seon.typeahead/gen-s (double (:gen_s output)))
      (number? (:free_entropy_worst ro))
      (assoc :seon.typeahead/entropy-worst (double (:free_entropy_worst ro)))
      (string? (:worker_sha output))
      (assoc :seon.typeahead/worker-sha (:worker_sha output))
      (not (str/blank? draft))
      (assoc :seon.typeahead/draft-preview
             (if (> (count draft) draft-preview-chars)
               (str (subs draft 0 draft-preview-chars) "…")
               draft)
             :seon.typeahead/draft-tokens (tokens/estimate draft))
      :always (add-buffer output)
      :always (add-offers output ro)
      :always (add-holes output))))

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

(schema/register! ::withheld :seon.agent.ctx.menu/offers-view)
(schema/register! ::proj :seon.typeahead/step)

(defn with-withheld-offers
  "The step projection with loop-side suppressions appended to its
   offers EDN.

   The worker is stateless: offers whose expansion previously locked
   nothing are WITHHELD from the wire by the loop (`failed`), so the
   worker's `offer_status` cannot know them. This appends each as
   `{… :seon.typeahead/state :suppressed :seon.typeahead/reason
   :failed-before}` so the surface shows the whole offer picture."
  {:malli/schema [:=> [:catn [::proj ::proj] [::withheld ::withheld]]
                  ::proj]}
  [proj withheld]
  (if (empty? withheld)
    proj
    (let [cur  (try (some-> (:seon.typeahead/offers-edn proj)
                            reader/read-string)
                    (catch :default _ nil))
          rows (into (vec cur)
                     (map (fn [{:seon.typeahead/keys [glyph label]}]
                            {:seon.typeahead/glyph  glyph
                             :seon.typeahead/label  label
                             :seon.typeahead/state  :suppressed
                             :seon.typeahead/reason :failed-before}))
                     withheld)]
      (assoc proj :seon.typeahead/offers-edn (pr-str rows)))))

;; ============================================================
;; The draft-head prefill affordance + the per-step PLAN PASS
;; (planner-worker-design W2). One computed rule, no fn list: a
;; registered request schema whose argument entry carries
;; `:seon.render/prefill-fn` names the projection of that argument's
;; CURRENT value. This loop derives the affordance from the registry +
;; the program graph, renders the projection as EDIT-WITH-PREFILL wire
;; segments (structure + key names + identity-attr entries CLAMPED —
;; ids and shape unforgeable by construction; entries whose current
;; datom ANOTHER agent authored CLAMPED — the separation-of-authority
;; zones, derived from `db/with-agent` tx provenance; only the
;; caller's OWN scalar values are editable holes), and ships them as
;; the step wire's `prefills` map. The worker expands an opened call to a listed head;
;; the PLAN PASS is the same affordance invoked at step-open by seeding
;; the head as the draft. `my.plan/reconcile!` is instance #1; any
;; future document-shaped fn gets this by declaring the property.
;; ============================================================

(schema/register! ::head :string)
(schema/register! ::req-schema :keyword)
(schema/register! ::arg-key :keyword)
(schema/register! ::prefill-fn :symbol)
(schema/register! ::prefill-spec :string)
(schema/register! ::affordance
  [:map
   [::head       {:optional true} ::head]
   [::req-schema ::req-schema]
   [::arg-key    ::arg-key]
   [::prefill-fn ::prefill-fn]
   [::prefill-spec {:optional true} ::prefill-spec]])
(schema/register! ::affordances [:vector ::affordance])
(schema/register! ::doc [:or :map [:vector :map]])

(defn- schema-props
  "The property map of a registered schema definition, or nil."
  [definition]
  (when (and (vector? definition) (map? (second definition)))
    (second definition)))

(defn- identity-attr?
  "Whether attr keyword `k`'s registered schema carries
   `:seon.db/identity` — the computed clamp rule (never a name list)."
  [k]
  (boolean (:seon.db/identity (schema-props (schema/schema-definition k)))))

(defn- prefill-entries
  "Every registered `:map` request schema entry declaring
   `:seon.render/prefill-fn` — the registry half of the affordance."
  []
  (into []
        (mapcat (fn [[k definition]]
                  (when (and (vector? definition) (= :map (first definition)))
                    (keep (fn [e]
                            (when (and (vector? e) (map? (second e))
                                       (:seon.render/prefill-fn (second e)))
                              {::req-schema k
                               ::arg-key    (first e)
                               ::prefill-fn (:seon.render/prefill-fn (second e))}))
                          (rest definition)))))
        (schema/registered-schemas)))

(defn- affordance-head
  "The program-graph fn whose specced request IS `req-schema`
   (`[:=> [:cat <req-schema>] …]`) — its full sym string, or nil."
  [rows req-schema]
  (let [needle (str req-schema)]
    (some (fn [[sym spec]]
            (when (str/includes? (str spec) needle)
              (let [form (try (reader/read-string spec)
                              (catch :default _ nil))]
                (when (and (vector? form) (= :=> (first form))
                           (= [:cat req-schema] (second form)))
                  sym))))
          rows)))

(defn ^:async ^:private prefill-affordances
  "The live prefill affordances in database value `db`.

   Registry entries are joined to their program-graph head fn (entries with no resolvable
   head are dropped: no head, no draft to match)."
  [db]
  (let [rows (await
              (db/query {:seon.db/db db
                         :seon.db/query '[:find ?sym ?spec
                                          :where
                                          [?e :seon.fn/sym ?sym]
                                          [?e :seon.fn/spec ?spec]]
                         :seon.db/max-results 16384
                         :seon.db/max-result-weight 1048576}))]
    (if (:seon.error/message rows)
      rows
      (let [specs (into {} rows)]
        (into []
              (keep (fn [{::keys [req-schema prefill-fn] :as entry}]
                      (when-let [head (affordance-head rows req-schema)]
                        (cond-> (assoc entry ::head head)
                          (get specs (str prefill-fn))
                          (assoc ::prefill-spec
                                 (get specs (str prefill-fn)))))))
              (prefill-entries))))))

(defn- resolve-sym
  "The live fn for a full `ns/name` symbol via the ONE symbol→fn
   mechanism (`seon.instrument/find-js-var`), or nil."
  [sym]
  (let [s (str sym)]
    (when-let [i (str/index-of s "/")]
      (instrument/find-js-var (symbol (subs s 0 i))
                              (symbol (subs s (inc i)))))))

(defn- emit-seg
  "Append text `s` of `kind` (\"clamp\"/\"prefill\") to wire segments
   `segs`, coalescing consecutive same-kind chunks."
  [segs kind s]
  (let [lst (peek segs)]
    (if (and lst (= kind (first lst)))
      (conj (pop segs) [kind (str (second lst) s)])
      (conj segs [kind s]))))

(defn- document-identities [doc]
  (into []
        (comp (filter map?)
              (mapcat (fn [node]
                        (keep (fn [[attr value]]
                                (when (identity-attr? attr) [attr value]))
                              node)))
              distinct)
        (tree-seq coll? seq doc)))

(defn- ^:async document-authors [db documents]
  (let [identities (into [] (comp (mapcat document-identities) distinct)
                         documents)]
    (if (empty? identities)
      {}
      (let [rows (await
                  (db/query
                   {:seon.db/db db
                    :seon.db/query
                    '[:find ?identity-attr ?identity-value ?attr ?agent-id
                      :in $ [[?identity-attr ?identity-value]]
                      :where
                      [?entity ?identity-attr ?identity-value]
                      [?entity ?attr _ ?tx]
                      [?tx :seon.db/user ?author]
                      [?author :seon.agent/id ?agent-id]]
                    :seon.db/args [identities]
                    :seon.db/max-results 65536
                    :seon.db/max-result-weight 1048576}))]
        (if (:seon.error/message rows)
          rows
          (reduce (fn [authors [identity-attr identity-value attr agent-id]]
                    (assoc-in authors [[identity-attr identity-value] attr]
                              agent-id))
                  {} rows))))))

(defn- render-doc-value
  "Render EDN `v` into edit-with-prefill segments (functional over
   `segs`). STRUCTURE AND VOCABULARY CLAMP — braces/brackets, key
   names, identity entries, and any scalar entry whose current datom
   ANOTHER agent authored (or that carries no provenance — unverifiable
   is unforgeable). ONLY the caller's OWN scalar VALUES ride as
   editable prefill holes (each with the worker's slack to grow into).
   Live-measured root cause: with node boundaries editable, the model
   merged nodes and rewrote key names — one denoise round, unbalanced
   EDN; clamped structure makes a parse-clean edit the construction,
   not a hope. Structural edits (split/drop/reorder) stay with the
   DELTA functions in the WORK loop — the design's other update shape."
  [authors agent-id v segs]
  (cond
    (map? v)
    (let [id-entry (some (fn [[k val]] (when (identity-attr? k) [k val])) v)
          node-authors (get authors id-entry {})
          own?     (fn [k] (= agent-id (get node-authors k)))
          coll?*   (fn [val] (and (sequential? val) (seq val) (every? map? val)))
          scalars  (->> (dissoc v (first id-entry))
                        (remove (fn [[_ val]] (coll?* val)))
                        (sort-by (fn [[k _]] (name k))))
          colls    (->> v
                        (filter (fn [[_ val]] (coll?* val)))
                        (sort-by (fn [[k _]]
                                   [(if (str/starts-with? (name k) "_") 1 0)
                                    (name k)])))]
      (as-> segs segs
        (emit-seg segs "clamp" "{")
        (if id-entry
          (emit-seg segs "clamp" (str (pr-str (first id-entry)) " "
                                      (pr-str (second id-entry)) " "))
          segs)
        (reduce (fn [segs [k val]]
                  (if (own? k)
                    (-> segs
                        (emit-seg "clamp" (str (pr-str k) " "))
                        (emit-seg "prefill" (pr-str val))
                        (emit-seg "clamp" " "))
                    (emit-seg segs "clamp"
                              (str (pr-str k) " " (pr-str val) " "))))
                segs scalars)
        (reduce (fn [segs [k val]]
                  (-> (reduce (fn [segs c]
                                (-> (render-doc-value authors agent-id c segs)
                                    (emit-seg "clamp" "\n")))
                              (emit-seg segs "clamp" (str (pr-str k) " ["))
                              val)
                      (emit-seg "clamp" "] ")))
                segs colls)
        (emit-seg segs "clamp" "}")))

    (sequential? v)
    (-> (reduce (fn [segs c]
                  (-> (render-doc-value authors agent-id c segs)
                      (emit-seg "clamp" "\n")))
                (emit-seg segs "clamp" "[")
                v)
        (emit-seg "clamp" "]"))

    :else (emit-seg segs "prefill" (pr-str v))))

(def ^:private plan-pass-doc-token-budget
  "Estimated-token cap on a prefilled document. The worker's code buffer
   is 256 tokens; the head/closing clamps + per-hole slack need margin.
   A document over this budget SCOPES DOWN to the ▶ active step's
   subtree + the root layer (titles only for non-active roots); if even
   the scoped document exceeds the budget the pass SKIPS with a recorded
   `:seon.typeahead/pass-skip` reason — never silently."
  190)

;; --- Scope-down (planner-worker-design W3 prerequisite). The scoped
;; --- document is an EDITOR VIEW, never the reconcile argument: on lock
;; --- the edited scalar values merge back by node id into the FULL
;; --- document, and the emitted form carries the whole open forest —
;; --- reconcile! treats absence as drop (+ absent scalars as retract),
;; --- so a narrowed view must never reach it directly. For the same
;; --- reason a scoped template is PASS-ONLY: it never rides the organic
;; --- wire (an organic lock would eval the narrowed view unmerged).

(defn- doc-child-entry?
  "Whether a map entry's value is a non-empty seq of maps — the same
   children rule [[render-doc-value]] renders with."
  [[_ v]]
  (and (sequential? v) (seq v) (every? map? v)))

(defn- doc-node-id
  "The value of a document node's identity-attr entry, or nil."
  [node]
  (some (fn [[k v]] (when (identity-attr? k) v)) node))

(defn- doc-children
  "Every child node of a document node (all children entries, flattened)."
  [node]
  (into [] (mapcat val) (filter doc-child-entry? node)))

(defn- active-doc-node?
  "Whether a document node's status-named entry says `:active` — the ▶."
  [node]
  (boolean (some (fn [[k v]]
                   (and (keyword? k) (= "status" (name k)) (= :active v)))
                 node)))

(defn- find-active
  "DFS: the first `:active` node in document `nodes` (its whole subtree)."
  [nodes]
  (some (fn [n]
          (or (when (active-doc-node? n) n)
              (find-active (doc-children n))))
        nodes))

(defn- subtree-contains?
  "Whether `target` (by object identity — same walked tree) is in
   `node`'s subtree."
  [node target]
  (or (identical? node target)
      (boolean (some #(subtree-contains? % target) (doc-children node)))))

(defn- title-layer
  "A node reduced to identity + title/goal/status scalars — the scoped
   document's rendering of a non-active root."
  [node]
  (into {}
        (comp (remove doc-child-entry?)
              (filter (fn [[k _]]
                        (or (identity-attr? k)
                            (contains? #{"title" "goal" "status"} (name k))))))
        node))

(defn scoped-document
  "The pass document scoped to the ▶ subtree + the root layer.

   The ▶ active step's subtree rides in full (nested under its root when
   the active step isn't itself a root); every other root is titles-only
   (identity + title/goal/status). Public for tests."
  {:malli/schema [:=> [:catn [::doc ::doc]] ::doc]}
  [doc]
  (let [roots  (if (map? doc) [doc] (vec doc))
        active (find-active roots)]
    (into []
          (map (fn [root]
                 (if (and active (identical? root active))
                   root
                   (let [ck (when active
                              (some (fn [[k v :as e]]
                                      (when (and (doc-child-entry? e)
                                                 (some #(subtree-contains? % active) v))
                                        k))
                                    root))]
                     (cond-> (title-layer root)
                       ck (assoc ck [active]))))))
          roots)))

(defn- scalar-edits
  "Edited (scoped) document → `{node-id → its scalar entries}` — the
   values the model may have changed (children entries stripped: the
   pass is SHARPEN-only, structure clamps)."
  [tree]
  (letfn [(walk [acc node]
            (let [acc (if-let [id (doc-node-id node)]
                        (assoc acc id (into {} (remove doc-child-entry?) node))
                        acc)]
              (reduce walk acc (doc-children node))))]
    (reduce walk {} (if (map? tree) [tree] tree))))

(defn- merge-edits
  "The FULL document with a scoped edit's scalar values merged in by
   node id — the scoped pass's write-back: reconcile! always receives
   the whole open forest, so the narrowed editor view can never read as
   absence (which would drop/retract out-of-scope steps)."
  [doc edits]
  (letfn [(walk [node]
            (let [node (if-let [e (some-> (doc-node-id node) edits)]
                         (merge node e)
                         node)]
              (reduce-kv (fn [n k v]
                           (if (doc-child-entry? [k v])
                             (assoc n k (mapv walk v))
                             n))
                         node node)))]
    (if (map? doc) (walk doc) (mapv walk doc))))

(defn- merge-scoped-form
  "A locked scoped-pass form rewritten against the FULL document.

   Parses the locked `(head {arg-key <edited-scoped-tree> …})` call,
   merges the edited scalar values into `doc` by node id, and re-emits
   the call with the merged full document. nil when the locked text
   doesn't parse to that shape — the pass is advisory, a malformed edit
   is dropped, never eval'd."
  [head arg-key doc form]
  (let [parsed (try (reader/read-string form) (catch :default _ nil))
        tree   (when (and (seq? parsed)
                          (= head (str (first parsed)))
                          (map? (second parsed)))
                 (get (second parsed) arg-key))]
    (when tree
      (str "(" head " {" (pr-str arg-key) " "
           (pr-str (merge-edits doc (scalar-edits tree))) "})"))))

(defn- affordance-template
  "The full prefilled call template: head + arg key clamped open, the
   document segments, the closing clamp."
  [head arg-key doc-segs]
  (-> (into [["clamp" (str "(" head " {" (pr-str arg-key) " ")]] doc-segs)
      (conj ["clamp" "})"])))

(defn- injected-request
  "The standard empty request for projection fn `sym`, its DECLARED
   injectable keys filled from the ambient scope — the same boundary
   rule `seon.instrument/injecting-fschema` applies at eval time (works
   whether or not the live var is wrapped; explicit keys win in the
   wrapper). {} when the fn's program-graph spec is unavailable."
  [spec]
  (let [inj  (try (some-> spec reader/read-string m/schema
                          instrument/declared-injectables)
                  (catch :default _ nil))]
    (reduce (fn [request k]
              (let [v ((instrument/injectables k) nil)]
                (if (some? v) (assoc request k v) request)))
            {} (or inj #{}))))

(defn- projection-doc?
  "Whether a projection result is a renderable document: node map(s)
   each carrying an identity-attr entry (an error ENVELOPE has none —
   the computed validity rule, no envelope-shape knowledge)."
  [doc]
  (let [nodes (cond (map? doc) [doc] (sequential? doc) doc :else nil)]
    (boolean (and (seq nodes)
                  (every? (fn [n] (and (map? n)
                                       (some identity-attr? (keys n))))
                          nodes)))))

(defn- ^:async pass-entries
  "head → pass entry, for every affordance whose projection fn resolves
   and returns a NON-EMPTY document. Within budget →
   `{::template … ::doc … ::arg-key … ::scoped? false}`; over budget the
   document scopes down ([[scoped-document]]) → same shape with
   `::scoped? true` and the FULL doc kept for the write-back merge;
   still over → `{::skip \"doc-over-budget (N tok)\"}`. {} when nothing
   qualifies."
  [db agent-id affordances]
  (let [candidates
        (loop [remaining (seq affordances) out []]
          (if-let [{::keys [head arg-key prefill-fn prefill-spec]} (first remaining)]
            (if-let [f (resolve-sym prefill-fn)]
              (let [doc (await
                         (db/with-tx-context
                           {::db/db db}
                           #(f (injected-request prefill-spec))))]
                (recur (next remaining)
                       (cond-> out
                         (projection-doc? doc)
                         (conj {::head head ::arg-key arg-key ::doc doc}))))
              (recur (next remaining) out))
            out))
        authors (await (document-authors db (mapv ::doc candidates)))]
    (if (:seon.error/message authors)
      authors
      (into {}
            (map (fn [{::keys [head arg-key doc]}]
                   (if (<= (tokens/estimate (pr-str doc))
                           plan-pass-doc-token-budget)
                     [head {::template (affordance-template
                                         head arg-key
                                         (render-doc-value authors agent-id doc []))
                            ::doc doc ::arg-key arg-key ::scoped? false}]
                     (let [scoped (scoped-document doc)
                           n (tokens/estimate (pr-str scoped))]
                       (if (<= n plan-pass-doc-token-budget)
                         [head {::template (affordance-template
                                             head arg-key
                                             (render-doc-value authors agent-id scoped []))
                                ::doc doc ::arg-key arg-key ::scoped? true}]
                         [head {::skip (str "doc-over-budget (" n " tok)")}])))))
            candidates))))

(defn- entries->organic-wire
  "head → template for the ORGANIC step wire — UNSCOPED templates only
   (a scoped template is pass-only: an organic lock would eval the
   narrowed view without the write-back merge)."
  [entries]
  (into {}
        (keep (fn [[h e]]
                (when (and (::template e) (not (::scoped? e)))
                  [h (::template e)])))
        entries))

(defn- entries->skips
  "head → skip reason, for the budget-skipped affordances."
  [entries]
  (into {}
        (keep (fn [[h e]] (when-let [r (::skip e)] [h r])))
        entries))

(def ^:private pass-render
  "The plan pass's MINIMAL render — never the full context render. The
   goal rides inside the prefilled document itself (the root node's
   `goal` entry)."
  (str "; PLAN PASS — refine your OPEN plan document before working the\n"
       "; ▶ step: split a too-big step, sharpen the active step's expect,\n"
       "; reorder, drop a dead branch — or leave it unchanged. Step ids\n"
       "; are fixed; edit titles/expects/structure only.\n"))

(defn- template-text
  "The template's full assembled text — what a NO-CHANGE fill decodes to
   (modulo whitespace)."
  [template]
  (apply str (map second template)))

(defn- no-change?
  "Whether a locked pass form equals the prefilled template text modulo
   whitespace/commas — the model left the document alone."
  [template form]
  (letfn [(norm [s] (str/trim (str/replace s #"[\s,]+" " ")))]
    (= (norm (template-text template)) (norm form))))

(defn- ^:async record-step!
  "Transact one step projection row; never throws, never blocks the loop.

   The agent ref rides from the ambient `db/with-agent` turn scope when
   present (outside a scope the row still records, unattributed)."
  [proj signal]
  (when-not (ai/aborted? signal)
    (let [row (cond-> proj
                (db/current-agent-id)
                (assoc :seon.typeahead/agent
                       [:seon.agent/id (db/current-agent-id)]))
          res (await (db/transact! {:seon.db/tx-data [row]}))]
      (when (:seon.error/message res)
        (js/console.warn "[seon.ai.typeahead] step projection failed:"
                         (pr-str res)))))
  nil)

(defn- ^:async run-plan-pass!
  "Run ONE plan pass (planner-worker-design W2): a small worker call —
   the minimal pass render, the head seeded as the draft, the prefilled
   template on the wire — whose locked result is the whole-document
   reconcile form. ADVISORY: any worker error skips the pass (nil), it
   never fails the provider call. Returns
   {::pass-form <form|nil> ::pass-proj <step row> ::pass-no-change? b}
   — the form is nil on a no-change pass (dropping it is cheaper than a
   0/0/0 receipt: zero eval, zero transcript tokens; the unchanged
   `:plan` block is the confirmation) and on a failed edit."
  [opts policy entries call-id]
  (let [head     (some (fn [[h e]] (when (::template e) h)) entries)
        {::keys [template doc arg-key scoped?]} (get entries head)
        wire     (merge {::dg/mode      :step
                         ::dg/prompt    pass-render
                         ::dg/policy    (policy->wire policy)
                         ::dg/prefills  {head template}
                         ::dg/committed ""
                         ::dg/draft     (str "(" head " ")}
                        opts)
        resp     (await (dg/complete wire))]
    (if (:seon.ai/error resp)
      (do (js/console.warn "[seon.ai.typeahead] plan pass skipped —"
                           (pr-str (:seon.ai/msg (:seon.ai/error resp))))
          nil)
      (let [out        (::dg/worker-output resp)
            form       (first (mapv str (:locked out)))
            unchanged? (boolean (and form (no-change? template form)))
            ;; A SCOPED pass's edit writes back through the merge — the
            ;; emitted form always carries the FULL document.
            form'      (when (and form (not unchanged?))
                         (if scoped?
                           (merge-scoped-form head arg-key doc form)
                           form))
            proj       (-> (step-projection call-id -1 out)
                           (assoc :seon.typeahead/plan-pass? true
                                  :seon.typeahead/prefill-tokens
                                  (tokens/estimate (template-text template))
                                  :seon.typeahead/prompt-tokens
                                  (tokens/estimate pass-render)))
            _          (await (record-step! proj (:seon.ai/abort-signal opts)))]
        {::pass-form       form'
         ::pass-proj       proj
         ::pass-no-change? unchanged?}))))

(defn- ^:async record-pass-skips!
  "Record one marked pass row per budget-skipped affordance — the skip
   is a step projection with `:seon.typeahead/pass-skip` carrying the
   reason, so the demotion measurement sees skips, never silence."
  [call-id skips signal]
  (loop [ss (seq skips)]
    (when ss
      (let [[_head reason] (first ss)]
        (await (record-step! {:seon.typeahead/call         call-id
                              :seon.typeahead/step-idx     -1
                              :seon.typeahead/at           (js/Date.)
                              :seon.typeahead/transition   :pass-skip
                              :seon.typeahead/locked-count 0
                              :seon.typeahead/plan-pass?   true
                              :seon.typeahead/pass-skip    reason}
                             signal))
        (recur (next ss))))))

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

(defn- abort-response
  []
  {:text ""
   :seon.ai/error {:seon.ai/msg      "Typeahead provider attempt aborted"
                   :seon.ai/timeout? true}})

(defn ^:async ^:private step-loop-at!
  "Run the mode=step FSM loop for one rendered prompt; return the
   turn-loop reply shape `{:text … :seon.ai/raw …}` (plus
   `:seon.ai/error` when a step failed)."
  [opts request database menu-value affordances]
  (let [prompt     (:seon.ai/ctx request)
        signal     (:seon.ai/abort-signal request)
        opts       (cond-> opts signal (assoc :seon.ai/abort-signal signal))
        agent-id   (db/current-agent-id)
        policy     (::menu/policy menu-value)
        offers     (::menu/offers menu-value)
        ;; The draft-head prefill affordance (W2): registry+program-graph
        ;; derived, computed ONCE per call (the projection can only change
        ;; after this call's forms eval). UNSCOPED templates ride EVERY
        ;; step's wire so an ORGANIC opened head expands prefilled too;
        ;; scoped templates are pass-only (write-back merge required).
        entries    (if (and database agent-id (seq affordances))
                     (await (pass-entries database agent-id affordances))
                     {})
        entries    (if (:seon.error/message entries) {} entries)
        prefills   (entries->organic-wire entries)
        skips      (entries->skips entries)
        passable?  (boolean (some ::template (vals entries)))
        pass-mode  (:seon.typeahead/plan-pass policy)
        ;; offers ride with the null-intent calibration render — the worker
        ;; gates the glyph baseline on BOTH (cursor.py: `offers and
        ;; null_render`), so auto-offers can actually fire (P5; P4 measured
        ;; uptake 0.0 with this unwired).
        wire       (cond-> {::dg/mode   :step
                            ::dg/prompt prompt
                            ::dg/policy (policy->wire policy)}
                     (seq offers)   (assoc ::dg/null-render (null-render prompt))
                     (seq prefills) (assoc ::dg/prefills prefills))
        max-rounds (max 1 (:seon.typeahead/max-rounds policy))
        call-id    (str (random-uuid))
        ptoks      (tokens/estimate prompt)
        ;; The step-open PLAN PASS (:every-step; :on-stuck runs it inside
        ;; the loop instead; :off never). A no-change/failed pass yields no
        ;; form and the loop proceeds straight to WORK. Budget-skipped
        ;; affordances record their reason (never a silent skip).
        pass       (when (and (= :every-step pass-mode) passable?)
                     (await (run-plan-pass! opts policy entries call-id)))
        _          (when (and (= :every-step pass-mode) (seq skips))
                     (await (record-pass-skips! call-id skips signal)))
        pass-form  (::pass-form pass)]
    ;; `failed` = glyphs whose EXPANSION locked nothing — suppressed for the
    ;; rest of the call (P6). The worker is stateless by design; this loop's
    ;; step trace is the driver's memory. Without it the P5 p1 trace shows
    ;; the identical failed auto-offer re-firing 4x at the same margin.
    (loop [round 0
           committed (or pass-form "")
           draft ""
           locked-all (if pass-form [pass-form] [])
           stuck 0
           steps (if pass [(::pass-proj pass)] [])
           failed #{}
           passed? (some? pass)]
      (cond
        (ai/aborted? signal)
        (abort-response)

        (>= round max-rounds)
        (let [reply (assemble-reply locked-all draft)]
          {:text reply :seon.ai/raw (loop-raw reply call-id :round-cap steps)})

        :else
        (let [live (into [] (remove #(contains? failed (:seon.typeahead/glyph %)))
                         offers)
              resp (await (dg/complete (merge (cond-> wire
                                                (seq live)
                                                (assoc ::dg/offers (offers->wire live)))
                                              opts
                                              {::dg/committed committed
                                               ::dg/draft     draft})))]
          (if-let [err (:seon.ai/error resp)]
            ;; One failing step fails the provider call as a value; the
            ;; turn loop's retry classification applies to the whole call.
            {:text "" :seon.ai/raw resp :seon.ai/error err}
            (let [out         (::dg/worker-output resp)
                  locked      (mapv str (:locked out))
                  locked-all' (into locked-all locked)
                  committed'  (->> (cons committed locked)
                                   (remove str/blank?)
                                   (str/join "\n"))
                  withheld    (filterv #(contains? failed
                                                   (:seon.typeahead/glyph %))
                                       offers)
                  proj        (-> (step-projection call-id round out)
                                  (assoc :seon.typeahead/prompt-tokens ptoks
                                         :seon.typeahead/max-rounds max-rounds
                                         :seon.typeahead/committed-tokens
                                         (tokens/estimate committed'))
                                  (with-withheld-offers withheld))
                  _           (await (record-step! proj signal))
                  steps'      (conj steps proj)
                  draft'      (str (:new_draft out))
                  transition  (:transition out)
                  stuck'      (if (= "stuck" transition) (inc stuck) 0)
                  failed'     (if (and (= "expand" transition)
                                       (empty? locked)
                                       (string? (:glyph out)))
                                (conj failed (:glyph out))
                                failed)]
              (cond
                (= "done" transition)
                (let [reply (assemble-reply locked-all' "")]
                  {:text reply :seon.ai/raw (loop-raw reply call-id :done steps')})

                ;; Two stuck rounds in a row = the model is not moving;
                ;; stop with whatever locked instead of burning forwards.
                (>= stuck' 2)
                (let [reply (assemble-reply locked-all' draft')]
                  {:text reply :seon.ai/raw (loop-raw reply call-id :gave-up steps')})

                :else
                ;; :on-stuck wiring — the demoted plan pass runs at the
                ;; FIRST observed stuck round, once per call (skips
                ;; record at the same moment).
                (let [moment? (and (= :on-stuck pass-mode)
                                   (not passed?)
                                   (= "stuck" transition))
                      pass'   (when (and moment? passable?)
                                (await (run-plan-pass! opts policy entries
                                                       call-id)))
                      _       (when (and moment? (seq skips))
                                (await (record-pass-skips! call-id skips signal)))
                      pform   (::pass-form pass')]
                  (recur (inc round)
                         (if pform
                           (->> [committed' pform]
                                (remove str/blank?)
                                (str/join "\n"))
                           committed')
                         draft'
                         (if pform (conj locked-all' pform) locked-all')
                         stuck'
                         (if pass' (conj steps' (::pass-proj pass')) steps')
                         failed'
                         (or passed? moment?)))))))))))

(defn- provider-database-error [error]
  {:text ""
   :seon.ai/error
   {:seon.ai/msg (str "Typeahead database acquisition failed: "
                      (:seon.error/message error))}})

(defn ^:async ^:private step-loop!
  [opts request]
  (let [database (await (db/db))]
    (if (:seon.error/message database)
      (provider-database-error database)
      (let [agent-id (db/current-agent-id)
            menu-value (if agent-id
                         (await
                          (menu/acquire-function-menu
                           {:seon.agent/id agent-id ::db/db database}))
                         {::menu/policy menu/default-policy
                          ::menu/offers []
                          ::menu/text ""})]
        (if (:seon.error/message menu-value)
          (provider-database-error menu-value)
          (let [affordances (if agent-id
                              (await (prefill-affordances database))
                              [])]
            (if (:seon.error/message affordances)
              (provider-database-error affordances)
              (await (step-loop-at! opts request database menu-value
                                    affordances)))))))))

(defn agent-adapter
  "A request function suitable for the agent turn's `llm-fn`.

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
  ([opts] (fn [request] (step-loop! opts request))))
