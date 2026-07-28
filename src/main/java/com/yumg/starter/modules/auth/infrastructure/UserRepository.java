package com.yumg.starter.modules.auth.infrastructure;

import com.yumg.starter.entities.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.yumg.starter.entities.UserStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);
    long countByRoles_Code(String roleCode);
    List<User> findDistinctByRoles_Code(String roleCode);

    @Query("""
        select user from User user
        where (:query is null
            or lower(user.username) like concat('%', lower(:query), '%')
            or lower(user.displayName) like concat('%', lower(:query), '%'))
          and (:status is null or user.status = :status)
        """)
    Page<User> search(@Param("query") String query, @Param("status") UserStatus status, Pageable pageable);

    @EntityGraph(attributePaths = "roles")
    @Query("select distinct user from User user where user.id in :ids")
    List<User> findAllWithRolesByIdIn(@Param("ids") Collection<String> ids);

    @EntityGraph(attributePaths = "roles")
    @Query("select distinct user from User user")
    List<User> findAllWithRoles();

    @EntityGraph(attributePaths = "roles")
    @Query("select user from User user where user.id = :id")
    Optional<User> findWithRolesById(@Param("id") String id);
}
