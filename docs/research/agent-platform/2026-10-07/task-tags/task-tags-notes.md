# task-tags — notes (lane task-tags, 2026-10-07)

Owner rulings: (C) delete `test/my/examples_test.clj`; (A) `:seon/tasks` tags name tasks by quoted `'ns/task-name`
symbols; (B) `seon.id/id` hashes value structure, never printed text. Order C, A, B.

## Probe (start)

- `bin/seon status`: default pid 65136, `:faulted`, running commit 42e569d9d. Health top: definition regressions on
  `seon.db/pull-call` (65→262 us) and `seon.db/pull-logical`; `:seon.test.runner/reach-declarations` cache 0% hits at
  34-68 MB; 14 open faults (one `java.lang.StackOverflowError` in `seon.error/normalize`, no task linked).
- Ugly output: a JVM-mode reply clipped my own repository path strings at 32 characters
  (`"resources/seon/tasks/seon.cluste"…+11`) inside a 30-row vector. Agents' own values are never clipped;
  this is the value printer's per-string budget on a small value.

## Slice C: delete the examples test

- Deleted `test/my/examples_test.clj` (owner ruled delete; the uncommitted edits it carried are moot) and its orphaned
  fixture `test/my/examples/nested_fixture.clj` (its only requirer was the test). `test/my/examples_fixture.clj`
  stays: `seon.test.runner-test` uses it.
- Closed both tasks by deleting their files: `my.examples-test/docstring-examples-needing-a-live-cluster-cannot-run-in-the-examples-fixture`
  and `my.examples/the-examples-test-reads-a-docstring-example-as-an-invalid-token` were about that test's world and
  its reader; nothing else executes docstring examples.
- `seon.cluster.instruction/toolkit-includes-fixtures-and-undocumented-generators` no longer names the deleted fixture.
- Stale references left for their owners: `.agents/skills/clojure-testing/SKILL.md` §2.11 and §3 name the deleted
  test (lane final-revision); `docs/seon/schedule/README.md` lines on `examples_test` (root's index);
  `docs/seon/issues/an-agents-test-world-has-no-agent-so-my-functions-cannot-be-tested-as-documented.md` cites it.

## Slice A: tasks are tagged and read by `ns/task-name` symbols

What existed: `:seon/tasks #{"<handle>"}` string sets copied verbatim by `seon.fn/task-links` into the program
entity's `:seon.task/tasks` (strings, indexed); readers (`seon.task/task-edges`, `open-tasks`, `task-findings`,
`candidate-dangling`, `seon.error/proof`) joined those strings to `:seon.task/id`. 130 task-file entries wrote their
handle as a literal `:id`; all matched their derivation.

Change (BEHAVIOR CHANGE; schema, ingestion and readers in one slice):

- `:seon.task/sym` (`ns/name`, `:db.unique/value`, row schema `:seon.task/task`) is derived at ingestion beside the
  handle (`seon.task/task-sym`), by the file reader, `create!`/`propose!` (runtime) and `seon.error/producer-task`.
  It is derived like the handle and stays out of `:seon.task/declaration` (the revision a basis compares): a stored
  task written before this slice has no symbol until the reset rebuilds the tasks.
- `:seon/tasks` takes quoted symbols, resolved through the namespace's aliases like `:seon/must-read`
  (`task-links` uses the same `link`); a string tag refuses `:malformed-annotation`.
- `:seon.task/tasks` holds symbols (`:seon.db/reference-to :seon.task/sym`, so the related-data walk follows
  `[:seon.task/tasks :seon.task/sym]` with no hand list). Valuetype string→symbol: adoption replaces the attribute
  (`seon.cluster/attribute-change-tx` `:replace`) and the reindexed program writes the symbols.
- Readers join tags to `:seon.task/sym`: `task-edges` (handle in, referrers now all symbols), `open-tasks` and
  `task-findings` (`:dangling` keyed by symbol, `:triage` a set of symbols), `candidate-dangling`,
  `seon.error/proof`. The task's AI face (`seon.render.ns/task-ai`) and `my.task/open` show `:seon.task/sym`.
