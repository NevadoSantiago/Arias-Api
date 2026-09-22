# Tasks: Pivote a Créditos B2C (aditivo) — mitad backend

Este archivo cubre exclusivamente las unidades de trabajo de backend (equivalentes a las unidades
1–13 del cambio combinado original). La mitad frontend, que depende de que este backend esté
implementado, vive en `C:\Arias\frontend\openspec\changes\b2c-credits-pivot\tasks.md`.

## Review Workload Forecast

| Campo | Valor |
|---|---|
| Líneas estimadas | ~3750 (suma de las 13 unidades de trabajo de backend, ~73% del total original de ~5150) |
| Riesgo de presupuesto de 400 líneas | High |
| PRs encadenados recomendados | No — el desarrollador no abre PRs; la entrega es `single-pr` |
| División sugerida | 13 unidades de trabajo secuenciales, una por commit |
| Estrategia de entrega | single-pr |
| Estrategia de cadena | size-exception |

Decision needed before apply: Yes
Chained PRs recommended: No
Chain strategy: size-exception
400-line budget risk: High

**Nota sobre PRs**: el desarrollador trabaja solo y no abre pull requests. La división en 13 unidades
de trabajo NO es una cadena de PRs — es una secuencia de commits, cada uno dejando `./mvnw test` en
verde. El PR único de este repositorio (si se abre alguno) requiere `size:exception` del orquestador
antes de `sdd-apply`, dado que ~3750 líneas excede ampliamente el presupuesto de 400. Este backend
debe quedar implementado y desplegado antes de iniciar la mitad frontend (ver `proposal.md`).

### Unidades de trabajo sugeridas

| # | Objetivo | Commit | Test focalizado | Harness de runtime | Límite de rollback |
|---|---|---|---|---|---|
| 1 | `Category.creditCost` | 1 | `CategoryServiceTest` | N/A — CRUD puro, sin efectos externos | Revertir migración V15 + `Category` |
| 2 | Esquema del libro mayor (entidades/repos) | 2 | compila + repo tests | N/A — sin lógica aún | Revertir migración V17 + paquete `credits` |
| 3 | `CreditLedgerService` + expiración | 3 | `CreditLedgerServiceTest` (estado, invariante, concurrencia) | `Clock.fixed` inyectado | Eliminar `CreditLedgerService`/`CreditController`/`CreditExpiryScheduler` |
| 4 | Autorregistro + verificación de correo | 4 | `RegistrationServiceTest` | `EmailService` mockeado | Revertir migración V16 (parte 1) + `RegistrationService` |
| 5 | Google login + complete-profile | 5 | `GoogleAuthServiceTest` | `GoogleIdTokenVerifier` mockeado | Revertir `google_sub` + `GoogleAuthService` |
| 6 | RED: test de regresión B2B | 6 | `OrderServiceCompanyFlowTest` (debe fallar) | N/A | Eliminar el test nuevo |
| 7 | GREEN: `Order`/`OrderItem` + reroute a créditos | 7 | `OrderServiceTest` + `BillingServiceTest` + unit 6 | `Clock.fixed` | Revertir migración V18 + `OrderService` (git revert acotado) |
| 8 | Programación de retiro | 8 | `PickupSlotServiceTest`, `OrderConsumptionSchedulerTest` | `Clock.fixed` + cron manual | Revertir campos de retiro en `restaurant_config` (V20 parcial) |
| 9 | Verificación sandbox de MP (reembolso parcial) | 9 | Checklist manual, sin código | Sandbox real de Mercado Pago + tarjetas de prueba | N/A — no cambia código |
| 10 | `PaymentGateway` + `MercadoPagoAdapter` | 10 | `SignatureVerifierTest` (vectores válidos/inválidos) | Mock de `PreferenceClient`/`PaymentClient` | Eliminar paquete `payments` |
| 11 | `CreditPack` + `CreditPurchase` + webhook + reconciliación | 11 | `MercadoPagoWebhookControllerTest` (duplicado, reversión) | Mock del SDK + fixtures de payload firmados | Revertir migración V19 + paquete `payments` (purchase/webhook) |
| 12 | Notificaciones | 12 | `OrderNotificationSchedulerTest` | `Clock.fixed` + `EmailService` mockeado | Revertir migración V21 + `orders/notifications` |
| 13 | Admin fulfillment + config | 13 | `AdminOrderControllerTest`, `RestaurantConfigServiceTest` | N/A | Revertir endpoints nuevos de `AdminOrderController` |

