---
type: research
status: complete
created: 2026-09-16
tags: [research, steward, sci, evaluation-context, classloader, test-fixtures]
---

# Evaluation-context membership followed the caller's classloader (2026-09-16)

Lane: repl-rule wave 2, bounded. Branch `steward-platform`.
Live evidence: default cluster, **pid 37572**, `mcp__seon__eval_clj` mode `jvm`.
The shared fixture base was **never forced** by this lane.

## The symptom

Refreshing `seon.test-support/database-base` on pid 37572 fails with

```
First-party program namespace seon.dev.dependency-cache-test could not be
loaded for the evaluation context.        (src/seon/sci/eval.clj:1025)
  <- Syntax error macroexpanding at (dev_cache.clj:1:1)
  <- FileNotFoundException: Could not locate clojure/tools/build/api__init.class
```

`seon.test-support/create-base` acquires a real evaluation context
(`sci.eval/cluster-ctx`, `test/seon/test_support.clj:293`), which calls
`install-first-party-namespaces!` (`src/seon/sci/eval.clj:1038`). The delay
caches the throwable, so the poisoned base is shared by every lane in the JVM.

## The gate session's hypothesis is REFUTED

Hypothesis: "the evaluation context recently began loading TEST namespaces as
first-party because tests became program rows with reach closure (bbbfafaf1,
1086a7b80, 8199364a2)."

Evidence against it:

- `seon.fn/source-roots` has indexed **both** `src` and `test` since
  `0fc110286` (2026-07-29). Test namespaces have been ordinary
  core-provenanced `:seon.ns` rows for seven weeks.
- `classpath-locatable?` was introduced by `4eb8c6ab4`
  (2026-08-08 00:19 -0400) *precisely because of this*: its docstring names
  `my.background-test` as "an ordinary core-provenanced program row" that a
  `-M:dev` cluster JVM cannot serve. The existing regression
  `the-context-binds-only-the-graph-this-process-can-serve`
  (`test/seon/sci/eval_test.clj:700`) has asserted that shape since then.
- None of `bbbfafaf1`, `1086a7b80`, `8199364a2` touches
  `src/seon/sci/eval.clj`, namespace provenance, or `seon.fn/source-roots`
  (`git show --stat`). They touch `seon.test/runner`, `seon.program`,
  `seon.render.test`, `cluster/source.clj` at `index-issues!`, and schemas.
- Live namespace rows on `default`: **429 total, 214 ending in `-test`**,
  including `seon.dev.dependency-cache-test`. Nothing new.

The classpath did not change either: the dev JVM's `java.class.path` (34
entries) carries **no** `test/`, **no** repo root `.`, and **no**
`clojure/tools/build/api`.

## The actual cause: a thread-context classloader redefines process membership

`classpath-locatable?` asked `io/resource`'s **one-argument** arity, which
resolves through `clojure.lang.RT/baseLoader` — the **current thread's context
classloader**, not the process's launch classpath.

`seon.test/with-test-loader` (`src/seon/test.clj:104-118`) builds a
`DynamicClassLoader` over the `:test` alias `:extra-paths`
(`["test" "script" "."]`, `deps.edn:135`), sets it as the thread context
classloader, and binds `Compiler/LOADER` — so an in-process
`(seon.test/run ...)` executes its whole body, **including the first
construction of `database-base`**, inside that extent
(`bounded-result`, `src/seon/test.clj:126`).

Measured on pid 37572 (same JVM, same call, two loader contexts):

| namespace | outside the test loader | inside `with-test-loader` |
|---|---|---|
| `seon.turn` | true | true |
| `my.web` | true | true |
| `seon.dev.dependency-cache-test` | **false** | **true** |
| `dev-cache` | **false** | **true** |
| `seon.test-support` | **false** | **true** |
| `clojure.tools.build.api` | false | **false** |
| `clojure.core.async.flow-monitor` | false | **false** |

The last two rows are the whole failure: `with-test-loader` adds the alias's
source **paths**, never its `:extra-deps`. So inside that extent all 214
`test/` rows become "servable", the install `require`s them in
`(sort-by str ...)` order, and the first row whose own dependency is an
extra-dep rather than a source path dies —
`seon.dev.dependency-cache-test` → `dev-cache` (repo-root `dev_cache.clj`,
reachable only via the `.` path) → `clojure.tools.build.api`
(`deps.edn:130,138,148`, an `:test`/`:build`/`:dev-cache` extra-dep).

`classpath-locatable?`'s own docstring claimed "Asking the classpath asks the
process itself. It is a computed fact." That claim was false under any caller
that bound a loader — the recurring class in `AGENTS.md`: a check whose
subject is re-decided by the context it runs in.

