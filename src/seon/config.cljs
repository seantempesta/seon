(ns seon.config
  "Compile one explicitly selected manifest into database-owned config.

   The pod reads the optional manifest selected by `SEON_CONFIG`. A selected
   manifest becomes database facts; an
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
   FOUR mechanical steps, no reshape: (1) register the
   `:seon.config/<section>` shape once in `seon.config.resolve`, (2) add its key
   to `:seon.config/manifest` there, (3) write one `resolve-<section>` fn here,
   (4) call it at the existing seed point. The manifest map IS the open
   registry; an UNKNOWN key fails LOUD at validation (a config typo is a crash,
   never a silent ignore).

   LEAF — `seon.config` produces block/route MAPS (data carrying literal quoted
   render symbols like `'my.skills/skill-block`), so it requires NEITHER
   `seon.agent.ctx` NOR `my.skills` (no var refs) — the seed-point call edges
   (`ctx → config`, `client → config`) stay one-way, no cycle. Its registered
   schemas therefore use LEAF shapes (`:keyword`, `[:vector :map]`): the full
   `:seon.agent.ctx/block` / `:my.skills/name` validation still happens
   downstream where those shapes are registered (`install!` validates each block,
   `transact!` validates each skill row)."
  (:require
    [cljs.reader :as reader]
    [clojure.string :as str]
    [malli.core :as m]
    [malli.transform :as mt]
    [seon.config.resolve :as resolve]
    [seon.platform :as platform]
    [seon.schema :as schema]))

;;; Schema ownership lives in `seon.config.resolve`, required above before any
;;; manifest reader, accessor, or resolver uses those registered shapes. This pod
;;; namespace delegates instead of maintaining a second schema graph.

(def cluster-config-id resolve/cluster-config-id)
(def cluster-config-lookup-ref resolve/cluster-config-lookup-ref)

(schema/register! ::agent-id :string)
(schema/register! ::routes [:vector :map])

(defn- env
  "A `process.env` value, nil when unset/blank — `seon.platform/env-val`, the
   ONE env reader."
  [var-name]
  (platform/env-val var-name))

(defn process-environment
  "The pod environment as an explicit ordinary string map."
  []
  (let [environment
        (into {}
              (map (fn [key] [key (aget js/process.env key)]))
              (js/Object.keys js/process.env))]
    (if (seq (get environment "SEON_HOST_TIMEZONE"))
      environment
      (assoc environment
             "SEON_HOST_TIMEZONE"
             (try
               (or (some-> (js/Intl.DateTimeFormat.)
                           .resolvedOptions .-timeZone)
                   "UTC")
               (catch :default _ "UTC"))))))

(defn- render-context-file-contents
  [manifest]
  (let [fs (js/require "fs")]
    (into {}
          (keep (fn [path]
                  (try
                    (when (.isFile (.statSync fs path))
                      [path (.readFileSync fs path "utf8")])
                    (catch :default _ nil))))
          (resolve/render-context-file-paths manifest))))

(defn- process-hardware
  "The pod's explicit hardware observations for non-launch callers."
  []
  (let [os (js/require "os")
        available (max 1 (.-length (.cpus os)))]
    {:seon.hardware/cores available
     :seon.hardware/system-memory-bytes (.totalmem os)
     :seon.hardware/fd-soft-limit 1024}))

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

(defn- read-config-file
  "Read + resolve `path` via aero. The ONE reader seam — the manifest shape is
   reader-independent, so a fallback to `cljs.reader/read-string` would swap only
   this body. A per-cluster variant is a SEPARATE file pointed at by
   `SEON_CONFIG` (no `#profile` — one file, one shape). The render section's
   `#long`/`#or`/`#env` tags resolve through aero's own readers here; the
   `#merge` tag uses our manifest-aware [[merge-manifest-pair]] override above
   (a sparse agent-context override can never silently drop the block tree)."
  [path]
  (resolve/read-manifest path (process-environment)))

(defn load-manifest-path
  "Read and validate one explicitly selected manifest path.

   The development operator calls this inside the already-running pod for an
   explicit config operation. Aero therefore resolves tags, environment
   overrides, and relative includes through the exact same reader as boot."
  {:malli/schema [:=> [:catn [::path :string]] :seon.config/manifest]}
  [path]
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
                  :seon.error/kind   :user-input}))))))

