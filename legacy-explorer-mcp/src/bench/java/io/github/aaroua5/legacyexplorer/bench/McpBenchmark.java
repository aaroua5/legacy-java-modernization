package io.github.aaroua5.legacyexplorer.bench;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Does the Legacy Explorer MCP server help a coding agent understand a legacy codebase?
 *
 * <p>Each question in {@code bench/tasks.json} is asked to Claude Code (headless) twice per trial:
 * WITHOUT any MCP server, and WITH only legacy-explorer. Everything else is identical: prompt, model,
 * read-only tools (Read, Grep, Glob and read-only shell commands), and a fresh copy of the legacy app
 * in a temp directory outside this repository, so the agent can't read our docs or answer keys.
 * Answers are scored against hand-verified expected values; cost, turns and time are recorded too.
 *
 * <p>Usage: {@code ./gradlew bench -Pargs="--trials 3 --model claude-haiku-5-5"}, or
 * {@code -Pargs="--selftest"} to check the scoring with the reference and wrong answers (no API calls).
 */
public final class McpBenchmark {

    private static final JsonMapper JSON = JsonMapper.shared();
    private static final String SERVER = "legacy-explorer";

    enum Variant { WITHOUT_MCP, WITH_MCP }

    record Check(String key, String type, String pattern, List<String> ordered, Map<String, String> expectedSet) {}

    record Task(String id, String prompt, List<Check> checks, JsonNode reference, JsonNode wrong) {}

    record Score(boolean correct, List<String> notes) {}

    record Trial(String task, Variant variant, int trial, boolean correct, List<String> notes, double costUsd,
                 int turns, long millis, int mcpCalls, int otherToolCalls, boolean mcpConnected, String answer) {}

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        Path tasksFile = Path.of(opts.getOrDefault("tasks", "bench/tasks.json"));
        JsonNode root = JSON.readTree(Files.readString(tasksFile));
        List<Task> tasks = loadTasks(root);
        if (opts.containsKey("task")) tasks = tasks.stream().filter(t -> t.id().equals(opts.get("task"))).toList();

        if (opts.containsKey("selftest")) {
            selfTest(tasks);
            return;
        }

        Path app = Path.of(opts.getOrDefault("app", "../app")).toAbsolutePath().normalize();
        Path jar = Path.of(opts.getOrDefault("jar", "build/libs/legacy-explorer-mcp-all.jar")).toAbsolutePath().normalize();
        if (!Files.exists(jar)) throw new IllegalStateException("Build the server first: ./gradlew fatJar (" + jar + " missing)");
        int trials = Integer.parseInt(opts.getOrDefault("trials", "3"));
        String model = opts.get("model");
        String budget = opts.getOrDefault("max-budget-usd", "1.0");
        String suffix = root.path("suffix").asString("");

        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        Path outDir = Path.of(opts.getOrDefault("out", "bench/results")).resolve(stamp).toAbsolutePath();
        Files.createDirectories(outDir);
        Path work = Files.createTempDirectory("mcp-bench-");
        System.out.printf("%d task(s) x 2 variants x %d trial(s), model %s -> %s%n", tasks.size(), trials,
                model == null ? "default" : model, outDir);

