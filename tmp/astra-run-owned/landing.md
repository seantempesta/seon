# astra-run-owned — partial decoder improvement, request target NOT met

The query owner decodes results column by column. A find variable introduced by
`get-else` is not proven native by `native-query-result?`, so even an entirely
native result incurred an armed `decode-query-tuple` and `decode-query-field`
for every tuple and field. Keep the existing O(query) proof; when it cannot
prove the answer and no column names a codec, inspect the result for maps once.
No maps means the recursive attribute-map decoder has nothing to change.
Maps and declared encoded columns keep the existing decoder. No cache, registry,
new function, namespace reload, or instrumentation exemption is introduced.

## Outcome and limits

**The requested one-test run below 1 second is NOT achieved or claimed.**
**RESTART NEEDED** for root's installed end-to-end proof. This lane did not
restart, reload production Vars, or run any bin/test gate or focused test list.
The explicitly requested own-agent `my.test/run-owned` reproduction was run.
The decoder candidate was an armed local function value, never installed over
`seon.db/decode-query-result` in default. Source rehearsal proves publication
and adoption, not execution of changed host code.

Agent: astra-run-owned, cluster default, namespace my.astrarunowned:

```clojure
(in-ns 'my.astrarunowned)
(defn plus-one {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))
(clojure.test/deftest small-test (clojure.test/is (= 2 (plus-one 1))))
;; next run, after settlement:
(my.test/run-owned {:seon.ns/name 'my.astrarunowned})
```

First executed baseline: 4,096 ms / 270,624 calls, one pass, zero failures/errors,
run 32aea78cd041 (full reply in baseline.json). Change plus-one's docstring to
"One.", settle, then run again: 2,567 ms / 264,624 calls, one pass, zero
failures/errors, run 751957438747, result/e3bcc49675694. A 117 ms / 18,021-call
response was unchanged-test evidence reuse, not another execution.

The run's own saved transaction hot rows showed vector-query-call self time
1,061 ms then 1,002 ms, transact-call 592 ms then 377 ms, and compiled-storage
182 ms then 134 ms. The decoder change does not remove those dominant costs.
The exact caller responsible for all 35,436 decode-query-field calls remains
unattributed; do not infer that all are removable from this isolated query.
The previously offered load-core-namespaces loaded-source patch remains outside
this lane's ownership. Source inspection still found acquisition using its
fresh database rather than its loaded-source value.

## Paired same-input decoder probe

`decode-probe.clj`, evaluated in MCP JVM mode as astra-run-owned, reads the live
immutable database and Datahike's result of the function arity query. The candidate
is compiled from this lane's file into a local fn, wrapped with the real compiled
contract and profile cell. No test body or production Var is replaced.

Latest seven alternating pairs, 3,133 rows, identical complete values every time:

| | Before | Candidate |
|---|---:|---:|
| Armed calls | 12,536 | 4 |
| Median ms | 4.481 | 2.103 |
| Best ms | 4.057 | 2.026 |
| Worst ms | 5.917 | 2.340 |

Raw pairs and counts: decode-probe.edn; result/e1590fdcc99d6, whole probe 79 ms.
Nested function-bound map with canonical :seon.db/read-request text also decodes
identically to its logical nested map. An earlier probe supplied noncanonical EDN
text and correctly refused; it is not counted as a successful measurement.
No memory savings are claimed; retained heap was not measured for this local fn.
Default's concurrent status sample had heap-live 1,104,944,896 bytes; it is not
an attribution to this change. The change retains no additional cache state.

Rejected experiment: prepare writer attribute plans only for attempted attributes
and obtain other plans lazily from the existing owner. On the actual 12-entry,
90-datom program overlay, calls fell 38,510 -> 33,759 but median time was
108.70 -> 109.61 ms. Removed every production/test edit from that experiment.
Local raw evidence is writer-probe.edn and rejected-lazy-plans.patch.

## Guarantees and verification

Datahike gitlink d9121bbbad955b383c421c796aceff4006016c9f:
query.cljc:472 (`-get-else`) returns the stored value or the supplied default.
Malli gitlink 43c6dca8d2d8df3162b13725939954807be4a4ed:
core.cljc:353 (`-cached`) and `validator` retain compiled validation; the probe
uses seon.instrument/compiled-wrapper, preserving the production contract.
Seon's db.clj:1928 recursive attribute-map decoder only changes maps' encoded
attribute values. Existing static-native proof is unchanged. The new scan costs
O(result nodes) only when that static proof fails and all column codecs are nil;
the previous fallback already visited those nodes through armed wrappers.
A late nested map can add one scan before the existing decoding walk; no
end-to-end latency guarantee is made for that mixed shape.

Archive of HEAD plus only db.clj and db_test.clj: clj-kondo --lint src script test
--cache false: 0 errors, 1,601 warnings, 6,923 ms. Contract check against that
archive's packaged projection: [], 130 ms (result/ec74df7410614).
`bin/seon check src/seon/db.clj test/seon/db_test.clj`: OK, 5,768 ms total,
3,565 ms publication / 843 ms adoption / 1,180 ms archive, 281 adopted identities.
Source rehearsal commit 6abb0321-f1b8-5762-8a34-59586d2bff9f.
These required checks exceed one second; they publish the archive's changed
program, not the single test's work. No sub-second claim is made for them.

Foreign boundaries: default restarted several times during this lane. Early status
reported unavailable endpoint, MCP reported starting, and one call reported prepl
closed with unknown outcome. A later probe was blocked by another lane's unmatched
parenthesis at src/seon/sci/eval.clj:3210. No foreign files were repaired, restored,
committed or sessions messaged. schema.clj's WIP was untouched. Default remained
faulted from existing observations. Archive proof excluded all foreign WIP.

Root's exact focused tests after installed reload:
- seon.db-test/a-query-with-nothing-to-decode-returns-datahikes-value
- seon.db-test/edn-backed-reads-return-distinguishable-logical-values
- seon.db-test/committed-edn-reads-as-written-unless-its-storage-is-malformed
- seon.db-overhead-test/query-preparation-has-bounded-armed-work

Then rerun the actual own-agent probe after changing plus-one and settling, and
report fresh execution rather than :seon.test/unchanged. Root owns that test round.
