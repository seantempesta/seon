---
type: issue
status: resolved
severity: blocker
created: 2026-09-20
tags: [testing, schema, publication]
---

# Fast admission loses the offending declaration from a live schema refusal

Resolved at the producer by `667f6519e`: the catch preserves the complete
exception data (except the retired kind stamp) and supplies any absent base
observations. Both subsequent fast snapshots admitted and executed tests.
The earlier malformed-facet refusal did not recur; its declaration is still
unknown, and this resolution does not claim a schema repair. Completion
recording separately reached the existing 30-second prepl silence bound;
that observation belongs to
[the persistence issue](test-results-persistence-can-time-out-during-development-adoption.md).

The bridge step-1 fast snapshot at HEAD
`7a7620fb6cdd7374b59a9d15edf1bfe12b31be4f` plus its owned overlay loaded and
armed 1,408 contracts (1,383 program-armable), then refused admission before
executing any test. Run `35732b099936` returned:

```clojure
{:seon.error/message "A boolean marker alone cannot define an error facet."
 :seon.error/kind :user-input}
```

`src/seon/test/runner.clj:3190` constructs the live recording form. Its catch
returns only kind and message, dropping the offending schema/member evidence
from `src/seon/schema/internal.cljc:173`. `record-snapshot!` at `:3237` then
reports that the authority returned no admission or result facts. This is
distinct from the earlier result-context recording refusal after 849 tests:
the new request runs zero tests.

The exact offending declaration and reason it is present in the recording
authority remain unknown; this note does not infer stale adoption or blame
uncommitted source. The snapshot excludes foreign edits and the prescribed
eight-namespace source load exits 0. The recording path crosses the held
`src/seon/cluster/source.clj` and `script/seon/fresh_operator.clj`; the lane
did not change them, bypass admission, or operate default.

Resolution must preserve the original declaration/member evidence and restore
admission through the canonical recording authority. Then rerun the pending
bridge check from
[the lane note](../../prds/steward-platform/research/bridge-step1-registry-2026-09-20.md).
The related broader admission failures remain in
[the projection-acquisition issue](test-refusal-observations-overflow-in-projection-acquisition.md).
