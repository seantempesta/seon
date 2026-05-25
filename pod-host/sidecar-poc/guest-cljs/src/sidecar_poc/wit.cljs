(ns sidecar-poc.wit
  "Thin boundary that turns WIT-bound JS imports from `seon:sidecar/db@0.1.0`
   into Clojure-friendly fns. All overlay calls funnel through here so the
   rest of the overlay is pure CLJS.

   The WIT bridge is JS-resident — wasm-rquickjs imports a JS module that
   re-exports the host fns under names matching the WIT contract. The host's
   wasmtime linker satisfies those imports via `db_iface::Host` in
   `rust-host/src/guest.rs`.

   WIT result<T, E> shows up on the JS side as: success returns T directly;
   error throws a JS value whose `.tag` is the variant name (`internal`,
   `protocol`, `not-found`, `invalid-query`) and whose `.val` is the message.
   We catch + re-throw as ex-info with `:seon.sidecar/error-kind`."
  (:require [clojure.edn :as edn]))

;; The shadow-cljs build is responsible for declaring this module as an
;; external import so the generated JS uses the WIT-bound name verbatim.
;; In dev (Node REPL), a stub at `node_modules/seon:sidecar/db@0.1.0` can
;; mirror the surface for unit testing.
(defn- resolve-wit-mod []
  ;; Resolution order:
  ;; (a) `globalThis.__seon_sidecar_db` set by an ESM shim that imported
  ;;     the WIT module and re-exposed its fns. This is how wasm-rquickjs
  ;;     bundles get their host imports — the JS shim does the ES
  ;;     `import { q, transact, ... } from "seon:sidecar/db@0.1.0"`
  ;;     and stashes the namespace on globalThis BEFORE the CLJS bundle
  ;;     is imported (ES dep-graph order guarantees this).
  ;; (b) `js/require` for Node-REPL / JVM-driven unit tests where the WIT
  ;;     module is mocked as a CommonJS module.
  ;; (c) Empty object — caller will get a clear "WIT import missing" error
  ;;     on first invoke.
  (or (.-__seon_sidecar_db js/globalThis)
      (try (js/require "seon:sidecar/db@0.1.0")
           (catch :default _ nil))
      #js {}))

;; Defer resolution to first invoke so the global is reliably populated.
(defonce ^:private !wit-mod (atom nil))

(defn- wit-mod []
  (or @!wit-mod
      (let [m (resolve-wit-mod)]
        (reset! !wit-mod m)
        m)))

(defn- ->kind [tag]
  (case tag
    "internal"      :seon.sidecar/internal
    "protocol"      :seon.sidecar/protocol
    "not-found"     :seon.sidecar/not-found
    "invalid-query" :seon.sidecar/invalid-query
    :seon.sidecar/unknown))

(defn- wit-throw! [op e]
  (let [tag (some-> e .-tag)
        val (some-> e .-val)
        msg (or val (some-> e .-message) (str e))]
    (throw
     (ex-info (str "sidecar " op " failed: " msg)
              {:seon.sidecar/op         op
               :seon.sidecar/error-kind (->kind tag)
               :seon.sidecar/message    msg}))))

(defn- invoke
  "Call a WIT-bound host fn by JS export name. Args are passed through as
   given (the overlay layer is responsible for marshalling to JS-friendly
   shapes — strings, BigInts for s64, numbers, arrays of strings).

   Returns the host fn's return value verbatim (a string in most cases,
   integer for handles, etc.). On WIT error variants, throws ex-info."
  [op-name & args]
  (let [m (wit-mod)
        f (aget m op-name)]
    (when-not (fn? f)
      (throw (ex-info (str "WIT import missing: " op-name
                           " (have keys: "
                           (try (js/Object.keys m) (catch :default _ "<?>"))
                           ")")
                      {:seon.sidecar/op op-name})))
    (try
      (apply f args)
      (catch :default e
        (wit-throw! op-name e)))))

(defn- read-edn [s]
  (if (or (nil? s) (= "" s) (= "nil" s))
    nil
    (edn/read-string s)))

;; ---------- Wrapped imports ----------
;;
;; Each fn returns native Clojure data (parsed from the EDN-string the host
;; returned) or throws on failure. `basis-t` of 0 means "current".

;; ---------- BigInt coercion ----------
;;
;; WIT `s64` maps to JS BigInt under wasm-rquickjs. Pass a plain JS Number
;; and the host bridge throws `Error converting from js 'int' into type
;; 'big_int'`. Coerce every basis-t before crossing the boundary.

(defn- ->bigint [n]
  (cond
    (nil? n)               (js/BigInt 0)
    (identical? "bigint" (js* "typeof ~{}" n)) n
    :else                  (js/BigInt n)))

(defn q-call [query-edn args basis-t]
  ;; args: vector of EDN-string-coerced values
  (let [args-arr (clj->js (mapv #(if (string? %) % (pr-str %)) args))]
    (read-edn (invoke "q" query-edn args-arr (->bigint basis-t)))))

(defn transact-call [tx-data-edn tx-meta-edn request-id]
  ;; All three args are strings; "" means omitted.
  (read-edn (invoke "transact"
                    tx-data-edn
                    (or tx-meta-edn "")
                    (or request-id ""))))

(defn pull-call [selector-edn eid-edn basis-t]
  (read-edn (invoke "pull" selector-edn eid-edn (->bigint basis-t))))

(defn entity-pull-call [ref-edn selector-edn depth basis-t]
  (read-edn (invoke "entity-pull"
                    ref-edn
                    (or selector-edn "")
                    (or depth 1)
                    (->bigint basis-t))))

(defn pull-many-call [selector-edn eids basis-t]
  (let [eids-arr (clj->js (mapv #(if (string? %) % (pr-str %)) eids))]
    (read-edn (invoke "pull-many" selector-edn eids-arr (->bigint basis-t)))))

(defn schema-call [] (read-edn (invoke "schema")))
(defn reverse-schema-call [] (read-edn (invoke "reverse-schema")))

(defn db-filter-call [pred-query-edn args]
  (let [args-arr (clj->js (mapv #(if (string? %) % (pr-str %)) args))]
    (invoke "db-filter" pred-query-edn args-arr)))

(defn q-filtered-call [handle query-edn args]
  (let [args-arr (clj->js (mapv #(if (string? %) % (pr-str %)) args))]
    (read-edn (invoke "q-filtered" handle query-edn args-arr))))

(defn filter-release-call [handle]
  (invoke "filter-release" handle))

(defn subscribe-tx-call [key]
  (invoke "subscribe-tx" key))

(defn unsubscribe-tx-call [handle]
  (invoke "unsubscribe-tx" handle))

(defn- bigint->num [b]
  ;; BigInt -> Number. Safe for our basis-t range (<2^53).
  (if (identical? "bigint" (js* "typeof ~{}" b))
    (js/Number b)
    b))

(defn next-tx-event-call
  "Blocks on the host's broadcast channel for the next tx event. Returns a
   native map shaped like the pub event ({:basis-t :basis-t-before :tx-data
   :tx-meta :request-id ...})."
  [handle]
  (let [ev (invoke "next-tx-event" handle)]
    ;; The host returns a WIT record with kebab-case JS properties. s64
    ;; fields arrive as BigInts; coerce to Number for downstream usage.
    {:basis-t          (bigint->num (.-basisT ev))
     :basis-t-before   (bigint->num (.-basisTBefore ev))
     :db-name          (.-dbName ev)
     :datoms-added     (bigint->num (.-datomsAdded ev))
     :datoms-retracted (bigint->num (.-datomsRetracted ev))
     :tx-data          (mapv (fn [d]
                               [(bigint->num (.-e d))
                                (.-a d)
                                (read-edn (.-v d))
                                (bigint->num (.-t d))
                                (.-added d)])
                             (.-txData ev))
     :tx-meta          (read-edn (.-txMeta ev))
     :request-id       (let [r (.-requestId ev)]
                         (when (and r (not= "" r)) r))}))
