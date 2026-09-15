---
type: issue
status: open
severity: blocker
tags: [context, database, read-evidence]
---

# Bound pull selectors retain all-attribute evidence

Observed 2026-09-14 in the context-renders lane's canonical fixture and a
read-only default JVM probe. `seon.sci.eval/directory-value` calls a query
whose `pull` selector is an input. Its dependency plan and revision say
`:datahike.read/attributes :all`, while the same source's complete
`:seon.db/read-index-patterns` names only function, namespace, and schema
attributes. The first version of the generated-read invariant refused this
coarse revision; the existing freshness checker already prioritizes the
complete index patterns over it. The invariant now uses the same authority.

The existing owner is `seon.db/read-evidence`: it attaches the exact index
patterns but computes the revision from the original conservative plan.
`query-index-patterns` already resolves the supplied selector through the
Datahike parser and pull-plan compiler. No `dir` special case is needed.
When every complete index pattern names an attribute, the plan's attribute
set can be derived from those patterns before computing its revision.
Wildcard patterns must remain conservative.

The original lane assignment restricts DB edits to render pairs. Refining
the coarse revision is a separate cleanup; the complete index evidence is
sufficient to verify the generated-read invariant without discarding it or
changing the documentation implementation.

Regression required: an input-bound explicit pull selector records its
actual attributes, unrelated writes leave it current, selected-attribute
changes invalidate it, and wildcard selectors remain conservative.

## Collection-bound attributes

The same invariant gate also found a case without complete patterns:
`seon.render.ns/render-ai` emits a query with `:in $ [?attribute ...]`.
`seon.db/query-index-patterns` resolves only `BindScalar` inputs, so its
pattern has no attribute even though the collection argument names a finite
set of attributes. This blocks the generated namespace read. The correct
repair belongs in that existing query-evidence function, using Datahike's
input binding semantics (`datahike.query/resolve-ins`), with scalar,
collection, tuple, and relation regressions. Do not change the fixture's
attribute names or weaken the invariant for this query.

### Collection-input repair, 2026-09-14

The owner authorized `query-index-patterns`. It now obtains input rows
through Datahike's existing `resolve-ins` and `collect`, preserving the
dependency walk for each binding. The armed DB regression supplies two
attributes through a collection input and verifies both in the evidence,
freshness after an unrelated write, and invalidation after either bound
attribute changes. It fails on unchanged HEAD and passes with the repair.
The invariant loop proof passes three virtual turns with zero generated
rereads and zero added system bytes. The coarse-revision cleanup described
above remains separate; complete index patterns already provide the needed
authority.
