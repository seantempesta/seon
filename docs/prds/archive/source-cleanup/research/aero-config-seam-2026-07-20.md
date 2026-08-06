---
type: research
status: complete
tags: [research, database, architecture]
---

# Aero → database config seam design (2026-07-20)

Grounded design for the config-through-aero PRD
([[../config-through-aero]]). Every claim carries file:line from this
checkout. Companion evidence:
[[../../runtime-reliability/research/cleanup-audit-config-startup-2026-07-20]].

## Dependency ledger

- aero 1.1.6 (`deps.edn:115`), full vendored source at
  `reference-code/aero/src/aero/core.cljc` (435 lines) plus
  `reference-code/aero/src/aero/alpha/core.cljc` (expand engine) and
  `reference-code/aero/test/aero/core_test.cljc` (edge cases).
- `src/seon/config.cljs` — the one pod manifest reader/resolver (1594
  lines); `aero.core/read-config` invoked at :688.
- `src/seon/client.cljs` — `apply-config!` :1898-1930 (reconcile),
  `acquire-configuration!` :606-623, boot install :2164-2166.
- `src/seon/state.cljs` `reconcile!` — provenance-scoped declarative diff
  (used with managed scope `#{:seon.db.process/boot :seon.db.process/config}`
  and identity attrs `#{:seon.route/name :my.skills/name :seon.config/id}`,
  client.cljs:1925-1930).
- `src/seon/launch.cljc` — descriptor schema; env-fallback default block
  :489-520; `process-launch-descriptor` :522-526.
- Manifests: `config/system.edn` (base), `config/acme.edn`,
  `config/test.edn`, `config/minimal*.edn` (overlays).
- First-party idiom exemplars: the existing `#long #or [#env … default]`
  rows (system.edn:57-113) and the manifest-aware `'merge` override
  (config.cljs:675-677).
- Open-source comparison: `reference-code/kaocha/src/kaocha/config.clj`
  (aero user, `#profile`-based); no juxt/duct system is vendored — aero's
  own tests/README are the remaining authority (used below; nothing was
  taken from memory).

## Aero semantics summary (grounded in reference-code/aero)

Tag set — all `defmethod reader` in `core.cljc`:

| Tag | Line | Semantics (CLJS branch where it differs) |
|---|---|---|
| `#env` | :48-50 | `goog.object/get js/process.env` — **nil when unset** |
| `#envf` | :52-56 | format string over env values |
| `#prop` | :58-61 | **always nil in CLJS** (`System/getProperty` is CLJ-only) — never use in pod manifests |
| `#long` | :63-66 | CLJS `js/parseInt` — lenient (`"12abc"` → 12, garbage → NaN, no error) |
| `#double` | :68-71 | CLJS `js/parseFloat` |
| `#keyword` | :73-77 | idempotent keywordize |
| `#boolean` | :79-82 | CLJS: `(= "true" (.toLowerCase (str value)))` — **"1" coerces to false** |
| `#include` | :84-90 | recursive `read-config` through the resolver |
| `#join` | :92-94 | `apply str` |
| `#read-edn` | :96-98 | nested edn read |
| `#merge` | :100-102 | `(apply merge values)` — **shallow**; Seon overrides it (config.cljs:675-677) |
| `#ref` | :228-241 | in-config path reference (env map lookup; retried) |
| `#profile`/`#hostname`/`#user` | :243-256 | `expand-case` over `:profile` opt / `os.hostname()` / `USER` |
| `#or` | :258-286 | first truthy value; a nil `#env` falls through to the next alternative (test `core_test.cljc:215,230`) |

Mechanics:

- **Extension point**: the `reader` multimethod (:30); unknown tags fall to
  data-readers or throw "No reader for tag" (:32-42). Seon's `'merge`
  override is exactly this designed seam.
- **#include resolution**: `adaptive-resolver` CLJS branch (:136-143)
  joins a relative include against the *including file's directory*
  (`path/join source ".." include`); `:source` is re-merged per
  `read-config` call (:425), so nested includes resolve relative to their
  own file. A missing include resolves to
  `{:aero/missing-include <name>}` **silently** (:140-143; tests
  :161-165) — Seon's loud manifest validation
  (`m/validate :seon.config/manifest`, config.cljs:704-710) is the
  backstop because that map fails the closed manifest schema.
- **CLJS reading**: `cljs.tools.reader.edn` over `fs.readFileSync`
  (:11-21, :200-213); tags become `tagged-literal`s first (:177-189) and
  are evaluated by the expand engine — no eval, no macro resolution.
- **Deferred** values (`:25`, :158-160) and `#profile` deferral rationale
  (:150-156) exist but Seon uses neither; do not introduce them.
