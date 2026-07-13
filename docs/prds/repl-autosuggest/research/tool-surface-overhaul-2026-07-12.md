---
type: research
status: active
tags: [research, agent]
---

# Tool-surface overhaul — sweep-proven docstrings + schema projections, applied to SOURCE

**Date:** 2026-07-12 (owner-cleared unit: the context freeze is LIFTED for
the fn surface — names, docstrings, schemas) · **Commits (main tree,
`codex/runtime-reliability-refactor`):** `2de88e0f` (FIX8 docstrings) ·
`608b2331` (broader line-1 pass, 16 files) · `6b75705c` (schema
sharpening + `::root?`→`::root`) · `f6cd9761` (message/user amendment)
· plus 4 fixes that rode in the runtime-reliability lane's `e2c3170e`
sweep-commit (listen!/listen-async!/managed-identities line-1s,
`normalize-entity-ref-keys`) · **Verification:** isolated worktree
`/Users/sean/src/seon-fn-surface` = pin `93c8d8ad` + toolkit commits
(`e2e4ce92`+`b255e23c` as `5258e166`/`299b37f7`) + the 4 commits above,
own acme cluster (pod 7986 / wire 7987, fresh store) · **Oracle:** the
KT2b/surface-sweep machinery re-run against the LIVE re-dumped fn index
(`seon_needle.surface_sweep`, stock needle 26M + Qwen2.5-Coder-1.5B) ·
**Spend:** $0 (all local MLX).

## TL;DR

- **Needle name accuracy @8-tool menus: 0.283 → 0.372 (+0.090)** on the
  live new surface — better than the sweep's doc-action override
  predicted (+0.041) and within noise of its full translation-layer
  stack (0.386). The 0.00 tier shrank **21 → 14 fns**; false-suggestion
  0.25 → 0.208. **Qwen: FIX8 7/24 → 13/24** (the doc-action prediction
  was 14/24); headline 0.421 → 0.428.
- **Control:** after the runs, the SAME machinery re-run against a
  re-dumped near-baseline index reproduced the old baselines
  byte-for-byte (needle 0.2828/0.9724, qwen 0.4207/1.0) — the deltas
  attribute to the surface change, not machinery drift.
- **~50 docstring line-1s rewritten in source** (all glyph-free ≤72-char
  capability sentences; old mechanism wording demoted to bodies, never
  deleted); **5 schemas sharpened** (`::query-form`, `::entity-ref`,
  `::pull-pattern`, `::thunk`, `::time-point`) + the
  `:my.plan/root?`→`:my.plan/root` request-key fix; **zero fn renames**
  (the sweep's evidence: name surgery is the weakest lever and reverses
  on nothing — candidates deferred below with evidence).
- **Suite GREEN in the isolated worktree: 1236 tests, 5649 assertions,
  0 failures, 0 errors** (`bin/test-cljs` at pin + toolkit + these
  commits). **Live proofs:** the root agent's rendered debug context
  (`/agent/root/debug`, the byte-identical prompt path) shows the new
  line-1s and the `::query-form` spec projection in its compact cards;
  the sharpened shapes are real datoms in the booted store
  (`:seon.db/query-form → [:or [:vector :any] :map :string]`, dumped at
  basis-t 536870929).
- **Residuals are the ones the sweep predicted:** `db/query` abstains in
  every arm on both models (aggregation-ask shape — the `my.kb/recall`
  contract is the fix lane, not this card); `transact!`/`register!`/
  `put!` stay 0/3 on needle (the sweep's own doc-action arm also left
  them at 0 — "their failure is not (only) the docstring").

## 1. Docstring rewrites (old → new line-1), applied to source

