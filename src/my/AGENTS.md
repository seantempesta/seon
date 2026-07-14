# src/my — the agent-owned composition toolkit

**Read before editing:** `docs/seon/architecture/toolkit.md` (the function
catalog + the four shared shapes), `data-model.md` (the `my.kb`/`my.todo`
schemas + data-agent-ref scoping). Skills: `ui-canvas`,
`data-oriented-clojure`.

These namespaces are agent-facing: current namespace source renders in full,
while home-required namespaces render as compact cards unless selected by the
one context policy. Composition functions still need complete, useful worked
examples when relevant. That means:

- **Every line here is agent-facing teaching material.** Code must be the
  cleanest possible worked example of the house style: namespaced keys,
  `schema/register!` first, errors-as-values, derive-don't-store. A hack
  here gets imitated by every agent, forever.
- **Docstrings are agent-facing** — line 1 is a complete ≤72-char sentence
  ending in punctuation (the compact-card summary). True current-state, no
  dates/issue refs.
- **Keep `register!` calls in the file** — agents learn the schema pattern
  from seeing them.
- **Token weight is real**: this corpus is eligible context. Every fn must
  pull measurable weight; new functions need drive evidence they get used.
- **New nses must be required into the boot build** (`client.cljs`) or they
  index with ZERO fns and render name-only.
- Agents reach these functions by FULL qualification (`my.data/…`) or a real
  `:require` alias — never home-ns aliases.

Current: `data` (aggregation), `ui` (static hiccup), `canvas` (interactive
controls wired to agent-defined handlers), `kb` (the database-fact worked
manual—`remember` writes and `recall` queries), `plan` (durable plan trees,
dependencies, active focus, and reconciliation), `ns` (program-graph function
listing through the one compact-card renderer), `skills` (imported skill
facts), and `blob` (the content-addressed
disk tier — SHA-256-named files under `<cluster>/blobs/`, paged reads; see
`docs/seon/architecture/observability.md`). `my.blob` IS required into the
boot build (`client.cljs`) and aliased as `blob` in `home-requires`. A
single eval form carries only ~2K tokens of literal content — `put!` in
chunks, then `concat!` the hashes into ONE canonical blob.
