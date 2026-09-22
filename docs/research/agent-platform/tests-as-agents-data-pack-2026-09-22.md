---
type: reference
created: 2026-09-22
subject: what exists today for "tests run as agents run" (owner ruling 2026-09-22)
head: 51b4ba78b
---

# Tests as agents run — data pack

HEAD at collection: `51b4ba78b` (branch `refactor/agent-platform`). Five lanes edit the
tree concurrently; every line below was read at collection time. Live probes ran through
`mcp__seon__eval_clj`, mode `jvm`, `read_only true`, root `/Users/sean/src/seon`,
cluster `default` (PID 38968, branch `cluster-default`, basis 536871148, commit
`6ab29f94-f2c7-59f7-85b5-4b68da866176`). No writes, no JVM launched, no test run.

## Summary — verdict per question

1. **Custody exists and is already a value.** `seon.db/call-with-custody` (`db.clj:370`) and the identical binding inside `seon.sci.eval/evaluate` (`eval.clj:2826`) are two spellings of one mechanism; `::custody` on the ctx (`eval.clj:2273`) is the carrier. Gap: two spellings, one function.
2. **The fork seam exists and is already connection-repointing.** `seon.sci.eval/fork-cluster-ctx` (`eval.clj:2293`) forks a base ctx onto a *different* connection. It has **no production caller** — only `test_support.clj:385`. Measured `sci/fork` on `default`'s ctx: **0.00123 ms**.
3. **The five-step overridden/affected rule is NOT implemented, in any part.** The only decision today is `:seon.schema.admission/source` — `:core` binds the JVM Var (`eval.clj:894-895`), everything else interprets stored source (`:904`). Nothing compares stored source to the JVM's loaded commit. This is the crux gap.
4. **The index function is already branch-targetable; the publication head flip is not.** `seon.fn/index!` (`fn.clj:3305`) takes `:seon.db/connection`. `seon.cluster.source/publish!` (`source.clj:354`) hard-codes `current-branch` (`:29`). `refresh-source!` (`cluster.clj:2171`) does a process-wide `(require namespace-name :reload)` at **`cluster.clj:1984`** — that is the shared-JVM hazard, named exactly.
5. **The branch fixture already exists at HEAD and the B4 c1 lane has already cut it to the captured commit.** HEAD branches off a *published-base copy* (`test_support.clj:954-985` of HEAD); the working tree branches off the caller's captured execution commit (`test_support.clj:689-727`). `seon.test/run` is still `[test-var connection options]`, not one request.
6. **Population: 2,056 test rows, 89 platform rows, 66 tests (3.2 %) reach a destroyer.** No test declares a database in its arity — `deftest` is 0-arity; the fixture macro is the only channel. 1,642 of 2,069 `deftest`s live in files using `with-database`.
7. **MCP `sci` mode runs on the cluster's shared live ctx and cannot target a branch** (`script/seon/dev/mcp.clj:510`).
8. **Smallest closure is four agent-seam changes, then two test-caller changes** — §8.
9. **Fork lifecycle: create and retire exist as installed Seon functions; the connection-repointed fork exists but is test-only; retirement-by-unlinking and GC reclamation exist but are not wired to a fixture** — §9.

---

## 1. How an agent evaluation gets its database today

