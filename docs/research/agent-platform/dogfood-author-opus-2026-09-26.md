---
type: report
status: complete
created: 2026-09-26
scope: dogfood lane dogfood-author-opus — add a small feature (a notes index) end to end at the agent REPL, through MCP only
---
# Dogfood: authoring a small feature at the agent REPL

**Method.** I used only the seon MCP server: `eval_clj` in SCI mode on my own branch, plus `branch`,
`runtime_status` and `get_value`. I learned the API through `help`, `dir`, `doc`, `apropos` and queries. I
read source only after hitting a defect, to locate its producer: `src/seon/test.clj:2121-2185` for the
`my.test/check` failure. I also read `data/clusters/default/logs/seon.log` after the JVM died.

I used two branches. `dogfood-author-opus-1` ran on default pid 19859 (16:02Z). That JVM ran out of heap at
about 16:10Z, and root restarted default as pid 27723 on HEAD 6a8e46ec6 at 16:14Z. I then used
`dogfood-author-opus-2` on the new JVM. Both branches are retired.

**The feature.** Namespace `notes-index`:

- Schemas: `:notes-index.note/{id,text,tags,created-at}`, the entity `:notes-index/note` (with an AI render
  pair), `:notes-index/summary` and `:notes-index/tag`.
- Functions: `add-note!`, `tagged` (Datalog by tag; the database is supplied when omitted, so an `as-of`
  value works), `all-notes`, `summary`, `pull-note` and `render-note-ai`.
- Three `deftest`s in `notes-index-test`.
- Merge proposed as message `798a3bdcb21f` for issue `b880b23560af`. Not merged.

**Every step completed except "run the tests that reach my change".** `my.test/check` failed on every call
(finding 1). The install gate was the only test runner that worked.

## Ranked findings

