# Complyance PSE — Assignment 1 candidate packet

Version 1.0. Start with the concise `Assignment_1_Brief.docx` or `Assignment_1_Brief.md` in the distributed ZIP. Read `SPECIFICATION.md` before coding for the complete CLI, validation, scheduling and output contract. These files are packaged beside this README when exported.

You implement the scheduler. This packet deliberately contains no reference implementation, private assessment cases or production code. Use any language; standard CSV and testing libraries are allowed. AI-assisted development is allowed, but the submitted runtime must have no AI/LLM integration.

## Public cases

| Input under `inputs/` | Workers | Expected file under `expected/` | Expected result / purpose |
| --- | --- | --- | --- |
| `basic.csv` | 2 | `basic.json` | Success, makespan 6; dependencies, priority and concurrent work. |
| `simultaneous.csv` | 2 | `simultaneous.json` | Success, makespan 5; all simultaneous completions release work before selection. |
| `idle-and-ties.csv` | 3 | `idle-and-ties.json` | Success, makespan 6; ASCII ties, idle periods and the lowest available worker. |
| `empty.csv` | 2 | `empty.json` | Success, makespan 0; valid header-only task set. |
| `unknown-dependency.csv` | 2 | `unknown-dependency.json` | `INVALID_DEPENDENCY`; unknown dependency, nonzero exit and no partial schedule. |
| `cycle.csv` | 2 | `cycle.json` | `CYCLE_DETECTED`; cycle plus unrelated work, nonzero exit and no partial schedule. |
| `duplicate-id.csv` | 2 | `duplicate-id.json` | `DUPLICATE_TASK`; duplicate task identifiers, nonzero exit and no partial schedule. |

For successful cases, compare required JSON values and schedule ordering—not whitespace or key ordering. For errors, check the applicable code, useful explanation and any cycle witness. Error messages are examples, not strings to copy. Equivalent rotations of an actual cycle are valid. Additional tests are required; do not hardcode public outputs.

## Run and submit

Your program accepts `--input PATH --workers W`. Document its real language-specific invocation and test command. It writes one JSON document to stdout and uses exit 0 for success/nonzero for errors. No output file flag or installed global command is required.

Complete `SUBMISSION.md` in your public implementation repository and keep final work on its default branch; no commit SHA is required. Use two public repositories, one per assignment, and submit both links once through the form supplied in the recruiter email. No corrections or resubmissions are accepted; submitted work must remain unchanged.

You have two days from receiving the assignment. The recruiter email supplies the exact deadline (date, time and timezone), form URL and HR contact. Extensions are considered only for a valid reason raised with HR; approval is not guaranteed and the deadline does not automatically pause. Contact HR for tooling or accessibility adjustments. Only candidates advancing receive a reply; next steps are communicated by email.
