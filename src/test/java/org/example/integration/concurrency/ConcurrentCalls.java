package org.example.integration.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs several calls against the same daemon as concurrently as one JVM can manage - what the shell scripts get
 * from launching several background subshells and {@code wait}ing on them. Every call sits on a thread of its own
 * behind a barrier, so they are released together rather than trickling in one at a time; results come back in
 * the order the calls were given, once every one of them has finished.
 */
final class ConcurrentCalls {
    private ConcurrentCalls() {
    }

    static <T> List<T> run(List<Callable<T>> calls) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch ready = new CountDownLatch(calls.size());
            CountDownLatch go = new CountDownLatch(1);

            List<Future<T>> futures = calls.stream()
                    .map(call -> pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        return call.call();
                    }))
                    .toList();

            ready.await();
            go.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(result(future));
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    private static <T> T result(Future<T> future) throws InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException exception) {
            throw new RuntimeException(exception.getCause());
        }
    }
}
