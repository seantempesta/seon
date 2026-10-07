# Review of cc6e2050d (reach digests per call-graph component) — lane reach-review (Opus)

Method: read the commit and every touched function in full (src/seon/test/runner.clj:755-1165 at
cc6e2050d) and its callers (src/seon/test.clj:97-98, 2404; runner.clj:1697, 1964). Default runs a
pre-commit runner (`reach-component-digests` unresolved), so the committed functions were loaded
into a disposable scratch namespace (`scratch.reach-review`, built from HEAD's text with `::` keys
rewritten to `:seon.test.runner/…`; tmp/reach-review/scratch.clj) and run, unarmed, on default's
real graph: 2,365 tests, 9,165 names, 6,834 schema keys.

## Findings

### MEDIUM 1 — undigested refusal is tests × closure again (FIXED)
runner.clj:1103-1120 (cc6e2050d): when any test is undigested, `reach-digests` builds its
message by walking `reach-names` once per undigested test and sorting each closure. One
undigested hub is enough: marking `seon.db/db`'s leaf unknown on default's graph made 2,111 tests
undigested and the message took **5,869 ms** — the exact per-test-closure class the commit removes,
on a path reached by every `reach-digests` caller (commit-results!, record, reuse checks) whenever
a declaration lacks its digest. Fix: `reach-names` takes a collection of starts; one walk from all
undigested tests collects the undigested declarations (sorted set). Same message shape, same
`:seon.error/offending` (sorted tests). Measured on the same graph: **17 ms**.

### MEDIUM 2 — "the only multi-test memberships caller" is false (NOT FIXED: owner design)
`commit-results!` (runner.clj:1964) and the record path (runner.clj:1697) still call
`reach-memberships` with every result of a run, because each test's closure is stored as
`:seon.test/reach` datoms (consumers: seon.fn:2087, my.program:298, seon.test:729). For a full run
that is the commit's own measured 5.2 M names, 3.5 s / 9.3 GB unarmed. Before, memberships shared
the cached `::reach-entries` derivation with `reach-digests`; now `reach-memberships` is uncached
and calls `reach-graph` a second time. Not a regression in total cost (old: 7.8 s armed), but the
commit message misstates it. Fixing it means deciding whether stored per-test closure membership
stays (storage: up to 5.2 M datoms); existing task `reach-over-every-test-takes-thirty-seconds`
(resources/seon/tasks/seon.test.runner.edn:18) owns it. Owner decision.

### LOW 3 — `[:maybe]` in a new contract (FIXED)
`test-reach-digest` declared `[:maybe [:string …]]` (runner.clj:982); the caller passed nil for a
test with no entity. Now it takes `:string` and the caller passes `""` — `(str nil)` was already
`""`, so digests are byte-identical.

### LOW 4 — reach-unavailable names the wrong operation (FIXED)
`reach-unavailable` stamped `seon.test.runner/reach-entries` on refusals from
`reach-memberships` and `tests-reaching`. It now takes the failing operation.

### LOW 5 — stale section comment (FIXED)
runner.clj:756-760 still said each test's digest is cached under its closure's fingerprint (the
retired `::reach-digest` kind). Rewritten.

### LOW 6 — missing class regressions (FIXED)
No test covered a cycle or the property reuse depends on: a digest recorded by a one-test run must
equal the digest a many-test request computes. Added
`seon.test-reaching-test/a-reach-digest-is-its-closures-alone-across-cycles` (c ↔ d mutual
recursion through the production analyzer; asserts alone = together, a change inside the cycle
changes the test's digest, an unrelated test keeps its own).

### LOW 7 (not fixed, noted) — runner_test's "never rebuild" assertion is partly vacuous
runner_test.clj:84-94 counts armed `reach-components` calls during a second digest+membership read.
`reach-memberships` no longer touches `reach-components`, so only the digest half proves anything.
Harmless.

### Pre-existing, not introduced here
- Cache key of `::reach-index` (runner.clj:1081) holds leaves, not edges. Sound: a digested node's
  edges are cached under its definition digest by `::reach-node` already (reach-graph docstring
  claims the digest covers source and resolver context; not re-verified here), and an undigested
  node's edge changes only touch components whose tests are all undigested.
- Host names (no entity) contribute a constant `:seon.error/unknown` leaf, so dependency upgrades
  never change a digest — unchanged from before.

## Verified (no defect)

1. **Correctness, iff.** On default's graph: perturbing the leaf of `my.agent/retire-branch!` (in
   the largest SCC, 121 members) changed exactly the 1,553 tests whose closure holds it; perturbing
   `:my.background/error` (a schema key reached only through another schema key) changed exactly
   the 2,114 tests whose schema closure holds it; making the same node undigested listed exactly
   those 1,553 tests undigested. A test with no node gets digest(name, "") as before.
   Undigested propagation (runner.clj:1021-1031) relies on Tarjan's sinks-first order: d < c is
   guaranteed (verified below), so `(nth flags d)` is always defined.
2. **SCC algorithm.** Iterative (explicit frames), no recursion: a 200,000-vertex chain and a
   200,000-vertex cycle both complete. Against brute-force mutual reachability on 1,000 random
   graphs (1-40 vertices, 0-4 edges each, self-loops and duplicate edges included): partition
   correct, SCCs exact, every edge points to the same or an earlier component. Determinism:
   positions come from sorted names, members are sorted, reached and extra digests are sorted
   sets, all text is `str` of symbols/keywords/hex — no hash-order dependence. Request
   independence: for 40 random tests, the digest asked alone equals the digest in the all-tests
   index (true), because a component and everything it reaches lie inside the test's closure.
   Default: 8,814 components, 55 multi-member, largest 121, no test inside a cycle.
3. **Callers.** `reach-memberships` callers (seon.test:97-98 per test; runner:1697, 1964 all
   results) keep their shape; effect-reach converted to `tests-reaching`. `tests-reaching` equals
   the memberships answer on default for `seon.effect/request*` (1,596 tests, identical sets).
   `::reach-digest` / `::reach-entries` no longer referenced in src/test/resources; test_reaching_test
   reads `::reach-index`. Task `reach-entries-fill-half-the-cache-at-half-hit-rate` still names the
   old kind (root's index; not edited).
4. **Numbers.** Re-measured unarmed on default (warm node cache): reach-graph 41 ms / 58 MB,
   reach-index 92-96 ms / 221 MB for all 2,365 tests (commit: 119 ms / 194 MB and 140 ms / 222 MB);
   one test 40 ms / 90 MB. Plausible. Armed numbers still need root's restart.
5. **Simpler?** Condensation is the minimum for a Merkle digest over a cyclic graph; nothing
   smaller found. `tests-reaching` is one reverse walk — simplest form.

## Fix commit
See the landing line below (sha filled after `bin/lane commit`).
