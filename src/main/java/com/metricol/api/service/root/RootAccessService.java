package com.metricol.api.service.root;

import org.springframework.stereotype.Service;

import com.metricol.api.entity.User;
import com.metricol.api.exception.ForbiddenException;

/**
 * La puerta de {@code /api/v1/root/**}.
 *
 * <p>Una sola comprobación, en un solo lugar: que quien pide administre la
 * plataforma ({@code User.platformAdmin}). No pasa por permisos de
 * organización ni de workspace a propósito: ser dueño de una organización no
 * acerca a nadie a ver las demás.
 *
 * <p>403 y no 404: quien llega aquí ya está autenticado y la ruta no es
 * secreta; lo que le falta es un acceso que sabe que existe.
 */
@Service
public class RootAccessService {

    public void exigir(User usuario) {
        if (usuario == null || !usuario.isPlatformAdmin()) {
            throw new ForbiddenException("Solo quien administra la plataforma puede entrar aquí.");
        }
    }
}
