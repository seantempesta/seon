---
type: reference
status: measured; fact-finding lane, no src/test/resources edits
created: 2026-09-23
tags: [agent-platform, caches, invalidation, datahike, measured, seconds-not-minutes]
---

# Cache-invalidation audit, measured on live default (2026-09-23)

Lane `cache-invalidation-audit`, schedule row 24u. Owner, verbatim: "audit all places
where we are constantly invalidating our own caches for no reason".

This note goes beyond the [census](invalidation-census-2026-09-23.md) (source reading,
`fee8bbbe7`) and its [review](review-invalidation-design-2026-09-23.md). Every row
below was measured live. Nothing in `src/`, `test/` or `resources/` was edited.

## Subject, boundary and method

There are two measurement windows. Every number carries its window label.

- **PRE** = "pre-move, pid 9104, unarmed, bfcce39ad".
  - Program archive `data/source/bfcce39ad…`, hook publication off.
  - The HEAD-only source commits were not loaded: `66c113d93`, `f4dd51d68`,
    `431821bfa`, `68a98f797` and `da151b516`.
- **POST** = pid 24492, HEAD `9f39ae83d`, nuked from zero, ARMED (1,821 cells,
  `ed62a3e06`).
  - Its `seon.profile` counters (`seon.profile/begin` / `explain`) are the per-callable
    hit/miss and cost evidence.
  - A snapshot diff counts every thread, so concurrent work is included.

The rules held throughout:

- No redefinition of any default Var.
- Every probe helper lived in the throwaway namespace `audit.cache-inv`.
- Caches were read directly: `.cache`, `.lru` and the priority-map min key of the
  core.cache objects, `datahike.lru/weighted-entries`, and the call-preparation state
  atom.
- One `d/listen` observer (`user/audit`, later `:audit.cache-inv/commits`) recorded each
  commit's changed revisions and whether the conservative revision advanced. It was
  removed at the end.

Branches:

- **PRE:** `:audit-cache-1` was created from `:cluster-default` and received 10 artifact
  writes.
  - The JVM move left it in the old store.
  - The from-zero nuke then deleted that store, so there is nothing left to retire.
- **POST:** `:audit-cache-2` was created, received 1 write, and was retired.
  - Roster afterwards: `cluster-default current-src db`.
  - Listeners afterwards: only the system listeners.

**Writes to default and paid calls that I caused. Read this first.**

1. **Artifact rows.** 13 MCP results went over the ~4 KB window: 8 PRE and 5 POST. Each
   transacted one `:seon.dev.mcp.artifact/*` row on `:cluster-default`, including every
   `read_only` one. That is W1 (item 4), reproduced by my own probes.
2. **Paid provider calls.** I GET'd `/ns/seon.db` and `/ns/seon.fn` (PRE). Each GET ran
   `ensure-namespace-owner!` (`render/web.clj:3132` at bfcce39ad).
   - That **created agents `seon.db` and `seon.fn`**.
   - Their turns made **9 paid provider attempts, 294,627 tokens**, between 00:55:21Z
     and 00:57:17Z.
   - I stopped them with `seon.cluster.agent/disarm!`: 8,246 ms and 6,633 ms, 14.9 s for
     the one eval. Only `root` stayed armed.
   - The nuke later removed both agents.
   - This is item 10. The GET path writes and spends money.
3. I also sent one non-`read_only` eval (`1`) on purpose, to measure item 3.

## 1. Inventory

The inventory was one scripted pass over `HEAD:src` and `script`. It matched these
patterns: `cache/*`, `lookup-or-miss`, `memoize`, `delay`, `volatile!`, top-level `atom`,
`WeakReference`, `:cache-context` and `query-cache`. That gave 174 hits.

- 54 are `(delay (requiring-resolve …))` Var handles or constants, which hold no derived
  state.
- The per-operation `volatile!`/`atom` locals are scoped to one call and never invalidate
  anything.

The rows that hold derived state across calls:

| # | cache (HEAD file:line) | key | reads | invalidation event | measured |
|---|---|---|---|---|---|
| C1 | `seon.db/projection-cache` LRU 24 (`db.clj:1234`) | revisions of `schema/projection-attributes` + connection/generation/conservative; then `[::commit id]`; then `[::declaration-content datoms]` | declaration datoms | a declaration-attribute revision; a new connection (branch) | hit after an unrelated write, 0.08 ms. **New branch at an equal commit: 173 ms PRE, 234 ms POST** (item 9) |
| C2 | `seon.db/value-projection-cache` LRU 4, weak `ValueKey` (`db.clj:1238`, `:1296`) | DB object identity | as C1 | every uncommitted/as-of value object | **unbounded: 338 of limit 4 (PRE), 121 of 4 (POST); eviction is dead** (item 7) |
| C3 | `seon.sci.eval/program-identity-cache` LRU 256 (`sci/eval.clj:2283`) | commit id → revision basis | cache-context | each commit (listener, one select) | full (256) PRE; cheap by design |
| C4 | `seon.sci.eval/base-context-cache` LRU 4 (`:2287`) | `[program-basis, loaded commit, commit-fault!]`; the basis carries **connection-id + generation** | program rows | program change; **any write on a non-cluster branch connection** | the default ctx was not re-acquired across 18+ data commits (0.31 ms, PRE). **A branch with 1 write: base-ctx 1,610 ms, 428 pulls (POST)** (item 5) |
| C5 | `acquisition-attribute-cache` LRU 8 (`:2280`), `refusal-recording-cache` (`:2487`) | projection fingerprint; `[program target]` | projection | new projection | 2 and 0 entries; fine |
| C6 | call-preparation state atom per cluster ctx (`call_preparation.clj:74`, `current-snapshot` `:535`, `plan` `:1008`) | snapshot: `checked-through-t` = **basis-t**; plan: row `basis-t` + **`contract-transaction` re-read per basis per symbol** | row attrs, fn rows | **every commit** | **snapshot 182–250 ms and each plan 190 ms after one 3-datom unrelated commit (POST)**; new branch 1,473–1,619 ms (item 2) |
| C7 | render shared cache per ctx (`render.clj:1623`) holding `::calls ::fragments ::page-results ::page-databases ::invocations ::packages ::ai-calls` and `[::entity-pull lookup]` | page: `program-key` (`74262d86e`) + committed value; entity pull: read evidence | retained reads | **runtime-eval event** drops pages (`web.clj:2278`); **every commit** replays wildcard entity pulls | GET warm 12–30 ms; after an unrelated commit 0.65–1.5 s; after a non-read-only MCP eval 215 ms (items 1, 3). Entity-pull entries are never evicted (352 PRE) |
| C8 | Datahike `query-result-cache`, a global weighted LRU (`query.cljc:2489`) | query + args + **source identity (connection/generation)**, promoted by attribute revisions | the query's dependency plan | a read attribute's revision; `:all` plans on every commit; new connection | unrelated write: hit (0.33 ms). **`[?f _ _ ?tx]` is `:all`: 236–254 ms per miss.** Branch work evicted default's entries: 56 → 14 (PRE) |
| C9 | `datahike.schema-cache` (`schema_cache.cljc:8`) | db | schema | schema change | not a factor |
| C10 | `seon.schema/projection-cache-value` per projection (`schema.clj:399`); `cluster.clj:1108`, `:1270`; test reach `runner.clj:2304` | projection identity | per caller | new projection object (follows C1) | inherits C1 |
| C11 | `seon.schema.edn/packaged-population-cache` (`schema/edn.clj:370`) | resource name, length, mtime | resources | a touch or rewrite of equal content | not measured (low) |
| C12 | `program.cljc:260` `!authored-shapes`, `env.clj:186` `declared-members`, `call_preparation.clj:129` row attrs, `render.clj:91` profile | constant or resource stamp | resources | none | KEEP |
| C13 | wake listener `volatile!` matchers/arming (`cluster/wake.clj:503`) | rebuilt only on `seon.wake/*`, `seon.listen/*` and schema attributes | wake facts | narrow; correct | KEEP |
| C14 | env `advance-projection!` (`env.clj:122`) | monotonic basis-t | projection | holds a value | KEEP |
| C15 | `seon.test.runner/program-digest` (`runner.clj:2369`), **no memo** | — | since-diff of every entity since the seal | every call | **3 calls per test request, 1,529 ms; first call on a new branch 2,279 ms (POST)** (item 6) |
| C16 | `seon.config/effective` (no cache) | — | config rows | every call | 3.8 ms per call, per GET/turn (low) |
| C17 | kondo per-file analysis cache (`fn/analyzer.clj:224`), test-input digests (`cluster.clj:1752`) | file digest | files | publication | cited from census A14/A15; not re-measured (hook publication off) |

