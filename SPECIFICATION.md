# Assignment 1 — Full Technical Specification

**Contract version: 1.0.** Read this specification alongside `Assignment_1_Brief.md` or `Assignment_1_Brief.docx`. These are the complete implementation and assessment rules, not optional bonus features. `README.md` maps the supplied CSV files to worker counts and expected outputs.

## 1. Command-line interface

Your program must accept:

```text
--input PATH --workers W
```

The language prefix and program filename are yours to choose. For example, a JavaScript implementation might run as:

```sh
node src/scheduler.mjs --input inputs/basic.csv --workers 2
```

A Python implementation might use `python3 scheduler.py` with the same flags. Document the exact working command for your repository.

Write exactly one JSON document to standard output. Successful scheduling exits with code 0. Invalid arguments, unreadable files and invalid task data exit nonzero with the error JSON described below. Optional diagnostic logs belong on standard error, not mixed into JSON. An output-file option is not required; ordinary shell redirection can save the JSON, for example `node src/scheduler.mjs --input inputs/basic.csv --workers 2 > result.json`.

`W` is an integer from **1 to 32**. Worker IDs are integers `1` through `W`.

## 2. CSV input contract

The header must contain exactly these columns in this order:

```csv
task_id,duration,priority,dependencies
A,3,2,
B,2,1,A
C,4,1,
D,1,2,"B,C"
```

- Input is UTF-8; an optional UTF-8 BOM, LF and CRLF line endings are supported. Use proper quoted CSV parsing. Extra/missing columns and malformed CSV are errors.
- Trim outer ASCII whitespace from header names and every field. Ignore blank lines and single-field whitespace-only records. A row such as `,,,` is an invalid task, not a blank line.
- `task_id`: unique, case-sensitive ASCII identifier matching `[A-Za-z][A-Za-z0-9_-]{0,31}`.
- `duration`: unsigned decimal integer from **1 to 1,000,000**, measured in virtual time units. No real waiting is needed.
- `priority`: unsigned decimal integer from **0 to 1,000**. **Smaller numbers have higher priority.** Leading zeroes in numeric fields are allowed; signs and fractions are not.
- `dependencies`: empty, or comma-separated task IDs inside the CSV field. Trim each dependency token. Forward references are valid; a dependency does not have to appear earlier in the file.
- Empty tokens inside a dependency list, malformed IDs, repeated dependencies, self-dependencies and dependencies on unknown tasks are invalid.
- Arbitrary dependency cycles are invalid. Validate the whole input before emitting a schedule; do not emit a partial schedule alongside an error.
- Support up to **2,000 tasks**. A header-only file is valid and represents zero tasks. A file without the required header is invalid.

Task-ID comparisons use **ASCII lexicographical order**, not locale or natural-number ordering. For example, `J10` sorts before `J2`, and uppercase letters sort before lowercase letters.

## 3. Scheduling rules

All tasks exist at virtual time 0. A task is ready only after **all** of its dependencies have finished. Each worker runs at most one task at a time. Once started, a task runs for its full duration without interruption.

At each scheduling instant:

1. Finish **all** tasks whose end time is that instant.
2. Make every task whose dependencies are now satisfied available for selection.
3. Combine newly ready tasks with any tasks already waiting. Sort this entire ready set by priority ascending, then task ID in ASCII order.
4. Assign tasks in that order to idle workers in worker-ID ascending order. Consider **all** idle workers, including those idle since an earlier instant—not just workers that finished now.
5. If work remains, advance directly to the next running task's completion and repeat.

Do not reserve workers for blocked tasks, intentionally idle while ready work exists, or wait for a blocked task merely because it has higher priority. Input row order must not decide a tie.

You are implementing this exact policy, **not searching for a globally optimal or shortest possible schedule**. A different valid schedule is still incorrect if it violates the stated tie-breaking policy.

## 4. Successful output

For `inputs/basic.csv` with two workers:

```json
{
  "status": "ok",
  "workers": 2,
  "makespan": 6,
  "schedule": [
    { "task_id": "C", "worker_id": 1, "start": 0, "end": 4 },
    { "task_id": "A", "worker_id": 2, "start": 0, "end": 3 },
    { "task_id": "B", "worker_id": 2, "start": 3, "end": 5 },
    { "task_id": "D", "worker_id": 1, "start": 5, "end": 6 }
  ]
}
```

Requirements:

- Exactly one schedule entry per task; no unknown tasks.
- Integer `worker_id`, `start` and `end`; time begins at 0 and `end - start` equals the supplied duration.
- Output entries sorted by `start` ascending, then `worker_id` ascending.
- `makespan` is the largest end time, or 0 for zero tasks.
- For an empty task set, return `status: "ok"`, the requested `workers`, `makespan: 0` and an empty `schedule`.
- JSON key ordering and indentation do not matter. Additional metadata is optional and earns no extra points; it must not replace or contradict required fields.

## 5. Error output

Example for a dependency cycle:

```json
{
  "status": "error",
  "errors": [
    {
      "code": "CYCLE_DETECTED",
      "message": "A depends on B, B on C, and C on A",
      "cycle": ["A", "B", "C", "A"]
    }
  ]
}
```

Use these codes:

| Code | Meaning |
| --- | --- |
| `INVALID_ARGUMENT` | Missing/invalid CLI arguments, including the worker-count bounds. |
| `READ_ERROR` | The input file cannot be read. |
| `INVALID_CSV` | Invalid encoding, malformed CSV, incorrect header or wrong column count. |
| `INVALID_TASK` | Invalid task ID, duration, priority or task-count bound. |
| `DUPLICATE_TASK` | A task ID occurs more than once. |
| `INVALID_DEPENDENCY` | An unknown, self, repeated or malformed dependency. |
| `CYCLE_DETECTED` | The graph contains a dependency cycle. |

Every error includes a nonempty, useful `message`. A cycle error also includes a real closed path: every next ID in `cycle` is a dependency of the previous ID, and the last repeats the first. Rotations of the same cycle are equally valid. Other context, such as task IDs or record numbers, is welcome but not mandatory.

At least one clear error is enough; you do not need to collect every error. When several different errors exist, their reporting order is not graded. Do not include `schedule` in an error response, claim success or silently discard invalid tasks.

## 6. Tests and explanation

Include executable tests for behavior, not just whether the program runs. Cover at least:

- Priority and task-ID ties, independent of input order.
- Multiple dependencies and simultaneous completion events.
- Lowest idle-worker selection, including a worker idle from an earlier time.
- Idle periods and jumping virtual time without sleeping or stepping through every time unit.
- Unknown, self and repeated dependencies; cycles with unrelated valid tasks.
- Invalid CSV/task values, duplicate task IDs and worker counts.
- Empty input tasks and a meaningful larger dependency chain.

Hidden assessment data will follow the same published contract. There are no surprise feature requirements. Include actual test commands/results and explain your data structures, approximate complexity and any limitations. Avoid hardcoded answers for the supplied files.

## 7. Assessment

| Area | Points |
| --- | --- |
| Scheduling correctness, ordering and worker allocation | 45 |
| Validation and graph-error handling | 20 |
| Meaningful executed tests | 20 |
| Readable code and reproducible instructions | 15 |
| **Total** | **100** |

We value a small correct solution you can explain, including when AI helped you write it. No extra points for architecture, a UI or paid tools. A human reviews the evidence. Only candidates advancing receive a reply, with next steps by email; a follow-up may ask you to explain or modify a small part without changing the submitted work.
