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

## The fix and what it now names (2026-09-16, fix lane)

HEAD at the fix: branch `steward-platform`. REPL-driven against the live
`default` (one bounded MCP evaluation at a time); no cluster was stopped,
started, or reforked, and no test JVM gate was run.

### 1. The operator refusal carries the cluster's cause

`script/seon/fresh_operator.clj` — the `:exception` branch of `prepl-eval!`
no longer raises a fixed sentence. The reply's `:val` is read AS DATA (it is
the cluster's printed `Throwable->map`) by two new helpers,
`prepl-exception-evidence` and `prepl-exception-message`, and the refusal
now carries its own kind plus the evidence:

```
:seon.error/kind                        :seon.fresh-operator/prepl-exception
:seon.fresh-operator/cause              the innermost ex-message
:seon.fresh-operator/via                [{:type :message :at :data} …]
:seon.fresh-operator/exception-data     the root ex-data
:seon.fresh-operator/trace-frame        the first frame
:seon.fresh-operator/form               the sent form, clipped to 200 chars
:seon.fresh-operator/advertisement      the addressed transport
:seon.fresh-operator/events             the whole prepl event sequence
:seon.fresh-operator/unreadable-value   only when `:val` is not readable data
```

A reply whose `:val` is not readable (`#object[…]`) is named as such with the
clipped raw value — never swallowed into silence. The `:seon.boot/refused`
branch is unchanged.

### 2. The gate line prints the cause

`src/seon/test/runner.clj` — `recording-failure` keeps the raiser's ex-data
under `:seon.error/data` (it previously kept only kind and ex-message), and
the new `recording-failure-notice` composes the printed line from kind,
message, and that data with the raw `:seon.fresh-operator/events` dropped
(bounded at 4000 chars). Measured line for an operator refusal:

```
bin/test: persistent results NOT recorded: :seon.fresh-operator/prepl-exception
The cluster threw during the prepl operation: java.lang.StackOverflowError: boom
#:seon.fresh-operator{:cause "boom", :form "(try (require ...))"}
```

### 3. Live proof through the OPERATOR path against `default`

`seon.fresh-operator/live-root-value!` with root `"."` — exactly the call
`seon.test.runner/record-persistent-results!` makes — driven from a probe JVM
(`tmp/recording-cause-read-probe.clj`), addressing the owner's live `default`:

| sent form | message | kind | data carried |
|---|---|---|---|
| `#seon.probe/no-such-tag[1 2 3]` (READ failure) | `The cluster threw during the prepl operation: clojure.lang.LispReader$ReaderException: No reader function for tag seon.probe/no-such-tag` | `:seon.fresh-operator/prepl-exception` | cause, via type, clipped form |
| `(throw (ex-info "…" {:seon.error/kind :seon.probe/outside-the-catch}))` | `… clojure.lang.ExceptionInfo: probe: recorder analogue threw outside the catch` | same | `:seon.fresh-operator/exception-data #:seon.error{:kind :seon.probe/outside-the-catch}` |

Both previously printed only *"The cluster rejected the prepl operation."*

### 4. The real cause was NOT reproduced — two hypotheses falsified

- **The full recorder path is green through the operator.**
  `record-persistent-results! "." run-result` for a one-test completion
  (`seon.custody-stability-test/cross-cluster-write-isolation`, program
  digest `6676d170…54740`, basis-t 536871363, branch `:current-src`)
  committed in **2229 ms**, returning
  `[#:seon.test{:pass-count 1 … :run #:db{:id 45694}}]`
  (`tmp/recording-cause-operator-probe.clj`). A deliberately wrong
  `:seon.test.run/branch` string came back as a contract-refusal VALUE in
  149 ms, with `:exception` nil — confirming cluster-side refusals are
  values, never prepl exceptions.
- **The out-fn emit hypothesis (`src/seon/cluster.clj:478-495`) is refuted
  for size.** Returned reference vectors of 1, 50, 400, 1200 and 4000 maps
  through the same operator send: **183 B / 9.1 KB / 73 KB / 221 KB /
  739 KB printed, 114-217 ms, every one returned complete and readable,
  none windowed or blob-settled** (`tmp/recording-cause-emit-probe.clj`).
  A large return value does not make the emit throw.
- The archived gate logs carrying the sentence
  (`tmp/orchestrator/*-stdout.log`) are GREEN runs of 15-16 tests — small
  completions with no failure payloads — so neither completion size nor
  failure values explain them either.

Verification boundary: the failure remains unreproduced on demand. The next
occurrence now prints the cluster's own cause, kind, ex-data and the sent
form; that line is the evidence to act on, and no further guessing is
warranted until it appears.

### 5. Regressions (run in-process on `default`, no test JVM)

`(seon.test/run (#'seon.test/resolve-test 'sym) (seon.operator/connection "default"))`
after reloading the test namespaces through `seon.test`'s own loader:

| test | result |
|---|---|
| `seon.dev.fresh-operator-test/prepl-exception-refusal-carries-the-cluster-cause` | 13 pass, 0 fail, 0 error |
| `seon.dev.fresh-operator-test/unreadable-prepl-exception-value-is-named-not-swallowed` | 4 pass, 0 fail, 0 error |
| `seon.dev.fresh-operator-test/eval-failure-falls-back-to-sigterm` (stale fixed-sentence expectation replaced) | 8 pass, 0 fail, 0 error |
| `seon.test-runner-test/recording-refusal-notice-names-the-cluster-cause` | 9 pass, 0 fail, 0 error |

## The real cause: data travelled as code (2026-09-16, recording-size lane)

Batch 44 (`tmp/orchestrator/gate-results/batch-44/platform.log`) printed the
cause the previous slice made visible:

```
bin/test: persistent results NOT recorded: :seon.fresh-operator/prepl-exception
The cluster threw during the prepl operation:
clojure.lang.Compiler$CompilerException: Method code too large!
  … java.lang.IndexOutOfBoundsException at clojure.asm.MethodWriter.computeMethodInfoSize
```

`persistent-results-form` inlined the ENTIRE completion — every test's
results, its structured failure facts (larger since `8199364a2`) and the
reach digests — as a quoted literal inside the `(try (let …))` form sent over
the prepl. The cluster therefore compiled ONE method whose bytecode grew with
the result count, and a gate-sized run passed the JVM's 64 KB per-method
ceiling. Batch 37 (21 tests) recorded; batches of 86–178 tests did not. This
is the transport law read backwards: durable facts were riding a compiled
form instead of a payload.

### The fix — the completion is staged as EDN, the form names its path

`src/seon/test/runner.clj`, the persistent-results region:

- `stage-completion!` writes the completion as EDN under the coordinator's
  run root (`seon.test.root`, else `seon.test.source-root`, else `.`) in
  `tmp/gate-completion-<run-id>-<nanos>.edn`;
- `persistent-results-form` now takes only that path — the sent form resolves
  the held store and calls `commit-staged-completion!` with the path;
- `commit-staged-completion!` (the cluster end) reads the file through
  `staged-completion` and commits it through the existing
  `commit-persistent-results!` path;
- `staged-completion` is TOTAL: a missing file, a truncated/unreadable file,
  and a value that is not a completion map each return a typed
  `:seon.test.runner/staged-completion-unreadable` value carrying
  `:seon.test.runner/completion-path` — absence is never read as an empty
  completion (which would retract every recorded result);
- `record-persistent-results!` deletes the staged file in a `finally` after
  the reply; the cluster deletes nothing.

Instants round-trip: the completion carries only strings, symbols, keywords,
ints and `#inst`, all of which `clojure.edn/read-string` reads by default.

### Measured

Same synthetic 2000-result completion (206,601 bytes of EDN), measured in the
live `default` JVM:

| | sent form |
|---|---|
| before (completion inlined) | **207,166 bytes** |
| after (path named) | **889 bytes** |

The form's size is now independent of the result count — its only variable
part is the absolute path.

### Live proof through the operator path (`seon.fresh-operator/live-root-value!`)

Driven from a probe JVM (`clojure -Sdeps '{:paths ["src" "resources" "script"]}'`)
against the owner's live `default` — `bin/test`'s exact send. Nothing was
stopped, restarted or reforked.

| sent | form bytes | wall ms | reply |
|---|---|---|---|
| OLD transport, 500 results inlined | 57,420 | 158 | `500` — compiles |
| OLD transport, 2000 results inlined | 207,166 | — | **`Method code too large!`** — the batch-44 line, reproduced on demand |
| NEW transport, 500-result completion staged | **901** | **261** | the cluster read the staged file and reached `record-results!`, which returned its contract refusal as a VALUE (`:seon.test/run-basis-t` absent — the probe's own completion was incomplete) |

So the cause is confirmed by construction: the same data inlined blows the
method-code ceiling, and named by path it does not. The new send carries a
57,661-byte completion in a 901-byte form.

Verification boundary: the live send was deliberately not given a valid
`:seon.test/run-basis-t`, so no synthetic rows were written to `default`'s
`:current-src`. The commit of a staged completion is proven in-process at
2000 results against the canonical fixture (below). `bin/test` itself was not
run by this lane.

### Regressions

`test/seon/test_runner_test.clj`:

- `gate-completions-travel-as-a-file-not-as-code` — stages a 2000-result
  completion, asserts the completion itself exceeds 65,536 bytes, the sent
  form is under 1 KB and contains no result, the file reads back as the exact
  completion including its `#inst`, and all 2000 results commit as test rows
  against the canonical fixture;
- `a-missing-or-unreadable-staged-completion-is-named` — the three absence
  cases return the typed refusal naming the path.

### In-process runs (live `default`, no test JVM)

`(seon.test/run (#'seon.test/resolve-test 'sym) (seon.operator/connection "default"))`
after reloading `seon.test-runner-test` through `seon.test`'s own loader:

| test | result |
|---|---|
| `seon.test-runner-test/gate-completions-travel-as-a-file-not-as-code` | 7 pass, 0 fail, 0 error |
| `seon.test-runner-test/a-missing-or-unreadable-staged-completion-is-named` | 7 pass, 0 fail, 0 error |
| `seon.test-runner-test/recording-refusal-notice-names-the-cluster-cause` | 9 pass, 0 fail, 0 error |
| `seon.test-runner-test/persistent-recording-failure-refuses-a-successful-gate` | 10 pass, 0 fail, 0 error |
| `seon.test-runner-test/explicit-result-root-directs-bare-gate-evidence` | 2 pass, 0 fail, 0 error |
| `seon.test-runner-test/result-recording-is-total-under-concurrent-test-retraction` | 5 pass, 0 fail, 0 error (one earlier run reported 1 error under concurrent lane load and did not reproduce; that test does not reach the changed region) |
| `seon.test-runner-test/result-facts-live-on-the-test-row-and-reruns-replace-them` | 10 pass, 0 fail, 0 error |

One design note the live proof produced: `requiring-resolve` does NOT resolve
`seon.cluster/running-instances` in the cluster (it returns nil where
`ns-resolve` returns the var), so the sent form keeps the original
`(ns-resolve 'seon.cluster (symbol "running-instances"))` spelling. A form
written with `requiring-resolve` fails with `@nil` — caught by the live send,
not by any in-process test.
