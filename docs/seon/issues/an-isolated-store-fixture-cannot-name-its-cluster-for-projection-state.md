---
type: issue
status: open
severity: friction
created: 2026-09-26
tags: [issue, test-fixture, sci, environment]
---

# An isolated-store fixture cannot name its cluster for `projection-state`

## Evidence (red pass on archive 65bf078ec, `tmp/orchestrator/verify-1210.out.d/028.txt`)

- `seon.cluster.entrance-supplier-test/supplied-context-acquires-and-releases-an-isolated-evaluation` errors in its
  fixture, before any acquisition: "A cluster context needs its caller's cluster name: this world carries no
  environment and names no single cluster." (`seon.sci.eval/carried-cluster-name`, `src/seon/sci/eval.clj:225`).
- The call site is `seon.cluster.acquisition-test/with-published-store` (`test/seon/cluster/acquisition_test.clj:75`).
  It opens the `acquisition` cluster branch of a freshly populated operator root and calls the two-argument
  `(sci/projection-state database projection)`. That arity derives the name from the world, and this world has none:
  the connection carries no projection state yet, the branch forked from `:current-src` holds no `:seon.cluster/name`
  row, and the branch is not `:current-src` itself.
- The fixture knows the name, because it created the branch as `(registry/cluster-branch "acquisition")`.
  `seon.cluster.acquisition-test/acquisition-owns-only-its-isolated-resources` shares the fixture and runs only with
  `--include-long`.

## Owner and acceptance

The owner is the fixture: `with-published-store` is the caller, and the refusal is `projection-state`'s declared,
correct answer. The fixture should call the three-argument arity with `"acquisition"` rather than the world
inventing a name. Acceptance: both suites reach their bodies; entrance-supplier-test runs within its bound or declares
a measured one (the red run took 31.4 s against 5 s).
