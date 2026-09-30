package com.arias.orders.notifications;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRunLogRepository extends JpaRepository<NotificationRunLog, NotificationRunLogId> {
}
