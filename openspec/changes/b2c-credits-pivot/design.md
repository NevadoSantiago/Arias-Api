# Diseño: Pivote a Créditos B2C (aditivo) — mitad backend

Este documento cubre exclusivamente el dominio backend del cambio `b2c-credits-pivot`. La
contraparte de interfaz (pantallas, rutas, componentes) está diseñada en
`C:\Arias\frontend\openspec\changes\b2c-credits-pivot\design.md`, cuya sección "Contrato con el
backend" deriva directamente de los endpoints e interfaces documentados aquí. Ver `proposal.md` de
este mismo directorio para el contexto del split en dos repositorios y el orden de implementación
(backend primero).

## Enfoque técnico

Se agregan dos dominios nuevos de backend (`credits`, `payments`) siguiendo la convención existente
de **package-by-feature en capas** (Controller → Service → Repository → Entity, inyección por
constructor con `@RequiredArgsConstructor`), y se introduce un modelo de pedido nuevo (`Order` +
`OrderItem`) que convive con `DailyChoice` en lugar de reemplazarlo en la base de datos.

La regla que ordena todo el cambio: **nada existente se borra ni se migra**. `daily_choice`,
`company`, `company_category_price` y `BillingService` quedan congelados como historia de solo
lectura; todo pedido nuevo — B2C o de empleado de empresa — nace en `orders` y consume créditos.
Todas las migraciones de Flyway son aditivas (`V15`–`V21`), ninguna hace `DROP` ni `UPDATE`
destructivo.

Única desviación deliberada de la convención: el puerto `PaymentGateway`. El resto del código base
llama a los sistemas externos directamente desde el service (R2, Resend, POI). Acá se interpone una
interfaz porque la propuesta prevé extraer pagos a un microservicio y porque es la única forma de
testear el flujo de webhook sin golpear la API de Mercado Pago. Los tipos `com.mercadopago.*` no
salen del paquete `payments.mercadopago`.

## Decisiones de arquitectura

### Decisión 1: `BillingService` / `CompanyCategoryPrice` se conservan como reporte histórico

| Opción | Costo | Consecuencia |
|---|---|---|
| Conservar tal cual, operando solo sobre `daily_choice` | **Cero código tocado** | El reporte deja de crecer; refleja historia B2B previa al pivote |
| Adaptar `BillingService` a `Order` + `OrderItem` × `CompanyCategoryPrice` | Alto | Reintroduce una segunda fuente de precio para el mismo pedido, justo lo que el cambio elimina |
| Reemplazar por compras de créditos de empresa | Muy alto | Fuera de alcance explícito (asignación mensual por empresa es etapa 2) |

**Elección**: conservar tal cual. `BillingService`, `BillingController`, `CompanyCategoryPrice*` y
`BillingServiceTest` no se tocan; siguen leyendo `daily_choice.precio_snapshot`.

**Razón**: es la opción menos invasiva y la única que no crea ambigüedad de precio. El riesgo
"dos caminos de precio coexistiendo" de la propuesta se cierra por separación temporal, no por
convivencia: `daily_choice` congelado = precios; `orders` = créditos. Cuando el cliente active la
campaña corporativa, la compra de créditos por empresa se construye sobre el libro mayor ya
existente, sin desarmar nada.

### Decisión 2: `orders` / `order_item` como tablas nuevas, `daily_choice` congelada

**Elección**: tablas nuevas. `daily_choice` conserva su `uq_daily_choice_user_fecha` y su
`precio_snapshot` intactos — no hay migración de datos ni relajación de constraints. La nueva tabla
`orders` simplemente no tiene esa restricción, con lo cual "múltiples pedidos por día" sale gratis.

**Alternativas descartadas**: (a) modificar `daily_choice` (obliga a dropear la constraint única y a
volver nullable `precio_snapshot`, es decir, migración destructiva sobre la tabla que sostiene la
facturación histórica); (b) migrar filas de `daily_choice` a `orders` (la propuesta lo prohíbe y no
hay datos de producción que justifiquen el riesgo).

**Pedidos de empresa**: `orders.company_id` es una **instantánea nullable** copiada de
`user.company` al confirmar. Así los tres endpoints de `AdminOrderController` agrupados por empresa
(`/export/{companyId}`, `/deliver-company/{companyId}`, listado ordenado por empresa) conservan su
semántica sobre la tabla nueva, y el empleado de empresa consume créditos como cualquier cliente.
`OrderService` deja de llamar a `resolvePrecio(...)`; el costo sale de `Category.creditCost`.

### Decisión 3: libro mayor = saldos materializados + movimientos inmutables, con bloqueo pesimista

**Elección**: `credit_wallet` (una fila por usuario, con `available`, `committed`, `expires_at`) +
`credit_movement` (append-only). Cada movimiento lleva **dos deltas con signo**,
`delta_available` y `delta_committed`, lo que convierte el libro mayor en partida doble entre los dos
buckets y hace verificable el invariante:
`wallet.available == SUM(delta_available)` y `wallet.committed == SUM(delta_committed)`.

| Opción | Tradeoff | Decisión |
|---|---|---|
| Saldo derivado (`SUM` sobre movimientos, sin tabla de saldo) | Lectura O(n); el vencimiento "solo AVAILABLE" es difícil de expresar; igual hay que bloquear algo para no sobregirar | Rechazada |
| Saldo materializado + `@Version` optimista | Reintentos bajo carga; el webhook no puede reintentar barato | Rechazada |
| **Saldo materializado + `SELECT ... FOR UPDATE` sobre `credit_wallet`** | Un lock de una fila, contención real ≈ 0 (un usuario no pide en paralelo consigo mismo) | **Elegida** |

