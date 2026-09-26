---
type: research
lane: eval-regression-history-opus
date: 2026-09-26
status: research only; no source changed; one read-only JVM probe on default (pid 89341)
---

# When evals were fast, and what made them slow (2026-09-26)

The owner's question: "We had much faster evals for everything before this." Today, on default, a trivial
`(inc 1)` costs about 144–190 ms per form, of which one commit is about 118 ms. A contracted `defn` on a branch
costs about 1.6 s p50 warm: 166k armed calls, about 2 GB allocated, and the projection rebuilt per defn. The
first defn on a fresh branch costs 2.6 s.

## Answer first

1. **The fast numbers measured a different thing.** Until 2026-09-25 18:13 (`a690aad68`), an MCP SCI
   evaluation was a bare `seon.sci.eval/evaluate` call:
   - without a branch, in the cluster's shared SCI context;
   - with a branch, in a retained context (`acquire-context!`).

   Nothing was committed, no run or evaluation row was written, and a branch `defn` never became a program row.
   It lived only in the retained SCI context (see the deleted `sci-evaluation-form` in `07a03cdd4`). So
   `(+ 1 2)` cost 1–5 ms and a contracted `defn` cost 16–126 ms.
   - The laws then required the one REPL path: read → evaluate → **record** → print. Two commits moved MCP onto
     the agent's ordinary run: `a690aad68` for root and `07a03cdd4` for branches.
   - Since then every MCP form pays at least one durable Datahike commit (about 64–150 ms here), and every defn
     pays the full settlement and installation path.
   - Product agents always paid that path. On 2026-09-16 a six-form turn with one defn cost about 365–452 ms on
     an in-memory fixture store, of which the defn's install was 120 ms.
2. **Part of the per-form cost is inherent in recording.** It is one durable commit (ruled). The measured floor
   was 40 ms p50 when the reply did not wait for durability (B, `fcee85161`), but B was deleted (`de67fdd1a`),
   because every observer reads the committed value.
3. **The per-defn cost is not inherent.** About 1.6 s is spent almost entirely on re-deriving things that do
   not depend on what the defn changed. In order of size:
   - (a) every whole-projection table is re-derived, because a contract row makes a new projection value with
     an empty holder;
   - (b) the SCI base context is re-derived, because its key is the branch's revision, not the program's
     content;
   - (c) the install gate makes a whole disposable branch and fork per gated defn;
   - (d) the settlement commit's writer validation runs against that cold projection.

   Two costs are already fixed in commits but not yet adopted: a history scan and a cold node cache. The
   ranking is below.

## Timeline

"Eval-only" means `evaluate` or `:seon.eval/duration-ms`, with no settlement. "Run" means
`evaluate-source!` or a full turn, including commits.

