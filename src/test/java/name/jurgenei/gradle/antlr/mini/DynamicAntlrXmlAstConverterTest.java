package name.jurgenei.gradle.antlr.mini;

import name.jurgenei.gradle.antlr.DynamicAntlrXmlAstConverter;
import org.gradle.api.GradleException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public class DynamicAntlrXmlAstConverterTest {

    private static final Path VALID_DIR = Paths.get("src", "test", "resources", "fixtures", "mini", "valid");
    private static final Path INVALID_DIR = Paths.get("src", "test", "resources", "fixtures", "mini", "invalid");

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void convertsValidFixturesToXmlAstFiles() throws Exception {
        final File outputDir = temporaryFolder.newFolder("xml-ast-out");
        final List<File> inputs = listSqlFiles(VALID_DIR).stream().map(Path::toFile).toList();

        new DynamicAntlrXmlAstConverter().convertFileTree(
                VALID_DIR.toFile(),
                inputs,
                outputDir,
                ".xml",
                MiniLexer.class.getClassLoader(),
                MiniLexer.class.getName(),
                MiniParser.class.getName(),
                "script",
                false,
                false);

        for (Path sourcePath : listSqlFiles(VALID_DIR)) {
            final String fileName = sourcePath.getFileName().toString();
            final String xmlName = fileName.substring(0, fileName.lastIndexOf('.')) + ".xml";
            final Path xmlPath = outputDir.toPath().resolve(xmlName);

            Assert.assertTrue("Expected XML output for " + fileName, Files.exists(xmlPath));
            final String xml = Files.readString(xmlPath, StandardCharsets.UTF_8);
            Assert.assertTrue("Expected <ast root element in " + xmlName, xml.contains("<ast"));
            Assert.assertTrue("Expected script rule in " + xmlName, xml.contains("name=\"script\""));
        }
    }

    @Test
    public void emitsPathIndexWhenCompressionEnabled() throws Exception {
        final File outputDir = temporaryFolder.newFolder("xml-ast-compressed");
        final List<File> inputs = List.of(VALID_DIR.resolve("01_select_star.sql").toFile());

        new DynamicAntlrXmlAstConverter().convertFileTree(
                VALID_DIR.toFile(),
                inputs,
                outputDir,
                ".xml",
                MiniLexer.class.getClassLoader(),
                MiniLexer.class.getName(),
                MiniParser.class.getName(),
                "script",
                true,
                false);

        final Path xmlPath = outputDir.toPath().resolve("01_select_star.xml");
        Assert.assertTrue("Expected XML output", Files.exists(xmlPath));
        final String xml = Files.readString(xmlPath, StandardCharsets.UTF_8);
        Assert.assertTrue("Expected pathIndex section", xml.contains("<pathIndex>"));
        Assert.assertTrue("Expected compressed path id attribute", xml.contains("pathId=\""));
    }

    @Test(expected = GradleException.class)
    public void failsOnInvalidFixtureInput() throws Exception {
        final File outputDir = temporaryFolder.newFolder("xml-ast-invalid");
        final List<File> invalid = List.of(INVALID_DIR.resolve("01_missing_from.sql").toFile());

        new DynamicAntlrXmlAstConverter().convertFileTree(
                INVALID_DIR.toFile(),
                invalid,
                outputDir,
                ".xml",
                MiniLexer.class.getClassLoader(),
                MiniLexer.class.getName(),
                MiniParser.class.getName(),
                "script",
                false,
                false);
    }

    @Test
    public void rejectsEmptySourceFiles() throws Exception {
        final File outputDir = temporaryFolder.newFolder("xml-ast-empty");

        final IllegalArgumentException ex = Assert.assertThrows(
                IllegalArgumentException.class,
                () -> new DynamicAntlrXmlAstConverter().convertFileTree(
                        VALID_DIR.toFile(),
                        List.of(),
                        outputDir,
                        ".xml",
                        MiniLexer.class.getClassLoader(),
                        MiniLexer.class.getName(),
                        MiniParser.class.getName(),
                        "script",
                        false,
                        false));

        Assert.assertTrue(ex.getMessage().contains("sourceFiles cannot be empty"));
    }

    @Test
    public void rejectsBlankStartRule() throws Exception {
        final File outputDir = temporaryFolder.newFolder("xml-ast-blank-rule");
        final List<File> inputs = List.of(VALID_DIR.resolve("01_select_star.sql").toFile());

        final IllegalArgumentException ex = Assert.assertThrows(
                IllegalArgumentException.class,
                () -> new DynamicAntlrXmlAstConverter().convertFileTree(
                        VALID_DIR.toFile(),
                        inputs,
                        outputDir,
                        ".xml",
                        MiniLexer.class.getClassLoader(),
                        MiniLexer.class.getName(),
                        MiniParser.class.getName(),
                        "   ",
                        false,
                        false));

        Assert.assertTrue(ex.getMessage().contains("startRule cannot be blank"));
    }

    @Test
    public void rejectsInvalidExecutionModelInStatsCall() throws Exception {
        final File outputDir = temporaryFolder.newFolder("xml-ast-invalid-model");
        final List<File> inputs = List.of(VALID_DIR.resolve("01_select_star.sql").toFile());

        final IllegalArgumentException ex = Assert.assertThrows(
                IllegalArgumentException.class,
                () -> new DynamicAntlrXmlAstConverter().convertFileTreeWithStats(
                        VALID_DIR.toFile(),
                        inputs,
                        outputDir,
                        ".xml",
                        MiniLexer.class.getClassLoader(),
                        MiniLexer.class.getName(),
                        MiniParser.class.getName(),
                        "script",
                        false,
                        false,
                        "BAD_MODEL",
                        1));

        Assert.assertTrue(ex.getMessage().contains("Invalid executionModelName"));
    }

    @Test
    public void rejectsNonPositiveParallelismInStatsCall() throws Exception {
        final File outputDir = temporaryFolder.newFolder("xml-ast-invalid-parallelism");
        final List<File> inputs = List.of(VALID_DIR.resolve("01_select_star.sql").toFile());

        final IllegalArgumentException ex = Assert.assertThrows(
                IllegalArgumentException.class,
                () -> new DynamicAntlrXmlAstConverter().convertFileTreeWithStats(
                        VALID_DIR.toFile(),
                        inputs,
                        outputDir,
                        ".xml",
                        MiniLexer.class.getClassLoader(),
                        MiniLexer.class.getName(),
                        MiniParser.class.getName(),
                        "script",
                        false,
                        false,
                        "SEQUENTIAL",
                        0));

        Assert.assertTrue(ex.getMessage().contains("configuredParallelism must be >= 1"));
    }

    private static List<Path> listSqlFiles(final Path directory) throws Exception {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }
}

