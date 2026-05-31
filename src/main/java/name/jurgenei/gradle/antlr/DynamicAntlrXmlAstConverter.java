package name.jurgenei.gradle.antlr;

import name.jurgenei.gradle.antlr.constants.GrammarConstants;
import name.jurgenei.gradle.antlr.constants.TimeConstants;
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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.function.Consumer;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
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
    private static final int MAX_OUTCOME_LOG_LINE_LENGTH = 225;
    private static final String LOG_TRUNCATION_SUFFIX = "...";
    private static final String GC_ENABLED_PROPERTY = "xmlast.gc.enabled";
    private static final String GC_EVERY_FILES_PROPERTY = "xmlast.gc.every.files";
    private static final String GC_HEAP_THRESHOLD_PERCENT_PROPERTY = "xmlast.gc.heap.threshold.percent";

    private final AtomicInteger completedFilesCounter = new AtomicInteger();

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
     * @param continueOnError when true, keeps converting remaining files and aggregates failures.
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
            final boolean compression,
            final boolean continueOnError,
            final Consumer<String> outcomeLogger) {
        // Guard: Null checks
        java.util.Objects.requireNonNull(sourceRoot, "sourceRoot cannot be null");
        java.util.Objects.requireNonNull(sourceFiles, "sourceFiles cannot be null");
        java.util.Objects.requireNonNull(destinationRoot, "destinationRoot cannot be null");
        java.util.Objects.requireNonNull(targetExtension, "targetExtension cannot be null");
        java.util.Objects.requireNonNull(classLoader, "classLoader cannot be null");
        java.util.Objects.requireNonNull(lexerClassName, "lexerClassName cannot be null");
        java.util.Objects.requireNonNull(parserClassName, "parserClassName cannot be null");
        java.util.Objects.requireNonNull(startRule, "startRule cannot be null");

        // Guard: Empty/blank checks
        if (sourceFiles.isEmpty()) {
            throw new IllegalArgumentException("sourceFiles cannot be empty");
        }
        if (startRule.isBlank()) {
            throw new IllegalArgumentException("startRule cannot be blank (e.g., 'script')");
        }
        if (targetExtension.isBlank()) {
            throw new IllegalArgumentException("targetExtension cannot be blank (e.g., '.xml')");
        }
        if (lexerClassName.isBlank()) {
            throw new IllegalArgumentException("lexerClassName cannot be blank");
        }
        if (parserClassName.isBlank()) {
            throw new IllegalArgumentException("parserClassName cannot be blank");
        }

        // Guard: Directory checks
        if (!sourceRoot.isDirectory()) {
            throw new IllegalArgumentException("sourceRoot must be an existing directory: " + sourceRoot);
        }

        convertFileTreeWithStats(
                sourceRoot,
                sourceFiles,
                destinationRoot,
                targetExtension,
                classLoader,
                lexerClassName,
                parserClassName,
                startRule,
                compression,
                continueOnError,
                GrammarConstants.EXECUTION_MODEL_SEQUENTIAL,
                GrammarConstants.DEFAULT_PARALLELISM,
                outcomeLogger
                );
    }


    public ConversionStats convertFileTreeWithStats(
            final File sourceRoot,
            final List<File> sourceFiles,
            final File destinationRoot,
            final String targetExtension,
            final ClassLoader classLoader,
            final String lexerClassName,
            final String parserClassName,
            final String startRule,
            final boolean compression,
            final boolean continueOnError,
            final String executionModelName,
            final int configuredParallelism,
            final Consumer<String> outcomeLogger) {
        // Guard: Parallelism constraints
        if (configuredParallelism < 1) {
            throw new IllegalArgumentException("configuredParallelism must be >= 1, got: " + configuredParallelism);
        }

        final Consumer<String> safeOutcomeLogger = outcomeLogger == null ? System.out::println : outcomeLogger;

        // Guard: Execution model validation
        if (executionModelName != null && !executionModelName.isBlank()) {
            final String upperModel = executionModelName.trim().toUpperCase();
            if (!upperModel.equals(GrammarConstants.EXECUTION_MODEL_SEQUENTIAL)
                    && !upperModel.equals(GrammarConstants.EXECUTION_MODEL_PLATFORM_THREADS)
                    && !upperModel.equals(GrammarConstants.EXECUTION_MODEL_VIRTUAL_THREADS)) {
                throw new IllegalArgumentException(
                    "Invalid executionModelName: '" + executionModelName + "'. " +
                    "Expected one of: SEQUENTIAL, PLATFORM_THREADS, VIRTUAL_THREADS");
            }
        }

        final long runStartNanos = System.nanoTime();
        try {
            Files.createDirectories(destinationRoot.toPath());

            try (RuntimeParserBinding binding = prepareParserBinding(classLoader, lexerClassName, parserClassName)) {
                validateParserBinding(binding);
                final List<ConversionJob> jobs = buildConversionJobs(sourceRoot, sourceFiles, destinationRoot, targetExtension);
                
                final ExecutionModel executionModel = parseExecutionModel(executionModelName);
                final int workerLimit = executionModel == ExecutionModel.SEQUENTIAL
                        ? 1
                        : Math.max(1, configuredParallelism);
                final List<ConversionOutcome> outcomes = executeJobs(
                        jobs,
                        binding,
                        startRule,
                        compression,
                        continueOnError,
                        executionModel,
                        workerLimit,
                        safeOutcomeLogger);

                return processOutcomes(outcomes, runStartNanos);
            }
        } catch (ConversionFailedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new GradleException("Dynamic ANTLR conversion failed", ex);
        }
    }

    /**
     * Builds a list of conversion jobs for all input files.
     * Each job maps a source file to its output location with relative path preserved.
     */
    private List<ConversionJob> buildConversionJobs(
            final File sourceRoot,
            final List<File> sourceFiles,
            final File destinationRoot,
            final String targetExtension) throws IOException {
        final List<ConversionJob> jobs = new ArrayList<>();
        int index = 0;
        for (File sourceFile : sourceFiles) {
            final Path relative = sourceRoot.toPath().relativize(sourceFile.toPath());
            final Path output = destinationRoot.toPath().resolve(mapTarget(relative, targetExtension));
            Files.createDirectories(output.getParent());
            jobs.add(new ConversionJob(index++, sourceFile, relative, output));
        }
        return jobs;
    }

    /**
     * Processes completed conversion outcomes and aggregates results.
     * Separates successes from failures, prints outputs, and returns conversion statistics.
     */
    private ConversionStats processOutcomes(
            final List<ConversionOutcome> outcomes,
            final long runStartNanos) {
        final List<String> failures = new ArrayList<>();
        long cumulativeFileProcessingNanos = 0L;

        for (ConversionOutcome outcome : outcomes) {
            cumulativeFileProcessingNanos += outcome.durationNanos();
            if (!outcome.success()) {
                failures.add(outcome.failureMessage());
            }
        }

        final ConversionStats stats = new ConversionStats(
                outcomes.size(),
                failures.size(),
                System.nanoTime() - runStartNanos,
                cumulativeFileProcessingNanos);

        if (!failures.isEmpty()) {
            throw new ConversionFailedException(String.join(" || ", failures), stats);
        }
        return stats;
    }

    private List<ConversionOutcome> executeJobs(
            final List<ConversionJob> jobs,
            final RuntimeParserBinding binding,
            final String startRule,
            final boolean compression,
            final boolean continueOnError,
            final ExecutionModel executionModel,
            final int workerLimit,
            final Consumer<String> outcomeLogger) throws Exception {
        if (jobs.isEmpty()) {
            return List.of();
        }
        if (executionModel == ExecutionModel.SEQUENTIAL || workerLimit <= 1 || jobs.size() == 1) {
            final List<ConversionOutcome> outcomes = new ArrayList<>();
            for (ConversionJob job : jobs) {
                final ConversionOutcome outcome = processSingleFile(job, binding, startRule, compression);
                outcomes.add(outcome);
                emitOutcomeLog(outcome, outcomeLogger);
                if (!continueOnError && !outcome.success()) {
                    break;
                }
            }
            return outcomes;
        }

        final ExecutorService executor = createExecutor(executionModel);
        final Semaphore permits = new Semaphore(workerLimit);
        final CompletionService<ConversionOutcome> completion = new ExecutorCompletionService<>(executor);
        final List<Future<ConversionOutcome>> submitted = new ArrayList<>();
        try {
            for (ConversionJob job : jobs) {
                submitted.add(completion.submit(new Callable<>() {
                    @Override
                    public ConversionOutcome call() throws Exception {
                        permits.acquire();
                        try {
                            return processSingleFile(job, binding, startRule, compression);
                        } finally {
                            permits.release();
                        }
                    }
                }));
            }

            final List<ConversionOutcome> outcomes = new ArrayList<>();
            for (int i = 0; i < jobs.size(); i++) {
                final ConversionOutcome outcome;
                try {
                    outcome = completion.take().get();
                } catch (ExecutionException ex) {
                    throw unwrapExecutionException(ex);
                }
                outcomes.add(outcome);
                emitOutcomeLog(outcome, outcomeLogger);
                if (!continueOnError && !outcome.success()) {
                    for (Future<ConversionOutcome> future : submitted) {
                        future.cancel(true);
                    }
                    break;
                }
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private ExecutorService createExecutor(final ExecutionModel executionModel) {
        if (executionModel == ExecutionModel.VIRTUAL_THREADS) {
            return Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
                    .name(GrammarConstants.VIRTUAL_THREAD_NAME_PREFIX, 0)
                    .factory());
        }
        return Executors.newCachedThreadPool();
    }

    private Exception unwrapExecutionException(final ExecutionException executionException) {
        final Throwable cause = executionException.getCause();
        if (cause instanceof Exception ex) {
            return ex;
        }
        return new Exception(cause);
    }

    private void emitOutcomeLog(final ConversionOutcome outcome, final Consumer<String> outcomeLogger) {
        if (outcome.success()) {
            outcomeLogger.accept(truncateOutcomeLogLine("[SUCCESS] " + outcome.successLine()));
            return;
        }
        outcomeLogger.accept(truncateOutcomeLogLine("[FAILURE] " + outcome.failureMessage()));
    }

    private ConversionOutcome processSingleFile(
            final ConversionJob job,
            final RuntimeParserBinding binding,
            final String startRule,
            final boolean compression) {
        final long fileStartNanos = System.nanoTime();
        currentBinding.set(binding);
        try {
            final String xml = parseToXml(
                    job.sourceFile().toPath(),
                    binding.classLoader(),
                    binding.lexerClassName(),
                    binding.parserClassName(),
                    startRule,
                    compression);
            Files.writeString(job.output(), xml, StandardCharsets.UTF_8);
            final long durationNanos = System.nanoTime() - fileStartNanos;
            final long lineCount = countLines(job.sourceFile().toPath());
            final long byteCount = job.sourceFile().length();
            return ConversionOutcome.success(
                    job.index(),
                    toPortablePath(job.relativePath()) + " " + formatDurationSeconds(durationNanos)
                            + " " + lineCount + ":" + byteCount + " parsed",
                    durationNanos);
        } catch (Exception ex) {
            final String message = ex.getMessage();
            if (message != null && message.startsWith("Parse failed for ")) {
                return ConversionOutcome.failure(job.index(), message, System.nanoTime() - fileStartNanos);
            }
            return ConversionOutcome.failure(
                    job.index(),
                    "Conversion failed for " + job.sourceFile() + ": " + describeThrowable(ex),
                    System.nanoTime() - fileStartNanos);
        } finally {
            currentBinding.remove();
            binding.clearDFACaches();
            maybeRunAggressiveGc();
        }
    }

    private void maybeRunAggressiveGc() {
        if (!Boolean.parseBoolean(System.getProperty(GC_ENABLED_PROPERTY, "false"))) {
            return;
        }

        final int gcEveryFiles;
        try {
            gcEveryFiles = Integer.parseInt(System.getProperty(GC_EVERY_FILES_PROPERTY, "25"));
        } catch (NumberFormatException ignored) {
            return;
        }

        if (gcEveryFiles < 1) {
            return;
        }

        final int heapThresholdPercent;
        try {
            heapThresholdPercent = Integer.parseInt(System.getProperty(GC_HEAP_THRESHOLD_PERCENT_PROPERTY, "80"));
        } catch (NumberFormatException ignored) {
            return;
        }

        if (heapThresholdPercent < 1 || heapThresholdPercent > 100) {
            return;
        }

        final int processed = completedFilesCounter.incrementAndGet();
        if (processed % gcEveryFiles == 0 && heapUsedPercent() >= heapThresholdPercent) {
            System.gc();
        }
    }

    private int heapUsedPercent() {
        final Runtime runtime = Runtime.getRuntime();
        final long maxMemory = runtime.maxMemory();
        if (maxMemory <= 0L) {
            return 0;
        }
        final long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        return (int) ((usedMemory * 100L) / maxMemory);
    }

    private void validateParserBinding(final RuntimeParserBinding binding) {
        final ClassLoader parserClassLoader = binding.classLoader();
        final String lexerName = binding.lexerClassName();
        final String parserName = binding.parserClassName();

        final Class<?> lexerRaw;
        try {
            lexerRaw = parserClassLoader.loadClass(lexerName);
        } catch (ClassNotFoundException ex) {
            throw new IllegalArgumentException("Lexer class not found on runtimeClasspath: " + lexerName, ex);
        }

        final Class<?> parserRaw;
        try {
            parserRaw = parserClassLoader.loadClass(parserName);
        } catch (ClassNotFoundException ex) {
            throw new IllegalArgumentException("Parser class not found on runtimeClasspath: " + parserName, ex);
        }

        if (!Lexer.class.isAssignableFrom(lexerRaw)) {
            throw new IllegalArgumentException("Configured lexer class does not extend org.antlr.v4.runtime.Lexer: " + lexerName);
        }
        if (!Parser.class.isAssignableFrom(parserRaw)) {
            throw new IllegalArgumentException("Configured parser class does not extend org.antlr.v4.runtime.Parser: " + parserName);
        }
    }

    private String formatDurationSeconds(final long durationNanos) {
        final long seconds = TimeConstants.nanosToSeconds(durationNanos);
        return seconds + "s";
    }

    private ExecutionModel parseExecutionModel(final String executionModelName) {
        if (executionModelName == null || executionModelName.isBlank()) {
            return ExecutionModel.SEQUENTIAL;
        }
        return switch (executionModelName.trim().toUpperCase()) {
            case GrammarConstants.EXECUTION_MODEL_VIRTUAL_THREADS -> ExecutionModel.VIRTUAL_THREADS;
            case GrammarConstants.EXECUTION_MODEL_PLATFORM_THREADS -> ExecutionModel.PLATFORM_THREADS;
            default -> ExecutionModel.SEQUENTIAL;
        };
    }

    private enum ExecutionModel {
        SEQUENTIAL,
        PLATFORM_THREADS,
        VIRTUAL_THREADS
    }

    private record ConversionJob(int index, File sourceFile, Path relativePath, Path output) {
    }

    private record ConversionOutcome(
            int index,
            boolean success,
            String successLine,
            String failureMessage,
            long durationNanos) {
        private static ConversionOutcome success(final int index, final String successLine, final long durationNanos) {
            return new ConversionOutcome(index, true, successLine, null, durationNanos);
        }

        private static ConversionOutcome failure(final int index, final String failureMessage, final long durationNanos) {
            return new ConversionOutcome(index, false, null, failureMessage, durationNanos);
        }
    }

    public record ConversionStats(
            int processedFiles,
            int filesWithErrors,
            long totalDurationNanos,
            long cumulativeFileProcessingNanos) {
    }

    public static final class ConversionFailedException extends GradleException {
        private final ConversionStats stats;

        public ConversionFailedException(final String message, final ConversionStats stats) {
            super(message);
            this.stats = stats;
        }

        public ConversionStats getStats() {
            return stats;
        }
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
        return "Unknown conversion failure";
    }

    private String describeThrowable(final Throwable throwable) {
        final String message = firstNonBlankMessage(throwable);
        if (message == null || message.isBlank()) {
            return throwable.getClass().getName();
        }
        return throwable.getClass().getName() + ": " + message;
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
        return trimmed.endsWith(GrammarConstants.GRAMMAR_FILE_EXTENSION)
                || trimmed.startsWith(GrammarConstants.SCHEME_HTTP)
                || trimmed.startsWith(GrammarConstants.SCHEME_HTTPS)
                || trimmed.startsWith(GrammarConstants.SCHEME_FILE)
                || trimmed.startsWith(GrammarConstants.SCHEME_PROTOCOL_LESS)
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
            if (GrammarConstants.SCHEME_FILE.equalsIgnoreCase(uri.getScheme())) {
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
        if (trimmed.startsWith(GrammarConstants.SCHEME_PROTOCOL_LESS)) {
            return URI.create(GrammarConstants.SCHEME_HTTPS + trimmed);
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
        try (Stream<String> lines = Files.lines(grammarFile, StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
                final Matcher matcher = GrammarConstants.GRAMMAR_NAME_PATTERN.matcher(line);
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
                if (GrammarConstants.PACKAGE_DECLARATION_PATTERN.matcher(trimmed).find()) {
                    final Matcher matcher = GrammarConstants.PACKAGE_DECLARATION_PATTERN.matcher(trimmed);
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

        // Cache instances in binding for DFA management (must be done via reflection for private access)
        try {
            // This will be called from binding context, so we need to stash for later clearing
            cacheParserInstances(lexer, parser);
        } catch (Exception ignored) {
            // Caching is best-effort; proceed without it
        }

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

    private final ThreadLocal<RuntimeParserBinding> currentBinding = new ThreadLocal<>();

    // ...existing code...

    private void cacheParserInstances(final Lexer lexer, final Parser parser) {
        final RuntimeParserBinding binding = currentBinding.get();
        if (binding != null) {
            binding.setCachedInstances(lexer, parser);
        }
    }

    private String toXml(
            final Parser parser,
            final ParseTree parseTree,
            final String sourceName,
            final String startRule,
            final boolean compression) {
        final CompressionState compressionState = compression ? new CompressionState() : null;
        final XmlBuilder xmlBuilder = new XmlBuilder();

        xmlBuilder.writeXmlDeclaration();
        xmlBuilder.writeStartElement("ast");
        xmlBuilder.writeAttribute("source", sourceName);
        xmlBuilder.writeAttribute("entryRule", startRule);

        appendTreeStreaming(xmlBuilder, parseTree, parser, compressionState);

        if (compressionState != null && !compressionState.pathIndex.isEmpty()) {
            appendPathIndexStreaming(xmlBuilder, compressionState.pathIndex);
        }

        xmlBuilder.writeEndElement();
        return xmlBuilder.getXml();
    }


    private String escapeXmlText(final String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;");
    }

    private void appendTreeStreaming(
            final XmlBuilder xmlBuilder,
            final ParseTree node,
            final Parser parser,
            final CompressionState compressionState) {
        if (node instanceof RuleNode ruleNode) {
            final int ruleIndex = ruleNode.getRuleContext().getRuleIndex();
            final String ruleName = parser.getRuleNames()[ruleIndex];

            xmlBuilder.writeStartElement("r");
            xmlBuilder.writeAttribute("name", ruleName);

            ParseTree traversalNode = node;
            if (compressionState != null) {
                final CompressionChain chain = trackCompressionChain(node, parser);
                if (chain.length >= 2) {
                    final String pathId = compressionState.registerPath(chain.names);
                    xmlBuilder.writeAttribute("pathId", pathId);
                    // Flatten compressed chain by traversing children from the chain tail.
                    traversalNode = chain.tailNode;
                }
            }

            for (int i = 0; i < traversalNode.getChildCount(); i++) {
                appendTreeStreaming(xmlBuilder, traversalNode.getChild(i), parser, compressionState);
            }

            xmlBuilder.writeEndElement();
            return;
        }

        if (node instanceof TerminalNode terminalNode) {
            final Token token = terminalNode.getSymbol();
            final String type = tokenName(parser, token);

            xmlBuilder.writeStartElement("t");
            xmlBuilder.writeAttribute("type", type);
            xmlBuilder.writeAttribute("line", String.valueOf(token.getLine()));
            xmlBuilder.writeAttribute("column", String.valueOf(token.getCharPositionInLine()));
            xmlBuilder.writeCharacters(token.getText());
            xmlBuilder.writeEndElement();
            return;
        }

        xmlBuilder.writeStartElement("node");
        xmlBuilder.writeCharacters(node.getText());
        xmlBuilder.writeEndElement();
    }


    private CompressionChain trackCompressionChain(
            final ParseTree node,
            final Parser parser) {
        final CompressionChain chain = new CompressionChain();
        ParseTree current = node;

        while (current.getChildCount() == 1) {
            if (!(current instanceof RuleNode currentRuleNode)) {
                break;
            }
            final int currentRuleIndex = currentRuleNode.getRuleContext().getRuleIndex();
            final String currentRuleName = parser.getRuleNames()[currentRuleIndex];
            chain.names.add(currentRuleName);
            chain.tailNode = current;

            final ParseTree child = current.getChild(0);
            if (child instanceof RuleNode childRule) {
                current = child;
            } else {
                break;
            }
        }

        // Include current rule if loop ended before adding it (e.g. no single child rule).
        if (chain.names.isEmpty() && current instanceof RuleNode currentRuleNode) {
            final int currentRuleIndex = currentRuleNode.getRuleContext().getRuleIndex();
            final String currentRuleName = parser.getRuleNames()[currentRuleIndex];
            chain.names.add(currentRuleName);
            chain.tailNode = current;
        }

        chain.length = chain.names.size();
        return chain;
    }

    private void appendPathIndexStreaming(
            final XmlBuilder xmlBuilder,
            final Map<String, String> pathIndex) {
        if (pathIndex.isEmpty()) {
            return;
        }

        xmlBuilder.writeStartElement("pathIndex");
        for (Map.Entry<String, String> entry : pathIndex.entrySet()) {
            xmlBuilder.writeStartElement("path");
            xmlBuilder.writeAttribute("id", entry.getKey());
            xmlBuilder.writeAttribute("value", entry.getValue());
            xmlBuilder.writeEndElement();
        }
        xmlBuilder.writeEndElement();
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

    private static final class CompressionState {
        private final Map<String, String> pathIndex = new LinkedHashMap<>();

        private String registerPath(final List<String> names) {
            final String path = String.join("/", names);
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

        private static String shortHash(final String value) {
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
    }

    private static final class CompressionChain {
        private final List<String> names = new ArrayList<>();
        private int length = 0;
        private ParseTree tailNode;
    }

    private static final class RuntimeParserBinding implements AutoCloseable {
        private final ClassLoader classLoaderField;
        private final String lexerClassName;
        private final String parserClassName;
        private final Path workspace;
        private volatile Lexer cachedLexer;
        private volatile Parser cachedParser;

        private RuntimeParserBinding(
                final ClassLoader classLoader,
                final String lexerClassName,
                final String parserClassName,
                final Path workspace) {
            this.classLoaderField = classLoader;
            this.lexerClassName = lexerClassName;
            this.parserClassName = parserClassName;
            this.workspace = workspace;
        }

        private ClassLoader classLoader() {
            return classLoaderField;
        }

        private String lexerClassName() {
            return lexerClassName;
        }

        private String parserClassName() {
            return parserClassName;
        }

        private void setCachedInstances(final Lexer lexer, final Parser parser) {
            this.cachedLexer = lexer;
            this.cachedParser = parser;
        }

        private void clearDFACaches() {
            try {
                if (cachedLexer != null) {
                    cachedLexer.getInterpreter().clearDFA();
                }
                if (cachedParser != null) {
                    cachedParser.getInterpreter().clearDFA();
                }
            } catch (Exception ignored) {
                // Best effort DFA clearing
            } finally {
                cachedLexer = null;
                cachedParser = null;
            }
        }

        @Override
        public void close() {
            // Clear DFA on binding close
            clearDFACaches();
            if (classLoaderField instanceof URLClassLoader closable) {
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

    private long countLines(final Path sourceFile) throws IOException {
        try (Stream<String> lines = Files.lines(sourceFile, StandardCharsets.UTF_8)) {
            return lines.count();
        }
    }

    private String toPortablePath(final Path relativePath) {
        return relativePath.toString().replace(File.separatorChar, '/');
    }

    private String truncateOutcomeLogLine(final String line) {
        if (line == null) {
            return "";
        }
        if (line.length() <= MAX_OUTCOME_LOG_LINE_LENGTH) {
            return line;
        }
        final int prefixLength = Math.max(0, MAX_OUTCOME_LOG_LINE_LENGTH - LOG_TRUNCATION_SUFFIX.length());
        return line.substring(0, prefixLength) + LOG_TRUNCATION_SUFFIX;
    }


}
