---
type: research
status: active
tags: [research, agent, context]
---

# Config → DB reactivity audit — does the pod honor "config seeds the DB, everything else derives"?

## 1. Executive summary

### The owner's intended contract

> "Config loads values into the DATABASE. From then on, EVERYTHING is derived
> from the database. Fully reactive."

Operationally: `seon.config` should be a **boot-time seeder only** — read
`config/system.edn`, transact its values into the cluster store, and never be
touched again at runtime. Every rendered/derived behavior should be a pure
function of the **current db value** (`context = f(db)`), so a live config change
that has been re-seeded reaches every agent, and no value is a one-time copy that
stops tracking its source of truth.

### The actual architecture (one paragraph)

The pod does **not** implement that contract. `seon.config` is a **live runtime
read layer**, not a seeder. Two populations behave completely differently. (a) A
small **declarative desired set** — `:seon.route/*` and the `:my.skills/*` skill
corpus — genuinely IS reconciled into the db at every boot through
`seon.state/reconcile!` (scope `#{:config}`, `src/seon/client.cljs:2492-2514`):
upsert-by-identity + retract-stale, so a dropped route/skill is removed. This is
the ONE place the contract holds. (b) **Everything else** falls into one of two
non-conforming shapes: the process-global render caps, dials, gates, policies,
timeouts, and the namespaces policy are **read live from the manifest at
runtime** by ~40 call sites via memoized `load-manifest` accessors (`context =
f(db, config)`, category B); and the per-agent context — the whole
`:seon.agent/ctx` block tree, `:seon.eval/home-requires`, and any agent-level
scalar — is **copy-once** onto the agent entity at `create!` and **never
reconciled** (category C). There is a mature, provenance-based reconcile
mechanism for the *code corpus* (fns/schemas/nses/tests) that heals drift on
every boot, but it was **never extended to context blocks**, and seeded blocks
carry **no provenance marker** distinguishing a pristine default copy from an
agent-customized one.

### Deviations ranked by severity

| # | Deviation | Class | Severity | Why |
|---|-----------|-------|----------|-----|
| D1 | New/changed default context blocks never reach **existing** agents | C (copy-once) | **HIGH** | `create!` seeds blocks only for a `fresh?` entity; no boot reconcile of `:seon.agent/ctx`. Proven live 2026-07-06. Editing `default-ctx-blocks` / manifest `:seon.agent/ctx` requires `cluster reset` to take effect. |
| D2 | Seeded block entities carry **no provenance** — cannot tell "unmodified default" from "agent-edited" | C (missing marker) | **HIGH** | Without it, a boot reconcile for blocks (the D1 fix) is impossible to write safely — it can't know which blocks to heal vs preserve. The code corpus solved exactly this with `:seon.db/origin :core-seed`; blocks have no equivalent. |
| D3 | `ctx/install!` is broken for any agent whose ctx has a symbol-valued block attr (`:live-tile`) | bug | **HIGH** | Symbol/EDN-string round-trip asymmetry rejects the re-transact of untouched kept blocks. Blocks the natural D1 fix (re-installing the default set). Issue `ctx-install-live-tile-symbol-roundtrip.md`. |
| D4 | `:seon.eval/home-requires` is copy-once onto the agent at seed; a manifest edit never reaches existing agents | C (copy-once) | MED | `home-requires-for` reads the copy-once **datom first** (`eval.cljs:1539-1547`); only a fresh mint or a live `db/transact!` override re-reads config. Same staleness class as blocks. |
| D5 | ~40 runtime call sites read config live for behavior (render caps, dials, gates, policy, timeouts, namespaces policy) | B (`f(db,config)`) | MED (mostly deliberate) | Owner explicitly carved render caps out as *global process caps, not per-agent datoms* (`config.cljs:94-102`). Legit as design, but it means these values are NOT in the db and NOT reactive to db — they need a config-file edit + pod restart, and they are invisible to `as-of`/time-travel/forensic replay. |
| D6 | Render caps etc. are **memoized per `SEON_CONFIG`** — a live config edit is ignored until reload/restart | B (cache) | LOW | Explains the "config edit → restart pod" note. The memo atoms are `def` (not `defonce`) so a hot-reload rotates them; a bare file edit with no reload does nothing. |
| D7 | `home-requires` and the block tree live in **two places** (config manifest AND a seeded db datom) with no single source of truth | dual-source | MED | The db copy silently wins and goes stale (D1/D4); the manifest looks authoritative but isn't, for existing agents. |