| date | commit (JVM) | operation | measured | kind | source |
|---|---|---|---|---|---|
| 09-16 | ~0c8f90630 era | six-form turn with one contracted defn, in-memory fixture | 452 ms whole turn; 365–437 ms warm reruns | run | docs/prds/context-generation/research/turn-bookkeeping-cost-2026-09-16.md:23, :729 |
| 09-16 | same | the defn's `gate-function-install` | 120 ms (analysis 27 ms, candidate + arming ~90 ms); settlement commit ~220 ms per turn | run | same :755, :782, :812 |
| 09-22 | before `5f2aa93f4` | MCP SCI `(+ 1 1)` after a commit | 1,242 ms / 3.58 GB (whole base-ctx rebuild) | eval-only | docs/research/agent-platform/slow-assumptions-audit-runtime-2026-09-23.md:92 |
| 09-22 | `5f2aa93f4` | same | 13 ms / 15 MB (base-ctx memo, keyed by branch revision) | eval-only | docs/research/agent-platform/fix-schedule-2026-09-23.md:62 |
| 09-24 | `c327d05b5` / `8a531008a` | contracted defn | 81–126 → 52–168 ms; `(+ 1 2)` 1–4 ms | eval-only | docs/prds/agent-platform/landing/lane-fix-entry-eval-2-2026-09-24.md:55-56 |
| 09-24 | — | first SCI defn on a fresh branch context | 958 ms / 1.76 GB | eval-only | docs/prds/agent-platform/landing/lane-fix-mcp-output-2026-09-24.md:21 |
| 09-25 | S1 `6ab6bf57` | contracted defn, armed, fresh branch | **16.3 ms / 33 MB** (parent 22.4 ms); `(+ 1 2)` 1.5 ms | eval-only | docs/prds/agent-platform/landing/lane-impl-repl-m1-2026-09-25.md:35 |
| 09-25 | ~`1ae366d7f` | MCP SCI trivial, warm / first on a fresh branch | 24 ms / 789 ms | eval-only | docs/prds/agent-platform/landing/lane-delete-mcp-bridge-2026-09-25.md:24 |
| 09-25 | parent of `a690aad68` | MCP SCI `(+ 1 2)` without a branch | ~5 ms, recorded nothing | eval-only | docs/research/agent-platform/one-repl-language-2026-09-25.md:640 |
| **09-25 18:13** | **`a690aad68`** | MCP SCI without a branch becomes a root run | **184–231 ms warm** ("the cost is the writer's transactions") | run | commit message of `a690aad68` |
| **09-25 19:42** | **`07a03cdd4`** | MCP SCI with a branch becomes the hosted agent's run; the retained-context evaluator is deleted | defn now settles rows (no clock in the commit) | run | commit message; `script/seon/dev/mcp.clj` diff |
| 09-26 | `b4b90d405` | `(inc 1)` on a branch, two commits | 155 ms in JVM (two commits ~64 ms each = 128) | run | docs/research/agent-platform/landing/perf-eval-overhead-2026-09-26.md:78 |
| 09-26 | `b4b90d405` | new contracted defn (eval-only) | 73 ms; first call 23 ms | eval-only | same :30 |
| 09-26 | owner report | trivial defn / `(inc 1)` | 875 ms / 310–506 ms (was 155) | run | docs/research/agent-platform/open-items.md:99 |
| 09-26 | `a34078273` (pid 70849) | contracted defn on a branch | 1st 3,105 ms, 2nd 970 ms | run | docs/research/agent-platform/landing/perf-admit-tests-opus-2026-09-26.md:41-42 |
| 09-26 | `a34078273` | contracted defn | 1.72 s p50, 1.57 GB, 182k armed calls | run | docs/research/agent-platform/landing/contract-cost-opus-2026-09-26.md:27 |
| 09-26 | `a34078273` + B `fcee85161` | `(inc 1)`, reply at applied | **40 ms p50** (quiet 38–40 ms); durable point 134 ms | run | one-commit-a-2026-09-26.md:204; contract-cost-opus-2026-09-26.md:54 |
| 09-26 | `8e4ac24c9` (A, B deleted) | `(inc 1)`, one commit | 144 / 155 ms p50/p95; `transact-call` 118 ms | run | docs/research/agent-platform/landing/one-commit-a-2026-09-26.md:163 |
| 09-26 | `8e4ac24c9` | defns on a fresh branch | 6,900 / 2,419 / 2,351 / 1,957 ms | run | same :61 |
| 09-26 | `c029d48cb` (option 2, node cache) | defns on a fresh branch | 2,645 / 815 / 753 / 864 ms; `(inc 1)` 176–193 ms p50 | run | same :59-61 |
| 09-26 | `bb07fdf08` (pid 89341, today) | 5 contracted defns | p50 1.6 s (3.5 s under test load), 1.66–2.13 GB, 166k armed calls | run | docs/research/agent-platform/landing/defn-scan-opus-2026-09-26.md:21 |

## Where today's defn goes: live cumulative profile (pid 89341, read-only probe)

This is one JVM-mode read of `seon.profile` counters since boot. It includes root's test runs, so read the
means as indicative. Inclusive times overlap.

| armed function | calls | mean | owner |
|---|---:|---:|---|
| `seon.cluster.agent/evaluate-source!` | 151 | 871 ms | the run |
| `seon.db/transact-call` | 389 | 300 ms | writer |
| `seon.db/validate-write-report` | 488 | **120 ms** | writer validation of every commit (was 9.5 ms per trivial form, perf-eval-overhead:72) |
| `seon.turn/settle-batch!` | 145 | 374 ms | settlement |
| `seon.sci.eval/derive-base-ctx` | 71 | **266 ms** | SCI base context |
| `seon.schema/load-projection` | 174 | 84 ms | projection |
| `seon.sci.eval/evaluate-candidate` | 51 | 137 ms | install gate |
| `seon.sci.eval/disposable-copy` | 51 | 90 ms | install gate |
| `seon.sci.eval/install-evaluated-rows!` | 41 | 82 ms | installation |
| `seon.sci.eval/function-digests` | 147 | 28 ms | a query over every function row |
| `seon.sci.eval/installation-covers-program-change?` | 41 | 26 ms | fixed in `ab87961d5` (was 800–1,636 ms) |
| `seon.sci.eval/admit-program-change!` | 171 | 25 ms | clj-kondo analysis |

