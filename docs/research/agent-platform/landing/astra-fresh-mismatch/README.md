# Fresh boot test admission: private namespace state

The SCI observer compares each namespace map after an evaluation. That map
also holds private result interns and SCI's lazily cached namespace object.
`changed-namespaces` now compares resolver fields only after its identity
fast path; admitted definitions already contribute their namespace in
`seon.turn.batch/derive-form`. No analyzer/publication code changes, no new
cache, retry, boot barrier or test-admission exemption. Cost remains O(namespace
count), with six map lookups for each namespace whose map actually changed.

## Cause and evidence

The suspected analyzer identity mismatch is refuted. On fresh cached boot
041d1d173, the first request selected at basis 536870927. Before admission,
root's `seon.turn/append-generated-call` committed transaction 536870928,
including new program declarations `result` and `user` (source `(ns result)`
and `(ns user)`). Test admission correctly detected program writes since its
basis, then `program-digest` refused the in-transaction value, which has no
commit identity. The refusal's expected value is that typed error, not an
analyzer digest. `refusal.edn` retains the complete refusal and program-attribute changes;
`changed.edn` identifies the two declarations and the writing operation.

A rebuilt-cache boot finished the generated turn before admission and passed
admission. Warming admission before the writer also hid the race. The exact
baseline `check --test seon.error.refusal-test --test
seon.cluster.program-change-test` refused: boot readiness 36,067 ms, test
request 1,499 ms, total check 60,689 ms. Rehearsal 13,973 ms includes unrelated
HEAD landings. Original analyzer-only 16.3 s -> 1.9 s optimization is untouched.
No new publication speedup or paired publication measurement is claimed.

The task's corrected ingestion handle is cbc58b6e7102 (4e84189bf), formerly
hand-typed as 9e7172cb5a16. Its stale analyzer explanation is removed with the
fix. Regression covers retained result handles, lazy namespace lookup, and a
positive resolver change through a real fixture SCI fork.

## Authorities and checks

SCI pin ce0aa53b9bf35029e0bc27a962d799c196a37874:
`src/sci/impl/utils.cljc:211-219` lazily caches `:obj`;
`src/sci/core.cljc:701-737` distinguishes resolver structure from interns.
Seon `src/seon/turn/batch.clj:354-362` adds admitted definitions' namespaces.
Contracts compile through `seon.contracts-compile-test/check`, using Malli
`function-schema` (reference-code/malli/src/malli/core.cljc:3130).

Archive of HEAD 041d1d173 plus this slice:
- clj-kondo src script test --cache false: 0 errors, 1551 warnings, 6170 ms.
- Contract compile against archive packaged resources: [], 69.4 ms.
- `git diff --check`: clean.

The exact two-namespace gate on candidate 21cf487fda9cf4c834ad76f1e5e9365783f3ee9e
ran f8f950238953: executed 27, reused 0, pass 196, fail 0, error 0, 5703 ms.
Fresh-boot admission and the new regression pass. The gate nonetheless refuses
because the pre-existing declared-long retirement test is excluded; the operator
has no include-long check option. This is the foreign operator boundary, not a
green namespace result. The final commit gate names the refusal namespace and
the two relevant program-change regressions explicitly. Root still owns the
retirement test with include-long and broader integration.

Focused fresh-candidate gate dd433c938aa216793a3be91c48a4aa2767e3e401:
run e9cbb806b077 / executed 19 / reused 0 / pass 144 / fail 0 / error 0 /
4387 ms / passed? true. Boot ready 37,977 ms; publication 8802 ms, adoption
1594 ms (154 identities across intervening HEAD changes); total check 66,291 ms.
Gate lint: 0 errors, 1552 warnings, 6212 ms. No controlled memory measurement.
The first commit attempt then hit bin/lane's untracked-path bookkeeping;
registering only this lane's new evidence files in Git's index permits its
ordinary commit path. No gate was bypassed.
Default was already faulted; other lanes changed unrelated files throughout.
No foreign source was edited, no code loaded into default, no shared restart.
Probe JVMs used owned disposable roots through bin/seon and ran admission-only
requests with a writer that stops before every body. Raw probes and their
outputs are retained under this directory; no test bodies ran in those probes.
