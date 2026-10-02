package com.metricol.api.service.root;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.metricol.api.entity.Organization;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.exception.ConflictoException;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.repository.OrganizationRepository;
import com.metricol.api.repository.UserRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.billing.StripeClient;
import com.metricol.api.service.media.AdaptadorDeImagenes;
import com.metricol.api.service.media.MiniaturaDeVideo;
import com.metricol.api.service.social.UploadPostConnectService;
import com.metricol.api.service.storage.R2StorageService;

/**
 * El borrado de verdad desde la Administración: una organización, un espacio
 * o una persona, con todo lo suyo. Solo lo pide la cuenta raíz
 * ({@link RootAccessService#exigirRaiz}) y no se puede deshacer.
 *
 * <p>El orden importa:
 * <ol>
 * <li><b>Stripe primero.</b> Se cancelan YA las suscripciones. Si Stripe
 * falla, no se borra nada: peor que no borrar es seguir cobrando algo que ya
 * no existe.</li>
 * <li><b>La base, en una sola transacción.</b> O se va todo o no se va
 * nada.</li>
 * <li><b>Después, R2 y upload-post.</b> Si alguno falla, el borrado ya pasó:
 * se avisa en {@link Resultado#avisos()} para limpiarlo a mano, sin dejar a
 * medias la base.</li>
 * </ol>
 *
 * <p>En SQL y no con los repositorios: varias tablas llevan {@code @TenantId}
 * y Hibernate las filtraría por el espacio de quien pide (la raíz), no por el
 * que se borra. La lista de tablas la vigila {@code EliminacionDefinitivaTest}
 * contra el esquema real, para que una tabla nueva no quede huérfana sin que
 * nadie se entere.
 */
@Service
public class EliminacionDefinitiva {

    private static final Logger log = LoggerFactory.getLogger(EliminacionDefinitiva.class);

    /** Lo que se borró, para enseñarlo, y lo que no se pudo limpiar fuera de la base. */
    public record Resultado(String que, String nombre, int espacios, int personas, int publicaciones,
            int archivos, List<String> avisos) {
    }

    private final NamedParameterJdbcTemplate sql;
    private final TransactionTemplate transaccion;
    private final OrganizationRepository organizaciones;
    private final WorkspaceRepository workspaces;
    private final UserRepository usuarios;
    private final AdministradoresService administradores;
    private final StripeClient stripe;
    private final R2StorageService storage;
    private final UploadPostConnectService uploadPost;

    public EliminacionDefinitiva(NamedParameterJdbcTemplate sql, TransactionTemplate transaccion,
            OrganizationRepository organizaciones, WorkspaceRepository workspaces, UserRepository usuarios,
            AdministradoresService administradores, StripeClient stripe, R2StorageService storage,
            UploadPostConnectService uploadPost) {
        this.sql = sql;
        this.transaccion = transaccion;
        this.organizaciones = organizaciones;
        this.workspaces = workspaces;
        this.usuarios = usuarios;
        this.administradores = administradores;
        this.stripe = stripe;
        this.storage = storage;
        this.uploadPost = uploadPost;
    }

    // ------------------------------------------------------------------ un espacio

