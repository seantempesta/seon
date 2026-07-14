(ns seon.config
  "The pod's config-read layer — ONE optional manifest explicitly selected by
   `SEON_CONFIG`. A selected manifest is compiled into database facts; an
   unselected boot preserves the database and never reads `config/system.edn`
   implicitly. The shipped file is selected automatically only for a fresh
   database by the operator. A present manifest declares:

     1. the agent CONTEXT — the two-level `:seon.config/agent-context`
        (agent-level scalars + a `:seon.agent/ctx` block tree) the GENERIC loader
        ([[resolve-agent-context]]) decodes + transacts; `:seon.config/root-context`
        is the sparse root override (its `:canvas` block = the system canvas);
     2. routes — drop seeded `:seon.route/*` rows per cluster ([[resolve-routes]]);
     3. the global RENDER bounds — `:seon.config/render` (value/eval/message
        display caps), read by the [[database-edn-cap]] etc. accessors.

   READER — aero (`aero.core/read-config`), the SAME library the JVM track's
   `seon.config` (`config.clj`) uses, so the two tracks are coherent siblings on
   ONE `system.edn` mental model. aero's CLJS branch reads via `cljs.tools.reader`
   (already bundled by `seon.eval`) + Node `fs`. The render section uses aero's
   own `#long`/`#or`/`#env` tags (env OVERRIDES a manifest default) — no custom
   data-readers. The `#merge` tag is OVERRIDDEN here ([[merge-manifest-pair]]):
   aero ships a SHALLOW map merge, so a sparse per-cluster override
   (`config/acme.edn`) silently dropped the base's `:seon.agent/ctx` block tree —
   the override is now manifest-aware for that ONE key (inherit when sparse,
   replace when it declares `:seon.agent/ctx`). `seon.config` is shadow-COMPILED
   (not self-host), so `:require-macros` resolves at build time. If aero ever
   fails to compile/run the swap is one private fn ([[read-config-file]]) — the
   manifest SHAPE is reader-independent.

   EXTENSIBILITY (the 'add more things to it' contract) — a new config concern is
   FOUR mechanical steps, no reshape: (1) `schema/register!` a
   `:seon.config/<section>` shape, (2) add its key to `:seon.config/manifest`,
   (3) write one `resolve-<section>` fn here, (4) call it at the existing seed
   point. The manifest map IS the open registry; an UNKNOWN key fails LOUD at
   validation (a config typo is a crash, never a silent ignore).

   LEAF — `seon.config` produces block/route MAPS (data carrying literal quoted
   render symbols like `'my.skills/skill-block`), so it requires NEITHER
   `seon.agent.ctx` NOR `my.skills` (no var refs) — the seed-point call edges
   (`ctx → config`, `client → config`) stay one-way, no cycle. Its registered
   schemas therefore use LEAF shapes (`:keyword`, `[:vector :map]`): the full
   `:seon.agent.ctx/block` / `:my.skills/name` validation still happens
   downstream where those shapes are registered (`install!` validates each block,
   `transact!` validates each skill row)."
  (:require
    [aero.core :as aero]
    [malli.core :as m]
    [malli.transform :as mt]
    [seon.platform :as platform]
    [seon.schema :as schema]))

;;; SCHEMA — the `:seon.config/*` shapes. Registered before any reference to
;;; them. Roles are an open ENUM SELECTOR, never a stored `:seon.agent/kind`
;;; (root is identified by id "root", not a kind stamp). Skill names + block
;;; shapes are validated as leaf `:keyword` / `:map` here and fully validated
;;; downstream (the LEAF rule above), so this ns stays cycle-free.

;; The skills section carries ONLY the pull-reference corpus directory. Corpus
;; rows are never injected into an agent's context tree. Roots identify by id
;; "root", never a stored `:seon.agent/kind` / config `:role`.
(schema/register! :seon.config/skills-spec
  [:map
   ;; corpus dir(s); `skills-dir` reads the first entry, else SEON_SKILLS_DIR,
   ;; else `.claude/skills`.
   [:seon.config/dirs {:optional true} [:vector :string]]])

