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
adapters. Shared development policy and top-level integration ownership belong
in `AGENTS.md`; only the delegated role overlay belongs in `AGENT.md`.

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

The 2026-07-14 follow-up found that neither harness loads `ORCHESTRATOR.md` and
that root `AGENTS.md` had absorbed its remaining durable rules. The active file
is therefore a superseded historical stub, and `AGENT.md` is reduced to the
delegated-lane rules the client aliases actually need.
