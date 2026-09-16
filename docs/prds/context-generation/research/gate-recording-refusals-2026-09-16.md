# Gate result recording refuses while the same cluster answers in milliseconds

Date: 2026-09-16. Lane: read-only research (no source edits, no test JVMs, no
cluster operations). Subject: three consecutive `bin/test` gates on the
`default` development cluster printing `persistent results NOT recorded`
(batch 25 `:seon.fresh-operator/live-prepl-unavailable`, pid 7595; batch 26A
`:seon.fresh-operator/prepl-response-silent` 30000 ms, pid 53378; batch 26B
`:seon.fresh-operator/live-prepl-unavailable`, pid 53378), while the
orchestrator's MCP `eval_clj` against pid 53378 answered in 2-800 ms.

## What the recorder actually does

- `bin/test:261-263` sets `persistent_results_root=$source_root` (the real
  checkout `/Users/sean/src/seon`) whenever no explicit result cluster is
  given, and passes it as `-Dseon.test.persistent-results-root`
  (`bin/test:778-781`). The coordinator's `-Dseon.operator.root` is the
  disposable run root, but recording resolves against the checkout.
- `src/seon/test/runner.clj:2984-2996` selects `record-persistent-results!`,
  which calls `seon.fresh-operator/live-root-value!`
  (`src/seon/test/runner.clj:1789-1804`) with the checkout root and the
  `commit-persistent-results!` form.
- `script/seon/fresh_operator.clj:1367-1390`: `live-root-value!` first builds
  `cluster-truth` for the root, then `select-anchor`
  (`script/seon/fresh_operator.clj:1356-1365`) demands a row that is
  `operator-root?` AND `registered?` AND `process-alive?` AND `reachable?`
  AND carries a `transport-advertisement`. Without an anchor, and with any
  live row present, it refuses `:seon.fresh-operator/live-prepl-unavailable`
  and never sends the form. The store fallback below it is unreachable while
  the dev JVM holds the `flock`.
- `reachable?` is not a property of the socket. It is the verdict of a
  separate census probe: `observe-jvm`
  (`script/seon/fresh_operator.clj:969-986`) evaluates `jvm-snapshot-form`
  (`script/seon/fresh_operator.clj:580-653`) through `prepl-value!`
  (`script/seon/fresh_operator.clj:944-949`), which is
  `(edn/read-string (terminal-value (prepl-eval! ...)))`. Any throw is caught
  and becomes `reachable? false`. The deadline is
  `:seon.config.operator/event-silence-backstop-ms` (30000 ms in
  `config/default.edn`), applied both to connect and to each read
  (`script/seon/fresh_operator.clj:1529-1600`), independent of the runner's
  own 300 s silence backstop.

## Probe and numbers (live, read-only, 2026-09-16)

Load-only JVM in the checkout, `cluster-truth "." {read-offline-roster? false}`:

```
cluster-truth ms 268   rows 1
#:seon.fresh-operator{:name default, :root /Users/sean/src/seon,
                      :operator-root? true, :registered? false,
                      :process-alive? true, :reachable? false,
                      :persisted? false, :inconsistencies []}
  transport? true
ANCHOR false
```

The refusal reproduces in 268 ms — nothing waits, nothing is slow. The JVM
observation carries the reason:

```
JVM #:seon.fresh-operator{:root /Users/sean/src/seon, :reachable? false,
     :error "Invalid token: :seon.test-support.fixture/0"}
```

Sending the exact `jvm-snapshot-form` to the advertised prepl (port 61867,
pid 53378) returns a complete, correct reply in **13 ms** (the cluster's own
`:ms 3`); unrelated forms answer in **1 ms**. The reply contains:

```
:branch-connections #{:db :seon.test-support.fixture/0 :cluster-default}
```

`:seon.test-support.fixture/0` is minted at `test/seon/test_support.clj:194`
as `(keyword "seon.test-support.fixture" (str next-id))`. `pr-str` emits it,
but `clojure.edn` refuses to read a keyword whose name begins with a digit —
the value does not round-trip. Since the steward-platform in-process test
runs began executing fixtures inside the development JVM, that connection is
live in `datahike.connections/*connections*` in pid 53378, so every census
probe of the dev cluster throws while parsing a healthy reply. The registry
roster is clean (`#{:db :cluster-beta :current-src :cluster-default
:test-results}`); only the in-memory fixture connection carries the token.

