---
type: reference
status: active
tags: [reference, agent, architecture]
---

# Conversion wiki — shared stumbling blocks and proven recipes

EVERY lane working on the portable-core conversion, normalize series,
or host/execution tiers READS this before starting and APPENDS any new
stumbling block or recipe (one tight entry, file:line where useful)
BEFORE reporting done. Spec preambles reference this file. Do not
duplicate an entry — extend it. This is the anti-relearning surface;
the anchor stays the state ledger.

## Datahike/schema shapes (cost us two live boot failures)

- **An old→old epoch CAS is a stale-holder fence, not claim arbitration.**
  Two drivers holding the same epoch both satisfy `e → e`; exactly-one turn
  requires an earlier exclusive claim transition (`nil → 1`, or steal
  `e → (inc e)`) and only the winner may receive/thread the claimed epoch.
  Never re-read the current epoch inside a work consumer: that lets a stale
  driver adopt the winner's authority. The loop acquisition and every fence
  wire producer must carry the held epoch end-to-end
  (`loop-cljc-sci-design-2026-07-23.md:215-256`).
- **Owning a pure fence builder does not own its authority input.** Migrating
  `lifecycle/core.cljc` to accept an epoch is incomplete while
  `lifecycle.cljc` still pulls no epoch and calls close/pause/resume without the
  driver-held value. Expand ownership through every production caller; a
  builder cannot safely re-read and adopt the current epoch for a stale driver.
- **Cardinality-many pulls return VECTORS, never sets.** The registered
  Malli `[:set X]` is the shape authority; the ONE decode boundary
  (`seon.db/decode-edn-value` + computed `set-valued-attr?` in
  `seon.db.internal`) reconstructs the set. Never convert at consumers.
- **Component-ref attrs have TWO honest shapes:** transaction refs
  (int/string/lookup) and acquired child-entity maps. Register ONE
  schema admitting both with an explicit `{:seon.db/value-type
  :db.type/ref}` facet (see `:seon.config/model-variants`), and let the
  decode boundary's structural component detection decode trees
  recursively. Never special-case by attr name.
- **Any new acquisition shape MUST be regressed through the FULL boot
  path**: resolve → real-Datahike transact → wildcard pull → decode →
  singleton validation → the consuming policy fn (see
  `config_test.cljs` "pulled-cardinality-many…" and the acquisition
  regression). Accessor-only tests pass while boot dies.
- **Datahike rejects retyping an installed attribute.** Migrations are
  RESET-BOUNDED under the no-lock-in ruling: same names, native types,
  no dual-read, no backfill. The reset installs fresh schema.
- **Wildcard-pulled component trees are not seed transaction data.**
  Pull adds `:db/id` to owners and children; strip both at the consumer
  boundary before copying, or births rekey/link config-owned blocks
  (`src/seon/config.cljs:1051-1103`, `src/seon/agent.cljs:143-149`).
- **:inherit-style sentinels are stored nils in costume.** Absence
  means inherit/default. Reject explicit sentinels with steering.

## Async / platform portability

- **A portable SCI guard needs an actual SCI installation on every claimed
  tier.** The production Bun eval path still calls `cljs.js/eval-str`
  (`src/seon/eval.cljs:1185-1292`), so there is no `:interrupt-fn` installation
  site and a synchronous loop cannot be fuel-preempted there. The retired B2
  probe under `tmp/sci-probe/exec-src/` is not a production seam, and C2 ruled
  out the Bun SCI tier. Specify whether “Bun proof” means a direct portable SCI
  conformance test or grant the engine/cutover owner; never claim the current
  pod is guarded by installing a timer around `cljs.js`.
- **Config-fact ownership includes `seon.config.resolve`, not only
  `seon.config`.** New aero keys cannot become validated singleton datoms by
  editing `config/system.edn` plus `src/seon/config.cljs`: leaf schemas, closed
  section/manifest shapes, the singleton entity, and flattening live in
  `src/seon/config/resolve.cljc:54-63,278-302,675-764`. Grant that owner in any
  unit requiring a new config fact; bypassing it creates an unvalidated or
  silently absent dial. This also blocks the U5 JVM web-render first slice as
  dispatched: its required heartbeat cadence, mailbox depth, connection pool,
  and timeout dials are ruling-27 facts, while the lane owns only
  `config/system.edn`; grant both config source owners before resuming.