## The introducing changes

1. **`a690aad68` (09-25 18:13) and `07a03cdd4` (09-25 19:42): recording.** These took MCP SCI from bare
   `evaluate` to `evaluate-source!`, which means a submission commit plus a settlement commit. The per-form
   cost went from 5 ms to 184–231 ms, and the commit message says so. This is the ruled one-REPL-path law, not
   an accident.
   - A (`1df3d5769`/`c672188e6`) removed one of the two commits: 144 ms.
   - B (`fcee85161`) answered at applied, 40 ms, and was deleted in `de67fdd1a`.
   - The later rise from 144 to 176–193 ms, after option 2 (`55b5b580f`), is unexplained. The one-commit landing
     suspects disk contention from concurrent tests (one-commit-a:63), and it needs a quiet re-run.
   - These commits also exposed every per-defn cost below to MCP, because a branch defn became a settled
     program row.
2. **A contract row makes a new projection with an empty holder (structural; not one commit).**
   - `:seon.fn/spec` is a projection range: `schema.clj:3054` `projection-ranges`, made the single definition in
     `ca9c40639` (09-22). Function contracts were read by `load-projection` before that too.
   - The writer's tables (`write-entity-schemas`, db.clj:3250, from `20d30a0bd`/`35c5d2fa8`, 09-15/16), the
     `shapes-in` walk, `reference-id`, `compiled-storage` and `storable-attribute-in?` are memoized on each
     projection's own holder (`projection-cache-value`, schema.clj:367). So each defn re-derives all of them
     over about 3,550 forms, although a function contract changes no entity schema.
   - On 09-16 the same structure cost little: a small fixture program, and settlement was about 220 ms in all.
     It grew with the program (5,385 function rows now) and with armed predicates, since every predicate call
     pays the armed wrapper (contract-cost:108).
   - This is the largest term: about 100k of the 166k armed calls per defn are in these tables
     (defn-scan:30-36).
   - `validate-write-report` averaging 120 ms per commit on this JVM is the same cold holder, seen at the
     writer.
3. **`5f2aa93f4` / `03bd7cfc9` (09-22): the base context is keyed by branch revision.**
   - This commit fixed 1,242 ms to 13 ms for forms that change no program rows. But `base-ctx-key` uses
     `revision-basis`, which includes the connection id and generation (eval.clj:3307-3322, :3399).
   - So a branch's first own commit, and every defn (the program rows change, and `acquired-database?`
     reacquires), gets a key no other value shares.
   - Result: 45 derivations for 50 lookups, 235–266 ms each, and up to 2.9 s / 11 GB cold on a fresh branch.
     Its largest term is `function-digests` twice over all 5,385 rows (shrink-plan-sci-eval:30, :60-71).
4. **`1b77876af` (09-26 11:55): the install gate proves each defn in a disposable branch copy.**
   - Per gated defn: `disposable-copy` 90–130 ms, which is a branch plus a fork plus, because of item 3, a
     base-ctx derivation.
   - `evaluate-candidate` adds 137–203 ms.
   - The commit's size note says "+75 disposable copy".
5. **Already fixed, not adopted (RESET NEEDED):**
   - `installation-covers-program-change?` scanned all of EAVT history (800–1,636 ms per first defn). Fixed by
     `ab87961d5`. It measures 26 ms mean live, so this JVM appears to have it.
   - A fresh branch's first writes decoded nodes cold: 350–530 ms. Fixed by `fc0ff40b9`, the shared node cache.
   - `shapes-in` ran 9.4 whole-program walks per defn: 50–110 ms. Fixed by `9b6df1ff4`, plus the db.clj patch.
   - The malli regex driver ran on every armed call: 30–60 ms per defn. Fixed by pin `c0c39ec5c`.

