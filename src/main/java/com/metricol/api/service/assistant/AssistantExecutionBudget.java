package com.metricol.api.service.assistant;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Presupuesto del segmento activo; el acumulado durable se lee de los eventos del turno. */
final class AssistantExecutionBudget implements AutoCloseable {
    private static final ThreadLocal<AssistantExecutionBudget> CURRENT = new ThreadLocal<>();
    private final long allowanceMs, started;
    private final LongSupplier clock;
    private final Consumer<String> progress;
    private final AssistantExecutionBudget previous;

    static AssistantExecutionBudget open(long limitMs, long spentMs, Consumer<String> progress) {
        return new AssistantExecutionBudget(limitMs, spentMs, progress, System::nanoTime);
    }
    AssistantExecutionBudget(long limitMs, long spentMs, Consumer<String> progress, LongSupplier clock) {
        this.allowanceMs = Math.max(0, limitMs - Math.max(0, spentMs));
        this.clock = clock; this.started = clock.getAsLong(); this.progress = progress;
        this.previous = CURRENT.get(); CURRENT.set(this);
    }
    static Duration timeout(Duration maximum) {
        var budget = CURRENT.get();
        if (budget == null) return maximum;
        long remaining = budget.allowanceMs - (budget.clock.getAsLong() - budget.started) / 1_000_000;
        if (remaining <= 0) throw new Expired();
        return Duration.ofMillis(Math.max(1, Math.min(maximum.toMillis(), remaining)));
    }
    static void check() { timeout(Duration.ofMillis(Long.MAX_VALUE)); }
    static void waitingForRateLimit(long milliseconds) {
        if (milliseconds >= timeout(Duration.ofMillis(Long.MAX_VALUE)).toMillis()) throw new Expired();
        var budget = CURRENT.get();
        if (budget != null && budget.progress != null)
            budget.progress.accept("El servicio de IA pidió una pausa. Estoy esperando para continuar, sin repetir tus acciones.");
    }
    @Override public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
    static final class Expired extends RuntimeException {
        Expired() { super("Alcancé el tiempo máximo de trabajo de este turno. Tu mensaje sigue guardado. "
                + "Esto no cancela trabajos externos: revisa su estado antes de repetir una acción."); }
    }
}
