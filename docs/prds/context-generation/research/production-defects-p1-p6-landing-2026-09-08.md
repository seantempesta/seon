---
type: research
status: complete
date: 2026-09-08
tags: [research, repair, instrumentation, storage, agent]
---

# Landing note: production defects P1-P6 and the frictions around them

Written by the `production-defects-p1-p6` lane against
[AGENTS.md](../../../../AGENTS.md) §2.4 and §5, read end to end;
[the independent verification](verify-repair-2-2026-09-08.md), read end to
end — its P1-P6 and its frictions 10-14 and 18 are the assignment; and
[the repair's landing note](storage-bound-repair-2-landing-2026-09-07.md),
read end to end. Skills read: `data-oriented-clojure`, `clojure-testing`,
`repl`.

The live surface is the development cluster the edit hook publishes to:
root `tmp/juniper-context-live`, cluster `juniper-context`, converged at
`:current-src` commit `6a9fe616-1b2f-5158-9936-033ccb708f8f`.

## The defects, and the class each one belongs to

### P1 — every core fault was unrecordable

`seon.cluster/commit-fault!` built ONE request, added
`:seon.config.error/max-evidence-bytes` only to the copy it handed
`seon.error/prepare`, and handed the original to `seon.error/commit-tx`,
which declares the same key required. The committer violated its own
contract on every fault, and the operator printed
"A core fault could not be normalized" about its own refusal.

The dial now rides the one request the committer builds from the cluster's
effective config. Every other `commit-tx` caller was checked and already
reads its dial from an environment value, never a constant:
`seon.schedule/error-request` from the cluster handle
(`src/seon/schedule.clj:519`), `seon.cluster.loop/error-tx` from the loop's
own dials (`src/seon/cluster/loop.clj:447`), and
`seon.sci.eval/record-acquisition-refusals!` from the effective config
(`src/seon/sci/eval.clj:1447`).

Note for the record: the production hunk itself was swept into a sibling
lane's path-limited commit `f325d9dfd` while both lanes held
`src/seon/cluster.clj` in the shared tree. The fix is at HEAD; the
regression is this lane's.

### P2 and P3 — a staged blob faulted the turn and left the run open

`seon.cluster.loop/settle-batch!` branched on `(seq staged-writes)` and
handed `seon.blob/with-publication!` a `ChunkedSeq` where the declared
input is `[:vector :seon.blob/staged-write]`. `with-publication!` is
already total over an empty vector, so the branch had nothing to decide;
the caller hands it the vector it built. An agent's own
`(def x <over the 4,096-byte blob threshold>)` was the trigger.

The cascade was the worse half: the violation ESCAPED the settlement, so
no arm closed the run and every later submission was refused
`:seon.cluster.run/agent-already-running` until the JVM was restarted. The
commit now runs under `phase`, so a host failure is a refused phase value
that the refusal arm settles — and that arm closes the run. A failure to
record a fault may never leave a run open.

### P4 — the MCP bridge cast every `:ret` value to a map

`enrich-projection-elisions` `update-in`s `[:val :seon.dev.mcp/value]` on
every `:ret` event. Only a DECODED projection carries a map there; a raw
io-prepl `:ret` carries the printed string, and the whole transport threw
`String cannot be cast to Associative`. The walk now asks the shape
question it means.

### P5 — an absence handed into a contract that forbids it

`seon.sci.eval/database-effective-config` answered nil for a database with
no config singleton and `instrumentation-config` passed it to
`seon.config/result-caps`, whose declared input is the effective config OR
the missing-effective refusal. The read now returns that typed refusal.
`seon.instrument/wrap-interpreted` accepts either — an accretion —
because `:record` never reads caps, while `:panic` refuses NAMING the
function and the missing config key rather than arming a contract whose
violation reporter has no bound.

One follow-up the change exposed: `record-acquisition-refusals!` fell back
to the shipped decisions with `(or (database-effective-config db)
(config/defaults))`, which would have carried the refusal onward. The
fallback now keys on the refusal.

### P6 — a generator that could not read back

`node-generator` built `::set` items from `(gen/vector inner 0 3)`. Node
equality is not the test and neither is emitted text: `[]` and `()` are
different literals and the SAME set member, which is what the round-trip
property shrank to. THE READER IS THE AUTHORITY ON DUPLICATES, so children
are distinct by the value their emitted literal reads back as. Map and
record entry keys carry the identical hazard and got the identical
treatment.

## The frictions

| friction | what landed |
|---|---|
| 10 — the diagnostic named neither the key nor a caller | every Malli problem is reported with its path (for a missing required key the path IS the key); the headline names the first four and COUNTS the rest, so it stays under the 64-estimated-token bound its own regression measures; the value carries `:seon.instrument/problem-paths` and `:seon.instrument/caller`, the first frame that is neither malli, the host, nor the reporter |
| 11 — two stale expectations | the agent-namespace uniqueness test now asserts what the declaration says (assignment is not identity; several agents share one namespace) and the unique-rejection diagnostic moved to `:seon.cluster.eval/refreshes`, the one genuinely unique-value attribute; the debug page test asserts the `prompt comparison` status and the named prospective pane |
| 12 — three gate-only vars | the worker LOADS the program before it arms it: `declared-program-namespaces` reads every namespace under `src` from its own `ns` form, so `seon.artifact` and `seon.test` are armed in the gate exactly as boot arms them. `the-gate-runs-under-the-contracts-a-cluster-runs-under` derives the expected set the same way and asserts set equality, excluding primitive fns by malli's own rule (`reference-code/malli/src/malli/instrument.clj:15`) — which is why `seon.sci.admit/required-cap` is armed nowhere and cannot be: its `^long` return hint is deliberate, and its hand-written throw is its check |
| 11b — `arm-contracts!` asserted nothing | a worker that arms zero vars now refuses, naming the count and the program namespace count. `instrumented= 0` can no longer be green |
| 13 — `elision-node` stripped requery coordinates | the strip stays for `prefix`, `bound-by` and an unknown `total`; a missing `:seon.render.data/path` or `/next-offset` is a defect AT THE CALLER and the constructor refuses |
| 18 — AGENTS.md contradicted the declaration | the `my.agents.<id>` vocabulary row now says assignment is not identity and names `:seon.ns/steward` as the one agent a namespace answers for |

Frictions 14 (`fit-children` overriding a caller-declared `::bound-by`),
15 (`node-face-validator*` reading the packaged population), 16
(`load-declared-predicate-owners!` swallowing `Throwable`) and 17 (the
"314 reds" arithmetic) were outside this lane's assignment and are
untouched.

## Live proof

On `juniper-context`, converged at commit
`6a9fe616-1b2f-5158-9936-033ccb708f8f`, through MCP `eval_clj` in `jvm`
mode.

**P2/P3.** `seon.cluster.agent/submit-source!` with
`(def p16-big-def (apply str (repeat 10000 "m")))`:

```clojure
{:seon.cluster.run/id "source:8b99ae77-2e5d-4cb0-aeb2-2305c89bcc57"
 :seon.cluster.run/closed-at #inst "2026-09-08T10:41:25Z"}   ; no error, no process
{:seon.def/key "[\"juniper\" \"my.agents.juniper/p16-big-def\"]"
 :seon.def/size 10002
 :seon.def/blob "72bcd3f7f61fc5cb48929154d1438525ad00825ec62907ad7899c39ca954a1ec"}
```

The run CLOSED and released custody, and the 10,002-byte value is stored
as a published blob. The next submission is not refused:

```clojure
{:seon.cluster.run/id "source:e2e4a30e-0d89-42c9-a3ea-7dfe760e9d5b"
 :seon.cluster.run/closed-at #inst "2026-09-08T10:41:38Z"}
;; (count p16-big-def) => #:seon.print{:face :seon.print/number, :value 10000}
```

**P1.** One ordinary core fault onto the live cluster's own
`:seon.flow/fault-channel`:

```clojure
{:faults-before 14 :faults-after 15
 :seon.error/id "3951055b-af7e-4b02-9954-33125dc4b03c"
 :seon.error/message "P16 live core fault probe"
 :seon.error/signature "1223ada1d7cd0c186c19151ed642bf41b8df452b27dfe1609dc75668ac8689a3"
 :seon.error/process "59736-1788858022686"
 :seon.error/data-size 12269}
```

A stored fact with its own message, signature, provenance and evidence
size — not a refusal about itself.

## Gates

### `bin/test seon.cluster-test seon.cluster.loop-test seon.error-test seon.print-test seon.sci.eval-test seon.test-runner-test seon.instrument-test`

Baseline (`e634d9c0c`, in a throwaway worktree): **191 tests, 893
assertions, 19 red**. At HEAD: **197 tests, 923 assertions, 16 red**
(`tmp/p16/baseline.log`, `tmp/p16/gate1.log`).

| namespace | baseline | now |
|---|---|---|
| `seon.cluster-test` | 0 | 0 (two new tests, both green) |
| `seon.cluster.loop-test` | 5 | 5 (two new tests, both green) |
| `seon.error-test` | 0 | 0 |
| `seon.print-test` | 1 | **0** — P6's round-trip property is green |
| `seon.sci.eval-test` | 8 | **5** — P5 cured three |
| `seon.test-runner-test` | 0 | 1 — see below |
| `seon.instrument-test` | 5 | 5 |

The one red this lane added is not its new assertion.
`the-gate-runs-under-the-contracts-a-cluster-runs-under`'s ORIGINAL first
block asserts a contract refusal from ambient instrumentation, and it goes
red whenever `seon.instrument-test` — which arms and removes
instrumentation as the behaviour it exists to prove — lands in the same
pooled worker. The runner's own confirmation phase names the shape:
`confirmation parallel-only`. The new block (`= program loaded`,
`seon.artifact`, `seon.test`, `seon.test/run`) passes. Filed as
[an-armed-contract-test-is-unarmed-by-another-test-in-the-same-worker](../../../seon/issues/an-armed-contract-test-is-unarmed-by-another-test-in-the-same-worker.md).

### `bin/test --platform`

```text
Ran 73 tests containing 398 assertions.
0 failures, 0 errors.
```

**GREEN — 73 / 398 / 0**, the same figure the verification and the repair
before it recorded (`tmp/p16/platform.log`).

## Unfinished

- The cross-test instrumentation hazard above is FILED, not fixed. The
  right fix is the runner's own mechanism — schedule
  `seon.instrument-test` in a worker group nothing else co-runs with, the
  way root-owning tasks already are — and it is a scheduling change this
  lane could not validate inside its budget.
- The five `seon.cluster.loop-test` reds, the five `seon.instrument-test`
  reds and the five remaining `seon.sci.eval-test` reds are all present at
  the baseline commit and outside this assignment.
- Frictions 14, 15, 16 and 17 of the verification are untouched, as
  assigned.
- `seon.sci.admit/required-cap` remains armed nowhere. That is CORRECT
  rather than outstanding: its `^long` return hint keeps a per-node walk
  off the instrumented path deliberately, malli cannot wrap a primitive
  fn, and its hand-written throw is the check. The gate's set-equality
  assertion excludes it by malli's own rule rather than by name.
