package name.jurgenei.gradle.antlr;

import name.jurgenei.gradle.antlr.catalog.GrammarCatalogEntry;
import name.jurgenei.gradle.antlr.catalog.GrammarCatalogLoader;
import name.jurgenei.gradle.antlr.constants.GrammarConstants;
import name.jurgenei.gradle.antlr.constants.TimeConstants;
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
import java.util.Locale;
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
    private final String projectDirPath;
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
    private final Property<Boolean> continueOnError;
    private final Property<Boolean> suppressStackTrace;
    private final RegularFileProperty catalogFile;
    private final Property<String> catalogGrammar;
    private final Property<String> parserClassName;
    private final Property<String> lexerClassName;
    private final Property<String> startRule;
    private final Property<Boolean> compression;
    private final Property<Boolean> enableDFAMonitoring;
    private final ConfigurableFileCollection runtimeClasspath;

    @Inject
    public XmlAstGradleTask(final ObjectFactory objects) {
        this.projectDirPath = getProject().getProjectDir().getAbsolutePath();
        sourceDirectory = objects.directoryProperty();
        destinationDirectory = objects.directoryProperty();
        grammar = objects.property(String.class).convention(GrammarConstants.DEFAULT_GRAMMAR);
        targetExtension = objects.property(String.class).convention(GrammarConstants.DEFAULT_FILE_EXTENSION);
        parallelism = objects.property(Integer.class).convention(GrammarConstants.DEFAULT_PARALLELISM);
        executionModel = objects.property(String.class).convention(GrammarConstants.EXECUTION_MODEL_SEQUENTIAL);
        includes = objects.listProperty(String.class).convention(List.of(GrammarConstants.DEFAULT_INCLUDE_PATTERN));
        excludes = objects.listProperty(String.class).convention(List.of());
        force = objects.property(Boolean.class).convention(false);
        failOnError = objects.property(Boolean.class).convention(true);
        failOnTransformationError = objects.property(Boolean.class).convention(true);
        continueOnError = objects.property(Boolean.class).convention(true);
        suppressStackTrace = objects.property(Boolean.class).convention(false);
        catalogFile = objects.fileProperty();
        catalogGrammar = objects.property(String.class);
        parserClassName = objects.property(String.class);
        lexerClassName = objects.property(String.class);
        startRule = objects.property(String.class).convention(GrammarConstants.DEFAULT_START_RULE);
        compression = objects.property(Boolean.class).convention(false);
        enableDFAMonitoring = objects.property(Boolean.class).convention(false);
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
     * Continue processing remaining files after per-file parse failures.
     *
     * @return continue-on-error property.
     */
    @Input
    public Property<Boolean> getContinueOnError() {
        return continueOnError;
    }

    /**
     * Suppresses task failure stack traces and emits concise lifecycle diagnostics instead.
     *
     * @return suppress-stack-trace property.
     */
    @Input
    public Property<Boolean> getSuppressStackTrace() {
        return suppressStackTrace;
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
     * Enables DFA memory monitoring and logging (requires --info or --debug).
     *
     * <p>When enabled, logs DFA clearing operations and memory heap statistics
     * after each file is parsed (both success and failure cases).</p>
     *
     * @return DFA monitoring flag property.
     */
    @Input
    public Property<Boolean> getEnableDFAMonitoring() {
        return enableDFAMonitoring;
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
        final long runStartNanos = System.nanoTime();
        final File sourceDir = sourceDirectory.get().getAsFile();

        // Guard: Directory validation
        if (!sourceDir.isDirectory()) {
            throw new GradleException("sourceDirectory must be an existing directory: " + sourceDir);
        }

        final File destinationDir = destinationDirectory.get().getAsFile();
        if (!destinationDir.getParentFile().isDirectory()) {
            throw new GradleException("destinationDirectory parent must be an existing directory: "
                    + destinationDir.getParent());
        }

        // Validate and resolve all configurations upfront
        final ResolvedParserConfig resolvedConfig = resolveEffectiveConfig();
        final String extension = validateAndGetExtension();
        final int parallelismValue = validateAndGetParallelism();
        final String executionModelValue = validateAndGetExecutionModel();

        // Select and filter files for processing
        final List<File> selectedFiles = selectSourceFiles(sourceDir.toPath(), includes.get(), excludes.get());
        final List<File> jobs = applyUpToDateCheck(sourceDir.toPath(), destinationDir.toPath(), selectedFiles);

        if (jobs.isEmpty()) {
            getLogger().info("No files selected for conversion in {}", sourceDir);
            logIndentedSummary(new DynamicAntlrXmlAstConverter.ConversionStats(0, 0, 0, 0), executionModel.get(), parallelism.get());
            return;
        }

        // Execute conversion and handle results
        performConversion(sourceDir, destinationDir, resolvedConfig, extension, parallelismValue, 
                         executionModelValue, jobs, runStartNanos);
    }

    /**
     * Validates configuration parameters and executes the conversion process.
     * Handles both success and failure paths with proper cleanup and logging.
     */
    private void performConversion(
            final File sourceDir,
            final File destinationDir,
            final ResolvedParserConfig resolvedConfig,
            final String extension,
            final int parallelismValue,
            final String executionModelValue,
            final List<File> jobs,
            final long runStartNanos) {
        final List<String> failures = new ArrayList<>();
        DynamicAntlrXmlAstConverter.ConversionStats conversionStats = null;
        int fallbackFilesWithErrors = 0;

        if (enableDFAMonitoring.get()) {
            getLogger().info("DFA memory monitoring enabled for {} file(s)", jobs.size());
            logHeapMemory("Initial state");
        }

        try (URLClassLoader classLoader = createRuntimeClassLoader()) {
            conversionStats = new DynamicAntlrXmlAstConverter().convertFileTreeWithStats(
                    sourceDir,
                    jobs,
                    destinationDir,
                    extension,
                    classLoader,
                    resolvedConfig.lexerClassName(),
                    resolvedConfig.parserClassName(),
                    resolvedConfig.startRule(),
                    compression.get(),
                    continueOnError.get(),
                    executionModelValue,
                    parallelismValue);
        } catch (Exception ex) {
            conversionStats = handleConversionException(ex, failures, jobs, runStartNanos);
            fallbackFilesWithErrors = conversionStats == null ? findParseFailureMessages(ex).size() : 0;
        } finally {
            if (conversionStats == null) {
                conversionStats = new DynamicAntlrXmlAstConverter.ConversionStats(
                        jobs.size(),
                        fallbackFilesWithErrors,
                        System.nanoTime() - runStartNanos,
                        0L);
            }
            logIndentedSummary(conversionStats, executionModel.get(), parallelism.get());
            if (enableDFAMonitoring.get()) {
                logHeapMemory("After conversion");
            }
        }

        if (!failures.isEmpty() && failOnError.get()) {
            throw new GradleException(String.join(System.lineSeparator(), failures));
        }
    }

    /**
     * Handles exceptions during conversion, extracting stats when available and logging appropriately.
     */
    private DynamicAntlrXmlAstConverter.ConversionStats handleConversionException(
            final Exception ex,
            final List<String> failures,
            final List<File> jobs,
            final long runStartNanos) {
        final DynamicAntlrXmlAstConverter.ConversionStats extractedStats = findConversionStats(ex);
        DynamicAntlrXmlAstConverter.ConversionStats result = extractedStats;
        
        final String message = "xmlast conversion failed: " + ex.getMessage();
        logLifecycleFailure(ex);
        
        if (failOnError.get() && failOnTransformationError.get()) {
            if (suppressStackTrace.get()) {
                throw new GradleException(message);
            }
            throw new GradleException(message, ex);
        }
        
        failures.add(message);
        if (suppressStackTrace.get()) {
            getLogger().warn(message);
        } else {
            getLogger().warn(message, ex);
        }
        
        return result;
    }

    /**
     * Validates the file extension configuration and returns the value.
     */
    private String validateAndGetExtension() {
        String extension = targetExtension.get();
        if (extension == null || extension.isBlank()) {
            throw new GradleException("targetExtension is not configured");
        }
        return extension;
    }

    /**
     * Validates the parallelism configuration and returns the value.
     */
    private int validateAndGetParallelism() {
        int parallelismValue = parallelism.get();
        if (parallelismValue < 1) {
            throw new GradleException("parallelism must be >= 1, got: " + parallelismValue);
        }
        return parallelismValue;
    }

    /**
     * Validates the execution model configuration and returns the value.
     */
    private String validateAndGetExecutionModel() {
        String executionModelValue = executionModel.get();
        if (executionModelValue != null && !executionModelValue.isBlank()) {
            final String upperModel = executionModelValue.trim().toUpperCase();
            if (!upperModel.equals(GrammarConstants.EXECUTION_MODEL_SEQUENTIAL)
                    && !upperModel.equals(GrammarConstants.EXECUTION_MODEL_PLATFORM_THREADS)
                    && !upperModel.equals(GrammarConstants.EXECUTION_MODEL_VIRTUAL_THREADS)) {
                throw new GradleException(
                    "Invalid executionModel: '" + executionModelValue + "'. " +
                    "Expected one of: SEQUENTIAL, PLATFORM_THREADS, VIRTUAL_THREADS");
            }
        }
        return executionModelValue;
    }

    private void logHeapMemory(final String label) {
        final Runtime runtime = Runtime.getRuntime();
        final long maxMemory = runtime.maxMemory();
        final long totalMemory = runtime.totalMemory();
        final long freeMemory = runtime.freeMemory();
        final long usedMemory = totalMemory - freeMemory;
        final long usedPercent = (usedMemory * 100) / maxMemory;
        getLogger().info(
                "Heap [{}]: used={} MB ({} %), total={} MB, max={} MB",
                label,
                usedMemory / (1024 * 1024),
                usedPercent,
                totalMemory / (1024 * 1024),
                maxMemory / (1024 * 1024));
    }

    private DynamicAntlrXmlAstConverter.ConversionStats findConversionStats(final Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof DynamicAntlrXmlAstConverter.ConversionFailedException conversionFailure) {
                return conversionFailure.getStats();
            }
            current = current.getCause();
        }
        return null;
    }

    private void logIndentedSummary(
            final DynamicAntlrXmlAstConverter.ConversionStats stats,
            final String executionModelName,
            final int configuredParallelism) {
        final int processed = stats.processedFiles();
        final int withErrors = Math.max(0, stats.filesWithErrors());
        final int withoutErrors = Math.max(0, processed - withErrors);
        final double successRate = processed == 0 ? 100.0 : (withoutErrors * 100.0) / processed;
        final long totalNanos = Math.max(0L, stats.totalDurationNanos());
        final long averageNanos = processed == 0 ? 0L : totalNanos / processed;
        final int normalizedParallelism = Math.max(1, configuredParallelism);
        final long cumulativeFileNanos = Math.max(0L, stats.cumulativeFileProcessingNanos());
        final double estimatedSequentialNanos = (double) cumulativeFileNanos;
        final double speedupFactor = totalNanos == 0
                ? 1.0
                : Math.max(0.0, estimatedSequentialNanos / (double) totalNanos);

        final List<String> lines = List.of(
                "XML AST Conversion Summary",
                String.format(Locale.ROOT, "  Files processed            : %d", processed),
                String.format(Locale.ROOT, "  Files with errors          : %d", withErrors),
                String.format(Locale.ROOT, "  Files with no errors       : %.2f%%", successRate),
                String.format(Locale.ROOT, "  Execution profile          : %s / %d / %.2fx", executionModelName, normalizedParallelism, speedupFactor),
                String.format(Locale.ROOT, "  Estimated sequential time  : %s", formatHhMmSs(cumulativeFileNanos)),
                String.format(Locale.ROOT, "  Total processing time      : %s", formatHhMmSs(totalNanos)),
                String.format(Locale.ROOT, "  Average time per file      : %s (%s ms)", formatHhMmSs(averageNanos), String.format(Locale.ROOT, "%.2f", averageNanos / 1_000_000.0))
        );
        logBoxedSummary(lines);
    }

    private void logBoxedSummary(final List<String> lines) {
        final int contentWidth = lines.stream().mapToInt(String::length).max().orElse(0);
        final String border = "+" + "-".repeat(contentWidth + 2) + "+";
        getLogger().lifecycle(border);
        for (String line : lines) {
            final int padding = Math.max(0, contentWidth - line.length());
            getLogger().lifecycle("| " + line + " ".repeat(padding) + " |");
        }
        getLogger().lifecycle(border);
    }

    private String formatHhMmSs(final long durationNanos) {
        final long totalSeconds = durationNanos / TimeConstants.NANOS_PER_SECOND;
        final long[] components = TimeConstants.toHourMinuteSecond(totalSeconds);
        return String.format("%02d:%02d:%02d", components[0], components[1], components[2]);
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
        return trimmed.endsWith(GrammarConstants.GRAMMAR_FILE_EXTENSION)
                || trimmed.startsWith(GrammarConstants.SCHEME_HTTP)
                || trimmed.startsWith(GrammarConstants.SCHEME_HTTPS)
                || trimmed.startsWith(GrammarConstants.SCHEME_FILE)
                || trimmed.startsWith(GrammarConstants.SCHEME_PROTOCOL_LESS)
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

    private void logLifecycleFailure(final Throwable throwable) {
        final List<String> parseMessages = findParseFailureMessages(throwable);
        if (!parseMessages.isEmpty()) {
            for (String parseMessage : parseMessages) {
                logParseDiagnostics(parseMessage);
            }
            return;
        }
        final String message = firstNonBlankMessage(throwable);
        if (message != null) {
            getLogger().lifecycle("xmlast failure: {}", message);
        }
    }

    private List<String> findParseFailureMessages(final Throwable throwable) {
        final List<String> messages = new ArrayList<>();
        Throwable current = throwable;
        while (current != null) {
            final String message = current.getMessage();
            if (message != null) {
                final String[] fragments = message.split("\\s*\\|\\|\\s*");
                for (String fragment : fragments) {
                    if (fragment.startsWith("Parse failed for ")) {
                        messages.add(fragment);
                    }
                }
            }
            current = current.getCause();
        }
        return messages;
    }

    private String firstNonBlankMessage(final Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            final String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                return message;
            }
            current = current.getCause();
        }
        return null;
    }

    private void logParseDiagnostics(final String parseMessage) {
        final String prefix = "Parse failed for ";
        final int detailSeparator = parseMessage.indexOf(": ");
        final String source = detailSeparator > prefix.length()
                ? parseMessage.substring(prefix.length(), detailSeparator)
                : "<unknown>";
        final String diagnostics = detailSeparator > -1
                ? parseMessage.substring(detailSeparator + 2)
                : parseMessage;
        final String[] entries = diagnostics.split(" \\| ");
        for (String entry : entries) {
            if (!entry.isBlank()) {
                getLogger().lifecycle("{} {}", makeRelativePath(source), sanitizeDiagnostic(entry));
            }
        }
    }

    private String makeRelativePath(final String absolutePath) {
        try {
            final Path projectDir = new File(projectDirPath).toPath();
            final Path filePath = new File(absolutePath).toPath();
            return projectDir.relativize(filePath).toString();
        } catch (IllegalArgumentException | NullPointerException e) {
            return absolutePath;
        }
    }

    private String sanitizeDiagnostic(final String diagnostic) {
        final String flattened = diagnostic.replace('\n', ' ').replace('\r', ' ');
        final int expectingPos = flattened.indexOf(" expecting ");
        final String compact = expectingPos > -1
                ? flattened.substring(0, expectingPos) + " expecting <token-set>"
                : flattened;
        final int max = 280;
        if (compact.length() <= max) {
            return compact;
        }
        return compact.substring(0, max - 3) + "...";
    }
}

