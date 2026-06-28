---
type: research
status: completed
tags: [research, agent, flow, web]
---

# Config + EDN/markdown loader — design (root-os research topic #1)

> **ADOPTED (2026-06-28).** The keystone — `boot-seed!`'s declarative desired set
> (routes + skills) routed through `seon.state/reconcile!` (origin `:config`,
> retract-on-drop) — plus the config-file seam in the launchers (`bin/seon` /
> `bin/acme` / `bin/test-cljs` export `SEON_CONFIG`/`SEON_PROFILE`, the
> `config/system.edn` `#profile` example, `config/test.edn` / `config/acme.edn`)
> have LANDED and are live-proven on the acme cluster. The single startup-load +
> config story now lives in [[../../../seon/architecture/overview]] (the map +
> per-test recipe), [[../data-model]] §5.6 (the `:seon.config/*` schema), and
> [[../agent-runtime]] (the boot → reconcile! seed model). The remaining
> FOLLOW-ON (env consolidation #54b — the ~15 scattered `SEON_*` reads onto
> `seon.config` `#env` accessors; the eval.cljs sites are locked by a concurrent
> owner fix) is tracked in the audit [[startup-load-cohesion-audit-2026-06-28]].
>
> Original research deliverable for the root-os milestone (`feature/agent-fsm`,
> CLJS pod track). Grounds every load-bearing claim in a `file:line` actually
> read, plus three live proofs against the running pod (store at
> `data/clusters/default`). Master doc:
> [[docs/prds/agent-fsm/root-os-vision.md]] (decision #1, Phase B).

## TL;DR

The pod is **already** a data-driven seeder; it is NOT missing a config system,
it is missing the **file-loaded override layer over two hardcoded vectors**. The
loader's whole job is to turn `default-seed-blocks` (a hardcoded vector in
`seon.agent.ctx`, `ctx.cljs:1599`) and `core-routes-tx` (a hardcoded vector in
`seon.route`, `route.cljs:91`) into **functions of an EDN manifest + markdown
files**, merged over the in-code defaults, consumed at the **two seed call sites
that already exist** — `seed-default-ctx!` (per-agent create) and `boot-seed!`'s
`:core-routes` step. Nothing else changes. No new runtime, no new vocabulary, no
Integrant.

Three sharp findings:

1. **Do NOT unify with Integrant.** Integrant models stateful-component
   *lifecycle* (config value → live object held in a system map, started/stopped
   in dependency order — `integrant/core.cljc:650,472`). The loader produces
   *data* (datoms) seeded once. They are different categories. Decisively: the
   **active pod has no Integrant at all** — Integrant/Aero are the *paused* JVM
   track (`.clj`). Unifying would drag a paused, JVM-only mechanism into a
   runtime that reads EDN with `cljs.reader` and files with `node:fs`.

2. **A datahike dump is NOT the config export.** Datahike's only export
   (`migrate.clj:8` `export-db`) is a flat **CBOR dump of all datoms incl.
   history**, self-described as *"temporary, pending Wanderung"* — whole-store,
   not human-editable, not git-diffable, and **the pod can't even produce one**
   (it is a read replica; the wire-server is the sole writer). Config export must
   be a **read-projection of the loadable datoms back into the same
   EDN-manifest + markdown shape the loader consumes**. That projection is the
   export↔import symmetry; the datahike dump is an orthogonal whole-world backup
   that lives on the JVM/wire-server.

3. **The manifest entry IS a `:seon.agent.ctx/block` map / `:seon.route/route`
   map — zero new schema.** Both are already-registered schemas
   (`ctx.cljs:110`, `route.cljs:53`). Markdown content rides the **existing**
   `:seon.agent.ctx/file-path` + `file-block` mechanism (`ctx.cljs:137,219`),
   which already live-reloads SOUL.md/AGENTS.md every render. Symbols ride the
   **existing** `lookup-value` (`eval.cljs:318`). The loader adds exactly one new
   concept: a **merge strategy** (`:replace` vs `:override`) plus a remove-list.

---

## (a) The current seed / config surface, mapped

### Boot sequence (pod, the active track)

`seon.client/-main` → `start-agent!` → for the primary agent in scope:

| Step | Where | What it seeds |
|------|-------|---------------|
| `boot-seed!` | `client.cljs:2026` (called `:2283`) | the four core transacts, below |
| `prune-core-ghosts!` | `client.cljs:2290` | GC of deleted-core ghost rows |
| `replay-program-graph!` | `client.cljs:762` (called `:2304`) | loads the **agent-authored** DB layer (never core, never blocks/routes) |
| `instrument-from-db-once!` | `:2320` | Malli instrument from the program graph |
| per-agent `boot-one-agent!` + `bootstrap-turn!` | `:2327` | home ns, entity, wake trigger, turn 0 |

`boot-seed!` (`client.cljs:2026`) runs four transacts under one
`{:seon.db/origin :core-seed}` tx-context (`:2072`):

1. `:entity-schemas` — `schema/all-entity-schemas-tx-data` (`:2076`,
   `schema.cljc:347`). **Derived from registered Malli schemas**, not authored.
2. `:core-seed` — `seed-core!` (`:2080`, `client.cljs:867`): the `user` entity +
   the `my.kb.shared` instruction singleton.
3. `:core-index` — `core-index-tx` (`:2087`): the `:seon.ns`/`:seon.fn`/
   `:seon.schema`/`:seon.test` program graph, **derived from the live analyzer**
   (code-as-data — `index-core!`, `client.cljs:1584`).
4. `:core-routes` — `route/core-routes-tx` (`:2095`, `route.cljs:91`): the
   `:seon.route/*` datoms. **HARDCODED vector — config candidate #2.**

### Context blocks (per-agent, seeded at create)

- The block map schema `:seon.agent.ctx/block` is `[:map [::name ::name]
  [::priority ::priority] [:seon.render/ai {:optional true} …]
  [:seon.render/html {:optional true} …]]` (`ctx.cljs:110`). The one render slot
  `:seon.render/ai` is `[:or :string :symbol]` (`render.cljs:76`): a **string** is
  verbatim doctrine, a **symbol** is a late-bound block fn.
- `default-seed-blocks` (`ctx.cljs:1599`) is a **HARDCODED vector** of nine block
  maps (`:1633-1666`). **Config candidate #1.**
- `seed-default-ctx!` (`ctx.cljs:1756`) = `(install! (default-seed-blocks))`
  (`:1764`). `install!` (`ctx.cljs:1697`) upserts blocks by name into the scoped
  agent's `:seon.agent/ctx` (component vector); `remove!` (`:1731`) drops one.
- `seon.agent/create!` (`agent.cljs:438`) calls `seed-default-ctx!` ONLY for a
  genuinely-new entity (the `fresh?` gate, `:439`/`:459`); a resumed agent keeps
  its own edited blocks.

### File-blocks — the markdown-as-content mechanism (already exists)

