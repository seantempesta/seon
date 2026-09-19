---
type: issue
status: open
severity: friction
created: 2026-09-20
tags: [issue, testing, provenance, mcp]
---

# Test provenance probe exceeds the MCP observation bound

During the results-reuse lane, runtime status answered for default PID
41822. The following read-only JVM form returned an MCP timeout at
20,000 ms, with no before/after values. No transaction or reload was sent.
This is an unavailable observation, not evidence for a digest mismatch or
a particular compilation defect.

```clojure
(let [database (seon.db/db (seon.operator/connection "default"))
      row (seon.db/pull database [:db/id :seon.fn/sym :seon.fn/source]
                        [:seon.fn/sym 'seon.id/id])
      before (seon.test.runner/program-digest database)
      changed (:db-after
               (datahike.api/with database
                 [[:db/add (:db/id row) :seon.fn/source
                   (str (:seon.fn/source row) "\n; immutable results-reuse probe")]]))
      after (seon.test.runner/program-digest changed)]
  {:seon.test.run/basis-t (seon.db/basis-t database)
   :seon.fn/sym (:seon.fn/sym row)
   :before before :after after :digest-changed? (not= before after)})
```

Owner: the test provenance read and MCP observation boundary. Acceptance:
observe a bounded result or a diagnostic identifying which operation did
not complete. Do not infer that the evaluator terminated merely because
MCP stopped waiting. The lane did not retry or operate the owner's session.

Related evidence:
[results-reuse landing note](../../prds/steward-platform/research/results-reuse-everywhere-2026-09-20.md).
