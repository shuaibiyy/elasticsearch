/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.job.process.normalizer;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.xpack.prelert.job.quantiles.Quantiles;

import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

/**
 * Renormalizer that discards outdated quantiles if even newer ones are received while waiting for a prior renormalization to complete.
 */
public class ShortCircuitingRenormalizer implements Renormalizer {

    private static final Logger LOGGER = Loggers.getLogger(ShortCircuitingRenormalizer.class);

    private final String jobId;
    private final ScoresUpdater scoresUpdater;
    private final ExecutorService executorService;
    private final boolean isPerPartitionNormalization;
    private final Deque<QuantilesWithLatch> quantilesDeque = new ConcurrentLinkedDeque<>();
    private final Deque<CountDownLatch> latchDeque = new ConcurrentLinkedDeque<>();
    /**
     * Each job may only have 1 normalization in progress at any time; the semaphore enforces this
     */
    private final Semaphore semaphore = new Semaphore(1);

    public ShortCircuitingRenormalizer(String jobId, ScoresUpdater scoresUpdater, ExecutorService executorService,
                                       boolean isPerPartitionNormalization) {
        this.jobId = jobId;
        this.scoresUpdater = scoresUpdater;
        this.executorService = executorService;
        this.isPerPartitionNormalization = isPerPartitionNormalization;
    }

    public void renormalize(Quantiles quantiles) {
        // This will throw NPE if quantiles is null, so do it first
        QuantilesWithLatch quantilesWithLatch = new QuantilesWithLatch(quantiles, new CountDownLatch(1));
        // Needed to ensure work is not added while the tryFinishWork() method is running
        synchronized (quantilesDeque) {
            // Must add to latchDeque before quantilesDeque
            latchDeque.addLast(quantilesWithLatch.getLatch());
            quantilesDeque.addLast(quantilesWithLatch);
            executorService.submit(() -> doRenormalizations());
        }
    }

    public void waitUntilIdle() {
        try {
            // We cannot tolerate more than one thread running this loop at any time,
            // but need a different lock to the other synchronized parts of the code
            synchronized (latchDeque) {
                for (CountDownLatch latchToAwait = latchDeque.pollFirst(); latchToAwait != null; latchToAwait = latchDeque.pollFirst()) {
                    latchToAwait.await();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private Quantiles getEarliestQuantiles() {
        QuantilesWithLatch earliestQuantilesWithLatch = quantilesDeque.peekFirst();
        return (earliestQuantilesWithLatch != null) ? earliestQuantilesWithLatch.getQuantiles() : null;
    }

    private QuantilesWithLatch getLatestQuantilesWithLatchAndClear() {
        // We discard all but the latest quantiles
        QuantilesWithLatch latestQuantilesWithLatch = null;
        for (QuantilesWithLatch quantilesWithLatch = quantilesDeque.pollFirst(); quantilesWithLatch != null;
             quantilesWithLatch = quantilesDeque.pollFirst()) {
            // Count down the latches associated with any discarded quantiles
            if (latestQuantilesWithLatch != null) {
                latestQuantilesWithLatch.getLatch().countDown();
            }
            latestQuantilesWithLatch = quantilesWithLatch;
        }
        return latestQuantilesWithLatch;
    }

    private boolean tryStartWork() {
        return semaphore.tryAcquire();
    }

    private boolean tryFinishWork() {
        // We cannot tolerate new work being added in between the isEmpty() check and releasing the semaphore
        synchronized (quantilesDeque) {
            if (!quantilesDeque.isEmpty()) {
                return false;
            }
            semaphore.release();
            return true;
        }
    }

    private void forceFinishWork() {
        semaphore.release();
    }

    private void doRenormalizations() {
        // Exit immediately if another normalization is in progress.  This means we don't hog threads.
        if (tryStartWork() == false) {
            return;
        }

        CountDownLatch latch = null;
        try {
            do {
                // Note that if there is only one set of quantiles in the queue then both these references will point to the same quantiles.
                Quantiles earliestQuantiles = getEarliestQuantiles();
                QuantilesWithLatch latestQuantilesWithLatch = getLatestQuantilesWithLatchAndClear();
                // We could end up with latestQuantilesWithLatch being null if the thread running this method
                // was preempted before the tryStartWork() call, another thread already running this method
                // did the work and exited, and then this thread got true returned by tryStartWork().
                if (latestQuantilesWithLatch != null) {
                    Quantiles latestQuantiles = latestQuantilesWithLatch.getQuantiles();
                    latch = latestQuantilesWithLatch.getLatch();
                    // We could end up with earliestQuantiles being null if quantiles were
                    // added between getting the earliest and latest quantiles.
                    if (earliestQuantiles == null) {
                        earliestQuantiles = latestQuantiles;
                    }
                    long earliestBucketTimeMs = earliestQuantiles.getTimestamp().getTime();
                    long latestBucketTimeMs = latestQuantiles.getTimestamp().getTime();
                    // If we're going to skip quantiles, renormalize using the latest quantiles
                    // over the time ranges implied by all quantiles that were provided.
                    long windowExtensionMs = latestBucketTimeMs - earliestBucketTimeMs;
                    if (windowExtensionMs < 0) {
                        LOGGER.warn("[{}] Quantiles not supplied in time order - {} after {}",
                                jobId, latestBucketTimeMs, earliestBucketTimeMs);
                        windowExtensionMs = 0;
                    }
                    scoresUpdater.update(latestQuantiles.getQuantileState(), latestBucketTimeMs, windowExtensionMs,
                            isPerPartitionNormalization);
                    latch.countDown();
                    latch = null;
                }
                // Loop if more work has become available while we were working, because the
                // tasks originally submitted to do that work will have exited early.
            } while (tryFinishWork() == false);
        } catch (RuntimeException e) {
            LOGGER.error("[" + jobId + "] Normalization failed", e);
            if (latch != null) {
                latch.countDown();
            }
            forceFinishWork();
            throw e;
        }
    }

    /**
     * Simple grouping of a {@linkplain Quantiles} object with its corresponding {@linkplain CountDownLatch} object.
     */
    private static class QuantilesWithLatch {
        private final Quantiles quantiles;
        private final CountDownLatch latch;

        QuantilesWithLatch(Quantiles quantiles, CountDownLatch latch) {
            this.quantiles = Objects.requireNonNull(quantiles);
            this.latch = Objects.requireNonNull(latch);
        }

        Quantiles getQuantiles() {
            return quantiles;
        }

        CountDownLatch getLatch() {
            return latch;
        }
    }
}