Es el mismo patrón de CeroComa (`findByIdForUpdate`), que ya está probado en producción del usuario,
aplicado a la billetera en vez de al pedido.

**Idempotencia**: el bloqueo no alcanza para el webhook (Mercado Pago reintenta hasta 8 veces). Se
suma, en la base, `UNIQUE (mp_payment_id)` sobre `credit_purchase` y un índice único parcial
`credit_movement(user_id) WHERE type = 'WELCOME_GRANT'`. La garantía vive en el esquema, no en el
código.

**Tipos de movimiento**: `WELCOME_GRANT`, `PACK_PURCHASE`, `DIRECT_PURCHASE`, `COMMIT`, `RELEASE`,
`CONSUME`, `EXPIRATION`, `PAYMENT_REVERSAL`, `ADMIN_ADJUSTMENT`.

**Vencimiento**: job horario sobre `credit_wallet WHERE expires_at <= now AND available > 0` →
movimiento `EXPIRATION` con `delta_available = -available`, `delta_committed = 0`, y `expires_at`
a `NULL`. **COMMITTED nunca se toca**: el pedido programado se sostiene. Además hay un guard
perezoso: `CreditLedgerService.lockWallet()` aplica el vencimiento vencido dentro de la misma
transacción bloqueada antes de cualquier COMMIT, así un job caído nunca habilita gastar saldo
vencido.

### Decisión 4: consumo en `pickup − lead` = job programado **más** re-validación perezosa

**Elección**: las dos cosas, con roles distintos y no redundantes.

- **El job hace el trabajo**: `OrderConsumptionScheduler`, cron cada minuto (mismo patrón que
  `OrdersScheduler.closeOverdueOrders`, `30 * * * * *`), toma los `orders` en `PENDIENTE` con
  `pickup_at - lead <= now`, registra `CONSUME` y pasa el pedido a `CONFIRMADO`. Debe ser un job
  porque el consumo tiene efectos que nadie "lee" a demanda: el resumen de cocina, el listado del
  admin y el cierre de la ventana de cancelación.
- **La lectura re-valida el reloj**: `OrderService.cancel()` rechaza la cancelación si
  `now >= pickupAt - lead`, **sin importar si el job corrió**.

**Por qué no solo el job**: si el job está caído, un usuario podría cancelar pasado el deadline y
recuperar créditos de una comida que la cocina ya está preparando. La re-validación cierra ese
agujero de forma determinística.
**Por qué no solo evaluación perezosa**: el consumo quedaría sin ocurrir hasta que alguien mirara, y
el listado del admin mostraría estados falsos.
**Si el job se cae**: nada se pierde ni se corrompe — los créditos siguen en COMMITTED, ninguna
cancelación tardía pasa, y al volver el job procesa el atraso en el primer tick (la query es por
`pickup_at`, no por "el minuto actual").

Ciclo de vida de `Order.estado`: `PENDIENTE` (créditos COMMITTED) → `CONFIRMADO` (consumidos) →
`COMANDADO` → `ENTREGADO`, más `CANCELADO`. Se reutiliza el enum `OrderEstado` agregando
`CANCELADO`. La cancelación es **soft** (`estado = CANCELADO`, `cancelled_at`), no `DELETE`, porque
los movimientos del libro mayor referencian el pedido para siempre.

### Decisión 5: `User.category` y el claim `categoryId` se conservan; la restricción se levanta solo para B2C

**Elección**: ni la columna ni el claim cambian. Cambia **quién los consulta**:

- Usuario **con** claim `categoryId` (empleado o admin de empresa) → sigue usando
  `DishService.listAvailableFor(categoryId)` con `CategoryHierarchyService`. Comportamiento B2B
  idéntico, cero riesgo de regresión.
- Usuario **sin** claim (autorregistrado B2C: `company = NULL`, `category = NULL`) → nuevo camino
  `DishService.listAllAvailable(fecha)`, que devuelve todos los platos habilitados.

