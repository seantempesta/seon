---
title: "Pre-brief: TASK-CHAIN slices S1b-1, S1b-2, S3d-1"
type: research
status: pre-brief at HEAD 311ae5a0d, read-only
created: 2026-10-14
---

# Pre-brief: S1b-1, S1b-2, S3d-1 (TASK-CHAIN §7)

Gathering only. Base: HEAD `311ae5a0d`, working tree as in `git status` at 2026-10-14 11:39 (+07). Dated evidence, so
`file:line` is cited here. Marks: **V** = seen in code or output by this pre-brief; **U** = not verified (reason given).
Nothing was run on default's JVM. No SCI request was made. No `bin/seon check` was run (one throwaway boot per test is
not cheap; the stored run is in section 5).

## 0. What the rows say (TASK-CHAIN §7, verbatim red proofs)

| Slice | Red proof as written | Model |
|---|---|---|
| S1b-1 | `seon.task-test/task-members-have-defaults-and-kinds`: decoding `{}` gives `{:seon.task/execution :held}`; a bad name is refused by the writer; a supplied `:running` survives the writer | Sonnet |
| S1b-2 | owned members are exactly `#{instructions proofs}` by schema property; a branch and its base named by a chain fact survive `retire-unclaimed!`, held chains included (no test name given) | Sonnet |
| S3d-1 | `seon.context-test/a-referenced-function-shows-its-doc-view`: distance 1 shows docstring, contract, schema descriptions and the read form, no source; distance 2 keeps the source | Sonnet (after S3a) |

§7 header: S1a "lands first"; S1b and S1c are "written against `plan`". S3d-1's row says "(after S3a)". S3a-1..3 are
status `—` at HEAD. **V** (`docs/seon/schedule/cards/TASK-CHAIN.md` §7 rows and table).

## 1. S1b-1: defaults and names

