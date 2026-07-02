(ns seon.diffusion.loop
  "THE control LOOP of the diffusion buzzsaw — the orchestration that turns the
   per-checkpoint `seon.diffusion.oracle/refine` call into a CONVERGENT process.
   Every leg (parse / retrieve / eval) and the unified dispatcher are unit-tested
   in isolation; this ns tests the one thing they don't — the LOOP itself:
   refine → apply → re-refine → converge (or give up).

   ## The two pieces

   1. `checkpoint-policy` — the PURE decision fn. Given one `::oracle/control-set`,
      the iteration index, a K-budget, and the PREVIOUS control set, it returns
      CONTINUE / CONVERGED / GIVE-UP:

        - CONVERGED — no `::oracle/renoise-spans` AND no `::oracle/injections`
          (the canvas parses and every committed symbol resolves).
        - GIVE-UP — the K-budget is exhausted (`iteration ≥ k-budget`, the HARD
          termination backstop), OR no progress was made (the control set's error
          signature is identical to the previous iteration's — the worker cannot
          move the canvas, so iterating again is pointless).
        - CONTINUE — errors remain, the budget is not spent, and the last step
          changed something.

   2. `dry-run` — the CPU dry-run loop. From a degraded canvas it calls `refine`,
      consults the policy, and (on CONTINUE) APPLIES the control set by MOCKING the
      diffusion worker DETERMINISTICALLY:

        - a CLAMP span is held VERBATIM (the worker freezes those token positions);
        - an INJECTION span is replaced by its `::retrieval/replacement` (the real
          symbol the retrieval leg named);
        - a RENOISE span is replaced by the canned 'corrected' fill the `::fills`
          fixture supplies for that broken source (the worker re-denoises those
          positions); a span with NO fill is left unchanged — genuinely unfixable.

      Regions covered by neither edit (clamps + the gaps between forms) are copied
      verbatim, so the next canvas differs from the last ONLY at injection +
      renoise spans. The new canvas is then re-`refine`d (its spans recomputed in
      the fresh char basis) and the cycle repeats until the policy says CONVERGED
      or GIVE-UP.

   ## How the real worker substitutes for the mock APPLY

   This harness IS the orchestration; the GPU worker only changes HOW spans get
   re-filled. The mock APPLY's three transforms are exactly the worker's
   `good_clamp_for_renoise` + clamp/infill surface: clamp positions HELD, injection
   positions forced toward `::replacement` (encoder-KV `::spec-text` appended),
   renoise positions left OUT of the clamp set so the entropy bound re-decides them
   — replacing the fixture fill with an actual denoise step. The control flow,
   the convergence policy, and the span coordinate system are identical; only the
   span→text function differs (a fixture lookup here, a denoise there). Spans stay
   in the `canvas_text`/`offset_map` basis end to end (closed-loop-span-alignment).

   PURE + synchronous: `refine` reads a db VALUE (no GPU, no embeddings, no
   awaits). NO writes."
  (:require
    [clojure.string :as str]
    [seon.diffusion.oracle :as oracle]
    [seon.diffusion.retrieval :as retrieval]
    [seon.schema :as schema]))

;; ============================================================
;; Schemas
;; ============================================================

(schema/register! ::canvas-text :seon.diffusion.retrieval/canvas-text)
;; the fixture: broken-form SOURCE string → its canned 'corrected' fill. A span
;; whose source is absent from the map is genuinely unfixable (no fill).
(schema/register! ::fills [:map-of :string :string])
(schema/register! ::k-budget [:int {:min 1}])
(schema/register! ::iteration [:int {:min 0}])

(schema/register! ::verdict [:enum :continue :converged :give-up])
(schema/register! ::reason
  [:enum :errors-remain :clean :budget-exhausted :no-progress])

;; one APPLY edit — a span and the text that replaces it (internal shape).
(schema/register! ::span :seon.diffusion.retrieval/span)
(schema/register! ::text :string)

(schema/register! ::policy-request
  [:map
   [::control-set :seon.diffusion.oracle/control-set]
   [::iteration ::iteration]
   [::k-budget ::k-budget]
   [::prev-control-set {:optional true} :seon.diffusion.oracle/control-set]])

(schema/register! ::policy-response
  [:map
   [::verdict ::verdict]
   [::reason ::reason]])

(schema/register! ::apply-request
  [:map
   [::canvas-text ::canvas-text]
   [::control-set :seon.diffusion.oracle/control-set]
   [::fills ::fills]])

(schema/register! ::apply-response
  [:map
   [::canvas-text ::canvas-text]])

;; one TRACE step — the canvas refined this iteration + the control set it yielded.
(schema/register! ::step
  [:map
   [::iteration ::iteration]
   [::canvas-text ::canvas-text]
   [::control-set :seon.diffusion.oracle/control-set]])

(schema/register! ::trace [:vector ::step])

(schema/register! ::run-request
  [:map
   [::canvas-text ::canvas-text]
   [::fills ::fills]
   [::k-budget ::k-budget]
   [::db {:optional true} :seon.embed/db]])

(schema/register! ::run-response
  [:map
   [::trace ::trace]
   [::verdict ::verdict]
   [::reason ::reason]
   [::iterations ::iteration]])

;; ============================================================
;; The convergence policy (PURE)
;; ============================================================

