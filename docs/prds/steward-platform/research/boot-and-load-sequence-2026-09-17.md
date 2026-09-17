---
type: research
status: open
created: 2026-09-17
tags: [boot, adoption, publication, load, operator]
---

# The boot and load sequence, measured — 2026-09-17

Owner's order: "keep fixing the boot/load sequence as the problems likely are
bigger and we need to chase these down." This traces `bin/seon start`,
publication, development adoption and the test workers' base construction end
to end against today's three operator runs, and names every point that refused
or waited unbounded, with `file:line` or a log line number for each claim.

Evidence: `tmp/orchestrator/reset-2026-09-17-fresh.log` (pid 55779, green),
`tmp/orchestrator/reset-2026-09-17-second.log` (pid 72356, refused at start),
`tmp/orchestrator/start-after-reset-2.log` (pids 80532/80531, start green,
`init --dev` refused), `data/operator/operations/reset-start-72356.log`,
`data/clusters/default/logs/seon.log`,
`tmp/orchestrator/post-reset-stale-fixtures-2-stdout.log` (two `jstack` reads).

## 1. The measured sequence

### 1a. Operator phases (elapsed-ms as printed by `fresh_operator.clj:295-304`)

| Phase | What it does | file:line | Fresh reset 55779 | Failed reset 72356 | Recovery 80532/80531 |
|---|---|---|---|---|---|
| preflight | `git diff`/`ls-files` over `src script resources test config`, then clj-kondo over the changed files | `script/seon/fresh_operator.clj:313-400` | 38, 33 | 37, 33 | 218, 197 (start); 266, 167 (init) |
| lifecycle | acquires the root lifecycle lock for the whole transition | `resources/seon/operator/state.clj:467-544` | 188,092 total | 176,478 total | 29,909 (start) / 212,535 (init) |
| down | stops recorded JVMs by exact (pid, start-instant) | — | 170 (0 records) | 851 | — |
| destroy | deletes `data/store` + `data/store.lock` under the root lock | `seon.operator.state/cleanup-root-under-lock!` (log line 13) | 137 (reclaimed 113,616,101 B) | 1,477 | — |
| republish | child JVM: complete `current-src` publication | `fresh_operator.clj:2726-2760` | 111,419 | 120,860 | 135,873 (as `init`, refused) |
| refork | `bin/seon init default --force` — forks the published commit | — | 18,780 | 26,814 | — |
| start | child JVM boot to `ready` | `src/seon/cluster.clj:3481` | 18,745 | 26,412 (**refused**) | 29,685 |
| adopt | `init --dev default` in the running JVM | `src/seon/cluster.clj:2463` | 38,783 | never ran | refused inside init |

### 1b. Publication sub-phases (`refresh-source!`, `src/seon/cluster.clj:2463-2560`)

Progress lines are `#:seon.source{:progress … :elapsed-ms …}`; the ms belongs
to the *completed* phase named in the same line.

| Progress line | Fresh (log 24-31, 623-643) | Failed (26-33, 623-643) | Recovery 1st attempt (40-46, 638-659) |
|---|---|---|---|
| publication preparation → request accepted | 5,392 | 4,961 | 0 |
| store acquisition | 4 | 3 | 2 |
| source build + snapshot | 188 | 145 | 4 |
| analysis (368/369 source inputs) | 10,178 | 10,446 | 0 (cached) |
| schema population | 707 | 838 | 673 |
| contract projection (2,869-2,870 schemas, 1,189-1,191 functions) | 1,791 + 567 | 2,125 + 591 | 1,209 + 583 |
| contract rows (10,563 / 10,564 / 10,571) | 8,640 across 6 strides | 8,665 | 10,269 |
| program population compiled (28,125 entities, 21,659 identities, 37,932 keyword facts) | 4,731 | 4,720 | 4,901 |
| population (87,716 / 87,745 / 87,778 rows) | 13,920 | 15,019 | 16,491 |
| publication issue indexing | 773 | 830 | 206 |
| publication test evidence | 2,817 | 2,918 | 2,447 (+1,600 tx) |
| publication activation seal | 0 | 0 | 3,464 |
| publication branch head | 2,895 | 3,122 | 4,453 |
| branch publication complete | 3,584 | 3,796 | 9,399 |

