# Plan research brief (root, 2026-10-07)

You are a RESEARCH lane for root's live-agents plan. Root writes plan v2 from your findings, then it is dual-reviewed
and refined before the owner sees it. Your job is to OBSERVE and MEASURE the existing code. Do not design and do
not change anything in src/ or test/.

Read first:
- docs/research/agent-platform/2026-10-07/lane-rules.md (all of it, including "One happy path" and "Budgets");
- docs/research/agent-platform/2026-10-07/root-plan/live-agents-plan-notes.md (the draft plan with every owner
  ruling so far: one parser/eval/record/printer/cache/loop; context = forms that ran, presented as REPL or DATA,
  decided by experiment; opening forms run once, and later contexts read stored exchanges; one machinery
  transaction per turn, while the agent's own writes commit when its forms run; budgets);
- the real captured turn in docs/research/agent-platform/2026-10-07/loop-pass/runs/00-installed-turn-lp-add-2/.

Method:
1. Read every owner on your stage END TO END, not excerpts.
2. Trace a real example through it at the REPL (MCP eval_clj on default; JVM mode with
   `(seon.cluster.boot/connection "default")`; your own agent id for SCI). Inspect the actual value at each step.
   - For a model turn, use a disposable agent with a fixture provider reply supplied as a value (`:seon.ai/send!`,
     as seon.ai-test does). Real DeepSeek calls are allowed (owner: unlimited) when the fixture can't show
     something: enable with `(seon.agent/settings! {:seon.config.ai/no-provider false} conn agent-id)`.
   - Never load-file, intern or alter-var-root into default. Never stop or reset default.
3. Measure each step: wall ms, allocated bytes (com.sun.management.ThreadMXBean getTotalThreadAllocatedBytes, and
   per thread), armed calls (seon.profile), commits (count transactions by `:seon.db.transaction/operation`), and
   bytes written to the store.
4. Write what you OBSERVED, with `file:line`, values and numbers. Mark anything you did not trace UNVERIFIED.
   Then list:
   - the facts the plan's draft gets wrong;
   - the constraints the plan must respect;
   - what is slated for deletion in your area, with its callers.

   No proposals beyond a short "implications for the plan" list.

Output: docs/research/agent-platform/2026-10-07/plan-research/<your-lane>-notes.md. Claim it with
`bin/lane claim <lane> <path>`, commit it with `bin/lane commit <lane> -m "..." <path>`, then release. Scratch goes
only under your session scratchpad /<lane>/. Final message: the 10 most important findings with numbers, and the
path.

**No background machinery.** Do not start Monitor tasks, pollers, watcher loops or extra JVMs. Use only foreground
REPL calls and short shell commands. Clean up any disposable agent branch you create
(`my.agent/retire-branch!`), and list it in your notes.
