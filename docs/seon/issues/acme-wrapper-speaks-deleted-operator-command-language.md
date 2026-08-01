---
type: issue
status: open
severity: friction
tags: [issue, operator, deletion]
---

# Make the ACME wrapper speak the fresh operator command language

## Problem

`bin/acme` documents and delegates `up`, `restart`, `status --edn`, named pod
logs, and cluster reset commands from the deleted pod operator. Its delegate is
now `bin/seon`, whose fresh command surface has none of those forms. The wrapper
therefore advertises operations that fail or silently report the global fresh
roster instead of an isolated ACME target.

## Evidence

- `bin/acme:11-16` advertises the deleted commands, and `:98-130` delegates
  them unchanged after composing pod/Shadow-era environment variables.
- `script/seon/fresh_operator.clj:1800-1832` accepts only `start`, `config`,
  `init`, `status`, `open`, `stop`/`down`, and `logs` in their fresh forms.
- Read-only live probe: `bin/acme status --edn` failed with
  ``status takes no arguments``. `bin/acme status` succeeded but printed the
  same global roster as `bin/seon status`, not an ACME-specific identity.
- `bin/acme:63-91` still teaches grants, a CLJS extra-source preload, an
  artifact descriptor, and `SEON_CONFIG`; the fresh operator reads database
  facts and has no pod/Shadow graph.

## Owner

The downstream ACME wrapper boundary; no consumer-specific mechanism belongs
in fresh Seon core.

## Acceptance

- Every documented ACME command maps to one current `bin/seon` operation and
  preserves explicit cluster identity.
- Unsupported pod/Shadow/config-manifest environment composition is deleted.
- Read-only status and logs prove they select ACME rather than the global/default
  target, and invalid legacy commands fail with current guidance.
