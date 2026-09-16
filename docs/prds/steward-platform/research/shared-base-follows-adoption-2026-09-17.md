---
type: research
date: 2026-09-17
lane: shared-base-follows-adoption
---

# The shared fixture base follows adoption, by derivation

## What was wrong

`seon.test-support/database-base` was built once per JVM and kept for the
JVM's life. It carried the program rows and contracts of whatever publication
was current when some earlier caller first forced it. After a later
`bin/seon init --dev default` adopted new source in the SAME JVM, every
in-process `seon.test/run` still branched from that base, so an accreted arity
was refused inside the run (`refused argument count … of 4`) while the same
call answered from the prepl, and a newly declared attribute read as
"not indexed". On 2026-09-17 this cost every lane hours and made three
pre-existing regressions in `seon.fn-test` report errors that were not theirs
(recorded in
[the issue](../../../seon/issues/successful-fixture-base-retains-pre-adoption-contracts.md)).

The stale mirror was not only the base. `seon.test-support/source-manifest` was
a `delay` over `seon.fn/build-manifest`, so the manifest a development JVM
populates from was ALSO frozen at first force — a second copy of the same
defect.

## What changed

`test/seon/test_support.clj` only, plus its regressions in
`test/seon/test_support_test.clj`.

**The base is keyed by the publication it was built from.**
`publication-key` names the publication a canonical base built right now would
carry:

- an isolated `bin/test` worker has `seon.test.published-base` set and that
  snapshot is immutable for the JVM's life, so its path is the whole key and no
  store head is ever read;
- a development JVM populates from the working tree, and the fact that moves
  under it is the store's `:current-src` head. `published-commit-ids` reads it
  from this JVM's held stores (`seon.operator.runtime/root-store-holder` →
  `seon.cluster.source/current`) — one konserve head record per store,
  **measured 1.0486771 ms per read** in default PID 88182;
- no reachable store is its OWN key member
  (`{:seon.test-support/published-base :seon.test-support/no-store}`), never a
  silent `nil`: a key that collapsed to `nil` when its subject was absent would
  be the project's named "absence of signal reads as health" class again.

`retrying-base` now takes that key function. A request whose key differs from
the key the current base was built under is a **cache miss by construction**:
the current base is RETIRED and a new one is constructed from the current
publication. No lane rebuilds a base by hand and no JVM is restarted.
`source-manifest` is keyed the same way, so the base and the manifest always
describe the same publication.

**Retirement waits for holders.** Datahike refuses to delete a branch under an
active connection ("Cannot delete a branch with an active connection", seen
2026-09-17). `with-branched-database` now takes a HOLD on the base across the
whole fixture (`acquire-base!` / `release-base!`) and binds it, so:

- a run holding the old base completes on it while a later run gets the new
  one;
- a retired base is closed only after its last holder releases, on a daemon
  thread, and `close-base!` is one-shot (it also removes its own JVM shutdown
  hook, so a retired base is never closed twice);
- `fork-cluster-ctx` forks the ctx of the base the ENCLOSING fixture holds, not
  whatever base is current — otherwise an adoption landing mid-fixture would
  fork a new base's ctx over an old base's branch.

**Preserved properties.** Construction still runs on a daemon thread with the
caller's projection handed to it, so a caller's bound never interrupts it and a
failed construction is reported as a typed value and retried, never cached.
`realized?` now answers for the publication the run executes under, so an
observer that guards its deref with `realized?` — `seon.test.runner`'s drift
snapshot does, and that file is another lane's — is never the caller that pays
for the next construction.

**One bound added.** At most one construction runs at a time. Measured live:
with several lanes adopting into the same JVM the head advances repeatedly, and
without the bound two full canonical populations could run concurrently on a
machine the owner has already flagged for load. A superseded construction still
completes, so every caller already waiting on it gets a base, and retirement
closes it.

