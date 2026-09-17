---
type: research
status: open
created: 2026-09-18
tags: [errors, instrumentation, contracts]
---

# Wrapper enforcement: recording dependency

Slice 2 is **not implemented**. The lane stopped before production edits at
the missing acquired recording/disposition operation. Source inspection used
`steward-platform` HEAD `1e0e17d717cf50bd0451272407541b352ba44160`.
This is an interface dependency, not a foreign lane's in-flight test failure.

## Seams

The [error-entities PRD](../plan/error-entities-prd-2026-09-17.md) §§4.1/5.2
requires a recorded flat refusal returned under `:record`. Its disposition
helper is described, but its callable interface is not implemented here:

- `src/seon/instrument.clj:507–547`: `wrap-interpreted` receives function,
  contract, projection, mode, caps and original callable. `:record` returns
  the original. Neither it nor `compiled-wrapper` at line 609 receives a
  recording operation.
- `src/seon/instrument.clj:679`: the shared host wrapper captures projection
  and caps. `apply!` preserves shared host wrappers across cluster mode changes.
  A cluster-local mode cannot simply be captured on the shared Var.
- `resources/seon/schemas/seon.env.edn:3` and `src/seon/effect.clj:197` carry
  environment and mode, but no recording operation.
- `src/seon/flow.clj:981` receives `commit-fault!`, `commit-drop!`, `panic!`
  and mode reader as proc args. `start-error-fanout!` at line 1164 supplies
  that custody to the graph, not a record-and-return operation to wrappers.
- Private `src/seon/cluster.clj:2955` `commit-fault!` needs connection,
  cluster, process, caps and fault. Those arguments are not supplied to
  `wrap-interpreted`. Selecting them from a global cluster lookup would
  violate acquired custody.
- `src/seon/error.clj:1552` `recording` prepares transaction data; it does
  not commit it. `commit-call` at line 1475 still selects legacy occurrence
  fields at lines 1499–1514. Preservation of the new base/facet entity is
  recorder-slice work under PRD §4.2.

Dependency source: `reference-code/malli/src/malli/core.cljc:2213–2222`
calls the reporter and continues into the body when it returns. Thus a
nonthrowing reporter is insufficient. Throwing into Flow transports a fault
but does not satisfy this invocation's required flat return under `:record`.
No second recorder, queue, global lookup or callback API was introduced.
The `0a58c769d` `kernel/with-arm` boundary was not changed.

## Live evidence

`bin/seon status` reported default alive at PID 80593. MCP runtime status
answered with plumbing ping replies, three error signatures, six errored
evaluations and eleven failed tests. These were inherited observations;
adoption freshness was not established.

The [retained lexical probe](error-wrapper-record-probe-2026-09-18.clj)
ran in MCP JVM mode on that process, with no definitions or cluster writes:

```clojure
{:probe/identical-original true
 :probe/invalid-input {:probe/body-ran true :probe/arguments ["invalid"]}
 :probe/invalid-arity {:probe/body-ran true :probe/arguments []}}
```

The first attempt incorrectly supplied empty caps and the outer wrapper
refused missing `:seon.config.eval.result/max-bytes`. The retained successful
form uses `config/result-caps` over `config/defaults`. This proves the existing
bypass only, not SCI execution or a new implementation. The script's explicit
requires were added for standalone lint; the live form used the already loaded
namespaces. Its initial write was blocked for missing requires, then corrected.

## Resume options

1. **Recommended: the recorder/environment owner carries the existing
   committer operation into the wrapper.** Guarantee: bounded recording with
   provenance and an acknowledged outcome, plus per-call mode. Cost:
   coordinated environment/boot/admission/recorder changes. Give up landing
   the complete behavior solely within the four assigned source/test files.
2. Expand this assignment to implement that acquisition and recorder seam.
   Guarantee: integrated both-dial proof. Cost: cross-owner work involving
   held acquisition callers. Give up independent bounded slice ownership.
3. Stage only pure matching and panic enforcement. Guarantee: partial code;
   no record-and-return completion. Cost: later integration. Give up the
   requested both-dial acceptance proof. This lane did not silently choose it.

## Verification and downstream consumption

Fast tally: **not run; zero tests executed**. No production or test behavior
changed. Per-call before/after overhead: **unmeasured**, because no facet check
was installed. No green implementation, cold gate or platform proof is claimed.

Slice 3 consumes no new API: `seon.error/facets` remains to be implemented
against the supplied projection and complete owned values. Slice 4 still owns
the per-arity result-position analysis. The recorder owner must supply the
operation above before the complete `:record` requirement can be verified.