Rules applied: complete standalone sentence, ≤72 chars, terminal
punctuation, glyph-free (no em-dash/arrow), capability vocabulary;
mechanism wording moved into the body (nothing deleted). For reads, the
measured finding overrode the noun-phrase convention: question-shaped
asks stop abstaining when line-1 is an action ("`my.plan/next` 0→3 on
BOTH models from the docstring alone") — `docs/conventions.md` still
says noun-phrase for pure queries; flagged for the owner below.

### The FIX8 (sweep-measured texts, adjusted only where the fn's truth demanded)

| fn | old line-1 | new line-1 |
|---|---|---|
| `seon.db/transact!` | Commit tx-data — forwarded to the JVM writer, returns an envelope. | Save records to the database, persisting new facts durably. |
| `seon.db/query` | Run a Datalog query, returning the result set. | Ask the database a question: find, count, or sum stored facts. |
| `seon.schema/register!` | Register a single schema in the global registry. | Define a new attribute so facts using it can be saved and queried. |
| `my.plan/step!` | Mint one OPEN plan step (agent = caller; blank title refused). | Add a new step to the plan. |
| `my.plan/done!` | Mark a step done; may unblock its dependents next turn. | Record that a plan step is finished and complete. |
| `my.plan/next` | Your focus queue: READY leaves (open, unblocked), oldest first. | Get the next plan steps to work on. |
| `my.blob/put!` | Persist `:my.blob/content` content-addressed; record its projection. | Save a long text durably; read it back page by page later. |
| `seon.db/entity` | Look up an entity by eid or lookup-ref, as a plain map (sync). | Fetch one stored record by its id, with all its fields. |

Adjustments from the sweep's literal texts: `register!` "field" →
"attribute" (the code's real name, and the case bank itself says
"attribute"); `transact!`/`put!` em-dashes replaced (glyph-free rule).

### The broader pass (implementation-vocabulary / truncated / glyphed line-1s)

my.plan: `active!` "Take a step up: mark it `:active` — your rendered
position anchor." → "Mark a plan step `:active`, the one you are working
on now." · `drop!` "Retract a step AND its whole subtree." → "Delete a
step and its whole subtree from the plan." (the done!↔drop! finish/
retract bleed, fixed by wording not rename) · `move!` → "Move a step
under a new parent step." · `plan!` → "Create a whole plan at once:
goal, pace, nested steps, and deps." · `needs!` → "Add dependency edges;
a step is ready only when its needs are done." · `tree` → "Get the whole
plan as nested EDN, the structural read for re-planning." · `document` →
"Get your open plan as one document to edit and `reconcile!`." ·
`status` → "Check one step's status: done, blocked, ready, and
progress." · `list-open` → "List unfinished steps (open, active,
blocked), oldest first."

my.blob: `get` → "Fetch a stored text's full content by hash, for use in
code." · `text` → "Read a stored blob page by page, as a bounded line
window." · `stat` → "Check whether a blob exists, and its size, without
reading it."

my.data: `rows` → "Fetch every entity carrying `attr` as self-describing
maps." · `sum-by` → "Total a numeric `key` across the given item maps."
· `max-by` → "Find the item map whose `key` is largest; the row, not the
value." · `group-sum` → "Sum a numeric field per group, one total row
per group value."

my.kb (the worked manual — idiom teaching moved to bodies):
`remember-sources!` → "Store the sample sources, linking authors and
findings by ref." · `retitle-source!` → "Rename one source's title in
place, by its id." · `clear-rating!` → "Remove one source's rating, an
explicit retraction." · `replace-topics!` → "Replace a source's whole
topics set with a new one." · `forget-source!` → "Delete one source and
all its findings, by id." · `titles` → "List every stored source
title." · `title+rating` → "List every source's title and rating as
`[title rating]` pairs." · `titles-by-author` → "List the titles of
every source by the named author." · `source-stats` → "Summarize stored
sources: count, rating total, and topic tally." · `source-detail` (was
"Pull by LOOKUP-REF.") → "Fetch one source with its author and findings,
by id." · `source-entity` → "Fetch one source as a plain map, nil when
the id is unknown." · `build-kb-example!` → "Run the end-to-end example:
register, seed rows, run [[source-stats]]." · `recall` (peer's fresh fn)
em-dash → colon only.

