---
type: issue
status: resolved
severity: blocking
tags: [issue, database, cljs]
---

# Restore CLJC reader required a CLJS-only alias

## Problem

The JVM writer gate could not read `seon.db.restore`. Its CLJS-only acquisition
branch contained auto-resolved `::db/...` keywords, while the `seon.db` alias
correctly exists only in the CLJS reader conditional. The Clojure reader must
resolve namespaced keywords before discarding the branch and failed with
`Invalid token: ::db/members`.

## Owner

`seon.db.restore` owns the cross-platform restore fact shape and its CLJS
authority acquisition.

## Acceptance

The namespace reads on both platforms, the CLJS branch retains the exact
`:seon.db/...` wire keys, and the focused writer and Bun authority-density
proof can start without adding a JVM dependency on the CLJS facade.

## Resolution

The five wire keys in the CLJS-only branch are written as their fully qualified
`:seon.db/...` values. This changes no runtime data and lets the JVM reader
discard the branch without requiring the CLJS alias.
