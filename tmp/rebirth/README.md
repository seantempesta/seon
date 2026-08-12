# Rebirth capability probe

`probe.clj` is a probe-only harness for rulings 45–47. It writes no production
source. It starts an isolated root, waits for complete generated openings,
authors artifacts through ordinary agent eval, evolves a scratch-only declared
plan shape across three turns, and derives the same empty-history rebirth from
two database branches forked at one commit.

Run only after the issue
`docs/seon/issues/generated-opening-live-pull-does-not-return-after-help.md`
is closed by its owning lane:

```bash
clojure -M:test -e '(load-file "tmp/rebirth/probe.clj") (rebirth.probe/-main "tmp/rebirth/scratch-root")'
```

The complete EDN evidence is written beneath that isolated root as
`rebirth-evidence.edn`. The committed owner-facing interpretation belongs at
`docs/prds/sci-execution-runtime/research/rebirth-capability-proof-2026-08-12.md`.
