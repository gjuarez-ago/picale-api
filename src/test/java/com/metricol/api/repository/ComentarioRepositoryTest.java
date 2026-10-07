package com.metricol.api.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.entity.Comentario;
import com.metricol.api.enums.Platform;

/**
 * Las consultas de la bandeja, contra la base de verdad.
 *
 * <p>Las pruebas del servicio simulan el repositorio, así que ninguna de estas
 * consultas se había ejecutado nunca: una coma de más en el JPQL habría pasado
 * todas las pruebas en verde y reventado en producción al abrir la pantalla.
 *
 * <p><b>La base del perfil dev puede ser un Postgres que persiste</b>, no un
 * H2 que se borra al terminar: cada prueba usa un espacio con id nuevo, mira
 * solo lo suyo y borra lo que escribió.
 *
 * <p>Todo corre dentro de {@link TenantIdentifierResolver#comoTenant}: sin
 * sesión de seguridad el tenant es {@code GLOBAL}, y entonces Hibernate
 * rechaza guardar una fila de otro espacio y devuelve vacío al leerla. Es el
 * mismo truco que usan los workers, que tampoco tienen usuario.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "app.publishing.queue.poll-delay-ms=3600000",
        "app.publishing.queue.rescue-delay-ms=3600000",
        "app.scheduling.poll-delay-ms=3600000",
        "app.media.orphan-delay-ms=3600000",
        "app.media.unused-delay-ms=3600000",
        "app.comentarios.enabled=false"
})
class ComentarioRepositoryTest {

    @Autowired
    private ComentarioRepository comentarios;

    private final UUID espacio = UUID.randomUUID();
    private final List<UUID> creados = new ArrayList<>();

    @AfterEach
    void limpiar() {
        comoElEspacio(() -> comentarios.deleteAllById(creados));
        creados.clear();
    }

    /** Corre dentro del espacio de la prueba, como haría una petición suya. */
    private void comoElEspacio(Runnable accion) {
        TenantIdentifierResolver.comoTenant(espacio.toString(), accion);
    }

    /** Lo pone el tenant de Hibernate; aquí solo se dice en nombre de quién. */
    private Comentario guardar(String idEnLaRed, Platform red, String autor, String texto,
            LocalDateTime escritoEn, boolean atendido, boolean propio) {
        Comentario fila = comentarios.save(Comentario.builder()
                .workspaceId(espacio)
                .socialAccountId(UUID.randomUUID())
                .postTargetId(UUID.randomUUID())
                .red(red)
                .idEnLaRed(idEnLaRed)
                .autorNombre(autor)
                .texto(texto)
                .escritoEn(escritoEn)
                .traidoEn(LocalDateTime.now())
                .propio(propio)
                .atendidoEn(atendido ? LocalDateTime.now() : null)
                .build());
        creados.add(fila.getId());
        return fila;
    }

    private void unPar() {
        guardar("a", Platform.INSTAGRAM, "Ana", "¿Cuánto cuesta?", LocalDateTime.now().minusHours(1), false, false);
        guardar("b", Platform.FACEBOOK, "Beto", "Me encantó", LocalDateTime.now().minusHours(3), false, false);
        guardar("c", Platform.INSTAGRAM, "Caro", "Ya contestado", LocalDateTime.now().minusHours(5), true, false);
        guardar("d", Platform.INSTAGRAM, "Mi negocio", "¡Gracias!", LocalDateTime.now().minusHours(6), false, true);
    }

    @Test
    @DisplayName("la bandeja trae lo pendiente, lo más nuevo arriba, sin lo propio ni lo atendido")
    void bandejaPendientes() {
        comoElEspacio(() -> {
            unPar();

            List<Comentario> pagina = comentarios
                    .bandeja(espacio, true, null, null, PageRequest.of(0, 25)).getContent();

            assertThat(pagina).extracting(Comentario::getIdEnLaRed).containsExactly("a", "b");
        });
    }

    @Test
    @DisplayName("'todos' incluye lo ya atendido, pero nunca lo que escribimos nosotros")
    void bandejaTodos() {
        comoElEspacio(() -> {
            unPar();

            List<Comentario> pagina = comentarios
                    .bandeja(espacio, false, null, null, PageRequest.of(0, 25)).getContent();

            assertThat(pagina).extracting(Comentario::getIdEnLaRed).containsExactly("a", "b", "c");
        });
    }

    @Test
    @DisplayName("W-04: el filtro por red deja solo esa red")
    void bandejaPorRed() {
        comoElEspacio(() -> {
            unPar();

            List<Comentario> pagina = comentarios
                    .bandeja(espacio, true, Platform.FACEBOOK, null, PageRequest.of(0, 25)).getContent();

            assertThat(pagina).extracting(Comentario::getIdEnLaRed).containsExactly("b");
        });
    }

    @Test
    @DisplayName("W-05: se busca por lo que dice y por quién lo dijo")
    void bandejaBuscando() {
        comoElEspacio(() -> {
            unPar();

            assertThat(comentarios.bandeja(espacio, true, null, "%cuesta%", PageRequest.of(0, 25)).getContent())
                    .extracting(Comentario::getIdEnLaRed).containsExactly("a");
            assertThat(comentarios.bandeja(espacio, true, null, "%beto%", PageRequest.of(0, 25)).getContent())
                    .extracting(Comentario::getIdEnLaRed).containsExactly("b");
        });
    }

    @Test
    @DisplayName("W-02: los contadores cuadran con la lista")
    void contadores() {
        comoElEspacio(() -> {
            unPar();

            assertThat(comentarios.pendientesDe(espacio)).isEqualTo(2);
            assertThat(comentarios.pendientesPorRed(espacio))
                    .extracting(fila -> fila[0] + ":" + fila[1])
                    .containsExactlyInAnyOrder("INSTAGRAM:1", "FACEBOOK:1");
        });
    }

    @Test
    @DisplayName("S-01: no se ve nada de otro espacio")
    void deOtroEspacio() {
        comoElEspacio(() -> {
            unPar();

            assertThat(comentarios.bandeja(UUID.randomUUID(), true, null, null, PageRequest.of(0, 25)).getContent())
                    .isEmpty();
            assertThat(comentarios.pendientesDe(UUID.randomUUID())).isZero();
        });
    }

    @Test
    @DisplayName("C-05: el hilo trae el comentario y lo que le contestaron")
    void hilo() {
        comoElEspacio(() -> {
            guardar("padre", Platform.INSTAGRAM, "Ana", "¿Precio?", LocalDateTime.now().minusHours(2), false, false);
            Comentario respuesta = Comentario.builder()
                    .workspaceId(espacio).postTargetId(UUID.randomUUID())
                    .red(Platform.INSTAGRAM).idEnLaRed("hija").padreIdEnLaRed("padre")
                    .autorNombre("Mi negocio").texto("$120").escritoEn(LocalDateTime.now().minusHours(1))
                    .propio(true).build();
            creados.add(comentarios.save(respuesta).getId());

            assertThat(comentarios.hilo(espacio, "padre"))
                    .extracting(Comentario::getIdEnLaRed).containsExactly("padre", "hija");
        });
    }

    @Test
    @DisplayName("C-03: el mismo comentario de la misma red no se puede guardar dos veces")
    void cualesYaEstan() {
        comoElEspacio(() -> {
            unPar();

            assertThat(comentarios.cualesYaEstan("INSTAGRAM", List.of("a", "c", "nuevo")))
                    .containsExactlyInAnyOrder("a", "c");
            assertThat(comentarios.cualesYaEstan("TIKTOK", List.of("a"))).isEmpty();
        });
    }

    @Test
    @DisplayName("N-02: el aviso cuenta lo nuevo sin atender, agrupado por espacio")
    void nuevosSinAtender() {
        comoElEspacio(() -> {
            unPar();

            List<Object[]> filas = comentarios.nuevosSinAtenderPorEspacio(LocalDateTime.now().minusHours(1));
            Object[] mio = filas.stream()
                    .filter(f -> espacio.toString().equals(String.valueOf(f[0])))
                    .findFirst().orElseThrow();

            // Dos pendientes, de dos cuentas distintas. Ni el atendido ni el propio.
            assertThat(((Number) mio[1]).longValue()).isEqualTo(2);
            assertThat(((Number) mio[2]).longValue()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("marcar leídos no cambia si está atendido, y no repite")
    void marcarLeidos() {
        comoElEspacio(() -> {
            Comentario uno = guardar("x", Platform.INSTAGRAM, "Ana", "hola",
                    LocalDateTime.now(), false, false);

            assertThat(comentarios.marcarLeidos(List.of(uno.getId()), LocalDateTime.now())).isEqualTo(1);
            assertThat(comentarios.marcarLeidos(List.of(uno.getId()), LocalDateTime.now())).isZero();
            assertThat(comentarios.findById(uno.getId()).orElseThrow().getLeidoEn()).isNotNull();
            assertThat(comentarios.findById(uno.getId()).orElseThrow().getAtendidoEn()).isNull();
        });
    }
}