- Ingestion: the `:entry` schema no longer admits `:seon.task/id` (closed, so a written handle refuses
  `:invalid-entry`); `:handle-mismatch` and `:duplicate-id` are replaced by `:invalid-name` (a name that is no
  symbol name, `seon.task/symbol-name?`) and `:duplicate-name` (same ns+name, any kind; Datahike's unique value
  refuses the same across writers).
- Runtime names became symbol names: `merge-<key>`, `faults-of-<name>`, `work-of-<agent-id with / as .>`; `create!`
  refuses a non-symbol name with `:invalid-name`.
- Sweep (one script, `scratchpad/task-tags/sweep.py`, mapping from `seon.task/task-handle` over every task file):
  every `:id` stripped from 68 task files; every `:seon/tasks` handle set in src/test rewritten to quoted symbols
  (85 sets in 50 files); handles quoted in task intents replaced by the symbol; the three spaced names hyphenated
  (`seon.sci.reader/schema-entities-lose-their-declaring-namespace`,
  `seon.sci.reader/a-value-deeper-than-the-readers-stack-cannot-be-read-back`,
  `seon.sci.eval/a-fresh-contexts-result-handles-have-no-regression`). Hand conversions: task_ingestion,
  related, walk_graph, source, host, resolution, ns, program tests; `test-support/task-row` writes a symbol.

Design check: the design held against the code with four corrections found while converting and proving.
1. Stored tasks written before the slice carry no symbol, so on a live branch every tag dangles until the tasks
   are rebuilt. Putting `:seon.task/sym` in the declaration would let the delta rewrite them, but it changes what a
   basis compares (`seon.cluster.host-test` passes a hand basis without it and refused `:revision-conflict`); the
   symbol stays derived, outside the revision, and the owner-approved reset rebuilds the tasks.
4. `task-edges` joined the tag through the task row, but an agent's branch may hold the tag and not the task row
   (the task is created at the home after the branch forked): `my.program/propose-merge!` answered `:no-proofs`.
   `task-edges` now takes the task's symbol, which `seon.task/assignment` reads at the home
   (`:seon.task/assigned-work` gains `:seon.task/sym`); `seon.agent.loop/task-context` and the task's form face pass
   the symbol.
2. Runtime producers minted names with spaces (`"faults of f"`, `"work of <id>"`, `"merge <key>"`); a symbol needs a
   symbol name, so the producers changed and `create!` refuses others. An agent id with whitespace still cannot
   make a work task (refused by name, loud).
3. `walk` follows value references only to unique attributes by shared named leaf or `:seon.db/reference-to`; the
   tag attribute needed the explicit reference and the symbol attribute its row schema, else the related walk would
   own task nodes under `:seon.task/sym` itself.
Awkward: `seon.error/producer-task` still derives the handle with its own `id/id` (it cannot require `seon.task`:
`seon.task` requires `seon.error`); it now derives the symbol beside it the same way.

Held files: `test/seon/render/value_test.clj` and `resources/seon/tasks/seon.render.value.edn` (lane source-display)
carry 2 handle tags and 2 `:id`s; the slice cannot land without them (ingestion refuses). Patch:
`scratchpad/task-tags/held.patch`; root arranged the transfer.

Not converted (recorded for root): `seon.agent.loop/task-context`, `seon.task/assign!`, `my.task/claim!` and
`seon.task/resolution` still take a handle (task-context now passes the symbol to `task-edges` and its docstring
example derives the handle instead of citing one). Health and fault delivery show runtime task handles (`:tasks
#{"9abceddcc929"}`), not symbols. Accepting the task symbol wherever a handle is taken would finish "tasks are
referenced as `ns/task-name`".

### Landing path and proof (root's option 1)

