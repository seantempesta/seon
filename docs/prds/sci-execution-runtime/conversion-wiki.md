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

- **Strict committed-contract admission needs provenance in the projection
  input.** `seon.schema/projection-from-rows` currently receives only
  `[identity form-string]`; it cannot preserve core/opaque exceptions while
  rejecting durable agent contracts without a forbidden namespace/name
  heuristic. Carry transaction-derived admission source through every
  acquisition producer (`host/context`, runtime admission, execution, and web
  value) into the one projection compiler before enabling R31 on reload
  ([[../../../seon/issues/committed-contract-admission-lacks-source-provenance]]).
- **Malli spell-checking and the wrong-namespace hint are complementary.**
  `me/with-spell-checking` classifies similar extra keys on closed maps, while
  the retained `seon.error.instrument/hint-for` covers the previously-proven
  same-name/different-namespace miss that Malli's distance threshold omitted.
  Compose spell-checking before `me/humanize`, keep the fallback, and bound the
  resulting headline through the existing steering head; do not delete either
  path ([[../../../seon/issues/instrument-report-omits-malli-spell-checking]]).
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

- **A transport leaf cannot widen claimant phase ownership by itself.** The
  portable eligibility predicate already recognizes `:llm`, but the JVM
  claimant advertises only `:eval` and dispatches only eval steps
  (`src/seon/agent/{loop/core.cljc:56-67,driver/host.clj:252-300}`).
  Durable attempt orchestration, prompt-blob recovery, reply-blob publication,
  and cursor advancement still live in the pod turn leaf
  (`src/seon/agent/turn.cljs:947-1114`). Also, the existing watchdog arms only
  inside `seon.host.invoke/execute-invocation!`; a provider call placed directly
  on the claimant vthread would not inherit it
  (`src/seon/host/invoke.clj:29-43,106-134`). Before implementing an isolated
  JVM HTTP adapter, first assign one owner to expose the durable portable LLM
  phase plus the existing deadline ceremony at the claimant step boundary;
  otherwise the adapter is unreachable and its interrupt proof is vacuous.
- **A portable SCI guard needs an actual SCI installation on every claimed
  tier.** The production Bun eval path still calls `cljs.js/eval-str`
  (`src/seon/eval.cljs:1185-1292`), so there is no `:interrupt-fn` installation
  site and a synchronous loop cannot be interpreter-step-preempted there. The
  retired B2
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
- **Provider objects become data exactly once, at the native leaf.** Keep SDK
  construction, SDK error classes, and `js->clj` in the CLJS adapter; pass the
  resulting ordinary map to a `.cljc` interpreter. The dual-tier test must call
  that interpreter from identical response bytes, while the existing public
  CLJS function remains the SDK-object compatibility edge
  (`src/seon/ai/{openai_compat,anthropic}.cljs`,
  `test/seon/ai/portable_test.cljc`).
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

- **A fixture classpath is not a standalone artifact load path.** The writer
  test runner composes `:host`, so SCI-backed Malli predicate compilation can
  pass there while the R26 standalone writer exits before readiness. Keep
  symbolic predicate forms durable, but replace registered core predicate
  symbols with their trusted function values at the Malli compilation
  boundary. Regress the scar by rebuilding the actual standalone jar, proving
  it contains no SCI, and loading/compiling the protocol predicate schemas in
  a child JVM whose classpath is only that jar
  (`test/seon/writer_standalone_schema_test.clj`).
- **Compiled gates cannot see live-boundary failures.** Every unit that
  changes schema, acquisition, renders, or process behavior gets a
  reset-boundary boot + live proof. Prompt-side render changes need the
  restart/admission boundary to appear in real prompts.
- **Receiptless probes don't record.** Orchestrator eval probes without
  a turn-id are engine-only: no receipts, no corpus, NO replay — never
  use them to "prove" corpus replay.
- **Sweep inline authored fixtures at the invocation boundary after admission
  changes.** Durable definitions are source strings scattered across writer
  suites, so a filename-local update misses siblings. Compute candidates with
  `rg` over `test/` for `eval-batch!`, `invoke-batch!`,
  `:seon.repl/source`, and quoted `(defn` forms; then inspect each call's
  `:seon.eval/starting-ns` against
  `seon.host.record/transient-ns-syms`. Add the complete concrete
  `:malli/schema` to every definition expected to succeed. Direct SCI-only
  evaluations, stored source-row literals, transient probes, and definitions
  expected to be rejected are not durable-admission fixtures.
- **Multiple awaits in one eval form hang** at the MCP timeout — one
  awaited op per form, or a ^:async fn.
- **bin/test-writer doesn't retain a log**: always redirect full gate
  output to a file (a lost intermittent test name costs a W10 row).
- **Env-coupled cljs tests**: a focused-build failure that's green in
  the integrated run is usually schema load-order, not your bug
  (my.plan-test precedent) — verify in the full run before chasing.
- **Retained test visibility is a union, not a directory convention.** The
  writer runner recursively claims every `test/**/*_test.clj[c]` namespace
  except the operator-owned `test/seon/dev` root and structurally CLJS-only
  namespaces; Shadow claims `.cljs`/`.cljc` namespaces matching `-test$`.
  `test/seon/dev/test_roots_test.clj` computes the union and fails with every
  orphan, so root-level and nested portable tests are equally visible. Keep
  exclusions structural—never add a filename list
  (`script/seon/dev/test_roots.clj`).
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

- **Lifecycle membership is a graph projection, never a hand list.** The
  fourth stale member list found on 2026-07-23 omitted web-render from
  shutdown while still reporting success. Derive membership, dependencies,
  reverse shutdown order, reader rebuilds, log selection, and absence evidence
  from the one owned-process graph, then require requested targets to equal
  returned result identities. Exact recorded `(pid,start-instant)` death makes
  a generation consumable even after PID reuse; a live exact identity remains
  protected (`script/seon/dev/process.clj`, `script/seon/dev/cli.clj`,
  `fe5e289b9`).

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
- **A cross-owner integration gate needs a source freeze, not merely separate
  test namespaces.** A host test still seeds and transacts through the live
  writer sources. While the writer lane has uncommitted admission changes, a
  host-focused gate can fail in writer setup before the host mechanism runs.
  Preserve that log as interference evidence, finish pure/compile proofs, and
  rerun the integration gate only after the writer owner reaches a coherent
  commit; never weaken the host assertion or attribute the setup failure to the
  guarded door.
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
- **A dedicated JVM interest session can stay on the public UDS seam.** Open
  one retained session with `open-session!`, derive streams from its public
  channel, and use `read-frame`/`write-frame!` for a single reader vthread,
  correlated control responses, and committed events. After reconnect,
  reinstall every typed `listen-request` and synthesize one
  `resynchronization-event` per still-current handler. No writer or transport
  change is required (`src/seon/db/host.clj`, U5 regression
  `seon.db.host-interest-test`).
- **The Datastar JVM SDK has no SSE-comment operation.** Heartbeats must not
  hand-roll framing merely to reproduce a comment. An empty named custom event
  sent through `send-event!` preserves SDK framing/compression, stays inert to
  Datastar, and can retain the existing “skip while draining; never displace
  state” policy. Record this wire-shape difference explicitly in parity
  evidence.
- **Babashka does not accept `java.nio.file.Path` directly in `slurp`.** The
  operator-side config resolver must convert `babashka.fs/path` results to
  strings before reading render-context files. Otherwise `bin/seon up` fails
  before process reconciliation and the cluster tests error while opening
  `AGENTS.md`; never bypass the supervisor to obtain a live proof
  (`tmp/orchestrator/u5-gate-live-up.log`).
- **A manifest section can be absent while its registered schema still blocks
  fresh database open.** Database opening installs the complete schema graph,
  so selecting a minimal manifest does not avoid an unstorable canonical
  alias. Resolve bare qualified-keyword forms recursively through the complete
  canonical form population before deriving the Datahike declaration; retain
  a traversal chain so cycles and missing aliases fail legibly. Preserve
  terminal storage semantics such as `:seon.db/ref`; do not inline shared
  shapes, weaken config acquisition, or bypass the operator
  ([[../../../seon/issues/archive/config-schema-alias-blocks-fresh-cluster-open]]).
