---
type: issue
status: resolved
severity: friction
tags: [issue, docs, wave/docs-honesty]
---

# Reconcile skill frontmatter validation

## Problem

The repository Markdown hook requires top-level `type` and `status`
on skill documents, while the bundled skill-creator validator rejects
those keys. A skill cannot pass both unchanged validators.

## Evidence

Observed 2026-09-08 in the docs-and-skills lane:

- `script/seon/dev/markdown.clj:484` implements the unconditional
  required-fields rule. Editing a skill with only name and description
  produced two errors: missing type and missing status.
- The skill-creator validator at
  `/Users/sean/.codex/skills/.system/skill-creator/scripts/quick_validate.py:42`
  permits name, description, license, allowed-tools, and metadata only.
- Running
  `uv run --with pyyaml python /Users/sean/.codex/skills/.system/skill-creator/scripts/quick_validate.py .agents/skills/repl`
  exited 1 with: `Unexpected key(s) in SKILL.md frontmatter: status, type.`
- The system `python3` lacks PyYAML; using uv supplied it and exposed
  the actual validation conflict. No validator code was changed.

The edited skills now retain the supported name/description frontmatter;
all nine pass the bundled validator. The repository hook still emits its
two document-metadata errors on these valid skill files. During diagnosis,
adding type/status instead produced a warning that `skill` was absent
from the vault vocabulary, despite multiple skill files using it.
Classification and vocabulary must use the correct skill-document scope.

## Owner

`script/seon/dev/markdown.clj` owns repository document classification.
The bundled validator is external to this repository. Fix classification
at the owner rather than making every skill carry incompatible metadata.

## Acceptance

An ordinary valid SKILL.md passes both the repository hook and the
supported skill validator. Non-skill documents still require their
declared document metadata, and malformed skill frontmatter still fails.

## Resolved — issues-sweep, 2026-09-08

The Markdown rule selects the SKILL.md file format and requires name/description, while ordinary documents still require type/status. Skill fields no longer enter the document vault’s type/tag vocabulary. The existing Babashka hook suite passed 30 tests / 369 assertions, including one class regression covering valid skills, ordinary documents using skill fields, and missing/empty skill fields. `validate-file` on .agents/skills/repl/SKILL.md returned valid? true with no violations; the bundled quick_validate.py also returned “Skill is valid!”. The external validator continues to own its full skill-format checks.

[Landing evidence](../../../prds/context-generation/research/issues-sweep-landing-2026-09-08.md).