        List<Trial> results = new ArrayList<>();
        for (Task task : tasks) {
            for (int trial = 1; trial <= trials; trial++) {
                List<Variant> order = trial % 2 == 1 ? List.of(Variant.WITHOUT_MCP, Variant.WITH_MCP) : List.of(Variant.WITH_MCP, Variant.WITHOUT_MCP);
                for (Variant v : order) {
                    Trial t = runTrial(task, v, trial, app, jar, model, budget, suffix, work, outDir);
                    results.add(t);
                    System.out.printf("%s / %s / %d: %s, $%.3f, %d turns, %d MCP calls%s%n", task.id(), v, trial,
                            t.correct() ? "CORRECT" : "wrong", t.costUsd(), t.turns(), t.mcpCalls(),
                            t.correct() ? "" : " " + t.notes());
                }
            }
        }
        Files.writeString(outDir.resolve("results.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(results));
        String report = report(results, model);
        Files.writeString(outDir.resolve("report.md"), report);
        System.out.println();
        System.out.println(report);
        deleteTree(work);
    }

    // ------------------------------------------------------------------ one run

    private static Trial runTrial(Task task, Variant variant, int trial, Path app, Path jar, String model, String budget,
                                  String suffix, Path work, Path outDir) throws Exception {
        Path dir = work.resolve(task.id() + "-" + variant.name().toLowerCase(Locale.ROOT) + "-" + trial);
        Path ws = dir.resolve("app");
        copyApp(app, ws);

        Path mcpConfig = dir.resolve("mcp.json");
        if (variant == Variant.WITH_MCP) {
            Map<String, Object> server = Map.of("command", "java", "args",
                    List.of("-jar", jar.toString(), ws.toString(), dir.resolve("audit.jsonl").toString()));
            Files.writeString(mcpConfig, JSON.writeValueAsString(Map.of("mcpServers", Map.of(SERVER, server))));
        } else {
            Files.writeString(mcpConfig, "{\"mcpServers\":{}}");
        }

        List<String> cmd = new ArrayList<>(List.of("claude", "-p", task.prompt() + "\n\n" + suffix,
                "--output-format", "stream-json", "--verbose",
                "--setting-sources", "project",
                "--strict-mcp-config", "--mcp-config", mcpConfig.toString(),
                "--tools", "Read,Grep,Glob,Bash",
                "--allowedTools", "Read,Grep,Glob,Bash(grep:*),Bash(find:*),Bash(ls:*),Bash(cat:*),Bash(head:*),Bash(wc:*),mcp__" + SERVER,
                "--max-budget-usd", budget));
        if (model != null) cmd.addAll(List.of("--model", model));

        long start = System.currentTimeMillis();
        String output = run(cmd, ws, 900);
        long millis = System.currentTimeMillis() - start;
        Files.writeString(outDir.resolve(task.id() + "-" + variant.name().toLowerCase(Locale.ROOT) + "-" + trial + ".jsonl"), output);

        double cost = 0;
        int turns = 0, mcpCalls = 0, otherCalls = 0;
        boolean connected = false;
        String finalText = "";
        for (String line : output.split("\n")) {
            if (!line.startsWith("{")) continue;
            JsonNode e;
            try {
                e = JSON.readTree(line);
            } catch (Exception ex) {
                continue;
            }
            switch (e.path("type").asString("")) {
                case "system" -> {
                    for (JsonNode s : e.path("mcp_servers").values()) {
                        if (SERVER.equals(s.path("name").asString("")) && "connected".equals(s.path("status").asString(""))) connected = true;
                    }
                }
                case "assistant" -> {
                    for (JsonNode c : e.path("message").path("content").values()) {
                        if ("tool_use".equals(c.path("type").asString(""))) {
                            if (c.path("name").asString("").startsWith("mcp__" + SERVER)) mcpCalls++; else otherCalls++;
                        }
                    }
                }
                case "result" -> {
                    cost = e.path("total_cost_usd").asDouble(0);
                    turns = e.path("num_turns").asInt(0);
                    finalText = e.path("result").asString("");
                }
                default -> { }
            }
        }
        JsonNode answer = extractJson(finalText);
        Score score = answer == null ? new Score(false, List.of("no JSON answer found")) : score(task, answer);
        if (variant == Variant.WITH_MCP && !connected) {
            score = new Score(false, List.of("MCP server did not connect: run invalid"));
        }
        return new Trial(task.id(), variant, trial, score.correct(), score.notes(), cost, turns, millis, mcpCalls, otherCalls,
                connected, answer == null ? finalText.substring(Math.max(0, finalText.length() - 400)) : answer.toString());
    }

    // ------------------------------------------------------------------ scoring

    static Score score(Task task, JsonNode answer) {
        List<String> notes = new ArrayList<>();
        for (Check c : task.checks()) {
            JsonNode value = answer.path(c.key());
            switch (c.type()) {
                case "match" -> {
                    String s = value.isString() ? value.asString() : "";
                    if (!Pattern.compile(c.pattern()).matcher(s.trim()).find()) notes.add(c.key() + ": got '" + s + "'");
                }
                case "set" -> {
                    List<String> items = new ArrayList<>();
                    value.values().forEach(v -> items.add(v.asString("").trim()));
                    for (var exp : c.expectedSet().entrySet()) {
                        if (items.stream().noneMatch(i -> Pattern.compile(exp.getValue()).matcher(i).find())) notes.add("missing " + exp.getKey());
                    }
                    for (String item : items) {
                        if (c.expectedSet().values().stream().noneMatch(p -> Pattern.compile(p).matcher(item).find())) notes.add("extra '" + item + "'");
                    }
                }
                case "ordered" -> {
                    List<String> items = new ArrayList<>();
                    value.values().forEach(v -> items.add(v.asString("").trim()));
                    int pos = 0;
                    for (String item : items) if (pos < c.ordered().size() && item.equals(c.ordered().get(pos))) pos++;
                    if (pos < c.ordered().size()) notes.add(c.key() + ": expected " + c.ordered() + " in order, got " + items);
                }
                default -> throw new IllegalArgumentException("Unknown check type " + c.type());
            }
        }
        return new Score(notes.isEmpty(), notes);
    }

    private static final Pattern JSON_BLOCK = Pattern.compile("```json\\s*(\\{.*?})\\s*```", Pattern.DOTALL);

    static JsonNode extractJson(String text) {
        Matcher m = JSON_BLOCK.matcher(text);
        String last = null;
        while (m.find()) last = m.group(1);
        if (last == null) {
            int start = text.lastIndexOf('{'), end = text.lastIndexOf('}');
            if (start >= 0 && end > start) last = text.substring(start, end + 1);
        }
        if (last == null) return null;
        try {
            return JSON.readTree(last);
        } catch (Exception e) {
            return null;
        }
    }

    private static void selfTest(List<Task> tasks) {
        boolean ok = true;
        for (Task t : tasks) {
            Score ref = score(t, t.reference());
            Score wrong = score(t, t.wrong());
            System.out.printf("%-42s reference: %s   wrong answer: %s %s%n", t.id(), ref.correct() ? "correct" : "WRONG " + ref.notes(),
                    wrong.correct() ? "ACCEPTED (bad check!)" : "rejected", wrong.correct() ? "" : wrong.notes());
            ok &= ref.correct() && !wrong.correct();
        }
        System.out.println(ok ? "Self-test passed." : "Self-test FAILED.");
        if (!ok) System.exit(1);
    }

    // ------------------------------------------------------------------ report

    static String report(List<Trial> results, String model) {
        StringBuilder md = new StringBuilder("# Legacy Explorer MCP benchmark\n\n");
        md.append("Model: ").append(model == null ? "default" : model)
          .append(". Same prompt, tools and fresh workspace for both variants; only the MCP server differs.\n\n");
        md.append("| task | correct without MCP | correct with MCP | avg cost without -> with | avg turns without -> with | avg time without -> with | avg MCP calls |\n");
        md.append("|---|---|---|---|---|---|---|\n");
        Map<String, List<Trial>> byTask = new LinkedHashMap<>();
        results.forEach(r -> byTask.computeIfAbsent(r.task(), k -> new ArrayList<>()).add(r));
        List<Trial> allWithout = new ArrayList<>(), allWith = new ArrayList<>();
        for (var e : byTask.entrySet()) {
            List<Trial> without = e.getValue().stream().filter(r -> r.variant() == Variant.WITHOUT_MCP).toList();
            List<Trial> with = e.getValue().stream().filter(r -> r.variant() == Variant.WITH_MCP).toList();
            allWithout.addAll(without);
            allWith.addAll(with);
            md.append(row(e.getKey(), without, with));
        }
        md.append(row("**all tasks**", allWithout, allWith));
        md.append("\n## Wrong answers\n\n");
        results.stream().filter(r -> !r.correct()).forEach(r ->
                md.append("- ").append(r.task()).append(" / ").append(r.variant()).append(" / trial ").append(r.trial())
                  .append(": ").append(r.notes()).append('\n'));
        if (results.stream().allMatch(Trial::correct)) md.append("None.\n");
        return md.toString();
    }

    private static String row(String name, List<Trial> without, List<Trial> with) {
        return String.format(Locale.ROOT, "| %s | %s | %s | $%.3f -> $%.3f | %.1f -> %.1f | %.0fs -> %.0fs | %.1f |%n", name,
                rate(without), rate(with), avg(without, Trial::costUsd), avg(with, Trial::costUsd),
                avg(without, t -> t.turns()), avg(with, t -> t.turns()),
                avg(without, t -> t.millis() / 1000.0), avg(with, t -> t.millis() / 1000.0), avg(with, t -> t.mcpCalls()));
    }

    private static String rate(List<Trial> ts) {
        long ok = ts.stream().filter(Trial::correct).count();
        return ok + "/" + ts.size();
    }

    private static double avg(List<Trial> ts, java.util.function.ToDoubleFunction<Trial> f) {
        return ts.stream().mapToDouble(f).average().orElse(0);
    }

    // ------------------------------------------------------------------ plumbing

    private static List<Task> loadTasks(JsonNode root) {
        List<Task> tasks = new ArrayList<>();
        for (JsonNode t : root.path("tasks").values()) {
            List<Check> checks = new ArrayList<>();
            for (JsonNode c : t.path("checks").values()) {
                List<String> ordered = new ArrayList<>();
                c.path("expected").values().forEach(v -> { if (v.isString()) ordered.add(v.asString()); });
                Map<String, String> set = new TreeMap<>();
                c.path("expected").values().forEach(v -> { if (v.isObject()) set.put(v.path("name").asString(), v.path("pattern").asString()); });
                checks.add(new Check(c.path("key").asString(), c.path("type").asString(), c.path("pattern").asString(null), ordered, set));
            }
            tasks.add(new Task(t.path("id").asString(), t.path("prompt").asString(), checks, t.path("reference_answer"), t.path("wrong_answer")));
        }
        return tasks;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            String key = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) opts.put(key, args[++i]); else opts.put(key, "true");
        }
        return opts;
    }

    /** Copies the app without build output, so the agent sees source, config and tests only. */
    private static void copyApp(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path src : paths.filter(p -> !from.relativize(p).startsWith("target")).toList()) {
                Path dest = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) Files.createDirectories(dest);
                else Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static String run(List<String> cmd, Path dir, int timeoutSeconds) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getOutputStream().close();
        CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> {
            try (var in = p.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                in.transferTo(buf);
                return buf.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
        return out.get(30, TimeUnit.SECONDS);
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
