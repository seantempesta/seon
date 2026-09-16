# Gate result recording: "The cluster rejected the prepl operation" (2026-09-16)

Read-only probe lane. HEAD `0e15593aa2cd5b3331499b5e7bb3ac562dc0c6f0` (branch
`steward-platform`); probes against the live `default` cluster (pid 45917,
prepl 127.0.0.1:55606, started 2026-09-16T06:51:16Z), one bounded MCP
evaluation at a time. No source, test, or configuration file was changed.

## The observed line and where each part of it comes from

```
bin/test: persistent results NOT recorded: :seon.test.runner/persistent-results-recording-failed The cluster rejected the prepl operation.
```

Both halves are produced in the COORDINATOR, not in the cluster:

- `script/seon/fresh_operator.clj:1605` — when a prepl `:ret` event carries
  `:exception` and its ex-data has no `:seon.boot/refused`, the operator
  raises `fail!` with the fixed message *"The cluster rejected the prepl
  operation."* and ex-data `{:seon.fresh-operator/events events}`. The
  events (which DO hold the cluster's `Throwable->map`) are never rendered.
- `src/seon/test/runner.clj:2007` — `recording-failure` catches that
  ExceptionInfo, takes `(:seon.error/kind (ex-data failure))` (absent →
  defaults to `::persistent-results-recording-failed`) and `(ex-message
  failure)`. Nothing else survives.

So the printed wrapper is structurally incapable of naming the rejection:
it is a check that reports the ABSENCE of a readable value as a fixed
sentence. That is the primary defect on this path.

## What the probes verified

1. The whole recorder path is healthy on an idle `default`. Reconstructing
   the exact form from `seon.test.runner/persistent-results-form`
   (`src/seon/test/runner.clj:1945`) for a minimal completion (one test
   symbol, the current HEAD sha, branch `:current-src`) and sending it over
   a socket to `default`'s own prepl exactly as `prepl-eval!`
   (`script/seon/fresh_operator.clj:1548`) does:
   - **`:exception` nil, 2010 ms**, terminal value a committed reference
     vector: `[#:seon.test{:pass-count 1, :fail-count 0, :error-count 0,
     :run-at #inst "...", :reach-digest "bbbb…", :run #:db{:id 45693},
     :run-basis-t 1, :sym "seon.custody-stability-test/cross-cluster-write-isolation"}]`.
   - The scratch fork + `force-branch!` on `:current-src`
     (`src/seon/cluster/source.clj:325-392`) completed with no stale-head
     retry.
2. Cluster-side failures come back as VALUES, never as prepl exceptions.
   Two reproduced rejections, both returned cleanly through the prepl with
   `:exception` nil:
   - contract refusal: `:seon.instrument/contract-violated` —
     *"seon.cluster.source/record-results! refused completion at
     [:seon.test.runner/results 0 :seon.test/pass-count] …"*;
   - writer rejection: `:seon.db/rejected`, `:seon.db/transaction-refused
     true` — *"Bad entity attribute … not defined in current schema"*.
   The recorder form's `(catch java.lang.Throwable …)` converts every
   in-cluster throw into `#:seon.error{:kind …, :message …}`, so an
   `:exception` reply cannot originate inside the form's body.
3. The form reads and prints cleanly (1373-1404 bytes for a 1-test
   completion; `read-string` round-trips).
4. The internal capture keys `::failure-messages` / `::failure-identities`
   are stripped at `src/seon/test/runner.clj:527` before the completion, so
   they are not the offending attributes in a real run (they DO reproduce a
   `:seon.db/rejected` when left in — useful as the class regression's
   negative case).

## What the probes falsified

- **Not the `current-src` stale-head race.** `record-results!`
  (`src/seon/cluster/source.clj:369-392`) already loops on
  `{:type :stale-branch-head}` until the declared allowance expires, and an
  expiry returns the `::recording-expired` diagnostic VALUE — it would be
  printed by kind, not as the prepl wrapper. The batch-34 A
  "Branch head changed before force-branch!" line is that retry working.
- **Not a concurrent publication.** Batch 40's recording failed at
  ~07:04:12Z; `logs/hook-debug.log` shows the previous `SOURCE_EDIT` at
  07:02 or earlier and the next at 07:04:37.727Z — no publication in flight.
- **Not an unreachable/degraded cluster.** `seon.test.runner` is loaded,
  the held operator store resolves, and the committer var derefs (1 ms).

## Verdict

The recording mechanism is green on an idle `default`; the failure is
reported through a wrapper that drops the only evidence that could name it.
An `:exception true` reply can only be raised by `clojure.core.server/prepl`
itself — i.e. at READ of the sent form, or from the out-fn emit in
`seon.cluster/mcp-io-prepl` (`src/seon/cluster.clj:478-495`), whose `prn` of
the projected `:ret` runs INSIDE prepl's own try, so a throw there is
re-reported as an exception `:ret`. Naming which of those fired requires the
events the wrapper discards. Verification boundary: I could not reproduce
the failure with a synthetic completion, and I did not run `bin/test`.

## Plan (not implemented)

1. **Minimal fix — make the wrapper carry the rejection value.**
   `script/seon/fresh_operator.clj:1605`: keep the parsed
   `Throwable->map` (`:cause`, `:via`, first trace frame) and the
   `:seon.fresh-operator/advertisement` in the diagnostic's ex-data, and
   name it with its own `:seon.error/kind`
   (`:seon.fresh-operator/prepl-exception`) instead of letting
   `recording-failure` default. `src/seon/test/runner.clj:1996-2010`:
   print `:seon.error/data` / the cause alongside kind and message.
2. **Then re-observe one gate** with the evidence in hand and fix the real
   cause; hypotheses ranked: out-fn emit of the projected return value >
   read of a real run-result > anything in `record-results!`.
3. **Class regression** (the class is "a diagnostic that reports absence of
   signal as a fixed sentence"): drive a cluster form that throws OUTSIDE
   the recorder's catch and assert the operator's refusal carries the
   cluster's cause and class — not the fixed string. Add the
   `:seon.db/rejected` recorder case (unstripped internal keys) as the
   value-path counterpart.
4. **Namespaces to gate**: `seon.dev.fresh-operator-test` (it already
   asserts the literal sentence at `test/seon/dev/fresh_operator_test.clj:1776`
   — that expectation is stale and is the fix's first casualty),
   `seon.test.runner-test`, `seon.cluster.source-test`, plus
   `bin/test --platform`.
