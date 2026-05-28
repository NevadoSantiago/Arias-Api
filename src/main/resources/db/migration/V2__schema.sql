-- =====================================================================
-- Arias — Schema completo
-- =====================================================================
-- Orden de creación respetando dependencias de FK:
--   1. category (self-ref)
--   2. menu_section
--   3. side
--   4. company (→ category)
--   5. users (→ company, category)
--   6. dish (→ category, menu_section)
--   7. dish_side (pivot: dish + side)
--   8. daily_choice (→ users, company, dish, side) + snapshots
--   9. refresh_token (→ users)
--  10. restaurant_config (singleton, sin deps)
-- =====================================================================


-- ─── Categorías (tier de acceso: Premium, Básico, Pescado, etc.) ────────
-- Self-referencing: árbol "ves tu categoría + descendientes"
CREATE TABLE category (
    id            BIGSERIAL PRIMARY KEY,
    nombre        VARCHAR(100) NOT NULL,
    parent_id     BIGINT REFERENCES category(id),
    orden_display INT NOT NULL DEFAULT 0,
    enabled       BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_category_nombre UNIQUE (nombre)
);
CREATE INDEX idx_category_parent ON category(parent_id);


-- ─── Secciones del menú (Carnes, Minutas, Pastas, Sandwich, Ensaladas) ──
CREATE TABLE menu_section (
    id            BIGSERIAL PRIMARY KEY,
    nombre        VARCHAR(100) NOT NULL,
    orden_display INT NOT NULL DEFAULT 0,
    enabled       BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_menu_section_nombre UNIQUE (nombre)
);


-- ─── Sides (guarniciones + salsas, unificadas por columna `tipo`) ───────
CREATE TABLE side (
    id         BIGSERIAL PRIMARY KEY,
    nombre     VARCHAR(100) NOT NULL,
    tipo       VARCHAR(20) NOT NULL,
    enabled    BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_side_nombre UNIQUE (nombre),
    CONSTRAINT chk_side_tipo CHECK (tipo IN ('GUARNICION', 'SALSA'))
);


-- ─── Company (cliente del resto, hace pedidos para sus empleados) ───────
CREATE TABLE company (
    id                   BIGSERIAL PRIMARY KEY,
    nombre               VARCHAR(150) NOT NULL,
    cuit                 VARCHAR(13) NOT NULL,
    calle                VARCHAR(200) NOT NULL,
    altura               VARCHAR(20) NOT NULL,
    piso                 VARCHAR(20),
    hora_entrega         TIME NOT NULL,
    categoria_default_id BIGINT NOT NULL REFERENCES category(id),
    enabled              BOOLEAN NOT NULL DEFAULT true,
    created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_company_cuit UNIQUE (cuit)
);


-- ─── Users (admins del resto, admins de empresa, empleados) ─────────────
-- Nombre de tabla "users" porque "user" es reservado en PostgreSQL
CREATE TABLE users (
    id             BIGSERIAL PRIMARY KEY,
    email          VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(255),
    first_name     VARCHAR(100),
    last_name      VARCHAR(100),
    role           VARCHAR(30) NOT NULL,
    company_id     BIGINT REFERENCES company(id),
    category_id    BIGINT REFERENCES category(id),
    active         BOOLEAN NOT NULL DEFAULT true,
    first_login_at TIMESTAMP,
    last_login_at  TIMESTAMP,
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT chk_users_role CHECK (role IN ('SUPER_ADMIN', 'COMPANY_ADMIN', 'EMPLOYEE'))
);
CREATE INDEX idx_users_company ON users(company_id);
CREATE INDEX idx_users_role_active ON users(role, active);


-- ─── Dish (platos del menú) ─────────────────────────────────────────────
CREATE TABLE dish (
    id                   BIGSERIAL PRIMARY KEY,
    nombre               VARCHAR(150) NOT NULL,
    descripcion          TEXT,
    foto_url             VARCHAR(500),
    category_id          BIGINT NOT NULL REFERENCES category(id),
    menu_section_id      BIGINT NOT NULL REFERENCES menu_section(id),
    side_type            VARCHAR(20),
    enabled              BOOLEAN NOT NULL DEFAULT true,
    stock_diario_default INT NOT NULL DEFAULT 0,
    stock_actual         INT NOT NULL DEFAULT 0,
    created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_dish_side_type CHECK (side_type IS NULL OR side_type IN ('GUARNICION', 'SALSA')),
    CONSTRAINT chk_dish_stock CHECK (stock_actual >= 0 AND stock_diario_default >= 0)
);
CREATE INDEX idx_dish_category ON dish(category_id);
CREATE INDEX idx_dish_menu_section ON dish(menu_section_id);
CREATE INDEX idx_dish_enabled_stock ON dish(enabled, stock_actual);


