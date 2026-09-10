(ns branch-manager.governor
  "BranchManagerGovernor — the independent safety/traceability layer for
  the ISCO-08 1346 financial/insurance branch manager actor. Wired as its
  own `:govern` node in `branch-manager.actor`'s StateGraph, downstream of
  `:advise` — the Advisor has no notion of branch/customer provenance or
  regulatory/financial risk, so this MUST be a separate system able to
  reject a proposal (itonami actor pattern, per ADR-2607011000 / CLAUDE.md
  Actors section).

  `check` is a pure function of (request, context, proposal, store) ->
  verdict; it never mutates the store. The StateGraph's `:decide` node
  routes on the verdict:
    :hard? true                → :hold  (irreversible, no write)
    :escalate? true            → :request-approval (interrupt-before)
    otherwise                  → :commit

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. branch provenance     — the request's branch must be registered.
    2. customer provenance   — if a customer is named, must be registered.
    3. no-actuation          — proposal :effect must be :propose.
    4. no-financial-binding  — proposal must never directly approve loans,
                               set rates, or bind the institution
                               financially (only humans make those decisions).
  ESCALATION invariants (:escalate? true, ALWAYS human sign-off, per the
  README robotics-premise: compliance concerns and staffing changes always
  require human oversight):
    5. :op :flag-compliance-concern — always escalates, never auto-resolved.
    6. :op :schedule-staffing       — always escalates (HR impact).
    7. low confidence (< `confidence-floor`)."
  (:require [branch-manager.store :as store]))

(def confidence-floor 0.6)
(def ^:private escalating-ops #{:flag-compliance-concern :schedule-staffing})
(def ^:private forbidden-financial-ops #{:approve-loan :set-rate :bind-commitment})

(defn- hard-violations [{:keys [proposal request]} branch-record customer-record]
  (cond-> []
    (nil? branch-record)
    (conj {:rule :no-branch :detail "未登録 branch"})

    (and (:customer-id request) (nil? customer-record))
    (conj {:rule :no-customer :detail "未登録 customer"})

    (not= :propose (:effect proposal))
    (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

    (contains? forbidden-financial-ops (:op proposal))
    (conj {:rule :no-financial-binding
           :detail "ローン承認・料率設定・拘束財務決定は人間のみが権限。AI が自動実行禁止"})))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `branch-manager.store/Store`. Returns
  `{:ok? bool :violations [...] :confidence n :hard? bool :escalate? bool}`."
  [request context proposal store]
  (let [branch-record (store/branch store (:branch-id request))
        customer-record (when (:customer-id request)
                          (store/customer store (:customer-id request)))
        hard (hard-violations {:proposal proposal :request request}
                              branch-record customer-record)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        risky-op? (contains? escalating-ops (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
