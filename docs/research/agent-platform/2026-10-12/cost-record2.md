---
title: Recording and acquisition cost, second investigation
type: research
status: in progress
---

f18ab95e3 reverted in this commit (revert-fressian): measured regression; the encoder call count was a proxy, not the cost.

# Recording and acquisition cost, second investigation

The recording transaction passes through `seon.db/transact-call`, Datahike's
writer, index serialization and konserve. JFR identifies repeated Fressian
serialization as the largest commit CPU share. Use Fressian's existing
per-stream cache for datom attributes; remove repeated keyword encoding,
without changing the datom tag, its list component or its reader.

## Baseline and profiler

Default initially ran `074a2c045`. Both status entrances answer and report existing
SCI acquisition errors, an installation error, and a cache warning. The first
lane form, `(seon.context/current-state)`, returns the turns remaining but
no assigned task. This lane loads no source into default's JVM and never restarts it.
Root independently restarted default at `3fe23b2c0` during this work; that
commit includes the first slice, `f18ab95e3`.

The previous lanes' evidence and six named commits were read before probing.
Their measurements are not repeated. The new JFR recording covers 100 warm
`(+ 1 2)` requests as agent `cost-record2`, inside root's JVM MCP evaluation.
This takes 4,790.45 ms, proportional to the 100 complete durable requests.
The profile reports `seon.db/transact-call` at 2,798 ms self time and
`seon.db/write-owned-values-error` at 94 ms over those requests. Its total
allocation window is 4,960.85 MB, explicitly including other JVM activity.

JFR uses 1 ms execution sampling, allocation sampling, 1 ms thread-park
events and file-force events with zero threshold. There are 1,951 execution
samples, of which 107 include Datahike commit. Of those, 85 include konserve
write operations, 71 include Fressian serialization, 43 include the datom
writer, and 25 include Clojure Fressian's named-value writer. These inclusive
counts overlap. There are 204 directory-force events for 102 commits; their
combined cost is 9.65 ms per commit, consistent with the supplied floor.
The writer queue is not the leading sampled CPU cost. Owning-value validation
averages below 1 ms per request.

The sampled LZ4 frame allocation remains real. The previously established
frame API limit is unchanged; this slice adds neither a compressor nor a
buffer cache. The largest sampled commit work is serialization of index
datoms, including attributes repeated throughout each encoded node.

## Attribute encoding

Pinned Datahike before this change is `c9e57c6f`; konserve is `e4334756`.
`datahike.index.persistent-set/datom-write-handler` delegates attribute
encoding to Fressian. `org.fressian.Writer.writeObject(Object, boolean)`
provides the existing per-stream priority cache. Clojure data.fressian 1.1.0
already uses this overload for map keys and the parts of named values.
The cache lives only as long as the writer; no Seon state or invalidation
is introduced. New readers and existing readers resolve the same attribute.

Red proof through `seon.profile/measure`: 1,000 datoms with one attribute
invoke the supplied keyword encoder 1,000 times, write 15,915 bytes, take
1.34 ms and allocate 303,912 calling-thread bytes. The expected count is one.
Candidate `d1fe2c92` confirms this red through the ordinary test runner:
run `18afd6eff3c9`, two passing assertions, one failure, no errors, 937 ms.
The regression tightens the existing datom serialization test and retains
its complete five-field round trip and allocation assertion.

The edit hook cannot acquire the changed test namespace in SCI; it reports
the existing acquisition failure. This dependency slice uses candidate JVMs.
The fork change is `2068b0df`, pushed on `seon/cost-record2`.

## Quiet candidate measurement

`script/seon/cost_record2.clj` uses the operator's existing check-slot,
template, candidate boot, publication and shutdown owners. It makes eight
successive measured requests while no tests run, then stops only its own
candidate by the verified process identity and deletes its disposable root.
The first request is fresh; requests three through eight are the warm set.
Raw replies are in `cost-record2-before.txt` and `cost-record2-after.txt`.

Before: warm requests 33.9–42.1 ms; recording 21.6–28.2 ms; commit CPU
4.25–5.98 ms, commit allocation 4.253–7.082 MB. The candidate uses the
operator's template store, so its timings cannot be substituted for default's
larger store. Fresh request: 273.2 ms, acquisition 177.5 ms, first test step
27.7 ms.

After: warm requests 38.2–58.0 ms; recording 24.0–34.4 ms; commit CPU
4.65–7.00 ms, commit allocation 4.590–7.020 MB. These observed windows
regress; they do not establish a request speedup. The deterministic encoder
count improves 1,000 to one. The requested recording target remains unmet.
Do not predict a 12 ms recording transaction on default after restart.

Candidate `eb87ddc2` passes the datom serialization and branch-pointer tests
through the ordinary runner. Full working-tree lint with `--cache false`:
zero errors, 1,582 warnings, 8.473 seconds. No protected source or tests
were edited. Root's later restart at `3fe23b2c0` includes the dependency change.


## First test step

