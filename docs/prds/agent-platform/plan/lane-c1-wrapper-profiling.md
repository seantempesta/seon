---
type: plan
status: first pass for astra review (2026-09-21); clean write follows
created: 2026-09-21
lane: C1
depends-on: [A1, B1, B3]
tags: [agent-platform, profiling, instrumentation, program-graph, tasks]
---

# Lane C1 — profiling on the armed wrapper

Owned files: `src/seon/profile.clj` (new), `resources/seon/schemas/seon.profile.edn`
and `seon.config.profile.edn` (new), `config/default.edn` (one line), the C1
hook points in `src/seon/instrument.clj` (`arm-var!` `:860-896`, `wrap-interpreted`
`:462-522`), `src/seon/turn.clj` `close-turn` `:4831-4843` and the pass binding at
`:5007`, `src/my/program.clj` (one read), `test/seon/profile_test.clj` (new).
Every `file:line` was read at the working tree of `6d7192c22` this session; the
JDK lines are OpenJDK 26.0.1 `lib/src.zip`. Three `eval_clj` (jvm, read_only,
`default`) were spent; forms and values are in §1 and §4. **UNVERIFIED** marks
what was not read.

## 0. For the owner: what was dumb, and the simpler way

Today nothing measures a function's cost while it runs. Every timing we own is a
one-off: a `println` benchmark in a `deftest` (A1 pack §6 deletes five), a shell
script that spawns JVMs, a number in a research note. Meanwhile every contracted
function already passes through one wrapper we wrote (`instrument.clj:880-889`),
1,570 of them in the running JVM, and that wrapper already costs 1,157 ns per call
against 180 ns unwrapped (C1 pack §8) — a thousand nanoseconds of validation with
no clock in it. The dumb shape was measuring OUTSIDE the running system, in
subprocesses, with hand-rostered targets, and never keeping the answer.

The simpler way, as data flow: **two `System/nanoTime` reads around the call**
(13 ns each), the difference added to three counters that already exist in the
JDK (`LongAdder` count and total, `LongAccumulator` max — 40 ns for all three,
measured), one set per armed definition, allocated once when the wrapper is
armed. The counters are keyed by the definition's CONTENT (its symbol and
digest), never by the JVM's Var, so a redefined function starts a new row and the
old row's numbers stay comparable. The wrapper learns whose work it is doing from
the custody binding the turn and every evaluation ALREADY establish
(`seon.db/*conn*`, bound at `sci/eval.clj:2900`, `kernel.clj:662`,
`effect.clj:515`, `test/runner.clj:690`): one thread-local read, 12 ns measured.
No database is touched on the call path. When a turn closes — a transaction the
loop already issues (`turn.clj:4831-4843`) — the loop drains the counters that are
not zero (`sumThenReset`, `LongAdder.java:158`) and appends one datom per touched
function to THAT SAME transaction: no extra write, no writer contention, work
proportional to the functions this window touched. Each function has ONE row per
branch, whose `[count total max]` value is REPLACED each window; Datahike's
history (`:keep-history? true`, `config/default.edn:4`) keeps every previous
window with its `:t`, so a rolling average is a history query, never a second
entity. Hot chains are a join of those rows over `:seon.fn/calls`, which the
program graph already stores as an indexed symbol set (measured: callees of 300
functions in 7.9 ms). A function whose self time exceeds the owner's own
"couple of seconds" law becomes one task per symbol at the writer (D2), inside
the same transaction, and later windows update that task rather than opening
another. Java Flight Recorder, which samples the whole JVM without any wrapper,
is the independent check.

## 1. Goal and the numbers that prove it

