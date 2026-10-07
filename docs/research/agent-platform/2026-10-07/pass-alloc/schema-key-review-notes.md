---
type: notes
status: awaiting-central-tests
created: 2026-10-07
lane: schema-key-review
---

# Schema-meta key review

**FALSIFIED for the original commit.** Datahike `8ed09208b3c41362cad607b3cd271bbc7ece07c3`
can reuse a key after the schema's content changes. The fix is prepared on `seon/schema-meta-key`;
it is not committed or promoted by this lane while root's central tests are pending. This lane
advanced or pushed no refs. **At handoff, an external concurrent action had nevertheless moved local
`main` and all three origin branches (`main`, `seon/identity-join-first`, `seon/schema-meta-key`) to the
unsafe original `8ed09208`.** Verified with `git ls-remote origin` after the initial notes commit;
local `seon/identity-join-first` still read `dbca76d5`. No attempt was made to reverse another actor's
ref changes. Seon's recorded gitlink remains `dbca76d5`.

## Broken things first

1. `:db.secondary/config` admits arbitrary values (`src/datahike/schema.cljc:105`). A Date or byte
   array is retained inside the schema. Changing that object preserves all five schema-meta map
   identities but changes `hasch/uuid`. The original key reuse therefore violates its stated equivalence.
   Task `seon.db/schema-meta-key-reuse-accepts-mutable-schema-values` (`463f5782327e`).
2. The original allocation assertion serializes `@conn` AFTER `d/transact` has already serialized it
   and primed the cache. It distinguishes the original optimization from unconditional hashing, but
   does not distinguish reuse across successive database values from reuse of just one database value.
   It checks identity of only `:schema`, not the other four members. The edited regression uses
   `d/with` and measures the first serialization of its result, checking all five identities.
3. A one-datum raw durable commit on the cloned default store still allocates about 105 MB after this
   optimization. Attribution of the remaining cost is not established here. Task
   `seon.db/a-small-raw-commit-allocates-a-hundred-megabytes` (`cd6c2900565a`); related existing tasks
   `seon.cluster.store/each-commit-serializes-its-root-record-twice` and
   `seon.cluster.store/an-ordinary-commit-writes-26-files-and-136-kb`.
4. Directly replacing `:db/ident` through `[:db/add [:db/ident :r/a] :db/ident :r/b]` throws
   `Keyword cannot be cast to IPersistentCollection` in the transaction path. Retract-old/add-new in
   one transaction works. This is outside the two owned files. Task
   `seon.db/renaming-an-attribute-directly-throws-a-class-cast` (`07f0876b2a63`).
5. Full fork lint has 143 error diagnostics on BOTH parent and fixed candidate (139 distinct messages
   after normalizing line numbers), including its nonexistent `script` directory, existing cljs/private
   Var and macro-analysis findings. No new error messages. This is not a zero-error fork-wide pass.

Default was idle (`open-turns 0`) when its store was cloned. `bin/seon status` and MCP `runtime_status`
both reported `:faulted`, with the existing cross-branch settlement fault
`seon.turn.batch/an-entrant-inside-another-custody-cannot-record` (`3d2fb1b41dc8`), four latency warnings,
eight errored receipts and three unowned namespaces. Nothing was restarted or reset. The tasks are
recorded through `seon.task/create!` on default; no Monitor task or poller was created.

## Counterexample and producer fix

The following ordinary transactions admit the mutable schema value (fresh in-memory store):

```clojure
(let [date (java.util.Date. 1000)]
  (d/transact conn [{:db/ident :r/mutable
                    :db/valueType :db.type/string
                    :db/cardinality :db.cardinality/one
                    :db.secondary/config {:at date}}])
  ;; After the first commit has keyed this schema:
  (.setTime date 2000)
  (d/transact conn [{:r/a "after-mutation"}]))
```

In the full reproduction (the accompanying probe establishes `:r/a` and the preceding schema history):

| implementation | stored/current key | old algorithm's expected key |
|---|---|---|
| `dbca76d5` | `1f24c9fc-e1de-5bd5-a8c8-24501d19444d` | same |
| `8ed09208` | `091603c4-99f2-510e-a24c-6d361cd2f8f8` | `1f24c9fc-e1de-5bd5-a8c8-24501d19444d` |
| fixed candidate | `1f24c9fc-e1de-5bd5-a8c8-24501d19444d` | same |

