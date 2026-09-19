package com.metricol.api.service.campaign;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.entity.User;
import com.metricol.api.enums.Platform;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.request.CampaignImageRequest;
import com.metricol.api.models.response.ContenidoEstadoResponse;
import com.metricol.api.service.campaign.CampaignImageService.Generado;
import com.metricol.api.service.campaign.CampaignImageService.Preparado;
import com.metricol.api.service.campaign.CampaignImageService.Progreso;
import com.metricol.api.service.campaign.CampaignImageService.VarianteGenerada;
import com.metricol.api.service.campaign.Lienzo.Variante;

import jakarta.annotation.PreDestroy;

/**
 * Crea el contenido en segundo plano y deja consultar cómo va.
 *
 * <p>Existe por el corte de Cloudflare: a los 100 segundos de una petición
 * responde 524 y la app se queda sin nada, aunque el servidor siga trabajando.
 * Con varias imágenes y el director de arte, una petición única no cabe. Aquí
 * la petición solo valida y devuelve un identificador en segundos; el trabajo
 * corre aparte y la app pregunta cómo va, viendo aparecer cada versión.
 *
 * <p><b>En memoria, a propósito.</b> Un trabajo dura minutos y solo importa
 * mientras alguien mira la pantalla, así que no justifica una tabla. El costo
 * es que reiniciar el servidor pierde los que estén corriendo (la persona lo
 * vuelve a pedir; las imágenes ya generadas quedaron en Contenido y su gasto
 * anotado). Si algún día hay más de una instancia, esto se mueve a la base.
 *
 * <p><b>Uno a la vez por workspace.</b> El tope diario se cuenta cuando cada
 * imagen termina; sin este candado, cinco trabajos lanzados juntos pasarían
 * todos el tope antes de que el primero anotara nada.
 *
 * <p><b>El workspace se pone a mano.</b> El hilo de fondo no pertenece a ninguna
 * petición, así que Hibernate no sabría a qué workspace leer y escribir; se
 * impone con {@link TenantIdentifierResolver#comoTenant}.
 */
@Service
public class ContenidoJobs {

    private static final Logger log = LoggerFactory.getLogger(ContenidoJobs.class);

    /** Cuánto se conserva un trabajo terminado para que la app lo pueda consultar. */
    static final long RETENCION_MS = 30 * 60_000L;

    private final CampaignImageService generador;
    private final Map<String, Trabajo> trabajos = new ConcurrentHashMap<>();
    private final Set<UUID> workspacesOcupados = ConcurrentHashMap.newKeySet();

    private final ExecutorService ejecutor = Executors.newFixedThreadPool(3, tarea -> {
        Thread hilo = new Thread(tarea, "contenido-job");
        hilo.setDaemon(true);
        return hilo;
    });

    /** El reloj se puede cambiar en las pruebas. */
    LongSupplier reloj = System::currentTimeMillis;

    public ContenidoJobs(CampaignImageService generador) {
        this.generador = generador;
    }

    @PreDestroy
    void cerrar() {
        ejecutor.shutdownNow();
    }

    private enum Estado {
        QUEUED, THINKING, CREATING, READY, FAILED
    }

    /**
     * Valida y arranca. Todo lo que puede estar mal —una red que no admite el
     * formato, el tope del día, una foto ajena— se dice aquí, en la respuesta,
     * y no crea ningún trabajo.
     *
     * @return el identificador para consultar
     */
    public String iniciar(User usuario, CampaignImageRequest peticion) {
        purgar(reloj.getAsLong());

        if (usuario.getWorkspace() == null) {
            throw new IllegalStateException("Elige un espacio de trabajo para crear el contenido.");
        }
        UUID workspace = usuario.getWorkspace().getId();
        if (!workspacesOcupados.add(workspace)) {
            throw new IllegalStateException(
                    "Ya estás creando contenido. Espera a que termine para empezar otro.");
        }

        Trabajo trabajo;
        try {
            Preparado preparado = generador.preparar(usuario, peticion);
            trabajo = new Trabajo(UUID.randomUUID().toString(), workspace, preparado, reloj.getAsLong());
        } catch (RuntimeException ex) {
            workspacesOcupados.remove(workspace);
            throw ex;
        }

        trabajos.put(trabajo.id, trabajo);
        Trabajo elegido = trabajo;
        ejecutor.submit(() -> TenantIdentifierResolver.comoTenant(workspace.toString(), () -> correr(elegido)));
        return trabajo.id;
    }

    private void correr(Trabajo trabajo) {
        try {
            Generado generado = generador.ejecutar(trabajo.preparado, trabajo);
            trabajo.terminar(generado, reloj.getAsLong());
            // Si no salió ninguna versión, no se cobra el crédito.
            if (trabajo.estado == Estado.FAILED) {
                generador.devolverCredito(trabajo.preparado);
            }
        } catch (Throwable ex) {
            log.error("Falló la creación de contenido {}: {}", trabajo.id, ex.toString(), ex);
            trabajo.fallar("No se pudo crear el contenido. Inténtalo de nuevo.", reloj.getAsLong());
            generador.devolverCredito(trabajo.preparado);
        } finally {
            workspacesOcupados.remove(trabajo.workspaceId);
        }
    }

