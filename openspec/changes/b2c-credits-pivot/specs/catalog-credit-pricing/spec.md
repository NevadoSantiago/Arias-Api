# Especificación: catalog-credit-pricing

## Purpose

Establecer una única moneda de crédito para todo el sistema: cada `Category` tiene un costo en créditos entero y fijo, editable por el administrador, mostrado al cliente como "almuerzos". Reemplaza el precio negociado por empresa (`CompanyCategoryPrice`) como fuente de costo para el consumo de pedidos.

## Requirements

### Requirement: Costo en créditos entero y fijo por categoría
El sistema MUST asociar a cada `Category` un `creditCost` entero positivo y fijo (no fraccionario), con valor 1 para la categoría estándar actual.

#### Scenario: Categoría estándar
- GIVEN la categoría estándar existente
- WHEN se consulta su costo en créditos
- THEN el valor es 1

#### Scenario: Rechazo de costo fraccionario
- GIVEN un administrador editando el `creditCost` de una categoría
- WHEN intenta guardar un valor no entero o menor o igual a cero
- THEN el sistema rechaza el cambio

### Requirement: Moneda única, sin tokens diferenciados
El sistema MUST NOT introducir un segundo tipo de crédito (por ejemplo, "premium" vs. "básico"); todas las categorías consumen de la misma moneda de crédito.

#### Scenario: Todas las categorías comparten el mismo saldo
- GIVEN un cliente con saldo disponible de almuerzos
- WHEN pide un plato de cualquier categoría
- THEN el pedido descuenta del mismo saldo, sin distinción de tipo de crédito

### Requirement: Costo visible para el cliente como "almuerzos"
El sistema MUST mostrar al cliente el costo en créditos de cada plato/categoría usando el término "almuerzos", nunca "créditos", en toda la interfaz orientada al cliente.

#### Scenario: Visualización del costo
- GIVEN un cliente navegando el catálogo
- WHEN visualiza un plato
- THEN el sistema muestra su costo como "N almuerzo(s)"

### Requirement: Edición administrativa del costo por categoría
El sistema MUST permitir a un administrador (`SUPER_ADMIN`) editar el `creditCost` de una categoría existente, aplicándose a partir de ese momento sin alterar las instantáneas (`snapshots`) de pedidos ya realizados.

#### Scenario: Cambio de costo no afecta pedidos previos
- GIVEN un pedido ya realizado con una instantánea de costo de 1 almuerzo
- WHEN el administrador cambia el `creditCost` de esa categoría a 2
- THEN el pedido previamente realizado conserva su instantánea original de 1 almuerzo
