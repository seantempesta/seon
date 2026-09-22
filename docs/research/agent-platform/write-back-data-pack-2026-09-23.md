---
type: reference
status: read-only research; citations verified at HEAD b02dcde7a on refactor/agent-platform
created: 2026-09-23
tags: [agent-platform, d1, write-back, form-span, seon-edit, publication]
---

# Write-back — data pack

Facts only. Every `file:line` was opened at HEAD `b02dcde7a`. Five lanes are
editing the tree; `src/seon/program.cljc`, `src/seon/cluster.clj`,
`resources/seon/schemas/seon.program.edn` and `bin/seon-hook` are DIRTY and
their lines move under a reader — those citations are marked **(wt)** and were
read at the timestamp of this note, not at HEAD. Live probes ran read-only on
`default` (pid 51528, root `/Users/sean/src/seon`, custody
`(seon.cluster.boot/connection "default")`, branch `cluster-default`, commit
`6ab2d787-fa35-53b5-a520-f06012ad8b75`).

## Summary (ten lines)

1. The splice unit is proven, not assumed: for `seon.edit/valid-form-operation?`
   the file's bytes `[21882 22793)` are **byte-identical** to `:seon.fn/source`,
   and the file's on-disk SHA-256 equals both `:seon.fn.file/digest` and the
   row's `:seon.program/analyzed-source-digest`. One probe closes §1 and §2.
2. So the file-base check is free: one file read, one digest, compared against
   the file row's own `:seon.fn.file/digest`. No new fact is needed.
3. `:seon.program/definition-digest` is now POPULATED on `default` — 10,201 rows
   (the merge pack observed `nil` pre-reset). The three-way input is live.
4. `seon.program/three-way` **exists in the working tree** (uncommitted) and
   already returns take/keep/conflict/added/retracted plus conflict digests,
   over `{identity → digest}` maps produced by `digest-map`.
5. Coverage is NOT uniform across identity kinds. Functions 4,435/4,436 spanned;
   tests 2,070/2,071 spanned; namespaces 408 filed but **0 spanned**; schema
   keys **3,285 with no file and no span at all**. Span write-back covers
   functions and tests only.
6. `seon.edit` is the splice owner and needs no change: pure, lossless, speaks
   the same half-open UTF-8 byte unit, and already offers `:insert-before` /
   `:insert-after` against a NAMED anchor form — which is the missing insertion
   point for an added declaration, expressed by identity instead of by offset.
7. `seon.edit.jvm/edit*` writes **in place** under a whole-file digest fence and
   does **not** reanalyze. Staging outside the live checkout does not exist.
8. Per-file incremental indexing EXISTS end to end: `:seon.fn/changed-paths`
   through `refresh-source!` → `full-source-refresh!` → `source/publish!`.
   Measured cost of one non-core file adopted and re-armed: **4,416.8 ms**.
9. Nothing else is needed to make staged files become `default`: the existing
   hook request already publishes changed paths, reloads changed namespaces in
   requires order, unmaps deleted interns and re-arms. Writing rows to `default`
   directly instead of writing files would be the second mechanism.
10. Smallest closure is four new things: a staged-checkout owner, a per-file
    splice composer, an ns→path deriver for new files, and the reanalysis
    equivalence check. Everything else is composition of installed functions.

## 1. The inputs, precisely

