---
type: research
status: active
tags: [testing, destructive, store, repl, in-process]
---

# An in-process run refuses a destructive drill (2026-09-17)

The in-process half of
[a-platform-tier-test-wiped-the-checkouts-store](../../../seon/issues/a-platform-tier-test-wiped-the-checkouts-store.md).
`ccccea806` made a recursive deletion unconstructable without a declared
absolute root; the platform-tier checker
([platform-tier-no-destructive-drill](platform-tier-no-destructive-drill-2026-09-17.md))
keeps the cold tier that runs FIRST free of destructive drills. What remained
was the seam the incident actually travelled: an IN-PROCESS `seon.test/run`,
inside the development JVM, of a test that reaches a function which deletes a
filesystem path it did not create. That rule lived only in prose
(`tmp/orchestrator/wave2/repl-rule.txt`, "those are cold-only"). It is now a
check.

## 1. The one seam

`seon.test/check` does not run tests itself: `check-in-process`
(`src/seon/test.clj:496`) selects, then calls `seon.test/run` once per
selected Var; `my.test/check` delegates to `seon.test/check` and the
`my.test/run` macro expands to `seon.test/run`. **`seon.test/run` is the one
seam every in-process execution passes through**, and the refusal lives there.
`check` additionally EXCLUDES the same tests from its selection, so a check
reports the exclusion with its evidence instead of turning N tests red one
refusal at a time.

## 2. The decision

Two values, both held by the caller, never fetched at the seam:

* **the declared operator root.** `bin/seon [--root PATH]` declares on every
  child JVM the root it was launched to operate (`bin/seon:5,12`,
  `script/seon/fresh_operator.clj:311`), a `bin/test` worker declares its own
  isolated run root (`src/seon/test/runner.clj:2549`), and `bin/test-fast`
  declares none. `seon.test/development-root` answers the canonical root ONLY
  when the declaration is the working directory this JVM runs in — the
  developer's checkout with its live `data/store`. Everything else (an
  isolated run root, a lane's `tmp/<lane>-root`, no declaration) is nil and is
  never refused. `run`'s options may carry `:seon.test/declared-root`, the
  declaration a caller genuinely holds; absent, this JVM's own is read once at
  the entry, exactly as `seon.cluster.store/create-store!` reads it before
  handing it to `admit-destructive-path!`. `check` reads it once and hands it
  to every run it starts — a first draft re-read the property inside `run` and
  refused a test its own check had admitted, which is the "no seam may act on
  a pre-read its authority will re-decide" law in miniature; the live check
  regression caught it.
* **the reach.** ONE owner set: `seon.test.runner/destructive-owners` (now
  public, same declaration and docstring, read by both halves), and the shared
  `:seon.fn/calls` derivation `seon.fn/tests-reaching` over the database's
  program graph. No second list and no name matching. An owner with no program
  row REFUSES (`:seon.test/unknown` naming the owner) instead of walking to
  nothing and admitting every test: the absence-of-signal class this whole
  issue is about.

The refusal is an `seon.error/diagnostic` of kind
`:seon.test/destructive-in-process` carrying `:seon.test/sym`, the
`:seon.fn/sym` it reaches, the shortest declared call path
(`:seon.test/destructive-path`), and `:seon.test/command` — the cold
invocation that may run it. Nothing executes and nothing is committed: the
refusal is returned before `bounded-result`.

## 3. Measured, live (pid 38993, adopted `:current-src` 6aaa83a4)

Against the development cluster's real program graph:

* `destructive-reach` resolves all three owners and answers **55 tests** —
  the same 55 the cold checker measured over the manifest (47 through
  `populate-published-root!`, 5 through `cleanup-root-under-lock!`, 3 through
  `populate-published-operator-root!`), in 5-13 ms.
* Refusal for
  `seon.test-support-test/simultaneous-fixture-bases-never-open-the-published-store`
  under declared root `/Users/sean/src/seon`:

  > … reaches `seon.test-support/populate-published-operator-root!`, which
  > deletes a filesystem path it did not create, and this JVM was launched to
  > operate the development root /Users/sean/src/seon. Run it cold — bin/test
  > -- seon.test-support-test — or in a JVM under an isolated operator root …

* The same test under declared root
  `/Users/sean/src/seon/tmp/lane-root` → `nil` (admitted);
  `seon.id-test/identity-is-content-addressed` under the development root →
  `nil`.
* Multi-hop paths are derived, not asserted, e.g.
  `seon.background-blob-test/background-binary-results-remain-exact-across-the-inline-threshold
  -> seon.background-blob-test/with-file-effect-store
  -> seon.test-support/with-published-file-database
  -> seon.test-support/populate-published-root!`.

