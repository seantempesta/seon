---
type: research
status: active
tags: [research, agent]
---

# Vocabulary audit — divergent/parallel naming across the seon repo (2026-07-11)

Yardstick: root `CLAUDE.md` §"Vocabulary — use the code's names, never coin
new ones" (owner, 2026-07-11). Every concept must be referred to by its
REAL, REPL-discoverable name (namespace / block / attribute / config value).
Synonyms, metaphors, and umbrella nouns are defects.

Scope of this audit: **read + report only, nothing changed.** Counts are
grep tallies (representative, not exhaustive); "verbatim" excluded from all
`verb` counts. Areas: (a) living docs, (b) agent-facing src strings
(docstrings / teaching prose that RENDERS to agents — the poison surface),
(c) code identifiers (rename-units), (d) config files, (e) tests.

## TL;DR

| term cluster | canonical name | must-fix (living docs + agent-facing strings) | rename-unit (code identifiers) | exempt / note only |
|---|---|---|---|---|
| "verb(s)" | **functions / schemas / tests** | ~110 living-doc hits + dozens of agent-facing strings (`ctx.cljs`, `menu.cljs`, `toolkit.md` is 100% verb-framed) | **11 symbols** across `eval.cljs` + `menu.cljs`, plus the `:recent-verbs` **block name** (typeahead-lane) | ~1106 hits under `docs/prds/**` research/history — light note only |
| "rung(s)" / "the ladder" | **capability milestones** (`repl`/`namespaces`/`plan`/`db`/`warnings`/`live-tile`/`subagents`/`soul`) | 124 doc hits incl. the doc filename `minimal-context-ladder.md`; ~13 src comments; config `.edn` comments | none (milestone names already canonical) | dated markers ("rung-0 verdict, 2026-07-10") in code comments — history, light note |
| "Mode A / Mode B" | **`:batch` / `:stream`** (`:seon.config/repl-mode`) | 20 doc + 16 src hits, all comments/docstrings (most pair the coinage WITH the canonical value) | none | test.edn comment |
| "the store" / "memory" (=DB) | **the `db`** (`seon.db`) | ~10 agent-facing docstrings (`namespaces.cljs`, `findings.cljs`, `inventory.cljs`, `render/system.cljs`, `ui/header.cljs`, `web/debug.cljs`, `live_tile.cljs`) | none | `:memory` conn, konserve store, `data/clusters/*/store` path = LEGIT, not flagged |
| "canvas" vs "live-tile" vs "tile" | **`canvas` = `#world-canvas` focal slot; `live-tile` = the agent-side block; other html = tiles** | seon UI usage is mostly CONSISTENT | none | diffusion's `canvas-text` (`worker_eval`, `worker_validator`, `diffusion/*`) is an unrelated homonym → OWNER note |
| "collaboration"/"identity"/"attention" (block names) | **`subagents` / `soul` / `warnings`** | docs only: collaboration 23, attention 144, identity-as-block a few | `identity-file-blocks` (`config.cljs`) is a soul+agents umbrella — minor | `:db.unique/identity`, `clojure.core/identity`, "identity file" = LEGIT |
| "world" / "environment" (=cluster) | **cluster** | scattered ("root world", "fresh world", "ROOT world only" in agent-facing docstrings) | none | `/world` UI page + `seon.ui.world` = EXEMPT legacy; OS/lexical `environment` = LEGIT → OWNER note on "root world" |
| "kind"/"type" taxonomies | **attributes + connections** | settled — no live offenders found in src | none | — |
| toolkit / cards / envelope / harness / oracle / drive | established terms | — | — | verify-consistent: all used consistently; `toolkit.md` subtitle re-introduces "verb" |

## Per-cluster detail

### 1. "verb(s)" → functions / schemas / tests (LARGEST)

The owner rule is explicit: "functions, schemas, tests | never 'verbs' |
`my.plan/done!` is a function." This coinage is pervasive and sits on the
poison surface (agent-facing prose).

**Living docs (MUST-FIX):**

