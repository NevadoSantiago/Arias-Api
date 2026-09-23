# Pruebas manuales pendientes — b2c-credits-pivot

Las dos verificaciones que ningún test automático puede cubrir. La primera protege
al cliente que ya paga; la segunda es la única forma de saber si los pagos funcionan
de verdad.

Hacer la **regresión de empresas primero**: si algo se rompió ahí, importa más que
cualquier funcionalidad nueva.

---

## 1. Regresión del flujo de empresas (B2B)

**Qué se está verificando:** que el canal que hoy factura siga funcionando igual.
Todo el cambio se hizo de forma aditiva justamente para esto, y 24 tests
automáticos lo cubren, pero ninguno abre el navegador.

Entrar como `SUPER_ADMIN` (por defecto `admin@arias.com` / `admin123` si no se
cambió el bootstrap).

- [ ] **Empresas**: abrir `/admin/companies`, editar una empresa y guardar. Los
      precios por categoría y la hora de entrega siguen editables.
- [ ] **Facturación**: abrir `/admin/billing`, elegir una empresa y un rango. Los
      totales siguen saliendo y el detalle por día se ve.
- [ ] **Alta de empleado**: entrar como `COMPANY_ADMIN`, cargar el correo de un
      empleado nuevo en `/company-admin/employees`.
- [ ] **Primer ingreso**: entrar con ese correo, completar nombre y contraseña.
      El alta por invitación no pasa por verificación de correo.
- [ ] **Pedido de empleado**: pedir un plato del día. **La pantalla tiene que verse
      exactamente como antes**: un solo plato, sin carrito, sin almuerzos, sin
      selector de horario de retiro.
- [ ] **Empleado sin correo verificado**: ese empleado recién creado tiene el correo
      sin verificar y **igual tiene que poder pedir**. Si ve un `409`, el bloqueo
      B2C se filtró al canal de empresas y hay que corregirlo.
- [ ] **Panel del admin**: el pedido aparece en la vista agrupada por empresa, se
      exporta y se puede marcar como comandado y entregado.

Cualquier diferencia respecto de cómo funcionaba antes es un defecto, por chica
que parezca.

---

## 2. Mercado Pago de punta a punta

**Qué se está verificando:** que el circuito de cobro funcione contra la API real.
Los tests usan un simulador: la API de Mercado Pago **nunca se tocó** desde el
código.

### 2.1 Aplicación nueva (no reutilizar otra)

Crear una aplicación **exclusiva de Arias** en el panel de Mercado Pago. No usar
credenciales de otro proyecto: las credenciales probadas en esta sesión pertenecían
a una cuenta con **pagos reales** de otro sistema, y cualquier operación de dinero
sobre esa cuenta afecta clientes de verdad.

- [ ] Crear la aplicación con el producto **Checkout Pro**.
- [ ] Copiar de **Credenciales de prueba**: `Access Token` y `Public Key`.
- [ ] En **Webhooks**, configurar la URL de notificación y copiar la **clave secreta**
      que genera Mercado Pago (con esa se valida la firma).

### 2.2 Túnel público

Mercado Pago tiene que poder alcanzar el backend, y rechaza `localhost`.

```bash
ngrok http 8080
```

Anotar la URL `https://...` que devuelve. La URL del webhook es esa más
`/api/webhooks/mercadopago`.

### 2.3 Levantar el backend apuntando al túnel

```bash
MP_ENABLED=true \
MP_ACCESS_TOKEN=<access token de prueba> \
MP_WEBHOOK_SECRET=<clave secreta del webhook> \
BACKEND_URL=https://<tu-tunel>.ngrok-free.app \
FRONTEND_URL=http://localhost:5174 \
GOOGLE_CLIENT_ID=<client id> \
./mvnw spring-boot:run
```

Con `FRONTEND_URL` en `http://localhost:5174` el retorno automático queda
desactivado (Mercado Pago exige HTTPS), así que hay que volver a la app a mano.
Es lo esperado en desarrollo.

### 2.4 El circuito

- [ ] Crear un pack desde `/admin/credit-packs` (por ejemplo 10 almuerzos).
- [ ] Como cliente verificado, comprarlo desde `/credits/packs`.
- [ ] Pagar en Mercado Pago con tarjeta de prueba: `5031 7557 3453 0604`,
      código `123`, vencimiento `11/30`, titular **`APRO`** (el nombre del titular
      decide el resultado: `APRO` aprueba, `OTHE` rechaza).
- [ ] **Mirar el log del backend**: tiene que entrar el webhook, validar la firma y
      acreditar. Si la firma falla, el backend rechaza y **no acredita**: eso está
      bien, significa que la clave secreta no coincide.
- [ ] Verificar el saldo en `/credits`: los almuerzos acreditados y el vencimiento
      renovado a 90 días.
- [ ] **Repetir el aviso**: reenviar la misma notificación desde el panel de
      Mercado Pago. El saldo **no** tiene que cambiar. Esta es la prueba más
      importante de todas: Mercado Pago reintenta hasta 8 veces.
- [ ] Probar un pago rechazado con titular `OTHE`: no se acredita nada y la compra
      queda cerrada.

### 2.5 Lo que quedó sin verificar (unidad 9)

El comportamiento exacto de un **reembolso parcial** nunca se pudo probar. La
reversión se implementó proporcional al monto devuelto, que es correcta tanto si
Mercado Pago deja el pago en `approved` como si lo pasa a `refunded`. Cuando haya
credenciales que permitan operar, reembolsar la mitad de un pago y confirmar que
se descuenta la mitad de los almuerzos.

---

## 3. Antes de publicar

- [ ] **Verificar un dominio en Resend.** Hoy la cuenta solo puede enviar correos a
      la casilla del titular, así que **ningún cliente recibiría la verificación**.
- [ ] Agregar el dominio de producción a los orígenes autorizados del cliente de
      Google.
- [ ] Credenciales de producción de Mercado Pago y URL pública real del webhook.
- [ ] Revisar con el cliente: horario de retiro (hoy 11:00 a 15:00 por defecto),
      precio del almuerzo suelto, tamaños y precios de los packs.
