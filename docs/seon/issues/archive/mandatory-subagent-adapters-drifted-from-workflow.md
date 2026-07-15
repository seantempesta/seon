---
type: issue
status: resolved
severity: cleanup
tags: [issue, agent, orchestrator]
---

# Make client subagent adapters selective

## Problem

The Claude and Codex `seon-agent` adapters required a subagent for every Seon
implementation, and the verifier adapters prescribed a mandatory cheaper-model
stage. That contradicted the maintained top-level workflow, which delegates
only coherent independent work or risk-reducing verification.

## Evidence

Before commit `64b72dbb`, both implementation-adapter descriptions contained
`MUST BE USED for all Seon implementation tasks`. Both verifier descriptions
assumed they ran after every implementation, and their embedded manuals copied
shared repository guidance already owned by `AGENTS.md` and `AGENT.md`.

## Owner

The `.claude/agents/` and `.codex/agents/` files are format-specific client
adapters. Shared development policy belongs in `AGENTS.md`; top-level and
delegated role overlays belong in `ORCHESTRATOR.md` and `AGENT.md`.

## Acceptance

- Existing `seon-agent` and `seon-verifier` names still load in both formats.
- Neither format mandates delegation, independent verification, or a cheaper
  model for every unit.
- The adapters point to the shared authorities instead of copying their
  operator and testing manuals.

## Resolution

Commit `64b72dbb` retained both compatibility aliases, made their use selective
and risk-based, and removed 255 lines of duplicate instructions. Claude YAML
frontmatter and Codex TOML parsed successfully; the updated orchestrator audit
passed `seon.dev.markdown/validate-file`; `git diff --check` was clean.
