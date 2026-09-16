---
type: issue
status: open
severity: cleanup
tags: [issue, bootstrap, turn, class/dead-mechanism, wave/generated-receipts]
---

# seon.bootstrap/next-entry has no caller in src/

## What was observed

`6aca09cce` (2026-09-09, "Use the system-turn generator for seeded agent
openings") replaced `seon.turn/generate-turn`'s call to
`seon.bootstrap/next-entry` with the shared system-turn source generator
(`declared-sources` + `system-plan`, `src/seon/turn.clj:4867-4875`). The
function and its private derivation `next-entry-in`
(`src/seon/bootstrap.clj:628`, `src/seon/bootstrap.clj:701`) survived the
change with no caller anywhere under `src/`:

```
$ rg -n "next-entry" src/ test/
src/seon/bootstrap.clj:628  (defn- next-entry-in
src/seon/bootstrap.clj:701  (defn next-entry
src/seon/bootstrap.clj:711     #(next-entry-in request run-id))))
test/seon/bootstrap_test.clj:96,98,136,137,154,156,157,158,424
test/seon/cluster/bootstrap_resume_child.clj:12
test/seon/cluster/agent_test.clj:1692
```

Only tests reach it, and they keep it green — a second derivation path for
the generated opening, maintained by its own regressions, which no cluster
ever executes (AGENTS.md §2.5).

## Why it matters

It already cost a red. `test/seon/cluster/bootstrap_resume_child.clj` paused
the JVM-kill drill by redefining `next-entry`; after `6aca09cce` nothing
called the redefinition, the child never printed its derivation boundary, and
`a-generated-prefix-resumes-on-the-same-run-after-jvm-kill` spent its 60 s
bound waiting for a line that could not arrive (batches 68-94; repaired by
moving the drill onto `seon.turn/generate-turn`, 2026-09-16,
`docs/prds/context-generation/research/boot-test-residue-2026-09-16.md`).

## The fix, when in scope

Delete `next-entry`, `next-entry-in`, and the regressions that exist only to
exercise them (`test/seon/bootstrap_test.clj`'s next-entry assertions, the
`agent_test.clj:1692` redefinition), in one slice. Git is the archive. The
generated-opening behavior those tests claim to protect is owned by
`seon.turn/generate-turn` and the system-turn source generator, which have
their own coverage.
