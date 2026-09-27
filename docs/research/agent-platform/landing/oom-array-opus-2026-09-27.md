# oom-array-opus: default's `Requested array size exceeds VM limit` (2026-09-26 22:12Z)

## Still broken (read this first)

- `seon.render.web/handler` still lets a throw reach http-kit, and http-kit's
  logger still prints the whole `ex-data` with no bound. The worst case is fixed:
  errors no longer hold places. The class is filed as task `80f54dd48aef`.
  `web.clj` is held by shrink-web-opus, so the `:seon/tasks` marks for this task
  are a patch: `tmp/oom-array-opus/web-task-marks.patch`.
- `page-response` passes a refused database (released connection) into
  `transcript/render-agent-header`. The web-test server keeps serving after its
  cluster's connection is released. Filed as task `0be9b4c92c56`, with its mark
  in the same patch.
- Tests build `is` messages with `(pr-str world)`. clojure.test evaluates the
  message even when the assertion passes. host-test and accretion-test each OOM
  after about 20 s in their own runs. Filed as task `c96ce4d07661`, with the
  production pr-str sites. The test files belong to other lanes, so this lane
  did not sweep them.
- Installed proof is still pending. Default runs a committed archive, and this
  lane did not adopt onto default. Root has to adopt, then run
  `bin/test-check default --ns seon.error.refusal-test`.

## Follow-ups (same lane)

- **556cd021d.** The refusal-test helper `printed-bytes` called
  `(pr value writer)`, which prints both arguments to `*out*`. It always
  answered 0 and printed whole worlds into the harness's captured output: the
  OOM red in `every-producer-references-the-worlds-it-was-handed`, from
  09fe1d064. The helper now binds the writer as `*out*`. Proven on default
  pid 34618 (fa4e4bccf): `bin/test-check default --ns seon.error.refusal-test`
  gives 83 pass / 0 fail / 0 error in 3,772 ms (`tmp/oom-array-opus/refusal-test-2.out`).
- **ffa4e6982, `seon.error/commit-call`.** The agent a fault happened to is
  never its own notification's recipient: the recipients are the steward or the
  escalation owner, minus that agent. The guard `(not= escalate-to agent-id)`
  had been lost in 9c9b50e3c. A message is a wake, and self-mail is the
  2026-08-08 self-feeding loop (nine paid calls). turn-loop-test
  `a-refused-phase-escalates…` now expects the D13 delivery: once per opening,
  on the first occurrence. 9c9b50e3c ruled this on 2026-09-19 and retired the
  recurrence limit from delivery (`docs/prds/steward-platform/research/error-family-1a-2026-09-19.md`).
  The test's old wait-for-the-third-refusal expectation was stale. **RESET
  NEEDED**: default pid 34618 predates this commit. Next, run
  `bin/test-check default --ns seon.turn-loop-test` once.
- The `:seon.config.error/recurrence-limit` dial is still required, but no
  recorder reads it for delivery. It should be retired or given a reader; not
  done in this cut.

### Red triage 2: instrument-test (b55d69c7c; RESET NEEDED, unproven on a JVM)

- `an-invalid-refusal-retains-its-evidence-at-the-kernel-boundary` (producer:
  `seon.sci.kernel/failure-value`). Instrument's minimal refusal records its
  rejection in its own `:seon.error/chain`, never as a Throwable cause. The
  kernel replaced that chain with the throwable's links and stripped the
  carrier link, so the rejection's message and `:seon.instrument/problems`
  data were lost. A refusal whose own chain holds links the throwable does not
  carry now keeps that chain after its wrappers; relation indices are shifted.
  Any other refusal keeps the old shape.
- Stale subjects, updated to their rulings:
  - `an-invalid-core-error-mode…` becomes
    `an-invalid-apply-request-is-refused-at-its-crossing`. 7c40396b3 deleted
    the mode, and the missing-projection registration error keeps its own
    regression.
  - `one-wrapper-per-adoption…`: 39960bb15 makes equal inputs answer the one
    built projection.
  - `semantic-admission…`: 36a3714b4 declares `failure-value` as
    `[:or execution-error error]`.
- `a-refusal-that-cannot-be-built…` `:missing-refusal-schema` (producer:
  instrument.clj, held by defn-scan). A JVM probe on default shows
  `references` under the masked arming projection throwing
  `:malli.core/invalid-schema` from `identity-only-descriptors-in`
  (schema.clj:3883). That bare Malli error escapes without members. The patch
  `tmp/oom-array-opus/instrument-minimal-refusal.patch` stops referencing
  under the suspect projection, because `diagnostic` already references the
  observation under the handed one. Trade-off: with no handed projection, a
  world in the offending arguments is not referenced (places still are).
- Not fixed: `refusal-value-projection…` lines 716 (the HTML face lacks
  members 103 onward) and 713 (a newline inside the AI cut). Both are render
  owners' problems (triage suspects 09fe1d064 and 132d94015/33f700f37).

## Evidence

- Heap dump: `data/heap/java_pid12212.hprof`, 6,964,021,613 bytes (6.5 GiB on
  disk). It was parsed with Eclipse MAT CLI through a symlink in
  `tmp/oom-array-opus/`, where the MAT indexes also live (`q.sh` runs OQL). The
  thread stacks are in `tmp/oom-array-opus/oom.threads`, and the OOM thread's
  stack is in `oomstack.txt`.
- OOM thread: virtual thread `0x736c12b798`, http-kit worker. Stack from the bottom up:
  `HttpHandler.runSync (RingHandler.java:153)` → `completeError` → `handleError`
  → `ContextLogger.log` → `HttpUtils.printError` → `Throwable.printStackTrace`
  → `ExceptionInfo.toString` → `APersistentMap.toString` → `RT.printString` →
  about 1,000 `pr-on`/`print-map` frames → `StringWriter.append` → `Arrays.copyOf`
  of `byte[2147483644]` (UTF-16 buffer). The buffer retained 2,147,483,664 B,
  59% of the heap.
