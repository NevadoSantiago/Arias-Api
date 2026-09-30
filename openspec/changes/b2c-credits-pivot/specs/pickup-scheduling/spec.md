# Especificación: pickup-scheduling

## Purpose

Definir la programación del retiro de un pedido en el restaurante: ventana de semana actual y siguiente, horario de retiro elegido por el cliente dentro de una ventana de pedidos configurable, tiempo de preparación configurable, y sin límite de capacidad por horario.

## Requirements

### Requirement: Ventana de programación de semana actual y siguiente
El sistema MUST restringir la fecha de retiro seleccionable a la semana calendario actual o la semana siguiente, sin permitir fechas anteriores ni posteriores a ese rango.

#### Scenario: Fecha dentro de la ventana permitida
- GIVEN un cliente programando el retiro de un pedido
- WHEN selecciona una fecha dentro de la semana actual o la semana siguiente
- THEN el sistema acepta la fecha

#### Scenario: Fecha fuera de la ventana permitida
- GIVEN un cliente programando el retiro de un pedido
- WHEN selecciona una fecha posterior a la semana siguiente
- THEN el sistema rechaza la selección

### Requirement: Horario de retiro dentro de la ventana de pedidos configurable
El sistema MUST limitar los horarios de retiro ofrecidos a los que caen dentro de la ventana de pedidos configurada por el administrador (por ejemplo, horario de apertura/cierre del servicio).

#### Scenario: Horario fuera de servicio
- GIVEN una ventana de pedidos configurada de 11:00 a 15:00
- WHEN un cliente intenta seleccionar un horario de retiro a las 16:00
- THEN el sistema no lo ofrece como opción válida

### Requirement: Tiempo de preparación configurable
El sistema MUST ofrecer únicamente horarios de retiro que respeten el tiempo mínimo de preparación configurado por el administrador, contado desde el momento de la confirmación del pedido.

#### Scenario: Retiro demasiado próximo
- GIVEN un tiempo de preparación mínimo configurado de 30 minutos
- WHEN un cliente intenta pedir para retirar en 10 minutos
- THEN el sistema no ofrece ese horario como opción

### Requirement: Horario de retiro elegido por el cliente
El sistema MUST permitir al cliente elegir el horario exacto de retiro dentro de los horarios válidos disponibles, dentro de la fecha seleccionada.

#### Scenario: Selección de horario válido
- GIVEN un cliente eligiendo el horario de retiro para su pedido
- WHEN selecciona un horario válido dentro de la ventana de pedidos y respetando el tiempo de preparación
- THEN el sistema confirma ese horario de retiro para el pedido

### Requirement: Sin límite de capacidad por horario
El sistema MUST NOT imponer un número máximo de pedidos por horario de retiro.

#### Scenario: Múltiples pedidos en el mismo horario
- GIVEN varios clientes distintos
- WHEN todos seleccionan el mismo horario de retiro válido
- THEN el sistema acepta todos los pedidos sin rechazar por capacidad
