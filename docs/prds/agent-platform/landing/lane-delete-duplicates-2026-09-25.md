---
type: landing
status: landed (e9e8176e9, b16c62792, slice 3 commit); tests owed to root
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

## Totals

| Slice | Commit | Deleted | Added | Net |
|---|---|---:|---:|---:|
| 1 changed-test family | e9e8176e9 | script/bin 698, tests 133, docs 5 | 18, 4, 13 | −680 src/script, −129 test |
| 2 schema-split CLI | b16c62792 | script 141 | 0 | −141 |
| 3 ns omission helpers | see git log | src 62 | 17 | −45 |

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