`bin/seon check` refused: it rehearses publication in default's running JVM (pid 65136, old compiled `seon.fn`), and
the old `seon.fn/task-links` cannot index `#{'ns/name}` tags ("seon.fn/task-links refused its result at
[:seon.task/tasks (quote my.edit/file-edit-tools-bypass-write-back)]",
`data/clusters/default/logs/operator-check-20261007T145415.659.edn`). A rehearsal by the old indexer of an indexer
change is the wrong proof by construction. Task `seon.operator/check-rehearses-an-indexer-change-with-the-old-indexer`
(created on default, handle `5d2bfc4c45f8`, persisted in `resources/seon/tasks/seon.operator.edn`).

Proof = the candidate booted in a throwaway JVM on a disposable store (fresh-install adoption by the new code) with
the reaching tests, through the operator's own `candidate-revision!` and `candidate-tests!`:

    bb -cp script:src <scratchpad>/task-tags/throwaway-full.clj   # paths = the slice's path list

Runs (candidate, then HEAD baselines for every red):

| run | commit | tests | result |
|---|---|---|---|
| first boot | candidate | (boot) | refused: dangling `seon.schema/declaration-stamp-has-no-caller` — my mapping read `seon.schema.edn.edn` as namespace `seon.schema`; fixed to `'seon.schema.edn/declaration-stamp-has-no-caller` |
| 85225de5ff9f | candidate | 11 names | 176 pass, 7 fail, 2 error |
| dd2d7c0063ce | HEAD 9d6b392b6 | the 4 red tests | 17 pass, 7 fail, 2 error: identical reds |
| 46e98cf72f2f | candidate | host, ingestion, ns, loop | host-test 5 fails (3 on HEAD, 2 mine: basis conflict), found `task-edges` needing the symbol |
| e3c499a59e18 | HEAD 9d6b392b6 | host-test proposal | 13 pass, 3 fail (lines 439, 440, 446) |
| 1c27e62a17ae | final candidate (tree of `dad76d540`, lint of its `git archive` 0 errors) | 14 names incl. long | 216 pass, 10 fail, 2 error: exactly the HEAD reds above |

Pre-existing reds on HEAD 9d6b392b6, unchanged by the slice: task `seon.test/reds-on-head-9d6b392b6-in-task-reaching-tests`
(handle `f8a29d6a540a`, persisted in `resources/seon/tasks/seon.test.edn`).

Mistake, corrected: a JVM-mode `spit` with relative paths wrote two task files inside default's running source
archive (`data/source/42e569d9d…/resources/seon/tasks/seon.operator.edn` and `seon.test.edn`): default's working
directory is that archive, not the checkout. Both restored byte-for-byte from `git show 42e569d9d:<path>` and
verified with `cmp`; only a publication or boot from that archive reads them, and none ran in between.

## Fix forward: A could not adopt onto the live store

`bin/seon start --head` on 0daf87818 failed and rolled default back to 42e569d9d: "The cluster population transaction
was refused: The stored definition digest of [:seon.fn/sym my.background/poll] is not the one its definition
produces." (`data/clusters/default/logs/operator-start-20261007T151519.398.edn`, `seon.cluster/accrete-schema-population!`).

Cause (two producers, both found by reproducing the upgrade):
1. `:seon.task/tasks` changed value type (string to symbol), so `seon.cluster/attribute-change-tx` replaces the
   attribute and drops its data from every program row holding a tag. The writer (`seon.db/write-digest-error`)
   then judged those rows: their stored digest still covered the dropped tags, so it refused. Nothing re-derived a
   digest for data dropped by an attribute's replacement. Fix: the writer's `prepared-write` re-derives, in the same
   transaction (the existing `namespace-digests-tx`), the digests of the namespaces whose function or test rows lose
   family-owned data by operation (`:db/retract`, `:db.purge/attribute`) without the transaction writing their
   digest (`seon.db/dropped-definition-namespaces`); a row whose digest the transaction writes keeps the check.
   Regression: `seon.db-test/a-retraction-of-a-declarations-own-data-re-derives-its-digest`.