| Fact | `file:line` |
|---|---|
| `*conn*` — the dynamic cluster connection an elided `seon.db` arity reaches | `src/seon/db.clj:146` |
| `*read-database*` — the evaluation-scoped read basis | `src/seon/db.clj:150` |
| `current-database-value` / `current-connection` — the two readers of those vars | `src/seon/db.clj:328`, `:335` |
| `missing-connection-error` — the refusal when nothing is bound; names `seon.db/*conn*` and tells a REPL to use `(seon.cluster.boot/connection "default")` | `src/seon/db.clj:313-326` |
| `call-with-custody` — "the custody is a VALUE on the request"; binds `*conn*` and clears `*read-database*` | `src/seon/db.clj:370`, binding at `:391` |
| `call-without-custody` — the same scope with nothing handed | `src/seon/db.clj:394` |
| `:seon.db/custody-request` — the declared schema of that value | `resources/seon/schemas/seon.db.edn:179-181` |
| Agent evaluation binds the same two vars directly, from `::custody` on the ctx | `src/seon/sci/eval.clj:2826-2832` (`connection` read at `:2806`) |
| `::custody` is written onto a cluster ctx at construction: connection when supplied, else the db value | `src/seon/sci/eval.clj:2273-2275` |
| `::custody` is rewritten onto a fork for a different connection | `src/seon/sci/eval.clj:2332` |
| Second, independent injection path: `seon.call-preparation` supplies a *declared* omitted `:seon.db/db` / `:seon.db/connection` argument from the ENVIRONMENT on the ctx (SCI's `:call-preparation-hook`) | `src/seon/call_preparation.clj:1-36`, `install` at `:95`, carrier `:91` |
| The two suppliers | `src/seon/db.clj:1807` (`supplied-database-value`), `:1834` (`supplied-connection`) |
| Which db value: `supplied-database-value` prefers `:seon.db/db` on the environment, else `(d/db connection)` **at call time** | `src/seon/db.clj:1819-1826` |

**Which branch/db value is handed over.** The cluster's live branch head, not a captured
value: `::custody` holds the *connection* whenever one exists (`eval.clj:2274`), and the
supplier derefs it per call (`db.clj:1824`). A captured immutable value only reaches the
body when the caller puts `:seon.db/db` on the environment or on the request
(`eval.clj:2748-2761`).

**The one production consumer of `call-with-custody` is the test runner** —
`src/seon/test/runner.clj:687`. `rg -n "call-with-custody|call-without-custody" src`
returns only `db.clj:370,394,417` and that one line. Agent evaluation uses
`with-bindings` on the same two vars instead (`eval.clj:2826`).

**Missing for the ruling:** nothing in the custody shape. The injection a test needs
already exists and is already a value. What is missing is that the *function* is called
by the test runner and open-coded by the agent evaluator.

---

## 2. How a SCI context is created and forked today

| Fact | `file:line` |
|---|---|
| `build-base-ctx` — the program-free interpreter | `src/seon/sci/eval.clj:185` |
| `base-ctx` — memoised base plus `::custody` propagation | `src/seon/sci/eval.clj:2150`, `:2141` |
| `cluster-ctx` / `cluster-ctx*` — one cluster's interpreter; installs projection, call-preparation state, `::custody` | `src/seon/sci/eval.clj:2238`, `:2265`; custody `:2273-2275`; `call-preparation/install` `:2280`; `call-preparation/watch!` `:2288-2290` |
| `acquire-program!` — walks namespace + function rows, orders by declared requires, installs each through `install-row!` | `src/seon/sci/eval.clj:1709`; cycle refusal `:1872`; `install-row` closure `:1896` |
| `fork-for-turn` — the **agent** fork: `sci/fork` + fresh `installed-functions` / `program-snapshot` / `print-session` atoms; inherits `::custody` unchanged | `src/seon/sci/eval.clj:2116`, `sci/fork` at `:2127` |
| `acquire-context!` — the agent's retained fork, one per agent, under `locking` | `src/seon/cluster/agent.clj:682-700` |
| `fork-candidate-ctx` — delegates to `fork-for-turn`; candidate code never calls plain `sci/fork` | `src/seon/sci/eval.clj:3096-3105` |
| `fork-cluster-ctx` — forks a base ctx onto a **different connection**: rewrites `::custody`, projection, call-preparation state, and re-installs every interpreted contract | `src/seon/sci/eval.clj:2293`; `sci/fork` `:2325`; `::custody` `:2332`; contract re-install loop `:2337-2344` |
| Its only caller anywhere | `test/seon/test_support.clj:385` (HEAD `:654`) |
| Turn fork call sites (production) | `src/seon/turn.clj:4800`, `:4841` |
| SCI's `fork` — `(update ctx :env #(atom (assoc @% :sci/generation (next-generation))))` | `reference-code/sci/src/sci/core.cljc:345-351` |
| `copy-var*` — copies a root value, not a dependency graph | `reference-code/sci/src/sci/core.cljc:112` |
| `bind-root!` / `add-namespace!` / `resolve` | `reference-code/sci/src/sci/core.cljc:273`, `:679`, `:837` |

**Measured fork cost, read-only, on `default`'s live ctx** (form:
`(dotimes [_ 100] (sci.core/fork ctx))` over `(:seon.sci.eval/ctx instance)`):
**0.00123 ms per fork** — 17× cheaper than the 0.021 ms cited in D1 §2a. `sci/fork`
allocates one atom and bumps a generation counter; nothing else.
`(count @(:seon.sci.kernel/installed-functions ctx))` on `default` = **0**, so
`fork-cluster-ctx`'s contract re-install loop (`eval.clj:2337`) is currently a no-op on
that cluster — its cost is proportional to interpreted rows, which `default` has none of.

**What a fork shares vs isolates.** Shares: the JVM's loaded classes and Vars (SCI copies
of `:core` rows are `copy-var*` of the host Var — `eval.clj:818-826`), the Malli registry,
instrumentation state, and any mutable object a shared Var closes over. Isolates:
new and redefined SCI Vars, by generation (`core.cljc:345-351`); the fork's own
`installed-functions`, `program-snapshot`, `print-session` atoms (`eval.clj:2127-2135`);
and, in `fork-cluster-ctx` alone, the custody, projection and call-preparation state.

**Missing for the ruling:** nothing in the fork primitive. `fork-cluster-ctx` is exactly
the seam the ruling describes — but it lives with a single test caller, so it has never
been proven on the production path.

---

## 3. How a lane's edited source reaches a branch without changing the JVM — the crux

**None of B2 §2a's five steps is implemented.** The install decision today is one
`if` on the admission source:

