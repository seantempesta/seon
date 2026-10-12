# Fresh install of HEAD: boot and agent evaluation

Question: does a fresh install of HEAD boot and serve agent evaluation? E7 (bc347d583) reported, on its isolated root
after `nuke` then `start`, ten `seon.cluster.squash` declarations refused ("database-effective-config requires
configuration naming its cluster"), then every agent form refused, and `seon.sci.eval/loaded-commit-id` throwing
"The JVM's loaded source commit is unknown."

## Verdict: does not reproduce at aea2a1052

Isolated root `tmp/test-runs/fresh-boot` (not default; default's store under `data/` was never touched), committed
archive of `aea2a10527f75f374150936bd0fd6ab4d4ddc200` (HEAD, contains E7 bc347d583 and M6 7c3077b9e/3da1f6449).
`bin/seon --root R nuke --force` twice (ready 47.3 s, 51.3 s; 75.6 s total for the first), `start` over the store
three times (ready 11.9 s with a cold class cache, then 3.6 s, 3.7 s).

Through the prepl of the isolated JVM (`seon.mcp/call` of `eval_clj`, cluster "default"), on the nuked store and on a
restart over it:

| Form | Mode, agent | Reply |
|---|---|---|
| `(+ 1 2)` | sci, root | `:value 3`, 15 ms, `:seon.repl/request :ms` 180 (first eval request after start) |
| `(+ 1 2)` | sci, `fresh-agent-1` (new branch) | `:value 3`, `:isError false`, request 201-230 ms (custody 112 ms for the new agent) |
| `(defn probe-f [x] (inc x))` | sci, `fresh-agent-1` | `:value {:seon.render.var/symbol user/probe-f}`, one `:uncontracted` finding (expected), 530-556 ms |
| `(#'seon.sci.eval/loaded-commit-id (seon.db/db c))` | jvm, root | the one adoption record's commit uuid, 4 ms |
| `(seon.problems/open-faults (seon.db/db))` | sci, root | `[]` |

No `:seon.error/type` or operation errors in any reply. `seon.cluster.squash` no longer exists at HEAD or at bc347d583
(7c3077b9e renamed it to `seon.merge`), so E7's ten refused declarations cannot be HEAD's. Health after boot is
`:degraded`, not faulted: counts `:seon.problems/malformed-contract 4` (all `seon.program-context-weight/*` private
functions: `jol-vm`, `jvm-retained`, `reference-offsets`, `walk!`, whose docstring-script contracts use anonymous
`[:fn #(...)]` schemas) and `:seon.cache/discipline-warning 1` (`:seon.sci.eval/program-context`, byte-percent 50,
hit-percent 33, task f204e0c69368). Those are not boot or evaluation failures.

`seon.sci.eval/loaded-program` and `loaded-commit-id` are private (`defn-`), and `loaded-program` takes a database
value, so `(seon.sci.eval/loaded-program)` from a JVM form is a compile error ("var is not public"); use
`(#'seon.sci.eval/loaded-program db)`.

## Where the reported messages come from

- `seon.sci.eval/database-effective-config` (src/seon/sci/eval.clj) queries `[:find ?cluster . :where [_ :seon.config/cluster ?cluster]]`
  on the database it is given. If that database holds no `:seon.config/cluster` fact it returns the refusal
  (`seon.error.refusal/diagnostic ... :seon.config/read`) instead of calling `seon.config/effective`. So the message
  means: the database value handed to the base acquisition has no config singleton, i.e. it is a database value that
  predates (or lacks) the config reconcile, for example the value `d/commit-as-db` returns for a commit older than
  the config write, or an empty store.
- `seon.sci.eval/loaded-commit-id` throws "The JVM's loaded source commit is unknown." when the database does not hold
  exactly one entity carrying both `:seon.cluster/name` and `:seon.source/commit-id` and its branch is not
  `:current-src`. The same absent-cluster-record database causes it, so the two reports share one cause: the
  acquisition read a database value without the cluster's adoption and config facts.

I did not isolate which database value E7's root read: its root was not preserved and HEAD's flow, over two nukes and
three restarts, always had both facts. The most likely explanation is that E7's root ran a commit older than 7c3077b9e
(its ten `seon.cluster.squash` declarations are that namespace's functions) or a store boot that had not reconciled
the cluster record yet when its probe ran; neither is evidence of a defect at HEAD.

## Harness note

A JVM started by `bin/seon start` or `nuke` from an agent's Bash tool call dies when that call returns (its process
group is torn down); `status` then reports "No live exact-root JVM" although `start` printed OK. Starting under
`perl -MPOSIX -e 'POSIX::setsid(); exec @ARGV'` and evaluating within the same call keeps it alive. A lane that reads
"every form failed" on its root should first check the JVM pid is alive.
