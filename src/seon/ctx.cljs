(ns seon.ctx
  "Context generation — the v4 composer (context-v4-repl-realism
   2026-06-11): the prompt IS a REPL session. One static `<system>`
   header, ALL the loaded namespaces as the body (`<namespace>` tags,
   recency-ordered), the agent's own entity as a map, what the human
   currently sees, the reactive warnings/todos, the threaded
   transcript of PAST turns (each `<turn>` carries its woken-by `<user>`
   + REPL-faithful evals), and a status line +
   clean REPL prompt. Layout is ordered top→bottom = static→volatile:
   everything above `<warnings>` is the provider-cacheable prefix.

   This namespace owns:
     - the `:seon.ctx/*` section schemas (`:seon.ctx/name`,
       `:seon.ctx/priority`, the `:seon.ctx/section` map shape). The
       one slot attr is `:seon.render/ai` (string = verbatim doctrine,
       symbol = late-bound section fn); `:seon.render/html` is the
       optional debug-view twin (§2.8b).
     - `assemble-context` — the ONE composer. Core default
       sections MERGED with the agent's own `:seon.agent/ctx` sections
       by one priority sort (override-by-name). Render guard (a broken
       section renders an inline error line, never breaks assembly)
       and the per-agent section char budget live here.
     - the selection rules: [[included-ns?]] (the ONE structural rule —
       EVERY indexed `:seon.ns` row renders EXCEPT *.internal and *-test
       ones; no prefix allow-list, the library gate lives on the INDEX
       side — `seon.indexing/first-party-file?`) and [[full-source-ns?]]
       (which rows the boot indexer inlines real file text for).
     - the `:system` section (system-text / system-section — kept here
       as a byte-stable shared artifact) and the derived read API every
       section shares (messages / evals / session-evals / current-ns /
       turns-since-inbound / format-eval-row / the eval-render caps /
       …) — every read takes the composer's `:seon.db/db` snapshot so
       one render is one db view. The OTHER core sections now live in
       their own `seon.ctx.<name>` nses (ctx-sections-split-2026-06-18):
       :namespaces → `seon.ctx.namespaces`, :your-entity →
       `seon.ctx.your-entity`, :live-tile → `seon.ctx.live-tile`,
       :warnings → `seon.ctx.warnings`, :transcript →
       `seon.ctx.transcript`, :inventory → `seon.ctx.inventory`,
       :prompt → `seon.ctx.prompt`; `core-default-ctx` wires them by
       SYMBOL (late lookup-value resolution), so this ns does NOT
       require them — they require this ns for the shared read API.
     - `render-namespace` — the standalone whole-namespace render
       (ns + fns + schemas + tests, :ai or :html), an agent-callable
       core capability the `<system>` prompt documents by name; kept
       here (not in `seon.ctx.namespaces`) so `seon.ctx/render-namespace`
       stays the stable entry point.

   Section fns receive ONE map:
     {:seon.db/db        <db value>
      :seon.agent/id     <id string>          ; convenience, = entity id
      :seon.agent/entity <the agent's own entity, pulled ONCE>
      :seon.ctx/section  <this section's map>} ; per-section overrides
   and return a string; \"\" suppresses the section.

   seon.agent requires this ns and re-exports the agent-taught read
   API (seon.agent/messages …) as transitional aliases; the P6
   agent.cljs split finishes the relocation."
  (:require
    [clojure.string :as str]
    [cljs.reader :as edn]
    [seon.db :as db]
    [seon.error.instrument :as einstrument]
    [seon.eval :as seval]
    [seon.handlers.fn :as h-fn]
    [seon.handlers.ns :as h-ns]
    [seon.handlers.schema :as h-schema]
    [seon.handlers.test :as h-test]
    [seon.render :as render]
    [seon.schema :as schema]
    [seon.warn :as warn]))

;; ============================================================
;; Section schemas. A section is a plain map — the SAME shape whether
;; it lives in code (core defaults) or as a component entity on
;; the agent's :seon.agent/ctx vector.
;; ============================================================

(declare decode-section core-default-ctx)

;; Program-graph ns rows — registered HERE (not seon.agent) because
;; this ns loads first and its render-namespace schemas reference
;; :seon.ns/name. The rest of the :seon.fn/:seon.schema attr family
;; stays in seon.agent until the P6 split finds them a real home.
(schema/register! :seon.ns/name    [:keyword {:seon.db/identity true}])
(schema/register! :seon.ns/source  :string)
;; The dependency-edge SET for a namespace: the required ns-NAMES as
;; keywords (aliases dropped — those are reconstituted from
;; :seon.ns/source's ns form in Phase 2). Captured + diff-upserted at
;; the tee from the analyzer (seon.analyzer-info/ns-requires). A
;; `[:vector :keyword]` maps to :db.cardinality/many keyword via the
;; bridge (db/internal form->cardinality) — INTENDED: a queryable set
;; of dep edges, so the load can topo-sort by requires (the one fix
;; that unblocks DB-layer load — see db-is-the-running-system PRD).
;; Cardinality-many means a plain upsert ACCUMULATES; the tee writes a
;; diff (additions + explicit retractions) so the stored set EXACTLY
;; matches the analyzer's current requires.
(schema/register! :seon.ns/requires [:vector :keyword])

(schema/register! :seon.ctx/name     :keyword)
(schema/register! :seon.ctx/priority :int)

;; The section map contract (validated at seon.agent/add-section! AND
;; at transact! like everything else). :seon.render/ai is the ONE slot:
;; a string renders verbatim (doctrine — content as source); a
;; qualified symbol resolves LATE via seon.eval/lookup-value at every
;; render. Optional :seon.render/html twin (symbol or hiccup literal).
(schema/register! :seon.ctx/section
  [:map
   [:seon.ctx/name     :seon.ctx/name]
   [:seon.ctx/priority :seon.ctx/priority]
   [:seon.render/ai    :seon.render/ai]
   [:seon.render/html  {:optional true} :seon.render/html]])

(def default-turns-cap 20)

(defn turns-cap
  "Read `:seon.agent/turns-cap` from the agent entity. Returns the
   configured cap or `default-turns-cap` when the attr is absent.
   Use this at every cap-check site so the agent can override the
   default by transacting its own value. Optional `db` value (the
   composer's snapshot); defaults to the live conn."
  ([agent-id] (turns-cap agent-id nil))
  ([agent-id db]
   (or (:seon.agent/turns-cap
         (db/entity-lazy (cond-> {:seon.db/ref [:seon.agent/id agent-id]}
                           db (assoc :seon.db/db db))))
       default-turns-cap)))

(defn home-ns
  "Return the deterministic home-ns symbol for an agent id.
   `(home-ns \"seon\") => 'my.agent.seon`."
  [agent-id]
  (symbol (str "my.agent." agent-id)))

(defn current-session
  "Most-recent `:seon.agent.session` entity for `agent-id`. Returns nil if
   the agent has no sessions yet (fresh boot before `start-session!`).
   Optional `db` value (the composer's snapshot) — the run-3 bug class
   fix: section fns must read the SAME db value the composer renders
   from, never reach back to the live conn mid-render."
  ([agent-id] (current-session agent-id nil))
  ([agent-id db]
   (let [a (db/entity-lazy (cond-> {:seon.db/ref [:seon.agent/id agent-id]}
                             db (assoc :seon.db/db db)))]
     (last (sort-by :seon.agent.session/at (:seon.agent/sessions a))))))

;; ============================================================
;; The classifier — ONE pass over the full index; every section
;; consumes the resulting model, none re-classifies. Replaces the
;; six scattered name filters (core-ns-name?, exemplar-ns?
;; duplication, the warn internal-attr-ns? regex, …).
;; ============================================================

(defn hidden-ns-name?
  "Rule 1: a `*.internal` namespace (or any of its children) is
   indexed but NEVER rendered — the V3-A naming convention IS the
   filter. String/keyword/symbol tolerant."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s (if (keyword? ns-name) (name ns-name) (str ns-name))]
    (boolean (or (str/ends-with? s ".internal")
                 (str/includes? s ".internal.")))))

(defn my-ns-name?
  "Rule 2: `my.*` is the human's world — always shown, provenance not
   consulted (one name rule, no special cases)."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s (if (keyword? ns-name) (name ns-name) (str ns-name))]
    (boolean (or (= s "my") (str/starts-with? s "my.")))))

(defn test-ns-name?
  "Rule 1b: a `*-test` namespace is indexed but NEVER rendered into the
   agent prompt — its `deftest`s are noise to the working agent, and the
   per-fn `:test` usage example already rides the regular fn's attr-map in
   the compact head. Full tests stay reachable on demand via
   [[render-namespace]]. STRUCTURAL, like [[hidden-ns-name?]]: the suffix
   IS the filter. String/keyword/symbol tolerant."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s (if (keyword? ns-name) (name ns-name) (str ns-name))]
    (str/ends-with? s "-test")))

(defn included-ns?
  "The ONE selection rule for the `<namespace>` tags: EVERY indexed
   :seon.ns row renders EXCEPT *.internal (hidden-ns-name?) and *-test
   (test-ns-name?) ones — both STRUCTURAL naming conventions that apply
   to seon, my.*, and downstream code alike. No prefix allow-list: the
   library gate lives on the INDEX side (only first-party + SEON_EXTRA_SRC
   code ever gets a :seon.ns row — seon.indexing/first-party-file?)."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s (if (keyword? ns-name) (name ns-name) (str ns-name))]
    (boolean (and (not (hidden-ns-name? s))
                  (not (test-ns-name? s))))))

(defn- base-ns-name
  "The ns name with a trailing `-test` stripped — the SUBJECT ns a test
   sibling pairs with (`seon.agent.search-test` → `seon.agent.search`).
   Non-test names pass through unchanged."
  [ns-str]
  (if (str/ends-with? ns-str "-test")
    (subs ns-str 0 (- (count ns-str) 5))
    ns-str))

(def exemplar-nses
  "The CURATED exemplar set — the few seon/`my.*` namespaces shown to
   every agent IN FULL as worked patterns to imitate (curated-inventory
   2026-06-21). Each teaches one thing:
     - `:seon.agent.todo` — the store/retrieve EXEMPLAR: `register!` per
       attr, three map-in/map-out `:malli/schema` fn shapes, error-as-value
       envelopes.
     - `:my.kb`           — the schema/provenance design (shared `:my.kb/*`
       shapes, register-once).
     - `:my.kb-test`      — the `deftest` idiom (fresh `:memory` conn, async).
   A `*-test` exemplar is the ONE place the *-test render-exclusion is
   OVERRIDDEN ([[exemplar-ns?]] beats [[test-ns-name?]]). Shared by the
   boot indexer (which stores their real file source — see
   `seon.client/ns-row`) and [[seon.ctx.namespaces/namespaces-section]]
   (which renders them FULL while the rest of the framework is a manifest)."
  #{:seon.agent.todo :my.kb :my.kb-test})

(defn exemplar-ns?
  "True when `ns-name` (string, keyword, or symbol) is one of the curated
   [[exemplar-nses]]. String/keyword/symbol tolerant — the indexer hands a
   string, the renderer a keyword."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (contains? exemplar-nses
             (if (keyword? ns-name) ns-name (keyword (str ns-name)))))

(defn full-source-ns?
  "True when `ns-name` (string, symbol, or ns-name keyword) carries its
   REAL FULL FILE TEXT as `:seon.ns/source`: every `my.*` ns (the
   human's world — always inlined), including `-test` siblings (the
   `-test` suffix is stripped to the subject ns first), AND every curated
   [[exemplar-ns?]] (so a seon-framework exemplar like `:seon.agent.todo`
   gets its REAL body stored, not a reconstructed-from-members stub that
   would drop private helpers/comments). Used by the boot indexer
   (`seon.client/ns-row`) to decide which rows get the file read;
   [[seon.ctx.namespaces/namespaces-section]] renders whatever depth the
   row has — one rule, one writer, no drift. Every other ns gets the
   minimal `(ns x)` stub at boot and is named in the manifest."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s    (if (keyword? ns-name) (name ns-name) (str ns-name))
        base (base-ns-name s)]
    (boolean (and (not (hidden-ns-name? s))
                  (or (my-ns-name? base)
                      (exemplar-ns? s))))))

