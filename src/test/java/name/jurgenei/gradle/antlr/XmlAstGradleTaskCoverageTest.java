package name.jurgenei.gradle.antlr;

import name.jurgenei.gradle.antlr.mini.MiniLexer;
import name.jurgenei.gradle.antlr.mini.MiniParser;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.model.ObjectFactory;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.inject.Inject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class XmlAstGradleTaskCoverageTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void convertsUsingConfiguredXirFormat() throws Exception {
        final TestXmlAstGradleTask task = newTask("modern-xmlast-xir");
        final File sourceDir = temporaryFolder.newFolder("modern-source");
        final File destinationDir = temporaryFolder.newFolder("modern-out");
        final File sample = new File(sourceDir, "sample.sql");
        Files.writeString(sample.toPath(), "SELECT * FROM employees;\n", StandardCharsets.UTF_8);

        task.getSourceDirectory().set(sourceDir);
        task.getDestinationDirectory().set(destinationDir);
        task.getParserClassName().set(MiniParser.class.getName());
        task.getLexerClassName().set(MiniLexer.class.getName());
        task.getStartRule().set("script");
        task.getTargetExtension().set(".xir");
        task.getXirFormat().set("beautified");
        task.getRuntimeClasspath().from(runtimeLocation(MiniParser.class));

        task.convert();

        final File output = new File(destinationDir, "sample.xir");
        Assert.assertTrue("Expected generated XIR output", output.isFile());
        final String xir = Files.readString(output.toPath(), StandardCharsets.UTF_8);
        Assert.assertTrue(xir.contains("(ast"));
    }

    @Test
    public void rejectsInvalidXirFormatDuringConversionValidation() throws Exception {
        final TestXmlAstGradleTask task = newTask("modern-xmlast-invalid-format");
        final File sourceDir = temporaryFolder.newFolder("modern-source-invalid");
        final File destinationDir = temporaryFolder.newFolder("modern-out-invalid");
        final File sample = new File(sourceDir, "sample.sql");
        Files.writeString(sample.toPath(), "SELECT * FROM employees;\n", StandardCharsets.UTF_8);

        task.getSourceDirectory().set(sourceDir);
        task.getDestinationDirectory().set(destinationDir);
        task.getParserClassName().set(MiniParser.class.getName());
        task.getLexerClassName().set(MiniLexer.class.getName());
        task.getStartRule().set("script");
        task.getTargetExtension().set(".xir");
        task.getXirFormat().set("pretty");

        final GradleException ex = Assert.assertThrows(GradleException.class, task::convert);
        Assert.assertTrue(ex.getMessage().contains("xirFormat must be 'compact' or 'beautified'"));
    }

    @Test
    public void resolvesCatalogParserAndLexerWhenNotExplicitlyConfigured() throws Exception {
        final TestXmlAstGradleTask task = newTask("modern-xmlast-catalog");
        final File sourceDir = temporaryFolder.newFolder("modern-source-catalog");
        final File destinationDir = temporaryFolder.newFolder("modern-out-catalog");
        final File sample = new File(sourceDir, "sample.sql");
        final File catalog = temporaryFolder.newFile("catalog.xml");
        Files.writeString(sample.toPath(), "SELECT * FROM employees;\n", StandardCharsets.UTF_8);
        Files.writeString(catalog.toPath(), """
                <catalog>
                  <grammar name="mini" runtimeGrammar="oracle" parser="%s" lexer="%s" start-rule="script"/>
                </catalog>
                """.formatted(MiniParser.class.getName(), MiniLexer.class.getName()), StandardCharsets.UTF_8);

        task.getSourceDirectory().set(sourceDir);
        task.getDestinationDirectory().set(destinationDir);
        task.getCatalogFile().set(catalog);
        task.getCatalogGrammar().set("mini");
        task.getTargetExtension().set(".xml");
        task.getRuntimeClasspath().from(runtimeLocation(MiniParser.class));

        task.convert();

        final File output = new File(destinationDir, "sample.xml");
        Assert.assertTrue("Expected generated XML output", output.isFile());
    }

    private TestXmlAstGradleTask newTask(final String name) {
        final Project project = ProjectBuilder.builder().build();
        return project.getTasks().register(name, TestXmlAstGradleTask.class).get();
    }

    private static File runtimeLocation(final Class<?> type) throws Exception {
        return new File(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    public abstract static class TestXmlAstGradleTask extends XmlAstGradleTask {
        @Inject
        public TestXmlAstGradleTask(final ObjectFactory objects) {
            super(objects);
        }
    }
}
