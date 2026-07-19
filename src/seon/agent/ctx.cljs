(ns seon.agent.ctx
  "Context generation — the ONE block renderer. The prompt IS a REPL
   session: a static system header, the loaded namespaces as the body, the
   agent's own entity as a map, what the human currently sees, the
   reactive warnings/plan, and the comment-block transcript of past
   turns + the live readline. Layout is ordered top→bottom =
   static→volatile: everything through `:namespaces` is the
   provider-cacheable prefix.

   This namespace owns:
     - the `:seon.agent.ctx/*` block schemas (`:seon.agent.ctx/name`,
       `:seon.agent.ctx/priority`, the `:seon.agent.ctx/block` map shape). The
       one slot attr is `:seon.render/ai` (string = verbatim doctrine,
       symbol = late-bound block fn); `:seon.render/html` is the
       optional debug-view twin.
     - `install!` / `remove!` — the ONE scope-aware mutation surface over the
       agent's own `:seon.agent/ctx` block set; [[initial-agent-context]]
       projects the manifest-declared tree into the atomic birth transaction.
       `selected-agent-blocks` reads the agent's COMPLETE `:seon.agent/ctx`, decoded
       + priority-sorted — NO render-time merge, NO separate default set, NO
       char budget. The render guard (a broken block renders an inline error
       line, never breaks assembly) lives in [[seon.render]].
     - the namespace-display selection rules live in their rightful
       home [[seon.agent.ctx.namespaces]]:
       [[seon.agent.ctx.namespaces/included-ns?]] (the ONE structural rule —
       EVERY indexed `:seon.ns` row renders EXCEPT *.internal and *-test
       ones; the library gate lives on the INDEX side) and
       [[seon.agent.ctx.namespaces/full-source-ns?]] (which rows the boot
       indexer inlines real file text for).
     - `system-text` — the byte-stable, system-specific seon mechanics
       sent as the LLM `system` role message (via
       `seon.ai/effective-system-prompt`); NOT a context section (the
       soul/agents files are context sections via [[file-block]]) — and
       the pure formatting functions shared by acquired context data. The
       other core sections live in their own
       `seon.agent.ctx.<name>` nses: :namespaces → `seon.agent.ctx.namespaces`,
       :canvas → `seon.agent.ctx.canvas`, :warnings →
       `seon.agent.ctx.warnings`,
       :transcript → `seon.agent.ctx.transcript`;
       `config/system.edn` wires active blocks by SYMBOL (late lookup-value
       resolution), so this ns does NOT require them — they require this
       ns for the shared read API.
   Child acquisition is asynchronous over one immutable database value in
   `seon.execution.runtime`; this namespace formats only ordinary values."
  (:require
    [clojure.string :as str]
    [cljs.reader :as edn]
    [malli.core :as m]
    [malli.registry :as mr]
    [seon.agent.ctx.render-fns]
    [seon.ai.tokens :as tokens]
    [seon.config :as config]
    [seon.db :as db]
    [seon.error.instrument :as einstrument]
    [seon.eval :as seval]
    [seon.handlers.test :as h-test]
    [seon.render.schema]
    [seon.schema :as schema]
    [seon.ui.markdown :as md]))

;; ============================================================
;; Block schemas. A block is a plain map in config and a component entity on
;; the agent's :seon.agent/ctx vector.
;; ============================================================

(declare decode-block)

;; Program-graph ns rows. :seon.ns/name itself is registered in
;; seon.agent.ctx.render-fns — the FIRST-loading ns whose load-time
;; schemas reference it (this ns requires render-fns for schema ownership, so render-fns
;; loads before line 90 here would run; registering it here broke
;; every COLD pod boot while hot reloads sailed). The rest of the
;; :seon.fn/:seon.schema attr family stays in seon.agent until the P6
;; split finds them a real home.
(schema/register! :seon.ns/source  :string)
;; The ns dependency edges have ONE persisted representation: the reified
;; `:seon.ns/require-edges` component rows (registered in seon.eval,
;; written by the tee — target/alias/refers per required ns). The flat
;; "required ns-names" view is DERIVED from them at read time via
;; `seon.eval/persisted-require-targets` — the parallel flat
;; `:seon.ns/requires` attr is deleted (C36).

(schema/register! :seon.agent.ctx/name     :keyword)
(schema/register! :seon.agent.ctx/priority :int)

;; Per-BLOCK render token cap — the block-level rung of the ONE cap
;; mechanism (the per-value clip caps above it, the eval-component caps
;; below). ABSENT (every default block) → no block-level clip, byte-parity.
;; PRESENT → the block's rendered :ai text is clipped to this many tokens
;; in `rendered-child-blocks` (prompt + web UI see the same clip), with a
;; deterministic token-denominated marker at the cut. `::cap-keep` picks
;; which END survives the cut: :head (default) keeps the opening, :tail
;; keeps the newest lines (a transcript-shaped block wants the tail).
;; First consumer: the `seon.repl.autocomplete` projection profile.
(schema/register! :seon.agent.ctx/token-cap [:int {:min 0}])
(schema/register! :seon.agent.ctx/cap-keep  [:enum :head :tail])

;; The block map contract (validated at seon.agent.ctx/install! AND
;; at transact! like everything else). :seon.render/ai is the ONE slot:
;; a string renders verbatim (doctrine — content as source); a
;; qualified symbol resolves LATE via seon.eval/lookup-value at every
;; render. Optional :seon.render/html twin (symbol or hiccup literal).
(schema/register! :seon.agent.ctx/block
  [:map
   [:seon.agent.ctx/name     :seon.agent.ctx/name]
   [:seon.agent.ctx/priority :seon.agent.ctx/priority]
   [:seon.agent.ctx/token-cap {:optional true} :seon.agent.ctx/token-cap]
   [:seon.agent.ctx/cap-keep  {:optional true} :seon.agent.ctx/cap-keep]
   [:seon.render/ai    {:optional true} :seon.render/ai]
   [:seon.render/html  {:optional true} :seon.render/html]])

;; Per-agent LIVE-DB render-set OVERRIDE (cardinality-many ns-name keywords).
;; The `:namespaces` section UNIONS this onto the config-curated full set at
;; render time (`seon.agent.ctx.namespaces/db-render-set`): transact a ns
;; keyword onto an agent's row and that ns renders FULL next turn; retract and
;; it leaves. Pure reactive — derive-at-render, no stored projection, scoped
;; per-agent like install!/remove!. `[:vector :keyword]` ⇒ cardinality-many.
(schema/register! :seon.agent.ctx/render-namespaces [:vector :keyword])

;; ============================================================
;; Config-driven agent-init — agent-level composer attrs.
;;   ::escape-clipping? (#43) — WIRED (CP-5): default true frees successful
;;                     eval source/stdout caps; failed diagnostics retain the
;;                     hard eval cap and result decay governs citable values.
;;   ::cache-breakpoint — WIRED (the pure prompt formatter reads it; replaces the
;;                        stable-priority-max const).
;;
;; PARKED (config-init CP-4.5, owner three-fates = PARK — deferred, NOT inert):
;;   ::capability / ::capabilities — the per-agent capability gate over
;;   search[grep]/fs/http. Registered + kept so the shape is stable, but there
;;   is deliberately NO consumer YET: grep/fs are unconditionally available
;;   today, and per-agent capability ENFORCEMENT is a phase-2 mechanism (a
;;   check at each provider function). Tracked here as the single park note; wire the
;;   enforcement when the owner greenlights per-agent capability scoping.
;; ============================================================

(schema/register! ::capability   [:enum :grep :exec :http])          ; PARKED — register-once enum
(schema/register! ::capabilities [:vector {:default [:grep]} ::capability]) ; PARKED (phase-2 enforcement)
(schema/register! ::escape-clipping? [:boolean {:default true}])     ; #43 — successful authored small components
(schema/register! ::cache-breakpoint [:int {:default 20 :min 0}])    ; WIRED — priority ≤ this = cached prefix

;; ============================================================
;; File-section utility — the ONE mechanism for turning an on-disk
;; markdown file into a renderable context section. GENERIC: it takes a
;; file PATH (not soul, not agents — any `.md`), returns a section when
;; the file currently exists, else nil (REACTIVE: an absent file is no
;; section, NO fallback). SOUL.md and AGENTS.md are two `file-block`s
;; declared in the manifest; a third party adds another the same way.
;;
;; The file is read FRESH on every render (the path lives on the section
;; node; the slot fns re-read it), so a user's edit lands next render
;; with no seed/restart/cache. Content is byte-stable BETWEEN renders, so
;; the section keeps its place in the cacheable prefix; a save busts only
;; this block (and below). Two views: :ai → reader-valid `;`-commented
;; markdown (keeps the prompt valid Clojure source); :html → the markdown
;; rendered (`seon.ui.markdown`).
;; ============================================================

;; The on-disk path a file-block reads (relative to the pod's cwd =
;; repo root). Carried on the section node so the slot render fns re-read
;; it fresh each render.
(schema/register! :seon.agent.ctx/file-path [:string {:min 1}])

(defn- file-path->abs
  "Absolute path for repo-relative `path` (cwd = repo root, the pod
   convention). Returns nil when there is no Bun process (non-pod
   runtime)."
  [path]
  (some-> (.. js/globalThis -process) .cwd (str "/" path)))

(defn- file-exists?
  "True when `path` (resolved against cwd) is a readable file. Never
   throws — a missing fs/file just answers false."
  [path]
  (try
    (.existsSync (js/require "fs") (file-path->abs path))
    (catch :default _ false)))

(defn read-file-text
  "Live text of file `path` (resolved against cwd), or nil.

   Nil when
   unreadable (missing file). Never throws. The ONE fresh-file read every
   file-section render routes through ([[file-block-ai]] / the my.skills
   loaded-body block both re-read here each render)."
  {:malli/schema [:=> [:catn [:seon.agent.ctx/file-path :seon.agent.ctx/file-path]]
                  [:maybe :string]]}
  [path]
  (try
    (.readFileSync (js/require "fs") (file-path->abs path) "utf8")
    (catch :default _ nil)))

(def ^:private leading-marker-re
  "A leading comment marker on a line: any run of `;`/whitespace plus an
   optional leading `=>`/`⇒` result arrow + following space. Stripped only
   when `quote-lines` is asked to (`:strip-markers?`), so a stored
   comment-preamble (already carrying `;;`) re-prefixes idempotently to ONE
   `;`. The `↻` repair / `⚠` warning breadcrumb glyphs are NOT stripped —
   they carry meaning and survive the re-quote as `; ↻ …` / `; ⚠ …`."
  #"^[\s;]*(?:(?:=>|⇒)\s*)?")

(defn quote-lines
  "The ONE body-text quoter every section routes text through.

   Every section routes prose/markdown/values
   through it. Renders `text` as reader-valid Clojure comment lines so the
   whole prompt stays eval'able source: each non-blank line → `; <line>`
   (SINGLE semicolon — the owner-locked body convention), each blank line →
   a bare `;` (NO trailing space, byte-stable for the cache prefix).
   Trailing whitespace is trimmed off every line.

   Options:
     :seon.agent.ctx/strip-markers? — strip a leading `;`/`⚠`/`↻`/`=>` marker
       per line BEFORE re-prefixing (idempotent re-quote of a stored
       comment-preamble). Default false (raw text, e.g. markdown files).

   Interior indentation is preserved (only trailing whitespace is
   trimmed), so a multi-line value or an error's caret-aligned source
   slice keeps its shape under the single-`;` prefix."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :string]] :string]
                  [:=> [:cat [:maybe :string] :map] :string]]}
  ([text] (quote-lines text {}))
  ([text {:seon.agent.ctx/keys [strip-markers?]}]
   (->> (str/split-lines (or text ""))
        (map (fn [line]
               (let [line (str/trimr line)]
                 (cond
                   (str/blank? line) ";"
                   strip-markers?    (str "; " (str/replace line leading-marker-re ""))
                   :else             (str "; " line)))))
        (str/join "\n"))))

(defn file-block-ai
  "DEPRECATED — reference for the `soul` milestone; see context-rebuild.

   The `:seon.render/ai` slot for a file-block.

   The node's file read
   FRESH and `;`-commented (via [[quote-lines]]). Blank when the file
   vanished between wiring and render (the section then renders empty and
   is dropped upstream)."
  {:malli/schema [:=> [:cat :seon.render/section-request] :string]}
  [{{path :seon.agent.ctx/file-path} :seon.render/node}]
  (let [text (read-file-text path)]
    (if (str/blank? text) "" (quote-lines text))))

(defn file-block-html
  "DEPRECATED — reference for the `soul` milestone; see context-rebuild.

   The `:seon.render/html` slot for a file-block.

   The node's file read
   FRESH and rendered as markdown hiccup. Empty `[:div]` when the file
   vanished."
  {:malli/schema [:=> [:cat :seon.render/section-request] :seon.render.canvas/content]}
  [{{path :seon.agent.ctx/file-path} :seon.render/node}]
  (md/md->hiccup (or (read-file-text path) "")))

