# pass-waste: storability and reach allocation

The schema bridge already retains each attribute's compiled storage facts in Malli's compiled root.
Storability now asks those facts for a boolean; declaration callers alone construct diagnostics.

## Scope and design check

EQUIVALENT for absent/noncompiled declarations, refusal-bearing storage descriptions, native refs,
missing native types, and secondary-only float/double attributes. The existing boolean contract remains
complete. The diagnostic's missing-schema message is now fixed prose; its attribute stays in data.
The one-off registration-form printer is deleted. No new cache or bound is introduced.
Work remains one registry lookup and retained storage lookup per queried member, O(queried members),
with no diagnostic construction or declaration map for the predicate. Malli owns cached storage:
`reference-code/malli/src/malli/core.cljc:353` (`-cached`), pinned `43c6dca8d2`.
The previous producer was `src/seon/schema/datahike.clj:218`: `declared-storable?` called
`attribute-declaration` and discarded everything except `:seon.error/at`. Its cost followed projection
members, not the evaluated function's arguments. The simpler algorithm held against the native
refusal/type/secondary checks; no alternate storage representation was necessary.

The second defect is NOT fixed. `src/seon/test/runner.clj:791-804` keys by connection id and generation.
Removing them is incorrect: Datahike attribute revisions are only comparable inside that generation;
fresh connections start empty. Definition digests alone are also insufficient: the existing
`a-call-edge-changed-under-an-unchanged-definition-digest-changes-the-reach` regression proves that a
retargeted derived edge changes reach without changing a definition digest. The key must cover those
edges, schema forms and identity-to-row mapping, including removals. No changed-index structural diff
API was found in the pinned Datahike `e5453d967b` API/index interface. Re-reading every reach attribute
to hash its content would retain whole-program work per branch, so it was not implemented.

Root continuation options: (1) a separately scoped content-key slice, initially deriving exact reach
content once per new program revision and sharing the expensive derived value (smallest correct,
but still scans content on a fresh branch); (2) carry an exact program/reach content identity through
the existing branch/commit owner, seeded from the loaded commit and updated from changed declarations
and graph facts (recommended; needs ownership and proof at that boundary); (3) add an upstream
structural-index difference seam, then derive the key from changed index ranges (larger dependency
work). None is represented here as completed or as a cheap existing API.

## Reproduction and health

Initial `bin/seon status` and MCP `runtime_status` answered successfully, state `:faulted`, 7 open
faults and 37 errored receipts. Reach declarations retained 204,743,904 estimated bytes at zero hits;
task `seon.test.runner/reach-declarations-misses-on-every-branch` already exists in its task EDN.
Storability's task is likewise already in `resources/seon/tasks/seon.schema.datahike.edn`.
No runtime-only task is tagged.

MCP agent `pass-waste`, cluster default, SCI scratch namespace: `(+ 1 2)` returned 3, 0 ms.
The installed pass probe calls the real MCP entrance and measures platform-thread allocations,
all-thread allocated bytes, armed calls, commits and store bytes. The committed `measure.clj` is the
exact paired isolated test probe; copy/run instructions are in its header. Shared-JVM values
are upper bounds; no JFR was needed because armed producer counts identify the discarded work.
Initial ordinary rows: 43.813/67.331 ms, 59,075,312/105,608,944 bytes, 10,684/10,976 calls,
1 own commit each, 254,486/250,296 store bytes. A subsequent three-pass sequence:

| kind | ms | allocated bytes | calls | own commits | store bytes |
|---|---:|---:|---:|---:|---:|
| ordinary | 59.827 | 55,353,960 | 10,723 | 1 | 261,077 |
| defn | 252.737 | 287,421,984 | 36,312 | 1 | 648,961 |
| after defn | 102.296 | 117,882,352 | 127,485 | 1 | 245,379 |

The sequence counted 8,249 attribute-declaration calls and 463 diagnostic calls (19/18 inclusive ms).
Per-thread ordinary allocation was dominated by async-mixed (~31 MB) and ForkJoinPool carriers
(~27–73 MB). Existing projection and writer tasks cover remaining defn work;
`seon.schema/call-with-projection-state` health already carries c4ece4f4eec9/d27179bab996.
The no-position definition fault belongs to `seon.sci.arm/a-def-without-line-metadata-refuses-its-origin`.