Two phases dominate: **row population (14-16.5 s)** and **contract rows
(8.6-10.3 s)**; analysis adds 10 s whenever the manifest cache misses.

### 1c. Boot phases inside the cluster JVM

`boot-phase` derives the phase from what stands on the instance
(`src/seon/cluster.clj:226-238`); the printed order is
`namespaces → repl → store → branch → recovery → config → program →
work-launcher → agents → web → ready`.

| Phase | Stands | file:line |
|---|---|---|
| repl | io-prepl opened BEFORE store acquisition | `cluster.clj:3481` (start!) |
| store | `flock`-held root Datahike store | `cluster.clj:3406` |
| branch | registry fork + provisional branch connection; `require-admissible-branch!`; schema accretion | `cluster.clj:3410-3476` |
| recovery | closes open turns, marks unfinished evaluations interrupted | `cluster.clj:3323` |
| config | manifest reconciled into facts | `cluster.clj:3325-3328` |
| **program** | `sci.eval/cluster-ctx` / `fork-cluster-ctx` — **loads every core-provenanced namespace** | `cluster.clj:3337-3342` → `src/seon/sci/eval.clj:1193-1207` |
| work-launcher / agents / web / ready | flow launcher, `arm-agents!`, web server | `cluster.clj:3361-3391` |

Fresh reset (log line 676) reached `ready` in 18,745 ms and instrumented
1,203 vars (`data/clusters/default/logs/seon.log:29`).

### 1d. Development adoption sub-phases (`init --dev`)

| Progress line | Fresh (log 1276-1649) | Recovery (log 660-1029) |
|---|---|---|
| development schema declarations | — | 2,376 |
| development program reconciliation | 444 | 223 |
| development published rows read | 1,323 | 1,312 |
| development reconciliation transaction | 23 | 25 |
| development changed definition comparison | 3,696 | 6,933 |
| development issue reconciliation | 3,650 | 3,938 |
| development loaded definitions | 3,677 | 4,437 |
| `development reload <ns>` × ~340 namespaces | ≈2,400 summed | ≈2,300 summed |
| development JVM instrumentation | 14 | — |
| development SCI acquisition | 1,569 | 1,863 |
| development source verification | 1,104 | 673 |
| development adoption record | 99 | — |
| development cluster converged | 204 | — |

The hold bound is no longer a total: it is liveness of the holder's published
phase (`resources/seon/operator/state.clj:467-544`, landed `c772db2d3`), which
is why 38.8 s adoption succeeds where the 181 s total bound used to refuse
(prior art: `docs/prds/steward-platform/plan/unsettled.md:2556-2680`).

**Adoption reloads test namespaces too**: the fresh reset's reload list
includes `seon.ai.tokens-test`, `seon.test-support-test`, `seon.turn-test`
(log lines 1289, and the tail of the file). It does **not** reload namespaces
under `resources/` — `seon.operator.state` lives at
`resources/seon/operator/state.clj`, which is the open blocker
`docs/seon/issues/a-first-party-namespace-under-resources-is-never-reloaded-by-development-adoption.md`.

## 2. Every refusal and wait point observed today

### R1 — Boot requires every test namespace; one lane's in-flight test file took the cluster down (class: test-namespace load refusing boot + in-flight tree edit)

`load-core-namespaces!` (`src/seon/sci/eval.clj:1193-1207`) requires every
namespace with a `:core` admission row. `seon.fn/source-roots` is
`["src" "test"]` (`src/seon/fn.clj:27-29`), so every test namespace is such a
row. `host-namespace!` (`:1154-1191`) guards with `classpath-locatable?`
(`:1145-1152`), whose docstring asserts "a cluster JVM runs `-M:dev`, whose
classpath carries no `test/`" (`:1122-1124`).

