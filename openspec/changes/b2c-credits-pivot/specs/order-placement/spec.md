# Especificación: order-placement

## Purpose

Reemplazar el modelo `DailyChoice` (un pedido por usuario por día) por `Order` + `OrderItem`, permitiendo múltiples pedidos por día y múltiples ítems por pedido, con el total en créditos calculado como la suma de los ítems y debitado del saldo de créditos del usuario — tanto para clientes B2C como para empleados de empresas.

## Requirements

### Requirement: Múltiples pedidos por día
El sistema MUST permitir a un usuario realizar más de un `Order` en el mismo día, eliminando la restricción de un único pedido diario del modelo anterior.

#### Scenario: Segundo pedido en el mismo día
- GIVEN un usuario que ya realizó un pedido hoy
- WHEN realiza un segundo pedido el mismo día con saldo suficiente
- THEN el sistema acepta el segundo pedido de forma independiente del primero

### Requirement: Pedidos con múltiples ítems
El sistema MUST permitir que un `Order` contenga múltiples `OrderItem`, cada uno con su propia instantánea (nombre del plato, categoría, costo en créditos al momento del pedido).

#### Scenario: Pedido con varios platos
- GIVEN un usuario armando un pedido
- WHEN agrega tres platos de distintas categorías antes de confirmar
- THEN el sistema crea un `Order` con tres `OrderItem`, cada uno con su instantánea propia

### Requirement: Total del pedido como suma de los ítems
El sistema MUST calcular el total en créditos de un `Order` como la suma del `creditCost` de cada `OrderItem` que lo compone.

#### Scenario: Cálculo del total
- GIVEN un pedido con ítems de costo 1, 1 y 2 almuerzos
- WHEN se confirma el pedido
- THEN el total del pedido es 4 almuerzos

### Requirement: Bloqueo por saldo insuficiente
El sistema MUST bloquear la confirmación de un pedido cuando el saldo AVAILABLE del usuario es menor al total en créditos del pedido, y MUST NOT comprometer parcialmente créditos en ese caso.

#### Scenario: Saldo insuficiente
- GIVEN un usuario con saldo AVAILABLE de 2 almuerzos
- WHEN intenta confirmar un pedido cuyo total es 3 almuerzos
- THEN el sistema rechaza la confirmación
- AND el saldo del usuario permanece sin cambios

### Requirement: Decremento de stock al confirmar
El sistema MUST decrementar de forma atómica el stock de cada plato pedido al confirmar el `Order`.

#### Scenario: Stock agotado durante la confirmación
- GIVEN un plato con stock actual de 0
- WHEN un usuario intenta incluirlo en un pedido
- THEN el sistema rechaza ese ítem antes de comprometer créditos

### Requirement: Los pedidos de empleados de empresas también consumen créditos
El sistema MUST debitar créditos del saldo del empleado para todo pedido realizado por un usuario asociado a una empresa (B2B), reemplazando el uso de `CompanyCategoryPrice`/`precioSnapshot` como fuente de costo del pedido.

#### Scenario: Pedido de un empleado de empresa
- GIVEN un empleado de una empresa con saldo de créditos propio
- WHEN realiza un pedido dentro del flujo de empresa existente
- THEN el sistema comprometen créditos de su saldo igual que a un cliente B2C
- AND el flujo de alta/lista blanca de la empresa continúa funcionando sin cambios
