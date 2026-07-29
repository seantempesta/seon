---
type: issue
status: open
severity: friction
tags: [issue, config, architecture]
---

# "A dial exists" has no single authority

Surfaced by the 2026-07-29 config-chain trace (the no-auth dial was
registered, leaf-implemented, and assembly-read — yet unconfigurable,
because manifest admission and database installation are separate
contracts nobody crossed). The landed cross-check test fences the
class; this issue records the DISSOLUTION the fence approximates:

**Derive the `:seon.config/manifest` map from the dial registrations**
— one authority (the registered `:seon.config.*` attributes and their
schemas), the closed manifest map computed from it, so a dial that
exists is admissible and installable by construction. The fence test
then deletes itself.

## Acceptance

Adding one dial = one registration in `schema/config.edn`; it is
immediately manifest-admissible, database-installable, and
defaults-documentable, with no second edit anywhere. The cross-check
test is deleted in the same change (its class is unrepresentable).
Judge at implementation whether the entity-map derivation belongs in
the schema loader or the config gate — one owner, no new mechanism.