- Throwable `0x736c4a0130`: `ExceptionInfo` "seon.render.transcript/render-agent-header
  refused request at [:seon.db/db]: expected must be an immutable Datahike
  database value, got a map … Called from seon.render.web (web.clj:3166)".
- The printed buffer was recovered from the dump as UTF-16LE at file offset
  559,564,080. Its content:
  `{:seon.error/expected …, :seon.instrument/check :input, … :seon.error/offending [{…web service request…}]}`.
  - `:seon.db/db` holds the db read refusal "Connection has been released."
    (`seon.db/db` ← `page-response` web.clj:3168 ← `root-alias-response`),
    at 22:12:19.730Z for cluster "web-test".
  - The worlds had already been referenced: `:seon.sci.eval/ctx`, connections
    and projections print as identity maps.
  - `:seon.turn.loop/cluster` → `:seon.agent/routing #object[clojure.lang.Atom …
    {:status :ready :val {… :seon.agent/contexts {…}}}]`. Clojure prints an atom
    by printing its current state. The roster's state holds each context's full
    `:seon.schema/projection`: the `compiled` atom, call-preparation
    `snapshot-memo` atoms and realized delays. The first 60 M characters hold
    42,916 `clojure.core$identity` objects, 47,130 `:seon.fn.argument/index`
    keys and 43,308 `:seon.db/decode` keys. `seon.error.refusal/referenced`
    stopped at the atom because an atom is not a map, and the printer then
    copied everything inside it.
- The same class appears in tests (tests-0627a): `seon.cluster.host-test/one-identity-is-homed-on-one-branch-decided-by-the-writer`
  (host_test.clj:167-168, `(pr-str outcomes)` of hosted agents holding the same
  roster) and `seon.test.accretion-test/a-gate-test-runs-with-its-authors-cluster-custody`
  (accretion_test.clj:251-258, `(pr-str result)` holding an SCI context). Both
  hit `OutOfMemoryError: Required array length 2147483638 + n` in a
  StringWriter after 20 s and 22 s.

## Changed

- `src/seon/error/refusal.clj`:
  - `referenced` now turns a place into the symbol `#object[Class 0xhash]`.
    A place is anything deref-able except a Var: an atom, delay, promise or
    future. Value admission already treats reference types as "named, never
    entered" (admit.clj:369-372). The floor printer's `datum` already names host
    objects the same way (render/value.clj:66). The walk now goes world check,
    then place, then record, then map/vector/set.
  - `operation-projection` returns nil when `seon.schema` is not loaded. Only
    `seon.schema` hands a projection, so nil is the correct answer there.
- `test/seon/error/refusal_test.clj`: new regression
  `a-place-in-an-error-is-named-never-printed-through` (proves `480378b94c9b`).
  A roster atom and a realized delay, each holding 100k items, print past
  16 KB. The error that holds them prints in kilobytes. A Var stays a Var.
- Tasks, all without `:id` (handles computed): `seon.render.web.edn` +2
  (`80f54dd48aef`, `0be9b4c92c56`) and `seon.render.value.edn` +1
  (`c96ce4d07661`).

## Probe numbers (plain JVM with only `src` on the classpath, clojure 1.12)

Error holding an atom of `(vec (range 1000000))` in `:seon.error/offending` and
in the cause link's ex-data:

| | printed chars |
|---|---|
| after the fix, whole error | 540 |
| the roster alone | 6,888,959 |

The babashka path, `diagnostic` with a throwable:

| | result |
|---|---|
| HEAD | `Could not locate malli/core…` (schema.clj:21), the `bin/seon status` crash |
| fix | the error keys come back, and the atom is `#object[clojure.lang.Atom 0x…]` |

## Other unbounded print sites (from `rg -n "pr-str|prn-str|with-out-str" src script`, failure and hot paths)

- `render/web.clj:3584` `create-owner-response` and `:3613`
  `context-action-response`: the 422 body is `(pr-str owner)` / `(pr-str result)`.
- `render/web.clj:3294`: `(pr-str (dissoc composed :seon.db/db))` in the
  graph debug page.
- `db.clj:1834` `value-type-error`: `(pr-str value)` of the transacted value.
  `db.clj:2619` `pull-call` prints its arguments. `db.clj:3571` prints its data.
- `problems.clj:542, 613`: `(pr-str (:seon.error/offending entry))` in prose.
- `cluster.clj:345` `mcp-plain`: `*print-length*` 65,536 × `*print-level*` 64,
  which in practice sets no bound for an atom's state.
- `error.clj:1348` `message-tx`: `(pr-str (value fact))` as message content.
- `fs.clj:210`: logs the deletion report. `cluster/export.clj:105`: prints its data.
- `test/accretion.clj:418-423`: failure arguments and actual value.
- `script/seon/dev/mcp.clj:85`: `(pr-str (Throwable->map throwable))`.
  `:288`: `(pr-str event)`.
- About 1,320 `pr-str` uses in `test/`. Every `(is … (pr-str x))` builds its
  message eagerly.

## The dump

`data/heap/java_pid12212.hprof` (6.5 GiB) can go: every finding above is
recorded, and the OOM thread's stack is kept in
`tmp/oom-array-opus/oomstack.txt`. The MAT indexes in `tmp/oom-array-opus/`
(2.1 GiB) can go with it. This lane deleted nothing. Also in `data/heap/`:
`java_pid19859.hprof` (17 GiB, the 10:10 OOM, already analysed in
`analysis/`, 4.8 GiB). The owner decides whether it goes.
