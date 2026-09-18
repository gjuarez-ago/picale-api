package com.metricol.api.enums;

/**
 * Lo que se puede hacer en la ORGANIZACIÓN, por encima de cualquier cliente.
 *
 * <p>Aparte de {@link Permission} porque no pertenecen a un espacio: invitar
 * gente o dar de alta un cliente son decisiones de la agencia. Dueño y
 * administradores los tienen todos por su papel; a un miembro se le dan uno
 * por uno.
 *
 * <p>Los dos llevan candado, y no es un detalle: quien los tiene sin
 * administrar la organización solo puede dar lo que él mismo tiene. Sin eso,
 * un miembro con "invitar" se invitaría a sí mismo con otro correo como
 * administrador y quedaría por encima de quien se lo dio.
 */
public enum OrgPermission {

    /**
     * Invitar gente, pero solo a los clientes donde él mismo entra y con
     * permisos que él mismo tiene. No puede invitar administradores.
     */
    INVITE_MEMBERS,

    /**
     * Dar de alta clientes nuevos. Cuentan contra el tope de la organización,
     * y el día que se cobre por espacio, cuestan dinero.
     */
    CREATE_WORKSPACES
}
