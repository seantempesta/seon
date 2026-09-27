# Fault resolution design — 2026-09-27 (revision 2: Astra c2534eef9 REVISE answered)

Lane `fault-design-revise-opus`, answering Astra's review
([review-vector-and-fault-resolution-2026-09-27.md](review-vector-and-fault-resolution-2026-09-27.md) §2, F1–F3) and
root's rulings: F1–F3 accepted; Q1 = (a) task EDN carries the fault signatures as values; Q2 = (a) any open fault
means `:faulted`, plus an untasked count; Q3 = an evidence-based cutover per F2, never a blanket `resolve!`.
Revision 1 was 5c01dde0a (lane `fault-resolution-design-opus`, stability sweep finding 3,
[stability-sweep-2026-09-27.md](stability-sweep-2026-09-27.md) §3, §10). This revision replaces it in place.

Probes ran on `default`, **pid 89589** (started 18:34:08Z, archive b7e148de6), from 18:48Z to 19:05Z. They were
read-only JVM-mode forms in the scratch namespace `tmp.fault-rev`. Nothing was written to any database. The forms are
in §9. Source citations are at HEAD c2534eef9, which includes 8169f74d1 (fault regressions), 152ceb62c
(`open-faults` reads the attached resolution; `deferred-agents`) and 11fc3a967 (task files).

## What exists and its data flow

- `seon.fault/fault!` → `seon.fault/record!` (`src/seon/fault.clj:64`) → `seon.error/prepare`/`commit-call` upserts
  one `:seon.error/error` entity per signature. Each occurrence names its process as
  `:seon.error.occurrence/process [:seon.db.process/id process]` (`error.clj:1485`). The entity carries
  `:seon.error/declared-schema`, `:seon.error/operation` and `:seon.instrument/fn`.
- `seon.error/resolution` (`src/seon/error.clj:1309-1354`) decides resolved-or-open. Today it has two clauses:
  `:seon.error/resolved-tx` (`seon.problems/resolve!`) at or after the latest occurrence; or a change of the
  `:seon.program/definition-digest` of `:seon.instrument/fn` after the latest occurrence.
- Its callers: `opening` (`error.clj:1356`, one notification per opening), `occurred-since` (`error.clj:1690`),
  `render-html` (`error.clj:1999`), `seon.render.transcript` (`transcript.clj:374`) and
  `seon.problems/error-signatures` (`problems.clj:113-135`). Since 152ceb62c `open-faults` (`problems.clj:137-147`)
  only filters the attached resolution.
- `seon.cluster/mcp-runtime-observation` (`cluster.clj:613-697`) turns open faults into
  `:seon.dev.mcp/health :faulted`.

**Principal approach.** Keep that whole flow and change only the clauses of `resolution`, as Datalog over facts. Two
of those facts are new: a process's observed exit, and a task's fault signatures. **A fault reports a condition, and
a task reports a defect.** A fault resolves when positive evidence ends its condition. That evidence is one of three
facts: root's `resolve!`, the closure of a task that named the fault, or, for a process-scoped fault, an observed
exit of every process it occurred in. A recurrence always re-opens the fault. A changed definition digest is
neither evidence nor authority, so that clause is deleted.

**What disappears:**
- the digest clause and the history query behind it;
- the digest token in the notification contracts;
- `:seon.error/digest-then` and `:seon.error/digest-now`;
- the never-written `:seon.error/issue` and `:seon.error/regressions`;
- the digest test.

## 1. Answers to F1–F3

### F1 — process exit is observed, never inferred from a store handoff

**What boot records today** (all verified at the REPL on 89589):

| fact | where | holds (pid, start-instant)? |
|---|---|---|
| the process row `{:seon.db.process/id "<pid>-<start-ms>"}` | `ensure-cluster-entity!` (`cluster.clj:3298-3316`), branch-local, asserted only when absent, under the provenance `seon.db.process/boot` | **No.** The schema declares optional `:seon.db.process/pid` and `:seon.db.process/start-instant` (`seon.db.process.edn:6-7`), but **0 of the 72 process rows carry them**. The generation exists only inside the id string, and parsing that string is banned. |
| the advertisement `(merge (process/current-identity) …)` | `listening-instance` (`boot.clj:426-440`), written to `prepl.edn` by `write-advertisement!` (`boot.clj:173`). `stop!` deletes it (`boot.clj:583-588`). | Yes, but it is a file, not a fact. It describes only the current holder and is gone after a stop. |
| the store flock | `open-store!` (`store.clj:425-512`); `release-store!` (`store.clj:514-541`) allows the same JVM to reopen | It carries no identity, and releasing it is not an exit (Astra F1). |
| the operator's `(pid, start-instant)` | `seon.cluster.process/live?` (`process.clj:79-87`): `ProcessHandle/of pid` plus a start-instant comparison at ms precision. A withheld instant refuses through `start-instant-unavailable!` ("unknown is never dead"). | This is **the positive exit observation that already exists**. It has one owner and is used by `bin/seon down` and the stop drills. |