| Fact | `file:line` |
|---|---|
| `install-row!` — the one installer; resolves the committed row by identity | `src/seon/sci/eval.clj:829` |
| The decision: `:core` → `install-jvm-root!`; otherwise interpret from database | `src/seon/sci/eval.clj:894-895` (`:core` → JVM), `:904` (else interpret) |
| `install-jvm-root!` — `sci/copy-var*` of the loaded JVM Var into the ctx namespace | `src/seon/sci/eval.clj:818-826` |
| `install-function-from-database!` — interpret stored source, then arm the contract | `src/seon/sci/eval.clj:709-741` |
| `install-function-contract!` — per-context wrapper over the resolved SCI Var | `src/seon/sci/eval.clj:689` |
| Fallback: an interpretation failure silently falls back to the JVM Var, recorded as `:jvm-fallback` | `src/seon/sci/eval.clj:909-912`; consumed at `:1911`, `:1918` |
| `same-declaration-source?` — compares a *request* row to the *committed* row (writer canonicalisation), **not** to the JVM's loaded commit | `src/seon/sci/eval.clj:773-793` |
| `committed-row?` — same comparison, for install eligibility | `src/seon/sci/eval.clj:795-817` |
| `install-evaluated-rows!` — accepted-definition installation after an agent write | `src/seon/sci/eval.clj:1077` |
| `acquire-program!` — no digest or loaded-commit comparison anywhere in its body | `src/seon/sci/eval.clj:1709-1901` |
| The reverse-reach primitive the `affected` closure would use, already installed | `src/seon/fn.clj:1505` (`gate-sets`) |

**What happens today when stored source differs from the JVM's loaded commit.** For a
`:core` row: nothing is detected and **the JVM Var is called** (`eval.clj:894-895`). The
context silently executes the loaded program, not its branch's program. There is no
`overridden` set, no `affected` closure, no per-context reinterpretation, and no refusal.
`:seon.source/commit-id` is stored on the cluster entity (`cluster.clj:2138`) and is the
value such a comparison would read, but no reader compares it to a row's source.

**Namespaces that carry host-defining forms** (command:
`rg -l '\((deftype|defprotocol|definterface|gen-class|proxy|defrecord|reify)\s' src --glob '*.clj' --glob '*.cljc'`),
**14 of 107** source namespaces at this HEAD — the plan's "15 of 109" figure, re-measured:

| Namespace | First host form |
|---|---|
| `src/seon/cluster.clj:2839` | `reify Executor` |
| `src/seon/cluster/agent.clj:95` | `deftype CountedSlidingBuffer` |
| `src/seon/env.clj:36` | `defrecord Environment` |
| `src/seon/flow.clj:304`, `:965` | `deftype RefusingBuffer`, `CountedDroppingBuffer` |
| `src/seon/fn/analyzer.clj:310` | `proxy [SimpleFileVisitor]` |
| `src/seon/fn/signature.cljc:21` | `reify LispReader$Resolver` |
| `src/seon/plan.clj:782` | `reify IDeref` |
| `src/seon/print.cljc:18`, `:214`, `:278` | `defprotocol Sink`, `deftype TextSink`, `HiccupSink` |
| `src/seon/render/hiccup.clj:70` | `defrecord Raw` |
| `src/seon/render/ns.clj:128` | `reify mr/Registry` |
| `src/seon/render/walk.clj:57` | `deftype DatabaseSchemaIdentity` |
| `src/seon/sci/kernel.clj:51` | `reify ThreadFactory` |
| `src/seon/test/runner.clj:617`, `:622`, `:2091` | `reify ThreadFactory`, `Runnable` |
| `src/seon/web/jvm.clj:136` | `proxy [FilterInputStream]` |

Of these, the **type-defining** six — `cluster/agent.clj`, `env.clj`, `flow.clj`,
`print.cljc`, `render/hiccup.clj`, `render/walk.clj` — cannot be interpreted at all
(`deftype`/`defrecord`/`defprotocol` create host classes). The eight `reify`/`proxy`
namespaces are a narrower, per-form question that this pack does not resolve.

**Missing for the ruling:** everything. A lane's edit reaching a branch is today
indistinguishable from the JVM's own program for any `:core` row, which is 100 % of
first-party source.

---

## 4. The index / publication function

| Fact | `file:line` |
|---|---|
| `seon.cluster/refresh-source!` — "publish the current source tree onto the one `current-src` branch"; arities `[root]`, `[root changed-paths]`, `[… development-cluster]`, `[… directory]` | `src/seon/cluster.clj:2171-2226` |
| It takes **no branch argument** | `src/seon/cluster.clj:2181-2186` (declared `:malli/schema`) |
| `seon.cluster.source/publish!` — reconcile and publish on the source history | `src/seon/cluster/source.clj:354` |
| `current-branch` is a hard-coded `def` — `:current-src` | `src/seon/cluster/source.clj:29-31` |
| publish! builds on a **scratch branch** first (`registry/branch!` + `store/open-branch!`), then flips `current-src` | `src/seon/cluster/source.clj:394-397`, `:400`, `:461-479` |
| the scratch name is pid+instant+uuid scoped | `src/seon/cluster/source.clj:224-230` |
| `seon.fn/index!` — the analysis→rows writer; **takes `:seon.db/connection` explicitly** | `src/seon/fn.clj:3305`, request destructuring `:3323` |
| `development-source-refresh!` — the adoption half: schema changes, `seon.fn/index!` with `:seon.reconcile/adopt-identities`, issue adoption | `src/seon/cluster.clj:2033`, index call `:2078-2086` |
| **JVM reload — the shared-JVM hazard, exact line** | **`src/seon/cluster.clj:1984` `(require namespace-name :reload)`**, inside `load-development-definitions!` `:1977`, called at `:2110` |
| Process-wide re-arming after the reload | `src/seon/cluster.clj:2118` (`instrument/apply!`) |
| The adoption record: `:seon.source/commit-id` on the cluster ref, `:seon.test/adoption-identities` on the tx | `src/seon/cluster.clj:2136-2145` |
| Snapshot resources must match the hosting JVM's tree or publication refuses | `src/seon/cluster.clj:2155-2168` |