| Fact | `file:line` |
|---|---|
| `identity-attributes` — the six identity attributes | `src/seon/program.cljc:17` (HEAD; **wt** lane is converting this to a derived set) |
| `row-identity` — the `[attribute value]` pair | `src/seon/program.cljc:312` (HEAD) |
| `definition-digest-excluded-attributes` — excludes `:seon.fn/file`, `:seon.fn/form-span`, `:seon.fn/calls/references/keywords/writes/call-arities`, `:seon.program/analyzed-source-digest` | `src/seon/program.cljc:323` (HEAD) |
| `definition-digest` | `src/seon/program.cljc:336` (HEAD) |
| `digest-map` — `{identity → digest}` for a database value; refuses a surviving row with no digest | `src/seon/program.cljc:~391` **(wt, uncommitted)** |
| `three-way` — base/branch/head → `:unchanged :changed-on-branch :changed-on-head :added :retracted :conflict` + `:conflict-digests` | `src/seon/program.cljc:422` **(wt, uncommitted)** |
| `:seon.program/absent`, `:seon.program/digest-map`, `:seon.program/three-way` schemas | `resources/seon/schemas/seon.program.edn:5,6,15` **(wt)** |
| `:seon.fn/file` — ref to the file row; "absent on agent-admitted definitions" | `resources/seon/schemas/seon.fn.edn:19` |
| `:seon.fn/form-span` — "Half-open UTF-8 byte offsets of this declaration's exact source within its indexed file" | `resources/seon/schemas/seon.fn.edn:20` |
| `:seon.fn/source` — `[:string {:min 1}]`; the source attribute of the `:seon.fn/sym` identity | `resources/seon/schemas/seon.fn.edn:193`, `:199` |
| `:seon.fn.file/relative-path` (identity) / `:seon.fn.file/digest` (64 chars) — the per-file digest fact | `resources/seon/schemas/seon.fn.file.edn:1`, `:4` |
| `:seon.test/sym`'s source attribute is `:seon.test/source` | `resources/seon/schemas/seon.test.edn:57` |
| `:seon.ns/name`'s source attribute is `:seon.ns/source` | `resources/seon/schemas/seon.ns.edn:9`, `:35` |
| `:seon.schema/key`'s source attribute is `:seon.schema/form` | `resources/seon/schemas/seon.schema.edn:79` |
| `exact-source` — `(subs text start end)` between the analyzer's row/col and end-row/end-col; refuses when the entry has no exact span | `src/seon/fn.clj:185` |
| `exact-form-span` — the same region converted to UTF-8 byte offsets through the context's `byte-line-starts` | `src/seon/fn.clj:207` |
| Row construction: `source`, `file`, `span` and `:seon.program/analyzed-source-digest` (= the FILE digest) written together, for tests and for functions | `src/seon/fn.clj:604-607`, `:623-629`, `:658-666` |

**What `:seon.fn/source` holds.** Exactly the bytes of the top-level form, from
the opening paren of `(defn …)` to its closing paren: docstring, attribute map
and `:malli/schema` metadata included because they are inside the form. NOT
included: preceding `;` or `;;` comments, blank lines, reader metadata written
before the form, and anything between two declarations.

**Probe 1 — population coverage on `default`** (22 ms):

```clojure
(let [conn (seon.cluster.boot/connection "default") db (seon.db/db conn)]
  {:fns (datahike.api/q '[:find (count ?e) . :where [?e :seon.fn/sym]] db)
   :spanned (datahike.api/q '[:find (count ?e) . :where [?e :seon.fn/sym] [?e :seon.fn/form-span]] db)
   :filed (datahike.api/q '[:find (count ?e) . :where [?e :seon.fn/sym] [?e :seon.fn/file]] db)
   :digested (datahike.api/q '[:find (count ?e) . :where [?e :seon.program/definition-digest]] db)
   :files (datahike.api/q '[:find (count ?e) . :where [?e :seon.fn.file/relative-path]] db)})
;; {:fns 4436 :spanned 4435 :filed 4435 :digested 10201 :files 701 :file-digests 701}
```

**Probe 2 — coverage by identity kind** (16 ms), same shape, counting
`:seon.schema/key`, `:seon.test/sym` and `:seon.ns/name` with and without
`:seon.fn/file` / `:seon.fn/form-span`: `{:schema-keys 3285
:schema-keys-filed nil :schema-keys-spanned nil :tests 2071 :tests-spanned 2070
:ns-filed 408 :ns-spanned nil}`.

**What the row does NOT retain** (merge pack §4, confirmed): inter-form comments,
blank lines, form ordering, top-level `(comment …)` and unindexed forms, and
file-level `.cljc` reader-conditional structure — plus, new here, the `ns` form's
span and any file or span at all for schema keys.

