(ns seon.diffusion.oracle
  "UNIFIED control-signal oracle — the buzzsaw the diffusion worker calls ONCE
   between denoise steps. The three control signals exist as separate Seon-side
   legs; this ns dispatches all three and returns ONE combined control set.

   ## The three legs

   - PARSE — `seon.repl.internal/parse-forms` (no-fence basis, spans index the
     raw `canvas_text`, the `offset_map` basis). Yields the GOOD form spans (to
     HOLD) and the BROKEN-syntax spans (to RE-NOISE).
   - RETRIEVE — `seon.diffusion.retrieval/retrieve-for-canvas`. Yields the
     hallucinated-symbol corrections (`{span,replacement,spec_text}`) — each a
     clamp-toward-the-real-API.
   - EVAL — `seon.worker-eval` (a SEPARATE node self-host bundle; NOT pod- or
     bb-loadable). Where a form is syntactically clean but semantically wrong
     (undeclared var, def-vs-defn, arity, throw, non-termination), its verdict
     folds into either a renoise-span or (when retrieval already named the real
     API) is left to the injection. The eval bundle runs out-of-process, so its
     verdicts arrive as DATA via `::eval-verdicts` (span-keyed); when absent,
     `refine` runs PARSE + RETRIEVE only and says so in `::legs`.

   ## The combined control set (`::control-set`)

       {::clamps        [{::span ::source}]            ; HOLD — do NOT re-noise
        ::renoise-spans [{::span ::error-kind ::source}] ; RE-NOISE these
        ::injections    [<retrieval injection>]        ; clamp-toward-real-API
        ::legs          [:parse :retrieve (:eval)]}    ; which legs actually ran

   Partition rule: a CLAMP is a good form whose span overlaps NEITHER an
   injection (it carries a hallucination → steer it, don't freeze it) NOR a
   renoise span (parse error or an eval-bad form). So the three sets never
   double-cover a region.

   PURE reader over a db value (the retrieve leg reads `:seon.fn/sym` from the
   program graph; default `seon.db/*conn*`). No writes, no GPU."
  (:require
    [seon.embed]                                    ; :seon.embed/db schema (referenced below)
    [seon.repl.internal :as internal]
    [seon.diffusion.retrieval :as retrieval]
    [seon.schema :as schema]))

;; ============================================================
;; Schemas — reuse the retrieval leg's shapes (register-once-reference)
;; ============================================================

;; The worker's `offset_map`: token-index → canvas char offset. It is the
;; WORKER's artifact (it maps our char spans back to token positions); the
;; Seon side emits char spans directly, so it is carried through but not
;; required here.
(schema/register! ::offset-map [:vector :int])

;; A good form to HOLD across the next denoise steps.
(schema/register!
  ::clamp
  [:map
   [::span :seon.diffusion.retrieval/span]
   [::source :string]])

;; A span to RE-NOISE (broken syntax, or an eval-bad form).
(schema/register!
  ::renoise-span
  [:map
   [::span :seon.diffusion.retrieval/span]
   [::error-kind :keyword]                          ; parse: :eof/:invalid-token/…
   [::source :string]])

;; The out-of-process EVAL leg's verdict for one form, span-keyed so it folds
;; back onto the parse tier's forms.
(schema/register! ::eval-error-kind [:enum :compile :throw :interrupt])
(schema/register!
  ::eval-verdict
  [:map
   [::span :seon.diffusion.retrieval/span]
   [::ok? :boolean]
   [::error-kind {:optional true} ::eval-error-kind]
   [::message {:optional true} :string]])

(schema/register! ::legs [:vector [:enum :parse :retrieve :eval]])

;; The ORDERED generation phase whose grammar `refine` enforces (optional —
;; absent = no phase gate, all heads allowed).
(schema/register! ::phase [:enum :schemas :functions])

(schema/register!
  ::checkpoint
  [:map
   [::canvas-text :seon.diffusion.retrieval/canvas-text]
   [::offset-map {:optional true} ::offset-map]
   [::aliases {:optional true} :seon.diffusion.retrieval/aliases]
   [::k {:optional true} :seon.diffusion.retrieval/k]
   [::eval-verdicts {:optional true} [:vector ::eval-verdict]]
   [::phase {:optional true} ::phase]
   [::db {:optional true} :seon.embed/db]])

(schema/register!
  ::control-set
  [:map
   [::clamps [:vector ::clamp]]
   [::renoise-spans [:vector ::renoise-span]]
   [::injections [:vector :seon.diffusion.retrieval/injection]]
   [::legs ::legs]])

;; ============================================================
;; Span overlap
;; ============================================================

(defn- overlaps?
  "Half-open `[s e)` interval overlap."
  [[s1 e1] [s2 e2]]
  (and (< s1 e2) (< s2 e1)))

;; ============================================================
;; Structural lint — the CHEAP tier (T1): a well-formed form whose SHAPE is
;; wrong. Catches the class of mistakes the eval tier need never be paid for —
;; the AST already proves them. Currently: def-vs-defn.
;; ============================================================

(defn- malformed-def?
  "A top-level `(def …)` that is NOT a valid `def` — i.e. a `defn` typo such as
   `(def mean [v] (/ (reduce + v) (count v)))`. `def` READS clean (the parse
   tier is blind to it) but its only valid arities are `(def name)`,
   `(def name init)`, and `(def name \"docstring\" init)` (the middle arg a
   STRING). MORE than name+init — or a 3-arg `def` whose middle isn't a string —
   is structurally malformed: almost certainly a dropped `n`. No semantics, no
   eval — the AST alone decides, so it renoises at the ~free structural tier
   instead of paying the 2.6ms eval. `(def xs [1 2 3])` (a real vector binding)
   stays valid (name+init = 2 args)."
  [form]
  (and (seq? form)
       (= 'def (first form))
       (let [args (rest form)                         ; name + the init forms
             n    (count args)]
         (or (> n 3)
             (and (= n 3) (not (string? (second args))))))))

;; ============================================================
;; Phased grammar gate — the generalization of the structural tier. Generation
;; runs in ORDERED phases; each phase ALLOWS a set of top-level form heads and
;; renoises everything else. Lock data-modeling to schemas FIRST (no premature
;; `defn` body), then unlock functions once the contract is fixed. Matching is
;; by the head symbol's NAME (namespace-agnostic), so `schema/register!`,
;; `seon.schema/register!`, and a bare `register!` all read as "register!".
;; ============================================================

(def ^:private phase-grammars
  {:schemas   {:allow #{"ns" "register!" "comment"}
               :intent "schemas only — register! the data shape before any fn body"}
   :functions {:allow #{"ns" "defn" "comment"}
               :intent "functions only — defn with specs; the schema contract is locked"}})

(defn- head-name
  "The NAME of a form's head symbol (namespace stripped), or nil if the form is
   not a `(head …)` list led by a symbol."
  [form]
  (let [h (and (seq? form) (first form))]
    (when (symbol? h) (name h))))

(defn- phase-violation?
  "True when `form` is a top-level call whose head is NOT allowed in `phase`.
   A non-call form (a bare literal/vector) has no head → not a violation here
   (the parse/structural tiers own those)."
  [phase form]
  (when-let [allow (get-in phase-grammars [phase :allow])]
    (when-let [h (head-name form)]
      (not (contains? allow h)))))

;; ============================================================
;; The unified dispatcher
;; ============================================================

(defn refine
  "Run the unified oracle on one mid-denoise checkpoint and return the combined
   `::control-set`. Runs PARSE (always) + RETRIEVE (always, reads the program
   graph) + EVAL FOLD (only when `::eval-verdicts` are supplied — the eval
   bundle is a separate node spawn).

   - `::clamps` = good form spans NOT overlapping an injection or a renoise span.
   - `::renoise-spans` = parse-error spans + STRUCTURAL-lint spans (a form that
     reads clean but has a wrong shape the AST proves, e.g. def-vs-defn) +
     PHASE-grammar spans (when `::phase` is supplied — a form whose head is not
     allowed in the current generation phase, e.g. a `defn` during the :schemas
     phase) + any eval-bad form NOT already covered by an injection (retrieval's
     correction supersedes a re-noise). The structural + phase tiers are ~free —
     they catch shape/phase mistakes WITHOUT paying the out-of-process eval bundle.
   - `::injections` = retrieval's hallucinated-symbol corrections.
   - `::legs` = `[:parse :retrieve]`, plus `:eval` when verdicts were folded."
  {:malli/schema [:=> [:cat ::checkpoint] ::control-set]}
  [{::keys [canvas-text aliases k eval-verdicts phase db]}]
  (let [entries  (internal/parse-forms canvas-text {:strip-fences? false})
        forms    (filter #(= :form (:kind %)) entries)
        reads    (filter #(= :read (:kind %)) entries)
        ;; RETRIEVE leg — hallucinated-symbol injections (reads the graph).
        {::retrieval/keys [injections]}
        (retrieval/retrieve-for-canvas
          (cond-> {:seon.diffusion.retrieval/canvas-text canvas-text}
            aliases (assoc :seon.diffusion.retrieval/aliases aliases)
            k       (assoc :seon.diffusion.retrieval/k k)
            db      (assoc :seon.diffusion.retrieval/db db)))
        inj-spans     (mapv :seon.diffusion.retrieval/span injections)
        ;; PARSE-tier renoise spans (broken syntax).
        parse-renoise (mapv (fn [{:keys [span error-kind source]}]
                              {::span span ::error-kind error-kind ::source source})
                            reads)
        ;; STRUCTURAL-tier (T1) renoise spans — a form that READS clean but has a
        ;; wrong SHAPE the AST alone proves (def-vs-defn). Renoises the offending
        ;; form WITHOUT paying the eval tier.
        struct-renoise (->> forms
                            (keep (fn [{:keys [span source form]}]
                                    (when (and span (malformed-def? form))
                                      {::span span ::error-kind :def-vs-defn
                                       ::source source})))
                            vec)
        ;; PHASE-grammar renoise spans — a form whose head is not ALLOWED in the
        ;; current generation phase (e.g. a `defn` body during the :schemas phase).
        ;; Only active when `phase` is supplied. Renoises the disallowed form so
        ;; the model regenerates within the phase grammar.
        phase-renoise (if phase
                        (->> forms
                             (keep (fn [{:keys [span source form]}]
                                     (when (and span (phase-violation? phase form))
                                       {::span span ::error-kind :phase-violation
                                        ::source source})))
                             vec)
                        [])
        ;; EVAL fold — a bad verdict becomes a renoise span UNLESS retrieval
        ;; already named the real API for that span (injection supersedes).
        eval-renoise  (->> eval-verdicts
                           (remove ::ok?)
                           (remove (fn [{::keys [span]}]
                                     (some #(overlaps? span %) inj-spans)))
                           (mapv (fn [{::keys [span error-kind]}]
                                   {::span span
                                    ::error-kind (or error-kind :throw)
                                    ::source (subs canvas-text (first span) (second span))})))
        renoise-spans (-> parse-renoise
                          (into struct-renoise)
                          (into phase-renoise)
                          (into eval-renoise))
        bad-spans     (into inj-spans (map ::span renoise-spans))
        ;; CLAMPS — good forms that carry no hallucination and are not broken.
        clamps        (->> forms
                           (keep (fn [{:keys [span source]}]
                                   (when (and span
                                              (not (some #(overlaps? span %) bad-spans)))
                                     {::span span ::source source})))
                           vec)]
    {::clamps        clamps
     ::renoise-spans renoise-spans
     ::injections    (vec injections)
     ::legs          (cond-> [:parse :retrieve]
                       (seq eval-verdicts) (conj :eval))}))

;; ============================================================
;; Wire boundary — the combined set → the worker's {op:"refine", …} object
;; ============================================================

(defn to-wire
  "Flatten a `::control-set` to the worker's JSON-ready `{op:\"refine\", …}`
   object: parallel `clamps` / `renoise_spans` / `injections` arrays (each
   `span` a JS `[start end]` array the worker maps via its offset_map) plus the
   `legs` that ran. Each injection reuses [[retrieval/to-wire]] so its
   `{op:\"clamp\", span, replacement, spec_text}` shape is byte-identical to the
   standalone retrieval emit."
  {:malli/schema [:=> [:cat [:map [::control-set ::control-set]]] :any]}
  [{::keys [control-set]}]
  (let [{::keys [clamps renoise-spans injections legs]} control-set]
    #js {:op            "refine"
         :legs          (clj->js (mapv name legs))
         :clamps        (clj->js (mapv (fn [c] {:span (::span c) :source (::source c)})
                                       clamps))
         :renoise_spans (clj->js (mapv (fn [r] {:span       (::span r)
                                                :error_kind (name (::error-kind r))
                                                :source     (::source r)})
                                       renoise-spans))
         :injections    (let [arr (array)]
                          (doseq [inj injections]
                            (.push arr (retrieval/to-wire
                                         {:seon.diffusion.retrieval/injection inj})))
                          arr)}))