- `read-config` entry: :414-430; `default-opts` `{:profile :default}`
  :146-148.

Idiom ruling confirmed: kaocha structures variation with `#profile`;
Seon's proven pattern is separate overlay files
(`#merge [#include "system.edn" {…}]`, acme.edn:23-24, minimal.edn:28-29,
test.edn:3-4, and second-level overlays minimal-nocards/plan/stream) with
the manifest-aware `'merge`. Nothing in aero's source favors `#profile`
over overlays; overlays additionally let Seon's custom agent-context patch
rule live in one place (config.cljs:596-677). **Keep #include+#merge
overlays** (owner ruling honored; no source contradiction found).

## Current-loader trace (end to end)

1. Selection: operator `config/select-manifest`
   (script/seon/dev/config.clj:110-131) — explicit `--config` >
   `SEON_CONFIG` > `config/system.edn` only for a never-born database.
   Pod side: `load-manifest` reads `SEON_CONFIG` (config.cljs:712-721),
   nil = preserve database.
2. Read: `read-config-file` = `(aero/read-config path {})`
   (config.cljs:679-688) with the `'merge` override active; validated
   loudly against the closed `:seon.config/manifest`
   (config.cljs:560-582, 690-710).
3. Resolution: `resolve-config-singleton` (config.cljs:822-940) flattens
   every knob to its effective value (manifest > literal default; env
   participates only through `#env` inside the manifest) into one flat
   `:seon.config/singleton` entity map, `:seon.config/id "cluster"`
   (config.cljs:452).
4. Reconcile: `apply-config!` (client.cljs:1898-1930) builds the desired
   set (routes + skills + singleton) and runs one provenance-scoped
   `state/reconcile!`; **a converged apply submits no transaction**
   (docstring :1904 — this is the idempotence contract). Cold boot calls
   it only when a manifest was explicitly selected (client.cljs:2135-2147);
   `bin/seon config apply` is the explicit repair door (stays
   explicit-only per owner ruling).
5. Runtime read — **two distinct paths, with different adjustability**:
   - **Per-operation acquisition (live-adjustable TODAY).** Consumers
     acquire the singleton entity from a fresh immutable database value
     and pass the decoded map to the pure accessors:
     `client/acquire-configuration!` (client.cljs:606-623 —
     `db/entity database [:seon.config/id "cluster"]` +
     `decode-edn-values`), `seon.agent/configuration-from-entity`
     (agent.cljs:411-437, used at :569, :664, :759, :941),
     `agent/run.cljs:195-197, 452, 744, 1033`, `agent/schedule.cljs:360`,
     `repl/autocomplete.cljs:168, 439`, `agent/ctx/subagents.cljs:252`,
     `execution.cljs:356, 712`, `execution/runtime.cljs:224, 375`,
     `web/serve.cljs:830, 1114`. A `db/transact!` against the singleton
     takes effect on the *next acquisition* with **no new mechanism** —
     the PRD's "adjust at runtime" requirement is already satisfied on
     this path. Propagation is the ordinary database-value acquisition
     each operation performs; reactive surfaces re-render through
     `seon.reactive`'s committed-transaction interest like any other
     datom change.
   - **Ambient boot snapshot (NOT live-adjustable — gap).** Boot installs
     the resolved map once into the async-fiber context:
     `db/install-configuration-context!` (db.cljs:702-706) called at
     client.cljs:2166. `read-resource-options` (db.cljs:745-749) reads
     query/pull ceilings from that ambient
     `:seon.config/configuration`, and `error/with-configuration`
     (error.cljs:338) scopes the `on-core-error` dial the same way.
     Transacting `:seon.config.database.*` or
     `:seon.config/on-core-error` changes the datom but the ambient
     snapshot serves stale values until restart. See "reconciliation
     design" for the fix.

