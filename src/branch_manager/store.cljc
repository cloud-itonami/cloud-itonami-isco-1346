(ns branch-manager.store
  "SSoT for the ISCO-08 1346 financial/insurance branch manager actor.
  Store is a protocol injected into the `branch-manager.actor`
  StateGraph — `MemStore` is the default, deterministic, zero-dep
  backend; a Datomic/kotoba-server-backed implementation can be
  swapped in without touching the actor or governor (itonami actor
  pattern, per ADR-2607011000 / CLAUDE.md Actors section).

  Domain:

    branch      — a registered branch (:branch-id, :name, :region)
    customer    — a registered customer under a branch
                  (:customer-id, :name, :branch-id)
    record      — a committed branch operation record
                  (staffing schedule, performance report, customer
                  correspondence, compliance flag) — written ONLY via
                  commit-record!, never mutated in place
    ledger      — an append-only audit trail of every proposal/verdict/
                  disposition, regardless of outcome (commit or hold)")

(defprotocol Store
  (branch [s branch-id])
  (customer [s customer-id])
  (records-of [s branch-id])
  (ledger [s])
  (register-branch! [s branch])
  (register-customer! [s customer])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (branch [_ branch-id] (get-in @a [:branches branch-id]))
  (customer [_ customer-id] (get-in @a [:customers customer-id]))
  (records-of [_ branch-id] (filter #(= branch-id (:branch-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-branch! [s branch]
    (swap! a assoc-in [:branches (:branch-id branch)] branch) s)
  (register-customer! [s customer]
    (swap! a assoc-in [:customers (:customer-id customer)] customer) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:branches {} :customers {} :records [] :ledger []} seed)))))
