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
   We catch + re-throw as ex-info with `:seon.sidecar/error-kind`.

   Wire format: every value crossing the boundary (query, args, tx-data,
   selectors, eids, results, tempids, tx-meta, datom v/a fields) is a
   Transit-JSON string. See `sidecar-poc.transit`."
  (:require [sidecar-poc.transit :as transit]))

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

(defn- read-payload
  "Decode a Transit-JSON value payload from the host. The host returns a
   Transit-JSON string for every value-carrying field (result, payload).
   Empty / nil / the legacy 'null' literal all read as nil."
  [s]
  (cond
    (nil? s)        nil
    (= "" s)        nil
    (= "null" s)    nil
    :else           (transit/read-str s)))

(defn- write-payload
  "Encode a Clojure value as a Transit-JSON string for the wire."
  [v]
  (transit/write-str v))

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

(defn q-call
  "Run a Datalog query. `query` is any Clojure value (a quoted vector
   form); `args` is a Clojure vector of input values. Both are encoded
   to Transit-JSON before crossing the WIT boundary; the result is
   Transit-decoded back to native Clojure."
  [query args basis-t]
  (let [query-str (write-payload query)
        args-arr  (clj->js (mapv write-payload args))]
    (read-payload (invoke "q" query-str args-arr (->bigint basis-t)))))

(defn transact-call
  "tx-data is a Clojure vector (of maps or 5-vecs). tx-meta optional.
   request-id is a string or nil."
  [tx-data tx-meta request-id]
  (let [tx-str   (write-payload tx-data)
        meta-str (if (nil? tx-meta) "" (write-payload tx-meta))]
    (read-payload (invoke "transact"
                          tx-str
                          meta-str
                          (or request-id "")))))

(defn transact-batch-call
  "Submit N tx-datas in one wire call. Each arg is a Clojure vector of
   length N (or empty/nil for omitted optional lists). Per-entry nil
   inside a metas or ids list means 'absent for that entry'."
  [tx-datas tx-metas request-ids]
  (let [n            (count tx-datas)
        tx-data-arr  (clj->js (mapv write-payload tx-datas))
        tx-meta-arr  (if (seq tx-metas)
                       (clj->js (mapv #(if (nil? %) "" (write-payload %)) tx-metas))
                       (clj->js []))
        req-id-arr   (if (seq request-ids)
                       (clj->js (mapv #(or % "") request-ids))
                       (clj->js []))]
    (when (and (seq tx-metas) (not= (count tx-metas) n))
      (throw (ex-info "transact-batch: tx-meta-list length mismatch"
                      {:expected n :got (count tx-metas)})))
    (when (and (seq request-ids) (not= (count request-ids) n))
      (throw (ex-info "transact-batch: request-ids length mismatch"
                      {:expected n :got (count request-ids)})))
    (read-payload (invoke "transact-batch" tx-data-arr tx-meta-arr req-id-arr))))

(defn pull-call
  "selector is a Clojure value (pull pattern). eid is an int or a
   lookup-ref vector. Both encoded as Transit."
  [selector eid basis-t]
  (read-payload (invoke "pull"
                        (write-payload selector)
                        (write-payload eid)
                        (->bigint basis-t))))

(defn entity-pull-call [reference selector depth basis-t]
  (read-payload (invoke "entity-pull"
                        (write-payload reference)
                        (if (nil? selector) "" (write-payload selector))
                        (or depth 1)
                        (->bigint basis-t))))

(defn pull-many-call [selector eids basis-t]
  (let [eids-arr (clj->js (mapv write-payload eids))]
    (read-payload (invoke "pull-many"
                          (write-payload selector)
                          eids-arr
                          (->bigint basis-t)))))

(defn schema-call [] (read-payload (invoke "schema")))
(defn reverse-schema-call [] (read-payload (invoke "reverse-schema")))

(defn db-filter-call [pred-query args]
  (let [args-arr (clj->js (mapv write-payload args))]
    (invoke "db-filter" (write-payload pred-query) args-arr)))

(defn q-filtered-call [handle query args]
  (let [args-arr (clj->js (mapv write-payload args))]
    (read-payload (invoke "q-filtered"
                          handle
                          (write-payload query)
                          args-arr))))

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
   :tx-meta :request-id ...}). The WIT record carries `a` and `v` as
   Transit-JSON strings; we decode them here so callers see native
   keywords/values."
  [handle]
  (let [ev (invoke "next-tx-event" handle)]
    {:basis-t          (bigint->num (.-basisT ev))
     :basis-t-before   (bigint->num (.-basisTBefore ev))
     :db-name          (.-dbName ev)
     :datoms-added     (bigint->num (.-datomsAdded ev))
     :datoms-retracted (bigint->num (.-datomsRetracted ev))
     :tx-data          (mapv (fn [d]
                               [(bigint->num (.-e d))
                                (read-payload (.-a d))
                                (read-payload (.-v d))
                                (bigint->num (.-t d))
                                (.-added d)])
                             (.-txData ev))
     :tx-meta          (read-payload (.-txMeta ev))
     :request-id       (let [r (.-requestId ev)]
                         (when (and r (not= "" r)) r))}))