**Can they target another branch?** `seon.fn/index!` — yes, today, unchanged: hand it a
branch connection. `publish!` — no: `current-branch` is a `def`, not a request member;
making it one is a one-line signature change plus the `force-branch!`/`registry/branch!`
sites at `source.clj:461-479`. `refresh-source!` — no, and it should not: its
`development-source-refresh!` half is *by construction* a JVM mutation.

**Does publishing imply `require :reload`?** `publish!` alone does not — it only writes
rows and flips a branch head. `refresh-source!` with a `development-cluster` does, at
`cluster.clj:1984`, and that reload plus `instrument/apply!` (`:2118`) is process-wide:
every cluster and every retained SCI fork in the JVM gets the new loaded Var. **That is
the shared-JVM hazard in one line**: a lane's half-landed namespace, published to *its*
branch, would still be `require :reload`-ed for everyone if it went through
`refresh-source!`.

**Measured cost.** README §5 records explicit no-change publication **264 ms** and a
repeated docstring edit **2,723 ms** (`docs/prds/agent-platform/plan/README.md:216`).
The `docs/prds/agent-platform/landing/` notes at this HEAD carry no newer publication
clock row; the A2 retention note
(`landing/lane-a2-storage-retention-2026-09-22.md:41-47`) reports GC dry-run timings
(8–11 ms on a 9-key scratch store), not publication. Hook publication remains paused
(`landing/lane-a2-storage-retention-2026-09-22.md:9`), so `default` at PID 38968 has
adopted no edited definition this session.

---

## 5. The test fixture and runner today

| Fact | `file:line` |
|---|---|
| **HEAD** `with-branched-database` — holds a published-base lease, `d/branch!` off the base head, `d/connect` on the branch config, `delete-branch!` + release in `finally` | `git show HEAD:test/seon/test_support.clj:954-985` |
| **HEAD** `with-database` — dispatches fresh-store vs branch | `…HEAD:test/seon/test_support.clj:990-1004` |
| **HEAD** `fork-cluster-ctx` — forks the *process base's* ctx, derives the cluster name from the database, replaces the environment | `…HEAD:test/seon/test_support.clj:623-655` |
| **HEAD** the base machinery the ruling deletes: `retrying-base`, `*held-base*`, `database-base`, shutdown hook | `…HEAD:test/seon/test_support.clj:590-613` |
| **Working tree (B4 c1, in flight)**: `with-branched-database` now branches off `(d/commit-id database)` from the caller's explicit `:seon.db/connection`/`:seon.db/db`, refuses absent custody and absent projection, and stashes `:seon.sci.eval/ctx` on the connection meta | `test/seon/test_support.clj:689-727` |
| working-tree delta | `git diff --stat`: `test_support.clj` −342/+54 lines; `src/seon/test.clj` −19/+… (one line of net change) |
| `seon.test/run` — `[test-var connection]` / `[test-var connection options]`; **not** one request map | `src/seon/test.clj:537-576` |
| `seon.test/run-owned` — the agent entry: takes the agent's connection and `:seon.sci.eval/ctx` from `:my.program/context` | `src/seon/test.clj:578-609`, ctx at `:594` |
| `execute-admitted!` — docstring states the custody rule verbatim | `src/seon/test.clj:396` |
| `bounded-result` — **already branches to the agent seam**: `:seon.sci.eval/ctx` present → `sci.eval/run-test`; absent → `runner/run-var!` | `src/seon/test.clj:141-150` |
| `sci.eval/run-test` / `run-tests` — arm the ctx, then `test.runner/run-vars!` | `src/seon/sci/eval.clj:3208`, `:3192`, `kernel/with-arm` `:3204` |
| `run-vars!` — the one capture path; custody applied by `db/call-with-custody` | `src/seon/test/runner.clj:656`, custody at `:687` |
| `run-var!` docstring — "THE CUSTODY IS A VALUE, NEVER A RE-READ" | `src/seon/test/runner.clj:700-720` |
| `destroyers` / `host` / `:seon.test.host/isolated-snapshot` | `src/seon/test.clj:215`, `:299`, `:304`, `:337` |
| `select` | `src/seon/test.clj:831` |
| `bin/test-fast` — acquires a slot, resolves a published base via `bb`, launches `clojure -M:test -m seon.test.fast` with `-Dseon.test.published-base` | `bin/test-fast:1-48` |
| `bin/_test-slot` — machine-wide cap of 2 test JVMs, derived from Git's common dir | `bin/_test-slot:21` (`test_slot_count=2`) |
| The published-base system property the fixture reads | `test/seon/test_support.clj` HEAD `:111`, `:136`, `:223`, `:233`, `:372`, `:599` |

