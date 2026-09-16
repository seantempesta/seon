---
type: research
status: active
tags: [research, schema, program-graph, issue-generator]
---

# Source root as a fact — 2026-09-16

The walked source root is now a fact on the file entity the indexer mints, so
"which source root did this declaration come from" is a join and never a rule
about the path text. Implementation commit **`925ca19fe`**; adopted on
`default` at `:current-src` commit **`6aaa4925-cb32-5a6e-b004-105078c43e7a`**.

Read end to end: AGENTS.md, `tmp/orchestrator/wave2/repl-rule.txt` (including
the BASE CONSTRUCTION and LIVE-PROOF VALIDATION rules), the issue note
[the program graph cannot say which source root a declaration came from](../../../seon/issues/the-program-graph-cannot-say-which-source-root-a-declaration-came-from.md),
[program-provenance-2026-09-16](program-provenance-2026-09-16.md), and
[issue-generator-2026-09-16](issue-generator-2026-09-16.md).

## Broken things first

- **The cached canonical fixture base on `default` predates the attribute**, so
  the three fixture-backed regressions cannot be proven in that JVM. Measured,
  not assumed: `seon.db/q` over the base answers the typed
  `seon.db/attribute-not-installed` refusal, and
  `seon.fn-test/indexed-declarations-carry-exact-file-bytes` ran **9 pass, 1
  fail** (run 70555) with
  `seon.db/transact! refused transaction data at [4 :seon.fn.file/root]:
  expected an installed attribute`. The base was neither forced nor rebuilt.
  Proof that this is the base's schema and not the change: the emitted rows,
  checked through `(#'seon.db/write-error database projection rows)` against
  `default`'s own database (whose schema HAS the attribute), are **admitted**
  — `:admitted? true`, no refusal. The cold gate builds its base at HEAD, so it
  owns the verdict for these three.
- `seon.program/shapes` silently stripped the new owned attribute until its
  literal list was extended — the **second** time in one day (first:
  `7cfe02790`). Recorded on
  [the mirror issue](../../../seon/issues/program-shapes-mirror-the-schema-row-maps-by-hand.md).
- The first adoption attempt exited 1 with
  `:seon.cluster/source-changed-during-adoption` under concurrent lane edits;
  the immediate retry converged. The coordinator's warned
  `:seon.test/adoption-identities #{nil}` refusal was **not** encountered.

## The change

| Path | What it does |
|---|---|
| `src/seon/program.cljc` | one element: `:seon.fn.file/root` joins the `:seon.fn.file/path` shape's owned attributes, so it is minted AND replaced with the row. Nothing else in that file. |
| `resources/seon/schemas/seon.fn.file.edn` | declares `:seon.fn.file/root` and adds it OPTIONAL to `:seon.fn.file/file`. |
| `src/seon/fn.clj` | `rooted-source-files` walks each root and pairs it with the files it finds; `artifact` writes the root on the file row; `build-artifact` (the changed-path seam) resolves it by canonical DIRECTORY ANCESTRY (`under-root?`) over the declared roots and omits it for a file under none. |
| `src/seon/issue/detect.clj` | `public-without-doc` keeps its one-argument over-report; the two-argument arity scopes on `{:seon.fn.file/root "src"}` by joining positively on the fact. |

The root is stored **exactly as supplied** to the index request: `"src"` /
`"test"` for `seon.fn/source-roots`, and the literal root string a caller
passes (`fn_test` passes temp directories). No canonicalization, no
repository-relative rewriting, no parsing of the file path.

**Absence is meaningful and must never be read as `src`.** The changed-path
seam indexes any Clojure file it is handed — `default` holds exactly one such
file entity today — so a file under no declared root carries no root. Every
consumer joins on the value. `seon.fn/source-roots` is `["src" "test"]`; the
indexer has no `script/` handling (`script` appears only in
`seon.cluster/source-roots`, which widens the source snapshot, not the walk).

## Live evidence on `default` (pid 45917, MCP `jvm`, explicit connection)

Cluster `:seon.source/commit-id` equals the published commit
`6aaa4925-cb32-5a6e-b004-105078c43e7a` — adoption converged, no RESET NEEDED
(the change is additive).

| Measurement | Value |
|---|---|
| `seon.fn.file` entities | 335 |
| `:seon.fn.file/root "src"` | 106 |
| `:seon.fn.file/root "test"` | 228 |
| no root | 1 — `docs/prds/steward-platform/research/reach-closure-live-proof-2026-09-16.clj`, a lane probe minted by the changed-path seam |
| declarations under `test` (`:seon.fn/file` → root join) | 918 |

