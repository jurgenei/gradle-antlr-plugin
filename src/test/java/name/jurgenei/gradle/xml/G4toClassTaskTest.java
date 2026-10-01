package name.jurgenei.gradle.xml;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class G4toClassTaskTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void convertsExplicitInputToClassesAndModelXir() throws Exception {
        final Project project = ProjectBuilder.builder().build();
        final TestG4toClassTask task = project.getTasks().register("g4UnitTask", TestG4toClassTask.class).get();

        final File sourceDir = temporaryFolder.newFolder("g4-source");
        final File grammarFile = new File(sourceDir, "mini.g4");
        Files.writeString(grammarFile.toPath(), sampleGrammar(), StandardCharsets.UTF_8);

        final File outputDir = temporaryFolder.newFolder("g4-out");
        final File classesOut = new File(outputDir, "mini.classes.xir");
        final File modelOut = new File(outputDir, "mini.model.xir");

        task.input(grammarFile);
        task.output(classesOut);
        task.modelOutput(modelOut);
        task.convertAll();

        Assert.assertTrue(classesOut.isFile());
        Assert.assertTrue(modelOut.isFile());
        Assert.assertTrue(Files.readString(classesOut.toPath(), StandardCharsets.UTF_8).contains("(class Assignment)"));
        Assert.assertTrue(Files.readString(modelOut.toPath(), StandardCharsets.UTF_8).contains("(rule assignment"));
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

    public abstract static class TestG4toClassTask extends G4toClassTask {
    }
}
