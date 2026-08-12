---
type: issue
status: open
severity: cleanup
tags: [issue, schema, database, vocabulary]
---

# Unify :seon.db/database-value into :seon.db/db

Owner-directed 2026-08-12. The entry key is already `:seon.db/db`; the
value shape it references is named `:seon.db/database-value` — two names
for one meaning (~146 occurrences across src/ and resources/). Unify on
the conventional short name the way every other Seon declaration pairs key
and shape (`[:seon.db/db :seon.db/db]`); "database value" stays the
English noun per the database vocabulary section. One atomic rename wave:
schema declarations, input-refs metadata, call-preparation rows, request
maps, docs. No stored-data migration (program facts reindex; DB data
disposable). Scheduled with the kind migration after the generator
integration gate.
