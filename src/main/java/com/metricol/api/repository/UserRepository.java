package com.metricol.api.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.metricol.api.entity.User;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    /**
     * El usuario con su workspace ya cargado, para el principal de cada
     * petición.
     *
     * <p>El {@code User} del JWT se carga en el filtro de seguridad, ANTES de
     * que exista la sesión de Hibernate de la petición. Con el workspace
     * perezoso, el proxy quedaba huérfano y el primer {@code getName()} —el
     * contexto del negocio para la IA— reventaba con
     * {@code LazyInitializationException: no session}: un 500 al componer el
     * texto. El {@code join fetch} lo trae de una vez.
     */
    @Query("select u from User u join fetch u.workspace where u.email = :email")
    Optional<User> findByEmailConWorkspace(String email);

    boolean existsByEmail(String email);

    /**
     * Quienes no tienen membresía en su propio workspace activo: todos los que
     * existían antes de que hubiera membresías. Ver WorkspaceMemberBackfill.
     */
    @Query("""
            select u from User u
            where not exists (
                select m.id from WorkspaceMember m
                where m.user = u and m.workspace = u.workspace)
            """)
    List<User> findSinMembresiaEnSuWorkspace();
}
