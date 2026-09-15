package name.jurgenei.gradle.antlr;

import name.jurgenei.gradle.xml.G4toClassTask;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.Assert;
import org.junit.Test;

import javax.inject.Inject;

public class XmlAstPluginTest {

    @Test
    public void registersXmlAstTaskWithDefaultsAndJavaClasspathWiring() {
        final Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply("java");

        new XmlAstPlugin().apply(project);

        final XmlAstGradleTask task = XmlAstGradleTask.class.cast(project.getTasks().getByName("xmlast"));
        Assert.assertEquals("xmlast", task.getGroup());
        Assert.assertEquals("Convert SQL file trees to XML AST output.", task.getDescription());

        final SourceSet mainSourceSet = project.getExtensions()
                .getByType(JavaPluginExtension.class)
                .getSourceSets()
                .getByName(SourceSet.MAIN_SOURCE_SET_NAME);

        Assert.assertTrue(task.getRuntimeClasspath().getFiles().containsAll(mainSourceSet.getRuntimeClasspath().getFiles()));
        Assert.assertTrue(task.getTaskDependencies().getDependencies(task).contains(project.getTasks().named("classes").get()));

        final XmlAstG4GradleTask g4Task = XmlAstG4GradleTask.class.cast(project.getTasks().getByName("antlrG4XmlAst"));
        Assert.assertEquals("antlr4", g4Task.getGrammar().get());
        Assert.assertEquals("name.jurgenei.parsers.ANTLRv4Parser", g4Task.getParserClassName().get());
        Assert.assertEquals("name.jurgenei.parsers.ANTLRv4Lexer", g4Task.getLexerClassName().get());
        Assert.assertEquals("grammarSpec", g4Task.getStartRule().get());
        Assert.assertTrue(g4Task.getTaskDependencies().getDependencies(g4Task).contains(project.getTasks().named("classes").get()));

        final G4toClassTask g4ToClassTask = G4toClassTask.class.cast(project.getTasks().getByName("antlrG4ToClass"));
        Assert.assertEquals("name.jurgenei.parsers.ANTLRv4Parser", g4ToClassTask.getParserClassName().get());
        Assert.assertEquals("name.jurgenei.parsers.ANTLRv4Lexer", g4ToClassTask.getLexerClassName().get());
        Assert.assertEquals("grammarSpec", g4ToClassTask.getStartRule().get());
    }

    @Test
    public void configuresCustomXmlAstTaskTypesRegisteredAfterPluginApply() {
        final Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply("java");

        new XmlAstPlugin().apply(project);

        final TestLegacyXmlAstTask legacyTask = TestLegacyXmlAstTask.class.cast(
                project.getTasks().register("legacyXmlAst", TestLegacyXmlAstTask.class).get());
        final TestModernXmlAstTask modernTask = TestModernXmlAstTask.class.cast(
                project.getTasks().register("modernXmlAst", TestModernXmlAstTask.class).get());

        final SourceSet mainSourceSet = project.getExtensions()
                .getByType(JavaPluginExtension.class)
                .getSourceSets()
                .getByName(SourceSet.MAIN_SOURCE_SET_NAME);

        Assert.assertTrue(legacyTask.getRuntimeClasspath().getFiles().containsAll(mainSourceSet.getRuntimeClasspath().getFiles()));
        Assert.assertTrue(modernTask.getRuntimeClasspath().getFiles().containsAll(mainSourceSet.getRuntimeClasspath().getFiles()));

        Assert.assertTrue(legacyTask.getTaskDependencies().getDependencies(legacyTask)
                .contains(project.getTasks().named("classes").get()));
        Assert.assertTrue(modernTask.getTaskDependencies().getDependencies(modernTask)
                .contains(project.getTasks().named("classes").get()));
    }

    public abstract static class TestLegacyXmlAstTask extends XmlAstTask {
        @Inject
        public TestLegacyXmlAstTask(final org.gradle.api.model.ObjectFactory objects) {
            super(objects);
        }
    }

    public abstract static class TestModernXmlAstTask extends XmlAstGradleTask {
        @Inject
        public TestModernXmlAstTask(final org.gradle.api.model.ObjectFactory objects) {
            super(objects);
        }
    }
}