**No exit or handoff fact exists.** Boot writes one, as Astra's option 2 ("record positive process-ended evidence
through the existing process/lifecycle owner after observed exit"). There is no polling, no liveness cache and no
inference from boot order:

1. **Record the generation.** At boot, the current process row gains `:seon.db.process/pid` and
   `:seon.db.process/start-instant` from `process/current-identity`. Both are already-declared attributes.
2. **Record observed exits.** In the same transaction, each other process row that carries a generation and no
   `:seon.db.process/exit-tx` is checked with `process/live?`. A `false` answer asserts
   `:seon.db.process/exit-tx "datomic.tx"`: the transaction recording that this generation was observed absent from
   the operating system. The attribute is a ref to its transaction, like `:seon.error/resolved-tx` and
   `:seon.agent/admission-closed-tx`.
3. **Leave the rest unknown.** A `live?` refusal leaves that row with no fact, and the row stays unknown. This is
   the one declared case the writer catches.

It runs where `recover-runs!` runs, in "the pass that runs before anything resumes" (`cluster.clj:3231`), per cluster
branch at boot.

Astra's counterexample, answered: A releases its store and stays alive, B boots, then A reacquires. B's observation
finds A `live? = true` and writes nothing, so A's faults stay open. A release without an exit never produces the fact.
The in-JVM form of this sequence is `boot-test/stop-instance`, two hosted instances in one JVM; §6 extends it.

**What stays unknown, and so open:**
- the boot and config constants (`seon.db.process/boot`, `seon.db.process/config`), which have no generation;
- the 70 historical rows without a generation;
- any row whose `live?` refused;
- a branch that has never been booted since the exit, or a store read offline.

The resolution requires a **nonempty** occurrence-process set and every member carrying `exit-tx`. A query or schema
error propagates as the resolution's refusal, which `open-faults` keeps open.

Measured: 70 `live?` checks took **2.5 ms**, and exactly one generation (89589) is live. The booted-row query took
**1.49 ms**. Every occurrence's process on default is a booted row except the 6 backstop signatures under the
`seon.db.process/boot` constant, so no foreign JVM wrote occurrences into this store (this answers revision 1's open
question).

