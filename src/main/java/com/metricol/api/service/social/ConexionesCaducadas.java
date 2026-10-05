package com.metricol.api.service.social;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.metricol.api.entity.Post;
import com.metricol.api.entity.PostTarget;
import com.metricol.api.entity.SocialConnectionCheck;
import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostStatus;
import com.metricol.api.repository.PostRepository;
import com.metricol.api.repository.PostTargetRepository;
import com.metricol.api.repository.SocialConnectionCheckRepository;
import com.metricol.api.service.avisos.AvisosPush;
import com.metricol.api.service.publishing.PublishQueueService;

/**
 * Las redes cuya conexión caducó: marcarlas, avisar y, al reconectarlas,
 * sacar lo que se quedó esperando.
 *
 * <p>Una conexión caduca sin aviso (Instagram vence sus permisos cada tanto).
 * Antes la cuenta seguía "Conectada", las publicaciones fallaban una tras otra
 * con el motivo escondido en cada una, y al reconectar había que reintentarlas
 * a mano (Juan Rodríguez, 5 oct 2026). Ahora:
 * <ol>
 * <li>El primer rechazo por conexión marca la red ({@code falloPorConexionEn})
 * y avisa al teléfono.</li>
 * <li>Mientras esté marcada no se le manda nada: lo que iba a salir ahí espera,
 * con el porqué, en vez de fallar contra la red otra vez.</li>
 * <li>Al reconectarla, lo de los últimos {@value #DIAS_PARA_REINTENTAR} días
 * sale solo; lo más viejo pregunta, porque publicar algo de hace semanas puede
 * no tener sentido.</li>
 * </ol>
 */
@Service
public class ConexionesCaducadas {

    private static final Logger log = LoggerFactory.getLogger(ConexionesCaducadas.class);

    /** Lo que dice la publicación cuando la red la rechazó por la conexión. */
    public static final String CADUCO = "La conexion con la red caduco. Vuelve a conectarla desde Redes.";

    static final int DIAS_PARA_REINTENTAR = 7;

    private final SocialConnectionCheckRepository checks;
    private final PostTargetRepository destinos;
    private final PostRepository posts;
    private final PublishQueueService cola;
    private final AvisosPush avisos;

    public ConexionesCaducadas(SocialConnectionCheckRepository checks, PostTargetRepository destinos,
            PostRepository posts, PublishQueueService cola, AvisosPush avisos) {
        this.checks = checks;
        this.destinos = destinos;
        this.posts = posts;
        this.cola = cola;
        this.avisos = avisos;
    }

    /** Lo que dice una publicación que espera a que se reconecte la red. */
    public static String esperando(Platform red) {
        return red.getLabel() + " necesita reconectarse. En cuanto la reconectes desde Redes, sale sola.";
    }

    static String clave(Platform red) {
        return red.name().toLowerCase(Locale.ROOT);
    }

    /** ¿Hay que reconectarla antes de mandarle algo? (en el espacio actual) */
    public boolean necesitaReconectar(Platform red) {
        return checks.findByPlatform(clave(red)).map(SocialConnectionCheck::necesitaReconectar).orElse(false);
    }

    /** Una red rechazó una publicación por la conexión: la red queda marcada y, la primera vez, se avisa. */
    public void marcarFallo(Platform red, UUID workspaceId) {
        SocialConnectionCheck check = checks.findByPlatform(clave(red))
                .orElseGet(() -> SocialConnectionCheck.builder().platform(clave(red)).build());
        boolean nueva = check.getFalloPorConexionEn() == null;
        if (nueva) {
            check.setFalloPorConexionEn(LocalDateTime.now());
            checks.save(check);
            log.info("{} quedó por reconectar en {}", red, workspaceId);
            if (workspaceId != null) {
                avisos.avisarAlEquipo(workspaceId, red.getLabel() + " necesita reconectarse",
                        red.getLabel() + " dejó de aceptar publicaciones de Pícale. Reconéctala en Redes y lo pendiente sale solo.",
                        Map.of("tipo", "reconectar", "red", clave(red)));
            }
        }
    }

