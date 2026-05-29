package name.jurgenei.gradle.antlr;

import com.sun.net.httpserver.HttpServer;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class XmlAstPluginFunctionalTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void registersXmlAstTask() throws Exception {
        final File projectDir = temporaryFolder.newFolder("functional-registers-xmlast");
        writeSettings(projectDir);
        writeBuildFile(projectDir, """
                plugins {
                    id 'java'
                    id 'name.jurgenei.gradle.antlr'
                }
                """);

        final BuildResult result = run(projectDir, "tasks", "--all");

        Assert.assertTrue("Expected xmlast task to be listed", result.getOutput().contains("xmlast"));
    }

    @Test
    public void xmlAstTaskDependsOnClasses() throws Exception {
        final File projectDir = temporaryFolder.newFolder("functional-xmlast-depends-classes");
        writeSettings(projectDir);
        writeBuildFile(projectDir, """
                plugins {
                    id 'java'
                    id 'name.jurgenei.gradle.antlr'
                }
                """);

        final BuildResult result = run(projectDir, "xmlast", "--dry-run");

        Assert.assertTrue("Expected classes task in execution plan", result.getOutput().contains(":classes SKIPPED"));
        Assert.assertTrue("Expected xmlast task in execution plan", result.getOutput().contains(":xmlast SKIPPED"));
    }

    @Test
    public void customTaskTypesDependOnClasses() throws Exception {
        final File projectDir = temporaryFolder.newFolder("functional-custom-types");
        writeSettings(projectDir);
        writeBuildFile(projectDir, """
                import name.jurgenei.gradle.antlr.XmlAstTask
                import name.jurgenei.gradle.antlr.XmlAstGradleTask

                plugins {
                    id 'java'
                    id 'name.jurgenei.gradle.antlr'
                }

                tasks.register('legacyXmlast', XmlAstTask) {
                    sourceTrees.from(layout.projectDirectory.dir('src/main/sql'))
                }

                tasks.register('modernXmlast', XmlAstGradleTask) {
                    sourceDirectory.set(layout.projectDirectory.dir('src/main/sql'))
                }
                """);

        final BuildResult legacy = run(projectDir, "legacyXmlast", "--dry-run");
        Assert.assertTrue("Expected classes before legacyXmlast", legacy.getOutput().contains(":classes SKIPPED"));
        Assert.assertTrue("Expected legacyXmlast in plan", legacy.getOutput().contains(":legacyXmlast SKIPPED"));

        final BuildResult modern = run(projectDir, "modernXmlast", "--dry-run");
        Assert.assertTrue("Expected classes before modernXmlast", modern.getOutput().contains(":classes SKIPPED"));
        Assert.assertTrue("Expected modernXmlast in plan", modern.getOutput().contains(":modernXmlast SKIPPED"));
    }

    @Test
    public void convertsSqlToXmlUsingDynamicallyLoadedParserAndLexer() throws Exception {
        final File projectDir = temporaryFolder.newFolder("functional-dynamic-parser-conversion");
        writeSettings(projectDir);
        writeBuildFile(projectDir, """
                plugins {
                    id 'java'
                    id 'antlr'
                    id 'name.jurgenei.gradle.antlr'
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    antlr 'org.antlr:antlr4:4.13.1'
                    implementation 'org.antlr:antlr4-runtime:4.13.1'
                }

                generateGrammarSource {
                    arguments += ['-package', 'e2e']
                }

                tasks.named('xmlast', name.jurgenei.gradle.antlr.XmlAstGradleTask) {
                    sourceDirectory.set(layout.projectDirectory.dir('src/main/sql'))
                    destinationDirectory.set(layout.projectDirectory.dir('build/xmlast'))
                    parserClassName.set('e2e.MiniParser')
                    lexerClassName.set('e2e.MiniLexer')
                    startRule.set('script')
                    targetExtension.set('.xml')
                }
                """);

        writeFile(projectDir, "src/main/antlr/MiniLexer.g4", """
                lexer grammar MiniLexer;

                SELECT: 'SELECT';
                FROM: 'FROM';
                STAR: '*';
                SEMI: ';';
                IDENTIFIER: [a-zA-Z_] [a-zA-Z_0-9]*;
                WS: [ \\t\\r\\n]+ -> skip;
                """);

        writeFile(projectDir, "src/main/antlr/MiniParser.g4", """
                parser grammar MiniParser;
                options { tokenVocab=MiniLexer; }

                script: SELECT STAR FROM IDENTIFIER SEMI EOF;
                """);

        writeFile(projectDir, "src/main/sql/sample.sql", "SELECT * FROM employees;");

        final BuildResult result = run(projectDir, "xmlast", "--stacktrace");
        Assert.assertTrue("Expected xmlast task success", result.getOutput().contains("BUILD SUCCESSFUL"));

        final Path output = projectDir.toPath().resolve("build/xmlast/sample.xml");
        Assert.assertTrue("Expected generated XML file", Files.exists(output));
        final String xml = Files.readString(output, StandardCharsets.UTF_8);
        Assert.assertTrue("Expected ast root", xml.contains("<ast"));
        Assert.assertTrue("Expected script rule", xml.contains("name=\"script\""));
        Assert.assertTrue("Expected SELECT token", xml.contains("SELECT"));
    }

    @Test
    public void failsXmlAstConversionForInvalidSqlWithParseError() throws Exception {
        final File projectDir = temporaryFolder.newFolder("functional-dynamic-parser-invalid-sql");
        writeSettings(projectDir);
        writeBuildFile(projectDir, """
                plugins {
                    id 'java'
                    id 'antlr'
                    id 'name.jurgenei.gradle.antlr'
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    antlr 'org.antlr:antlr4:4.13.1'
                    implementation 'org.antlr:antlr4-runtime:4.13.1'
                }

                generateGrammarSource {
                    arguments += ['-package', 'e2e']
                }

                tasks.named('xmlast', name.jurgenei.gradle.antlr.XmlAstGradleTask) {
                    sourceDirectory.set(layout.projectDirectory.dir('src/main/sql'))
                    destinationDirectory.set(layout.projectDirectory.dir('build/xmlast'))
                    parserClassName.set('e2e.MiniParser')
                    lexerClassName.set('e2e.MiniLexer')
                    startRule.set('script')
                    targetExtension.set('.xml')
                }
                """);

        writeFile(projectDir, "src/main/antlr/MiniLexer.g4", """
                lexer grammar MiniLexer;

                SELECT: 'SELECT';
                FROM: 'FROM';
                STAR: '*';
                SEMI: ';';
                IDENTIFIER: [a-zA-Z_] [a-zA-Z_0-9]*;
                WS: [ \\t\\r\\n]+ -> skip;
                """);

        writeFile(projectDir, "src/main/antlr/MiniParser.g4", """
                parser grammar MiniParser;
                options { tokenVocab=MiniLexer; }

                script: SELECT STAR FROM IDENTIFIER SEMI EOF;
                """);

        writeFile(projectDir, "src/main/sql/invalid.sql", "SELECT FROM employees;");

        final BuildResult result = runAndFail(projectDir, "xmlast", "--stacktrace");
        Assert.assertTrue("Expected xmlast task to fail", result.getOutput().contains("Execution failed for task ':xmlast'"));
        Assert.assertTrue(
                "Expected parse failure diagnostics in output",
                result.getOutput().contains("Parse failed for") || result.getOutput().contains("Dynamic ANTLR conversion failed"));
    }

    @Test
    public void convertsUsingCatalogConfiguredStartRule() throws Exception {
        final File projectDir = temporaryFolder.newFolder("functional-catalog-start-rule");
        writeSettings(projectDir);
        writeBuildFile(projectDir, """
                plugins {
                    id 'java'
                    id 'antlr'
                    id 'name.jurgenei.gradle.antlr'
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    antlr 'org.antlr:antlr4:4.13.1'
                    implementation 'org.antlr:antlr4-runtime:4.13.1'
                }

                generateGrammarSource {
                    arguments += ['-package', 'e2e']
                }

                tasks.named('xmlast', name.jurgenei.gradle.antlr.XmlAstGradleTask) {
                    sourceDirectory.set(layout.projectDirectory.dir('src/main/sql'))
                    destinationDirectory.set(layout.projectDirectory.dir('build/xmlast'))
                    parserClassName.set('e2e.MiniParser')
                    lexerClassName.set('e2e.MiniLexer')
                    catalogFile.set(layout.projectDirectory.file('catalog.xml'))
                    catalogGrammar.set('mini')
                    targetExtension.set('.xml')
                }
                """);

        writeFile(projectDir, "catalog.xml", """
                <catalog>
                  <grammar name="mini" runtimeGrammar="oracle" parser="e2e.MiniParser" lexer="e2e.MiniLexer" start-rule="root"/>
                </catalog>
                """);

        writeFile(projectDir, "src/main/antlr/MiniLexer.g4", """
                lexer grammar MiniLexer;

                SELECT: 'SELECT';
                FROM: 'FROM';
                STAR: '*';
                SEMI: ';';
                IDENTIFIER: [a-zA-Z_] [a-zA-Z_0-9]*;
                WS: [ \\t\\r\\n]+ -> skip;
                """);

        writeFile(projectDir, "src/main/antlr/MiniParser.g4", """
                parser grammar MiniParser;
                options { tokenVocab=MiniLexer; }

                root: SELECT STAR FROM IDENTIFIER SEMI EOF;
                """);

        writeFile(projectDir, "src/main/sql/sample.sql", "SELECT * FROM employees;");

        final BuildResult result = run(projectDir, "xmlast", "--stacktrace");
        Assert.assertTrue("Expected xmlast task success", result.getOutput().contains("BUILD SUCCESSFUL"));

        final Path output = projectDir.toPath().resolve("build/xmlast/sample.xml");
        Assert.assertTrue("Expected generated XML file", Files.exists(output));
        final String xml = Files.readString(output, StandardCharsets.UTF_8);
        Assert.assertTrue("Expected ast root", xml.contains("<ast"));
        Assert.assertTrue("Expected catalog start rule", xml.contains("name=\"root\""));
    }

    @Test
    public void convertsUsingCatalogRemoteGrammarUrlsWithoutProtocolAndSuperClasses() throws Exception {
        final File projectDir = temporaryFolder.newFolder("functional-catalog-remote-superclass");
        writeSettings(projectDir);
        writeBuildFile(projectDir, """
                plugins {
                    id 'java'
                    id 'name.jurgenei.gradle.antlr'
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    implementation 'org.antlr:antlr4:4.13.1'
                    implementation 'org.antlr:antlr4-runtime:4.13.1'
                }

                tasks.named('xmlast', name.jurgenei.gradle.antlr.XmlAstGradleTask) {
                    sourceDirectory.set(layout.projectDirectory.dir('src/main/sql'))
                    destinationDirectory.set(layout.projectDirectory.dir('build/xmlast'))
                    catalogFile.set(layout.projectDirectory.file('catalog.xml'))
                    catalogGrammar.set('mini')
                    targetExtension.set('.xml')
                }
                """);

        writeFile(projectDir, "src/main/java/e2e/MyLexerBase.java", """
                package e2e;

                import org.antlr.v4.runtime.CharStream;
                import org.antlr.v4.runtime.Lexer;

                public abstract class MyLexerBase extends Lexer {
                    protected MyLexerBase(final CharStream input) {
                        super(input);
                    }
                }
                """);

        writeFile(projectDir, "src/main/java/e2e/MyParserBase.java", """
                package e2e;

                import org.antlr.v4.runtime.Parser;
                import org.antlr.v4.runtime.TokenStream;

                public abstract class MyParserBase extends Parser {
                    protected MyParserBase(final TokenStream input) {
                        super(input);
                    }
                }
                """);

        final File grammarDir = temporaryFolder.newFolder("remote-mini-grammars");
        Files.writeString(grammarDir.toPath().resolve("MiniLexer.g4"), """
                lexer grammar MiniLexer;

                options { superClass = e2e.MyLexerBase; }

                SELECT: 'SELECT';
                FROM: 'FROM';
                STAR: '*';
                SEMI: ';';
                IDENTIFIER: [a-zA-Z_] [a-zA-Z_0-9]*;
                WS: [ \\t\\r\\n]+ -> skip;
                """, StandardCharsets.UTF_8);

        Files.writeString(grammarDir.toPath().resolve("MiniParser.g4"), """
                parser grammar MiniParser;
                options {
                    tokenVocab=MiniLexer;
                    superClass=e2e.MyParserBase;
                }

                root: SELECT STAR FROM IDENTIFIER SEMI EOF;
                """, StandardCharsets.UTF_8);

        final HttpServer server = startStaticServer(grammarDir.toPath());
        try {
            final int port = server.getAddress().getPort();
            writeFile(projectDir, "catalog.xml", """
                    <catalog>
                      <grammar name="mini" runtimeGrammar="oracle" parser="localhost:%d/MiniParser.g4" lexer="localhost:%d/MiniLexer.g4" start-rule="root"/>
                    </catalog>
                    """.formatted(port, port));

            writeFile(projectDir, "src/main/sql/sample.sql", "SELECT * FROM employees;");

            final BuildResult result = run(projectDir, "xmlast", "--stacktrace");
            Assert.assertTrue("Expected xmlast task success", result.getOutput().contains("BUILD SUCCESSFUL"));

            final Path output = projectDir.toPath().resolve("build/xmlast/sample.xml");
            Assert.assertTrue("Expected generated XML file", Files.exists(output));
            final String xml = Files.readString(output, StandardCharsets.UTF_8);
            Assert.assertTrue("Expected ast root", xml.contains("<ast"));
            Assert.assertTrue("Expected catalog start rule", xml.contains("name=\"root\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void continuesOnMalformedInputWhenFailOnErrorDisabled() throws Exception {
        final File projectDir = temporaryFolder.newFolder("functional-continue-on-malformed");
        writeSettings(projectDir);
        writeBuildFile(projectDir, """
                plugins {
                    id 'java'
                    id 'antlr'
                    id 'name.jurgenei.gradle.antlr'
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    antlr 'org.antlr:antlr4:4.13.1'
                    implementation 'org.antlr:antlr4-runtime:4.13.1'
                }

                generateGrammarSource {
                    arguments += ['-package', 'e2e']
                }

                tasks.named('xmlast', name.jurgenei.gradle.antlr.XmlAstGradleTask) {
                    sourceDirectory.set(layout.projectDirectory.dir('src/main/sql'))
                    destinationDirectory.set(layout.projectDirectory.dir('build/xmlast'))
                    parserClassName.set('e2e.MiniParser')
                    lexerClassName.set('e2e.MiniLexer')
                    startRule.set('script')
                    targetExtension.set('.xml')
                    continueOnError.set(true)
                    failOnError.set(false)
                    failOnTransformationError.set(false)
                    suppressStackTrace.set(true)
                }
                """);

        writeFile(projectDir, "src/main/antlr/MiniLexer.g4", """
                lexer grammar MiniLexer;

                SELECT: 'SELECT';
                FROM: 'FROM';
                STAR: '*';
                SEMI: ';';
                IDENTIFIER: [a-zA-Z_] [a-zA-Z_0-9]*;
                WS: [ \\t\\r\\n]+ -> skip;
                """);

        writeFile(projectDir, "src/main/antlr/MiniParser.g4", """
                parser grammar MiniParser;
                options { tokenVocab=MiniLexer; }

                script: SELECT STAR FROM IDENTIFIER SEMI EOF;
                """);

        writeFile(projectDir, "src/main/sql/valid.sql", "SELECT * FROM employees;");
        final Path malformed = projectDir.toPath().resolve("src/main/sql/malformed.sql");
        Files.createDirectories(malformed.getParent());
        Files.write(malformed, new byte[]{(byte) 0xC3, (byte) 0x28});

        final BuildResult result = run(projectDir, "xmlast", "--rerun-tasks");

        Assert.assertTrue("Expected build to continue despite malformed input", result.getOutput().contains("BUILD SUCCESSFUL"));
        Assert.assertTrue("Expected success line for valid input", result.getOutput().contains("[SUCCESS]"));
        Assert.assertTrue("Expected summary to report one file with error", result.getOutput().contains("Files with errors          : 1"));
        Assert.assertTrue("Expected valid file output to be generated", Files.exists(projectDir.toPath().resolve("build/xmlast/valid.xml")));
    }

    @Test
    public void logsSkipForUpToDateFilesOnRerunTasks() throws Exception {
        final File projectDir = temporaryFolder.newFolder("functional-skip-notice");
        writeSettings(projectDir);
        writeBuildFile(projectDir, """
                plugins {
                    id 'java'
                    id 'antlr'
                    id 'name.jurgenei.gradle.antlr'
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    antlr 'org.antlr:antlr4:4.13.1'
                    implementation 'org.antlr:antlr4-runtime:4.13.1'
                }

                generateGrammarSource {
                    arguments += ['-package', 'e2e']
                }

                tasks.named('xmlast', name.jurgenei.gradle.antlr.XmlAstGradleTask) {
                    sourceDirectory.set(layout.projectDirectory.dir('src/main/sql'))
                    destinationDirectory.set(layout.projectDirectory.dir('build/xmlast'))
                    parserClassName.set('e2e.MiniParser')
                    lexerClassName.set('e2e.MiniLexer')
                    startRule.set('script')
                    targetExtension.set('.xml')
                    force.set(false)
                }
                """);

        writeFile(projectDir, "src/main/antlr/MiniLexer.g4", """
                lexer grammar MiniLexer;

                SELECT: 'SELECT';
                FROM: 'FROM';
                STAR: '*';
                SEMI: ';';
                IDENTIFIER: [a-zA-Z_] [a-zA-Z_0-9]*;
                WS: [ \\t\\r\\n]+ -> skip;
                """);

        writeFile(projectDir, "src/main/antlr/MiniParser.g4", """
                parser grammar MiniParser;
                options { tokenVocab=MiniLexer; }

                script: SELECT STAR FROM IDENTIFIER SEMI EOF;
                """);

        writeFile(projectDir, "src/main/sql/sample.sql", "SELECT * FROM employees;");

        run(projectDir, "xmlast", "--rerun-tasks");
        final BuildResult secondRun = run(projectDir, "xmlast", "--rerun-tasks");

        Assert.assertTrue("Expected skip notice for up-to-date file", secondRun.getOutput().contains("sample.sql + SKIP"));
    }

    private static HttpServer startStaticServer(final Path rootDirectory) throws IOException {
        final HttpServer server = HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            final String rawPath = exchange.getRequestURI().getPath();
            final String relative = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
            final Path target = rootDirectory.resolve(relative).normalize();
            if (!target.startsWith(rootDirectory) || !Files.isRegularFile(target)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }

            final byte[] bytes = Files.readAllBytes(target);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static BuildResult run(final File projectDir, final String... args) {
        return GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments(args)
                .withPluginClasspath()
                .build();
    }

    private static BuildResult runAndFail(final File projectDir, final String... args) {
        return GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments(args)
                .withPluginClasspath()
                .buildAndFail();
    }

    private static void writeSettings(final File projectDir) throws Exception {
        Files.writeString(
                projectDir.toPath().resolve("settings.gradle"),
                "rootProject.name = 'xmlast-functional-test'\n",
                StandardCharsets.UTF_8);
    }

    private static void writeBuildFile(final File projectDir, final String content) throws Exception {
        Files.writeString(
                projectDir.toPath().resolve("build.gradle"),
                content,
                StandardCharsets.UTF_8);
    }

    private static void writeFile(final File projectDir, final String relativePath, final String content) throws Exception {
        final Path target = projectDir.toPath().resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}

