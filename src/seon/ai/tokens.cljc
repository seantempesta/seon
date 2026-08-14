(ns seon.ai.tokens
  "Token estimation — the ONE sizer, calibrated against recorded provider
  usage rather than trusting a constant.

  Human-visible sizes are estimated tokens, never raw character counts
  (the standing house rule), and the prompt budget is enforced through
  the same owner. There is no tokenizer dependency, so an estimate is a
  characters-per-token ratio applied to a string — but WHICH ratio is
  evidence, not a guess:

  - the OBSERVED basis fits the ratio to committed facts — the exact
    prompt characters a capture recorded against the `prompt_tokens`
    the provider billed for that same run — per model, because
    tokenizers differ. Measured on cluster `default` 2026-08-08 over 14
    attempts of `deepseek-v4-flash`: 3.28 characters per token, not 4;
  - the SHIPPED PRIOR is 3.2 characters per token, measured from 17
    recorded DeepSeek prompt samples across the 2026-08-08 whole-system
    arc and 2026-08-10 model-authoring observer. It carries NO invented
    error band; the first local attempt replaces it with the model's own
    committed usage fit.

  Why this exists: `chars/4` ran 23-26% low against DeepSeek's own
  count, so a 35,827-token prompt passed a 32,768-token budget with no
  refusal and no warning (the whole-system-arc observer report,
  2026-08-08). A dial that says 32,768 and means ~40,000 is the failure
  class this project treats as a defect even while it works.

  [[budget-report]] is the one budget judgement: an estimate whose
  calibrated error band reaches the budget is `::near-limit` and says
  so loudly, rather than being silently admitted because the point
  estimate happened to fit.

  Everything here is PURE. The facts-reading derivation of a
  calibration lives with the budget seam that owns the database value
  (`seon.cluster.prompt/model-calibration`); this namespace stays
  portable and loadable from anywhere.

  DELIBERATELY REGISTRATION-FREE: this leaf loads inside the cluster's
  own require chain, and a load-time `register!` here would admit the
  whole candidate population while its predicate owners are still
  mid-load — the cyclic-require class. The declarations live in
  `resources/seon/schemas/seon.ai.tokens.edn` and are admitted by the
  one schema population like every other file.")

(def shipped-chars-per-token
  "The evidence-backed default when no database-derived prior is supplied."
  3.2)

(def shipped-prior-sample-count
  "Recorded prompt samples underlying the shipped cross-run prior."
  17)

(defn prior-calibration
  "A measured prior calibration with no invented error band."
  {:malli/schema [:=> [:cat :seon.ai.tokens/chars-per-token]
                  :seon.ai.tokens/calibration]}
  [chars-per-token]
  {:seon.ai.tokens/chars-per-token (double chars-per-token)
   :seon.ai.tokens/basis :seon.ai.tokens/shipped-prior
   :seon.ai.tokens/sample-count shipped-prior-sample-count})

(def shipped-calibration
  "The measured shipped prior when no database-derived prior is supplied."
  (prior-calibration shipped-chars-per-token))

(defn- observation-usable?
  [observation]
  (and (pos? (long (get observation :seon.ai.tokens/characters 0)))
       (pos? (long (get observation :seon.ai.usage/prompt-tokens 0)))))

(defn calibrate
  "Fit one characters-per-token calibration to recorded observations.

  Each observation pairs the exact characters that went out with the
  `prompt_tokens` the provider counted for them. The ratio is fitted
  over the TOTALS, so the large prompts that actually approach a budget
  dominate the fit, and the reported `:seon.ai.tokens/relative-error`
  is the worst relative miss that ratio makes across the same
  observations — the honest margin a budget check must carry.

  With no usable observation this returns the supplied measured prior,
  still band-free and named as such."
  {:malli/schema
   [:function
    [:=> [:cat :seon.ai.tokens/observations]
     :seon.ai.tokens/calibration]
    [:=> [:cat :seon.ai.tokens/observations
          :seon.ai.tokens/calibration]
     :seon.ai.tokens/calibration]]}
  ([observations] (calibrate observations shipped-calibration))
  ([observations fallback-calibration]
  (let [usable (filterv observation-usable? observations)]
    (if (empty? usable)
      fallback-calibration
      (let [characters (reduce + 0 (map :seon.ai.tokens/characters usable))
            provider-tokens (reduce + 0 (map :seon.ai.usage/prompt-tokens
                                             usable))
            ratio (/ (double characters) (double provider-tokens))
            band (reduce
                  max
                  0.0
                  (map (fn [observation]
                         (let [actual (double (:seon.ai.usage/prompt-tokens
                                               observation))
                               predicted (/ (double
                                             (:seon.ai.tokens/characters
                                              observation))
                                            ratio)]
                           (/ (abs (- predicted actual)) actual)))
                       usable))]
        {:seon.ai.tokens/chars-per-token ratio
         :seon.ai.tokens/basis :seon.ai.tokens/observed
         :seon.ai.tokens/sample-count (count usable)
         :seon.ai.tokens/relative-error band})))))

(defn estimate-of-characters
  "Estimate the token count of `character-count` characters.

  For a caller that has a width or a cap rather than a string. It is the
  same derivation [[estimate]] performs, so a caller never divides by
  the ratio itself — the constant is not the interface."
  {:malli/schema
   [:function
    [:=> [:cat :seon.ai.tokens/characters] [:int {:min 0}]]
    [:=> [:cat :seon.ai.tokens/characters :seon.ai.tokens/calibration]
     [:int {:min 0}]]]}
  ([character-count] (estimate-of-characters character-count
                                             shipped-calibration))
  ([character-count calibration]
   (long (Math/floor (/ character-count
                        (:seon.ai.tokens/chars-per-token calibration))))))

(defn estimate
  "Estimate the token count of `text`, integer-floored, zero for empty.

  The one-argument arity uses the evidence-backed [[shipped-calibration]].
  Supply a calibration whenever one is derivable — a budget check always
  should."
  {:malli/schema
   [:function
    [:=> [:cat :string] [:int {:min 0}]]
    [:=> [:cat :string :seon.ai.tokens/calibration] [:int {:min 0}]]]}
  ([text] (estimate text shipped-calibration))
  ([text calibration]
   (estimate-of-characters (count text) calibration)))

(defn estimate-chars
  "Estimate the character capacity of a token budget."
  {:malli/schema
   [:function
    [:=> [:cat [:int {:min 0}]] [:int {:min 0}]]
    [:=> [:cat [:int {:min 0}] :seon.ai.tokens/calibration]
     [:int {:min 0}]]]}
  ([token-budget] (estimate-chars token-budget shipped-calibration))
  ([token-budget calibration]
   (long (Math/floor (* token-budget
                        (:seon.ai.tokens/chars-per-token calibration))))))

(defn budget-report
  "Judge `text` against a token `budget` under one calibration.

  THE ONE BUDGET JUDGEMENT, so no caller has to remember that a point
  estimate is not the whole truth. Three verdicts:

  - `:seon.ai.tokens/over` — the estimate itself exceeds the budget.
    Refuse or shrink;
  - `:seon.ai.tokens/near-limit` — the estimate fits but the
    calibration's own observed error band reaches past the budget, so
    the real prompt may already be over. Admitting this silently is
    what shipped an over-budget prompt; it is admitted LOUDLY instead;
  - `:seon.ai.tokens/within` — the estimate plus its band still fits.

  On the shipped prior there is no band, so no
  `near-limit` verdict is derivable and the report says which basis it
  used. Read the basis before trusting the verdict."
  {:malli/schema [:=> [:cat :string [:int {:min 0}]
                       :seon.ai.tokens/calibration]
                  :seon.ai.tokens/budget-report]}
  [text budget calibration]
  (let [estimated (estimate text calibration)
        band (:seon.ai.tokens/relative-error calibration)
        upper-bound (when band
                      (long (Math/ceil (* estimated (+ 1.0 band)))))
        verdict (cond
                  (> estimated budget) :seon.ai.tokens/over
                  (and upper-bound (> upper-bound budget))
                  :seon.ai.tokens/near-limit
                  :else :seon.ai.tokens/within)]
    (cond-> {:seon.ai.tokens/estimated estimated
             :seon.ai.tokens/verdict verdict
             :seon.ai.tokens/basis (:seon.ai.tokens/basis calibration)
             :seon.ai.tokens/chars-per-token
             (:seon.ai.tokens/chars-per-token calibration)
             :seon.ai.tokens/sample-count
             (:seon.ai.tokens/sample-count calibration)
             :seon.config.ai/prompt-token-budget budget}
      band (assoc :seon.ai.tokens/relative-error band
                  :seon.ai.tokens/upper-bound upper-bound))))

(defn- rounded
  "`value` rounded to `places` decimals, as a portable printable number."
  [value places]
  (let [scale (double (reduce * 1.0 (repeat places 10)))]
    (/ (double (Math/round (* (double value) scale))) scale)))

(defn report-sentence
  "Say one estimate, its basis, and its margin in a readable sentence.

  The human-visible form of a [[budget-report]]. Used by the budget
  refusal, the near-limit note, and anything else that shows a size, so
  a reader is never left guessing which basis produced a number."
  {:malli/schema [:=> [:cat :seon.ai.tokens/budget-report] :string]}
  [report]
  (let [{:seon.ai.tokens/keys [estimated basis chars-per-token sample-count
                               relative-error upper-bound]
         budget :seon.config.ai/prompt-token-budget} report]
    (str estimated " estimated tokens against a " budget "-token budget, "
         (case basis
           :seon.ai.tokens/observed
           (str "calibrated at " (rounded chars-per-token 2)
                " characters per token from " sample-count
                " recorded provider usage facts (worst observed miss "
                (rounded (* 100.0 relative-error) 1)
                "%, so as much as " upper-bound " tokens)")

           :seon.ai.tokens/shipped-prior
           (str "using the shipped measured prior of "
                (rounded chars-per-token 2)
                " characters per token from " sample-count
                " recorded provider prompt samples; no local usage exists"
                " yet, so no error band is asserted")

           (str "on the legacy uncalibrated " (long chars-per-token)
                "-characters-per-token fallback")))))