(defn load-manifest
  "Read the explicitly selected `SEON_CONFIG` manifest.

   Returns nil when no path is selected: absence means preserve the database,
   not apply an implicit empty/default desired state. A selected path must
   exist and validate; missing or invalid explicit input fails loudly."
  {:malli/schema [:=> [:cat] [:maybe :seon.config/manifest]]}
  []
  (when-let [path (env "SEON_CONFIG")]
    (load-manifest-path path)))

(defn load-resolved-manifest
  "Read, digest-check, and validate one operator-resolved manifest value."
  [{:seon.launch/keys [path sha-256]}]
  (let [fs (js/require "fs")
        crypto (js/require "crypto")
        text (.readFileSync fs path "utf8")
        actual (-> (.createHash crypto "sha256") (.update text "utf8") (.digest "hex"))
        manifest (reader/read-string text)]
    (when-not (= sha-256 actual)
      (throw (ex-info "The resolved manifest digest does not match."
                      {:seon.launch/expected-sha-256 sha-256
                       :seon.launch/actual-sha-256 actual
                       :seon.error/kind :core-bug})))
    (when-not (m/validate :seon.config/manifest manifest)
      (throw (ex-info "The resolved manifest value is invalid."
                      {:seon.config/path path
                       :seon.error/kind :core-bug})))
    manifest))

;;; ============================================================
;;; NAMESPACES POLICY — the explicit-listing resolver (#42). The SHIPPED
;;; default reproduces the pre-config hardcoded rules BYTE-IDENTICALLY; a lean
;;; cluster overrides it with a short explicit list. Pure given the manifest;
;;; [[namespaces-policy]] receives the ordinary decoded singleton explicitly.
;;; ============================================================

(def ^:private default-namespaces-policy
  "The shipped namespace full-source storage policy.

   Complete source for these framework exemplars is indexed so a per-agent
   namespaces block may select it. `my.*` source follows its structural storage
   rule independently. This policy never decides what renders."
  {:seon.config/always '[my.kb my.data my.ui my.canvas
                          my.plan seon.agent.message seon.agent.lifecycle]})

