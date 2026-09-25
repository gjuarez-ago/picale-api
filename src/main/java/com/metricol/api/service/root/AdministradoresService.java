package com.metricol.api.service.root;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.User;
import com.metricol.api.exception.ConflictoException;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.response.root.AdministradorResponse;
import com.metricol.api.repository.UserRepository;
import com.metricol.api.util.Correos;

/**
 * Quién administra la plataforma ({@code User.platformAdmin}), gestionado en
 * la base desde la pantalla de Administración.
 *
 * <p>Solo quien ya administra llega aquí ({@link RootAccessService} y
 * {@code SecurityConfig}). Dar la marca exige una cuenta que ya exista: no se
 * crean cuentas ni se tocan contraseñas.
 *
 * <p>Dos cuentas no se pueden quitar: la raíz, porque el arranque se la vuelve
 * a poner (quitarla desde aquí sería un cambio que no dura), y quien pide,
 * para que la plataforma nunca se quede sin nadie que la administre. Como
 * quien pide siempre la tiene, con esto último basta para que quede al menos
 * uno.
 *
 * <p>La marca se lee de la base en cada petición (el filtro JWT carga al
 * usuario), así que darla o quitarla vale desde la siguiente, sin cerrar
 * sesión.
 */
@Service
public class AdministradoresService {

    private static final Logger log = LoggerFactory.getLogger(AdministradoresService.class);

    private final UserRepository usuarios;
    private final String correoRaiz;

    /**
     * @param correoDemo la cuenta demo ({@code DEMO_ACCOUNT_EMAIL}); es la raíz
     *                   solo con {@code DEMO_ACCOUNT_ROOT=true}
     */
    public AdministradoresService(UserRepository usuarios,
            @Value("${app.demo.email:}") String correoDemo,
            @Value("${app.demo.root:false}") boolean demoEsRaiz) {
        this.usuarios = usuarios;
        this.correoRaiz = demoEsRaiz && correoDemo != null && !correoDemo.isBlank()
                ? Correos.normalizar(correoDemo)
                : null;
    }

    @Transactional(readOnly = true)
    public List<AdministradorResponse> listar(User quien) {
        return usuarios.findByPlatformAdminTrue().stream()
                .map(u -> new AdministradorResponse(u.getId(), u.getName(), u.getEmail(), esRaiz(u),
                        quien != null && u.getId().equals(quien.getId())))
                .sorted(Comparator.comparing(AdministradorResponse::raiz).reversed()
                        .thenComparing(a -> a.email() == null ? "" : a.email()))
                .toList();
    }

    /** Idempotente: a quien ya administra no le pasa nada. */
    @Transactional
    public List<AdministradorResponse> agregar(String correo, User quien) {
        String normalizado = Correos.normalizar(correo);
        User usuario = usuarios.findByEmail(normalizado)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No hay ninguna cuenta con ese correo. Tiene que registrarse primero."));
        if (!usuario.isPlatformAdmin()) {
            usuario.setPlatformAdmin(true);
            usuarios.save(usuario);
            log.warn("{} le dio la administración de la plataforma a {}", quien.getEmail(), usuario.getEmail());
        }
        return listar(quien);
    }

    @Transactional
    public List<AdministradorResponse> quitar(UUID userId, User quien) {
        User usuario = usuarios.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Esa cuenta no existe."));
        if (usuario.getId().equals(quien.getId())) {
            throw new ConflictoException("ADMIN_ES_USTED",
                    "No puedes quitarte a ti mismo: pídeselo a otra persona que administre la plataforma.");
        }
        if (esRaiz(usuario)) {
            throw new ConflictoException("ADMIN_ES_RAIZ",
                    "La cuenta raíz siempre administra la plataforma.");
        }
        if (usuario.isPlatformAdmin()) {
            usuario.setPlatformAdmin(false);
            usuarios.save(usuario);
            log.warn("{} le quitó la administración de la plataforma a {}", quien.getEmail(), usuario.getEmail());
        }
        return listar(quien);
    }

    private boolean esRaiz(User u) {
        return correoRaiz != null && correoRaiz.equals(Correos.normalizar(u.getEmail()));
    }
}