## Unidad 1 — `Category.creditCost` (backend, pequeña)

- [x] 1.1 Migración `backend/src/main/resources/db/migration/V15__category_credit_cost.sql`: `ADD COLUMN credit_cost INTEGER NOT NULL DEFAULT 1` + `CHECK (credit_cost > 0)`.
- [x] 1.2 Agregar campo `creditCost` a `backend/src/main/java/com/arias/catalog/categories/Category.java` y `CategoryDto.java`.
- [x] 1.3 Validar entero positivo en `CategoryService` (rechazar fraccionario o ≤ 0) — cubre spec `catalog-credit-pricing`, escenario "Rechazo de costo fraccionario".
- [x] 1.4 Test: `backend/src/test/java/com/arias/catalog/categories/CategoryServiceTest.java` — creación/edición de `creditCost`, valor 1 en categoría estándar.

Verificación: `./mvnw test -Dtest=CategoryServiceTest` (desde `backend/`).

## Unidad 2 — Esquema del libro mayor (backend)

- [x] 2.1 Migración `backend/src/main/resources/db/migration/V17__credit_ledger.sql` (tablas `credit_wallet`, `credit_movement`, índice único parcial `WELCOME_GRANT`, ver diseño §Modelo de datos).
- [x] 2.2 Crear `backend/src/main/java/com/arias/credits/{CreditWallet,CreditMovement,MovementType}.java`.
- [x] 2.3 Crear `backend/src/main/java/com/arias/credits/{CreditWalletRepository,CreditMovementRepository}.java` con `findByIdForUpdate` (patrón `SELECT ... FOR UPDATE` de CeroComa).

Verificación: `./mvnw test -Dtest=CreditWalletRepositoryTest` (contexto Spring levanta con las nuevas tablas).

## Unidad 3 — `CreditLedgerService` + expiración (backend)

- [x] 3.1 `backend/src/main/java/com/arias/credits/CreditLedgerService.java`: método `apply(userId, type, deltaAvailable, deltaCommitted, ref)` único punto de mutación de saldo; `commit()`, `release()`, `consume()`; `expireIfDue()` como guard perezoso.
- [x] 3.2 `backend/src/main/java/com/arias/credits/CreditController.java`: `GET /api/v1/credits/wallet`, `GET /api/v1/credits/movements`.
- [x] 3.3 `backend/src/main/java/com/arias/credits/CreditExpiryScheduler.java`: job horario sobre `expires_at <= now AND available > 0` → movimiento `EXPIRATION`, `committed` intacto (decisión confirmada: expiración solo toca AVAILABLE).
- [x] 3.4 RED→GREEN: `backend/src/test/java/com/arias/credits/CreditLedgerServiceTest.java` — máquina de estados COMMIT/RELEASE/CONSUME/EXPIRATION, invariante `saldo == SUM(deltas)`, test de compromisos paralelos sobre la misma billetera (sin sobregiro) — cubre spec `credit-ledger` completa.

Verificación: `./mvnw test -Dtest=CreditLedgerServiceTest`.

## Unidad 4 — Autorregistro + verificación de correo (backend)

