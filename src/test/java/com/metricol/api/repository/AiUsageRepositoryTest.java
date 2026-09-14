package com.metricol.api.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.metricol.api.entity.AiUsage;
import com.metricol.api.enums.AiOperacion;

/**
 * Las sumas del reporte de gasto, contra la base de verdad.
 *
 * <p><b>La base del perfil dev puede ser un Postgres que persiste</b> (ver
 * DB_* en el .env), no un H2 que se borra al terminar. Por eso cada prueba usa
 * workspaces con ids nuevos, mira solo los suyos y borra lo que escribió: con
 * ids fijos, la segunda corrida sumaba sus filas a las de la primera y los
 * conteos salían al doble.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "app.publishing.queue.poll-delay-ms=3600000",
        "app.publishing.queue.rescue-delay-ms=3600000",
        "app.scheduling.poll-delay-ms=3600000",
        "app.media.orphan-delay-ms=3600000",
        "app.media.unused-delay-ms=3600000"
})
class AiUsageRepositoryTest {

    @Autowired
    private AiUsageRepository usos;

    /** Lo que escribe cada prueba, para no dejar basura en la base. */
    private final List<UUID> creados = new ArrayList<>();

    @AfterEach
    void limpiar() {
        usos.deleteAllById(creados);
        creados.clear();
    }

    private void anotar(UUID workspace, AiOperacion operacion, String costo, LocalDateTime cuando) {
        AiUsage fila = usos.save(AiUsage.builder()
                .workspaceId(workspace)
                .operacion(operacion)
                .modelo("gpt-4.1-mini")
                .tokensEntrada(100)
                .tokensSalida(50)
                .costoUsd(new BigDecimal(costo))
                .createdAt(cuando)
                .build());
        creados.add(fila.getId());
    }

    private static LocalDateTime dia(int anio, int mes, int dia) {
        return LocalDateTime.of(anio, mes, dia, 0, 0);
    }

    private static AiUsageRepository.PorWorkspace soloDe(UUID workspace, List<AiUsageRepository.PorWorkspace> filas) {
        List<AiUsageRepository.PorWorkspace> suyas = filas.stream()
                .filter(fila -> workspace.equals(fila.getWorkspaceId()))
                .toList();
        assertThat(suyas).hasSize(1);
        return suyas.get(0);
    }

    @Test
    @DisplayName("ordena los workspaces del que mas gasta al que menos, con sus sumas")
    void ordenaPorGasto() {
        UUID gastaMucho = UUID.randomUUID();
        UUID gastaPoco = UUID.randomUUID();

        // El que hace MAS llamadas no es el que mas gasta: el orden tiene que
        // salir del costo, no del conteo.
        anotar(gastaPoco, AiOperacion.AJUSTAR, "0.001", dia(2001, 3, 10));
        anotar(gastaPoco, AiOperacion.AJUSTAR, "0.001", dia(2001, 3, 11));
        anotar(gastaPoco, AiOperacion.AJUSTAR, "0.001", dia(2001, 3, 12));
        anotar(gastaMucho, AiOperacion.DESCRIBIR_IMAGENES, "0.010", dia(2001, 3, 10));
        anotar(gastaMucho, AiOperacion.REDACTAR, "0.003", dia(2001, 3, 20));

        List<AiUsageRepository.PorWorkspace> filas = usos.gastoPorWorkspace(dia(2001, 3, 1), dia(2001, 4, 1));

        assertThat(filas)
                .extracting(AiUsageRepository.PorWorkspace::getWorkspaceId)
                .filteredOn(Set.of(gastaMucho, gastaPoco)::contains)
                .containsExactly(gastaMucho, gastaPoco);

        AiUsageRepository.PorWorkspace mucho = soloDe(gastaMucho, filas);
        assertThat(mucho.getLlamadas()).isEqualTo(2L);
        assertThat(mucho.getTokensEntrada()).isEqualTo(200L);
        assertThat(mucho.getTokensSalida()).isEqualTo(100L);
        assertThat(mucho.getCostoUsd()).isEqualByComparingTo("0.013");
    }

    @Test
    @DisplayName("lo de fuera del periodo no cuenta, y el ultimo instante tampoco")
    void respetaElPeriodo() {
        UUID workspace = UUID.randomUUID();
        anotar(workspace, AiOperacion.REDACTAR, "0.005", dia(2002, 5, 15));
        anotar(workspace, AiOperacion.REDACTAR, "0.007", dia(2002, 6, 1));
        anotar(workspace, AiOperacion.REDACTAR, "0.009", dia(2002, 4, 30).withHour(23).withMinute(59));

        AiUsageRepository.PorWorkspace fila =
                soloDe(workspace, usos.gastoPorWorkspace(dia(2002, 5, 1), dia(2002, 6, 1)));

        assertThat(fila.getLlamadas()).isEqualTo(1L);
        assertThat(fila.getCostoUsd()).isEqualByComparingTo("0.005");
    }

    @Test
    @DisplayName("desglosa el gasto de cada workspace por operacion")
    void desglosaPorOperacion() {
        UUID workspace = UUID.randomUUID();
        anotar(workspace, AiOperacion.AJUSTAR, "0.002", dia(2003, 7, 1));
        anotar(workspace, AiOperacion.AJUSTAR, "0.002", dia(2003, 7, 2));
        anotar(workspace, AiOperacion.ACORTAR, "0.001", dia(2003, 7, 2));

        List<AiUsageRepository.PorOperacion> filas = usos.gastoPorOperacion(dia(2003, 7, 1), dia(2003, 8, 1))
                .stream()
                .filter(fila -> workspace.equals(fila.getWorkspaceId()))
                .toList();

        assertThat(filas).hasSize(2);
        assertThat(filas)
                .anySatisfy(fila -> {
                    assertThat(fila.getOperacion()).isEqualTo(AiOperacion.AJUSTAR);
                    assertThat(fila.getLlamadas()).isEqualTo(2L);
                    assertThat(fila.getCostoUsd()).isEqualByComparingTo("0.004");
                })
                .anySatisfy(fila -> {
                    assertThat(fila.getOperacion()).isEqualTo(AiOperacion.ACORTAR);
                    assertThat(fila.getCostoUsd()).isEqualByComparingTo("0.001");
                });
    }
}
