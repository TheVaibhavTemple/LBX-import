package com.bofa.ibox.lockbox.service;

import com.bofa.ibox.lockbox.LockboxConstants;
import com.bofa.ibox.lockbox.config.LockboxImportProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.io.IOException;

/**
 * Polls the configured {@code lockbox.import.in-dir} for new
 * DIGLBX_Aspec_*.json files and drives the import pipeline.
 *
 * File lifecycle:
 * <pre>
 *  in/DIGLBX_Aspec_20260416T120000.json
 *    │
 *    ├─► rename to *.processing          (atomic file-lock – skipped if rename fails)
 *    │
 *    ├─► LockboxImportService.processFile()
 *    │
 *    ├─► SUCCESS → move to processed/DIGLBX_Aspec_20260416T120000.json
 *    └─► FAILURE → move to error/DIGLBX_Aspec_20260416T120000.json
 * </pre>
 *
 * Scheduling notes:
 * <ul>
 *   <li>{@code fixedDelay} (not {@code fixedRate}) is used intentionally – the
 *       next scan only starts after the previous one fully completes, making
 *       concurrent runs impossible even if processing takes longer than the interval.</li>
 *   <li>The rename-to-.processing step is an atomic OS rename; if two instances
 *       (or two threads) race for the same file, only one will succeed.</li>
 *   <li>Set {@code lockbox.import.scheduler-enabled=false} to pause polling
 *       without restarting the application.</li>
 * </ul>
 */
@Service
public class LockboxFileWatcherService {

    private static final Logger log = LoggerFactory.getLogger(LockboxFileWatcherService.class);

    private final LockboxImportService    importService;
    private final LockboxImportProperties props;

    public LockboxFileWatcherService(LockboxImportService importService,
                                     LockboxImportProperties props) {
        this.importService = importService;
        this.props = props;
    }

    // ----------------------------------------------------------------
    // Startup: ensure working directories exist
    // ----------------------------------------------------------------

    @PostConstruct
    void ensureDirectories() {
        ensureDir(props.getInDir(),        "in");
        ensureDir(props.getProcessedDir(), "processed");
        ensureDir(props.getErrorDir(),     "error");
    }

    // ----------------------------------------------------------------
    // Scheduler
    // ----------------------------------------------------------------

    /**
     * Scans {@code lockbox.import.in-dir} for new lockbox files.
     *
     * Interval is read from {@code lockbox.import.scheduler-interval-ms}
     * (default 10 000 ms = 10 s).  The initial delay gives the application
     * time to fully start before the first scan.
     */
    @Scheduled(
        fixedDelayString   = "${lockbox.import.scheduler-interval-ms:10000}",
        initialDelayString = "${lockbox.import.scheduler-initial-delay-ms:5000}")
    public void scanAndProcess() {
        if (!props.isSchedulerEnabled()) {
            log.debug("Lockbox file watcher is disabled – skipping scan");
            return;
        }

        File inDir = new File(props.getInDir());
        if (!inDir.isDirectory()) {
            log.warn("Lockbox in-dir does not exist or is not a directory: {}", props.getInDir());
            return;
        }

        // List only *.json files – *.processing files are mid-flight and skipped
        File[] candidates = inDir.listFiles(f ->
            f.isFile() && LockboxConstants.FILE_NAME_PATTERN.matcher(f.getName()).matches());

        if (candidates == null || candidates.length == 0) {
            log.debug("No lockbox files found in {}", inDir.getAbsolutePath());
            return;
        }

        log.info("Found {} lockbox file(s) in {}", candidates.length, inDir.getName());
        for (File candidate : candidates) {
            processOne(candidate);
        }
    }

    // ----------------------------------------------------------------
    // Per-file: lock → process → route
    // ----------------------------------------------------------------

    private void processOne(File file) {
        String originalName = file.getName();

        // ── Atomic file lock via rename ───────────────────────────────
        // If two processes or threads race for the same file, only one
        // rename() call will succeed – the loser simply skips the file.
        File processingFile = new File(file.getParent(), originalName + LockboxConstants.PROCESSING_SUFFIX);
        if (!file.renameTo(processingFile)) {
            log.warn("Could not lock '{}' (already being processed or access denied) – skipping",
                originalName);
            return;
        }
        log.info("Locked '{}' → '{}'", originalName, processingFile.getName());

        try {
            importService.processFile(processingFile);
            moveToDir(processingFile, originalName, props.getProcessedDir(), "processed");
        } catch (Exception ex) {
            log.error("Import failed for '{}': {} – routing to error dir",
                originalName, ex.getMessage(), ex);
            moveToDir(processingFile, originalName, props.getErrorDir(), "error");
        }
    }

    // ----------------------------------------------------------------
    // File move helper
    // ----------------------------------------------------------------

    private void moveToDir(File source, String targetName, String destDirPath, String label) {
        ensureDir(destDirPath, label);
        File dest = new File(destDirPath, targetName);
        try {
            Files.move(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            log.info("'{}' → {}/{}", targetName, label, targetName);
        } catch (IOException e) {
            log.error("Failed to move '{}' to {} dir (source path: {}). Error: {}",
                targetName, label, source.getAbsolutePath(), e.getMessage());
        }
    }

    private void ensureDir(String path, String label) {
        File dir = new File(path);
        if (!dir.exists() && !dir.mkdirs()) {
            log.warn("Could not create {} directory: {}", label, path);
        }
    }
}