## Verification and limits

Initial full-tree `clj-kondo --lint src script test --cache false`: 11,849 ms, zero errors, 1,460 warnings.
First isolated candidate b5a73aa1f60e: five tests, 26 assertions passed, one failed, zero errors, 5,595 ms.
The MCP regression already proved zero attribute-declaration calls and value 42. The failing extra
assertion expected *all* diagnostics from every pass subsystem to be zero, but counted four unrelated
diagnostics; it was removed in favor of the producer's zero declaration calls. The storage regression
separately asserts zero diagnostic construction for boolean checks, for 1/10/100 requests.
Candidate readiness was 54,659 ms and boot 63,410 ms; this is not ordinary-pass performance.
A premature measurement through the candidate's prepl, before its instance was ready, answered a
nil-connection contract error; no measurement from that attempt is used. No source was loaded into default.

Foreign boundary: `src/seon/sci/eval.clj` belonged to stale-caller and was never edited. HEAD advanced
during this lane, including that lane's landing and armed-calls' independent diagnosis; each check
uses HEAD plus only this lane's paths. No other lane's process or disposable root was changed.


## Paired isolated measurements

Same `probe/run-mcp running "sci" 2 #{1 2} true` on fresh throwaway JVMs, HEAD plus the measurement
fixture alone versus HEAD plus this slice. Parent candidate 8083bb36b2d8, run a13c549b4ce3;
change candidate d06c008d209b, run 5631e9aa5149. Every measured pass completed with `error? false`.
The evidence probe intentionally fails one assertion to expose its data through the runner's value
printer; it is preserved in docs and removed from test/. The change run also ran both correctness
regressions: 19 assertions passed, no errors. No production failure was suppressed.

| pass | parent ms | change ms | parent bytes | change bytes | parent calls | change calls |
|---|---:|---:|---:|---:|---:|---:|
| ordinary 1 | 35.274 | 35.796 | 23,607,664 | 23,439,000 | 7,499 | 7,593 |
| defn 1 | 286.783 | 285.450 | 373,644,920 | 372,391,416 | 61,710 | 61,339 |
| after defn 1 | 83.617 | 71.555 | 86,105,264 | 63,068,272 | 127,754 | 57,595 |
| ordinary 2 | 32.724 | 29.484 | 24,886,488 | 24,507,416 | 8,040 | 8,106 |
| defn 2 | 155.517 | 148.623 | 115,354,288 | 116,230,736 | 30,594 | 30,736 |
| after defn 2 | 74.168 | 61.006 | 87,892,144 | 63,681,608 | 128,242 | 57,418 |

Target after-defn allocation falls 23–24 MB (27%), armed calls about 55%, time 12–13 ms (14–18%).
Ordinary passes stay 23–25 MB; this predicate does not remove the projection walk. The second defn
allocates 0.88 MB more (0.76%); no defn allocation improvement is claimed. Its cost remains assigned
to the projection/writer tasks. Memory is total allocation on platform-thread/carrier counters,
not retained heap; no post-GC retention result was acquired. The test branch is an in-memory overlay,
so durable commit counts and store bytes are unavailable in the paired probe, not zero. The durable
shared-default baseline above is reported separately and not compared as though it were that fixture.

Final full-tree lint before acceptance: 12,530 ms, zero errors, 1,460 warnings. Production diff:
11 added / 16 removed (net -5 lines); tests: 31 added / 1 removed (net +30 lines).
Default requires root's normal adoption/restart to install the slice; no reset is needed. All owned
throwaway checks exited through bin/seon cleanup. The pass-probe retires its hosted agents in finally.
The exploratory MCP agent pass-waste is retained as evidence; no other lane's resources were cleaned.

Acceptance command names the MCP regression plus seven existing bridge tests: optional unstorable
members; lifted properties; repeated false answers; absent-schema refusal; native literal types;
compiled storage reuse; unsupported native shapes. The exact selected vars are in the commit message.
No pre-existing red in that selected set is excluded. Broad tiers remain root's work.
