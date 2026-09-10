---
type: issue
status: open
severity: friction
tags: [issue, database, schema, wave/schema-admission]
---

# Raw write validation refuses reverse refs and partial entity maps

Data lane probe, 2026-09-09: `seon.db/write-attribute-error` refuses
`:seon.turn/_attempts` as uninstalled even though Datahike accepts the reverse
of the installed ref. `write-map-error` validates a supplied identity map as
an entire entity, so a model gauge update containing only its identity and
changed gauge refuses the model's required provider field.

The data lane's production writers now use ordinary forward `:db/add` facts
for the attempt-to-turn link and model gauges. Attempt recording returns a
refused transaction's diagnostic; the caller cannot freeze a reply or make a
second provider call after that refusal. The armed data-shape and AI tests
exercise those writers.

The general raw-write surface remains narrower than Datahike. Fix validation
at its owning writer admission seam without a racy pre-read of the entity or
another transaction engine. Regression subjects should include reverse ref
map syntax and an identity upsert that supplies only a changed optional
attribute. This is outside chart data-shape steps 3–7.
