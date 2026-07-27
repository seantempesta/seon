# Diffusion tree

This is a PRESERVED experimental tree by owner directive (2026-07-21).
Preservation means its builds and tests stay working; it is not part of the
main program gates.

The require fence is directional: the main system never requires
`seon.diffusion.*`. Diffusion may require main-system namespaces.
Diffusion-backed providers are
explicit-configuration opt-in only and never activate as a side effect.

Logical membership is `seon.diffusion.bootstrap-cache`, `.gemma`, `.grammar`,
`.retrieval`, `.oracle`, `.scaffold`, `.worker.eval`, and `.worker.parse`. Gemma
self-registers its provider
descriptor and the typeahead step backing when explicitly loaded.

The subsystem owns the `:worker-validator` and `:worker-oracle-eval` builds.
It shares `:bootstrap` until the W5 decision 12. Its focused tests and worker
bundle compiles run in a dedicated diffusion lane, separate from the main
program gates.

Design authority: `docs/prds/sci-execution-runtime/research/namespace-hierarchy-design-2026-07-21.md`
§7 and §10 (NS-1a/NS-1b).
