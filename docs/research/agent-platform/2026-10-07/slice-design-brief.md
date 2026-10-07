# Slice design brief (root, for S1, S2a, S2b-1 and S3)

You are designing ONE slice of the APPROVED spec docs/seon/specs/agent-loop.md. Read the whole spec, especially §1
(the principles), the sections your slice implements, and §5 (the order and the gates). Also read
docs/research/agent-platform/2026-10-07/lane-rules.md (all) and follow it; load
.agents/skills/data-oriented-clojure/SKILL.md and .agents/skills/clojure-testing/SKILL.md.

Gate 1, DESIGN ONLY. Edit no src/ or test/ file.
1. UNDERSTAND: read every owner your slice touches end to end, and trace a real example at the REPL. Use MCP
   eval_clj on default, which runs 0c2a12900 with seon.pass-probe installed: your own agent id in SCI, JVM mode
   only for reads. Measure today's cost with the S0 probe (`seon.pass-probe/run-mcp`, `run-model`) where it applies.
2. Write docs/research/agent-platform/2026-10-07/<your-lane>/design-notes.md:
   - what exists (file:line, callers, data, measured cost);
   - duplicates;
   - library seams;
   - the smallest composition (what is deleted, changed and added, and why no owner or library already does it);
   - alternatives;
   - risks and the tests that reach them;
   - the expected S0 numbers after;
   - the claims you will need.
   Mark anything untraced UNVERIFIED.
3. Commit only that file (`bin/lane claim <lane> <dir>`, then `bin/lane commit <lane> -m "..." <dir>/design-notes.md`),
   SendMessage root that it is ready, and STOP. Root runs a dual review, and the owner approves core slices
   before any code.

No Monitor tasks, pollers or extra JVMs. Retire any agent branch you create.
