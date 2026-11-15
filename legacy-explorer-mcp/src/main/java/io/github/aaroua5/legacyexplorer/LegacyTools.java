package io.github.aaroua5.legacyexplorer;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import io.github.aaroua5.legacyexplorer.CodeIndex.Bean;
import io.github.aaroua5.legacyexplorer.CodeIndex.Entity;
import io.github.aaroua5.legacyexplorer.CodeIndex.EntryPoint;
import io.github.aaroua5.legacyexplorer.CodeIndex.JavaSource;
import io.github.aaroua5.legacyexplorer.CodeIndex.TypeDecl;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The questions the MCP server answers. Every method is read-only and returns plain text with
 * {@code file:line} references relative to the analyzed project, so an agent can open the exact spot.
 */
final class LegacyTools {

    private final CodeIndex index;
    private final Map<String, List<String>> lineCache = new LinkedHashMap<>();

    LegacyTools(CodeIndex index) {
        this.index = index;
    }

    // ================================================================== entry_points

    String entryPoints() {
        StringBuilder out = new StringBuilder("Entry points: every way requests, messages or timers get into the application.\n");
        Map<String, List<EntryPoint>> byKind = new TreeMap<>();
        index.entryPoints.forEach(e -> byKind.computeIfAbsent(e.kind(), k -> new ArrayList<>()).add(e));
        byKind.forEach((kind, list) -> {
            out.append("\n## ").append(kind).append(" (").append(list.size()).append(")\n");
            for (EntryPoint e : list) {
                out.append("- ").append(e.description());
                if (e.beanId() != null) out.append(" -> bean '").append(e.beanId()).append("'").append(beanClassSuffix(e.beanId(), e.context()));
                out.append("  [").append(e.file()).append(':').append(e.line()).append("]\n");
            }
        });
        if (!index.warnings.isEmpty()) out.append("\nIndex warnings:\n").append(index.warnings.stream().map(w -> "- " + w).collect(Collectors.joining("\n"))).append('\n');
        return out.toString();
    }

    // ================================================================== find_usages

    String findUsages(String symbol) {
        String typeName = symbol.contains("#") ? symbol.substring(0, symbol.indexOf('#')) : symbol;
        String method = symbol.contains("#") ? symbol.substring(symbol.indexOf('#') + 1).replace("()", "") : null;
        Resolution type = resolveType(typeName);
        if (type.error != null) return type.error;
        return method == null ? classUsages(type.fqn) : methodUsages(type.fqn, method);
    }

    private String classUsages(String fqn) {
        String simple = CodeIndex.simpleName(fqn);
        StringBuilder out = new StringBuilder("Usages of " + fqn + "\n");
        declaredAt(fqn).ifPresent(d -> out.append("Declared at ").append(d).append('\n'));

        Map<String, List<String>> main = new TreeMap<>();
        Map<String, List<String>> test = new TreeMap<>();
        for (JavaSource s : index.javaSources) {
            for (int line : typeReferenceLines(s, fqn, simple)) {
                (s.test() ? test : main).computeIfAbsent(s.file(), k -> new ArrayList<>()).add(lineRef(s.file(), line));
            }
        }
        appendGrouped(out, "Java references in main code", main);
        appendGrouped(out, "Java references in tests", test);
        appendNonJava(out, fqn);
        return out.toString();
    }

