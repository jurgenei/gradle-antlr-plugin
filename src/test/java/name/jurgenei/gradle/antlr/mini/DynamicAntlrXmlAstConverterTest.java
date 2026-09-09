package name.jurgenei.gradle.antlr.mini;

import name.jurgenei.gradle.antlr.DynamicAntlrXmlAstConverter;
import org.gradle.api.GradleException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;

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
                false,
                null);

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
    public void convertsValidFixturesToSexprAstFilesWhenTargetExtensionIsSexpr() throws Exception {
        final File outputDir = temporaryFolder.newFolder("sexpr-ast-out");
        final List<File> inputs = listSqlFiles(VALID_DIR).stream().map(Path::toFile).toList();

        new DynamicAntlrXmlAstConverter().convertFileTree(
                VALID_DIR.toFile(),
                inputs,
                outputDir,
                ".sexpr",
                MiniLexer.class.getClassLoader(),
                MiniLexer.class.getName(),
                MiniParser.class.getName(),
                "script",
                false,
                false,
                "beautified",
                null);

        final Path sexprPath = outputDir.toPath().resolve("01_select_star.sexpr");
        Assert.assertTrue("Expected S-expression output", Files.exists(sexprPath));
        final String sexpr = Files.readString(sexprPath, StandardCharsets.UTF_8);
        Assert.assertTrue("Expected canonical S-expression document", sexpr.startsWith("(."));
        Assert.assertTrue("Expected beautified line breaks", sexpr.contains(System.lineSeparator()));
        Assert.assertTrue("Expected ast root node", sexpr.contains("(ast"));
        Assert.assertTrue("Expected entry rule attribute", sexpr.contains("entryRule \"script\""));
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
                false,
                null);

        final Path xmlPath = outputDir.toPath().resolve("01_select_star.xml");
        Assert.assertTrue("Expected XML output", Files.exists(xmlPath));
        final String xml = Files.readString(xmlPath, StandardCharsets.UTF_8);
        Assert.assertTrue("Expected pathIndex section", xml.contains("<pathIndex>"));
        Assert.assertTrue("Expected compressed path id attribute", xml.contains("pathId=\""));
    }

    @Test
    public void emitsPathIndexInSexprWhenCompressionEnabled() throws Exception {
        final File outputDir = temporaryFolder.newFolder("sexpr-ast-compressed");
        final List<File> inputs = List.of(VALID_DIR.resolve("01_select_star.sql").toFile());

        new DynamicAntlrXmlAstConverter().convertFileTree(
                VALID_DIR.toFile(),
                inputs,
                outputDir,
                ".sexpr",
                MiniLexer.class.getClassLoader(),
                MiniLexer.class.getName(),
                MiniParser.class.getName(),
                "script",
                true,
                false,
                null);

        final Path sexprPath = outputDir.toPath().resolve("01_select_star.sexpr");
        Assert.assertTrue("Expected S-expression output", Files.exists(sexprPath));
        final String sexpr = Files.readString(sexprPath, StandardCharsets.UTF_8);
        Assert.assertTrue("Expected pathIndex node", sexpr.contains("(pathIndex"));
        Assert.assertTrue("Expected compressed path id attribute", sexpr.contains("pathId \""));
    }

    @Test
    public void compressionPathIndexProvidesStableLookupForFlowgraphStyleMatching() throws Exception {
        final File outputDir = temporaryFolder.newFolder("xml-ast-compressed-lookup");
        final List<File> inputs = List.of(VALID_DIR.resolve("04_mixed_script.sql").toFile());

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
                false,
                null);

        final Path xmlPath = outputDir.toPath().resolve("04_mixed_script.xml");
        Assert.assertTrue("Expected XML output", Files.exists(xmlPath));

        final Document document = parseXml(xmlPath);
        final Map<String, String> index = readPathIndex(document);
        Assert.assertFalse("Expected non-empty pathIndex for compressed output", index.isEmpty());

        final NodeList compressedRules = document.getElementsByTagName("r");
        int compressedRuleCount = 0;

        for (int i = 0; i < compressedRules.getLength(); i++) {
            final Element rule = (Element) compressedRules.item(i);
            if (!rule.hasAttribute("pathId")) {
                continue;
            }
            compressedRuleCount++;

            final String pathId = rule.getAttribute("pathId");
            final String indexedPath = index.get(pathId);
            Assert.assertNotNull("Every pathId on a rule must exist in <pathIndex>", indexedPath);

            final String[] parts = indexedPath.split("/");
            Assert.assertTrue("Compressed path must contain a chain (>= 2 segments)", parts.length >= 2);
            Assert.assertEquals("Path must start with the current rule name", rule.getAttribute("name"), parts[0]);

            // Guard against duplicate leading segments (regression for chain collection bugs).
            for (int p = 1; p < parts.length; p++) {
                Assert.assertNotEquals("Adjacent duplicate path segments are invalid", parts[p - 1], parts[p]);
            }
        }

        Assert.assertTrue("Expected at least one compressed rule with pathId", compressedRuleCount > 0);
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
                false,
                null);
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
                        false,
                        null));

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
                        false,
                        null));

        Assert.assertTrue(ex.getMessage().contains("startRule cannot be blank"));
    }

    @Test
    public void rejectsUnsupportedSexprFormat() throws Exception {
        final File outputDir = temporaryFolder.newFolder("xml-ast-invalid-sexpr-format");
        final List<File> inputs = List.of(VALID_DIR.resolve("01_select_star.sql").toFile());

        final IllegalArgumentException ex = Assert.assertThrows(
                IllegalArgumentException.class,
                () -> new DynamicAntlrXmlAstConverter().convertFileTree(
                        VALID_DIR.toFile(),
                        inputs,
                        outputDir,
                        ".sexpr",
                        MiniLexer.class.getClassLoader(),
                        MiniLexer.class.getName(),
                        MiniParser.class.getName(),
                        "script",
                        false,
                        false,
                        "pretty",
                        null));

        Assert.assertTrue(ex.getMessage().contains("Unsupported sexprFormat"));
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
                        1, null));

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
                        0,null));

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

    private static Document parseXml(final Path xmlPath) throws Exception {
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        return factory.newDocumentBuilder().parse(xmlPath.toFile());
    }

    private static Map<String, String> readPathIndex(final Document document) {
        final Map<String, String> index = new HashMap<>();
        final NodeList paths = document.getElementsByTagName("path");
        for (int i = 0; i < paths.getLength(); i++) {
            final Element path = (Element) paths.item(i);
            index.put(path.getAttribute("id"), path.getAttribute("value"));
        }
        return index;
    }
}

