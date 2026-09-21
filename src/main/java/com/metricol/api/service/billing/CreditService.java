package com.metricol.api.service.billing;

import com.metricol.api.service.CuentaSinLimites;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.CreditMovement;
import com.metricol.api.entity.ImageCredits;
import com.metricol.api.exception.QuotaExceededException;
import com.metricol.api.repository.CreditMovementRepository;
import com.metricol.api.repository.ImageCreditsRepository;

/**
 * Los créditos de imagen: 1 crédito = 1 generación.
 *
 * <p>Con los cobros apagados no hay créditos ni tope: todo pasa. Encendidos,
 * cada generación gasta un crédito —primero de los mensuales, luego de los de
 * paquete— y si la generación no produce nada se devuelve.
 *
 * <p>Todo movimiento queda en {@code credit_movements} con una referencia, y la
 * misma referencia nunca cuenta dos veces: un aviso de Stripe repetido no regala
 * créditos, y una devolución repetida no los duplica.
 */
@Service
public class CreditService {

    private final ImageCreditsRepository creditos;
    private final CreditMovementRepository movimientos;
    private final BillingConfig config;
    private final CuentaSinLimites sinLimites;

    public CreditService(ImageCreditsRepository creditos, CreditMovementRepository movimientos,
            BillingConfig config, CuentaSinLimites sinLimites) {
        this.sinLimites = sinLimites;
        this.creditos = creditos;
        this.movimientos = movimientos;
        this.config = config;
    }

    /** Lo que tiene un workspace, separado por bolsa. */
    public record Saldo(int mensuales, int paquete, LocalDateTime vencenLosMensuales) {
        public int total() {
            return mensuales + paquete;
        }
    }

    @Transactional(readOnly = true)
    public Saldo saldo(UUID workspaceId) {
        LocalDateTime ahora = LocalDateTime.now();
        return creditos.findById(workspaceId)
                .map(c -> new Saldo(c.mensualesVigentes(ahora), Math.max(0, c.getPackBalance()),
                        c.getMonthlyExpiresAt()))
                .orElse(new Saldo(0, 0, null));
    }

    /**
     * Gasta el crédito de una generación. Devuelve cuántos quedan, o
     * {@code Integer.MAX_VALUE} si los cobros están apagados.
     *
     * @param referencia identifica la generación: con ella se puede devolver
     * @throws QuotaExceededException si no queda ninguno
     */
    @Transactional
    public int consumirGeneracion(UUID workspaceId, String referencia) {
        if (!config.habilitado() || sinLimites.deWorkspace(workspaceId)) {
            return Integer.MAX_VALUE;
        }
        // Reintentar la misma generación no la cobra dos veces.
        if (movimientos.existsByWorkspaceIdAndMotivoAndReferencia(workspaceId, CreditMovement.GENERACION, referencia)) {
            return saldo(workspaceId).total();
        }

        LocalDateTime ahora = LocalDateTime.now();
        ImageCredits c = obtener(workspaceId);
        int mensuales = c.mensualesVigentes(ahora);
        int paquete = Math.max(0, c.getPackBalance());
        if (mensuales + paquete < 1) {
            throw new QuotaExceededException("CREDITS_EXHAUSTED",
                    // Sin "compra": este mensaje lo lee también la app móvil, que no vende ni manda a pagar.
                    "Ya usaste los créditos de imagen de este espacio. Revisa la situación de tu cuenta o escribe a soporte.");
        }

        String bolsa;
        if (mensuales > 0) {
            c.setMonthlyBalance(mensuales - 1);
            bolsa = CreditMovement.MENSUAL;
        } else {
            // Los mensuales vencidos que sobraban ya no valen: se dejan en cero.
            c.setMonthlyBalance(0);
            c.setPackBalance(paquete - 1);
            bolsa = CreditMovement.PAQUETE;
        }
        c.setUpdatedAt(ahora);
        creditos.save(c);
        movimientos.save(CreditMovement.builder()
                .workspaceId(workspaceId).delta(-1).bolsa(bolsa)
                .motivo(CreditMovement.GENERACION).referencia(referencia).build());
        return Math.max(0, c.mensualesVigentes(ahora) + Math.max(0, c.getPackBalance()));
    }

