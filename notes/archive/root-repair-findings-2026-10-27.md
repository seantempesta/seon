# Root repair findings: four staged merge failures (2026-10-27)

Worktree `jolt/root-repair-playbook` (HEAD bd59a21d5b), binary `target/seon` from 07:50. Its own root:
`SEON_PROFILE=scripted bin/root start` (server 9540, dashboard 9541, store `runs/store`). For failure 4 the root was
restarted with `SEON_SOURCE_ROOT=<scratch clone>`. The shared `refs/heads/jolt/seon-merges` was 537fce7e7b both before
and after. The root is stopped, nothing of it is still running, and the scratch clone is deleted. `runs/store` (192 MB) is kept
as evidence and is gitignored. No tracked file was edited.

## How to stage (root REPL)

Each request is one line: `printf '{:op :eval :agent "root" :code "..."}\n' | nc -w 30 127.0.0.1 9540`, with every newline in the code
escaped as `\n`. Raw newlines turn each line into its own request, and each of those comes back as a contract defect of
`seon.server/answer` instead of a single "does not read" error (see gap G11).

```clojure
(require '[seon.fixtures :as fx] '[seon.task :as task] '[seon.root :as c] '[my.root :as root] 'seon.await '[seon.source :as source])
(defn stage! [k tns]   ; seon.e2e/release! (private) as a root form
  (let [row (get fx/scenarios k) db (fn [] @(:conn sys))
        prog (mapv #(select-keys @(c/transact-main! sys %) [:ok :datoms]) (fx/program-txs (db) (:specs row)))
        ds (mapv #(assoc % :seon.task/config {:seon.config.agent/max-turns 2}) (fx/decls (:specs row) tns nil))
        cr (select-keys (root/create! sys ds) [:ok :datoms :problems])
        af (when (seq (:after row)) (select-keys @(c/transact-main! sys (vec (for [[m n] (:after row)] [:db/add [:seon.task/id (str tns "/" m)] :seon.task/needs [:seon.task/id (str tns "/" n)]]))) [:ok]))]
    {:prog prog :create cr :after af :ids (mapv #(task/task-id tns (:seon.task/name %)) ds)}))
(root/run! sys ids)
(seon.await/await-quiet sys (:max-tx @(:conn sys)))   ; answers {:t :root-causes}
```
Staging pitfall: the scripted replies are keyed `"<scenario>/<task>"` (`seon.fixtures/all-replies`, test/seon/fixtures.clj:244-247).
The task namespace must therefore be the scenario's own name. Any other name gets the reply of an unrelated scenario: my first `:semantic`
run under `sem` was given another scenario's "a" and parked at `:cap`. A second staging of the same scenario needs
`(alter-var-root #'seon.fixtures/all-replies ...)` to copy the replies under a new namespace. I did this for c2..c5.

---

## 1. Conflicting branches: merge refused (`:hairy`)

**Staging:** `(stage! :hairy 'hairy)`, then `run!` on all four tasks. Quiet came after 37 s:
```
{:t 536871146 :root-causes [{:task "hairy/b-merge" :run "hairy/b-merge#1" :blocks []
  :cause "run hairy/b-merge#1 ended merge-failed (4 conflicts > :seon.config.merge/max-conflicts 3): unmerged [:var app.m/f1]; ... f4"}]}
```
**Seeing it from root:**
- `(my.root/problems sys)` returns `:merge {:n 1}` and `:failed {:n 1}` rows for the same run. Each carries `:reason`, `:unmerged`, and `:evidence :conflicts` with base, ours and theirs.
  It took 217 ms and allocated 212 MB.
- `(select-keys (get (task/runs @(:conn sys)) "hairy/b-merge#1") [:seon.run/status :seon.run/outcome :seon.run/conflicts :seon.run/unmerged])`
  returns `:status :closed :outcome :merge-failed`. It holds four `:both-modified` conflicts, each with its `:src` on all three sides. This is the best read.
