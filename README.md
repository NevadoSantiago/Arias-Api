# Arias — Sistema de pedidos empresariales

Trabajo de Fin de Máster — Máster de Desarrollo con IA (BIG School).

Aplicación real en producción para un bodegón porteño: gestiona el menú diario y permite
que empleados de empresas clientes pidan su almuerzo online, con un panel de administración
para el restaurante y otro para cada empresa.

Este repositorio contiene el **backend** (API REST). El frontend vive en un repositorio
aparte: [Arias-Front](https://github.com/NevadoSantiago/Arias-Front).

## Despliegue

| Entorno | URL | Notas |
|---|---|---|
| Producción | https://ariasbodegon.com | Cliente real. **No usar para pruebas** — ver credenciales de prueba más abajo. |
| Demo / staging | https://demo.ariasbodegon.com | Copia del mismo código, entorno aislado para que se pueda probar sin tocar datos reales. |

Backend desplegado en **Railway**, frontend en **Cloudflare Pages**.

> El entorno de demo **no tiene configurados Resend ni Cloudflare R2**: los emails
> transaccionales se loguean pero no se envían, y la subida de fotos de platos responde 503.
> Esto es intencional, para no mandar emails reales ni tocar el storage de producción.

## Usuario y contraseña de prueba

- **usuario**: bigschool@admin.com
- **password**: test




## Stack tecnológico

**Backend**
- Java 21 + Spring Boot 4
- Spring Security (JWT stateless, access + refresh token)
- Spring Data JPA + Hibernate
- PostgreSQL + Flyway (migraciones versionadas, sin `ddl-auto`)
- Resend (envío de emails transaccionales: bienvenida, recuperación de contraseña, recordatorios)
- Cloudflare R2 (S3-compatible, storage de fotos de platos vía URLs firmadas)
- Apache POI (export de pedidos a Excel)
- springdoc-openapi (Swagger UI)

**Frontend**
- React 19 + TypeScript + Vite
- Tailwind CSS + shadcn/ui (Radix primitives)
- TanStack React Query (estado de servidor) + Zustand (estado de cliente)
- React Hook Form + Zod (formularios y validación)
- React Router
- Recharts (reportes/métricas)
- Desplegado como sitio estático en Cloudflare Pages (Wrangler)

## Estructura del proyecto (backend)

```
src/main/java/com/arias/
├── auth/                 # Login, refresh, first-login, recuperación de contraseña
├── catalog/
│   ├── categories/       # Categorías del menú (con precio por empresa)
│   ├── dishes/           # Platos + calendario de disponibilidad
│   ├── menusections/     # Secciones del menú
│   └── sides/            # Acompañamientos
├── companies/            # Empresas clientes (multi-tenant)
├── contact/              # Formulario de cotización de la landing pública
├── email/                # Envío de mails vía Resend
├── metrics/              # Métricas para el admin de empresa
├── orders/                # Pedidos (empleado) + gestión de pedidos (admin)
├── restaurantconfig/     # Configuración general del restaurante (hora de corte, etc.)
├── uploads/              # Subida de imágenes a Cloudflare R2
├── users/                # Usuarios, empleados, notificaciones
└── common/
    ├── bootstrap/        # Creación del primer SUPER_ADMIN
    ├── config/           # Clock, OpenAPI
    ├── exception/        # Manejo global de errores
    └── security/         # JWT, Spring Security, CORS
```

Cada módulo sigue el mismo patrón: `Controller` → `Service` → `Repository` (JPA), con DTOs
de entrada/salida propios y validación con Bean Validation.

## Funcionalidades principales

- **Autenticación**: login con JWT (access + refresh en cookie httpOnly), primer ingreso con
  seteo de contraseña, recuperación de contraseña por email.
- **Multi-tenant por empresa**: cada empresa cliente tiene su propio precio por categoría y
  su propia nómina de empleados.
- **Panel del restaurante (SUPER_ADMIN)**: alta/edición de platos, categorías, secciones de
  menú y acompañamientos, con soft delete (archivado) y resurrección al recrear con el mismo
  nombre. Calendario de disponibilidad de platos por fecha.
- **Panel de la empresa (COMPANY_ADMIN)**: alta de empleados (con email de bienvenida
  automático), métricas de consumo, export de pedidos a Excel.
- **Empleado**: arma su pedido del día respetando la hora de corte configurada, ve
  sugerencias basadas en pedidos anteriores.
- **Notificaciones**: emails de bienvenida, recordatorio de pedido pendiente (con
  unsubscribe), aviso cuando se deshabilita un plato/side que el empleado tenía pedido.
- **Landing pública**: presentación del bodegón + formulario de cotización para empresas
  interesadas (sin necesidad de login).
- **Documentación de la API**: Swagger UI en `/swagger-ui.html` (spec en `/v3/api-docs`),
  con soporte de JWT Bearer vía el botón "Authorize".

## Instalación y ejecución en local

### Requisitos
- Java 21
- Maven (o el wrapper `./mvnw` incluido)
- Docker (para levantar Postgres)
- Node 20+ (para el frontend)

### Backend

```bash
# 1. Levantar Postgres
docker compose up -d

# 2. Ejecutar la app (perfil dev por default)
./mvnw spring-boot:run
```

La API queda en `http://localhost:8080`. Swagger UI en
`http://localhost:8080/swagger-ui.html`.

Variables de entorno relevantes (todas con default de desarrollo, ver
`src/main/resources/application.yml`):

| Variable | Uso | Default dev |
|---|---|---|
| `JWT_SECRET` | Firma de los tokens | valor de desarrollo (no usar en prod) |
| `CORS_ALLOWED_ORIGINS` | Orígenes permitidos | `http://localhost:5173` |
| `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` | Credenciales del primer SUPER_ADMIN (solo se crea si no existe ninguno) | `admin@arias.com` / `admin123` |
| `RESEND_API_KEY` | Envío real de emails (si está vacío, se loguean pero no se envían) | vacío |
| `R2_ENDPOINT`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_PUBLIC_URL` | Storage de fotos (si están vacíos, el upload responde 503) | vacío |

Con los defaults de dev, la app arranca sin configurar nada y crea automáticamente el
usuario `admin@arias.com` / `admin123` como SUPER_ADMIN.

### Frontend

Ver [Arias-Front](https://github.com/NevadoSantiago/Arias-Front) — resumen rápido:

```bash
npm install
npm run dev
```

En dev, Vite corre en `http://localhost:5173` y proxea `/api` hacia `http://localhost:8080`
(el backend), así que no hace falta configurar nada más.

## Slides

[`docs/slides/Arias-TFM-Slides-Final.pptx`](docs/slides/Arias-TFM-Slides.pptx) — presentación del
proyecto (contexto, funcionalidades, stack, arquitectura, despliegue).

## Video explicativo

https://www.youtube.com/watch?v=g8qNCPL2hLI

## Repositorios

- Backend (este repo): https://github.com/NevadoSantiago/Arias-Api
- Frontend: https://github.com/NevadoSantiago/Arias-Front
