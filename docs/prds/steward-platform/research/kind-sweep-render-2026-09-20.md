---
type: research
status: active
tags: [render, error, contracts]
---

# Render-family kind retirement — 2026-09-20

Work in progress; no completion or green claim. The five source files and
`resources/seon/schemas/seon.render.edn` contain uncommitted partial edits.
Producer and consumer changes must land together; these edits are not a
landing-ready checkpoint.

## Grounding

Read the supplied AGENTS.md sections 0–5, namespace-agents plan section 8,
inventory replacement actions R1–R8 and the five owned source sections,
and error-family landing note Step 6 / D13 continuations. Read the base and
component declarations and the error PRD render manifest. Used the
data-oriented-clojure, datastar-web-ui, clojure-testing and repl skills.

Dependency ledger: the existing `seon.error/project-observation`
(`src/seon/error.clj:582`) owns evidence projection and calls its bounded
admission seam (`:405`), which consumes supplied SCI admission caps. The
render data cursor (`src/seon/render/data.clj:43`) accepts arbitrary values;
its regression explicitly navigates an infinite sequence
(`test/seon/render/data_test.clj:50`). New evidence serialization cannot
assume those roots are finite.

## Owner decision pending

AGENTS.md section 2.5 requires the owner design gate before a cross-owner
change. The new `:seon.render.data/error` requires an error-root projection
and requested-location, while `at` receives only a value and cursor, without
caps. An asynchronous question presented these options:

1. Recommended: carry evidence caps in the request/cursor and use the
   existing projector. Guarantees bounded evidence; costs caller changes,
   including held/out-of-scope `src/seon/cluster.clj:540`.
2. Record only root identity/type and path. Preserves the API and avoids
   realizing arbitrary values; gives up the failed root value as evidence.
3. Add a declared default evidence policy here. Preserves callers; adds
   policy acquisition at a boundary that currently carries none.

No option has been selected. No independent clipping helper or new default
bound has been introduced.

## Interim source census

Point-in-time literal occurrence counts (not completion):

| File | Before | Remaining |
|---|---:|---:|
| src/seon/render.clj | 37 | 9 |
| src/seon/render/transcript.clj | 50 | 8 |
| src/seon/render/web.clj | 38 | 9 |
| src/seon/render/walk.clj | 10 | 3 |
| src/seon/render/data.clj | 8 | 2 |

Straightforward consumers inspect all three base members inline. There is
no new general predicate. The invalid-output producers now supply the base
and retain the concrete requested output; their facet is
`:seon.render/invalid-output-error`. All other producer facets and test
conversions remain unfinished.

## Verification

Read-only MCP runtime status observed default pid 41822 alive with replying
procs. A read-only JVM probe reproduced the old missing-path observation.
Default lifecycle and loaded definitions were not changed by this lane.

The five-namespace load command returned `:loads` after the initial consumer
edits, before the later invalid-output contract edit. It is not proof of
final HEAD or adoption.

The requested existing test namespaces are `seon.render.transcript-test`,
`seon.render.web-test`, `seon.render.walk-test`, and
`seon.render.data-test`. `test/seon/render_test.clj` does not exist.

Main-tree fast overlay exited 64 before tests: required dirty callers
`src/seon/db.clj`, `test/seon/error_test.clj`, and
`test/seon/schema_test.clj` are held by another lane. No held file was added.
The prescribed detached HEAD worktree at bcb0ef256 excluded those edits;
its first overlay exited 64 because that worktree lacked a published base.
Linking the existing `target/test-published-bases` cache allowed admission.
The subsequent run acquired its packaged projection and armed 1,312
contracts, then began the first transcript test. The lane deliberately
terminated its own test JVM (pid 41067) while awaiting the owner decision;
the launcher exited 143. No test/assertion tally was emitted. This was an
interrupted diagnostic run, not a test failure attribution or a green claim.

## Cross-file needs and proof owed

- `src/seon/cluster.clj:540`: data cursor evidence policy, pending decision.
- `src/seon/sci/reader.cljc:11,623`: reader observation still stamps the old
  classification; the transcript consumer needs the declared reply-phase
  evidence, not another boolean marker.
- `src/seon/db.clj:4268`: unknown write completion still uses the old stamp
  and boolean. The web HTTP status consumer is being moved to the declared
  write-attempt completion-unavailable observation.
- Callees still declaring generic error values need their owning later
  sweeps; no foreign source or test was edited.

Cold gate and platform proof remain owed by the orchestrator, followed by
live adoption and browser observation. No cold gate or lifecycle command
was run by this lane.

## Cleanup

The owned diagnostic JVM and launcher exited. The scratch worktree was
removed after copying nothing over the main draft. All six source/schema
drafts remain in the main working tree; unrelated edits were preserved.
