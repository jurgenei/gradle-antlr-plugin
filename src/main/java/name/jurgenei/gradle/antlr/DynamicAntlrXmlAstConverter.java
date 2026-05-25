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

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Converts SQL input files to XML AST output using dynamically loaded ANTLR lexer/parser classes.
 *
 * <p>The converter supports both precompiled parser/lexer class names and grammar coordinates
 * (`.g4`) that are generated and compiled at runtime (including HTTP(S) and protocol-less
 * host/path locations).</p>
 *
 * <p>When grammar-source mode is used, lexer/parser `superClass` options are supported as long
 * as superclass types are available through the provided runtime classloader classpath.</p>
 */
public final class DynamicAntlrXmlAstConverter {

    /**
     * Converts a list of source files relative to a source root into XML AST output files.
     *
     * @param sourceRoot root directory used to preserve relative output paths.
     * @param sourceFiles source files to parse.
     * @param destinationRoot output root directory receiving generated XML files.
     * @param targetExtension output extension appended to mapped source filenames.
     * @param classLoader class loader containing runtime dependencies and optionally parser/lexer classes.
     * @param lexerClassName lexer class name or `.g4` grammar coordinate.
     * @param parserClassName parser class name or `.g4` grammar coordinate.
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

            try (RuntimeParserBinding binding = prepareParserBinding(classLoader, lexerClassName, parserClassName)) {
                for (File sourceFile : sourceFiles) {
                    final Path relative = sourceRoot.toPath().relativize(sourceFile.toPath());
                    final Path output = destinationRoot.toPath().resolve(mapTarget(relative, targetExtension));
                    Files.createDirectories(output.getParent());

                    final String xml = parseToXml(
                            sourceFile.toPath(),
                            binding.classLoader(),
                            binding.lexerClassName(),
                            binding.parserClassName(),
                            startRule,
                            compression);
                    Files.writeString(output, xml, StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ex) {
            throw new GradleException("Dynamic ANTLR conversion failed", ex);
        }
    }

    private RuntimeParserBinding prepareParserBinding(
            final ClassLoader classLoader,
            final String lexerSpec,
            final String parserSpec) throws Exception {
        final boolean lexerFromGrammar = isGrammarSourceSpec(lexerSpec);
        final boolean parserFromGrammar = isGrammarSourceSpec(parserSpec);

        if (!lexerFromGrammar && !parserFromGrammar) {
            return new RuntimeParserBinding(classLoader, lexerSpec, parserSpec, null);
        }

        if (lexerFromGrammar != parserFromGrammar) {
            throw new IllegalArgumentException("parser and lexer coordinates must both be class names or both be .g4 resources");
        }

        final Path workspace = Files.createTempDirectory("xmlast-antlr-runtime-");
        final Path grammarDir = workspace.resolve("grammars");
        final Path generatedDir = workspace.resolve("generated");
        final Path classesDir = workspace.resolve("classes");
        Files.createDirectories(grammarDir);
        Files.createDirectories(generatedDir);
        Files.createDirectories(classesDir);

        final Path lexerGrammar = materializeGrammarSpec(lexerSpec, grammarDir, "Lexer.g4");
        final Path parserGrammar = materializeGrammarSpec(parserSpec, grammarDir, "Parser.g4");

        runAntlrTool(classLoader, lexerGrammar, generatedDir, generatedDir);
        runAntlrTool(classLoader, parserGrammar, generatedDir, generatedDir);

        compileGeneratedSources(classLoader, generatedDir, classesDir);

        final String lexerSimple = grammarName(lexerGrammar);
        final String parserSimple = grammarName(parserGrammar);
        final String lexerFqcn = resolveGeneratedFqcn(generatedDir, lexerSimple);
        final String parserFqcn = resolveGeneratedFqcn(generatedDir, parserSimple);

        final URLClassLoader generatedLoader = new URLClassLoader(
                new URL[]{classesDir.toUri().toURL()},
                classLoader);

        return new RuntimeParserBinding(generatedLoader, lexerFqcn, parserFqcn, workspace);
    }

    private boolean isGrammarSourceSpec(final String spec) {
        final String trimmed = spec.trim();
        return trimmed.endsWith(".g4")
                || trimmed.startsWith("http://")
                || trimmed.startsWith("https://")
                || trimmed.startsWith("file:")
                || trimmed.startsWith("//")
                || looksLikeHostPathWithoutScheme(trimmed);
    }

    private boolean looksLikeHostPathWithoutScheme(final String value) {
        if (!value.contains("/") || value.startsWith(".") || value.startsWith("/")) {
            return false;
        }
        final int slash = value.indexOf('/');
        final String hostPort = value.substring(0, slash);
        if (hostPort.contains("://") || hostPort.length() == 1) {
            return false;
        }
        final String host = hostPort.contains(":") ? hostPort.substring(0, hostPort.indexOf(':')) : hostPort;
        return (host.equalsIgnoreCase("localhost") || host.contains(".") || host.contains("-"))
                && value.endsWith(".g4");
    }

    private Path materializeGrammarSpec(final String spec, final Path targetDir, final String fallbackName) throws IOException {
        final URI uri = resolveSpecUri(spec);
        if (uri != null) {
            final String fileName = fileNameFromUri(uri, fallbackName);
            final Path target = targetDir.resolve(fileName);
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                Files.copy(Path.of(uri), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                try (InputStream in = uri.toURL().openStream()) {
                    Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return target;
        }

        final Path local = Path.of(spec);
        if (!Files.isRegularFile(local)) {
            throw new IllegalArgumentException("Grammar resource not found: " + spec);
        }
        return local.toAbsolutePath().normalize();
    }

    private URI resolveSpecUri(final String spec) {
        final String trimmed = spec.trim();
        if (trimmed.startsWith("//")) {
            return URI.create("https:" + trimmed);
        }
        if (looksLikeHostPathWithoutScheme(trimmed)) {
            return URI.create(defaultSchemeForHostPath(trimmed) + "://" + trimmed);
        }
        try {
            final URI uri = URI.create(trimmed);
            if (uri.isAbsolute()) {
                return uri;
            }
        } catch (Exception ignored) {
            // Fall back to local path handling.
        }
        return null;
    }

    private String fileNameFromUri(final URI uri, final String fallback) {
        final String path = uri.getPath();
        if (path == null || path.isBlank()) {
            return fallback;
        }
        final int slash = path.lastIndexOf('/');
        final String candidate = slash >= 0 ? path.substring(slash + 1) : path;
        return candidate.isBlank() ? fallback : candidate;
    }

    private String defaultSchemeForHostPath(final String value) {
        final int slash = value.indexOf('/');
        final String hostPort = slash >= 0 ? value.substring(0, slash) : value;
        final String host = hostPort.contains(":") ? hostPort.substring(0, hostPort.indexOf(':')) : hostPort;
        return host.equalsIgnoreCase("localhost") || host.startsWith("127.") ? "http" : "https";
    }

    private void runAntlrTool(
            final ClassLoader classLoader,
            final Path grammarFile,
            final Path outputDir,
            final Path libDir) throws Exception {
        final Class<?> toolClass = classLoader.loadClass("org.antlr.v4.Tool");
        final String[] args = new String[]{
                "-listener",
                "-no-visitor",
                "-lib", libDir.toString(),
                "-o", outputDir.toString(),
                grammarFile.toString()
        };
        final Constructor<?> ctor = toolClass.getConstructor(String[].class);
        final Object tool = ctor.newInstance((Object) args);
        final Method process = toolClass.getMethod("processGrammarsOnCommandLine");
        process.invoke(tool);
    }

    private void compileGeneratedSources(
            final ClassLoader classLoader,
            final Path generatedDir,
            final Path classesDir) throws IOException {
        final List<File> javaSources;
        try (Stream<Path> stream = Files.walk(generatedDir)) {
            javaSources = stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(Path::toFile)
                    .toList();
        }

        if (javaSources.isEmpty()) {
            throw new IllegalStateException("No generated Java sources found in " + generatedDir);
        }

        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler is not available. Use a JDK (not JRE) to generate runtime grammars.");
        }

        final List<String> classpathEntries = new ArrayList<>();
        if (classLoader instanceof URLClassLoader urlClassLoader) {
            for (URL url : urlClassLoader.getURLs()) {
                if ("file".equalsIgnoreCase(url.getProtocol())) {
                    classpathEntries.add(Path.of(url.getPath()).toString());
                }
            }
        }
        classpathEntries.add(System.getProperty("java.class.path"));

        final String classpath = String.join(File.pathSeparator, classpathEntries);
        final List<String> options = List.of("-classpath", classpath, "-d", classesDir.toString());

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            final var units = fileManager.getJavaFileObjectsFromFiles(javaSources);
            final Boolean ok = compiler.getTask(null, fileManager, null, options, null, units).call();
            if (!Boolean.TRUE.equals(ok)) {
                throw new IllegalStateException("Failed to compile generated ANTLR sources");
            }
        }
    }

    private String grammarName(final Path grammarFile) throws IOException {
        final Pattern pattern = Pattern.compile("(?:lexer|parser)?\\s*grammar\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;");
        try (Stream<String> lines = Files.lines(grammarFile, StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
                final Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        throw new IllegalArgumentException("Unable to extract grammar name from " + grammarFile);
    }

    private String resolveGeneratedFqcn(final Path generatedDir, final String simpleClassName) throws IOException {
        Path source = null;
        try (Stream<Path> stream = Files.walk(generatedDir)) {
            source = stream
                    .filter(path -> path.getFileName().toString().equals(simpleClassName + ".java"))
                    .findFirst()
                    .orElse(null);
        }

        if (source == null) {
            throw new IllegalStateException("Generated source not found for " + simpleClassName);
        }

        String packageName = null;
        try (Stream<String> lines = Files.lines(source, StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
                final String trimmed = line.trim();
                if (trimmed.startsWith("package ") && trimmed.endsWith(";")) {
                    packageName = trimmed.substring("package ".length(), trimmed.length() - 1).trim();
                    break;
                }
            }
        }

        return packageName == null || packageName.isBlank() ? simpleClassName : packageName + "." + simpleClassName;
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

    private static final class RuntimeParserBinding implements AutoCloseable {
        private final ClassLoader classLoader;
        private final String lexerClassName;
        private final String parserClassName;
        private final Path workspace;

        private RuntimeParserBinding(
                final ClassLoader classLoader,
                final String lexerClassName,
                final String parserClassName,
                final Path workspace) {
            this.classLoader = classLoader;
            this.lexerClassName = lexerClassName;
            this.parserClassName = parserClassName;
            this.workspace = workspace;
        }

        private ClassLoader classLoader() {
            return classLoader;
        }

        private String lexerClassName() {
            return lexerClassName;
        }

        private String parserClassName() {
            return parserClassName;
        }

        @Override
        public void close() {
            if (classLoader instanceof URLClassLoader closable) {
                try {
                    closable.close();
                } catch (IOException ignored) {
                    // Best effort close.
                }
            }
            if (workspace != null) {
                deleteRecursively(workspace);
            }
        }
    }

    private static void deleteRecursively(final Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Best effort cleanup.
                        }
                    });
        } catch (IOException ignored) {
            // Best effort cleanup.
        }
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