## 2. Measurements under the workload

**Commit frequency and cause.** PRE, the listener saw 52 default commits in 108 s. Most
came from the two GET-created agents. The spread over the attributes was:

- `seon.cluster.eval` 171;
- `seon.turn` 58;
- `seon.db` 62;
- `datahike.read` 36;
- `seon.ai.*` about 45;
- artifacts 4.

In that window:

- **0 conservative advances.**
- **0 commits touched a projection attribute** (`proj-changed 0`).

POST, with the cluster quiet, there were 2 commits in 137 s, and both were artifact rows
from other lanes' MCP results.

**`:db.fn/call` (24q premise).** On `:audit-cache-1` (PRE), a
`[[:db.fn/call #'audit-tx dig]]` commit advanced exactly its 2 attribute revisions. It
did not advance the conservative revision (58.5 ms).

- So at the commit level, Datahike's revisions are already exact for `:db.fn/call`.
- What misses is the **in-transaction value**. It has no `:cache-context`
  (`writing.cljc:609`, `complete-db-update`; `core.cljc:136`), so every revision-keyed
  read inside a transaction function takes the content-key path (item 8).
- Row 24q should be re-scoped to that.

**One unrelated 3-datom commit, then the next `/agent/root` GET (POST, profile diff):**

- `derive-page!` 986 ms;
- `render.walk/neighborhood` 704 ms;
- `call-preparation/hook` ×5 411 ms, made of `current-snapshot` 203 ms and one `plan`
  207 ms;
- `render.walk/root-acquisition` 252 ms (`acquire-entity` ×15, 235 ms);
- `seon.db/with-declarations` ×385 1,069 ms, inclusive.

PRE: 139 of 141 stack samples of the same call were inside `read-evidence-current?`, 120
of them in `replay-read` → `pull-plan-with-evidence`.

**Page drops without any commit.** A 100 ms poller of `::page-results` (POST) showed a
drop at t=26 s, coinciding with another session's windowed artifact commit. That is a
non-`read_only` eval's `::runtime-eval` (`cluster.clj:513-522` → `web.clj:2722`). The next
GET took 0.65 s.

PRE, `::page-results` vanished within 2 s of every GET while other lanes were active.
After my own non-read-only `1`, the GET took 215 ms against 13 ms warm.

**Test request, reused-only (POST).** Named `seon.id-test`: executed 0, reused 2, 2,521 ms.

- `program-digest` ×3: 1,529 ms.
- `selection-admission`: 1,364 ms.
- `admit-run` transaction: 1,094 ms.
- `content-projection` ×25: 1,011 ms.
- `declaration-content-key` ×6: 888 ms.
  - Armed, its output contract validates every datom:
    `transaction-report-datom?` ×185,934, 496 ms.
- `changed-definition-symbols`: 705 ms.

The first run executed 2 tests in 6,691 ms. PRE (unarmed) the same runs took 3,650 ms and
1,695 ms.

## 3. Roots and whether today's fixes resolved them

