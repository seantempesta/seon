# Superseded rulings


### Main's merge verification goes first, as a fact (owner, 2026-10-26 night)
Asked whether main's verification goes first, the owner chose "main first, as a fact": while main's verification runs, a worktree's heavy run waits on it, a dependency the tool reads (a lock main's run holds), never a rule to remember and never a timer. Stated in: AGENTS.md §2 (Heavy runs); the tool on its way (full-run-reds).
Superseded 2026-10-27 by "No lock on main's verification" (docs/decisions.md).
