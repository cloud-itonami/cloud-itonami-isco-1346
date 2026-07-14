# cloud-itonami-isco-1346

Open Occupation Blueprint for **ISCO-08 1346**: Financial and Insurance Services Branch Managers.

This repository designs a forkable OSS business for a bank/insurance branch manager: an administrative-support robot assists with staffing scheduling, performance reporting, and customer correspondence under a governor-gated actor, so the branch maintains its own operational records and compliance ledger instead of renting a closed branch-management SaaS.

## What this actor does

- Propose and log staffing schedules (with human approval required)
- Propose branch performance reports and operational metrics
- Draft customer communications and correspondence
- Flag compliance concerns (always escalates for human review)
- Maintain an append-only audit ledger of all actions and verdicts

## What this actor DOES NOT do

**This actor does not approve loans, set insurance rates, or make any binding financial decisions on behalf of the institution.** Those decisions remain the exclusive authority of human managers, underwriters, and loan officers. The actor proposes, but never commits a financial decision without human sign-off. Specifically:

- ❌ No loan approvals (automatic or human-in-the-loop)
- ❌ No rate-setting or premium calculations
- ❌ No insurance binding commitments
- ❌ No customer credit line authorizations
- ❌ No fund transfer execution (only proposes, requires human approval)

Human managers retain full authority over all financial commitments. The actor's role is administrative support — scheduling, reporting, and communication — under strict governor gates.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here an administrative-support robot manages staff
scheduling, branch performance tracking, and customer communications under an
actor that proposes actions and an independent **Branch Manager Governor** that
gates them. The governor never dispatches an action the law or policy forbids.
Compliance concerns always escalate to human managers. Staffing changes always
require human oversight.

A live sample of the operator console (robotics safety console, shared template)
is rendered in [docs/samples/operator-console.html](docs/samples/operator-console.html)
— pure-data HTML output of `kotoba.robotics.ui`.

## Core Contract

```text
branch management request + customer records + compliance requirements
        |
        v
BranchManager Advisor -> BranchManager Governor -> schedule/report/correspond, or human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated advice can dispatch a robot action the governor refuses, suppress
an operating record, or escalate a compliance concern without audit evidence.
No automated action can ever make a financial decision on behalf of the
institution — those remain human-exclusive.

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISCO-08 `1346`). Required capabilities:

- :robotics
- :identity
- :forms
- :dmn
- :bpmn
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Reference implementation (`:maturity :implemented`)

Full itonami Actor pattern (per ADR-2607011000 / CLAUDE.md's Actors
section): a real [`kotoba-lang/langgraph`](https://github.com/kotoba-lang/langgraph)
`StateGraph`, with the Advisor and Governor as distinct graph nodes and
human-in-the-loop interrupt/resume via checkpointing.

```text
:intake -> :advise -> :govern -> :decide -+-> :commit            (:ok? true)
                                           +-> :request-approval   (:escalate? true, interrupt-before)
                                           +-> :hold               (:hard? true)
```

- `src/branch_manager/store.cljc` — `Store` protocol + `MemStore`:
  registered branches and customers, committed records, an append-only audit
  ledger.
- `src/branch_manager/advisor.cljc` — `Advisor` protocol; `mock-advisor`
  (deterministic, default) proposes a branch management operation from a
  request; `llm-advisor` wraps a `langchain.model/ChatModel` — either way the
  advisor only ever produces a `:propose`-effect proposal, never a committed
  record, and LLM parse failures always yield `confidence 0.0` (forces
  escalation, never fabricated confidence).
- `src/branch_manager/governor.cljc` — `BranchManagerGovernor/check`: a pure
  function, wired as its own `:govern` node. Hard invariants (unregistered
  branch/customer, a proposal whose `:effect` isn't `:propose`, attempts to
  approve loans or bind the institution financially) always route to `:hold`.
  Escalation invariants (`:flag-compliance-concern`, `:schedule-staffing`,
  or low advisor confidence) always route to `:request-approval` — an
  `interrupt-before` node that the graph checkpoints and only resumes on
  explicit human approval (`actor/approve!`), matching the README's robotics-premise
  statement that compliance concerns and staffing changes always require
  human oversight.
- `src/branch_manager/actor.cljc` — `build-graph`, `run-request!`,
  `approve!`: the `langgraph.graph/state-graph` wiring itself.

```bash
clojure -M:test
```

This is what backs this repo's `:maturity :implemented` entry in
[`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation).

## License

AGPL-3.0-or-later.
