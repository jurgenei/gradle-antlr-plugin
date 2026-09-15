package name.jurgenei.gradle.antlr;

import name.jurgenei.gradle.xml.G4toClassTask;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.Assert;
import org.junit.Test;

public class CompatG4PluginTest {

    @Test
    public void registersPreconfiguredTaskType() {
        final Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply("java");

        new CompatG4Plugin().apply(project);

        final Object task = project.getTasks().getByName("g4XmlAst");
        Assert.assertNotNull(task);
        XmlAstG4GradleTask.class.cast(task);
    }

    @Test
    public void preconfiguresG4Defaults() {
        final Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply("java");

        new CompatG4Plugin().apply(project);

        final XmlAstG4GradleTask task = XmlAstG4GradleTask.class.cast(project.getTasks().getByName("g4XmlAst"));
        Assert.assertEquals("antlr4", task.getGrammar().get());
        Assert.assertEquals("name.jurgenei.parsers.ANTLRv4Parser", task.getParserClassName().get());
        Assert.assertEquals("name.jurgenei.parsers.ANTLRv4Lexer", task.getLexerClassName().get());
        Assert.assertEquals("grammarSpec", task.getStartRule().get());
        Assert.assertTrue(task.getIncludes().get().contains("**/*.g4"));
        Assert.assertEquals(".xml", task.getTargetExtension().get());
        Assert.assertEquals("compact", task.getSexprFormat().get());
    }

    @Test
    public void registersG4ToClassTaskWithDefaults() {
        final Project project = ProjectBuilder.builder().build();

        new CompatG4Plugin().apply(project);

        final G4toClassTask task = G4toClassTask.class.cast(project.getTasks().getByName("g4ToClass"));
        Assert.assertEquals(".classes.sexp", task.getClassOutputExtension().get());
        Assert.assertEquals(".model.sexp", task.getModelOutputExtension().get());
        Assert.assertEquals("name.jurgenei.parsers.ANTLRv4Parser", task.getParserClassName().get());
        Assert.assertEquals("name.jurgenei.parsers.ANTLRv4Lexer", task.getLexerClassName().get());
        Assert.assertEquals("grammarSpec", task.getStartRule().get());
    }
}