`database-base` is now a `def` over a `defonce` STATE atom rather than a
`defonce` object: reloading `seon.test-support` in a live JVM must neither
discard a realized base nor keep an older namespace's construction code.

## Live evidence — default PID 88182

Nothing was restarted, reforked, or rebuilt by hand; no test JVM was launched.

1. The derivation reads a fact that actually moves. At 14:40Z the one held
   store named `:current-src` head `6aaaac9-b8d8-518a-b545-1209486ae462`. After
   the hook adopted this lane's edits the base realized in this JVM recorded
   `:seon.test-support/publication-key
   {:seon.source/commit-id #{6aaaad05-30a6-532e-9f27-8062a3386b6e}}` — a base
   built under the NEW publication, with no manual rebuild. Minutes later the
   head had advanced again to `6aaaad54-99bb-5dc8-9d62-dea0b6c23fb5` and
   `(realized? database-base)` answered **false**: the base under the
   superseded publication is correctly not a realized base for this run.

2. In-process regressions, each on a daemon thread with
   `{:seon.test.run/provenance (seon.test.runner/provenance (seon.db/db c))
   :seon.test/remaining-ms …}`, the Var resolved through `seon.test`'s own
   loader AFTER reloading the namespace through it:

   - `seon.test-support-test/the-publication-key-derives-from-the-published-head`
     — 7 pass / 0 fail / 0 error.
   - `seon.test-support-test/the-shared-base-follows-the-published-commit`
     — 11 pass / 0 fail / 0 error.

   Both were re-run against the exact committed bytes after the
   single-construction bound landed, and both stayed green. An earlier pass of
   the first one also reported one worker-drift error — two re-armed
   `seon.test-support` contracts (`effective-config`, `transacted!`) — caused
   by the probe's own explicit `(require 'seon.test-support :reload)`; it is
   not produced by the test and did not recur once the reload preceded the
   run's own snapshot.
   - `seon.test-support-test/a-refused-fixture-write-is-reported-at-the-write`
     — 9 pass / 0 fail, **67389.731625 ms**, the branched-fixture path end to
     end under the new holds. That single run is the whole point: the head had
     advanced since the previous base was built, the fixture MISSED, built a
     new base from the current publication on its own daemon thread, forked a
     branch from it and completed — in process, with no manual rebuild and no
     restart. (Its one error is the same reload-induced drift artifact.)

3. Measured consequence, reported rather than tuned: in this JVM the
   `:current-src` head advanced four times in roughly sixteen minutes
   (`6aaaac9…` → `6aaaad05…` → `6aaaad54…` → `6aaaae8c…`) because several
   lanes adopt into the same development JVM. Every one of those is a miss for
   the shared base, and a miss costs one canonical population. That is correct
   — a base older than the publication it runs under is the defect this change
   removes — but it means a fixture-bearing in-process run in a busy JVM should
   budget roughly a minute, and the single-construction bound above is what
   keeps concurrent lanes from paying it twice at once. A cheaper design (an
   incremental upsert of the publication's own program rows into a retained
   base, instead of a full repopulation) is a real option and an owner-level
   decision, not this lane's.

## Verification boundary

Proven: the publication key's derivation and its two shapes; the miss on an
advanced publication, including the base's recorded key and a row only the new
publication carries; a run holding the old base completing while a later run
gets the new one, with the retired base closed only at zero holders; the
branched fixture path under the new holds, in process.

NOT proven here: a full `bin/test` gate (this lane launches no test JVM — gate
request in `tmp/orchestrator/gate-requests/shared-base.txt`), and the worker
path with `seon.test.published-base` set, which by construction never reads a
store head and so cannot exercise the commit-keyed branch.

Concurrent-editor note: `src/seon/test.clj`, `src/seon/test/runner.clj`,
`src/my/test.clj` and `src/seon/db.clj` were held uncommitted by the custody
lane throughout and were NOT touched. This lane's whole change is in
`test/seon/test_support.clj` and `test/seon/test_support_test.clj`.