## Verdict — two causes, one seam

1. **Primary (batch 25, 26B, reproduced at 268 ms).** The recording refusal
   is a **reply-parsing defect in the census pre-read**, not a prepl outage.
   `prepl-value!` (`script/seon/fresh_operator.clj:948`) cannot read a
   keyword named `0`, so `observe-jvm` marks the only live row
   `reachable? false`, `select-anchor` finds nothing, and `live-root-value!`
   refuses (`script/seon/fresh_operator.clj:1375-1387`) without ever
   attempting the recording form. Root of the unreadable value:
   `test/seon/test_support.clj:194`.
2. **Secondary (batch 26A, 30000 ms).** The same pre-read, timed out rather
   than mis-parsed: `prepl-eval!`'s read loop hit the operator silence
   backstop (`script/seon/fresh_operator.clj:1589-1600`) during a 212-namespace
   platform gate with three worker JVMs. The recording form itself was again
   never sent. Both failures are the census probe, not the recording.

This is the owner law in `CLAUDE.md` §1: no seam may act on a pre-read its
authority will re-decide. `select-anchor`'s `reachable?` is a pre-read of the
very socket the recording send would exercise, and it is strictly weaker than
the send: it fails on payload shape and on unrelated load, and it reports
absence of signal as ill health — the project's named recurring failure class.

## Plan (not implemented)

**Minimal fix — dissolve the pre-read.** `record-persistent-results!` should
read the advertisement for the root (`operator.state/read-advertisement`, used
at `script/seon/fresh_operator.clj:527-529`) and send the recording form
through `prepl-eval!` directly, letting the send be the authority: a
successful `:ret` is the proof of liveness, a refusal or silence from that
send is the diagnostic. `live-root-value!` keeps `cluster-truth` only to pick
which advertisement, never to decide reachability, and returns
`{:live-process? false}` only when no advertisement exists at all. This
deletes a mechanism (the snapshot probe on the recording path) rather than
hardening it, and makes the recorder behave exactly like the MCP bridge that
answers in milliseconds.

**Second, independent fix.** `test/seon/test_support.clj:194` must mint a
readable keyword (e.g. `(keyword "seon.test-support.fixture" (str "b" n))`).
An identifier that `pr-str` emits and `clojure.edn` refuses is a latent defect
in every diagnostic that transports it, not only in this probe.

**Regression that proves the class dead.** One test asserting that a gate
completion is recorded against a live root whose JVM holds a connection whose
branch keyword does not round-trip through `clojure.edn` — the recording
succeeds, and the census probe's inability to parse an unrelated payload is
never the recorder's verdict. A second assertion in the same test: with no
advertisement at all, the recorder returns the typed
`{:seon.fresh-operator/live-process? false}` and takes the held-store path.

**Namespaces to gate.** `seon.test-runner-test`, `seon.operator-test`, plus
`bin/test --platform`. Owned paths for the fix:
`script/seon/fresh_operator.clj`, `src/seon/test/runner.clj`,
`test/seon/test_support.clj`, `test/seon/test_runner_test.clj`.

## Verification boundary

Everything above was verified by read-only evaluation against the live
`default` cluster (pid 53378) and by reading the named source. No test JVM was
run, no cluster was stopped or reforked, and no source file was edited. The
30000 ms silence of batch 26A was not reproduced live; it is attributed to the
same probe from its logged error kind, phase, and backstop attribute
(`tmp/orchestrator/gate-results/batch-26/platform.log:804-805`).

## Landing note — 2026-09-16, lane `recording-pre-read`

The plan above is implemented. REPL-driven throughout: every form was evaluated
against the live `default` cluster (pid 53378) or an isolated load-only probe
JVM before the file changed.

### Files touched (bytes after the change)

| File | Bytes | Change |
|---|---|---|
| `script/seon/fresh_operator.clj` | 129271 | +44 / −23 lines |
| `test/seon/dev/fresh_operator_test.clj` | 90819 | +127 lines |
| `test/seon/test_support.clj` | 30948 | +6 / −2 lines |