;; ------------------------------------------------------------
;; Pretty-print + truncation helpers.
;; ------------------------------------------------------------

(defn host-timezone
  "IANA tz string for the running pod, or 'UTC' if Intl is unavailable."
  []
  (try
    (or (some-> (js/Intl.DateTimeFormat.) .resolvedOptions .-timeZone) "UTC")
    (catch :default _ "UTC")))

(defn truncate-edn
  "pr-str a value, truncate to ~2 KB for display in the eval log
   (v1.md §1's three-tier storage rule: DB datoms hold projections,
   not full content)."
  ([v] (truncate-edn v 2048))
  ([v limit]
   (let [s (pr-str v)
         n (count s)]
     (if (> n limit)
       (str (subs s 0 limit)
            " …⟨⚠ TRUNCATED at " limit " of " n " chars — display clip, "
            "the underlying value is complete⟩")
       s))))

(defn message-label
  "Transcript label for a message's `:seon.agent.message/from` ref (a pulled
   map carrying `:seon.user/id` / `:seon.agent/id`), resolved by REF
   KIND: the user → `user`, this agent itself → `assistant`, any other
   agent → `agent-<id>`."
  [from own-id]
  (cond
    (:seon.user/id from)             "user"
    (= own-id (:seon.agent/id from)) "assistant"
    (:seon.agent/id from)            (str "agent-" (:seon.agent/id from))
    :else                            "unknown"))

(defn- read-error-envelope
  "Best-effort EDN decode of a `:seon.eval/error-data` instrument-envelope
   string. Returns the envelope map, or nil when blank/unreadable. Never
   throws. (The plain `:seon.eval/error` string is now stored pre-rendered
   and legible by `seon.eval/render-error-string`, so it is NOT decoded
   here — `format-eval-row` surfaces it as-is.)"
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (try (edn/read-string s)
         (catch :default _ nil))))

(def eval-render-cap
  "Per-eval rendered-result char cap for the transcript context section.
   Context-SAFETY invariant: no single eval's result may dominate the
   agent's whole context. One 9.7M-char `pull` result used to blow
   render-prompt to ~9.8M chars; capping each rendered result here keeps
   `transcript-section` bounded regardless of how large any individual
   `:seon.eval/result-edn` blob is."
  1500)

(def result-body-render-cap
  "Per-eval render cap for the CITABLE RESULT BODY — the `=> <value>`
   line every successful eval renders (`cap-result-body`). The result
   body alone gets this LARGER cap (vs `eval-render-cap` for echoed
   source + stdout) because it is the one component that (a) is the #53
   symptom — a stored ≤16384 value clipped mid-value at 1500 drove
   needless re-queries; (b) carries a `result/<id>` escape, so an
   over-cap body still points the agent at the whole live value; and
   (c) is already row-capped at 50 elements upstream
   (`seon.eval/render-result-edn`), so a 16384-char body is STRUCTURED,
   not a wall of text.

   16384 currently EQUALS `seon.eval/store-edn-cap`, so a stored result
   renders WHOLE — but this is a CROSS-REFERENCE, NOT an alias. The
   render cap (an LLM-facing read-time projection) and `store-edn-cap`
   (the write-time per-datom anti-OOM RAM ceiling) are different
   three-tier-storage tiers: this one is independently tunable down for
   token economy WITHOUT moving the RAM ceiling, or store-edn-cap up
   without enlarging the LLM-facing render. Echoed source (`form-ln`)
   and captured stdout (`out-ln`) stay at the smaller [[eval-render-cap]]
   — neither is dereferenceable via `result/<id>`, so a large one is
   context-wasting noise that would crowd the 24000 transcript budget."
  16384)

(defn cap-result
  "Truncate a rendered eval-result string to `eval-render-cap`,
   appending a LOUD truncation marker (shown of full chars) so a
   clipped display can never pass for complete content — the observed
   failure mode is an agent summarizing INVENTED content from a
   silently-clipped render. Operates on the ALREADY-stringified result
   (`:seon.eval/result-edn` is a pr-str string), so no re-quoting.
   Nil-safe."
  ([s] (cap-result s eval-render-cap))
  ([s limit]
   (let [s (str s)
         n (count s)]
     (if (> n limit)
       (str (subs s 0 limit)
            " …⟨⚠ TRUNCATED at " limit " of " n " chars — the DISPLAY is "
            "clipped, the underlying data is complete; do not summarize "
            "or quote beyond what is shown⟩")
       s))))

(def message-render-cap
  "Per-message rendered-content char cap for a `<user>`/`<from>` line in
   the threaded transcript: each woken-by message must be individually
   bounded or a single pasted blob could blow the context. 4000 (≈1k
   tokens) keeps any realistic chat turn whole; the full content stays in
   the db ((seon.agent/messages))."
  4000)

(defn cap-result-body
  "Like `cap-result`, but for an eval RESULT body specifically: when the
   value is clipped by size, append a GUIDING clip message that teaches
   the agent how to get less/narrower output, instead of a bare elision
   marker. A clip is feedback, not a failure (errors are values the agent
   reads).

   Only the SIZE clip (a huge scalar/string that overflows the display
   cap) gets this guide. Large COLLECTIONS are already bounded upstream
   with their own row-count guide in `:seon.eval/result-edn`
   (`seon.eval/render-result-edn`), so their preview fits under the cap
   and no second guide fires here — no double-noising.

   The full value is always available as the live var `result/<id>`
   (transcript-redesign-2026-06-18 — the agent references the var
   directly, no `(result …)` call); the clip is display-only.

   The default cap is [[result-body-render-cap]] (16384, = the store
   ceiling) so a stored result renders WHOLE — the result body is the
   one citable, row-capped, `result/<id>`-dereferenceable component, so
   it earns the larger cap (echoed source + stdout stay at the smaller
   [[eval-render-cap]])."
  ([s] (cap-result-body s result-body-render-cap nil))
  ([s limit] (cap-result-body s limit nil))
  ([s limit eid]
   (let [s (str s)
         n (count s)]
     (if (> n limit)
       (let [ref (if eid (str "result/" eid) "result/<id>")]
         (str (subs s 0 limit)
              " …⟨⚠ TRUNCATED at " limit " of " n " chars — the DISPLAY "
              "is clipped, the live value is COMPLETE⟩"
              "\n;; Never summarize or quote beyond the shown " limit
              " chars — bind and process the value with code: " ref
              " holds it whole; (count " ref "), subs, get-in/filter, or "
              "paged take/drop. To get less next time: a :find aggregate, "
              "a tighter :where, or pull fewer attrs."))
       s))))

(def unverified-narration-marker
  "What a model-authored result-claim comment is rewritten to in the
   transcript. Deliberately does NOT match [[result-claim-re]] itself
   (no `=>`/`⇒` after the semicolons), so the rewrite is idempotent."
  ";; [unverified narration — not a real result]")

