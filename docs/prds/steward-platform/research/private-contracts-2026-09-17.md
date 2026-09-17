---
type: research
status: active
tags: [contracts, instrumentation, private-functions, error-model]
created: 2026-09-17
---

# Private contracts — slice 1

Slice 1 verifies an already implemented selection rule. It does not introduce
a second arming mechanism. `e7230a963` (2026-09-06) changed both collection
and wrapper discovery from `ns-publics` to `ns-interns`. Current HEAD retains
that behavior. No production selector, cluster lifecycle, schema resource,
or worker arming code needed changing.

Read AGENTS.md §§0–7, the data-oriented Clojure skill, and
[the read-error class issue](../../../seon/issues/a-database-reads-error-value-is-read-as-a-row-by-its-caller.md)
end to end. Read the working edge's 16:35Z entry and program-facts PRD §1j,
plus the REPL, testing, and Datahike skills. The issue's historical
throwing-read recommendation is superseded by §1j; the issue now says so.

## Dependency ledger and arming path

- Malli `reference-code/malli/src/malli/instrument.clj:43` (`-schema`)
  reads Var or arglist contracts without checking privacy. Its convenience
  `clj-collect!` at line 52 uses public Vars, but Seon's collector does not
  call it. The primitive-function exclusion is at lines 16–17.
- `src/seon/instrument.clj:687` (`collect-contracts!`) walks every loaded
  namespace's `ns-interns`, reads `mi/-schema`, and excludes unbound and
  primitive callables. `apply!` at line 698 compares wrappers with the
  supplied projection. No privacy predicate appears in either selection.
- `function-schemas*` at `src/seon/instrument.clj:786` retains Malli's
  registry for state capture/restoration; it is not the selection owner.
  Malli's atom is `reference-code/malli/src/malli/core.cljc:3061`.
- Development adoption calls `instrument/apply!` with the selected
  database's projection at `src/seon/cluster.clj:2404`. Operator boot's
  `instrument-form` at `script/seon/fresh_operator.clj:1818` calls the same
  owner; `src/seon/cluster.clj` alone is not the whole operator boot path.
- `src/seon/test/arm.clj:167` (`arm-contracts!`) loads the derived program
  namespaces, calls that owner, and checks set coverage using `armable`.
  `src/seon/instrument.clj:65` (`armable`) also walks `ns-interns`.
- Datahike's `get-else` is an optional scan, not a missing-entity filter:
  `reference-code/datahike/src/datahike/query/logical.cljc:145–174`.
  The query uses explicit false/empty-string defaults to retain functions
  lacking optional facts. Call edges on this dated graph are refs
  (`resources/seon/schemas/seon.fn.edn:36`), so the consumer query joins the
  target's `:seon.fn/sym`.

## Live queries, explicit custody

[The committed probe forms](private_contracts_probe_2026_09_17.clj) reproduce
all three successful observations. MCP arguments: root
`/Users/sean/src/seon`, cluster `default`, mode `jvm`, `read_only: true`,
20,000 ms bound. Connection comes explicitly from
`(seon.operator/connection "default")`; each query uses one database value.
No definitions, arming, reload, adoption, restart, reset, or SCI mutation
were performed on default. `bin/seon status` and MCP both observed PID 94566.

At basis **536871348** (25 ms complete JVM form):

| Observation | Count |
|---|---:|
| Public rows with `:seon.fn/spec` | 1,168 |
| Private rows with `:seon.fn/spec` | 16 |
| Loaded private spec Vars | 14 |
| Armed loaded private spec Vars | 14 |
| All installed host wrappers | 1,158 |

The two private spec rows not loaded were
`seon.background-blob-test/binary-handler` and
`seon.instrument-test/private-integer-boundary`. The other 14 were
`seon.edit.jvm/edit`, `seon.effect-test/arm-probe-handler`,
`seon.effect-test/test-handler`, `seon.fs.jvm/glob`, `seon.fs.jvm/read`,
`seon.fs.jvm/read-complete`, `seon.fs.jvm/stat`, `seon.fs.jvm/write`,
`seon.issue/create-tx`, `seon.sci.eval/acquire-program!`,
`seon.shell.jvm/run`, `seon.test.runner/run-coordinator!`,
`seon.web.jvm/fetch`, and `seon.web.jvm/search`.
Counts are observations, not a maintained arming roster. The assignment's
1,161 public count had advanced by seven when queried.

