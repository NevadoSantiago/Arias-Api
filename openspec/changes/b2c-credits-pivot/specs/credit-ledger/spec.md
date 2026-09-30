# Especificación: credit-ledger

## Purpose

Llevar el libro mayor de créditos de cada usuario: saldo AVAILABLE vs. COMMITTED, compromiso al pedir, consumo automático en el punto de confirmación del pedido, devolución al cancelar, vencimiento global renovable solo por compra de paquetes, y auditoría completa de cada movimiento.

## Requirements

### Requirement: Saldo AVAILABLE y COMMITTED
El sistema MUST mantener, por usuario, un saldo separado en estado AVAILABLE (disponible para nuevos pedidos) y COMMITTED (comprometido en pedidos ya confirmados y aún no consumidos).

#### Scenario: Consulta de saldo
- GIVEN un usuario con 5 almuerzos AVAILABLE y 2 COMMITTED
- WHEN consulta su saldo
- THEN el sistema muestra ambos valores por separado

### Requirement: Compromiso de créditos al confirmar un pedido
El sistema MUST mover del saldo AVAILABLE al saldo COMMITTED una cantidad igual al total en créditos del pedido, al momento de confirmarlo.

#### Scenario: Compromiso exitoso
- GIVEN un usuario con 5 almuerzos AVAILABLE
- WHEN confirma un pedido de 2 almuerzos
- THEN su saldo pasa a 3 AVAILABLE y 2 COMMITTED

### Requirement: Consumo automático en el punto de confirmación del pedido
El sistema MUST consumir automáticamente los créditos COMMITTED de un pedido cuando el reloj alcanza el horario de retiro menos la antelación configurable (20 minutos por defecto), registrando un movimiento de consumo definitivo.

#### Scenario: Consumo automático
- GIVEN un pedido con créditos COMMITTED y horario de retiro a las 13:00, con antelación configurada de 20 minutos
- WHEN el reloj llega a las 12:40
- THEN el sistema consume automáticamente esos créditos y registra el movimiento

### Requirement: Devolución a AVAILABLE al cancelar antes de la confirmación
El sistema MUST devolver a AVAILABLE los créditos COMMITTED de un pedido cancelado antes de su punto de confirmación (retiro − antelación configurable).

#### Scenario: Cancelación a tiempo
- GIVEN un pedido con 2 almuerzos COMMITTED, cuyo punto de confirmación aún no llegó
- WHEN el usuario lo cancela
- THEN esos 2 almuerzos vuelven a AVAILABLE

#### Scenario: Cancelación fuera de tiempo
- GIVEN un pedido cuyo punto de confirmación ya pasó y sus créditos ya fueron consumidos
- WHEN el usuario intenta cancelarlo
- THEN el sistema no lo permite como cancelación con devolución de créditos

### Requirement: Vencimiento global del saldo
El sistema MUST aplicar una única fecha de vencimiento global por usuario a todo su saldo, con un valor por defecto de 90 días configurable por el administrador.

#### Scenario: Vencimiento por defecto
- GIVEN la configuración del administrador sin cambios
- WHEN se calcula la fecha de vencimiento de un saldo nuevo
- THEN es 90 días desde el evento que la generó

### Requirement: Renovación del vencimiento solo por compra de paquetes
El sistema MUST renovar la fecha de vencimiento global únicamente cuando el usuario compra un paquete, y MUST NOT renovarla por una compra directa de un solo pedido ni por un reembolso.

#### Scenario: Renovación por compra de paquete
- GIVEN un usuario con una fecha de vencimiento vigente
- WHEN compra un paquete de créditos
- THEN la fecha de vencimiento se renueva a partir de esa compra

#### Scenario: Sin renovación por reembolso
- GIVEN un usuario cuyo pago fue revertido (`refunded`/`charged_back`)
- WHEN el sistema le devuelve los créditos correspondientes
- THEN la fecha de vencimiento del usuario no cambia

### Requirement: Movimiento de vencimiento auditable
El sistema MUST registrar, al llegar la fecha de vencimiento, un movimiento auditable de tipo EXPIRATION que pone en cero el saldo AVAILABLE vencido, en lugar de eliminar registros.

#### Scenario: Vencimiento del saldo
- GIVEN un usuario con saldo AVAILABLE cuya fecha de vencimiento se cumplió
- WHEN el proceso de vencimiento se ejecuta
- THEN el sistema registra un movimiento EXPIRATION y el saldo AVAILABLE queda en cero

### Requirement: Auditoría completa de movimientos
El sistema MUST registrar todo cambio de saldo como un movimiento inmutable del libro mayor, con tipo, cantidad, marca de tiempo y referencia al origen (pedido, compra, vencimiento, reembolso).

#### Scenario: Trazabilidad de un movimiento
- GIVEN cualquier cambio de saldo ya ocurrido
- WHEN se consulta el historial del usuario
- THEN cada cambio aparece como un movimiento individual con su tipo y origen