- **A database-backed member that requires selected config must depend on the
  config-reconciling pod for a fresh cluster.** Writer readiness alone cannot
  guarantee config singleton facts exist. Put the additive member after pod
  readiness in the managed graph so its fail-closed config acquisition works
  on both fresh and retained databases.
- **Writer idempotency needs both an active transport join and an in-flight
  transaction cache.** The active-request map sees a duplicate request id
  before `recover-current` can observe its durable receipt. A same-intent
  transaction retry must join the original completion and receive its
  canonical response; a different logical hash remains a conflict. Keep the
  process-local promise map only as a cache, remove its entry after completion,
  and leave committed receipt transactions as the recovery authority
  (`src/seon/db/writer.clj`, U3 recovery regression).
- **Allocation exclusivity is discoverable from transaction structure.**
  Generated candidates and `seon.db.id/transaction-tempids` identify
  transactions whose preparation reads the pre-commit database value. Mark
  only those executor submissions serialized; ordinary mutations pipeline
  into Datahike's LocalWriter. Never preserve allocation safety with request-id
  prefixes, operation-name lists, or a second admission path.
- **Four file-backed databases do not imply four times aggregate commit
  throughput on one device.** U3 measured real cross-database parallel
  progress but only 1.55–1.96x aggregate scaling as offered load rose. Use
  separate low-load and saturated Probe C points to distinguish executor
  serialization from shared persistence contention, and retain the raw
  ratios instead of treating the research estimate as measured fact
  ([[../../../seon/issues/shared-file-persistence-limits-cross-database-writer-scaling]]).
- **Datahike's expected-basis check currently loses its uncommitted basis
  under pipelining.** The LocalWriter processing loop threads `:db-after`, but
  before the next apply it copies the still-committed connection's older
  `:max-tx` onto that value. Two queued transactions with the same
  `:datahike/expected-basis-t` can both commit. Fix the maintained Datahike
  processing loop and add the direct two-request regression; never restore
  Seon's per-database mutation serializer or invent a Seon-side head cache to
  conceal the dependency defect
  ([[../../../seon/issues/datahike-local-writer-rewinds-uncommitted-basis]]).
- **A held claim still needs a transaction when a wake carries new input.**
  Returning the already-held epoch without writing strands the message outside
  `:seon.agent.run/consumed-input`; calling the retired epoch-less `renew!`
  instead adopts authority the caller does not hold. The held transition emits
  the same pointer+epoch-fenced beat transaction and adds the consumption edge.
  A later scan derives the oldest uncovered inbound edge so a wake that raced
  another tier's live custody is not lost (`seon.agent.run.core/claim-plan`,
  `seon.agent.driver/acquire-run-state!`).
- **Cursor resume must consume the persisted artifact, not rerender an
  equivalent-looking input.** Prompt rendering includes legitimate
  wall-clock/host observations. Re-rendering after `:rendered` can therefore
  change bytes and violate the attempt's config digest. Store once, then split
  the exact prompt blob at the system boundary for every resumed attempt
  (`seon.agent.turn/llm-phase!`).
- **Register an unstarted vthread before starting it.** A virtual driver thread
  can complete before the handle map records it; installing the handle after
  `.start` leaves a stale entry that suppresses every later dispatch for that
  run. Create with `Thread/ofVirtual().unstarted`, win the map CAS, then start;
  identity-check cleanup (`seon.agent.driver.host/start!`).
- **Validate config-query cardinality before `every?`.** `every?` over the
  empty values of a missing policy is true. The guard door then receives nil
  interpreter-step budget and collapses every host invocation with an
  implementation NPE. Require
  the complete five-field row before validating positive values; remain loud
  when the database lacks the config facts
  ([[../../../seon/issues/guard-policy-empty-query-passes-vacuous-validation]]).

## Design rulings that bind conversions