The second form performs **one Datalog query** for private direct callers
of `pull`, `q`, `entity`, `pull-many`, and `transact!`. At basis
**536871358**, it took 56 ms and returned these derived counts:

| Population | Total | Without spec |
|---|---:|---:|
| Private direct read-consumers | 354 | 352 |
| Private direct read-or-write consumers | 401 | 399 |

This includes indexed test helpers; it is not a source-only count.
The result's namespace map was presentation-elided after 32 children;
the four aggregate counts above were present in full. No conclusion here
depends on unseen namespace entries. The probe's explicit string symbols
and ref joins reproduce the pre-reset graph, not the ruled target schema.

## Regression and cost

Strengthened the existing class regression
`seon.instrument-test/the-selection-is-declared-vars-with-schemas-and-nothing-else`.
Its canonical database fixture supplies the projection. It removes only
its own private helper's wrapper, proves absence, then calls the real
`seon.test.arm/arm-contracts!` owner. It proves privacy, positive installed
counts, valid-call behavior, wrong input/output refusals with the correct
operation and arm, and preservation of an original flat error supplied as
an argument. The fixture restores entering instrumentation state.

Measured inside that regression (JVM PID 38353):

| Canonical worker arming pass | Elapsed | Registered / installed |
|---|---:|---:|
| Re-arm after removing the private helper's wrapper | 146.408208 ms | 1,195 / 1,195 |
| Repeated pass with unchanged wrappers | 90.181375 ms | 1,195 / 1,195 |

These are single observations, including the worker's program-loading and
coverage checks, excluding fixture construction and decision acquisition.
They are **not** a controlled public-only versus private-enabled benchmark:
there is no before/after selector change in this slice. Both before and
after select private contracts, so a claimed expansion cost would be
invented. Default was not re-armed for a timing experiment.

Exact test command, foreground subprocess with `timeout=2400`, no test
configuration overrides:

```sh
bin/test-fast --paths test/seon/instrument_test.clj -- seon.instrument-test
```

Snapshot HEAD: `812c6ab2100648a1b8ba8ba7246ddce4ad08a9ad`.
Newest published graph:
`f6e9f52cfe3522bec0f4dddfa33c46f8c62948a4b07806127125d2dadf27e9c1`,
reported **18 commits behind HEAD**. The snapshot included only the named
test file. No missing-base refusal and no baseline preparation.

Exact terminal tally:

```text
Ran 30 tests containing 157 assertions.
0 failures, 0 errors.
```

The test session exited 0 and its snapshot `tmp/test-runs/run.R6cDaS` was
removed by the launcher. No foreign failure occurred. Cold gate/platform
proof remains with the orchestrator. This is a canonical fast iteration,
not a cold gate or post-reset proof.

## Findings and review boundary

1. **No public-only selector remains.** Private selection shipped eleven
   days before the ruling; absence of contracts is the current hole.
   Updated AGENTS.md §§2.4, 3, and 5, the skill's stale public-only sentence,
   and the test namespace's description. No production functions contracted
   in slice 1; no new reds.
2. **Raw host refusal still throws.** `throwing-report` at
   `src/seon/instrument.clj:450` raises `ExceptionInfo` with the flat error;
   `seon.sci.kernel/failure-value` at line 462 recovers it at the guarded
   boundary. The live private `glob` probe returned exactly:

   ```clojure
   {:data {:seon.error/kind :private-contracts/failed-read
           :seon.error/message "The read was refused."}
    :preserved true :transport :exception}
   ```

   The private consumer refuses the shape and preserves the error, but a
   raw host call does not return it normally. Recorded in the existing
   read-error class issue, not hidden behind the green tally. An initial
   one-argument probe correctly refused arity (`glob` takes two arguments);
   the successful preservation probe supplied both. No filesystem body ran.
3. **Slice 2 remains pending review.** The measured 352 missing private
   read-consumer contracts remain. No claim is made that this class has
   been fixed, that the no-throw target has landed, or that schema reset
   compatibility has been proved. No concurrent lane's files or session
   were changed. No default lifecycle operation was performed.

Owned paths are this note and its probe, `test/seon/instrument_test.clj`,
`AGENTS.md`, `.agents/skills/data-oriented-clojure/SKILL.md`, and the
read-error class issue. The requested pause is after the slice-1 commit;
slice 2 is not silently treated as complete.