2. Then task indexing refused `[48356 :seon.task/sym]` missing: stored tasks written before A carry no symbol, and
   the task delta only rewrites changed declarations. Fix: `seon.task/tasks-tx` first derives the symbol of every
   stored task (file or runtime) lacking one (`symbol-tx`), so the tags resolve and later writes validate.

Reproduction and proof, both through the operator's own launch functions (scratchpad `upgrade.clj`): a disposable root
boots the pre-A commit 58f2af834 (old schema, string tags), stops, then boots the new commit on the same store, as
`start --head` does on default.

| run | old → new | result |
|---|---|---|
| upgrade, HEAD 0daf87818 (no fix) | 58f2af834 → eabc94b59 | old ready 117 s; new refused: the same `my.background/poll` digest refusal as default |
| upgrade, HEAD + fix 1 | 58f2af834 → aadd06f71 | schema population passes; task indexing refused `:seon.task/sym` missing |
| upgrade, HEAD + fixes 1 and 2 | 58f2af834 → 5b367154f | old ready 88 s, new ready 84 s |
| fresh throwaway 7ef77238e017 | HEAD + fixes | 22 tests, 100 pass, 1 fail: `seon.task-steps-test/steps-persist-across-a-compaction`, red on HEAD (task `seon.test/reds-on-head-9d6b392b6-in-task-reaching-tests`) |

Verification limit: the upgraded throwaway keeps its program (its tests ran the pre-A test bodies, run 436eb7bb101c),
so incremental adoption of the changed program rows onto an upgraded store is proven only by default's own
`start --head`; the same rows adopt onto an empty store in the fresh throwaway.

## Fix forward 2: runtime tasks on the cluster branch

`start --head` on 178aa24e0 failed again (rolled back): "seon.db/transact! refused transaction data at [106991
:seon.task/sym]" in `seon.cluster/adopt-entities!` (`operator-start-20261007T154542.904.edn`). Entity 106991 is
`seon.schedule/a-scheduled-collection-runs-inside-the-schedule-proc-step-and-reports-as-in-world`, a runtime task
(created by root, 2026-09-29) later persisted to its task file, so the publication holds it under the same handle.
Development adoption copies published task rows onto the cluster branch through `seon.task/adopt-tasks-tx`, whose
delta writes only changed declaration members; the symbol is no declaration member, and `symbol-tx` ran only on the
publishing branch, so the cluster's stored row stayed without one and its rewrite failed the task schema. Default
held 449 tasks on `:cluster-default` (137 runtime, 27 names with spaces, slashes or colons; no two with one ns and
name). Fix: `adopt-tasks-tx` also starts with `symbol-tx` on its destination, so every task the cluster holds
(runtime or published) gains its derived symbol before the delta.

My earlier "upgrade" proofs never ran development adoption (the operator's `start` stops at readiness;
`move-to-head!` adopts with an `:init` request afterwards), and a fresh old-commit store holds none of default's
runtime rows. Proof now on default's REAL data: an APFS clone of `data/store` (`cp -c -R`, 17.5 s) in a disposable
root, booted from a proof-only commit (HEAD 178aa24e0 + this `seon.task` fix + a store-id override,
`SEON_PROOF_STORE_ID`, because the store id is derived from the canonical path and Datahike refuses a store opened
under another id), then the same `:init` development adoption `move-to-head!` sends:

| run | result |
|---|---|
| clone boot (no adoption) | ready 91 s; still on the live store's source commit 6ac5d64c, so it proved nothing about adoption |
| clone boot + adoption | ready 75 s, adoption 13.5 s, commit 6ac5eccc, converged, 0 refusals |
| clone boot + adoption + tests, run 54eeecb2f3dc | 27 tests, 105 pass, 1 fail = `seon.task-steps-test/steps-persist-across-a-compaction` (red on HEAD) |