The accessors themselves (config.cljs:1090-1477) are pure over the passed
singleton map with literal fallbacks equal to the manifest defaults — no
hidden env read except `render-strict?` (:1355-1378, a violation, below)
and the documented env-only knob family (:975-1420: `SEON_TICK_MS`,
`SEON_TEST_TIMEOUT_MS`, `SEON_LLM_ATTEMPT_TIMEOUT_MS`,
`SEON_TURN_TIMEOUT_MS`, `SEON_EVAL_RESULT_VARS_CAP`, `SEON_NO_AUTO_BOOT`,
`SEON_EXTRA_SRC`, `ANTHROPIC_API_KEY`).

## Boot-only vs live-adjustable boundary

**Boot-only — launch descriptor, never config facts** (they identify the
process/cluster before any database exists; a transaction cannot re-bind a
listening port or an already-connected socket):

- HTTP port + port file, request socket path, cluster dir, process/log
  dirs, writer REPL port file, build ids, artifact flavor — the exact
  field set of `launch.cljc` `default-process-descriptor` (:489-520).
  Owner of defaults: operator `config.clj:387-416` (PRD problem 2). The
  pod's env-fallback descriptor block does NOT shrink to test-runner-only
  use: it is the launch seam for every direct-launch pod — the docker
  entrypoint (`docker/seon-entrypoint:110-125` execs the client with env
  only, no operator) and the inspect-ai harnesses
  (`src-inspect-ai/src/seon_inspect/tb_agent.py:159`,
  `swebench_arm.py:219`) — as well as the test runner. See the PRD's
  "Grants for direct-launch pods" design.
- Host capability grants `SEON_WEB` / `SEON_SHELL` — recommended
  **launch-descriptor fields**, not singleton datoms (see table; rationale
  there).
- Secrets stay SEAM: `GEMINI_API_KEY`, `SERPER_API_KEY`,
  `ANTHROPIC_API_KEY`, provider `:api-key-env` values — read live from
  env at call time; only the *variable name* is data
  (config.cljs:243-283, audit rows).

**Live-adjustable — singleton datoms** (all already are, or migrate to
be): render caps + explicit-character knobs, run limits, repair dial,
watchdog/breaker, spawn depth, root recent-limit, repl-mode, web
*policy*/search backend, model-transport caps, database
query/pull ceilings (after the ambient-snapshot fix), system-text,
context-profiles, model-variants, agent/root context, skills dir, brand
row. Reactive timings are NOT live-adjustable today: they are a
process-local boot capture (`serve.cljs:1721` `datastar/configure!` →
`reactive.cljs` `!policy` :43-45, :73-78 — the same snapshot class as the
ticker row); they join this list only after the PRD's "Boot `configure!`
snapshots" fix, and `execution.host/configure!` (client.cljs:2112) and
`log/configure!` (client.cljs:2808) are boot-only launch-descriptor
consumers under the dividing rule.

The dividing rule stated once: **process identity and OS resources bind at
launch (descriptor); behavior policy binds at acquisition (database)**.

## Per-violation migration table

