---
type: landing
status: complete
created: 2026-09-22
tags: [agent-platform, operator, boot, evidence]
---

# Operator client defects

## Result

The connected boot reply now declares `:seon.operator/process-exit?` as data.
When the last instance is removed, the core-only PREPL owner prints and flushes
that reply, then halts the launched JVM with exit code 0. The client awaits the
captured `ProcessHandle.onExit` before returning, so the following `start` takes
the existing cold-launch path without retry or a second discovery mechanism.

Every `seon.cluster.boot/request!` response is round-tripped through
`clojure.edn/read-string` at the producer. Non-EDN diagnostic evidence is
replaced by a data-only diagnostic containing its printed text and reader error.
The client separately refuses any connected operator reply that is not a map;
no custom readers were added.

The B1b command table and `:seon.operator/response` schema now include the
boolean process-exit observation for `stop` and `down`.

## Probes

Initial scratch root:
`/Users/sean/src/seon/tmp/operator-client-defects-root`.

Pre-fix sole-instance sequence:

```text
bin/seon --root tmp/operator-client-defects-root start probe
bin/seon --root tmp/operator-client-defects-root stop probe
bin/seon --root tmp/operator-client-defects-root start probe
```

Observed values: start PID `30299`; stop returned
`{:seon.boot/cluster-name "probe" :seon.operator/stopped? true}`; the process
remained selected with no advertisement; the next start refused with
`An exact-root JVM is alive but its endpoint is unavailable`.

Raw PREPL reply probe submitted the ordinary `:down` request and printed the
terminal event's value before the client parsed it. Current HEAD printed:

```clojure
{:raw-ret
 "#:seon.operator{:stopped-processes [#:seon.boot{:pid 30273, :start-instant #inst \"2026-09-22T10:14:08.649-00:00\"}]}"
 :raw-ret-pr-str
 "\"#:seon.operator{:stopped-processes [#:seon.boot{:pid 30273, :start-instant #inst \\\"2026-09-22T10:14:08.649-00:00\\\"}]}\""}
```

Thus the monitor's `#'` failure was not reproducible from current HEAD; it was
observed while the live tree was partially adopted. The producer boundary is
now total for that class: arbitrary exception data cannot cross unreadably.

Clean-snapshot post-fix sequence returned:

```clojure
{:seon.boot/pid 32758, ...}
{:seon.boot/cluster-name "probe"
 :seon.operator/stopped? true
 :seon.operator/process-exit? true}
{:seon.boot/pid 32786, ...}
```

The second PID differs and was ready. The final `down` returned a readable map.
Warm boots in this sequence reported `5,495 ms` and `5,665 ms` readiness.

## Recorded verification

Command:

```text
bin/test-fast --paths resources/seon/operator/prepl.clj resources/seon/schemas/seon.operator.edn script/seon/operator.clj src/seon/cluster/boot.clj test/seon/cluster/boot_test.clj docs/prds/agent-platform/plan/lane-b1b-operator-and-boot-rewrite.md -- seon.cluster.boot-test
```

Run `88ca15dd3638`: 10 executed, 0 unchanged, 122 assertions, 0 failures,
0 errors. Program digest
`e086084f4cb70298c0dce669a410428b9076b5314ccb4e7c78a6b0c3f8f6216a`;
input digest
`faa527c873a0380edc2acc75b5b6cb370ffac0f212f5722390da4b0011dbf4fc`.

The two added drills were `sole-stop-exits-and-restarts` and
`down-reply-is-readable-edn`. The first awaited the captured handle's actual
exit under `event-ms`, started a different PID and evaluated `(+ 1 1)`. The
second read the CLI reply, compared its exact identity map and verified
`(= reply (clojure.edn/read-string (pr-str reply)))` before observing exit.

## Boundary and inventory

The shared tree could not cold-load during the first manual probe because the
foreign in-flight `resources/seon/schemas/seon.test.selection.edn` placed
`:seon.test.input/difference` in the wrong owner file. A detached HEAD worktree
with linked `reference-code` and only this diff restored both load checks and
ran the live scratch probes. Its first `--paths` attempt had no local published
base; the canonical main-root `--paths` run above supplied the recorded proof
while excluding every foreign path.

The owned files contain 29 lines in `resources/seon/operator/prepl.clj`, 419 in
`script/seon/operator.clj`, 464 in `src/seon/cluster/boot.clj`, and 471 in the
boot test. No platform or cold gate ran; those remain orchestrator-owned. The
Seon MCP `runtime_status` and `eval_clj` tools were unavailable in this session,
so no MCP envelope or live adoption claim is made. `default` was never stopped,
reset or restarted.
