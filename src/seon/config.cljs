(ns seon.config
  "The pod's config-read layer — ONE consolidated manifest (`config/system.edn`,
   `SEON_CONFIG` override) that primes an agent's context + skill loadout WITHOUT
   a code change. The manifest is a pure OPTIONAL OVERRIDE: absent → the system
   behaves byte-identically to a no-config boot (the env-dir skill scan +
   `default-seed-blocks` seed unchanged). Present → it shapes three things the
   code would otherwise hardcode:

     1. the curated POD skill corpus — `include`/`exclude` over the scanned
        `.claude/skills` dir, so Claude-Code-only skills (browser-automation,
        clojure-testing) drop from the seon-agent catalog while staying on disk
        for Claude Code (ONE physical corpus, two consumers, curated by name);
     2. per-role context loadouts — `:default`/`:root`/`:worker` get extra
        blocks + a `default-load` set whose skill BODIES are always-on (seeded
        as `:skill/<name>` blocks at the cached-prefix priority 16), merged over
        `default-seed-blocks` by upsert-on-name;
     3. routes — drop seeded `:seon.route/*` rows per cluster.

   READER — aero (`aero.core/read-config`), the SAME library the JVM track's
   `seon.config` (`config.clj`) uses, so the two tracks are coherent siblings on
   ONE `system.edn` mental model. aero's CLJS branch reads via `cljs.tools.reader`
   (already bundled by `seon.eval`) + Node `fs`; `#env` interpolates
   `process.env`, `#profile` selects the per-cluster variant (`SEON_PROFILE`).
   `seon.config` is shadow-COMPILED (not self-host), so `:require-macros` resolves
   at build time. If aero ever fails to compile/run the swap is one private fn
   ([[read-config-file]]) — the manifest SHAPE is reader-independent.

   EXTENSIBILITY (the 'add more things to it' contract) — a new config concern is
   FOUR mechanical steps, no reshape: (1) `schema/register!` a
   `:seon.config/<section>` shape, (2) add its key to `:seon.config/manifest`,
   (3) write one `resolve-<section>` fn here, (4) call it at the existing seed
   point. The manifest map IS the open registry; an UNKNOWN key fails LOUD at
   validation (a config typo is a crash, never a silent ignore).

   LEAF — `seon.config` produces block/route/skill MAPS (data carrying literal
   quoted render symbols like `'my.skills/skill-block`), so it requires NEITHER
   `seon.agent.ctx` NOR `my.skills` (no var refs) — the seed-point call edges
   (`ctx → config`, `client → config`) stay one-way, no cycle. Its registered
   schemas therefore use LEAF shapes (`:keyword`, `[:vector :map]`): the full
   `:seon.agent.ctx/block` / `:my.skills/name` validation still happens
   downstream where those shapes are registered (`install!` validates each block,
   `transact!` validates each skill row)."
  (:require
    [aero.core :as aero]
    [malli.core :as m]
    [seon.platform :as platform]
    [seon.schema :as schema]))

;;; SCHEMA — the `:seon.config/*` shapes. Registered before any reference to
;;; them. Roles are an open ENUM SELECTOR, never a stored `:seon.agent/kind`
;;; (root is identified by id "root", not a kind stamp). Skill names + block
;;; shapes are validated as leaf `:keyword` / `:map` here and fully validated
;;; downstream (the LEAF rule above), so this ns stays cycle-free.

(schema/register! :seon.config/strategy   [:enum :override :replace])
(schema/register! :seon.config/role       [:enum :default :root :worker])
;; A skill handle — a plain keyword here (the `:seon.db/identity` property on
;; `:my.skills/name` is a storage concern irrelevant to a config selector list).
(schema/register! :seon.config/skill-name :keyword)

