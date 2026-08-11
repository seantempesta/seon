---
type: research
status: active
tags: [research, testing, contracts, flow]
---

# Red classes B — phase-one prepared fixes

## Verdict

Phase one is complete at `2d9e2ef25`. No production or test source was edited.
The named complete-gate attribution and all six linked issue notes were read
end to end before probing. Reproduction used isolated test JVMs plus the
project-local probes under `tmp/red-classes-b/`.

Five classes still need a phase-two edit. The injected first-fault class is
already green after `3630a34cd`; it needs only issue archival after the frozen
gate lands. The background-binary timeout is not a lost listener event or a
worker that failed to finish: the request is refused before a receipt opens
because its fixture writes an incomplete effective-config row.

The co-hosted shared-context class is deliberately skipped because it awaits
the per-run fork design conversation. The nested-declared-face class is also
skipped because it awaits the ruled nested build. The init backstop belongs to
the suite-speed-tail record and is outside this lane.

## Dependency ledger

- Core.async/Flow is pinned at `dc35f3e0d7bc2eef502e77982f48641f025c8051`
  (`v1.10.874-alpha3`). The relevant sources are
  `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj`,
  `flow/impl.clj`, and `flow/spi.clj`. Flow ping exposes the live proc roster
  through the datafied graph; no second roster is needed.
- Datahike is pinned at `10540578248eaa686c1f88a7fe57644ee4c9f993`.
  `reference-code/datahike/src/datahike/core.cljc:199-218` establishes that a
  connection listener receives each committed transaction report. The tested
  listener is not responsible for a request refused before transaction.
- Malli is pinned at `80138076960e7820523b4cb932c5b5d1936d4e7f`.
  Seon's durable inverse is `seon.schema/canonical-definition`; it correctly
  refuses a function object with no recoverable qualified symbol.
- SCI is pinned at `6ee57c9c3e73e5b8224fde851e33a1e2a8e08383`.
  No SCI behavior is changed by these fixes.
- First-party owners are `src/seon/effect.clj`, `src/seon/flow.clj`,
  `src/seon/cluster.clj`, `src/seon/oversight.clj`,
  `src/seon/cluster/agent.clj`, `src/seon/env.clj`,
  `src/seon/cluster/loop.clj`, and `src/seon/search.clj`. The recurring proof
  owners are the six named tests in the complete-gate attribution.

## 1. Background binary settlement

Issue: [background binary settlement does not publish its required event](../../../seon/issues/background-binary-settlement-does-not-publish-required-event.md).

The named test still times out. The diagnostic probe retained the database
and datafied launcher after ten seconds. `effect/request!` returned this flat
value immediately:

```clojure
{:seon.error/kind :seon.effect/missing-background-time-limit
 :seon.config.effect.background/time-limit-ms nil}
```

No receipt existed and launcher active work was empty. The test's partial row
contains only `:seon.config/cluster` and the blob threshold. Commit
`aeb17ec7f` made a positive background time limit required before an effect
receipt opens; `test/seon/effect_test.clj:135-145` already records the correct
fixture rule: a test that reads effective config supplies the complete shipped
row, then overrides the dial under test. Calling `second` on the returned
error map produces a bogus would-be effect identity, after which the listener
wait hides the immediate refusal behind a timeout.

Prepared phase-two fix:

1. In `test/seon/background_blob_test.clj`, replace the partial config map with
   `(assoc (config/defaults) :seon.config/cluster "default"
   :seon.config.eval.result/blob-threshold 8)`.
2. Derive the expected lookup ref directly as
   `[:seon.effect/id (pr-str ["binary-run" 0 ordinal])]` and assert that the
   request returned it before awaiting any event. Enter the event/byte proof
   only when that acceptance assertion holds, so a future pre-open refusal is
   immediate and typed instead of a 60-second listener timeout.
3. Keep the existing listener assertion on `:seon.effect/to` and the exact
   binary readback on both sides of the threshold. Production settlement and
   Datahike listener code need no edit.

The existing long test remains the one class regression.

## 2. First injected core fault

Issue: [fault committer misses the first injected fault](../../../seon/issues/fault-committer-misses-the-first-injected-fault.md).

Both `3630a34cd` and its issue-closing documentation commit `7ebc65caa` are
ancestors of the current tree. The named
`an-escaped-throwable-becomes-a-fact-and-a-message` test passed in the focused
run: the panic line printed, the durable fact was observed, and the message
naming its fact was observed without reaching the old timeout.

Prepared phase-two action: make no code or test edit. Move this issue to the
archive with the focused evidence and the frozen-gate result. Do not re-fix
the recurrence or overflow work already owned by those commits.

## 3. Oversight proc roster

Issue: [oversight fleet test pins a stale proc roster](../../../seon/issues/oversight-fleet-test-pins-a-stale-proc-roster.md).

The focused live-cluster proof observes the deterministic roster mismatch:
the test's copied set omits `:seon.search/index`, while
`cluster-graph-definition` declares armer, render, and index. A concurrent
full-gate load also made the 20 ms turn ping miss during this phase-one probe,
which produced unrelated parked/mid-turn assertions; that does not change the
roster cause recorded by the quiet complete gate.

Prepared phase-two fix in `test/seon/oversight_test.clj`:

```clojure
(let [declared-plumbing
      (set (keys (:procs (datafy/datafy (:seon.flow/graph instance)))))]
  (is (= declared-plumbing
         (into #{} (map :seon.oversight/proc) plumbing))))
```

Add `clojure.datafy` to the test namespace. This compares oversight's rendered
fleet with Flow's live graph roster—the same source oversight reads—without a
hand list. Keep the existing pass-count and output assertions unchanged.

## 4. Schedule graph environment

Issue: [schedule graph test constructs a handle without an environment](../../../seon/issues/schedule-graph-test-constructs-a-handle-without-an-environment.md).

The named var reproduces the exact `seon.env/absent-environment` exception in
`env/scope`. Production `agent/graph-definition` correctly refuses the
impossible handle.

Prepared phase-two fix in `test/seon/schedule_test.clj`:

```clojure
(let [environment (test-support/environment "seon.schedule-test")
      handle (env/carry {:seon.schedule/channel ::channel} environment)
      definition
      (agent/graph-definition
       {:seon.cluster.loop/cluster handle
        :seon.cluster.agent/id "root"})]
  ...)
```

Require `seon.env` as `env`. This uses the same constructor and carriage
mechanism as production while building only the subset environment the pure
graph-definition test owns. No fallback is added to `seon.env`.

## 5. Public `settle!` contract

Issue: [`settle!` is public without a complete contract](../../../seon/issues/settle-is-public-without-a-complete-contract.md).

The public census reproduces exactly one missing function: `settle!`. Commits
`a8a38313c`, `f3033018e`, and `718761a9f` are all ancestors of the current
tree, and the prepared contract describes their current two input and two
output arms rather than an earlier terminal shape.

Prepared phase-two schema additions in
`resources/seon/schemas/seon.cluster.loop.edn`:

- `:settle-evaluation-request`: required cluster, instant, agent id, run id,
  form ordinal, and `:seon.sci.eval/evaluation`; optional declared form problem
  and message trigger.
- `:settle-failure-request`: required cluster, instant, agent id, and
  `:seon.error/value`; run id and form ordinal remain optional because open or
  claim refusal can occur before a run exists.
- `:settle-request`: the union of those two named arms.
- `:commit-outcome`: an open map requiring `:db-after` to be a
  `:seon.db/database-value`, which is the exact member terminal installation
  consumes from Datahike's raw transaction report.
- `:evaluation-settlement`: required settled disposition (nilable in-memory),
  evaluation, terminal receipt, staged blob writes, transaction data, and
  commit outcome; optional refused outcome.
- `:failure-settlement`: required flat error value, transaction data, and
  commit outcome; optional refused outcome.
- `:settlement`: the union of those named output arms.

Then declare on `settle!`:

```clojure
{:malli/schema
 [:=> [:cat :seon.cluster.loop/settle-request]
  :seon.cluster.loop/settlement]}
```

Maps remain open. No `:any`, `:some`, or anonymous callable is introduced.
The existing public census is the class regression, and the existing seeded
phase-failure property continues to exercise the terminal failure arm against
real database facts.

While grounding this shape, the consumer read of `::failure` at
`src/seon/cluster/loop.clj:1461` proved stale: no settlement producer writes
that key; refusal settlement writes `:seon.error/value`. This is independent
of adding the contract and is not included in this phase-two patch. It is
recorded as [the settlement consumer reads a key no producer writes](../../../seon/issues/loop-settlement-consumer-reads-a-key-no-producer-writes.md)
rather than being silently folded into a contract-only class.

## 6. `index-step` callable predicates

Issue: [a search contract predicate cannot be made durable](../../../seon/issues/a-search-contract-predicate-cannot-be-made-durable.md).

The named search test reproduces the refusal at
`schema/canonical-definition`. The source contains two unnamed predicate
objects, not one: both `seon.search/ping-map-fn?` and
`seon.search/datahike-datom?` are unquoted inside evaluated defn metadata.
Commit `b8d843549` registered both names but did not preserve those names in
the contract value.

Prepared phase-two fix: quote both qualified predicate symbols in
`index-step`'s schema, matching their already quoted `:gen/gen` symbols. The
existing test already checks both names, EDN round-trip, compilation, seeded
generation, and validation; no second point test is needed.

The required database-driven sweep queried 725 function rows carrying
`:seon.fn/spec`, resolved their loaded Vars, and ran
`canonical-definition` over their current metadata. It found 17
noncanonical contracts, including `index-step`. Therefore the issue's literal
claim that no other first-party contract writes an unquoted predicate is
falsified. The other 16 belong to the existing broader issue
[anonymous runtime contracts have recurred](../../../seon/issues/anonymous-runtime-contracts-have-recurred.md),
not to this search patch. Archiving the search issue after both local
predicates pass must not claim the global sweep is clean.

## Phase-two proof and archival order

After the orchestrator releases this lane with `gate landed`:

1. Apply the five prepared edits; make no change for the already-green fault
   class.
2. Run the six named tests, with the fault test serving as verification-only.
3. Run one `bin/test --changed` invocation naming every touched production,
   schema, and test file.
4. Archive the six issues with exact focused and changed-gate evidence. The
   search archive must preserve the broader 16-contract finding, and the fault
   archive must credit `3630a34cd` rather than claim a new fix.

Any red caused by another lane's in-flight source at that checkpoint blocks
verification only; report its exact boundary without editing or resuming the
other lane's work.
