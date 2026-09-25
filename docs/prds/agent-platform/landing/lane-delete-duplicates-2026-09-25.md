---
type: landing
status: landed (e9e8176e9, b16c62792, 67dc5acb8, reds follow-up); tests owed to root
lane: delete-duplicates-opus
created: 2026-09-25
---

# Lane delete-duplicates (2026-09-25)

Seon already owns analysis (`seon.fn.analyzer`), test selection and runs (`seon.test/select`, `run`),
schema resource checks (`seon.schema.edn`) and the namespace renderer (`seon.render.ns`). This lane
deletes duplicate paths beside those owners, one slice at a time, converting every caller in the same
commit. Sources: [duplicate-paths audit §11](../../../research/agent-platform/duplicate-paths-audit-2026-09-25.md)
and [script/dev roots audit](../../../research/agent-platform/script-dev-roots-audit-2026-09-25.md).

## Broken / not done

- Tests were not run (LOAD RULE). Root runs the commands at the end of this note.
- Hook fixtures in `test/seon/dev/edit_feedback_test.clj` still write `:changed-tests {:enabled false}`
  into hook configuration; no reader of that key exists. Left in place (harmless residue, not this slice's
  machinery).

## Slice 1: the legacy changed-test/cache-helper family (script audit rank 1; §11 R9 inside it)

- Survivor: `seon.fn.analyzer/analyze-dependencies!` (dependency clj-kondo cache in the loaded JVM),
  `seon.test/select` / `run` via `bin/test-check` (test selection and runs), `seon.cluster.process`
  (subprocess ownership, whose tests stay).
- Deleted files: `script/seon/dev/changed_test.clj` 380, `script/seon/dev/test_roots.clj` 118,
  `script/seon/dev/clj_kondo.clj` 96, `script/seon/dev/state.clj` 69 = **663**.
- Callers converted:
  - `bin/seon-hook` required `seon.dev.clj-kondo` only for the string `clj-kondo-output-config`; the
    constant moves beside the hook's existing `clj-kondo-definition-output-config`.
  - `test/seon/dev/dependency_cache_test.clj` wrote its process-reference fixture with
    `seon.dev.state/write-edn!`; it now writes the same bytes (`pr-str` + newline) the operator's
    `record-cache-reference!` writes.
  - Comment/docstring references in `script/seon/operator.clj` (nuke path list) and
    `script/seon/dev/dependency_digest.clj`.
- Tests of deleted machinery deleted: `test/seon/dev/clj_kondo_test.clj` (40, whole file);
  `changed-paths-are-handed-to-the-one-gate-selector` and `concurrent-tooling-transition-refuses-busy`
  in `test/seon/dev/changed_test_test.clj`; `changed-test-analysis-keeps-the-valid-namespace-graph` in
  `test/seon/dev/edit_feedback_test.clj`. The remaining subprocess-ownership tests of
  `seon.cluster.process` stay; their file moves to `test/seon/cluster/subprocess_test.clj` so the
  namespace no longer names a deleted namespace.

- Also deleted: `seon.dev.dependency-digest/dependency-set-digest` (17 lines), whose only caller was
  `clj_kondo.clj`; `configuration-digest` keeps the declared-set inputs.
- Stale docs corrected in the same commit: `docs/seon/reference/linting-setup.md` (named
  `run-changed!` as current); issue `source-publication-cache-contention-hides-dependency-analysis-failure`
  resolved by deletion (its producer is gone).
- Equivalence evidence (Babashka, repository `bb.edn`, before deletion): `state/write-edn!` and the
  replacement `spit (str (pr-str record) "\n")` wrote byte-identical files and read back `=` records for a
  process-reference record; the hook's moved constant is the same string,
  `"{:output {:format :edn} :analysis {:var-usages true}}"`. After the edit, `load-file "bin/seon-hook"`
  resolves `user/clj-kondo-output-config` to that string, `seon.operator` loads under BB, and
  `configuration-digest` returns a 64-character digest.
- Lint: clj-kondo 0 errors on every touched file (warnings pre-existing).
- Sizes (numstat): script/bin +18/−698 (net −680); tests +4/−133 (net −129); docs +13/−5.

## Slice 2: the completed schema-split migration CLI (script audit rank 3)