**Limit.** `ProcessHandle` sees one host. The store is a canonical local directory under a host-local flock
(`store.clj:446-468`), so the only processes that can have written occurrences are processes on this host. A store
shared across hosts is outside the one-lock-holder law. No restriction is added for it (AGENTS "Add no hypothetical
restrictions").

`:seon.error/resolved-tx` keeps its meaning. For process scope it is the **maximum `exit-tx`** over the occurrence
processes, which is the transaction that proved the last exit, not `latest`.

### F2 — cutover by evidence, per signature

§3 gives each of the 30 signatures one of Astra's four dispositions, with its evidence:
1. known fixed with evidence;
2. process ended with evidence;
3. duplicate of a still-open, tasked record;
4. unresolved or unknown, linked to a task.

Only dispositions 1 and 2 call `resolve!`: **3 writes** (b76fae17, 504062b8, 721e82de).

Three landed fixes can close through the new mechanism itself instead of `resolve!`. Sweep items 1, 2 and 4 were
fixed in 9f4f43443 at 18:22Z, and the running archive b7e148de6 contains it. Each fix's task declares the signatures,
and root closes the task after its regression is green on the running program. Five signatures resolve that way:
c2268b2f, 31711187, b559b883, 477a97dc and 67029fe7.

Duplicates are **not** `resolve!`d. They are linked to their successor's task and close with it. That keeps
signature churn (finding 5) visible in data instead of hiding it.

### F3 — scope is validated at admission and read from the declared schema on the same basis

- **Read.** `(:seon.error/scope (seon.schema.internal/entity-properties (mr/schema registry declared-schema)))`, with
  `registry` taken from `(db/carried-projection database)`, which is the same database value the other clauses read.
  30 reads took **0.67 ms**.
- **Admission.** `assert-error-declaration!` (`src/seon/schema/internal.cljc:257-326`) is the existing error-schema
  authority. It refuses an explicit `:seon.error/scope` whose value is not `:process`, using its existing
  `contract-error!` shape. Absence means code scope.
- **Inheritance (measured, and it differs from Astra's assumption).** `entity-properties` merges entity-map
  properties with the *selected root's* properties (`internal.cljc:122-128`). Probe: `[:and {:seon.error/scope
  :process} :seon.error/base [:map …]]` reads `:process`. A schema that references that one inside its own `[:and …]`
  reads **no scope**, because root properties do not cross a reference. So scope belongs to the named schema a
  producer declares. A refinement that wants process scope redeclares it, and forgetting to do so errs toward open,
  never toward resolved.
- **Unreadable.** If the declared schema is absent from the projection, `mr/schema` throws. The throw becomes the
  resolution's refusal, and the fault stays open with that diagnostic. It is never reclassified.
- **Complete integration price.** The instructions (§10) price every slice: schema, writer, reader, admission, task
  reconciliation, status and regressions. They total about 6–7 hours of implementation plus root's cutover
  (30–60 min). The rule is not "+16 lines" alone.

## 2. The rule

`(seon.error/resolution database signature)` keeps its arity, its callers and its purity over one database value.
`latest` is the existing max transaction of the occurrences' `last-at`. The first matching clause wins:

```clojure
;; 1 resolve!      (max :seon.error/resolved-tx) >= latest
;;                 -> {:seon.error/resolved-by :seon.error/resolved-tx :seon.error/resolved-tx tx}
;; 2 task closed   no CURRENT task entity has :seon.task/faults sig, and in (db/history database) some task entity
;;                 retracted both :seon.task/faults sig and its :seon.task/id in one tx >= latest
;;                 [:find ?h (max ?tx) :in $ ?sig ?latest
;;                  :where [?t :seon.task/faults ?sig ?tx false] [?t :seon.task/id ?h ?tx false] [(>= ?tx ?latest)]]
;;                 -> {:seon.error/resolved-by :seon.task/id :seon.task/id h :seon.error/resolved-tx tx}
;; 3 process ended (:seon.error/scope (entity-properties (mr/schema registry declared-schema))) = :process,
;;                 the occurrence processes are a NONEMPTY set, and every one has :seon.db.process/exit-tx
;;                 -> {:seon.error/resolved-by :seon.db.process/exit-tx :seon.error/resolved-tx (max exit-tx)}
;; else open; error-signatures attaches :seon.task/tasks #{h …} from the current task entities declaring sig
```

Guards:
- **History off.** When the store runs with `:keep-history? false`, clause 2 answers unknown, so the fault stays open.
  Missing history never masquerades as closure. Default runs with `:keep-history? true` (verified).
- **Dropped link.** A task that drops the signature but stays open retracts `:seon.task/faults` without
  `:seon.task/id`, so the fault stays open and becomes untasked.
- **Recurrence.** An occurrence after closure has `latest` above the closing transaction, so the fault is open and
  untasked.

`opening`, `notification-id` and `message-tx` tokens become integers only. Every clause answers a transaction, so
each opening is delivered once, and a process-scoped recurrence under a new process opens once per process life.

## 3. Each signature: old rule today, new rule today, cutover disposition

Columns:
- **old** is today's rule on 89589 (`resolved-by`).
- **new** is §2 on today's facts: no task declares faults, no producer declares scope, no exit fact exists.
- **disposition** uses the F2 numbering.
- **after** is the state once §10 lands and root runs the cutover.

Tasks are named by their current handles (verified by query). "exited" means every occurrence process read
`live? = false` from 89589 at about 18:50Z.

| sig | op / `:seon.instrument/fn` | last (Z) | old | new | disposition and evidence | after |
|---|---|---|---|---|---|---|
| c2268b2f | write-refusal-error / record-write-refusal-fault! | 09-27 18:16 | open | open | **1**: item 2 fixed in 9f4f43443; procs 75447 and 88762 exited | closes with task 86ed257beb07 once root confirms its evaluate-source/turn regressions are green |
| 93e4a1e2 | record-tx / seon.test/run | 18:14 | **digest (false: recurred 18:14 and unfixed in b7e148de6)** | open | **4**: candidate fix 4dea3b95a is not in the running archive | open, tasked 80aa14c43291 |
| 477a97dc | turn-step / superv.async | 18:05 | open | open | **1**: item 4 fixed in 9f4f43443 (`agent_test.clj:964`) | closes with task e7feed4a2e86 |
| 31711187 | turn-step / settle-batch-refusal! | 17:51 | **digest** | open | **1**: item 1 fixed in 9f4f43443 (`turn_test/a-refused-refusal-settlement-is-answered-as-a-value`) | closes with task d066476a2fd2 |
| b76fae17 | acquire-arm / memory-pressure-crossed! | 17:27 | open | open | **2**: a JVM heap condition; procs 63809 and 88762 exited. The defect (the gate misses hosted-branch runs) stays task d287c254b094. | `resolve!` |
| 8f6fdfc0 | turn-step / install-function-contract! (NPE: `fut` null) | 03:28 | digest | open | **4**: cause unknown | open, tasked 45138a27e89e |
| d2082881 | stop-work-launcher! / seon.test/run | 03:09 | digest | open | **4**: recurs at restarts | open, tasked f12bf9eda5f1 |
| a0323a94 | exception-value | 03:06 | open | open | **4**: race versus loss undecided | open, tasked 357f3b947cd0 |
| 504062b8 | seon.render/unknown | 02:35 | open | open | **1**: cause was the OOM walk; 9e61340b2 ("nothing walks a result after evaluation") closed task d1efccd3ab7e (absent from task files); no recurrence across 4 boots; proc 88762 exited | `resolve!` |
| 7535402c | turn-step / install-function-contract! | 02:21 | digest | open | **3**: same class and message as 8f6fdfc0 | open, tasked 45138a27e89e |
| 721e82de | turn-completion-error / backstop | 02:08 | open | open | **1**: test exhaust stopped reaching default in 125a1dd4e (`fault_test/a-fault-no-agent-row-places-stays-on-its-own-branch`); its 5 siblings were `resolve!`d; process is the boot constant (unknown) | `resolve!` |
| 67029fe7 | turn-step / superv.async | 02:08 | open | open | **1**: item 4, as 477a97dc | closes with task e7feed4a2e86 |
| 104b2c82, 4dfa157e, 28ce3d56, 017bd6fe | record-tx / seon.test/run | 09-26 23:17 to 09-27 01:56 | **digest (false)** | open | **3**: same message ("…does not authorize this transition") as 93e4a1e2 | open, tasked 80aa14c43291 |
| 1870b33b, 04197210 | stop-work-launcher! / seon.test/run | 01:42, 00:19 | **digest (false)** | open | **3**: same message as d2082881 | open, tasked f12bf9eda5f1 |
| b559b883 | turn-step / settle-batch-refusal! | 09-26 22:24 | **digest (false)** | open | **3/1**: same message as 31711187 | closes with task d066476a2fd2 |
| fa60ad84 | turn-step / valid-candidate-value? | 09-26 17:13 | digest | open | **4** | open, tasked 5c8cf0549616 |
| 2decd62d | transact! / seon.test/run ("Nothing found for entity id [:seon.cluster.eval/id …]") | 09-26 20:46 | digest | open | **4**: unknown; different message from its `transact!` neighbours; no task | open, **untasked**, until root files one |
| bdbbae25, eb8254dc, e02d2dcc, e66a24b8 | transact! / seon.test/run | 09-26 | digest (a `resolve!` also recorded) | resolved-tx | keep | resolved-tx |
| 05a4d415, d65f3d2c, a609c069, dc6a374f, a232ccb2 | turn-completion-error / backstop | 09-26/27 | resolved-tx | resolved-tx | keep | resolved-tx |

Counts:
- **old, on 89589**: 7 open, 18 by digest, 5 by `resolve!`. **The digest rule's false answer reproduced live.** The
  18:34Z restart republished the program, so 93e4a1e2 (recurred 18:14Z, fix not loaded) and 31711187 now read
  resolved, and health shows 7 open where revision 1 measured 9 on 75447.
- **new, today's facts**: 21 open (untasked) and 9 `resolve!`. This is Astra's safe zero-write initial state.
- **after cutover**: 9 by `resolve!` plus 3 new, 5 closed by task, 12 open and tasked, 1 open and untasked (2decd62d),
  for **13 open**. If root has not yet confirmed the three fix regressions, their 5 signatures stay open and tasked,
  for 18 open.

The process clause resolves nothing today, correctly, because no historical row carries a generation. It first acts
on memory-pressure faults recorded after the landing.

## 4. Cost (measured on 89589, warm)

| operation | now | new |
|---|---|---|
| `resolution` over all 30 signatures, per signature (the shape the writer calls) | 14.4–18.9 ms (5 runs) | **11.5–13.0 ms** (5 runs; clauses 1 and 3 plus the scope read; revision 1's "0.93 ms" was a batched simulation) |
| clause 2's history query | — | 0.77 ms for the whole `:seon.task/id` retraction history (7 closed tasks); per signature it binds `?sig` |
| scope read, 30 declared schemas | — | 0.67 ms |
| boot: generation and exit write | — | 70 `live?` checks in 2.5 ms, plus one transaction |
| `problems/problems` | 98–107 ms | unchanged ± the per-signature delta |
| **health: `mcp-runtime-observation "default"`** | **114–136 ms** (3 runs) | adds one task query (≈1 ms) |

## 5. Producer fixes the rule needs

1. `memory-pressure-crossed!` (`boot.clj:400-424`) records `refusal` itself as the source, with
   `:seon.error/declared-schema :seon.sci.kernel/memory-pressure-error`. `prepare` accepts a value source
   (`error.clj:571-586`: `(if failure (refusal failure) source)`). That schema (`seon.sci.kernel.edn:54-56`) gains the
   root property `:seon.error/scope :process`. Its defn gains `:seon/tasks #{"d287c254b094"}` (sweep §10).