The Date is `identical?` to the value retained in the schema; the schema map is `identical?` before and
after the second commit. The mismatch is also present in the durable head's `:schema-meta-key`, not
just a private helper. A second probe replaces the Date with a byte array, mutates byte zero, and
confirms both the direct key and durable head equal the old hash with the fix.

**Design check — EQUIVALENT relative to the old hash under its own content/hash assumptions.**
`db->stored` constructs the same fixed five-member map (`writing.cljc:84-89`). Keep its one-slot
cache, but store whether its content consists of known immutable values. Check this on a cache miss,
not every commit. Reuse only when all five identities match AND that eligibility is true. Otherwise
compute the old `uuid` expression. Persistent collections are traversed recursively; mutable and
unrecognized leaves are conservatively ineligible. Big numeric types outside the whitelist also
rehash, preserving correctness without widening the optimization's assumptions.

The constructor's fixed key set is material: the private helper's identity test is not a general map
comparison. Hashing is assumed to retain its normal pure implementation, and persistent Clojure
collections their immutability contract. Concurrent mutation during the hash itself has the old
algorithm's limitations; this change does not promise to repair mutable values already retained under
older content keys by the memory backend.

The cache reads `[schema key eligibility]` atomically and publishes that tuple together. A competing
writer can evict a useful entry but cannot pair one schema with another key. Alternating different
schemas still causes misses. Hit cost is O(5), space one retained schema/key tuple. A changed schema
costs O(schema size) hashing plus one eligibility walk; an ineligible unchanged schema hashes again
without repeating the eligibility walk. No per-store cache, invalidation mechanism, second writer or
Seon-side state was added. This serves the record step of the existing loop. The smallest alternative,
unconditional hashing, was measured below; retaining it for unsafe inputs is the old behavior.

Contracts were added to the two keying helpers. The total eligibility predicate intentionally accepts
`:any`; it answers false for unrecognized values. Its caller declares all five schema-meta members.
Malli `function-schema` compiled both contracts, and the installed
`seon.contracts-compile-test/check` against the archived packaged projection returned `[]`.

## Equivalence observations

Each observation compares the old `hasch/uuid` of the complete five-member map with `db->stored`'s key;
committed cases additionally compare the stored branch head. Parent, original and fixed candidate
were loaded from distinct archives in separate JVMs, never redefined in default.

- Fresh store; attribute add; doc change; property retract; attribute retract: all equal.
- Rename by retracting the old ident then adding the new ident: equal.
- Branch with a different schema, back to base, then back to other: equal.
- Merge with a supplied schema transaction: equal.
- `stored->db` with current separate schema metadata and legacy inline schema metadata: equal.
- Fixed candidate: 160 concurrent alternating-schema comparisons with attribute refs false, and
  160 with attribute refs true: all equal. Every future was dereferenced before connection release.
- Mutable Date and byte array: fixed candidate direct and stored keys equal the old hash. Original's
  Date counterexample is false; parent and fixed candidate are true.

The original/fixed/parent ordinary-case key sequence was respectively identical in every variant:
`2fda0855` (fresh), `09989ee4` (add), `3de1d987` (doc), `09989ee4` (retract doc),
`2100539d` (rename), `1d715f7e` (retract attribute), `22e521bd` (other), `2d17f86c`
(base), `22e521bd` (other), `38cc6db5` (merge, restored, inline legacy). Prefixes identify
observations here; the probe emits the complete UUIDs.

## Same-store cost

Scratch: `/tmp/codex-01a11611-2a2b-7e52-99bb-ef630efb511f/schema-key-review/`.
Clone command, while default was idle:
`cp -c -R /Users/sean/src/seon/data/store <scratch>/store`.
Only the clone was opened by measurement JVMs. Each variant branches from the clone's unchanged
`:cluster-default` head, adds the same `:review/value` attribute, warms 30 one-datum commits, then
measures 60 sequential one-datum commits. All runs had exactly 60 measured commits and zero Seon armed
calls (raw Datahike). Store config: history enabled, persistent-set, branching factor 128, diff buffer
32, fused roots, deferred immutable sync, LZ4; 2,318 schema-map entries before the extra attribute.

JDK 26.0.1, Clojure 1.12.4, each JVM `-Xmx2g`. Allocation is
`ThreadMXBean.getTotalThreadAllocatedBytes`; caller-thread allocation alone was 1,056 bytes/commit
and would miss the async writer. Timings use `System.nanoTime`. Median of 60 commits; keying rows
median of 40 calls. Acquisition was measured separately (84–379 ms / 6.7 MB); JVM namespace load is
outside these rows. Peak heap was not measured. MB below are decimal.

