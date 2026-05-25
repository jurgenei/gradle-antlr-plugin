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
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;

import javax.inject.Inject;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Native Gradle-style task with explicit source/destination properties.
 *
 * <p>This task is intended as the primary Gradle-facing API. It supports include/exclude
 * patterns, incremental conversion checks, configurable execution model, and explicit
 * runtime classpath injection for configuration-cache-friendly execution. Parsing is executed
 * through dynamically loaded ANTLR lexer/parser classes.</p>
 */
public abstract class XmlAstGradleTask extends DefaultTask {

    private final DirectoryProperty sourceDirectory;
    private final DirectoryProperty destinationDirectory;
    private final Property<String> grammar;
    private final Property<String> targetExtension;
    private final Property<Integer> parallelism;
    private final Property<String> executionModel;
    private final ListProperty<String> includes;
    private final ListProperty<String> excludes;
    private final Property<Boolean> force;
    private final Property<Boolean> failOnError;
    private final Property<Boolean> failOnTransformationError;
    private final RegularFileProperty catalogFile;
    private final Property<String> catalogGrammar;
    private final Property<String> parserClassName;
    private final Property<String> lexerClassName;
    private final Property<String> startRule;
    private final Property<Boolean> compression;
    private final ConfigurableFileCollection runtimeClasspath;

    @Inject
    public XmlAstGradleTask(final ObjectFactory objects) {
        sourceDirectory = objects.directoryProperty();
        destinationDirectory = objects.directoryProperty();
        grammar = objects.property(String.class).convention("oracle");
        targetExtension = objects.property(String.class).convention(".xml");
        parallelism = objects.property(Integer.class).convention(1);
        executionModel = objects.property(String.class).convention("SEQUENTIAL");
        includes = objects.listProperty(String.class).convention(List.of("**/*.sql"));
        excludes = objects.listProperty(String.class).convention(List.of());
        force = objects.property(Boolean.class).convention(false);
        failOnError = objects.property(Boolean.class).convention(true);
        failOnTransformationError = objects.property(Boolean.class).convention(true);
        catalogFile = objects.fileProperty();
        catalogGrammar = objects.property(String.class);
        parserClassName = objects.property(String.class);
        lexerClassName = objects.property(String.class);
        startRule = objects.property(String.class).convention("script");
        compression = objects.property(Boolean.class).convention(false);
        runtimeClasspath = objects.fileCollection();

        sourceDirectory.convention(getProject().getLayout().getProjectDirectory().dir("src/main/sql"));
        destinationDirectory.convention(getProject().getLayout().getProjectDirectory().dir("target/xmlast"));
    }

    /**
     * Source directory scanned for input files.
     *
     * @return directory property containing source files.
     */
    @InputDirectory
    public DirectoryProperty getSourceDirectory() {
        return sourceDirectory;
    }

    /**
     * Destination directory receiving generated XML files.
     *
     * @return output directory property.
     */
    @OutputDirectory
    public DirectoryProperty getDestinationDirectory() {
        return destinationDirectory;
    }

    /**
     * Grammar key used by {@code SqlXmlConverter} (e.g. oracle, sybase, java8, antlr4).
     *
     * <p>Retained for compatibility with existing builds and catalog metadata.</p>
     *
     * @return grammar property.
     */
    @Input
    public Property<String> getGrammar() {
        return grammar;
    }

    /**
     * Output file extension mapped from source files.
     *
     * @return target extension property.
     */
    @Input
    public Property<String> getTargetExtension() {
        return targetExtension;
    }

    /**
     * Max worker count setting retained for compatibility.
     *
     * @return parallelism property.
     */
    @Input
    public Property<Integer> getParallelism() {
        return parallelism;
    }

    /**
     * Execution model setting retained for compatibility.
     *
     * @return execution model property.
     */
    @Input
    public Property<String> getExecutionModel() {
        return executionModel;
    }

    /**
     * Ant-style include patterns evaluated relative to {@link #getSourceDirectory()}.
     *
     * @return include patterns property.
     */
    @Input
    public ListProperty<String> getIncludes() {
        return includes;
    }

