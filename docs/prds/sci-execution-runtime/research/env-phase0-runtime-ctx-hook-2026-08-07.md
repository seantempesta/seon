---
type: research
status: complete
tags: [research, runtime, sci]
---

# Phase 0(b) — the runtime-ctx call-preparation hook, falsified live

Read end to end before any work here:
[seon-env-prd-2026-08-07.md](../plan/seon-env-prd-2026-08-07.md) (the sealed
design), [environment-mechanism-sci-2026-08-07.md](environment-mechanism-sci-2026-08-07.md)
(the grounding), and the "Recommended seam" + "Choice 1" sections of
[ambient-injection-prd-2026-08-05-r2-draft.md](../plan/ambient-injection-prd-2026-08-05-r2-draft.md).
All three were read in full for this lane.

## Verdict

**Minimal-edit viable.** The existing SCI machinery at pin `2db3358` cannot
deliver runtime-ctx-aware argument preparation for a host `copy-var` function —
falsified by probe, four independent shortfalls with file:line below. One
narrow, optional hook (three touched files, ~30 lines) makes the whole P17
contract work: declared-and-absent arguments are filled from the runtime ctx's
environment at call time, per fork, correct under concurrency, with the fork's
own suite green (386 tests / 1443 assertions / 0 failures).

The change lives on branch **`seon-env-hook-probe`** in `reference-code/sci`,
commit `a072c8e`, **deliberately unpinned in the superproject** — `git status`
shows `reference-code/sci` as modified-and-uncommitted and it must stay that
way until the design lands. (The requested branch name `seon/env-hook-probe`
is unusable: a branch named `seon` already exists in that repository, so git
refuses the `seon/` ref namespace.)

Two things the probes surfaced that the design must absorb, neither fatal:

1. **A ctx key cannot ride the init options map.** `sci.impl.opts/init`
   destructures a fixed key set and silently drops everything else
   (`opts.cljc:250-269`), so `:seon/environment` must be `assoc`'d onto the
   constructed ctx. Passing it as an option yields a ctx with no environment
   and no error.
