(ns branch-manager.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [branch-manager.store :as store]
            [branch-manager.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-branch! st {:branch-id "branch-1" :name "Downtown Branch" :region "north"})
    (store/register-customer! st {:customer-id "cust-1" :name "Acme Corp" :branch-id "branch-1"})
    st))

(deftest ok-on-clean-performance-report
  (let [st (fresh-store)
        proposal {:op :log-branch-performance-report :effect :propose :confidence 0.9 :stake :low}
        v (governor/check {:branch-id "branch-1"} {} proposal st)]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (not (:escalate? v)))))

(deftest hard-on-unregistered-branch
  (let [st (fresh-store)
        proposal {:op :log-branch-performance-report :effect :propose :confidence 0.9 :stake :low}
        v (governor/check {:branch-id "no-such-branch"} {} proposal st)]
    (is (:hard? v))
    (is (some #(= :no-branch (:rule %)) (:violations v)))))

(deftest hard-on-unregistered-customer
  (let [st (fresh-store)
        proposal {:op :draft-customer-correspondence :effect :propose :confidence 0.9 :stake :low}
        v (governor/check {:branch-id "branch-1" :customer-id "no-such-cust"} {} proposal st)]
    (is (:hard? v))
    (is (some #(= :no-customer (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        proposal {:op :log-branch-performance-report :effect :direct-write :confidence 0.9 :stake :low}
        v (governor/check {:branch-id "branch-1"} {} proposal st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest hard-on-financial-binding-approval-loan
  (let [st (fresh-store)
        proposal {:op :approve-loan :effect :propose :confidence 0.9 :stake :high}
        v (governor/check {:branch-id "branch-1"} {} proposal st)]
    (is (:hard? v))
    (is (some #(= :no-financial-binding (:rule %)) (:violations v)))))

(deftest hard-on-financial-binding-set-rate
  (let [st (fresh-store)
        proposal {:op :set-rate :effect :propose :confidence 0.9 :stake :high}
        v (governor/check {:branch-id "branch-1"} {} proposal st)]
    (is (:hard? v))
    (is (some #(= :no-financial-binding (:rule %)) (:violations v)))))

(deftest escalates-on-flag-compliance-concern
  (let [st (fresh-store)
        proposal {:op :flag-compliance-concern :effect :propose :confidence 0.9 :stake :high}
        v (governor/check {:branch-id "branch-1"} {} proposal st)]
    (is (:escalate? v))
    (is (not (:hard? v)))))

(deftest escalates-on-schedule-staffing
  (let [st (fresh-store)
        proposal {:op :schedule-staffing :effect :propose :confidence 0.9 :stake :high}
        v (governor/check {:branch-id "branch-1"} {} proposal st)]
    (is (:escalate? v))
    (is (not (:hard? v)))))

(deftest escalates-on-low-confidence
  (let [st (fresh-store)
        proposal {:op :log-branch-performance-report :effect :propose :confidence 0.2 :stake :low}
        v (governor/check {:branch-id "branch-1"} {} proposal st)]
    (is (:escalate? v))
    (is (not (:hard? v)))))

(deftest store-records-and-ledger-append-only
  (let [st (fresh-store)]
    (store/commit-record! st {:branch-id "branch-1" :op :log-branch-performance-report})
    (store/append-ledger! st {:disposition :commit})
    (is (= 1 (count (store/records-of st "branch-1"))))
    (is (= 1 (count (store/ledger st))))))
