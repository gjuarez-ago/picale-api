package com.metricol.api.exception;

import java.util.List;

/** La operación necesita el contexto mínimo completo de la marca. */
public class MarcaIncompletaException extends RuntimeException {

    private final int percent;
    private final List<String> missing;

    public MarcaIncompletaException(int percent, List<String> missing) {
        super("Completa la marca antes de crear contenido.");
        this.percent = percent;
        this.missing = List.copyOf(missing);
    }

    public int getPercent() {
        return percent;
    }

    public List<String> getMissing() {
        return missing;
    }
}
