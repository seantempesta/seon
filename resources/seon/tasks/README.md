# Task files

One file per owning namespace: `resources/seon/tasks/<ns>.edn` holds one EDN vector of
`:seon.task/declaration` maps, without `:seon.task/ns` (the file's name is the owning namespace).
Nothing in them is evaluated.

`(my.root/load-tasks! sys)` reconciles them into main (`seon.task/load-tx`):
- a new entry is created held;
- an entry equal to the declaration main remembers writes nothing, so what main learned
  since (the improved plan) stays;
- a changed entry writes what it states and retracts what it dropped;
- an entry no file declares any more is cancelled, and declaring it again revives it.

Release with `(my.root/run! sys ids)`. A task's proofs include its cost proofs
(`seon.cost/proof`): its performance goal.