Grounding was partial: supplied AGENTS §§0–5, requested program-facts rulings,
wrapper source/tests and the relevant PRD/manifest/review/arm-leak seams were
inspected. Large literal-manifest output was truncated; this record does not
claim the requested complete end-to-end reading. Resume must finish that
grounding before production design.

No default lifecycle, reload or adoption command was issued. Foreign edits
were preserved; no foreign lane was contacted or operated. No test JVM,
worktree or scratch root was created. The
[dependency issue](../../../seon/issues/instrumentation-record-mode-has-no-acquired-fault-recorder.md)
records the unresolved work.

The edit hook automatically queued publication
`b787999a-3451-4660-a18c-3a2c0fa19b1b` for these documentation/probe files.
Its recorded feedback refused during init preflight with
`:seon.operator.subprocess/deadline-exceeded`; no successful adoption is
claimed. The lane issued no publication retry or lifecycle workaround.
The markdown hook also reported 44 existing repository citation issues,
including stale dependency gitlinks in
`docs/prds/context-generation/research/agents-md-audit-2026-09-15.md`.
Those unrelated files were left unchanged. `git diff --check` passed.

## Resumed assignment: acquired operation accepted, held SCI arm owner

The owner accepted option 2 with a narrower construction rule: acquire the
existing recording operation at arm time; `:record` construction without it
must return a typed refusal, while `:panic` needs none. Successful recording
must preserve the flat refusal and provenance and the invocation must return
that same value. This supersedes the pending options above.

On resume, HEAD was `e0b8146cd53a0df8b8378f5cdc232d2a4ef92f85`.
The specifically requested PRD §5.2 and §6 ownership row 2 were read in full
with bounded `sed` ranges, as were both the historical “What slice 2 consumes”
and the later authoritative “What slice 2 consumes now” sections. The latter
confirms the additive manifest is available; it is not a dependency refusal.

**Next explicit stop boundary: SCI-side arm request in held `eval.clj`.**
The resumed assignment explicitly says to stop at that item if the SCI-side
arm request lives in this file. It does, and `git status` confirmed it dirty.
The exact current hunk is `src/seon/sci/eval.clj:674–685`:

```clojure
(defn- install-function-contract!
  [ctx committed projection db]
  (when-let [spec-edn (:seon.fn/spec committed)]
    (let [function-symbol (:seon.fn/sym committed)
          sci-var (sci/resolve ctx function-symbol)
          {:keys [:seon.config/on-core-error :seon.sci.admit/caps]}
          (instrumentation-config db)]
      (sci/bind-root!
       ctx sci-var
       (instrument/wrap-interpreted
        function-symbol spec-edn projection on-core-error caps @sci-var))))
  nil)
```

The foreign diff changes the function-symbol binding at line 677 in this same
hunk. No edit, restoration or lane message was made. An isolated HEAD overlay
would exclude that edit but would not grant ownership of this call site.

The actual existing committer in this snapshot is private
`seon.cluster/commit-fault!` at `src/seon/cluster.clj:2955`, not
`seon.error/commit-fault!`. Development acquisition already constructs its
closure at `cluster.clj:2344–2358` and supplies it as
`:seon.flow/commit-fault!`; the Flow fault path supplies the same owner at
`cluster.clj:3270–3273`. This is the operation to reuse.

The propagation gap is precise: `seon.sci.eval/acquire!` at lines 2101–2117
accepts that callback but calls `base-ctx` with only the database. It uses the
callback for `record-acquisition-refusals!` only after generation has completed.
`base-ctx` at lines 2083–2099 constructs/installs the program without the
callback. `install-function-contract!` callers at lines 710, 873 and 2925
likewise supply only ctx, row, projection and database. The held owner must
thread acquired recording custody through construction and subsequent
installation before requiring it at the `:record` wrapper seam.

The canonical armed-cluster test was read at
`test/seon/cluster/armed_test.clj:338–379`: it injects a proc failure through
`turn/next-agent-work`, wakes the real graph and awaits committed error facts.
`test/seon/test_support.clj` has no named `commit-fault!` helper in this
snapshot. No fake committer was substituted and neither held test file was
edited. The environment declarations and `env.clj` also do not currently
carry the named committer operation; the callback presently travels through
acquisition and Flow requests.

The host arm call at `cluster.clj:2505–2509` is within the expanded ownership,
but was left unchanged at the explicit SCI-side stop. No production subset
is presented as completion of items 1–4. Fast tally remains **not run** and
before/after overhead remains **unmeasured**. `git diff --check` is the
verification for this documentation-only update; cold/platform proof remains
orchestrator-owned. No new runtime probe, default operation, worktree, test
JVM or scratch root was created during this resumed turn.