The store-id override exists only in the proof commit (`git commit-tree`, never on a branch); it is not landed.

## Slice B: `seon.id/id` hashes value structure

What existed: `seon.id/id` printed the value under fixed bindings (iterative printer) and streamed the text into
SHA-256; a second text hash, `seon.schema/canonical-data-string` (framed text, members sorted by encoding), fed
definition digests, schema map-entry key fingerprints, the schema declaration digest, the projection fingerprint and
read-result digests; `pr-str` fed `seon.test.runner/report-identity`, `seon.test.cache/input-evidence-digest` and the
reach schema leaves (after a `pr-str`-comparator sort, `reach-canonical`); the reach component digests hashed
`(str item)` lines.

Change (BEHAVIOR CHANGE: every stored identity re-derives):

- `seon.id/id` = SHA-256 of a tagged structural encoding (`structural-digest`): one tag byte per value; longs and
  every integer that fits a long as 8 bytes (so `1` = `1N`, `(int 5)` = `5`); doubles by bits with -0.0 folded;
  BigDecimal with trailing zeros stripped; ratio, char, UUID, Date, Instant, Pattern, tagged literal; strings as
  int length + one byte per character when ASCII, else UTF-16 units under their own tag (exact, no replacement
  character, so `"\uD800"` ≠ `"?"`); keywords and symbols as namespace and name; vectors and other sequentials
  ordered with distinct tags (a vector ≠ a list, as code identity needs); maps, sets and records as the sum mod 2^256
  of their members' own digests (order-independent, no sort, no cancellation), with count and, for a record, its
  class. Metadata is ignored. Anything else throws `:seon.id/unhashable-error` (registered in `seon.id.edn`;
  hand-built map, because `seon.error.refusal` requires `seon.id`). Iterative over an explicit stack: depth costs
  heap. Loads and agrees in babashka.
- Converted to `id/id`: `seon.program/definition-digest`, `typed-key-facts` key fingerprint,
  `seon.schema.edn/declaration-digest`, `seon.schema/entry-fingerprint` (32-bit head of the id; projection
  fingerprint version 4), `seon.db/read-result-digest` (an unhashable value answers nil as before),
  `seon.test.runner/report-identity`, `reach-component-digests`, `test-reach-digest`, the reach schema leaf
  (`reach-canonical` deleted), `seon.test.cache/input-evidence-digest`.
- Deleted: `seon.id/printed-digest` and its proxy sink; `seon.schema/canonical-data-string`,
  `canonical-value-string`, `framed`; `test/seon/schema_encoding_test.clj` and
  `seon.schema-test/canonical-encoding-remains-byte-identical-after-dispatch-reorder` with its legacy encoder (tests
  of deleted machinery). Its generators moved into the new law property in `seon.id-test`.
- `seon.id/printed` stays: it is stored text (schema storage, admitted values, turn source), not identity.
- Task handles all change with the hash; a changed handle keeps its symbol, so `seon.task/task-delta` now orders
  retractions first (else the new row claims the unique symbol before the old row frees it: the clone upgrade
  refused "a unique :seon.task/sym value is already held"). Regression
  `seon.task-ingestion-test/a-task-whose-handle-changes-keeps-its-symbol`.