- **Relocating a compiled callee does not relocate a symbolic invocation.**
  `seon.agent.turn/render-prompt` currently sends
  `'seon.execution.runtime/render-prompt!` through
  `seon.execution.host/invoke-compiled!`; that function always constructs an
  execution invocation, and the only dispatch entry for the symbol is the
  execution child's `compiled-functions` table. Moving the implementation to
  a pod-owned namespace while preserving that caller therefore still runs it
  in the child. A real in-pod render move must rewire the caller to a direct
  pod call (the deletion audit's ruled seam) or first add an explicitly owned
  pod-local dispatch boundary; do not disguise a namespace move as a process
  move (`src/seon/agent/turn.cljs:335-405`,
  `src/seon/execution/host.cljs:1285-1307`,
  `src/seon/execution/runtime.cljs:683-693`).
- **A scalar success with a ruled error-value failure needs an explicit union.**
  `home-dir` could not remain `[] -> :string` after errors-as-values: register
  the exact string-or-flat-error response and regress the absent environment
  at the public entry (`src/seon/agent/fs.cljs:165,544-556`), or Malli turns
  the repaired failure into a new throw.
- **A `.cljc` rename is not a portability proof.** Require the promoted
  namespace on the JVM immediately: pod-only aliases hidden by conditional
  requires still fail when an unconditional function body resolves them.
  Keep native bodies in the leaf and make the portable load a focused gate
  (`src/seon/agent/web.cljc:1-20,239-359`).
- **A prose-closed request needs `{:closed true}` in its Malli map.** Web fetch
  and search documented closed option sets but their schemas admitted unknown
  resource dials; the portable drift test caught the mismatch
  (`src/seon/agent/web.cljc:69-75,127-133`).
- **Async is contagious upward.** Don't sprinkle reader conditionals
  through logic — push the async/sync difference down to the ONE
  transport/capability leaf; everything above is plain portable
  Clojure. Agent-facing seams already await top-level results, so
  agents never see the difference.
- **Platform residue at edges only**: js/Date, AsyncLocalStorage,
  node:fs/crypto, js/require must live in platform leaf namespaces or
  reader-tag islands, never mid-logic. A `.cljc` is wrong only if it
  CONTAINS unconditional platform code.
- **Same-source or same-artifact are the only non-fragile bridges.**
  Wrapper registries and hand-mirrored APIs drift by construction
  (observed: transact! returning a host-specific report; differently
  nested error envelopes; variadic-vs-request-map shapes).
- **Call shapes are the contract.** When porting, the existing child
  (.cljs) signature/options/error-envelope is authoritative; a port
  that resolves but differs is worse than one that's missing.
- **Replay identity must exist in the frozen public call shape.** The P1c
  exemplar cannot simultaneously keep the child transaction request closed
  (`src/seon/db.cljs:71-80,909-947`), reject its internal request-id as a
  public option, and prove two-call op-id replay: minting at each entry proves
  only an ambiguous-delivery retry, while accepting `:seon.capability/op-id`
  changes the child contract. Settle the public identity key or narrow the
  replay gate before extracting the shared entry function.
- **Inventory effects from the child inward, not the host wrapper
  outward.** Start at the census LEFT symbol, record every arity and
  closed-map key, follow it through its internal choke point to the
  exact native binding, then compare the host counterpart. This exposed
  drift beyond the familiar three cases: omitted database arities and
  resource caps, `db` renamed to host-only `head`, host-only op-id keys,
  and missing surface functions.
- **The replay taxonomy currently has a fourth word.** The seam ruling
  says pure/idempotent/external, while the recovery ruling separately
  says READ-ONLY (`program-synthesis-2026-07-21.md:1679-1685`). A query,
  file read, env read, or process-table read is replay-safe but not
  referentially pure. Flag it as a design decision; never silently call
  reads pure merely to fit three labels.
- **The JVM writer session is a retained connection pool now, not one
  retained channel.** `host/context.clj:192-235,237-447` lazily opens,
  leases, evicts, and replaces pool members; `writer-call!` owns one
  roundtrip and reconnect behavior. Preserve request identity across a
  retry, but do not design the seam around the superseded single-channel
  description.
- **WP-S2 kill recovery is not invocation cancellation.** It can
  TERM→KILL an exact managed generation, but the current host-lane
  `kill!` closes only its UDS stream and `ensure host` preserves a live,
  converged workload (`src/seon/execution/host.cljs:589-605`,
  `script/seon/dev/process.clj:2645-2658`). A shared Bun worker needs an
  explicit deadline → exact-generation drain/force → interrupted-receipt
  recovery path; “both kill modes” means workload- and owner-death
  recovery, not in-thread hot-loop preemption.

## Testing/proof recipes

- **Compiled gates cannot see live-boundary failures.** Every unit that
  changes schema, acquisition, renders, or process behavior gets a
  reset-boundary boot + live proof. Prompt-side render changes need the
  restart/admission boundary to appear in real prompts.
- **Receiptless probes don't record.** Orchestrator eval probes without
  a turn-id are engine-only: no receipts, no corpus, NO replay — never
  use them to "prove" corpus replay.
- **Multiple awaits in one eval form hang** at the MCP timeout — one
  awaited op per form, or a ^:async fn.
- **bin/test-writer doesn't retain a log**: always redirect full gate
  output to a file (a lost intermittent test name costs a W10 row).
- **Env-coupled cljs tests**: a focused-build failure that's green in
  the integrated run is usually schema load-order, not your bug
  (my.plan-test precedent) — verify in the full run before chasing.
- **Put dual-tier `.cljc` tests below a namespace directory.** The writer
  runner discovers `test/seon/**/_test.clj[c]`, while the CLJS runner follows
  namespaces; `test/seon/db/portable_test.cljc:1` is visible to both, unlike a
  new root-level `.cljc` file that the retained writer discovery can miss.
- **Public replay identity hashes logical intent, not the moving source
  database.** A second `:seon.capability/op-id` call normally starts from the
  new head, so `src/seon/db/protocol.cljc:798` excludes `:seon.db/db` from the
  receipt hash while preserving explicit `:seon.db/expected-db`; transport
  `::request-id` remains private roundtrip identity.
- **A JVM portable core validates through its bound committed projection.**
  Source declarations are process-global and leak across sequential test
  hosts; `src/seon/host/context.clj:236` supplies one projection accessor and
  `src/seon/db/internal.cljc:15` scopes the shared transforms to that immutable
  value. An empty fake/bootstrap database explicitly disables domain
  validation until its schema declaration transaction establishes a
  projection; never activate one host's projection process-wide.
- **Loading a portable capability must not publish child-only schemas on the
  JVM.** Requiring message/lifecycle from the host installer eagerly added
  their application declarations to the process-global schema population; a
  minimal test host then rejected an unrelated seed because
  `:seon.agent.lifecycle/lifecycle-result` referenced an absent
  `:seon.derive/state`, cascading into 198 writer failures. Keep executable
  `.cljc` cores loadable while making child application-schema registration a
  CLJS-only load effect (`src/seon/agent/{message,lifecycle}.cljc`).
- **Normalize optional component nils before portable validation.** Malli
  default decoding can materialize absent optional keys inside acquired maps;
  `src/seon/db/internal.cljc:287` recursively omits those nil map entries so
  validation and transport see the database's one representation of absence.
- **CLJS dynamic leaf bindings end before Promise continuations.** A JVM host
  can use the synchronous closures returned by `src/seon/db.cljc:196`, but an
  async Bun test must install its leaf for the complete Promise lifetime and
  restore it before calling `done` (`test/seon/db/portable_test.cljc:321`).
  Never let a fake leaf escape into later namespaces.
- **Reconcile architecture from public schemas and dual-tier tests, not old
  envelope prose.** The landed database boundary requires flat
  `:seon.error/message` + `:seon.error/kind` with optional data
  (`src/seon/db.cljc:27-34`), and the shared test rejects a nested
  `:seon/error` wrapper (`test/seon/db/portable_test.cljc:267-271`). Sweep the
  target docs for old specialized/nested examples whenever a family is ported.
- **A receipt key does not stabilize generated transaction intent by itself.**
  Message allocation generates candidate ids before `seon.db/transact!`; a
  later call with the same `:seon.capability/op-id` can therefore present a
  different candidate manifest and fail the writer's intent-hash check instead
  of replaying. An idempotent generated-id capability must retain or derive the
  exact candidate manifest from the op-id before claiming two-call replay
  (`src/seon/agent/message.cljc`, `src/seon/db/id.cljc:1341-1360`).

## Process/operator

- **A landed seam does not close arbitrary-result ambiguity.** Flat
  `:seon.error/message`/`:seon.error/kind` errors and portable database calls
  can coexist while collision-capable successes remain bare:
  `src/seon/db.cljc:522-541,557-591` still projects query/pull success directly.
  Triage the public result discriminator separately from transport-envelope
  portability.

- **Issue closure needs evidence at the issue's full acceptance boundary.**
  A current source line may disprove the original local mechanism while the
  note still owns a live, cross-surface, or artifact proof. Classify that case
  UNCLEAR with the exact missing probe (or REAL when the broader mechanism is
  still absent); do not archive merely because a focused implementation
  comment says the source half landed.

- **A launch manifest can disable a database-selected execution tier during
  reconciliation.** A green SCI evaluator plus a correct batch router still
  yields no host invocation when `:seon.config.execution/host-tier?` is false
  and agent eval-socket facts have been retracted. Probe the config fact and
  socket facts before blaming evaluation; after deliberately enabling the
  database fact, restart without reapplying a manifest that declares false.
  At pod write-back, a non-empty executable batch with zero recorded attempts
  must become a recorded core fault and flat `:seon/error`, never an ordinary
  zero-form turn (`src/seon/agent/turn.cljs:440-490`).

- **Mixed-tier selection is per parsed eval batch, not per agent arc.** The
  router derives Bun locality from executable symbols, loader forms, and
  projected require edges naming `seon.packages.js.*`; ordinary quoted data
  and strings do not select a tier (`src/seon/execution/host.cljs`). Every
  other eval batch follows the agent's existing SCI host coordinate. Durable
  continuity crosses tiers through corpus and database facts; `result/<id>`
  stays process-local, so a cross-tier reference returns steering to persist
  ordinary data or rerun its producer on the selected tier. Never add a
  package name list or a host-to-Bun leaf to implement this routing policy.

- **A cluster package corpus has no ingestion door yet.** The cluster operator
  creates only native manifests (`script/seon/dev/cluster.clj:75-95`), while
  CLJS program acquisition admits database namespace sources written by the
  REPL process (`src/seon/execution.cljs:342-356,669-708`). A wrapper file under
  `packages/corpus` never reaches `guarded-load*`'s authored-source map and an
  absent require rethrows at `src/seon/eval.cljs:884-885`. Do not directly eval
  the file to manufacture REPL provenance; WP-W must transact it through the
  one corpus authority.

- **Package corpus locality is a database join, not a loader path.** Stamp each
  ordinary `:seon.ns`/`:seon.fn`/`:seon.schema` row with the installed ledger
  ref (`:seon.packages/package`), acquire it alongside REPL-proven rows, then
  require exact equality between the row's namespace and the ledger's
  `:seon.packages/as` plus the computed `seon.packages.js.` prefix
  (`src/seon/packages.cljc`, `src/seon/execution.cljs`). Retraction removes the
  stamped corpus entities before the ledger entity; absence of the ref/ledger
  join makes another cluster unable to acquire the wrapper without a loader
  filesystem branch.
- **A registered package provenance ref is not necessarily installed.** A
  cluster with no package rows has no Datahike schema for
  `:seon.packages/package`; naming it unconditionally in the raw acquisition
  query fails every agent turn before eval. Install the provenance schema at
  reconciliation or omit the package query clauses until installed—never
  assume process-local `schema/register!` makes a database attribute queryable
  (`src/seon/execution.cljs:343-366`, live E2E 2026-07-22).

- **The CLJS UDS transport has no public server-side framed-text seam.**
  `seon.db.transport.uds/connect-stream!` exposes the existing four-byte
  text-payload codec only as a client; its parser and frame encoder are private
  (`src/seon/db/transport/uds.cljs:112-200,716-824`). A supervised Bun worker
  cannot serve the execution protocol over that codec from an owned
  `src/seon/execution/**` namespace without either duplicating framing or
  changing the protected transport owner. Grant the transport owner a public
  server-session seam (or explicitly grant the worker unit that extraction)
  before implementation; never copy the parser/encoder into the worker.
- **Same-artifact identity and execution-request identity are distinct today.**
  The authorized worker argv executes `out/client/main.js`, whose process
  record can prove the client/application artifact digest, while protocol-v3
  startup and `ready-message-valid?` currently exchange the separate execution
  build id/digest (`src/seon/execution.cljs:83-142`,
  `src/seon/execution/host.cljs:401-416`). A worker-ready contract must name
  which descriptor fields prove the serving artifact and which digest pins a
  compiled invocation; silently echoing the child execution digest from the
  client artifact does not prove same-artifact identity.
- **A same-artifact worker needs two explicit owner-level control decisions.**
  The current managed graph is closed over watcher/writer/host/pod and the
  launch descriptor publishes only the JVM host eval socket
  (`script/seon/dev/process.clj:28-31,200-223`,
  `src/seon/launch.cljc:102-104`). A NEW Bun worker therefore cannot be wired
  only in `process.clj`: its UDS coordinate/generation must enter the launch
  contract consumed by the pod. Likewise, WP-S2's exact-generation drain is a
  private JVM operator path (`process.clj:1249-1294,1510-1566,1621-1660`);
  the pod has no typed way to invoke it. Settle whether the bridge is an
  operator command/service or another existing authority extension before
  implementation—never expose the containment socket path and let the pod
  speak its private line protocol directly.
- **status shows owner AND workload pids** — kill drills target the
  WORKLOAD; killing the owner tests a different (also real) mode.
  Process identity is (pid, start-instant); pid alone lies (macOS
  reuse observed within seconds).
- **Isolated clusters/drives end with their own operator's `down`**;
  a leftover watcher blocks the shared build role.
- **Never shell-& a codex run**, including inside harness-backgrounded
  compounds — the notification dies with the shell; use the harness
  background directly and watch the summary file.
- **Two lanes never own one file — including TEST files.** Specs list
  the other live lanes' grants as PROTECTED explicitly. Entangled edits
  force combined commits.
- **A sequencing grant does not make an already-dirty shared file safe to
  commit.** If an earlier lane has uncommitted hunks in a file that the next
  lane must release first, a path-limited commit would absorb both units.
  Stop at the overlap and have the earlier lane commit or hand off its exact
  hunks; do not reverse another lane's worktree edits or substitute an
  index-only commit for the repository's path-limited-commit rule.
- **Path-limited commits always**; add new untracked owned files
  explicitly in the same commit (a missing test-support file made every
  intermediate writer gate unreproducible for a day).
- **Skills: edit seon-skills/ (canonical), then `bin/seon skills
  sync`** — .agents/.claude trees are generated; the operator gate's
  drift check catches direct adapter edits.
- **bb tooling loads via bb.edn** — never bare `--classpath`; new deps
  used by bb-loaded namespaces must be pinned in bb.edn too (parinferish
  0.8.0 precedent).
- **The mechanism embodies idempotency; metadata does not duplicate it.**
  `my.blob/put!` and `concat!` derive durable identity before publication
  through `seon.content-hash/sha-256`. Identical bytes address the same archive
  path and database identity, so no separate idempotency annotation or receipt
  table is needed.
- **Absent effect metadata is conservatively non-replayable today.**
  Unexpected-exit recovery terminalizes the eval receipt and never redispatches
  an in-flight invocation (`src/seon/runtime/recovery.cljs`,
  `src/seon/execution/host.cljs`). Prove this with the host-death
  one-dispatch regression; do not add an unused parallel classifier.
- **An injection seam must own the whole native publication vocabulary.** A
  partial map that injects only fsync/rename/transact still leaves tests and
  alternate leaves coupled to direct `node:fs`, `node:path`, and UUID calls.
  Extend that same map with open/close/mkdir/read/write/exists/unlink, path
  algebra, and random-id operations; do not introduce a second effects map.
- **A cutover-death namespace can contain surviving contracts.** The current
  execution/eval owners mix shared protocol, durable receipt, rendering, and
  platform codecs with the per-agent child/self-host engine. Inventory and
  repoint those consumers before deleting bands: `src/seon/execution.cljs`,
  `src/seon/execution/host.cljs`, and `src/seon/eval.cljs` are not honest
  whole-file deletions merely because their engines die.

## Design rulings that bind conversions

- One mechanism; fix in place; delete the superseded path.
- Computed structural rules, never literal name lists (set-valued-attr?,
  the census's base-resolved bidirectional check).
- Errors as values with steering that names the governing config key.
- The capability seam carries effect-class metadata (pure/idempotent/
  external) — replay classification and portability share one boundary.
- Owner tiebreaker: experimentability + reasonability win seams.

## Issue-triage recipe

- **Classify against the owning mechanism before trusting an issue's old
  filename or acceptance.** A landed partial repair is not closure when the
  note retains an unproved live boundary; conversely, a child/self-host defect
  belongs to the explicit post-P4 deletion unit when every cited owner dies at
  cutover. Use current symbol/file evidence for independent work, an exact
  planned unit for dissolution, and state the one live probe when neither is
  decisive.
