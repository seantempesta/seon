---
type: issue
status: open
severity: blocker
tags: [issue, database, agent]
---

# Preserve cache revisions for materialized database values

## Problem

A query over a materialized committed database value could inherit a cached
result from a different commit in the same connection generation. Stored
commits do not retain Datahike's process-local attribute revision map, but
`commit-as-db` attached only connection, generation, commit, and committed
status. Two materialized commits therefore compared absent attribute revisions
as equal and unsafe result promotion became possible.

The exact-artifact root delegation proof made this visible in the transcript.
Prompt database values advanced through basis transactions 536871546,
536871556, and 536871565. The first two evals committed at 536871554 and
536871563, and an as-of query proved each eval and its turn link existed before
the following render. Nevertheless the aggregate turn count and ordered,
limited turns query repeatedly returned the pre-run result, so every prompt
omitted the immediately preceding eval and still displayed turn 14. The
correct repetition guard then bounded three genuinely identical model
observations with `:no-forms`.

## Owner and correction

Maintained Datahike revision `6f256908` strengthens
`datahike.versioning/attached-cache-context`. When the attached source owns the
selected commit, its exact attribute revisions remain intact. When
`commit-as-db` selects another commit and exact revision history is unavailable,
the materialized database value uses its own commit ID as the conservative
revision. Exact same-snapshot cache hits remain valid, while cross-commit
promotion cannot treat missing revisions as unchanged.

The regression caches both an ordered/limited relation and aggregate count at
one materialized commit, advances the branch head, then reads an intermediate
materialized commit containing a newly matching entity. Before the correction
all three JVM profiles returned the older row and count. Afterward PSS, HHT,
and specs return the selected commit's two rows and count, and repeated reads
hit the exact snapshot cache entry.

## Acceptance

- The focused regression and full query-cache plus versioning namespaces pass
  under persistent-set, hitchhiker-tree, and specs instrumentation.
- The maintained Node ClojureScript suite remains green.
- A rebuilt exact-artifact root run sees each preceding eval in the next
  prompt and proceeds from one `my.plan/position` read to delegation without
  changing the repetition guard, plan selector, or transcript renderer.