**How a body receives a connection today.** As a *fixture argument*: `run-database-body`
calls `(body connection)` (`…HEAD:test_support.clj:918-927`; working tree `:653-659`).
The SCI ctx is a *separate explicit call* to `test-support/fork-cluster-ctx`, used by 99
test files. Nothing is injected by arity.

**What a run boots — measured, most recent run on disk**
(`tmp/test-runs/run.nThhzR/test-run.txt`, git `92a97636c`, selection `platform tier`):

| Phase | Elapsed |
|---|---|
| snapshot | 2 s |
| test-slot acquisition | 0 s |
| dependency cache + classpath | 2 s |
| worker checkouts | 0 s |
| **published-base** | **141 s** |
| coordinator + tests | 73 s |

The 141 s published-base phase is precisely the cost the ruling removes; warm fixture
branch is recorded at 37 ms p50 (README §5, `plan/README.md:214`).

---

## 6. Population — read-only probe on `default`

Form (one `eval_clj`, jvm, read_only, 6 ms):

```clojure
(let [c (seon.cluster.boot/connection "default") db (seon.db/db c)]
  {:tests (seon.db/q '[:find (count ?e) . :where [?e :seon.test/sym]] db)
   :platform (seon.db/q '[:find (count ?e) . :where [?e :seon.test/platform _]] db)
   :destroy-owners (seon.db/q '[:find [?s ...] :where [?e :seon.fn/destroys _][?e :seon.fn/sym ?s]] db)
   :runs (seon.db/q '[:find (count ?e) . :where [?e :seon.test.run/id]] db)
   :reach (seon.db/q '[:find (count ?e) . :where [?e :seon.test/reach]] db)
   :fns (seon.db/q '[:find (count ?e) . :where [?e :seon.fn/sym]] db)})
```

| Measure | Value |
|---|---|
| test rows (`:seon.test/sym`) | **2,056** |
| function rows (`:seon.fn/sym`) | **4,462** |
| `:seon.test/platform` rows | **89** — the value is a **prose reason string**, not `true` (`:seon.test/platform true` matches nothing) |
| `:seon.test/long` rows | 119 |
| `:seon.test/fixture` rows | 6 |
| recorded runs | **nil** — nothing has ever been recorded on `default` |
| observed reach rows | **nil** — no observation, not an empty one |
| `:seon.fn/destroys` owners | **2**: `seon.test-support/populate-published-root!`, `…/populate-published-operator-root!` (the two `seon.operator/…` owners the 2026-09-21 census listed are gone with B1b) |
| tests reaching a destroyer (`seon.fn/gate-sets` over both owners, 831 ms) | 36 + 31, **union 66 = 3.2 % of 2,056** |
| attributes present on test rows | `:seon.test/{sym,ns,source,platform,long,long-ms,fixture,fixture-observation,usage}`, `:seon.fn/{calls,references,call-arities,file,form-span,keywords,writes}`, `:seon.program/analyzed-source-digest`, `:seon.schema.admission/source` — **no `:seon.db/*` member** |

**Fixture usage over `test/` (285 files, 2,069 `deftest` forms):**

| Marker | Files | Occurrences | `deftest`s in those files |
|---|---|---|---|
| `with-database` | 201 | 927 | **1,642** |
| `test-support/fork-cluster-ctx` | 99 | 229 | 1,081 |
| `with-published-file-database` | 4 | 5 | — |
| `with-branched-database` (direct) | 1 | 4 | — |
| `db/*conn*` bound by hand | 12 | 43 | — |
| `seon.db/connection` named | 133 | 482 | — |
| store-global (`fresh-store?` / `database-id` / published-file) | 10 files | — | — |

**How many test functions declare a database in their arity: zero.** `clojure.test/deftest`
produces a 0-arity Var, so custody can only arrive through the fixture or the runner's
`call-with-custody`. The ruling's "when the test's arity declares the database or
connection" therefore has **no current population** — the equivalent question is
"the 1,642 `deftest`s whose file takes the fixture's connection argument".

**Fraction that can run as agents run:** ≈ 96.8 % by the destroyer test (66 excluded);
the 10 store-global files and the 89 platform rows are the residual that keeps a host.

---

## 7. The MCP evaluation tool

| Fact | `file:line` |
|---|---|
| mode validation — exactly `{"jvm" "sci"}` | `script/seon/dev/mcp.clj:461` |
| dispatch | `script/seon/dev/mcp.clj:518-521` |
| `sci` mode reads `@@running-instances`, takes `(:seon.sci.eval/ctx instance)` — **the cluster's live shared ctx** | `script/seon/dev/mcp.clj:487-490`, ctx at `:510` |
| caps / time limit / error dial come from `(:seon.turn.loop/cluster instance)` | `script/seon/dev/mcp.clj:511-515` |
| degraded-cluster refusal when the instance has no ctx | `script/seon/dev/mcp.clj:494-503` |
| the tool description states the mutation honestly: "it MUTATES that shared per-cluster ctx, so a debug def enters the agents' world" | `script/seon/dev/mcp.clj:730` |
| `jvm` mode "binds no cluster custody" | `script/seon/dev/mcp.clj:730` |

**Can it target a candidate branch/ctx today? No.** The request schema has
`root`/`cluster`/`namespace`/`mode`/`session_id`/`timeout_ms` (`mcp.clj:737` and
neighbours) — no branch and no ctx handle. `sci` mode's ctx is resolved by cluster name
from the instance map, and the custody it carries is the cluster's own connection.

