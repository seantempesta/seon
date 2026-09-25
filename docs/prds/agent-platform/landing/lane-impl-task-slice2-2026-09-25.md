---
type: landing
lane: impl-task-slice2-opus
status: "two of three records migrated; the MCP record is blocked as the review says; committed, unverified until root runs the commands below"
created: 2026-09-25
authority: docs/research/agent-platform/language-requirements-review-2026-09-25.md "The first three slices", Slice 2; spec 7.12, 7.14, 7.15, 3b.6
---

# Lane impl-task-slice2-opus: migrate the first §6 defects to tasks (2026-09-25)

This note is the dated evidence file that both migrated tasks cite in `:seon.task/evidence`.

## Broken, blocked or unproven first

- **I did not import the MCP record.** Its handle would be `"bce01f35637e"`, from
  `[seon.dev.mcp "mcp-jvm-eval-drops-reader-metadata-on-a-defn-name" :function]`.
  - The review marks it as a blocker for this cohort: its producer, `seon.dev.mcp/jvm-evaluation-form`, is
    in `script/`, and `seon.fn/source-roots` is `["src" "test"]` (`src/seon/fn.clj:35-37` at this HEAD).
  - No published row can carry its `:seon/tasks`.
  - Importing it waits for one of two things: the owner's reviewed external-source handle, or a deliberate
    extension of the indexing owner. I invented neither, and I made no `:seon.fn/sym` row for it.
  - It is not migrated. It stays under the old owner (7.15): the issue note
    `docs/seon/issues/mcp-jvm-eval-drops-reader-metadata-on-a-defn-name.md` is unchanged and is still the
    current issue input, and its part of the §6 bullet stays ("MCP drops `defn` name metadata …").
  - Because nothing migrated it, the note-to-task transfer in `seon.issue` (old path resolves to task, note
    stops being parsed) has **no production change and no proof**. No migrated record needs it yet, so I added
    no code for it.
  - Current evidence for the owner, not acceptance: the note's success probe **now passes in both modes**
    (below).
- **Neither migrated task has a naming proof yet, so both are raised and appear in `task-findings` triage
  until their annotations publish.**
  - `"132fee0467c8"` has a real regression:
    `seon.sci.entry-gate-test/every-definition-a-form-makes-is-gated-and-armed`, added by `8a531008a`. That
    test file, and `seon.sci.eval/evaluate`, belong to impl-repl-m1-opus, so neither annotation is mine to
    write. **Handoff to impl-repl-m1-opus:**
    - add `{:seon/tasks #{"132fee0467c8"}}` to `seon.sci.eval/evaluate`'s attribute map (subject);
    - add `^{:seon/tasks #{"132fee0467c8"}}` on that deftest's name (proof).

    Publish them no earlier than this commit's task file, or publication refuses `:dangling-id`.
  - `"cea47b015b23"` has no regression. The historical done-when is unmet: `seon.schema/valid?` and
    `/explain` do not exist, and there is no projection-free one-call form. It stays raised, with no
    placeholder test (4.5).
- **Nothing ran as a test.** Under the LOAD RULE I ran no test request, init or publication. What ran:
  clj-kondo (0 errors, 0 warnings on the test file and task EDN), and the REPL probes below on default
  (pid 35446). Those probes were JVM `d/with` over the live value, plus one disposable SCI branch, now
  retired.
- Default shows an open fault that is not mine: `seon.fn/gate-set is refused: dangling-id` at
  2026-09-25T17:19Z (2 occurrences), which predates this lane's edits.
- Ugly output (5.6/ugly-output law): `explain-candidate-value` returns raw Malli explanation maps, and in
  SCI their `:schema` prints as `#object[malli.core$_simple_schema$reify…]`. It belongs with the
  `"cea47b015b23"` work, so I filed no separate issue.

## What migrated

| Handle | Aspect `[ns name kind]` | File | Subject edge | Proof | State |
|---|---|---|---|---|---|
| `"132fee0467c8"` | `[seon.sci.eval "definitions-bypass-entry-gate" :function]` | `resources/seon/tasks/seon.sci.eval.edn` | `seon.sci.eval/evaluate` (handed off) | existing regression (handed off) | raised; green evidence not settlement |
| `"cea47b015b23"` | `[seon.schema "agents-can-validate" :namespace]` | `resources/seon/tasks/seon.schema.edn` | `seon.schema` ns attribute map (this commit) | none | raised |

- I took the names from the review's table. `:seon.task/name "agents-can-validate"` keeps the old token
  greppable: `rg agents-can-validate` finds the task file, the ns annotation's task and the historical
  `:seon.schema.task/agents-can-validate` in `experiments/tasks-v02/seon.schema.edn`, which the task now cites
  as evidence.
- Nothing parses that experiment file as tasks: `task-path?` accepts only `resources/seon/tasks/`. That keeps
  the task at one writable representation.
- §6 in `language-requirements-2026-09-24.md` now replaces its first two bullets with one line naming both
  handles and their files. The other bullets are unchanged, including the MCP one.

## Historical evidence preserved (7.15), not imported as truth

- **definitions-bypass-entry-gate.** The old §6 bullet said REPL forms were recognized by a hand matcher at
  `src/seon/sci/reader.cljc:245,323`, which sees only a top-level `clojure.core/defn`. The owner directed
  this fix:
  - evaluate in a `sci/fork` and diff the namespace maps;
  - gate and arm the created Vars;
  - clj-kondo stays the one analyzer.

  The limit it named: a call inside the defining form runs before the check. `8a531008a` (2026-09-24) says it
  gates every definition by matching `definition-rows` to the Vars SCI changed. Its proof was
  `entry-gate-test` run e0bab8ac7b56, 33 passed and 0 failed, on an archive root, not default. I have not
  re-run it. Whether "unindexed change" and "absent contract" stay distinct in the refusal is not verified
  here.