2. **Dropped from revision 1:** `record-write-refusal-fault!` gets **no** process scope. Since 9f4f43443 a committed
   write clears a park within the process, so the process is the wrong scope. The fault stays code-scoped and closes
   by its task (86ed257beb07) or `resolve!`.

## 6. Regressions (focused list for root)

New:
1. `seon.problems-test/a-changed-definition-does-not-resolve-a-fault` replaces
   `a-fault-resolves-when-its-function-changes-and-reopens-on-recurrence` (`problems_test.clj:373-418`, deleted).
2. `seon.problems-test/closing-its-task-resolves-a-fault`. A task entry with `:seon.task/faults #{sig}` makes the
   fault read open with `:seon.task/tasks #{h}`. Removing the entry through `tasks-tx` resolves it
   (`:seon.task/id h`). The test must also cover:
   - dropping only the link, which leaves the fault open and untasked;
   - two tasks, where closing one leaves the fault open;
   - a recurrence after closure, which reads open and untasked;
   - a world with `:keep-history? false`, where closure is unknown and the fault stays open.
3. `seon.problems-test/an-unknown-process-keeps-a-process-scoped-fault-open`. An occurrence under
   `seon.db.process/boot`, or a row without a generation, never resolves by process.
4. `seon.problems-test/a-process-scoped-fault-closes-only-after-evidenced-exit`. It uses the real writer with explicit
   identities:
   1. record a fault under a generation of a `cat` subprocess, then close its stdin and await its exit;
   2. run `record-process-generations!` as the current JVM; the subprocess row gains `exit-tx`, and the fault
      resolves with that `resolved-tx`;
   3. a recurrence under the current generation re-opens the fault and delivers exactly one new notification;
   4. the current row never gains `exit-tx`.
