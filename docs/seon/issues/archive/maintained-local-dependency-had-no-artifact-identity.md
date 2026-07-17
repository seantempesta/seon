---
type: issue
status: resolved
severity: blocker
tags: [issue, component, flow]
---

# Maintained local dependency had no artifact identity

## Problem

The source checkout intentionally selects the maintained Datahike repository
through `:local/root`, but artifact publication accepted only dependency maps
that repeated a GitHub URL and SHA. `bin/seon up` therefore rejected the
vendored source after completing its builds, even though that checkout already
had a stronger exact Git identity.

## Evidence

The operator rejected `org.replikativ/datahike` in the writer `:replace-deps`
because its coordinate was `{:local/root "reference-code/datahike"}`. The
selected repository was clean at
`a464cd887458d2572414a6ea951c477b0981fdae`, with origin
`git@github.com:seantempesta/datahike.git`.

## Owner

Maintained dependency identity derivation in `script/seon/dev/artifact.clj` and
its focused artifact tests.

## Acceptance

- Exact public `:git/url` and `:git/sha` coordinates remain supported.
- A maintained `:local/root` must resolve to a clean Git checkout with a public
  GitHub origin and a 40-character HEAD.
- SSH GitHub origins normalize to the manifest's HTTPS form.
- Uncommitted vendored dependency source fails closed instead of publishing a
  misleading SHA.
- Both aliases selecting Datahike resolve to the same identity, and focused
  artifact tests pass.

## Resolution

Artifact identity now derives the clean local repository's public origin and
HEAD, then passes them through the existing deterministic alias-equality and
manifest validation. The focused artifact suite passes 20 tests and 78
assertions, including the actual maintained Datahike checkout.