| root | self-invalidation | status |
|---|---|---|
| receipts wrote `:seon.fn/calls` (census C1) | receipt settlement advanced a program revision | **RESOLVED** `f2e6285cc`: 0 of 52 commits touched a projection attribute |
| SCI identity by commit | every commit re-acquired the context | **RESOLVED in-connection** `5f2aa93f4`. Open across connections (item 5) |
| projection memo | every commit re-derived the projection | **RESOLVED in-connection** `55ddec16c`/`d32a6b2d7`/`fa39b67b3`. New defects: the value-tier leak and the commit key never written on a revision hit (items 7, 9) |
| page key held `:seon.db/db` | every acquisition dropped pages | **RESOLVED** `74262d86e`: warm GETs at 12–30 ms |
| read currency order | history scan before the revision compare | **RESOLVED** `51af11aec`, but wildcard `:all` evidence still replays (item 1) |
| index page read `:all` | every commit | `bfcce39ad`: not isolated here |
| render cache per branch | forks shared one cache | `66c113d93`: loaded POST only, not isolated |
| key contains something volatile | C6 basis-t; C6 `[?f _ _ ?tx]` `:all`; C7 wildcard `:all` = commit id; C15 no key | **OPEN**: items 1, 2, 6 |
| write on a read/eval/render path | W1 artifact rows; W5 GET creates an agent (and paid turns); non-read-only eval → runtime-eval | **OPEN**: items 3, 4, 10 |
| scoped too narrowly | C4 per connection; C8 per connection; C1 commit key absent | **OPEN**: items 5, 9 |
| `:db.fn/call` | in-transaction value has no cache-context | **OPEN**, re-scoped: item 8 |

## 4. Ranked open items (cost × frequency), each with its smallest fix at the owner

