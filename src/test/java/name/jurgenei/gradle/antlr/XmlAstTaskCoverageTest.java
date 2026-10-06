package name.jurgenei.gradle.antlr;

import name.jurgenei.gradle.antlr.mini.MiniLexer;
import name.jurgenei.gradle.antlr.mini.MiniParser;
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

public class XmlAstTaskCoverageTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void convertsLegacyTaskWithConfiguredOutputFormat() throws Exception {
        final TestLegacyXmlAstTask task = newTask("legacy-xmlast-xir");
        final File sourceDir = temporaryFolder.newFolder("legacy-source");
        final File destinationDir = temporaryFolder.newFolder("legacy-out");
        final File nestedDir = new File(sourceDir, "nested");
        Assert.assertTrue(nestedDir.mkdirs());
        final File sample = new File(nestedDir, "sample.sql");
        Files.writeString(sample.toPath(), "SELECT * FROM employees;\n", StandardCharsets.UTF_8);

        task.getSourceTrees().from(sourceDir);
        task.getDestinationDirectory().set(destinationDir);
        task.getParserClassName().set(MiniParser.class.getName());
        task.getLexerClassName().set(MiniLexer.class.getName());
        task.getStartRule().set("script");
        task.getTargetExtension().set(".xir");
        task.getOutputFormat().set("beautified");
        task.getRuntimeClasspath().from(runtimeLocation(MiniParser.class));

        task.convertSqlTrees();

        final File output = new File(destinationDir, "nested/sample.xir");
        Assert.assertTrue("Expected generated XIR output", output.isFile());
    }

    private TestLegacyXmlAstTask newTask(final String name) {
        final Project project = ProjectBuilder.builder().build();
        return project.getTasks().register(name, TestLegacyXmlAstTask.class).get();
    }

    private static File runtimeLocation(final Class<?> type) throws Exception {
        return new File(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    public abstract static class TestLegacyXmlAstTask extends XmlAstTask {
        @Inject
        public TestLegacyXmlAstTask(final ObjectFactory objects) {
            super(objects);
        }
    }
}