    /**
     * Ant-style exclude patterns evaluated relative to {@link #getSourceDirectory()}.
     *
     * @return exclude patterns property.
     */
    @Input
    public ListProperty<String> getExcludes() {
        return excludes;
    }

    /**
     * Forces conversion even when output files are newer than inputs.
     *
     * @return force flag property.
     */
    @Input
    public Property<Boolean> getForce() {
        return force;
    }

    /**
     * Global fail-fast switch aligned with Ant task semantics.
     *
     * @return fail-on-error property.
     */
    @Input
    public Property<Boolean> getFailOnError() {
        return failOnError;
    }

    /**
     * Transformation-specific failure switch aligned with Ant task semantics.
     *
     * @return fail-on-transformation-error property.
     */
    @Input
    public Property<Boolean> getFailOnTransformationError() {
        return failOnTransformationError;
    }

    /**
     * Optional XML catalog containing named grammars and parser/lexer coordinates.
     *
     * @return catalog file property.
     */
    @Optional
    @InputFile
    public RegularFileProperty getCatalogFile() {
        return catalogFile;
    }

    /**
     * Grammar name to resolve from {@link #getCatalogFile()}.
     *
     * @return catalog grammar key property.
     */
    @Optional
    @Input
    public Property<String> getCatalogGrammar() {
        return catalogGrammar;
    }

    /**
     * Optional parser coordinate used for dynamic ANTLR parsing.
     *
     * <p>Supported values:</p>
     * <ul>
     *   <li>fully-qualified parser class name</li>
     *   <li>grammar source coordinate (`.g4`) as local path, file URI, HTTP(S) URL,
     *       protocol-relative URL, or host/path without protocol</li>
     * </ul>
     *
     * @return parser class property.
     */
    @Optional
    @Input
    public Property<String> getParserClassName() {
        return parserClassName;
    }

    /**
     * Optional lexer coordinate used for dynamic ANTLR parsing.
     *
     * <p>Supported values mirror {@link #getParserClassName()}.</p>
     *
     * @return lexer class property.
     */
    @Optional
    @Input
    public Property<String> getLexerClassName() {
        return lexerClassName;
    }

    /**
     * Parser entry rule method invoked during dynamic parsing.
     *
     * @return start rule property.
     */
    @Input
    public Property<String> getStartRule() {
        return startRule;
    }

    /**
     * Enables rule-chain compression for generated XML AST output.
     *
     * <p>When enabled, single-child `<rule>` chains of length {@code >= 2} are flattened,
     * the chain head receives a `pathId` attribute, and a `<pathIndex>` section is appended
     * to the AST root with `id -> path` mappings.</p>
     *
     * @return compression flag property.
     */
    @Input
    public Property<Boolean> getCompression() {
        return compression;
    }

    /**
     * Runtime classpath used to load converter classes and dependencies.
     *
     * @return classpath file collection.
     */
    @Classpath
    public ConfigurableFileCollection getRuntimeClasspath() {
        return runtimeClasspath;
    }

