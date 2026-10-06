package com.metricol.api.service.agente.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.enums.RasgoDelNegocio;

/** Las fechas que le tocan a cada negocio, y las fotos que se piden sin IA. */
class FechasDelAnioTest {

    private static Set<String> codigos(List<FechasDelAnio.Proxima> p) {
        return p.stream().map(x -> x.fecha().codigo()).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("a cada quien sus fechas: el Día del Albañil a la constructora, no a la taquería")
    void porGiro() {
        LocalDate abril = LocalDate.of(2026, 4, 25);
        var constructora = FechasDelAnio.proximas(abril, 30, "Construcción", "Obras",
                EnumSet.of(RasgoDelNegocio.POR_PROYECTO));
        var taqueria = FechasDelAnio.proximas(abril, 30, "Taquería", "Tacos al pastor",
                EnumSet.of(RasgoDelNegocio.PRODUCTO));
        assertThat(codigos(constructora)).contains("ALBANIL", "MADRES").doesNotContain("NINO");
        assertThat(codigos(taqueria)).contains("NINO", "MADRES").doesNotContain("ALBANIL");
    }

    @Test
    @DisplayName("las fechas que cambian de día se calculan: Buen Fin y Día del Padre 2026")
    void fechasMoviles() {
        var nov = FechasDelAnio.proximas(LocalDate.of(2026, 11, 1), 30, "Tienda", "",
                EnumSet.of(RasgoDelNegocio.PRODUCTO));
        assertThat(nov).filteredOn(p -> p.fecha().codigo().equals("BUEN_FIN")).singleElement()
                .extracting(FechasDelAnio.Proxima::dia).isEqualTo(LocalDate.of(2026, 11, 12));
        var jun = FechasDelAnio.proximas(LocalDate.of(2026, 6, 1), 30, "Tienda", "", null);
        assertThat(jun).filteredOn(p -> p.fecha().codigo().equals("PADRE")).singleElement()
                .extracting(FechasDelAnio.Proxima::dia).isEqualTo(LocalDate.of(2026, 6, 21));
    }

    @Test
    @DisplayName("cruza de año: a fin de diciembre ya ve el Día de Reyes")
    void cruzaDeAnio() {
        var p = FechasDelAnio.proximas(LocalDate.of(2026, 12, 28), 10, "Panadería", "",
                EnumSet.of(RasgoDelNegocio.PRODUCTO));
        assertThat(p).extracting(FechasDelAnio.Proxima::clave).contains("FIN_DE_ANIO-2026", "REYES-2027");
    }

    @Test
    @DisplayName("sin IA, la lista de fotos sale del catálogo: tres, y la fecha cercana primero")
    void tomasDeCatalogo() {
        var fechas = FechasDelAnio.proximas(LocalDate.of(2026, 4, 25), 14, "Construcción", "", Set.of());
        var tomas = ListaDeTomas.catalogo(EnumSet.of(RasgoDelNegocio.POR_PROYECTO), fechas);
        assertThat(tomas).hasSize(3);
        assertThat(tomas.get(0).que()).contains("Santa Cruz");
        assertThat(tomas).extracting(ListaDeTomas.Toma::que).anyMatch(q -> q.contains("terminado"));
    }
}