| Number | Before (measured this session or C1 pack §8) | Target after C1 |
|---|---|---|
| Armed call, `seon.id/valid?` | 1,136.81 ns (this session) / 1,156.88 ns (pack) | armed + sample ≤ armed + 60 ns; after A1-3 the armed path itself drops (no argument scan, no `binding`) |
| Unwrapped call | 174.70 ns | unchanged |
| Sample: two `nanoTime` + count/total/max, inline primitives | 40.09 ns (pack §8) | ≤ 60 ns, verified by §4 form 1 |
| Sample written the WRONG way (Clojure map of cells, boxed `t0`/`t1` through a closure call) | **+624 ns** (798.66 vs 174.70, this session) | forbidden by construction: `deftype` cell, primitive `let` locals, no keyword lookup on the call path |
| Branch attribution per call (`*conn*` read + `ConcurrentHashMap.get` by connection identity) | **+12 ns** (811.09 bound vs 798.66 host cell, this session; unbound 889.36 in the same run — order/JIT noise, re-measure in §4) | ≤ 20 ns |
| Flush of 300 touched functions, Datahike floor (`d/with`, 301 datoms, identity upsert) | **12.95 ms first, 6.03 ms second** (this session) | ≤ 30 ms including the write validator, inside the turn-close transaction |
| Callees of 300 symbols over `:seon.fn/calls` (40,812 edges) | **7.87 ms** (this session) | the detector's self-time query for N candidates is ≤ 10 ms |
| Callers of 300 symbols | 12.26 ms | the one-hop chain read in `my.program` |
| Whole edge set | 71.60 ms | never run per flush; only the REPL's full-program view |

Any per-call number over 60 ns or any flush over 100 ms is a defect explained by
algorithm, not by "the cold path".

## 2. The data flow

| Mechanism | What data | Computed when | Carried where | Recompute trigger | Proportional to |
|---|---|---|---|---|---|
| Cell | `count`, `total-ns` (`LongAdder`), `max-ns` (`LongAccumulator` with `Math/max`, identity `Long/MIN_VALUE`) | allocated at arming (`arm-var!`) or SCI install (`wrap-interpreted`) | closed over by the wrapper fn; registered in `seon.profile/cells`, a `ConcurrentHashMap<[symbol digest], Cells>` | a re-arm with a NEW digest registers a new entry and marks the old `retired`; same digest reuses the entry (`computeIfAbsent`) | armed definitions (1,570 today; 4,600 rows when every function carries a contract) |
| Cells per branch | `host` cell + `ConcurrentHashMap<connection, Cell>` | per definition | inside the registry entry | first call under a new custody creates that connection's cell | live connections in the JVM (1–4) |
| Sample | `d = nanoTime after − before` | every armed call | primitives in the wrapper's `let`; `(.increment count) (.add total d) (.accumulate max d)` on the cell chosen by `seon.db/*conn*` (bound → that connection's cell; nil → `host`) | — | calls |
| Definition digest | `:seon.fn/digest` — **B1 writes it**: `seon.id/digest 64` of the declaration's own `:seon.fn/source` bytes (`seon.fn.edn:121`), form-scoped | at publication, per changed declaration | on the function row; read once per arming pass into a `{symbol digest}` map supplied on the arming request (`cluster.clj:2121` has `database` in scope: one query `[:find ?s ?d :where [?f :seon.fn/sym ?s] [?f :seon.fn/digest ?d]]`) | publication of the declaration | changed declarations at publication; whole program once per arming pass (the pass already walks `all-ns`, `instrument.clj:898-908`) |
| Flush transaction data | for each registry entry whose `sumThenReset` count > 0 under THIS connection: `{:seon.profile/id (id/id [sym digest]) :seon.profile/sym sym :seon.profile/digest digest :seon.profile/window [count total max]}` | at turn close, before `db/transact!` | appended to the close transaction's data (`close-turn` `:4834-4837`, and the terminal paths `:4483`, `:4888`) | the turn closing | functions touched in this window (never calls) |
| Window history | previous `[count total max]` values with their `:t` | never — Datahike keeps them | the branch's history index (`:keep-history? true`) | — | windows × touched functions; 2 datoms per function per window (retract + add of one tuple) |
| Rolling average | Σ total / Σ count over windows whose `:db/txInstant ≥ since` | at read | a `history` query in `seon.profile/windows` | — | that function's windows in the span |
| Self time | `total(f) − Σ total(callee)` over the same window, floored at 0 and marked `:seon.profile/self-underflow? true` when callees exceed the parent (shared callees are process-wide per window, C1 pack §9) | at detection (in memory) and at read | computed, never stored | — | candidates × their callees |
| Hot chain | one hop: the subject's row, its callees' rows sorted by total, its callers' rows | at read (`my.program/profile`) | a read result; the agent requeries the next hop | — | direct edges of one symbol (813 callers / 2,586 callees for 300 subjects measured) |
| Detector | candidates = touched entries whose window `total ≥ :seon.config.profile/slow-ms`; finding = those whose self time ≥ the dial after ONE callee query | at flush, in memory, before the transaction | one `[:db.fn/call #'<D2 writer> {detector subject window}]` per finding in the SAME transaction | a window crossing the dial | candidates (usually 0) |
| Task identity | `(seon.issue/subject-id 'seon.profile/slow-function [:seon.fn/sym sym])` today; B3's `seon.task` identity = detector + subject value, same derivation | at the writer | the task row; the digest rides on the occurrence, never in the identity | a later window ≥ dial updates the row (D2: no new task, no new agent) | findings |
| JFR check | `jdk.ExecutionSample` top frames → `Compiler/demunge` → `ns/fn` (the `error.clj:505-525` grammar) | on demand from the REPL protocol | `tmp/c1-<date>.jfr` + the landing note's table | — | samples |

