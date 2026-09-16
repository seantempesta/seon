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
