<!-- canary: 008C08CB-D5F9-40D0-8114-614283073DE0 -->
You are keeping a small expedition knowledge base in your database. Work in TWO phases, in SEPARATE turns — do not do both in one turn.

Phase 1 — STORE (this turn). Design a schema for supply caches and register each attribute with `schema/register!`, e.g.:

- `(schema/register! :my.cache/name [:string {:seon.db/identity true}])`
- `(schema/register! :my.cache/contents :string)`
- `(schema/register! :my.cache/weight-kg :double)`

Then persist these four facts with ONE `db/transact!` call, e.g. `(db/transact! [{:my.cache/name "..." :my.cache/contents "..." :my.cache/weight-kg 0.0} ...])`:

- cache KESTREL holds 42.5 kg of dried fish
- cache MARMOT holds 17.0 kg of pemmican
- cache TERN holds 8.25 kg of tea
- cache PLOVER holds 3.75 kg of salt

Phase 2 — RECALL (a LATER turn, only after you have SEEN the real interleaved result line for your transact). Answer this question by running a `db/query` Datalog query against the database — compute the answer FROM the query result, do not re-derive it from this prompt: **what is the TOTAL weight in kg of all caches strictly heavier than 10 kg?** For example `(db/query '[:find (sum ?w) . :with ?e :where [?e :my.cache/weight-kg ?w] ...])`. Then report the total to your human with `message/user` and end the task with `complete`, stating the total in both.
