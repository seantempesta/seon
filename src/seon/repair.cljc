(ns seon.repair
  "Best-effort delimiter repair for one Clojure form, via parinferish
   indent-mode. The heart of the multi-form eval path's self-correction:
   when a top-level form fails to READ (an unmatched/missing delimiter),
   we attempt an indent-mode repair, re-validate that the repaired text
   now reads cleanly, and — only then — hand the repaired source back so
   the eval pipeline can auto-eval it.

   CLJC so the pod (`seon.eval`) and JVM tests use ONE mechanism. The
   JVM-only `seon.dev.repair` is the pattern this mirrors; we drop its
   cljfmt step (the value here is repair, not formatting) and its
   edamame `delimiter-error?` probe (the caller already knows the form
   failed to read — that IS the trigger).

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
   and why `:seon.repair/changes` is surfaced so a wrong-but-valid repair
   stays visible to the agent."
  (:require
    [clojure.string :as str]
    [parinferish.core :as parinferish]
    [seon.schema :as schema]))

;; ============================================================
;; Schema registration (per CONVENTIONS.md). Register the leaf shapes
;; BEFORE the request/response maps that reference them (load-order
;; rule). `:seon.repair/*` keys are owned by THIS namespace — no other
;; group registers them.
;; ============================================================

(schema/register! :seon.repair/source :string)

(schema/register! :seon.repair/repaired? :boolean)

;; `reads?` is an injected predicate (the eval pipeline's re-parse gate),
;; cycle-free: `seon.repair` must not depend on the parser/eval. A `:=>`
;; value schema keeps the slot fully specced — string in, boolean out.
(schema/register! :seon.repair/reads?
                  [:=> [:cat :seon.repair/source] :boolean])

;; One change entry as parinferish reports it: a structural delimiter
;; edit (line/col/content/action/type). Surfaced so a wrong-but-valid
;; repair stays legible to the agent.
(schema/register! :seon.repair/change
                  [:map
                   [:line {:optional true} :int]
                   [:column {:optional true} :int]
                   [:content {:optional true} :string]
                   [:action {:optional true} :keyword]
                   [:type {:optional true} :keyword]])

(schema/register! :seon.repair/changes
                  [:vector :seon.repair/change])

(schema/register! :seon.repair/note :string)

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
   `:seon.repair/delimiters` = the shipped parinfer parse-class repair;
   `:seon.repair/def-vs-defn` + `:seon.repair/undeclared-var` = the
   compile-proven symbol tier."
  {:seon.repair/delimiters     :safe-syntax
   :seon.repair/def-vs-defn    :symbols
   :seon.repair/undeclared-var :symbols})

(def ^:private level-rank (zipmap levels (range)))

(schema/register! :seon.repair/level (into [:enum] levels))
(schema/register! :seon.repair/class :keyword)
(schema/register! :seon.repair/classes [:map-of :keyword :boolean])

(schema/register! :seon.repair/class-enabled-request
                  [:map
                   [:seon.repair/level :seon.repair/level]
                   [:seon.repair/classes {:optional true} :seon.repair/classes]
                   [:seon.repair/class :seon.repair/class]])

;; ── Persisted fix datoms (the A/B substrate — a projection of a real
;; repair event on the eval entity, one Datalog query for fix volume /
;; class mix / revert rate). Stamped by `seon.eval` in a separate
;; top-level tx (the `:seon.eval/record-error` precedent).

(schema/register! :seon.repair/applied-class :keyword)
(schema/register! :seon.repair/from :string)
(schema/register! :seon.repair/to :string)

;; One in-memory fix entry (from → to, both symbol TOKENS as written).
(schema/register! :seon.repair/fix
                  [:map
                   [:seon.repair/from :seon.repair/from]
                   [:seon.repair/to :seon.repair/to]])

(schema/register! :seon.repair/fixes [:vector :seon.repair/fix])

(schema/register! :seon.repair/source-request
                  [:map
                   [:seon.repair/source :seon.repair/source]
                   [:seon.repair/reads? :seon.repair/reads?]])

