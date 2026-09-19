package com.metricol.api.enums;

/**
 * En qué punto está la licencia de un workspace.
 *
 * <p>Se guarda como texto sin restricción en la base (ver
 * {@code LicenseStatusConverter}): agregar un estado no debe obligar a tocar
 * una restricción CHECK que {@code ddl-auto=update} no sabe refrescar.
 */
public enum LicenseStatus {
    /** Prueba gratis: se puede usar hasta {@code trialEndsAt}, sin Stripe de por medio. */
    TRIALING,
    /** Pagada: se puede usar hasta el fin del periodo. Puede estar en "no se renueva". */
    ACTIVE,
    /** Falló el cobro: se sigue usando durante los días de gracia, luego se archiva. */
    PAST_DUE,
    /** Terminó: el workspace se archivó (no se borró). Pagar la vuelve a abrir. */
    ENDED
}
