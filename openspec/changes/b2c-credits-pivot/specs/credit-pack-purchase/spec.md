# Especificación: credit-pack-purchase

## Purpose

Permitir la compra de paquetes de créditos (día/semana/mes con descuento por volumen) y la compra directa de créditos para un único pedido, mediante Mercado Pago Checkout Pro, acreditando el saldo únicamente desde el webhook validado por firma tras volver a consultar el pago, de forma idempotente.

## Requirements

### Requirement: Paquetes de día, semana y mes con descuento por volumen
El sistema MUST ofrecer paquetes de créditos de tamaño día/semana/mes, con precio y porcentaje de descuento configurables por el administrador para cada tamaño.

#### Scenario: Compra de un paquete
- GIVEN un catálogo de paquetes configurado por el administrador
- WHEN un usuario selecciona el paquete "semana"
- THEN el sistema calcula el precio con el descuento por volumen configurado para ese tamaño

### Requirement: Flujo de pago mediante Mercado Pago Checkout Pro
El sistema MUST iniciar el cobro de un paquete o de una compra directa creando una preferencia de Mercado Pago (Checkout Pro) con el monto calculado en el servidor y `external_reference` igual al id interno de la compra, redirigiendo al usuario al `initPoint` de Mercado Pago.

#### Scenario: Redirección al checkout
- GIVEN un usuario que confirmó la compra de un paquete
- WHEN el sistema crea la preferencia de pago
- THEN redirige al usuario a la URL de Checkout Pro de Mercado Pago

### Requirement: Acreditación solo desde el webhook validado
El sistema MUST otorgar créditos únicamente cuando: (1) recibe la notificación webhook de Mercado Pago, (2) valida su firma HMAC (`x-signature`/`x-request-id`), (3) vuelve a consultar el pago directamente a la API de Mercado Pago como fuente de verdad, y (4) el estado consultado es `approved`. El sistema MUST NOT otorgar créditos a partir únicamente de la redirección del navegador (`back_urls`/`auto_return`).

#### Scenario: Acreditación por webhook válido
- GIVEN una compra pendiente y un webhook con firma válida
- WHEN el sistema vuelve a consultar el pago y su estado es `approved`
- THEN acredita los créditos correspondientes

#### Scenario: Webhook con firma inválida
- GIVEN un webhook cuya firma HMAC no coincide
- WHEN el sistema la valida
- THEN rechaza la notificación sin acreditar créditos

#### Scenario: Redirección del navegador sin webhook
- GIVEN un usuario que vuelve del checkout mediante `back_urls`
- WHEN la página de retorno se muestra antes de recibir el webhook
- THEN el sistema no acredita créditos todavía

### Requirement: Idempotencia por id de pago de Mercado Pago
El sistema MUST garantizar que una misma compra se acredite una única vez, mediante una restricción de unicidad sobre el id de pago de Mercado Pago, incluso ante notificaciones webhook duplicadas.

#### Scenario: Webhook duplicado
- GIVEN una compra ya acreditada a partir de un id de pago determinado
- WHEN llega una segunda notificación webhook con el mismo id de pago
- THEN el sistema no acredita créditos por segunda vez

### Requirement: Manejo de estados no aprobados
El sistema MUST cerrar la compra pendiente sin acreditar créditos cuando el estado del pago es `rejected` o `cancelled`, y MUST revertir el saldo de créditos ya acreditado cuando el estado pasa a `refunded` o `charged_back`.

#### Scenario: Pago rechazado
- GIVEN una compra pendiente
- WHEN el pago vuelve con estado `rejected`
- THEN el sistema cierra la compra sin acreditar créditos

#### Scenario: Reembolso posterior a la acreditación
- GIVEN una compra ya acreditada
- WHEN Mercado Pago reporta el pago como `refunded`
- THEN el sistema revierte los créditos otorgados por esa compra

### Requirement: Compra directa para un único pedido
El sistema MUST permitir pagar con dinero exactamente los créditos necesarios para un pedido puntual, sin descuento por volumen, consumiéndolos de inmediato al confirmarse el pago, mediante un único movimiento de libro mayor.

#### Scenario: Compra directa exitosa
- GIVEN un usuario sin saldo suficiente para un pedido de 2 almuerzos
- WHEN paga directamente 2 almuerzos y el pago se aprueba
- THEN el sistema acredita y consume esos 2 almuerzos en un único movimiento
