package name.jurgenei.gradle.antlr;

import name.jurgenei.gradle.antlr.catalog.GrammarCatalogEntry;
import name.jurgenei.gradle.antlr.catalog.GrammarCatalogLoader;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import javax.inject.Inject;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.PathMatcher;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Legacy Gradle task model for converting source trees to XML AST output.
 *
 * <p>This type is kept for compatibility with existing builds. New build scripts should
 * prefer {@link XmlAstGradleTask}, which exposes a more idiomatic Gradle property model.
 * Parsing is executed through dynamically loaded ANTLR lexer/parser classes.</p>
 */
public abstract class XmlAstTask extends DefaultTask {

    private final ConfigurableFileCollection sourceTrees;
    private final DirectoryProperty destinationDirectory;
    private final Property<String> grammar;
    private final Property<String> targetExtension;
    private final Property<Integer> parallelism;
    private final Property<String> executionModel;
    private final ListProperty<String> includes;
    private final RegularFileProperty catalogFile;
    private final Property<String> catalogGrammar;
    private final Property<String> parserClassName;
    private final Property<String> lexerClassName;
    private final Property<String> startRule;
    private final Property<Boolean> compression;
    private final ConfigurableFileCollection runtimeClasspath;

    @Inject
    public XmlAstTask(final ObjectFactory objects) {
        sourceTrees = objects.fileCollection();
        destinationDirectory = objects.directoryProperty();
        grammar = objects.property(String.class).convention("oracle");
        targetExtension = objects.property(String.class).convention(".xml");
        parallelism = objects.property(Integer.class).convention(1);
        executionModel = objects.property(String.class).convention("SEQUENTIAL");
        includes = objects.listProperty(String.class).convention(List.of("**/*.sql"));
        catalogFile = objects.fileProperty();
        catalogGrammar = objects.property(String.class);
        parserClassName = objects.property(String.class);
        lexerClassName = objects.property(String.class);
        startRule = objects.property(String.class).convention("script");
        compression = objects.property(Boolean.class).convention(false);
        runtimeClasspath = objects.fileCollection();
        destinationDirectory.convention(getProject().getLayout().getProjectDirectory().dir("target/xmlast"));
    }

    /**
     * Source tree roots scanned for input files.
     *
     * @return source tree file collection.
     */
    @InputFiles
    public ConfigurableFileCollection getSourceTrees() {
        return sourceTrees;
    }

    /**
     * Destination directory for generated XML AST files.
     *
     * @return output directory property.
     */
    @OutputDirectory
    public DirectoryProperty getDestinationDirectory() {
        return destinationDirectory;
    }

    /**
     * Runtime grammar key for legacy converter mode.
     *
     * @return grammar property.
     */
    @Input
    public Property<String> getGrammar() {
        return grammar;
    }

    /**
     * Target extension used when mapping source files to output files.
     *
     * @return output extension property.
     */
    @Input
    public Property<String> getTargetExtension() {
        return targetExtension;
    }

    /**
     * Parallelism setting for legacy converter execution.
     *
     * @return parallel worker count property.
     */
    @Input
    public Property<Integer> getParallelism() {
        return parallelism;
    }

    /**
     * Execution model for legacy converter mode.
     *
     * @return execution model property.
     */
    @Input
    public Property<String> getExecutionModel() {
        return executionModel;
    }

    /**
     * Include patterns evaluated relative to each source root.
     *
     * @return include patterns property.
     */
    @Input
    public ListProperty<String> getIncludes() {
        return includes;
    }

    /**
     * Optional XML catalog used to resolve grammar metadata.
     *
     * @return catalog file property.
     */
    @Optional
    @InputFile
    public RegularFileProperty getCatalogFile() {
        return catalogFile;
    }

    /**
     * Grammar key to select from {@link #getCatalogFile()}.
     *
     * @return catalog grammar property.
     */
    @Optional
    @Input
    public Property<String> getCatalogGrammar() {
        return catalogGrammar;
    }

    /**
     * Optional fully-qualified parser class for dynamic ANTLR mode.
     *
     * @return parser class property.
     */
    @Optional
    @Input
    public Property<String> getParserClassName() {
        return parserClassName;
    }

    /**
     * Optional fully-qualified lexer class for dynamic ANTLR mode.
     *
     * @return lexer class property.
     */
    @Optional
    @Input
    public Property<String> getLexerClassName() {
        return lexerClassName;
    }

    /**
     * Parser entry rule used in dynamic ANTLR mode.
     *
     * @return parser entry rule property.
     */
    @Input
    public Property<String> getStartRule() {
        return startRule;
    }

    /**
     * Enables rule-chain compression for generated XML AST output.
     *
     * @return compression flag property.
     */
    @Input
    public Property<Boolean> getCompression() {
        return compression;
    }