**That assertion is false.** `launch!` passes the dependency cache's
`:seon.dev-cache/test-classpath` (`script/seon/fresh_operator.clj:2078`) and
`child-jvm-command` turns it into `-Scp <full test classpath>`
(`:469-473`). That classpath's roots begin
`[… "test" "script" "." "src" "resources" …]`
(`target/test-classpaths/cb2f2fdc….basis.edn`). So the cluster JVM's system
classloader carries `test/`, every test namespace is locatable, and boot
requires all of them.

Result: `tmp/orchestrator/reset-2026-09-17-second.log:673` —
`The cluster instance failed above the REPL: First-party program namespace
seon.test-support-test could not be loaded for the evaluation context`,
`:seon.error/kind :seon.boot/refused`, after `boot phase: config`
(`data/clusters/default/logs/seon.log:3-12`). A concurrent lane's edit to
`test/seon/test_support_test.clj` (an unrequired alias) refused the whole
reset. This is the SECOND instance of the class: `unsettled.md:941` records
the same shape for `seon.operator-test` on 2026-09-16.

The preflight cannot catch it: the reset preflight linted **syntax only**
(`^:replace {:linters {:syntax {:level :error}}}`, the pre-change form, still
visible as `git diff script/seon/fresh_operator.clj`), and both preflights
had run minutes earlier (log lines 1-7), before the lane's write.

### R2 — The refusal keeps no cause (class: evidence capping / diagnostic loss)

`host-namespace!` wraps the real compile failure as the `cause` of its
`ex-info` (`src/seon/sci/eval.clj:1178-1187`), but nothing prints or stores
it: `data/clusters/default/logs/seon.log:12` and
`data/operator/operations/reset-start-72356.log:2-3` carry only the outer
sentence and the operator's own SCI stack. The diagnosing agent gets the
namespace name and nothing about *why* it would not load. Same class as the
boot fault recorded on the fresh cluster —
`seon.agent/supervision-not-committed` from
`seon.cluster.agent/armer-step` (`src/seon/cluster/agent.clj:985-996`) whose
`:seon.error/data` was capped to 7,820 bytes
(`docs/seon/issues/an-agent-turn-proc-dies-on-every-pass-and-oversight-still-reports-it-armed.md`).
Live confirmation (one read-only MCP evaluation against `default`, basis-t
536871050): five fault kinds present — `seon.cluster.reply/no-forms`,
`seon.turn.loop/write-refusals-exhausted`, `seon.db/invalid-read`,
`seon.sci.eval/documentation-unavailable`,
`seon.turn/generated-read-depends-on-turns` — and **every one has no
`:seon.error/message` and no `:seon.error/fn` attribute**.

### R3 — The recovery `init --dev` was refused by an attribute-blind tempid rewrite (class: in-flight schema shape change + pre-read the authority re-decides)

`tmp/orchestrator/start-after-reset-2.log` (tail):

```
Program indexing transaction was refused. seon.db/transact! refused
transaction data at [35742 :seon.schema/references]: expected a set, got a
set. … :offending #{"seon.fn.index/17904"}
```

`:seon.schema/references` is declared `[:set … :qualified-keyword]`
("Canonical schema keys directly referenced by this schema. Names survive
retraction." — `resources/seon/schemas/seon.schema.edn:75-77`, retyped today
by `0e8f7d323`). The offending member is a **transaction tempid string**
minted by `index-tempids` (`src/seon/fn.clj:2388-2390`,
`(str "seon.fn.index/" index)`).

Cause: `rewrite` in `compile-index-transaction`
(`src/seon/fn.clj:2415-2455`) substitutes a tempid for **any** two-element
vector that matches a known `[identity-attribute value]` pair
(`lookup-tempid`, `:2415-2418`), and recurses into every set
(`:2453`). It never asks whether the attribute it is under is a
`:db.type/ref`. While `:seon.schema/references` still held `[attr value]`
pairs — the shape a concurrently edited fixture was mid-migration from
(`test/seon/cluster/registry_test.clj`, diff at
`tmp/orchestrator/post-reset-stale-fixtures-2-stdout.log:6036-6045`) — every
member was rewritten into a tempid string, and the write authority refused.
This is exactly the AGENTS.md §7 rule "a schema resource and its loaded
consumer land in one publication", meeting the value-versus-ref discriminator.