**Alternativas descartadas**: quitar el claim del JWT (obliga a tocar `JwtService`, `MeResponse`, el
`authStore` del frontend y el menú de todos los empleados de empresa, sin que ninguna spec lo pida);
devolver todo a todos (cambia en silencio lo que ve un empleado de empresa, violando "sin
modificar").

### Decisión 6: `Category.parentId` se conserva, activo para el camino B2B

Por la Decisión 5, el árbol sigue siendo la fuente de visibilidad de los empleados de empresa. No es
código inerte: es el camino B2B. Para B2C queda sin uso, sin costo. Sin migración, sin borrado.

### Decisión 7: ventana de cancelación y stock

Cancelable mientras `estado = PENDIENTE` y `now < pickupAt - lead` (el mismo punto de consumo — un
solo deadline en todo el producto, no dos). Al cancelar: movimiento `RELEASE`
(`delta_available = +N`, `delta_committed = -N`), `incrementStock` por cada ítem (se reutiliza el
método existente de `DishRepository`), `estado = CANCELADO`, alerta de cancelación.

El stock se decrementa con la misma regla que hoy: solo para pedidos del día (`if (!isFuture)`), con
la reconciliación nocturna de `adjustStockForScheduledOrders` ya existente. No se rediseña.

### Decisión 8: validación de teléfono = formato + unicidad, sin OTP

**Elección**: normalización a E.164 en el backend + `UNIQUE` sobre `users.phone`. Costo operativo
cero.

**Descartado**: OTP por SMS/WhatsApp — costo por mensaje, un proveedor nuevo (Twilio o WhatsApp
Business API), verificación de número de remitente, y una pantalla más en el alta. La spec solo
exige *rechazar un teléfono ya asociado a una cuenta*, que la unicidad cumple exactamente. El OTP
puede agregarse después como migración aditiva (`phone_verified_at`) sin rehacer nada.

### Decisión 9: Google login = ID token verificado en el backend

**Elección**: el frontend usa Google Identity Services (`@react-oauth/google`) y obtiene un **ID
token**; el backend expone `POST /api/v1/auth/google` que lo valida con
`com.google.api-client:google-api-client` (`GoogleIdTokenVerifier`, audiencia = client id, emisor
`accounts.google.com`) y luego emite los tokens propios con el `issueTokens(user)` existente.

**Descartado**: `spring-boot-starter-oauth2-client` con flujo de código de autorización — mete
redirecciones y estado de sesión del lado del servidor en una API que es deliberadamente stateless
con JWT, creando un segundo modelo de autenticación. **Descartado**: validar el JWKS a mano —
reimplementar rotación de claves sin necesidad.

**Fusión con cuentas existentes** (por email normalizado, la misma clave que ya usa `AuthService`):

| Situación | Resultado |
|---|---|
| No existe el email | Se crea `EMPLOYEE` con `password_hash = NULL`, `email_verified_at = now`, `google_sub` seteado |
| Existe con `google_sub = NULL` | Se vincula (`google_sub`, `email_verified_at`); la contraseña existente sigue funcionando |
| Existe con otro `google_sub` | Se rechaza con el error genérico de credenciales |
| Falta teléfono o apodo | Se emiten tokens, pero pedir/comprar responde `409 profile-incomplete` hasta `POST /api/v1/auth/complete-profile` |

### Decisión 10: verificación de correo y almuerzo de bienvenida

Se replica el patrón ya probado de `PasswordResetToken`: tabla `email_verification_token` (hash del
token, usuario, `expires_at`, `used_at`) y envío por `EmailService`/`WelcomeEmails`.

El **otorgamiento único** no se defiende en código sino en el esquema: índice único parcial
`credit_movement(user_id) WHERE type = 'WELCOME_GRANT'`. Re-verificar, reenviar el mail o entrar por
Google después de haber verificado por correo choca contra la base de datos, no contra un `if`.
La **no transferibilidad** es estructural: el saldo es por usuario y no existe operación de
transferencia.

`users.email_verified_at` se rellena en la migración con `created_at` para **todos** los usuarios
preexistentes, de modo que ningún empleado de empresa quede bloqueado: el alta por lista blanca la
hizo un `COMPANY_ADMIN`, que ya es la verificación de ese canal.

### Decisión 11: nuevo `OrderNotificationScheduler`, sin tocar `OrderReminderScheduler`

**Elección**: `OrderReminderScheduler` queda intacto (sigue sirviendo el recordatorio B2B "no
pediste todavía", anclado a `horaCorte` y a `findReminderRecipientsForDate`). Se agrega
`orders/notifications/OrderNotificationScheduler`, reutilizando el patrón de dedup por clave primaria
de `ReminderRunLog` en una tabla `notification_run_log` con PK `(tipo, fecha)`.

**Descartado**: extender el scheduler existente — está acoplado a un único disparador y a una única
query de destinatarios; mezclarle tres triggers más lo vuelve intestable y pone en riesgo una
notificación B2B que hoy funciona.

| Notificación | Destinatario | Disparo |
|---|---|---|
| Resumen matutino | Administradores del restaurante (`SUPER_ADMIN`) — es información de cocina | Cron, `daily_summary_time` configurable; pedidos del día agrupados por horario de retiro |
| Alerta de cancelación | Administradores del restaurante **y** copia al cliente | Evento, vía `@TransactionalEventListener(AFTER_COMMIT)` |
| Recordatorio de retiro | Cliente | Cron por minuto en `pickup_at − pickup_reminder_minutes` (25 por defecto, dentro de la ventana 20–30); omite `CANCELADO` |

### Decisión 12: paquetes como entidad administrable

Tabla `credit_pack` con CRUD de `SUPER_ADMIN` bajo `/api/v1/admin/credit-packs`, siguiendo la
convención de `MenuSection`/`Side`. El precio autoritativo es `price_cents` (BIGINT, centavos de
ARS — nunca punto flotante); `discount_percent` es metadato **de presentación**, no entra en el
cálculo del cobro. Una sola fuente de verdad para el dinero.

## Flujo de datos

### Pedido y consumo

```
Cliente ──POST /api/v1/orders──► OrderService.place()
                                   │ valida ventana (semana actual + siguiente)
                                   │ valida pickup_at ≥ now + lead y dentro de la ventana
                                   │ decrementStock() por ítem (atómico)
                                   ▼
                          CreditLedgerService.commit(userId, total)
                                   │ SELECT ... FOR UPDATE credit_wallet
                                   │ aplica EXPIRATION si expires_at venció
                                   │ available ≥ total? no → 409 insufficient-credits
                                   ▼
                          movimiento COMMIT (-N available, +N committed)
                                   ▼
                          orders(estado=PENDIENTE, pickup_at, company_id?)

  ... pickup_at − lead ...

OrderConsumptionScheduler (cron 1 min) ──► movimiento CONSUME (-N committed)
                                           orders.estado = CONFIRMADO

Cancelación (solo si now < pickup_at − lead)
        └──► movimiento RELEASE (+N available, -N committed) + incrementStock + CANCELADO
```

### Compra con Mercado Pago

```
Cliente ──POST /api/v1/credits/purchases {packId | orderId}──► CreditPurchaseService
   │  crea credit_purchase(PENDING) con importe calculado EN EL SERVIDOR
   │  external_reference = credit_purchase.id (UUID)
   ▼
PaymentGateway.createCheckout() ──► MercadoPagoAdapter ──► PreferenceClient.create()
   │                                   back_urls (HTTPS) + notification_url
   │                                   auto_return("approved") SOLO si frontend-url es https
   ▼
initPoint ──► el navegador va a Mercado Pago ──► back_urls ──► página "procesando" (NO acredita)

Mercado Pago ──POST /api/webhooks/mercadopago──► MercadoPagoWebhookController
   1. x-signature + x-request-id + data.id (minúsculas) → HMAC constante  ── inválido → 401
   2. topic != "payment"                                                  ── → 200 ignorado
   3. PaymentClient.get(data.id)  (fuente de verdad, nunca el body)
   4. external_reference → credit_purchase   ── no existe → 200 + log
   5. transaction_amount/currency vs importe guardado ── difiere → 200, sin acreditar, flag
   6. SELECT ... FOR UPDATE; ¿ya acreditada? → 200 (corto)
   7. mapeo de estado → acción
   8. commit → 200 (siempre < 22 s)
   9. AFTER_COMMIT → email (fuera de la transacción bloqueada)
```

| Estado del pago | Acción |
|---|---|
| `approved` | `PACK_PURCHASE` (+N available, **renueva** `expires_at = now + credit_expiry_days`) o `DIRECT_PURCHASE` (+N committed, sin renovar); `credit_purchase = APPROVED` |
| `pending`, `in_process`, `authorized` | Sin cambios; queda PENDING; el job de reconciliación vuelve a mirar |
| `rejected`, `cancelled` | `credit_purchase = REJECTED/CANCELLED`; sin créditos; si era compra directa, se cancela el pedido asociado |
| `refunded`, `charged_back` | `PAYMENT_REVERSAL` (−N available, hasta 0 si ya se gastó; el saldo puede quedar negativo solo por `ADMIN_ADJUSTMENT`, nunca por reversión: se acota a `available`), `credit_purchase = REVERSED`, alerta al admin, **sin** cambiar `expires_at` |
| `in_mediation` | Se marca para revisión manual; no se acredita ni se revierte |

**Compra directa**: un **único** movimiento `DIRECT_PURCHASE` que entra directo a COMMITTED
(`delta_available = 0`, `delta_committed = +N`), sin pasar por AVAILABLE. Cumple el "único movimiento
de libro mayor" de la spec y mantiene una sola máquina de estados: esos créditos se consumen en
`pickup − lead` como cualquier otro.

**Reconciliación** (`PaymentReconciliationScheduler`, horario): `credit_purchase` en PENDING con más
de 30 minutos → re-consulta a Mercado Pago por `external_reference` y aplica el mismo mapeo; más de
24 horas sin pago → `EXPIRED`. Cubre el webhook perdido, que es el modo de falla real del flujo.

## Modelo de datos y plan de migraciones

Todas aditivas. Ninguna hace `DROP`, ni modifica `daily_choice`, `company`, `company_category_price`
ni `users.company_id`.

**V15 `__category_credit_cost.sql`**
```sql
ALTER TABLE category ADD COLUMN credit_cost INTEGER NOT NULL DEFAULT 1;
ALTER TABLE category ADD CONSTRAINT chk_category_credit_cost CHECK (credit_cost > 0);
```

**V16 `__user_self_registration.sql`**
```sql
ALTER TABLE users ADD COLUMN phone             VARCHAR(30);
ALTER TABLE users ADD COLUMN nickname          VARCHAR(50);
ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMP;
ALTER TABLE users ADD COLUMN google_sub        VARCHAR(64);
CREATE UNIQUE INDEX uq_users_phone      ON users(phone)      WHERE phone IS NOT NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX uq_users_google_sub ON users(google_sub) WHERE google_sub IS NOT NULL;
UPDATE users SET email_verified_at = created_at WHERE email_verified_at IS NULL;

CREATE TABLE email_verification_token (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users(id),
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMP   NOT NULL,
    used_at     TIMESTAMP,
    created_at  TIMESTAMP   NOT NULL DEFAULT now()
);
```

**V17 `__credit_ledger.sql`**
```sql
CREATE TABLE credit_wallet (
    user_id    BIGINT    PRIMARY KEY REFERENCES users(id),
    available  INTEGER   NOT NULL DEFAULT 0,
    committed  INTEGER   NOT NULL DEFAULT 0,
    expires_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_credit_wallet_non_negative CHECK (available >= 0 AND committed >= 0)
);

CREATE TABLE credit_movement (
    id               BIGSERIAL   PRIMARY KEY,
    user_id          BIGINT      NOT NULL REFERENCES users(id),
    type             VARCHAR(30) NOT NULL,
    delta_available  INTEGER     NOT NULL,
    delta_committed  INTEGER     NOT NULL,
    order_id         BIGINT,       -- FK lógica a orders (V18)
    purchase_id      UUID,         -- FK lógica a credit_purchase (V19)
    description      VARCHAR(255),
    created_at       TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT chk_credit_movement_type CHECK (type IN
        ('WELCOME_GRANT','PACK_PURCHASE','DIRECT_PURCHASE','COMMIT','RELEASE',
         'CONSUME','EXPIRATION','PAYMENT_REVERSAL','ADMIN_ADJUSTMENT'))
);
CREATE INDEX idx_credit_movement_user ON credit_movement(user_id, created_at DESC);
CREATE UNIQUE INDEX uq_credit_movement_welcome
    ON credit_movement(user_id) WHERE type = 'WELCOME_GRANT';
```

**V18 `__orders.sql`**
```sql
CREATE TABLE orders (
    id            BIGSERIAL   PRIMARY KEY,
    user_id       BIGINT      NOT NULL REFERENCES users(id),
    company_id    BIGINT      REFERENCES company(id),   -- instantánea, NULL para B2C
    fecha         DATE        NOT NULL,
    pickup_at     TIMESTAMP   NOT NULL,
    estado        VARCHAR(20) NOT NULL,
    credit_total  INTEGER     NOT NULL,
    notas         TEXT,
    created_at    TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP   NOT NULL DEFAULT now(),
    confirmed_at  TIMESTAMP,
    comandado_at  TIMESTAMP,
    delivered_at  TIMESTAMP,
    cancelled_at  TIMESTAMP,
    CONSTRAINT chk_orders_estado CHECK (estado IN
        ('PENDIENTE','CONFIRMADO','COMANDADO','ENTREGADO','CANCELADO')),
    CONSTRAINT chk_orders_credit_total CHECK (credit_total > 0)
);
-- SIN unique (user_id, fecha): varios pedidos por día
CREATE INDEX idx_orders_fecha_pickup   ON orders(fecha, pickup_at);
CREATE INDEX idx_orders_user_fecha     ON orders(user_id, fecha);
CREATE INDEX idx_orders_company_fecha  ON orders(company_id, fecha) WHERE company_id IS NOT NULL;
CREATE INDEX idx_orders_pending_pickup ON orders(pickup_at) WHERE estado = 'PENDIENTE';

CREATE TABLE order_item (
    id              BIGSERIAL    PRIMARY KEY,
    order_id        BIGINT       NOT NULL REFERENCES orders(id),
    dish_id         BIGINT       NOT NULL REFERENCES dish(id),
    side_id         BIGINT       REFERENCES side(id),
    category_id     BIGINT       REFERENCES category(id),
    dish_nombre     VARCHAR(150) NOT NULL,   -- snapshot
    dish_categoria  VARCHAR(100) NOT NULL,   -- snapshot
    side_nombre     VARCHAR(100),            -- snapshot
    credit_cost     INTEGER      NOT NULL,   -- snapshot: reemplaza a precio_snapshot
    notas           TEXT,
    CONSTRAINT chk_order_item_credit_cost CHECK (credit_cost > 0)
);
CREATE INDEX idx_order_item_order ON order_item(order_id);
```

**V19 `__credit_packs_and_purchases.sql`**
```sql
CREATE TABLE credit_pack (
    id               BIGSERIAL    PRIMARY KEY,
    code             VARCHAR(20)  NOT NULL UNIQUE,   -- DAY | WEEK | MONTH
    nombre           VARCHAR(100) NOT NULL,
    credit_amount    INTEGER      NOT NULL,
    price_cents      BIGINT       NOT NULL,          -- ARS en centavos, autoritativo
    discount_percent INTEGER      NOT NULL DEFAULT 0,-- solo presentación
    orden_display    INTEGER      NOT NULL DEFAULT 0,
    enabled          BOOLEAN      NOT NULL DEFAULT true,
    deleted_at       TIMESTAMP,
    CONSTRAINT chk_credit_pack_amount CHECK (credit_amount > 0 AND price_cents > 0)
);

CREATE TABLE credit_purchase (
    id               UUID         PRIMARY KEY,
    user_id          BIGINT       NOT NULL REFERENCES users(id),
    type             VARCHAR(10)  NOT NULL,          -- PACK | DIRECT
    pack_id          BIGINT       REFERENCES credit_pack(id),
    order_id         BIGINT       REFERENCES orders(id),
    credit_amount    INTEGER      NOT NULL,
    amount_cents     BIGINT       NOT NULL,
    currency         VARCHAR(3)   NOT NULL DEFAULT 'ARS',
    status           VARCHAR(20)  NOT NULL,
    mp_preference_id VARCHAR(100),
    mp_payment_id    VARCHAR(50)  UNIQUE,            -- idempotencia del webhook
    mp_status_detail VARCHAR(100),
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now(),
    credited_at      TIMESTAMP,
    reversed_at      TIMESTAMP,
    CONSTRAINT chk_credit_purchase_status CHECK (status IN
        ('PENDING','APPROVED','REJECTED','CANCELLED','REVERSED','EXPIRED','IN_MEDIATION')),
    CONSTRAINT chk_credit_purchase_target CHECK (
        (type = 'PACK' AND pack_id IS NOT NULL) OR (type = 'DIRECT' AND order_id IS NOT NULL))
);
CREATE INDEX idx_credit_purchase_pending ON credit_purchase(created_at) WHERE status = 'PENDING';
```

**V20 `__restaurant_config_b2c.sql`**
```sql
ALTER TABLE restaurant_config ADD COLUMN pickup_lead_minutes     INTEGER NOT NULL DEFAULT 20;
ALTER TABLE restaurant_config ADD COLUMN credit_expiry_days      INTEGER NOT NULL DEFAULT 90;
ALTER TABLE restaurant_config ADD COLUMN pickup_window_start     TIME    NOT NULL DEFAULT '11:00';
ALTER TABLE restaurant_config ADD COLUMN pickup_window_end       TIME    NOT NULL DEFAULT '15:00';
ALTER TABLE restaurant_config ADD COLUMN pickup_slot_minutes     INTEGER NOT NULL DEFAULT 15;
ALTER TABLE restaurant_config ADD COLUMN daily_summary_time      TIME    NOT NULL DEFAULT '08:00';
ALTER TABLE restaurant_config ADD COLUMN pickup_reminder_minutes INTEGER NOT NULL DEFAULT 25;
```

**V21 `__notification_run_log.sql`**: `(tipo VARCHAR(30), fecha DATE, sent_at, recipients)` con
`PRIMARY KEY (tipo, fecha)` — mismo dedup atómico que `reminder_run_log`.

### `pickup_lead_minutes`: un solo valor, dos usos

Es la **única** configuración de tiempo de cocina, exactamente como pidió el usuario:

- retiro más temprano ofrecido = `now + pickup_lead_minutes`;
- punto de consumo/cierre = `pickup_at − pickup_lead_minutes`.

La ventana de retiro se deriva: slots cada `pickup_slot_minutes` entre `pickup_window_start` y
`pickup_window_end`, filtrando los anteriores a `now + lead`, en la semana actual o la siguiente,
excluyendo `fechas_deshabilitadas` (se reutiliza `FechaDeshabilitadaRepository`).

## Cambios de archivos (backend)

| Archivo | Acción | Descripción |
|---|---|---|
| `backend/.../credits/{CreditWallet,CreditMovement,MovementType,CreditWalletRepository,CreditMovementRepository,CreditLedgerService,CreditController,CreditExpiryScheduler}.java` | Crear | Libro mayor, saldos, vencimiento |
| `backend/.../credits/packs/{CreditPack,CreditPackRepository,CreditPackService,AdminCreditPackController,CreditPackDto}.java` | Crear | Catálogo de paquetes administrable |
| `backend/.../payments/{PaymentGateway,CheckoutRequest,CheckoutSession,PaymentSnapshot,PaymentStatus}.java` | Crear | Puerto y tipos del dominio, sin dependencia del SDK |
| `backend/.../payments/mercadopago/{MercadoPagoAdapter,MercadoPagoProperties,SignatureVerifier}.java` | Crear | Único punto donde viven los tipos `com.mercadopago.*` |
| `backend/.../payments/{CreditPurchase,CreditPurchaseRepository,CreditPurchaseService,CreditPurchaseController,MercadoPagoWebhookController,PaymentReconciliationScheduler}.java` | Crear | Compra, webhook, reconciliación |
| `backend/.../orders/{Order,OrderItem,OrderRepository,OrderItemRepository,OrderDto,OrderItemDto,PlaceOrderV2Request,PickupSlotService,OrderConsumptionScheduler}.java` | Crear | Modelo de pedido nuevo y programación de retiro |
| `backend/.../orders/notifications/{OrderNotificationScheduler,OrderNotificationEmails,NotificationRunLog,NotificationRunLogRepository}.java` | Crear | Resumen, cancelación, recordatorio |
| `backend/.../auth/{RegistrationService,GoogleAuthService,EmailVerificationToken,EmailVerificationTokenRepository}.java` + DTOs | Crear | Autorregistro, verificación, Google |
| `backend/.../orders/OrderService.java` | Modificar | `place/update/cancel` sobre `Order`; `resolvePrecio` sale del camino nuevo; `Company` solo como instantánea |
| `backend/.../orders/AdminOrderController.java`, `OrderExportService.java`, `AdminOrderDto.java` | Modificar | Consultan `orders`; se agrega agrupación/exportación por horario de retiro junto a la de empresa |
| `backend/.../orders/OrderEstado.java` | Modificar | Se agrega `CANCELADO` |
| `backend/.../auth/{AuthController,AuthService}.java` | Modificar | Endpoints de registro/verificación/Google/complete-profile; `me()` expone `emailVerified` y `profileComplete` |
| `backend/.../common/security/SecurityConfig.java` | Modificar | Se agregan a la lista pública: `/api/v1/auth/{register,verify-email,resend-verification,google}`, `/api/webhooks/mercadopago` |
| `backend/.../users/User.java` | Modificar | `phone`, `nickname`, `emailVerifiedAt`, `googleSub`. `company` y `category` **sin tocar** |
| `backend/.../catalog/categories/{Category,CategoryService,CategoryDto}.java` | Modificar | `creditCost` + validación de entero positivo |
| `backend/.../catalog/dishes/DishService.java` | Modificar | Se agrega `listAllAvailable(fecha)` para usuarios sin `categoryId`; el camino B2B no cambia |
| `backend/.../restaurantconfig/{RestaurantConfig,RestaurantConfigDto,UpdateRestaurantConfigRequest}.java` | Modificar | Siete campos de configuración nuevos |
| `backend/pom.xml` | Modificar | `com.mercadopago:sdk-java:3.7.0`, `com.google.api-client:google-api-client` |
| `backend/.../{billing,companies,metrics,users/CompanyAdminEmployee*}` | **Sin tocar** | Historia B2B congelada |
| `backend/.../orders/{DailyChoice,DailyChoiceRepository,DailyChoiceDto}.java`, `OrdersScheduler.java`, `users/notifications/OrderReminderScheduler.java` | **Sin tocar** | Camino B2B y jobs existentes intactos |

## Interfaces y contratos

```java
// backend/src/main/java/com/arias/payments/PaymentGateway.java
public interface PaymentGateway {
    CheckoutSession createCheckout(CheckoutRequest request);
    PaymentSnapshot getPayment(String paymentId);
    boolean verifySignature(String xSignature, String xRequestId, String dataId);
    // Sin refund(): el producto nunca inicia una devolución de dinero (decisión de producto).
    // El puerto queda extensible para agregarla sin tocar a los consumidores.
}

public record CheckoutRequest(String externalReference, String title,
                              int quantity, long unitPriceCents,
                              String payerEmail, String successUrl,
                              String pendingUrl, String failureUrl, String notificationUrl) {}
public record CheckoutSession(String preferenceId, String initPoint) {}
public record PaymentSnapshot(String paymentId, PaymentStatus status, String statusDetail,
                              long amountCents, String currency, String externalReference) {}
public enum PaymentStatus { APPROVED, PENDING, IN_PROCESS, AUTHORIZED, REJECTED,
                            CANCELLED, REFUNDED, CHARGED_BACK, IN_MEDIATION, UNKNOWN }
```

```java
// El movimiento es el ÚNICO modo de cambiar un saldo. La billetera nunca se escribe suelta.
@Transactional
public CreditMovement apply(Long userId, MovementType type,
                            int deltaAvailable, int deltaCommitted, MovementRef ref) {
    CreditWallet wallet = walletRepo.findByIdForUpdate(userId)   // SELECT ... FOR UPDATE
        .orElseGet(() -> walletRepo.save(CreditWallet.emptyFor(userId)));
    expireIfDue(wallet);                                          // guard perezoso del vencimiento
    // ... valida, aplica ambos deltas, persiste movimiento + saldo en la misma transacción
}
```

### Endpoints expuestos (contrato consumido por el frontend)

`POST /api/v1/auth/{register,verify-email,resend-verification,google,complete-profile}`,
`GET /api/v1/credits/wallet`, `GET /api/v1/credits/movements`, `GET /api/v1/credits/packs`,
`POST /api/v1/credits/purchases`, `GET /api/v1/credits/purchases/{id}`,
`POST /api/webhooks/mercadopago` (llamado por Mercado Pago, no por el frontend),
`GET /api/v1/orders/pickup-slots?fecha=`,
`POST /api/v1/orders`, `DELETE /api/v1/orders/{id}`,
`GET /api/v1/admin/orders/by-pickup?fecha=`, `GET /api/v1/admin/orders/export/by-pickup?fecha=`,
CRUD `/api/v1/admin/credit-packs`.

Los payloads exactos (`PlaceOrderV2Request`, `CreditPackDto`, `MeResponse` con `emailVerified`/
`profileComplete`, etc.) se definen al implementar los DTOs de la tabla de archivos anterior; la
mitad frontend los consume tal como queden serializados por Jackson (camelCase, fechas ISO-8601).

## Configuración

| Clave | Origen | Default |
|---|---|---|
| `arias.mercadopago.access-token` | env `MP_ACCESS_TOKEN` | — (obligatoria si `enabled`) |
| `arias.mercadopago.webhook-secret` | env `MP_WEBHOOK_SECRET` | — (obligatoria si `enabled`) |
| `arias.mercadopago.enabled` | env `MP_ENABLED` | `false` — interruptor del plan de reversión |
| `arias.public.frontend-url` / `arias.public.backend-url` | env | — (`auto_return` solo si el frontend es https) |
| `arias.google.client-id` | env `GOOGLE_CLIENT_ID` | — (mismo valor que el frontend usa como client ID de `@react-oauth/google`) |
| `pickup_lead_minutes`, `credit_expiry_days`, `pickup_window_*`, `pickup_slot_minutes`, `daily_summary_time`, `pickup_reminder_minutes` | `restaurant_config` (editable por el admin) | ver V20 |

Los secretos siguen el patrón ya usado por R2/Resend: propiedades tipadas con `@ConfigurationProperties`
y variables de entorno, nunca valores en el repositorio.

**Desarrollo local**: `back_urls` debe ser HTTPS y no puede ser `localhost`. Igual que en CeroComa,
`autoReturn("approved")` se setea **solo** si `frontend-url` empieza con `https`; para probar el
webhook en local se usa un túnel HTTPS como `backend-url`.

## Seguridad

| Superficie | Control |
|---|---|
| `POST /api/webhooks/mercadopago` | Público por necesidad. HMAC-SHA256 sobre el manifiesto `id:<data.id en minúsculas>;request-id:<x-request-id>;ts:<ts>;`, comparación en tiempo constante (`MessageDigest.isEqual`). Firma inválida → 401 sin trabajo ni log del cuerpo |
| Importe | Calculado **siempre** en el servidor desde `credit_pack.price_cents` o el total del pedido. El cliente solo manda `packId`/`orderId`. El webhook además compara `transaction_amount` contra el importe guardado |
| Acreditación | Nunca desde `back_urls`. Solo desde el webhook, tras `PaymentClient.get(id)` |
| Replay | `UNIQUE (mp_payment_id)` + bloqueo de fila + atajo de "ya acreditada" |
| Registro público | `/api/v1/auth/register` sin autenticar: la respuesta es idéntica exista o no el email (no revela cuentas, igual que el `check-email` actual). Unicidad de teléfono verificada en la base |
| Token de Google | Validado contra la audiencia y el emisor. Nunca se confía en el `email` sin `email_verified` del token |
| Verificación de correo | Token aleatorio de 256 bits, persistido como SHA-256 (mismo patrón que `PasswordResetToken`), un solo uso, con expiración |
| Autorización | Se mantiene `@PreAuthorize` por controller. `/admin/credit-packs` → `SUPER_ADMIN`; `/credits/**` → autenticado y con perfil completo |
| Cuentas sin verificar | Pueden iniciar sesión pero reciben `409` al pedir o comprar hasta verificar |

## Estrategia de testing (backend)

| Capa | Qué se prueba | Cómo |
|---|---|---|
| Unit | Máquina de estados del libro mayor: COMMIT/RELEASE/CONSUME/EXPIRATION/reversión; invariante `saldo == SUM(deltas)`; que EXPIRATION no toca COMMITTED; cálculo de slots de retiro con `Clock` fijo; mapeo de estados de Mercado Pago; verificador HMAC con vectores válidos e inválidos | JUnit 5 + `Clock.fixed` (ya inyectado en todo el código) + mocks del `PaymentGateway` |
| Integración | Webhook duplicado no acredita dos veces (contra la restricción de unicidad real); saldo insuficiente no compromete parcialmente; pedido de empleado de empresa consume créditos **y** sigue apareciendo en el export por empresa; el almuerzo de bienvenida no se otorga dos veces; migraciones V15–V21 aplican limpias | `spring-boot-starter-data-jpa-test` + `flyway-test` (slices ya presentes en `pom.xml`) |
| Regresión B2B | `BillingServiceTest` sigue verde sin cambios; `first-login` y el listado de platos por categoría del empleado de empresa no cambian de comportamiento | Suite existente + un test nuevo del camino de pedido de empresa **antes** de tocar `OrderService` |
| Manual | Sandbox de Mercado Pago con tarjetas de prueba (APRO/OTHE/CONT/FUND) y túnel HTTPS para el webhook | Checklist en la fase de tareas |

`./mvnw test` debe pasar. La concurrencia del libro mayor se cubre con un test de compromisos
paralelos sobre la misma billetera, verificando que no haya sobregiro.

## Matriz de amenazas

N/A — el cambio no toca enrutamiento de procesos, comandos de shell, subprocesos, automatización de
VCS/PR, clasificación de archivos ejecutables ni integración de procesos. La superficie externa es
HTTP entrante (webhook) y saliente (API de Mercado Pago, Google, Resend), cubierta en la sección
**Seguridad** con controles concretos y tests asociados.

## Migración y reversión

**Migración de datos**: ninguna. Las siete migraciones son `ADD COLUMN` con default, `CREATE TABLE`
y dos `UPDATE` de relleno idempotentes (`email_verified_at`). No hay `DROP`, no hay reescritura de
`daily_choice`, `company` ni `company_category_price`.

**Despliegue**: el orden de las tareas sigue el de la propuesta (créditos → autorregistro → pedidos →
pagos → notificaciones → admin). `arias.mercadopago.enabled=false` permite desplegar todo el resto
antes de tener credenciales productivas. Este backend debe quedar desplegado y estable antes de que
la mitad frontend comience su implementación (ver `proposal.md`).

**Reversión**: una sola rama de funcionalidad, sin producción. Revertir = descartar la rama y
recrear la base local; las migraciones son archivos aditivos que se van con la rama. Como nada
existente se modificó, el flujo B2B queda intacto por construcción. Si Mercado Pago falla después de
lanzar, se pone `arias.mercadopago.enabled=false`: las compras de paquetes se deshabilitan y los
créditos ya otorgados siguen gastándose.

**Convivencia de los dos modelos de pedido**: a partir del despliegue, `daily_choice` deja de recibir
filas. Las vistas del admin leen `orders`; el reporte de facturación lee `daily_choice`. Los dos
conjuntos no se solapan, por lo que no hay doble conteo.

## Puntos abiertos

- [ ] Verificar en el sandbox el comportamiento exacto de `payment.status` ante un reembolso
      **parcial** (la investigación lo dejó sin confirmar). Si queda `approved` con
      `transaction_amount_refunded`, la reversión debe ser proporcional, no total.
- [ ] Confirmar la versión de `com.mercadopago:sdk-java` disponible en Maven Central al implementar
      (la investigación vio deriva de versión entre espejos; el objetivo es 3.7.0).
- [ ] Definir con el cliente los valores iniciales de `credit_pack` (créditos y precio por
      día/semana/mes). El esquema no los fija; se cargan por el panel de administración.