- `docs/seon/architecture/toolkit.md:7` — `# Agent toolkit — the my.* verb
  catalog over a protected seon.* floor`. The entire doc is verb-framed
  ("verb catalog", "control verbs", "lifecycle verbs", "the verb's render
  fn") — 26 hits. This is the idealized-system doc; it needs a systematic
  rewrite to "function".
- `docs/seon/architecture/agent-runtime.md` (15), `observability.md` (8),
  `data-model.md` (8), `architecture.md` (5), `laws.md` (4).
- `docs/conventions.md` (7), `docs/seon/components` (10), `docs/seon/vision`
  (8).
- CLAUDE.md family: root `CLAUDE.md` (7 — some are the canonical-table rows
  themselves, i.e. legit), `docs/prds/agent-ctx/CLAUDE.md` (5),
  `ORCHESTRATOR.md` (1).

**Agent-facing src strings (MUST-FIX, highest priority — these render):**

- `src/seon/agent/ctx.cljs:1440` — `"; my.tile) and your core verbs (plan /
  message / lifecycle). Everything\n"`
- `src/seon/agent/ctx.cljs:1534` — `"; explicit verbs — all plain Clojure…"`
- `src/seon/agent/ctx.cljs:1597` — `"; - WHEN YOU ARE DONE, say so with a
  verb. (complete \"<the answer>\")\n"`
- `src/seon/agent/ctx.cljs:1393` — `"; ^:async verb (db/transact!, plan/*)…"`
- `src/seon/agent/ctx/menu.cljs:421/425/429` — `"; recent verbs …"`,
  `"; toolkit verbs …"`, `"; toolkit — more verbs from your required
  namespaces:"`
- `src/seon/client.cljs:2168` — `";; say hello to your human via the
  message/user verb\n"` (seed code the agent reads)
- Docstrings (render in namespace cards): `agent.cljs:2` ("the agent-facing
  verbs"), `agent.cljs:574` ("the capability-gated spawn lifecycle verb"),
  `agent/lifecycle.cljs:2` ("the agent's run-lifecycle verbs"),
  `agent/internal.cljs:2/12`, `agent/web.cljs:330` ("this verb extracts
  text"), `eval.cljs:1157/1477` ("home-ns verb").

**Config (MUST-FIX, comments):** `config/system.edn:133/158/159/227`,
`config/acme.edn:13` all say "verb"/"verb surface".

**EXEMPT (light note):** ~1106 hits under `docs/prds/**` are dated
research/history — do not sweep; they record the era's language.

### 2. "rung(s)" / "the ladder" → capability milestones

The milestone names are already canonical (`repl`/`namespaces`/`plan`/`db`/
`warnings`/`live-tile`/`subagents`/`soul`). "rung"/"ladder" is the metaphor
layered over them.

- **MUST-FIX (living guidance):** the doc filename itself —
  `docs/prds/agent-ctx/minimal-context-ladder.md` (11 "rung" hits), which
  the root CLAUDE.md table cites as the milestone definition. The doc name +
  body embody the retired coinage.
- **src comments (history-flavored, light-fix):** `derive.cljs:137`,
  `client.cljs:2882`, `eval.cljs:2076`, `agent/loop.cljs:171`,
  `agent/turn.cljs:407/427/634`, `agent/run.cljs:133/311`,
  `agent/ctx/transcript.cljs:566` — all dated markers like "rung-0 verdict,
  2026-07-10".
- **config comments:** `config/minimal.edn:2` ("measurement-ladder rung-0
  world"), `minimal-plan.edn:1` ("The rung-2 (planning) minimal context"),
  `minimal-plan-stream.edn:1`, `minimal-nocards.edn:3` ("cross-model ladder
  variant").

### 3. "Mode A / Mode B" → `:batch` / `:stream`

Leaked chat shorthand. No code identifiers — all comments/docstrings, and
most already pair the coinage with the canonical value (so the fix is
deleting the "Mode A/B" half):

- `src/seon/agent/turn.cljs:102/105/378/407/428` — `"Mode A (:batch)…"`,
  `"Mode B :stream single-form close"`.
- `src/seon/agent/ctx.cljs:625/736/823` — `"Mode A [[strip-result-claims]]"`.
- `src/seon/repl/internal.cljc:866` — `"The cheap STREAMING gate (Mode B,
  :stream)"`.
- `src/seon/agent/run.cljs:132` — `"a Mode A turn"`.
- `config/test.edn:12` — `"The suite is DETERMINISTIC Mode A"`;
  `config/minimal-stream.edn:1` — "the Mode B variant".

### 4. "the store" / "memory" (for the DB) → `db`

CAREFUL cluster — I flagged ONLY uses meaning the datahike DB / agent
memory; `:memory` (the datahike backend), konserve, and the
`data/clusters/*/store` path are legit and NOT flagged.

**Agent-facing docstrings that say "the store" meaning the DB (MUST-FIX):**

- `src/seon/agent/ctx/namespaces.cljs:298/311` — `"More namespaces exist in
  the store than render"` (renders to the agent).
- `src/seon/agent/ctx/findings.cljs:23/180` — `"when the store holds no
  user-domain rows"`.
- `src/seon/agent/ctx/inventory.cljs:163` — `"when the store … the composer
  drops the section"`.
- `src/seon/render/system.cljs:71` — `"Every agent id in the store"`.
- `src/seon/ui/header.cljs:151` — `"The store + embeddings cluster: datom
  count"`.
- `src/seon/web/debug.cljs:886` — `"every row in the store — the whole
  system is data"`.
- `src/seon/render/live_tile.cljs:440` — `"The human's name, when the store
  carries one."`
- `src/seon/eval.cljs:4530` — string `" from the store: "`.

Note: because "the store" ALSO legitimately names the durable wire-server
datahike store, some of these are arguably correct-in-context. See OWNER
DECISION D2.

### 5. canvas / live-tile / tile

seon's own UI usage is **consistent** with the canonical distinction:
`docs/seon/architecture/ui.md:137-141` correctly documents canvas =
`#world-canvas` focal slot, `:canvas` "is just a block name like any
other", live-tile = `:seon.render.live-tile/content`. `config.cljs:12/293`,
`client.cljs:151`, `render.cljs:794/1161` align. No fix needed inside seon
UI.

The homonym: **diffusion** overloads "canvas" to mean the code buffer being
diffused — `worker_eval.cljs:8` (`"is the canvas structurally
well-formed?"`), `worker_validator.cljs`, `repl/internal.cljc`,
`diffusion/oracle.cljs`, `diffusion/retrieval.cljs`, plus `canvas-text` in
the Python worker. Per the rule ("don't widen 'canvas'; diffusion's
canvas-text is unrelated") the two coexist, but the collision is a smell →
OWNER DECISION D1.

### 6. collaboration / identity / attention (block/milestone names)

- **collaboration** → `subagents`: src=0, docs=23. Docs-only cleanup.
- **attention** → `warnings`: src=1, docs=144 (many "attention" are ordinary
  English; the block-name misuse is the subset). Docs-only, mostly.
- **identity** → `soul` (as a block name): src "identity" is 302 but almost
  all are `:db.unique/identity`, `clojure.core/identity`, "upsert by
  identity", "model identity", or "identity file" (SOUL.md/agents file,
  which is a reasonable descriptor). The one umbrella worth noting:
  `config.cljs:1302 identity-file-blocks` bundles soul + agents files under
  "identity" — minor. No urgent fix.

### 7. world / environment (for cluster)

- `/world` UI page + `seon.ui.world` = EXEMPT legacy (settled).
- `environment` in src is overwhelmingly OS env vars / lexical environment
  (`render/sci.cljs`, `ai.cljs`, `web/brand.cljs`) = LEGIT.
- The residue: "root world" / "fresh world" as a stand-in for the root
  cluster/DB-at-t, some in agent-facing docstrings —
  `agent/ctx/subagents.cljs:187` (`"ROOT world only"`),
  `agent/ctx/warnings.cljs:72/103` (`"ROOT world only"`),
  `client.cljs:433/485/535` ("a fresh world"), `agent/inspect.cljs:236/276`
  ("txs the world advanced"). "ROOT world only" is an established idiom in
  these sections. → OWNER DECISION D3.

### 8. Established terms — verified consistent

- **toolkit** — grounded (`docs/seon/architecture/toolkit.md`, the my.*
  surface). Used consistently; its only sin is re-introducing "verb" in the
  subtitle (see cluster 1).
- **cards** (compact namespace cards) — consistent.
- **envelope** (errors-as-values / items) — consistent, established (src 370).
- **harness** (acme / inspect-ai testbed), **oracle** (LLM judge/consult),
  **drive** (live agent run vs a stimulus) — eval-lane terms, used
  consistently, no competing synonym found. No action.

## RENAME-UNIT list (code identifiers — coordinated renames)

Canonical target for all: drop "verb" for "fn"/"function" (or the concrete
concept). Listed with blast radius.

**`src/seon/eval.cljs` (context/eval lane — mostly file-local):**

| symbol | def site | blast radius |
|---|---|---|
| `repl-verb-heads` (def) | `eval.cljs:3417` | file-local refs in eval.cljs |
| `repl-verb-form` (defn) | `eval.cljs:3422` | file-local |
| `dispatch-repl-verb!` (defn ^:async) | `eval.cljs:4471` | ~9 refs in eval.cljs + 1 docstring cross-ref `agent/ctx/namespaces.cljs:297` |
| `record-verb-result!` (defn-, private) | `eval.cljs:4440` | ~14 call sites, ALL file-local in eval.cljs |

Nuance for owner: these dispatch Clojure REPL forms (`in-ns`/`alias`/
`ns-unmap`/`redefine`), not `my.*` functions — so the natural rename is
`repl-form`/`dispatch-repl-form!`/`record-form-result!` rather than
"function". Confirm the target noun.

**`src/seon/agent/ctx/menu.cljs` (TYPEAHEAD LANE — coordinate, do NOT
unilaterally rename; MEMORY flags this file + `:recent-verbs` as
typeahead-owned):**

| symbol | def site | visibility |
|---|---|---|
| `ranked-verbs` | `menu.cljs:226` | private |
| `capped-verbs` | `menu.cljs:247` | private |
| `toolkit-verbs` | `menu.cljs:362` | private |
| `combined-verbs` | `menu.cljs:399` | private |
| `verb-line` | `menu.cljs:431` | private |
| `recent-verbs-block` | `menu.cljs:444` | **public** (section fn) |
| `verb-offers` | `menu.cljs:509` | **public** (typeahead offers) |

**`:recent-verbs` — the registered BLOCK NAME (biggest coordinated rename):**

- Registered: `config.cljs:224` (`{:seon.agent.ctx/name :recent-verbs …}`).
- Referenced: `menu.cljs:8/149/445/463/488/510`, `namespaces.cljs:575`.
- **Gotcha:** `menu.cljs:463` notes the block is "seed-copied into live
  agents'" context, so a rename touches existing cluster stores → requires a
  `bin/seon cluster reset` (or a migration) to avoid stale block names in
  persisted agent contexts. This is the single highest-coordination item.
  Candidate target: `:recent-fns`.

## OWNER DECISIONS (genuine ambiguities)

- **D1 — diffusion "canvas".** The diffusion subsystem calls the code buffer
  it diffuses a "canvas" (`canvas-text`, "is the canvas structurally
  well-formed?"). This collides with the UI `canvas` (the `#world-canvas`
  focal slot). The rule says don't widen "canvas" but treats diffusion's as
  "unrelated". Keep the homonym, or rename the diffusion buffer (e.g.
  `code-buffer` / `draft`)? Recommend: rename in diffusion to kill the
  collision, since diffusion is a self-contained subsystem and the fix is
  cheap there.

- **D2 — "the store" for the durable DB.** "the store" legitimately names
  the wire-server datahike store AND is the retired coinage for the `db`.
  When an agent-facing docstring says "rows in the store", is that the
  durable-store name (keep) or the `db` synonym (fix to "the db")? Recommend:
  in agent-facing prose, always say "the db" (agents call `db/query`); keep
  "store" only for the physical `data/clusters/*/store` / konserve / blob
  store.

- **D3 — "root world".** "ROOT world only" is an entrenched idiom in the
  subagents/warnings section docstrings meaning "the root cluster's DB".
  Rename to "root cluster only" per the settled world→cluster ruling, or
  grandfather it alongside the exempt `/world` page? Recommend: rename in the
  agent-facing docstrings ("root cluster only"); it is prose, not the exempt
  UI-page name.

- **D4 — dispatch-repl-verb noun.** (see RENAME-UNIT nuance) — these handle
  REPL special forms, not `my.*` functions. Confirm `repl-form` as the
  target rather than forcing "function".
