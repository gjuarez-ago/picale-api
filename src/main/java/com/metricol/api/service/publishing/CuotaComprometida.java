package com.metricol.api.service.publishing;

import com.metricol.api.service.CuentaSinLimites;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.metricol.api.entity.Post;
import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostStatus;
import com.metricol.api.enums.PostTargetStatus;
import com.metricol.api.exception.QuotaExceededException;
import com.metricol.api.repository.DailyPublishUsageRepository;
import com.metricol.api.repository.PostRepository;
import com.metricol.api.repository.PostTargetRepository;
import com.metricol.api.service.limits.LimitesConfigurables;

/**
 * Lo que hay que comprobar ANTES de guardar una publicación que va a salir.
 *
 * <p>La cuota diaria ya se hacía respetar al publicar: la red se agotaba, el
 * destino se aplazaba a mañana, y nadie se pasaba. Pero eso llegaba tarde. Al
 * programar no se miraba nada, así que alguien podía dejar trescientas
 * publicaciones para el mismo martes; salían veinticinco, las demás se corrían
 * al miércoles, luego al jueves… y él creía que habían salido. La pila crecía
 * sin fin y el proveedor recibía cada día exactamente el máximo.
 *
 * <p>Aquí se dice <b>no</b> en el momento en que todavía se puede hacer algo:
 * cambiar el día, quitar una red. Tres preguntas, en orden de lo barato a lo
 * caro:
 * <ol>
 *   <li>¿Cuántas tiene esperando salir este workspace? (tope de pendientes)</li>
 *   <li>¿Cuántas creó hoy? (tope por día)</li>
 *   <li>Para cada red elegida, ¿cuántas hay ya publicadas o comprometidas
 *       para ese día? (cuota de la red)</li>
 * </ol>
 *
 * <p>Nada de esto reemplaza la reserva atómica del publicado, que sigue siendo
 * la última defensa contra dos guardados a la vez. Esto es la primera.
 */
@Service
public class CuotaComprometida {

    /** Las publicaciones que todavía van a gastar cuota. */
    static final List<PostStatus> VIVAS = List.of(
            PostStatus.QUEUED, PostStatus.SCHEDULED, PostStatus.PUBLISHING);

    /** Los destinos que todavía van a pedir un hueco a su red. */
    static final List<PostTargetStatus> PENDIENTES = List.of(
            PostTargetStatus.QUEUED, PostTargetStatus.PUBLISHING, PostTargetStatus.SKIPPED);

    /** Un id que no existe, para «sin excluir ninguna» sin pasar un nulo a la consulta. */
    private static final UUID NINGUNA = new UUID(0L, 0L);

    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("d 'de' MMMM", Locale.forLanguageTag("es"));

    private final PostRepository posts;
    private final PostTargetRepository destinos;
    private final DailyPublishUsageRepository consumo;
    private final LimitesConfigurables limites;
    private final CuentaSinLimites sinLimites;

    public CuotaComprometida(
            PostRepository posts,
            PostTargetRepository destinos,
            DailyPublishUsageRepository consumo,
            LimitesConfigurables limites,
            CuentaSinLimites sinLimites) {
        this.sinLimites = sinLimites;
        this.posts = posts;
        this.destinos = destinos;
        this.consumo = consumo;
        this.limites = limites;
    }

    /**
     * Lanza {@link QuotaExceededException} si esta publicación no cabe.
     *
     * @param post      la publicación ya armada (con estado y destinos), sin guardar
     * @param workspace el workspace de quien guarda
     * @param excluir   el id de la publicación que se está editando, o {@code null}
     *                  si es nueva: la versión anterior no debe contarse contra sí misma
     */
    public void exigirCupo(Post post, UUID workspace, UUID excluir) {
        if (post.getStatus() == PostStatus.DRAFT) {
            return; // Un borrador no compromete nada.
        }
        if (sinLimites.deWorkspace(workspace)) {
            return; // La cuenta de la casa no tiene tope.
        }

        boolean esNueva = excluir == null;
        exigirPendientes(excluir);
        if (esNueva) {
            exigirCreadasHoy();
        }
        exigirRedes(post, workspace, excluir);
    }

    private void exigirPendientes(UUID excluir) {
        int tope = limites.maxPendientes();
        if (tope <= 0) {
            return;
        }
        long pendientes = posts.countByStatusInAndIdNot(VIVAS, excluir == null ? NINGUNA : excluir);
        if (pendientes >= tope) {
            throw new QuotaExceededException("POSTS_PENDING_LIMIT",
                    "Tienes " + pendientes + " publicaciones esperando salir, que es el máximo. "
                            + "Espera a que salgan o borra alguna antes de programar más.");
        }
    }

    private void exigirCreadasHoy() {
        int tope = limites.maxPorDia();
        if (tope <= 0) {
            return;
        }
        LocalDate hoy = LocalDate.now();
        long creadas = posts.countByCreatedAtBetween(hoy.atStartOfDay(), hoy.plusDays(1).atStartOfDay());
        if (creadas >= tope) {
            throw new QuotaExceededException("POSTS_DAILY_LIMIT",
                    "Hoy ya creaste " + creadas + " publicaciones, que es el máximo por día. "
                            + "Mañana puedes seguir.");
        }
    }

    private void exigirRedes(Post post, UUID workspace, UUID excluir) {
        LocalDate dia = post.getScheduledAt() == null ? LocalDate.now() : post.getScheduledAt().toLocalDate();
        boolean esHoy = dia.equals(LocalDate.now());
        LocalDateTime desde = dia.atStartOfDay();
        LocalDateTime hasta = dia.plusDays(1).atStartOfDay();
        String tenant = workspace.toString();

        Set<Platform> redes = new LinkedHashSet<>();
        post.getTargets().forEach(t -> redes.add(t.getSocialAccount().getPlatform()));

        for (Platform red : redes) {
            Integer tope = limites.cuotaDiaria(red);
            if (tope == null) {
                continue;
            }

            long comprometidas = destinos.countComprometidas(
                    tenant, red, VIVAS, PENDIENTES, desde, hasta, excluir == null ? NINGUNA : excluir);

            // Lo ya publicado hoy solo cuenta si la publicación es para hoy:
            // el contador de mañana empieza en cero.
            long publicadasHoy = esHoy
                    ? consumo.findByWorkspaceIdAndPlatformAndDay(workspace, red, dia)
                            .map(fila -> (long) fila.getUsed()).orElse(0L)
                    : 0L;

            if (!cabe(comprometidas, publicadasHoy, tope)) {
                throw new QuotaExceededException("DAILY_QUOTA_EXCEEDED", mensaje(red, tope, dia, esHoy));
            }
        }
    }

    /** ¿Cabe una más? Separado para poder probarlo sin base. */
    static boolean cabe(long comprometidas, long publicadasHoy, int tope) {
        return comprometidas + publicadasHoy + 1 <= tope;
    }

    private static String mensaje(Platform red, int tope, LocalDate dia, boolean esHoy) {
        if (esHoy) {
            return red.getLabel() + " ya tiene comprometidas hoy las " + tope
                    + " publicaciones que acepta en un día. Prográmala para mañana o quita esa red.";
        }
        return red.getLabel() + " ya tiene " + tope + " publicaciones para el " + dia.format(DIA)
                + ", que es todo lo que acepta en un día. Elige otro día o quita esa red.";
    }
}
