---
type: reference
status: ready for Fable review
created: 2026-09-21
tags: [agent-platform, instructions, testing, review]
---

# Fable review: shared instruction activation

Review the commit containing this note against its parent. Activation is independent
of B1b's remaining integration. No next implementation lane launches until this
review checks for dropped binding rules. Documentation activation is not platform green.
Read old and new root AGENTS.md, the old/new clojure-testing skill, the vocabulary
reference, the changed REPL introduction and plan §6/§8. Read the named source seams
when reviewing enforcement claims. Review committed bytes and preserve other work.

## What changed and where binding rules live

| Former content | Current home / disposition |
|---|---|
| Five design laws, source archaeology, speed, explicit custody, data/ref/component rules, rendering limits | Root AGENTS.md; mechanics stay in existing architecture/data guide and specialized skills |
| Vocabulary table and its terminology rules | docs/seon/architecture/vocabulary.md, linked from root; old absent archive links replaced with current owners/plans, target status retained |
| Test fixture rules, helper signatures, instrumentation restoration, properties, waits, evidence | clojure-testing skill, with source-grounded enforcement/author-duty table |
| Gate every commit / run reaching suite per conversion | Superseded by owner's cut-level policy, plan §6; root and both skills route there |
| Root/branch isolation, resource limits, exact process identity, no worktrees for lanes, path-limited commits, default ownership, model/effort limits | Root operation/collaboration sections; detailed launch mechanics remain in lane skill |
| Future task and candidate admission design | Explicit target paragraphs and owning B3/D1/D2 specs, not claims of installed behavior |
| Repeated incidents, obsolete line-by-line runner choreography, proposed duplicate instructions | History remains in Git/research; proposal now routes to active root, no competing full authority |

## Required review

1. Find any binding owner rule lost or weakened. Cite its old paragraph and the
   smallest correct surviving home. Distinguish a dropped guarantee from retired
   mechanism details or explicitly superseded gate cadence. Do not reintroduce
   suites per commit, obsolete operators, or arbitrary line-count ceilings.
2. Check the enforcement table: duration failure is recorded at test completion;
   request observation bounds do not guarantee thread termination; transacted!
   surfaces refused writes only when used; worker initialization checks arming
   coverage but run does not arm automatically; drift compares limited observations;
   no mechanism detects every hand-written fixture map or proves its semantic fidelity.
3. Test the instructions against these examples without running the system:
   a core edit reaches most tests during a cut; a fixture write returns a refusal;
   a test runs beyond its body bound; a REPL reload strips an entering wrapper;
   a lock competitor exits nonzero before reaching lock acquisition; a correct
   recorded green is reused without executing. Do the instructions lead to honest
   evidence and bounded work rather than ritual or false green?
4. Check current-versus-target labeling, navigation, and that Codex/Claude share the
   same files (CLAUDE.md and .claude/skills remain symlinks).

Return ranked findings with exact locations. No source changes, process operations,
JVMs, suites, branch changes or new lanes. Root owns corrections and final review
closure. The implementation agent has completed B1b and is not editing these files.

## Validation at activation

- Root reduced from 1330 to 297 lines; no binding rule may be dropped to hit a count.
- Testing skill is 157 lines, with explicit responsibilities rather than historical
  runtime choreography. Vocabulary retained separately (128 lines).
- Checked duration, fixture writer/helpers, arming initialization, drift and bounded
  request implementations directly; this is documentation verification, not a new
  runtime test result.
- Checked Markdown local link targets, shared-file symlinks, YAML metadata and diff
  whitespace. The skill-creator Python validator lacked PyYAML; Ruby's installed
  YAML parser validated the metadata without installing dependencies.