(def ^:private result-claim-re
  "A comment posing as a REPL result read: one-or-more `;`, optional
   whitespace, then `=>` or `⇒` — `;; =>`, `;; ⇒`, `; =>`, `;⇒`,
   `;;=>` all match — through to end of line. Shape-match, line-
   position preserving: applies anywhere in a line, so inline claims
   (`(+ 1 2) ;; => 3`) lose only the comment, never the code.
   `[^\\n]*` instead of `(?m)…$` because CLJS `str/replace` rebuilds
   the RegExp with only the `g` flag, dropping `m`."
  #";+[ \t]*(?:=>|⇒)[^\n]*")

(def ^:private bare-result-claim-re
  "The DOMINANT fabrication shape — a BARE result line with NO leading
   `;`: a line whose first non-space char is `=>` or `⇒`, e.g.
   `=> #{...} ;; result/OKf` or `=> 61 ;; result/LFd`. This is how weak
   models continue the transcript's `(form)` → `=> value ;; result/<id>`
   adjacency, fabricating the value and the handle (6 captured response
   files carried this shape vs 1 the commented `;; =>` shape).
   `[[result-claim-re]] requires a leading `;` and is BLIND to it.

   ANCHORED TO COLUMN 0 (`^` + optional indent): this is LOAD-BEARING.
   It must NOT clobber `:=>` inside :malli/schema vectors
   (`{:malli/schema [:=> [:cat …] …]}`) — those appear mid-line, always
   preceded by `[`, never as `=>` at the start of a line. The `(?m)` flag
   survives `str/replace` in this CLJS runtime (verified), and `[^\\n]*`
   bounds each match to a single line so the anchor is what selects the
   line, not the trailer."
  #"(?m)^[ \t]*(?:=>|⇒)[^\n]*")

(defn neutralize-result-claims
  "Rewrite every model-authored result-claim in `s` to
   [[unverified-narration-marker]], dropping the claimed value
   entirely (the claimed value is the poison: a later turn reads
   `;; => {...}` or a bare `=> 61` as a real result and trusts data that
   was never computed — two live fabrication incidents, F13/F14, plus the
   bare-`=>` headline case in gwM-2606211132).

   TWO shapes: the bare line ([[bare-result-claim-re]], `=> value` at
   column 0 — the DOMINANT fabrication) is rewritten FIRST, then the
   commented line ([[result-claim-re]], `;; =>`/inline `;; => 3`). The
   marker carries no `=>`/`⇒` so neither regex re-matches it — idempotent.

   PROVENANCE GATE, not regex luck: this runs ONLY on the
   model-authored transcript channels — `:seon.eval/narration` and
   `:seon.eval/source` — BEFORE [[format-eval-row]] composes the row.
   Real result lines (`=> <value> ;; result/<id>`) are appended by the
   composer itself AFTER this rewrite and never pass through it, so the
   bare-`=>` rule can never touch a genuine runtime-written result line."
  {:malli/schema [:=> [:cat :string] :string]}
  [s]
  (-> s
      (str/replace bare-result-claim-re unverified-narration-marker)
      (str/replace result-claim-re unverified-narration-marker)))

(defn- strip-comment-prefix
  "Strip any leading `;`/`⚠`/`↻`/`=>` comment markers + whitespace from
   one line, returning the bare text. Lets the renderer re-prefix a line
   idempotently whether the stored text already carried a `;;` (a real
   comment, the scratch-def note, the repair `↻` breadcrumb, a
   `render-malli-error` `;;` line) or not (raw bare prose). The `↻`/`⚠`
   glyphs and the `=>`/`⇒` arrows are re-emitted by the caller as the
   line's role demands."
  [line]
  (-> (str line)
      (str/replace #"^[\s;]*(?:[↻⚠]\s*)?" "")
      str/trimr))

(defn- comment-lines
  "Render multi-line comment-preamble text `s` as literal `;;` lines —
   one `;; <text>` per non-blank source line, leading comment markers
   normalized away first (idempotent re-prefix). Returns a seq of
   `;;`-prefixed strings (empty when `s` is blank). The repair `↻`
   breadcrumb keeps its glyph: a line whose stripped text starts with a
   non-`;;` marker is handled by the caller; here we re-add only `;;`."
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (->> (str/split-lines s)
         (map str/trim)
         (remove str/blank?)
         (map (fn [line]
                ;; The repair breadcrumb (`↻`) and the demoted-data-literal
                ;; warning (`⚠`, seon.repl.internal/demoted-literal-warning)
                ;; are stored with a leading glyph; keep it so the
                ;; breadcrumb / warning stays visible. Everything else
                ;; becomes a plain `;;` line.
                (if (or (str/starts-with? line "↻")
                        (str/starts-with? line "⚠"))
                  (str ";; " line)
                  (str ";; " (strip-comment-prefix line))))))))

(defn- error-lines
  "Render an error/guidance body `s` as the REPL FAILURE shape: the FIRST
   non-blank line becomes the `=> ✗ <headline>` output line (visually
   DISTINCT from `;;` comments — it is REPL output, not narration), every
   CONTINUATION line a plain `;;` comment (so a read-error's source slice
   + `^` caret stay ALIGNED — only a leading `;`/`⚠`/`ERROR`/`✗` marker is
   stripped, never the interior indentation the caret depends on). One
   crystal-clear guidance block, never a stack trace. Returns a single
   newline-joined string (\"\" when blank)."
  [s]
  (let [strip-marker
        (fn [line]
          ;; Drop ONLY a leading comment marker / legacy `ERROR` keyword /
          ;; prior `⚠`/`✗`, plus the single following space — KEEP any
          ;; deeper indentation (the caret + source-slice lines rely on it).
          (-> (str line)
              (str/replace #"^\s*;+[ \t]?" "")
              (str/replace #"(?i)^ERROR[ \t]?" "")
              (str/replace #"^[⚠✗][ \t]?" "")))
        lines (->> (str/split-lines (str s))
                   (drop-while str/blank?))]
    (if (empty? lines)
      ""
      (str/join "\n"
        (cons (str "=> ✗ " (strip-marker (first lines)))
              (map #(str ";; " (strip-marker %)) (rest lines)))))))

(defn format-eval-row
  "REPL-faithful render of one eval (transcript-redesign-2026-06-18):
   the form's comment-preamble as literal `;;` lines, the form verbatim
   (or the parinfer-repaired source), captured print output, then the
   value as a `=> <value>` output line trailing ` ;; result/<id>` (or
   the error as a `=> ✗ <guidance>` line). NO `<eval>` tag and NO
   `<ns>=>` history prompt prefix — the live `<your-ns>=>` cursor lives
   once at the very END of the context; each row reads as plain
   comments + form + REPL output, the exact shape the system prompt
   teaches.

     ;; add 1 and 2
     (+ 1 2)
     => 3 ;; result/EVLabc-123

   The `=>` line is visually DISTINCT from the `;;` comments (it starts
   with `=>`, not semicolons) — fixing the agents-confusing-output-for-
   comments bug (ari-2606180804). The trailing ` ;; result/<id>` is the
   LIVE VAR HANDLE: the agent references `result/<id>` directly to reuse
   the value. PRIOR-SESSION evals (`prior?` true) render the value
   WITHOUT the handle (their vars died with the restart; the resume
   boundary marker says so once). A clipped value appends `(N of M)` to
   the handle so the agent knows the display is a partial view.

   FAILURES render `=> ✗ <crystal-clear guidance>` (never a stack trace,
   never a `;; result/<id>` — there is no value to reuse): the
   pre-rendered legible `:seon.eval/error` string (read/compile/runtime —
   crystal-clear at the source) or a Malli instrumentation envelope via
   `render-malli-error`. A COMMENT-ONLY row (blank source — trailing
   `;;` lines / bare prose the agent typed with no following form)
   renders just its `;;` preamble, no form, no output.

   Render caps SPLIT BY COMPONENT (context-SAFETY invariant — agent
   code can return literally anything): echoed source (`form-ln`) and
   captured stdout (`out-ln`) cap at the smaller [[eval-render-cap]]
   (1500 — neither is dereferenceable via `result/<id>`, so a large one
   is just context-wasting noise), while the CITABLE RESULT BODY caps at
   the larger [[result-body-render-cap]] (16384 = the store ceiling, so
   a stored result renders WHOLE — it is the one row-capped,
   `result/<id>`-dereferenceable component). Error/guidance bodies stay
   at [[eval-render-cap]] (a failure has no value to cite).

   On SUCCESS a reactive 'won't persist' note (#7) is DERIVED from the
   eval's source via [[seon.eval/scratch-def-note]] and appended as a
   trailing `;;` line — pure, no stored attr, recomputed each render so
   it FOLLOWS the form. The repair `↻ auto-balanced …` breadcrumb (when
   a span was parinfer-repaired) rides in the preamble, keeping a
   wrong-but-valid repair catchable."
  ([row] (format-eval-row row false))
  ([{src        :seon.eval/source
     ok?        :seon.eval/ok?
     res        :seon.eval/result-edn
     out        :seon.eval/output
     err        :seon.eval/error
     err-data   :seon.eval/error-data
     eid        :seon.eval/id
     narr       :seon.eval/narration}
    prior?]
   (let [envelope    (read-error-envelope err-data)
         ;; Echoed source + stdout + error/guidance bodies cap at the
         ;; smaller `eval-render-cap` (1500); only the citable result
         ;; body below gets `result-body-render-cap` (16384).
         limit       eval-render-cap
         comment-only? (str/blank? (str src))
         ;; Comment-preamble — the agent's `;;`/prose thinking, neutralized
         ;; against fabricated result-claims BEFORE we re-prefix.
         preamble    (comment-lines
                       (when (and narr (not (str/blank? narr)))
                         (neutralize-result-claims narr)))
         ;; The form, verbatim (or repaired) — neutralized for any inline
         ;; result-claim, capped. Omitted for a comment-only row.
         form-ln     (when-not comment-only?
                       (cap-result (neutralize-result-claims src) limit))
         ;; Captured println/prn output — shown above the value like a
         ;; real REPL prints before returning. Bounded by the same cap.
         out-ln      (when (and (string? out) (not (str/blank? out)))
                       (cap-result (str/trimr out) limit))
         ;; The result / error body, rendered as REPL output.
         result-ln
         (cond
           comment-only? nil

           ok?
           ;; READ-SIDE projection net (#41, D4): a legacy row whose
           ;; stored `:seon.eval/result-edn` holds a raw `#datahike/DB`/
           ;; `#datahike/Datom` dump is sanitized on render (re-read,
           ;; re-project, re-pr-str) WITHOUT a cluster reset. A clean
           ;; string (every post-fix row) returns untouched.
           ;; The CITABLE RESULT BODY caps at `result-body-render-cap`
           ;; (16384 = the store ceiling), NOT `limit` (1500) — so a
           ;; stored ≤16384 result renders WHOLE (#53). It is the one
           ;; component that is row-capped upstream AND dereferenceable
           ;; via result/<id>, so it earns the larger cap.
           (let [body-cap result-body-render-cap
                 raw     (str (seval/sanitize-result-edn (or res "nil")))
                 full    (count raw)
                 clipped? (> full body-cap)
                 ;; The value body — clipped (with the size guide) when
                 ;; huge. The guide carries its own `\n;;` lines (a
                 ;; result/<id> dig hint), shown below the `=>` line.
                 v       (cap-result-body raw body-cap eid)
                 ;; The live VAR HANDLE rides the `=>` line as a trailing
                 ;; `;; result/<id>`: the agent references `result/<id>`
                 ;; directly. Prior-session rows carry NO handle (their
                 ;; vars died with the process). A clip appends `(N of M)`
                 ;; so the agent knows the shown value is partial.
                 handle  (when-not prior?
                           (str " ;; result/" eid
                                (when clipped? (str " (" body-cap " of " full ")"))))
                 lines   (str/split-lines v)]
             ;; Prefix ONLY the first line with `=>` + handle; continuation
             ;; lines (a clip's own `;;` guide) stay as the body wrote them.
             (str "=> " (first lines) handle
                  (when (next lines)
                    (str "\n" (str/join "\n" (rest lines))))))

           (einstrument/instrument-error? envelope)
           (cap-result-body
             (error-lines (einstrument/render-malli-error envelope)) limit eid)

           (and (string? err) (not (str/blank? err)))
           ;; `:seon.eval/error` is stored pre-rendered + crystal-clear
           ;; (`seon.eval/render-error-string` / `read-error-message` /
           ;; the undeclared-var message) — render as a `=> ✗` failure
           ;; line, plain-clip (NOT the "narrow your query" result guide).
           (cap-result (error-lines err) limit)

           :else "=> ✗ <no result>")
         ;; Reactive 'won't persist' note (#7) — DERIVED from source, no
         ;; stored attr; recomputed each render so it follows the form.
         note   (when (and ok? (not comment-only?))
                  (seval/scratch-def-note src))]
     (->> [(when (seq preamble) (str/join "\n" preamble))
           form-ln
           out-ln
           result-ln
           (when (and note (not (str/blank? note)))
             (str ";; " (strip-comment-prefix note)))]
          (remove nil?)
          (str/join "\n")))))

;; ------------------------------------------------------------
;; Read API — what the agent calls from its REPL to walk its own
;; state. All sync, all pulling from the live conn. Match v1.md §5's
;; map-arg convention with smart defaults.
;;
;; Agent-id resolution: callers pass `:seon.agent/id` explicitly OR
;; run inside a `(seon.db/with-agent id …)` scope (the normal boot/
;; run-loop path). `resolve-id` throws a clear ex-info when neither
;; is available — we don't guess, we don't fall back to a hardcoded
;; process-global default (audit P1).
;; ------------------------------------------------------------

(defn- resolve-id
  "Return the explicit id when supplied, else `(db/current-agent-id)`,
   else throw with a clear message. Centralized so every read API
   surfaces the same instruction when called outside any agent scope."
  [id]
  (or id
      (db/current-agent-id)
      (throw (ex-info
               (str "seon.agent: no agent-id in scope — pass "
                    ":seon.agent/id explicitly or call inside "
                    "(seon.db/with-agent id …).")
               {::error :seon.agent/no-agent-id}))))