(schema/register! :seon.config/skills-spec
  [:map
   ;; corpus dir(s); reserved for multi-dir curation — the boot scan reads
   ;; SEON_SKILLS_DIR today, so this is forward-compat, not yet consumed.
   [:seon.config/dirs    {:optional true} [:vector :string]]
   ;; which skill BODIES are always-on (seeded as priority-16 :skill/<name>
   ;; blocks). EXPLICIT LISTING (#42), retiring the opaque named-profile sets:
   ;;   :all        → every corpus skill body always-on ("load everything")
   ;;   [:repl …]   → only these (the VISIBLE lean list — this is how you go lean)
   ;;   []          → none always-on
   ;; ABSENT → the legacy per-role :seon.config/default-load (migration bridge).
   [:seon.config/load    {:optional true} [:or [:enum :all]
                                           [:vector :seon.config/skill-name]]]
   ;; allowlist (absent = all scanned skills) — CATALOG curation, distinct from
   ;; :load (which selects the always-on subset of the catalog)
   [:seon.config/include {:optional true} [:vector :seon.config/skill-name]]
   ;; denylist (the first concrete payload: browser-automation, clojure-testing)
   [:seon.config/exclude {:optional true} [:vector :seon.config/skill-name]]])

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

(schema/register! :seon.config/loadout
  [:map
   [:seon.config/role         :seon.config/role]
   ;; skills whose BODY is always-on (expanded to priority-16 :skill/<name> blocks)
   [:seon.config/default-load {:optional true} [:vector :seon.config/skill-name]]
   ;; extra ctx blocks (ordinary :seon.agent.ctx/block maps; full shape validated
   ;; downstream at install!)
   [:seon.config/blocks       {:optional true} [:vector :map]]
   ;; block names to drop from the default seed
   [:seon.config/removes      {:optional true} [:vector :keyword]]
   [:seon.config/strategy     {:optional true} :seon.config/strategy]])

(schema/register! :seon.config/route-spec
  [:map
   [:seon.config/strategy {:optional true} :seon.config/strategy]
   [:seon.config/removes  {:optional true} [:vector :keyword]]])

;; THE manifest — the registry of known sections. A future section = ONE more
;; optional key here + a resolver fn. Every key optional ⇒ `{}` (config absent)
;; validates ⇒ identity everywhere.
(schema/register! :seon.config/manifest
  [:map
   [:seon.config/skills     {:optional true} :seon.config/skills-spec]
   [:seon.config/namespaces {:optional true} :seon.config/namespaces-spec]
   [:seon.config/loadouts   {:optional true} [:vector :seon.config/loadout]]
   [:seon.config/routes     {:optional true} [:vector :seon.config/route-spec]]])

;;; Verb arg/return shapes. The three corpora are leaf `[:vector :map]` here
;;; (full shapes validated downstream); registered once + referenced so the
;;; resolver specs don't re-inline the shape.
(schema/register! ::agent-id :string)
(schema/register! ::rows   [:vector :map])
(schema/register! ::blocks [:vector :map])
(schema/register! ::routes [:vector :map])

(def ^:private default-config-path
  "The consolidated manifest, CWD-relative (the pod's cwd is the repo root) —
   `SEON_CONFIG` overrides the path (the SOUL.md / SEON_SKILLS_DIR precedent)."
  "config/system.edn")

(def ^:private default-load-priority
  "A default-loaded skill body sits in the CACHED prefix between the L0 catalog
   (`:skills-catalog`, 12) and `:namespaces` (20), inside `stable-priority-max`
   = 20, so an always-on body never busts the provider cache. (A RUNTIME
   `(my.skills/load …)` uses priority 30 — the volatile band — instead.)"
  16)

(defn- env
  "A `process.env` value, nil when unset/blank — `seon.platform/env-val`, the
   ONE env reader."
  [var-name]
  (platform/env-val var-name))

(defn- read-config-file
  "Read + resolve `path` via aero (the §g.0 decision). The ONE reader seam —
   the manifest shape is reader-independent, so a fallback to
   `cljs.reader/read-string` would swap only this body. `#profile` selects the
   per-cluster variant from `SEON_PROFILE` (default `:default`)."
  [path]
  (aero/read-config path {:profile (keyword (or (env "SEON_PROFILE") "default"))}))

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
   `[SEON_CONFIG SEON_PROFILE]` (config is process-stable; the gym steers those
   env vars per run, so the key tracks them — a different manifest re-resolves).
   The ONE policy `seon.agent.ctx.namespaces` (renderer) and `seon.client`
   (boot indexer's full-source decision) share — see [[resolve-namespaces]]."
  {:malli/schema [:=> [:cat] :seon.config/namespaces-policy]}
  []
  (let [k [(env "SEON_CONFIG") (env "SEON_PROFILE")]]
    (or (get @ns-policy-cache k)
        (let [p (resolve-namespaces (load-manifest))]
          (swap! ns-policy-cache assoc k p)
          p))))

;;; ============================================================
;;; ENV KNOBS — the ONE typed env surface. `platform/env-val` is the single
;;; low-level reader (a raw `process.env` lookup); `seon.config` is the single
;;; TYPED layer on top — `env-string` / `env-int` plus the named accessors
;;; below. Every `SEON_*` TUNING knob (render/output caps, agent bounds, fs
;;; grants, brand, instrument, tile bounding, identity-file selection) flows
;;; through these readers; nothing else calls `js/process.env` directly for a
;;; knob. A consumer that needs a one-off flag calls `env-string`/`env-int`
;;; here with its var name (e.g. `agent/turn` reads SEON_AI_MAX_RETRIES via
;;; `env-int`; `agent/fs` reads SEON_FS_* via `env-string`).
;;;
;;; THREE surfaces legitimately read env OUTSIDE this layer, none a tuning knob:
;;;   1. the LLM-provider config seam `seon.ai` (SEON_AI_* → the DB-owned
;;;      `:seon.ai/config` row — its own consolidated env→DB surface);
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

;;; --- Render/output caps — the coherent `SEON_RENDER_*_CAP` family. Each is
;;; a read-time, LLM-facing display truncation; all live here so the family is
;;; discoverable + tunable in ONE place.

(defn store-edn-cap
  "Per-value pr-str truncation cap for stored EDN display
   (`SEON_RENDER_STORE_EDN_CAP`, default 16384)."
  {:malli/schema [:=> [:cat] :int]}
  []
  (env-int "SEON_RENDER_STORE_EDN_CAP" 16384))

(defn result-body-render-cap
  "Per-value render-body truncation cap — the citable `;;=> <value>` line
   (`SEON_RENDER_RESULT_CAP`, default 16384)."
  {:malli/schema [:=> [:cat] :int]}
  []
  (env-int "SEON_RENDER_RESULT_CAP" 16384))

(defn eval-render-cap
  "Char cap for the echoed SOURCE + captured STDOUT of one eval row — neither
   is dereferenceable via `result/<id>`, so a large one is context-wasting
   noise (`SEON_RENDER_EVAL_CAP`, default 1500)."
  {:malli/schema [:=> [:cat] :int]}
  []
  (env-int "SEON_RENDER_EVAL_CAP" 1500))

(defn message-render-cap
  "Per-message rendered-content char cap for one inbound transcript line — a
   single pasted blob must not blow the context (`SEON_RENDER_MESSAGE_CAP`,
   default 4000 ≈ 1k tokens)."
  {:malli/schema [:=> [:cat] :int]}
  []
  (env-int "SEON_RENDER_MESSAGE_CAP" 4000))

(defn transcript-token-cap
  "Total token cap for the transcript section eviction knob (RETAINED but
   currently OFF — `:seon.render/clip :none`). Measured in TOKENS, not chars
   (`SEON_RENDER_TRANSCRIPT_TOKEN_CAP`, default 6000)."
  {:malli/schema [:=> [:cat] :int]}
  []
  (env-int "SEON_RENDER_TRANSCRIPT_TOKEN_CAP" 6000))

