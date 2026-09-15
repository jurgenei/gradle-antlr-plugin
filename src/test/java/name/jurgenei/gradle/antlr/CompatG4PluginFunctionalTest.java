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

public class CompatG4PluginFunctionalTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void registersG4TasksViaMainPluginId() throws Exception {
        final File projectDir = temporaryFolder.newFolder("functional-registers-g4-compat");
        writeSettings(projectDir);
        writeBuildFile(projectDir, """
                plugins {
                    id 'java'
                    id 'name.jurgenei.gradle.antlr'
                }
                """);

        final BuildResult result = run(projectDir, "tasks", "--all");

        Assert.assertTrue("Expected antlrG4XmlAst task", result.getOutput().contains("antlrG4XmlAst"));
        Assert.assertTrue("Expected antlrG4ToClass task", result.getOutput().contains("antlrG4ToClass"));
    }

    @Test
    public void preconfiguredDefaultsAreAppliedForMergedTask() throws Exception {
        final File projectDir = temporaryFolder.newFolder("functional-g4-compat-defaults");
        writeSettings(projectDir);
        writeBuildFile(projectDir, """
                plugins {
                    id 'java'
                    id 'name.jurgenei.gradle.antlr'
                }

                tasks.register('printG4Defaults') {
                    doLast {
                        def t = tasks.named('antlrG4XmlAst').get()
                        println "grammar=${t.grammar.get()}"
                        println "parserClassName=${t.parserClassName.get()}"
                        println "lexerClassName=${t.lexerClassName.get()}"
                        println "startRule=${t.startRule.get()}"
                    }
                }
                """);

        final BuildResult result = run(projectDir, "printG4Defaults");
        final String output = result.getOutput();

        Assert.assertTrue(output.contains("grammar=antlr4"));
        Assert.assertTrue(output.contains("parserClassName=name.jurgenei.parsers.ANTLRv4Parser"));
        Assert.assertTrue(output.contains("lexerClassName=name.jurgenei.parsers.ANTLRv4Lexer"));
        Assert.assertTrue(output.contains("startRule=grammarSpec"));
    }

    private static BuildResult run(final File projectDir, final String... args) {
        return GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments(args)
                .withPluginClasspath()
                .build();
    }

    private static void writeSettings(final File projectDir) throws Exception {
        Files.writeString(
                projectDir.toPath().resolve("settings.gradle"),
                "rootProject.name = 'g4-compat-functional-test'\n",
                StandardCharsets.UTF_8);
    }

    private static void writeBuildFile(final File projectDir, final String content) throws Exception {
        Files.writeString(
                projectDir.toPath().resolve("build.gradle"),
                content,
                StandardCharsets.UTF_8);
    }
}

