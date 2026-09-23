---
type: evidence
status: committed (fix schedule #24w); #24x not landed, owner fix in db.clj reported
created: 2026-09-23
---

# Lane page-reads — 2026-09-23

Schedule rows 24w (P1) and 24x (P2); `cache-invalidation-audit-2026-09-23.md`
(`eb903978b`) items 10 and 1; supersedes #48.
Held paths: `src/seon/render/web.clj`, `src/seon/render/walk.clj`,
`test/seon/render/web_test.clj`, this note.

## Commit 1 — a page GET never writes (24w)

Before: `canonical-namespace-response` called `ensure-namespace-owner!` on every
`GET /ns/<namespace>` with no assigned agent. That call transacts an agent, and the
agent then runs provider turns (audit: 9 paid calls, 294,627 tokens).

After:
- The GET renders an owned namespace with its first assigned agent's page, as before.
- An unowned namespace renders the existing agentless namespace inspection
  (`debug-response` with no agent, subject `[:seon.ns/name ns]`). That path was
  already proven write-free by `canonical-debug-inspects-without-creating-a-namespace-owner`.
  It now carries one explicit control, `create-owner-form`.
- The control is a plain HTML form POST to the existing agent-context route
  (`/agent/<ns>/context`, same-origin middleware) with `action=create-owner`.
  `context-response` decodes the form once and dispatches:
  - `create-owner-response` calls `ensure-namespace-owner!` and answers 303 to
    `/ns/<ns>`. An unknown namespace gets 404; a refusal gets 422.
  - Every other action goes to `context-action-response`, the old body, unchanged.
- `route.clj` did not change: the route id is the would-be agent id, and the owner's
  agent id is the namespace name.
- `ensure-namespace-owner!` declared its output as `:map`, but it returns the agent id
  string. It now declares `:seon.agent/id`.

### GET-reachable writers, traced from the route table (`seon.render.route/routes`)

| route | handler path | writes? |
|---|---|---|
| `/ns/{ns}` | `canonical-namespace-response` | **was `ensure-namespace-owner!`; fixed** |
| `/`, `/agent/{id}` | `page-response` → `current-page`/`derive-page!` | shared-cache atoms only |
| `…/debug`, `/ns/{ns}/debug` | `debug-response` | atoms only (existing regression) |
| `/feed/{id}` | `feed` | tab registration atom, render-channel offer |
| `/data` | `data-response` | blob read; a failed read offers a declared fault (error policy) |
| any page | `failed-page-result` | one declared fault per failure signature (error policy) |
| any page with an authored source slot | `render-source-call` → `turn/preview-sources` | **OPEN, not fixed.** The preview fork receives the cluster's connection (`turn.clj` `evaluate-sources` passes `:seon.db/connection`), so authored preview source that calls a writer transacts under a GET or SSE paint. This is content-dependent. Fixing it means removing write custody from preview evaluation (`turn.clj`, `sci/eval.clj`), which is outside this lane's paths. |
| `/css`, `/js` | static | no |

### Evidence

Scratch root: `tmp/page-reads/root`, built from `git archive d3bcdc302` into
`tmp/page-reads/src`.
- Symlinked: `reference-code`, `target`, `.cpcache`, `.clj-kondo/.cache`, `clj_kondo`.
- This lane's two files were copied in.
- The scratch JVM was launched with `META_MODEL_API_KEY`, `DEEPSEEK_API_KEY`,
  `MOONSHOT_API_KEY` and `OPENROUTER_API_KEY` unset. The root has no `.env`. The
  process env had 0 `*_API_KEY` variables.
- Every turn attempt recorded `:seon.ai/missing-credential-variable "DEEPSEEK_API_KEY"`
  (3 total). No provider call was possible.
- No namespace page was loaded on `default`.

Live, on the scratch server (`http://127.0.0.1:51080`), parent vs own, in one JVM.
`load-file` of `git show HEAD:src/seon/render/web.clj`, then of this lane's file. The
handler Var resolves on each request.

| code | GET of a free namespace | ms | commit unchanged? | agents after |
|---|---|---|---|---|
| parent (HEAD `web.clj`) | `/ns/my.agent` | 1,370 | **false** | `["my.agent"]` |
| own | `/ns/my.agent-test` | 786 | true | `[]` |
| own, repeated ×3 (`/ns/seon.flow`, before creation) | | 787 / 21 / 23 | true | `[]` |

Explicit creation, live: `POST /agent/seon.flow/context action=create-owner` returned
303 with location `/ns/seon.flow` in 436 ms and created agent `seon.flow`. The owned
GETs that followed took 2,175 / 877 / 48 ms. The first render of a new agent's page ran
concurrently with that agent's first (credential-less) turn. That cost is unchanged
from the parent, which paid it inside the GET.

Tests, `seon.test/run` on the scratch cluster (test file published with
`seon.cluster/refresh-source!`):

- `a-get-of-an-unowned-namespace-page-never-writes` (new; the 24w regression): green,
  7 assertions.
  - Its assertions are: 200; head commit id unchanged; `:max-tx` unchanged; no agent
    assigned; the create control is present and posts to the context route.
  - Parent: `seon.test/run` reused the green evidence, because `load-file` does not
    change program rows, so it could not re-execute there. Red on the parent is shown
    by the live row above: the commit moved and the agent was created.
- `namespace-routes-admit-by-reader-and-existing-corpus-row`: green, run-ms 5,105.
  - It asserts 303 plus location, creation provenance, repeated-POST idempotence,
    404 creation for a missing namespace, and 404 GETs that commit nothing.
  - The expectation "the first GET creates the owner" was the retired assumption and
    is deleted.
  - Its 404 loop counted `(db/datoms @connection :eavt)` twice per path. That is a
    whole-store decode, and it took the test from 8,510 ms to 5,105 ms. The head
    commit id is the witness now.
- `canonical-debug-inspects-without-creating-a-namespace-owner`: every assertion
  passes. Its duration was 5,001 ms against a 5,000 ms bound in one run and passed in
  the next. That is the with-server fixture cost (below).
- All three tests now pick their namespace with `unowned-namespace`: `seon.flow` when
  it is free, otherwise the first free corpus namespace. The fixture branches the
  cluster head, and namespace owners accumulate on a live store: `default` got owners
  from exactly the GETs this commit removes.
- Run ids: `ad60fb9fbc6e`, `cf62c7253023`, `205a00067bf5`, `b482b644c144`, `3fa657a6cf3f`.

Contracts compile from zero. Built from `(seon.schema/build-projection
(seon.schema.edn/packaged-forms))` in 380 ms. All five touched contracts compile:
`ensure-namespace-owner!`, `create-owner-form`, `create-owner-response`,
`context-action-response` and `context-response`.

## Commit 2 (24x) — not landed; the premise does not hold at the walk

Measured on `default` (read-only) and on the scratch root:

1. **The walk's pull is not `[*]`.** `root-selector` enumerates every installed
   attribute explicitly: 1,220 on `default`, and the dependency plan is a 1,220-attribute
   set, not `:all`. Its evidence carries attribute revisions for all 1,220. Almost any
   commit changes one of them, so the revision compare fails.
2. **The expensive part is the fallback in `seon.db`.** After an unrelated commit,
   `read-evidence-current?` calls `index-evidence-current`, which returns nil (no
   decision) for two reasons:
   - The `:db/id` member yields entity-only patterns (5 on the root pull, 3 on its
     reverse pull), and `(when-let [attribute …])` refuses them.
   - 16 of the 1,428 attribute patterns are `:db/noHistory`.

   So every entity replays its whole pull. That is 7 ms per entity on the scratch store
   and 115 ms for `root` on `default`. A root GET after one unrelated commit on scratch
   took 177–213 ms (×4). `seon.db/replay-read` was called 85 times across those 4 GETs.
3. **A declared selector drops stored members.** I compared every entity's present
   attributes with the members of the entity schemas indexed by its identity attribute
   (585 entities, 39 identity attributes, `default`).
   - Three identity classes store undeclared members:
     - `:seon.cluster/name` stores `:seon.source/commit-id`;
     - `:seon.error.occurrence/id` stores 11 `:seon.error/*` and `:seon.instrument/*`
       attributes;
     - `:seon.schema/key` stores 8, including `:seon.render/html` and `:seon.render/ai`.
   - Five identity attributes have no indexed entity schema at all: `:seon.error/id`,
     `:seon.fn/sym`, `:seon.config/agent`, `:seon.source/digest` and `:db/ident`.
   - Pulling "the declared selector" would silently truncate these, which the spec
     forbids.

**The owner fix, prototyped in the scratch JVM under `page-reads.probe` and not
committed.** It is in `seon.db/read-evidence-current?` and `index-evidence-current`
(`src/seon/db.clj`, held by lane speculative-context-consumer).
- When the revisions differ, index-check only the patterns whose attribute's revision
  changed. An attribute whose revision is unchanged is already proven by the revision.
- An entity-only pattern is covered when the plan's attributes are an explicit set.
- A changed `:db/noHistory` attribute in the read set, or a moved conservative
  revision, falls back to replay.

Measured on the scratch root after an unrelated committed write:

| check on the root agent's pull | ms |
|---|---|
| installed `read-evidence-current?` (replays the pull) | 11.0 / 12.7 / 17.1 (root, seon.flow agent, `seon.flow` ns) |
| full replay alone | 7.0 |
| `index-evidence-current` over all 1,428 history patterns | 8.7–10.2 |
| prototype, changed-attribute patterns only | 0.6–3.0 |

The prototype agrees with the installed check on every case measured:
- the unrelated write: true for all three entities;
- a message to `root` (it moves `:seon.message/to`): false for `root`, true for the
  `seon.flow` agent.

The page regression ("an unrelated commit does not re-derive the page block") belongs
with that owner change and `test/seon/db_test.clj`. This commit adds neither the
regression nor any walk code.

## TIMINGS (every operation over 1 s)

| operation | wall ms | justification / status |
|---|---|---|
| scratch boot from `git archive` (`bin/seon --root … start`) | 169,000 (ready-ms 123,117) | **>10 s DEFECT**, existing `docs/seon/issues/from-zero-boot-takes-minutes.md`. Dependency classes were a MISS with `:reason :pins-unavailable`: an archive snapshot has no submodule pins, so the shared class cache is not linked. That is a cache-linking defect; the note is not extended here (not a held path) |
| `seon.cluster/refresh-source!` of one test file on scratch | 14,864 / 17,113 / 23,047 | **>10 s DEFECT**: `full-source-refresh!` runs 10.6–16.3 s for a one-file change. Existing publication-work class (`lane-publication-work-2026-09-23.md`) |
| `seon.test/run` request, one member | 15,913 total, member run-ms 5,105 | **>10 s DEFECT** in the request overhead: `selection-admission`, `reach-digests` and `program-digest` (audit items 6–8, existing) |
| `bin/test-check`, 2–4 members | 24,000–34,000 | same request overhead, plus the with-server fixture (~4.5 s per member; `lane-page-key` recorded seed-cluster at 19 s as a defect) |
| with-server test body `namespace-routes…` | 5,105 (was 8,510) | fixture ~4.5 s plus POST 436 ms; the old datom counts were removed |
| first owned-page GET after creation | 2,175 | first render of a new agent's page, concurrent with its first turn; the parent paid the same inside the GET |
| unowned GET, first | 786–787 | first derivation of the namespace inspection; 21–23 ms warm |
| parent GET of an unowned page | 1,370 | the write this commit removes |

Everything else was under 1 s: GETs 10–50 ms warm, POST create 436 ms, contract
compile 380 ms, probes 5–181 ms, scratch transactions 45 ms.

## Verification limits

- The tests ran on a scratch cluster, not `default`. `default` runs archive `9f39ae83d`
  with publication off, so nothing here is live on `default`.
- The scratch JVM's `web.clj` was `load-file`d twice for the parent comparison, which
  leaves those Vars unarmed there.
- The full `seon.render.web-test` namespace was not run; the three tests above were.
- The Python and Node page probes under `test/seon/render/` were not run.
- `render-source-call`'s preview write custody is open (table above).
- Scratch roots `tmp/page-reads/root` and `tmp/page-reads/src` are retained as evidence.
  The scratch JVM was stopped (see the commit report).
- RESET NEEDED: no.
