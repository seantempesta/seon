"""seon-inspect — Seon's benchmarks on the inspect-ai standard harness.

A cluster pod runs as a custom @solver behind POST /agents/run (Seon owns
the loop; per-sample isolation = one ephemeral cluster per sample);
generations score through the REAL Seon oracles (bb parse/structural/phase +
node cljs.js eval/behavioral) behind a fail-loud liveness gate.
"""