- `:seon.agent.ctx/file-path` is a registered attr (`ctx.cljs:137`).
- `file-block` (`ctx.cljs:219`) takes a `{:file-path :name :priority}` map and,
  **when the file exists**, returns a block storing the path + the symbol slots
  `'seon.agent.ctx/file-block-ai` / `…/file-block-html` (`:243-247`). Those slot
  fns re-read the file **fresh every render** (`file-block-ai`, `:201`), so an
  edit lands next render with no seed/restart. Absent file → `nil` → no block
  (reactive, no fallback).
- SOUL.md + AGENTS.md are wired as two `file-block`s in `default-seed-blocks`
  (`:1642-1645`). `soul-file-path` (`ctx.cljs:255`) is `SEON_SOUL_FILE` (env
  override) else `SOUL.md`, resolved **CWD-relative** via `process.cwd()`
  (`file-path->abs`, `:139`).

### Routes — routing-as-data (already exists)

- `:seon.route/*` schema in `route.cljs:43-60`; `:seon.route/handler` is a native
  `:symbol` (`:47`) resolved late via `lookup-value` (docstring `:19-26`).
- `core-routes-tx` (`route.cljs:91`) is the hardcoded seed; identity upsert on
  `:seon.route/name`.

### Symbol resolution (already exists, the late binding for both)

`seon.eval/lookup-value` (`eval.cljs:318`) walks `js/globalThis` munged
segment-by-segment. It resolves **compiled core fns AND agent-authored fns
identically** — both land at the same munged paths. This is what lets config
name a render/handler symbol that may not be loaded yet: the render/route guard
falls through to a default on a miss, and self-heals when the symbol appears.

### The EDN reader + path resolution available to the pod

- The pod's EDN reader is `cljs.reader/read-string` (`db.cljs:59,1127`;
  `ctx.cljs:62,371`). **No Aero, no `clojure.edn`.**
- Mixed-`:or` render slots round-trip through `db/decode-edn-value`
  (`db.cljs:1117`) — string-encoded on write, read back on pull.
- World-owned files (SOUL.md, config) resolve **CWD-relative**
  (`process.cwd()`); build artifacts resolve via `platform/artifact-path`
  against `SEON_RUNTIME_ROOT` (`platform.cljs:73`). Config is world-owned, so it
  follows the SOUL.md convention, not artifact-path.

### The system message (a `def`, not a block)

`seon.agent.ctx/system-text` (`ctx.cljs:881`) is a hardcoded **string `def`**
consumed by `seon.ai/effective-system-prompt` (`ai.cljs:360`) as
`(or system-prompt ctx/system-text)` (`:369`). It rides the LLM **system role**,
deliberately decoupled from context blocks. It is NOT a datom and NOT a block.

### Live proofs (running pod, 2026-06-28)

```clojure
;; PROOF 1 — routes are datoms with late-resolved symbol handlers:
(seon.db/query '[:find ?name ?h :where
                 [?e :seon.route/name ?name][?e :seon.route/handler ?h]] db)
;; => [:seon.route/root  seon.web.serve/serve-root!]
;;    [:seon.route/agent seon.web.datastar/serve-agent-page!] … etc.
;;    *** ALSO still present: :seon.route/world + :seon.route/world-feed ***
```

The **retired** `/world` routes still live in the store even though the current
`core-routes-tx` (`route.cljs:97-105`) no longer emits them — identity-upsert
seeds but never retracts. This is a **live demonstration of the route-removal gap**
the loader's `:replace` strategy must close (risk R1 below).

```clojure
;; PROOF 2 — the root agent's seeded blocks ARE plain maps (name/priority/slot):
(seon.agent.ctx/ctx-entities {:seon.agent/id "root"})
;; => [:soul 5 [:file "SOUL.md"]]              ; file-block stores the PATH
;;    [:shared-instructions 10 [:sym my.kb.shared/instructions-block]]
;;    [:namespaces 20 [:sym …/namespaces-block]] … [:transcript 100 …]

;; PROOF 3 — cljs.reader reads a fully-namespaced-kw block map w/ symbol slot,
;; LOSSLESS (the manifest entry needs zero translation):
(cljs.reader/read-string
  (pr-str {:seon.agent.ctx/name :doctrine :seon.agent.ctx/priority 15
           :seon.render/ai 'seon.agent.ctx.namespaces/namespaces-block}))
;; => {:seon.agent.ctx/name :doctrine, :seon.agent.ctx/priority 15,
;;     :seon.render/ai seon.agent.ctx.namespaces/namespaces-block}
```

---

## (b) Verdict: unify with Integrant, or separate concern?

**SEPARATE. The loader is content/state seeding; Integrant is component
lifecycle. Do not force one into the other.** Grounded:

1. **Integrant is a lifecycle engine, not a data seeder.** `ig/init`
   (`integrant/core.cljc:650`) "turns a config map into a system map… initiated
   via the `init-key` multimethod"; `init-key` (`:472`) "turns a config value…
   into a concrete implementation. For example, a database URL might be turned
   into a database connection." `ig/build` (`:430`) traverses keys **in
   dependency order**; `halt!` reverses it. Its unit of work is a **stateful
   resource with a start and a stop** held live in the system map. The config
   loader's unit of work is a **datom transacted once into a DB and then
   forgotten** — there is no object to hold, no halt, no dependency order beyond
   "schemas registered first" (already guaranteed by ns-load).

2. **The active pod has no Integrant.** Integrant + Aero are the **paused JVM
   track** (`config.clj`, `system.clj`, `resources/integrant/hierarchy.edn` —
   all `.clj`). The pod boots through plain async fns (`start-agent!`,
   `boot-seed!`). Per the lane-discipline rule (CLAUDE.md), `.clj`/`.cljs`
   siblings never co-compile. Unifying the *content* loader with Integrant would
   either (a) require Integrant on the pod (it isn't there, and the pod has no
   component-lifecycle problem to solve — its one live resource is the DB conn,
   opened directly), or (b) move content-seeding onto the JVM (wrong track,
   currently paused). Either way you'd be solving a non-problem with a
   heavyweight, off-track dependency.

3. **Where the JVM config IS a useful precedent (borrow the *pattern*, not the
   library).** `seon.config/system-config` (`config.clj:27`) is "read an EDN file
   at boot, parse it with custom readers, return data." That shape — **read EDN
   → data → hand to the boot sequence** — is exactly what the pod loader does,
   minus Aero (the pod uses `cljs.reader`) and minus Integrant (the pod hands
   data to `transact!`, not to a system map). So: **same idea, different
   mechanism, different track.** The honest framing is *"the pod gets its OWN
   config-file step, structurally parallel to the JVM's Aero step, sharing
   neither code nor library"* — not *"unify the two."* They are coherent
   siblings, not one system.

**One caveat that survives the verdict:** the DB connection itself (which the
seeding runs over) *is* a lifecycle resource. On the JVM that conn is an
Integrant component (`:seon.db/flow`, `system.clj:8`); on the pod it's
`open-cluster-conn!` (`client.cljs:563`). The loader depends on a live conn but
does not own it — it is a *function called during boot over the conn*, the same
way `seed-core!` and `core-routes-tx` already are. So even the lifecycle-adjacent
part stays on the right side of the line.

