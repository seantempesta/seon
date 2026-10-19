---
title: "Runtime review 4 (Opus): HEAD 342e14357"
type: research
status: review, 2026-10-20
created: 2026-10-20
---

# Runtime review 4 (Opus): HEAD `342e14357`

Lane review4-opus (Opus, independent; wrote none of the code under review; did not read the other reviewer's note),
2026-10-20. Question: is it safe for root to restart default onto HEAD? Owner requirement: a branch's change is live
in its whole runtime, exact for every mix of branches and order of evaluation, independent of main's compiled JVM, and
cheap. Inputs read: [branch-runtime](../2026-10-19/branch-runtime.md), [runtime-review](../2026-10-19/runtime-review.md),
[runtime-rereview](../2026-10-19/runtime-rereview.md), [closed-runtime-hybrid](../2026-10-19/closed-runtime-hybrid.md),
[review 3 Opus](runtime-review3-opus.md), [review 3 Astra](runtime-review3-astra.md), [defn-perf](defn-perf.md), and
the commits `959ec1a91`, `fcd9949b5`, `00430af64`, `e9b37328f`, `81ab24b8f`, `3a266a487`, `342e14357`. HEAD is now
`87c686293`, which changes `AGENTS.md` only.

Method. Every probe ran in a throwaway check JVM (`bin/seon check --at SHA`) on a dangling commit (`git commit-tree`
over a private index) of `342e14357` plus one probe file, Astra's review 3 probe tests (from
[the probe patch](runtime-review3-astra-probes.patch)) and, for mutants, one edited `src/seon/sci/eval.clj` or
`src/seon/sci/runtime.clj`. On default only JVM-mode reads and SCI requests as my own agents (`review4-opus-*`,
never root); default was neither restarted nor reloaded. Raw values are in `tmp/runtime-review4/` (gitignored); every
number used is quoted here.

## Verdict: SAFE (default already runs it); NOT the exact runtime yet

- **Default already runs `342e14357`**: `bin/seon status` names source root `data/source/342e14357…`, pid 6516,
  started 2026-10-19T22:43:49Z (35 s after the commit), ready 22,049 ms. Over this review it answered every request,
  heap live 0.98 GB of 4 GB, cache 318.6 of 512 MB, health `:faulted` with the same pre-existing seven open faults,
  seven agent-error signatures and no regressions. A restart onto `87c686293` changes no code.