The contract is honored for **routes + skills** and violated for **per-agent
context** and **global caps/dials**. The single highest-value fix is a
provenance marker on seeded blocks (D2) enabling a boot reconcile of
`:seon.agent/ctx` (D1), after fixing the `install!` round-trip (D3).

---

## 2. Full inventory table

Lifecycle categories: **A** = transacted into db at seed, all runtime reads
derive from db (CONFORMS); **B** = runtime code reads config directly for
behavior (`f(db,config)`); **C** = materialized onto a db entity once at
create/seed, never reconciled (stale snapshot); **D** = env read at runtime for
behavior.

| Config key / value | Category | Runtime read site(s) (file:line) | Staleness / consequence |
|---|---|---|---|
| `:seon.config/routes` (`:seon.route/*`) | **A** | seeded `client.cljs:2492-2514` via `state/reconcile!`; read from db by the router | CONFORMS. Dropped route is retracted at boot (`config/test.edn` proves it). |
| `:seon.config/skills` corpus (`:my.skills/*`) | **A** | seeded `client.cljs:2500` via `state/reconcile!`; catalog reads db | CONFORMS. Removed skill on disk is retracted at boot. `skills-dir` corpus path itself is a live config+env read (`config.cljs:493-514`, category B). |
| `:seon.agent/ctx` default block tree (`config/default-ctx-blocks`) | **C** | seeded `agent.cljs:467-470` → `ctx/seed-default-ctx!` (`ctx.cljs:2214`) ONLY when `fresh?`; render reads db (`ctx.cljs:2284-2298 agent-blocks`) | **STALE (D1).** New default block never reaches existing agents; `cluster reset` required. Render itself IS `f(db)` (good) — the staleness is purely at the copy-once seed. |
| `:seon.eval/home-requires` (agent-context + root-context) | **C** | seeded via `seed-default-ctx!` scalar transact (`ctx.cljs:2245`); read `eval.cljs:1539-1553 home-requires-for` (datom-first, then config, then const) | **STALE (D4).** Copy-once datom wins over the manifest for existing agents. Consumed only at `setup-agent-ns!` (agent boot), not per-turn. |
| `:my.skills/load` (always-on skill bodies) | **C** | consumed-into-blocks at seed (`config.cljs:1082-1091 expand-skill-blocks`), dropped from scalar transact (`ctx.cljs:2206-2212`) | **STALE.** Block presence IS its truth; a changed `:my.skills/load` only affects fresh agents (same class as D1). |
| `:seon.config/root-context` (`:live-tile` canvas, root-only blocks) | **C** | merged at seed by `context-config-for` (`config.cljs:1126-1151`), id `"root"` | **STALE.** Root's canvas/blocks are copy-once; a manifest edit needs root re-created (cluster reset). |
| `:seon.config/render` store-edn-cap | **B** | `eval.cljs:2871` | Global cap; not in db; config-edit+restart to change; memoized `config.cljs:567-579`. |
| `:seon.config/render` eval-cap | **B** | `ctx.cljs:513` | " |
| `:seon.config/render` result-body-cap | **B** | `ctx.cljs:539`, `eval.cljs:2962`, `:3138` | " (C32 single-owner). |
| `:seon.config/render` message-cap | **B** | `ctx.cljs:570` | " |
| `:seon.config/render` value-max-depth/keys/items/string/shape-sample | **B** | `render/value.cljs:71-75` | " |
| `:seon.config/render` value-verbatim-cap | **B** | `render/value.cljs:84` | " |
| `:seon.config/render` value-width | **B** | `render/value.cljs:372` | " |
| `:seon.config/render` render-fn-token-cap | **B** | `ctx/render_fns.cljs:356` | " (token-denominated). |
| `:seon.config/render` whitespace/tabs/trailing-ws/content-layout/line-numbers | **B** | `render/value.cljs:229-256` | " Transcript-render style knobs, read every render. |
| `:seon.config/namespaces` policy (`:always`, `:current-ns`) | **B** | `ctx/namespaces.cljs:154,166,416`; boot indexer (`client.cljs`) | Read at render AND at boot; memoized `config.cljs:435-449`. Config-only (never a db datom) → not reactive to db. |
| `:seon.config/on-core-error` dial | **B** | `error.cljs:462` | Read on every `:core` fault; memoized `config.cljs:748-764`. Config-only. |
| `:seon.config/repair` level/classes/max-fixes/budget-ms | **B** | `eval.cljs:3413-3414,3599-3602` | Read on every agent eval; memoized `config.cljs:772-823`. Config-only. |
| `:seon.config/web` policy/allowed-domains | **B** | `agent/web/internal.cljs:89` (+ `!policy-override` atom) | Read per fetch; memoized `config.cljs:826-854`. Config-only. |
| `:seon.config/web` search-backend/search-model | **B** | `agent/web/internal.cljs:585` (+ `!search-config-override` atom) | Read per search; memoized `config.cljs:857-888`. |
| `:seon.config/spawn-depth-cap` | **B** | `agent.cljs:612` | Read on every `start!`; `config.cljs:962-972` (NOT memoized — `load-manifest` each call). |
| `:seon.config/watchdog` stale-ms | **B** | `loop.cljs:643`, `subagents.cljs`/`schedule.cljs` render | `config.cljs:974-986`. |
| `:seon.config/schedule-breaker` crash-count/window-ms | **B** | `schedule.cljs:347-348`, `ctx/subagents.cljs:148-149` | `config.cljs:988-1009`. Also read at RENDER (subagents block) → block render is `f(db,config)`. |
| `SEON_TURN_TIMEOUT_MS` | **D** | `loop.cljs:198` | Deliberate infra knob. |
| `SEON_TICK_MS` | **D** | `loop.cljs:665` | " |
| `SEON_LLM_ATTEMPT_TIMEOUT_MS` | **D** | `turn.cljs:453` | " |
| `SEON_TEST_TIMEOUT_MS` | **D** | `test/runner.cljs:541` | " |
| `SEON_EVAL_RESULT_VARS_CAP` | **D** | `eval.cljs:981` | " |
| `SEON_RENDER_STRICT` | **D** | `render.cljs:457` (`render-strict?`) | Deliberate: build flags can't tell prod pod from test process; env can. Kill-switch-ish. |
| `SEON_SOUL` / `SEON_SOUL_FILE` | **D** | `config.cljs:1048-1058 soul-file-path` (at seed) | Gates identity file-blocks at seed; files re-read LIVE at render (`ctx.cljs file-block`). |
| `SEON_SKILLS_DIR`, `SEON_EXTRA_SRC`, `SEON_CONFIG`, `SEON_NO_AUTO_BOOT`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `SERPER_API_KEY`, `SEON_INSTRUMENT`, `SEON_EMBED`, `SEON_WEB`, `SEON_FS_*`, `SEON_SHELL`, ports/sockets/cluster-dir | **D** | various | Deliberate launch-wiring / feature-gates / kill-switches / secrets. Secrets read live at call time is CORRECT (never stored). |
| LLM provider/model (`:seon.ai/config`) | **A** (separate surface) | `seon.ai` env→DB row | Its OWN consolidated env→DB surface; a live switch is a `db/transact!`. Conforms, deliberately outside this manifest (`config/system.edn:21-28`). |