- [x] 4.1 Migración `backend/src/main/resources/db/migration/V16__user_self_registration.sql` (columnas `phone`, `nickname`, `email_verified_at`, `google_sub`; `UNIQUE` parciales; `UPDATE ... email_verified_at = created_at`; tabla `email_verification_token`).
- [x] 4.2 `backend/src/main/java/com/arias/auth/{EmailVerificationToken,EmailVerificationTokenRepository}.java` (mismo patrón que `PasswordResetToken`).
- [x] 4.3 `backend/src/main/java/com/arias/auth/RegistrationService.java`: alta pública, normalización E.164 + `UNIQUE(phone)`, envío de verificación vía `EmailService`.
- [x] 4.4 DTOs y endpoints en `AuthController`/`AuthService`: `POST /api/v1/auth/{register,verify-email,resend-verification}`.
- [x] 4.5 Agregar rutas públicas en `backend/src/main/java/com/arias/common/security/SecurityConfig.java`.
- [x] 4.6 Test: `RegistrationServiceTest` — registro exitoso, campos faltantes, teléfono duplicado, cuenta no verificada bloqueada — cubre spec `self-registration` (excepto Google/welcome grant, unidad 5; solo requisitos de dominio/API — los de flujo de UI viven en la mitad frontend).

Verificación: `./mvnw test -Dtest=RegistrationServiceTest`.

## Unidad 5 — Google login + complete-profile + almuerzo de bienvenida (backend)

- [x] 5.1 `backend/src/main/java/com/arias/auth/GoogleAuthService.java`: `POST /api/v1/auth/google` valida ID token (`GoogleIdTokenVerifier`, audiencia = client id); fusión por email normalizado (crear / vincular `google_sub` / rechazar si `google_sub` distinto).
- [x] 5.2 `POST /api/v1/auth/complete-profile` para teléfono/apodo faltantes; `me()` expone `emailVerified`/`profileComplete` (`MeResponse`).
- [x] 5.3 Otorgamiento del almuerzo de bienvenida en `CreditLedgerService` (movimiento `WELCOME_GRANT`) disparado desde verificación de correo (unidad 4) y desde Google login; protegido por el índice único parcial de la unidad 2 — cubre spec `self-registration`, "Otorgamiento único".
- [x] 5.4 `backend/pom.xml`: agregar `com.google.api-client:google-api-client`.
- [x] 5.5 Test: `GoogleAuthServiceTest` — alta con Google, vinculación, rechazo por `google_sub` distinto, sin doble otorgamiento del almuerzo.

Verificación: `./mvnw test -Dtest=GoogleAuthServiceTest`.

## Unidad 6 — RED: proteger el flujo de pedidos de empresa (backend, protección B2B)

- [x] 6.1 Escribir `backend/src/test/java/com/arias/orders/OrderServiceCompanyFlowTest.java` **antes** de tocar `OrderService`: caracteriza el flujo de pedido de empresa TAL COMO funciona hoy sobre `DailyChoice` (lookup de `CompanyCategoryPrice`, `precioSnapshot`/`horaEntrega` congelados, decremento de stock, un pedido por usuario por día, tarifa faltante cae a 0 sin bloquear, `update`/`cancel` con restauración de stock, y el consolidado agrupado por `companyId` que respalda `AdminOrderController`). Son 8 tests, todos VERDES hoy — es la RED/safety-net que debe fallar si la unidad 7 rompe el camino B2B, no una prueba que hoy falle.
- [x] 6.2 Confirmar baseline verde: `./mvnw test -Dtest=BillingServiceTest` (documentado abajo — pasa ANTES de la unidad 7, sin tocar el archivo).

Verificación: `./mvnw test -Dtest=OrderServiceCompanyFlowTest` (rojo esperado) + `./mvnw test -Dtest=BillingServiceTest` (verde, baseline).

## Unidad 7 — GREEN: `Order`/`OrderItem` + reroute de consumo a créditos (backend, riesgo alto)