| operation | parent `dbca76d5` | original `8ed09208` | fixed candidate |
|---|---:|---:|---:|
| durable commit | 49.980 ms / 122.721 MB | 45.990 ms / 104.619 MB | 44.992 ms / 104.635 MB |
| serialize held db, no flush | 4.233 ms / 18.130 MB | 0.0061 ms / 3,704 B | 0.0058 ms / 3,704 B |
| first serialization of new `d/with` result | 4.227 ms / 18.130 MB | 0.0172 ms / 3,704 B | 0.0107 ms / 3,704 B |

A second matched parent/fix run added store-size snapshots outside the allocation/time measurement:

| measure | parent | fixed |
|---|---:|---:|
| commit median | 48.945 ms / 123.015 MB | 43.869 ms / 104.621 MB |
| commit min–max | 41.922–111.116 ms | 36.895–87.683 ms |
| store bytes before 60 commits | 685,381,330 | 689,641,845 |
| store bytes after | 688,120,127 | 692,380,910 |
| growth / commit | 45,646.6 B | 45,651.1 B |

The 4.5 B/commit disk difference is timestamp/UUID/compression noise: stored schema keys and serialization
shape are unchanged. Branches share the original clone head, not earlier benchmark branches; the larger
absolute store in later runs includes prior benchmark branches. The byte counters are logical file
lengths, not APFS allocated blocks. Allocation difference between original and fix is under 0.02% in the
first run; hit-path allocation is identical. There is no demonstrated performance regression from the fix.

Committed measurement sources: `schema-key-review-bench.clj` (set `SCHEMA_REVIEW_STORE` to the disposable
clone) and `schema-key-review-probe.clj`. Run each in its own JVM with the chosen Datahike archive's
`src`, `src-secondary`, `resources` and compiled Java API plus that revision's dependency classpath.
The benchmark creates its own branch and releases its connections; dispose of the clone after use.
Raw logs remain in the lane scratch directory. No measurement code was loaded into default.

## Verification and handoff

The user's last instruction reserves test runs for root. **No test suites were launched by this lane.**
The observations above are probes, not a claim of green suites. Root's exact central list is:

- `datahike.test.schema-meta-key-test/a-commit-that-changes-no-schema-hashes-no-schema`
- `datahike.test.schema-meta-key-test/mutable-schema-values-are-rehashed`
- namespaces `datahike.test.schema-test`, `datahike.test.transact-test`,
  `datahike.test.writer-error-test`, `datahike.test.commit-graph-test`,
  `datahike.test.versioning-test`, `datahike.test.audit-verify-test`, `datahike.test.store-test`.

Use the fork's test alias, including the optional hitchhiker backend, for suites that cover it. The
prior pass-alloc note's missing-hitchhiker error is not a green test and was not retried here.

Seon HEAD archive, `clj-kondo --lint src script test --cache false`: 0 errors, 1,483 warnings, 15.0 s.
After another lane landed, a fresh HEAD archive was checked again: 0 errors, 1,482 warnings, 7.19 s.
Fork HEAD archive plus this exact two-file diff: full lint 143 inherited errors / 616 warnings, 2.36 s;
parent archive: 143 / 616, 2.22 s. Normalized error sets are equal. Touched fork files: 0 errors,
7 warnings, 37 ms. The fork's inherited lint/analysis configuration is a foreign boundary, not changed
code in this slice. The installed contracts compile check returned `[]`; both native Malli contracts
also compiled in the disposable JVM. Edited measurement scripts lint with zero errors.

Other lanes' Seon source/test edits were excluded by archiving HEAD. No foreign sessions or files were
resumed, messaged or edited. `origin/main` was fetched at `dbca76d5`; rebasing `seon/schema-meta-key`
onto it was a no-op before editing. This lane made no force push, worktree, promotion or gitlink commit. The externally changed refs
observed at handoff are recorded at the top of this note.

The fork fix remains a two-file working-tree diff on `seon/schema-meta-key`, for root's central tests
and commit: source +17/-8 (net +9), tests +32/-5 (net +27), total +49/-13 (net +36) relative to
`8ed09208`. Notes and reproducible scripts are committed separately through `bin/lane`. Fork source
is not represented as a tested or committed landing. All started JVMs exited; owned clone removed
at completion, raw evidence retained. Default needs no restart or reset for this review; installing a
later accepted fork change remains root's gitlink/adoption work.
