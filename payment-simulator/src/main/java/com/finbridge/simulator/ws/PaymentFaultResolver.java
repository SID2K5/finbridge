package com.finbridge.simulator.ws;

import com.finbridge.simulator.domain.PaymentFaultException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.stereotype.Component;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.endpoint.AbstractEndpointExceptionResolver;

/**
 * Turns exceptions into contract faults. Anything unexpected becomes a Server fault with
 * the code INTERNAL_ERROR and no internal detail (no class name, no stack trace).
 */
@Component
public class PaymentFaultResolver extends AbstractEndpointExceptionResolver {

    private static final Logger log = LoggerFactory.getLogger(PaymentFaultResolver.class);

    private final Jaxb2Marshaller marshaller;

    public PaymentFaultResolver(Jaxb2Marshaller marshaller) {
        this.marshaller = marshaller;
        setOrder(Ordered.HIGHEST_PRECEDENCE);
    }

    @Override
    protected boolean resolveExceptionInternal(MessageContext messageContext, Object endpoint, Exception ex) {
        if (ex instanceof PaymentFaultException fault) {
            FaultWriter.write(messageContext, marshaller, fault.isServerFault(), fault.getErrorCode(),
                    fault.getMessage());
        } else {
            log.error("Unexpected error while handling a SOAP request", ex);
            FaultWriter.write(messageContext, marshaller, true, "INTERNAL_ERROR",
                    "The payment service could not process the request");
        }
        return true;
    }
}
