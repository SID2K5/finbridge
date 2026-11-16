package com.finbridge.simulator.ws;

import com.finbridge.payments.v1.PaymentFault;
import java.util.Locale;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.soap.SoapBody;
import org.springframework.ws.soap.SoapFault;
import org.springframework.ws.soap.SoapMessage;

/**
 * Writes a SOAP fault whose detail carries the contract's PaymentFault element:
 *
 *   <soap:Fault>
 *     <faultcode>soap:Client</faultcode>            (or soap:Server)
 *     <faultstring>...</faultstring>
 *     <detail><PaymentFault><ErrorCode>UNKNOWN_TRANSACTION</ErrorCode><Message>...</Message></PaymentFault></detail>
 *   </soap:Fault>
 *
 * Callers switch on ErrorCode, never on the human-readable text.
 */
final class FaultWriter {

    private FaultWriter() {
    }

    static void write(MessageContext context, Jaxb2Marshaller marshaller, boolean serverFault, String errorCode,
                      String message) {
        SoapBody body = ((SoapMessage) context.getResponse()).getSoapBody();
        SoapFault fault = serverFault
                ? body.addServerOrReceiverFault(message, Locale.ENGLISH)
                : body.addClientOrSenderFault(message, Locale.ENGLISH);
        PaymentFault detail = new PaymentFault();
        detail.setErrorCode(errorCode);
        detail.setMessage(message);
        marshaller.marshal(detail, fault.addFaultDetail().getResult());
    }
}
