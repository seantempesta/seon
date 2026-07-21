---
type: research
status: active
tags: [research, agent, flow]
---

# Startup-load cohesion audit + in-place unification plan (2026-06-28)

> Owner question: "Do we have a COHESIVE config + skills + other-load-at-startup
> system? We should be able to easily specify the config file to load per test,
> right?" Owner directive (mid-audit): "unify and update the docs so we stop
> creating parallel systems." This doc is the ACTIONABLE in-place plan — every
> mechanism classified KEEP / FOLD / DELETE, each step REPLACE-IN-PLACE or
> DELETE with `file:line`, no new parallel layer. Companion design doc:
> [[docs/prds/agent-fsm/research/config-loader-2026-06-28.md]].

## TL;DR

The pieces exist and mostly work, but they are **three half-overlapping owners
of "what loads at startup"**, and the one primitive built to be THE single owner
(`seon.state/reconcile!`) **is not wired into the boot path at all**. The
config-file-per-test seam (`SEON_CONFIG` + `SEON_PROFILE`) is **real and
live-proven at the reader**, but **no launcher sets it** and the manifest has **no
`#profile` content**, so per-test customization is reachable only by hand-exporting
an env var. Verdict: **near-cohesive, two genuine parallel systems to collapse**
(reconcile! vs raw boot transacts; the ~15 scattered `SEON_*` reads vs
`seon.config`), plus **one capability gap** (no launcher flag / profile content).

Three sharp findings:

1. **`reconcile!` is a built-but-unused parallel seeder.** `seon.state/reconcile!`
   (`state.cljs:58`) is the declared "ONE primitive over the whole
   declarative-state surface (context blocks, routes, core entities)" — fully
   schema'd, provenance-scoped, retract-stale. It is called from **nowhere in
   `src/`** (grep: only `state_test.cljs`). `boot-seed!` (`client.cljs:2020`)
   instead does **five hand-rolled `db/transact!` steps**, none of which retract
   stale rows. So a config change that DROPS a route/skill leaves the old datom
   live. The unified model = route the desired-set steps through `reconcile!`.

2. **Loadout has a legit two-layer owner (keep), but the env knobs do not.**
   `default-seed-blocks` (in-code baseline, `ctx.cljs:1608`) + manifest deltas
   (`resolve-loadout`, `config.cljs:197`) is a sound baseline/override split —
   KEEP both. The fragmentation is the **~15 `SEON_*`/`process.env` reads** spread
   across `ai.cljs`, `eval.cljs`, `debug.cljs`, `my/skills.cljs`,
   `platform.cljs`, `client.cljs` — `seon.config` owns only 2 of them. Fold the
   rest onto aero `#env` accessors so config is the single env surface.

3. **The config-file seam works but is not exposed.** `SEON_CONFIG=<path>`
   overrides the manifest path and `SEON_PROFILE` selects the aero profile —
   **live-proven** (below). Gap: (a) no launcher (`bin/seon`, `bin/acme`) sets
   either; acme reuses the SAME `config/system.edn` as default; (b)
   `config/system.edn` is a plain map with **no `#profile` variants**, so
   `SEON_PROFILE` currently selects nothing. Wire both into the launchers + add a
   profile example = config-file-per-test becomes first-class.

---

## (a) The boot load-order map (pod, ACTIVE track)

`seon.client/-main` (`client.cljs:2493`, skipped iff `SEON_NO_AUTO_BOOT`) →
`start-agent!`:

| # | Step | Where | Reads / seeds | Customization seam |
|---|------|-------|---------------|--------------------|
| 0 | **Module load** — every `:require` in `client.cljs:31-191` runs `schema/register!`, loads compiled core into the runtime (display-only) | ns load | — | source only |
| 1 | `open-cluster-conn!` — ping wire-server, connect DIS-peer, transact `pod-full-schema` over wire, start listen adapter | `client.cljs:583` | `SEON_RUNTIME_ROOT`, store path | env |
| 2 | **`boot-seed!`** under `{:seon.db/origin :core-seed}` | `client.cljs:2020` | — | — |
| 2a | `manifest = (config/load-manifest)` | `client.cljs:2059` | `SEON_CONFIG` path, `SEON_PROFILE`, `config/system.edn` (aero) | **the seam** |
| 2b | `:entity-schemas` — `schema/all-entity-schemas-tx-data` | `:2073` | registry | source |
| 2c | `:core-seed` — `seed-core!` (user entity + my.kb.shared singleton) | `:2078` | — | source |
| 2d | `:core-index` — `core-index-tx` (`:seon.ns`/`:seon.fn`/`:seon.schema`/`:seon.test` introspection of `core-vars` + `SEON_EXTRA_SRC`) | `:2085` | `SEON_EXTRA_SRC`, `SEON_EXTRA_PRELOAD` | env |
| 2e | `:core-routes` — `config/resolve-routes(route/core-routes-tx, manifest)` | `:2093` | manifest `:routes` | **manifest** |
| 2f | `:core-skills` — `config/resolve-skill-rows(my.skills/seed-skills-tx-data, manifest)` | `:2104` | `SEON_SKILLS_DIR` scan + manifest `:skills` | **env + manifest** |
| 3 | `replay-program-graph!` — load agent-authored DB layer (topo-sorted ns eval) | `client.cljs:782` | store rows | (runtime) |
| 4 | per-agent `create!` → `seed-default-ctx!` → `install!(config/resolve-loadout(default-seed-blocks, agent-role(id), (config/load-manifest)))` | `ctx.cljs:1774` | `default-seed-blocks` baseline + manifest `:loadouts` | **manifest** (re-read) |
| 5 | `ai/seed-config-row!` from `SEON_AI_*` env table | `ai.cljs:440`, table `:223-231` | `SEON_AI_*` (9 vars) | env |
| 6 | `bootstrap-turn!` (newly minted only) | `client.cljs:2133` | — | — |
| 7 | `web.serve/start!`, ticker install, heartbeat | `client.cljs` | — | — |

The manifest is read **twice per boot+create** (step 2a once; step 4 once per
agent created) — harmless (pure file read) but a candidate to thread once.

## (b) Cohesion verdict + the fragmentation

**Verdict: near-cohesive with two parallel systems and one exposure gap.** There
is a real single config entry (`seon.config/load-manifest`) and a real single
boot entry (`boot-seed!`), and the manifest already owns the loadout/route/skill
deltas. But:

- **Parallel seeder.** `reconcile!` (`state.cljs:58`) vs `boot-seed!`'s 5 raw
  transacts (`client.cljs:2073-2111`). Two mechanisms for "make the managed
  datoms match a desired set." `reconcile!` is the more correct one
  (provenance-scoped + retract-stale) and is **unused**; `boot-seed!` is the one
  that runs and **never retracts**. A config that removes a route/skill leaves it
  live. → collapse onto `reconcile!`.
- **Parallel env surface.** `seon.config` owns `SEON_CONFIG`/`SEON_PROFILE`;
  every other knob (`SEON_AI_*`, `SEON_SKILLS_DIR`, `SEON_DEBUG_CAPTURE*`,
  `SEON_EXTRA_SRC`, `SEON_RESULT_*`, `SEON_STORE_EDN_CAP`, `SEON_INSTRUMENT`,
  `SEON_DEFAULT_TURN_LIMIT`, `SEON_EMBED`, `SEON_RUNTIME_ROOT`, `SEON_NO_AUTO_BOOT`)
  reads `process.env` directly at its own site. The `:seon.config/dirs` manifest
  key already exists for `SEON_SKILLS_DIR` but is **unused** (`config.cljs:64`).
- **No single documented story.** The boot order lives only in `boot-seed!`'s
  docstring + the config-loader research draft; no canonical doc says "here is
  what loads at startup and the one way to customize it."

**Non-issues (do NOT collapse):** `default-seed-blocks` (in-code baseline) vs
manifest deltas is a sound baseline/override layering — keep both. SOUL.md /
AGENTS.md are not seed steps; they are live `file-block`s re-read every render
(`ctx.cljs:1651`) — keep as-is.

## (c) The specify-config-file answer — WORKS at the reader, NOT exposed

**Live proof (read-only, against the running default pod):**

```clojure
;; wrote {:seon.config/skills {:seon.config/exclude [:datahike]}} to a temp file
(set! (.. js/globalThis -process -env -SEON_CONFIG) tmp)
(seon.config/load-manifest)
;; => {:seon.config/skills {:seon.config/exclude [:datahike]}}   ; read the custom file
;; cleared SEON_CONFIG:
(seon.config/load-manifest)
;; => {:seon.config/skills {:seon.config/exclude [:browser-automation :clojure-testing]}} ; fell back to config/system.edn
```

