# Lane fix-status-readable-response (2026-09-24) — STOPPED at the ownership boundary

## Broken (still)

`bin/seon status` refuses (0.365 s wall, refusal from
`seon.cluster.boot/readable-response`, output contract `:seon.operator/response`).
Not fixed: the fix lies outside this lane's owned paths (boot.clj + its test).

## Cause (REPL-proven)

- Producer: `seon.cluster/mcp-runtime-observation` (`src/seon/cluster.clj:695-696`,
  commit de13aa02c) now derives `:seon.dev.mcp/health` as `:faulted` while any
  fault is open.
- Declaration: `resources/seon/schemas/seon.operator.edn:226-227` still declares
  `[:seon.dev.mcp/health [:enum :observed :unknown]]`. de13aa02c changed the
  producer without publishing the schema resource with it.
- The consumer already accepts the state: `script/seon/dev/mcp.clj:116` treats
  `#{:observed :faulted}` as alive.
- No error value is incomplete. The "missing :seon.error/at" message is the
  first alternative's problem of the `:or` (23 problems); see
  `docs/seon/issues/or-contract-refusal-message-names-an-unrelated-alternative.md`.

Probe (jvm, default, read-only, 1228 ms dominated by projection-from-database):

    (let [p (seon.schema/projection-from-database (seon.db/db (seon.cluster.boot/connection "default")))
          obs (seon.cluster/mcp-runtime-observation "default")
          resp (merge (seon.cluster.process/current-identity) {:seon.operator/clusters [obs]})
          v #(seon.schema/valid-candidate-value? p :seon.operator/response %)]
      {:health (:seon.dev.mcp/health obs) :open-faults (count (:seon.dev.mcp/open-faults obs))
       :valid-as-is (v resp)
       :valid-if-observed (v (assoc-in resp [:seon.operator/clusters 0 :seon.dev.mcp/health] :observed))})
    => {:health :faulted, :open-faults 1, :valid-as-is false, :valid-if-observed true}

## Fix needed (for the owner of seon.operator.edn)

`:faulted` is a real bounded state, so declare it: in
`resources/seon/schemas/seon.operator.edn:227` change
`[:enum :observed :unknown]` to `[:enum :observed :faulted :unknown]`
(publication of that schema resource). Regression (boot test, this lane's
file, not written because it cannot pass without the schema): with one open
fault recorded through the canonical fault writer, `request!` `:status`
answers and its cluster entry has health `:faulted` and one open fault.

No source changed; no commit of source; net lines 0. RESET not needed.