    private String methodUsages(String fqn, String method) {
        Set<String> relevant = new LinkedHashSet<>();
        relevant.add(fqn);
        relevant.addAll(ancestors(fqn));
        StringBuilder out = new StringBuilder("Call sites of " + fqn + "#" + method + "\n");
        declaredAt(fqn).ifPresent(d -> out.append("Class declared at ").append(d).append('\n'));
        if (relevant.size() > 1) {
            out.append("Also counts calls made through its supertypes: ")
               .append(relevant.stream().skip(1).collect(Collectors.joining(", "))).append('\n');
        }
        List<String> resolved = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (JavaSource s : index.javaSources) {
            for (MethodCallExpr call : s.cu().findAll(MethodCallExpr.class)) {
                if (!call.getNameAsString().equals(method)) continue;
                String declaring = declaringTypeOf(call);
                int line = line(call);
                if (declaring == null) {
                    unresolved.add(lineRef(s.file(), line) + (s.test() ? "  (test)" : ""));
                } else if (relevant.contains(declaring)) {
                    String via = declaring.equals(fqn) ? "" : "  (via " + CodeIndex.simpleName(declaring) + ")";
                    resolved.add(lineRef(s.file(), line) + via + (s.test() ? "  (test)" : ""));
                }
            }
            for (MethodReferenceExpr ref : s.cu().findAll(MethodReferenceExpr.class)) {
                if (ref.getIdentifier().equals(method)) unresolved.add(lineRef(s.file(), line(ref)) + "  (method reference)");
            }
        }
        out.append("\nResolved call sites (").append(resolved.size()).append("):\n");
        resolved.forEach(r -> out.append("  ").append(r).append('\n'));
        out.append("\nUnresolved candidates: a method named '").append(method)
           .append("' is called but the receiver's type could not be resolved; check these by hand (").append(unresolved.size()).append("):\n");
        unresolved.forEach(r -> out.append("  ").append(r).append('\n'));

        List<String> xml = new ArrayList<>();
        for (Bean b : index.beans) {
            if (fqn.equals(b.className())) {
                index.entryPoints.stream().filter(e -> b.id().equals(e.beanId()) && e.kind().equals("JMS listener") && method.equals("onMessage"))
                        .forEach(e -> xml.add("JMS delivers messages from " + e.description() + " to this method  [" + e.file() + ":" + e.line() + "]"));
            }
        }
        if (!xml.isEmpty()) {
            out.append("\nCalled by the framework (from XML configuration):\n");
            xml.forEach(x -> out.append("  ").append(x).append('\n'));
        }
        return out.toString();
    }

    // ================================================================== spring_wiring

    String springWiring(String beanOrClass) {
        List<Bean> matches = index.beans.stream().filter(b -> b.id().equals(beanOrClass)).collect(Collectors.toList());
        if (matches.isEmpty()) {
            Resolution type = resolveType(beanOrClass);
            if (type.error != null) return "No bean with id '" + beanOrClass + "'. " + type.error;
            matches = index.beans.stream().filter(b -> type.fqn.equals(b.className())).collect(Collectors.toList());
            if (matches.isEmpty()) {
                return "No Spring XML bean is defined with class " + type.fqn
                        + ". It is not created by Spring XML (it may be created with 'new', by Hibernate, or not at all).";
            }
        }
        StringBuilder out = new StringBuilder();
        for (Bean b : matches) {
            out.append("Bean '").append(b.id()).append("' (").append(b.className()).append(")\n");
            out.append("  defined at ").append(b.file()).append(':').append(b.line()).append(", application context: ").append(b.context()).append('\n');
            if (!b.properties().isEmpty()) {
                out.append("  configuration:\n");
                b.properties().forEach((k, v) -> out.append("    ").append(k).append(" = ").append(v).append('\n'));
            }
            List<String> deps = b.refs().stream().map(r -> describeRef(r, b.context())).toList();
            out.append("  depends on (").append(deps.size()).append("):").append(deps.isEmpty() ? " nothing" : "").append('\n');
            deps.forEach(d -> out.append("    ").append(d).append('\n'));
            List<Bean> injectedInto = dependents(b);
            out.append("  injected into (").append(injectedInto.size()).append("):").append(injectedInto.isEmpty() ? " nothing" : "").append('\n');
            injectedInto.forEach(d -> out.append("    '").append(d.id()).append("'").append(classSuffix(d.className()))
                    .append("  [").append(d.file()).append(':').append(d.line()).append("]").append(d.context().equals(b.context()) ? "" : "  (context: " + d.context() + ")").append('\n'));
            List<String> paths = entryPointPaths(b);
            out.append("  reachable from entry points (").append(paths.size()).append("):").append(paths.isEmpty() ? " none" : "").append('\n');
            paths.forEach(p -> out.append("    ").append(p).append('\n'));
            if (b.className() != null && index.types.containsKey(b.className()) && !isInstantiatedInJava(b.className())) {
                out.append("  NOTE: no Java code creates this class with 'new'. It is wired only in XML, so a Java-only\n")
                   .append("        'find usages' or text search for the constructor will not show where it is created.\n");
            }
            out.append('\n');
        }
        return out.toString().trim() + "\n";
    }

