---
type: research
created: 2026-09-23
status: point-in-time audit at HEAD ec01174a5 (read-only; no JVM, no tests)
---

# Agent-platform plan: what is done and what is left (audit at `ec01174a5`)

**Method.** Every row of README §4 and each lane spec's commit table got one status. A status comes
from the commit ids (`git log f6216bd26..HEAD`, 576 commits), from `rg` for the named mechanism in
`src/` at HEAD, and from the landing notes. A commit message alone was not taken as proof.

**Proof boundary.** Default is pid 90963, started 2026-09-23T04:00:24Z from the checkout at
`7e5cdea9e` (`runtime_status`, read-only). No `src/` or `resources/` commit has landed since then,
so every LANDED source commit below is loaded on default.

Most landing proofs ran on scratch roots, not through default's own adoption. "Proven on default"
is claimed only where a note says so. Uncommitted working-tree edits belong to the three running
lanes: `cluster.clj`, `issue.clj`, `program_test.clj`, `transcript_test.clj` and `test_support.clj`.

**Live defects seen at audit time.** Both are reported here, not fixed:
- `runtime_status` problems are unavailable (`seon.test/population-unknown`).
- The profile shows `seon.schema/call-with-projection-state` at max **600,001 ms**, ×211, 69 threw.
  It also shows `seon.db/with-declarations` ×310,198 for 405 s in total.
- 15 `seon.contracts-compile-test` Vars have replaced roots.

## 1. Cut summaries

### Cut 1 — plumbing