The refusal text is itself a defect: **"expected a set, got a set"** —
`:expected-description` and `:actual-description` both describe the container,
so the message names neither `:qualified-keyword` nor the offending member.

### R4 — Every Seon write is an unbounded wait (class: unbounded wait)

`jstack` of the wedged fast runner
(`tmp/orchestrator/post-reset-stale-fixtures-2-stdout.log:5998-6027`):

```
"main" … cpu=61194.85ms elapsed=134.61s … WAITING (parking)
  CompletableFuture.waitingGet … datahike.tools$throwable_promise…deref
  datahike.api.impl$transact … seon.db$transact_call … seon.db$transact_BANG_
```

`seon.db/transact-call` calls `d/transact` (`src/seon/db.clj:3736`);
`datahike.api.impl/transact` is `@(dw/transact! …)` with no timeout
(`reference-code/datahike/src/datahike/api/impl.cljc:44-46`). The promise
*does* expose a bounded arity (`IBlockingDeref`,
`reference-code/datahike/src/datahike/tools.cljc:105-109`) — Seon simply does
not use it. Every publication row batch, every adoption transaction, every
fixture write is therefore an unbounded park: a stalled writer becomes a
silent wedge with no phase line and no bound firing. AGENTS.md §2.3.

### R5 — The publication monitor is an unbounded lock (class: unbounded wait / load contention)

`refresh-source!` wraps its whole body in `(locking source-refresh-monitor …)`
(`src/seon/cluster.clj:2465`) after reporting `"request accepted"`
(`:2464`) and before `"bootstrap configuration"` (`:2466`). A publication
holds it for 111-136 s today (§1a). A second request in the same JVM parks
inside `locking` with no bound, no progress line and no holder announcement —
it is stuck at `request accepted` forever. The 5,392 ms charged to
`publication preparation` on the fresh reset (log line 24) versus 0 ms on the
later ones is the only visible signal that anything waits here.

### R6 — Schema canonicalization is the setup cost, and it is O(values) × `satisfies?` (class: load contention / recomputation)

Second `jstack` (`…post-reset-stale-fixtures-2-stdout.log:13359-13400`):
`main`, RUNNABLE, 44.3 s CPU in 96.9 s elapsed, entirely inside
`seon.schema/canonical-value-string` → `canonical-coll-string` → itself, with
the leaf frames in `clojure.core/inst?` → `satisfies?` →
`find-protocol-impl` → a linear `filter`.

`canonical-value-string` (`src/seon/schema.clj:614-651`) tests `inst?`
(`:631`) **before** `vector?`/`set?`/`map?`/`sequential?` (`:634-645`), so
every node of every nested Malli form pays an uncached protocol lookup.
It is reached from `projection-fingerprint` (`:675-704`), which canonical-
strings all 2,870 schema forms plus 10,571 function contracts each time a
projection is derived (`:1950-1953`).

**It is the same code path boot uses**: boot derives at
`cluster.clj:3446` / `:3464` (`schema/projection-from-database`,
`src/seon/schema.clj:2570-2590`); the fixtures derive at
`test/seon/test_support.clj:171`, `:429`, `:769`, `:952`. The documented
reuse path "avoids recompilation only when its canonical fingerprint equals
the queried rows" (`schema.clj:2574-2576`) — i.e. the check costs the walk it
is meant to avoid. Under load average 10-17 that walk is what the 290 s test
bound expired on.

### R7 — A source change during adoption reruns the entire publication (class: in-flight tree edit)

`retrying-source-change` (`src/seon/cluster.clj:634-659`) retries the whole
`attempt` once. On the recovery run it fired at
`start-after-reset-2.log:1029` (`source changed during adoption; retrying
publication once`) and re-ran analysis (9,782 ms), schema population, contract
projection, all 10,571 contract rows and the full compile — ~60 s — before
reaching R3. With several lanes editing the tree, one publication can spend
two full passes and still refuse.

### R8 — Preflight lint gap (fix already in flight, uncommitted)