## 4. Regressions (canonical harness, `seon.test-reaching-test`)

A probe deftest is interned into a throwaway namespace, its indexed row
declares `:seon.fn/calls` to `seon.test-support/populate-published-root!`, and
its body creates a marker directory — so an execution that should have been
refused leaves evidence on disk rather than passing silently.

| regression | claim |
|---|---|
| `a-development-root-is-the-declared-root-this-jvm-operates` | the working directory and `"."` are the development root; nil, `""` and an isolated root are not |
| `an-in-process-run-under-a-development-root-refuses-a-destructive-test` | typed refusal naming test, owner, call path and cold command; the marker directory was never created; no `:seon.test/run` and no counts were committed |
| `an-in-process-run-under-an-isolated-root-runs-the-same-test` | the same Var runs, passes and records its result |
| `an-in-process-check-excludes-a-destructive-test-and-reports-it` | `check` runs only the cheap test, reports `:seon.test/destructive-excluded` with owner/path/command, says `destructive-excluded 1` in its feedback, creates no marker, records no result — and the same check under an isolated root runs it |
| `a-destructive-owner-without-a-program-row-refuses-instead-of-admitting` | retracting an owner's `:seon.fn/sym` makes the reach unanswerable, and an unanswerable reach refuses the run |

In-process results on pid 38993, one test at a time on a daemon thread with
`{:seon.test.run/provenance (seon.test.runner/provenance (seon.db/db c))
  :seon.test/remaining-ms 100000}`:

| regression | result |
|---|---|
| `a-development-root-is-the-declared-root-this-jvm-operates` | 5 / 0 / 0 |
| `an-in-process-run-under-a-development-root-refuses-a-destructive-test` | 10 / 0 / 0 |
| `an-in-process-run-under-an-isolated-root-runs-the-same-test` | 5 / 0 / 0 |
| `an-in-process-check-excludes-a-destructive-test-and-reports-it` | 10 / 0 / 0 |
| `a-destructive-owner-without-a-program-row-refuses-instead-of-admitting` | 4 / 0 / 0 |

(pass / fail / error; the first four re-run after the final adoption
6aaa83a4, the last on 6aaa8289 and unaffected by that hunk.)

The intermediate red is the evidence for §2's second paragraph: before the
declaration travelled from `check` into each `run`, this ran

```
an-in-process-check-excludes-a-destructive-test-and-reports-it  8 / 2 / 0
  expected: (= [test-symbol] (:seon.test/passed isolated))
  actual:   (not (= ["destructive.probe…/probe"] []))
```

because `run` re-read the JVM property and refused what the check had already
admitted under the isolated root it was handed.

## 5. Not done here, and why

* **Undeclared JVMs are not refused.** `bin/test-fast` declares no operator
  root, so `development-root` answers nil and the check never fires there. It
  is the same value the deletion admission uses, and an undeclared JVM already
  cannot spell the checkout's `data/store` since `ccccea806`. Refusing every
  undeclared JVM would refuse `bin/test-fast` itself.
* **A test Var with no program row is admitted.** Its reach is unknown because
  nothing indexed it — the probe Vars that `seon.test-expiry-test` and
  `seon.test-reaching-test` intern are exactly this shape, and refusing them
  would refuse the whole in-process regression style. The incident's test was
  an ordinary indexed `deftest`, which this check does cover. The cold gate's
  checker has the same property.
* **`:seon.test/declared-root` is a caller's declaration, not a bypass door.**
  An agent that wants to run a destructive drill in process can also call
  `clojure.test/test-var` directly; this check catches honest mistakes
  (AGENTS.md §6), and the value-passing is what lets `check` and `run` agree.
* No protected file was edited (`src/seon/operator.clj`,
  `src/seon/cluster/store.clj`, `src/seon/cluster.clj`,
  `test/seon/test_support.clj`). In `src/seon/test/runner.clj` only
  `destructive-owners` changed: `^:private` removed, two docstring lines added.

## 6. Verification boundary

In-process on pid 38993 only, plus clj-kondo clean on the three changed
source files. No test JVM was launched and no cold gate was run by this lane:
the gate request is `tmp/orchestrator/gate-requests/in-process-destructive.txt`
(`seon.test-reaching-test`, `seon.test.runner-test`, `seon.test-runner-test`,
plus `--platform`). Nothing outside those namespaces was exercised; the 55
genuinely destructive tests were deliberately NOT run in process — that is the
incident this change exists to prevent.
