---
type: research
status: completed
tags: [research, architecture, database, web, agent]
---

# Architecture target drift audit — 2026-07-14

## TL;DR

The architecture set has the right thesis, but it is not yet one coherent
target. Its strongest documents already describe the intended system: one JVM
database/heavy-compute server, one canonical CLJS agent/UI runtime, database
facts as authority, one observed render-unit engine, a dedicated root layout,
minimal derived context, and one runner per runtime boundary. The drift comes
from older implementation narratives being left beside that target.

The highest-priority corrections are:

- Make the deployment topology unambiguous: the normal local system is **two
  processes**, the JVM database server plus the CLJS pod. The pod may combine
  UI-host and agent-runtime roles, but it does not “play” the JVM role, and the
  writer is not re-homed to Node. Remote and thin-client topology remain later
  PRDs.
- Remove the per-render `instrumentation-gaps` census from the target. A failed
  publication/instrumentation transition is a readiness/core fault at its
  owning transition, not a 505-row standing prompt block. This is the cause of
  the observed 5,538-token root block, not a context-cap problem.
- Make the root target internally consistent: `/` has its own system layout
  over the shared route/render-unit/feed engine. It is not an ordinary agent
  page wrapped around a fleet canvas.
- Replace the historical `library-grounding.md` phase diary with a small,
  current concept-to-source read map. Move dated findings and rejected
  Packetstar/paused-JVM material to PRD research or archive.
- Correct `data-model.md`'s claim of completeness. Its run/turn tables omit
  result/blob fields, retain the retired `prompt-file`, and omit the blob entity
  even though observability depends on it.
- Reconcile `toolkit.md` with the intended public namespace surface. It
  describes several `my.*` namespaces that do not exist and are not in the
  active roadmap, while the actual home context exposes protected
  `seon.agent.*` capabilities plus the smaller real `my.*` corpus.
- Remove downstream-product details from the core architecture. ACME-specific
  routes, files, and `set!` seams are implementation evidence for a downstream
  proof, not Seon's target architecture.
- Treat `docs/prds/runtime-reliability/roadmap.md` as the status/path document,
  not a second architecture book. Its completed chronology should become
  linked research or a closeout record; its remaining slices should become
  independent PRD folders and branches.

The architecture/roadmap distinction should be stated once and enforced:

| Document | Owns | Must not own |
|---|---|---|
| `docs/seon/architecture/` | Always-current intended system, written in present tense; vocabulary, invariants, boundaries, schemas, and settled ADRs | “Complete/partial/pending,” current failures, commit SHAs, dated phase narratives, or build order |
| `docs/prds/<chunk>/roadmap.md` | Current implementation state, the exact gap, ordered work, proof, and graduation for one branch-sized chunk | A competing long-lived description of the whole system |
| `docs/prds/<chunk>/research/` | Dated evidence, source audits, experiments, rejected alternatives, and raw external findings | Current authority or instructions that must remain loaded forever |
| localized `AGENTS.md` | Durable ownership/invariants/runbook plus links | Branch diary or duplicated architecture prose |

## Audit method and evidence boundary

This audit read `architecture.md` first, then every active architecture domain
doc, every ADR, both archived architecture records, the active runtime-
reliability `AGENTS.md` and roadmap, the instruction-unification worktree
changes, relevant localized `src/**/AGENTS.md` files, and the completed root,
render-cache, test-impact, instruction-unification, database, lifecycle, and
distribution research. It checked claims against active source/config/routes
and recent commits. Material library claims were checked against the vendored
Datahike, Malli, Reitit, Datastar, Hyperlith, and SCI source pointers already
captured by those audits.

This report deliberately does not call an unbuilt target “wrong” merely because
the current source has not reached it. A claim is drift when it conflicts with a
settled target, conflicts with another architecture doc, names a retired
mechanism as current, has no implementation chunk that would build it, or puts
status/history in the target layer.

## Cross-document contradictions to resolve first

