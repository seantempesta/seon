---
type: issue
status: open
severity: friction
created: 2026-09-24
tags: [issue, schema, datahike, schema-admission]
---

# A stored `:or` attribute drops its children's nil refusal

Seen 2026-09-24 by lane fix-leftovers-1 in default (pid 70372) after
e86043f81. That commit refuses a named value schema spelled `[:maybe X]` or
`[:or :nil X]` at projection build. A nil-accepting form that is not the
top-level node still reaches storage. `seon.schema.datahike/compiled-storage`
handles an `:or` node in its `(= :or t)` branch
(`src/seon/schema/datahike.clj`, just above the `:maybe` refusal at
line 131). That branch derives the value type from its children's
`::value-type` and ignores their `::refusal`. A nil child has no value type,
so the attribute is stored as an EDN string.

Probe (`seon.schema.datahike/malli->datahike-attr-in` over
`(seon.schema/build-projection (assoc (seon.schema.edn/packaged-forms) k form))`):

| form | result |
|---|---|
| `[:or :nil :string]` | refused at build (e86043f81) |
| `[:and [:or :nil :string]]` | `:db.type/string` |
| `[:or :string [:maybe :int]]` | `:db.type/string` (the child's `::nilable-attribute` refusal is dropped) |
| `[:or :string [:or :nil :int]]` | `:db.type/string` |

The storage fold is the one place that sees every nested node. The fix
belongs there. An `:or` should carry the first child `::refusal`, and a
`:nil` child should refuse as `::nilable-attribute`. Then the top-level check
in `assert-non-nilable-value-schema!` is only the early, guiding message.
