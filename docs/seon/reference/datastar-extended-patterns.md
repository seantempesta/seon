---
type: reference
status: abandoned
tags: [reference, web, history]
---

# Historical Datastar pattern survey

> Historical dependency research only. None of the handlers, atom watches,
> authentication sketches, `seon.web.sse` calls, or testing recipes formerly
> collected here are current Seon APIs. Use [[datastar-quick-reference]].

This 2025-12-02 survey explored generic Datastar application patterns before
Seon's database-woken Flow renderer existed. Its project-specific recipes were
deleted because they taught a second page architecture and a deleted
`refresh-all!` path. Git is the archive.

Useful current source material lives in the vendored Datastar repositories
under `reference-code/datastar/` and `reference-code/datastar-clojure/`. Seon's
chosen mechanism is implemented and tested in `src/seon/render/web.clj` and
`test/seon/render/web_test.clj`.
