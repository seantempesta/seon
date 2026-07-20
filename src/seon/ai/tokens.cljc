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
(schema/register! ::character-truncated? :boolean)
(schema/register! ::bounded-print-result
                  [:map {:closed true}
                   [::text ::text]
                   [::character-truncated? ::character-truncated?]])

#?(:cljs
   (def ^:private bounded-print-sentinel
     (js/Error. "bounded print complete")))

#?(:cljs
   (deftype CappedWriter [chunks limit written truncated? sentinel]
     IWriter
     (-write [_ s]
       (let [remaining (max 0 (- limit @written))
             n         (count s)
             retained  (min remaining n)]
         (when (pos? retained)
           (.push chunks (subs s 0 retained))
           (vswap! written + retained))
         (when (> n retained)
           (vreset! truncated? true)
           (throw sentinel))))
     (-flush [_] nil)))

#?(:cljs
   (defn- capped-pr-str
     "Print `value` through a bounded writer without expanding huge strings."
     [value limit]
     (let [chunks     (array)
           written    (volatile! 0)
           truncated? (volatile! false)
           writer     (CappedWriter. chunks limit written truncated?
                                     bounded-print-sentinel)
           bounded-part (fn [s]
                          (subs s 0 (min (count s) limit)))
           alt-impl   (fn [x w opts]
                        (let [fallback (:fallback-impl opts)]
                          (cond
                            (string? x)
                            ;; CLJS's ordinary quote-string expands the complete
                            ;; escaped string before IWriter sees it. Quote only a
                            ;; bounded prefix so one huge field cannot duplicate
                            ;; itself upstream of the capped writer.
                            (fallback (bounded-part x)
                                      w (dissoc opts :alt-impl))

                            (keyword? x)
                            (do (-write w ":")
                                (when-some [ns (namespace x)]
                                  (-write w (bounded-part ns))
                                  (-write w "/"))
                                (-write w (bounded-part (name x))))

                            (symbol? x)
                            (do (when-some [ns (namespace x)]
                                  (-write w (bounded-part ns))
                                  (-write w "/"))
                                (-write w (bounded-part (name x))))

                            :else
                            (fallback x w opts))))]
       (try
         (binding [*print-level* 64]
           (pr-seq-writer [value] writer
                          {:readably true
                           :meta false
                           :dup false
                           :print-length 256
                           :alt-impl alt-impl}))
         (catch :default e
           (when-not (and @truncated?
                          (identical? e bounded-print-sentinel))
             (throw e))))
       {::text (str (.join chunks "") (when @truncated? "…"))
        ::character-truncated? @truncated?})))

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

(defn bounded-pr-str-result
  "Bounded printed summary and character-cap fact for `v`.

   CLJS is work-bounded for ordinary data: a capped `IWriter`, bounded
   collection breadth/depth, and string/keyword/symbol hooks prevent huge
   scalar expansion before the cap. Arbitrary host printers are intentionally
   outside this contract and must never be used as opaque summaries. The JVM
   branch retains the historical bounded-output behavior. The returned flag
   reports only the character/writer cap; callers derive structural
   completeness from their bounded sampler."
  {:malli/schema [:=> [:catn [::value :any] [::budget ::budget]]
                  ::bounded-print-result]}
  [v budget]
  #?(:cljs (capped-pr-str v (estimate-chars budget))
     :clj  (binding [*print-length* 256 *print-level* 64]
             (let [full (pr-str v)
                   clipped (clip-str full budget)]
               {::text clipped ::character-truncated? (not= full clipped)}))))

(defn bounded-pr-str
  "Bounded printed text for `v` within token `budget`."
  {:malli/schema [:=> [:catn [::value :any] [::budget ::budget]] ::text]}
  [v budget]
  (::text (bounded-pr-str-result v budget)))
