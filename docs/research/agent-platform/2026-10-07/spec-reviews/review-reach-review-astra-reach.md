# Review of cc6e2050d — review-astra-reach

Reviewed 2026-10-07. **Do not certify the unconditional “stale digests can never reuse green” claim.** One high-severity, pre-existing cache assumption survives this commit. The SCC implementation itself passed the bounded probes below; I found no demonstrated new SCC correctness defect.

All source line references below are to cc6e2050d; the reviewed production files matched that commit in the checkout. Read the commit, changed function bodies and reach callers, lane notes, B4 reuse authority, definition-digest producer, and the analyzer's declared-invocation path. No production files changed.

## Findings, ranked

### High — declaration digests do not identify all graph inputs (retained defect)

**Locations:** src/seon/test/runner.clj:852–865 and :1080–1085; src/seon/program.cljc:389–418; src/seon/fn.clj:1477–1503 and :2863–2895.

The cache assumes calls/references/keywords are determined by a definition digest. That is not universally true:
- Definition identity explicitly excludes calls, references and keywords (program.cljc:389–401).
- The analyzer adds calls from the declaration population (fn.clj:1492–1503).
- Its invoker-paths owner explicitly reanalyzes an unchanged invoker when a schema population retargets an invoked attribute (fn.clj:2866–2868).
- Retargeting between two functions in the same already-required namespace can leave used-resolver-context unchanged (program.cljc:412–418).

**Concrete failure scenario:** warm a test's reach-node for an invoker targeting handler A. Publish a declaration-population change targeting handler B in the same namespace, without changing the invoker's source or resolver context. Reanalysis updates its calls, but its definition digest can remain equal. reach-graph retrieves the old node under that digest, so B remains absent from the test's computed closure. A later change to B can leave the returned reach digest unchanged. If the other reuse conditions hold, select can reuse green using that stale digest (test.clj:998–1017). Choose a changed population declaration not itself named by the invoker's keyword/contract closure; the named-schema walk then supplies no independent invalidation.

The new reach-index key also drops edges and keeps leaves alone (:1082). Fixing only the node cache is insufficient when a batch's reached name set and leaves stay equal while membership edges change.

**Verified:** the source dependency and exclusions; an installed definition-digest probe returned true for equality after retargeting calls A→B with unchanged source and same-namespace resolver context (result/ebf828a1cbc6c). This was a pure producer call, not fabricated database facts.

**Unverified:** the complete publish → warm cache → selection → reused-green scenario through canonical writers. Do not describe this as a newly introduced regression: the parent already keyed reach-node this way. It nevertheless invalidates the requested unconditional correctness sign-off and the equivalence argument in tmp/no-print-hash/notes.md.

**Fix:** make both cache keys identify actual graph inputs, either using their immutable node content or a producer-owned digest that includes declaration-population-derived edges. A simple correct starting point is reading the graph facts and keying on them; optimize only after measuring. Add a warm-cache regression that retargets an invoked schema declaration through publication, then changes only B and verifies the test is selected.

### Medium — new internal contracts admit values the implementation cannot consume

**Locations:** runner.clj:891–892, :950–952, :1003.

reach-components declares vector-of-some? rather than vector-of-int-array. Its component-of output has the same non-nil-only predicate. reach-index accepts any map, without requiring nodes or schemas. Thus its central graph representation is not checked.

**Concrete examples, probed:** Malli validates [42] against the declared SCC input shape, but the exact committed SCC body throws java.lang.ClassCastException. reach-index accepts {} and returns a normal digest for x/t, treating missing graph data as a missing test. These are input/contract counterexamples, not a demonstrated current public caller supplying malformed values.

**Fix:** declare the graph/node/index members and the actual primitive-array predicate; ensure producer output validates before downstream consumption. Keep maps open. Compile against the candidate's packaged projection. Malli compilation alone does not establish completeness: m/schema and m/function-schema only compile the authored shape; m/validate enforces that shape (reference-code/malli/src/malli/core.cljc:2596, :2680, :3130; pin 43c6dca8d2d8df3162b13725939954807be4a4ed).

### Medium — the changed regression does not prove the new failure or performance class

**Locations:** test/seon/test/reach_test.clj:76–89; test/seon/test/runner_test.clj:77–95; test/seon/test_reaching_test.clj:68–138.

The added test compares tests-reaching to memberships for two existing tests and excludes self. This is useful caller-equivalence coverage. The updated work-count test warms digests before asserting zero SCC calls. It would still pass a cold implementation that repeatedly rebuilt SCCs or full closures per test. Counting retained cache entries in derived-digests also does not count derivations; repeated computation can overwrite the same key.

No added regression exercises a constructed mutual cycle, self-loop, deep chain, cyclic schema dependency, undigested member reached through a cycle, request-subset independence or permutation invariance. Existing undigested coverage tests missing digests on roots, not propagation through SCCs (test/seon/test_runner_test.clj:399–420).

**Failure scenario:** an incorrect low-link update or missing schema-extra contribution can preserve green for a cyclic/member-only dependency while the added tests pass. Reintroducing cold work per test can pass the warmed zero-call assertion.