---

## 3. The code-corpus reconcile mechanism — and whether it extends to blocks

### The mechanism (this is the precedent the owner's model implies for blocks)

At every boot, `client.cljs:2445` calls `core-index-tx` (`client.cljs:1891-2035`),
which:

1. **Re-derives** every core `:seon.fn`/`:seon.schema`/`:seon.ns`/`:seon.test`
   row from the freshly-compiled build (`index-core!`/`index-schemas`/`index-tests`).
2. **Compares against what the store holds** — and the comparison is a **full
   content comparison, not a hash**. For fns it diffs the whole derived field
   set `{source, spec, doc, arglists, private?}` (`client.cljs:1930-1935,1996-2010`);
   for nses/schemas/tests it diffs the stored `:...​/source` string against the
   freshly-built one. A row re-emits (drift-heals via identity upsert) whenever
   ANY field differs; a `:seon.fn/spec` that DISAPPEARED is explicitly retracted
   (`client.cljs:2015-2023`) because upsert can't remove a datom.
3. **Distinguishes "safe to update" from "preserve" by PROVENANCE, not content.**
   Only rows whose `:source` datom's tx carries `:seon.db/origin :core-seed` are
   eligible to be overwritten (`core-syms`, `client.cljs:1952-1963`). An
   agent-authored row (detect-and-tee, or a `(register! …)` call-shaped schema
   source, `client.cljs:2004-2006`) with a colliding sym is **never clobbered**.

