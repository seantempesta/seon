# Jolt `check-form` over our own source (measure only, nothing shipped)

Question: would a per-eval `:warnings` finding from `jolt.passes.types/check-form` be quiet enough on our code
(notes/jolt-debug-features.md, item 3)? Method: bind `jolt.analyzer/*unit-tap*` (the node the REPL's own tap
holds), `load-file` every `src/**/*.clj` (58 files), and run `(check-form (new-unit) node)` on each analyzed
unit. Non-strict. Runtime = the built target/jolt (Jolt pin 4342b73c). Script: scratchpad cf.clj (not kept).

- Forms checked: **3397** units in 58 files; loads: 0 failures; check-form crashes: 0.
- Findings: **0**. False positives: **0** (nothing to be false).
- Control (same path, REPL-style): `(+ 1 :a)` -> "`+` requires a number, but argument 2 is a keyword" (line/column);
  `(inc "x")` and `(let [x 1] (first x))` likewise; `(+ 1 2)` -> none. `(prinltn 1)` -> none (a resolve error,
  not a type finding; the eval error's diagnostics cover it).
- Not measured: cost per form (calls/bytes; AGENTS §5 asks for a cost proof before it ships), strict mode,
  and whether the tap sees AOT-cached units (3397 analyzed suggests runtime-compiled ones are all seen).

Reading: the checker is silent on our code and fires on provable type errors, so a `:warnings` item is
plausible. It is a new item key (Root's gate #4), so it waits for an owner ruling; this paper only informs it.