What is NOT here: no atom, no cache holder, no timer thread, no periodic proc,
no per-call map, no string, no entity per call, no digest computed at call time.

**Branch attribution — the decision.** Three options were on the table:

| Option | Guarantee | Cost | Why not / why |
|---|---|---|---|
| (a) the arming generation carries the cluster | none for JVM Vars: one Var root serves every cluster in the JVM (C1 pack §1 scope fact); the last arming would own every cluster's counts | a re-arm per cluster, flipping the root | **rejected for JVM Vars**; it is exactly right for SCI-interpreted definitions, where `install-function-contract!` (`sci/eval.clj:697-711`) runs inside ONE cluster's ctx — but those calls also run under `kernel.clj:662`'s custody binding, so (b) covers them with no special case |
| (b) the custody binding the turn already establishes (`seon.db/*conn*`) | a sample is attributed to the connection whose work it is, as declared by the caller that knows (`call-with-custody` docstring, `db.clj:365-387`); unbound = host work | 12 ns per call | **chosen.** One gap: the turn's own pass (prompt derivation, render, transact) binds projection state at `turn.clj:5007` but not custody; `turn.clj` never reads `*conn*`, and the explicit-vs-ambient writer check (`db.clj:477-481`) refuses a mismatch, so binding `db/*conn*` beside the projection state at `:5007` is a one-line accretion that puts the turn's plumbing on its own branch's row. Commit C1-4 does it; its probe is §6 |
| (c) per-cluster arming | impossible on shared roots without a per-cluster Var namespace | a second registry | rejected |

**What "branch" means for an agent's fork.** A branch IS the database the flush
transacts into: no `:seon.profile/branch` attribute exists, because a fact on
branch X is only in X's database value. An agent working on a forked branch +
SCI context (`run-owned`, `test.clj:576`) has its own connection, so its cells
drain into its own branch at its own turn close. Its override of a function is
SCI-interpreted under `:agent` admission with a new `:seon.fn/digest`; the
published JVM definition keeps the old digest on the same branch — "before/after
a fix = same symbol, two digests" on one branch, both rows present, compared by
one query. After merge (`exact-replacement-tx`) the JVM Var is re-armed with the
new digest at adoption; the fork's rows stay in the fork's history.

**The host cell.** Work with no custody (boot, publication, operator, web
handlers outside `call-with-walk-context` `render.clj:1720-1730`) accumulates in
the `host` cell, readable as a value through `seon.profile/host-windows` from the
REPL and covered by JFR. It is transacted nowhere: no cluster owns it and
"nothing may assume 'the' cluster" (AGENTS.md §1). Named in §8 as a limitation.

## 3. Reading list

