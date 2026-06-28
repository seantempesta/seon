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
   ;; allowlist (absent = all scanned skills)
   [:seon.config/include {:optional true} [:vector :seon.config/skill-name]]
   ;; denylist (the first concrete payload: browser-automation, clojure-testing)
   [:seon.config/exclude {:optional true} [:vector :seon.config/skill-name]]])

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
   [:seon.config/skills   {:optional true} :seon.config/skills-spec]
   [:seon.config/loadouts {:optional true} [:vector :seon.config/loadout]]
   [:seon.config/routes   {:optional true} [:vector :seon.config/route-spec]]])

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
;;; ENV KNOBS — the ONE typed env surface. Every `SEON_*` knob that is NOT a
;;; manifest section is read HERE, coerced to its concrete type, on top of the
;;; single low-level reader `platform/env-val`. Nothing outside this ns reads
;;; `process.env` for a knob — consumers call these accessors. (`platform`
;;; itself is the leaf this ns sits on, so its own `SEON_RUNTIME_ROOT` path
;;; resolution reads `env-val` directly — it cannot require config without a
;;; cycle, and path resolution is pre-config.)
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
  "Max live `result/<id>` vars kept per session (`SEON_RESULT_VARS_CAP`,
   default 200)."
  {:malli/schema [:=> [:cat] :int]}
  []
  (env-int "SEON_RESULT_VARS_CAP" 200))

(defn store-edn-cap
  "Per-value pr-str truncation cap for stored EDN (`SEON_STORE_EDN_CAP`,
   default 16384)."
  {:malli/schema [:=> [:cat] :int]}
  []
  (env-int "SEON_STORE_EDN_CAP" 16384))

(defn result-body-render-cap
  "Per-value render-body truncation cap (`SEON_RESULT_BODY_RENDER_CAP`,
   default 16384)."
  {:malli/schema [:=> [:cat] :int]}
  []
  (env-int "SEON_RESULT_BODY_RENDER_CAP" 16384))

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

(defn- upsert-by-name
  "Merge `additions` over `base` by `:seon.agent.ctx/name` (a re-named block
   replaces in place) — install!'s upsert semantics."
  [base additions]
  (let [names (into #{} (map :seon.agent.ctx/name) additions)]
    (into (filterv #(not (names (:seon.agent.ctx/name %))) base)
          additions)))

(defn resolve-loadout
  "Shape `base-blocks` (`default-seed-blocks`) for an agent of `role` against
   the manifest's `:seon.config/loadouts`. Applies the `:default` loadout then
   the `role` loadout (in that order): each `default-load` skill-name expands to
   a priority-16 `:skill/<name>` always-on block, each `:blocks` entry rides as
   an extra block — both UPSERTED over `base-blocks` by name; `:removes` drop
   blocks; `:strategy :replace` starts from an empty base. No matching loadouts →
   `base-blocks` unchanged."
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
        loads      (into [] (comp (mapcat :seon.config/default-load) (distinct))
                         applicable)
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
