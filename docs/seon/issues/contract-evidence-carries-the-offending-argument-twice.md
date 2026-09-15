
## Re-observed 2026-09-15 21:10Z — the count is missing from the count refusal

`(seon.problems/problems db)` on default (jvm mode) was refused with
"seon.problems/problems refused argument count at []: expected the declared
arglists, got an argument count of. Fix: Call one of the declared arglists."
The sentence omits the count and never names the declared arglists it tells
the caller to use. Same class: the grammar's "got <value>" slot rendered
nothing for an integer. Belongs to the refusal-grammar lane's regression set.