| step | delivers | status | evidence | left |
|---|---|---|---|---|
| 1.1 | B3 constructor + declared error contracts | LANDED | 9bf22ecd9, 20ee7864b, e569532dd; repair 7fc62edbc; named schemas a86e93e21, 6d84f27fa | canonical-fixture proof pending (error-schemas note) |
| 1.2 | `:seon.program/definition-digest` on every declaration | LANDED | f27b96c19; 66 src refs | — |
| 1.3 | wrappers read retained contract; per-context install | PARTIAL | e0577a6fb (arm only changed identities); `contract-digest` gone (ed62a3e06) | A1-1b `spec-edn` still in instrument.clj:465; A1-2 blocked (below) |
| 1.3b | B1b operator + boot rewrite | LANDED | 076827cc9, f49187619, 171388062, 1a434cc48; lifecycle lock and `seon.operator.lock` = 0 in src | `script/seon/operator.clj` is 1,434 lines against the ≈900 target |
| 1.3c | storage retention: GC, `:keep-history?`, noHistory | PARTIAL | 1cc00a4a5, 289c9b587, 907b231fe, 9d4fa01a2, 3b321f261 | §6.2 comparator failed, so f1/c2 are SUPERSEDED (codec retained); default GC never swept (#21); leaf rewrite per commit (#39) |
| 1.3d | tests run as agents run (6 commits) | PARTIAL | c1 39a337013; c2 1ada78050; c4 678009fcd; c5 8bc917872…2ff62cc0c | c3 destination publication (save-gate, RUNNING); c6 spec rewrite to installed seams NOT STARTED |
| 1.3e | schema retirement refuses surviving writers | LANDED | c94e88a40, 094888006, 1819cdcd3; `removed-definition-error` db.clj:3916 | schedule #24b still calls the writer-level refusal unbuilt, but src has it; reconcile #24b |
| 1.3f | stamp/hand-cache deletions (26 audit rows) | PARTIAL | lock → expected head a102a8403; fabricated report 87c4228f7; oversight search da2086452; as-of projection ca9c40639; memo 55ddec16c | packaged-population → core.cache (#41; `locking` at schema/edn.clj:376); walk `acquire-entity` (#42); `read-basis-transaction` 17 sites (#40); gitlink hash (#44) |
| 1.4 | projection is a read; ambient transport deleted | PARTIAL | sweep c1 memo: 9b8c5b405, 55ddec16c, ec1b53b38; `derive-projection-from-database` = 0 | c2–5 ruled AFTER cut 3 (§7); `call-with-projection` 44, `handed-projection` 23, `*projection*` 8 in src |
| 1.2b | manifest/seal/snapshot dissolved; incremental adoption | PARTIAL | cfa76f4dd, 975af0a60, 60b94954a, dcc15e5b3; reload per declaration 2bd568c08, da703089b, af5eea72f | `database-manifest` (fn.clj:3435, cluster.clj:2829), `build-manifest`, `publication-input-digest!` (source.clj:527) remain; hook adoptions 9.6–35 s (#55) |
| 1.4b | schema-shape family leaves (A1-13) | NOT STARTED | `src/seon/fn/schema_shape.clj` 467 lines present | waits on the sweep (#45 → #25) |
| 1.4c | candidate acquisition; save-time gate | PARTIAL, **RUNNING save-gate** | handles 1ada78050; gate design in lane-save-gate note; `:seon.source/gate?` uncommitted in cluster.clj:2692 | land the gate, then re-enable the hook |
| 1.6 | flow MUST-NOW N1–N4 + one error route (M4) | PARTIAL | N4 310d4235d (boot.clj:372); kondo discouraged-var e0e44c344 | N1 executor join shelved (7e5cdea9e patch; no fork, 7a9ebeede); N2, N3 not started; error route #0 not started |
| 1.5 | hook as one request; `seon.search`; kind/class | PARTIAL | hook 324d41507; search 434c01f4c, 62487dbc3; kind cut 2d066bc36…bc70a82e3 (`:seon.error/kind` = 0) | hook re-enable BLOCKED on the save gate (owner, schedule 04:15Z) |
| exit | default restarts on the new operator; docstring publishes in one request; one projection transport; reset batch 1 | PARTIAL | operator and one-request hook landed | one transport is deferred past cut 3; the platform tier and the bulk tier have not been run at the cut's end |

Wave table (§4): A — 1.3d c1 LANDED, 1.2b PARTIAL, 1.3e LANDED, boot provenance LANDED 6ec971b07.
B — 1.3d c2 LANDED, 1.5 PARTIAL, partition and host-bound facts LANDED (8a069b5e4, 25f315779).
C — c4 and c5 LANDED, wrappers narrowed LANDED, save-gate RUNNING. D — minimal merge PARTIAL
(three-way and guarded writer landed; gate and accept not), 1.4b not started, cut-1 end not started.

### Cut 2 — tests, publication, database

| step | delivers | status | evidence | left |
|---|---|---|---|---|
| B4 c1–3 | fixture on the open store, one `run`, launchers deleted | LANDED (via 1.3d) | 678009fcd, 8bc917872 (+686/−10,220), 80e9828b8 | one-request test 40.5 s vs its 30 s bound |
| B4 c4 | worker/claim schema markers out | NOT STARTED | `claim-tx` ×6 and worker text in test/runner.clj | — |
| B4 c5–6 | observed reach; skill + spec rewrite | NOT STARTED | skill anchors only (2a37fd8f5) | — |
| B1 c1 | kondo cache correctness | PARTIAL | ea9a4e3e9 | content keying (#24k) |
| B1 c2 | manifest dissolved | PARTIAL | cfa76f4dd | `database-manifest`, `build-manifest` remain (B4 export) |
| B1 c3 | caller-less fn.clj vars deleted | NOT STARTED | no commit found | — |
| B1 c4 | caller files widened | PARTIAL | 6699de97a | — |
| B1 c5 | digest | LANDED | f27b96c19 | — |
| B1 c6–7 | seal, snapshot, toolchain | PARTIAL | upserts retired 60b94954a | `publication-input-digest!` alive |
| B1 c8 | adoption by commit-id compare | LANDED | dcc15e5b3, 874918765 | — |
| B1 c9–10 | reset cold path; progress argument and phase bounds | NOT STARTED | `phase-bounds-ms` = 0; phases declared 320c73adc | — |
| B1 c13–14 | hook on; `publication-base!` | BLOCKED; NOT STARTED | hook blocked on the gate; `publication-base!` at cluster.clj | — |
| A2 c1 | read currency, one mechanism | PARTIAL | 51af11aec, 255ecd14c (revision compare first) | `read-basis-transaction` 17 sites; turn.clj half is in cut 4 |
| A2 c3/c4/c5 | diff, pull-selector and pulled-form deletions | LANDED | f1e55a824, f4fb846e5, 4fe00e120; symbols = 0 | — |
| A2 c6 | validator narrowed to the report | PARTIAL | f4dd51d68, 431821bfa | `owners-of` from-zero cost (#37) |
| A2 c8 | 56 inline error guards | NOT STARTED | 45 `(inst? (:seon.error/at` remain | — |
| A2 c9/c11 | registry and blob pre-reads | PARTIAL | 24ea81bf5 | `::cannot-retire-main` still in registry.clj |
| A2 c10/c12/f2/f4/f8 | GC, store keys, complete pulls | LANDED | 1cc00a4a5, 289c9b587, 907b231fe, 59e86fd8b; fork `store-fixed-record-keys` includes `:keep-history?` | c12 residue (#43) |
| A2 c13 | parser pre-checks | PARTIAL | b6fe3ffa1, be713ae30 | `query-call-valid?` ×3 remains |
| A2 c2/f1 | codec deletion | SUPERSEDED | README §7 "Native heterogeneous storage", codec retained | — |
| A2 c7 | `jdk-integers->long` | NOT STARTED | present in db.clj | the §6.4 probe |
| exit | unchanged green runs nothing; docstring adopts ≤700 ms; validation ∝ report | PARTIAL | explicit `init --dev --changed` 0.71–0.78 s | hook path 9.6–35 s; stale-green reuse (#0h) unverified closed; release wedges the writer (#0d) |

### Cut 3 — tasks, profiling, candidates on the existing turn loop

| step | delivers | status | evidence | left |
|---|---|---|---|---|
| B3 c7–9 | one `seon.task` family, trigger/start/settle, issue/plan retired | NOT STARTED | no `src/seon/task.clj`; `seon.issue` 552 refs, `seon.plan` 43 | whole family |
| C1 minimal | always-on counters, >1 s directives | LANDED | ed62a3e06, 27c08129d, 225d0059c | SCI `defn` and hook-published `defn` armed-and-counted proof (#52) |
| C1 slices 2–6 | stored cumulative cells, snapshot admission, `my.program/profile` | NOT STARTED | `my.program/profile` = 0 | all |
| D1 s2 / nsa s2 | net-definition comparison, change-scoped | LANDED | b51a24055, e4cd4ee97, d8734f1e7, 816092afb | — |
| D1 s3 | candidate lifecycle | PARTIAL | 1ada78050 | save gate (1.4c) RUNNING |
| D1 s4 / nsa s3 | guarded merge writer, immutable lineage | LANDED | a118b34b4 (fork 131ca636), db.clj:4367 `merge-db!` | 3 db_test fixtures lack `:seon.agent/branch` |
| nsa s1 | start one worker on its branch | **RUNNING nsa-slice1** | uncommitted issue.clj diff (+15) | land it |
| nsa s4 / D1 s5 | prepare-merge, gate, named accept | NOT STARTED | `prepare-merge!` / `accept-merge!` = 0 | needs s2 + s3 (done) |
| nsa s5 / D1 s8 | first real fix demonstration | NOT STARTED | — | needs s1–s4 |
| D1 s6–7 | retire the definition-time gate; write-back/export | NOT STARTED | — | — |
| exit | trigger→task→agent→merge→export demonstrated | NOT STARTED | — | the task family, merge gate and export |

### Cut 4 — turn, context, rendering

| step | delivers | status | evidence | left |
|---|---|---|---|---|
| B2 c1–2 | program identity; fork once, keep it | PARTIAL | 5f2aa93f4 (re-acquire only on program-row change), 03bd7cfc9 (M9) | `regenerate-agent-context!`, `same-program-root?` in sci/eval.clj |
| B2 c5 | read currency with one owner | PARTIAL | 51af11aec, 255ecd14c | `declared-sources` still present |
| B2 c3, c4, c6–c14 | doc as data, one turn, wake in-port, walk = history, whole view, ns/debug page, `my.turn`, cycles, conflict basis | NOT STARTED | `render/transcript.clj` 2,446 lines, `mailbox-step`, `declaration-diverged-since-open?` present; render/lint deleted 9440698f3 (S6) | all |

## 2. Spec tables not covered above

| spec | status by commit |
|---|---|
| A1 | 1 PARTIAL; 1b NOT; 2 BLOCKED (failed prerequisite probe, scan kept per §7, wrappers-changed-identities note); 3 PARTIAL (memo); 3b LANDED 1625fb9bc; 4, 5, 6, 9, 10, 11 NOT (after-cut-3 sweep); 7 SUPERSEDED (withdrawn); 8 and 8b fork NOT (malli fork log has no `:refuse`); 12 deferred after cut 3; 13 = 1.4b NOT |
| B3 | 1–3 LANDED; 4 LANDED; 5 LANDED a86e93e21 (fixture proof pending); 6 NOT (no render/error.clj); 7–9 NOT; 10 PARTIAL (F0 chain ed2e1a6b6, noHistory 3b321f261); 11–13 NOT; 14 LANDED; 15 NOT (bootstrap.clj 882) |
| realities §3 | 1 LANDED; 2 LANDED; 3 PARTIAL (reload B/C/D and hook landed; gate RUNNING); 4 LANDED; 5 LANDED 25f315779, 8a069b5e4; 6 PARTIAL (three-way landed; accept, `my.*` merge and release open) |
| flow PRD | N4 LANDED; N1 shelved (patch); N2 and N3 NOT; LATER items NOT (they follow cut 3) |
| projection sweep | c1 LANDED; c2–c5 WAITING (ruled after cut 3's first namespace agents) |
| C1 / D1 / B4 | in the cut tables above |

## 3. README §2 acceptance loop

| # | step | status | gap |
|---|---|---|---|
| 1 | reliable development access | PARTIAL | `runtime_status` problems unavailable at audit time; status page raw error maps (#60); readiness union (#0a residue) |
| 2 | one program authority | PARTIAL | digest landed; an agent contract can enter by a raw `:seon.fn/spec` write (#24ah) |
| 3 | cheap change | PARTIAL | adoption by commit id landed; hook adoptions 9.6–35 s (#55); resume 28 s ready at the last start; a move publishes at 91 s (#24au) |
| 4 | independent execution | PARTIAL | overrides interpreted (39a337013) and M9 landed; A1-2 per-context confirmation probe failed; 24aa base context per connection |
| 5 | honest tests | PARTIAL | one `seon.test/run` landed; stale-green reuse (#0h) and thousand-member release wedge (#0d) not shown closed |
| 6 | real tasks | NOT STARTED | B3 task family |
| 7 | gated acceptance | PARTIAL | comparator and guarded writer landed; gate and named accept (nsa s4) not |
| 8 | verified export | NOT STARTED | D1 s7 |
| 9 | live demonstration | NOT STARTED | nsa s5 / D1 s8 |

## 4. Size

Command (tracked files, working-tree contents):
`for d in src test resources/seon/schemas; do printf "%s " $d; git ls-files -z $d | xargs -0 cat | wc -l; done`

| scope | plan baseline (§5) | now | interim target | owner target |
|---|---|---|---|---|
| src | 90,162 (90,086 at f6216bd26 per #24ao) | **84,486** | ≤55,000 | whole codebase ≤10,000 |
| test | 98,985 | **92,575** | ≤68,000, then ≤45,000 | — |
| schemas | 14,256 | **14,015** | ≤11,000 | — |

The largest src files are turn.clj (5,604), db.clj (4,658), schema.clj (4,202), render/web.clj
(3,807), sci/eval.clj (3,774), cluster.clj (3,706) and fn.clj (3,691). src must still shrink by
≈29,500 lines to reach 55 k. The 10 k shape is an open owner decision (#24ar).

## 5. What is left, in the plan's order (README §7 "Priority to namespace agents")

**Must-fixes — done.** Each is LANDED and loaded on default; residues are noted.
- SCI rebuild (5f2aa93f4) and receipts (f2e6285cc).
- Writer cost (55ddec16c…431821bfa) and the restart hang (5f2aa93f4, 55ddec16c).
- Stop hang (ad63964fb).
- `runtime_status` (24c42427a; residue #0a, #60).
- 1.3d c5 (8bc917872).
- Nuke and reset (76b42f90f, 9744c970d).
- Leaf publication (ea9a4e3e9; residue #55).
- Issue guard (3c55bb0f6).

**M3** — LANDED d32a6b2d7. **M9** — LANDED 03bd7cfc9, eac0333c6, 306f32431, 779fb9b23.

**M4 (next per §7)**
1. The one error route, #0: `seon.fault`, stored and delivered, the panic/record dial.
2. Flow N1 executor join: re-apply the shelved patch.
3. Flow N2: one deadline over request, lock, stop and join (folds in #28).
4. Flow N3: listener failure path.
5. Then the census conversions R-HELPER, R-MSG, R-LOG and R-DISCARD (#10), plus #24h, #24ai, #29, #30 and #47.

**Cut 1 residue** (it can run beside M4 where files are disjoint)
6. Save-time gate, 1.4c / 1.3d c3 — **RUNNING save-gate**. Then re-enable the hook (B1 c13).
7. Fixture reds: missing `:seon.program/definition-digest` and `:seon.agent/branch` (program_test,
   transcript_test, #58, #24an). These are retired assumptions — **RUNNING fixture-reds**.
8. 1.2b remainder: `database-manifest`, `build-manifest`, `publication-input-digest!`. Hook adoption ≤700 ms (#55, #66, #67).
9. 1.3 remainder: A1-1b `spec-edn`. The A1-2 per-context probe needs an owner ruling (§7 failed-probe rule).
10. 1.3f remainder: #41 core.cache (READY), #42, #40, #44.
11. 1.3d c6: rewrite the B4, B2 and D1 specs to the installed seams.
12. The cut-1 end proof: platform tier once, bulk tier once, reds read through the three questions.

**Cut 2 remainder**
13. B4 c4–6.
14. B1 c1 (#24k), c3, c4, c6–7, c9–10, c14.
15. A2 c1 (with #40), c6 (#37), c8, c9, c12 residue (#43), c13, c7.
16. The exit numbers: an unchanged green run executes nothing (#0h), and a docstring adopts in ≤700 ms.

**Cut 3** — first namespace agents
17. nsa s1 — **RUNNING nsa-slice1**. Then nsa s4 (prepare/gate/accept), then nsa s5 (demonstration).
18. B3 task family c7–9. Then D1 s6–7 (gate retirement, export). Then the §2.9 demonstration.
19. C1 slices 2–6. Reset batch 2.

**Projection sweep** — after cut 3's first namespace agents
20. Sweep c2–5 (A1-3/4/12). Then 1.4b / A1-13 (#45), A1-4–6 and A1-9–11.

**Cut 4** — last
21. B2 c1–14: context, one turn, bounded completion, walk as history, whole-view delivery, ns/debug pages, cycles.
22. The flow PRD's LATER items.

**Ordering note.** README §7 lists "starting cut 3 before the must-fixes" as a change that needs a
decision. Cut-3 slices (nsa s2, s3 landed; s1 running) are proceeding before M4 lands. The audit
found no ruling in §7 that grants this.