**Fix:** one bounded graph-law regression through the public reach entrance, with canonical analyzed declarations and a closure oracle; cover these cases as rows. Add a cold deterministic work-count check that proves two graph condensation passes per batch, independent of test count, and one component-hashing pass per graph. Keep wall-clock results in profile evidence. My scratch probes support the implementation but do not replace a permanent regression.

### Low — O(V+E) is not the complete implementation bound

**Locations:** runner.clj:931, :962, :1005, :1012; claims at :889, :948–949, :1001–1002.

Tarjan traversal is linear and hashing no longer enumerates every test closure. However it sorts component members, all names/schema keys, and distinct successor/extra hashes. A single giant component or a broad fan-out pays sorting work. The whole derivation therefore includes O(V log V) plus the sum of per-component sorted outgoing/extra digest costs, as well as bytes hashed. It is not strictly O(V+E).

**Fix:** keep the straightforward deterministic sorts and state the actual bound. No measured reason here to replace them. The valuable improvement is eliminating tests × closure materialization/hashing.

### Low — source commentary still describes retired machinery and embeds profile history

**Locations:** runner.clj:755–760 still describes per-test closure fingerprints; id.clj:103–111, runner.clj:1069–1073 and test.clj:2398–2402 add measurement/history prose.

**Fix:** update the reach introduction to SCC/index behavior and keep dated measurements in the landing evidence, with short result-oriented docstrings. This is also the applicable data-oriented-clojure skill's §9 requirement.

## Correctness and algorithm assessment

Conditional on accurate graph inputs/cache keys, and the ordinary collision-resistance assumption for SHA-256:

| Case | Evidence and conclusion |
|---|---|
| Cycles and self-loops | Explicit DFS frames, Tarjan stack, discovery/low-link arrays and on-stack check (runner.clj:894–934). A completed child propagates low-link to its DFS parent; an already-active successor contributes discovery index. Components are emitted sinks first. No recursive call chain. |
| Deep graph | Exact committed body handled a 9,001-node chain (9,001 singleton components) and 9,001-node cycle (one component). Combined probe body including small digest cases: 12.769542 ms, unarmed. |
| Member-only schema keys | reach-graph gathers keys from every reached declaration (:870–872). Schema SCC hashes recursively include their outgoing components (:1005–1011). Name SCC hashes include every member's schema extras (:975, :1016–1018). Probe changed a transitive schema key in a schema cycle, named only by a reached member; only the reaching test changed. |
| Changed definition in SCC | A changed member leaf changes its component digest (:964–976), then its ancestors' hashes. Probe changed x/b in x/a↔x/b; only x/t changed. |
| Self-loop and disconnected test | Its own leaf is still hashed even when the self-edge is omitted from component successors. Probe changed x/alone; only x/alone changed. Adding unrelated requested roots cannot alter reachable SCC membership. |
| No test node | :1033 deliberately chooses no component and :983–987 hashes test-name plus newline. This is a stable name-only digest, not positive execution proof; behavior existed before. verified? additionally requires test source (test.clj:1570–1577), and selection eligibility starts from declared tests. Do not treat this digest alone as evidence. |
| Undigested declarations | Non-string declared leaves flag the component; flags propagate along condensed edges (:1021–1035). reach-digests returns unknown instead of reuse keys (:1102–1120). Probe confirmed propagation through a cycle. Missing external names remain distinguished from declared-but-undigested nodes (:1016, :1019–1026). |
| Iteration order | Names and schema keys sorted, component members sorted by positions, outgoing and extra digests sorted/deduplicated (:931, :962, :1005, :1012). Component numbering is not hashed. Reversed insertion/edge/request order produced equal outputs in one JVM. |
| Across JVMs | Source uses UTF-8, SHA-256 and stable scalar ordering, not object hashes (:959–976, :984–988). For equal graphs with equal leaves, deterministic by inspection. Separate-JVM execution **unverified**. Equality of upstream graph/leaf production is a separate prerequisite; see high finding. |
| Stale/unknown evidence | green-members requires positive completed and terminated assertions (test.clj:684–701). select requires loaded-program evidence, matching input evidence and a string current digest equal to the recorded one (:978–1017); verified? checks source and current digest (:1547–1577). These safeguards do not repair a stale reach-node cache. |

The hash is not an injective encoding of arbitrary graphs: internal SCC edges and duplicate outgoing edges are not hashed independently. For canonical fixed graph inputs, changed definition/schema leaves propagate correctly. The equivalence argument must not claim that every graph change necessarily changes a definition leaf: the high finding disproves that premise.

## Callers and retirement

Searched src, script, test and resources for removed helpers and cache kinds. No surviving executable call to reach-bits, reach-postorder, reach-closures, closure-digest or reach-entry was found. reach-entries remains a function; only its cache kind changed to reach-index.

