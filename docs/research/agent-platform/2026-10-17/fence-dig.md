---
title: "Fence dig: why the merge and done path carries guard sites, by cause"
type: research
status: "evidence only, Haiku lane fence-dig 2026-10-17; judgement belongs to Opus and Astra"
---

# Fence dig (2026-10-17)

Owner question: why were these checks and fences put in, what is the root cause, and how can it be handled as a
platform constraint instead of edge case by edge case. This dig counts the guard patterns mechanically over the eight
files named in the assignment, samples 50 sites, classes each by one cause, and counts the contracts and reads on the
path. It judges nothing; the classes are evidence to be checked by the owner's reviewers.

## 1. Method

- **Files** (`src/seon/cluster/source.clj`, `src/seon/merge.clj`, `src/seon/cluster.clj`, `src/seon/test.clj`,
  `src/seon/test/runner.clj`, `src/seon/context.clj`, `src/seon/turn.clj`, `src/seon/cluster/agent.clj`). `seon.cluster`
  and `seon.test` are counted whole-file, not only their gate and step parts, so the per-file counts are upper bounds
  for the merge path. `seon.turn` (5,999 lines) and `seon.test` (3,476 lines) are the largest contributors and are not
  all merge path.
- **Counting:** `rg -c` for each literal pattern per file; the figures below are line counts, and one line can hold
  more than one site.
- **Sampling:** 50 sites, spread over the patterns (22 `:seon.error/at`, 12 `try`, 7 `when (map?`, 4 `read-refusal?`,
  4 `:seon.error/type`, 1 `nil?`). Each sampled line was located in the current tree.
- **Adding commit:** `git log -S"<first 60 characters of the trimmed line>" --reverse`, oldest commit that changes the
  count of that string. This approximates the commit that introduced the line. It can be wrong when an identical
  prefix existed earlier; `git blame` was run too, but its answer is the last commit that touched the line, which is
  often a later refactor (for example `ff61d0b29`), so it is not used as the adding commit.
- **Cause classes:** (1) error carrier, a read or function returns a refusal map and the caller checks; (2) two
  derivations reconciled; (3) loose contract, the callee's schema admits a refusal so the shape is not guaranteed;
  (4) symptom fix, a fence added where a failure was seen, producer elsewhere; (5) resource lifetime; (6) other,
  described in the table.

## 2. Pattern counts per file

| File | `:seon.error/at` | `read-refusal?` | `:seon.error/type` | `try` | `catch` | `(when (map?` | `(when (seq` | `(if (nil?` | `db/q`+`db/pull` calls |
|---|---|---|---|---|---|---|---|---|---|
| `src/seon/cluster/source.clj` | 55 | 5 | 0 | 29 | 13 | 4 | 7 | 1 | 23 |
| `src/seon/merge.clj` | 27 | 0 | 1 | 7 | 0 | 0 | 2 | 0 | 12 |
| `src/seon/cluster.clj` | 25 | 0 | 0 | 19 | 5 | 6 | 14 | 0 | 38 |
| `src/seon/test.clj` | 91 | 1 | 0 | 36 | 27 | 1 | 16 | 0 | 71 |
| `src/seon/test/runner.clj` | 7 | 1 | 5 | 8 | 8 | 0 | 15 | 0 | 37 |
| `src/seon/context.clj` | 57 | 1 | 2 | 1 | 0 | 2 | 2 | 0 | 35 |
| `src/seon/turn.clj` | 48 | 6 | 6 | 15 | 16 | 3 | 13 | 1 | 75 |
| `src/seon/cluster/agent.clj` | 18 | 3 | 4 | 25 | 16 | 16 | 9 | 0 | 53 |
| **Total** | **328** | **17** | **18** | **140** | **85** | **32** | **78** | **3** | **344** |

Notes: `:seon.error/at` appears as a lookup (`(:seon.error/at x)`, `not-any?`, `contains?`) in 302 of its 328 lines;
the rest are constructors or docstrings. `(when (seq` is mostly a collection-emptiness test, not a shape check, and is
listed for completeness, not as a fence count.

## 3. Sample of 50 sites

Class column: the cause class from section 1. The adding-commit column is the oldest commit whose diff added the
sampled line's text.

