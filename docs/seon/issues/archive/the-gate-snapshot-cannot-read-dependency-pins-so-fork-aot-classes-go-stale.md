---
type: issue
status: resolved
severity: blocker
created: 2026-09-18
tags: [issue, testing, bin/test, dev-cache, dependencies, wave/contract-gate]
---

# The gate snapshot cannot read dependency pins, so a fork's AOT classes go stale

## The reported problem, and its refutation (2026-09-18)

The note claimed that a cold gate's run root — a `git archive` extraction
(`bin/test:732`) — is not a git repository, so
`git -C <root> ls-files --stage -- reference-code` (`dev_cache.clj:203`)
yields nothing there, the dependency-configuration digest never changes when
a fork commit moves, and the worker loads stale AOT classes.

**Measured, that mechanism does not hold.** `bin/test` symlinks the source
`.git` into the run root immediately after the extraction
(`bin/test:735`), so git takes the run root as its own work tree and the
pathspec resolves at that top level:

- `git -C tmp/test-runs/run.NL3zMp ls-files --stage -- reference-code`
  → exit 0, **8250 bytes**, including
  `160000 e11845bac78e1241bca0766ddc07d978bd63d74a 0 reference-code/datahike`.
- Each worker checkout of that root answers identically (its own `.git`
  symlink chains to the run root's).
- A `git archive` extraction with no `.git` at all answers **exit 128**,
  which `dependency-pins` already refused.

The cache in evidence is not stale either. `tmp/test-runs/run.NL3zMp`'s
`logs/dependency-cache-and-classpath.log` opens with
`seon cache: inputs changed; rebuilding`: that gate BUILT
`target/dev-dependency-classes/25e1db91…` at 14:56, from a
`reference-code/datahike/src/datahike/writer.cljc` whose fix was written at
14:41. `valid-cache` (`dev_cache.clj:284`) re-hashes the recorded source URLs
on every reuse, so a moved fork source invalidates a cache independently of
the pins.

The `seon.db-test/a-throwing-datahike-listener-cannot-strand-a-committed-write`
red in `tmp/orchestrator/gate-results/manifest-merge-gate.log` therefore has
another cause: it reports `:seon.db/write-bound-exceeded` after **255 ms**
against a 250 ms `:seon.config.db/write-time-limit-ms`. The cold gate run
after this fix reproduced it exactly (254 ms, run root
`tmp/test-runs/run.IVzccp`, `dev-cache-digest=25e1db91…`), while
`bin/test-fast --paths dev_cache.clj -- seon.db-test` at the same HEAD and the
same cache digest ran 58 tests green. Same classes, opposite verdicts: the
discriminator is the cold gate's environment, not the pins. That red is
[its own issue](the-cold-gate-misses-the-250-ms-listener-completion-bound.md).

## The real defect, and what closed it

`git ls-files` answers for the PREFIX it runs in. A directory nested under
the repository WITHOUT its own work tree answers exit 0 with **zero bytes** —
measured directly — and `dependency-pins` digested that silence as a pin set.
Nothing checked that the `.git` symlink existed; the digest read absence of
signal as health, exactly one link away from the failure this project keeps
meeting. The derivation also read the source repository's LIVE index, so a
HEAD-exact snapshot could be keyed on pins a concurrent `git add` had moved.

Closed by making the snapshot carry its own dependency identity:

- `bin/test` records the source repository's exact
  `ls-files --stage -- reference-code` bytes into `dependency-pins.txt` beside
  the run root, refusing the snapshot when that output is empty, and
  `populate_checkout_root` copies the file into every worker checkout (the
  file is untracked, so `git ls-files` never names it).
- `dev_cache.clj` prefers those recorded pins, falls back to the root's own
  git index only when it states pins, and otherwise REFUSES with a typed
  `:seon.error` value (`:seon.dev-cache/dependency-pins-unavailable`,
  cause `:seon.dev-cache/no-pin-source`) naming the root and both missing
  sources. No cache is reused or created under unknown pins.
- Regressions in `seon.dev.dependency-cache-test`: recorded pins decide the
  digest, a moved fork commit selects a different cache, a root with neither
  source refuses with the typed value, and a root recording the checkout's
  own pins digests exactly as the checkout.
