---
type: issue
status: open
tags: [issue, sci, eval, agent]
---

# The interpreted program graph is PROCESS-wide, so it crosses clusters

Owner ruling 2026-08-01: agents sharing one live program graph is
INTENDED — agent A's improvement must be immediately available to agent
B — **within a cluster**. Cross-cluster sharing is NOT acceptable: each
cluster is a sovereign program, and one JVM hosts many clusters.

The current mechanism cannot honor that boundary. `base-ctx` is a
process-wide `defonce` (`src/seon/sci/eval.clj:147`) and every agent in
every cluster forks it; `sci/fork` shares `sci.lang.Var` objects, so a
`def` of an already-interned name `bindRoot`s through into the shared
base. Falsified live on `default`: after one fork defined
`my.message/send` as a string, the base ctx AND a freshly created fork
both saw the new value. Nothing in that path is cluster-scoped, so two
clusters in one JVM would see each other's redefinitions.

(Today `acquire!` reinstalls program rows per run, which accidentally
masks this for corpus functions. The mask disappears the moment a hot
ctx is parked per agent — the session-persistence slice — so this must
be fixed before that lands.)

Required shape: the interpreted program graph is per CLUSTER, matching
the database boundary that already exists (one cluster = one branch =
one program-fact set). Candidates: one base ctx per cluster branch held
on the cluster handle rather than a process `defonce` (cost: the ~489 ms
/ 3 MB substrate install per cluster instead of per process — measure
it), or a fork-scope rule in our sci fork that makes cluster forks
copy-on-write while intra-cluster forks keep sharing.

Acceptance: two clusters live in ONE JVM; an agent in cluster A
redefines a corpus name; agents in cluster A see the new definition
immediately (the intended sharing) and agents in cluster B still see
the original; a regression covers the class. Related open question
(owner, same session): enforcing namespace-lane ownership so an agent
only writes namespaces it owns (`:seon.cluster.agent/namespace` is
already unique).