5. `seon.problems-test/declared-fault-scope-is-validated-and-read-from-the-declared-schema`. `:seon.error/scope
   :process` on the named schema reads `:process`, a malformed scope refuses at admission, and a referencing
   refinement without its own scope reads code scope.
6. `seon.issue.task-ingestion-test/a-tasks-fault-signatures-round-trip`: `tasks-tx` over an entry with
   `:seon.task/faults`, with the revision compare included.

Extended:
7. `seon.cluster.boot-test/stop-instance`. After an ordered release and reacquire in one JVM, the process row has no
   `:seon.db.process/exit-tx`. Root alone runs this platform test.
8. `seon.cluster.mcp-test` (the `resolve!` case at `mcp_test.clj:299-309`) adds `:seon.dev.mcp/untasked-fault-count`.

Unchanged, must stay green:
- `seon.problems-test/a-resolved-fault-stays-closed-until-it-recurs` (`:353`);
- `seon.error.branch-test/a-fault-is-delivered-and-shown-open-once-per-opening` (`branch_test.clj:83`);
- `seon.fault-test` (8169f74d1's regressions);
- `seon.cluster.boot-test/status-answers-faulted-health-and-lists-the-open-fault` (`boot_test.clj:496`).

## 7. Owner decisions (ruled)

| question | ruling | where it lands |
|---|---|---|
| Q1: where the link lives | (a) The task EDN entry carries `:seon.task/faults #{sig}` as values. | Steps 3 and 10 |
| Q2: health when every fault is tasked | (a) Any open fault means `:faulted`, plus `untasked-fault-count` and per-fault `:seon.task/tasks`. | Step 8 |
| Q3: cutover | Evidence-based, per signature (§3): 3 `resolve!` calls, 5 closures by task, 12 linked open, 1 untasked. No blanket pass, no reset. | Cutover list at the end |

## 8. Findings (every slow or erroring reply)

1. **Slow, over 10 s, on 89589's boot.** Profile: `seon.schema/call-with-projection-state` has max **109,322 ms**
   (x304). `seon.cluster/refresh-source!` has max 28,366 ms and `seon.cluster.boot/request!` 28,572 ms. Boot
   `ready-ms` is 54,319. These need an issue and an owner. They are not this design's.
2. **The digest rule's false resolution reproduced live** (§3 counts). It is the evidence for deleting it.
3. **The floor windowed a 16.5 KB reply of 30 small vectors into a blob, and cut inside rows.** Revision 1's finding 4
   recurred. The same reply printed as one string came back whole.
4. **Doubly escaped strings.** A JVM-mode string result prints with `\\\"` and `\\\\n` inside the MCP value. Ugly
   output.
5. **0 of 72 process rows carry the declared `:seon.db.process/pid` or `:start-instant`.** The generation survives
   only in the id string. Step 2 fixes this for new boots.
6. **`record-write-refusal-fault!` (`turn.clj:5914`) has no Malli contract.** Private functions need one (AGENTS
   "Contract every admitted function").
7. **Agent mistake.** My probe query on `:db/valueType` was correctly refused as an uninstalled attribute.
8. **Signature churn (revision 1 finding 5) stands.** The five record-tx signatures, three stop-work signatures and two
   settle-batch signatures share their messages. Cutover links them. The churn itself is an upstream defect for
   `seon.error/signature` (`error.clj:159`).
9. **Tasks superseded by this landing:** 9612584f8e1e (`health-stays-faulted-on-dead-process-faults`) and
   3d1e36226ec4 (`a-deleted-functions-fault-never-resolves`: resolution no longer needs a function entity). Root
   closes both with the landing.

## 9. Evidence and limits

The forms, in `tmp.fault-rev` on 89589 (JVM mode, `read_only`):
- `bin/seon status` and MCP `runtime_status`: OK, `:faulted`, 7 open, 23 resolved.
- The booted rows: `[:find ?id ?tx :where [?b :seon.db.process/id "seon.db.process/boot"] [?tx :seon.db/process ?b]
  [?p :seon.db.process/id ?id ?tx]]` gave 70 rows in 1.49 ms. The newest is `89589-1790534048230`, which equals
  `(process/id (process/current-identity))`. `[:find (count ?p) . :where [?p :seon.db.process/pid _]]` gave `nil`.
- `process/live?` over the 70 generations, with the ids split in the probe only, took 2.5 ms: 1 live, 0 refused.
- The occurrence processes of all 30 signatures: only the 6 backstop signatures have the boot constant, and none has a
  process outside the booted rows.
- `(seon.error/resolution d sig)` over the 30, and `new-res` (clauses 1 and 3 of §2 with `exited` from `live?`), timed
  5 times each.
- `entity-properties` over the 30 declared schemas. Inheritance probe: a child `[:and {:seon.error/scope :process}
  :seon.error/base [:map [:tmp/x :int]]]` reads the scope; a grandchild `[:and child [:map …]]` does not.
- `problems/problems` and `mcp-runtime-observation "default"`, timed 3 times each.
- Task handles, by query over `:seon.task/intent` containing each signature prefix and by name.

**Not proven:**
- Clause 2 on real task entities, because `:seon.task/faults` does not exist yet. Only the history query's cost was
  measured.
- Clause 3 with real `exit-tx` facts. The facts were simulated from `live?` answers.
- The inferred `live?` behaviour across hosts (see the F1 limit).
- The three task closures depend on root's green runs of the named regressions.

No source, test or resource edits. Nothing was written to any database.

## 10. THE INSTRUCTIONS (at HEAD c2534eef9)

Each step is one loadable slice of under an hour, committed with `git commit --only`. Lint `src script test` with
`--cache false` and compile touched contracts and schema resources before each commit.

**Foreign hold.** `src/seon/cluster.clj` carries another holder's uncommitted MCP-value hunk (`cluster.clj:394-470`).
Steps 2 and 8 wait for that holder, or go to it as a patch.

Steps 1, 3, 4 and 5 are independent. Step 6 follows 3 and 4, step 7 follows 6, and step 10 follows 3.

1. **`resources/seon/schemas/seon.db.process.edn`**: add `:exit-tx [:and {:description "The transaction recording
   that a later process observed this generation absent from the operating system (seon.cluster.process/live?
   false); absence means alive or unknown."} :seon.db/ref]` and `[:seon.db.process/exit-tx {:optional true}
   :seon.db.process/exit-tx]` in `:process`. **+2.** Compile the schema resource.
2. **`src/seon/cluster.clj`**: add `record-process-generations!` right after `recover-runs!`. It is contracted
   `[:=> [:cat :seon.db/connection :seon.cluster.process/identity] :nil]` and does one transaction under the current
   process's provenance:
   - assert `:seon.db.process/pid` and `:start-instant` on the current row when they are absent;
   - query rows that carry a generation, have no `exit-tx`, and are not the current row;
   - for each, `(process/live? {:seon.boot/pid … :seon.boot/start-instant …})`, asserting
     `:seon.db.process/exit-tx "datomic.tx"` when it is `false`;
   - catch only the `start-instant-unavailable` refusal, which leaves that row unknown;
   - write nothing when there is nothing to assert.

   **`src/seon/cluster/boot.clj`** `stand-cluster-runtime!` calls it right after `seed-cluster-facts!` (`:58`) with
   `(:seon.boot/advertisement instance)`. **+24 / +2.** Test: regression 4 and the stop-instance extension (7).
3. **`resources/seon/schemas/seon.task.edn`**: add `:faults [:set {:min 1 :seon.db/index true :description "Fault
   signatures this task owns, as values; closing the task resolves them (seon.error/resolution)."} [:string {:min 64
   :max 64}]]`, and `[:seon.task/faults {:optional true} :seon.task/faults]` in `:entry`, `:declaration` and `:task`.
   `task-delta` then owns the key through `:declaration`, with no code change. **+4.** Test: regression 6.
4. **`src/seon/schema/internal.cljc`** `assert-error-declaration!`: after the render-property checks, add
   `(when-let [entry (find properties :seon.error/scope)] (when-not (= :process (val entry)) (contract-error! identity
   definition [] :seon.schema/invalid-schema "An error scope property is :process or absent." {:seon.schema/member
   :seon.error/scope})))`. **+4.** Test: regression 5.
5. **`resources/seon/schemas/seon.error.edn`**:
   - set `:resolved-by` to `[:enum {…} :seon.error/resolved-tx :seon.task/id :seon.db.process/exit-tx]`;
   - delete `:digest-then` and `:digest-now`, and in `:resolution` delete the `:seon.instrument/fn`, `digest-then`
     and `digest-now` entries;
   - add `[:seon.task/id {:optional true} :seon.task/id]` to `:resolution`;
   - delete `:regressions`, `:issue` and their four entries (`:139-140`, `:219-220`), which are never written
     (revision 1: 0 facts, no writer).

   **−10 / +2.** Lands with step 6, since the contracts change together.
6. **`src/seon/error.clj`**:
   - `resolution` (`:1309-1354`): delete `current`, `then` and the digest branch, and rewrite the docstring. Add
     clause 2 (current links, plus the history query, guarded by `(get-in database [:config :keep-history?])`) and
     clause 3 (occurrence processes, `exit-tx`s, and the scope read via `db/carried-projection`, with a nonempty
     guard). Any query or schema refusal is returned.
   - `opening`, `notification-id` and `message-tx`: the `:seon.program/definition-digest` alternatives go, and the
     token is `[:int {:min 0}]` (`:1362,1372,1380,1390`).
   - `render-html` (`:2002`): replace the digest line with "Resolved by task h" and "Resolved: every process exited".

   **−31 / +26.** Tests: regressions 1–5; keep `a-resolved-fault-stays-closed-until-it-recurs` and
   `a-fault-is-delivered-and-shown-open-once-per-opening`.
7. **`src/seon/problems.clj`**:
   - `restore-error-fact`: delete the `:issue` and `:regressions` branches (`:108-111`).
   - `error-signatures`: one query, `[:find ?sig ?h :where [?t :seon.task/faults ?sig] [?t :seon.task/id ?h]]`,
     attaches `:seon.task/tasks` to each open entry.
   - `resolution-text` (`:474-479`): cases for task and process.
   - Fix the namespace, `open-faults` and `resolve!` docstrings (`:11-13`, `:138-143`, `:151`).
   - **`test/seon/problems_test.clj`**: delete `:373-418` and add regressions 1–5.

   **−7 / +8; test −46 / +~95.**
8. **`src/seon/cluster.clj`**:
   - `mcp-runtime-observation`: add `:seon.task/tasks` to each shown fault's `select-keys`, and
     `:seon.dev.mcp/untasked-fault-count` (the count of open faults without tasks) beside `open-fault-count`.
   - `runtime-observation-ai` shows it.
   - **`resources/seon/schemas/seon.dev.mcp.edn`** declares the optional count.
   - **`test/seon/cluster/mcp_test.clj:299-309`** asserts it.

   **+5, test +1.**
9. **`src/seon/cluster/boot.clj`** `memory-pressure-crossed!` (`:413-415`): the source is `refusal` and the declared
   schema is `:seon.sci.kernel/memory-pressure-error`. Add `:seon/tasks #{"d287c254b094"}`. In
   **`resources/seon/schemas/seon.sci.kernel.edn:54`**, add `:seon.error/scope :process` to the root properties. **±0 / +1.** Prove
   it at the REPL: `(fault/record! …)` with the value source returns a committed fact whose scope reads `:process`.
10. **Task EDN (data)**: add `:seon.task/faults` on:
    - 86ed257beb07 (`seon.turn.edn`): c2268b2f;
    - d066476a2fd2 (`seon.turn.edn`): 31711187 and b559b883;
    - e7feed4a2e86 (`seon.cluster.agent.edn`): 477a97dc and 67029fe7;
    - 80aa14c43291 (`seon.test.runner.edn`): 93e4a1e2, 017bd6fe, 28ce3d56, 4dfa157e and 104b2c82;
    - f12bf9eda5f1 (`seon.flow.edn`): d2082881, 04197210 and 1870b33b;
    - 357f3b947cd0 (`seon.maintenance.edn`): a0323a94;
    - 45138a27e89e (`seon.turn.edn`): 8f6fdfc0 and 7535402c;
    - 5c8cf0549616 (`seon.schema.edn`): fa60ad84.

    Full 64-character signatures come from `(db/q '[:find [?s ...] :where [_ :seon.error/signature ?s]] d)`. Remove
    31711187 from 57f97689b302's intent prose. Delete 9612584f8e1e and 3d1e36226ec4, which this landing supersedes.
    **+~14 data lines.** Reconciled by the ordinary publication.

**Net lines.**
- Source and schema resources: −48 / +82 = **+34**. The writer in step 2 (+26) is F1's price. Without it the rule
  would be net −1.
- Tests: −46 / about +110 = **+64**.
- Task data: about +14.

**Price.** The ten steps take about 6–7 hours of implementation. Steps 2 and 8 are gated on the `cluster.clj`
holder.

### Cutover evidence list (root executes after step 10 publishes and one restart arms steps 1–9)

1. Confirm the new boot wrote generations. On the new process row, `[:find ?pid . :where [?p :seon.db.process/id
   "<new id>"] [?p :seon.db.process/pid ?pid]]` is non-nil. `exit-tx` is absent on every older row, because they have
   no generation. That is expected, and they stay unknown.
2. Record each sibling group's messages from its latest fact, as §9 does. This is the evidence for the disposition-3
   links.
3. For each of the three `resolve!` signatures, record its evidence in the landing note, then run
   `(seon.problems/resolve! (seon.cluster.boot/connection "default") sig)` and record the returned transaction:
   - **b76fae17**: `(seon.cluster.process/live? {:seon.boot/pid 63809 :seon.boot/start-instant #inst
     "<its start>"})` and the same for 88762, both `false`, and no occurrence under any live generation.
   - **504062b8**: 9e61340b2 is an ancestor of the running archive; d1efccd3ab7e is absent from `resources/seon/tasks`;
     no occurrence after 02:35Z.
   - **721e82de**: 125a1dd4e is an ancestor; `seon.fault-test/a-fault-no-agent-row-places-stays-on-its-own-branch` is
     green in a stored run on the running program; no occurrence after 02:08Z.
4. For the three fix tasks, each closed by deleting its entry only after a green stored run:
   - **86ed257beb07**: `seon.cluster.evaluate-source-test`'s refused-runs-then-settles case (9f4f43443) and the
     parked-submission case;
   - **d066476a2fd2**: `seon.turn-test/a-refused-refusal-settlement-is-answered-as-a-value`;
   - **e7feed4a2e86**: `seon.cluster.agent-test`'s wrapped-interrupt case (`agent_test.clj:964`).

   Then check that `(seon.error/resolution db sig)` answers `:seon.task/id` for c2268b2f, 31711187, b559b883,
   477a97dc and 67029fe7.
5. Leave the 12 linked signatures and 2decd62d open. File a task for 2decd62d, the retired `:seon.cluster.eval/id`
   lookup in a test transact.
6. The final check is `runtime_status`: `:faulted`, 13 open (or 18 before step 4), `untasked-fault-count` 1, and every
   shown fault except 2decd62d carries `:seon.task/tasks`. Record the timing of `mcp-runtime-observation`; the
   baseline is 114–136 ms.