| # | Severity | Finding | Form → output (trimmed) · time | Model I suspect is wrong | Issue |
|---|---|---|---|---|---|
| 1 | **blocks work** | Once any contract on the branch names a schema the agent registered, `my.test/check` fails for **every** test on the branch. That includes a control namespace whose only contract is `[:=> [:cat :int] :int]`. | `(my.test/check {:seon.test/changed '[notes-index/normalize-tags]})` → `:malli.core/invalid-schema`, nothing else. 1.6–3.2 s, once **17.3 s** (`with-declarations` ×373,203, `datoms` ×186,306). The run's member report holds the cause: `:data {:schema :notes-index/note}` at `seon.sci.eval/install-function-contract!` ← `fork-cluster-ctx` ← `seon.test/clean-request-handle`. | The check's fork builds its contracts against a projection without the branch's schemas. The install gate uses the right one, so there are two test-world constructions. The REPL also dropped `:data`. | [new](../../seon/issues/my-test-check-fails-every-test-once-a-branch-contract-names-an-agent-schema.md) |
| 2 | **blocks work** | A breaking redefinition installs **without its gate** when a `seon.schema/register!` came earlier in the same run. The same `defn` without the `register!` is refused by the gating test. A passing gate and a skipped gate both print `#'ns/name`. | `(seon.schema/register! :notes-index/tag :keyword) (in-ns 'notes-index) (defn tagged … request map …)` → `#'notes-index/tagged`, although `add-note-then-tagged-finds-it` (defined in an earlier run) calls `(tagged :probe)`. 952 ms. On pid 19859, a bad `normalize-tags` (lower-casing dropped) also installed silently while two tests gated it. | The registration delta routes the later `defn` through an admission path that does not gate. A gate that cannot run admits instead of refusing. | [new](../../seon/issues/a-schema-registration-earlier-in-a-run-lets-a-later-redefinition-install-ungated.md) |
| 3 | **blocks work** | The auto-check writes its generated calls of a writing function into my live branch. Install-gate test bodies do too. | After `(defn add-note! …)`, a query on `:notes-index.note/id` → 45 ids, 42 of them generated (`"yIEXMwM170LgT0"`, `"i"`, …). Reproduced on 6a8e46ec6 (22, then 23 more). The gating test's note `t1` appeared at tx `536871459`. I cleaned up by hand three times. | Generated calls and gate bodies run in the agent's custody instead of a fork or `d/with`. | [new](../../seon/issues/an-agents-auto-check-of-a-writing-function-commits-generated-entities-to-its-branch.md) |
| 4 | **blocks work** | A function using Java interop (`(java.util.Date.)`) evaluates in its run, then is refused on every later call as "host-bound … load its source files". The agent has no source files. On pid 19859 the row also raised a branch error in every run until I `ns-unmap!`ed it. | `(notes-index/now-inst)` → `install-row! refused … got nil nil. Fix: Cannot interpret notes-index/now-inst: Host-bound declaration … in a namespace this JVM has not loaded`. The row has `:seon.fn/host-bound? true`; its interop-free sibling has `false`. | Host interop is classified as needing compiled files, even in an agent-only namespace where SCI is the only evaluator. The refusal does not name the construct. | [new](../../seon/issues/an-agent-function-using-java-interop-is-host-bound-and-cannot-run-on-its-branch.md) |
| 5 | slows work | A contract predicate is refused both ways. Unquoted `inst?` fails because "contracts are data; use … a quoted symbol". Quoted `'inst?` fails `canonical-definition` with "got `[:=> [:cat] (quote inst?)]`". Only a registered schema key works. | `(defn now-inst {:malli/schema [:=> [:cat] inst?]} …)` and the same with `'inst?`: both refused. | Two admission stages read two different values: evaluated metadata and authored source. | [new](../../seon/issues/a-contract-predicate-is-refused-both-quoted-and-unquoted.md) |
| 6 | **blocks work** (environment) | Default ran out of heap at about 16:10Z during my third `my.test/check` call. Root's test namespaces (`seon.effect-test`, `seon.reset-edges-test`) were running at the same time, so I cannot attribute it. There was a 17.8 GB heap dump. | `seon.log`: `java.lang.OutOfMemoryError: Java heap space … Terminating`. | See the existing issue; my check (a 17 s gate over `gate-set-in` ×87) is one candidate. | evidence on [default-ran-out-of-heap…](../../seon/issues/default-ran-out-of-heap-while-a-timed-out-test-member-stayed-live.md) |
| 7 | slows work | `propose-merge!` has no selection. The proposal's change list included `my.plan-test/current-refuses-an-unknown-agent-by-name`, a test I never touched. Selecting pieces exists only in root's `prepare-merge!`. To keep `notes-probe` out I had to `remove-ns!` it first. | `(my.program/propose-merge! {:seon.issue/id "b880b23560af"})` → message `798a3bdcb21f` listing 21 identities, including the foreign test. 213 ms. | The proposal's change is "every declaration differing from the published program", not what the agent chose. The foreign test is a cluster/published drift that the branch inherits. | — (missing entrance 2) |
| 8 | confusing | Printing a schema definition whose properties hold `:seon.render/ai` renders the **properties map** through that renderer. The renderer refuses, and its refusal dumps the whole render context (connection, profile, caps) three times, about 10 KB. | `(mapv (juxt identity seon.schema/schema-definition) [… :my.note/note])` → `render-note-ai refused note at [:my.note/id] … got a map missing :my.note/id {:seon.agent/id … :seon.sci.eval/ctx …}` | The same key means "render me with X" and "my values render with X". | evidence on [schema-rows-render-through-the-pair-they-declare](../../seon/issues/schema-rows-render-through-the-pair-they-declare.md) |
| 9 | confusing | Every MCP run starts in `user`, although root's note says "runs start where the last ended". `(str *ns*)` at the start of a run → `"user"` right after a run ended in `notes-index`. I needed `in-ns` in every run. | `(str *ns*)` → `"user"` | The MCP `namespace` argument defaults to `user` and resets `*ns*` each call. | — (smaller) |
| 10 | confusing | Printed shape ≠ value. `(dir seon.schema)` prints `{:seon.repl/columns … :seon.repl/rows …}`, but the value's keys are `(:seon.render/ai :seon.render/html :schemas :functions)`, so `(:seon.repl/rows (dir …))` → `[]`. A db prints `{:db-name … :t …}` but `(:t (seon.db/db))` → `nil`. | as stated | The render hides the value's real shape and nothing says so. | — (smaller) |
| 11 | confusing | `unresolved-reference` names no key. `register!` of `:notes-index/summary` in the same run as its two attributes (and a note re-registration) → "Schema population refused :notes-index/summary (unresolved-reference)". The same form alone in the next run worked. It did not reproduce on 6a8e46ec6, where eight registrations in one run worked. | as stated | — | — (smaller; not reproduced) |
| 12 | ugly | Expected refusals print `datahike.writer` stack traces (about 3 KB each, three per `defn add-note!`) into the reply as `out`. | `defn add-note!` → three `:datahike/write-error … Transaction report validation rejected` traces | known class | evidence on [expected-refusal-logs-raw-datom-error-twice](../../seon/issues/expected-refusal-logs-raw-datom-error-twice.md) |
| 13 | ugly | `dir seon.schema` is about 25 KB. It lists plumbing (`*candidate-visit!*`, `begin-registration-delta`, `restore!`, …). Several rows show the error "The program row lacks a declaration … :seon.fn/spec" in place of `:in`/`:out`. | `(dir seon.schema)` | known (cycle 8/10 `dir` plumbing) | — |
| 14 | ugly | `ns-unmap!`/`remove-ns!` answer five fixed `:seon.program/unknown` gap paragraphs every time (about 1.3 KB). | `(my.program/ns-unmap! 'notes-index/by-tag)` | — | — (smaller) |
| 15 | confusing | `branch list` during boot answered a raw `NullPointerException` trace (`"fut" is null`) instead of "store not ready". | `branch list` at 16:13:40Z | Raw host exception at the MCP edge. | — (smaller) |
| 16 | confusing | A vector of notes prints as raw maps. Only a single note uses my `render-note-ai`. A collection needs its own vector schema and renderer (as `:my.note/notes` has). A schema's new render pair takes effect only from the next run. | `(first (tagged …))` → `Note n1 [:datahike :history]: Datahike owns history`; `(tagged …)` → raw maps | Element renderers do not compose into collections. | — (smaller) |
| 17 | confusing | Validation at the REPL needs plumbing. `malli.core/validate` is unresolvable (known). What works is `(seon.schema/valid-candidate-value? (seon.schema/declaration-projection) :notes-index/note v)`, and its explain prints `#object[malli.core/-map-schema…]`. | as stated | known (cycle 4) | — |
| 18 | confusing | Datahike returns a cardinality-many attribute as a vector, so a wildcard pull of my entity does not satisfy the entity schema I declared (`[:set :keyword]`). I needed a `pull-note` helper to convert it. | `(seon.db/pull '[*] [:notes-index.note/id "n1"])` → `:notes-index.note/tags [:datahike :history]` | known | evidence on [wildcard-pulled-collections-do-not-satisfy-entity-set-contracts](../../seon/issues/wildcard-pulled-collections-do-not-satisfy-entity-set-contracts.md) |
| 19 | confusing | "Supply defaults by declared schema name" works for a **request map** (`[:map [:seon.db/db …]]` with the key omitted is supplied). It does not work for a positional `:seon.db/database-value` argument: `(tagged :probe)` against `[:cat :seon.db/database-value :keyword]` → "refused argument count". | as stated | The law says "as for every function". Positional parameters are not covered. | — (smaller) |