| Conflict | Target ruling | Evidence |
|---|---|---|
| “One application, two processes” versus “v1 = a single pod plays all three roles” | The local deployment is JVM database server + CLJS pod. The pod combines UI-host and agent execution today; the JVM remains a separate process. | Root instructions and runtime PRD establish the two processes. `architecture.md:191-234` currently collapses the JVM role into the pod. |
| Permanent JVM authority versus “writer either remote or re-homed to Node” | The authoritative writer/heavy-work server remains JVM. Location may become remote; implementation does not move to Node. | Settled runtime PRD and client/server research; the user selected a strong server and thin clients. |
| Remote replay described as “settled” versus remote replication explicitly deferred/open | Keep local protocol/replay truth in the main architecture; move remote bootstrap/catch-up choices to a remote-replication PRD until proven. | `architecture.md:31-53` asserts compressed full-log bootstrap; runtime roadmap defers WebSocket/cloud/browser replication and says topology remains open. |
| Root uses the same layout versus root needs a dedicated system layout | Share blocks, render units, route resolution, observation, and feed machinery; use a distinct root layout. | Root live audit proves `/` currently calls the ordinary agent shell and duplicates the fleet surface. `ui.md:163-270` already has the better target. |
| Never crash versus fail-loud development | Agent/user failures are values. A core fault is recorded once and development may intentionally fail the process/readiness gate; production returns a bounded fallback. | `SEON_RENDER_STRICT` and `:seon.config/on-core-error` design in `observability.md`; the root instructions require cause-fixing and fast development failure. |
| Instrumentation coverage is a boot/publication invariant versus a standing giant root block | Validate the exact candidate at publication, fail readiness or record one bounded core fault, and expose compact diagnosis on demand. Do not enumerate every missing wrapper in every root prompt. | `context.md:267-334`, ADR 007, `config/system.edn:311-312`, and `warnings.cljs:102-129` still canonize the expensive census. The current PRD says hot reload now reaches zero gaps. |
| Minimal context versus broad always-on manuals | Default context remains namespaces + canvas + plan + transcript, with no skills block. Additional data is namespace-local, state-gated, pull-first, or explicitly installed after behavioral proof. | `config/system.edn:204-268` is the current minimal tree; the user's explicit policy and `context.md`'s strongest sections agree. |
| “No output rewriting” versus batch reply claim stripping | The target should make parsing/evaluation/results truthful without regex-rewriting model output. If the current boundary filter remains temporarily, keep it in the roadmap as debt, not an architectural principle. | Root instruction “Fix causes, not agent-output symptoms” conflicts with `context.md:129-172`. |
| One vocabulary versus active `tile`/`store` history inside current docs/tests/config | Core architecture uses database/db, block, render, surface, canvas, card, slot, page, web UI. Historical ADR/archive prose may quote old terms; active names/tests/config must migrate or be deleted. | Active search still finds `tile` throughout render tests, typeahead naming, `config/acme.edn`, and `library-grounding.md`; core source has largely moved to surface/canvas. |
| Complete data model versus stale schema tables | `data-model.md` must list the intended complete schema, including result/blob/session/coordinate facts, and remove retired attrs. | `turn.cljs` has prompt/reply blob refs, error, llm-meta, results-stripped, and usage-estimated; `run.cljs` has result/result-ref/closed-at. The doc still lists retired `prompt-file` and omits those fields. |
| Toolkit catalog versus discoverable code | Architecture names only intended namespaces with a real owner and implementation PRD. Function-level truth comes from schema/docstrings/program graph, not a manually duplicated pseudo-API. | Real `src/my/` contains `blob`, `canvas`, `data`, `kb`, `ns`, `plan`, `skills`, and `ui`; `toolkit.md` also declares absent `my.files`, `my.search`, `my.shell`, `my.test`, `my.code`, and `my.schedule`. |

## Per-file drift and proposed target edits

### `architecture.md`

