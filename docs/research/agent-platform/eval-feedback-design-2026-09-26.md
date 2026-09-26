---
type: design
lane: eval-feedback-design-opus
created: 2026-09-26
status: "design + measurement only; no source edited; three priced options for the owner (§9); 3 defects committed as tasks, 2 more + 1 update as a patch for root (§10)"
owner-request: "I want general perf delivered to all agent evals in addition to the result symbol. I want it very painfully clear when an agent is doing something stupid."
code-cited-at: a34078273 (repl.clj, profile.clj, sci/kernel.clj, cluster.clj, test.clj, db.clj clean; sci/eval.clj cited from HEAD, working tree dirty by another lane)
measured-on: default pid 59671 (HEAD e405f51be) and pid 70054 (HEAD a34078273), branch eval-feedback-design-opus
---

# Every evaluation says what it cost, and misuse is loud (design, 2026-09-26)

What exists: an evaluation already measures wall time, allocation, interpreted entries and host-interop calls on its
kernel arm (`seon.sci.kernel/record`, kernel.clj:290-312), records its reads (`:seon.cluster.eval/read-evidence`), its
test gate counts and its writes' provenance (`:seon.db/receipt` on every transaction, db.clj:3223-3229). Only
`:seon.eval/duration-ms` reaches the stored receipt and the reply (`:ms`, repl.clj:197). The one "slow" signal
(`seon.profile/explain-slow`) takes a JVM-wide counter delta and appends it to the form's printed output. The
principal approach: **one cost value per evaluation, measured by the owners that already hold each number, stored as
flat receipt attributes, and judged against one declared Malli bound schema whose `m/explain` errors are the
findings.** `seon.repl/response`, the one function behind both the MCP answer and the next Seon context, renders both.

## 1. Broken first (measured today)

1. **The slow explanation blames the agent for platform time.** The first `(inc 1)` on a new branch answered
   `:ms 1307` with `:out ";; OVER ONE SECOND (1313 ms): this is your defect; fix it before continuing…"`. The ranked
   lines name `seon.sci.eval/acquire! 1290 ms` and `derive-base-ctx 1075 ms`. That is Seon's program acquisition,
   which runs inside the arm (eval.clj:4488 inside `with-arm`). The agent's form took about 0 ms. Task `c7bc0c506aa4`.
2. **The explanation is not the evaluation's.** `seon.profile/begin`/`explain` (profile.clj:181-269) read every cell in
   the JVM. A 4.5 s window around one of my forms held 35 evaluations and 57 transactions from other lanes. The same
   code runs for MCP JVM mode (cluster.clj:492), test members (test.clj:2032-2051) and publication
   (cluster.clj:2936-3010). Under concurrency no per-operation attribution is correct. Same task.
3. **The explanation masquerades as printed output.** `explained-evaluation` (eval.clj:4012-4027) appends its lines to
   `:seon.cluster.eval/output`, so the reply's `:out` holds text the form never printed. Same task.
4. **`:ms` is neither the agent's time nor the reply's.** The arm clock starts before program acquisition and stops at
   `(record :ok)` (eval.clj:4674), *before* admission and rendering (`shown-result`, eval.clj:4683). `(vec (range 3000))`
   answered `:ms 711` against 229 for `(inc 1)`. The whole MCP run that answers `:ms 229` took 408 ms inclusive
   (`seon.cluster.agent/evaluate-source!` growth in a quiet window). Task `f5e49bfa4e85` (patch for root, §10).
5. **The reply map stops being readable EDN when the value is elided.** `(vec (range 3000))` answered
   `#:seon.repl{:value [0 1 … …2968]\n;; …N = N more … (seon.print/value-at result/e7b3b69b578ae [...])., :result result/e7b3b69b578ae, :ms 711}`.
   The legend's `;;` comments out `, :result …, :ms 711}`. A reader loses the handle, the time and the closing brace.
   Task `18c37812b5bc`.