| Symbol / key | Where (HEAD) | State at HEAD | Callers | Docstring first line | Status |
|---|---|---|---|---|---|
| `seon.task/task-delta` (private) | `src/seon/task.clj:281` | takes `projection stored by-id basis`; a new task with no owning child is written `:seon.task/execution :held` by `(assoc proposed … :held)` (`:else` branch); an owned child has execution dissoc'd (`owned-children` branch) | `src/seon/task.clj:364`, `:414` | "The writer's delta from `stored` to `by-id` tasks, by handle, retractions first, so a task" | V |
| `seon.task/held-tx` (private) | `src/seon/task.clj:327` | `[:db/add task :seon.task/execution :held]` for every stored task with no execution and no parent; reads with `db/q` (`(not [?e :seon.task/execution _])`, `(not [_ :seon.task/children ?e])`) | `src/seon/task.clj:364`, `:413` | "`[:db/add task :seon.task/execution :held]` for every stored task with no execution and no parent: an" | V |
| `seon.task/symbol-name?` | `src/seon/task.clj:55` | public `defn`, `{:malli/schema [:=> [:cat :string] :boolean]}`; **not** in the name schema | `src/seon/task.clj:153` (file ingestion, `refuse-task! :invalid-name`), `:710` (`create!`'s separate check). `test/seon/test_support.clj:1307` mentions it in a docstring only | "Whether `task-name` reads back as one symbol's name: no whitespace, delimiter, `/` or `:`," | V |
| `:seon.task/name` schema | `resources/seon/schemas/seon.task.edn:8` | `[:string {:min 1 :description "…(seon.task/symbol-name?)…"}]`: no `symbol-name?` predicate yet | all `:seon.task/name` users | n/a | V |
| `:seon.task/execution` schema | `resources/seon/schemas/seon.task.edn:51` | `[:enum {:seon.db/index true :seon.wake/starts true …} :held :running :paused :stopped :cancelled]`: **no `:default`** | — | — | V |
| `:seon.task/declaration` / `:seon.task/task` | `seon.task.edn` (declaration and task maps) | no `:default` on any task map (chain-in-one-transaction.md §3 says the same; I did not re-decode) | — | — | V (grep) / U (decode) |
| `seon.task/task-members-have-defaults-and-kinds` | — | **does not exist** (rg over `src test resources` returns nothing) | — | — | V |
| `seon.task-test` deftests | `test/seon/task_test.clj` | 13 deftests; none named for defaults or names; `refuse-task! :invalid-name` is exercised only in `test/seon/task_ingestion_test.clj:122` (`"x y"`) | — | — | V |

Writer refusal path for a bad name today: `seon.task/create!` (`src/seon/task.clj:710`) and file ingestion
(`:153`). The `:seon.task/name` schema itself does not refuse. **V**.

Stored run (not re-run): `docs/research/agent-platform/2026-10-14/baseline-s1.md` (commit `de579d862`, HEAD
`5e0774bd6`): `malformed-task-files-refuse-by-name-without-writing`, `ingested-tasks-answer-both-ways-and-unchanged-input-writes-nothing`,
`one-write-clones-a-chain-and-one-running-readies-survey`, `the-task-facts-adopt-on-a-branch-of-the-live-store`,
`an-adoption-keeps-a-merged-refinement-the-files-have-not-seen`, `only-a-merge-completes-a-leaf`: all **green** at
`5e0774bd6`. The S1b-1 red is not in that set (it does not exist). HEAD has moved to `311ae5a0d`: **U** for current status.

Existing tests that touch the same behaviour (candidates for the "supplied `:running` survives" assertion):
`test/seon/task_test.clj:108-127` (writes `:seon.task/execution :held` and reads it back), `:237-238` (`set-running`),
`:349-356` (`a-running-subject-readies-its-first-step-only`), `:396` (`one-write-clones-a-chain-and-one-running-readies-survey`,
reads `:running` on a supplied execution per `prebrief-s1.md:51`). **V** (lines seen), **U** (assertions beyond the
lines quoted).

Claims: no lane claims `src/seon/task.clj`, `resources/seon/schemas/seon.task.edn` or `test/seon/task_test.clj`
(`bin/lane status <lane>` for all five live lanes). `git status --porcelain` shows none of the three modified. **V**.

Upstream dependency: S1a (`intent` → `plan`) is not landed. `with-references` still reads `intent`
(`src/seon/task.clj:274-279`), and `schemas/seon.task.edn` still declares `:intent`, `:step`, `:children`, `:needs`,
`:product`. **V**.

## 2. S1b-2: chain schemas

| Item | Where (HEAD) | State at HEAD | Status |
|---|---|---|---|
| `:seon.task/next` | nowhere in `src resources test docs/seon/architecture` | absent | V |
| `{:seon.task/owned true}` | nowhere in `src resources test` | absent; no schema property of that name is read anywhere | V |
| `owned` as a derived set | `src/seon/task.clj:290` (`owned` in `task-delta`): `(keys (m/entries … :seon.task/declaration))` plus `:seon.task/references` | today "owned" means every declaration key, not `#{instructions proofs}` | V |
| `:seon.task/proofs` | `resources/seon/schemas/seon.task.edn:26`, `:40`, `:143`, `:153` (`:obligations`) | exists, optional, no owned property | V |
| `:seon.task/instructions` | `resources/seon/schemas/seon.task.edn` (task and declaration maps) | exists, optional | V |
| attempt `outcome` `:done` | `resources/seon/schemas/seon.task.edn:75` (`:outcome [:enum …]`) | enum values present: `:closed`, `:done`, `:merged` (seen by grep of the line) | V |
| outcome readers | `src/seon/task.clj:1076` (`leaf-done`: `[?a :seon.task/outcome :merged ?tx]`), `:1148`, `:1183`-`:1185`; `src/seon/merge.clj:200-202`, `:285-289` (`{:done :merged}` mapping) | readers still test `:merged` only | V |
| `:seon.chain/*` (chain fact) | nowhere in `src resources test` | absent; no `seon.chain.edn` schema file (`resources/seon/schemas` has `seon.cluster.wake.edn`, `seon.wake.edn`, no chain file) | V |
| `:seon.wake/starts` | used in `resources/seon/schemas/seon.task.edn:48,51,111`, `seon.schedule.task.edn:2`, `seon.agent.edn:134`; declared in `seon.wake.edn` (not opened) | exists as a schema property | V (uses) / U (its definition) |
| `seon.cluster.registry/retire-unclaimed!` | `src/seon/cluster/registry.clj:552` | public; "Retire every branch no fact claims and no connection here holds"; claims = `claimed`, main `:db`, cluster branches, each open agent row's branch (`cluster-claims`) | V |
| `seon.cluster.registry/cluster-claims` (private) | `src/seon/cluster/registry.clj:530` | returns `#{branch}` plus agent branches when `branch` is a cluster branch, else nil; **no `:seon.chain/branch` clause** | V |
| caller of `retire-unclaimed!` | `src/seon/cluster/boot.clj:202` (`#{source/current-branch}`); tests `test/seon/cluster/registry_test.clj:715`, `:718`; `test/seon/merge_test.clj:429` | — | V |
| retire red test | `test/seon/cluster/registry_test.clj:692` `boot-retires-only-branches-no-fact-claims` (contains the `retire-unclaimed!` assertions at 715-718) | exists; no held-chain or chain-fact case | V |
| owned-members proof | — | **no deftest** (no name given in the row) | V |

Stored status: `boot-retires-only-branches-no-fact-claims` is not in `baseline-s1.md`; **U**.

Claims: `lane-*` lanes hold no file here (`src/seon/cluster/registry.clj`, `test/seon/cluster/registry_test.clj`,
`src/seon/cluster/boot.clj` not in any `bin/lane status <lane>` listing). `git status`: none modified. **V**.

## 3. S3d-1: doc view render tier

### 3.1 The function render today

| Item | Where (HEAD) | State at HEAD | Status |
|---|---|---|---|
| `seon.render.ns/function-ai` | `src/seon/render/ns.clj:179` | distance 0: `{:seon.fn/sym, :seon.fn/doc <first line>}`. Distance ≥1 (`near?`): adds `:seon.render.ns/source-lines` (whole source split by line) when source exists; `:seon.fn/spec` when spec exists and source lacks `:malli/schema`; `:arglists` only when no source; `:seon.render/operations` = `my.program/callers`, `my.program/tests-reaching`, plus `clojure.repl/source` when no source | V |
| doc line | `function-ai` uses `first-doc-line` on `:seon.fn/doc` | — | V |
| `node-distance` (private) | `src/seon/render/ns.clj:95` | `(max 0 (long (get unit :seon.render/distance 1)))`: an absent distance is 1 | V |
| distance tier shape | `function-ai` has two tiers only (0 and ≥1), so the doc view has no tier yet | — | V (code) |
| `seon.render.schema/description` (private) | `src/seon/render/schema.clj:94`, `defn-` | returns the `:description` of a `[:kind {…}]` schema vector; no caller outside its file (rg of the qualified symbol: none) | V |
| `seon.ref/cite-once` (private) | `src/seon/ref.clj:495`, `defn-` | "answers with each name shown once" | V |
| `seon.ref/render-doc-ai` | `src/seon/ref.clj:662` | document/section references only (`:seon.ref/path`, `:anchor`), operation `my.fs/section`; not a function view | V |
| `seon.ai.tokens/estimate` | `src/seon/ai/tokens.cljc` | exists (`estimate`, `estimate-of-characters`, `estimate-chars`) | V |

Callers of `seon.render.ns/function-ai`: `resources/seon/schemas/seon.fn.edn:130` (declared `:seon.render/ai`);
`test/seon/render/ns_test.clj:223`, `:226` (`a-function-renders-its-source-by-name`, `:214`; its distance-0 assertion is
at `:226`, its distance-1 assertion past `:226` is **U**); `test/seon/render/entity_pairs_test.clj:58`, `:61`, `:79`;
`test/seon/ref_test.clj:138`, `:301-307` (`[:function …] 'seon.render.ns/function-…`, asserting the render symbol per
reference kind). **V**.

### 3.2 Must-read selection

| Item | Where (HEAD) | State | Status |
|---|---|---|---|
| `seon.context/task-sections` | `src/seon/context.clj:559` (public) | builds `:seon.context/must-reads` as `others` = the held task's references not in the proof resolution (`(remove #(contains? resolution …))`), at `:580-593` | V |
| `task-references` (private) | `src/seon/context.clj:182` | `(ref/references database {:seon.ref/text (intent + instructions) :seon.ref/names proofs})` | V |
| `must-read-ai` (private) | `src/seon/context.clj:638` | renders each must-read through `seon.render/render-call` with `:seon.render/output :seon.render/ai`, "one hop beyond `unit`'s distance" | V |
| distance of must-reads | `docs/research/agent-platform/2026-10-14/doc-view-measure.md` §2 says `must-read-ai` renders at `(inc 0)` = distance 1 | not re-read in code | U (taken from the note) |
| `must-read-view` / `proofs` / `step-section` | `src/seon/context.clj` | not opened | U |

Existing context tests that read must-reads: `test/seon/context_test.clj:141-319` (`the-block-resolves-the-names-a-task-references` at `:141`, `:246`, `:267`, `:278`, `:300`, `:319`) and `test/seon/context_cost_test.clj:30`.
`test/seon/render_cache_test.clj:132`, `:155`. **V** (lines seen).

### 3.3 The red proof and its neighbours

| Item | State | Status |
|---|---|---|
| `seon.context-test/a-referenced-function-shows-its-doc-view` | **does not exist** (rg over `test` for `shows-its-doc-view` returns nothing). `test/seon/context_test.clj` is `(ns seon.context-test …)` with 30 deftests | V |
| `seon.ref-test/a-referenced-function-shows-its-source-whole` | `test/seon/ref_test.clj:101`: asserts `(:seon.fn/source entry)` of `ref/references` equals the stored source ("a function is its source, as code"). Named in the S3d-1 neighbourhood; it is a reference-level assertion, not a render-tier assertion | V (text) / U (passes at HEAD) |
| `seon.render.ns-test/a-function-renders-its-source-by-name` | `test/seon/render/ns_test.clj:214`; asserts distance 0 equals `{:seon.fn/sym, :seon.fn/doc}` (`:226`) | V (distance 0) / U (distance 1 lines) |

### 3.4 Measured token sizes (TASK-CHAIN §3a)

TASK-CHAIN §3a cites: raw block 37.4k characters, about 11.7k tokens, must-reads 9.8k; shown prompt 6.0k (91
elisions); doc views bring the raw block to about 7.1k (5.0k with schema-only nesting) and elisions to 30.

Source of the numbers: `docs/research/agent-platform/2026-10-14/doc-view-measure.md` (table §1: 37,400 chars, 11,687
tokens, must-reads 9,826, shown 6,013 with 91 elisions; table §3: block 11,973 → 7,087, must-reads 9,826 → 4,939,
block without inner references 5,022, elisions 91 → 30).

- Method in that note: REPL probe on default in JVM mode (`seon.context/current-state`, `seon.context/model-context`
  on the branch database of agent `attempt/341f9a9016cf`, read-only, nothing transacted); tokens via `seon.ai.tokens/estimate`
  (chars / 3.2). The doc-view figures come from a **scratch fold, "REPL only, not installed"** (note §3).
- **No committed script** measures these numbers. `script/seon/dev/context_corpus.clj` mentions `seon.ai.tokens` (`rg`
  match); not opened for this pre-brief. **V** (no script of that name in the note's method), **U** (contents of
  `context_corpus.clj`).
- The note's "9.5k tokens" for the earlier TASK-CHAIN figure divided by 4; the note corrects it to chars/3.2. **V** (note text).
- Measured on a branch database of an agent whose task is `seon.ref/define-a-pathless-reference-section-is-a-missing-file-error`
  (Run 25 defect chain). Single warm samples. The note is dated 2026-10-14. **V**.
- TASK-CHAIN §3a says "the doc view becomes distance 1 and the source distance 2". The note says distance 1 is today's
  full view, so the tier must be inserted (a shift, not a relabel). **V** (note §2).

## 4. Overlap between S1b (both rows) and S3d-1

| File | S1b-1 | S1b-2 | S3d-1 | Overlap |
|---|---|---|---|---|
| `src/seon/task.clj` | yes (`task-delta`, `held-tx`, `symbol-name?`) | `owned` derivation at `:290`, next/owned writer | no (`task-ai` not named by the row) | S1b-1 and S1b-2 overlap here, serial within S1b |
| `resources/seon/schemas/seon.task.edn` | yes (`:name`, `:execution :default`) | yes (`:next`, owned props, chain fact `:seon.chain/*` if placed here) | no named change | S1b-1 / S1b-2 only |
| `test/seon/task_test.clj` | yes (new deftest) | yes (owned, chain fact) | no | S1b only |
| `src/seon/cluster/registry.clj`, `src/seon/cluster/boot.clj`, `test/seon/cluster/registry_test.clj` | no | yes (`cluster-claims` clause, retire tests) | no | S1b-2 only |
| `src/seon/render/ns.clj` | no | no | yes (`function-ai`, `node-distance`) | none |
| `src/seon/render/schema.clj` | no | no | yes (`description` public) | none |
| `src/seon/ref.clj` | no | no | possibly (`cite-once` used by doc-view nesting) | **U**; `lane-ref-depth` holds `src/seon/ref.clj` and `test/seon/ref_test.clj` |
| `src/seon/context.clj` | no | no | not named (its must-reads are read, not changed, by the row) | none named |
| `test/seon/context_test.clj` | no | no | yes (red proof) | none |
| `resources/seon/schemas/seon.fn.edn` | no | no | not named (`:seon.render/ai` already present) | none |

**Result:** no file is named by both an S1b row and S3d-1. S1b-1 and S1b-2 overlap each other in `src/seon/task.clj` and
`seon.task.edn`. S3d-1 is gated by S3a in its own row, and S3a (`src/seon/context.clj`, `seon.cluster.db-flow-test` etc.)
has not started; S3d-1 therefore should not run before S3a regardless of file overlap. **V** (row text and status column).

## 5. Lane claims and status (`bin/lane`)

| Lane | Paths claimed (`bin/lane status <lane>`) | Touches a pre-brief file? |
|---|---|---|
| astra-ctx-cost | `docs/research/agent-platform/2026-10-14/ctx-cost.md` | no |
| lane-help-truth | `resources/seon/help.md`, `test/seon/bootstrap_test.clj`, `resources/seon/tasks/seon.bootstrap.edn` | no |
| lane-input-roots | none (holds no claims) | no |
| lane-ns-render | `src/my/program.clj`, `test/seon/program_test.clj`, `resources/seon/tasks/my.program.edn`, `resources/seon/schemas/my.program.edn`, `test/my/program_test.clj` | no (`src/seon/render/ns.clj` not claimed) |
| lane-ref-depth | `src/seon/ref.clj`, `test/seon/ref_test.clj`, `resources/seon/tasks/seon.ref.edn`, `src/seon/sci/reader.cljc`, `src/seon/program.cljc` | **yes for S3d-1 only if** `ref.clj` carries its render (U) |

`git status --porcelain` on every pre-brief file: `src/seon/ref.clj` and `test/seon/ref_test.clj` are modified (lane-ref-depth's
uncommitted work); the rest are clean. **V**.

## 6. Unverified or not checked

- Current status of every named test at HEAD `311ae5a0d` (only the stored run at `5e0774bd6` was read). **U**.
- S1b-2's owned-members property: no test exists, so nothing to run. The row gives no test name. **V** (absence).
- `retire-unclaimed!` behaviour for held chains (no chain fact exists). **V** (absence) only.
- The distance behaviour of `must-read-ai` (taken from the note, not re-read). **U**.
- `function-ai` distance-1 assertions in `ns_test.clj` past line 226. **U**.
- Whether `ref.clj` renders function references (which decides the lane-ref-depth overlap for S3d-1). **U**.
- `script/seon/dev/context_corpus.clj` as a measuring script. **U**.
