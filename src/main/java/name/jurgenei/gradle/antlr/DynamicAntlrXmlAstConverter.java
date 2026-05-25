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
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * @param compression enables rule-chain compression and path index emission when true.
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
            final String startRule,
            final boolean compression) {
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
                        startRule,
                        compression);
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
            final String startRule,
            final boolean compression) throws Exception {
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

        return toXml(parser, parseTree, sourceFile.getFileName().toString(), startRule, compression);
    }

    private String toXml(
            final Parser parser,
            final ParseTree parseTree,
            final String sourceName,
            final String startRule,
            final boolean compression)
            throws Exception {
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        final var document = factory.newDocumentBuilder().newDocument();

        final var root = document.createElement("ast");
        root.setAttribute("source", sourceName);
        root.setAttribute("entryRule", startRule);
        document.appendChild(root);

        appendTree(document, root, parseTree, parser);

        if (compression) {
            final Map<String, String> pathIndex = new LinkedHashMap<>();
            compressRuleSubtree(root, pathIndex);
            appendPathIndex(document, root, pathIndex);
        }

        final Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

        final StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }

    private void compressRuleSubtree(final org.w3c.dom.Element element, final Map<String, String> pathIndex) {
        if ("rule".equals(element.getTagName())) {
            final List<String> chainNames = new ArrayList<>();
            chainNames.add(element.getAttribute("name"));

            while (hasSingleRuleChild(element)) {
                final org.w3c.dom.Element childRule = singleElementChild(element);
                chainNames.add(childRule.getAttribute("name"));

                element.removeChild(childRule);
                while (childRule.hasChildNodes()) {
                    element.appendChild(childRule.getFirstChild());
                }
            }

            if (chainNames.size() >= 2) {
                final String path = String.join("/", chainNames);
                final String pathId = ensureUniquePathId(path, pathIndex);
                element.setAttribute("pathId", pathId);
            }
        }

        final List<org.w3c.dom.Element> children = elementChildren(element);
        for (org.w3c.dom.Element child : children) {
            compressRuleSubtree(child, pathIndex);
        }
    }

    private boolean hasSingleRuleChild(final org.w3c.dom.Element element) {
        final List<org.w3c.dom.Element> children = elementChildren(element);
        return children.size() == 1 && "rule".equals(children.get(0).getTagName());
    }

    private org.w3c.dom.Element singleElementChild(final org.w3c.dom.Element element) {
        return elementChildren(element).get(0);
    }

    private List<org.w3c.dom.Element> elementChildren(final org.w3c.dom.Element element) {
        final List<org.w3c.dom.Element> children = new ArrayList<>();
        final org.w3c.dom.NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            final org.w3c.dom.Node node = nodes.item(i);
            if (node instanceof org.w3c.dom.Element child) {
                children.add(child);
            }
        }
        return children;
    }

    private String ensureUniquePathId(final String path, final Map<String, String> pathIndex) {
        int attempt = 0;
        while (true) {
            final String id = shortHash(attempt == 0 ? path : path + "#" + attempt);
            final String existing = pathIndex.get(id);
            if (existing == null) {
                pathIndex.put(id, path);
                return id;
            }
            if (existing.equals(path)) {
                return id;
            }
            attempt++;
        }
    }

    private String shortHash(final String value) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash compression path", ex);
        }
    }

    private void appendPathIndex(
            final org.w3c.dom.Document document,
            final org.w3c.dom.Element root,
            final Map<String, String> pathIndex) {
        if (pathIndex.isEmpty()) {
            return;
        }
        final org.w3c.dom.Element index = document.createElement("pathIndex");
        for (Map.Entry<String, String> entry : pathIndex.entrySet()) {
            final org.w3c.dom.Element path = document.createElement("path");
            path.setAttribute("id", entry.getKey());
            path.setAttribute("value", entry.getValue());
            index.appendChild(path);
        }
        root.appendChild(index);
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

