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
import java.util.concurrent.ConcurrentHashMap;
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

    private static final int MAX_RETAINED_FAILURE_MESSAGES = 256;

    /**
     * Creates a converter instance.
     */
    public DynamicAntlrXmlAstConverter() {
    }

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
                GrammarConstants.DEFAULT_MAX_IN_FLIGHT_JOBS,
                GrammarConstants.DEFAULT_CACHE_PRESSURE_CHECK_INTERVAL,
                GrammarConstants.DEFAULT_MEMORY_PRESSURE_THRESHOLD_PERCENT);
    }

    /**
     * Converts source files and returns aggregated execution statistics.
     *
     * @param sourceRoot root directory used to preserve relative output paths.
     * @param sourceFiles files to process.
     * @param destinationRoot output root directory.
     * @param targetExtension output extension appended to mapped source filenames.
     * @param classLoader classloader containing parser/lexer/runtime dependencies.
     * @param lexerClassName lexer class name or grammar coordinate.
     * @param parserClassName parser class name or grammar coordinate.
     * @param startRule parser entry rule.
     * @param compression enables AST compression.
     * @param continueOnError when true, processes remaining files after failures.
     * @param executionModelName worker model (sequential/platform/virtual threads).
     * @param configuredParallelism worker count when non-sequential model is used.
     * @return aggregated conversion statistics.
     */
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
            final int configuredParallelism) {
        return convertFileTreeWithStats(
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
                executionModelName,
                configuredParallelism,
                GrammarConstants.DEFAULT_MAX_IN_FLIGHT_JOBS,
                GrammarConstants.DEFAULT_CACHE_PRESSURE_CHECK_INTERVAL,
                GrammarConstants.DEFAULT_MEMORY_PRESSURE_THRESHOLD_PERCENT);
    }

    /**
     * Converts source files and returns aggregated execution statistics with memory controls.
     */
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
            final int maxInFlightJobs,
            final int cachePressureCheckInterval,
            final int memoryPressureThresholdPercent) {
        // Guard: Parallelism constraints
        if (configuredParallelism < 1) {
            throw new IllegalArgumentException("configuredParallelism must be >= 1, got: " + configuredParallelism);
        }
        if (maxInFlightJobs < 1) {
            throw new IllegalArgumentException("maxInFlightJobs must be >= 1, got: " + maxInFlightJobs);
        }
        if (cachePressureCheckInterval < 1) {
            throw new IllegalArgumentException("cachePressureCheckInterval must be >= 1, got: " + cachePressureCheckInterval);
        }
        if (memoryPressureThresholdPercent < 50 || memoryPressureThresholdPercent > 98) {
            throw new IllegalArgumentException("memoryPressureThresholdPercent must be between 50 and 98, got: "
                    + memoryPressureThresholdPercent);
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
                        : configuredParallelism;
                final List<ConversionOutcome> outcomes = executeJobs(
                        jobs,
                        binding,
                        startRule,
                        compression,
                        continueOnError,
                        executionModel,
                        workerLimit,
                        Math.max(workerLimit, maxInFlightJobs),
                        cachePressureCheckInterval,
                        memoryPressureThresholdPercent);

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
        final List<String> retainedFailures = new ArrayList<>();
        long cumulativeFileProcessingNanos = 0L;
        int failureCount = 0;

        for (ConversionOutcome outcome : outcomes) {
            cumulativeFileProcessingNanos += outcome.durationNanos();
            if (outcome.success()) {
                System.out.println(outcome.successLine());
            } else {
                failureCount++;
                if (retainedFailures.size() < MAX_RETAINED_FAILURE_MESSAGES) {
                    retainedFailures.add(outcome.failureMessage());
                }
            }
        }
        
        // Print detailed parse errors inline
        for (String failure : retainedFailures) {
            if (failure.startsWith("Parse failed for")) {
                final int colonIdx = failure.indexOf(": ");
                if (colonIdx > 0) {
                    final String filePath = failure.substring("Parse failed for ".length(), colonIdx);
                    final String messages = failure.substring(colonIdx + 2);
                    for (String msg : messages.split(" \\| ")) {
                        System.out.println(filePath + " " + msg.trim());
                    }
                }
            }
        }

        final ConversionStats stats = new ConversionStats(
                outcomes.size(),
                failureCount,
                System.nanoTime() - runStartNanos,
                cumulativeFileProcessingNanos);
        
        if (failureCount > 0) {
            final int droppedFailureMessages = Math.max(0, failureCount - retainedFailures.size());
            if (droppedFailureMessages > 0) {
                retainedFailures.add("... " + droppedFailureMessages
                        + " additional parse failure(s) omitted to cap in-memory aggregation");
            }
            throw new ConversionFailedException(String.join(" || ", retainedFailures), stats);
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
            final int maxInFlightJobs,
            final int cachePressureCheckInterval,
            final int memoryPressureThresholdPercent) throws Exception {
        if (jobs.isEmpty()) {
            return List.of();
        }
        if (executionModel == ExecutionModel.SEQUENTIAL || workerLimit <= 1 || jobs.size() == 1) {
            final List<ConversionOutcome> outcomes = new ArrayList<>();
            int processedCount = 0;
            for (ConversionJob job : jobs) {
                final ConversionOutcome outcome = processSingleFile(job, binding, startRule, compression, outcomeLogger);
                outcomes.add(outcome);
                processedCount++;
                applyMemoryPressureControl(processedCount, binding, cachePressureCheckInterval, memoryPressureThresholdPercent);
                if (!continueOnError && !outcome.success()) {
                    break;
                }
            }
            return outcomes;
        }

        final ExecutorService executor = createExecutor(executionModel);
        final Semaphore permits = new Semaphore(workerLimit);
        final CompletionService<ConversionOutcome> completion = new ExecutorCompletionService<>(executor);
        final List<Future<ConversionOutcome>> inFlight = new ArrayList<>();
        final AtomicInteger submittedIndex = new AtomicInteger(0);
        try {
            fillInFlightWindow(jobs, binding, startRule, compression, completion, permits, inFlight, submittedIndex, maxInFlightJobs);

            final List<ConversionOutcome> outcomes = new ArrayList<>();
            int processedCount = 0;
            while (!inFlight.isEmpty()) {
                final ConversionOutcome outcome;
                try {
                    outcome = completion.take().get();
                } catch (ExecutionException ex) {
                    throw unwrapExecutionException(ex);
                }
                processedCount++;
                outcomes.add(outcome);
                inFlight.removeIf(Future::isDone);
                applyMemoryPressureControl(processedCount, binding, cachePressureCheckInterval, memoryPressureThresholdPercent);

                if (!continueOnError && !outcome.success()) {
                    for (Future<ConversionOutcome> future : inFlight) {
                        future.cancel(true);
                    }
                    break;
                }

                fillInFlightWindow(jobs, binding, startRule, compression, completion, permits, inFlight, submittedIndex, maxInFlightJobs);
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private void fillInFlightWindow(
            final List<ConversionJob> jobs,
            final RuntimeParserBinding binding,
            final String startRule,
            final boolean compression,
            final CompletionService<ConversionOutcome> completion,
            final Semaphore permits,
            final List<Future<ConversionOutcome>> inFlight,
            final AtomicInteger submittedIndex,
            final int maxInFlightJobs) {
        while (inFlight.size() < maxInFlightJobs && submittedIndex.get() < jobs.size()) {
            final ConversionJob nextJob = jobs.get(submittedIndex.getAndIncrement());
            inFlight.add(completion.submit(new Callable<>() {
                @Override
                public ConversionOutcome call() throws Exception {
                    permits.acquire();
                    try {
                        return processSingleFile(nextJob, binding, startRule, compression);
                    } finally {
                        permits.release();
                    }
                }
            }));
        }
    }

    private void applyMemoryPressureControl(
            final int processedCount,
            final RuntimeParserBinding binding,
            final int cachePressureCheckInterval,
            final int memoryPressureThresholdPercent) {
        if (processedCount % cachePressureCheckInterval == 0) {
            clearSharedCaches(binding);
            // Avoid explicit GC; rely on JVM heuristics after cache cleanup.
        }
    }

    private void clearSharedCaches(final RuntimeParserBinding binding) {
        try {
            final Class<?> lexerType = binding.classLoader().loadClass(binding.lexerClassName());
            clearSharedPredictionContextCache(lexerType);
        } catch (Exception ignored) {
            // Best effort cache cleanup.
        }
        try {
            final Class<?> parserType = binding.classLoader().loadClass(binding.parserClassName());
            clearSharedPredictionContextCache(parserType);
        } catch (Exception ignored) {
            // Best effort cache cleanup.
        }
    }

    private int heapPressurePercent() {
        final Runtime runtime = Runtime.getRuntime();
        final long maxMemory = Math.max(1L, runtime.maxMemory());
        final long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        return (int) ((usedMemory * 100L) / maxMemory);
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
            final boolean compression,
            final Consumer<String> outcomeLogger) {
        final long fileStartNanos = System.nanoTime();
        setCurrentBinding(binding);
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
            return ConversionOutcome.failure(job.index(), firstNonBlankMessage(ex), System.nanoTime() - fileStartNanos);
        } finally {
            clearCurrentBinding();
            binding.clearCurrentThreadCaches();
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

    /**
     * Supported worker execution strategies for file conversion.
     */
    private enum ExecutionModel {
        SEQUENTIAL,
        PLATFORM_THREADS,
        VIRTUAL_THREADS
    }

    /**
     * Immutable mapping of one source input to one target output path.
     */
    private record ConversionJob(int index, File sourceFile, Path relativePath, Path output) {
    }

    /**
     * Per-file conversion result used for ordered reporting and summary statistics.
     */
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

    /**
     * Aggregated conversion metrics for one converter invocation.
     *
     * @param processedFiles number of processed inputs.
     * @param filesWithErrors number of inputs that failed parsing/conversion.
     * @param totalDurationNanos wall-clock duration for the invocation.
     * @param cumulativeFileProcessingNanos sum of all per-file durations.
     */
    public record ConversionStats(
            int processedFiles,
            int filesWithErrors,
            long totalDurationNanos,
            long cumulativeFileProcessingNanos) {
    }

    /**
     * Signals one or more conversion failures while preserving collected statistics.
     */
    public static final class ConversionFailedException extends GradleException {
        /**
         * Aggregated stats captured at failure time.
         */
        private final ConversionStats stats;

        /**
         * Creates a conversion failure with captured metrics.
         *
         * @param message failure summary.
         * @param stats aggregated conversion metrics.
         */
        public ConversionFailedException(final String message, final ConversionStats stats) {
            super(message);
            this.stats = stats;
        }

        /**
        * Returns conversion metrics collected before failure propagation.
        *
        * @return conversion metrics.
        */
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
        if (message.isBlank()) {
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
        final Path source;
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
        final Constructor<? extends Parser> parserCtor = parserClass.getConstructor(org.antlr.v4.runtime.TokenStream.class);

        Lexer lexer = null;
        CommonTokenStream tokenStream = null;
        Parser parser = null;
        ParseTree parseTree = null;
        CollectingErrorListener errors = null;
        try {
            lexer = lexerCtor.newInstance(CharStreams.fromPath(sourceFile, StandardCharsets.UTF_8));
            tokenStream = new CommonTokenStream(lexer);
            parser = parserCtor.newInstance(tokenStream);

            // Bind instances for explicit cache cleanup after each file.
            cacheParserInstances(lexer, parser);

            errors = new CollectingErrorListener();
            lexer.removeErrorListeners();
            parser.removeErrorListeners();
            lexer.addErrorListener(errors);
            parser.addErrorListener(errors);

            final Method entryPoint = parserClass.getMethod(startRule);
            final Object treeObj = entryPoint.invoke(parser);
            if (!(treeObj instanceof ParseTree parsedTree)) {
                throw new IllegalStateException("Start rule does not return a ParseTree: " + startRule);
            }
            parseTree = parsedTree;

            if (errors.errorCount > 0) {
                throw new GradleException("Parse failed for " + sourceFile + ": " + String.join(" | ", errors.messages));
            }

            final String xml = toXml(parser, parseTree, sourceFile.getFileName().toString(), startRule, compression);
            final long lineCount = countLines(sourceFile);
            final long byteCount = Files.size(sourceFile);
            errors.successfulParse = true;
            errors.lineCount = lineCount;
            errors.byteCount = byteCount;
            return xml;
        } finally {
            // Drop strong references quickly to improve old-gen reclamation during long runs.
            parseTree = null;
            if (errors != null) {
                errors.messages.clear();
            }
            if (tokenStream != null) {
                tokenStream.setTokenSource(null);
            }
            if (lexer != null) {
                lexer.removeErrorListeners();
            }
            if (parser != null) {
                parser.removeErrorListeners();
            }
            clearAntlrStaticCaches(lexerClass, parserClass, lexer, parser);
        }
    }

    private void clearAntlrStaticCaches(
            final Class<? extends Lexer> lexerClass,
            final Class<? extends Parser> parserClass,
            final Lexer lexer,
            final Parser parser) {
        try {
            if (lexer != null && lexer.getInterpreter() != null) {
                lexer.getInterpreter().clearDFA();
            }
        } catch (Exception ignored) {
            // Best effort cache cleanup.
        }
        try {
            if (parser != null && parser.getInterpreter() != null) {
                parser.getInterpreter().clearDFA();
            }
        } catch (Exception ignored) {
            // Best effort cache cleanup.
        }
        clearSharedPredictionContextCache(lexerClass);
        clearSharedPredictionContextCache(parserClass);
    }

    private void clearSharedPredictionContextCache(final Class<?> antlrType) {
        try {
            final var sharedCacheField = antlrType.getDeclaredField("_sharedContextCache");
            sharedCacheField.setAccessible(true);
            final Object sharedCache = sharedCacheField.get(null);
            if (sharedCache == null) {
                return;
            }
            try {
                final Method clearMethod = sharedCache.getClass().getMethod("clear");
                clearMethod.invoke(sharedCache);
                return;
            } catch (NoSuchMethodException ignored) {
                // Fall through to reflective map clear for ANTLR versions without a public clear().
            }
            final var cacheField = sharedCache.getClass().getDeclaredField("cache");
            cacheField.setAccessible(true);
            final Object cache = cacheField.get(sharedCache);
            if (cache instanceof Map<?, ?> map) {
                map.clear();
            }
        } catch (Exception ignored) {
            // Best effort cache cleanup.
        }
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
        final StringBuilder xml = new StringBuilder(16_384);
        final Map<String, String> pathIndex = compression ? new LinkedHashMap<>() : Map.of();

        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<ast source=\"")
                .append(escapeXml(sourceName))
                .append("\" entryRule=\"")
                .append(escapeXml(startRule))
                .append("\">\n");

        appendTreeXml(xml, parseTree, parser, 1, compression, pathIndex);
        appendPathIndexXml(xml, pathIndex, 1);
        xml.append("</ast>\n");
        return xml.toString();
    }

        while (current.getChildCount() == 1) {
            if (!(current instanceof RuleNode currentRuleNode)) {
                break;
            }
            final int currentRuleIndex = currentRuleNode.getRuleContext().getRuleIndex();
            final String currentRuleName = parser.getRuleNames()[currentRuleIndex];
            chain.names.add(currentRuleName);
            chain.tailNode = current;

            final ParseTree child = current.getChild(0);
            if (child instanceof RuleNode) {
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

    private void appendPathIndexXml(
            final StringBuilder xml,
            final Map<String, String> pathIndex,
            final int indentLevel) {
        if (pathIndex.isEmpty()) {
            return;
        }
        indent(xml, indentLevel).append("<pathIndex>\n");
        for (Map.Entry<String, String> entry : pathIndex.entrySet()) {
            indent(xml, indentLevel + 1)
                    .append("<path id=\"")
                    .append(escapeXml(entry.getKey()))
                    .append("\" value=\"")
                    .append(escapeXml(entry.getValue()))
                    .append("\"/>\n");
        }
        indent(xml, indentLevel).append("</pathIndex>\n");
    }

    private void appendTreeXml(
            final StringBuilder xml,
            final ParseTree node,
            final Parser parser,
            final int indentLevel,
            final boolean compression,
            final Map<String, String> pathIndex) {
        if (node instanceof RuleNode ruleNode) {
            RuleNode emissionNode = ruleNode;
            final List<String> chain = compression ? new ArrayList<>() : List.of();
            if (compression) {
                chain.add(ruleName(parser, emissionNode));
                while (emissionNode.getChildCount() == 1 && emissionNode.getChild(0) instanceof RuleNode childRule) {
                    emissionNode = childRule;
                    chain.add(ruleName(parser, emissionNode));
                }
            }

            final String ruleName = compression && !chain.isEmpty() ? chain.get(0) : ruleName(parser, ruleNode);
            indent(xml, indentLevel).append("<rule name=\"").append(escapeXml(ruleName)).append("\"");
            if (compression && chain.size() >= 2) {
                final String path = String.join("/", chain);
                final String pathId = ensureUniquePathId(path, pathIndex);
                xml.append(" pathId=\"").append(pathId).append("\"");
            }
            xml.append(">\n");
            for (int i = 0; i < emissionNode.getChildCount(); i++) {
                appendTreeXml(xml, emissionNode.getChild(i), parser, indentLevel + 1, compression, pathIndex);
            }
            indent(xml, indentLevel).append("</rule>\n");
            return;
        }

        if (node instanceof TerminalNode terminalNode) {
            final Token token = terminalNode.getSymbol();
            indent(xml, indentLevel)
                    .append("<token type=\"")
                    .append(escapeXml(tokenName(parser, token)))
                    .append("\" line=\"")
                    .append(token.getLine())
                    .append("\" column=\"")
                    .append(token.getCharPositionInLine())
                    .append("\">")
                    .append(escapeXml(token.getText()))
                    .append("</token>\n");
            return;
        }

        indent(xml, indentLevel)
                .append("<node>")
                .append(escapeXml(node.getText()))
                .append("</node>\n");
    }

    private String ruleName(final Parser parser, final RuleNode ruleNode) {
        final int ruleIndex = ruleNode.getRuleContext().getRuleIndex();
        return parser.getRuleNames()[ruleIndex];
    }

    private StringBuilder indent(final StringBuilder xml, final int level) {
        return xml.append("  ".repeat(Math.max(0, level)));
    }

    private String escapeXml(final String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        final StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            final char ch = value.charAt(i);
            switch (ch) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '\"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                default -> escaped.append(ch);
            }
        }
        return escaped.toString();
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

    /**
     * Holds runtime parser/lexer loading metadata and provides best-effort cache cleanup hooks.
     */
    private static final class RuntimeParserBinding implements AutoCloseable {
        private final ClassLoader classLoaderField;
        private final String lexerClassName;
        private final String parserClassName;
        private final Path workspace;
        private final ThreadLocal<Lexer> cachedLexerByThread = new ThreadLocal<>();
        private final ThreadLocal<Parser> cachedParserByThread = new ThreadLocal<>();

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
            this.cachedLexerByThread.set(lexer);
            this.cachedParserByThread.set(parser);
        }

        private void clearCurrentThreadCaches() {
            try {
                final Lexer cachedLexer = cachedLexerByThread.get();
                if (cachedLexer != null) {
                    cachedLexer.getInterpreter().clearDFA();
                }
                final Parser cachedParser = cachedParserByThread.get();
                if (cachedParser != null) {
                    cachedParser.getInterpreter().clearDFA();
                }
            } catch (Exception ignored) {
                // Best effort DFA clearing
            } finally {
                cachedLexerByThread.remove();
                cachedParserByThread.remove();
            }
        }

        @Override
        public void close() {
            // Clear DFA on binding close
            clearCurrentThreadCaches();
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

    /**
     * Collects lexer/parser syntax diagnostics for one file conversion.
     */
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
