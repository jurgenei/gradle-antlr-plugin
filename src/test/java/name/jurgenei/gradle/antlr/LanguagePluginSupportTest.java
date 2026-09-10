package name.jurgenei.gradle.antlr;

import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.Assert;
import org.junit.Test;

import javax.inject.Inject;

public class LanguagePluginSupportTest {

    @Test
    public void registerXmlAstTaskSetsGroupAndDescription() {
        final Project project = ProjectBuilder.builder().build();

        final TaskProvider<TestXmlAstTask> provider = LanguagePluginSupport.registerXmlAstTask(
                project,
                "xmlAstMini",
                TestXmlAstTask.class,
                "Mini grammar conversion");

        final TestXmlAstTask task = provider.get();
        Assert.assertEquals("xmlast", task.getGroup());
        Assert.assertEquals("Mini grammar conversion", task.getDescription());
    }

    @Test
    public void wireJavaRuntimeClasspathConfiguresExistingTaskWhenJavaPluginPresent() {
        final Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply("java");

        final TaskProvider<TestXmlAstTask> provider = project.getTasks().register("xmlAst", TestXmlAstTask.class);

        LanguagePluginSupport.wireJavaRuntimeClasspath(project, TestXmlAstTask.class);

        final TestXmlAstTask task = provider.get();
        final SourceSet mainSourceSet = project.getExtensions()
                .getByType(JavaPluginExtension.class)
                .getSourceSets()
                .getByName(SourceSet.MAIN_SOURCE_SET_NAME);

        Assert.assertTrue(
                task.getRuntimeClasspath().getFiles().containsAll(mainSourceSet.getRuntimeClasspath().getFiles()));
        Assert.assertTrue(
                task.getTaskDependencies().getDependencies(task).contains(project.getTasks().named("classes").get()));
    }

    @Test
    public void wireJavaRuntimeClasspathDoesNothingWithoutJavaPlugin() {
        final Project project = ProjectBuilder.builder().build();
        final TaskProvider<TestXmlAstTask> provider = project.getTasks().register("xmlAst", TestXmlAstTask.class);

        LanguagePluginSupport.wireJavaRuntimeClasspath(project, TestXmlAstTask.class);

        final TestXmlAstTask task = provider.get();
        Assert.assertTrue(task.getRuntimeClasspath().isEmpty());
    }

    public abstract static class TestXmlAstTask extends XmlAstGradleTask {
        @Inject
        public TestXmlAstTask(final org.gradle.api.model.ObjectFactory objects) {
            super(objects);
        }
    }
}


