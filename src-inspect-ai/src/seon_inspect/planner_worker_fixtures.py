"""Planner/worker W1 stimulus fixtures — frontier-style plan texts.

planner-worker-design.md §W1: the planner (a frontier model, or the owner)
hands down a SHORT plain-text plan — goal line + 3-4 ordered steps, each
with a falsifiable outcome sentence — as a normal message. The worker's
first job is authoring it as `my.plan` datoms (plan!/reconcile!; either is
the general path). These two texts are the W1 stimulus corpus and the seed
samples for the future `planner_worker` task in this bench.

Shapes follow the exercising-agents doctrine (repo CLAUDE.md): one
long-term-planning-shaped goal, one db-memory-shaped goal. Goals are stated
as OUTCOMES; seon API names are never coached (the no-coaching rule) — the
agent discovers plan!/reconcile! from its own rendered context.

These texts live HERE, with the bench assets, and must never be pasted into
any context block (they are stimulus, not teaching).
"""

# Long-term-planning shape: several steps that must survive interruption;
# the win is continuity (durable steps laid down first, closed as they land).
PLANNING_TEXT = """\
Here is the plan for this session. Lay it down as your durable plan first,
then work it one step at a time, closing each step only when its outcome
actually holds.

# Expense tracker groundwork
Goal: an expense record my human can query across sessions
1. Design a structured shape for expenses (date, amount in cents, category,
   note) — expect: one probe expense stores and reads back intact.
2. Store these three seed expenses: 2026-07-01 coffee 450 food;
   2026-07-03 train 1200 transport; 2026-07-05 book 2300 learning —
   expect: a lookup returns exactly three expenses.
3. Report the total spent per category — expect: food 450, transport 1200,
   learning 2300, derived from the stored rows, not recomputed by hand.
"""

# DB-memory shape: store facts as schema'd data with provenance, then answer
# a later question FROM the store (recall that survives turns/restarts).
DB_MEMORY_TEXT = """\
Plan for this session — record it as your durable plan before doing any of
the work, then execute step by step.

# Team facts you can recall later
Goal: facts stored as structured data that survive a restart, never a
prose note
1. Design a structured shape for team facts (person, role, UTC offset) —
   expect: one probe fact stores and reads back by person.
2. Store these facts: Ana is the backend lead at UTC+2; Bo is the designer
   at UTC-5; Kai is the data engineer at UTC+9 — expect: three facts are
   retrievable.
3. Answer from the stored facts only: who has the lowest UTC offset? —
   expect: the answer is derived by a lookup over the stored facts, and it
   names Bo.
"""

FIXTURES = {
    "planning": PLANNING_TEXT,
    "db-memory": DB_MEMORY_TEXT,
}
