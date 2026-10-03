package com.metricol.api.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.MediaAssetStatus;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    /**
     * La galería: solo lo que está de verdad en R2.
     *
     * <p>Un PENDING no se enseña porque puede no existir —el teléfono pudo
     * quedarse sin batería entre la firma y la subida—, y una miniatura rota
     * es peor que una ausencia.
     */
    /**
     * Los archivos de estas URLs, para no pedirlos de uno en uno.
     *
     * <p>El filtro por tenant lo pone Hibernate solo (@TenantId), asi que esto
     * nunca alcanza archivos de otro workspace aunque le pasen su URL.
     */
    List<MediaAsset> findByUrlIn(java.util.Collection<String> urls);

    List<MediaAsset> findByStatusOrderByCreatedAtDesc(MediaAssetStatus status);

    List<MediaAsset> findAllByOrderByCreatedAtDesc();

    /**
     * La galería de Contenido: lo confirmado y no archivado, sin logotipos.
     *
     * <p>Un logotipo no es contenido. Se reconoce por las dos vías: por su
     * carpeta ({@code logos/}, donde sube el cambio de logotipo desde que
     * existe) y por ser el {@code logo_url} de algún espacio (los que se
     * subieron antes a {@code media/}, o desde la biblioteca). Lo primero
     * lo esconde aunque todavía no se haya guardado en el espacio.
     */
    @Query("""
            select m from MediaAsset m
            where m.status = com.metricol.api.enums.MediaAssetStatus.READY
              and m.archivedAt is null
              and (m.storageKey is null or m.storageKey not like 'logos/%')
              and not exists (select 1 from Workspace w where w.logoUrl = m.url)
            order by m.createdAt desc
            """)
    List<MediaAsset> galeria();

    /** Lo archivado, sin logotipos, lo último en archivarse primero. */
    @Query("""
            select m from MediaAsset m
            where m.status = com.metricol.api.enums.MediaAssetStatus.READY
              and m.archivedAt is not null
              and (m.storageKey is null or m.storageKey not like 'logos/%')
              and not exists (select 1 from Workspace w where w.logoUrl = m.url)
            order by m.archivedAt desc
            """)
    List<MediaAsset> archivados();

    /** La galería del día a día: lo confirmado y no archivado. */
    List<MediaAsset> findByStatusAndArchivedAtIsNullOrderByCreatedAtDesc(MediaAssetStatus status);

    /** Lo archivado, lo último en archivarse primero. Se puede devolver a la galería. */
    List<MediaAsset> findByStatusAndArchivedAtIsNotNullOrderByArchivedAtDesc(MediaAssetStatus status);

    /**
     * Pone READY a las filas anteriores a que existiera la columna de estado.
     *
     * <p>Hace falta porque el esquema se actualiza con {@code ddl-auto=update},
     * que añade la columna nueva pero la deja en {@code null} en todo lo que ya
     * estaba. Y la galería pide {@code status = READY}, así que sin esto todos
     * los medios ya subidos habrían desaparecido de la pantalla de Contenido en
     * el primer despliegue — el archivo seguiría en R2, pero nadie lo vería.
     *
     * <p>En SQL nativo por lo de siempre: {@code MediaAsset} lleva
     * {@code @TenantId}, y esto tiene que alcanzar a todos los workspaces
     * corriendo al arrancar, donde no hay usuario del cual deducir ninguno.
     */
    @Modifying
    @Transactional
    @Query(value = "update media_assets set status = 'READY' where status is null",
            nativeQuery = true)
    int marcarAntiguosComoListos();

    /**
     * Quita el CHECK que la base puso sobre los valores del estado.
     *
     * <p>Hibernate genera un {@code check (status in (...))} al CREAR la tabla,
     * con los valores que tenía el enum ese día — y con {@code ddl-auto=update}
     * no lo vuelve a tocar nunca. Así que añadir {@code RELEASED} al enum
     * compila, pasa los tests de una base recién creada, y en producción falla
     * al escribir: {@code violates check constraint media_assets_status_check}.
     *
     * <p>Lo encontró una prueba contra la base de verdad. Contra una vacía no
     * se ve: la tabla nace con el enum nuevo y el check ya lo incluye.
     *
     * <p>Se quita en vez de recrearlo con los valores buenos porque el
     * siguiente valor del enum volvería a chocar, y quien lo añada no va a
     * acordarse de esto. Quien valida el estado es el enum de Java, que es el
     * único sitio por donde se escribe.
     *
     * <p>{@code if exists} para que sea idempotente: corre en cada arranque.
     */
    @Modifying
    @Transactional
    @Query(value = "alter table media_assets drop constraint if exists media_assets_status_check",
            nativeQuery = true)
    void quitarCheckDeEstado();

    /** Lo mismo que {@link #quitarCheckDeEstado()}, para la etapa del agente ({@code EtapaAgente}). */
    @Modifying
    @Transactional
    @Query(value = "alter table media_assets drop constraint if exists media_assets_agente_etapa_check",
            nativeQuery = true)
    void quitarCheckDeEtapaDelAgente();

    /**
     * Las imágenes que creó la IA antes de que existiera {@code creadaConIa}:
     * Crear con IA las nombra "contenido-v…". Las copias retocadas o con logo
     * no llevan ese nombre, así que no se confunden.
     */
    @Modifying
    @Transactional
    @Query(value = """
            update media_assets set creada_con_ia = true
            where creada_con_ia is null and generada_por_ia = true and file_name like 'contenido-v%'
            """, nativeQuery = true)
    int marcarCreadasConIa();

    /**
     * Videos ya confirmados a los que todavía les falta la miniatura.
     *
     * <p>En SQL nativo por lo mismo que la consulta de arriba: corre al
     * arrancar, tiene que alcanzar a TODOS los workspaces, y ahí no hay
     * usuario del cual deducir el tenant que {@code @TenantId} exigiría.
     *
     * <p>Con tope y por los más nuevos primero: si hay mil videos viejos, los
     * que alguien va a mirar hoy son los últimos que subió.
     */
    @Query(value = "select * from media_assets"
            + " where type = 'VIDEO' and status = 'READY' and thumbnail_url is null"
            + " order by created_at desc limit :tope",
            nativeQuery = true)
    List<MediaAsset> findVideosSinMiniatura(@org.springframework.data.repository.query.Param("tope") int tope);

    /**
     * Bytes que lleva usados el workspace actual, contando los PENDING.
     *
     * <p>Los pendientes cuentan a propósito: son espacio apartado. Si no
     * contaran, pedir seis firmas seguidas pasaría del límite entre todas —
     * cada una miraría un total que las otras cinco todavía no han movido—.
     * Lo que se aparta y no se usa lo libera la limpieza de huérfanos.
     *
     * <p>No hace falta filtrar por workspace a mano: {@code MediaAsset} lleva
     * {@code @TenantId} y Hibernate añade el filtro. Escribirlo aquí además
     * habría sido peor que redundante —dos sitios que pueden discrepar sobre
     * qué workspace es el actual—.
     *
     * <p>El {@code coalesce} de dentro es por las filas anteriores a que
     * existiera la columna: cuentan como 0 en vez de anular la suma entera.
     */
    /// <p>Los RELEASED no cuentan: su archivo ya no está en R2. Es justo el
    /// punto de liberarlos — devolverle el espacio a la persona sin que tenga
    /// que borrar a mano lo que ya publicó.
    @Query("select coalesce(sum(coalesce(m.sizeBytes, 0)), 0) from MediaAsset m"
            + " where m.status <> com.metricol.api.enums.MediaAssetStatus.RELEASED")
    long espacioUsado();

    /**
     * Los prefirmados que llevan demasiado tiempo sin confirmarse, de TODOS
     * los workspaces.
     *
     * <p>Va en SQL nativo por lo mismo que {@code findProgramadasVencidas}:
     * {@code MediaAsset} lleva {@code @TenantId}, así que cualquier consulta
     * de JPA la filtra Hibernate por el tenant actual — y el worker que llama
     * aquí no tiene usuario, luego no tiene tenant. Con una consulta normal
     * esto devolvería siempre vacío y los huérfanos se acumularían para
     * siempre, ocupando cuota de gente que no subió nada.
     */
    @Query(value = """
            select cast(m.id as varchar), m.tenant_id, m.storage_key
            from media_assets m
            where m.status = 'PENDING'
              and m.created_at < :limite
            order by m.created_at asc
            limit :tope
            """, nativeQuery = true)
    List<Object[]> findPrefirmadosViejos(LocalDateTime limite, int tope);

    /**
     * Los archivos ya subidos que ninguna publicación usa, de TODOS los
     * workspaces.
     *
     * <p>Es el otro cabo suelto de la subida optimista. La app empieza a subir
     * en cuanto se elige la foto, antes de saber si esa publicación se va a
     * guardar: si después falla el {@code POST /posts}, o si la persona cierra
     * la pantalla sin publicar, el archivo se queda READY en R2 ocupando su
     * parte de la cuota sin que nada apunte a él. No es un huérfano de los que
     * busca {@code findPrefirmadosViejos} —ese mira los PENDING, y este ya
     * está confirmado—, así que hasta ahora no lo miraba nadie.
     *
     * <p>El {@code not exists} contra {@code post_media} es lo que decide. Un
     * borrador cuenta como uso: sus URLs están en esa tabla desde que se
     * guarda, así que las fotos de un borrador no se tocan. Una publicación
     * que la persona eliminó ya no cuenta: para ella no existe.
     *
     * <p>El logotipo de un espacio tampoco se toca, aunque ninguna publicación
     * lo use nunca: eso es lo normal en un logotipo. Sin esta condición la
     * limpieza los borraba a los tres días y los espacios se quedaban con la
     * imagen rota (reportado el 27 sep 2026). El logotipo que se reemplazó sí
     * se limpia: ya no es el de nadie.
     *
     * <p>En SQL nativo por lo de siempre: corre sin usuario, y una consulta de
     * JPA la filtraría Hibernate por un tenant que aquí no existe.
     */
    @Query(value = """
            select cast(m.id as varchar), m.tenant_id
            from media_assets m
            where m.status = 'READY'
              and m.archived_at is null
              and m.created_at < :limite
              and m.agente_etapa is null
              and not exists (
                    select 1 from post_media pm
                    join posts p on p.id = pm.post_id
                    where pm.url = m.url and p.deleted_at is null
              )
              and not exists (
                    select 1 from workspaces w where w.logo_url = m.url
              )
            order by m.created_at asc
            limit :tope
            """, nativeQuery = true)
    // agente_etapa: lo que el agente dejó en observación, descartado o en
    // espera no está en ninguna publicación, y sin esta condición se borraba
    // a los tres días. "Nada se borra" es una de sus reglas.
    List<Object[]> findListosSinUsar(LocalDateTime limite, int tope);

    /**
     * Lo que le toca revisar al agente en el workspace actual: fotos subidas
     * por la persona desde que se encendió, que nadie ha usado ni él ha visto.
     *
     * <p>Fotos, y videos que ya tienen portada: la IA mira el video por su
     * fotograma, así que uno recién subido espera unos segundos a tenerlo.
     */
    @Query("""
            select m from MediaAsset m
            where m.status = com.metricol.api.enums.MediaAssetStatus.READY
              and m.archivedAt is null
              and (m.type = com.metricol.api.enums.MediaType.IMAGE
                   or (m.type = com.metricol.api.enums.MediaType.VIDEO and m.thumbnailUrl is not null))
              and (m.generadaPorIa is null or m.generadaPorIa = false)
              and (m.agenteEtapa is null or m.agenteEtapa = com.metricol.api.enums.EtapaAgente.PENDIENTE
                   or (m.agenteEtapa = com.metricol.api.enums.EtapaAgente.REVISANDO and m.agenteTomadoEn < :vencido))
              and m.createdAt >= :desde
              and (m.storageKey is null or m.storageKey not like 'logos/%')
              and not exists (select 1 from Workspace w where w.logoUrl = m.url)
              and not exists (select 1 from Post p join p.mediaUrls u where u = m.url and p.deletedAt is null)
            order by coalesce(m.agenteIntentos, 0) asc, m.createdAt asc
            """)
    // Las que ya fallaron van al final: si no, cuatro que fallan siempre taparían todo lo nuevo.
    List<MediaAsset> paraElAgente(LocalDateTime desde, LocalDateTime vencido,
            org.springframework.data.domain.Pageable pagina);

    /**
     * Toma un archivo para revisarlo: el candado contra dos revisiones a la vez
     * (el proceso de fondo y un botón, o dos botones). Atómico en la base: de
     * dos que lo pidan juntos, solo a uno le devuelve 1.
     *
     * <p>Se puede tomar si nadie lo tiene, si quedó pendiente, si está en
     * observación o descartada (la persona pide revisarla otra vez), o si quien
     * lo tomó lleva más de {@code vencido} sin soltarlo: se cayó a la mitad.
     */
    @Modifying
    @Transactional
    @Query("""
            update MediaAsset m
            set m.agenteEtapa = com.metricol.api.enums.EtapaAgente.REVISANDO, m.agenteTomadoEn = :ahora
            where m.id = :id
              and (m.agenteEtapa is null
                   or m.agenteEtapa in (com.metricol.api.enums.EtapaAgente.PENDIENTE,
                                        com.metricol.api.enums.EtapaAgente.OBSERVACION,
                                        com.metricol.api.enums.EtapaAgente.DESCARTADA,
                                        com.metricol.api.enums.EtapaAgente.ANALIZADA)
                   or (m.agenteEtapa = com.metricol.api.enums.EtapaAgente.REVISANDO and m.agenteTomadoEn < :vencido))
            """)
    int tomar(UUID id, LocalDateTime ahora, LocalDateTime vencido);

    /** Cuántas le faltan por revisar, con las mismas condiciones que {@link #paraElAgente}. */
    @Query("""
            select count(m) from MediaAsset m
            where m.status = com.metricol.api.enums.MediaAssetStatus.READY
              and m.archivedAt is null
              and (m.type = com.metricol.api.enums.MediaType.IMAGE
                   or (m.type = com.metricol.api.enums.MediaType.VIDEO and m.thumbnailUrl is not null))
              and (m.generadaPorIa is null or m.generadaPorIa = false)
              and (m.agenteEtapa is null or m.agenteEtapa in (com.metricol.api.enums.EtapaAgente.PENDIENTE,
                                                             com.metricol.api.enums.EtapaAgente.REVISANDO,
                                                             com.metricol.api.enums.EtapaAgente.ANALIZADA))
              and m.createdAt >= :desde
              and (m.storageKey is null or m.storageKey not like 'logos/%')
              and not exists (select 1 from Workspace w where w.logoUrl = m.url)
              and not exists (select 1 from Post p join p.mediaUrls u where u = m.url and p.deletedAt is null)
            """)
    long porRevisarDelAgente(LocalDateTime desde);

    List<MediaAsset> findByAgenteEtapaOrderByCreatedAtDesc(com.metricol.api.enums.EtapaAgente etapa);

    /** Las fotos que el agente ya trabajó y tienen huella: contra ellas se buscan repetidas. */
    @Query("""
            select m from MediaAsset m
            where m.huella is not null
              and m.agenteEtapa in (com.metricol.api.enums.EtapaAgente.PROPUESTA,
                                    com.metricol.api.enums.EtapaAgente.APROBADA,
                                    com.metricol.api.enums.EtapaAgente.OBSERVACION,
                                    com.metricol.api.enums.EtapaAgente.ANALIZADA)
            """)
    List<MediaAsset> yaTrabajadasConHuella();

    /** Las que la persona rescató ("sí va"), las más nuevas primero: lo que el revisor ya no debe dudar. */
    @Query("""
            select m from MediaAsset m
            where m.agenteRescatada = true and m.descripcionIa is not null
            order by m.createdAt desc
            """)
    List<MediaAsset> rescatadas(org.springframework.data.domain.Pageable pagina);

    long countByAgenteEtapa(com.metricol.api.enums.EtapaAgente etapa);

    /** Las que esperan a organizarse, en el orden en que se subieron. Hibernate filtra por tenant. */
    List<MediaAsset> findByAgenteEtapaOrderByCreatedAtAscIdAsc(com.metricol.api.enums.EtapaAgente etapa);

    /**
     * Toma una analizada para organizarla (pasa a REVISANDO), o 0 si otro ya la
     * tomó: el proceso de fondo y un botón no arman dos veces la misma tanda.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("""
            update MediaAsset m
            set m.agenteEtapa = com.metricol.api.enums.EtapaAgente.REVISANDO, m.agenteTomadoEn = :ahora
            where m.id = :id and m.agenteEtapa = com.metricol.api.enums.EtapaAgente.ANALIZADA
            """)
    int tomarAnalizada(@org.springframework.data.repository.query.Param("id") java.util.UUID id,
            @org.springframework.data.repository.query.Param("ahora") LocalDateTime ahora);

    /**
     * La clave en R2 de cada archivo que sigue vivo, con su workspace.
     *
     * <p>Lo pide la barrida de derivados, que va al revés que todo lo demás:
     * el nombre de la carpeta de un derivado es un resumen de la clave del
     * original, y un resumen no se deshace. Así que no se puede mirar un
     * derivado y preguntar de quién salió — hay que calcular el resumen de
     * todo lo que vive y borrar las carpetas que no salgan en esa lista.
     */
    @Query(value = "select m.tenant_id, m.storage_key from media_assets m"
            + " where m.storage_key is not null", nativeQuery = true)
    List<Object[]> findClavesVivas();

    /**
     * Los videos que ya salieron hace tiempo y se pueden liberar.
     *
     * <p>Tres condiciones, y las tres importan:
     *
     * <ul>
     *   <li><b>Es un video.</b> Las fotos no se liberan: son la biblioteca que
     *       se reutiliza y pesan mil veces menos.</li>
     *   <li><b>Alguna publicación suya ya salió</b> y hace más del plazo. Una
     *       vez publicado, cada red guarda su copia; la nuestra solo cuesta.</li>
     *   <li><b>Ninguna publicación suya está sin salir.</b> Es la condición que
     *       evita el daño: el mismo video puede estar en una publicada de hace
     *       dos meses y en una programada para mañana, y borrarlo dejaría a la
     *       segunda sin archivo que publicar.</li>
     * </ul>
     *
     * <p>En SQL nativo por lo de siempre: corre sin usuario, y una consulta de
     * JPA la filtraría Hibernate por un tenant que aquí no existe.
     */
    @Query(value = """
            select cast(m.id as varchar), m.tenant_id, m.storage_key
            from media_assets m
            where m.status = 'READY'
              and m.type = 'VIDEO'
              and exists (
                    select 1 from post_media pm
                    join posts p on p.id = pm.post_id
                    where pm.url = m.url
                      and p.status = 'PUBLISHED'
                      and p.published_at < :limite
                      and p.deleted_at is null
              )
              and not exists (
                    select 1 from post_media pm2
                    join posts p2 on p2.id = pm2.post_id
                    where pm2.url = m.url
                      and p2.status <> 'PUBLISHED'
                      and p2.deleted_at is null
              )
            order by m.created_at asc
            limit :tope
            """, nativeQuery = true)
    List<Object[]> findVideosLiberables(LocalDateTime limite, int tope);

    /**
     * Cuántos archivos subió el workspace desde {@code desde}: los de la
     * persona, no las copias de la IA. Hibernate filtra por tenant. Para el
     * tope diario de subidas ({@code app.media.max-subidas-por-dia}).
     */
    @Query("select count(a) from MediaAsset a where a.createdAt >= :desde and (a.generadaPorIa is null or a.generadaPorIa = false)"
            + " and (a.storageKey is null or a.storageKey not like 'logos/%')")
    long subidasDesde(@org.springframework.data.repository.query.Param("desde") java.time.LocalDateTime desde);
}
