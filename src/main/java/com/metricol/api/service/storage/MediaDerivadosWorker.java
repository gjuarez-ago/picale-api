package com.metricol.api.service.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.media.AdaptadorDeImagenes;
import com.metricol.api.service.media.MiniaturaDeVideo;

/**
 * Borra las versiones adaptadas que ya no tienen original.
 *
 * <p>Al publicar, ffmpeg deja en R2 una copia de cada imagen encajada en lo
 * que acepta la red ({@link AdaptadorDeImagenes}). Esas copias no tienen fila
 * en {@code media_assets} —no las subió nadie, las generamos nosotros— y por
 * eso no cuentan para la cuota de la persona. Pero ocupan en el bucket y se
 * pagan, y hasta ahora no las borraba nadie: sobrevivían al original, a la
 * publicación y al workspace.
 *
 * <p>Borrarlas al borrar el original ya lo hace {@code MediaService.delete}.
 * Esto es la red que hay detrás, y recoge lo que aquello no puede:
 * <ul>
 *   <li>lo acumulado antes de que existiera esta limpieza, incluidas las
 *       claves del formato viejo, que eran planas y no se podían asociar a
 *       ninguna imagen;</li>
 *   <li>los derivados de un borrado en el que R2 falló justo en ese paso;</li>
 *   <li>los de un workspace del que ya no queda ni una fila.</li>
 * </ul>
 *
 * <p>Son archivos regenerables: si se borra uno de más, la siguiente
 * publicación lo vuelve a crear. Por eso esta limpieza puede permitirse ser
 * tajante, y por eso corre de madrugada — es una barrida del bucket entero,
 * no algo que deba competir con nadie.
 */
@Component
public class MediaDerivadosWorker {

    private static final Logger log = LoggerFactory.getLogger(MediaDerivadosWorker.class);

    private final MediaAssetRepository repository;
    private final R2StorageService storage;

    public MediaDerivadosWorker(MediaAssetRepository repository, R2StorageService storage) {
        this.repository = repository;
        this.storage = storage;
    }

    @Scheduled(cron = "${app.media.derivados-cleanup-cron:0 40 3 * * *}")
    public void barrer() {
        try {
            limpiar();
        } catch (Exception ex) {
            // Un fallo aquí no debe matar la tarea. Con cron Spring la vuelve
            // a programar igual, pero el error se traga solo si no se apunta,
            // y una limpieza que dejó de correr no se nota por ningún otro
            // lado hasta que llega la factura.
            log.error("No se pudo barrer los derivados: {}", ex.getMessage(), ex);
        }
    }

    private void limpiar() {
        List<String> derivados = storage.listarClaves(AdaptadorDeImagenes.prefijoRaiz());
        if (derivados.isEmpty()) {
            return;
        }

        List<Object[]> vivas = repository.findClavesVivas();
        Map<String, Set<String>> vivasPorWorkspace = carpetasVivas(vivas);
        Set<String> miniaturasVivas = miniaturasDe(vivas);

        List<String> aBorrar = new ArrayList<>();
        for (String clave : derivados) {
            if (estaHuerfana(clave, vivasPorWorkspace, miniaturasVivas)) {
                aBorrar.add(clave);
            }
        }

        if (aBorrar.isEmpty()) {
            log.debug("Derivados revisados: {}, ninguno sobra", derivados.size());
            return;
        }

        storage.borrarClaves(aBorrar);
        log.info("Derivados borrados: {} de {} revisados", aBorrar.size(), derivados.size());
    }

    /**
     * ¿Esta clave de derivado ya no tiene original detrás?
     *
     * <p>Una clave que se puede reclamar es
     * {@code derivados/<workspace>/<carpeta>/<algo>.jpg}, donde la carpeta es
     * el resumen de la clave del original. Lo que no tenga esa forma sobra por
     * definición —ver {@link AdaptadorDeImagenes#leerDerivado}—, y lo que la
     * tenga sobra si su carpeta no la reclama ningún archivo vivo.
     */
    static boolean estaHuerfana(
            String clave,
            Map<String, Set<String>> vivasPorWorkspace,
            Set<String> miniaturasVivas) {

        // Las miniaturas de video cuelgan del mismo prefijo pero NO tienen su
        // forma: son claves planas, una por video. Sin este caso aparte
        // caían por el `return true` de abajo y la barrida las borraba
        // TODAS cada noche — las de los videos vivos también.
        if (clave.startsWith(MiniaturaDeVideo.prefijo())) {
            return !miniaturasVivas.contains(clave);
        }

        AdaptadorDeImagenes.RefDerivado ref = AdaptadorDeImagenes.leerDerivado(clave);
        if (ref == null) {
            return true;
        }
        return !vivasPorWorkspace.getOrDefault(ref.workspaceId(), Set.of()).contains(ref.carpeta());
    }

    /**
     * Para cada workspace, los nombres de carpeta que le tocarían a sus
     * archivos vivos.
     *
     * <p>Se calcula el resumen de todo lo que existe porque el camino
     * contrario no se puede andar: dado un derivado, su carpeta es un resumen,
     * y de un resumen no se saca la clave que lo produjo.
     */
    private Map<String, Set<String>> carpetasVivas(List<Object[]> vivas) {
        Map<String, Set<String>> porWorkspace = new HashMap<>();

        for (Object[] fila : vivas) {
            Object tenant = fila[0];
            Object clave = fila[1];
            if (tenant == null || clave == null) {
                continue;
            }
            porWorkspace
                    .computeIfAbsent(String.valueOf(tenant), ignorado -> new HashSet<>())
                    .add(AdaptadorDeImagenes.carpetaDe(String.valueOf(clave)));
        }

        return porWorkspace;
    }

    /**
     * Las claves de miniatura que todavía tienen su video detrás.
     *
     * <p>Se calculan igual que al generarlas —{@link MiniaturaDeVideo#claveDe}
     * lo hace por los dos— y no se listan de la base: la miniatura no tiene
     * fila propia, vive como una URL en la del video. Lo que no salga de aquí
     * es de un video que ya no existe.
     */
    private Set<String> miniaturasDe(List<Object[]> vivas) {
        Set<String> claves = new HashSet<>();

        for (Object[] fila : vivas) {
            Object clave = fila[1];
            if (clave != null) {
                claves.add(MiniaturaDeVideo.claveDe(String.valueOf(clave)));
            }
        }

        return claves;
    }
}