2. **An interpreted fn created against the BASE ctx pins the BASE
   environment.** Probed: `(defn program-fn ...)` evaluated in the base ctx and
   then called from a fork prepares arguments from the base environment, not
   the fork's, because `fns/fun` closure-captures its creation ctx
   (`fns.cljc:53,78,167`). Re-evaluating the same `defn` inside the fork fixes
   it. Program-graph functions must therefore be installed as **host Vars**
   (where the hook fires with the caller's runtime ctx) or **re-created in the
   fork that runs them** — never pre-evaluated once into the shared base as
   interpreted fns.

## The hook contract validated

```clojure
(hook runtime-ctx var evaluated-args) -> prepared-args | (reduced result)
```

- **Where it fires.** Inside the generated call node's body, so on the thread
  performing the call, once per call — `analyzer.cljc:1751-1772` on the branch.
- **What it sees.** `runtime-ctx` is the node's `ctx` argument, i.e. the fork
  actually executing (`types.cljc:264-273`); `var` is the resolved
  `sci.lang.Var`, whose `sci.impl.vars/toSymbol` is the provable
  program-function identity (probed: `my/read-rows`, and only when the Var was
  created with an `:ns` — a bare `sci/new-var` yields an unqualified symbol);
  `evaluated-args` is a vector of the already-evaluated arguments.
- **What it returns.** The vector of arguments actually applied, or a `reduced`
  value returned as the call's result without entering the callee — the flat
  `:seon.ambient/unavailable` error path, verified not to enter the body.
- **What it cannot see.** The unevaluated argument forms, the analysis ctx, the
  binding array (deliberately not exposed), and anything about a callee that is
  not a Var.
- **Where it does NOT fire.** Computed callees (`((add 1) 2)`), self-recursive
  calls, CLJS var-deref calls, and binding-position calls — every path where
  `return-call` already receives a non-nil `wrap`. Scoping to
  `(and (nil? wrap) (utils/var? f))` keeps the existing `wrap` semantics
  untouched and matches r2's "provable program identity" boundary exactly.
- **Per-fork correctness.** The hook value is read from the runtime ctx inside
  the node body, never lifted at analysis time. This is the corrective to
  `:built-in-call-observer` (`analyzer.cljc:1719`), now filed as
  [an issue](../../../seon/issues/sci-built-in-call-observer-is-read-from-the-analysis-context.md).

## Why no-edit is not viable — four shortfalls, probed

Probe A (`tmp/env-probes/no_edit_hook_probe.clj`) run against the **pinned**
fork `2db3358` returns `:probe/verdict :no-edit-not-viable`.

1. **`:built-in-call-observer` never sees a host leaf.**
   `built-in-call-symbol` returns nil unless the Var carries `:sci/built-in`
   meta (`analyzer.cljc:62-65`), which `copy-var`/`new-var` leaves do not.
   Probed: `observer-saw` is empty while `(my/read-rows :caller-db :q)` runs.
2. **Its return value is discarded.** The notifying node calls
   `(observer# sym)` for effect and then evaluates the original call
   (`analyzer.cljc:1745-1750`). It is one-arg — no ctx, no callable, no args —
   so it cannot reshape anything.
3. **`wrap` is unreachable.** It is the sixth positional parameter of the
   analyzer-internal `return-call` (`analyzer.cljc:1718`), not an option;
   probed `(contains? (set (keys (opts/init {}))) :wrap)` is false, and invented
   ctx keys are ignored. On the JVM the direct-Var and direct-fn paths pass
   `nil` (`analyzer.cljc:2086-2090,2101-2104,2114-2121`) — inert exactly where
   capability leaves are called.
4. **`wrap` replaces the callee, it does not reshape arguments.** The
   generated arities evaluate arguments inline and apply them positionally
   (`analyzer.cljc:1727-1741`), so even a reachable `wrap` cannot add a missing
   one. And a `copy-var` host function receives no ctx at all
   (`analyzer.cljc:1717-1744`, `evaluator.cljc:398-420`).

The one no-edit workaround that does work — installing each leaf per fork as a
closure over that fork's environment — was measured (~30 µs per ctx
construction) and rejected in the report's findings: the leaf's arity must be
authored *without* the supplied default argument, nothing prepares a declared-and-absent
argument for a shared Var, and every fork must re-intern every capability leaf.

## The edit

Three files on `seon-env-hook-probe`:

- `src/sci/impl/opts.cljc` — `call-preparation-hook` added as a **Ctx record
  field** (not an extmap key: the comment at `opts.cljc:203-206` is explicit
  that non-field `assoc` rebuilds the extmap), threaded through `->ctx`,
  `init`, and `merge-opts`.
- `src/sci/impl/analyzer.cljc` — in `gen-return-call`, when `wrap` is nil and
  the callee is a Var, the generated node is wrapped by one that reads
  `(:call-preparation-hook ctx)` from the **runtime** ctx; if absent it
  delegates to the original node, if present it evaluates the children,
  consults the hook, and applies. The existing `catch-clause` is reused so
  stack/location behaviour is unchanged.
- `src/sci/core.cljc` — the option documented alongside the other observers.

## Measurements (rough, single JVM, 2M iterations, not a benchmark)

| path | ns/call |
|---|---|
| pinned fork `2db3358`, direct host-Var call | 7 |
| branch, wrapping node present, **no hook installed** | 9 |
| branch, hook installed, **empty plan** (returns args unchanged) | 80 |
| branch, hook installed, one argument prepared | 299 |

Read: the wrapping node itself is ~free (2 ns). The cost is **consulting the
hook at all** — a vector allocation over the children plus the hook call plus
`apply`, ~70 ns on every Var call once a hook is installed, whether or not that
call site has anything to prepare. With a hook installed cluster-wide that is a
tax on every single agent call.