;;; NAMESPACES render policy (#42 explicit listing) — the single curation lever
;;; for the agent's prompt BODY. Signatures are RETIRED: every rendered ns
;;; renders FULL real source, so the only knobs are WHICH nses render (the
;;; explicit `:always` list, plus the agent's current ns + its requires +
;;; third-party code, resolved in `seon.agent.ctx.namespaces`) and whether the
;;; current ns renders at all. Token budget is bound by curation (which nses
;;; render), never by compression (how each renders).

(schema/register! :seon.config/current-ns [:enum :full :off])

(schema/register! :seon.config/namespaces-spec
  [:map
   ;; explicit always-present FULL-source nses
   [:seon.config/always     {:optional true} [:vector :symbol]]
   ;; the agent's CURRENT ns: :full (default — rendered) | :off (dropped)
   [:seon.config/current-ns {:optional true} :seon.config/current-ns]])

;; The RESOLVED policy the renderer + boot indexer read (symbols → ns-name
;; keywords, defaults applied). Registered once + referenced by
;; [[resolve-namespaces]].
(schema/register! :seon.config/namespaces-policy
  [:map
   [:seon.config/always     [:set :keyword]]
   [:seon.config/current-ns :seon.config/current-ns]])

(schema/register! :seon.config/route-spec
  [:map
   [:seon.config/removes {:optional true} [:vector :keyword]]])

;;; Per-knob LEAF attrs — each knob's ONE registered shape, referenced by BOTH
;;; the manifest section specs below AND the `:seon.config` singleton entity
;;; schema (config-db-migration 2026-07-10): register once, reference
;;; everywhere. Enum/int/boolean/string scalars store natively as singleton
;;; datoms; the collection knobs are registered with the singleton block (they
;;; ride the mixed-`:or` EDN-slot bridge, so their datom shape differs from
;;; their manifest-section shape).

;; Shared positive-int cap shape — every render/eval/timeout cap knob
;; references it (register-once, no inline duplication).
(schema/register! :seon.config/cap [:int {:min 1}])

(schema/register! :seon.config.render/database-edn-cap   :seon.config/cap)
(schema/register! :seon.config.render/eval-cap           :seon.config/cap)
(schema/register! :seon.config.render/message-cap        :seon.config/cap)
(schema/register! :seon.config.render/result-body-cap    :seon.config/cap)
(schema/register! :seon.config.render/value-max-depth    :seon.config/cap)
(schema/register! :seon.config.render/value-max-keys     :seon.config/cap)
(schema/register! :seon.config.render/value-max-items    :seon.config/cap)
(schema/register! :seon.config.render/value-max-string   :seon.config/cap)
(schema/register! :seon.config.render/value-shape-sample :seon.config/cap)
(schema/register! :seon.config.render/value-verbatim-cap :seon.config/cap)
(schema/register! :seon.config.render/value-width        :seon.config/cap)
;; TOKEN cap (not chars — the auto-run family is token-denominated) for ONE
;; current-ns auto-run render fn's ai output (seon.agent.ctx.render-fns).
(schema/register! :seon.config.render/render-fn-token-cap :seon.config/cap)
;; EXPLICIT-CHARACTER knobs (transcript-render redesign) — for content the
;; agent edits byte-exactly. Every DEFAULT reproduces today's bytes, so an
;; absent section / `{}` boot is byte-identical.
;;   :whitespace     :raw     — literal (default) | :visible — `·`/`→` glyphs
;;   :tabs           :literal — literal `\t` (default) | :arrow — `→`
;;   :trailing-ws    :off     — no marker (default) | :dot — `·` on trailing ws
;;   :content-layout :structured — multi-line body (default) | :single-line
;;   :line-numbers   false    — no gutter (default) | true — 1-based gutter
(schema/register! :seon.config.render/whitespace     [:enum :raw :visible])
(schema/register! :seon.config.render/tabs           [:enum :literal :arrow])
(schema/register! :seon.config.render/trailing-ws    [:enum :off :dot])
(schema/register! :seon.config.render/content-layout [:enum :structured :single-line])
(schema/register! :seon.config.render/line-numbers   :boolean)
;; Repair dial scalars (level enum inlined by the LEAF rule — `seon.config`
;; loads before `seon.repair`, so no keyword ref to `:seon.repair/level`).
(schema/register! :seon.config.repair/level
  [:enum :off :safe-syntax :symbols :aggressive])
(schema/register! :seon.config.repair/max-fixes-per-form :seon.config/cap)
(schema/register! :seon.config.repair/budget-ms          :seon.config/cap)
;; Multi-agent dials (watchdog staleness, schedule-breaker N + window).
(schema/register! :seon.config.watchdog/stale-ms    :seon.config/cap)
(schema/register! :seon.config.breaker/crash-count  :seon.config/cap)
(schema/register! :seon.config.breaker/window-ms    :seon.config/cap)

;;; RENDER BOUNDS — the GLOBAL, cluster-wide render/value display caps (#46).
;;; These are NOT per-agent: they bound the value/eval/message renderers for
;;; the whole cluster, seeded from this manifest section into the
;;; `:seon.config` singleton at boot. Env OVERRIDES config (owner model): the
;;; manifest declares each knob as `#long #or [#env SEON_RENDER_* default]` in
;;; `config/system.edn` — env set → the coerced env value, env unset → the
;;; manifest default. The keys here are `{:optional true}` WITHOUT a `:default`
;;; (decision: default in ONE place — the manifest `#or`); the accessors below
;;; apply the SAME literal as their own fallback when the section is absent.
(schema/register! :seon.config/render
  [:map
   [:seon.config.render/database-edn-cap   {:optional true} :seon.config.render/database-edn-cap]
   [:seon.config.render/eval-cap           {:optional true} :seon.config.render/eval-cap]
   [:seon.config.render/message-cap        {:optional true} :seon.config.render/message-cap]
   [:seon.config.render/result-body-cap    {:optional true} :seon.config.render/result-body-cap]
   [:seon.config.render/value-max-depth    {:optional true} :seon.config.render/value-max-depth]
   [:seon.config.render/value-max-keys     {:optional true} :seon.config.render/value-max-keys]
   [:seon.config.render/value-max-items    {:optional true} :seon.config.render/value-max-items]
   [:seon.config.render/value-max-string   {:optional true} :seon.config.render/value-max-string]
   [:seon.config.render/value-shape-sample {:optional true} :seon.config.render/value-shape-sample]
   [:seon.config.render/value-verbatim-cap {:optional true} :seon.config.render/value-verbatim-cap]
   [:seon.config.render/value-width        {:optional true} :seon.config.render/value-width]
   [:seon.config.render/render-fn-token-cap {:optional true} :seon.config.render/render-fn-token-cap]
   [:seon.config.render/whitespace     {:optional true} :seon.config.render/whitespace]
   [:seon.config.render/tabs           {:optional true} :seon.config.render/tabs]
   [:seon.config.render/trailing-ws    {:optional true} :seon.config.render/trailing-ws]
   [:seon.config.render/content-layout {:optional true} :seon.config.render/content-layout]
   [:seon.config.render/line-numbers   {:optional true} :seon.config.render/line-numbers]])

;; THE manifest — the registry of known sections. A future section = ONE more
;; optional key here + a resolver fn. Every key optional ⇒ `{}` (config absent)
;; validates ⇒ identity everywhere.
;;; ============================================================
;;; AGENT-CONTEXT — the v3 two-level context config (decisions 13/16/4). ONE
;;; nested map: agent-level scalars + a `:seon.agent/ctx` vector of BLOCK maps
;;; (component-ref'd onto the agent at transact). The manifest owns the desired
;;; seed tree; absent config means an empty tree, never a hidden code fallback.
;;; LEAF rule holds: the block vector is a loose
;;; `[:vector :map]` (block/attr shapes register + validate downstream at
;;; install!/transact!), so `seon.config` never requires `seon.agent.ctx` —
;;; the `:seon.render/ai` values are literal quoted symbols
;;; (VERIFIED to survive `m/decode` as `cljs.core/Symbol`), not var refs.
;;; ============================================================

;; Agent-level keys are persisted on the agent entity and read reactively:
;;       `:seon.agent.runtime/wake?` (gates the wake trigger at resume),
;;       `:seon.eval/home-requires` (the home-ns require list). These are
;;       declared HERE (referencing their owning ns's registered shape) so the
;;       recursive decode validates overrides before `seon.agent` includes
;;       them in the atomic birth transaction. Default = today's value ⇒ parity.
;; (The per-agent LLM / capabilities / toolkit / transcript-scalar keys are the
;; owner's pending three-fates call — added here the same way once decided.)
;; The persisted agent-level dials (`:seon.agent.runtime/wake?`, `:seon.eval/home-requires`)
;; carry NO schema `:default` here — their DEFAULT lives ONCE at the CONSUMER
;; (`seon.agent.runtime/wake-armed?` → true; `seon.agent.home/home-requires-for` → the
;; `home-ns-require-specs` const). So a no-config agent never gets the datom
;; (the consumer's fallback = byte-parity), and the manifest sets the key ONLY to
;; OVERRIDE. Declared LEAF-shaped (NOT a keyword ref — `seon.config` is a leaf
;; that loads before `seon.eval`/`seon.client`, and the full shape is validated
;; downstream at `transact!`, the same rule the block vector uses).
(schema/register! :seon.config/agent-context
  [:or
   [:map
    ;; Persisted agent datoms — override-only (no default; consumer owns it).
    [:seon.agent.runtime/wake? {:optional true} :boolean]
    [:seon.eval/home-requires {:optional true} [:vector :any]]
    [:seon.agent/ctx {:optional true :default []} [:vector :map]]]
   :nil])

;; The ROOT override — a SPARSE agent-context merged over `:seon.config/agent-context`
;; by [[context-config-for]] (block upsert-by-name). Its `:canvas` block sets
;; root's canvas = `system-view`, REPLACING the hardcoded client.cljs root branch.
;; NOT decoded through the transformer directly (it's a partial override layer);
;; only the MERGED result is decoded. Same loose `[:vector :map]` leaf shape.
(schema/register! :seon.config/root-context
  [:or
   [:map
    ;; root can override its home-ns require list (e.g. add `[seon.agent :as agent]`
    ;; so root additionally shows the orchestration card). Same leaf shape +
    ;; override-only semantics as `:seon.config/agent-context` — merged by
    ;; [[context-config-for]] onto the defaulted base for id "root".
    [:seon.eval/home-requires {:optional true} [:vector :any]]
    [:seon.agent/ctx {:optional true} [:vector :map]]]
   :nil])

;; The core-fault escalation dial (error-blame-strict-gate, RULED
;; 2026-07-04): what a `:core`-fault `seon.error/record!` does BEYOND
;; persisting its datom. `:crash` = persist first, then loud exit (dev,
;; after the catch-site sweep); `:gate` = pod stays alive, the CI-shaped
;; wrappers (bin/test-cljs, dev hook) fail any run that accumulated one
;; (the SHIPPED default); `:log` = datom + derived section only
;; (prod/demo). `:agent` faults never escalate in ANY mode.
(schema/register! :seon.config/on-core-error [:enum :crash :gate :log])

;;; WEB-ACCESS POLICY — the host-owned reachability policy for
;;; `seon.agent.web/fetch` (UNIFIES the old private-range SSRF guard + domain
;;; allowlist into ONE config). The AUTHORITATIVE `:seon.agent.web/policy` enum
;;; (:open/:public-only/:allowlist) lives in `seon.agent.web` (the owning ns).
;;; Here the mode is validated as a LEAF `:keyword` — the LEAF rule: `seon.config`
;;; loads BEFORE `seon.agent.web`, and `schema/register!` asserts compilability
;;; EAGERLY, so a forward keyword-ref would break boot. The enum-level check
;;; happens downstream in [[web-policy]] (coerces an unrecognized mode to the
;;; SSRF-safe `:public-only` — fail-closed, never open by accident).
;;; `allowed-domains` matters only under `:allowlist`. Absent section ⇒ the
;;; accessor's `:public-only` fallback. The master on/off grant (SEON_WEB) stays
;;; ENV — it gates whether web is available at all; this policy shapes
;;; reachability once it is (env gate + config policy, two concerns).
;;; The same section also carries the WEB-SEARCH backend (`seon.agent.web/search`):
;;; `:seon.agent.web/search-backend` (which grounded/SERP provider — leaf
;;; `:keyword`, enum-checked downstream in [[web-search-config]], default
;;; `:gemini-grounding`) + `:seon.agent.web/search-model` (the model id for the
;;; grounded backend, default `"gemini-3.1-flash-lite"`). Backend choice is
;;; host-owned CONFIG (never env — env carries only the API KEY, read live);
;;; a new backend (Serper) slots in here WITHOUT changing the function shape.
(schema/register! :seon.config/web-spec
  [:map
   [:seon.agent.web/policy          {:optional true} :keyword]
   [:seon.agent.web/allowed-domains {:optional true} [:vector :string]]
   [:seon.agent.web/search-backend  {:optional true} :keyword]
   [:seon.agent.web/search-model    {:optional true} :string]])

;;; FORM-AUTOFIX (repair) — the pre-flight repair dial (owner rulings
;;; 2026-07-05; design docs/prds/agent-ctx/research/form-autofix-system-
;;; 2026-07-05.md). Levels as config data; per-class kill switches are a
;;; `{class-kw boolean}` map COMBINED with the class registry in
;;; `seon.repair/class-levels` (enablement is computed, never a call-site
;;; list). `:aggressive` is an enum slot only — not implemented. `classes`
;;; stays a plain map HERE (the manifest shape); its DATOM shape is the
;;; mixed-`:or` EDN-slot registration in the singleton block below.
(schema/register! :seon.config/repair
  [:map
   [:seon.config.repair/level              {:optional true} :seon.config.repair/level]
   [:seon.config.repair/classes            {:optional true} [:map-of :keyword :boolean]]
   [:seon.config.repair/max-fixes-per-form {:optional true} :seon.config.repair/max-fixes-per-form]
   [:seon.config.repair/budget-ms          {:optional true} :seon.config.repair/budget-ms]])

;;; MULTI-AGENT dials (multiagent-context-spec). All three are core config
;;; numbers, plumbed the same way as `:seon.config/on-core-error` (a plain
;;; literal in the manifest, absent ⇒ the accessor's default): the spawn
;;; DEPTH cap (Piece 2 — how deep the spawn tree may go), the heartbeat
;;; WATCHDOG staleness threshold (Piece 2c), and the schedule-wake circuit
;;; BREAKER's N + window (Piece 2d). Absent ⇒ byte-identical to the defaults.
(schema/register! :seon.config/spawn-depth-cap [:int {:min 0}])
(schema/register! :seon.config/watchdog
  [:map [:seon.config.watchdog/stale-ms {:optional true} :seon.config.watchdog/stale-ms]])
(schema/register! :seon.config/schedule-breaker
  [:map
   [:seon.config.breaker/crash-count {:optional true} :seon.config.breaker/crash-count]
   [:seon.config.breaker/window-ms   {:optional true} :seon.config.breaker/window-ms]])

;;; REPL MODE (repl-mode Phase 1) — how the agent's REPL turn resolves a
;;; form's result. The DEFAULT is per-MODEL ([[default-repl-mode]]); an
;;; explicit manifest value always wins. `:batch`: the turn is one LLM call writing N
;;; forms; a model-typed result is STRIPPED at the reply boundary and the
;;; real values arrive interleaved next turn. `:stream`: the SDK stream is
;;; consumed delta-by-delta and ABORTED the moment one complete top-level
;;; form has streamed — one form per turn, its real value in the next
;;; transcript. The manifest value is read ONCE at boot and reconciled into
;;; a DB datom on the singleton cluster-config entity; the turn loop + the
;;; transcript masthead read the DATOM (config-through-DB), never this key.
(schema/register! :seon.config/repl-mode [:enum :batch :stream])

;;; RUN RESOURCE POLICY — generous safety ceilings, not normal stop reasons.
;;; The manifest section resolves into three scalar singleton datoms so the
;;; frozen database value completely determines what a new run will seed.
(schema/register! :seon.config.run/batch-turn-limit  [:int {:default 100 :min 1}])
(schema/register! :seon.config.run/stream-form-limit [:int {:default 300 :min 1}])
(schema/register! :seon.config.run/deadline-ms       [:int {:default 1800000 :min 1}])
(schema/register! :seon.config/run
  [:map
   [:seon.config.run/batch-turn-limit  {:optional true} :seon.config.run/batch-turn-limit]
   [:seon.config.run/stream-form-limit {:optional true} :seon.config.run/stream-form-limit]
   [:seon.config.run/deadline-ms       {:optional true} :seon.config.run/deadline-ms]])

;;; ============================================================
;;; THE `:seon.config` SINGLETON — one cluster-config entity, ATTRIBUTE-PER-KEY
;;; (config-db-migration-spec 2026-07-10). The owner contract: config is read
;;; at BOOT and TRANSACTED into the db (this singleton); from then on EVERY
;;; runtime read is a db query ([[config-view]] via the injected [[!db-config-view]]
;;; seam). Each knob is its OWN registered attr — a real type is the knob's
;;; contract, NEVER an EDN-blob dump of the whole config. Three collection knobs
;;; (`:seon.config/always`, `:seon.config.repair/classes`,
;;; `:seon.agent.web/allowed-domains`) ride the ESTABLISHED mixed-`:or` EDN-slot
;;; bridge (the `:seon.eval/home-requires` precedent) — one cardinality-one
;;; datom that upsert REPLACES (so a shrunk list heals, no accumulation). The
;;; singleton is ONE entity in the boot `#{:config}` `seon.state/reconcile!`
;;; desired set (routes/skills pattern) — upsert-by-identity keeps it current +
;;; retract-protected; NO second mechanism.
;;; ============================================================

;; The fixed singleton identity value — the one cluster-config entity per store.
(def cluster-config-id "cluster")

(schema/register! :seon.config/id [:and {:seon.db/identity true} :string])

;;; The scalar/enum per-knob attrs are registered ONCE with their manifest
;;; section specs above (the LEAF-attr block before `:seon.config/render`) —
;;; the singleton entity schema below references those registrations. Only
;;; the knobs whose DATOM shape differs from their manifest shape live here:
;;; the three collections ride the mixed-`:or` EDN-slot bridge (a `:nil` alt
;;; makes it a mixed `:or` so `transact!` pr-str's the value and
;;; `config-view` `decode-edn-value`s it back).
;; The always-on FULL-source ns render list — the resolved keyword set
;; (`:seon.config/namespaces` `:always`). EDN-slot bridged (mixed `:or`).
(schema/register! :seon.config/always [:or [:set :keyword] :nil])
;; Selected skill-corpus input. The manifest shape is a map; the database slot
;; is cardinality-one EDN so replacement is exact and optional means absent.
(schema/register! :seon.config/skills [:or :seon.config/skills-spec :nil])
;; The per-class repair kill-switch map `{class-kw boolean}`. EDN-slot bridged.
(schema/register! :seon.config.repair/classes [:or [:map-of :keyword :boolean] :nil])
;; The web allowlist hosts (meaningful only under `:allowlist`). EDN-slot bridged.
(schema/register! :seon.agent.web/allowed-domains [:or [:vector :string] :nil])
;; The cluster system-prompt TEXT — OPTIONAL, no default (absent ⇒ not seeded
;; ⇒ `seon.ai/effective-system-prompt` falls through to the shipped
;; `seon.agent.ctx/system-text`, preserving the pre-datom behavior). The
;; read side IS wired: request override → THIS datom (via [[config-view]]) →
;; the shipped default. The value is the literal prompt string (a manifest
;; keeps it inline; `config/minimal.edn` is the worked example).
(schema/register! :seon.config/system-text :string)
;; Named context RENDER PROFILES — `{profile-kw → [block-patch …]}`, each
;; patch a `:seon.agent.ctx/profile` entry (block name + per-block config
;; overrides) that `seon.agent.ctx/render-context` renders as a curated
;; subset of the agent's blocks. Config-through-DB so an as-of render
;; regenerates under the profile IN FORCE at that t (the byte-exact
;; contract — `seon.repl.autocomplete` reads its `:autocomplete` profile
;; off the passed db value, code default when absent). OPTIONAL, no
;; default (absent ⇒ not seeded ⇒ consumers use their code defaults).
;; EDN-slot bridged (mixed `:or`, the ::home-requires pattern).
(schema/register! :seon.config/context-profiles
  [:or [:map-of :keyword [:vector :map]] :nil])

;; The singleton entity schema — every knob optional (a `{}` manifest seeds the
;; resolved defaults; `:seon.config/id` is the only required key).
(schema/register! :seon.config/singleton
  [:map {:seon.db/entity true}
   [:seon.config/id                          :seon.config/id]
   [:seon.config/skills             {:optional true} :seon.config/skills]
   [:seon.config/repl-mode          {:optional true} :seon.config/repl-mode]
   [:seon.config.run/batch-turn-limit  {:optional true} :seon.config.run/batch-turn-limit]
   [:seon.config.run/stream-form-limit {:optional true} :seon.config.run/stream-form-limit]
   [:seon.config.run/deadline-ms       {:optional true} :seon.config.run/deadline-ms]
   [:seon.config/current-ns         {:optional true} :seon.config/current-ns]
   [:seon.config/on-core-error      {:optional true} :seon.config/on-core-error]
   [:seon.config/spawn-depth-cap    {:optional true} :seon.config/spawn-depth-cap]
   [:seon.config/always             {:optional true} :seon.config/always]
   [:seon.config/system-text        {:optional true} :seon.config/system-text]
   [:seon.config/context-profiles   {:optional true} :seon.config/context-profiles]
   [:seon.config/agent-context      {:optional true} :seon.config/agent-context]
   [:seon.config/root-context       {:optional true} :seon.config/root-context]
   [:seon.config.render/database-edn-cap   {:optional true} :seon.config/cap]
   [:seon.config.render/eval-cap           {:optional true} :seon.config/cap]
   [:seon.config.render/message-cap        {:optional true} :seon.config/cap]
   [:seon.config.render/result-body-cap    {:optional true} :seon.config/cap]
   [:seon.config.render/value-max-depth    {:optional true} :seon.config/cap]
   [:seon.config.render/value-max-keys     {:optional true} :seon.config/cap]
   [:seon.config.render/value-max-items    {:optional true} :seon.config/cap]
   [:seon.config.render/value-max-string   {:optional true} :seon.config/cap]
   [:seon.config.render/value-shape-sample {:optional true} :seon.config/cap]
   [:seon.config.render/value-verbatim-cap {:optional true} :seon.config/cap]
   [:seon.config.render/value-width        {:optional true} :seon.config/cap]
   [:seon.config.render/render-fn-token-cap {:optional true} :seon.config/cap]
   [:seon.config.render/whitespace     {:optional true} :seon.config.render/whitespace]
   [:seon.config.render/tabs           {:optional true} :seon.config.render/tabs]
   [:seon.config.render/trailing-ws    {:optional true} :seon.config.render/trailing-ws]
   [:seon.config.render/content-layout {:optional true} :seon.config.render/content-layout]
   [:seon.config.render/line-numbers   {:optional true} :seon.config.render/line-numbers]
   [:seon.config.repair/level              {:optional true} :seon.config.repair/level]
   [:seon.config.repair/max-fixes-per-form {:optional true} :seon.config/cap]
   [:seon.config.repair/budget-ms          {:optional true} :seon.config/cap]
   [:seon.config.repair/classes            {:optional true} :seon.config.repair/classes]
   ;; LEAF types for the web knobs — `seon.agent.web` (which registers the
   ;; authoritative `:seon.agent.web/policy`/`search-backend`/`search-model`
   ;; enums) loads AFTER this leaf ns, so a schema-keyword ref here would break
   ;; boot. The DATOM's storage type comes from web's own registration (present
   ;; by boot-seed time); this entity schema only validates loosely.
   [:seon.agent.web/policy          {:optional true} :keyword]
   [:seon.agent.web/search-backend  {:optional true} :keyword]
   [:seon.agent.web/search-model    {:optional true} :string]
   [:seon.agent.web/allowed-domains {:optional true} :seon.agent.web/allowed-domains]
   [:seon.config.watchdog/stale-ms   {:optional true} :seon.config/cap]
   [:seon.config.breaker/crash-count {:optional true} :seon.config/cap]
   [:seon.config.breaker/window-ms   {:optional true} :seon.config/cap]])

;; ── The db-read seam (config ← db injection) ──
;; `seon.config` CANNOT require `seon.db` (the require direction is
;; db→error→config), so — exactly like `seon.error`'s `!db-hooks` — `seon.db`
;; INJECTS a reader at its load. [[config-view]] reads the singleton through it
;; POST-conn; a nil reader / no conn / unseeded singleton falls back to the
;; boot manifest resolve (the pre-conn sliver). ONE switchover point.
(defonce ^:private !db-config-view
  ;; fn of [] → the DECODED singleton config map (collections decoded), or nil
  ;; when no conn / the singleton is not yet seeded.
  (atom nil))

(defn set-db-config-view!
  "Install the singleton-config reader [[config-view]] uses post-conn.

   Called ONCE by `seon.db` at namespace load (require dir is db→config, so
   the read path is injected, not required — mirrors `seon.error/set-db-hooks!`)."
  {:malli/schema [:=> [:cat fn?] :nil]}
  [f]
  (reset! !db-config-view f)
  nil)

(schema/register! :seon.config/manifest
  [:map
   [:seon.config/skills        {:optional true} :seon.config/skills-spec]
   [:seon.config/repl-mode     {:optional true} :seon.config/repl-mode]
   [:seon.config/run           {:optional true} :seon.config/run]
   [:seon.config/namespaces    {:optional true} :seon.config/namespaces-spec]
   [:seon.config/routes        {:optional true} [:vector :seon.config/route-spec]]
   [:seon.config/render        {:optional true} :seon.config/render]
   [:seon.config/system-text   {:optional true} :seon.config/system-text]
   [:seon.config/context-profiles {:optional true} :seon.config/context-profiles]
   [:seon.config/on-core-error {:optional true} :seon.config/on-core-error]
   [:seon.config/web           {:optional true} :seon.config/web-spec]
   [:seon.config/repair        {:optional true} :seon.config/repair]
   [:seon.config/spawn-depth-cap   {:optional true} :seon.config/spawn-depth-cap]
   [:seon.config/watchdog          {:optional true} :seon.config/watchdog]
   [:seon.config/schedule-breaker  {:optional true} :seon.config/schedule-breaker]
   [:seon.config/agent-context {:optional true} :seon.config/agent-context]
   [:seon.config/root-context  {:optional true} :seon.config/root-context]])

;;; Function arg/return shapes — leaf `[:vector :map]` (full shapes validated
;;; downstream); registered once + referenced so the resolver specs don't
;;; re-inline the shape.
(schema/register! ::agent-id :string)
(schema/register! ::routes [:vector :map])

(defn- env
  "A `process.env` value, nil when unset/blank — `seon.platform/env-val`, the
   ONE env reader."
  [var-name]
  (platform/env-val var-name))

;;; ── `#merge` COMPOSITION — the manifest-aware combine (config-merge trap,
;;; 2026-07-11) ──
;;; A per-cluster manifest (`config/acme.edn`, `config/minimal*.edn`) composes as
;;; `#merge [#include "system.edn" {overrides}]`. Aero's SHIPPED `#merge` reader
;;; is `(apply merge values)` — a SHALLOW map merge, so a top-level key in the
;;; override REPLACES the base's value WHOLESALE. That is correct for a scalar
;;; knob but WRONG for the nested `:seon.config/agent-context`: a sparse override
;;; that sets only `:seon.eval/home-requires` silently DROPPED the base's
;;; `:seon.agent/ctx` block tree, and the schema `:default` then quietly filled
;;; the LEGACY tree — acme ran the wrong context for a day (the 1bd1d21d cutover
;;; regression). We OVERRIDE aero's `'merge` reader (its `reader` multimethod is
;;; the designed extension point) with a manifest-aware combine that applies —
;;; for the `:seon.config/agent-context` key ONLY — the SAME replaces-wholesale
;;; rule [[resolve-agent-context]] already documents: an override that declares
;;; `:seon.agent/ctx` replaces the tree wholesale; a SPARSE override (no
;;; `:seon.agent/ctx`) is a PATCH whose keys win while every unstated key
;;; (`:seon.agent/ctx` included) inherits from the base. Every OTHER top-level
;;; key stays shallow-replace, byte-identical to aero's default — so the
;;; wholesale-replacing minimal.edn family (which DECLARES `:seon.agent/ctx`, and
;;; deliberately drops the base home-requires to fall back to the consumer
;;; default) is untouched. ONE merge rule, applied at the ONE composition seam.

(defn- combine-agent-context
  "Combine a base `:seon.config/agent-context` map with an `override` map.

   An `override` that declares `:seon.agent/ctx` REPLACES the whole map (the
   documented replaces-wholesale contract the minimal.edn family relies on to
   also drop the base `:seon.eval/home-requires`). A SPARSE `override` (no
   `:seon.agent/ctx`) is a PATCH: its keys win, every unstated key — the
   `:seon.agent/ctx` block tree included — inherits from `base`. Matches
   [[resolve-agent-context]]'s `explicit-ctx?` rule."
  [base override]
  (if (contains? override :seon.agent/ctx)
    override
    (merge base override)))

(defn- merge-manifest-pair
  "Shallow-merge `override` over `base`, then re-combine the nested
   `:seon.config/agent-context` maps via [[combine-agent-context]] so a sparse
   override can never silently drop the base's block tree. Only that ONE key is
   special-cased; every other top-level key keeps aero's shallow replace."
  [base override]
  (let [m (merge base override)]
    (cond-> m
      (and (map? (:seon.config/agent-context base))
           (map? (:seon.config/agent-context override)))
      (assoc :seon.config/agent-context
             (combine-agent-context (:seon.config/agent-context base)
                                    (:seon.config/agent-context override))))))

;; OVERRIDE aero's built-in `#merge` (shipped `(apply merge values)`) with the
;; manifest-aware fold. Registered at ns load — `seon.config` is the pod's ONLY
;; aero user, so the blast radius is exactly the config-manifest composition this
;; fixes. `values` is the vector of maps after the `#merge` tag.
(defmethod aero/reader 'merge
  [_opts _tag values]
  (reduce merge-manifest-pair {} values))

(defn- read-config-file
  "Read + resolve `path` via aero. The ONE reader seam — the manifest shape is
   reader-independent, so a fallback to `cljs.reader/read-string` would swap only
   this body. A per-cluster variant is a SEPARATE file pointed at by
   `SEON_CONFIG` (no `#profile` — one file, one shape). The render section's
   `#long`/`#or`/`#env` tags resolve through aero's own readers here; the
   `#merge` tag uses our manifest-aware [[merge-manifest-pair]] override above
   (a sparse agent-context override can never silently drop the block tree)."
  [path]
  (aero/read-config path {}))

(defn load-manifest
  "Read the explicitly selected `SEON_CONFIG` manifest.

   Returns nil when no path is selected: absence means preserve the database,
   not apply an implicit empty/default desired state. A selected path must
   exist and validate; missing or invalid explicit input fails loudly."
  {:malli/schema [:=> [:cat] [:maybe :seon.config/manifest]]}
  []
  (when-let [path (env "SEON_CONFIG")]
    (let [fs (js/require "fs")]
      (when-not (try (.existsSync fs path) (catch :default _ false))
        (throw (ex-info (str "seon.config: selected manifest does not exist: " path)
                        {:seon.config/path path
                         :seon.error/kind  :user-input})))
      (let [raw (read-config-file path)]
        (if (m/validate :seon.config/manifest raw)
          raw
          (throw (ex-info
                   (str "seon.config: invalid manifest at " path ": "
                        (m/explain :seon.config/manifest raw))
                   {:seon.config/path  path
                    :seon.error/kind   :user-input})))))))

;;; ============================================================
;;; NAMESPACES POLICY — the explicit-listing resolver (#42). The SHIPPED
;;; default reproduces the pre-config hardcoded rules BYTE-IDENTICALLY; a lean
;;; cluster overrides it with a short explicit list. Pure given the manifest;
;;; the live accessor [[namespaces-policy]] reads [[config-view]] (the db
;;; singleton post-conn, the manifest resolve pre-conn — config-through-DB).
;;; ============================================================

(def ^:private default-namespaces-policy
  "The SHIPPED default namespaces render policy. Everything renders FULL real
   source; this is just WHICH nses are always present: the `my.*` toolkit
   exemplars plus the core function nses the agent calls constantly
   (`my.plan`/`seon.agent.message`/`seon.agent.lifecycle`). The agent's
   CURRENT ns and the nses it `:require`s render full on top of this (resolved
   in `seon.agent.ctx.namespaces`). `config/system.edn` mirrors this list
   verbatim for visibility; a lean cluster overrides it with a short explicit
   list (the curation lever)."
  {:seon.config/always     '[my.kb my.data my.ui my.canvas
                             my.plan seon.agent.message seon.agent.lifecycle]
   :seon.config/current-ns :full})

(defn- ns-sym->kw
  "An ns-name SYMBOL (config) → its ns-name KEYWORD (the DB `:seon.ns/name`
   shape the renderer matches): `my.kb` → `:my.kb`."
  [s]
  (keyword (str s)))

(defn resolve-namespaces
  "Resolve the `:seon.config/namespaces` section into render policy.

   The `manifest` section becomes the policy
   the renderer + boot indexer read (`seon.agent.ctx.namespaces`). KEY-LEVEL
   merge over [[default-namespaces-policy]]: an absent section ⇒ the
   byte-identical default; a present section overrides ONLY the keys it lists.
   Symbols become ns-name keywords."
  {:malli/schema [:=> [:catn [::manifest :seon.config/manifest]]
                  :seon.config/namespaces-policy]}
  [manifest]
  (let [merged (merge default-namespaces-policy (:seon.config/namespaces manifest))]
    {:seon.config/always     (into #{} (map ns-sym->kw) (:seon.config/always merged))
     :seon.config/current-ns (:seon.config/current-ns merged)}))

;;; ============================================================
;;; THE RESOLVER + THE RUNTIME READ. `resolve-config-singleton` maps a manifest
;;; to the FLAT `:seon.config` singleton entity map — every SCALAR knob resolved
;;; to its EFFECTIVE value (env→manifest→default, coerced) + the three decoded
;;; collections. It is the ONE resolution point, used BOTH to SEED the db at
;;; boot AND as the PRE-CONN fallback [[config-view]] reads before the conn
;;; exists. `config-view` is the runtime read: the db singleton POST-conn (via
;;; the injected [[!db-config-view]] seam), else the manifest resolve. The ~30
;;; accessors below are thin `(get (config-view) attr …)` reads — same names +
;;; arities as before (the coordination contract; ~40 caller sites unchanged).
;;; ============================================================

(def ^:private default-transformer
  "The one recursive schema-default resolver for config sections. Optional
   keys carrying Malli defaults are materialized before singleton flattening."
  (mt/default-value-transformer {:malli.transform/add-optional-keys true}))

(defn default-run-policy
  "The run section with every schema default materialized.

   This is the sole no-manifest fallback used before a config singleton exists;
   normal runtime reads the resolved scalar datoms from the database."
  {:malli/schema [:=> [:cat] :seon.config/run]}
  []
  (m/decode :seon.config/run {} default-transformer))

(defn- coerce-enum
  "`v` when it is in `allowed`, else `fallback` — the belt for a stale/invalid
   value (the manifest validator already rejects a bad literal loudly at load)."
  [v allowed fallback]
  (if (contains? allowed v) v fallback))

(defn- default-repl-mode
  "The per-MODEL `:seon.config/repl-mode` default (manifest absent).

   Measured 2026-07-10 (evals/runs/2026-07-10-minimal-buildup): DeepSeek
   pattern-completes typed results in ~32-48% of `:batch` turns and
   `:stream` eliminates that structurally, while Spark-class
   instruction-followers are ~0-fab in `:batch` and pay `:stream`'s
   extra per-turn latency for nothing. The rule is computed from the
   model identity the `:seon.ai/config` row seeds from — the SAME
   boot-seed moment as this resolver (`seon.ai` sits above config, so
   the env is read directly; `:deepseek` is [[seon.ai/provider]]'s
   documented default when the env is unset). An explicit manifest
   `:seon.config/repl-mode` always wins."
  []
  (let [provider (or (env "SEON_AI_PROVIDER") "deepseek")
        model    (or (env "SEON_AI_MODEL") "")]
    (if (or (= "deepseek" provider) (re-find #"deepseek" model))
      :stream
      :batch)))

(defn resolve-config-singleton
  "The FLAT `:seon.config` singleton entity map for `manifest`.

   Every knob RESOLVED to its effective value (the default reproduces today's
   byte-parity behavior). The one resolution point — seeds the db at boot AND
   is the pre-conn fallback [[config-view]] reads. `:seon.config/system-text` is
   OPTIONAL (no default): included ONLY when the manifest carries it; the exact
   desired-state reconcile retracts a previously stored value when it is later
   omitted."
  {:malli/schema [:=> [:catn [::manifest :seon.config/manifest]] :seon.config/singleton]}
  [manifest]
  (let [r   (get manifest :seon.config/render {})
        run (merge (default-run-policy) (get manifest :seon.config/run {}))
        rep (get manifest :seon.config/repair {})
        web (get manifest :seon.config/web {})
        nsp (resolve-namespaces manifest)]
    (cond-> {:seon.config/id cluster-config-id
             :seon.config/repl-mode
             (let [d (default-repl-mode)]
               (coerce-enum (get manifest :seon.config/repl-mode d) #{:batch :stream} d))
             :seon.config.run/batch-turn-limit
             (:seon.config.run/batch-turn-limit run)
             :seon.config.run/stream-form-limit
             (:seon.config.run/stream-form-limit run)
             :seon.config.run/deadline-ms
             (:seon.config.run/deadline-ms run)
             :seon.config/current-ns (:seon.config/current-ns nsp)
             :seon.config/always     (:seon.config/always nsp)
             :seon.config/on-core-error
             (coerce-enum (get manifest :seon.config/on-core-error :gate) #{:crash :gate :log} :gate)
             :seon.config/spawn-depth-cap
             (let [v (get manifest :seon.config/spawn-depth-cap 1)] (if (and (int? v) (>= v 0)) v 1))
             :seon.config.render/database-edn-cap   (get r :seon.config.render/database-edn-cap 16384)
             :seon.config.render/eval-cap           (get r :seon.config.render/eval-cap 1500)
             :seon.config.render/message-cap        (get r :seon.config.render/message-cap 4000)
             :seon.config.render/result-body-cap    (get r :seon.config.render/result-body-cap 16384)
             :seon.config.render/value-max-depth    (get r :seon.config.render/value-max-depth 3)
             :seon.config.render/value-max-keys     (get r :seon.config.render/value-max-keys 8)
             :seon.config.render/value-max-items    (get r :seon.config.render/value-max-items 8)
             :seon.config.render/value-max-string   (get r :seon.config.render/value-max-string 80)
             :seon.config.render/value-shape-sample (get r :seon.config.render/value-shape-sample 8)
             :seon.config.render/value-verbatim-cap (get r :seon.config.render/value-verbatim-cap 1500)
             :seon.config.render/value-width        (get r :seon.config.render/value-width 72)
             :seon.config.render/render-fn-token-cap (get r :seon.config.render/render-fn-token-cap 2000)
             :seon.config.render/whitespace     (get r :seon.config.render/whitespace :raw)
             :seon.config.render/tabs           (get r :seon.config.render/tabs :literal)
             :seon.config.render/trailing-ws    (get r :seon.config.render/trailing-ws :off)
             :seon.config.render/content-layout (get r :seon.config.render/content-layout :structured)
             :seon.config.render/line-numbers   (boolean (get r :seon.config.render/line-numbers false))
             :seon.config.repair/level
             (coerce-enum (get rep :seon.config.repair/level :symbols)
                          #{:off :safe-syntax :symbols :aggressive} :symbols)
             :seon.config.repair/max-fixes-per-form (get rep :seon.config.repair/max-fixes-per-form 1)
             :seon.config.repair/budget-ms          (get rep :seon.config.repair/budget-ms 50)
             :seon.config.repair/classes            (get rep :seon.config.repair/classes {})
             :seon.agent.web/policy
             (coerce-enum (get web :seon.agent.web/policy :public-only)
                          #{:open :public-only :allowlist} :public-only)
             :seon.agent.web/search-backend
             (coerce-enum (get web :seon.agent.web/search-backend :gemini-grounding)
                          #{:gemini-grounding :serper} :gemini-grounding)
             :seon.agent.web/search-model    (get web :seon.agent.web/search-model "gemini-3.1-flash-lite")
             :seon.agent.web/allowed-domains (vec (get web :seon.agent.web/allowed-domains []))
             :seon.config.watchdog/stale-ms
             (get-in manifest [:seon.config/watchdog :seon.config.watchdog/stale-ms] 1200000)
             :seon.config.breaker/crash-count
             (get-in manifest [:seon.config/schedule-breaker :seon.config.breaker/crash-count] 3)
             :seon.config.breaker/window-ms
             (get-in manifest [:seon.config/schedule-breaker :seon.config.breaker/window-ms] 1800000)}
      (contains? manifest :seon.config/system-text)
      (assoc :seon.config/system-text (:seon.config/system-text manifest))
      (contains? manifest :seon.config/context-profiles)
      (assoc :seon.config/context-profiles (:seon.config/context-profiles manifest))
      (contains? manifest :seon.config/skills)
      (assoc :seon.config/skills (:seon.config/skills manifest))
      (contains? manifest :seon.config/agent-context)
      (assoc :seon.config/agent-context (:seon.config/agent-context manifest))
      (contains? manifest :seon.config/root-context)
      (assoc :seon.config/root-context (:seon.config/root-context manifest)))))

(defn config-view
  "The live config singleton map — database post-attach, explicit input pre-attach.

   The ONE switchover: the injected [[!db-config-view]] seam reads the seeded
   `:seon.config` singleton once the conn is up (config-through-DB); before the
   conn exists (the bootstrap sliver — the `on-core-error` dial can fire during
   database attach) it resolves an explicitly selected manifest or the code
   defaults. Post-attach the db value is authoritative, so a live
   `db/transact!` to the singleton or an explicit config apply reaches every
   accessor. Collections are already decoded by the seam."
  {:malli/schema [:=> [:cat] :map]}
  []
  (or (when-let [f @!db-config-view] (f))
      (resolve-config-singleton (or (load-manifest) {}))))

(defn namespaces-policy
  "The resolved namespaces render policy — read from the config singleton.

   `{:seon.config/always #{ns-kw…} :seon.config/current-ns :full|:off}` off the
   db singleton (config-through-DB) via [[config-view]]; the boot manifest
   resolve before the conn. The ONE policy `seon.agent.ctx.namespaces` reads."
  {:malli/schema [:=> [:cat] :seon.config/namespaces-policy]}
  []
  (let [v (config-view)]
    {:seon.config/always     (or (:seon.config/always v) #{})
     :seon.config/current-ns (or (:seon.config/current-ns v) :full)}))

;;; ============================================================
;;; ENV KNOBS — the ONE typed env surface for the FEW knobs that stay env-only
;;; (launch-wiring + process-level flags). `platform/env-val` is the single
;;; low-level reader (a raw `process.env` lookup); `seon.config` is the typed
;;; layer on top — `env-string` / `env-int` plus the named accessors below.
;;; The render/value DISPLAY caps live in the manifest's `:seon.config/render`
;;; section (a proper config, not scattered env reads — #46); an env override on
;;; a specific cap is still available per-knob via aero's `#long #or [#env …]`
;;; tag IN the manifest, but is not pre-wired. A consumer that needs a one-off
;;; process flag calls `env-string`/`env-int` here with its var name (e.g.
;;; `agent/fs` reads SEON_FS_* via `env-string`).
;;;
;;; THREE surfaces legitimately read env OUTSIDE this layer, none a tuning knob:
;;;   1. the LLM-provider config seam `seon.ai` (SEON_AI_* → the DB-owned
;;;      `:seon.ai/config` row — its own consolidated env→DB surface; per-agent
;;;      overrides + retries are agent-entity datoms, not env);
;;;   2. process-launch / infra wiring read at its point of use (SEON_PORT[_FILE],
;;;      SEON_CLUSTER_DIR, SEON_REQ_SOCK/SEON_PUB_SOCK, SEON_AGENT_ID, and the
;;;      SEON_EMBED feature gate) — launch wiring, not agent-tunable knobs;
;;;   3. `platform`'s own pre-config SEON_RUNTIME_ROOT path resolution — it is
;;;      the leaf this ns sits on, so it cannot require config without a cycle.
;;; ============================================================

(defn env-string
  "The raw string value of env `var-name`, or nil when unset/blank.

   The base
   typed read every named knob below sits on."
  {:malli/schema [:=> [:catn [::var-name :string]] [:maybe :string]]}
  [var-name]
  (env var-name))

(defn env-int
  "Env `var-name` as a POSITIVE int, or `default` if unusable.

   `default` when unset / blank /
   non-numeric / non-positive — the shared cap-knob reader."
  {:malli/schema [:=> [:catn [::var-name :string] [::default :int]] :int]}
  [var-name default]
  (let [v (some-> (env var-name) js/parseInt)]
    (if (and (number? v) (not (js/isNaN v)) (pos? v)) v default)))

(defn skills-dir
  "The skills corpus directory (database config, else env, else default).

   The manifest's `:seon.config/skills`
   `:seon.config/dirs` first entry when present, else `SEON_SKILLS_DIR`, else
   `.claude/skills` (the standard Claude-Code layout humans edit too). This is
   where `:seon.config/dirs` is finally consumed — the last hardcoded env read
   folded into the config seam.

   The corpus is a CHECKOUT ARTIFACT (a skills dir in the seon tree), so a
   RELATIVE value resolves via `seon.platform/artifact-path` — under
   SEON_RUNTIME_ROOT when set (a containerized/downstream pod running from
   its own data root), else CWD-relative (seon's own usage, byte-identical).
   An absolute value is used as-is."
  {:malli/schema
   [:function
    [:=> [:cat] :string]
    [:=> [:catn [::manifest :seon.config/manifest]] :string]]}
  ([] (skills-dir (config-view)))
  ([manifest]
   (let [dir (or (some-> manifest :seon.config/skills :seon.config/dirs first)
                 (env "SEON_SKILLS_DIR")
                 ".claude/skills")]
     (if (.startsWith dir "/")
       dir
       (platform/artifact-path dir)))))

(defn extra-src
  "`SEON_EXTRA_SRC` — a downstream's compiled-in source root, or nil.

   Its `/src` +
   `/test` get probed after the seon artifact roots; nil when unset."
  {:malli/schema [:=> [:cat] [:maybe :string]]}
  []
  (env "SEON_EXTRA_SRC"))

(defn no-auto-boot?
  "True when `SEON_NO_AUTO_BOOT` is set (skip auto-boot).

   `-main` then skips the auto-boot of
   the agent + HTTP server (the bare-smoke-test switch)."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (some? (env "SEON_NO_AUTO_BOOT")))

(defn anthropic-api-key
  "`ANTHROPIC_API_KEY` — the Anthropic adapter's secret, or nil.

   The one non-`SEON_*` knob read through here; it gates adapter vs stub."
  {:malli/schema [:=> [:cat] [:maybe :string]]}
  []
  (env "ANTHROPIC_API_KEY"))

(defn result-vars-cap
  "Max live `result/<id>` vars kept per session.

   A COUNT of retained vars,
   not a render width, so it keeps the `SEON_EVAL_*` prefix distinct from the
   render-cap family (`SEON_EVAL_RESULT_VARS_CAP`, default 200)."
  {:malli/schema [:=> [:cat] :int]}
  []
  (env-int "SEON_EVAL_RESULT_VARS_CAP" 200))

;;; --- Render/output caps — the coherent `SEON_RENDER_*` family (#46). These
;;; are read-time, LLM-facing display truncations. Env OVERRIDES config (owner
;;; model): the manifest's `:seon.config/render` section declares each knob as
;;; `#long #or [#env SEON_RENDER_* default]` in `config/system.edn`, so an env
;;; var wins when set, else the manifest default applies. Each accessor reads
;;; the RESOLVED section via [[render-config]] (memoized per SEON_CONFIG, like
;;; [[namespaces-policy]]); the literal fallback in the accessor equals the
;;; manifest default, so a no-manifest boot is byte-identical to today. Callers
;;; (render/value.cljs, ctx.cljs, eval.cljs) are UNTOUCHED — they still call
;;; `database-edn-cap` etc.; only the accessor body moved from `env-int` to a
;;; manifest read.

;; The render caps are datoms on the config singleton now — [[render-config]]
;; reads the live [[config-view]] (db post-conn, manifest resolve pre-conn), so
;; each accessor's `(get (render-config) attr default)` reads the SAME flat map
;; ([[resolve-config-singleton]] stores the leaf keys top-level). No memo cache:
;; the db value is the (self-invalidating) cache; a live edit reaches agents on
;; the next boot reconcile OR a `db/transact!` to the singleton.
(defn- render-config
  "The live config-singleton map [[config-view]] returns — carries every
   `:seon.config.render/*` cap datom as a top-level key. Each render accessor
   reads it with its own literal fallback (= the default)."
  []
  (config-view))

(defn database-edn-cap
  "Per-value pr-str truncation cap for stored EDN display.

   Manifest
   `:seon.config.render/database-edn-cap`; env `SEON_RENDER_DATABASE_EDN_CAP`; 16384."
  {:malli/schema [:=> [:cat] :int]}
  []
  (get (render-config) :seon.config.render/database-edn-cap 16384))

(defn eval-render-cap
  "Char cap for one eval row's echoed SOURCE + captured STDOUT.

   Neither
   is dereferenceable via `result/<id>`, so a large one is context-wasting
   noise (manifest `:seon.config.render/eval-cap`; env `SEON_RENDER_EVAL_CAP`;
   1500)."
  {:malli/schema [:=> [:cat] :int]}
  []
  (get (render-config) :seon.config.render/eval-cap 1500))

(defn message-render-cap
  "Per-message rendered-content char cap for one transcript line.

   A
   single pasted blob must not blow the context (manifest
   `:seon.config.render/message-cap`; env `SEON_RENDER_MESSAGE_CAP`; 4000)."
  {:malli/schema [:=> [:cat] :int]}
  []
  (get (render-config) :seon.config.render/message-cap 4000))

(defn result-body-render-cap
  "Char cap for the CITABLE RESULT BODY of one eval row.

   THE one owner of
   the value (registry row C32 — it was duplicated as 4096 tokens in
   `seon.eval` vs 16384 chars in `seon.agent.ctx`): both call sites read
   this accessor, `seon.eval/clip-result-body` converting to its token
   budget at the boundary (chars/4). Char-denominated like its family
   siblings (manifest `:seon.config.render/result-body-cap`; env
   `SEON_RENDER_RESULT_BODY_CAP`; 16384)."
  {:malli/schema [:=> [:cat] :int]}
  []
  (get (render-config) :seon.config.render/result-body-cap 16384))

;;; --- Value-renderer bounds — the `SEON_RENDER_VALUE_*` sub-family
;;; (per-node depth/breadth limits of the structural eval-value skeleton).

(defn value-max-depth
  "Max nesting depth of the value skeleton.

   Manifest
   `:seon.config.render/value-max-depth`; env `SEON_RENDER_VALUE_MAX_DEPTH`; 3."
  {:malli/schema [:=> [:cat] :int]} []
  (get (render-config) :seon.config.render/value-max-depth 3))

(defn value-max-keys
  "Max map keys shown per node.

   Manifest `:seon.config.render/value-max-keys`;
   env `SEON_RENDER_VALUE_MAX_KEYS`; 8."
  {:malli/schema [:=> [:cat] :int]} []
  (get (render-config) :seon.config.render/value-max-keys 8))

(defn value-max-items
  "Max collection items shown per node.

   Manifest
   `:seon.config.render/value-max-items`; env `SEON_RENDER_VALUE_MAX_ITEMS`; 8."
  {:malli/schema [:=> [:cat] :int]} []
  (get (render-config) :seon.config.render/value-max-items 8))

(defn value-max-string
  "Max chars of a string leaf before it is clipped to a marker.

   Manifest
   `:seon.config.render/value-max-string`; env `SEON_RENDER_VALUE_MAX_STRING`;
   80)."
  {:malli/schema [:=> [:cat] :int]} []
  (get (render-config) :seon.config.render/value-max-string 80))

(defn value-shape-sample
  "How many homogeneous-map elements to probe for a shared shape.

   Manifest `:seon.config.render/value-shape-sample`; env
   `SEON_RENDER_VALUE_SHAPE_SAMPLE`; 8)."
  {:malli/schema [:=> [:cat] :int]} []
  (get (render-config) :seon.config.render/value-shape-sample 8))

(defn value-verbatim-cap
  "Char budget under which an eval value prints WHOLE (REPL-style).

   Otherwise skeletonized (manifest `:seon.config.render/value-verbatim-cap`; env
   `SEON_RENDER_VALUE_VERBATIM_CAP`; 1500)."
  {:malli/schema [:=> [:cat] :int]} []
  (get (render-config) :seon.config.render/value-verbatim-cap 1500))

(defn value-width
  "Inline-vs-break width budget for the skeleton emitter.

   Manifest
   `:seon.config.render/value-width`; env `SEON_RENDER_VALUE_WIDTH`; 72."
  {:malli/schema [:=> [:cat] :int]} []
  (get (render-config) :seon.config.render/value-width 72))

(defn render-fn-token-cap
  "TOKEN cap for one auto-run render fn's ai output.

   The current-ns auto-run pass (`seon.agent.ctx.render-fns`) clips each
   discovered render fn's `:seon.render/ai` string at this token budget so
   one chatty view can't blow the context (manifest
   `:seon.config.render/render-fn-token-cap`; 2000)."
  {:malli/schema [:=> [:cat] :int]} []
  (get (render-config) :seon.config.render/render-fn-token-cap 2000))

;;; --- Explicit-character render knobs (transcript-render redesign). Each
;;; default reproduces today's bytes, so an absent section renders identically.

(defn render-whitespace
  "Whitespace rendering mode for string content: `:raw` or `:visible`.

   `:visible` makes tab/space glyphs (`·`/`→`) explicit so a whitespace bug
   (tabs-vs-spaces in Python) is visible; `:raw` (default — byte-identical to
   today) leaves literal (manifest `:seon.config.render/whitespace`)."
  {:malli/schema [:=> [:cat] :keyword]} []
  (get (render-config) :seon.config.render/whitespace :raw))

(defn render-tabs
  "Tab rendering mode: `:literal` (default) or `:arrow` (`→`).

   Manifest `:seon.config.render/tabs`; default reproduces today's bytes."
  {:malli/schema [:=> [:cat] :keyword]} []
  (get (render-config) :seon.config.render/tabs :literal))

(defn render-trailing-ws
  "Trailing-whitespace marker mode: `:off` (default) or `:dot` (`·`).

   Manifest `:seon.config.render/trailing-ws`; default reproduces today."
  {:malli/schema [:=> [:cat] :keyword]} []
  (get (render-config) :seon.config.render/trailing-ws :off))

(defn render-content-layout
  "Content layout for edited text: `:structured` (default) or `:single-line`.

   Manifest `:seon.config.render/content-layout`; default reproduces today."
  {:malli/schema [:=> [:cat] :keyword]} []
  (get (render-config) :seon.config.render/content-layout :structured))

(defn render-line-numbers?
  "Whether string content renders with a 1-based line-number gutter.

   `false` (default — byte-identical to today); manifest
   `:seon.config.render/line-numbers`."
  {:malli/schema [:=> [:cat] :boolean]} []
  (boolean (get (render-config) :seon.config.render/line-numbers false)))

(defn on-core-error
  "The core-fault escalation dial: `:crash`, `:gate`, or `:log`.

   Read off the config singleton via [[config-view]] (the manifest resolve
   pre-conn — this dial can fire during store-connect, so the pre-conn sliver
   matters). Default `:gate` (the SHIPPED posture — pod never crashes, the
   CI-shaped wrappers fail runs that accumulated a new `:core`-fault datom).
   Read by `seon.error/record!` on every `:core` fault."
  {:malli/schema [:=> [:cat] :seon.config/on-core-error]}
  []
  (or (:seon.config/on-core-error (config-view)) :gate))

;;; --- FORM-AUTOFIX (repair) accessors — the `:seon.config/repair` knobs, now
;;; singleton datoms. Absent section ⇒ the owner-ruled defaults (level
;;; `:symbols`, no class kill-switches, 1 fix/form, 50ms budget). Consumers
;;; combine level + classes via `seon.repair/class-enabled?` (the computed rule).

(defn- repair-config
  "The live config-singleton map [[config-view]] returns — carries the
   `:seon.config.repair/*` datoms as top-level keys."
  []
  (config-view))

(defn repair-level
  "The repair level: `:off` / `:safe-syntax` / `:symbols` / `:aggressive`.

   Manifest `:seon.config.repair/level`; default `:symbols` (owner ruling
   2026-07-05 — AR agents get pre-flight symbol repair). An unrecognized
   value coerces to `:symbols` (the manifest validator already rejects it
   loudly at load; this is the belt for a stale cache)."
  {:malli/schema [:=> [:cat] :keyword]}
  []
  (let [raw (get (repair-config) :seon.config.repair/level :symbols)]
    (if (contains? #{:off :safe-syntax :symbols :aggressive} raw) raw :symbols)))

(defn repair-classes
  "The per-class repair kill-switch map `{class-kw boolean}`.

   Manifest `:seon.config.repair/classes`; default `{}` (the level alone
   decides). Combined with `seon.repair/class-levels` by
   `seon.repair/class-enabled?`."
  {:malli/schema [:=> [:cat] :map]}
  []
  (get (repair-config) :seon.config.repair/classes {}))

(defn repair-max-fixes
  "Max chained symbol fixes per form.

   Manifest `:seon.config.repair/max-fixes-per-form`; default 1 (multi-fix
   is the unimplemented `:aggressive` tier's territory)."
  {:malli/schema [:=> [:cat] :int]}
  []
  (get (repair-config) :seon.config.repair/max-fixes-per-form 1))

(defn repair-budget-ms
  "Wall-clock budget for one form's whole repair pipeline.

   Over budget = no fix, plain error — a slow fix is a worse product than
   a fast error. Manifest `:seon.config.repair/budget-ms`; default 50."
  {:malli/schema [:=> [:cat] :int]}
  []
  (get (repair-config) :seon.config.repair/budget-ms 50))

(defn web-policy
  "The resolved web-access policy for `seon.agent.web/fetch`.

   `{:seon.agent.web/policy <mode> :seon.agent.web/allowed-domains [host…]}`
   off the config singleton via [[config-view]] (the mode coerced fail-closed
   in [[resolve-config-singleton]]). Mode default `:public-only` (the SSRF-safe
   fallback — a downstream inheritor with NO config is never open by accident);
   `allowed-domains` `[]` (only meaningful under `:allowlist`). Host-owned:
   `seon.agent.web` reads it but nothing in the pod can widen it."
  {:malli/schema [:=> [:cat]
                  [:map
                   [:seon.agent.web/policy :keyword]
                   [:seon.agent.web/allowed-domains [:vector :string]]]]}
  []
  (let [v (config-view)]
    {:seon.agent.web/policy          (or (:seon.agent.web/policy v) :public-only)
     :seon.agent.web/allowed-domains (vec (:seon.agent.web/allowed-domains v))}))

(defn web-search-config
  "The resolved web-SEARCH backend config for `seon.agent.web/search`.

   `{:seon.agent.web/search-backend <mode> :seon.agent.web/search-model <id>}`
   off the config singleton via [[config-view]] (backend coerced fail-closed in
   [[resolve-config-singleton]]). Backend default `:gemini-grounding`; model
   default `\"gemini-3.1-flash-lite\"`. The API key is NEVER here — read live
   from env (`GEMINI_API_KEY` / `SERPER_API_KEY`) at call time."
  {:malli/schema [:=> [:cat]
                  [:map
                   [:seon.agent.web/search-backend :keyword]
                   [:seon.agent.web/search-model :string]]]}
  []
  (let [v (config-view)]
    {:seon.agent.web/search-backend (or (:seon.agent.web/search-backend v) :gemini-grounding)
     :seon.agent.web/search-model   (or (:seon.agent.web/search-model v) "gemini-3.1-flash-lite")}))

(defn render-strict?
  "The FAIL-LOUD render dial.

   When ON, a render/converter failure THROWS
   (naming the offending block + the full malli `explain`) instead of being
   swallowed by the graceful guard; when OFF, today's guard-and-continue
   (a live prod agent must not hard-crash on one bad block).

   Read from env `SEON_RENDER_STRICT` (`1`/`true`/`on`/`yes` → ON; anything
   else / unset → OFF). DEFAULT OFF: a bare pod boot (the live prod agent) is
   graceful. Turned ON explicitly in dev / test / benchmark contexts
   (`bin/test-cljs` exports it; a benchmark driver may set it per run) so a
   silent render failure SCREAMS the moment it happens instead of hiding in a
   one-line `⚠ … render failed` guard. This is the config seam the
   `seon.render` guards + the transcript converter route their swallow through
   ([[seon.render/render]] / [[seon.render/slot]] / [[seon.render/render-value]]).

   An explicit env dial (not a build `goog.DEBUG` guess) BECAUSE the live
   `:client` pod is itself a `:devtools`-enabled dev build — build flags cannot
   tell the prod pod from a test process, but an env var can."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (contains? #{"1" "true" "on" "yes"}
             (some-> (env "SEON_RENDER_STRICT") clojure.string/lower-case)))

;;; --- Agent + test bounds (not render caps — kept on their own prefixes).

(defn tick-ms
  "The `SEON_TICK_MS` ticker-cadence override, or nil.

   Parsed POSITIVE int; nil
   when unset / unparseable / non-positive (the caller then uses its default)."
  {:malli/schema [:=> [:cat] [:maybe :int]]}
  []
  (let [v (some-> (env "SEON_TICK_MS") js/parseInt)]
    (when (and (number? v) (not (js/isNaN v)) (pos? v)) v)))

(defn test-timeout-ms
  "Per-test / -fixture wall-clock bound in ms.

   `SEON_TEST_TIMEOUT_MS`, default 15000."
  {:malli/schema [:=> [:cat] :int]}
  []
  (env-int "SEON_TEST_TIMEOUT_MS" 15000))

(defn llm-attempt-timeout-ms
  "Wall-clock cap in ms on ONE LLM adapter attempt.

   `SEON_LLM_ATTEMPT_TIMEOUT_MS`, default 120000 (2 min). The inner bound
   [[seon.agent.turn/call-llm!]] races each attempt against — independent
   of the adapter's own `:seon.ai/timeout-ms` (which may be unset/huge), so
   a single attempt can never park the turn."
  {:malli/schema [:=> [:cat] :int]}
  []
  (env-int "SEON_LLM_ATTEMPT_TIMEOUT_MS" 120000))

(defn turn-timeout-ms
  "Per-step wall-clock bound in ms for the agent loop's awaits.

   `SEON_TURN_TIMEOUT_MS`, default 900000 (15 min) — comfortably above the
   worst-case bounded LLM retry ladder (≤5 capped attempts + backoff), so it
   only fires on a genuinely hung step. The run DEADLINE stays the outer
   bound; this is the INNER bound that frees a parked [[seon.agent.loop/run-loop!]]."
  {:malli/schema [:=> [:cat] :int]}
  []
  (env-int "SEON_TURN_TIMEOUT_MS" 900000))

;;; --- MULTI-AGENT dials (multiagent-context-spec) — read straight off the
;;; manifest with a literal default (the same shape as `on-core-error`; absent
;;; section ⇒ the shipped default). Pure fns take these as ARGS — the accessor
;;; is only the manifest read; never mutate config from a test.

(defn spawn-depth-cap
  "Max spawn DEPTH a caller may spawn at (Piece 2 backstop).

   Manifest `:seon.config/spawn-depth-cap`; default 1 (root at depth 0 spawns;
   a depth-1 subagent may NOT). `seon.agent/start!` refuses a caller AT/over
   this. Raise the dial (and add the spawn functions to the general agent-context)
   to deepen the tree."
  {:malli/schema [:=> [:cat] :int]}
  []
  (get (config-view) :seon.config/spawn-depth-cap 1))

(defn watchdog-stale-ms
  "Heartbeat-watchdog staleness threshold in ms (Piece 2c).

   A run whose beat (or `started-at`, if it never beat) has not progressed for
   longer is closed `:crashed`. Singleton `:seon.config.watchdog/stale-ms`;
   default 1200000 (20 min — comfortably ABOVE the 15-min per-turn inner bound
   `SEON_TURN_TIMEOUT_MS`, so a slow-but-alive LLM turn is never falsely
   killed)."
  {:malli/schema [:=> [:cat] :int]}
  []
  (get (config-view) :seon.config.watchdog/stale-ms 1200000))

(defn schedule-breaker-crash-count
  "Schedule-wake breaker trip count N (Piece 2d).

   At ≥N `:crashed` closes within the window, schedule wakes are refused.
   Singleton `:seon.config.breaker/crash-count`; default 3."
  {:malli/schema [:=> [:cat] :int]}
  []
  (get (config-view) :seon.config.breaker/crash-count 3))

(defn schedule-breaker-window-ms
  "Schedule-wake breaker sliding window in ms (Piece 2d).

   `:crashed` closes older than this don't count toward the trip; the window
   sliding past re-enables schedules (no stored reset). Singleton
   `:seon.config.breaker/window-ms`; default 1800000 (30 min)."
  {:malli/schema [:=> [:cat] :int]}
  []
  (get (config-view) :seon.config.breaker/window-ms 1800000))

(defn- upsert-by-name
  "Layer `additions` over `base` by `:seon.agent.ctx/name`, MERGING an
   addition's attrs OVER the matching base block IN PLACE — so a sparse
   override that sets ONE sub-key (e.g. root/acme's `:canvas` setting
   only `:seon.render.canvas/content`) KEEPS the default block's other
   attrs (`:seon.agent.ctx/priority`, `:seon.render/ai`, ...). This is the
   third-party-first contract: a manifest overriding a block need only name
   the sub-keys it changes, never re-specify the whole block. A name absent
   from `base` is a brand-new block, appended in `additions` order. Used by
   [[context-config-for]] to layer the root-context override over the base,
   by [[context-config-for]]."
  [base additions]
  (let [by-name  (into {} (map (juxt :seon.agent.ctx/name identity)) additions)
        seen     (atom #{})
        ;; existing blocks: merge any same-name addition over them, in place.
        merged   (mapv (fn [b]
                         (let [nm (:seon.agent.ctx/name b)]
                           (if-let [add (get by-name nm)]
                             (do (swap! seen conj nm)
                                 (merge b add))
                             b)))
                       base)
        ;; additions with no base counterpart: append in original order.
        appended (filterv #(not (@seen (:seon.agent.ctx/name %))) additions)]
    (into merged appended)))

(defn resolve-routes
  "Curate the seeded `routes` by the manifest's `:seon.config/routes`.

   The `:seon.route/*` maps; drop any route whose `:seon.route/name` is in a
   spec's `:removes`. No specs → `routes` unchanged."
  {:malli/schema [:=> [:catn [::routes ::routes]
                       [::manifest :seon.config/manifest]]
                  ::routes]}
  [routes manifest]
  (let [specs (:seon.config/routes manifest)]
    (if (empty? specs)
      routes
      (let [removes (into #{} (mapcat :seon.config/removes) specs)]
        (filterv #(not (removes (:seon.route/name %))) routes)))))

;;; ============================================================
;;; AGENT-CONTEXT RESOLVER (decisions 11/16) — the GENERIC loader. Selects the
;;; agent-context by IDENTITY (root gets the root-context override, NOT a
;;; `:kind`), merges the per-mint override, then RECURSIVELY fills every
;;; unspecified key from the schema `:default`s (agent-level AND per-block). It
;;; hardcodes NO block-specific knowledge — it just decodes+returns whatever the
;;; schema + manifest specify, so a `SEON_CONFIG` cluster configures its whole
;;; context from its own file with zero `src/seon` edits.
;;; ============================================================

(def ^:private ctx-default-transformer
  "The recursive default-fill: `default-value-transformer` with
   `add-optional-keys` (REQUIRED — our agent-context keys are `{:optional true}`,
   so without it the transformer skips absent keys instead of filling their
   `:default`). Fills agent-level keys AND recurses into any supplied partial
   block map to fill its per-block defaults."
  default-transformer)

(defn- context-config-for
  "Select the FULLY-DEFAULTED agent-context map for `id` from `manifest`
   (decision 11) — by IDENTITY, not a `:kind`. Decodes `:seon.config/agent-context`
   through the default transformer FIRST, then for
   `\"root\"` upserts the sparse `:seon.config/root-context` blocks over it by
   `:seon.agent.ctx/name`. Any other id gets the decoded agent-context unchanged.
   Both manifest keys default to `{}` when absent; the context vector then defaults
   to empty. Decoding before the root upsert keeps scalar defaults and block-value
   decoding on one path."
  [id manifest]
  (let [base (m/decode :seon.config/agent-context
                       (get manifest :seon.config/agent-context {})
                       ctx-default-transformer)]
    (if (= id "root")
      (let [override (get manifest :seon.config/root-context {})]
        (-> base
            ;; merge root-context's SCALAR keys (e.g. `:seon.eval/home-requires`)
            ;; over the base — everything except the `:seon.agent/ctx` block
            ;; vector, which is merged-by-name below ([[upsert-by-name]]): a
            ;; sparse per-block override keeps the default block's other attrs.
            (merge (dissoc override :seon.agent/ctx))
            (assoc :seon.agent/ctx
                   (upsert-by-name (:seon.agent/ctx base)
                                   (:seon.agent/ctx override)))))
      base)))

(defn resolve-agent-context
  "Resolve the FULLY-DEFAULTED nested agent-context map for `id`.

   §3.1 — two
   explicit key-level merge layers — `agent-context ← root-context` (in
   [[context-config-for]], by identity, already defaulted) ← per-mint `override`
   — then a final recursive `m/decode` fills any key the override left absent.
   Returns `{… agent scalars … :seon.agent/ctx [block …]}`; the caller transacts
   it as ONE nested component-ref tx. An explicit `:seon.agent/ctx` is the
   complete seed tree; absent config resolves to an empty tree."
  {:malli/schema [:=> [:catn [::agent-id ::agent-id]
                       [::override [:maybe :map]]]
                  :seon.config/agent-context]}
  [id override]
  (let [manifest (config-view)
        merged   (merge (context-config-for id manifest) override)]
    (m/decode :seon.config/agent-context merged ctx-default-transformer)))
