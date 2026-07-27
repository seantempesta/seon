(ns seon.repl.parse.repair
  "Repair parser failures with best-effort delimiters and proven symbols.

   CLJC so the pod, JVM host, worker, and portable tests use ONE mechanism.
   Formatting is deliberately separate, and the caller already knows the
   form failed to read — that failure IS the repair trigger.

   ## Why indent-mode only

   Parinfer's `:paren` mode no-ops on already-broken input (it trusts the
   existing parens); `:smart` mode needs a cursor coordinate we don't
   have. `:indent` infers the intended delimiters from INDENTATION, which
   is exactly the signal an LLM's consistently-indented hiccup carries —
   live-proven to salvage both dominant failure forms in the
   `ari-2606180804` episode while preserving every map key.

   ## Honest scope

   CAN fix: missing trailing parens; an unclosed call before a `]`/`}`
   arrives; an unclosed collection; a mismatched close-delimiter TYPE
   (indent-mode swaps `]`↔`)`↔`}`); a stray extra closer.

   CANNOT reliably fix: a wrong OPENING delimiter, or misleadingly-
   indented input (indent-mode can then produce a DIFFERENT-but-valid
   structure). This is why the repair is accepted ONLY when the result
   (a) changed AND (b) re-reads cleanly via the injected `reads?` gate,
   and why `:seon.repl.parse.repair/changes` is surfaced so a wrong-but-valid repair
   stays visible to the agent."
  (:require
    [clojure.string :as str]
    [parinferish.core :as parinferish]
    [seon.schema :as schema]))

;; ============================================================
;; Public symbol-candidate mechanics
;; ============================================================

(def max-candidates
  "Candidate cap per unresolved name (k ≤ 5 — the research sweep bound)."
  5)

(defn levenshtein
  "Classic edit distance between strings `a` and `b`.

   This is the one distance function for ranked repair candidates and the
   diffusion retrieval consumer."
  {:malli/schema [:=> [:catn [::a :string] [::b :string]] :int]}
  [a b]
  (let [m (count a) n (count b)]
    (cond
      (zero? m) n
      (zero? n) m
      :else
      (loop [i 1 prev (vec (range (inc n)))]
        (if (> i m)
          (peek prev)
          (let [ca (nth a (dec i))
                cur (reduce
                      (fn [row j]
                        (let [cost (if (= ca (nth b (dec j))) 0 1)]
                          (conj row (min (inc (peek row))
                                         (inc (nth prev j))
                                         (+ cost (nth prev (dec j)))))))
                      [i] (range 1 (inc n)))]
            (recur (inc i) cur)))))))

(defn name-part
  "The name part of a possibly namespace-qualified symbol string."
  {:malli/schema [:=> [:catn [::s :string]] :string]}
  [s]
  (let [i (.lastIndexOf s "/")]
    (if (>= i 0) (subs s (inc i)) s)))

(defn ns-part
  "The namespace part of a qualified symbol string, nil when bare."
  {:malli/schema [:=> [:catn [::s :string]] [:maybe :string]]}
  [s]
  (let [i (.lastIndexOf s "/")]
    (when (pos? i) (subs s 0 i))))

