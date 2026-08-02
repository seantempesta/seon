---
type: issue
status: open
severity: cleanup
tags: [issue, schema, deletion]
---

# Delete five readerless schema rows left by completed cuts

## Problem

The admitted schema population still publishes five named shapes that no
production function contract, database family, config dial, or other schema
references. Four survived completed mechanism cuts; the fifth exists only to
advertise a Flow opaque-value generator that no contract requests.

These rows are not harmless documentation. Every cluster admits and publishes
them as current program-graph facts, so agents can discover contracts for
mechanisms the runtime no longer has.

## Evidence

A reference-graph sweep treated production function contracts, entity maps,
`{:seon.db/attributes true}` maps, and config dials as roots, then followed
every keyword reference through `resources/seon/schema.edn`. These five
top-level rows were unreachable, and an independent literal plus owning-
namespace `::keyword` search over `src/`, `script/`, and `bin/` found no
reader:

- The LOOP section of `resources/seon/schema.edn` declares
  `:seon.cluster.loop/evaluation`. The live evaluator returns the stricter
  `:seon.sci.eval/evaluation` in the EVAL section of that resource, which is
  consumed by `src/seon/sci/eval.clj:1255` and
  `src/seon/cluster/loop.cljc:1308`. No source or schema references the loop
  copy.
- The DATA section of `resources/seon/schema.edn` declares
  `:seon.render.data/window`, including `/entries`, `/total`, and resume
  offsets. Commit `d6399b4b8` deleted the second HTML data floor; the surviving
  floor now creates `:seon.render.value/window` at
  `src/seon/render/value.cljc:86-136`. No current source or schema references
  the old window.
- The BLOCK section of `resources/seon/schema.edn` declares
  `:seon.render.block/band` and says it is a temporary context-contribution
  dependency. Commit `580de2f50` deleted stored band ordering, and the later
  one-walk cut removed the contribution reader. No current schema or source
  references the enum.
- The RENDER section of `resources/seon/schema.edn` declares
  `:seon.render/literal`, but no function contract or schema references that
  key. The live runtime rule is the direct `seon.render/declaration?`
  predicate at `src/seon/render.clj:232-245`; it does not consume the named
  schema.
- The FLOW section of `resources/seon/schema.edn` declares `:seon.flow/future`, whose only
  purpose is its `seon.flow/java-future-generator`. No source contract or
  other schema references the key. The broader opaque-generator defect is
  already owned by [[flow-generators-reuse-one-mutable-sample]]; its repair
  should delete this unused schema/generator pair instead of making a
  readerless generator fresher.

Calibration: the same sweep retained indirect roots that a text-only search
would misclassify, including program AST/arity attribute maps, test-result
entity families, agent context-link families, and dynamically derived AI wire
settings. This finding is limited to the five rows above.

## Owner

The one classpath schema population, with each completed mechanism cut owning
the deletion of its now-unreferenced row. The Flow generator wave owns the
`:seon.flow/future` pair.

## Acceptance

- Delete the five named rows and any child declarations or generator function
  left readerless by those deletions.
- The complete schema population still admits, derives Datahike declarations,
  and publishes current-source rows.
- A recurring reachability check starts from function contracts, entity and
  database-attribute maps, and config dials, then rejects a named schema row
  with no transitive reader.
- The check proves a nonempty root set and retains indirect live rows such as
  program AST/arity maps and dynamically consumed AI setting schemas.
