---
type: issue
status: open
severity: platform
created: 2026-09-24
tags: [errors, flow, unbounded-output, class, oom]
---

# An unstored panic prints its whole `Throwable->map` to stderr

## Evidence

`data/clusters/default/logs/seon.log` (pid 68592, 2026-09-24 ~16:10Z): line
64192 is `seon.flow/proc-failed!`'s panic print
(`{:seon.error/message "seon.fault/record! refused caps ..." :seon.error/operation
seon.flow/work-launcher-step :seon.fault/recorded nil :seon.error/data {:panic ...`).
The recording failed (`seon.config/effective-in` found no cluster row for
`"flow-evaluation"`), so the unstored branch printed
`(Throwable->map panic)` and `(Throwable->map failure)` with `prn`. The failure's
ex-data holds a contract refusal whose offending value is the whole world
(`:seon.db/connection`, render profile, `:seon.sci.eval/ctx` with projection,
registry, decoder maps), and nested `#error {...}` values print their ex-data
whole again. Lines 64194-64558 (fragments interleaved with test output) total
about 3.18 GB, single lines up to 1,052,860,504 chars; the log is 3.75 GB and
21 lines over 100 KB hold 3,738,172,626 bytes. The JVM died of
`OutOfMemoryError: Requested array size exceeds VM limit` at line 69114.

## Sites

- `src/seon/flow.clj:210-219` `proc-failed!`: `prn` of `Throwable->map` of
  panic and failure.
- `src/seon/cluster/store.clj:610-617` `listener-failed!`: the same shape, plus
  suppressed throwables.

## Owner and fix

The bounded throwable printer already exists: `seon.render.value/floor`
(`src/seon/render/value.clj:119`, at most 8192 chars, whole cause chain with
messages, 12 ex-data entries, first-party frames). `seon.cluster.boot/uncaught-emergency!`
(`boot.clj:320-330`) already prints unstored faults through it. Replace each
`Throwable->map` print with `(println (render.value/floor panic))` and
`(println (render.value/floor failure))`; the whole value stays in memory for the
REPL (`*e`/ex-data), and storage failure still panics loudly.

## Related

Q4 of lane fix-unbounded-printed-values: the refusal itself is
`seon.error/prepare-result` called from `seon.sci.eval/shown-result`
(`sci/eval.clj:3113-3128`) with `(render/request-profile request)`; that
returns a request-carried `:seon.render/profile` unvalidated
(`src/seon/render.clj:129`), and the flow evaluation request carried a profile
lacking `:seon.render.profile/id`.