**Missing for the ruling:** a `:seon.db/branch` (or candidate-handle) member on the
request, resolved to `store/open-branch!` + `fork-cluster-ctx` — i.e. exactly the same
closure §8 names for tests.

---

## 8. Gaps and the smallest closure

The owner's constraint: **the test runner calls the SAME functions agent evaluation
calls.** Each row is labelled `agent seam must change` or `test caller must change`.
Agent-seam rows land first; no test-support wrapper reimplements any of them.

### Exists today, unchanged

| Capability | Installed function |
|---|---|
| custody as a value, binding `*conn*`/`*read-database*` | `seon.db/call-with-custody` (`db.clj:370`) |
| declared supplied-default injection of db/conn from the environment | `seon.call-preparation` hook (`call_preparation.clj:95`) + `db.clj:1807,1834` |
| fork a ctx onto a different connection, with projection, call-preparation state and contract re-arm | `seon.sci.eval/fork-cluster-ctx` (`eval.clj:2293`) |
| branch creation / retirement primitives | `seon.cluster.registry/branch!` (`registry.clj:178`), `retire-branch!` (`:327`) |
| open a connection on a roster branch of the flock-held store | `seon.cluster.store/open-branch!` (`store.clj:534`) |
| run a test Var under an armed ctx with custody | `seon.sci.eval/run-test` (`eval.clj:3208`) → `test.runner/run-vars!` (`runner.clj:656`) |
| the runner already dispatches to that agent seam when a ctx is handed | `seon.test/bounded-result` (`test.clj:141-150`) |
| write program rows to an arbitrary branch connection | `seon.fn/index!` (`fn.clj:3305`) |

### Partially there

| # | Gap | Label | Smallest change | Owning row |
|---|---|---|---|---|
| P1 | Two spellings of one custody scope: `db/call-with-custody` (runner) and open-coded `with-bindings` in `evaluate` | **agent seam must change**: `seon.sci.eval/evaluate` calls `db/call-with-custody` instead of open-coding `#'db/*conn*`/`#'db/*read-database*` at `eval.clj:2826-2832` | one call-site conversion inside `evaluate` | B2 §2a / B4 c2 |
| P2 | `fork-cluster-ctx` has no production caller, so the connection-repointed fork is unproven on the agent path | **agent seam must change**: D1's candidate handle acquires its ctx through `fork-cluster-ctx`, making it the one production fork-for-another-connection | wire `fork-cluster-ctx` into candidate acquisition; delete `test-support/fork-cluster-ctx`'s base-lease wrapper, keeping only the environment derivation it needs as an argument | D1 §2a / plan row 1.4c |
| P3 | The fixture opens its branch with a bare `d/connect` on `(assoc (:config database) :branch b)` rather than the installed `store/open-branch!` | **test caller must change**: pass the cluster's `:seon.store/store` and call `store/open-branch!` | `test_support.clj:712-716` (working tree) | B4 c1 |
| P4 | `seon.test/run` is `[test-var connection options]`, not one request | **test caller must change** | B4 §6's `run` request shape | B4 c2 |

### Missing, in dependency order

| # | Gap | Label | Smallest concrete change | Owning row |
|---|---|---|---|---|
| M1 | Nothing compares a context's program to the JVM's loaded commit; `:core` rows always bind the JVM Var (`eval.clj:894-895`) | **agent seam must change**: `install-row!` takes the context's `:seon.source/commit-id` and computes `overridden` | one predicate over `:seon.program/definition-digest` (landed 1.2, `f27b96c19`) beside the admission check at `eval.clj:894` | B2 §2a step 1 |
| M2 | No `affected` reverse closure, so a copied JVM caller still calls the JVM callee | **agent seam must change** | `seon.fn/gate-sets` (`fn.clj:1505`) already computes the reverse walk; call it on the `overridden` seed set inside `acquire-program!` (`eval.clj:1709`) | B2 §2a step 2 |
| M3 | No per-context interpretation of the `overridden ∪ affected` set; no refusal when a member cannot be interpreted | **agent seam must change** | route that set through the existing `install-function-from-database!` (`eval.clj:709`) and turn the silent `:jvm-fallback` at `eval.clj:909` into a named refusal | B2 §2a step 3; README §7 "affected caller SCI cannot interpret" |
| M4 | No adoption-boundary invalidation: a shared `require :reload` (`cluster.clj:1984`) changes loaded behavior under every retained fork with no notification | **agent seam must change** | at each retained context's next boundary, enter the adoption's identities into `overridden(X)` when X's program did not change | B2 §2a step 5 |
| M5 | `publish!` writes only to `:current-src` (`source.clj:29`) | **agent seam must change**: make the target branch a request member | `current-branch` becomes `(:seon.source/branch request)` defaulting to `:current-src`; the two head-flip sites at `source.clj:461-479` take it | B1 c2–c10 / plan 1.2b |
| M6 | No reach from "a lane published to its branch" to "the shared JVM must NOT reload" | **agent seam must change**: `refresh-source!`'s `development-source-refresh!` half is opt-in per request, never implied by publication | split `refresh-source!` so `publish!`-to-a-branch and `development-source-refresh!` are separate requests | B1 c12 / plan 1.5 |
| M7 | MCP `sci` mode cannot name a branch or a candidate ctx (`mcp.clj:510`) | **agent seam must change** (the same handle D1 builds) | add a branch/handle member resolved through `store/open-branch!` + `fork-cluster-ctx` | B1 (MCP tool) with D1 §2a |
| M8 | No retirement path a *test* can call: the fixture open-codes `d/delete-branch!` instead of `registry/retire-branch!` | **test caller must change** after P3 | `test_support.clj:722-726` → `registry/retire-branch!` | B4 c1 |

