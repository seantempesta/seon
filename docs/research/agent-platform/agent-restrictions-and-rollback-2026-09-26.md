---
type: audit
status: complete (Part 2 rollback dropped by root)
created: 2026-09-26
scope: lane audit-agent-restrictions-opus — restrictions that make agent work harder, ranked by dogfood frequency
---
# Agent restrictions audit (2026-09-26)

What exists: agents change the program at an SCI REPL (`seon.sci.eval`), with host-bound rows bound to compiled
Vars, an entry gate before install, and run/turn writers in `seon.turn`. The owner's frame (2026-09-26): "We are not
trying to restrict them, we are tweaking the system to make it easier for them to do work." Agents may make serious
changes, first-party code included; the immutable database is the safety net (AGENTS.md, commits c186136e1,
43277a00a, af2ee3ee8). For each item the question is: does this make an agent's work harder, and what correctness
failure (if any) does it prevent? Verdicts: **KEEP** (names a correctness failure and costs little), **DELETE**
(prevents nothing), **RESHAPE** (keep the correctness part, drop the refusal).

Part 2 (rollback entrance) was dropped by root on the owner's instruction ("rollback can happen later").

## Broken things first

- The default JVM was replaced during this audit. Its advertisement disappeared at about 16:02Z, and pid 19859 was
  booting from `start --head`. That was root's restart, not this lane's. The probe of private-var access, `eval`,
  `with-redefs` and `all-ns` did not run, so those rows below are marked **unverified live**.
- Branches `audit-agent-restrictions` and `audit-agent-restrictions-2` could not be retired because the JVM was down.
  **Root: retire both** (`branch` tool, action retire).
- Found in passing (not restrictions, but they make work harder):
  - Contract finding on `seon.sci.kernel/failure-value`: an ordinary "Unable to resolve symbol" error matches none of
    its declared alternatives.
  - The same kind of finding on `seon.sci.eval/evaluate` for the unindexed-change refusal.
  - An expected `starting-namespace-changed` refusal logs a full `datahike.writer` stack trace into the MCP reply.

## Ranked by how often a working agent hits them

