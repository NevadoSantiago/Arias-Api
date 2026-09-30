# Especificación: admin-order-fulfillment

## Purpose

Incorporar a las vistas administrativas de pedidos la agrupación y exportación por horario de retiro, necesaria para el flujo B2C de retiro en el restaurante, conservando íntegro el flujo existente de agrupación por empresa.

## Requirements

### Requirement: Agrupación y exportación por horario de retiro
El sistema MUST permitir al administrador listar y exportar los pedidos del día agrupados por horario de retiro.

#### Scenario: Listado agrupado por horario
- GIVEN pedidos del día con distintos horarios de retiro
- WHEN el administrador solicita el listado agrupado por horario de retiro
- THEN el sistema devuelve los pedidos agrupados por ese criterio

#### Scenario: Exportación agrupada por horario
- GIVEN pedidos del día con distintos horarios de retiro
- WHEN el administrador exporta el listado por horario de retiro
- THEN el archivo exportado agrupa los pedidos por ese criterio

### Requirement: El flujo existente de agrupación por empresa se conserva
El sistema MUST mantener sin cambios de comportamiento la agrupación, exportación y marcado de entrega de pedidos por empresa (`companyId`) ya existente.

#### Scenario: Listado agrupado por empresa
- GIVEN pedidos del día pertenecientes a distintas empresas
- WHEN el administrador solicita el listado agrupado por empresa como hoy
- THEN el sistema devuelve los pedidos agrupados por empresa igual que antes de este cambio

#### Scenario: Marcado de entrega por empresa
- GIVEN un conjunto de pedidos de una empresa
- WHEN el administrador los marca como entregados usando el flujo existente por empresa
- THEN el sistema los marca como entregados igual que antes de este cambio
