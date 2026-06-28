---
type: research
status: draft
tags: [research, agent]
---

# my.skills — a skill system for seon agents (design)

> Owner intent, 2026-06-28 (verbatim): "a context block where the agent can persist
> loaded skills and then disable them when they aren't using them to shrink context. A
> clear message after the skill is displayed that it is taking up X% of context, and if
> they are done, to remove it from their display. Should be a simple enough system to
> setup. Call it `my.skills`. Pre-write skills for common things from files and load them
> at startup. People coming from other systems have lots of existing skill files, so design
> a system compatible with current skill standards."

> Grounds every load-bearing claim in a `file:line` actually read. No implementation code
> here, no `src/` edits — this is the design + the schema.

## TL;DR

`my.skills` is **not a new subsystem** — it is a thin naming layer over machinery that
already exists:

- A **loaded skill IS a `:seon.agent.ctx/block`** (the block schema, `ctx.cljs:110`).
- **`(my.skills/load :datahike)` = `install!`** that block (`ctx.cljs:1697`);
  **`(my.skills/unload :datahike)` = `remove!`** it (`ctx.cljs:1731`). That is exactly the
  dynamic-context load/unload verb the root-os research already called for
  (D2 in `ideal-system-md-2026-06-28.md:187`).
- The skill **body** rides the **existing `file-block`** path: the DB stores only a
  `:seon.agent.ctx/file-path` to the original `SKILL.md`, re-read fresh every render
  (`ctx.cljs:219,137,154`), `;`-commented eval-safe via **`quote-lines`** (`ctx.cljs:171`).
