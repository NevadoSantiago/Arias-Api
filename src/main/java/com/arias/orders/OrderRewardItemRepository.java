package com.arias.orders;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRewardItemRepository extends JpaRepository<OrderRewardItem, Long> {

    List<OrderRewardItem> findByOrderId(Long orderId);
}
