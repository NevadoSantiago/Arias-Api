package com.arias.users;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Lookup principal — filtra soft-deleted. Cualquier user con deleted_at != NULL
     * es invisible para auth, listings y validaciones de duplicate-email.
     */
    @Query("SELECT u FROM User u WHERE u.email = :email AND u.deletedAt IS NULL")
    Optional<User> findByEmail(@Param("email") String email);

    /**
     * Para el flujo de check-email del login:
     * existe el user y NO tiene password_hash (= primer ingreso pendiente).
     */
    @Query("""
        SELECT COUNT(u) > 0 FROM User u
        WHERE u.email = :email
          AND u.passwordHash IS NULL
          AND u.active = true
          AND u.deletedAt IS NULL
    """)
    boolean isFirstLoginPending(@Param("email") String email);

    /** Empleados activos de una empresa — para queries que no necesitan inactivos. */
    @Query("""
        SELECT u FROM User u
        WHERE u.company.id = :companyId
          AND u.role = :role
          AND u.active = true
          AND u.deletedAt IS NULL
        ORDER BY u.email ASC
    """)
    List<User> findAllByCompanyIdAndRoleAndActiveTrueOrderByEmailAsc(
        @Param("companyId") Long companyId, @Param("role") Role role);

    /** Para el panel admin: incluye inactivos pero NO los soft-deleted. */
    @Query("""
        SELECT u FROM User u
        WHERE u.company.id = :companyId
          AND u.role = :role
          AND u.deletedAt IS NULL
        ORDER BY u.email ASC
    """)
    List<User> findAllByCompanyIdAndRoleOrderByEmailAsc(
        @Param("companyId") Long companyId, @Param("role") Role role);

    /** Empleados + Admin de empresa juntos — para el listado del CompanyAdmin. */
    @Query("""
        SELECT u FROM User u
        WHERE u.company.id = :companyId
          AND u.role IN (com.arias.users.Role.EMPLOYEE, com.arias.users.Role.COMPANY_ADMIN)
          AND u.deletedAt IS NULL
        ORDER BY u.role DESC, u.email ASC
    """)
    List<User> findAllStaffByCompanyId(@Param("companyId") Long companyId);

    @Query("""
        SELECT COUNT(u) FROM User u
        WHERE u.company.id = :companyId
          AND u.active = true
          AND u.deletedAt IS NULL
          AND u.role = com.arias.users.Role.EMPLOYEE
    """)
    long countActiveEmployeesByCompany(@Param("companyId") Long companyId);

    /**
     * Usuarios elegibles para recibir el recordatorio diario de pedido:
     * EMPLOYEE o COMPANY_ADMIN (ambos hacen pedidos), activos, no soft-deleted,
     * empresa activa, opt-in al recordatorio, y SIN un pedido para la fecha dada.
     */
    @Query("""
        SELECT u FROM User u
        WHERE u.role IN (com.arias.users.Role.EMPLOYEE, com.arias.users.Role.COMPANY_ADMIN)
          AND u.active = true
          AND u.deletedAt IS NULL
          AND u.recibeRecordatorioPedido = true
          AND u.company IS NOT NULL
          AND u.company.enabled = true
          AND u.passwordHash IS NOT NULL
          AND NOT EXISTS (
              SELECT 1 FROM DailyChoice d
              WHERE d.user.id = u.id AND d.fecha = :fecha
          )
    """)
    List<User> findReminderRecipientsForDate(@Param("fecha") LocalDate fecha);

    /**
     * Administradores del restaurante — destinatarios del resumen matutino y
     * de la alerta de cancelación (unidad 12, diseño §Decisión 11).
     */
    @Query("""
        SELECT u FROM User u
        WHERE u.role = com.arias.users.Role.SUPER_ADMIN
          AND u.active = true
          AND u.deletedAt IS NULL
    """)
    List<User> findActiveSuperAdmins();

    /**
     * Para el autorregistro público: rechaza un teléfono ya asociado a otra
     * cuenta activa (spec {@code self-registration}, "Validación de teléfono
     * contra duplicados"). Filtra soft-deleted, igual que {@link #findByEmail}.
     */
    @Query("SELECT COUNT(u) > 0 FROM User u WHERE u.phone = :phone AND u.deletedAt IS NULL")
    boolean existsActivePhone(@Param("phone") String phone);

    /** Primer user con un role específico en una empresa — para CompanyAdmin principal. */
    @Query("""
        SELECT u FROM User u
        WHERE u.company.id = :companyId
          AND u.role = :role
          AND u.deletedAt IS NULL
        ORDER BY u.id ASC
        LIMIT 1
    """)
    Optional<User> findFirstByCompanyIdAndRoleOrderByIdAsc(
        @Param("companyId") Long companyId, @Param("role") Role role);
}
