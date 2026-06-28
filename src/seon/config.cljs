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
