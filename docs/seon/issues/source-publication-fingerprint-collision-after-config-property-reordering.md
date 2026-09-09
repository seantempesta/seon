---
type: issue
status: open
severity: friction
tags: [issue, source, schema, config]
---

# Investigate the scratch config publication fingerprint collision

During the 2026-09-09 faults-render scratch publication, admission reported a
fingerprint collision while making the credential selector optional. The
subsequent publication succeeded with the declaration properties ordered as
`:min`, `:seon.config/optional`, `:seon.config/per-agent`. The failed variant
placed the same optional/per-agent properties in the opposite order.

This is an observation, not proof that map order caused the failure: the
scratch publication and branch state also changed during recovery. Reproduce
on an isolated root with two otherwise identical EDN declarations before
attributing the cause to fingerprint derivation. The faults-render lane did
not change the source indexing owner and its final canonical gate is green.

See [the landing note](../../prds/context-generation/research/faults-render-landing-2026-09-09.md)
for the publication boundary. No production workaround or extra identity
mechanism is proposed.
