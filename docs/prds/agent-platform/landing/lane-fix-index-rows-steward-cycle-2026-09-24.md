# Lane fix-index-rows-steward-cycle (2026-09-24)

Issue: `docs/seon/issues/whole-program-index-rows-overflow-on-an-agent-steward-cycle.md`.

**What exists and the approach.** `seon.fn/published-index-rows` reads program rows
through `reached-entities` (one `pull-many` per ref depth) and `reference` inlines any
ref target that has no program row identity. The partition is already declared on the
entity schemas (`:seon.program/partition`, derived by `seon.program/program-attributes`);
the fix reads it instead of adding a cycle guard or a hand list.

## Broken first

- **The save gate refuses every fn.clj save on pre-existing reds** (13 fail, 4 error:
  mcp-test ×3, publication-findings, config-application, fn-test ×5, mcp-test, program-test,
  reconcile-test; none reach this change). My first version reached default only because
  the test-file save added a host-bound deftest, which adopts first ("red after adoption",
  run 3b2211084bc2). The final version (a cheaper descent gate, same output) is
  **published but not adopted** (save refused, run e9a8c0acc41b); default runs the first
  version, which is correct but ~14% slower. It adopts with the next accepted publication.
  RESET NEEDED: no.
- A non-program entity reached by a ref that has no `:db.unique/identity` would still be
  inlined (as `{}` for its unwalked refs). None exists on default; it is not refused loudly.

## Change

- `reference`: a target with no program row identity and no program attribute is written
  as its first declared `:db.unique/identity` pair (`db/identity-attributes`).
- `reached-entities` takes the program attributes; past the named rows it descends only
  through entities with a program attribute, so the agent is read for its identity and its
  runtime/turns are never pulled.
- Census on default (JVM, same db value): the only non-program ref target is
  `:seon.ns/steward` → agent (1). Components (arities, aliases, imports, refers) and
  `:seon.schema/shape` (partition `:seon.program`, non-component) still inline unchanged.

## Evidence (MCP eval_clj, JVM, default pid 99265)

| probe (one db value, 12,459 identities without `my.agents.root`) | before | after |
|---|---:|---:|
| `published-index-rows` output | — | `=` before: true |
| same, ms (4 runs, loaded shared JVM) | 709 688 738 809 | 731 719 721 875 |
| whole program (1-arity) | StackOverflowError | 12,460 rows, 724–761 ms |
| `my.agents.root` steward | overflow | `[:seon.agent/id "root"]` |

`program-attributes` costs 33 ms per call and is included. The first version (row-identity
check per walked entity) measured 770–790 ms vs 680 ms, so it was replaced before commit.

## Regression

`seon.fn-test/index-rows-name-a-data-entity-by-its-identity`: an agent made by
`seon.cluster.agent/creation-tx` (runtime points back) stewards its namespace; the
namespace row indexes and names the agent by identity.
`bin/test-check default --test …`: pass (run 69acaa9bec3f, 2,936 ms).
`seon.fn-test/indexing-refuses-an-already-populated-branch`: pass (run c3b53c38d0a7,
3,143 ms); red with StackOverflowError before.

## Size and limits

`src/seon/fn.clj` +18 −6; `test/seon/fn_test.clj` +15. The regression was not run against
the old code (the old code overflowed on the same cycle class at the REPL). Timings are
single warm runs with other lanes' analysis running in the same JVM.