- Page `/`: shows "1 root causes when it went quiet ... hairy/b-merge: run ... ended merge-failed (4 conflicts > ...)" and a footer
  "problems ... failed 1 merge 1". The conflicts' content does not appear on the page, and nothing links to the run or the task. `/root/debug` shows only the counts.

**Repair (worked):**
```
printf '{:op :attach :task "hairy/b-merge"}\n' | nc -w 60 127.0.0.1 9540
=> {:agent :a5, :task "hairy/b-merge", :run "hairy/b-merge#2"}
a5: (mapv (juxt :key :kind) (my.branch/unmerged))
=> [[[:var app.m/f1] :both-modified] ... f4]          ; the held run's unmerged entries are still on the branch
a5: (doseq [e (my.branch/unmerged)] (my.branch/resolve! (second (:key e)) :theirs))
    [(my.branch/unmerged) (app.m/f1 10) (my.branch/changes)]
=> [[] 11 {}]
printf '{:op :done :agent "a5"}\n' | nc -w 300 127.0.0.1 9540
=> {:run "hairy/b-merge#2", :status :merged, :outcome :green}
```
After the repair, both the `:merge` rows and the `:failed` row read `:open? false`. Attaching to a held task works, because attach writes `:running` itself
(src/seon/server.clj:88), and `seon_done`'s verdict is clear.

**Gaps:**
- G1 (medium): attach leaves the task on model "outside" permanently (`task/env-tx id {:model "outside"}`, src/seon/server.clj:88).
  After the hand-back, `(task/task db "semantic/b-merge")` still shows `:seon.task/model "outside"`. Any later run of that task runs
  "outside" with nothing driving it. Failure 3 shows the effect: a respawned `:behind` run sat `:idle` and quiet never came. Fix: attach
  should set the outside model on the run's env only, or `:done` should restore the task's model. Until then the playbook needs
  `[:db/retract [:seon.task/id T] :seon.task/model "outside"]` after an attach.
- G2 (low): root has no conflict function of its own. `my.branch/unmerged`, `resolve!` and `merge-main!` run only in an agent (src/my/branch.clj).
  The path is attach, then eval in that agent, then done. It worked, so this is a doc item rather than a missing tool.
- G3 (low): a held merge appears twice in `my.root/problems`, once under `:merge` and once under `:failed`. On the page, the footer counts closed rows too.
  After the repairs it read "failed 3 merge 13" when 1 and 4 were open, which reads as unrepaired.

## 2. Green on its branch, red once merged (`:semantic`)

