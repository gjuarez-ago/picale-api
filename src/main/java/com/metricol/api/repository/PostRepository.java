package com.metricol.api.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.Post;
import com.metricol.api.enums.PostStatus;
import com.metricol.api.models.response.PostStatusResponse;

public interface PostRepository extends JpaRepository<Post, UUID> {

    List<Post> findAllByOrderByScheduledAtAscCreatedAtDesc();

    List<Post> findTop5ByOrderByCreatedAtDesc();

    /**
     * Solo el estado de cada publicación, sin tocar medios ni destinos.
     *
     * <p>Es la consulta que la app pide cada pocos segundos mientras algo
     * está saliendo, así que tenía que ser barata de verdad.
     * {@code findAllByOrderBy...} habría servido, pero arrastra la lista de
     * medios de cada publicación y, al armar la respuesta, una consulta por
     * cada destino para leer su cuenta de red. Sondear eso cada cinco
     * segundos es exactamente lo que tumba el servicio.
     *
     * <p>Aquí son tres columnas de una sola tabla. Hibernate sigue filtrando
     * por {@code @TenantId} igual que en cualquier otra consulta.
     */
    @Query("""
            select new com.metricol.api.models.response.PostStatusResponse(
                p.id, p.status, p.publishedAt)
            from Post p
            """)
    List<PostStatusResponse> findEstados();

    long countByStatus(PostStatus status);

    /**
     * Cuantas del workspace actual estan en alguno de esos estados, sin
     * contar una (la que se esta editando). Hibernate filtra por tenant.
     */
    long countByStatusInAndIdNot(List<PostStatus> estados, UUID excluir);

    /** Cuantas creo el workspace actual en ese rango. Hibernate filtra por tenant. */
    long countByCreatedAtBetween(LocalDateTime desde, LocalDateTime hasta);

    /**
     * ¿Alguna publicación usa este archivo?
     *
     * <p>Lo pregunta {@code MediaService.delete} antes de borrar la miniatura
     * del video. El archivo se va —lo pidió la persona, y es lo que le libera
     * espacio— pero su fotograma se queda si hay una publicación que lo
     * nombra: es lo único que hace que esa publicación siga viéndose. Pesa
     * unos cientos de kilobytes contra los cien megas del video.
     *
     * <p>Sin esto pasaba lo que se vio en producción: alguien borró un video
     * para hacer sitio y la publicación que ya había salido se quedó sin
     * portada para siempre, sin forma de recuperarla.
     */
    @Query(value = "select exists (select 1 from post_media pm where pm.url = :url)",
            nativeQuery = true)
    boolean algunaPublicacionUsa(String url);

    /**
     * Copia a cada publicación la portada de su video, donde todavía falte.
     *
     * <p>Las que ya existían cuando se añadió la columna resuelven su portada
     * mirando la fila del archivo, así que se ven bien — hasta que alguien
     * borra ese archivo para hacer sitio, y entonces la pierden para siempre.
     * Ya pasó con una. Esto se la pega ahora, mientras la fila sigue ahí.
     *
     * <p>Corre al arrancar y no hace nada las siguientes veces: solo toca las
     * que tienen la portada vacía.
     *
     * <p>En SQL nativo por lo de siempre: alcanza a todos los workspaces, y ahí
     * no hay usuario del cual deducir el tenant que {@code @TenantId} exigiría.
     */
    @Modifying
    @Transactional
    @Query(value = """
            update posts p set thumbnail_url = (
                select m.thumbnail_url
                from post_media pm
                join media_assets m on m.url = pm.url
                where pm.post_id = p.id and m.thumbnail_url is not null
                limit 1
            )
            where p.thumbnail_url is null
              and exists (
                    select 1 from post_media pm2
                    join media_assets m2 on m2.url = pm2.url
                    where pm2.post_id = p.id and m2.thumbnail_url is not null
              )
            """, nativeQuery = true)
    int fijarPortadasQueFaltan();

    /**
     * Las programadas que ya les tocaba salir, de TODOS los workspaces.
     *
     * <p>Va en SQL nativo a propósito. {@code Post} lleva {@code @TenantId},
     * así que cualquier consulta de JPA la filtra Hibernate por el tenant
     * actual — y el worker que llama aquí no tiene usuario, luego no tiene
     * tenant. Con una consulta normal esto devolvería siempre vacío.
     *
     * <p>Devuelve el id y su tenant, no la entidad: con el tenant en mano el
     * worker ya puede cargar el post por el camino normal
     * ({@code TenantIdentifierResolver.comoTenant}) y publicar con las mismas
     * reglas que si lo pidiera una persona.
     *
     * <p>El tope evita que un rezago de mil publicaciones —tras un rato con
     * el servidor caído— intente salir todo en la misma vuelta.
     */
    @Query(value = """
            select cast(p.id as varchar), p.tenant_id
            from posts p
            where p.status = 'SCHEDULED'
              and p.scheduled_at is not null
              and p.scheduled_at <= :ahora
            order by p.scheduled_at asc
            limit :tope
            """, nativeQuery = true)
    List<Object[]> findProgramadasVencidas(LocalDateTime ahora, int tope);
}
