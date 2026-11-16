package com.finbridge.integration.soap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.client.support.interceptor.ClientInterceptor;
import org.springframework.ws.transport.http.HttpUrlConnectionMessageSender;
import org.springframework.xml.xsd.SimpleXsdSchema;

/**
 * The SOAP client for the payment service.
 *
 *   - JAXB classes come from the contract (wsdl/payments.xsd), generated at build time.
 *   - Every outgoing request and incoming response is validated against that schema.
 *   - Connect and read timeouts are explicit; a payment call never waits forever.
 *   - XML parsing refuses DTDs and external entities (Jaxb2Marshaller's defaults, stated
 *     here on purpose so nobody switches them on by accident).
 */
@Configuration
public class SoapClientConfig {

    @Bean
    public Jaxb2Marshaller paymentsMarshaller() {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath("com.finbridge.payments.v1");
        marshaller.setSupportDtd(false);
        marshaller.setProcessExternalEntities(false);
        return marshaller;
    }

    @Bean
    public SoapExchangeRecorder soapExchangeRecorder() {
        return new SoapExchangeRecorder();
    }

    @Bean
    public WebServiceTemplate paymentsWebServiceTemplate(PaymentProperties properties,
                                                         Jaxb2Marshaller paymentsMarshaller,
                                                         SoapExchangeRecorder recorder) throws Exception {
        SimpleXsdSchema schema = new SimpleXsdSchema(new ClassPathResource("wsdl/payments.xsd"));
        schema.afterPropertiesSet();
        ContractValidatingInterceptor validator = new ContractValidatingInterceptor();
        validator.setXsdSchema(schema);
        validator.setValidateRequest(true);
        validator.setValidateResponse(true);
        validator.afterPropertiesSet();

        HttpUrlConnectionMessageSender sender = new HttpUrlConnectionMessageSender();
        sender.setConnectionTimeout(properties.connectTimeout());
        sender.setReadTimeout(properties.readTimeout());

        WebServiceTemplate template = new WebServiceTemplate(paymentsMarshaller);
        template.setDefaultUri(properties.wsUrl());
        template.setMessageSender(sender);
        // The recorder is first, so it sees the request even when validation then refuses it.
        template.setInterceptors(new ClientInterceptor[] {recorder, validator});
        return template;
    }
}
