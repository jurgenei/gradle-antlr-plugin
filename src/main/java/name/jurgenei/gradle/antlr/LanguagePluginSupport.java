package name.jurgenei.gradle.antlr;

import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;

/**
 * Shared helpers for language plugin modules built on top of XmlAstGradleTask.
 */
public final class LanguagePluginSupport {

    private LanguagePluginSupport() {
    }

    /**
     * Registers language-specific XmlAst task with common group/description metadata.
     */
    public static <T extends XmlAstGradleTask> TaskProvider<T> registerXmlAstTask(
            final Project project,
            final String taskName,
            final Class<T> taskType,
            final String description) {
        return project.getTasks().register(taskName, taskType, task -> {
            task.setGroup("xmlast");
            task.setDescription(description);
        });
    }

    /**
     * Wires runtime classpath and classes dependency for all tasks of given type when Java plugin exists.
     */
    public static <T extends XmlAstGradleTask> void wireJavaRuntimeClasspath(
            final Project project,
            final Class<T> taskType) {
        project.getPlugins().withId("java", plugin -> {
            final JavaPluginExtension javaPluginExtension = project.getExtensions().getByType(JavaPluginExtension.class);
            final SourceSetContainer sourceSets = javaPluginExtension.getSourceSets();
            final SourceSet mainSourceSet = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);

            project.getTasks().withType(taskType).configureEach(task -> {
                task.getRuntimeClasspath().from(mainSourceSet.getRuntimeClasspath());
                task.dependsOn(project.getTasks().named("classes"));
            });
        });
    }
}