- **agents-can-validate.** A Codex agent delivered this on branch `exp-validate-2` from the brief alone
  (11 forms), and it was never delivered to the program. The MCP `branch list` on 2026-09-25 shows no
  `exp-validate-2`, so that branch's evaluation evidence is unavailable, and I ran nothing from it.

## New ordinary proofs (current evidence; nothing marked settled)

SCI, on disposable branch `impl-task-slice2-opus`:
- `(seon.schema/valid-candidate-value? :seon.task/handle "300aca1ad57c")` refuses by arity: the projection
  is positional and no default supplies it.
- `(seon.schema/current-projection)` returns nil in SCI.
- `(let [p (seon.schema/projection-from-database (seon.db/db))] [(valid-candidate-value? p :seon.task/handle
  "300aca1ad57c") (explain-candidate-value p :seon.task/handle "short")])` returns `[true {:errors …}]` in
  0 ms of evaluation.

So an agent can validate in two calls, but not in the one ordinary call the task asks for.

MCP record, the note's success probe, run in namespace `scratch.task-slice2-probe-meta`:
- JVM mode: both `probe-meta-a` (name metadata) and `probe-meta-b` (attribute map) keep
  `{:doc "doc" :malli/schema [:=> [:cat :int] :int] :probe/x 1}`, in 4 ms.
- SCI mode on the branch: the same result, in 1,040 ms. That is over one second, and it is the first-call
  context acquisition: `seon.cluster.agent/acquire-context!` 1,467 ms, `seon.db/with-declarations` ×1,961
  1,783 ms inclusive. Later SCI calls took 32–78 ms. The acquisition cost belongs to the acquisition owner
  and is not introduced here.

## REPL-first reconciliation (JVM, `d/with` over default's live value; nothing written)

```clojure
(let [db0 (d/db (seon.cluster.boot/connection "default"))
      files (seon.issue/task-files "/Users/sean/src/seon" nil)
      db1 (:db-after (d/with db0 (seon.issue/tasks-tx db0 files (seon.issue/task-basis db0 files))))
      db2 (:db-after (d/with db1 [{:db/id [:seon.ns/name 'seon.schema] :seon.task/tasks #{"cea47b015b23"}}]))] …)
```

- `tasks-tx` produced 2 entity maps in 0.72 ms. Pulled back, each has the authored ns, name, kind, must-read
  and evidence (sets come back as vectors, as Datahike pulls them).
- Re-ingest: `(tasks-tx db1 files (task-basis db1 files))` returns `[]`, so zero writes.
- Task to subjects: `(task-edges db2 "cea47b015b23")` returns
  `{:subjects #{seon.schema} :proofs #{} :referrers #{}}`.
- Subject to task: `(open-tasks db2 [:seon.ns/name 'seon.schema])` returns `#{"cea47b015b23"}`.
- Findings: `{:dangling {} :triage #{"132fee0467c8"}}`, because its subject is not yet annotated.
- Evidence path to task: querying `:seon.task/evidence` for this note's path returns both handles.
- `seon.fn/rows` over a one-file root holding the edited `schema.clj` returns 199 rows in 536 ms. Exactly one
  is tagged: `{:seon.ns/name seon.schema :seon.task/tasks #{"cea47b015b23"}}`.

Edge cases:
- Annotation without its task, on `db0`: `task-findings` gives `:dangling {"cea47b015b23" #{seon.schema}}`,
  and publication would refuse it by name.
- Handle that does not match its aspect: the same id with `:kind :function` refuses `:handle-mismatch` with
  `:seon.error/expected "8ca99055e7bc"` and the offending entry.
- The entry moved to `seon.sci.eval.edn` refuses `:handle-mismatch`, expecting `"4c0a36d23760"`. The file
  name is part of the aspect, so moving an entry to another namespace's file needs a new handle. This is the
  new regression's edge case.
- Old id cited in other docs: `:seon.schema.task/agents-can-validate` appears in
  `experiments/tasks-v02/seon.schema.edn` and `toy-self-documenting-experiment-2026-09-24.md`. Neither is
  task or issue input. The name token links them.

## Changed paths, sizes and commit

| Path | Change |
|---|---|
| `resources/seon/tasks/seon.sci.eval.edn` | +8 (new) |
| `resources/seon/tasks/seon.schema.edn` | +9 (new) |
| `src/seon/schema.clj` | +1 (ns attribute map) |
| `test/seon/issue/task_ingestion_test.clj` | +20 |
| `docs/research/agent-platform/language-requirements-2026-09-24.md` §6 | +4/−3 |

All five are within budget: 17 of 36 EDN lines, 1 of 6 annotation lines, 0 of 20 production lines, 20 of
30 regression lines. Net source is +1 line and net test is +20. There is no deletion credit.

## Root runs (serially, after adopting the committed paths; the task files first or together with `schema.clj`)

```
bin/test-check default --test seon.issue.task-ingestion-test/the-checkouts-migrated-records-ingest-by-aspect-and-repeat-as-a-no-op
bin/test-check default --ns seon.issue.task-ingestion-test
bin/test-check default --ns seon.schema-test
```

After publication, check it at the REPL and record the time:

```
(seon.issue/task-edges (d/db (seon.cluster.boot/connection "default")) "cea47b015b23")
```

It should return `#{seon.schema}` subjects, and `task-findings` should show no dangling handle. Watch the
first publication that includes `src/seon/schema.clj`: it re-analyzes that file.

RESET NEEDED: no.