    /**
     * Runs conversion for all selected files from source directory to destination directory.
     *
     * <p>When {@code force=false}, files are converted only if missing or out-of-date.</p>
     */
    @TaskAction
    public void convert() {
        final File sourceDir = sourceDirectory.get().getAsFile();
        if (!sourceDir.isDirectory()) {
            throw new GradleException("sourceDirectory must be an existing directory: " + sourceDir);
        }

        final List<File> selectedFiles = selectSourceFiles(sourceDir.toPath(), includes.get(), excludes.get());
        final File destinationDir = destinationDirectory.get().getAsFile();
        final List<File> jobs = applyUpToDateCheck(sourceDir.toPath(), destinationDir.toPath(), selectedFiles);
        final ResolvedParserConfig resolvedConfig = resolveEffectiveConfig();

        if (jobs.isEmpty()) {
            getLogger().info("No files selected for conversion in {}", sourceDir);
            return;
        }

        final List<String> failures = new ArrayList<>();

        try (URLClassLoader classLoader = createRuntimeClassLoader()) {
            new DynamicAntlrXmlAstConverter().convertFileTree(
                    sourceDir,
                    jobs,
                    destinationDir,
                    targetExtension.get(),
                    classLoader,
                    resolvedConfig.lexerClassName(),
                    resolvedConfig.parserClassName(),
                    resolvedConfig.startRule(),
                    compression.get());
        } catch (Exception ex) {
            final String message = "xmlast conversion failed: " + ex.getMessage();
            if (failOnError.get() && failOnTransformationError.get()) {
                throw new GradleException(message, ex);
            }
            failures.add(message);
            getLogger().warn(message, ex);
        }

        if (!failures.isEmpty() && failOnError.get()) {
            throw new GradleException(String.join(System.lineSeparator(), failures));
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
            resolvedParser = resolveCatalogCoordinate(entry.getParser(), true);
        }
        if (resolvedLexer == null || resolvedLexer.isBlank()) {
            resolvedLexer = resolveCatalogCoordinate(entry.getLexer(), false);
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

    private String resolveCatalogCoordinate(final String value, final boolean parser) {
        if (!looksLikeGrammarCoordinate(value)) {
            return value;
        }
        final Path baseDirectory = catalogFile.get().getAsFile().toPath().toAbsolutePath().getParent();
        final GrammarCatalogLoader loader = new GrammarCatalogLoader();
        final GrammarCatalogEntry entry = loader.load(catalogFile.get().getAsFile()).require(catalogGrammar.get());
        return (parser ? entry.resolveParserUri(baseDirectory) : entry.resolveLexerUri(baseDirectory)).toString();
    }

    private boolean looksLikeGrammarCoordinate(final String value) {
        final String trimmed = value.trim();
        return trimmed.endsWith(".g4")
                || trimmed.startsWith("http://")
                || trimmed.startsWith("https://")
                || trimmed.startsWith("file:")
                || trimmed.startsWith("//")
                || trimmed.contains("/");
    }

    private List<File> selectSourceFiles(
            final Path sourceDir,
            final List<String> includePatterns,
            final List<String> excludePatterns) {
        final List<PathMatcher> includeMatchers = toMatchers(includePatterns);
        final List<PathMatcher> excludeMatchers = toMatchers(excludePatterns);

        try (Stream<Path> stream = Files.walk(sourceDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(sourceDir::relativize)
                    .filter(path -> matchesAny(path, includeMatchers))
                    .filter(path -> !matchesAny(path, excludeMatchers))
                    .map(sourceDir::resolve)
                    .map(Path::toFile)
                    .sorted(Comparator.comparing(File::getAbsolutePath))
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (Exception ex) {
            throw new GradleException("Failed scanning source directory " + sourceDir, ex);
        }
    }

    private List<File> applyUpToDateCheck(
            final Path sourceRoot,
            final Path destinationRoot,
            final List<File> selectedFiles) {
        if (force.get()) {
            return selectedFiles;
        }

        final List<File> toConvert = new ArrayList<>();
        for (File sourceFile : selectedFiles) {
            final Path relativePath = sourceRoot.relativize(sourceFile.toPath());
            final File targetFile = destinationRoot.resolve(mapTarget(relativePath.toString())).toFile();
            if (!targetFile.exists() || sourceFile.lastModified() >= targetFile.lastModified() || targetFile.length() == 0L) {
                toConvert.add(sourceFile);
            }
        }
        return toConvert;
    }

    private String mapTarget(final String relativePath) {
        final int dotPos = relativePath.lastIndexOf('.');
        final String baseName = dotPos > 0 ? relativePath.substring(0, dotPos) : relativePath;
        return baseName + targetExtension.get();
    }

    private URLClassLoader createRuntimeClassLoader() {
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

    private List<PathMatcher> toMatchers(final List<String> patterns) {
        final List<PathMatcher> matchers = new ArrayList<>();
        for (String pattern : patterns) {
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
            if (pattern.startsWith("**/")) {
                // Keep Ant-like behavior where **/*.ext also matches files directly in sourceDirectory.
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3)));
            }
        }
        return matchers;
    }

    private boolean matchesAny(final Path relativePath, final List<PathMatcher> matchers) {
        if (matchers.isEmpty()) {
            return false;
        }
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(relativePath)) {
                return true;
            }
        }
        return false;
    }
}