**Staging:** `(stage! :semantic 'semantic)`, releasing `a`, `a-merge` and `b` only. The scripted `b-merge` agent fixes `six` itself, so I
left `b-merge` held and attached to it as the outside agent who forgets to merge main:
```
attach semantic/b-merge => {:agent :a11 :run "semantic/b-merge#1"}
a11: [(app.m/scale 3) (app.m/six) (my.branch/changes)]  => [9 9 {:changed [app.m/six]}]
done a11 => {:run "semantic/b-merge#1", :status :closed, :outcome :red, :reason "proof-unknown app.proofs/a-test",
             :held {:seon.task/failure {:reason "run semantic/b-merge#1 ended red (proof-unknown app.proofs/a-test): ..."}
                    :seon.task/evidence {:conflicts [] :unmerged [] :red [] ...}}}
```
The run's `:seon.run/results` read `{app.proofs/b-test :pass}`. The judge ran the proofs on the branch as committed, which never merged main and still has
`scale` doubling. The agent's REPL meanwhile already runs main's `scale` (9), so `(app.m/six)` returns 9 at the REPL while b-test
"passes". The task was held only because a-test (accumulated from task a's plan) was never run.

**Seeing it:** the root cause and the `:failed` row both say "proof-unknown app.proofs/a-test". Nothing mentions b-test, `six`, or main being unmerged.

**Repair (worked):**
```
attach semantic/b-merge => {:agent :a12 :run "semantic/b-merge#2"}
a12: (my.branch/merge-main!)  => {:ok true :conflicts [] :applied [[:form ...] [:form ...] [:var app.m/scale]] :unmerged []}
a12: (clojure.test/run-tests 'app.proofs)  => FAIL in (b-test) expected: (= 6 (app.m/six)) actual: (not (= 6 9))
a12: (in-ns 'app.m) (defn six {:malli/schema [:=> [:cat] :int]} [] (* 2 (/ (scale 3) 3))) (in-ns 'user) [(app.m/six) (my.branch/changes)]
     => [6 {:changed [app.m/six]}]
done a12 => {:run "semantic/b-merge#2", :status :merged, :outcome :green}
root: [(app.m/scale 3) (app.m/six)] => [9 6]
```
**Gaps:**
- G4 (medium): an attached agent's REPL and its branch db disagree until `merge-main!`. The process holds main's program plus the branch's changes,
  while the branch db still holds the old base (src/seon/agent.clj init!, as docs/tasks.md describes). `:done` judges the branch db, so the REPL said 9 while the proof said pass.
  The held reason ("proof-unknown a-test") does not lead the repairer to "merge main first". Fix: for a `:merge` task, `seon_done`, or the judge,
  should refuse a branch whose merge base is behind main with the writer's `:seon.merge/behind` message and its fix ("merge main into the branch first"),
  before judging the proofs. Alternatively, attach to a merge task should run `my.branch/merge-main!` the way a turn loop does (seon.turn/work!).
- Not a gap: a red merged program never reached main. The writer refuses a behind branch (src/seon/writer.clj:86-91).

## 3. Held task and `:behind` run

**3a, held (worked).** Staging: copy `:conflict` to namespace `c2`, then
`@(c/transact-main! sys [{:seon.task/id "c2/b-merge" :seon.config.merge/max-conflicts 0}])` before `run!`. (`:seon.task/config` is not
an attribute; the writer refused it with "Bad entity attribute :seon.task/config". The limits are flat keys on the task.)
Quiet: root cause "run c2/b-merge#1 ended merge-failed (1 conflicts > :seon.config.merge/max-conflicts 0)". The page showed it.
```
(root/run! sys ["c2/b-merge"])   => {:ok true}; quiet => same cause, run c2/b-merge#2 (released unchanged: held again, as it should)
[@(c/transact-main! sys [{:seon.task/id "c2/b-merge" :seon.config.merge/max-conflicts 3}]) (root/run! sys ["c2/b-merge"])]
=> [{:ok true ...} {:ok true}]; quiet => {:root-causes []}; runs: #1 #2 :closed :merge-failed, #3 :merged :green
```
**3b, `:behind` stuck (not repairable, cancelled).** Staging: copy `:conflict` to `c4`, release everything except b-merge, then
attach b-merge (a30). In a30: `merge-main!` and resolve `:theirs`. Root then moves main with
`@(c/transact-main! sys (source/text-tx @(:conn sys) 'app.z "(defn z {:malli/schema [:=> [:cat :int] :int]} [x] x)" nil))`, and done a30
answers `{:status :behind :reason "main changed 2 key(s) since the branch's merge base: merge main into the branch first"}`.
- The reconciler respawns the task at once as run #2, agent a31, on model "outside" (G1). Nobody drives that run, so
  `await-quiet` never answered (my 120 s nc timed out). The page showed "a31 idle" and no root cause, because root causes appear only at quiescence.
- `(root/run! sys ["c4/b-merge"])` gave `:not-held [... "task c4/b-merge is running, not held: nothing to release"]`.
  `seon.root/release!` writes `:running` with no check (src/seon/root.clj:1034), which is a no-op here.
- I drove a31 by hand: `merge-main!` applied `[:form "app.z/25a8..."] [:var app.z/z]` with no conflicts, and done returned **`:behind` again,
  "main changed 1 key(s)"**. Runs #3 and #4 did the same. Redefining `app.z/z` in the agent did not help either.
- The cause, read from root:
  ```
  (let [conn (:conn sys) b :main/task-c4-b head (seon.db/head conn b) base (seon.db/branch-base conn (seon.db/conn-main conn) b)
        k [[:form "app.z/25a8fd53-0b71-5886-b516-3c1a2477999c"]] f (fn [db] (map :ns (vals (seon.merge/program-of db k #{}))))]
    {:base (f base) :head (f head) :main (f @conn)})
  => {:base (nil) :head (nil) :main (app.z)}
  ```
  The form of a namespace that is **new on main** is carried into the branch without its namespace (`:ns nil`), so main always differs at
  that key, and every merge after that is `:behind`.
  A control case, c5, used the same steps but with a main change to an already existing namespace. It returned `:behind` once, then merged on the next run.
- Repair: `(root/cancel! sys ["c4/b-merge"])` killed run #5 (agent a34), and quiet came with no root causes. The branch's work was lost.
  b's change was empty after resolving `:theirs`, so nothing was lost this time.

**Gaps:**
- G5 (high): perpetual `:behind` after main gains a new namespace. `my.branch/merge-main!` reports the form applied, and the writer still
  refuses it (src/seon/writer.clj:86-91 counts `:theirs`). The carry is in `seon.merge/merge-tx` (src/seon/merge.clj:275) or the
  `:ns` read of `seon.merge/program-of` (src/seon/merge.clj:197), and the form's namespace link is not carried. Caveat: I staged main's change through `source/text-tx` with
  a nil file. A namespace a merge makes from an agent's REPL (the `:newns` scenario) should be checked the same way. Fix it at the carry,
  with a proof that `merge-main!` followed by the octopus merge is not `:behind`.
- G6 (high): nothing bounds `:behind` reruns. A `:behind` run is green, so `:seon.config.merge/max-merge-turns` never counts it, and the task stays running
  and ready (src/seon/reconcile.clj:953). Under a model, G5 loops forever and spends turns, and quiet never comes, so `root-causes` never names it.
  Fix: a `:behind` of a branch that merged main at the head it was refused at is a defect fact, and the task is held with that evidence.
- G7 (medium): root has no "stop this run, keep the task, hold it" function. `run!` refuses a running task, `release!` cannot move it,
  and `cancel!` is final (its rows stay open: G8). A `my.root/hold!` (execution `:held` by compare-and-swap from `:running`, killing the live run, as `hold-tx` does)
  would let root take a looping task back without losing it.
- G8 (medium): cancelling does not close a task's problem rows. The `:red` rows of the cancelled `sem/a#1` and `sem/b#1`, and the `:merge` rows of the
  cancelled `c4/b-merge#1..4`, stay `:open? true` (src/seon/problems.clj:538 and the run-row path). The page footer keeps showing them.

## 4. Write-back to git fails (`:seon.run/commit-error`)

**Staging:** I stopped the root, ran `git clone --local` of this worktree into the scratchpad, and restarted with `SEON_PROFILE=scripted SEON_SOURCE_ROOT=<clone> bin/root start`. Changing
the source root needs this restart: the source is read into root's system at start (src/seon/root.clj:888). I then locked the ref with
`touch <clone>/.git/refs/heads/jolt/seon-merges.lock` and ran `(stage! :loop 'loop)` + `run!`.
Quiet came with **no root causes**. The run reads `:status :merged`, and `(seon.generation/generation)` answers 2 in root. The run also carries:
```
:seon.run/commit-error "git update-ref refs/heads/jolt/seon-merges 3b096cce... 0000...: fatal: ... cannot lock ref ...: Unable to create '.../seon-merges.lock': File exists. ..."
```
**Seeing it:** `my.root/problems` has an `:effect` row (`:operation seon.fx/git-commit`, `:type :seon.fx/io-error`, `:open? true`) with git's message.
That row has `:last-task nil` and does not name the run whose commit was lost. The page shows only "effect 1" in the footer. The task reads as done/merged.

**Repair (worked by hand, with no tool):** I removed the lock, then rebuilt the reconciler's `:main-read` and `:committed` steps (src/seon/reconcile.clj:982-1000) at root:
```clojure
(let [db @(:conn sys) paths [(source/ns-file db 'seon.generation)] files (source/files db paths)
      members [{:task "loop/gen-merge" :run "loop/gen-merge#1"}]
      r @(c/effect! sys :git {:dir (seon.config/value :seon.config.source/root) :branch (seon.config/value :seon.config.source/branch)
                              :files files :message (source/commit-message members)})]
  [paths (count files) (select-keys r [:value :seon.error/message])
   (when-let [sha (get-in r [:value :sha])]
     @(c/transact-main! sys (seon.error/asked-tx db r :seon.run/commit [{:seon.run/id "loop/gen-merge#1" :seon.run/commit sha}])))])
=> [["src/seon/generation.clj"] 1 {:value {:files 1 :sha "56cf4505..."}} {:ok true ...}]
```
After this, the clone's `jolt/seon-merges` has commit 56cf450586 "Seon merge: loop/gen-merge" (`- [] 1)` / `+ [] 2)`), and the `:effect` row reads `:open? false`.
**Gaps:**
- G9 (high): **a worktree root writes back to the shared merges branch.** `bin/root` defaults `SEON_SOURCE_ROOT` to its own checkout
  (bin/root:27), and the branch is `"jolt/seon-merges"` for every checkout (resources/seon/config/default.edn:332). `seon.fx/git-commit` moves
  `refs/heads/<branch>` in that checkout's repository (src/seon/fx.clj:290-301), and worktrees share refs. So the first Seon-source merge on
  any worktree root moves the main tree's `jolt/seon-merges`, the branch the merges worktree has checked out. I avoided this only
  because app.* forms have no file, and by using a clone for failure 4. Fix: `bin/worktree --ports` should write a per-worktree
  `:seon.config.source/branch` (for example `jolt/seon-merges-<name>`) into `config/local.edn`. Or git-commit should refuse a ref that another worktree has checked out
  (`git worktree list --porcelain`).
- G10 (high): **no tool retries a write-back, and a failed one is silently lost.** The reconciler records the error and moves on
  (src/seon/reconcile.clj:996). The next merge's commit writes only its own paths onto the branch head (src/seon/reconcile.clj:939-944,
  src/seon/fx.clj:290-295), so the lost file stays stale on the merges branch while the `:effect` row closes at that next sha. Nothing at quiescence names
  it: `root-causes` covers tasks only. Fix: a `my.root/write-back!` taking run ids, built from the reconciler's `:main-read`
  and `:committed` steps (the form above). Also, a merged run with a `:seon.run/commit-error` and no later `:seon.run/commit` should be a root cause.
- G10b (low): after a successful retry, the run holds both `:seon.run/commit` and the stale `:seon.run/commit-error`. Nothing retracts the error.
- G10c (low): every merge of a namespace with no file records a commit-error ("these changed forms name no source file ...") whose
  listing names each key twice (hairy/a-merge#1: f1..f4 each repeated, src/seon/reconcile.clj:526-531).

## Other

- G11 (low): the server answers a multi-line request with one `:seon.server/defect` per line, each a contract error of
  `seon.server/answer`, plus "unknown op " (src/seon/server.clj:238-260). The first line's "EOF while reading string" is the only useful
  one. Fix: a line that does not read as a map should answer once, naming the rule "one request per line, newlines escaped".
- G12 (medium, cost): reads that root uses to repair are heavy on a store of about 25 tasks. `my.root/problems` took between 66 ms / 100 MB and 1,063 ms / 1.7 GB allocated,
  and `seon.server/task-summaries` took about 560 ms / 850-915 MB per call.
- The page: root causes show only at quiescence, so a run that never ends (3b) shows nothing but "a31 idle". Nothing on the page links a cause to its run
  or task, and the conflicts' content is not on it.