1. **The pre-read is dissolved.** `live-root-value!` no longer calls
   `select-anchor`. It asks `cluster-truth` for the root with
   `{:read-offline-roster? false :probe-jvms? false}` — so no JVM snapshot probe
   is taken at all — and sends the form to the transport advertisement of the
   first operator-root row whose process is alive. The send is the authority:
   its own refusal or silence is the diagnostic. A root with no live advertised
   transport still answers `{:seon.fresh-operator/live-process? false}`, which is
   the caller's held-store path. The
   `:seon.fresh-operator/live-prepl-unavailable` refusal and its `live-rows`
   derivation are deleted; the kind had no other reference in `src`, `test`,
   `script`, `resources`, or `bin`.
2. **Every prepl reply parse is total.** New private `read-prepl-reply`
   (`script/seon/fresh_operator.clj:944`) refuses with
   `:seon.fresh-operator/prepl-reply-unreadable`, carrying the advertisement and
   the offending reply verbatim, instead of throwing a bare reader exception that
   an outer `catch` downgrades to `reachable? false`. It now backs `prepl-value!`
   and the four other prepl-reply seams: start (`start-result`), export, `init`,
   and `stop`. The remaining `edn/read-string` calls in the namespace read config
   manifests or prefixed stdout lines from launched child processes, not prepl
   replies.
3. **The unreadable identifier is gone at its source.**
   `test/seon/test_support.clj:194` now mints
   `:seon.test-support.fixture/fixture-N`. `test/seon/schema/datahike_test.clj:327`
   still constructs `:seon.test-support.fixture/0` by hand on purpose — it is the
   regression for reader-inexpressible identifiers surviving the Datahike EDN
   attribute round-trip, and is unaffected.

### Live numbers (isolated probe JVM, `-M:probe` with `script` on the path)

Against a disposable root whose advertised socket answers a healthy census
snapshot containing `:seon.test-support.fixture/0`:

```
:OLD-census-verdict [#:seon.fresh-operator{:reachable? false}] :OLD-anchor false
:NEW-live-root-value-ms 120 :value #:seon.fresh-operator{:live-process? true,
                                                         :value {:recorded? true}}
```

The old census verdict still reproduces on the unchanged `cluster-truth`
default; the recording path no longer consults it.

Against the live `default` cluster, three consecutive sends of the recorder's
own `live-root-value!` path:

```
:DEFAULT-live-root-value-ms 110 :value #:seon.fresh-operator{:live-process? true, :value #:seon.dev.probe{:pid 53378}}
:DEFAULT-live-root-value-ms 112 :value ...
:DEFAULT-live-root-value-ms 113 :value ...
```

110-113 ms, matching the MCP bridge against the same JVM. The prior refusal
reproduced in 268 ms and never sent the form.

### In-process test runs (live `default` JVM, no test JVM)

Namespace reloaded through `seon.test`'s own loader first
(`(with-test-loader #(require 'seon.dev.fresh-operator-test :reload))`), then:

```clojure
(seon.test/run (#'seon.test/resolve-test 'seon.dev.fresh-operator-test/<test>)
               (seon.operator/connection "default"))
```

| Test | pass | fail | error |
|---|---|---|---|
| `live-root-value-sends-through-the-transport-without-a-census-pre-read` | 5 | 0 | 0 |
| `unreadable-prepl-replies-are-typed-diagnostics-not-silence` | 2 | 0 | 0 |
| `fixture-branch-keywords-round-trip-through-clojure-edn` | 1 | 0 | 0 |

3481 ms for all three. The first test stands up a real loopback prepl whose
census snapshot reply carries `:seon.test-support.fixture/0` and whose ordinary
reply is readable; it asserts the recording value comes back, that no form
containing `datahike.connections` was ever sent, and that the last form sent is
the recording form. Its second `testing` block asserts the no-advertisement root
still answers `{:live-process? false}`. On the pre-fix code the same setup
produced `:OLD-anchor false`, i.e. the `live-prepl-unavailable` refusal.

### Verification boundary

