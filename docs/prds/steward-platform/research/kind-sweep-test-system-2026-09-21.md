---
type: research
status: blocked
created: 2026-09-21
tags: [error-model, kind-retirement, test-system]
---

# Kind sweep — test system

Read the binding error-conversion PRD and the four requested landing notes end
to end. This bounded lane used the data-oriented-clojure, clojure-testing, and
repl skills. It did not operate the default cluster, create a worktree, launch
a test JVM, run a cold gate, or edit source, schema, test, held, or foreign
dirty paths.

## Section-6 stop condition

The conversion stops before production edits because multiple facets in the
same test-runner family would share every required member after the literal R3
and R4 conversion. In `resources/seon/schemas/seon.test.runner.edn:74-83`,
these five facets consist only of a retired `[:= true]` marker, the retired
`:seon.error/class true` property, and optional message/render metadata:

- `:seon.test.runner/invalid-marker-reason-error`
- `:seon.test.runner/process-tree-exit-backstop-error`
- `:seon.test.runner/unknown-worker-command-error`
- `:seon.test.runner/unresolved-test-var-error`
- `:seon.test.runner/worker-launch-failure-error`

Removing the stamps leaves no substantive required member on any of them.
Replacing all five with the owner's existing `:seon.test.runner/error` is not
literal either: that facet requires both `:seon.test.runner/error-selection`
and `:seon.test.runner/worker-observation`, which these producers do not carry.
The runner needs distinct required observations for commands, test Vars,
process exit bounds, launch evidence, and invalid marker reasons. Choosing and
declaring those members is the schema decision the PRD's section-6 shared-
required-member stop rule reserves for the orchestrator.

Concrete consumers require the distinctions. For example,
`src/seon/test/runner.clj:3598-3688` distinguishes worker retirement, write
failure, process exit/re-arm failure, exchange bound, interruption, and exchange
failure; `:3727-3756` distinguishes worker launch failure and currently copies
an underlying kind. Tests construct and assert those separate cases at
`test/seon/test_runner_test.clj:1259-1288` and `:1378-1408`. Converting these
sites without the missing facets would either collapse real outcomes or invent
undeclared discriminator fields.

## Census and verification boundary

The required retirement search remains nonzero because the section-6 stop
preceded edits:

| Path | Matching lines |
| --- | ---: |
| `src/seon/test.clj` | 0 |
| `src/seon/test/runner.clj` | 58 |
| `src/seon/test/selection.clj` | 0 |
| `src/seon/test/accretion.clj` | 8 |
| `src/seon/test/arm.clj` | 7 |
| `resources/seon/schemas/seon.test.edn` | 0 |
| `resources/seon/schemas/seon.test.runner.edn` | 10 |
| `resources/seon/schemas/seon.test.accretion.edn` | 1 |
| `test/seon/test_runner_test.clj` | 24 |
| `test/seon/test/*` | 5 |
| `test/seon/test_cache_test.clj` | 0 |

No fast tally exists: running unchanged tests cannot verify a conversion that
the binding PRD forbids this lane to invent. The required namespace-load probe
was likewise not claimed after making no source change. The default cluster was
observed alive at PID 24777 and was not touched.

The shared tree's pre-existing dirty/held boundary was preserved, including
`src/seon/test/cache.clj`, `src/seon/fn.clj`, `src/seon/cluster/source.clj`,
`script/seon/fresh_operator.clj`, `src/seon/schema*.clj`,
`src/seon/cluster.clj`, and every other path reported dirty by `git status`.

## Decision and proof owed

The orchestrator must first name substantive required members (or an existing
owning facet) for the five marker-only runner failures above. After that ruling,
the resumed lane owes the PRD section-4 order, the required namespace-load
command, one foreground changed-input fast run, a zero retirement search, and
the final path-limited implementation commit.

The orchestrator's eventual cold proof is:

```sh
bin/test --paths src/seon/test.clj src/seon/test/runner.clj \
  src/seon/test/selection.clj src/seon/test/accretion.clj \
  src/seon/test/arm.clj resources/seon/schemas/seon.test.edn \
  resources/seon/schemas/seon.test.runner.edn \
  resources/seon/schemas/seon.test.accretion.edn \
  test/seon/test_runner_test.clj test/seon/test test/seon/test_cache_test.clj \
  -- seon.test-runner-test seon.test-cache-test
```

followed by `bin/test --platform`. This lane ran neither command.
