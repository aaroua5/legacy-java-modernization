package io.github.aaroua5.legacyexplorer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Read-only index of a legacy Java web application (Maven layout): Java sources, Spring XML bean
 * definitions grouped by application context, web.xml, and Hibernate {@code .hbm.xml} mappings.
 * Built once at startup; nothing is ever written to the analyzed project.
 */
final class CodeIndex {

    record JavaSource(String file, CompilationUnit cu, boolean test) {}

    record TypeDecl(String fqn, String file, int line) {}

    /**
     * @param context which Spring application context the bean lives in ("root" or a servlet name)
     * @param refs    ids of beans this bean depends on (property/constructor refs, inner beans, RMI links)
     */
    record Bean(String id, String className, String file, int line, String context, Set<String> refs, Map<String, String> properties) {}

    record EntryPoint(String kind, String description, String beanId, String context, String file, int line) {}

    record Entity(String className, String table, String file, int line, List<String> columns, List<String> relations) {}

    record Hql(String entity, String file, int line, String query) {}

    final Path root;
    final List<JavaSource> javaSources = new ArrayList<>();
    final Map<String, TypeDecl> types = new LinkedHashMap<>();
    final List<Bean> beans = new ArrayList<>();
    final List<EntryPoint> entryPoints = new ArrayList<>();
    final Map<String, Entity> entities = new LinkedHashMap<>();
    final List<Hql> hql = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();

    private final Path mainJava;
    private final Path testJava;
    private final Path resources;
    private final Path webInf;

    private CodeIndex(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.mainJava = this.root.resolve("src/main/java");
        this.testJava = this.root.resolve("src/test/java");
        this.resources = this.root.resolve("src/main/resources");
        this.webInf = this.root.resolve("src/main/webapp/WEB-INF");
    }

    static CodeIndex load(Path root) throws IOException {
        CodeIndex index = new CodeIndex(root);
        if (!Files.isDirectory(index.mainJava)) {
            throw new IllegalArgumentException("Not a Maven-style project (no src/main/java): " + index.root);
        }
        index.loadJava();
        index.loadSpringAndWebXml();
        index.loadHibernateMappings();
        index.findHql();
        return index;
    }

    String rel(Path p) {
        return root.relativize(p.toAbsolutePath().normalize()).toString();
    }

    // ------------------------------------------------------------------ Java

    private void loadJava() throws IOException {
        CombinedTypeSolver solver = new CombinedTypeSolver(new ReflectionTypeSolver(true), new JavaParserTypeSolver(mainJava));
        if (Files.isDirectory(testJava)) solver.add(new JavaParserTypeSolver(testJava));
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
                .setSymbolResolver(new JavaSymbolSolver(solver)));

