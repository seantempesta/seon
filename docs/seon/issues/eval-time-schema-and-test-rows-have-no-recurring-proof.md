---
type: issue
status: open
severity: blocker
tags: [issue, sci, program-graph, testing]
---

# Eval-time schema and test rows have no recurring proof

## Problem

The original missing runtime proof and the later exact-namespace blocker are
implemented. Runtime schema, function, and test declarations commit canonical
rows, materialize only from the successful terminal transaction report, and
reacquire after a real cluster reopen. `1135d8f39` replaces lossy require-edge
reconstruction with separate exact facts for actual requires, aliases, and
refers, installed through maintained SCI APIs (`2217449`, `98457e8`).

The schema lifecycle is also complete through `913f8177c`: schemas are global;
`schema/unregister!` stages one typed deletion in the isolated registration
delta; dependency/current-data checks run at the terminal transaction; and the
active projection derives from `db-after`. This issue remains open only until
the independent landing-wave audit is reviewed and any findings are resolved.

## Evidence

Current recurring proofs establish:

- evaluated canonical schema values rather than reader syntax;
- schema row, derived Datahike attribute, and terminal receipt in one
  transaction;
- failed registration leaving no row, Datahike attribute, staged schema, or
  process-global projection mutation;
- exact live test execution, replacement, and deletion;
- incompatible A-B-A database projections without global registry bleed; and
- schema/function/test materialization after stopping and reopening a cluster.

The final combined independent focused gate after the owner's superseding
cross-namespace `ns-unmap` ruling was 59 tests / 462 assertions / 0 failures /
0 errors.

The two former shortest falsifiers are now recurring successes: runtime renamed
refers retain their target Var identity through restart, and a build source
that renames `clojure.test/deftest` to `dt` produces the exact test row.
Multiple aliases to one target remain distinct, `:as-alias` does not become a
load dependency, and a plain require remains visible for acquisition ordering.

Git archaeology found the already-implemented exact representation in
`57761ddb4` (`feat(program): persist canonical direct edges`). Its JVM SCI
projection and CLJS analyzer projection both represented aliases as
`local -> target namespace` and refers as `local -> qualified target`.
Compressing those effective bindings back into require syntax reintroduced
the information loss.

The full source audit, vendored SCI anchors, probes, and recommendation are in
`docs/prds/sci-execution-runtime/research/runtime-registration-adversarial-audit-2026-07-30.md`.
Schema removal/history evidence is in
`docs/prds/sci-execution-runtime/research/schema-removal-history-probe-2026-07-30.md`:
after current data is retracted, old temporal datoms and the historical schema
row rebuild validation at one `as-of` basis; Datahike's schema map itself does
not time-travel, and `:seon.db/no-history? true` explicitly discards old values.

## Owner

`seon.sci.reader` / `seon.sci.eval` / the maintained SCI fork's namespace
binding seam. `seon.program` and `seon.cluster.run` already own the shared row
and terminal transaction contracts and should not gain another registry.

## Acceptance

One shared pure owner defines canonical identities, owned attributes, row
construction, exact replacement, and typed function/test deletion. Build and
runtime call it after their explicit producer admission: build indexes every
function; runtime publishes only fully contracted functions. No transaction
owner restates that contract.

The recurring proof matrix establishes:

- build/runtime canonical row parity over their shared admitted domain;
- evaluated schema values, with failed registration leaving neither a row nor
  staged state, and with the same terminal transaction installing a derived
  Datahike attribute declaration;
- exact function/schema/test replacement and stale source reconciliation;
- `ns-unmap` retracting matching function and test facts with ordinary SCI
  REPL semantics, including a cross-namespace target;
- exact resolved test operation identity, so unrelated `foo/deftest` never
  becomes a `:seon.test` row;
- installation from the successful terminal transaction report's exact
  `db-after`, never from receipts or pre-commit declaration mutation;
- current function, schema, and test materialization after cluster reopen;
- two incompatible cluster projections alternating in one JVM without global
  registry bleed;
- renamed `deftest` and renamed `seon.schema/register!` at build time;
- renamed refer, two aliases to one target, and `:as-alias` through a runtime
  terminal transaction and fresh acquisition; and
- an agent-authored target namespace proving acquisition orders target Vars
  before installing referring functions/tests.

The unifying repair is namespace-owned effective binding facts: aliases carry
local + target namespace, refers carry local + target namespace + target name,
and bare dependency targets remain only where loading/order needs them. A
single narrow public operation in Seon's maintained SCI fork installs those
facts into a context. Do not add accumulated require-source replay or a second
registry.
