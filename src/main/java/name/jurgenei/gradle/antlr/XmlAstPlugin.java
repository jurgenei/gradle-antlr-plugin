package name.jurgenei.gradle.antlr;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

/**
 * Registers sqlxmlast Gradle tasks and wires them to the Java runtime classpath.
 *
 * <p>The plugin exposes a default {@code sqlxmlast} task using {@link XmlAstGradleTask}
 * and also configures all SqlXmlAst task types to depend on {@code classes}, ensuring
 * converter classes are available before conversion starts.</p>
 */
public class XmlAstPlugin implements Plugin<Project> {

    /**
     * Applies task registrations and common task conventions for this plugin.
     *
     * @param project Gradle project receiving the plugin.
     */
    @Override
    public void apply(final Project project) {
        final JavaPluginExtension javaPluginExtension = project.getExtensions().getByType(JavaPluginExtension.class);
        final SourceSetContainer sourceSets = javaPluginExtension.getSourceSets();
        final SourceSet mainSourceSet = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);

        project.getTasks().register("sqlxmlast", XmlAstGradleTask.class, task -> {
            task.setGroup("sqlxmlast");
            task.setDescription("Convert SQL file trees to XML AST output.");
            task.getRuntimeClasspath().from(mainSourceSet.getRuntimeClasspath());
        });

        project.getTasks().withType(XmlAstGradleTask.class).configureEach(task -> {
            task.getRuntimeClasspath().from(mainSourceSet.getRuntimeClasspath());
            task.dependsOn(project.getTasks().named("classes"));
        });

        project.getTasks().withType(XmlAstTask.class).configureEach(task -> {
            task.getRuntimeClasspath().from(mainSourceSet.getRuntimeClasspath());
            task.dependsOn(project.getTasks().named("classes"));
        });
    }
}