> **Nota de transición (resuelve la contradicción 7.4/7.7 original)**: la
> primera versión de esta unidad decía "reescribir `OrderService`" (7.4) Y a
> la vez "`OrderServiceCompanyFlowTest` debe seguir verde SIN ediciones"
> (7.7). Ambas no pueden ser ciertas a la vez: ese test caracteriza —
> literalmente asserts sobre — el comportamiento de HOY de `OrderService`
> sobre `DailyChoice`/`CompanyCategoryPrice`/`precioSnapshot` (un pedido por
> día, tarifa faltante cae a 0, `cancel()` hace `DELETE`, etc.), justo lo que
> una reescritura reemplazaría. Resolución (decisión del orquestador,
> consistente con el cambio siendo ADITIVO y con `daily_choice` congelada
> como historia — diseño §Decisión 1 y §Decisión 2):
> - `OrderService`, `DailyChoice`, `resolvePrecio`, `CompanyCategoryPrice` y
>   `BillingService` **NO se tocan**. `OrderServiceCompanyFlowTest` sigue
>   verde sin ediciones porque el código que caracteriza no cambió.
> - El flujo nuevo vive en **`OrderPlacementService`** (clase nueva), sobre
>   `orders`/`order_item`, con sus propios endpoints bajo `POST /api/v2/orders`
>   y `DELETE /api/v2/orders/{id}` (`OrderPlacementController`) — no
>   colisionan con `/api/v1/orders` (`OrderController`, sin tocar). Todo
>   pedido nuevo — B2C o empleado de empresa — consume créditos vía
>   `CreditLedgerService`; `company_id` es solo instantánea de `user.company`.
> - Los endpoints viejos (`/api/v1/orders`) siguen funcionando; el frontend
>   migra a `/api/v2/orders` en su propia mitad del cambio.
> - `7.4` se reinterpreta como "crear `OrderPlacementService`" en vez de
>   "reescribir `OrderService`"; la verificación de `7.8` pasa a
>   `OrderPlacementServiceTest` (no `OrderServiceTest`, para no sugerir que
>   reemplaza al service viejo).

- [x] 7.1 Migración `backend/src/main/resources/db/migration/V18__orders.sql` (tablas `orders`, `order_item`, ver diseño; sin `UNIQUE(user_id, fecha)`).
- [x] 7.2 Crear `backend/src/main/java/com/arias/orders/{Order,OrderItem,OrderRepository,OrderItemRepository,OrderDto,OrderItemDto,PlaceOrderV2Request}.java`.
- [x] 7.3 Agregar `CANCELADO` a `backend/src/main/java/com/arias/orders/OrderEstado.java` (enum compartido con `DailyChoice`, que nunca usa ese valor).
- [x] 7.4 Crear `backend/src/main/java/com/arias/orders/{OrderPlacementService,OrderPlacementController}.java`: `place()` valida ventana mínima + `decrementStock` + `CreditLedgerService.commit()`; `cancel()` valida `now < pickupAt - lead`, `RELEASE` + `incrementStock`, soft-cancel (`CANCELADO`, nunca `DELETE`). No usa `resolvePrecio(...)`: el costo sale de `Category.creditCost`. `company_id` se copia como instantánea de `user.company`. `OrderService`/`DailyChoice`/`CompanyCategoryPrice`/`BillingService` **sin tocar** (ver nota de transición arriba).
- [x] 7.5 Bloqueo por saldo insuficiente sin compromiso parcial — cubre spec `order-placement`, "Bloqueo por saldo insuficiente" (decrementStock corre antes que `commit()`, misma transacción: si el commit falla, Spring revierte también el stock).
- [x] 7.6 `DishService.listAllAvailable(fecha)` en `backend/src/main/java/com/arias/catalog/dishes/DishService.java` para usuarios sin `categoryId` (B2C); el camino con claim (`listAvailableFor`) no se toca. `DishController` rutea por rol EMPLOYEE + `categoryId == null` (no solo `categoryId == null`, para no cambiar el comportamiento de COMPANY_ADMIN/SUPER_ADMIN).
- [x] 7.7 Confirmar GREEN: el test de la unidad 6 (`OrderServiceCompanyFlowTest`) pasa SIN modificaciones (el archivo no cambió); `BillingServiceTest` sigue verde sin modificaciones.
- [x] 7.8 `OrderPlacementServiceTest`: múltiples pedidos por día, múltiples ítems, total = suma de ítems, stock agotado rechaza el ítem antes de comprometer créditos, saldo insuficiente bloquea todo el pedido sin stock decrementado, cancelación libera créditos y restaura stock, empleado de empresa consume créditos con `company` como instantánea.

