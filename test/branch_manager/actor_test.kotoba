(ns branch-manager.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [branch-manager.actor :as actor]
            [branch-manager.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-branch! st {:branch-id "branch-1" :name "Downtown Branch" :region "north"})
    (store/register-customer! st {:customer-id "cust-1" :name "Acme Corp" :branch-id "branch-1"})
    st))

(deftest commits-a-clean-low-risk-request
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:branch-id "branch-1" :op :log-branch-performance-report :stake :low}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "branch-1"))))))

(deftest holds-on-unregistered-branch-without-committing
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:branch-id "no-such-branch" :op :log-branch-performance-report :stake :low}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :done (:status result)))
    (is (nil? (get-in result [:state :record])))
    (is (empty? (store/records-of st "no-such-branch")))
    (is (= :hold (:disposition (:state result))))))

(deftest interrupts-then-commits-on-human-approval
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        ;; compliance flag always escalates (governor invariant)
        request {:branch-id "branch-1" :op :flag-compliance-concern :stake :high}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "branch-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (some? (get-in resumed [:state :record])))
      (is (= 1 (count (store/records-of st "branch-1")))))))

(deftest holds-on-financial-binding-attempt
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:branch-id "branch-1" :op :approve-loan :stake :high}
        result (actor/run-request! graph request {} "thread-4")]
    (is (= :done (:status result)))
    (is (nil? (get-in result [:state :record])))
    (is (empty? (store/records-of st "branch-1")))
    (is (= :hold (:disposition (:state result))))))