Frequency evidence comes from `docs/research/agent-platform/dogfood-report-2026-09-26.md` (cycles 1–10, table rows
#n) and from probes run today on branches `audit-agent-restrictions*` off default at basis 536871301.

### 1. Host-bound rows cannot change at the REPL: "must change through the loaded source files". RESHAPE

- **Where**
  - `src/seon/sci/eval.clj:1516-1526` (`host-bound-row?`) and `:1595-1626` (the refusal in `install-row!`).
  - `src/seon/test.clj:2146-2154` (`arm-first-loads!`, the same message for a contract change).
  - `src/seon/cluster.clj:2829-2911` (`host-bound-declarations`, `adopt-then-test!`).
  - Derivation: `src/seon/fn.clj:1028-1079` (`host-bound-callers`, `host-bound-declaration?`).
- **What it blocks**
  - Any REPL change to a declaration whose body uses a class outside SCI's `default-classes`, or reify, proxy or
    deftype, or a non-SCI macro.
  - Live count on default: **926 of 5,264 function rows are `:seon.fn/host-bound? true`** (sample:
    `seon.agent/archived?`, `my.program/refusal`, `my.program/read-result`).
  - The usual trigger is `(java.util.Date.)` inside an error map. Almost every function that returns a declared
    error is host-bound.
  - Dogfood #1 **[B]**: a branch forked before an adoption refuses every form after it, even `(doc help)`. "Default
    adopts several times an hour, so no agent branch survives."
  - Live today: removing the `java.util.Date` from `seon.agent/archived?` made it interpretable. The install got past
    this refusal and was stopped by item 5 instead.
- **Correctness failure prevented:** SCI cannot interpret a body that uses an unadmitted class. Silently calling the
  compiled Var instead would run code other than the row.
- **Reshape**
  - (a) Item 2 removes the main cause, the class set. After it, host-bound shrinks to reify, proxy, deftype,
    definterface and JVM-only macros such as `go`.
  - (b) Where a row still cannot be interpreted, refuse it by name only when it is called. `install-refusing-root!`
    already does this for the unbound case at `eval.clj:1620-1630`. Do not refuse the install or every later form.
    This matches the law "refuse unsupported overrides by name instead of running other code".
  - A stale branch then keeps working. Only a call into a genuinely stale host-bound row answers with its name.
- **Deletion**
  - Delete the java-class clause of `host-bound-callers`, `fn.clj:1049-1053` (5 lines).
  - Delete the `host-row` refusal branch in `install-row!`, `eval.clj:1595-1599` and `1617-1626` (about 14 lines),
    and route that case through `install-refusing-root!`.
  - Delete the contract-change refusal in `arm-first-loads!`, `test.clj:2146-2154` (9 lines): a changed contract on a
    first load is armed from the branch like any other.
  - Once host-bound is rare, `adopt-then-test!` and `host-bound-declarations` (`cluster.clj:2829-2911`, about 80
    lines) are worth re-checking. Root should confirm before cutting them.

### 2. The interpreter has almost no classes and no dependency namespaces. RESHAPE

- **Where**
  - `src/seon/sci/eval.clj:245-266` (`java-lang-throwables`).
  - `:299-325` (`sci/init`): `:classes` is only the `java.lang` Throwables, with no `:load-fn`. `:namespaces` holds
    only `clojure.core` and `clojure.string` (interrupt variants), `clojure.test` and `seon.bootstrap`, declared in
    `resources/seon/schemas/seon.sci.binding.edn`.
- **What it blocks** (probed live on the branch)
  - `(System/currentTimeMillis)` → "Unable to resolve symbol: System/currentTimeMillis". It fails at analysis, so the
    whole form fails and `try` cannot catch it.
  - `(require 'datahike.api)` → "Could not find namespace datahike.api."
  - `(require 'malli.core)` → "Could not find namespace malli.core."
  - `clojure.set` and first-party `seon.db` do resolve.
  - Dogfood: "an agent cannot validate a value against a named schema at the REPL" (cycle 9, line 119); `Math/abs`
    (#14); `io/file`, `tc/quick-check` unresolved in 1,482 gated tests (#2).
  - This is also the root of item 1: every `java.util.Date`, `UUID` or `File` use makes a declaration host-bound.
- **Correctness failure prevented:** none. SCI's own documentation calls `:classes` a sandbox "safety measure"
  (`reference-code/sci/README.md:497-500`), which is a hostile-agent protection. The one real limit is that host
  calls cannot be interrupted (`reference-code/sci/doc/interrupt.md:84-98`). The deadline already names that limit,
  and the classes restriction does not fix it.
- **Reshape**
  - Pass `:classes {:allow :all}` (SCI option, `README.md:500`). Add a `Class/forName` fallback in our SCI fork's
    `interop/resolve-class-opts` (`reference-code/sci/src/sci/impl/interop.cljc:166-183`) when `:allow` is `:all`,
    so any class on the JVM classpath resolves. That is about 5 lines in the fork; upstream has no such seam.
  - Add a `:load-fn` that, for a namespace already loaded in the JVM and not a program namespace, copies its publics
    with the existing `copy-host-var` (`eval.clj:229`), about 10 lines. `datahike.api`, `malli.core`,
    `clojure.java.io` and `clojure.test.check` then require normally.
- **Deletion**
  - Delete `java-lang-throwables` (about 22 lines).
  - Delete the Throwable special-case in `fn.clj:1053`.
  - `seon.sci.binding`'s `public-namespace clojure.test` entry becomes one case of the load-fn. Keep the three
    `seon.bootstrap` targets.
  - Net about −20 plus the fork's +5.

### 3. A refused run leaves the context "unavailable" for every later run. RESHAPE

- **Where:** `src/seon/sci/eval.clj:690-710` (`execution-unavailable`) and its callers around `:760-780`.
- **What it blocks:** dogfood #21 **[B]** (cycle 10) and #15. After one refused program change, every later run,
  even `(+ 1 1)`, answers "This execution is unavailable … release and reacquire the context", until the branch is
  retired.
- **Correctness failure prevented:** a refused change whose private SCI state could not be restored must not keep
  running on a half-restored context. That part is real.
- **Reshape:** keep refusing the rest of *that* run. At the next run's start, reacquire from the branch head, as the
  message already promises ("your next run starts from the branch head") and as AGENTS.md says ("Reacquire from the
  branch head at turn start"). The refusal stays; the permanent lock-out goes.
- **Deletion:** none, beyond whatever "held unavailable" state blocks reacquisition. This is a defect fix inside the
  same owner. Size it at the REPL first.

### 4. Namespace friction: fixed starting namespace, `in-ns` does not persist, `(do (in-ns …) (defn …))` refused

- **Where**
  - `src/seon/turn.clj:702-705` (`plan-call`) and `:755-756` (`system-run-call`), both
    `::starting-namespace-changed`.
  - Docstring `src/seon/cluster/agent.clj:760-763`.
  - `src/seon/sci/eval.clj:780-793` (`unindexed-change`).
- **What it blocks**
  - Probed live: MCP `namespace seon.agent` on a branch whose host was created in `user` → refused
    `:seon.turn/starting-namespace-changed`.
  - `(in-ns 'seon.agent)` as its own run succeeds, but the next run starts in `user` again. A `defn` then landed as
    `#'user/archived?`, not in `seon.agent`.
  - `(do (in-ns 'seon.agent) (defn archived? …))` → "This form changed seon.agent/archived?, which its analysis
    declares no definition for at the place it ran".
  - Dogfood #12 (still open, cycle 10); report line 49 (host created once per branch; later `namespace` refused).
  - At a Clojure REPL, `*ns*` persists and `in-ns` inside `do` works.
- **Correctness failure prevented**
  - `starting-namespace-changed`: none. The run records its own `::starting-ns` either way (`turn.clj:706-707`), so
    nothing is mis-attributed. **DELETE.**
  - `unindexed-change`: prevents Var/row drift, where a changed Var has no row. That is real and stays. The refusal
    in the `do`/`in-ns` case is an analyzer attribution defect: the analysis puts the `do` in the starting namespace.
    **RESHAPE** at the analyzer. Also start each run in the namespace where the agent's previous run ended (its
    context's `*ns*`), not the host's creation namespace.
- **Deletion:** `turn.clj:702-705` and `:755-756` (about 7 lines), plus the docstring sentence. There is no deletion
  in `unindexed-change`; the fix belongs to its producer.

### 5. "A different SCI context is already armed on this thread". RESHAPE

- **Where:** `src/seon/sci/kernel.clj:310-326` (`acquire-arm`), documented at `:342-345` and `eval.clj:54`.
- **What it blocks:** probed live today. A deliberately gated redefinition of first-party `seon.agent/archived?` on a
  branch was refused at install. Its reaching test
  `seon.sci.supplied-database-test/own-record-reads-supply-the-same-database-and-agent-as-explicit-calls` evaluates
  in a second context while the agent's arm is live, and failed with this refusal (12 expected values, 8 returned).
  Any test or function that opens another agent's context from inside an evaluation hits this. The owner allowed
  exactly that reach today ("deliberately … grabbing the context it needs is allowed").
- **Correctness failure prevented:** "one thread cannot honestly serve two limits". The worry is that two deadlines
  on one thread cannot both be honoured.
- **Reshape:** the guard and `interrupt-fn` are process-wide (`kernel.clj:95-107`), and nested same-interpreter work
  already inherits the governing arm (`:327-331`). Treat a different interpreter the same way: it inherits the
  outer arm, so one deadline governs and there is still one latch. Nothing is lost; the outer deadline is the
  honest bound for nested work.
- **Deletion:** the `when-not same-interpreter?` throw, `kernel.clj:310-326` (about 17 lines), plus the
  `same-interpreter?` helper if it has no other caller.

### 6. `native-call-refusal`: `remove-ns`, `ns-unalias`, `intern`, `alter-var-root`. RESHAPE (part DELETE)

- **Where:** `src/my/program.clj:747-790`, installed on every context at `src/seon/sci/eval.clj:308-312`.
- **What it blocks:** the native Clojure REPL operations. The agent is told "Use my.program/remove-ns! …" instead.
  Dogfood #15/#21 start from a refused `ns-unmap` run.
- **Correctness failure prevented**
  - For `intern` and `alter-var-root` of a program Var: Var/row drift. `unindexed-change` (`eval.clj:780-793`)
    already refuses exactly that ("a root written after its definition (alter-var-root, intern)"). This is a
    duplicate check. **DELETE.**
  - For `remove-ns` and `ns-unalias`: facts must decide before SCI changes.
- **Reshape:** when the program has a row, the SCI hook *calls* `my.program/remove-ns!` or `my.program/ns-unalias!`
  instead of returning a refusal naming them. The native form then does the right thing.
- **Deletion:** the `intern` and `alter-var-root` arms and the `breaks` lookup (about 25 of 44 lines).

### 7. Issue writes cannot cite the branch's own new tests. RESHAPE

- **Where:** `src/seon/issue.clj:1291-1300` (`require-test-refs!`), called at `:1347`, `:1544`, `:1587`.
- **What it blocks:** dogfood cycle 10, line 195 and item 10, line 284: `my.issue/add!` refuses the tests the
  agent's branch just added.
- **Correctness failure prevented:** dangling test references. That is real.
- **Reshape:** check against the caller's custody database (the branch), not another basis. There is no refusal to
  delete; it is a basis fix.

### 8. `seon.schema/register!` only at top level. RESHAPE

- **Where:** `src/seon/schema.clj:1279` ("Schema declarations require an isolated registration delta"), and `:1758`.
- **What it blocks:** dogfood #17 (still open). `register!` inside `let` refuses.
- **Correctness failure prevented:** a registration outside a delta would change the registry without a matching
  committed fact.
- **Reshape:** open the delta around any `register!` call reached during a form, not only top-level ones. This is
  lower frequency. Size it before cutting.

### 9. Printed output over `:seon.config.eval.result/max-bytes` refuses the evaluation. RESHAPE (low evidence)

- **Where:** `src/seon/sci/admit.clj:120-135` (output streaming bound); header note at `eval.clj:62-66`.
- **What it blocks:** a form that prints a lot is refused, not stored. No dogfood hits were recorded.
- **Correctness failure prevented:** unbounded memory while streaming. That is real.
- **Reshape:** spill past-threshold output into the blob tier, as oversized values already do (MCP
  `eval_clj` description). Only the floor printer clips (AGENTS.md "Apply presentation limits once").

### KEEP (each names a correctness failure; small cost to agents)

| Where | What | Failure it prevents |
|---|---|---|
| `src/seon/sci/kernel.clj:245-265`, `eval.clj:12-20` | Evaluation deadline via SCI `:interrupt-fn` | A non-terminating interpreted loop holding the agent and its thread forever |
| `src/seon/sci/eval.clj:1516-1526` (`host-bound-row?` unknown → true) | A row without the fact is not interpreted | Running an unanalyzed body as if analyzed (law: silence is not health) |
| `src/seon/sci/eval.clj:1553-1562` | Committed source must equal the install request | Installing code other than the committed row |
| Entry gate `:gate` (`seon.test.accretion/install-refusal`) | Reaching tests must pass before install on the branch | A red program row entering the branch; selection is fixed by today's call-reach ruling, not by removing the gate |
| SCI default: built-in Vars read-only (no `:unrestricted`) | `with-redefs`/`alter-var-root` of `clojure.core` inside SCI | SCI built-in Vars are shared across forks, so one agent's redef would change every agent's `inc`. **Unverified live** (JVM went down). |
| `src/seon/fn.clj:2334-2446` (`capability-refused!`) | Effect-capability declarations must be well-formed | An effect handler that cannot be resolved or scheduled (`:io` workload) |

No `:deny` or `:allow` symbol lists and no hostile, untrusted or sandbox policy code exist in `src`. The rg hits for
those words are comments. The comment at `eval.clj:17` ("sandboxed code cannot forge it") describes SCI's interrupt
semantics and should be reworded when item 5 lands.

## Counts and size

- **DELETE:** 1 whole item (4a, `starting-namespace-changed`), plus the duplicate `intern`/`alter-var-root` arms
  inside item 6.
- **RESHAPE:** 9 (items 1, 2, 3, 4b, 5, 6, 7, 8, 9).
- **KEEP:** 6.
- **Estimated deleted lines:** about 100 in `src`. That is item 1 about 28, item 2 about 22, item 4 about 7, item 5
  about 17, item 6 about 25, before the optional `cluster.clj:2829-2911` re-check (about 80 more).
- **Added:** about 5 in the SCI fork (item 2) and about 10 for the load-fn.

## Order that removes the most friction first

1. Item 2 (classes and load-fn): unblocks dependency namespaces and shrinks host-bound from 926 rows.
2. Item 1b (refuse host-bound by name only when called): no more dead branches after adoption.
3. Item 3 (reacquire on the next run).
4. Item 4 (delete `starting-namespace-changed`; `*ns*` persists; analyzer attributes `in-ns` inside `do`).
5. Item 5 (inherit the arm across interpreters).

Items 6 to 9 follow. Each lands through the gate with one class regression asserting the wanted behaviour, for
example: `(require 'malli.core)` works at an agent REPL; a stale branch answers `(+ 1 1)` after an adoption.

## Probes run (branch runs, default at HEAD 61d207c3e lineage, pid 13262)

- `(require 'datahike.api)` → "Could not find namespace datahike.api."; `malli.core` the same; `clojure.set` ok;
  `seon.db/q` resolves.
- `(System/currentTimeMillis)` inside `try` → form-level "Unable to resolve symbol", plus a failure-value contract
  finding.
- Host-bound count query (JVM, read-only, 13 ms): `[[false 4338] [true 926]]`.
- MCP `namespace seon.agent` on a host created in `user` → `:seon.turn/starting-namespace-changed` (5 ms), with a
  writer stack trace logged into the reply.
- `(do (in-ns 'seon.agent) (defn archived? …))` → unindexed-change refusal. A separate `(in-ns …)` run, then
  `(defn …)` → `#'user/archived?`.
- On a fresh branch hosted in `seon.agent`: `(defn archived? …)` without `java.util.Date` → install refused by the
  gate. 2 tests, 1 failed with "A different SCI context is already armed on this thread"; auto-check 25/25.
