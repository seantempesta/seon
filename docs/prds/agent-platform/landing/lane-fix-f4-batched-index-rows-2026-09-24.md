# Lane fix-f4-batched-index-rows (2026-09-24)

Fix F4 of `docs/research/agent-platform/projection-call-cost-2026-09-24.md`.
Issue: `docs/seon/issues/a-full-reanalysis-publication-takes-twenty-five-seconds.md`.

**What changed:** `seon.fn/published-index-rows` pulled one entity at a time. It
now reads through a new private `reached-entities`: one `seon.db/pull-many '[*]`
per ref depth (4 rounds on default), keyed by identity and `:db/id`, with
omitted attributes removed. `file-identities` now reads identities with one
`pull-many` instead of one eavt seek per entity × identity attribute.
`row`/`reference` are unchanged. All reads still go through the `seon.db` owner.

## Broken first

- **Full reanalysis is still over 10 s: 16,022 ms** (it was 24,804 ms). Most of the
  rest is analysis (`analyze-rows`, 13 s in the issue). The issue stays open.
- **The whole-program arity overflows the stack**, both before and after this
  change: the agent steward ref cycle. Filed as
  `docs/seon/issues/whole-program-index-rows-overflow-on-an-agent-steward-cycle.md`.
  This is what makes `seon.fn-test/indexing-refuses-an-already-populated-branch` red.
- **The save gate refuses every fn.clj save** because 13 fail and 5 error. These were
  red before this lane's code was installed. `bin/test-check default` against the old
  loaded code: `analyzer-edges-are-readable-symbols-or-no-edge`,
  `indexing-refuses-an-already-populated-branch`,
  `indexing-resolves-its-declaration-world-once-per-operation`,
  `indexing-uses-a-prebuilt-manifest-without-analysis` and
  `publication-writes-one-definition-digest-per-declaration-family` were all red
  (run 27f7489c8a7e). The others (mcp-test, config-application, publication-findings,
  program-test, reconcile-test timeout) are in namespaces this lane does not touch.
  The test-file save adopted the change (`red after adoption`, run c42588b68909).
  The installed Vars run the new code: 9 reads, not 901, for two files.
  RESET NEEDED: no.

## Evidence (MCP eval_clj, JVM mode, default, pid 99265)

The same db value, all 744 files, 7,419 rows:

| probe | before | after |
|---|---:|---:|
| `file-rows` all paths (armed) | 2,374 ms | 700 ms |
| `published-index-rows` over those identities | 1,717 ms | 567 ms |
| same, omitting `:seon.fn/arities` | 1,747 ms | 381 ms |
| `file-identities` all paths | 627 ms | 136–151 ms |
| reads for `file-rows` of id.clj + program.cljc (66 rows) | 501 pull + 400 datoms | 5 pull-many + 4 datoms |
| full-reanalysis publication (`publish-ms` from the hook) | 24,804 ms | 16,022 ms |

Equality: `(= new old)` is true for `file-rows` over every path, and for
`published-index-rows` with and without omission, on the same db value.

After the change, the time left in a whole-program read is inside `pull-many`:
about 1 s for 11 calls, which is decoding and `validate-pulled-result` over
thousands of entities. It is no longer per-read owner overhead.

## Second half of F4 (changed files only)

The callers already read only the changed paths: fn.clj:2505, 2912 and 2946 pass
`:seon.fn/changed-paths` or `selected`. In an `:all` reanalysis every file is
changed, so the whole-program read is inherent there, and nothing was implemented.
One remaining lead: one publication reads the same (database, paths) through
`file-rows` up to three times (2505, 2912, 2946). Sharing that read is outside
this lane's budget.

## Regression

`seon.fn-test/file-rows-read-in-batches-not-per-row` binds
`seon.db/*read-evidence-sink*`, the owner's read capture. It asserts fewer than 16
reads for more than 16 rows, and that the batch equals the rows each identity
reads alone. `bin/test-check default --test seon.fn-test/file-rows-read-in-batches-not-per-row`:
passed? true (run bc5b5a34cd57, reused 1).

## Size

`src/seon/fn.clj` +44 −20 (net +24). `test/seon/fn_test.clj` +16.

## Limits

- The before and after times are single warm runs on a loaded shared JVM.
- The full-reanalysis numbers come from two different hook publications (old
  loaded code, then new). They are not a controlled A/B.