There is a SECOND, in-process comparison used by the live detect-and-tee at eval
time: `analyzer_info/var-digest` (`analyzer_info.cljs:68-90`) — a single-int
`hash` over the load-bearing meta subset (`:fn-var :arglists :doc :private
:malli/schema :test`), deliberately excluding `:line/:column/:file/:env`. This
digest is **meta-only and misses body-only redefs**, so `changed-defs`
(`eval.cljs:1979-2010`) adds a body-sensitive rescue. Note the split: **boot
reconcile compares the actual source string** (content), while **eval-time
detect-and-tee compares a meta digest** (+ body rescue). The db-level answer to
"has this changed" is therefore the raw source/field content, and the db-level
answer to "may I overwrite it" is the `:core-seed` provenance stamp.

### Does it structurally extend to blocks? No — and here is exactly what's missing

The declarative-desired-set path (`state/reconcile!`, scope `#{:config}`) already
IS this pattern for routes/skills: upsert-by-identity + retract-stale, gated by
`:seon.db/origin :config` provenance. Context blocks could ride the identical
mechanism — but they **don't**, and two things block it:

1. **No provenance marker on seeded blocks.** `seed-default-ctx!` runs inside
   `db/with-agent id` scope (`agent.cljs:468`), so the block transact is stamped
   `:seon.db/origin :agent` — **identical** to what `install!` stamps when an
   agent customizes its own context. There is **nothing** on a block entity that
   says "I am an unmodified copy of manifest default `:namespaces` v1" vs "the
   agent edited me." (Verified: `install!` `ctx.cljs:2170` and `seed-default-ctx!`
   `ctx.cljs:2245` use the same `db/transact!` under the same agent scope; the
   block schema `:seon.agent.ctx/*` carries no origin/hash/source-ref attr.)
   Plainly: **a seeded default block and an agent-authored block are
   indistinguishable in the store.** So a hypothetical block reconcile could not
   tell which blocks to heal (stale default) from which to preserve (agent's
   own) — exactly the discriminator the code-corpus path gets for free from
   `:core-seed`.

2. **No reconcile is even attempted.** `create!` seeds blocks **only** for a
   `fresh?` entity (`agent.cljs:447,467`) and explicitly documents "a resumed
   agent keeps whatever blocks it edited/removed." There is no boot-time
   equivalent of `core-index-tx` for `:seon.agent/ctx`. Blocks are pure
   copy-once.

**The missing piece to make blocks conform:** stamp each seeded block with a
provenance/version marker (e.g. `:seon.db/origin :config` + a manifest-block
content hash, mirroring `:core-seed`), then add a boot reconcile that, for each
existing agent, upserts default blocks whose stored version differs from the
current manifest AND whose marker proves they were never agent-edited — leaving
agent-authored/edited blocks untouched. This is the code-corpus mechanism applied
verbatim; the only new work is the marker (D2) and fixing the `install!`
round-trip (D3) so re-installing the default set validates.

---

## 4. Confirmed bug list

**B1 — New default blocks never reach existing agents (D1).** Confirmed from
code. `agent.cljs:447` computes `fresh? (nil? (db/entity …))`; `agent.cljs:467`
gates the seed-copy on `(when fresh? …)`. No other path seeds `:seon.agent/ctx`
onto an existing agent, and `boot-seed!` / `core-index-tx` cover only
code-corpus + routes + skills, never blocks. **Failing scenario:** add a new
block to `config/default-ctx-blocks` (or manifest `:seon.agent/ctx`), `bin/seon
restart pod`. Existing agents render the OLD block set; only agents created after
the edit get the new block. Confirmed live 2026-07-06 per task brief. Only
`cluster reset` (wipe → agents recreated fresh) applies it fleet-wide.

**B2 — `ctx/install!` rejects any agent whose ctx has a symbol-valued block attr
(D3).** Confirmed from issue `ctx-install-live-tile-symbol-roundtrip.md` +
`install!` code. `install!` (`ctx.cljs:2164-2172`) re-transacts ALL kept blocks
(`current` via `ctx-entities`, minus the names being installed). A kept
`:live-tile` block's `:seon.render.live-tile/content` reads back from the entity
as a **string** (`"seon.render.system/system-view"`) while its schema requires
`:symbol`, so the re-transact of untouched blocks fails validation. **Failing
scenario:** any `install!` call on root (whose ctx carries the `:live-tile`
symbol content). Root can never add a block via the normal verb. Root cause: a
symbol/EDN-string round-trip asymmetry between the storage bridge (writes symbol)
and the kept-path read (reads string). This directly blocks the D1 fix, since a
block reconcile would re-install the default set through this same path.