Verificación: `./mvnw test -Dtest=OrderPlacementServiceTest,OrderServiceCompanyFlowTest,BillingServiceTest`.

## Unidad 8 — Programación de retiro (backend)

- [x] 8.1 Migración `backend/src/main/resources/db/migration/V20__restaurant_config_b2c.sql` (los 7 campos del diseño: `pickup_lead_minutes`, `credit_expiry_days`, `pickup_window_{start,end}`, `pickup_slot_minutes`, `daily_summary_time`, `pickup_reminder_minutes`).
- [x] 8.2 `backend/src/main/java/com/arias/orders/PickupSlotService.java`: slots cada `pickup_slot_minutes` entre `pickup_window_start`/`end`, filtra `< now + lead`, semana actual + siguiente, excluye `fechas_deshabilitadas` (reutiliza `FechaDeshabilitadaRepository`).
- [x] 8.3 Endpoint `GET /api/v1/orders/pickup-slots?fecha=` en `OrderController`.
- [x] 8.4 `backend/src/main/java/com/arias/orders/OrderConsumptionScheduler.java`: cron cada minuto, `pickup_at - lead <= now` en `PENDIENTE` → `CONSUME` + `CONFIRMADO` (mismo patrón que `OrdersScheduler.closeOverdueOrders`).
- [x] 8.5 Re-validación perezosa en `OrderPlacementService.cancel()` (la tabla de unidades y el texto original decían `OrderService.cancel()`, pero `OrderService`/`DailyChoice` está congelado desde la unidad 7 y no tiene `pickupAt`; esta lógica ya existía desde la unidad 7 con un lead fijo de 20 minutos — esta unidad la vuelve configurable): rechaza si `now >= pickupAt - lead`, sin depender de si el job corrió.
- [x] 8.6 Test con `Clock.fixed`: `PickupSlotServiceTest` (ventana, tiempo de preparación, sin límite de capacidad) y `OrderConsumptionSchedulerTest` (consumo automático, atraso procesado en el primer tick) — cubre spec `pickup-scheduling` completa.

**Nota adicional (settled con el usuario, fuera del texto original de 8.1-8.6)**: además de la migración, se expuso la edición de los 7 campos nuevos vía `RestaurantConfig`/`RestaurantConfigDto`/`UpdateRestaurantConfigRequest`/`RestaurantConfigController` (`PUT /api/v1/restaurant-config`, `SUPER_ADMIN`), y se reemplazaron `CreditLedgerService.DEFAULT_EXPIRY_DAYS` y `OrderPlacementService.DEFAULT_PICKUP_LEAD_MINUTES` por lectura directa de `restaurant_config` — esto adelanta parte de lo que la unidad 13 (13.3) tenía planeado solo para la config; 13.3 ahora solo necesita cubrir `AdminOrderController`/`OrderExportService`.

Verificación: `./mvnw test -Dtest=PickupSlotServiceTest,OrderConsumptionSchedulerTest`.

## Unidad 9 — Verificación sandbox de Mercado Pago: reembolso parcial (manual, sin código)