| Env read | Location | Manifest key (namespaced) | Aero expression | Database attribute / home | Consumer change |
|---|---|---|---|---|---|
| `SEON_WEB` | agent/web/internal.cljs:43 (`granted?`, read live per call) | none — **launch descriptor** `:seon.launch/web-granted?` `:boolean` | n/a (operator config.clj:301 AND the env-fallback descriptor both compute it through the one shared `launch/env-grant?` fn — "any non-blank value other than `\"0\"` grants") | launch descriptor field, not a datom | `granted?` reads `launch/process-launch-descriptor`; delete its `platform/env-val` probe (the env read moves into descriptor construction, never disappears — direct-launch pods still grant via env through the fallback descriptor). Envelope text (internal.cljs:101, 121-122) updates to name the launcher grant. |
| `SEON_SHELL` | agent/shell/internal.cljs:58-63 (`granted?`) | none — launch descriptor `:seon.launch/shell-granted?` `:boolean` | n/a (config.clj:300, same shared `env-grant?`) | launch descriptor field | same as SEON_WEB; shell.cljs:208-344 docstrings updated. |
| `SEON_WEB`/`SEON_SHELL` at direct launch | docker/seon-entrypoint:110-125; src-inspect-ai/src/seon_inspect/tb_agent.py:159; src-inspect-ai/src/seon_inspect/swebench_arm.py:219 | n/a | n/a | env consumed by the fallback descriptor via `env-grant?` | none — these launchers keep exporting env; the fallback descriptor is their grant seam. Regression gate: one shell-dependent inspect-ai smoke bench in the container (uniform 0 = default-deny defect). |
| `SEON_RENDER_STRICT` | config.cljs:1355-1378 (`render-strict?`, zero-arity env read); exported by bin/test-cljs:97, changed_test.clj:521-522, operator child env config.clj:302-304 | `:seon.config/render` → `:seon.config.render/strict?` | `#boolean #or [#env SEON_RENDER_STRICT "false"]` — **wrappers must export `"true"`/`"false"`, not `"1"`** (aero CLJS `#boolean` is `= "true"`, core.cljc:82) | `:seon.config.render/strict?` `:boolean` on the singleton | `render-strict?` becomes 1-arity over the singleton; `seon.render` guards receive the acquired configuration (they already flow a configuration for the caps). Test wrappers keep working because each test pod boots with its own manifest apply. |
| `SEON_BRAND_NAME` / `_TAGLINE` / `_THEME` | web/brand.cljs:82-97 (`env-row`), synced env→row at boot (`sync!` :190+) | acme.edn overlay only (owner ruling): `:seon.config/brand` `{:seon.web.brand/name … :seon.web.brand/tagline … :seon.web.brand/theme …}` | plain strings in acme.edn; optionally `#or [#env SEON_BRAND_NAME "Acme"]` during transition | existing brand row `[:seon.web.brand/id "brand"]` — add the row to `apply-config!`'s desired set (identity attr `:seon.web.brand/id` joins the managed-identity set) | delete `env-row` + `sync!`'s env input (the reconcile replaces the bespoke sync); `info` (brand.cljs:101-106) unchanged — it already merges an acquired row over defaults, so brand is live-adjustable by ordinary transaction. system.edn carries **no** brand section. |
| `SEON_BRAND_CSS` | brand.cljs:133 (`css-text` 0-arity), datastar.cljs:556, operator packaging config.clj:369, release.clj:1013, bin/acme:96 | `:seon.config/brand` → `:seon.web.brand/css-path` | acme.edn: `"acme/branding/acme.css"` (relative resolves via `platform/artifact-path`, the skills-dir precedent config.cljs:1034-1040) | `:seon.web.brand/css-path` `:string` on the brand row | `css-text` 0-arity is deleted; callers pass the path from the acquired brand row. File *content* stays a fresh per-call read (content is not config data). Packaging keeps its own descriptor-side copy step. |
| `SEON_CLUSTER_DIR` re-read | my/blob.cljs:200 (`!storage-view` init) | none — already in the descriptor (`::launch/cluster-dir`) | n/a | n/a | initialize `!storage-view` from `launch/process-launch-descriptor` `::launch/cluster-dir`; delete the env probe and its duplicate `"data/clusters/default"` default. |
| `SEON_DB_SOCK`/`SEON_REQ_SOCK` re-read | db/transport/uds.cljs:28 (`default-socket-path`) | none — descriptor `::launch/request-socket-path` (launch.cljc:503-505) | n/a | n/a | `default-socket-path` reads the descriptor; delete the env probe and the duplicate `"tmp/seon-cluster-default-db.sock"` literal. |
| `SEON_EMBED` presence gate | embed.clj:153; scrubs at config.clj:305-308 and bin/acme | keep env for now, **fix the gate to a value test** (`"0"`/blank = off) at the owner | n/a this unit | candidate future `:seon.config/embed?` fact (JVM writer reads the database, so a fact is feasible) — record as follow-on, not this unit | delete both translation scrubs once the owner accepts `"0"`. |

Naming follows docs/conventions.md: fully namespaced keys owned by the
consuming namespace (`:seon.web.brand/*`, `:seon.config.render/*`), reused
verbatim as the database attribute — no third umbrella noun.

