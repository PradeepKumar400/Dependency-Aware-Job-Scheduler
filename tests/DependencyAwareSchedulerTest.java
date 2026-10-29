package tests;

import java.nio.charset.StandardCharsets;

public class DependencyAwareSchedulerTest {

    public static void main(String[] args) throws Exception {
        Process process = new ProcessBuilder(
                "java", "-cp", "out",
                "DependencyAwareScheduler",
                "--input", "inputs/basic.csv",
                "--workers", "2").redirectErrorStream(true).start();

        String output;
        try (var in = process.getInputStream()) {
            output = new String(
                    in.readAllBytes(),
                    StandardCharsets.UTF_8);
        }

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new AssertionError(
                    "Program failed. Output: " + output);
        }

        if (!output.contains("\"status\": \"ok\"")
                && !output.contains("\"status\":\"ok\"")) {
            throw new AssertionError(
                    "Expected successful JSON. Output: " + output);
        }

        if (!output.contains("\"makespan\": 6")
                && !output.contains("\"makespan\":6")) {
            throw new AssertionError(
                    "Expected makespan 6. Output: " + output);
        }

        System.out.println("PASS: Basic scheduling test");
    }
}