A fresh agent's request calls `seon.test/changes`, which compares its program
with the commit at which it branched. `seon.test/digest-differences` queried
both complete definition-digest populations, built two maps and pulled
identities even when neither program changed. The smallest composition uses
`seon.db/content-revisions` before that derivation. Its authority is
`datahike.writing/commit-revisions` and `datahike.writing/stored-revisions`:
committed per-attribute revisions are inherited across branches. Comparing
the definition-digest attribute and the selected identity attributes costs
only their fixed count. Differing revisions retain the existing derivation;
a value without committed revisions still derives from its datoms.

On default's retained fresh branch, the original owner took 11.92 ms and
26,973,768 calling-thread bytes, with two identity pulls. The proposed
composition returned the same two empty sets in 0.05 ms and 3,936 bytes,
with zero pulls. These are measured compositions, not installed default code.

The red count assertion through `seon.profile/measure` observed two pulls,
expected zero: candidate `082ebd4c`, run `690ac6fbdb76`, five passing
assertions, one failure, no errors, 3,331 ms. The final assertion extends
`seon.test-reaching-test/a-branchs-change-is-what-it-changed-since-its-fork`,
which also proves that a subsequent changed definition is selected. No new
test machinery is introduced. Candidate `1ddfa1f1`, run `c7c4af82cff7`,
passes that test, namespace-binding selection, unchanged-branch reuse and
branch-local red selection: four tests, eleven assertions, 4,111 ms;
publication, boot and checks take 27.222 seconds in total.

The second quiet comparison uses parent `3fe23b2c0` and candidate `1ddfa1f1`.
Raw replies are `cost-record2-fresh-before.txt` and
`cost-record2-fresh-after.txt`. First request: 329.5 to 275.6 ms;
`:custody`: 210.3 to 173.3 ms; first test step: 31.4 to 26.6 ms;
recording: 40.3 to 32.0 ms. Warm requests: 33.3–43.3 to 34.2–42.7 ms.
Acquisition and recording were not changed by this slice: their variance
cannot be credited to it. The candidate still spends 5 ms in
`seon.test/digest-differences`, because it still compares differing declarations.

The diagnostic in `cost-record2-fresh-diagnostic.txt` finds a new `user`
namespace, with changed namespace and definition-digest revisions. The
candidate's template lacks it; default's retained branch already holds it.
An additional comparison explicitly starts in the published `seon.test`
namespace (`cost-record2-fresh-matched-before.txt` and
`cost-record2-fresh-matched-after.txt`). This still finds changed
`seon.cluster.store` namespace declarations, so the existing algorithm
correctly remains necessary: 16.99 to 14.51 ms, 48,215,664 to 47,857,880
calling-thread bytes, two pulls on both sides. These candidates do not
establish an end-to-end speedup for the unchanged-program case. After
restart default should avoid both declaration reads whenever the selected
program revisions equal its branch base; no specific total request time is
promised. Changed programs remain full comparisons.

Final working-tree lint: zero errors, 1,582 warnings. Source is three lines
smaller; tests add five lines; the reusable measurement driver adds three.
All timings and allocations above distinguish the candidate and default.

## Acquisition and remaining work

The fresh-request JFR on the original default covered three fresh agents.
Together they took 733.57 ms; the first two requests took 288.7 and
228.9 ms, with `:custody` at 171.0 and 139.9 ms and first test steps at
53.1 and 30.3 ms. There were twelve checked transactions, six calls to
`seon.cluster.registry/branch!`, and three SCI forks. Checked transactions
accounted for 340 ms self time, branch operations 124 ms, owning-value
validation 27 ms and SCI forks 21 ms. Inclusive query time was 177 ms
across 192 calls. The profiler also sampled rebuilding the definition maps.

The writer's startup poll was investigated and refuted as the suspected
10 ms per-first-transaction delay. Five disposable connections reported
first transaction entry at 3.42, 1.06, 0.742, 0.687 and 0.685 ms;
following entries were about 0.59–1 ms. Complete native transactions took
9–11 ms, mostly commit. No writer polling change was made. The disposable
branch was retired through its owner.

Acquisition currently creates both a working branch and a retained base,
then commits fork metadata and agent admissions on main and its branch:
three durable checked transactions before the evaluation's recording.
Consequently the complete acquisition is not only a pointer plus SCI fork.
The few-millisecond target remains unmet. Removing these transactions
requires changing the lifecycle's durable facts and their consumers together,
not skipping their writes. Existing tasks remain applicable:
`seon.mcp/a-fresh-agents-first-eval-request-acquires-its-branch-cheaply` and
`seon.test/a-check-compares-every-definition-digest-of-the-program-twice`.
The latter still applies to changed programs, whose comparison is complete.

The protected files were never edited. The only concurrent tracked change
outside this lane at the final check is `tmp/test-data-assertions/proofs.edn`;
it is excluded. Edit-hook SCI acquisition took 12–14 seconds on this branch;
that does not prove host installation. Candidate checks prove the host slice.
Default requires root's batched restart for the second slice
(**RESET NEEDED**, retaining its store).
