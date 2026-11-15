package io.github.aaroua5.legacyexplorer;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * MCP server (stdio) that lets a coding agent ask structural questions about a legacy Java/Spring
 * XML/Hibernate application: entry points, call sites, XML wiring, table mappings, change impact.
 *
 * <p>Safety model: every tool is read-only and only looks inside the project root given at startup;
 * arguments are names, never paths; nothing is fetched from the network (DTDs included); every call
 * is appended to an audit log. Stdout carries the protocol, so diagnostics go to stderr only.
 *
 * <p>Usage: {@code java -jar legacy-explorer-mcp-all.jar <project-root> [audit-log-file]}
 */
public final class LegacyExplorerServer {

    private static final int MAX_ARGUMENT_LENGTH = 300;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: legacy-explorer <project-root> [audit-log-file]");
            System.exit(2);
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path audit = args.length > 1 ? Path.of(args[1])
                : Path.of(System.getProperty("user.home"), ".legacy-explorer", "audit.jsonl");

        long start = System.currentTimeMillis();
        CodeIndex index = CodeIndex.load(root);
        System.err.printf("legacy-explorer: indexed %s in %d ms: %d Java files, %d beans, %d entry points, %d entities; audit log %s%n",
                root, System.currentTimeMillis() - start, index.javaSources.size(), index.beans.size(),
                index.entryPoints.size(), index.entities.size(), audit);

        LegacyTools tools = new LegacyTools(index);
        AuditLog auditLog = new AuditLog(audit, root);

        McpServer.sync(new StdioServerTransportProvider(McpJsonDefaults.getMapper()))
                .serverInfo("legacy-explorer", "0.1.0")
                .instructions("Read-only structural analysis of the legacy application at " + root + ". "
                        + "Use it before changing legacy code: Spring XML wiring, RMI links and Hibernate mappings are "
                        + "invisible to plain text search. Start with entry_points, then impact_of_change for the class you will edit.")
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .tools(
                        tool("entry_points",
                                "Lists every way into the application: servlets with URLs, web controllers, JMS listeners and their queues, "
                                        + "SOAP endpoints, RMI services, scheduled tasks, startup listeners and filters, each with file:line.",
                                null, null, a -> tools.entryPoints(), auditLog),
                        tool("find_usages",
                                "Finds where a class is used, or where a method is called. Pass a class name (simple or fully qualified) "
                                        + "or Class#method. Method calls made through interfaces and superclasses are included and marked; "
                                        + "calls whose receiver type cannot be resolved are listed separately for manual checking.",
                                "symbol", "Class name, or Class#method, e.g. CargoRepository#find",
                                a -> tools.findUsages(a), auditLog),
                        tool("spring_wiring",
                                "Shows how a Spring XML bean is wired: where it is defined, its configuration, what it depends on, "
                                        + "what it is injected into (across application contexts and RMI), and the paths from entry points to it.",
                                "bean_or_class", "Bean id (e.g. cargoRepository) or class name",
                                a -> tools.springWiring(a), auditLog),
                        tool("entity_mapping",
                                "Shows a Hibernate .hbm.xml mapping: table, field-to-column mapping, relations, and the HQL/SQL query "
                                        + "strings in the code that name the entity.",
                                "entity_or_table", "Entity class name or table name, e.g. Cargo",
                                a -> tools.entityMapping(a), auditLog),
                        tool("impact_of_change",
                                "Before changing a class: lists implementations, Java files and tests that reference it, the Spring beans "
                                        + "of that type with the entry points that reach them, its database mapping, and configuration files "
                                        + "that name it.",
                                "class_name", "Simple or fully qualified class name",
                                a -> tools.impactOfChange(a), auditLog))
                .build();

        new CountDownLatch(1).await(); // the client stops the process when it is done
    }

    private static SyncToolSpecification tool(String name, String description, String param, String paramDescription,
                                               Function<String, String> handler, AuditLog auditLog) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        if (param != null) {
            schema.put("properties", Map.of(param, Map.of("type", "string", "description", paramDescription)));
            schema.put("required", List.of(param));
        } else {
            schema.put("properties", Map.of());
        }
        Tool tool = Tool.builder(name, schema)
                .description(description)
                .annotations(ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(false).build())
                .build();

        return SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            long start = System.currentTimeMillis();
            Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
            String value = param == null ? null : String.valueOf(arguments.getOrDefault(param, "")).trim();
            String text;
            boolean error = false;
            if (param != null && (value.isEmpty() || value.length() > MAX_ARGUMENT_LENGTH || value.contains("/") || value.contains("\\"))) {
                text = "Invalid '" + param + "': expected a non-empty name (no paths), at most " + MAX_ARGUMENT_LENGTH + " characters.";
                error = true;
            } else {
                try {
                    text = handler.apply(value);
                } catch (RuntimeException e) {
                    text = "Tool failed: " + e;
                    error = true;
                }
            }
            auditLog.record(name, arguments, System.currentTimeMillis() - start, text.length(), error);
            return CallToolResult.builder().addTextContent(text).isError(error).build();
        }).build();
    }

    /** Append-only JSON-lines log of every tool call: what was asked, how long it took, how much was returned. */
    static final class AuditLog {
        private final Path file;
        private final Path root;

        AuditLog(Path file, Path root) throws IOException {
            this.file = file;
            this.root = root;
            Files.createDirectories(file.toAbsolutePath().getParent());
        }

        synchronized void record(String tool, Map<String, Object> arguments, long millis, int outputChars, boolean error) {
            String args = arguments.entrySet().stream()
                    .map(e -> quote(e.getKey()) + ":" + quote(String.valueOf(e.getValue())))
                    .collect(Collectors.joining(",", "{", "}"));
            String line = "{\"ts\":" + quote(Instant.now().toString()) + ",\"project\":" + quote(root.toString())
                    + ",\"tool\":" + quote(tool) + ",\"args\":" + args + ",\"ms\":" + millis
                    + ",\"outputChars\":" + outputChars + ",\"error\":" + error + "}\n";
            try {
                Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("legacy-explorer: could not write audit log: " + e.getMessage());
            }
        }

        private static String quote(String s) {
            StringBuilder b = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r");
                    case '\t' -> b.append("\\t");
                    default -> b.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
                }
            }
            return b.append('"').toString();
        }
    }
}