---

## (c) Proposed loader design

### Guiding shape

> **The loader is a pure function from files to tx-data, plugged into the two
> seed points that already exist.** It introduces no runtime and no schema beyond
> a merge directive.

### One small new namespace: `seon.config` (cljs)

A new `seon/config.cljs` (sibling to the JVM `seon/config.clj` — same "load this
track's config" concern, never co-compiled, lane-clean per CLAUDE.md). It owns:

- the generic **read EDN file** + **apply merge strategy** + **expand
  file-blocks** machinery, and
- the config-directive schemas (registered here, the ns that owns the data):
  - `:seon.config/strategy` — `[:enum :replace :override]` (default `:override`)
  - `:seon.config/removes` — `[:vector :keyword]` (block names / route names to drop)
  - `:seon.config/dir` — resolved config root (default `"config"`, CWD-relative;
    `SEON_CONFIG_DIR` env override — the "swap the folder" knob, matching the
    `SEON_SOUL_FILE` precedent).

`seon.agent.ctx` and `seon.route` each call into it with *their* code-default +
*their* config file. The domain wiring stays with the domain (schemas live with
the data); the file/merge plumbing lives once in `seon.config`.

> Naming note: `seon.config` (cljs) is recommended for coherence with the JVM
> sibling, but if the reviewer finds the dual-track name confusing, `seon.boot.config`
> is an equally good home. This is a naming call, not a design fork.

### The manifest files

```
config/
  blocks.edn          ; composition: a vector of :seon.agent.ctx/block maps + strategy
  blocks/             ; large markdown content, one file per content-block
    onboarding.md
  routes.edn          ; a vector of :seon.route/route maps + strategy
SYSTEM.md             ; the LLM system message (world root, CWD-relative)
SOUL.md  AGENTS.md    ; already read live as file-blocks (unchanged)
```

`config/blocks.edn` — **entries ARE block maps** (proof 3: read losslessly), the
only extra keys are the directives:

```clojure
{:seon.config/strategy :override          ; or :replace
 :seon.config/removes  [:warnings]         ; drop these code-default blocks
 :seon.config/blocks
 [;; symbol-slot block (names a render fn, compiled OR agent-authored):
  {:seon.agent.ctx/name :namespaces :seon.agent.ctx/priority 20
   :seon.render/ai seon.agent.ctx.namespaces/namespaces-block}

  ;; markdown-content block → loader expands via the EXISTING file-block:
  {:seon.agent.ctx/name :onboarding :seon.agent.ctx/priority 12
   :seon.agent.ctx/file-path "config/blocks/onboarding.md"}

  ;; inline verbatim doctrine (small text, string slot):
  {:seon.agent.ctx/name :doctrine :seon.agent.ctx/priority 15
   :seon.render/ai "Always reconcile against my.finance.ledger."}]}
```

`config/routes.edn` — entries ARE `:seon.route/route` maps:

```clojure
{:seon.config/strategy :override
 :seon.config/removes  [:seon.route/world :seon.route/world-feed]  ; closes the live gap
 :seon.config/routes
 [{:seon.route/pattern "/finance" :seon.route/method :get
   :seon.route/name :acme.route/finance
   :seon.route/handler acme.web/finance-page!}]}
```

**Keyword discipline:** entries use the **canonical fully-namespaced keys** so
they flow straight into the registered `:seon.agent.ctx/block` /
`:seon.route/route` schemas with zero translation and free validation (the data
rules: "every key fully namespaced, no exceptions"). The manifest is literally a
vector of the same maps the code default produces.

### Override semantics

`merge-by-name(code-default, config)`:

- **`:override` (default).** Start from the code default. For each config entry,
  **upsert by name** (`:seon.agent.ctx/name` for blocks, `:seon.route/name` for
  routes — both already the identity within their set). Same name → replace; new
  name → append. Apply `:seon.config/removes` last (drop those names). This is a
  thin, declarative diff over the baseline: bump `:namespaces`' priority, swap
  one render symbol, add `:onboarding`, drop `:warnings` — without restating the
  whole set.
- **`:replace`.** Ignore the code default entirely; the config IS the complete
  set. (The owner's "fully replace if they want.")

This mirrors the in-memory upsert `install!` already does (`ctx.cljs:1697`), so
blocks behave identically whether seeded from code or config.

### Markdown expansion — reuse `file-block`, do not fork

A manifest block carrying `:seon.agent.ctx/file-path` is handed to the existing
`file-block` (`ctx.cljs:219`), which returns the path-storing, live-reloading
block (or `nil` if the file is absent — reactive). **The markdown content lives
in the file; the DB stores only the path** — which is the whole "EDN =
composition, markdown = content" thesis, and it round-trips for free (export
emits the path, content stays in the file). Verbatim-string blocks store the
string inline (small text only).

### Exactly where it plugs in (call sites UNCHANGED)

1. **Blocks** — `seed-default-ctx!` (`ctx.cljs:1764`) changes from
   `(install! (default-seed-blocks))` to
   `(install! (seon.config/resolve-blocks (default-seed-blocks)))`.
   `default-seed-blocks` stays as the **in-code baseline / fallback** (absent
   config → byte-identical to today). The `create!` call site (`agent.cljs:462`)
   does not change. Runs per-create, which is correct: a config edit + a new
   agent picks it up; existing agents keep their edited blocks (the `fresh?`
   gate).
2. **Routes** — `boot-seed!`'s `:core-routes` step (`client.cljs:2095`) changes
   from `(route/core-routes-tx)` to `(route/seed-routes-tx)`, where
   `seed-routes-tx` = `(seon.config/resolve-routes (core-routes-tx*))` and
   `core-routes-tx*` is the renamed in-code baseline. The `boot-seed!` call site
   does not change. Runs once at boot.
3. **System message** — `effective-system-prompt` (`ai.cljs:369`) changes from
   `(or system-prompt ctx/system-text)` to
   `(or system-prompt (ctx/system-text-effective))`, where `system-text-effective`
   reads `SYSTEM.md` fresh via the file-read util (`read-file-text`,
   `ctx.cljs:154`) with the in-code `system-text` `def` as the **absent-file
   fallback** (reactive, byte-stable between renders → cache-prefix invariant
   preserved). This stays on the LLM system role — NOT a block, NOT a datom —
   honoring the code's deliberate decoupling (`ai.cljs:365`). SYSTEM.md is a
   file, so it is self-exporting (the file IS the export).

> All three are the SAME pattern (read a file → fall back to the in-code default),
> at three points, none of them a new mechanism.

### "Runs after all schemas are registered" + leveraging the `:seon.schema` rows

Schemas register at ns-load (top-level `register!`), which completes before
`boot-seed!`. So the loader is naturally post-registration. The schema leverage
is **validation**: the loader pre-validates each manifest entry against the
registered `:seon.agent.ctx/block` / `:seon.route/route` schema (via
`seon.schema`) to produce a *file-named* error (`"config/blocks.edn entry
:onboarding: missing :seon.agent.ctx/priority"`) instead of a downstream
`transact!` instrumentation throw. `transact!` validates again at the boundary
regardless (`db.cljs`), so this is a better-error layer, not the only guard.

### Export → import symmetry

`seon.config/export!` is the **read-projection inverse** of the loader,
pod-side (read replica, no writer needed):

- **Blocks** — pull the chosen agent's `:seon.agent/ctx` (proof 2 shows the
  shape), `decode-block` each (`ctx.cljs:1782`), strip `:db/id`, and emit a
  `blocks.edn` whose `:seon.config/blocks` is that vector. A file-block emits
  `{:name … :priority … :file-path "SOUL.md"}` (path round-trips, content stays
  in the file). A symbol block emits the symbol; a string block emits the string
  (or, optional ergonomics, spills a large string to `config/blocks/<name>.md`
  and converts it to a file-block).