No test JVM was launched; `bin/test` and `bin/test-fast` were not run. No cluster
was started, stopped, reforked, or reset. Files outside the three owned paths
were not modified. clj-kondo over the three files: 0 errors (70 pre-existing
warnings of the namespace's existing `Shadowed var` / `reset!` classes). The
orchestrator's batched gate is the proof; the request is
`tmp/orchestrator/gate-requests/gate-recording.txt`.

## Second landing — 2026-09-16, lane `gate-recording-latency`

The pre-read is gone and the recorder still reported
`:seon.fresh-operator/prepl-response-silent` on batch 27
(`tmp/orchestrator/gate-results/batch-27/platform.log`, retained root
`tmp/test-runs/run.4hIzdV`). This section names the remaining cause. It is NOT
in the recorder, and it is NOT in this lane's owned paths; no source file was
edited.

### The send is honest — the remote really is blocked

Timing each step of the recording form's own work against the live `default`
JVM (pid 53378), idle machine, MCP `eval_clj` in `jvm` mode, with a realistic
86-result completion (`pr-str` payload **24,509 bytes**, reply **26,955 bytes**
— neither is large):

| Step (`src/seon/cluster/source.clj:300-323`) | ms |
|---|---|
| `seon.cluster.source/current` (read the head) | 0 |
| `registry/branch!` — fork the scratch | **1,437** … **63,224** |
| `store/open-branch!` | 31 |
| `schema/projection-from-database` | 1,372 |
| `seon.test.runner/commit-results!` (86 rows, one transaction) | 699 |
| `registry/retire-branch!` — retire the scratch | **74,326** |

Everything except the two roster operations is sub-second to ~1.4 s. The roster
operations are bimodal: the FIRST one in each evaluation waited 57-74 s, and
every later one in the same evaluation took 13-29 ms.

```
[{:branch-ms 63224 :delete-ms 29} {:branch-ms 20 :delete-ms 13} {:branch-ms 28 :delete-ms 15}]
```

### Cause — the store's reachability gate is closed 96% of the time

`registry/branch!` (`src/seon/cluster/registry.clj:196-206`) calls
`datahike.api/branch!`, which acquires a `:roster` reachability permit
(`reference-code/datahike/src/datahike/versioning.cljc:227-231`). That
acquisition blocks **without a bound** while a sweep is queued or active
(`reference-code/datahike/src/datahike/gc_guard.cljc:190-206`: `async/<!!` on
the ready channel).

Sampling the gate every 500 ms for 70 s against the live store
(`224560ae-b0ba-3d5c-b6db-6d7415ad66e7`), using the non-queuing
`try-reachability-permit!`:

```
{:n 139 :sweep-in-progress 134 :admitted 5}   ; 96.4% of wall time closed
```

The only caller of `acquire-sweep-permit!` in the tree is
`seon.blob.retention/reclaim!` (`src/seon/blob/retention.clj:38`), and its
schedule is **`"* * * * *"` — once per minute** (`src/seon/schedule.clj:71-75`).
Its first act under that exclusive permit is `inventory`
(`src/seon/blob/retention.clj:29`), a full `(k/keys store {:sync? true})` walk.
Measured on this store:

```
{:konserve-keys 380285 :binary-keys 3624 :k-keys-ms 64580}
```

**64.6 s to enumerate 380,285 konserve keys in order to find 3,624 blobs**, on a
store that is now **72 GB / 379,772 files** under `data/store`. One run does not
finish before the next minute fires; the schedule fire facts show the delivery
already slipping (`nominal 04:50:00 → observed 04:50:44`, `04:51:00 → 04:51:59`).
So the gate is effectively always closed, every roster operation queues behind
it, and the recorder's single send sits for ~60-135 s while the operator's
30,000 ms socket read timeout (`script/seon/fresh_operator.clj:1548,1605-1615`,
`:seon.config.operator/event-silence-backstop-ms`) fires first and reports
`prepl-response-silent`.

The 30 s diagnostic is therefore accurate, not a false negative: the remote
genuinely had not answered. Raising the backstop would hide the defect.

### Verdict

Two defects, neither in the recorder and neither in this lane's owned paths:

1. **`seon.blob.retention/reclaim!` scans the whole store under an exclusive
   sweep permit, once a minute.** A blob inventory is derived by walking 380k
   datahike index keys to select 3.6k binary keys — the work is ~100× the
   question. Under AGENTS.md §2.3 the schedule also has no bound: a run that
   cannot finish inside its own period is never reported, it simply starves
   every other roster writer. `src/seon/blob/retention.clj:11-29`,
   `src/seon/schedule.clj:71-75`.
2. **The roster permit wait is unbounded** (`gc_guard.cljc:190-206`,
   `versioning.cljc:227-231`). Even with retention fixed, any sweep can stall a
   recording, a cluster start, a refork, or a publication forever with no event
   naming what is being waited for. `seon.cluster/start!` already knows the
   typed `:sweep-in-progress` refusal (`src/seon/cluster.clj:3081`); the roster
   path has no equivalent.

A third, separate observation: the shared root's store has reached 72 GB /
379,772 files. Under AGENTS.md §6 that order-of-magnitude growth is itself the
investigation signal.

### Why no fix landed here

The owned paths for this lane were `src/seon/test/runner.clj`,
`script/seon/fresh_operator.clj`, `bin/test` and three test namespaces. Every
root cause above lives in `src/seon/blob/retention.clj`,
`src/seon/schedule.clj`, or the Datahike fork. Hardening the recorder against
another mechanism's abnormal operation is exactly the shape AGENTS.md tells us
to stop at, and adding a permit pre-read to the recorder would reintroduce the
pre-read that the first landing deleted. Issue filed:
`docs/seon/issues/blob-retention-sweep-starves-every-roster-writer.md`.

### Verification boundary

All numbers are live read-only evaluations against the running `default`
cluster (pid 53378) through MCP `eval_clj`, on an otherwise idle machine. No
test JVM was launched; `bin/test` and `bin/test-fast` were not run. No cluster
was started, stopped, reforked, or reset. Probe scratch branches were retired;
the roster is back to `#{:cluster-beta :cluster-default :current-src :db
:test-results}` and `current-src`'s head was never advanced (the probe
`commit-results!` wrote only into a scratch branch that was then retired). No
source file was edited.

