package com.metricol.api.enums;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * El papel de una persona dentro de un workspace, con los permisos que trae
 * puestos.
 *
 * <p>El rol es el atajo: dice de un vistazo qué hace alguien y evita marcar
 * nueve casillas por cada alta. Lo que el rol no puede prever se ajusta encima,
 * persona por persona, en {@link com.metricol.api.entity.WorkspaceMember} — "un
 * editor que además conecta redes" no necesita un rol nuevo.
 *
 * <p>ADMIN existía antes con otro significado —era el único valor y lo tenía
 * todo el mundo— y se conserva con ese mismo alcance: lo puede todo. Así las
 * cuentas de hoy siguen funcionando igual sin migrar nada.
 */
public enum Role {

    /** Lo puede todo, incluido invitar gente y conectar redes. El dueño del workspace. */
    ADMIN(EnumSet.allOf(Permission.class)),

    /**
     * Trabaja el contenido: crea, publica, programa y borra sus publicaciones,
     * y redacta con la IA. No toca las redes ni al equipo, que es lo que
     * distingue a quien hace el trabajo de quien administra la cuenta del
     * cliente.
     */
    EDITOR(EnumSet.of(
            Permission.POST_CREATE,
            Permission.POST_PUBLISH,
            Permission.POST_SCHEDULE,
            Permission.POST_DELETE,
            Permission.MEDIA_DELETE)),

    /**
     * Mira y ya: el calendario, lo publicado y cómo va todo. Para el cliente
     * que quiere ver qué se está haciendo en su cuenta sin poder cambiarlo.
     */
    VIEWER(EnumSet.noneOf(Permission.class));

    private final Set<Permission> permisos;

    Role(Set<Permission> permisos) {
        this.permisos = Collections.unmodifiableSet(permisos);
    }

    /** Los permisos que trae el rol, antes de los ajustes de cada persona. */
    public Set<Permission> permisosPorDefecto() {
        return permisos;
    }
}
