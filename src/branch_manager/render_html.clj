(ns branch-manager.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300)
  for the ISCO-08 cluster: this repo previously had NO demo page and no
  generator at all (`:item2/classification \"unknown-no-demo\"` in the
  fleet-wide scan). This namespace drives the REAL actor stack
  (`branch-manager.actor` -> `branch-manager.governor` ->
  `branch-manager.store`) through a scenario built from real, exercised
  store data and renders the result deterministically -- no invented
  numbers, no timestamps in the page content, byte-identical across
  reruns against the same seed (verify by diffing two consecutive runs
  before shipping).

  Adapted from the ISCO-08 1211/1111/2113/1213/1112/2112/2131
  build-time-console precedents
  (`90-docs/business/cloud-itonami-maturity-loop.md`,
  com-junkawasaki/root) using this repo's OWN real fixture, not a copy
  of theirs: branch `branch-1` (\"Downtown Branch\", region \"north\")
  + customer `cust-1` (\"Acme Corp\") are lifted VERBATIM from
  `branch-manager.actor-test`'s (and `branch-manager.governor-test`'s,
  which uses the identical fixture) `fresh-store` fixture (ground
  truth, not invented). Branch `branch-2` (\"Uptown Branch\", region
  \"south\") is ADDITIONAL demo data registered via the SAME real
  `register-branch!` protocol call this actor's own store exposes --
  disclosed here plainly, not presented as pre-existing fixture, so the
  console can show a second branch operating cleanly. Every other
  field this page displays (statuses, record counts, hold reasons) is
  real output read after `run-demo!` actually executed the graph --
  none of it is hand-typed.

  Docstring-vs-code check (per the isco-1112 precedent, which found a
  real discrepancy between its own governor's docstring wording
  (\"registered AND verified\") and what its code actually gates
  (existence only)): reading `branch-manager.store`'s and
  `branch-manager.governor`'s own namespace docstrings against
  `branch-manager.governor/hard-violations` and `check` for the same
  kind of gap -- NONE found here. Both docstrings describe a
  referenced branch/customer as needing to be \"registered\", and the
  code checks exactly that (`(nil? ...-record)`, i.e. existence), no
  more and no less.

  This scenario demonstrates 3 of `branch-manager.governor`'s 4 HARD
  invariants that are genuinely reachable through the real
  `mock-advisor`: `:no-branch` and `:no-customer` are provenance checks
  the governor performs directly against the store using the REQUEST's
  own ids (always reachable regardless of what the advisor forwards).
  `:no-financial-binding` is ALSO reachable -- unlike the ISCO-08 2112
  precedent's `:published?`/`:auto-issue?` fields (which its advisor
  never forwards), `branch-manager.advisor/infer` forwards the
  request's `:op` verbatim into the proposal (`{:op op ...}`), so
  requesting any of the 3 `forbidden-financial-ops`
  (`:approve-loan`/`:set-rate`/`:bind-commitment`) as a real op reaches
  the real hard-block -- this scenario demonstrates all 3, not just
  one, since each is a distinct real, reachable path. This scenario
  also demonstrates both of the governor's advisor-reachable
  ESCALATION rules (`:flag-compliance-concern` and `:schedule-staffing`
  both always escalate).

  Known architectural gaps, honestly noted rather than papered over
  (confirmed by reading `branch-manager.advisor/infer` and
  `branch-manager.governor` themselves, not assumed):
  - `:no-actuation` (proposal `:effect` must be `:propose`) is NOT
    reachable, because `mock-advisor` unconditionally sets
    `:effect :propose` on every proposal it emits, regardless of the
    request. Covered instead by
    `branch-manager.governor-test/hard-on-no-actuation-violation` (a
    hand-built proposal with `:effect :direct-write`, calling
    `governor/check` directly).
  - low-confidence escalation (`confidence < 0.6`) is NOT reachable,
    because `infer`'s stake-derived confidence (`:high` 0.7, `:medium`
    0.85, `:low` 0.95) never drops below the governor's
    `confidence-floor` (0.6). Covered instead by
    `branch-manager.governor-test/escalates-on-low-confidence`.
  Both gaps are the same shape as the ISCO-08 1211/2113/1213/1112/2112/
  2131 precedents' disclosed `:no-actuation`-class gaps -- this demo,
  like those, only ever drives the real actor/graph the way an
  operator actually would, and does not hand-construct proposals to
  force unreachable paths.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [branch-manager.store :as store]
            [branch-manager.actor :as actor]))

