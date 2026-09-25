---
type: landing
lane: impl-render-pairs-opus
status: "Five commits: task and schema pairs added; namespace, function, agent HTML, runtime and turn pairs made node-shaped; ns.clj -586 lines of page dump. Agent and transcript commits are not yet adopted (foreign changed paths block --changed adoption)."
---

# Graph-node render pairs

The agent context is `walk/graph` rendered node by node through each schema's pair. This lane makes each pair
render one node: distance 0 names it, distance 1 and beyond shows its content with its neighbours as names, and the
walk renders the neighbours. Pairs read the supplied database value (owner ruling) and go through the one render
path; the floor printer stays the only clipper.

## Broken things first

1. **Data-returning pairs cannot land yet (owner rule received mid-lane).** The rule is that a pair selects data and
   returns a map for the floor to lay out. The render path refuses that today:
   - `:seon.render/ai` is `[:or :qualified-symbol :string]` (`seon.render.edn:19`), and `:html` is hiccup;
   - `seon.render/valid-projection?` (`render.clj:1138`) rejects any other output;
   - `project-node*` (`render.clj:1263-1270`) then falls back to the unprojected floor node of the whole value.

   A pair returning a map would therefore print the entire entity. Every pair here still builds text or hiccup. The
   conversion waits on the floor lane accepting map outputs.
2. **A schema row renders through the pair it declares.** `[:seon.schema/key :seon.task/task]` renders through
   `task-html` and refuses; `:seon.fn/fn` renders through `function-ai`. The cause is that the projected
   `:seon.render/ai` datoms on schema rows (334 rows) are read as the value's own override. Filed as
   `docs/seon/issues/schema-rows-render-through-the-pair-they-declare.md`. Schemas without a pair render correctly.
3. **Two core faults were recorded on default by this lane.**
   - `14c6292e…` (`seon.schema/canonical-definition`): my first adoption carried an invalid contract form,
     `[:or :vector …]`. Adoption panicked instead of preserving the malformed contract as data, which is itself a
     defect in the indexer (`fn.clj:780`).
   - `f6d16919…` (`render-contract-refusal!`, 23:44:13Z) came before that attempt. It is likely publication seeing the
     EDN pair before its function existed.

   Both are resolved in the committed tree; root may resolve both faults.
4. **Agent and transcript commits are unadopted.** `bin/seon init --dev default --changed <mine>` refuses "Source
   changed during development adoption". Its changed paths are other lanes' committed files (`boot.clj`,
   `fn_test.clj`, …). The agent HTML was proved as a scratch copy with its output explicitly validated against
   `:seon.render/hiccup`. The transcript change (two deletions) was not run.
5. **`:seon.agent/task` does not exist.** The agent node shows the task handles naming its namespace as a subject
   (`:seon.task/tasks` on the namespace row). S3 of the task-context design adds the direct relation.
6. **The agent AI pair is still the opening.** It holds identity plus issue-opening source blocks. I left it
   unchanged: issue workers depend on it until T3 (task-context design §7).
7. **The agent pair's connection requirement is gone, not declared.** The fleet table (`cluster.status/agents`) was
   the only reader of `:seon.db/connection`. The node now reads only `:seon.db/db`, which `:seon.render/unit`
   already declares, so the contract is honest with no new input.
8. **The owner's "no face" ruling.** No code keys on a separate face role. The word survives in `:seon.print/face`,
   the floor's node tag, which is for the word sweep.

## Pairs

| kind | pair | change | armed time per node (default, warm, 20 calls) |
|---|---|---|---|
| `:seon.task/task` | `seon.render.ns/task-ai` / `task-html` | new. Intent; done-when as the proofs naming the task (via `seon.issue/task-edges`); subjects and must-reads as names; the `task-edges` call | 0.50 / 0.44 ms |
| `:seon.schema/schema` | `schema-ai` / `schema-html` | new. `(seon.schema/register! k form)` from the stored form, headed by its doc line | 0.29 / 0.32 ms |
| `:seon.ns/ns` | `render-ai` / `render-html` | node. Stored ns form, doc line, public functions by name, `(dir ns)`; HTML has requires as links | 0.16 / 0.21 ms |
| `:seon.fn/fn` | `function-ai` / `function-html` | node. Authored source; callees and callers by name. Over 20 callers renders the count plus the `my.program/callers` call | 0.26–0.31 ms |
| `:seon.agent/agent` HTML | `seon.cluster.agent/render-identity-html` | identity, tasks, status (Archived / Turn open / Idle · n turns); no fleet table | 0.14 ms (scratch) |
| `:seon.runtime/entity` HTML | `render-runtime-html` | no embedded trigger message card; keeps the "Woke on" link | not run |
| `:seon.turn/turn` HTML | `render-run-html` via `html-output` | no `surface-transcript` or per-entry `id`s; `data-transcript-id` kept | not run |

