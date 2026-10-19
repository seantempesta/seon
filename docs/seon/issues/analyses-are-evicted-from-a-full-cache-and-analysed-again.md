# Analyses are evicted from a full cache and analysed again

Lane closed-p7, 2026-10-20, HEAD `57fa7fa7d` and `1bcb3bf65`.

`seon.sci.runtime-test/a-hundred-branches-analyse-each-distinct-digest-once` is red when it runs in one check JVM
after `seon.cost-estimate-test/closed-runtime-rows-grow-past-no-accepted-overrun`, `seon.schema-test` and
`seon.config-test`, and green alone. Measured by a probe that replays the test's scenario and reads
`seon.cache/status`:

| Tree | digests analysed twice | `:seon.sci.runtime/analysed` evictions during the test | cache weighed |
|---|---|---|---|
| HEAD `b75caf440` | 41 | 386 | 495 of 512 MB |
| P7 candidate | 15 | 212 | 511 of 512 MB |
| `1bcb3bf65` commit check (same order) | 21 (5,764 against 5,743) | | |

Every duplicate re-analyses an evicted entry under the same `seon.sci.runtime/analysis-key`: the keying is exact.
An analysis (`seon.sci.runtime/analysed-function-bytes`, about 18 KB retained) is shared by every branch and costs
milliseconds to redo, while the cache at its budget evicts it before kinds that weigh far more. To decide at the
owner (`seon.cache`): its weight or the cache's admission keeps a program's analyses resident under the shipped
budget, or the test owns its cache state. The test's claim is not loosened.

Not filed as a task with this proof: `bin/lane commit --accept-red-before` re-runs the var alone on HEAD, where it is
green, so an order-dependent red cannot carry a task until the ordering is part of the proof.
