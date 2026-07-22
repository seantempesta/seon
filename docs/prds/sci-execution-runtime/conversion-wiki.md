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