    // ================================================================== entity_mapping

    String entityMapping(String entityOrTable) {
        List<Entity> matches = index.entities.values().stream()
                .filter(e -> e.className().equals(entityOrTable) || CodeIndex.simpleName(e.className()).equals(entityOrTable)
                        || e.table().equalsIgnoreCase(entityOrTable))
                .toList();
        if (matches.isEmpty()) {
            return "No Hibernate mapping for '" + entityOrTable + "'. Mapped entities: "
                    + index.entities.values().stream().map(e -> CodeIndex.simpleName(e.className()) + " (table " + e.table() + ")").collect(Collectors.joining(", "));
        }
        StringBuilder out = new StringBuilder();
        for (Entity e : matches) {
            out.append("Entity ").append(e.className()).append(" -> table ").append(e.table())
               .append("  [").append(e.file()).append(':').append(e.line()).append("]\n");
            out.append("Columns:\n");
            e.columns().forEach(c -> out.append("  ").append(c).append('\n'));
            out.append("Relations:").append(e.relations().isEmpty() ? " none" : "").append('\n');
            e.relations().forEach(r -> out.append("  ").append(r).append('\n'));
            List<CodeIndex.Hql> queries = index.hql.stream().filter(h -> h.entity().equals(e.className())).toList();
            out.append("HQL or SQL query strings naming this entity (").append(queries.size()).append("):\n");
            queries.forEach(q -> out.append("  ").append(q.file()).append(':').append(q.line()).append("  \"").append(q.query().trim()).append("\"\n"));
            List<String> referencedBy = index.entities.values().stream()
                    .filter(other -> other.relations().stream().anyMatch(r -> r.contains(e.className())))
                    .map(other -> CodeIndex.simpleName(other.className())).toList();
            if (!referencedBy.isEmpty()) out.append("Other mappings pointing at it: ").append(String.join(", ", referencedBy)).append('\n');
            out.append('\n');
        }
        return out.toString().trim() + "\n";
    }

    // ================================================================== impact_of_change

