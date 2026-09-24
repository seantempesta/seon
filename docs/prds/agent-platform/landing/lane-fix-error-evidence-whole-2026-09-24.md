# Lane fix-error-evidence-whole — 2026-09-24

## Broken, or not proven

- **No regression for the step fix (eb14a2f22).** A test cannot make
  `next-agent-work` refuse without `with-redefs`, which the laws forbid, or a
  store-close race. I tried making a listened attribute unindexed so that
  `wake/declarations-refusal` would fire, but Datahike refused the schema
  alteration ("Update not supported for these schema attributes"). The proof
  is live only (see Evidence).
- **The underlying cause is not fixed.** Agent graphs on test fixture branches
  keep stepping after their store closes. Filed as
  `docs/seon/issues/an-agent-graph-steps-after-its-test-branch-store-closes.md`.
- **`seon.fault-test/record-mode-stores-the-whole-fault-and-wakes-the-responsible-agent`
  fails its 1000 ms bound.** It took 1093 ms and then 1172 ms, with machine
  load average 9–11 from hook test runs. Its blob assertions pass.
  - This change adds no work on that path. The same prepare and stage calls
    run, plus `evidence-stage` at about 0.005 ms.
  - A parent-commit timing was not taken: the one-JVM rule forbids a second
    JVM.
- **The `:seon.error/recording` schema** (resources/seon/schemas/seon.error.edn:213)
  does not declare `:seon.blob/staged-writes`. The map is open, so it
  validates, but the key should be declared. That file is outside this lane,
  so it needs a grant.
- **Where the gap remains.** `error/commit-tx` callers still record a capped
  fault with no blob, because `commit-tx` returns only transaction data. They
  are `sci/eval.clj:1889`, `cluster.clj:3432` and `cluster_test`. A fact that
  already carries a `prepare-result` blob (the rendered offending value) is
  not restaged.
- **Staging behaviour change in `fault/record!`.** It used to stage a blob
  whenever `data-size` exceeded the blob threshold (4096), even when the fact
  was not capped. It now stages only capped facts. An uncapped fact already
  holds its whole evidence inline, so no cause is lost.
- **Foreign reds seen during verification, not caused by this lane:**
  - `seon.schedule-test/returned-and-thrown-handler-errors-use-the-existing-root-wake`:
    the fixture inherits four `seon.render/unknown` errors from default's head,
    so its counts read 5 instead of 1.
  - `seon.turn-loop-test/a-refused-terminal-commit-still-closes-the-run` and
    `a-refused-batch-settlement…`: the fixture write is refused on
    `:seon.program/definition-digest`.
  - `seon.turn-loop-test/a-refused-phase-escalates…`: its phase-failed source
    maps to no declared schema.
  - `seon.turn-loop-test/delivery-rows-projects-rows-and-every-refusal-transaction`:
    it stubs `error-tx` through `with-redefs` with the wrong arity. It would
    also now see an extra `:seon.blob/staged-writes []` key.
- **Publication was refused at 17:43Z** by a foreign file's static analysis
  (`test/my/program_mutation_test.clj`: unresolved `program/ns-unmap!` and
  others). The last hunk, `vec` around the batch refusal's staged writes, may
  not be installed yet.

## What changed

- **`seon.error/evidence-stage`** is a new function. Given a connection and a
  capped fact with no blob, it calls `blob/stage!` on `data-content`, sets
  `:seon.error/data-blob`, and returns `:seon.blob/staged-writes`.
- **`recording`** calls `evidence-stage` with the request's
  `:seon.db/connection`, then dissociates the connection before the value
  reaches the transaction function. It returns the staged writes. The 6-arity
  forwards the cluster's connection.
- **Callers now publish the staged writes with their commit:**
  - turn `refusal-terminal-data` (its commit already runs `with-publication!`);
  - turn batch refusal (now wrapped in `with-publication!`);
  - turn `error-tx` (now calls `recording`), threaded through `delivery-rows`
    into the evaluation settlement's staged writes;
  - turn AI attempt;
  - `schedule/settle!`, which prepares and stages outside the transaction
    function and hands the fact to `settle-call`;
  - `fault/record!`, which deletes its own staging and uses `evidence-stage`.
- **`seon.turn/step`** now throws a refused `next-agent-work` whole to the
  graph's fault route instead of passing it to `turn`. This came from the
  root follow-up.
  - The situation value outside its schema came from none of the three
    candidate commits. The unhandled error arm dates from 820d0ab60.
  - The value itself was a `seon.db/q` read refusal
    (`ClosedChannelException`) on a test agent branch.

## Evidence

- **Before the fix**, on default, root's occurrence 46155 (turn
  `38c5a9b4d625`) had:
  - `data-size 25879`, with 1342 chars of capped `data-edn`;
  - a remainder marked `:over-bound`;
  - no blob and no `:seon.render.unknown/reason`.
- **Reproduction** (`seon.error/prepare`, default caps, max-evidence 16384):
  - `data-content` was 27 860 bytes and contained the reason;
  - the fact did not contain it, and `data-blob` was nil.
- **After the fix, same probe** (tmp/fix-error-evidence-whole/probe.clj):
  - the over-cap `recording` sets the fact's `data-blob` to `360e1b6d…` and
    stages 27 560 bytes;
  - the under-cap staged writes are `[]`.
- **Regression:** `bin/test-check default --ns seon.error-evidence-test`
  passed 9/9 (run a7dfca5f146a). It covers two cases:
  - the turn path through `turn/settle!`, where the capped inline text lacks
    the tail marker and the blob contains it;
  - the `fault/fault!` path, with the same assertions.
- **`seon.fault-test`:** 32 pass and 1 fail, the timing bound above.
- **Step fix, live:**
  - the last `seon.turn/turn` contract fault is from 17:29:32Z;
  - after installation, the same class records as a `seon.db/q` read refusal
    from `seon.turn/step` (signature `d3ef61b6…`, branch
    `:agent-96b7917bed55`), with blob `a11ac961…`.

## Numbers

- **Under-cap fault, before:**
  - `prepare`: 0.967 ms per call (100 warm calls);
  - `recording`: 0.898 ms per call.
- **Under-cap fault, after:**
  - `evidence-stage`: 0.004–0.006 ms;
  - `recording` with a connection: 0.720 ms, against 0.737 ms without one
    (same load);
  - added cost is about 0 ms.
- **Over-cap turn settlement:** under 1000 ms, asserted by the regression.
- **Net lines:**
  - 5c968dd34: +71 −34 across four source files, plus the 89-line test;
  - eb14a2f22: +6.
- **RESET NEEDED:** no.

## Commits

- 0b807c175: first landing note (stop and options)
- 5c968dd34: whole evidence staged on every recording path, plus regression
- eb14a2f22: refused next-work derivation escapes as its own refusal
- this note and the issue file
