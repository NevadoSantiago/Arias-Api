# Especificación: order-notifications

## Purpose

Enviar a los usuarios y/o al restaurante las notificaciones necesarias para operar el ciclo de un pedido: resumen matutino de los pedidos del día, alerta ante una cancelación, y recordatorio previo al retiro.

## Requirements

### Requirement: Resumen matutino de pedidos del día
El sistema MUST enviar un resumen con los pedidos programados para retirar ese día, generado una vez por día.

#### Scenario: Envío del resumen
- GIVEN pedidos programados para el día de hoy
- WHEN llega el horario configurado de envío del resumen matutino
- THEN el sistema envía el resumen con esos pedidos

### Requirement: Alerta de cancelación
El sistema MUST enviar una notificación cuando un pedido es cancelado, identificando el pedido y su horario de retiro original.

#### Scenario: Cancelación de un pedido
- GIVEN un pedido programado antes de su punto de confirmación
- WHEN el usuario lo cancela
- THEN el sistema envía una alerta de cancelación

### Requirement: Recordatorio previo al retiro
El sistema MUST enviar al cliente un recordatorio entre 20 y 30 minutos antes del horario de retiro de su pedido.

#### Scenario: Recordatorio a tiempo
- GIVEN un pedido con horario de retiro a las 13:00
- WHEN el reloj llega a un punto entre las 12:30 y las 12:40
- THEN el sistema envía el recordatorio de retiro al cliente

#### Scenario: Sin recordatorio para pedido ya cancelado
- GIVEN un pedido cancelado antes de la ventana de recordatorio
- WHEN el reloj llega a la ventana de 20 a 30 minutos antes del horario original
- THEN el sistema no envía el recordatorio de retiro