## Missing entrances (each tried and failed)

1. **Run the tests that reach my change.** `my.test/check` fails for the whole branch (finding 1).
   `my.test/run`, `clojure.test/run-tests` and a direct call are known not to report (cycle-10 #23). The only
   working runner is a throwaway redefinition that triggers the install gate, and a pass shows nothing.
2. **Propose only the pieces worth keeping.** `propose-merge!` takes only `:seon.issue/id`. Its `doc` shows no
   `:seon.program/identities` key (`prepare-merge!` has one). I did not call it with that key. The workaround
   is deleting what you don't want merged before proposing.
3. **Preview my change as data before proposing.** `(my.program/overrides {})` → `[]` on a branch with 21
   changed identities. The first place I saw the change list was the delivered message's content string.
4. **A clock for agent code.** `(java.util.Date.)` makes a function host-bound (finding 4). No `my.*`/`seon.*`
   function matched `apropos "now"` or `"clock"`. The transaction instant is the only time source, and it is
   reachable only after a write.
5. **Validate a value against a named schema without plumbing** (known). See finding 17.
6. **Rename a function.** Known missing. It took three runs: new `defn`, a new test calling it, then
   `ns-unmap!` of the old test and the old function. `breaks` and `ns-unmap!` made the order safe.
7. **An auto-check that does not write.** No option on `defn` or the entry gate, found by `doc` or `apropos`,
   runs the auto-check against a fork (finding 3).

## What worked well

- **Schema declaration** by `seon.schema/register!` as top-level forms: attributes, `[:set …]` for
  cardinality-many, `:seon.db/identity`, the entity map with `:seon.db/attributes true` and
  `:seon.program/partition :seon.data`, learned from `(seon.schema/schema-definition :my.note/id)`. Eight
  registrations in one run took 3.7 s (first run on the branch).
- **Writes and reads**: `seon.db/transact!` with a supplied connection answered the datoms. Queries needed no
  `$` argument. `retractEntity` cleaned up 42 junk notes in one transaction (199 ms).
- **History**: `(seon.db/as-of 536871453)` and `(seon.db/history)` answered exactly as in Datahike. The text
  of note `n2` before and after the update, and the full assert/retract log, took 147 ms. My own `tagged`,
  taking `:seon.db/db` in its request, answered the pre-update text given an `as-of` value.
- **The install gate**, when it runs, is precise:
  `Gate: 2 tests · 1 passed · 1 failed … expected: (= #{:a :b} …) actual: (not (= #{:a :b} #{:A :a :b}))`.
  It kept the old definition (269 ms). It also caught a caller-arity break from a changed signature, naming
  the test and the refused argument.
- **Auto-check** refused `pull-note` with a too-loose `[:cat :map]` input and gave a precise reason
  (`arguments: [{}] … missing required key`). That forced an honest contract.
- **`breaks`** named both the caller and the test before I deleted `normalize-tags`, and planned the repair
  issues. `ns-unmap!` / `remove-ns!` removed the rows and Vars (about 1 s each).
- **Render**: a declared `:seon.render/ai` on the entity schema printed a single note as
  `Note n1 [:datahike :history]: Datahike owns history`.
- **`my.issue/add!`** refused one missing key at a time with an exact fix (`:seon.issue/severity`).
  `propose-merge!` delivered in 213 ms, although root's graph had panicked at boot.
- Branch `dogfood-author-opus-1` **survived the JVM's OOM restart** and kept its rows and data. Root's note
  said old branches died; mine answered `ns-publics` on the new JVM.

## Turn times (branch `dogfood-author-opus-2`, open→close from its own turn rows)

| Step | ms |
|---|---|
| eight `register!` (first run on the branch) | **3,681** |
| `ns` + seven `defn`s | **2,788** |
| `ns-unmap!` + `defn add-note!` (auto-check writes 22 notes) | **2,403** |
| `defn add-note!` redefinition | **1,651** |
| four `deftest`s | **1,092** |
| `my.test/check`, 1 changed (failed) | **2,474**; control namespace **1,744** |
| two `ns-unmap!` (+ `ns-publics`, query) | 1,032 / 1,058 |
| `register!` + breaking `defn` (ungated) | 952 |
| `remove-ns!` + retract + `overrides` | 896 |
| gated refusal of a bad `defn` | 256–297 |
| reads, `breaks`, `as-of`/`history` queries | 88–213 |
| `propose-merge!` | 213 |

On pid 19859 the same `my.test/check` took 2.4 s and then 17.3 s. Each result over one second is named with
its top armed functions in the reply's profile line (for example, `seon.db/transact!` ×3 at 1.8 s inside
`my.test/check`).

## Proposed merge (message `798a3bdcb21f`, issue `b880b23560af`; branch retired, per the preamble)

Worth keeping: the `notes-index.*` schemas, `add-note!`, `tagged`, `all-notes`, `summary`, `pull-note`,
`render-note-ai` and the three `notes-index-test` tests. Not worth keeping: the foreign
`my.plan-test/current-refuses-an-unknown-agent-by-name` that the proposal swept in. `notes-probe` was removed
before proposing. The proposal names a retired branch. If root wants to merge it, the forms above are in this
report's companion issue files and in the MCP transcript.
