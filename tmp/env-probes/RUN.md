# Phase 0 environment probes — how to run

Load-only JVM evaluation. No cluster, no database, no test framework: each
probe namespace exposes `run`, which returns a `:probe/verdict` value.

```sh
# Probe A — environment on fork across thread hops (24 forks x 8 rounds)
clojure -M:dev -e '(load-file "tmp/env-probes/env_probes/probe_a_env_on_fork.clj")
                   (clojure.pprint/pprint (env-probes.probe-a-env-on-fork/run))'

# Probe B — is work handed across a thread from an armed eval armed?
clojure -M:dev -e '(load-file "tmp/env-probes/env_probes/probe_b_interrupt_arm.clj")
                   (clojure.pprint/pprint (env-probes.probe-b-interrupt-arm/run))'
```

`run` takes an optional argument map:

- probe A — `{:probe/fork-count 24 :probe/rounds 8}`;
- probe B — `{:probe/time-limit-ms 300 :probe/observe-ms 1500}`.

Recorded outputs from the 2026-08-07 run live beside the sources as
`probe-a-output.edn` and `probe-b-output.edn`; the durable analysis is
`docs/prds/sci-execution-runtime/research/env-phase0-fork-carriage-2026-08-07.md`.

The nested `env_probes/` directory exists so the namespace name matches the
file path for clj-kondo; `tmp/` itself is gitignored, so these files were
added with `git add -f`.
