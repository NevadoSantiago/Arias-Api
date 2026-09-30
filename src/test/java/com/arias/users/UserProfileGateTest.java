package com.arias.users;

import com.arias.companies.Company;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Only self-registered B2C customers (EMPLOYEE without company) must complete
 * phone and nickname. Company users and admins never go through
 * self-registration, so the gate must never block them.
 */
class UserProfileGateTest {

    private static User user(Role role, Company company, String phone, String nickname) {
        return User.builder().email("ana@test.arias.com")
            .role(role).company(company).phone(phone).nickname(nickname).build();
    }

    @Test
    @DisplayName("B2C customer without phone or nickname must complete the profile")
    void b2cIncompleteIsGated() {
        assertThat(user(Role.EMPLOYEE, null, null, null).mustCompleteProfileToSpend()).isTrue();
        assertThat(user(Role.EMPLOYEE, null, "+5491159876547", null).mustCompleteProfileToSpend()).isTrue();
    }

    @Test
    @DisplayName("B2C customer with phone and nickname is not gated")
    void b2cCompleteIsNotGated() {
        assertThat(user(Role.EMPLOYEE, null, "+5491159876547", "Coty").mustCompleteProfileToSpend()).isFalse();
    }

    @Test
    @DisplayName("company employee without phone or nickname is never gated")
    void companyEmployeeIsNotGated() {
        assertThat(user(Role.EMPLOYEE, new Company(), null, null).mustCompleteProfileToSpend()).isFalse();
    }

    @Test
    @DisplayName("admins without company, phone or nickname are never gated")
    void adminsAreNotGated() {
        assertThat(user(Role.SUPER_ADMIN, null, null, null).mustCompleteProfileToSpend()).isFalse();
        assertThat(user(Role.COMPANY_ADMIN, new Company(), null, null).mustCompleteProfileToSpend()).isFalse();
    }
}