(defn- error-signature
  "The part of a control set the policy compares for PROGRESS: the renoise spans
   and the injection (span, replacement) pairs. Two iterations with the same
   signature means the worker did not move the canvas."
  [control-set]
  {::renoise    (mapv ::oracle/span (::oracle/renoise-spans control-set))
   ::injections (mapv (juxt ::retrieval/span ::retrieval/replacement)
                      (::oracle/injections control-set))})

(defn checkpoint-policy
  "Decide CONTINUE / CONVERGED / GIVE-UP for one checkpoint.

   CONVERGED iff the control set has neither renoise spans nor injections. Otherwise GIVE-UP when
   the K-budget is spent (`::iteration ≥ ::k-budget` — the hard termination
   backstop) or no progress was made since `::prev-control-set` (identical error
   signature); else CONTINUE."
  {:malli/schema [:=> [:cat ::policy-request] ::policy-response]}
  [{::keys [control-set iteration k-budget prev-control-set]}]
  (let [errors? (boolean (or (seq (::oracle/renoise-spans control-set))
                             (seq (::oracle/injections control-set))))]
    (cond
      (not errors?)
      {::verdict :converged ::reason :clean}

      (>= iteration k-budget)
      {::verdict :give-up ::reason :budget-exhausted}

      (and prev-control-set
           (= (error-signature control-set) (error-signature prev-control-set)))
      {::verdict :give-up ::reason :no-progress}

      :else
      {::verdict :continue ::reason :errors-remain})))

;; ============================================================
;; The mock APPLY — deterministic stand-in for the GPU denoise step
;; ============================================================

(defn- edits-for
  "The ordered, non-overlapping edits the APPLY step performs: each injection
   span → its `::retrieval/replacement`; each renoise span → the `::fills` entry
   for its source (omitted when there is no fill — that span is unfixable).
   Clamp spans and inter-form gaps carry NO edit (held verbatim). Sorted by
   span start; the control-set partition rule guarantees disjointness."
  [control-set fills]
  (let [inj-edits (mapv (fn [inj]
                          {::span (::retrieval/span inj)
                           ::text (::retrieval/replacement inj)})
                        (::oracle/injections control-set))
        ren-edits (keep (fn [r]
                          (when-let [fill (get fills (::oracle/source r))]
                            {::span (::oracle/span r) ::text fill}))
                        (::oracle/renoise-spans control-set))]
    (vec (sort-by (comp first ::span) (into inj-edits ren-edits)))))

(defn- splice
  "Rebuild a canvas by replacing each edit's span with its text, copying every
   other region verbatim. `edits` are sorted, disjoint."
  [canvas edits]
  (loop [pos 0 es edits out []]
    (if-let [{::keys [span text]} (first es)]
      (let [[s e] span]
        (recur e (rest es) (conj out (subs canvas pos s) text)))
      (str/join (conj out (subs canvas pos))))))

(defn apply-control-set
  "MOCK the diffusion worker for ONE checkpoint: produce the next canvas.

   Produces the next canvas from the
   current one by holding clamp spans + gaps verbatim, replacing each injection
   span with the real symbol, and replacing each renoise span with its fixture
   fill. The next refine recomputes spans/specials in the fresh char basis — this
   is the deterministic CPU analogue of `resume_renoise` re-denoising the
   non-clamped positions."
  {:malli/schema [:=> [:cat ::apply-request] ::apply-response]}
  [{::keys [canvas-text control-set fills]}]
  {::canvas-text (splice canvas-text (edits-for control-set fills))})

;; ============================================================
;; The dry-run loop
;; ============================================================

(defn dry-run
  "Run the FULL buzzsaw control loop on CPU from a degraded `::canvas-text`.

   Each iteration: `refine` the current canvas, consult `checkpoint-policy`, and on
   CONTINUE `apply-control-set` (the mock worker, driven by `::fills`) to produce
   the next canvas. Terminates at CONVERGED or GIVE-UP — bounded HARD by
   `::k-budget` iterations regardless of `::fills`, so it can never spin forever.
   Returns the per-iteration `::trace` (canvas + control set each step) plus the
   terminal `::verdict`/`::reason` and the `::iterations` run."
  {:malli/schema [:=> [:cat ::run-request] ::run-response]}
  [{::keys [canvas-text fills k-budget db]}]
  (loop [iteration 0
         canvas    canvas-text
         trace     []
         prev      nil]
    (let [control-set (oracle/refine
                        (cond-> {::oracle/canvas-text canvas}
                          db (assoc ::oracle/db db)))
          trace'      (conj trace {::iteration    iteration
                                   ::canvas-text  canvas
                                   ::control-set  control-set})
          {::keys [verdict reason]}
          (checkpoint-policy
           (cond-> {::control-set control-set
                    ::iteration   iteration
                    ::k-budget    k-budget}
             prev (assoc ::prev-control-set prev)))]
      (if (= verdict :continue)
        (let [{next-canvas ::canvas-text}
              (apply-control-set {::canvas-text canvas
                                  ::control-set control-set
                                  ::fills       fills})]
          (recur (inc iteration) next-canvas trace' control-set))
        {::trace      trace'
         ::verdict    verdict
         ::reason     reason
         ::iterations (inc iteration)}))))
