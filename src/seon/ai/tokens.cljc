(ns seon.ai.tokens
  "Token estimation, the one sizer: a characters-per-token ratio applied to a string.

  Human-visible sizes are estimated tokens, never character counts. The
  shipped prior is a measured ratio with no invented error band. Pure and
  registration-free: its declarations live in
  `resources/seon/schemas/seon.ai.tokens.edn`.")

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
