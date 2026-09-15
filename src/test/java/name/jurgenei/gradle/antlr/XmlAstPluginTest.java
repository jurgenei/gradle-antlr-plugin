package name.jurgenei.gradle.antlr;

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

