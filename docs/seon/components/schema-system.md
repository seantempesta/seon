---
type: component
status: active
tags: [component, schema, database]
---

# Schema System

`seon.schema` is the one cross-runtime Malli registry and program-data seam.
Namespaces register fully namespaced attribute and function schemas once; the
runtime uses the same definitions for instrumentation, context discovery, and
Datahike schema derivation.

## Active namespaces

| Namespace | Runtime | Responsibility |
|---|---|---|
| `seon.schema` | CLJC | Register, inspect, discard, relink, and tee schema definitions |
| `seon.schema.internal` | CLJC | Pure schema-form mechanics |
| `seon.db.datahike.schema` | JVM | Translate Malli entity schemas into Datahike attribute schema |

`schema/register!` updates the Malli registry and emits structured program
facts through its configured tee. At boot, definitions restored from database
facts rebuild the runtime registry; later definitions update it incrementally.
The registry is runtime machinery, not a second durable authority.

Datahike schema is installed by the authoritative writer. Domain code uses
`:seon.db/*` properties; the JVM bridge translates those properties at the
database boundary. References use `:seon.db/ref`, absence means an attribute is
not present, and persisted attributes never use unregistered bare keys.

The archived JVM application carried additional registries and flow-message
types; those were removed with that application. See
[[../architecture/archive/jvm-main-app]] and
[[../architecture/data-model]] for the current model.