So `SEON_CONFIG=/path/to/test.edn` **does** make the pod load that file
(`config.cljs:139` `(or (env "SEON_CONFIG") default-config-path)`), and
`SEON_PROFILE` is passed to `aero/read-config` (`config.cljs:129`). The reader
seam is correct and uniform with the SOUL.md / `SEON_SKILLS_DIR` precedent.

**The gap (what's missing for "easily customize per test"):**

1. **No launcher sets it.** `bin/seon` and `bin/acme` never export `SEON_CONFIG`
   or `SEON_PROFILE`; acme runs on the **same** `config/system.edn` as default
   (confirmed: no `SEON_CONFIG` anywhere in `bin/`, `.env*`). A test cluster
   cannot point at its own manifest without hand-exporting the var.
2. **No profile content.** `config/system.edn` is a plain map, not an aero
   `#profile` form, so `SEON_PROFILE` currently selects nothing.
3. **`bin/test-cljs` has no config hook** — the suite cannot say "run with
   `config/test.edn`."

## (d) The cohesive target — ONE model, in-place

**The single model:** `seon.config` is the ONE config entry (path =
`SEON_CONFIG` else `config/system.edn`, profile = `SEON_PROFILE`, reader =
aero). `boot-seed!` is the ONE boot entry, and its desired-set steps run through
`reconcile!` so the managed population (origin `:core-seed`/`:config`) always
matches the manifest — add/remove a route or skill in the manifest and the next
boot reconciles it (stale retracted). Every `SEON_*` knob is declared once in the
manifest via aero `#env`, read through a `seon.config` accessor. No new layer.

### Classification + ordered in-place steps

| Mechanism | Verdict | Action |
|-----------|---------|--------|
| `seon.config` / aero manifest (`config.cljs`) | **KEEP — the entry** | becomes single env surface (below) |
| `boot-seed!` raw `:core-routes` + `:core-skills` transacts (`client.cljs:2093,2104`) | **FOLD into `reconcile!`** | REPLACE-IN-PLACE: route desired-set through `seon.state/reconcile!` (origin `:core-seed`, scope `#{:core-seed :config}`) so removals retract |
| `boot-seed!` `:entity-schemas` + `:core-index` (`client.cljs:2073,2085`) | **KEEP** | append-only introspection, not a desired set — leave as plain transacts |
| `seon.state/reconcile!` (`state.cljs:58`) | **KEEP — promote to the seeder** | wire the boot desired-set steps to it; delete its "unused" status, not the fn |
| `default-seed-blocks` (`ctx.cljs:1608`) | **KEEP** | in-code baseline; manifest `:loadouts` override it via `resolve-loadout` |
| `seed-default-ctx!` / `resolve-loadout` (`ctx.cljs:1774`, `config.cljs:197`) | **KEEP** | the manifest IS the loadout-delta owner; no change |
| `my.skills` `SEON_SKILLS_DIR` scan (`my/skills.cljs:93-100`) | **FOLD env read** | REPLACE-IN-PLACE: read dir from `seon.config` (`:seon.config/dirs`, already reserved at `config.cljs:64`) backed by `#env [SEON_SKILLS_DIR ".claude/skills"]`; keep the scan |
| SOUL.md / AGENTS.md `file-block` (`ctx.cljs:1651`) | **KEEP-AS-IS** | reactive render context, not a seed step |
| ~15 scattered `SEON_*` reads (§e) | **FOLD onto `#env`** | REPLACE each direct `process.env`/`env-val` read with a `seon.config` accessor |
| `platform/env-val` (`platform.cljs:86`) | **KEEP** | the low-level reader aero/`seon.config` sit on; remains the one primitive |

**Ordered execution:**

1. **`bin/seon` + `bin/acme`: export the seam.** Add `SEON_CONFIG` (default
   `config/system.edn`) + `SEON_PROFILE` (default unset) pass-through; point
   `bin/acme` at `config/acme.edn` (or `SEON_PROFILE=acme` against one file).
   → config-file-per-cluster becomes first-class. (`bin/seon:~125-166` env block;
   `bin/acme:~26-78`.)
2. **Add a `#profile` example** to `config/system.edn` (or ship `config/test.edn`)
   so `SEON_PROFILE` selects a real variant; document the test recipe.
3. **`bin/test-cljs`: honor `SEON_CONFIG`** so a test run can name its manifest.
4. **Fold the desired-set boot steps onto `reconcile!`** (`client.cljs:2093-2111`)
   — routes + skills become `reconcile!` calls; stale rows retract on manifest
   change.
5. **Env consolidation** (§e) — one `#env` declaration per knob in the manifest,
   one `seon.config` accessor per consumer.

## (e) Env consolidation table (fold onto `seon.config` `#env`)

Every scattered read → the single `seon.config` accessor / manifest `#env` knob:

| Env var | Current read site | Folds onto |
|---------|-------------------|-----------|
| `SEON_CONFIG` | `config.cljs:139` | already the entry (path) |
| `SEON_PROFILE` | `config.cljs:129` | already the entry (profile) |
| `SEON_AI_PROVIDER/MODEL/TEMPERATURE/MAX_TOKENS/THINKING/TIMEOUT_MS/BASE_URL/API_KEY_ENV/EXTRA_BODY` | `ai.cljs:223-231` (env-table) | `:seon.config/ai` section, `#env` per key; `ai/seed-config-row!` reads via config |
| `SEON_SKILLS_DIR` | `my/skills.cljs:98` | `:seon.config/skills :seon.config/dirs` (`#env`, reserved `config.cljs:64`) |
| `SEON_DEBUG_CAPTURE` | `debug.cljs:85` | `:seon.config/debug` `#env` |
| `SEON_DEBUG_CAPTURE_DIR` | `debug.cljs:93` | `:seon.config/debug` `#env` |
| `SEON_EXTRA_SRC` | `client.cljs:1074,1323` | `:seon.config/extra-src` `#env` |
| `SEON_RUNTIME_ROOT` | `platform.cljs:66` | keep low-level (path resolution is pre-config), but expose accessor |
| `SEON_DEFAULT_TURN_LIMIT` | run-bound (`client.cljs:367` ref) | `:seon.config/run` `#env` |
| `SEON_INSTRUMENT` | `eval.cljs:1353` | `:seon.config/instrument` `#env` |
| `SEON_RESULT_VARS_CAP` | `eval.cljs:840` | `:seon.config/caps` `#env` |
| `SEON_STORE_EDN_CAP` | `eval.cljs:1988` | `:seon.config/caps` `#env` |
| `SEON_RESULT_BODY_RENDER_CAP` | `eval.cljs:2076` | `:seon.config/caps` `#env` |
| `SEON_EMBED` | embeddings gate | `:seon.config/embed` `#env` |
| `SEON_NO_AUTO_BOOT` | `client.cljs:2496` | keep at entry (pre-config boot switch) |
| `SEON_SOUL` / soul path | `ctx.cljs` `soul-file-path` | `:seon.config/soul` `#env` (path, like dirs) |

`platform/env-val` (`platform.cljs:86`) stays as the single low-level reader aero
sits on; the consolidation is "no consumer reads `process.env` directly — they
read a `seon.config` accessor."

## (d′) Route fixes to lanes

- **Core lane** owns: `seon.config` (the entry + `#env` consolidation), `boot-seed!`
  → `reconcile!` folding, `seon.state`, `default-seed-blocks`/`seed-default-ctx!`,
  the `bin/seon`/`bin/acme`/`bin/test-cljs` config-file wiring.
- **UI lane** owns: nothing here — routes are data the UI projects; route SEED is
  Core. UI only consumes `:seon.route/*`.

## Docs to update (make the unified story THE story; delete parallel prose)

1. **`docs/seon/architecture/overview.md`** (the map) — add a "Startup load +
   config" section with the §(a) boot-order table and the one-seam statement
   (`SEON_CONFIG`/`SEON_PROFILE`). This is the canonical narrative entry.
2. **`docs/prds/agent-fsm/data-model.md`** — document the `:seon.config/*`
   manifest schema (skills-spec / loadout / route-spec / the `#env` knob
   sections) as the single config schema; reference `config.cljs:55-95`.
3. **`docs/prds/agent-fsm/agent-runtime.md`** — fold the boot/seed narrative onto
   the single `boot-seed!` → `reconcile!` model; delete any description of raw
   per-step seeding as the mechanism.
4. **`docs/prds/agent-fsm/research/config-loader-2026-06-28.md`** — mark its env
   consolidation (#54b) + `reconcile!` folding as the ADOPTED plan, cross-link
   this audit; it already carries the design — this audit is the execution order.
5. **Component note** — add/refresh `docs/seon/components/` entry for
   `seon.config` describing the per-test config-file recipe (`SEON_CONFIG=…`,
   `SEON_PROFILE=…`, `bin/acme` → its own manifest).

After steps 1-5, the boot-order + config story lives in ONE place
(architecture.md, schema in data-model.md); every other doc references it. The
`boot-seed!` docstring stays as the in-code anchor but stops being the only map.
