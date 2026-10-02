package com.metricol.api.service.agente;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.service.agente.OrganizadorDeContenido.Formato;
import com.metricol.api.service.agente.OrganizadorDeContenido.Foto;
import com.metricol.api.service.agente.OrganizadorDeContenido.Grupo;

/** Las reglas que mandan sobre lo que propone la IA al organizar una tanda. */
class OrganizadorDeContenidoTest {

    private static Foto foto(int n, String orientacion, boolean efimero, String tema) {
        return new Foto(n, new RevisorDeMarca.Revision(RevisorDeMarca.Veredicto.VA, "va", "se ve algo", "idea",
                DecisorDelAgente.Diagnostico.BUENA, orientacion, efimero, tema));
    }

    private static final List<Foto> CINCO = List.of(
            foto(1, "CUADRADA", false, "depa"), foto(2, "CUADRADA", false, "depa"), foto(3, "CUADRADA", false, "depa"),
            foto(4, "VERTICAL", true, "promo de hoy"), foto(5, "HORIZONTAL", false, "local"));

    @Test
    @DisplayName("lo propuesto se respeta cuando cumple: carrusel en su orden, historia vertical, post")
    void respeta() {
        List<Grupo> g = OrganizadorDeContenido.normalizar(List.of(
                new Grupo(Formato.CARRUSEL, List.of(2, 1, 3), "depa", "mismo depa"),
                new Grupo(Formato.HISTORIA, List.of(4), "promo", ""),
                new Grupo(Formato.POST, List.of(5), "local", "")), CINCO, 6, true);
        assertThat(g).extracting(Grupo::formato).containsExactly(Formato.CARRUSEL, Formato.HISTORIA, Formato.POST);
        assertThat(g.get(0).fotos()).containsExactly(2, 1, 3);
    }

    @Test
    @DisplayName("un carrusel de más del tope se parte, y el que queda suelto es post")
    void parteElCarrusel() {
        List<Foto> ocho = List.of(foto(1, "CUADRADA", false, "x"), foto(2, "CUADRADA", false, "x"),
                foto(3, "CUADRADA", false, "x"), foto(4, "CUADRADA", false, "x"), foto(5, "CUADRADA", false, "x"),
                foto(6, "CUADRADA", false, "x"), foto(7, "CUADRADA", false, "x"));
        List<Grupo> g = OrganizadorDeContenido.normalizar(
                List.of(new Grupo(Formato.CARRUSEL, List.of(1, 2, 3, 4, 5, 6, 7), "x", "")), ocho, 6, true);
        assertThat(g).extracting(Grupo::formato).containsExactly(Formato.CARRUSEL, Formato.POST);
        assertThat(g.get(0).fotos()).hasSize(6);
        assertThat(g.get(1).fotos()).containsExactly(7);
    }

    @Test
    @DisplayName("historia solo vertical y con redes de historias; un carrusel de una es post")
    void historiaYCarruselDeUna() {
        List<Grupo> g = OrganizadorDeContenido.normalizar(List.of(
                new Grupo(Formato.HISTORIA, List.of(5), "local", ""),   // horizontal
                new Grupo(Formato.CARRUSEL, List.of(1), "depa", "")), CINCO, 6, true);
        assertThat(g.get(0).formato()).isEqualTo(Formato.POST);
        assertThat(g.get(1).formato()).isEqualTo(Formato.POST);

        List<Grupo> sinHistorias = OrganizadorDeContenido.normalizar(
                List.of(new Grupo(Formato.HISTORIA, List.of(4), "promo", "")), CINCO, 6, false);
        assertThat(sinHistorias.get(0).formato()).isEqualTo(Formato.POST);
    }

    @Test
    @DisplayName("cada foto en una sola publicación; números inventados fuera; la que nadie acomodó sale sola")
    void cadaFotoUnaVez() {
        List<Grupo> g = OrganizadorDeContenido.normalizar(List.of(
                new Grupo(Formato.CARRUSEL, List.of(1, 2, 9), "depa", ""),
                new Grupo(Formato.POST, List.of(2), "depa", "")), CINCO, 6, true);
        assertThat(g.stream().flatMap(x -> x.fotos().stream()).toList()).containsExactlyInAnyOrder(1, 2, 3, 4, 5);
        // La 4 (vertical y del momento) sin acomodar: historia por regla.
        assertThat(g).filteredOn(x -> x.fotos().equals(List.of(4))).extracting(Grupo::formato)
                .containsExactly(Formato.HISTORIA);
    }

    @Test
    @DisplayName("sin respuesta de la IA, cada foto con la regla de una sola")
    void sinIa() {
        List<Grupo> g = OrganizadorDeContenido.normalizar(null, CINCO, 6, true);
        assertThat(g).hasSize(5);
        assertThat(g).extracting(Grupo::formato)
                .containsExactly(Formato.POST, Formato.POST, Formato.POST, Formato.HISTORIA, Formato.POST);
    }

    @Test
    @DisplayName("lee lo que contesta la IA aunque venga envuelto en texto")
    void interpreta() throws Exception {
        List<Grupo> g = new OrganizadorDeContenido(null).interpretar("""
                Aquí va: {"publicaciones": [{"formato": "carrusel", "fotos": [3, 1], "tema": "depa", "porque": "mismo depa"},
                {"formato": "raro", "fotos": [2]}]}""");
        assertThat(g).extracting(Grupo::formato).containsExactly(Formato.CARRUSEL, Formato.POST);
        assertThat(g.get(0).fotos()).containsExactly(3, 1);
    }

    @Test
    @DisplayName("las tandas se cortan en una pausa de más de 15 minutos entre subidas")
    void tandas() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 1, 12, 0);
        List<MediaAsset> subidas = List.of(
                MediaAsset.builder().createdAt(t).build(),
                MediaAsset.builder().createdAt(t.plusMinutes(4)).build(),
                MediaAsset.builder().createdAt(t.plusMinutes(10)).build(),
                MediaAsset.builder().createdAt(t.plusMinutes(40)).build());
        List<List<MediaAsset>> tandas = AgenteService.tandas(subidas);
        assertThat(tandas).extracting(List::size).containsExactly(3, 1);
    }
}
