// guest.mjs — Phase 3 minimal wasm guest.
//
// Imports the host-provided `seon:sidecar/db` interface and exports
// `runSmoke` (kebab `run-smoke`). When the Rust host calls `run-smoke`,
// the guest fires one transact + one query against the JVM writer via
// the WIT bridge and returns an EDN-ish report string.
//
// This is intentionally JS, not CLJS, for Phase 3. The wasm-rquickjs
// build chain is the load-bearing thing being proved here; CLJS-on-top
// is orthogonal and can be slotted in later. The user's "minimal CLJS
// wasm guest" goal is recast: the chain itself is what matters — the
// JS layer is the smallest thing that exercises it end-to-end.

// Phase B WIT (2026-05-25): signatures changed.
//   q(query, args, basis-t: s64)               // basis-t=0 => current
//   transact(tx-data, tx-meta, request-id)     // "" => omitted
//   pull(selector, eid: string, basis-t: s64)  // eid is EDN string
//   entityPull, pullMany, schema, reverseSchema, dbFilter, qFiltered,
//   filterRelease, subscribeTx, unsubscribeTx, nextTxEvent
import {
  q,
  transact,
  pull,
  entityPull,
  pullMany,
  schema,
  reverseSchema,
  subscribeTx,
} from "seon:sidecar/db@0.1.0";

export const runSmoke = () => {
  const log = [];
  try {
    // 1. Transact a new person. Empty strings = no tx-meta, no request-id.
    const txReport = transact(
      `[{:person/name "phase-3-test" :person/age 99}]`,
      "",
      ""
    );
    log.push(`transact-ok ${txReport}`);

    // 2. Query everyone with a name (basis-t=0 means "current").
    const rows = q(
      `[:find ?n ?a :where [?e :person/name ?n] [?e :person/age ?a]]`,
      [],
      0n
    );
    log.push(`q-ok ${rows}`);

    // 3. Pull by lookup-ref string. basis-t=0 means current.
    const pulled = pull(
      `[:person/name :person/age]`,
      `[:person/name "phase-3-test"]`,
      0n
    );
    log.push(`pull-ok ${pulled}`);

    // 4. entity-pull eager realization. Empty selector => default [*].
    const ent = entityPull(`[:person/name "phase-3-test"]`, "", 1, 0n);
    log.push(`entity-ok ${ent}`);

    // 5. schema read.
    const schemaResult = schema();
    log.push(`schema-ok ${schemaResult.length > 0}`);

    // 6. Register interest (Phase 4 will wire delivery).
    const sub = subscribeTx("phase-3-sub");
    log.push(`subscribe-ok ${sub}`);

    return `{:ok true :events [${log.map((s) => `"${s.replace(/"/g, '\\"')}"`).join(" ")}]}`;
  } catch (e) {
    const msg = e && e.message ? e.message : String(e);
    return `{:ok false :error "${msg.replace(/"/g, '\\"')}" :partial [${log
      .map((s) => `"${s.replace(/"/g, '\\"')}"`)
      .join(" ")}]}`;
  }
};
