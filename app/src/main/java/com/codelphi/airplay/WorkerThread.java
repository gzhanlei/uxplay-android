package com.codelphi.airplay;

final class WorkerThread {
    private WorkerThread() { }

    static void stop(Thread worker) {
        if (worker == null) return;
        worker.interrupt();
        try {
            worker.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
