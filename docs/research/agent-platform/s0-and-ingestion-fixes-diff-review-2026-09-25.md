---
type: review
created: 2026-09-25
status: revise-task-ingestion
---

# S0 and task ingestion fixes — diff review

S0 composes Malli's entry grammar with the existing shape codec. Task ingestion composes analyzer edges, edamame declarations, the `seon.issue` writer and publication/adoption. Those owners remain appropriate; the task changes still have admission and revision defects.

| Landing | Verdict | Blocking findings |
|---|---|---|
| [Derived-contracts S0](../../prds/agent-platform/landing/lane-impl-derived-prereqs-2026-09-25.md), `eb7e64ffa` + `044b0f44b` | **accept** | None; P2 coverage improvement below |
| [Task ingestion revision](../../prds/agent-platform/landing/lane-impl-task-ingestion-2026-09-25.md), `d7139e8e5..c8d543330` inclusive | **accept after fixes** | P1-1; prior revision-basis finding only partially resolved |
| [Task gate/publication and adoption](../../prds/agent-platform/landing/lane-fix-task-gate-publication-2026-09-25.md), `f06358c74..3b1091d3b` inclusive | **revise** | P1-2; P1-3 remains an explicit integration dependency |

No P0 found. These are static diff verdicts, not a fresh runtime certification. The assignment reports default green: `seon.fn.schema-shape-test` 8/0 and task ingestion 46 assertions. I accept that evidence as supplied, without claiming to have rerun it. The gate landing records the earlier 7-test/40-assertion run; the current test file also contains the later migration regression from `0a332e8fc`, outside these diffs. No code edits, tests, REPL calls or `bin/seon` commands were performed.

The source references below use `3b1091d3b` for task code and `044b0f44b` for S0. Unrelated intervening commits, including the census note, are excluded. The comparison authority is [review `03bf7d6cb`](task-ingestion-diff-review-2026-09-25.md), [language requirements](language-requirements-2026-09-24.md) 3b.6 and 7.13/7.16, and [derived-contracts](derived-contracts-2026-09-25.md) S0.

## P1 findings

### P1-1 — normalization bypasses the closed authored schema

`src/seon/issue.clj:587` removes **every** map member whose value is an empty collection, before `m/explain` validates `:seon.task/file` at `:588`. The rule is broader than “empty optional sets are absence.” For example, this authored entry is accepted after silently discarding its unknown member:

```clojure
[#:seon.task{:name "x" :kind :function :intent "i" :unknown []}]
```

Similarly, `:must-read []` and `:evidence {}` disappear rather than failing their set schemas. Even `:id []` disappears and causes a handle to be minted, bypassing validation of a supplied identity. This is source-derived behavior; I did not execute these examples. A closed map cannot reject keys that its producer deletes first. This leaves prior P1-3/P1-7 partially unresolved and introduces a defect in P1-5's fix.

**Smallest fix:** omit only an empty **set** at the two declared optional collection keys, then validate the complete resulting entry. Preserve every other key/value for the existing Malli refusal. Keep the original authored value in the diagnostic. Add cases for an unknown empty-valued member, a wrong empty collection type and an invalid empty-valued id; each must refuse without changing the basis. Retain the valid `#{}` no-op and clearing cases.

### P1-2 — adoption disables conflict detection and treats destination-only tasks as deletions

`src/seon/issue.clj:649–659` computes the basis for `task-delta` from the **same transaction-time stored rows** it compares against. Consequently its revision-conflict branch can never fire for adoption. `adopt-tasks-call` (`:670–682`) passes source rows and the union of source/destination handles, but no previously read destination basis.

Concrete sequence: capture an adoption proposal changing task H from A to B; another writer changes H to C; execute the adoption transaction. `adopt-tasks-tx` supplies C as both current and basis and replaces C with B. The surrounding published-head guard (`src/seon/cluster.clj:2563–2565`) checks the source head, not the task revision read at the destination. This is last-writer-wins under 7.16, despite reusing the right writer.

There is a second consequence of the same authoritative-copy assumption: a destination-only handle is included in `handles`, absent from source `rows`, and retracted at `task-delta:629`. A task created through the existing `index-tasks!` owner on that cluster can therefore disappear on unrelated program adoption. This does not require the future runtime-create API. “Tasks are data” and a missing source row do not establish that such a task was previously imported and has now been deleted.

**Smallest correction:** carry the held destination declaration basis into the transaction and compare it there; use existing publication/history evidence to distinguish deletion of a previously imported declaration from a destination-only task. Retain one `task-delta`, not a second reconciler. Cover a competing edit between proposal and transaction, a destination-only task, and deletion of a previously adopted task. If the intended scope is a disposable, source-authoritative task mirror, obtain an explicit constraint ruling against 7.16 before calling that behavior general cluster task adoption.