    /** Se pidió el enlace para (re)conectar estas redes; vacía = todas las del espacio. */
    public void reconectando(Collection<String> redes) {
        LocalDateTime ahora = LocalDateTime.now();
        for (SocialConnectionCheck c : checks.findAll()) {
            if (c.necesitaReconectar() && (redes == null || redes.isEmpty()
                    || redes.stream().anyMatch(r -> r.equalsIgnoreCase(c.getPlatform())))) {
                c.setReconectandoDesde(ahora);
                checks.save(c);
            }
        }
    }

    /**
     * Lo que upload-post dice de una red al verificarla. Si estaba marcada y
     * ahora está sana —el proveedor ya no la da por vencida, o se reconectó
     * después de fallar—, se desmarca y sale lo pendiente.
     *
     * @return si la red sigue por reconectar (para enseñarla así)
     */
    public boolean alVerificar(SocialConnectionCheck check, boolean vencidaEnProveedor, UUID workspaceId) {
        boolean reconecto = false;
        if (!vencidaEnProveedor) {
            if (check.getExpiredSince() != null && check.getFalloPorConexionEn() == null) {
                reconecto = true;
            }
            if (check.getFalloPorConexionEn() != null && check.getReconectandoDesde() != null
                    && check.getReconectandoDesde().isAfter(check.getFalloPorConexionEn())) {
                check.setFalloPorConexionEn(null);
                check.setReconectandoDesde(null);
                reconecto = true;
            }
        }
        if (reconecto && workspaceId != null) {
            try {
                Platform red = Platform.valueOf(check.getPlatform().toUpperCase(Locale.ROOT));
                int salieron = reintentar(red, workspaceId, LocalDateTime.now());
                log.info("{} reconectada en {}: {} publicación(es) de vuelta en la cola.", red, workspaceId, salieron);
            } catch (IllegalArgumentException redQueNoModelamos) {
                // upload-post reporta redes que Pícale todavía no publica.
            }
        }
        return check.getFalloPorConexionEn() != null || vencidaEnProveedor;
    }

    /**
     * Lo que no salió en esa red por la conexión: lo reciente vuelve a la cola
     * (solo a las redes que faltaron; donde ya salió no se repite), lo viejo
     * se queda con una pregunta.
     *
     * @return cuántas publicaciones volvieron a la cola
     */
    int reintentar(Platform red, UUID workspaceId, LocalDateTime ahora) {
        List<PostTarget> fallidas = destinos.fallidasPorConexion(workspaceId.toString(), red, CADUCO);
        List<UUID> encoladas = new ArrayList<>();
        LocalDateTime desde = ahora.minusDays(DIAS_PARA_REINTENTAR);
        for (PostTarget t : fallidas) {
            Post p = t.getPost();
            LocalDateTime cuando = p.getScheduledAt() != null ? p.getScheduledAt() : p.getCreatedAt();
            if (cuando != null && cuando.isBefore(desde)) {
                t.setErrorMessage("No salió mientras " + red.getLabel()
                        + " estaba desconectada. Ya la reconectaste: si todavía va, toca Reintentar.");
                destinos.save(t);
                continue;
            }
            if (encoladas.contains(p.getId())) {
                continue;
            }
            // En cola aunque ya hubiera salido en otras redes: publicada, la cola
            // la daría por terminada. Donde ya salió no se repite (el armado del
            // envío salta los destinos publicados) y al terminar vuelve a "Publicada".
            cola.encolarAhora(p.getId(), workspaceId);
            p.setStatus(PostStatus.QUEUED);
            posts.save(p);
            encoladas.add(p.getId());
        }
        return encoladas.size();
    }

    /** Una red por reconectar, para la franja de Hoy. */
    public record PorReconectar(String red, String nombre, LocalDateTime desde, int pendientes) {
    }

    public List<PorReconectar> porReconectar(UUID workspaceId) {
        List<PorReconectar> lista = new ArrayList<>();
        for (SocialConnectionCheck c : checks.findAll()) {
            if (!c.necesitaReconectar()) {
                continue;
            }
            Platform red;
            try {
                red = Platform.valueOf(c.getPlatform().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            int pendientes = (int) destinos.fallidasPorConexion(workspaceId.toString(), red, CADUCO).stream()
                    .map(t -> t.getPost().getId()).distinct().count();
            LocalDateTime desde = c.getFalloPorConexionEn() != null ? c.getFalloPorConexionEn() : c.getExpiredSince();
            lista.add(new PorReconectar(clave(red), red.getLabel(), desde, pendientes));
        }
        return lista;
    }
}