The brief's other candidates are not the cause:
- **Read-evidence digests** (`ca17618c0`, `b96d6e684`, 09-25) moved install, identity, profile and gate reads out
  of a form's evidence. That reduced cost: `(quote 1)` had carried 7,286 reads.
- **Arming private contracts** (`8a531008a`, 09-24) added +48% allocation for a two-defn form and −65% time.
  Contract arming in total is 3% of CPU (contract-cost:78).

## Ranked regressions and the smallest fix for each

Per defn, against today's ~1.6 s. Each cost is measured or taken from a landing; they overlap, and none of the
after-numbers has been re-run.

| # | ms per defn | introduced | mechanism | smallest revert-or-fix |
|---:|---:|---|---|---|
| 1 | ~600–900 (≈100k of 166k armed calls; writer validation 120 ms mean per commit) | structural; exposed on MCP by `07a03cdd4`; tables per holder since `20d30a0bd`/`e590464c0` | a contract row → a new projection value → every holder table re-derived over ~3,550 forms, in the writer and in readers | key the entity-schema tables (`write-entity-schemas`, write validators, `storable-attribute-in?`, `compiled-storage`) by the **schema-rows range** (`:seon.schema/schema-rows` content), not the projection holder. A function-contract-only change then hits. Task `a-contracted-defn-rederives-every-projection-table` (tmp/defn-scan-opus/tasks.edn). |
| 2 | 235–266 (2.9 s cold) | `5f2aa93f4` / `03bd7cfc9` (09-22) | base-ctx key = branch revision + connection id; every defn and every new branch derives again; `function-digests` ×2 over all rows | F1+F2: memoize `loaded-program` and the loaded digests by commit (+10 lines). Then F3: key by [loaded commit, row-digest diff, projection identity] (shrink-plan-sci-eval §F3, about −150/+40). |
| 3 | 230–330 (copy 90–130 + candidate 137–203) | `1b77876af` (09-26) | a whole disposable branch and SCI fork per gated defn | gate on the run's working value with `d/with` and the existing fork, instead of a branch copy. Or F5 (gate through `seon.test/run`, −224 lines). With #2's F3, the copy's acquisition becomes a hit. |
| 4 | ~118–150 (also per form) | `a690aad68` / `07a03cdd4` (09-25), ruled | one durable settlement commit; 12–16 forces per commit | one directory force per node group (task `b08e692710dc`, −6 ms per commit measured). The rest needs the owner's durability options (perf-eval-overhead §1, task `d741e6812a31`). |
| 5 | 800–1,636 first defn | `a0c69cfd9` lineage (09-17) | history scan in `installation-covers-program-change?` | fixed in `ab87961d5` |
| 6 | 350–530 first writes on a branch | per-connection node LRU | cold decode per branch | fixed in `fc0ff40b9` |
| 7 | 50–110 | `shapes-in` per call | 9.4 whole-program walks per defn | fixed in `9b6df1ff4`; apply the db.clj patch |
| 8 | 30–60 | malli `-instrument-f` regex driver | allocation per armed call | fixed by pin `c0c39ec5c`, not adopted |

Per form (trivial), against today's 144–190 ms:

| # | ms per form | introduced | smallest fix |
|---:|---:|---|---|
| 1 | +130–180 (5 → 144–190) | `a690aad68` / `07a03cdd4`: recording through a durable run | ruled. The only measured sub-50 ms point was B (40 ms), and B was deleted. Owner options: perf-eval-overhead §1. |
| 2 | +30–50 (144 → 176–193), unexplained | after `55b5b580f` (option 2) or test load | quiet re-run of `docs/research/agent-platform/landing/one-commit-probe-2026-09-26.clj` before naming a cause |
| 3 | ~9.5 + 23 (writer validation; 59 `db/q`) | writer checks since 09-16 | the config patch (−3 ms). The `db/q` wrapper is task `the-query-wrapper-costs-36x-datahike`. |

## Verification limits

- **Timings are not comparable across rows.** Timings are not like-for-like across eras: the eval-only and run
  rows measure different paths, and 09-16 used an in-memory store.
- **The projection-per-defn cost has no single introducing commit.** It is structural, and its size grew with
  the program. I did not reproduce a pre-09-22 build to measure it.
- **The live probe counts one JVM's cumulative work.** Its counters mix root's test runs with MCP runs.
- **Nothing here was re-run after the fixes in #5–#8.** They are not adopted on default.
