---
type: issue
status: resolved
severity: friction
tags: [issue, tooling, mcp, repl]
---

# Give MCP frame provenance the program graph's source-root authority

## Problem

MCP now derives frame prefixes from namespaces parsed out of source, but it
selects that source through its own `["src" "test"]` roster. The program graph
already owns the same roster. Adding or changing a first-party source root can
therefore index valid functions while MCP silently omits their exception
frames.

## Evidence

- `src/seon/fn.clj:19-21` declares `seon.fn/source-roots` as the roots admitted
  to the program graph.
- `script/seon/dev/mcp.clj:34` independently declares
  `first-party-source-directories` with the same two literals.
- `script/seon/dev/mcp.clj:589-595` derives class prefixes only from files
  below that second roster. The namespace parsing and exact `$` class
  boundary at `:571-611` are sound once the right source inventory arrives;
  the duplicated inventory owner is the remaining hand list.
- Commit `5a83efc2e` archived the earlier prefix-list issue with an acceptance
  condition that membership be derived from source or program provenance.
  Namespace spelling is no longer the classifier, but root membership still
  has two authorities.

## Owner

The first-party source inventory consumed by the program index and development
MCP server.

## Acceptance

- One maintained source-root authority feeds both program indexing and MCP
  exception provenance without making MCP boot the application runtime.
- A regression adds a temporary first-party source root at that authority and
  proves its compiled frame survives projection with no MCP-specific edit.
- Dependency and JDK frames remain omitted, and source discovery continues to
  refuse symlink traversal.

## Resolution — 2026-08-03

The exception-frame projection and its source-root walk were deleted with the
MCP-only truncation mechanism. Exceptions now pass through the same admitted
print-node value chain as every other result, so there is no MCP frame roster
to synchronize with the program graph. The obsolete frame-specific tests were
deleted in the same change; the focused bridge suite is green at 18 tests and
117 assertions.
