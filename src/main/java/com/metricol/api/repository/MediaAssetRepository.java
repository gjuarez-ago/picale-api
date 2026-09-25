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
     * <p>En SQL nativo por lo de siempre: corre sin usuario, y una consulta de
     * JPA la filtraría Hibernate por un tenant que aquí no existe.
     */
    @Query(value = """
            select cast(m.id as varchar), m.tenant_id
            from media_assets m
            where m.status = 'READY'
              and m.archived_at is null
              and m.created_at < :limite
              and not exists (
                    select 1 from post_media pm
                    join posts p on p.id = pm.post_id
                    where pm.url = m.url and p.deleted_at is null
              )
            order by m.created_at asc
            limit :tope
            """, nativeQuery = true)
    List<Object[]> findListosSinUsar(LocalDateTime limite, int tope);

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
}
