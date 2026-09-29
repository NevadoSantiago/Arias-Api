package com.arias.payments;

/**
 * Puerto del dominio de pagos — única desviación deliberada de la convención
 * "el service llama directo al sistema externo" que sigue el resto del
 * código base (diseño §Enfoque técnico). Se interpone una interfaz porque:
 * <ul>
 *   <li>la propuesta prevé extraer pagos a un microservicio en el futuro;</li>
 *   <li>es la única forma de testear el flujo de webhook sin golpear la API
 *       real de Mercado Pago.</li>
 * </ul>
 *
 * <p>Los tipos {@code com.mercadopago.*} del SDK no deben salir del paquete
 * {@code com.arias.payments.mercadopago} — este puerto y sus tipos asociados
 * ({@link CheckoutRequest}, {@link CheckoutSession}, {@link PaymentSnapshot},
 * {@link PaymentStatus}) son el único contrato visible para el resto del
 * dominio.
 *
 * <p>Deliberadamente sin {@code refund()}: el producto nunca inicia una
 * devolución de dinero (decisión de producto, settled con el usuario). El
 * puerto queda extensible para agregarla sin tocar a los consumidores.
 */
public interface PaymentGateway {

    /**
     * Falla rápido (503 {@code mercadopago-disabled}) si la integración no está
     * habilitada/configurada. Se llama ANTES de crear cualquier fila o reservar
     * stock/saldo para un checkout (unidad B13.2): sin esto, con Mercado Pago
     * apagado cada intento de pago directo commitearía un pedido y lo cancelaría.
     */
    void requireAvailable();

    /** Crea la preferencia de checkout y devuelve la URL a la que redirigir al cliente. */
    CheckoutSession createCheckout(CheckoutRequest request);

    /** Consulta el estado real de un pago — fuente de verdad, nunca el cuerpo del webhook. */
    PaymentSnapshot getPayment(String paymentId);

    /**
     * Verifica la firma HMAC del header {@code x-signature} de un webhook
     * entrante. Comparación en tiempo constante.
     */
    boolean verifySignature(String xSignature, String xRequestId, String dataId);

    /**
     * Todos los pagos asociados a un {@code external_reference}
     * (unidad 11, {@code PaymentReconciliationScheduler}), del más reciente al
     * más viejo — cubre el webhook perdido: una compra sin notificación se
     * re-consulta acá en vez de necesitar el {@code payment_id}, que todavía
     * no se conoce. Un checkout admite varios intentos (una tarjeta rechazada y
     * un reintento aprobado): devolver sólo uno podía dejar afuera el aprobado
     * (unidad B15.1), así que el llamador elige entre todos. Lista vacía si
     * Mercado Pago no tiene ningún pago para esa referencia.
     */
    java.util.List<PaymentSnapshot> findAllByExternalReference(String externalReference);
}