- Every earlier blocker's reproduction answers as required, except F2 (the stated exception), the predicate gap
  (filed, held) and N3 (latent, SCI's rule). Every fix of review 3 holds under its own reproduction and under
  mutation of its mechanism (8 of 8 mutants red).
- Nothing hung, crashed or ran away: 54 test vars in 17.8 s in one check JVM; no new error class.
- Not exact: the N1 fix is incomplete (B1: F1's own change, read through the health report's problems unit, crosses
  runtimes in both orders), and two smaller residuals were confirmed (B2, B3). None reaches the done rule or merge;
  all are in the N1 scope (compiled code reading the same database value as a branch) or agent-only constructs.
- Cost: the gate passes with 1.8-2.4% headroom; same-run parent ratio 1.30-1.32x, unchanged from review 3.

Root should keep the post-merge run on main and should not relax merge-time checks on the strength of exactness until
B1 and the predicate gap are fixed.

## Blockers to the "exact" claim, ranked (none blocks the restart)

### B1. An open caller handing a derivation leaves its deriver compiled

Filed: [an-open-caller-handing-a-derivation-leaves-its-deriver-compiled](../../../seon/issues/an-open-caller-handing-a-derivation-leaves-its-deriver-compiled.md).

Probe `h1-problems-family-through-derived`. The branch replaces `seon.problems/missing-models` with
`[db] [{:seon.config.ai/model "BRANCH"}]` (F1's change). `(:seon.problems/missing-models (#'seon.health/problems-unit db
60000 nil))` on the same committed branch database value, kinds `:seon.health/derived`, `:seon.health/reads` and
`:seon.health/problems` evicted before each order (check run `f2e341cc7fe2`):

| Order | branch | compiled | expected |
|---|---|---|---|
| branch first | BRANCH | **BRANCH** | BRANCH / nil |
| compiled first | **nil** | nil | BRANCH / nil |

On the branch `seon.health/derived` is compiled (`derived-compiled-on-branch? true`), `seon.health/problems-unit`
analysed; closed: `derived` true, `problems-unit`, `seon.problems/problems` and `seon.problems/families` false.
`problems-unit` is among `derived`'s `::leaders`, but `seon.sci.runtime/compiled-names` counts a leader only when it
is changed or in the closed reach of a change, and an open leader never is. The N1 issue is marked fixed while its own
scope list names this path. Confirmation candidate `76bcf8c5c` (an open `seon.*` leader counts as reaching) answers
BRANCH / nil in both orders and keeps the committed N1 row and `seon.sci.open-derivers-test` green (run
`d0331b6d2a1c`). The direct path `(seon.problems/problems db {})` (the committed F1 row) is exact.

### B2. A branch's new caller of a handed deriver is not its leader

Filed: [a-branchs-new-caller-of-a-handed-deriver-is-not-its-leader](../../../seon/issues/a-branchs-new-caller-of-a-handed-deriver-is-not-its-leader.md).

Probe `h2-new-caller-of-a-compiled-deriver`: an agent's new `rt4.h/f` hands `(fn [_] #{'rt4/branch})` to
`seon.health/derived` under `:seon.health/untested`. Branch first: `[#{rt4/branch} true]` (compiled
`seon.health/untested` serves the branch's set); compiled first: `[false false]`. `seon.sci.eval/coupling` is derived
once from the loaded program in `seon.sci.eval/acquire-program!`, so a caller the branch adds is never a leader. Needs a
derivation name main also uses; lower likelihood than B1.

### B3. Predicates (known)

`seon.sci.eval-test/a-branch-changed-predicate-decides-its-branch-projection` is still the one committed red of the
runtime rows (run `31ca3ba7a255`). Judged below.

## Step 1: earlier blockers re-run on HEAD

One check JVM, `6f04892fe` (HEAD plus probe `seon.runtime-review4-probe-test` and Astra's three review 3 test
namespaces with their fixture): run `31ca3ba7a255`, 54 vars executed, 139 pass, 4 fail, 17,836 ms. Reds: the
predicate row, B1's probe (2 assertions), and Astra's synthetic `cache-reader-and-writer-agree-in-one-runtime`.

| Finding | Form | Expected | HEAD |
|---|---|---|---|
| F1 value holding functions | committed row | BRANCH | green; **through `problems-unit`: B1** |
| F2 host-bound callee | branch `seon.ref/words`, `#'seon.ref/parsed` cites `seon.db/q` | fresh `false` | `true`, words `["BRANCH"]`: the stated exception, unchanged |
| F3 macro (database, MCP) | committed rows | 22 / 33 | green |
| F3 `seon.test-support/evaluate` | agent `defmacro bucket-of` then `(seon.profile/bucket 5)` | 33 | 3, `bucket` compiled: unchanged, test helper never arms |
| F4, F4b, F5 | Astra's `failed-production-does-not-bind-a-partial-runtime` | `[1 1 2]`, snapshot equal | as expected |
| R1 either order, every entrance | committed rows | | green |
| R2 escapes | committed row | | green |
| R3 macro reads a value, two branches | probe `p-two-branches-change-a-macro-value` | `[2 3]` | `[2 3]` |
| R5 launch defonces | committed row (with review 3's second branch) | | green |
| R6/R7 | `seon.sci.runtime-test` (10 vars) | | green |
| N1 (review 3) | probe `n1-callback-crosses-a-compiled-lookup` | branch first `[0 8]`, compiled first `[8 0]` | `[0 8]`, `[8 0]`: fixed |
| N2 values evaluated again | probe `n2-value-census` | proportional | 412 value rows, 8 macros, 4,501 functions; first change 445 ms, 889 analysed, 12 evaluated; continuation 72 ms, 24 evaluated |
| N3 closure under another context | probe `p-lazy-value-escapes-to-another-context` | | own/raw `("(BRANCH BRANCH)")`, under main's context `("(a 1)")`: unchanged, latent |
| Astra 1 projection derivation | `projection-cache-holds-the-executing-program` | `[[] ca]`, `[[] cb]` | as expected |
| Astra 2 multimethod, protocol | real and extra rows, committed `a-method-written-in-an-acquired-fork-is-its-own` | `[2 1 1]` | `[2 1 1]` |
| Astra 3 cache writer/reader (production) | `a-branch-cache-reader-cannot-read-the-entry-it-replaced`, `real-invocation-cache-reader-and-writer`, committed row | `[:branch :branch]` | as expected |
| Astra 3 (synthetic pair) | `cache-reader-and-writer-agree-in-one-runtime` | `[2 2]` | `[2 nil]`, then `[2 1]`: red by design (below) |
| Astra 4 initializer order | `a-loaded-value-initializer-executes-the-whole-branch`, `a-value-executes-its-whole-runtime-during-production` | `[[2 2 2] [3 3 3] [3 3 3] [1 1 1]]` | as expected |
| Astra 5 open macro | `a-loaded-open-macro-executes-the-whole-branch`, `a-loaded-open-macro-reads-its-branches-helper` | `[2 2 2]` | as expected |
| Host dispatch | probe `p-host-dispatch-leak` | JVM unchanged | `print-method` refused (SecurityException), JVM `pr-str` and `datafy` unchanged |

Astra 3's synthetic pair stays red as the resolution of
[its issue](../../../seon/issues/a-branch-cache-writer-and-reader-use-different-keys.md) states: the rule is "one keyer
per kind", held for the program by the census row
`seon.sci.open-derivers-test/a-cache-kind-read-apart-from-its-derivation-has-one-keyer`. That census reads the
database, so it is not a reaching test of an agent's new definitions: an agent's own reader/writer pair of one kind
would answer wrongly on a later branch and is not caught at its merge unless root runs the census. Agent use of
`seon.cache` is rare; noted, not filed.

## Step 2: new failures of the requirement

- **B1, B2** above.
- **Fork writes besides `defmethod`** (probe `fs-fork-shared-writes`, `[fork sibling parent]`, parent from
  `seon.sci.eval/base-ctx`, forks by `seon.sci.eval/fork-cluster-ctx`): `remove-method` **`[0 0 0]`** (expected
  `[0 1 1]`), `prefer-method` **`[:q :q :q]`** (expected `[:q :ambiguous :ambiguous]`): the shared MultiFn is written.
  Filed: [remove-method-and-prefer-method-in-a-fork-write-the-shared-multifn](../../../seon/issues/remove-method-and-prefer-method-in-a-fork-write-the-shared-multifn.md).
  Per context, as required: `derive` `[true false false]` (the multimethod issue's note that `derive` is shared is
  wrong), `alter-var-root` `[2 1 1]`, `alter-meta!` `[2 nil nil]`, `intern` `[2 1 1]`, `ns-unmap` `[false true true]`,
  `alias` `[true false false]`; on a main Var, `alter-meta!` `[true nil nil]` and `alter-var-root` `[:rt4 "(a 1)"
  "(a 1)"]` with the JVM Var unchanged. No `src` multimethod exists, so only agent multimethods are exposed.
- **Two branches, a continuation, a fork of a fork**: exact in every committed row and probe above.
- **Cache eviction**: keys are exact, so eviction costs re-analysis, never a wrong answer; default's cache is at
  318.6 MB of 512 after its restart, so the eviction issue is dormant for now.
- **Boot and adoption**: default booted HEAD in 22,049 ms ready. The cross-lineage cold overlay derive (~65 ms) is the
  accepted decision.
- **Hang, crash, OOM**: none. One of my JVM-mode reads on default (an attribute scan over every entity) allocated
  11.9 GB in 6.2 s and default stayed healthy; that is my query's cost, not a runtime defect.

### The predicate gap: acceptable to restart with

A branch that changes a predicate a schema form names validates with main's predicate until merge. It is not a
regression of default (which already runs it), it does not cross into main's answers, and its task stays red. The
consequence is the same as review 3 stated: a branch's tests judge a changed predicate's contracts with main's
definition, so root's post-merge run on main is the first to see it. Acceptable for the restart, provided root keeps
that run.

## Step 3: do the proofs hold the mechanism?

Each mutant is the probe commit `6f04892fe` with one edit; all were green unmutated in run `31ca3ba7a255`.

| Mutant | Edit | Proofs run | Result |
|---|---|---|---|
| m1 `14efcef33` | `compiled-names` ignores `::leaders` | committed N1 row, probes h1, n1 | **red** (N1 row) |
| m2 `c380b954c` | `(dependency-order due {})` | `a-value-is-produced-after-its-whole-reach`, Astra 4 (both) | **red** (3) |
| m3 `30af92759` | an open macro is not due | `an-open-macro-expands-with-its-runtime`, Astra 5 (both) | **red** (3) |
| m4 `775a72aeb` | `rekeyed?` drops the open-macro clause | `seon.sci.runtime-test`, Astra 5 | **red** (2) |
| m5 `6d6bc8766` | `projection-cache-value` entry unkeyed | committed projection row, Astra 1 | **red** (2) |
| m6 `def19cefe` | no shared analyses at acquisition | first-defn cost row, runtime-test, every-entrance, agent macro row | **red** (cost row only; behavior unchanged, as intended) |
| m7 `8ba694ccb` | an open macro's analysis key holds no changes | `seon.sci.runtime-test`, Astra 5 | **red** (2) |
| m8 `5efbcda4a` | `keyed-entries`' `present` unkeyed | committed writer/reader row, Astra 3 (both) | **red** (3) |

The proofs hold the mechanisms they claim. The one proof gap is B1's: the N1 row covers a closed leader only.

## Step 4: cost, HEAD against `716cc7527`

`seon.cost-estimate-test/measured` once per JVM (probe `seon.runtime-review4-cost-test`; HEAD candidate `cb1d6a000`,
base `a0c406310` = `716cc7527` with review 3's transplanted cost test), run head, base, head, base one after another;
the first head run overlapped one short check. MB allocated / calls:

| Row | `716cc7527` (two runs) | HEAD (two runs) | ratio MB (calls) |
|---|---|---|---|
| `:hub-change-sequence` (gate 5,053 MB) | 3,789 / 641,937; 3,767 / 641,059 | 4,933 / 760,729; 4,964 / 762,498 | **1.30-1.32x** (1.19x) |
| `:hub-behavior-change` | 895 / 198,755; 890 / 198,571 | 1,764 / 278,882; 1,771 / 279,472 | 1.97-1.99x (1.40-1.41x), accepted overrun |
| `:redefine-one-form` | 330 / 50,177; 330 / 50,153 | 413 / 57,771; 414 / 58,024 | 1.25-1.26x (1.15-1.16x) |
| `:new-def` | 91 / 19,489; 91 / 19,482 | 116 / 21,316; 117 / 21,290 | 1.28-1.29x (1.09x) |
| `:fresh-agent-first-request` | 54 / 7,626; 54 / 7,618 | 66 / 8,308; 66 / 8,330 | 1.22-1.23x (1.09x) |
| `:unchanged-request` | 18 / 6,587; 18 / 6,579 | 21 / 7,238; 21 / 7,260 | 1.15-1.17x (1.10x) |
| `:similar-by-name-warm` | 27 / 8,006; 26 / 7,998 | 31 / 8,946; 31 / 8,970 | 1.15-1.17x (1.12x) |
| `:reuse-one-unchanged-test` | 30 / 8,021; 30 / 7,992 | 33 / 8,793; 33 / 8,827 | 1.12x (1.10x) |
| `:current-state-with-task` | 217 / 38,160; 216 / 38,142 | 224 / 40,825; 254 / 40,858 | 1.03-1.18x (1.07x) |
| `:unresolved-symbol-request` | 33 / 14,566; 33 / 14,549 | 36 / 15,437; 36 / 15,470 | 1.09-1.10x (1.06x) |
| `:merge-one-function` | 124 / 19,892; 122 / 19,802 | 130 / 21,691; 130 / 21,899 | 1.04-1.06x (1.09-1.11x) |
| `:hub-docstring-change` | 263 / 58,897; 262 / 58,904 | 268 / 60,505; 266 / 60,639 | 1.01-1.02x (1.03x) |
| `:seon-db-nine-forms` | 990 / 107,083; 986 / 106,844 | 1,005 / 114,341; 1,005 / 114,407 | 1.01-1.02x (1.07x) |
| `:cold-stale-merge-members` | 4,373 / 823,882; 4,356 | 4,373 / 858,381; 4,358 | 1.00x (1.04x) |
| `:unmap-one-def` | 103 / 18,684; 104 / 18,668 | 101 / 20,418; 100 / 20,395 | 0.96-0.97x (1.09x) |
| `:first-defn-on-a-branch` | (no row) | 267 / 22,847; 268 / 22,966 | accepted overrun 266.8 MB / 22,014 |

- The gate passes on its recorded denominator with **2.4% and 1.8% headroom**; against the parent measured in the
  same runs it is 1.30-1.32x, past the 1.3x bar in the second run, calls 1.19x. Against review 3's HEAD (4,909-4,916 MB)
  the sequence moved +0.5-1.0%, so the fixes since review 3 cost about what they saved. The gate still compares with a
  recorded number, not a same-run parent.
- Ordinary requests on a changed runtime cost +6-12% calls and +12-29% allocation against `716cc7527`, the same band as
  review 3 (`:unchanged-request` 7,007-7,177 calls then, 7,238-7,260 now).

### On default, as my own agents (sentinel probes)

| Probe | Now (default `342e14357`) | defn-perf |
|---|---|---|
| warm `(+ 1 2)`, five after two warmups | 57.9 ms median (54.2-60.1), 7,116 calls, 26.7-28.7 MB | default warm 51.8/71.1/55.4 ms |
| fresh agent `(+ 1 2)`, three agents | 168.8 / 184.2 / 178.4 ms, 7,063 calls, 54 MB | second/third fresh 168/135 ms (check JVM) |
| first defn `(defn sentinel-probe-inc [x] (inc x))` | **332.3 ms**, 18,274 calls, 194.6 MB | root's 1,511 ms / 257,109 calls before; live clone 219 ms / 18,347 |
| live-run step: fresh agent redefines `seon.ref/first-sentence` (adds `"cf."`) | **1,059 ms**, 72,732 calls, 449 MB, 327 reads; stages settle 221, custody 175, test-step 168, evaluate 153, apply 120, derive 106 | |
| its continuation (adds `"viz."`) | 295 ms, 27,574 calls, 184 MB | |
| plain request on that changed branch | 68.7-70.5 ms, 7,116 calls, 30.4-31.6 MB | |

The branch change is live through a compiled-in-main caller (`(seon.ref/docstring "See viz. Smith. Then more.")`
answers `"See viz. Smith."` on the branch and `"See viz."` in main's JVM). The first src change on a fresh branch is
over one second (1,059 ms); it is carried by `seon.repl/the-first-requests-on-a-new-agent-branch-are-sub-second`. The
done rule and merge were not exercised end to end (they need root to file and release a task).

## Changed paths

- `docs/research/agent-platform/2026-10-20/runtime-review4-opus.md` (this note)
- `docs/seon/issues/an-open-caller-handing-a-derivation-leaves-its-deriver-compiled.md` (B1)
- `docs/seon/issues/a-branchs-new-caller-of-a-handed-deriver-is-not-its-leader.md` (B2)
- `docs/seon/issues/remove-method-and-prefer-method-in-a-fork-write-the-shared-multifn.md`

## Dangling commits (`342e14357`, `10928dd49` for the cost base, plus the named files)

| Use | Commit |
|---|---|
| probe 1 (all probes, Astra's review 3 tests, committed rows) | `6f04892fe` |
| B1 confirmation (open leaders count as reaching) | `76bcf8c5c` |
| mutants m1-m8 | `14efcef33`, `c380b954c`, `30af92759`, `775a72aeb`, `6d6bc8766`, `def19cefe`, `8ba694ccb`, `5efbcda4a` |
| cost probe HEAD / base | `cb1d6a000` / `a0c406310` |

Agents left on default (my branches, not retired; root's `my.agent/retire-branch!` removes them): `review4-opus-warm`,
`review4-opus-fresh-0`..`2`, `review4-opus-defn`, `review4-opus-live`.
