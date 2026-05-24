package name.jurgenei.gradle.antlr;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
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
                    id 'xmlast'
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
                    id 'xmlast'
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
                    id 'xmlast'
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
                    id 'xmlast'
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
                    id 'xmlast'
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
                    id 'xmlast'
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

