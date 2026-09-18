package com.metricol.api.enums;

/**
 * El papel de una persona en la organización: la agencia, no el cliente.
 *
 * <p>Es una capa por encima de {@link Role}, que es el papel dentro de UN
 * espacio de trabajo. La diferencia práctica: el rol de organización decide
 * quién puede crear clientes y meter gente; el de workspace decide qué se hace
 * dentro de un cliente concreto.
 *
 * <p>OWNER y ADMIN mandan sobre todos los espacios de su organización sin
 * necesidad de estar apuntados uno por uno. Eso es justo lo que se compró al
 * meter esta capa: sin ella, dar de alta a un socio en 30 clientes eran 30
 * altas, y sacarlo, 30 bajas — con la primera que se olvide, sigue dentro.
 */
public enum OrgRole {

    /**
     * Quien creó la organización. Lo puede todo, incluido archivar espacios y
     * quitar administradores. Siempre tiene que quedar uno: una organización
     * sin dueño no la puede recuperar nadie desde la aplicación.
     */
    OWNER,

    /**
     * Administra la organización: crea espacios, invita gente y entra a
     * cualquier cliente. Es el socio o el director de cuentas.
     */
    ADMIN,

    /**
     * Trabaja solo en los espacios a los que se le asignó, con los permisos
     * que se le hayan dado en cada uno. Es el subordinado: el community
     * manager que lleva tres clientes y no ve los otros veintisiete.
     */
    MEMBER;

    /** Manda sobre todos los espacios de su organización. */
    public boolean administraLaOrganizacion() {
        return this == OWNER || this == ADMIN;
    }
}
