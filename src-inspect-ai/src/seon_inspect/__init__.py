"""seon-inspect — Seon's benchmarks on the inspect-ai standard harness.

The pod runs as a custom @solver behind POST /solve (Seon owns the loop);
generations score through the REAL Seon oracles (bb parse/structural/phase +
node cljs.js eval/behavioral) behind a fail-loud liveness gate.
"""
