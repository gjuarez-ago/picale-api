package com.metricol.api.service.campaign;

import java.util.List;

/** {@link PaletaDeLogo} para fuera del paquete: los colores dominantes de un logotipo, en "#RRGGBB". */
public final class PaletaPublica {

    private PaletaPublica() {
    }

    public static List<String> dominantes(byte[] imagen, int maximo) {
        return PaletaDeLogo.dominantes(imagen, maximo);
    }
}
