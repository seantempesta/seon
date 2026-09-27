---
type: landing
lane: instrument-scope-opus
date: 2026-09-27
status: "seon.instrument-test arms only its own Vars; static checks and snapshot load green; tests for root"
---
# instrument-scope-opus — seon.instrument-test stops re-arming the whole JVM (2026-09-27)

Existing: boot arms every production Var through `seon.cluster/arm-host-program!` →
`seon.instrument/apply!` with `:seon.db/db` (call-preparation plan) and definition digests.
Defect: `seon.instrument-test` called `apply!` without `:seon.instrument/changed-identities`
(33 calls), so the process-global Var roots changed for every member. Approach: each test arms
only its own suite Vars, builds a production Var's wrapper without installing it, or reads what
boot armed. No new machinery; `src/seon/instrument.clj` unchanged (no patch needed).

## Verified cause (source, HEAD a0f795375)

- `apply!` (`src/seon/instrument.clj:780`): with no changed identities, `collect-contracts!`
  (`:751`) walks every loaded namespace; `pending` keeps Vars failing `current-wrapper?`
  (`:687`), which requires `(= policy (::policy metadata))`. A request without `:seon.db/db`
  has no `:seon.call-preparation/snapshot`, so every boot wrapper fails the check and is
  replaced by `arm-var!` (`:703`) without plan and digest.
- Passing a test branch db would not make the full path a no-op: default's boot snapshot
  holds `:seon.call-preparation/basis-t` and `:seon.schema/projection` (probed read-only at
  default pid 91336), so no test request's policy equals boot's.
- The changed-identity path always unwraps and re-arms the named Vars (`:846-850`), so
  wrapper-identity properties (unchanged content keeps the wrapper) are `arm-var!`'s
  `current-wrapper?` decision, the same predicate the full path's `pending` uses.

## Read-only probes at default (pid 91336, before the change)

- 2,227 armed wrappers, 0 without a plan; `seon.error/value`'s cell carries its digest.
- The 12 armable `seon.instrument-test` Vars are armed with plans (loaded before boot arming).
- The program database projection has function contracts for every suite Var used
  (`many-problem-input/output`, `guarded-result`, `doubled`, `declared-map-input`,
  `private-integer-boundary`, `optional-positional`, `deadline-probe`,
  `integer-inspector`, `prefix-contract-in`): the changed path can arm them.
- `src/` program population (rows joined to `:seon.fn.file/relative-path`): 105
  namespaces, 2,189 armable Vars, 0 unarmed, 0 without plan, `seon.id/valid?` digest equal
  to its row; 24 ms.
- `seon.dev.docstring`/`seon.dev.markdown` (script root) are loaded but unarmed (0 of 10):
  the old whole-JVM re-arm masked that. The converted test proves selection and the
  wrapper's refusal without depending on that state.

## Snapshot-JVM probe (git archive HEAD + diff, `clojure -M:test`, disposable)

- Uninstalled wrapper of `error/value` under default caps: offending `["not a fact"]`,
  expected `:seon.error/fact`, no `::instrument/args`, the Var's root unchanged.
- `arm-var!`: equal inputs keep the identical wrapper; a changed contract rearms around the
  same original and refuses the old input.
- `apply!` under nil `*projection*`/`*projection-state*` with one changed identity answers
  member `:seon.schema/projection` and leaves the Var's root identical.
- `(require 'seon.instrument-test)` loads: 53 deftests (was 54), 13 s.

## Tests changed

- New helpers: `arm-suite!` (changed identities for suite Vars, asserts the registered
  count; replaces `arm-prefix-contract!`), `unarmed-wrapper` (compiled, never installed),
  `default-policy`. `instrumented!` now takes the suite symbols it arms.
- Suite Vars via `arm-suite!`: optional-positional, jvm-and-interpreted guard,
  deadline-firing, many-problem, registry-sized, applying-twice (now also asserts the
  original under the wrapper is no wrapper), prefix-related siblings, re-evaluating-a-defn.
- `arm-with!` instead of a full `apply!`: authored-contract-changes, referenced-contract-changes,
  one-wrapper-per-adoption.
- Boot's wrappers, asserted armed, nothing re-armed: an-invalid-apply-request (also sends
  `changed-identities #{}` so an unarmed `apply!` could arm nothing), a-public-multi-arity,
  a-wrong-shaped-call, projection-gates, cluster-policy-and-removal, restoring-state.
- Uninstalled wrappers: a-violation-carries-bounded-arguments (`error/value` was re-armed
  JVM-wide with 4-element caps), dependency-free-linters (plus `armable` selection).
- `armable` only: the-work-launcher-api.
- applying-uses-the-acquired-projection: names its own Var; the acquired projection carries
  its function contract.
- applying-without-a-handed-projection: nil projection bindings on this thread replace
  `with-redefs-fn` over `schema/handed-projection` and `collect-contracts!` (also a
  JVM-global redefinition); asserts the named suite Var is untouched.
- the-selection-is-declared-vars: `armable` over this namespace (private included, an
  undeclared function excluded), armed twice through `arm-suite!`; the stray `prn` is gone.
- boot-arming-arms-every-loaded-contract: reads boot's arming (every armable `src/`
  program Var armed with a plan; `seon.id/valid?` digest) instead of calling
  `arm-host-program!` for the whole JVM. cold-arming-covers-the-complete-armable-population
  (which unarmed every Var in the JVM) is deleted; this read covers its population claim.

## Limits and remaining class

- Not run: root runs tests centrally. The boot-arming read goes red whenever any concurrent
  or leaked scope has stripped a production plan; that is the defect it guards.
- Tests still re-arming or redefining JVM-wide, outside this lane's files: task
  `e702974a368a` (`resources/seon/tasks/seon.instrument.edn`) lists them.
  `a-root-replaced-outside-a-reload-is-named` still uses `with-redefs` on this suite's
  `doubled` (a suite Var; left unchanged).

Lint: clj-kondo `src script test --cache false` on the snapshot, 0 errors; the test file
11 warnings (HEAD 12, all pre-existing kinds).
