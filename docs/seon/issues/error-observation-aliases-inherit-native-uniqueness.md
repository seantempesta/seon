---
type: issue
status: open
severity: friction
tags: [schema, datahike, error, alias]
---

# Error observation aliases inherit native uniqueness

The bridge's raw and compiled mappings both follow an attribute alias before
reading storage properties. An observation attribute aliasing an identity
therefore receives `:db.unique/identity`, even when its own declaration does
not declare identity. This differs from putting the domain identity key
directly in an error map: the attribute names are distinct, so the alias
does not upsert the domain row. Repeated observations of the same value can
nevertheless upsert one another if submitted as entity maps.

The canonical error storage audit in run `adf54170ac72` reports:

- `:seon.sci.eval/schema-refused` aliases `:seon.schema/key`.
- `:seon.context/selection-agent-id` aliases `:seon.agent/id`.
- `:seon.test.runner/long-test-ns-hook` aliases `:seon.ns/name`.
- `:seon.test.runner/default-cluster-refused` aliases `:seon.cluster/name`.

The same mapping also reaches `:seon.schema/expected-value` through its
alias. Source evidence: historical `22a1a0567` bridge
`malli->datahike-attr-in` resolves the alias then reads its properties;
the step-2 compiled fold preserves those native declarations exactly.

Bridge step 2 does not remove inherited storage properties: that would change
the promised native parity and alias precedence. The five direct foreign
identity members are being converted separately under the explicit owner
ruling. These observation aliases need their own value declarations and
reset boundary, coordinated with their schema owners. The existing
`seon.schema-test/error-facets-and-their-owned-members-are-storable` regression
continues to report their native uniqueness; its assertion is not weakened.
