package com.metricol.api.service.agente.video;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.metricol.api.entity.PostTarget;
import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostFormat;
import com.metricol.api.repository.PostTargetRepository;

/** Aprende qué redes rinden mejor para los videos del workspace. */
@Service
public class RendimientoPorRed {

    static final int MIN_MUESTRAS = 3;
    static final double UMBRAL_RELATIVO = 65.0;
    private static final int DIAS = 90;

    public record Evaluacion(Platform plataforma, int muestras, Double puntaje, boolean elegida, String frase) {
        public boolean tieneHistoria() {
            return puntaje != null;
        }
    }

    public record Seleccion(Set<Platform> elegidas, Map<Platform, Evaluacion> evaluaciones) {
    }

    private final PostTargetRepository destinos;

    public RendimientoPorRed(PostTargetRepository destinos) {
        this.destinos = destinos;
    }

    public Seleccion elegir(UUID workspaceId, Set<Platform> compatibles, PostFormat formato) {
        List<Object[]> filas = destinos.rendimientoDeVideos(
                workspaceId.toString(), LocalDateTime.now().minusDays(DIAS));
        return calcular(compatibles, formato, filas);
    }

    static Seleccion calcular(Set<Platform> compatibles, PostFormat formato, List<Object[]> filas) {
        Map<Platform, List<Double>> exactas = new EnumMap<>(Platform.class);
        Map<Platform, List<Double>> videos = new EnumMap<>(Platform.class);
        for (Object[] f : filas == null ? List.<Object[]>of() : filas) {
            Platform red = plataforma(f[0]);
            if (red == null || !compatibles.contains(red)) continue;
            Double puntaje = puntaje(f, 2);
            if (puntaje == null) continue;
            videos.computeIfAbsent(red, k -> new ArrayList<>()).add(puntaje);
            if (formato != null && formato.name().equals(String.valueOf(f[1]))) {
                exactas.computeIfAbsent(red, k -> new ArrayList<>()).add(puntaje);
            }
        }

        Map<Platform, List<Double>> usadas = new EnumMap<>(Platform.class);
        Map<Platform, Double> medianas = new EnumMap<>(Platform.class);
        for (Platform red : compatibles) {
            List<Double> delFormato = exactas.getOrDefault(red, List.of());
            List<Double> muestra = delFormato.size() >= MIN_MUESTRAS
                    ? delFormato : videos.getOrDefault(red, List.of());
            usadas.put(red, muestra);
            if (muestra.size() >= MIN_MUESTRAS) medianas.put(red, mediana(muestra));
        }

        double mejor = medianas.values().stream().max(Double::compare).orElse(0.0);
        boolean comparar = medianas.size() >= 2;
        Set<Platform> elegidas = new LinkedHashSet<>();
        Map<Platform, Evaluacion> evaluaciones = new EnumMap<>(Platform.class);
        for (Platform red : compatibles) {
            List<Double> muestra = usadas.getOrDefault(red, List.of());
            Double relativo = medianas.containsKey(red)
                    ? mejor > 0 ? redondear(medianas.get(red) * 100.0 / mejor) : 100.0
                    : null;
            boolean elegida = !comparar || relativo == null || relativo >= UMBRAL_RELATIVO;
            if (elegida) elegidas.add(red);
            String frase;
            if (relativo == null) {
                frase = red.getLabel() + " se incluye: aún no hay " + MIN_MUESTRAS
                        + " videos medidos para juzgarla (hay " + muestra.size() + ").";
            } else if (elegida) {
                frase = red.getLabel() + " se incluye: rendimiento histórico " + relativo.intValue()
                        + "/100 con " + muestra.size() + " videos medidos.";
            } else {
                frase = red.getLabel() + " queda fuera: rendimiento histórico " + relativo.intValue()
                        + "/100 con " + muestra.size() + " videos; está por debajo del 65% de la mejor red.";
            }
            evaluaciones.put(red, new Evaluacion(red, muestra.size(), relativo, elegida, frase));
        }
        // Siempre hay salida si técnicamente había una red compatible.
        if (elegidas.isEmpty() && !medianas.isEmpty()) {
            Platform mejorRed = medianas.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            elegidas.add(mejorRed);
            Evaluacion e = evaluaciones.get(mejorRed);
            evaluaciones.put(mejorRed, new Evaluacion(mejorRed, e.muestras(), e.puntaje(), true,
                    mejorRed.getLabel() + " se incluye por ser la red con mejor rendimiento histórico."));
        }
        return new Seleccion(Set.copyOf(elegidas), Map.copyOf(evaluaciones));
    }

    private static Double puntaje(Object[] f, int desde) {
        PostTarget t = PostTarget.builder()
                .vistas(largo(f[desde])).alcance(largo(f[desde + 1])).meGusta(largo(f[desde + 2]))
                .comentarios(largo(f[desde + 3])).compartidos(largo(f[desde + 4]))
                .guardados(largo(f[desde + 5])).metricasEn(LocalDateTime.now()).build();
        return t.puntaje();
    }

    private static Platform plataforma(Object valor) {
        try {
            return valor == null ? null : Platform.valueOf(String.valueOf(valor));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Long largo(Object valor) {
        return valor instanceof Number n ? n.longValue() : null;
    }

    static double mediana(List<Double> valores) {
        List<Double> orden = valores.stream().sorted(Comparator.naturalOrder()).toList();
        int n = orden.size();
        if (n == 0) return 0;
        return n % 2 == 1 ? orden.get(n / 2) : (orden.get(n / 2 - 1) + orden.get(n / 2)) / 2.0;
    }

    private static double redondear(double valor) {
        return Math.round(valor * 10.0) / 10.0;
    }
}
