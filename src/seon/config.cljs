(ns seon.config
  "The pod's config-read layer — ONE consolidated manifest (`config/system.edn`,
   `SEON_CONFIG` override) that primes an agent's context + the global render
   bounds WITHOUT a code change. The manifest is a pure OPTIONAL OVERRIDE: absent
   → the system behaves byte-identically to a no-config boot (the env-dir skill
   scan + the default context tree unchanged). Present → it shapes what the code
   would otherwise hardcode:

     1. the agent CONTEXT — the two-level `:seon.config/agent-context`
        (agent-level scalars + a `:seon.agent/ctx` block tree) the GENERIC loader
        ([[resolve-agent-context]]) decodes + transacts; `:seon.config/root-context`
        is the sparse root override (its `:live-tile` block = the system canvas);
     2. routes — drop seeded `:seon.route/*` rows per cluster ([[resolve-routes]]);
     3. the global RENDER bounds — `:seon.config/render` (value/eval/message
        display caps), read by the [[store-edn-cap]] etc. accessors.

   READER — aero (`aero.core/read-config`), the SAME library the JVM track's
   `seon.config` (`config.clj`) uses, so the two tracks are coherent siblings on
   ONE `system.edn` mental model. aero's CLJS branch reads via `cljs.tools.reader`
   (already bundled by `seon.eval`) + Node `fs`. The render section uses aero's
   own `#long`/`#or`/`#env` tags (env OVERRIDES a manifest default) — no custom
   data-readers. `seon.config` is shadow-COMPILED (not self-host), so
   `:require-macros` resolves at build time. If aero ever fails to compile/run the
   swap is one private fn ([[read-config-file]]) — the manifest SHAPE is
   reader-independent.

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

;; The skills section carries ONLY the corpus dir override now — the always-on
;; skill BODIES are the agent-context's `:my.skills/load` presence-set, and the
;; corpus is the env-dir scan verbatim (no include/exclude curation). Roots
;; identify by id "root", never a stored `:seon.agent/kind` / config `:role`.
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

