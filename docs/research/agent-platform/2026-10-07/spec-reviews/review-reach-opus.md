# Review of cc6e2050d (reach digests per call-graph component) — lane reach-review (Opus)

Method: I read the commit and every function it touches in full (src/seon/test/runner.clj:755-1165 at
cc6e2050d), plus their callers (src/seon/test.clj:97-98, 2404; runner.clj:1697, 1964). Default
still runs the runner from before the commit (`reach-component-digests` is unresolved). The
committed functions, and then the fixed ones, were therefore loaded into disposable scratch
namespaces built from the source text, with `::` keys rewritten and cache kinds renamed. They ran
unarmed on default's real graph: 2,365 tests, 9,165 names, 104,761 edges and 6,834 schema keys.
Astra's review (review-reach-review-astra-reach.md) was checked finding by finding. The results
are marked [A] below.

Fixes landed in one commit, named in the Landing section. Root's commit message gives the sha.

## Findings

### HIGH 1 [A, confirmed and widened] — a reach node keyed by the definition digest keeps stale edges (FIXED)

`reach-graph` cached each node (its calls, references and keywords) under
`:seon.program/definition-digest` (runner.clj:852-866). The `::reach-index` cache key also kept the
leaves and dropped the edges (runner.clj:1082). The definition digest excludes calls, references
and keywords (program.cljc:389-401). It keeps only the resolver context of the namespaces those
calls name (program.cljc:404-418). A call can therefore change while the digest stays the same.
Two cases were reproduced at the REPL:
- Astra's case: the declaration population retargets an invoker to another handler in the same
  namespace (fn.clj:1492-1503, 2866-2868). `definition-digest` returned equal digests for calls
  `reach-entries` and `reach-graph` under one resolver context. A cross-namespace retarget did
  change the digest.
- A more common case: a forward reference resolves later. `(defn c … (d …))` was analyzed before
  and after `d` existed. Its calls gained `reach.cycle/d`, and its definition digest was unchanged.
End to end on default's value: I added a call edge to
`seon.test.reach-test/reach-is-the-closure-of-each-rows-own-facts` with `d/with`. The committed
code then returned the same reach digest as before (61f66b7a…), so stale green could be reused.
The fixed code returns a different digest.

Fix: `reach-facts` reads each reach attribute in bulk: calls, references, keywords, spec and
subject. These reads go through the per-commit `::reach-declarations` cache. Each node is cached
under its whole row (digest plus graph facts), and `::reach-index` is keyed by the nodes whole,
edges included. On default the fixed code gives every one of the 2,365 digests identical to the
committed code, so the edges themselves were the same and nothing else moved.

Cost, measured on default and unarmed:
- The facts read is 52 ms per new commit, against the 22 ms declaration read it extends.
- All tests: 75 ms warm and 476 ms cold, with every node cache missing.
- One test: 58 ms.
- An as-of value is never cached: its declaration read rose from 65 ms to 370 ms. This affects
  changed-names (seon.test:96) and the record fallback (runner.clj:1713). The common record path
  passes the committed tested value.

A producer-owned alternative would avoid the per-commit read: put the declaration-population and
resolved-call edges into the definition digest, or into a sibling edge digest. It changes identity
semantics in program.cljc and fn.clj, so it is left as an owner decision.

### MEDIUM 2 — an undigested refusal costs tests × closure again (FIXED)

When any test is undigested, `reach-digests` built its message by walking and sorting one closure
per undigested test (runner.clj:1103-1120). I marked one hub, `seon.db/db`, as undigested: 2,111
tests became undigested and the message took **5,869 ms**. Fix: `reach-names` takes many starts, and
one walk from all undigested tests collects the undigested declarations. The refusal has the same
shape and the same sorted `:seon.error/offending`. It now takes **17 ms**.

### MEDIUM 3 [A] — the contracts were too loose (PARTLY FIXED)

- Fixed: `reach-index` now declares its input as `{::nodes, ::schemas}` maps and its output as
  `{::digests, ::undigested}`. `reach-graph` declares its output the same way.
- Fixed: `test-reach-digest` used `[:maybe …]`, which is against the schema rules. It now takes
  `:string`, and the caller passes `""`. `(str nil)` was already `""`, so the digests are
  byte-identical.
- Not fixed: `reach-components` and `reach-component-digests` still declare their int-array
  vectors as `[:fn clojure.core/some?]`. Clojure has no named int-array predicate. The honest shape
  needs a registered schema in the schema resources, which means publication. Left for the schema
  owner.

### MEDIUM 4 — "the only multi-test memberships caller" is false (NOT FIXED: owner design)

`commit-results!` (runner.clj:1964) and the record path (runner.clj:1697) still call
`reach-memberships` with every result of a run. The reason is that each test's closure is stored
as `:seon.test/reach` datoms, read by seon.fn:2087, my.program:298 and seon.test:729. For a full
run that is the commit's own measured figure: 5.2 M names, 3.5 s and 9.3 GB unarmed. Before the
commit, memberships shared the cached derivation with digests. Total cost has not regressed, but
the message misstates it. Task `reach-over-every-test-takes-thirty-seconds` already owns this
(resources/seon/tasks/seon.test.runner.edn:18). Whether per-test closure membership should stay
stored is an owner decision.