    String impactOfChange(String className) {
        Resolution type = resolveType(className);
        if (type.error != null) return type.error;
        String fqn = type.fqn;
        String simple = CodeIndex.simpleName(fqn);
        StringBuilder out = new StringBuilder("Impact of changing " + fqn + "\n");
        declaredAt(fqn).ifPresent(d -> out.append("Declared at ").append(d).append('\n'));

        List<String> implementations = implementationsOf(fqn);
        if (!implementations.isEmpty()) {
            out.append("\nImplementations / subclasses (").append(implementations.size()).append("): ").append(String.join(", ", implementations)).append('\n');
        }

        Set<String> mainDependents = new java.util.TreeSet<>();
        Set<String> testDependents = new java.util.TreeSet<>();
        for (JavaSource s : index.javaSources) {
            if (index.types.containsKey(fqn) && index.types.get(fqn).file().equals(s.file())) continue;
            if (!typeReferenceLines(s, fqn, simple).isEmpty()) (s.test() ? testDependents : mainDependents).add(s.file());
        }
        out.append("\nJava files that reference it directly (").append(mainDependents.size()).append("):\n");
        mainDependents.forEach(f -> out.append("  ").append(f).append('\n'));
        out.append("\nTests that reference it directly; run at least these (").append(testDependents.size()).append("):\n");
        testDependents.forEach(f -> out.append("  ").append(f).append('\n'));

        Set<String> beanClasses = new LinkedHashSet<>();
        beanClasses.add(fqn);
        beanClasses.addAll(implementations);
        List<Bean> beans = index.beans.stream().filter(b -> beanClasses.contains(b.className())).toList();
        out.append("\nSpring beans of this type (").append(beans.size()).append("):").append(beans.isEmpty() ? " none" : "").append('\n');
        for (Bean b : beans) {
            out.append("  '").append(b.id()).append("'").append(classSuffix(b.className())).append("  [").append(b.file()).append(':').append(b.line()).append("]\n");
            for (String p : entryPointPaths(b)) out.append("    reached from ").append(p).append('\n');
        }

        Entity entity = index.entities.get(fqn);
        if (entity != null) {
            out.append("\nPersistent entity: mapped to table ").append(entity.table()).append(" in ").append(entity.file())
               .append(". Renaming or retyping mapped fields changes the database schema or breaks the mapping.\n");
            index.hql.stream().filter(h -> h.entity().equals(fqn))
                    .forEach(h -> out.append("  query string at ").append(h.file()).append(':').append(h.line()).append("  \"").append(h.query().trim()).append("\"\n"));
        }
        List<String> embeddedIn = index.entities.values().stream()
                .filter(e -> !e.className().equals(fqn) && e.relations().stream().anyMatch(r -> r.contains(fqn)))
                .map(e -> CodeIndex.simpleName(e.className()) + " [" + e.file() + "]").toList();
        if (!embeddedIn.isEmpty()) out.append("Referenced from Hibernate mappings: ").append(String.join(", ", embeddedIn)).append('\n');

        List<String> xmlMentions = nonJavaMentions(fqn);
        if (!xmlMentions.isEmpty()) {
            out.append("\nMentioned by name in configuration files (renaming the class breaks these):\n");
            xmlMentions.forEach(m -> out.append("  ").append(m).append('\n'));
        }
        return out.toString();
    }

    // ================================================================== Java analysis helpers

    private record Resolution(String fqn, String error) {}

    private Resolution resolveType(String name) {
        if (index.types.containsKey(name)) return new Resolution(name, null);
        List<String> candidates = index.types.keySet().stream().filter(f -> CodeIndex.simpleName(f).equals(name)).toList();
        if (candidates.size() == 1) return new Resolution(candidates.get(0), null);
        if (candidates.size() > 1) return new Resolution(null, "'" + name + "' is ambiguous; use one of: " + String.join(", ", candidates));
        if (name.contains(".")) return new Resolution(name, null); // a library class: still searchable by name
        return new Resolution(null, "No class named '" + name + "' in this project. Use a simple or fully qualified class name.");
    }

    /** Lines in a file that refer to the type: declarations, 'new', casts, generics, class literals, static access. */
    private List<Integer> typeReferenceLines(JavaSource s, String fqn, String simple) {
        String pkg = fqn.contains(".") ? fqn.substring(0, fqn.lastIndexOf('.')) : "";
        boolean visible = s.cu().getPackageDeclaration().map(p -> p.getNameAsString().equals(pkg)).orElse(pkg.isEmpty())
                || s.cu().getImports().stream().anyMatch(i -> i.getNameAsString().equals(fqn) || (i.isAsterisk() && i.getNameAsString().equals(pkg)));
        Set<Integer> lines = new java.util.TreeSet<>();
        for (ClassOrInterfaceType t : s.cu().findAll(ClassOrInterfaceType.class)) {
            if (!t.getNameAsString().equals(simple)) continue;
            String resolved = tryResolve(t);
            if (fqn.equals(resolved) || (resolved == null && (visible || t.getNameWithScope().equals(fqn)))) lines.add(line(t));
        }
        if (visible) {
            for (NameExpr n : s.cu().findAll(NameExpr.class)) {
                if (n.getNameAsString().equals(simple)) lines.add(line(n)); // static access, e.g. Type.CONSTANT
            }
        }
        return new ArrayList<>(lines);
    }

