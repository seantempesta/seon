---
type: landing
status: partial
lane: fix-ns-usages-span-filter
created: 2026-09-24
---

# Lane fix-ns-usages-span-filter (2026-09-24)

clj-kondo attributes every require and `:refer` to the namespace that made it
(`:from`). `seon.fn.analyzer/with-namespace-clauses` threw that answer away
and kept only usages positioned inside the `ns` form's span. The fix reads
requires, aliases and refers by `:from`. Imports and bare `:rename` keywords
carry no `:from`, so they stay limited to the `ns` span, now checked by the
analyzer's one containment rule (`contains-position?`). The binary-search
helper `entries-within` and `entry-position` are deleted.

## Broken or unproven first

- **Not yet run under the new code:** the new regression and 4f880a823's
  regression. Root paused hook publication and test runs (load on the owner's
  machine) before I could run them on the final bytes. Root needs to run:
  - `bin/test-check default --ns seon.fn.analyzer-test` (it must execute 8
    tests. The earlier run executed 7 and passed 35/35, but the new test was
    not published yet.)
  - `bin/test-check default --test seon.sci.branch-execution-test/an-interpreted-caller-resolves-its-compiled-namespace-aliases-privates-and-core`
  - `bin/test-check default --ns seon.fn.analyzer-test --ns seon.sci.branch-execution-test`
    covers both in one request.
- **Publication race:** my first analyzer save published and adopted. The
  second save, the grouped rewrite, was refused with "The source head changed
  before publication" (stale-branch-head) because other lanes were publishing
  at the same time. A later save loaded the grouped code into the JVM: timing
  the Var gave 31 ms. The last docstring-only edit and the `entry-position`
  deletion were saved after publication paused, so the published row for
  `with-namespace-clauses` may still hold the earlier source. Resuming the
  hook, or `start --head`, publishes them.
- **Not caused by this lane:** publication also refused
  "Static program analysis found blocking errors", with unresolved
  `seon.flow/submit!!` in turn.clj:3327, env_test.clj:182 and flow_test.clj:264,
  plus `sut/capacity-observer-proc` in flow_test.clj:332. These come from
  another lane's in-progress seon.flow edit.
- **Residual gap:** kondo records no renamed local, so a `:rename` inside a
  *top-level* `require` is neither indexed nor refused. There are no instances
  in the program.

## Evidence (default JVM, jvm mode, scratch session `fix-ns-usages-span`)

- **kondo attribution, clj-kondo 57252e07:** namespace.clj:721 registers
  requires from the ns form and :771 from a top-level `require`, both with
  `:from` set to the current namespace. `:refer` var usages carry
  `:refer true` and `:ns` set to the current namespace (:220-231).
  `reg-class-usage!` (analysis/java.clj:328-357) has no `:from`, and
  `:import true` comes from the ns form (:702), `reg-imports!`
  (namespace.clj:429-438) for top-level `import`, and deftype/defrecord
  (analyzer.clj:2454).
- **Whole-program census** (`src test script`, 463 namespace definitions, no
  file with two ns forms):
  - Usages outside the ns span: exactly 3, all
    `(require '[seon.effect :as effect])`, in my.edit:31, my.fs:35 and
    my.shell:22.
  - Refers outside the span: 0.
  - Imports outside the span: 12, all unpositioned deftype/defrecord imports
    (for example seon.flow.RefusingBuffer). They stay excluded as before.
    Adding them would change the definition digests of every var in 9
    namespaces, because the namespace context feeds
    `program/definition-digest` (fn.clj:650).
  - `:rename` outside the span: 1, seon.sci.reader:144
    `(:rename options)`, ordinary code. Attributing it by `:from` would
    wrongly refuse the namespace, which is why renames keep the span.
- **Row diff, `namespace-context` for all 463 namespaces before and after:**
  only my.edit, my.fs and my.shell changed, each gaining
  `:aliases {effect seon.effect}` and `:requires #{seon.effect}`. seon.cluster
  (gained seon.error.refusal) and seon.cluster.adoption-exclusion-test (gained
  seon.schema) also differed, but that came from commit d77a9461a editing
  their ns forms between my two analyses, not from this change.
  The grouped rewrite's output `=` the first rewrite's output for all 463
  namespaces.
- **Published rows:** default's database shows `:seon.ns/aliases` with
  `effect → seon.effect` for my.edit, my.fs and my.shell.
- **Cost of clause attachment over the whole-program analysis, 5 runs:**
  - Before (binary search): 9.3–16.5 ms.
  - First rewrite (per-namespace scan): 9,655 ms. Rejected.
  - Final (group once): 32–66 ms, median ≈35 ms.
  - Net ≈ +23 ms against `analyze` ≈ 6.2 s (0.4%), under the 20% / 50 ms bar.
    The JVM was under heavy load from other lanes, so absolute numbers are
    noisy.
- **caller-bindings (sci/eval.clj, from 4f880a823):** redundant now that the
  row carries the alias. Deleted, and its 3 call sites use `row-bindings`
  again. The adopted JVM confirms it is gone from `ns-interns`.

## Changes

- Commit a2bf4d8ed:
  - `src/seon/fn/analyzer.clj`: +23 −34
  - `src/seon/sci/eval.clj`: +3 −16
  - `test/seon/fn/analyzer_test.clj`: +20. New
    `namespace-clauses-are-every-require-clj-kondo-attributes-to-the-namespace`
    checks that a top-level require and refer are clauses, and that a
    `:rename` keyword read in a body is not.
- Commit a209c6d3f: `src/seon/fn.clj` +3 −2, the `namespace-context`
  docstring.
- Net: source −26, test +20.

Released paths: src/seon/fn/analyzer.clj, src/seon/fn.clj,
src/seon/sci/eval.clj, test/seon/fn/analyzer_test.clj, this note.