The join, one declaration each side:

```clojure
[?f :seon.fn/sym ?sym] [?f :seon.fn/file ?file] [?file :seon.fn.file/root ?root]
;; "seon.plan/settle-call"            => "src"  (src/seon/plan.clj)
;; "seon.test-support/with-database"  => "test" (test/seon/test_support.clj)
```

The docstring standard, live, before and after scoping:

| `seon.issue.detect/public-without-doc` | Subjects |
|---|---|
| unscoped (unchanged default) | 31 |
| `{:seon.fn.file/root "src"}` | 2 — `seon.flow/->CountedDroppingBuffer`, `seon.flow/->RefusingBuffer` |
| `{:seon.fn.file/root "test"}` | 28 |
| neither (agent-admitted, no file) | 1 |

That confirms the issue's estimate from the other side: 28 of the 31 subjects
are test helpers, and the production finding a steward would act on is two
rows wide.

## In-process proof and its boundary

Every run is `(seon.test/run (#'seon.test/resolve-test 'ns/test)
(seon.operator/connection "default"))` on a daemon thread in `default`'s JVM.
No test JVM, no `bin/test`, no `bin/test-fast`, and the shared fixture delay was
never forced by this lane.

| Regression | Result |
|---|---|
| `seon.fn-test/the-walked-root-travels-with-its-file-and-is-stored-verbatim` | **3 / 0 / 0**, run 70554 |
| `seon.fn-test/every-indexed-file-carries-the-root-the-indexer-walked` | not provable here — stale base (above) |
| `seon.fn-test/a-declaration-answers-its-source-root-through-its-file` | not provable here — stale base |
| `seon.issue-generate-test/the-docstring-standard-scopes-on-the-source-root-fact` | not provable here — stale base |
| `seon.fn-test/indexed-declarations-carry-exact-file-bytes` (existing) | 9 / 1 / 0, run 70555 — the stale-base write refusal, shown above to be admitted at HEAD's schema |

Regression (1) is scoped to the canonical fixture population on purpose: it
asserts every file entity there carries a root and that the set of roots equals
`seon.fn/source-roots`. Making it a claim about EVERY database would require
either refusing out-of-root files at the changed-path seam or fabricating a
root, and would turn the honest absence into a lie.

## Batch 46 B — one function for both seams (`0eba4b8c3`)

`seon.fn-test/file-artifacts-and-manifests-are-byte-digested-and-deterministic`
went red at `fn_test.clj:1029` on snapshot `2b996b1b5`: the fixture built the
manifest with a temp root and the incremental artifact with NO `:seon.fn/roots`,
which falls back to `seon.fn/source-roots` — under which the temp file lies
beneath no root. The walk wrote a root, the single-file seam wrote none, and
the two artifacts differed. My defect: two ways to answer one question.

`rooted-source-files` is deleted. `build-manifest` and `build-artifact` now both
call **`containing-root` with the roots of their own request**, so the same file
under the same roots carries the same root fact by construction, and the
regression asserts that equality — and the concrete root value — explicitly
rather than inferring it from whole-artifact equality.

**Not adopted and not run in-process.** A concurrent lane is mid-flight
dissolving `seon.program/shapes` into shapes derived from the declared entity
maps (uncommitted `src/seon/program.cljc` plus the `seon.ns`/`seon.fn`/
`seon.test`/`seon.schema`/`seon.lint`/`seon.program` schema EDNs — the issue
this lane recorded twice today). `default`'s loaded program now throws
`A program identity attribute declares no row schema`, which refuses
`bin/seon init --dev default` (twice, ten minutes apart) and every in-process
`seon.test/run` with `Test provenance unavailable: …`. That lane's edits were
preserved and nothing of theirs was touched. clj-kondo over both changed files
reports 0 errors. That dissolution also SUBSUMES this lane's
`src/seon/program.cljc` hunk: `:seon.fn.file/root`, declared on
`:seon.fn.file/file`, is owned by derivation, and their note cites `925ca19fe`
as the second silent strip.

## Verification boundary

Adopted and measured on `default` only; `default` was never stopped, reforked,
or reset, and its fixture base was neither forced nor rebuilt. Foreign
concurrent edits (`src/seon/cluster.clj`, `src/seon/test/runner.clj`,
`test/seon/adoption_rows_test.clj`, skills) were preserved; the commit is
path-limited to the six files above, and `src/seon/program.cljc` carries one
hunk of two lines, far from the `retractAttribute` region. The batched
isolated gate is the orchestrator's proof, not a claim made here:
`tmp/orchestrator/gate-requests/source-root-fact.txt`.