    /**
     * Runtime classpath used to load converter/parser dependencies.
     *
     * @return runtime classpath file collection.
     */
    @Classpath
    public ConfigurableFileCollection getRuntimeClasspath() {
        return runtimeClasspath;
    }

    /**
     * Executes conversion for all configured source trees.
     *
     * <p>Each source root is scanned using include patterns and converted while
     * preserving relative output paths.</p>
     */
    @TaskAction
    public void convertSqlTrees() {
        if (sourceTrees.isEmpty()) {
            throw new GradleException("xmlast requires at least one source tree");
        }

        final Set<File> roots = sourceTrees.getFiles();
        if (roots.isEmpty()) {
            throw new GradleException("xmlast sourceTrees resolved to no directories");
        }

        final File destinationDir = destinationDirectory.get().getAsFile();
        final List<String> includePatterns = includes.get();
        final ResolvedParserConfig resolvedConfig = resolveEffectiveConfig();

        try (URLClassLoader classLoader = createRuntimeClassLoader()) {
            final DynamicAntlrXmlAstConverter converter = new DynamicAntlrXmlAstConverter();

            for (File root : roots) {
                if (!root.isDirectory()) {
                    continue;
                }
                final List<File> files = collectMatchingFiles(root.toPath(), includePatterns);

                if (!files.isEmpty()) {
                    converter.convertFileTree(
                            root,
                            files,
                            destinationDir,
                            targetExtension.get(),
                            classLoader,
                            resolvedConfig.lexerClassName(),
                            resolvedConfig.parserClassName(),
                            resolvedConfig.startRule(),
                            compression.get());
                }
            }
        } catch (Exception ex) {
            throw new GradleException("xmlast conversion failed", ex);
        }
    }

    private ResolvedParserConfig resolveEffectiveConfig() {
        String resolvedParser = parserClassName.getOrNull();
        String resolvedLexer = lexerClassName.getOrNull();
        String resolvedStartRule = startRule.get();

        if (!catalogFile.isPresent()) {
            if (resolvedParser == null || resolvedParser.isBlank() || resolvedLexer == null || resolvedLexer.isBlank()) {
                throw new GradleException("parserClassName and lexerClassName are required when catalogFile is not configured");
            }
            return new ResolvedParserConfig(resolvedParser, resolvedLexer, resolvedStartRule);
        }

        if (!catalogGrammar.isPresent() || catalogGrammar.get().isBlank()) {
            throw new GradleException("catalogGrammar is required when catalogFile is configured");
        }

        final GrammarCatalogLoader loader = new GrammarCatalogLoader();
        final GrammarCatalogEntry entry = loader.load(catalogFile.get().getAsFile()).require(catalogGrammar.get());
        if (resolvedParser == null || resolvedParser.isBlank()) {
            resolvedParser = entry.getParser();
        }
        if (resolvedLexer == null || resolvedLexer.isBlank()) {
            resolvedLexer = entry.getLexer();
        }
        resolvedStartRule = entry.getStartRule();

        getLogger().info(
                "Resolved catalog grammar '{}' (runtimeGrammar='{}') -> parser={}, lexer={}, startRule={}",
                entry.getName(),
                entry.resolveRuntimeGrammar(),
                resolvedParser,
                resolvedLexer,
                resolvedStartRule);
        return new ResolvedParserConfig(resolvedParser, resolvedLexer, resolvedStartRule);
    }

    private record ResolvedParserConfig(String parserClassName, String lexerClassName, String startRule) {
    }

    private record ResolvedCatalogConfig(String runtimeGrammar, String startRule) {
    }

    private URLClassLoader createRuntimeClassLoader() throws Exception {
        final URL[] runtimeUrls = runtimeClasspath.getFiles().stream()
                .map(File::toPath)
                .map(Path::toUri)
                .map(uri -> {
                    try {
                        return uri.toURL();
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                })
                .toArray(URL[]::new);

        return new URLClassLoader(runtimeUrls, getClass().getClassLoader());
    }

    private List<File> collectMatchingFiles(final Path root, final List<String> includePatterns) {
        final List<PathMatcher> matchers = includePatterns.stream()
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .collect(Collectors.toCollection(ArrayList::new));

        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> matchesAny(root.relativize(path), matchers))
                    .map(Path::toFile)
                    .sorted(Comparator.comparing(File::getAbsolutePath))
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (Exception ex) {
            throw new GradleException("Failed scanning source tree " + root, ex);
        }
    }

    private boolean matchesAny(final Path relativePath, final List<PathMatcher> matchers) {
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(relativePath)) {
                return true;
            }
        }
        return false;
    }
}