The earlier revision fix is also only conditional at the file entrance: `index-tasks!` still defaults missing basis to a read performed when submitting the proposal (`:690–692`), and publication supplies none (`source.clj:540–544`). Explicit-basis callers are protected; an already stale file is not protected against edits made before that submission-time read. Bind the actual read/review basis for the promised authored-edit guarantee; do not describe the optional default as proof of it.

### P1-3 — destination program merge still has no dangling-task gate

The previous review's integration requirement remains open. `src/seon/cluster/source.clj:825–873`, `accept-merge!`, checks run evidence and program replacement but never calls `task-findings` or validates candidate edges against destination tasks. Its successful path reaches `db/transact!` at `:871`. The only added findings call is in publication's `index-tasks!` (`:545`).

Thus a tested candidate can carry a task handle available on its branch while the destination lacks that task. Task rows correctly do not ride the program merge; that makes the missing destination check significant. Publication's coherent-source check does not close this path.

The ingestion landing honestly names this as outside its grant. Do not expand that lane's ownership retrospectively, but retain it as a P1 integration dependency before accepting ingestion as a complete 7.13 feature. The existing guarded acceptance owner must validate the combined candidate program against admitted destination task data and bind the decision to its write basis. Required proof remains: a missing destination handle refuses by handle/holder, and a separately admitted task survives a subsequent program-gate failure. No new gate framework is needed.

## Prior findings: resolved versus remaining

| Prior finding in `03bf7d6cb` | Assessment of this revision |
|---|---|
| P1-1 identity | **Resolved.** `task-handle` uses `seon.id/id [ns name kind]`; admission verifies a supplied id and excludes mutable intent. The seed and annotations agree. |
| P1-2 revision conflicts | **Partially resolved.** Explicit-basis `tasks-tx` compares at the writer and keeps the winner; adoption/default-basis gaps are P1-2 above. |
| P1-3 reader and offending data | **Reader resolved.** `parse-string-all` consumes all input; one vector is required; reader cause and original entry survive. Closed admission still has P1-1 above. |
| P1-4 placement | **Resolved for the requested rule.** Analyzer form positions identify the post-name map; body keyword usage is legal. String handles make the old auto-resolved keyword scan unnecessary. |
| P1-5 empty optional sets | **No-op/clearing resolved, normalization too broad.** Narrow it as P1-1 specifies. |
| P1-6 error propagation | **Returned read/write refusals are no longer projected into success.** Lookups check before selection/coercion, and `index-tasks!` checks the transaction result before wrapping it. The real invalid-pull case is covered; other declared alternatives are source-reviewed, not independently exercised here. |
| P1-7 schemas/contracts | **Substantially resolved.** Named annotation/file/declaration/basis/edge/finding/request/report schemas, namespaced outputs, error-base composition and touched function/helper contracts are present. Closed authored admission still needs P1-1. |
| P2 namespace reverse edges | **Resolved.** Namespace holders participate in referrer lookup, with namespace and alias/see-also coverage. |
| P2 scans/member list | **Resolved in structure.** Stored tasks are indexed once, owned members derive from the declaration schema, links are bound once. Sorting handles makes delta construction O(n log n), not strictly linear. |
| P2 file deletion/duplicates | **Resolved for the file-owned inventory.** Explicit missing files read as `[]`; cross-input duplicates refuse; publication unions current files with stored namespaces. This does not establish ownership of all destination-only task data during adoption. |
| Publication and destination gate follow-ups | **Publication wired; adoption added but needs revision.** Destination program gate still unwired, P1-3. |

## S0 correctness, simplicity and tests

`entry-parts` (`src/seon/fn/schema_shape.clj:31–40`) implements the same child selection as Malli `-parse-entry`: naked `k`, `[k]` and `[k properties]` select registered `k`; explicit entries retain their child. Both canonicalization and row production use it. It also handles the explicit three-element nil-properties spelling. Malli owns schema validity; this helper need not become another schema validator.

This is a small producer fix: +15/−4 source lines, +48 test lines across the two commits. Existing entry order canonicalization, property normalization, identities and storage model remain in place. The temporary nil property slot is an in-memory value; `entry-row` writes properties only when present. No stored nil, new registry or task ownership change is introduced. Equal encoded rows establish that call-preparation's existing key/schema joins see the same facts.

`044b0f44b` correctly changes the expectation from authored order to `normalized-form`; it does not weaken the codec's contract. The regression exercises shorthand, explicit/reordered twins, nesting, canonical decode, storage readback and fingerprints. The landing's 5,240-form census reports zero drift, supporting no normalization-revision bump.