**B3 — `home-requires` copy-once staleness (D4).** Confirmed from
`eval.cljs:1539-1547`: `home-requires-for` reads the persisted
`:seon.eval/home-requires` **datom first**. `seed-default-ctx!` writes that datom
at create (the manifest sets `:seon.eval/home-requires` for both agent-context
and root-context, `config/system.edn:154-196`). **Failing scenario:** edit the
manifest `home-requires` list, restart pod. Existing agents keep the copy-once
datom (branch 1 wins); only agents minted after the edit (branch 2, config) or an
explicit live `db/transact!` override pick up the change. Same staleness class as
B1.

**B4 — Global render caps ignore a live config edit until reload/restart (D6).**
Confirmed from the memo atoms `render-config-cache` / `ns-policy-cache` /
`on-core-error-cache` / `repair-config-cache` / `web-policy-cache` /
`web-search-cache` (all `def` atoms keyed on `SEON_CONFIG`, e.g.
`config.cljs:567-579`). First read caches; a subsequent `config/system.edn` edit
is not observed within the process. They are `def` (not `defonce`) specifically
so a hot-reload of `seon.config` rotates the atom (`config.cljs:430-432` comment),
and a pod restart obviously re-reads. **This is the code-level WHY of the
operational note "config edit → restart pod."** (`reset-render-cache!`
`config.cljs:581-589` exists only for tests.)

**Why "removed rows → cluster reset" (the second half of the note):** removing a
*route* or *skill* row IS handled by restart alone — the `#{:config}` reconcile
retracts it (`client.cljs:2417-2419`, proven by `config/test.edn` dropping
`:seon.route/agent-call`). But removing/changing a *default context block* is NOT
reconciled — it is copy-once per B1 — so the only way to drop it fleet-wide is
`cluster reset` (wipe the store so agents are recreated with the new default
tree). The note conflates two mechanisms; the code shows routes/skills are
restart-reactive while blocks/home-requires are reset-only.

---

## 5. Open questions I could not settle from code alone

1. **Is B/global-caps-as-config a deliberate permanent carve-out or a to-migrate
   gap?** The `config.cljs:94-102` comment states render caps are "NOT per-agent
   datoms … for the whole process," which reads as intentional. But the owner's
   contract as stated ("everything derived from the db") would put even global
   caps in the db as a singleton config entity. I cannot tell from code whether
   the owner wants these migrated into the db (making them time-travel/replay
   visible) or accepts them as a process-config exception. **Recommendation:** ask
   — a `:seon.config` singleton entity would make the whole thing `f(db)` and let
   `as-of`/forensic replay see the caps that were in force at a failure.

2. **Should `home-requires` (B3) and blocks (B1) reconcile eagerly on boot, or
   only on demand?** Eager reconcile risks stomping an agent that deliberately
   pruned a default block; the provenance marker (D2) is required either way. I
   can't tell from code whether "a resumed agent keeps its edits" is meant to
   also mean "keeps STALE unedited defaults" (current behavior) or just "keeps its
   OWN edits while unedited defaults track the manifest" (the marker-enabled
   behavior). This is a product decision.

3. **Does the `subagents`/`schedule-breaker` render read of config
   (`ctx/subagents.cljs:148-149`) matter for prompt-cache stability?** Those dials
   are stable within a process, so in practice the rendered bytes are stable — but
   it does make that block formally `f(db,config)`. Whether that is acceptable vs.
   pushing the breaker numbers into the db is a design call I can't settle.

4. **Whether the `install!` kept-path (B2) round-trips only `:live-tile` or every
   symbol-valued block attr.** The issue says "suspect every install! caller on
   agents whose ctx contains any symbol-valued block attr." From code the
   `decode-block` path (`ctx.cljs:2269-2282`) decodes `:seon.render/ai|html`
   symbols on the RENDER read but `ctx-entities` (the `install!` kept-path read)
   apparently does not — I did not fully trace `ctx-entities` decode behavior, so
   the exact breadth of B2 is unconfirmed beyond `:live-tile`.
