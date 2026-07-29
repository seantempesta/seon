---
type: issue
status: open
severity: friction
tags: [issue, config, boot]
---

# Boot cannot select a config manifest, so a cluster cannot choose its provider

## Problem

The standing rule is "an explicitly selected config manifest reconciles its
declared subset into database facts". Nothing selects one at boot:

- `seon.cluster/start!` calls
  `(config/apply! {… :seon.config/manifest (config/defaults) …})` — the shipped
  defaults, unconditionally (`src/seon/cluster.clj:935-939`);
- `:seon.boot/overrides` is a CLOSED schema with no manifest key
  (`src/seon/schema/boot.edn`), so no caller can pass one;
- `config/read-manifest` exists and reads an override manifest over the
  defaults — and has no caller in `src/`.

So the only way to boot a cluster against a different provider, deadline or
context manifest is to redefine `config/defaults` around `start!`. The dials
then land as ordinary facts and everything downstream behaves normally, which
is what makes this friction rather than a wrong design: the mechanism is
right, the seam is missing.

## Evidence

`docs/prds/sci-execution-runtime/research/scripts/generate-code-v0-drive-2026-07-29.clj`
points a scratch cluster at the local Ollama server with

```clojure
(with-redefs [config/defaults (fn [] (merge (shipped-defaults) local-dials))]
  (cluster/start! …))
```

The F4 drives worked around the same gap differently (scoping `ai/targets` at
boot, `docs/prds/sci-execution-runtime/research/f4-drives-2026-07-29.md`),
which is the tell that this is a missing seam and not one drive's convenience:
two independent drives invented two different workarounds for it.

## Expected owner

`seon.cluster/start!` plus `:seon.boot/overrides` in `src/seon/schema/boot.edn`.

## Acceptance criteria

`start!` accepts an optional manifest selection in its bootstrap overrides —
a path resolved through the existing `config/read-manifest`, so the defaults
remain the base and the manifest declares only its subset — and a drive can
point a scratch cluster at a local provider with no `with-redefs` anywhere.
Bootstrap config stays tiny: the selection is a path, never dials.
