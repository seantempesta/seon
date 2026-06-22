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
- **Live tile via SCI (BUG A)** — `acme.widget/dash` is a correctly
  `:require`-d tile that calls `acme.helpers/format-count`, an UNSPECCED
  helper. It renders, but falls off the SCI-bounded path onto the unbounded
  compiled path because `expose-ns` only enumerates SPECCED `:seon.fn`
  rows. Wire it with:

  ```clojure
  (seon.db/transact!
    {:seon.db/tx-data [{:seon.agent/id "<id>"
                        :seon.render.live-tile/content 'acme.widget/dash}]})
  ```

- **Function override** — `acme.overrides` `set!`s
  `seon.render.live-tile/error-response` (late-binding through the global
  var slot) for a calm broken-tile card. No seon-src edit.
- **CSS / branding** — `acme/branding/acme.css` (via `SEON_BRAND_CSS`) plus
  `SEON_BRAND_NAME` / `SEON_BRAND_TAGLINE`, all set by `bin/acme`.

## Boot

```bash
# (optional) export GEMINI_API_KEY=...   for embeddings KNN
bin/acme build                 # one-off compile → out-acme/client/main.js (acme.* baked in)
bin/acme start wire-server     # JVM writer first (sole writer; pod boot is gated on it)
bin/acme start pod             # Node pod on http://127.0.0.1:7980
bin/acme status
bin/acme tail pod
```

`bin/acme build` runs a one-off shadow COMPILE of the `:acme-client` build
(its own output dir, `out-acme/`) — not a second watch, which would collide
with the live cluster's shadow nREPL on `:7889`. Re-run it after editing
`acme/src`.

Fresh world (clean store — needed to reproduce BUG B from zero):

```bash
bin/acme cluster reset default   # wipes data/clusters/acme/store, bounces both
```

## Isolation (zero overlap with the live cluster)

| | live default | acme |
|---|---|---|
| pod HTTP | 7890 | 7980 |
| wire-server REPL | 7891 | 7981 |
| store | `data/clusters/default` | `data/clusters/acme` |
| req/pub sockets | `tmp/seon-cluster-default-*.sock` | `tmp/acme-cluster-*.sock` |
| supervisor state | `tmp/proc` | `tmp/proc-acme` |
| logs | `logs/` | `logs/acme/` |

`bin/acme` is pure env composition over `bin/seon`; the only seon change is
making `PROC_DIR`/`LOG_DIR` env-overridable (byte-identical when unset).