**Keep:** the thesis, one graph, derive-don't-store, fully specified functions,
one human/root bond, one vocabulary, database-as-bus, and the domain map.

**Update now (target truth):**

- Rewrite deployment as two concrete local processes and three logical roles.
  JVM database authority is one process; the CLJS pod currently combines
  UI-host and agent execution. Per-agent Node workers are an isolation target,
  not a claim that already changes the process count.
- Delete “re-homed to Node.” A remote server is a location change behind the
  same database protocol, not a database-authority implementation fork.
- Move remote bootstrap/snapshot/backfill choices out of the core thesis. Keep
  only the transport-independent invariant: a replica attaches at a complete
  coordinate, applies committed transactions in order, and repairs a gap
  without inventing state.
- Define render keys precisely once: `:seon.render/html` selects/stores the
  human renderer; its value response carries `:seon.render/hiccup`.
- Change “identical layout machinery” wording to “shared block, render-unit,
  route, observation, and live-feed machinery; page-specific layouts.”
- State development core-fault escalation explicitly so “never crash” does not
  contradict fail-loud source-checkout behavior.
- Keep dependency-aware testing as a target but state the proof boundary:
  namespace/schema/macro edges may narrow; incomplete call/dynamic edges widen.

**Move out:** device bootstrap algorithms, cloud history backfill, and
per-agent microVM rollout details belong in their future PRDs. The architecture
may link those target domains without declaring an unproven algorithm settled.

### `context.md`

**Keep:** context as ordered functions of a frozen database value, transcript
as the narrative spine, plan as externalized intent, current namespace as
location, additive/state-gated blocks, explicit injected dependencies,
skills absent by default, and stable old transcript bytes.

**Update now:**

- Replace the broad order beginning with “reference-code namespaces” with the
  actual target gradient: minimal fixed blocks; current/home-required namespace
  cards and current namespace source; stable transcript bands; state-gated or
  pull-first additions. Reference code is a source an agent can explicitly
  inspect, not an unconditional prompt band.
- Remove the reply-rewriting policy from the target. Retain the batch invariant
  that every complete parsed form is attempted and every real result appears in
  order. Track any current claim-stripper as migration debt.
- Remove the instrumentation census from the injection/instrumentation section.
  Instrumentation publication belongs to the program transition; context may
  show one concise current core fault only when that transition failed.
- Use one render-key vocabulary and distinguish stored selector from returned
  Hiccup response.
- Tighten root context to one irreducible role block plus namespace-led
  operations and derived fleet/recovery facts. Do not describe a second fleet
  block or standing system manual.
- Keep the system prompt and selected config as database facts, but link the
  exact population reconciliation to the lifecycle/data docs instead of
  repeating its algorithm here.

**Status that belongs only in the roadmap:** current prompt token totals,
current config block list, whether current namespace auto-run is fully wired,
and the current context failures found in live drives.

### `data-model.md`

**Keep:** absence over nil, attribute presence instead of an entity
discriminator, one `:seon.db/ref`, component ownership semantics, symbol values
for late-bound functions, transaction metadata for user/process provenance,
the complete coordinate, and data-to-agent scoping refs.

**Update now:**

- Change frontmatter from `type: prd` to `type: architecture`.
- Replace repeated “entity kind” terminology with “entity shape” or “attribute
  presence.” A derived label is fine, but the architecture should not teach a
  pseudo-type system after banning it.
- Make the run table complete: result, result-ref, closed-at, and any selected
  bound fields must appear; derived-only values must be labeled as such.
- Make the turn table complete: prompt/reply blob refs, error, llm-meta,
  results-stripped, usage-estimated, and the final complete rendered coordinate.
  Delete `:seon.agent.turn/prompt-file`, which source explicitly marks retired.
- Add the blob projection entity and its identity/content metadata, then show
  prompt/reply refs pointing to it. `observability.md` cannot own an entity that
  the “complete” schema doc omits.