- [ ] 9.1 Antes de implementar la reversión de pagos (unidad 11), probar en el sandbox de Mercado Pago (tarjetas APRO/OTHE/CONT/FUND) un reembolso **parcial** (`POST /v1/payments/{id}/refunds` con `amount`) y registrar el `payment.status` resultante: si queda `approved` con `transaction_amount_refunded` poblado (comportamiento esperado según la investigación #607, no confirmado), la reversión en `CreditLedgerService` debe ser proporcional a `transaction_amount_refunded`, no total.
- [ ] 9.2 Documentar el resultado en el diseño o como nota de la unidad 11 antes de escribir `PAYMENT_REVERSAL`.

Verificación: checklist manual — no hay comando automatizado; evidencia = captura del payload de `GET /v1/payments/{id}` post-reembolso.

## Unidad 10 — `PaymentGateway` port + `MercadoPagoAdapter` (backend)

- [x] 10.1 `backend/pom.xml`: agregar `com.mercadopago:sdk-java` (confirmar versión disponible, objetivo 3.7.0 según investigación #607). **Versión resuelta: 3.7.0** (existe en Maven Central, jar+pom verificados con `curl`, `./mvnw -o test` la resuelve desde el repo local sin problemas).
- [x] 10.2 Crear `backend/src/main/java/com/arias/payments/{PaymentGateway,CheckoutRequest,CheckoutSession,PaymentSnapshot,PaymentStatus}.java` (sin `refund()` — decisión confirmada: el producto no inicia reembolsos de dinero).
- [x] 10.3 Crear `backend/src/main/java/com/arias/payments/mercadopago/{MercadoPagoAdapter,MercadoPagoProperties,SignatureVerifier}.java`: `PreferenceClient.create()`, `PaymentClient.get(id)`; manifiesto HMAC `id:<data.id minúsculas>;request-id:<x-request-id>;ts:<ts>;`, comparación en tiempo constante.
- [x] 10.4 Configuración `arias.mercadopago.{access-token,webhook-secret,enabled}`, `arias.google.client-id` (ya existía desde la unidad 5, `GoogleAuthProperties`), `arias.public.{frontend-url,backend-url}` (nueva, `com.arias.common.config.PublicUrlProperties`) vía `@ConfigurationProperties` (patrón R2/Resend).
- [x] 10.5 Test: `SignatureVerifierTest` con vectores HMAC válidos e inválidos (firma válida, `data.id` alterado, secreto incorrecto, `x-request-id` ausente con componente omitido del manifiesto, header malformado — 5 casos, todos verdes).

Verificación: `./mvnw test -Dtest=SignatureVerifierTest`.

## Unidad 11 — `CreditPack` + `CreditPurchase` + webhook + reconciliación (backend, la más grande)

- [ ] 11.1 Migración `backend/src/main/resources/db/migration/V19__credit_packs_and_purchases.sql` (tablas `credit_pack`, `credit_purchase`, `UNIQUE(mp_payment_id)`).
- [ ] 11.2 Crear `backend/src/main/java/com/arias/credits/packs/{CreditPack,CreditPackRepository,CreditPackService,AdminCreditPackController,CreditPackDto}.java` (CRUD `SUPER_ADMIN` en `/api/v1/admin/credit-packs`, `price_cents` autoritativo, `discount_percent` solo presentación).
- [ ] 11.3 Crear `backend/src/main/java/com/arias/payments/{CreditPurchase,CreditPurchaseRepository,CreditPurchaseService,CreditPurchaseController}.java`: `POST /api/v1/credits/purchases`, importe calculado en servidor, `external_reference = credit_purchase.id`.
- [ ] 11.4 Crear `backend/src/main/java/com/arias/payments/MercadoPagoWebhookController.java`: los 9 pasos del diseño (validar firma → `topic != payment` → `PaymentClient.get` → resolver `credit_purchase` → comparar importe → `SELECT FOR UPDATE` con atajo de "ya acreditada" → mapear estado → commit → email `AFTER_COMMIT`).
- [ ] 11.5 Aplicar mapeo de estados del diseño: `approved` → `PACK_PURCHASE` (renueva `expires_at`) o `DIRECT_PURCHASE` (sin renovar, directo a COMMITTED); `rejected/cancelled` → cierra sin acreditar; `refunded/charged_back` → `PAYMENT_REVERSAL` acotado a `available`, sin tocar `expires_at`, usando el resultado de la unidad 9 para reembolsos parciales.
- [ ] 11.6 Crear `backend/src/main/java/com/arias/payments/PaymentReconciliationScheduler.java`: `PENDING` > 30 min re-consulta; > 24 h → `EXPIRED`.
- [ ] 11.7 Test: `MercadoPagoWebhookControllerTest` — webhook duplicado no acredita dos veces (contra la restricción real), firma inválida rechaza sin acreditar, redirección del navegador sola no acredita, compra directa = un único movimiento — cubre spec `credit-pack-purchase` completa.

Verificación: `./mvnw test -Dtest=MercadoPagoWebhookControllerTest,CreditPurchaseServiceTest`.

## Unidad 12 — Notificaciones (backend)

- [ ] 12.1 Migración `backend/src/main/resources/db/migration/V21__notification_run_log.sql` (`PRIMARY KEY (tipo, fecha)`, mismo patrón que `reminder_run_log`).
- [ ] 12.2 Crear `backend/src/main/java/com/arias/orders/notifications/{NotificationRunLog,NotificationRunLogRepository}.java`.
- [ ] 12.3 Crear `backend/src/main/java/com/arias/orders/notifications/OrderNotificationScheduler.java`: resumen matutino (cron `daily_summary_time`, destinatarios `SUPER_ADMIN`), alerta de cancelación (`@TransactionalEventListener(AFTER_COMMIT)`), recordatorio de retiro (cron por minuto, `pickup_at − pickup_reminder_minutes`, omite `CANCELADO`). `OrderReminderScheduler` NO se toca.
- [ ] 12.4 Crear `backend/src/main/java/com/arias/orders/notifications/OrderNotificationEmails.java`.
- [ ] 12.5 Test: `OrderNotificationSchedulerTest` con `Clock.fixed` — resumen agrupado por horario de retiro, alerta enviada al cancelar, recordatorio en ventana 20–30 min, sin recordatorio para `CANCELADO` — cubre spec `order-notifications` completa.

Verificación: `./mvnw test -Dtest=OrderNotificationSchedulerTest`.

## Unidad 13 — Admin fulfillment + configuración (backend, cierre de la mitad backend)

- [ ] 13.1 Modificar `backend/src/main/java/com/arias/orders/AdminOrderController.java`: `GET /api/v1/admin/orders/by-pickup?fecha=`, `GET /api/v1/admin/orders/export/by-pickup?fecha=`, agregados junto a los endpoints por empresa existentes (sin modificarlos) — cubre spec `admin-order-fulfillment`.
- [ ] 13.2 Modificar `backend/src/main/java/com/arias/orders/OrderExportService.java` y `AdminOrderDto.java` para exportar agrupado por horario de retiro.
- [ ] 13.3 Modificar `backend/src/main/java/com/arias/restaurantconfig/{RestaurantConfig,RestaurantConfigDto,UpdateRestaurantConfigRequest,RestaurantConfigController}.java`: exponer edición de los 7 campos nuevos.
- [ ] 13.4 Test: `AdminOrderControllerTest` — listado/exportación agrupados por horario de retiro Y por empresa sin cambio de comportamiento (regresión); `RestaurantConfigServiceTest` — edición de paquetes/vencimiento/ventana por el admin.

Verificación: `./mvnw test -Dtest=AdminOrderControllerTest,RestaurantConfigServiceTest` — al quedar en verde junto con el resto de la suite (`./mvnw test`), la mitad backend queda lista para que la mitad frontend inicie su implementación.