**Order.** M1→M2→M3 are one loadable slice (the eligibility rule); M4 follows it; M5/M6
are B1's publication slice and are independent; P1/P2 are cheap and unblock everything
because they make one function the shared seam; P3/P4/M8 are the test-caller conversions
that must come last. M7 rides M5+P2.

**Dependency seams the closure uses** (§9 has the lifecycle detail):
`reference-code/datahike/src/datahike/versioning.cljc:212` (`branch!`), `:279`
(`delete-branch!`), `:323` (`force-branch!`), `:457` (`commit-id`), `:469`
(`commit-as-db`);
`reference-code/datahike/src/datahike/connections.cljc:5,37,94,124`;
`reference-code/sci/src/sci/core.cljc:345` (`fork`), `:112` (`copy-var*`), `:273`
(`bind-root!`), `:679` (`add-namespace!`), `:837` (`resolve`).

---

## 9. The fork lifecycle, seam by seam

Each step: the dependency seam, the installed Seon caller (or "none"), the cost, and
the change label.

### (a) Create the branch off a captured commit

| Element | `file:line` | Seon caller |
|---|---|---|
| `branch!` — roster permit acquired **before** source or roster is read, released after head + roster publication; `from` may be a branch keyword or a commit UUID | `reference-code/datahike/src/datahike/versioning.cljc:212-240` | `seon.cluster.registry/branch!` (`src/seon/cluster/registry.clj:178`) — "the one primitive every other creation in the system is built from" |
| the reachability/roster permit | `versioning.cljc:228-233` | passed through as `:datahike.gc-guard/reachability-permit` (`registry.clj:197`) |
| commit-source branching requires `:commit-graph?` (defaults true) | `versioning.cljc:237-240` | `registry.clj:184-187` documents the dependence |
| duplicate name is idempotence, not failure | `versioning.cljc:233-235` cited at `registry.clj:190-193` | `registry.clj:198-200` |
| `commit-id` of a captured db value | `versioning.cljc:457` | `test_support.clj:704` (working tree) |
| `d/connect` on a branch config — one connection per `(store, branch)` per process, refcounted | `connections.cljc:5` (`active-connection`), `:37` (`reserve-connection-opening!`), `:94`, `:124` | `seon.cluster.store/open-branch!` (`src/seon/cluster/store.clj:534`), which refuses `::branch-absent` (`:555`) and `::branch-already-open` (`:560`) precisely because Datahike would silently share one writer |

**Cost:** D1 §2a records branch **74.65 ms** and open **25.62 ms** (Codex REPL
verification, 2026-09-21). Not re-measured here: `branch!` is a write, outside this
pack's read-only boundary. `registry/roster` and `branch-commit-id` reads are O(1).

**Label:** exists. **test caller must change**: the fixture must call
`store/open-branch!` (P3), not a bare `d/connect`.

### (b) Hand the branch's connection into a forked SCI context

| Element | `file:line` | Seon caller |
|---|---|---|
| `sci/fork` — new `:env` atom, new generation; copy-on-write Vars | `reference-code/sci/src/sci/core.cljc:345-351` | `eval.clj:2127` (`fork-for-turn`), `:2325` (`fork-cluster-ctx`) |
| `copy-var*` — root value only, **not** the dependency graph | `reference-code/sci/src/sci/core.cljc:112-140` | `eval.clj:823` (`install-jvm-root!`) |
| `bind-root!` on a fork copies an inherited Var before mutation | `reference-code/sci/src/sci/impl/utils.cljc:362-379` (cited by B2 §2a) | `eval.clj:697` (`install-function-contract!`) |
| the custody rewrite | `eval.clj:2332` | `fork-cluster-ctx` only |
| the binding the body actually sees | `db.clj:391` / `eval.clj:2826` | `runner.clj:687` / `evaluate` |

**Cost, measured read-only on `default`:** `sci/fork` **0.00123 ms**;
`fork-cluster-ctx`'s contract re-install loop is O(interpreted rows) and is **0 rows** on
`default` today.

**Label:** exists but test-only. **agent seam must change** (P2): make
`fork-cluster-ctx` the production fork-for-another-connection via D1's candidate handle,
so the test caller and the candidate call one function.

### (c) Run one test body there, carried projection, armed contracts