    public Resultado eliminarEspacio(User quien, UUID workspaceId, String confirmacion) {
        Workspace espacio = workspaces.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Ese espacio no existe."));
        exigirConfirmacion(confirmacion, espacio.getName(), "el nombre del espacio");
        UUID orgId = espacio.getOrganization() == null ? null : espacio.getOrganization().getId();
        if (orgId != null && contar("select count(*) from workspaces where organization_id = :org",
                Map.of("org", orgId)) <= 1) {
            throw new ConflictoException("ULTIMO_ESPACIO",
                    "Es el único espacio de su organización. Elimina la organización completa.");
        }

        // Quien trabaja en este espacio pasa a otro suyo; si alguien no tiene a
        // dónde ir, no se borra: quedaría con una cuenta que no abre.
        Map<UUID, UUID> mudanzas = new java.util.LinkedHashMap<>();
        List<String> sinDestino = new ArrayList<>();
        for (Map<String, Object> u : sql.queryForList("select id, email from users where workspace_id = :ws",
                Map.of("ws", workspaceId))) {
            UUID userId = (UUID) u.get("id");
            UUID destino = destinoFuera(userId, List.of(workspaceId));
            if (destino == null) {
                sinDestino.add(String.valueOf(u.get("email")));
            } else {
                mudanzas.put(userId, destino);
            }
        }
        if (!sinDestino.isEmpty()) {
            throw new ConflictoException("SIN_OTRO_ESPACIO", "Estas personas solo tienen este espacio: "
                    + String.join(", ", sinDestino) + ". Elimínalas o dales otro espacio primero.");
        }

        cancelarSuscripciones("select stripe_subscription_id from licenses where workspace_id = :id "
                + "and stripe_subscription_id is not null", workspaceId);
        Limpieza fuera = limpiezaDe(List.of(espacio), Set.of());

        int[] cuentas = transaccion.execute(estado -> {
            mudanzas.forEach((userId, destino) -> sql.update(
                    "update users set workspace_id = :d where id = :u", Map.of("d", destino, "u", userId)));
            int[] c = borrarContenido(workspaceId);
            sql.update("delete from workspaces where id = :ws", Map.of("ws", workspaceId));
            return c;
        });

        List<String> avisos = fuera.ejecutar();
        log.warn("{} eliminó DEFINITIVAMENTE el espacio «{}» ({}): {} publicaciones, {} archivos",
                quien.getEmail(), espacio.getName(), workspaceId, cuentas[0], cuentas[1]);
        return new Resultado("ESPACIO", espacio.getName(), 1, 0, cuentas[0], cuentas[1], avisos);
    }

    // ------------------------------------------------------------------ una organización

    public Resultado eliminarOrganizacion(User quien, UUID orgId, String confirmacion) {
        Organization org = organizaciones.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Esa organización no existe."));
        exigirConfirmacion(confirmacion, org.getName(), "el nombre de la organización");

        List<UUID> espaciosIds = sql.queryForList("select id from workspaces where organization_id = :org",
                Map.of("org", orgId), UUID.class);
        List<Workspace> espacios = workspaces.findAllById(espaciosIds);

        // Su gente: los miembros, y quien tenga su espacio activo ahí aunque no lo sea.
        Set<UUID> gente = new LinkedHashSet<>(sql.queryForList(
                "select user_id from organization_members where organization_id = :org", Map.of("org", orgId),
                UUID.class));
        if (!espaciosIds.isEmpty()) {
            gente.addAll(sql.queryForList("select id from users where workspace_id in (:ws)",
                    Map.of("ws", espaciosIds), UUID.class));
        }

        Set<UUID> aBorrar = new LinkedHashSet<>();
        Map<UUID, UUID> mudanzas = new java.util.LinkedHashMap<>();
        List<String> sinDestino = new ArrayList<>();
        for (UUID userId : gente) {
            User u = usuarios.findById(userId).orElse(null);
            if (u == null) {
                continue;
            }
            if (administradores.esRaiz(u) || u.getId().equals(quien.getId())) {
                throw new ConflictoException("ORG_DE_LA_RAIZ",
                        "En esta organización está la cuenta raíz: no se puede eliminar.");
            }
            boolean enOtra = contar("select count(*) from organization_members where user_id = :u "
                    + "and organization_id <> :org", Map.of("u", userId, "org", orgId)) > 0;
            if (!enOtra) {
                aBorrar.add(userId);
                continue;
            }
            // Sigue en otra organización: se queda, sin lo de esta.
            if (u.getWorkspace() != null && espaciosIds.contains(u.getWorkspace().getId())) {
                UUID destino = destinoFuera(userId, espaciosIds);
                if (destino == null) {
                    sinDestino.add(u.getEmail());
                } else {
                    mudanzas.put(userId, destino);
                }
            }
        }
        if (!sinDestino.isEmpty()) {
            throw new ConflictoException("SIN_OTRO_ESPACIO", "Estas personas están en otra organización pero "
                    + "sin un espacio a dónde pasar: " + String.join(", ", sinDestino) + ".");
        }

        cancelarSuscripciones("select stripe_subscription_id from licenses where organization_id = :id "
                + "and stripe_subscription_id is not null", orgId);
        Limpieza fuera = limpiezaDe(espacios, Set.copyOf(espaciosIds));

        int[] cuentas = transaccion.execute(estado -> {
            mudanzas.forEach((userId, destino) -> sql.update(
                    "update users set workspace_id = :d where id = :u", Map.of("d", destino, "u", userId)));
            int[] total = { 0, 0 };
            for (UUID ws : espaciosIds) {
                int[] c = borrarContenido(ws);
                total[0] += c[0];
                total[1] += c[1];
            }
            MapSqlParameterSource p = new MapSqlParameterSource("org", orgId);
            sql.update("delete from invitation_workspaces where invitation_id in "
                    + "(select id from invitations where organization_id = :org)", p);
            sql.update("delete from invitations where organization_id = :org", p);
            for (UUID userId : aBorrar) {
                borrarPersona(userId);
            }
            sql.update("delete from organization_members where organization_id = :org", p);
            sql.update("delete from licenses where organization_id = :org", p);
            sql.update("delete from workspaces where organization_id = :org", p);
            sql.update("delete from organizations where id = :org", p);
            return total;
        });

        List<String> avisos = fuera.ejecutar();
        log.warn("{} eliminó DEFINITIVAMENTE la organización «{}» ({}): {} espacios, {} personas, "
                + "{} publicaciones, {} archivos", quien.getEmail(), org.getName(), orgId, espaciosIds.size(),
                aBorrar.size(), cuentas[0], cuentas[1]);
        return new Resultado("ORGANIZACION", org.getName(), espaciosIds.size(), aBorrar.size(), cuentas[0],
                cuentas[1], avisos);
    }

