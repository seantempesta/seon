---
type: research
status: reviewed with two amendments
created: 2026-09-17
tags: [review, orchestrator, testing, stage0]
---

# Orchestrator review — test-system stage 0 design (`a1e0738f3`)

Read in full (523 lines). The note read every line it was assigned, names
`clojure.test`'s own report vocabulary, and grounds every claim with a line.

**Accepted as written.**
- The function inventory and deletion stages (§1): one owner per concern,
  duplicates named with lines, deleted in the stage that replaces them.
- The contradictions found (§1, "Contradictions with principles"): three
  different selectors giving different answers to the same request; results
  replacing facts on the test entity so a historical run cannot recover its
  result; `gate-set` still admitting `:seon.test/subject` (parent S1 removes
  it); implicit `default` in `check` and its refusal in `record!`; the
  coordinator's tally not counting as `clojure.test` does. Each is a real
  defect and the stages address each.
- Selection over one immutable database value with the previous completed
  run's tested basis as the default change basis; open runs are obligations,
  not a baseline; missing call facts are unknown, never "no test needed".
- The classpath derivation reusing `dev_cache`'s existing tools.build basis
  (§3) rather than a second list.
- The typed refusals (§4) and the line-by-line disposition of `bin/test` (§5).
- Recommendations: claims per namespace; the global answer is a query across
  named clusters; the platform tier stays a declared fact.

**Amendment 1 — report facts, not one entity per assertion.** §2 proposes
`:seon.test.member/reports` as immutable components, one per `clojure.test`
report including every `:pass`. That is a new entity per assertion per run,
which is the unlimited-growth shape the owner ruled out on 2026-09-17 ("not
generating unlimited growth of crap but only accumulating new important info
or incrementing counters"). The note itself flags the cost as unmeasured.
Ruling for stage 1 and 3: per member, store the COUNTS (pass, fail, error,
begin/end presence) as attributes on the member, and store a report entity
only for `:fail` and `:error`, keyed by its signature so an identical failure
on re-run upserts rather than duplicates (the failure component already has a
signature). The historical result of a run is then the member's counts plus
its failure reports, which answers the P2 concern without a pass entity.

**Amendment 2 — the platform declaration stays a marker with a reason.** The
note proposes `:seon.test/platform` as a string reason. Acceptable, provided
it is the SAME attribute the tier already reads (no second marker) and its
absence means "not platform".

**The decision only the owner can make (§6, worker custody).** A Datahike
connection is a JVM object; an isolated worker cannot hold the named
cluster's connection. The note's recommended constraint: workers execute
against an immutable snapshot of the named cluster's program and database in
their isolated stores, while claims and result recording go to the cluster's
holding JVM through the existing bounded operator transport. E1's "tests run
on the cluster and record to it" is then satisfied for recording, and
"ran on" is an explicitly recorded snapshot of that cluster. Tests that must
see live concurrent writes stay in-process. I recommend accepting this
constraint; the alternative (a remote custody protocol for reads, writes,
temporal values and listeners) is a project of its own.

**Rejected.** Nothing else.

**Next.** Stage 1 may start on the owner's answers to §6 with the two
amendments applied; stage 3 waits on the custody answer.

## Addendum — `bb6673af4` (test-preparation-costs, step 1) reviewed (orchestrator, 2026-09-16 23:35Z)

Read: the diff to `src/seon/test/arm.clj` and `src/seon/test/runner.clj`, the
regression, and the landing note. **Approved.** The worker primes the
canonical fixture base before announcing readiness and reports the
preparation as its own typed line; first database tasks went from 16.4–17.1 s
to 0.8–1.4 s; the packaged projection acquired for priming is the one handed
to arming (one value, §2.1). Two side findings filed as issues: the
publication JVM idles ≥ 39 s at exit on non-daemon agent threads
(`shutdown-agents` missing) and the coordinator's "building the program
graph" label actually spans worker-initialization joins — the coordinator
already reads the published manifest (since `45e5c6c56`), so step 3 is
dissolved. Step 2 (publication reuse) is a design choice: the lane's option
1 — clone the compatible immutable base and drive the existing incremental
publisher — is the one consistent with "publication derives from the nearest
base plus changed files"; approved to implement, with the `shutdown-agents`
exit fix as a separate one-line commit first.
