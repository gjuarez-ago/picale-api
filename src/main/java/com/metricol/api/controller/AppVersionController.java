package com.metricol.api.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.models.response.ApiResponse;

/**
 * La compilación más vieja de la app de Android que todavía se deja usar.
 *
 * <p>La app lo pregunta al abrir (ver {@code actualizaciones_service.dart}). Si
 * la suya es más vieja y Google Play tiene una más nueva, le pide actualizar y
 * no deja seguir sin hacerlo. Sirve para cuando un cambio de la API rompe las
 * versiones viejas de la app: se sube la versión nueva a Play, y cuando ya
 * esté publicada se pone aquí su número de compilación (el que va después del
 * {@code +} en el {@code pubspec.yaml}, p. ej. {@code 1.0.13+14} → 14).
 *
 * <p>Se cambia con {@code APP_ANDROID_VERSION_MINIMA} en el {@code .env.vps} y
 * reiniciando el contenedor. Con 0 (lo de siempre) nadie está obligado: la app
 * solo ofrece la versión nueva y la persona decide.
 *
 * <p>Público y sin sesión: se pregunta también desde el login, y un número de
 * versión no es un secreto.
 */
@RestController
public class AppVersionController {

    private final int androidMinima;

    public AppVersionController(@Value("${app.android.version-minima:0}") int androidMinima) {
        this.androidMinima = Math.max(0, androidMinima);
    }

    @GetMapping("/api/v1/ops/app-version")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> version() {
        return ResponseEntity.ok(ApiResponse.success(Map.of("androidMinima", androidMinima)));
    }
}
