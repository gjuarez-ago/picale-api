package com.metricol.api.enums;

/**
 * Lo que una persona puede hacer dentro de un workspace.
 *
 * <p>Cada valor es una acción que el servidor comprueba de verdad, no una
 * etiqueta para pintar botones: un permiso que solo escondiera un botón no
 * sería un permiso, porque la petición se puede mandar a mano. La interfaz los
 * usa además para no ofrecer lo que va a ser rechazado, pero quien manda es
 * {@link com.metricol.api.service.PermissionService}.
 *
 * <p>Ver siempre se puede: quien está en el workspace ve sus publicaciones, su
 * contenido y sus redes. Por eso no hay un permiso "LEER" — sería un valor que
 * nadie puede quitar, y un permiso que no se puede negar confunde más de lo
 * que informa. Lo que se controla es lo que CAMBIA algo o gasta dinero.
 */
public enum Permission {

    /** Crear publicaciones y editarlas. Sin esto solo se mira. */
    POST_CREATE,

    /** Publicar ya, en el momento. */
    POST_PUBLISH,

    /** Dejar una publicación programada para después. */
    POST_SCHEDULE,

    /** Borrar o archivar publicaciones. */
    POST_DELETE,

    /** Borrar fotos y videos del contenido del workspace. */
    MEDIA_DELETE,

    /**
     * Conectar, reconectar y desconectar redes, y elegir la Página donde se
     * publica. Es el permiso delicado: quien lo tiene decide a qué cuentas
     * llega el contenido del cliente.
     */
    NETWORK_MANAGE,

    /** Invitar gente, cambiarle permisos y quitarla del workspace. */
    MEMBER_MANAGE,

    /** Cambiar los datos del workspace: nombre, giro, ciudad, descripción. */
    WORKSPACE_EDIT
}
