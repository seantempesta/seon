---
type: landing
status: committed 729d50e4b c75013752, code only (owner load rule); unadopted; no test request run
lane: fix-one-request-reuse-and-hold
created: 2026-09-25
prior: lane-harness-s2-overlay-s2c-2026-09-24.md (fifth and sixth follow-ups, :87/:203 notes)
---

# Lane fix-one-request-reuse-and-hold (2026-09-25)

What exists: `seon.test/run` builds each request's clean world
(`program-overlay-tx` onto the published base plus boot's facts), and `admit-run`
reserves members at the writer. It holds any member another admitted run
still holds. Both reds were producer defects in those two functions, not in
the reuse rule.

## Broken or unproven first

- **Nothing was loaded or run through `seon.test/run`** (load rule). Both edits lint clean.
  - The overlay change ran from its committed form in a throwaway JVM namespace against
    default's store (since removed).
  - The admission change is proven only by reading and by timing its two queries on
    default. Its `admit-run` has not executed.
- **Holding is now effective, so a lost exit record holds a test for the JVM's life.**
  - Since f3a4b33d9 no body could be held across commits, so a member whose `settle!`
    never recorded `terminated-tx` was simply run again.
  - Now that test reads pending until the JVM restarts.
  - That is the declared rule. But fix-unstarted-members-unknown saw a possible lost
    `terminated-tx` after a stopped-unfinished member, and it is still undiagnosed.
- The overlay carries attributes the world **lacks**. An attribute whose declaration differs
  between world and candidate (for example a changed cardinality) is not carried.

## Causes (read from default's recorded failures of root's run at 00:13Z)

1. **one_request_test.clj:96.**
   - The first request's members were red: line 77 shows executed 2, fail 2. Their `db/q`
     was refused with "cannot read uninstalled attribute :seon.test.one-request-test/marker"
     (`:seon.db/installed-declaration nil`).
   - The fixture installs that raw attribute on the execution branch only. The clean world
     carries program-partition rows, so it never had the attribute, and a red member is never
     reused.
   - Digests, basis and loaded commit were not the difference.
2. **one_request_test.clj:202-206.**
   - f3a4b33d9 made `runner/program-digest` the digest of the tested commit.
   - The fixture's `record! false` is a new commit, so the request's program digest differs
     from the live body's run.
   - `admit-run`'s holder query required equal `:seon.test.run/program-digest` and
     `:seon.test.run/input-digest`, so it never found that run. It executed the test beside
     the live body.
   - :207 then reused that green, which is why it reported executed 0 and reused 1.
   - cb632bd81 (the stopped-run rule) is not involved.

## Changes

- **729d50e4b** `program-overlay-tx` puts first every installed attribute declaration that
  the candidate holds and the world lacks. It returns only those attributes when the programs
  are equal. (+13/−3)
- **c75013752** `admit-run`: a live body holds its test in any scope of its cluster. A live
  body is a member with `completed-tx` and no `terminated-tx`, in a run admitted since this
  JVM started. Unstarted coverage still needs an equal `admission-scope`. (+21)
- Net: +34/−3 in `src/seon/test.clj`. The test file is unchanged: its two tests are the class
  regressions.

## Evidence (default pid 57919, JVM mode)

| probe | result |
|---|---|
| keyword attributes: live default against its adopted base 6ab5bf39 | 2,452 each, 0 extra, 0.8 ms |
| overlay onto base, candidate = default + raw marker attribute (`db-with`) | 16 entries (the attribute first, then default's 15). A marker write on the overlaid world reads back `["a"]` |
| overlay, candidate = live default: HEAD form against the new form, 5 runs each | 46/33/32/34/34 ms, then 35/34/33/33/33 ms. Unchanged, 15 entries |
| live-holder queries (28 runs since JVM start, 60 symbols) | 11.8 ms cold, 1.3 ms warm |
| old holder queries | about 2.7 ms |

The per-admission cost rises by at most about 12 ms. The admission phase measured 354 ms in
S2's phase table.

## Root runs (serially, after adopting 729d50e4b and c75013752)

1. `bin/test-check default --test seon.test.one-request-test/one-request-runs-each-member-as-an-isolated-agent --include-long`
2. `bin/test-check default --test seon.test.one-request-test/an-unexited-member-is-never-started-again-until-its-exit-is-recorded --include-long`
3. `bin/test-check default --ns seon.test.one-request-test --include-long`
4. `bin/test-check default --ns seon.test.overlay-world-test --include-long`: overlay regressions
5. `bin/test-check default --ns seon.test.interrupted-request-test --include-long`: stopped-run coverage

RESET NEEDED: no. Released paths: `src/seon/test.clj`, this note. `src/seon/test/runner.clj` and
`test/seon/test/one_request_test.clj` were not edited.