**Design consequence for Phase 1, not a blocker:** the *plan* (which Var, which
arity, which argument index, which environment key) is derived from the program
graph and is identical across forks of one cluster; only the *environment read*
must be per-fork. So the hook should be consulted only at call sites that have
a plan. Whether that gating is done at analysis time (zero cost at unplanned
sites, but reintroduces an analysis-ctx read for the plan — acceptable only if
the plan is genuinely cluster-scoped and forks cannot change it) or by a
cheaper runtime pre-check keyed on the Var is an implementation decision for
the hook's landing, and it should be measured against the 7 ns baseline above.
The 299 ns "prepared" figure is dominated by the probe's naive map lookup and
`subvec`/`concat`, not by the seam.

## Probe inventory

Committed under `docs/prds/sci-execution-runtime/research/env-phase0-probes/`
(they are evidence, and `tmp/` is gitignored); each exposes a `run` returning
`{:probe/verdict …}` data. They were executed from a working copy in
`tmp/env-probes/`, which is where a rerun should put them.

| file | what it falsifies | verdict |
|---|---|---|
| `no_edit_hook_probe.clj` | can existing `wrap`/observer machinery prepare arguments from the runtime ctx? Run against the **pinned** fork. | `:no-edit-not-viable`, four shortfalls with file:line |
| `runtime_ctx_hook_probe.clj` | the minimal hook: declared-and-absent filling, caller-wins, nested and interpreted-`defn` call sites, 320 concurrent virtual-thread calls across 8 forks, unavailable short-circuit, timing, base-created-closure pinning | `:minimal-edit-viable`, all correct, zero mismatches |
| `baseline_call_timing.clj` | ns/call for one direct host-Var call; run on both the pinned pin and the branch to price the wrapping node | 7 ns pinned / 9 ns branch |

Reproduce:

```bash
clojure -M:dev -e "(require 'clojure.pprint) \
  (load-file \"tmp/env-probes/runtime_ctx_hook_probe.clj\") \
  (clojure.pprint/pprint (runtime-ctx-hook-probe/run))"
```

Selected results, verbatim from the second probe:

- `:fills-declared-argument` — elided call returns `#:read{:from :db-alpha}`;
  explicit call returns `#:read{:from :explicit-db}`; nested call and a call
  through an interpreted `defn` both resolve `:db-alpha`; the undeclared leaf
  is untouched.
- `:per-fork-under-concurrency` — `{:calls 320, :all-correct? true,
  :mismatches []}` across 8 forks on virtual threads, 51 hooked calls each.
- `:unavailable-short-circuits` — `{:flat-error? true,
  :callee-not-entered? true}` with the flat `:seon.ambient/unavailable` value.
- `:base-created-closure` — `:db-BASE` from the fork before re-evaluation,
  `:db-FORK` after.

## Graduation path

Each probe is written to become a class regression: probe B's concurrency and
short-circuit assertions belong in the isolation suite the test-infrastructure
spec describes, and the base-created-closure finding deserves its own
regression asserting that a program function is never callable from a fork it
was not created in with a stale environment.

## Reported friction

- The pinned fork's `:built-in-call-observer` reads the analysis ctx — filed as
  [an issue](../../../seon/issues/sci-built-in-call-observer-is-read-from-the-analysis-context.md).
- `sci.impl.opts/init` **silently drops** any option key it does not
  destructure (`opts.cljc:250-269`). A caller who puts `:seon/environment` in
  the options map gets a ctx with no environment and no diagnostic — a quiet
  wrong answer, and exactly the failure the design's "the environment is
  required, never defaulted" invariant is meant to catch. Worth a loud refusal
  in our fork.
- `sci/new-var` without an `:ns` produces a Var whose `toSymbol` is
  unqualified, so identity-keyed lookups silently miss. Cost this lane one
  debugging cycle; the arity error it produced
  (`Wrong number of args (1) passed to: runtime-ctx-hook-probe/read-rows`)
  names the *host* function, not the sci Var or the call site, which is an
  unhelpful face for a Seon agent to receive. Worth improving when the hook
  lands.