**Verdict (1).** Every taken FUNCTION or TEST identity carries file + span +
exact source, and the file row carries the digest the row was indexed from; a
splice needs no fact that is missing. Namespace forms carry source but no span,
and schema keys carry neither — those two kinds cannot be written back by span.

## 2. File-base verification

| Fact | `file:line` |
|---|---|
| `:seon.program/analyzed-source-digest` on each declaration row = the FILE digest at analysis | `src/seon/fn.clj:627`, `:661` |
| `:seon.fn.file/digest` on the file row | `resources/seon/schemas/seon.fn.file.edn:4`; written `src/seon/fn.clj:116`, `:1342` |
| `source/stored-path-digests` — stored per-path digests at a commit | `src/seon/cluster/source.clj:162-166` |
| `source/capture-paths` — read named inputs once, digest and carry the exact text | `src/seon/cluster/source.clj:104` |
| Whole-file digest fence on write | `src/seon/edit/jvm.clj:18` (`stale-source`), `:115` (`edit*` compares `:my.edit/expected-digest` before transforming) |
| `my.fs/write!` precondition (expected absence or prior digest) | `src/my/fs.clj:57` |

**Probe 3 — the whole check, end to end** (7 ms): pull
`[:seon.fn/sym :seon.fn/form-span :seon.fn/source :seon.program/definition-digest
:seon.program/analyzed-source-digest {:seon.fn/file [:seon.fn.file/relative-path
:seon.fn.file/digest]}]` for `[:seon.fn/sym 'seon.edit/valid-form-operation?]`,
read the file's bytes, slice `[start end)` and compare.

```clojure
{:seon.fn/form-span [21882 22793]
 :seon.fn.file/relative-path "src/seon/edit.clj"
 :seon.fn.file/digest                 "4043868b49949de7fd4597f939aaba49040cc3dbd58defc4ff13730e96566888"
 :seon.program/analyzed-source-digest "4043868b…6888"   ; identical
 :disk-digest                         "4043868b…6888"   ; identical
 :span-matches-source? true}
```

**Cheapest check.** One `Files/readAllBytes` + one SHA-256 per touched file,
compared against that file row's `:seon.fn.file/digest` at the commit the rows
were read from. It is strictly stronger than comparing the span's bytes to
`:seon.fn/source`, because it also proves every OTHER span in the file is still
correct — which is what a multi-edit descending-order splice depends on.

**When H moved the file after C.** The byte offset from B's row is then
meaningless. The span must be re-read from H's own rows by identity:

```clojure
(datahike.api/pull head-db [:seon.fn/form-span {:seon.fn/file [:seon.fn.file/relative-path
                                                               :seon.fn.file/digest]}]
                   [:seon.fn/sym 'some.ns/f])   ; identity IS the lookup ref
```

The content to write comes from B (`:seon.fn/source`); the destination
(file + span) comes from H. Only when the identity is absent from H is this an
addition. A digest mismatch between H's file row and the disk file means the
checkout is ahead of `default`'s rows and the write must refuse, not relocate.

**Verdict (2).** File-base verification is one digest comparison against an
existing fact, and span relocation is one pull by identity against the head
database. Neither needs a new attribute.

## 3. `seon.edit` as the splice owner

