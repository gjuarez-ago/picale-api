package com.metricol.api.service.media;

import java.util.UUID;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.metricol.api.config.TenantIdentifierResolver;

/**
 * Saca la miniatura en otro hilo, sin perder el workspace por el camino.
 *
 * <p><b>Por qué existe esta clase y no un {@code @Async} en
 * {@link MiniaturaDeVideo}.</b> Ahí estaba antes, y por eso las miniaturas no
 * se generaron nunca: {@code MediaAsset} lleva {@code @TenantId}, el tenant
 * vive en un ThreadLocal, y {@code @Async} salta a un hilo del pool donde ese
 * ThreadLocal está vacío. Hibernate resolvía {@code GLOBAL}, el
 * {@code findById} volvía vacío y el método salía por su primer {@code return}
 * sin decir nada. El síntoma era el que no apunta a ningún lado: el arranque
 * anunciaba "sacando miniatura a 3 videos" y después, silencio.
 *
 * <p>Y no basta con imponer el tenant dentro de {@code generar}: ese método es
 * {@code @Transactional(REQUIRES_NEW)}, así que la sesión de Hibernate —y con
 * ella la resolución del tenant— se abre ANTES de que corra su primera línea.
 * Hay que imponerlo antes de cruzar el proxy, que es exactamente lo que hacen
 * {@code MediaOrphanWorker} y los demás workers de fondo.
 *
 * <p>De ahí la separación: este pone el hilo y el tenant, y
 * {@link MiniaturaDeVideo} hace el trabajo dentro de su transacción.
 */
@Service
public class MiniaturasEnSegundoPlano {

    private final MiniaturaDeVideo miniaturas;

    public MiniaturasEnSegundoPlano(MiniaturaDeVideo miniaturas) {
        this.miniaturas = miniaturas;
    }

    /**
     * @param tenantId el workspace del archivo. Se pasa a mano porque en este
     *                 hilo no hay usuario del cual deducirlo.
     */
    @Async("mediaExecutor")
    public void sacar(UUID assetId, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }
        TenantIdentifierResolver.comoTenant(tenantId, () -> miniaturas.generar(assetId));
    }
}