-- ─── DishSide (pivot: qué sides puede llevar cada plato) ────────────────
CREATE TABLE dish_side (
    dish_id BIGINT NOT NULL REFERENCES dish(id) ON DELETE CASCADE,
    side_id BIGINT NOT NULL REFERENCES side(id) ON DELETE CASCADE,
    PRIMARY KEY (dish_id, side_id)
);
CREATE INDEX idx_dish_side_side ON dish_side(side_id);


-- ─── DailyChoice (el pedido del empleado) ───────────────────────────────
-- Constraint UNIQUE (user_id, fecha) = un solo pedido por empleado por día
-- Las columnas dish_nombre / dish_categoria / side_nombre / hora_entrega
-- son SNAPSHOTS: una vez confirmado, no se tocan aunque el catálogo cambie
CREATE TABLE daily_choice (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id),
    company_id      BIGINT NOT NULL REFERENCES company(id),
    fecha           DATE NOT NULL,
    dish_id         BIGINT NOT NULL REFERENCES dish(id),
    side_id         BIGINT REFERENCES side(id),
    notas           TEXT,
    estado          VARCHAR(20) NOT NULL,
    dish_nombre     VARCHAR(150) NOT NULL,
    dish_categoria  VARCHAR(100) NOT NULL,
    side_nombre     VARCHAR(100),
    hora_entrega    TIME NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at    TIMESTAMP,
    delivered_at    TIMESTAMP,
    CONSTRAINT uq_daily_choice_user_fecha UNIQUE (user_id, fecha),
    CONSTRAINT chk_daily_choice_estado CHECK (estado IN ('PENDIENTE', 'CONFIRMADO', 'ENTREGADO'))
);
CREATE INDEX idx_daily_choice_company_fecha_estado ON daily_choice(company_id, fecha, estado);
CREATE INDEX idx_daily_choice_fecha_estado ON daily_choice(fecha, estado);


-- ─── RefreshToken (persistido para permitir revocación) ─────────────────
-- token_hash = SHA-256 del token real; NUNCA guardamos el token en claro
CREATE TABLE refresh_token (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash)
);
CREATE INDEX idx_refresh_token_user ON refresh_token(user_id);


-- ─── RestaurantConfig (singleton, una sola fila id=1) ───────────────────
CREATE TABLE restaurant_config (
    id         BIGINT PRIMARY KEY,
    hora_corte TIME NOT NULL,
    timezone   VARCHAR(50) NOT NULL DEFAULT 'America/Argentina/Buenos_Aires',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_restaurant_config_singleton CHECK (id = 1)
);


-- =====================================================================
-- Seeds — datos iniciales
-- =====================================================================

-- Categorías (tier de acceso): Premium > Básico
INSERT INTO category (nombre, parent_id, orden_display) VALUES
    ('Premium', NULL, 1);

INSERT INTO category (nombre, parent_id, orden_display) VALUES
    ('Básico', (SELECT id FROM category WHERE nombre = 'Premium'), 2);

-- Secciones del menú (orden importa: define el orden de las pills)
INSERT INTO menu_section (nombre, orden_display) VALUES
    ('Carnes',    1),
    ('Minutas',   2),
    ('Pastas',    3),
    ('Sandwich',  4),
    ('Ensaladas', 5);

-- Sides básicos (guarniciones + salsas)
INSERT INTO side (nombre, tipo) VALUES
    ('Papas fritas',          'GUARNICION'),
    ('Puré rústico',          'GUARNICION'),
    ('Ensalada mixta',        'GUARNICION'),
    ('Verduras grilladas',    'GUARNICION'),
    ('Salsa bolognesa',       'SALSA'),
    ('Salsa filetto',         'SALSA'),
    ('Salsa de champignones', 'SALSA'),
    ('Salsa al limón',        'SALSA');

-- Config del restaurant (hora de corte por defecto)
INSERT INTO restaurant_config (id, hora_corte) VALUES (1, '10:00');