(defn resolve-namespaces
  "Resolve the `:seon.config/namespaces` section into render policy.

   The `manifest` section becomes the policy
   the renderer + boot indexer read (`seon.agent.ctx.namespaces`). KEY-LEVEL
   merge over [[default-namespaces-policy]]. An absent section uses the shipped
   storage set; a present section overrides the one list. Symbols become the
   current ns-name database values."
  {:malli/schema [:=> [:catn [::manifest :seon.config/manifest]]
                  :seon.config/namespaces-policy]}
  [manifest]
  (let [merged (merge default-namespaces-policy (:seon.config/namespaces manifest))]
    {:seon.config/always
     (into #{} (:seon.config/always merged))}))

;;; ============================================================
;;; THE CONFIG RESOLVER. `resolve-config-singleton` maps a manifest
;;; to the FLAT `:seon.config` singleton entity map — every SCALAR knob resolved
;;; to its EFFECTIVE value (env→manifest→default, coerced) + the three decoded
;;; collections. It is the ONE pre-session resolution point used to seed a
;;; fresh database. After the database session opens, the owning operation acquires and decodes
;;; the database singleton once, then passes that ordinary map to the pure
;;; accessors below. There is no ambient reader or fallback.
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

(def default-reactive-policy
  "Reactive timing used when an older persisted configuration has no policy."
  {:seon.config/reactive-settle-ms 16
   :seon.config/reactive-structural-settle-ms 300
   :seon.config/reactive-max-latency-ms 500})

(def default-database-query-policy
  "Generous query ceilings used only to stop runaway database work."
  {:seon.config.database.query/max-work 100000000
   :seon.config.database.query/max-results 1000000
   :seon.config.database.query/max-result-weight 3000000})

(def default-database-pull-policy
  "Generous pull ceilings used only to stop runaway database work."
  {:seon.config.database.pull/max-work 25000000
   :seon.config.database.pull/max-results 1000000
   :seon.config.database.pull/max-result-weight 3000000})

(def configuration-read-profile
  "Datahike ceilings for acquiring the complete config singleton.

   W1 relocates this named policy into aero-backed database facts. The current
   values cover the largest retained singleton tree used by prompt, agent-view,
   and execution-child acquisition."
  {:datahike.resource/max-work 100000
   :datahike.resource/max-results 4096
   :datahike.resource/max-result-weight (* 1024 1024)})

(defn resolve-config-singleton
  "The resolved configuration singleton for one manifest."
  ([manifest]
   (resolve-config-singleton manifest (process-hardware)))
  ([manifest hardware]
   (resolve/resolve-config-singleton
    manifest
    (process-environment)
    hardware
    (render-context-file-contents manifest))))

(defn resolve-ai-config
  "The declared cluster-default LLM desired rows.

   Startup reconciles a declared value before `seon.ai/sync!`; therefore the
   resolution order is declared manifest value, existing database fact when
   this section is absent, then the environment's first-boot seed when no row
   exists. Returns zero or one row. `:seon.ai/api-key-env` is a variable name,
   never a secret."
  {:malli/schema [:=> [:cat :seon.config/manifest] :seon.config/ai-rows]}
  [manifest]
  (resolve/resolve-ai-config manifest))

(defn reactive-policy
  "Returns the reactive-read timing policy from ordinary singleton data."
  {:malli/schema [:=> [:cat :seon.config/singleton]
                  :seon.config/reactive]}
  [configuration]
  (merge default-reactive-policy
         (select-keys configuration (keys default-reactive-policy))))

(defn database-query-policy
  "Return runaway-work ceilings for one Datahike query."
  {:malli/schema [:=> [:cat :seon.config/singleton] :seon.config/database]}
  [configuration]
  (merge default-database-query-policy
         (select-keys configuration (keys default-database-query-policy))))

(defn database-pull-policy
  "Return runaway-work ceilings for one Datahike pull operation."
  {:malli/schema [:=> [:cat :seon.config/singleton] :seon.config/database]}
  [configuration]
  (merge default-database-pull-policy
         (select-keys configuration (keys default-database-pull-policy))))

(defn namespaces-policy
  "The resolved full-source storage policy from ordinary singleton data.

   The operation that owns the immutable database value acquires and decodes
   the singleton once, then passes it here. Runtime namespace selection belongs
   to the agent's namespaces block."
  {:malli/schema [:=> [:cat :seon.config/singleton]
                  :seon.config/namespaces-policy]}
  [configuration]
  {:seon.config/always (or (:seon.config/always configuration) #{})})

;;; --- Package policy accessors. WP-K intentionally lands only this pure read
;;; surface. W1 owns the later aero -> singleton-fact sweep; until then these
;;; fallbacks are the owner-approved open exploration posture.

(defn packages-policy
  "The package-install policy: `:closed`, `:allowlist`, or `:open`.

   W1 will sweep this accessor into database facts and later tighten the
   default from `:open` to `:allowlist`."
  {:malli/schema [:=> [:cat :seon.config/singleton] :keyword]}
  [configuration]
  (get configuration :seon.config.packages/policy :open))

(defn packages-allowlist
  "The npm names and deps libs admitted by package allowlist policy.

   W1 will sweep this accessor into database facts and later tighten the
   package posture from open exploration to this allowlist."
  {:malli/schema [:=> [:cat :seon.config/singleton]
                  [:set [:or :string :qualified-symbol]]]}
  [configuration]
  (get configuration :seon.config.packages/allowlist #{}))

(defn packages-trusted-lifecycle-scripts
  "The npm lifecycle-script trust policy, `:all` or explicit names.

   W1 will sweep this accessor into database facts and later tighten the
   default from trust-all exploration to an explicit allowlist."
  {:malli/schema [:=> [:cat :seon.config/singleton]
                  [:or [:= :all] [:set :string]]]}
  [configuration]
  (get configuration :seon.config.packages/trusted-lifecycle-scripts :all))

(defn packages-install-deadline-ms
  "The wall-clock bound for one staged package install.

   W1 will sweep this accessor into database facts and hardware-derived
   defaults while tightening the package posture to allowlist."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.packages/install-deadline-ms 120000))

(defn packages-max-rows
  "The maximum package-ledger rows admitted in one cluster.

   W1 will sweep this accessor into database facts and hardware-derived
   defaults while tightening the package posture to allowlist."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.packages/max-rows 256))

(defn packages-host-sessions
  "The client session-pool size for each package host.

   W1 will sweep this accessor into database facts and hardware-derived
   defaults while tightening the package posture to allowlist."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.packages.host/sessions 3))

(defn packages-host-call-deadline-ms
  "The default deadline for one package-host call.

   W1 will sweep this accessor into database facts and hardware-derived
   defaults while tightening the package posture to allowlist."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.packages.host/call-deadline-ms 120000))

(defn packages-host-ready-timeout-ms
  "The package-host spawn-to-ready timeout.

   W1 will sweep this accessor into database facts and hardware-derived
   defaults while tightening the package posture to allowlist."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.packages.host/ready-timeout-ms 30000))

(defn packages-host-respawn-backoff-ms
  "The minimum delay between package-host respawn attempts.

   W1 will sweep this accessor into database facts and hardware-derived
   defaults while tightening the package posture to allowlist."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.packages.host/respawn-backoff-ms 1000))

(defn packages-host-swap-queue-deadline-ms
  "The deadline for package calls queued across a host swap.

   W1 will sweep this accessor into database facts and hardware-derived
   defaults while tightening the package posture to allowlist."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.packages.host/swap-queue-deadline-ms 5000))

(defn packages-host-jvm-heap-mb
  "The maximum JVM package-host heap in megabytes.

   W1 will sweep this accessor into database facts and hardware-derived
   defaults while tightening the package posture to allowlist."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.packages.host/jvm-heap-mb 512))

(defn handle-per-channel-cap
  "The maximum live handles retained per package-host channel.

   W1 will sweep this accessor into database facts and hardware-derived
   defaults while tightening the package posture to allowlist."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.handle/per-channel-cap 64))

(defn handle-summary-token-cap
  "The token budget for one package-handle summary.

   W1 will sweep this accessor into database facts and hardware-derived
   defaults while tightening the package posture to allowlist."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.handle/summary-token-cap 40))

(defn packages-exploration-ops
  "Whether exploratory package operations are `:enabled` or `:disabled`.

   W1 will sweep this accessor into database facts and later tighten the
   default alongside the package-policy flip to allowlist."
  {:malli/schema [:=> [:cat :seon.config/singleton] :keyword]}
  [configuration]
  (get configuration :seon.config.packages/exploration-ops :enabled))

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
;;;      SEON_CLUSTER_DIR, SEON_REQ_SOCK, SEON_AGENT_ID, and the
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
  "The skills corpus directory for explicit configuration data.

   Returns the ordinary `:seon.config/skills-dir` value, or nil when this
   configuration declares no corpus.

   The corpus is a CHECKOUT ARTIFACT (a skills dir in the seon tree), so a
   RELATIVE value resolves via `seon.platform/artifact-path` — under
   SEON_RUNTIME_ROOT when set (a containerized/downstream pod running from
   its own data root), else CWD-relative (seon's own usage, byte-identical).
   An absolute value is used as-is."
  {:malli/schema
   [:=> [:catn [::configuration :map]] [:maybe :string]]}
  [configuration]
  (when-let [dir (:seon.config/skills-dir configuration)]
    (if (.startsWith dir "/")
      dir
      (platform/artifact-path dir))))

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

;;; --- Render/output caps — the coherent `SEON_RENDER_*` family (#46). These
;;; are read-time, LLM-facing display truncations. Env OVERRIDES config (owner
;;; model): the manifest's `:seon.config/render` section declares each knob as
;;; `#long #or [#env SEON_RENDER_* default]` in `config/system.edn`, so an env
;;; var wins when set, else the manifest default applies. Each accessor reads
;;; the ordinary resolved singleton supplied by its operation. The literal
;;; fallback equals the manifest default.

;; Render caps are datoms on the config singleton. Every accessor reads the
;; SAME ordinary flat map (`resolve-config-singleton` stores leaf keys
;; top-level). The immutable database value is the cache; there is no second
;; config cache or ambient reader.
(defn database-edn-cap
  "Per-value pr-str truncation cap for stored EDN display.

   Manifest
   `:seon.config.render/database-edn-cap`; env `SEON_RENDER_DATABASE_EDN_CAP`; 16384."
  {:malli/schema
   [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.render/database-edn-cap 16384))

(defn guard-agent-eval-interpreter-step-budget
  "SCI interpreter-step circuit breaker for one agent eval."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (:seon.config.guard/agent-eval-interpreter-step-budget configuration))

(defn guard-authored-render-interpreter-step-budget
  "SCI interpreter-step circuit breaker for one authored render invocation."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (:seon.config.guard/authored-render-interpreter-step-budget configuration))

(defn guard-plan-interpreter-step-budget
  "SCI interpreter-step circuit breaker for one plan invocation."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (:seon.config.guard/plan-interpreter-step-budget configuration))

(defn guard-deadline-ms
  "Wall-clock circuit breaker for one guarded SCI invocation, in milliseconds."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (:seon.config.guard/deadline-ms configuration))

(defn guard-output-cap
  "Captured SCI stdout/stderr circuit breaker, in characters."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (:seon.config.guard/output-cap configuration))

(defn web-render-configuration
  "The JVM web-render dial section projected from one acquired singleton."
  {:malli/schema [:=> [:cat :seon.config/singleton]
                  :seon.config/web-render]}
  [configuration]
  (resolve/web-render-configuration configuration))

(defn eval-render-cap
  "Char cap for one eval row's echoed SOURCE + captured STDOUT.

   Neither
   is dereferenceable via `result/<id>`, so a large one is context-wasting
   noise (manifest `:seon.config.render/eval-cap`; env `SEON_RENDER_EVAL_CAP`;
   1500)."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.render/eval-cap 1500))

(defn message-render-cap
  "Per-message rendered-content char cap for one transcript line.

   A
   single pasted blob must not blow the context (manifest
   `:seon.config.render/message-cap`; env `SEON_RENDER_MESSAGE_CAP`; 4000)."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.render/message-cap 4000))

(defn result-body-render-cap
  "Char cap for the CITABLE RESULT BODY of one eval row.

   THE one owner of
   the value (registry row C32 — it was duplicated as 4096 tokens in
   `seon.eval` vs 16384 chars in `seon.agent.ctx`): both call sites read
   this accessor, `seon.eval/clip-result-body` converting to its token
   budget at the boundary (chars/4). Char-denominated like its family
   siblings (manifest `:seon.config.render/result-body-cap`; env
   `SEON_RENDER_RESULT_BODY_CAP`; 16384)."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.render/result-body-cap 16384))

;;; --- Value-renderer bounds — the `SEON_RENDER_VALUE_*` sub-family
;;; (per-node depth/breadth limits of the structural eval-value skeleton).

(defn value-max-depth
  "Max nesting depth of the value skeleton.

   Manifest
   `:seon.config.render/value-max-depth`; env `SEON_RENDER_VALUE_MAX_DEPTH`; 3."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.render/value-max-depth 3))

(defn value-max-keys
  "Max map keys shown per node.

   Manifest `:seon.config.render/value-max-keys`;
   env `SEON_RENDER_VALUE_MAX_KEYS`; 8."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.render/value-max-keys 8))

(defn value-max-items
  "Max collection items shown per node.

   Manifest
   `:seon.config.render/value-max-items`; env `SEON_RENDER_VALUE_MAX_ITEMS`; 8."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.render/value-max-items 8))

(defn value-max-path-segments
  "Max decoded path elements in one value-drill request.

   Manifest `:seon.config.render/value-max-path-segments`; 32."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.render/value-max-path-segments 32))

(defn value-max-path-bytes
  "Max raw encoded path bytes in one value-drill request.

   Manifest `:seon.config.render/value-max-path-bytes`; 4096. The HTTP owner
   measures UTF-8 bytes before URL decoding or EDN reading."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.render/value-max-path-bytes 4096))

(defn value-max-realized-items
  "Max admitted offset plus page size for one value drill.

   Manifest `:seon.config.render/value-max-realized-items`; 1024. The later
   request owner performs checked safe-integer arithmetic before realization."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.render/value-max-realized-items 1024))

(declare value-max-string value-shape-sample)

(defn effective-value-drill-limits
  "Effective value-drill limits under host and operation policy."
  {:malli/schema
   [:=> [:cat :seon.render.value/limit-normalization-request]
    :seon.render.value/effective-limits]}
  [{:seon.config/keys [configuration]
    :seon.render.value/keys [operation-limits]}]
  (let [operation-limits (or operation-limits {})
        narrowed (fn [k host]
                   (min host (get operation-limits k host)))]
    {:seon.config.render/value-max-path-segments
     (narrowed :seon.config.render/value-max-path-segments
               (value-max-path-segments configuration))
     :seon.config.render/value-max-path-bytes
     (narrowed :seon.config.render/value-max-path-bytes
               (value-max-path-bytes configuration))
     :seon.config.render/value-max-realized-items
     (narrowed :seon.config.render/value-max-realized-items
               (value-max-realized-items configuration))
     :seon.config.render/value-max-depth
     (narrowed :seon.config.render/value-max-depth
               (value-max-depth configuration))
     :seon.config.render/value-max-string
     (narrowed :seon.config.render/value-max-string
               (value-max-string configuration))
     :seon.config.render/value-shape-sample
     (narrowed :seon.config.render/value-shape-sample
               (value-shape-sample configuration))
     :seon.render.value/page-size
     (narrowed :seon.render.value/page-size
               (value-max-items configuration))}))

(defn value-max-string
  "Max chars of a string leaf before it is clipped to a marker.

   Manifest
   `:seon.config.render/value-max-string`; env `SEON_RENDER_VALUE_MAX_STRING`;
   80)."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.render/value-max-string 80))

(defn value-shape-sample
  "How many homogeneous-map elements to probe for a shared shape.

   Manifest `:seon.config.render/value-shape-sample`; env
   `SEON_RENDER_VALUE_SHAPE_SAMPLE`; 8)."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.render/value-shape-sample 8))

(defn value-verbatim-cap
  "Char budget under which an eval value prints WHOLE (REPL-style).

   Otherwise skeletonized (manifest `:seon.config.render/value-verbatim-cap`; env
   `SEON_RENDER_VALUE_VERBATIM_CAP`; 1500)."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.render/value-verbatim-cap 1500))

(defn value-width
  "Inline-vs-break width budget for the skeleton emitter.

   Manifest
   `:seon.config.render/value-width`; env `SEON_RENDER_VALUE_WIDTH`; 72."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.render/value-width 72))

(defn render-fn-token-cap
  "TOKEN cap for one auto-run render fn's ai output.

   The current-ns auto-run pass (`seon.agent.ctx.render-fns`) clips each
   discovered render fn's `:seon.render/ai` string at this token budget so
   one chatty view can't blow the context (manifest
   `:seon.config.render/render-fn-token-cap`; 2000)."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.render/render-fn-token-cap 2000))

;;; --- Explicit-character render knobs (transcript-render redesign). Each
;;; default reproduces today's bytes, so an absent section renders identically.

(defn render-whitespace
  "Whitespace rendering mode for string content: `:raw` or `:visible`.

   `:visible` makes tab/space glyphs (`·`/`→`) explicit so a whitespace bug
   (tabs-vs-spaces in Python) is visible; `:raw` (default — byte-identical to
   today) leaves literal (manifest `:seon.config.render/whitespace`)."
  {:malli/schema [:=> [:cat :seon.config/singleton] :keyword]}
  [configuration]
  (get configuration :seon.config.render/whitespace :raw))

(defn render-tabs
  "Tab rendering mode: `:literal` (default) or `:arrow` (`→`).

   Manifest `:seon.config.render/tabs`; default reproduces today's bytes."
  {:malli/schema [:=> [:cat :seon.config/singleton] :keyword]}
  [configuration]
  (get configuration :seon.config.render/tabs :literal))

(defn render-trailing-ws
  "Trailing-whitespace marker mode: `:off` (default) or `:dot` (`·`).

   Manifest `:seon.config.render/trailing-ws`; default reproduces today."
  {:malli/schema [:=> [:cat :seon.config/singleton] :keyword]}
  [configuration]
  (get configuration :seon.config.render/trailing-ws :off))

(defn render-content-layout
  "Content layout for edited text: `:structured` (default) or `:single-line`.

   Manifest `:seon.config.render/content-layout`; default reproduces today."
  {:malli/schema [:=> [:cat :seon.config/singleton] :keyword]}
  [configuration]
  (get configuration :seon.config.render/content-layout :structured))

(defn render-line-numbers?
  "Whether string content renders with a 1-based line-number gutter.

   `false` (default — byte-identical to today); manifest
   `:seon.config.render/line-numbers`."
  {:malli/schema [:=> [:cat :seon.config/singleton] :boolean]}
  [configuration]
  (boolean (get configuration :seon.config.render/line-numbers false)))

(defn on-core-error
  "The core-fault escalation dial: `:crash`, `:gate`, or `:log`.

   Read from explicit ordinary config data. Before the database session opens the caller may
   pass the explicitly selected manifest's resolved singleton. Default `:gate`
   (the SHIPPED posture — pod never crashes, the
   CI-shaped wrappers fail runs that accumulated a new `:core`-fault datom).
   Read by `seon.error/record!` on every `:core` fault."
  {:malli/schema [:=> [:cat [:or :nil :seon.config/singleton]]
                  :seon.config/on-core-error]}
  [configuration]
  (or (:seon.config/on-core-error configuration) :gate))

;;; --- FORM-AUTOFIX (repair) accessors — the `:seon.config/repair` knobs, now
;;; singleton datoms. Absent section ⇒ the owner-ruled defaults (level
;;; `:symbols`, no class kill-switches, 1 fix/form, 50ms budget). Consumers
;;; combine level + classes via `seon.repl.parse.repair/class-enabled?` (the computed rule).

(defn repair-level
  "The repair level: `:off` / `:safe-syntax` / `:symbols` / `:aggressive`.

   Manifest `:seon.config.repair/level`; default `:symbols` (owner ruling
   2026-07-05 — AR agents get pre-flight symbol repair). An unrecognized
   value coerces to `:symbols` (the manifest validator already rejects it
   loudly at load; this is the belt for a stale cache)."
  {:malli/schema [:=> [:cat :seon.config/singleton] :keyword]}
  [configuration]
  (let [raw (get configuration :seon.config.repair/level :symbols)]
    (if (contains? #{:off :safe-syntax :symbols :aggressive} raw) raw :symbols)))

(defn repair-classes
  "The per-class repair kill-switch map `{class-kw boolean}`.

   The manifest map is stored as three optional native boolean attributes;
   absence leaves that class enabled. This projection preserves the parser's
   class-keyed policy shape without storing an opaque aggregate."
  {:malli/schema [:=> [:cat :seon.config/singleton] :map]}
  [configuration]
  (into {}
        (keep (fn [[class attribute]]
                (when (contains? configuration attribute)
                  [class (get configuration attribute)])))
        resolve/repair-class-attributes))

(defn repair-max-fixes
  "Max chained symbol fixes per form.

   Manifest `:seon.config.repair/max-fixes-per-form`; default 1 (multi-fix
   is the unimplemented `:aggressive` tier's territory)."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.repair/max-fixes-per-form 1))

(defn repair-budget-ms
  "Wall-clock budget for one form's whole repair pipeline.

   Over budget = no fix, plain error — a slow fix is a worse product than
   a fast error. Manifest `:seon.config.repair/budget-ms`; default 50."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.repair/budget-ms 50))

(defn web-policy
  "The resolved web-access policy for `seon.agent.web/fetch`.

   `{:seon.agent.web/policy <mode> :seon.agent.web/allowed-domains [host…]}`
   from the ordinary config singleton (the mode is coerced fail-closed in
   [[resolve-config-singleton]]). Mode default `:public-only` (the SSRF-safe
   fallback — a downstream inheritor with NO config is never open by accident);
   `allowed-domains` `[]` (only meaningful under `:allowlist`). Host-owned:
   `seon.agent.web` reads it but nothing in the pod can widen it."
  {:malli/schema [:=> [:cat :seon.config/singleton]
                  [:map
                   [:seon.agent.web/policy :keyword]
                   [:seon.agent.web/allowed-domains [:vector :string]]]]}
  [configuration]
  {:seon.agent.web/policy
   (or (:seon.agent.web/policy configuration) :public-only)
   :seon.agent.web/allowed-domains
   (vec (or (:seon.agent.web/allowed-domains configuration) []))})

(defn web-search-config
  "The resolved web-SEARCH backend config for `seon.agent.web/search`.

   `{:seon.agent.web/search-backend <mode> :seon.agent.web/search-model <id>}`
   from the ordinary config singleton (backend coerced fail-closed in
   [[resolve-config-singleton]]). Backend default `:gemini-grounding`; model
   default `\"gemini-3.1-flash-lite\"`. The API key is NEVER here — read live
   from env (`GEMINI_API_KEY` / `SERPER_API_KEY`) at call time."
  {:malli/schema [:=> [:cat :seon.config/singleton]
                  [:map
                   [:seon.agent.web/search-backend :keyword]
                   [:seon.agent.web/search-model :string]]]}
  [configuration]
  {:seon.agent.web/search-backend
   (or (:seon.agent.web/search-backend configuration) :gemini-grounding)
   :seon.agent.web/search-model
   (or (:seon.agent.web/search-model configuration)
       "gemini-3.1-flash-lite")})

(defn render-strict?
  "The FAIL-LOUD render dial.

   When ON, a render/converter failure THROWS
   (naming the offending block + the full malli `explain`) instead of being
   swallowed by the graceful guard; when OFF, today's guard-and-continue
   (a live prod agent must not hard-crash on one bad block).

   The selected manifest resolves the environment observation once and stores
   the resulting database fact. Render callers pass that immutable
   configuration value explicitly."
  {:malli/schema [:=> [:cat :seon.config/singleton] :boolean]}
  [configuration]
  (true? (:seon.config.render-context/render-strict? configuration)))

(defn host-timezone
  "The manifest-resolved IANA timezone database fact."
  {:malli/schema [:=> [:cat :seon.config/singleton] :string]}
  [configuration]
  (get configuration :seon.config.render-context/host-timezone "UTC"))

(defn file-fingerprint
  "The transacted SHA-256 identity for one manifest-observed file."
  {:malli/schema [:=> [:cat :seon.config/singleton :string]
                  [:maybe :seon.content-hash/digest]]}
  [configuration path]
  (some (fn [fingerprint]
          (when (= path
                   (:seon.config.render-context/file-path fingerprint))
            (:seon.config.render-context/sha-256 fingerprint)))
        (:seon.config.render-context/file-fingerprints configuration)))

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
  (resolve/llm-attempt-timeout-ms (process-environment)))

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
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config/spawn-depth-cap 1))

(defn watchdog-stale-ms
  "Heartbeat-watchdog staleness threshold in ms (Piece 2c).

   A run whose beat (or `started-at`, if it never beat) has not progressed for
   longer is closed `:crashed`. Singleton `:seon.config.watchdog/stale-ms`;
   default 1200000 (20 min — comfortably ABOVE the 15-min per-turn inner bound
   `SEON_TURN_TIMEOUT_MS`, so a slow-but-alive LLM turn is never falsely
   killed)."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.watchdog/stale-ms 1200000))

(defn schedule-breaker-crash-count
  "Schedule-wake breaker trip count N (Piece 2d).

   At ≥N `:crashed` closes within the window, schedule wakes are refused.
   Singleton `:seon.config.breaker/crash-count`; default 3."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.breaker/crash-count 3))

(defn schedule-breaker-window-ms
  "Schedule-wake breaker sliding window in ms (Piece 2d).

   `:crashed` closes older than this don't count toward the trip; the window
   sliding past re-enables schedules (no stored reset). Singleton
   `:seon.config.breaker/window-ms`; default 1800000 (30 min)."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.breaker/window-ms 1800000))

(defn root-recent-limit
  "Bound root's recent activity and failed-eval lookbacks.

   The resolved value is a datom on the config singleton. Passing an immutable
   database value keeps the fleet render exactly reproducible; the zero-arity
   form is for interactive runtime callers."
  {:malli/schema [:=> [:cat :seon.config/singleton] :int]}
  [configuration]
  (get configuration :seon.config.root/recent-limit 12))

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

(defn- context-value
  "Return one acquired context component as independent seed data."
  [configuration attribute]
  (some-> (first (get configuration attribute))
          (dissoc :db/id :seon.config/context)
          (update :seon.agent/ctx
                  (fn [blocks]
                    (->> blocks
                         (map #(dissoc % :db/id))
                         (sort-by (juxt :seon.agent.ctx/priority
                                        (comp str :seon.agent.ctx/name)))
                         vec)))))

(defn- context-config-for
  "Select the effective stored context entity for one agent identity."
  [id configuration]
  (or (when (= id "root")
        (context-value configuration :seon.config/root-context))
      (context-value configuration :seon.config/agent-context)
      {}))

(defn resolve-agent-context
  "Resolve the FULLY-DEFAULTED nested agent-context map for `id`.

   Manifest resolution has already materialized the effective ordinary/root
   component trees. This boundary selects by identity, removes acquisition ids
   so a birth receives independent components, merges the per-mint `override`,
   then fills any key the override left absent.
   Returns `{… agent scalars … :seon.agent/ctx [block …]}`; the caller transacts
   it as ONE nested component-ref tx. An explicit `:seon.agent/ctx` is the
   complete seed tree; absent config resolves to an empty tree."
  {:malli/schema [:=> [:catn [::agent-id ::agent-id]
                       [::override [:maybe :map]]
                       [::configuration :seon.config/singleton]]
                  :seon.config/agent-context-spec]}
  [id override configuration]
  (let [merged (merge (context-config-for id configuration) override)]
    (m/decode :seon.config/agent-context-spec merged ctx-default-transformer)))

(defn context-profiles
  "The named render-profile block patches in `configuration`."
  {:malli/schema [:=> [:cat :seon.config/singleton]
                  :seon.config/context-profiles-spec]}
  [configuration]
  (into {}
        (map (fn [profile]
               [(:seon.config/context-profile profile)
                (->> (:seon.agent/ctx profile)
                     (map #(dissoc % :db/id))
                     (sort-by (juxt :seon.agent.ctx/priority
                                    (comp str :seon.agent.ctx/name)))
                     vec)]))
        (or (:seon.config/context-profiles configuration) [])))

(defn model-variants
  "The named launch-time model attribute maps in `configuration`."
  {:malli/schema [:=> [:cat :seon.config/singleton]
                  :seon.config/model-variants-spec]}
  [configuration]
  (into {}
        (map (fn [variant]
               [(:seon.config/model-variant variant)
                (dissoc variant :db/id :seon.config/model-variant)]))
        (or (:seon.config/model-variants configuration) [])))