(defn messages
  "Last N messages of MY conversation, oldest-first. The conversation
   is DERIVED — `from = me OR to ∋ me` — never stored as a membership
   attr (the retired per-message agent back-ref). Queries the
   message log DIRECTLY, not via :seon.agent.session/turns → :seon.agent.turn/
   messages (the turn-walk was the run-3 demo killer: standalone
   inbound messages never attach to a turn). The from/to refs are
   pulled with their id attrs so transcript labeling resolves by ref
   kind. Default {:seon.agent/n 50}. Optional `:seon.db/db` — the
   composer threads its render snapshot here so every section reads
   the SAME db value (the run-3 bug class fix)."
  ([] (messages {}))
  ([{:seon.agent/keys [n id] db :seon.db/db :or {n 50}}]
   (let [id     (resolve-id id)
         db     (or db @db/*conn*)
         my-eid (:db/id (db/entity-lazy {:seon.db/db db
                                         :seon.db/ref [:seon.agent/id id]}))
         rows   (when my-eid
                  (db/query
                    {:seon.db/db db
                     :seon.db/query
                     '[:find (pull ?m [* {:seon.agent.message/from
                                          [:db/id :seon.user/id :seon.agent/id]
                                          :seon.agent.message/to
                                          [:db/id :seon.user/id :seon.agent/id]}])
                       :in $ ?me
                       :where
                       (or-join [?m ?me]
                         [?m :seon.agent.message/from ?me]
                         [?m :seon.agent.message/to ?me])
                       [?m :seon.agent.message/at _]]
                     :seon.db/args [my-eid]}))
         msgs   (->> rows
                     (map first)
                     (sort-by #(.getTime ^js (:seon.agent.message/at %))))]
     (vec (take-last n msgs)))))

(defn current-turn
  "Most-recent :seon.agent.turn on the agent's current session — the one
   that's :running, or the last :done if no turn is open."
  ([] (current-turn {}))
  ([{:seon.agent/keys [id] db :seon.db/db}]
   (let [id      (resolve-id id)
         session (current-session id db)]
     (last (sort-by :seon.agent.turn/at (:seon.agent.session/turns session))))))

(defn session-evals
  "ALL :seon.eval entries for `agent-id`, oldest-first across ALL
   sessions, each tagged with its owning `:seon.agent.session/id` —
   the transcript's cross-restart read (context-v4 §2.8: prior-session
   evals render too, behind a resume boundary marker). Walks
   agent → sessions → turns → evals. Optional `db` snapshot."
  [agent-id db]
  (let [a (db/entity-lazy (cond-> {:seon.db/ref [:seon.agent/id agent-id]}
                            db (assoc :seon.db/db db)))]
    (vec
      (for [s (sort-by :seon.agent.session/at (:seon.agent/sessions a))
            t (sort-by :seon.agent.turn/at (:seon.agent.session/turns s))
            e (sort-by :seon.eval/at (:seon.agent.turn/evals t))]
        (assoc (into {} e)
               :seon.agent.session/id-of-session
               (:seon.agent.session/id s))))))

(defn evals
  "Last N :seon.eval entries for the agent's current session,
   oldest-first. Walks :seon.agent.session/turns → :seon.agent.turn/evals (Platform
   migrated eval storage to this shape in commit 5786247).
   Default {:seon.agent/n 20}. Optional `:seon.db/db` snapshot."
  ([] (evals {}))
  ([{:seon.agent/keys [n id] db :seon.db/db :or {n 20}}]
   (let [id      (resolve-id id)
         session (current-session id db)
         es      (for [t (sort-by :seon.agent.turn/at (:seon.agent.session/turns session))
                       e (sort-by :seon.eval/at (:seon.agent.turn/evals t))]
                   e)]
     (vec (take-last n es)))))

(defn current-ns
  "The agent's current namespace — derived from the latest successful
   eval's :seon.eval/ns. Falls back to (home-ns id) when no successful
   eval has run yet. Reactive: the next successful eval that switches
   ns (via `(ns …)`) shows up here on the next call. See
   docs/seon/concepts/reactive-context. Optional `:seon.db/db`
   snapshot (the composer threads its render db here)."
  ([] (current-ns {}))
  ([{:seon.agent/keys [id] db :seon.db/db}]
   (let [id (resolve-id id)
         ;; All evals across all sessions, latest first.
         all-evals
         (for [s (:seon.agent/sessions
                   (db/entity-lazy (cond-> {:seon.db/ref [:seon.agent/id id]}
                                     db (assoc :seon.db/db db))))
               t (:seon.agent.session/turns s)
               e (:seon.agent.turn/evals t)
               :when (true? (:seon.eval/ok? e))]
           e)
         latest (last (sort-by :seon.eval/at all-evals))]
     (or (:seon.eval/ns latest) (home-ns id)))))

(defn turns-since-inbound
  "Count of :seon.agent.turn entities in the agent's current session whose
   :seon.agent.turn/at is strictly after the latest INBOUND message's :at —
   a message with to ∋ me AND from ≠ me with a waking origin (∈
   {:human :agent} — the user and other agents both reset the window; a
   :core substrate nudge does NOT, #43). Drives `run-agentic-loop!`'s
   cap policy. Derived from the message + turn log; nothing stored.
   See docs/seon/concepts/reactive-context."
  ([] (turns-since-inbound {}))
  ([{:seon.agent/keys [id] db :seon.db/db}]
   (let [id      (resolve-id id)
         db      (or db @db/*conn*)
         session (current-session id db)
         turns   (:seon.agent.session/turns session)
         ;; lookup refs are NOT auto-resolved in query args — bind the
         ;; eid explicitly so the ref-valued ?me joins work.
         my-eid  (:db/id (db/entity-lazy {:seon.db/db db
                                          :seon.db/ref [:seon.agent/id id]}))
         latest-inbound-at
         (when my-eid
           (->> (db/query
                  {:seon.db/db db
                   :seon.db/query
                   '[:find (max ?at)
                     :in $ ?me ?cap
                     :where
                     [?m :seon.agent.message/to ?me]
                     [?m :seon.agent.message/from ?f]
                     [(not= ?f ?me)]
                     ;; hop-exhausted messages must NOT extend the loop:
                     ;; without this filter two live agent loops reset
                     ;; each other's window forever (the wake guard only
                     ;; gates loop STARTS, not in-flight loops).
                     [(get-else $ ?m :seon.agent.message/hops 0) ?h]
                     [(< ?h ?cap)]
                     ;; :core substrate nudges (tile recovery) must not
                     ;; extend the loop either (#43) — mirrors the wake gate
                     ;; and replied-since-inbound?. Legacy rows have no
                     ;; origin ⇒ default :human.
                     [(get-else $ ?m :seon.agent.message/origin :human) ?o]
                     [(not= ?o :core)]
                     [?m :seon.agent.message/at ?at]]
                   :seon.db/args [my-eid warn/hop-cap]})
                ffirst))]
     (count
       (if latest-inbound-at
         (filter #(> (.getTime ^js (:seon.agent.turn/at %))
                     (.getTime ^js latest-inbound-at))
                 turns)
         turns)))))

(defn ctx-entities
  "Pull the agent's :seon.agent/ctx vector with each :seon.ctx entity
   inlined. Sorted by :seon.ctx/priority. Useful for inspection
   and for the agent's layout-editing flow."
  ([] (ctx-entities {}))
  ([{:seon.agent/keys [id]}]
   (let [id (resolve-id id)]
     (->> (db/pull {:seon.db/pull-pattern '[{:seon.agent/ctx [*]}]
                    :seon.db/ref [:seon.agent/id id]})
          :seon.agent/ctx
          (map decode-section)
          (sort-by :seon.ctx/priority)
          vec))))

;; ------------------------------------------------------------
;; Section fns (v1.md §5.2). Each takes :seon.render/system-input
;; {:seon.db/db :seon.agent/id} optionally with :seon.ctx/section
;; (the :seon.ctx entity that named this section, so the fn can read
;; per-section overrides like :seon.agent/n). Returns a string;
;; empty string = section suppressed by the composer.
;; ------------------------------------------------------------

(def system-text
  "The ONE universal `<system>` block (context-v4 §2.1) — the concept
   paragraphs (a–h) plus the four standing behavioral teachings, and
   nothing else. Usage teaching lives in the rendered namespace
   sources (docstrings + `;;` comments) and the startup tutorial
   evals, never here.

   BYTE-IDENTICAL for every agent and every turn — a `def`, not a fn
   of the agent: the agent id lives in the status line at the very
   END of the prompt (§2.9), so this block is one shared cacheable
   artifact across the whole cluster. CACHE-PREFIX invariant: no
   timestamps, no ids, no counts — anything volatile lives in
   `prompt-section`. PROVIDER-NEUTRAL: no model or vendor words, ever."
  (str
    "<system>\n"
    "You are at a live Clojure REPL on one human's runtime. The REPL is\n"
    "your only tool: everything you do — read, compute, store, reply,\n"
    "render — is a Clojure form evaluated here. It is ClojureScript in a\n"
    "long-running Node process: you have full js/ interop (js/fetch,\n"
    "js/Date, (js/require \"node:fs\") and any installed Node module) but\n"
    "NO JVM — no java.*, no Java class. Reach for a Node module or a js/\n"
    "builtin, never a java.* import.\n"
    "\n"
    "THE LIVE CONTEXT SYSTEM. This whole prompt re-derives from the\n"
    "shared database every turn: every section is a view of NOW, not an\n"
    "accumulating log. Fix a problem and its warning vanishes; store\n"
    "data and the next render shows it; other agents' writes appear on\n"
    "your next turn. The status line and a clean REPL prompt\n"
    "(<your-ns>=>) are the very END of this context — your reply is the\n"
    "next REPL input.\n"
    "\n"
    "EVAL MECHANICS. Your reply is one or more Clojure forms, each\n"
    "preceded by ;; comment lines. There are no tool calls. A form RUNS\n"
    "only if it starts with ( on a new line — (foo …), and the reader\n"
    "shorthands @x '(…) #(…) #'x. Everything else is treated as a NOTE,\n"
    "not run: a sentence, a bare value, AND a bare data literal you paste\n"
    "({…}, […], #{…}) — these do NOT evaluate and produce NO result. To\n"
    "use a value, wrap it in a form: (def x {…}) or (identity {…}). NEVER\n"
    "paste a printed `=>` value back as a new line — reference its\n"
    "result/<id> var. The <past-evals> below is READ-ONLY history: the\n"
    "runtime adds each form's `=> result` line and the `;; result/<id>`\n"
    "after it — never write a `=>`, `;; result/`, or any <tag> yourself.\n"
    "Your reply is ONLY ;; comments and (-forms.\n"
    "\n"
    "After your LAST form, STOP. Do not write what you think a result will\n"
    "be — the runtime runs each form and shows you the real `=> value`\n"
    "next. If a reply DEPENDS on a value you have not computed yet, query\n"
    "this turn and reply from the REAL result on a later turn; never invent\n"
    "the result to feed the reply.\n"
    "\n"
    "  Correct shape:                 Wrong shape (don't do this):\n"
    "    ;; first, look around          Let me look around first.\n"
    "    (seon.db/query ...)            (seon.db/query ...)\n"
    "    ;; then, write a reply         Now I'll write the reply.\n"
    "    (seon.db/transact! ...)        (seon.db/transact! ...)\n"
    "\n"
    "THINK IN COMMENTS. The ;; lines BEFORE each form are where your\n"
    "reasoning lives — what you are about to do and why. If you write a\n"
    "sentence, put ;; in front of every line of it. Two characters carry\n"
    "reader meaning and derail the eval if they appear loose (outside a\n"
    "string): a backtick begins a syntax-quote, and a markdown code fence\n"
    "makes the reader syntax-quote your prose and choke. Narrate plainly —\n"
    "no fences, no backticks around forms; name keywords as ordinary text\n"
    "— the :seon.db/tx-data key, never a backticked span. Markdown inside\n"
    "a reply! string is fine, though — that renders on your human's screen.\n"
    "\n"
    "RESULT VARS. Every eval's value is a live var result/<id>, where the\n"
    "id is the short handle the runtime prints on that form's result line\n"
    "in the past-evals history. Reference result/<id> directly to reuse it\n"
    "— never re-run a form you\n"
    "already computed (the result/<id> handle is the runtime's, never one\n"
    "you write). A clipped display is NOT a clipped value: dig into\n"
    "a big result with ordinary Clojure (get-in, filter, count) on its\n"
    "result/<id> var instead of re-querying. NEVER copy a printed `=>`\n"
    "value back as a new form — reference its result/<id> var instead. A\n"
    "printed value is also a SUMMARY, not the live object: a\n"
    "datahike db/datom/entity in a result shows as a small placeholder\n"
    "(e.g. {:seon.eval/opaque \"datahike/DB\" …} or {:seon.eval/datom […]})\n"
    "— the real handle lives in result/<id>. Reach for the result/<id>\n"
    "var, never the printed value.\n"
    "\n"
    "STATE ACROSS TURNS. A (defn …) and an atom def like (def !x (atom\n"
    "0)) persist in your namespace — define a helper now, call it next\n"
    "turn. A bare (def x 42) does NOT survive being read back on a later\n"
    "turn (a self-host limitation); hold mutable values in an atom, not a\n"
    "bare def.\n"
    "\n"
    "ERRORS ARE VALUES. Core calls never throw at you — a failure\n"
    "comes back as data, e.g. {:seon.db/ok? false :seon.db/error …}.\n"
    "Read the error map; it names the defect and the fix. Telling your\n"
    "human something \"threw an exception\" when you were handed an error\n"
    "envelope is wrong — nothing was thrown; the failure is a value you\n"
    "read.\n"
    "\n"
    "THE RENDERING SYSTEM. You show your human things with render\n"
    "twins: :seon.render/ai (text for you) + :seon.render/html (hiccup\n"
    "for their screen) — one render, two surfaces. Your live tile and\n"
    "your context sections both ride this shape. A *section* (not just\n"
    "the tile) can carry an :seon.render/html twin — that is where rich\n"
    "panels (tables, images, SVG) go: the agent reads the :ai text, the\n"
    "human sees the :html panel, one section row serving both.\n"
    "\n"
    "THE SHARED STORE. All agents are wired to ONE shared datahike\n"
    "(datomic-style) database; *conn* is ambient — your universe binds\n"
    "it, never thread it. seon.db is rendered below; its docstrings are\n"
    "the API reference. Two laws worth stating once: register an\n"
    "attribute (seon.schema/register!) BEFORE the first transact that\n"
    "uses it, and give every attribute keyword a namespace of at least\n"
    "two dot-separated segments (:my.kb.doc/title — never :doc/title,\n"
    "never :title).\n"
    "\n"
    "COMMON DB OPS — the seon.db docstrings below are the full reference;\n"
    "this is the cheat sheet. *conn* is ambient (never pass it); reads\n"
    "are synchronous, transact! hands back a VALUE envelope.\n"
    "\n"
    "  ;; ADD — register each attr once, then transact entity maps. An\n"
    "  ;; :seon.db/identity attr is the entity's natural key.\n"
    "  (seon.schema/register! :my.kb.doc/id    [:string {:seon.db/identity true}])\n"
    "  (seon.schema/register! :my.kb.doc/title :string)\n"
    "  (seon.db/transact! [{:my.kb.doc/id \"d1\" :my.kb.doc/title \"Intro\"}])\n"
    "  ;; => an envelope VALUE you read — :seon.db/ok? true on success, or\n"
    "  ;;    :seon.db/ok? false + :seon.db/error on failure (read it, never retype).\n"
    "\n"
    "  ;; UPSERT — transact the SAME identity value to UPDATE that entity\n"
    "  ;; (no duplicate); keys you omit are left untouched.\n"
    "  (seon.db/transact! [{:my.kb.doc/id \"d1\" :my.kb.doc/title \"Intro v2\"}])\n"
    "\n"
    "  ;; REMOVE — retraction is explicit (omitting a key only leaves it\n"
    "  ;; unchanged). Clear one attr, or delete the whole entity:\n"
    "  (seon.db/transact! [[:db/retract [:my.kb.doc/id \"d1\"] :my.kb.doc/title]])\n"
    "  (seon.db/transact! [[:db.fn/retractEntity [:my.kb.doc/id \"d1\"]]])\n"
    "  ;; cardinality-many attrs ADD on transact — replace = retract then add.\n"
    "\n"
    "  ;; QUERY — Datalog; the db auto-injects from *conn* (just omit it).\n"
    "  (seon.db/query '[:find ?t :where [?e :my.kb.doc/title ?t]])       ; #{[v] …}\n"
    "  (seon.db/query '[:find ?t . :where [?e :my.kb.doc/title ?t]])     ; one value\n"
    "  (seon.db/query '[:find [?t ...] :where [?e :my.kb.doc/title ?t]]) ; one column\n"
    "  ;; :in inputs go AFTER the query (db still omitted):\n"
    "  (seon.db/query '[:find ?t :in $ ?id\n"
    "                   :where [?e :my.kb.doc/id ?id] [?e :my.kb.doc/title ?t]] \"d1\")\n"
    "  ;; Logic vars (?e ?t) stay symbols ONLY inside the quoted '[…]\n"
    "  ;; vector; an empty #{} usually means a misspelled attr — copy the\n"
    "  ;; keyword EXACTLY from <inventory> or the register! form.\n"
    "\n"
    "  ;; PULL / ENTITY — by lookup ref [identity-attr value] or :db/id.\n"
    "  (seon.db/pull '[*] [:my.kb.doc/id \"d1\"])   ; {:db/id … :my.kb.doc/title …}\n"
    "  (seon.db/entity [:my.kb.doc/id \"d1\"])      ; plain map: :db/id + attrs\n"
    "\n"
    "  ;; WILDCARD + COMPONENTS — '[*] inlines COMPONENT children as full\n"
    "  ;; nested maps (recursively); a PLAIN ref comes back as {:db/id N}.\n"
    "  ;; Pull a plain ref's fields by NAMING it: '[* {:my.kb.doc/author [*]}].\n"
    "\n"
    "THE NAMESPACES BELOW are real loaded code, ordered by RECENCY —\n"
    "most-recently-modified LAST, not dependency order; the runtime\n"
    "loaded them correctly. Each renders COMPACT: the (ns …) form, every\n"
    "schema in FULL (the schemas ARE the contract), and each fn as its\n"
    "real (defn …) with the BODY elided to `…` — signature + docstring +\n"
    "attr-map, enough to CALL it without reading its guts. The ONE\n"
    "exception is YOUR OWN current namespace, shown in FULL so you see\n"
    "your complete working code. To read another ns's full bodies + tests\n"
    "on demand, call\n"
    "(seon.ctx/render-namespace {:seon.ns/name :the.ns}). Namespaces are\n"
    "workspaces: (ns my.domain.thing) moves you there and your context\n"
    "follows your namespace. Your code is my.*, your knowledge is my.kb.*\n"
    "(real schemas per domain); the core is seon.* — call its fns, never\n"
    "redefine them.\n"
    "\n"
    "STANDING TEACHINGS:\n"
    "- Consult stored knowledge FIRST — it is DISCOVERABLE, not dumped:\n"
    "  <inventory> lists every stored KIND + its attrs (run\n"
    "  (seon.db/store-inventory) — your creation turn already did), so\n"
    "  you READ the rows by QUERYING rather than from a wall of text.\n"
    "  Datalog the existing attrs for\n"
    "  anything you need. The inventory lists the data added AFTER\n"
    "  bootstrap; the full system inventory — the core's own\n"
    "  fn/schema/test index included — is one call away:\n"
    "  (seon.db/store-inventory {:seon.db/system? true})\n"
    "  Prior agents already answered many questions; re-deriving a\n"
    "  stored answer is wasted turns.\n"
    "- Store what you verify, without being asked: design (or reuse) a\n"
    "  my.kb.<domain> schema, reference the shared :my.kb/* provenance\n"
    "  attrs, and transact the fact. Knowledge nobody stored is\n"
    "  research the next agent pays for again.\n"
    "- Keep fns small and tight. Add ONE concise :test usage example to\n"
    "  a new defn ONLY when its :malli/schema doesn't already make the\n"
    "  call obvious (generic shapes like [:int]->:boolean):\n"
    "  (defn f {:malli/schema … :test (fn [] (assert (= 4 (f 2 2))))}\n"
    "    [a b] …). It is what the NEXT agent sees INSTEAD of your body, so\n"
    "  it must be self-explanatory AND pass (a failing example surfaces).\n"
    "- A task with 2+ steps: mint one todo per step with\n"
    "  seon.agent.todo/add! BEFORE you start, and complete! each id as\n"
    "  the step lands. Your open todos render every turn with their\n"
    "  ids; an empty <open-todos> section is your done-signal.\n"
    "- Your replies render as markdown on your human's screen — use\n"
    "  structure when it helps (short headings, lists, code fences for\n"
    "  code or data); plain prose otherwise.\n"
    "- A question is not served until (seon.agent/reply! …) lands — your\n"
    "  human sees NOTHING until it does. Reply in the SAME response WHEN\n"
    "  you can answer from what's already present; but when you must\n"
    "  evaluate to answer, emit the forms and let the REAL result come\n"
    "  back — reply from it on a later turn. NEVER fabricate a result to\n"
    "  satisfy \"reply this turn.\"\n"
    "  ONE reply per question: once it lands your wake is complete and\n"
    "  the loop stops; a new message will wake you if more is needed.\n"
    "  reply! ALWAYS lands and is delivered. But errors-are-VALUES: a\n"
    "  transact can SUCCEED as an eval yet return {:seon.db/ok? false} —\n"
    "  the write did NOT happen. If you reply in the SAME turn as a form\n"
    "  that returned such a failure envelope, your human gets a possibly-\n"
    "  false confirmation, so you are given ONE more turn with a\n"
    "  <reply-over-claim-warning> to re-read the failure and send a\n"
    "  correction. So: confirm each write's envelope is {:seon.db/ok?\n"
    "  true} BEFORE you claim it landed; reply as a CLEAN final step. When\n"
    "  you are deliberately replying ABOUT a failure, pass\n"
    "  :seon.agent.message/force true.\n"
    "  reply! answers whoever woke you; to message a SPECIFIC target use\n"
    "  (seon.agent/message! {:seon.agent.message/to [:seon.agent/id\n"
    "  \"<id>\"] :seon.agent.message/content \"…\"}).\n"
    "- TURNS ARE PRECIOUS — each turn is a full round-trip; don't spend\n"
    "  one exploring when the answer is already in front of you. If your\n"
    "  context CLEARLY contains the answer (it's in the soul, an\n"
    "  <inventory> row, a loaded ns, or the past-evals), REPLY this turn —\n"
    "  (seon.agent/reply! \"…\") — don't re-research what you can already\n"
    "  see. But if the answer is NOT plainly present — anything about\n"
    "  stored knowledge, your human's data, the codebase, or specifics you\n"
    "  haven't read this turn — QUERY FIRST (store-inventory + datalog),\n"
    "  THEN reply from what you found. Guessing because replying is fast is\n"
    "  the failure; a turn spent confirming a known answer is the waste.\n"
    "- Replying does NOT end your ability to act. After the reply lands\n"
    "  you can keep working THIS turn or on later turns — set/refresh a\n"
    "  live tile, do the other work they asked for, store what you\n"
    "  learned. Reply first, then continue.\n"
    "- You may reply EVERY turn when your human needs to stay informed:\n"
    "  if they ask for progress, or you judge too many turns / too much\n"
    "  time have passed without an update, send a SHORT progress reply.\n"
    "  Silence across many turns is a failure mode; a one-line update is\n"
    "  cheap.\n"
    "- Shorthand: (seon.agent/reply! \"text\") is the same as\n"
    "  (seon.agent/reply! {:seon.agent.message/content \"text\"}) — use the\n"
    "  string form for plain replies.\n"
    "- Building tile or panel hiccup from a sequence: splice children\n"
    "  with (into [:div …] children), never nest a bare seq as one\n"
    "  child. Eval your render fn once at the REPL to eyeball the hiccup\n"
    "  before you wire it onto a surface.\n"
    "- NEVER write a result you have not evaluated. Your reply is REPL\n"
    "  input, not a transcript — any result line you type yourself (a bare\n"
    "  `=> 61`, a `;; => 42`, a `;; result/<id>`) is FICTION the next agent\n"
    "  may trust, and acting on it — a count, a reply, a query result you\n"
    "  invented — is the worst failure there is. Eval the form, WAIT, and\n"
    "  let the runtime write the real value line.\n"
    "- When you store a my.kb.* fact, grade it: record HOW you know it\n"
    "  (a :my.kb/source) and HOW SURE you are (a :my.kb/confidence). A\n"
    "  guess stored as fact is worse than no fact — the next agent\n"
    "  cannot read your certainty from your phrasing.\n"
    "</system>"))

(defn system-section
  "The universal `<system>` header — returns [[system-text]] verbatim.
   A fn only because sections render through the symbol slot; it takes
   the standard section input and ignores it (byte-identical for every
   agent and turn — the whole point)."
  {:malli/schema [:=> [:cat :map] :string]}
  [_input]
  system-text)


;; ============================================================
;; render-namespace — the foundational whole-namespace render.
;;
;; Renders ONE namespace (ns source + its fns + schemas + tests) in
;; either :ai text or :html hiccup, recursing into the namespaces it
;; `(:require …)`s. Required nses render FIRST (prepended) so that, read
;; top-to-bottom, a reference resolves before its use. The default
;; context an agent receives is built from this: drop an agent into a
;; near-empty ns that requires a parent agent ns, and depth-1 brings the
;; parent's fns/schemas into view.
;;
;; Pure function of the DB — stores nothing. Per-member output is bounded
;; here (signature + doc by default, full source only for small fns); the
;; clip guardrail is a later backstop, not a crutch.
;; ============================================================

(def ^:private fn-source-inline-threshold
  "Fns whose `:seon.fn/source` is at or under this many chars render
   their full source in the :ai form; larger fns show signature + doc
   only. Keeps a whole-ns render bounded to a few KB."
  240)

(def ^:private member-doc-clip
  "Max chars of a fn docstring surfaced per member in the :ai form."
  280)

(defn- clip
  "Clip `s` to `n` chars with an ellipsis marker. nil-safe."
  [s n]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 n) " …") s)))

(defn- parse-require-syms
  "Parse an `(ns … (:require …))` source string and return the vector of
   required namespace symbols (in declaration order, deduped). Handles
   bare-symbol specs (`a.b`) and vector specs (`[a.b :as c :refer […]]`).
   Returns [] on any parse failure or when there's no `(ns …)` form —
   recursion simply stops rather than erroring."
  [src]
  (if (or (nil? src) (str/blank? src))
    []
    (try
      (let [form (edn/read-string src)]
        (if (and (seq? form) (= 'ns (first form)))
          (->> (rest form)
               (filter #(and (seq? %) (= :require (first %))))
               (mapcat rest)
               (keep (fn [spec]
                       (cond
                         (symbol? spec)     spec
                         (sequential? spec) (first spec)
                         :else              nil)))
               (filter symbol?)
               distinct
               vec)
          []))
      (catch :default _ []))))

(defn- pull-ns-data
  "Reverse-ref pull of everything one `:seon.ns` owns: its source plus
   every `:seon.fn` / `:seon.schema` / `:seon.test` whose `:ns` points at
   it. Returns nil when no `:seon.ns` entity exists for `ns-kw` (the
   caller renders a one-line 'not in db' note instead). `:seon.test` is a
   real entity kind (Step 3); its rows are pulled and rendered under the
   ns alongside fns and schemas.

   Guarded by an `entity` existence check first: `db/pull` throws on an
   unresolved lookup-ref, so we confirm presence before pulling."
  [db ns-kw]
  (when (db/entity-lazy {:seon.db/db db :seon.db/ref [:seon.ns/name ns-kw]})
    (let [core (db/pull {:seon.db/db db
                         :seon.db/ref [:seon.ns/name ns-kw]
                         :seon.db/pull-pattern
                         '[:seon.ns/source
                           {:seon.fn/_ns     [:seon.fn/sym :seon.fn/arglists
                                              :seon.fn/doc :seon.fn/source
                                              :seon.fn/private? :seon.fn/spec
                                              :seon.fn/schema-error]
                            :seon.schema/_ns [:seon.schema/key :seon.schema/source]}]})
          ;; :seon.test is now a real entity kind (Step 3): `:seon.test/ns`
          ;; IS registered, so this reverse-ref pull resolves. Kept as a
          ;; SEPARATE guarded call (vs. inlining into the `core` pull) for
          ;; cleanliness: a conn that has no `:seon.test` rows for this ns
          ;; yields nil and the merge below is a no-op.
          tests (try
                  (-> (db/pull {:seon.db/db db
                                :seon.db/ref [:seon.ns/name ns-kw]
                                :seon.db/pull-pattern
                                '[{:seon.test/_ns
                                   [:seon.test/sym :seon.test/source
                                    :seon.test/last-passed-at
                                    :seon.test/last-failed-at
                                    :seon.test/last-failure-summary]}]})
                      :seon.test/_ns)
                  (catch :default _ nil))]
      (cond-> core
        (seq tests) (assoc :seon.test/_ns tests)))))

(defn- fn-block-ai
  "One fn rendered for the :ai form: `(sym arglists)` header, clipped
   doc, and full source only when small. Reuses the conventional
   signature shape via `seon.handlers.fn/render-ai` is overkill here
   (that fn also runs test-status queries); we render flat + bounded."
  [{:seon.fn/keys [sym arglists doc source private? spec schema-error]}]
  (let [sig    (when (and arglists (not (str/blank? arglists)))
                 (let [a (str/trim arglists)]
                   (if (and (str/starts-with? a "(") (str/ends-with? a ")"))
                     (str "(" sym " " (subs a 1 (dec (count a))) ")")
                     (str "(" sym " " a ")"))))
        flags  (cond-> []
                 private?      (conj ":private")
                 (some? spec)  (conj (str ":spec " (clip spec 80)))
                 (nil? spec)   (conj ":unspecced")
                 schema-error  (conj (str ":schema-error " (clip schema-error 80))))
        header (str "[fn " sym "]"
                    (when sig (str "  " sig))
                    (when (seq flags) (str "  " (str/join " " flags))))
        small? (and source (<= (count source) fn-source-inline-threshold))
        lines  (cond-> [header]
                 (and doc (not (str/blank? doc)))
                 (conj (str ";; " (clip (first (str/split-lines doc)) member-doc-clip)))
                 small?
                 (conj (str/trim source)))]
    (str/join "\n" lines)))

(defn- schema-block-ai
  "One schema rendered for the :ai form: `[schema :ns/key]  <malli form>`.
   Pulls the live shape from the registry; falls back to the persisted
   `:seon.schema/source` when the registry has no entry."
  [{:seon.schema/keys [key source]}]
  (let [shape (when (keyword? key)
                (try (schema/schema-definition key) (catch :default _ nil)))
        form  (cond
                shape                       (clip (pr-str shape) 200)
                (not (str/blank? source))   (clip (str/trim source) 200)
                :else                       "<not registered>")]
    (str "[schema " (pr-str key) "]  " form)))

(defn- test-block-ai
  "One test rendered for the :ai form — `[test sym]` header, the
   pass/fail status line (✓/✗/•), and clipped source. The status glyph
   is derived via the shared `seon.handlers.test/status-line` — the
   SINGLE source of the ✓/✗/• logic — so this whole-ns block and the
   per-kind `seon.handlers.test/render-ai` never diverge."
  [{:seon.test/keys [sym source] :as test}]
  (str "[test " sym "]"
       "\n" (h-test/status-line test)
       (when (and source (not (str/blank? source)))
         (str "\n" (clip (str/trim source) fn-source-inline-threshold)))))

(defn- render-one-ns-ai
  "Render a single namespace block to text. `ns-kw` is the namespace
   keyword; `data` is the `pull-ns-data` result (or nil = not in db)."
  [ns-kw data]
  (if (nil? data)
    (str ";; requires: " (name ns-kw) " (not in db)")
    (let [src     (:seon.ns/source data)
          fns     (->> (:seon.fn/_ns data)     (sort-by :seon.fn/sym))
          schemas (->> (:seon.schema/_ns data) (sort-by (comp str :seon.schema/key)))
          tests   (->> (:seon.test/_ns data)   (sort-by :seon.test/sym))
          body    (cond-> []
                    (and src (not (str/blank? src)))
                    (conj (str/trim src))
                    (seq fns)
                    (into (map fn-block-ai fns))
                    (seq schemas)
                    (into (map schema-block-ai schemas))
                    (seq tests)
                    (into (map test-block-ai tests)))]
      (str "<namespace name=\"" (name ns-kw) "\">\n"
           (if (seq body) (str/join "\n\n" body) ";; (no recorded source/fns/schemas)")
           "\n</namespace>"))))

(defn- render-one-ns-html
  "Render a single namespace block to hiccup. Reuses the per-kind
   `seon.handlers.{ns,fn,schema}/render-html` for each member so the
   webview card styling stays consistent with the inspector panes."
  [db ns-kw data]
  (if (nil? data)
    [:div {:class "py-1 text-xs font-mono text-text-500 italic"}
     (str "requires: " (name ns-kw) " (not in db)")]
    (let [fns     (->> (:seon.fn/_ns data)     (sort-by :seon.fn/sym))
          schemas (->> (:seon.schema/_ns data) (sort-by (comp str :seon.schema/key)))
          tests   (->> (:seon.test/_ns data)   (sort-by :seon.test/sym))
          ns-ent  {:seon.ns/name ns-kw}]
      (into
        [:section {:class "py-1 border-l-2 border-base-700 pl-2"}
         (:seon.render/hiccup (h-ns/render-html {:seon.db/db db :seon.render/entity ns-ent}))]
        (concat
          (for [f fns]
            (:seon.render/hiccup
              (h-fn/render-html {:seon.db/db db :seon.render/entity f})))
          (for [s schemas]
            (:seon.render/hiccup
              (h-schema/render-html {:seon.db/db db :seon.render/entity s})))
          ;; Tests rendered via the per-kind handler — same `test-status`
          ;; source as the AI path, so the pass/fail pill never diverges.
          (for [t tests]
            (:seon.render/hiccup
              (h-test/render-html {:seon.db/db db :seon.render/entity t}))))))))

(defn- collect-ns-order
  "Compute the ordered, deduped list of namespace keywords to render —
   required nses FIRST (prepended), then the ns itself, recursing to
   `depth`. Cycle- and revisit-safe: a ns already in the accumulator is
   never expanded or re-added. depth 0 = just `ns-kw` (no requires).

   Returns `[ordered-kws data-by-kw]` where `data-by-kw` caches each
   ns's `pull-ns-data` result (possibly nil for not-in-db requires)."
  [db ns-kw depth]
  (let [data-by-kw (atom {})
        seen       (atom #{})
        order      (atom [])
        ;; memoized pull
        data-for   (fn [k]
                     (if (contains? @data-by-kw k)
                       (@data-by-kw k)
                       (let [d (pull-ns-data db k)]
                         (swap! data-by-kw assoc k d)
                         d)))
        walk       (fn walk [k d]
                     (when-not (contains? @seen k)
                       (swap! seen conj k)
                       (let [data (data-for k)
                             reqs (when (and data (pos? d))
                                    (->> (parse-require-syms (:seon.ns/source data))
                                         (map keyword)))]
                         ;; required nses first (prepended), then self
                         (doseq [r reqs] (walk r (dec d)))
                         (swap! order conj k))))]
    (walk ns-kw depth)
    [@order @data-by-kw]))

(schema/register! :seon.render/depth :int)
(schema/register! :seon.render/format [:enum :ai :html])

(schema/register! ::render-namespace-request
  [:map
   [:seon.ns/name        :seon.ns/name]
   [:seon.render/depth   {:optional true} :seon.render/depth]
   [:seon.render/format  {:optional true} :seon.render/format]
   [:seon.db/db          {:optional true} :seon.db/db]])

(schema/register! ::render-namespace-response
  [:map
   [:seon.render/text   {:optional true} :string]
   ;; Pure-data shallow hiccup bound — registered forms must not embed
   ;; fns (platform law; see seon.render.live-tile). Deep validation
   ;; stays at the render boundary.
   [:seon.render/hiccup {:optional true} :seon.render.live-tile/hiccup]])

(defn render-namespace
  "Render a WHOLE namespace — its `(ns …)` source plus every `:seon.fn`,
   `:seon.schema`, and (when the kind exists) `:seon.test` it owns — in
   either `:ai` text or `:html` hiccup, recursing into the namespaces it
   `(:require …)`s.

   Required namespaces render FIRST (prepended), then the namespace
   itself, to `:seon.render/depth` (default 1 = the ns + its direct
   requires). Recursion is deduped (each ns rendered once) and cycle-safe.
   A required ns with no `:seon.ns` entity is noted on a single line
   (`requires: x.y (not in db)`), never errored.

   Map-in / map-out:

     {:seon.ns/name <keyword>
      :seon.render/depth  <int, default 1>
      :seon.render/format <:ai | :html, default :ai>
      :seon.db/db <db value, optional — defaults to @*conn*>}

   → {:seon.render/text <string>}     for :ai
   → {:seon.render/hiccup <hiccup>}   for :html

   This is the foundation of every agent's default context; the section
   that surfaces the agent's namespaces resolves to it (T5)."
  {:malli/schema [:=> [:cat ::render-namespace-request] ::render-namespace-response]}
  [{ns-name :seon.ns/name
    :seon.render/keys [depth format]
    :seon.db/keys [db]
    :or {depth 1 format :ai}}]
  (let [db    (or db @db/*conn*)
        ns-kw (if (keyword? ns-name) ns-name (keyword (str ns-name)))
        [order data-by-kw] (collect-ns-order db ns-kw (max 0 depth))]
    (if (= format :html)
      {:seon.render/hiccup
       (into [:div {:class "flex flex-col gap-2"}]
             (for [k order]
               (render-one-ns-html db k (data-by-kw k))))}
      {:seon.render/text
       (str/join "\n\n" (for [k order]
                          (render-one-ns-ai k (data-by-kw k))))})))
(defn- latest-live-inbound
  "The latest LIVE inbound message for `my-eid` in db value `db` as
   [at content] — to ∋ me, from ≠ me, hops < `warn/hop-cap` (the same
   window [[turns-since-inbound]] and the loop's cap policy count
   against). nil when none."
  [db my-eid]
  (->> (db/query
         {:seon.db/db db
          :seon.db/query
          '[:find ?at ?content
            :in $ ?me ?cap
            :where
            [?m :seon.agent.message/to ?me]
            [?m :seon.agent.message/from ?f]
            [(not= ?f ?me)]
            [(get-else $ ?m :seon.agent.message/hops 0) ?h]
            [(< ?h ?cap)]
            [?m :seon.agent.message/at ?at]
            [?m :seon.agent.message/content ?content]]
          :seon.db/args [my-eid warn/hop-cap]})
       (sort-by #(.getTime ^js (first %)))
       last))

;; Mirrors `:seon.render/assemble-request` shapes — `:seon.db/db` (the
;; registered `:any` db-value boundary, seon.render) + `:string` id — so the
;; schema compiles regardless of cross-ns load order (referencing
;; `:seon.agent/id`, registered later in seon.agent, would break a fresh build).
(schema/register! :seon.ctx/retrieval-query-request
                  [:map
                   [:seon.db/db    :seon.db/db]
                   [:seon.agent/id :string]])

(defn retrieval-query
  "Derive the text to embed for THIS turn's embedding retrieval: the latest
   LIVE inbound message's content (to ∋ me, from ≠ me, hops < `warn/hop-cap` —
   the same window the loop's cap policy uses, via [[latest-live-inbound]]),
   falling back to the most-recent message of MY conversation. Returns \"\"
   when I have no messages at all (the caller skips the wire call on blank).

   SYNC — reads the live db value the caller threads in. Does NOT add the
   retrieval-instruction prefix (the wire-server's `knn-search` adds it).
   Called by `seon.agent/run-turn!` to build the prefetch query."
  {:malli/schema [:=> [:cat :seon.ctx/retrieval-query-request] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [my-eid (:db/id (db/entity-lazy {:seon.db/db db
                                        :seon.db/ref [:seon.agent/id id]}))
        live   (when my-eid (latest-live-inbound db my-eid))]
    (cond
      (some? live) (second live)
      :else        (or (some-> (last (messages {:seon.agent/id id
                                                :seon.agent/n 1
                                                :seon.db/db db}))
                               :seon.agent.message/content)
                       ""))))

(defn task-in-progress?
  "MID-TASK derivation — TRUE from a live inbound message until the
   agent REPLIES to it: the latest live inbound (to ∋ me, from ≠ me,
   hops < `warn/hop-cap`) has no LATER outbound to a NON-SELF
   recipient. Mirrors the loop's own stop semantics
   (`seon.agent/replied-since-inbound?`, halt `:replied`) read-only
   from the message log at render time — the per-turn self-fold
   (from = to = me) never closes the window, unlike [[inbox-count]]'s
   any-outbound window (opus-live-tests 2026-06-12 finding 1: gating
   `<turns>` on inbox-count made it
   first-turn-only — dead exactly where the countdown matters). The
   gate the turns section consumes; nothing stored, nothing to clear
   (docs/seon/concepts/reactive-context)."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [{:seon.agent/keys [id] db :seon.db/db}]
  (let [id     (resolve-id id)
        db     (or db @db/*conn*)
        my-eid (:db/id (db/entity-lazy {:seon.db/db db
                                        :seon.db/ref [:seon.agent/id id]}))
        [inbound-at _] (when my-eid (latest-live-inbound db my-eid))
        reply-at
        (when my-eid
          (ffirst
            (db/query
              {:seon.db/db db
               :seon.db/query
               '[:find (max ?at)
                 :in $ ?me
                 :where
                 [?m :seon.agent.message/from ?me]
                 [?m :seon.agent.message/to ?t]
                 [(not= ?t ?me)]
                 [?m :seon.agent.message/at ?at]]
               :seon.db/args [my-eid]})))]
    (boolean (and inbound-at
                  (or (nil? reply-at)
                      (<= (.getTime ^js reply-at)
                          (.getTime ^js inbound-at)))))))
(schema/register! :seon.render/sections [:vector :seon.ctx/name])

(schema/register! :seon.render/assemble-request
  [:map
   [:seon.db/db    :seon.db/db]
   [:seon.agent/id :string]])

(schema/register! :seon.ctx/section-text
  [:map
   [:seon.ctx/name :seon.ctx/name]
   [:seon.render/text :string]])

;; The HTML TWIN of a rendered section (debug-view-section-twins-2026-06-18):
;; the dormant `:seon.render/html` slot, resolved through
;; `seon.render/html-render` + the throw-to-banner guard, paired with its
;; section name. Hiccup is genuinely arbitrary agent-authored data at this
;; boundary — `:seon.render.live-tile/hiccup` is the registered shallow
;; bound (vector with keyword head); the deep walk happens at the render
;; boundary, same as every `:seon.render/html-response`.
(schema/register! :seon.ctx/section-html
  [:map
   [:seon.ctx/name :seon.ctx/name]
   [:seon.render/hiccup :seon.render.live-tile/hiccup]])

(schema/register! :seon.render/stable-text   :string)
(schema/register! :seon.render/volatile-text :string)

(schema/register! :seon.render/assemble-response
  [:map
   [:seon.render/text            :string]
   [:seon.render/stable-text     :seon.render/stable-text]
   [:seon.render/volatile-text   :seon.render/volatile-text]
   [:seon.render/sections        :seon.render/sections]
   [:seon.render/section-texts   [:vector :seon.ctx/section-text]]
   [:seon.render/section-html    [:vector :seon.ctx/section-html]]
   [:seon.render/token-estimate  :int]])

(schema/register! :seon.render/split-response
  [:map
   [:seon.render/stable-text   :seon.render/stable-text]
   [:seon.render/volatile-text :seon.render/volatile-text]])

(def stable-boundary
  "The in-band cache-boundary line the composer joins between the
   STABLE prefix (every section through :namespaces — byte-stable
   within a session given the deterministic rendering) and the
   VOLATILE tail (everything after: your-entity, live-tile, warnings,
   open-todos, transcript, turns, inventory, prompt).

   In-band because the agent loop hands providers ONE assembled
   string (`llm-fn` is fn-of-ctx-string): [[split-context]] recovers
   the two halves on the provider side, so the Anthropic adapter can
   put the stable prefix in a cache_control'd system block and send
   only the volatile tail as the user message (task #34 — cache
   coverage was 5.4k of ~38k input tokens). Built by concatenation so
   the marker can never appear verbatim in rendered source text."
  (str ";; ──── ctx cache boundary — everything above this line is the "
       "byte-stable" " prefix; everything below changes per turn ────"))

(def ^:private stable-boundary-delim
  (str "\n\n" stable-boundary "\n\n"))

(defn split-context
  "Split an assembled ctx string at [[stable-boundary]] into the
   stable prefix and the volatile tail. A string WITHOUT the boundary
   (hand-rolled test ctx, stub prompts) is all volatile —
   `:seon.render/stable-text` is \"\" and the input rides through
   unchanged as the tail, so providers degrade to pre-split behavior."
  {:malli/schema [:=> [:catn [:seon.render/text :string]]
                  :seon.render/split-response]}
  [text]
  (let [i (.indexOf text stable-boundary-delim)]
    (if (neg? i)
      {:seon.render/stable-text   ""
       :seon.render/volatile-text text}
      {:seon.render/stable-text   (subs text 0 i)
       :seon.render/volatile-text (subs text (+ i (count stable-boundary-delim)))})))
(defn core-default-ctx
  "The default :seon.ctx section layout that ships with every fresh
   agent — the context-v4 layout (§2), ordered top→bottom =
   static→volatile (the provider-cache contract, §1):

     1. :system      — the universal concept paragraphs + standing
                       teachings; byte-identical for every agent and
                       turn (the agent id lives in the status line)
     2. :namespaces  — THE BODY: one <namespace> tag per included ns
                       (ALL seon.* + my.* minus *.internal),
                       recency-ordered most-recently-modified LAST
     3. :your-entity — the agent's own entity as a pretty-printed map
                       (purpose, tile wiring, sections, self-notes)
     4. :live-tile   — what your human currently sees (tiles-PRD U5;
                       this layout only fixes its slot: after
                       :your-entity, before :warnings)
     5. :warnings    — current problems; reactive, vanishes when fixed
     5b. :reply-over-claim — #51 advisory: fires ONLY when a user-facing
                       reply landed in the prior turn alongside a sibling
                       form that returned a {*/ok? false} envelope value
                       (the loop forced this make-good turn). Derived,
                       vanishes once the agent moves on
     6. :open-todos  — the agent's open work items; derived, vanishes
     6b. :relevant-source — env-gated (SEON_EMBED, default-OFF):
                       <relevant-source>, the top-k :seon.fn hits nearest
                       this turn's query by embedding KNN, PREFETCHED in
                       run-turn! + read from the per-turn stash. VOLATILE
                       half (query-dependent → out of the cache prefix);
                       reactive — blank when off or no hits (dropped)
     7. :transcript  — PAST turns, grouped <turn id=… evals=N/M>: the
                       woken-by <user> + the turn's evals, REPL-faithful
     8. :turns       — the turn-budget countdown (one line, mid-task
                       only; derived, vanishes when idle) — just
                       above the prompt tail for salience
     9. :inventory   — the cheap <data-inventory> map: one line per
                       stored KIND with each attr's live row count
                       (user-domain first); derived from
                       seon.db/store-inventory, vanishes when the store
                       holds no post-bootstrap data
    10. :prompt      — the §2.9 status line + clean REPL prompt
                       (always changing — the volatile tail's end)

   The dead sections (capabilities, exemplars, schema-catalog,
   functions-catalog, namespace-context, the :purpose/:your-sections
   seeds) dissolved into code (docstrings, the rendered namespace
   sources), data (the agent's own entity), or the startup tutorial
   evals (store-inventory, my.kb.system/instructions) — context-v4
   §§2.2–2.4, 3b. Smallest priority renders first."
  []
  [{:seon.ctx/name :system       :seon.ctx/priority 10
    :seon.render/ai 'seon.ctx/system-section}
   {:seon.ctx/name :namespaces   :seon.ctx/priority 20
    :seon.render/ai 'seon.ctx.namespaces/namespaces-section}
   {:seon.ctx/name :your-entity  :seon.ctx/priority 30
    :seon.render/ai 'seon.ctx.your-entity/your-entity-section}
   {:seon.ctx/name :live-tile    :seon.ctx/priority 35
    :seon.render/ai 'seon.ctx.live-tile/live-tile-section}
   {:seon.ctx/name :warnings     :seon.ctx/priority 40
    :seon.render/ai 'seon.ctx.warnings/warnings-section}
   {:seon.ctx/name :reply-over-claim :seon.ctx/priority 42
    :seon.render/ai 'seon.agent.message/overclaim-advisory-section}
   {:seon.ctx/name :open-todos   :seon.ctx/priority 45
    :seon.render/ai 'seon.agent.todo/open-todos-section}
   {:seon.ctx/name :relevant-source :seon.ctx/priority 48
    :seon.render/ai 'seon.ctx.relevant/relevant-source-section}
   {:seon.ctx/name :transcript   :seon.ctx/priority 50
    :seon.render/ai 'seon.ctx.transcript/transcript-section
    :seon.render/html 'seon.ctx.transcript/transcript-section-html}
   {:seon.ctx/name :turns        :seon.ctx/priority 90
    :seon.render/ai 'seon.agent.turns/turns-section}
   {:seon.ctx/name :inventory    :seon.ctx/priority 97
    :seon.render/ai 'seon.ctx.inventory/inventory-section}
   {:seon.ctx/name :prompt       :seon.ctx/priority 99
    :seon.render/ai 'seon.ctx.prompt/prompt-section}])

;; ============================================================
;; Composer — merge semantics + render guard + budget (the
;; agent-self-context spec, 2026-06-10):
;;
;;   sections = sort-by priority (core defaults ∪ agent's
;;              :seon.agent/ctx)   — MERGE, never replace; a name
;;              collision means override-by-name (deliberate, visible
;;              as data).
;;   input    = {db, id, entity (pulled ONCE), section, model}
;;   render   = string slot → verbatim | symbol slot → (fn input)
;;
;; Guard: a section whose fn is missing/throws renders a one-line
;; error string inside the section — never breaks assembly, surfaces
;; loudly, self-heals when fixed.
;;
;; Budget: agent-authored sections share a per-agent char budget
;; (agent-section-char-budget). Over budget → lowest-priority agent
;; sections truncate with a loud marker. Core sections are not
;; charged to it.
;; ============================================================

(def agent-section-char-budget
  "Total rendered-chars budget shared by the agent's OWN sections
   (everything in :seon.agent/ctx — strings and computed alike).
   Core default sections are not charged. Over budget, the
   LOWEST-priority (largest number, renders last) agent sections
   truncate first, each with a loud marker line."
  8000)

(defn decode-section
  "Decode the mixed-:or render slots of a PULLED section entity back to
   their value shapes (`seon.db/decode-edn-value` — the inverse of the
   bridge's EDN-string storage encoding). Code-default sections pass
   through unchanged. Public: `seon.ctx.your-entity` calls it to render
   the agent's own ctx vector."
  [section]
  (cond-> section
    (contains? section :seon.render/ai)
    (update :seon.render/ai #(db/decode-edn-value :seon.render/ai %))
    (contains? section :seon.render/html)
    (update :seon.render/html #(db/decode-edn-value :seon.render/html %))))

(defn agent-sections
  "The agent's OWN section maps from its pulled entity — slot-decoded,
   sorted by priority. `entity` is the once-pulled agent entity map."
  [entity]
  (->> (:seon.agent/ctx entity)
       (map decode-section)
       (sort-by :seon.ctx/priority)
       vec))

(defn- merge-sections
  "Core defaults ∪ the agent's own sections, ONE priority sort.
   Name collisions = override-by-name (the agent's entry wins — the
   deliberate escape hatch). Ties sort core-first, then by name,
   for byte-stable output."
  [defaults agent-sects]
  (let [agent-names (into #{} (map :seon.ctx/name) agent-sects)
        kept        (remove #(contains? agent-names (:seon.ctx/name %))
                            defaults)
        tagged      (concat (map #(assoc % :seon.ctx/agent? false) kept)
                            (map #(assoc % :seon.ctx/agent? true) agent-sects))]
    (vec (sort-by (juxt :seon.ctx/priority
                        :seon.ctx/agent?
                        (comp str :seon.ctx/name))
                  tagged))))

(defn- render-error-line
  "The guard's inline one-liner for a broken section."
  [section-name detail]
  (str "[" (name section-name) "] render failed: " detail))

(defn- render-section
  "Render ONE section map against `input`. String slot → verbatim;
   qualified symbol → resolve via seon.eval/lookup-value and call with
   `(assoc input :seon.ctx/section section)`. Missing fn or throw →
   the one-line error string (guard — assembly never breaks)."
  [input section]
  (let [slot (:seon.render/ai section)
        nm   (:seon.ctx/name section :unnamed)]
    (try
      (cond
        (string? slot)
        slot

        (qualified-symbol? slot)
        (if-let [f (seval/lookup-value slot)]
          (str (f (assoc input :seon.ctx/section section)))
          (render-error-line nm (str "fn " slot " does not resolve — "
                                     "define it (or fix the symbol) and "
                                     "this section self-heals next render")))

        :else
        (render-error-line nm (str ":seon.render/ai must be a string or a "
                                   "qualified symbol, got " (pr-str slot))))
      (catch :default e
        (render-error-line nm (or (.-message e) (str e)))))))

(defn- render-section-html
  "Resolve a section's `:seon.render/html` twin to hiccup via the EXISTING
   `seon.render/html-render` (symbol | literal-hiccup | else), wrapped in
   the SAME throw-to-banner guard `seon.render/render-entity-html` uses: a
   throwing twin degrades to a legible banner naming the slot + message,
   NEVER nil, NEVER vanish (vanish = banned). `base-in` is the composer's
   shared render input ({:seon.db/db :seon.agent/id …}); the section is
   assoc'd in as every section fn expects."
  [base-in section]
  (let [slot (:seon.render/html section)
        nm   (:seon.ctx/name section :unnamed)]
    (try
      (:seon.render/hiccup
        (render/html-render slot (assoc base-in :seon.ctx/section section)))
      (catch :default e
        [:div {:class (str "flex flex-col gap-1 p-3 border "
                           "border-error/40 bg-error/10 rounded")}
         [:div {:class "text-xs text-error font-mono font-bold"}
          "⚠ render error"]
         [:div {:class "text-xs font-mono text-text-300 break-all"}
          (str (name nm) " (" (pr-str slot) ") threw: "
               (or (.-message e) (str e)))]]))))

(defn- apply-agent-budget
  "Enforce [[agent-section-char-budget]] over the rendered agent
   sections. `rendered` is [{:seon.ctx/name _ :seon.ctx/agent? _
   :seon.ctx/priority _ :seon.render/text _} …] in render order.
   Truncates the lowest-priority (largest number) agent sections first,
   replacing the overflow with a loud marker; core sections pass
   through untouched."
  [rendered]
  (let [agent-total (transduce (comp (filter :seon.ctx/agent?)
                                     (map (comp count :seon.render/text)))
                               + 0 rendered)]
    (if (<= agent-total agent-section-char-budget)
      rendered
      ;; Walk agent sections lowest-priority-first, truncating until
      ;; the total fits. Each truncated section keeps a head slice +
      ;; the loud marker (a fully-dropped section would hide that it
      ;; exists — the marker teaches the agent to trim its own ctx).
      (let [order (->> rendered
                       (filter :seon.ctx/agent?)
                       (sort-by (juxt (comp - :seon.ctx/priority)
                                      (comp str :seon.ctx/name))))
            cuts  (loop [over (- agent-total agent-section-char-budget)
                         [s & more] order
                         acc {}]
                    (if (or (<= over 0) (nil? s))
                      acc
                      (let [n    (count (:seon.render/text s))
                            keep (max 0 (- n over))]
                        (recur (- over (- n keep))
                               more
                               (assoc acc (:seon.ctx/name s) keep)))))]
        (mapv (fn [{nm :seon.ctx/name txt :seon.render/text :as s}]
                (if-let [keep (and (:seon.ctx/agent? s) (get cuts nm))]
                  (assoc s :seon.render/text
                         (str (subs txt 0 keep)
                              "\n;; ⚠ [" (name nm) "] TRUNCATED — your agent "
                              "sections exceed the " agent-section-char-budget
                              "-char budget (this section was "
                              (count txt) " chars). Trim it with "
                              "(seon.agent/add-section! …) or remove it."))
                  s))
              rendered)))))

(defn assemble-context
  "Compose the LLM context — the ONE composer, called by BOTH the agent
   prompt path (`seon.agent/render-prompt`) and the inspector, so
   divergence is impossible.

   Sections = core defaults ([[core-default-ctx]]) MERGED
   with the agent's own `:seon.agent/ctx` section maps by one priority
   sort (override-by-name; merge-never-replace — core evolution
   always flows through, agent customization layers on top). The agent
   entity is pulled ONCE (sans the session log — the transcript section
   walks that separately; a bare `[*]` pull would inline every
   turn/eval component) and rides in the input map every section fn
   receives.

   The render splits at [[stable-boundary]]: every section through
   :namespaces (by merged render order) is the STABLE prefix —
   byte-stable within a session given the deterministic rendering —
   and everything after is the VOLATILE tail. `:seon.render/text` is
   the full prompt with the boundary line joined in-band between the
   halves; `:seon.render/stable-text` / `:seon.render/volatile-text`
   carry the halves separately (and [[split-context]] recovers them
   from the joined string — the provider-cache contract, task #34).
   An agent section with priority below :namespaces' lands in the
   stable half — it must render byte-stably (verbatim strings do).

   Returns
     `{:seon.render/text \"…\"
       :seon.render/stable-text \"…\"
       :seon.render/volatile-text \"…\"
       :seon.render/sections [<section-name> …]      ; render order
       :seon.render/section-texts [{:seon.ctx/name _
                                    :seon.render/text _} …]
       :seon.render/token-estimate <int>}`"
  {:malli/schema [:=> [:cat :seon.render/assemble-request]
                       :seon.render/assemble-response]}
  [{:seon.db/keys [db] :seon.agent/keys [id] :as input}]
  (let [entity   (db/pull {:seon.db/db db
                           :seon.db/pull-pattern
                           ;; Registered-but-uninstalled attrs (e.g. the
                           ;; tile slot on a store predating it) are
                           ;; silently filtered by the pull guard — safe.
                           '[:db/id :seon.agent/id :seon.agent/state
                             :seon.agent/purpose
                             :seon.agent/turns-cap :seon.agent/completed-at
                             :seon.render/ai :seon.render/html
                             :seon.render.live-tile/content
                             {:seon.agent/ctx [*]}]
                           :seon.db/ref [:seon.agent/id id]})
        sections (merge-sections (core-default-ctx)
                                 (agent-sections entity))
        base-in  (assoc input :seon.agent/entity entity)
        rendered (->> sections
                      (map (fn [section]
                             (assoc section :seon.render/text
                                    (render-section base-in section))))
                      (remove (comp str/blank? :seon.render/text))
                      vec
                      apply-agent-budget)
        ;; Stable/volatile boundary: everything through :namespaces in
        ;; merged render order is the stable prefix (see docstring).
        ;; Names are unique post-merge, so the stable set is a prefix
        ;; of `rendered` (which preserves merged order).
        names    (mapv :seon.ctx/name sections)
        stable?  (set (take (inc (.indexOf names :namespaces)) names))
        stable-text   (->> rendered
                           (filter (comp stable? :seon.ctx/name))
                           (map :seon.render/text)
                           (str/join "\n\n"))
        volatile-text (->> rendered
                           (remove (comp stable? :seon.ctx/name))
                           (map :seon.render/text)
                           (str/join "\n\n"))
        text     (cond
                   (str/blank? stable-text)   volatile-text
                   (str/blank? volatile-text) stable-text
                   :else (str stable-text "\n\n" stable-boundary
                              "\n\n" volatile-text))
        ;; HTML TWINS (debug-view-section-twins-2026-06-18): for each
        ;; NON-BLANK rendered section (post-budget, in render order) that
        ;; carries a :seon.render/html slot, resolve it through
        ;; html-render + the banner guard. Sections without an html slot
        ;; contribute no item — the right pane simply has no card for
        ;; them. Not char-budgeted (html isn't counted against the
        ;; agent's char budget); derived from the same post-budget vec so
        ;; the twin mirrors whatever text the agent sees.
        section-html
        (->> rendered
             (filter :seon.render/html)
             (mapv (fn [section]
                     {:seon.ctx/name       (:seon.ctx/name section)
                      :seon.render/hiccup  (render-section-html base-in
                                                                section)})))]
    {:seon.render/text           text
     :seon.render/stable-text    stable-text
     :seon.render/volatile-text  volatile-text
     ;; :seon.render/sections is LAYOUT PROVENANCE — every merged
     ;; section name in render order, including ones whose fn rendered
     ;; blank this turn (a suppressed section is still part of the
     ;; layout). :seon.render/section-texts carries only the non-blank
     ;; contributions (what the inspector shows).
     :seon.render/sections       names
     :seon.render/section-texts  (mapv #(select-keys % [:seon.ctx/name
                                                        :seon.render/text])
                                       rendered)
     :seon.render/section-html   section-html
     :seon.render/token-estimate (quot (count text) 4)}))