- Reconcile message/turn/session facts with the final root-navigation design.
  Current source does not yet have `message/web-session` or
  `turn/cause-message`; the target can keep them, but the roadmap must own their
  build.
- Correct the root-context config semantics. A root home-require scalar is a
  complete replacement; block data may use the one named block reconciliation
  rule. Do not say both “sparse merged override” and “complete scalar” without
  naming which value follows which rule.
- Remove ACME commands from the core manifest examples. Use a generic downstream
  manifest example and link consumer proof from its own repository.
- Reconcile the error vocabulary: persisted forensic `:seon.error/fault`
  (`:agent | :core`) is the blame axis. Do not teach a competing
  `:user-input | :core-bug` “kind” in `toolkit.md`.

**Historical detail to move:** multi-page proof narratives and line-numbered
library derivations belong in dated research and `library-grounding.md`, not in
the schema table itself.

### `agent-runtime.md`

**Keep:** derived state, run/turn separation, one frozen database value per
turn, writer-side CAS fences, independent work/deadline bounds, one ticker,
fact-first birth, explicit boot/birth/resume transitions, no effect replay,
idle-after-crash recovery, and root as one capable agent plus an external
supervisor.

**Update now:**

- Change frontmatter from `type: prd` to `type: architecture`.
- Separate the local current target from later worker/microVM isolation. The
  execution-service contract belongs here; pool sizing, memory numbers, and
  platform-specific microVM choices belong in an isolation PRD.
- Remove implementation-era issue labels, dated “settled” headings, and exact
  historical drive defenses after their invariant is stated.
- Make boot steps refer to one published candidate transition rather than
  retelling config/schema/program ownership in multiple paragraphs. Link the
  data model and the future lifecycle PRD for the exact transaction compiler.
- Fix the instrumentation paragraph typo and state the one delta rule:
  reconstruct once from committed program facts; thereafter instrument only
  the changed definitions/schema dependents. A publication failure fails
  admission and records one core fault.
- Review the stored complete-test gate. Parsed test-run facts may remain useful,
  but completion must not depend on a removed display adapter or silently treat
  an unrecognized runner as proof. State the behavioral invariant and let the
  test-system PRD own recognized-run mechanics.
- Keep the batch non-fail-fast rule. Remove any suggestion that unattempted
  forms receive synthetic results.

**Status to move:** whether worker threads, restore, full coordinates, and
crash supervisor are currently implemented belongs in their roadmap chunks.

### `ui.md`

**Keep:** one block with AI/HTML twins, one guarded render engine, dedicated
canvas, surface/card distinction, page-specific layouts, root system view,
chat-first transcript, lazy debug/data units, Reitit routes as data, one
capability-gated call door, Datastar signals for transient state, gzip SSE, and
runtime-observed reads as correctness authority.

**Update now:**

- Make the render-unit engine the sole transition owner. Remove language that
  permits `seon.ui.agent-view/transition`, debug, and `/data` to retain custom
  invalidation paths once the new engine lands.
- Say directly that declared `:seon.fn/read-attrs` may guide focus/cold-start
  hints but can never veto a captured runtime read. The live root audit proved
  this exact bug.
- Define one root shell/feed composition without implying root uses the
  ordinary agent layout. Keep `/agent/root` canonicalized to `/`.
- Keep current root-card descriptions derived from plan, purpose, and bounded
  conversation facts. Do not introduce `roster`, summary, last-accessed, or
  activity entities.
- Specify lazy-unit lifecycle rather than merely collapsed markup: closed means
  no renderer, Hiccup tree, SCI invocation, observation, or cache entry.
- Keep the cache automatic at the unit boundary. Active-unit state is bounded
  by consumers; an across-subscription LRU exists only after profiling and is
  an engine implementation detail.
- Remove the ACME-specific “total override” section and the two global `set!`
  seam recipe. State a generic downstream rule: override through route/block/
  renderer/config facts and public symbols; consumer wiring lives downstream.
  A mutable global override is not database-derived target state.
