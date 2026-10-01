package name.jurgenei.gradle.antlr;

import name.jurgenei.gradle.xml.G4toClassTask;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.Assert;
import org.junit.Test;

import javax.inject.Inject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class XmlAstPluginTest {

    @Test
    public void registersXmlAstTaskWithDefaultsAndJavaClasspathWiring() {
        final Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply("java");

        new XmlAstPlugin().apply(project);

        final XmlAstGradleTask task = (XmlAstGradleTask) project.getTasks().getByName("xmlast");
        Assert.assertEquals("xmlast", task.getGroup());
        Assert.assertEquals("Convert SQL file trees to XML AST output.", task.getDescription());

        final SourceSet mainSourceSet = project.getExtensions()
                .getByType(JavaPluginExtension.class)
                .getSourceSets()
                .getByName(SourceSet.MAIN_SOURCE_SET_NAME);

        Assert.assertTrue(task.getRuntimeClasspath().getFiles().containsAll(mainSourceSet.getRuntimeClasspath().getFiles()));
        Assert.assertTrue(task.getTaskDependencies().getDependencies(task).contains(project.getTasks().named("classes").get()));

        final XmlAstG4GradleTask g4Task = (XmlAstG4GradleTask) project.getTasks().getByName("antlrG4XmlAst");
        Assert.assertEquals("antlr4", g4Task.getGrammar().get());
        Assert.assertEquals("name.jurgenei.parsers.ANTLRv4Parser", g4Task.getParserClassName().get());
        Assert.assertEquals("name.jurgenei.parsers.ANTLRv4Lexer", g4Task.getLexerClassName().get());
        Assert.assertEquals("grammarSpec", g4Task.getStartRule().get());
        Assert.assertTrue(g4Task.getTaskDependencies().getDependencies(g4Task).contains(project.getTasks().named("classes").get()));

        final G4toClassTask g4ToClassTask = (G4toClassTask) project.getTasks().getByName("antlrG4ToClass");
        Assert.assertEquals("name.jurgenei.parsers.ANTLRv4Parser", g4ToClassTask.getParserClassName().get());
        Assert.assertEquals("name.jurgenei.parsers.ANTLRv4Lexer", g4ToClassTask.getLexerClassName().get());
        Assert.assertEquals("grammarSpec", g4ToClassTask.getStartRule().get());
    }

    @Test
    public void configuresCustomXmlAstTaskTypesRegisteredAfterPluginApply() {
        final Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply("java");

        new XmlAstPlugin().apply(project);

        final TestLegacyXmlAstTask legacyTask = project.getTasks().register("legacyXmlAst", TestLegacyXmlAstTask.class).get();
        final TestModernXmlAstTask modernTask = project.getTasks().register("modernXmlAst", TestModernXmlAstTask.class).get();

        final SourceSet mainSourceSet = project.getExtensions()
                .getByType(JavaPluginExtension.class)
                .getSourceSets()
                .getByName(SourceSet.MAIN_SOURCE_SET_NAME);

        Assert.assertTrue(legacyTask.getRuntimeClasspath().getFiles().containsAll(mainSourceSet.getRuntimeClasspath().getFiles()));
        Assert.assertTrue(modernTask.getRuntimeClasspath().getFiles().containsAll(mainSourceSet.getRuntimeClasspath().getFiles()));
        Assert.assertEquals("compact", legacyTask.getXirFormat().get());

        Assert.assertTrue(legacyTask.getTaskDependencies().getDependencies(legacyTask)
                .contains(project.getTasks().named("classes").get()));
        Assert.assertTrue(modernTask.getTaskDependencies().getDependencies(modernTask)
                .contains(project.getTasks().named("classes").get()));
    }

    @Test
    public void resolvesLegacyTaskCatalogEntryWithRecordAccessors() throws Exception {
        final Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply("java");

        new XmlAstPlugin().apply(project);
        final TestLegacyXmlAstTask legacyTask = project.getTasks().register("legacyXmlAstForCatalog", TestLegacyXmlAstTask.class).get();

        final File catalog = File.createTempFile("xmlast-catalog", ".xml");
        catalog.deleteOnExit();
        Files.writeString(catalog.toPath(), """
                <catalog>
                  <grammar name="mini" runtimeGrammar="oracle" parser="e2e.MiniParser" lexer="e2e.MiniLexer" start-rule="root"/>
                </catalog>
                """, StandardCharsets.UTF_8);

        legacyTask.getCatalogFile().set(catalog);
        legacyTask.getCatalogGrammar().set("mini");

        final var method = XmlAstTask.class.getDeclaredMethod("resolveEffectiveConfig");
        method.setAccessible(true);
        final Object resolved = method.invoke(legacyTask);
        final Class<?> type = resolved.getClass();

        Assert.assertEquals("e2e.MiniParser", type.getMethod("parserClassName").invoke(resolved));
        Assert.assertEquals("e2e.MiniLexer", type.getMethod("lexerClassName").invoke(resolved));
        Assert.assertEquals("root", type.getMethod("startRule").invoke(resolved));
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
