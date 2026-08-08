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
  - the SHIPPED basis is the flat `chars/4` fallback used when no usage
    has been recorded yet for that model. It is honest only about being
    uncalibrated: it carries NO error band, and every report says which
    basis produced it.

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
  "The no-tokenizer fallback: ~4 characters per token. The ONLY constant
  in this namespace, used exactly when a model has no recorded usage to
  calibrate against. Measured drift on DeepSeek is ~23% low, which is
  why an estimate on this basis carries no error band and says so."
  4)

(def shipped-calibration
  "The uncalibrated fallback calibration. No `:seon.ai.tokens/relative-error`
  key: with no observations the error is genuinely UNKNOWN, and an
  invented band would be a tuned constant pretending to be evidence."
  {:seon.ai.tokens/chars-per-token (double shipped-chars-per-token)
   :seon.ai.tokens/basis :seon.ai.tokens/shipped-constant
   :seon.ai.tokens/sample-count 0})

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

  With no usable observation this returns [[shipped-calibration]]:
  uncalibrated, band-free, and named as such."
  {:malli/schema [:=> [:cat :seon.ai.tokens/observations]
                  :seon.ai.tokens/calibration]}
  [observations]
  (let [usable (filterv observation-usable? observations)]
    (if (empty? usable)
      shipped-calibration
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
         :seon.ai.tokens/relative-error band}))))

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

  The one-argument arity uses the uncalibrated [[shipped-calibration]]
  and is therefore `(quot (count text) 4)` exactly as before. Supply a
  calibration whenever one is derivable — a budget check always
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

(defn clip-str
  "Clip `text` to a token budget and mark a cut with `…`."
  {:malli/schema
   [:function
    [:=> [:cat [:or :nil :string] [:int {:min 0}]] :string]
    [:=> [:cat [:or :nil :string] [:int {:min 0}]
          :seon.ai.tokens/calibration]
     :string]]}
  ([text token-budget] (clip-str text token-budget shipped-calibration))
  ([text token-budget calibration]
   (let [text (str text)
         character-limit (estimate-chars token-budget calibration)]
     (if (> (count text) character-limit)
       (str (subs text 0 character-limit) "…")
       text))))

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

  On the uncalibrated shipped basis there is no band, so no
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
         (if (= basis :seon.ai.tokens/observed)
           (str "calibrated at " (rounded chars-per-token 2)
                " characters per token from " sample-count
                " recorded provider usage facts (worst observed miss "
                (rounded (* 100.0 relative-error) 1)
                "%, so as much as " upper-bound " tokens)")
           (str "on the uncalibrated " (long chars-per-token)
                "-characters-per-token fallback — no provider usage has been"
                " recorded for this model yet, so the real count is unknown"
                " and has run about a quarter higher in practice")))))