6. **A declared renderer's reply drops `:result` and `:ms`.** `(seon.db/pull d '[*] [:seon.turn/id …])` answered with
   the turn's prose render only (repl.clj:237-247 keeps only diagnostics and errors), so every rendered value loses
   its handle and all performance data. Same task as 5 (one composer, `seon.repl/response`).
7. **Measured and discarded.** `:seon.eval/allocated-bytes`, `:seon.eval/fn-entries` and
   `:seon.eval/host-interop-count` are measured on every evaluation (kernel.clj:300-311) and dropped: the receipt
   schema (`resources/seon/schemas/seon.cluster.eval.edn` `:receipt`) has none of them. This is a design gap, not
   logged as a defect.
8. **A warm `(inc 1)` run makes ~31,000 armed calls.** In a window holding exactly one `seon.sci.eval/evaluate`
   (pid 70054): 39,950 armed calls across 288 functions. My probe's own `seon.profile/cells` contract accounted for
   8,480 of them (`profile/adder?` 6,360, `accumulator?` 2,120). The rest include `seon.db/database-value?` 7,456,
   `seon.sci.eval/copy-host-var` 3,852, `seon.sci.kernel/mark-installed!` 3,810, `seon.db/with-declarations` 1,101
   (285 ms inclusive) and `seon.db/pull-call` 518. Per-form work tracks the host namespace count, not the form.
   Added to existing task `c088e4367dc5` (patch for root, §10).
9. **The form's read evidence includes platform reads.** The stored receipt of `(inc 1)` holds one read of
   `#{:seon.agent/id :seon.agent/namespace :seon.ns/name}` with pattern value `"mcp/eval-feedback-design-opus"`. The
   form read nothing. The form sink is also bound around admission and rendering (eval.clj:4683), so the platform's
   agent lookup becomes the agent's read. Wakes and refresh then treat it as the agent's. Task `611637cf8634` (patch for root, §10).

Also seen while probing, and logged (§10): when default restarted mid-probe, the MCP `repl-unavailable` and
`session-lost` answers each carried a whole babashka stack trace, about 9,000 characters (about 2,800 tokens), for a
one-line fact.

## 2. Measurements (collection cost, live JVM, one bounded probe at a time)

JVM mode, scratch namespace `scratch.eval-feedback-design-opus`, read-only (`read_only true`). No default Var was
redefined, and the loops were finite. The forms and outputs are in `tmp/eval-feedback-design-opus/probes.clj`. The prototype output is in
`tmp/eval-feedback-design-opus/findings-output.txt`.

| Item | Cost | Already paid today? |
|---|---|---|
| `System/nanoTime` (a phase boundary) | 21 ns | yes (arm, profile) |
| `ThreadMXBean.getCurrentThreadAllocatedBytes` | 69 ns | yes (arm start/end, every 1,024 entries) |
| `ThreadMXBean.getCurrentThreadCpuTime` | 482 ns | no (option C) |
| `ThreadLocal.get` | 6 ns | — |
| ThreadLocal + `ConcurrentHashMap.merge` (per-arm attribution of one armed call) | 36 ns | no (options B, C) |
| `seon.profile/begin` over 4,187-4,236 cells | 133 µs | yes, every evaluation |
| `seon.profile/explain` over the same cells | 4.1 ms | only when over one second |
| `seon.ai.tokens/estimate` of 10,000 characters | 1.5 µs | — |
| Malli explainer over a 7-bound cost map (bb, interpreted; the JVM compiled cost is lower) | 6.9 µs | — |
| Armed calls per warm `(inc 1)` run | ≈ 31,000 | per-arm attribution then ≈ 31,000 × 42 ns ≈ 1.3 ms |

Observed evaluation times:

| Form | Reply `:ms` | Other evidence |
|---|---|---|
| `(inc 1)`, first on a new branch | 1,307 | Seon acquisition 1,290 ms |
| `(inc 1)`, warm | 135, 150, 229, 332, 539 | 408 ms for the whole run |
| `(vec (range 3000))` | 711 | whole value ≈ 4,650 tokens (14,890 characters / 3.2); shown ≈ 30 tokens |
| `(defn …)` | — | the owner's 875 ms; not re-measured, because default restarted twice under this lane (root's `one-commit` B landing) |

Every item the design needs costs under 2 ms per evaluation in total. The largest is per-arm function attribution,
and only options B and C include it. The existing `profile/begin` (133 µs per evaluation, whose result is thrown away
unless the form is slow) is deleted by B.

## 3. The cost value every evaluation carries

One map, `:seon.eval/cost`, built at settlement from values the owners already hold. **Stored as flat optional receipt
attributes**, so "every evaluation over N" is one Datalog query, with no parsing and no new entity kind. Absent means
not measured, never zero. Each key has one producer:

| Key (stored on the receipt) | Meaning | Producer / seam | New code? |
|---|---|---|---|
| `:seon.eval/duration-ms` (exists) | wall time from arm to shown text | `seon.sci.eval/evaluate`; the clock moves to after `shown-result` | move one line |
| `:seon.eval/own-ms` | the agent form's own evaluation phase: interpretation plus every call the form made | nanoTime around the eval call inside `trusted-evaluation` | ~6 lines |
| `:seon.eval/seon-ms` | `duration-ms − own-ms`: acquisition, read, admission, render | derived at settlement | 1 line |
| `:seon.eval/allocated-bytes` (declared in `seon.sci.admit`) | allocation of the arm, all threads it travelled to | `seon.sci.kernel/record` | schema accretion only |
| `:seon.eval/fn-entries`, `:seon.eval/host-interop-count` (declared) | interpreted entries and host calls | `seon.sci.kernel/record` | schema accretion only |
| `:seon.eval/value-tokens` | estimated tokens of the whole admitted value | `seon.ai.tokens/estimate` over admission's `:seon.sci.admit/edn` (already built) or its over-bound `:bytes` | ~3 lines |
| `:seon.eval/shown-tokens` | estimated tokens of the shown text | `seon.ai.tokens/estimate` over `:seon.eval/shown` | ~2 lines |
| `:seon.eval/unshown-tokens` | `value-tokens − shown-tokens` | derived | 1 line |
| `:seon.eval/out-tokens` | tokens of printed output | `seon.ai.tokens/estimate` over output | 1 line |
| `:seon.eval/reads` | count of read-evidence entries | count of the evidence vector already stored | 1 line |
| `:seon.eval/unbounded-reads` | reads whose Datahike dependency plan is `:all`, or whose source attributes are `:all` (an unbound attribute: `[?e ?a ?v]`, `missing?` with a variable attribute) | Datahike's own plan (`reference-code/datahike/src/datahike/query.cljc:2554-2580`, `merge-attr-deps` 2709-2715) as stored by `seon.db/read-evidence` | ~4 lines |
| `:seon.eval/unrecorded-defs` | Vars the form interned that settlement did not turn into program rows (private defs, a `defn` refused at the gate) | `:seon.sci.eval/bindings` versus `:seon.program/row` on the evaluation map (eval.clj success path) | ~3 lines |
| `:seon.test.accretion/gate-test-count`, `…/gate-pass-count`, `…/gate-fail-count` (exist) | tests the evaluation triggered | settlement (turn.clj:172-177) | none; now shown |
| writes | transactions and datoms the evaluation wrote | **derived, not stored**: every write under an evaluation carries `:seon.db/receipt` on its transaction (db.clj:3223-3229); count by AVET at render | ~6 lines (render) |
| `:seon.eval/self` (options B, C) | the top 3 armed functions by self time within this arm, as components `{:seon.profile/sym :seon.profile/calls :seon.profile/total-ms}` | a `seon.profile` window carried by the kernel arm (§5) | ~45 lines |