    private static String tryResolve(ClassOrInterfaceType t) {
        try {
            return t.resolve().asReferenceType().getQualifiedName();
        } catch (Throwable e) {
            return null;
        }
    }

    private static String declaringTypeOf(MethodCallExpr call) {
        try {
            return call.resolve().declaringType().getQualifiedName();
        } catch (Throwable e) {
            return null;
        }
    }

    /** All supertypes declared in the project (or resolvable), transitively. */
    private Set<String> ancestors(String fqn) {
        Set<String> result = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(fqn));
        while (!todo.isEmpty()) {
            String current = todo.pop();
            declaration(current).ifPresent(decl -> {
                List<ClassOrInterfaceType> supers = new ArrayList<>(decl.getExtendedTypes());
                supers.addAll(decl.getImplementedTypes());
                for (ClassOrInterfaceType st : supers) {
                    String q = tryResolve(st);
                    if (q != null && result.add(q)) todo.push(q);
                }
            });
        }
        return result;
    }

    private List<String> implementationsOf(String fqn) {
        List<String> result = new ArrayList<>();
        for (String candidate : index.types.keySet()) {
            if (!candidate.equals(fqn) && ancestors(candidate).contains(fqn)) result.add(candidate);
        }
        return result;
    }

    private Optional<ClassOrInterfaceDeclaration> declaration(String fqn) {
        TypeDecl decl = index.types.get(fqn);
        if (decl == null) return Optional.empty();
        return index.javaSources.stream().filter(s -> s.file().equals(decl.file()))
                .flatMap(s -> s.cu().findAll(ClassOrInterfaceDeclaration.class).stream())
                .filter(c -> c.getFullyQualifiedName().map(fqn::equals).orElse(false)).findFirst();
    }

    private boolean isInstantiatedInJava(String fqn) {
        String simple = CodeIndex.simpleName(fqn);
        for (JavaSource s : index.javaSources) {
            if (s.test()) continue;
            for (ObjectCreationExpr n : s.cu().findAll(ObjectCreationExpr.class)) {
                if (n.getType().getNameAsString().equals(simple)) return true;
            }
        }
        return false;
    }

    private Optional<String> declaredAt(String fqn) {
        TypeDecl d = index.types.get(fqn);
        return d == null ? Optional.empty() : Optional.of(d.file() + ":" + d.line());
    }

    // ================================================================== Spring graph helpers

    /** Beans whose refs point at this bean (same context first, child contexts see the root context). */
    private List<Bean> dependents(Bean target) {
        return index.beans.stream().filter(b -> b != target && b.refs().stream().anyMatch(r -> pointsTo(r, b, target))).toList();
    }

    private boolean pointsTo(String ref, Bean from, Bean target) {
        if (ref.startsWith("rmi:")) return ref.substring(4).equals(target.id()) && target.context().equals("root");
        return ref.equals(target.id()) && (from.context().equals(target.context()) || target.context().equals("root"));
    }

    private Optional<Bean> lookup(String ref, String fromContext) {
        if (ref.startsWith("rmi:")) return index.beansById(ref.substring(4)).stream().filter(b -> b.context().equals("root")).findFirst();
        Optional<Bean> same = index.beansById(ref).stream().filter(b -> b.context().equals(fromContext)).findFirst();
        return same.isPresent() ? same : index.beansById(ref).stream().filter(b -> b.context().equals("root")).findFirst();
    }

    /** Shortest XML wiring path from each entry point to the bean, e.g. "JMS listener ... -> a -> b". */
    private List<String> entryPointPaths(Bean target) {
        List<String> paths = new ArrayList<>();
        for (EntryPoint e : index.entryPoints) {
            if (e.beanId() == null) continue;
            Optional<Bean> start = lookup(e.beanId(), e.context());
            if (start.isEmpty()) continue;
            List<Bean> path = shortestPath(start.get(), target);
            if (path != null) {
                paths.add(e.kind() + " " + e.description() + ": "
                        + path.stream().map(b -> b.id() + (b.refs().stream().anyMatch(r -> r.startsWith("rmi:")) ? " (RMI)" : "")).collect(Collectors.joining(" -> ")));
            }
        }
        return paths;
    }

    private List<Bean> shortestPath(Bean from, Bean to) {
        Map<Bean, Bean> parent = new LinkedHashMap<>();
        Deque<Bean> queue = new ArrayDeque<>(List.of(from));
        parent.put(from, null);
        while (!queue.isEmpty()) {
            Bean current = queue.poll();
            if (current == to) {
                List<Bean> path = new ArrayList<>();
                for (Bean b = to; b != null; b = parent.get(b)) path.add(0, b);
                return path;
            }
            for (String ref : current.refs()) {
                lookup(ref, current.context()).ifPresent(next -> {
                    if (!parent.containsKey(next)) {
                        parent.put(next, current);
                        queue.add(next);
                    }
                });
            }
        }
        return null;
    }

    private String describeRef(String ref, String context) {
        Optional<Bean> b = lookup(ref, context);
        String id = ref.startsWith("rmi:") ? ref.substring(4) + " (over RMI, in the root context)" : ref;
        return "'" + id + "'" + b.map(x -> classSuffix(x.className()) + "  [" + x.file() + ":" + x.line() + "]").orElse("  (not defined in this project)");
    }

    private String beanClassSuffix(String id, String context) {
        return lookup(id, context).map(b -> classSuffix(b.className())).orElse("");
    }

    private static String classSuffix(String className) {
        return className == null ? "" : " (" + className + ")";
    }

    // ================================================================== text helpers

    private void appendGrouped(StringBuilder out, String title, Map<String, List<String>> byFile) {
        int count = byFile.values().stream().mapToInt(List::size).sum();
        out.append('\n').append(title).append(" (").append(count).append(" lines in ").append(byFile.size()).append(" files):\n");
        byFile.forEach((file, refs) -> refs.forEach(r -> out.append("  ").append(r).append('\n')));
    }

    private void appendNonJava(StringBuilder out, String fqn) {
        List<String> mentions = nonJavaMentions(fqn);
        out.append("\nConfiguration files naming it (").append(mentions.size()).append("):\n");
        mentions.forEach(m -> out.append("  ").append(m).append('\n'));
    }

    /** XML, JSP and properties files under src/main that mention the fully qualified name. */
    private List<String> nonJavaMentions(String fqn) {
        List<String> result = new ArrayList<>();
        try (var files = Files.walk(index.root.resolve("src/main"))) {
            for (var p : files.filter(f -> f.toString().matches(".*\\.(xml|jsp|properties|tld)$")).sorted().toList()) {
                String rel = index.rel(p);
                List<String> lines = lines(rel);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains(fqn)) result.add(rel + ":" + (i + 1) + "  " + lines.get(i).trim());
                }
            }
        } catch (IOException e) {
            result.add("(could not scan configuration files: " + e.getMessage() + ")");
        }
        return result;
    }

    private String lineRef(String file, int line) {
        List<String> lines = lines(file);
        String text = line > 0 && line <= lines.size() ? lines.get(line - 1).trim() : "";
        if (text.length() > 140) text = text.substring(0, 137) + "...";
        return file + ":" + line + "  " + text;
    }

    private List<String> lines(String relFile) {
        return lineCache.computeIfAbsent(relFile, f -> {
            try {
                return Files.readAllLines(index.root.resolve(f));
            } catch (IOException e) {
                return List.of();
            }
        });
    }

    private static int line(Node n) {
        return n.getBegin().map(b -> b.line).orElse(0);
    }
}
