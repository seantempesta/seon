(ns seon.ai.tokens
  "Token estimation — the ONE place the `chars/4` heuristic lives.

  Adopted from the quarry (`src-old/seon/ai/tokens.cljc`), reduced to
  the surviving need: human-visible sizes are estimated tokens, never
  raw character counts (the standing house rule), and the fresh tree
  had no estimator (`:seon.ai/tokens` is provider-reported completion
  tokens, which does not exist before a call). The context-blocks
  contribution rows are the first consumer.

  NAMED ADOPT NOTE (context-blocks contract §9): the contract names the
  adopt as `seon.ai/estimate-tokens`; it lands here as
  `seon.ai.tokens/estimate` instead because `src/seon/ai.cljc` is
  another lane's protected owner in this wave and the quarry namespace
  is already the standing name (`seon.ai.tokens/estimate` is the exact
  spelling CLAUDE.md's token rule uses). One estimator, one home, one
  seam to swap a real tokenizer in behind later.

  Seon has NO tokenizer dependency: every token count in context-size
  reporting is the `chars / 4` estimate, integer-floored.

  DELIBERATELY REGISTRATION-FREE: this leaf loads inside the cluster's
  own require chain, and a load-time `register!` here would admit the
  whole candidate population while its predicate owners are still
  mid-load — the cyclic-require class. Two inline scalar forms cost
  nothing and keep the leaf loadable from anywhere.")

(def chars-per-token
  "The no-tokenizer heuristic: ~4 characters per token. The single
  constant behind [[estimate]] — change it (or replace this namespace
  with a real tokenizer) in ONE place."
  4)

(defn estimate
  "Estimate the token count of `text`: `(quot (count text) 4)`.
  The canonical token-estimate derivation — no tokenizer dependency,
  integer-floored, zero for the empty string."
  {:malli/schema [:=> [:cat :string] [:int {:min 0}]]}
  [text]
  (quot (count text) chars-per-token))

(defn estimate-chars
  "Estimate the character capacity of a token budget."
  {:malli/schema [:=> [:cat [:int {:min 0}]] [:int {:min 0}]]}
  [token-budget]
  (* token-budget chars-per-token))

(defn clip-str
  "Clip `text` to a token budget and mark a cut with `…`."
  {:malli/schema
   [:=> [:cat [:or :nil :string] [:int {:min 0}]] :string]}
  [text token-budget]
  (let [text (str text)
        character-limit (estimate-chars token-budget)]
    (if (> (count text) character-limit)
      (str (subs text 0 character-limit) "…")
      text)))
