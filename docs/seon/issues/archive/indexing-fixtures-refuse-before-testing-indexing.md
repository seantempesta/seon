---
type: issue
status: resolved
severity: friction
tags: [issue, test, database, class/n3, wave/test-fixture]
---

# Exercise indexing with admitted inputs and the real writer

The armed fn/analyzer gate `run.jWvccd` ran 37 tests / 191 assertions with
5 failures and 4 errors. Existing fixtures supplied a fake connection,
non-digest artifact strings, and explicit nils where optional fields must be
absent. They asserted transaction behavior before their input contracts could
admit the call. The graph parity fixture also reconstructed cardinality-many
calls as a vector, and the preview census omitted the newly ruled system turn.

A real production defect was exposed at the same boundary: `seon.fn/index!`'s
one-argument overload passed nil to its callback overload, whose contract
requires an invocable function. It now supplies a no-op observer.

The prebuilt-manifest regression now builds the complete canonical source
manifest plus its synthetic declaration, uses the canonical database fixture
and actual writer, and checks stored refs and a single transaction. Only the
analyzer is replaced with a throw to prove a prepared manifest bypasses it.
The population refusal test uses the already-published database as its source.
Digest and absence fixtures use valid values, and call parity retains set
semantics. Final isolated verification is pending.

## Resolution — 2026-09-09

Fixed in `10b034289`. The exact selected gate
`bin/test --paths src/seon/fn.clj test/seon/fn_test.clj -- seon.fn-test`
passed 30 tests / 175 assertions, zero failures/errors. The remaining
settlement fixture used retired `:seon.cluster.eval/result-edn`; supplying
`:seon.eval/value` admits settlement and installs its definition. It now
asserts the settlement refusal separately and refuses a missing fixture
entity before querying edges, avoiding the enormous unrelated graph dump.
Selected-snapshot native clj-kondo: zero errors (532 warnings).