- The **cheap always-on catalog** (every skill's name + description) is one more
  symbol-slot section block, wired exactly like the existing `:shared-instructions` block
  (`ctx.cljs:1646-1647`, `my/kb/shared.cljs:87`). This is **level-1 progressive
  disclosure** (metadata always loaded); the body is **level-2** (loaded only on `load`).
- The **token-cost footer** is **derived at render**, never stored: `tokens/estimate`
  (chars/4, `ai/tokens.cljs:27`) over the loaded block's own text, with the
  `(my.skills/unload …)` hint. Per the reactive-context doctrine (CLAUDE.md).
- **Startup seeding** of pre-authored skill files is **not a new loader** — the skill rows
  are entity-maps in the one `reconcile!` desired set (`holistic-state-management-2026-06-28.md:117-118`),
  produced by the config-loader scanning the skills dir.

A skill is the same `:my.skills/*` row whether pre-seeded or agent-authored — distinguished
by **`:seon.db/origin` provenance**, never a `:kind` stamp (`db.cljs:278`, schema.cljc:202-210).

The only genuinely-new code is: the `:my.skills/*` schema, one render fn for the loaded
body+footer (because `file-block` can't carry a footer), three thin verbs
(`load`/`unload`/`list`), one catalog render fn, and a minimal frontmatter `name`+`description`
extractor in the config-loader. Everything else is reuse.

---

## 1. The `:my.skills/*` data model

`my.skills` lives in the **`my.*` agent world** (`src/my/skills.cljs`, sibling of the
worked manual `src/my/kb.cljs`), following the `:my.kb.source/*` precedent exactly:
identity attr + scalars, the shared shapes registered once and referenced
(`my/kb.cljs:37-72`).

```clojure
;; src/my/skills.cljs — register! before any entity references these.

;; Identity. The catalog key AND the load/unload handle. A keyword munged from the
;; frontmatter `name` string (the importer ingests `name` as-authored and normalizes
;; ONLY here — never rejects a non-conformant name; portability must-have #4).
(schema/register! :my.skills/name        [:keyword {:seon.db/identity true}])

;; The frontmatter `description`, VERBATIM (incl. its "Use when …" trigger phrasing —
;; load-bearing for description-as-trigger). The always-on catalog line. Non-empty.
(schema/register! :my.skills/description  [:string {:min 1}])

;; The load-on-demand BODY for an AGENT-AUTHORED skill that has no file on disk
;; (markdown after the frontmatter). {:optional}. File-backed skills DON'T carry this —
;; they carry :seon.agent.ctx/file-path instead (below).
(schema/register! :my.skills/body         [:string {:min 1}])
```

A skill row carries **exactly one** body source — the **attribute presence is the
discriminator**, no `:kind`:

| Skill kind | Body source | Attrs the row carries |
|---|---|---|
| **File-backed** (imported `SKILL.md`) | the file on disk (lossless, live-reloaded) | `:my.skills/name`, `:my.skills/description`, **`:seon.agent.ctx/file-path`** (the EXISTING attr, `ctx.cljs:137`) |
| **Inline** (agent-authored at runtime) | a DB string | `:my.skills/name`, `:my.skills/description`, **`:my.skills/body`** |

File-backed skills store **only the path** — the body and any extra frontmatter keys
(`allowed-tools`, `version`, `author`, `license`, `platforms`, `prerequisites`,
`metadata.hermes…`) **stay in the original file, untouched**. That is the strongest
possible losslessness (portability must-have #10): the imported `SKILL.md` is never
rewritten, so import→export is the identity on it. It is also the config-loader's
"EDN = composition, markdown = content, DB stores only the path" thesis applied to skills
(`config-loader-2026-06-28.md:324`).

### Provenance — `:seon.db/origin`, not an attribute

Where a skill came from is a **transaction** fact, not a row field — exactly like every
other managed surface (`holistic-state-management-2026-06-28.md:54,111`):

- **seon-shipped skills** (`.claude/skills/datahike`, `.claude/skills/clojurescript`) seed
  under `:seon.db/origin :core-seed` (`db.cljs:278`).
- **user-provided skill files** (their `.claude/skills/*`) seed under `:config`.
- **agent-authored skills** are written under `:agent`.

Reconcile's managed scope `#{:core-seed :config}` owns the seeded skills; `:agent` skills
sit outside it and survive a reset (`holistic-state-management-2026-06-28.md:111`). **No
`:kind`/`:type` discriminator** — a row "is a skill" because it carries `:my.skills/name`,
and it "is file-backed" because it carries `:seon.agent.ctx/file-path`. That is how
datahike works: identity and shape are attribute presence + connection, never a class stamp
(schema.cljc:202-210, holistic-state-management-2026-06-28.md:26-49).

> Note: `:config` is **not yet** in the origin enum (`db.cljs:278` is
> `[:enum :user :agent :system :replay :core-seed :test-run]`). Adding it is the
> holistic-state-management doc's call (its managed scope is `#{:core-seed :config}`), not a
> my.skills fork. Until it lands, user skill files can seed under `:core-seed` with the
> seon-shipped ones; the model is unchanged.

### How a standard `SKILL.md` frontmatter maps to a row (UNCHANGED ingest)

The two live seon examples are the canonical shape (`.claude/skills/datahike/SKILL.md:1-4`,
`.claude/skills/clojurescript/SKILL.md:1-4`):

```yaml
---
name: datahike
description: "Seon database patterns. Use when writing Datalog queries, ... Use when you see seon.db ..."
---
# Datahike -- Seon Database Patterns
... body ...
```

maps to:

```clojure
{:my.skills/name           :datahike                  ; munge of frontmatter `name`
 :my.skills/description     "Seon database patterns. Use when ..."  ; verbatim
 :seon.agent.ctx/file-path ".claude/skills/datahike/SKILL.md"}      ; body+extra stay in file
```

The importer reads **only `name` + `description`** out of the frontmatter; **everything
else** (the H1 + body, the `references/` dir, any extra/nested frontmatter keys) **stays in
the file**, reachable through the path. So:

- Extra frontmatter keys (the hermes superset:
  `version`/`author`/`license`/`platforms`/`prerequisites`/`dependencies`/`metadata.hermes…`,
  `reference-code/hermes-agent/skills/*/SKILL.md`) are **tolerated by being ignored, never
  rejected** (portability must-have #2) — they are not parsed at all, they ride along in the
  file.
- The **directory form** works as-is: `<skill>/SKILL.md` + sibling `references/*.md` +
  `scripts/` — the body's relative links resolve against the skill dir because the body is
  read from its real on-disk location (portability must-have #6). A single-file `SKILL.md`
  (clojurescript) works identically.
- A non-conformant authored `name` (not `[a-z0-9-]`, predates the spec) ingests as-is;
  munging to the keyword identity is the only normalization (portability must-have #4).

**Because the body stays in the file, the pod needs NO YAML parser and NO markdown parser.**
It only has to pull two top-level scalar lines (`name:`, `description:`) from between the two
`---` delimiters — a ~10-line scanner. This is the key simplification the file-reference
buys: full YAML/markdown parsing is exactly the work we don't do.

---

## 2. Load / unload + the catalog (built ON install!/remove!)

### The verbs are thin wrappers — no new context mechanism

```clojure
(defn ^:async load   [skill-name] ...)   ; = install! the skill's body block
(defn ^:async unload [skill-name] ...)   ; = remove! that block
(defn list   [...] ...)                  ; derived: query :my.skills/* rows
```

**`(my.skills/load :datahike)`** looks up the `:my.skills/*` row by name and `install!`s a
single `:seon.agent.ctx/block` for its body:

```clojure
(seon.agent.ctx/install!
  {:seon.agent.ctx/name     :skill/datahike      ; namespaced under :skill/ so the
   :seon.agent.ctx/priority 30                    ;   catalog can detect "loaded"
   :my.skills/name          :datahike             ; the handle the render fn re-reads
   :seon.render/ai          'my.skills/skill-block})
```

- `install!` upserts by `:seon.agent.ctx/name` (`ctx.cljs:1699`), so loading the same skill
  twice **replaces, never accumulates** — idempotent.
- **Priority 30 is in the volatile band** (`> stable-priority-max` = 20, `ctx.cljs:1815`),
  so loading/unloading a skill **does not bust the cacheable static prefix** (soul →
  `:namespaces`). Only the volatile tail re-renders.
- The block carries `:my.skills/name` so the render fn can re-fetch the row each render
  (REACTIVE: if the skill row is later retracted, the body renders blank and the block
  drops, `ctx.cljs:1934`).

**`(my.skills/unload :datahike)`** = `(seon.agent.ctx/remove! :skill/datahike)`
(`ctx.cljs:1731`). The block vanishes from the agent's `:seon.agent/ctx` and its tokens are
gone next render. This is "remove it from their display" — the owner's exact ask, served by
the one remove verb.

### The loaded-body render fn — the ONE new render fn

`file-block` already reads a markdown file fresh, `quote-lines`-comments it, and drops when
absent (`ctx.cljs:201-247`) — but it **cannot append the cost footer**. So `my.skills/load`
installs a block whose `:seon.render/ai` is a small `my.skills/skill-block` render fn that
**reuses** the file-block machinery and adds the footer:

```text
;;; ┌─ skill · datahike ─────────────────────────────────────────
; # Datahike -- Seon Database Patterns
;   ... the SKILL.md body, ;-commented via quote-lines ...
;
; ── datahike skill · ~512 tok (≈3% of your ~17.6k-tok context)
;    done? (my.skills/unload :datahike) ──
;;; └─────────────────────────────────────────────────────────────
```

The render fn, given the section input `{:seon.db/db :seon.agent/id :seon.render/node}`
(`ctx.cljs:51-56`):

1. reads `:my.skills/name` off the node, pulls the row from `db`;
2. resolves the body text — **file-backed** → `read-file-text` of `:seon.agent.ctx/file-path`
   fresh (`ctx.cljs:154`, the same fresh read `file-block-ai` does); **inline** →
   `:my.skills/body`;
3. `quote-lines` the body (`ctx.cljs:171`) — eval-safe `;`-comments (the pod is CLJS
   self-host; uncommented markdown would derail the reader, /clojurescript);
4. appends the derived footer (§3);
5. returns `""` when the body is blank/absent → the block drops (reactive, no fallback).

This is the single justified new render fn: a body+footer wrapper over `quote-lines` +
`read-file-text` + `tokens/estimate`. It is not a second markdown loader — it reuses the
exact read+quote path file-block uses.

### The always-on catalog block — level-1 discovery, cheap

A single section block — wired into the default seed set exactly like `:shared-instructions`
(`ctx.cljs:1646-1647`) — renders one cheap line per skill from a query over the rows:

```clojure
{:seon.agent.ctx/name :skills-catalog :seon.agent.ctx/priority 12   ; ≤ 20 → cached prefix
 :seon.render/ai 'my.skills/catalog-block}
```

`my.skills/catalog-block` queries `[?e :my.skills/name ?n] [?e :my.skills/description ?d]`
and renders:

```text
;;; ┌─ skills ────────────────────────────────────────────────────
; Skills are loadable knowledge. Each costs nothing until you load it.
; Load a body with (my.skills/load :name); unload when done.
;
; - :datahike      ● loaded — Seon database patterns. Use when writing Datalog queries...
; - :clojurescript          — ClojureScript semantics for the Seon CLJS pod. Use when...
;;; └─────────────────────────────────────────────────────────────
```

- Priority 12 (`≤ stable-priority-max`) → the catalog sits in the **cached static prefix**.
  It is the same name+description text every render, so load/unload (which only touch
  volatile bodies) **never bust the catalog's cache slot**. Adding a skill row (boot/import)
  does change it — acceptable, that's rare.
- The `● loaded` marker is **derived**: cross-reference the agent's own
  `:seon.agent/ctx` block names against `:skill/*` (the loaded blocks). Pure projection, no
  stored "is-loaded" flag.
- REACTIVE: with no skill rows the query is empty → the section returns `""` → it drops
  (`ctx.cljs:1934`).

This is the token-economy core, identical to the three-level progressive-disclosure model
the standard defines: **level-1 metadata** (name+description of every skill, always loaded,
cheap) vs **level-2 instructions** (the SKILL.md body, loaded only while `load`ed). The
`references/*.md` files are **level-3** — naturally on-demand because the body links to them
and the agent reads them with its file tools only when a task needs them. The footer makes
level-2's cost visible and the unload explicit.

---

## 2b. Disclosure levels — "degrees of context length, agent-adjustable"

> Owner ask (2026-06-28, via U handoff): the skills block should display skills at **degrees** of
> context length, the agent dialing each skill up/down — not just binary on/off. This is the
> mechanism's render-fn detail (Core lane); spec'd here so the doc stays the agreed design.

A skill has **three disclosure levels**; the agent moves any skill between them:

| Level | What renders | Cost | How |
|---|---|---|---|
| **L0 — catalog** | one line: `:name — description` (+ `● loaded`/level marker) | ~1 line/skill, always-on | the catalog block (§2); no per-skill block installed |
| **L1 — summary** | the SKILL.md's TL;DR (its H1 + first section / first `## …` block, or the first ~15 body lines if it has no sub-headings) | a fraction of the body | `(my.skills/load :name :summary)` |
| **L2 — full body** | the whole SKILL.md body | full | `(my.skills/load :name)` (default) or `(my.skills/load :name :full)` |

**The minimal slice ships L0 ⇄ L2** — which *is* the owner's core ask already (a cheap always-on
catalog the agent expands to the full body and collapses back, with the cost footer making the
trade visible). L1 is the **fast-follow refinement**, not a v1 blocker.

### The verb arity

`load` gains an optional level arg (named positional, fully specced):

```clojure
(my.skills/load :datahike)            ; L2 full (default)
(my.skills/load :datahike :summary)   ; L1
(my.skills/load :datahike :full)      ; L2 explicit
(my.skills/unload :datahike)          ; back to L0 (remove! the block)
```

- `load` still `install!`s ONE `:seon.agent.ctx/block` by the same `:skill/<name>` identity, so
  re-loading at a different level **replaces in place** (idempotent upsert, `ctx.cljs:1699`) —
  raising/lowering a skill's level is just a re-`load`, never an accumulate.
- The installed block carries `:my.skills/name` AND a `:my.skills/level` keyword
  (`:summary`/`:full`); the `skill-block` render fn reads the level and renders the matching slice.
  `:my.skills/level` is **block-local render state on the ctx block, not a `:my.skills/*` row
  attr** — the skill row is identical regardless of how any agent currently views it (the level is
  a property of *this agent's loaded view*, exactly like priority).
- The catalog's per-skill marker reflects the level: `○` (L0, not loaded) / `◐ summary` (L1) /
  `● full` (L2) — derived from the agent's own `:skill/*` blocks + their `:my.skills/level`, no
  stored flag.

### The L1 summary source (no new parser)

The summary is a **prefix slice of the same body text** the L2 path already reads — so L1 needs no
new storage and no markdown parser:

1. read the body fresh (file-backed → `read-file-text`; inline → `:my.skills/body`) — the L2 read;
2. take the H1 + everything up to the **second** `^## ` heading (the skill's lead-in / TL;DR);
   if there is no second `##`, take the first ~15 non-blank body lines;
3. `quote-lines` it + the footer (which notes "summary — `(load :name)` for the full body").

This reuses the L2 read+quote+footer path entirely; L1 is one `take-until-second-h2` slice over
the already-read text. House skills should therefore **lead with a tight TL;DR section** so L1 is
useful — a SKILL.md authoring convention for U's corpus (worth a line in the corpus skills'
front-matter discipline), not a mechanism requirement.

## 3. The DERIVED token-cost footer (never stored)

Per the reactive-context doctrine (CLAUDE.md "derived by default"; never store a
renderable), the footer is computed **at render time** from the block's own text and the
turn's prompt size — no datom.

```clojure
(let [own-tok (seon.ai.tokens/estimate body-text)]   ; chars/4, ai/tokens.cljs:27
  (str "; ── " skill-name " skill · ~" own-tok " tok"
       (when total-tok (str " (≈" (pct own-tok total-tok) "% of your ~"
                            (human total-tok) "-tok context)"))
       "\n;    done? (my.skills/unload :" skill-name ") ──"))
```

**The own-cost `~N tok`** is exact, cheap, and has no chicken-and-egg: `tokens/estimate`
over the block's own rendered body (`ai/tokens.cljs:27`, the canonical
`:seon.render/token-estimate` convention, `inspect.cljs:49,112`). This is the load-bearing
number — it is what motivates unloading.

**The `≈X% of context` denominator** is the whole assembled prompt's token estimate. That
total is **not available at block-render time without recursion** (a skill block can't
render the whole prompt — it is *in* the prompt). The clean, non-recursive, no-new-storage
source is the **per-turn fiber-local stash** that `:relevant-source` already uses
(`turn.cljs:145`, `ctx/relevant.cljs:10`: "prefetched in run-turn!, read by a synchronous
section"):

- `render-prompt` computes the full prompt string once per turn anyway (`turn.cljs:370`).
  Stash its `tokens/estimate` in the same per-turn stash.
- The footer reads `last-total-tok` from the stash as the denominator. It is **one render
  stale** (off by the footer's own small delta) — negligible for a `≈X%` figure, and the
  stash is a **volatile runtime artifact, not a datom** (explicitly allowed by
  reactive-context: "genuinely stateful runtime artifacts").
- **Graceful degradation:** when the stash is empty (first render), the footer shows
  **just `~N tok`** and omits the `%`. Nothing breaks; the percentage appears once a total
  is known.

This adds **one `put` + one `read` over an existing stash**, not a new mechanism.

> Alternative denominator (future): once a model **context-window** constant exists
> (CLAUDE.md notes `::max-tokens` is the *output* cap and a context-window limit is "a
> separate concern" not yet present), `% = own-tok / window` is a constant-denominator,
> non-recursive, arguably more useful figure ("X% of your context window"). Lead with
> prompt-total via the stash; switch the denominator if/when a window constant lands. The
> footer text is identical either way — only the denominator source changes.

---

## 4. Startup seeding of pre-authored skill files

Pre-authored skills are **not a bespoke loader** — they are entity-maps in the **one
`reconcile!` desired set** (`holistic-state-management-2026-06-28.md:117-118`: "the EDN/markdown
loader produces an OVERRIDE that merges over the code-default desired set … not a separate
loader — it is the input that shapes the desired set"). The flow:

1. **At boot**, the config-loader (`config-loader-2026-06-28.md`) scans the skills dir(s) —
   the standard Claude Code locations `.claude/skills/<name>/SKILL.md` (and a seon config
   skills dir) — the same standard layout other tools use, so a user drops their existing
   skill folders in and they appear (portability must-haves #1, #6).
2. For each `SKILL.md`, the loader pulls `name` + `description` from the frontmatter (the
   ~10-line scanner of §1) and emits one **`:my.skills/*` entity-map** carrying
   `:seon.agent.ctx/file-path` = that SKILL.md path.
3. Those maps join the **default desired set** that `reconcile!` seeds, tagged
   `:core-seed` (seon-shipped) / `:config` (user). `reconcile!` upserts each by its
   `:my.skills/name` identity (`holistic-state-management-2026-06-28.md:70`) — so re-running at
   boot is idempotent, and **reset / removal of a skill file retracts its row** for free (the
   same retract-diff that fixes gotcha #33, `holistic-state-management-2026-06-28.md:119`).
4. The **catalog block** and the **`load`/`unload` verbs** are themselves part of the core
   wiring (the catalog is one symbol-slot block in the default seed set, §2). Nothing per-skill
   is special; adding a skill is adding a row + dropping a `SKILL.md` in the dir.

**seon ships two pre-written skills already** — `.claude/skills/datahike/SKILL.md` and
`.claude/skills/clojurescript/SKILL.md` — which become the first two seeded rows
(origin `:core-seed`). "Pre-write skills for common things from files and load them at
startup" is satisfied by pointing the loader at the existing skills dir; the owner's
existing two files are the seed payload, no new content needed to prove the path.

Backup/restore and reset fall out of reconcile (the holistic-state table,
`holistic-state-management-2026-06-28.md:113-121`): the skill rows are managed datoms; export is
the read-projection back to the desired-set EDN; for file-backed skills the body never needs
projecting (it's already a file).

---

## 5. Reuses, does NOT fork

Every piece maps to an existing seon mechanism. The whole design is naming + four small
fns + one schema over machinery that is already in the tree.

| my.skills piece | Reuses (existing mechanism) | file:line |
|---|---|---|
| a loaded skill body IS a context block | `:seon.agent.ctx/block` schema | `ctx.cljs:110` |
| `load` = install the body block (idempotent upsert) | `seon.agent.ctx/install!` | `ctx.cljs:1697` |
| `unload` = remove the body block | `seon.agent.ctx/remove!` | `ctx.cljs:1731` |
| body read fresh from `SKILL.md`, reactive (absent → drops) | `file-block` + `:seon.agent.ctx/file-path` + `read-file-text` | `ctx.cljs:219,137,154` |
| body rendered eval-safe (`;`-commented) | `quote-lines` | `ctx.cljs:171` |
| derived token cost (chars/4) | `seon.ai.tokens/estimate` | `ai/tokens.cljs:27` |
| `:seon.render/token-estimate` convention | inspect's per-section estimate | `inspect.cljs:49,112` |
| catalog = a symbol-slot section block in the seed set | `:shared-instructions` precedent | `ctx.cljs:1646-1647`, `my/kb/shared.cljs:87` |
| seed-copy the catalog into a fresh agent | `seed-default-ctx!` | `ctx.cljs:1756` |
| `:my.skills/*` rows in the `my.*` world | `:my.kb.source/*` precedent (identity + scalars + shared shapes) | `my/kb.cljs:37-72` |
| pre-seed skill files at boot | config-loader → `reconcile!` desired set | `config-loader-2026-06-28.md`, `holistic-state-management-2026-06-28.md:117-118` |
| provenance (seeded vs authored), reset-safe | `:seon.db/origin` enum on TX, managed scope `#{:core-seed :config}` | `db.cljs:278`, `holistic-state-management-2026-06-28.md:54,111` |
| NO `:kind` — attribute presence is the discriminator | file-path-vs-body presence | `schema.cljc:202-210`, `holistic-state-management-2026-06-28.md:26-49` |
| `%`-of-context denominator | per-turn fiber stash (prefetch in run-turn!, read in sync section) | `turn.cljs:145`, `ctx/relevant.cljs:10` |
| the load/unload verb itself (was already designed) | D2 dynamic-context load/unload | `ideal-system-md-2026-06-28.md:187` |

### What is genuinely new (and why an existing mechanism can't carry it)

1. **The `:my.skills/*` schema** (3 attrs). New data, but it is just the `:my.kb.source/*`
   shape with different names — register-once, reference-everywhere. Not a new mechanism.
2. **`my.skills/skill-block`** — one render fn (body + footer). New because `file-block`
   renders a body but **cannot append the cost footer**; a footer-bearing wrapper is the
   minimal addition, and it reuses `read-file-text`/`quote-lines`/`tokens/estimate` — it is
   not a second markdown loader.
3. **`load` / `unload` / `list`** — thin wrappers over `install!` / `remove!` / a row query.
   They exist only to give the agent a named, ergonomic verb (the D2 ask); the work is the
   existing context mechanism.
4. **`my.skills/catalog-block`** — one render fn. A pure derived section (level-1 discovery),
   the same shape as every other `seon.agent.ctx.<name>` section block.
5. **A `name`+`description` frontmatter scanner** in the config-loader (~10 lines). New, but
   deliberately tiny: because the body stays in the file, we **avoid** a full YAML/markdown
   parser entirely.

Nothing here is a parallel context system, a second markdown path, a stored token count, an
entity `:kind`, or a bespoke per-skill loader. The owner's "simple enough to set up" is met:
seon already had the substrate; my.skills is the label on it.

---

## 6. Anti-fork guardrails (what this design refuses)

1. **No parallel "skill runtime."** A loaded skill is a `:seon.agent.ctx/block` datom like
   any other; `load`/`unload` are `install!`/`remove!`. The agent's loaded set persists in
   its own `:seon.agent/ctx` (the owner's "persist loaded skills") — no separate registry.
2. **No second markdown loader.** Bodies ride `file-block`'s read+quote path (`ctx.cljs:219`).
3. **No stored token count or `%`.** Both are render-time projections (`tokens/estimate`),
   self-healing — unload and the cost is simply gone next render.
4. **No `:kind`/`:type`.** A skill is its `:my.skills/*` attrs; file-backed-vs-inline is
   attribute presence; seeded-vs-authored is `:seon.db/origin`.
5. **No bespoke skill seeder.** Skill rows are entity-maps in the one `reconcile!` desired
   set; backup/restore/reset come for free.
6. **No frontmatter rejection.** Only `name`+`description` are read; every other key
   (the hermes superset) rides along in the unrewritten file — tolerated by being ignored.
7. **No body copied into the DB for file-backed skills.** The DB stores the path; the file is
   the lossless source (import→export identity).

---

## 7. Open questions

- **OQ1 — skill dir(s) + `:config` origin.** The loader scans `.claude/skills/` (standard) +
  a seon config dir. User skills want origin `:config`, which the origin enum
  (`db.cljs:278`) does not yet carry — adding it is the holistic-state doc's managed-scope
  call (`#{:core-seed :config}`). Until then, seed user skills under `:core-seed`.
- **OQ2 — load priority band.** Loaded bodies at priority 30 (volatile, `> 20`) keep the
  cached prefix intact. If an agent loads many skills, they stack in the volatile tail in
  name order; a per-load priority bump (most-recently-loaded last) is a trivial refinement,
  not needed for v1.
- **OQ3 — multi-line / unquoted `description`.** The two standard examples use a single
  quoted line. The scanner should take `description:` as the rest of its line (strip quotes);
  if a real file wraps it across lines, take until the next top-level key. Keep the scanner
  minimal; full YAML is explicitly out of scope (body stays in the file).
- **OQ4 — `references/*.md` (level-3).** The body links to siblings; the agent reads them
  with its file tools on demand. Do we want a `(my.skills/load :datahike/querying)` sub-verb
  to install a specific reference file as its own block? Cheap to add later (it is another
  `file-block` install), out of scope for v1 — the agent can already read the file.
- **OQ5 — `%` denominator source.** Recommended: prompt-total via the per-turn stash
  (one render stale, no new mechanism). Alternative: a model context-window constant once one
  exists. Both render the same footer; pick when the window constant question is settled.

---

## Cross-references

- [[docs/prds/agent-fsm/research/config-loader-2026-06-28.md]] — file→tx-data loader; blocks
  ride `file-block`; symbols via `lookup-value`; origin `:core-seed`/`:config`.
- [[docs/prds/agent-fsm/holistic-state-management-2026-06-28.md]] — `reconcile!` over a desired
  set; no `:kind`; provenance-scoped managed slice; skills are more declarative rows.
- [[docs/prds/agent-fsm/research/ideal-system-md-2026-06-28.md]] — D2 dynamic-context
  load/unload verb (`:187`); namespaces is the real bloat lever (`:32`).
- Source: `seon.agent.ctx/block` schema (`ctx.cljs:110`), `quote-lines` (`ctx.cljs:171`),
  `file-block` (`ctx.cljs:219`), `install!` (`ctx.cljs:1697`), `remove!` (`ctx.cljs:1731`),
  `default-seed-blocks` (`ctx.cljs:1599`), `stable-priority-max` (`ctx.cljs:1815`);
  `seon.ai.tokens/estimate` (`ai/tokens.cljs:27`); `my.kb` row model (`my/kb.cljs:37-72`),
  `my.kb.shared/instructions-block` (`my/kb/shared.cljs:87`); `:seon.db/origin`
  (`db.cljs:278`); per-turn stash (`turn.cljs:145`, `ctx/relevant.cljs:10`).
- Standard: `.claude/skills/datahike/SKILL.md:1-4` + `references/*.md`,
  `.claude/skills/clojurescript/SKILL.md:1-4`,
  `reference-code/hermes-agent/skills/*/SKILL.md` (extended frontmatter superset),
  `reference-code/claude-agent-sdk-typescript/CHANGELOG.md:210-211` (SDK `skills` field).
