package com.hedera.hashgraph.web.impl;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * An {@link java.util.concurrent.ExecutorService} that immediately executes the supplied
 * runnable.
 */
public class SameThreadExecutorService extends AbstractExecutorService {
    private boolean shutdown = false;

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @NotNull
    @Override
    public List<Runnable> shutdownNow() {
        return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, @NotNull TimeUnit unit) {
        return true;
    }

    @Override
    public void execute(@NotNull Runnable command) {
        command.run();
    }
}