The old `syntax-preflight!` linted syntax only, which is why R1 got through.
The working tree already carries the replacement: `source-preflight!`
(`script/seon/fresh_operator.clj:313-400`) lints with
`boot-refusing-linter-config` — `:syntax`, `:unresolved-namespace`,
`:unresolved-var`, `:unresolved-symbol` all at `:error`
(`:252-257`) — after preparing the clj-kondo dependency cache
(`:356-370`). It is **uncommitted** (`git status`: `M
script/seon/fresh_operator.clj`, 182 insertions). Today's logs still print the
old `changed-source syntax checked` line, so no run has exercised it yet.

### R9 — Cache churn from non-source inputs (class: stale cache)

The dependency-cache / test-classpath input digest includes
`.agents/skills/*/SKILL.md` and other non-Clojure files
(`target/test-classpaths/cb2f2fdc….inputs.edn`, first entries). A
documentation-only edit therefore moves the digest. Related: the classpath
carries `"."` — the whole checkout, the recursion trap AGENTS.md §5 warns
about for clj-kondo.

## 3. Ranked fix list

Ranked by how often the defect takes the cluster down or wedges a lane.

| # | Fix | Seam (file:line) | Size | Regression that proves it | Owner |
|---|---|---|---|---|---|
| 1 | Boot must not require test namespaces. Either drop `test/` from the cluster JVM's `-Scp` (`script/seon/fresh_operator.clj:469-473`, `:2078`) or make `load-core-namespaces!` select by the *process's declared* source roots rather than mere locatability (`src/seon/sci/eval.clj:1193-1207`, guard at `:1145-1152`). Correct the false docstring at `:1122-1124` either way. | see left | M | A boot fixture whose tree contains one deliberately unloadable `test/` namespace reaches `ready`; the same file under `src/` still refuses. | sol lane, after the owner picks §4 |
| 2 | Bound the write. `seon.db/transact-call` derefs `dw/transact!` with the declared bound and reports a typed refusal naming the connection and the elapsed wait, instead of `d/transact` (`src/seon/db.clj:3736`). | `src/seon/db.clj:3707-3760` | M | A fixture whose writer never delivers refuses within the declared bound with a typed value naming the stalled transaction — not a hang. | sol lane (db.clj is concurrently edited; schedule after) |
| 3 | Bound and announce the publication monitor: replace `(locking source-refresh-monitor …)` with a bounded acquisition that names the current holder and its published phase, as the lifecycle lock already does (`resources/seon/operator/state.clj:467-544`). | `src/seon/cluster.clj:2463-2466` | S | Two concurrent `refresh-source!` calls: the second refuses within its bound naming the holder's phase. | sol lane |
| 4 | Make the tempid rewrite attribute-aware: substitute only under attributes the installed schema declares `:db.type/ref`; a value attribute's members pass through untouched. | `src/seon/fn.clj:2415-2455` (+ `index-tempids` `:2367-2391`) | M | A population whose value attribute holds a two-element vector matching a known identity publishes that vector verbatim. | sol lane |
| 5 | Refusals carry their cause. `host-namespace!` includes the underlying message/location in its `ex-info` data and the operator prints it (`src/seon/sci/eval.clj:1178-1190`); the set/collection refusal names the failing *member* and its expected element schema, not the container (the `expected-description`/`actual-description` producer); fault entities keep a message. | `sci/eval.clj:1178-1190`; the error-description producer; `src/seon/cluster/agent.clj:985-996` | S each | A boot against an unloadable namespace prints the compile error's file, line and message; a bad set member's refusal names `:qualified-keyword` and the member. | sol lane (small, 3 slices) |
| 6 | `canonical-value-string`: test the collection predicates before `inst?`/`uuid?`/`char?`, and use `(instance? java.util.Date …)`/`Instant` rather than `inst?`'s `satisfies?`. | `src/seon/schema.clj:614-651` | S | A property test proving the encoding is byte-identical before and after, plus a measured projection-fingerprint timing in the note. | sol lane (schema.clj concurrently edited) |
| 7 | The adoption retry re-uses the analysis and population it already computed for the unchanged files, instead of re-running the whole attempt. | `src/seon/cluster.clj:634-659` | M | A publication interrupted by a one-file change completes its retry without a second full analysis. | orchestrator decision first (cost vs. simplicity), then sol |
| 8 | Move `seon.operator.state` (and any other first-party namespace) out of `resources/` into `src/` so adoption reloads it. | `resources/seon/operator/state.clj` → `src/seon/operator/state.clj` | S | Adoption of an edited `seon.operator.state` changes the loaded Var. | sol lane (the open blocker issue already specifies it) |
| 9 | Land the preflight upgrade already in the tree and give it a regression. | `script/seon/fresh_operator.clj:252-400` | S (land) | A reset preflight refuses a changed file carrying an unresolved namespace, naming file:row. | whoever holds the file |
| 10 | Key the dependency-cache / test-classpath inputs on Clojure source and manifests only; drop `.agents/skills/*.md` and the `"."` root. | `dev_cache.clj:490-520` | S | A docs-only commit leaves the cache digest unchanged. | orchestrator decision (it interacts with `--prepare-head-base`) |