### MEDIUM 5 [A] — the regressions did not prove the class (PARTLY FIXED)

Added, both through the public entrance with analyzed declarations:
- `seon.test-reaching-test/a-call-edge-changed-under-an-unchanged-definition-digest-changes-the-reach`
  checks finding 1. It warms the cache, adds a call edge, asserts the definition digest is
  unchanged, the membership is there and the digest has changed. It then changes the newly reached
  function and asserts the digest changes again.
- `seon.test-reaching-test/a-reach-digest-is-its-closures-alone-across-cycles` uses mutual
  recursion `c ↔ d`. It asserts that a digest asked alone equals the digest asked beside another
  test, that a change inside the cycle changes the digest, and that an unrelated test keeps its
  digest.

Not added: a law table with self-loop, deep-chain, schema-cycle, undigested-through-cycle and
permutation rows, and a cold work-count check. These are proven here only by REPL probes (listed
under Verified). Recommended as the next test slice.

### LOW 6 [A] — the cost notes understated the bound (FIXED)

The Costs notes now name the sorts: component members, distinct edge digests, names and schema
keys.

### LOW 7 — `reach-unavailable` named the wrong operation (FIXED)

Refusals from `reach-memberships` and `tests-reaching` were stamped `reach-entries`. The function
now takes the operation.

### LOW 8 [A] — stale comment on the section (FIXED)

The comment at runner.clj:756-760 still described per-test fingerprint caching. It now describes
graph-fact keys and the index.

### LOW 9 (noted) — the "never rebuild" check in runner_test is partly vacuous

runner_test.clj:84-94 counts calls to `reach-components`, which `reach-memberships` no longer
makes, so only the digest half proves anything. Astra's cold work-count check would replace it.

## Verified (no defect)

- **Iff, on the real graph:** each perturbation changed exactly the expected tests.
  - The leaf of `my.agent/retire-branch!`, in the largest SCC (121 members), changed exactly the
    1,553 tests whose closure holds it.
  - The leaf of `:my.background/error`, a schema key reached only through another schema key,
    changed exactly the 2,114 tests whose schema closure holds it.
  - Marking that same node undigested listed exactly those 1,553 tests as undigested.
- **Tarjan.**
  - It is iterative: a 200,000-vertex chain and a 200,000-vertex cycle both complete.
  - Against brute-force mutual reachability on 1,000 random graphs (1-40 vertices, with self-loops
    and duplicate edges), the partition is exact.
  - Components come out sinks first, so every edge points to the same or an earlier component, and
    `(nth flags d)` / `(nth digests d)` are always defined.
- **Determinism and request independence.**
  - The hashed text uses only sorted names, sorted members and sorted digest sets: no hash order
    and no component numbering. Running in separate JVMs was not tested; this rests on inspection.
  - For 40 random tests, the digest asked alone equals the one in the all-tests index. Default has
    8,814 components; 55 have more than one member, and no test sits inside a cycle.
- **Callers.**
  - `tests-reaching` gives the same 1,596 tests as memberships for `seon.effect/request*`.
  - No reference to a retired helper or cache kind is left in src, test or resources.
  - Task `reach-entries-fill-half-the-cache-at-half-hit-rate` still names the old cache kind. It is
    in root's index, so I did not edit it.
- **Numbers.** Unarmed, with the node cache warm: graph 41 ms / 58 MB and index 92-96 ms / 221 MB
  for all tests. These are plausible against the commit's 119 + 140 ms.
- **Simpler?** A Merkle digest over a cyclic graph needs the graph condensed into its components.
  Iterative Tarjan is that condensation, done once. `tests-reaching` is a single reverse walk.
  Nothing smaller was found.

## Landing

One commit through `bin/lane commit reach-review` touches src/seon/test/runner.clj,
test/seon/test_reaching_test.clj and this report. It ran 48 named tests from seon.test-reaching-test,
seon.test-runner-test, seon.test.reach-test, seon.test.runner-test,
seon.test.one-request-test/evidence-earned-by-another-loaded-program-is-never-reused and
seon.test-failure-facts-test (recorded-reach-belongs-to-the-tested-value-and-is-replaced,
a-red-counts-only-while-the-program-it-ran-on-is-current). Four tests were excluded because they
are red on HEAD with identical failures:
- seon.test-reaching-test/agent-admitted-tests-reach-their-tested-function
- seon.test-reaching-test/run-carries-the-connections-projection-to-the-test-thread
- seon.test.runner-test/the-deliberate-failure-fixtures-declare-their-exclusion
- seon.test.selection-test/named-selection-reuses-green-members-by-reachable-content (red on HEAD
  8e42e0889, check run b25ab5e8f253)

`a-red-counts-only…` once went over its 5 s bound (7.3 s) while two other check JVMs were running.
Alone on the candidate it passed in check run 4e95746ebc14. Platform tests were not run. The armed
numbers need root's restart.
