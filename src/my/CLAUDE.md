# src/my — the agent-owned toolkit (renders INTO agent context, in full)

**Read before editing:** `docs/seon/architecture/toolkit.md` (the function
catalog + the four shared shapes), `data-model.md` (the `my.kb`/`my.todo`
schemas + data-agent-ref scoping). Skills: `ui-canvas`,
`data-oriented-clojure`.

These namespaces are special: **their full source renders into every
agent's context** (the render-prominence law — a composition function's value
IS its worked example). That means:

- **Every line here is agent-facing teaching material.** Code must be the
  cleanest possible worked example of the house style: namespaced keys,
  `schema/register!` first, errors-as-values, derive-don't-store. A hack
  here gets imitated by every agent, forever.
- **Docstrings render everywhere** — line 1 is a complete ≤72-char sentence
  ending in punctuation (the compact-card summary). True current-state, no
  dates/issue refs.
- **Keep `register!` calls in the file** — agents learn the schema pattern
  from seeing them.
- **Token weight is real**: this corpus is in every prompt. Every fn must
  pull measurable weight; new functions need drive evidence they get used.
- **New nses must be required into the boot build** (`client.cljs`) or they
  index with ZERO fns and render name-only.
- Agents reach these functions by FULL qualification (`my.data/…`) or a real
  `:require` alias — never home-ns aliases.

Current: `data` (aggregation), `ui` (static hiccup), `tile` (interactive
controls wired to agent-defined handlers), `kb` (the DB-memory worked
manual — `remember` store + `recall` ask), `ns` (program-graph fn listing
via the ONE compact-card renderer), `skills` (the skill catalog blocks),
`blob` (the content-addressed
disk tier — SHA-256-named files under `<cluster>/blobs/`, paged reads; see
`docs/seon/architecture/observability.md`). `my.blob` IS required into the
boot build (`client.cljs`) and aliased as `blob` in `home-requires`. A
single eval form carries only ~2K tokens of literal content — `put!` in
chunks, then `concat!` the hashes into ONE canonical blob.