(defn file-block
  "DEPRECATED — reference for the `soul` milestone; see context-rebuild.

   A renderable context SECTION backed by a markdown file.

   At
   `:seon.agent.ctx/file-path`, named `:seon.agent.ctx/name`, ordered at
   `:seon.agent.ctx/priority` — when the file currently exists; else `nil`
   (REACTIVE, NO fallback: absent file → no section).

   The returned section carries the path + a SYMBOL slot per view; the
   slot fns ([[file-block-ai]] / [[file-block-html]]) re-read the file
   fresh on every render so a user's edit lands next turn with no
   seed/restart. GENERIC: any markdown file is a section — SOUL.md and
   AGENTS.md may each be declared as `file-block`s in a manifest; nothing
   file-name-specific lives here."
  {:malli/schema [:=> [:cat [:map
                             [:seon.agent.ctx/file-path :seon.agent.ctx/file-path]
                             [:seon.agent.ctx/name :keyword]
                             [:seon.agent.ctx/priority :int]]]
                  [:maybe [:map
                           [:seon.agent.ctx/name :keyword]
                           [:seon.agent.ctx/priority :int]
                           [:seon.agent.ctx/file-path :seon.agent.ctx/file-path]
                           [:seon.render/ai :symbol]
                           [:seon.render/html :symbol]]]]}
  [{path :seon.agent.ctx/file-path :seon.agent.ctx/keys [name priority]}]
  (when (file-exists? path)
    {:seon.agent.ctx/name      name
     :seon.agent.ctx/priority  priority
     :seon.agent.ctx/file-path path
     :seon.render/ai     'seon.agent.ctx/file-block-ai
     :seon.render/html   'seon.agent.ctx/file-block-html}))

;; Repo-relative identity-file helpers retained for explicitly declared
;; file-blocks. The primary file is
;; `SEON_SOUL_FILE` (override) else `SOUL.md`; AGENTS.md is the cross-tool
;; standard repo/work-instructions file, read alongside it. They are
;; CONTEXT, NOT the LLM system message — that is the hardcoded mechanics
;; ([[system-text]] via `seon.ai/effective-system-prompt`).
(def soul-file-path
  "Repo-relative path of the primary identity file: `SEON_SOUL_FILE`
   override, else `SOUL.md`. `nil` when `SEON_SOUL` is explicitly disabled
   (`false`/`0`/`off`/`no`) — the `:soul` block is then omitted entirely."
  (when-not (contains? #{"false" "0" "off" "no"}
                       (some-> (config/env-string "SEON_SOUL")
                               str/lower-case str/trim))
    (or (config/env-string "SEON_SOUL_FILE")
        "SOUL.md")))

(def agents-file-path
  "Repo-relative path of the cross-tool repo/work-instructions file."
  "AGENTS.md")

(defn identity-files-text
  "The LIVE text of every CURRENTLY-PRESENT identity file.

   [[soul-file-path]] then [[agents-file-path]], deduped, read FRESH on
   each call and joined with a blank line. `\"\"` when none exist. Used by
   the teachings validator to surface + validate code blocks a user places
   in the identity files. This is NOT the LLM system message — that is the
   hardcoded mechanics in [[system-text]]."
  {:malli/schema [:=> [:cat] :string]}
  []
  (->> [soul-file-path agents-file-path]
       distinct
       (filter file-exists?)
       (keep read-file-text)
       (str/join "\n\n")))

(def default-turn-limit
  "Work-bound shown by the readline when the agent has NO open run (the
   run-model default a new run would seed). Mirrors
   `seon.agent.run/default-turn-limit`."
  (:seon.config.run/batch-turn-limit (config/default-run-policy)))

(def default-form-limit
  "Work-bound shown by the idle readline in stream mode. Mirrors
   `seon.agent.run/default-form-limit` without introducing the run→ctx cycle."
  (:seon.config.run/stream-form-limit (config/default-run-policy)))

;; The namespace-display selection rules (hidden-ns-name?,
;; included-ns?, full-source-ns?, …) live in their rightful home,
;; [[seon.agent.ctx.namespaces]] — the ns that owns the namespaces section
;; body and shares the rules with the boot indexer.

;; ------------------------------------------------------------
;; Pretty-print + truncation helpers.
;; ------------------------------------------------------------

(defn host-timezone
  "IANA tz string for the running pod, or 'UTC' if Intl is unavailable."
  {:malli/schema [:=> [:cat] :string]}
  []
  (try
    (or (some-> (js/Intl.DateTimeFormat.) .resolvedOptions .-timeZone) "UTC")
    (catch :default _ "UTC")))

(def ^:private default-cache-breakpoint
  "The cache-breakpoint priority when no agent datom is present (byte-parity =
   the old `stable-priority-max` const): blocks with priority ≤ this are the
   byte-stable cacheable PREFIX (soul → :namespaces, priority 20); the provider
   cache line falls at the transition to the volatile tail."
  20)

(defn- clip-or-full
  "THE authored-content clip gate — the SINGLE place a display cap is
   applied, so the `:seon.render/full?` no-clip opt-out lives in ONE spot.
   Returns `s` UNCUT when `full?` (a block / value / eval-row pinned
   `:seon.render/full? true` to keep its content whole past the cap) OR
   when `s` already fits `limit`. Otherwise the cut is
   `seon.ai.tokens/clip-str` at the limit's token equivalent and the call
   site's LOUD `(marker budget-tokens total-tokens)` is appended, so a
   clipped display can never pass for complete content. The safety cap thus
   still fires for UNFLAGGED, genuinely-huge dumps; the flag only bypasses
   it. `limit` is CHARS (the ctx cap plumbing — `seon.config` render caps);
   markers speak TOKENS (Token Reporting rule). Nil-safe."
  {:malli/schema [:=> [:catn [::s :any] [::limit :int]
                       [::full? :boolean] [::marker 'fn?]] :string]}
  [s limit full? marker]
  (let [s (str s)]
    (if (or full? (<= (count s) limit))
      s
      (tokens/clip-str s (tokens/chars->tokens limit) marker))))

(defn truncate-edn
  "pr-str a value and truncate to ~2 KB for the eval log.

   Display only (v1.md §1's three-tier storage rule: DB datoms hold projections,
   not full content). A `full?` (`:seon.render/full? true` pinned on the
   block/value) renders the value WHOLE past the cap."
  {:malli/schema [:function
                  [:=> [:catn [::v :any]] :string]
                  [:=> [:catn [::v :any] [::limit :int]] :string]
                  [:=> [:catn [::v :any] [::limit :int] [::full? :boolean]] :string]]}
  ([v] (truncate-edn v 2048 false))
  ([v limit] (truncate-edn v limit false))
  ([v limit full?]
   (clip-or-full (pr-str v) limit full?
     (fn [budget total]
       (str " …⟨⚠ TRUNCATED at " budget " of " total " tokens — display clip, "
            "the underlying value is complete⟩")))))

(defn message-label
  "Transcript label for a message's `:seon.agent.message/from` ref.

   A pulled
   map carrying `:seon.user/id` / `:seon.agent/id`, resolved by REF
   KIND: the user → `user`, this agent itself → `assistant`, any other
   agent → `agent-<id>`."
  {:malli/schema [:=> [:catn [::from :any] [::own-id :string]] :string]}
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

(defn cap-result
  "Truncate a rendered eval-result string to `eval-render-cap`.

   Appends a LOUD truncation marker (shown of full tokens) so a
   clipped display can never pass for complete content — the observed
   failure mode is an agent summarizing INVENTED content from a
   silently-clipped render. Operates on the ALREADY-stringified result
   (`:seon.eval/result-edn` is a pr-str string), so no re-quoting.
   Nil-safe."
  {:malli/schema [:=> [:catn [::s :any] [::limit :int]
                             [::full? :boolean]] :string]}
  [s limit full?]
  (clip-or-full s limit full?
    (fn [budget total]
      (str " …⟨⚠ TRUNCATED at " budget " of " total " tokens — the DISPLAY is "
           "clipped, the underlying data is complete; do not summarize "
           "or quote beyond what is shown⟩"))))

(defn cap-result-body
  "Truncate an eval RESULT body, with a GUIDING clip message.

   Like `cap-result`, but for a body: when the
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
  {:malli/schema [:=> [:catn [::s :any] [::limit :int] [::eid :any]
                             [::full? :boolean]] :string]}
  [s limit eid full?]
  (clip-or-full s limit full?
    (fn [budget total]
      (let [ref (if eid (str "result/" eid) "result/<id>")]
        (str " …⟨⚠ TRUNCATED at " budget " of " total " tokens — the DISPLAY "
             "is clipped, the live value is COMPLETE⟩"
             "\n; bind " ref " for the full value — process it with code: "
             "(count " ref "), subs, get-in/filter, or paged take/drop. To "
             "get less next time: a :find aggregate, a tighter :where, or "
             "pull fewer attrs.")))))

(def result-marker
  "The reserved RUNTIME result-OPEN glyph `⟹` — the SINGLE SOURCE OF TRUTH.

   The runtime writes it BARE (no leading `;`) in front of a real eval
   result: `(form) ⟹ <value> ⟸ result/<id>` — everything from `⟹` through
   the `⟸` handle is the runtime's; the value is deliberately NOT
   comment-shaped, so a model can't fabricate one by writing a `;` comment
   (the `; ⟹` shape that agents copied — T4 6/24). It is RUNTIME OUTPUT
   ONLY: model-authored copies remain evidence in the raw reply but acquire no
   runtime authority. The emit site ([[format-eval-row]] +
   `seon.agent.ctx.transcript`) and [[system-text]]'s teaching both reference
   THIS def — never a bare `\"⟹\"` literal, so the marker is one swappable
   edit and cannot drift between runtime output and context teaching."
  "⟹")

(def result-close
  "The reserved RUNTIME result-CLOSE glyph `⟸` — CARRIES THE HANDLE.

   Fences the end of a real result and precedes its live-var handle:
   `⟹ <value> ⟸ result/<id>`. Reserved like [[result-marker]]: only committed
   eval facts give a rendered copy runtime authority.
   ONE swappable edit — every emit site references THIS def, never a bare
   `\"⟸\"` literal."
  "⟸")

(def status-open
  "The reserved RUNTIME per-turn-status OPEN glyph `⋘`.

   Opens the byte-stable per-turn status masthead
   `⋘ <turn-ts> · turn N · <ctx-tok> · <ns> ⋙ ❯` whose fields are each
   turn's FIXED stored values (never live-recomputed). Reserved runtime
   chrome — an agent never writes it; referenced from THIS def only."
  "⋘")

(def status-close
  "The reserved RUNTIME per-turn-status CLOSE glyph `⋙` — see [[status-open]]."
  "⋙")

(def prompt
  "The reserved RUNTIME prompt glyph `❯` — the last symbol of a status line.

   Marks where the REPL awaits input. Reserved runtime chrome (an agent
   never authors it); referenced from THIS def only, so it can never drift."
  "❯")

(def reserved-glyphs
  "The FIVE reserved result-grammar glyphs, as a set of the single-source
   constants: result-open [[result-marker]] `⟹`, result-close
   [[result-close]] `⟸`, status [[status-open]]/[[status-close]] `⋘`/`⋙`,
   and [[prompt]] `❯`. The no-mixed-references lint (`seon.dev.docstring`)
   flags a literal outside the constant defs. Distinct from the
   value-VOCABULARY glyphs
   (`⟨N tok⟩` · `‹partial›` · `#‹…›` · `{…N keys}` · `«…»`) which are NOT
   reserved and appear legitimately elsewhere (compacted values / `my.plan`)."
  #{result-marker result-close status-open status-close prompt})

(defn- error-lines
  "Render an error/guidance body `s` as the REPL FAILURE shape: the FIRST
   non-blank line becomes the bare `⟹ ✗ <headline>` output line (a RUNTIME
   failure line, not comment-shaped, so it can't be mimicked), every
   CONTINUATION line a plain `;` comment (via
   [[quote-lines]], so a read-error's source slice + `^` caret stay
   ALIGNED — only the leading comment marker is stripped, never the
   interior indentation the caret depends on). One crystal-clear guidance
   block, never a stack trace. Returns a single newline-joined string
   (\"\" when blank)."
  [s]
  (let [lines (->> (str/split-lines (str s))
                   (drop-while str/blank?))]
    (if (empty? lines)
      ""
      (let [head (-> (str (first lines))
                     (str/replace #"^\s*;+[ \t]?" "")
                     (str/replace #"(?i)^ERROR[ \t]?" "")
                     (str/replace #"^[⚠✗][ \t]?" ""))
            rest-body (quote-lines (str/join "\n" (rest lines))
                                   {:seon.agent.ctx/strip-markers? true})]
        (cond-> (str result-marker " ✗ " head)
          (seq (rest lines)) (str "\n" rest-body))))))

(defn format-eval-row
  "REPL-faithful render of one eval.

   The form's comment-preamble as
   `;` lines (via [[quote-lines]]), the form verbatim (or the
   parinfer-repaired source), captured print output, then the value as a
   BARE `⟹ <value> ⟸ result/<id>` RUNTIME line (or the error as a bare
   `⟹ ✗ <guidance>` line). The `⟹`/`⟸` glyphs are the reserved
   [[result-marker]]/[[result-close]] (see their docstrings — the runtime
   is their only writer, so a model can't fabricate a result by writing a
   `;` comment). A SHORT single-line value inlines onto the form's own
   line; a large one spans lines and its `⟸ result/<id>` handle fences it.
   NO history prompt prefix — the live
   `<your-ns>=>` cursor lives once at the very END of the context.

     ; add 1 and 2
     (+ 1 2) ⟹ 3 ⟸ result/EVLabc-123

   The result is NOT comment-shaped (the settled tradeoff: the transcript
   is no longer re-evaluable Clojure — clarity + anti-fabrication win,
   because the old `; ⟹` shape was mimicked by agents). The trailing
   `⟸ result/<id>` is the LIVE VAR HANDLE: the agent references
   `result/<id>` directly to reuse the value. An eval whose bounded runtime
   slot is unavailable (`result-unavailable?` true) renders the value WITHOUT
   a handle; this covers both an evicted result and one from a prior process.
   A clipped value appends `(N of M)` to the handle so the agent knows the
   display is a partial view.

   FAILURES render bare `⟹ ✗ <crystal-clear guidance>` (never a stack trace,
   never a `⟸ result/<id>` — there is no value to reuse): the
   pre-rendered legible `:seon.eval/error` string (read/compile/runtime —
   crystal-clear at the source) or a Malli instrumentation envelope via
   `render-malli-error`. A COMMENT-ONLY row (blank source — trailing
   `;` lines / bare prose the agent typed with no following form)
   renders just its `;` preamble, no form, no output.

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
   `;` line — pure, no stored attr, recomputed each render so it FOLLOWS
   the form. The repair `↻ auto-balanced …` breadcrumb (when a span was
   parinfer-repaired) rides in the preamble, keeping a wrong-but-valid
   repair catchable."
  {:malli/schema [:=> [:catn [::row :map]
                             [::result-unavailable? :boolean]] :string]}
  [{src        :seon.eval/source
     ok?        :seon.eval/ok?
     res        :seon.eval/result-edn
     out        :seon.eval/output
     err        :seon.eval/error
     err-data   :seon.eval/error-data
     eid        :seon.eval/id
     narr       :seon.eval/narration
     full?      :seon.render/full?
     ;; CP-3 move 4: the citable RESULT-BODY cap, selected by the eval's AGE
     ;; from the transcript block's `::result-decay` levels — computed by the
     ;; transcript converter (which reads the block) and threaded in here.
     ;; ABSENT (every non-transcript caller: tests and direct calls) →
     ;; `result-body-render-cap` (16384) = byte-identical to today.
     result-body-cap :seon.render/result-body-cap
     ;; Escape-clipping THREADED on the row (byte-exact path): the transcript
     ;; converter computes it once off ITS render db and stamps it here, so an
     ;; as-of render never reads the live conn. ABSENT (direct callers) →
     ;; the live [[escape-clipping?]] read, byte-identical to today.
     escape-override :seon.agent.ctx/escape-clipping?
     configuration :seon.config/configuration}
    result-unavailable?]
   (let [eval-render-cap (config/eval-render-cap configuration)
         result-body-render-cap (config/result-body-render-cap configuration)
         envelope    (read-error-envelope err-data)
         ;; `:seon.render/full?` (the no-clip opt-out, pinned on this eval
         ;; row) renders every authored component WHOLE past its cap. Absent
         ;; → false → byte-identical to today's clipped render.
         full?       (boolean full?)
         ;; CP-5 escape-clipping (#43, owner: "render the blocks in full"):
         ;; successful authored source/stdout may render whole when the agent's
         ;; `:seon.agent.ctx/escape-clipping?` is on (default true). A FAILED
         ;; eval is diagnostic data, not authored content: its source, stdout,
         ;; Malli body, and ordinary/read/runtime error ALWAYS keep the hard
         ;; `eval-render-cap`, under both escape-clipping and
         ;; `:seon.render/full?`. The citable successful RESULT BODY remains
         ;; governed independently by the age-decay `result-body-cap`.
         escape?     (if (some? escape-override)
                       (boolean escape-override)
                       true)
         ;; `:seon.eval/ok?` is optional while an eval row is being assembled
         ;; and on historical malformed rows.  `cap-result`'s public contract
         ;; is a boolean, so keep the derived flag boolean too; allowing
         ;; `(and nil ...)` through instrumentation used to retire the whole
         ;; execution child while merely rendering prior context.
         small-full? (boolean (and ok? (or full? escape?)))
         ;; Echoed source + stdout + error/guidance bodies cap at the
         ;; smaller `eval-render-cap` (1500); only the citable result
         ;; body below gets its age-decayed `result-body-cap`.
         limit       eval-render-cap
         comment-only? (str/blank? (str src))
         ;; Comment-preamble — the agent's `;`/prose thinking, re-prefixed to
         ;; `;`. Model-authored runtime-like text stays preserved as narration;
         ;; only committed database facts give results or events authority.
         ;; `(str narr)` coerces an off-shape stored narration (a non-string
         ;; that slipped past the write boundary) so a bad datom renders
         ;; instead of sinking the whole transcript — never throw into render.
         preamble    (when (and narr (not (str/blank? narr)))
                       (quote-lines (str narr)
                                    {:seon.agent.ctx/strip-markers? true}))
         ;; The form, verbatim (or repaired), capped. Omitted for a
         ;; comment-only row. `(str src)` coerces an off-shape source too.
         form-ln     (when-not comment-only?
                       (cap-result (str src) limit small-full?))
         ;; Captured println/prn output — shown above the value like a
         ;; real REPL prints before returning. Bounded by the same cap.
         out-ln      (when (and (string? out) (not (str/blank? out)))
                       (cap-result (str/trimr out) limit small-full?))
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
           (let [body-cap (or result-body-cap result-body-render-cap)
                 raw     (str (seval/sanitize-result-edn (or res "nil")))
                 full    (count raw)
                 ;; `full?` (the no-clip opt-out) renders the body WHOLE, so
                 ;; the `(N of M)` partial-handle marker must NOT fire.
                 clipped? (and (> full body-cap) (not full?))
                 ;; The value body — clipped (with the size guide) when
                 ;; huge, unless `full?` pins it whole. The guide carries its
                 ;; own `\n;` lines (a result/<id> dig hint), below the `=>` line.
                 v       (cap-result-body raw body-cap eid full?)
                 ;; The live VAR HANDLE rides the `=>` line as a trailing
                 ;; `; result/<id>`: the agent references `result/<id>`
                 ;; directly. Rows whose bounded result slot is unavailable
                 ;; carry NO handle (evicted or prior-process). A clip appends
                 ;; `(N of M)` so the agent knows the shown value is partial.
                 ;; The `(N of M)` clip marker is TOKENS (Token Reporting
                 ;; rule): `body-cap`/`full` are CHAR budgets (the ctx clip
                 ;; plumbing), converted here at the display site so this
                 ;; marker speaks the SAME unit as the inline `⟨… tokens⟩`
                 ;; guide `cap-result-body` appends (no mixed units in one
                 ;; row). Render-time-computed off `raw`, so aging is
                 ;; unaffected (byte-stable per fixed age).
                 handle  (when-not result-unavailable?
                           (str " " result-close " result/" eid
                                (when clipped?
                                  (str " (" (tokens/chars->tokens body-cap)
                                       " of " (tokens/chars->tokens full)
                                       " tokens)"))))
                 lines   (str/split-lines v)]
             ;; BARE `⟹ <value> ⟸ result/<id>` — the result-open marker + the
             ;; result-close handle ride the FIRST line; continuation lines (a
             ;; clip's own `;` guide, or a multi-line value's rest) stay as the
             ;; body wrote them. NOT comment-shaped: the runtime is the only
             ;; writer of a `⟹`/`⟸` line, so a model can't fabricate one.
             (str result-marker " " (first lines) handle
                  (when (next lines)
                    (str "\n" (str/join "\n" (rest lines))))))

           (einstrument/instrument-error? envelope)
           (cap-result-body
             (error-lines (einstrument/render-malli-error envelope)) limit eid small-full?)

           (and (string? err) (not (str/blank? err)))
           ;; `:seon.eval/error` is stored pre-rendered + crystal-clear
           ;; (`seon.eval/render-error-string` / `read-error-message` /
           ;; the undeclared-var message) — render as a `=> ✗` failure
           ;; line, plain-clip (NOT the "narrow your query" result guide).
           (cap-result (error-lines err) limit small-full?)

           :else (str result-marker " ✗ <no result>"))
         ;; Reactive 'won't persist' note (#7) — DERIVED from source, no
         ;; stored attr; recomputed each render so it follows the form.
         note   (when (and ok? (not comment-only?))
                  (seval/scratch-def-note src))
         ;; INLINE the result onto the form's OWN line — the PRD grammar
         ;; `(form) ⟹ <value> ⟸ result/<id>` — when everything is
         ;; single-line: a form present + single-line, no captured stdout
         ;; between them, and a single-line result line. A multi-line form,
         ;; multi-line/clipped value, or intervening stdout keeps the result
         ;; on its own line (still bare `⟹ …`), so the fence stays legible.
         inline? (and form-ln
                      (not (str/includes? form-ln "\n"))
                      (str/blank? (str out-ln))
                      (string? result-ln)
                      (not (str/blank? result-ln))
                      (not (str/includes? result-ln "\n")))
         form+result (when inline? (str form-ln " " result-ln))]
     (->> (if inline?
            [(when (not (str/blank? preamble)) preamble)
             form+result
             (when (and note (not (str/blank? note)))
               (quote-lines note {:seon.agent.ctx/strip-markers? true}))]
            [(when (not (str/blank? preamble)) preamble)
             form-ln
             out-ln
             result-ln
             (when (and note (not (str/blank? note)))
               (quote-lines note {:seon.agent.ctx/strip-markers? true}))])
          (remove nil?)
          (str/join "\n"))))

;; ------------------------------------------------------------
;; Byte-stable system instructions. Context acquisition and child invocation
;; are owned by seon.execution.runtime; this namespace formats their values.
;; ------------------------------------------------------------

(def system-text
  "The ONE always-on instruction floor — the concept paragraphs, the
   REPL/eval mechanics, and the universal load-bearing rules every agent
   needs EVERY turn (async-reads-as-synchronous, register!-before-transact,
   every-map-key-namespaced, :malli/schema-is-enforced, entities-are-
   attributes-not-kinds). Per-FUNCTION usage examples live in the rendered
   namespace sources (docstrings + `;;` comments); DEEP reference lives in
   the skill bodies, reachable by (my.skills/load :name). This block is the
   floor, not the depth — a skill only DEEPENS a floor rule.

   BYTE-IDENTICAL for every agent and every turn — a `def`, not a fn of
   the agent: the agent id lives in the transcript readline at the very
   END of the prompt, so this block is one shared cacheable artifact
   across the whole cluster. CACHE-PREFIX invariant: no timestamps, no
   ids, no counts. PROVIDER-NEUTRAL: no model or vendor words, ever."
  (str
    "; ── system ──\n"
    "; You are at a live Clojure REPL on one human's runtime. The REPL is\n"
    "; your only tool: everything you do — read, compute, store, reply,\n"
    "; render — is a Clojure form evaluated here. It is ClojureScript in a\n"
    "; long-running Bun process: you have full js/ interop (js/fetch,\n"
    "; js/Date, (js/require \"node:fs\") and any installed npm package) but\n"
    "; NO JVM — no java.*, no Java class. Reach for an npm package or a js/\n"
    "; builtin, never a java.* import.\n"
    ";\n"
    "; THE LIVE CONTEXT SYSTEM. This whole prompt re-derives from the\n"
    "; shared database every turn: every section is a view of NOW, not an\n"
    "; accumulating log. Fix a problem and its warning vanishes; store\n"
    "; data and the next render shows it; other agents' writes appear on\n"
    "; your next turn. The status line and a clean REPL prompt\n"
    "; (<your-ns>=>) are the very END of this context — your reply is the\n"
    "; next REPL input.\n"
    ";\n"
    "; THE TRANSCRIPT IS YOUR LIVE REPL HISTORY. The whole bottom of this\n"
    "; context is what you wrote (; comments and forms) with the runtime's\n"
    "; output woven around it. Each form's value is written on its own line\n"
    "; as a BARE `" result-marker " <value> " result-close " result/<id>` line — NOT a\n"
    "; comment: everything from " result-marker " through the " result-close " handle is the\n"
    "; runtime's, and the next line is yours again. You write two things\n"
    "; only: Clojure (forms) and ; comments. The runtime writes the rest\n"
    "; around your forms: each section's ;;; ┌─ … ─ / ;;; └─ end … ─\n"
    "; fold-brackets, the ;;; ◀ from / ;;; ▶ to message lines, each form's\n"
    "; " result-marker " value line, and the <your-ns>=> cursor at the very end. To\n"
    "; reuse a value, name its result/<id> var (below) — that is how\n"
    "; results flow forward.\n"
    ";\n"
    "; EVAL MECHANICS. A form RUNS only if it starts with ( on a new line —\n"
    "; (foo ...), and the reader shorthands @x '(...) #(...) #'x. Everything\n"
    "; else is treated as a NOTE, not run: a sentence, a bare value, AND a\n"
    "; bare data literal you paste ({...}, [...], #{...}) — these do NOT\n"
    "; evaluate and produce NO result. To use a value, wrap it in a form:\n"
    "; (def x {...}) or (identity {...}).\n"
    ";\n"
    "; After your LAST form, STOP. The runtime runs each form and shows you\n"
    "; the real " result-marker " value next turn — read it then. If a reply DEPENDS on\n"
    "; a value you have not computed yet, query this turn and reply from the\n"
    "; REAL result on a later turn; the runtime writes the values, you write\n"
    "; the forms.\n"
    ";\n"
    ";   Correct shape:                 Wrong shape (don't do this):\n"
    ";     ; first, look around           Let me look around first.\n"
    ";     (db/query ...)                 (db/query ...)\n"
    ";     ; then, tell my human          Now I'll write the reply.\n"
    ";     (message/user \"...\")           (message/user \"...\")\n"
    ";\n"
    "; THINK IN COMMENTS. The ; lines BEFORE each form are where your\n"
    "; reasoning lives — what you are about to do and why. If you write a\n"
    "; sentence, put ; in front of every line of it. Two characters carry\n"
    "; reader meaning and derail the eval if they appear loose (outside a\n"
    "; string): a backtick begins a syntax-quote, and a markdown code fence\n"
    "; makes the reader syntax-quote your prose and choke. Narrate plainly —\n"
    "; no fences, no backticks around forms; name keywords as ordinary text\n"
    "; — the :seon.db/tx-data key, never a backticked span. Markdown inside\n"
    "; a (message/user \"...\") string is fine, though — that renders on your\n"
    "; human's screen.\n"
    ";\n"
    "; RAW FOREIGN CODE — #code HEREDOC. To pass code in another language\n"
    "; (Python, a diff, YAML) as DATA with NO escaping of its quotes or\n"
    "; backslashes, write #code/<lang> <<WORD, the payload lines, then a line\n"
    "; that is exactly WORD — usable INLINE as any argument (e.g. fs/replace!\n"
    "; :seon.agent.fs/find / :seon.agent.fs/replace):\n"
    ";   (fs/replace! {:seon.agent.fs/path \"a.py\"\n"
    ";                 :seon.agent.fs/find #code/python <<PY\n"
    "; def f(x): return x\n"
    "; PY})              ; reads to inert data, never runs as Clojure\n"
    "; ANCHORED EDITS never guess: an ambiguous fs/replace! returns CANDIDATES\n"
    "; with line ranges instead of mutating — re-issue with :seon.agent.fs/near\n"
    "; [from to] or a longer :seon.agent.fs/find to disambiguate; never guess.\n"
    ";\n"
    "; REPORT THE VALUE YOUR LAST EVAL RETURNED. A number you state to your\n"
    "; human — a count, a total, an id — must be the " result-marker " value the runtime\n"
    "; just wrote, never one you remember or read off source. To confirm a\n"
    "; figure, eval the form and quote its real result; do not retype a\n"
    "; value you have not just seen returned.\n"
    ";\n"
    "; " result-marker " marks a REAL result — only the runtime writes it,\n"
    "; on the turn AFTER you submit a form. " result-marker " and " result-close " (and the\n"
    "; " status-open " " status-close " status glyphs) are RESERVED for the runtime —\n"
    "; NEVER type them yourself: a result you type (a " result-marker " line, or a\n"
    "; bare `=> value`) is STRIPPED from your reply at the boundary before it\n"
    "; is recorded — it never becomes a result and never reaches a later turn.\n"
    "; To see what a form returns, submit it and read the runtime's bare\n"
    "; " result-marker " line next turn. Never (complete ...) claiming a result you have not seen the\n"
    "; runtime print.\n"
    ";\n"
    "; RESULT VARS. Every eval's value is a live var result/<id>, where the\n"
    "; id is the short handle the runtime prints on that form's " result-marker " result\n"
    "; line in the transcript history. Reference result/<id> directly to\n"
    "; reuse a value — it is faster and surer than re-running a form you\n"
    "; already computed. A clipped display is NOT a clipped value: dig into a\n"
    "; big result with ordinary Clojure (get-in, filter, count) on its\n"
    "; result/<id> var instead of re-querying. A printed value is also a\n"
    "; SUMMARY, not the live object: a datahike db/datom/entity in a result\n"
    "; shows as a small placeholder (e.g. {:seon.eval/opaque \"datahike/DB\"}\n"
    "; or {:seon.eval/datom [...]}) — the real handle lives in result/<id>.\n"
    "; Reach for the result/<id> var when you want the value again.\n"
    ";\n"
    "; STATE ACROSS TURNS. A (defn ...) and an atom def like (def !x (atom\n"
    "; 0)) persist in your namespace — define a helper now, call it next\n"
    "; turn. A bare (def x 42) does NOT survive being read back on a later\n"
    "; turn (a self-host limitation); hold mutable values in an atom, not a\n"
    "; bare def.\n"
    ";\n"
    "; ASYNC READS AS SYNCHRONOUS. (await x) works only INSIDE an ^:async\n"
    "; fn — a bare top-level (await x) throws. You rarely need it: an\n"
    "; ^:async function (db/transact!, plan/*) auto-resolves to its DATA value,\n"
    "; so it reads as an ordinary synchronous call — use what it returns\n"
    "; directly.\n"
    ";\n"
    "; ERRORS ARE VALUES. Core calls never throw at you — a failure\n"
    "; comes back directly as data, e.g. {:seon.error/message \"...\"\n"
    "; :seon.error/kind :user-input}.\n"
    "; Read the error map; it names the defect and the fix. Telling your\n"
    "; human something \"threw an exception\" when you were handed an error\n"
    "; value is wrong — nothing was thrown; the failure is data you can\n"
    "; read.\n"
    ";\n"
    "; THE RENDERING SYSTEM. You show your human things with render\n"
    "; twins: :seon.render/ai (text for you) + :seon.render/html (hiccup\n"
    "; for their screen) — one render, two surfaces. Your canvas and\n"
    "; your context sections both ride this shape. A *section* (not just\n"
    "; the surface) can carry an :seon.render/html twin — that is where rich\n"
    "; panels (tables, images, SVG) go: the agent reads the :ai text, the\n"
    "; human sees the :html panel, one section row serving both.\n"
    "; A specced view returns ordinary render data. The runtime acquires its\n"
    "; database inputs at one immutable database value before formatting.\n"
    "; SHOW, DON'T TELL: your canvas (the canvas section below) is\n"
    "; your PRIMARY surface for showing your human data, results, and\n"
    "; status; (message/user \"...\") is narration/backup that scrolls away.\n"
    "; One carve-out: a surface is never a reply — a final answer must still\n"
    "; be SENT with (message/user \"...\") or (complete \"...\").\n"
    ";\n"
    "; THE SHARED STORE. All agents are wired to ONE shared datahike\n"
    "; (datomic-style) database. Laws worth stating once: register an\n"
    "; attribute (schema/register!) BEFORE the first transact that uses it,\n"
    "; and give every attribute keyword a namespace of at least two\n"
    "; dot-separated segments (:my.kb.doc/title — never :doc/title, never\n"
    "; :title) — and MORE: every key in every map you write (not just DB\n"
    "; attrs) is a namespaced keyword; bare :status / :ok are refused. An\n"
    "; entity has no kind or type — it IS its attributes + refs; never model\n"
    "; a :kind or :type field, and FIND entities by attribute presence, not\n"
    "; by a type tag.\n"
    ";\n"
    "; THE NAMESPACES BELOW are real loaded code, each delimited by its own\n"
    "; ;;; ┌─ namespace X ─ / ;;; └─ end namespace X ─ brackets, all in FULL\n"
    "; real source (no signatures, no clipping). What renders is CURATED: YOUR\n"
    "; CURRENT namespace (your live workspace, the most important thing here)\n"
    "; and the nses it :requires, the my.* toolkit (my.kb / my.data / my.ui /\n"
    "; my.canvas) and your core functions (plan / message / lifecycle). Everything\n"
    "; else — the rest of the seon framework AND your other my.* nses — is\n"
    "; deliberately NOT dumped; it stays QUERYABLE and SEARCHABLE, one step\n"
    "; away, so you are not buried in code you don't need. Never hallucinate a\n"
    "; fn name — discover it. To find or read any non-shown ns or fn:\n"
    ";   (seon.agent.search/grep {:seon.agent.search/pattern \"defn store-\"})\n"
    ";   (seon.agent.search/grep {:seon.agent.search/pattern \"seon.warn\"})\n"
    "; To PIN a ns into your always-on view, transact its keyword onto your\n"
    "; agent's :seon.agent.ctx/render-namespaces; retract it to unpin.\n"
    "; Full namespaces are ordered by RECENCY — most-recently-modified LAST,\n"
    "; not dependency order; the runtime loaded them correctly.\n"
    ";\n"
    "; BUILD YOUR ENVIRONMENT. This runtime is yours to shape. When you have\n"
    "; data worth keeping, CREATE a namespace for it — WRITE THE REAL REQUIRES\n"
    "; in the ns form so the short aliases resolve (no magic — using requires\n"
    "; is the right thing):\n"
    ";   (ns my.<domain>.<thing>\n"
    ";     (:require [seon.db :as db] [my.plan :as plan]\n"
    ";               [seon.agent.message :as message] [seon.schema :as schema]))\n"
    "; — design a schema (schema/register!), and COLOCATE the functions that\n"
    "; operate on that data in the same namespace. Your code is my.*, your\n"
    "; knowledge is my.kb.*; the core is seon.* (call it, never redefine it).\n"
    "; A public fn's :malli/schema is INSTRUMENTED — it validates the args\n"
    "; and the return on EVERY call and throws on a mismatch, so a wrong\n"
    "; schema is a runtime bug, not a doc nit.\n"
    "; The my.* toolkit (my.ui / my.canvas / my.data) is FULL-QUALIFIED — call\n"
    "; my.ui/card etc. directly, no alias needed. If a tool you need doesn't\n"
    "; exist, write it and run it — don't wait to be given one. Namespaces are\n"
    "; PLACES: (in-ns 'my.domain.thing) moves you there (state preserved; a\n"
    "; fresh name is created with your toolkit), making it your current ns,\n"
    "; which renders in full along with the nses it :requires.\n"
    ";\n"
    "; EVERY rendered element shows its id — you can (db/pull '[*] <id>)\n"
    "; it to see the full row, or transact onto it. The context is the\n"
    "; database rendered; ids are live handles, not labels.\n"
    ";\n"
    "; STANDING TEACHINGS:\n"
    "; - Consult stored knowledge FIRST — query it rather than relying on a\n"
    ";   wall of text. Use (my.kb/recall {:my.kb/about \"<topic>\"}) for\n"
    ";   knowledge, or Datalog an existing attribute directly. Inspect\n"
    ";   Search existing schemas before defining a new attribute.\n"
    ";   Prior agents already answered many questions; re-deriving a\n"
    ";   stored answer is wasted turns.\n"
    "; - Store what you verify, without being asked — ONE call does it:\n"
    ";   (my.kb/remember {:my.kb/claim \"<the fact>\"\n"
    ";                    :my.kb/source \"<file:line or url>\"\n"
    ";                    :my.kb/confidence :verified}) ; or :inferred\n"
    ";   It returns {:my.kb/id <eid>} — the durable handle to point a\n"
    ";   message/complete at. No schema to design, no register!, no\n"
    ";   transact to hand-write; the grade is required so a guess can't\n"
    ";   pass as a fact. Knowledge nobody stored is research the next\n"
    ";   agent pays for again. Store each claim the MOMENT you verify it —\n"
    ";   a grep hit with a file:line is already one remember call; don't\n"
    ";   wait for the whole investigation to finish. Storing is not\n"
    ";   optional follow-up; it IS the deliverable of research. (A\n"
    ";   multi-field domain — linked refs, your own id — designs a\n"
    ";   my.kb.<domain> schema; remember is the single-claim fast path.)\n"
    "; - Keep fns small and tight. Add ONE concise :test usage example to\n"
    ";   a new defn ONLY when its :malli/schema doesn't already make the\n"
    ";   call obvious (generic shapes like [:int]->:boolean):\n"
    ";   (defn f {:malli/schema S :test (fn [] (assert (= 4 (f 2 2))))}\n"
    ";     [a b] ...). It is what the NEXT agent sees INSTEAD of your body, so\n"
    ";   it must be self-explanatory AND pass (a failing example surfaces).\n"
    "; - A task with 2+ steps: lay the WHOLE plan down FIRST with\n"
    ";   my.plan/plan! — a :goal (why), :pace :multi-session when it spans\n"
    ";   sessions, an :expect per step (how you'd know it failed). Then\n"
    ";   active! the step you take up, VERIFY its expect, and done! it the\n"
    ";   MOMENT it is verified — an open step you actually finished reads\n"
    ";   as unfinished. Your plan renders every turn: where you are, the\n"
    ";   ready frontier, and what you just finished.\n"
    "; - AFTER A RESTART your plan is still rendered above you: RESUME it —\n"
    ";   take up its open steps and done! each as you finish. Do NOT create\n"
    ";   a new plan for work you already planned; re-planning from scratch\n"
    ";   discards your own progress. Close the finished steps FIRST, then\n"
    ";   continue from the frontier. And before you complete, SWEEP your\n"
    ";   plan: done! every step whose work is in fact finished — including\n"
    ";   the deliver step, the moment you have delivered.\n"
    "; - When your human messages you, an address-step (marked ✉) is\n"
    ";   auto-minted for you. The natural turn: done! that message-step\n"
    ";   AND do the work AND (often) step! sub-steps from what they said —\n"
    ";   all at once. The done-signal is the step's completion, nothing is\n"
    ";   destroyed.\n"
    "; - Your messages render as markdown on your human's screen — use\n"
    ";   structure when it helps (short headings, lists, code fences for\n"
    ";   code or data); plain prose otherwise.\n"
    ";\n"
    "; MESSAGING + LIFECYCLE. You talk to people and you end your work with\n"
    "; explicit functions — all plain Clojure, all through the DB:\n"
    ";   (message/user \"...\")            ; tell your one human — they see it now\n"
    ";   (message/agent \"<id>\" \"...\")    ; tell a specific peer agent\n"
    ";   (wait \"note\")                   ; deliberately park; resume when a message arrives\n"
    ";   (complete \"<the answer>\")       ; finish cleanly — delivers the string IF you haven't already messaged this run\n"
    "; - TELL YOUR HUMAN with (message/user \"...\"). They see exactly what you\n"
    ";   send, when you send it — so send the answer when you have it, and a\n"
    ";   SHORT progress note when a longer task is still running. You can\n"
    ";   message every turn when they need to stay informed; silence across\n"
    ";   many turns is the failure, a one-line update is cheap.\n"
    "; - YOUR DELIVERED ANSWER IS WHAT YOU SEND, never what you merely\n"
    ";   computed or narrated. A value sitting in a result, a line of prose,\n"
    ";   or an answer typed raw on its own line (ANSWER: 4) is NOT delivered\n"
    ";   — raw text is a NOTE, dropped, never sent. Put the answer INSIDE a\n"
    ";   sending form: (message/user \"...\") or (complete \"...\"). Whoever\n"
    ";   asked reads your LAST sent string, so the last thing you send must\n"
    ";   BE the answer. If a specific output format was asked for, that\n"
    ";   exact format is your ENTIRE final string — (message/user\n"
    ";   \"ANSWER: C\") — nothing appended after it, no restating around it.\n"
    ";   Once you HAVE messaged this run, a closing (complete \"...\") sends\n"
    ";   NOTHING more — the message you already sent IS the delivered\n"
    ";   answer, and complete just closes; a filler string cannot clobber\n"
    ";   it.\n"
    "; - TALK TO A PEER with (message/agent \"<agent-id>\" \"...\"). Messaging\n"
    ";   YOURSELF is refused — your notes-to-self are just ; comments in\n"
    ";   your turn.\n"
    "; - REPORT = DATA, MESSAGE = POINTER. A message (or a complete result)\n"
    ";   is ONE eval'd form and must fit your output budget — pack a long\n"
    ";   report into (message/agent id (str \"...hundreds of lines...\")) and\n"
    ";   it TRUNCATES mid-string: the form won't parse, NOTHING sends, and\n"
    ";   the reader gets silence. So STORE the artifact as data FIRST — a\n"
    ";   my.kb.* entity or a :seon.items envelope (schema'd + queryable) —\n"
    ";   then send a SHORT pointer: the id + a one-line summary. The reader\n"
    ";   QUERIES the stored data; the message just points at it.\n"
    "; - IF YOU WERE SPAWNED BY ANOTHER AGENT (you have a parent), finishing\n"
    ";   MEANS reporting back. (complete \"...\") sends its result string to\n"
    ";   your parent and wakes them (unless you already messaged your parent\n"
    ";   this run — that message is then the report and complete just\n"
    ";   closes). So store your findings, then complete with the POINTER\n"
    ";   (the id + a one-line summary), never the whole\n"
    ";   report inline. Doing the work but going idle without a complete\n"
    ";   leaves your parent waiting on a delivery that never comes.\n"
    "; - DELEGATE WITH ONE FORM. To hand a task to a fresh worker, use\n"
    ";   (agent/delegate! {:seon.agent/purpose \"why it exists\"\n"
    ";                      :seon.agent.message/content \"<the task>\"}) — it\n"
    ";   spawns the child AND messages it the task in one call, returning the\n"
    ";   child's real {:seon.agent/id \"...\"}. NEVER\n"
    ";   (let [c (agent/start! {…})] (message/agent (:seon.agent/id c) …)):\n"
    ";   start! is async, so (:seon.agent/id c) reads nil and you spawn an\n"
    ";   ORPHAN you can't reach. If you must spawn alone, eval (agent/start!\n"
    ";   {…}) by itself, READ the child id it returns, then message that\n"
    ";   literal id in the NEXT form — never invent an id.\n"
    "; - FINISHING IS AN ACT, NOT A DRIFT. A task has a GOAL — the thing you\n"
    ";   were asked to produce (an answer, an artifact, a stored fact, a\n"
    ";   decision). The moment that thing EXISTS and you have delivered it\n"
    ";   (said the answer with (message/user …), or handed the pointer with\n"
    ";   (complete …)), the task is DONE — end the loop that same turn. Do\n"
    ";   NOT keep going once the goal is met: re-confirming a value you\n"
    ";   already computed, re-storing what you already stored, restyling a\n"
    ";   surface, or re-announcing \"it works / I'm ready\" are not progress —\n"
    ";   they burn turns on a finished task. The done-signal is the GOAL\n"
    ";   being satisfied, not your open steps or the turn cap. Ask each turn:\n"
    ";   \"do I already have what was asked for?\" If yes, deliver it and stop.\n"
    "; - WHEN YOU ARE DONE, say so with a function. (complete \"<the answer>\")\n"
    ";   marks the work finished AND — if you have not already messaged\n"
    ";   whoever asked (your human, or your parent agent) this run —\n"
    ";   delivers that exact string to them; so its argument is the\n"
    ";   ANSWER (or the pointer), never filler like \"done\"/\"result\". Call\n"
    ";   it the turn the goal is met. (wait \"what you're waiting for\") parks you\n"
    ";   until the next message wakes you — use it after you've asked a\n"
    ";   question and need their answer to continue. If you simply have\n"
    ";   nothing more to do this loop, emit NO forms — the loop ends cleanly\n"
    ";   and you go idle until the next message. You stay wakeable in every\n"
    ";   one of these states: a new message always brings you back.\n"
    "; - A message is REAL once it lands — but errors-are-VALUES: a transact\n"
    ";   can SUCCEED as an eval yet return a map with :seon.error/message\n"
    ";   (the write did NOT happen). Only tell your human it landed when the\n"
    ";   call returned a transaction report, not an error value.\n"
    "; - TURNS ARE PRECIOUS — each turn is a full round-trip; don't spend one\n"
    ";   exploring when the answer is already in front of you. If your\n"
    ";   context CLEARLY contains the answer (it's in the soul, a loaded ns,\n"
    ";   or the transcript above), ANSWER this turn —\n"
    ";   (message/user \"...\") — don't re-research what you can already see. But\n"
    ";   if the answer is NOT plainly present — anything about stored\n"
    ";   knowledge, your human's data, the codebase, or specifics you haven't\n"
    ";   read this turn — QUERY FIRST (my.kb/recall or Datalog), THEN answer\n"
    ";   from what you found.\n"
    "; - THE PER-LOOP CAP IS A SLIDING WINDOW. A loop runs up to a base cap\n"
    ";   of turns, and EVERY message you receive (from your human or a peer)\n"
    ";   grants one MORE turn — so a fresh message always buys you a turn to\n"
    ";   see and respond to it. The readline shows loop K/cap. As you near\n"
    ";   the cap, wrap up: (complete \"...\") with what you have.\n"
    "; - Building canvas or panel hiccup from a sequence: splice children\n"
    ";   with (into [:div ...] children), never nest a bare seq as one\n"
    ";   child. Eval your render fn once at the REPL to eyeball the hiccup\n"
    ";   before you wire it onto a surface.\n"
    "; - When you store a my.kb.* fact, grade it: record HOW you know it\n"
    ";   (a :my.kb/source) and HOW SURE you are (a :my.kb/confidence). A\n"
    ";   guess stored as fact is worse than no fact — the next agent\n"
    ";   cannot read your certainty from your phrasing.\n"
    "; ── end system ──"))


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

(defn- clip
  "Clip `s` to `n` chars with an ellipsis marker. nil-safe."
  [s n]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 n) " …") s)))

(defn- fn-block-ai
  "One fn rendered for the :ai form: `(sym arglists)` header, first-doc-line,
   and FULL source — no size gate, no clipping (signatures retired; authored
   code renders whole). Used only for nses with NO stored full file source
   (runtime-created nses whose members live only in the index rows) and the
   member-drill ([[render-member]]); a full-source ns renders its whole file
   directly, not per-member."
  [{:seon.fn/keys [sym arglists doc source private? spec schema-error]}]
  (let [sig    (when (and arglists (not (str/blank? arglists)))
                 (let [a (str/trim arglists)]
                   (if (and (str/starts-with? a "(") (str/ends-with? a ")"))
                     (str "(" sym " " (subs a 1 (dec (count a))) ")")
                     (str "(" sym " " a ")"))))
        flags  (cond-> []
                 private?      (conj ":private")
                 (some? spec)  (conj (str ":spec " spec))
                 (nil? spec)   (conj ":unspecced")
                 schema-error  (conj (str ":schema-error " schema-error)))
        ;; The header (incl. the `(sig)` arglist shape) is DOCUMENTATION,
        ;; not a form to run — rendered as a `;` prose comment so the
        ;; arglist `(ns/fn [args])` is NEVER a bare callable list. If an
        ;; agent echoes a rendered signature back into its reply,
        ;; `seon.repl.internal/parse-forms` skips the comment line instead
        ;; of EXECUTING it (a `(seon.schema/clear-all! [])` signature once
        ;; wiped the live registry that way). Render is a pure read; the
        ;; only re-runnable forms it emits are full `(defn …)` source.
        header (str "; [fn " sym "]"
                    (when sig (str "  " sig))
                    (when (seq flags) (str "  " (str/join " " flags))))
        lines  (cond-> [header]
                 (and doc (not (str/blank? doc)))
                 (conj (str "; " (first (str/split-lines doc))))
                 (and source (not (str/blank? source)))
                 (conj (str/trim source)))]
    (str/join "\n" lines)))

;;; ---------------------------------------------------------------------------
;;; Referenced-schema closure — the DEFINITIONS behind a namespace's fn specs.
;;;
;;; A compact/full ns card shows each public fn's `:malli/schema` and the ns's
;;; OWN `register!` block, but a spec references registered schemas whose
;;; definitions live in OTHER namespaces (`:seon.db/ref`, `:seon.ns/name`, a
;;; `::foo-request` map…). Without those a reader can't tell what an arg IS or
;;; whether the call is positional vs map-in. These fns compute the TRANSITIVE
;;; closure of registered schemas a namespace's fns reference — reaching freely
;;; across namespaces (schemas are ONE global registry; which file a `register!`
;;; sits in is incidental) — and render their definitions as `(register! …)`
;;; lines. Shared by BOTH the full renderer ([[render-one-ns-ai]]) and the
;;; compact card ([[seon.agent.ctx.namespaces/render-one-ns-compact]]).
;;;
;;; PURE FUNCTION OF THE DB VALUE (byte-identical train/serve): references are
;;; detected with malli's own RefSchema walk (`m/-ref-schema?` / `m/-ref`),
;;; supplied an isolated placeholder registry rather than Malli's live one, and definitions are
;;; resolved from `:seon.schema/form` in the db — NOT the live `*schemas`
;;; registry. (Malli's native transitive walk, `::m/walk-refs`, resolves refs
;;; THROUGH the registry to descend; using it would couple the render to live
;;; registry state. So we use malli only for per-form DIRECT detection and do
;;; the transitive expansion ourselves over db source strings.)
;;; ---------------------------------------------------------------------------

(def referenced-schema-cap
  ;; Token weight is real (these cards ride every prompt). Bound the closure and
  ;; say what was capped rather than silently truncate. Measured max closure in
  ;; the live corpus is ~26; 40 is headroom that essentially never trips.
  40)

(def ^:private schema-ref-registry
  ;; `m/schema` normally resolves qualified keywords through Malli's mutable
  ;; process-global registry. Context is a projection of a DATABASE VALUE, so
  ;; that registry is neither an authority nor a stable input. Malli already
  ;; knows which positions in every schema form are schemas (rather than map /
  ;; catn labels or enum values); give that parser only its built-ins plus an
  ;; inert fallback for qualified schema refs. The resulting RefSchema nodes
  ;; retain their names while dereferencing to `:any`, and `m/walk` therefore
  ;; reports DIRECT refs without touching live registry state.
  (mr/composite-registry
    (mr/fast-registry (m/default-schemas))
    (reify
      mr/Registry
      (-schema [_ type]
        (when (qualified-keyword? type) :any))
      (-schemas [_] {}))))

(defn- schema-form-refs
  "The DIRECT registered-schema references in one Malli form — every qualified
   keyword in SCHEMA position (not a `:catn`/`:cat` entry LABEL), via malli's own
   RefSchema walk. Built-ins (`:string`, `:map`, …) are not refs and never
   match; a bare `[:schema …]` inline (nil ref name) is skipped. The isolated
   [[schema-ref-registry]] resolves refs to inert placeholders, never through
   the process-global registry. Errors-as-values: a foreign/unbuildable form
   yields `#{}`, never throws into the render."
  [form]
  (let [acc (atom #{})]
    (try
      (m/walk (m/schema form {:registry schema-ref-registry})
              (fn [sch _ _ _]
                (when (m/-ref-schema? sch)
                  (let [r (m/-ref sch)]
                    (when (qualified-keyword? r) (swap! acc conj r))))
                sch))
      (catch :default _ nil))
    @acc))

(defn- read-schema-form
  "Read one schema/spec string to data, or nil when it is unreadable."
  [s]
  (try (edn/read-string s) (catch :default _ nil)))

(defn- schema-str-refs
  "[[schema-form-refs]] over a stored spec STRING."
  [s]
  (or (some-> s read-schema-form schema-form-refs) #{}))

(schema/register! ::schema-sources [:vector :string])
(schema/register! ::schema-keys [:set :keyword])

(defn schema-refs
  "Direct qualified schema keys referenced by persisted schema strings."
  {:malli/schema [:=> [:catn [::schema-sources ::schema-sources]] ::schema-keys]}
  [schema-sources]
  (reduce (fn [refs source] (into refs (schema-str-refs source)))
          #{}
          schema-sources))

(defn normalize-schema-form
  "Read one persisted Malli form, unwrapping a canonical register call."
  {:malli/schema [:=> [:catn [:seon.schema/form :string]] :any]}
  [source]
  (let [form (read-schema-form source)]
    (if (and (seq? form)
             (#{'register! 'seon.schema/register!} (first form))
             (keyword? (second form))
             (<= 3 (count form)))
      (nth form 2)
      form)))

(defn- schema-definition-text
  "Reader-shaped text for one normalized persisted schema definition."
  [{::keys [schema-source schema-form]}]
  (if (some? schema-form) (pr-str schema-form) schema-source))

(defn- schema-ref-closure
  "Transitive closure of registered schema keys reachable from `seed`, resolved
   through `definition-for`. `own-keys` are TRAVERSED (so their
   cross-ns children still surface) but never EMITTED — the ns already shows them
   as its own `register!` block / in its source. Cycle-safe via a `seen` set;
   bounded at [[referenced-schema-cap]]. Returns
   `{::closure-keys [<kw>…] ::capped? bool}`, keys sorted."
  [definition-for seed own-keys]
  (loop [queue (vec (sort seed)), seen #{}, out #{}, definitions {}]
    (if (or (empty? queue) (>= (count out) referenced-schema-cap))
      {::closure-keys (vec (sort out))
       ::capped?      (boolean (and (seq queue) (>= (count out) referenced-schema-cap)))
       ::definitions  definitions}
      (let [k     (first queue)
            queue (subvec queue 1)]
        (if (contains? seen k)
          (recur queue seen out definitions)
          (let [definition (definition-for k)
                definitions' (cond-> definitions definition (assoc k definition))
                child      (or (some-> definition ::schema-form schema-form-refs) #{})
                out'       (if (or (contains? own-keys k) (nil? definition))
                             out
                             (conj out k))
                seen'      (conj seen k)]
            (recur (->> (concat queue child)
                        (remove seen')
                        distinct
                        sort
                        vec)
                   seen'
                   out'
                   definitions')))))))

(defn- schema-definitions-from-rows
  "Normalized schema definitions indexed by their existing persisted keys."
  [schema-rows]
  (into {}
        (keep (fn [{k :seon.schema/key source :seon.schema/form}]
                (when (and (keyword? k) (string? source))
                  [k {::schema-source source
                      ::schema-form (normalize-schema-form source)}])))
        schema-rows))

(defn- referenced-schema-block*
  "Render a referenced-schema block using one definition lookup function."
  [definition-for seed-specs own-keys]
  (let [seed        (reduce (fn [a s] (into a (schema-str-refs s))) #{} seed-specs)
        closure     (schema-ref-closure definition-for seed own-keys)
        definitions (::definitions closure)
        lines       (mapv (fn [k]
                            (str "(register! " (pr-str k) " "
                                 (schema-definition-text (get definitions k)) ")"))
                          (::closure-keys closure))
        lines       (cond-> lines
                      (::capped? closure)
                      (conj (str "; … " referenced-schema-cap "+ referenced schemas"
                                 " — capped; more reachable via the db")))]
    (when (seq lines)
      (str/join "\n" lines))))

(schema/register! ::seed-specs ::schema-sources)
(schema/register! ::own-keys   [:set :keyword])
(schema/register! ::schema-row
  [:map
   [:seon.schema/key :keyword]
   [:seon.schema/form :string]])
(schema/register! ::schema-rows [:vector ::schema-row])
(schema/register! ::referenced-schema-rows-request
  [:map
   [::seed-specs ::seed-specs]
   [::own-keys ::own-keys]
   [::schema-rows ::schema-rows]])
(defn referenced-schema-rows-block
  "Render referenced schema definitions from ordinary persisted rows."
  {:malli/schema [:=> [:cat ::referenced-schema-rows-request]
                  [:maybe :string]]}
  [{seed-specs ::seed-specs own-keys ::own-keys schema-rows ::schema-rows}]
  (let [definitions (schema-definitions-from-rows schema-rows)]
    (referenced-schema-block* #(get definitions %) seed-specs own-keys)))

(defn- schema-block-ai
  "One schema rendered from the supplied database row."
  [{:seon.schema/keys [key form]}]
  (let [form (if (str/blank? form) "<not registered>" (str/trim form))]
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
         (str "\n" (str/trim source)))))

(defn ns-demarc
  "Wrap a rendered namespace BODY in begin/end demarcation brackets.

   The `;;;` runtime-structure convention, nesting one level
   under the section-level `;;; ┌─/└─` brackets. A `;;; ┌─ namespace X ─`
   begin line sits above the body and a `;;; └─ end namespace X ─` end
   line below it, so every ns in the `:namespaces` section is clearly
   delimited; a truly-empty ns (begin immediately followed by end) is
   glaring, no longer silent poison. `suffix` (optional) rides the begin
   line — e.g. \"(member store-fact)\" for the member-drill view. The sites
   ns blocks are bracketed: [[render-one-ns-ai]], [[render-member]], and the
   home-ns workspace stub ([[seon.agent.ctx.namespaces/cur-ns-workspace-stub]])
   all route through it, so the demarcation is uniform."
  {:malli/schema [:function
                  [:=> [:catn [::ns-kw [:or :keyword :symbol]] [::body :string]] :string]
                  [:=> [:catn [::ns-kw [:or :keyword :symbol]] [::body :string]
                        [::suffix [:maybe :string]]] :string]]}
  ([ns-kw body] (ns-demarc ns-kw body nil))
  ([ns-kw body suffix]
   (str ";;; ┌─ namespace " (name ns-kw)
        (when (and suffix (not (str/blank? suffix))) (str " " suffix)) " ─\n"
        body
        "\n;;; └─ end namespace " (name ns-kw) " ─")))

(defn- render-one-ns-ai
  "Render a single namespace block to text, FULL — `ns-kw` is the namespace
   keyword; `data` is an eager namespace row (or nil = not in db), and
   `ref-blk` is its already-derived referenced-schema text.

   Every block is delimited by the per-ns `;;; ┌─ namespace X ─` /
   `;;; └─ end namespace X ─` brackets ([[ns-demarc]]) — the runtime-
   structure convention that makes each ns boundary explicit and an empty
   one glaring.

   Signatures are retired: the ns renders its `(ns …)` SOURCE plus every fn
   (FULL source), schema, and test. When the ns carries its REAL full file
   SOURCE, that source IS the authoritative body — every defn/register! is
   already in it, so re-emitting per-member [fn …]/[schema …] blocks would be
   pure duplication (GI-1). We render the source ALONE, surfacing only the
   member facts NOT visible in the source and worth the agent's attention: a
   fn whose :malli/schema failed to compile (:seon.fn/schema-error) and a test
   whose last recorded run FAILED — each a compact one-line ⚠ note. When there
   is NO stored source (runtime-created nses that hold only schemas/fns) — OR
   only the indexer's bare `(ns x)` STUB (a non-full-source framework ns: its
   members live only in the index rows, NOT in that one-line source) — the
   per-member blocks ARE the content, rendered in full. The stub guard below
   is load-bearing for the on-demand `render-namespace` capability the system
   prompt teaches by name: without it, rendering a dropped framework ns (e.g.
   :seon.warn) would yield just `(ns x)`, erasing its whole API."
  [ns-kw data ref-blk]
  (if (nil? data)
    (str "; requires: " (name ns-kw) " (not in db)")
    (let [src     (:seon.ns/source data)
          fns     (->> (:seon.fn/_ns data)     (sort-by :seon.fn/sym))
          schemas (->> (:seon.schema/_ns data) (sort-by (comp str :seon.schema/key)))
          tests   (->> (:seon.test/_ns data)   (sort-by :seon.test/sym))]
      (if (and src (not (str/blank? src))
               (not= (str/trim src) (str "(ns " (name ns-kw) ")")))
        (let [notes (concat
                      (for [{:seon.fn/keys [sym schema-error]} fns
                            :when (and schema-error (not (str/blank? schema-error)))]
                        (str "; ⚠ " sym ": schema-error " (clip schema-error 120)))
                      (for [{:seon.test/keys [sym last-failure-summary] :as t} tests
                            :when (false? (:passing? (h-test/test-status t)))]
                        (str "; ⚠ test " sym " failing"
                             (when (and last-failure-summary
                                        (not (str/blank? last-failure-summary)))
                               (str ": " (clip last-failure-summary 120))))))]
          (ns-demarc ns-kw
                     (str (str/trim src)
                          (when ref-blk (str "\n\n" ref-blk))
                          (when (seq notes) (str "\n\n" (str/join "\n" notes))))))
        (let [body (cond-> []
                     (seq fns)     (into (map fn-block-ai fns))
                     (seq schemas) (into (map schema-block-ai schemas))
                     ref-blk       (conj ref-blk)
                     (seq tests)   (into (map test-block-ai tests)))]
          (ns-demarc ns-kw
                     (if (seq body) (str/join "\n\n" body) "; (no recorded source/fns/schemas)")))))))

(schema/register! ::namespace-row
  [:map
   [:seon.ns/name :seon.ns/name]
   [:seon.ns/source {:optional true} :string]
   [:seon.fn/_ns {:optional true} [:vector [:map]]]
   [:seon.schema/_ns {:optional true} [:vector [:map]]]
   [:seon.test/_ns {:optional true} [:vector [:map]]]])
(schema/register! ::namespace-rows [:vector ::namespace-row])
(schema/register! ::render-namespace-ai-request
  [:map
   [:seon.ns/name :seon.ns/name]
   [::namespace-rows ::namespace-rows]
   [::schema-rows ::schema-rows]])

(defn render-namespace-ai
  "Render one eager namespace row and persisted schema rows to AI text."
  {:malli/schema [:=> [:cat ::render-namespace-ai-request] :string]}
  [{ns-kw :seon.ns/name namespace-rows ::namespace-rows schema-rows ::schema-rows}]
  (let [data        (some (fn [row]
                            (when (= ns-kw (:seon.ns/name row)) row))
                          namespace-rows)
        fns         (:seon.fn/_ns data)
        schemas     (:seon.schema/_ns data)
        seed-specs  (into [] (keep :seon.fn/spec) fns)
        own-keys    (into #{} (map :seon.schema/key) schemas)
        ref-blk     (referenced-schema-rows-block
                      {::seed-specs seed-specs
                       ::own-keys own-keys
                       ::schema-rows (into schema-rows schemas)})]
    (render-one-ns-ai ns-kw data ref-blk)))

;; The HTML TWIN of a rendered section (debug-view-section-twins-2026-06-18):
;; the dormant `:seon.render/html` slot, resolved through
;; `seon.render/html-render` + the throw-to-banner guard, paired with its
;; section name. Hiccup is genuinely arbitrary agent-authored data at this
;; boundary — `:seon.render.canvas/hiccup` is the registered shallow
;; bound (vector with keyword head); the deep walk happens at the render
;; boundary, same as every `:seon.render/html-response`.
(schema/register! :seon.agent.ctx/block-html
  [:map
   [:seon.agent.ctx/name :seon.agent.ctx/name]
   [:seon.render/hiccup :seon.render.canvas/hiccup]])

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
   VOLATILE tail (everything after: canvas, warnings, plan, transcript).

   In-band because the agent loop hands providers ONE assembled
   string (`llm-fn` is fn-of-ctx-string): [[split-context]] recovers
   the two halves on the provider side, so an adapter can put the stable
   prefix in a cached system block and send only the volatile tail as the
   user message. Built by concatenation so the marker can never appear
   verbatim in rendered source text."
  (str "; ──── ctx cache boundary — everything above this line is the "
       "byte-stable" " prefix; everything below changes per turn ────"))

(def ^:private stable-boundary-delim
  (str "\n\n" stable-boundary "\n\n"))

(defn split-context
  "Split an assembled ctx string at [[stable-boundary]].

   Into the
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

;; The default block layout lives in the manifest resolved by
;; `seon.config/resolve-agent-context`. This ns still owns
;; the file-block RENDER fns ([[file-block-ai]]/[[file-block-html]]) + the
;; identity-file PATH consts ([[soul-file-path]]/[[agents-file-path]], read by
;; [[identity-files-text]]) — config emits the block DATA (literal render
;; symbols) and this ns renders it.

;; ============================================================
;; install! / remove! — the ONE scope-aware override + seed function over the
;; agent's own :seon.agent/ctx block set. Errors are values. Both target the
;; agent in scope (db/current-agent-id): an agent shapes its OWN context, and
;; the boot seed-copy runs them inside the new agent's with-agent scope.
;; ============================================================

(schema/register! ::ok?   :boolean)
(schema/register! ::error :string)
(schema/register! ::names [:vector :seon.agent.ctx/name])

(schema/register! ::install-request
  [:or :seon.agent.ctx/block [:vector :seon.agent.ctx/block]])

(schema/register! ::result
  [:or
   [:map [::ok? [:= true]]  [::names ::names]]
   [:map [::ok? [:= false]] [::error ::error]]])

(schema/register! ::initial-context-request
  [:map
   [:seon.agent/id :seon.agent/id]
   [:seon.config/configuration :seon.config/singleton]
   [:seon.agent.ctx/override {:optional true} [:maybe :map]]])

(defn initial-agent-context
  "Return the complete creation-time context facts for one new agent.

   This is a pure projection of the optional config manifest: agent-level
   scalar dials and the entire `:seon.agent/ctx` component tree in one map.
   The caller merges it into the new agent entity so identity, context blocks,
   and dials commit atomically. Existing agents never call this function during
   resume; their database facts remain authoritative."
  {:malli/schema
   [:=> [:cat ::initial-context-request] :seon.config/agent-context]}
  [{:seon.agent/keys [id] :seon.agent.ctx/keys [override]
    configuration :seon.config/configuration}]
  (config/resolve-agent-context id override configuration))

(defn- upsert-ctx-tx
  "Tx-data that REPLACES the scoped agent's :seon.agent/ctx with `blocks`:
   `:db.fn/retractAttribute` the whole component vector — which
   cascade-retracts the old child block entities (datahike emits a
   `:db.fn/retractEntity` per component-ref value; plain `:db/retract` returns
   NO follow-on ops, so it would only sever the agent→block edges and ORPHAN
   the children) — then re-add `blocks`. An empty `blocks` leaves the attr
   retracted (no add)."
  [id blocks]
  (into [[:db.fn/retractAttribute [:seon.agent/id id] :seon.agent/ctx]]
        (when (seq blocks)
          [{:seon.agent/id id :seon.agent/ctx (vec blocks)}])))

(defn ^:async ^:private acquire-context-blocks
  "Acquire the scoped agent's context components as ordinary maps."
  [id]
  (let [entity (await
                 (db/pull {::db/pull-pattern '[{:seon.agent/ctx [*]}]
                           ::db/ref [:seon.agent/id id]
                           ::db/max-work 100000
                           ::db/max-results 2048
                           ::db/max-result-weight 262144}))]
    (if (:seon.error/message entity)
      entity
      (->> (:seon.agent/ctx entity)
           (map decode-block)
           (sort-by (juxt :seon.agent.ctx/priority
                          (comp str :seon.agent.ctx/name)))
           vec))))

(defn- transaction-result
  [operation names result]
  (if-let [message (:seon.error/message result)]
    {::ok? false ::error (str operation " transact failed: " message)}
    {::ok? true ::names names}))

(defn ^:async install!
  "Install context BLOCK(S) into the agent in scope.

   One block map OR a
   vector of block maps, idempotent UPSERT by :seon.agent.ctx/name (re-installing
   a name replaces that block, so iterating never accumulates copies). The
   target is the agent in scope (db/current-agent-id): an agent shapes its OWN
   context; the creation seed-copy runs install! inside the new agent's scope
   to copy the default block set in. Errors are values — no agent in scope or
   a failed transact comes back as {::ok? false ::error …}.

     (seon.agent.ctx/install!
       {:seon.agent.ctx/name :doctrine :seon.agent.ctx/priority 15
        :seon.render/ai \"Always reconcile against my.finance.ledger.\"})"
  {:malli/schema [:=> [:cat ::install-request] ::result]}
  [block-or-blocks]
  (let [id (db/current-agent-id)]
    (if (nil? id)
      {::ok? false
       ::error (str "install!: no agent in scope — call inside "
                    "(seon.db/with-agent id …).")}
      (let [current (await (acquire-context-blocks id))]
        (if (:seon.error/message current)
          {::ok? false ::error (:seon.error/message current)}
          (let [blocks (if (vector? block-or-blocks)
                         block-or-blocks
                         [block-or-blocks])
                new-names (into #{} (map :seon.agent.ctx/name) blocks)
                kept (->> current
                          (remove #(contains? new-names
                                              (:seon.agent.ctx/name %)))
                          (mapv #(dissoc % :db/id)))
                res (await (db/transact!
                             {:seon.db/tx-data
                              (upsert-ctx-tx id (into kept blocks))}))]
            (transaction-result "install!" (vec new-names) res)))))))

(defn ^:async remove!
  "Remove ONE context block by name from the agent in scope.

   The block is a
   component child, so dropping it from :seon.agent/ctx cascade-retracts the
   child entity. Errors are values — no agent in scope or a failed transact
   comes back as {::ok? false ::error …}. Removing an absent name is a no-op
   success."
  {:malli/schema [:=> [:catn [:seon.agent.ctx/name :seon.agent.ctx/name]] ::result]}
  [nm]
  (let [id (db/current-agent-id)]
    (if (nil? id)
      {::ok? false
       ::error (str "remove!: no agent in scope — call inside "
                    "(seon.db/with-agent id …).")}
      (let [current (await (acquire-context-blocks id))]
        (if (:seon.error/message current)
          {::ok? false ::error (:seon.error/message current)}
          (let [kept (->> current
                          (remove #(= nm (:seon.agent.ctx/name %)))
                          (mapv #(dissoc % :db/id)))
                res (await (db/transact!
                             {:seon.db/tx-data (upsert-ctx-tx id kept)}))]
            (transaction-result "remove!" [nm] res)))))))

;; ============================================================
;; Render pipeline — the agent's OWN block set, decoded + priority-sorted,
;; each block rendered via the injected handle:
;;
;;   children = sort-by priority (the agent's complete :seon.agent/ctx) —
;;              one collection, no merge over a default catalog.
;;   input    = {db, id, entity (pulled ONCE), node}
;;   render   = string slot → verbatim | symbol slot → (fn input)
;;
;; Guard: a block whose fn is missing/throws renders a one-line error
;; string inside the block — never breaks assembly, surfaces loudly,
;; self-heals when fixed. There is NO char budget: the agent owns its
;; whole block set; unbounded growth is bounded at the eval-output layer
;; (and seeded blocks get rolling windows later).
;; ============================================================

(defn decode-block
  "Decode a PULLED section entity's EDN-encoded attrs to value shapes.

   Every attr runs through `seon.db/decode-edn-value` (the inverse of the
   bridge's mixed-:or EDN-string storage encoding) — the encoded set is the
   COMPUTED `edn-encoded-attr?` rule, never a key list here, so new encoded
   slots (e.g. :seon.render.canvas/content) decode without this fn
   changing. Non-encoded attrs pass through unchanged."
  {:malli/schema [:=> [:catn [::block :map]] :map]}
  [section]
  (reduce-kv (fn [m k v] (assoc m k (db/decode-edn-value k v)))
             {} section))

(defn agent-blocks
  "The agent's COMPLETE set of `:seon.agent.ctx/block` maps.

   From its pulled
   entity — slot-decoded, sorted by `:seon.agent.ctx/priority` with the block
   NAME as the byte-stable tie-break (no merge, no separate default set: every
   block was seed-copied in at creation and the agent owns the whole set).
   `entity` is the once-pulled agent entity map."
  {:malli/schema [:=> [:catn [::entity :map]] [:vector :map]]}
  [entity]
  (->> (:seon.agent/ctx entity)
       (map decode-block)
       (sort-by (juxt :seon.agent.ctx/priority
                      (comp str :seon.agent.ctx/name)))
       vec))

;; ── the root's children = the agent's OWN complete block set ─────────────
;; The ROOT renderable's children are exactly the agent's `:seon.agent/ctx`
;; blocks, one priority sort. There is no render-time merge over a separate
;; default catalog — the manifest tree was copied into the agent at creation,
;; so render reads one collection and stops.


(defn- block-bracket-ai
  "The ai-view bracket the ROOT section renderer wraps each child in — the
   self-demarcating boundary that REPLACES the old per-section `;; ── x ──`
   headers. The agent can fold the left debug view pane on these lines."
  [section-name body]
  (str ";;; ┌─ " (name section-name) " ─\n"
       body
       "\n;;; └─ end " (name section-name) " ─"))

;; A render PROFILE — an ordered list of block PATCH maps, each carrying at
;; least the block `::name`. Present on a render request ⇒ the root's
;; children are exactly these entries, each MERGED over the agent's stored
;; block of the same name (patch keys win; a name with no stored block
;; renders from the patch alone); derived auto-run blocks are skipped.
;; ABSENT ⇒ byte-parity with the ordinary full-context render. First
;; consumer: the `seon.repl.autocomplete` projection.
(schema/register! :seon.agent.ctx/profile
  [:vector [:map [:seon.agent.ctx/name :seon.agent.ctx/name]]])

(schema/register! ::rendered-block
  [:map
   [:seon.agent.ctx/name :seon.agent.ctx/name]
   [:seon.agent.ctx/priority :seon.agent.ctx/priority]
   [:seon.render/text {:optional true} :string]
   [:seon.render/token-estimate {:optional true} :int]
   [:seon.render/hiccup {:optional true} :seon.render.canvas/hiccup]
   [:seon.render/html {:optional true} :seon.render/html]])
(schema/register! ::rendered-blocks [:vector ::rendered-block])
(schema/register! ::rendered-context
  [:map
   [:seon.render/text {:optional true} :string]
   [:seon.agent.ctx/rendered-blocks ::rendered-blocks]])

(defn- block-renders-ai?
  "A context block contributes to the agent's PROMPT only when it declares an
   `:seon.render/ai` render. An html-only block (a human-facing widget — the
   canvas/canvas, an ACME dashboard surface) has nothing to say to the agent:
   it is OMITTED from the prompt entirely — no self-demarcating bracket, no
   generic data-dump stub (`;; :canvas {:db/id … :seon.agent.ctx/name …}`).
   This is the inverse of the html view's 'AI-only block contributes no surface'
   rule, and the reactive-context principle: a block surfaces to the agent
   only when it has ai content for the current state. The html twin is
   untouched — the block still renders for humans through its HTML slot.
   A block that DOES declare an ai render but resolves to blank for the
   current state is dropped separately, after render, by
   [[rendered-block-texts]] — both 'no ai content' cases vanish."
  [block]
  (contains? block :seon.render/ai))

(defn- cap-block-text
  "Clip a rendered block's :ai text to its `:seon.agent.ctx/token-cap`.

   ABSENT cap (every default block) or a fitting text → unchanged
   (byte-parity). Over-cap → cut at the cap's char equivalent with a
   deterministic token-denominated marker at the cut;
   `:seon.agent.ctx/cap-keep` `:tail` keeps the END (newest lines — the
   transcript shape), default `:head` keeps the opening."
  [child text]
  (let [cap (:seon.agent.ctx/token-cap child)]
    (if-not (and (int? cap) (string? text) (> (tokens/estimate text) cap))
      text
      (let [limit  (tokens/estimate-chars cap)
            marker (str "…⟨block clipped to " cap " of "
                        (tokens/estimate text) " tokens⟩")]
        (if (= :tail (:seon.agent.ctx/cap-keep child))
          (str marker (subs text (- (count text) limit)))
          (str (subs text 0 limit) marker))))))

(defn- rendered-child-blocks
  "Render `children` in the requested formats as one block-oriented view.

   `render-ai` and `render-html` are format-bound render handles. A format is
   evaluated only when requested and declared on the block. Blank AI and nil
   HTML results disappear independently; a block remains when either requested
   format produced content. A block carrying `:seon.agent.ctx/token-cap`
   has its :ai text clipped here ([[cap-block-text]]) so prompt, agent
   view, and debug view all see the same bounded text. This is the one
   projection consumed by prompt, agent view, and debug view — no parallel
   text/html collections to correlate."
  [children formats render-ai render-html]
  (->> children
       (keep
         (fn [child]
           (let [base {:seon.agent.ctx/name     (:seon.agent.ctx/name child)
                       :seon.agent.ctx/priority (:seon.agent.ctx/priority child)}
                 text (when (and (contains? formats :ai)
                                 (block-renders-ai? child))
                        (cap-block-text child (render-ai child)))
                 html (when (and (contains? formats :html)
                                 (contains? child :seon.render/html))
                        (render-html child))
                 out  (cond-> base
                        (and (string? text) (not (str/blank? text)))
                        (assoc :seon.render/text text
                               :seon.render/token-estimate (tokens/estimate text))

                        (some? html)
                        (assoc :seon.render/hiccup html
                               :seon.render/html (:seon.render/html child)))]
             (when (or (contains? out :seon.render/text)
                       (contains? out :seon.render/hiccup))
               out))))
       vec))

(defn- rendered-blocks->context-text
  "Assemble already-rendered AI blocks into the exact prompt context.

   This owns only ordering/bracketing/cache-boundary assembly. Child renderers
   have already run, so callers can reuse the same block strings for debug
   token accounting instead of invoking every AI renderer a second time."
  [root rendered]
  (let [bracketed (->> rendered
                       (filter #(contains? % :seon.render/text))
                       (mapv (fn [block]
                               (assoc block :seon.render/bracketed
                                      (block-bracket-ai
                                        (:seon.agent.ctx/name block)
                                        (:seon.render/text block))))))]
    (if (:seon.agent.ctx/profile-render? root)
      (->> bracketed (map :seon.render/bracketed) (str/join "\n\n"))
      (let [breakpoint (or (:seon.agent.ctx/cache-breakpoint root)
                           default-cache-breakpoint)
            stable (->> bracketed
                        (filter #(<= (or (:seon.agent.ctx/priority %) 999)
                                     breakpoint))
                        (map :seon.render/bracketed)
                        (str/join "\n\n"))
            volatile (->> bracketed
                          (remove #(<= (or (:seon.agent.ctx/priority %) 999)
                                       breakpoint))
                          (map :seon.render/bracketed)
                          (str/join "\n\n"))]
        (cond
          (str/blank? stable) volatile
          (str/blank? volatile) stable
          :else (str stable "\n\n" stable-boundary "\n\n" volatile))))))

(schema/register! ::acquired-context-request
  [:map {:closed true}
   [:seon.agent/entity :map]
   [::selected-blocks {:optional true} [:vector :map]]
   [::whole-prompt {:optional true} [:or :string :symbol]]
   [:seon.agent.ctx/profile {:optional true} :seon.agent.ctx/profile]])

(defn selected-agent-blocks
  "Select stored context blocks or a requested profile from an agent entity."
  {:malli/schema [:=> [:cat :map [:maybe :seon.agent.ctx/profile]]
                  [:vector :map]]}
  [entity profile]
  (let [stored (agent-blocks entity)]
    (if (seq profile)
      (let [by-name (into {} (map (juxt :seon.agent.ctx/name identity))
                          stored)]
        (->> profile
             (mapv (fn [patch]
                     (merge (get by-name
                                 (:seon.agent.ctx/name patch) {})
                            patch)))
             (sort-by (juxt :seon.agent.ctx/priority
                            (comp str :seon.agent.ctx/name)))
             vec))
      stored)))

(defn rendered-context-from-entity
  "Render literal prompt data from one already-acquired agent entity.

   This is the synchronous tail for the compiled prompt owner. It selects the
   entity's complete stored block set or the requested profile, preserves the
   existing omission, cap, order, bracket, and cache-boundary behavior, and
   returns the ordinary rendered prompt map. The asynchronous owner may
   supply an already-selected block vector whose symbol slots have become
   literal results or block-local error strings."
  {:malli/schema [:=> [:cat ::acquired-context-request] ::rendered-context]}
  [{:seon.agent/keys [entity]
    selected ::selected-blocks
    resolved-whole-prompt ::whole-prompt
    profile :seon.agent.ctx/profile
    :as request}]
  (let [children (or selected (selected-agent-blocks entity profile))
        whole-prompt
        (when-not (seq profile)
          (if (contains? request ::whole-prompt)
            resolved-whole-prompt
            (some->> (:seon.render/ai entity)
                     (db/decode-edn-value :seon.render/ai))))]
    (if (or (string? whole-prompt) (symbol? whole-prompt))
      (let [text whole-prompt
            blocks (cond-> []
                     (and (string? text) (not (str/blank? text)))
                     (conj {:seon.agent.ctx/name :prompt
                            :seon.agent.ctx/priority 0
                            :seon.render/text text
                            :seon.render/token-estimate
                            (tokens/estimate text)}))]
        {:seon.render/text (or text "")
         :seon.agent.ctx/rendered-blocks blocks})
      (let [root (cond->
                   {:seon.agent.ctx/name :context
                    :seon.agent.ctx/cache-breakpoint
                    (or (:seon.agent.ctx/cache-breakpoint entity)
                        default-cache-breakpoint)
                    :seon.agent/entity entity
                    :seon.agent.ctx/children
                    children}
                   (seq profile)
                   (assoc :seon.agent.ctx/profile-render? true))
            blocks (rendered-child-blocks
                    (:seon.agent.ctx/children root)
                    #{:ai}
                    :seon.render/ai
                    (constantly nil))]
        {:seon.render/text (rendered-blocks->context-text root blocks)
         :seon.agent.ctx/rendered-blocks blocks}))))

;; ============================================================
;; Block-chain KV cache keys — the Seon half of the prefix-KV reuse win.
;; A PURE derivation: given a turn's ordered (static→volatile) ctx blocks +
;; the agent id, produce the per-block chain-hash vector the co-located
;; diffusion worker keys its encoder-KV snapshots on. Mirrors vLLM's
;; automatic-prefix-cache chain hash VERBATIM (vendored
;; reference-code/vllm/vllm/v1/core/kv_cache_utils.py):
;;   hash_block_tokens (:577-603): H((parent_block_hash, block_token_ids, extra_keys))
;;   the chain (get_request_block_hasher :703-728): prev = this, fold forward
;;   NONE_HASH seeds the root (:95-114); cache_salt rides ONLY the first
;;   block's extra_keys (generate_block_hash_extra_keys :560-561) → per-scope
;;   isolation. We salt by :seon.agent/id so one agent's cached prefix is not
;;   reused for another. Prefix-reuse semantics: blocks 0..i share a hash iff
;;   their content (and salt) are byte-identical; the FIRST changed block
;;   breaks the chain and every hash from there on diverges — exactly vLLM's
;;   longest-prefix match. Derived at render, NEVER persisted (reactive-ctx).
;; The worker-reuse half awaits the co-location image (kv-section-caching
;; design §5); THIS half is pure Seon code, buildable + testable with no GPU.
;; ============================================================

(def ^:private node-crypto (js/require "crypto"))

(def ^:private chain-root-hash
  "The chain's root parent hash — the analog of vLLM's NONE_HASH
   (kv_cache_utils.py:95-114), a fixed constant prepended before the first
   block so a single-block chain still folds in a stable root. Constant (NOT
   `os.urandom`) on purpose: the keys must line up turn-over-turn AND
   across pod/worker restarts, so the seed cannot be process-random."
  "seon.agent.ctx/kv-chain-root")

(defn- sha256-hex
  "Hex SHA-256 of a UTF-8 string (Node crypto). The chain hash function —
   our stand-in for vLLM's `caching_hash_fn` over the tuple."
  [s]
  (-> (.createHash node-crypto "sha256")
      (.update (js/Buffer.from s "utf8"))
      (.digest "hex")))

(defn- block-chain-hash
  "ONE block's chain hash, mirroring vLLM `hash_block_tokens`
   (kv_cache_utils.py:577-603) `H((parent_block_hash, block_tokens, extra_keys))`.
   `parent` = the previous block's hash (or [[chain-root-hash]] at the head),
   `content` = this block's byte-stable rendered text (Seon's analog of the
   block's token ids — the worker tokenizes it deterministically), `salt` =
   the cache_salt extra-key, non-blank ONLY for the head block so the whole
   chain is scoped to one agent. The US (\\u001f, unit-separator) separators
   make the serialization injective (no (a+b,c) vs (a,b+c) collision) — a
   printable escape, not a raw NUL byte (which made the file look binary to
   grep, #83)."
  [parent content salt]
  (sha256-hex (str parent "\u001f" content "\u001f" salt)))

(schema/register! ::chain-hash [:string {:min 1}])
(schema/register! ::chain-hashes [:vector ::chain-hash])

;; A block as the keying fn sees it: its byte-stable rendered text in prompt
;; (priority) order. name/priority are carried only so a caller can correlate
;; a key back to a section — the HASH keys on `:seon.render/text` alone.
(schema/register! ::keyable-block
  [:map
   [:seon.render/text :string]
   [:seon.agent.ctx/name     {:optional true} :seon.agent.ctx/name]
   [:seon.agent.ctx/priority {:optional true} :seon.agent.ctx/priority]])

(schema/register! ::block-chain-keys-request
  [:map
   [::blocks       [:vector ::keyable-block]]
   ;; agent id value-schema = :string (NOT the :seon.agent/id ref): this ns
   ;; loads BEFORE seon.agent registers that attr, so a ref would be an
   ;; unregistered schema at cold boot, so this request spells it :string.
   [:seon.agent/id :string]])

(schema/register! ::block-chain-keys-response
  [:map [::chain-hashes ::chain-hashes]])

(defn block-chain-keys
  "The per-block KV cache-key vector for a turn's context blocks.

   The Seon side of the prefix-KV-reuse contract (the worker reuses
   cached encoder KV for any shared static prefix). PURE: a function of
   (`::blocks`, `:seon.agent/id`) only — no I/O, no GPU.

   `::blocks` is the turn's `:seon.render/text`-bearing blocks in prompt order
   (static→volatile by stored priority), as returned by the compiled prompt
   child. The
   output `::chain-hashes` is parallel to `::blocks`: hash i fingerprints the
   exact block prefix 0..i, salted at the root by `:seon.agent/id`.

   Invariants (mirror vLLM APC, see the ns block above):
   - identical block sequences + same agent ⇒ identical key vectors;
   - two turns sharing a static prefix but differing in the tail share the
     prefix keys and diverge at exactly the first changed block;
   - different `:seon.agent/id` ⇒ different keys even for identical blocks.

   The worker maps each chain-hash → the encoder KV snapshot after encoding
   the prompt prefix ending at that block, and on a new turn reuses the
   longest matching prefix (see the worker-integration contract in
   research/kv-section-caching-design)."
  {:malli/schema [:=> [:cat ::block-chain-keys-request] ::block-chain-keys-response]}
  [{::keys [blocks] :seon.agent/keys [id]}]
  {::chain-hashes
   (reduce (fn [acc [idx block]]
             (let [parent (or (peek acc) chain-root-hash)
                   ;; cache_salt rides the HEAD block only (vLLM :560-561).
                   salt   (if (zero? idx) id "")]
               (conj acc (block-chain-hash parent (:seon.render/text block) salt))))
           []
           (map-indexed vector blocks))})