    // ------------------------------------------------------------------ una persona

    public Resultado eliminarUsuario(User quien, UUID userId, String confirmacion) {
        User u = usuarios.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Esa cuenta no existe."));
        exigirConfirmacion(confirmacion, u.getEmail(), "el correo de la cuenta");
        if (u.getId().equals(quien.getId())) {
            throw new ConflictoException("ES_USTED", "No puedes eliminar tu propia cuenta.");
        }
        if (administradores.esRaiz(u)) {
            throw new ConflictoException("ES_RAIZ", "La cuenta raíz no se elimina.");
        }
        List<String> suyas = sql.queryForList("select o.name from organization_members m "
                + "join organizations o on o.id = m.organization_id where m.user_id = :u and m.role = 'OWNER'",
                Map.of("u", userId), String.class);
        if (!suyas.isEmpty()) {
            throw new ConflictoException("ES_DUENO", "Es dueño de «" + String.join("», «", suyas)
                    + "». Elimina la organización (se lleva a su dueño) o pásala a otra persona.");
        }

        transaccion.executeWithoutResult(estado -> borrarPersona(userId));
        log.warn("{} eliminó DEFINITIVAMENTE la cuenta {} ({})", quien.getEmail(), u.getEmail(), userId);
        return new Resultado("USUARIO", u.getEmail(), 0, 1, 0, 0, List.of());
    }

    // ------------------------------------------------------------------ lo de abajo