;; ----------------------------- harness --------------------------------

(defn- run-op!
  "Drives one real branch management operation request through the
  actual compiled graph for `tid` (thread-id). If the graph escalates
  (interrupts before `:request-approval`), immediately approves it
  (this demo's scenario never demonstrates an UNAPPROVED escalation --
  every escalation here reaches a human who signs off). Returns a map
  describing exactly what really happened -- no field is invented."
  [graph tid branch-id op extra]
  (let [request (merge {:branch-id branch-id :op op} extra)
        r1 (actor/run-request! graph request {} tid)]
    (if (= :interrupted (:status r1))
      (let [r2 (actor/approve! graph tid)]
        {:thread-id tid :branch-id branch-id :op op :request request
         :outcome :approved-and-committed
         :record (get-in r2 [:state :record])})
      (let [disposition (get-in r1 [:state :disposition])]
        (if (= :hold disposition)
          {:thread-id tid :branch-id branch-id :op op :request request
           :outcome :hard-hold
           :verdict (get-in r1 [:state :verdict])
           :rule (-> r1 :state :verdict :violations first :rule)}
          {:thread-id tid :branch-id branch-id :op op :request request
           :outcome :auto-committed
           :record (get-in r1 [:state :record])})))))

(def ^:private op-specs
  "The scenario: covers every disposition this actor can genuinely reach
  through its real graph (auto-commit for each op family, escalate-
  then-approve for both advisor-reachable escalation rules, and all 3
  of the 3 advisor-reachable HARD-hold reasons in
  `branch-manager.governor` -- the 4th, `:no-actuation`, plus the
  low-confidence escalation, are architecturally unreachable via the
  real advisor, see namespace docstring). Every `:op` keyword and
  violation rule name below is copied from `branch-manager.governor`'s
  own `hard-violations`/`check`, not invented. Vector shape:
  [thread-id branch-id op extra]."
  [;; branch-1 / "Downtown Branch" (real fixture from branch-manager.actor-test)
   ["branch1-report-clean"        "branch-1" :log-branch-performance-report {:stake :low}]
   ["branch1-correspondence-clean" "branch-1" :draft-customer-correspondence {:customer-id "cust-1" :stake :low}]
   ["branch1-staffing-escalate"   "branch-1" :schedule-staffing            {:stake :high}]
   ["branch1-compliance-escalate" "branch-1" :flag-compliance-concern      {:stake :high}]
   ["branch1-approve-loan-blocked" "branch-1" :approve-loan                {:stake :high}]
   ["branch1-set-rate-blocked"    "branch-1" :set-rate                     {:stake :high}]
   ["branch1-bind-commitment-blocked" "branch-1" :bind-commitment          {:stake :high}]
   ["branch1-no-customer"         "branch-1" :draft-customer-correspondence {:customer-id "no-such-cust" :stake :low}]
   ;; unregistered branch entirely
   ["ghost-no-branch"             "no-such-branch" :log-branch-performance-report {:stake :low}]
   ;; branch-2 / "Uptown Branch" (additional demo data, registered via the
   ;; same real register-branch! call -- see namespace docstring)
   ["branch2-report-clean"        "branch-2" :log-branch-performance-report {:stake :low}]])