| Element | `file:line` |
|---|---|
| arm the ctx for the bound | `src/seon/sci/eval.clj:3204` (`kernel/with-arm`) |
| the agent entry | `src/seon/sci/eval.clj:3208` (`run-test`), `:3192` (`run-tests`) |
| the capture + custody application | `src/seon/test/runner.clj:656`, custody `:687` |
| the dispatch that already prefers the agent seam | `src/seon/test.clj:141-150` |
| the projection carried into execution | `src/seon/test.clj:443-446` (`schema/call-with-projection` around `bounded-result`) |
| the agent's own precedent — a gate test runs on the agent's connection through the same seam | `src/seon/sci/eval.clj:3236-3245` |

**Label:** exists. **test caller must change** only in the request shape (P4).

### (d) Retire the fork

| Element | `file:line` | Seon caller |
|---|---|---|
| `delete-branch!` — typed refusals: `:cannot-delete-main-db-branch` for `:db`, `:branch-does-not-exist`, `:branch-has-active-connection` (release first) | `reference-code/datahike/src/datahike/versioning.cljc:279-321` (refusals at `:283`, `:305`, `:313`) | `seon.cluster.registry/retire-branch!` (`registry.clj:327`), which converts `:branch-does-not-exist` into the success path for a re-run (`registry.clj:331-334`) |
| "unlinked" = removed from the roster; data survives until GC | `versioning.cljc:280-282` ("still be accessible until the next gc"); `registry.clj:328` | — |
| the roster itself | `versioning.cljc:174` (`update-branches-held!`), `:182` (`branches`) | `registry.clj:107` (`roster`) |
| `reachable-in-branch` — marks every roster branch head and follows parents while their timestamps exceed `remove-before`; an unlinked branch's head is no longer a root, so its ancestry becomes collectable | `reference-code/datahike/src/datahike/gc.cljc:22-81` | cited at `registry.clj:339-342` |
| `gc-storage!` — sweep options pass through under the reachability permit and a separate writer safe point | `reference-code/datahike/src/datahike/gc.cljc:83-100`, `:166` | `seon.cluster.registry/collect!` (`registry.clj:503`) |
| the retention cutoff (landed today, `289c9b587`) — derived from captured branch heads + `:seon.config.db/snapshot-window-ms`; **refuses** when any cluster configuration has not declared it | `src/seon/cluster/registry.clj:472-501`, refusal at `:494-498` | — |
| dry-run inventory (landed today, `1cc00a4a5`) — same denominator as the real sweep | `src/seon/cluster/registry.clj:503-520` | measured 8–11 ms on a 9-key scratch store (`landing/lane-a2-storage-retention-2026-09-22.md:41-47`) |
| the fixture's current retirement | `test/seon/test_support.clj:722-726` — bare `d/branches` + `d/delete-branch!`, **not** `registry/retire-branch!` |

**Label:** the primitives exist as installed Seon functions. **test caller must change**
(M8): call `registry/retire-branch!`. Nothing needs to be built for "unlink then GC
later" — that is already what `retire-branch!` + `collect!` mean.

### (e) Cost, and what a branch does NOT isolate

| Step | Cost | Source |
|---|---|---|
| `registry/branch!` | 74.65 ms | D1 §2a (Codex, 2026-09-21); write, not re-measured |
| `store/open-branch!` | 25.62 ms | D1 §2a |
| `sci/fork` | **0.00123 ms measured 2026-09-22** (100 iterations on `default`'s live ctx) | this pack |
| `fork-cluster-ctx` contract re-arm | O(interpreted rows); **0** on `default` | this pack |
| warm fixture branch, end to end | 37 ms p50 | `plan/README.md:214` |
| whole-JVM alternative (what this replaces) | 141 s published base + 73 s run | `tmp/test-runs/run.nThhzR/test-run.txt` |
| `registry/collect!` dry run | 8–11 ms on a 9-key store | `landing/lane-a2-storage-retention-2026-09-22.md:44-46` |

**A branch does NOT isolate** (A2 §2 row j, `plan/lane-a2-datahike-one-answer.md:118`,
verified against the seams): konserve blobs (`bassoc` keys are store-scoped), the
`:branches` roster itself, GC reachability, the OS `flock`
(`store.clj` `acquire-flock!`), and every JVM global — `datahike.connections/*connections*`
(`connections.cljc:5`), loaded classes, SCI base Vars, the Malli registry and
instrumentation state. A test that needs an independent store needs a store, not a
branch: the 10 store-global files in §6 are that population.

### Verdict

- **Installed and already called by Seon:** `registry/branch!`, `store/open-branch!`,
  `registry/retire-branch!`, `registry/collect!` + `retention-cutoff`,
  `db/call-with-custody`, `sci.eval/run-test`, `test.runner/run-vars!`, `seon.fn/index!`.
- **Installed but with no production caller:** `sci.eval/fork-cluster-ctx` — the one
  connection-repointing fork. It must become the agent path's own function (P2) before
  any test uses it.
- **Only in the dependency, never wrapped:** nothing required by this lifecycle —
  `sci/fork`, `copy-var*`, `bind-root!`, `versioning/branch!`, `delete-branch!`,
  `gc/reachable-in-branch`, `gc-storage!` all have first-party owners.
- **Missing entirely:** the loaded-vs-stored eligibility rule (M1–M4), a branch-targetable
  `publish!` (M5), publication without implied JVM reload (M6), and a branch-addressable
  MCP evaluation (M7). Every one of those is an **agent seam** change, and every one lands
  before a single line of test code moves.
