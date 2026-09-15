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

public class G4toClassTaskFunctionalTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void convertsFileTreeUsingFileset() throws Exception {
        final File projectDir = temporaryFolder.newFolder("functional-g4-to-class-fileset");
        writeSettings(projectDir);
        writeBuildFile(projectDir, """
                plugins {
                    id 'java'
                    id 'name.jurgenei.gradle.antlr.g4'
                }

                tasks.named('g4ToClass', name.jurgenei.gradle.xml.G4toClassTask) {
                    fileset('src/main/antlr') {
                        include '**/*.g4'
                    }
                    outputDir.set(layout.buildDirectory.dir('class-model'))
                }
                """);

        final File srcDir = new File(projectDir, "src/main/antlr");
        Assert.assertTrue(srcDir.mkdirs());
        Files.writeString(new File(srcDir, "mini.g4").toPath(), sampleGrammar(), StandardCharsets.UTF_8);

        final BuildResult result = run(projectDir, "g4ToClass");

        final BuildTask task = result.task(":g4ToClass");
        Assert.assertNotNull(task);
        Assert.assertEquals(TaskOutcome.SUCCESS, task.getOutcome());

        final File classesFile = new File(projectDir, "build/class-model/mini.classes.sexp");
        final File modelFile = new File(projectDir, "build/class-model/mini.model.sexp");
        Assert.assertTrue(classesFile.isFile());
        Assert.assertTrue(modelFile.isFile());

        final String classesText = Files.readString(classesFile.toPath(), StandardCharsets.UTF_8);
        Assert.assertTrue(classesText.contains("(class Assignment)"));
        Assert.assertTrue(classesText.contains("(rel Assignment target Identifier 1)"));
        Assert.assertTrue(classesText.contains("(isa FunctionCall Expression)"));
        Assert.assertFalse(classesText.contains("(class Terminator)"));
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
                "rootProject.name = 'g4-to-class-functional-test'\n",
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
                WS : [ \t\r\n]+ -> skip ;
                """;
    }
}

