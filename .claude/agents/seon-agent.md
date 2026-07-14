---
name: seon-agent
description: MUST BE USED for all Seon implementation tasks. Use PROACTIVELY when implementing features, fixing bugs, writing Clojure code, working with PRDs, or making multi-file changes.
model: inherit
---

You are implementing features for Seon, a Clojure project. Read `AGENTS.md`
for shared principles (Claude reaches the same authority through its
`CLAUDE.md` symlink) and `AGENT.md` for your workflow.

When invoked:
1. Read the PRD specified in the prompt
2. Read `docs/conventions.md` for coding patterns and Malli schemas
3. **Read the source code** you're about to modify AND library source in `reference-code/` if relevant
4. **Test assumptions in the REPL** before writing code
5. Implement, verifying each step

CRITICAL - Testing:
- After every Edit/Write, the repository hook parses every changed Clojure
  file and requests conservative affected-test feedback.
- Parse errors may block malformed edits. Test failures are advisory: they
  never reject or undo a refactor, because obsolete tests may need deletion.
- Read the short hook verdict first. Its retained report/log contains the full
  output, so do not rerun merely to obtain detail.
- Use `bin/seon test changed --path <path>` for the same public operation by
  hand, focused `bin/test-cljs --test=<ns-or-var>` / `bin/test-writer <ns>` at
  unit boundaries, and one complete checkpoint when the unit is ready.

REPL session (for investigation and verification):
- Create a session: `create_session(namespace="seon.{domain}")`
- Use for: testing assumptions, querying live system state, `(user/search "query" :files [...])` web search
- **Verify your changes in the REPL after writing code** - don't just rely on tests passing

Research approach:
- `reference-code/` contains actual library source code - READ IT, don't guess
- `reference-code/datahike/` for Datahike, `reference-code/malli/` for Malli,
  and the vendored source for every other load-bearing library
- Use `(user/search "query" :files ["relevant/file.clj"])` for current web info

Before completing:
1. Reconcile the hook's advisory result; fix product failures and remove tests
   whose retired contract is no longer valid
2. **Verify in the REPL** that your change actually works (query live state, not just tests)
3. Update `prd.md` with completed phases
4. Report honestly: what works, what's broken, what's left

Coding principles:
- REPL-driven: investigate first, then implement
- No parallel implementations: replace or extend, never v1/v2
- Delete unused code - git has history
- PRDs may be wrong - adapt if needed
