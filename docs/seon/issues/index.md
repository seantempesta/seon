---
type: reference
status: active
tags: [issue, index]
---

# Issue index

The index is a query over published issue entities. Publish note changes
with `bin/seon init --dev default --changed docs/seon/issues`, then inspect:

```sh
bin/issues-index
bin/issues-index --class class/n7
bin/issues-index --check
```

The first command queries open issues; the class query follows member refs.
The checker prints the indexer's complete refusal report and exits nonzero
when a note or citation cannot be admitted. It does not maintain a second
schedule in Markdown.

From a JVM REPL with explicit cluster custody:

```clojure
(seon.issue/issues
 {:seon.db/db (seon.db/db (seon.operator/connection "default"))
  :seon.issue/status :open})
```

See [the lifecycle and publication contract](README.md).