    /** Cómo va un trabajo. Uno de otro workspace se ve igual que uno que no existe. */
    public ContenidoEstadoResponse estado(User usuario, String id) {
        purgar(reloj.getAsLong());
        Trabajo trabajo = trabajos.get(id);
        UUID workspace = usuario.getWorkspace() == null ? null : usuario.getWorkspace().getId();
        if (trabajo == null || workspace == null || !trabajo.workspaceId.equals(workspace)) {
            throw new ResourceNotFoundException("Ese contenido ya no está disponible.");
        }
        return trabajo.instantanea();
    }

    /** Tira los trabajos terminados hace más de {@link #RETENCION_MS}. */
    void purgar(long ahora) {
        trabajos.values().removeIf(t -> t.terminadoEn > 0 && ahora - t.terminadoEn > RETENCION_MS);
    }

    /** Un trabajo en marcha. Es también quien recibe el avance de quien ejecuta. */
    private static final class Trabajo implements Progreso {
        final String id;
        final UUID workspaceId;
        final Preparado preparado;
        final long creadoEn;

        private volatile Estado estado = Estado.QUEUED;
        private volatile String etapa = "En cola…";
        private final List<VarianteGenerada> listas = new CopyOnWriteArrayList<>();
        private volatile Generado resultado;
        private volatile String error;
        volatile long terminadoEn;

        Trabajo(String id, UUID workspaceId, Preparado preparado, long creadoEn) {
            this.id = id;
            this.workspaceId = workspaceId;
            this.preparado = preparado;
            this.creadoEn = creadoEn;
        }

        @Override
        public void etapa(String nueva) {
            this.etapa = nueva;
            this.estado = nueva.startsWith("Pensando") ? Estado.THINKING : Estado.CREATING;
        }

        @Override
        public void varianteLista(VarianteGenerada variante) {
            listas.add(variante);
        }

        /**
         * El estado se escribe AL FINAL y se lee AL PRINCIPIO ({@link #instantanea}):
         * quien ve READY o FAILED tiene garantizado ver ya el resultado, el error y
         * la etapa. Al revés, la app podía ver "listo" con las versiones vacías.
         */
        void terminar(Generado generado, long ahora) {
            boolean algunaSalio = generado.variantes().stream().anyMatch(v -> v.causa() == null);
            this.resultado = generado;
            this.terminadoEn = ahora;
            if (algunaSalio) {
                this.etapa = "Listo";
                this.estado = Estado.READY;
            } else {
                this.error = generado.variantes().get(0).error();
                this.etapa = "No se pudo crear";
                this.estado = Estado.FAILED;
            }
        }

        void fallar(String mensaje, long ahora) {
            this.error = mensaje;
            this.etapa = "No se pudo crear";
            this.terminadoEn = ahora;
            this.estado = Estado.FAILED;
        }

        ContenidoEstadoResponse instantanea() {
            // Primero el estado y después todo lo demás (ver terminar).
            Estado estadoAhora = estado;
            Generado g = resultado;
            String etapaAhora = etapa;
            String errorAhora = error;
            // Terminado, manda el resultado final; mientras corre, lo que se fue
            // avisando. Así el estado final nunca depende de que no se perdiera un aviso.
            List<VarianteGenerada> reportadas = g != null ? g.variantes() : listas;
            List<ContenidoEstadoResponse.Version> versiones = new ArrayList<>();
            for (Variante variante : preparado.variantes()) {
                VarianteGenerada lista = reportadas.stream().filter(v -> v.id().equals(variante.id())).findFirst()
                        .orElse(null);
                String estadoVersion = lista == null ? "PENDING" : lista.causa() == null ? "READY" : "FAILED";
                versiones.add(new ContenidoEstadoResponse.Version(
                        variante.id(),
                        variante.lienzo().etiqueta,
                        variante.redes().stream().map(Platform::name).toList(),
                        estadoVersion,
                        lista == null ? List.of() : lista.urls(),
                        lista == null ? null : lista.error()));
            }

            Map<String, String> captions = new LinkedHashMap<>();
            if (g != null) {
                g.captionsPorRed().forEach((red, texto) -> captions.put(red.name(), texto));
            }
            return new ContenidoEstadoResponse(
                    id,
                    estadoAhora.name(),
                    etapaAhora,
                    versiones,
                    g == null ? null : g.titular(),
                    g == null ? null : g.subtitulo(),
                    captions,
                    g == null || g.restantes() == Integer.MAX_VALUE ? null : g.restantes(),
                    errorAhora);
        }
    }
}
