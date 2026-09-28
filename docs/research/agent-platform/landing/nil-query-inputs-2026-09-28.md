---
title: Refuse nil query bindings before index work
date: 2026-09-28
type: research
status: complete
---

# Refuse nil query bindings before index work

seon.db/q already parses its query and aligns the inputs. Walk those parsed
bindings once before attribute lookup or query execution, stopping at the first
nil. Tuple and collection bindings recurse; nested data inside a scalar stays
one scalar. False and empty collections remain ordinary inputs. The refusal is
the existing declared seon.db/invalid-read-error, naming :in and the remedy.

Cost is O(scalar binding positions), O(binding depth) stack, with no new query,
projection, cache or input-value map. Datahike query.cljc:1536 uses nil for
unbound pattern slots. Seon's former value-type check covered an attribute's
value position but did not prevent a nil entity input from reaching query work.

## Live proof

On default c27e4aea8, through MCP JVM evaluation in scratch.astra.one-writer,
the same held database and aggregate query were passed to the installed q and
the scratch candidate. The entity-input query was
`[:find (count ?sym) . :in $ ?e :where [?e :seon.fn/sym ?sym]]`.

| Input | Parent ms | Candidate ms | Answer |
| --- | ---: | ---: | --- |
| nil entity | 9.574 | 0.039 | Both refused; parent reached append-query-evidence!, candidate refused :in before query work |
| [:seon.fn/sym 'seon.db/q] | 0.782 | 0.139 | 1 on both |
| nil symbol value, equivalent value-position query | 1.687 | 0.129 | Both refused |
| 'seon.db/q, value-position query | 0.635 | 0.254 | 1 on both |

These are bounded real-data probes, not a reproduction of the owner's 7.1 s
receipt pull. Parent outer q was armed and the candidate outer q unarmed;
nested installed owners remained armed. Valid-input timings are single paired
samples, not a claimed throughput gain. No probe transaction or store bytes were
added by the read itself; ordinary MCP evaluation recording is separate.
Exact probes and complete recorded envelopes are in tmp/astra-analysis-nil-20260928.
No default Var was replaced. After the restart, the 1/10/100-input probe
completed: parent index executions were one per query and candidate executions
were zero, with both refusing the nil binding. Collector-inclusive parent times
were 14.326/11.962/11.394 ms, candidate 9.685/8.394/8.461 ms. The complete
envelope is nil-count-result.json beside the probe.

## Verification and foreign boundary

Archive 83533cc8cde99afc2e64c366f0ed94c3ace39b2b plus only db.clj and db_test.clj:
clj-kondo src script test --cache false: zero errors, 1616 repository warnings,
11011 ms for the mandated full-tree check. Contract compilation: 507 files,
7316 ms, no touched findings; the same ten foreign schema-reference findings
remain in script/seon/dev/docstring.clj and script/seon/dev/markdown.clj.
Whole-repository contract compilation is not green.

No tests or drills ran. Root's focused tests:

- seon.db-test/nil-query-bindings-refuse-before-reading-the-index
- seon.db-test/relation-only-queries-need-no-database-schema
- seon.db-test/malformed-public-database-requests-name-the-public-operation

The new regression covers scalar, collection, tuple and relation nil inputs,
valid empty/absent inputs, the declared error schema, and 1/10/100 bindings with
zero vector-query-call executions and a linear predicate-call bound. This read
guard changes no schema resource, publication, adoption or boot path. Source
+38/-2 (net +36), test +29. Root's committed stamp-transaction work is preserved.