(schema/register! :seon.repair/result
                  [:map
                   [:seon.repair/repaired? :seon.repair/repaired?]
                   [:seon.repair/source :seon.repair/source]
                   [:seon.repair/changes :seon.repair/changes]])

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
     :seon.repair/source  — the source string that failed to read.
     :seon.repair/reads?  — injected predicate `(fn [s] boolean)`: TRUE
                            iff `s` re-reads with zero read failures.
                            Cycle-free — the caller (seon.eval) supplies
                            the re-parse, so this ns never depends on
                            the parser.

   Response keys:
     :seon.repair/repaired? — TRUE iff the repair (a) CHANGED the source
                              AND (b) the changed source now reads.
     :seon.repair/source    — the repaired source on success, else the
                              ORIGINAL source unchanged.
     :seon.repair/changes   — the delimiter edits parinferish made
                              (empty when not repaired).

   The accept gate is deliberately conservative: a repair that did not
   change the input, or whose output still does not read, is REJECTED
   (`:repaired? false`, original source returned) so the caller falls
   through to the sharpened read error."
  {:malli/schema [:=> [:cat :seon.repair/source-request] :seon.repair/result]}
  [{:seon.repair/keys [source reads?]}]
  (try
    (let [parsed  (parinferish/parse source {:mode :indent})
          out     (parinferish/flatten parsed)
          changes (delimiter-changes (parinferish/diff parsed))]
      (if (and (not= out source) (reads? out))
        {:seon.repair/repaired? true
         :seon.repair/source    out
         :seon.repair/changes   changes}
        {:seon.repair/repaired? false
         :seon.repair/source    source
         :seon.repair/changes   []}))
    (catch #?(:clj Exception :cljs :default) _
      {:seon.repair/repaired? false
       :seon.repair/source    source
       :seon.repair/changes   []})))

(defn repair-note
  "Compose the transparency breadcrumb line for a repaired eval.

   Derived from `:seon.repair/changes`. Names the count + kind of
   delimiter edits and that the repaired form WAS auto-evaled, so the
   agent always sees the diff and can reject a wrong-but-valid repair.

   Leads with the `↻` glyph and carries NO `;;` prefix — the transcript
   renderer (`seon.agent.ctx/format-eval-row`) emits it as a `;; ↻ …` comment
   line in the unified stream, so a wrong-but-valid repair stays
   catchable right above the form it changed.

   `:seon.repair/shape` (optional) is a short structural description of
   the repaired top-level form (e.g. \"2-key map\") the caller can cheaply
   compute; when absent, the note omits the shape clause."
  {:malli/schema [:=> [:cat [:map
                            [:seon.repair/changes :seon.repair/changes]
                            [:seon.repair/shape {:optional true} :string]]]
                  :seon.repair/note]}
  [{:seon.repair/keys [changes shape]}]
  (let [n        (count changes)
        inserts  (count (filter #(= :insert (:action %)) changes))
        removes  (count (filter #(= :remove (:action %)) changes))
        delims   (->> changes (keep :content) distinct (str/join " "))
        verb     (cond
                   (and (pos? inserts) (pos? removes)) "balanced"
                   (pos? inserts)                      "inserted"
                   (pos? removes)                      "removed"
                   :else                               "adjusted")]
    (str "↻ auto-balanced your unparseable input and evaluated the result: "
         verb " " n " delimiter" (when (not= n 1) "s")
         (when (seq delims) (str " (" delims ")"))
         (when (and shape (seq shape)) (str " → " shape))
         ". Verify this is what you intended; re-eval the whole form if not.")))

(defn class-enabled?
  "Is fix class `:seon.repair/class` active at `:seon.repair/level`?

   COMPUTED: the class's [[class-levels]] minimum-level rank must be ≤
   the configured level's rank, AND the per-class kill-switch map
   (`:seon.repair/classes`, config data) must not disable it (absent =
   enabled — the level decides). An unknown class is never enabled."
  {:malli/schema [:=> [:cat :seon.repair/class-enabled-request] :boolean]}
  [{:seon.repair/keys [level classes class]}]
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
  {:malli/schema [:=> [:cat [:map [:seon.repair/fixes :seon.repair/fixes]]]
                  :seon.repair/note]}
  [{:seon.repair/keys [fixes]}]
  (str "↻ fixed: "
       (str/join ", " (map (fn [{:seon.repair/keys [from to]}]
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
                             [:seon.repair/from :seon.repair/from]
                             [:seon.repair/suggestions [:vector :map]]
                             [:seon.repair/ambiguous? {:optional true} :boolean]]]
                  :seon.repair/note]}
  [{:seon.repair/keys [from suggestions ambiguous?]}]
  (let [names (str/join ", " (map #(str "`" (:seon.repair/to %) "`")
                                  suggestions))]
    (if ambiguous?
      (str "↻ auto-fix refused (ambiguous): " (count suggestions)
           " near matches for `" from "` all compile — " names
           ". Pick the one you meant and re-eval.")
      (str "Did you mean " names "? (nearest matches for `" from
           "` — none compile-proven, so nothing was changed.)"))))
