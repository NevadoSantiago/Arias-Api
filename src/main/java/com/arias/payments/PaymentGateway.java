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

    /** Crea la preferencia de checkout y devuelve la URL a la que redirigir al cliente. */
    CheckoutSession createCheckout(CheckoutRequest request);

    /** Consulta el estado real de un pago — fuente de verdad, nunca el cuerpo del webhook. */
    PaymentSnapshot getPayment(String paymentId);

    /**
     * Verifica la firma HMAC del header {@code x-signature} de un webhook
     * entrante. Comparación en tiempo constante.
     */
    boolean verifySignature(String xSignature, String xRequestId, String dataId);
}