- Survivor: `seon.schema.edn/validate-resource-placement!` and the schema resource loader.
- Deleted: `script/seon/schema_split.clj` **141** (whole file). Callers: none executable at HEAD
  (`rg` over src, script, test, bin, hidden config; only its own `-main`).

- Equivalence evidence: at HEAD the splitter can no longer read the current resources. BB
  `(#'seon.schema-split/merge-files (#'seon.schema-split/split-files "resources/seon/schemas"))` throws
  "Schema key namespace is not safe as a verbatim filename: :inst" (it predates the unqualified-key
  resource). The survivor on default (JVM, read-only) reads the same directory:
  `(seon.schema.edn/packaged-forms)` → 3,461 keys, 1 unqualified, 1.6 ms. The CLI was dead and broken;
  nothing replaces it.
- Sizes: script −141, no additions.

## Slice 3: namespace unreachable omission helpers (§11 R8)

- Survivor: the floor printer, the one clipper (owner 2026-09-25). `seon.render.ns` renderers already
  include every item: `ai-text` passes `(count items)` and `render-html` passes
  `(count (::functions data))`, so `omitted` is always 0.
- Deleted: `omission-value` 11, `omission-text` 10, the `included-count` parameter/`subvec`/`omitted`
  bindings and the three `(pos? omitted)` branches in `compact-ai-text`, `full-html-view`,
  `compact-html-view`, `html-view`.
- Also deleted: the `::profile-id` entry of `render-data`, whose only reader was `omission-value`.
- Equivalence evidence (default JVM, one captured `seon.db/db`): the edited file was loaded with
  `load-string` into scratch namespace `delete-duplicates.scratch-render-ns` (30.6 ms; no default Var
  redefined; `remove-ns` afterwards). For every one of the 459 `:seon.ns/name` entities at distances
  0, 1 and 2, `seon.render.ns/render-ai` and `render-html` (HEAD, still carrying `omission-text`) equal
  the scratch copies: **1,377/1,377 AI strings and 1,377/1,377 hiccup vectors equal, 0 mismatches**, and
  no HEAD output contained an elision. Whole probe 8.7 s for 5,508 renders (≈1.6 ms each); the profile
  line naming `seon.effect/capabilities` 11.3 s ×3 was concurrent work, the cost fix-cost-capabilities
  owns. No test asserts the omission text or class.
- Lint: clj-kondo 0 errors, 0 warnings. Sizes: src +17/−62 (net −45).

## Reds after adoption at 6ab6cd5e (root run, `tmp/orchestrator/verify-1955.out.d/`)