Nothing is stored twice. Shown text stays whole and saved. The cost is stored beside the value and never replaces
it: the path stays total. If building the cost fails, that failure is one more diagnostic beside the value.

## 4. Misuse made loud: one declared bound schema, findings are `m/explain` errors

**Mechanism.** A cluster configuration fact `:seon.config.eval/bounds` holds one Malli `:map` schema over the cost
value. Each entry is `[measure {:level :loud|:note :owner :agent|:seon :fix "…"} bound]`. The findings are exactly
`(m/explain bounds cost)`'s `:errors`. Each finding is `{measure, observed, bound form, level, owner, fix}`, read back
from the entry's own properties (`malli.core/explain`, `reference-code/malli/src/malli/core.cljc`; the prototype uses
`m/children` for the properties). There is no rule code, and no branch names a schema, a symbol or a measure. A new
rule is a new entry. A tighter rule is a new bound. The bound values are dials, per the configuration law.
`tmp/eval-feedback-design-opus/findings.clj` runs this over today's measured values:

    ;; (inc 1) first on a new branch -> 1 finding
    #:seon.eval.finding{:measure :seon.eval/seon-ms, :observed 1305, :bound [:<= 250], :level :loud, :owner :seon, …}
    ;; (inc 1) warm -> 0 findings
    ;; (vec (range 3000)) -> 2 findings (:seon-ms 690 > 250, :owner :seon; :unshown-tokens 4620 > 2000, :owner :agent)
    ;; (d/q '[:find ?e ?a ?v :where [?e ?a ?v]] db) -> 1 finding (:unbounded-reads 1 > 0, :owner :agent)

The prototype's first run judged `:wall-ms` and blamed the agent for the 1,305 ms acquisition, the same defect as
§1.1. Judging the agent only on `:own-ms` is what the split in §3 is for.

**Initial bounds**, each tied to a law or to measured evidence:

| Measure | Bound | Level / owner | What stupid thing it names |
|---|---|---|---|
| `:seon.eval/own-ms` | ≤ 1000 | loud / agent | "over one second is your defect" (CLAUDE.md, sub-second); now only the agent's own time |
| `:seon.eval/seon-ms` | ≤ 250 (dial) | loud / seon | platform overhead; recorded as a fault at `seon.sci.eval`, delivered to root; the agent is told it is not its defect |
| `:seon.eval/unbounded-reads` | = 0 | loud / agent | whole-store scans |
| `:seon.eval/unshown-tokens` | ≤ 2000 | note / agent | the result is far larger than shown: select instead of returning whole |
| `:seon.eval/out-tokens` | ≤ 500 | note / agent | huge printed output, which is re-read every turn |
| `:seon.eval/allocated-bytes` | ≤ 512 MiB | loud / agent | unbounded lazy work, runaway realization |
| `:seon.eval/unrecorded-defs` | = 0 | note / agent | a def that did not become a program row; replaces `def-note` (§7) |
| `:seon.test.accretion/gate-fail-count` | = 0 | loud / agent | the change broke the tests it reaches |

**Deliberately not in the first cut.** Each of these needs a fact no owner holds today, and inventing one is exactly
what §4 forbids:

- "repeated identical work" and "re-derived something cached": option C, keyed on source digest plus read revisions;
- "unbounded lazy seq": the arm's time-limit outcome and admission's `:over-bound` reason already say it; that
  refusal's own `:seon.error/fix` is the right owner.

**Seon-owned findings are faults.** An `:owner :seon` finding is recorded once through `seon.fault` at the owning
boundary (`seon.sci.eval/evaluate`). Its signature is the measure plus the top self-time function, so repeats count
occurrences instead of creating new faults. The agent sees one line saying the time is the platform's.

## 5. Per-evaluation function attribution (options B and C)

This replaces `profile/begin`/`explain` with a **window**. A window is one per-operation map of cell → `[calls nanos
self-nanos]`, held by `seon.profile`. `timed` (profile.clj:107-126) adds one ThreadLocal read and, when a window is
present, one `ConcurrentHashMap.merge` (36 ns). Self time is a per-thread child-nanos stack on the window, a few more
nanoseconds. Two changes carry it:

- The kernel arm holds the window, and `adopt-arm` (kernel.clj:~600) installs it with the arm. Work that travels
  across threads is therefore attributed to the evaluation that owns it, not to whatever else the JVM was doing.
- `seon.profile/with-window` is the one entrance for non-SCI operations: MCP JVM mode, test members, publication.

The same windowed value answers all four places that use `begin`/`explain` today. Self time is what makes "top
functions" useful. With inclusive time, `evaluate`, `with-arm` and `call-with-projection-state` top every list.

The measured bound is about 31,000 armed calls × 42 ns ≈ 1.3 ms per warm `(inc 1)` run, under 0.6% of it. The
slice's first commit records its own count. Past 2% of `own-ms + seon-ms` over 20 trivial forms, it falls back to
sampling every 64th call per cell.

## 6. Delivery: one renderer, both faces, both audiences

- **Where it renders:** `seon.repl/response` (repl.clj:237-247), the single generator for the MCP answer
  (`history-entries` → `:seon.repl/response`, script/seon/dev/mcp.clj:445-455) and for the next Seon context
  (`seon.render.transcript`). No second grammar. The HTML pair reads the same entries.
- **Shape**, fixed and compact, costing about 26 tokens per evaluation:
  `#:seon.repl{:value 2, :result result/e512…, :ms 229, :cost {:own-ms 0, :seon-ms 229, :alloc-mb 4, :reads 0, :writes 0, :shown-tokens 1}}`.
  Gate counts are added when a gate ran. `:unshown-tokens` is added when above zero.
- **Findings come first**, as `;;` lines *above* the reply map, so they are the first thing read and cannot corrupt
  the map (§1.5):
  - `;; LOUD :own-ms 1450 > 1000: over one second in your own code. Self time: my.x/scan 1320 ms x1, seon.db/q 110 ms x40.` (about 37 tokens)
  - `;; SEON :seon-ms 1305 > 250: platform time (seon.sci.eval/acquire! 1290 ms), fault 3f2a9c recorded for root. Not your defect.` (about 39 tokens)
  - `;; NOTE :unshown-tokens 4620 > 2000: about 4,650 tokens of value, 30 shown. Select what you need.` (about 30 tokens)
- **A declared renderer's reply keeps the reply map.** The renderer's text is the value, followed by the same
  `#:seon.repl{:result …, :ms …, :cost …}` tail (fixes §1.6).
- **History is stable.** A shown entry's text never changes on later turns, so provider prompt caching holds.
  Findings are derived from the stored cost and the bounds *as of the receipt's basis*, so re-rendering reproduces the
  same bytes.
- **Delivered to whoever can act.** Agent-owned findings go into the agent's own reply, the same bytes over MCP and
  in the next context. Seon-owned findings do that too, and are also a recorded fault that root reads in
  `runtime_status`. Nothing is only logged.

## 7. What it replaces (deleted in the same slices)

| Path | Lines (HEAD) | Replaced by |
|---|---|---|
| `seon.repl/def-note`: re-reads the source with `edn/read-string` and branches on `'def`/`'defn` symbols | repl.clj:119-141, 23 lines, plus the `:seon.repl/note` entry | the `:unrecorded-defs` bound (data from the evaluation, form read once) |
| `seon.sci.eval/explained-evaluation` and its call (output munging) | eval.clj:4012-4027 + 4367, ~18 lines | the arm window and findings |
| `seon.profile` `snapshot?`/`begin`/`growth-line`/`explain`/`explain-slow` and the "OVER ONE SECOND … your defect" text | profile.clj:168-269, ~100 lines, plus the `:seon.profile/mark`/`explanation` schemas | `seon.profile/with-window` + `window-report` (~45 lines) |
| MCP JVM `:seon.dev.mcp/profile` explanation | cluster.clj:280-296 (mark), 492-499 | the same window, reported as that call's cost |
| test member `explain-slow` | test.clj:2031-2032, 2051, 2066-2067 | the window on the member's arm; the member records the same cost keys |
| publication `explain-slow` progress line | cluster.clj:2934-2936, 3008-3011 | the window |
| per-evaluation `profile/begin` (133 µs each, result thrown away when fast) | eval.clj:4238 | nothing; the window costs nothing when not read |

`seon.profile/top`/`summary` (lifetime per-cell totals behind `runtime_status`) stay. They answer a different
question: which definitions are costly over the JVM's life.

## 8. Slices (each loadable, HEAD loads, callers converted in the same cut)

| # | Slice | Files | Added / deleted (estimate) |
|---|---|---|---|
| S1 | Record the cost: move the clock after `shown-result`; own/seon split; store the kernel's three dropped measures, the token measures, reads, unbounded reads and unrecorded defs on the receipt | `sci/eval.clj`, `turn.clj` (settlement copy), `seon.cluster.eval.edn`, `seon.eval` schema resource | +45 / −4 |
| S2 | Show the cost: `:seon.repl/cost` in `response`; the renderer path keeps `:result`/`:ms`/`:cost`; derive writes by receipt | `repl.clj`, `seon.repl.edn` | +30 / −6 |
| S3 | Findings: `:seon.config.eval/bounds` dial (a Malli schema form), a `findings` function (~20 lines), `;;` lines above the map; `:seon` findings recorded as faults; delete `def-note` and `explained-evaluation` | `repl.clj`, `sci/eval.clj`, `config` schema + `config/default.edn`, `seon.fault` call | +45 / −45 |
| S4 (B, C) | Window: `seon.profile/with-window`, `timed` writes to it, the kernel arm carries it, `:seon.eval/self` top 3; convert MCP JVM, test and publication; delete `begin`/`explain`/`explain-slow` | `profile.clj`, `sci/kernel.clj`, `sci/eval.clj`, `cluster.clj`, `test.clj`, schemas | +60 / −125 |
| S5 (C only) | Repeated work: `:seon.eval/source-digest` plus read-revision digest; a bounded `core.cache` LRU per agent (size a dial) answers "same form, same reads, same result as result/eX"; CPU versus wall (`blocked-ms`); blob write counts via blob provenance | `sci/eval.clj`, `seon.blob`, schemas | +110 / −10 |

Pre-commit for every slice: the two static checks (contracts compile, schema EDN reads). S3 adds a dial, so it also
runs `seon.config-test/checkout-effective`. Regressions: one per class. Each asserts that the wanted behaviour appears
in `seon.repl/response` for a real evaluation:
- `own-ms` is attributed and the platform is not blamed on the first evaluation of a branch;
- an elided value's reply reads back as EDN with `:result` present;
- an unbound-attribute query yields one loud finding;
- a private def yields `:unrecorded-defs 1`;
- a renderer reply keeps `:result`.

## 9. Options for the owner

**A. Record and show what is already measured, with declared bounds; no function attribution (simplest viable).**
S1 + S2 + S3.
- Guarantee: every reply, MCP and Seon alike, carries own versus Seon ms, allocation, reads, whole-store scans,
  whole-versus-shown tokens, printed tokens, unrecorded defs and gate counts. Seven declared bounds make misuse loud
  and route platform time to root as faults. The blame and elided-map defects are fixed.
- Cost: ≈ +120 / −55 lines; < 0.1 ms per evaluation; about 26 tokens per reply, plus about 35 per finding.
- Sacrifice: "which function" stays unanswered per evaluation. The JVM-wide `explain-slow` is deleted, not replaced,
  so a slow own-time finding names no function.

**B. A + per-evaluation windows with self time (recommended).** S1-S4.
- Guarantee: everything in A. Every finding names the top self-time functions *of this evaluation only*, correct
  under concurrent lanes. MCP JVM mode, test members and publication report the same cost shape through one window.
  The four `begin`/`explain` duplicates are deleted.
- Cost: ≈ +180 / −180 lines (net about 0); about 1.3 ms per warm trivial run (≈ 31,000 armed calls × 42 ns), with a
  sampling fallback if over 2%; 133 µs per evaluation saved by deleting `begin`.
- Sacrifice: one ThreadLocal read on every armed call JVM-wide, including unarmed host work. The `timed` macro grows
  by about 6 lines.

**C. B + repeated-work detection, CPU versus wall, and blob accounting.** S1-S5.
- Guarantee: everything in B, plus "you ran this same form over the same reads before: result/eX", "N ms blocked,
  not computing", and blob bytes written.
- Cost: ≈ +290 / −190 lines; a bounded LRU per agent (a new cache dial); 0.5 µs CPU-time read per evaluation; a
  digest over source and read revisions per evaluation.
- Sacrifice: a new cache and a new identity input for evaluations. Repeated-work detection has false positives when a
  read is not replayable, so it needs its own design review before implementation.

## 10. Defects logged (task EDN, `language-requirements-2026-09-24.md` §7)

Ids are `seon.issue/task-handle` over `[ns name kind]`, verified against an existing id before use.

- `resources/seon/tasks/seon.profile.edn` (new file) — `c7bc0c506aa4`
  `slow-explanation-blames-the-agent-for-platform-time`: §1.1-§1.3.
- `seon.sci.eval`: `f5e49bfa4e85` `evaluation-duration-mixes-acquisition-and-omits-render` (§1.4), `611637cf8634`
  `form-read-evidence-includes-platform-reads` (§1.9), and today's numbers for existing `c088e4367dc5` (§1.8) are a
  **patch for root** in `tmp/eval-feedback-design-opus/seon.sci.eval-tasks.edn`: another lane holds an uncommitted
  hunk in `resources/seon/tasks/seon.sci.eval.edn`, and one file has one owner.
- `resources/seon/tasks/seon.repl.edn` (new file) — `18c37812b5bc`
  `reply-map-is-not-edn-when-elided-or-rendered` (§1.5, §1.6).

- `resources/seon/tasks/seon.dev.mcp.edn` — `2cd1f9deb10c` `mcp-failure-answers-carry-a-babashka-stack-trace`. Seen
  twice while root restarted default: `repl-unavailable` and `session-lost`, about 2,800 tokens of the bridge's own
  babashka frames each. The producer is `seon.dev.mcp/failure` (`script/seon/dev/mcp.clj:77-86`).

## 11. Verification limits

- **Contaminated windows.** JVM-wide profile growth in an active JVM includes other lanes' work. The 31,000-call
  census used a window with exactly one `seon.sci.eval/evaluate`, but it still includes the bridge and my probe's
  contract checks, which I subtracted.
- **Restarts.** Default restarted twice during the lane (root landing `one-commit` B, a34078273), so some samples are
  from pid 59671 and some from pid 70054. `defn` was not re-measured. Settlement changed under B, so the pre-B
  `settle-batch!` means are not used here.
- **Prototype runtime.** The Malli prototype ran in babashka against `reference-code/malli/src`, not in the JVM. The
  JVM explainer cost is expected to be lower, not measured.
- **Unimplemented.** No slice was implemented, so no before/after row exists. The option costs are estimates from the
  measured per-item costs above.
- **Cleanup.** The branch `eval-feedback-design-opus` is retired (MCP `branch retire` → `retired? true`). The scratch JVM
  namespace ended with default's restart (`session-lost`).
