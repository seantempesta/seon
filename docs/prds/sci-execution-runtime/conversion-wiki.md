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

## Process/operator

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
- **Path-limited commits always**; add new untracked owned files
  explicitly in the same commit (a missing test-support file made every
  intermediate writer gate unreproducible for a day).
- **Skills: edit seon-skills/ (canonical), then `bin/seon skills
  sync`** — .agents/.claude trees are generated; the operator gate's
  drift check catches direct adapter edits.
- **bb tooling loads via bb.edn** — never bare `--classpath`; new deps
  used by bb-loaded namespaces must be pinned in bb.edn too (parinferish
  0.8.0 precedent).

## Design rulings that bind conversions

- One mechanism; fix in place; delete the superseded path.
- Computed structural rules, never literal name lists (set-valued-attr?,
  the census's base-resolved bidirectional check).
- Errors as values with steering that names the governing config key.
- The capability seam carries effect-class metadata (pure/idempotent/
  external) — replay classification and portability share one boundary.
- Owner tiebreaker: experimentability + reasonability win seams.
