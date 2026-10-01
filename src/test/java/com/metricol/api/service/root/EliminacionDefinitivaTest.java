package com.metricol.api.service.root;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.entity.ImageCredits;
import com.metricol.api.entity.License;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.Organization;
import com.metricol.api.entity.OrganizationMember;
import com.metricol.api.entity.Post;
import com.metricol.api.entity.PostTarget;
import com.metricol.api.entity.SocialAccount;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.entity.WorkspaceMember;
import com.metricol.api.enums.LicenseStatus;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.enums.OrgRole;
import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostStatus;
import com.metricol.api.enums.Role;
import com.metricol.api.enums.SocialAccountStatus;
import com.metricol.api.exception.ConflictoException;
import com.metricol.api.repository.ImageCreditsRepository;
import com.metricol.api.repository.LicenseRepository;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.OrganizationMemberRepository;
import com.metricol.api.repository.OrganizationRepository;
import com.metricol.api.repository.PostRepository;
import com.metricol.api.repository.SocialAccountRepository;
import com.metricol.api.repository.UserRepository;
import com.metricol.api.repository.WorkspaceMemberRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.billing.StripeClient;
import com.metricol.api.service.social.UploadPostConnectService;
import com.metricol.api.service.storage.R2StorageService;

/**
 * El borrado definitivo contra la base de verdad. Stripe, R2 y upload-post van
 * doblados: el .env local apunta al bucket de producción.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "app.publishing.queue.poll-delay-ms=3600000",
        "app.publishing.queue.rescue-delay-ms=3600000",
        "app.scheduling.poll-delay-ms=3600000",
        "app.media.orphan-delay-ms=3600000",
        "app.media.unused-delay-ms=3600000",
        "app.billing.sweep-initial-delay-ms=3600000",
        "app.agente.enabled=false"
})
class EliminacionDefinitivaTest {

    @Autowired private EliminacionDefinitiva eliminacion;
    @Autowired private NamedParameterJdbcTemplate sql;
    @Autowired private OrganizationRepository organizaciones;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private UserRepository usuarios;
    @Autowired private OrganizationMemberRepository miembrosOrg;
    @Autowired private WorkspaceMemberRepository miembrosWs;
    @Autowired private LicenseRepository licencias;
    @Autowired private ImageCreditsRepository creditos;
    @Autowired private PostRepository posts;
    @Autowired private MediaAssetRepository assets;
    @Autowired private SocialAccountRepository cuentas;

    @MockitoBean private StripeClient stripe;
    @MockitoBean private R2StorageService storage;
    @MockitoBean private UploadPostConnectService uploadPost;
    @MockitoBean private AdministradoresService administradores;

    private final List<UUID> orgsCreadas = new ArrayList<>();
    private User raiz;

    // La raíz vive en su propia organización, aparte de lo que se borra.
    private Organization casa;

    @BeforeEach
    void preparar() {
        casa = org("Casa raíz " + UUID.randomUUID());
        Workspace w = espacio(casa, "Pícale");
        raiz = persona("raiz", w, casa, OrgRole.OWNER);
        when(administradores.esRaiz(any())).thenAnswer(i -> {
            User u = i.getArgument(0);
            return u != null && u.getId().equals(raiz.getId());
        });
        when(stripe.disponible()).thenReturn(true);
        when(uploadPost.borrarPerfil(anyString())).thenReturn(true);
    }

    @AfterEach
    void limpiar() {
        // Lo que la prueba no borró se borra aquí, con las mismas piezas del servicio.
        for (UUID id : orgsCreadas) {
            if (!organizaciones.existsById(id)) {
                continue;
            }
            Map<String, UUID> o = Map.of("o", id);
            List<UUID> ws = sql.queryForList("select id from workspaces where organization_id = :o", o, UUID.class);
            ws.forEach(eliminacion::borrarContenido);
            Set<UUID> gente = new TreeSet<>(sql.queryForList(
                    "select user_id from organization_members where organization_id = :o", o, UUID.class));
            gente.addAll(sql.queryForList(
                    "select id from users where workspace_id in (select id from workspaces where organization_id = :o)",
                    o, UUID.class));
            gente.forEach(eliminacion::borrarPersona);
            sql.update("delete from organization_members where organization_id = :o", o);
            sql.update("delete from licenses where organization_id = :o", o);
            sql.update("delete from workspaces where organization_id = :o", o);
            sql.update("delete from organizations where id = :o", o);
        }
    }

    // ------------------------------------------------------------------ armado

    private Organization org(String nombre) {
        Organization o = organizaciones.save(Organization.builder().name(nombre).build());
        orgsCreadas.add(o.getId());
        return o;
    }

    private Workspace espacio(Organization o, String nombre) {
        return workspaces.save(Workspace.builder().name(nombre).organization(o).build());
    }

    private User persona(String nombre, Workspace activo, Organization o, OrgRole rol) {
        User u = usuarios.save(User.builder().name(nombre).email(nombre + "-" + UUID.randomUUID() + "@test.local")
                .password("no-se-usa").role(Role.ADMIN).workspace(activo).build());
        miembrosOrg.save(OrganizationMember.de(u, o, rol));
        miembrosWs.save(WorkspaceMember.de(u, activo, Role.ADMIN));
        return u;
    }

    /** Una publicación con su red, un archivo, licencia de Stripe y créditos. */
    private void conContenido(Workspace w, String suscripcion) {
        TenantIdentifierResolver.comoTenant(w.getId().toString(), () -> {
            SocialAccount ig = cuentas.save(SocialAccount.builder().platform(Platform.INSTAGRAM)
                    .accountName("cliente").status(SocialAccountStatus.CONNECTED).build());
            assets.save(MediaAsset.builder().fileName("foto.jpg")
                    .url("https://cdn.test/media/" + w.getId() + "/foto.jpg")
                    .storageKey("media/" + w.getId() + "/foto.jpg").type(MediaType.IMAGE)
                    .contentType("image/jpeg").sizeBytes(10L).status(MediaAssetStatus.READY).build());
            Post p = Post.builder().caption("Hola").mediaUrls(new ArrayList<>(List.of("https://cdn.test/x.jpg")))
                    .mediaType(MediaType.IMAGE).status(PostStatus.DRAFT).build();
            p.getTargets().add(PostTarget.builder().post(p).socialAccount(ig).build());
            posts.save(p);
        });
        licencias.save(License.builder().organizationId(w.getOrganization().getId()).workspaceId(w.getId())
                .status(LicenseStatus.ACTIVE).currentPeriodEnd(LocalDateTime.now().plusDays(20))
                .stripeSubscriptionId(suscripcion).build());
        creditos.save(ImageCredits.builder().workspaceId(w.getId()).packBalance(39).build());
    }

    private long filas(String tabla, String columna, Object valor) {
        return sql.queryForObject("select count(*) from " + tabla + " where " + columna + " = :v",
                Map.of("v", valor), Long.class);
    }

    // ------------------------------------------------------------------ organización

    @Test
    @DisplayName("una organización se va con sus espacios, contenido y dueño; quien está en otra se queda y se muda")
    void organizacionCompleta() {
        Organization cliente = org("Tacos El Güero");
        Workspace centro = espacio(cliente, "Centro");
        Workspace norte = espacio(cliente, "Norte");
        User duena = persona("duena", centro, cliente, OrgRole.OWNER);

        Organization agencia = org("Agencia");
        Workspace deLaAgencia = espacio(agencia, "Agencia");
        User cm = persona("cm", deLaAgencia, agencia, OrgRole.OWNER);
        miembrosOrg.save(OrganizationMember.de(cm, cliente, OrgRole.MEMBER));
        miembrosWs.save(WorkspaceMember.de(cm, norte, Role.EDITOR));
        sql.update("update users set workspace_id = :w where id = :u", Map.of("w", norte.getId(), "u", cm.getId()));

        conContenido(centro, "sub_centro");
        conContenido(norte, null);

        EliminacionDefinitiva.Resultado r = eliminacion.eliminarOrganizacion(raiz, cliente.getId(), " tacos el güero ");

        assertThat(r.espacios()).isEqualTo(2);
        assertThat(r.personas()).isEqualTo(1);
        assertThat(r.publicaciones()).isEqualTo(2);
        assertThat(r.archivos()).isEqualTo(2);
        assertThat(filas("organizations", "id", cliente.getId())).isZero();
        assertThat(filas("workspaces", "organization_id", cliente.getId())).isZero();
        assertThat(filas("users", "id", duena.getId())).isZero();
        for (Workspace w : List.of(centro, norte)) {
            String t = w.getId().toString();
            assertThat(filas("posts", "tenant_id", t)).isZero();
            assertThat(filas("media_assets", "tenant_id", t)).isZero();
            assertThat(filas("social_accounts", "tenant_id", t)).isZero();
            assertThat(filas("licenses", "workspace_id", w.getId())).isZero();
            assertThat(filas("image_credits", "workspace_id", w.getId())).isZero();
        }
        // La community manager sigue, en su agencia.
        assertThat(sql.queryForObject("select workspace_id from users where id = :u", Map.of("u", cm.getId()),
                UUID.class)).isEqualTo(deLaAgencia.getId());
        assertThat(filas("organization_members", "user_id", cm.getId())).isEqualTo(1);

        verify(stripe).cancelarYa("sub_centro");
        verify(storage).borrarPrefijo("media/" + centro.getId() + "/");
        verify(storage).borrarPrefijo("logos/" + norte.getId() + "/");
        verify(uploadPost).borrarPerfil(centro.getId().toString());
        assertThat(r.avisos()).isEmpty();
    }

    @Test
    @DisplayName("sin escribir el nombre exacto no se borra nada")
    void confirmacion() {
        Organization cliente = org("Gym Fuerte");
        espacio(cliente, "Gym");
        assertThatThrownBy(() -> eliminacion.eliminarOrganizacion(raiz, cliente.getId(), "Gym"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Gym Fuerte");
        assertThat(organizaciones.existsById(cliente.getId())).isTrue();
    }

    @Test
    @DisplayName("si Stripe no cancela, no se borra nada")
    void stripeFallaNoBorra() {
        Organization cliente = org("Con suscripción");
        Workspace w = espacio(cliente, "Único");
        persona("dueno", w, cliente, OrgRole.OWNER);
        conContenido(w, "sub_falla");
        doThrow(new IllegalStateException("Stripe caído")).when(stripe).cancelarYa("sub_falla");

        assertThatThrownBy(() -> eliminacion.eliminarOrganizacion(raiz, cliente.getId(), "Con suscripción"))
                .hasMessageContaining("Stripe caído");
        assertThat(filas("posts", "tenant_id", w.getId().toString())).isEqualTo(1);
        verify(storage, never()).borrarPrefijo(anyString());
    }

    @Test
    @DisplayName("la organización de la raíz no se borra")
    void laDeLaRaiz() {
        assertThatThrownBy(() -> eliminacion.eliminarOrganizacion(raiz, casa.getId(), casa.getName()))
                .isInstanceOf(ConflictoException.class).hasMessageContaining("raíz");
    }

    // ------------------------------------------------------------------ espacio

    @Test
    @DisplayName("un espacio se va y quien trabajaba en él pasa a otro de la organización")
    void espacio() {
        Organization cliente = org("Panadería");
        Workspace uno = espacio(cliente, "Sucursal 1");
        Workspace dos = espacio(cliente, "Sucursal 2");
        User dueno = persona("dueno", dos, cliente, OrgRole.OWNER);
        conContenido(dos, null);

        EliminacionDefinitiva.Resultado r = eliminacion.eliminarEspacio(raiz, dos.getId(), "Sucursal 2");

        assertThat(r.publicaciones()).isEqualTo(1);
        assertThat(filas("workspaces", "id", dos.getId())).isZero();
        assertThat(filas("workspaces", "id", uno.getId())).isEqualTo(1);
        // Es dueño: administra la organización y cae en el otro espacio.
        assertThat(sql.queryForObject("select workspace_id from users where id = :u", Map.of("u", dueno.getId()),
                UUID.class)).isEqualTo(uno.getId());
    }

    @Test
    @DisplayName("ni el último espacio de una organización ni uno que deja a alguien sin a dónde ir")
    void espacioQueNoSePuede() {
        Organization sola = org("Florería");
        Workspace unico = espacio(sola, "Florería");
        assertThatThrownBy(() -> eliminacion.eliminarEspacio(raiz, unico.getId(), "Florería"))
                .isInstanceOf(ConflictoException.class).hasMessageContaining("único espacio");

        Organization cliente = org("Óptica");
        espacio(cliente, "Óptica Centro");
        Workspace sur = espacio(cliente, "Óptica Sur");
        User empleada = persona("empleada", sur, cliente, OrgRole.MEMBER);
        assertThatThrownBy(() -> eliminacion.eliminarEspacio(raiz, sur.getId(), "Óptica Sur"))
                .isInstanceOf(ConflictoException.class).hasMessageContaining(empleada.getEmail());
        assertThat(filas("workspaces", "id", sur.getId())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ persona

    @Test
    @DisplayName("una persona se va con sus membresías; ni la raíz, ni uno mismo, ni un dueño")
    void persona() {
        Organization cliente = org("Estética");
        Workspace w = espacio(cliente, "Estética");
        User dueno = persona("dueno", w, cliente, OrgRole.OWNER);
        User ayudante = persona("ayudante", w, cliente, OrgRole.MEMBER);

        assertThatThrownBy(() -> eliminacion.eliminarUsuario(raiz, dueno.getId(), dueno.getEmail()))
                .isInstanceOf(ConflictoException.class).hasMessageContaining("Estética");
        assertThatThrownBy(() -> eliminacion.eliminarUsuario(raiz, raiz.getId(), raiz.getEmail()))
                .isInstanceOf(ConflictoException.class);

        eliminacion.eliminarUsuario(raiz, ayudante.getId(), ayudante.getEmail().toUpperCase());

        assertThat(filas("users", "id", ayudante.getId())).isZero();
        assertThat(filas("organization_members", "user_id", ayudante.getId())).isZero();
        assertThat(filas("workspace_members", "user_id", ayudante.getId())).isZero();
        assertThat(filas("users", "id", dueno.getId())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ cobertura

    /**
     * Toda tabla que nombra un espacio, una organización o una persona tiene que
     * estar en el borrado. Si este test falla, hay una tabla nueva: agrégala a
     * {@link EliminacionDefinitiva} (y aquí) para que no queden huérfanas.
     */
    @Test
    @DisplayName("el borrado conoce todas las tablas que nombran un espacio, una organización o una persona")
    void cobertura() {
        Set<String> encontradas = new TreeSet<>(sql.queryForList("""
                select distinct table_name || '.' || column_name from information_schema.columns
                where table_schema = current_schema()
                  and column_name in ('tenant_id', 'workspace_id', 'organization_id', 'user_id', 'invited_by',
                                      'post_id', 'invitation_id')
                """, Map.of(), String.class));
        Set<String> conocidas = Set.of(
                "posts.tenant_id", "post_targets.post_id", "post_media.post_id", "publish_jobs.workspace_id",
                "publish_jobs.post_id", "social_accounts.tenant_id", "social_connection_checks.tenant_id",
                "media_assets.tenant_id", "ai_usage.workspace_id", "daily_publish_usage.workspace_id",
                "credit_movements.workspace_id", "image_credits.workspace_id", "licenses.workspace_id",
                "licenses.organization_id", "invitation_workspaces.workspace_id",
                "invitation_workspaces.invitation_id", "invitations.organization_id", "invitations.invited_by",
                "workspace_members.workspace_id", "workspace_members.user_id", "organization_members.user_id",
                "organization_members.organization_id", "users.workspace_id", "workspaces.organization_id",
                // De antes, sin entidad: solo en bases viejas.
                "workspace_invitations.workspace_id", "workspace_invitations.invited_by");
        assertThat(conocidas).containsAll(encontradas);
    }
}
