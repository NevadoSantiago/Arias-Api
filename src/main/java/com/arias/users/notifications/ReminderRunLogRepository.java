package com.arias.users.notifications;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

public interface ReminderRunLogRepository extends JpaRepository<ReminderRunLog, LocalDate> {
}