- test.clj:2404 replaces the only production batch-membership classification with tests-reaching; :2439–2442 carries membership into member-result's effect handling. Reverse traversal at runner.clj:1152–1163 matches “target in membership excluding self,” including unresolved target symbols.
- changed-since-green still legitimately requests each test's memberships before/after (test.clj:97–98).
- Digest consumers retain the same public map/error API: select (:1006), reach-digest (:1405), verified? (:1568), run (:2307), problems/failed-tests (problems.clj:419).
- Tests naming the cache/helper were converted (runner_test.clj:88–93; test_reaching_test.clj:73). The task must-read target was converted to reach-component-digests (resources/seon/tasks/seon.id.edn:4).
- id/id's executable body is unchanged; this commit edits only its docstring.

No caller conversion defect found in that audit. The error-constructor/caller convention is the currently installed one; a separate errors cut owns its migration.

## Performance evidence and simpler alternatives

The claimed improvement is plausible: the old algorithm materialized 5,224,126 membership names; the new digest folds each condensed graph once. Removing that batch membership query from effect classification is the simplest direct optimization, and the reverse walk is appropriate.

The commit reports 2,974 ms / 3.37 GB before versus 119 ms / 194 MB graph acquisition plus 140 ms / 222 MB index work after: approximately 11.5× latency and 8× allocation reduction by those figures. These are **not controlled armed before/after measurements**: before was armed, after unarmed, and node caches were warm. The 18 ms effect-reach figure needs its own exact input/cache-state evidence; it cannot simply be substituted for the separately reported 119 ms graph acquisition. notes.md contains baseline tables/design but no saved benchmark program or complete after-run envelopes. Claimed full-suite performance, retained memory and the excluded-red classifications remain **unverified by this review**.

Do not replace iterative Tarjan with recursive DFS for cosmetic brevity. Iterative Kosaraju would require a second traversal and reverse adjacency. Keeping the current algorithm is simpler than restoring closure BitSets/fingerprints. Small optional simplifications: accumulate component undigested status alongside component hashes, and avoid deriving schema nodes for name-only tests-reaching/memberships by separating name acquisition from schema expansion within the existing owner. Neither is needed to establish SCC correctness.

## Verification, boundaries and central test request

Initial bin/seon status and MCP runtime_status both reported default faulted: 10 open faults, 105 failed tests and 43 unknown tests. Runtime source archive was a8e3bde5b475e54fead8ed107a86d67d47d10a13. JVM resolve probe result/efe57612d41ee found reach-components, reach-index and tests-reaching absent and reach-closures present. **The target is not installed.**

Read-only/local-value JVM probes explicitly used agent root, cluster default. No test runs, restart, source reload, publication, adoption, shared Var replacement, worktree, commit or other lane communication. The first two probes use local function values extracted from cc6e2050d, not installed production Vars. Exact forms: [probe evidence](review-reach-review-astra-reach-probes.clj). Full returned envelopes: [results](review-reach-review-astra-reach-evidence.edn). These envelopes showed values/result handles but no separate tool timing/call/fault fields; reported timings are explicit body timers. No allocation measurement was taken.

Foreign boundary: dirty AGENTS/skills/spec/reference documentation, test/my/examples_test.clj, tmp/test-data-assertions/proofs.edn, reference-code/http-kit and unrelated untracked paths were present. They were untouched and did not stop this review. Errors-s1 held several dependency owners; no edits or requests were sent to that lane.

**Not run:** candidate archive lint, packaged contract compilation, installed/armed target tests, multi-JVM determinism or publication/adoption proof. This is a report-only review, with no commit attempted. The evidence script passed the edit hook's error-level lint after adding its explicit requires; its two unused bindings and shadowed cycle name remain verbatim probe evidence. Whole-repository lint and contract-compile requirements still apply before an implementation commit.

Root's exact focused test list (existing tests; none executed here):

- seon.test.reach-test/reach-is-the-closure-of-each-rows-own-facts
- seon.test.reach-test/a-digest-follows-its-closures-content-and-nothing-else
- seon.test.reach-test/tests-reaching-answers-the-tests-whose-closure-holds-the-target
- seon.test.runner-test/reach-digests-and-memberships-share-content-valid-work
- seon.test-reaching-test/reach-digests-follow-only-changed-closures
- seon.test-reaching-test/concurrent-reach-reads-of-one-value-derive-once
- seon.test-reaching-test/a-fresh-branch-with-unchanged-program-entities-derives-nothing
- seon.test-runner-test/a-metadata-only-change-changes-the-reach-digest
- seon.test-runner-test/undigested-declarations-never-share-a-reuse-key
- seon.test.selection-test/named-selection-reuses-green-members-by-reachable-content
- seon.test.one-request-test/evidence-earned-by-another-loaded-program-is-never-reused
- seon.test-failure-facts-test/recorded-reach-belongs-to-the-tested-value-and-is-replaced
- seon.test-failure-facts-test/a-red-counts-only-while-the-program-it-ran-on-is-current

Required new regressions, **proposed names, not installed**:
- seon.test.reach-test/retargeted-invocations-invalidate-warm-reach-and-green
- seon.test.reach-test/component-digests-follow-exact-closures

Commit size from git show --numstat: source +56 net lines, tests +13, task data net zero. This review adds only owned tmp report/evidence files.