;;; --- Value-renderer bounds — the `SEON_RENDER_VALUE_*` sub-family
;;; (per-node depth/breadth limits of the structural eval-value skeleton).

(defn value-max-depth
  "Max nesting depth of the value skeleton (`SEON_RENDER_VALUE_MAX_DEPTH`, 3)."
  {:malli/schema [:=> [:cat] :int]} [] (env-int "SEON_RENDER_VALUE_MAX_DEPTH" 3))

(defn value-max-keys
  "Max map keys shown per node (`SEON_RENDER_VALUE_MAX_KEYS`, 8)."
  {:malli/schema [:=> [:cat] :int]} [] (env-int "SEON_RENDER_VALUE_MAX_KEYS" 8))

(defn value-max-items
  "Max collection items shown per node (`SEON_RENDER_VALUE_MAX_ITEMS`, 8)."
  {:malli/schema [:=> [:cat] :int]} [] (env-int "SEON_RENDER_VALUE_MAX_ITEMS" 8))

(defn value-max-string
  "Max chars of a string leaf before it is clipped to a length marker
   (`SEON_RENDER_VALUE_MAX_STRING`, 80)."
  {:malli/schema [:=> [:cat] :int]} [] (env-int "SEON_RENDER_VALUE_MAX_STRING" 80))

