---
type: issue
status: open
tags: [packages, claimant, classpath, sci]
---

# A deps-ecosystem package install cannot reach a running claimant

## Observed

`seon.packages/deps-manifest` (`src/seon/packages.cljc:307-322`) derives a
byte-stable `deps.edn` string from ledger rows, and
`script/seon/dev/cluster.clj:201-219` writes it to
`<cluster>/packages/deps.edn`. Writing that file is the whole install effect
for a `:seon.packages.host/jvm` row.

Nothing loads it into a live process:

- `rg -n 'add-lib|add-libs|DynamicClassLoader|addURL|clojure.tools.deps' src/ script/ deps.edn`
  returns nothing. There is no dynamic classpath mechanism in the tree.
- The claimant JVM's classpath is fixed at process start, so a jar named by a
  freshly written `deps.edn` is not resolvable by `requiring-resolve` in that
  process.
- SCI cannot reach it either: `registry-load-fn`
  (`src/seon/host/context.clj:613-633`) resolves an unknown lib from the
  wrapper registry, then from `:seon.ns/source` in the corpus, then returns
  nil. A classpath namespace is neither.

`seon.packages/validate-install` and `install-plan` have no caller in `src/`
at all — only `script/seon/dev/cluster.clj` requires the namespace, and only
to write empty manifests at cluster creation.

## Why it matters

Any design that lets an agent install a JVM package and then call it in the
same cluster lifetime depends on this path existing. Today the only way a
newly installed deps coordinate becomes callable is a full claimant restart,
which costs the measured 9232 ms boot and discards every process-local SCI
context and in-flight run.

## Owner

`seon.packages` (ledger + manifests) and `seon.host.context` (SCI resolution).

## Acceptance

Either:

1. an installed deps row becomes callable in the already-running claimant, with
   a live proof that a coordinate absent at boot resolves after install; or
2. the ledger states plainly that a deps install requires a cluster restart,
   and the install path returns that as data rather than implying immediacy.

Recorded 2026-07-25 during the one-tier package-capability design review.