| Fact | `file:line` |
|---|---|
| Namespace contract: "no cluster, database, filesystem, schema loading, or JVM-handler dependency" | `src/seon/edit.clj:1-8` |
| `byte-span` — char indices → half-open UTF-8 byte span, explicitly "joinable with the declaration spans the indexer wrote" | `src/seon/edit.clj:14-24` |
| `form` — one unambiguous named top-level form (rewrite-clj zipper); returns `:seon.edit/source` + `:seon.edit/form-span` | `src/seon/edit.clj:320` |
| `exact` — one (or every) exact string occurrence | `src/seon/edit.clj:406` |
| `lines` — a one-based inclusive line window guarded by its exact bytes | `src/seon/edit.clj:468` |
| `valid-form-operation?` — operations are `:replace`, `:insert-before`, `:insert-after`, `:delete`; a replacement source must be ONE complete form | `src/seon/edit.clj:517` |
| Lossless check before accepting a candidate | `src/seon/edit.clj:262` (`lossless-candidate`), `:293` |
| JVM handler: read whole file → compare digest → transform → write under `{:my.fs/expected-digest actual}` | `src/seon/edit/jvm.clj:115-155` |
| Result carries `:seon.effect/provenance` with the canonical file path and the exact `:seon.edit/form-span` written — "in the unit the program graph uses" | `src/seon/edit/jvm.clj:66-88` |
| Agent surface, capability `seon.edit.jvm/edit` | `src/my/edit.clj:35` (`form!`), `:59` (`exact!`), `:82` (`lines!`) |
| Filesystem seam actually used | `src/seon/fs/jvm.clj:512` (`read-complete`), `:670` (`write`) |

**Does it stage outside the checkout?** No. `edit*` takes `:my.edit/path` and
writes that path (`edit/jvm.clj:118`, `:141-148`). There is no root argument and
no staging root anywhere in `seon.edit*`.

**Does it reanalyze?** No. It records provenance (`edit/jvm.clj:81-88`) and
returns; publication is a separate request driven by the hook (§6).

**Verdict (3). Reuse unchanged.** `seon.edit` is the correct splice owner for
both spans and named-anchor insertions and needs no modification. What is
missing sits ABOVE it: a composer that groups a row set per file and applies
edits in descending span order against one captured original, and a staging
root so the composer does not write the live checkout. `seon.edit.jvm/edit*` is
the wrong entry for a multi-edit staged write — it re-reads and re-writes the
file once per edit and fences on the digest each time, so N edits to one file
would need N sequential digest hand-offs.

## 4. Added and retracted declarations

| Fact | `file:line` |
|---|---|
| `:insert-before` / `:insert-after` with a form selector (head + name) | `src/seon/edit.clj:517-531`; request shape `src/my/edit.clj:48` |
| `:delete` — no `:my.edit/source` permitted | `src/seon/edit.clj:528` |
| `program/declaration-at` — the declaration whose span contains a byte position | `src/seon/program.cljc:268` (HEAD) |
| `fn/unresolved-callers` — surviving callers refuse a deletion | `src/seon/fn.clj:1522` |
| `deletion-row` — typed identities removed by one deletion | `src/seon/program.cljc:1044` (HEAD) |
| D1: "A genuinely new declaration supplies destination and insertion position; ambiguous namespace-to-file mapping refuses with identity and candidate paths" | `docs/prds/agent-platform/plan/lane-d1-isolation-merge-writeback.md:215-218` |
| D1: new-file absence expressed under `:my.fs/precondition` | `.../lane-d1-isolation-merge-writeback.md:222-223` |
| Namespace rows carry `:seon.fn/file` (408 of 409) but no span — probe 2 | — |

**ADDED identity, existing namespace.** No stored fact supplies an insertion
point, and none is needed: `:insert-after` with `{:my.edit.form/head 'defn
:my.edit.form/name <last-existing-name>}` expresses "after this declaration" by
IDENTITY, which survives an H that moved bytes. The natural anchor is the
declaration that precedes it in B's file — read from B's rows by descending
`:seon.fn/form-span` start. Nothing implements this selection today.

**ADDED namespace → new file.** `:seon.ns/source` holds the `ns` form (409 of
409 rows), so the file's first form is regenerable. The PATH is not: no function
in `src/` derives a file path from `:seon.ns/name`. The one place the mapping
appears is `src/seon/fn/analyzer.clj:695`, and it is a synthetic kondo filename
that does **not** munge `-` → `_`:

```clojure
filename (str (str/replace (str namespace-name) "." "/") ".clj")
```

