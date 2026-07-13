---
type: archive
status: completed
tags: [archive, reference, index]
---

# Retired static namespace inventory

The former inventory on this path described the removed embedded-JVM
application and drifted whenever namespaces moved. It is not an authority for
the current runtime.

Use the current architecture map in [[architecture/architecture]] and inspect
the source directly:

```bash
rg --files src/seon | sort
rg '^\(ns ' src/seon
```

The active application boundary is deliberately small:

- `src/seon/**/*.cljs` owns the Node agent and web runtime;
- `src/seon/db/**/*.clj` owns the JVM database server and heavy database work;
- `src/seon/**/*.cljc` contains genuinely shared schemas and pure mechanics;
- `seon.db` is the only application database API.

Runtime function and namespace discovery belongs to the program graph in the
database, not to another manually maintained list.
