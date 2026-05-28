package com.arias.users;

import java.util.List;

/**
 * Resultado del bulk-add. Para cada email, te dice si lo creó o lo omitió.
 */
public record BulkCreateEmployeesResult(
    List<EmployeeDto> created,
    List<SkippedEmail> skipped
) {
    public record SkippedEmail(String email, String reason) {}
}