| Site | Pattern | Adding commit (date, subject) | Class |
|---|---|---|---|
| `src/seon/cluster/source.clj:59` | error-at | 245f693f6 2026-09-20 Adopt changed publication identities from transaction reports | 1 |
| `src/seon/cluster/source.clj:781` | error-at | 331eec6ec 2026-10-15 One merge base phase 2, slice 1: a forward merge is a merge commit; one gr | 1 |
| `src/seon/cluster/source.clj:886` | error-at | 49b2ac05d 2026-10-15 Merge list by reach closure (phase 1a): seon.cluster.source/merge-list ove | 1 |
| `src/seon/cluster/source.clj:950` | error-at | 6f1d9b0cc 2026-10-16 Fold changed call edges into held merge-member contributions | 1 |
| `src/seon/cluster/source.clj:1023` | error-at | 6f1d9b0cc 2026-10-16 Fold changed call edges into held merge-member contributions | 1 |
| `src/seon/cluster/source.clj:1174` | error-at | 72ab60a86 2026-09-28 my.program/diff: the branch's added, changed and removed declarations agai | 1 |
| `src/seon/cluster/source.clj:1311` | error-at | 8592c669d 2026-10-15 Done rule judges what the branch alone decides of its merge: a clj-kondo e | 1 |
| `src/seon/cluster/source.clj:1418` | error-at | 003a931d4 2026-09-23 merge: prepare-merge! tests a candidate's replacements through the save ga | 1 |
| `src/seon/cluster/source.clj:1515` | error-at | e67cb2ba0 2026-10-14 TASK-CHAIN S2b-2: seon.cluster.source/done-refusals, the done rule for eve | 3 |
| `src/seon/cluster/source.clj:1683` | error-at | 73734df97 2026-10-16 Done rule: a proof whose result never started is unknown evidence, not a r | 3 |
| `src/seon/merge.clj:154` | error-at | 7c3077b9e 2026-10-12 M6 slice 1: seon.cluster.squash becomes seon.merge (namespace, test namesp | 1 |
| `src/seon/merge.clj:454` | error-at | 2f713f0de 2026-10-15 merge-main!: a forward merge brings the entities main added that its decla | 1 |
| `src/seon/merge.clj:626` | error-at | 05d3eb2ed 2026-10-12 M9 narrowed: publication of a merge is the next pass's derived due work (s | 1 |
| `src/seon/test.clj:194` | error-at | 17034b4e1 2026-09-24 seed preparation: a deftest's ordered :seon.test/seed prepares its member  | 1 |
| `src/seon/test.clj:1705` | error-at | 5b71e4670 2026-10-16 Make unchanged eval requests reuse branch changes and test evidence | 1 |
| `src/seon/turn.clj:5281` | error-at | c5eb7b814 2026-10-13 A default deftest judges the task it is given: the :seon.test/task test-ru | 1 |
| `src/seon/turn.clj:5515` | error-at | 676578c36 2026-10-15 A reply says one thing about its tests: the defn value names no test statu | 1 |
| `src/seon/turn.clj:5604` | error-at | 56038bbb1 2026-10-16 One member list: the test step runs, the done rule judges and the merge ga | 1 |
| `src/seon/context.clj:121` | error-at | e1b547528 2026-10-14 S5 D3+D4a: chain! of a filed task runs its first task; a task-reading resu | 1 |
| `src/seon/context.clj:282` | error-at | 1932ed7e3 2026-10-13 The chain in the block (H3): :seon.context/chain names a step's subject, t | 1 |
| `src/seon/context.clj:581` | error-at | ade6e3059 2026-10-08 CX-1: the model sees (help), its own model exchanges, and its block last ( | 1 |
| `src/seon/cluster.clj:1883` | error-at | 6e8cadca1 2026-09-20 Read publication findings through changed file references | 1 |
| `src/seon/cluster/source.clj:1209` | try | fa0b0b6cb 2026-10-12 M8 (c) gate: root's merge compares the branch with main once and takes the | 1 |
| `src/seon/cluster/source.clj:1259` | try | 251d40805 2026-10-13 One merge verdict; prepare by task symbol; the writer's task-basis check f | 2 |
| `src/seon/cluster/source.clj:1941` | try | e984eb0ab 2026-10-09 write-back rebases by declaration: seon.cluster.source/proven-file writes  | 1 |
| `src/seon/cluster/source.clj:2124` | try | af44dda7c 2026-10-10 A written schema keeps its authored layout (task seon.cluster.source/a-wri | 6 |
| `src/seon/cluster/source.clj:2229` | try | ff61d0b29 2026-10-10 A function and its schema merge and write back as one unit (task seon.clus | 6 |
| `src/seon/cluster/agent.clj:966` | try | 45833be3e 2026-09-26 cluster/agent: releasing an execution releases the executions acquired thr | 6 |
| `src/seon/cluster/agent.clj:1270` | try | 1ada78050 2026-09-22 Acquire live and isolated execution handles through the agent entrance | 5 |
| `src/seon/cluster/agent.clj:2277` | try | bdc09786a 2026-10-14 TASK-CHAIN S3c-1: an end that is not done bails to root once; my.root/run! | 5 |
| `src/seon/turn.clj:5188` | try | 5d8e803f7 2026-09-25 R1: one fault route — every producer records through fault! on its own t | 6 |
| `src/seon/turn.clj:5246` | try | e6c3487ad 2026-09-26 turn, agent: a fired turn bound requests its owner's stop and settles its  | 5 |
| `src/seon/cluster.clj:2009` | try | d77a9461a 2026-09-24 cluster: an adoption failing after roots change keeps test members out unt | 6 |
| `src/seon/test/runner.clj:2641` | try | baa0afca3 2026-09-19 test: query recorded run tallies and retire the file basis | 6 |
| `src/seon/cluster/agent.clj:1829` | when-map | 76c4bed09 2026-10-14 TASK-CHAIN S3a-3: root advances a chain on its branch, and the same pass s | 1 |
| `src/seon/cluster/agent.clj:2004` | when-map | 990a441cd 2026-10-11 R1 slice 3: db<->flow writes the attempt first and makes Flow follow | 1 |
| `src/seon/context.clj:307` | when-map | 2dba90a5b 2026-10-14 TASK-CHAIN S3a-2b: a chain agent's block names its chain through next and  | 1 |
| `src/seon/cluster/source.clj:614` | when-map | 8de7a8868 2026-09-20 Return the stored source commit without transacting an unchanged digest | 1 |
| `src/seon/cluster/source.clj:1004` | when-map | 6f1d9b0cc 2026-10-16 Fold changed call edges into held merge-member contributions | 1 |
| `src/seon/cluster.clj:1625` | when-map | 9e0f1cba8 2026-10-08 Reuse publication derivations and read selected identities | 1 |
| `src/seon/turn.clj:1991` | when-map | 8822666ce 2026-09-26 turn: opening-deferred? reads the outside-wake anchor once (spent-runs tak | 1 |
| `src/seon/cluster/source.clj:1739` | nil | 251d40805 2026-10-13 One merge verdict; prepare by task symbol; the writer's task-basis check f | 3 |
| `src/seon/cluster/agent.clj:308` | read-refusal | 4a591af91 2026-09-27 agent identity render drops the duplicate :seon.agent/issues and its conte | 1 |
| `src/seon/turn.clj:1980` | read-refusal | c3bca983b 2026-09-28 issues cut, slice 2: convert every caller of seon.issue (turn budget rebas | 1 |
| `src/seon/cluster/source.clj:1239` | read-refusal | 251d40805 2026-10-13 One merge verdict; prepare by task symbol; the writer's task-basis check f | 2 |
| `src/seon/cluster/source.clj:1375` | read-refusal | 3c5f650a0 2026-10-15 A prepared merge carries only its merge list; a conflict in a change that  | 1 |
| `src/seon/cluster/agent.clj:2351` | error-type | c62266fd4 2026-10-15 MERGE-STEP phases B-D (part): a refused merge keeps its merge branch, root | 1 |
| `src/seon/merge.clj:629` | error-type | 3da1f6449 2026-10-12 M6 slice 2: one merge keyed by the assignment. seon.merge/merge! merges an | 1 |
| `src/seon/turn.clj:3403` | error-type | 33fb994f2 2026-10-07 errors S1: an error names its variant on the :seon.error/type key, never a | 1 |
| `src/seon/test/runner.clj:230` | error-type | be32e7501 2026-10-12 Record typed test errors and recognize unimplemented Prove products | 6 |

## 4. Share of each cause class (50 sampled sites)

| Class | Count | Share |
|---|---|---|
| (1) error carrier | 35 | 70% |
| (2) two derivations | 2 | 4% |
| (3) loose contract | 3 | 6% |
| (4) symptom fix | 0 | 0% |
| (5) resource lifetime | 3 | 6% |
| (6) other | 7 | 14% |

Class (6) breaks down as: swallowing `catch` that turns a failure into `false` or `nil` (`source.clj:2124`,
`source.clj:2229`); an exception turned into a value at a read (`test/runner.clj:2641`, `test/runner.clj:230`); a
fault-recording and an identity-restore path (`turn.clj:5188`, `cluster.clj:2009`); and a deref of a pending start
(`agent.clj:966`). The `(4)` count is zero: none of the 50 adding-commit subjects contains the word "fix", and most of them are feature or consolidation commits that introduced the check together with its caller. That is a result for this sample, not a proof that symptom fixes are absent from the 175.

Class (1) dominates in every pattern: each `:seon.error/at` check, `read-refusal?` test and `when (map?` branch is a
caller testing a value a read or function returned in place of an answer.

## 5. Top adding commits in the sample

| Adding commit | Sites | Subject |
|---|---|---|
| `6f1d9b0cc` (2026-10-16) | 3 (`source.clj:950`, `:1004`, `:1023`) | Fold changed call edges into held merge-member contributions |
| `251d40805` (2026-10-13) | 3 (`source.clj:1239`, `:1259`, `:1739`) | One merge verdict; prepare by task symbol; the writer's task-basis check first in accept-merge! (TASK-CHAIN phase 4, slice 4a) |

The other 44 sites were each added by a distinct commit; no other commit added two sampled sites. The two repeated commits
are both derivation consolidations: they moved a verdict, a fold or a basis check into a second place rather than
removing one, which matches audit section 5's "pattern in the history".

## 6. Contract counts on the path

| Measure | Count | Notes |
|---|---|---|
| `:malli/schema` forms in the eight files | 762 | cluster/agent 111, turn 168, runner 93, context 61, test 89, cluster 88, merge 24, source 128 |
| Schema lines naming `:seon.error/value` | 166 | turn 63, context 34, test 24, source 14, runner 8, cluster 9, merge 9, agent 5 |
| Schema lines naming `:any` | 15 | |
| Schema lines naming `[:map]` | 4 | |

These are line counts over whole schemas, not output-slot counts. The output slot of each contract was not isolated
by script in this dig, so the number of functions whose output admits a refusal is not measured here. The owner can
get it from `seon.contracts` or a Malli walk of each `:=>` form.

The reads that refusals come from: `seon.db/q` and `seon.db/pull` are called 344 times across the eight files. Its
input schema admits `:seon.error/value` (`src/seon/db.clj`, `q`, its `:malli/schema`), and its body returns
`refused-read` for a bad query form, so the carrier is built into the reader's own contract. `db/read-refusal?`
(`src/seon/db.clj`, `read-refusal?`, docstring: "THE ONE TEST every reader applies to a value a read handed it in
place of an answer") is the test the callers repeat.

## 7. Evidence for the owner question

- The dominant cause (class 1) is produced at the reader: reads return a refusal value, so every caller of 344 read
  sites carries the check. Under the revised law (c04a309e0, "Nothing crashes": non-tool functions throw their error
  map inside `ex-info`), reads that throw would remove most of the 328 `:seon.error/at` checks at their source. This
  is the S-reads conversion named in CLAUDE.md, and the 2026-10-17 audit's 175 figure depends on it.
- Class (2) and (3) are the two places the sample shows a second derivation or a schema that admits the refusal
  (`251d40805`, `6f1d9b0cc`, `green-result?` in `source.clj:1515`, `executed` in `source.clj:1683`).
- Class (6) items are swallowing catches and exception-to-value conversions; those are fences by the law and need
  their own task to name the producer that should throw or return a typed value.

## 8. Limits

- Sampling is 50 of about 330 `:seon.error/at` sites, 140 `try` forms and 17 `read-refusal?` sites; the class shares
  are for this sample, with the usual error for n = 50.
- The adding commit is found by string match on a 60-character prefix, so it may be an earlier identical line. The sampled lines were read from the current tree; the match was not checked against the full diff history.
- The 175 figure is the audit's count of guard sites on the merge and done path. This dig's per-file counts are whole
  files and do not reproduce that number; they are upper bounds for the path, not the path count itself.
- No source was edited. No SCI request, no default restart.
