import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

public class DependencyAwareScheduler {

    private static final int MAX_TASKS = 2000;
    private static final int MAX_WORKERS = 32;
    private static final int MAX_DURATION = 1_000_000;
    private static final int MAX_PRIORITY = 1000;

    private static final Pattern TASK_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,31}");

    private static final Pattern UNSIGNED_INTEGER = Pattern.compile("[0-9]+");

    private static final String[] EXPECTED_HEADER = {
            "task_id", "duration", "priority", "dependencies"
    };

    static class SchedulerException extends Exception {
        final String code;
        final List<String> cycle;

        SchedulerException(String code, String message) {
            super(message);
            this.code = code;
            this.cycle = null;
        }

        SchedulerException(String message, List<String> cycle) {
            super(message);
            this.code = "CYCLE_DETECTED";
            this.cycle = cycle;
        }
    }

    static class Task {
        final String id;
        final int duration;
        final int priority;
        final List<String> dependencies;

        Task(String id, int duration, int priority,
                List<String> dependencies) {
            this.id = id;
            this.duration = duration;
            this.priority = priority;
            this.dependencies = new ArrayList<>(dependencies);
        }
    }

    static class ScheduledTask {
        final String taskId;
        final int workerId;
        final long start;
        final long end;

        ScheduledTask(String taskId, int workerId,
                long start, long end) {
            this.taskId = taskId;
            this.workerId = workerId;
            this.start = start;
            this.end = end;
        }
    }

    static class RunningTask {
        final Task task;
        final int workerId;
        final long end;

        RunningTask(Task task, int workerId, long end) {
            this.task = task;
            this.workerId = workerId;
            this.end = end;
        }
    }

    static class CsvRecord {
        final List<String> fields;
        final int recordNumber;

        CsvRecord(List<String> fields, int recordNumber) {
            this.fields = fields;
            this.recordNumber = recordNumber;
        }
    }

    static final Comparator<Task> READY_ORDER = Comparator.comparingInt((Task t) -> t.priority)
            .thenComparing(t -> t.id);

    static final Comparator<ScheduledTask> OUTPUT_ORDER = Comparator.comparingLong((ScheduledTask s) -> s.start)
            .thenComparingInt(s -> s.workerId);

    public static void main(String[] args) {
        try {
            Arguments arguments = parseArguments(args);

            Map<String, Task> tasks = readTasks(arguments.input);
            Map<String, List<String>> dependents = buildGraph(tasks);

            List<String> cycle = findCycle(tasks, dependents);
            if (cycle != null) {
                StringBuilder description = new StringBuilder();

                for (int i = 0; i < cycle.size() - 1; i++) {
                    if (i > 0) {
                        description.append(", ");
                    }
                    description.append(cycle.get(i))
                            .append(" depends on ");
                    description.append(cycle.get(i + 1));
                }

                throw new SchedulerException(
                        description.toString(), cycle);
            }

            List<ScheduledTask> schedule = createSchedule(tasks, dependents, arguments.workers);

            schedule.sort(OUTPUT_ORDER);

            long makespan = 0;
            for (ScheduledTask s : schedule) {
                makespan = Math.max(makespan, s.end);
            }

            System.out.println(successJson(
                    arguments.workers, makespan, schedule));

        } catch (SchedulerException e) {
            System.out.println(errorJson(
                    e.code, e.getMessage(), e.cycle));
            System.exit(1);
        } catch (IOException e) {
            System.out.println(errorJson(
                    "READ_ERROR",
                    "Cannot read input file: " + safeMessage(e),
                    null));
            System.exit(1);
        } catch (Exception e) {
            System.out.println(errorJson(
                    "INVALID_ARGUMENT",
                    safeMessage(e),
                    null));
            System.exit(1);
        }
    }

    // ---------------- COMMAND-LINE ARGUMENTS ----------------

    static class Arguments {
        final String input;
        final int workers;

        Arguments(String input, int workers) {
            this.input = input;
            this.workers = workers;
        }
    }

    private static Arguments parseArguments(String[] args)
            throws SchedulerException {

        String input = null;
        String workerText = null;

        for (int i = 0; i < args.length; i++) {
            String option = args[i];

            if ("--input".equals(option)) {
                if (input != null || i + 1 >= args.length
                        || args[i + 1].startsWith("--")) {
                    throw new SchedulerException(
                            "INVALID_ARGUMENT",
                            "--input must be provided exactly once with a path.");
                }
                input = args[++i];

            } else if ("--workers".equals(option)) {
                if (workerText != null || i + 1 >= args.length
                        || args[i + 1].startsWith("--")) {
                    throw new SchedulerException(
                            "INVALID_ARGUMENT",
                            "--workers must be provided exactly once with a number.");
                }
                workerText = args[++i];

            } else {
                throw new SchedulerException(
                        "INVALID_ARGUMENT",
                        "Unknown command-line argument: " + option);
            }
        }

        if (input == null || workerText == null) {
            throw new SchedulerException(
                    "INVALID_ARGUMENT",
                    "Usage: java DependencyAwareScheduler "
                            + "--input PATH --workers W");
        }

        if (!UNSIGNED_INTEGER.matcher(workerText).matches()) {
            throw new SchedulerException(
                    "INVALID_ARGUMENT",
                    "Worker count must be an integer from 1 to 32.");
        }

        int workers;
        try {
            workers = Integer.parseInt(workerText);
        } catch (NumberFormatException e) {
            throw new SchedulerException(
                    "INVALID_ARGUMENT",
                    "Worker count must be an integer from 1 to 32.");
        }

        if (workers < 1 || workers > MAX_WORKERS) {
            throw new SchedulerException(
                    "INVALID_ARGUMENT",
                    "Worker count must be between 1 and 32.");
        }

        return new Arguments(input, workers);
    }

    // ---------------- CSV READING ----------------

    private static Map<String, Task> readTasks(String filename)
            throws IOException, SchedulerException {

        byte[] bytes = Files.readAllBytes(Path.of(filename));

        final String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new SchedulerException(
                    "INVALID_CSV",
                    "Input file is not valid UTF-8.");
        }

        String text = content;

        // Remove an optional UTF-8 BOM.
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }

        // Support CRLF and LF; reject unsupported bare CR.
        text = text.replace("\r\n", "\n");
        if (text.indexOf('\r') >= 0) {
            throw new SchedulerException(
                    "INVALID_CSV",
                    "Unsupported line ending; use LF or CRLF.");
        }

        List<CsvRecord> records = parseCsv(text);

        if (records.isEmpty()) {
            throw new SchedulerException(
                    "INVALID_CSV",
                    "CSV file is missing the required header.");
        }

        List<String> header = records.get(0).fields;

        if (header.size() != EXPECTED_HEADER.length) {
            throw new SchedulerException(
                    "INVALID_CSV",
                    "Header must contain exactly four columns in the required order.");
        }

        for (int i = 0; i < EXPECTED_HEADER.length; i++) {
            if (!trimAscii(header.get(i)).equals(EXPECTED_HEADER[i])) {
                throw new SchedulerException(
                        "INVALID_CSV",
                        "Expected header column '" + EXPECTED_HEADER[i]
                                + "' at position " + (i + 1) + ".");
            }
        }

        Map<String, Task> tasks = new LinkedHashMap<>();

        for (int r = 1; r < records.size(); r++) {
            CsvRecord record = records.get(r);
            List<String> fields = record.fields;

            // Ignore blank lines and one-field whitespace-only records.
            if (fields.size() == 1 && trimAscii(fields.get(0)).isEmpty()) {
                continue;
            }

            if (fields.size() != EXPECTED_HEADER.length) {
                throw new SchedulerException(
                        "INVALID_CSV",
                        "Record " + record.recordNumber
                                + " must contain exactly four columns.");
            }

            String id = trimAscii(fields.get(0));
            String durationText = trimAscii(fields.get(1));
            String priorityText = trimAscii(fields.get(2));
            String dependencyText = trimAscii(fields.get(3));

            if (!TASK_ID.matcher(id).matches()) {
                throw new SchedulerException(
                        "INVALID_TASK",
                        "Invalid task ID at record "
                                + record.recordNumber + ".");
            }

            if (tasks.containsKey(id)) {
                throw new SchedulerException(
                        "DUPLICATE_TASK",
                        "Task ID occurs more than once: " + id);
            }

            int duration = parseBoundedInteger(
                    durationText, 1, MAX_DURATION,
                    "INVALID_TASK",
                    "Duration for task " + id
                            + " must be an unsigned integer from 1 to 1000000.");

            int priority = parseBoundedInteger(
                    priorityText, 0, MAX_PRIORITY,
                    "INVALID_TASK",
                    "Priority for task " + id
                            + " must be an unsigned integer from 0 to 1000.");

            List<String> dependencies = new ArrayList<>();
            Set<String> seen = new HashSet<>();

            if (!dependencyText.isEmpty()) {
                String[] tokens = dependencyText.split(",", -1);

                for (String token : tokens) {
                    String dep = trimAscii(token);

                    if (!TASK_ID.matcher(dep).matches()) {
                        throw new SchedulerException(
                                "INVALID_DEPENDENCY",
                                "Malformed dependency '" + dep
                                        + "' for task " + id + ".");
                    }

                    if (dep.equals(id)) {
                        throw new SchedulerException(
                                "INVALID_DEPENDENCY",
                                "Task " + id + " cannot depend on itself.");
                    }

                    if (!seen.add(dep)) {
                        throw new SchedulerException(
                                "INVALID_DEPENDENCY",
                                "Repeated dependency " + dep
                                        + " for task " + id + ".");
                    }

                    dependencies.add(dep);
                }
            }

            if (tasks.size() >= MAX_TASKS) {
                throw new SchedulerException(
                        "INVALID_TASK",
                        "The input cannot contain more than 2000 tasks.");
            }

            tasks.put(id, new Task(
                    id, duration, priority, dependencies));
        }

        return tasks;
    }

    private static int parseBoundedInteger(
            String value, int minimum, int maximum,
            String code, String message)
            throws SchedulerException {

        if (!UNSIGNED_INTEGER.matcher(value).matches()) {
            throw new SchedulerException(code, message);
        }

        // Reject excessively long numeric strings before parsing.
        if (value.length() > 10) {
            throw new SchedulerException(code, message);
        }

        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new SchedulerException(code, message);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new SchedulerException(code, message);
        }
    }

    private static String trimAscii(String value) {
        int start = 0;
        int end = value.length();

        while (start < end && value.charAt(start) <= 0x20) {
            start++;
        }

        while (end > start && value.charAt(end - 1) <= 0x20) {
            end--;
        }

        return value.substring(start, end);
    }

    /*
     * Parse CSV records while respecting quoted commas and escaped quotes.
     * Newlines inside quoted fields are preserved by this parser, but the
     * task-field validation will reject them where they are not permitted.
     */
    private static List<CsvRecord> parseCsv(String text)
            throws SchedulerException {

        List<CsvRecord> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();

        boolean inQuotes = false;
        boolean afterQuote = false;
        boolean fieldStarted = false;
        int recordNumber = 1;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length()
                            && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                        afterQuote = true;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }

            if (afterQuote) {
                if (c == ',') {
                    fields.add(field.toString());
                    field.setLength(0);
                    afterQuote = false;
                    fieldStarted = false;
                } else if (c == '\n') {
                    fields.add(field.toString());
                    records.add(new CsvRecord(
                            new ArrayList<>(fields), recordNumber++));
                    fields.clear();
                    field.setLength(0);
                    afterQuote = false;
                    fieldStarted = false;
                } else if (isAsciiWhitespace(c)) {
                    // Allow outer whitespace after a closing quote.
                } else {
                    throw new SchedulerException(
                            "INVALID_CSV",
                            "Unexpected character after a closing CSV quote.");
                }
                continue;
            }

            if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
                fieldStarted = false;

            } else if (c == '\n') {
                fields.add(field.toString());
                records.add(new CsvRecord(
                        new ArrayList<>(fields), recordNumber++));
                fields.clear();
                field.setLength(0);
                fieldStarted = false;

            } else if (c == '"') {
                if (!trimAscii(field.toString()).isEmpty()
                        || fieldStarted) {
                    throw new SchedulerException(
                            "INVALID_CSV",
                            "Unexpected quote inside an unquoted CSV field.");
                }

                // Permit whitespace before an opening quote.
                field.setLength(0);
                inQuotes = true;
                fieldStarted = true;

            } else {
                field.append(c);
                if (!isAsciiWhitespace(c)) {
                    fieldStarted = true;
                }
            }
        }

        if (inQuotes) {
            throw new SchedulerException(
                    "INVALID_CSV",
                    "Unclosed quoted CSV field.");
        }

        // Don't create an extra record for a final newline.
        if (!fields.isEmpty() || field.length() > 0
                || afterQuote || fieldStarted) {
            fields.add(field.toString());
            records.add(new CsvRecord(
                    new ArrayList<>(fields), recordNumber));
        }

        return records;
    }

    private static boolean isAsciiWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n'
                || c == '\r' || c == '\f' || c == '\u000B';
    }

    // ---------------- DEPENDENCY GRAPH ----------------

    private static Map<String, List<String>> buildGraph(
            Map<String, Task> tasks) throws SchedulerException {

        Map<String, List<String>> dependents = new LinkedHashMap<>();

        for (String id : tasks.keySet()) {
            dependents.put(id, new ArrayList<>());
        }

        for (Task task : tasks.values()) {
            for (String dep : task.dependencies) {
                if (!tasks.containsKey(dep)) {
                    throw new SchedulerException(
                            "INVALID_DEPENDENCY",
                            "Task " + task.id
                                    + " depends on unknown task " + dep + ".");
                }

                // Edge: prerequisite -> dependent task.
                dependents.get(dep).add(task.id);
            }
        }

        return dependents;
    }

    /*
     * Iterative depth-first search avoids recursive stack overflow.
     * Each reported edge follows a real dependency.
     */
    private static List<String> findCycle(
            Map<String, Task> tasks,
            Map<String, List<String>> dependents) {

        // For cycle detection, traverse task -> its dependencies.
        Map<String, Integer> color = new HashMap<>();
        Map<String, String> parent = new HashMap<>();

        for (String id : tasks.keySet()) {
            color.put(id, 0); // 0 = unseen, 1 = active, 2 = complete
        }

        class Frame {
            String id;
            int next;

            Frame(String id) {
                this.id = id;
            }
        }

        List<String> ids = new ArrayList<>(tasks.keySet());
        ids.sort(String::compareTo);

        for (String root : ids) {
            if (color.get(root) != 0) {
                continue;
            }

            ArrayDeque<Frame> stack = new ArrayDeque<>();
            color.put(root, 1);
            stack.push(new Frame(root));

            while (!stack.isEmpty()) {
                Frame frame = stack.peek();
                List<String> deps = tasks.get(frame.id).dependencies;

                if (frame.next >= deps.size()) {
                    color.put(frame.id, 2);
                    stack.pop();
                    continue;
                }

                String dep = deps.get(frame.next++);

                if (color.get(dep) == 0) {
                    parent.put(dep, frame.id);
                    color.put(dep, 1);
                    stack.push(new Frame(dep));

                } else if (color.get(dep) == 1) {
                    // Back edge frame.id -> dep. Reconstruct a closed cycle.
                    List<String> reverse = new ArrayList<>();
                    String current = frame.id;
                    reverse.add(current);

                    while (!current.equals(dep)) {
                        current = parent.get(current);
                        if (current == null) {
                            break;
                        }
                        reverse.add(current);
                    }

                    List<String> cycle = new ArrayList<>();
                    cycle.add(dep);

                    for (int i = reverse.size() - 2; i >= 0; i--) {
                        cycle.add(reverse.get(i));
                    }

                    cycle.add(dep);
                    return cycle;
                }
            }
        }

        return null;
    }

    // ---------------- SCHEDULING ENGINE ----------------

    private static List<ScheduledTask> createSchedule(
            Map<String, Task> tasks,
            Map<String, List<String>> dependents,
            int workerCount) {

        List<ScheduledTask> result = new ArrayList<>();

        if (tasks.isEmpty()) {
            return result;
        }

        Map<String, Integer> remainingDependencies = new HashMap<>();

        PriorityQueue<Task> ready = new PriorityQueue<>(READY_ORDER);

        for (Task task : tasks.values()) {
            remainingDependencies.put(
                    task.id, task.dependencies.size());

            if (task.dependencies.isEmpty()) {
                ready.offer(task);
            }
        }

        TreeSet<Integer> idleWorkers = new TreeSet<>();
        for (int i = 1; i <= workerCount; i++) {
            idleWorkers.add(i);
        }

        PriorityQueue<RunningTask> running = new PriorityQueue<>(
                Comparator.comparingLong((RunningTask r) -> r.end)
                        .thenComparingInt(r -> r.workerId));

        long time = 0;
        int completed = 0;

        while (completed < tasks.size()) {

            // Assign ready tasks to every available worker.
            while (!idleWorkers.isEmpty() && !ready.isEmpty()) {
                Task task = ready.poll();
                int worker = idleWorkers.pollFirst();

                long end = time + task.duration;

                result.add(new ScheduledTask(
                        task.id, worker, time, end));

                running.offer(new RunningTask(task, worker, end));
            }

            if (running.isEmpty()) {
                // Should not happen after cycle validation.
                throw new IllegalStateException(
                        "No runnable tasks remain; dependency graph is invalid.");
            }

            // Advance directly to the next completion.
            time = running.peek().end;

            List<RunningTask> finished = new ArrayList<>();

            // Finish all jobs at this same instant before selecting new work.
            while (!running.isEmpty() && running.peek().end == time) {
                finished.add(running.poll());
            }

            // Release all workers first.
            for (RunningTask runningTask : finished) {
                idleWorkers.add(runningTask.workerId);
                completed++;
            }

            // Then update dependencies for every completed task.
            for (RunningTask runningTask : finished) {
                for (String dependent : dependents.get(runningTask.task.id)) {
                    int remaining = remainingDependencies.get(dependent) - 1;

                    remainingDependencies.put(dependent, remaining);

                    if (remaining == 0) {
                        ready.offer(tasks.get(dependent));
                    }
                }
            }
        }

        return result;
    }

    // ---------------- JSON OUTPUT ----------------

    private static String successJson(
            int workers, long makespan, List<ScheduledTask> schedule) {

        StringBuilder out = new StringBuilder();

        out.append("{\n");
        out.append("  \"status\": \"ok\",\n");
        out.append("  \"workers\": ").append(workers).append(",\n");
        out.append("  \"makespan\": ").append(makespan).append(",\n");
        out.append("  \"schedule\": [");

        if (!schedule.isEmpty()) {
            out.append("\n");

            for (int i = 0; i < schedule.size(); i++) {
                ScheduledTask s = schedule.get(i);

                out.append("    {\n");
                out.append("      \"task_id\": \"")
                        .append(jsonEscape(s.taskId)).append("\",\n");
                out.append("      \"worker_id\": ").append(s.workerId)
                        .append(",\n");
                out.append("      \"start\": ").append(s.start).append(",\n");
                out.append("      \"end\": ").append(s.end).append("\n");
                out.append("    }");

                if (i + 1 < schedule.size()) {
                    out.append(",");
                }

                out.append("\n");
            }

            out.append("  ");
        }

        out.append("]\n");
        out.append("}");

        return out.toString();
    }

    private static String errorJson(
            String code, String message, List<String> cycle) {

        StringBuilder out = new StringBuilder();

        out.append("{\n");
        out.append("  \"status\": \"error\",\n");
        out.append("  \"errors\": [\n");
        out.append("    {\n");
        out.append("      \"code\": \"").append(jsonEscape(code)).append("\",\n");
        out.append("      \"message\": \"")
                .append(jsonEscape(message == null
                        ? "Unknown error"
                        : message))
                .append("\"");

        if (cycle != null) {
            out.append(",\n");
            out.append("      \"cycle\": [");

            for (int i = 0; i < cycle.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append("\"").append(jsonEscape(cycle.get(i))).append("\"");
            }

            out.append("]");
        }

        out.append("\n    }\n");
        out.append("  ]\n");
        out.append("}");

        return out.toString();
    }

    private static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder();

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }

        return out.toString();
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();

        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }

        return message;
    }
}
