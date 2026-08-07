---
type: research
status: complete
tags: [research, runtime, sci]
---

# Phase 1 W3 — the call-preparation hook lands in the maintained fork

Read end to end for this lane:
[seon-env-phase1-specs-2026-08-07.md](../plan/seon-env-phase1-specs-2026-08-07.md)
(section W3 is the contract),
[seon-env-prd-2026-08-07.md](../plan/seon-env-prd-2026-08-07.md),
[env-phase0-runtime-ctx-hook-2026-08-07.md](env-phase0-runtime-ctx-hook-2026-08-07.md),
and [sci-built-in-call-observer-is-read-from-the-analysis-context.md](../../../seon/issues/sci-built-in-call-observer-is-read-from-the-analysis-context.md).

## Result

Branch **`seon-env-hook`** in `reference-code/sci`, branched from the current
pin `2db3358c`, head **`f934044d94814e85867187f75ba13b90927c6db4`**. Three
commits, one concern each. **The superproject pin is deliberately NOT bumped
and NOT staged** — `git -C . status` shows `reference-code/sci` as modified
and unstaged, for the orchestrator to review and bump.

The probe branch `seon-env-hook-probe` (`a072c8e`) is superseded; its ~30
lines were reauthored rather than cherry-picked, because the review below
changed the node ordering and the observer read.

| commit | subject |
|---|---|
| `af8a5fb` | Read the built-in call observer from the runtime ctx |
| `40fcaab` | Add `:call-preparation-hook`, read from the runtime ctx |
| `f934044` | Refuse unknown option keys in `sci/init` and `sci/merge-opts` |

Diff against the pin: 6 files, +262 / −19.

```
 src/sci/core.cljc                       |  13 +-
 src/sci/impl/analyzer.cljc              |  40 +++--
 src/sci/impl/opts.cljc                  |  63 ++++++--
 test/sci/call_preparation_hook_test.clj | 108 ++++++++++++++
 test/sci/core_test.cljc                 |  32 +++-
 test/sci/interop_test.cljc              |  25 +++
```

## What the review of the probe's ~30 lines changed

1. **The probe silently disabled `:built-in-call-observer`.** It chained its
   node OUTSIDE the observation node and, when a hook was installed, evaluated
   the children and applied the callee itself — never reaching the inner node,
   so the observer was not notified for any Var callee. Fixed by ordering: the
   hook node is built first and the observation node wraps it, so both fire.
   This is the reason to reauthor rather than cherry-pick.
2. **The observer's analysis-ctx read is fixed in the same file.** The fully
   qualified symbol still resolves at analysis time — it is a property of the
   callee — but the observer is read from the node's runtime `ctx`. The
   generation condition changes accordingly: the observation node is generated
   whenever the callee is a built-in Var, not only when an observer happened to
   be installed at analysis time. Without that, a node analyzed under a
   context with no observer would stay permanently unobservable in every fork.
3. **`(fn [c#] ...)` over `#(...)`**, `if-let`/`when-let` over `(let [x] (if x`,
   an explicit `inner#` binding instead of relying on `let`'s
   shadow-the-previous-binding behaviour — the surrounding macro's own style.