- One mechanism; fix in place; delete the superseded path.
- Computed structural rules, never literal name lists (set-valued-attr?,
  the census's base-resolved bidirectional check).
- Errors as values with steering that names the governing config key.
- The capability seam carries effect-class metadata (pure/idempotent/
  external) — replay classification and portability share one boundary.
- Owner tiebreaker: experimentability + reasonability win seams.

## U4 render-purity recipes

- **A file fingerprint is a component fact, not a map-valued singleton
  shortcut.** Store one component entity per path with a namespaced path
  identity and SHA-256 attribute. Both config callers provide exact UTF-8
  observations to the pure resolver. Render re-reads the file only to verify
  those bytes and returns a flat `:core-bug` on missing/mismatch; it never
  silently renders divergent content.
- **A cross-process byte gate must diff nonempty bytes.** Have the focused
  regression write the exact rendered context to an ignored artifact, run the
  same selector in two fresh Bun processes, copy each artifact, assert both
  byte counts are nonzero, then `diff -u`. Diffing absent log markers produces
  an empty diff and is a vacuous pass.
- **The canonical watcher can make a live proof depend on an unrelated test
  build.** If client/execution compile but the operator refuses readiness
  because the shared `:test` build is broken in an owner-protected lane, retain
  both operator and watcher logs, attribute the exact test symbol, and shut
  the named operator down. Do not bypass `bin/seon` or patch the protected
  spine merely to manufacture a live result.

## Issue-triage recipe

- **Classify against the owning mechanism before trusting an issue's old
  filename or acceptance.** A landed partial repair is not closure when the
  note retains an unproved live boundary; conversely, a child/self-host defect
  belongs to the explicit post-P4 deletion unit when every cited owner dies at
  cutover. Use current symbol/file evidence for independent work, an exact
  planned unit for dissolution, and state the one live probe when neither is
  decisive.

## Test-integrity widening (2026-07-23)

- **Classify JVM eligibility from the selected namespace form.** The runner
  reads `.cljc` namespace forms with the `:clj` feature, excludes the operator
  subtree, and rejects unconditional CLJS/Google Closure or JavaScript-module
  requires. The only current content-derived exclusion is
  `test/my/blob_test.cljc`: its namespace unconditionally imports `node:*` and
  `cljs.test`, and its fixture body uses Promises and `js/*` interop; Shadow's
  `-test$` scan still owns it. Grounding: Clojure 1.12.0 reader conditionals
  (`deps.edn`), Shadow CLJS `c98bf60f` namespace selection
  (`reference-code/shadow-cljs/src/main/shadow/build/test_util.clj`), and the
  existing shared consumers in `script/seon/dev/changed_test.clj:237-238,
  282-284`. Adding another exclusion means making the file's platform coupling
  explicit enough for this same predicate, never naming the file.
- **Widened discovery can expose tests whose runtime has already died.**
  `test/seon/authority_density_test.clj` and
  `test/seon/execution_process_test.clj` both spawned per-agent Bun execution
  children. R26/R28 removed that topology; the accepted deletion inventory
  already classified the execution-process suite for deletion, and its fixture
  issue was triaged as dissolving with the child path. Delete such a whole
  dead-runtime assertion instead of repairing its bespoke build/fixture;
  retain the writer concurrency, host conformance, claim-driver, and portable
  core tests that exercise the surviving contracts.
- **Writer eligibility cannot see through a JVM-looking test namespace.**
  Discovery intentionally classifies the selected `ns` form, not an unloaded
  transitive require graph. A test of pure build inventory classification must
  target `seon.dev.program-inventory` under the operator-owned test root; it
  must not require the `seon.client.indexing` analyzer leaf and accidentally
  pull `cljs.env` onto the writer classpath. The build publishes inventory
  data; writer-side consumers read that artifact and never load the analyzer.

## U2 claim-driver falsifier scars (2026-07-23)

- **A process claim does not serialize fibers inside that process.** The
  `:held` claim transition must accept the same claimant identity so a retained
  driver can renew its lease. Consequently, a wake listener and scan listener
  in one pod can both pass the durable fence and create distinct turns at the
  same epoch unless the claimant retains one addressable local handle per run.
  Keep that Promise/thread map strictly process-local; database claim and cursor
  facts remain authority. Both pod and JVM leaves now implement this R1 rule
  (`seon.agent.driver.pod/dispatch-run!`,
  `seon.agent.driver.host/start!`).
- **Portable attribute registration is not by itself fresh-schema
  installation.** Reset initialization derives installed attributes from the
  canonical entity schemas. Moving eval receipt registrations into the
  portable receipt owner made JVM loading honest, but
  `:seon.eval/progress?` still remained absent until the canonical
  `:seon.eval` entity schema referenced it. A reset-boundary pull is the
  falsifier; a populated database or namespace-registry assertion is not.
- **A kill drill's fixture must encode its claimed cardinality.** The ordinary
  run budget legitimately permits sequential follow-up turns after publication.
  A receipt assertion demanding one turn therefore needs a one-turn demo agent
  (or another explicit close outcome); an unconstrained stub conversation is
  not evidence of duplicate effects.
- **Do not complete a cross-tier proof across a broken shared artifact.** The
  final reset rerun was blocked when an independent render portability edit
  left `src/seon/render/value.cljc` with an unmatched delimiter. Preserve the
  already committed cursor/receipt evidence, name the exact compiler error, and
  stop the owned cluster. Never patch or commit the other lane's in-flight
  source to manufacture U12 or full-CLJS evidence.

## Portable durable LLM phase (2026-07-23)

- **The durable phase owns receipts; transports own only wire I/O.** Before a
  provider call, the portable phase commits the open attempt with the held
  pointer/epoch fence, frozen config digest, ordinal, and absolute deadline.
  The platform transport receives that frozen request plus the remaining
  request timeout. It never creates, settles, or repairs attempt rows.
- **Successful provider bytes are not terminal until blob publication is
  addressable.** Reply publication calls the bound `my.blob` capability. If
  that capability fails, the attempt remains `:open`; takeover records it
  `:crashed` and advances the ordinal. Committing `:success` without a reply
  link would strand the cursor and falsely claim durable evidence.
- **JVM LLM eligibility is the intersection of installed leaves.** A claimant
  advertises `:llm` only when both a real transport function and the JVM blob
  leaf are present. Absence is scheduler policy data, not a transport stub.
- **Compose HTTP and custody deadlines at the claimant boundary.** The
  portable phase derives the minimum of the run deadline and frozen attempt
  horizon. A JVM transport must apply the resulting milliseconds to
  `HttpRequest.Builder.timeout`; the claimant independently schedules an
  interrupt of its retained driver virtual thread. The eval session watchdog
  is invocation-private and is not reused for provider I/O.

## Bug-chase persistence hardening (2026-07-23)

- **Every database leaf must bind the retained committed projection.** A leaf
  that returns nil from `schema-projection`, discards cache refreshes, or
  reports validation disabled silently defeats the shared transaction
  admission mechanism. Mirror the host context's projection-state contract:
  return only a projection with forms, replace the cached generation
  atomically, and derive validation from those committed forms. Prove the leaf
  rejects an enum violation and a positive-int violation before its transport.
- **Guard the final public eval envelope, not only the ordinary SCI result.**
  Terminal preflight, read, timeout, repair, and recording paths can replace or
  extend an earlier `wire-safe-value` result. Extract host-only live values
  first, then apply the same guard to the envelope that is persisted and
  returned. This keeps Transit admission in one mechanism and turns a nested
  `clojure.core$_STAR_` value into a flat tier-local error without closing the
  execution session.
- **A protective portable literal graduates through all four config layers.**
  Register one described leaf shape, reference it from a closed concern
  section, reference it from the singleton entity, and resolve the manifest
  value into a flat fact. The consumer acquires that fact and carries no
  numeric fallback. Descriptions record units, retained-policy provenance, the
  protected resource, and the exact key surfaced when the limit is absent or
  fires. This applies equally to retry waits, capability defaults, parser
  ceilings, and JVM claimant invocation bounds.

## U7 render and ctx portability scars (2026-07-23)

- **Classify a stored symbol before resolving it.** A trusted renderer lookup
  is a literal compiled symbol-to-function table. An authored symbol enters
  only the injected guarded SCI door. Never try a dynamic lookup first and
  classify the result afterward: a hostile core-looking symbol would inherit
  unguarded compiled authority.
- **A guard steering error is the render-slot value.** Do not stringify
  `:budget`/`:timeout` into ordinary prose inside the walker. Carry the flat
  error value through the slot so the caller retains kind, config key,
  interpreter-step, and invocation-class evidence; view formatting happens
  outside the door.
- **Port config reads as reads of the threaded singleton.** The JVM render
  path reads immutable configuration map facts with the same shipped defaults.
  A CLJS compatibility call may retain the existing accessor for redefinition
  tests, but no promoted namespace may require the pod-only config authority
  on the JVM.
- **A promoted helper must pass the production JVM classpath, not only a test
  alias.** `seon.repl.parse` is `.cljc`, but its rewrite-clj dependency is not
  in the writer production alias. The scratch-definition predicate therefore
  belongs beside the already-portable namespace-source reader. The failed
  production require was useful evidence that a filename suffix is not a
  portability proof.
- **One `.cljc` assertion is the cross-runtime byte oracle.** Run the same
  core block, same ordinary database value, and same expected string under
  Shadow and the writer test runner. Pair it with fresh JVM require gates and
  the existing nonempty stage-5 context-byte gate; none substitutes for the
  others.

## U8a native-leaf and host-binding scars (2026-07-23)

- **Re-read a recorded block with its namespace alias table.** Raw source
  retains reader conditionals and auto-resolved keywords. Selecting the JVM
  branch without binding the source aliases can turn `::db/db` into a keyword
  in the reader's ambient namespace, while printing the already parsed form
  leaves `#?` syntax for SCI. Bind `tools.reader/*alias-map*`, select `:clj`,
  and only then hand the resulting form to the host interpreter.
- **A computed registry census needs literal wrapper declarations.** Runtime
  construction from a map of names can serve calls correctly but cannot be
  proven by the source census. Declare each promoted public symbol literally
  in `register-host-capabilities!`, load the real landed implementation
  closure, and reinstall those same shared read-only vars through
  `install-registered-wrappers!`.
- **Acquire config before normalizing a native request.** The JVM shell and
  web wrappers read the invocation's already-acquired
  `:seon.config/configuration`; the pure core turns that singleton plus the
  child request into native request data. A platform leaf must not retain an
  old numeric default after the core promotes that value to a database fact.
- **Content identity crosses tiers; archive mechanics do not.** The JVM blob
  leaf uses the portable SHA-256 result as both lookup identity and
  idempotency receipt, writes the same `<first-two>/<hash>` archive path, and
  transacts the same projection. Durable channel force and atomic rename are
  platform mechanics, not a second blob contract.

## U6b JVM LLM HTTP leaf scars (2026-07-23)

- **A claimant label is not a process tier.** Join the persisted
  `:seon.agent.run/claimant` PID/start instant to the operator's exact workload
  record before attributing a provider receipt to Bun or the JVM. The claim
  driver retains an epoch while its leaf remains eligible, so leaving
  `:seon.agent.driver.capability/llm` on the pod after the JVM leaf landed made
  the pod structurally keep render → attempt custody. Retire the superseded
  capability and its dispatch arms; the existing eligibility check then
  releases the run for JVM acquisition without a routing table
  (`src/seon/agent/driver{,/pod}.clj*`,
  [[research/claimant-llm-transport-path-audit-2026-07-24]]).
- **An interrupt can surface while consuming the response body.** JDK
  `HttpClient.send` declares `InterruptedException`, but an SSE
  `InputStream.read` may instead surface `IOException` while retaining the
  thread's interrupted flag. Check that flag and rethrow
  `InterruptedException`; otherwise the claimant watchdog's deadline becomes
  a generic transport failure instead of the one flat timeout envelope.
- **SSE cancellation belongs at the portable parser predicate.** Fold decoded
  `data:` events into provider-shaped state and invoke the same
  `seon.repl.parse` first-top-level-close plus parsed-form confirmation used by
  the pod. Closing the response stream immediately on confirmation cancels the
  HTTP exchange. Because the provider's final usage event is then absent,
  replace even partial usage with the shared estimate and flag it
  `:seon.ai/estimated? true`.
- **One process client makes its construction facts restart-bound.** Resolve
  connect timeout and response-byte ceiling as cluster config facts, but build
  the process-shared client only once. If a later request presents a different
  connect timeout, fail loudly and require claimant restart rather than
  silently creating a second client authority.
- **A named cluster still consumes shared build prerequisites.** The first
  `u6bleaf` open found a stale canonical watcher and absent writer; prerequisite
  reconciliation then stopped on an unrelated in-flight
  `seon.schema/runtime-predicate` CLJS compile error. Preserve the focused JVM
  socket/CAS gate and retry the live proof only after the owning source lane
  commits; do not patch its files or spend provider calls against a stale
  artifact.

## Edge-bundle verified-stop scars (2026-07-23)

- **A dual tee needs the same resolved namespace facts, not only the same
  source.** The pod owns resolved aliases and refers through analyzer require
  edges. The JVM recorder currently receives only the source and namespace
  symbol, so it cannot distinguish `db/query` from another `db` alias or
  resolve a referred callable from a prior namespace form. Pass the immutable
  alias and refer tables into both the whole-source read and edge projection;
  never guess common aliases inside `seon.host.record`.
- **Read edges consume Datahike's parsed dependency semantics.** The maintained
  dependency exposes exact query/pull attribute sets and `:all` for dynamic or
  wildcard cases. A source walker in a new graph namespace would become a
  second Datalog and pull parser. Expose the pure maintained projection through
  the `seon.db` boundary, then map `:all` to the graph's explicit
  all-at-basis fact.
- **An artifact inventory is not the public function corpus.**
  `seon.client.indexing/public-fn-vars` deliberately rejects private helpers,
  and every returned var becomes a public `:seon.fn` row. Capture per-function
  direct facts during analysis, filter them by Shadow's exact
  `:build-sources` closure at the existing flush publisher, and bind each
  inventory-artifact digest into the selected artifact manifest and
  application digest.
  A client-only file or namespace-level `:used-vars` reconstruction is not an
  exact artifact inventory.
- **Graph identity must be acquired at one immutable database value.** Add
  normalized direct edges, descriptors, and their analyzed source generation
  to `seon.execution/canonical-program`, then retain its one ordinary-value
  digest. Coordinate this edit with any lane changing committed schema or
  contract acquisition; parallel changes to the same canonical acquisition
  boundary are not independent.
- **Installed host metadata is not yet a complete effect inventory.** The
  capability seam's effect vocabulary is
  `:pure`/`:read`/`:idempotent`/`:external`, and absence remains external.
  The current host installer preserves metadata for several wrapper families
  but drops source effects for the `seon.db` family. Derive graph descriptors
  from analyzed source/build facts until the installer carries the same
  metadata; do not infer purity from successful installation.

## U7 R4 context-family port scars (2026-07-23)

- **A portable block family needs its pure dependencies to load on the JVM.**
  Renaming only the ctx leaves exposed three hidden `.cljs` requirements:
  home-namespace selection, derived state, and warning formatting. Promote the
  pure owners in place (or extract the one pure projection and make the old
  owner consume it); never copy their rules into each block merely to satisfy
  a require gate.
- **Async ceremony can be centralized without changing acquisition data.**
  Keep protocol member maps and pure formatting unchanged. Route every
  `db/execute-many` stage through one `.cljc` executor whose CLJS branch awaits
  and whose CLJ branch returns the plain call. Any parallel stage uses that
  same executor's ordered `all`; block metadata is `:async true` only on CLJS.
- **Static resolution tables must include internal context converters.**
  Transcript events store symbols such as
  `seon.agent.ctx.transcript/eval->renderable`. After generic global lookup is
  deleted, those compiled symbols must enter a literal symbol-to-function
  table before recursive rendering. Otherwise a successful acquisition
  degrades every event into a missing-render error even though the namespace
  is loaded.
- **Request value schemas must not depend on stored-identity load order.**
  A focused transcript test loaded `:seon.render/section-request` before
  `seon.agent.run/id` existed. Keep the real namespaced request key, but
  validate its value through the shared compact-id value schema; the stored
  identity registration stays with its database owner.

## Edge-bundle implementation scars (2026-07-23)

- **Direct edges come from the accepted form plus the retained resolver
  tables.** The self-host analyzer projection does not retain function-body
  ASTs, while reparsing source with a second analyzer would create another
  program authority. Both tees now feed the same already-read function form
  and immutable alias, refer, local, core, macro, and effect facts into one
  pure projection.
- **Datahike query inputs must be aligned before asking for dependencies.**
  Query forms can omit the implicit `$` source while the maintained parsed
  projection reasons over declared `:in` bindings. The `seon.db` read-only
  seam inserts only that implicit database argument, then returns the fork's
  exact attribute set or `:all`; malformed or misaligned inputs widen rather
  than guessing.
- **Uncertainty is a first-class direct edge.** Dynamic keyword construction,
  unresolved calls, open higher-order values, dynamic read/write patterns,
  and macros whose expansion is unavailable produce explicit uncertainty
  facts. An empty edge set means analysis proved there was no edge; it never
  means the projection silently gave up.
- **A graph digest can remain independent of artifact acquisition.** The
  canonical edge bundle owns a sibling digest over sorted intrinsic facts and
  includes each function's analyzed generation. The deferred artifact export
  inventory can later select bundles without making private helpers public or
  changing edge identity.
- **A pure planner needs an explicitly acquired planning projection.**
  `:seon.db/db` is the closed ordinary database value
  (`db-name`/`store-id`/basis/temporal fields/commit ID); it does not contain
  program facts. The landed edge bundle has no production acquisition for its
  bundles or sibling digest, and persisted terminal descriptors are global
  rows without a function-to-terminal connection. Therefore
  `{db-value, roots, invocation?, tier-inventories}` cannot support a pure,
  portable reachable-graph walk by itself. Add one basis-fenced immutable
  planning-projection input containing canonical bundles/terminal connections,
  graph digest, committed schema projection, and fingerprint (or explicitly
  rename a larger wrapper); never hide these under roots/invocation/inventories,
  consult process-global state, or perform async database acquisition inside
  `plan-execution` (`src/seon/db/protocol.cljc:242-250`,
  `src/seon/program/edge.cljc:65-83,483-529`).
- **Exact persisted bundles need owned terminal connections, not global
  terminal lookup.** Store terminal descriptors once by their identity, but
  replace a function's cardinality-many terminal refs in the same exact-set
  transition as its direct edges. A single pull can then reconstruct canonical
  bundles and reproduce the in-memory graph digest. Keep acquisition separate:
  query every edge/schema/contract row at one explicit database value, stamp
  its basis transaction and commit ID, and make the planner reject a mismatched
  fence as a flat core bug. Fresh real-writer fixtures must first exercise
  committed schema installation before an exact-set transition whose leading
  retractions assume installed attributes (`src/seon/program/edge.cljc`,
  `src/seon/program/plan.cljc`).

## Streaming and provider-descriptor verified-stop scars (2026-07-23)

- **R36 reply policy must cross the durable attempt into the evaluator.**
  Orthogonalizing transport behavior alone is incomplete. The JVM driver
  currently hardcodes batch reply parsing
  (`src/seon/agent/driver/host.clj:253-307`), and portable run accounting still
  branches on legacy `:seon.config/repl-mode`
  (`src/seon/agent/driver.cljc:107-122`). The claimant must freeze
  `:seon.ai/reply-evaluation` on the attempt, the protected driver must pass
  that value to the existing reply-program path, and run bounds must count
  forms only for `:first-form`.
- **Attribute-indexed reactive interest is not entity-specific interest.**
  A transcript query can join an agent to its open attempt, but the current
  dependency plan reduces that evidence to attributes. Adding
  `:seon.ai.attempt/partial-text` therefore wakes every feed interested in the
  attribute; equality suppression can hide the extra morph but does not prove
  that another agent's feed avoided recomputation. The writer/dependency-plan
  owner must provide an entity-scoped contract before the cross-agent
  no-recompute falsifier can pass.
- **A two-core provider descriptor needs an explicit local-worker
  disposition.** The proposed `:openai-compat`/`:anthropic` descriptor enum
  covers hosted providers, while `:diffusiongemma` and `:typeahead` still use
  compiled local adapters. Deleting provider-ID dispatch without classifying
  those adapters would either strand them or preserve a second registry while
  claiming row-only extensibility. Settle whether descriptors are
  frontier-only or admit local adapter cores before replacing the existing
  dispatch mechanism.

## Streaming and provider-descriptor implementation scars (2026-07-23)

- **Reply evaluation is durable policy; wire streaming is presentation.**
  Freeze `:seon.ai/reply-evaluation` on every attempt and carry it through both
  claim drivers. `:first-form` retains upstream abort and estimated usage;
  `:batch` reads to natural EOF, retains terminal real usage, parses once, and
  evaluates the existing batch. `:seon.ai/wire-stream?` changes neither run
  accounting nor evaluator semantics.
- **A partial publisher must be latest-wins before it reaches the database.**
  Keep the transport fold synchronous and offer cumulative complete prefixes
  into a non-blocking, coalescing presentation sink. A blocked or throwing
  publication loses partials only. Fence each cardinality-one no-history write
  against the run, turn phase, and open attempt, then retract the fact in the
  terminal attempt transaction.
- **Attribute wakes are an accepted bounded intermediate contract.** The pure
  transcript query selects only its agent's open attempt and equality
  suppression prevents unrelated morphs. Attribute-level interest still
  recomputes the render: measured p50 was 1.094 ms for a representative
  50,558-byte page (p95 1.478 ms). Entity-scoped writer interest is recorded as
  a later web-tier-slice-2/C10-adjacent unit, not a streaming graduation gate.
- **Hosted descriptors and local workers are two named mechanisms.** Hosted
  rows are components of the config singleton and select only the fixed
  `:openai-compat` or `:anthropic` wire core. The compiled pod-only
  `:diffusiongemma` and `:typeahead` local-worker adapters remain explicit D12
  experimental dispatch; they never receive hosted rows or JVM arms.
- **Gemini's OpenAI compatibility surface is sufficient for the fixed core.**
  A cheap live qualification proved SSE `data:` framing, `[DONE]`, multiple
  forms, cumulative real usage, JSON-schema response format, tool calls, and
  bounded HTTP error translation. Register Gemini as an OpenAI-compatible row;
  do not introduce a native GenerateContent core.

## Schema admission boot-path scar (2026-07-23)

- **Desired program validation is not committed-row admission.** A fresh boot
  validates core program data before the writer creates its asserting
  transaction. Feeding those synthetic identity/form pairs through the
  committed-row projection correctly invokes its missing-provenance
  fail-closed rule and misclassifies core contracts. Validate desired core
  forms with the core projection compiler; reserve committed-row acquisition
  for rows that carry their real asserting transaction. Fixture coverage must
  exercise both sides because neither substitutes for the live boot ordering.
- **Every strictness branch must consult derived admission source.** It is not
  enough for undefined slots and open maps to distinguish authored contracts
  if nilability branches remain unconditional. Existing core backlog contracts
  stay advisory until touched; authored map-value and bare-return nilability
  remains terminal. Test both sources against the same contract form.

## P1b artifact-inventory verified-stop scar (2026-07-23)

- **A CLJS build inventory does not define the JVM claimant inventory.**
  Shadow's flush state supplies both the exact `:build-sources` closure and
  `:compiler-env` analyzer definitions, so one derivation can publish public
  exports plus private internal terminals without adding private `:seon.fn`
  rows. The JVM claimant currently runs source through `-M:writer:host`, while
  `writer-uber` deliberately copies source and avoids Clojure AOT; there is no
  corresponding JVM build-analysis state. Rule the JVM inventory authority
  before implementation rather than scanning source or mislabeling the writer
  jar as a claimant artifact (`build.clj:58-82`,
  `script/seon/dev/process.clj:562-585`).
- **Publishing a build artifact is not planner consumption.** The current
  acquisition path hardcodes artifact inventories unavailable, and
  selected-flavor artifact paths plus application-digest membership belong to
  the build manifest
  owner. A bounded inventory lane must own or receive explicit handoffs for the
  build hook/config, manifest digest, and planning-projection acquisition
  boundaries; a fixture-only available inventory does not make production
  planning exact (`src/seon/program/plan.cljc:33-43,524-568`,
  `script/seon/dev/artifact.clj:386-515`).

## Invocation-placement implementation scars (2026-07-23)

- **Parsed invocation forms are synthetic graph roots, not a second corpus.**
  Retain the P1 analyzer resolution with the parsed reply, qualify definitions
  in reply order, and pass each form through `seon.program.edge/analyze-function`.
  Non-definition forms can be wrapped in synthetic zero-argument definitions
  because that changes neither their calls nor their attribute and package
  references. Everything they call still resolves through the acquired
  persisted graph.
- **No roots means no executable placement evidence.** An empty root vector is
  `:unplannable` with a named `:no-roots` unresolved entry; it is not a
  vacuously pure program.
- **Tier selection is policy data derived after eligibility.** Prefer the
  invoking claimant only when it is eligible, then an eligible handoff tier.
  If neither is eligible the selected tier is absent so the caller releases
  the run instead of inventing placement.
- **Installed-leaf inventories are captured by the installer.** Enumerate
  binding, effect, and remoteness descriptors while wrappers are installed,
  default undeclared effects to external, and hash the canonical immutable
  descriptor set. Reconstructing this inventory later from wrapper registries
  loses producer metadata and creates a second authority.

## P5 pre-dispatch and router-consumer scars (2026-07-23)

- **The phase transition belongs after planning, not around it.** Parse the
  reply, acquire the fenced planning projection, derive and verify placement,
  and only then commit `:reply-ready → :evaling`. Steering and handoff paths
  must not create an eval receipt or advance the cursor.
- **A router consumes selected tier data and nothing weaker.** Passing parsed
  forms alongside an execution plan does not authorize AST, loader, require,
  or namespace-prefix inspection. Route from `:seon.execution/selected-tier`
  and retain only result-symbol ownership checks, because retained values are
  runtime-local facts rather than placement evidence.
- **Exact-plan verification changes the failure class.** Before exact
  placement, missing leaves, exports, schemas, and unresolved edges are
  steering evidence. After an exact selected-tier plan, the same missing
  requirement means planner/inventory drift and is a `:core-bug`.
- **Portable-base purity needs a per-root planner product.** Base construction
  precedes claim database acquisition, and aggregate invocation placement
  cannot classify sibling source blocks independently. Do not recreate P1
  resolution and edge projection inside `seon.host.context`; the planner must
  expose a batch/per-root purity projection before the regex classifier can be
  deleted.

## Frame-safe database initialization scars (2026-07-23)

- **A transport ceiling is not a corpus-size governor.** The 4 MiB R27 frame
  limit correctly rejected the former complete-population ensure request.
  Fresh initialization now sends ordinary ordered ensure requests whose
  schema, selected attributes, program, and initial entities are row-paged.
  Corpus growth creates more frames, not a larger legal frame; do not
  recalibrate the circuit breaker to admit boot growth.
- **Schema rows need paging after one fixed bootstrap closure.** Page zero
  carries only the transitive schema forms required to store genesis,
  canonical schema rows, and initialization receipts. Once that bounded
  closure commits, every remaining schema row is ordinary page data. Those
  rows must be topologically ordered by canonical-schema references before
  partitioning: a generated identity schema cannot transact before the value
  schemas it names are committed. Lexical order reproduced this on
  `:my.plan/id`. Partitioning program rows while retaining one complete schema
  population merely moves the same unbounded frame.
- **A committed prefix is unavailable state, not a smaller valid seed.** Each
  page uses a deterministic durable transaction receipt and records its
  desired program identities on that receipt. The singleton initialization
  entity remains `in-progress` across process death; external bare ensure and
  acquire reject it. The writer's own startup may reopen that database only to
  retain the connection for deterministic page replay, without running runtime
  initialization or embedding backfill while the marker is `in-progress`. The
  final page first proves every predecessor receipt, removes stale boot-owned
  program identities in bounded transactions, then commits `complete`.
- **A bare file-database ensure is open-existing, never create-if-absent.**
  Derive creation authority only from writer startup or an initialization
  page, then let the registry's one open/create choke point call Datahike's
  `database-exists?` before creating a parent directory or store. A missing
  database returns the protocol's not-found value; a live logical route also
  rejects a different backend path (`src/seon/db/{writer,registry}.clj`,
  `d0a73db8e`).
- **No-op pages still need receipts.** Attribute declaration pages can already
  be converged, but their ordinal is still part of the restart proof. Commit an
  empty domain transaction with the normal protocol receipt rather than
  silently skipping the page; otherwise the next page correctly appears
  out-of-order after a no-op.

## Per-artifact inventory and private-corpus scars (2026-07-23)

- **Let each artifact’s existing enumerator publish its own truth.** Shadow’s
  flush hook selects exact `:build-sources` and delegates function
  classification to the client analyzer indexer; it does not reparse source.
  The JVM claimant projects its already-captured installed wrapper inventory
  and preserves that enumerator’s digest exactly. A JVM classpath analyzer is
  justified only by proof that a reachable compiled terminal is absent from
  installed bindings.
- **Inventory bytes are application identity, not diagnostic output.** Publish
  one `program-inventory.edn` artifact beside every supported CLJS build
  output, include both inventory-artifact digests in the artifact manifest and
  application digest, and pass the
  planner-ready Bun projection through the host launch request. The host merges
  that value with its claimant-local JVM inventory before planning.
- **Private is a presence fact, not an indexing exclusion.** First-party
  private functions retain real `:seon.fn` rows and source with
  `:seon.fn/private? true`; public rows omit the attribute. Default discovery
  queries filter private rows, while explicit full-namespace source remains
  drillable. Third-party functions remain structurally outside the corpus and
  can appear only as artifact-internal terminals.
- **Do not infer privacy from an unresolved predicate name.** The fresh paged
  reset exposed an unresolved `seon.db.protocol/ordinary-wire-value?`, but the
  source and build analysis both classify that function as public. Attribute
  the failure to schema projection or SCI predicate binding from those facts;
  changing private-row publication would conceal a separate acquisition bug.

## Reconstruction and readiness scars (2026-07-23)

- **Fixture compilation is not client reconstruction.** A registered core
  predicate may compile in schema fixtures while the committed projection's
  dependency walk or Malli instrumentation silently drops its computed
  predicate bindings. Every projection consumer must carry the same complete
  compile options derived from the one predicate registration authority.
- **Readiness is detected, never guessed.** The readiness advertisement ends
  the wait; a clock may only break a stall after concrete progress stops.
  Initialization page receipts, boot-phase transitions, projection-acquisition
  pages, and bounded heartbeats must advance the observed log so legitimate
  corpus growth cannot resemble a wedge.
- **Polling loops and timeout literals are design-smell hunting grounds.**
  Ask which event should end the wait and which observable progress can prove
  healthy work before adding any clock. When a stall breaker remains
  necessary, make it an R27 config fact, document its unit and protection
  boundary, and name that key loudly when it fires.

## Fixture boot-population scars (2026-07-23)

- **Genesis forms were CLJS-captive.** Canonical program-row entity forms and
  database-attribute selection lived in `seon.client`, so a JVM fixture could
  load the leaf forms yet still miss the production genesis population.
  Registration and its computed database projection belong to the portable
  schema authority; the client delegates to it.
- **Fixtures are clusters in miniature.** A writer fixture must consume the
  complete compiled base and apply it through
  `seon.db.protocol/initialization-pages`, exactly as production does. A
  schema-only bootstrap or per-test attribute list is a second initialization
  semantics. This is the construction-level repair for the fragile-test
  audit's F1 row; do not patch individual tests listed there.
- **The JVM cannot reproduce CLJS analyzer rows.** Re-reading program source
  with `seon.host.record` changed function metadata and namespace edges, proving
  it is a second indexer rather than a portable reconstruction. The build must
  publish the byte-faithful, digest-bound `program-rows.edn` artifact from the
  live analyzer derivation. Both runtime tiers and fixtures consume those
  rows; neither tier reconstructs them.
- **Fixture consumption starts from the selected artifact manifest.** Resolve
  `program-sources.edn` and `program-rows.edn` beneath the manifest's immutable
  runtime root, verify both exact byte digests, and use the manifest's
  application digest as initialization identity before reading transaction
  data. A missing member, stale version, path escape, or digest mismatch is a
  loud core fault; silently falling back to checkout files would detach the
  fixture from the build it claims to model.
- **Pre-parsed transaction data must come from the compiled indexer, not an
  approximation of Shadow metadata.** The flush hook already has the exact
  just-built Shadow state, but that state cannot supply registered Malli forms
  or runtime var metadata byte-faithfully. At release `:optimize-prepare`,
  convert only the copied Closure/JS inputs exactly as Shadow's dev compiler
  does, flush an isolated unoptimized Node view of that state, replace only
  Shadow's generated main append with a build derivation that calls
  `seon.client/index-core!` and `seon.client/index-schemas`, then retain the
  ordinary transaction-data rows in the build state. Dev builds run the same
  derivation at flush. The existing flush publisher verifies the published
  program-source digest and remains the sole atomic artifact writer. Capturing
  before optimization matters: an optimized release state cannot expose the
  runtime vars, while its copied `goog.module` sources are intentionally not
  loadable without the normal conversion. Invoke the compiled boot path's own
  wall-clock removal and identity ordering, then preserve its CLJS `pr-str`
  bytes directly: JVM `pr-str` is not byte-identical for the full population
  and must not reserialize the rows (`script/seon/dev/program_artifact.clj`).
  Producer names use Shadow/build vocabulary (`flush`, artifact, digest);
  consumers must use Datahike and `seon.db.protocol/initialization-pages`
  vocabulary (`transaction data`, rows, pages), never an orchestrator lane
  label.
- **A copied watch state retains Shadow's injected Node devtools entry.**
  Replacing only `shadow.module.main.append` does not make the temporary
  program a run-to-exit build: `shadow.cljs.devtools.client.node` still opens
  its websocket and retains Bun after the row marker is written. Apply
  Shadow's own disabled-devtools configuration to the copied state, remove
  that injected entry, and rerun `shadow.build.api/analyze-modules` before
  `shadow.build.node/flush-unoptimized`. Do not terminate the correct
  derivation after a timeout or manipulate its socket. The managed-watcher
  regression is a first flush that publishes manifest v11 while no
  `.program-rows-build-*` Bun process remains
  (`script/seon/dev/program_artifact.clj`).
- **Every boot-read inventory is an immutable runtime member.** The pod reads
  `program-inventory.edn` beside both its client output and the selected
  execution output. A manifest path and digest do not make either file part of
  a content-addressed runtime root. Include both inventory digests in the root
  identity, copy both files at their manifest-relative paths, and verify their
  exact bytes before admission. The default watcher makes this coherent by
  always watching both configured builds; each build publishes its inventory
  from its own flush hook (`script/seon/dev/artifact.clj`,
  `script/seon/dev/process.clj`).
- **R28 breakage can survive on a pod surface until build-time derivation
  executes it.** The shell config-fact conversion changed portable
  `run-request` to require the acquired configuration, but the still-alive pod
  leaf retained both its deleted default var and old arity. Ordinary JVM gates
  did not exercise that caller; program-row derivation did, so the watcher
  failed loudly before publishing the v11 manifest. Any public signature
  conversion that leaves a canonical build alive must compile both `client`
  and `test` with zero first-party arity or undeclared-var warnings. A focused
  suite or only one canonical build is not a class gate.
- **A complete projection build prepares one immutable validation context; it
  never rebuilds that context per contract.** Collect predicate bindings and
  direct predicate symbols, bind forms, construct the registry/options, compile
  schemas and contracts, and derive dependency edges once. Then validate the
  request vector with a bounded platform-core fold and one build-scoped
  `[reference role admission-source]` result cache. Registration keeps the same
  completeness entry point because its candidate population is genuinely
  changing. A fresh boot may retain this projection only after the committed
  rows reproduce its full forms/contracts/admissions/pure-predicate
  fingerprint; mismatch always invokes the same builder
  (`src/seon/schema.cljc`, `src/seon/runtime/admission.cljs`,
  `src/seon/client.cljs`).

## Source provenance and contract provenance are different facts

A function contract row and its source row may have different asserting
transactions. Datahike does not create a new datom when an identical
`:seon.fn/spec` value is reasserted, so an agent can replace
`:seon.fn/source` while the spec datom retains its earlier core transaction.
Authorship and trust must therefore derive from the current source datom's
asserting transaction, never from contract admission.

Keep source admissions and exact compiled artifact exports in the one compiled
projection. Include both in its fingerprint and in fingerprint-guarded reuse;
patching them onto a reused projection afterward makes cold and reused
classification observably different. A corpus source row decides before
artifact membership, and absent evidence fails closed as agent-authored.

## Page variable-weight read expansion, not only identity enumeration

An index cursor can bound and deterministically order identity datoms while
the query that expands one identity page into source and contract rows remains
unbounded. Variable-length source made a 32-identity expansion exceed the
database result-weight breaker even though enumeration itself was paged.

Keep the breaker unchanged and page the expansion at its minimum exact unit:
one canonical identity row per query. Acquire one immutable database value
before enumeration and pass that same value through every stable cursor and
expansion request, so concurrent commits cannot tear the result. Corpus growth
then increases the number of bounded reads rather than any read's payload.
This is the read-side sibling of paged initialization: page both the keys and
the variable-weight values they select.

## CLJS multi-arity test-stub and detached-chain scars (2026-07-23)

- **A replaced CLJS var must preserve the production var's arity shape.**
  Adding a second arity changes compiled call sites to invoke generated
  `cljs$core$IFn$_invoke$arity$N` properties. Replacing that var in a test
  with a single-arity anonymous function can therefore throw a JavaScript
  `TypeError` even when the exercised call uses the stub's apparent arity.
  Define every production arity on the stub, or route them through one local
  implementation.
- **A detached product chain needs a test latch on every terminal rail.**
  `shadow-build-notify!` returns a synchronous notification acknowledgement
  while its publication Promise intentionally converts failures into
  `admission/mark-unavailable!`. A test waiting only for the later rehost
  callback never observes that failure and never calls `done`. Resolve the
  test's latch from both rehost and unavailable, assert which outcome arrived,
  and keep cleanup plus `done` in `.finally`.
- **The last printed test can be only a landmark.** The full-suite tail ended
  at the synchronous `INITPAGE_10X_MEASUREMENT`, but that exact test completed
  alone. The namespace reproduced the timeout; exact-var halves then isolated
  the later reload test. Preserve this order of falsifiers: tail, namespace,
  exact selector, then exact-var halves only when namespace output has no
  per-test start marker.

## Portable async macro resolution scar (2026-07-23)

- **A same-namespace CLJ identity macro can erase CLJS `await`.** A `.cljc`
  namespace that defines `(defmacro await [value] value)` for its synchronous
  JVM branch also exposes that macro while compiling the CLJS branch. The
  apparent `(await (thunk))` then returns the Promise itself, so value
  predicates inspect a Promise and retry loops silently stop after one
  attempt. Once the Promise is honestly awaited, its resolved value also
  matters: an interruptible sleep contract returning boolean must explicitly
  resolve `true`; a bare timer callback resolves `nil` and looks interrupted.
  Keep the platform choice at each async leaf call:
  `#?(:clj (thunk) :cljs (await (thunk)))`; never shadow the CLJS async
  transform with a portable identity macro (`src/seon/retry.cljc`).

- **Namespace metadata parsing must allow reader conditionals.** A `.cljc`
  namespace can put its platform-specific requires behind `#?` while keeping
  one shared docstring. `cljs.reader/read-string` rejects that source and a
  fail-soft parser then silently drops both documentation and require edges.
  Use the maintained tools reader with `:read-cond :allow` and the current
  platform feature set at the one namespace-source boundary
  (`src/seon/ns/source.cljc`).
- **A portable family core is public structure, not `.internal`.** If both a
  parent namespace and a platform leaf consume the same pure policy and
  response transformations, the shared namespace is the family `core`.
  Calling it `.internal` either violates the parent-only require law or forces
  an allowlist that conceals the real ownership. Rename the mechanism in
  place, update every consumer, and delete the misleading legacy namespace
  (`seon.agent.web.core`).
- **Reconnect tests synchronize on the attempted connection, not a timer.**
  Scheduling owner close after an arbitrary few milliseconds can race ahead
  of the retry backoff and assert that a reconnect occurred before the code
  was allowed to attempt one. Resolve a test latch from the replacement
  `connect!`, close the session only after that latch fires, and keep the
  connection Promise pending long enough to prove owner close stops recovery
  during the attempt (`test/seon/db_remote_contract_test.cljs`).

## Descriptor-policy consumer scar (2026-07-23)

- **Provider tests assert descriptor keys, not family folklore.** A shared
  OpenAI-compatible wire core does not imply shared thinking fields or missing
  defaults. Select the descriptor whose
  `:seon.ai.provider/thinking-policy` promises the behavior under test:
  DeepSeek emits its `thinking` toggle, Z.AI emits `reasoning_effort`, and the
  generic descriptor omits both while shipping its current default base URL.
  To prove the missing-endpoint error, remove the URL from the resolved
  request explicitly instead of assuming a catalog row lacks one.

## Reply-policy consumer scar (2026-07-23)

- **A split policy requires every consumer to resolve both axes from both
  rows.** Run limits and historical-attempt validation must acquire the
  cluster and agent policy facts, pass them through
  `seon.ai/reply-policy-from-rows`, and consume reply evaluation separately
  from wire streaming. Reading the retired mode field directly silently
  ignores explicit per-agent overrides.
- **Historical fixtures are persisted entities, not convenient partial
  maps.** Keep projection fixtures valid against `:seon.ai.attempt/entity`,
  including compact identity, config digest, deadline, and reply evaluation,
  so fail-closed production validation proves ordering and drift rather than
  rejecting an impossible test row first.
- **A policy split is incomplete until producer-result contracts move too.**
  `seon.agent.ctx.driver/render-prompt!` correctly returned the two explicit
  reply-policy axes while `seon.agent.turn/render-prompt` still required the
  retired `:seon.config/repl-mode` projection. Malli output instrumentation
  therefore rejected every valid prompt before a turn opened. When replacing
  one field with orthogonal facts, sweep function output schemas and test
  fixtures in addition to database readers; prove the producer through the
  instrumented consumer for every legal axis combination. Reuse the registered
  axis schemas rather than copying their boolean and enum definitions
  (`a88e11505`, `tmp/orchestrator/replmode-gate.log`).

## Web-limit reader scar (2026-07-23)

- **A validated limit is not real until its enforcement point reads it.**
  Thread the operation's frozen configuration to extraction, read link count,
  HTML character count, and HTML nesting depth where those bounds fire, and
  include every required fact in the loud missing-limit gate. Literal defaults
  in a leaf make accepted manifest overrides dishonest
  (`seon.agent.web/fetch` → `seon.agent.web.pod/extract-content`).

## Computed cold-boot schema population scar (2026-07-23)

- **A computed inventory is only as complete as its persisted-entity
  declarations.** Canonical database attributes derive from entries of maps
  marked `:seon.db/entity true` plus persistence facets on standalone
  attributes. Component children, transaction roots, and optional persisted
  fields are still entities and attributes even when an API response schema
  resembles them. Declare those persisted shapes at their owners; never add a
  fallback attribute list to boot.
- **Parity cannot compare two names for the same derivation.** The old
  initialization assertion compared its attribute value to
  `agent-bootstrap-attrs`, which was the exact value used to construct it, and
  spot-checked only one deleted-list member. Preserve the removed cold-boot
  contract as test evidence and assert that the computed population is its
  superset. Also assert a persisted declaration that the old list omitted, so
  the regression proves computation can grow beyond frozen history.

## Build artifact boundary scars (2026-07-23)

- **Package the compiled build beside the inventory path its consumer
  derives.** Checkout builds place each `main.js` beside
  `program-inventory.edn`; a source-free release must preserve that directory
  relationship for both the client and execution builds. Distinct manifest
  members are insufficient when package assembly flattens both builds into one
  directory.
- **A standalone fixture must select an exact current artifact manifest.**
  Test setup must not inherit `tmp/seon-operator/artifact.edn` from an earlier
  operator run. Resolve the manifest through the one artifact mechanism and
  pass its exact path to the fixture, or fail before tests with the operator
  command that produces it.
- **A Shadow preload executes at namespace load during transaction-data
  derivation.** Keep preload registration definition-only. Timers and other
  application-startup effects belong in the real `-main`, because the
  temporary compiled derivation loads preloads but must exit after emitting
  initialization rows.
- **Release preparation and flush must publish one source snapshot.** Shadow's
  release optimization can change the analyzer source set between
  `:optimize-prepare` and `:flush`. Carry the exact pre-optimization program
  source text through the existing hook state with its derived rows, so the
  digest guard binds one coherent build transaction.

## Error boundary shape scar (2026-07-23)

- **A retired nested error check silently turns a flat failure into success
  data.** The database leaf returns canonical flat values with
  `:seon.error/message`, but host startup still checked the former
  `{:seon/error {...}}` envelope and entered healthy-session setup. At every
  boundary, recognize the current producer's exact error keys and route the
  value through the existing consumer error frame; do not translate through a
  second error shape. Prove transport failure with a real retained session
  severed between calls, then assert error keys before EOF and a healthy
  reconnect (`src/seon/host.clj`,
  `test/seon/host_conformance_writer_test.clj`).

## UDS frame scheduling scar (2026-07-24)

- **One physical socket requires one ordered output sequence.** A complete
  encoded `ByteBuffer` is not an atomic frame write when the channel is
  non-blocking. If responses live in one deque while unsolicited events live
  in a separate active-output slot, a newly queued response can preempt a
  partially written event and splice one frame into another. Queue opening
  responses, request responses, and events in the same `::outputs` deque;
  keep event state only for admission and completion ownership. The deque head
  remains the active frame until its buffer is exhausted
  (`src/seon/db/transport/uds.cljc`,
  `test/seon/db/transport_uds_test.clj`).

## HTTP terminal-catch fault scar (2026-07-24)

- **Returning HTTP 500 does not record a caught core fault.** A terminal
  Promise catch at an HTTP composition door must call `seon.error/record!`
  with `:seon.error/fault :core` before it logs and constructs the bounded
  response. Console output is operational evidence, not the durable fault
  datom. Regress the boundary with the real injected persistence hook and
  assert the transaction projection, not merely that a mocked recorder was
  invoked (`src/seon/web/serve.cljs`,
  `test/seon/web/serve_test.cljs`).

## Computed-bootstrap fixture scar (2026-07-23)

- **Host transaction data is never SCI source.** `pr-str` preserves symbol
  values such as `:seon.ns/name 'seon.host.context` without adding a quote at
  their eventual code position. Splicing canonical schema or compiled program
  rows into `(seon.db/transact! ...)` source therefore asks SCI to analyze host
  data as agent code and fails at the first unbound namespace symbol. Seed a
  fresh writer fixture with
  `seon.db.writer-test-support/seed-canonical-schema!`, pass fixture-specific
  initial rows as its ordinary `initial-data`, and assert the protocol success
  value. Reserve evaluated transaction forms for data the agent actually
  authored. When this failure appears, sweep every writer fixture for
  `corpus-schema-rows` or canonical transaction data inside `eval-string*`,
  `invoke-batch!`, and source-building `str` forms; repairing only the first
  failing namespace leaves the same class latent behind suite order.
- **A hand-built initialization fixture dies twice under computed-bootstrap
  enforcement: missing bootstrap forms, then a dangling registry.** After
  paging landed, `protocol/initialization-pages` rejects any
  `:seon.db/initialization` whose schema rows lack the bootstrap closure, so
  fixtures must seed through the canonical producers
  (`seon.schema/canonical-schema-rows` + `canonical-database-attributes`) —
  never a hand row list. But the producers project ONLY the schemas the
  process has loaded, and `register!` permits forward references, so a
  focused test bundle can yield a population with dangling references (e.g.
  `:seon.fn` referencing `:seon.program.edge/*` registered in an unloaded
  namespace). The fix is not a closure namespace list: require the production
  corpus entry (`seon.client`) so the loaded registry is complete by the
  application's own require graph, then drop wall-clock attrs
  (`:seon.schema/created-at`) for a deterministic fingerprint. Forwarding
  assertions compare the ensure requests' `:seon.db/initialization-page`
  sequence against `protocol/initialization-pages` of the same fixture value
  (`test/seon/db_remote_contract_test.cljs`).
- **An opaque committed-projection acquisition failure is not evidence of a
  forward reference.** The claimant host formerly sent schema, contract, and
  whole-source queries as one `execute-many` request, then logged only the
  first two member results. On restart, the omitted source member exceeded the
  aggregate result-weight bound; replaying the complete successful
  schema/contract rows through `projection-from-rows` was the shortest
  falsifier. The host now freezes one database value, pages identities through
  AEVT, and reads each variable-size form row separately through `seon.db`,
  matching pod admission without a corpus-size response. A real missing Malli
  reference now reports the registering key plus the missing key and namespace
  on both tiers (`c2c5faeff`,
  [[../../../seon/issues/archive/claimant-host-drains-after-clean-restart]]).

## Claimant acquisition and phase-settlement scars (2026-07-24)

- **A singleton identity is one portable Datahike lookup ref, not a copied
  attribute/value pair.** Owning only the scalar value still lets another tier
  substitute a schema keyword or literal in the lookup-ref value. Define the
  complete lookup ref beside the identity registration in
  `seon.config.resolve`, and make pod, claimant, execution, and web acquisition
  pass that exact value. A focused consumer regression must assert the shared
  ref itself; matching literals do not prove acquisition consistency
  (`7b16ca694`).
- **Persist the provider response key the adapter actually produces.** Both
  OpenAI-compatible JVM and pod interpreters return visible completion text as
  `:seon.ai/text`. Reading an unnamespaced `:text` after a successful HTTP
  response silently publishes the empty content hash while the attempt still
  terminalizes `:success`. The portable durable-attempt owner now reads
  `:seon.ai/text`, with a regression that captures the exact non-empty blob
  request (`fdba88aad`).
- **An optional entity override must sit above one shared process fallback.**
  Pod and JVM claimants must pass the same resolved process attempt timeout to
  `seon.ai.core/resolved-config-from-rows`; that resolver alone applies an
  optional `:seon.ai/agent-attempt-timeout-ms` override. Requiring the entity
  attribute in one claimant turns ordinary inheritance into a tier-specific
  configuration failure. Put environment parsing and shipped fallback in
  portable `seon.config.resolve`, and make every tier delegate to it
  (`094e7a7e6`).
- **A phase error is a fenced durable terminal transition, never a returned
  thread-local value.** Under the held run epoch and observed turn phase, one
  transaction crashes every open attempt, clears partial presentation text,
  marks the turn `:published`/`:error`, closes the run `:error`, and retracts
  both claimant custody and the agent's current-run connection. Record the
  same flat error as a fault datom after the transition; malformed leaf output
  uses this path too. A dispatch thread returning an error without this
  settlement recreates a heartbeat-only wedge (`094e7a7e6`).