- **Routes** — pull all `:seon.route` rows, emit `routes.edn`.
- **System message** — already a file (SYSTEM.md); nothing to project.

Because `export!` emits **exactly** the shape `resolve-*` consumes, round-trip is
load(export(db)) ≡ db for the loadable subset. **This is the export↔import
symmetry the owner asked for** — at the EDN-manifest layer, the human-editable,
git-diffable, swappable layer.

### Reconciling with the datahike dump + "persist everything?"

| Export | Tool | Scope | Editable? | Where it runs |
|--------|------|-------|-----------|---------------|
| **Config** | `seon.config/export!` (this design) | blocks + routes + (SYSTEM.md is already a file) | YES, EDN+md, git-diffable | pod (read projection) |
| **Whole world** | datahike `export-db` (`migrate.clj:8`) | ALL datoms incl. history (fns, schemas, evals, transcript, blocks, routes) | NO, CBOR flat dump | wire-server / JVM (sole writer) |

CLAUDE.md's "we don't promise to persist everything, but functions, schemas, and
tests sure" maps cleanly: the **durable corpus** (fns/schemas/tests/blocks/routes)
is what *either* export captures; the **ephemeral** (agent eval state, live run
FSM) is neither persisted nor exported. The config export is the *curated,
swappable* slice of the durable corpus; the datahike dump is the *whole-world
disaster-recovery* of it. They are complementary, not competing — and crucially
the **pod cannot produce the datahike dump** (it isn't the writer), which is the
structural reason the config export must be a read-projection to EDN.

---

## (d) What it explicitly does NOT do (anti-fork guardrails)

1. **No parallel "config runtime."** The loader is a pure fn returning tx-data,
   consumed by the EXISTING `seed-default-ctx!` / `boot-seed!` /
   `effective-system-prompt` call sites. Files are an INPUT to seeding, not a new
   system. (code-as-data-runtime: "Files are an input to seeding, not a new
   runtime.")
2. **No config-authored schemas or program graph.** `:seon.schema` and
   `:seon.ns`/`:seon.fn` rows stay **analyzer-derived** from code
   (`all-entity-schemas-tx-data`, `index-core!`). Config does NOT re-author them
   — that would fork the code-as-data spine. The loader's scope is **blocks +
   routes + system message ONLY** (the genuinely override-able composition). This
   is the sharp pushback on "load ALL initial state": most initial state is
   *derived*, and must stay derived.
3. **No new block/route vocabulary.** Manifest entries are
   `:seon.agent.ctx/block` / `:seon.route/route` maps (registered schemas). The
   only new keys are the `:seon.config/*` directives.
4. **No second markdown reader.** Content rides the existing
   `:seon.agent.ctx/file-path` + `file-block` (live reload, reactive). No new
   "load a markdown file" path.
5. **No new symbol resolver.** Render/handler symbols resolve via the existing
   `lookup-value` (`eval.cljs:318`), so config can name compiled OR
   agent-authored fns identically.
6. **No Aero, no Integrant, no `clojure.edn` on the pod.** EDN via
   `cljs.reader/read-string`; files via `node:fs`; paths CWD-relative like
   SOUL.md.
7. **No new export format.** Export is a read-projection to the SAME manifest
   shape the loader reads. The whole-store datahike dump is left to the
   wire-server, untouched.

---

## (e) Open questions + risks

- **R1 — Route removal under `:replace`/`removes` (LIVE GAP, proof 1).**
  Identity-upsert seeds but never retracts (the `:seon.route/world` rows still in
  the live store prove it). `:replace` and `:seon.config/removes` need a
  **retract-diff**: query the live `:seon.route/name` set, retract any name not
  in the resolved config (or named in `removes`). Blocks already handle this
  (`upsert-ctx-tx`, `ctx.cljs:1687`, retracts the whole `:seon.agent/ctx`
  component vector then re-adds), so the block side is solved; **routes need the
  explicit diff.** Recommend: build it for routes; it also retires `/world`
  without a `cluster reset`.
- **R2 — Per-agent vs global block config.** One `config/blocks.edn` applies to
  every minted agent at create. The root agent needs a *distinct* supervisor
  context (vision §"root's context"). Options: (a) per-id override file
  `config/blocks/<id>.edn` merged on top for that id — the data-driven path; (b)
  interim: root installs its extra blocks post-create via `install!`. Recommend
  (a) as the target, (b) as the cheap first proof. Needs an owner call.
- **R3 — When is config re-read?** Per-create (blocks) + per-boot (routes). File
  reads are cheap; recommend NOT memoizing first (measure, per reactive-context's
  "cache is the perf escape hatch, don't bifurcate"). A config edit then takes
  effect for new agents / next boot — acceptable; a "reload config" REPL verb can
  re-seed an existing agent via `install!` if wanted.
- **R4 — Verbatim-string vs file-block for markdown.** Recommend file-block (live
  reload, content not frozen into the DB, path round-trips). Trade-off: a
  file-block re-reads each render, so its content is NOT a byte-frozen seed — a
  feature for editing, but it means the content isn't owned in the DB (only the
  path). Fine, and consistent with SOUL.md today.
- **R5 — Config naming a not-yet-loaded agent symbol.** `lookup-value` returns
  nil → the render/route guard falls through to a default and self-heals when the
  symbol appears (existing behavior). Worth a one-line warning surface (derive,
  don't store) listing config symbols that currently fail to resolve.
- **R6 — `SYSTEM.md` and the cache-prefix invariant.** `system-text` must stay
  byte-stable across a turn for provider caching (`ctx.cljs:887` "no timestamps,
  no ids"). A file read is stable between renders, so this holds — but a SYSTEM.md
  that embeds a timestamp would silently bust the cache. Document the invariant
  in SYSTEM.md's header. (Soft/pending per vision decision #4 — confirm
  global-vs-root framing.)
- **R7 — Ns naming (`seon.config` cljs vs `seon.boot.config`).** A coherence call,
  noted in (c); not a design fork.

---

## (f) Raw external (Gemini) responses — verbatim

**Gemini was UNAVAILABLE this session.** `agy` is installed
(`/Users/sean/.local/bin/agy`) but returned **no output and exit 0** for every
invocation form tried — `cat prompt.txt | agy -p ""`, `printf … | agy`, and
`agy -p "Reply with the single word OK."`. Likely a non-interactive auth /
rate-limit condition (no error surfaced; silent empty stdout). The prompt
prepared for it is preserved at
`/private/tmp/.../scratchpad/agy-prompt.txt` and covered: (1) Integrant-unify vs
separate, (2) datahike-dump vs config-projection export, (3) EDN+markdown merge
semantics + route-removal-under-upsert, (4) symbols-in-EDN late-resolution
pitfalls.

Per honesty-over-completion: this doc's verdicts rest on **source reads + three
live proofs against the running pod**, which is the stronger evidence here than a
model opinion would have been. If an external skeptic pass is still wanted, re-run
the preserved prompt once `agy` auth is restored and append its verbatim reply
below this line.

```
[no Gemini response captured — see note above]
```

---

## (g) Config-driven context + skill loadouts (2026-06-28 extension)

> Owner ask, 2026-06-28 (verbatim intent): *"I want it easy to prime context based on
> configs so we can experiment with different skills and context being loaded. Make sure
> the config stuff is done and well documented and we can add more things to it. The skills
> being loaded should be data-driven if config is present, otherwise just loading skills
> from the env var isn't the worst thing to do."* Plus: the pod skill corpus should be
> CURATABLE (exclude Claude-Code-only skills like `browser-automation`, `clojure-testing`)
> and certain skills DEFAULT-LOADED (full body always-on, e.g. `repl`), all expressed in
> config — NOT hardcoded.

Sections (a)–(f) above predate two things now in the tree: the shipped `my.skills` corpus
scan (`src/my/skills.cljs`, commit `aba1b5dd`) and the holistic `reconcile!` spine
(`src/seon/state.cljs`, `holistic-state-management-2026-06-28.md`). This section folds the
loadout ask into BOTH, and answers the three questions UI raised in `coordination.md:349-355`.

### TL;DR

The loadout config is **not a new loader** — it is the **override input that shapes the
reconcile desired set** (`holistic-state-management-2026-06-28.md:117-118`) plus the per-agent
block seed. ONE consolidated manifest `config/system.edn` (CWD-relative, `SEON_CONFIG`
override — the SOUL.md/`SEON_SKILLS_DIR` precedent) declares, as registered `:seon.config/*`
Malli, three things the code currently hardcodes:

1. **The pod skill corpus** — `include`/`exclude` over the scanned dir, so
   `browser-automation` + `clojure-testing` are dropped from the seon-agent catalog while
   staying in `.claude/skills/` for Claude Code. **ONE physical corpus, two consumers** —
   curation is by config allowlist/denylist over the shared dir, so the shared coding skills
   are NOT duplicated (the trap UI flagged at `coordination.md:342`). A separate
   `config/skills/` folder is **optional, not required**: `:seon.config/dirs` accepts
   additional corpus dirs if a cluster wants private skills, but the default is the one
   `.claude/skills/` dir curated by name.
2. **The default-load set** — skills whose BODY is always-on (e.g. `repl`), seeded as a
   `:skill/<name>` ctx block at a cached-prefix priority, reusing `my.skills/skill-block`
   (`skills.cljs:331`) — no new render path.
3. **Per-role loadouts** — `:root` vs `:worker` get different default-load + extra/removed
   blocks, merged over `default-seed-blocks` (`ctx.cljs:1607`) by the existing
   `:override`/`:replace` semantics from §(c).

**Config absent → byte-identical to today**: the env-dir scan (`my.skills/skills-dir`,
`SEON_SKILLS_DIR`, `skills.cljs:93`) seeds the full corpus, `default-seed-blocks` seeds
unchanged. The config is an OPTIONAL override to one reconcile desired set / one block seed,
not a second code path.

### (g.0) Config-read layer — ADOPT AERO, not a bespoke EDN read (decision)

> Owner question, 2026-06-28: *"are we using a proper config like aero?"* — and the answer,
> grounded in aero's source + the pod's bundle, is **yes, the pod should adopt aero as its
> config-read layer.** This reverses §(c)'s "no Aero on the pod" guidance, which was wrong on
> one fact: §(c) assumed aero would drag a heavy reader into the pod. It would not — the pod
> already bundles that reader.

**Ground state (read, not guessed).** `aero/aero {:mvn/version "1.1.6"}` IS a dep
(`deps.edn:24`) but used ONLY on the paused JVM track: `seon.config` (`config.clj:1-8`) calls
`aero/read-config` and registers `#ig/ref`/`#ig/refset` readers (`config.clj:11-17`) for the
Integrant system.edn. The ACTIVE pod has **no unified config ns** — ~15 scattered
`process.env`/`aget` reads (`ai.cljs:190`, `client.cljs:1339,1930,2504`, `platform.cljs:93`,
`eval.cljs:60`, `my/skills.cljs:98`, …), and `platform/env-val` (`platform.cljs:87`) even
calls itself *"the ONE env reader"* but is not universally used.

**Does aero actually RUN in the pod? — yes, verified from source (3 facts):**

1. Aero's CLJS branch requires `cljs.tools.reader.edn` / `cljs.tools.reader` /
   `cljs.tools.reader.reader-types` + `goog.string` + `goog.object`, and
   `:require-macros [aero.impl.macro]` (`aero/core.cljc:9-23`, from the 1.1.6 jar). **The pod
   ALREADY bundles `cljs.tools.reader` + `cljs.tools.reader.reader-types`** — `seon.eval`
   requires them (`eval.cljs:39-40`). So aero adds only its own small `core.cljc`, not a new
   reader stack. goog.string/object are always present (Closure).
2. Aero is a **compile-time require**, NOT self-host. `seon.config.cljs` is shadow-compiled
   into `out/client/main.js` like every `src/seon/*.cljs`; the self-host `cljs.js` runtime is
   ONLY for agent-eval'd forms. The coordinator's self-host concern does not apply to a
   compiled ns. `:require-macros [aero.impl.macro]` resolves on the JVM at shadow build time —
   fine.
3. `#env` reads `(gobj/get js/process.env s)` (`aero/core.cljc:45`) — Node, exactly the pod
   runtime. `#profile`, `#long`, `#keyword`, `#include`, `#merge`, `#join` are all
   platform-clean. Render-handle SYMBOLS (`'my.skills/skill-block`) survive: aero reads via
   `cljs.tools.reader.edn`, and a bare symbol is not a tagged literal — it reads natively (the
   same losslessness proof 3 showed for `cljs.reader`).

**Why aero over bespoke (the upside that justifies it):**

- **It IS "a proper config"** — the owner's phrasing — already a dep, already the JVM track's
  config layer, so the two tracks stay coherent siblings (`config.clj` ↔ `config.cljs`, same
  library, same `system.edn` mental model).
- **`#env` consolidates the ~15 scattered `process.env` reads into ONE `seon.config` ns.** The
  manifest (or a small `:seon.config/env` section) declares every env-derived knob with `#env`
  interpolation; the scattered `aget`s repoint to `seon.config` accessors. This is the
  owner's "make the config stuff done."
- **`#profile` expresses per-cluster loadout variation in ONE file** — the owner's "experiment
  with different skills/context being loaded," per cluster, with no second file:

  ```clojure
  ;; ONE config/system.edn, profile-selected at read (default vs the acme test cluster):
  {:seon.config/skills
   {:seon.config/exclude #profile {:default [:browser-automation :clojure-testing]
                                   :acme    []}}                      ; acme sees everything
   :seon.config/dirs    [#env [SEON_SKILLS_DIR ".claude/skills"]]}    ; env knob, declared once
  ```

  `load-manifest` passes `{:profile (or #env SEON_PROFILE :default)}` to `aero/read-config`.
  Driving an agent with a precise curated context is then a **profile switch**, exactly UI's
  Q3.

**Honesty caveat (the one thing not yet proven).** I could NOT live-eval aero in the running
pod — `seon.config` and the aero require are not in the current bundle; adding them needs a
shadow rebuild. The "runs in pod" verdict rests on the three SOURCE facts above, NOT a live
proof. So **build step 1's FIRST action is the live gate**: add the require, restart the pod,
eval `(aero/read-config (io/resource …) {})`. If aero fails to compile or run in shadow for
any reason, **fall back to bespoke `cljs.reader/read-string`** (§(c)'s original plan) — the
manifest SHAPE is byte-identical either way, only the reader swaps, so this decision is
reversible at near-zero cost. Recommend aero; gate it on that one eval.

### The manifest — `config/system.edn`, one file that grows

UI's Q3 (`coordination.md:352-354`: *"should the agent's FULL context manifest be ONE
config the test cluster overrides?"*) — **yes.** The separate `config/blocks.edn` /
`config/routes.edn` of §(c) become **sections of one manifest keyed by concern**, because
"we can add more things to it" wants *add-a-key*, not *add-a-file-plus-loader-wiring*.
Large content still lives in markdown files referenced by `:seon.agent.ctx/file-path`
(the §(c) thesis, unchanged).

```clojure
;; config/system.edn — the consolidated context manifest. EVERY key fully namespaced;
;; entries that ride registered schemas (:seon.agent.ctx/block, :seon.route/route) ARE
;; those maps verbatim (proof 3 round-trips them). Role keys are enum SELECTORS, not
;; entity attrs (bare like :core-seed / :override are).
{;; ── the curated POD skill corpus (global; shapes the :my.skills/* desired rows) ──
 :seon.config/skills
 {:seon.config/dirs    [".claude/skills"]                 ; default [skills-dir]; multi-dir allowed
  :seon.config/exclude [:browser-automation :clojure-testing]}   ; ← the first concrete payload

 ;; ── per-role context loadouts (per-agent; merged over default-seed-blocks at create) ──
 :seon.config/loadouts
 [{:seon.config/role         :default
   :seon.config/default-load [:repl]}                     ; repl body always-on for everyone

  {:seon.config/role         :root
   :seon.config/default-load [:repl]
   :seon.config/blocks       [ ;; extra supervisor blocks, ordinary :seon.agent.ctx/block maps
                              {:seon.agent.ctx/name :supervision :seon.agent.ctx/priority 14
                               :seon.render/ai "Watch the fleet; spawn workers for scoped tasks."}]
   :seon.config/removes      []}]

 ;; ── routes (the §(c) routes.edn section, now inline) ──
 :seon.config/routes
 [{:seon.config/strategy :override
   :seon.config/removes  [:seon.route/world :seon.route/world-feed]}]}
```

### The schema (registered in the new `seon.config` cljs ns)

`:seon.config/skill-name` REFERENCES the shipped id shape (`:my.skills/name`,
`skills.cljs:49`) — never re-inline it (the shared-shape rule). Roles are an open enum
selector; a richer role model later stays a config selector, never a stored
`:seon.agent/kind` (memory: *roles = capability-sets, not `:kind`*; there is no
`:seon.agent/role` attr today — root is identified by `:seon.agent/id "root"`,
`agent.cljs:87`).

```clojure
;; src/seon/config.cljs — register! before any entity references these.
(schema/register! :seon.config/strategy   [:enum :override :replace])      ; (from §c)
(schema/register! :seon.config/role       [:enum :default :root :worker])  ; SELECTOR, extensible
(schema/register! :seon.config/skill-name :my.skills/name)                 ; reference, no re-inline

(schema/register! :seon.config/skills-spec
  [:map
   [:seon.config/dirs    {:optional true} [:vector :string]]
   [:seon.config/include {:optional true} [:vector :seon.config/skill-name]]   ; allowlist (absent = all)
   [:seon.config/exclude {:optional true} [:vector :seon.config/skill-name]]]) ; denylist

(schema/register! :seon.config/loadout
  [:map
   [:seon.config/role         :seon.config/role]
   [:seon.config/default-load {:optional true} [:vector :seon.config/skill-name]]
   [:seon.config/blocks       {:optional true} [:vector :seon.agent.ctx/block]]   ; registered shape
   [:seon.config/removes      {:optional true} [:vector :seon.agent.ctx/name]]
   [:seon.config/strategy     {:optional true} :seon.config/strategy]])

;; THE manifest — the registry of known sections. Adding a future section
;; (system-message variant, knowledge seeds, …) = add ONE key here + one resolver fn.
;; An UNKNOWN key is a LOUD, file-named error (surface-errors-loudly), never silently
;; ignored — a config typo fails fast.
(schema/register! :seon.config/manifest
  [:map
   [:seon.config/skills   {:optional true} :seon.config/skills-spec]
   [:seon.config/loadouts {:optional true} [:vector :seon.config/loadout]]
   [:seon.config/routes   {:optional true} [:vector :map]]])   ; §c route entries
```

> **Extensibility contract (the "add more things" guarantee).** A new config concern is:
> (1) `schema/register!` a `:seon.config/<section>` shape, (2) add its key to
> `:seon.config/manifest`, (3) write one `resolve-<section>` fn, (4) call it at the existing
> seed point. No reshaping of the manifest, no new file, no new loader. The manifest map IS
> the open registry.

### Data-driven-if-present, env-scan fallback — ONE code path

`seon.config/load-manifest` reads `config/system.edn` (or `SEON_CONFIG`) via
**`aero/read-config`** (the §(g.0) decision; `{:profile …}` selects the cluster variant),
returning the validated manifest map **or `nil`** when the file is absent. Both seed points then take the manifest as an OPTIONAL override:

- **Skill corpus** — `seon.config/resolve-skill-rows` takes the raw scan
  (`my.skills/seed-skills-tx-data`, `skills.cljs:143`) + the optional `:seon.config/skills`
  spec and returns the curated `:my.skills/*` rows: `nil` spec → the scan unchanged (today's
  behavior); spec present → scan over `:seon.config/dirs`, keep `include` (if given), drop
  `exclude`. ONE function; absent config is the identity.
- **Blocks/loadout** — `seon.config/resolve-loadout` takes `(default-seed-blocks)` + the
  agent's role + the optional loadouts; `nil` → `default-seed-blocks` unchanged; present →
  merge the `:default` loadout then the role's loadout (upsert-by-name, the §(c) `:override`
  semantics, mirroring `install!`'s upsert at `ctx.cljs:1733-1738`), expanding each
  `default-load` skill-name into a `:skill/<name>` body block (below).

Role is a **pure selector**, no stored attr: `seon.config/agent-role` = `(if (= id "root")
:root :worker)` (extensible). This keeps role a config-composition key, never an entity
kind stamp.

### Default-load mechanism — a skill body becomes an always-on cached block

`my.skills/load` (`skills.cljs:213`) installs a `:skill/<name>` block at `load-priority`
**30** (volatile band, `> stable-priority-max` 20, so a runtime load/unload never busts the
cached prefix). A **default-loaded** skill is the same block, but seeded into the per-agent
default set at a **cached-prefix** priority so it is always-on AND part of the byte-stable
prefix:

```clojure
;; what resolve-loadout emits for each :seon.config/default-load skill-name:
{:seon.agent.ctx/name     (keyword "skill" (name skill-name))   ; :skill/repl — block-name shape
 :seon.agent.ctx/priority 16                                    ; CACHED band: catalog(12) < 16 < namespaces(20)
 :seon.render/ai          'my.skills/skill-block}               ; REUSE the shipped render fn — no new path
```

- Priority **16** sits between the L0 catalog (`:skills-catalog`, 12, `ctx.cljs:1660`) and
  `:namespaces` (20, `ctx.cljs:1662`), inside the cacheable prefix (`stable-priority-max`
  20, `ctx.cljs:1832`). The body is byte-stable between renders (the SKILL.md file is read
  fresh but stable mid-turn, same invariant as SOUL.md file-blocks), so it does NOT bust the
  provider cache. Multiple default-loaded skills stack at 16 in name-order (the existing
  `agent-blocks` tie-break, `ctx.cljs:1822`).
- It rides the per-agent block seed (`seed-default-ctx!`, `ctx.cljs:1773` → `install!`), so
  it is seed-copied into every fresh agent — "always-on" = "in the default set," reusing the
  exact seed-copy path. The L0 catalog's `● loaded` marker derives correctly (the
  `:skill/<name>` namespace IS the loaded signal, `skills.cljs:189`), no special-casing.
- An agent can still `(my.skills/unload :repl)` it (it is an ordinary block); a default-load
  is a seeded default, not a lock. That is the right ergonomics — the human primes context,
  the agent retains agency.

### Where it plugs in (call sites, file:line)

| Seed point | Today | Change | Scope |
|---|---|---|---|
| `boot-seed!` `:core-skills` (`client.cljs:2114`) | `(my.skills/seed-skills-tx-data)` straight transact | `(seon.config/resolve-skill-rows (my.skills/seed-skills-tx-data) manifest)` | global corpus |
| `seed-default-ctx!` (`ctx.cljs:1781`) | `(install! (default-seed-blocks))` | `(install! (seon.config/resolve-loadout (default-seed-blocks) (seon.config/agent-role id) manifest))` | per-agent blocks |
| (routes, §c) `:core-routes` (`client.cljs:2108`) | `(route/core-routes-tx)` | `(seon.config/resolve-routes (route/core-routes-tx) manifest)` | global routes |

`seon.config` is a **leaf** — it produces block/route/skill MAPS (data) carrying literal
quoted symbols (`'my.skills/skill-block`), so it requires neither `seon.agent.ctx` nor
`my.skills` (no var refs), only `seon.schema` + `cljs.reader` + node `fs`. The edge
`seon.agent.ctx → seon.config` is clean one-way (verify no cycle at build).

> **Exclude → retract honesty (same as R1).** Under reconcile (`reconcile!`, `state.cljs:58`,
> managed scope `#{:core-seed :config}`), an `exclude`d skill previously seeded is RETRACTED
> uniformly (`managed-identities` diff, `db.cljs:1240`) — the curation takes full effect on a
> `cluster reset`. Under the current plain `:core-skills` transact (holistic Step 2 not yet
> landed, `holistic-state-management-2026-06-28.md:150`), `exclude` prevents NEW seeding but a
> previously-seeded excluded row lingers until reconcile routes this step. Recommend landing
> the skills corpus on the reconcile desired set as part of holistic Step 2 so exclude
> retracts cleanly.

### The system-text strip (follow-on seam — flagged, NOT designed here)

Once `repl` is RELIABLY default-loaded for every agent, the REPL/eval mechanics duplicated
in `system-text` (`ctx.cljs:889`+) should collapse to a SINGLE source (the `repl` skill
body). The seam, not the strip:

- **Moves to the `repl` skill body** (`.claude/skills/repl/SKILL.md`): the eval-mechanics
  paragraphs — *THE TRANSCRIPT IS ONE EVAL'ABLE REPL SESSION* (`ctx.cljs:918`), *EVAL
  MECHANICS* (`:930`), *THINK IN COMMENTS* (`:949`), *RESULT VARS* (`:966`), *STATE ACROSS
  TURNS* (`:978`). These are exactly what the `repl` skill ("how the Seon REPL reads,
  repairs, evaluates the forms you write") owns.
- **Stays minimal in `system-text`**: the irreducible frame that is in no skill — *you are
  at a live CLJS-in-Node REPL, no JVM* (`:902`), *the prompt re-derives every turn /
  reactive* (`:910`), *ERRORS ARE VALUES* (`:984`), *register-before-transact + 2-segment
  namespaces* (`:999`). The system role keeps the orientation; the mechanics become a
  default-loaded block.
- **Why a SEPARATE unit**: (1) it must not strip until default-load is live-proven for every
  agent (else agents lose eval mechanics); (2) it shifts text from the **system-role cache
  scope** (`effective-system-prompt`, `ai.cljs:369`) to the **prefix cache scope** (the
  cached block band) — for some providers these are distinct cache regions, so re-measure the
  cache hit after the move. Design the strip in its own follow-on doc; here we only name the
  cutline so the loadout build doesn't accidentally couple to it.

### Build plan (ordered, each REPLACE-IN-PLACE)

> **STATUS (Phase B landed, 2026-06-28).** Steps 0–5 DONE on `feature/agent-fsm`.
> **AERO GATE = PASS** (recommended path taken, NOT the bespoke fallback): aero
> compiles clean in shadow (the `bin/acme build` `:acme-client` build + the
> `:test` `:node-test` build, both 0 warnings) AND runs live —
> `(aero.core/read-config "config/system.edn" {:profile :default})` returned the
> parsed manifest map in the `:client` pod runtime, and the acme pod booted with
> `load-manifest` reading it during `boot-seed!`. Shipped:
> `src/seon/config.cljs` (the `:seon.config/*` schemas + `load-manifest` /
> `resolve-skill-rows` / `resolve-loadout` / `resolve-routes` / `agent-role`),
> `config/system.edn` (first payload: exclude `[:browser-automation
> :clojure-testing]` + `:default` loadout `default-load [:repl]`),
> `test/seon/config_test.cljs` (8 tests / 23 assertions), and the three
> REPLACE-IN-PLACE call sites (`client.cljs` skills + routes, `ctx.cljs`
> `seed-default-ctx!`). Full suite green (697/3218). Two LEAF deviations from the
> draft, both to keep `seon.config` cycle-free under the register!-time
> reference gate (`schema/internal/assert-compilable-schema!` requires a
> referenced schema to already be registered, which `:seon.agent.ctx/block` /
> `:my.skills/name` cannot be from the leaf): (a) `load-manifest` returns `{}`
> (not `nil`) when the file is absent — the empty manifest is the identity
> everywhere, so no `[:maybe]` at any boundary; (b) `:seon.config/skill-name` /
> loadout-`:blocks` are registered as leaf `:keyword` / `[:vector :map]`, with
> the full `:seon.agent.ctx/block` / skill-row validation happening downstream at
> `install!` / `transact!`. Step 6 follow-ons (exclude→retract via reconcile,
> env consolidation, system-text strip) remain DEFERRED.

0. **AERO LIVE GATE (do this FIRST, §(g.0)).** Add `[aero.core :as aero]` to a throwaway pod
   require, `bin/seon cluster reset default` (NOT a bare cljs-watch restart — the detach
   gotcha), eval `(aero/read-config (resource-or-path "config/system.edn") {:profile :default})`
   against the live pod. PASS → proceed with aero; FAIL → switch the reader to
   `cljs.reader/read-string` (manifest shape unchanged) and proceed bespoke. Record the result
   in this doc.
1. **`src/seon/config.cljs`** (NEW leaf ns — the `seon.config` of §(c), already proposed; not
   a v2). Implement: the `:seon.config/*` schemas above; `load-manifest`
   (read `config/system.edn`/`SEON_CONFIG` via `aero/read-config` with the profile, validate
   against `:seon.config/manifest`, `nil` when absent, file-named error on invalid);
   `resolve-skill-rows`; `resolve-loadout`
   (merge `:default`+role, expand `default-load` → priority-16 `:skill/<name>` blocks,
   apply `:override`/`:replace`+`removes`); `agent-role`; `resolve-routes` (the §c routes
   resolver, folded in). **Test** `test/seon/config_test.cljs`: absent-manifest → identity;
   `exclude` drops named rows; `include` allowlists; `default-load` → a priority-16 block
   carrying `'my.skills/skill-block`; per-role merge + `removes`; invalid manifest → loud
   error value.
2. **`src/seon/client.cljs:2114`** — replace the `:core-skills` `seed-skills-tx-data` call
   with `(seon.config/resolve-skill-rows (my.skills/seed-skills-tx-data) (seon.config/load-manifest))`.
   (Load the manifest ONCE near the top of `boot-seed!`, thread it to both skill + route
   steps.) **Test**: existing boot tests stay green; add one asserting an excluded skill is
   absent from the seeded `:my.skills/*` rows when a manifest excludes it.
3. **`src/seon/agent/ctx.cljs:1781`** — `seed-default-ctx!` calls `resolve-loadout` over
   `(default-seed-blocks)` with the scoped agent's role + manifest. Verify no require cycle
   (`ctx → config` one-way). **Test**: a fresh root agent's `:seon.agent/ctx` contains
   `:skill/repl` at priority 16; a worker gets the `:default` loadout; absent manifest →
   block set byte-identical to today.
4. **`config/system.edn`** (NEW) — the first concrete payload: `:seon.config/skills`
   `{:exclude [:browser-automation :clojure-testing]}` + `:seon.config/loadouts`
   `[{:role :default :default-load [:repl]}]`. Add `SEON_CONFIG` to `.env`/`.env.acme` docs
   if a per-cluster override path is wanted (acme can point at a different manifest — the
   "experiment with different skills/context" knob).
5. **Live-prove** (`bin/seon cluster reset default`, pod 7890):
   - the L0 catalog NO LONGER lists `browser-automation`/`clojure-testing`
     (`(my.skills/list)` excludes them); they still exist in `.claude/skills/` for Claude
     Code.
   - a fresh agent's context carries the `:skill/repl` body block always-on
     (`(seon.agent.ctx/ctx-entities {:seon.agent/id "root"})` shows `:skill/repl` @ 16); the
     catalog marks `repl` `● loaded` via derivation.
   - rename/remove `config/system.edn` → reset → the full env-dir scan returns (all 7 skills
     seeded, no default-load) — the fallback path is byte-identical to today.
6. **(SEPARATE follow-ons, do NOT bundle into the loadout build)** —
   (a) route the skills corpus onto the `reconcile!` desired set (holistic Step 2) so
   `exclude` retracts cleanly;
   (b) **env consolidation** — sweep the ~15 scattered `process.env`/`aget` reads
   (`ai.cljs:190`, `client.cljs:1339,1930,2504`, `platform.cljs:93`, `eval.cljs:60`,
   `my/skills.cljs:98`, …) onto `seon.config` accessors backed by aero `#env`, retiring the
   per-site readers (and folding `platform/env-val`'s "ONE env reader" claim into the real
   one). A pure repoint, its own patch;
   (c) the `system-text` strip once default-load is live-proven (its own doc).

---

## Cross-references

- [[docs/prds/agent-fsm/holistic-state-management-2026-06-28.md]] — `reconcile!` over a
  provenance-scoped desired set (`#{:core-seed :config}`); the loadout config is the OVERRIDE
  input that shapes the desired set, not a parallel loader (`state.cljs:58`, `db.cljs:1240`)
- [[docs/prds/agent-fsm/research/my-skills-design-2026-06-28.md]] — the shipped skill corpus
  scan, `:skill/<name>` block handle, `skill-block`/`catalog-block` render fns
  (`src/my/skills.cljs`)
- [[docs/prds/agent-fsm/coordination.md]] — UI's config-driven-context questions
  (`:349-355`); answered: ONE physical corpus + config curation, ONE `config/system.edn`
- [[docs/prds/agent-fsm/root-os-vision.md]] — milestone, decision #1, Phase B
- [[docs/prds/agent-fsm/data-model.md]] — §2.3 symbol-as-value, §4.8 routes,
  the block model
- [[docs/seon/concepts/code-as-data-runtime.md]] — "files are an input to
  seeding, not a new runtime"; schemas/program-graph are analyzer-derived
- [[docs/seon/concepts/reactive-context.md]] — derive-don't-store; cache is the
  perf escape hatch
- Source: `seon.client/boot-seed!` (`client.cljs:2026`),
  `seon.agent.ctx/default-seed-blocks` (`ctx.cljs:1599`), `…/file-block`
  (`ctx.cljs:219`), `…/install!` (`ctx.cljs:1697`),
  `seon.route/core-routes-tx` (`route.cljs:91`), `seon.eval/lookup-value`
  (`eval.cljs:318`), `seon.ai/effective-system-prompt` (`ai.cljs:360`)
- Library: `datahike.migrate/export-db,import-db`
  (`reference-code/datahike/src/datahike/migrate.clj:8,30`), `integrant.core/init`
  (`reference-code/integrant/src/integrant/core.cljc:650,472`)
