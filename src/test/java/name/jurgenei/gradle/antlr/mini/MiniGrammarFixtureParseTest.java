package name.jurgenei.gradle.antlr.mini;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public class MiniGrammarFixtureParseTest {

    private static final Path FIXTURE_ROOT = Paths.get("src", "test", "resources", "fixtures", "mini");

    @Test
    public void parsesAllValidFixtures() throws Exception {
        final List<Path> files = listSqlFiles(FIXTURE_ROOT.resolve("valid"));
        Assert.assertFalse("Expected at least one valid fixture", files.isEmpty());

        for (final Path file : files) {
            final ParseResult result = parse(file);
            Assert.assertTrue("Expected parse success for " + file + " but got: " + result.errors(), result.isSuccess());
        }
    }

    @Test
    public void rejectsAllInvalidFixtures() throws Exception {
        final List<Path> files = listSqlFiles(FIXTURE_ROOT.resolve("invalid"));
        Assert.assertFalse("Expected at least one invalid fixture", files.isEmpty());

        for (final Path file : files) {
            final ParseResult result = parse(file);
            Assert.assertFalse("Expected parse failure for " + file, result.isSuccess());
        }
    }

    private static ParseResult parse(final Path sqlFile) throws IOException {
        final CharStream input = CharStreams.fromPath(sqlFile, StandardCharsets.UTF_8);
        final MiniLexer lexer = new MiniLexer(input);
        final CommonTokenStream tokens = new CommonTokenStream(lexer);
        final MiniParser parser = new MiniParser(tokens);

        final CollectingErrorListener errorListener = new CollectingErrorListener();
        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        lexer.addErrorListener(errorListener);
        parser.addErrorListener(errorListener);

        parser.script();

        return new ParseResult(errorListener.errorCount == 0, String.join(" | ", errorListener.errors));
    }

    private static List<Path> listSqlFiles(final Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private static final class CollectingErrorListener extends BaseErrorListener {
        private int errorCount;
        private final java.util.ArrayList<String> errors = new java.util.ArrayList<>();

        @Override
        public void syntaxError(
                final Recognizer<?, ?> recognizer,
                final Object offendingSymbol,
                final int line,
                final int charPositionInLine,
                final String msg,
                final RecognitionException e
        ) {
            errorCount++;
            errors.add(line + ":" + charPositionInLine + " " + msg);
        }
    }

    private record ParseResult(boolean isSuccess, String errors) {
    }
}