Reusing that for a real path would create `my.edit` → `my/edit.clj` correctly
but `seon.fn.analyzer` → fine and any hyphenated segment WRONG. A deriver owes
`clojure.lang.Compiler/munge`'s inverse (`-`→`_`), the `src` vs `test` root
choice (`:seon.fn.file/relative-root`,
`resources/seon/schemas/seon.fn.file.edn:6`), and the `.clj`/`.cljc` choice.
**Missing.**

**RETRACTED identity.** The span to delete is the row's `[start end)`. The
whitespace rule is NOT declared anywhere: `edit/form` with `:delete` goes
through the rewrite-clj zipper (`src/seon/edit.clj:230` `splice`, `:234`
`zipper-edit`) and the lossless check (`:262`) validates the result parses back
to the same remaining forms — so whatever rewrite-clj does to the surrounding
newlines is the installed behavior, and it is not stated in a contract. A raw
byte-span delete would leave two blank lines. Prefer `edit/form` `:delete`.

**Verdict (4).** Deletion is installed (`edit/form` `:delete` + the
`unresolved-callers` refusal). Addition into an existing file is expressible
with installed primitives but nothing chooses the anchor. New-file creation is
missing its path deriver; `my.fs/write!` with `:my.fs/expected-absence?` is the
installed write.

## 5. Reanalysis as the proof

| Fact | `file:line` |
|---|---|
| `fn/build-artifact` — one deterministic first-party file projection from a path, including its declaration digests | `src/seon/fn.clj:2104`, digests at `:1275`, `:1344` |
| `fn/analyzed-artifacts` — analyze N paths, optionally from CAPTURED text rather than disk | `src/seon/fn.clj:2176-2200` |
| `fn/analyze-rows` | `src/seon/fn.clj:2308` |
| `fn/plan-file-change` — per-file `:incremental-upsert` vs `full-rebuild`, keyed on `:seon.fn.file/digest` and changed identities | `src/seon/fn.clj:2357`, `:2452-2465` |
| `fn/rows` — narrowed by `:seon.fn/changed-paths` | `src/seon/fn.clj:2466-2474` |
| `fn/index!` — fresh scratch branch, or in-place reconciliation with `:seon.source/previous-database`; `:seon.reconcile/adopt-identities` narrows to exactly the changed identities; `:seon.fn/changed-paths` narrows previous identities by FILE | `src/seon/fn.clj:3228`, `:3255-3262`, `file-identities` `:3160` |
| `fn/published-index-rows` — portable rows for transfer | `src/seon/fn.clj:3063` |
| `fn/report-identities` | `src/seon/fn.clj:3043` |
| `source/publish!` — scratch off the expected commit, reconcile, guarded head advance | `src/seon/cluster/source.clj:390` |

**Per-file indexing exists.** `:seon.fn/changed-paths` is threaded from the hook
all the way down (§6), and `full-source-refresh!` computes `analysis-paths` as
the digest-changed subset unless the classification is `:all`
(`src/seon/cluster.clj:1781-1786` **(wt)**). A `.clj-kondo` config change or a
dependency/gitlink change escalates to the whole tree
(`src/seon/cluster.clj:1783` **(wt)**; landing note
`docs/prds/agent-platform/landing/lane-publication-envelope-2026-09-22.md:36-39`).

**Cost.** From the committed clock rows,
`docs/prds/agent-platform/landing/publication-envelope-clocks-2026-09-22.edn`
(boundary `:scratch-jvm-targets-loaded-and-armed`):

| Case | Elapsed | Namespaces reloaded | Vars re-armed |
|---|---|---|---|
| `adopt-nochange` | 290.2 ms | 0 | 0 |
| `adopt-noncore` (one file, `my/note.clj`) | **4,416.8 ms** | 1 | 3 |
| `adopt-core` (`my.*` surface) | later row, same file | — | 1,552 |

README §5 records explicit no-change publication 264 ms and a repeated docstring
edit 2,723 ms (`docs/prds/agent-platform/plan/README.md:228`), and notes these
are 2026-09-21 baselines to be repeated before implementation. The 4,416.8 ms
row is the honest current price of ONE changed non-core file taken all the way
to loaded-and-armed. No measurement exists for N>1 staged files.

