# Loaded analyzer declarations, not unrelated file bytes

The publisher already holds the stored functions, their file identities and
the bytes loaded by the JVM. `selected-analyzer-digest` now retains the stored
declarations and their call graph when their file digest matches those loaded
bytes and the candidate does not. Candidate declarations win when the candidate
is loaded. Unknown loaded bytes retain the conservative file-digest fallback.
There is no new cache, stamp, stored field, writer or execution machinery.
The existing O(functions + reached edges) selection gains one linear selection
over the rows it already reads; it avoids analyzing all program files for an
unloaded edit. A genuine loaded producer change still invalidates derivations.

## Paired live proof

Runtime: committed archive cea904e924bbe7b6abeb2c9c358a05433048ff7f, PID 18164.
Candidate: that archive's src/test/resources/config/bin/script, dependency pins
and reference-code directory, with one trailing comment added to
src/seon/fn/analyzer.clj. Exactly one source input differs. Both runs used the
same candidate and current-src basis. Rehearsal publishes and adopts on disposable
branches, and retires them before answering; neither advances current-src.

Parent called the installed owners. Candidate used three definitions in
scratch.astra-rederive: the changed selected-analyzer-digest and unchanged
full-source-refresh!/rehearse-source! callers wired to it. Those scratch outer
definitions were unarmed; their nested owners were the same installed functions.
No production Var was redefined or reloaded. This is a branch publication/adoption
proof, not an installed boot timing. Root restarted independently after the pair.

| Stage (ms) | Parent | Candidate |
| --- | ---: | ---: |
| Capture/analysis through publication start | 12147 | 1444 |
| Program preparation through contract projection start | 2368 | 14 |
| Contract enrichment through reconciliation start | 562 | 24 |
| Reconciliation through program completion | 710 | 50 |
| Total publication | 15924 | 1672 |
| Adoption | 232 | 133 |
| Total including cleanup | 16277 | 1926 |

Parent re-derived 478 unchanged inputs and published 479 identities; candidate
published one. Full portable program export compared 14,335 rows on each saved
commit: all equal after omitting only :seon.fn.file/derivation-digest (3,530 ms).
The comparison is deliberately whole-program diagnostic work. The candidate's
1,444 ms capture/analysis remains above the ordinary-operation goal; this slice
does not claim sub-second publication or remove legitimate analyzer invalidation.
No controlled allocation or peak-memory measurement was made.

`key-probe.edn` records six positive checks: unchanged definitions, changed but
unloaded definitions AND calls, removed but still loaded source, changed loaded
producer, loaded deletion, and unknown loaded bytes. The changed producer and
unknown cases still change the key. The focused regression extends the existing
test for this behavior class rather than adding a second test.

`install-probe.clj`, `measure-before.clj`, `measure-after.clj`, `compare.clj`, and
`key-probe.clj` are the exact JVM probe sources. Evaluate them through eval_clj
in the order install, before, after, compare, key; the parent measurement assumes
the parent code is installed. Paths name the lane's disposable candidate.
The clean-*.edn files are the complete successful timing/equality values.

## Checks and boundaries

Archive b1e75c06516ebbaccf38af6bd79c08cda7dc81f4 plus the two-file diff:

- clj-kondo --lint src script test --cache false: 0 errors, 1,570 existing
  warnings, 7,544 ms. Exit 2 represents warnings, not errors.
- Contracts compile against that archive's packaged projection: no findings,
  139 ms. The checker uses Malli function-schema (core.cljc:3130); the changed
  function retains its complete existing contract.
- bin/seon check src/seon/cluster.clj test/seon/cluster/program_change_test.clj:
  OK, 5,049 ms; archive 1,190, publication 3,070, adoption 644 ms.
- git diff --check: clean.

No test requests or bin/test were run. Root should run:

1. seon.cluster.program-change-test/a-launch-on-analyzer-bytes-with-unchanged-definitions-publishes-selected
2. seon.cluster-test/the-analyzer-digest-follows-the-analyzing-declarations-only

An initial historical candidate (fc1497469~15) refused before analysis because
its :seon.eval/origin refers to undeclared :seon.issue/id; this foreign schema
boundary was preserved in baseline.edn and did not block the controlled proof.
An initial incomplete candidate omitted 47 supporting inputs; its results were
superseded by the clean pair above. Default was already faulted, and independently
restarted twice during this lane. We did not restart or stop it. fn.clj and its
tests were inspected but not changed; no expanded ownership is needed.

Source +19/-7 (net +12); tests +13/-0 (net +13). The remaining analyzer reach
includes seon.test.accretion/schema-row-in and generatable?, which really produce
stored schema facts and therefore were not excluded.
