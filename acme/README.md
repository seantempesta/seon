# Acme — the third-party consumer harness

"Acme" is the codename for a downstream consumer that builds a product on
Seon. This directory is a real, port-isolated reproduction of that
deployment: its own `deps.edn` project, consumed by the pod via
`SEON_EXTRA_SRC` (Path B — `docs/seon/components/extra-src.md`), booted on
ports that never collide with the live seon dev cluster.

It exists to reproduce and validate the two consumer-facing bugs, and to be
the place we iterate on the "consume Seon without forking it" story.

## What it exercises

- **Source indexing + context (BUG B)** — `acme.widget` / `acme.helpers` /
  `acme.brand` are the third party's OWN namespaces. `acme.pod` (the
  `SEON_EXTRA_PRELOAD` entry ns) registers them via `(reset!
  seon.client/!extra-core-vars …)`. Omit that one call and the entire
  surface is silently invisible — that's the bug.
  The same namespace exposes `-main` for an optimized package build; after its
  load-time registration it delegates to `seon.client/-main`. Development uses
  it as a preload, while production uses it as the real Shadow entrypoint, so
  downstream code cannot be removed as unreachable.
- **Execution entry** — `SEON_EXTRA_EXECUTION_MAIN=acme.execution/-main`
  keeps the same downstream functions reachable in each optimized isolated
  execution child without pulling the pod and web startup graph into it.
- **Live canvas via SCI (BUG A)** — `acme.widget/dash` is a correctly
  `:require`-d renderer that calls `acme.helpers/format-count`, an UNSPECCED
  helper. It renders, but falls off the SCI-bounded path onto the unbounded
  compiled path because `expose-ns` only enumerates SPECCED `:seon.fn`
  rows. Wire it with:

  ```clojure
  (seon.db/transact!
    {:seon.db/tx-data [{:seon.agent/id "<id>"
                        :seon.render.canvas/content 'acme.widget/dash}]})
  ```

- **Function override** — `acme.overrides` `set!`s
  `seon.render.canvas/error-response` (late-binding through the global
  var slot) for a calm broken-surface card. Normal startup renders the healthy
  dashboard; explicitly pin `acme.widget/broken-surface` to exercise the error
  seam. No seon-src edit.
- **CSS / branding** — `acme/branding/acme.css` (via `SEON_BRAND_CSS`) plus
  `SEON_BRAND_NAME` / `SEON_BRAND_TAGLINE`, all set by `bin/acme`.

## Operator status

The downstream wrapper now contributes only ACME target data. The shared
operator owns its artifact, watcher, Bun pod, readiness, and shutdown graph.
The default operator's JVM writer serves ACME's isolated database through the
same typed database protocol.
The preserved legacy `store` databases are deliberately rejected; they must be
archived and read back before the current `db` layout can be created.

```bash
bin/acme status --edn          # identity, endpoints, artifact, ownership state
bin/acme up                    # complete target; refuses preserved legacy layout
bin/acme restart               # rebuild and reconcile the complete target
bin/acme logs pod --follow
bin/acme down
```

After the preservation gate is closed, a scoped current-layout reset is:

```bash
bin/acme cluster reset acme
```

## Isolation with one JVM writer

| | live default | acme |
|---|---|---|
| pod HTTP | 7890 | 7980 |
| JVM writer | default authority | same default authority |
| database | `data/clusters/default/db` | `data/clusters/acme/db` |
| database request socket | default writer socket | same default writer socket |
| supervisor state | `tmp/proc` | `tmp/proc-acme` |
| logs | `logs/` | `logs/acme/` |

`bin/acme` is pure target-data composition over `bin/seon`; it does not expose
the retired `build`, `start`, `stop`, `tail`, or named-process commands.
