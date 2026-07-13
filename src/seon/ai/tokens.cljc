(ns seon.ai.tokens
  "Token <-> char estimation — the ONE place the `chars/4` heuristic lives.

   Seon has NO tokenizer dependency (CLAUDE.md, Token Reporting): every
   token count in context-size reporting is the `chars / 4` estimate.
   This leaf ns is that estimate, factored out of the inlined
   `(quot (count s) 4)` sites so the heuristic has a single home — and a
   single seam to swap a real tokenizer in behind later (only this ns
   changes; every caller keeps the same map-in/map-out signature).

   `estimate` (string -> tokens) and `estimate-chars` (tokens -> chars)
   are exact inverses up to the `chars-per-token` factor."
  (:require
    [seon.schema :as schema]))

(def chars-per-token
  "The no-tokenizer heuristic: ~4 characters per token (CLAUDE.md Token
   Reporting). The single constant behind [[estimate]] /
   [[estimate-chars]] — change it (or replace this ns with a real
   tokenizer) in ONE place."
  4)

(schema/register! ::text :string)
(schema/register! ::tokens :int)
(schema/register! ::chars :int)

(defn chars->tokens
  "Estimate tokens from an already-measured character count `n`.

   `(quot n chars-per-token)` — the seam for size badges that carry an
   int char-count (e.g. `:seon.render.value/string-len`) rather than the
   string itself — same single heuristic, no inlined `(quot n 4)`."
  {:malli/schema [:=> [:catn [::chars ::chars]] ::tokens]}
  [n]
  (quot n chars-per-token))

(defn estimate
  "Estimate the token count of `s` as `(quot (count s) chars-per-token)`.
   The canonical `:seon.render/token-estimate` derivation — no tokenizer
   dep, integer-floored."
  {:malli/schema [:=> [:catn [::text ::text]] ::tokens]}
  [s]
  (chars->tokens (count s)))

(defn estimate-chars
  "Inverse of [[estimate]]: the approximate char count of `n` tokens.

   `(* n chars-per-token)`. Used to place a token-offset (e.g. a
   provider's cached-token count) back onto a char-measured bar."
  {:malli/schema [:=> [:catn [::tokens ::tokens]] ::chars]}
  [n]
  (* n chars-per-token))

;; ============================================================
;; Bounded print — the ONE clip helper (dual-code-paths registry C2).
;; The API speaks TOKENS (Token Reporting rule); char math is internal.
;; ============================================================

(schema/register! ::budget [:int {:min 0}])
;; (fn [budget-tokens total-tokens] -> string) appended at the cut — a
;; caller's LOUD marker (e.g. seon.agent.ctx's truncation guides).
(schema/register! ::marker-fn 'fn?)

(defn- ellipsis-marker
  "The default cut marker — a bare ellipsis."
  [_budget _total]
  "…")

(defn clip-str
  "Clip string `s` to a token `budget`, marking the cut (default `…`).

   A fitting `s` is returned unchanged. Otherwise it is cut at the
   budget's char equivalent and `(marker budget total-tokens)` is
   appended, so a caller can surface a loud, token-denominated
   truncation notice. Nil-safe (`nil` → `\"\"`)."
  {:malli/schema [:function
                  [:=> [:catn [::text :any] [::budget ::budget]] ::text]
                  [:=> [:catn [::text :any] [::budget ::budget]
                        [::marker-fn ::marker-fn]] ::text]]}
  ([s budget] (clip-str s budget ellipsis-marker))
  ([s budget marker]
   (let [s     (str s)
         limit (estimate-chars budget)]
     (if (> (count s) limit)
       (str (subs s 0 limit) (marker budget (estimate s)))
       s))))

(defn bounded-pr-str
  "`pr-str` of `v` clipped to a token `budget` (`…` marks the cut).

   The one bounded-print for quoting a value in an error message or a
   glance surface without dumping the whole structure."
  {:malli/schema [:=> [:catn [::value :any] [::budget ::budget]] ::text]}
  [v budget]
  (clip-str (pr-str v) budget))