| Open | Learn |
|---|---|
| `LongAdder.java:38-52, 85-93` (OpenJDK 26 `src.zip`) | "When updates are contended across threads, the set of variables may grow dynamically"; `add` CASes `base` and falls into `longAccumulate` only on a failed CAS |
| `Striped64.java:60-75, 128, 150-161, 195` | cells are `@Contended`-padded and created only on first contention; `NCPU` bounds the table; `getProbe` is the per-thread slot |
| `LongAdder.java:112-131` `sum` | "NOT an atomic snapshot": concurrent adds during the sum may be missed — acceptable, they land in the next window |
| `LongAdder.java:146-170` `sumThenReset` | `getAndSetBase(0)` then per-cell `getAndSet(0)`: nothing is lost, an add racing the reset lands in the next window |
| `LongAccumulator.java:89-121, 160-185` | `accumulate` with the operator and identity; `getThenReset` mirrors `sumThenReset` |
| `src/seon/instrument.clj:860-896` `arm-var!` | the hook point: the outer fn at `:880-889`; after A1-3 it is a straight `apply` |
| `src/seon/instrument.clj:462-522` `wrap-interpreted`, `sci/eval.clj:697-711` | the SCI-side wrapper and the one seam that installs it with the committed row (`:seon.fn/digest` in hand) |
| `src/seon/instrument.clj:844-858` `current-wrapper?` | staleness = definitions changed; a changed digest arrives as a re-arm |
| `src/seon/db.clj:146, 365-387, 477-481` | `*conn*`, `call-with-custody`, the explicit-vs-ambient refusal |
| `src/seon/turn.clj:4831-4843` `close-turn`; `:4483`, `:4888` | the three close transactions the flush joins |
| `src/seon/turn.clj:5007-5008` | where the pass binds projection state — C1-4 binds custody beside it |
| `src/seon/db.clj:2756-2795` `history`/`as-of`/`since`; `reference-code/datahike/src/datahike/query.cljc:618` | five-position history datoms `[?e ?a ?v ?tx ?added]`; `untuple` is `identity`, so `[(untuple ?w) [?count ?total ?max]]` destructures a tuple in a clause |
| `src/seon/schema/datahike.clj:29, 132-135, 172-174` | `[:tuple …]` bridges to `:db.type/tuple` + `:db/tupleTypes`; cardinality-one |
| `resources/seon/schemas/seon.fn.edn:50-67, 116-140` | `:seon.fn/calls` is an indexed `[:set :qualified-symbol]` (verified live: `:db/index true`, `:db.cardinality/many`, `:db.type/symbol`) |
| `src/seon/issue.clj:554-564` `subject-id`, `:565-585` `subject-row` | the upsert-by-detector-identity property C1 uses until B3's writer lands |
| `src/my/program.clj:15-44, 152-166` | `read-result` and the `{database :seon.db/db subject :seon.program/subject}` request idiom the new read copies |
| `src/seon/error.clj:505-525` | the demunge grammar for JFR frames |
| `resources/seon/schemas/seon.config.db.edn` | how a dial with a default is declared (`:seon.config/dial true :seon.config/default …`) |

## 4. REPL protocol

Codex reaches the REPL through `.codex/config.toml` → `bin/mcp-server` →
`eval_clj` (`mode "jvm"`, `read_only true`) against `default`. One evaluation in
flight; never `sci` mode for measurement.

**Form 1 — per-call cost (before and after each commit).** The sample shape is
the deliverable; measure it, not a stand-in:

```clojure
(let [original (malli.instrument/-f->original seon.id/valid?)
      cell (seon.profile/cell 'seon.id/valid? "<digest>")   ; after C1-1; before: a deftype literal
      n 200000 arg "0123456789ab"
      bench (fn [f] (dotimes [_ 50000] (f))
              (let [t0 (System/nanoTime)] (dotimes [_ n] (f)) (/ (- (System/nanoTime) t0) (double n))))]
  {:unwrapped (bench #(original 12 arg))
   :sampled   (bench #(let [t0 (System/nanoTime) v (original 12 arg)] (seon.profile/record! cell t0) v))
   :custody   (binding [seon.db/*conn* (seon.operator/connection "default")]
                (bench #(let [t0 (System/nanoTime) v (original 12 arg)] (seon.profile/record! cell t0) v)))
   :armed     (bench #(seon.id/valid? 12 arg))})
;; this session, the un-optimized shape: unwrapped 174.70, sampled 798.66 (map of cells, boxed),
;; custody-bound 811.09, armed 1136.81 ns. Acceptance: sampled − unwrapped ≤ 60; custody − sampled ≤ 20.
```

