---
type: research
status: complete
tags: [research, repl, sci, diagnostics]
---

# REPL dogfood edges — 2026-08-04

## Verdict

The real SCI door safely terminated or admitted every stressed value, and the
good faces are genuinely good: Unicode survives, nil is ordinary, a 5,000-item
blob pages correctly, repeated references do not recurse, and a timed-out spin
shows `:seon.eval/fn-entries` prominently. The pass nevertheless found four
new agent-face defects, independently confirmed D6 and D7, reproduced four
existing live defects, and found one operator-status contradiction while
stopping to inspect the isolated cluster.

No production source was edited. I read the named authorities end to end
before probing: [the transfer prompt](../../../TRANSFER_PROMPT.md),
[the localized runbook](../AGENTS.md), [the active plan](../plan/README.md),
[its current working edge](../plan/unsettled.md),
[the complete curation ledger](../plan/curation-findings-ledger-2026-08-04.md),
[the agent-runtime architecture](../../../seon/architecture/agent-runtime.md),
and the [issue convention](../../../seon/issues/README.md) and
[schedule](../../../seon/issues/index.md).

## Dependency ledger and method

- Isolated operator root: `tmp/repl-dogfood-edgefaces-0804`; cluster:
  `edgefaces0804`; PID `11892`; prepl `56068`; web port `7914`.
- Published source branch commit ID at boot:
  `6a7262a6-e93a-5675-827f-990a7e356b6c`.
- Vendored SCI pin: `2db3358cba913b6fbbe49c7b5b34d7ac72715924` under
  `reference-code/sci/`.
- Vendored Clojure pin: `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d` under
  `reference-code/clojure/`.
- Owning first-party seams: `seon.sci.eval/evaluate`,
  `seon.sci.kernel/failure-value`, `seon.sci.admit/admit`,
  `seon.cluster/mcp-project`, `seon.instrument/violation`, and
  `seon.error/instrumentation-prose`.
- The probes used MCP `eval_clj` in `door` mode, which enters the shared cluster
  SCI context through `seon.sci.eval/evaluate`. It is the real guarded agent
  evaluation path, but MCP does not create a run or receipt; facts that belong
  specifically to terminal settlement were not under test.
- Each large envelope was summarized inside the cluster rather than copied
  wholesale. Blob re-reads used the real MCP value drill by digest, path, and
  offset.
- A session-only function cannot become a declared render producer through
  the MCP door because that surface intentionally performs no terminal program
  publication. The requested "throws while rendering its own result" edge was
  therefore exercised at the shared realization seam: a function returned a
  lazy value whose projection threw while admission rendered the result. No
  database row or production renderer was altered to manufacture the case.

## Ranked findings

### 1. High — top-level string bypasses the MCP value window

Reproduction:

```clojure
(apply str (repeat 1048576 "x"))
```

The result was admitted and blob-backed, but the inline text still contained
262,147 characters:

```clojure
{:edge/original-count 1048576
 :edge/result-edn-count 262220
 :edge/text-count 262147
 :edge/artifact-size 262265
 :edge/capped? true
 :edge/windowed? true}
```

Digest:
`cfbbec8053dd361e864119a55d5c887b55261f4728a70153fe510415158ad261`.
The face says it is windowed while returning the whole admitted string window
inline. Root cause: `seon.cluster/mcp-project` derives `projected-node` and then
passes the unprojected evaluation node to `evaluation-face`. Tracked in
[Bound top-level string results before returning the MCP face](../../../seon/issues/mcp-door-top-level-string-bypasses-value-window.md).

### 2. High — D7 loses the original collection size and continuation

Reproduction:

```clojure
(vec (range 100000))
```

The inline face showed 32 values and an ellipsis. The blob digest was
`9afb8e7075bb4c3c04e36c8c2e60d847153583069a9e12d8309017417ac51e24`.
Drilling at offset 8,184 reported total `8193`; offset 8,192 returned only:

```clojure
["seon.sci.admit/elided"]
```

The apparent total is 8,192 admitted values plus one marker, not the original
100,000. The remaining 91,808 values cannot be paged from that digest. A map
nested 80 levels deep exhibited the same grammar failure: its text ended in a
naked `#`, and drilling through 64 `:edge/next` steps reached only
`"seon.sci.admit/elided"` with no reason, count, or handle. This independently
confirms ledger item D7 and the existing issue
[Give the elision marker its count and identity](../../../seon/issues/elided-marker-carries-no-count-or-identity.md).

### 3. High — fresh `runtime_status` violates its own problems contract

Immediately after the isolated cluster reached ready, `runtime_status`
returned its selected cluster but placed this error in the runtime field:

```clojure
{:seon.error/kind "seon.instrument/contract-violated"
 :seon.error/message
 "seon.problems/problems violated its contract (invalid-output): #:seon.problems{:error-signatures [nil #:seon.error{:fact ...}]}"
 :seon.dev.mcp/exception-class "clojure.lang.ExceptionInfo"}
```

The recently repaired bounded status face therefore regressed on a clean,
isolated boot. Tracked by reopening
[Repair development MCP error locations and status scope](../../../seon/issues/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md).

### 4. Medium — D6 stores a print-face tree inside an error string

Reproduction:

```clojure
(my.fs/read 42)
```

The readable headline was followed by error data containing:

```clojure
{:seon.instrument/problems
 "#:seon.print{:face :seon.print/vector, :items [#:seon.print{...}]}"
 :seon.instrument/args "[42]"}
```

The runtime already possesses bounded semantic values, but serializes both
before putting them in the error. This confirms D6. Tracked in
[Keep contract-violation evidence as data](../../../seon/issues/contract-violation-serializes-print-tree-inside-error-data.md).