(defn- sym-char?
  [c]
  (boolean (re-find #"[A-Za-z0-9*+!\-_?<>='.$%&#:/]" c)))

(defn substitute-symbol
  "Replace each word-boundary occurrence of token `from` with `to`.

   A qualified `from` only matches the complete qualified token."
  {:malli/schema [:=> [:catn [::code :string] [::from :string] [::to :string]]
                  :string]}
  [code from to]
  (let [n (count from) clen (count code)]
    (loop [i 0 out ""]
      (let [j (.indexOf code from i)]
        (if (neg? j)
          (str out (subs code i))
          (let [before (when (pos? j) (subs code (dec j) j))
                k      (+ j n)
                after  (when (< k clen) (subs code k (inc k)))]
            (if (and (or (nil? before) (not (sym-char? before)))
                     (or (nil? after) (not (sym-char? after))))
              (recur k (str out (subs code i j) to))
              (recur k (str out (subs code i k))))))))))

(defn threshold
  "The fix band for a broken name: ⌈n/3⌉ edits, floor 1."
  {:malli/schema [:=> [:catn [::from :string]] :int]}
  [from]
  (max 1 (quot (+ (count from) 2) 3)))

(defn rank-candidates
  "Ranked candidates within the fix band, nearest then shortest.

   Levenshtein distance must be positive and ≤ ⌈n/3⌉. At most five
   candidates are returned."
  {:malli/schema [:=> [:catn [::from :string] [::names [:sequential :string]]]
                  [:vector :map]]}
  [from names]
  (let [thresh (threshold from)]
    (->> names
         distinct
         (keep (fn [nm]
                 (let [d (levenshtein from nm)]
                   (when (and (pos? d) (<= d thresh))
                     {:seon.repl.parse.repair/to nm
                      :seon.repl.parse.repair/distance d}))))
         (sort-by (juxt :seon.repl.parse.repair/distance
                        (comp count :seon.repl.parse.repair/to)))
         (take max-candidates)
         vec)))

(defn nearest-tier
  "Candidates sharing the minimum distance from an ordered candidate set."
  {:malli/schema [:=> [:catn [::cands [:vector :map]]] [:vector :map]]}
  [cands]
  (if (empty? cands)
    []
    (let [min-d (:seon.repl.parse.repair/distance (first cands))]
      (vec (take-while #(= min-d (:seon.repl.parse.repair/distance %))
                       cands)))))

(defn- winner-result
  [passers over?]
  (cond
    (over?)               {:seon.repl.parse.repair/budget? true}
    (= 1 (count passers)) {:seon.repl.parse.repair/winner (first passers)}
    (seq passers)         {:seon.repl.parse.repair/ambiguous passers}
    :else                 {:seon.repl.parse.repair/none? true}))

(defn ^:async pick-winner
  "Trial only the nearest-distance tier and apply exactly one passer.

   Exactly one passing candidate wins; two or more are ambiguous and zero
   passers means no fix. Deeper tiers are never tried."
  {:malli/schema [:=> [:catn [::request :map]] :any]}
  [{:seon.repl.parse.repair/keys [cands passes? over?]}]
  (if (empty? cands)
    {:seon.repl.parse.repair/none? true}
    (let [tier (nearest-tier cands)
          passers
          #?(:clj
             (loop [cs tier acc []]
               (if (or (empty? cs) (over?))
                 acc
                 (let [c (first cs)
                       passed? (passes? c)]
                   (recur (rest cs) (if passed? (conj acc c) acc)))))
             :cljs
             (loop [cs tier acc []]
               (if (or (empty? cs) (over?))
                 acc
                 (let [c (first cs)
                       passed? (await (passes? c))]
                   (recur (rest cs) (if passed? (conj acc c) acc))))))]
      (winner-result passers over?))))

;; ============================================================
;; Schema registration (per CONVENTIONS.md). Register the leaf shapes
;; BEFORE the request/response maps that reference them (load-order
;; rule). `:seon.repl.parse.repair/*` keys are owned by THIS namespace — no other
;; group registers them.
;; ============================================================

(schema/register! :seon.repl.parse.repair/source :string)

(schema/register! :seon.repl.parse.repair/repaired? :boolean)

;; `reads?` is an injected predicate (the eval pipeline's re-parse gate),
;; cycle-free: `seon.repl.parse.repair` must not depend on the parser/eval. A `:=>`
;; value schema keeps the slot fully specced — string in, boolean out.
(schema/register! :seon.repl.parse.repair/reads?
                  [:=> [:cat :seon.repl.parse.repair/source] :boolean])

;; One change entry as parinferish reports it: a structural delimiter
;; edit (line/col/content/action/type). Surfaced so a wrong-but-valid
;; repair stays legible to the agent.
(schema/register! :seon.repl.parse.repair/change
                  [:map
                   [:line {:optional true} :int]
                   [:column {:optional true} :int]
                   [:content {:optional true} :string]
                   [:action {:optional true} :keyword]
                   [:type {:optional true} :keyword]])

(schema/register! :seon.repl.parse.repair/changes
                  [:vector :seon.repl.parse.repair/change])

(schema/register! :seon.repl.parse.repair/note :string)

;; ── Repair LEVELS + fix-class registry (form-autofix, owner rulings
;; 2026-07-05). Levels are config DATA (`:seon.config/repair` section);
;; each fix CLASS declares its minimum level HERE — the one class
;; registry. Enablement is COMPUTED from level rank + the config
;; kill-switch map, never a hand-maintained list at call sites.

(def levels
  "Ordered repair levels, weakest → strongest. `:aggressive` is an enum
   slot only — multi-fix / semantic-index candidates are NOT implemented."
  [:off :safe-syntax :symbols :aggressive])

(def class-levels
  "Minimum level per fix class — the class registry (data, ONE place).
   `:seon.repl.parse.repair/delimiters` = the shipped parinfer parse-class repair;
   `:seon.repl.parse.repair/def-vs-defn` + `:seon.repl.parse.repair/undeclared-var` = the
   compile-proven symbol tier."
  {:seon.repl.parse.repair/delimiters     :safe-syntax
   :seon.repl.parse.repair/def-vs-defn    :symbols
   :seon.repl.parse.repair/undeclared-var :symbols})

(def ^:private level-rank (zipmap levels (range)))

(schema/register! :seon.repl.parse.repair/level (into [:enum] levels))
(schema/register! :seon.repl.parse.repair/class :keyword)
(schema/register! :seon.repl.parse.repair/classes [:map-of :keyword :boolean])

(schema/register! :seon.repl.parse.repair/class-enabled-request
                  [:map
                   [:seon.repl.parse.repair/level :seon.repl.parse.repair/level]
                   [:seon.repl.parse.repair/classes {:optional true} :seon.repl.parse.repair/classes]
                   [:seon.repl.parse.repair/class :seon.repl.parse.repair/class]])

;; ── Persisted fix datoms (the A/B substrate — a projection of a real
;; repair event on the eval entity, one Datalog query for fix volume /
;; class mix / revert rate). Stamped by `seon.eval` in a separate
;; top-level tx.

(schema/register! :seon.repl.parse.repair/applied-class :keyword)
(schema/register! :seon.repl.parse.repair/from :string)
(schema/register! :seon.repl.parse.repair/to :string)

;; One in-memory fix entry (from → to, both symbol TOKENS as written).
(schema/register! :seon.repl.parse.repair/fix
                  [:map
                   [:seon.repl.parse.repair/from :seon.repl.parse.repair/from]
                   [:seon.repl.parse.repair/to :seon.repl.parse.repair/to]])

(schema/register! :seon.repl.parse.repair/fixes [:vector :seon.repl.parse.repair/fix])

(schema/register! :seon.repl.parse.repair/source-request
                  [:map
                   [:seon.repl.parse.repair/source :seon.repl.parse.repair/source]
                   [:seon.repl.parse.repair/reads? :seon.repl.parse.repair/reads?]])

(schema/register! :seon.repl.parse.repair/result
                  [:map
                   [:seon.repl.parse.repair/repaired? :seon.repl.parse.repair/repaired?]
                   [:seon.repl.parse.repair/source :seon.repl.parse.repair/source]
                   [:seon.repl.parse.repair/changes :seon.repl.parse.repair/changes]])

;; ============================================================
;; Private — diff filtering + the structural-shape note
;; ============================================================

(defn- delimiter-changes
  "Keep only the delimiter edits parinferish actually made (drop the
   `:keep`/`:text` content nodes its diff also enumerates) and dedupe —
   parinferish's diff enumerates overlapping nodes, so the same
   line/col/content/action repeats. These are the inserts/removes/swaps
   that constitute the repair."
  [diff]
  (->> diff
       (filter #(and (map? %)
                     (= :delimiter (:type %))
                     (#{:insert :remove} (:action %))))
       distinct
       vec))

;; ============================================================
;; Public — the one repair entry point
;; ============================================================

(defn repair-source
  "Best-effort delimiter repair via parinferish indent-mode.

   Pure,
   never throws.

   Request keys:
     :seon.repl.parse.repair/source  — the source string that failed to read.
     :seon.repl.parse.repair/reads?  — injected predicate `(fn [s] boolean)`: TRUE
                            iff `s` re-reads with zero read failures.
                            Cycle-free — the caller (seon.eval) supplies
                            the re-parse, so this ns never depends on
                            the parser.

   Response keys:
     :seon.repl.parse.repair/repaired? — TRUE iff the repair (a) CHANGED the source
                              AND (b) the changed source now reads.
     :seon.repl.parse.repair/source    — the repaired source on success, else the
                              ORIGINAL source unchanged.
     :seon.repl.parse.repair/changes   — the delimiter edits parinferish made
                              (empty when not repaired).

   The accept gate is deliberately conservative: a repair that did not
   change the input, or whose output still does not read, is REJECTED
   (`:repaired? false`, original source returned) so the caller falls
   through to the sharpened read error."
  {:malli/schema [:=> [:cat :seon.repl.parse.repair/source-request] :seon.repl.parse.repair/result]}
  [{:seon.repl.parse.repair/keys [source reads?]}]
  (try
    (let [parsed  (parinferish/parse source {:mode :indent})
          out     (parinferish/flatten parsed)
          changes (delimiter-changes (parinferish/diff parsed))]
      (if (and (not= out source) (reads? out))
        {:seon.repl.parse.repair/repaired? true
         :seon.repl.parse.repair/source    out
         :seon.repl.parse.repair/changes   changes}
        {:seon.repl.parse.repair/repaired? false
         :seon.repl.parse.repair/source    source
         :seon.repl.parse.repair/changes   []}))
    (catch #?(:clj Exception :cljs :default) _
      {:seon.repl.parse.repair/repaired? false
       :seon.repl.parse.repair/source    source
       :seon.repl.parse.repair/changes   []})))

(defn repair-note
  "Compose the transparency breadcrumb line for a repaired eval.

   Derived from `:seon.repl.parse.repair/changes`. Names the count + kind of
   delimiter edits and that the repaired form WAS auto-evaled, so the
   agent always sees the diff and can reject a wrong-but-valid repair.

   Leads with the `↻` glyph and carries NO `;;` prefix — the transcript
   renderer (`seon.agent.ctx/format-eval-row`) emits it as a `;; ↻ …` comment
   line in the unified stream, so a wrong-but-valid repair stays
   catchable right above the form it changed.

   `:seon.repl.parse.repair/shape` (optional) is a short structural description of
   the repaired top-level form (e.g. \"2-key map\") the caller can cheaply
   compute; when absent, the note omits the shape clause."
  {:malli/schema [:=> [:cat [:map
                            [:seon.repl.parse.repair/changes :seon.repl.parse.repair/changes]
                            [:seon.repl.parse.repair/shape {:optional true} :string]]]
                  :seon.repl.parse.repair/note]}
  [{:seon.repl.parse.repair/keys [changes shape]}]
  (let [n        (count changes)
        inserts  (count (filter #(= :insert (:action %)) changes))
        removes  (count (filter #(= :remove (:action %)) changes))
        delims   (->> changes (keep :content) distinct (str/join " "))
        action   (cond
                   (and (pos? inserts) (pos? removes)) "balanced"
                   (pos? inserts)                      "inserted"
                   (pos? removes)                      "removed"
                   :else                               "adjusted")]
    (str "↻ auto-balanced your unparseable input and evaluated the result: "
         action " " n " delimiter" (when (not= n 1) "s")
         (when (seq delims) (str " (" delims ")"))
         (when (and shape (seq shape)) (str " → " shape))
         ". Verify this is what you intended; re-eval the whole form if not.")))

(defn class-enabled?
  "Is fix class `:seon.repl.parse.repair/class` active at `:seon.repl.parse.repair/level`?

   COMPUTED: the class's [[class-levels]] minimum-level rank must be ≤
   the configured level's rank, AND the per-class kill-switch map
   (`:seon.repl.parse.repair/classes`, config data) must not disable it (absent =
   enabled — the level decides). An unknown class is never enabled."
  {:malli/schema [:=> [:cat :seon.repl.parse.repair/class-enabled-request] :boolean]}
  [{:seon.repl.parse.repair/keys [level classes class]}]
  (let [min-level (get class-levels class)]
    (boolean
      (and min-level
           (>= (get level-rank level -1) (get level-rank min-level 99))
           (get classes class true)))))

(defn fix-note
  "Compose the visible `↻ fixed:` breadcrumb for a symbol auto-fix.

   Show-don't-tell (owner ruling 2026-07-05): every applied fix renders
   original → fixed in the agent's context — no silent mutation. Rides
   the eval row's narration exactly like [[repair-note]] (the transcript
   renderer emits it as a `;` preamble line above the fixed form)."
  {:malli/schema [:=> [:cat [:map [:seon.repl.parse.repair/fixes :seon.repl.parse.repair/fixes]]]
                  :seon.repl.parse.repair/note]}
  [{:seon.repl.parse.repair/keys [fixes]}]
  (str "↻ fixed: "
       (str/join ", " (map (fn [{:seon.repl.parse.repair/keys [from to]}]
                             (str "`" from "` → `" to "`"))
                           fixes))
       " — not defined; substituted the unique compile-proven near match "
       "and evaluated the FIXED form. Re-eval if that's not what you meant."))

(defn suggestion-note
  "Compose the did-you-mean line for a REFUSED symbol fix.

   Ambiguity always refuses (owner ruling 2026-07-05): 2+ compile-proven
   candidates → name them all so the agent's fix-turn is one-shot; 0
   proven → the nearest non-passing candidates as plain did-you-mean.
   Appended to the eval error message by `seon.eval`."
  {:malli/schema [:=> [:cat [:map
                             [:seon.repl.parse.repair/from :seon.repl.parse.repair/from]
                             [:seon.repl.parse.repair/suggestions [:vector :map]]
                             [:seon.repl.parse.repair/ambiguous? {:optional true} :boolean]]]
                  :seon.repl.parse.repair/note]}
  [{:seon.repl.parse.repair/keys [from suggestions ambiguous?]}]
  (let [names (str/join ", " (map #(str "`" (:seon.repl.parse.repair/to %) "`")
                                  suggestions))]
    (if ambiguous?
      (str "↻ auto-fix refused (ambiguous): " (count suggestions)
           " near matches for `" from "` all compile — " names
           ". Pick the one you meant and re-eval.")
      (str "Did you mean " names "? (nearest matches for `" from
           "` — none compile-proven, so nothing was changed.)"))))
