# Propuesta: Pivote a Créditos B2C (aditivo) — mitad backend

## Cambio coordinado en dos repositorios

Este cambio es **una mitad de un cambio SDD coordinado en dos repositorios independientes**. El
mismo cambio `b2c-credits-pivot` fue planificado originalmente en un único directorio (`C:\Arias\openspec`)
que no comparte directorio común de Git con el runtime, y quedó bloqueado (`cross_common_dir_runtime_target`).
Se dividió en dos changes autocontenidos, uno por repositorio:

- **Backend** (este documento): `C:\Arias\backend\openspec\changes\b2c-credits-pivot\`
- **Frontend** (mitad hermana): `C:\Arias\frontend\openspec\changes\b2c-credits-pivot\`

**El backend debe implementarse y desplegarse primero.** El frontend consume la API que este cambio
expone (autorregistro, libro mayor de créditos, pedidos, checkout de Mercado Pago, notificaciones,
administración); no hay contrato estable contra el cual construir la UI hasta que los endpoints del
backend existan. La copia original combinada en `C:\Arias\openspec\changes\b2c-credits-pivot\`
permanece intacta como referencia de las dos mitades juntas.

## Intención

Actualmente Arias es exclusivamente B2B: las empresas dan de alta a sus empleados en una lista blanca,
negocian precios por categoría (`CompanyCategoryPrice`) y son facturadas; el cliente nunca ve un
precio. El producto incorpora un canal B2C — las personas se autorregistran a partir de un código QR,
compran "almuerzos" con Mercado Pago y los gastan en platos que retiran en el restaurante.

**Este cambio es ADITIVO, no un reemplazo.** El programa de empresas (B2B) SE MANTIENE y sigue
funcionando, en espera para una futura campaña corporativa. **No se elimina nada relacionado con
empresas**: `Company`, `CompanyCategoryPrice`, `COMPANY_ADMIN`, la lista blanca de empleados y los
endpoints de administración de empresas se conservan en su totalidad. No está prevista ninguna
migración ni eliminación de datos de empresas.

Los créditos pasan a ser el **único mecanismo de consumo para todos los pedidos**, tanto B2C como
B2B — las empresas también comprarán créditos para sus empleados. La lógica del lado de empresas se
modifica **solo donde la reutilización realmente lo requiere** (que los pedidos consuman créditos en
lugar de instantáneas de precio); no se construyen nuevas funcionalidades de empresas por ahora.

Éxito (mitad backend): la API expone alta pública, verificación de correo, inicio de sesión con
Google, un libro mayor de créditos con vencimiento y compromiso/consumo automático, paquetes y compra
directa mediante Mercado Pago, programación de retiro y notificaciones — mientras el flujo de
empresas existente sigue funcionando sin cambios de comportamiento.

## Vocabulario

Los textos orientados al usuario dicen **"almuerzos" (lunches)**, nunca "créditos". Ese vocabulario
lo aplica la UI (mitad frontend); esta mitad expone la API y el dominio en inglés/español técnico
según la convención ya usada en el código base (`credit`, `creditCost`, etc. permanecen en inglés).

## Alcance

### Dentro del alcance (backend)

- **Autorregistro (nuevo)**: endpoint público de alta con nombre, correo electrónico, teléfono y
  apodo; verificación obligatoria de correo electrónico; validación de teléfono (unicidad) para
  disuadir cuentas duplicadas; **inicio de sesión con Google (prioritario)** validado en el backend;
  al validarse, el usuario recibe **1 almuerzo de bienvenida no transferible**. El flujo de lista
  blanca `first-login` existente se conserva para los empleados de empresas.
- **Una única moneda de crédito**: cada `Category` tiene un `creditCost` ENTERO fijo (1 para la
  categoría estándar actual). Sin token premium, sin costos fraccionarios.
- **Se elimina la restricción por categoría de usuario para B2C**: cualquier cliente puede pedir
  cualquier plato. `User.category` / el `categoryId` del JWT dejan de restringir el B2C; para
  empleados de empresa la restricción existente se conserva sin cambios.
- **Modelo de pedidos**: `DailyChoice` → `Order` + `OrderItem`, instantáneas por ítem, múltiples
  pedidos por día, el total de créditos del pedido = suma de los ítems.
- **La ventana de programación no cambia respecto de hoy: semana actual + semana siguiente.**
- **Solo retiro** en el restaurante (sin entrega a domicilio): ventana de pedidos y tiempo de
  preparación configurables, horario de retiro elegido por el cliente. Las vistas de administración
  de pedidos incorporan agrupación por horario de retiro. Las empresas conservan su comportamiento
  actual de horario de entrega, salvo que la reutilización obligue a cambiarlo.
- **Estados de crédito AVAILABLE vs COMMITTED**: al realizar/programar un pedido se comprometen
  créditos; se consumen automáticamente cuando el reloj llega a `(horario de retiro − antelación
  configurable, 20 min por defecto)`, el punto de confirmación del pedido. Cancelar antes de ese
  momento devuelve los créditos a AVAILABLE. No existe un estado separado de "en preparación".
- **Vencimiento**: una única fecha de vencimiento global para todo el saldo, 90 días por defecto,
  configurable por el administrador. Solo las compras de paquetes lo renuevan; las compras directas
  no. El vencimiento genera un movimiento auditable `EXPIRATION` en el libro mayor que pone el saldo
  en cero. Los reembolsos por cancelaciones devuelven créditos pero nunca renuevan el vencimiento.
- **Paquetes planteados como día / semana / mes** con descuentos por volumen; tamaños, precios y
  descuentos configurables por el administrador.
- **Compra directa**: pagar dinero por un único pedido compra exactamente los créditos necesarios sin
  descuento y los consume de inmediato (un movimiento de libro mayor, un solo flujo).
- **Pagos**: Mercado Pago Checkout Pro mediante el SDK oficial de Java, aislado detrás de un puerto
  `PaymentGateway`. Los créditos se otorgan únicamente desde el webhook validado por HMAC
  (`x-signature`/`x-request-id`) tras volver a consultar el pago — nunca desde la redirección del
  navegador — idempotente mediante una restricción de unicidad sobre el id de pago de MP. Se manejan
  los estados no aprobados (refunded/charged_back revierten los créditos; rejected/cancelled cierran
  la compra pendiente). Job de reconciliación para compras pendientes obsoletas y para el
  vencimiento. Los efectos secundarios de correo electrónico se mantienen fuera de la transacción
  bloqueada.
- **Notificaciones (nuevo)**: resumen matutino de los pedidos del día, alerta de cancelación y un
  recordatorio de 20 a 30 min antes del retiro. Reutiliza el patrón de `OrderReminderScheduler` sin
  tocarlo.
- Migraciones de Flyway para cada cambio de esquema (`V15`–`V21`).

### Fuera del alcance (backend)

- Eliminar, migrar o degradar cualquier funcionalidad de empresas/B2B.
- Nueva lógica del lado de empresas: asignación mensual de créditos por empleado por empresa,
  interfaz de compra de créditos orientada a empresas más allá de lo que exige la reutilización.
- Extraer los pagos a un microservicio independiente (el puerto deja esto disponible para más
  adelante).
- Checkout Bricks o cualquier checkout embebido/en el propio sitio.
- Entrega o envío de cualquier tipo.
- Bebidas/postres como adicionales de crédito fraccionario.
- Transferencia de créditos entre usuarios (etapa 2).
- Todo trabajo de UI/frontend: formularios, pantallas, rutas, landing, Vitest. Ese trabajo está en la
  mitad hermana `C:\Arias\frontend\openspec\changes\b2c-credits-pivot\`.

## Capacidades

### Capacidades nuevas (backend)

- `self-registration`: alta pública, verificación de correo electrónico, validación de teléfono,
  inicio de sesión con Google, otorgamiento del almuerzo de bienvenida, claims del JWT. (Los
  requisitos de flujo de UI de esta misma capacidad viven en la mitad frontend.)
- `catalog-credit-pricing`: costo en créditos entero fijo por `Category`.
- `order-placement`: pedidos con múltiples ítems y múltiples pedidos por día, debitados del saldo de
  créditos, ventana de semana actual + siguiente.
- `pickup-scheduling`: ventana de pedidos, tiempo de preparación, horario de retiro elegido por el
  cliente, punto automático de confirmación/consumo.
- `credit-ledger`: saldo AVAILABLE/COMMITTED, movimientos, vencimiento global renovable, reembolsos,
  job de expiración.
- `credit-pack-purchase`: paquetes de día/semana/mes y compra directa mediante Mercado Pago Checkout
  Pro detrás de un puerto de pago.
- `order-notifications`: resumen matutino, alerta de cancelación, recordatorio previo al retiro.
- `admin-order-fulfillment`: listado de pedidos para el administrador, exportación y marcado de
  entrega, con agrupación por horario de retiro agregada junto con la agrupación por empresa que se
  conserva.

### Capacidades modificadas

- Ninguna. `backend/openspec/specs/` está vacío; este es el primer cambio especificado en este
  repositorio. Los cambios de comportamiento B2B existentes se registran en la tabla siguiente, no
  como specs delta.

## Comportamiento existente: modificado vs. sin modificar (backend)

| Comportamiento existente | Estado |
|---|---|
| `Company`, `CompanyAdmin*`, rol `COMPANY_ADMIN`, lista blanca | **Sin modificar** — se conserva en espera |
| Consumo de pedidos | **Modificado** — todos los pedidos (B2C y B2B) consumen créditos en lugar de instantáneas de `CompanyCategoryPrice` |
| Facturación de `BillingService` / `CompanyCategoryPrice` | **Punto abierto** — se conserva tal cual o es reemplazada por compras de créditos de empresas; se decide en diseño, no se elimina |
| Flujo de lista blanca `first-login` | **Se conserva** para empleados de empresas; convive con el autorregistro |
| Horario de entrega `horaEntrega` de empresas | **Sin modificar**, salvo que la reutilización obligue a un cambio |
| Restricción de `User.category` / `categoryId` del JWT | **Modificado** — ya no restringe el B2C; la semántica para empleados se decide en diseño |
| Infraestructura de `catalog/{menusections,sides}`, `uploads`, `email` | **Sin modificar** |

## Enfoque

Construir de forma aditiva junto al flujo B2B en funcionamiento, en este orden:

1. `Category.creditCost` + libro mayor de créditos (AVAILABLE/COMMITTED, vencimiento, movimientos).
2. Autorregistro, verificación de correo electrónico, inicio de sesión con Google, almuerzo de
   bienvenida.
3. `Order`/`OrderItem` + programación de retiro; enrutar **todo** el consumo de pedidos (B2C y B2B) a
   través del libro mayor.
4. Mercado Pago Checkout Pro detrás de `PaymentGateway`: paquetes, compra directa, webhook,
   reconciliación.
5. Notificaciones.
6. Administración: agrupación por horario de retiro, configuración de paquetes/vencimiento/ventana.

Los pagos siguen el flujo de Checkout Pro que ya funciona en CeroComa (se crea primero la compra
pendiente con montos calculados en el servidor, `external_reference` = id interno, redirección
`initPoint`, nueva consulta del pago desde el webhook, `SELECT FOR UPDATE` con un atajo para pagos ya
procesados), corrigiendo a la vez sus falencias conocidas: validación HMAC, manejo de estados no
aprobados, reconciliación de compras pendientes obsoletas, efectos secundarios de correo electrónico
fuera del bloqueo, migraciones de Flyway, una restricción de unicidad sobre el id de pago de MP, y
tests.

El puerto `PaymentGateway` es una desviación deliberada de la convención del código base de
organización por funcionalidad (package-by-feature) sin puertos, justificada por la extracción futura
planeada.

## Áreas afectadas

| Área | Impacto | Descripción |
|------|--------|-------------|
| `backend/.../companies`, `billing`, `metrics/CompanyAdmin*`, `users/CompanyAdminEmployee*` | Sin modificar | Se conserva en espera; sin eliminación, sin migración |
| `backend/.../credits`, `backend/.../payments` | Nuevo | Libro mayor, paquetes, puerto `PaymentGateway` + adaptador de Mercado Pago, webhook, reconciliación |
| `backend/.../auth` (`AuthService`, `JwtService`, `SecurityConfig`) | Modificado | Endpoints de autorregistro + verificación de correo electrónico + inicio de sesión con Google; el claim `categoryId` ya no restringe el B2C; se conserva `first-login` |
| `backend/.../orders` | Modificado | `Order`/`OrderItem`, débito de créditos para todos los pedidos, horario de retiro, agrupación/exportación por horario de retiro |
| `backend/.../catalog/categories` | Modificado | Campo `creditCost` entero y edición por el administrador |
| `backend/.../users/notifications/OrderReminderScheduler` | Modificado | Reutilización/extensión para el resumen matutino, la alerta de cancelación y el recordatorio previo al retiro |
| `backend/.../restaurantconfig` | Modificado | Ventana de pedidos, tiempo de preparación, días de vencimiento, catálogo de paquetes |
| `backend/src/main/resources/db/migration` | Nuevo | `credit_cost`, tablas del libro mayor, pedidos/ítems, id de pago de MP único — solo aditivo |

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|------|------------|------------|
| Enrutar los pedidos B2B a través de créditos rompe el flujo de empresas en funcionamiento | Alta | Modificar el código de empresas solo donde la reutilización lo requiera; cubrir el camino de pedidos de empresas con tests de backend antes y después |
| Vacíos en la documentación de Mercado Pago (esquema de preferencia, familia de clientes Payments vs. Orders, ventana de reembolso) | Alta | Reverificar los tres puntos en diseño antes de programar; probar en sandbox |
| Condición de carrera entre webhook/redirección o notificaciones duplicadas | Media | Acreditar solo desde el webhook validado; idempotencia sobre el id de pago de MP |
| Dos caminos de precios coexistiendo (créditos + `CompanyCategoryPrice`) generan ambigüedad | Media | Resolver el punto abierto de facturación en diseño y documentar la regla de consumo único |
| El cambio excede el presupuesto de revisión de 400 líneas | Alta | Dividir en unidades de trabajo secuenciales en la fase de tareas |
| Errores de sincronización en el compromiso/consumo de créditos (doble gasto, reembolso perdido) | Media | Solo movimientos de libro mayor, ninguna mutación de saldo sin un movimiento; tests de concurrencia |
| El frontend queda bloqueado si el contrato de API cambia después de iniciado su desarrollo | Media | Backend se implementa y estabiliza primero (ver "Cambio coordinado"); el diseño frontend documenta el contrato consumido |

## Plan de reversión

Una única rama de funcionalidad, sin despliegue a producción. Revertir = descartar la rama (o hacer
`git revert` del merge) y eliminar la base de datos local; las migraciones de Flyway son archivos
aditivos que se eliminan junto con la rama. Como el cambio es aditivo, revertirlo deja intacto el
flujo B2B existente — no hay datos ni comportamiento de empresas que restaurar. Si Mercado Pago falla
después del lanzamiento, el puerto `PaymentGateway` permite deshabilitar las compras de paquetes
mientras los créditos ya otorgados siguen siendo utilizables.

## Dependencias

- Cuenta de Mercado Pago con credenciales de prueba, secreto de aplicación para el HMAC del webhook,
  y una URL de callback HTTPS pública (`auto_return` rechaza localhost).
- `com.mercadopago:sdk-java` (versión a confirmar en diseño).
- Credenciales de cliente OAuth de Google para el inicio de sesión con Google.
- Un canal con capacidad de correo electrónico/SMS para la verificación (reutilizar el paquete
  `email` existente; mecanismo de validación de teléfono a confirmar en diseño).

## Criterios de éxito (backend)

- [ ] El flujo de empresas existente (lista blanca, pedidos de empleados de empresas, endpoints de
      administración de empresas) sigue funcionando de punta a punta después del cambio.
- [ ] No se eliminó ninguna entidad, rol ni endpoint de empresas.
- [ ] Un nuevo visitante puede autorregistrarse por API, verificar el correo electrónico o usar el
      inicio de sesión con Google, y recibe exactamente 1 almuerzo de bienvenida no transferible.
- [ ] Un cliente puede realizar múltiples pedidos por día, cada uno con múltiples platos, de cualquier
      categoría, para la semana actual o la siguiente, eligiendo un horario de retiro válido.
- [ ] Realizar un pedido mueve los créditos a COMMITTED; se consumen automáticamente en
      `retiro − antelación (20 min por defecto)`; cancelar antes de ese momento los devuelve a
      AVAILABLE.
- [ ] La compra de un paquete acredita el saldo exactamente una vez, solo desde el webhook validado
      por firma, y renueva el único vencimiento global; las compras directas no lo renuevan.
- [ ] Los pagos refunded/charged_back revierten los créditos; rejected/cancelled cierran la compra
      pendiente; las compras pendientes obsoletas se reconcilian.
- [ ] Los saldos vencidos generan un movimiento auditable `EXPIRATION` en lugar de una eliminación.
- [ ] Las vistas de administración de pedidos pueden agruparse por horario de retiro; los tamaños de
      paquete, descuentos, ventana de pedidos, antelación y días de vencimiento son configurables por
      el administrador vía API.
- [ ] `./mvnw test` pasa correctamente.

## Puntos abiertos para diseño

- **Facturación de empresas**: ¿se conserva `BillingService` / `CompanyCategoryPrice` tal cual, o se
  reemplaza por compras de créditos de empresas? La eliminación no está en consideración.
- Tamaños, precios y descuentos por volumen de los paquetes de créditos (día/semana/mes) —
  configurables por el administrador, no fijos en el código.
- Qué significan `User.category` / el `categoryId` del JWT para los empleados de empresas existentes
  una vez eliminada la restricción B2C.
- Si el árbol de `Category` (`parentId`) sigue teniendo sentido con una única categoría activa.
- Ventana de cancelación de pedidos y si la cancelación restaura el stock de platos.
- Mecanismo de validación de teléfono y con qué grado de rigurosidad bloquea cuentas duplicadas.
- Si se extiende `OrderReminderScheduler` o se agrega un nuevo scheduler para los tres tipos de
  notificación.
- Mercado Pago: esquema de creación de preferencia, familia de clientes de API clásica Payments vs.
  Orders, ventana de tiempo para reembolsos.

## Nota de entrega

La estrategia de entrega es `single-pr` (desarrollador individual, no se abren PRs — se trata como un
único bloque de trabajo). El cambio abarca ~2 dominios nuevos de backend más modificaciones en auth,
orders, catalog, notifications y config, lo que excede el presupuesto de revisión de 400 líneas, por
lo que se esperan unidades de trabajo secuenciales en la fase de tareas.
