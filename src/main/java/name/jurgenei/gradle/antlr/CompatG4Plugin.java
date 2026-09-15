package name.jurgenei.gradle.antlr;

import name.jurgenei.gradle.xml.G4toClassTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * Compatibility plugin for legacy id {@code name.jurgenei.gradle.antlr.g4}.
 */
@SuppressWarnings("unused")
public final class CompatG4Plugin implements Plugin<Project> {

    @Override
    public void apply(final Project project) {
        LanguagePluginSupport.registerXmlAstTask(
                project,
                "g4XmlAst",
                XmlAstG4GradleTask.class,
                "Convert ANTLRv4 grammar files to XML AST output.");
        LanguagePluginSupport.wireJavaRuntimeClasspath(project, XmlAstG4GradleTask.class);

        project.getTasks().register("g4ToClass", G4toClassTask.class, task -> {
            task.setGroup("xmlast");
            task.setDescription("Convert ANTLRv4 grammar files to GrammarModel and AST-Classes output.");
        });
    }
}