seon.agent.fs: `read-file` (was "Read a file (sync).") → "Read a file's
text, whole or as a paged line window." · `list-dir` → "List the
filenames in one directory, without recursion." · `stat` → "Check a
path's type, size, and mtime without reading it." (worktree resolution;
main-tree body has no size) · `file-exists?` → "Check whether a path
exists; false on any error." · `replace!`/`insert!` de-glyphed.
`write-file` untouched (1.00 tier — its line-1 naming both request keys
is the exemplar).

seon.agent.shell: `run` → "Run a command; returns its exit code and full
output as data." (argv rule → body first sentence) · `py-run` → "Run
Python source code; returns its exit code and output as data." ·
`job-output` → "Read a background job's captured output, full or
only-new." · `list-jobs` → "List your background jobs, newest first."

seon.agent.search: `grep-graph` (was "Text-search the LIVE PROGRAM GRAPH
— the literal counterpart of `grep`.") → "Search stored code (functions,
schemas, namespaces) by regex."

seon.embed: `search` (was "Embedding KNN search (P2-C)." — a dated
phase ref in an agent-facing card) → "Find stored entities semantically
similar to a text query." · `enabled?` de-glyphed.

seon.test.runner: `run!` (was "Universal entrypoint.") → "Run tests
selected by var symbols or by namespace; returns data." · `run-ns!` →
"Run every test in one namespace and record the results."

seon.agent.message: `message!` → "Send a message; the single entry point
for message writes." · `user` → "Send a message to your human user."
(first rewrite dropped "user"; the re-lint measured the regression —
§3 — and `f6cd9761` restored the fn's own name token).

seon.agent.lifecycle: `wait`/`pause` de-glyphed (arrow/em-dash out of
line-1, real names kept). seon.agent.web: `fetch` → "Fetch a web page as
markdown: a preview now, the full text as a blob." · `search`/`grants`
de-glyphed. my.canvas: `form` (line-1 was hard-wrapped mid-sentence) →
"Stack controls into a form that submits a field map to your handler." ·
`input` → "A text field for a surrounding [[form]]." · `toggle`/`save!`
tightened. my.ui: `table` (was "A multi-column table — generalises
`kv-table` to N columns." — sibling-twin defined by its sibling) → "A
table of N labelled columns, built from rows of cell maps." ·
`status-line`/`badge`/`progress` de-glyphed. my.skills: `list`/`unload`
de-glyphed. seon.schema: `clear-all!`/`set-tee-fn!` de-glyphed.
seon.db: `listen!` → "Install a tx-listener; safe by default, never
crashes the pod." · `listen-async!` → "Alias of [[listen!]] for a
Promise handler (fire-and-forget)." · `managed-identities` → "Map each
managed eid to its `[identity-attr identity-value]` set." (these three
rode in `e2c3170e`). seon.db.internal: `normalize-entity-ref-keys`
(the clipped card named in the brief) → "Rewrite `:seon.db/ref`-keyed
entity maps into datahike `:db/id` slots." (rode in `e2c3170e`).

**Peer counter-edit (respected, deferred to owner):** my `as-of` /
`since` / `history` action rewrites were reverted by the
runtime-reliability lane in `976173a0` ("docs(db): preserve temporal
API wording") — those three line-1s keep their noun-phrase + em-dash
form. Not re-applied (their lane, their live capture-reads work); if
the glyph-free rule should win there, that is an owner call.

## 2. Schema sharpening (commit `6b75705c`)

From the KT2b 46-fn gap list, the evidence-gated subset:

- **`:seon.db/query-form`** `[:or [:vector :any] :map :string]` —
  registered ONCE, referenced by `::query-request`'s `::query` key and
  BOTH of `query`'s arities (the shape was inlined three times).
  `query`'s arity-1 is now `[:or ::query-request ::query-form]`, so the
  request schema RESOLVES for card projection. Acceptance is provably
  unchanged (the old `:or`'s `:map` branch already admitted every
  previously-valid call).
- **`:seon.db/entity-ref`** `[:or :int [:tuple :qualified-keyword :any]]`
  — THE taught entity-address shape; referenced by
  `::entity-request/::ref` and `::cas-ref`. `entity`/`entity-lazy`
  arities keep their `:any` escape (core render/derive paths thread live
  Entity handles); `::pull-request/::ref` deliberately stays `:any`
  (pull's map-in arity validates directly, no escape) with a comment
  pointing at the taught shape.
- **`::pull-pattern`** `:any` → `[:vector :any]` (every caller passes a
  vector; now enforced + projects as array).
- **`::thunk`** `:any` → `'fn?` and **`::time-point`** `:any` →
  `[:or :int 'inst?]` — both were dishonest `:any` (the real shapes are
  known and pure-data expressible).
- **`:my.plan/root?` → `:my.plan/root`** in `::tree-request` — an
  ID-typed key with a boolean name (KT3-redux tune table: `document`
  `:root?` missing/mistyped 3/7 — not inferable, and contradictory once
  spec-bearing cards show `::root? … ::id`). Request-only key, no
  persisted datoms, all callers + tests + docstrings migrated in the
  same commit; now mirrors `::plan-response`'s `::root`.

## 3. The re-lint (before → after, same cases, same scorer, same decoding)

Index: re-dumped from the live worktree cluster (167 fns at basis-t
536870929) + the 3 `seon.repl.autocomplete` cards merged unchanged from
the baseline dump (that ns post-dates the pin; its fns are case-bank
targets) = 170 cards (168 baseline + `my.kb/recall` + `my.ns/functions`,
which enter menus as distractors only — no cases target them).

| arm | name acc | Δ vs old base | FIX8 | parse | F1 | false-sug | trunc |
|---|---|---|---|---|---|---|---|
| needle old base (168 fns) | 0.283 | — | 0/24 | 0.972 | 0.374 | 0.25 | 1 |
| needle old doc-action (override) | 0.324 | +0.041 | 6/24 | 0.966 | 0.413 | 0.25 | 1 |
| needle old stack (override) | 0.386 | +0.103 | 14/24 | 0.979 | 0.486 | 0.25 | 1 |
| **needle NEW SOURCE (170 fns)** | **0.372** | **+0.090** | 3/24 | 0.986 | 0.472 | **0.208** | 2 |
| qwen old base | 0.421 | — | 7/24 | 1.000 | 0.574 | 0.00 | — |
| qwen old doc-action (override) | 0.469 | +0.048 | 14/24 | 0.993 | 0.619 | 0.00 | — |
| **qwen NEW SOURCE** | 0.428 | +0.007 | **13/24** | 0.993 | 0.579 | 0.00 | — |

**Control:** re-running both base arms against a re-dumped near-baseline
index (live main acme, 168 fns, basis-t 536877156) reproduced the old
baselines exactly — needle 0.2828/0.9724, qwen 0.4207/1.0.

Per-fn FIX8 (needle, correct/3; the amended-user final run):

| fn | old base | new source | new picks |
|---|---|---|---|
| `seon.db/transact!` | 0 | 0 | since ×3, as-of ×2, transact! ×1 |
| `seon.db/query` | 0 | 0 | abstain ×3 |
| `seon.schema/register!` | 0 | 0 | abstain ×2, enum-members ×1 |
| `my.plan/step!` | 0 | 1 | step!, needs!, fs/insert!, done! |
| `my.plan/done!` | 0 | 0 | needs! ×2, register!, status |
| `my.plan/next` | 0 | **2** | next ×2, abstain ×1 |
| `my.blob/put!` | 0 | 0 | text, concat!, stat (all in-family now) |
| `seon.db/entity` | 0 | 0 | abstain ×2, lifecycle/wait ×1 |

Qwen per-fn FIX8: `register!` 1→2, `next` 1→2, `put!` 1→**3**, `entity`
0→**2**; `transact!`/`query`/`step!`/`done!` unchanged.

Where needle's +0.090 headline actually came from (the broader pass, not
the FIX8): 17 fns improved — `blob/concat!` 2→3, `blob/text` 0→1,
`canvas/show!` 1→2, `kb/forget-source!` 0→2, `kb/remember` 2→3,
`plan/active!`+`drop!`+`move!` 0→1 each, `ui/table` 1→2, `fs/list-dir`
0→1, `grep-graph` 0→1, `shell/py-run` 1→3, `web/search` 1→2,
`embed/search` 0→2, `runner/run-ns!` 0→1, `plan/next` 0→2, `plan/step!`
0→1. 8 fns dropped by 1 (all n=3; incl. two UNTOUCHED fns —
`fs/write-file` 3→2, `lifecycle/complete` 3→2 — which bounds the
menu-redraw noise floor at about ±1/3 per fn).

**The measured regression that got a fix:** `message/user` 2→0 (picks:
`message/agent` ×2, `message!` ×1) — the first rewrite dropped the token
"user" from the line-1 of the fn NAMED user. `f6cd9761` restored it
("Send a message to your human user.") and the re-run recovered 0→1
(headline 0.3655→0.3724). A one-word lesson: **a fn's line-1 should
carry its own name-word when that word is the natural ask vocabulary.**

Honest caveats: menus are seeded per case-id over the fn pool, so the
170-fn pool redraws menus vs the old 168-fn run — per-fn deltas of ±1
are inside menu variance (see the two untouched regressions); the
24-case FIX8 aggregate and 145-case headline are the load-bearing
numbers. n=3 per fn, one greedy sample, unchanged case bank.

## 4. Unfixable / deliberately-left list (from the 46-gap KT2b list)

- `:seon.db/conn`, `:seon.db/db` — genuine runtime handles (`:any` is
  the documented idiom; `::db-val 'map?` remains the strict positional
  face). Policy-exempt.
- `:seon.schema/form` — a Malli schema DEFINITION, recursive
  heterogeneous third-party shape. Policy-exempt.
- `::cas-value` — honestly "eid, lookup-ref, or ANY scalar".
- `::listen-request/::key` — any equality-comparable key.
- fn-valued params (`::handler 'fn?`, `schedule/fire-due-schedules!`'s
  `::exec-fn!`/`::drive!`) — already predicate forms; the `#object`
  print in the old dump was a probe-side literal issue.
- "multi-arity: first arity only" notes (14 fns) — a translation-layer
  limitation, not a source defect; the first arity IS the map-in shape
  for all of them.
- `enum-members`/`identity-attr?` "opaque single arg" — honest
  positional `:keyword` args; nothing to fix.
- `seon.test.runner/::run-request` (`:and` of `:or`-of-maps) — a
  deliberate, tested pure-data "at least one" design (both-keys case
  must reach the body's envelope). Fix belongs in the TRANSLATION layer:
  flatten map-unions to the union of keys when building cards.
- `embed/search`/`search-pull` return `:any` — response shape is known,
  but they are simple-shape `^:async` fns whose wrappers validate
  output; sharpening returns needs a verified read of
  `seon.instrument`'s Promise-aware output validation first. Deferred,
  flagged.
- `seon.db/new-id!` (0.00 tier at the pin) — DELETED on the main tree
  (id allocation moved to `seon.db.id/allocate!` policy machinery); no
  fix applicable. **Case-bank note:** its 3 cases (and any menu
  membership) will break the next re-dump from a post-refactor tree the
  same way the missing `seon.repl.autocomplete` fns broke this one
  (KeyError in menu build) — re-target when the pin bumps.
- `my.skills/catalog-block` + `skill-block` — DEPRECATED stubs still in
  the index (stale-card distractors, the exact KT3-redux complaint).
  Deletion candidates; out of this unit's scope.
- `my.ns/functions` line-1 carries an em-dash (peer-authored fresh fn) —
  left; flag only.
- `as-of`/`since`/`history` line-1s — peer-reverted (`976173a0`), see §1.

## 5. Renames — ZERO applied; deferred candidates with evidence

The sweep's verdict stands after this unit: name-alias was +0.034
needle / ±0.000 Qwen (the weakest lever), and the source docstring pass
recovered most of what facades bought without any rename. Morning
candidates, strongest first — none cross the churn bar today:

1. **`my.plan/done!` → `finish-step!`** — still 0/3 needle after the
   docstring fix (picks scatter to `needs!`/`status`); name-alias arm
   moved it 0→1, facade (name+simple params) 0→2/3. But Qwen holds 1/3
   before and after, and the confusion is lifecycle-stance
   (done-vs-probe), not lexical. Evidence: thin.
2. **`my.blob/put!` → `save-content!`** — needle 0/3 with all picks now
   in-family (`text`/`concat!`/`stat`), while Qwen went 1→3 on the SAME
   docstring — the residual is needle-capacity, not the name.
3. **`seon.db/transact!` → `save-records!`** — the name-alias arm
   measurably did NOT rescue it ("still loses to as-of's pull"). Not a
   real candidate; listed to close it.

## 6. What was verified, honestly

- **Suite:** `bin/test-cljs` in the isolated worktree — **1236 tests,
  5649 assertions, 0 failures, 0 errors** (first pass). Main-tree suite
  NOT run (runtime-reliability churn; per coordination directive).
- **Live proofs (worktree cluster, fresh store):** (1) `GET
  /agent/root/debug` — the rendered context shows "Save records to the
  database…", "Ask the database a question…", "Add a new step to the
  plan.", "Get the next plan steps to work on." inside real compact
  cards, with the new `[:or ::query-request ::query-form]` spec visible;
  (2) the re-dumped index (a pull over live datoms) carries every
  rewrite + `:seon.db/query-form`/`entity-ref`/`time-point`/`thunk`/
  `cas-ref` and `:my.plan/tree-request` with `::root`.
- **Not verified:** the amended `user` line-1 exists in source + the
  patched card re-run, but was not re-dumped from a rebooted cluster
  (single-word docstring change; the dump is derived from the same
  source text). No live LLM drive was run (no keys in the isolated
  worktree; not needed for this unit's oracle).

## 7. Ops notes / artifacts

- Worktree: `/Users/sean/src/seon-fn-surface` (branch
  `repl-autosuggest/fn-surface-pin` = `299b37f7` + 4 cherry-picks;
  node_modules symlinked from seon-pin; `reference-code/datahike`
  submodule initialized for the wire-server's `:simd` extra-path).
  Cluster ran on 7986/7987 (7980-7983 were held by other lanes),
  stopped after verification.
- Re-lint artifacts (gitignored, re-derivable):
  `src-needle/data/fn-index-fnsurface-2026-07-12.json` (the new-surface
  index used, amended user card),
  `src-needle/data/kt2b/surface_sweep_{needle,qwen}.fnsurface.json`
  (my runs). The SHARED `fn-index.json` was re-derived from the live
  main acme (168 fns, basis-t 536877156) and the shared sweep files'
  base arms re-run against it (they reproduce the documented baselines
  exactly). The original basis-536877061 dump was consumed in the
  process; it remains re-derivable via an `as-of` dump against the acme
  store.
- The pin lacks `src/seon/repl/autocomplete.cljs` (post-pin file) — any
  pinned re-dump must merge those 3 cards or re-target their cases.

## 8. Flags for the owner / other lanes

- **Convention tension (measured):** action line-1s on pure reads beat
  noun-phrases for assemblers (`next` 0→2/3 needle, 1→2 Qwen;
  `list-open`, `tree`, `document` all moved) — `docs/conventions.md`
  "noun-phrase for pure queries" should probably become "action verb;
  mood no longer signals purity" or the compact card should carry a
  purity marker instead. Not edited (conventions are shared doctrine).
- **Temporal trio wording** — owner tie-break needed between the
  glyph-free capability rule and the runtime-reliability lane's
  "preserve temporal API wording" revert.
- **`db/query` aggregation-asks**: unchanged 0/3 everywhere, as
  predicted; the case bank should gain `my.kb/recall`-targeted cases so
  the new contract is measured (cases exist for query only).
- **Deprecated `my.skills/catalog-block`/`skill-block`** still render
  as index cards — deletion unit candidate.