(defn value-shape-sample
  "How many homogeneous-map elements to probe for a shared key-set shape
   (`SEON_RENDER_VALUE_SHAPE_SAMPLE`, 8)."
  {:malli/schema [:=> [:cat] :int]} [] (env-int "SEON_RENDER_VALUE_SHAPE_SAMPLE" 8))

(defn value-verbatim-cap
  "Char budget under which an eval value prints WHOLE (REPL-style) instead of
   being skeletonized (`SEON_RENDER_VALUE_VERBATIM_CAP`, 1500)."
  {:malli/schema [:=> [:cat] :int]} [] (env-int "SEON_RENDER_VALUE_VERBATIM_CAP" 1500))

(defn value-width
  "Inline-vs-break width budget for the skeleton emitter
   (`SEON_RENDER_VALUE_WIDTH`, 72)."
  {:malli/schema [:=> [:cat] :int]} [] (env-int "SEON_RENDER_VALUE_WIDTH" 72))

;;; --- Agent + test bounds (not render caps — kept on their own prefixes).

(defn default-turn-limit
  "The `SEON_DEFAULT_TURN_LIMIT` work-bound override (parsed int), or nil when
   unset / unparseable (the caller then applies its own default)."
  {:malli/schema [:=> [:cat] [:maybe :int]]}
  []
  (let [v (some-> (env "SEON_DEFAULT_TURN_LIMIT") js/parseInt)]
    (when (and (number? v) (not (js/isNaN v))) v)))

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

(defn agent-role
  "The loadout SELECTOR for `agent-id` — `:root` for the root agent (id
   \"root\"), `:worker` otherwise. A pure config-composition key, NOT a stored
   attr (there is no `:seon.agent/role`/`:kind` datom)."
  {:malli/schema [:=> [:catn [::agent-id ::agent-id]] :seon.config/role]}
  [agent-id]
  (if (= agent-id "root") :root :worker))