### 5. Medium — time-limit face mixes excellent diagnostics with SCI internals

Reproduction:

```clojure
(loop [n 0] (recur (inc n)))
```

At 30,004 ms the face correctly reported outcome `:time` and
`:seon.eval/fn-entries 613144508`, with zero host interop calls. That is the
actionable diagnostic promised by the vocabulary: this was interpreted spin,
not a blocked host call. The same face also exposed:

```clojure
{:seon.sci.eval/throwable "clojure.lang.ExceptionInfo"
 :seon.sci.eval/data
 #:sci.impl{:interrupt #object[java.lang.Object ...]}}
```

The interpreter-private marker is an ugly, un-actionable host face. Tracked in
[Keep interpreter-private markers out of the time-limit face](../../../seon/issues/time-limit-face-exposes-interpreter-interrupt-marker.md).

### 6. Medium — a nested error hides the throw-site message

Admission deliberately realizes a lazy return inside the armed boundary. A
lazy result whose realization threw `result renderer exploded 🧨` while
carrying `{:seon.error/kind :edge/inner :seon.error/message "inner failure"}`
returned only:

```clojure
{:seon.error/kind :edge/inner
 :seon.error/message "inner failure"
 :seon.error/data
 {:seon.sci.eval/throwable "clojure.lang.ExceptionInfo"
  :seon.sci.admit/record {:seon.eval/outcome :error}}}
```

The operation that failed disappeared. Tracked in
[Preserve the throw-site message when an error carries another error](../../../seon/issues/nested-error-data-hides-the-throw-site-message.md).

### 7. Medium — a contracted definition allocates about 578 MB

Defining `unicode-doc-edge` with a complete `:malli/schema` took 135 ms and
recorded 578,302,120 allocated bytes. Defining a second contracted function
took 169 ms and 578,696,192 bytes. An ordinary agent action is therefore much
more expensive than the existing issue's earlier 21–30 ms measurement
suggests. Tracked in
[Stop rebuilding the whole schema projection on every contracted `defn`](../../../seon/issues/contracted-defn-rebuilds-the-whole-schema-projection.md).

### 8. Medium — clean boot emits a search contract core fault

The isolated cluster log emitted this immediately after readiness:

```text
SEON CORE FAULT (dev panic): seon.search/apply-report! violated its contract
(invalid-input): [[{:value ".../derived/lucene", :message "invalid type"}]]
```

`apply-report!` receives the process-local string index ID, while its current
contract at `src/seon/search.clj:216` requires a map. This is the live failure
face of the already-recorded key/shape collision. Tracked in
[Separate declared search metadata from the process index ID](../../../seon/issues/search-index-property-collides-with-process-index-id.md).

### 9. Medium — operator status contradicts a working prepl

After all MCP door probes had succeeded on prepl port 56068,
`bin/seon --root tmp/repl-dogfood-edgefaces-0804 status` printed the cluster as
alive and then said:

```text
roster unreadable: A recorded JVM is alive but its prepl is unreachable; the
offline reader was not allowed to contend for its flock.
```

This independently reproduces
[Stop reporting an MCP-proven live prepl as unreachable](../../../seon/issues/status-reports-a-live-mcp-proven-prepl-unreachable.md).

## Faces that held up

- **Blob tier and re-read:** `(vec (range 5000))` stayed uncapped, became
  blob-backed at digest
  `fa0acb1adf057830391460c42bb9435c4e3732bdda31766f72453829c766bc64`,
  and paged offsets 0 and 32 as eight-item windows with original total 5,000.
- **Unicode and multiline strings:** snowman `☃`, emoji `🧪`, combining
  `é`, and embedded newlines survived value rendering. A multiline Unicode
  docstring rendered faithfully through `doc` and returned ordinary `nil`.
- **Nil:** bare nil rendered as `nil`; nil nested in a vector, namespaced map,
  set, and list retained its position and ordinary REPL syntax.
- **Repeated and circular-ish references:** repeated Vars and symbols remained
  repeated symbolic references. A self-referential atom rendered twice as the
  same bounded opaque object identity and did not dereference or recurse.
- **Self-interruption:** `sci.interrupt/interrupt!` is not resolvable from the
  agent context. The surface offers no callable self-interrupt; the configured
  `time-limit` remains the observable stop mechanism. This was recorded as a
  boundary fact, not a defect.
- **Large finite collection diagnostics:** `(vec (range 100000))` completed in
  9 ms and recorded exactly 100,000 function entries; the cap did not disguise
  a timeout or failure.

## Elegant target

The findings point to one consistent target: carry finite semantic data until
the last producer, and make every cut explain its retained count, original
count, reason, and retrieval route. Error values should contain data rather
than serialized render trees; the MCP envelope should use its one projected
node; time-limit faces should retain the diagnostic record and omit interpreter
objects; nested failures should preserve bounded causal context. None requires
a second renderer, codec, or error mechanism.

## Verification boundary

The isolated cluster stopped cleanly through the prepl path; its empty JVM
then exited under the operator, and root-scoped status reported zero live
clusters and zero orphan JVMs.

The Markdown validator accepted this report, the four new issue notes, the
four updated existing notes, and the curation ledger. `bin/issues-index
--check` remained red only because the shared [issue schedule](../../../seon/issues/index.md)
was already under another dogfood lane's uncommitted ownership. Its exact result
for this lane was four `missing-schedule-row` findings, one for each new note.
I did not edit or commit that shared in-flight file. The required integration
after its owner lands is to schedule these four notes and increase the friction
heading count by four; no source or runtime verification is pending.
