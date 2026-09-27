# Whole-acquisition refusal from an unchanged core row (2026-09-27)

Lane `probe-acq-refusal-opus`. When `seon.sci.eval/install-row!` installs the
reverse closure of an agent's change, it interprets every row in that closure.
If interpreting an unchanged `:core` row threw, the result became an
acquisition refusal. The acquisition then refused every later form on that
branch. The fix makes such a row refuse by name when called, which is what
be96a64ba already does for host-bound rows.

## Broken, and what remains

- **Confirmed.** On a disposable branch whose agent revised
  `seon.render.value/datum`, the acquisition refused with exactly one member,
  `seon.cluster.agent/graph-definition`. The message was
  `Cannot interpret seon.cluster.agent/graph-definition: Unable to resolve classname: CountedSlidingBuffer`.
  `datum`'s closure has 412 declarations.
- **After the patch, a remaining fidelity gap.** `graph-definition` refuses by
  name when called instead of running, because the SCI context is missing an
  import:
  - `src/seon/cluster/agent.clj:596` calls `(CountedSlidingBuffer. ...)`.
    `CountedSlidingBuffer` is a same-namespace `deftype` (`agent.clj:109`).
  - A `deftype` imports its class into its namespace implicitly. The compiled
    namespace has the mapping (`(ns-imports 'seon.cluster.agent)` returns
    `seon.cluster.agent.CountedSlidingBuffer`), but no `:seon.ns/imports` fact
    names it.
  - SCI resolves the fully qualified name (`:classes {:allow :all}`, probed:
    `(seon.cluster.agent.CountedSlidingBuffer. ...)` evaluates). It fails only
    on the short name.
  - The smallest faithful follow-up: `install-caller-host-bindings!`
    (`eval.clj:2320`) already gives an interpreted namespace its loaded JVM
    namespace's remaining interns. It could also give it the loaded
    namespace's own-class imports. That is not in this patch; it is recorded
    here for root to schedule.
- **Not recorded as a fault.** A row that refuses by name carries its whole
  cause (`interpretation-failure`: class, chain, frame) in the thrown refusal.
  It also appears in `:seon.sci.eval/load-results` as `:unavailable` with that
  message. Like the be96a64ba path, it is not recorded as a fault at
  acquisition.

## Cause

- Before the patch, `src/seon/sci/eval.clj:1850` (HEAD b3f1a7ae7) handled an
  interpretation failure in the `catch` of `install-row!` by returning
  `interpretation-failure`.
- `acquire-program!`'s `install-row` (`eval.clj:~3010`) sends any
  `:seon.sci.eval/refused-function` result to `refusal-member` (`eval.clj:2731`).
  For every non-agent row, that is `::acquisition-refusals`.
- `::acquisition-refusals` makes every evaluation throw
  "SCI program acquisition refused a declaration." (`eval.clj:4637`, `4649`).
- Every interpreted `:core` row is unchanged. `overridden-row?` requires
  `:agent` admission, so a core row is interpreted only because it is in an
  agent change's reverse closure.
- be96a64ba covered only host-bound rows and callers in unloaded namespaces
  (`unbound?`). A non-host-bound row whose interpretation throws for any other
  reason still refused the whole program.

## Decision: option (b)

After interpretation, an unchanged `:core` row whose interpretation failed
(`result` is a map, no prior `refusal`, not an agent's own evaluation) installs
`install-refusing-root!` with its `interpretation-failure`. The failure message
goes into the row's load result. An agent's own evaluation still gets the
refusal returned, as before.

- **Why (b):** "make failure local and loud" and "refuse an unsupported override
  by name instead of running other code" apply to every cause of a failed
  interpretation, not only to a deftype import. (b) is the general owner, adds
  no mechanism, and reuses be96a64ba's path.
- **Why not (a):** marking a function host-bound because it constructs a
  same-namespace deftype is a hypothetical restriction. SCI can run the code
  once it has the import. `seon.fn/host-bound-callers` says class usage never
  makes a declaration host-bound. (a) would encode a limit that does not exist.
  The correct classification-side fix is the import follow-up above, not (a).

## Patch

`tmp/probe-acq-refusal-opus/unchanged-core-interpretation-refuses-by-name.patch`
is against HEAD b3f1a7ae7 and applies cleanly to the working tree. It touches
two files:

- `src/seon/sci/eval.clj` (`install-row!`): +10 lines, 0 deleted.
- `test/seon/sci/branch_execution_test.clj`
  (`an-unoverridden-host-bound-declaration-refuses-by-name-only-when-called`):
  +7 lines, 0 deleted.
  - The existing `(= #{} (:refused view))` is the class regression: it
    changes `datum`, whose fixture closure reaches `graph-definition`.
  - The added assertion checks that `graph-definition` refuses by name when
    called.
  - If the import follow-up lands, `graph-definition` interprets and this
    added assertion must change to call it successfully.

Lint (`clj-kondo --cache false --lint src script test`, a `git archive` of HEAD
plus the patch): 0 errors and 965 warnings, the same as HEAD. The two touched
files: 62 warnings, the same as HEAD.

## Measurements

All acquisitions ran on the probe branch against the same edited database with
a fresh context (JVM REPL, default pid 99840). Both variants were loaded as
scratch namespaces, with their global predicate registrations removed and
`loaded-identity` pointed at the live atom.

| variant | first (base-ctx miss) | memo hits | refused | calling `graph-definition` |
|---|---|---|---|---|
| live `seon.sci.eval` (HEAD) | 1,004 ms | — | `#{graph-definition}` | — |
| scratch copy of HEAD | 555 ms | 35, 3 ms | `#{graph-definition}` | unresolved symbol (the row was `ns-unmap`ped) |
| scratch copy, patched (older base) | 564 ms | 3, 3, 3 ms | `#{}` | refuses by name |
| scratch copy, patched (HEAD b3f1a7ae7 + patch, exact bytes) | 454 ms | 35 ms | `#{}` | refuses by name |

The patch does not change acquisition cost; the change is in a per-failure
branch. The 1,004 ms live figure included the first derivation of this key on
a warm-but-different JVM path. The comparable cold numbers are the scratch
rows, 454–564 ms.

## Focused tests for root

- `--test seon.sci.branch-execution-test/an-unoverridden-host-bound-declaration-refuses-by-name-only-when-called`
- `--test seon.sci.eval-test/a-stale-host-bound-row-refuses-by-name-only-when-called`
- `--test seon.sci.eval-test` covers the agent-row acquisition refusals
  (`acquire.poison`, `acquire.custody`). Those rows are `:agent`, so they are
  unaffected.

## Side observation

On the branch, an agent-REPL `defn` of `seon.render.value/datum` (the MCP `sci`
mode) was refused at entry. The response was
`{:seon.error/member :seon.test.accretion/install?, :expected true}`.

- Because of that refusal, the probe transacted the agent revision through
  `seon.test-support/revision`, the same way the regression does.
- Not investigated. Root should decide whether this entry refusal on a
  file-derived private function is intended.

The probe branch has been retired, and the scratch namespaces and disposable
directories have been removed.
