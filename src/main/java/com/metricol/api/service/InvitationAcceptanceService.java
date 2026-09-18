package com.metricol.api.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.Invitation;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.Role;
import com.metricol.api.exception.ForbiddenException;
import com.metricol.api.models.request.AceptarInvitacionRequest;
import com.metricol.api.models.response.AuthResponse;
import com.metricol.api.repository.UserRepository;
import com.metricol.api.util.Correos;

/**
 * Aceptar una invitación: probar quién eres y entrar.
 *
 * <p>Aparte de {@link InvitationService} porque esto toca cuentas —crear una,
 * o comprobar la contraseña de la que ya existe— y aquello solo reparte
 * accesos. Mezclarlos haría que el servicio de invitaciones supiera de
 * contraseñas, que es justo lo que no debe pasar.
 *
 * <p><b>La regla que sostiene todo esto:</b> el token del enlace dice a qué
 * invitan, no quién eres. Con él solo no se entra. Quien no tiene cuenta crea
 * una con ese correo; quien ya la tiene, escribe su contraseña. Si bastara el
 * token, reenviar un correo sería regalar el acceso a la cuenta del invitado.
 */
@Service
public class InvitationAcceptanceService {

    private final InvitationService invitaciones;
    private final UserRepository usuarios;
    private final PasswordEncoder passwordEncoder;
    private final AuthService auth;

    public InvitationAcceptanceService(InvitationService invitaciones, UserRepository usuarios,
            PasswordEncoder passwordEncoder, AuthService auth) {
        this.invitaciones = invitaciones;
        this.usuarios = usuarios;
        this.passwordEncoder = passwordEncoder;
        this.auth = auth;
    }

    /** ¿Ese correo ya tiene cuenta? Decide qué pide la pantalla. */
    public boolean yaTieneCuenta(String email) {
        return usuarios.existsByEmail(Correos.normalizar(email));
    }

    @Transactional
    public AuthResponse aceptar(AceptarInvitacionRequest peticion) {
        Invitation invitacion = invitaciones.porToken(peticion.getToken());
        String email = invitacion.getEmail();

        User persona = usuarios.findByEmail(email).orElse(null);
        Workspace primero = invitaciones.primerEspacioDe(invitacion);

        if (persona == null) {
            persona = crearCuenta(invitacion, peticion, primero);
        } else {
            exigirContraseña(persona, peticion.getPassword());
            // Si no tenía espacio activo —no debería pasar— se le pone este.
            if (persona.getWorkspace() == null) {
                persona.setWorkspace(primero);
                usuarios.save(persona);
            }
        }

        invitaciones.aceptar(invitacion, persona);

        return auth.sesionPara(persona);
    }

    /**
     * La cuenta de quien no tenía.
     *
     * <p>Nace sin workspace propio y con el primero de los asignados como
     * activo: no es su agencia, es donde va a trabajar. Crearle uno suyo lo
     * dejaría entrando a un espacio vacío sin redes, preguntándose dónde está
     * el cliente del que le hablaron.
     */
    private User crearCuenta(Invitation invitacion, AceptarInvitacionRequest peticion, Workspace primero) {
        String password = peticion.getPassword();
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("La contraseña necesita al menos 6 caracteres.");
        }
        String nombre = peticion.getName() == null || peticion.getName().isBlank()
                ? invitacion.getEmail()
                : peticion.getName().strip();

        return usuarios.save(User.builder()
                .name(nombre)
                .email(invitacion.getEmail())
                .password(passwordEncoder.encode(password))
                // El rol viejo del usuario, que ahora solo es un valor por
                // defecto: lo que manda en cada espacio es su WorkspaceMember.
                .role(Role.ADMIN)
                .workspace(primero)
                .build());
    }

    /**
     * Comprueba la contraseña de quien ya tenía cuenta.
     *
     * <p>Mismo mensaje se equivoque en lo que se equivoque: decir "esa cuenta
     * existe pero la contraseña está mal" le confirmaría a cualquiera con el
     * enlace que ese correo tiene cuenta aquí.
     */
    private void exigirContraseña(User persona, String password) {
        if (password == null || persona.getPassword() == null
                || !passwordEncoder.matches(password, persona.getPassword())) {
            throw new ForbiddenException("Revisa tu contraseña para aceptar la invitación.");
        }
    }
}