1. **Wildcard entity pulls replay on every commit.**
   - Evidence: `render/walk.clj` `acquire-entity` → `db.clj` `dependency-revision` turns
     `:all` into the commit id.
   - Cost: 235–252 ms (POST), and 139/141 samples (PRE), per GET and SSE pass after
     *any* commit.
   - Fix: the walk pulls with the entity schema's declared selector, so its evidence
     carries the real attributes (the review's "specialize deliberate projections at
     callers"). Keep `[*]` semantics elsewhere.
   - Files: `src/seon/render/walk.clj`.
2. **Call preparation keyed on basis-t, plus an `:all` contract query.**
   - Cost: `current-snapshot` 182–250 ms, plus 190 ms per prepared symbol, on the first
     call after every commit.
   - `contract-transaction`'s `[?function _ _ ?tx]` is 236–254 ms per miss, and its
     dependency is `:all`.
   - Fix:
     - compare the row attributes' and `seon.fn` attributes' revisions from
       `:cache-context` and skip both queries when they are equal;
     - replace `_` with the declared attributes, or `d/datoms :eavt eid`.
   - Files: `src/seon/call_preparation.clj`. Lane 24s holds this file; coordinate.
3. **Every non-`read_only` MCP eval drops every cohosted page.**
   - Cost: 215 ms to 1 s on the next GET, times every lane eval.
   - Fix: offer `::runtime-eval` only when the form defines or reloads code. Read the
     top-level form (the reader, not a regex) at the MCP boundary. Otherwise treat it as
     read-only.
   - Files: `src/seon/cluster.clj` (`mcp-valf`), `script/seon/dev/mcp.clj`.
4. **W1: a windowed MCP result writes an artifact row, even when `read_only`.**
   - Cost: about 150 KB of blob per result, and the head moves, which fires items 1 and 2
     for everyone.
   - I caused 13 of these.
   - Fix: apply the ready MCP blob-only patch (lane `mcp-and-stop` landing note).
   - Files: `src/seon/cluster.clj` (`mcp-project`, `:455-485`).
5. **Base context is per connection.**
   - Cost: a branch with one write re-derives 1,610 ms at an equal program. That is every
     test or candidate branch that writes.
   - Fix: register `watch-program-identity!` for every custody branch connection. When the
     program basis is unchanged across a commit, remember `db-before`'s identity for
     `db-after`.
   - Files: `src/seon/sci/eval.clj`.
6. **`program-digest` has no memo.**
   - Cost: ×3 per test request, 1,529 ms; 2,279 ms on a new branch (as-of projection).
   - Fix: compute it once per request, memoized by the program-attribute revision basis
     (C3's basis). An equal basis means the seal digest.
   - Files: `src/seon/test/runner.clj`, `src/seon/test.clj`.
7. **Value-tier LRU leak and the content key's contract.**
   - The leak: `hit` re-inserts a new `ValueKey` object into the priority map. Once the
     referent dies, the min key equals nothing, so `miss` can never evict it.
   - Evidence: `min-key-in-cache? false`, 121 or 338 entries against a limit of 4, and 2
     to 16 projections retained only by this tier.
   - Armed, `declaration-content-key` spends 148 ms validating 30,615 datoms.
   - Fix:
     - key equality stays reflexive for a dead key (the same hash, and both referents
       collected), or drop the tier;
     - give the key a declared, bounded output schema.
   - Files: `src/seon/db.clj`.
8. **In-transaction values have no cache-context.**
   - Cost: `content-projection` ×25 per test request (about 1 s armed).
   - Fix in the fork: `with`/`:db.fn/call` values carry the parent context, marked
     uncommitted, so revision keys see "parent + these attributes".
   - Files: `reference-code/datahike/src/datahike/writing.cljc:604-611`,
     `core.cljc:136`. Re-scope row 24q.
9. **Datahike's query cache and the projection commit key are scoped per connection.**
   - Cost:
     - a new branch at an equal commit pays call preparation 1,473 ms and projection
       234 ms;
     - branch work evicts default's entries (weight-limited global LRU, 56 → 14).
   - Fix:
     - key committed sources by commit id, an immutable root, in
       `query-cache-source-contexts`;
     - on a revision hit, `carried-projection` also records `[::commit id]`.
   - Files: `reference-code/datahike/src/datahike/query.cljc`, `src/seon/db.clj`.
10. **A GET of an unowned namespace creates an agent that runs paid turns.**
    - Cost:
      - 9 attempts and 294,627 tokens in 2 minutes;
      - about 50 commits, which invalidate items 1 and 2 for everyone;
      - 4.9–9.8 s pages.
    - Fix: GET never writes. Owner creation is an explicit action.
    - Files: `src/seon/render/web.clj` (`ensure-namespace-owner!`).

Lower: `config/effective` is uncached at 3.8 ms per call. Render `[::entity-pull …]`
entries are never evicted. `packaged-population` is keyed by mtime.

## Other findings (not caches)

- **Stop is over the ten-second rule.** `disarm!` took 8.2 s and 6.6 s during a turn's
  CPU prelude; the eval was 14.9 s. It belongs to the existing class
  `docs/seon/issues/a-turn-spends-seconds-before-its-provider-call.md`, where the
  landing note says "a CPU-bound prelude still delays the stop". It is not extended here,
  because this lane commits one file.
- **The MCP window (~4 KB) is small enough that ordinary probe results write artifact
  rows.** This strengthens item 4.

## TIMINGS (every operation over 1 s)

| window | operation | wall ms | justification |
|---|---|---|---|
| PRE | new-branch `current-snapshot` | 1,619 | defect: item 9 (per-connection query cache), proportional to all program rows |
| PRE | SCI eval on a branch with writes (first) | 2,070 | defect: item 5 |
| PRE | `/agent/root` after commits | 1,535 / 1,076 / 960 | defect: items 1–3 |
| PRE | `/ns/seon.db` ×4, `/ns/seon.fn` ×2 | 6,294 / 5,001 / 4,880 / 9,830; 2,692 / 1,918 | defect: item 10 (a live agent writing under the page); under 10 s |
| PRE | test request (execute 2; reused ×2) | 3,650; 1,695 / 1,527 | defects: items 6–8 |
| PRE | `disarm!` ×2 (one eval 14,887 ms) | 8,246 / 6,633 | **over 10 s: existing issue above**; stop waits out the CPU prelude |
| POST | boot (orchestrator's, observed) | ready-ms 107,102 | existing `from-zero-boot-takes-minutes.md` |
| POST | `/agent/root` first after boot; after a commit | 1,358; 1,007 | items 1–3 |
| POST | branch probes: projection 234, call preparation 1,473, `program-digest` 2,279 / 383 | eval 4,410 | items 6, 9 |
| POST | SCI eval on a branch after 1 write | 2,478 (base-ctx 1,610) | item 5 |
| POST | test request (execute 2; reused ×2) | 6,691; 2,229 / 2,521 | items 6–8; armed |

Everything else I ran was under 1 s:

- branch create 25–37 ms; open 10–21 ms; retire 9.9 ms;
- 2-datom transactions 50–94 ms, and 312–337 ms for the first write on a new branch
  (the writer-cost lane's subject);
- config read 3.8 ms; every probe eval 3–600 ms.

Cache hits and misses are in §1 and §2.