- Remove status labels such as “Status: LIVE” and current proof stories. Those
  belong in the roadmap/research.
- Reconcile exact root feed path and route names with the dedicated root layout
  PRD before documenting them as settled. The invariant is one shell plus one
  feed, not a particular compatibility URL.

### `toolkit.md`

**Keep:** protected `seon.*` substrate, editable `my.*` composition layer,
fully specified functions, namespaced map data, explicit errors-as-values,
one allocator, `my.canvas`, `my.plan`, `my.kb`, and discoverability from code.

**Update now:**

- Change frontmatter from `type: prd` to `type: architecture`.
- Rebuild the namespace table from the intended source of truth. The actual
  `my.*` corpus is `blob`, `canvas`, `data`, `kb`, `ns`, `plan`, `skills`, and
  `ui`; filesystem/search/shell/web are currently protected
  `seon.agent.*` capabilities exposed by home requires. Either create an
  explicit future PRD for each missing wrapper or delete the absent namespaces
  from the target catalog. Do not leave fictional APIs in permanent context.
- Stop manually duplicating every function signature in the architecture.
  Namespace purpose, ownership, and compositional shapes belong here; exact
  signatures/docstrings are discoverable program facts and must remain
  colocated with code.
- Replace the old timestamp agent-id example with a readable word ID and keep
  generic identities abstract behind `seon.db.id/allocate!`.
- Standardize error blame on `:seon.error/fault`, not a second
  `:seon.error/kind` taxonomy.
- Correct blob storage claims. The current `my.blob` implementation is a
  SHA-256-addressed append-only file tier; it does not implement the documented
  zstd/GC behavior. Compression/GC requires a dedicated blob-lifecycle PRD if
  it is still desired.
- Remove stale “full toolkit renders every turn” language where it contradicts
  the minimal namespace-card/current-namespace context policy. Composition
  functions can render fully when relevant without rendering every owned
  namespace unconditionally.

### `observability.md`

**Keep:** exact turn coordinate plus byte-ground-truth prompt/reply blobs,
transaction-metadata provenance, queryable errors, turn/turn-diff/ctx-preview,
branch-qualified debugging, non-autonomous forensic views, and Inspect through
the production one-shot door.

**Update now:**

- Keep full-coordinate, fork, restore, and forensic-agent behavior as target,
  but remove current command timings and implied availability. The roadmap
  records that `bin/seon` currently exposes reset but not create/fork/destroy or
  `watch-faults`.
- Reconcile blob terminology and lifecycle with the data model. Blob durability,
  overlays, promotion, and GC are one design; do not describe them as complete
  without an owning PRD.
- Replace the root `instrumentation-gaps` surface with one bounded publication
  fault and on-demand diagnostics. Observability owns the detailed drill-down;
  prompt context does not.
- Separate local branch/restore semantics from remote/cloud retention. A local
  immutable Datahike root does not by itself settle remote transfer,
  cancellation, or retention policy.
- Keep logs as operational evidence but preserve the stronger statement that
  forensic truth is database facts plus blobs. Process startup/readiness logs
  are still necessary and should not be rhetorically treated as useless.

### `laws.md`

**Keep:** render prominence for composition functions, stable aged transcript
bytes, full qualification, canvas honesty, honest termination, pass-to-k,
hermetic fixtures, and live drives.

**Update now:**

- Change “hoist skill guidance into always-on context” to the evidence-gated
  policy: skills remain absent by default; promote only a concise instruction
  whose behavioral drive proves it belongs in the minimal base, preferably by
  improving the owning namespace/schema/docstring first.
- Distinguish empirical observations from permanent law. Each law should link a
  dated reproducible drive and name its revalidation condition.
- Replace old product examples (`my.data`, broad toolkit names) when they no
  longer represent the intended public surface.

### `library-grounding.md`

The current file is mostly historical migration notes, not an always-current
read map. It contains old phase numbers, obsolete `ctx.cljs` line references,
Packetstar/tile language, a downstream ACME proof, old character-budget text,
and instructions to a long-finished “Lane U” build agent.

