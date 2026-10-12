---
title: Recording and acquisition cost, second investigation
type: research
status: in progress
---

# Recording and acquisition cost, second investigation

The recording transaction passes through `seon.db/transact-call`, Datahike's
writer, index serialization and konserve. JFR identifies repeated Fressian
serialization as the largest commit CPU share. Use Fressian's existing
per-stream cache for datom attributes; remove repeated keyword encoding,
without changing the datom tag, its list component or its reader.

## Baseline and profiler

Default runs `074a2c045`. Both status entrances answer and report existing
SCI acquisition errors, an installation error, and a cache warning. The first
lane form, `(seon.context/current-state)`, returns the turns remaining but
no assigned task. No source is loaded into default's JVM and it is not restarted.

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
were edited. Default needs root's batched restart to load the dependency
change (**RESET NEEDED**, retaining its store).