## Deletions

- `src/seon/render/ns.clj`: 887 → 318 lines.
  - Deleted: `render-data` and its readers (`namespace-row`, `function-rows`, `own-schema-rows`, `schema-row`,
    `cached-schema-row`); the Malli referenced-schema closure with `catch Throwable → nil` readers
    (`schema-ref-registry`, `read-edn`, `schema-form-refs`, `normalize-schema-form`, `schema-definition`,
    `referenced-schema-closure`, `referenced-schema-summary`, …); the full and compact AI and HTML views; and
    `require-specs` / `requires-html`. That is 586 lines in commit 2.
  - Also dropped: the old function pair's five unevaluated query comments.
- `agent.clj`: fleet table, −46 / +39.
- `transcript.clj`: −11 / +6 (`entry-name` deleted).
- Net source across the five commits: about **−560 lines**.
- `ns_test.clj`: 489 → 150 lines. It deletes the tests of the deleted dump and adds three class regressions.

## Commits

- `ff381f1ab` task and schema pairs, plus the issue file
- namespace node (−586)
- function node
- agent HTML node
- runtime and turn HTML

`git log --oneline -6` lists all five.

## Evidence

- **Debug page** `/agent/root/debug?n=2|3`: 200 in 0.17 s after, against 0.30 / 0.34 s before (curl). The namespace
  node no longer carries `seon-namespace-source` (1 → 0).
- **Screenshots** in `tmp/impl-render-pairs/`:
  - `before-n2-w1440.png`, `after-n2-w1440.png`, `after-n3-w1440.png`;
  - `after-task-data-w1440.png`: the task node on `/data`, with intent, proofs, subjects, must-read and evidence;
  - `after-schema-data-w1440.png`.

  Root's n=2 graph reaches no task, schema or function node, so the new pairs show on `/data`, not on the agent page.
- **REPL.** Every adopted face was called through its armed Var on real rows: tasks `132fee0467c8`; schemas
  `:seon.task/task` and `:seon.task/ns`; functions `seon.render.walk/graph` and `seon.db/q` (410 callers); namespaces
  `seon.render.walk` and `my.agents.root`. Their contracts accepted the outputs. All 3,456 stored schema forms read as
  EDN in 10.9 ms.

## Root runs

```
bin/seon init --dev default --changed src/seon/cluster/agent.clj src/seon/render/transcript.clj   (after the restart, if not loaded)
bin/test-check default --ns seon.render.ns-test
bin/test-check default --test seon.render.entity-pairs-test/function-entity-pair-is-selected-through-the-issue-walk
bin/test-check default --ns seon.cluster.agent-identity-test
bin/test-check default --ns seon.render.runtime-test
bin/test-check default --ns seon.render.page-review-test
bin/test-check default --ns seon.render.transcript-test
bin/test-check default --ns seon.render.walk-graph-test
bin/test-check default --test seon.render.web-debug-test/the-graph-debug-page-shows-both-faces-in-graph-order-at-every-n
```

## String-building pairs for the data-return sweep

These build text or hiccup layouts:

- `seon.render.ns`: all pairs;
- `seon.cluster.agent/render-identity-ai` / `-html`, `render-situation-ai`;
- `seon.render.transcript/render-run-*`, `render-runtime-*`, `render-history-html`;
- `seon.cluster.status/render-ai` / `-html`;
- `seon.render.test/render-ai`;
- `seon.error/render-ai` / `-html` and `index-refusal-prose`.