Replace it with a short table organized by architectural concept, for example:

| Concept | Vendored/current source to read | Invariant it proves |
|---|---|---|
| Datahike transaction fence | `reference-code/datahike/.../transaction.cljc`, `src/seon/db/*` | CAS is checked by the authoritative writer |
| Refs/components/index cursors | Datahike transaction/db/index source, Seon bridge | lookup and ownership semantics |
| Malli candidate/projection/instrumentation | vendored Malli registry/instrument source, `seon.schema`, `seon.instrument` | publish only validated complete candidates |
| Reitit route derivation | vendored core/ring source, `seon.route`, `seon.web.router` | routes are compiled values; streaming remains Seon-owned |
| Datastar morph/feed | vendored Datastar SDK/client and tiny gzip example, `seon.web.datastar` | stable-ID element patches, signals, and flushed gzip SSE |
| SCI bounded invocation | vendored SCI source, `seon.render.sci` | one capability/bounding door |
| Changed tests | vendored Shadow and clj-kondo sources, Seon test artifact/selector | conservative namespace/macro dependency selection |

Move the existing source quotes, validation summaries, historical bugs, and
phase-specific instructions into dated research files. Preserve useful evidence;
do not keep it permanently loaded as architecture.

### ADRs and archives

- **ADR 001 (Nippy):** mark superseded/archived. The active semantic protocol
  uses Transit; Nippy is a private Konserve encoding detail, not the Seon wire
  decision. Add or revise one active database-protocol ADR around data-only,
  fully namespaced Transit messages independent of transport.
- **ADR 002 (absence over nil):** keep active. Update examples to current
  `seon.db/transact!` request shape and current source links.
- **ADR 003 (`:seon.db/ref`):** keep active. Update the ref grammar and source
  owners to `seon.schema.cljc`/`seon.db.internal.cljs`; remove old API examples.
- **ADR 004 (schema unification):** keep implemented, but delete the stale
  `flow/msg.clj` and `render.clj::html` “still open” section. Link the current
  candidate/projection publication design.
- **ADR 005 (core.async.flow):** status must be superseded/abandoned, not
  implemented. Move it under archive or keep only a short supersession header
  plus historical link. Active source/classpaths no longer contain this system.
- **ADR 006 (JVM-per-agent):** status must be superseded/abandoned. Its detailed
  nREPL/pool design belongs in archive. Add a fresh isolation ADR only after the
  worker/microVM target is carved into an implementation PRD.
- **ADR 007 (instrumentation):** retain the always-on decision but rewrite the
  body around committed program facts, one reconstruction pass, incremental
  definition/schema-dependent deltas, structural exceptions, and fail-closed
  publication. Delete the Integrant implementation and the per-render
  instrumentation-gap census from active decision text.
- **`archive/datahike-reactive.md`:** keep as history. Old vocabulary is allowed
  because the file is explicitly abandoned; active searches should exclude
  archive paths.
- **`archive/jvm-main-app.md`:** keep. It is concise, names the Git recovery
  point, and clearly forbids compatibility restoration.

## Current implementation facts that belong only in the roadmap

The following are useful and should remain visible, but not in target prose:

- `/` currently calls `write-agent-page!` for `"root"`, and
  `/agent/root/feed` currently uses `seon.ui.agent-view`.
- `seon.ui.agent-view/transition` currently gates exact runtime observations
  behind non-transitive declared keyword reads, causing live stale units.
- Debug currently performs broad 400–1,100 ms rerenders while open.
- The complete CLJS suite most recently passed 1,301 tests/6,159 assertions;
  focused changed-test feedback is namespace-level and being extended across
  CLJ/CLJS/CLJC.
- `bin/seon` currently exposes up/down/restart/config/status/logs/doctor/tests/
  skills/reset, not the full target branch/restore/forensic command family.