**Form 2 — the flush floor (before C1-3):** the `d/with` form of §1 (300 identity
upserts on `:seon.fn/doc-order`) returned `{:datoms 301 :ms 12.95 :second-ms 6.03}`.
After C1-3, replace it with a real `seon.db/transact!` on a scratch cluster
(`bin/seon --root tmp/c1-root start c1`, downed after) and record the wall time
of one turn close with and without the appended window datoms.

**Form 3 — the queries (after C1-3, on `default` after one turn):**

```clojure
(let [db (seon.db/db (seon.operator/connection "default"))]
  {:windows (seon.profile/windows db 'seon.turn/turn)               ; history rows [count total max t]
   :chain   (my.program/profile {:seon.db/db db :seon.program/subject 'seon.turn/turn})
   :edges-ms (seon.profile/self-times db (seon.profile/latest-window db))})
;; this session, the join sizes: callees-of-300 2,586 rows 7.87 ms; callers-of-300 813 rows 12.26 ms;
;; all 40,812 edges 71.60 ms (REPL only, never per flush).
```

**Form 4 — JFR, the independent check** (CLI syntax from the JDK `jcmd`/`jfr`
tool documentation; **UNVERIFIED** in this tree, nothing runs JFR today):
`jcmd <pid> JFR.start duration=60s settings=profile filename=tmp/c1-<date>.jfr`,
then `jfr print --events jdk.ExecutionSample tmp/c1-<date>.jfr`, top frames
demunged with `clojure.lang.Compiler/demunge` and the `error.clj:523-524` suffix
strip; table the top 20 by sample count beside the top 20 by self time from the
same minute's windows. Disagreement is the finding (JFR samples CPU on running
threads; the wrapper measures wall time, so a provider wait ranks in one and not
the other).

## 5. The work, ordered as commits

Each commit leaves HEAD loadable:
`clojure -M -e "(require 'seon.profile 'seon.instrument 'seon.turn 'my.program)"`.

| # | Commit | Adds (lines, why) | Verified spans |
|---|---|---|---|
| C1-1 | `seon.profile`: cell, registry, `record!` | `deftype Cell [^LongAdder count ^LongAdder total ^LongAccumulator max]`; `cells` registry `ConcurrentHashMap<[sym digest], Cells>` with `host` + per-connection map; `cell` (computeIfAbsent), `cell-for` (`*conn*` read), `record!` (primitive arithmetic, three JDK calls); `retire!`. ~45 lines — each is the one place its cost is paid | `LongAdder.java:85`, `LongAccumulator.java:108`, `db.clj:146` |
| C1-2 | Sample in both wrappers | `arm-var!`: close over `(profile/cell function-symbol digest)` where `digest` comes from the request's `:seon.profile/digests` map (accretion on `:seon.instrument/request`; `cluster.clj:2121` and `test/arm.clj:183` supply it with one query); wrap the outer fn's `apply` with the two reads; on re-arm `retire!` the previous entry. `wrap-interpreted`: same, the digest is on `committed` (`sci/eval.clj:699`). ~12 + ~8 lines | `instrument.clj:860-896, 462-522`; `sci/eval.clj:697-711` |
| C1-3 | Schema + flush in the close transaction | `seon.profile.edn` (§ below, ~25 lines); `profile/flush-tx` drains this connection's cells (`sumThenReset`/`getThenReset`), skips zero counts, emits one entity map per touched (sym, digest); `close-turn` and the two terminal closes `into` it before `transact!`. ~40 + 3 lines. **RESET NEEDED**: new attributes | `turn.clj:4831-4843, 4483, 4888`; `LongAdder.java:158` |
| C1-4 | The turn's plumbing is its cluster's work | bind `db/*conn*` beside the projection state at `turn.clj:5007`. 1 line; probe in §6 first | `turn.clj:5007-5008`, `db.clj:477-481` |
| C1-5 | Reads: windows, self time, one-hop chain | `profile/windows` (history query), `profile/self-times` (one callees query over `:seon.fn/calls`), `my.program/profile` in the `read-result` idiom with `:seon.program/subject`. ~45 + ~25 lines | `my/program.clj:15-44, 152-166`; `query.cljc:618` |
| C1-6 | Detector → task at the writer | `seon.config.profile.edn` `:slow-ms` dial (default 2000, the owner's law "anything larger than a couple of seconds with suspicion", goals note `:270` block / unsettled `:6662`); `profile/findings` (in-memory candidates ≥ dial → one callees query → self time ≥ dial); `flush-tx` appends `[:db.fn/call #'seon.issue/subject-row …]` per finding today, `seon.task`'s D2 writer after B3, same call site. ~30 lines | `issue.clj:554-585`; goals note 2d row 2 |
| C1-7 | JFR protocol run + landing note | no code; §4 form 4 table, the digests before/after one deliberate slow edit | — |

**Schema (`resources/seon/schemas/seon.profile.edn`), as Malli:**

```clojure
#:seon.profile{:id [:string {:seon.db/identity true :description "seon.id/id of [sym digest]; one row per definition version per branch (the branch is the database it lives in)."}]
               :sym :seon.fn/sym                      ; a VALUE (G2): deleting the function touches no row; "row with no function" is one Datalog clause
               :digest :seon.fn/digest                ; B1's per-declaration content digest; B1 declares the key
               :window [:tuple {:description "[count total-ns max-ns] of the LAST flushed window; every earlier window is in history with its :t. Zero-count windows are never written: absence of a row means never called on this branch since arming, not health."}
                        [:int {:min 1}] [:int {:min 0}] [:int {:min 0}]]
               :entity [:map {:seon.db/attributes true}
                        [:seon.profile/id :seon.profile/id] [:seon.profile/sym :seon.profile/sym]
                        [:seon.profile/digest :seon.profile/digest] [:seon.profile/window :seon.profile/window]]}
