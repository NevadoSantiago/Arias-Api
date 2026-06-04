package com.arias.contact;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoint público (sin sesión) para que las empresas pidan cotización
 * desde la landing. Dispara un mail a Arias vía Resend. No persiste en BD.
 */
@RestController
@RequestMapping("/api/v1/contact")
public class ContactController {

    private final QuoteEmails quoteEmails;

    public ContactController(QuoteEmails quoteEmails) {
        this.quoteEmails = quoteEmails;
    }

    @PostMapping("/quote")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void quote(@Valid @RequestBody QuoteRequest req) {
        // El envío es async/best-effort dentro del EmailService. Respondemos 202.
        quoteEmails.sendQuoteRequest(req);
    }
}