4. **Docstring** for the new option in `sci/core`'s option list, alongside the
   other observers, plus a sentence added to `:built-in-call-observer` stating
   the runtime-ctx read (it is now part of that option's contract).
5. The Ctx record field placement, `merge-opts` carry-forward, and the
   `(and (nil? wrap) (utils/var? f))` scoping from the probe were correct and
   are unchanged.

## The `:interrupt-fn` half of the observer issue does not apply

The issue records that `:interrupt-fn` at `fns.cljc:40,64,152` "has the same
analysis-time-capture shape and belongs in the same fix". **It does not, and
it is not fixed here.** `fns/fun` is called from inside the fn-node's body
(`analyzer.cljc:368-370,400,417-419`), so its `ctx` is the RUNTIME context at
the moment the `fn` form is evaluated — the fn object's creation context, not
an analysis context. An interpreted fn is later invoked as a plain `IFn` with
no ctx argument, so there is no other context available at invocation to read
instead. This is sci's load-bearing "the ctx travels with the code" design and
the same mechanism behind Phase 0 finding 2 (an interpreted fn created against
the base ctx pins the base environment). Changing it would mean threading a
caller ctx into every interpreted invocation, which is a different and much
larger design question. Recorded on the issue.

## The unknown-option refusal found two live defects

`sci/init` and `sci/merge-opts` now name unsupported keys instead of dropping
them. Running the fork's suite with the refusal in place immediately turned up
two option keys that were being silently ignored in the fork's own tests:

- `:disable-arity-checks` — removed from sci years ago (`CHANGELOG.md:472`).
  `disable-arity-checks-test` passed it and asserted the arity error happened
  anyway. It now asserts the removed option is refused, plus that arity checks
  hold without it.
- `:read-cond :allow` passed to `sci/eval-string` in `try-catch-test`.
  `eval-string` threads its options to `opts/init` only, never to the reader
  (`interpreter.cljc:111-116`), so the key did nothing. Removed from the test;
  the adjacent `:features #{:clj}` is what actually drove the reader
  conditional, and the test still passes.

This is the "quiet wrong answer" the Phase 0 report flagged, and both
instances were inside sci itself.

`merge-option-keys` is `init-option-keys` less `:env` and `:proxy-fn`, the two
keys `merge-opts` genuinely does not read.

## Suite results

**JVM — `script/test/jvm`, the fork's gate: GREEN.**
393 tests / 1470 assertions / 0 failures / 0 errors, on both Clojure 1.10.3
and 1.11.1. Baseline at the pin was 386 / 1443; the 7 added tests are the 6 in
`sci.call-preparation-hook-test`, `built-in-call-observer-runtime-ctx-test`,
and `unsupported-option-test` (two upstream tests were edited, not added).

**CLJS — `script/test/node`: unchanged from the pin.**
`:optimizations :none` gives 412 tests / 5727 assertions / 4 failures / 1
error. The pin, run from a separate clone in `tmp/w3-sci-pin` on the same
machine, gives 411 / 5722 / 4 failures / 1 error — the same five by name
(`async-fn-letfn-test`, `async-fn-integration-test`,
`built-in-call-observer-test`, `effective-namespace-bindings-test`,
`effective-namespace-bindings-distinguish-load-from-as-alias-test`). No new
CLJS breakage; the script's `set -e` stops the chain after `:none`, at the pin
as well, so `:advanced` and jit-off were not exercised on either side.

Note for the fork: **`sci.interop-test/built-in-call-observer-test` already
fails on CLJS at the pin.** Our own `:built-in-call-observer` feature is
JVM-only in practice, because on CLJS a Var is reached through the var-deref
path where `return-call` never sees a Var callee. The new hook has the same
boundary, which is why `sci.call-preparation-hook-test` is a `.clj` (its
docstring says so) and the new observer regression is under `#?(:clj …)`.
Worth a separate fork issue; it is pre-existing and out of this lane's scope.

## Falsifier results against the new commits

Run from `tmp/env-probes/` (working copies of the committed
`research/env-phase0-probes/` files), `clojure -M:dev`, against branch head
`f934044`:

| probe | result |
|---|---|
| `runtime_ctx_hook_probe.clj` | `:probe/verdict :minimal-edit-viable`. Declared-and-absent filling, caller-wins, nested and interpreted-`defn` call sites, undeclared leaf untouched, `{:calls 320, :all-correct? true, :mismatches []}` across 8 forks on virtual threads, `:unavailable-short-circuits {:flat-error? true, :callee-not-entered? true}`, base-created-closure pinning reconfirmed. |
| `baseline_call_timing.clj` | runs; 10–11 ns/call. |
| `no_edit_hook_probe.clj` | **cannot run unmodified, by design.** Three of its four shortfall checks pass invented option keys (`:seon/environment`, `:seon/wrap`, `:seon/call-preparation-hook`) to `sci/init` to demonstrate that they are silently dropped. `sci/init` now refuses them, so the probe throws at its first such call. That is the fix landing, not a regression — the probe's own "Reported friction" section asked for exactly this refusal. It remains a correct falsifier against the PIN, which is what it was written for. Graduating or retiring it belongs with W1's probe graduation, not here; nothing in `research/env-phase0-probes/` was edited by this lane. |

Seon itself was checked against the branch: `seon.sci.eval/build-base-ctx`
builds, the ctx carries the `:call-preparation-hook` field, and
`(+ 1 2)` evaluates. Seon passes only known keys to `sci/init`
(`src/seon/sci/eval.clj:186-191`; `::kernel/guard` is destructured out first),
so the refusal costs no first-party change.

## Measurements — honest caveats

Machine was loaded with sibling lanes throughout; absolute numbers run high
against the Phase 0 report's (7 ns baseline there, 10–12 here) and run-to-run
variance is large. Read orderings, not values.

Hook consultation, same session (`runtime_ctx_hook_probe`, 2M iterations):

| path | ns/call |
|---|---|
| wrapping node present, no hook installed | 12 |
| hook installed, empty plan | 115 |
| hook installed, one argument prepared | 396 |

Confirms PRD ruling 8's premise: the cost is consulting the hook at all, and
it is a per-call tax once a hook is installed cluster-wide. Plan-gating stays
queued for Phase 3 / S1.

Observer wrapper cost — committed as
`env-phase1-w3-probes/w3_builtin_observer_cost.clj` (its docstring carries the
paired-run command), three paired runs, pin clone vs branch, same session:

| site | pin | branch |
|---|---|---|
| `(gensym "x")`, no observer | 34 / 37 / 49 | 41 / 57 / 38 |
| `(gensym "x")`, observer installed | 54 / 51 / 46 | 48 / 39 / 49 |
| `(+ 1 2)`, no observer | 5 / 5 / 6 | 6 / 8 / 7 |

The always-generated observation node is inside run-to-run variance on the
`gensym` site and shows roughly +2 ns on the specialized `(+ 1 2)` site. For
Seon specifically it is cost-neutral: `seon.sci.kernel/context-options` always
installs an observer, so the wrapping node already existed on every Seon call
site; only the closed-over local became a ctx field read.

## Ugly output found

- `sci/init`'s silent drop, now fixed — the whole point of commit `f934044`.
- The refusal's own face is fine (`Unsupported option passed to sci/init:
  [:seon/environment]`, `ex-data` carrying `:unsupported-options` and
  `:supported-options`), but the supported-options vector is 20 keys long and
  prints as one unbroken line. Acceptable at a host boundary; if this value
  ever reaches an agent it needs a `:seon.render/ai` producer.
- Carried forward from Phase 0 and still true: `sci/new-var` without an `:ns`
  yields an unqualified `toSymbol`, so an identity-keyed hook plan silently
  misses, and the resulting arity error names the host function rather than
  the sci Var or the call site.

## What this lane did not do

- No superproject pin bump, staged or committed (verified with
  `git -C /Users/sean/src/seon status`).
- No plan-gating machinery (PRD ruling 8: simple hook form only).
- No provider or `seon.env` production code — W1/W2 own those.
- No edits to `research/env-phase0-probes/` — W1 owns probe graduation.
- No `:interrupt-fn` change in `fns.cljc`; see the section above for why.