;;; RENDER BOUNDS — the GLOBAL, process-wide render/value display caps (#46).
;;; These are NOT per-agent datoms: they bound the value/eval/message renderers
;;; for the whole process, so they live in the manifest as a section (not on an
;;; agent entity). Env OVERRIDES config (owner model): the manifest declares
;;; each knob as `#long #or [#env SEON_RENDER_* default]` in `config/system.edn`
;;; — env set → the coerced env value, env unset → the manifest default. The
;;; keys here are `{:optional true}` WITHOUT a `:default` (decision: default in
;;; ONE place — the manifest `#or`); the accessors below apply the SAME literal
;;; as their own fallback when the whole section is absent (a no-manifest boot).
(schema/register! :seon.config/render
  [:map
   [:seon.config.render/store-edn-cap     {:optional true} [:int {:min 1}]]
   [:seon.config.render/eval-cap          {:optional true} [:int {:min 1}]]
   [:seon.config.render/message-cap       {:optional true} [:int {:min 1}]]
   [:seon.config.render/value-max-depth   {:optional true} [:int {:min 1}]]
   [:seon.config.render/value-max-keys    {:optional true} [:int {:min 1}]]
   [:seon.config.render/value-max-items   {:optional true} [:int {:min 1}]]
   [:seon.config.render/value-max-string  {:optional true} [:int {:min 1}]]
   [:seon.config.render/value-shape-sample {:optional true} [:int {:min 1}]]
   [:seon.config.render/value-verbatim-cap {:optional true} [:int {:min 1}]]
   [:seon.config.render/value-width       {:optional true} [:int {:min 1}]]])

;; THE manifest — the registry of known sections. A future section = ONE more
;; optional key here + a resolver fn. Every key optional ⇒ `{}` (config absent)
;; validates ⇒ identity everywhere.
;;; ============================================================
;;; AGENT-CONTEXT — the v3 two-level context config (decisions 13/16/4). ONE
;;; nested map: agent-level scalars/presence-sets + a `:seon.agent/ctx` vector
;;; of BLOCK maps (component-ref'd onto the agent at transact). The whole point
;;; of the schema is to CARRY the `:default`s that the recursive
;;; `default-value-transformer` fills — a SPARSE manifest (`{}`) decodes into
;;; the FULL byte-parity tree. LEAF rule holds: the block vector is a loose
;;; `[:vector :map]` (block/attr shapes register + validate downstream at
;;; install!/transact!), so `seon.config` never requires `seon.agent.ctx` /
;;; `my.skills` — the `:seon.render/ai` values are literal quoted symbols
;;; (VERIFIED to survive `m/decode` as `cljs.core/Symbol`), not var refs.
;;; ============================================================

(def ^:private default-ctx-blocks
  "The default `:seon.agent/ctx` block TREE the schema carries as its `:default`
   — a SPARSE manifest fills it. Reproduces the CP-0 parity oracle. Two block
   groups are NOT hardcoded here, they are computed by
   [[resolve-agent-context]]: the always-on skill BODIES (from `:my.skills/load`
   via [[expand-skill-blocks]], default `[:repl]`) and the soul/agents identity
   file-blocks (from [[identity-file-blocks]] — present only when the file exists
   and SEON_SOUL is not off; the default cluster runs SEON_SOUL=false, matching
   the soul-off oracle). `:seon.render/ai` values are literal quoted symbols
   (LEAF rule — no var ref). Sorted top→bottom = static→volatile (the
   provider-cache contract)."
  [{:seon.agent.ctx/name :shared-instructions :seon.agent.ctx/priority 10
    :seon.render/ai 'my.kb.shared/instructions-block}
   {:seon.agent.ctx/name :skills-catalog :seon.agent.ctx/priority 12
    :seon.render/ai 'my.skills/catalog-block}
   {:seon.agent.ctx/name :namespaces :seon.agent.ctx/priority 20
    :seon.render/ai 'seon.agent.ctx.namespaces/namespaces-block}
   {:seon.agent.ctx/name :live-tile :seon.agent.ctx/priority 35
    :seon.render/ai 'seon.agent.ctx.live-tile/live-tile-block}
   {:seon.agent.ctx/name :warnings :seon.agent.ctx/priority 40
    :seon.render/ai 'seon.agent.ctx.warnings/warnings-block}
   {:seon.agent.ctx/name :open-todos :seon.agent.ctx/priority 45
    :seon.render/ai 'seon.agent.todo.internal/open-todos-block}
   {:seon.agent.ctx/name :relevant-source :seon.agent.ctx/priority 48
    :seon.render/ai 'seon.agent.ctx.relevant/relevant-source-block}
   {:seon.agent.ctx/name :findings :seon.agent.ctx/priority 97
    :seon.render/ai 'seon.agent.ctx.findings/findings-block}
   {:seon.agent.ctx/name :transcript :seon.agent.ctx/priority 100
    :seon.render/ai 'seon.agent.ctx.transcript/transcript-block
    ;; the transcript carries BOTH render slots (ai + html) — the html slot
    ;; drives the datastar UI tile. Matches default-ctx-blocks +
    ;; the CP-0 oracle inventory (:seon.render html(1)).
    :seon.render/html 'seon.agent.ctx.transcript/transcript-block-html
    ;; CP-5 — the eval-result age-decay schedule (owner: evals "start larger
    ;; and shrink over time"). Nested maps → datahike reifies each into a
    ;; `::decay-level` entity (component ref). Near-full this turn + next
    ;; (0→16384), partial at offset 2 (→1500), stub at offset 5 (→200, keeps
    ;; the result/<id> handle). Seeded HERE (the loose block-vector default
    ;; does not run the per-block schema decode, so the ::result-decay schema
    ;; default is not auto-filled — the schedule rides the seed explicitly).
    :seon.agent.ctx.transcript/result-decay
    [{:seon.agent.ctx.transcript/from-turn-offset 0 :seon.agent.ctx.transcript/token-cap 16384}
     {:seon.agent.ctx.transcript/from-turn-offset 2 :seon.agent.ctx.transcript/token-cap 1500}
     {:seon.agent.ctx.transcript/from-turn-offset 5 :seon.agent.ctx.transcript/token-cap 200}]}])

;; The agent-context map — agent-level config keys (all `{:optional true}`,
;; carrying their `:default`) + the `:seon.agent/ctx` block vector (its
;; `:default` = the full tree). Every key optional ⇒ `{}` validates ⇒ the
;; recursive decode fills the whole thing.
;;
;; TWO CLASSES of agent-level key:
;;   (a) CONSUMED-INTO-BLOCKS at seed, NOT persisted as an agent datom —
;;       `:my.skills/load` (expanded into `:skill/<name>` blocks; block presence
;;       is its truth). Dropped before the scalar transact by `seed-default-ctx!`.
;;   (b) PERSISTED as an agent-entity datom, read reactively by its consumer —
;;       `:seon.client/wake?` (gates the wake trigger at init),
;;       `:seon.eval/home-requires` (the home-ns require list). These are
;;       declared HERE (referencing their owning ns's registered shape) so the
;;       recursive decode fills the default AND `seed-default-ctx!` transacts
;;       them onto the entity. Default = today's value ⇒ byte-parity.
;; (The per-agent LLM / capabilities / toolkit / transcript-scalar keys are the
;; owner's pending three-fates call — added here the same way once decided.)
;; The persisted agent-level dials (`:seon.client/wake?`, `:seon.eval/home-requires`)
;; carry NO schema `:default` here — their DEFAULT lives ONCE at the CONSUMER
;; (`seon.client/wake-armed?` → true; `seon.eval/home-requires-for` → the
;; `home-ns-require-specs` const). So a no-config agent never gets the datom
;; (the consumer's fallback = byte-parity), and the manifest sets the key ONLY to
;; OVERRIDE. Declared LEAF-shaped (NOT a keyword ref — `seon.config` is a leaf
;; that loads before `seon.eval`/`seon.client`, and the full shape is validated
;; downstream at `transact!`, the same rule the block vector uses).
(schema/register! :seon.config/agent-context
  [:map
   ;; (a) consumed-into-blocks at seed (leaf `:keyword`; my.skills owns identity)
   [:my.skills/load          {:optional true :default [:repl]} [:vector :keyword]]
   ;; (b) persisted agent datoms — override-only (no default; consumer owns it)
   [:seon.client/wake?       {:optional true} :boolean]
   [:seon.eval/home-requires {:optional true} [:vector :any]]
   [:seon.agent/ctx {:optional true :default default-ctx-blocks} [:vector :map]]])

;; The ROOT override — a SPARSE agent-context merged over `:seon.config/agent-context`
;; by [[context-config-for]] (block upsert-by-name). Its `:live-tile` block sets
;; root's canvas = `system-view`, REPLACING the hardcoded client.cljs root branch.
;; NOT decoded through the transformer directly (it's a partial override layer);
;; only the MERGED result is decoded. Same loose `[:vector :map]` leaf shape.
(schema/register! :seon.config/root-context
  [:map
   [:seon.agent/ctx {:optional true} [:vector :map]]])

(schema/register! :seon.config/manifest
  [:map
   [:seon.config/skills        {:optional true} :seon.config/skills-spec]
   [:seon.config/namespaces    {:optional true} :seon.config/namespaces-spec]
   [:seon.config/routes        {:optional true} [:vector :seon.config/route-spec]]
   [:seon.config/render        {:optional true} :seon.config/render]
   [:seon.config/agent-context {:optional true} :seon.config/agent-context]
   [:seon.config/root-context  {:optional true} :seon.config/root-context]])

;;; Verb arg/return shapes — leaf `[:vector :map]` (full shapes validated
;;; downstream); registered once + referenced so the resolver specs don't
;;; re-inline the shape.
(schema/register! ::agent-id :string)
(schema/register! ::routes [:vector :map])

(def ^:private default-config-path
  "The consolidated manifest, CWD-relative (the pod's cwd is the repo root) —
   `SEON_CONFIG` overrides the path (the SOUL.md / SEON_SKILLS_DIR precedent)."
  "config/system.edn")

(def ^:private skill-body-priority
  "An always-on skill body sits in the CACHED prefix between the L0 catalog
   (`:skills-catalog`, 12) and `:namespaces` (20), inside `cache-breakpoint`
   = 20, so an always-on body never busts the provider cache. (A RUNTIME
   `(my.skills/load …)` uses the volatile band instead.)"
  16)

(defn- env
  "A `process.env` value, nil when unset/blank — `seon.platform/env-val`, the
   ONE env reader."
  [var-name]
  (platform/env-val var-name))

(defn- read-config-file
  "Read + resolve `path` via aero. The ONE reader seam — the manifest shape is
   reader-independent, so a fallback to `cljs.reader/read-string` would swap only
   this body. A per-cluster variant is a SEPARATE file pointed at by
   `SEON_CONFIG` (no `#profile` — one file, one shape). The render section's
   `#long`/`#or`/`#env` tags resolve through aero's own readers here."
  [path]
  (aero/read-config path {}))

(defn load-manifest
  "Read the consolidated manifest (`config/system.edn`, `SEON_CONFIG` override).
   Returns the VALIDATED manifest map, or `{}` when the file is absent (the
   empty manifest = no overrides = byte-identical to a no-config boot). Throws a
   LOUD, file-named error when the file is present but invalid (a config typo
   fails fast, never a silent ignore)."
  {:malli/schema [:=> [:cat] :seon.config/manifest]}
  []
  (let [path (or (env "SEON_CONFIG") default-config-path)
        fs   (js/require "fs")]
    (if-not (try (.existsSync fs path) (catch :default _ false))
      {}
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
;;; the live accessor [[namespaces-policy]] memoizes per env so the boot
;;; indexer (93 ns rows) and every render share one read.
;;; ============================================================

(def ^:private default-namespaces-policy
  "The SHIPPED default namespaces render policy. Everything renders FULL real
   source; this is just WHICH nses are always present: the `my.*` toolkit
   exemplars plus the core verb nses the agent calls constantly
   (`seon.agent.todo`/`seon.agent.message`/`seon.agent.lifecycle`). The agent's
   CURRENT ns and the nses it `:require`s render full on top of this (resolved
   in `seon.agent.ctx.namespaces`). `config/system.edn` mirrors this list
   verbatim for visibility; a lean cluster overrides it with a short explicit
   list (the curation lever)."
  {:seon.config/always     '[my.kb my.data my.ui my.tile
                             seon.agent.todo seon.agent.message seon.agent.lifecycle]
   :seon.config/current-ns :full})

(defn- ns-sym->kw
  "An ns-name SYMBOL (config) → its ns-name KEYWORD (the DB `:seon.ns/name`
   shape the renderer matches): `my.kb` → `:my.kb`."
  [s]
  (keyword (str s)))

(defn resolve-namespaces
  "Resolve the `:seon.config/namespaces` section of `manifest` into the policy
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

;; `def` (NOT defonce): a hot-reload of `seon.config` ROTATES the cache, so a
;; dev edit to `config/system.edn` is picked up on the next reload (config is
;; otherwise a boot-time read). Within a process it memoizes per env key.
(def ^:private ns-policy-cache (atom {}))

(defn namespaces-policy
  "The resolved namespaces render policy for the live manifest, memoized per
   `SEON_CONFIG` (config is process-stable; the gym steers SEON_CONFIG per run,
   so the key tracks it — a different manifest re-resolves). The ONE policy
   `seon.agent.ctx.namespaces` (renderer) and `seon.client` (boot indexer's
   full-source decision) share — see [[resolve-namespaces]]."
  {:malli/schema [:=> [:cat] :seon.config/namespaces-policy]}
  []
  (let [k (env "SEON_CONFIG")]
    (or (get @ns-policy-cache k)
        (let [p (resolve-namespaces (load-manifest))]
          (swap! ns-policy-cache assoc k p)
          p))))

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
  "The raw string value of env `var-name`, or nil when unset/blank — the base
   typed read every named knob below sits on."
  {:malli/schema [:=> [:catn [::var-name :string]] [:maybe :string]]}
  [var-name]
  (env var-name))

(defn env-int
  "Env `var-name` parsed as a POSITIVE int, or `default` when unset / blank /
   non-numeric / non-positive — the shared cap-knob reader."
  {:malli/schema [:=> [:catn [::var-name :string] [::default :int]] :int]}
  [var-name default]
  (let [v (some-> (env var-name) js/parseInt)]
    (if (and (number? v) (not (js/isNaN v)) (pos? v)) v default)))

(defn skills-dir
  "The skills corpus directory: the manifest's `:seon.config/skills`
   `:seon.config/dirs` first entry when present, else `SEON_SKILLS_DIR`, else
   `.claude/skills` (the standard Claude-Code layout humans edit too). This is
   where `:seon.config/dirs` is finally consumed — the last hardcoded env read
   folded into the config seam."
  {:malli/schema [:=> [:cat] :string]}
  []
  (or (some-> (load-manifest) :seon.config/skills :seon.config/dirs first)
      (env "SEON_SKILLS_DIR")
      ".claude/skills"))

(defn extra-src
  "`SEON_EXTRA_SRC` — a downstream's compiled-in source root (its `/src` +
   `/test` get probed after the seon artifact roots), or nil when unset."
  {:malli/schema [:=> [:cat] [:maybe :string]]}
  []
  (env "SEON_EXTRA_SRC"))

(defn no-auto-boot?
  "True when `SEON_NO_AUTO_BOOT` is set — `-main` then skips the auto-boot of
   the agent + HTTP server (the bare-smoke-test switch)."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (some? (env "SEON_NO_AUTO_BOOT")))

(defn anthropic-api-key
  "`ANTHROPIC_API_KEY` (the Anthropic adapter's secret), or nil when unset —
   the one non-`SEON_*` knob read through here; it gates adapter vs stub."
  {:malli/schema [:=> [:cat] [:maybe :string]]}
  []
  (env "ANTHROPIC_API_KEY"))

(defn result-vars-cap
  "Max live `result/<id>` vars kept per session — a COUNT of retained vars,
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
;;; `store-edn-cap` etc.; only the accessor body moved from `env-int` to a
;;; manifest read.

;; `def` (NOT defonce): a hot-reload rotates the cache so a dev edit to the
;; manifest is picked up on next reload. Memoized per SEON_CONFIG within a
;; process (config is boot-stable; the gym steers SEON_CONFIG per run).
(def ^:private render-config-cache (atom {}))

(defn- render-config
  "The resolved `:seon.config/render` section of the live manifest (env
   overrides config via the section's `#or [#env … default]` tags), memoized
   per `SEON_CONFIG`. `{}` when the section is absent — each accessor then uses
   its own literal fallback (= the manifest default)."
  []
  (let [k (env "SEON_CONFIG")]
    (or (get @render-config-cache k)
        (let [m (get (load-manifest) :seon.config/render {})]
          (swap! render-config-cache assoc k m)
          m))))

(defn reset-render-cache!
  "Clear the memoized [[render-config]] read — for tests that `with-redefs`
   `load-manifest` and need the next accessor read to re-resolve."
  {:malli/schema [:=> [:cat] :nil]}
  []
  (reset! render-config-cache {})
  nil)

(defn store-edn-cap
  "Per-value pr-str truncation cap for stored EDN display (manifest
   `:seon.config.render/store-edn-cap`; env `SEON_RENDER_STORE_EDN_CAP`; 16384)."
  {:malli/schema [:=> [:cat] :int]}
  []
  (get (render-config) :seon.config.render/store-edn-cap 16384))

(defn eval-render-cap
  "Char cap for the echoed SOURCE + captured STDOUT of one eval row — neither
   is dereferenceable via `result/<id>`, so a large one is context-wasting
   noise (manifest `:seon.config.render/eval-cap`; env `SEON_RENDER_EVAL_CAP`;
   1500)."
  {:malli/schema [:=> [:cat] :int]}
  []
  (get (render-config) :seon.config.render/eval-cap 1500))

(defn message-render-cap
  "Per-message rendered-content char cap for one inbound transcript line — a
   single pasted blob must not blow the context (manifest
   `:seon.config.render/message-cap`; env `SEON_RENDER_MESSAGE_CAP`; 4000)."
  {:malli/schema [:=> [:cat] :int]}
  []
  (get (render-config) :seon.config.render/message-cap 4000))

;;; --- Value-renderer bounds — the `SEON_RENDER_VALUE_*` sub-family
;;; (per-node depth/breadth limits of the structural eval-value skeleton).

(defn value-max-depth
  "Max nesting depth of the value skeleton (manifest
   `:seon.config.render/value-max-depth`; env `SEON_RENDER_VALUE_MAX_DEPTH`; 3)."
  {:malli/schema [:=> [:cat] :int]} []
  (get (render-config) :seon.config.render/value-max-depth 3))

(defn value-max-keys
  "Max map keys shown per node (manifest `:seon.config.render/value-max-keys`;
   env `SEON_RENDER_VALUE_MAX_KEYS`; 8)."
  {:malli/schema [:=> [:cat] :int]} []
  (get (render-config) :seon.config.render/value-max-keys 8))

(defn value-max-items
  "Max collection items shown per node (manifest
   `:seon.config.render/value-max-items`; env `SEON_RENDER_VALUE_MAX_ITEMS`; 8)."
  {:malli/schema [:=> [:cat] :int]} []
  (get (render-config) :seon.config.render/value-max-items 8))

(defn value-max-string
  "Max chars of a string leaf before it is clipped to a length marker (manifest
   `:seon.config.render/value-max-string`; env `SEON_RENDER_VALUE_MAX_STRING`;
   80)."
  {:malli/schema [:=> [:cat] :int]} []
  (get (render-config) :seon.config.render/value-max-string 80))

(defn value-shape-sample
  "How many homogeneous-map elements to probe for a shared key-set shape
   (manifest `:seon.config.render/value-shape-sample`; env
   `SEON_RENDER_VALUE_SHAPE_SAMPLE`; 8)."
  {:malli/schema [:=> [:cat] :int]} []
  (get (render-config) :seon.config.render/value-shape-sample 8))

(defn value-verbatim-cap
  "Char budget under which an eval value prints WHOLE (REPL-style) instead of
   being skeletonized (manifest `:seon.config.render/value-verbatim-cap`; env
   `SEON_RENDER_VALUE_VERBATIM_CAP`; 1500)."
  {:malli/schema [:=> [:cat] :int]} []
  (get (render-config) :seon.config.render/value-verbatim-cap 1500))

(defn value-width
  "Inline-vs-break width budget for the skeleton emitter (manifest
   `:seon.config.render/value-width`; env `SEON_RENDER_VALUE_WIDTH`; 72)."
  {:malli/schema [:=> [:cat] :int]} []
  (get (render-config) :seon.config.render/value-width 72))

;;; --- Agent + test bounds (not render caps — kept on their own prefixes).

(defn tick-ms
  "The `SEON_TICK_MS` ticker-cadence override (parsed POSITIVE int), or nil
   when unset / unparseable / non-positive (the caller then uses its default)."
  {:malli/schema [:=> [:cat] [:maybe :int]]}
  []
  (let [v (some-> (env "SEON_TICK_MS") js/parseInt)]
    (when (and (number? v) (not (js/isNaN v)) (pos? v)) v)))

(defn test-timeout-ms
  "Per-test / -fixture wall-clock bound in ms (`SEON_TEST_TIMEOUT_MS`,
   default 15000)."
  {:malli/schema [:=> [:cat] :int]}
  []
  (env-int "SEON_TEST_TIMEOUT_MS" 15000))

(defn debug-capture
  "Raw `SEON_DEBUG_CAPTURE` string (or nil) — `seon.debug` applies its
   off-values + process-override semantics on top."
  {:malli/schema [:=> [:cat] [:maybe :string]]}
  []
  (env "SEON_DEBUG_CAPTURE"))

(defn debug-capture-dir
  "Debug-capture output base dir: `SEON_DEBUG_CAPTURE_DIR` when set, else the
   default `logs/turns` (a DATA path → CWD-relative)."
  {:malli/schema [:=> [:cat] :string]}
  []
  (or (env "SEON_DEBUG_CAPTURE_DIR") "logs/turns"))

(defn- upsert-by-name
  "Merge `additions` over `base` by `:seon.agent.ctx/name` (a re-named block
   replaces in place) — install!'s upsert semantics. Used by
   [[context-config-for]] to layer the root-context override over the base."
  [base additions]
  (let [names (into #{} (map :seon.agent.ctx/name) additions)]
    (into (filterv #(not (names (:seon.agent.ctx/name %))) base)
          additions)))

(defn- skill-body-block
  "The always-on body block a `:my.skills/load` skill-name expands to — the
   SAME `:skill/<name>` handle a runtime `(my.skills/load …)` uses, reusing the
   shipped `my.skills/skill-block` render fn (a literal quoted symbol; no var
   ref — LEAF rule), seeded at the cached-prefix priority so the body is
   always-on AND cacheable."
  [skill-name]
  {:seon.agent.ctx/name     (keyword "skill" (name skill-name))
   :seon.agent.ctx/priority skill-body-priority
   :seon.render/ai          'my.skills/skill-block})

(defn- soul-file-path
  "The primary identity file: `nil` when `SEON_SOUL` is explicitly disabled
   (`false`/`0`/`off`/`no`), else `SEON_SOUL_FILE` override, else `SOUL.md`.
   Mirrors the retired `seon.agent.ctx/soul-file-path` const — now the config
   path owns identity-file seeding (LEAF rule: config computes the path + does
   the fs existence check, emitting a pure-data block with a literal render
   symbol; no `seon.agent.ctx` var ref)."
  []
  (let [flag (some-> (env "SEON_SOUL") clojure.string/lower-case clojure.string/trim)]
    (when-not (contains? #{"false" "0" "off" "no"} flag)
      (or (env "SEON_SOUL_FILE") "SOUL.md"))))

(defn- file-exists? [path]
  (and (string? path)
       (try (.existsSync (js/require "fs") path) (catch :default _ false))))

(defn- identity-file-blocks
  "The soul/agents file-blocks PREPENDED onto the seed when their file is
   PRESENT (reactive, NO fallback — absent file → no block, as the retired
   `seon.agent.ctx/default-seed-blocks` fn did). SOUL.md at priority 5 (gated by SEON_SOUL),
   AGENTS.md at priority 8. Each is a pure-data block carrying the shipped
   `seon.agent.ctx/file-block-ai|html` render symbols (the slot fns re-read the
   file fresh each render). Only files that EXIST yield a block, so a soul-OFF /
   file-absent cluster gets none = byte-parity."
  []
  (->> [(when-let [p (soul-file-path)]
          {:seon.agent.ctx/name :soul :seon.agent.ctx/priority 5
           :seon.agent.ctx/file-path p})
        {:seon.agent.ctx/name :agents :seon.agent.ctx/priority 8
         :seon.agent.ctx/file-path "AGENTS.md"}]
       (filterv (fn [b] (and b (file-exists? (:seon.agent.ctx/file-path b)))))
       (mapv (fn [b] (assoc b :seon.render/ai   'seon.agent.ctx/file-block-ai
                              :seon.render/html 'seon.agent.ctx/file-block-html)))))

(defn- expand-skill-blocks
  "Expand the agent-context's `:my.skills/load` presence-set into `:skill/<name>`
   body blocks upserted onto `:seon.agent/ctx`, so the always-on skill BODIES a
   cluster names in `:my.skills/load` actually drive the seeded blocks (not a
   hardcoded list). Default `[:repl]` → the one `:skill/repl` block = byte-parity
   with the pre-CP-4 hardcoded seed. An explicit `[]` seeds no bodies."
  [{:my.skills/keys [load] :as ctx}]
  (assoc ctx :seon.agent/ctx
         (upsert-by-name (:seon.agent/ctx ctx)
                         (mapv skill-body-block (distinct load)))))

(defn resolve-routes
  "Curate the seeded `routes` (`:seon.route/*` maps) by the manifest's
   `:seon.config/routes` specs — drop any route whose `:seon.route/name` is in a
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
  (mt/default-value-transformer {:malli.transform/add-optional-keys true}))

(defn- context-config-for
  "Select the FULLY-DEFAULTED agent-context map for `id` from `manifest`
   (decision 11) — by IDENTITY, not a `:kind`. Decodes `:seon.config/agent-context`
   through the default transformer FIRST (so the block tree is present), then for
   `\"root\"` upserts the sparse `:seon.config/root-context` blocks over it by
   `:seon.agent.ctx/name` (root's `:live-tile` upserts to set `system-view`). Any
   other id gets the defaulted agent-context unchanged. Both manifest keys default
   `{}` when absent ⇒ the schema fills the full byte-parity tree. Decoding the base
   BEFORE the root upsert is load-bearing: a sparse `{}` base only carries its
   blocks after decode, so upserting first would drop the default tree."
  [id manifest]
  (let [base (m/decode :seon.config/agent-context
                       (get manifest :seon.config/agent-context {})
                       ctx-default-transformer)]
    (if (= id "root")
      (let [override (get manifest :seon.config/root-context {})]
        (assoc base :seon.agent/ctx
               (upsert-by-name (:seon.agent/ctx base)
                               (:seon.agent/ctx override))))
      base)))

(defn resolve-agent-context
  "Resolve the FULLY-DEFAULTED nested agent-context map for `id` (§3.1). Two
   explicit key-level merge layers — `agent-context ← root-context` (in
   [[context-config-for]], by identity, already defaulted) ← per-mint `override`
   — then a final recursive `m/decode` fills any key the override left absent.
   Returns `{… agent scalars … :seon.agent/ctx [block …]}`; the caller transacts
   it as ONE nested component-ref tx. Two non-generic block steps run last:
   [[expand-skill-blocks]] (the `:my.skills/load` presence-set → `:skill/<name>`
   bodies, default `[:repl]`) and the identity file-blocks ([[identity-file-blocks]]
   — SOUL.md/AGENTS.md when present, gated by SEON_SOUL). Both upsert by name, so
   a manifest that names those blocks wins. A sparse/absent manifest + nil
   override ⇒ the byte-parity default tree."
  {:malli/schema [:=> [:catn [::agent-id ::agent-id]
                       [::override [:maybe :map]]]
                  :seon.config/agent-context]}
  [id override]
  (let [merged (merge (context-config-for id (load-manifest)) override)]
    (-> (m/decode :seon.config/agent-context merged ctx-default-transformer)
        (expand-skill-blocks)
        (update :seon.agent/ctx #(upsert-by-name (vec (identity-file-blocks)) %)))))