### The batch-27 B red is not this commit's

`seon.dev.fresh-operator-test/init-owns-current-source-and-dormant-cluster-lifecycle`
missed `:task-complete` within the 270 s worker bound
(`tmp/orchestrator/gate-results/batch-27/named.md`, worker pool-2 pid 64984,
retained root `tmp/test-runs/run.o0NcHO`). The hypothesis under test was that
7c7395c8a's `read-prepl-reply` can wait forever behind the start/export/init/stop
seams. It cannot:

- `read-prepl-reply` (`script/seon/fresh_operator.clj:944-958`) performs **no
  I/O**. It is `edn/read-string` over a `String` that `terminal-value` took from
  an already-completed `prepl-eval!`; its only new behavior is turning a bare
  reader throw into a typed `:seon.fresh-operator/prepl-reply-unreadable`.
- Every socket read still happens inside `prepl-eval!`
  (`script/seon/fresh_operator.clj:1545-1615`) under
  `(.setSoTimeout socket timeout-ms)`, whose `SocketTimeoutException` branch
  raises the typed `prepl-response-silent`. The commit added no read loop, and
  the pre-existing loop's `::eof` branch fails immediately rather than reading
  on. `git show 7c7395c8a -- script/seon/fresh_operator.clj` touches no read.
- The new `:seon.fresh-operator/probe-jvms?` option is honored by `cluster-truth`
  (`script/seon/fresh_operator.clj:1012-1013,1271-1277`), so it is not a silently
  ignored flag either.

Positive evidence for the real cause: the worker emitted `END` at 04:50:58 —
exactly 270.05 s after its 04:46:28 `BEGIN` — with a clean stderr (projection
acquired, 1038 contracts armed, no exception). That is a slow run, not a wedge.
The test's own recorded cost is **110.837 s** (it is one of the declared-long
pool tests the platform tier skips; batch 27 B ran it because explicit
namespaces run complete). It ran 04:46-04:51, inside the same window in which
the shared `default` JVM performs its minute-by-minute 64.6 s walk of 380,285
konserve keys over a 72 GB / 379,772-file store on the same disk, with two other
worker JVMs alongside. A 2.4× slowdown of a disk-bound 110 s test under that
load needs no further explanation, and the first defect above is its cause too.