(defn resolve-skill-rows
  "Curate the scanned skill `rows` (`:my.skills/*` maps) by the manifest's
   `:seon.config/skills` spec: keep `include` (when given), drop `exclude`. No
   spec → `rows` unchanged (the identity = today's full-corpus scan)."
  {:malli/schema [:=> [:catn [::rows ::rows]
                       [::manifest :seon.config/manifest]]
                  ::rows]}
  [rows manifest]
  (let [spec (:seon.config/skills manifest)]
    (if (nil? spec)
      rows
      (let [incl (some-> (:seon.config/include spec) set)
            excl (some-> (:seon.config/exclude spec) set)]
        (->> rows
             (filterv (fn [r]
                        (let [nm (:my.skills/name r)]
                          (and (or (nil? incl) (incl nm))
                               (not (and excl (excl nm))))))))))))

(defn- skill-block
  "The always-on block a `default-load` skill-name expands to — the SAME
   `:skill/<name>` handle a runtime load uses, reusing the shipped
   `my.skills/skill-block` render fn (a literal quoted symbol; no var ref), but
   seeded at the cached-prefix priority so the body is always-on AND cacheable."
  [skill-name]
  {:seon.agent.ctx/name     (keyword "skill" (name skill-name))
   :seon.agent.ctx/priority default-load-priority
   :seon.render/ai          'my.skills/skill-block})

(defn- scan-skill-names
  "The corpus skill names as keywords — the subdirectories of `(skills-dir)`
   that carry a `SKILL.md`. Expands `:seon.config/skills {:seon.config/load
   :all}` WITHOUT a DB dependency (the leaf rule: config never requires
   `my.skills`). Mirrors `my.skills/seed-skills-tx-data`'s scan."
  []
  (let [fs  (js/require "fs")
        dir (skills-dir)]
    (if-not (try (.existsSync fs dir) (catch :default _ false))
      []
      (->> (.readdirSync fs dir #js {:withFileTypes true})
           (filter (fn [d] (.isDirectory d)))
           (map (fn [d] (.-name d)))
           (filter (fn [n] (try (.existsSync fs (str dir "/" n "/SKILL.md"))
                                (catch :default _ false))))
           (mapv keyword)))))

(defn- upsert-by-name
  "Merge `additions` over `base` by `:seon.agent.ctx/name` (a re-named block
   replaces in place) — install!'s upsert semantics."
  [base additions]
  (let [names (into #{} (map :seon.agent.ctx/name) additions)]
    (into (filterv #(not (names (:seon.agent.ctx/name %))) base)
          additions)))

(defn resolve-loadout
  "Shape `base-blocks` (`default-seed-blocks`) for an agent of `role` against
   the manifest. The ALWAYS-ON skill bodies come from `:seon.config/skills`
   `:seon.config/load` (#42 explicit listing): `:all` → every corpus skill
   (`scan-skill-names`); a vector → only those; `[]` → none. When `:load` is
   ABSENT it falls back to the legacy per-role `:seon.config/default-load`
   (a migration bridge — retire once acme/test/gym move to `:load`). Each
   resolved skill-name expands to a priority-16 `:skill/<name>` always-on block.
   The `role`'s loadout (applied after the `:default` loadout) adds `:blocks`
   (UPSERTED over `base-blocks` by name), drops `:removes`, and `:strategy
   :replace` starts from an empty base. No `:load` + no matching loadouts →
   `base-blocks` unchanged (config-absent identity)."
  {:malli/schema [:=> [:catn [::blocks ::blocks]
                       [::role :seon.config/role]
                       [::manifest :seon.config/manifest]]
                  ::blocks]}
  [base-blocks role manifest]
  (let [by-role    (into {} (map (juxt :seon.config/role identity))
                         (:seon.config/loadouts manifest))
        applicable (->> [(:default by-role) (get by-role role)]
                        (remove nil?)
                        ;; dedup when role IS :default (one loadout, not twice)
                        (distinct))
        load-spec  (get (:seon.config/skills manifest) :seon.config/load ::absent)
        loads      (cond
                     (= load-spec :all)     (scan-skill-names)
                     ;; absent ⇒ legacy per-role :default-load (migration bridge)
                     (= load-spec ::absent) (into [] (comp (mapcat :seon.config/default-load)
                                                           (distinct))
                                                  applicable)
                     :else                  (vec (distinct load-spec)))  ; explicit vector (incl [])
        extras     (into [] (mapcat :seon.config/blocks) applicable)
        removes    (into #{} (mapcat :seon.config/removes) applicable)
        replace?   (some #(= :replace (:seon.config/strategy %)) applicable)
        additions  (into (mapv skill-block loads) extras)
        start      (if replace? [] base-blocks)]
    (->> (upsert-by-name start additions)
         (filterv #(not (removes (:seon.agent.ctx/name %)))))))

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