    /**
     * Todo lo de un espacio menos su fila (y la de su gente): publicaciones,
     * archivos, redes, cola, consumo, créditos, licencia, membresías.
     *
     * @return {publicaciones, archivos}
     */
    int[] borrarContenido(UUID workspaceId) {
        MapSqlParameterSource ws = new MapSqlParameterSource("ws", workspaceId);
        MapSqlParameterSource t = new MapSqlParameterSource("t", workspaceId.toString());
        int publicaciones = contar("select count(*) from posts where tenant_id = :t", t.getValues());
        int archivos = contar("select count(*) from media_assets where tenant_id = :t", t.getValues());

        sql.update("delete from post_targets where post_id in (select id from posts where tenant_id = :t)", t);
        sql.update("delete from post_media where post_id in (select id from posts where tenant_id = :t)", t);
        sql.update("delete from publish_jobs where workspace_id = :ws", ws);
        sql.update("delete from posts where tenant_id = :t", t);
        sql.update("delete from social_connection_checks where tenant_id = :t", t);
        sql.update("delete from social_accounts where tenant_id = :t", t);
        sql.update("delete from media_assets where tenant_id = :t", t);
        sql.update("delete from ai_usage where workspace_id = :ws", ws);
        // La bitácora de lo que hicieron las IA en ese espacio se va con él.
        sql.update("delete from acciones_ia where workspace_id = :ws", ws);
        sql.update("delete from daily_publish_usage where workspace_id = :ws", ws);
        sql.update("delete from credit_movements where workspace_id = :ws", ws);
        sql.update("delete from image_credits where workspace_id = :ws", ws);
        sql.update("delete from licenses where workspace_id = :ws", ws);
        sql.update("delete from invitation_workspaces where workspace_id = :ws", ws);
        if (existe("workspace_invitations")) {
            sql.update("delete from workspace_invitations where workspace_id = :ws", ws);
        }
        sql.update("delete from workspace_members where workspace_id = :ws", ws);
        return new int[] { publicaciones, archivos };
    }

    /** Una persona y lo que la nombra. Su espacio activo no se toca: es de quien sea. */
    void borrarPersona(UUID userId) {
        MapSqlParameterSource u = new MapSqlParameterSource("u", userId);
        String correo = sql.queryForObject("select email from users where id = :u", u, String.class);
        sql.update("update invitations set invited_by = null where invited_by = :u", u);
        if (existe("workspace_invitations")) {
            sql.update("delete from workspace_invitations where invited_by = :u", u);
        }
        sql.update("delete from workspace_members where user_id = :u", u);
        sql.update("delete from organization_members where user_id = :u", u);
        // Lo que una IA hizo en su nombre en espacios que siguen vivos se queda
        // en la bitácora de esos espacios, pero ya sin nombrarla.
        sql.update("update acciones_ia set user_id = null, user_email = null where user_id = :u", u);
        // Sus IA conectadas se van con ella: sin persona no hay a quién representar.
        sql.update("delete from conexiones_ia where user_id = :u", u);
        sql.update("delete from email_verification_codes where lower(email) = lower(:e)",
                new MapSqlParameterSource("e", correo == null ? "" : correo));
        sql.update("delete from users where id = :u", u);
    }

    /**
     * A qué espacio puede pasar alguien que pierde el suyo: uno donde esté
     * apuntado, o uno de una organización que administre. Nunca uno de
     * {@code fuera}. Los archivados, al final.
     */
    private UUID destinoFuera(UUID userId, List<UUID> fuera) {
        MapSqlParameterSource p = new MapSqlParameterSource("u", userId).addValue("fuera", fuera);
        List<UUID> suyo = sql.queryForList("select w.id from workspace_members m join workspaces w on w.id = m.workspace_id "
                + "where m.user_id = :u and w.id not in (:fuera) order by (w.archived_at is not null), w.created_at",
                p, UUID.class);
        if (!suyo.isEmpty()) {
            return suyo.get(0);
        }
        List<UUID> administrado = sql.queryForList("select w.id from organization_members m "
                + "join workspaces w on w.organization_id = m.organization_id "
                + "where m.user_id = :u and m.role in ('OWNER', 'ADMIN') and w.id not in (:fuera) "
                + "order by (w.archived_at is not null), w.created_at", p, UUID.class);
        return administrado.isEmpty() ? null : administrado.get(0);
    }