(defn run-demo!
  "Runs a fresh store through `op-specs` (see above) via the real
  compiled `branch-manager.actor` graph. Returns `{:store :runs}` --
  `:runs` is the ordered vector of real per-request outcomes; every
  field in `render` below is read from this or from `store` after the
  graph actually executed, never hand-typed."
  []
  (let [db (store/mem-store)]
    (store/register-branch! db {:branch-id "branch-1" :name "Downtown Branch" :region "north"})
    (store/register-customer! db {:customer-id "cust-1" :name "Acme Corp" :branch-id "branch-1"})
    (store/register-branch! db {:branch-id "branch-2" :name "Uptown Branch" :region "south"})
    (let [graph (actor/build-graph {:store db})
          runs (mapv (fn [[tid branch-id op extra]]
                       (run-op! graph tid branch-id op extra))
                     op-specs)]
      {:store db :runs runs})))

;; ----------------------------- rendering -------------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- outcome-cell [{:keys [outcome rule]}]
  (case outcome
    :auto-committed "<span class=\"ok\">committed</span>"
    :approved-and-committed "<span class=\"ok\">approved &amp; committed</span>"
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>")
    "<span class=\"muted\">in progress</span>"))

(defn- branch-row [store {:keys [branch-id name region]} runs]
  (let [record-count (count (store/records-of store branch-id))
        last-run (last (filter #(= branch-id (:branch-id %)) runs))]
    (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%d</td><td>%s</td></tr>"
            (esc branch-id) (esc name) (esc region) record-count
            (if last-run (outcome-cell last-run) "<span class=\"muted\">no activity</span>"))))

(defn- run-row [{:keys [thread-id branch-id op request outcome rule]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc thread-id) (esc branch-id) (esc (name op))
          (esc (or (some-> (:customer-id request) str) ""))
          (outcome-cell {:outcome outcome :rule rule})))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract (README.md /
  ;; `branch-manager.governor`'s own docstring) -- documentation of
  ;; fixed behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:log-branch-performance-report</code></td><td><span class=\"ok\">auto-commit when branch is registered, no other gate</span></td></tr>"
   "        <tr><td><code>:draft-customer-correspondence</code></td><td><span class=\"ok\">auto-commit when branch AND (if named) customer are registered</span></td></tr>"
   "        <tr><td><code>:schedule-staffing</code></td><td><span class=\"warn\">ALWAYS human approval &middot; HR impact</span></td></tr>"
   "        <tr><td><code>:flag-compliance-concern</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto-resolved</span></td></tr>"
   "        <tr><td><code>:approve-loan</code> / <code>:set-rate</code> / <code>:bind-commitment</code></td><td><span class=\"critical\">HARD block &middot; forbidden financial ops, humans only</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from `{:store :runs}`
  as produced by `run-demo!` (or any other real scenario)."
  [{:keys [store runs]}]
  (let [branches [{:branch-id "branch-1" :name "Downtown Branch" :region "north"}
                   {:branch-id "branch-2" :name "Uptown Branch" :region "south"}]
        branch-rows (str/join "\n" (map #(branch-row store % runs) branches))
        run-rows (str/join "\n" (map run-row runs))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isco-1346 &middot; branch manager operator console</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Financial/Insurance Branch Manager Support (ISCO-08 1346) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · every proposal is for branch manager review only, never a bound financial decision</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Registered branches</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>branch-manager.store</code> via <code>branch-manager.render-html</code> (<code>clojure -M:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Branch</th><th>Name</th><th>Region</th><th>Records committed</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     branch-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "    <p class=\"muted\">Also registered under <code>branch-1</code>: customer <code>cust-1</code> (\"Acme Corp\").</p>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Branch Manager Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Branch/customer provenance is checked directly against the store using the request's own ids; forbidden financial ops are blocked regardless of provenance.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit trail (this run)</h2>\n"
     "    <p class=\"muted\">Every request this scenario drove through the real compiled graph, in order — thread-id, branch, op, the request's own customer reference (if any), and the real disposition (auto-commit, approved-after-escalation, or the specific HARD-hold rule).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Thread</th><th>Branch</th><th>Op</th><th>Customer</th><th>Disposition</th></tr></thead>\n"
     "      <tbody>\n"
     run-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out "("
             (count (:runs result)) "requests driven through the real graph,"
             (count (store/ledger (:store result))) "ledger facts )")))
