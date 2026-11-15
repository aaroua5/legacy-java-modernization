plugins {
    application
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

dependencies {
    implementation("io.modelcontextprotocol.sdk:mcp:2.1.0")
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.28.2")

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

application {
    mainClass = "io.github.aaroua5.legacyexplorer.LegacyExplorerServer"
}

tasks.test {
    useJUnitPlatform()
    // Tests run against the real legacy app next to this project.
    systemProperty("legacy.root", file("../app").absolutePath)
}

// Fat jar so Claude Code can start the server with a plain `java -jar`.
tasks.register<Jar>("fatJar") {
    archiveClassifier = "all"
    manifest { attributes["Main-Class"] = application.mainClass }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

tasks.test {
    testLogging {
        events("passed", "failed")
        showStandardStreams = providers.gradleProperty("showOutput").isPresent
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Benchmark (with vs without the MCP server): separate source set, not shipped in the server jar.
val bench: SourceSet by sourceSets.creating
configurations[bench.implementationConfigurationName].extendsFrom(configurations.implementation.get())
dependencies {
    "benchImplementation"("tools.jackson.core:jackson-databind:3.1.4")
}
tasks.register<JavaExec>("bench") {
    description = "Runs the with/without MCP benchmark. Pass options with -Pargs=\"--trials 3 --model ...\""
    classpath = bench.runtimeClasspath
    mainClass = "io.github.aaroua5.legacyexplorer.bench.McpBenchmark"
    workingDir = projectDir
    args = providers.gradleProperty("args").map { it.split(" ").filter(String::isNotBlank) }.getOrElse(emptyList())
}