    private void cancelarSuscripciones(String consulta, UUID id) {
        List<String> suscripciones = sql.queryForList(consulta, Map.of("id", id), String.class);
        if (suscripciones.isEmpty()) {
            return;
        }
        if (!stripe.disponible()) {
            throw new ConflictoException("STRIPE_NO_DISPONIBLE", "Tiene suscripciones en Stripe y los cobros "
                    + "no están configurados aquí: no se puede cancelarlas, así que no se elimina.");
        }
        for (String s : suscripciones) {
            // Si falla, la excepción sale y no se borra nada.
            stripe.cancelarYa(s);
        }
    }

    private void exigirConfirmacion(String escrito, String esperado, String que) {
        String a = escrito == null ? "" : escrito.trim();
        String b = esperado == null ? "" : esperado.trim();
        if (b.isEmpty() || !a.equalsIgnoreCase(b)) {
            throw new IllegalArgumentException("Para confirmar escribe exactamente " + que + ": «" + b + "».");
        }
    }

    /**
     * Tablas de antes que ya no tienen entidad pero pueden seguir en una base
     * vieja ({@code ddl-auto=update} no borra tablas): se limpian si están.
     */
    private boolean existe(String tabla) {
        return contar("select count(*) from information_schema.tables where table_schema = current_schema() "
                + "and table_name = :t", Map.of("t", tabla)) > 0;
    }

    private int contar(String consulta, Map<String, ?> params) {
        Long n = sql.queryForObject(consulta, params, Long.class);
        return n == null ? 0 : n.intValue();
    }

    /** Lo que se limpia fuera de la base, ya calculado antes de borrarla. */
    private Limpieza limpiezaDe(List<Workspace> espacios, Set<UUID> tambienSeVan) {
        List<String> prefijos = new ArrayList<>();
        List<String> perfiles = new ArrayList<>();
        Set<UUID> seVan = new java.util.HashSet<>(tambienSeVan);
        espacios.forEach(e -> seVan.add(e.getId()));
        for (Workspace e : espacios) {
            UUID id = e.getId();
            prefijos.add(R2StorageService.CARPETA_MEDIA + "/" + id + "/");
            prefijos.add(R2StorageService.CARPETA_LOGOS + "/" + id + "/");
            prefijos.add(AdaptadorDeImagenes.prefijoDe(id));
            String miniatura = MiniaturaDeVideo.claveDe(R2StorageService.CARPETA_MEDIA + "/" + id + "/");
            prefijos.add(miniatura.substring(0, miniatura.length() - ".jpg".length()));

            String perfil = e.getUploadPostProfile() == null || e.getUploadPostProfile().isBlank()
                    ? id.toString() : e.getUploadPostProfile();
            // Un perfil que comparte con un espacio que se queda no se borra.
            List<UUID> loUsan = sql.queryForList("select id from workspaces where lower(trim(upload_post_profile)) = "
                    + "lower(trim(:p)) or cast(id as varchar) = lower(trim(:p))", Map.of("p", perfil), UUID.class);
            if (seVan.containsAll(loUsan)) {
                perfiles.add(perfil);
            }
        }
        return new Limpieza(prefijos, perfiles);
    }

    private final class Limpieza {
        private final List<String> prefijos;
        private final List<String> perfiles;

        Limpieza(List<String> prefijos, List<String> perfiles) {
            this.prefijos = prefijos;
            this.perfiles = perfiles;
        }

        List<String> ejecutar() {
            List<String> avisos = new ArrayList<>();
            for (String prefijo : prefijos) {
                try {
                    storage.borrarPrefijo(prefijo);
                } catch (RuntimeException ex) {
                    log.error("No se pudo borrar {} de R2: {}", prefijo, ex.toString());
                    avisos.add("No se pudieron borrar los archivos de " + prefijo + " en R2.");
                }
            }
            for (String perfil : perfiles) {
                if (!uploadPost.borrarPerfil(perfil)) {
                    avisos.add("No se pudo borrar el perfil «" + perfil + "» de upload-post.");
                }
            }
            return avisos;
        }
    }
}
