# Especificación: self-registration (dominio/API — mitad backend)

## Purpose

Permitir que cualquier persona se dé de alta públicamente (por ejemplo, escaneando un código QR) sin intervención de un administrador, con verificación de identidad mínima y un almuerzo de bienvenida como incentivo de activación. El flujo de alta por lista blanca (`first-login`) para empleados de empresas se conserva sin cambios y convive con este nuevo flujo.

Este documento cubre los requisitos de **dominio, API, verificación y otorgamiento del almuerzo de
bienvenida**. Los requisitos de **flujo de interfaz** de esta misma capacidad (formulario de
registro, botón de Google, pantalla de felicitación, UX de verificación de correo) viven en
`C:\Arias\frontend\openspec\changes\b2c-credits-pivot\specs\self-registration\spec.md`. Ningún
requisito se duplica entre las dos copias.

## Requirements

### Requirement: Alta pública con datos mínimos
El sistema MUST exponer un endpoint de registro público (no autenticado) que capture nombre, correo electrónico, teléfono y un apodo para mostrar en el ticket de cocina.

#### Scenario: Registro exitoso
- GIVEN un visitante que escaneó el QR y no tiene cuenta previa
- WHEN envía nombre, correo, teléfono y apodo válidos al endpoint de registro
- THEN el sistema crea la cuenta en estado no verificado
- AND no otorga todavía ningún almuerzo de bienvenida

#### Scenario: Campos obligatorios faltantes
- GIVEN una solicitud de registro al endpoint público
- WHEN omite el teléfono o el apodo
- THEN el sistema rechaza el registro y señala los campos faltantes

### Requirement: Verificación obligatoria de correo electrónico
El sistema MUST impedir el uso normal de la cuenta (inicio de sesión con acceso completo) hasta que el correo electrónico haya sido verificado mediante un enlace o código enviado a esa dirección.

#### Scenario: Cuenta no verificada bloqueada
- GIVEN una cuenta recién registrada sin verificar
- WHEN el usuario intenta iniciar sesión y pedir un almuerzo
- THEN el sistema bloquea el pedido con una respuesta que indica verificación pendiente

#### Scenario: Verificación exitosa
- GIVEN una cuenta con un correo de verificación enviado
- WHEN el usuario confirma el enlace o código antes de que expire
- THEN el sistema marca la cuenta como verificada y habilita el uso normal

### Requirement: Validación de teléfono contra duplicados
El sistema MUST rechazar un registro cuyo teléfono ya esté asociado a una cuenta existente, para disuadir la creación de cuentas duplicadas.

#### Scenario: Teléfono ya registrado
- GIVEN un teléfono ya asociado a una cuenta existente
- WHEN otra persona intenta registrarse con ese mismo teléfono
- THEN el sistema rechaza el nuevo registro e informa que el teléfono ya está en uso

### Requirement: Inicio de sesión con Google
El sistema MUST permitir registrarse e iniciar sesión mediante Google como método prioritario, validando el ID token en el backend y solicitando los datos obligatorios (teléfono, apodo) que Google no provee antes de habilitar la cuenta.

#### Scenario: Alta con Google
- GIVEN un visitante sin cuenta previa
- WHEN el backend recibe y valida un ID token de Google
- THEN el sistema crea la cuenta con el correo verificado por Google
- AND responde indicando que faltan teléfono y apodo antes de habilitar el uso completo

### Requirement: Otorgamiento único del almuerzo de bienvenida
El sistema MUST otorgar exactamente 1 almuerzo de bienvenida, no transferible, al momento en que la cuenta queda validada por primera vez (verificación de correo completada, o alta mediante Google), y MUST NOT otorgarlo nuevamente ante una re-verificación o un nuevo intento de validación sobre la misma cuenta.

#### Scenario: Otorgamiento en la primera validación
- GIVEN una cuenta recién validada por primera vez
- WHEN el sistema procesa la validación
- THEN acredita exactamente 1 almuerzo de bienvenida no transferible en el libro mayor de créditos

#### Scenario: Sin doble otorgamiento
- GIVEN una cuenta ya validada que recibió su almuerzo de bienvenida
- WHEN el correo se reenvía o se re-verifica por cualquier motivo
- THEN el sistema NO otorga un segundo almuerzo de bienvenida

### Requirement: Coexistencia con el flujo de lista blanca de empresas
El sistema MUST mantener sin cambios el flujo existente `first-login` para empleados pre-cargados por una empresa, operando en paralelo al autorregistro público sin interferir entre sí.

#### Scenario: Empleado de empresa completa first-login
- GIVEN un empleado pre-cargado por un `COMPANY_ADMIN` con contraseña sin definir
- WHEN completa el flujo existente `first-login` (nombre + contraseña)
- THEN el sistema activa la cuenta como hoy, sin pasar por el autorregistro público ni el otorgamiento de almuerzo de bienvenida
