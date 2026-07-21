---
type: issue
status: open
tags: [issue, test, rendering]
severity: blocker
---

# Full writer host fixtures lack the required value-sampling policy

## Evidence

The frozen full writer gate after Unit 1G ran 281 tests / 2,128 assertions
with 22 failures and 2 downstream errors. Both failing host integration
fixtures stop at the first invocation with the same core error: `The invocation
database lacks a complete value-sampling policy.`

`host_registry_writer_test` and `host_graduate_writer_test` create fresh memory
databases and seed the corpus schemas, agent, process, and turn, but no
`:seon.config/id "cluster"` entity carrying the seven required value-sampling
attributes. Narrow host tests supply this policy explicitly; the two complete
host drives predate the Unit 1G fail-closed acquisition contract.

## Acceptance

- One shared writer-test fixture value seeds the seven production sampling
  attributes on the cluster configuration entity in both fresh databases.
- The runtime remains fail-closed when any policy attribute is absent.
- The two focused host integration tests pass.
- The complete writer gate passes without sampling-policy failures.