### Why the previous JVM (pid 53378) succeeded in 27 s

Nothing about the store reset matters. The outcome is decided by **which
thread forces the delay first**: forced from an ordinary MCP evaluation or a
plain `future`, the test loader is not bound, every `test/` row answers
`false`, and only test namespaces that some earlier run already loaded enter
the ctx through `find-ns`. pid 37572 forced it from inside an in-process
`seon.test/run`. The new in-process workflow (repl-rule, 2026-09-15/16) is
what made the loaded extent the normal first forcer, not any program-row
change.

Secondary observation, not fixed here: `host-namespace!` prefers `find-ns`, so
a test namespace an earlier in-process run happened to load is installed into
the base ctx regardless of the loader. On pid 37572, **81 `-test` namespaces
were already loaded**. Membership therefore still varies with load history —
the same instability the function's own docstring forbids, at a different
door. Filed as an issue rather than widened here.

## The fix (one owner, one line of behavior)

`src/seon/sci/eval.clj`, `classpath-locatable?`: resolve through
`(ClassLoader/getSystemClassLoader)` — the loader that carries **this
process's launch classpath** — instead of the thread's context loader.

**Guarantee:** evaluation-context membership is the set of core-provenanced
program rows *this process's own `-cp` can serve*, and no caller's
thread-context classloader can change it.

**Cost:** none observed. A gate worker is launched `clojure -M:test`, so
`test/`, `script`, `.` **and** the alias extra-deps are on its system
classpath; worker membership is byte-for-byte unchanged. A development JVM is
`-M:dev` and now answers the same inside and outside `with-test-loader`.

**What we give up:** an evaluation context can no longer be widened by a
caller binding a loader. That is the point; a process that should serve more
namespaces gets them on its `-cp`.

Rejected alternatives: excluding rows by name (`-test` suffix) is a naming
convention, banned by AGENTS.md §2.2; carrying every `:test` extra-dep in the
dev JVM enlarges the development classpath to fix a check that was simply
asking the wrong loader.

Verified live before the edit, on pid 37572, by redefining the Var in the host
REPL and probing both loader contexts — identical answers, and
`my.web`/`seon.turn` still `true`.

## Regression

`test/seon/sci/eval_test.clj`,
`process-membership-ignores-a-thread-context-classloader`, beside the existing
`the-context-binds-only-the-graph-this-process-can-serve`. It writes an
unloadable first-party-named source into a temporary directory, adds that
directory to a `DynamicClassLoader`, binds it as the thread context loader
plus `Compiler/LOADER`, and asserts:

1. the bound loader genuinely serves that source (`io/resource` sees it), so
   the test fails loudly if the setup stops reproducing the condition;
2. `classpath-locatable?` still answers `false`;
3. `host-namespace!` returns `nil` rather than throwing
   `::namespace-unloadable`;
4. `find-ns` is still `nil` — nothing was required into the worker JVM.

The probe namespace has no file on any classpath, so the
"own nothing global" fixture property holds by construction.

### In-process proof (default, pid 37572, `seon.test/run` on a daemon thread)

| run | pass | fail | error |
|---|---|---|---|
| new regression, adopted `classpath-locatable?` | 4 | 0 | 0 |
| new regression, with the OLD one-argument `io/resource` redefined in | 0 | 0 | **1** |
| new regression again, definition restored | 4 | 0 | 0 |
| existing `the-context-binds-only-the-graph-this-process-can-serve` | 4 | 0 | 0 |

The middle row is the falsification: against the old implementation the
regression does not merely fail an assertion, it ERRORS — `host-namespace!`
throws `::namespace-unloadable`, the exact production symptom. Run through
`(seon.test/run (#'seon.test/resolve-test 'sym) (seon.operator/connection
"default"))` after `(#'seon.test/with-test-loader #(require
'seon.sci.eval-test :reload))`, per the in-process reload rule.

## Verification boundary

- Live probes and the pre-edit proof: default cluster pid 37572, `jvm` mode.
- `src/seon/sci/eval.clj` adopted via
  `bin/seon init --dev default --changed src/seon/sci/eval.clj`;
  `test/seon/sci/eval_test.clj` adopted by the edit hook
  (publication `4dfcf084-83a4-4a62-b791-d83d9dda89eb`).
- In-process regression run: recorded in the gate-request file.
- **No test JVM was launched and `default` was not restarted.** The batched
  gate is the proof; this lane's iteration is not.
- The shared fixture base on pid 37572 remains poisoned by the earlier
  forcing. This fix stops the class; it does not un-poison that cached delay.
  A fresh JVM, or the orchestrator's deliberate rebuild, is required.