- Web sessions, message-to-session provenance, and turn cause-message facts are
  target schemas not yet present in active source.
- `/data` currently has the shared feed and bounded schema/AEVT path, while
  entity/reverse-ref/transaction/history units remain incomplete.
- Tile vocabulary still appears extensively in active tests and some config;
  core product source is farther through the surface/canvas rename than its
  behavioral fixtures.
- The default config is minimal and has no skills block, but root still receives
  the instrumentation-gap block and a sparse block overlay.

These facts should be updated or deleted as code changes. They are not reasons
to weaken the target.

## Historical or duplicate prose to delete or link

- Old phase/lane handoffs embedded in `library-grounding.md`.
- ACME-specific override instructions in `ui.md` and manifest examples in
  `data-model.md`.
- Repeated config reconciliation algorithms across context, data-model, and
  agent-runtime. Data-model owns facts; agent-runtime owns transitions; context
  states only that runtime reads the database projection.
- Repeated complete root-view descriptions across architecture, context,
  agent-runtime, and UI. `ui.md` owns layout; `agent-runtime.md` owns root's
  lifecycle capability; `context.md` owns the concise role block.
- Repeated instrumentation history across context, library-grounding, ADR 007,
  and the roadmap. ADR 007 owns the decision; the program-transition PRD owns
  implementation; research keeps the old mechanisms.
- Completed commit chronology and resolved failure cascades in the 1,599-line
  runtime roadmap. Preserve them in a closeout/research record and keep the
  active roadmap scannable.
- Current runtime-reliability `AGENTS.md` starts by saying the paused JVM
  application still exists, then later says it is deleted. Keep only durable
  branch rules/runbook and one short dated state paragraph; roadmap owns the
  changing inventory.
- The roadmap's instruction to update both root `AGENTS.md` and `CLAUDE.md` is
  stale after instruction unification. Update `AGENTS.md`; verify the symlink.

## Recommended architecture edit order

1. Fix the architecture map and vocabulary first: two processes, permanent JVM
   authority, page-specific layouts on one render-unit/feed engine, development
   core-fault escalation, and the architecture/roadmap boundary.
2. Fix `data-model.md` tables and cross-doc ownership. This gives later PRDs
   stable facts and eliminates contradictory names before code changes.
3. Tighten `context.md`, removing output-rewrite and instrumentation-census
   policy while preserving the minimal derived context target.
4. Tighten `ui.md` around one observed unit engine and a dedicated root layout;
   delete downstream and current-status prose.
5. Reconcile `toolkit.md` to real intended namespaces and source-discoverable
   contracts.
6. Reduce `agent-runtime.md` and `observability.md` to stable transitions and
   forensic invariants; move platform/timing/build detail into PRDs.
7. Replace `library-grounding.md` with the current source map, then reclassify
   ADRs 001/005/006 and rewrite ADR 007.
8. Run the active vocabulary/link/doc validator and check that every target
   claim has either current code or exactly one planned PRD.

## Ordered PRD and branch carve-outs

The current runtime-reliability branch should stop being the branch for every
remaining architectural ambition. After it closes its instruction authority,
green baseline, and any inseparable cleanup already in flight, carve the
remaining work as follows. Each folder gets its own tight `AGENTS.md`, roadmap,
research links, branch, proof, and merge before the next begins.

### 1. `database-lifecycle-recovery`

Branch: `codex/database-lifecycle-recovery`

Owns candidate publication failure, optional config apply, clean quiesce,
unexpected-crash idle repair, complete coordinates, local as-of, writable
Datahike branches, restore/undo, blob overlays, and non-autonomous forensic
attachment. It must use maintained Datahike primitives and must not create a
physical-copy versioning path.

Exit: the fresh/converged/config-free/restart/crash/as-of/fork/restore/undo
transition matrix is mechanically and live-proven.

### 2. `reactive-render-units`

Branch: `codex/reactive-render-units`