- Closed `seon.id/identities-hash-structure-not-printed-text` (task file removed). Not converted, recorded as
  `seon.operator/build-cache-keys-hash-printed-text`: the dependency-class cache key (`script/seon/dev/dependency_digest.clj`,
  loaded by path on tools.deps' tool classpath, which lacks `src/`) and the operator's program-classes key. The
  `pr-str` comparators that order values are task `seon.program/values-printed-to-order-or-compare`
  (`seon.schema.datahike/storage-compare` among them). `seon.error`'s occurrence identity still builds a
  `sorted-map` before hashing (harmless now; `src/seon/error.clj` is lane error-delivery's).

### Measurements

Same inputs both sides, in one standalone JVM (`clojure -M scratchpad/task-tags/bench.clj`, HEAD's `seon.id` loaded
as `seon.id-old`): every test's reach input as the old reach digest printed it, `[test [[name leaf] ...]]` over its
closure, from default's reach graph of 2026-10-07 (2,398 tests, 9,232 nodes; 5,479,722 closure entries; 457,056,962
characters printed). Median of 3 after one warm call, thread-allocated bytes; the machine was loaded (load average
10 to 28), so times vary by run:

| run | printed into SHA-256 (HEAD) | structural (B) |
|---|---|---|
| 1 | 5,780 ms, 2,762.6 MB | 3,672 ms, 332.1 MB |
| 2 | 7,252 ms, 2,762.5 MB | 4,437 ms, 332.1 MB |

Allocation is 8.3 times lower and now proportional to the number of collections (56 bytes per vector frame), never
to the text; time is bound by SHA-256 over about the same number of bytes. Per element (100,000-element vectors):
strings, symbols and longs 0.1 B, two-element vectors 56 B, one-entry maps 420 B (a frame per member and a digest
finalization). Laws: the 24-row table and the 500-trial order property in `seon.id-test` hold standalone and in
the throwaway run.

### Proof

- clj-kondo `--lint src script test --cache false` on the candidate's `git archive`: 0 errors.
- Fresh throwaway on the candidate, run 4b2dacbf61e4 (230 tests: id, task ingestion, runner, db, schema, program,
  related, walk-graph, queue): 11,797 pass, 28 fail, 4 error; every red is red on HEAD 178aa24e0 (baseline run
  8c1ff443a361 over the same tests: `seon.db-test` 2, `seon.program-test` 6, `seon.schema-test` 4 to 5) or the
  fixture declaration test fixed by 8d9791b32. Task `seon.test/reds-on-head-178aa24e0-in-db-program-schema-tests` (below).
- Clone of default's live store (APFS, 36 s), booted from a proof-only commit (candidate + store-id override), then
  the operator's `:init` development adoption and `:reset`, as root will run them: ready 84 s, adoption 66 s (every
  digest and handle re-derives once), reset 7.3 s, then tests run cbb608149713 (id, task ingestion, queue, related):
  141 pass, 0 fail.
- Final candidate (task files and notes included), lint of its archive 0 errors; fresh throwaway run 96f31923081b
  (id, task ingestion, the digest regression, host-binding facts): 134 pass, 0 fail.

The adoption's 66 s is over the ten-second bound: it is the one-time re-derivation of every stored identity
(every program row and task row is rewritten once); the next adoption converges as before.

## RESET NEEDED

After root commits B: `bin/seon start --head` (boots B on the live store; the first development adoption rewrites
every program and task row, about 66 s on the clone), then `bin/seon reset default --force` (the running JVM unlinks
`:cluster-default` and forks a fresh branch from the program entities, keeping caches; 7.3 s on the clone). Effect:
every stored identity is the structural one (definition digests, evaluation, turn, error and test-run ids, task
handles, source and schema digests, projection fingerprints); runtime tasks, agents' turns and evaluations on the
cluster branch are gone (the 56 runtime tasks worth keeping were persisted to task files earlier).

## Correction for `.agents/skills/clojure-testing/SKILL.md` §4 Example 1 (lane final-revision's file)

Its table states the printed-text behavior. The landed test is `seon.id-test/identity-is-a-function-of-the-values-structure`:

```clojure
(deftest identity-is-a-function-of-the-values-structure
  (are [a b same?] (= same? (= (id/id a) (id/id b)))
    {:a 1 :b 2} {:b 2 :a 1} true
    1           1N          true
    1           1.0         false
    [1 2]       '(1 2)      false
    nil         false       false
    'abc        "abc"       false)
  (is (= "ebc54726b35b" (id/id 'abc)) "the encoding is fixed: every stored identity was derived under it")
  (is (= "ebc54726" (id/id 'abc 8))))
```