**The equivalence check.** Analyze the staged bytes with `analyzed-artifacts`
(which accepts captured text, `src/seon/fn.clj:2181-2184`), read
`:seon.fn.file/declaration-digests` off each artifact (`src/seon/fn.clj:1344`)
and compare per identity against B's taken digests. `build-artifact` refuses a
path that is not an existing Clojure file (`src/seon/fn.clj:2122`), so the
staged root must be a real directory, not an in-memory map — unless
`analyzed-artifacts` is called directly with `captured`. **Missing:** the caller
that does this against a staged root and reports per-identity equality.

**Verdict (5).** Both halves exist — a per-file analysis producer with
declaration digests, and a per-identity reindex through `index!`. What is
missing is the staged-root caller and the digest-equality assertion. D1's
warning applies: `analyze-forms` with a synthetic prelude
(`src/seon/fn/analyzer.clj:657`) is NOT an equivalent file oracle
(`lane-d1-isolation-merge-writeback.md:230-231`).

## 6. Then the files become default

The installed path, in call order:

| Step | `file:line` |
|---|---|
| Hook collects changed paths and issues ONE prepl request | `bin/seon-hook:1483` (`publish-source-paths`), `:1500`, `:1533` **(wt)** |
| `seon.cluster/refresh-source!` — root + changed paths + development cluster | `src/seon/cluster.clj:2151` **(wt)**, paths relativized `:2190-2192` |
| `full-source-refresh!` — capture, digest-compare, classify, analyze only changed paths | `src/seon/cluster.clj:1754` **(wt)** |
| `source/publish!` — reconcile and atomically publish on the source lineage | `src/seon/cluster/source.clj:390`; called `src/seon/cluster.clj:1829` **(wt)** |
| `development-source-refresh!` — adopt into the running cluster | `src/seon/cluster.clj:2024` **(wt)** |
| `fn/index!` with `:seon.reconcile/adopt-identities` for the changed rows | `src/seon/cluster.clj:2065-2074` **(wt)** → `src/seon/fn.clj:3228` |
| Unmap interns whose identity is absent from the published database | `src/seon/cluster.clj:2088-2096` **(wt)** |
| `development-namespaces` — changed namespaces and dependents in either value | `src/seon/cluster.clj:1933` **(wt)** |
| `load-development-definitions!` — `(require ns :reload)` in `reload-order` | `src/seon/cluster.clj:1901` **(wt)**, order at `:1875` (`namespace-requires`), `:1893` (`reloadable-namespace?`) |
| `verify-development-sources!` — reloaded file digests verified before adoption is recorded | `src/seon/cluster.clj:1966` **(wt)** |
| `env/advance-projection!` then instrumentation arming | `src/seon/cluster.clj:2101-2113` **(wt)** |
| Reload semantics: Clojure `load-one` for `:reload` compiles that file only and does not select dependents — Seon selects them from `:seon.ns/requires` | `docs/prds/agent-platform/landing/lane-publication-envelope-2026-09-22.md:52-60` |

**What would be a second mechanism.**

| Tempting shortcut | Why it is a second mechanism |
|---|---|
| Transact the merged rows onto `default` directly and write files afterwards | Rows would no longer derive from files; `verify-development-sources!` (`cluster.clj:1966` **(wt)**) exists precisely to refuse that divergence, and AGENTS.md's "One JVM, many realities" makes files the authority |
| A write-back-specific reloader | `load-development-definitions!` is the one ordered replacement; a second would not respect `reload-order` or the unmap pass |
| A new digest fact recording "written back" | `:seon.fn.file/digest` plus Git state already answer it; D1 forbids a second completion registry (`lane-d1-isolation-merge-writeback.md:246-251`) |
| An ad hoc `git worktree` for staging | Explicitly refused: `lane-d1-isolation-merge-writeback.md:224-225` |
| Calling `my.edit/form!` per identity on the live checkout | Exposes partially written multi-file source to a live reload — refused at `lane-d1-isolation-merge-writeback.md:239-240` |

