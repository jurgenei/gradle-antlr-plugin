package name.jurgenei.gradle.antlr;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.RuleNode;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.gradle.api.GradleException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts SQL input files to XML AST output using dynamically loaded ANTLR lexer/parser classes.
 *
 * <p>The converter is intentionally runtime-driven and does not require compile-time
 * dependencies on generated grammar classes. A caller provides fully-qualified class names
 * and an entry rule name, and the converter reflects into the parser to produce an XML
 * representation of the parse tree.</p>
 */
public final class DynamicAntlrXmlAstConverter {

    /**
     * Converts a list of source files relative to a source root into XML AST output files.
     *
     * @param sourceRoot root directory used to preserve relative output paths.
     * @param sourceFiles source files to parse.
     * @param destinationRoot output root directory receiving generated XML files.
     * @param targetExtension output extension appended to mapped source filenames.
     * @param classLoader class loader containing generated lexer/parser classes.
     * @param lexerClassName fully-qualified lexer class name.
     * @param parserClassName fully-qualified parser class name.
     * @param startRule parser entry rule method name.
     * @throws GradleException when parsing or XML generation fails.
     */
    public void convertFileTree(
            final File sourceRoot,
            final List<File> sourceFiles,
            final File destinationRoot,
            final String targetExtension,
            final ClassLoader classLoader,
            final String lexerClassName,
            final String parserClassName,
            final String startRule) {
        try {
            Files.createDirectories(destinationRoot.toPath());

            for (File sourceFile : sourceFiles) {
                final Path relative = sourceRoot.toPath().relativize(sourceFile.toPath());
                final Path output = destinationRoot.toPath().resolve(mapTarget(relative, targetExtension));
                Files.createDirectories(output.getParent());

                final String xml = parseToXml(
                        sourceFile.toPath(),
                        classLoader,
                        lexerClassName,
                        parserClassName,
                        startRule);
                Files.writeString(output, xml, StandardCharsets.UTF_8);
            }
        } catch (Exception ex) {
            throw new GradleException("Dynamic ANTLR conversion failed", ex);
        }
    }

    private String mapTarget(final Path relativePath, final String targetExtension) {
        final String asText = relativePath.toString();
        final int dot = asText.lastIndexOf('.');
        final String base = dot > 0 ? asText.substring(0, dot) : asText;
        return base + targetExtension;
    }

    private String parseToXml(
            final Path sourceFile,
            final ClassLoader classLoader,
            final String lexerClassName,
            final String parserClassName,
            final String startRule) throws Exception {
        final Class<?> lexerRaw = classLoader.loadClass(lexerClassName);
        final Class<?> parserRaw = classLoader.loadClass(parserClassName);

        if (!Lexer.class.isAssignableFrom(lexerRaw)) {
            throw new IllegalArgumentException("Class is not an ANTLR lexer: " + lexerClassName);
        }
        if (!Parser.class.isAssignableFrom(parserRaw)) {
            throw new IllegalArgumentException("Class is not an ANTLR parser: " + parserClassName);
        }

        @SuppressWarnings("unchecked") final Class<? extends Lexer> lexerClass = (Class<? extends Lexer>) lexerRaw;
        @SuppressWarnings("unchecked") final Class<? extends Parser> parserClass = (Class<? extends Parser>) parserRaw;

        final Constructor<? extends Lexer> lexerCtor = lexerClass.getConstructor(org.antlr.v4.runtime.CharStream.class);
        final Lexer lexer = lexerCtor.newInstance(CharStreams.fromPath(sourceFile, StandardCharsets.UTF_8));

        final CommonTokenStream tokenStream = new CommonTokenStream(lexer);

        final Constructor<? extends Parser> parserCtor = parserClass.getConstructor(org.antlr.v4.runtime.TokenStream.class);
        final Parser parser = parserCtor.newInstance(tokenStream);

        final CollectingErrorListener errors = new CollectingErrorListener();
        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        lexer.addErrorListener(errors);
        parser.addErrorListener(errors);

        final Method entryPoint = parserClass.getMethod(startRule);
        final Object treeObj = entryPoint.invoke(parser);
        if (!(treeObj instanceof ParseTree parseTree)) {
            throw new IllegalStateException("Start rule does not return a ParseTree: " + startRule);
        }

        if (errors.errorCount > 0) {
            throw new GradleException("Parse failed for " + sourceFile + ": " + String.join(" | ", errors.messages));
        }

        return toXml(parser, parseTree, sourceFile.getFileName().toString(), startRule);
    }

    private String toXml(final Parser parser, final ParseTree parseTree, final String sourceName, final String startRule)
            throws Exception {
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        final var document = factory.newDocumentBuilder().newDocument();

        final var root = document.createElement("ast");
        root.setAttribute("source", sourceName);
        root.setAttribute("entryRule", startRule);
        document.appendChild(root);

        appendTree(document, root, parseTree, parser);

        final Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

        final StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }

    private void appendTree(
            final org.w3c.dom.Document document,
            final org.w3c.dom.Element parent,
            final ParseTree node,
            final Parser parser) {
        if (node instanceof RuleNode ruleNode) {
            final int ruleIndex = ruleNode.getRuleContext().getRuleIndex();
            final String ruleName = parser.getRuleNames()[ruleIndex];
            final var element = document.createElement("rule");
            element.setAttribute("name", ruleName);
            parent.appendChild(element);
            for (int i = 0; i < node.getChildCount(); i++) {
                appendTree(document, element, node.getChild(i), parser);
            }
            return;
        }

        if (node instanceof TerminalNode terminalNode) {
            final Token token = terminalNode.getSymbol();
            final var element = document.createElement("token");
            element.setAttribute("type", tokenName(parser, token));
            element.setAttribute("line", Integer.toString(token.getLine()));
            element.setAttribute("column", Integer.toString(token.getCharPositionInLine()));
            element.setTextContent(token.getText());
            parent.appendChild(element);
            return;
        }

        final var element = document.createElement("node");
        element.setTextContent(node.getText());
        parent.appendChild(element);
    }

    private String tokenName(final Parser parser, final Token token) {
        final String symbolic = parser.getVocabulary().getSymbolicName(token.getType());
        if (symbolic != null) {
            return symbolic;
        }
        final String literal = parser.getVocabulary().getLiteralName(token.getType());
        if (literal != null) {
            return literal;
        }
        return Integer.toString(token.getType());
    }

    private static final class CollectingErrorListener extends BaseErrorListener {
        private int errorCount;
        private final List<String> messages = new ArrayList<>();

        @Override
        public void syntaxError(
                final Recognizer<?, ?> recognizer,
                final Object offendingSymbol,
                final int line,
                final int charPositionInLine,
                final String msg,
                final RecognitionException e) {
            errorCount++;
            messages.add(line + ":" + charPositionInLine + " " + msg);
        }
    }
}

