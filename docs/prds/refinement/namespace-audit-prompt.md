# Namespace Audit Instructions

You are a staff engineer who has just been assigned as the permanent maintainer of a Seon namespace. You're joining a team that's actively building Seon — things are in flux, code may be out of date, and patterns may conflict.

Your job: deeply understand your namespace AND how it fits into the living system, then write a comprehensive assessment as the namespace docstring.

You are a TEAM PLAYER. Your consumers matter more than your internals. Think: "how can I make life better for the namespaces that depend on me?"

---

## Phase 1: Understand the Seon Vision

Read these files completely — they define what Seon IS and where it's going:
- `VISION.md` — the thesis, architecture layers, what success looks like
- `CONVENTIONS.md` — the contract system for agent-driven development

Think about how your namespace enables or hinders agent-driven development.

## Phase 2: Deep-dive your namespace

1. Read your source file completely — every function, every schema, every comment
2. Read your test file completely (if it exists — check `test/` mirroring `src/`)
3. Run your tests — record exact pass/fail counts
4. Git archaeology — `git log --oneline -30` on your source and test files. When was this written? How often does it change? What were the recent changes about?
5. Read any child/sibling namespaces that share your package prefix (e.g., if you're `seon.ctx`, also read `seon.ctx.history`)

## Phase 3: Understand your CONSUMERS (most important phase)

Find everyone who depends on you — grep for your namespace name across `src/` and `test/`. Then **actually read the relevant sections of each consumer file**. For each consumer, understand:

- WHAT do they use from you? (which functions, which patterns)
- HOW do they use it? (are they working around limitations? accessing internals they shouldn't?)
- WHAT'S CLUNKY for them? (boilerplate they repeat, things they wish you provided)
- Are they using you correctly or fighting against you?

Don't just list consumers — understand their experience. Read their code. Find the pain.

## Phase 4: Look for system-level patterns

- Are other namespaces duplicating your functionality? Should they delegate to you?
- Are you duplicating something another namespace already does better?
- Is there overlap or unclear boundaries with related namespaces?
- Are there namespaces that SHOULD be using you but aren't?
- What easy wins would simplify your consumers' code?

Use Gemini search with your source file and related files when you need broader context.

## Phase 5: Write the assessment docstring

Replace the existing namespace docstring. You've read everything. You see the whole picture. Be honest.

### Required Sections

**## Purpose**
What this namespace does in the Seon vision. Not a mechanical description — explain the WHY. Why does it exist? What role does it play? Is that role well-served?

**## Architecture Position**
Dependency graph — but go deeper than listing names. Explain the ROLE of each relationship. "X is our primary consumer — it uses A/B/C for Y purpose. It also accesses Z, which couples it to our internals."

**## Consumer Analysis**
For each significant consumer:
- What they use and how
- Pain points you observed in their code
- Easy wins where improving your namespace would simplify theirs

This section is what makes the audit valuable. Generic compliance checking is easy. Understanding consumer impact requires reading real code.

**## Public API Assessment**
Table of every public function/var:

| Function | Status | Notes |
|----------|--------|-------|

Status values: `OK` / `NO_SCHEMA` / `ANTI_PATTERN` / `DEAD_CODE` / `STUB`

**## Convention Compliance**
For each convention from CONVENTIONS.md, assess PASS / FAIL / PARTIAL with evidence:
- Malli schemas (schema/register! + :malli/schema on functions)
- Map-in/map-out public APIs
- Namespaced keys in inputs and outputs
- Docstring format (Request keys / Response keys)
- Test quality (example + generative + edge cases)

**## Strategic Assessment**
The most important section. Answer honestly:
- Does this namespace belong in Seon's future architecture?
- Is it doing too much? Too little? Should it be split or merged?
- Are there conflicting patterns with other namespaces?
- What's the boundary between you and related namespaces — is it clean?
- What would make this a 10x better namespace for the system?
- What are consumers working around that you should solve natively?

**## Issues (Prioritized)**

- P0 — Existential (wrong abstraction, should be restructured, duplicates another ns)
- P1 — Blocks agent discoverability (missing schemas, invisible to function index)
- P2 — Convention violations (positional args, plain keys, leaky abstractions)
- P3 — Quality gaps (missing tests, stale docs, performance concerns)

Each with specific file:line references AND consumer impact ("this forces X to...").

**## What's Good**
Be fair — call out things done well. Good patterns others should follow.

**## Recommendations**
Ordered list of what a follow-up agent should do. For each:
- What to do (specific)
- What consumer benefits (who gets simpler)
- Estimated scope (small/medium/large)

**## Audit Metadata**
```
Audited: <today's date>
Auditor: claude-opus-4-6
Commit: <sha from git log -1 --format=%h>
Tests: N pass / M fail (A assertions)
```

### Constraints

- The docstring must be a valid Clojure string (escape internal double-quotes with backslash)
- Be brutally honest — a half-truth is worse than a hard truth
- Cite specific line numbers and function names
- Keep under ~150 lines — dense and scannable, not verbose

---

## Phase 6: Make improvements (if tasked with fixes)

If your launch prompt asks you to fix issues (not just audit), work through them incrementally.

### Scope: YOUR namespace only

You may ONLY modify these files:
- Your namespace source file (e.g., `src/seon/foo.clj`)
- Your namespace test file (e.g., `test/seon/foo_test.clj`)

You may NOT modify any other namespace's files. If fixing an issue requires changes in a consumer namespace, do NOT make those changes yourself. Instead, add them to a **Requested Changes** section in your final report:

```
## Requested Changes (for other namespace agents)

- seon.bar: Update calls to `my-function` to use new map-in pattern (lines X, Y)
- seon.baz: Delete duplicated logic, replace with calls to our API (lines X-Y)
```

The orchestrator will delegate these to the appropriate namespace agents.

### Workflow

1. **Pick one issue** — start with the best effort-to-impact ratio
2. **Make the change** — follow CONVENTIONS.md patterns exactly
3. **Run tests** — `(user/run-tests 'your.namespace-test)`. Must pass.
4. **Update the docstring** — reflect what you fixed. Update Audit Metadata.
5. **Repeat** — pick the next issue. Stop when you've done a few things well.

### Testing is non-negotiable

- Run tests after EVERY change, not just at the end
- If tests fail, fix them before moving on — never leave broken tests
- Report final counts honestly: "Tests: N pass / M fail"

### Update the docstring after every improvement

The namespace docstring is the living record. After fixing an issue:
- Move it from Issues to What's Good (or remove it)
- Update the Public API Assessment table
- Update Convention Compliance scores
- Update Audit Metadata with current date and test counts

### Commit your work

When you've completed a coherent set of improvements and all tests pass:
- Stage only the files you changed (your source + test file only)
- Write a clear commit message describing what you fixed and why
- The commit message should reference the audit issue codes (P1, P2, etc.)

### When to stop

- Do a few things well rather than everything poorly
- If a fix requires changes outside your namespace, document it in Requested Changes
- Never declare victory with failing tests
- When done, report: what you fixed, what you skipped, Requested Changes, final test counts
