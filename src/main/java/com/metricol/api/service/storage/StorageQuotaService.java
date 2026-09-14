package com.metricol.api.service.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.config.MediaLimitsProperties;
import com.metricol.api.exception.QuotaExceededException;
import com.metricol.api.repository.MediaAssetRepository;

/**
 * Cuánto espacio lleva usado un workspace y si le cabe un archivo más.
 *
 * <p>Se cuenta sumando el tamaño de sus medios en la base, no preguntándole a
 * R2. Preguntar al almacén habría sido la fuente de la verdad, pero listar un
 * bucket entero en cada subida es una llamada de red por archivo y no escala
 * a nada; la suma local vale lo mismo mientras cada borrado quite su fila, que
 * es lo que hace {@code MediaService.delete}.
 *
 * <p>El tope se comprueba ANTES de subir. Al revés —subir y luego medir— haría
 * que el archivo que rompe la cuota ya estuviera pagado, y habría que borrarlo
 * a mano.
 */
@Service
public class StorageQuotaService {

    private static final Logger log = LoggerFactory.getLogger(StorageQuotaService.class);

    private final MediaAssetRepository repository;
    private final MediaLimitsProperties props;

    public StorageQuotaService(MediaAssetRepository repository, MediaLimitsProperties props) {
        this.repository = repository;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public Uso uso() {
        long usado = repository.espacioUsado();
        return new Uso(usado, props.cuotaActiva() ? props.getMaxBytesPerWorkspace() : null);
    }

    /**
     * Lanza si este archivo no cabe. Comprueba las dos cosas que pueden
     * fallar por separado: que el archivo suelto no sea gigante, y que quepa
     * en lo que queda del workspace.
     */
    @Transactional(readOnly = true)
    public void verificar(long bytes, String nombre) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("El archivo está vacío.");
        }

        if (props.getMaxFileBytes() > 0 && bytes > props.getMaxFileBytes()) {
            throw new QuotaExceededException(
                    "FILE_TOO_LARGE",
                    "Ese archivo pesa " + MediaLimitsProperties.legible(bytes)
                            + " y el máximo por archivo es "
                            + MediaLimitsProperties.legible(props.getMaxFileBytes()) + ".");
        }

        if (!props.cuotaActiva()) {
            return;
        }

        Uso uso = uso();
        if (bytes > uso.disponible()) {
            log.info("Cuota de espacio agotada: usados {} de {}, y el archivo pide {}",
                    uso.usado(), uso.limite(), bytes);
            throw new QuotaExceededException(
                    "STORAGE_QUOTA_EXCEEDED",
                    "Te quedan " + MediaLimitsProperties.legible(uso.disponible())
                            + " libres y " + (nombre == null ? "el archivo" : nombre)
                            + " pesa " + MediaLimitsProperties.legible(bytes)
                            + ". Borra contenido que ya no uses en Contenido.");
        }
    }

    /**
     * El espacio de un workspace. {@code limite} en {@code null} es un
     * entorno sin cuota: entonces no hay nada disponible que calcular ni
     * porcentaje que enseñar.
     */
    public record Uso(long usado, Long limite) {

        public long disponible() {
            return limite == null ? Long.MAX_VALUE : Math.max(0, limite - usado);
        }

        /** De 0 a 100, o {@code null} sin cuota. Se recorta en 100. */
        public Integer porcentaje() {
            if (limite == null || limite == 0) {
                return null;
            }
            return (int) Math.min(100, Math.round(usado * 100.0 / limite));
        }
    }
}