## 4. Open design choice for the owner — should boot load test namespaces at all?

Simplest first.

**Option A (recommended) — the cluster JVM stops carrying `test/`.**
Drop `:seon.fresh-operator/test-classpath` from `launch!`
(`script/seon/fresh_operator.clj:2078`) so a cluster runs `-M:dev` as its own
docstring already claims. `classpath-locatable?` then answers `false` for
every test row by construction, no predicate changes, and `seon.test/run`
keeps its explicit `with-test-loader` for in-process runs. Guarantee: a broken
test file can never refuse a cluster boot. Cost: an in-process `seon.test/run`
from a cluster JVM must supply its own loader (it already does). Give up:
nothing that today's design claims.

**Option B — boot records a fault instead of refusing.**
Keep the classpath, but make `host-namespace!` record an unloadable
first-party namespace as a durable fault with its cause and continue, refusing
only for namespaces the *agent surface* needs. Guarantee: boot always reaches
`ready`; the breakage is a query. Cost: the base SCI context's membership is
no longer exactly the graph's, which is the silent-fallback shape §2.4 names —
so the fault must be loud on the debug page. Give up: "the ctx's membership is
exactly the graph's".

**Option C — the program graph stops admitting `test/` as `:core`.**
Change `seon.fn/source-roots` (`src/seon/fn.clj:27-29`) so test rows carry
their own admission, and have `load-core-namespaces!` select on it. Guarantee:
the strongest — the graph itself says which namespaces are executable
cluster-side. Cost: L; touches indexing, selection (`gate-set`), test reach
and every fixture that assumes one admission. Give up: the current uniform
"first-party source is first-party source" model.

A and C are not exclusive: A is the one-line stop-the-bleeding, C is the model
fix if the owner wants the graph to state it.

## 5. What I could not verify (unknown)

- **The real cause of R1.** The compile error inside
  `test/seon/test_support_test.clj` at 11:46Z is not recorded anywhere I can
  read; the file has since been edited. I am relying on the orchestrator's
  account of "an unrequired alias". The load refusal's own evidence does not
  contain it (that is R2).
- **Whether R4 has ever actually fired in production boot**, as opposed to in
  the fast runner. I have one `jstack` showing the unbounded park; I did not
  run anything, so I cannot say how often a cluster write stalls.
- **Whether the R6 reorder is byte-compatible** with existing stored
  fingerprints. It should be (the tag set is unchanged, only the dispatch
  order moves) but a `:db/instant` that is also `sequential?` would change —
  I did not enumerate the value space.
- **Whether R3 is fully dissolved by `0e8f7d323`.** The attribute is now
  `:qualified-keyword` at HEAD, so the specific pair shape is gone; the
  attribute-blind rewrite (fix 4) remains, so the class is not.
- **Current adoption state of `default`.** My one read-only evaluation found
  the cluster alive at basis-t 536871050 with one agent (`root`) and **no
  `:seon.source/commit-id` on the branch** — consistent with the `init --dev`
  refusal in `start-after-reset-2.log`, i.e. the running `default` is
  unadopted. I did not run `bin/seon status` to confirm independently.
- **Timings under a quiet machine.** Every number above was measured under
  concurrent lanes (load 10-17 for the hang evidence); none is a floor.