Broken first: `seon.cluster.subprocess-test` stays **red in 3 members** until `src/seon/cluster/process.clj`
(not this lane's file) is fixed; the tests assert the wanted behaviour.

`seon.render.ns-test` (008: 32 pass / 52 fail / 6 error). None of the reds reached a line slice 3 changed.
Proof: the pre-slice file (`git show 67dc5acb8^:src/seon/render/ns.clj`, loaded into a scratch namespace)
throws the identical `contains? not supported on type: java.lang.String` on `seon.flow` distance 1. The
causes, per member:

| Member | Cause | Ruling |
|---|---|---|
| all fixture members | `namespace-unit` pulled `{:seon.ns/requires [:seon.ns/name]}`; `:seon.ns/requires` is now `:db.type/symbol` many, so the pull returned a read error and every render saw an empty world (`(dir nil)`, `unknown.namespace`) | retired assumption: selector is `:seon.ns/requires` |
| `install-namespace!` / `function-row` users | hand-built rows lack `:seon.program/definition-digest` and analysis evidence (`:seon.program/analyzed-source-digest`) | retired assumption: canonical writers (`program/declaration-row`, `support/revised-row`, `support/program-row`) through `support/transacted!` |
| `identity-reads-stewardship…`, `source-less-agent-namespace…` | raw agent rows lack `:seon.agent/branch` | retired assumption: `support/agent-tx` (the first agent stewards) |
| `fitted-full-html…` | `:seon.ns/requires` written as lookup refs; required namespace rows hand-built | retired assumption: `support/revision` with a symbol set |
| `distance-one-never-drops-a-member-when-source-is-absent` | `:seon.fn/source` is a required key, so a sourceless function cannot be written | test of retired machinery: deleted, with the survivor's sourceless fallback (`function-source`, `signature-form`, `stored-arglists`, 30 lines) |
| `schema-closure…` missing-schema / malformed / cap-honesty members | a spec naming an undeclared key, and recursive canonical registrations, are refused by the projection owner | retired cases: deleted; the `a ↔ b` cycle became an acyclic chain; test renamed `schema-closure-is-database-derived-and-budgeted` |
| `schema-closure…` error schema | the named-error form `[:and :seon.error/base [:map {:error/message …} …]]` never showed its message: `schema-error-message` read only the top form's properties | real loss in the survivor: fixed for forms conjoining `:seon.error/base` |
| `populated-namespace…`, every distance-1 namespace with referenced schemas | `read-refusal?` called `contains?` on a string section | real loss in the survivor: fixed |
| `bounded-read-refusals…` | `read-refusal?` keyed on retired `:seon.db/read-operation`, so the `q` refusal passed as rows and HTML reached `surface-id` with nil | real loss: keyed on the declared member `:seon.db/invalid-read`; the test now asserts the declared refusal and compares the faces without `:seon.error/at` (occurrence time) |

REPL evidence (default JVM, edited file loaded into scratch namespace `delete-duplicates.scratch-render-ns`,
removed after): all `populated-namespace…` assertions true on `seon.flow` d0/d1/d2 (AI and HTML); the bounded
unit returns the same `seon.db/q` refusal from both faces, `:seon.db/invalid-read true`, layer
`:seon.db/database-read`. Sweep of 459 namespaces × 3 distances × 2 faces: 0 throws; 2 of 1,374 cases differ
from HEAD, both `seon.db` named error schemas now showing their message (5.5 s for the sweep). Fixture writes
were proven on disposable MCP branches `delete-duplicates-fixtures{,-2..-5}` (all retired): closure, cap,
budget and presentation worlds and three `agent-tx` agents land and the branch stays readable; closure d2
renders every expected member.

`seon.cluster.subprocess-test` (009: 7 pass / 4 fail). The move is not the cause; the owner changed:

- `reaped?` (two members): `5b5227bc1` made `terminate-subprocess!` return survivors but `run-process!` still
  stores that vector as `:reaped?`. Tests unchanged (they assert the wanted `true`). Issue
  `docs/seon/issues/run-process-reaped-flag-carries-the-survivor-vector.md`.
- duration (one member): the test's own 5,000 ms deadline plus the owner's 1,000 ms grace cannot fit the 5 s
  test bound; the deadline is now 2,000 ms (the child is ready in about 100 ms).
- `deadline-member` (one member): with armed contracts the input contract refuses before the body, so the
  body's `:deadline-member` is unreachable; the test now asserts the armed refusal (`:seon.fn.arity/input`,
  naming `:seon.operator.subprocess/deadline-ms`). It stays red: the contract's unquoted
  `[:fn seon.cluster.process/progress-atom?]` makes the refusal unbuildable (REPL: "the refusal could not be
  built"). Recorded in `docs/seon/issues/a-search-contract-predicate-cannot-be-made-durable.md`.
- `test/seon/dev/publication_test.clj:34` has the same `reaped?` assertion (not this lane's file).

Out of scope, recorded: `docs/seon/issues/writes-admit-schema-rows-that-break-the-branch-projection.md` (three
probe writes landed and then every read on the branch refused), and the `pull` budget refusal without
`:seon.db/invalid-read` (added to `read-refusal-checks-test-error-at-not-invalid-read.md`).

Sizes of the follow-up: src +16/−34; tests +58/−111.

Round 3 (granted `src/seon/cluster/process.clj`): `run-process!` sets `:reaped? (empty? survivors)`, and all seven
unquoted `[:fn seon.cluster.process/...]` contract predicates (two in `run-process!`/`terminate-subprocess!`,
five `process-handle?` slots) are quoted symbols, the form `seon.schema/canonical-definition` accepts. Proof as
recorded in the two issue notes; `test/seon/dev/publication_test.clj:34`'s `(true? reaped?)` is correct again
without change. Sizes: src +8/−8.

## Round 4 red: `bounded-read-refusals-remain-flat-errors` (root run d19b257f5c49)

`render-ai`/`render-html` returned the `seon.db/q` refusal correctly, but their armed output contracts named
`:seon.db.read/error`, which that refusal does not satisfy (REPL: the real bounded refusal validates as
`:seon.db/invalid-read-error` true, `:seon.db.read/error` false). The error stays a value to the edge, so the
contracts now declare the alternative the producer actually returns, `:seon.db/invalid-read-error`; both admit the
refusal and the source/hiccup value and still reject an arbitrary map. The render pipeline renders it through
the error schema's declared pair.

## Red on adopted 6ab6f5fb: `bounded-read-refusals-remain-flat-errors` (CAUSED, lane's own assertion)

`tmp/orchestrator/verify-reds.out.d/016.txt`: the two faces' refusals differ in `:seon.error/chain` as well as
`:seon.error/at`; the chain's frames name `render_ai` versus `render_html`. REPL on default: exactly those two
keys differ; operation, member, message, data, layer and exception class are equal. Both are occurrence
evidence, so the assertion now compares the refusal without them.

## Round 4, R5 (shown-text reparsing): browser half landed, grader half blocked

- `src/seon/repl.clj` (−10/+2): `pretty-response` deleted. The browser's evaluation entry now shows the exact
  saved shown text (or the live value's prepared text) instead of re-reading it as EDN and pretty-printing it a
  second time. REPL, 69 saved `:seon.eval/shown` values on default: the reparse changed 58 of them (re-laid-out
  and rewritten into namespaced-map syntax), so the page showed text the agent never saw. No test asserted the
  pretty form.
- `src/seon/eval/drive.clj` `read-result` (13) NOT deleted: the ruling's premise does not hold on the stored
  facts. A completed turn stores `:seon.turn/disposition :completed` but no result text: the `:my.turn/result`
  exists only inside `:seon.turn/reply` (source) and `:seon.eval/shown`, and `seon.cluster.message/reply` makes
  no message when the requester is outside the agent population, which is exactly an eval objective. The
  inspect-ai provider grades `:seon.eval.drive/completed-result` (`src-inspect-ai/src/seon_inspect/provider.py:81`).
  Options are in the final report.

## Round 4, C6: test-only projection serializer (root ruling option 1)

- Survivor: `seon.schema/build-projection`, the one constructor `projection-from-rows` calls after parsing rows.
- Deleted from `src/seon/schema.clj` (−97/+5): `projection-pure-data`, `materialize-projection`, and what only
  they used: `projection-runtime-keys` and the whole-build twin `shape-projections` (the build derives its shape
  indexes inline; `shape-projections-with` is the incremental path). Two docstrings and one comment that cited
  them are corrected.
- Tests: `schema_test` loses the pure-data assertion and `pure-projection-data-materializes-compiled-entity-indexes`
  (tests of the deleted serializer); `schema/datahike_test` builds the historical population with
  `build-projection`.
- Baseline `test/seon/schema/datahike_parity.edn`: `build-projection` refuses 129 of the 3,277 historical forms
  (44 identity-bearing entity schemas without a partition, the rest their referrers). REPL loop over refusals:
  dropping them builds 3,148 forms to 836 attributes, and those 836 attributes and their native rows EQUAL the
  original 1,191-entry baseline filtered to them, so the oracle stays independent (filtered, not regenerated).
  Only `:initial-baseline` was spliced; every other key reads back equal to HEAD. The build takes 213 ms.

## Totals

| Slice | Commit | Deleted | Added | Net |
|---|---|---:|---:|---:|
| 1 changed-test family | e9e8176e9 | script/bin 698, tests 133, docs 5 | 18, 4, 13 | −680 src/script, −129 test |
| 2 schema-split CLI | b16c62792 | script 141 | 0 | −141 |
| 3 ns omission helpers | 67dc5acb8 | src 62 | 17 | −45 |

Source/script net **−866**; tests net **−129**.

## Root runs

Serially, in a quiet window (none were run by this lane):

```sh
bin/test-check default --ns seon.cluster.subprocess-test
bin/test-check default --ns seon.dev.edit-feedback-test
bin/test-check default --ns seon.dev.dependency-cache-test
bin/test-check default --ns seon.render.ns-test
bin/test-check default --ns seon.render.entity-pairs-test
bin/test-check default --ns seon.html-views-test
```

Adoption: `src/seon/render/ns.clj` is the only JVM program file changed; the script/bin/test deletions
retire namespaces that were never program rows (`script/` is outside `seon.fn/source-roots`), except
`test/seon/dev/clj_kondo_test.clj` and the renamed `seon.dev.changed-test-test`, whose test rows retire at
the next publication. No RESET NEEDED.