**Verdict (6).** Once the staged files replace the checkout's, the existing hook
request carries them to loaded-and-armed rows with no new function. The staged
copy exists to make the multi-file replacement atomic from the reloader's point
of view, which is the whole reason staging is in D1 §2d.2.

## 7. Smallest closure

| # | Change | State | Owning spec row |
|---|---|---|---|
| 0 | `:seon.program/definition-digest` live on `default` | **exists** — 10,201 rows (probe 1); was `nil` in the merge pack | B1 c5, `lane-b1-one-publication-path.md:372` |
| 1 | `digest-map` + `three-way` | **partial** — present in the working tree, uncommitted (`program.cljc:~391`, `:422` **wt**) | `lane-realities-one-lifecycle.md:56` (row 11) |
| 2 | Resolve file + span per accepted identity, from HEAD's rows, not B's | **partial** — all facts present for functions/tests; the inverse resolution pattern exists at `src/seon/effect.clj:272` | D1 §2d.1 |
| 3 | Staged checkout at the expected source commit | **missing** — `seon.edit*` has no root argument; no staging owner in `src/` | D1 §2d.2 (`lane-d1…:219-225`) |
| 4 | Per-file splice composer: one captured original, edits in descending span order, `edit/form`/`edit/exact` per edit, one write | **missing** — every primitive exists (`edit.clj:320,406,517`; `my/fs.clj:57`) | D1 §2d.2 |
| 5 | Anchor selection for an added declaration in an existing file | **missing** — `:insert-after` exists; nothing chooses the anchor | D1 §2d.1 |
| 6 | `:seon.ns/name` → file path for a new namespace | **missing** — only the un-munged synthetic at `src/seon/fn/analyzer.clj:695` | D1 §2d.1 |
| 7 | Reanalyze the staged root and compare declaration digests per identity | **partial** — `analyzed-artifacts:2176` accepts captured text; `declaration-digests:1275` produces the map; no caller | D1 §2d.3 |
| 8 | Prove the changed callable loads and executes | **exists** — B4's isolated host; D1 forbids its own runner | D1 §2d.4 (`lane-d1…:232-235`) |
| 9 | Replace the checkout's files and publish | **exists** — `refresh-source!` → `publish!` → `development-source-refresh!` (§6) | D1 §2d.5; `lane-realities-one-lifecycle.md:58` (row 13) |
| 10 | Recovery: desired bytes already present → no write; unexpected bytes → refuse | **partial** — `my.fs/write!`'s digest precondition is the enforcement; no resume reader | D1 §2d.6 |

Dependency order: 1 → 2 → 3 → 4 → (5, 6) → 7 → 8 → 9, with 10 alongside 9.

**The honest limit.** A span-based write-back cannot:

- **reorder forms** — `form-span` addresses a position, never an ordering;
- **place or preserve intent in comments** — nothing between declarations is stored;
- **edit `.cljc` reader-conditional arms** — 228 declaration rows live in 7 `.cljc`
  files (probe), and the analyzer records `:lang` per entry but no file-level arm
  structure exists to address;
- **touch `ns` forms by span** — 0 of 409 namespace rows carry a span, so an
  added `:require` must go through `edit/form` with head `ns`;
- **write back schema changes at all** — 3,285 `:seon.schema/key` rows have no
  file and no span, and their source attribute is `:seon.schema/form` in a
  resources EDN file that is not declaration-spanned.

**Does it matter?** For agent-authored FUNCTIONS, barely: they carry no comments
and no reader conditionals, and their whole form IS the row. For human files,
it matters at exactly one point — the blank-line/comment region around an
insertion or deletion, which `edit/form`'s zipper handles and a raw byte splice
does not. For SCHEMAS it matters completely: the agent-platform target has
agents declaring attributes, and a schema-key change today has no write-back
path at all. That gap is not in D1 §2d's inventory and should be either ruled
out of scope or given a `:seon.schema/key` file/span fact at the EDN indexer.