    /**
     * Devuelve el crédito de una generación que no produjo nada. A la misma
     * bolsa de la que salió. Repetirlo no hace nada.
     */
    @Transactional
    public void devolverGeneracion(UUID workspaceId, String referencia) {
        Optional<CreditMovement> gasto = movimientos.findByWorkspaceIdAndMotivoAndReferencia(
                workspaceId, CreditMovement.GENERACION, referencia);
        if (gasto.isEmpty()
                || movimientos.existsByWorkspaceIdAndMotivoAndReferencia(workspaceId, CreditMovement.DEVOLUCION, referencia)) {
            return;
        }
        ImageCredits c = obtener(workspaceId);
        String bolsa = gasto.get().getBolsa();
        if (CreditMovement.MENSUAL.equals(bolsa)) {
            c.setMonthlyBalance(Math.max(0, c.getMonthlyBalance()) + 1);
        } else {
            c.setPackBalance(Math.max(0, c.getPackBalance()) + 1);
        }
        c.setUpdatedAt(LocalDateTime.now());
        creditos.save(c);
        movimientos.save(CreditMovement.builder()
                .workspaceId(workspaceId).delta(1).bolsa(bolsa)
                .motivo(CreditMovement.DEVOLUCION).referencia(referencia).build());
    }

    /**
     * Los créditos del mes: reinicia la bolsa mensual (no se acumulan) y fija
     * cuándo vence. {@code false} si esa referencia ya se había otorgado.
     */
    @Transactional
    public boolean otorgarMensuales(UUID workspaceId, int cantidad, LocalDateTime vence, String referencia) {
        if (movimientos.existsByWorkspaceIdAndMotivoAndReferencia(workspaceId, CreditMovement.OTORGADO_MENSUAL, referencia)) {
            return false;
        }
        ImageCredits c = obtener(workspaceId);
        c.setMonthlyBalance(Math.max(0, cantidad));
        c.setMonthlyExpiresAt(vence);
        c.setUpdatedAt(LocalDateTime.now());
        creditos.save(c);
        movimientos.save(CreditMovement.builder()
                .workspaceId(workspaceId).delta(Math.max(0, cantidad)).bolsa(CreditMovement.MENSUAL)
                .motivo(CreditMovement.OTORGADO_MENSUAL).referencia(referencia).build());
        return true;
    }

    /** Suma los créditos de un paquete comprado (no vencen). {@code false} si esa compra ya se sumó. */
    @Transactional
    public boolean agregarPaquete(UUID workspaceId, int cantidad, String referencia) {
        if (cantidad <= 0
                || movimientos.existsByWorkspaceIdAndMotivoAndReferencia(workspaceId, CreditMovement.COMPRA_PAQUETE, referencia)) {
            return false;
        }
        ImageCredits c = obtener(workspaceId);
        c.setPackBalance(Math.max(0, c.getPackBalance()) + cantidad);
        c.setUpdatedAt(LocalDateTime.now());
        creditos.save(c);
        movimientos.save(CreditMovement.builder()
                .workspaceId(workspaceId).delta(cantidad).bolsa(CreditMovement.PAQUETE)
                .motivo(CreditMovement.COMPRA_PAQUETE).referencia(referencia).build());
        return true;
    }

    /** La fila del workspace, con candado; se crea si es la primera vez. */
    private ImageCredits obtener(UUID workspaceId) {
        return creditos.bloquear(workspaceId)
                .orElseGet(() -> creditos.save(ImageCredits.builder().workspaceId(workspaceId).build()));
    }
}
