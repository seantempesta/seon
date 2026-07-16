---
type: issue
status: resolved
severity: correctness
tags: [issue, database]
---

# Datahike disconnected query drops projected inputs

## Symptom

A planner-enabled query over disconnected database sources threw while
projecting an ordinary value supplied through `:in`:

```clojure
[:find ?a ?b ?ordinary
 :in $a ?ordinary $b
 :where [$a ?ea :x/value ?a]
        [$b ?eb :x/value ?b]]

```

The Cartesian component split constructed its output positions solely from
variables produced by `:where` components. A projected scalar, tuple,
collection, or relation input belongs to no such component, so its missing
position reached `nth` as nil and caused a `Character.charValue` null error.

## Resolution

Datahike commit `a464cd88` detects a projected variable that no split component
owns and uses Datahike's existing relation engine for that query. That engine
already retains every resolved input binding. Seon adds no query workaround or
second execution mechanism.

The focused `test-multi-source-queries` proof passes under persistent-sorted-set,
hitchhiker-tree, and specification configurations: one test and nine assertions
in each configuration. Seon's real JVM/UDS compatibility proof subsequently
passes result shapes, two- and three-database queries, nested descriptor-shaped
ordinary inputs, temporal values, pull, pull-many, and index paging.