        for (Path dir : List.of(mainJava, testJava)) {
            if (!Files.isDirectory(dir)) continue;
            for (Path file : listFiles(dir, ".java")) {
                ParseResult<CompilationUnit> result = parser.parse(file);
                if (result.getResult().isEmpty() || !result.isSuccessful()) {
                    warnings.add("Could not parse " + rel(file) + ": " + result.getProblems());
                    continue;
                }
                CompilationUnit cu = result.getResult().get();
                JavaSource source = new JavaSource(rel(file), cu, dir.equals(testJava));
                javaSources.add(source);
                for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
                    t.getFullyQualifiedName().ifPresent(fqn ->
                            types.put(fqn, new TypeDecl(fqn, source.file(), t.getBegin().map(b -> b.line).orElse(0))));
                }
            }
        }
    }

    // ------------------------------------------------------------------ Spring + web.xml

    private void loadSpringAndWebXml() throws IOException {
        Path webXml = webInf.resolve("web.xml");
        Map<Path, String> contextOf = new LinkedHashMap<>();
        XmlTree web = null;
        if (Files.exists(webXml)) {
            web = parse(webXml);
            for (XmlTree param : web.childrenNamed("context-param")) {
                if ("contextConfigLocation".equals(param.childText("param-name"))) {
                    for (String location : param.childText("param-value").split("[,\\s]+")) {
                        Path file = resolveLocation(location);
                        if (file != null) contextOf.put(file, "root");
                    }
                }
            }
            for (XmlTree servlet : web.childrenNamed("servlet")) {
                Path servletXml = webInf.resolve(servlet.childText("servlet-name") + "-servlet.xml");
                if (Files.exists(servletXml)) contextOf.put(servletXml, servlet.childText("servlet-name"));
            }
        }
        // Follow <import resource="..."/> inside each context
        List<Path> queue = new ArrayList<>(contextOf.keySet());
        for (int i = 0; i < queue.size(); i++) {
            XmlTree tree = parse(queue.get(i));
            if (tree == null) continue;
            for (XmlTree imp : tree.childrenNamed("import")) {
                Path file = resolveLocation(imp.attr("resource"));
                if (file != null && !contextOf.containsKey(file)) {
                    contextOf.put(file, contextOf.get(queue.get(i)));
                    queue.add(file);
                }
            }
        }
        // Any other Spring file in the project is indexed too, marked as not loaded by web.xml
        for (Path dir : List.of(resources, webInf)) {
            if (!Files.isDirectory(dir)) continue;
            for (Path file : listFiles(dir, ".xml")) {
                if (!contextOf.containsKey(file)) {
                    XmlTree tree = parse(file);
                    if (tree != null && tree.localName().equals("beans")) contextOf.put(file, "not loaded by web.xml");
                }
            }
        }
        for (Map.Entry<Path, String> e : contextOf.entrySet()) {
            XmlTree tree = parse(e.getKey());
            if (tree != null && tree.localName().equals("beans")) readBeans(tree, e.getKey(), e.getValue());
        }
        linkRmi();
        if (web != null) readWebEntryPoints(web, webXml);
    }

    private Path resolveLocation(String location) {
        if (location == null || location.isBlank()) return null;
        String l = location.trim();
        Path file;
        if (l.startsWith("classpath:")) {
            String p = l.substring("classpath:".length()).replaceFirst("^/+", "");
            if (p.startsWith("META-INF/cxf/")) return null; // shipped inside the CXF jar
            file = resources.resolve(p);
        } else {
            file = l.startsWith("/WEB-INF/") ? webInf.resolve(l.substring("/WEB-INF/".length())) : resources.resolve(l);
        }
        if (!Files.exists(file)) {
            warnings.add("Context location not found in project: " + location);
            return null;
        }
        return file.toAbsolutePath().normalize();
    }

    private void readBeans(XmlTree beansRoot, Path file, String context) {
        int[] anonymous = {0};
        for (XmlTree node : beansRoot.children()) {
            String local = node.localName();
            if (local.equals("bean")) {
                readBean(node, file, context, null, anonymous);
            } else if (node.name().equals("jms:listener-container")) {
                for (XmlTree listener : node.childrenNamed("listener")) {
                    entryPoints.add(new EntryPoint("JMS listener", "queue " + listener.attr("destination"),
                            listener.attr("ref"), context, rel(file), listener.line()));
                }
            } else if (local.equals("endpoint") && node.name().startsWith("jaxws:")) {
                String impl = node.attr("implementor");
                String beanId = impl != null && impl.startsWith("#") ? impl.substring(1) : null;
                entryPoints.add(new EntryPoint("SOAP endpoint", "address " + node.attr("address")
                        + " (served under the CXF servlet mapping)", beanId, context, rel(file), node.line()));
            } else if (node.attr("id") != null) {
                // Namespace beans such as amq:queue, amq:connectionFactory
                beans.add(new Bean(node.attr("id"), node.name(), rel(file), node.line(), context, new LinkedHashSet<>(), node.attributes()));
            }
        }
    }

    private String readBean(XmlTree node, Path file, String context, String parentId, int[] anonymous) {
        String id = node.attr("id");
        if (id == null && node.attr("name") != null) id = node.attr("name").split("[,;\\s]+")[0];
        if (id == null) {
            String cls = node.attr("class") == null ? "bean" : simpleName(node.attr("class"));
            id = parentId == null ? "(unnamed " + cls + " #" + (++anonymous[0]) + ")" : parentId + "#inner" + (++anonymous[0]);
        }
        Set<String> refs = new LinkedHashSet<>();
        Map<String, String> props = new LinkedHashMap<>();
        Bean bean = new Bean(id, node.attr("class"), rel(file), node.line(), context, refs, props);
        beans.add(bean);
        String beanId = id;
        for (XmlTree child : node.children()) {
            if (child.localName().equals("property") || child.localName().equals("constructor-arg")) {
                String key = child.localName().equals("property") ? child.attr("name") : "constructor-arg";
                if (child.attr("ref") != null) {
                    refs.add(child.attr("ref"));
                    props.put(key, "ref " + child.attr("ref"));
                } else if (child.attr("value") != null) {
                    props.put(key, child.attr("value"));
                }
                child.walk(n -> {
                    if (n == child) return;
                    switch (n.localName()) {
                        case "ref", "idref" -> {
                            String r = n.attr("bean") != null ? n.attr("bean") : n.attr("local");
                            if (r != null) refs.add(r);
                        }
                        case "value" -> refs.add("value:" + n.textValue()); // resolved below if it names a bean
                        default -> { }
                    }
                });
                for (XmlTree inner : child.childrenNamed("bean")) {
                    refs.add(readBean(inner, file, context, beanId, anonymous));
                }
            }
        }
        if (node.attr("class") != null && node.attr("class").endsWith("ScheduledTimerTask")) {
            String task = props.getOrDefault("timerTask", "");
            entryPoints.add(new EntryPoint("Scheduled task", "every " + props.get("period") + " ms, after "
                    + props.get("delay") + " ms", task.startsWith("ref ") ? task.substring(4) : null, context, rel(file), node.line()));
        }
        if (node.attr("class") != null && node.attr("class").endsWith("RmiServiceExporter")) {
            String service = props.getOrDefault("service", "");
            entryPoints.add(new EntryPoint("RMI service", "rmi name '" + props.get("serviceName") + "', interface "
                    + props.get("serviceInterface"), service.startsWith("ref ") ? service.substring(4) : null, context, rel(file), node.line()));
        }
        return id;
    }

    /** Turns "value:x" placeholders into real refs when x is a bean id, and drops the rest. */
    private void resolveValueRefs() {
        Set<String> ids = new LinkedHashSet<>();
        beans.forEach(b -> ids.add(b.id()));
        for (Bean b : beans) {
            List<String> values = b.refs().stream().filter(r -> r.startsWith("value:")).toList();
            b.refs().removeAll(values);
            for (String v : values) {
                String candidate = v.substring("value:".length());
                if (ids.contains(candidate)) b.refs().add(candidate);
            }
        }
    }

    /** An RmiProxyFactoryBean in one context depends on the bean exported under the same RMI name elsewhere. */
    private void linkRmi() {
        resolveValueRefs();
        Map<String, String> exported = new LinkedHashMap<>();
        for (Bean b : beans) {
            if (b.className() != null && b.className().endsWith("RmiServiceExporter")) {
                String service = b.properties().getOrDefault("service", "");
                if (service.startsWith("ref ")) exported.put(b.properties().get("serviceName"), service.substring(4));
            }
        }
        for (Bean b : beans) {
            if (b.className() != null && b.className().endsWith("RmiProxyFactoryBean")) {
                String url = b.properties().getOrDefault("serviceUrl", "");
                String name = url.substring(url.lastIndexOf('/') + 1);
                if (exported.containsKey(name)) b.refs().add("rmi:" + exported.get(name));
            }
        }
    }

    private void readWebEntryPoints(XmlTree web, Path webXml) {
        Map<String, List<String>> patterns = new LinkedHashMap<>();
        for (XmlTree m : web.childrenNamed("servlet-mapping")) {
            patterns.computeIfAbsent(m.childText("servlet-name"), k -> new ArrayList<>()).add(m.childText("url-pattern"));
        }
        for (XmlTree s : web.childrenNamed("servlet")) {
            String name = s.childText("servlet-name");
            List<String> urls = patterns.getOrDefault(name, List.of());
            entryPoints.add(new EntryPoint("Servlet", name + " (" + s.childText("servlet-class") + ") at " + urls,
                    null, name, rel(webXml), s.line()));
            // Controllers mapped by bean name (BeanNameUrlHandlerMapping) in <name>-servlet.xml
            for (Bean b : beans) {
                if (b.context().equals(name) && b.id().startsWith("/")) {
                    for (String pattern : urls) {
                        String url = pattern.replaceFirst("/\\*$", "") + b.id();
                        entryPoints.add(new EntryPoint("Web controller", "URL " + url, b.id(), name, b.file(), b.line()));
                    }
                }
            }
        }
        for (XmlTree l : web.childrenNamed("listener")) {
            entryPoints.add(new EntryPoint("Startup listener", l.childText("listener-class"), null, "web.xml", rel(webXml), l.line()));
        }
        for (XmlTree f : web.childrenNamed("filter")) {
            entryPoints.add(new EntryPoint("Servlet filter", f.childText("filter-name") + " (" + f.childText("filter-class") + ")",
                    null, "web.xml", rel(webXml), f.line()));
        }
    }

    // ------------------------------------------------------------------ Hibernate

    private void loadHibernateMappings() throws IOException {
        if (!Files.isDirectory(resources)) return;
        for (Path file : listFiles(resources, ".hbm.xml")) {
            XmlTree mapping = parse(file);
            if (mapping == null) continue;
            String pkg = mapping.attr("package");
            for (XmlTree cls : mapping.childrenNamed("class")) {
                String name = cls.attr("name");
                if (pkg != null && !name.contains(".")) name = pkg + "." + name;
                List<String> columns = new ArrayList<>();
                List<String> relations = new ArrayList<>();
                collectMapping(cls, "", columns, relations);
                entities.put(name, new Entity(name, cls.attr("table") != null ? cls.attr("table") : simpleName(name),
                        rel(file), cls.line(), columns, relations));
            }
        }
    }

    private void collectMapping(XmlTree node, String prefix, List<String> columns, List<String> relations) {
        for (XmlTree c : node.children()) {
            String n = c.attr("name");
            switch (c.localName()) {
                case "id", "property" -> columns.add(prefix + n + " -> column " + column(c, n));
                case "many-to-one" -> relations.add(prefix + n + " -> many-to-one" + (c.attr("class") != null ? " " + c.attr("class") : "")
                        + " via column " + column(c, n));
                case "component" -> collectMapping(c, prefix + n + ".", columns, relations);
                case "list", "bag", "set", "map" -> {
                    String target = c.childrenNamed("one-to-many").stream().map(x -> x.attr("class")).findFirst()
                            .orElse(c.childrenNamed("many-to-many").stream().map(x -> x.attr("class")).findFirst().orElse("elements"));
                    String key = c.childrenNamed("key").stream().map(k -> k.attr("column")).findFirst().orElse("?");
                    relations.add(prefix + n + " -> " + c.localName() + " of " + target
                            + (c.attr("table") != null ? " in table " + c.attr("table") : "") + ", key column " + key);
                }
                default -> { }
            }
        }
    }

    private static String column(XmlTree node, String name) {
        if (node.attr("column") != null) return node.attr("column");
        return node.childrenNamed("column").stream().map(c -> c.attr("name")).findFirst().orElse(name);
    }

    private static final Pattern HQL = Pattern.compile("\\b(?:from|join|update|delete\\s+from)\\s+([A-Z]\\w*)", Pattern.CASE_INSENSITIVE);

    private void findHql() {
        Map<String, String> bySimpleName = new LinkedHashMap<>();
        entities.keySet().forEach(fqn -> bySimpleName.put(simpleName(fqn), fqn));
        for (JavaSource s : javaSources) {
            for (StringLiteralExpr lit : s.cu().findAll(StringLiteralExpr.class)) {
                Matcher m = HQL.matcher(lit.getValue());
                while (m.find()) {
                    String entity = bySimpleName.get(m.group(1));
                    if (entity != null) {
                        hql.add(new Hql(entity, s.file(), lit.getBegin().map(b -> b.line).orElse(0), lit.getValue()));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    Collection<Bean> beansById(String id) {
        return beans.stream().filter(b -> b.id().equals(id)).toList();
    }

    static String simpleName(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }

    private XmlTree parse(Path file) {
        try {
            return XmlTree.parse(file);
        } catch (Exception e) {
            warnings.add("Could not parse " + rel(file) + ": " + e.getMessage());
            return null;
        }
    }

    private static List<Path> listFiles(Path dir, String suffix) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(suffix)).map(p -> p.toAbsolutePath().normalize()).sorted().toList();
        }
    }
}
