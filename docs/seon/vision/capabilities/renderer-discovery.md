---
type: capability
status: complete
tags: [vision, web]
---
# Automatic Renderer Discovery

The system discovers how to render data by finding functions whose schemas match. No renderer registration ceremony -- write a function with `:seon.render/html` in its output schema and it is found automatically. This is a concrete instance of the vision's "one discovery mechanism for all use cases."

## What Exists

- `resolve-renderer` delegates to `gq/functions-with-output-key` (graph query)
- Functions with `:seon.render/html` in output schema are auto-discovered
- Specificity algorithm: most required keys matched, namespace proximity tiebreak
- Cache invalidated on code changes triggers re-render
- Legacy `*renderers` atom fully removed

## Gaps

Two dispatch mechanisms coexist (renderer resolution and direct route handlers). Render html output still typed as `:any`.

## Related

- Components: [[components/renderer]], [[components/code-graph]]
- PRDs: `prds/graph-cleanup/prd`, `prds/render-pipeline/prd`
- Issues: [[issues/archive/overlap-three-rendering]], [[issues/archive/any-in-render-html]]