Why the grants are descriptor fields, not datoms: both `granted?` fns
document "nothing inside the pod can flip it"
(web/internal.cljs:41-43, shell/internal.cljs:59-63). Isolation comes from
processes and the database capability surface (repo CLAUDE.md); a
singleton datom is writable through the ordinary agent-facing
`db/transact!`, so a grant-as-datom would let the pod widen its own
capability. The *policy* (reachability mode, allowlist, search backend)
stays config facts as today. The audit reached the same split
(cleanup-audit row for SEON_WEB: "grant remains a launcher decision → a
launch-descriptor field"). If the owner instead rules that grants must be
datoms, they must at minimum be excluded from the agent transact surface —
surface this before implementation.

## Reconciliation and idempotence design

- **One read, one reconcile** (already true): aero read →
  `resolve-config-singleton` → `apply-config!` → `state/reconcile!` with
  managed scope + identity attrs (client.cljs:1917-1930). New keys are the
  documented four mechanical steps (config.cljs docstring :32-37):
  register the shape, add the manifest key, resolve it in
  `resolve-config-singleton`, and it rides the same reconcile. The brand
  row adds one more desired entity and one identity attr to the same call
  — no second mechanism.
- **Idempotence**: converged apply submits no transaction
  (client.cljs:1904); acceptance = run `bin/seon config apply` twice and
  assert the second reconcile response reports zero tx.
- **Env-at-apply semantics**: with everything behind aero, an env
  override binds only when a manifest is applied (boot with selection, or
  explicit apply). This is the owner's model — runtime truth is the
  datom; env is one input to an explicit apply. Document it in
  system.edn's header when migrating (the current header text at
  system.edn:1-20 already says exactly this).
- **Close the ambient-snapshot gap** so *every* singleton datom is
  live-adjustable: two options, prefer (a).
  - (a) Make `read-resource-options` (db.cljs:745-749) read the
    configuration off the database value in hand instead of the tx-context
    when the entity is present — but that costs an entity read per
    query/pull; so practically:
  - (b) Refresh the installed context: the pod already consumes the
    committed-transaction feed; when a committed transaction touches
    `[:seon.config/id "cluster"]`, re-run `acquire-configuration!` and
    `db/install-configuration-context!` (client.cljs:606-623, 2166 —
    both exist; the addition is one listener arm next to the existing
    feed handling). This keeps per-operation reads free and makes the
    ambient dials (`database.*` ceilings, `on-core-error` scopes) follow
    a live transaction within one feed delivery.
  Either way the acceptance proof is: transact a new
  `:seon.config.render/eval-cap` (per-operation path — must change the
  next rendered context with no restart) *and* a new
  `:seon.config.database.query/max-work` (ambient path — must change the
  next query's ceiling), both observed live.

## Open risks

1. **`#boolean` vs `"1"`**: every current wrapper exports `"1"`
   (bin/test-cljs:97, bin/acme:72-73, config.clj:300-304). Aero CLJS
   `#boolean` coerces only `"true"`. Migrate wrapper values in the same
   commit as each knob, or the knob silently reads false.
2. **`#long` leniency**: `js/parseInt` accepts garbage prefixes and NaN
   flows into `#or` as a truthy NaN? No — NaN is truthy in CLJS `#or`
   (aero's `or` checks truthiness, core.cljc:277). A malformed numeric env
   value can therefore produce NaN datoms. The manifest validator
   (`:seon.config/cap` `[:int {:min 1}]`) rejects NaN at load — verify
   with a test (`SEON_RUN_DEADLINE_MS=abc` must fail loudly, not seed
   NaN).
3. **Missing include is silent** at the aero layer
   (`{:aero/missing-include …}`, core.cljc:140-143); the closed manifest
   schema catches it today. Keep the manifest schema closed; never add a
   permissive `:map` escape hatch.
4. **Grant home decision** (descriptor vs datom) needs the owner's
   confirmation before implementation — recommendation is descriptor
   (capability-surface argument above).
5. **Brand row managed-identity widening**: adding
   `:seon.web.brand/id` to `apply-config!`'s managed identity attrs means
   an acme→system re-apply retracts brand values (desired set has no brand
   row). That is the correct exact-reconcile semantics but is a behavior
   change from today's env-owned sync — note it in the acme runbook.
6. **Ambient-snapshot refresh** (option b) adds one feed-listener arm;
   keep it inside `seon.client`'s existing committed-transaction handling,
   not a new subscription mechanism.
7. `render-strict?` in test wrappers — verified safe: `bin/test-cljs`
   defaults `SEON_CONFIG` to `config/test.edn` (bin/test-cljs:14-19), so
   the strict dial reaches the test pod through the manifest apply once
   `config/test.edn` declares `:seon.config.render/strict?`
   `#boolean #or [#env SEON_RENDER_STRICT "true"]`.
