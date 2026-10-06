package name.jurgenei.gradle.xml;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class G4toSchemaTaskFunctionalTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void convertsFileTreeUsingFileset() throws Exception {
        final File projectDir = temporaryFolder.newFolder("functional-g4-to-schema-fileset");
        writeSettings(projectDir);
        writeBuildFile(projectDir, """
                plugins {
                    id 'java'
                    id 'name.jurgenei.gradle.antlr'
                }

                tasks.named('antlrG4ToSchema', name.jurgenei.gradle.xml.G4toSchemaTask) {
                    fileset('src/main/antlr') {
                        include '**/*.g4'
                    }
                    outputDir.set(layout.buildDirectory.dir('class-model'))
                }
                """);

        final File srcDir = new File(projectDir, "src/main/antlr");
        Assert.assertTrue(srcDir.mkdirs());
        Files.writeString(new File(srcDir, "mini.g4").toPath(), sampleGrammar(), StandardCharsets.UTF_8);

        final BuildResult result = run(projectDir, "antlrG4ToSchema");

        final BuildTask task = result.task(":antlrG4ToSchema");
        Assert.assertNotNull(task);
        Assert.assertEquals(TaskOutcome.SUCCESS, task.getOutcome());

        final File schemaFile = new File(projectDir, "build/class-model/mini.schema.xir");
        final File modelFile = new File(projectDir, "build/class-model/mini.model.xir");
        Assert.assertTrue(schemaFile.isFile());
        Assert.assertTrue(modelFile.isFile());

        final String schemaText = Files.readString(schemaFile.toPath(), StandardCharsets.UTF_8);
        Assert.assertTrue(schemaText.contains("(class Assignment)"));
        Assert.assertTrue(schemaText.contains("(rel Assignment target Identifier 1)"));
        Assert.assertTrue(schemaText.contains("(isa FunctionCall Expression)"));
        Assert.assertFalse(schemaText.contains("(class Terminator)"));
    }

    @Test
    public void convertsSingleInputAndInfersModelOutputExtension() throws Exception {
        final File projectDir = temporaryFolder.newFolder("functional-g4-to-schema-explicit");
        writeSettings(projectDir);
        writeBuildFile(projectDir, """
                plugins {
                    id 'java'
                    id 'name.jurgenei.gradle.antlr'
                }

                tasks.named('antlrG4ToSchema', name.jurgenei.gradle.xml.G4toSchemaTask) {
                    input('src/main/antlr/mini.g4')
                    output('build/class-model/mini.schema.xir')
                }
                """);

        final File srcDir = new File(projectDir, "src/main/antlr");
        Assert.assertTrue(srcDir.mkdirs());
        Files.writeString(new File(srcDir, "mini.g4").toPath(), sampleGrammar(), StandardCharsets.UTF_8);

        final BuildResult result = run(projectDir, "antlrG4ToSchema");

        final BuildTask task = result.task(":antlrG4ToSchema");
        Assert.assertNotNull(task);
        Assert.assertEquals(TaskOutcome.SUCCESS, task.getOutcome());

        final Path schemaPath = projectDir.toPath().resolve("build/class-model/mini.schema.xir");
        final Path modelPath = projectDir.toPath().resolve("build/class-model/mini.schema.model.xir");
        Assert.assertTrue("Expected schema output", Files.exists(schemaPath));
        Assert.assertTrue("Expected inferred model output", Files.exists(modelPath));

        final String schemaXir = Files.readString(schemaPath, StandardCharsets.UTF_8);
        final String modelXir = Files.readString(modelPath, StandardCharsets.UTF_8);
        Assert.assertTrue(schemaXir.contains("(class Assignment)"));
        Assert.assertTrue(modelXir.contains("(rule assignment"));
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
                "rootProject.name = 'g4-to-schema-functional-test'\n",
                StandardCharsets.UTF_8);
    }

    private static void writeBuildFile(final File projectDir, final String content) throws Exception {
        Files.writeString(
                projectDir.toPath().resolve("build.gradle"),
                content,
                StandardCharsets.UTF_8);
    }

    private static String sampleGrammar() {
        return """
                grammar Mini;

                assignment
                  : target=identifier '=' value=expression ';'
                  ;

                expression
                  : functionCall
                  | binaryExpression
                  | literalExpr
                  ;

                functionCall : identifier ;
                binaryExpression : identifier ;
                literalExpr : INT ;
                identifier : nameToken ;
                nameToken : ID ;
                terminator : ';' ;

                ID : [a-zA-Z_][a-zA-Z0-9_]* ;
                INT : [0-9]+ ;
                WS : [ \t\r
                ]+ -> skip ;
                """;
    }
}