**P2 — add a positive validation case.** The validation loop at `schema_shape_test.clj:238` supplies `{}`, `{:seon.db/db 1}` and `{:seon.agent/id 1 :seon.db/db 1}`. None demonstrates a valid database-bearing map; the nested case lacks `:sample/unit` throughout. Equality of two false validations cannot establish acceptance preservation. Use the real fixture database plus a valid id, and a valid nested value, with explicit expected true/false assertions. Row equality and the other round-trip assertions already give sufficient evidence to accept this small fix.

## Task laws, simplicity and test quality

Native values are preserved: task handles are strings under the clarified identity rule; must-read/see-also links remain symbols; task edges are values, never refs. Optional sets are omitted and clearing retracts; missing base/current/proposed values are omitted rather than stored as nil. The authored entry is declared closed; its actual enforcement is P1-1. Stored task entities and extensible annotation/result maps appropriately remain open. Closing those would not fix authored admission.

`seon.issue` remains the task reconciliation owner. Publication and cluster adoption orchestrate it; analyzer/fn own program annotation production. `:seon.task/task` is partitioned `:seon.data`, and `seon.program/program-attributes` derives program membership from entity partitions (`src/seon/program.cljc:39–67`). Neither change adds task identity to the program roster. This satisfies “tasks never ride program merge” structurally; it is not proof of the missing destination gate. The shared `task-delta`, ordinary immutable maps and dependency parser are preferable to duplicate registries or stateful revision machinery.

The tests improve materially: real analyzer rows and canonical transaction fixtures; identity retained across intent change; namespace/alias edges; set clearing; explicit-basis competition; malformed input with unchanged basis; positional annotation checks; real pull refusal; publication-seam reconciliation/deletion; adoption add/remove/no-op. No Var redefinitions or copied evaluator appear in these changes.

Limits matter despite green: the publication tests invoke private `index-tasks!`, not full `publish!`; adoption tests invoke the task transaction builder, not full `adopt-rows!`. The source-only regression does not construct the claimed stale file-digest state or run changed-path publication. The program-partition assertion proves attribute classification, not an actual merge. Conflict tests cover sequential proposals with an explicit basis but not an intervening write after adoption preparation. Add focused cases at the existing owners for P1-1 through P1-3 rather than another fixture framework. Strengthen no-op proof with unchanged commit/basis as well as `:changed? false`.

## Evidence, cost and review limits

Pinned dependencies at the task endpoint:

- `reference-code/malli`: `befcd302a8e626a67d84b80aabee0cd1b38dadb6`.
- `reference-code/edamame`: `63373df6ab056d86d106a79caecc2ebfca18d357`.
- `reference-code/datahike`: `4157916b5a1b8d80d7f809c889bce1b098b1d75d`.

- Malli `-parse-entry`, `reference-code/malli/src/malli/core.cljc:487–535`, supplies the S0 grammar. Task validation uses Malli `m/explain`, with `me/humanize` confined to the explanation field.
- edamame `parse-string-all`, `reference-code/edamame/src/edamame/impl/parser.cljc:987–998`, loops to EOF; it replaces the former single-form reader.
- Datahike's `:db.fn/call`, `reference-code/datahike/src/datahike/db/transaction.cljc:1171–1172`, passes transaction-time database state. This guarantees recomputation, not preservation of a caller basis that the caller never passed.
- Task revision range: source +230/−105 (net +125), schemas +46/−15, seed +3/−1, tests +118/−43. Gate/adoption range: source +97/−41 (net +56), tests +36/−5 (net +31). These are scoped diff counts, not total feature size. Existing owner size acceptance stands; no new size veto.
- S0 landing reports 0.7–1.5 ms per spelling, first 8.2 ms, and census 473 ms before/467 ms after. Gate landing reports file read 0.3 ms, task delta 1.8 ms, findings 55 ms; whole-file reconciliation now scales with task inventory on every publication. No caching is justified by those measurements.
- **P2 evidence debt:** the gate landing records 56.5 s init, 5.2 s final init and 11.8 s test request, without a new issue/authorization citation for the over-ten-second work. It names 37 s analyzer work but does not close the performance law. Parent/change annotation-index timings and memory evidence remain absent. Root should reconcile the landing with the latest supplied green run and retain exact run/authorization/issue references; this review supplies no new timing or memory result.

Applied the data-oriented Clojure, data-modeling and clojure-testing skills. The prior stale testing-skill statement remains: it says `seon.test/run` does not arm automatically, while `src/seon/test.clj:2060` invokes `instrument/apply!`. This is recorded for its owner, not edited under a one-file review grant. The language table's older “keyword values” wording also trails the ruled string handle in 3b.6; native string handles are correct, not a regression to repair.

Only this review file is changed. Shared-tree/runtime defects did not block static review; no foreign sessions, files or resources were modified. Required follow-up is bounded to the named admission, task-writer and merge-owner seams above.