```

Deletion behaviour: **value**, not ref — a retracted function leaves its rows;
they are the past's evidence and `history` owns them. A branch's rows die with
the branch. No component, nothing sweeps, nothing refuses.

**Dependencies and what to do before each lands:**

| Seam | Needed for | Before it lands |
|---|---|---|
| A1-3 (`instrument.clj:604-619` argument scan and the per-call `binding` deleted; the outer fn is a straight `apply`) | the clean hook point | land C1-2 on the current outer fn anyway; the sample brackets `(apply wrapped arguments)` at `:889`, so the measured armed cost includes the scan until A1-3 lands — the landing note reports both numbers |
| B1 `:seon.fn/digest` (form-scoped content digest; B1 must declare it on the fn row, `seon.fn.edn:116` block) | the key | build the arming digest map from `:seon.program/analyzed-source-digest` (`fn.clj:659-660`): a FILE digest, so two functions in one file share a key and a file edit moves every function in it to a new key — over-splits windows, never merges two versions; correct but coarse; record it |
| B3 `seon.task` + D2 writer | the finding's task identity | `seon.issue/subject-row` (`issue.clj:565-585`) already upserts by detector + subject identity; C1-6 calls it and renames one symbol when B3 lands |
| B4 reach (`reach-memberships`, `runner.clj:2362`) | the task's tests | no dependency: `my.program/profile` names tests through `seon.fn/gate-set` as `tests-reaching` does (`my/program.clj:167-186`) |

## 6. Better than the floor — probe first

| Candidate | Probe | Why it may win |
|---|---|---|
| Sample at `:776` (inside `m/-instrument`, around `original` only) instead of the outer fn | form 1 with both placements | excludes validation cost from the window, so before/after a fix compares only the body; costs passing the cell into the memoized `compiled-wrapper` key |
| Record in `finally` (count refusals and throws) vs only on return | form 1: `try/finally` vs none | a thrown contract refusal is already a fault; counting it would double-report — measure whether `finally` is free before deciding |
| Cumulative `[count total max]` instead of per-window | none; reasoning | O(1) averages between any two `:t` by subtraction, but max cannot reset — per-window keeps max honest; rejected unless the history query proves slow |
| C1-4's custody binding for the pass | `grep -n '\*conn\*' src/seon/turn.clj` (0 today) and one turn on a scratch cluster with the binding | if any elided `seon.db` arity in the pass expected to REFUSE, it now reaches the cluster — the writer's mismatch check (`db.clj:477-481`) is the guard; a refusal there is the finding |
| Wrapper-free JFR only | form 4 | JFR sees every frame (5,191 functions vs 1,570 armed) but nothing per definition digest, nothing per branch, and no fact; it stays the check, not the mechanism |

## 7. Tests

One namespace, `test/seon/profile_test.clj`, four regressions, each bounded by
the 5 s default, on the canonical fixture (`with-database`, `seed-cluster!`,
`transacted!`):

| Regression | Asserts the wanted behaviour |
|---|---|
| the sample is bounded | armed-with-sample − armed ≤ 60 ns and custody − host ≤ 20 ns over 200,000 warm calls; a failure names the ns |
| a flush is one transaction, zero rows for zero counts | after N calls under a fixture connection, `flush-tx` yields exactly the touched (sym, digest) rows in the close transaction; an untouched armed definition yields no datom; a second flush with no calls yields none |
| windows are history | two flushes → `windows` returns two rows with increasing `:t`; rolling average over both equals Σ total / Σ count |
| a finding is one task | a definition whose window crosses a fixture-supplied `:slow-ms` produces one task row; a second crossing updates it (same id, no second entity); a callee crossing does not open a task for its caller (self time) |

Nothing dies here; the five benchmark `deftest`s A1 deletes (A1 pack §6) are the
measurement's previous home and stay deleted. No `:seon.test/long` is declared.

## 8. Done, landing note, stop rules

Done: §1's four acceptance numbers pasted from the REPL; `wc -l` of the added
files against the budget below; the reaching set green in-process
(`seon.test/check`); the cold proof named as owed to the orchestrator; the JFR
table; **RESET NEEDED** with the C1-3 commit id.

Size budget (a feature, so it adds): `seon.profile.clj` ≤ 160 · `seon.profile.edn`
≤ 25 · `seon.config.profile.edn` + `default.edn` ≤ 6 · `instrument.clj` +20 ·
`turn.clj` +4 · `my/program.clj` +25 · test ≤ 130. **≤ 240 src, ≤ 130 test.**
Every line is one of: a JDK call, a query, the schema, or the one seam edit.

Landing note: `docs/prds/agent-platform/landing/lane-c1.md` — forms and values
of §4 before and after; `git diff --stat` per commit; which of A1-3 / B1 / B3 had
landed at each commit and the stand-in used; the §6 probes and rejected
candidates; the JFR/wrapper disagreement table.

Stop rules: a held file (`git status --short` first: `src/seon/cluster.clj` and
`src/seon/fn.clj` were dirty at session start — the arming-request digest map is
supplied at `cluster.clj:2121`; if still held, land C1-2 with `test/arm.clj:183`
only and list the site); an unsettled design (three options into the note); a
seam not landed (name it: B1's `:seon.fn/digest`, B3's writer).

**Unsettled, for the reviewer:**

| Question | Options |
|---|---|
| Provider and effect WAITS rank as slow self time (wall clock) | (i) the one effect owner `seon.effect/request!` samples its crossing into its own row and the detector excludes the function the config declares as the effect executor — a config fact, not a name (**recommended**); (ii) `ThreadMXBean` CPU time instead of `nanoTime` — ~µs per call, rejected by cost; (iii) accept one permanent task per waiting seam, closed by "self time under the dial for k windows" — a constant |
| The host cell is never a fact | (i) readable value + JFR only (**chosen**); (ii) flush into the operator's daily maintenance rows — but onto which branch; (iii) bind custody in boot/publication — widens custody semantics |
| `:slow-ms` is a declared dial | the owner's own number, declared as a config fact with a default; the reviewer may prefer top-N by self time per window (N is also a constant) |
| The `sumThenReset` race | an add between `getAndSetBase` and a cell's `getAndSet` lands in the next window — accepted, documented at `LongAdder.java:150-156`; nothing is lost |
