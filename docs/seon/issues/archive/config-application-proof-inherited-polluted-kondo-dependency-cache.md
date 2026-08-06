---
type: issue
status: resolved
severity: blocker
tags: [issue, config, testing, program-graph]
---

# Keep config application discovery on runtime consumers

## Problem

The config application proof added the entire `script` tree to find the fresh
operator's unique config consumer. That also made unrelated developer-tool
sources part of this runtime-application gate. A polluted clj-kondo dependency
entry for one such tool stopped the proof before it could inspect any config
consumer rows.

## Evidence

`seon.config-application-test/every-config-entry-has-an-honest-application-contract`
threw from `seon.fn/build-manifest` with four error findings in
`script/seon/dev/docstring.clj`: unresolved `rewrite-clj.node/sexpr`, `tag`,
`whitespace?`, and `children` calls.

All four functions are public in the pinned rewrite-clj source at commit
`60782e501aaf312cb90c9ff0bee05d5da5125563`. The shared
`.clj-kondo/.cache/v1/cljc/rewrite-clj.node.transit.json` was only 131 bytes
and carried no definitions. `bin/lint --kondo script`, whose clj-kondo call
uses `--cache false`, reported zero errors.

The fresh operator is the only script source with a config family not already
consumed under `src`: it reads
`:seon.config.operator/event-silence-backstop-ms`. A normal cached
`seon.fn/build-manifest` request over `src` plus
`script/seon/fresh_operator.clj` completed with 82 artifacts and 2,106 rows.

## Owner

The config application test's source selection owns the runtime consumers this
proof inspects. Production manifest analysis continues to use its dependency
cache and normal blocking findings.

## Acceptance

- The proof still derives function and keyword rows from `src` and the fresh
  operator source under `script`.
- Unrelated developer-tool sources cannot add their findings to the runtime
  application proof.
- Real first-party syntax, name, and arity errors remain blocking.
- `bin/test seon.config-application-test` passes with nonzero tests and
  assertions.

## Resolution

Resolved in the path-limited commit containing this note. `source-rows` keeps
the complete `src` graph and the exact operator source that introduced the
script-owned config application boundary. It no longer indexes unrelated
developer tools, and it retains the production analyzer's dependency context
and blocking-finding behavior.

`bin/test seon.config-application-test` passed 4 tests and 18 assertions with
zero failures and zero errors.
