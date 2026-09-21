---
type: reference
status: ready for review at 076827cc9
created: 2026-09-21
tags: [agent-platform, operator, boot, review]
---

# Fable review of the operator and boot implementation

Review the implementation on `refactor/agent-platform`, range
`04fd7ed88..076827cc9`. First record the exact committed tip you reviewed and read the
implementation landing note. Source may still be changing: review committed
bytes, preserve all uncommitted work, and do not edit implementation files.
The first scratch boot reached readiness after publication and in-process repair:
no missing layers, one root agent, 9,154 ms for that boot, and HTTP GET `/` = 200.
This is not a completed cold-reset proof. The eight canonical drills, MCP restoration,
platform checkpoint and replacement of default remain pending. The implementation
lane continues those proofs while you review this fixed commit.

Read the integrated B1b specification end to end, README §4/§6/§7, and
`docs/research/agent-platform/b1b-implementation-brief-2026-09-21.md`.
Implementation is one Astra medium lane, alone on default. The owner permits
temporary boot, REPL and MCP breakage within the slice. B1b deliberately retains
current projection, instrumentation, publication and acquisition semantics until
their later cuts. Do not turn this review into implementing those cuts or making
the entire legacy suite green.

Review the real diff and the dependency implementations it relies on. Prioritize:

1. Reset captures one exact process identity. Its graceful request and every
   signal address that identity; a replacement winning the gap is never stopped
   or deleted. Missing identity is a refusal, not evidence of absence.
2. Destruction follows store-lock acquisition and precedes every connection or
   store probe. The sibling lock inode, FileLock and channel survive publication
   and boot. Failed release retains exclusion; no second descriptor defeats it.
3. The REPL opens before program loading and store acquisition. Partial boot
   remains diagnosable. A losing process cannot replace or remove the winner's
   advertisement. Readiness names actual missing layers and the served URL.
4. Resource release is instance-addressed and ordered. Named stop preserves
   siblings and replacements; down awaits actual process exit under declared
   bounds, including an unresponsive process.
5. MCP, hook, test-check, test recording and export callers use the new owners.
   Transport exceptions, missing terminal events and malformed replies cannot
   become success. Test publication still forwards progress and uses its declared
   bound. No old lifecycle/claims/census mechanism survives under a new name.
6. The eight drills test the actual guarantees through the canonical authority:
   real stores, SCI and contracts where applicable; actual competing processes;
   no hand-rostered substitute or `.isValid`-only exclusion proof. Distinguish
   implemented assertions, executed results and pending integration evidence.

The new command/boot files target approximately 900 lines, not a ceiling.
Judge clarity, guarantees and deletion of redundant mechanisms. Account for
surviving maintenance/filesystem behavior at its new owner. Avoid cosmetic work
or fresh infrastructure unless a concrete failing guarantee requires it.

Return a short ranked review with exact file:line and reproduction or dependency
evidence for each finding. State which guarantees are established, which remain
unproved, and whether any finding blocks replacement of default. Give the smallest
correct fix. Do not run destructive processes, reset default, start a suite,
change branches, launch agents, push, or amend another agent's commits. Coordinate
any live probe with the orchestrator so it cannot overlap the implementation's
scratch drills. Documentation feedback only; the implementation lane owns fixes.