Owns the one observation/candidate/replay/render/serialize engine; removes
page-specific transition logic; makes active-unit reuse automatic; adds an LRU
only if profiling proves reopen value; gives debug/data/root details real lazy
unit lifecycles; and suppresses identical output.

Exit: helper-indirected reads update existing tabs, unrelated commits invoke no
renderer/SCI work, equivalent tabs share work, and closed details cost nothing.

### 3. `database-browser`

Branch: `codex/database-browser`

Owns the fully specified `seon.db.browser` projections, maintained Datahike
`count-datoms`, cursor-bounded entities/outbound refs/reverse refs/transactions/
provenance/history, and `/data` composition through the unit engine from PRD 2.
It does not recreate inventory or a separate feed.

Exit: every installed attribute and required fact path is inspectable without a
global scan or hidden work for closed details.

### 4. `root-workspace-sessions`

Branch: `codex/root-workspace-sessions`

Owns the dedicated `/` layout, non-recursive fleet cards, derived plan/purpose/
conversation overlays, database-backed per-tab location, cause-message/session
provenance, and root-directed redirect of only the originating tab. It does not
create roster, summary, accessed-at, or presence entities.

Exit: two browser tabs do not fight; root sees the relevant human location and
routes only that tab; one agent update patches only that card/unit.

### 5. `agent-canvas-interaction`

Branch: `codex/agent-canvas-interaction`

Owns deliberate focus/pin rules, all `my.canvas` controls and structured
feedback, transcript-bottom behavior, responsive canvas/right rail, plan
disclosures, focused-surface de-duplication, and the hidden live bar decision.
It consumes the render-unit and session mechanisms rather than adding another
channel.

Exit: buttons, inputs, selects, toggles, forms, rapid input, invalid data,
handler rejection, and throws all produce fast database-backed reactive proof.

### 6. `developer-feedback-and-operator`

Branch: `codex/developer-feedback-and-operator`

Owns the unified CLJ/CLJS/CLJC changed-test selector, dynamic test-root owners,
conservative widening, full logs plus one token-bounded report, Claude/Codex
hook delivery, test graveyard deletion, packaged launch manifest, and one
operator/caller contract. It retains the existing pod/database/operator
runners and Inspect AI; it creates no fourth harness.

Exit: a normal edit gets the smallest sound advisory run automatically; missing
facts widen; the full checkpoint remains green and proves the selector.

### 7. `local-performance-graduation`

Branch: `codex/local-performance-graduation`

Owns cold/warm boot and grown-database profiles, dirty-unit/SCI/serialization/
gzip attribution, event-loop delay, CPU/heap/GC/RSS budgets, the historical
1.4–2.5 GB sawtooth falsification, real-browser journeys, one bounded Inspect
smoke, vocabulary/no-legacy scans, and default-cluster graduation. Coordinate
downstream ACME only after the default system passes.

Exit: idle feeds are idle, cost scales with open changed units, memory stays in
a measured band, and active source/docs/tests contain no superseded mechanism.

### Deferred architecture PRDs after local graduation

- `remote-replication-transport` — WebSocket adapter, attachment/replay,
  cancellation, reconnect, backpressure, immutable-root transfer, and remote
  recovery over the one semantic protocol.
- `thin-client-tauri-mobile` — hosted JVM+Node cluster, phone-focused fact
  ingestion, Tauri packaging, browser/IndexedDB role, and offline semantics.
- `cloud-durability` — GCS/S3 backend selection, acknowledgment/RPO policy,
  mirroring, and retention.
- `execution-isolation` — worker-thread pool and optional microVM backend after
  current runtime semantics and profiling are stable.
- `blob-lifecycle` — compression, branch-aware GC, remote placement, and
  promotion only if the current append-only tier needs them.

## Mechanical validation performed

The repository-native `seon.dev.markdown/validate-file` returned valid for this
report, including frontmatter, document structure, and links.
`git diff --check` also passed. No architecture, source, config, or test file is
modified by this audit.
