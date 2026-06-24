(ns seon.ctx
  "Context generation — the ONE composer. The prompt IS a REPL session:
   a static system header, the loaded namespaces as the body, the
   agent's own entity as a map, what the human currently sees, the
   reactive warnings/todos, and the comment-block transcript of past
   turns + the live readline. Layout is ordered top→bottom =
   static→volatile: everything through `:namespaces` is the
   provider-cacheable prefix.

   This namespace owns:
     - the `:seon.ctx/*` section schemas (`:seon.ctx/name`,
       `:seon.ctx/priority`, the `:seon.ctx/section` map shape). The
       one slot attr is `:seon.render/ai` (string = verbatim doctrine,
       symbol = late-bound section fn); `:seon.render/html` is the
       optional debug-view twin.
     - `assemble-context` — the ONE composer. Core default sections
       MERGED with the agent's own `:seon.agent/ctx` sections by one
       priority sort (override-by-name). Render guard (a broken section
       renders an inline error line, never breaks assembly) and the
       per-agent section char budget live here.
     - the namespace-display selection rules live in their rightful
       home [[seon.ctx.namespaces]]:
       [[seon.ctx.namespaces/included-ns?]] (the ONE structural rule —
       EVERY indexed `:seon.ns` row renders EXCEPT *.internal and *-test
       ones; the library gate lives on the INDEX side) and
       [[seon.ctx.namespaces/full-source-ns?]] (which rows the boot
       indexer inlines real file text for).
     - the `:system` section (system-text / system-section — a
       byte-stable shared artifact) and the derived read API every
       section shares (messages / evals / session-evals / current-ns /
       effective-cap / format-eval-row / the eval-render caps / …) —
       every read takes the composer's `:seon.db/db` snapshot so one
       render is one db view. The other core sections live in their own
       `seon.ctx.<name>` nses: :namespaces → `seon.ctx.namespaces`,
       :your-entity → `seon.ctx.your-entity`, :live-tile →
       `seon.ctx.live-tile`, :warnings → `seon.ctx.warnings`,
       :inventory → `seon.ctx.inventory`, :relevant-source →
       `seon.ctx.relevant`, :transcript → `seon.ctx.transcript`;
       `core-default-ctx` wires them by SYMBOL (late lookup-value
       resolution), so this ns does NOT require them — they require this
       ns for the shared read API.
     - `render-namespace` — the standalone whole-namespace render
       (ns + fns + schemas + tests, :ai or :html), an agent-callable
       core capability the system prompt documents by name.

   Section fns receive ONE map:
     {:seon.db/db        <db value>
      :seon.agent/id     <id string>          ; convenience, = entity id
      :seon.agent/entity <the agent's own entity, pulled ONCE>
      :seon.ctx/section  <this section's map>} ; per-section overrides
   and return a string; \"\" suppresses the section.

   seon.agent requires this ns and re-exports the agent-facing read API
   (seon.agent/messages …)."
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

(def default-max-turns-per-loop
  "Base per-loop turn cap when the agent has no
   `:seon.agent/max-turns-per-loop` attr and `SEON_MAX_TURNS_PER_LOOP`
   is unset. Mirrors `seon.agent.loop/default-max-turns-per-loop`."
  20)

(defn effective-cap
  "The ENFORCED sliding-window per-loop cap the readline/turn-headers
   display — base cap + every inbound (human or peer) that has landed
   this wake. Mirrors `seon.agent.loop/effective-cap`, recomputed here
   from the composer's db value (requiring `seon.agent.loop` would cycle:
   fsm → seon.agent → seon.ctx). Base = `:seon.agent/max-turns-per-loop`
   on the entity, else env `SEON_MAX_TURNS_PER_LOOP`, else
   [[default-max-turns-per-loop]]. Optional `db` snapshot.

   The inbound window is bounded by the first turn stamped with the
   current wake (`:seon.agent.turn/wake`); before any turn this wake the
   window is empty and the cap is the base alone."
  ([agent-id] (effective-cap agent-id nil))
  ([agent-id db]
   (let [db    (or db @db/*conn*)
         a     (db/entity-lazy {:seon.db/db db
                                :seon.db/ref [:seon.agent/id agent-id]})
         base  (or (:seon.agent/max-turns-per-loop a)
                   (some-> (.. js/process -env -SEON_MAX_TURNS_PER_LOOP)
                           js/parseInt
                           (#(when-not (js/isNaN %) %)))
                   default-max-turns-per-loop)
         wake  (:seon.agent/wake a)
         my-eid (:db/id a)
         since (when (and wake my-eid)
                 (->> (db/query
                        {:seon.db/db db
                         :seon.db/query
                         '[:find ?at
                           :in $ ?aid ?wake
                           :where
                           [?a :seon.agent/id ?aid]
                           [?a :seon.agent/sessions ?s]
                           [?s :seon.agent.session/turns ?t]
                           [?t :seon.agent.turn/wake ?wake]
                           [?t :seon.agent.turn/at ?at]]
                         :seon.db/args [agent-id wake]})
                      (map first)
                      (sort-by #(.getTime ^js %))
                      first))
         inbounds (if (and my-eid since)
                    (or (ffirst
                          (db/query
                            {:seon.db/db db
                             :seon.db/query
                             '[:find (count ?m)
                               :in $ ?me ?cap ?since
                               :where
                               [?m :seon.agent.message/to ?me]
                               [?m :seon.agent.message/from ?f]
                               [(not= ?f ?me)]
                               [(get-else $ ?m :seon.agent.message/hops 0) ?h]
                               [(< ?h ?cap)]
                               [(get-else $ ?m :seon.agent.message/origin :human) ?o]
                               [(not= ?o :core)]
                               [?m :seon.agent.message/at ?at]
                               [(>= (.getTime ?at) (.getTime ?since))]]
                             :seon.db/args [my-eid warn/hop-cap since]}))
                        0)
                    0)]
     (+ base inbounds))))

(defn home-ns
  "Return the deterministic home-ns symbol for an agent id.
   `(home-ns \"seon\") => 'my.agent.seon`."
  [agent-id]
  (symbol (str "my.agent." agent-id)))

(defn current-session
  "Most-recent `:seon.agent.session` entity for `agent-id`. Returns nil if
   the agent has no sessions yet (fresh boot before `start-session!`).
   Optional `db` value (the composer's snapshot): section fns read the
   SAME db value the composer renders from, never reaching back to the
   live conn mid-render."
  ([agent-id] (current-session agent-id nil))
  ([agent-id db]
   (let [a (db/entity-lazy (cond-> {:seon.db/ref [:seon.agent/id agent-id]}
                             db (assoc :seon.db/db db)))]
     (last (sort-by :seon.agent.session/at (:seon.agent/sessions a))))))

;; The namespace-display selection rules (hidden-ns-name?,
;; included-ns?, full-source-whitelist, full-source-ns?, …) live in
;; their rightful home, [[seon.ctx.namespaces]] — the ns that owns the
;; namespaces section body and shares the rules with the boot indexer.

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
  "Char cap for the echoed SOURCE and captured STDOUT components of one
   eval row — neither is dereferenceable via `result/<id>`, so a large one
   is context-wasting noise. The citable RESULT BODY gets the larger
   [[result-body-render-cap]] instead. Context-SAFETY invariant: no single
   eval component may dominate the agent's whole context (one 9.7M-char
   `pull` result once blew the prompt to ~9.8M chars). Override via env
   SEON_EVAL_RENDER_CAP."
  (or (some-> (.. js/process -env -SEON_EVAL_RENDER_CAP)
              js/parseInt
              (#(when-not (js/isNaN %) %)))
      1500))

(def result-body-render-cap
  "Render cap for the CITABLE RESULT BODY — the `;;=> <value>` line every
   successful eval renders (`cap-result-body`). The result body alone gets
   this LARGER cap (vs [[eval-render-cap]] for echoed source + stdout)
   because it is the one component that (a) carries a `result/<id>`
   escape, so an over-cap body still points the agent at the whole live
   value; and (b) is already row-capped at 50 elements upstream
   (`seon.eval/render-result-edn`), so a body this size is STRUCTURED, not
   a wall of text.

   16384 currently EQUALS `seon.eval/store-edn-cap`, so a stored result
   renders WHOLE — but this is a CROSS-REFERENCE, NOT an alias. The render
   cap (an LLM-facing read-time projection) and `store-edn-cap` (the
   write-time per-datom anti-OOM RAM ceiling) are different tiers: this
   one is independently tunable down for token economy WITHOUT moving the
   RAM ceiling. Override via env SEON_RESULT_BODY_RENDER_CAP."
  (or (some-> (.. js/process -env -SEON_RESULT_BODY_RENDER_CAP)
              js/parseInt
              (#(when-not (js/isNaN %) %)))
      16384))

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
  "Per-message rendered-content char cap for a `;;; ◀ from X` inbound line
   in the transcript: each inbound message must be individually bounded or
   a single pasted blob could blow the context. 4000 (≈1k tokens) keeps
   any realistic chat turn whole; the full content stays in the db
   ((seon.agent/messages)). Override via env SEON_MESSAGE_RENDER_CAP."
  (or (some-> (.. js/process -env -SEON_MESSAGE_RENDER_CAP)
              js/parseInt
              (#(when-not (js/isNaN %) %)))
      4000))

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
   non-blank line becomes the `;;=> ✗ <headline>` output line (a COMMENTED
   result — re-evaluating the transcript runs only the forms, never an
   echoed value), every CONTINUATION line a plain `;;` comment (so a
   read-error's source slice + `^` caret stay ALIGNED — only a leading
   `;`/`⚠`/`ERROR`/`✗` marker is stripped, never the interior indentation
   the caret depends on). One crystal-clear guidance block, never a stack
   trace. Returns a single newline-joined string (\"\" when blank)."
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
        (cons (str ";;=> ✗ " (strip-marker (first lines)))
              (map #(str ";; " (strip-marker %)) (rest lines)))))))

(defn format-eval-row
  "REPL-faithful render of one eval: the form's comment-preamble as
   literal `;;` lines, the form verbatim (or the parinfer-repaired
   source), captured print output, then the value as a `;;=> <value>`
   COMMENTED output line trailing ` ;; result/<id>` (or the error as a
   `;;=> ✗ <guidance>` line). NO history prompt prefix — the live
   `<your-ns>=>` cursor lives once at the very END of the context; each
   row reads as plain
   comments + form + commented REPL output, the exact shape the system
   prompt teaches.

     ;; add 1 and 2
     (+ 1 2)
     ;;=> 3 ;; result/EVLabc-123

   The result line is a COMMENT (`;;=>`) so re-evaluating the whole
   transcript runs ONLY the forms — the values are history the runtime
   wrote, not inputs (north star: the context IS eval'able Clojure). The
   trailing ` ;; result/<id>` is the LIVE VAR HANDLE: the agent references
   `result/<id>` directly to reuse the value. PRIOR-SESSION evals
   (`prior?` true) render the value WITHOUT the handle (their vars died
   with the restart; the resume boundary marker says so once). A clipped
   value appends `(N of M)` to the handle so the agent knows the display
   is a partial view.

   FAILURES render `;;=> ✗ <crystal-clear guidance>` (never a stack trace,
   never a `;; result/<id>` — there is no value to reuse): the
   pre-rendered legible `:seon.eval/error` string (read/compile/runtime —
   crystal-clear at the source) or a Malli instrumentation envelope via
   `render-malli-error`. A COMMENT-ONLY row (blank source — trailing
   `;;` lines / bare prose the agent typed with no following form)
   renders just its `;;` preamble, no form, no output.

   Render caps SPLIT BY COMPONENT (context-SAFETY invariant — agent
   code can return literally anything): echoed source (`form-ln`) and
   captured stdout (`out-ln`) cap at the smaller [[eval-render-cap]]
   (neither is dereferenceable via `result/<id>`, so a large one is just
   context-wasting noise), while the CITABLE RESULT BODY caps at the
   larger [[result-body-render-cap]] (the store ceiling, so a stored
   result renders WHOLE — it is the one row-capped,
   `result/<id>`-dereferenceable component). Error/guidance bodies stay
   at [[eval-render-cap]] (a failure has no value to cite).

   On SUCCESS a reactive 'won't persist' note is DERIVED from the eval's
   source via [[seon.eval/scratch-def-note]] and appended as a trailing
   `;;` line — pure, no stored attr, recomputed each render so it FOLLOWS
   the form. The repair `↻ auto-balanced …` breadcrumb (when a span was
   parinfer-repaired) rides in the preamble, keeping a wrong-but-valid
   repair catchable."
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
             ;; Prefix ONLY the first line with `;;=>` + handle; continuation
             ;; lines (a clip's own `;;` guide) stay as the body wrote them.
             ;; `;;=>` is a COMMENT — the value is runtime history, never a
             ;; form to re-run.
             (str ";;=> " (first lines) handle
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

           :else ";;=> ✗ <no result>")
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
   attr. Queries the message log DIRECTLY (standalone inbound messages
   never attach to a turn, so a turn-walk would miss them). The from/to
   refs are pulled with their id attrs so transcript labeling resolves by
   ref kind. Default {:seon.agent/n 50}. Optional `:seon.db/db` — the
   composer threads its render snapshot here so every section reads the
   SAME db value."
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
   the transcript's cross-restart read (prior-session evals render too,
   behind a resume boundary marker). Walks agent → sessions → turns →
   evals. Optional `db` snapshot."
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
   oldest-first. Walks :seon.agent.session/turns → :seon.agent.turn/evals.
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
  "The ONE universal system block — the concept paragraphs plus the
   standing behavioral teachings, and nothing else. Usage teaching lives
   in the rendered namespace sources (docstrings + `;;` comments) and the
   startup tutorial evals, never here.

   BYTE-IDENTICAL for every agent and every turn — a `def`, not a fn of
   the agent: the agent id lives in the transcript readline at the very
   END of the prompt, so this block is one shared cacheable artifact
   across the whole cluster. CACHE-PREFIX invariant: no timestamps, no
   ids, no counts. PROVIDER-NEUTRAL: no model or vendor words, ever."
  (str
    ";;; ── system ──────────────────────────────────────────────────\n"
    ";;; You are at a live Clojure REPL on one human's runtime. The REPL is\n"
    ";;; your only tool: everything you do — read, compute, store, reply,\n"
    ";;; render — is a Clojure form evaluated here. It is ClojureScript in a\n"
    ";;; long-running Node process: you have full js/ interop (js/fetch,\n"
    ";;; js/Date, (js/require \"node:fs\") and any installed Node module) but\n"
    ";;; NO JVM — no java.*, no Java class. Reach for a Node module or a js/\n"
    ";;; builtin, never a java.* import.\n"
    ";;;\n"
    ";;; THE LIVE CONTEXT SYSTEM. This whole prompt re-derives from the\n"
    ";;; shared database every turn: every section is a view of NOW, not an\n"
    ";;; accumulating log. Fix a problem and its warning vanishes; store\n"
    ";;; data and the next render shows it; other agents' writes appear on\n"
    ";;; your next turn. The status line and a clean REPL prompt\n"
    ";;; (<your-ns>=>) are the very END of this context — your reply is the\n"
    ";;; next REPL input.\n"
    ";;;\n"
    ";;; THE TRANSCRIPT IS ONE EVAL'ABLE REPL SESSION. The whole bottom of\n"
    ";;; this context is your live REPL history: ;; comments, the forms you\n"
    ";;; wrote, and each form's value on the next line as a ;;=> ... comment.\n"
    ";;; Re-evaluating it would run only the forms (the comments pass through),\n"
    ";;; reproducing your state — it is a replayable program. You write two\n"
    ";;; things: Clojure (forms) and ;; comments. The runtime writes the rest\n"
    ";;; around your forms: the ;;; -- turn N -- headers, the ;;; <- inbound\n"
    ";;; message lines, each form's ;;=> value result, and the <your-ns>=>\n"
    ";;; cursor at the very end. Just write the form; its ;;=> value arrives\n"
    ";;; on the turn AFTER you write it. To reuse a value, name its\n"
    ";;; result/<id> var (below) — that is how results flow forward.\n"
    ";;;\n"
    ";;; EVAL MECHANICS. A form RUNS only if it starts with ( on a new line —\n"
    ";;; (foo ...), and the reader shorthands @x '(...) #(...) #'x. Everything\n"
    ";;; else is treated as a NOTE, not run: a sentence, a bare value, AND a\n"
    ";;; bare data literal you paste ({...}, [...], #{...}) — these do NOT\n"
    ";;; evaluate and produce NO result. To use a value, wrap it in a form:\n"
    ";;; (def x {...}) or (identity {...}).\n"
    ";;;\n"
    ";;; After your LAST form, STOP. The runtime runs each form and shows you\n"
    ";;; the real ;;=> value next turn — read it then. If a reply DEPENDS on\n"
    ";;; a value you have not computed yet, query this turn and reply from the\n"
    ";;; REAL result on a later turn; the runtime writes the values, you write\n"
    ";;; the forms.\n"
    ";;;\n"
    ";;;   Correct shape:                 Wrong shape (don't do this):\n"
    ";;;     ;; first, look around          Let me look around first.\n"
    ";;;     (seon.db/query ...)            (seon.db/query ...)\n"
    ";;;     ;; then, tell my human         Now I'll write the reply.\n"
    ";;;     (message/user \"...\")           (message/user \"...\")\n"
    ";;;\n"
    ";;; THINK IN COMMENTS. The ;; lines BEFORE each form are where your\n"
    ";;; reasoning lives — what you are about to do and why. If you write a\n"
    ";;; sentence, put ;; in front of every line of it. Two characters carry\n"
    ";;; reader meaning and derail the eval if they appear loose (outside a\n"
    ";;; string): a backtick begins a syntax-quote, and a markdown code fence\n"
    ";;; makes the reader syntax-quote your prose and choke. Narrate plainly —\n"
    ";;; no fences, no backticks around forms; name keywords as ordinary text\n"
    ";;; — the :seon.db/tx-data key, never a backticked span. Markdown inside\n"
    ";;; a (message/user \"...\") string is fine, though — that renders on your\n"
    ";;; human's screen.\n"
    ";;;\n"
    ";;; REPORT THE VALUE YOUR LAST EVAL RETURNED. A number you state to your\n"
    ";;; human — a count, a total, an id — must be the ;;=> value the runtime\n"
    ";;; just wrote, never one you remember or read off source. To confirm a\n"
    ";;; figure, eval the form and quote its real result; do not retype a\n"
    ";;; value you have not just seen returned.\n"
    ";;;\n"
    ";;; RESULT VARS. Every eval's value is a live var result/<id>, where the\n"
    ";;; id is the short handle the runtime prints on that form's ;;=> result\n"
    ";;; line in the transcript history. Reference result/<id> directly to\n"
    ";;; reuse a value — it is faster and surer than re-running a form you\n"
    ";;; already computed. A clipped display is NOT a clipped value: dig into a\n"
    ";;; big result with ordinary Clojure (get-in, filter, count) on its\n"
    ";;; result/<id> var instead of re-querying. A printed value is also a\n"
    ";;; SUMMARY, not the live object: a datahike db/datom/entity in a result\n"
    ";;; shows as a small placeholder (e.g. {:seon.eval/opaque \"datahike/DB\"}\n"
    ";;; or {:seon.eval/datom [...]}) — the real handle lives in result/<id>.\n"
    ";;; Reach for the result/<id> var when you want the value again.\n"
    ";;;\n"
    ";;; STATE ACROSS TURNS. A (defn ...) and an atom def like (def !x (atom\n"
    ";;; 0)) persist in your namespace — define a helper now, call it next\n"
    ";;; turn. A bare (def x 42) does NOT survive being read back on a later\n"
    ";;; turn (a self-host limitation); hold mutable values in an atom, not a\n"
    ";;; bare def.\n"
    ";;;\n"
    ";;; ERRORS ARE VALUES. Core calls never throw at you — a failure\n"
    ";;; comes back as data, e.g. {:seon.db/ok? false :seon.db/error ...}.\n"
    ";;; Read the error map; it names the defect and the fix. Telling your\n"
    ";;; human something \"threw an exception\" when you were handed an error\n"
    ";;; envelope is wrong — nothing was thrown; the failure is a value you\n"
    ";;; read.\n"
    ";;;\n"
    ";;; THE RENDERING SYSTEM. You show your human things with render\n"
    ";;; twins: :seon.render/ai (text for you) + :seon.render/html (hiccup\n"
    ";;; for their screen) — one render, two surfaces. Your live tile and\n"
    ";;; your context sections both ride this shape. A *section* (not just\n"
    ";;; the tile) can carry an :seon.render/html twin — that is where rich\n"
    ";;; panels (tables, images, SVG) go: the agent reads the :ai text, the\n"
    ";;; human sees the :html panel, one section row serving both.\n"
    ";;;\n"
    ";;; THE SHARED STORE. All agents are wired to ONE shared datahike\n"
    ";;; (datomic-style) database; *conn* is ambient — your universe binds\n"
    ";;; it, never thread it. seon.db is rendered below; its docstrings are\n"
    ";;; the API reference. Two laws worth stating once: register an\n"
    ";;; attribute (seon.schema/register!) BEFORE the first transact that\n"
    ";;; uses it, and give every attribute keyword a namespace of at least\n"
    ";;; two dot-separated segments (:my.kb.doc/title — never :doc/title,\n"
    ";;; never :title).\n"
    ";;;\n"
    ";;; COMMON DB OPS — the seon.db docstrings below are the full reference;\n"
    ";;; this is the cheat sheet. *conn* is ambient (never pass it); reads\n"
    ";;; are synchronous, transact! hands back a VALUE envelope.\n"
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
    "  (seon.db/query '[:find ?t :where [?e :my.kb.doc/title ?t]])       ; #{[v] ...}\n"
    "  (seon.db/query '[:find ?t . :where [?e :my.kb.doc/title ?t]])     ; one value\n"
    "  (seon.db/query '[:find [?t ...] :where [?e :my.kb.doc/title ?t]]) ; one column\n"
    "  ;; :in inputs go AFTER the query (db still omitted):\n"
    "  (seon.db/query '[:find ?t :in $ ?id\n"
    "                   :where [?e :my.kb.doc/id ?id] [?e :my.kb.doc/title ?t]] \"d1\")\n"
    "  ;; Logic vars (?e ?t) stay symbols ONLY inside the quoted '[...]\n"
    "  ;; vector; an empty #{} usually means a misspelled attr — copy the\n"
    "  ;; keyword EXACTLY from the stored-data inventory or the register! form.\n"
    "\n"
    "  ;; PULL / ENTITY — by lookup ref [identity-attr value] or :db/id.\n"
    "  (seon.db/pull '[*] [:my.kb.doc/id \"d1\"])   ; {:db/id N :my.kb.doc/title \"...\"}\n"
    "  (seon.db/entity [:my.kb.doc/id \"d1\"])      ; plain map: :db/id + attrs\n"
    "\n"
    "  ;; WILDCARD + COMPONENTS — '[*] inlines COMPONENT children as full\n"
    "  ;; nested maps (recursively); a PLAIN ref comes back as {:db/id N}.\n"
    "  ;; Pull a plain ref's fields by NAMING it: '[* {:my.kb.doc/author [*]}].\n"
    "\n"
    ";;; THE NAMESPACES BELOW are real loaded code, CURATED to what you use:\n"
    ";;; shown in FULL are your own my.* code, the third-party business code,\n"
    ";;; YOUR current namespace, and a small set of seon.* tools you call\n"
    ";;; directly — each its whole file. The rest of the seon framework is NOT\n"
    ";;; shown here, but it stays QUERYABLE and SEARCHABLE — never hallucinate a\n"
    ";;; fn name; discover it. To find or read any non-shown ns or fn:\n"
    ";;;   (seon.agent.search/grep {:seon.agent.search/pattern \"defn store-\"})\n"
    ";;;   (seon.db/store-inventory {:seon.db/system? true})  ; every indexed kind\n"
    ";;;   (seon.ctx/render-namespace {:seon.ns/name :seon.warn})  ; whole-ns view\n"
    ";;; Full namespaces are ordered by RECENCY — most-recently-modified LAST,\n"
    ";;; not dependency order; the runtime loaded them correctly. Namespaces are\n"
    ";;; workspaces: (ns my.domain.thing) moves you there and your context\n"
    ";;; follows your namespace. Your code is my.*, your knowledge is my.kb.*\n"
    ";;; (real schemas per domain); the core is seon.* — call its fns, never\n"
    ";;; redefine them.\n"
    ";;;\n"
    ";;; EVERY rendered element shows its id — you can (seon.db/pull '[*] <id>)\n"
    ";;; it to see the full row, or transact onto it. The context is the\n"
    ";;; database rendered; ids are live handles, not labels.\n"
    ";;;\n"
    ";;; STANDING TEACHINGS:\n"
    ";;; - Consult stored knowledge FIRST — it is DISCOVERABLE, not dumped:\n"
    ";;;   the stored-data inventory lists every stored KIND + its attrs (run\n"
    ";;;   (seon.db/store-inventory) — your creation turn already did), so\n"
    ";;;   you READ the rows by QUERYING rather than from a wall of text.\n"
    ";;;   Datalog the existing attrs for\n"
    ";;;   anything you need. The inventory lists the data added AFTER\n"
    ";;;   bootstrap; the full system inventory — the core's own\n"
    ";;;   fn/schema/test index included — is one call away:\n"
    ";;;   (seon.db/store-inventory {:seon.db/system? true})\n"
    ";;;   Prior agents already answered many questions; re-deriving a\n"
    ";;;   stored answer is wasted turns.\n"
    ";;; - Store what you verify, without being asked: design (or reuse) a\n"
    ";;;   my.kb.<domain> schema, reference the shared :my.kb/* provenance\n"
    ";;;   attrs, and transact the fact. Knowledge nobody stored is\n"
    ";;;   research the next agent pays for again.\n"
    ";;; - Keep fns small and tight. Add ONE concise :test usage example to\n"
    ";;;   a new defn ONLY when its :malli/schema doesn't already make the\n"
    ";;;   call obvious (generic shapes like [:int]->:boolean):\n"
    ";;;   (defn f {:malli/schema S :test (fn [] (assert (= 4 (f 2 2))))}\n"
    ";;;     [a b] ...). It is what the NEXT agent sees INSTEAD of your body, so\n"
    ";;;   it must be self-explanatory AND pass (a failing example surfaces).\n"
    ";;; - A task with 2+ steps: mint one todo per step with\n"
    ";;;   seon.agent.todo/add! BEFORE you start, and complete! each id as\n"
    ";;;   the step lands. Your open todos render every turn with their\n"
    ";;;   ids; once none remain, that section vanishes — your done-signal.\n"
    ";;; - Your messages render as markdown on your human's screen — use\n"
    ";;;   structure when it helps (short headings, lists, code fences for\n"
    ";;;   code or data); plain prose otherwise.\n"
    ";;;\n"
    ";;; MESSAGING + LIFECYCLE. You talk to people and you end your work with\n"
    ";;; explicit verbs — all plain Clojure, all through the DB:\n"
    ";;;   (message/user \"...\")            ; tell your one human — they see it now\n"
    ";;;   (message/agent \"<id>\" \"...\")    ; tell a specific peer agent\n"
    ";;;   (wait \"note\")                   ; park; you resume when a message arrives\n"
    ";;;   (complete \"result\")             ; finish this work cleanly\n"
    ";;; - TELL YOUR HUMAN with (message/user \"...\"). They see exactly what you\n"
    ";;;   send, when you send it — so send the answer when you have it, and a\n"
    ";;;   SHORT progress note when a longer task is still running. You can\n"
    ";;;   message every turn when they need to stay informed; silence across\n"
    ";;;   many turns is the failure, a one-line update is cheap.\n"
    ";;; - TALK TO A PEER with (message/agent \"<agent-id>\" \"...\"). Messaging\n"
    ";;;   YOURSELF is refused — your notes-to-self are just ;; comments in\n"
    ";;;   your turn.\n"
    ";;; - WHEN YOU ARE DONE, say so with a verb. (complete \"result\") marks\n"
    ";;;   the work finished. (wait \"what you're waiting for\") parks you\n"
    ";;;   until the next message wakes you — use it after you've asked a\n"
    ";;;   question and need their answer to continue. If you simply have\n"
    ";;;   nothing more to do this loop, emit NO forms — the loop ends cleanly\n"
    ";;;   and you go idle until the next message. You stay wakeable in every\n"
    ";;;   one of these states: a new message always brings you back.\n"
    ";;; - A message is REAL once it lands — but errors-are-VALUES: a transact\n"
    ";;;   can SUCCEED as an eval yet return {:seon.db/ok? false} (the write\n"
    ";;;   did NOT happen). Confirm an envelope is {:seon.db/ok? true} before\n"
    ";;;   you tell your human it landed.\n"
    ";;; - TURNS ARE PRECIOUS — each turn is a full round-trip; don't spend one\n"
    ";;;   exploring when the answer is already in front of you. If your\n"
    ";;;   context CLEARLY contains the answer (it's in the soul, the\n"
    ";;;   inventory, a loaded ns, or the transcript above), ANSWER this turn —\n"
    ";;;   (message/user \"...\") — don't re-research what you can already see. But\n"
    ";;;   if the answer is NOT plainly present — anything about stored\n"
    ";;;   knowledge, your human's data, the codebase, or specifics you haven't\n"
    ";;;   read this turn — QUERY FIRST (store-inventory + datalog), THEN answer\n"
    ";;;   from what you found.\n"
    ";;; - THE PER-LOOP CAP IS A SLIDING WINDOW. A loop runs up to a base cap\n"
    ";;;   of turns, and EVERY message you receive (from your human or a peer)\n"
    ";;;   grants one MORE turn — so a fresh message always buys you a turn to\n"
    ";;;   see and respond to it. The readline shows loop K/cap. As you near\n"
    ";;;   the cap, wrap up: (complete \"...\") or (wait \"...\").\n"
    ";;; - Building tile or panel hiccup from a sequence: splice children\n"
    ";;;   with (into [:div ...] children), never nest a bare seq as one\n"
    ";;;   child. Eval your render fn once at the REPL to eyeball the hiccup\n"
    ";;;   before you wire it onto a surface.\n"
    ";;; - WRITE FORMS; READ VALUES. You write Clojure forms and ;; comments;\n"
    ";;;   the runtime writes each form's value on the next line as ;;=> ...,\n"
    ";;;   next turn. So to learn a count, a reply, or a query result: write\n"
    ";;;   the form this turn, read its ;;=> value next turn, and act on the\n"
    ";;;   REAL value. That is the whole loop — the values are the runtime's to\n"
    ";;;   write, always real, always current.\n"
    ";;; - When you store a my.kb.* fact, grade it: record HOW you know it\n"
    ";;;   (a :my.kb/source) and HOW SURE you are (a :my.kb/confidence). A\n"
    ";;;   guess stored as fact is worse than no fact — the next agent\n"
    ";;;   cannot read your certainty from your phrasing.\n"
    ";;; ─────────────────────────────────────────────────────────────"))

(defn system-section
  "The universal system header — returns [[system-text]] verbatim.
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
   doc, and (at `:full` detail) full source only when small. Reuses the
   conventional signature shape via `seon.handlers.fn/render-ai` is
   overkill here (that fn also runs test-status queries); we render flat
   + bounded.

   `detail` (default `:full`) selects how much body to show:
     - `:full`      — header + clipped first-doc-line + full source when
                      small (the original whole-ns behavior, unchanged).
     - `:signature` — header + flags + clipped first-doc-line; the body
                      is NEVER inlined (the API-surface manifest view)."
  ([f] (fn-block-ai f :full))
  ([{:seon.fn/keys [sym arglists doc source private? spec schema-error]} detail]
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
         small? (and (= detail :full)
                     source (<= (count source) fn-source-inline-threshold))
         lines  (cond-> [header]
                  (and doc (not (str/blank? doc)))
                  (conj (str ";; " (clip (first (str/split-lines doc)) member-doc-clip)))
                  small?
                  (conj (str/trim source)))]
     (str/join "\n" lines))))

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
   keyword; `data` is the `pull-ns-data` result (or nil = not in db).

   `detail` (default `:full`) selects the depth of each fn body and the
   shape of the whole block:
     - `:full`      — a `;; ── namespace x ──` header, the ns's `(ns …)`
                      SOURCE plus every fn (full source when small),
                      schema, and test. The whole-ns view — the agent's
                      own / my.* / acme / current code.
     - `:signature` — a `;; ── namespace x (signatures) ──` header
                      carrying ONLY each fn's SIGNATURE (header + flags +
                      one-line doc, bodies elided). No ns source, no
                      schemas, no tests — the public-API manifest view of
                      a framework ns."
  ([ns-kw data] (render-one-ns-ai ns-kw data :full))
  ([ns-kw data detail]
   (if (nil? data)
     (str ";; requires: " (name ns-kw) " (not in db)")
     (let [src     (:seon.ns/source data)
           fns     (->> (:seon.fn/_ns data)     (sort-by :seon.fn/sym))
           schemas (->> (:seon.schema/_ns data) (sort-by (comp str :seon.schema/key)))
           tests   (->> (:seon.test/_ns data)   (sort-by :seon.test/sym))]
       (if (= detail :signature)
         ;; manifest view: public fn signatures only, bodies elided.
         (let [pub  (remove :seon.fn/private? fns)
               sigs (map #(fn-block-ai % :signature) pub)]
           (str ";; ── namespace " (name ns-kw) " (signatures) ──\n"
                (if (seq sigs)
                  (str/join "\n\n" sigs)
                  ";; (no public fns indexed yet — query by name)")))
         ;; full view: ns source + every member.
         (let [body (cond-> []
                      (and src (not (str/blank? src)))
                      (conj (str/trim src))
                      (seq fns)
                      (into (map #(fn-block-ai % :full) fns))
                      (seq schemas)
                      (into (map schema-block-ai schemas))
                      (seq tests)
                      (into (map test-block-ai tests)))]
           (str ";; ── namespace " (name ns-kw) " ──\n"
                (if (seq body) (str/join "\n\n" body) ";; (no recorded source/fns/schemas)"))))))))

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
      ;; The handlers are CONVERTERS now — they return BARE hiccup (keystone),
      ;; called with the entity under :seon.render/node.
      (into
        [:section {:class "py-1 border-l-2 border-base-700 pl-2"}
         (h-ns/render-html {:seon.db/db db :seon.render/node ns-ent})]
        (concat
          (for [f fns]
            (h-fn/render-html {:seon.db/db db :seon.render/node f}))
          (for [s schemas]
            (h-schema/render-html {:seon.db/db db :seon.render/node s}))
          ;; Tests rendered via the per-kind handler — same `test-status`
          ;; source as the AI path, so the pass/fail pill never diverges.
          (for [t tests]
            (h-test/render-html {:seon.db/db db :seon.render/node t})))))))

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
(schema/register! :seon.render/detail [:enum :full :signature])

(schema/register! ::render-namespace-request
  [:map
   [:seon.ns/name        :seon.ns/name]
   [:seon.render/depth   {:optional true} :seon.render/depth]
   [:seon.render/format  {:optional true} :seon.render/format]
   [:seon.render/detail  {:optional true} :seon.render/detail]
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
      :seon.render/detail <:full | :signature, default :full>
      :seon.db/db <db value, optional — defaults to @*conn*>}

   → {:seon.render/text <string>}     for :ai
   → {:seon.render/hiccup <hiccup>}   for :html

   `:seon.render/detail` (`:ai` form only) selects how much of each fn
   shows: `:full` (default) renders the ns SOURCE + every member with
   small fn bodies inlined — the whole-ns view; `:signature` renders a
   `;; ── namespace x (signatures) ──` block of public fn signatures only
   (bodies elided) — the API-surface manifest view.

   This is the foundation of every agent's default context; the section
   that surfaces the agent's namespaces resolves to it."
  {:malli/schema [:=> [:cat ::render-namespace-request] ::render-namespace-response]}
  [{ns-name :seon.ns/name
    :seon.render/keys [depth format detail]
    :seon.db/keys [db]
    :or {depth 1 format :ai detail :full}}]
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
                          (render-one-ns-ai k (data-by-kw k) detail)))})))
(defn- latest-live-inbound
  "The latest LIVE inbound message for `my-eid` in db value `db` as
   [at content] — to ∋ me, from ≠ me, hops < `warn/hop-cap` (the same
   window the loop's cap policy counts against). nil when none."
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

;; `:seon.db/db` (the registered `:any` db-value boundary, seon.render) +
;; `:string` id — so the schema compiles regardless of cross-ns load order
;; (referencing `:seon.agent/id`, registered later in seon.agent, would break
;; a fresh build).
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

(schema/register! :seon.render/split-response
  [:map
   [:seon.render/stable-text   :seon.render/stable-text]
   [:seon.render/volatile-text :seon.render/volatile-text]])

(def stable-boundary
  "The in-band cache-boundary line the composer joins between the
   STABLE prefix (every section through :namespaces — byte-stable
   within a session given the deterministic rendering) and the
   VOLATILE tail (everything after: your-entity, live-tile, warnings,
   open-todos, relevant-source, inventory, transcript).

   In-band because the agent loop hands providers ONE assembled
   string (`llm-fn` is fn-of-ctx-string): [[split-context]] recovers
   the two halves on the provider side, so an adapter can put the stable
   prefix in a cached system block and send only the volatile tail as the
   user message. Built by concatenation so the marker can never appear
   verbatim in rendered source text."
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
   agent — ordered top→bottom = static→volatile (the provider-cache
   contract): everything through :namespaces is the cacheable prefix.

     1. :system      — the universal concept paragraphs + standing
                       teachings; byte-identical for every agent and turn
                       (the agent id lives in the transcript readline)
     2. :namespaces  — THE BODY: one `;; ── namespace x ──` block per
                       included ns, recency-ordered (most-recently-
                       modified LAST), curated full/signature per ns
     3. :your-entity — the agent's own entity as a pretty-printed map
                       (purpose, tile wiring, sections, self-notes)
     4. :live-tile   — what your human currently sees
     5. :warnings    — current problems; reactive, vanishes when fixed
     6. :open-todos  — the agent's open work items; derived, vanishes
     6b. :relevant-source — env-gated (SEON_EMBED, default-OFF): the
                       top-k entities nearest this turn's query by
                       embedding KNN, PREFETCHED in run-turn! + read from
                       the per-turn stash. VOLATILE half; blank when off
                       or no hits (dropped)
     9. :inventory   — the cheap stored-data map: one line per stored
                       KIND with each attr's live row count (user-domain
                       first); vanishes when the store holds no
                       post-bootstrap data
    10. :transcript  — the comment-block REPL: the masthead, PAST turns,
                       and the folded live readline — the whole bottom of
                       the context (absorbs the old prompt/turns/status
                       sections)

   Smallest priority renders first."
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
   {:seon.ctx/name :open-todos   :seon.ctx/priority 45
    :seon.render/ai 'seon.agent.todo/open-todos-section}
   {:seon.ctx/name :relevant-source :seon.ctx/priority 48
    :seon.render/ai 'seon.ctx.relevant/relevant-source-section}
   {:seon.ctx/name :inventory    :seon.ctx/priority 97
    :seon.render/ai 'seon.ctx.inventory/inventory-section}
   ;; The transcript is the WHOLE bottom of the context (priority 100,
   ;; LAST): the comment-block REPL with the masthead at its head and the
   ;; folded live readline at its very end — it ABSORBS the prompt + turns
   ;; + status into ONE steering surface (no separate sections).
   {:seon.ctx/name :transcript   :seon.ctx/priority 100
    :seon.render/ai 'seon.ctx.transcript/transcript-section
    :seon.render/html 'seon.ctx.transcript/transcript-section-html}])

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

;; ── section gathering (subsumes the old merge-sections) ─────────────────
;; The ROOT renderable's children = core defaults ∪ the agent's own sections,
;; ONE priority sort. Name collisions = override-by-id (the agent's entry
;; wins — the deliberate escape hatch). The core's `:soul` block is NOT a
;; child here (it stays the adapter's system message — P3 moves it in).

(def stable-priority-max
  "Sections with priority ≤ this are the byte-stable cacheable PREFIX (soul
   → :system → :namespaces); the cache breakpoint falls at the transition to
   the volatile tail. Matches the old `.indexOf :namespaces` heuristic
   (:namespaces has priority 20)."
  20)

(defn- gather-sections
  "Core defaults ∪ the agent's own sections, one priority sort. Name
   collisions = override-by-id (agent wins). Ties sort core-first, then by
   name, for byte-stable output."
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

(defn- section-bracket-ai
  "The ai-view bracket the ROOT section renderer wraps each child in — the
   self-demarcating boundary that REPLACES the old per-section `;; ── x ──`
   headers. The agent can fold the left inspector pane on these lines."
  [section-name body]
  (str ";;; ┌─ " (name section-name) " ─\n"
       body
       "\n;;; └─ end " (name section-name) " ─"))

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

(defn- pull-agent-entity
  "The agent entity, pulled ONCE (sans the session log — the transcript
   section walks that separately; a bare `[*]` pull would inline every
   turn/eval component). Rides in the injected ctx so every section fn
   reads it without re-pulling. Registered-but-uninstalled attrs (e.g. the
   tile slot on a store predating it) are silently filtered by the pull
   guard — safe."
  [db id]
  (db/pull {:seon.db/db db
            :seon.db/pull-pattern
            '[:db/id :seon.agent/id :seon.agent/state
              :seon.agent/purpose
              :seon.agent/wake :seon.agent/max-turns-per-loop
              :seon.render/ai :seon.render/html
              :seon.render.live-tile/content
              {:seon.agent/ctx [*]}]
            :seon.db/ref [:seon.agent/id id]}))

(defn context-root
  "The ROOT renderable. Its children = the core section renderables
   ([[core-default-ctx]]) UNIONed with the agent's `:seon.agent/ctx`
   overrides (override-by-id) and any derived rows, sorted by static
   `:seon.ctx/priority` (subsumes the old merge-sections override-by-name).
   The agent entity is pulled once and assoc'd into ctx so every child
   reads it without re-pulling.

   Producing the prompt is rendering the root per view — there is no
   bespoke composer:
     (seon.render/render :seon.render/ai   ctx (context-root ctx))  ; String
     (seon.render/render :seon.render/html ctx (context-root ctx))  ; hiccup

   The root carries the agent entity + a stash of its sorted children;
   the root's slot fns ([[render-context-ai]] / [[render-context-html]])
   render each child through the injected recursion handle."
  [{:seon.db/keys [db] :seon.agent/keys [id] :as ctx}]
  (let [entity   (pull-agent-entity db id)
        children (gather-sections (core-default-ctx) (agent-sections entity))]
    {:seon.ctx/name          :context
     :seon.agent/entity      entity
     :seon.ctx/children      children
     :seon.render/ai         'seon.ctx/render-context-ai
     :seon.render/html       'seon.ctx/render-context-html}))

(defn- render-child-text
  "Render ONE child section to its ai text via the injected handle, carrying
   its name / agent? / priority forward for budgeting + the cache split."
  [render child]
  {:seon.ctx/name     (:seon.ctx/name child)
   :seon.ctx/agent?   (boolean (:seon.ctx/agent? child))
   :seon.ctx/priority (:seon.ctx/priority child)
   :seon.render/text  (or (render child) "")})

(defn- rendered-section-texts
  "Render each child to its ai text via `render`, drop blanks, apply the
   per-agent char budget — the post-budget per-section vector shared by the
   joined prompt ([[render-context-ai]]) and the inspector ([[ctx-sections]])
   so the two can never disagree on what each section contributes."
  [render children]
  (->> children
       (map #(render-child-text render %))
       (remove (comp str/blank? :seon.render/text))
       vec
       apply-agent-budget))

(defn render-context-ai
  "The ROOT renderable's :ai slot — the section renderer. Renders each child
   via the injected `:seon.render/render` handle, drops blanks, applies the
   per-agent char budget, brackets each section (self-demarcating — replaces
   the old `;; ── x ──` headers), and joins with the in-band [[stable-boundary]]
   inserted at the static stable→volatile `:seon.ctx/priority` transition
   (priority ≤ [[stable-priority-max]] = the cacheable prefix). [[split-context]]
   recovers the two halves on the provider side."
  [{:seon.render/keys [node render]}]
  (let [rendered  (rendered-section-texts render (:seon.ctx/children node))
        bracketed (mapv (fn [s]
                          (assoc s :seon.render/bracketed
                                 (section-bracket-ai (:seon.ctx/name s)
                                                     (:seon.render/text s))))
                        rendered)
        stable   (->> bracketed
                      (filter #(<= (or (:seon.ctx/priority %) 999)
                                   stable-priority-max))
                      (map :seon.render/bracketed)
                      (str/join "\n\n"))
        volatile (->> bracketed
                      (remove #(<= (or (:seon.ctx/priority %) 999)
                                   stable-priority-max))
                      (map :seon.render/bracketed)
                      (str/join "\n\n"))]
    (cond
      (str/blank? stable)   volatile
      (str/blank? volatile) stable
      :else (str stable "\n\n" stable-boundary "\n\n" volatile))))

(defn render-context-html
  "The ROOT renderable's :html slot — renders each child's html twin via the
   injected handle, one card per renderable (eval cards short, per-item — NOT
   a section-level dump), in render order."
  [{:seon.render/keys [node render]}]
  (into [:div {:class "flex flex-col gap-2"}]
        (->> (:seon.ctx/children node)
             (keep (fn [child]
                     (when-let [h (render child)]
                       [:section {:data-section (clojure.core/name
                                                  (:seon.ctx/name child :unnamed))}
                        h]))))))

(defn ctx-sections
  "Structured per-section breakdown for the INSPECTOR — one entry per
   non-blank section, each carrying its name + the exact ai text it
   contributes (left pane, foldable) + its html twin (right pane, one card
   per renderable). Derives from the SAME `context-root` + `render` the
   prompt uses, so the debug view can never drift from the agent's context."
  [{:as ctx}]
  (let [root     (context-root ctx)
        children (:seon.ctx/children root)
        ctx*     (assoc ctx :seon.agent/entity (:seon.agent/entity root))
        rh       #(render/render :seon.render/html ctx* %)
        ra       #(render/render :seon.render/ai   ctx* %)
        ;; Post-budget per-section texts — the SAME path the joined prompt
        ;; takes, so the inspector's left pane shows exactly what each
        ;; section contributes (TRUNCATED markers included).
        texts    (->> (rendered-section-texts ra children)
                      (mapv #(select-keys % [:seon.ctx/name :seon.render/text])))
        htmls    (->> children
                      (keep (fn [c]
                              (when-let [h (rh c)]
                                {:seon.ctx/name      (:seon.ctx/name c)
                                 :seon.render/hiccup h})))
                      vec)]
    {:seon.render/section-texts texts
     :seon.render/section-html  htmls}))

